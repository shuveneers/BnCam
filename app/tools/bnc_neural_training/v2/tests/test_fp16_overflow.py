"""Regression for the real W24 step-28036 bright circles/arcs sample."""
from pathlib import Path
import unittest

import numpy as np
import torch

from ..data import TrainingData, encode
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..procedural import scene


FIXTURE = Path(__file__).with_name("fixtures") / "nonfinite_step_28036.pt"


@unittest.skipUnless(torch.cuda.is_available(), "W24 AMP regression needs CUDA")
class OpponentPrecisionTests(unittest.TestCase):
    def test_exact_failing_sample_and_first_overflow(self):
        torch.set_num_threads(2)
        state = torch.load(FIXTURE, map_location="cpu", weights_only=True)
        self.assertEqual((state["step"], state["sampleIndex"], state["seed"]),
                         (28036, 897163, 823109))
        rgb, meta = scene(state["sampleIndex"], "train", 256, state["seed"])
        self.assertEqual(meta["kind"], "circles_arcs")
        self.assertTrue(bool((rgb >= 0).all() and (rgb <= 4).all()))
        packed, truth = TrainingData([], 1, state["seed"], state["sampleIndex"])[0]
        packed, truth = packed.unsqueeze(0).cuda(), truth.unsqueeze(0).cuda()
        model = BncNeuralV2().cuda().eval()
        model.load_state_dict(state["model"])
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16):
            features = model.shared(model.stem(packed))
            green = model.green(features)
            scaffold = torch.stack((green[:, 0], packed[:, 1], packed[:, 2], green[:, 1]), 1)
            chroma = model.opponent_input(torch.cat((features, packed, scaffold), 1))
            chroma = model.opponent[:2](chroma)
            last = model.opponent[2]
            a, b = last.expand(last.depthwise(chroma)).chunk(2, dim=1)
            self.assertTrue(bool(torch.isfinite(a).all() and torch.isfinite(b).all()))
            self.assertGreater(float((a.float() * b.float()).abs().max()), 65504.)
            self.assertFalse(bool(torch.isfinite(a * b).all()))  # original FP16 failure
            output = model(packed)
            loss, terms, reconstructed = reconstruction_loss(packed, output, truth, True)
        for value in (packed, truth, output, reconstructed, loss, *terms.values()):
            self.assertTrue(bool(torch.isfinite(value).all()))
        with torch.no_grad():
            reference = model(packed)
        self.assertTrue(bool(torch.isfinite(reference).all()))
        self.assertLess(float((output - reference).abs().max()) /
                        max(1., float(reference.abs().max())), .1)

    def test_extreme_valid_scene_linear_inputs(self):
        state = torch.load(FIXTURE, map_location="cpu", weights_only=True)
        model = BncNeuralV2().cuda().eval()
        model.load_state_dict(state["model"])
        y, x = np.indices((256, 256))
        ramp = np.broadcast_to((x / 255)[..., None], (256, 256, 3)).astype("f4")
        isoluminant, meta = scene(897163, "train", 256, 823109,
                                  _kind_for_test="isoluminant_edge")
        self.assertEqual(meta["kind"], "isoluminant_edge")
        color_edge = np.zeros((256, 256, 3), dtype="f4")
        color_edge[:, :128] = [1, .05, .05]
        color_edge[:, 128:] = [.05, 1, .05]
        cases = dict(black=np.zeros_like(ramp), near_black=ramp * 1e-6,
                     maximum_headroom=np.full_like(ramp, 4),
                     above_one=ramp * 3.5, neutral=ramp * 2,
                     saturated_chromatic=color_edge * 4,
                     isoluminant=isoluminant)
        sensor = np.array([[.84, .08, .08], [.08, .84, .08], [.08, .08, .84]], dtype="f4")
        for name, matrix in (("spectral_high", np.diag([2, .5, 2]) @ sensor * 2),
                             ("spectral_low", np.diag([.5, 2, .5]) @ sensor * .25)):
            self.assertLessEqual(float(np.linalg.cond(matrix)), 5)
            cases[name] = (ramp @ matrix.T).astype("f4")
        for name, rgb in cases.items():
            self.assertTrue(bool(np.isfinite(rgb).all() and (rgb >= 0).all() and (rgb <= 4).all()), name)
            for pattern in ("RGGB", "GRBG", "GBRG", "BGGR"):
                packed, truth = encode(rgb, pattern)
                packed, truth = packed.unsqueeze(0).cuda(), truth.unsqueeze(0).cuda()
                with self.subTest(case=name, pattern=pattern), torch.no_grad(), torch.autocast("cuda", dtype=torch.float16):
                    output = model(packed)
                    loss, terms, reconstructed = reconstruction_loss(packed, output, truth, True)
                    for value in (packed, truth, output, reconstructed, loss, *terms.values()):
                        self.assertTrue(bool(torch.isfinite(value).all()))


if __name__ == "__main__":
    unittest.main()
