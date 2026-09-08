from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Dict

import numpy as np

from .reference_inference import read_golden_vector
from .vulkan_reference import (
    compare_outputs,
    load_vulkan_reference_package,
    run_tiled_vulkan_package_reference,
    run_vulkan_package_reference,
)

SCHEMA_VERSION = 1


def _array_sha(array: np.ndarray) -> str:
    a = np.ascontiguousarray(array)
    return hashlib.sha256(a.tobytes()).hexdigest()


def build_parity_contract(package_path: str | Path, golden_path: str | Path) -> Dict[str, Any]:
    package_path = Path(package_path).resolve()
    golden_path = Path(golden_path).resolve()
    golden_manifest, arrays = read_golden_vector(golden_path)
    package = load_vulkan_reference_package(package_path)
    if package.summary["source_model_content_sha256"] != golden_manifest["model_content_sha256"]:
        raise ValueError("Vulkan package and Phase-3 golden vector reference different Students")

    full = run_vulkan_package_reference(
        package_path, arrays["conditioning"], arrays["global_condition"]
    )
    tiled = run_tiled_vulkan_package_reference(
        package_path, arrays["conditioning"], arrays["global_condition"]
    )
    phase3 = {name.removeprefix("expected_"): value for name, value in arrays.items() if name.startswith("expected_")}
    package_vs_phase3 = compare_outputs(phase3, full)
    tiled_vs_full = compare_outputs(full, tiled)
    if any(v["max_abs"] != 0.0 for v in package_vs_phase3.values()):
        raise AssertionError("packed Vulkan package no longer exactly reproduces Phase-3 reference")
    if any(v["max_abs"] != 0.0 for v in tiled_vs_full.values()):
        raise AssertionError("valid-center tiled reference no longer exactly reproduces full-frame reference")

    return {
        "schema_version": SCHEMA_VERSION,
        "contract_name": "spectra_neural_phase4_vulkan_golden_parity_v1",
        "package_sha256": package.package_sha256,
        "source_model_content_sha256": package.summary["source_model_content_sha256"],
        "phase3_golden_model_content_sha256": golden_manifest["model_content_sha256"],
        "recommended_inner_tile_packed": package.summary["recommended_inner_tile_packed"],
        "manifest_minimum_halo_packed": package.summary["minimum_symmetric_halo_packed"],
        "runtime_aligned_halo_packed": ((int(package.summary["minimum_symmetric_halo_packed"]) + 7) // 8) * 8,
        "phase3_package_reference_exact": True,
        "valid_center_tiled_reference_exact": True,
        "package_vs_phase3": package_vs_phase3,
        "tiled_vs_full": tiled_vs_full,
        "reference_output_sha256": {name: _array_sha(value) for name, value in sorted(full.items())},
        "clipped_reference_cell_identity": bool(np.array_equal(full["clean_raw"][..., 0, 0], arrays["raw_normalized"][None, ..., 0, 0])),
        "gpu_validation_gate": {
            "status": "REQUIRED_ON_ANDROID_VULKAN_DEVICE",
            "reason": "offline environment has no Android NDK Vulkan headers/glslc/device",
            "required_comparison": "run the same golden vector through VulkanNeuralRawDenoiseBackend and compare dumped FP32 clean/posterior outputs against these reference hashes/arrays",
            "do_not_claim_device_parity_from_this_file": True,
        },
    }


def write_parity_contract(package_path: str | Path, golden_path: str | Path, output: str | Path) -> Dict[str, Any]:
    result = build_parity_contract(package_path, golden_path)
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--golden", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = write_parity_contract(args.package, args.golden, args.output)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
