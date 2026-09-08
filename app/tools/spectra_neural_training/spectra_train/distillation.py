from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Mapping

import torch
import torch.nn.functional as F

from .losses import LossWeights, TeacherLossBatch, compute_teacher_loss
from .model_student import StudentFeatures, StudentOutput
from .model_teacher import TeacherFeatures, TeacherOutput


@dataclass(frozen=True)
class DistillationWeights:
    teacher_clean: float = 0.50
    teacher_residual: float = 0.50
    teacher_posterior: float = 0.20
    teacher_confidence: float = 0.02
    feature_energy: float = 0.05

    def validate(self) -> None:
        for name, value in self.__dict__.items():
            if not torch.isfinite(torch.tensor(float(value))) or value < 0.0:
                raise ValueError(f"distillation weight {name} must be finite and non-negative")


def _charbonnier(a: torch.Tensor, b: torch.Tensor, eps: float = 1.0e-3) -> torch.Tensor:
    return torch.sqrt((a - b).square() + eps * eps).mean()


def _feature_energy(x: torch.Tensor) -> torch.Tensor:
    energy = torch.sqrt(x.square().mean(dim=1, keepdim=True) + 1.0e-8)
    scale = energy.mean(dim=(-2, -1), keepdim=True).clamp_min(1.0e-6)
    return energy / scale


def feature_distillation_loss(student: StudentFeatures, teacher: TeacherFeatures) -> torch.Tensor:
    """Parameter-free feature distillation across different channel widths.

    The Student does not inherit Teacher-only feature adapters or operators.
    We compare normalized spatial feature-energy maps at the bottleneck and
    final decoder, preserving production graph independence.
    """

    losses = []
    for s, t in ((student.bottleneck, teacher.bottleneck), (student.decoder0, teacher.decoder0)):
        target = _feature_energy(t.detach())
        source = _feature_energy(s)
        if target.shape[-2:] != source.shape[-2:]:
            target = F.interpolate(target, size=source.shape[-2:], mode="nearest")
        losses.append(F.l1_loss(source, target))
    return torch.stack(losses).mean()


def compute_student_distillation_loss(
    student_output: StudentOutput,
    teacher_output: TeacherOutput,
    student_features: StudentFeatures,
    teacher_features: TeacherFeatures,
    fidelity_batch: TeacherLossBatch,
    fidelity_weights: LossWeights,
    distillation_weights: DistillationWeights = DistillationWeights(),
) -> tuple[torch.Tensor, Dict[str, torch.Tensor]]:
    distillation_weights.validate()
    base_total, base_terms = compute_teacher_loss(student_output, fidelity_batch, fidelity_weights)

    teacher_clean = _charbonnier(student_output.clean_raw, teacher_output.clean_raw.detach())
    teacher_residual = F.l1_loss(
        student_output.sigma_normalized_residual,
        teacher_output.sigma_normalized_residual.detach(),
    )
    teacher_posterior = F.l1_loss(
        student_output.posterior_log_variance_ratio,
        teacher_output.posterior_log_variance_ratio.detach(),
    )
    if student_output.confidence is not None and teacher_output.confidence is not None:
        teacher_confidence = F.l1_loss(student_output.confidence, teacher_output.confidence.detach())
    else:
        teacher_confidence = base_total.new_zeros(())
    feature_energy = feature_distillation_loss(student_features, teacher_features)

    total = (
        base_total
        + distillation_weights.teacher_clean * teacher_clean
        + distillation_weights.teacher_residual * teacher_residual
        + distillation_weights.teacher_posterior * teacher_posterior
        + distillation_weights.teacher_confidence * teacher_confidence
        + distillation_weights.feature_energy * feature_energy
    )
    terms = dict(base_terms)
    terms.update(
        distill_teacher_clean=teacher_clean,
        distill_teacher_residual=teacher_residual,
        distill_teacher_posterior=teacher_posterior,
        distill_teacher_confidence=teacher_confidence,
        distill_feature_energy=feature_energy,
        distill_total=total,
    )
    return total, terms


def freeze_teacher(model: torch.nn.Module) -> None:
    model.eval()
    for parameter in model.parameters():
        parameter.requires_grad_(False)
