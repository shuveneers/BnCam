"""Explicitly governed known-RGB sources, group-disjoint splits, linear augmentation."""
from pathlib import Path
import hashlib
import json
import numpy as np
from PIL import Image


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_manifest(path):
    path = Path(path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "bnc-rgb-corpus-v1":
        raise ValueError("corpus schema missing")
    records = manifest["images"]
    groups, hashes = {}, set()
    for record in records:
        for key in ("path", "sha256", "source", "license", "source_group", "split", "encoding", "ground_truth"):
            if not record.get(key):
                raise ValueError(f"corpus provenance missing: {key}")
        if record["split"] not in ("train", "validation", "test"):
            raise ValueError("invalid split")
        if record["ground_truth"] != "known_full_rgb" or record["encoding"] not in ("srgb", "linear_rgb"):
            raise ValueError("no demosaiced RAW pseudo-ground-truth or unknown transfer function")
        group = record["source_group"]
        if groups.setdefault(group, record["split"]) != record["split"]:
            raise ValueError("source-image leakage between splits")
        f = (path.parent / record["path"]).resolve()
        if digest(f) != record["sha256"] or record["sha256"] in hashes:
            raise ValueError("corpus hash mismatch or duplicate image")
        hashes.add(record["sha256"])
        record = dict(record, resolved_path=str(f))
        yield record


def read_linear(record):
    path = record["resolved_path"]
    if record["encoding"] == "linear_rgb":
        v = np.load(path, allow_pickle=False).astype(np.float32)
    else:
        with Image.open(path) as image:
            if image.mode != "RGB" or image.info.get("icc_profile"):
                raise ValueError("explicit RGB sRGB encoding required; convert tagged ICC sources first")
            v = np.asarray(image, np.float32) / 255
        v = np.where(v <= .04045, v / 12.92, ((v + .055) / 1.055) ** 2.4)
    if v.ndim != 3 or v.shape[-1] != 3 or min(v.shape[:2]) < 32 or not np.isfinite(v).all() or v.min() < 0 or v.max() > 4:
        raise ValueError("invalid known RGB domain")
    return v


def augment_camera(rgb, rng):
    """A bounded positive invertible basis; no clipping, tone curve or auto gain.

    Off-diagonal row mixing U[0,.08], diagonal row sum 1; independent channel
    gains log-uniform [.5,2], fixed sampled exposure log-uniform [.25,2].
    Reject out-of-domain draws rather than adapting gain to image brightness.
    This family approximates broad sensor sensitivities; it is not calibration.
    """
    for _ in range(128):
        matrix = rng.uniform(0, .08, (3, 3))
        np.fill_diagonal(matrix, 0)
        np.fill_diagonal(matrix, 1 - matrix.sum(1))
        matrix = np.diag(np.exp(rng.uniform(np.log(.5), np.log(2), 3))) @ matrix
        matrix *= np.exp(rng.uniform(np.log(.25), np.log(2)))
        transformed = rgb @ matrix.T
        if transformed.min() >= 0 and transformed.max() <= 4:
            return transformed.astype(np.float32), matrix.tolist()
    raise ValueError("camera-space augmentation cannot fit fixed [0,4] domain")
