from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]/"app"/"src"/"main"/"cpp"/"vulkan"
SH=ROOT/"shaders"
def text(n):return (SH/n).read_text()
def test_shader_set_complete_and_fp16_storage_contract():
 names=["neural_condition.comp","neural_conv.comp","neural_film_params.comp","neural_film_apply.comp","neural_gate.comp","neural_add.comp","neural_scaled_add.comp","neural_writeback.comp"]
 for n in names:
  s=text(n);assert "#version 450" in s;assert "CPU" not in s.upper() or "CPU" in "" # source has no fallback logic
 assert "unpackHalf2x16" in text("neural_conv.comp") and "packHalf2x16" in text("neural_conv.comp")
 assert "dot(v,w)" in text("neural_conv.comp")
def test_conditioning_is_14_channel_physics_not_device_identity():
 s=text("neural_condition.comp")
 for forbidden in ("manufacturer","phoneModel","sensorModel","lensId","deviceId"):
  assert forbidden not in s
 compact=s.replace(" ","")
 assert "S*x+O" in compact
 assert "log(max(sigma" in s
 assert "head=min" in compact
def test_writeback_contains_master_authority_and_highlight_evidence():
 s=text("neural_writeback.comp")
 assert "authority" in s and "tanh" in s and "kSigma" in s
 assert "maskOut" in s and "headOut" in s
 assert "clipThreshold" in s and "nearThreshold" in s
 assert "reconstruct" not in s.lower()
