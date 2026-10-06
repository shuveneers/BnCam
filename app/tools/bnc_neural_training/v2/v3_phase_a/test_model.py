import unittest

import torch

from ..model import BncNeuralV2
from .model import PhaseBlock, PhaseModel


class PhaseGateTests(unittest.TestCase):
    def test_reference_is_exact_w24_fp32_forward(self):
        torch.manual_seed(71)
        source = BncNeuralV2().eval()
        candidate = PhaseModel('A').eval()
        candidate.load_state_dict(source.state_dict(), strict=True)
        packed = torch.randn(1, 4, 12, 14)
        self.assertTrue(torch.equal(source(packed), candidate(packed)))

    def test_only_gate_formula_changes(self):
        torch.manual_seed(12)
        x = torch.randn(1, 24, 9, 11)
        reference = PhaseBlock(24, 2, 'A')
        for variant in ('A', 'B', 'C'):
            block = PhaseBlock(24, 2, variant)
            block.load_state_dict(reference.state_dict(), strict=True)
            a, b = block.expand(block.depthwise(x)).chunk(2, 1)
            gate = {'A': lambda: a*b,
                    'B': lambda: a*torch.tanh(b),
                    'C': lambda: 16*torch.tanh((a*b)/16)}[variant]()
            expected = x+torch.tanh(block.alpha)*block.project(gate)
            self.assertTrue(torch.equal(expected, block(x)))

    def test_all_nine_blocks_have_selected_gate(self):
        for variant in ('A', 'B', 'C'):
            model = PhaseModel(variant)
            blocks = [m for m in model.modules() if isinstance(m, PhaseBlock)]
            self.assertEqual(len(blocks), 9)
            self.assertTrue(all(m.variant == variant for m in blocks))


if __name__ == '__main__':
    unittest.main()
