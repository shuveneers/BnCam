"""Restore capture-local noise and Android DNG lens shading for the fixed scene replay.

Usage: python prepare_default_raw_capture_metadata.py FIXTURE_DIRECTORY
Requires numpy and tifffile. Reads original.dng and isp-unwrapped.txt.
Opcode layout: Android media/img_utils/src/DngUtils.cpp, addGainMap().
https://android.googlesource.com/platform/frameworks/av/+/e743a47/media/img_utils/src/DngUtils.cpp
"""
import argparse
import json
import re
import struct
from pathlib import Path
import numpy as np
import tifffile

parser = argparse.ArgumentParser(__doc__)
parser.add_argument('fixture', type=Path)
args = parser.parse_args()
report = (args.fixture/'isp-unwrapped.txt').read_text()
noise = [float(v) for v in re.search(r'noiseModelSoReceivedByCpp=\[([^]]+)\]', report)[1].split(',')]
assert len(noise) == 8 and all(np.isfinite(noise))
confidence = float(re.search(r'spectraSignalModelConfidence=([0-9.]+)', report)[1])
with tifffile.TiffFile(args.fixture/'original.dng') as dng:
    tags = dng.pages[0].tags
    assert tags[33422].value == bytes([2, 1, 1, 0]), 'This fixture uses BGGR'
    blob = tags[51009].value
count, = struct.unpack_from('>I', blob)
assert count == 4
offset = 4
maps = {}
phase_to_channel = {(1, 1): 0, (0, 1): 1, (1, 0): 2, (0, 0): 3}
for _ in range(count):
    opcode, version, flags, size = struct.unpack_from('>4I', blob, offset)
    offset += 16
    assert opcode == 9 and version == 0x01030000
    top, left, bottom, right, plane, planes, rp, cp, rows, cols, sv, sh, ov, oh, mp = struct.unpack_from('>10I4dI', blob, offset)
    assert (plane, planes, rp, cp, ov, oh, mp) == (0, 1, 2, 2, 0, 0, 1)
    assert np.isclose(sv, 1/(rows-1)) and np.isclose(sh, 1/(cols-1))
    assert size == 76 + rows*cols*4
    maps[phase_to_channel[top, left]] = np.frombuffer(blob, dtype='>f4', count=rows*cols, offset=offset+76).reshape(rows, cols)
    offset += size
assert offset == len(blob) and len(maps) == 4
gains = np.stack([maps[ch] for ch in range(4)], axis=-1)
assert np.isfinite(gains).all() and np.min(gains) >= 1
values = [confidence, *noise, rows, cols, *gains.ravel().tolist()]
(args.fixture/'capture-metadata.txt').write_text(' '.join(map(str, values))+'\n', encoding='utf-8')
print(json.dumps(dict(noise_SO=noise, confidence=confidence, rows=rows, cols=cols,
                      minimum_gain=float(gains.min()), maximum_gain=float(gains.max())), indent=2))
