"""Run the separately built stage-audit library on a fixed RAW fixture.
The fixture directory contains raw.bin (little-endian uint16) and fixture.txt:
width height CFA ISO RAW_SENSOR_flag exposure_ms white, 4 black levels,
4 WB gains (R,Gr,Gb,B), 9 row-major Camera2 CCM coefficients.
See prepare_default_raw_stage_audit.py. No app installation or settings changes.
"""
from pathlib import Path
import argparse, subprocess, shutil
parser=argparse.ArgumentParser(__doc__)
parser.add_argument('--sdk',type=Path,required=True)
parser.add_argument('--fixture',type=Path,required=True)
parser.add_argument('--build',type=Path)
parser.add_argument('--variant',help='Single current-policy run (always single-shot RAW); use separate frozen/new libraries')
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
build=args.build.resolve() if args.build else root/'build/default-raw-stage-audit'
adb=args.sdk/'platform-tools/adb.exe'
clang=args.sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
libs=root/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
def run(*cmd):
    subprocess.run(list(map(str,cmd)),check=True,cwd=root)
run(clang,'--target=aarch64-linux-android29','-std=c++17','-O2','-I',root/'app/src/main/cpp',
    '-I',root/'opencv/native/jni/include',root/'app/src/test/cpp/DefaultRawSceneReplay.cpp',
    '-L',build/'native','-L',libs,'-lbncam','-lopencv_java4','-lc++_shared','-o',build/'replay')
remote='/data/local/tmp/bncam-'+build.name
(build/'dumps').mkdir(exist_ok=True)
run(adb,'shell','mkdir','-p',remote)
for file in [build/'replay',build/'native/libbncam.so',libs/'libopencv_java4.so',libs/'libc++_shared.so',args.fixture/'raw.bin',args.fixture/'fixture.txt']:
    run(adb,'push',file,remote+'/'+file.name)
if (args.fixture/'capture-metadata.txt').exists():
    run(adb,'push',args.fixture/'capture-metadata.txt',remote+'/capture-metadata.txt')
else:
    raise RuntimeError('Capture noise/LSC metadata is required for a production-path noise audit')
run(adb,'shell','chmod','755',remote+'/replay')
for variant,flag in ([(args.variant,1)] if args.variant else [('old',0),('new',1)]):
    for stage in [-1,-2,-3,-4,1,2,4,5]:
        name=variant+'-stage-'+str(stage)
        dest=remote+'/'+name
        run(adb,'shell','mkdir','-p',dest)
        run(adb,'shell','env','LD_LIBRARY_PATH='+remote,'BNCAM_RAW_AUDIT_DIR='+dest,
            'BNCAM_RAW_AUDIT_STAGE='+str(stage),remote+'/replay',remote,name,str(flag))
        # Pull into the parent, avoiding an extra nested directory on repeat runs.
        run(adb,'pull',dest,build/'dumps')
        run(adb,'pull',remote+'/'+name+'.txt',build/'dumps'/(name+'.txt'))
        log=(build/'dumps'/(name+'.txt')).read_text()
        assert 'vulkanToneUsedForOutput=true' in log, 'Audit unexpectedly left the GPU tone path'
        assert (build/'dumps'/name/'tone__blue_hood.f32').exists(), 'Missing native tone samples'
        if stage == 5:
            run(adb,'pull',remote+'/'+name+'.jpg',build/(variant+'-full-metadata.jpg'))
shutil.copy2(args.fixture/'fixture.txt',build/'fixture.txt')
shutil.copy2(args.fixture/'capture-metadata.txt',build/'capture-metadata.txt')
