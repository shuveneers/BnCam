"""Contract and real failure-mode tests; no corpus/GPU dependency."""
import copy
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from ...contracts import pack, merge, measured_mask
from ...model import infer_tiled
from ..model import MissingRgbAdapter, missing_rgb
from ..checkpoint import save, load, capture
from ..training_safety import UnsafeUpdate, ResearchRegression, enforce_guard, ScopedObserver, checked_step, guard, probe
from .model import ResearchModel, FeatureNorm
from .run import session_lock, protected_root, fresh, verify, fail, preserved_rng
from types import SimpleNamespace

torch.set_num_threads(2)


class ResearchTests(unittest.TestCase):
    def test_matched_fresh_weights_and_new_identity(self):
        torch.manual_seed(823109);a=ResearchModel('N')
        torch.manual_seed(823109);b=ResearchModel('S')
        self.assertTrue(all(torch.equal(v,b.state_dict()[k]) for k,v in a.state_dict().items()))
        self.assertEqual(sum(p.numel() for p in a.parameters()),27632)
        self.assertEqual(a.config.parameter_count,27632)
        self.assertNotEqual(a.config.description(),b.config.description())
        self.assertEqual(a.config.halo,20)

    def test_exact_measured_samples_all_patterns_offsets_and_odd_size(self):
        raw=np.random.default_rng(19).uniform(0,4,(31,35)).astype('f4')
        for variant in ('N','S'):
            torch.manual_seed(17);model=ResearchModel(variant).eval()
            for pattern in ('RGGB','GRBG','GBRG','BGGR'):
                for offset in ((0,0),(1,0),(0,1),(1,1)):
                    packed,g=pack(raw,pattern,crop_offset=offset)
                    a=torch.from_numpy(packed.transpose(2,0,1).copy())[None]
                    with torch.no_grad():
                        missing=missing_rgb(a,model(a))[0].permute(1,2,0).numpy()
                    output=merge(raw,missing,g)
                    self.assertTrue(np.array_equal(output[measured_mask(g)].view('u4'),raw.ravel().view('u4')))

    def test_tiling_matches_full_without_spatial_normalization(self):
        for variant in ('N','S'):
            torch.manual_seed(9);m=MissingRgbAdapter(ResearchModel(variant).eval())
            x=torch.rand(1,4,57,61)
            with torch.no_grad():
                reference=m(x);tiled=infer_tiled(m,x,inner=24)
            self.assertLess(float((reference-tiled).abs().max()),1e-5)

    def test_optimizer_overflow_rejected_before_mutation(self):
        m=torch.nn.Linear(1,1,bias=False)
        opt=torch.optim.AdamW(m.parameters(),lr=.001,betas=(.9,.99))
        m.weight.grad=torch.full_like(m.weight,6e30)
        before=copy.deepcopy(m.state_dict());state=copy.deepcopy(opt.state_dict())
        with self.assertRaises(UnsafeUpdate) as caught:
            checked_step(m,opt)
        self.assertEqual(caught.exception.stage,'adamw_square')
        self.assertTrue(torch.equal(m.weight,before['weight']))
        self.assertEqual(opt.state_dict(),state)

    def test_post_update_failure_is_detected_immediately(self):
        m=torch.nn.Linear(1,1,bias=False)
        class BadAdamW(torch.optim.AdamW):
            def step(self,closure=None):
                super().step(closure)
                self.state[next(iter(m.parameters()))]['exp_avg_sq'].fill_(float('inf'))
        opt=BadAdamW(m.parameters(),lr=.001,betas=(.9,.99))
        m.weight.grad=torch.ones_like(m.weight)
        with self.assertRaises(UnsafeUpdate) as caught:
            checked_step(m,opt)
        self.assertEqual(caught.exception.stage,'post_update_state')

    def test_probe_isolation_preserves_live_gradients_state_and_scope(self):
        torch.manual_seed(3);m=ResearchModel('N').train()
        opt=torch.optim.AdamW(m.parameters(),lr=.001,betas=(.9,.99))
        for p in m.parameters():p.grad=torch.ones_like(p)
        old=copy.deepcopy(m.state_dict());grads=[p.grad.clone() for p in m.parameters()]
        observer=ScopedObserver(m,'training_only')
        a=torch.rand(1,4,12,14);t=torch.rand(1,3,24,28)
        result=probe(m,opt,a,t,'diagnostic',True)
        self.assertTrue(m.training)
        self.assertEqual(observer.result()['modelOutputMax'],0.)
        self.assertTrue(all(torch.equal(v,old[k]) for k,v in m.state_dict().items()))
        self.assertTrue(all(torch.equal(p.grad,g) for p,g in zip(m.parameters(),grads)))
        self.assertEqual(opt.state_dict()['state'],{})
        self.assertTrue(result['optimizerPreflightPassed'])
        observer.close()

    def test_regression_guards_detect_c_edge_and_stress(self):
        reference=dict(stress=dict(finite=True,optimizerPreflightPassed=True,loss=.128),
                       edge=dict(finite=True,metrics=dict(missing_rgb_l1=.005727)))
        current=copy.deepcopy(reference)
        current['stress']['loss']=3.657
        current['edge']['metrics']['missing_rgb_l1']=.0599
        result=guard(current,reference)
        self.assertEqual(len(result['failures']),2)
        self.assertFalse(result['passed'])
        self.assertFalse(result['releaseQualification'])
        self.assertTrue(result['numericalPassed'])
        with self.assertRaises(ResearchRegression):enforce_guard(result)

    def test_training_root_and_cross_process_lock_are_protected(self):
        with self.assertRaises(ValueError):protected_root('build/bnc-neural-v2/v3-phase-b')
        with tempfile.TemporaryDirectory() as temp:
            with session_lock(Path(temp)):
                with self.assertRaises(OSError):
                    with session_lock(Path(temp)):pass

    def test_checkpoint_resume_preserves_next_update_and_sampler(self):
        corpus=dict(sha256='test-corpus',counts={},revision='unit-test')
        m,opt,schedule,scaler,generator,state=fresh('N','cpu',corpus,Path('not-used.npz'))
        args=SimpleNamespace(**state['trainingArguments'])
        a=torch.rand(1,4,12,14);truth=torch.rand(1,3,24,28)
        from ..losses import reconstruction_loss
        schedule.apply(0)
        reconstruction_loss(a,m(a),truth,True)[0].backward()
        checked_step(m,opt);schedule.committed();opt.zero_grad(set_to_none=True)
        saved=capture(m,opt,scaler,schedule,1,[],float('inf'),state['provenance'],0.,args,generator)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'latest.pt';save(path,saved);resumed=load(path)
        verify(resumed,'N',corpus)
        self.assertEqual(resumed['sampler']['nextSampleIndex'],32)
        b=ResearchModel('N');b.load_state_dict(resumed['model'],strict=True)
        other=torch.optim.AdamW(b.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
        other.load_state_dict(resumed['optimizer'])
        from ..checkpoint import GlobalCosine
        next_schedule=GlobalCosine(other);next_schedule.load_state_dict(resumed['scheduler'])
        for model,optimizer,scheduler in ((m,opt,schedule),(b,other,next_schedule)):
            scheduler.apply(1);optimizer.zero_grad(set_to_none=True)
            reconstruction_loss(a,model(a),truth,True)[0].backward()
            checked_step(model,optimizer);scheduler.committed()
        self.assertTrue(all(torch.equal(v,b.state_dict()[k]) for k,v in m.state_dict().items()))
        self.assertEqual(next_schedule.next_step,2)

    def test_rejected_update_does_not_overwrite_last_checkpoint(self):
        import hashlib,json
        with tempfile.TemporaryDirectory() as temp:
            out=Path(temp);latest=out/'latest.pt';latest.write_bytes(b'preserve this checkpoint')
            original=hashlib.sha256(latest.read_bytes()).hexdigest()
            fail(out,UnsafeUpdate('adamw_square','unsafe gradient'),29001,latest)
            self.assertEqual(hashlib.sha256(latest.read_bytes()).hexdigest(),original)
            event=json.loads((out/'status.json').read_text())
            self.assertEqual(event['numericalFailures'],1)
            self.assertEqual(event['status'],'RESEARCH_STOP')
            self.assertEqual(len(list((out/'failures').glob('*.json'))),1)

    def test_research_graph_cannot_be_exported_as_old_schema(self):
        from ...package import export_reference,PackageError
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'candidate.bncmodel'
            with self.assertRaises(PackageError):
                export_reference(ResearchModel('N'),path,{})
            self.assertFalse(path.exists())

    def test_evaluation_does_not_consume_resume_rng(self):
        import random
        torch.manual_seed(9);np.random.seed(9);random.seed(9)
        from ..checkpoint import rng_state
        before=rng_state()
        with preserved_rng():
            torch.rand(8);np.random.random(8);random.random()
        after=rng_state()
        self.assertTrue(torch.equal(before['torchCPU'],after['torchCPU']))
        self.assertEqual(before['numpy'],after['numpy'])
        self.assertEqual(before['python'],after['python'])

    def test_prepare_and_diagnose_never_advance_or_replace_checkpoint(self):
        from unittest.mock import patch
        import hashlib,json
        from . import run as runner
        a=torch.rand(4,12,14);b=torch.rand(3,24,28)
        corpus=dict(sha256='mock-corpus',counts={},revision='workflow-test')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'research';manifest=Path(temp)/'corpus.json'
            manifest.write_text(json.dumps(dict(dedupFinalized=True)))
            with patch.object(runner,'read_corpus',return_value=(dict(train=[],validation=[]),corpus)), \
                 patch.object(runner,'edge_input',return_value=(a,b)), \
                 patch('app.tools.bnc_neural_training.v2.v4_research.run.sentinels',
                       side_effect=lambda model,opt,archive: dict(stress=probe(model,opt,a,b,'stress',True),
                                                                 edge=probe(model,None,a,b,'edge',False))):
                runner.run('N',root,'cpu','prepare',None,manifest,Path(temp)/'unused.npz')
                checkpoint=root/'N/checkpoints/latest.pt'
                before=hashlib.sha256(checkpoint.read_bytes()).hexdigest()
                runner.run('N',root,'cpu','diagnose',None,manifest,Path(temp)/'unused.npz')
                self.assertEqual(hashlib.sha256(checkpoint.read_bytes()).hexdigest(),before)
                self.assertEqual(load(checkpoint)['step'],0)
                self.assertEqual(len(list((root/'N').glob('diagnosis_step_*.json'))),1)


if __name__=='__main__':
    unittest.main()
