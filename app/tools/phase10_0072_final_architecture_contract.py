from pathlib import Path
r=Path(__file__).resolve().parents[1]
i=(r/'src/main/cpp/IspCore.cpp').read_text()
sh=(r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
b=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
h=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
policy=(r/'src/main/cpp/FastLocalLaplacianPolicy.h').read_text()
demosaic=(r/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
run=sh[sh.index('void runTone'):sh.index('void main()')]
checks={
 'raw_architecture_marker':'GTM_SCENE_PLACEMENT__FLLF__AGX' in i,
 'gtm_raw_auto_contrast_retired':'phase10RawToneArchitecture ? 0.0f : dynamicRangeTonePlan.contrastStrength' in i,
 'gtm_raw_black_anchor_retired':'phase10RawToneArchitecture\n            ? 0.0f' in i,
 'raw_legacy_ltm_retired':'request.localToneStrength = !phase10RawToneArchitecture' in i,
 'fllf_policy_scene_evidence':all(x in policy for x in ['dynamicRangePressure','recoverableHighlightPressure','sensorClipPressure','noisePressure']),
 'fllf_two_scalar_resident_buffers':'PersistentBuffer fllfGaussian_' in h and 'PersistentBuffer fllfCorrection_' in h,
 'fllf_modes_10_13':all(f'push.mode = {m}u' in b for m in [10,11,12,13]),
 'fllf_runtime_active_flag':'push.presenceReserved0 = fllfRequested ? 1u : 0u' in b and 'if (pc.presenceReserved0 != 0u) rgb = applyFllfLocalExposure' in run,
 'raw_order_gtm_fllf_agx':run.index('rgb *= pc.exposureGain') < run.index('applyFllfLocalExposure') < run.index('rgb = applyAgXTonemap(rgb);') < run.index('rgb = applyToneLookLut(rgb);') < run.index('rgb = applyProfileColor(rgb);'),
 'agx_core_only_no_second_toe':'kAgxBlackPedestal' not in sh and 'pow(val, vec3(1.08))' not in sh,
 'agx_correct_absolute_log_domain':'const float min_ev = -12.47393;' in sh and 'const float max_ev = +4.026069;' in sh,
 'agx_signed_outset_until_final_gamut':'val = AGX_OUTSET_MATRIX * val;' in sh and 'max(vec3(0.0), AGX_OUTSET_MATRIX * val)' not in sh,
 'agx_no_auto_sat_restore':'satBoost' not in sh,
 'raw_auto_vibrance_neutral':'phase10AppliedBaseVibrance = phase10RawToneArchitecture ? 1.0f' in i,
 'post_agx_lut_not_gamut_owner':'return rgb * scale;' in sh,
 'final_gamut_owner_post_profile_signed':'phase10OutputGamutOwner=' in i and 'POST_PROFILE_SIGNED_LUMA_PRESERVING' in i,
 'raw_noise_propagation_follows_agx':'SCENE_SAMPLED_AGX_NEUTRAL_JACOBIAN_AND_CHROMA_SCALE' in i and 'phase10AgxCoreCpu(cv::Vec3f(x, x, x))' in i,
 'cpu_failure_agx_mirror':'phase10AgxCoreCpu' in i and 'phase10CompressUnitGamutCpu' in i,
 'push_abi_unchanged':'static_assert(sizeof(PushConstants) == 128u' in b,
 'telemetry_capacity':'kTelemetryWords = 20u' in b,
 'phase9_tile_owner_preserved':'preToneChroma444FromInput' in sh and 'phase9TileModel0' in sh,
 'phase9_domain_owner_preserved':'POST_DEMOSAIC_RGB: outputRgb is authoritative' in demosaic and 'POST_COLOUR_TRANSFORM_RGB: colorRgb is authoritative' in demosaic,
 'yuv_legacy_presentation_preserved':'rgb = applyLocalTone(gid, rgb);' in run and 'YUV capture path' in run,
 'fllf_no_cpu_pixel_stage':'CPU_FLLF' not in b and 'fllfCpu' not in b,
 'explicit_phase10_domains':all(x in i for x in ['phase10GtmOutputDomain=','phase10FllfDomain=','phase10AgxRole=']),
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE10_0072_FINAL_ARCHITECTURE_FAIL='+','.join(failed))
print('PHASE10_0072_FINAL_ARCHITECTURE_PASS='+str(len(checks)))
