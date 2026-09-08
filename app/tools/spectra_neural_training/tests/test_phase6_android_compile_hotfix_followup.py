from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_conditioning_push_constants_cast_unsigned_tile_origins_explicitly():
    src = text("app/src/main/cpp/vulkan/VulkanNeuralRawDenoiseBackend.cpp")
    assert "static_cast<std::int32_t>(tile.inputX)" in src
    assert "static_cast<std::int32_t>(tile.inputY)" in src
    assert "CondPC pc{\n                            tile.inputX" not in src


def test_retired_isp_telemetry_uses_live_iso_and_zero_retired_scale():
    src = text("app/src/main/cpp/IspCore.cpp")
    assert '<< "; frameIso=" << actualIso' in src
    assert '<< "; phase4ResidualChromaStrengthScale=" << 0.0f' in src
    assert "referenceFrameIso" not in src
    assert "residualChromaStrengthScale" not in src
    assert '<< "; phase4ResidualBudgetActive=false"' in src
    assert '<< "; phase4ResidualAuthoritySource=RETIRED_N003"' in src


def test_profile_defaults_test_uses_adaptive_response_semantics():
    src = text("app/src/test/java/com/bncam/core/quality/SpectraProfileDefaultsTest.kt")
    assert ".adaptiveResponse" in src
    assert ".dynamicIso" not in src
