from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from spectra_train.export_vulkan_package import export_vulkan_package, verify_vulkan_package

HERE = Path(__file__).resolve().parent
PHASE3 = HERE / "golden" / "student_v1_contract"


def test_vulkan_package_is_deterministic_and_schema_exact(tmp_path: Path):
    a = tmp_path / "a.vkmodel"
    b = tmp_path / "b.vkmodel"
    sa = export_vulkan_package(PHASE3 / "model_manifest.json", a)
    sb = export_vulkan_package(PHASE3 / "model_manifest.json", b)
    assert a.read_bytes() == b.read_bytes()
    assert sa["package_sha256"] == sb["package_sha256"]
    assert sa["input_channels"] == 14
    assert sa["global_condition_dim"] == 18
    assert sa["minimum_symmetric_halo_packed"] == 38
    assert sa["source_model_content_sha256"] == json.loads((PHASE3/"model_manifest.json").read_text())["model_content_sha256"]


def test_vulkan_package_detects_payload_corruption(tmp_path: Path):
    out = tmp_path / "model.vkmodel"
    export_vulkan_package(PHASE3 / "model_manifest.json", out)
    data = bytearray(out.read_bytes())
    data[-7] ^= 0x40
    out.write_bytes(data)
    with pytest.raises(ValueError, match="SHA-256"):
        verify_vulkan_package(out)


def test_vulkan_package_rejects_phase3_manifest_tamper(tmp_path: Path):
    copied = tmp_path / "phase3"
    copied.mkdir()
    for name in ("model_manifest.json", "student_weights.fp16.bin"):
        (copied/name).write_bytes((PHASE3/name).read_bytes())
    m = json.loads((copied/"model_manifest.json").read_text())
    m["conditioning_schema"]["device_identity_features"] = True
    (copied/"model_manifest.json").write_text(json.dumps(m, sort_keys=True))
    with pytest.raises(ValueError):
        export_vulkan_package(copied/"model_manifest.json", tmp_path/"bad.vkmodel")
