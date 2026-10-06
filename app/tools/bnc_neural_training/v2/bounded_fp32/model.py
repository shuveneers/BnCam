"""W24 parameter topology with the single approved bounded-gate change."""
import torch
from torch import nn

from ..model import BncNeuralV2, Config, GatedBlock, SHARED_DILATIONS, OPPONENT_DILATIONS


class ResearchConfig(Config):
    def description(self):
        result = super().description()
        result['architectureVersion'] = 'BNC_NEURAL_V2_GOPP_W24_BOUNDED_GATE_FP32_RESEARCH'
        result['gate'] = '64 * tanh((a * b) / 64)'
        result['forwardPrecision'] = 'FP32'
        return result


class BoundedGatedBlock(GatedBlock):
    def forward(self, x):
        a, b = self.expand(self.depthwise(x)).chunk(2, dim=1)
        raw = a * b
        gate = 64 * torch.tanh(raw / 64)
        # Detached observations do not affect the model or gradients.
        self.raw_abs_max = float(raw.detach().abs().max())
        self.gate_abs_max = float(gate.detach().abs().max())
        delta = self.project(gate)
        return x + torch.tanh(self.alpha).to(delta.dtype) * delta


class BoundedBncNeuralV2(BncNeuralV2):
    def __init__(self):
        super().__init__(ResearchConfig())
        w = self.config.width
        self.shared = nn.Sequential(*(BoundedGatedBlock(w, d) for d in SHARED_DILATIONS))
        self.opponent = nn.Sequential(*(BoundedGatedBlock(w, d) for d in OPPONENT_DILATIONS))
        self.last_activations = {}
        self.shared.register_forward_hook(self._observe_shared)
        self.green.register_forward_hook(self._observe_green)

    def _observe_shared(self, module, inputs, output):
        self.last_activations['shared'] = float(output.detach().abs().max())

    def _observe_green(self, module, inputs, output):
        self.last_activations['green'] = float(output.detach().abs().max())

    def forward(self, packed):
        # Covers stem, shared, green, opponent, and reconstruction even if a
        # caller has enabled AMP outside this research model.
        with torch.autocast(device_type=packed.device.type, enabled=False):
            output = super().forward(packed.float())
        self.last_activations['opponent'] = float(output[:, 2:].detach().abs().max())
        return output
