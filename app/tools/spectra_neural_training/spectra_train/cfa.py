from __future__ import annotations

from typing import Dict, Tuple

import numpy as np

from .contracts import CfaSpec


def _effective_grid(spec: CfaSpec) -> np.ndarray:
    spec.validate()
    grid = np.asarray(list(spec.arrangement), dtype="U1").reshape(2, 2)
    # A crop beginning at (offset_x, offset_y) changes the phase seen at the
    # new origin.  np.roll with negative shifts expresses that new 2x2 cell.
    return np.roll(grid, shift=(-spec.offset_y, -spec.offset_x), axis=(0, 1))


def canonical_offsets(spec: CfaSpec) -> Dict[str, Tuple[int, int]]:
    grid = _effective_grid(spec)
    r_pos = tuple(int(v) for v in np.argwhere(grid == "R")[0])
    b_pos = tuple(int(v) for v in np.argwhere(grid == "B")[0])
    greens = [tuple(int(v) for v in p) for p in np.argwhere(grid == "G")]
    if len(greens) != 2:
        raise ValueError("standard Bayer cell must contain exactly two greens")
    g1 = next((p for p in greens if p[0] == r_pos[0]), None)
    g2 = next((p for p in greens if p[1] == r_pos[1]), None)
    if g1 is None or g2 is None or g1 == g2:
        raise ValueError("could not resolve canonical G1/G2")
    # Return (x,y) to match the C++ contract.
    return {
        "R": (r_pos[1], r_pos[0]),
        "G1": (g1[1], g1[0]),
        "G2": (g2[1], g2[0]),
        "B": (b_pos[1], b_pos[0]),
    }


def canonical_pack_mosaic(raw: np.ndarray, spec: CfaSpec) -> np.ndarray:
    raw = np.asarray(raw)
    if raw.ndim != 2:
        raise ValueError("mosaic RAW must be HxW")
    h, w = raw.shape
    if h < 2 or w < 2 or h % 2 or w % 2:
        raise ValueError("mosaic extent must be even and at least 2x2")
    offsets = canonical_offsets(spec)
    out = []
    for channel in ("R", "G1", "G2", "B"):
        x, y = offsets[channel]
        out.append(raw[y:h:2, x:w:2])
    packed = np.stack(out, axis=0)
    if packed.shape != (4, h // 2, w // 2):
        raise AssertionError("canonical pack produced inconsistent plane shapes")
    return packed


def normalize_canonical_packed(
    packed_raw: np.ndarray,
    black_level_raw: np.ndarray,
    white_level_raw: np.ndarray,
) -> np.ndarray:
    packed = np.asarray(packed_raw, dtype=np.float32)
    if packed.ndim != 3 or packed.shape[0] != 4:
        raise ValueError("canonical packed RAW must be 4xHxW")
    black = np.asarray(black_level_raw, dtype=np.float32).reshape(4, 1, 1)
    white = np.asarray(white_level_raw, dtype=np.float32).reshape(4, 1, 1)
    if not np.isfinite(packed).all() or not np.isfinite(black).all() or not np.isfinite(white).all():
        raise ValueError("RAW levels must be finite")
    denom = white - black
    if np.any(denom <= 0.0):
        raise ValueError("white levels must exceed black levels")
    return np.clip((packed - black) / denom, 0.0, 1.0).astype(np.float32, copy=False)
