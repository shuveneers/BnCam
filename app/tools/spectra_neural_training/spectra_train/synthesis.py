from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable, Optional, Sequence, Tuple

import numpy as np
import torch
import torch.nn.functional as F

from .contracts import SPATIAL_CONDITIONING_CHANNELS, PhysicsMetadata
from .dataset import LoadedRawRecord, NoiseRegimeProbabilities, RawManifestDataset


@dataclass(frozen=True)
class NoiseSynthesisConfig:
    regimes: NoiseRegimeProbabilities = NoiseRegimeProbabilities()
    identity_so_scale: Tuple[float, float] = (0.0, 0.10)
    moderate_so_scale: Tuple[float, float] = (0.65, 1.75)
    heavy_so_scale: Tuple[float, float] = (1.50, 4.00)
    distribution_randomization: Tuple[float, float] = (0.50, 2.00)
    heavy_tail_probability: float = 0.01
    heavy_tail_sigma_multiplier: float = 5.0
    prnu_sigma_fraction: float = 0.015
    dsnu_sigma_fraction: float = 0.35
    row_banding_sigma_fraction: float = 0.35
    column_banding_sigma_fraction: float = 0.35
    black_residual_sigma_fraction: float = 0.25
    hot_pixel_probability: float = 2.0e-5
    hot_pixel_sigma_multiplier: float = 8.0
    lsc_max_gain_range: Tuple[float, float] = (1.0, 3.0)
    lsc_channel_imbalance: float = 0.08
    poisson_exact_min_s: float = 1.0e-7
    log_sigma_floor: float = 1.0e-8
    headroom_span: float = 0.08

    def validate(self) -> None:
        self.regimes.validate()
        for name in ("identity_so_scale", "moderate_so_scale", "heavy_so_scale", "distribution_randomization", "lsc_max_gain_range"):
            lo, hi = getattr(self, name)
            if lo < 0.0 or hi < lo or not np.isfinite([lo, hi]).all():
                raise ValueError(f"invalid range: {name}")
        for name in (
            "heavy_tail_probability", "heavy_tail_sigma_multiplier", "prnu_sigma_fraction",
            "dsnu_sigma_fraction", "row_banding_sigma_fraction", "column_banding_sigma_fraction",
            "black_residual_sigma_fraction", "hot_pixel_probability", "hot_pixel_sigma_multiplier",
            "lsc_channel_imbalance", "poisson_exact_min_s", "log_sigma_floor", "headroom_span",
        ):
            v = float(getattr(self, name))
            if not np.isfinite(v) or v < 0.0:
                raise ValueError(f"{name} must be finite and non-negative")
        if self.heavy_tail_probability > 1.0 or self.hot_pixel_probability > 1.0:
            raise ValueError("probabilities must be <= 1")
        if self.headroom_span <= 0.0 or self.headroom_span > 1.0:
            raise ValueError("headroom_span must be in (0,1]")


@dataclass
class DarkFrameBank:
    residuals: Sequence[torch.Tensor]

    @classmethod
    def from_dataset(cls, dataset: RawManifestDataset) -> "DarkFrameBank":
        residuals = []
        for i in range(len(dataset)):
            item = dataset[i]
            if item.dark_raw is None:
                continue
            raw = np.asarray(item.dark_raw, dtype=np.float32)
            denom = (item.record.physics.white_level_raw - item.record.physics.black_level_raw).reshape(4, 1, 1)
            centered = raw - np.median(raw, axis=(1, 2), keepdims=True)
            residual = centered / denom
            residuals.append(torch.from_numpy(residual.astype(np.float32)))
        if not residuals:
            raise ValueError("dark-frame dataset contains no dark records")
        return cls(tuple(residuals))

    def sample_crop(self, shape: Tuple[int, int], generator: torch.Generator, device: torch.device) -> torch.Tensor:
        h, w = shape
        idx = int(torch.randint(len(self.residuals), (1,), generator=generator).item())
        src = self.residuals[idx]
        if src.ndim != 3 or src.shape[0] != 4 or src.shape[1] < h or src.shape[2] < w:
            raise ValueError("dark frame is smaller than requested crop")
        max_y = src.shape[1] - h
        max_x = src.shape[2] - w
        y = int(torch.randint(max_y + 1, (1,), generator=generator).item()) if max_y else 0
        x = int(torch.randint(max_x + 1, (1,), generator=generator).item()) if max_x else 0
        crop = src[:, y:y + h, x:x + w].to(device=device, dtype=torch.float32)
        # Recenter each crop to avoid leaking a new black-level authority.
        return crop - crop.mean(dim=(1, 2), keepdim=True)


def _rand_uniform(lo: float, hi: float, generator: torch.Generator, device: torch.device) -> torch.Tensor:
    if hi == lo:
        return torch.tensor(lo, device=device)
    return lo + (hi - lo) * torch.rand((), generator=generator, device=device)


def _smooth_random_field(shape: Tuple[int, int], channels: int, generator: torch.Generator, device: torch.device) -> torch.Tensor:
    h, w = shape
    low_h, low_w = max(2, (h + 15) // 16), max(2, (w + 15) // 16)
    low = torch.randn((1, channels, low_h, low_w), generator=generator, device=device)
    field = F.interpolate(low, size=(h, w), mode="bilinear", align_corners=False)[0]
    field = field - field.mean(dim=(1, 2), keepdim=True)
    return field / field.std(dim=(1, 2), keepdim=True).clamp_min(1.0e-6)


def synthesize_remaining_lsc(
    shape: Tuple[int, int],
    config: NoiseSynthesisConfig,
    generator: torch.Generator,
    device: torch.device,
) -> torch.Tensor:
    h, w = shape
    yy = torch.linspace(-1.0, 1.0, h, device=device).view(1, h, 1)
    xx = torch.linspace(-1.0, 1.0, w, device=device).view(1, 1, w)
    radius2 = (xx.square() + yy.square()).clamp(0.0, 2.0) / 2.0
    max_gain = _rand_uniform(*config.lsc_max_gain_range, generator, device)
    base = 1.0 + (max_gain - 1.0) * radius2
    channel_scale = 1.0 + config.lsc_channel_imbalance * torch.randn((4, 1, 1), generator=generator, device=device)
    return (1.0 + (base - 1.0) * channel_scale).clamp_min(1.0)


def _regime_scale(regime: str, config: NoiseSynthesisConfig, generator: torch.Generator, device: torch.device) -> torch.Tensor:
    ranges = {
        "identity": config.identity_so_scale,
        "moderate": config.moderate_so_scale,
        "heavy": config.heavy_so_scale,
    }
    if regime not in ranges:
        raise ValueError(regime)
    return _rand_uniform(*ranges[regime], generator, device)


def randomized_so(
    meta: PhysicsMetadata,
    regime: str,
    config: NoiseSynthesisConfig,
    generator: torch.Generator,
    device: torch.device,
) -> Tuple[torch.Tensor, torch.Tensor]:
    scale = _regime_scale(regime, config, generator, device)
    randomize = _rand_uniform(*config.distribution_randomization, generator, device)
    s = torch.as_tensor(meta.shot_s, dtype=torch.float32, device=device).view(4, 1, 1) * scale * randomize
    o = torch.as_tensor(meta.read_o, dtype=torch.float32, device=device).view(4, 1, 1) * scale.square() * randomize
    return s.clamp_min(0.0), o.clamp_min(0.0)


def poisson_gaussian_noise(
    clean: torch.Tensor,
    shot_s: torch.Tensor,
    read_o: torch.Tensor,
    config: NoiseSynthesisConfig,
    generator: torch.Generator,
) -> torch.Tensor:
    x = clean.clamp(0.0, 1.0)
    s = shot_s.expand_as(x)
    o = read_o.expand_as(x)
    exact = s >= config.poisson_exact_min_s
    safe_s = s.clamp_min(config.poisson_exact_min_s)
    counts = torch.poisson((x / safe_s).clamp_min(0.0), generator=generator)
    shot_exact = safe_s * counts - x
    shot_approx = torch.randn(x.shape, generator=generator, device=x.device, dtype=x.dtype) * torch.sqrt((s * x).clamp_min(0.0))
    shot = torch.where(exact, shot_exact, shot_approx)
    read = torch.randn(x.shape, generator=generator, device=x.device, dtype=x.dtype) * torch.sqrt(o)
    return shot + read


def add_structured_sensor_noise(
    clean: torch.Tensor,
    base_noise: torch.Tensor,
    read_o: torch.Tensor,
    config: NoiseSynthesisConfig,
    generator: torch.Generator,
) -> torch.Tensor:
    device = clean.device
    _, h, w = clean.shape
    read_sigma = torch.sqrt(read_o).expand(4, h, w)
    out = base_noise

    prnu = _smooth_random_field((h, w), 4, generator, device) * config.prnu_sigma_fraction
    out = out + clean * prnu

    dsnu = _smooth_random_field((h, w), 4, generator, device) * read_sigma * config.dsnu_sigma_fraction
    out = out + dsnu

    row = torch.randn((4, h, 1), generator=generator, device=device) * read_sigma[:, :, :1] * config.row_banding_sigma_fraction
    col = torch.randn((4, 1, w), generator=generator, device=device) * read_sigma[:, :1, :] * config.column_banding_sigma_fraction
    out = out + row + col

    black = torch.randn((4, 1, 1), generator=generator, device=device) * torch.sqrt(read_o) * config.black_residual_sigma_fraction
    out = out + black

    if config.heavy_tail_probability > 0.0:
        mask = torch.rand(clean.shape, generator=generator, device=device) < config.heavy_tail_probability
        tail = torch.randn(clean.shape, generator=generator, device=device) * read_sigma * config.heavy_tail_sigma_multiplier
        out = out + mask * tail

    if config.hot_pixel_probability > 0.0:
        mask = torch.rand(clean.shape, generator=generator, device=device) < config.hot_pixel_probability
        hot = torch.abs(torch.randn(clean.shape, generator=generator, device=device)) * read_sigma * config.hot_pixel_sigma_multiplier
        out = out + mask * hot
    return out


def quantize_normalized(
    raw: torch.Tensor,
    bit_depth: int,
    code_span: Optional[torch.Tensor | np.ndarray | Sequence[float]] = None,
) -> torch.Tensor:
    if bit_depth <= 0 or bit_depth > 32:
        raise ValueError("bit_depth must be in 1..32")
    nominal_levels = float((1 << bit_depth) - 1) if bit_depth < 31 else float(2**bit_depth - 1)
    if code_span is None:
        levels = torch.as_tensor(nominal_levels, dtype=raw.dtype, device=raw.device)
    else:
        levels = torch.as_tensor(code_span, dtype=raw.dtype, device=raw.device)
        if levels.ndim == 1:
            if raw.ndim < 3 or levels.numel() != raw.shape[-3]:
                raise ValueError("per-channel code_span must match RAW channel count")
            levels = levels.view(-1, 1, 1)
        if not torch.isfinite(levels).all() or torch.any(levels <= 0.0) or torch.any(levels > nominal_levels):
            raise ValueError("code_span must be finite, positive, and within nominal bit-depth range")
    return torch.round(raw.clamp(0.0, 1.0) * levels) / levels


def conservative_spatial_trust(meta: PhysicsMetadata, remaining_lsc_active: bool) -> float:
    trusts = [meta.metadata_trust, meta.noise_model_trust, meta.black_level_trust]
    if remaining_lsc_active:
        trusts.append(meta.remaining_lsc_trust)
    return float(np.clip(min(trusts), 0.0, 1.0))


def build_spatial_conditioning(
    noisy: torch.Tensor,
    shot_s: torch.Tensor,
    read_o: torch.Tensor,
    remaining_lsc: torch.Tensor,
    metadata_trust: float,
    config: NoiseSynthesisConfig,
) -> Tuple[torch.Tensor, torch.Tensor]:
    if noisy.ndim != 3 or noisy.shape[0] != 4:
        raise ValueError("noisy RAW must be 4xHxW")
    if remaining_lsc.shape != noisy.shape:
        raise ValueError("remaining_lsc must match RAW shape")
    variance = (shot_s * noisy.clamp(0.0, 1.0) + read_o).clamp_min(0.0)
    sigma = torch.sqrt(variance)
    log_sigma = torch.log(sigma.clamp_min(config.log_sigma_floor))
    min_headroom = ((1.0 - noisy) / config.headroom_span).clamp(0.0, 1.0).amin(dim=0, keepdim=True)
    trust = torch.full_like(min_headroom, float(np.clip(metadata_trust, 0.0, 1.0)))
    cond = torch.cat((noisy, log_sigma, remaining_lsc, trust, min_headroom), dim=0)
    if cond.shape[0] != len(SPATIAL_CONDITIONING_CHANNELS):
        raise AssertionError("conditioning channel count drift")
    return cond, variance


@dataclass
class PhysicsTrainingSample:
    noisy: torch.Tensor
    clean: torch.Tensor
    conditioning: torch.Tensor
    input_variance: torch.Tensor
    remaining_lsc: torch.Tensor
    global_condition: torch.Tensor
    identity_target: bool
    regime: str
    sample_id: str


class PhysicsConditionedSampleBuilder:
    def __init__(
        self,
        config: NoiseSynthesisConfig,
        dark_bank: Optional[DarkFrameBank] = None,
        seed: int = 0,
        device: str | torch.device = "cpu",
    ):
        config.validate()
        self.config = config
        self.dark_bank = dark_bank
        self.device = torch.device(device)
        self.generator = torch.Generator(device=self.device.type if self.device.type != "mps" else "cpu")
        self.generator.manual_seed(seed)
        self.numpy_rng = np.random.default_rng(seed)

    def _real_paired(self, item: LoadedRawRecord) -> PhysicsTrainingSample:
        clean = torch.from_numpy(item.clean).to(self.device)  # type: ignore[arg-type]
        noisy = torch.from_numpy(item.noisy).to(self.device)  # type: ignore[arg-type]
        lsc = torch.ones_like(clean) if item.remaining_lsc is None else torch.from_numpy(item.remaining_lsc).to(self.device)
        s = torch.as_tensor(item.record.physics.shot_s, dtype=torch.float32, device=self.device).view(4, 1, 1)
        o = torch.as_tensor(item.record.physics.read_o, dtype=torch.float32, device=self.device).view(4, 1, 1)
        trust = conservative_spatial_trust(item.record.physics, item.remaining_lsc is not None)
        cond, variance = build_spatial_conditioning(noisy, s, o, lsc, trust, self.config)
        return PhysicsTrainingSample(
            noisy=noisy, clean=clean, conditioning=cond, input_variance=variance,
            remaining_lsc=lsc, global_condition=torch.from_numpy(item.global_condition).to(self.device),
            identity_target=False, regime="real_paired", sample_id=item.sample_id,
        )

    def build(self, item: LoadedRawRecord, regime: Optional[str] = None) -> PhysicsTrainingSample:
        if item.clean is None:
            raise ValueError("training sample builder requires clean or paired scene record")
        if item.noisy is not None:
            return self._real_paired(item)

        regime = regime or self.config.regimes.sample(self.numpy_rng)
        clean_scene = torch.from_numpy(item.clean).to(self.device, dtype=torch.float32)
        if item.remaining_lsc is None:
            if item.record.physics.lens_shading_already_applied:
                lsc = torch.ones_like(clean_scene)
                clean = clean_scene
                remaining_lsc_active = False
            else:
                lsc = synthesize_remaining_lsc(clean_scene.shape[1:], self.config, self.generator, self.device)
                clean = (clean_scene / lsc).clamp(0.0, 1.0)
                remaining_lsc_active = bool(torch.any(torch.abs(lsc - 1.0) > 1.0e-7).item())
        else:
            lsc = torch.from_numpy(item.remaining_lsc).to(self.device, dtype=torch.float32)
            clean = clean_scene
            remaining_lsc_active = True

        s, o = randomized_so(item.record.physics, regime, self.config, self.generator, self.device)
        noise = poisson_gaussian_noise(clean, s, o, self.config, self.generator)
        noise = add_structured_sensor_noise(clean, noise, o, self.config, self.generator)
        if self.dark_bank is not None and regime != "identity":
            noise = noise + self.dark_bank.sample_crop(clean.shape[1:], self.generator, self.device)

        code_span = np.asarray(item.record.physics.white_level_raw, dtype=np.float32) - np.asarray(item.record.physics.black_level_raw, dtype=np.float32)
        noisy = quantize_normalized(clean + noise, item.record.physics.bit_depth, code_span).clamp(0.0, 1.0)
        trust = conservative_spatial_trust(item.record.physics, remaining_lsc_active)
        cond, variance = build_spatial_conditioning(noisy, s, o, lsc, trust, self.config)
        return PhysicsTrainingSample(
            noisy=noisy,
            clean=clean,
            conditioning=cond,
            input_variance=variance,
            remaining_lsc=lsc,
            global_condition=torch.from_numpy(item.global_condition).to(self.device),
            identity_target=(regime == "identity"),
            regime=regime,
            sample_id=item.sample_id,
        )
