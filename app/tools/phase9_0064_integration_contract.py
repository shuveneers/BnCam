from pathlib import Path
root = Path(__file__).resolve().parents[1]
shader = (root/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
header = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
backend = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
isp = (root/'src/main/cpp/IspCore.cpp').read_text()
checks = {
    'request_demosaic_guard_fields': 'preToneChromaDemosaicFamily' in header and 'preToneChromaAuthorityScale' in header and 'preToneChromaResidualFloorScale' in header,
    'result_guard_telemetry_fields': 'preToneChromaDemosaicGuardedPixels' in header and 'preToneChromaMeanAuthorityScale' in header,
    'backend_reuses_mode0_push_slots': 'push.ultraHdrSourceMapWidth = request.preToneChromaDemosaicFamily;' in backend and 'push.portraitTargetLeft = std::clamp(request.preToneChromaAuthorityScale' in backend,
    'telemetry_word_count_grown': 'kTelemetryWords = 10u' in backend,
    'shader_demosaic_helpers': 'uint phase9DemosaicFamily()' in shader and 'float phase9DemosaicAuthorityScale()' in shader,
    'multiscale_disagreement_guard': 'multiScalePredictionDisagreement' in shader and 'predictionDisagreementProtection' in shader,
    'demosaic_scaled_tile_authority': 'tilePredictionAuthority = 0.28 * demosaicAuthorityScale' in shader,
    'raised_stochastic_floor': 'tileResidualVariance * demosaicResidualFloorScale' in shader,
    'per_channel_evidence_scaled': 'float residualEvidenceScale = sqrt(demosaicResidualFloorScale);' in shader,
    'demosaic_guard_counter': 'atomicAdd(telemetry[8], 1u);' in shader and 'atomicAdd(telemetry[9], uint(round(phase9DemosaicAuthorityScale() * 4095.0)));' in shader,
    'isp_computes_guard_plan': 'phase9DemosaicAuthorityScale' in isp and 'phase9DemosaicDetailProtectionBoost' in isp and 'phase9DemosaicResidualFloorScale' in isp,
    'isp_routes_guard_into_request': 'request.preToneChromaDemosaicFamily = phase9DemosaicFamily;' in isp and 'request.preToneChromaAuthorityScale = phase9DemosaicAuthorityScale;' in isp,
    'isp_debug_exposes_guard': 'phase9ChromaDemosaicGuardedPixels=' in isp and 'phase9ChromaMeanAuthorityScale=' in isp and 'phase9ChromaRequestedResidualFloorScale=' in isp,
}
failed = [k for k, v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE9_0064_INTEGRATION_CONTRACT_FAIL=' + ','.join(failed))
print('PHASE9_0064_INTEGRATION_CONTRACT_PASS=' + str(len(checks)))
