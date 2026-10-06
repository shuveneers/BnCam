"""Opponent supervision and diagnostics; no filter is applied to predicted pixels.

Every term is relative to the same RGB truth. Zero-chroma energy is conditional
on BOTH truth opponents being zero. Color edges are never treated as false color.
"""
import numpy as np
import torch


def opponents(rgb):
    return torch.stack((rgb[:, 0]-rgb[:, 1], rgb[:, 2]-rgb[:, 1]), 1)


def differences(v):
    return v[..., 1:] - v[..., :-1], v[..., 1:, :] - v[..., :-1, :]


def soft_sign_changes(v):
    # tau is fixed numerical conditioning in normalized input units, never noise sigma.
    s = v / (v.abs()+.01)
    return torch.relu(-s[..., 1:]*s[..., :-1]), torch.relu(-s[..., 1:, :]*s[..., :-1, :])


def opponent_losses(rgb, truth):
    prediction, target = opponents(rgb), opponents(truth)
    error = prediction-target
    dx, dy = differences(error)
    changes, expected_changes = soft_sign_changes(prediction), soft_sign_changes(target)
    zero = (target.abs().amax(dim=1, keepdim=True) <= 1e-7).to(prediction.dtype)
    # Denominator counts both opponent components; all-colored batches contribute zero.
    energy = (prediction.square()*zero).sum() / (2*zero.sum()).clamp_min(1)
    return dict(opponent=error.abs().mean(),
                opponent_gradient=(dx.abs().mean()+dy.abs().mean())*.5,
                opponent_alternating=sum((a-b).abs().mean() for a,b in zip(changes,expected_changes))*.5,
                zero_chroma_energy=energy)


def opponent_metrics(rgb, truth):
    prediction = np.stack((rgb[..., 0]-rgb[..., 1], rgb[..., 2]-rgb[..., 1]), -1).astype("f8")
    target = np.stack((truth[..., 0]-truth[..., 1], truth[..., 2]-truth[..., 1]), -1).astype("f8")
    error, rows = prediction-target, {}
    zero = np.max(abs(target), axis=-1) <= 1e-7
    for c, name in enumerate(("RG", "BG")):
        p, t, e = prediction[..., c], target[..., c], error[..., c]
        gradient_error = [np.diff(e, axis=axis) for axis in (0, 1)]
        changes, truth_changes = [], []
        for axis in (0, 1):
            pa, pb = (p[:-1], p[1:]) if axis == 0 else (p[:, :-1], p[:, 1:])
            ta, tb = (t[:-1], t[1:]) if axis == 0 else (t[:, :-1], t[:, 1:])
            changes.append((pa*pb<0)&(abs(pa)>1e-5)&(abs(pb)>1e-5))
            truth_changes.append((ta*tb<0)&(abs(ta)>1e-5)&(abs(tb)>1e-5))
        tg = np.sqrt(sum(np.mean(np.diff(t, axis=axis)**2) for axis in (0, 1))*.5)
        pg = np.sqrt(sum(np.mean(np.diff(p, axis=axis)**2) for axis in (0, 1))*.5)
        rows[name] = dict(reconstruction_rms=float(np.sqrt(np.mean(e**2))),
            gradient_error_rms=float(np.sqrt(sum(np.mean(g**2) for g in gradient_error)*.5)),
            alternating_sign_change_fraction=float(np.mean([v.mean() for v in changes])),
            alternating_sign_change_error=float(np.mean([(a!=b).mean() for a,b in zip(changes,truth_changes)])),
            zero_chroma_false_color_energy=float(np.mean(p[zero]**2)) if zero.any() else None,
            chromatic_gradient_retention=float(pg/tg) if tg>1e-10 else None)
    return rows
