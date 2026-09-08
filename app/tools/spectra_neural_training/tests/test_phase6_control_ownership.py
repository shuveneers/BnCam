from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "app" / "src" / "main"
CPP = APP / "cpp"
JAVA = APP / "java" / "com" / "bncam"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_visible_neural_controls_are_one_shared_profile_state():
    ui = text(JAVA / "ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
    settings = text(JAVA / "data/settings/ProfileLensTuningSettings.kt")
    assert 'NEURAL_DENOISE_STRENGTH = "neural_denoise_strength"' in settings
    assert 'NEURAL_ADAPTIVE_RESPONSE = "neural_adaptive_response"' in settings
    assert 'title = "Neural Denoise Strength"' in ui
    assert 'title = "Adaptive Response"' in ui
    assert ui.count("ProfileIspKeys.NEURAL_DENOISE_STRENGTH") >= 4
    assert ui.count("ProfileIspKeys.SPECTRA_LUMA") >= 4
    assert ui.count("ProfileIspKeys.SPECTRA_CHROMA") >= 4
    denoise = ui.split("fun ProfileDenoiseSettingsScreen", 1)[1].split("fun ProfileSharpnessSettingsScreen", 1)[0]
    assert "DETAIL_NR_LUMINANCE," not in denoise.split("ResetPageToDefaultValuesButton", 1)[0]
    assert "Post-reconstruction luminance and colour noise reduction" not in denoise


def test_character_presets_are_transparent_six_control_vectors():
    preset = text(JAVA / "core/quality/SpectraProfileCharacter.kt")
    assert "val masterStrength: Float" in preset
    assert "val adaptiveResponse: Float" in preset
    for name in ("Natural", "Clean", "Texture", "Night"):
        assert f'name = "{name}"' in preset
    assert "masterStrength = 0.70f" in preset
    assert "adaptiveResponse = 0.45f" in preset
    assert "masterStrength = 0.85f" in preset
    assert "masterStrength = 0.60f" in preset
    assert "masterStrength = 0.95f" in preset
    assert "kotlin.math.abs(masterStrength - other.masterStrength)" in preset
    assert "kotlin.math.abs(adaptiveResponse - other.adaptiveResponse)" in preset


def test_runtime_reads_new_controls_and_retires_second_profile_nr_owner():
    render = text(JAVA / "core/quality/RenderQualityConfig.kt")
    resolver = text(JAVA / "core/quality/LibpatcherProfileResolver.kt")
    assert "neuralDenoiseStrength = readProfileFloatOrFallback" in render
    assert "ProfileIspKeys.NEURAL_DENOISE_STRENGTH" in render
    assert "neuralAdaptiveResponse = readProfileFloatOrFallback" in render
    assert "ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE" in render
    assert "val noiseReductionTuning = ProfileNoiseReductionTuning().sanitized()" in render
    runtime = resolver.split("fun runtimeProfileSettingSpecs", 1)[1].split("legacyNoiseCompatibilitySpecs", 1)[0]
    assert "NEURAL_DENOISE_STRENGTH" in runtime
    assert "NEURAL_ADAPTIVE_RESPONSE" in runtime
    assert "DETAIL_NR_LUMINANCE" not in runtime
    legacy = resolver.split("legacyNoiseCompatibilitySpecs", 1)[1]
    assert "DETAIL_NR_LUMINANCE" in legacy
    assert "SPECTRA_DYNAMIC_ISO" in legacy


def test_raw_transport_keeps_adaptive_response_separate_from_lens_dynamic_iso():
    image = text(JAVA / "core/engine/ImageUtils.kt")
    native = text(CPP / "native-lib.cpp")
    cfg = text(CPP / "NativeRenderQualityConfig.h")
    isp = text(CPP / "IspCore.cpp")
    assert "profileNoiseTuning?.neuralDenoiseStrength" in image
    assert "profileNoiseTuning?.neuralAdaptiveResponse" in image
    assert "val lensDynamicIsoCoeff = qualityConfig?.lensHardwareSettings?.dynamicIsoCoeff ?: 0.0f" in image
    assert "profileDynamicIsoBoost" not in image
    assert "float profileNeuralAdaptiveResponse = 0.45f;" in cfg
    assert "cfg.profileNeuralAdaptiveResponse = std::isfinite(profileNrLuminance)" in native
    neural_call = isp.split("projectVisibleProfileControlsToNeural", 1)[1].split(");", 1)[0]
    assert "uiConfig.profileNeuralAdaptiveResponse" in neural_call
    assert "uiConfig.lensDynamicIsoCoeff" not in neural_call


def test_yuv_legacy_profile_nr_transport_is_hard_neutral():
    image = text(JAVA / "core/engine/ImageUtils.kt")
    yuv = image.split("fun processNativeYuvSafe", 1)[1].split("fun processNativeYuvWithUltraHdrSafe", 1)[0]
    assert "profileNrLuminance = 0.0f" in yuv
    assert "profileNrLuminanceDetail = 0.5f" in yuv
    assert "profileNrLuminanceContrast = 0.0f" in yuv
    assert "profileNrColor = 0.0f" in yuv
    assert "profileNrColorDetail = 0.5f" in yuv
    assert "profileNrColorSmoothness = 0.5f" in yuv
    assert "profileNoiseReductionTuning?." not in yuv


def test_student_v1_missing_gain_split_does_not_force_ood_or_synthesize_iso_gain():
    policy = text(CPP / "SpectraNeuralProductionPolicy.h")
    isp = text(CPP / "IspCore.cpp")
    assert "!input.explicitGainMetadataValid || !out.framePhysics.valid()" not in policy
    assert "if (!out.framePhysics.valid())" in policy
    assert "explicitGainMetadataValid = false" in isp
    gain_block = isp.split("explicitGainMetadataValid = false", 1)[0][-1800:]
    assert "analogGain = 1.0f" in gain_block
    assert "digitalGain = 1.0f" in gain_block
    assert "baseIso" not in gain_block


def test_master_zero_has_direct_exact_bypass_path():
    policy = text(CPP / "SpectraNeuralProductionPolicy.h")
    assert "projectVisibleProfileControlsToNeural" in policy
    assert "std::clamp(masterStrength, 0.0f, 1.0f)" in policy
    assert "out.noiseReduction = out.enabled ? safeMaster : 0.0f;" in policy
    assert "out.controls.noiseReduction <= kNeuralAuthorityBypassEpsilon" in policy
    assert "NeuralBypassReason::ZeroAuthority" in policy
