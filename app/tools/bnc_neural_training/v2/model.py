from dataclasses import dataclass
import math
import torch
from torch import nn

SHARED_DILATIONS = (1, 2, 3, 3, 2, 1)
OPPONENT_DILATIONS = (1, 2, 1)


@dataclass(frozen=True)
class Config:
    width: int = 24
    package_schema = 2
    output_domain = "G_at_R_G_at_B_then_signed_dR_G1_G2_B_dB_R_G1_G2_divide_4"

    def __post_init__(self):
        if self.width != 24:
            raise ValueError("Only BNC_NEURAL_V2_GOPP_W24 is authorized before the >=100k capacity review")

    @property
    def blocks(self):
        return 9

    @property
    def green_radius(self):
        return 1 + sum(SHARED_DILATIONS) + 2

    @property
    def halo(self):
        return self.green_radius + sum(OPPONENT_DILATIONS) + 1

    @property
    def parameter_count(self):
        return 37*self.width*self.width + 245*self.width + 8

    def description(self):
        return dict(architectureVersion="BNC_NEURAL_V2_GOPP_W24", width=self.width, blocks=9,
            sharedDilations=list(SHARED_DILATIONS), opponentDilations=list(OPPONENT_DILATIONS),
            greenActivation="SiLU", residualScale="per-channel tanh(alpha), initial=0.1, bounds=(-1,1)",
            parameterCount=self.parameter_count, fp16WeightBytes=self.parameter_count*2,
            greenReceptiveFieldPacked=2*self.green_radius+1,
            greenReceptiveFieldRaw=2*(2*self.green_radius+1),
            opponentReceptiveFieldPacked=2*self.halo+1,
            opponentReceptiveFieldRaw=2*(2*self.halo+1), haloPacked=self.halo)


class GatedBlock(nn.Module):
    def __init__(self, width, dilation):
        super().__init__()
        self.depthwise = nn.Conv2d(width, width, 3, padding=dilation, dilation=dilation, groups=width)
        self.expand = nn.Conv2d(width, 2*width, 1)
        self.project = nn.Conv2d(width, width, 1)
        self.alpha = nn.Parameter(torch.full((1,width,1,1), math.atanh(.1)))

    def forward(self, x):
        a, b = self.expand(self.depthwise(x)).chunk(2, dim=1)
        delta = self.project(a*b)
        return x + torch.tanh(self.alpha).to(delta.dtype)*delta


class BncNeuralV2(nn.Module):
    """Outputs [G@R,G@B,dR@G1,dR@G2,dR@B,dB@R,dB@G1,dB@G2]."""
    def __init__(self, config=Config()):
        super().__init__()
        self.config = config
        w = config.width
        self.stem = nn.Conv2d(4,w,3,padding=1)
        self.shared = nn.Sequential(*(GatedBlock(w,d) for d in SHARED_DILATIONS))
        self.green = nn.Sequential(nn.Conv2d(w,w,3,padding=1), nn.SiLU(), nn.Conv2d(w,2,3,padding=1))
        self.opponent_input = nn.Conv2d(w+8,w,1)
        self.opponent = nn.Sequential(*(GatedBlock(w,d) for d in OPPONENT_DILATIONS))
        self.opponent_output = nn.Conv2d(w,6,3,padding=1)

    def forward(self, packed):
        features = self.shared(self.stem(packed))
        green = self.green(features)
        scaffold = torch.stack((green[:,0],packed[:,1],packed[:,2],green[:,1]),dim=1)
        # Gated opponent blocks multiply two learned activations. A valid bright
        # scene can exceed FP16's 65504 limit at that product even with finite
        # inputs and weights. Keep this branch in FP32; its topology and weights
        # are unchanged, and FP32 inference already follows the same path.
        with torch.autocast(device_type=packed.device.type, enabled=False):
            chroma = self.opponent_input(torch.cat((features.float(),packed.float(),scaffold.float()),dim=1))
            signed = self.opponent_output(self.opponent(chroma))
        return torch.cat((green.float(),signed),dim=1)  # no final clipping/nonlinearity


def missing_rgb(packed, structured):
    """Deterministic structured -> existing eight-missing-channel contract.

    Add in FP32 even when convolutions use autocast. The final existing merge
    still replaces all measured sensels from the original scene-linear FP32 RAW.
    """
    p, s = packed.float(), structured.float()
    gr, gb = s[:,0],s[:,1]
    return torch.stack((gr,gr+s[:,5],p[:,1]+s[:,2],p[:,1]+s[:,6],
                        p[:,2]+s[:,3],p[:,2]+s[:,7],gb+s[:,4],gb),dim=1)


def structured_targets(truth):
    """Canonical full RGB truth NCHW in normalized scene-linear units."""
    g = truth[:,1]
    dr, db = truth[:,0]-g,truth[:,2]-g
    return torch.stack((g[:,0::2,0::2],g[:,1::2,1::2],dr[:,0::2,1::2],dr[:,1::2,0::2],
                        dr[:,1::2,1::2],db[:,0::2,0::2],db[:,0::2,1::2],db[:,1::2,0::2]),1)


class MissingRgbAdapter(nn.Module):
    """Lets the unchanged Phase-3 evaluator consume a structured v2 model."""
    def __init__(self, model):
        super().__init__()
        self.model,self.config=model,model.config

    def forward(self, packed):
        return missing_rgb(packed,self.model(packed))
