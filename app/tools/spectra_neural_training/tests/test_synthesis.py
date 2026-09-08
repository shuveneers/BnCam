import json
from pathlib import Path

import numpy as np
import torch

from spectra_train.dataset import RawManifestDataset
from spectra_train.manifest import load_manifest
from spectra_train.synthesis import (
    DarkFrameBank,
    NoiseSynthesisConfig,
    PhysicsConditionedSampleBuilder,
    poisson_gaussian_noise,
    quantize_normalized,
    synthesize_remaining_lsc,
)


def _manifest(tmp_path: Path):
    clean = np.full((4, 32, 32), 0.25 * 1023, dtype=np.float32)
    dark = np.zeros((4, 40, 40), dtype=np.float32)
    rng = np.random.default_rng(4)
    dark += 64.0 + rng.normal(0, 2, dark.shape).astype(np.float32)
    np.save(tmp_path / "clean.npy", clean)
    np.save(tmp_path / "dark.npy", dark)
    physics = {
        "shot_s": [0.01, 0.012, 0.011, 0.014],
        "read_o": [0.0004, 0.0003, 0.0003, 0.0005],
        "black_level_raw": [0, 0, 0, 0],
        "white_level_raw": [1023, 1023, 1023, 1023],
        "exposure_time_seconds": 0.01,
        "analog_gain": 2,
        "digital_gain": 1,
        "bit_depth": 10,
        "metadata_trust": 0.9,
    }
    rec_base = {
        "sensor_id": "A", "storage_layout": "canonical_packed",
        "cfa": {"arrangement": "RGGB"}, "physics": physics,
        "source": "unit", "license": "unit", "calibration_quality": "measured", "clean_gt_method": "synthetic_unit_reference",
    }
    records = [
        dict(rec_base, sample_id="clean", kind="clean", clean_path="clean.npy"),
        dict(rec_base, sample_id="dark", kind="dark", dark_path="dark.npy"),
    ]
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps({"schema_version": 1, "dataset_name": "u", "dataset_version": "1", "records": records}))
    return load_manifest(p)


def test_poisson_gaussian_variance_tracks_sx_plus_o():
    g = torch.Generator().manual_seed(10)
    cfg = NoiseSynthesisConfig(heavy_tail_probability=0, hot_pixel_probability=0)
    x = torch.full((4, 256, 256), 0.4)
    s = torch.full((4, 1, 1), 0.01)
    o = torch.full((4, 1, 1), 0.002)
    n = poisson_gaussian_noise(x, s, o, cfg, g)
    measured = n.var().item()
    expected = (0.01 * 0.4 + 0.002)
    assert abs(measured - expected) / expected < 0.10


def test_lsc_is_positive_and_radially_amplifying():
    cfg = NoiseSynthesisConfig(lsc_max_gain_range=(2.0, 2.0), lsc_channel_imbalance=0.0)
    g = torch.Generator().manual_seed(2)
    m = synthesize_remaining_lsc((32, 32), cfg, g, torch.device("cpu"))
    assert m.shape == (4, 32, 32)
    assert torch.all(m >= 1.0)
    assert m[:, 0, 0].mean() > m[:, 16, 16].mean()


def test_dark_frame_bank_and_full_builder(tmp_path: Path):
    manifest = _manifest(tmp_path)
    clean_ds = RawManifestDataset(manifest, [manifest.records[0]])
    dark_ds = RawManifestDataset(manifest, [manifest.records[1]])
    bank = DarkFrameBank.from_dataset(dark_ds)
    builder = PhysicsConditionedSampleBuilder(NoiseSynthesisConfig(), dark_bank=bank, seed=3)
    sample = builder.build(clean_ds[0], regime="heavy")
    assert sample.noisy.shape == sample.clean.shape == (4, 32, 32)
    assert sample.conditioning.shape == (14, 32, 32)
    assert sample.input_variance.shape == (4, 32, 32)
    assert sample.remaining_lsc.shape == (4, 32, 32)
    assert torch.isfinite(sample.conditioning).all()
    assert not sample.identity_target


def test_identity_regime_has_lower_correction_energy_than_heavy(tmp_path: Path):
    manifest = _manifest(tmp_path)
    ds = RawManifestDataset(manifest, [manifest.records[0]])
    cfg = NoiseSynthesisConfig(heavy_tail_probability=0, hot_pixel_probability=0)
    identity = PhysicsConditionedSampleBuilder(cfg, seed=7).build(ds[0], regime="identity")
    heavy = PhysicsConditionedSampleBuilder(cfg, seed=7).build(ds[0], regime="heavy")
    e_identity = torch.mean((identity.noisy - identity.clean) ** 2)
    e_heavy = torch.mean((heavy.noisy - heavy.clean) ** 2)
    assert e_identity < e_heavy


def test_quantization_is_deterministic():
    x = torch.tensor([0.1, 0.2, 0.3])
    assert torch.equal(quantize_normalized(x, 10), quantize_normalized(x, 10))


def test_real_paired_sample_uses_measured_noisy_raw_without_resynthesis(tmp_path: Path):
    clean = np.full((4, 16, 16), 0.30 * 1023, dtype=np.float32)
    noisy = clean.copy()
    noisy[0, 5, 7] += 9.0
    np.save(tmp_path / "paired_clean.npy", clean)
    np.save(tmp_path / "paired_noisy.npy", noisy)
    physics = {
        "shot_s": [0.01] * 4,
        "read_o": [0.0004] * 4,
        "black_level_raw": [0] * 4,
        "white_level_raw": [1023] * 4,
        "exposure_time_seconds": 0.01,
        "analog_gain": 2.0,
        "digital_gain": 1.0,
        "bit_depth": 10,
        "metadata_trust": 0.95,
    }
    record = {
        "sample_id": "paired",
        "sensor_id": "A",
        "kind": "paired",
        "storage_layout": "canonical_packed",
        "cfa": {"arrangement": "RGGB"},
        "physics": physics,
        "source": "unit",
        "license": "unit",
        "calibration_quality": "measured",
        "clean_gt_method": "robust_multiframe_master",
        "clean_path": "paired_clean.npy",
        "noisy_path": "paired_noisy.npy",
    }
    p = tmp_path / "paired_manifest.json"
    p.write_text(json.dumps({"schema_version": 1, "dataset_name": "u", "dataset_version": "1", "records": [record]}))
    manifest = load_manifest(p)
    item = RawManifestDataset(manifest, manifest.records)[0]
    sample = PhysicsConditionedSampleBuilder(NoiseSynthesisConfig(), seed=99).build(item, regime="heavy")
    assert sample.regime == "real_paired"
    assert not sample.identity_target
    assert torch.equal(sample.noisy, torch.from_numpy(item.noisy))


def test_quantization_uses_effective_white_minus_black_code_span():
    x = torch.full((4, 2, 2), 0.5)
    span = np.asarray([959.0, 959.0, 959.0, 959.0], dtype=np.float32)
    q = quantize_normalized(x, 10, span)
    expected = torch.round(x * 959.0) / 959.0
    assert torch.equal(q, expected)


def test_lsc_already_applied_keeps_identity_remaining_lsc_and_conservative_trust(tmp_path: Path):
    clean = np.full((4, 20, 20), 0.3 * 1023, dtype=np.float32)
    np.save(tmp_path / "clean_lsc.npy", clean)
    record = {
        "sample_id": "lsc_applied",
        "sensor_id": "A",
        "kind": "clean",
        "storage_layout": "canonical_packed",
        "cfa": {"arrangement": "RGGB"},
        "physics": {
            "shot_s": [0.01] * 4,
            "read_o": [0.0004] * 4,
            "black_level_raw": [0] * 4,
            "white_level_raw": [1023] * 4,
            "exposure_time_seconds": 0.01,
            "analog_gain": 2.0,
            "digital_gain": 1.0,
            "bit_depth": 10,
            "metadata_trust": 0.9,
            "noise_model_trust": 0.8,
            "black_level_trust": 0.7,
            "remaining_lsc_trust": 0.2,
            "lens_shading_already_applied": True,
        },
        "source": "unit",
        "license": "unit",
        "calibration_quality": "measured",
        "clean_gt_method": "synthetic_unit_reference",
        "clean_path": "clean_lsc.npy",
    }
    p = tmp_path / "lsc_manifest.json"
    p.write_text(json.dumps({"schema_version": 1, "dataset_name": "u", "dataset_version": "1", "records": [record]}))
    manifest = load_manifest(p)
    item = RawManifestDataset(manifest, manifest.records)[0]
    cfg = NoiseSynthesisConfig(heavy_tail_probability=0.0, hot_pixel_probability=0.0, lsc_max_gain_range=(2.0, 2.0))
    sample = PhysicsConditionedSampleBuilder(cfg, seed=5).build(item, regime="moderate")
    assert torch.equal(sample.remaining_lsc, torch.ones_like(sample.remaining_lsc))
    assert torch.all(sample.conditioning[12] == 0.7)


def test_active_remaining_lsc_includes_lsc_trust_in_conservative_metadata_channel(tmp_path: Path):
    clean = np.full((4, 20, 20), 0.3 * 1023, dtype=np.float32)
    np.save(tmp_path / "clean_lsc_active.npy", clean)
    record = {
        "sample_id": "lsc_active",
        "sensor_id": "A",
        "kind": "clean",
        "storage_layout": "canonical_packed",
        "cfa": {"arrangement": "RGGB"},
        "physics": {
            "shot_s": [0.01] * 4,
            "read_o": [0.0004] * 4,
            "black_level_raw": [0] * 4,
            "white_level_raw": [1023] * 4,
            "exposure_time_seconds": 0.01,
            "analog_gain": 2.0,
            "digital_gain": 1.0,
            "bit_depth": 10,
            "metadata_trust": 0.9,
            "noise_model_trust": 0.8,
            "black_level_trust": 0.7,
            "remaining_lsc_trust": 0.2,
            "lens_shading_already_applied": False,
        },
        "source": "unit",
        "license": "unit",
        "calibration_quality": "measured",
        "clean_gt_method": "synthetic_unit_reference",
        "clean_path": "clean_lsc_active.npy",
    }
    p = tmp_path / "lsc_active_manifest.json"
    p.write_text(json.dumps({"schema_version": 1, "dataset_name": "u", "dataset_version": "1", "records": [record]}))
    manifest = load_manifest(p)
    item = RawManifestDataset(manifest, manifest.records)[0]
    cfg = NoiseSynthesisConfig(heavy_tail_probability=0.0, hot_pixel_probability=0.0, lsc_max_gain_range=(2.0, 2.0), lsc_channel_imbalance=0.0)
    sample = PhysicsConditionedSampleBuilder(cfg, seed=5).build(item, regime="moderate")
    assert torch.all(sample.conditioning[12] == 0.2)
