"""Offline fail-closed tests for the standard-library, binary-safe ADB collector."""
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"scripts"))
import sf2_phase7_collect as sf2


class CollectorQualificationContract(unittest.TestCase):
    def test_fails_without_explicit_completed_on_device_report(self):
        self.assertFalse(sf2.classify_qualifier("")["gpu_cpu_parity_pass"])
        self.assertFalse(sf2.classify_qualifier(
            "stage=0;\nstage=1;\ntestedStages=6;allPassed=true\n"
        )["gpu_cpu_parity_pass"])
        self.assertFalse(sf2.classify_qualifier(
            "testedStages=6;allPassed=true;allPassed=false\n" +
            "".join(f"stage={x};gpuKernelMs=1\n" for x in (0,1,2,4,5,5))
        )["gpu_cpu_parity_pass"])

    def test_full_stage_matrix_requires_six_records(self):
        lines="buildSha=abc\n"+"".join(
            f"stage={s};kernelProxy=false;maxRgbDifference=0\n"
            for s in (0,1,2,4,5,5)
        )+"testedStages=6;maxRgbDifference=0;allPassed=true\n"
        result=sf2.classify_qualifier(lines)
        self.assertTrue(result["gpu_cpu_parity_pass"])
        self.assertEqual(result["build_sha"],"abc")
        self.assertEqual(result["device_stage_records"],6)

    def test_capture_dir_safety(self):
        self.assertTrue(sf2.valid_capture_dir("1763354444444"))
        self.assertFalse(sf2.valid_capture_dir("../something"))
        self.assertFalse(sf2.valid_capture_dir("nothing"))
        self.assertEqual(sf2.pick_newest_dir("1763354444\n1763354555/\n"),"1763354555")
        self.assertIsNone(sf2.pick_newest_dir("error: nothing"))

    def test_five_stage_plus_off_repeat_manifest_and_jpeg_markers(self):
        notes="sf2QualificationVersion=1\nrawMasterCrc32=1234\n"+"".join(
            f"selectedStage.{name}={value};expected={value}\n"
            for name,value in (("off",0),("legacy",1),("fine",2),
                               ("coarse",4),("confidence",5),("offRepeat",0))
        )+"offRepeatJpegBytesIdentical=true\n"
        native_status = {
            "off.txt": ("OFF", False),
            "on.txt": ("LEGACY_REFERENCE", True),
            "guided.txt": ("GUIDED_V2_FINE_DEBUG", True),
            "combined.txt": ("GUIDED_V2_FINE_COARSE_DEBUG", True),
            "confidence.txt": ("GUIDED_V2_CONFIDENCE_DEBUG", True),
            "off_repeat.txt": ("OFF", False),
        }
        files={}
        for name in sf2.CAPTURE_FILES:
            if name.endswith(".jpg"):
                files[name]=b"\xff\xd8dummy\xff\xd9"
            elif name=="qualification.txt":
                files[name]=notes.encode()
            else:
                stage, enabled=native_status[name]
                files[name]=(f";stage={stage};gpuExecuted={'true' if enabled else 'false'}"
                             + (";measurementStatus=GPU_MEASURED" if enabled else "")
                             + (";confidenceGateApplied=true" if name=="confidence.txt" else "")
                             ).encode()
        x=sf2.verify_capture(files)
        self.assertTrue(x["all_capture_files_present"])
        self.assertTrue(x["jpeg_markers_valid"])
        self.assertTrue(x["six_debug_stage_checks_pass"])
        self.assertTrue(x["native_stage_and_gpu_route_checks_pass"])
        files["confidence.txt"]=b";stage=GUIDED_V2_CONFIDENCE_DEBUG;gpuExecuted=false"
        self.assertFalse(sf2.verify_capture(files)["native_stage_and_gpu_route_checks_pass"])
        files["confidence.txt"]=(b";stage=GUIDED_V2_CONFIDENCE_DEBUG;gpuExecuted=true;"
                                 b"measurementStatus=GPU_MEASURED;confidenceGateApplied=true")
        self.assertTrue(x["master_crc_present"])
        self.assertTrue(x["off_repeat_jpeg_byte_identical"])
        files["off_repeat.jpg"]=b"not a JPEG"
        self.assertFalse(sf2.verify_capture(files)["jpeg_markers_valid"])
        del files["confidence.jpg"]
        self.assertFalse(sf2.verify_capture(files)["all_capture_files_present"])

    def test_wrong_stage_and_stale_metadata_fail_closed(self):
        notes="".join(f"selectedStage.{n}={v};expected={v}\n"
            for n,v in (("off",0),("legacy",1),("fine",2),
                        ("coarse",4),("confidence",4),("offRepeat",0)))
        files={"qualification.txt":notes.encode()}
        self.assertFalse(sf2.verify_capture(files)["six_debug_stage_checks_pass"])
        self.assertFalse(sf2.verify_capture(files)["jpeg_markers_valid"])
        self.assertFalse(sf2.verify_capture(files)["master_crc_present"])


if __name__=="__main__":
    unittest.main()
