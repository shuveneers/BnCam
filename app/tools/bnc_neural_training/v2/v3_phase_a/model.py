"""Keep W24 topology and parameters; vary only its nine gate operations."""
import torch
from torch import nn

from ..model import BncNeuralV2, Config, GatedBlock, SHARED_DILATIONS, OPPONENT_DILATIONS

VARIANTS = ('A', 'B', 'C')
GATES = {'A': 'a*b', 'B': 'a*tanh(b)', 'C': '16*tanh((a*b)/16)'}


class PhaseConfig(Config):
    def __init__(self, variant):
        super().__init__()
        object.__setattr__(self, 'variant', variant)

    def description(self):
        result = super().description()
        result.update(architectureVersion='BNC_NEURAL_V3_PHASE_A_RANDOM_INIT',
                      gate=GATES[self.variant], forwardPrecision='FP32')
        return result


class PhaseBlock(GatedBlock):
    def __init__(self, width, dilation, variant):
        super().__init__(width, dilation)
        self.variant = variant
        self.peak_branch = None
        self.peak_gate = None

    def forward(self, x):
        a, b = self.expand(self.depthwise(x)).chunk(2, dim=1)
        if self.variant == 'A':
            gate = a*b
        elif self.variant == 'B':
            gate = a*torch.tanh(b)
        else:
            gate = 16*torch.tanh((a*b)/16)
        branch_peak = torch.maximum(a.detach().abs().amax(), b.detach().abs().amax())
        gate_peak = gate.detach().abs().amax()
        self.peak_branch = branch_peak if self.peak_branch is None else torch.maximum(self.peak_branch, branch_peak)
        self.peak_gate = gate_peak if self.peak_gate is None else torch.maximum(self.peak_gate, gate_peak)
        delta = self.project(gate)
        return x+torch.tanh(self.alpha).to(delta.dtype)*delta


class PhaseModel(BncNeuralV2):
    def __init__(self, variant):
        if variant not in VARIANTS:
            raise ValueError('Only the three requested Phase A gates are permitted')
        super().__init__(PhaseConfig(variant))
        w = self.config.width
        self.shared = nn.Sequential(*(PhaseBlock(w, d, variant) for d in SHARED_DILATIONS))
        self.opponent = nn.Sequential(*(PhaseBlock(w, d, variant) for d in OPPONENT_DILATIONS))

    def forward(self, packed):
        with torch.autocast(device_type=packed.device.type, enabled=False):
            return super().forward(packed.float())

    def peaks(self):
        blocks = [b for b in self.modules() if isinstance(b, PhaseBlock)]
        return dict(maxBranchActivation=max(float(b.peak_branch) for b in blocks if b.peak_branch is not None),
                    maxGateOutput=max(float(b.peak_gate) for b in blocks if b.peak_gate is not None))
