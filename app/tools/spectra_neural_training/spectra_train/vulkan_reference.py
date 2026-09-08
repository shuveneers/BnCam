from __future__ import annotations

"""Deterministic Phase-4 reference for the Vulkan Student package.

This is not a production CPU fallback.  It exists only in offline tooling to
prove that the packed C4 FP16 package, tile/halo contract and Phase-3 golden
vector describe the same Student graph before device Vulkan validation.
"""

import hashlib
import json
import math
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Mapping

import numpy as np
import torch

from .export_vulkan_package import (
    HEADER_BYTES,
    PACKING_C4,
    PACKING_O4I4,
    PACKING_O4I4HW,
    PACKING_SCALAR,
    TENSOR_RECORD_BYTES,
    _HEADER,
    _RECORD,
    verify_vulkan_package,
)
from .model_student import PhysicsConditionedStudent, StudentConfig


@dataclass(frozen=True)
class VulkanReferencePackage:
    summary: Mapping[str, Any]
    state_dict: Mapping[str, torch.Tensor]
    config: StudentConfig
    package_sha256: str


def _unpack_tensor(chunk: bytes, packing: int, dims: tuple[int, ...]) -> np.ndarray:
    logical = tuple(int(v) for v in dims)
    raw = np.frombuffer(chunk, dtype=np.dtype("<f2"))
    if packing == PACKING_O4I4HW:
        o, i, kh, kw = logical
        op, ip = (o + 3) & ~3, (i + 3) & ~3
        packed = raw.reshape(op // 4, ip // 4, kh, kw, 4, 4)
        padded = packed.transpose(0, 4, 1, 5, 2, 3).reshape(op, ip, kh, kw)
        return padded[:o, :i].astype(np.float32, copy=True)
    if packing == PACKING_O4I4:
        o, i = logical
        op, ip = (o + 3) & ~3, (i + 3) & ~3
        packed = raw.reshape(op // 4, ip // 4, 4, 4)
        padded = packed.transpose(0, 2, 1, 3).reshape(op, ip)
        return padded[:o, :i].astype(np.float32, copy=True)
    if packing == PACKING_C4:
        n = int(math.prod(logical))
        return raw[:n].reshape(logical).astype(np.float32, copy=True)
    if packing == PACKING_SCALAR:
        if math.prod(logical) != 1:
            raise ValueError("scalar Vulkan tensor has non-scalar logical shape")
        return raw[:1].reshape(logical).astype(np.float32, copy=True)
    raise ValueError(f"unsupported Vulkan packing {packing}")


def load_vulkan_reference_package(path: str | Path) -> VulkanReferencePackage:
    path = Path(path).resolve()
    summary = verify_vulkan_package(path)
    data = path.read_bytes()
    fields = _HEADER.unpack_from(data, 0)
    flags = int(fields[4])
    tensor_count = int(fields[5])
    table_offset = int(fields[6])
    string_offset = int(fields[7])
    weights_offset = int(fields[8])

    state: Dict[str, torch.Tensor] = {}
    for index in range(tensor_count):
        rec = _RECORD.unpack_from(data, table_offset + index * TENSOR_RECORD_BYTES)
        name_offset, name_length, rank = int(rec[0]), int(rec[1]), int(rec[2])
        dims4 = tuple(int(v) for v in rec[3:7])
        packing = int(rec[7])
        weight_offset, weight_bytes = int(rec[12]), int(rec[13])
        name_start = string_offset + name_offset
        name = data[name_start:name_start + name_length].decode("utf-8")
        chunk = data[weights_offset + weight_offset:weights_offset + weight_offset + weight_bytes]
        arr = _unpack_tensor(chunk, packing, dims4[:rank])
        state[name] = torch.from_numpy(arr)

    config = StudentConfig(
        name="spectra_student_vulkan_reference_v1",
        variant="research",
        input_channels=int(summary["input_channels"]),
        global_condition_dim=int(summary["global_condition_dim"]),
        widths=tuple(int(v) for v in summary["widths"]),
        encoder_blocks=tuple(int(v) for v in summary["encoder_blocks"]),
        bottleneck_blocks=int(summary["bottleneck_blocks"]),
        decoder_blocks=tuple(int(v) for v in summary["decoder_blocks"]),
        residual_k_sigma=float(summary["residual_k_sigma"]),
        posterior_log_ratio_min=float(summary["posterior_log_ratio_clamp"][0]),
        posterior_log_ratio_max=float(summary["posterior_log_ratio_clamp"][1]),
        confidence_head=bool(flags & 1),
        global_lowres_context=False,
        fine_coarse_residual_head=False,
    )
    model = PhysicsConditionedStudent(config)
    expected = set(model.state_dict())
    if set(state) != expected:
        raise ValueError(
            f"Vulkan package tensor graph mismatch: missing={sorted(expected-set(state))}, "
            f"extra={sorted(set(state)-expected)}"
        )
    return VulkanReferencePackage(
        summary=summary,
        state_dict=state,
        config=config,
        package_sha256=hashlib.sha256(data).hexdigest(),
    )


def _model_from_package(package: VulkanReferencePackage) -> PhysicsConditionedStudent:
    model = PhysicsConditionedStudent(package.config)
    model.load_state_dict(dict(package.state_dict), strict=True)
    model.half().eval()
    return model


def _output_arrays(output: Any) -> Dict[str, np.ndarray]:
    def arr(t: torch.Tensor | None) -> np.ndarray | None:
        return None if t is None else t.detach().float().cpu().numpy().copy()
    result: Dict[str, np.ndarray] = {
        "clean_raw": arr(output.clean_raw),
        "residual": arr(output.residual),
        "sigma_normalized_residual": arr(output.sigma_normalized_residual),
        "posterior_variance": arr(output.posterior_variance),
        "posterior_log_variance_ratio": arr(output.posterior_log_variance_ratio),
    }  # type: ignore[dict-item]
    if output.confidence is not None:
        result["confidence"] = arr(output.confidence)  # type: ignore[assignment]
    return result


def run_vulkan_package_reference(
    package_path: str | Path,
    conditioning: np.ndarray,
    global_condition: np.ndarray,
) -> Dict[str, np.ndarray]:
    package = load_vulkan_reference_package(package_path)
    model = _model_from_package(package)
    x = torch.as_tensor(conditioning, dtype=torch.float16).unsqueeze(0) if conditioning.ndim == 3 else torch.as_tensor(conditioning, dtype=torch.float16)
    g = torch.as_tensor(global_condition, dtype=torch.float16).unsqueeze(0) if global_condition.ndim == 1 else torch.as_tensor(global_condition, dtype=torch.float16)
    previous_threads = torch.get_num_threads()
    previous_mkldnn = torch.backends.mkldnn.enabled
    try:
        torch.backends.mkldnn.enabled = False
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True, warn_only=False)
        with torch.inference_mode():
            return _output_arrays(model(x, g))
    finally:
        torch.set_num_threads(previous_threads)
        torch.backends.mkldnn.enabled = previous_mkldnn


def _tile_conditioning(conditioning: np.ndarray, input_x: int, input_y: int, tile_w: int, tile_h: int) -> np.ndarray:
    """Mirror neural_condition.comp border semantics for an already materialized 14ch condition.

    The original image is first padded on right/bottom to an 8-pixel phase using
    replicate semantics; samples outside that padded extent are zero.  This is
    exactly the combination of Student::_pad_to_multiple and convolution zero
    padding used by the full-frame reference.
    """
    if conditioning.ndim != 3 or conditioning.shape[0] != 14:
        raise ValueError("conditioning must be 14 x H x W")
    _, full_h, full_w = conditioning.shape
    padded_w = (full_w + 7) & ~7
    padded_h = (full_h + 7) & ~7
    out = np.zeros((14, tile_h, tile_w), dtype=np.float32)
    for ly in range(tile_h):
        gy = input_y + ly
        if gy < 0 or gy >= padded_h:
            continue
        sy = min(gy, full_h - 1)
        for lx in range(tile_w):
            gx = input_x + lx
            if gx < 0 or gx >= padded_w:
                continue
            sx = min(gx, full_w - 1)
            out[:, ly, lx] = conditioning[:, sy, sx]
    return out


def _run_tile_with_original_head_domain(
    model: PhysicsConditionedStudent,
    tile_conditioning: torch.Tensor,
    global_condition: torch.Tensor,
    *,
    input_x: int,
    input_y: int,
    full_width: int,
    full_height: int,
) -> Dict[str, np.ndarray]:
    """Mirror Phase-3 crop-before-head semantics inside a Vulkan tile.

    The Student decoder is allowed to use the replicate-padded 8-phase domain,
    but residual/posterior/confidence heads must see zero outside the original
    image domain because Phase-3 crops decoder features before those heads.
    """
    features, _ = model.decode_features(tile_conditioning, global_condition)
    _, _, h, w = features.shape
    xs = torch.arange(w, device=features.device) + int(input_x)
    ys = torch.arange(h, device=features.device) + int(input_y)
    domain = (ys[:, None] < int(full_height)) & (xs[None, :] < int(full_width))
    domain = domain.to(features.dtype)[None, None]
    head_features = features * domain

    raw = tile_conditioning[:, 0:4]
    sigma = torch.exp(tile_conditioning[:, 4:8]).clamp_min(1.0e-8)
    headroom = tile_conditioning[:, 13:14].clamp(0.0, 1.0)
    z = model.residual_head(head_features)
    k = model.config.residual_k_sigma
    z_bounded = k * torch.tanh(z / k)
    proposed = sigma * z_bounded * headroom
    clean = (raw - proposed).clamp(0.0, 1.0)
    residual = raw - clean
    q = model.posterior_head(head_features).clamp(
        model.config.posterior_log_ratio_min, model.config.posterior_log_ratio_max
    )
    post = sigma.square() * torch.exp(q)
    class TileOut: pass
    out = TileOut()
    out.clean_raw=clean;out.residual=residual;out.sigma_normalized_residual=residual/sigma
    out.posterior_variance=post;out.posterior_log_variance_ratio=q
    out.confidence=(torch.sigmoid(model.confidence_head(head_features))
                    if model.confidence_head is not None else None)
    return _output_arrays(out)

def run_tiled_vulkan_package_reference(
    package_path: str | Path,
    conditioning: np.ndarray,
    global_condition: np.ndarray,
) -> Dict[str, np.ndarray]:
    package = load_vulkan_reference_package(package_path)
    model = _model_from_package(package)
    if conditioning.ndim != 3 or conditioning.shape[0] != 14:
        raise ValueError("conditioning must be 14 x H x W")
    _, full_h, full_w = conditioning.shape
    inner = int(package.summary["recommended_inner_tile_packed"])
    minimum_halo = int(package.summary["minimum_symmetric_halo_packed"])
    halo = ((minimum_halo + 7) // 8) * 8
    padded_w = (full_w + 7) & ~7
    padded_h = (full_h + 7) & ~7
    nx = (padded_w + inner - 1) // inner
    ny = (padded_h + inner - 1) // inner
    assembled: Dict[str, np.ndarray] = {}
    g = torch.as_tensor(global_condition, dtype=torch.float16).reshape(1, -1)

    previous_threads = torch.get_num_threads()
    previous_mkldnn = torch.backends.mkldnn.enabled
    try:
        torch.backends.mkldnn.enabled = False
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True, warn_only=False)
        with torch.inference_mode():
            for ty in range(ny):
                for tx in range(nx):
                    center_x, center_y = tx * inner, ty * inner
                    center_w = min(inner, padded_w - center_x)
                    center_h = min(inner, padded_h - center_y)
                    input_x = max(0, center_x - halo)
                    input_y = max(0, center_y - halo)
                    end_x = min(padded_w, center_x + center_w + halo)
                    end_y = min(padded_h, center_y + center_h + halo)
                    valid_x = center_x - input_x
                    valid_y = center_y - input_y
                    tile = _tile_conditioning(
                        conditioning,
                        input_x,
                        input_y,
                        end_x - input_x,
                        end_y - input_y,
                    )
                    # All boundaries are 8-phase aligned, so no tile-specific
                    # padding can alter the downsample/upsample phase.
                    if tile.shape[-1] % 8 or tile.shape[-2] % 8:
                        raise AssertionError("Vulkan tile lost 8-pixel Student phase")
                    x = torch.as_tensor(tile, dtype=torch.float16).unsqueeze(0)
                    outputs = _run_tile_with_original_head_domain(
                        model, x, g, input_x=input_x, input_y=input_y,
                        full_width=full_w, full_height=full_h
                    )
                    valid_w = min(center_w, max(0, full_w - center_x))
                    valid_h = min(center_h, max(0, full_h - center_y))
                    if valid_w == 0 or valid_h == 0:
                        continue
                    for name, value in outputs.items():
                        if name not in assembled:
                            assembled[name] = np.zeros((1, value.shape[1], full_h, full_w), dtype=np.float32)
                        assembled[name][..., center_y:center_y+valid_h, center_x:center_x+valid_w] = \
                            value[..., valid_y:valid_y+valid_h, valid_x:valid_x+valid_w]
    finally:
        torch.set_num_threads(previous_threads)
        torch.backends.mkldnn.enabled = previous_mkldnn
    return assembled


def compare_outputs(reference: Mapping[str, np.ndarray], candidate: Mapping[str, np.ndarray]) -> Dict[str, Dict[str, float]]:
    metrics: Dict[str, Dict[str, float]] = {}
    for name in sorted(set(reference) & set(candidate)):
        a = np.asarray(reference[name], dtype=np.float64)
        b = np.asarray(candidate[name], dtype=np.float64)
        if a.shape != b.shape:
            raise ValueError(f"shape mismatch for {name}: {a.shape} != {b.shape}")
        d = np.abs(a - b)
        metrics[name] = {
            "max_abs": float(np.max(d)) if d.size else 0.0,
            "mean_abs": float(np.mean(d)) if d.size else 0.0,
            "rms": float(np.sqrt(np.mean(np.square(d)))) if d.size else 0.0,
        }
    return metrics
