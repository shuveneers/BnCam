"""Same-signal reconstruction objectives, in normalized camera-linear units."""
import torch
from .opponent import opponent_losses

DEFAULT_WEIGHTS = dict(missing=1.0, luma_gradient=0.2, opponent=0.2, detail=0.1,
                       opponent_gradient=0.1, opponent_alternating=0.01, zero_chroma_energy=0.1)


def rgb_from_missing(packed, missing):
    n, _, h, w = packed.shape
    rgb = packed.new_empty((n, 3, h * 2, w * 2))
    rgb[:, :, 0::2, 0::2] = torch.stack((packed[:, 0], missing[:, 0], missing[:, 1]), 1)
    rgb[:, :, 0::2, 1::2] = torch.stack((missing[:, 2], packed[:, 1], missing[:, 3]), 1)
    rgb[:, :, 1::2, 0::2] = torch.stack((missing[:, 4], packed[:, 2], missing[:, 5]), 1)
    rgb[:, :, 1::2, 1::2] = torch.stack((missing[:, 6], missing[:, 7], packed[:, 3]), 1)
    return rgb


def reconstruction_loss(packed, prediction, target, weights=None):
    weights = DEFAULT_WEIGHTS if weights is None else weights
    # All metrics compare to truth; none rewards neutralizing real scene chroma.
    rgb, truth = rgb_from_missing(packed, prediction), rgb_from_missing(packed, target)
    error = rgb - truth
    # Camera-basis mean luma is a training diagnostic, not a downstream CCM.
    luma = error.mean(1)
    dx, dy = luma[..., 1:] - luma[..., :-1], luma[..., 1:, :] - luma[..., :-1, :]
    lap = -4 * error[:, :, 1:-1, 1:-1] + error[:, :, :-2, 1:-1] + error[:, :, 2:, 1:-1] + error[:, :, 1:-1, :-2] + error[:, :, 1:-1, 2:]
    terms = dict(missing=((prediction-target).square() + 1e-8).sqrt().mean(),
                 luma_gradient=(dx.abs().mean() + dy.abs().mean()) * .5,
                 detail=lap.abs().mean())
    terms.update(opponent_losses(rgb, truth))
    return sum(weights.get(k, 0) * v for k, v in terms.items()), terms
