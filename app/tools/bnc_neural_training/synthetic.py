"""Expand held-out truth patterns and run the actual unchanged classical Vulkan routes.

No fixture in this file is loaded by training. Phase-2 evidence is copied, never edited.
"""
from pathlib import Path
import argparse
import json
import shutil
import subprocess
import numpy as np
from .contracts import sample


def prepare(out, phase2):
    fixtures, demosaic = out/"fixtures", out/"demosaic"
    fixtures.mkdir(parents=True, exist_ok=True)
    demosaic.mkdir(exist_ok=True)
    for path in (phase2/"fixtures").glob("synthetic-*"):
        dest = fixtures/path.name
        dest.mkdir(exist_ok=True)
        for name in ("ground-truth.rgb.f32", "normalized-off.f32", "golden.txt"):
            shutil.copy2(path/name, dest/name)
        shutil.copytree(phase2/"demosaic"/path.name, demosaic/path.name, dirs_exist_ok=True)
    y, x = np.indices((256, 256))
    scenes = {"flat_neutral": np.full((256, 256), .61),
              "neutral_headroom": 1.4 + .8*np.cos(2*np.pi*x/13),
              "slanted_edge_0417": .2+.6*(x>y*.417+39),
              "slanted_edge_131": .2+.6*(x>y*1.31-20),
              "thin_lines": .2+.6*((x+2*y)%19 < 3),
              "zero_chroma_fine_detail": .5+.15*np.cos(2*np.pi*(x*.173+y*.219))}
    for period in (5, 7, 11, 13):
        scenes[f"repetitive_period_{period}"] = .5+.15*np.cos(2*np.pi*(x+.37)/period)+.15*np.cos(2*np.pi*(y+.61)/period)
    rgb = np.where((x>y*.617+17)[..., None], np.array([.75,.16,.21]), np.array([.12,.53,.81]))
    scenes["color_edge"] = rgb
    # Exactly equal Rec.709 linear luma by construction, different RG and BG on each side.
    left = np.array([.8, .25, .15])
    right = np.array([.15, 0., .75])
    right[1] = (left @ np.array([.2126,.7152,.0722]) - right[0]*.2126-right[2]*.0722)/.7152
    scenes["isoluminant_color_edge"] = np.where((x>y*.383+41)[..., None], left, right)
    ids = []
    for name, scene in scenes.items():
        truth = np.repeat(scene[..., None], 3, axis=-1) if scene.ndim == 2 else scene
        truth = truth.astype("f4")
        dest = fixtures/("synthetic-"+name)
        dest.mkdir(exist_ok=True)
        truth.tofile(dest/"ground-truth.rgb.f32")
        sample(truth, "BGGR").tofile(dest/"normalized-off.f32")
        (dest/"golden.txt").write_text("256 256 3\n1 1 1 1\n1 0 0 0 1 0 0 0 1\n")
        ids.append(dest.name)
    (out/"extended-ids.json").write_text(json.dumps(ids, indent=2))
    return ids


def replay(out, phase2, ids):
    adb = Path.home()/"AppData/Local/Android/Sdk/platform-tools/adb.exe"
    remote = "/data/local/tmp/bncam-bnc-synthetic"
    log = out/"device-replay.txt"
    def run(*args):
        result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
        with log.open("a") as f:
            f.write(result.stdout+result.stderr)
        result.check_returncode()
    run(adb, "shell", "mkdir", "-p", remote)
    for name in ("demosaic-replay", "libbncam.so", "libopencv_java4.so", "libc++_shared.so"):
        run(adb, "push", phase2/"native"/name, remote+"/"+name)
    run(adb, "shell", "chmod", "755", remote+"/demosaic-replay")
    for name in ids:
        source, dest = out/"fixtures"/name, out/"demosaic"/name
        dest.mkdir(exist_ok=True)
        run(adb, "push", source/"golden.txt", remote+"/golden.txt")
        run(adb, "push", source/"normalized-off.f32", remote+"/normalized.f32")
        for label, alg in (("malvar", 1), ("amaze", 4)):
            base = label+"-lsc-off"
            run(adb, "shell", "env", "LD_LIBRARY_PATH="+remote, remote+"/demosaic-replay", remote, base, alg)
            for suffix in ("rgb.f32", "status.txt"):
                run(adb, "pull", remote+"/"+base+"."+suffix, dest/(base+"."+suffix))
        print(name, flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--out", type=Path, default=Path("build/bnc-neural/synthetic"))
    p.add_argument("--phase2", type=Path, default=Path("build/phase2-raw-quality"))
    p.add_argument("--device", action="store_true")
    args = p.parse_args()
    ids = prepare(args.out, args.phase2)
    if args.device:
        replay(args.out, args.phase2, ids)
