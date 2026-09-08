from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .contracts import GLOBAL_CONDITIONING_FIELDS, SPATIAL_CONDITIONING_CHANNELS


@dataclass(frozen=True)
class TeacherConfig:
    input_channels: int = 14
    global_condition_dim: int = len(GLOBAL_CONDITIONING_FIELDS)
    widths: Tuple[int, int, int, int] = (80, 120, 176, 240)
    encoder_blocks: Tuple[int, int, int] = (4, 6, 8)
    bottleneck_blocks: int = 10
    decoder_blocks: Tuple[int, int, int] = (8, 6, 4)
    residual_k_sigma: float = 4.0
    posterior_log_ratio_min: float = -6.0
    posterior_log_ratio_max: float = 3.0
    confidence_head: bool = True

    def validate(self) -> None:
        if self.input_channels != len(SPATIAL_CONDITIONING_CHANNELS):
            raise ValueError("teacher input_channels must match Phase-1 14-channel conditioning")
        if self.global_condition_dim != len(GLOBAL_CONDITIONING_FIELDS):
            raise ValueError("global_condition_dim contract drift")
        if len(self.widths) != 4 or len(self.encoder_blocks) != 3 or len(self.decoder_blocks) != 3:
            raise ValueError("teacher stage tuple lengths are fixed for v1")
        if any(v <= 0 for v in (*self.widths, *self.encoder_blocks, self.bottleneck_blocks, *self.decoder_blocks)):
            raise ValueError("teacher widths/depths must be positive")
        if self.residual_k_sigma <= 0.0:
            raise ValueError("residual_k_sigma must be >0")
        if self.posterior_log_ratio_max <= self.posterior_log_ratio_min:
            raise ValueError("invalid posterior clamp")


class LayerNorm2d(nn.Module):
    def __init__(self, channels: int, eps: float = 1.0e-6):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(1, channels, 1, 1))
        self.bias = nn.Parameter(torch.zeros(1, channels, 1, 1))
        self.eps = eps

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        mean = x.mean(dim=1, keepdim=True)
        var = (x - mean).square().mean(dim=1, keepdim=True)
        return (x - mean) * torch.rsqrt(var + self.eps) * self.weight + self.bias


class SimpleGate(nn.Module):
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        a, b = x.chunk(2, dim=1)
        return a * b


class NAFBlock(nn.Module):
    def __init__(self, channels: int, expansion: int = 2, ffn_expansion: int = 2):
        super().__init__()
        dw_channels = channels * expansion
        if dw_channels % 2:
            raise ValueError("expanded channels must be even")
        ffn_channels = channels * ffn_expansion
        if ffn_channels % 2:
            raise ValueError("FFN channels must be even")
        self.norm1 = LayerNorm2d(channels)
        self.conv1 = nn.Conv2d(channels, dw_channels, 1)
        self.dw = nn.Conv2d(dw_channels, dw_channels, 3, padding=1, groups=dw_channels)
        self.gate = SimpleGate()
        gated_channels = dw_channels // 2
        self.sca = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Conv2d(gated_channels, gated_channels, 1))
        self.conv2 = nn.Conv2d(gated_channels, channels, 1)
        self.beta = nn.Parameter(torch.zeros(1, channels, 1, 1))
        self.norm2 = LayerNorm2d(channels)
        self.ffn1 = nn.Conv2d(channels, ffn_channels, 1)
        self.ffn2 = nn.Conv2d(ffn_channels // 2, channels, 1)
        self.gamma = nn.Parameter(torch.zeros(1, channels, 1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = self.dw(self.conv1(self.norm1(x)))
        y = self.gate(y)
        y = y * self.sca(y)
        x = x + self.beta * self.conv2(y)
        y = self.gate(self.ffn1(self.norm2(x)))
        return x + self.gamma * self.ffn2(y)


class FiLM(nn.Module):
    def __init__(self, condition_dim: int, channels: int):
        super().__init__()
        hidden = max(64, channels)
        self.net = nn.Sequential(
            nn.Linear(condition_dim, hidden),
            nn.SiLU(),
            nn.Linear(hidden, channels * 2),
        )
        # Identity at initialization: gamma=1, beta=0 after residual form below.
        nn.init.zeros_(self.net[-1].weight)
        nn.init.zeros_(self.net[-1].bias)

    def forward(self, x: torch.Tensor, condition: torch.Tensor) -> torch.Tensor:
        gamma, beta = self.net(condition).chunk(2, dim=1)
        return x * (1.0 + gamma[:, :, None, None]) + beta[:, :, None, None]


class Stage(nn.Module):
    def __init__(self, channels: int, depth: int, condition_dim: int):
        super().__init__()
        self.film = FiLM(condition_dim, channels)
        self.blocks = nn.Sequential(*(NAFBlock(channels) for _ in range(depth)))

    def forward(self, x: torch.Tensor, condition: torch.Tensor) -> torch.Tensor:
        return self.blocks(self.film(x, condition))


@dataclass
class TeacherOutput:
    clean_raw: torch.Tensor
    residual: torch.Tensor
    sigma_normalized_residual: torch.Tensor
    posterior_variance: torch.Tensor
    posterior_log_variance_ratio: torch.Tensor
    confidence: torch.Tensor | None


@dataclass
class TeacherFeatures:
    encoder0: torch.Tensor
    encoder1: torch.Tensor
    encoder2: torch.Tensor
    bottleneck: torch.Tensor
    decoder0: torch.Tensor


class PhysicsConditionedTeacher(nn.Module):
    """Offline Teacher for canonical packed RAW.

    Output authority is physically bounded: the learned head predicts a residual
    in sigma units, never an unconstrained replacement RAW image.
    """

    def __init__(self, config: TeacherConfig = TeacherConfig()):
        super().__init__()
        config.validate()
        self.config = config
        w0, w1, w2, w3 = config.widths
        self.stem = nn.Conv2d(config.input_channels, w0, 3, padding=1)
        self.enc0 = Stage(w0, config.encoder_blocks[0], config.global_condition_dim)
        self.down0 = nn.Conv2d(w0, w1, 3, stride=2, padding=1)
        self.enc1 = Stage(w1, config.encoder_blocks[1], config.global_condition_dim)
        self.down1 = nn.Conv2d(w1, w2, 3, stride=2, padding=1)
        self.enc2 = Stage(w2, config.encoder_blocks[2], config.global_condition_dim)
        self.down2 = nn.Conv2d(w2, w3, 3, stride=2, padding=1)
        self.bottleneck = Stage(w3, config.bottleneck_blocks, config.global_condition_dim)

        self.up2 = nn.Conv2d(w3, w2, 3, padding=1)
        self.dec2 = Stage(w2, config.decoder_blocks[0], config.global_condition_dim)
        self.up1 = nn.Conv2d(w2, w1, 3, padding=1)
        self.dec1 = Stage(w1, config.decoder_blocks[1], config.global_condition_dim)
        self.up0 = nn.Conv2d(w1, w0, 3, padding=1)
        self.dec0 = Stage(w0, config.decoder_blocks[2], config.global_condition_dim)

        self.residual_head = nn.Conv2d(w0, 4, 3, padding=1)
        self.posterior_head = nn.Conv2d(w0, 4, 3, padding=1)
        self.confidence_head = nn.Conv2d(w0, 1, 3, padding=1) if config.confidence_head else None

    @staticmethod
    def _pad_to_multiple(x: torch.Tensor, multiple: int = 8) -> Tuple[torch.Tensor, Tuple[int, int]]:
        h, w = x.shape[-2:]
        pad_h = (-h) % multiple
        pad_w = (-w) % multiple
        if pad_h or pad_w:
            x = F.pad(x, (0, pad_w, 0, pad_h), mode="reflect")
        return x, (h, w)

    def _decode_features(self, conditioning: torch.Tensor, global_condition: torch.Tensor) -> torch.Tensor:
        x, original = self._pad_to_multiple(conditioning, 8)
        s0 = self.enc0(self.stem(x), global_condition)
        s1 = self.enc1(self.down0(s0), global_condition)
        s2 = self.enc2(self.down1(s1), global_condition)
        b = self.bottleneck(self.down2(s2), global_condition)

        y = F.interpolate(b, size=s2.shape[-2:], mode="nearest")
        y = self.dec2(self.up2(y) + s2, global_condition)
        y = F.interpolate(y, size=s1.shape[-2:], mode="nearest")
        y = self.dec1(self.up1(y) + s1, global_condition)
        y = F.interpolate(y, size=s0.shape[-2:], mode="nearest")
        y = self.dec0(self.up0(y) + s0, global_condition)
        return y[..., :original[0], :original[1]]

    def _decode_features_with_pyramid(
        self, conditioning: torch.Tensor, global_condition: torch.Tensor
    ) -> Tuple[torch.Tensor, TeacherFeatures]:
        x, original = self._pad_to_multiple(conditioning, 8)
        s0 = self.enc0(self.stem(x), global_condition)
        s1 = self.enc1(self.down0(s0), global_condition)
        s2 = self.enc2(self.down1(s1), global_condition)
        b = self.bottleneck(self.down2(s2), global_condition)
        y = F.interpolate(b, size=s2.shape[-2:], mode="nearest")
        y = self.dec2(self.up2(y) + s2, global_condition)
        y = F.interpolate(y, size=s1.shape[-2:], mode="nearest")
        y = self.dec1(self.up1(y) + s1, global_condition)
        y = F.interpolate(y, size=s0.shape[-2:], mode="nearest")
        y = self.dec0(self.up0(y) + s0, global_condition)
        y = y[..., :original[0], :original[1]]
        return y, TeacherFeatures(s0, s1, s2, b, y)

    def _output_from_features(self, conditioning: torch.Tensor, features: torch.Tensor) -> TeacherOutput:
        raw = conditioning[:, 0:4]
        sigma = torch.exp(conditioning[:, 4:8]).clamp_min(1.0e-8)
        headroom = conditioning[:, 13:14].clamp(0.0, 1.0)
        z = self.residual_head(features)
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
        return TeacherOutput(clean, residual, z_effective, posterior_variance, q, confidence)

    def _validate_inputs(self, conditioning: torch.Tensor, global_condition: torch.Tensor) -> None:
        if conditioning.ndim != 4 or conditioning.shape[1] != self.config.input_channels:
            raise ValueError("conditioning must be N x 14 x H x W")
        if global_condition.ndim != 2 or global_condition.shape != (conditioning.shape[0], self.config.global_condition_dim):
            raise ValueError("invalid global conditioning shape")
        if not torch.isfinite(conditioning).all() or not torch.isfinite(global_condition).all():
            raise ValueError("teacher inputs must be finite")

    def forward(self, conditioning: torch.Tensor, global_condition: torch.Tensor) -> TeacherOutput:
        self._validate_inputs(conditioning, global_condition)
        features = self._decode_features(conditioning, global_condition)
        return self._output_from_features(conditioning, features)

    def forward_with_features(
        self, conditioning: torch.Tensor, global_condition: torch.Tensor
    ) -> Tuple[TeacherOutput, TeacherFeatures]:
        self._validate_inputs(conditioning, global_condition)
        features, pyramid = self._decode_features_with_pyramid(conditioning, global_condition)
        return self._output_from_features(conditioning, features), pyramid

    def parameter_count(self) -> int:
        return sum(p.numel() for p in self.parameters())
