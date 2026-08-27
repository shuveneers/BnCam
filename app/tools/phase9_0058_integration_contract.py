from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
shader = (ROOT / 'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
backend = (ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp').read_text()
header = (ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h').read_text()
reference = (ROOT / 'src/main/cpp/HighlightGamutProtectionV2.h').read_text()

checks = []
def check(name, cond):
    checks.append((name, bool(cond)))

check('seven_storage_bindings_declared', 'VkDescriptorSetLayoutBinding bindings[7]' in backend and 'bindingCount = 7u' in backend)
check('compact_phase9_telemetry_binding', 'binding = 6) buffer ColorTelemetry' in shader and 'infos[6].buffer = colorTelemetry_.buffer' in backend)
check('telemetry_is_compact_12_u32', '12u * sizeof(std::uint32_t)' in backend and 'colorTelemetry_' in header)
check('immutable_pre_wb_source_distinct_output', 'updateDescriptorSetLocked(device, VK_NULL_HANDLE, rgbUpload_.buffer);' in backend and 'infos[1].buffer = deviceOutput_.buffer' in backend)
check('resident_output_promoted_without_full_copy', 'std::swap(deviceOutput_, rgbUpload_)' in backend)
check('sensor_clip_classified_before_wb', shader.find('uint sensorClipMask = phase9SensorClipMask(rawInput);') < shader.find('vec3 legacyWb = rawForWb * vec3(pc.wbR'))
check('wb_over_unity_is_counter_only', 'sensorClipChannels == 0 && any(greaterThan(legacyWb, vec3(1.0)))' in shader and 'atomicAdd(colorTelemetry[3], 1u)' in shader)
check('sensor_reconstruction_only_for_clip_mask', 'phase9ReconstructSensorClip' in shader and 'if (clippedChannels <= 0 || clippedChannels >= 3) return center;' in shader)
check('fully_clipped_hue_not_invented', 'sensorClipChannels == 3' in shader and 'colorTelemetry[10]' in shader)
check('neighbour_support_uses_immutable_source', 'vec3 n = residentRgbAt(coord.x + dx, coord.y + dy);' in shader)
check('residual_observer_uses_protected_color', all(x in shader for x in ['centerPixel = colorRgbAt', 'leftPixel = colorRgbAt', 'rightPixel = colorRgbAt']))
check('legacy_pointwise_ccm_kept_counterfactual', 'vec3 legacySignedCcm' in shader and 'ccm = max(legacySignedCcm, vec3(0.0));' in shader and 'phase9MagentaRisk(ccm)' in shader)
check('production_ccm_remains_signed_until_gamut_protection', 'vec3 signedCcm = vec3(' in shader and 'phase9ProtectSignedCcmLowerGamut(signedCcm' in shader)
check('old_independent_ccm_clamp_removed_from_mode3', 'ccm = max(vec3(0.0), vec3(' not in shader)
check('negative_ccm_only_triggers_lower_gamut', 'minimum < -kCcmNegativeTolerance' in reference and 'min(signedRgb.r, min(signedRgb.g, signedRgb.b)) < -1.0e-6' in shader)
check('scene_linear_upper_headroom_not_clamped', 'Scene-linear headroom >1 is deliberately preserved' in shader and 'positive_hdr_headroom' not in shader)
check('luma_axis_chroma_compression', 'vec3 protectedRgb = vec3(y) + (signedRgb - vec3(y)) * chromaScale;' in shader)
check('no_global_desaturation_control_added', 'saturation' not in shader[shader.find('const float PHASE9_SENSOR_CLIP_THRESHOLD'):shader.find('vec3 opponentAtRgb')].lower())
check('no_phase9_full_frame_readback_added', 'colorTelemetry_' in backend and 'outputReadback_' in backend and 'request.deferFullReadback' in backend)
check('telemetry_result_contract_exposed', all(name in header for name in [
    'phase9SensorClipCandidatePixels', 'phase9WbAboveUnityWithoutSensorClipPixels',
    'phase9CcmNegativeExcursionPixels', 'phase9ReconstructedPixels',
    'phase9GamutCompressedPixels', 'phase9SceneLinearOverUnityPixels']))
check('cpu_reference_matches_key_threshold', 'kSensorClipThreshold = 0.985f' in reference and 'PHASE9_SENSOR_CLIP_THRESHOLD = 0.985' in shader)
check('cpu_reference_preserves_upper_headroom', 'values >1 are HDR' in reference and 'if (!result.excursion) return result;' in reference)
check('phase6_residual_mode_still_present', 'push.mode = 4u;' in backend and 'writeResidualCandidate' in shader)
check('phase7_awb_gains_still_consumed', 'push.wbR = request.wbRgb[0]' in backend and 'vec3 recoveredWb = recoveredRaw * vec3(pc.wbR, pc.wbG, pc.wbB);' in shader)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if failed:
    print(f'PHASE9_0058_INTEGRATION_CONTRACT_FAIL={len(failed)}')
    sys.exit(1)
print(f'PHASE9_0058_INTEGRATION_CONTRACT_PASS={len(checks)}')
