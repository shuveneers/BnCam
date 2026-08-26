#!/usr/bin/env python3
"""Verify BnCam Phase 1 device reports without adb logcat.

Inputs are the app-written capture_performance.jsonl and capture_runtime_trace.txt files.
The script fails closed: missing routes or missing ownership metrics are reported as failures.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def as_int(value: Any, default: int = 0) -> int:
    try:
        if isinstance(value, bool):
            return int(value)
        return int(value)
    except (TypeError, ValueError):
        return default


def as_bool(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        low = value.strip().lower()
        if low in {"true", "1", "yes"}:
            return True
        if low in {"false", "0", "no"}:
            return False
    return None


def percentile(values: list[float], percentile_value: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value
    lo = math.floor(position)
    hi = math.ceil(position)
    if lo == hi:
        return ordered[lo]
    return ordered[lo] * (hi - position) + ordered[hi] * (position - lo)


def classify(record: dict[str, Any]) -> str:
    metrics = record.get("metrics") or {}
    mode = str(metrics.get("captureMode", "")).upper()
    fmt = str(metrics.get("bufferFormat", "")).upper()
    route = str(record.get("route", "")).upper()
    is_multi = mode.startswith("MULTI") or "MULTI" in route
    if "YUV" in fmt or "YUV" in route:
        source = "yuv"
    elif "RAW10" in fmt or "RAW10" in route:
        source = "raw10"
    elif "RAW_SENSOR" in fmt or "RAWSENSOR" in route or "RAW_SENSOR" in route:
        source = "raw_sensor"
    else:
        source = "unknown"
    return ("multi" if is_multi else "single") + "_" + source


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Invalid JSON at {path}:{number}: {exc}")
        if not isinstance(value, dict):
            raise SystemExit(f"Expected JSON object at {path}:{number}")
        records.append(value)
    return records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("performance", type=Path)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--minimum-single-yuv", type=int, default=10)
    parser.add_argument("--minimum-other-route", type=int, default=3)
    parser.add_argument("--gate-ms", type=float, default=3000.0)
    parser.add_argument("--allow-missing-dng", action="store_true")
    args = parser.parse_args()

    failures: list[str] = []
    warnings: list[str] = []
    if not args.performance.is_file():
        raise SystemExit(f"Missing performance file: {args.performance}")
    if not args.trace.is_file():
        raise SystemExit(f"Missing runtime trace: {args.trace}")

    records = load_jsonl(args.performance)
    trace = args.trace.read_text(encoding="utf-8", errors="replace")
    if not records:
        failures.append("performance report contains no records")

    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        groups[classify(record)].append(record)

    required = {
        "single_yuv": args.minimum_single_yuv,
        "single_raw10": args.minimum_other_route,
        "single_raw_sensor": args.minimum_other_route,
        "multi_yuv": args.minimum_other_route,
        "multi_raw10": args.minimum_other_route,
        "multi_raw_sensor": args.minimum_other_route,
    }
    for route, minimum in required.items():
        actual = len(groups.get(route, []))
        if actual < minimum:
            failures.append(f"{route}: expected at least {minimum} records, found {actual}")

    published = [r for r in records if str(r.get("status", "")).upper() == "PUBLISHED"]
    non_published = [r for r in records if str(r.get("status", "")).upper() != "PUBLISHED"]
    if non_published:
        failures.append(f"{len(non_published)} capture record(s) are not PUBLISHED")

    known_trace_failures = [
        "Single frame YUV produced no output URI",
        "ForgottenCoroutineScopeException",
        "runner_returned_no_output",
        "CAPTURE_EXCEPTION",
        "FATAL EXCEPTION",
        "java.lang.OutOfMemoryError",
    ]
    for marker in known_trace_failures:
        if marker in trace:
            failures.append(f"runtime trace contains forbidden failure marker: {marker}")

    yuv_sync_results = trace.count("type=CompletedSynchronously")
    if len(groups.get("single_yuv", [])) >= args.minimum_single_yuv and yuv_sync_results < args.minimum_single_yuv:
        warnings.append(
            f"runtime trace contains only {yuv_sync_results} CompletedSynchronously runner results; "
            "verify the trace was cleared immediately before this test"
        )

    for record in groups.get("single_yuv", []):
        counters = record.get("invocationCounters") or {}
        cid = record.get("captureId")
        checks = {
            "yuvNativeInvocationCount": 1,
            "jpegEncodeInvocationCount": 1,
            "jpegMediaStorePublicationCount": 1,
            "terminalPublicationCount": 1,
        }
        for name, expected in checks.items():
            actual = as_int(counters.get(name), -1)
            if actual != expected:
                failures.append(f"single_yuv captureId={cid}: {name}={actual}, expected {expected}")
        if as_int((record.get("metrics") or {}).get("finalJpegByteCount"), 0) <= 0:
            failures.append(f"single_yuv captureId={cid}: finalJpegByteCount is not positive")

    raw_records = [r for key, group in groups.items() if "raw" in key for r in group]
    dng_records: list[dict[str, Any]] = []
    for record in raw_records:
        metrics = record.get("metrics") or {}
        counters = record.get("invocationCounters") or {}
        cid = record.get("captureId")
        route = classify(record)
        policy = str(metrics.get("outputPolicy", "")).upper()
        produces_jpeg = "JPEG" in policy or policy == ""
        produces_dng = "RAW" in policy or "DNG" in policy
        if produces_dng:
            dng_records.append(record)

        if metrics.get("raw16Ownership") != "NATIVE_HANDLE":
            failures.append(f"{route} captureId={cid}: raw16Ownership={metrics.get('raw16Ownership')!r}")
        released = as_bool(metrics.get("nativeRaw16ReleaseSucceeded"))
        if released is not True:
            failures.append(f"{route} captureId={cid}: nativeRaw16ReleaseSucceeded={released}")
        materializations = as_int(metrics.get("nativeRaw16MaterializationCountActual"), -1)
        if produces_dng:
            if materializations != 1:
                failures.append(
                    f"{route} captureId={cid}: DNG route materialized RAW16 {materializations} times, expected 1"
                )
        elif materializations != 0:
            failures.append(
                f"{route} captureId={cid}: JPEG-only route materialized Java RAW16 {materializations} times, expected 0"
            )
        if produces_jpeg:
            if as_int(counters.get("rawIspInvocationCount"), -1) != 1:
                failures.append(f"{route} captureId={cid}: rawIspInvocationCount must be 1")
            if as_int(counters.get("jpegEncodeInvocationCount"), -1) != 1:
                failures.append(f"{route} captureId={cid}: jpegEncodeInvocationCount must be 1")
            if as_int(counters.get("jpegMediaStorePublicationCount"), -1) != 1:
                failures.append(f"{route} captureId={cid}: JPEG publication count must be 1")
        if route.startswith("multi_") and as_int(counters.get("rawMergeInvocationCount"), -1) != 1:
            failures.append(f"{route} captureId={cid}: rawMergeInvocationCount must be 1")

    if not dng_records and not args.allow_missing_dng:
        failures.append("no RAW JPEG+DNG or RAW-only record found; native materialization path was not tested")

    # In sequential testing the final RAW record should leave the registry empty. We do not demand
    # zero after every capture because CaptureProcessingQueue may overlap independent captures.
    if raw_records:
        last = raw_records[-1]
        metrics = last.get("metrics") or {}
        handles = as_int(metrics.get("nativeRaw16RegistryHandleCountAfterRelease"), -1)
        bytes_after = as_int(metrics.get("nativeRaw16RegistryByteCountAfterRelease"), -1)
        if handles not in (0, -1):
            failures.append(f"final RAW record leaves {handles} native RAW16 handle(s) active")
        if bytes_after not in (0, -1):
            failures.append(f"final RAW record leaves {bytes_after} native RAW16 byte(s) active")
        if handles == -1 or bytes_after == -1:
            warnings.append("final RAW record does not expose complete native registry-after-release metrics")

    print("BnCam Phase 1 device report verification")
    print(f"records={len(records)} published={len(published)} failed={len(non_published)}")
    print()
    print("Route summary")
    print("route,count,p50_ms,p95_ms,gate")
    for route in required:
        group = groups.get(route, [])
        times = [float(r.get("elapsedMs", 0.0)) for r in group if r.get("elapsedMs") is not None]
        p50 = percentile(times, 0.50)
        p95 = percentile(times, 0.95)
        gate = "PASS" if times and p95 <= args.gate_ms else "FAIL"
        print(f"{route},{len(group)},{p50:.3f},{p95:.3f},{gate}")

    if warnings:
        print("\nWarnings")
        for warning in warnings:
            print(f"WARN {warning}")
    if failures:
        print("\nFailures")
        for failure in failures:
            print(f"FAIL {failure}")
        print(f"\nRESULT: FAIL ({len(failures)} failure(s))")
        return 1
    print("\nRESULT: PASS — Phase 1 device ownership/crash contracts verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
