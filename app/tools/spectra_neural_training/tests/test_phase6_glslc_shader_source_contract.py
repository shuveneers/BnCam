from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
SHADERS = ROOT / "app/src/main/cpp/vulkan/shaders"

C4_SHADERS = (
    "neural_add.comp",
    "neural_condition.comp",
    "neural_conv.comp",
    "neural_film_apply.comp",
    "neural_film_params.comp",
    "neural_gate.comp",
    "neural_scaled_add.comp",
    "neural_writeback.comp",
)


def test_runtime_ssbo_arrays_are_not_function_parameters():
    forbidden = re.compile(
        r"\b(?:const\s+|inout\s+|out\s+|in\s+)?"
        r"(?:uint|int|float|vec[234]|uvec[234]|ivec[234])\s+\w+\s*\[\s*\]\s*[,)]"
    )
    for name in C4_SHADERS:
        text = (SHADERS / name).read_text(encoding="utf-8")
        assert not forbidden.search(text), name
        assert "#define loadC4" in text, name
        assert "const uint words[]" not in text, name
        assert "inout uint words[]" not in text, name


def test_remaining_lsc_does_not_use_reserved_coherent_identifier():
    text = (SHADERS / "neural_remaining_lsc.comp").read_text(encoding="utf-8")
    assert re.search(r"\bbool\s+coherent\b", text) is None
    assert "edgeCoherent" in text
    assert re.search(r"\bcoherent\s*=", text) is None


def test_hotfix_does_not_change_shader_interfaces():
    # The hotfix is source-syntax only: descriptor/push-constant declarations stay present.
    writeback = (SHADERS / "neural_writeback.comp").read_text(encoding="utf-8")
    assert "layout(set=0,binding=9,std430) readonly buffer Confidence" in writeback
    remaining = (SHADERS / "neural_remaining_lsc.comp").read_text(encoding="utf-8")
    for binding in range(5):
        assert f"binding = {binding}" in remaining
