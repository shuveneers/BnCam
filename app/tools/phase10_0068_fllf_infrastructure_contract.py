from pathlib import Path
r=Path(__file__).resolve().parents[1]
pol=(r/'src/main/cpp/FastLocalLaplacianPolicy.h').read_text()
h=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
c=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
sh=(r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
checks={
 'policy_exists':'resolveFastLocalLaplacianPlan' in pol,
 'policy_no_display_shoulder':'shoulderStart' not in pol and 'displayWhite' not in pol,
 'policy_noise_limited_lift':'0.82f * noise' in pol,
 'request_contract':'fllfPyramidLevels' in h and 'fllfEdgeStopEv' in h,
 'result_contract':'fllfPyramidBuildMs' in h and 'fllfRemapReconstructMs' in h,
 'two_scalar_buffers':'PersistentBuffer fllfGaussian_' in h and 'PersistentBuffer fllfCorrection_' in h,
 'descriptor_layout_13':'VkDescriptorSetLayoutBinding bindings[13]' in c and 'descriptorCount = 13u' in c,
 'descriptor_bindings':'infos[11].buffer = fllfGaussian_' in c and 'infos[12].buffer = fllfCorrection_' in c,
 'shader_bindings':'binding = 11' in sh and 'binding = 12' in sh,
 'destroy_owns_buffers':'&fllfGaussian_, &fllfCorrection_' in c,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0068_FLLF_INFRA_FAIL='+','.join(f))
print('PHASE10_0068_FLLF_INFRA_PASS='+str(len(checks)))
