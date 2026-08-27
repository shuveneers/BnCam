from pathlib import Path
root=Path(__file__).resolve().parents[1]
shader=(root/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
backend_h=(root/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h').read_text()
backend_cpp=(root/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp').read_text()
isp=(root/'src/main/cpp/IspCore.cpp').read_text()
header=(root/'src/main/cpp/HighlightGamutProtectionV2.h').read_text()
checks={
 'confidence starts pre wb': 'phase9ColorConfidence(rawInput, sensorClipMask)' in shader,
 'continuous start threshold': 'PHASE9_COLOR_CONFIDENCE_START = 0.960' in shader,
 'continuous zero threshold': 'PHASE9_COLOR_CONFIDENCE_ZERO = 0.995' in shader,
 'fully clipped confidence zero': 'phase9BitCount3(hardClipMask) == 3' in shader,
 'normal wb preserved': 'vec3 legacyWb = rawForWb * vec3(pc.wbR, pc.wbG, pc.wbB)' in shader,
 'normal ccm preserved': 'vec3 signedCcm = legacySignedCcm' in shader,
 'confidence applied after ccm': 'phase9ApplyClippingAwareHighlightColor(\n                signedCcm, colorConfidence' in shader,
 'neutral luma mix': 'mix(vec3(y), postCcmLinearRgb, confidence)' in shader,
 'original ccm excursion measured before confidence': 'originalCcmExcursion' in shader and shader.find('originalCcmExcursion') < shader.find('phase9ApplyClippingAwareHighlightColor(\n                signedCcm'),
 'confidence before gamut': shader.find('phase9ApplyClippingAwareHighlightColor(\n                signedCcm') < shader.find('phase9ProtectSignedCcmLowerGamut(\n                confidenceSafeCcm'),
 'no production reconstruction call': 'phase9ReconstructSensorClip(' not in shader,
 'scene headroom preserved': 'No upper clamp: neutral highlights above 1.0 remain valid scene-linear headroom.' in shader,
 'counter renamed applied': 'phase9ColorConfidenceAppliedPixels' in backend_h and 'phase9ColorConfidenceAppliedPixels = phase9[5]' in backend_cpp,
 'counter renamed partial': 'phase9PartialColorConfidencePixels' in backend_h and 'phase9PartialColorConfidencePixels = phase9[11]' in backend_cpp,
 'cpu reference uses confidence': 'applyClippingAwareHighlightColor(\n                                    signedCcm, colorConfidence)' in isp,
 'cpu reference no reconstruction': 'reconstructSensorClipped(' not in isp,
 'cpu no fullframe clone for highlight': 'const cv::Mat preWbSource = linearRgb.clone();' not in isp,
 'raw tone duplicate removed': 'if (!isRawBayer && (nearWhiteClipped || magentaClip))' in isp,
 'owner telemetry v3': 'phase9Owner=FUSED_AWB_CCM_CLIPPING_AWARE_HIGHLIGHT_GAMUT_V3' in isp,
 'policy telemetry': 'phase9HighlightColorPolicy=PRE_WB_CONFIDENCE_POST_CCM_NEUTRAL_LUMA_MIX' in isp,
 'fully clipped telemetry': 'phase9FullyClippedForcesZeroColorConfidence=true' in isp,
 'header confidence contract': 'resolveSensorColorConfidence' in header and 'applyClippingAwareHighlightColor' in header,
 'header no reconstruction owner': 'reconstructSensorClipped' not in header,
}
failed=[name for name,ok in checks.items() if not ok]
if failed:
    for name in failed: print('FAIL',name)
    raise SystemExit(1)
print(f'PHASE9_0061_INTEGRATION_CONTRACT_PASS={len(checks)}')
