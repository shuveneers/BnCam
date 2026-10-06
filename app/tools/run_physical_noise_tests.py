"""CMake/CTest entry point. Host by default; --sdk PATH runs arm64 tests on adb.

Android requires one connected device (or ANDROID_SERIAL). No application settings
are changed. CTest deploys each executable through this script's emulator mode.
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import sys


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


if len(sys.argv) > 1 and sys.argv[1] == "--run-device":
    adb, binary = sys.argv[2:4]
    remote = "/data/local/tmp/bncam-" + Path(binary).name
    run(adb, "push", binary, remote)
    run(adb, "shell", "chmod", "755", remote)
    run(adb, "shell", remote)
    sys.exit(0)

parser = argparse.ArgumentParser(__doc__)
parser.add_argument("--sdk", type=Path)
parser.add_argument("--build-dir", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
build = args.build_dir or root / "build" / "physical-noise-tests"
cmake = shutil.which("cmake")
config = ["-DCMAKE_BUILD_TYPE=Release"]
if args.sdk:
    ndk = sorted((args.sdk / "ndk").iterdir(), key=lambda p: tuple(map(int, p.name.split('.'))))[-1]
    cmake_bin = args.sdk / "cmake" / "3.22.1" / "bin"
    suffix = ".exe" if os.name == "nt" else ""
    cmake = str(cmake_bin / ("cmake" + suffix))
    config += ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={cmake_bin / ('ninja' + suffix)}",
               f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
               "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static",
               "-DCMAKE_CROSSCOMPILING_EMULATOR=" + ';'.join([sys.executable, str(Path(__file__).resolve()),
                   "--run-device", str(args.sdk / 'platform-tools' / ('adb' + suffix))])]
if not cmake:
    raise SystemExit("Install CMake and a C++17 host compiler, or pass --sdk for Android.")
run(cmake, "-S", root / "app/src/test/cpp", "-B", build, *config)
run(cmake, "--build", build, "--config", "Release", "--parallel", "4")
ctest = Path(cmake).with_name("ctest.exe" if os.name == "nt" else "ctest") if Path(cmake).is_absolute() else "ctest"
run(ctest, "--test-dir", build, "-C", "Release", "--output-on-failure")
