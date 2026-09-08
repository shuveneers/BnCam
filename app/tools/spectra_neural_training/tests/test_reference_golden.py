from pathlib import Path

import numpy as np
import torch

from spectra_train.export_student import export_student_checkpoint
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig
from spectra_train.reference_inference import (
    build_physics_golden_inputs,
    generate_golden_vector,
    read_golden_vector,
    run_reference_inference,
)


def _export_fixture(tmp_path: Path) -> Path:
    torch.manual_seed(41)
    cfg = StudentConfig(
        name="golden_unit", variant="research", widths=(8, 12, 16, 24),
        encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1),
    )
    model = PhysicsConditionedStudent(cfg)
    checkpoint = tmp_path / "student.pt"
    torch.save({
        "schema_version": 1,
        "student_config": cfg.to_dict(),
        "student_state": model.state_dict(),
        "teacher_checkpoint_sha256": "11" * 32,
        "run_provenance_sha256": "22" * 32,
    }, checkpoint)
    export_student_checkpoint(
        checkpoint, tmp_path / "export", model_version="golden-unit",
        recommended_inner_tile_packed=64, release_status="TEST_ONLY_UNTRAINED_CONTRACT_FIXTURE",
    )
    return tmp_path / "export" / "model_manifest.json"


def test_reference_inference_is_bit_exact_for_repeated_cpu_calls(tmp_path: Path):
    manifest = _export_fixture(tmp_path)
    inputs = build_physics_golden_inputs(16, 24, seed=3)
    a = run_reference_inference(manifest, inputs["conditioning"], inputs["global_condition"])
    b = run_reference_inference(manifest, inputs["conditioning"], inputs["global_condition"])
    assert set(a) == set(b)
    assert all(np.array_equal(a[k], b[k]) for k in a)


def test_golden_vector_contains_required_physics_controls_and_outputs(tmp_path: Path):
    manifest = _export_fixture(tmp_path)
    golden = tmp_path / "golden.zip"
    meta = generate_golden_vector(manifest, golden, height=16, width=24, seed=4)
    loaded_meta, arrays = read_golden_vector(golden)
    assert meta == loaded_meta
    required = {
        "conditioning", "global_condition", "raw_normalized", "shot_s", "read_o",
        "remaining_lsc", "metadata_trust", "headroom", "black_level_raw", "white_level_raw",
        "user_controls", "expected_residual", "expected_posterior_variance", "expected_clean_raw",
    }
    assert required <= set(arrays)
    assert arrays["conditioning"].shape == (14, 16, 24)
    assert arrays["user_controls"].shape == (7,)
    assert arrays["expected_clean_raw"].shape == (1, 4, 16, 24)
    assert loaded_meta["control_semantics"].startswith("full-authority backend fixture")


def test_golden_zip_bytes_are_deterministic_for_same_model_and_seed(tmp_path: Path):
    manifest = _export_fixture(tmp_path)
    a = tmp_path / "a.zip"
    b = tmp_path / "b.zip"
    generate_golden_vector(manifest, a, height=16, width=24, seed=8)
    generate_golden_vector(manifest, b, height=16, width=24, seed=8)
    assert a.read_bytes() == b.read_bytes()


def test_clipped_golden_cell_remains_identity_in_expected_output(tmp_path: Path):
    manifest = _export_fixture(tmp_path)
    inputs = build_physics_golden_inputs(16, 24, seed=5)
    out = run_reference_inference(manifest, inputs["conditioning"], inputs["global_condition"])
    # Shared headroom is zero at [0,0] because all four CFA samples are clipped.
    assert np.array_equal(out["clean_raw"][0, :, 0, 0], inputs["raw_normalized"][:, 0, 0])
    assert np.count_nonzero(out["residual"][0, :, 0, 0]) == 0


def test_embedded_phase3_contract_fixture_matches_reference_exactly():
    root = Path(__file__).parent / "golden" / "student_v1_contract"
    meta, arrays = read_golden_vector(root / "golden_vector.zip")
    out = run_reference_inference(root / "model_manifest.json", arrays["conditioning"], arrays["global_condition"])
    assert len(meta["model_content_sha256"]) == 64
    for name, value in out.items():
        assert np.array_equal(value, arrays[f"expected_{name}"])


def test_golden_array_tamper_is_detected(tmp_path: Path):
    import io
    import zipfile

    manifest = _export_fixture(tmp_path)
    golden = tmp_path / "golden.zip"
    generate_golden_vector(manifest, golden, height=16, width=24, seed=9)
    tampered = tmp_path / "tampered.zip"
    with zipfile.ZipFile(golden, "r") as src, zipfile.ZipFile(tampered, "w") as dst:
        for name in src.namelist():
            blob = src.read(name)
            if name == "arrays/shot_s.npy":
                blob = bytearray(blob)
                blob[-1] ^= 0x01
                blob = bytes(blob)
            dst.writestr(name, blob)
    try:
        read_golden_vector(tampered)
        assert False, "tampered golden array must fail integrity validation"
    except ValueError as exc:
        assert "integrity failure" in str(exc)
