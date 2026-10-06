"""Run real-device gain/routing checks and isolated demosaic regression fixtures.
Run prepare_phase2.py and assembleDebug first. Requires PYTHONPATH with numpy/scipy/Pillow.
Does not install an APK or change camera settings.
"""
from pathlib import Path
import subprocess, shutil, json, hashlib, argparse
p=argparse.ArgumentParser(__doc__);p.add_argument("--regression-only",action="store_true");p.add_argument("--ids",nargs="+");args=p.parse_args()

repo=Path(__file__).resolve().parents[3]; root=repo/'build/phase2-raw-quality'
sdk=Path.home()/'AppData/Local/Android/Sdk'; adb=sdk/'platform-tools/adb.exe'
clang=sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
libs=repo/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
native=root/'native'; native.mkdir(exist_ok=True)
remote='/data/local/tmp/bncam-phase2-quality'
def run(*args): subprocess.run(list(map(str,args)),check=True)
for name in ['libbncam.so','libopencv_java4.so','libc++_shared.so']:
    shutil.copy2(libs/name,native/name)
for source,exe in [('RawQualityRegression.cpp','regression'),('RawTruthDemosaic.cpp','demosaic-replay')]:
    run(clang,'--target=aarch64-linux-android29','-std=c++17','-O2','-I',repo/'app/src/main/cpp','-I',repo/'opencv/native/jni/include',repo/'app/src/test/cpp'/source,'-L',native,'-lbncam','-lopencv_java4','-lc++_shared','-o',native/exe)
run(adb,'shell','mkdir','-p',remote)
for f in native.iterdir(): run(adb,'push',f,remote+'/'+f.name)
run(adb,'shell','chmod','755',remote+'/regression',remote+'/demosaic-replay')
ids=json.loads((root/'fixture-ids.json').read_text()); maps=[]
for id in ids:
    name=id+'-gains.txt'; run(adb,'push',root/'fixtures'/id/'gain-test.txt',remote+'/'+name); maps.append(remote+'/'+name)
result=subprocess.run([str(adb),'shell','env','LD_LIBRARY_PATH='+remote,remote+'/regression',*maps],capture_output=True,text=True)
(root/'metrics/device-regression.txt').write_text(result.stdout+result.stderr)
print(result.stdout,flush=True); result.check_returncode()
for fixture in ([] if args.regression_only else sorted((root/'fixtures').iterdir())):
    if args.ids and fixture.name not in args.ids: continue
    if not (fixture/'golden.txt').exists(): continue
    synthetic=fixture.name.startswith('synthetic-'); out=root/'demosaic'/fixture.name; out.mkdir(exist_ok=True)
    job=remote+'/'+fixture.name; run(adb,'shell','mkdir','-p',job); run(adb,'push',fixture/'golden.txt',job+'/golden.txt')
    variant='off' if synthetic else 'on'
    run(adb,'push',fixture/f'normalized-{variant}.f32',job+'/normalized.f32')
    algorithms={'malvar':1,'amaze':4,'auto':5,'bnc-unavailable':3} if synthetic else {'malvar':1,'amaze':4}
    for label,alg in algorithms.items():
        name=label+'-lsc-'+variant
        run(adb,'shell','env','LD_LIBRARY_PATH='+remote,remote+'/demosaic-replay',job,name,alg)
        for ext in ['rgb.f32','jpg','status.txt']: run(adb,'pull',job+'/'+name+'.'+ext,out/(name+'.'+ext))
        print('Completed',fixture.name,name,flush=True)
(root/'native-manifest.json').write_text(json.dumps({f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in native.iterdir()},indent=2))
