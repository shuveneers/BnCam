from pathlib import Path
from argparse import Namespace
import copy
import os
import random
import tempfile
import unittest
import numpy as np
import torch
from torch.utils.data import DataLoader
from PIL import Image
import hashlib
from ..checkpoint import GlobalCosine,capture,load,persist,restore_rng,save
from ..model import BncNeuralV2
from ..losses import reconstruction_loss
from ..session_train import session_target
from ..data import TrainingData

os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')


class CheckpointTests(unittest.TestCase):
    def test_sample_resume_discards_prefetch_without_skipping_or_repeating(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'source.png'
            pixels=np.random.default_rng(55).integers(0,256,(512,512,3),dtype=np.uint8)
            Image.fromarray(pixels).save(path)
            records=[dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),group='test')]
            expected=[TrainingData(records,8,seed=914)[i] for i in range(8)]
            first=iter(DataLoader(TrainingData(records,8,seed=914),batch_size=2,num_workers=2,
                prefetch_factor=2,generator=torch.Generator().manual_seed(1)))
            a,b=next(first);first._shutdown_workers()
            for i in range(2):
                self.assertTrue(torch.equal(a[i],expected[i][0]));self.assertTrue(torch.equal(b[i],expected[i][1]))
            resumed=iter(DataLoader(TrainingData(records,6,seed=914,start_index=2),batch_size=2,num_workers=2,
                prefetch_factor=2,generator=torch.Generator().manual_seed(999)))
            index=2
            for a,b in resumed:
                for j in range(2):
                    self.assertTrue(torch.equal(a[j],expected[index][0]));self.assertTrue(torch.equal(b[j],expected[index][1]));index+=1
            self.assertEqual(index,8)

    def test_session_boundaries_are_global_not_restarted(self):
        self.assertEqual(session_target(0),10000)
        self.assertEqual(session_target(1880),10000)
        self.assertEqual(session_target(10000),20000)
        self.assertEqual(session_target(1880,20000),20000)
        for step,target in ((1880,1880),(20000,10000),(149999,150001)):
            with self.assertRaises(ValueError):session_target(step,target)

    def roundtrip(self,device):
        torch.set_num_threads(2);torch.use_deterministic_algorithms(True)
        torch.backends.cudnn.benchmark=False;torch.backends.cudnn.deterministic=True
        torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
        random.seed(94);np.random.seed(94);torch.manual_seed(94)
        if device=='cuda':torch.cuda.manual_seed_all(94)
        model=BncNeuralV2().to(device)
        optimizer=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
        scaler=torch.amp.GradScaler('cuda',enabled=device=='cuda')
        scheduler=GlobalCosine(optimizer,99999)
        truth=torch.rand((1,3,32,32),generator=torch.Generator().manual_seed(791)).to(device)
        packed=torch.stack((truth[:,0,0::2,0::2],truth[:,1,0::2,1::2],truth[:,1,1::2,0::2],truth[:,2,1::2,1::2]),1)
        def update(m,o,s,c):
            # Probe every RNG stream without changing the actual loss definition.
            draws=(random.random(),float(np.random.rand()),float(torch.rand(())),
                float(torch.rand((),device='cuda')) if device=='cuda' else None)
            for attempt in range(12):
                o.zero_grad(set_to_none=True);c.apply(c.next_step)
                with torch.autocast(device,dtype=torch.float16,enabled=device=='cuda'):output=m(packed)
                loss,_,_=reconstruction_loss(packed,output,truth)
                s.scale(loss).backward();before=s.get_scale();s.step(o);s.update()
                if s.get_scale()>=before:break
            else:raise AssertionError('test update overflowed')
            c.committed();return draws
        update(model,optimizer,scaler,scheduler)
        args=Namespace(seed=94,microbatch=32)
        provenance=dict(trainingCorpus=dict(sha256='test-only'),purpose='contract_test_only')
        state=capture(model,optimizer,scaler,scheduler,100000,[],1.,provenance,1.,args)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'checkpoint.pt';save(path,state)
            expected_draws=update(model,optimizer,scaler,scheduler)
            expected_model=copy.deepcopy(model.state_dict());expected_optimizer=copy.deepcopy(optimizer.state_dict())
            expected_scaler=scaler.state_dict();expected_scheduler=scheduler.state_dict()
            restored=load(path)
            resumed=BncNeuralV2().to(device)
            resumed.load_state_dict(restored['model'])
            resumed_optimizer=torch.optim.AdamW(resumed.parameters(),lr=.123,betas=(.1,.2),weight_decay=.5)
            resumed_optimizer.load_state_dict(restored['optimizer'])
            resumed_scaler=torch.amp.GradScaler('cuda',enabled=device=='cuda');resumed_scaler.load_state_dict(restored['scaler'])
            resumed_scheduler=GlobalCosine(resumed_optimizer);resumed_scheduler.load_state_dict(restored['scheduler'])
            restore_rng(restored['rng'])
            actual_draws=update(resumed,resumed_optimizer,resumed_scaler,resumed_scheduler)
            self.assertEqual(expected_draws,actual_draws)
            for name,tensor in resumed.state_dict().items():
                self.assertTrue(torch.equal(tensor,expected_model[name]),(device,name))
            actual_optimizer=resumed_optimizer.state_dict()
            self.assertEqual(actual_optimizer['param_groups'],expected_optimizer['param_groups'])
            for key,values in actual_optimizer['state'].items():
                for name,value in values.items():
                    expected=expected_optimizer['state'][key][name]
                    self.assertTrue(torch.equal(value,expected) if isinstance(value,torch.Tensor) else value==expected)
            self.assertEqual(resumed_scaler.state_dict(),expected_scaler)
            self.assertEqual(resumed_scheduler.state_dict(),expected_scheduler)
            self.assertEqual(restored['sampler']['nextSampleIndex'],3200000)
            self.assertEqual(resumed_scheduler.next_step,100001)

    def test_exact_resume_cpu_at_cosine_end(self):self.roundtrip('cpu')

    @unittest.skipUnless(torch.cuda.is_available(),'CUDA unavailable')
    def test_exact_resume_cuda_amp_at_cosine_end(self):self.roundtrip('cuda')

    def test_retention_and_integrity(self):
        model=BncNeuralV2();o=torch.optim.AdamW(model.parameters(),lr=.001)
        s=torch.amp.GradScaler('cuda',enabled=False);c=GlobalCosine(o)
        args=Namespace(seed=94,microbatch=32);provenance=dict(trainingCorpus=dict(sha256='test-only'))
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for step in (1000,2000,3000):
                c.next_step=step
                state=capture(model,o,s,c,step,[],1.,provenance,1.,args)
                persist(root,state,periodic=True,best=step==1000)
            self.assertEqual(sorted(p.name for p in (root/'checkpoints').glob('*.pt')),
                ['best.pt','checkpoint_step_002000.pt','checkpoint_step_003000.pt','latest.pt'])
            path=root/'checkpoints/latest.pt';self.assertEqual(load(path)['step'],3000)
            with path.open('r+b') as stream:stream.seek(100);stream.write(b'corrupt')
            with self.assertRaisesRegex(ValueError,'SHA'):load(path)


if __name__=='__main__':unittest.main()
