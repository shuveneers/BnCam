from pathlib import Path
r=Path(__file__).resolve().parents[1]
sh=(r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
c=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
checks={
 'packed_layout':'FllfPyramidLayout' in c and 'totalFloats' in c,
 'half_res_base':'(frameWidth + 1u) / 2u' in c and '(frameHeight + 1u) / 2u' in c,
 'bounded_levels':'kFllfMaxLevels = 6u' in c,
 'two_scalar_allocs':'fllfScalarBytes' in c and 'fllfGaussian_' in c and 'fllfCorrection_' in c,
 'level0_log_luma':'buildFllfGaussianLevel0' in sh and 'log2(max(1.0e-6, lumaOf(rgb)) + 0.0025)' in sh,
 'gaussian_downsample':'buildFllfGaussianDownsample' in sh and 'sum * (1.0 / 16.0)' in sh,
 'barrier_per_level':'previousReady.buffer = fllfGaussian_.buffer' in c,
 'resident_dispatch':'push.mode = 10u' in c and 'push.mode = 11u' in c,
 'no_cpu_image_roundtrip':'fllfGaussian_.mapped' not in c and 'fllfCorrection_.mapped' not in c,
 'timing':'fllfPyramidBuildMs' in c and 'queryCount = 8u' in c,
 'telemetry_capacity':'kTelemetryWords = 20u' in c,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0069_FLLF_PYRAMID_FAIL='+','.join(f))
print('PHASE10_0069_FLLF_PYRAMID_PASS='+str(len(checks)))
