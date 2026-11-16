"""Phase-5 source normalizer: allow ONLY the explicit debug-only coarse hook.

The selected block is fingerprinted byte-for-byte (FNV32), and the actual
phase-4 CPU block is loaded from the immutable phase-4 commit. This does not
weaken the frozen phase-0 through phase-4 source regressions.
"""
import subprocess
from pathlib import Path

_PHASE5_BASE = "ccf3e96976ae90b2ce59bcf774a4c5fae803555d"
_PHASE5_ROOT = Path(__file__).resolve().parents[4]
_PHASE5_ISP = "app/src/main/cpp/IspCore.cpp"

def fnv32(source: str) -> str:
    h = 2166136261
    for byte in source.encode("utf-8"):
        h = ((h ^ byte)*16777619) & 0xffffffff
    return format(h, "08x")

def strip_phase5_opt_in_isp(source: str) -> str:
    include = '#include "SingleFrameGuidedCoarse.h"\\n'
    assert source.count(include)==1, "Missing/duplicate SF2-D05 include"
    source=source.replace(include, "", 1)
    edits=[
        (
            '        singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2 &&\\n'
            '        singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2_FINE_COARSE) baselinePhysicalLuma = {};',
            '        singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2) baselinePhysicalLuma = {};'
        ),
        (
            '(singleFrameLumaStage==bncam::luma::Stage::GUIDED_V2_FINE_COARSE?"GUIDED_V2_FINE_COARSE_DEBUG":"GUIDED_V2_FINE_DEBUG")',
            '"GUIDED_V2_FINE_DEBUG"'
        ),
    ]
    for current, prior in edits:
        assert source.count(current)==1, "Missing/duplicate SF2-D05 runtime opt-in"
        source=source.replace(current, prior, 1)
    start_marker='        if (baselinePhysicalLuma.valid()) {\\n            const cv::Mat& source = physicalSource;\\n'
    end_marker='        ensureDefaultRawSourceConfidence();'
    assert source.count(start_marker)==1 and source.count(end_marker)==1
    start=source.index(start_marker)
    end=source.index(end_marker,start)
    new_block=source[start:end]
    assert fnv32(new_block)=="adcb27df", "CPU fine/coarse code changed outside approved delta"
    frozen=subprocess.check_output(
        ["git","show",_PHASE5_BASE+":"+_PHASE5_ISP],
        cwd=_PHASE5_ROOT,text=True)
    before=frozen.index(start_marker)
    after=frozen.index(end_marker,before)
    source=source[:start]+frozen[before:after]+source[end:]
    return source

"""Strict debug-only phase-3/4 source normalization for frozen pixel guards.

Phase 4 deliberately implements a new debug-selected pixel path. Source
identity after stripping the exact opt-in edits proves OFF/legacy unchanged;
it does NOT prove GUIDED output is byte-identical to the old pipeline.
"""

def strip_phase4_opt_in_isp(source: str) -> str:
    source = strip_phase5_opt_in_isp(source)
    include = '#include "SingleFrameGuidedLuma.h"\n'
    assert source.count(include) == 1
    source = source.replace(include, "", 1)
    edits = [
        (
            "    if (!bncam::luma::legacyAuthorized(singleFrameLumaStage) &&\n"
            "        singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2) baselinePhysicalLuma = {};",
            "    if (!bncam::luma::legacyAuthorized(singleFrameLumaStage)) baselinePhysicalLuma = {};"
        ),
        (
            "        request.baselinePhysicalLuma = baselinePhysicalLuma;\n"
            "        request.lumaStage = singleFrameLumaStage;",
            "        request.baselinePhysicalLuma = baselinePhysicalLuma;"
        ),
        (
            "                    auto p=(singleFrameLumaStage == bncam::luma::Stage::GUIDED_V2)\n"
            "                            ? bncam::sf2::guidedFine(x,y,baselinePhysicalLuma,\n"
            "                                    bncam::luma::Pixel{current[0],current[1],current[2]},read,shape,true).pixel\n"
            "                            : bncam::luma::filter(x,y,baselinePhysicalLuma,\n"
            "                                    bncam::luma::Pixel{current[0],current[1],current[2]},read,shape).pixel;",
            "                    auto p=bncam::luma::filter(x,y,baselinePhysicalLuma,\n"
            "                            bncam::luma::Pixel{current[0],current[1],current[2]},read,shape).pixel;"
        ),
        (
            'singleFrameLumaStage==bncam::luma::Stage::LEGACY_REFERENCE?"LEGACY_REFERENCE":"GUIDED_V2_FINE_DEBUG"',
            'singleFrameLumaStage==bncam::luma::Stage::LEGACY_REFERENCE?"LEGACY_REFERENCE":"GUIDED_V2_NOT_YET_ACTIVE"'
        ),
    ]
    for new, frozen in edits:
        assert source.count(new) == 1, "Missing or duplicated guided opt-in ISP edit"
        source = source.replace(new, frozen, 1)
    return source


"""Normalize exactly the phase-3 debug-only RAW observation additions.

Tests compare the remaining IspCore source byte-for-byte to the frozen release.
Any extra, missing or duplicated pixel-stage change still fails.
"""

def strip_phase3_debug_isp(source: str) -> str:
    source = strip_phase4_opt_in_isp(source)
    include = '#include "RawPoissonGaussianEstimator.h"\n'
    assert source.count(include) == 1, "Missing/duplicate SF2-D03 include"
    source = source.replace(include, "", 1)

    start_needle = "    // SF2-D03-02: debug-only, CPU-accessible PRE-LSC RAW diagnostic.\n"
    end_needle = "    // Temporal observation, independent S/O fitting and fusion weighting were already"
    assert source.count(start_needle) == 1
    assert source.count(end_needle) == 1
    start = source.index(start_needle)
    end = source.index(end_needle, start)
    code = source[start:end]
    for expected in (
            "RawPoissonGaussianObservation sf2RawPoissonGaussian{};",
            "rawJpegDebugDumpsEnabled",
            "singleShotRaw",
            "residentEntry",
            '"GPU_RESIDENT_NO_READBACK"',
            "hostInputValid",
            "estimateRawPoissonGaussian(",
            "workingRaw.mosaic.ptr<float>(0)",
            "workingRaw.info.effectiveCfaPattern",
            "workingMeta.calibration.effectiveS[0]",
            "workingMeta.calibration.effectiveO[0]",
    ):
        assert expected in code, f"Phase-3 diagnostic hook missing {expected}"
    # Neither invocation nor this input read may write RAW/CFA/calibration.
    assert "workingRaw.mosaic.at<" not in code
    assert "workingRaw.mosaic.clone(" not in code
    assert "normalizeRawForJpeg(" not in code
    assert "executeRaw" not in code
    assert "VulkanRuntime" not in code
    assert "effectiveS[0] =" not in code
    assert "effectiveO[0] =" not in code
    source = source[:start] + source[end:]

    telemetry = "            << bncam::sf2::formatRawPoissonGaussianDiagnostic(sf2RawPoissonGaussian)\n"
    assert source.count(telemetry) == 1
    source = source.replace(telemetry, "", 1)
    return source
