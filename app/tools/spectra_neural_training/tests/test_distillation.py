from pathlib import Path

import torch

from spectra_train.distillation import DistillationWeights, compute_student_distillation_loss, freeze_teacher
from spectra_train.losses import LossWeights, TeacherLossBatch
from spectra_train.model_student import PhysicsConditionedStudent, StudentConfig
from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig


def _inputs(batch=1, size=24):
    torch.manual_seed(23)
    clean = torch.rand(batch, 4, size, size) * 0.6
    noisy = (clean + torch.randn_like(clean) * 0.02).clamp(0.0, 1.0)
    sigma = torch.full_like(clean, 0.02)
    cond = torch.cat((
        noisy, torch.log(sigma), torch.ones_like(clean),
        torch.full((batch, 1, size, size), 0.9), torch.ones(batch, 1, size, size),
    ), dim=1)
    global_c = torch.zeros(batch, 18)
    return clean, noisy, sigma, cond, global_c


def _teacher():
    return PhysicsConditionedTeacher(TeacherConfig(
        widths=(16, 24, 32, 48), encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1)
    ))


def _student():
    return PhysicsConditionedStudent(StudentConfig(
        name="unit", variant="research", widths=(12, 16, 20, 24),
        encoder_blocks=(1, 1, 1), bottleneck_blocks=1, decoder_blocks=(1, 1, 1),
    ))


def test_teacher_freeze_and_feature_distillation_backpropagates_only_student():
    teacher = _teacher()
    freeze_teacher(teacher)
    assert not teacher.training
    assert not any(p.requires_grad for p in teacher.parameters())
    student = _student()
    clean, noisy, sigma, cond, global_c = _inputs()
    student_out, student_features = student(cond, global_c, return_features=True)
    with torch.no_grad():
        teacher_out, teacher_features = teacher.forward_with_features(cond, global_c)
    batch = TeacherLossBatch(clean, noisy, sigma.square(), torch.ones_like(clean), torch.tensor([False]))
    total, terms = compute_student_distillation_loss(
        student_out, teacher_out, student_features, teacher_features,
        batch, LossWeights(frequency=0.0, isp_proxy=0.0), DistillationWeights(),
    )
    assert torch.isfinite(total)
    assert {"distill_teacher_clean", "distill_teacher_residual", "distill_teacher_posterior", "distill_feature_energy"} <= set(terms)
    total.backward()
    assert any(p.grad is not None and torch.isfinite(p.grad).all() for p in student.parameters())
    assert all(p.grad is None for p in teacher.parameters())


def test_distillation_uses_teacher_residual_and_posterior_not_only_clean_rgb_proxy():
    teacher = _teacher().eval()
    student = _student().eval()
    clean, noisy, sigma, cond, global_c = _inputs()
    with torch.no_grad():
        s, sf = student(cond, global_c, return_features=True)
        t, tf = teacher.forward_with_features(cond, global_c)
    batch = TeacherLossBatch(clean, noisy, sigma.square(), torch.ones_like(clean), torch.tensor([False]))
    _, terms = compute_student_distillation_loss(s, t, sf, tf, batch, LossWeights(), DistillationWeights())
    assert terms["distill_teacher_residual"].item() >= 0.0
    assert terms["distill_teacher_posterior"].item() >= 0.0


def test_teacher_normal_forward_is_unchanged_by_feature_api():
    teacher = _teacher().eval()
    _, _, _, cond, global_c = _inputs()
    with torch.no_grad():
        ordinary = teacher(cond, global_c)
        featured, _ = teacher.forward_with_features(cond, global_c)
    assert torch.equal(ordinary.clean_raw, featured.clean_raw)
    assert torch.equal(ordinary.posterior_variance, featured.posterior_variance)
