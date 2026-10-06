"""Matched W24 controls: normalized bounded gate versus normalized ungated SiLU."""
import torch
from torch import nn
from ..model import BncNeuralV2, Config, GatedBlock, SHARED_DILATIONS, OPPONENT_DILATIONS
from ..training_safety import gate


class FeatureNorm(nn.Module):
    """Channel-wise at each pixel, never scene/gain normalization of measured RAW."""
    def __init__(self, width):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(1,width,1,1))
        self.bias = nn.Parameter(torch.zeros(1,width,1,1))
        self.eps = 1e-6

    def forward(self, x):
        x = x.float()
        centered = x-x.mean(1,keepdim=True)
        variance = centered.square().mean(1,keepdim=True)
        return centered*torch.rsqrt(variance+self.eps)*self.weight+self.bias


class ResearchConfig(Config):
    export_allowed = False
    def __init__(self, variant):
        super().__init__()
        object.__setattr__(self,'variant',variant)

    @property
    def parameter_count(self):
        return super().parameter_count+2*self.width*self.blocks

    def description(self):
        result = super().description()
        result.update(architectureVersion='BNC_NEURAL_V4_RESEARCH_W24', candidate=self.variant,
                      featureNormalization='per-pixel across learned channels; affine; eps=1e-6',
                      blockOperation='16*tanh(a*b/16)' if self.variant=='N' else '0.5*(SiLU(a)+SiLU(b))',
                      forwardPrecision='FP32', productReady=False)
        return result


class ResearchBlock(GatedBlock):
    def __init__(self,width,dilation,variant):
        super().__init__(width,dilation)
        self.variant = variant
        self.norm = FeatureNorm(width)

    def forward(self,x):
        a,b = self.expand(self.depthwise(self.norm(x))).chunk(2,1)
        delta = self.project(gate(a,b,self.variant))
        return x+torch.tanh(self.alpha)*delta


class ResearchModel(BncNeuralV2):
    def __init__(self,variant):
        if variant not in ('N','S'):
            raise ValueError('V4 research permits N and S only')
        super().__init__(ResearchConfig(variant))
        w=self.config.width
        self.shared=nn.Sequential(*(ResearchBlock(w,d,variant) for d in SHARED_DILATIONS))
        self.opponent=nn.Sequential(*(ResearchBlock(w,d,variant) for d in OPPONENT_DILATIONS))

    def forward(self,packed):
        with torch.autocast(device_type=packed.device.type,enabled=False):
            return super().forward(packed.float())
