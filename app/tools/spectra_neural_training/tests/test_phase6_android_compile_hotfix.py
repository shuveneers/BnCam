from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_removed_classical_telemetry_symbols_do_not_reenter_isp_core():
    src = text("app/src/main/cpp/IspCore.cpp")
    for symbol in ("baseChromaNrStrength", "pass0NoRegret", "pass1NoRegret", "spectraNoRegretP0", "spectraNoRegretP1"):
        assert symbol not in src


def test_profile_noise_tuning_exposes_read_only_legacy_master_alias():
    src = text("app/src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
    assert "val spectraStrength: Float" in src
    assert "get() = neuralDenoiseStrength" in src
    assert "var spectraStrength" not in src
    assert "spectraStrength =" not in src


def test_character_values_keep_primary_six_control_api_and_legacy_constructor_only():
    src = text("app/src/main/java/com/bncam/core/quality/SpectraProfileCharacter.kt")
    assert "val masterStrength: Float" in src
    assert "val adaptiveResponse: Float" in src
    assert "constructor(\n        dynamicIso: Float" in src
    assert "masterStrength = SpectraProfileDefaults.MASTER_STRENGTH" in src
    assert "adaptiveResponse = dynamicIso" in src


def test_production_ui_still_uses_primary_neural_controls():
    src = text("app/src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
    assert "ProfileIspKeys.NEURAL_DENOISE_STRENGTH" in src
    assert "ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE" in src
    assert "masterStrength = neuralMaster" in src
    assert "adaptiveResponse = adaptiveResponse" in src
