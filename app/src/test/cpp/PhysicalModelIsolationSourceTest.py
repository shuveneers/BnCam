"""Lock the production CPU/GPU pixel path to 8aee2d2; allow diagnostics only."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASELINE = "8aee2d298bffb2daf9f1640e7ff654ab8c7a46bc"
FILES = ["PhysicalChromaDenoise.h", "PhysicalLumaDenoise.h", "PhysicalNoiseAuthority.h",
         "SpectraNoisePropagation.h", "native-lib.cpp",
         "vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
         "vulkan/VulkanSpectraResidentDemosaicBackend.h",
         "vulkan/shaders/physical_chroma_denoise.glsl",
         "vulkan/shaders/physical_luma_denoise.glsl",
         "vulkan/shaders/spectra_demosaic_resident.comp", "IspCore.cpp"]
for name in FILES:
    path = "app/src/main/cpp/" + name
    reference = subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT).decode().replace("\r\n", "\n")
    current = (ROOT / path).read_text(encoding="utf-8").replace("\r\n", "\n")
    if name == "IspCore.cpp":
        blocks = re.findall(r"(?m)^ *// BEGIN_PHYSICAL_MODEL_ONLY\n.*?^ *// END_PHYSICAL_MODEL_ONLY\n", current, re.S)
        assert len(blocks) == 2, "Expected only include and diagnostic stream insertion"
        assert blocks[0].splitlines()[1] == '#include "PhysicalNoisePropagation.h"'
        # Exact allow-list: this must remain a formatting expression with const inputs.
        expected = '''            // BEGIN_PHYSICAL_MODEL_ONLY
            // Read-only prediction: do not feed corrected covariance/confidence into
            // baselinePhysicalChroma/Luma, downstream tone state, or GPU push constants.
            << bncam::physical::formatNoisePredictionOnly(
                    residualNoiseState.preDemosaic,
                    resolvedDemosaicNoiseModel(demosaicResolution.algorithm),
                    residualNoiseState.postDemosaic, physicalNoiseStatisticsActive,
                    meta.calibration.spectraProcessingMode == 0 &&
                    !neuralPosteriorSeedApplied && !autoHybridUsedForOutput)
            // END_PHYSICAL_MODEL_ONLY
'''
        assert blocks[1] == expected, "Prediction must never enter a pixel consumer"
        for block in blocks:
            current = current.replace(block, "")
    assert current == reference, f"Pixel path differs from 8aee2d2: {name}"
print("CPU filters, GPU shaders/transport, shared covariance and ISP pixel wiring match 8aee2d2")
