"""BnC-only reference package. Integrity is never evidence of reconstruction quality.

Envelope: <8sIIIIQ32s (64 bytes), UTF-8 canonical JSON, contiguous LE FP16 OIHW.
The full-file SHA must be supplied independently by the caller. No pickle loading.
Not accepted by Spectra's BNCNVK1 loader; no production backend consumes it yet.
"""
from pathlib import Path
import hashlib
import json
import re
import struct
import numpy as np
import torch
from .model import BncNeural, Config

HEADER = struct.Struct("<8sIIIIQ32s")
MAGIC = b"BNCDEM1\0"
MAGIC_V2 = b"BNCDEM2\0"
MAX_PACKAGE = 2 * 1024 * 1024
REQUIRED = ("modelVersion", "trainingCorpus", "splitProvenance", "trainingCodeSHA256",
            "lossDefinition", "exportDate", "trainingSteps", "purpose")


class PackageError(ValueError):
    pass


def export_reference(model, path, provenance):
    if not getattr(model.config, 'export_allowed', True):
        raise PackageError('BNC_NEURAL_RESEARCH_GRAPH_EXPORT_NOT_IMPLEMENTED')
    for field in REQUIRED:
        if field not in provenance or provenance[field] in (None, ""):
            raise PackageError("BNC_NEURAL_PROVENANCE_MISSING:" + field)
    if provenance["purpose"] not in ("contract_test_only", "research_candidate"):
        raise PackageError("no production export before quality and GPU validation")
    tensors, parts, offset = [], [], 0
    for name, tensor in model.state_dict().items():
        array = tensor.detach().cpu().numpy().astype("<f2")
        if not np.isfinite(array).all():
            raise PackageError("BNC_NEURAL_WEIGHTS_NONFINITE")
        data = array.tobytes()
        tensors.append(dict(name=name, shape=list(array.shape), offset=offset, bytes=len(data)))
        parts.append(data)
        offset += len(data)
    weights = b"".join(parts)
    manifest = dict(provenance)
    manifest.update(productName="BnC Neural", **model.config.description(),
                    modelSHA256=hashlib.sha256(weights).hexdigest(),
                    weightsFormat="fp16-le-contiguous-oihw-v1", inputDomain="finalized_bayer_fp32_0_4_divide_4",
                    outputDomain=getattr(model.config,"output_domain","eight_missing_components_divide_4"),
                    cfaPacking="spatial_reflect_to_RGGB_then_R_Gr_Gb_B",
                    measuredSampleMerge="original_fp32_bit_exact_no_half_roundtrip",
                    productReady=False, tensors=tensors)
    metadata = json.dumps(manifest, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    payload = metadata + weights
    schema=getattr(model.config,"package_schema",1)
    blob = HEADER.pack(MAGIC_V2 if schema==2 else MAGIC, schema, model.config.width, model.config.blocks, len(metadata), len(weights),
                       hashlib.sha256(payload).digest()) + payload
    path = Path(path)
    path.write_bytes(blob)
    package_sha = hashlib.sha256(blob).hexdigest()
    path.with_suffix(path.suffix + ".sha256").write_text(package_sha + "\n")
    return manifest, package_sha


def load_reference(path, expected_sha):
    path = Path(path)
    if not path.is_file():
        raise PackageError("BNC_NEURAL_MODEL_MISSING")
    if not isinstance(expected_sha, str) or not re.fullmatch("[0-9a-f]{64}", expected_sha):
        raise PackageError("BNC_NEURAL_EXPECTED_SHA_REQUIRED")
    if not HEADER.size <= path.stat().st_size <= MAX_PACKAGE:
        raise PackageError("BNC_NEURAL_PACKAGE_BOUNDS_INVALID")
    blob = path.read_bytes()
    if hashlib.sha256(blob).hexdigest() != expected_sha:
        raise PackageError("BNC_NEURAL_SHA_MISMATCH")
    magic, version, width, blocks, mbytes, wbytes, sha = HEADER.unpack_from(blob)
    if (magic,version) not in ((MAGIC,1),(MAGIC_V2,2)):
        raise PackageError("BNC_NEURAL_PACKAGE_IDENTITY_INVALID")
    if version==2:
        from .v2.model import Config as V2Config, BncNeuralV2
        config=V2Config(width)
        if blocks!=config.blocks:raise PackageError("BNC_NEURAL_V2_GRAPH_INVALID")
        model_factory=BncNeuralV2
    else:
        config=Config(width,blocks)
        model_factory=BncNeural
    payload = blob[HEADER.size:]
    if mbytes == 0 or mbytes > 1024 * 1024 or mbytes + wbytes != len(payload) or wbytes != config.parameter_count * 2:
        raise PackageError("BNC_NEURAL_PACKAGE_BOUNDS_INVALID")
    if hashlib.sha256(payload).digest() != sha:
        raise PackageError("BNC_NEURAL_PAYLOAD_SHA_MISMATCH")
    try:
        manifest = json.loads(payload[:mbytes])
    except (ValueError, UnicodeError) as e:
        raise PackageError("BNC_NEURAL_MANIFEST_INVALID") from e
    if not isinstance(manifest, dict):
        raise PackageError("BNC_NEURAL_MANIFEST_INVALID")
    weights = payload[mbytes:]
    required_values = dict(productName="BnC Neural", **config.description(),
                           weightsFormat="fp16-le-contiguous-oihw-v1",
                           inputDomain="finalized_bayer_fp32_0_4_divide_4",
                           outputDomain=getattr(config,"output_domain","eight_missing_components_divide_4"),
                           cfaPacking="spatial_reflect_to_RGGB_then_R_Gr_Gb_B",
                           measuredSampleMerge="original_fp32_bit_exact_no_half_roundtrip",
                           productReady=False, modelSHA256=hashlib.sha256(weights).hexdigest())
    if any(manifest.get(k) != v for k, v in required_values.items()) or any(k not in manifest for k in REQUIRED):
        raise PackageError("BNC_NEURAL_MANIFEST_CONTRACT_INVALID")
    model, state, offset = model_factory(config), {}, 0
    records = manifest.get("tensors", [])
    if len(records) != len(model.state_dict()):
        raise PackageError("BNC_NEURAL_TENSOR_TABLE_INVALID")
    for (name, tensor), record in zip(model.state_dict().items(), records):
        size = tensor.numel() * 2
        if record != dict(name=name, shape=list(tensor.shape), offset=offset, bytes=size):
            raise PackageError("BNC_NEURAL_TENSOR_TABLE_INVALID")
        a = np.frombuffer(weights, "<f2", count=tensor.numel(), offset=offset).astype(np.float32)
        if not np.isfinite(a).all():
            raise PackageError("BNC_NEURAL_WEIGHTS_NONFINITE")
        state[name] = torch.from_numpy(a.reshape(tuple(tensor.shape)))
        offset += size
    model.load_state_dict(state)
    model.eval()
    return model, manifest


def preflight(path, expected_sha):
    """Development preflight only. Current product continues its existing fallback."""
    try:
        load_reference(path, expected_sha)
        reason = "BNC_NEURAL_BACKEND_UNAVAILABLE"
    except (ValueError, OSError, TypeError, KeyError, struct.error):
        reason = "BNC_NEURAL_MODEL_MISSING" if not Path(path).is_file() else "BNC_NEURAL_PACKAGE_INVALID"
    return dict(requested="BNC_NEURAL", actual="MALVAR_2004", fallback=True,
                fallbackReason=reason, bncNeuralAvailable=False)
