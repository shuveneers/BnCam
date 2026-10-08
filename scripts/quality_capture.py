"""Collect an operator-declared physical scene without changing camera/image settings.

Run with the numpy/Pillow Python runtime to analyze the collected exact-RAW replays.
Large data stays in ignored work/quality/. No ROI is semantically invented.
"""
import argparse
import io
import json
import pathlib
import re
import subprocess
import tarfile
import time
import uuid
import single_frame_qualification as gate
import quality_analyzer as analyzer

TRANSIENT_READINESS = {
    'NO_ACTIVE_PIPELINE', 'PIPELINE_RESET_IN_PROGRESS', 'CAPTURE_SESSION_NOT_READY',
    'SESSION_GENERATION_NOT_CONFIGURED', 'READINESS_COLD_EMPTY', 'READINESS_FILLING',
    'READINESS_METADATA_UNSTABLE', 'READINESS_AF_SCANNING', 'READINESS_AE_AWB_CONVERGING',
    'LEASABLE_WARM_BUFFER_NOT_READY', 'WARM_BUFFER_STREAM_STALE',
}


def capture_when_ready(device, scope, retries):
    # The existing capture admission gate is the authoritative readiness probe.
    # A refused readiness attempt cannot render/publish a frame and is preserved.
    for attempt in range(retries+1):
        attempt_scope=f'{scope}_a{attempt+1}'
        try:
            device.capture(attempt_scope,1)
            return attempt_scope
        except RuntimeError:
            report=gate.OUT/f'capture-{attempt_scope}.json'
            failure=json.loads(report.read_text()) if report.exists() else {}
            records=failure.get('records',[])
            reason=records[0].get('reason','') if len(records)==1 else ''
            code=reason.removeprefix('pipeline_not_ready:').split(' ',1)[0]
            transient=(failure.get('exactAccounting') is True and len(records)==1
                and records[0].get('status')=='FAILURE'
                and reason.startswith('pipeline_not_ready:') and code in TRANSIENT_READINESS)
            if not transient or attempt==retries:raise
            print(f'Pipeline not ready; waiting before retry {attempt+1}/{retries}',flush=True)
            time.sleep(5)


def collect(device, scene, role, repetitions, destination, expected_source='RAW_SENSOR', expected_camera=None,
            transient_retries=0, on_frame=None):
    destination=pathlib.Path(destination).resolve()
    destination.mkdir(parents=True,exist_ok=True)
    gate.OUT=destination/'capture-evidence'
    prefix=f'{scene}_{uuid.uuid4().hex[:8]}'
    captured=[]
    frozen_profile_identity=None
    repo=pathlib.Path(__file__).resolve().parents[1]
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip()
    patch=subprocess.check_output(['git','diff','--binary'],cwd=repo)
    (destination/'source-working-tree.patch').write_bytes(patch)
    apk=repo/'app/build/outputs/apk/debug/app-debug.apk'
    provenance=dict(gitRevision=revision,workingTreePatchSha256=analyzer.sha(destination/'source-working-tree.patch'),
                    apkSha256=analyzer.sha(apk),scripts={p.name:analyzer.sha(p) for p in
                    [pathlib.Path(__file__),repo/'scripts/quality_analyzer.py',repo/'scripts/quality_gpu_diagnostic.py']})
    helper=repo/'app/src/main/java/com/bncam/core/debug/QualityCaptureReplay.kt'
    provenance['qualityReplaySourceSha256']=analyzer.sha(helper)
    (destination/'QualityCaptureReplay.kt').write_bytes(helper.read_bytes())
    package_path=device.shell('pm','path','com.bncam').strip().splitlines()[0]
    if not package_path.startswith('package:/'):raise RuntimeError('Installed APK path unavailable')
    installed_sha=device.shell('sha256sum',package_path.removeprefix('package:')).split()[0]
    if installed_sha!=provenance['apkSha256']:raise RuntimeError('Installed APK differs from local measurement build')
    provenance['installedApkSha256']=installed_sha
    (destination/'build-provenance.json').write_text(json.dumps(provenance,indent=2))
    (destination/'camera-service.txt').write_text(device.shell('dumpsys','media.camera'),encoding='utf-8')
    try:
        for repetition in range(1,repetitions+1):
            scope=f'{prefix}_{repetition}'
            thermal_before=device.shell('dumpsys','thermalservice')
            device.shell('am','broadcast','-a','com.bncam.SET_TEST_CONFIG','-n','com.bncam/.core.debug.DebugTestReceiver',
                         '--es','quality_scene',scene,'--es','quality_lens_role',role,
                         '--ei','quality_repetition',repetition)
            scope=capture_when_ready(device, scope, transient_retries)
            thermal_after=device.shell('dumpsys','thermalservice')
            (destination/f'thermal-{repetition}.json').write_text(json.dumps(dict(before=thermal_before,after=thermal_after),indent=2))
            evidence=json.loads((gate.OUT/f'evidence-{scope}.json').read_text())
            if len(evidence)!=1:raise RuntimeError('Expected exactly one capture evidence row')
            remote=evidence[0]['metrics'].get('qualityReplayPath','')
            if not re.fullmatch(r'quality/[0-9a-f-]{36}',remote):
                raise RuntimeError('Quality export missing; inspect capture failure and device logcat')
            # exec-out is binary; tar validity and checksums independently validate remote success.
            archive=device.run('exec-out','run-as','com.bncam','tar','-C','files','-cf','-',remote,timeout=120)
            with tarfile.open(fileobj=io.BytesIO(archive),mode='r:') as tar:
                tar.extractall(destination,filter='data')
            root=destination/remote
            manifest=json.loads((root/'manifest.json').read_text())
            if manifest['sceneId']!=scene or manifest['repetition']!=repetition:
                raise RuntimeError('Export identity differs from requested measurement')
            if manifest['frameSource']!=expected_source:
                (root/'qualification-status.json').write_text(json.dumps(dict(controlledSceneEligible=False,
                    reason='SOURCE_MISMATCH',expectedSource=expected_source,actualSource=manifest['frameSource']),indent=2))
                raise RuntimeError(f"Expected {expected_source}, captured {manifest['frameSource']}; preserved export, further captures refused")
            if expected_camera is not None and manifest['cameraId']!=expected_camera:
                raise RuntimeError(f"Expected camera {expected_camera}, captured {manifest['cameraId']}; further captures refused")
            raw_metadata=json.loads((root/'raw-metadata.json').read_text())
            if (raw_metadata['rawSha256']!=manifest['rawSha256']
                    or raw_metadata['recipe']['frameSource']!=expected_source
                    or (expected_camera is not None and
                        raw_metadata['recipe']['lensIdentifier']!=expected_camera)):
                raise RuntimeError('Canonical RAW recipe/hash differs from requested capture identity')
            profile_identity=(manifest['cameraId'],raw_metadata['recipe']['activeProfileIdentifier'],
                              raw_metadata['recipe']['profileVersionHash'])
            if frozen_profile_identity is not None and profile_identity!=frozen_profile_identity:
                raise RuntimeError('Camera/profile changed within the repeat series; further captures refused')
            frozen_profile_identity=profile_identity
            actual_algorithms={'Malvar':'MALVAR_2004','AMaZE':'AMAZE','Auto Hybrid':'AUTO_HYBRID'}
            for entry in manifest['outputs']:
                stats=dict(part.strip().split('=',1) for part in entry['nativeStats'].split(';') if '=' in part)
                if stats.get('phase16DemosaicResolved')!=actual_algorithms[entry['mode']] or stats.get('phase16DemosaicFallback')!='false':
                    raise RuntimeError('Requested algorithm was not actually executed without fallback; inspect native stats')
            verify_replays(root, manifest)
            if any(json.loads((pathlib.Path(p)/'manifest.json').read_text())['rawSha256']==manifest['rawSha256'] for p in captured):
                raise RuntimeError('Repeated canonical RAW; independent physical frame required')
            if on_frame is not None:on_frame(device,root,evidence[0])
            analyzer.compare(root/'manifest.json',root/'analysis')
            analyzer.raw_analysis(root/'raw.bin',root/'raw-metadata.json',root/'raw-analysis')
            captured.append(str(root))
            (destination/'collection.json').write_text(json.dumps(dict(scene=scene,lensRole=role,
                operatorDeclaredPhysicalSetup=True,captures=captured),indent=2))
            print('Verified RAW + three modes + bitexact replays:',root,flush=True)
            # Allow live preview and exposure/focus to settle; never alter policies or tuning.
            if repetition<repetitions:time.sleep(5)
    finally:
        device.shell('am','broadcast','-a','com.bncam.SET_TEST_CONFIG','-n','com.bncam/.core.debug.DebugTestReceiver',
                     '--esn','quality_scene')
    return captured


def verify_replays(root, manifest):
    """Independently verify both saved renders, not only the device success label."""
    if manifest.get('sameRawReplaysPerMode')!=2 or manifest.get('jpegDeterminism')!='BITEXACT':
        raise RuntimeError('Exactly two bitexact replays per algorithm required')
    if sorted(e['mode'] for e in manifest['outputs'])!=sorted(analyzer.MODES):
        raise RuntimeError('Exactly Malvar, AMaZE and Auto Hybrid outputs required')
    algorithms=dict(zip(analyzer.MODES,['MALVAR_2004','AMAZE','AUTO_HYBRID']))
    for entry in manifest['outputs']:
        first=root/entry['file']
        if analyzer.sha(first)!=entry['sha256']:raise RuntimeError('Replay checksum mismatch')
        second=root/entry['file'].replace('-replay-0.jpg','-replay-1.jpg')
        if second==first or analyzer.sha(second)!=entry['sha256']:
            raise RuntimeError('Duplicate replay is not bitexact')
        for path in (first,second):
            stats=(root/(path.stem+'-native.txt')).read_text()
            fields=dict(p.strip().split('=',1) for p in stats.split(';') if '=' in p)
            if (fields.get('phase16DemosaicResolved')!=algorithms[entry['mode']]
                    or fields.get('phase16DemosaicFallback')!='false'):
                raise RuntimeError('Algorithm mismatch or fallback in saved replay')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=str(pathlib.Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    p.add_argument('--serial',required=True);p.add_argument('--scene',required=True,choices=list('ABCDEFGH'))
    p.add_argument('--lens-role',required=True,choices=['main','ultrawide','tele','front'])
    p.add_argument('--repetitions',type=int,default=3);p.add_argument('--output',required=True)
    p.add_argument('--expected-source',choices=['RAW_SENSOR','RAW10'],default='RAW_SENSOR')
    p.add_argument('--expected-camera-id')
    a=p.parse_args()
    if not 1<=a.repetitions<=10:p.error('repetitions must be 1..10')
    collect(gate.Device(a.adb,a.serial),a.scene,a.lens_role,a.repetitions,a.output,a.expected_source,a.expected_camera_id)

if __name__=='__main__':main()
