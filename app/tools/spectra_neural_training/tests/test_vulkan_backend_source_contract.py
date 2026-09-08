from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]/"app"/"src"/"main"/"cpp"
B=(ROOT/"vulkan"/"VulkanNeuralRawDenoiseBackend.cpp").read_text()
R=(ROOT/"vulkan"/"VulkanNeuralResourceBridge.cpp").read_text()
ABI=(ROOT/"NeuralRawDenoiseBackend.h").read_text()
def test_exact_off_and_zero_bypass_before_resource_import_or_dispatch():
 for token in ("!request.controls.valid()", "!request.controls.enabled", "request.controls.noiseReduction <= kNeuralAuthorityBypassEpsilon"):
  assert token in B
 pos=B.index("if (!request.controls.valid() || !request.controls.enabled ||")
 assert pos < B.index("VulkanNeuralResourceBridge::resolveInput", pos)
 assert pos < B.index("recordAndSubmit", pos)
 assert "NeuralDisabled" in B and "ZeroAuthority" in B
def test_no_full_frame_cpu_raw_fallback_path():
 forbidden=("AHardwareBuffer_lock","AHardwareBuffer_lockPlanes","outputRaw16","cv::Mat","memcpy(raw","CPU_FALLBACK")
 for token in forbidden:
  assert token not in B and token not in R
 assert "GpuStagingFallback" in R
 assert "DIRECT_IMPORT_REQUIRES_GPU_BLOB_BUFFER" in R
def test_persistent_slots_async_and_master_writeback():
 assert "submitAsync" in B and "vkGetFenceStatus" in B and "NEURAL_NO_FREE_INFLIGHT_SLOT" in B
 assert "slots_.resize" in B and "ensureSlots" in B
 assert "request.controls.noiseReduction" in B
 assert "fullFrameCpuReadbackBytes=0" in (ROOT/"vulkan"/"VulkanNeuralRawDenoiseBackend.h").read_text()
def test_highlight_extension_boundary_is_evidence_only():
 assert "originalSaturationEvidenceRequested" in ABI
 assert "originalSaturationMaskOutput" in ABI and "originalHeadroomEvidenceOutput" in ABI
 assert "Highlight Reconstruction" in ABI
 assert "reconstruct" not in (ROOT/"vulkan"/"shaders"/"neural_writeback.comp").read_text().lower()
def test_aligned_weight_offsets_and_stride_phase_safe_halo():
 exp=(Path(__file__).resolve().parents[1]/"spectra_train"/"export_vulkan_package.py").read_text()
 plan=(ROOT/"vulkan"/"VulkanNeuralExecutionPlan.cpp").read_text()
 assert "(-len(weight_blob)) % 16" in exp
 assert "alignUp(m.halo,8u)" in plan

def test_ahb_import_is_capability_gated_synchronized_and_dedicated():
 assert "vkGetPhysicalDeviceExternalBufferProperties" in R
 assert "VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT" in R
 assert "VkImportAndroidHardwareBufferInfoANDROID" in R
 assert "VkMemoryDedicatedAllocateInfo" in R
 assert "out.dedicatedAllocation = true" in R
 assert "NEURAL_AHB_PRODUCER_SYNC_MISSING" in R
 assert "waitSemaphore" in R and "pWaitSemaphores" in B

def test_edge_tiles_and_final_heads_match_phase3_boundary_semantics():
 layout=(ROOT/'vulkan'/'VulkanNeuralTensorLayout.h').read_text()
 conv=(ROOT/'vulkan'/'shaders'/'neural_conv.comp').read_text()
 assert "fictitious" in layout and "r.validX=r.centerX-r.inputX" in layout
 assert "enforceDomain" in conv and "inputGlobalX" in conv
 assert 'op.weight == "residual_head.weight"' in B
 assert 'op.weight == "posterior_head.weight"' in B

def test_descriptor_sets_are_reused_across_tiles_not_allocated_per_tile():
 assert "setCursor = 0u" in B
 assert "updateDescriptorSets = tileIndex == 0u" in B
 assert "NEURAL_DESCRIPTOR_PLAN_DISPATCH_COUNT_MISMATCH" in B

def test_resolve_does_not_hold_backend_mutex_while_waiting_for_gpu():
 wait=B.index("const VkResult waitResult = vkWaitForFences")
 pre=B.rfind("}",0,wait)
 lock=B.rfind("std::lock_guard<std::mutex> lock(mutex_)",0,wait)
 assert lock < pre < wait
