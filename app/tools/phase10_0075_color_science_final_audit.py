from pathlib import Path
r=Path(__file__).resolve().parents[1]
i=(r/'src/main/cpp/IspCore.cpp').read_text()
p=(r/'src/main/cpp/ProfileColorManagement.h').read_text()
shaders=[
 r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp',
 r/'src/main/cpp/vulkan/shaders/raw_preview.comp',
 r/'src/main/cpp/vulkan/shaders/raw_preview_image.comp',
 r/'src/main/cpp/vulkan/shaders/isp_exposure_tone.comp',
]
texts=[x.read_text() for x in shaders]
raw_cpu=i[i.index('const auto applyCpuToneAndProfile'):i.index('if (!vulkanToneApplied)', i.index('const auto applyCpuToneAndProfile'))]
checks={
 'all_gpu_agx_absolute_domain':all('const float min_ev = -12.47393;' in s and ('const float max_ev = +4.026069;' in s or 'const float max_ev = 4.026069;' in s) for s in texts),
 'cpu_agx_absolute_domain':'constexpr float minEv = -12.47393f;' in i and 'constexpr float maxEv = 4.026069f;' in i,
 'no_old_agx_domain':all('const float min_ev = -10.0;' not in s and 'const float max_ev = +6.5;' not in s for s in texts),
 'signed_agx_outset_capture':'val = AGX_OUTSET_MATRIX * val;' in texts[0] and 'max(vec3(0.0), AGX_OUTSET_MATRIX * val)' not in texts[0],
 'signed_agx_outset_preview':all('val = AGX_OUTSET_MATRIX * val;' in s for s in texts[1:3]),
 'single_final_cpu_gamut':'applyBncamProfileColorManagement(r, g, b, uiConfig, false);' in raw_cpu and raw_cpu.count('phase10CompressUnitGamutCpu(cv::Vec3f(r, g, b))') == 1,
 'raw_cpu_skips_legacy_post_agx_sat':'if (!phase10RawToneArchitecture) {\n                    float finalLuma = curvedLuma;' in raw_cpu,
 'raw_cpu_no_obsolete_boost_constants':'invNeutral' not in raw_cpu and 'invSatShadow =' not in raw_cpu and 'invVibrance =' not in raw_cpu,
 'final_owner_telemetry':'phase10OutputGamutOwner=' in i and 'POST_PROFILE_SIGNED_LUMA_PRESERVING' in i,
 'agx_allocation_telemetry':'phase10AgxAllocationMin=' in i and 'phase10AgxAllocationMax=' in i,
 'color_pair_telemetry':'phase10ColorPairOwner=' in i and 'CAMERA2_EXACT_FRAME_PAIR' in i,
 'profile_finalizer_luma_preserving':'bncamCompressToUnitGamutPreserveLuma' in p,
 'phase9_owner_preserved':'FUSED_AWB_CCM_CLIPPING_AWARE_HIGHLIGHT_GAMUT_V3' in i,
 'phase9_domain_repair_preserved':'POST_DEMOSAIC_RGB' in (r/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text(),
 'no_cpu_fllf_pixel_pipeline':'CPU_FLLF' not in i and 'fllfCpu' not in i,
}
f=[x for x,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0075_COLOR_SCIENCE_FINAL_AUDIT_FAIL='+','.join(f))
print('PHASE10_0075_COLOR_SCIENCE_FINAL_AUDIT_PASS='+str(len(checks)))
