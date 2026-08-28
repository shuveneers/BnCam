from pathlib import Path
r=Path(__file__).resolve().parents[1]
files=[
 r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp',
 r/'src/main/cpp/vulkan/shaders/raw_preview.comp',
 r/'src/main/cpp/vulkan/shaders/raw_preview_image.comp',
 r/'src/main/cpp/vulkan/shaders/isp_exposure_tone.comp',
]
texts=[p.read_text() for p in files]
i=(r/'src/main/cpp/IspCore.cpp').read_text()
t=(r/'src/test/cpp/AgxPhase10DomainTest.cpp').read_text()
checks={
 'all_agx_gpu_paths_linearize_2p2': all('pow(abs(val), vec3(2.2))' in s for s in texts),
 'linearization_after_outset': all(s.index('AGX_OUTSET_MATRIX * val') < s.index('pow(abs(val), vec3(2.2))') for s in texts),
 'capture_before_profile': texts[0].index('pow(abs(val), vec3(2.2))') < texts[0].index('vec3 applyToneLookLut'),
 'preview_before_srgb': all(s.index('pow(abs(val), vec3(2.2))') < s.index('rgb = srgb(rgb);') for s in texts[1:3]),
 'cpu_agx_mirror_2p2': 'linearizeAgxOutset' in i and 'std::pow(std::abs(v), 2.2f)' in i,
 'signed_extension_preserved': all('sign(val) * pow(abs(val), vec3(2.2))' in s for s in texts),
 'single_final_gamut_owner_preserved': 'POST_PROFILE_SIGNED_LUMA_PRESERVING' in i,
 'telemetry_eotf_truth': 'phase10AgxEotf=' in i and 'SIGNED_2P2_TO_DISPLAY_LINEAR' in i and 'phase10AgxOutputDomain=' in i,
 'numerical_test_linear_domain': 'visibleMean > 0.47f && visibleMean < 0.53f' in t and 'mean > 0.19f && mean < 0.24f' in t,
 'old_false_middle_gray_gate_removed': 'mean > 0.44f && mean < 0.56f' not in t,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0076_AGX_EOTF_FAIL='+','.join(f))
print('PHASE10_0076_AGX_EOTF_PASS='+str(len(checks)))
