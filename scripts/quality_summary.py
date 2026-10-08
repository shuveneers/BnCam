"""Compact measured scorecard. No automatic quality winner or artifact attribution."""
import argparse
import json
import pathlib

def summarize(collection, output):
    collection=pathlib.Path(collection);data=json.loads(collection.read_text())
    rows=[]
    for folder in data['captures']:
        root=pathlib.Path(folder)
        m=json.loads((root/'manifest.json').read_text());r=json.loads((root/'raw-metadata.json').read_text())
        analysis=root/'manual-roi-analysis/analysis.json'
        if not analysis.exists():analysis=root/'analysis/analysis.json'
        a=json.loads(analysis.read_text())['stages']['FINAL_JPEG']
        row=dict(captureId=m['captureId'],repetition=m['repetition'],rawSha256=m['rawSha256'],
                 cameraId=m['cameraId'],source=m['frameSource'],profile=r['recipe']['activeProfileIdentifier'],
                 profileHash=r['recipe']['profileVersionHash'],iso=r['effectiveIso'],exposureNs=r['effectiveExposureNs'],
                 aeState=r['aeState'],afState=r['afState'],focusDistance=r['focusDistance'],
                 jpegDeterminism=m['jpegDeterminism'],dimensions=a['dimensions'],differences=a['differences'],modes={},rois={})
        for mode,metrics in a['modes'].items():
            clip=metrics['clipping']
            row['modes'][mode]=dict(lowPercentRgb=[c['percent'] for c in clip['low']['perChannel']],
                                   highPercentRgb=[c['percent'] for c in clip['high']['perChannel']],
                                   lowAllChannels=clip['low']['allChannels'],highAllChannels=clip['high']['allChannels'])
        for name,roi in a['rois'].items():
            if name=='position_center':continue
            row['rois'][name]=dict(spec=roi['spec'],modes={mode:{k:v for k,v in metrics.items()
                if k in ['channels','lumaProxy','redMinusGreen','blueMinusGreen','highFrequencyRedMinusGreen',
                         'highFrequencyBlueMinusGreen','gradientMagnitude','edge']} for mode,metrics in roi['metrics'].items()})
        thermal=collection.parent/f"thermal-{m['repetition']}.json"
        if thermal.exists():
            import re
            t=json.loads(thermal.read_text())
            row['thermal']={k:next(iter(re.findall(r'(?m)^Thermal Status: (\d+)',v)),None) for k,v in t.items()}
        rows.append(row)
    identities={(r['cameraId'],r['source'],r['profileHash']) for r in rows}
    result=dict(scene=data['scene'],lensRole=data['lensRole'],captureCount=len(rows),captures=rows,
        consistentCameraSourceProfile=len(identities)==1,distinctRawInputs=len({r['rawSha256'] for r in rows}),
        scope='Frozen three-mode production JPEG replays on independently captured RAW frames; not independent live shutters per mode',
        classification='UNKNOWN',qualityWinner='NOT ESTABLISHED',
        limitations=['No scene-independent noise metric from spatial variance','No artifact attribution from JPEG differences alone',
                     'No absolute color accuracy without characterized reference/illumination','Manual ROIs and physical scene qualification required'])
    pathlib.Path(output).write_text(json.dumps(result,indent=2,allow_nan=False))
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--collection',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();summarize(a.collection,a.output)
