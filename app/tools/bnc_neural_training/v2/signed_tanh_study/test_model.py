import unittest

import torch

from ..model import BncNeuralV2
from .model import SignedModel


def gate(a,b,tau):
    return .5*(a*(tau*torch.tanh(b/tau))+b*(tau*torch.tanh(a/tau)))


class SignedGateTests(unittest.TestCase):
    def test_parameter_topology_and_fp32(self):
        official=BncNeuralV2()
        model=SignedModel(16)
        self.assertEqual(list(official.state_dict()),list(model.state_dict()))
        self.assertEqual(sum(p.numel() for p in model.parameters()),27200)
        self.assertEqual(len(model.blocks()),9)
        self.assertEqual(model(torch.rand(1,4,8,8)).dtype,torch.float32)

    def test_small_signal_signed_symmetric(self):
        a=torch.tensor([.001,-.001,.001,-.001])
        b=torch.tensor([.002,.002,-.002,-.002])
        for tau in (16.,32.,64.):
            x=gate(a,b,tau)
            torch.testing.assert_close(x,a*b,rtol=1e-6,atol=1e-10)
            torch.testing.assert_close(x,gate(b,a,tau),rtol=0,atol=0)
            self.assertEqual(x.sign().tolist(),(a*b).sign().tolist())

    def test_large_signal_linear_growth_and_useful_gradient(self):
        for tau in (16.,32.,64.):
            a=torch.tensor([1e8],requires_grad=True)
            b=torch.tensor([1e8],requires_grad=True)
            y=gate(a,b,tau)
            doubled=gate(2*a,2*b,tau)
            torch.testing.assert_close(doubled,2*y,rtol=1e-6,atol=1.)
            y.backward()
            self.assertTrue(bool(torch.isfinite(a.grad).all() and torch.isfinite(b.grad).all()))
            self.assertGreater(float(a.grad),tau*.49)
            self.assertGreater(float(b.grad),tau*.49)

    @unittest.skipUnless(torch.cuda.is_available(),'CUDA required')
    def test_full_forward_disables_external_amp(self):
        model=SignedModel(16).cuda()
        with torch.autocast('cuda',dtype=torch.float16):
            output=model(torch.rand(1,4,8,8,device='cuda'))
        self.assertEqual(output.dtype,torch.float32)


if __name__=='__main__':unittest.main()
