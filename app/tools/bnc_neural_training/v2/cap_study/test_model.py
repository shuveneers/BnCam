import unittest

import torch

from ..model import BncNeuralV2
from .model import CapModel


class CapModelTests(unittest.TestCase):
    def test_only_cap_changes_parameter_topology(self):
        official = BncNeuralV2()
        for cap in (16, 32, 64):
            with self.subTest(cap=cap):
                model = CapModel(cap)
                self.assertEqual(list(model.state_dict()), list(official.state_dict()))
                self.assertEqual([v.shape for v in model.state_dict().values()],
                                 [v.shape for v in official.state_dict().values()])
                self.assertEqual(sum(p.numel() for p in model.parameters()), 27200)
                self.assertEqual(len(model.blocks()), 9)

    def test_exact_cap_formula(self):
        for cap in (16, 32, 64):
            with self.subTest(cap=cap):
                block = CapModel(cap).shared[0]
                x = torch.randn(1, 24, 8, 8)*10
                seen = []
                handle = block.project.register_forward_pre_hook(lambda module, args: seen.append(args[0]))
                try: block(x)
                finally: handle.remove()
                a, b = block.expand(block.depthwise(x)).chunk(2, 1)
                torch.testing.assert_close(seen[0], cap*torch.tanh((a*b)/cap), rtol=0, atol=0)

    @unittest.skipUnless(torch.cuda.is_available(), 'CUDA required')
    def test_full_forward_fp32_inside_external_amp(self):
        model = CapModel(16).cuda()
        with torch.autocast('cuda', dtype=torch.float16):
            out = model(torch.randn(1, 4, 8, 8, device='cuda'))
        self.assertEqual(out.dtype, torch.float32)


if __name__=='__main__': unittest.main()
