import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np
import torch
from ..contracts import (PATTERNS, canonicalize, geometry, measured_mask, merge, pack, sample, targets)
from ..corpus import augment_camera, load_manifest, read_linear
from ..losses import reconstruction_loss
from ..model import BncNeural, Config, infer_tiled
from ..package import PackageError, export_reference, load_reference, preflight, HEADER

torch.set_num_threads(2)
PROVENANCE = dict(modelVersion="contract-test-NOT-TRAINED", trainingCorpus="none: numerical test only",
                  splitProvenance="not applicable: not a quality model", trainingCodeSHA256="0"*64,
                  lossDefinition="not trained", exportDate="2026-09-27T00:00:00Z", trainingSteps=0,
                  purpose="contract_test_only")


class CfaTests(unittest.TestCase):
    def test_all_patterns_offsets_odd_sizes_exact_oracle(self):
        rng = np.random.default_rng(991)
        for shape in ((16, 18), (17, 18), (16, 19), (17, 19)):
            rgb = rng.uniform(0, 4, (*shape, 3)).astype("f4")
            for pattern in PATTERNS:
                for ox, oy in ((0, 0), (1, 0), (0, 1), (1, 1), (-3, 7)):
                    for crop in ((0, 0), (1, 1), (3, 4)):
                        raw = sample(rgb, pattern, (ox, oy), crop)
                        packed, g = pack(raw, pattern, (ox, oy), crop)
                        reconstructed = merge(raw, targets(rgb, g), g)
                        np.testing.assert_array_equal(reconstructed, rgb)
                        predicted_half = targets(rgb, g).astype("f2")
                        actual = merge(raw, predicted_half, g)
                        np.testing.assert_array_equal(actual[measured_mask(g)].view("u4"), raw.flatten().view("u4"))
                        self.assertGreater(packed.max(), .25)

    def test_metadata_phase_equivalence_same_physical_evidence(self):
        raw = np.random.default_rng(17).uniform(0, 4, (23, 28)).astype("f4")
        reference, _ = pack(raw)
        for pattern, (rx, ry) in PATTERNS.items():
            actual, _ = pack(raw, pattern, crop_offset=(rx, ry))
            np.testing.assert_array_equal(reference, actual)

    def test_spatial_canonicalization_equivalent_reflected_scene(self):
        rgb = np.random.default_rng(21).uniform(0, 4, (22, 24, 3)).astype("f4")
        p0, g0 = pack(sample(rgb))
        t0 = targets(rgb, g0)
        torch.manual_seed(7)
        model = BncNeural(Config(8, 4)).eval()
        def infer(p):
            with torch.no_grad():
                return model(torch.from_numpy(p.transpose(2, 0, 1)).unsqueeze(0)).numpy()
        prediction = infer(p0)
        for pattern, (rx, ry) in PATTERNS.items():
            reflected = rgb[::-1] if ry else rgb
            reflected = reflected[:, ::-1] if rx else reflected
            packed, g = pack(sample(reflected, pattern), pattern)
            np.testing.assert_array_equal(packed, p0)
            np.testing.assert_array_equal(targets(reflected, g), t0)
            np.testing.assert_array_equal(infer(packed), prediction)

    def test_no_assumption_different_mosaics_have_same_information(self):
        rgb = np.random.default_rng(9).uniform(0, 4, (16, 18, 3)).astype("f4")
        self.assertFalse(np.array_equal(sample(rgb, "RGGB"), sample(rgb, "BGGR")))

    def test_signed_zero_subnormal_and_headroom_measured_bits(self):
        raw = np.array([-0., np.nextafter(np.float32(0), np.float32(1)), 1.0001, 4.] * 4, "f4").reshape(4, 4)
        for p in PATTERNS:
            packed, g = pack(raw, p)
            rgb = merge(raw, np.ones((*packed.shape[:2], 8), "f2"), g)
            np.testing.assert_array_equal(rgb[measured_mask(g)].view("u4"), raw.flatten().view("u4"))

    def test_reject_bad_domain_shape_and_prediction(self):
        for value in (-1., 4.1, np.inf, np.nan):
            with self.assertRaises(ValueError):
                pack(np.full((4, 4), value, "f4"))
        with self.assertRaises(ValueError):
            pack(np.zeros((1, 3), "f4"))
        with self.assertRaises(ValueError):
            pack(np.zeros((4, 4), "f8"))
        raw = np.zeros((4, 4), "f4")
        _, g = pack(raw)
        with self.assertRaises(ValueError):
            merge(raw, np.full((2, 2, 8), np.nan), g)


class ModelTests(unittest.TestCase):
    def test_all_six_architectures_and_tiling_bias_edges_seams(self):
        torch.manual_seed(43)
        inp = torch.rand(1, 4, 35, 43)
        for width in (8, 12, 16):
            for blocks in (4, 6):
                model = BncNeural(Config(width, blocks)).eval()
                self.assertEqual(sum(p.numel() for p in model.parameters()), model.config.parameter_count)
                with torch.no_grad():
                    full = model(inp)
                    for inner in (7, 16, 64):
                        tiled = infer_tiled(model, inp, inner)
                        torch.testing.assert_close(tiled, full, rtol=1e-5, atol=5e-7)

    def test_gradient_receptive_field_bound(self):
        torch.manual_seed(19)
        model = BncNeural(Config(8, 4))
        inp = torch.rand(1, 4, 31, 31, requires_grad=True)
        model(inp)[0, 0, 15, 15].backward()
        outside = inp.grad.clone()
        r = model.config.halo
        outside[:, :, 15-r:16+r, 15-r:16+r] = 0
        self.assertEqual(float(outside.abs().max()), 0)
        self.assertGreater(float(inp.grad[:, :, 15-r:16+r, 15-r:16+r].abs().max()), 0)

    def test_losses_preserve_colored_edges_and_have_gradients(self):
        inp, truth = torch.rand(1, 4, 12, 12), torch.rand(1, 8, 12, 12)
        identical, terms = reconstruction_loss(inp, truth, truth)
        self.assertAlmostEqual(float(identical), 1e-4, places=7)
        for name in ("luma_gradient", "opponent", "detail"):
            self.assertEqual(float(terms[name]), 0)
        pred = torch.zeros_like(truth, requires_grad=True)
        loss, _ = reconstruction_loss(inp, pred, truth)
        loss.backward()
        self.assertTrue(torch.isfinite(pred.grad).all())
        self.assertGreater(float(pred.grad.abs().sum()), 0)

    def test_same_signal_optimizer_smoke_is_not_quality_training(self):
        torch.manual_seed(47)
        model = BncNeural(Config(8, 4))
        inp, target = torch.rand(1, 4, 16, 16), torch.rand(1, 8, 16, 16)
        optim = torch.optim.Adam(model.parameters(), lr=.003)
        initial = float(reconstruction_loss(inp, model(inp), target)[0].detach())
        for _ in range(8):
            optim.zero_grad()
            loss, _ = reconstruction_loss(inp, model(inp), target)
            loss.backward()
            optim.step()
        final = float(reconstruction_loss(inp, model(inp), target)[0].detach())
        self.assertLess(final, initial)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "test.bncmodel"
        self.model = BncNeural(Config(8, 4))
        self.manifest, self.sha = export_reference(self.model, self.path, PROVENANCE)

    def test_roundtrip_exact_fp16_weights_and_identity(self):
        loaded, manifest = load_reference(self.path, self.sha)
        for a, b in zip(self.model.parameters(), loaded.parameters()):
            torch.testing.assert_close(a.half().float(), b, rtol=0, atol=0)
        self.assertEqual(manifest["productName"], "BnC Neural")
        self.assertFalse(manifest["productReady"])

    def test_bad_hash_and_truncated_package_rejected(self):
        for change in (lambda b: b[:-1], lambda b: b[:-1]+bytes([b[-1]^1])):
            data = change(self.path.read_bytes())
            self.path.write_bytes(data)
            with self.assertRaises(PackageError):
                load_reference(self.path, self.sha)

    def test_payload_hash_checked_independently_of_file_hash(self):
        b = self.path.read_bytes()
        b = b[:-1] + bytes([b[-1]^1])
        self.path.write_bytes(b)
        with self.assertRaisesRegex(PackageError, "PAYLOAD_SHA"):
            load_reference(self.path, hashlib.sha256(b).hexdigest())

    def test_manifest_tamper_rejected_even_with_recomputed_hashes(self):
        b = self.path.read_bytes()
        h = list(HEADER.unpack_from(b))
        manifest = json.loads(b[64:64+h[4]])
        manifest["inputDomain"] = "srgb"
        m = json.dumps(manifest).encode()
        payload = m + b[64+h[4]:]
        h[4], h[-1] = len(m), hashlib.sha256(payload).digest()
        b = HEADER.pack(*h) + payload
        self.path.write_bytes(b)
        with self.assertRaisesRegex(PackageError, "CONTRACT_INVALID"):
            load_reference(self.path, hashlib.sha256(b).hexdigest())

    def test_missing_invalid_and_unavailable_always_truthful(self):
        for path, sha, reason in ((self.path, self.sha, "BNC_NEURAL_BACKEND_UNAVAILABLE"),
                                 (self.path, "0"*64, "BNC_NEURAL_PACKAGE_INVALID"),
                                 (self.path.parent/"absent", self.sha, "BNC_NEURAL_MODEL_MISSING")):
            result = preflight(path, sha)
            self.assertEqual(result["actual"], "MALVAR_2004")
            self.assertEqual(result["requested"], "BNC_NEURAL")
            self.assertTrue(result["fallback"])
            self.assertFalse(result["bncNeuralAvailable"])
            self.assertEqual(result["fallbackReason"], reason)


class CorpusTests(unittest.TestCase):
    def test_linear_invertible_augmentation(self):
        rng = np.random.default_rng(39)
        rgb = rng.uniform(0, 1, (24, 24, 3)).astype("f4")
        for _ in range(20):
            v, matrix = augment_camera(rgb, rng)
            self.assertGreater(abs(np.linalg.det(matrix)), .001)
            self.assertLessEqual(v.max(), 4)
            np.testing.assert_allclose(v @ np.linalg.inv(matrix).T, rgb, atol=4e-7)

    def test_source_group_leakage_duplicate_and_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for i in range(2):
                np.save(root/f"{i}.npy", np.full((32, 32, 3), .1+i*.1, "f4"))
            records = [dict(path=f"{i}.npy", sha256=hashlib.sha256((root/f"{i}.npy").read_bytes()).hexdigest(),
                            source="unit-test", license="self-generated-test", source_group="same-scene",
                            split=("train", "test")[i], encoding="linear_rgb", ground_truth="known_full_rgb") for i in range(2)]
            path = root/"corpus.json"
            path.write_text(json.dumps(dict(schema="bnc-rgb-corpus-v1", images=records)))
            with self.assertRaisesRegex(ValueError, "leakage"):
                list(load_manifest(path))
            records[1]["source_group"] = "other"
            path.write_text(json.dumps(dict(schema="bnc-rgb-corpus-v1", images=records)))
            valid = list(load_manifest(path))
            self.assertEqual(len(valid), 2)
            np.testing.assert_array_equal(read_linear(valid[0]), np.full((32, 32, 3), .1, "f4"))
            records[1]["sha256"] = "0"*64
            path.write_text(json.dumps(dict(schema="bnc-rgb-corpus-v1", images=records)))
            with self.assertRaisesRegex(ValueError, "hash"):
                list(load_manifest(path))


if __name__ == "__main__":
    unittest.main()
