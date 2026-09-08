from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional

import torch
import torch.nn.functional as F

from .model_teacher import TeacherOutput


@dataclass(frozen=True)
class LossWeights:
    # Starter weights only. They are logged and must be ablated; none is a
    # hidden production constant.
    raw: float = 1.0
    gradient: float = 0.20
    multiscale: float = 0.10
    frequency: float = 0.00
    identity: float = 0.50
    physics: float = 0.05
    channel_bias: float = 0.10
    uncertainty: float = 0.10
    isp_proxy: float = 0.01

    def validate(self) -> None:
        for name, value in self.__dict__.items():
            if value < 0.0:
                raise ValueError(f"loss weight {name} must be non-negative")


@dataclass
class TeacherLossBatch:
    clean: torch.Tensor
    noisy: torch.Tensor
    input_variance: torch.Tensor
    remaining_lsc: torch.Tensor
    identity_mask: Optional[torch.Tensor] = None


def _charbonnier(error: torch.Tensor, epsilon: float = 1.0e-3) -> torch.Tensor:
    return torch.sqrt(error.square() + epsilon * epsilon)


def _lsc_weight(lsc: torch.Tensor) -> torch.Tensor:
    # Downstream remaining LSC amplifies corner errors. Normalize to keep the
    # batch loss scale stable while still giving amplified areas more weight.
    w = lsc.clamp(0.25, 8.0)
    return w / w.mean(dim=(1, 2, 3), keepdim=True).clamp_min(1.0e-6)


def raw_fidelity_loss(pred: torch.Tensor, gt: torch.Tensor, lsc: torch.Tensor) -> torch.Tensor:
    return (_charbonnier(pred - gt) * _lsc_weight(lsc)).mean()


def gradient_loss(pred: torch.Tensor, gt: torch.Tensor, lsc: torch.Tensor) -> torch.Tensor:
    w = _lsc_weight(lsc)
    px, gx = pred[..., :, 1:] - pred[..., :, :-1], gt[..., :, 1:] - gt[..., :, :-1]
    py, gy = pred[..., 1:, :] - pred[..., :-1, :], gt[..., 1:, :] - gt[..., :-1, :]
    return (_charbonnier(px - gx) * w[..., :, 1:]).mean() + (_charbonnier(py - gy) * w[..., 1:, :]).mean()


def multiscale_loss(pred: torch.Tensor, gt: torch.Tensor, levels: int = 3) -> torch.Tensor:
    total = pred.new_zeros(())
    p, g = pred, gt
    for level in range(levels):
        total = total + _charbonnier(p - g).mean() / (2**level)
        if min(p.shape[-2:]) < 4:
            break
        p = F.avg_pool2d(p, 2, 2)
        g = F.avg_pool2d(g, 2, 2)
    return total


def frequency_loss(pred: torch.Tensor, gt: torch.Tensor) -> torch.Tensor:
    pf = torch.fft.rfft2(pred.float(), norm="ortho")
    gf = torch.fft.rfft2(gt.float(), norm="ortho")
    # Low-weight auxiliary only: compare complex spectra without discarding phase.
    return torch.mean(torch.abs(pf - gf))


def identity_loss(output: TeacherOutput, identity_mask: Optional[torch.Tensor]) -> torch.Tensor:
    if identity_mask is None:
        return output.residual.new_zeros(())
    mask = identity_mask.to(device=output.residual.device, dtype=torch.bool).view(-1)
    if mask.numel() != output.residual.shape[0]:
        raise ValueError("identity_mask must have one value per batch item")
    if not torch.any(mask):
        return output.residual.new_zeros(())
    return torch.mean(torch.abs(output.residual[mask]))


def physics_residual_loss(output: TeacherOutput, soft_sigma: float = 3.0) -> torch.Tensor:
    # Hard K bound is in the model. This softer loss discourages living near it.
    excess = F.relu(torch.abs(output.sigma_normalized_residual) - soft_sigma)
    return excess.square().mean()


def channel_bias_loss(pred: torch.Tensor, gt: torch.Tensor) -> torch.Tensor:
    bias = (pred - gt).mean(dim=(0, 2, 3))
    return torch.mean(torch.abs(bias))


def uncertainty_nll(pred: torch.Tensor, gt: torch.Tensor, posterior_variance: torch.Tensor) -> torch.Tensor:
    var = posterior_variance.clamp_min(1.0e-12)
    e2 = (pred - gt).square()
    return torch.mean(e2 / (2.0 * var) + 0.5 * torch.log(var))


def fixed_raw_to_rgb_proxy(raw: torch.Tensor) -> torch.Tensor:
    # Fixed, non-learned linear proxy only. It exists to expose small RAW
    # channel biases downstream; it is not a color/tone/style target.
    return torch.stack((raw[:, 0], 0.5 * (raw[:, 1] + raw[:, 2]), raw[:, 3]), dim=1)


def isp_proxy_loss(pred: torch.Tensor, gt: torch.Tensor) -> torch.Tensor:
    return _charbonnier(fixed_raw_to_rgb_proxy(pred) - fixed_raw_to_rgb_proxy(gt)).mean()


def compute_teacher_loss(
    output: TeacherOutput,
    batch: TeacherLossBatch,
    weights: LossWeights = LossWeights(),
) -> tuple[torch.Tensor, Dict[str, torch.Tensor]]:
    weights.validate()
    if output.clean_raw.shape != batch.clean.shape or batch.clean.shape != batch.noisy.shape:
        raise ValueError("RAW shapes do not match")
    if batch.input_variance.shape != batch.clean.shape or batch.remaining_lsc.shape != batch.clean.shape:
        raise ValueError("variance/LSC shapes do not match RAW")
    terms: Dict[str, torch.Tensor] = {
        "raw": raw_fidelity_loss(output.clean_raw, batch.clean, batch.remaining_lsc),
        "gradient": gradient_loss(output.clean_raw, batch.clean, batch.remaining_lsc),
        "multiscale": multiscale_loss(output.clean_raw, batch.clean),
        "frequency": frequency_loss(output.clean_raw, batch.clean) if weights.frequency else output.clean_raw.new_zeros(()),
        "identity": identity_loss(output, batch.identity_mask),
        "physics": physics_residual_loss(output),
        "channel_bias": channel_bias_loss(output.clean_raw, batch.clean),
        "uncertainty": uncertainty_nll(output.clean_raw, batch.clean, output.posterior_variance),
        "isp_proxy": isp_proxy_loss(output.clean_raw, batch.clean) if weights.isp_proxy else output.clean_raw.new_zeros(()),
    }
    total = sum(getattr(weights, name) * value for name, value in terms.items())
    terms["total"] = total
    return total, terms
