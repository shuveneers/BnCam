import torch

from spectra_train.contracts import GLOBAL_CONDITIONING_FIELDS
from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig


def _input(batch=1, h=32, w=40):
    torch.manual_seed(4)
    cond = torch.zeros(batch, 14, h, w)
    cond[:, 0:4] = torch.rand(batch, 4, h, w) * 0.8
    sigma = torch.full((batch, 4, h, w), 0.02)
    cond[:, 4:8] = torch.log(sigma)
    cond[:, 8:12] = 1.0
    cond[:, 12:13] = 0.9
    cond[:, 13:14] = 1.0
    global_c = torch.zeros(batch, len(GLOBAL_CONDITIONING_FIELDS))
    return cond, global_c


def test_teacher_output_contract_and_sigma_bound():
    model = PhysicsConditionedTeacher(TeacherConfig(widths=(16, 24, 32, 48), encoder_blocks=(1,1,1), bottleneck_blocks=1, decoder_blocks=(1,1,1)))
    cond, global_c = _input()
    out = model(cond, global_c)
    assert out.clean_raw.shape == (1, 4, 32, 40)
    assert out.posterior_variance.shape == out.clean_raw.shape
    assert out.confidence.shape == (1, 1, 32, 40)
    assert torch.all(out.posterior_variance > 0)
    assert torch.max(torch.abs(out.sigma_normalized_residual)) <= 4.0 + 1e-5


def test_clipped_headroom_forces_identity():
    model = PhysicsConditionedTeacher(TeacherConfig(widths=(16, 24, 32, 48), encoder_blocks=(1,1,1), bottleneck_blocks=1, decoder_blocks=(1,1,1)))
    cond, global_c = _input(h=24, w=24)
    cond[:, 13:14] = 0.0
    out = model(cond, global_c)
    assert torch.equal(out.clean_raw, cond[:, 0:4])
    assert torch.count_nonzero(out.residual) == 0


def test_eval_is_deterministic_for_same_input():
    torch.manual_seed(10)
    model = PhysicsConditionedTeacher(TeacherConfig(widths=(16, 24, 32, 48), encoder_blocks=(1,1,1), bottleneck_blocks=1, decoder_blocks=(1,1,1))).eval()
    cond, global_c = _input(h=24, w=24)
    with torch.no_grad():
        a = model(cond, global_c).clean_raw
        b = model(cond, global_c).clean_raw
    assert torch.equal(a, b)


def test_default_teacher_is_in_declared_teacher_parameter_class():
    model = PhysicsConditionedTeacher()
    count = model.parameter_count()
    assert 10_000_000 <= count <= 40_000_000, count


def test_high_snr_absolute_correction_collapses_with_sigma():
    model = PhysicsConditionedTeacher(TeacherConfig(widths=(16, 24, 32, 48), encoder_blocks=(1,1,1), bottleneck_blocks=1, decoder_blocks=(1,1,1))).eval()
    cond, global_c = _input(h=24, w=24)
    sigma = 1.0e-7
    cond[:, 4:8] = torch.log(torch.full_like(cond[:, 4:8], sigma))
    with torch.no_grad():
        out = model(cond, global_c)
    assert torch.max(torch.abs(out.residual)) <= 4.0 * sigma + 1.0e-8
