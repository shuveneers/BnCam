import json
from dataclasses import asdict
from pathlib import Path

import numpy as np
import pytest
import torch
import yaml

from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig
from spectra_train.reproducibility import sha256_file
from spectra_train.train_student import run_student_training


def _physics():
    return {
        "shot_s": [0.003] * 4,
        "read_o": [0.0001] * 4,
        "black_level_raw": [0] * 4,
        "white_level_raw": [1023] * 4,
        "exposure_time_seconds": 0.01,
        "analog_gain": 1.5,
        "digital_gain": 1.0,
        "bit_depth": 10,
        "metadata_trust": 0.9,
    }


def _record(sample_id, sensor, filename):
    return {
        "sample_id": sample_id,
        "sensor_id": sensor,
        "kind": "clean",
        "storage_layout": "canonical_packed",
        "cfa": {"arrangement": "RGGB"},
        "physics": _physics(),
        "source": "unit",
        "license": "unit",
        "calibration_quality": "measured",
        "clean_gt_method": "synthetic_unit_reference",
        "clean_path": filename,
    }


def _fixture(tmp_path: Path):
    rng = np.random.default_rng(5)
    records = []
    for sensor in ("A", "B", "C"):
        clean = (0.2 + rng.random((4, 24, 24), dtype=np.float32) * 0.4) * 1023.0
        name = f"clean_{sensor}.npy"
        np.save(tmp_path / name, clean.astype(np.float32))
        records.append(_record(f"sample_{sensor}", sensor, name))
    (tmp_path / "manifest.json").write_text(json.dumps({
        "schema_version": 1,
        "dataset_name": "unit",
        "dataset_version": "1",
        "records": records,
    }))

    teacher_cfg = TeacherConfig(
        widths=(8, 12, 16, 24), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1)
    )
    teacher = PhysicsConditionedTeacher(teacher_cfg)
    teacher_path = tmp_path / "teacher.pt"
    torch.save({
        "schema_version": 1,
        "model_config": asdict(teacher_cfg),
        "model_state": teacher.state_dict(),
    }, teacher_path)
    teacher_hash = sha256_file(teacher_path)

    cfg = {
        "schema_version": 1,
        "dataset": {
            "manifest": "manifest.json",
            "sensor_split": {"train_sensors": ["A"], "validation_sensors": ["B"], "test_sensors": ["C"]},
        },
        "teacher": {"checkpoint": "teacher.pt", "expected_sha256": teacher_hash},
        "student": {
            "name": "unit_student", "variant": "research",
            "widths": [8, 12, 16, 24], "encoder_blocks": [1, 1, 1],
            "bottleneck_blocks": 1, "decoder_blocks": [1, 1, 1],
        },
        "training": {
            "seed": 71, "deterministic": True, "device": "cpu", "run_dir": "student_run",
            "repo_root": str(tmp_path), "batch_size": 1, "steps_per_epoch": 1,
            "evaluation_crop_size": 16, "grad_clip_norm": 1.0,
        },
        "optimizer": {"lr": 1e-4, "weight_decay": 0.0},
        "loss_weights": {"frequency": 0.0, "isp_proxy": 0.0},
        "distillation_weights": {"feature_energy": 0.01},
        "synthesis": {"heavy_tail_probability": 0.0, "hot_pixel_probability": 0.0, "lsc_max_gain_range": [1.0, 1.0]},
        "curriculum": [{"name": "unit_16", "epochs": 1, "crop_size": 16}],
    }
    config_path = tmp_path / "student.yaml"
    config_path.write_text(yaml.safe_dump(cfg))
    return config_path, teacher_hash


def test_student_training_is_real_distillation_and_keeps_test_sensor_out_of_optimization(tmp_path: Path):
    config_path, teacher_hash = _fixture(tmp_path)
    summary = run_student_training(config_path)
    assert Path(summary["checkpoint"]).exists()
    assert summary["teacher_checkpoint_sha256"] == teacher_hash
    assert summary["history"][0]["crop_size"] == 16
    assert "teacher_residual_sigma_mae" in summary["zero_shot_test_metrics"]
    provenance = json.loads((tmp_path / "student_run" / "run_provenance.json").read_text())
    assert provenance["teacher_hash"] == teacher_hash
    assert provenance["sensor_split"]["test_sensors"] == ["C"]


def test_student_training_rejects_wrong_teacher_hash(tmp_path: Path):
    config_path, _ = _fixture(tmp_path)
    cfg = yaml.safe_load(config_path.read_text())
    cfg["teacher"]["expected_sha256"] = "0" * 64
    config_path.write_text(yaml.safe_dump(cfg))
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        run_student_training(config_path)
