#!/usr/bin/env python3
"""SF2 phase-7: collect on-device GPU parity and same-RAW five-stage evidence.

Runs on Windows/macOS/Linux with Python 3 and adb in PATH. No external Python
packages, root, image uploading, or production switches. Only debug app access.
Generated ZIP stays on the user's computer. Camera capture is opt-in (--capture).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.bncam"
CAPTURE_FILES = (
    "off.jpg", "off.txt", "on.jpg", "on.txt",
    "guided.jpg", "guided.txt", "combined.jpg", "combined.txt",
    "confidence.jpg", "confidence.txt", "off_repeat.jpg", "off_repeat.txt",
    "qualification.txt",
)
IMAGE_FILES = tuple(name for name in CAPTURE_FILES if name.endswith(".jpg"))


def classify_qualifier(report: str) -> dict:
    """Fail closed: absence of explicit pass is NOT success."""
    lines = report.splitlines()
    stages = [line for line in lines if line.startswith("stage=")]
    completed = any("testedStages=6;" in line and "allPassed=true" in line
                    for line in lines)
    failed = "allPassed=false" in report
    return {
        "gpu_cpu_parity_pass": completed and not failed and len(stages) == 6,
        "device_stage_records": len(stages),
        "reported_failure": failed,
        "build_sha": next((x.split("=", 1)[1] for x in lines
                           if x.startswith("buildSha=")), None),
    }


def valid_capture_dir(value: str) -> bool:
    return bool(re.fullmatch(r"[0-9]{10,18}", value))


def pick_newest_dir(listing: str) -> str | None:
    candidates = [line.strip().rstrip("/")
                  for line in listing.splitlines() if valid_capture_dir(line.strip().rstrip("/"))]
    return max(candidates, key=int) if candidates else None


def verify_capture(files: dict[str, bytes]) -> dict:
    missing = sorted(set(CAPTURE_FILES).difference(files))
    bad_jpegs = [p for p in IMAGE_FILES if p in files and
                 not (files[p].startswith(b"\xff\xd8") and files[p].endswith(b"\xff\xd9"))]
    notes = files.get("qualification.txt", b"").decode("utf-8", errors="replace")
    # The five filenames are NOT sufficient evidence that the requested
    # native stage really executed on the resident GPU. Inspect each render's
    # own native stats; never classify CPU fallback as a GPU-qualified photo.
    expected_telemetry = {
        "off.txt": ("OFF", False),
        "on.txt": ("LEGACY_REFERENCE", True),
        "guided.txt": ("GUIDED_V2_FINE_DEBUG", True),
        "combined.txt": ("GUIDED_V2_FINE_COARSE_DEBUG", True),
        "confidence.txt": ("GUIDED_V2_CONFIDENCE_DEBUG", True),
        "off_repeat.txt": ("OFF", False),
    }
    gpu_route_checks = {}
    for path, (stage, enabled) in expected_telemetry.items():
        stats = files.get(path, b"").decode("utf-8", errors="replace")
        gpu_route_checks[path] = (
            f";stage={stage}" in stats and
            (";gpuExecuted=true" in stats if enabled else
             ";gpuExecuted=false" in stats) and
            (";measurementStatus=GPU_MEASURED" in stats if enabled else True) and
            (";confidenceGateApplied=true" in stats if path=="confidence.txt" else True)
        )
    expected = {"off": 0, "legacy": 1, "fine": 2, "coarse": 4,
                "confidence": 5, "offRepeat": 0}
    observed = re.findall(r"selectedStage\.(\w+)=(\d+);expected=(\d+)", notes)
    matches = len(observed) == len(expected) and all(
        name in expected and int(actual) == expected[name] == int(requested)
        for name, actual, requested in observed
    ) and len({name for name, _, _ in observed}) == len(expected)
    return {
        "all_capture_files_present": not missing,
        "missing": missing,
        "jpeg_markers_valid": not bad_jpegs and all(p in files for p in IMAGE_FILES),
        "bad_jpeg_markers": bad_jpegs,
        "six_debug_stage_checks_pass": matches,
        "native_stage_and_gpu_route_checks_pass": all(gpu_route_checks.values()),
        "native_route_checks_by_stage": gpu_route_checks,
        "off_repeat_jpeg_byte_identical": "offRepeatJpegBytesIdentical=true" in notes,
        "off_repeat_byte_identity_is_not_pixel_proof": True,
        "master_crc_present": ("rawMasterCrc32=" in notes and
                               "rawMasterCrc32=UNAVAILABLE" not in notes),
    }


class Adb:
    def __init__(self, serial: str | None = None):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args: str, timeout: int = 45) -> bytes:
        result = subprocess.run(self.base + list(args), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
        if result.returncode:
            raise RuntimeError(f"adb {' '.join(args[:5])}: "
                               f"{result.stderr.decode(errors='replace').strip()}")
        return result.stdout

    def private_file(self, relative: str) -> bytes:
        # exec-out is binary-safe on Windows; PowerShell stdout redirection is not.
        return self.run("exec-out", "run-as", PACKAGE, "cat", f"files/{relative}")

    def broadcast(self, action: str, *options: str) -> None:
        response = self.run("shell", "am", "broadcast", "-a", action, *options)
        if b"Broadcast completed" not in response and b"Broadcasting" not in response:
            raise RuntimeError("Unexpected adb broadcast response: " +
                               response.decode(errors="replace"))

    def newest_folder(self) -> str | None:
        try:
            result = self.run("shell", "run-as", PACKAGE, "ls", "-1t",
                              "files/physical_luma_ab")
            return pick_newest_dir(result.decode(errors="replace"))
        except RuntimeError:
            return None


def wait_file(device: Adb, relative: str, max_seconds: int) -> bytes:
    deadline = time.monotonic() + max_seconds
    while time.monotonic() < deadline:
        try:
            data = device.private_file(relative)
            if data:
                return data
        except RuntimeError:
            pass
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for on-device files/{relative}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=None, help="adb device serial (optional)")
    parser.add_argument("--output", type=Path, default=Path("sf2_phase7_device_evidence.zip"))
    parser.add_argument("--capture", action="store_true",
                        help="Opt in to camera capture + six debug RAW renders; defaults to GPU parity only")
    parser.add_argument("--collect-latest", action="store_true",
                        help="Collect last completed same-RAW set without triggering a new capture")
    parser.add_argument("--timeout", type=int, default=180, help="Seconds to await device diagnostics")
    args = parser.parse_args()

    if args.timeout < 10 or args.timeout > 900:
        parser.error("--timeout must be in [10,900]")
    if args.capture and args.collect_latest:
        parser.error("Choose --capture OR --collect-latest")

    device = Adb(args.serial)
    device.run("get-state")
    device.run("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(2)

    # Remove stale report BEFORE broadcast, otherwise an old PASS could pass.
    device.run("shell", "run-as", PACKAGE, "rm", "-f",
               "files/sf2_qualification/report.txt")
    device.broadcast("com.bncam.VALIDATE_SF2")
    report = wait_file(device, "sf2_qualification/report.txt", args.timeout)
    body = report.decode("utf-8", errors="replace")
    parity = classify_qualifier(body)
    files = {"sf2_qualification/report.txt": report}
    print(f"GPU/CPU stage parity pass: {parity['gpu_cpu_parity_pass']} "
          f"({parity['device_stage_records']} stage entries)")

    capture_state = None
    if args.capture or args.collect_latest:
        old_folder = device.newest_folder()
        if args.capture:
            device.broadcast("com.bncam.SET_BENCHMARK_CONFIG",
                             "--ez", "physicalLumaAb", "true",
                             "--ei", "physicalLumaStage", "0")
            device.broadcast("com.bncam.TRIGGER_SF2_CAPTURE")
        deadline = time.monotonic() + args.timeout
        folder = None
        while time.monotonic() < deadline:
            newest = device.newest_folder()
            if newest and (args.collect_latest or newest != old_folder):
                try:
                    note = device.private_file(f"physical_luma_ab/{newest}/qualification.txt")
                    if note and "selectedStage.offRepeat=" in note.decode(errors="replace"):
                        folder = newest
                        break
                except RuntimeError:
                    pass
            time.sleep(2)
        if not folder:
            raise RuntimeError("No complete on-device six-render RAW set found in time")
        capture_files: dict[str, bytes] = {}
        for name in CAPTURE_FILES:
            try:
                capture_files[name] = device.private_file(f"physical_luma_ab/{folder}/{name}")
                files[f"physical_luma_ab/{folder}/{name}"] = capture_files[name]
            except RuntimeError:
                pass
        capture_state = verify_capture(capture_files)
        capture_state["folder"] = folder
        print("Six-render stage verification:",
              capture_state["six_debug_stage_checks_pass"])
        # Do not keep a capture diagnostics flag permanently enabled.
        if args.capture:
            device.broadcast("com.bncam.SET_BENCHMARK_CONFIG",
                             "--ez", "physicalLumaAb", "false",
                             "--ei", "physicalLumaStage", "0")

    manifest = {
        "schema": "sf2_phase7_device_evidence_v1",
        "collected_utc": datetime.now(timezone.utc).isoformat(),
        "package": PACKAGE,
        "native_synthetic_gpu_cpu_qualification": parity,
        "same_raw_qualification": capture_state,
        "claims": {
            "gpu_cpu_parity": "Valid only if report says allPassed=true for all six tested stage/proxy cases",
            "real_scene_quality": "NOT_ESTABLISHED_BY_PARITY_OR_JPEG_MARKERS",
            "phone_latency_thermal": "NOT_ESTABLISHED_BY_SYNTHETIC_FIXTURE",
            "off_jpeg_bytes": "Byte identity is informational; not equivalent to RGB pixel proof",
        }
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as bundle:
        for name, data in files.items():
            bundle.writestr(name, data)
        bundle.writestr("sf2_phase7_manifest.json",
                        json.dumps(manifest, indent=2, ensure_ascii=False))
    print(f"Evidence ZIP: {args.output.resolve()}")
    if not parity["gpu_cpu_parity_pass"]:
        print("GPU/CPU qualification NOT PASSED. Inspect the report.", file=sys.stderr)
        return 2
    if capture_state and not (capture_state["all_capture_files_present"] and
                              capture_state["jpeg_markers_valid"] and
                              capture_state["six_debug_stage_checks_pass"] and
                              capture_state["native_stage_and_gpu_route_checks_pass"]):
        print("Same-RAW qualification INCOMPLETE. Inspect ZIP.", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
