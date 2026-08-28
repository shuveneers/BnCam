from pathlib import Path
r=Path(__file__).resolve().parents[1]
sh=(r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
i=(r/'src/main/cpp/IspCore.cpp').read_text()
checks={
 'agx_core_inset':'AGX_INSET_MATRIX' in sh,
 'agx_core_log':'const float min_ev = -12.47393;' in sh and 'const float max_ev = +4.026069;' in sh,
 'agx_core_sigmoid':'agxDefaultContrastApprox' in sh,
 'agx_core_outset':'AGX_OUTSET_MATRIX * val' in sh,
 'embedded_second_toe_removed':'kAgxBlackPedestal' not in sh and 'pow(val, vec3(1.08))' not in sh,
 'embedded_sat_restore_removed':'satBoost' not in sh,
 'automatic_raw_vibrance_removed':'phase10AppliedBaseVibrance = phase10RawToneArchitecture ? 1.0f' in i,
 'profile_creative_controls_remain':'profileVibrance' in sh and 'profileSaturation' in sh,
 'cpu_fallback_agx':'phase10AgxCoreCpu' in i,
 'cpu_fallback_luma_gamut':'phase10CompressUnitGamutCpu' in i,
 'raw_cpu_shoulder_still_off':'!phase10RawToneArchitecture && postNeutralY > shoulderStart' in i,
 'final_order':'applyFllfLocalExposure' in sh and sh.find('applyFllfLocalExposure') < sh.find('applyAgXTonemap(rgb)') if 'applyAgXTonemap(rgb)' in sh else False,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0071_AGX_SOLE_DRT_FAIL='+','.join(f))
print('PHASE10_0071_AGX_SOLE_DRT_PASS='+str(len(checks)))
