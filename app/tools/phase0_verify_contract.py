#!/usr/bin/env python3
"""Static Phase 0 contract checks for a BnCam project or extracted src directory."""
from __future__ import annotations

import argparse
from pathlib import Path


def find_src(root: Path) -> Path:
    candidates = [root / "app" / "src", root / "src", root]
    for candidate in candidates:
        if (candidate / "main/java/com/bncam/core/runners/MultiFrameRunner.kt").is_file():
            return candidate
    raise SystemExit("Cannot locate BnCam src/main below the supplied path.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path, default=Path("."))
    args = parser.parse_args()
    src = find_src(args.root.resolve())

    multi = (src / "main/java/com/bncam/core/runners/MultiFrameRunner.kt").read_text(encoding="utf-8")
    single = (src / "main/java/com/bncam/core/runners/SingleFrameRunner.kt").read_text(encoding="utf-8")
    tracker = (src / "main/java/com/bncam/core/debug/CapturePerformanceTracker.kt").read_text(encoding="utf-8")
    image_utils = (src / "main/java/com/bncam/core/engine/ImageUtils.kt").read_text(encoding="utf-8")

    checks = {
        "multi RAW render call count is one": multi.count("ImageUtils.renderJpegFromMasterFrameSafe(") == 1,
        "publication does not rerun RAW ISP": "ImageUtils.renderJpegFromMasterFrameSafe(" not in multi.split(
            "CaptureProcessingQueue.submit(context, reservation)", 1
        )[1],
        "publication reuses immutable JPEG snapshot": "val jpegBytesToUse = finalJpegBytesForPublication" in multi,
        "single RAW render call count is one": single.count("ImageUtils.renderJpegFromRaw16InputSafe(") == 1,
        "persistent JSONL report exists": "capture_performance.jsonl" in tracker,
        "monotonic timing is used": "SystemClock.elapsedRealtimeNanos" in tracker,
        "routine debug RAW CRC is disabled": "BuildConfig.DEBUG && rawJpegDebugDumpsEnabled" in image_utils,
        "misleading concurrent native claim removed": "Start Concurrent C++ processing" not in multi,
    }
    failed = False
    for name, passed in checks.items():
        print(f"{'PASS' if passed else 'FAIL'}  {name}")
        failed |= not passed
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
