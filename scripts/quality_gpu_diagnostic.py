"""Bounded, isolated GPU demosaic diagnostic on a real canonical RAW crop.

This does NOT reproduce production raw-finalize, Spectra/noise, lens shading, WB,
color or tone. It reuses the existing device numerical harness without APK changes.
"""
import argparse
import hashlib
import json
import pathlib
import uuid
import numpy as np
import quality_analyzer as q
from single_frame_qualification import Device

def diagnose(device, source, output, x=None, y=None):
    source=pathlib.Path(source);output=pathlib.Path(output).resolve()
    output.mkdir(parents=True,exist_ok=True)
    m=json.loads((source/'raw-metadata.json').read_text())
    if q.sha(source/'raw.bin')!=m['rawSha256']:raise ValueError('Parent RAW checksum mismatch')
    width,height=m['width'],m['height'];w,h=min(257,width),min(193,height)
    x=(width-w)//2 if x is None else x;y=(height-h)//2 if y is None else y
    if x<0 or y<0 or x+w>width or y+h>height:raise ValueError('Diagnostic crop outside RAW')
    raw=np.memmap(source/'raw.bin',dtype='<u2',mode='r',shape=(height,width))
    crop=np.ascontiguousarray(raw[y:y+h,x:x+w]);crop.tofile(output/'raw.bin')
    cfa=m['cfa']^((y&1)*2+(x&1))
    canonical=[[0,1,2,3],[1,0,3,2],[2,3,0,1],[3,2,1,0]][cfa]
    black=list(map(float,m['blackLevel'].split(',')))
    positional=[black[c] for c in canonical]
    white=m['whiteLevel'];iso=m['effectiveIso'];exposure=m['effectiveExposureNs']/1e6
    # The numerical action consumes only RAW/black/white/CFA/ISO. Matrix is deliberately unused.
    fixture=' '.join(map(str,[w,h,cfa,iso,1,exposure,white,*positional,1,1,1,1,1,0,0,0,1,0,0,0,1]))
    (output/'fixture.txt').write_text(fixture)
    identity=json.loads((source/'manifest.json').read_text())
    remote='/data/local/tmp/bncam-quality/'+uuid.uuid4().hex
    device.shell('mkdir','-p',remote)
    for name in ['raw.bin','fixture.txt']:device.run('push',output/name,remote+'/')
    result=device.run('shell',f'cd /data/local/tmp/bncam-validation && LD_LIBRARY_PATH=. ./bncam_single_frame_qualification {remote} numerical',timeout=120)
    (output/'device-numerical.log').write_bytes(result)
    for name in ['hybrid-mask-evidence.f32','hybrid-numerical.json']:
        device.run('pull',remote+'/'+name,output/name)
    mask=np.fromfile(output/'hybrid-mask-evidence.f32',dtype='<f4').reshape(h,w,20)
    meta=json.loads((output/'hybrid-numerical.json').read_text())
    meta.update(scope='ISOLATED_GPU_DEMOSAIC_DIAGNOSTIC_NOT_PRODUCTION_ISP_STAGE',
                parentRawSha256=m['rawSha256'],cropSensorCoordinates=[x,y,w,h],effectiveCfa=cfa,
                sceneId=identity['sceneId'],lensRole=identity['lensRole'],cameraId=identity['cameraId'])
    (output/'hybrid-numerical.json').write_text(json.dumps(meta,indent=2))
    rawsha=q.sha(output/'raw.bin');controls=hashlib.sha256(fixture.encode()).hexdigest()
    entries=[]
    for mode,offset in zip(q.MODES,[8,11,14]):
        file=f'mode-{offset}.npy';np.save(output/file,np.ascontiguousarray(mask[:,:,offset:offset+3]))
        entries.append(dict(mode=mode,file=file,sha256=q.sha(output/file),stage='ISOLATED_DIRECT_DEMOSAIC',
                            domain='NORMALIZED_LINEAR_RGB',coordinateFrame='RAW_CROP_PIXELS',
                            rawSha256=rawsha,controlsSha256=controls))
    manifest={k:identity[k] for k in ['captureId','gitRevision','cameraId','lensRole','sceneId','repetition','frameSource']}
    manifest.update(rawSha256=rawsha,rawFile='raw.bin',parentRawSha256=m['rawSha256'],outputs=entries,
                    scope=meta['scope'],cropSensorCoordinates=[x,y,w,h])
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2))
    raw_meta=dict(m,width=w,height=h,cfa=cfa,rawSha256=rawsha,parentRawSha256=m['rawSha256'],
                  cropSensorCoordinates=[x,y,w,h],scope=meta['scope'])
    (output/'raw-metadata.json').write_text(json.dumps(raw_meta,indent=2))
    normalized=np.empty((h,w),np.float32)
    for p,b in enumerate(positional):normalized[p//2::2,p%2::2]=np.clip((crop[p//2::2,p%2::2].astype(np.float32)-b)/max(1,white-b),0,1)
    np.save(output/'isolated-normalized-mosaic.npy',normalized)
    q.hybrid(output/'hybrid-mask-evidence.f32',output/'hybrid-numerical.json',output/'mask-analysis')
    q.compare(output/'manifest.json',output/'rgb-analysis')
    q.raw_analysis(output/'raw.bin',output/'raw-metadata.json',output/'raw-analysis')
    print(json.dumps(meta,indent=2))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True);p.add_argument('--source',required=True);p.add_argument('--output',required=True)
    p.add_argument('--x',type=int);p.add_argument('--y',type=int)
    p.add_argument('--adb',default=str(pathlib.Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    a=p.parse_args();diagnose(Device(a.adb,a.serial),a.source,a.output,a.x,a.y)

if __name__=='__main__':main()
