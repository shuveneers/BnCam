#!/usr/bin/env python3
"""Summarize BnCam Phase 0 capture_performance.jsonl without external packages."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


def percentile(values: list[float], q: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def load_records(paths: Iterable[Path]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_number}: invalid JSON: {exc}") from exc
                item["_source"] = str(path)
                item["_line"] = line_number
                records.append(item)
    return records


def route_key(record: dict[str, Any]) -> str:
    metrics = record.get("metrics") or {}
    capture_mode = metrics.get("captureMode", "?")
    buffer_format = metrics.get("bufferFormat", "?")
    output_policy = metrics.get("outputPolicy", "?")
    requested = metrics.get("requestedFrameCount", "?")
    return f"{capture_mode}/{buffer_format}/{output_policy}/requested={requested}"


def as_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def validate(record: dict[str, Any]) -> list[str]:
    status = record.get("status")
    if status != "PUBLISHED":
        return []
    metrics = record.get("metrics") or {}
    counters = record.get("invocationCounters") or {}
    mode = metrics.get("captureMode")
    fmt = str(metrics.get("bufferFormat", ""))
    policy = str(metrics.get("outputPolicy", ""))
    produces_jpeg = "JPEG" in policy
    produces_raw = "RAW" in policy
    is_raw = "RAW" in fmt
    failures: list[str] = []

    if produces_jpeg:
        if int(counters.get("jpegEncodeInvocationCount", 0)) != 1:
            failures.append("jpegEncodeInvocationCount != 1")
        if int(counters.get("jpegMediaStorePublicationCount", 0)) != 1:
            failures.append("jpegMediaStorePublicationCount != 1")
        if is_raw and int(counters.get("rawIspInvocationCount", 0)) != 1:
            failures.append("rawIspInvocationCount != 1")
    if produces_raw and int(counters.get("dngMediaStorePublicationCount", 0)) != 1:
        failures.append("dngMediaStorePublicationCount != 1")
    if mode == "SINGLE" and int(counters.get("rawMergeInvocationCount", 0)) != 0:
        failures.append("single capture invoked RAW merge")
    if mode == "MULTI" and is_raw and int(counters.get("rawMergeInvocationCount", 0)) != 1:
        failures.append("RAW multi capture merge count != 1")
    return failures



def runtime_metric(record: dict[str, Any]) -> tuple[str, float | None, str]:
    events = record.get("events") or {}
    details = record.get("details") or {}
    kind = str(record.get("traceType", "UNKNOWN"))

    def between(first: str, last: str) -> float | None:
        try:
            start = int(events[first])
            end = int(events[last])
        except (KeyError, TypeError, ValueError):
            return None
        return max(0.0, (end - start) / 1_000_000.0)

    if kind == "STARTUP":
        return (
            str(details.get("startupClass", "STARTUP")),
            between("process_start", "first_viewfinder_frame_presented"),
            str(details.get("presentationSignal", "n/a")),
        )
    if kind == "WARM_RESUME":
        return (
            "WARM_RESUME",
            between("activity_resume", "first_viewfinder_frame_presented"),
            str(details.get("presentationSignal", "n/a")),
        )
    if kind == "LENS_SWITCH":
        return (
            f"LENS_SWITCH {details.get('fromLensId', '?')}->{details.get('targetLensId', '?')}",
            between("lens_ui_request", "first_target_viewfinder_frame_presented"),
            str(details.get("presentationSignal", "n/a")),
        )
    if kind == "THUMBNAIL":
        return (
            f"THUMBNAIL {details.get('route', '?')}",
            between("shutter", "thumbnail_ui_presented"),
            "n/a",
        )
    return kind, None, "n/a"


def summarize_runtime(records: list[dict[str, Any]]) -> None:
    groups: dict[str, list[tuple[float, str]]] = defaultdict(list)
    incomplete: list[str] = []
    for record in records:
        label, value, signal = runtime_metric(record)
        if value is None:
            incomplete.append(f"{record.get('_source')}:{record.get('_line')} {label}")
            continue
        groups[label].append((value, signal))

    header = (
        f"{'Metric':55} {'N':>3} {'min':>9} {'mean':>9} "
        f"{'p50':>9} {'p95':>9} {'max':>9}  presentation-signal(s)"
    )
    print(header)
    print("-" * len(header))
    for label in sorted(groups):
        samples = groups[label]
        values = [item[0] for item in samples]
        signals = ",".join(sorted(set(item[1] for item in samples)))
        print(
            f"{label[:55]:55} {len(values):3d} {min(values):9.1f} "
            f"{statistics.fmean(values):9.1f} {percentile(values, 0.50):9.1f} "
            f"{percentile(values, 0.95):9.1f} {max(values):9.1f}  {signals}"
        )
    if incomplete:
        print("\nIncomplete/unrecognized records:")
        for item in incomplete:
            print(f"- {item}")

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path, help="capture_performance.jsonl file(s)")
    parser.add_argument("--gate-ms", type=float, default=3000.0)
    args = parser.parse_args()

    records = load_records(args.files)
    if not records:
        raise SystemExit("No Phase-0 records found.")

    if all("traceType" in record for record in records):
        summarize_runtime(records)
        return

    if any("traceType" in record for record in records):
        raise SystemExit("Do not mix runtime timeline JSONL and capture JSONL in one invocation.")

    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        groups[route_key(record)].append(record)

    print(f"Records: {len(records)} | release gate: p95 <= {args.gate_ms:.1f} ms")
    print()
    header = (
        f"{'Route':70} {'N':>3} {'OK':>3} {'min':>9} {'mean':>9} "
        f"{'p50':>9} {'p95':>9} {'max':>9} {'gate':>6}"
    )
    print(header)
    print("-" * len(header))

    any_violations = False
    for key in sorted(groups):
        group = groups[key]
        successful = [r for r in group if r.get("status") == "PUBLISHED"]
        durations = [v for r in successful if (v := as_float(r.get("elapsedMs"))) is not None]
        if durations:
            p95 = percentile(durations, 0.95)
            gate = "PASS" if p95 <= args.gate_ms else "FAIL"
            line = (
                f"{key[:70]:70} {len(group):3d} {len(successful):3d} "
                f"{min(durations):9.1f} {statistics.fmean(durations):9.1f} "
                f"{percentile(durations, 0.50):9.1f} {p95:9.1f} {max(durations):9.1f} {gate:>6}"
            )
        else:
            line = f"{key[:70]:70} {len(group):3d} {0:3d} {'-':>9} {'-':>9} {'-':>9} {'-':>9} {'-':>9} {'FAIL':>6}"
        print(line)

        for record in group:
            violations = validate(record)
            if violations:
                any_violations = True
                print(
                    f"  CONTRACT VIOLATION captureId={record.get('captureId')}: "
                    + "; ".join(violations)
                )
            if record.get("status") != "PUBLISHED":
                print(
                    f"  CAPTURE FAILURE captureId={record.get('captureId')} "
                    f"reason={record.get('failureReason')}"
                )

    if any_violations:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
