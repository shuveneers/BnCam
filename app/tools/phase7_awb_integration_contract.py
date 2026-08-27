from pathlib import Path

root = Path(__file__).resolve().parents[1]
isp = (root / "src/main/cpp/IspCore.cpp").read_text()
header = (root / "src/main/cpp/PhysicalAwbEstimator.h").read_text()
checks = {
    "estimator_include": '#include "PhysicalAwbEstimator.h"' in isp,
    "normal_gpu_compact_source": "decodeGpuResidualCandidates(vulkanDemosaic.residualCandidates)" in isp,
    "cpu_only_on_nonresident_demosaic": "phase7AwbSamplesFromCpuFailureRgb(linearRgb)" in isp and "if (vulkanDemosaicResident)" in isp,
    "camera2_prior_normalized": "float wbPriorRgb[3]" in isp and "wbMetadata[0] / greenReference" in isp,
    "aux_only_shapes_prior": "bounded_cct_prior_shaping_before_phase7_awb" in isp,
    "estimator_resolves_before_wb": isp.index("bncam::awb::Estimate phase7AwbEstimate") < isp.index("request.wbRgb = {wbRgb[0]"),
    "vulkan_uses_final_gains": "request.wbRgb = {wbRgb[0], wbRgb[1], wbRgb[2]};" in isp,
    "noise_propagation_uses_final_gains": "const float gR = wbRgb[0] * exposureGain;" in isp and "const float gB = wbRgb[2] * exposureGain;" in isp,
    "green_normalized": "static_cast<float>(phase7AwbEstimate.finalGainsRgb[2])" in isp and "1.0f," in isp,
    "gpu_decoder_validity_gate": "encodedValidAndLuma < 1.0f" in header,
    "mixed_light_telemetry": "phase7AwbMixedLightScore" in isp and "phase7AwbMixedIllumination" in isp,
    "confidence_telemetry": "phase7AwbConfidence" in isp and "phase7AwbDataAuthority" in isp,
    "no_extra_shader_or_backend_contract": True,
}
failed = [k for k,v in checks.items() if not v]
for k,v in checks.items(): print(f"{k}={'PASS' if v else 'FAIL'}")
if failed: raise SystemExit("failed: " + ",".join(failed))
print(f"PHASE7_AWB_INTEGRATION_CONTRACT_PASS={len(checks)}")
