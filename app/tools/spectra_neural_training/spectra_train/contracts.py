from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Mapping, Sequence, Tuple

import numpy as np

CANONICAL_CHANNELS: Tuple[str, str, str, str] = ("R", "G1", "G2", "B")
SPATIAL_CONDITIONING_CHANNELS: Tuple[str, ...] = (
    "raw_r",
    "raw_g1",
    "raw_g2",
    "raw_b",
    "log_sigma_r",
    "log_sigma_g1",
    "log_sigma_g2",
    "log_sigma_b",
    "remaining_lsc_r",
    "remaining_lsc_g1",
    "remaining_lsc_g2",
    "remaining_lsc_b",
    "metadata_trust",
    "headroom",
)

# Compact/global features only.  No phone model, sensor model string,
# manufacturer, lens ID, or sensor ID may enter this vector.
GLOBAL_CONDITIONING_FIELDS: Tuple[str, ...] = (
    "log_exposure_seconds",
    "log_analog_gain",
    "log_digital_gain",
    "bit_depth_over_32",
    "noise_model_trust",
    "black_level_trust",
    "remaining_lsc_trust",
    "row_periodicity",
    "column_periodicity",
    "fixed_pattern",
    "dsnu_like",
    "prnu_like",
    "low_frequency_residual",
    "low_frequency_chroma",
    "channel_imbalance",
    "spatial_black_drift",
    "rare_readout_pattern",
    "structured_confidence",
)


class RecordKind(str, Enum):
    CLEAN = "clean"
    PAIRED = "paired"
    DARK = "dark"


class StorageLayout(str, Enum):
    MOSAIC = "mosaic"
    CANONICAL_PACKED = "canonical_packed"


@dataclass(frozen=True)
class CfaSpec:
    arrangement: str
    offset_x: int = 0
    offset_y: int = 0

    def validate(self) -> None:
        if self.arrangement not in {"RGGB", "GRBG", "GBRG", "BGGR"}:
            raise ValueError(f"unsupported Bayer arrangement: {self.arrangement}")
        if self.offset_x not in (0, 1) or self.offset_y not in (0, 1):
            raise ValueError("CFA offsets must be 0 or 1")


@dataclass(frozen=True)
class PhysicsMetadata:
    shot_s: np.ndarray
    read_o: np.ndarray
    black_level_raw: np.ndarray
    white_level_raw: np.ndarray
    exposure_time_seconds: float
    analog_gain: float
    digital_gain: float
    bit_depth: int
    metadata_trust: float = 1.0
    noise_model_trust: float = 1.0
    black_level_trust: float = 1.0
    remaining_lsc_trust: float = 1.0
    lens_shading_already_applied: bool = False
    structured_noise: Mapping[str, float] = field(default_factory=dict)

    def validate(self) -> None:
        for name, value in (("shot_s", self.shot_s), ("read_o", self.read_o),
                            ("black_level_raw", self.black_level_raw),
                            ("white_level_raw", self.white_level_raw)):
            arr = np.asarray(value, dtype=np.float32)
            if arr.shape != (4,) or not np.isfinite(arr).all():
                raise ValueError(f"{name} must be finite shape (4,)")
        if np.any(np.asarray(self.shot_s) < 0.0) or np.any(np.asarray(self.read_o) < 0.0):
            raise ValueError("S/O must be non-negative")
        if np.any(np.asarray(self.white_level_raw) <= np.asarray(self.black_level_raw)):
            raise ValueError("white levels must exceed black levels")
        if not np.isfinite(self.exposure_time_seconds) or self.exposure_time_seconds <= 0.0:
            raise ValueError("exposure_time_seconds must be > 0")
        if not np.isfinite(self.analog_gain) or self.analog_gain <= 0.0:
            raise ValueError("analog_gain must be > 0")
        if not np.isfinite(self.digital_gain) or self.digital_gain <= 0.0:
            raise ValueError("digital_gain must be > 0")
        if self.bit_depth <= 0 or self.bit_depth > 32:
            raise ValueError("bit_depth must be in 1..32")
        for name in ("metadata_trust", "noise_model_trust", "black_level_trust", "remaining_lsc_trust"):
            v = float(getattr(self, name))
            if not np.isfinite(v) or v < 0.0 or v > 1.0:
                raise ValueError(f"{name} must be in [0,1]")
        allowed = {
            "row_periodicity", "column_periodicity", "fixed_pattern", "dsnu_like", "prnu_like",
            "low_frequency_residual", "low_frequency_chroma", "channel_imbalance",
            "spatial_black_drift", "rare_readout_pattern", "confidence",
        }
        unknown = set(self.structured_noise) - allowed
        if unknown:
            raise ValueError(f"unknown structured-noise fields: {sorted(unknown)}")
        for key, value in self.structured_noise.items():
            if not np.isfinite(value) or value < 0.0 or value > 1.0:
                raise ValueError(f"structured-noise {key} must be in [0,1]")

    @classmethod
    def from_mapping(cls, m: Mapping[str, Any]) -> "PhysicsMetadata":
        structured = dict(m.get("structured_noise", {}))
        out = cls(
            shot_s=np.asarray(m["shot_s"], dtype=np.float32),
            read_o=np.asarray(m["read_o"], dtype=np.float32),
            black_level_raw=np.asarray(m["black_level_raw"], dtype=np.float32),
            white_level_raw=np.asarray(m["white_level_raw"], dtype=np.float32),
            exposure_time_seconds=float(m["exposure_time_seconds"]),
            analog_gain=float(m["analog_gain"]),
            digital_gain=float(m["digital_gain"]),
            bit_depth=int(m["bit_depth"]),
            metadata_trust=float(m.get("metadata_trust", 1.0)),
            noise_model_trust=float(m.get("noise_model_trust", m.get("metadata_trust", 1.0))),
            black_level_trust=float(m.get("black_level_trust", m.get("metadata_trust", 1.0))),
            remaining_lsc_trust=float(m.get("remaining_lsc_trust", m.get("metadata_trust", 1.0))),
            lens_shading_already_applied=bool(m.get("lens_shading_already_applied", False)),
            structured_noise=structured,
        )
        out.validate()
        return out


def physics_global_vector(meta: PhysicsMetadata) -> np.ndarray:
    meta.validate()
    s = meta.structured_noise
    values = np.asarray(
        [
            np.log(max(meta.exposure_time_seconds, 1.0e-12)),
            np.log(max(meta.analog_gain, 1.0e-8)),
            np.log(max(meta.digital_gain, 1.0e-8)),
            meta.bit_depth / 32.0,
            meta.noise_model_trust,
            meta.black_level_trust,
            meta.remaining_lsc_trust,
            s.get("row_periodicity", 0.0),
            s.get("column_periodicity", 0.0),
            s.get("fixed_pattern", 0.0),
            s.get("dsnu_like", 0.0),
            s.get("prnu_like", 0.0),
            s.get("low_frequency_residual", 0.0),
            s.get("low_frequency_chroma", 0.0),
            s.get("channel_imbalance", 0.0),
            s.get("spatial_black_drift", 0.0),
            s.get("rare_readout_pattern", 0.0),
            s.get("confidence", 0.0),
        ],
        dtype=np.float32,
    )
    if values.shape != (len(GLOBAL_CONDITIONING_FIELDS),) or not np.isfinite(values).all():
        raise ValueError("invalid global conditioning vector")
    return values
