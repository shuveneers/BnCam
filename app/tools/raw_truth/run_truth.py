"""Execute isolated production GPU demosaic on immutable normalized fixtures via ADB."""
import argparse,subprocess,shutil,json
from pathlib import Path
p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('--ids',nargs='+',required=True);p.add_argument('--algorithms',nargs='+',default=['bilinear','malvar','amaze','neural_slot']);p.add_argument('--lsc',nargs='+',default=['off','on']);a=p.parse_args()
adb=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe';remote='/data/local/tmp/bncam-raw-truth'
def run(*args):subprocess.run(list(map(str,args)),check=True)
for id in a.ids:
    fixture=a.root/'fixtures'/id;dest=a.root/'demosaic'/id;dest.mkdir(exist_ok=True)
    job=remote+'/'+id;run(adb,'shell','mkdir','-p',job)
    run(adb,'push',fixture/'golden.txt',job+'/golden.txt')
    for lsc in a.lsc:
        algorithms=a.algorithms if lsc=='off' else ['malvar']
        run(adb,'push',fixture/f'normalized-{lsc}.f32',job+'/normalized.f32')
        for alg in algorithms:
            name=alg+'-lsc-'+lsc
            if (dest/(name+'.rgb.f32')).exists():continue
            run(adb,'shell','env','LD_LIBRARY_PATH='+remote,remote+'/demosaic-replay',job,name,dict(bilinear=0,malvar=1,amaze=4,neural_slot=3)[alg])
            for ext in ['rgb.f32','jpg','status.txt']:run(adb,'pull',job+'/'+name+'.'+ext,dest/(name+'.'+ext))
            print(id,name,flush=True)
