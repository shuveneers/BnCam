import json
from pathlib import Path

import numpy as np
import torch
import yaml

from spectra_train.train_teacher import run_training


def _physics():
    return {
        "shot_s": [0.003]*4, "read_o": [0.0001]*4,
        "black_level_raw": [0]*4, "white_level_raw": [1023]*4,
        "exposure_time_seconds": 0.01, "analog_gain": 1.5, "digital_gain": 1.0,
        "bit_depth": 10, "metadata_trust": 0.9,
    }


def _make_record(sample_id, sensor, kind, path_key, filename):
    d = {
        "sample_id": sample_id, "sensor_id": sensor, "kind": kind,
        "storage_layout": "canonical_packed", "cfa": {"arrangement": "RGGB"},
        "physics": _physics(), "source": "unit", "license": "unit", "calibration_quality": "measured", "clean_gt_method": "synthetic_unit_reference",
    }
    d[path_key] = filename
    return d


def test_end_to_end_reference_training_writes_provenance_checkpoint_and_model_card(tmp_path: Path):
    records = []
    rng = np.random.default_rng(2)
    for sensor in ("A", "B", "C"):
        clean = (0.2 + rng.random((4, 24, 24), dtype=np.float32) * 0.4) * 1023
        name = f"clean_{sensor}.npy"
        np.save(tmp_path / name, clean.astype(np.float32))
        records.append(_make_record(f"clean_{sensor}", sensor, "clean", "clean_path", name))
    dark = 64 + rng.normal(0, 1, (4, 28, 28)).astype(np.float32)
    np.save(tmp_path / "dark_A.npy", dark)
    records.append(_make_record("dark_A", "A", "dark", "dark_path", "dark_A.npy"))
    manifest = {"schema_version": 1, "dataset_name": "unit", "dataset_version": "1", "records": records}
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))

    cfg = {
        "schema_version": 1,
        "dataset": {"manifest": "manifest.json", "sensor_split": {"train_sensors": ["A"], "validation_sensors": ["B"], "test_sensors": ["C"]}},
        "model": {"name": "unit_teacher", "widths": [8,12,16,24], "encoder_blocks": [1,1,1], "bottleneck_blocks": 1, "decoder_blocks": [1,1,1]},
        "training": {"seed": 11, "deterministic": True, "device": "cpu", "run_dir": "run", "repo_root": str(tmp_path), "batch_size": 1, "steps_per_epoch": 1, "crop_size": 16, "evaluation_crop_size": 16},
        "optimizer": {"lr": 1e-4, "weight_decay": 0.0},
        "loss_weights": {"frequency": 0.0, "isp_proxy": 0.0},
        "synthesis": {"heavy_tail_probability": 0.0, "hot_pixel_probability": 0.0, "lsc_max_gain_range": [1.0,1.5]},
        "curriculum": [{"name": "A_ideal_physics", "epochs": 1, "crop_size": 16}, {"name": "D_real_paired", "epochs": 1, "crop_size": 16}, {"name": "F_held_out_validation", "validation_only": True}],
    }
    (tmp_path / "config.yaml").write_text(yaml.safe_dump(cfg))
    summary = run_training(tmp_path / "config.yaml")
    assert Path(summary["checkpoint"]).exists()
    assert len(summary["checkpoint_sha256"]) == 64
    assert (tmp_path / "run" / "run_provenance.json").exists()
    assert (tmp_path / "run" / "summary.json").exists()
    assert (tmp_path / "run" / "MODEL_CARD.md").exists()
    provenance = json.loads((tmp_path / "run" / "run_provenance.json").read_text())
    assert provenance["dataset_manifest_sha256"]
    assert provenance["rng_seed"] == 11
    assert provenance["sensor_split"]["test_sensors"] == ["C"]
    assert summary["validation_metrics"]
    assert summary["zero_shot_test_metrics"]
    paired_stage = [row for row in summary["history"] if row["stage"] == "D_real_paired"]
    assert paired_stage == [{"epoch": 1, "stage": "D_real_paired", "skipped": True, "reason": "no_real_paired_training_records"}]
