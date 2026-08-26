#!/usr/bin/env python3
"""Static Phase 1 release-contract verifier for the BnCam source tree.

This intentionally avoids Android/Gradle dependencies so it can be run against a source-only
handoff. It verifies the ownership/scheduling contracts that must remain true before device tests.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass
class Check:
    name: str
    passed: bool
    detail: str = ""


class Verifier:
    def __init__(self, app_root: Path) -> None:
        self.root = app_root
        self.checks: list[Check] = []

    def file(self, rel: str) -> str:
        path = self.root / rel
        if not path.is_file():
            self.checks.append(Check(f"file exists: {rel}", False, str(path)))
            return ""
        self.checks.append(Check(f"file exists: {rel}", True))
        return path.read_text(encoding="utf-8")

    def expect(self, name: str, condition: bool, detail: str = "") -> None:
        self.checks.append(Check(name, bool(condition), detail))

    def contains(self, name: str, text: str, needle: str) -> None:
        self.expect(name, needle in text, f"missing: {needle}" if needle not in text else "")

    def absent(self, name: str, text: str, needle: str) -> None:
        self.expect(name, needle not in text, f"unexpected: {needle}" if needle in text else "")


def strip_comments_and_strings(source: str) -> str:
    """Replace comments/string bodies with spaces while preserving newlines."""
    out = list(source)
    i = 0
    n = len(source)
    state = "code"
    quote = ""
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                out[i] = out[i + 1] = " "
                i += 2
                state = "line_comment"
                continue
            if c == "/" and nxt == "*":
                out[i] = out[i + 1] = " "
                i += 2
                state = "block_comment"
                continue
            if source.startswith('"""', i):
                out[i:i+3] = [" ", " ", " "]
                i += 3
                state = "triple_string"
                continue
            if c in ('"', "'"):
                quote = c
                out[i] = " "
                i += 1
                state = "string"
                continue
            i += 1
        elif state == "line_comment":
            if c == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
        elif state == "block_comment":
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                i += 2
                state = "code"
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
        elif state == "triple_string":
            if source.startswith('"""', i):
                out[i:i+3] = [" ", " ", " "]
                i += 3
                state = "code"
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
        elif state == "string":
            if c == "\\":
                out[i] = " "
                if i + 1 < n:
                    if source[i + 1] != "\n":
                        out[i + 1] = " "
                    i += 2
                else:
                    i += 1
            elif c == quote:
                out[i] = " "
                i += 1
                state = "code"
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
    return "".join(out)


def balanced(source: str, pairs: dict[str, str]) -> tuple[bool, str]:
    clean = strip_comments_and_strings(source)
    reverse = {v: k for k, v in pairs.items()}
    stack: list[tuple[str, int]] = []
    for idx, c in enumerate(clean):
        if c in pairs:
            stack.append((c, idx))
        elif c in reverse:
            if not stack or stack[-1][0] != reverse[c]:
                return False, f"unexpected {c!r} at offset {idx}"
            stack.pop()
    if stack:
        c, idx = stack[-1]
        return False, f"unclosed {c!r} at offset {idx}"
    return True, ""


def split_top_level_params(body: str) -> list[str]:
    clean = strip_comments_and_strings(body)
    depth = 0
    current: list[str] = []
    result: list[str] = []
    for c in clean:
        if c in "(<[{":
            depth += 1
        elif c in ")>]}":
            depth -= 1
        if c == "," and depth == 0:
            token = "".join(current).strip()
            if token:
                result.append(token)
            current = []
        else:
            current.append(c)
    token = "".join(current).strip()
    if token:
        result.append(token)
    return result


def find_function_params(source: str, marker: str) -> list[str] | None:
    start = source.find(marker)
    if start < 0:
        return None
    open_idx = source.find("(", start + len(marker))
    if open_idx < 0:
        return None
    clean = strip_comments_and_strings(source)
    depth = 0
    for i in range(open_idx, len(clean)):
        c = clean[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return split_top_level_params(source[open_idx + 1:i])
    return None


def kotlin_external_names(source: str) -> list[str]:
    return re.findall(r"\bexternal\s+fun\s+([A-Za-z0-9_]+)\s*\(", source)


def cpp_jni_names(source: str) -> list[str]:
    return re.findall(r"Java_com_bncam_core_engine_ImageUtils_([A-Za-z0-9_]+)\s*\(", source)


def locate_src_root(path: Path) -> Path:
    candidates = [path, path / "app", path / "src" / ".."]
    for candidate in candidates:
        candidate = candidate.resolve()
        if (candidate / "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").is_file():
            return candidate
    raise SystemExit(f"Cannot locate BnCam app source under {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="App module root containing src/main")
    args = parser.parse_args()
    root = locate_src_root(args.source)
    v = Verifier(root)

    single = v.file("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
    multi = v.file("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
    manager = v.file("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
    image_utils = v.file("src/main/java/com/bncam/core/engine/ImageUtils.kt")
    screen = v.file("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
    owner = v.file("src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt")
    raw_input = v.file("src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt")
    master = v.file("src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt")
    native = v.file("src/main/cpp/native-lib.cpp")
    merger_h = v.file("src/main/cpp/DngMerger.h")
    merger = v.file("src/main/cpp/DngMerger.cpp")
    phase_test = v.file("src/test/java/com/bncam/core/performance/Phase1NativeOwnershipContractTest.kt")

    # Single YUV crash contract.
    yuv_section = single[single.find("val queued = if (processingWork == null)"):]
    await_idx = single.find("CaptureSaveQueue.enqueueAndAwait")
    uri_idx = single.find("val finalUri = publishedOutputUri")
    v.contains("Single YUV has synchronous save completion branch", yuv_section, "CaptureSaveQueue.enqueueAndAwait")
    v.expect("Single YUV waits before reading publication URI", await_idx >= 0 and uri_idx > await_idx,
             f"await={await_idx} uri={uri_idx}")
    v.contains("Single YUV returns completed URI contract", single, "CaptureSubmissionResult.CompletedSynchronously(finalUri)")
    v.contains("Capture UI uses lifecycle-owned scope", screen, "lifecycleOwner.lifecycleScope.launch")
    v.contains("Capture UI protects against duplicate trigger", screen, "captureSequenceInFlight.compareAndSet(false, true)")
    v.contains("Cancellation remains cancellable", screen, "catch (cancelled: CancellationException)")
    v.contains("Ordinary capture failures are handled", screen, "catch (failure: Exception)")
    trigger = screen[screen.find("val triggerCaptureSequence = {"):screen.find("// EVENT BUS LISTENER")]
    v.absent("Capture trigger does not use remembered coroutine scope", trigger, "coroutineScope.launch")
    v.absent("Ordinary failures are not rethrown into Activity", trigger, "throw failure")
    v.absent("Known Compose forgotten-scope path removed from capture trigger", trigger, "rememberCoroutineScope")

    # Native owner contracts.
    v.contains("Native RAW16 owner is closeable", owner, ") : Closeable")
    v.contains("Native RAW16 close is idempotent", owner, "closed.compareAndSet(false, true)")
    v.contains("Native RAW16 release is explicit", owner, "releaseNativeRaw16Handle(nativeHandle)")
    v.contains("Native RAW16 DNG materialization is explicit", owner, "fun materializeForDng(): ByteArray")
    v.contains("RAW renderer input owns native handle", raw_input, "val nativeRaw16: NativeRaw16Buffer")
    v.absent("RAW renderer input no longer owns Java RAW16 payload", raw_input, "val raw16Bytes: ByteArray")
    v.contains("Master RAW owns native handle", master, "override val nativeRaw16: NativeRaw16Buffer")
    v.absent("Master RAW no longer owns Java RAW16 payload", master, "override val raw16Bytes")
    v.contains("Single builder closes handle on post-registration failure", raw_input, "nativeRaw16.close()")
    v.contains("Master builder closes handle on post-registration failure", master, "nativeRaw16.close()")

    # JNI / registry contract.
    v.contains("Native RAW16 registry exists once", native,
               "std::unordered_map<jlong, std::shared_ptr<NativeRaw16Storage>>")
    v.expect("Native RAW16 registry declaration is unique",
             native.count("std::unordered_map<jlong, std::shared_ptr<NativeRaw16Storage>>") == 1)
    for needle, name in [
        ("registerNativeRaw16(", "register"),
        ("acquireNativeRaw16(", "acquire"),
        ("releaseNativeRaw16(", "release"),
        ("nativeRaw16ActiveHandleCount()", "handle leak count"),
        ("nativeRaw16ActiveByteCount()", "byte leak count"),
        ("renderJpegFromRaw16Pointer", "shared pointer renderer"),
    ]:
        v.contains(f"Native RAW16 {name} path exists", native, needle)
    v.absent("Production JNI no longer uses PrimitiveArrayCritical", native, "GetPrimitiveArrayCritical")
    v.absent("Production JNI no longer releases PrimitiveArrayCritical", native, "ReleasePrimitiveArrayCritical")
    pointer_helper = native[native.find("static jbyteArray renderJpegFromRaw16Pointer"):
                            native.find("Java_com_bncam_core_engine_ImageUtils_renderJpegFromMasterNative")]
    v.contains("Native handle renderer normalizes from read-only pointer", pointer_helper, "normalizeRawForJpeg(")
    v.absent("Native handle renderer avoids full Master RAW clone", pointer_helper, "bayer16.clone()")
    v.contains("RAW10 native buffer merger exists", merger, "mergeRaw10DngToRaw16Buffer")
    v.contains("RAW_SENSOR native buffer merger exists", merger, "mergeRawSensorDngToRaw16Buffer")
    v.contains("Native output timing is reported truthfully", merger_h, "outputNativeBufferMs")
    v.contains("Java materialization timing remains separate", merger_h, "javaRaw16MaterializationMs")

    kotlin_names = sorted(kotlin_external_names(image_utils))
    cpp_names = sorted(cpp_jni_names(native))
    v.expect("Kotlin and C++ JNI symbol sets match", kotlin_names == cpp_names,
             f"only Kotlin={sorted(set(kotlin_names)-set(cpp_names))}; only C++={sorted(set(cpp_names)-set(kotlin_names))}")
    # Compare parameter counts, excluding JNI's env + class/object prefix in C++.
    mismatches: list[str] = []
    for name in kotlin_names:
        kp = find_function_params(image_utils, f"external fun {name}")
        cp = find_function_params(native, f"Java_com_bncam_core_engine_ImageUtils_{name}")
        if kp is None or cp is None:
            mismatches.append(f"{name}: signature not parsed")
            continue
        actual_cpp = max(0, len(cp) - 2)
        if len(kp) != actual_cpp:
            mismatches.append(f"{name}: Kotlin={len(kp)} C++={actual_cpp}")
    v.expect("Kotlin/C++ JNI parameter counts match", not mismatches, "; ".join(mismatches))

    # Production route: no Java materialization for JPEG-only.
    v.contains("Direct native-handle JPEG bridge is declared", image_utils, "renderJpegFromNativeRaw16HandleNative")
    render_section = image_utils[image_utils.find("fun renderJpegFromRaw16InputSafe"):
                                 image_utils.find("fun renderJpegFromMasterFrameSafe")]
    v.contains("RAW renderer consumes native handle", render_section, "masterFrame.nativeRaw16.requireOpenHandle()")
    v.absent("RAW renderer does not call legacy Java payload renderer", render_section, "renderJpegFromMasterNative(")
    v.contains("Single DNG materializes only under output policy", single, "if (plan.outputPolicy.producesRaw")
    v.contains("Multi DNG materializes only when requested", multi, "if (dngExportRequested)")
    v.contains("Single release invariant is enforced", single, "single RAW native handle remained open")
    v.contains("Multi release invariant is enforced", multi, "multi RAW native handle remained open")
    v.contains("Single release metric is persisted", single, "nativeRaw16ReleaseSucceeded")
    v.contains("Multi release metric is persisted", multi, "nativeRaw16ReleaseSucceeded")

    # Multi-frame scheduling/ownership boundary.
    submit_idx = multi.find("val submitted = com.bncam.core.output.CaptureProcessingQueue.submit")
    merge_idx = multi.find("masterRawFrame = RawMasterBuilder.build")
    yuv_idx = multi.find("ImageUtils.processNativeYuvSafe")
    feedback_idx = multi.find("onProcessingFeedback(", submit_idx)
    return_idx = multi.find("CaptureSubmissionResult.Submitted", submit_idx)
    v.expect("Multi RAW merge executes inside processing queue", submit_idx >= 0 and merge_idx > submit_idx,
             f"submit={submit_idx} merge={merge_idx}")
    v.expect("Multi YUV processing executes inside processing queue", submit_idx >= 0 and yuv_idx > submit_idx,
             f"submit={submit_idx} yuv={yuv_idx}")
    v.expect("Multi async feedback follows native processing", feedback_idx > min(merge_idx, yuv_idx),
             f"feedback={feedback_idx}")
    v.expect("Multi runner returns Submitted after ownership handoff", return_idx > submit_idx,
             f"return={return_idx}")
    v.contains("Deferred shot logger has capture-stable destination", multi, "shotLogger.forkForDeferredWork()")
    v.contains("Queue terminal cleanup closes Master RAW", multi, "masterRawFrame?.close()")
    v.contains("Queue terminal cleanup closes burst frames", multi, "closeBurstFrames()")
    reject = multi[multi.find("if (!submitted) {"):return_idx]
    v.absent("Rejected queue submission cannot access lambda-local Master RAW", reject, "masterRawFrame")
    v.contains("Rejected queue submission returns burst ownership", reject, "closeBurstFrames()")
    v.contains("Manager receives asynchronous multi feedback", manager, 'source = "ASYNC_MULTI"')
    v.absent("Stale deferred single RAW health read is absent", manager, "rawSingleProcessingDeferred")

    # Test and source syntax contracts.
    v.expect("Phase 1 source contract test contains broad release gates",
             phase_test.count("@Test") >= 10, f"tests={phase_test.count('@Test')}")
    changed_files: Iterable[tuple[str, str]] = [
        ("SingleFrameRunner.kt", single), ("MultiFrameRunner.kt", multi),
        ("BnCameraManager.kt", manager), ("ImageUtils.kt", image_utils),
        ("CameraScreen.kt", screen), ("NativeRaw16Buffer.kt", owner),
        ("Raw16RenderInput.kt", raw_input), ("MasterRawFrame.kt", master),
        ("native-lib.cpp", native), ("DngMerger.h", merger_h), ("DngMerger.cpp", merger),
        ("Phase1NativeOwnershipContractTest.kt", phase_test),
    ]
    for name, source in changed_files:
        ok, detail = balanced(source, {"(": ")", "[": "]", "{": "}"})
        v.expect(f"Balanced delimiters: {name}", ok, detail)

    failed = [c for c in v.checks if not c.passed]
    for check in v.checks:
        prefix = "PASS" if check.passed else "FAIL"
        suffix = f" — {check.detail}" if check.detail else ""
        print(f"{prefix:4} {check.name}{suffix}")
    print(f"\n{len(v.checks)-len(failed)}/{len(v.checks)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
