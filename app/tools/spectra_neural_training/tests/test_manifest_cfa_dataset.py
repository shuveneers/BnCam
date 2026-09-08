import json
from pathlib import Path

import numpy as np
import pytest

from spectra_train.cfa import canonical_offsets, canonical_pack_mosaic
from spectra_train.contracts import CfaSpec, RecordKind
from spectra_train.dataset import NoiseRegimeProbabilities, RawManifestDataset
from spectra_train.manifest import SensorSplitPolicy, load_manifest


def _physics():
    return {
        "shot_s": [0.01, 0.01, 0.01, 0.01],
        "read_o": [0.0001, 0.0001, 0.0001, 0.0001],
        "black_level_raw": [64, 64, 64, 64],
        "white_level_raw": [1023, 1023, 1023, 1023],
        "exposure_time_seconds": 0.01,
        "analog_gain": 2.0,
        "digital_gain": 1.0,
        "bit_depth": 10,
        "metadata_trust": 0.9,
        "structured_noise": {"row_periodicity": 0.2, "confidence": 0.8},
    }


def _record(sample_id, sensor, path, arrangement="RGGB"):
    return {
        "sample_id": sample_id,
        "sensor_id": sensor,
        "kind": "clean",
        "storage_layout": "mosaic",
        "cfa": {"arrangement": arrangement},
        "physics": _physics(),
        "source": "unit-test",
        "license": "test-only",
        "calibration_quality": "measured", "clean_gt_method": "synthetic_unit_reference",
        "clean_path": path,
    }


def test_all_bayer_patterns_pack_to_same_semantics():
    values = {"R": 10.0, "G": 20.0, "B": 30.0}
    for arrangement in ("RGGB", "GRBG", "GBRG", "BGGR"):
        grid = np.asarray([values[c] for c in arrangement], dtype=np.float32).reshape(2, 2)
        raw = np.tile(grid, (2, 3))
        packed = canonical_pack_mosaic(raw, CfaSpec(arrangement))
        assert np.all(packed[0] == 10.0)
        assert np.all(packed[1] == 20.0)
        assert np.all(packed[2] == 20.0)
        assert np.all(packed[3] == 30.0)


def test_crop_phase_changes_offsets_without_changing_channel_identity():
    base = CfaSpec("RGGB", offset_x=1, offset_y=0)
    assert canonical_offsets(base) == {"R": (1, 0), "G1": (0, 0), "G2": (1, 1), "B": (0, 1)}


def test_manifest_split_and_loader(tmp_path: Path):
    raw = np.arange(16, dtype=np.float32).reshape(4, 4) + 64.0
    records = []
    for i, sensor in enumerate(("A", "B", "C")):
        name = f"raw_{i}.npy"
        np.save(tmp_path / name, raw)
        records.append(_record(f"s{i}", sensor, name))
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps({
        "schema_version": 1,
        "dataset_name": "unit",
        "dataset_version": "1",
        "records": records,
    }))
    manifest = load_manifest(manifest_path)
    policy = SensorSplitPolicy(("A",), ("B",), ("C",))
    policy.validate(manifest.records)
    train = RawManifestDataset.for_split(manifest, policy, "train", [RecordKind.CLEAN])
    sample = train[0]
    assert sample.clean.shape == (4, 2, 2)
    assert sample.global_condition.ndim == 1
    assert sample.record.sensor_id == "A"  # governance metadata only
    assert not any("sensor" in str(x).lower() for x in sample.global_condition.tolist())


def test_sensor_leakage_is_rejected(tmp_path: Path):
    np.save(tmp_path / "r.npy", np.ones((4, 4), dtype=np.float32) * 128)
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps({
        "schema_version": 1,
        "dataset_name": "unit",
        "dataset_version": "1",
        "records": [_record("s0", "A", "r.npy")],
    }))
    m = load_manifest(p)
    with pytest.raises(ValueError, match="sensor leakage"):
        SensorSplitPolicy(("A",), ("A",), ("B",)).validate(m.records)


def test_default_identity_mix_is_30_percent():
    p = NoiseRegimeProbabilities()
    p.validate()
    assert p.identity == pytest.approx(0.30)


def test_clean_ground_truth_method_is_required(tmp_path: Path):
    np.save(tmp_path / "r.npy", np.ones((4, 4), dtype=np.float32) * 128)
    record = _record("s0", "A", "r.npy")
    record.pop("clean_gt_method")
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps({
        "schema_version": 1,
        "dataset_name": "unit",
        "dataset_version": "1",
        "records": [record],
    }))
    with pytest.raises(ValueError, match="clean_gt_method"):
        load_manifest(p)
