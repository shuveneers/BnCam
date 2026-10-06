"""Numerical infrastructure validation, explicitly separate from learned quality."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import struct
import subprocess
import time
import numpy as np
import torch
from .contracts import PATTERNS, pack, merge
from .model import BncNeural, Config, infer_tiled, execution_plan
from .package import export_reference
from .tests.test_contracts import PROVENANCE


def run(command, log):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
    with log.open("a", encoding="utf-8") as f:
        f.write(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f"command failed: {command[0]}; see {log}")
    return result.stdout


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--out", type=Path, default=Path("build/bnc-neural"))
    p.add_argument("--device", action="store_true")
    args = p.parse_args()
    for name in ("architecture", "benchmarks", "device", "metrics", "model"):
        (args.out/name).mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.manual_seed(3901)
    inp = torch.rand(1, 4, 65, 79)
    rows = []
    for width in (8, 12, 16):
        for blocks in (4, 6):
            model = BncNeural(Config(width, blocks)).eval()
            with torch.no_grad():
                full = model(inp)
                start = time.perf_counter()
                for _ in range(5):
                    tiled = infer_tiled(model, inp, 32)
                ms = (time.perf_counter()-start)*1000/5
            error = float((full-tiled).abs().max())
            # CPU convolution algorithms can change summation order with tile shape.
            # 2e-6 normalized FP32 is 8e-6 scene-linear; always publish actual error.
            assert error <= 2e-6, error
            rows.append(dict(**model.config.description(),
                             referenceTiledMaxAbs=error, referenceCpuMs65x79=ms,
                             benchmarkModel="random contract weights; not reconstruction quality",
                             plan12mp=execution_plan(model.config, 2000, 1500)))
    (args.out/"architecture/candidates.json").write_text(json.dumps(rows, indent=2))
    (args.out/"benchmarks/reference.json").write_text(json.dumps(dict(cpu=platform.processor(),
        torch=str(torch.__version__), threads=2, rows=rows, adreno840InferenceMs=None), indent=2))
    # Test package kept outside app assets, clearly identified as NOT TRAINED.
    fixture_package = args.out/"model/contract-test-NOT-TRAINED.bncmodel"
    _, sha = export_reference(BncNeural(Config(8, 4)), fixture_package, PROVENANCE)
    rng = np.random.default_rng(941)
    cases = []
    for height, width in ((16, 18), (17, 18), (16, 19), (17, 19)):
        for pattern_index, pattern in enumerate(PATTERNS):
            for ox, oy, cx, cy in ((0, 0, 0, 0), (1, 0, 0, 1), (0, 1, 1, 0), (-3, 7, 3, 4), (1, 1, 1, 1)):
                raw = rng.uniform(0, 4, (height, width)).astype("f4")
                raw[0, 0] = -0.
                raw[0, 1] = np.nextafter(np.float32(0), np.float32(1))
                packed, g = pack(raw, pattern, (ox, oy), (cx, cy))
                missing = rng.uniform(-.1, 1.1, (*packed.shape[:2], 8)).astype("f4")
                rgb = merge(raw, missing, g)
                cases.append(struct.pack("<IIIiiii", width, height, pattern_index, ox, oy, cx, cy) +
                             raw.tobytes()+packed.tobytes()+missing.tobytes()+rgb.tobytes())
    fixtures = args.out/"device/native-contract-fixtures.bin"
    fixtures.write_bytes(struct.pack("<I", len(cases))+b"".join(cases))
    if args.device:
        repo = Path(__file__).resolve().parents[3]
        sdk = Path.home()/"AppData/Local/Android/Sdk"
        clang = sdk/"ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
        adb = sdk/"platform-tools/adb.exe"
        native = args.out/"device/bnc-contract"
        log = args.out/"device/native-contract.txt"
        log.write_text("")
        run([clang, "--target=aarch64-linux-android29", "-std=c++17", "-O2", "-static-libstdc++",
             "-I", repo/"app/src/main/cpp", repo/"app/src/test/cpp/BncNeuralContractTest.cpp",
             repo/"app/src/main/cpp/vulkan/NeuralSha256.cpp", "-o", native], log)
        remote = "/data/local/tmp/bncam-bnc-neural-contract"
        run([adb, "shell", "mkdir", "-p", remote], log)
        for file in (native, fixtures, fixture_package):
            run([adb, "push", file, remote+"/"+file.name], log)
        run([adb, "shell", "chmod", "755", remote+"/"+native.name], log)
        print(run([adb, "shell", remote+"/"+native.name, remote+"/"+fixtures.name,
                   remote+"/"+fixture_package.name], log))
        (args.out/"device/native-provenance.json").write_text(json.dumps(dict(
            executableSHA256=hashlib.sha256(native.read_bytes()).hexdigest(),
            fixturesSHA256=hashlib.sha256(fixtures.read_bytes()).hexdigest(), packageSHA256=sha,
            type="arm64 CPU reference contracts on phone; NOT Vulkan inference", cases=len(cases)), indent=2))


if __name__ == "__main__":
    main()
