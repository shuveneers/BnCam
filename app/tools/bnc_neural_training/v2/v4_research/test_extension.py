"""Continuation invariants at the 100k boundary; CPU fixtures, no corpus or GPU."""
import ast
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import numpy as np
import torch

from ..checkpoint import capture, load, save, GlobalCosine
from ..losses import reconstruction_loss
from ..training_safety import checked_step, probe
from .model import ResearchModel
from . import run as original
from . import extend as extension

torch.set_num_threads(2)


def same(a,b):
    if isinstance(a,torch.Tensor):return isinstance(b,torch.Tensor) and torch.equal(a,b)
    if isinstance(a,dict):return a.keys()==b.keys() and all(same(a[k],b[k]) for k in a)
    if isinstance(a,(tuple,list)):return type(a)==type(b) and len(a)==len(b) and all(same(x,y) for x,y in zip(a,b))
    return a==b


class ExtensionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name);self.root=self.directory/'extension'
        self.corpus=dict(sha256='extension-test-corpus',counts={},revision='test')
        self.archive=self.directory/'unused.npz'
        self.manifest=self.directory/'corpus.json';self.manifest.write_text('{"dedupFinalized":true}')
        m,opt,schedule,scaler,generator,state=original.fresh('N','cpu',self.corpus,self.archive)
        self.a=torch.rand(2,4,12,14);self.b=torch.rand(2,3,24,28)
        schedule.apply(0);reconstruction_loss(self.a,m(self.a),self.b,True)[0].backward()
        checked_step(m,opt);schedule.committed();opt.zero_grad(set_to_none=True)
        # A synthetic committed boundary, sufficient to test exact state copying.
        for value in opt.state.values():value['step'].fill_(100000)
        schedule.next_step=100000
        for group in opt.param_groups:group['lr']=GlobalCosine.value(99999)
        self.source_state=capture(m,opt,scaler,schedule,100000,[],.02,state['provenance'],7.,
            SimpleNamespace(**state['trainingArguments']),generator)
        self.source=self.directory/'source.pt';save(self.source,self.source_state)
        self.digest=hashlib.sha256(self.source.read_bytes()).hexdigest()
        p=patch.multiple(extension,SOURCE=self.source,SOURCE_SHA=self.digest)
        p.start();self.addCleanup(p.stop)

    def prepare(self):return extension.prepare(self.root,'N','cpu',self.corpus,self.archive)

    def test_fork_preserves_every_training_field_and_source_bytes(self):
        _,_,_,_,_,_,fork=self.prepare()
        for key in self.source_state:
            if key!='provenance':self.assertTrue(same(fork[key],self.source_state[key]),key)
        for key in self.source_state['provenance']:
            if key!='researchCodeIdentity':
                self.assertTrue(same(fork['provenance'][key],self.source_state['provenance'][key]),key)
        self.assertEqual(fork['sampler']['nextSampleIndex'],3200000)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),self.digest)
        extension.verify(fork,'N',self.corpus)
        self.assertEqual(fork['provenance']['continuation']['sourceCheckpointSHA256'],self.digest)

    def test_next_update_matches_source_with_preserved_optimizer_moments(self):
        _,model,opt,schedule,_,_,_=self.prepare()
        reference=ResearchModel('N');reference.load_state_dict(self.source_state['model'])
        other=torch.optim.AdamW(reference.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
        other.load_state_dict(copy.deepcopy(self.source_state['optimizer']))
        ref_schedule=GlobalCosine(other);ref_schedule.load_state_dict(self.source_state['scheduler'])
        for m,o,s in ((model,opt,schedule),(reference,other,ref_schedule)):
            o.zero_grad(set_to_none=True);s.apply(100000)
            reconstruction_loss(self.a,m(self.a),self.b,True)[0].backward()
            checked_step(m,o);s.committed()
        self.assertTrue(same(model.state_dict(),reference.state_dict()))
        self.assertTrue(same(opt.state_dict(),other.state_dict()))
        self.assertEqual(schedule.next_step,100001)
        self.assertEqual(opt.param_groups[0]['lr'],3e-5)

    def test_wrong_source_digest_and_candidate_are_rejected_before_writes(self):
        with patch.object(extension,'SOURCE_SHA','0'*64):
            with self.assertRaisesRegex(ValueError,'reviewed 100k'):self.prepare()
        self.assertFalse(self.root.exists())
        with self.assertRaisesRegex(ValueError,'only reviewed N'):
            extension.prepare(self.root,'S','cpu',self.corpus,self.archive)
        self.assertFalse(self.root.exists())

    def test_modified_original_code_is_rejected(self):
        identity=original.code_identity();identity['research']['training_safety.py']='changed'
        with patch.object(original,'code_identity',return_value=identity):
            with self.assertRaisesRegex(ValueError,'code changed'):self.prepare()
        self.assertFalse(self.root.exists())

    def test_scheduler_remains_at_original_endpoint_without_restart(self):
        self.assertGreater(GlobalCosine.value(99999),3e-5)
        for step in (100000,100001,125000,150000,200000):
            self.assertEqual(GlobalCosine.value(step),3e-5)
        _,_,_,schedule,_,_,_=self.prepare()
        self.assertEqual(schedule.state_dict()['decaySteps'],100000)

    def test_original_output_and_ancestors_are_protected(self):
        for root in (original.ROOT,original.ROOT/'N/new',original.ROOT.parent):
            with self.assertRaisesRegex(ValueError,'disjoint'):
                extension.protected_root(root)
        self.assertEqual(extension.protected_root(self.root),self.root.resolve())

    def test_diagnose_is_read_only_for_checkpoint_and_source(self):
        out,*_=self.prepare();latest=out/'checkpoints/latest.pt'
        before=hashlib.sha256(latest.read_bytes()).hexdigest()
        checks=lambda m,o,a:dict(stress=probe(m,o,self.a,self.b,'stress',True),
            edge=probe(m,None,self.a,self.b,'edge',False))
        with patch.object(extension,'read_corpus',return_value=(dict(train=[],validation=[]),self.corpus)), \
             patch.object(extension,'edge_input',return_value=(self.a,self.b)), \
             patch.object(extension,'sentinels',side_effect=checks):
            extension.run('N',self.root,'cpu','diagnose',None,self.manifest,self.archive)
        self.assertEqual(hashlib.sha256(latest.read_bytes()).hexdigest(),before)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),self.digest)
        self.assertEqual(load(latest)['step'],100000)

    def test_bounds_and_latched_stop_block_training_without_checkpoint_changes(self):
        out,*_=self.prepare();latest=out/'checkpoints/latest.pt'
        before=hashlib.sha256(latest.read_bytes()).hexdigest();prepare=extension.prepare
        with patch.object(extension,'setup'), \
             patch.object(extension,'read_corpus',return_value=(dict(train=[],validation=[]),self.corpus)), \
             patch.object(extension,'edge_input',return_value=(self.a,self.b)), \
             patch.object(extension,'prepare',side_effect=lambda r,c,d,k,a:prepare(r,c,'cpu',k,a)):
            for target in (100000,125001,200001):
                with self.assertRaisesRegex(ValueError,'extension target'):
                    extension.run('N',self.root,'cuda','train',target,self.manifest,self.archive)
            (out/'status.json').write_text(json.dumps(dict(status='RESEARCH_STOP')))
            with self.assertRaisesRegex(ValueError,'latched'):
                extension.run('N',self.root,'cuda','train',125000,self.manifest,self.archive)
        self.assertEqual(hashlib.sha256(latest.read_bytes()).hexdigest(),before)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),self.digest)

    def test_extension_code_change_blocks_resume(self):
        self.prepare();identity=extension.code_identity();identity['extension']['codeSHA256']='changed'
        with patch.object(extension,'code_identity',return_value=identity):
            with self.assertRaisesRegex(ValueError,'identity changed'):self.prepare()

    def test_training_updates_and_safety_functions_match_original_ast(self):
        a=ast.parse(Path(original.__file__).read_text());b=ast.parse(Path(extension.__file__).read_text())
        af={n.name:n for n in a.body if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef))}
        bf={n.name:n for n in b.body if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef))}
        for name in ('sentinels','fail','session_lock','setup','preserved_rng','qualification_resources','evaluate_only'):
            self.assertEqual(ast.dump(af[name]),ast.dump(bf[name]),name)
        aw=next(n for n in ast.walk(af['run']) if isinstance(n,ast.While))
        bw=next(n for n in ast.walk(bf['run']) if isinstance(n,ast.While))
        self.assertEqual(ast.dump(aw),ast.dump(bw))


if __name__=='__main__':unittest.main()
