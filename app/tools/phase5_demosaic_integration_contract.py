#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
h = (ROOT / "app/src/main/cpp/Demosaic.h").read_text()
cpp = (ROOT / "app/src/main/cpp/Demosaic.cpp").read_text()
isp = (ROOT / "app/src/main/cpp/IspCore.cpp").read_text()
backend_h = (ROOT / "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h").read_text()
shader = (ROOT / "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").read_text()

checks = {
    "product_set_malvar_rcd_amaze": (
        "normal_mode_forces_malvar_2004" in h and
        all(x in cpp for x in [
            "legacy_slot_3_forces_rcd_inspired",
            "legacy_slot_2_forces_amaze_inspired"])),
    "retired_routes_hardened": all(x in isp for x in [
        "retired_bilinear_execution_hardened_to_rcd_inspired",
        "retired_menon_execution_hardened_to_amaze_inspired",
        "legacyBilinearProductAvailable=false",
        "legacyMenonProductAvailable=false"]),
    "physical_cfa_evidence_reaches_demosaic": all(x in isp for x in [
        "demosaicPhysicalEvidenceActive",
        "PHYSICAL_SINGLE_FRAME_SO",
        "demosaicExecutionCfaEvidence"]),
    "physical_noise_or_zero": all(x in isp for x in [
        "demosaicPhysicalNoiseContextAvailable",
        "UNAVAILABLE_ZERO_AUTHORITY",
        "demosaicNoiseAuthorityContract=PHYSICAL_SO_OR_ZERO"]),
    "sampled_sensel_contract_exposed": "demosaicSamplePreservationContract=EXACT_SAMPLED_CFA_SENSELS" in isp,
    "gpu_supports_product_set": all(x in backend_h for x in [
        "MALVAR_2004 = 1u", "RCD_INSPIRED = 3u", "AMAZE_INSPIRED = 4u"]),
    "malvar_v2": "malvarStabilizeMissingColor" in cpp and "malvarStabilizeMissingColor" in shader,
    "rcd_v2": "rcdDirectionalWeight" in cpp and "rcdWeight" in shader,
    "amaze_v2": "amazeRetainHighOrderDetail" in cpp and "amazeRetainHighOrderDetail" in shader,
    "push_contract_has_physical_sigma": all(x in backend_h for x in [
        "float noiseSigmaY", "float noiseSigmaChroma", "float noisePressure"]),
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(f"{k}: {'PASS' if v else 'FAIL'}")
if failed: raise SystemExit("FAILED: " + ", ".join(failed))
print("PHASE5_DEMOSAIC_INTEGRATION_CONTRACT: PASS")
