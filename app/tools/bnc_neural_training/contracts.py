"""FP32 CFA geometry and exact measured-sample merge, outside the learned model.

Coordinates are (x, y). Pattern names describe the sensor at absolute origin (0,0).
sensor_origin + crop_offset is applied ONCE; never pass an already adjusted pattern.
Spatial reflection, not just channel permutation, makes the model's geometry RGGB.
"""
from dataclasses import dataclass
import numpy as np

PATTERNS = {"RGGB": (0, 0), "GRBG": (1, 0), "GBRG": (0, 1), "BGGR": (1, 1)}
SITES = ((0, 0, 0), (0, 1, 1), (1, 0, 1), (1, 1, 2))
MISSING = ((1, 2), (0, 2), (0, 2), (0, 1))
INPUT_SCALE = 4.0


@dataclass(frozen=True)
class Geometry:
    height: int
    width: int
    flip_x: bool
    flip_y: bool


def geometry(shape, pattern="RGGB", sensor_origin=(0, 0), crop_offset=(0, 0)):
    if pattern not in PATTERNS or len(shape) != 2 or min(shape) < 2:
        raise ValueError("BNC_NEURAL_CFA_GEOMETRY_INVALID")
    if any(not isinstance(v, (int, np.integer)) for v in (*sensor_origin, *crop_offset)):
        raise ValueError("CFA offsets must be integer sensor coordinates")
    rx, ry = PATTERNS[pattern]
    return Geometry(*shape, bool(rx ^ ((sensor_origin[0] + crop_offset[0]) & 1)),
                    bool(ry ^ ((sensor_origin[1] + crop_offset[1]) & 1)))


def canonicalize(image, g):
    if image.shape[:2] != (g.height, g.width):
        raise ValueError("geometry/image mismatch")
    # Reflect without repeating the border: the added sensel retains CFA parity.
    pads = [(0, g.height & 1), (0, g.width & 1)] + [(0, 0)] * (image.ndim - 2)
    v = np.pad(image, pads, mode="reflect")
    if g.flip_y:
        v = v[::-1]
    if g.flip_x:
        v = v[:, ::-1]
    return np.ascontiguousarray(v)


def uncanonicalize(image, g):
    if g.flip_x:
        image = image[:, ::-1]
    if g.flip_y:
        image = image[::-1]
    return np.ascontiguousarray(image[:g.height, :g.width])


def measured_mask(g):
    mask = np.zeros(((g.height + 1) // 2 * 2, (g.width + 1) // 2 * 2, 3), bool)
    for y, x, c in SITES:
        mask[y::2, x::2, c] = True
    return uncanonicalize(mask, g)


def sample(rgb, pattern="RGGB", sensor_origin=(0, 0), crop_offset=(0, 0)):
    g = geometry(rgb.shape[:2], pattern, sensor_origin, crop_offset)
    return rgb[measured_mask(g)].reshape(rgb.shape[:2]).astype(np.float32)


def pack(raw, pattern="RGGB", sensor_origin=(0, 0), crop_offset=(0, 0)):
    if raw.dtype != np.float32 or raw.ndim != 2:
        raise ValueError("finalized Bayer must be a two-dimensional FP32 array")
    if not np.isfinite(raw).all() or raw.min() < 0 or raw.max() > INPUT_SCALE:
        raise ValueError("BNC_NEURAL_INPUT_DOMAIN_INVALID")
    g = geometry(raw.shape, pattern, sensor_origin, crop_offset)
    v = canonicalize(raw, g)
    return np.stack([v[y::2, x::2] for y, x, _ in SITES], -1) / INPUT_SCALE, g


def targets(rgb, g):
    v = canonicalize(rgb, g)
    return np.stack([v[y::2, x::2, c] for (y, x, _), cs in zip(SITES, MISSING)
                     for c in cs], -1) / INPUT_SCALE


def merge(raw, missing, g):
    expected = ((g.height + 1) // 2, (g.width + 1) // 2, 8)
    if missing.shape != expected or not np.isfinite(missing).all():
        raise ValueError("BNC_NEURAL_PREDICTION_INVALID")
    v = np.empty((expected[0] * 2, expected[1] * 2, 3), np.float32)
    original = canonicalize(raw, g)
    for site, ((y, x, c), cs) in enumerate(zip(SITES, MISSING)):
        v[y::2, x::2, c] = original[y::2, x::2]  # no FP16 or /4 roundtrip
        for k, channel in enumerate(cs):
            v[y::2, x::2, channel] = missing[..., 2 * site + k].astype(np.float32) * INPUT_SCALE
    return uncanonicalize(v, g)
