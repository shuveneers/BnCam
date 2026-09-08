from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, Mapping, Tuple

import numpy as np
import torch

from .contracts import CANONICAL_CHANNELS, GLOBAL_CONDITIONING_FIELDS, SPATIAL_CONDITIONING_CHANNELS
from .model_student import PhysicsConditionedStudent, StudentConfig, theoretical_receptive_field
from .reproducibility import sha256_file
from .train_student import _student_config

MODEL_EXPORT_SCHEMA_VERSION = 1
WEIGHTS_FORMAT = "bncam-flat-fp16-le-v1"


def _canonical_json_bytes(payload: Mapping[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def _model_content_hash(core_manifest: Mapping[str, Any], weights: bytes) -> str:
    h = hashlib.sha256()
    h.update(_canonical_json_bytes(core_manifest))
    h.update(weights)
    return h.hexdigest()


def _student_from_checkpoint_payload(payload: Mapping[str, Any]) -> tuple[PhysicsConditionedStudent, StudentConfig]:
    if int(payload.get("schema_version", -1)) != 1:
        raise ValueError("unsupported Student checkpoint schema")
    if "student_config" not in payload or "student_state" not in payload:
        raise ValueError("checkpoint missing student_config/student_state")
    config = _student_config(payload["student_config"])
    model = PhysicsConditionedStudent(config)
    model.load_state_dict(payload["student_state"], strict=True)
    model.eval()
    return model, config


def _pack_state_dict_fp16(model: PhysicsConditionedStudent) -> tuple[bytes, list[Dict[str, Any]]]:
    chunks = []
    tensors = []
    offset = 0
    state = model.state_dict()
    for name in sorted(state):
        tensor = state[name].detach().cpu().contiguous()
        if not torch.is_floating_point(tensor):
            raise ValueError(f"non-floating tensor is not supported by FP16 v1 export: {name}")
        arr = tensor.numpy().astype(np.dtype("<f2"), copy=False)
        data = arr.tobytes(order="C")
        tensor_hash = hashlib.sha256(data).hexdigest()
        tensors.append({
            "name": name,
            "shape": list(tensor.shape),
            "dtype": "fp16",
            "byte_offset": offset,
            "byte_length": len(data),
            "sha256": tensor_hash,
        })
        chunks.append(data)
        offset += len(data)
    return b"".join(chunks), tensors


def export_student_checkpoint(
    checkpoint_path: str | Path,
    output_dir: str | Path,
    *,
    model_version: str = "1",
    recommended_inner_tile_packed: int = 320,
    release_status: str = "PHASE3_UNVALIDATED_WEIGHTS",
) -> Dict[str, Any]:
    checkpoint_path = Path(checkpoint_path).resolve()
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    if recommended_inner_tile_packed <= 0 or recommended_inner_tile_packed % 8:
        raise ValueError("recommended_inner_tile_packed must be a positive multiple of 8")

    checkpoint_sha = sha256_file(checkpoint_path)
    payload = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    model, config = _student_from_checkpoint_payload(payload)
    # Phase-4 baseline has one spatial input. Research ablations remain offline
    # until the backend contract explicitly grows to support them.
    if config.global_lowres_context:
        raise ValueError("global-lowres research Student is not eligible for production v1 export")

    weights, tensor_manifest = _pack_state_dict_fp16(model)
    weights_path = output_dir / "student_weights.fp16.bin"
    weights_path.write_bytes(weights)
    weights_sha = hashlib.sha256(weights).hexdigest()
    rf = theoretical_receptive_field(config)
    halo = (rf - 1) // 2

    core: Dict[str, Any] = {
        "schema_version": MODEL_EXPORT_SCHEMA_VERSION,
        "model_name": config.name,
        "model_version": str(model_version),
        "variant": config.variant,
        "release_status": str(release_status),
        "student_checkpoint_sha256": checkpoint_sha,
        "teacher_checkpoint_sha256": str(payload.get("teacher_checkpoint_sha256", "")),
        "run_provenance_sha256": str(payload.get("run_provenance_sha256", "")),
        "weights_file": weights_path.name,
        "weights_format": WEIGHTS_FORMAT,
        "weights_sha256": weights_sha,
        "weights_bytes": len(weights),
        "precision": "fp16",
        "parameter_count": model.parameter_count(),
        "supported_cfa_contract": {
            "name": "canonical_bayer_r_g1_g2_b_v1",
            "channels": list(CANONICAL_CHANNELS),
            "supported_sensor_bayer": ["RGGB", "GRBG", "GBRG", "BGGR"],
        },
        "normalization_schema": {
            "name": "raw_minus_black_over_white_minus_black_v1",
            "range": [0.0, 1.0],
            "clipped_highlight_reconstruction": False,
        },
        "conditioning_schema": {
            "schema_version": 1,
            "spatial_channels": list(SPATIAL_CONDITIONING_CHANNELS),
            "global_fields": list(GLOBAL_CONDITIONING_FIELDS),
            "device_identity_features": False,
        },
        "output_schema": {
            "residual": "4ch_sigma_normalized_k_tanh_then_sigma_scaled",
            "residual_k_sigma": config.residual_k_sigma,
            "posterior": "4ch_log_variance_ratio",
            "posterior_log_ratio_clamp": [config.posterior_log_ratio_min, config.posterior_log_ratio_max],
            "confidence": "1ch_sigmoid" if config.confidence_head else "absent",
            "fine_coarse_residual_head": config.fine_coarse_residual_head,
        },
        "architecture_config": asdict(config),
        "primitive_contract": dict(model.export_primitive_contract()),
        "recommended_inner_tile_packed": int(recommended_inner_tile_packed),
        "theoretical_receptive_field_packed": rf,
        "minimum_symmetric_halo_packed": halo,
        "backend_compatibility": ["desktop_reference_v1", "vulkan_phase4_fp16"],
        "tensor_manifest": tensor_manifest,
    }
    core["model_content_sha256"] = _model_content_hash(core, weights)
    manifest_path = output_dir / "model_manifest.json"
    manifest_path.write_text(json.dumps(core, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    verify_exported_student(manifest_path)
    return core


def verify_exported_student(manifest_path: str | Path) -> Dict[str, Any]:
    manifest_path = Path(manifest_path).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if int(manifest.get("schema_version", -1)) != MODEL_EXPORT_SCHEMA_VERSION:
        raise ValueError("unsupported model manifest schema")
    if manifest.get("precision") != "fp16" or manifest.get("weights_format") != WEIGHTS_FORMAT:
        raise ValueError("unsupported Student precision/weights format")
    weights_path = manifest_path.parent / str(manifest["weights_file"])
    weights = weights_path.read_bytes()
    if len(weights) != int(manifest["weights_bytes"]):
        raise ValueError("weights byte length mismatch")
    if hashlib.sha256(weights).hexdigest() != manifest["weights_sha256"]:
        raise ValueError("weights SHA-256 mismatch")

    expected_offset = 0
    parameter_count = 0
    for tensor in manifest["tensor_manifest"]:
        offset = int(tensor["byte_offset"])
        length = int(tensor["byte_length"])
        if offset != expected_offset or length <= 0 or length % 2:
            raise ValueError("invalid/non-contiguous tensor manifest")
        chunk = weights[offset:offset + length]
        if len(chunk) != length or hashlib.sha256(chunk).hexdigest() != tensor["sha256"]:
            raise ValueError(f"tensor hash mismatch: {tensor['name']}")
        shape = tuple(int(v) for v in tensor["shape"])
        numel = int(np.prod(shape, dtype=np.int64))
        if length != numel * 2:
            raise ValueError(f"FP16 byte length mismatch: {tensor['name']}")
        parameter_count += numel
        expected_offset += length
    if expected_offset != len(weights):
        raise ValueError("tensor manifest does not consume complete weights file")
    if parameter_count != int(manifest["parameter_count"]):
        raise ValueError("parameter count mismatch")

    stored_model_hash = manifest.pop("model_content_sha256")
    actual_model_hash = _model_content_hash(manifest, weights)
    manifest["model_content_sha256"] = stored_model_hash
    if actual_model_hash != stored_model_hash:
        raise ValueError("model content SHA-256 mismatch")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Export SPECTRA Neural Student to deterministic flat FP16 weights")
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--model-version", default="1")
    parser.add_argument("--inner-tile", type=int, default=320)
    parser.add_argument("--release-status", default="PHASE3_UNVALIDATED_WEIGHTS")
    args = parser.parse_args()
    manifest = export_student_checkpoint(
        args.checkpoint, args.output_dir,
        model_version=args.model_version,
        recommended_inner_tile_packed=args.inner_tile,
        release_status=args.release_status,
    )
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
