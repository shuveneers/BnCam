from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping, Tuple

import numpy as np

from .export_student import verify_exported_student

VKMODEL_MAGIC = b"BNCNVK1\0"
VKMODEL_SCHEMA_VERSION = 1
HEADER_BYTES = 256
TENSOR_RECORD_BYTES = 128
PACKING_O4I4HW = 1
PACKING_O4I4 = 2
PACKING_C4 = 3
PACKING_SCALAR = 4

_HEADER = struct.Struct("<8s4I6Q4I4I3I1I3I1I3f32s32s32s")
# name_offset, name_length, rank, dims[4], packing, logical_out, logical_in,
# kernel_h, kernel_w, weights_offset, weights_bytes, sha256, reserved.
_RECORD = struct.Struct("<IHH4I5I2Q32s36s")
assert _HEADER.size <= HEADER_BYTES
assert _RECORD.size == TENSOR_RECORD_BYTES


def _sha(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def _pad4(v: int) -> int:
    return (int(v) + 3) & ~3


def _pack_conv(arr: np.ndarray) -> tuple[bytes, int, int, int, int]:
    # PyTorch OIHW -> O4/I4/KH/KW/4x4. Each 4x4 block is output-lane major.
    if arr.ndim != 4:
        raise ValueError("conv tensor must be OIHW")
    o, i, kh, kw = map(int, arr.shape)
    op, ip = _pad4(o), _pad4(i)
    padded = np.zeros((op, ip, kh, kw), dtype=np.dtype("<f2"))
    padded[:o, :i] = arr.astype(np.dtype("<f2"), copy=False)
    out = np.empty((op // 4, ip // 4, kh, kw, 4, 4), dtype=np.dtype("<f2"))
    for og in range(op // 4):
        for ig in range(ip // 4):
            # OIHW sub-block -> KH,K W,O,I
            block = padded[og*4:(og+1)*4, ig*4:(ig+1)*4]
            out[og, ig] = block.transpose(2, 3, 0, 1)
    return out.tobytes(order="C"), o, i, kh, kw


def _pack_linear(arr: np.ndarray) -> tuple[bytes, int, int, int, int]:
    if arr.ndim != 2:
        raise ValueError("linear tensor must be OI")
    o, i = map(int, arr.shape)
    op, ip = _pad4(o), _pad4(i)
    padded = np.zeros((op, ip), dtype=np.dtype("<f2"))
    padded[:o, :i] = arr.astype(np.dtype("<f2"), copy=False)
    out = np.empty((op // 4, ip // 4, 4, 4), dtype=np.dtype("<f2"))
    for og in range(op // 4):
        for ig in range(ip // 4):
            out[og, ig] = padded[og*4:(og+1)*4, ig*4:(ig+1)*4]
    return out.tobytes(order="C"), o, i, 1, 1


def _pack_vector(arr: np.ndarray) -> tuple[bytes, int, int, int, int]:
    flat = arr.reshape(-1).astype(np.dtype("<f2"), copy=False)
    n = int(flat.size)
    padded = np.zeros((_pad4(n),), dtype=np.dtype("<f2"))
    padded[:n] = flat
    return padded.tobytes(order="C"), n, 1, 1, 1


def _read_tensor(weights: bytes, t: Mapping[str, Any]) -> np.ndarray:
    off, length = int(t["byte_offset"]), int(t["byte_length"])
    shape = tuple(int(v) for v in t["shape"])
    return np.frombuffer(weights[off:off+length], dtype=np.dtype("<f2")).reshape(shape).copy()


def _classify(name: str, arr: np.ndarray) -> tuple[int, bytes, int, int, int, int]:
    if name.endswith(".weight") and arr.ndim == 4:
        data, o, i, kh, kw = _pack_conv(arr)
        return PACKING_O4I4HW, data, o, i, kh, kw
    if name.endswith(".weight") and arr.ndim == 2:
        data, o, i, kh, kw = _pack_linear(arr)
        return PACKING_O4I4, data, o, i, kh, kw
    if arr.size == 1:
        data = arr.astype(np.dtype("<f2"), copy=False).tobytes(order="C")
        return PACKING_SCALAR, data, 1, 1, 1, 1
    data, o, i, kh, kw = _pack_vector(arr)
    return PACKING_C4, data, o, i, kh, kw


def export_vulkan_package(manifest_path: str | Path, output_path: str | Path) -> Dict[str, Any]:
    manifest_path = Path(manifest_path).resolve()
    output_path = Path(output_path).resolve()
    manifest = verify_exported_student(manifest_path)
    arch = manifest["architecture_config"]
    if arch.get("global_lowres_context") or arch.get("fine_coarse_residual_head"):
        raise ValueError("Phase-4 production package supports baseline v1 Student only")
    if manifest["precision"] != "fp16":
        raise ValueError("Phase-4 Vulkan package requires FP16 Student")
    if manifest["conditioning_schema"]["schema_version"] != 1:
        raise ValueError("conditioning schema mismatch")
    if manifest["conditioning_schema"].get("device_identity_features"):
        raise ValueError("device identity features are forbidden")

    source_weights = (manifest_path.parent / manifest["weights_file"]).read_bytes()
    names = bytearray()
    weight_blob = bytearray()
    records = []
    for t in manifest["tensor_manifest"]:
        name = str(t["name"])
        arr = _read_tensor(source_weights, t)
        packing, packed, logical_out, logical_in, kh, kw = _classify(name, arr)
        name_b = name.encode("utf-8")
        name_offset = len(names)
        names += name_b + b"\0"
        alignment_pad = (-len(weight_blob)) % 16
        if alignment_pad:
            weight_blob += b"\0" * alignment_pad
        weight_offset = len(weight_blob)
        weight_blob += packed
        dims = list(arr.shape)[:4] + [1] * (4 - arr.ndim)
        records.append({
            "name": name, "name_offset": name_offset, "name_length": len(name_b),
            "rank": arr.ndim, "dims": dims[:4], "packing": packing,
            "logical_out": logical_out, "logical_in": logical_in,
            "kh": kh, "kw": kw, "weight_offset": weight_offset,
            "weight_bytes": len(packed), "sha": _sha(packed),
        })

    table_offset = HEADER_BYTES
    string_offset = table_offset + len(records) * TENSOR_RECORD_BYTES
    weights_offset = (string_offset + len(names) + 15) & ~15
    string_padding = weights_offset - (string_offset + len(names))
    payload_records = bytearray()
    for r in records:
        payload_records += _RECORD.pack(
            r["name_offset"], r["name_length"], r["rank"], *r["dims"],
            r["packing"], r["logical_out"], r["logical_in"], r["kh"], r["kw"],
            r["weight_offset"], r["weight_bytes"], r["sha"], b"\0" * 36,
        )
    payload = bytes(payload_records) + bytes(names) + b"\0" * string_padding + bytes(weight_blob)
    payload_sha = _sha(payload)
    packed_weights_sha = _sha(bytes(weight_blob))
    src_model_sha = bytes.fromhex(manifest["model_content_sha256"])
    widths = [int(v) for v in arch["widths"]]
    enc = [int(v) for v in arch["encoder_blocks"]]
    dec = [int(v) for v in arch["decoder_blocks"]]
    total_bytes = HEADER_BYTES + len(payload)
    flags = (1 if arch.get("confidence_head") else 0)
    header_core = _HEADER.pack(
        VKMODEL_MAGIC, VKMODEL_SCHEMA_VERSION, HEADER_BYTES, TENSOR_RECORD_BYTES, flags,
        len(records), table_offset, string_offset, weights_offset, len(weight_blob), total_bytes,
        int(arch["input_channels"]), int(arch["global_condition_dim"]),
        int(manifest["recommended_inner_tile_packed"]), int(manifest["minimum_symmetric_halo_packed"]),
        *widths, *enc, int(arch["bottleneck_blocks"]), *dec,
        int(manifest["theoretical_receptive_field_packed"]),
        float(arch["residual_k_sigma"]), float(arch["posterior_log_ratio_min"]), float(arch["posterior_log_ratio_max"]),
        src_model_sha, packed_weights_sha, payload_sha,
    )
    header = header_core + b"\0" * (HEADER_BYTES - len(header_core))
    package = header + payload
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(package)
    summary = verify_vulkan_package(output_path)
    summary["source_manifest"] = str(manifest_path)
    return summary


def verify_vulkan_package(path: str | Path) -> Dict[str, Any]:
    path = Path(path).resolve()
    data = path.read_bytes()
    if len(data) < HEADER_BYTES:
        raise ValueError("truncated Vulkan model package")
    fields = _HEADER.unpack_from(data, 0)
    (magic, schema, header_bytes, record_bytes, flags, tensor_count, table_offset,
     string_offset, weights_offset, weights_bytes, total_bytes, input_ch, global_dim,
     inner_tile, halo, w0,w1,w2,w3,e0,e1,e2,bottleneck,d0,d1,d2,rf,k,qmin,qmax,
     source_model_sha, packed_weights_sha, payload_sha) = fields
    if magic != VKMODEL_MAGIC or schema != VKMODEL_SCHEMA_VERSION:
        raise ValueError("Vulkan model magic/schema mismatch")
    if header_bytes != HEADER_BYTES or record_bytes != TENSOR_RECORD_BYTES or total_bytes != len(data):
        raise ValueError("Vulkan model structural header mismatch")
    if input_ch != 14 or global_dim != 18:
        raise ValueError("Vulkan conditioning schema mismatch")
    if table_offset != HEADER_BYTES or string_offset != table_offset + tensor_count * record_bytes:
        raise ValueError("Vulkan tensor table offsets invalid")
    if not (string_offset <= weights_offset <= len(data)) or weights_offset + weights_bytes != len(data):
        raise ValueError("Vulkan weights bounds invalid")
    if _sha(data[HEADER_BYTES:]) != payload_sha:
        raise ValueError("Vulkan package payload SHA-256 mismatch")
    if _sha(data[weights_offset:]) != packed_weights_sha:
        raise ValueError("Vulkan packed weights SHA-256 mismatch")
    names_end = weights_offset
    seen = set()
    logical_params = 0
    for idx in range(tensor_count):
        off = table_offset + idx*record_bytes
        rec = _RECORD.unpack_from(data, off)
        name_off,name_len,rank,*rest = rec
        dims = rest[:4]
        packing,logical_out,logical_in,kh,kw = rest[4:9]
        weight_off,weight_len,tensor_sha = rest[9:12]
        if rank < 1 or rank > 4 or name_off + name_len >= names_end-string_offset+1:
            raise ValueError("invalid Vulkan tensor record")
        ns = string_offset + name_off
        name = data[ns:ns+name_len].decode("utf-8")
        if not name or name in seen or data[ns+name_len] != 0:
            raise ValueError("invalid/duplicate Vulkan tensor name")
        seen.add(name)
        ws = weights_offset + weight_off
        chunk = data[ws:ws+weight_len]
        if weight_off % 16:
            raise ValueError(f"Vulkan tensor offset is not 16-byte aligned: {name}")
        if len(chunk) != weight_len or _sha(chunk) != tensor_sha:
            raise ValueError(f"Vulkan tensor hash mismatch: {name}")
        logical_params += math.prod(int(v) for v in dims[:rank])
    return {
        "schema_version": schema, "tensor_count": tensor_count, "flags": flags,
        "input_channels": input_ch, "global_condition_dim": global_dim,
        "widths": [w0,w1,w2,w3], "encoder_blocks": [e0,e1,e2],
        "bottleneck_blocks": bottleneck, "decoder_blocks": [d0,d1,d2],
        "recommended_inner_tile_packed": inner_tile, "minimum_symmetric_halo_packed": halo,
        "theoretical_receptive_field_packed": rf, "residual_k_sigma": k,
        "posterior_log_ratio_clamp": [qmin,qmax], "logical_parameter_count": logical_params,
        "source_model_content_sha256": source_model_sha.hex(),
        "packed_weights_sha256": packed_weights_sha.hex(), "payload_sha256": payload_sha.hex(),
        "tensor_alignment_bytes": 16,
        "package_sha256": hashlib.sha256(data).hexdigest(), "package_bytes": len(data),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description="Export Phase-3 Student into deterministic Vulkan C4 package")
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()
    print(json.dumps(export_vulkan_package(args.manifest, args.output), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
