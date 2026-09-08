from __future__ import annotations

import argparse
import hashlib
import io
import json
import zipfile
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

import numpy as np
import torch

from .contracts import GLOBAL_CONDITIONING_FIELDS, SPATIAL_CONDITIONING_CHANNELS
from .export_student import verify_exported_student
from .model_student import PhysicsConditionedStudent
from .train_student import _student_config

GOLDEN_VECTOR_SCHEMA_VERSION = 1
_FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def _load_flat_state(manifest_path: Path, manifest: Mapping[str, Any]) -> Dict[str, torch.Tensor]:
    weights = (manifest_path.parent / str(manifest["weights_file"])).read_bytes()
    state: Dict[str, torch.Tensor] = {}
    for item in manifest["tensor_manifest"]:
        offset = int(item["byte_offset"])
        length = int(item["byte_length"])
        shape = tuple(int(v) for v in item["shape"])
        arr = np.frombuffer(weights[offset:offset + length], dtype=np.dtype("<f2")).reshape(shape).astype(np.float32, copy=True)
        state[str(item["name"])] = torch.from_numpy(arr)
    return state


def load_exported_student(manifest_path: str | Path) -> tuple[PhysicsConditionedStudent, Dict[str, Any]]:
    manifest_path = Path(manifest_path).resolve()
    manifest = verify_exported_student(manifest_path)
    config = _student_config(manifest["architecture_config"])
    model = PhysicsConditionedStudent(config)
    state = _load_flat_state(manifest_path, manifest)
    expected = set(model.state_dict())
    if set(state) != expected:
        missing = sorted(expected - set(state))
        extra = sorted(set(state) - expected)
        raise ValueError(f"export tensor names do not match Student graph: missing={missing}, extra={extra}")
    model.load_state_dict(state, strict=True)
    if manifest["precision"] == "fp16":
        model.half()
    model.eval()
    return model, manifest


def run_reference_inference(
    manifest_path: str | Path,
    conditioning: np.ndarray | torch.Tensor,
    global_condition: np.ndarray | torch.Tensor,
) -> Dict[str, np.ndarray]:
    model, manifest = load_exported_student(manifest_path)
    dtype = torch.float16 if manifest["precision"] == "fp16" else torch.float32
    cond = torch.as_tensor(conditioning, dtype=dtype, device="cpu")
    glob = torch.as_tensor(global_condition, dtype=dtype, device="cpu")
    if cond.ndim == 3:
        cond = cond.unsqueeze(0)
    if glob.ndim == 1:
        glob = glob.unsqueeze(0)
    if cond.ndim != 4 or cond.shape[1] != len(SPATIAL_CONDITIONING_CHANNELS):
        raise ValueError("reference conditioning must be N x 14 x H x W")
    if glob.shape != (cond.shape[0], len(GLOBAL_CONDITIONING_FIELDS)):
        raise ValueError("reference global conditioning shape mismatch")
    if not torch.isfinite(cond).all() or not torch.isfinite(glob).all():
        raise ValueError("reference inputs must be finite")

    previous_threads = torch.get_num_threads()
    previous_mkldnn = torch.backends.mkldnn.enabled
    try:
        # oneDNN FP16 convolution kernels can differ by one half-precision ULP
        # across host/PyTorch revisions. The frozen golden contract is the plain
        # deterministic CPU graph, so disable oneDNN for reference generation.
        torch.backends.mkldnn.enabled = False
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True, warn_only=False)
        with torch.inference_mode():
            output = model(cond, glob)
    finally:
        torch.set_num_threads(previous_threads)
        torch.backends.mkldnn.enabled = previous_mkldnn

    def array(x: Optional[torch.Tensor]) -> Optional[np.ndarray]:
        if x is None:
            return None
        return x.detach().float().cpu().numpy().copy()

    result: Dict[str, np.ndarray] = {
        "clean_raw": array(output.clean_raw),  # type: ignore[dict-item]
        "residual": array(output.residual),  # type: ignore[dict-item]
        "sigma_normalized_residual": array(output.sigma_normalized_residual),  # type: ignore[dict-item]
        "posterior_variance": array(output.posterior_variance),  # type: ignore[dict-item]
        "posterior_log_variance_ratio": array(output.posterior_log_variance_ratio),  # type: ignore[dict-item]
    }
    confidence = array(output.confidence)
    if confidence is not None:
        result["confidence"] = confidence
    return result


def _npy_bytes(array: np.ndarray) -> bytes:
    buffer = io.BytesIO()
    np.lib.format.write_array(buffer, np.asarray(array), allow_pickle=False)
    return buffer.getvalue()


def _write_deterministic_zip(path: Path, entries: Mapping[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=_FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, entries[name])


def build_physics_golden_inputs(height: int = 24, width: int = 32, seed: int = 20260908) -> Dict[str, np.ndarray]:
    if height <= 0 or width <= 0:
        raise ValueError("golden dimensions must be positive")
    rng = np.random.default_rng(seed)
    raw = rng.uniform(0.04, 0.82, size=(4, height, width)).astype(np.float32)
    # Explicit highlight/clipping safety cells plus a dark/high-SNR region.
    raw[:, 0, 0] = 1.0
    raw[:, -1, -1] = 0.02
    shot_s = np.asarray([0.0020, 0.0019, 0.0019, 0.0022], dtype=np.float32)
    read_o = np.asarray([8.0e-5, 7.0e-5, 7.0e-5, 9.0e-5], dtype=np.float32)
    variance = shot_s[:, None, None] * raw + read_o[:, None, None]
    sigma = np.sqrt(np.maximum(variance, 0.0)).astype(np.float32)

    yy, xx = np.meshgrid(np.linspace(-1.0, 1.0, height), np.linspace(-1.0, 1.0, width), indexing="ij")
    radius = np.clip((xx * xx + yy * yy) / 2.0, 0.0, 1.0)
    base_lsc = 1.0 + 0.55 * radius
    lsc = np.stack((base_lsc * 1.02, base_lsc, base_lsc, base_lsc * 1.04)).astype(np.float32)
    metadata_trust = np.float32(0.82)
    headroom_span = np.float32(0.08)
    headroom = np.min(np.clip((1.0 - raw) / headroom_span, 0.0, 1.0), axis=0, keepdims=True).astype(np.float32)
    trust = np.full((1, height, width), metadata_trust, dtype=np.float32)
    conditioning = np.concatenate((raw, np.log(np.maximum(sigma, 1.0e-8)), lsc, trust, headroom), axis=0).astype(np.float32)

    global_condition = np.asarray([
        np.log(1.0 / 60.0), np.log(2.0), np.log(1.0), 10.0 / 32.0,
        0.90, 0.86, 0.84,
        0.15, 0.08, 0.12, 0.06, 0.05, 0.10, 0.11, 0.03, 0.04, 0.01, 0.88,
    ], dtype=np.float32)
    if global_condition.shape != (len(GLOBAL_CONDITIONING_FIELDS),):
        raise AssertionError("global golden conditioning schema drift")

    return {
        "conditioning": conditioning,
        "global_condition": global_condition,
        "raw_normalized": raw,
        "shot_s": shot_s,
        "read_o": read_o,
        "remaining_lsc": lsc,
        "metadata_trust": np.asarray([metadata_trust], dtype=np.float32),
        "headroom": headroom,
        "black_level_raw": np.asarray([64.0, 64.0, 64.0, 64.0], dtype=np.float32),
        "white_level_raw": np.asarray([1023.0, 1023.0, 1023.0, 1023.0], dtype=np.float32),
        # Full-authority backend fixture. Detailed visible control projection is
        # Phase-5 integration work, not silently approximated here.
        "user_controls": np.asarray([1.0, 1.0, 1.0, 1.0, 0.0, 1.0, 1.0], dtype=np.float32),
    }


def generate_golden_vector(
    manifest_path: str | Path,
    output_zip: str | Path,
    *,
    height: int = 24,
    width: int = 32,
    seed: int = 20260908,
) -> Dict[str, Any]:
    manifest_path = Path(manifest_path).resolve()
    output_zip = Path(output_zip).resolve()
    inputs = build_physics_golden_inputs(height, width, seed)
    expected = run_reference_inference(manifest_path, inputs["conditioning"], inputs["global_condition"])
    manifest = verify_exported_student(manifest_path)
    vector_manifest: Dict[str, Any] = {
        "schema_version": GOLDEN_VECTOR_SCHEMA_VERSION,
        "vector_name": "spectra_student_backend_golden_v1",
        "model_content_sha256": manifest["model_content_sha256"],
        "model_name": manifest["model_name"],
        "model_version": manifest["model_version"],
        "precision": manifest["precision"],
        "packed_shape": [4, height, width],
        "conditioning_schema_version": manifest["conditioning_schema"]["schema_version"],
        "spatial_conditioning_channels": manifest["conditioning_schema"]["spatial_channels"],
        "global_conditioning_fields": manifest["conditioning_schema"]["global_fields"],
        "user_control_fields": [
            "enabled", "noise_reduction", "luma_noise", "chroma_noise",
            "detail_protection", "low_frequency_cleanup", "adaptive_response",
        ],
        "control_semantics": "full-authority backend fixture; Phase-5 visible-control projection not approximated",
        "reference": "desktop_cpu_fp16_weights_and_activations_v1",
        "comparison_guidance": {
            "exact_repeatability_reference": True,
            "backend_parity": "Phase 4 must derive and justify FP16 parity tolerance from kernel evidence; Phase 3 does not guess one.",
        },
    }
    array_entries: Dict[str, bytes] = {}
    for name, value in {**inputs, **{f"expected_{k}": v for k, v in expected.items()}}.items():
        array_entries[f"arrays/{name}.npy"] = _npy_bytes(np.asarray(value))
    vector_manifest["array_sha256"] = {
        Path(name).stem: hashlib.sha256(data).hexdigest() for name, data in sorted(array_entries.items())
    }
    entries: Dict[str, bytes] = dict(array_entries)
    entries["golden_manifest.json"] = (json.dumps(vector_manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    _write_deterministic_zip(output_zip, entries)
    return vector_manifest


def read_golden_vector(path: str | Path) -> tuple[Dict[str, Any], Dict[str, np.ndarray]]:
    path = Path(path).resolve()
    arrays: Dict[str, np.ndarray] = {}
    with zipfile.ZipFile(path, "r") as zf:
        manifest = json.loads(zf.read("golden_manifest.json").decode("utf-8"))
        if int(manifest.get("schema_version", -1)) != GOLDEN_VECTOR_SCHEMA_VERSION:
            raise ValueError("unsupported golden vector schema")
        expected_hashes = dict(manifest.get("array_sha256", {}))
        seen = set()
        for name in zf.namelist():
            if not name.startswith("arrays/") or not name.endswith(".npy"):
                continue
            stem = Path(name).stem
            blob = zf.read(name)
            if stem not in expected_hashes or hashlib.sha256(blob).hexdigest() != expected_hashes[stem]:
                raise ValueError(f"golden array integrity failure: {stem}")
            arrays[stem] = np.load(io.BytesIO(blob), allow_pickle=False)
            seen.add(stem)
        if seen != set(expected_hashes):
            raise ValueError("golden vector array manifest/file set mismatch")
    return manifest, arrays


def main() -> None:
    parser = argparse.ArgumentParser(description="Deterministic desktop/reference inference and golden-vector generator")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--golden-output")
    parser.add_argument("--height", type=int, default=24)
    parser.add_argument("--width", type=int, default=32)
    parser.add_argument("--seed", type=int, default=20260908)
    args = parser.parse_args()
    if args.golden_output:
        print(json.dumps(generate_golden_vector(args.manifest, args.golden_output, height=args.height, width=args.width, seed=args.seed), indent=2, sort_keys=True))
    else:
        raise SystemExit("--golden-output is required by the CLI")


if __name__ == "__main__":
    main()
