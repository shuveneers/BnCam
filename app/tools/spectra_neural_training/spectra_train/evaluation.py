from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable, Optional, Sequence

import torch
import torch.nn.functional as F

from .model_teacher import TeacherOutput


def _percentile(x: torch.Tensor, q: float) -> float:
    return float(torch.quantile(x.float().reshape(-1), q).item())


def raw_fidelity_metrics(pred: torch.Tensor, gt: torch.Tensor) -> Dict[str, float]:
    e = pred - gt
    mse = torch.mean(e.square()).clamp_min(1.0e-12)
    out = {
        "mae": float(torch.mean(torch.abs(e)).item()),
        "psnr": float((-10.0 * torch.log10(mse)).item()),
    }
    bias = e.mean(dim=(0, 2, 3))
    for i, name in enumerate(("R", "G1", "G2", "B")):
        out[f"mean_bias_{name}"] = float(bias[i].item())
    return out


def detail_metrics(pred: torch.Tensor, gt: torch.Tensor) -> Dict[str, float]:
    def gradients(x: torch.Tensor):
        return x[..., :, 1:] - x[..., :, :-1], x[..., 1:, :] - x[..., :-1, :]
    px, py = gradients(pred)
    gx, gy = gradients(gt)
    grad_error = 0.5 * (torch.mean(torch.abs(px - gx)) + torch.mean(torch.abs(py - gy)))
    # Fixed high-pass energy; useful for texture-retention comparisons, not a training target by itself.
    blur_p = F.avg_pool2d(F.pad(pred, (1, 1, 1, 1), mode="reflect"), 3, 1)
    blur_g = F.avg_pool2d(F.pad(gt, (1, 1, 1, 1), mode="reflect"), 3, 1)
    hp_p = torch.mean((pred - blur_p).square())
    hp_g = torch.mean((gt - blur_g).square()).clamp_min(1.0e-12)
    return {
        "gradient_error": float(grad_error.item()),
        "high_frequency_energy_ratio": float((hp_p / hp_g).item()),
    }


def noise_metrics(pred: torch.Tensor, gt: torch.Tensor, noisy: torch.Tensor) -> Dict[str, float]:
    residual = pred - gt
    input_noise = noisy - gt
    var_in = torch.var(input_noise).clamp_min(1.0e-12)
    var_out = torch.var(residual)
    # Low-frequency residual via fixed 16x16 average; reports blotch/banding energy.
    kernel = min(16, pred.shape[-2], pred.shape[-1])
    if kernel >= 2:
        low = F.avg_pool2d(residual, kernel, kernel)
        low_energy = torch.mean(low.square())
    else:
        low_energy = torch.mean(residual.square())
    return {
        "residual_variance": float(var_out.item()),
        "residual_to_input_variance_ratio": float((var_out / var_in).item()),
        "low_frequency_residual_energy": float(low_energy.item()),
    }


def posterior_metrics(pred: torch.Tensor, gt: torch.Tensor, posterior_variance: torch.Tensor) -> Dict[str, float]:
    var = posterior_variance.clamp_min(1.0e-12)
    normalized_abs = torch.abs(pred - gt) / torch.sqrt(var)
    return {
        "posterior_normalized_abs_mean": float(normalized_abs.mean().item()),
        "posterior_fraction_within_1sigma": float((normalized_abs <= 1.0).float().mean().item()),
        "posterior_fraction_within_2sigma": float((normalized_abs <= 2.0).float().mean().item()),
        "posterior_variance_p50": _percentile(var, 0.50),
        "posterior_variance_p90": _percentile(var, 0.90),
    }


def safety_metrics(
    output: TeacherOutput,
    conditioning: torch.Tensor,
    k_sigma: float,
) -> Dict[str, float]:
    z = torch.abs(output.sigma_normalized_residual)
    headroom = conditioning[:, 13:14]
    clipped = headroom <= 0.0
    touched = (torch.abs(output.residual) > 0.0).any(dim=1, keepdim=True)
    clipped_touched = (touched & clipped).float().sum() / clipped.float().sum().clamp_min(1.0)
    return {
        "correction_sigma_p95": _percentile(z, 0.95),
        "correction_sigma_p99": _percentile(z, 0.99),
        "fraction_gt_1sigma": float((z > 1.0).float().mean().item()),
        "fraction_gt_2sigma": float((z > 2.0).float().mean().item()),
        "fraction_gt_3sigma": float((z > 3.0).float().mean().item()),
        "fraction_at_k_bound": float((z >= k_sigma * 0.999).float().mean().item()),
        "clipped_cells_touched_fraction": float(clipped_touched.item()),
    }


def new_frequency_energy(pred: torch.Tensor, reference: torch.Tensor, support_floor: float = 1.0e-5) -> float:
    pf = torch.fft.rfft2(pred.float(), norm="ortho")
    rf = torch.fft.rfft2(reference.float(), norm="ortho")
    support = torch.abs(rf) > support_floor
    outside = torch.where(support, torch.zeros_like(pf), pf)
    return float(torch.mean(torch.abs(outside).square()).item())


def repeatability_error(a: torch.Tensor, b: torch.Tensor) -> float:
    if a.shape != b.shape:
        raise ValueError("repeatability tensors must have the same shape")
    return float(torch.max(torch.abs(a - b)).item())


def tile_seam_metric(image: torch.Tensor, seam_x: Sequence[int] = (), seam_y: Sequence[int] = ()) -> float:
    values = []
    for x in seam_x:
        if 0 < x < image.shape[-1]:
            values.append(torch.mean(torch.abs(image[..., x] - image[..., x - 1])))
    for y in seam_y:
        if 0 < y < image.shape[-2]:
            values.append(torch.mean(torch.abs(image[..., y, :] - image[..., y - 1, :])))
    return 0.0 if not values else float(torch.stack(values).max().item())


def evaluate_prediction(
    output: TeacherOutput,
    conditioning: torch.Tensor,
    noisy: torch.Tensor,
    gt: torch.Tensor,
    k_sigma: float,
) -> Dict[str, float]:
    metrics: Dict[str, float] = {}
    metrics.update(raw_fidelity_metrics(output.clean_raw, gt))
    metrics.update(detail_metrics(output.clean_raw, gt))
    metrics.update(noise_metrics(output.clean_raw, gt, noisy))
    metrics.update(posterior_metrics(output.clean_raw, gt, output.posterior_variance))
    metrics.update(safety_metrics(output, conditioning, k_sigma))
    metrics["new_frequency_energy_vs_gt"] = new_frequency_energy(output.clean_raw, gt)
    return metrics
