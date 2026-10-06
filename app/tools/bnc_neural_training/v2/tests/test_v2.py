from pathlib import Path
import tempfile
import unittest
import numpy as np
import torch
from torch import nn
from ...contracts import PATTERNS,canonicalize,pack,sample,merge,measured_mask
from ...model import infer_tiled
from ...package import export_reference,load_reference,PackageError
from ...tests.test_contracts import PROVENANCE
from ..model import BncNeuralV2,Config,GatedBlock,missing_rgb,structured_targets,MissingRgbAdapter
from ..losses import WEIGHTS,reconstruction_loss,truth_masks,diagnostics,opp
from ..procedural import scene,parameters,rng_for,KINDS
from ..train import learning_rate,plateau

torch.set_num_threads(2)


class ArchitectureTests(unittest.TestCase):
    def test_exact_graph_parameter_count_and_no_normalization(self):
        m=BncNeuralV2()
        self.assertEqual(sum(p.numel() for p in m.parameters()),27200)
        self.assertEqual([b.depthwise.dilation[0] for b in m.shared],[1,2,3,3,2,1])
        self.assertEqual([b.depthwise.dilation[0] for b in m.opponent],[1,2,1])
        self.assertEqual(m.opponent_input.in_channels,32)
        self.assertTrue(all(b.depthwise.groups==24 for b in m.modules() if isinstance(b,GatedBlock)))
        self.assertFalse(any(isinstance(b,(nn.BatchNorm2d,nn.LayerNorm,nn.InstanceNorm2d,nn.AvgPool2d,nn.MaxPool2d)) for b in m.modules()))
        self.assertEqual((m.config.green_radius,m.config.halo),(15,20))
        with self.assertRaises(ValueError):Config(32)

    def test_signed_opponents_and_missing_rgb_above_one(self):
        p=torch.tensor([.8,.7,.6,.2]).view(1,4,1,1)
        s=torch.tensor([.5,.4,-.1,-.2,.9,-.4,.5,-.5]).view(1,8,1,1)
        got=missing_rgb(p,s).flatten()
        torch.testing.assert_close(got,torch.tensor([.5,.1,.6,1.2,.4,.1,1.3,.4]))
        m=BncNeuralV2()
        with torch.no_grad():
            m.opponent_output.weight.zero_();m.opponent_output.bias.fill_(-.4)
            out=m(torch.rand(1,4,12,12))
        self.assertTrue((out[:,2:]<0).all())

    def test_residual_scale_is_bounded(self):
        b=GatedBlock(24,1)
        with torch.no_grad():b.alpha.fill_(1000)
        self.assertLessEqual(float(torch.tanh(b.alpha).max()),1.)
        with torch.no_grad():b.alpha.fill_(-1000)
        self.assertGreaterEqual(float(torch.tanh(b.alpha).min()),-1.)

    def test_green_and_opponent_gradient_support_bounds(self):
        torch.manual_seed(823)
        m=BncNeuralV2()
        for channel,radius in ((0,15),(2,20)):
            x=torch.rand(1,4,49,49,requires_grad=True)
            m(x)[0,channel,24,24].backward()
            outside=x.grad.clone();outside[:,:,24-radius:25+radius,24-radius:25+radius]=0
            self.assertEqual(float(outside.abs().max()),0.)
            self.assertGreater(float(x.grad.abs().max()),0.)
            m.zero_grad(set_to_none=True)

    def test_green_conditions_opponent_head(self):
        m=BncNeuralV2();x=torch.rand(1,4,12,12)
        m(x)[:,2:].sum().backward()
        self.assertGreater(float(m.green[2].weight.grad.abs().sum()),0.)

    def test_tiled_matches_full_scene_linear_including_edges(self):
        torch.manual_seed(873)
        m=MissingRgbAdapter(BncNeuralV2()).eval();x=torch.rand(1,4,65,73)
        with torch.no_grad():full=m(x)
        for inner in (13,32,128):
            actual=infer_tiled(m,x,inner)
            self.assertLessEqual(float((actual-full).abs().max())*4,1e-5)


class ReconstructionTests(unittest.TestCase):
    def test_oracle_structured_conversion_all_cfa_and_offsets(self):
        rng=np.random.default_rng(723)
        for pattern in PATTERNS:
            for ox,oy in ((0,0),(1,0),(0,1),(1,1),(-3,7)):
                for shape in ((20,22),(21,23)):
                    truth=rng.uniform(0,4,(*shape,3)).astype('f4')
                    raw=sample(truth,pattern,(ox,oy),(3,4));packed,g=pack(raw,pattern,(ox,oy),(3,4))
                    t=torch.from_numpy(canonicalize(truth,g).transpose(2,0,1).copy()/4).unsqueeze(0)
                    p=torch.from_numpy(packed.transpose(2,0,1).copy()).unsqueeze(0)
                    s=structured_targets(t);missing=missing_rgb(p,s).squeeze(0).permute(1,2,0).numpy()
                    rgb=merge(raw,missing,g)
                    np.testing.assert_allclose(rgb,truth,atol=5e-7,rtol=1e-6)
                    np.testing.assert_array_equal(rgb[measured_mask(g)].view('u4'),raw.ravel().view('u4'))

    def test_exact_loss_weights_and_oracle_loss(self):
        self.assertEqual(list(WEIGHTS.values()),[1.,.3,.2,.2,.2,.08,.1,.1])
        t=torch.rand(1,3,16,18)
        p=torch.stack((t[:,0,0::2,0::2],t[:,1,0::2,1::2],t[:,1,1::2,0::2],t[:,2,1::2,1::2]),1)
        s=structured_targets(t);loss,terms,rgb=reconstruction_loss(p,s,t)
        torch.testing.assert_close(rgb,t,atol=1e-7,rtol=1e-6)
        self.assertLess(float(loss),.0001002)
        self.assertTrue(all(float(v)<1e-7 for k,v in terms.items() if k!='missing_charbonnier'))

    def test_complementary_truth_masks_and_preserved_chromatic_edge(self):
        t=torch.full((1,3,32,32),.2);t[:,0,:,16:]=.6;t[:,2,:,16:]=.1
        neutral,chromatic=truth_masks(opp(t))
        self.assertFalse((neutral&chromatic).any());self.assertTrue(neutral.any());self.assertTrue(chromatic.any())
        p=torch.stack((t[:,0,0::2,0::2],t[:,1,0::2,1::2],t[:,1,1::2,0::2],t[:,2,1::2,1::2]),1)
        s=structured_targets(t);_,terms,_=reconstruction_loss(p,s,t)
        self.assertEqual(float(terms['chromatic_edge']),0.)
        wrong=s.clone();wrong[:,2:]=0
        _,bad,_=reconstruction_loss(p,wrong,t)
        self.assertGreater(float(bad['chromatic_edge']),0.)
        q=diagnostics(p,s,t)
        for key in ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','luma_gradient_ratio','opponent_gradient_ratio','neutral_false_color_energy','chromatic_edge_retention'):
            self.assertIn(key,q)

    def test_v2_package_roundtrip_preserves_signed_head_and_v1_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'contract.bncmodel';m=BncNeuralV2()
            manifest,sha=export_reference(m,path,PROVENANCE)
            loaded,metadata=load_reference(path,sha)
            self.assertIsInstance(loaded,BncNeuralV2)
            self.assertEqual(metadata['architectureVersion'],'BNC_NEURAL_V2_GOPP_W24')
            self.assertEqual(path.read_bytes()[:8],b'BNCDEM2\0')
            for a,b in zip(m.parameters(),loaded.parameters()):torch.testing.assert_close(a.half().float(),b,rtol=0,atol=0)
            with self.assertRaises(PackageError):load_reference(path,'0'*64)


class DataScheduleTests(unittest.TestCase):
    def test_procedural_splits_have_disjoint_frequency_angle_phase_width_bins(self):
        seen={s:{k:set() for k in ('frequency','angle','phase','width','chirp_end','texture_other_frequency','checker_frequency','checker_other_frequency')} for s in ('train','validation','test')}
        for s in seen:
            for i in range(200):
                for k,v in parameters(rng_for(s,i),s).items():seen[s][k].add(v)
        for k in seen['train']:
            for a,b in (('train','validation'),('train','test'),('validation','test')):
                self.assertFalse(seen[a][k]&seen[b][k],(k,a,b))

    def test_procedural_truth_range_determinism_and_coverage(self):
        kinds=set();headroom=False
        for i in range(100):
            rgb,meta=scene(i,'train',64);kinds.add(meta['kind']);headroom|=bool(rgb.max()>1)
            self.assertGreaterEqual(rgb.min(),0);self.assertLessEqual(rgb.max(),4)
            np.testing.assert_array_equal(rgb,scene(i,'train',64)[0])
        self.assertEqual(kinds,set(KINDS));self.assertTrue(headroom)

    def test_lr_and_plateau_no_early_shortcut(self):
        self.assertAlmostEqual(learning_rate(0),.001)
        self.assertAlmostEqual(learning_rate(100000),.00003)
        self.assertEqual(learning_rate(150000),learning_rate(100000))
        self.assertFalse(plateau([dict(missing=1.,opponent=1.,edge=1.)]*19)[0])
        self.assertTrue(plateau([dict(missing=1.,opponent=1.,edge=1.)]*20)[0])
        h=[dict(missing=1.,opponent=1.,edge=1.)]*10+[dict(missing=.9,opponent=1.,edge=1.)]*10
        self.assertFalse(plateau(h)[0])


if __name__=='__main__':unittest.main()
