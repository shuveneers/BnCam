"""Reproducibly generate/build instrumentation for one fixture's sensor-coordinate ROIs."""
from pathlib import Path
import argparse,json,subprocess
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('id');a=p.parse_args();root=a.root.resolve();repo=Path(__file__).resolve().parents[3]
def run(*args):subprocess.run(list(map(str,args)),check=True)
import sys
build=root/'stages';run(sys.executable,repo/'app/tools/prepare_default_raw_stage_audit.py','--build',build)
rois=json.loads((root/'fixtures'/a.id/'rois.json').read_text());header=build/'src/RawStageAuditOnly.h';s=header.read_text();start=s.index(' const Patch patches[]=');end=s.index(';',start)
s=s[:start]+' const Patch patches[]={'+','.join('{"'+k+'",'+','.join(map(str,v))+'}' for k,v in rois.items())+'}'+s[end:];s=s.replace('int sx=y,sy=h-1-x;','int sx=x,sy=y;');header.write_text(s);(build/'rois.json').write_text(json.dumps(rois,indent=2))
sdk=Path.home()/'AppData/Local/Android/Sdk';cmake=sdk/'cmake/3.22.1/bin/cmake.exe'
run(cmake,'-S',build/'src','-B',build/'native','-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(sdk/'cmake/3.22.1/bin/ninja.exe'),'-DCMAKE_TOOLCHAIN_FILE='+str(sdk/'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_shared','-DOpenCV_SDK_PATH='+str(repo/'opencv'),'-DBNCAM_ENABLE_GPU_BENCHMARKS=1','-DCMAKE_BUILD_TYPE=Release')
run(cmake,'--build',build/'native','-j','6')
