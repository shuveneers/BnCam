import unittest
import numpy as np
import torch
from ..opponent import opponent_losses, opponent_metrics
from ..confidence_study import region_scores


class OpponentTests(unittest.TestCase):
    def test_true_isoluminant_edge_has_zero_opponent_penalties(self):
        truth = torch.empty(1, 3, 16, 16)
        left = torch.tensor([.8, .25, .15])
        right = torch.tensor([.15, (.2126*(.8-.15)+.7152*.25+.0722*(.15-.75))/.7152, .75])
        truth[:, :, :, :8] = left[None, :, None, None]
        truth[:, :, :, 8:] = right[None, :, None, None]
        terms = opponent_losses(truth, truth)
        self.assertTrue(all(float(v)==0 for v in terms.values()))
        gray = truth.mean(1, keepdim=True).expand_as(truth).clone().requires_grad_()
        bad = opponent_losses(gray, truth)
        self.assertGreater(float(bad["opponent"].detach()), .1)
        self.assertGreater(float(bad["opponent_gradient"].detach()), 0)
        self.assertEqual(float(bad["zero_chroma_energy"].detach()), 0.)
        sum(bad.values()).backward()
        self.assertGreater(float(gray.grad.abs().sum()), 0)

    def test_zero_chroma_oscillations_have_energy_and_sign_error(self):
        truth = np.full((16, 16, 3), .5, "f4")
        prediction = truth.copy()
        prediction[..., 0] += (np.arange(16)%2*2-1)[None, :]*.05
        q = opponent_metrics(prediction, truth)
        self.assertGreater(q["RG"]["alternating_sign_change_fraction"], .4)
        self.assertGreater(q["RG"]["zero_chroma_false_color_energy"], .002)
        self.assertGreater(q["RG"]["gradient_error_rms"], .06)
        self.assertEqual(q["BG"]["reconstruction_rms"], 0)

    def test_real_alternating_chroma_is_not_suppressed(self):
        rgb = torch.full((1, 3, 16, 16), .5)
        rgb[:, 0, :, ::2] += .1
        rgb[:, 0, :, 1::2] -= .1
        self.assertTrue(all(float(v)==0 for v in opponent_losses(rgb, rgb).values()))

    def test_confidence_does_not_reward_low_chroma_amplitude(self):
        rgb = np.zeros((16,16,3), "f4")
        rgb[:, :8] = [.7,.2,.1]
        rgb[:, 8:] = [.1,.4,.7]
        gray = np.repeat(rgb.mean(-1,keepdims=True),3,-1)
        scores = region_scores([rgb,rgb,gray])
        self.assertEqual(scores[0], 0.)
        self.assertEqual(scores[1], 0.)
        self.assertGreater(scores[2], 0.)
