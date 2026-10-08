"""Verify frozen selected-mode replay against the actual MediaStore publication.

Checks encoded-file SHA against capture evidence and compares decoded stored RGB
pixels. EXIF orientation is reported explicitly, never silently transformed.
"""
import argparse
import io
import json
import pathlib
import numpy as np
from PIL import Image
import quality_analyzer as q
from single_frame_qualification import Device

def verify(device, collection):
    collection=pathlib.Path(collection);dataset=json.loads(collection.read_text())
    evidence=[]
    for path in (collection.parent/'capture-evidence').glob('evidence-*.json'):
        if path.name.startswith('evidence-checks-'):continue
        evidence.extend(json.loads(path.read_text()))
    reports=[]
    for folder in dataset['captures']:
        root=pathlib.Path(folder);remote='quality/'+root.name
        rows=[r for r in evidence if r.get('metrics',{}).get('qualityReplayPath')==remote]
        if len(rows)!=1:raise ValueError('Missing or duplicated publication evidence for '+remote)
        metrics=rows[0]['metrics'];raw=json.loads(metrics['qualification.raw'])
        mode=raw['recipe']['methods']['demosaic']['requested']
        manifest=json.loads((root/'manifest.json').read_text())
        if manifest['rawSha256']!=raw['rawSha256']:
            raise ValueError('Publication evidence and replay use different canonical RAW')
        outputs=[e for e in manifest['outputs'] if e['mode']==mode]
        if len(outputs)!=1:raise ValueError('Selected production mode absent from frozen replays')
        encoded=device.run('exec-out','content','read','--uri',metrics['qualification.jpegUri'])
        (root/'published.jpg').write_bytes(encoded)
        digest=q.sha(root/'published.jpg')
        if digest!=metrics['qualification.jpegSha256']:raise ValueError('Published file no longer matches capture evidence')
        with Image.open(io.BytesIO(encoded)) as im:
            if im.mode!='RGB':raise ValueError('Unexpected publication pixel format')
            actual=np.asarray(im).copy();orientation=im.getexif().get(274,1)
        with Image.open(root/outputs[0]['file']) as im:replay=np.asarray(im).copy()
        report=dict(captureId=manifest['captureId'],selectedMode=mode,publishedSha256=digest,
                    rawSha256=manifest['rawSha256'],publicationExifOrientation=orientation,
                    publishedDimensions=list(actual.shape[:2][::-1]),replayDimensions=list(replay.shape[:2][::-1]),
                    sameDecodedStoredPixels=actual.shape==replay.shape and np.array_equal(actual,replay))
        if actual.shape==replay.shape:
            delta=np.abs(actual.astype(np.int16)-replay.astype(np.int16))
            report.update(maxChannelDifference8bit=int(delta.max()),meanChannelDifference8bit=float(delta.mean()))
        (root/'publication-replay-check.json').write_text(json.dumps(report,indent=2))
        if not report['sameDecodedStoredPixels']:
            raise ValueError('Published JPEG pixels differ from selected replay; preserved mismatch report')
        reports.append(report)
    (collection.parent/'publication-replay-checks.json').write_text(json.dumps(reports,indent=2))
    print(json.dumps(reports,indent=2))
    return reports

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--collection',required=True)
    p.add_argument('--adb',default=str(pathlib.Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    a=p.parse_args();verify(Device(a.adb,a.serial),a.collection)
