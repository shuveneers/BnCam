"""Strict debug-only phase-3/4 source normalization for frozen pixel guards.

Phase 4 deliberately implements a new debug-selected pixel path. Source
identity after stripping the exact opt-in edits proves OFF/legacy unchanged;
it does NOT prove GUIDED output is byte-identical to the old pipeline.
"""

def strip_phase4_opt_in_isp(source: str) -> str:
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
