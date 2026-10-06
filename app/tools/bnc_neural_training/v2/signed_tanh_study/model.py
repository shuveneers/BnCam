"""W24 parameter topology with only the symmetric signed gate substituted."""
import torch
from torch import nn

from ..model import BncNeuralV2, Config, GatedBlock, SHARED_DILATIONS, OPPONENT_DILATIONS


class SignedConfig(Config):
    def __init__(self, tau):
        super().__init__()
        object.__setattr__(self, 'tau', tau)

    def description(self):
        result=super().description()
        result['architectureVersion']='BNC_NEURAL_V2_GOPP_W24_SIGNED_TANH_GATE_FP32_RESEARCH'
        result['gate']='0.5 * (a * tau*tanh(b/tau) + b * tau*tanh(a/tau))'
        result['tau']=self.tau
        result['forwardPrecision']='FP32'
        return result


class SignedBlock(GatedBlock):
    def __init__(self,width,dilation,tau,name):
        super().__init__(width,dilation)
        self.tau,self.name=tau,name
        self.observer=None
        self.gate_observer=None
        self.max_input=0.
        self.max_gate=0.

    def forward(self,x):
        a,b=self.expand(self.depthwise(x)).chunk(2,dim=1)
        if self.observer is not None:
            self.observer(self.name,a,b)
        if self.tau is None:
            gate=a*b
        else:
            gate=.5*(a*(self.tau*torch.tanh(b/self.tau))+
                     b*(self.tau*torch.tanh(a/self.tau)))
        if self.gate_observer is not None:
            self.gate_observer(self.name,a,b,gate)
        self.max_input=max(self.max_input,float(torch.maximum(a.detach().abs().max(),b.detach().abs().max())))
        self.max_gate=max(self.max_gate,float(gate.detach().abs().max()))
        delta=self.project(gate)
        return x+torch.tanh(self.alpha).to(delta.dtype)*delta


class SignedModel(BncNeuralV2):
    def __init__(self,tau):
        if tau is not None and (not isinstance(tau,(int,float)) or tau<=0):
            raise ValueError('tau must be positive, or None for original product diagnostics')
        super().__init__(SignedConfig(tau))
        w=self.config.width
        self.shared=nn.Sequential(*(SignedBlock(w,d,tau,f'shared{i}') for i,d in enumerate(SHARED_DILATIONS)))
        self.opponent=nn.Sequential(*(SignedBlock(w,d,tau,f'opponent{i}') for i,d in enumerate(OPPONENT_DILATIONS)))

    def forward(self,packed):
        with torch.autocast(device_type=packed.device.type,enabled=False):
            return super().forward(packed.float())

    def blocks(self):
        return [m for m in self.modules() if isinstance(m,SignedBlock)]

    def peaks(self):
        return dict(maxGateInput=max(b.max_input for b in self.blocks()),
                    maxGateOutput=max(b.max_gate for b in self.blocks()))
