from __future__ import annotations

import hashlib
import json
import os
import platform
import random
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

import numpy as np
import torch


def sha256_file(path: str | Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def stable_json_sha256(payload: Mapping[str, Any]) -> str:
    data = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def git_commit(repo_root: str | Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(repo_root), "rev-parse", "HEAD"], stderr=subprocess.DEVNULL, text=True
        ).strip()
    except Exception:
        return "UNAVAILABLE"


def seed_everything(seed: int, deterministic: bool = True) -> None:
    os.environ["PYTHONHASHSEED"] = str(seed)
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    if deterministic:
        torch.use_deterministic_algorithms(True, warn_only=False)
        if torch.backends.cudnn.is_available():
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True


@dataclass(frozen=True)
class RunProvenance:
    schema_version: int
    git_commit: str
    dataset_manifest_sha256: str
    dataset_name: str
    dataset_version: str
    sensor_split: Mapping[str, Any]
    rng_seed: int
    deterministic_algorithms: bool
    architecture_config: Mapping[str, Any]
    loss_weights: Mapping[str, Any]
    optimizer: Mapping[str, Any]
    lr_schedule: Mapping[str, Any]
    augmentation_config: Mapping[str, Any]
    curriculum: Any
    teacher_hash: str
    student_hash: str
    export_tool_version: str
    environment: Mapping[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def environment_snapshot() -> Dict[str, Any]:
    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "numpy": np.__version__,
        "torch": torch.__version__,
        "cuda_available": torch.cuda.is_available(),
        "cuda_version": torch.version.cuda,
    }


def write_run_provenance(path: str | Path, provenance: RunProvenance) -> str:
    payload = provenance.to_dict()
    payload["provenance_sha256"] = stable_json_sha256(payload)
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return payload["provenance_sha256"]
