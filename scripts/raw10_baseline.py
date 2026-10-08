"""Final bounded pre-Neural-Bn baseline: camera 2, RAW10, A/B/D, three valid inputs."""
import argparse
import contextlib
import io
import json
import pathlib
import quality_analyzer as q
import quality_capture as capture
import quality_publication_check as publication
from single_frame_qualification import Device


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False), encoding='utf-8')


def verify_publication(device, root, evidence):
    # Existing verifier resolves the exact MediaStore publication from capture evidence.
    collection = root.parents[1] / 'candidate-collection.json'
    write_json(collection, dict(captures=[str(root)]))
    with contextlib.redirect_stdout(io.StringIO()):
        reports = publication.verify(device, collection)
    if len(reports) != 1 or not reports[0]['sameDecodedStoredPixels']:
        raise RuntimeError('Publication did not match selected replay')


def scorecard(directory, rois=None):
    collection = json.loads((directory / 'collection.json').read_text(encoding='utf-8'))
    if collection['scene'] not in ('A', 'B', 'D') or len(collection['captures']) != 3:
        raise ValueError('Exactly three A/B/D frames required')
    frames = []
    publication_checks = []
    hashes = set()
    for folder in collection['captures']:
        root = pathlib.Path(folder)
        manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
        metadata = json.loads((root / 'raw-metadata.json').read_text(encoding='utf-8'))
        if manifest['cameraId'] != '2' or manifest['frameSource'] != 'RAW10':
            raise ValueError('Camera 2 / RAW10 required')
        capture.verify_replays(root, manifest)
        if q.sha(root / 'raw.bin') != manifest['rawSha256']:
            raise ValueError('Canonical RAW checksum mismatch')
        hashes.add(manifest['rawSha256'])
        check = json.loads((root / 'publication-replay-check.json').read_text())
        if not check['sameDecodedStoredPixels'] or check['rawSha256'] != manifest['rawSha256']:
            raise ValueError('Publication validation absent or mismatched')
        publication_checks.append(check)
        if rois:
            analysis = q.compare(root / 'manifest.json', root / 'baseline-roi-analysis', rois)
        else:
            analysis = json.loads((root / 'analysis/analysis.json').read_text())
        stage = analysis['stages']['FINAL_JPEG']
        regions = {}
        for name, region in stage['rois'].items():
            regions[name] = dict(kind=region['spec']['kind'], box=region['spec']['box'], modes={})
            for mode, metrics in region['metrics'].items():
                regions[name]['modes'][mode] = dict(
                    detailGradientMean=metrics.get('gradientMagnitude', {}).get('mean'),
                    edge=metrics.get('edge'),
                    highFrequencyChromaStdRG=metrics.get('highFrequencyRedMinusGreen', {}).get('std'),
                    highFrequencyChromaStdBG=metrics.get('highFrequencyBlueMinusGreen', {}).get('std'),
                    spatialLumaStd=metrics['lumaProxy']['std'])
        frames.append(dict(rawSha256=manifest['rawSha256'], captureId=manifest['captureId'],
            iso=metadata['effectiveIso'], exposureNs=metadata['effectiveExposureNs'],
            afState=metadata['afState'], focusDistance=metadata['focusDistance'],
            disagreement={pair: {k: values[k] for k in ('mean', 'p95', 'p99', 'max')}
                          for pair, values in stage['differences'].items()}, regions=regions))
    if len(hashes) != 3:
        raise ValueError('Three distinct physical RAW inputs required')
    identical = all(v['max'] == 0 for f in frames for v in f['disagreement'].values())
    conclusion = 'NO_MEANINGFUL_DIFFERENCE' if identical else 'INCONCLUSIVE'
    report = dict(schemaVersion=1, baseline='pre-Neural-Bn', scene=collection['scene'],
        cameraId='2', source='RAW10', validFrames=3, replaysPerMode=2,
        verification='PASS', conclusion=conclusion,
        reason=('All three modes have identical decoded pixels on all three RAW inputs.' if identical else
                'Measured rendition differences; no ground truth establishing retained detail, false colour or a quality winner.'),
        frames=frames,
        interpretation=dict(detail='Gradient is a detail proxy, also increased by noise/sharpening.',
            edgeZipper='Declared straight-edge profiles only; no automatic zipper diagnosis from mixed content.',
            falseColour='High-frequency chroma is a false-colour proxy only in declared neutral texture.',
            noiseAmplification='Spatial luma/chroma spread includes texture; not isolated sensor noise or causal amplification.',
            defaultRegions='Position-only center crop; never assumed neutral, flat or a straight edge.'),
        stop='Scene complete: no additional captures or analysis requested.')
    write_json(directory / 'scorecard.json', report)
    write_json(directory / 'publication-replay-checks.json', publication_checks)
    table = '| Same-RAW pair | Mean absolute RGB difference (range) | p99 (range) |\n| --- | --- | --- |\n'
    for pair in frames[0]['disagreement']:
        means = [f['disagreement'][pair]['mean'] for f in frames]
        p99 = [f['disagreement'][pair]['p99'] for f in frames]
        table += f'| {pair} | {min(means):.6f}–{max(means):.6f} | {min(p99):.6f}–{max(p99):.6f} |\n'
    (directory / 'scorecard.md').write_text(
        f"# RAW10 {collection['scene']} — {conclusion}\n\n"
        f"Camera 2; 3 distinct physical frames; 2 bitexact replays per mode; publication and RAW checks PASS.\n\n"
        f"{report['reason']}\n\n"
        + table + "\nMetrics and per-frame evidence: scorecard.json. Proxies are not absolute quality scores.\n",
        encoding='utf-8')
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--scene', required=True, choices=['A', 'B', 'D'])
    p.add_argument('--serial', default='AUWE025B03006422')
    p.add_argument('--output', help='New empty directory; default work/quality/pre-neural-bn-raw10/<scene>')
    p.add_argument('--rois', help='Optional existing manually declared ROI JSON; never guessed from scene name')
    p.add_argument('--adb', default=str(pathlib.Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    a = p.parse_args()
    directory = pathlib.Path(a.output or f'work/quality/pre-neural-bn-raw10/{a.scene}').resolve()
    if directory.exists() and any(directory.iterdir()):
        p.error('Output already contains evidence. Refusing additional captures/overwrite; use a new empty directory.')
    if a.rois:
        # Validate syntax before any shutter; geometric bounds are checked against actual images.
        data = json.loads(pathlib.Path(a.rois).read_text(encoding='utf-8-sig'))
        if not isinstance(data.get('rois'), list):p.error('ROI JSON requires a rois list')
    device = Device(a.adb, a.serial)
    print('Keep camera 2 / RAW10 and physical scene stable. Waiting for capture admission.', flush=True)
    capture.collect(device, a.scene, 'main', 3, directory, 'RAW10', '2',
                    transient_retries=5, on_frame=verify_publication)
    script_root = pathlib.Path(__file__).parent
    write_json(directory / 'runner-checksums.json', {name: q.sha(script_root / name) for name in
        ('raw10_baseline.py', 'runRaw10Baseline.ps1', 'quality_publication_check.py')})
    report = scorecard(directory, a.rois)
    print(f"{a.scene}: {report['conclusion']}; 3/3 valid frames. {directory / 'scorecard.md'}", flush=True)


if __name__ == '__main__':
    main()
