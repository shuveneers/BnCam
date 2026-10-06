import unittest

import torch

from ..model import BncNeuralV2, GatedBlock
from .model import BoundedBncNeuralV2, BoundedGatedBlock


class ResearchForkTests(unittest.TestCase):
    def test_only_gate_changes_and_state_keys_match(self):
        official, fork = BncNeuralV2(), BoundedBncNeuralV2()
        self.assertEqual(list(official.state_dict()), list(fork.state_dict()))
        self.assertEqual([v.shape for v in official.state_dict().values()],
                         [v.shape for v in fork.state_dict().values()])
        self.assertEqual(sum(p.numel() for p in fork.parameters()), 27200)
        self.assertEqual(sum(isinstance(b, BoundedGatedBlock) for b in fork.modules()), 9)
        self.assertEqual(sum(isinstance(b, GatedBlock) for b in official.modules()), 9)
        self.assertEqual([b.depthwise.dilation for b in fork.shared],
                         [b.depthwise.dilation for b in official.shared])
        self.assertEqual([b.depthwise.dilation for b in fork.opponent],
                         [b.depthwise.dilation for b in official.opponent])

    def test_exact_bounded_product(self):
        block = BoundedGatedBlock(24, 1)
        x = torch.randn(1, 24, 8, 8) * 10
        captured = []
        handle = block.project.register_forward_pre_hook(lambda module, args: captured.append(args[0]))
        try:
            block(x)
        finally:
            handle.remove()
        a, b = block.expand(block.depthwise(x)).chunk(2, dim=1)
        torch.testing.assert_close(captured[0], 64*torch.tanh((a*b)/64), rtol=0, atol=0)
        self.assertLessEqual(float(captured[0].detach().abs().max()), 64.)

    @unittest.skipUnless(torch.cuda.is_available(), 'CUDA required for AMP precision test')
    def test_full_forward_is_fp32_under_external_amp(self):
        model = BoundedBncNeuralV2().cuda()
        x = torch.randn(1, 4, 16, 16, device='cuda')
        with torch.autocast('cuda', dtype=torch.float16):
            output = model(x)
        self.assertEqual(output.dtype, torch.float32)
        self.assertTrue(all(b.raw_abs_max >= 0 for b in model.modules() if isinstance(b, BoundedGatedBlock)))


if __name__ == '__main__': unittest.main()
