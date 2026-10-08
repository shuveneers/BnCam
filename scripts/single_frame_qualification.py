"""ADB qualification and evidence analysis. Python standard library only.

No scene is classified from pixel appearance. A physical scene needs an operator declaration.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import pathlib
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/single-frame-qualification'

def save(name, data):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')

def debt(directory):
    """Conservative classification: uncertainty is explicit, never inferred away to green tests."""
    failures, groups = [], collections.defaultdict(list)
    for path in sorted(pathlib.Path(directory).glob('TEST-*.xml')):
        for test in ET.parse(path).getroot().findall('testcase'):
            failure = test.find('failure')
            if failure is None:
                failure = test.find('error')
            if failure is None:
                continue
            name = test.get('classname') + '.' + test.get('name')
            stack = failure.text or ''
            # One assertion site groups repetitions without pretending all AssertionErrors share a cause.
            site = next((line.strip() for line in stack.splitlines() if 'at com.bncam.' in line), name)
            category, reason, evidence = 'UNCERTAIN', 'Contract and root cause require individual investigation.', []
            if re.search(r'Neural|Hdr|Night|MultiFrame|Multiframe|TemporalFusion', name, re.I):
                category = 'OUT_OF_SCOPE'
                reason = 'Named Neural/multi-frame/HDR/night contract; excluded by this phase.'
                evidence = [str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)]
            if 'PostPhase10HardwareShutterInputSourceContractTest.foreground' in name:
                category, reason = 'STALE_TEST', 'Unit bus emission was replaced by typed, accounted requestCapture; hardware still routes through the same bus.'
                evidence = ['app/src/main/java/com/bncam/MainActivity.kt:CameraEventBus.requestCapture']
            if 'Phase1ASingleYuvLifecycleContractTest.captureExecutionIsNotOwned' in name:
                category, reason = 'STALE_TEST', 'Substring extraction missed the typed lambda declaration and scanned the whole screen; lifecycleOwner.lifecycleScope ownership remains.'
                evidence = ['app/src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt:triggerCaptureSequence']
            if name.endswith('DemosaicNativeContractTest.nativeValidationCoversMalvarMenonResolverAndOddCfaShifts'):
                category, reason = 'STALE_TEST', 'Expects qualityForcesMenon/autoDetailMenon; stable slot 2 now resolves to AMaZE and Auto uses regional Malvar/AMaZE blend.'
                evidence = ['app/src/main/cpp/native-lib.cpp:legacySlot2ForcesAmaze',
                            'app/src/main/cpp/Demosaic.cpp:auto_hybrid_region_aware_gpu',
                            'app/src/main/java/com/bncam/core/quality/DemosaicMode.kt:legacy persisted names']
            group_id = hashlib.sha256(site.encode()).hexdigest()[:12]
            entry = dict(test=name, category=category, rootCauseGroup=group_id, reason=reason,
                         evidence=evidence, assertionSite=site, failureType=failure.get('type'), message=failure.get('message'),
                         stack=stack, baseline='work/qualification-baseline-jvm.log')
            groups[group_id].append(name)
            failures.append(entry)
    counts = collections.Counter(f['category'] for f in failures)
    save('jvm-debt.json', dict(total=len(failures), counts=dict(counts), failures=failures,
                             groups=dict(groups), grouping='ASSERTION_SITE; unresolved groups are not proven root causes'))
    print('JVM debt', len(failures), dict(counts))

def percentile(values, p):
    values = sorted(values)
    position = (len(values)-1)*p
    lo, hi = math.floor(position), math.ceil(position)
    return values[lo]+(values[hi]-values[lo])*(position-lo)

def stats(values):
    return dict(n=len(values), min=min(values), median=statistics.median(values), mean=statistics.mean(values),
                p90=percentile(values,.9), max=max(values), sd=statistics.stdev(values) if len(values)>1 else 0)

def benchmark(directory):
    directory = pathlib.Path(directory)
    rows = list(csv.DictReader((directory/'warm-benchmark.csv').open()))
    by_mode = collections.defaultdict(list)
    for row in rows:
        if row['warmup'] == '1':
            continue
        detail = (directory/f"warm-{row['round']}-{row['mode']}.txt").read_text()
        values = dict(re.findall(r'(\w+)\s*=\s*([^;\n]+)', detail))
        row['nativeTiming'] = {key: float(value) for key,value in values.items()
                               if key.endswith('Ms') and re.fullmatch(r'[\d.eE+-]+',value.strip())}
        by_mode[row['mode']].append(row)
    report = {}
    for mode, samples in by_mode.items():
        if len(samples)<10:
            raise ValueError('Insufficient measured samples: '+mode)
        thermal = [int(s['thermal']) for s in samples]
        thermal_after = [int(s.get('thermalAfter',-1)) for s in samples]
        report[mode] = dict(path={'0':'Auto Hybrid','1':'Malvar','2':'AMaZE'}[mode],
            timing={k:stats([float(s[k]) for s in samples]) for k in ['normalizeMs','pipelineMs','totalMs']},
            nativeTiming={k:stats([s['nativeTiming'][k] for s in samples if k in s['nativeTiming']])
                          for k in sorted(set().union(*(s['nativeTiming'].keys() for s in samples)))},
            thermal=thermal, thermalAfter=thermal_after, thermallyInfluenced=any(t>0 for t in thermal+thermal_after),
            thermalUnavailable=any(t<0 for t in thermal+thermal_after),
            bitexact=all(s['bitexact']=='1' for s in samples),
            memory={key:[int(s[key]) for s in samples] for key in ['rssBeforeKb','rssAfterKb','nativeBeforeBytes','nativeAfterBytes']},
            graphicsMemory='UNAVAILABLE in standalone native process; capture dumpsys meminfo separately',
            monotoneRssGrowth=all(int(b['rssAfterKb'])>int(a['rssAfterKb']) for a,b in zip(samples,samples[1:])))
        core = [s['nativeTiming'].get('totalRawIspCoreMs') for s in samples]
        if all(value is not None for value in core):
            # Disjoint outer boundaries. All other native *Ms fields remain nested observations.
            formatting = [max(0,float(s['pipelineMs'])-value) for s,value in zip(samples,core)]
            coordination = [max(0,float(s['totalMs'])-float(s['normalizeMs'])-float(s['pipelineMs'])) for s in samples]
            report[mode]['waterfall'] = dict(normalization=stats([float(s['normalizeMs']) for s in samples]),
                nativeCore=stats(core), postCoreIncludingDiagnostics=stats(formatting), coordination=stats(coordination),
                contract='These four outer categories reconcile total; nativeKernel/sync/stage durations are nested and must not be added again.')
            unknown = [s['nativeTiming'].get('rawIspUnattributedMs',float('inf')) for s in samples]
            report[mode]['needsFurtherInstrumentation'] = max(unknown+formatting)>=1000
        else:
            report[mode]['needsFurtherInstrumentation'] = True
        report[mode]['rankingQualified'] = not (report[mode]['thermallyInfluenced'] or report[mode]['thermalUnavailable'] or
                                                report[mode]['needsFurtherInstrumentation']) and report[mode]['bitexact']
    report['_fixture'] = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.iterdir()
                          if p.name in ['raw.bin','fixture.txt','recipe.json','capture-metadata.txt','lens-id.txt']}
    save('warm-benchmark-summary.json',report)
    print(json.dumps(report,indent=2))

class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial
        if not serial:
            devices = self.run('devices').decode().splitlines()[1:]
            available = [d.split()[0] for d in devices if len(d.split())>=2 and d.split()[1]=='device']
            if len(available)!=1:
                raise RuntimeError('Exactly one authorized device required, or pass --serial: '+repr(available))
            self.serial=available[0]

    def run(self, *args, timeout=60):
        prefix = [self.adb] + (['-s',self.serial] if self.serial else [])
        p = subprocess.run(prefix+list(map(str,args)), capture_output=True, timeout=timeout)
        if p.returncode:
            raise RuntimeError(p.stderr.decode(errors='replace')+p.stdout.decode(errors='replace'))
        return p.stdout

    def shell(self,*args):
        return self.run('shell',*args).decode(errors='replace')

    def records(self, file='capture_terminal.jsonl'):
        records=[]
        for suffix in ('.1', ''):
            try:
                raw=self.run('exec-out','run-as','com.bncam','cat',f'files/phase0/{file}{suffix}')
            except RuntimeError as error:
                if 'No such file or directory' in str(error):
                    continue
                raise
            # ADB exec-out does not transport the remote cat exit code on this device.
            if raw.startswith(b'cat: ') and b'No such file or directory' in raw:
                continue
            records.extend(json.loads(line) for line in raw.decode().splitlines() if line.strip())
        return records

    def capture(self, scene, count):
        # Allow camera/session startup after am start; readiness failures still count explicitly.
        time.sleep(5)
        before={(r['session'],r['captureId']) for r in self.records()}
        self.shell('am','broadcast','-a','com.bncam.SET_TEST_CONFIG','-n','com.bncam/.core.debug.DebugTestReceiver',
                   '--es','qualification_scene',scene)
        try:
            triggered=0
            for _ in range(count):
                self.shell('am','broadcast','-a','com.bncam.TRIGGER_CAPTURE','-n','com.bncam/.core.debug.DebugTestReceiver')
                triggered+=1
            deadline=time.monotonic()+120
            while True:
                records=[r for r in self.records() if (r['session'],r['captureId']) not in before and r['source']=='adb_explicit']
                if len(records)>=triggered or time.monotonic()>deadline:
                    break
                time.sleep(.5)
            identities=[(r['session'],r['captureId']) for r in records]
            counts=collections.Counter(r['status'] for r in records)
            summary=dict(scene=scene,triggered=triggered,routerAdmitted=sum(r['admitted'] for r in records),
                outcomes=dict(counts),reasons=dict(collections.Counter(r['reason'] for r in records)),
                exactAccounting=len(records)==triggered and len(set(identities))==len(identities),records=records)
            save(f'capture-{scene}.json',summary)
            evidence=self.records('qualification_evidence.jsonl')
            published_ids={r['captureId'] for r in records if r['status']=='PUBLISHED'}
            scope_started_ns=min((r['timestampNs'] for r in records),default=0)
            def in_scope(row):
                return (row.get('metrics',{}).get('qualificationScene')==scene and
                        int(row.get('startedElapsedRealtimeNs',0))>=scope_started_ns)
            evidence_deadline=time.monotonic()+15
            while not published_ids.issubset({int(r.get('captureTriggerId') or -1) for r in evidence
                    if in_scope(r)}) and time.monotonic()<evidence_deadline:
                time.sleep(.5)
                evidence=self.records('qualification_evidence.jsonl')
            scoped=[r for r in evidence if int(r.get('captureTriggerId') or -1) in published_ids
                    and in_scope(r)]
            save(f'evidence-{scene}.json',scoped)
            (OUT/f'memory-{scene}.txt').write_text(self.shell('dumpsys','meminfo','com.bncam'),encoding='utf-8')
            if not summary['exactAccounting']:
                raise RuntimeError('Terminal accounting does not close; see report')
            if any(r['status']=='FAILURE' and ('queue is full' in r['reason'] or 'processing_queue_full' in r['reason']) for r in records):
                raise RuntimeError('Queue admission rejection was misclassified as processing failure')
            if counts['PUBLISHED'] < 1:
                raise RuntimeError('No photo published in this scope; accounting alone is insufficient for camera smoke')
            if any('SINGLE_FRAME_ZSL' not in r['context'] for r in records if r['status']=='PUBLISHED'):
                raise RuntimeError('Published capture used another route; select Single Frame Photo before qualification')
            if {int(r.get('captureTriggerId') or -1) for r in scoped}!=published_ids or len(scoped)!=len(published_ids):
                raise RuntimeError('Published capture evidence missing or duplicated')
            checks=[]
            for row in scoped:
                metrics=row['metrics']
                raw=json.loads(metrics.get('qualification.raw','{}'))
                if not re.fullmatch('[0-9a-f]{64}',raw.get('rawSha256','')):
                    raise RuntimeError('Canonical RAW evidence absent; RAW_SENSOR/RAW10 capture required')
                required=['gitRevision','recipe','width','height','cfa','cropLeft','cropTop','effectiveExposureNs',
                          'effectiveIso','sensorTimestampNs','afState','aeState','focusDistance']
                if any(k not in raw for k in required) or raw['width']<=0 or raw['height']<=0:
                    raise RuntimeError('Incomplete RAW metadata evidence')
                uri=metrics.get('qualification.jpegUri','')
                if not uri.startswith('content://media/'):
                    raise RuntimeError('Published JPEG URI absent')
                actual=self.run('exec-out','content','read','--uri',uri)
                digest=hashlib.sha256(actual).hexdigest()
                if digest!=metrics.get('qualification.jpegSha256'):
                    raise RuntimeError('Published JPEG checksum does not match evidence')
                if int(metrics.get('qualification.outputWidth',0))<=0 or int(metrics.get('qualification.outputHeight',0))<=0:
                    raise RuntimeError('Published JPEG dimensions absent')
                checks.append(dict(captureTriggerId=row['captureTriggerId'],rawSha256=raw['rawSha256'],
                    jpegSha256=digest,jpegBytes=len(actual),jpegUri=uri,metadataKeysPresent=True,
                    rawChecksumIndependentReplay='NOT EXPORTED; digest calculated inside native-buffer ownership',
                    nullHalFields=[k for k in required if raw[k] is None]))
            save(f'evidence-checks-{scene}.json',checks)
            print(json.dumps({k:v for k,v in summary.items() if k!='records'},indent=2))
        finally:
            self.shell('am','broadcast','-a','com.bncam.SET_TEST_CONFIG','-n','com.bncam/.core.debug.DebugTestReceiver',
                       '--esn','qualification_scene')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['debt','benchmark','capture','stress'])
    p.add_argument('--directory',default=str(ROOT/'app/build/test-results/testDebugUnitTest'))
    p.add_argument('--adb',default=str(pathlib.Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    p.add_argument('--serial');p.add_argument('--scene',default='CURRENT_UNCONTROLLED')
    p.add_argument('--count',type=int)
    a=p.parse_args()
    if a.action=='debt':debt(a.directory)
    elif a.action=='benchmark':benchmark(a.directory)
    else:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',a.scene):p.error('Use a safe scene label')
        count=a.count or (100 if a.action=='stress' else 1)
        if not 1<=count<=1000:p.error('count must be 1..1000')
        Device(a.adb,a.serial).capture(a.scene,count)

if __name__=='__main__':main()
