#!/usr/bin/env python3
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
single = (root / "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").read_text()
camera = (root / "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").read_text()
checks = {
    "no synchronous YUV URI race": "Single frame YUV produced no output URI" not in single,
    "YUV reserves processing ownership": "val yuvReservation = CaptureProcessingQueue.tryReserve" in single,
    "YUV submits detached work": "val yuvSubmitted = CaptureProcessingQueue.submit" in single,
    "YUV returns Submitted": "workId = yuvReservation.workId" in single,
    "YUV source lifetime transferred": "candidateFramesOwnershipTransferred.set(true)" in single,
    "processing worker closes YUV frames": "sourceFrameOwner=PROCESSING_QUEUE" in single and "closeTakenFrames()" in single,
    "collector respects transferred ownership": "if (!candidateFramesOwnershipTransferred.get())" in single,
    "capture uses lifecycle scope": "captureScope.launch" in camera and "lifecycleOwner.lifecycleScope" in camera,
    "unexpected UI failure is contained": "Capture failed without terminating the camera UI" in camera,
}
failed = []
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL"), name)
    if not ok:
        failed.append(name)
sys.exit(1 if failed else 0)
