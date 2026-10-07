"""Materialize a small declarative RAW fixture for the existing production scene replay.

Usage: python app/tools/prepare_single_frame_replay.py fixture.json build/replay-fixture
Real camera exports use the same raw.bin/fixture.txt/geometry.txt convention (see README).
"""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path


def prepare(source: Path, destination: Path):
    fixture = json.loads(source.read_text(encoding="utf-8"))
    width, height = fixture["width"], fixture["height"]
    cfa, white = fixture["sensorCfa"], fixture["white"]
    black, samples = fixture["positionalBlack"], fixture["samples"]
    if not (width > 0 and height > 0 and cfa in range(4) and 0 < white <= 65535):
        raise ValueError("invalid dimensions/CFA/white")
    if len(samples) != width * height or any(not isinstance(x, int) or not 0 <= x <= 65535 for x in samples):
        raise ValueError("sample count/range mismatch")
    if len(black) != 4 or any(not math.isfinite(x) or not 0 <= x < white for x in black):
        raise ValueError("invalid positional black levels")
    origin = fixture.get("origin", [0, 0])
    if len(origin) != 2 or any(not isinstance(x, int) or x < 0 for x in origin):
        raise ValueError("invalid CFA origin")
    row_pixels = width + fixture.get("paddingPixels", 0)
    if row_pixels < width:
        raise ValueError("invalid row padding")
    rows = []
    for y in range(height):
        rows.extend(samples[y * width:(y + 1) * width] + [65535] * (row_pixels - width))
    payload = struct.pack("<" + "H" * len(rows), *rows)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "raw.bin").write_bytes(payload)
    # Identity WB/CCM isolate fixture geometry; production color/tone implementation remains in use.
    header = [width, height, cfa, fixture.get("iso", 100), fixture.get("format", 1),
              fixture.get("exposureMs", 10), white] + black + [1, 1, 1, 1] + [1, 0, 0, 0, 1, 0, 0, 0, 1]
    (destination / "fixture.txt").write_text(" ".join(map(str, header)) + "\n", encoding="utf-8")
    (destination / "geometry.txt").write_text(f"{origin[0]} {origin[1]} {row_pixels * 2}\n", encoding="utf-8")
    manifest = {"schemaVersion": 1, "fixture": source.name, "rawSha256": hashlib.sha256(payload).hexdigest(),
                "byteCount": len(payload), "scope": "synthetic geometry; no captured device noise/LSC"}
    (destination / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.fixture, args.destination), sort_keys=True))
