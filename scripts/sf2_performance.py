"""Capture one immutable RAW master and measure the three SF2 Vulkan paths via ADB."""

import argparse
import json
import math
import statistics
import subprocess
import time
import uuid
from pathlib import Path


MODES = ("OFF", "ORIGINAL", "OPTIMIZED")
TIMINGS = (
    "vulkanDemosaicKernelMs",
    "fusedColourLumaGpuMs",
    "vulkanAwbCcmSynchronizationMs",
    "jpegRenderMs",
)


def summarize(report, camera, raw_format, runs):
    if not report.get("complete"):
        raise ValueError(report.get("error", "Incomplete device replay"))
    if report["cameraId"] != camera:
        raise ValueError(f"Expected camera {camera}, got {report['cameraId']}")
    if not report["rawMasterCrc32"] or report["width"] <= 0 or report["height"] <= 0:
        raise ValueError("RAW identity or resolution missing")
    samples = [sample for sample in report["samples"] if not sample["warmup"]]
    if len(samples) != runs * len(MODES) or any(sample["source"] != raw_format for sample in samples):
        raise ValueError(f"Expected {runs} measured {raw_format} cycles")

    mode_summary = {}
    for mode in MODES:
        rows = [sample for sample in samples if sample["mode"] == mode]
        if len(set(row["cycle"] for row in rows)) != runs or len(rows) != runs:
            raise ValueError(f"Missing or duplicate measured rows for {mode}")
        if any(row["thermalStatus"] >= 3 for row in rows):
            raise ValueError(f"Severe thermal throttling during {mode}")
        result = {}
        for field in TIMINGS:
            values = sorted(row[field] for row in rows)
            if any(not math.isfinite(value) or value <= 0 for value in values):
                raise ValueError(f"Invalid {field} data for {mode}")
            result[field] = {"median": statistics.median(values), "p90": values[math.ceil(0.9 * runs) - 1]}
        result["thermalStatuses"] = sorted(set(row["thermalStatus"] for row in rows))
        result["residentColorBytes"] = sorted(set(int(row["vulkanAwbCcmPersistentResidentBytes"]) for row in rows))
        result["extraFullFrameReadbacks"] = sorted(set(int(row["extraFullFrameReadbacks"]) for row in rows))
        mode_summary[mode] = result

    off, original, optimized = (
        mode_summary[mode]["fusedColourLumaGpuMs"]["median"] for mode in MODES
    )
    return {
        "modes": mode_summary,
        "originalOverheadMs": original - off,
        "originalOverheadPercent": 100 * (original - off) / off,
        "optimizedOverheadMs": optimized - off,
        "optimizedOverheadPercent": 100 * (optimized - off) / off,
        "fusedGpuGainMs": original - optimized,
        "fusedGpuGainPercent": 100 * (original - optimized) / original,
        "overheadReductionPercent": 100 * (original - optimized) / (original - off) if original > off else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.bncam")
    parser.add_argument("--camera-id", required=True, help="Select this physical camera in BnCam before running")
    parser.add_argument("--format", choices=("RAW10", "RAW_SENSOR"), required=True)
    parser.add_argument("--runs", type=int, choices=range(10, 21), default=10, metavar="10..20")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*params, check=True):
        return subprocess.run(command + list(params), check=check, capture_output=True, timeout=40)

    def thermal_snapshot(name):
        (args.output / f"thermal-{name}.txt").write_bytes(adb("shell", "dumpsys", "thermalservice").stdout)

    def private_file(name, check=True):
        return adb("exec-out", "run-as", args.package, "cat", f"files/sf2_performance/{request_id}/{name}", check=check)

    request_id = uuid.uuid4().hex
    adb("shell", "am", "start", "-W", "-n", f"{args.package}/com.bncam.MainActivity")
    adb("shell", "am", "broadcast", "-a", "com.bncam.SET_BENCHMARK_CONFIG", "-p", args.package,
        "--es", "format", args.format, "--es", "mode", "SINGLE_FRAME_ZSL",
        "--ez", "spectra", "false", "--ez", "physicalLumaAb", "false")
    time.sleep(5)
    thermal_snapshot("before")
    adb("shell", "am", "broadcast", "-a", "com.bncam.TRIGGER_SF2_CAPTURE", "-p", args.package,
        "--es", "performanceId", request_id, "--ei", "runs", str(args.runs))
    deadline = time.monotonic() + 420
    while time.monotonic() < deadline:
        result = private_file("results.json", check=False)
        if result.returncode == 0 and result.stdout.startswith(b"{"):
            (args.output / "results.json").write_bytes(result.stdout)
            break
        time.sleep(2)
    else:
        raise RuntimeError(f"Capture or replay timed out (request {request_id})")
    thermal_snapshot("after")
    report = json.loads(result.stdout)
    if report.get("id") != request_id:
        raise ValueError("Device returned a different benchmark request")
    summary = summarize(report, args.camera_id, args.format, args.runs)
    for name in ("off.jpg", "original.jpg", "optimized.jpg"):
        data = private_file(name).stdout
        if not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
            raise ValueError(f"Incomplete JPEG {name}")
        (args.output / name).write_bytes(data)
    match = (args.output / "original.jpg").read_bytes() == (args.output / "optimized.jpg").read_bytes()
    if match != report["balancedJpegBytesIdentical"]:
        raise ValueError("Device and collected JPEG identity checks disagree")
    summary["balancedJpegBytesIdentical"] = match
    linear = report["linearRgbComparison"]
    if linear["pixels"] != report["width"] * report["height"]:
        raise ValueError("Linear RGB comparison did not cover the full frame")
    summary["linearRgbComparison"] = linear
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
