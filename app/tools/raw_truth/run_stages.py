"""Replay current production stages with separate generated instrumentation."""
from pathlib import Path
import subprocess,argparse,json
p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('id');p.add_argument('--mode',type=int,default=1);a=p.parse_args()
root=a.root.resolve();repo=Path(__file__).resolve().parents[3];sdk=Path.home()/'AppData/Local/Android/Sdk';adb=sdk/'platform-tools/adb.exe';b=root/'stages';fixture=root/'fixtures'/a.id
def run(*args):subprocess.run(list(map(str,args)),check=True)
libs=repo/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
run(sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe','--target=aarch64-linux-android29','-std=c++17','-O2','-I',repo/'app/src/main/cpp','-I',repo/'opencv/native/jni/include',repo/'app/src/test/cpp/DefaultRawSceneReplay.cpp','-L',b/'native','-L',libs,'-lbncam','-lopencv_java4','-lc++_shared','-o',b/'replay')
remote='/data/local/tmp/bncam-truth-stages';run(adb,'shell','mkdir','-p',remote)
for file in [b/'replay',b/'native/libbncam.so',libs/'libopencv_java4.so',libs/'libc++_shared.so',fixture/'raw.bin',fixture/'fixture.txt',fixture/'capture-metadata.txt']:run(adb,'push',file,remote+'/'+file.name)
run(adb,'shell','chmod','755',remote+'/replay');out=b/a.id;out.mkdir(exist_ok=True)
for stage in [-1,-2,-3,-4,1,2,4,5]:
    name='stage'+str(stage);dest=remote+'/'+name;run(adb,'shell','mkdir','-p',dest)
    run(adb,'shell','env','LD_LIBRARY_PATH='+remote,'BNCAM_RAW_AUDIT_DIR='+dest,'BNCAM_RAW_AUDIT_STAGE='+str(stage),remote+'/replay',remote,name,'1',a.mode)
    run(adb,'pull',dest,out);run(adb,'pull',remote+'/'+name+'.txt',out/(name+'.txt'))
    log=(out/(name+'.txt')).read_text();assert 'vulkanToneUsedForOutput=true' in log
    assert list((out/name).glob('tone__*.f32'))
    if stage==5:run(adb,'pull',remote+'/'+name+'.jpg',out/'normal-replay.jpg')
