import json
from dataclasses import asdict
from pathlib import Path

import numpy as np
import torch
import yaml

from spectra_train.export_student import export_student_checkpoint
from spectra_train.heldout_scorecard import run_heldout_scorecard
from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig
from spectra_train.reference_inference import generate_golden_vector, read_golden_vector
from spectra_train.reproducibility import sha256_file
from spectra_train.train_student import run_student_training


def _physics():
    return {
        "shot_s": [0.003] * 4, "read_o": [0.0001] * 4,
        "black_level_raw": [0] * 4, "white_level_raw": [1023] * 4,
        "exposure_time_seconds": 0.01, "analog_gain": 1.2, "digital_gain": 1.0,
        "bit_depth": 10, "metadata_trust": 0.9,
    }


def test_phase3_checkpoint_to_export_to_golden_to_heldout_scorecard(tmp_path: Path):
    rng = np.random.default_rng(91)
    records = []
    for sensor in ("A", "B", "C"):
        raw = (0.2 + rng.random((4, 24, 24), dtype=np.float32) * 0.35) * 1023.0
        name = f"{sensor}.npy"
        np.save(tmp_path / name, raw.astype(np.float32))
        records.append({
            "sample_id": sensor, "sensor_id": sensor, "kind": "clean",
            "storage_layout": "canonical_packed", "cfa": {"arrangement": "RGGB"},
            "physics": _physics(), "source": "unit", "license": "unit",
            "calibration_quality": "measured", "clean_gt_method": "synthetic_unit_reference",
            "clean_path": name,
        })
    (tmp_path / "manifest.json").write_text(json.dumps({
        "schema_version": 1, "dataset_name": "unit", "dataset_version": "1", "records": records,
    }))

    teacher_cfg = TeacherConfig(widths=(8, 12, 16, 24), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1))
    teacher = PhysicsConditionedTeacher(teacher_cfg)
    teacher_path = tmp_path / "teacher.pt"
    torch.save({"schema_version": 1, "model_config": asdict(teacher_cfg), "model_state": teacher.state_dict()}, teacher_path)
    teacher_hash = sha256_file(teacher_path)

    train_cfg = {
        "schema_version": 1,
        "dataset": {"manifest": "manifest.json", "sensor_split": {"train_sensors": ["A"], "validation_sensors": ["B"], "test_sensors": ["C"]}},
        "teacher": {"checkpoint": "teacher.pt", "expected_sha256": teacher_hash},
        "student": {"name": "unit_student", "variant": "research", "widths": [8, 12, 16, 24], "encoder_blocks": [1, 1, 1], "bottleneck_blocks": 1, "decoder_blocks": [1, 1, 1]},
        "training": {"seed": 77, "deterministic": True, "device": "cpu", "run_dir": "run", "repo_root": str(tmp_path), "batch_size": 1, "steps_per_epoch": 1, "evaluation_crop_size": 16},
        "optimizer": {"lr": 1e-4, "weight_decay": 0.0},
        "loss_weights": {"frequency": 0.0, "isp_proxy": 0.0},
        "distillation_weights": {"feature_energy": 0.01},
        "synthesis": {"heavy_tail_probability": 0.0, "hot_pixel_probability": 0.0, "lsc_max_gain_range": [1.0, 1.0]},
        "curriculum": [{"name": "unit", "epochs": 1, "crop_size": 16}],
    }
    train_path = tmp_path / "train.yaml"
    train_path.write_text(yaml.safe_dump(train_cfg))
    summary = run_student_training(train_path)

    exported = export_student_checkpoint(summary["checkpoint"], tmp_path / "export", model_version="unit", recommended_inner_tile_packed=64, release_status="TEST_ONLY")
    golden_path = tmp_path / "golden.zip"
    golden_meta = generate_golden_vector(tmp_path / "export" / "model_manifest.json", golden_path, height=16, width=24, seed=77)
    read_meta, _ = read_golden_vector(golden_path)
    assert golden_meta["model_content_sha256"] == exported["model_content_sha256"] == read_meta["model_content_sha256"]

    score_cfg = {
        "schema_version": 1,
        "dataset": train_cfg["dataset"],
        "student": {"manifest": "export/model_manifest.json"},
        "teacher": {"checkpoint": "teacher.pt", "expected_sha256": teacher_hash},
        "evaluation": {"seed": 12, "crop_size": 16},
        "synthesis": train_cfg["synthesis"],
        "output": {"dir": "score"},
    }
    score_path = tmp_path / "score.yaml"
    score_path.write_text(yaml.safe_dump(score_cfg))
    score = run_heldout_scorecard(score_path)
    assert score["heldout_sensors"] == ["C"]
    assert score["model_content_sha256"] == exported["model_content_sha256"]
    assert score["teacher_checkpoint_sha256"] == teacher_hash
    assert (tmp_path / "score" / "MODEL_CARD_STUDENT.md").exists()
