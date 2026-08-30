from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

BASELINE_HEAD = "7a67376528fe4d1eac59061f1240299b8dc8ea20"
EXPECTED_GIT_BLOBS = {
    "src/main/cpp/vulkan/shaders/spectra_tone_resident.comp": "9186c18c1522355c57bce3c33ec01b464ee07332",
    "src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp": "60a3f650394132a7272ad21f94823b12a888fcb9",
}
BACKUP_DIR = Path("tools/tone_truth/_backup")

SHADER_HELPERS = r'''

// DIAGNOSTIC DELTA — RAW tone-stage truth telemetry only.
// Sparse 64x64 sampling keeps contention bounded and never changes workingRgb.
// Metrics per stage are R,G,B,luma,max-min chroma. Values use signed fixed-point
// accumulation so small negative AgX-outset excursions remain visible in diagnostics.
const uint kToneTruthSampleStride = 64u;
const float kToneTruthScale = 4096.0;
const float kToneTruthBias = 4.0;

uint toneTruthEncode(float value) {
    float bounded = clamp(isnan(value) || isinf(value) ? 0.0 : value, -4.0, 4.0);
    return uint(round((bounded + kToneTruthBias) * kToneTruthScale));
}

float toneTruthChroma(vec3 rgb) {
    return max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
}

void toneTruthAccumulate(uint baseIndex, vec3 rgb) {
    atomicAdd(telemetry[baseIndex + 0u], toneTruthEncode(rgb.r));
    atomicAdd(telemetry[baseIndex + 1u], toneTruthEncode(rgb.g));
    atomicAdd(telemetry[baseIndex + 2u], toneTruthEncode(rgb.b));
    atomicAdd(telemetry[baseIndex + 3u], toneTruthEncode(lumaOf(rgb)));
    atomicAdd(telemetry[baseIndex + 4u], toneTruthEncode(toneTruthChroma(rgb)));
}

void recordToneTruth(
        uvec2 gid,
        vec3 postCcm,
        vec3 postGtmScenePlacement,
        vec3 postFllf,
        vec3 postAgx) {
    if ((gid.x % kToneTruthSampleStride) != 0u ||
        (gid.y % kToneTruthSampleStride) != 0u) return;

    // [64] count; [65..84] four stages x five metrics.
    atomicAdd(telemetry[64], 1u);
    toneTruthAccumulate(65u, postCcm);
    toneTruthAccumulate(70u, postGtmScenePlacement);
    toneTruthAccumulate(75u, postFllf);
    toneTruthAccumulate(80u, postAgx);

    // Deep-shadow truth is selected only from immutable post-CCM scene-linear luma.
    // This 2% threshold is diagnostic classification only; it never gates image processing.
    if (lumaOf(postCcm) <= 0.020) {
        // [85] deep count; [86..105] same four-stage layout.
        atomicAdd(telemetry[85], 1u);
        toneTruthAccumulate(86u, postCcm);
        toneTruthAccumulate(91u, postGtmScenePlacement);
        toneTruthAccumulate(96u, postFllf);
        toneTruthAccumulate(101u, postAgx);
    }
}
'''

BACKEND_SUMMARY = r'''
    // DIAGNOSTIC DELTA — decode sparse GPU stage truth without any full-frame CPU work.
    // Shader encoding is signed fixed point: (value + 4) * 4096.
    std::string toneTruthSummary;
    if (request.isRawBayer && telemetry[64] > 0u) {
        constexpr float kToneTruthScale = 4096.0f;
        constexpr float kToneTruthBias = 4.0f;
        const std::uint32_t toneTruthCount = telemetry[64];
        const std::uint32_t toneTruthDeepCount = telemetry[85];
        auto decodeToneTruthMean = [&](std::uint32_t index, std::uint32_t count) noexcept -> float {
            if (count == 0u) return 0.0f;
            return static_cast<float>(telemetry[index]) /
                    (kToneTruthScale * static_cast<float>(count)) - kToneTruthBias;
        };
        auto appendToneTruthStage = [&](const char* label, std::uint32_t baseIndex,
                                        std::uint32_t count) {
            toneTruthSummary += ";";
            toneTruthSummary += label;
            toneTruthSummary += "=[";
            for (std::uint32_t i = 0u; i < 5u; ++i) {
                if (i != 0u) toneTruthSummary += ",";
                toneTruthSummary += std::to_string(decodeToneTruthMean(baseIndex + i, count));
            }
            toneTruthSummary += "]";
        };

        toneTruthSummary = "TONE_TRUTH_V1;sampleStride=64;metrics=R,G,B,Y,C;count=" +
                std::to_string(toneTruthCount);
        appendToneTruthStage("postCcm", 65u, toneTruthCount);
        appendToneTruthStage("postGtm", 70u, toneTruthCount);
        appendToneTruthStage("postFllf", 75u, toneTruthCount);
        appendToneTruthStage("postAgx", 80u, toneTruthCount);
        toneTruthSummary += ";deepThreshold=0.020000;deepCount=" +
                std::to_string(toneTruthDeepCount);
        if (toneTruthDeepCount > 0u) {
            appendToneTruthStage("deepPostCcm", 86u, toneTruthDeepCount);
            appendToneTruthStage("deepPostGtm", 91u, toneTruthDeepCount);
            appendToneTruthStage("deepPostFllf", 96u, toneTruthDeepCount);
            appendToneTruthStage("deepPostAgx", 101u, toneTruthDeepCount);
        }
    }
'''


def normalized_lf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def git_blob_sha(data: bytes) -> str:
    data = normalized_lf(data)
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def read_text_preserve(path: Path) -> tuple[str, str]:
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    text = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    return text, newline


def write_text_preserve(path: Path, text: str, newline: str) -> None:
    if newline != "\n":
        text = text.replace("\n", newline)
    path.write_bytes(text.encode("utf-8"))


def require_once(text: str, needle: str, label: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise RuntimeError(f"{label}: expected anchor exactly once, found {count}")


def patch_shader(text: str) -> str:
    if "TONE_TRUTH_V1" in text or "recordToneTruth(" in text:
        raise RuntimeError("shader already contains tone-truth instrumentation")

    luma_anchor = '''float lumaOf(vec3 v) {
    return 0.2126 * v.r + 0.7152 * v.g + 0.0722 * v.b;
}
'''
    require_once(text, luma_anchor, "shader luma anchor")
    text = text.replace(luma_anchor, luma_anchor + SHADER_HELPERS, 1)

    rgb_anchor = '''    vec3 rgb = vec3(workingRgb[base + 0u], workingRgb[base + 1u], workingRgb[base + 2u]);
    rgb *= pc.exposureGain;
    if (pc.isRawBayer != 0u) {
'''
    rgb_replacement = '''    vec3 rgb = vec3(workingRgb[base + 0u], workingRgb[base + 1u], workingRgb[base + 2u]);
    vec3 toneTruthPostCcm = rgb;
    rgb *= pc.exposureGain;
    if (pc.isRawBayer != 0u) {
        vec3 toneTruthPostGtm = rgb;
'''
    require_once(text, rgb_anchor, "shader runTone input anchor")
    text = text.replace(rgb_anchor, rgb_replacement, 1)

    fllf_anchor = '''        if (pc.presenceReserved0 != 0u) rgb = applyFllfLocalExposure(gid, rgb);
        // Scene-linear RAW Bayer path: AgX is the sole automatic scene-to-display DRT.
'''
    fllf_replacement = '''        if (pc.presenceReserved0 != 0u) rgb = applyFllfLocalExposure(gid, rgb);
        vec3 toneTruthPostFllf = rgb;
        // Scene-linear RAW Bayer path: AgX is the sole automatic scene-to-display DRT.
'''
    require_once(text, fllf_anchor, "shader FLLF anchor")
    text = text.replace(fllf_anchor, fllf_replacement, 1)

    agx_anchor = '''        rgb = applyAgXTonemap(rgb);
        // The LUT is identity for automatic RAW rendering in Phase 10; only explicit profile
'''
    agx_replacement = '''        rgb = applyAgXTonemap(rgb);
        vec3 toneTruthPostAgx = rgb;
        recordToneTruth(gid, toneTruthPostCcm, toneTruthPostGtm,
                        toneTruthPostFllf, toneTruthPostAgx);
        // The LUT is identity for automatic RAW rendering in Phase 10; only explicit profile
'''
    require_once(text, agx_anchor, "shader AgX anchor")
    text = text.replace(agx_anchor, agx_replacement, 1)
    return text


def patch_backend(text: str) -> str:
    if "TONE_TRUTH_V1" in text:
        raise RuntimeError("backend already contains tone-truth instrumentation")

    words_anchor = "[[maybe_unused]] constexpr std::uint32_t kTelemetryWords = 64u;"
    require_once(text, words_anchor, "backend telemetry size anchor")
    text = text.replace(
        words_anchor,
        "[[maybe_unused]] constexpr std::uint32_t kTelemetryWords = 112u; // 64 legacy + 42 tone-truth + spare",
        1,
    )

    reset_old = '''    // Reset tone/gainmap/local-adaptation/detail telemetry while preserving Phase-9 scene-observer
    // tile count [0]. FLLF owns [8..19]; Phase-11 owns [20..38]; Phase-12 owns [39..55].
    // Mode-0 near-black covariance telemetry uses [56..63] and is read before this tone reset.
'''
    reset_new = '''    // Reset tone/gainmap/local-adaptation/detail telemetry while preserving Phase-9 scene-observer
    // tile count [0]. FLLF owns [8..19]; Phase-11 owns [20..38]; Phase-12 owns [39..55].
    // Mode-0 near-black covariance telemetry uses [56..63] and is read before this tone reset.
    // Diagnostic RAW tone-stage truth owns [64..105]; [106..111] remain spare.
'''
    require_once(text, reset_old, "backend telemetry ownership comment")
    text = text.replace(reset_old, reset_new, 1)

    summary_anchor = '''    result.perceptualDetailApplied = perceptualDetailRequested && result.perceptualDetailChangedPixels > 0u;
    if (ultraHdrRequested) {
'''
    require_once(text, summary_anchor, "backend decode insertion anchor")
    text = text.replace(
        summary_anchor,
        '''    result.perceptualDetailApplied = perceptualDetailRequested && result.perceptualDetailChangedPixels > 0u;
''' + BACKEND_SUMMARY + '''    if (ultraHdrRequested) {
''',
        1,
    )

    final_anchor = '''    result.failureReason = result.success ? "none" : "FULL_RGB_READBACK_INCOMPLETE";
'''
    final_replacement = '''    result.failureReason = result.success
            ? (toneTruthSummary.empty() ? "none" : "none|" + toneTruthSummary)
            : "FULL_RGB_READBACK_INCOMPLETE";
'''
    require_once(text, final_anchor, "backend final diagnostic export anchor")
    text = text.replace(final_anchor, final_replacement, 1)
    return text


def verify_baseline(app_root: Path) -> None:
    problems: list[str] = []
    for rel, expected in EXPECTED_GIT_BLOBS.items():
        path = app_root / rel
        if not path.is_file():
            problems.append(f"missing: {rel}")
            continue
        actual = git_blob_sha(path.read_bytes())
        if actual != expected:
            problems.append(f"baseline mismatch: {rel}\n  expected git blob {expected}\n  actual   git blob {actual}")
    if problems:
        raise RuntimeError(
            "Refusing to patch because local source is not the verified GitHub baseline "
            f"{BASELINE_HEAD[:8]}.\n" + "\n".join(problems)
        )


def apply(app_root: Path) -> None:
    verify_baseline(app_root)
    BACKUP_DIR_ABS = app_root / BACKUP_DIR
    BACKUP_DIR_ABS.mkdir(parents=True, exist_ok=True)

    shader_rel = Path("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
    backend_rel = Path("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
    targets = [shader_rel, backend_rel]

    patched: dict[Path, tuple[str, str]] = {}
    for rel in targets:
        text, newline = read_text_preserve(app_root / rel)
        if rel == shader_rel:
            new_text = patch_shader(text)
        else:
            new_text = patch_backend(text)
        patched[rel] = (new_text, newline)

    # All anchors and baseline checks succeeded before the first production file is changed.
    for rel in targets:
        src = app_root / rel
        backup = BACKUP_DIR_ABS / rel
        backup.parent.mkdir(parents=True, exist_ok=True)
        if backup.exists():
            raise RuntimeError(f"backup already exists: {backup}; revert or remove it before re-applying")
        shutil.copy2(src, backup)

    try:
        for rel in targets:
            text, newline = patched[rel]
            write_text_preserve(app_root / rel, text, newline)
    except Exception:
        for rel in targets:
            backup = BACKUP_DIR_ABS / rel
            if backup.exists():
                shutil.copy2(backup, app_root / rel)
        raise

    print("Applied GTM/AgX tone-truth diagnostic delta.")
    print("Changed:")
    for rel in targets:
        print(f"  {rel}")
    print("Image math/output is unchanged; only sparse Vulkan telemetry + debug export were added.")


def revert(app_root: Path) -> None:
    backup_root = app_root / BACKUP_DIR
    restored = 0
    for rel in [
        Path("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp"),
        Path("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp"),
    ]:
        backup = backup_root / rel
        target = app_root / rel
        if backup.is_file():
            shutil.copy2(backup, target)
            restored += 1
    if restored == 0:
        raise RuntimeError("no tone-truth backups found")
    shutil.rmtree(backup_root)
    print(f"Reverted GTM/AgX tone-truth diagnostic delta ({restored} files restored).")


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--revert", action="store_true")
    parser.add_argument("--app-root", default=".", help="BnCam app folder; default current directory")
    args = parser.parse_args()
    app_root = Path(args.app_root).resolve()
    try:
        if args.apply:
            apply(app_root)
        else:
            revert(app_root)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
