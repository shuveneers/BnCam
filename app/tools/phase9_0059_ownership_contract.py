from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
isp = (ROOT / 'src/main/cpp/IspCore.cpp').read_text()
demosaic = (ROOT / 'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
tone = (ROOT / 'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
tone_cpp = (ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
tone_h = (ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
runtime_h = (ROOT / 'src/main/cpp/vulkan/VulkanRuntime.h').read_text()
reference = (ROOT / 'src/main/cpp/HighlightGamutProtectionV2.h').read_text()

checks=[]
def check(name, cond): checks.append((name, bool(cond)))

# Production ownership truth.
check('legacy_cpu_post_ccm_recovery_removed', 'applyLocalHighlightRecovery' not in isp)
check('legacy_vulkan_post_ccm_3x3_recovery_removed', 'runHighlightRecovery' not in tone and '0.65 * severity' not in tone)
check('scene_mode0_is_pretone_preparation_only', 'void runPreTonePreparation' in tone and 'runPreTonePreparation(gid);' in tone)
check('raw_post_tone_magenta_neutralizer_removed', 'neutralizeRawHighlightChroma' not in tone)
check('raw_tone_sequence_has_no_highlight_fix', 'rgb = applyAgXTonemap(rgb);\n        rgb = applyToneLookLut(rgb);\n        rgb = applyProfileColor(rgb);' in tone)
check('yuv_heuristic_left_separate', 'YUV capture path (already ISP-tonemapped by HAL)' in tone and 'bool magentaClip' in tone)
check('scene_backend_legacy_fields_explicit_zero', 'result.correctedHighlightPixels = 0u;' in tone_cpp and 'result.highlightRecoveryApplied = false;' in tone_cpp)
check('scene_backend_status_no_longer_claims_recovery', 'GPU_RESIDENT_PRETONE_PREPARATION_AND_SCENE_SAMPLES_READY' in tone_cpp)
check('runtime_comment_truthful', 'consume upstream-protected post-CCM RGB' in runtime_h)

# CPU failure reference uses same core contract rather than a cosmetic fallback.
check('isp_includes_phase9_reference', '#include "HighlightGamutProtectionV2.h"' in isp)
check('cpu_reference_reconstructs_sensor_candidates', 'bncam::highlight::reconstructSensorClipped' in isp)
check('cpu_reference_uses_signed_ccm_gamut_contract', 'bncam::highlight::protectSignedCcmLowerGamut' in isp)
check('cpu_full_frame_clone_typed_failure_only', 'Failure-only reference path' in isp and 'const cv::Mat preWbSource = linearRgb.clone();' in isp)
check('cpu_reference_does_not_desaturate_globally', 'phase9NoGlobalDesaturation=true' in isp)

# Phase 7/8 verified compact telemetry remains on its old domain; production pixels branch after it.
check('phase7_wb_mean_domain_preserved', 'wb = legacyWb;' in demosaic)
check('phase8_ccm_mean_domain_preserved', 'ccm = max(legacySignedCcm, vec3(0.0));' in demosaic)
check('phase9_production_output_separate', 'vec3 protectedCcm = phase9ProtectSignedCcmLowerGamut' in demosaic and 'colorRgb[index + 0u] = finiteLinear(protectedCcm.r);' in demosaic)
check('phase8_validation_code_preserved', 'phase8ColorValidationDomain=POST_AWB_CCM_LINEAR_SRGB_PRE_PRESENTATION' in isp)
check('phase7_final_wb_telemetry_preserved', 'phase7FinalWbR=' in isp and 'phase7FinalWbB=' in isp)

# Required domain distinction/telemetry.
check('phase9_owner_explicit', 'phase9Owner=FUSED_AWB_CCM_SENSOR_CLIP_GAMUT_V2' in isp)
check('sensor_candidate_domain_explicit', 'phase9SensorClipDomain=PRE_WB_NORMALIZED_DEMOSAIC_CEILING_CANDIDATE' in isp)
check('wb_over_unity_separate_and_preserved', 'phase9WbAboveUnityWithoutSensorClipPixels=' in isp and 'phase9WbOverUnityPreserved=true' in isp)
check('ccm_excursion_separate', 'phase9CcmNegativeExcursionPixels=' in isp and 'phase9GamutCompressedPixels=' in isp)
check('scene_linear_over_unity_separate_and_preserved', 'phase9SceneLinearOverUnityPixels=' in isp and 'phase9SceneLinearOverUnityPreserved=true' in isp)
check('tone_output_clipping_explicitly_deferred', 'phase9ToneOutputClippingOwner=PHASE10_DEFERRED' in isp)
check('legacy_owners_explicit_false', 'phase9LegacyPostCcmRecoveryOwner=false' in isp and 'phase9LegacyRawPostToneNeutralizerOwner=false' in isp)
check('gpu_cpu_owner_telemetry', 'phase9GpuPrimary=' in isp and 'phase9CpuFailureReferenceUsed=' in isp)
check('no_phase9_full_frame_readback_claim', 'phase9FullFrameReadback=false' in isp)

# Highlight downstream policy now keys only from real Phase-9 reconstruction.
check('highlight_debug_uses_phase9_reconstruction_count', 'highlightDebug.applied = phase9ColorDebug.reconstructedPixels > 0u;' in isp)
check('scene_observer_no_longer_overwrites_highlight_debug', 'vulkanSceneObserver.highlightRecoveryApplied' not in isp)
check('cpu_fallback_syncs_same_highlight_debug', isp.count('syncHighlightDebugFromPhase9();') >= 4)

# Core safety invariants.
check('fully_clipped_not_reconstructed', 'if (!result.clip.any || result.clip.all' in reference)
check('only_negative_ccm_invokes_gamut_compression', 'minimum < -kCcmNegativeTolerance' in reference)
check('positive_hdr_headroom_preserved_reference', 'if (!result.excursion) return result;' in reference)
check('raw_tone_neutralization_telemetry_can_only_come_from_yuv', tone.find('atomicAdd(telemetry[1], 1u);') > tone.find('// YUV capture path'))

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
failed=[n for n,o in checks if not o]
if failed:
    print(f'PHASE9_0059_OWNERSHIP_CONTRACT_FAIL={len(failed)}')
    sys.exit(1)
print(f'PHASE9_0059_OWNERSHIP_CONTRACT_PASS={len(checks)}')
