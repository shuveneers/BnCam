from pathlib import Path

import torch.nn as nn

from spectra_train.contracts import GLOBAL_CONDITIONING_FIELDS, SPATIAL_CONDITIONING_CHANNELS
from spectra_train.export_student import verify_exported_student
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig
from spectra_train.reference_inference import read_golden_vector


def test_phase3_conditioning_has_no_device_identity_shortcuts():
    names = [x.lower() for x in (*SPATIAL_CONDITIONING_CHANNELS, *GLOBAL_CONDITIONING_FIELDS)]
    forbidden = ("phone", "manufacturer", "sensor_id", "sensor_model", "lens_id", "device_model")
    assert not any(token in name for name in names for token in forbidden)


def test_phase3_baseline_export_graph_is_mobile_and_ablation_free():
    model = PhysicsConditionedStudent()
    assert not model.config.global_lowres_context
    assert not model.config.fine_coarse_residual_head
    assert not any(isinstance(m, (nn.LayerNorm, nn.ConvTranspose2d, nn.MultiheadAttention)) for m in model.modules())
    contract = model.export_primitive_contract()
    assert contract["conv_kernels"] == [1, 3]
    assert contract["nearest_upsample"]


def test_embedded_phase3_model_and_golden_are_integrity_bound():
    root = Path(__file__).parent / "golden" / "student_v1_contract"
    model = verify_exported_student(root / "model_manifest.json")
    golden, arrays = read_golden_vector(root / "golden_vector.zip")
    assert golden["model_content_sha256"] == model["model_content_sha256"]
    assert len(golden["array_sha256"]) == len(arrays)
    assert model["precision"] == "fp16"
    assert model["release_status"] == "TEST_ONLY_UNTRAINED_CONTRACT_FIXTURE"


def test_default_student_parameter_targets_remain_in_prompt_classes():
    hq = PhysicsConditionedStudent(StudentConfig()).parameter_count()
    lite = PhysicsConditionedStudent(StudentConfig.lite()).parameter_count()
    assert 3_000_000 <= hq <= 6_000_000
    assert 1_000_000 <= lite <= 2_500_000
