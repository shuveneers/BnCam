from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Sequence, Tuple

import numpy as np

from .cfa import canonical_pack_mosaic, normalize_canonical_packed
from .contracts import RecordKind, StorageLayout, physics_global_vector
from .manifest import DatasetManifest, DatasetRecord, SensorSplitPolicy, records_for_split


def load_numpy_array(path: Path, key: Optional[str] = None) -> np.ndarray:
    if not path.exists():
        raise FileNotFoundError(path)
    if path.suffix.lower() == ".npy":
        arr = np.load(path, allow_pickle=False)
    elif path.suffix.lower() == ".npz":
        with np.load(path, allow_pickle=False) as z:
            if key is None:
                if len(z.files) != 1:
                    raise ValueError(f"npz {path} has multiple arrays; array_key is required")
                arr = z[z.files[0]]
            else:
                arr = z[key]
    else:
        raise ValueError(f"unsupported RAW training array format: {path.suffix}")
    if not np.issubdtype(arr.dtype, np.number) or not np.isfinite(arr).all():
        raise ValueError(f"array must be finite numeric data: {path}")
    return np.asarray(arr)


def _canonical_record_array(root: Path, record: DatasetRecord, relative_path: str, normalize: bool) -> np.ndarray:
    arr = load_numpy_array((root / relative_path).resolve(), record.array_key)
    if record.storage_layout == StorageLayout.MOSAIC:
        packed = canonical_pack_mosaic(arr, record.cfa)
    else:
        if arr.ndim == 3 and arr.shape[0] == 4:
            packed = arr
        elif arr.ndim == 3 and arr.shape[-1] == 4:
            packed = np.moveaxis(arr, -1, 0)
        else:
            raise ValueError("canonical_packed arrays must be 4xHxW or HxWx4")
    packed = np.asarray(packed, dtype=np.float32)
    if normalize:
        return normalize_canonical_packed(
            packed,
            record.physics.black_level_raw,
            record.physics.white_level_raw,
        )
    return packed


def _load_lsc(root: Path, record: DatasetRecord, shape: Tuple[int, int]) -> np.ndarray:
    if not record.lsc_path:
        return np.ones((4, *shape), dtype=np.float32)
    arr = np.asarray(load_numpy_array((root / record.lsc_path).resolve()), dtype=np.float32)
    if arr.ndim == 2:
        arr = np.repeat(arr[None, ...], 4, axis=0)
    elif arr.ndim == 3 and arr.shape[-1] == 4:
        arr = np.moveaxis(arr, -1, 0)
    if arr.shape != (4, *shape):
        raise ValueError(f"LSC map must match canonical packed extent {(4, *shape)}, got {arr.shape}")
    if not np.isfinite(arr).all() or np.any(arr <= 0.0):
        raise ValueError("LSC gains must be finite and >0")
    return arr


@dataclass(frozen=True)
class LoadedRawRecord:
    sample_id: str
    kind: RecordKind
    clean: Optional[np.ndarray]
    noisy: Optional[np.ndarray]
    dark_raw: Optional[np.ndarray]
    remaining_lsc: Optional[np.ndarray]
    global_condition: np.ndarray
    record: DatasetRecord


class RawManifestDataset:
    """Deterministic loader for clean/paired/dark RAW records.

    This class performs no random synthetic corruption.  It establishes the
    exact provenance/canonical-pack boundary consumed by later synthesis and
    training layers.
    """

    def __init__(self, manifest: DatasetManifest, records: Sequence[DatasetRecord]):
        self.manifest = manifest
        self.records = tuple(records)
        if not self.records:
            raise ValueError("dataset selection is empty")

    @classmethod
    def for_split(
        cls,
        manifest: DatasetManifest,
        policy: SensorSplitPolicy,
        split: str,
        kinds: Optional[Sequence[RecordKind]] = None,
    ) -> "RawManifestDataset":
        return cls(manifest, records_for_split(manifest, policy, split, kinds))

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> LoadedRawRecord:
        record = self.records[index]
        root = self.manifest.root
        clean = noisy = dark = lsc = None
        if record.clean_path:
            clean = _canonical_record_array(root, record, record.clean_path, normalize=True)
        if record.noisy_path:
            noisy = _canonical_record_array(root, record, record.noisy_path, normalize=True)
        if record.dark_path:
            # Preserve signed/raw dark-frame structure.  Dark-frame synthesis
            # later recenters and scales it in normalized units explicitly.
            dark = _canonical_record_array(root, record, record.dark_path, normalize=False)
        shape = None
        for candidate in (clean, noisy, dark):
            if candidate is not None:
                shape = candidate.shape[1:]
                break
        if shape is None:
            raise AssertionError("validated record had no array path")
        if record.lsc_path:
            lsc = _load_lsc(root, record, shape)
        return LoadedRawRecord(
            sample_id=record.sample_id,
            kind=record.kind,
            clean=clean,
            noisy=noisy,
            dark_raw=dark,
            remaining_lsc=lsc,
            global_condition=physics_global_vector(record.physics),
            record=record,
        )


@dataclass(frozen=True)
class NoiseRegimeProbabilities:
    identity: float = 0.30
    moderate: float = 0.40
    heavy: float = 0.30

    def validate(self) -> None:
        values = np.asarray([self.identity, self.moderate, self.heavy], dtype=np.float64)
        if np.any(values < 0.0) or not np.isfinite(values).all() or abs(float(values.sum()) - 1.0) > 1.0e-8:
            raise ValueError("noise regime probabilities must be finite, non-negative, and sum to 1")

    def sample(self, rng: np.random.Generator) -> str:
        self.validate()
        return str(rng.choice(("identity", "moderate", "heavy"), p=(self.identity, self.moderate, self.heavy)))
