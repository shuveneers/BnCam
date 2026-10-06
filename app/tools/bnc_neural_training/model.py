"""Compact local gated residual reconstruction; no classical RGB input or skip."""
from dataclasses import dataclass, asdict
import torch
from torch import nn


@dataclass(frozen=True)
class Config:
    width: int = 12
    blocks: int = 6

    def __post_init__(self):
        if self.width not in (8, 12, 16) or self.blocks not in (4, 6):
            raise ValueError("unsupported BnC Neural architecture")

    @property
    def halo(self):
        return self.blocks + 2

    @property
    def receptive_field(self):
        return 2 * self.halo + 1  # packed cells, two raw pixels each

    @property
    def parameter_count(self):
        w = self.width
        return 109 * w + 8 + self.blocks * (19 * w * w + 3 * w)

    def description(self):
        return dict(**asdict(self), architectureVersion="bnc-gated-local-v1",
                    parameterCount=self.parameter_count,
                    receptiveFieldPacked=self.receptive_field,
                    receptiveFieldRaw=2 * self.receptive_field,
                    haloPacked=self.halo, fp16WeightBytes=2 * self.parameter_count)


class Block(nn.Module):
    def __init__(self, width):
        super().__init__()
        self.spatial = nn.Conv2d(width, width * 2, 3, padding=1)
        self.project = nn.Conv2d(width, width, 1)

    def forward(self, x):
        a, b = self.spatial(x).chunk(2, dim=1)
        return x + self.project(a * b)


class BncNeural(nn.Module):
    def __init__(self, config=Config()):
        super().__init__()
        self.config = config
        self.stem = nn.Conv2d(4, config.width, 3, padding=1)
        self.blocks = nn.ModuleList(Block(config.width) for _ in range(config.blocks))
        self.head = nn.Conv2d(config.width, 8, 3, padding=1)

    def forward(self, packed):
        x = self.stem(packed)
        for block in self.blocks:
            x = block(x)
        return self.head(x)


@torch.inference_mode()
def infer_tiled(model, packed, inner=128):
    """True image edges stay true edges at EVERY layer; copy each core exactly once."""
    if inner < 1:
        raise ValueError("tile core must be positive")
    n, c, h, w = packed.shape
    if c != 4:
        raise ValueError("expected 4 canonical CFA channels")
    out = packed.new_empty((n, 8, h, w))
    halo = model.config.halo
    for y in range(0, h, inner):
        for x in range(0, w, inner):
            ey, ex = min(y + inner, h), min(x + inner, w)
            sy, sx = max(0, y - halo), max(0, x - halo)
            tile = model(packed[:, :, sy:min(h, ey + halo), sx:min(w, ex + halo)])
            out[:, :, y:ey, x:ex] = tile[:, :, y-sy:ey-sy, x-sx:ex-sx]
    return out


def execution_plan(config, packed_width, packed_height, inner=128):
    if min(packed_width, packed_height, inner) < 1:
        raise ValueError("invalid tile dimensions")
    # Future GPU slots: input C4, A/B width, C 2*width, missing C8. No aliases.
    area = (inner + 2 * config.halo) ** 2
    return dict(tileCount=((packed_width + inner - 1) // inner) *
                ((packed_height + inner - 1) // inner),
                halo=config.halo, inner=inner, dispatchesPerTile=4 + 4 * config.blocks,
                plannedFp16ScratchBytes=area * (4 + 4 * config.width + 8) * 2,
                measuredGpuMemoryBytes=None,
                operations=["pack_fp32_to_fp16_c4", "stem_conv3"] +
                [op for _ in range(config.blocks) for op in
                 ["spatial_conv3_2w", "simple_gate", "project_conv1", "add"]] +
                ["head_conv3_8", "merge_original_fp32_sensels"])
