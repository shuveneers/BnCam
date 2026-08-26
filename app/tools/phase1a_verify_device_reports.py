#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description="Verify a fresh Phase 1A Single YUV device run")
parser.add_argument("performance_jsonl", type=Path)
parser.add_argument("runtime_trace", type=Path)
parser.add_argument("--minimum-captures", type=int, default=10)
args = parser.parse_args()

records = []
for number, line in enumerate(args.performance_jsonl.read_text(errors="replace").splitlines(), 1):
    if not line.strip():
        continue
    try:
        records.append(json.loads(line))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSONL line {number}: {exc}")

yuv = [r for r in records if r.get("route") == "SINGLE_FRAME_YUV_420_888"]
published = [r for r in yuv if r.get("status") == "PUBLISHED"]
failures = [r for r in yuv if r.get("status") != "PUBLISHED"]
trace = args.runtime_trace.read_text(errors="replace")

checks = {
    f"at least {args.minimum_captures} Single YUV records": len(yuv) >= args.minimum_captures,
    "all Single YUV records published": len(yuv) > 0 and not failures,
    "one native YUV invocation per published capture": all(
        r.get("invocationCounters", {}).get("yuvNativeInvocationCount") == 1
        for r in published
    ),
    "one JPEG encode per published capture": all(
        r.get("invocationCounters", {}).get("jpegEncodeInvocationCount") == 1
        for r in published
    ),
    "one JPEG publication per published capture": all(
        r.get("invocationCounters", {}).get("jpegMediaStorePublicationCount") == 1
        for r in published
    ),
    "processing ownership recorded": all(
        r.get("metrics", {}).get("processingOwner") == "CaptureProcessingQueue"
        for r in published
    ),
    "old synchronous URI race absent": "Single frame YUV produced no output URI" not in trace,
    "Single YUV returned Submitted": "type=Submitted route=SingleYuvJpegPath" in trace,
}

failed = []
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL"), name)
    if not ok:
        failed.append(name)

if yuv:
    elapsed = sorted(float(r.get("elapsedMs", 0.0)) for r in published)
    print(f"INFO Single YUV records={len(yuv)} published={len(published)} failed={len(failures)}")
    if elapsed:
        print(f"INFO elapsed min={elapsed[0]:.3f} ms median={elapsed[len(elapsed)//2]:.3f} ms max={elapsed[-1]:.3f} ms")

raise SystemExit(1 if failed else 0)
