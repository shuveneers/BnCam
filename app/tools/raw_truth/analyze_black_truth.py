#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from black_truth import dump_json, format_human, parse_truth


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract BnCam RAW black/Pass-0 truth telemetry from a log, directory, or debug ZIP without deriving a correction."
    )
    parser.add_argument("input", type=Path, help="Debug text/log file, directory, or ZIP")
    parser.add_argument(
        "--dark-frame",
        action="store_true",
        help="Declare that the capture was made with the lens fully covered. This changes wording only; it never applies a threshold/correction.",
    )
    parser.add_argument("--json-out", type=Path, help="Optional JSON output path")
    args = parser.parse_args()

    if not args.input.exists():
        parser.error(f"Input does not exist: {args.input}")

    try:
        result = parse_truth(args.input, declared_dark_frame=args.dark_frame)
    except Exception as exc:
        print(f"ERROR: {exc}")
        return 2

    print(format_human(result))
    if args.json_out:
        dump_json(result, args.json_out)
        print(f"\nJSON written: {args.json_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
