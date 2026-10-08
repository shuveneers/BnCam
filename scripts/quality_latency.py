"""Measure ordinary live captures with quality replay explicitly disabled.

Qualification checks remain enabled and their nested overhead is reported.
Stages are sequential; native timings are nested and must not be added again.
This records thermal state, but does not claim a thermally gated benchmark.
"""
import argparse
import json
import pathlib
import time
import uuid
import single_frame_qualification as gate


def measure(device, output, count=3, expected_source='RAW10', expected_camera='2'):
    output = pathlib.Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    gate.OUT = output / 'capture-evidence'
    device.shell('am', 'broadcast', '-a', 'com.bncam.SET_TEST_CONFIG', '-n',
                 'com.bncam/.core.debug.DebugTestReceiver', '--esn', 'quality_scene')
    rows = []
    prefix = 'LATENCY_' + uuid.uuid4().hex[:8]
    for i in range(count):
        before = device.shell('dumpsys', 'thermalservice')
        scene = f'{prefix}_{i+1}'
        device.capture(scene, 1)
        after = device.shell('dumpsys', 'thermalservice')
        (output / f'thermal-{i+1}.json').write_text(json.dumps(dict(before=before, after=after), indent=2))
        evidence = json.loads((gate.OUT / f'evidence-{scene}.json').read_text())
        if len(evidence) != 1:
            raise RuntimeError('Expected one verified publication')
        row = evidence[0]
        metrics = row['metrics']
        if metrics.get('qualityReplayPath'):
            raise RuntimeError('Quality replay contaminated ordinary latency measurement')
        raw = json.loads(metrics['qualification.raw'])
        if raw['recipe']['frameSource'] != expected_source:
            raise RuntimeError('Unexpected source; latency evidence preserved')
        # Lens identity is taken from the actual RAW evidence, never the UI label.
        if str(raw['recipe']['lensIdentifier']) != expected_camera:
            raise RuntimeError('Unexpected camera; latency evidence preserved')
        rows.append(dict(captureTriggerId=row['captureTriggerId'], elapsedMs=row['elapsedMs'],
            sequentialStageDurationsMs=row['sequentialStageDurationsMs'],
            explicitNestedDurationsMs=row['explicitNestedDurationsMs'],
            invocationCounters=row['invocationCounters'],
            nativeTimingMs={k: v for k, v in metrics.items()
                            if k.startswith(('nativeRawIsp.', 'nativeRawInput.')) and k.endswith('Ms')},
            recipe=raw['recipe'], qualityReplayEnabled=False))
        (output / 'latency.json').write_text(json.dumps(dict(captures=rows,
            scope='Ordinary live capture plus qualification checksum overhead; thermal state recorded, not gated'), indent=2))
        print('Ordinary capture', i+1, 'elapsedMs', row['elapsedMs'], flush=True)
        if i+1 < count:
            time.sleep(5)
    return rows


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', required=True)
    p.add_argument('--source', choices=['RAW_SENSOR', 'RAW10'], default='RAW10')
    p.add_argument('--camera', default='2')
    p.add_argument('--adb', default=str(pathlib.Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    a = p.parse_args()
    measure(gate.Device(a.adb, a.serial), a.output, expected_source=a.source, expected_camera=a.camera)
