"""Identical W24 topology with only C in C*tanh((a*b)/C) varied."""
import torch
from torch import nn

from ..model import BncNeuralV2, Config, GatedBlock, SHARED_DILATIONS, OPPONENT_DILATIONS


class CapConfig(Config):
    def __init__(self, cap):
        super().__init__()
        object.__setattr__(self, 'cap', cap)

    def description(self):
        result = super().description()
        result['architectureVersion'] = 'BNC_NEURAL_V2_GOPP_W24_BOUNDED_GATE_FP32_CAP_STUDY'
        result['gate'] = f'{self.cap} * tanh((a * b) / {self.cap})'
        result['forwardPrecision'] = 'FP32'
        return result


class CapBlock(GatedBlock):
    def __init__(self, width, dilation, cap):
        super().__init__(width, dilation)
        self.cap = cap
        self.observe_amplitude = False
        self.amplitude = [0, 0, 0, 0]
        self.max_raw = 0.
        self.max_bounded = 0.

    def forward(self, x):
        a, b = self.expand(self.depthwise(x)).chunk(2, dim=1)
        raw = a * b
        gate = self.cap * torch.tanh(raw / self.cap)
        self.max_raw = max(self.max_raw, float(raw.detach().abs().max()))
        self.max_bounded = max(self.max_bounded, float(gate.detach().abs().max()))
        if self.observe_amplitude:
            r, g = raw.detach().abs(), (gate.detach()-raw.detach()).abs()
            self.amplitude[0] += raw.numel()
            for i, threshold in enumerate((.01, .05, .10), 1):
                self.amplitude[i] += int((g > threshold*r).sum())
        delta = self.project(gate)
        return x + torch.tanh(self.alpha).to(delta.dtype)*delta


class CapModel(BncNeuralV2):
    def __init__(self, cap):
        if cap not in (16, 32, 64):
            raise ValueError('study permits only C=16,32,64')
        super().__init__(CapConfig(cap))
        w = self.config.width
        self.shared = nn.Sequential(*(CapBlock(w, d, cap) for d in SHARED_DILATIONS))
        self.opponent = nn.Sequential(*(CapBlock(w, d, cap) for d in OPPONENT_DILATIONS))
        self.max_shared = 0.
        self.max_green = 0.
        self.max_opponent = 0.
        self.shared.register_forward_hook(self._shared_hook)
        self.green.register_forward_hook(self._green_hook)

    def _shared_hook(self, module, inputs, output):
        self.max_shared = max(self.max_shared, float(output.detach().abs().max()))

    def _green_hook(self, module, inputs, output):
        self.max_green = max(self.max_green, float(output.detach().abs().max()))

    def forward(self, packed):
        with torch.autocast(device_type=packed.device.type, enabled=False):
            output = super().forward(packed.float())
        self.max_opponent = max(self.max_opponent, float(output[:, 2:].detach().abs().max()))
        return output

    def blocks(self):
        return [m for m in self.modules() if isinstance(m, CapBlock)]

    def amplitude_report(self):
        count = sum(b.amplitude[0] for b in self.blocks())
        if not count:
            raise ValueError('no normal gate values were observed')
        return dict(values=count, over1Percent=sum(b.amplitude[1] for b in self.blocks())/count,
                    over5Percent=sum(b.amplitude[2] for b in self.blocks())/count,
                    over10Percent=sum(b.amplitude[3] for b in self.blocks())/count)

    def gate_peaks(self):
        return dict(maxRawGateProduct=max(b.max_raw for b in self.blocks()),
                    maxBoundedGate=max(b.max_bounded for b in self.blocks()))
