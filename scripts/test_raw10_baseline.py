import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from PIL import Image
import quality_analyzer as q
import quality_capture as capture
import raw10_baseline as baseline
import quality_publication_check as publication


class BaselineTests(unittest.TestCase):
    def test_readiness_retry_is_bounded_and_preserves_attempts(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(capture.time, 'sleep'):
            with patch.object(capture.gate, 'OUT', pathlib.Path(temp)):
                class Device:
                    calls = []
                    def capture(self, scope, count):
                        self.calls.append(scope)
                        if len(self.calls) < 3:
                            (pathlib.Path(temp)/f'capture-{scope}.json').write_text(json.dumps(dict(
                                exactAccounting=True, records=[dict(status='FAILURE',
                                reason='pipeline_not_ready:NO_ACTIVE_PIPELINE expected=RAW10')])) )
                            raise RuntimeError('not ready')
                device = Device()
                self.assertEqual(capture.capture_when_ready(device, 'A', 5), 'A_a3')
                self.assertEqual(len(device.calls), 3)

    def test_mismatch_and_non_readiness_failures_never_retry(self):
        for reason in ['pipeline_not_ready:CAMERA_ID_MISMATCH', 'render_failed', 'queue_full']:
            with tempfile.TemporaryDirectory() as temp, patch.object(capture.gate, 'OUT', pathlib.Path(temp)):
                class Device:
                    calls = 0
                    def capture(self, scope, count):
                        self.calls += 1
                        (pathlib.Path(temp)/f'capture-{scope}.json').write_text(json.dumps(dict(
                            exactAccounting=True, records=[dict(status='FAILURE', reason=reason)])))
                        raise RuntimeError(reason)
                device = Device()
                with self.assertRaises(RuntimeError):capture.capture_when_ready(device, 'B', 5)
                self.assertEqual(device.calls, 1)

    def test_exhausted_readiness_stops(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(capture.gate, 'OUT', pathlib.Path(temp)), patch.object(capture.time, 'sleep'):
            class Device:
                calls = 0
                def capture(self, scope, count):
                    self.calls += 1
                    (pathlib.Path(temp)/f'capture-{scope}.json').write_text(json.dumps(dict(
                        exactAccounting=True, records=[dict(status='FAILURE', reason='pipeline_not_ready:READINESS_FILLING')])))
                    raise RuntimeError('not ready')
            device = Device()
            with self.assertRaises(RuntimeError):capture.capture_when_ready(device, 'D', 2)
            self.assertEqual(device.calls, 3)

    def fixture(self, parent, index, differing=False):
        root = parent / str(index)
        root.mkdir()
        (root/'raw.bin').write_bytes(bytes([index])*32)
        digest = q.sha(root/'raw.bin')
        outputs = []
        for i, (mode, resolved) in enumerate(zip(q.MODES, ['MALVAR_2004', 'AMAZE', 'AUTO_HYBRID'])):
            for replay in range(2):
                file = f'mode-{i}-replay-{replay}.jpg'
                Image.new('RGB', (16,16), (30+i*20 if differing else 30,40,50)).save(root/file)
                (root/f'mode-{i}-replay-{replay}-native.txt').write_text(
                    f'phase16DemosaicResolved={resolved};phase16DemosaicFallback=false')
            outputs.append(dict(mode=mode, file=f'mode-{i}-replay-0.jpg',
                sha256=q.sha(root/f'mode-{i}-replay-0.jpg'), rawSha256=digest,
                stage='FINAL_JPEG', domain='ENCODED_SRGB', controlsSha256='common'))
        manifest=dict(cameraId='2', frameSource='RAW10', sceneId='A', captureId=index,
            repetition=index, lensRole='main', rawSha256=digest, rawFile='raw.bin',
            sameRawReplaysPerMode=2, jpegDeterminism='BITEXACT', outputs=outputs)
        baseline.write_json(root/'manifest.json',manifest)
        baseline.write_json(root/'raw-metadata.json',dict(effectiveIso=100,effectiveExposureNs=10000,afState=2,focusDistance=0.))
        baseline.write_json(root/'publication-replay-check.json',dict(sameDecodedStoredPixels=True,rawSha256=digest))
        q.compare(root/'manifest.json',root/'analysis')
        return root

    def test_scorecard_does_not_invent_a_winner(self):
        for differing, expected in [(False,'NO_MEANINGFUL_DIFFERENCE'),(True,'INCONCLUSIVE')]:
            with tempfile.TemporaryDirectory() as temp:
                parent=pathlib.Path(temp)
                roots=[self.fixture(parent,i,differing) for i in range(1,4)]
                baseline.write_json(parent/'collection.json',dict(scene='A',captures=list(map(str,roots))))
                result=baseline.scorecard(parent)
                self.assertEqual(result['conclusion'],expected)
                self.assertEqual(result['validFrames'],3)
                # A changed second replay must fail despite a BITEXACT manifest label.
                (roots[0]/'mode-0-replay-1.jpg').write_bytes(b'changed')
                with self.assertRaises(RuntimeError):baseline.scorecard(parent)

    def test_fallback_is_checked_in_second_replay(self):
        with tempfile.TemporaryDirectory() as temp:
            root=self.fixture(pathlib.Path(temp),1)
            (root/'mode-1-replay-1-native.txt').write_text('phase16DemosaicResolved=AMAZE;phase16DemosaicFallback=true')
            with self.assertRaises(RuntimeError):capture.verify_replays(root,json.loads((root/'manifest.json').read_text()))

    def test_publication_mismatch_is_fatal(self):
        for wrong_pixels, wrong_raw in [(False,False),(True,False),(False,True)]:
            with tempfile.TemporaryDirectory() as temp:
                parent=pathlib.Path(temp)
                root=self.fixture(parent,1,True)
                manifest=json.loads((root/'manifest.json').read_text())
                file=root/('mode-1-replay-0.jpg' if wrong_pixels else 'mode-0-replay-0.jpg')
                encoded=file.read_bytes()
                evidence=parent/'capture-evidence'
                evidence.mkdir()
                baseline.write_json(evidence/'evidence-A.json',[dict(metrics={
                    'qualityReplayPath':'quality/1',
                    'qualification.raw':json.dumps(dict(rawSha256='0'*64 if wrong_raw else manifest['rawSha256'],
                        recipe=dict(methods=dict(demosaic=dict(requested='Malvar'))))),
                    'qualification.jpegUri':'content://media/test',
                    'qualification.jpegSha256':q.sha(file)})])
                baseline.write_json(parent/'collection.json',dict(captures=[str(root)]))
                class Device:
                    def run(self,*args):return encoded
                if wrong_pixels or wrong_raw:
                    with self.assertRaises(ValueError):publication.verify(Device(),parent/'collection.json')
                else:
                    self.assertTrue(publication.verify(Device(),parent/'collection.json')[0]['sameDecodedStoredPixels'])


if __name__=='__main__':unittest.main()
