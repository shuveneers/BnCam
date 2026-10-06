"""Expose the actual CPU fallback LSC in the diagnostic COPY only, then compare GPU.
Run after phase2 production-stage replays finish (shares their generated native build).
"""
from pathlib import Path
import subprocess,json
repo=Path(__file__).resolve().parents[3];r=repo/'build/phase2-raw-quality';b=r/'stages'
p=b/'src/IspCore.cpp';s=p.read_text()
marker='// PHASE2_CPU_LSC_PROBE'
if marker not in s:
    s+='''
// PHASE2_CPU_LSC_PROBE — generated diagnostic copy, never linked into the APK.
extern "C" void rawAuditCpuLsc(const float* map,std::size_t count,int cols,int rows,int w,int h,float* out) {
    IspFrameMetadata meta;meta.lensShadingFromMetadata=true;
    meta.lensShadingColumns=cols;meta.lensShadingRows=rows;
    meta.lensShadingMap.assign(map,map+count);
    LinearFloatRaw raw;raw.mosaic=cv::Mat(h,w,CV_32FC1,cv::Scalar(.01f));
    raw.info.sensorCfaPattern=3;raw.info.effectiveCfaPattern=3;
    applyLensShadingToJpegRaw(raw,meta);
    std::copy(raw.mosaic.ptr<float>(),raw.mosaic.ptr<float>()+size_t(w)*h,out);
}
''';p.write_text(s)
sdk=Path.home()/'AppData/Local/Android/Sdk';adb=sdk/'platform-tools/adb.exe'
def run(*args):subprocess.run(list(map(str,args)),check=True)
run(sdk/'cmake/3.22.1/bin/cmake.exe','--build',b/'native','-j','6')
native=r/'native';exe=native/'cpu-gpu-regression'
run(sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe','--target=aarch64-linux-android29','-std=c++17','-O2','-DBNCAM_TEST_CPU_LSC','-I',repo/'app/src/main/cpp','-I',repo/'opencv/native/jni/include',repo/'app/src/test/cpp/RawQualityRegression.cpp','-L',b/'native','-L',native,'-lbncam','-lopencv_java4','-lc++_shared','-o',exe)
remote='/data/local/tmp/bncam-phase2-parity';run(adb,'shell','mkdir','-p',remote)
for f in [exe,b/'native/libbncam.so',native/'libopencv_java4.so',native/'libc++_shared.so']:run(adb,'push',f,remote+'/'+f.name)
run(adb,'shell','chmod','755',remote+'/cpu-gpu-regression');maps=[]
for id in json.loads((r/'fixture-ids.json').read_text()):
    dest=remote+'/'+id+'.txt';run(adb,'push',r/'fixtures'/id/'gain-test.txt',dest);maps.append(dest)
out=subprocess.run([str(adb),'shell','env','LD_LIBRARY_PATH='+remote,remote+'/cpu-gpu-regression',*maps],capture_output=True,text=True)
(r/'metrics/actual-cpu-gpu-parity.txt').write_text(out.stdout+out.stderr)
print(out.stdout);out.check_returncode()
