#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Optional

from black_truth import parse_truth


def _fmt(value: Optional[float]) -> str:
    return "n/a" if value is None else f"{value:.6f}"


def _metric(result, key: str) -> Optional[float]:
    value = result.derived.get(key)
    return float(value) if isinstance(value, (int, float)) else None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare two BnCam RAW-black truth artifacts without deciding or applying a correction."
    )
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    parser.add_argument("--left-label", default="LEFT")
    parser.add_argument("--right-label", default="RIGHT")
    parser.add_argument("--dark-frame", action="store_true")
    args = parser.parse_args()

    for p in (args.left, args.right):
        if not p.exists():
            parser.error(f"Input does not exist: {p}")

    left = parse_truth(args.left, declared_dark_frame=args.dark_frame)
    right = parse_truth(args.right, declared_dark_frame=args.dark_frame)

    print("BnCam RAW BLACK TRUTH COMPARISON")
    print("================================")
    print(f"Left : {args.left_label} -> {args.left}")
    print(f"Right: {args.right_label} -> {args.right}")
    print(f"Declared dark frame: {'yes' if args.dark_frame else 'no'}")
    print()

    metrics = (
        ("mean_green", "Mean green"),
        ("mean_red_blue", "Mean red/blue"),
        ("green_excess", "Green excess"),
        ("green_split_g1_minus_g2", "G1-G2 split"),
    )
    print(f"{'Metric':24} {args.left_label:>16} {args.right_label:>16} {'right-left':>16}")
    print("-" * 76)
    for key, label in metrics:
        lv = _metric(left, key)
        rv = _metric(right, key)
        delta = None if lv is None or rv is None else rv - lv
        print(f"{label:24} {_fmt(lv):>16} {_fmt(rv):>16} {_fmt(delta):>16}")

    print()
    print("Authority / mutation policy")
    print("---------------------------")
    for key, label in (
        ("scene_black_authority_mode", "Authority mode"),
        ("scene_black_metadata_authoritative", "Metadata authoritative"),
        ("scene_black_image_mutation_allowed", "Image mutation allowed"),
        ("spectra_pass0_applied", "Pass 0 applied"),
    ):
        print(f"{label:24} {str(left.fields.get(key, 'n/a')):>16} {str(right.fields.get(key, 'n/a')):>16}")

    print()
    if args.dark_frame:
        print("Interpretation: these are covered-lens residual measurements only. No automatic root-cause threshold is applied.")
    else:
        print("Interpretation: scene-derived CFA imbalance must not be treated as an electronic black pedestal.")

    if not left.derived or not right.derived:
        print("WARNING: one or both artifacts did not contain a parsable four-channel residual vector.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
