import torch
import torch.nn as nn

from spectra_train.contracts import GLOBAL_CONDITIONING_FIELDS
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig, theoretical_receptive_field


def _input(batch=1, h=32, w=40, sigma=0.02):
    torch.manual_seed(17)
    cond = torch.zeros(batch, 14, h, w)
    cond[:, 0:4] = torch.rand(batch, 4, h, w) * 0.8
    cond[:, 4:8] = torch.log(torch.full((batch, 4, h, w), sigma))
    cond[:, 8:12] = 1.0
    cond[:, 12:13] = 0.9
    cond[:, 13:14] = 1.0
    global_c = torch.zeros(batch, len(GLOBAL_CONDITIONING_FIELDS))
    return cond, global_c


def _tiny(**kwargs):
    return StudentConfig(
        name="unit_student", variant="research", widths=(12, 16, 20, 24),
        encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1),
        **kwargs,
    )


def test_student_output_is_sigma_bounded_and_posterior_positive():
    model = PhysicsConditionedStudent(_tiny())
    cond, global_c = _input(h=24, w=28)
    out = model(cond, global_c)
    assert out.clean_raw.shape == (1, 4, 24, 28)
    assert out.posterior_variance.shape == out.clean_raw.shape
    assert torch.all(out.posterior_variance > 0)
    assert torch.max(torch.abs(out.sigma_normalized_residual)) <= 4.0 + 1e-5


def test_student_clip_headroom_and_high_snr_are_identity_safe():
    model = PhysicsConditionedStudent(_tiny()).eval()
    cond, global_c = _input(h=24, w=24)
    cond[:, 13:14] = 0.0
    with torch.no_grad():
        clipped = model(cond, global_c)
    assert torch.equal(clipped.clean_raw, cond[:, 0:4])
    assert torch.count_nonzero(clipped.residual) == 0

    cond, global_c = _input(h=24, w=24, sigma=1.0e-7)
    with torch.no_grad():
        high_snr = model(cond, global_c)
    assert torch.max(torch.abs(high_snr.residual)) <= 4.0e-7 + 5.0e-8


def test_student_uses_only_declared_mobile_graph_families():
    model = PhysicsConditionedStudent(_tiny())
    forbidden = (nn.LayerNorm, nn.ConvTranspose2d, nn.MultiheadAttention)
    assert not any(isinstance(m, forbidden) for m in model.modules())
    convs = [m for m in model.modules() if isinstance(m, nn.Conv2d)]
    assert convs
    assert all(m.kernel_size in {(1, 1), (3, 3)} for m in convs)
    assert model.export_primitive_contract()["layer_norm"] is False


def test_default_hq_and_lite_parameter_classes_match_prompt_targets():
    hq = PhysicsConditionedStudent().parameter_count()
    lite = PhysicsConditionedStudent(StudentConfig.lite()).parameter_count()
    assert 3_000_000 <= hq <= 6_000_000, hq
    assert 1_000_000 <= lite <= 2_500_000, lite


def test_optional_global_context_is_explicit_second_input_not_hidden_sensor_identity():
    cfg = _tiny(global_lowres_context=True)
    model = PhysicsConditionedStudent(cfg)
    cond, global_c = _input(h=24, w=24)
    try:
        model(cond, global_c)
        assert False, "global context branch must require its explicit spatial context input"
    except ValueError as exc:
        assert "global_spatial_condition" in str(exc)
    out = model(cond, global_c, global_spatial_condition=cond)
    assert out.clean_raw.shape == cond[:, 0:4].shape


def test_optional_fine_coarse_heads_sum_before_single_physical_bound():
    model = PhysicsConditionedStudent(_tiny(fine_coarse_residual_head=True))
    cond, global_c = _input(h=24, w=24)
    out = model(cond, global_c)
    assert out.fine_sigma_residual is not None
    assert out.coarse_sigma_residual is not None
    assert torch.max(torch.abs(out.sigma_normalized_residual)) <= 4.0 + 1e-5


def test_receptive_field_is_derived_and_symmetric_halo_is_integer():
    rf = theoretical_receptive_field(_tiny())
    assert rf > 1 and rf % 2 == 1
    assert (rf - 1) % 2 == 0


def test_student_eval_repeatability_is_bit_exact_on_cpu():
    torch.manual_seed(99)
    model = PhysicsConditionedStudent(_tiny()).eval()
    cond, global_c = _input(h=24, w=24)
    with torch.no_grad():
        a = model(cond, global_c).clean_raw
        b = model(cond, global_c).clean_raw
    assert torch.equal(a, b)
