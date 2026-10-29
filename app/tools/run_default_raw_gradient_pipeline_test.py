"""Run full resident highlight/CFA/RAW10 parity regression after assembleDebug."""
from pathlib import Path
import argparse,subprocess,os
p=argparse.ArgumentParser(__doc__);p.add_argument('--sdk',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2];out=root/'build/default-raw-gradient-test';out.mkdir(parents=True,exist_ok=True)
ndk=sorted((a.sdk/'ndk').iterdir(),key=lambda x:tuple(map(int,x.name.split('.'))))[-1]
host='windows-x86_64' if os.name=='nt' else 'linux-x86_64';ext='.exe' if os.name=='nt' else ''
clang=ndk/'toolchains/llvm/prebuilt'/host/('bin/clang++'+ext);adb=a.sdk/'platform-tools'/('adb'+ext)
libs=root/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
def run(*cmd):subprocess.run(list(map(str,cmd)),cwd=root,check=True)
exe=out/'gradient-pipeline-test'
run(clang,'--target=aarch64-linux-android29','-std=c++17','-O2','-UNDEBUG',
    '-I',root/'app/src/main/cpp','-I',root/'opencv/native/jni/include',
    root/'app/src/test/cpp/DefaultRawGradientPipelineDeviceTest.cpp','-L',libs,
    '-lbncam','-lopencv_java4','-lc++_shared','-o',exe)
remote='/data/local/tmp/bncam-production-gradient-test';run(adb,'shell','mkdir','-p',remote)
for path in (exe,libs/'libbncam.so',libs/'libopencv_java4.so',libs/'libc++_shared.so'):run(adb,'push',path,remote+'/'+path.name)
run(adb,'shell','chmod','755',remote+'/'+exe.name)
run(adb,'shell','env','LD_LIBRARY_PATH='+remote,remote+'/'+exe.name)
