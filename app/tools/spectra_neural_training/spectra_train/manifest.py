from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

from .contracts import CfaSpec, PhysicsMetadata, RecordKind, StorageLayout

DATASET_MANIFEST_SCHEMA_VERSION = 1


@dataclass(frozen=True)
class DatasetRecord:
    sample_id: str
    sensor_id: str
    kind: RecordKind
    storage_layout: StorageLayout
    cfa: CfaSpec
    physics: PhysicsMetadata
    source: str
    license: str
    calibration_quality: str
    clean_gt_method: str = ""
    clean_path: Optional[str] = None
    noisy_path: Optional[str] = None
    dark_path: Optional[str] = None
    lsc_path: Optional[str] = None
    array_key: Optional[str] = None
    notes: str = ""

    def validate(self) -> None:
        if not self.sample_id.strip() or not self.sensor_id.strip():
            raise ValueError("sample_id and sensor_id are required")
        self.cfa.validate()
        self.physics.validate()
        if not self.source.strip() or not self.license.strip():
            raise ValueError("source and license are required for provenance")
        if self.calibration_quality not in {"measured", "derived", "unknown"}:
            raise ValueError("calibration_quality must be measured/derived/unknown")
        if self.kind in {RecordKind.CLEAN, RecordKind.PAIRED} and not self.clean_gt_method.strip():
            raise ValueError("clean/paired records require clean_gt_method provenance")
        if self.kind == RecordKind.CLEAN and not self.clean_path:
            raise ValueError("clean record requires clean_path")
        if self.kind == RecordKind.PAIRED and (not self.clean_path or not self.noisy_path):
            raise ValueError("paired record requires clean_path and noisy_path")
        if self.kind == RecordKind.DARK and not self.dark_path:
            raise ValueError("dark record requires dark_path")
        # Sensor ID is intentionally governance-only.  It is not exposed in the
        # physics conditioning structures and must never be converted to a model feature.

    @classmethod
    def from_mapping(cls, m: Mapping[str, Any]) -> "DatasetRecord":
        cfa_m = m.get("cfa", {})
        out = cls(
            sample_id=str(m["sample_id"]),
            sensor_id=str(m["sensor_id"]),
            kind=RecordKind(m["kind"]),
            storage_layout=StorageLayout(m.get("storage_layout", "mosaic")),
            cfa=CfaSpec(
                arrangement=str(cfa_m["arrangement"]),
                offset_x=int(cfa_m.get("offset_x", 0)),
                offset_y=int(cfa_m.get("offset_y", 0)),
            ),
            physics=PhysicsMetadata.from_mapping(m["physics"]),
            source=str(m["source"]),
            license=str(m["license"]),
            calibration_quality=str(m.get("calibration_quality", "unknown")),
            clean_gt_method=str(m.get("clean_gt_method", "")),
            clean_path=m.get("clean_path"),
            noisy_path=m.get("noisy_path"),
            dark_path=m.get("dark_path"),
            lsc_path=m.get("lsc_path"),
            array_key=m.get("array_key"),
            notes=str(m.get("notes", "")),
        )
        out.validate()
        return out


@dataclass(frozen=True)
class DatasetManifest:
    path: Path
    dataset_name: str
    dataset_version: str
    records: Tuple[DatasetRecord, ...]
    sha256: str

    @property
    def root(self) -> Path:
        return self.path.parent


def load_manifest(path: str | Path) -> DatasetManifest:
    path = Path(path).resolve()
    raw = path.read_bytes()
    payload = json.loads(raw.decode("utf-8"))
    if int(payload.get("schema_version", -1)) != DATASET_MANIFEST_SCHEMA_VERSION:
        raise ValueError("unsupported dataset manifest schema_version")
    name = str(payload.get("dataset_name", "")).strip()
    version = str(payload.get("dataset_version", "")).strip()
    if not name or not version:
        raise ValueError("dataset_name and dataset_version are required")
    records = tuple(DatasetRecord.from_mapping(r) for r in payload.get("records", []))
    if not records:
        raise ValueError("manifest contains no records")
    ids = [r.sample_id for r in records]
    if len(ids) != len(set(ids)):
        raise ValueError("sample_id values must be unique")
    return DatasetManifest(path, name, version, records, hashlib.sha256(raw).hexdigest())


@dataclass(frozen=True)
class SensorSplitPolicy:
    train_sensors: Tuple[str, ...]
    validation_sensors: Tuple[str, ...]
    test_sensors: Tuple[str, ...]

    def validate(self, records: Sequence[DatasetRecord]) -> None:
        groups = [set(self.train_sensors), set(self.validation_sensors), set(self.test_sensors)]
        labels = ["train", "validation", "test"]
        for i in range(3):
            for j in range(i + 1, 3):
                overlap = groups[i] & groups[j]
                if overlap:
                    raise ValueError(f"sensor leakage between {labels[i]} and {labels[j]}: {sorted(overlap)}")
        all_declared = set().union(*groups)
        present = {r.sensor_id for r in records}
        missing = present - all_declared
        if missing:
            raise ValueError(f"sensors missing from split policy: {sorted(missing)}")
        if not groups[0] or not groups[1] or not groups[2]:
            raise ValueError("train/validation/test sensor groups must all be non-empty")

    def split_for(self, sensor_id: str) -> str:
        if sensor_id in self.train_sensors:
            return "train"
        if sensor_id in self.validation_sensors:
            return "validation"
        if sensor_id in self.test_sensors:
            return "test"
        raise KeyError(sensor_id)

    @classmethod
    def from_mapping(cls, m: Mapping[str, Any]) -> "SensorSplitPolicy":
        return cls(
            train_sensors=tuple(str(v) for v in m.get("train_sensors", [])),
            validation_sensors=tuple(str(v) for v in m.get("validation_sensors", [])),
            test_sensors=tuple(str(v) for v in m.get("test_sensors", [])),
        )


def records_for_split(
    manifest: DatasetManifest,
    policy: SensorSplitPolicy,
    split: str,
    kinds: Optional[Iterable[RecordKind]] = None,
) -> Tuple[DatasetRecord, ...]:
    policy.validate(manifest.records)
    allowed = None if kinds is None else set(kinds)
    return tuple(
        r for r in manifest.records
        if policy.split_for(r.sensor_id) == split and (allowed is None or r.kind in allowed)
    )
