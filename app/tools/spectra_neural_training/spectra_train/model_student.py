from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Dict, Mapping, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .contracts import GLOBAL_CONDITIONING_FIELDS, SPATIAL_CONDITIONING_CHANNELS


@dataclass(frozen=True)
class StudentConfig:
    """Hardware-oriented Student graph contract.

    The default graph intentionally uses only 3x3/1x1 convolutions, add,
    multiply/SimpleGate, nearest-neighbour resize and stage-wise FiLM.  It has
    no LayerNorm, transposed convolution or full-resolution attention.
    """

    name: str = "spectra_student_hq_v1"
    variant: str = "hq"
    input_channels: int = len(SPATIAL_CONDITIONING_CHANNELS)
    global_condition_dim: int = len(GLOBAL_CONDITIONING_FIELDS)
    widths: Tuple[int, int, int, int] = (48, 72, 104, 144)
    encoder_blocks: Tuple[int, int, int] = (3, 4, 5)
    bottleneck_blocks: int = 6
    decoder_blocks: Tuple[int, int, int] = (5, 4, 3)
    residual_k_sigma: float = 4.0
    posterior_log_ratio_min: float = -6.0
    posterior_log_ratio_max: float = 3.0
    confidence_head: bool = True
    global_lowres_context: bool = False
    fine_coarse_residual_head: bool = False

    def validate(self) -> None:
        if self.variant not in {"hq", "lite", "research"}:
            raise ValueError("student variant must be hq/lite/research")
        if self.input_channels != len(SPATIAL_CONDITIONING_CHANNELS):
            raise ValueError("student input_channels must match the 14-channel conditioning contract")
        if self.global_condition_dim != len(GLOBAL_CONDITIONING_FIELDS):
            raise ValueError("student global_condition_dim contract drift")
        if len(self.widths) != 4 or len(self.encoder_blocks) != 3 or len(self.decoder_blocks) != 3:
            raise ValueError("student stage tuple lengths are fixed for v1")
        if any(v <= 0 for v in (*self.widths, *self.encoder_blocks, self.bottleneck_blocks, *self.decoder_blocks)):
            raise ValueError("student widths/depths must be positive")
        if any(v % 2 for v in self.widths):
            raise ValueError("student widths must be even for SimpleGate")
        if self.residual_k_sigma <= 0.0:
            raise ValueError("residual_k_sigma must be > 0")
        if self.posterior_log_ratio_max <= self.posterior_log_ratio_min:
            raise ValueError("invalid posterior clamp")

    @classmethod
    def lite(cls) -> "StudentConfig":
        return cls(
            name="spectra_student_lite_v1",
            variant="lite",
            widths=(36, 52, 76, 104),
            encoder_blocks=(2, 3, 4),
            bottleneck_blocks=4,
            decoder_blocks=(4, 3, 2),
        )

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


class SimpleGate(nn.Module):
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        a, b = x.chunk(2, dim=1)
        return a * b


class StudentFiLM(nn.Module):
    """Compact feature-wise modulation from global physics context."""

    def __init__(self, condition_dim: int, channels: int):
        super().__init__()
        self.proj = nn.Linear(condition_dim, channels * 2)
        nn.init.zeros_(self.proj.weight)
        nn.init.zeros_(self.proj.bias)

    def forward(self, x: torch.Tensor, condition: torch.Tensor) -> torch.Tensor:
        gamma, beta = self.proj(condition).chunk(2, dim=1)
        return x * (1.0 + gamma[:, :, None, None]) + beta[:, :, None, None]


class MobileNAFBlock(nn.Module):
    """Normalization-free mobile residual block using the planned primitive set."""

    def __init__(self, channels: int):
        super().__init__()
        self.mix_in = nn.Conv2d(channels, channels * 2, 1)
        self.gate1 = SimpleGate()
        self.spatial = nn.Conv2d(channels, channels, 3, padding=1)
        self.mix_out = nn.Conv2d(channels, channels, 1)
        self.beta = nn.Parameter(torch.zeros(1, channels, 1, 1))

        self.ffn_in = nn.Conv2d(channels, channels * 2, 1)
        self.gate2 = SimpleGate()
        self.ffn_out = nn.Conv2d(channels, channels, 1)
        self.gamma = nn.Parameter(torch.zeros(1, channels, 1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = self.gate1(self.mix_in(x))
        y = self.mix_out(self.spatial(y))
        x = x + self.beta * y
        y = self.ffn_out(self.gate2(self.ffn_in(x)))
        return x + self.gamma * y


class StudentStage(nn.Module):
    def __init__(self, channels: int, depth: int, condition_dim: int):
        super().__init__()
        self.film = StudentFiLM(condition_dim, channels)
        self.blocks = nn.Sequential(*(MobileNAFBlock(channels) for _ in range(depth)))

    def forward(self, x: torch.Tensor, condition: torch.Tensor) -> torch.Tensor:
        return self.blocks(self.film(x, condition))


class GlobalLowResolutionContext(nn.Module):
    """Research-only whole-frame low-resolution context encoder.

    Callers must provide a separate global spatial conditioning tensor.  The
    baseline HQ/Lite export does not enable this branch until held-out sensor
    ablations demonstrate a quality/runtime win.
    """

    def __init__(self, input_channels: int, output_channels: int):
        super().__init__()
        mid = max(24, output_channels // 2)
        self.net = nn.Sequential(
            nn.Conv2d(input_channels, mid, 3, stride=2, padding=1),
            nn.SiLU(),
            nn.Conv2d(mid, mid, 3, stride=2, padding=1),
            nn.SiLU(),
            nn.Conv2d(mid, output_channels, 3, stride=2, padding=1),
        )

    def forward(self, x: torch.Tensor, target_hw: Tuple[int, int]) -> torch.Tensor:
        y = self.net(x)
        return F.interpolate(y, size=target_hw, mode="nearest")


@dataclass
class StudentOutput:
    clean_raw: torch.Tensor
    residual: torch.Tensor
    sigma_normalized_residual: torch.Tensor
    posterior_variance: torch.Tensor
    posterior_log_variance_ratio: torch.Tensor
    confidence: Optional[torch.Tensor]
    fine_sigma_residual: Optional[torch.Tensor] = None
    coarse_sigma_residual: Optional[torch.Tensor] = None


@dataclass
class StudentFeatures:
    encoder0: torch.Tensor
    encoder1: torch.Tensor
    encoder2: torch.Tensor
    bottleneck: torch.Tensor
    decoder0: torch.Tensor


class PhysicsConditionedStudent(nn.Module):
    def __init__(self, config: StudentConfig = StudentConfig()):
        super().__init__()
        config.validate()
        self.config = config
        w0, w1, w2, w3 = config.widths
        self.stem = nn.Conv2d(config.input_channels, w0, 3, padding=1)
        self.enc0 = StudentStage(w0, config.encoder_blocks[0], config.global_condition_dim)
        self.down0 = nn.Conv2d(w0, w1, 3, stride=2, padding=1)
        self.enc1 = StudentStage(w1, config.encoder_blocks[1], config.global_condition_dim)
        self.down1 = nn.Conv2d(w1, w2, 3, stride=2, padding=1)
        self.enc2 = StudentStage(w2, config.encoder_blocks[2], config.global_condition_dim)
        self.down2 = nn.Conv2d(w2, w3, 3, stride=2, padding=1)
        self.bottleneck = StudentStage(w3, config.bottleneck_blocks, config.global_condition_dim)
        self.global_context = (
            GlobalLowResolutionContext(config.input_channels, w3)
            if config.global_lowres_context else None
        )

        self.up2 = nn.Conv2d(w3, w2, 3, padding=1)
        self.dec2 = StudentStage(w2, config.decoder_blocks[0], config.global_condition_dim)
        self.up1 = nn.Conv2d(w2, w1, 3, padding=1)
        self.dec1 = StudentStage(w1, config.decoder_blocks[1], config.global_condition_dim)
        self.up0 = nn.Conv2d(w1, w0, 3, padding=1)
        self.dec0 = StudentStage(w0, config.decoder_blocks[2], config.global_condition_dim)

        self.residual_head = nn.Conv2d(w0, 4, 3, padding=1)
        self.coarse_residual_head = nn.Conv2d(w0, 4, 3, padding=1) if config.fine_coarse_residual_head else None
        self.posterior_head = nn.Conv2d(w0, 4, 3, padding=1)
        self.confidence_head = nn.Conv2d(w0, 1, 3, padding=1) if config.confidence_head else None

    @staticmethod
    def _pad_to_multiple(x: torch.Tensor, multiple: int = 8) -> Tuple[torch.Tensor, Tuple[int, int]]:
        h, w = x.shape[-2:]
        pad_h = (-h) % multiple
        pad_w = (-w) % multiple
        if pad_h or pad_w:
            # Replicate is deterministic and valid for tiny contract-test tiles.
            x = F.pad(x, (0, pad_w, 0, pad_h), mode="replicate")
        return x, (h, w)

    def decode_features(
        self,
        conditioning: torch.Tensor,
        global_condition: torch.Tensor,
        global_spatial_condition: Optional[torch.Tensor] = None,
    ) -> Tuple[torch.Tensor, StudentFeatures]:
        x, original = self._pad_to_multiple(conditioning, 8)
        s0 = self.enc0(self.stem(x), global_condition)
        s1 = self.enc1(self.down0(s0), global_condition)
        s2 = self.enc2(self.down1(s1), global_condition)
        b = self.bottleneck(self.down2(s2), global_condition)
        if self.global_context is not None:
            if global_spatial_condition is None:
                raise ValueError("global_spatial_condition is required when global_lowres_context is enabled")
            if global_spatial_condition.ndim != 4 or global_spatial_condition.shape[1] != self.config.input_channels:
                raise ValueError("global_spatial_condition must be N x 14 x H x W")
            b = b + self.global_context(global_spatial_condition, b.shape[-2:])

        y = F.interpolate(b, size=s2.shape[-2:], mode="nearest")
        y = self.dec2(self.up2(y) + s2, global_condition)
        y = F.interpolate(y, size=s1.shape[-2:], mode="nearest")
        y = self.dec1(self.up1(y) + s1, global_condition)
        y = F.interpolate(y, size=s0.shape[-2:], mode="nearest")
        y = self.dec0(self.up0(y) + s0, global_condition)
        y = y[..., :original[0], :original[1]]
        features = StudentFeatures(s0, s1, s2, b, y)
        return y, features

    def forward(
        self,
        conditioning: torch.Tensor,
        global_condition: torch.Tensor,
        global_spatial_condition: Optional[torch.Tensor] = None,
        *,
        return_features: bool = False,
    ) -> StudentOutput | Tuple[StudentOutput, StudentFeatures]:
        if conditioning.ndim != 4 or conditioning.shape[1] != self.config.input_channels:
            raise ValueError("conditioning must be N x 14 x H x W")
        if global_condition.ndim != 2 or global_condition.shape != (conditioning.shape[0], self.config.global_condition_dim):
            raise ValueError("invalid global conditioning shape")
        if not torch.isfinite(conditioning).all() or not torch.isfinite(global_condition).all():
            raise ValueError("student inputs must be finite")

        features, feature_set = self.decode_features(conditioning, global_condition, global_spatial_condition)
        raw = conditioning[:, 0:4]
        sigma = torch.exp(conditioning[:, 4:8]).clamp_min(1.0e-8)
        headroom = conditioning[:, 13:14].clamp(0.0, 1.0)

        z_fine = self.residual_head(features)
        z_coarse = self.coarse_residual_head(features) if self.coarse_residual_head is not None else None
        z = z_fine if z_coarse is None else z_fine + z_coarse
        k = self.config.residual_k_sigma
        z_bounded = k * torch.tanh(z / k)
        proposed_residual = sigma * z_bounded * headroom
        clean = (raw - proposed_residual).clamp(0.0, 1.0)
        residual = raw - clean
        z_effective = residual / sigma

        q = self.posterior_head(features).clamp(
            self.config.posterior_log_ratio_min,
            self.config.posterior_log_ratio_max,
        )
        posterior_variance = sigma.square() * torch.exp(q)
        confidence = torch.sigmoid(self.confidence_head(features)) if self.confidence_head is not None else None
        output = StudentOutput(
            clean_raw=clean,
            residual=residual,
            sigma_normalized_residual=z_effective,
            posterior_variance=posterior_variance,
            posterior_log_variance_ratio=q,
            confidence=confidence,
            fine_sigma_residual=(k * torch.tanh(z_fine / k)) if z_coarse is not None else None,
            coarse_sigma_residual=(k * torch.tanh(z_coarse / k)) if z_coarse is not None else None,
        )
        return (output, feature_set) if return_features else output

    def parameter_count(self) -> int:
        return sum(p.numel() for p in self.parameters())

    def export_primitive_contract(self) -> Mapping[str, object]:
        return {
            "conv_kernels": [1, 3],
            "stride2_conv": True,
            "nearest_upsample": True,
            "add": True,
            "multiply": True,
            "simple_gate": True,
            "film": True,
            "layer_norm": False,
            "transposed_convolution": False,
            "full_resolution_attention": False,
            "global_lowres_context": self.config.global_lowres_context,
            "fine_coarse_residual_head": self.config.fine_coarse_residual_head,
        }


def theoretical_receptive_field(config: StudentConfig) -> int:
    """Longest-path receptive field in packed-CFA pixels for the v1 graph.

    Nearest-neighbour resize changes the sampling jump but adds no new support.
    The returned odd integer can be converted to a minimum symmetric halo by
    (rf-1)//2.  Phase 4 must still confirm border parity with backend tests.
    """

    config.validate()
    rf = 1
    jump = 1

    def conv3(stride: int = 1) -> None:
        nonlocal rf, jump
        rf += 2 * jump
        jump *= stride

    conv3()  # stem
    for depth in config.encoder_blocks:
        for _ in range(depth):
            conv3()  # MobileNAFBlock spatial conv
        conv3(stride=2)  # downsample to next stage
    for _ in range(config.bottleneck_blocks):
        conv3()

    # Decoder longest path. Nearest upsample halves the effective input jump.
    for depth in config.decoder_blocks:
        jump //= 2
        conv3()  # up projection 3x3
        for _ in range(depth):
            conv3()
    conv3()  # output residual/posterior head
    if jump != 1:
        raise AssertionError("receptive field bookkeeping did not return to packed-pixel jump 1")
    return int(rf)
