import json
from pathlib import Path

import pytest
import torch

from spectra_train.export_student import export_student_checkpoint, verify_exported_student
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig, theoretical_receptive_field


def _checkpoint(path: Path, *, global_context=False):
    torch.manual_seed(31)
    cfg = StudentConfig(
        name="unit_student", variant="research", widths=(8, 12, 16, 24),
        encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1),
        global_lowres_context=global_context,
    )
    model = PhysicsConditionedStudent(cfg)
    torch.save({
        "schema_version": 1,
        "student_config": cfg.to_dict(),
        "student_state": model.state_dict(),
        "teacher_checkpoint_sha256": "ab" * 32,
        "run_provenance_sha256": "cd" * 32,
    }, path)
    return cfg, model


def test_fp16_export_has_complete_manifest_and_deterministic_bytes(tmp_path: Path):
    cfg, model = _checkpoint(tmp_path / "student.pt")
    first = tmp_path / "a"
    second = tmp_path / "b"
    a = export_student_checkpoint(tmp_path / "student.pt", first, model_version="unit", recommended_inner_tile_packed=64)
    b = export_student_checkpoint(tmp_path / "student.pt", second, model_version="unit", recommended_inner_tile_packed=64)
    assert (first / "student_weights.fp16.bin").read_bytes() == (second / "student_weights.fp16.bin").read_bytes()
    assert (first / "model_manifest.json").read_bytes() == (second / "model_manifest.json").read_bytes()
    assert a["model_content_sha256"] == b["model_content_sha256"]
    assert a["precision"] == "fp16"
    assert a["weights_bytes"] == model.parameter_count() * 2
    assert a["conditioning_schema"]["spatial_channels"][0:4] == ["raw_r", "raw_g1", "raw_g2", "raw_b"]
    assert a["supported_cfa_contract"]["channels"] == ["R", "G1", "G2", "B"]
    assert a["minimum_symmetric_halo_packed"] == (theoretical_receptive_field(cfg) - 1) // 2
    assert verify_exported_student(first / "model_manifest.json")["weights_sha256"] == a["weights_sha256"]


def test_corrupted_weight_bytes_fail_closed(tmp_path: Path):
    _checkpoint(tmp_path / "student.pt")
    export_student_checkpoint(tmp_path / "student.pt", tmp_path / "out", recommended_inner_tile_packed=64)
    weights = tmp_path / "out" / "student_weights.fp16.bin"
    data = bytearray(weights.read_bytes())
    data[len(data) // 2] ^= 0x01
    weights.write_bytes(data)
    with pytest.raises(ValueError, match="weights SHA-256 mismatch"):
        verify_exported_student(tmp_path / "out" / "model_manifest.json")


def test_global_lowres_research_branch_is_not_silently_exported_as_v1(tmp_path: Path):
    _checkpoint(tmp_path / "student.pt", global_context=True)
    with pytest.raises(ValueError, match="not eligible"):
        export_student_checkpoint(tmp_path / "student.pt", tmp_path / "out", recommended_inner_tile_packed=64)


def test_manifest_tamper_fails_model_content_hash(tmp_path: Path):
    _checkpoint(tmp_path / "student.pt")
    export_student_checkpoint(tmp_path / "student.pt", tmp_path / "out", recommended_inner_tile_packed=64)
    manifest_path = tmp_path / "out" / "model_manifest.json"
    m = json.loads(manifest_path.read_text())
    m["model_version"] = "tampered"
    manifest_path.write_text(json.dumps(m, indent=2, sort_keys=True) + "\n")
    with pytest.raises(ValueError, match="model content SHA-256 mismatch"):
        verify_exported_student(manifest_path)
