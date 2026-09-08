import json
from dataclasses import asdict
from pathlib import Path

import numpy as np
import pytest
import torch
import yaml

from spectra_train.export_student import export_student_checkpoint
from spectra_train.heldout_scorecard import run_heldout_scorecard
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig
from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig
from spectra_train.reproducibility import sha256_file
from spectra_train.student_model_card import render_student_model_card


def _physics():
    return {
        "shot_s": [0.003] * 4, "read_o": [0.0001] * 4,
        "black_level_raw": [0] * 4, "white_level_raw": [1023] * 4,
        "exposure_time_seconds": 0.01, "analog_gain": 1.5, "digital_gain": 1.0,
        "bit_depth": 10, "metadata_trust": 0.9,
    }


def _record(sample_id, sensor, filename):
    return {
        "sample_id": sample_id, "sensor_id": sensor, "kind": "clean",
        "storage_layout": "canonical_packed", "cfa": {"arrangement": "RGGB"},
        "physics": _physics(), "source": "unit", "license": "unit",
        "calibration_quality": "measured", "clean_gt_method": "synthetic_unit_reference",
        "clean_path": filename,
    }


def _fixture(tmp_path: Path):
    rng = np.random.default_rng(12)
    records = []
    for sensor in ("A", "B", "C"):
        raw = (0.15 + rng.random((4, 24, 24), dtype=np.float32) * 0.5) * 1023.0
        name = f"{sensor}.npy"
        np.save(tmp_path / name, raw.astype(np.float32))
        records.append(_record(f"s_{sensor}", sensor, name))
    (tmp_path / "manifest.json").write_text(json.dumps({
        "schema_version": 1, "dataset_name": "unit", "dataset_version": "1", "records": records,
    }))

    teacher_cfg = TeacherConfig(widths=(8, 12, 16, 24), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1))
    teacher = PhysicsConditionedTeacher(teacher_cfg)
    teacher_path = tmp_path / "teacher.pt"
    torch.save({"schema_version": 1, "model_config": asdict(teacher_cfg), "model_state": teacher.state_dict()}, teacher_path)
    teacher_hash = sha256_file(teacher_path)

    student_cfg = StudentConfig(name="unit_student", variant="research", widths=(8, 12, 16, 24), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1))
    student = PhysicsConditionedStudent(student_cfg)
    student_ckpt = tmp_path / "student.pt"
    torch.save({
        "schema_version": 1, "student_config": student_cfg.to_dict(), "student_state": student.state_dict(),
        "teacher_checkpoint_sha256": teacher_hash, "run_provenance_sha256": "55" * 32,
    }, student_ckpt)
    export_student_checkpoint(student_ckpt, tmp_path / "export", model_version="unit", recommended_inner_tile_packed=64, release_status="TEST_ONLY")

    config = {
        "schema_version": 1,
        "dataset": {"manifest": "manifest.json", "sensor_split": {"train_sensors": ["A"], "validation_sensors": ["B"], "test_sensors": ["C"]}},
        "student": {"manifest": "export/model_manifest.json"},
        "teacher": {"checkpoint": "teacher.pt", "expected_sha256": teacher_hash},
        "evaluation": {"seed": 7, "crop_size": 16},
        "synthesis": {"heavy_tail_probability": 0.0, "hot_pixel_probability": 0.0, "lsc_max_gain_range": [1.0, 1.0]},
        "output": {"dir": "score"},
    }
    config_path = tmp_path / "score.yaml"
    config_path.write_text(yaml.safe_dump(config))
    return config_path, teacher_hash


def test_scorecard_is_strictly_heldout_and_writes_machine_and_human_reports(tmp_path: Path):
    config_path, teacher_hash = _fixture(tmp_path)
    score = run_heldout_scorecard(config_path)
    assert score["heldout_sensors"] == ["C"]
    assert set(score["per_sensor"]) == {"C"}
    assert score["teacher_checkpoint_sha256"] == teacher_hash
    assert score["quality_thresholds"] == "NOT_INVENTED_BY_PHASE3_TOOLING"
    assert score["per_sensor"]["C"]["evidence"]["evidence_class"] == "SYNTHETIC_FROM_CLEAN_ONLY"
    assert (tmp_path / "score" / "heldout_scorecard.json").exists()
    assert (tmp_path / "score" / "heldout_scorecard.md").exists()
    assert (tmp_path / "score" / "MODEL_CARD_STUDENT.md").exists()
    gates = score["per_sensor"]["C"]["contract_gates"]
    assert gates["all_metrics_finite"]
    assert gates["clipped_cells_untouched"]
    card = render_student_model_card(json.loads((tmp_path / "export" / "model_manifest.json").read_text()), score)
    assert "Held-out sensors: `['C']`" in card
    assert "Vulkan numerical parity is Phase 4 work" in card


def test_scorecard_rejects_teacher_not_bound_to_exported_student(tmp_path: Path):
    config_path, _ = _fixture(tmp_path)
    # Create another valid Teacher so only the identity/hash binding changes.
    cfg = TeacherConfig(widths=(8, 12, 16, 24), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1))
    torch.manual_seed(999)
    other = PhysicsConditionedTeacher(cfg)
    other_path = tmp_path / "other_teacher.pt"
    torch.save({"schema_version": 1, "model_config": asdict(cfg), "model_state": other.state_dict()}, other_path)
    yaml_cfg = yaml.safe_load(config_path.read_text())
    yaml_cfg["teacher"] = {"checkpoint": "other_teacher.pt", "expected_sha256": sha256_file(other_path)}
    config_path.write_text(yaml.safe_dump(yaml_cfg))
    with pytest.raises(ValueError, match="different Teacher"):
        run_heldout_scorecard(config_path)
