"""Compile production/HEAD GLSL and run CPU/Vulkan colour regression on a connected Android device.
Usage: python app/tools/run_default_raw_color_tests.py --sdk <Android SDK>
No installation or camera settings are changed. All fixtures are synthetic.
"""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(__doc__)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--baseline', default='8aee2d298bffb2daf9f1640e7ff654ab8c7a46bc',
                    help='Frozen pre-fix revision used for Spectra/YUV pixel identity')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
build = root / 'build/default-raw-color-tests'
build.mkdir(parents=True, exist_ok=True)
ndk = sorted((args.sdk / 'ndk').iterdir(), key=lambda p: tuple(map(int,p.name.split('.'))))[-1]
host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
ext = '.exe' if os.name == 'nt' else ''
clang = ndk / 'toolchains/llvm/prebuilt' / host / ('bin/clang++' + ext)
glslc = ndk / 'shader-tools' / host / ('glslc' + ext)
adb = args.sdk / 'platform-tools' / ('adb' + ext)
def run(*cmd):
    print(' '.join(map(str,cmd)), flush=True)
    subprocess.run(list(map(str,cmd)), cwd=root, check=True)
shaders = root / 'app/src/main/cpp/vulkan/shaders'
for name in ['spectra_raw_finalize.comp','spectra_demosaic_resident.comp','spectra_tone_resident.comp']:
    old = build / ('head-' + name)
    old.write_bytes(subprocess.check_output(['git','show',args.baseline+':app/src/main/cpp/vulkan/shaders/'+name],cwd=root))
    for path in [shaders/name, old]:
        run(glslc,'--target-env=vulkan1.1','-O','-I',shaders,path,'-o',build/(path.name+'.spv'))
exe = build / 'default-raw-color-test'
run(clang,'--target=aarch64-linux-android29','-std=c++17','-O2','-UNDEBUG','-static-libstdc++',
    '-I',root/'app/src/main/cpp',root/'app/src/test/cpp/DefaultRawColorHighlightDeviceTest.cpp','-lvulkan','-o',exe)
remote = '/data/local/tmp/bncam-default-raw-color-tests'
run(adb,'shell','mkdir','-p',remote)
for path in [exe, *build.glob('*.spv')]: run(adb,'push',path,remote+'/'+path.name)
run(adb,'shell','chmod','755',remote+'/'+exe.name)
run(adb,'shell',remote+'/'+exe.name,remote)
