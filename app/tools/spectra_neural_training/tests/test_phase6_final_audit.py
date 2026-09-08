from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app" / "src" / "main" / "cpp"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "bncam"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_01_master_neural_denoise_strength_is_visible_direct_and_zero_bypasses():
    ui = text(JAVA / "ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
    policy = text(CPP / "SpectraNeuralProductionPolicy.h")
    assert 'title = "Neural Denoise Strength"' in ui
    assert "projectVisibleProfileControlsToNeural" in policy
    assert "std::clamp(masterStrength, 0.0f, 1.0f)" in policy
    assert "NeuralBypassReason::ZeroAuthority" in policy


def test_02_luma_control_uses_fixed_neural_residual_basis():
    control = text(CPP / "SpectraNeuralResidualControl.h")
    assert "controls.lumaNoise" in control
    assert "orthonormal" in control.lower()
    assert "residual" in control.lower()


def test_03_chroma_control_uses_the_same_residual_not_a_second_filter():
    control = text(CPP / "SpectraNeuralResidualControl.h")
    shader = text(CPP / "vulkan/shaders/neural_writeback.comp")
    assert "controls.chromaNoise" in control
    assert "componentControlled" in shader
    assert "chromaAuthority" in shader
    assert "bilateral" not in shader.lower()


def test_04_detail_protection_is_a_posterior_aware_gate_only():
    shader = text(CPP / "vulkan/shaders/neural_writeback.comp")
    assert "posteriorRisk" in shader
    assert "detailGate" in shader
    assert "pc.detailProtection" in shader
    assert "vec4 delta=controlled" in shader


def test_05_low_frequency_cleanup_comes_from_coarse_neural_residual_context():
    shader = text(CPP / "vulkan/shaders/neural_writeback.comp")
    assert "lowFrequencyCleanup" in shader
    assert "coarse" in shader
    assert "fine" in shader
    assert "componentControlled(coarse" in shader


def test_06_adaptive_response_is_sigma_snr_based_and_not_iso_based():
    shader = text(CPP / "vulkan/shaders/neural_writeback.comp")
    isp = text(CPP / "IspCore.cpp")
    neural_block = isp.split("projectVisibleProfileControlsToNeural", 1)[1].split(");", 1)[0]
    assert "invSnr" in shader
    assert "pc.adaptiveResponse" in shader
    assert "captureSensitivityIso" not in shader
    assert "isoPressure" not in shader
    assert "profileNeuralAdaptiveResponse" in neural_block
    assert "lensDynamicIsoCoeff" not in neural_block


def test_07_character_presets_are_only_visible_control_vectors():
    preset = text(JAVA / "core/quality/SpectraProfileCharacter.kt")
    ui = text(JAVA / "ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
    for name in ("Natural", "Clean", "Texture", "Night"):
        assert f'name = "{name}"' in preset
    assert "there is no hidden preset state" in ui.lower()
    assert "NEURAL_DENOISE_STRENGTH to values.masterStrength" in ui
    assert "NEURAL_ADAPTIVE_RESPONSE to values.adaptiveResponse" in ui


def test_08_profile_nr_ownership_is_consolidated_to_one_neural_state():
    render = text(JAVA / "core/quality/RenderQualityConfig.kt")
    image = text(JAVA / "core/engine/ImageUtils.kt")
    ui = text(JAVA / "ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
    denoise = ui.split("fun ProfileDenoiseSettingsScreen", 1)[1].split("fun ProfileSharpnessSettingsScreen", 1)[0]
    assert "ProfileIspKeys.NEURAL_DENOISE_STRENGTH" in denoise
    assert "ProfileIspKeys.SPECTRA_LUMA" in denoise
    assert "ProfileIspKeys.SPECTRA_CHROMA" in denoise
    assert "val noiseReductionTuning = ProfileNoiseReductionTuning().sanitized()" in render
    yuv = image.split("fun processNativeYuvSafe", 1)[1].split("fun processNativeYuvWithUltraHdrSafe", 1)[0]
    assert "profileNoiseReductionTuning?." not in yuv


def test_09_posterior_variance_returns_to_spectra_core_as_compact_gpu_summary():
    bridge_h = text(CPP / "vulkan/VulkanNeuralRawProductionBridge.h")
    bridge_cpp = text(CPP / "vulkan/VulkanNeuralRawProductionBridge.cpp")
    runtime_h = text(CPP / "vulkan/VulkanRuntime.h")
    assert "posteriorSummaryReady" in bridge_h
    assert "posteriorMeanVarianceCfa" in bridge_h
    assert "one vec4 per 8x8 packed workgroup" in bridge_h
    assert "compactPosteriorReadbackBytes" in bridge_cpp
    assert "posteriorSummaryReady" in runtime_h


def test_10_posterior_uncertainty_propagates_through_lsc_demosaic_wb_ccm():
    isp = text(CPP / "IspCore.cpp")
    assert "neuralProductionTrace.posteriorSummaryReady" in isp
    assert "neuralPosteriorLscPropagationReady" in isp
    assert "posteriorMeanVarianceCfa" in isp
    assert "preDemosaic" in isp
    assert "postDemosaic" in isp
    assert "postWb" in isp or "postWB" in isp
    assert "postColourTransform" in isp
    assert "propagateColourMatrix" in isp


def test_11_creative_detail_reads_propagated_noise_without_becoming_a_denoiser():
    isp = text(CPP / "IspCore.cpp")
    assert "physically propagated posterior" in isp
    assert "profile" in isp[isp.find("physically propagated posterior")-1600:isp.find("physically propagated posterior")+2200:].lower()
    # No neural writeback is added to the creative detail stage; covariance is measurement/protection input.
    assert "Sensor S/O and propagated covariance remain measurement-only" in isp


def test_12_final_owner_and_failure_boundary_has_no_classical_neural_fallback():
    forbidden = (
        "SpectraContextFusionNoRegret.h",
        "SpectraChromaNoRegretPolicy.h",
        "SpectraContextFusionChromaAuthority.h",
        "SpectraLowFrequencyCorrectionCeiling.h",
        "SpectraAnisotropicDetail.h",
        "SpectraMultiscaleContext.h",
        "SpectraMultiscaleResidualConsensus.h",
        "SpectraMultiscaleChromaContext.h",
        "SpectraCfaSurfaceClassifier.h",
        "SpectraCfaOrthonormalSupport.h",
    )
    production = "\n".join(
        text(path) for path in (
            CPP / "SpectraNeuralProductionPolicy.h",
            CPP / "vulkan/VulkanNeuralRawProductionBridge.cpp",
            CPP / "vulkan/VulkanNeuralRawDenoiseBackend.cpp",
            CPP / "vulkan/VulkanRuntime.cpp",
            CPP / "IspCore.cpp",
        )
    )
    for symbol in forbidden:
        assert symbol not in production
    neural_runtime = "\n".join(
        text(path).lower() for path in (
            CPP / "vulkan/VulkanNeuralRawProductionBridge.cpp",
            CPP / "vulkan/VulkanNeuralRawDenoiseBackend.cpp",
        )
    )
    assert "opencv" not in neural_runtime
    assert "cv::" not in neural_runtime
    assert "cpu fallback" not in neural_runtime
    assert "originalpublished" in text(CPP / "vulkan/VulkanRuntime.cpp").lower()
