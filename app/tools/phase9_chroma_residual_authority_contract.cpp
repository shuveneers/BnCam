#include "../src/main/cpp/SpectraPhysicalBaselineNr.h"
#include "../src/main/cpp/SpectraNoiseCalibration.h"

#include <cmath>
#include <iostream>

namespace {
int check(bool ok, const char* name, int& passed) {
    if (!ok) { std::cerr << "FAIL: " << name << '\n'; return 1; }
    ++passed; return 0;
}
}

int main() {
    int passed = 0;
    const auto p = bncam::spectra2::resolvePhysicalPreToneChroma(
            true, true, false, 0.40f, 1.0f, 0.2168f);
    if (check(p.enabled && p.physicalBaselineActive, "physical pre-tone baseline stays active", passed)) return 1;
    if (check(p.finalStrength > 0.70f && p.finalStrength < 0.76f,
              "device-evidence chroma authority raised without saturation", passed)) return 1;
    if (check(p.residualHeadroom > 0.88f && p.residualHeadroom < 0.91f,
              "upstream authority not over-credited", passed)) return 1;
    const auto quiet = bncam::spectra2::resolvePhysicalPreToneChroma(
            true, true, false, 0.0f, 0.0f, 0.0f);
    if (check(quiet.finalStrength >= 0.57f && quiet.finalStrength <= 0.59f,
              "quiet baseline floor unchanged", passed)) return 1;

    bncam::spectra2::ResidualObservation obs{};
    obs.lowFrequencyChromaValidTileCount = obs.kLowFrequencyChromaGridSize;
    for (std::size_t i=0; i<obs.kLowFrequencyChromaGridSize; ++i) {
        const int x = static_cast<int>(i % obs.kLowFrequencyChromaGridColumns);
        const int y = static_cast<int>(i / obs.kLowFrequencyChromaGridColumns);
        obs.lowFrequencyChromaFieldValid[i] = 1u;
        obs.lowFrequencyChromaFieldRG[i] = 0.00065f * float(x - 7) + 0.00015f * float(y - 5);
        obs.lowFrequencyChromaFieldBG[i] = -0.00045f * float(x - 7) + 0.00012f * float(y - 5);
        obs.lowFrequencyMeanLuma[i] = 0.10f;
    }
    const auto cloud = bncam::spectra2::buildChromaCloudCorrectionPlan(
            obs, 0.60f, 0.90f, 0.60f, 0.20f, 0.0040f,
            2.0f, 1.8f, 1.40f, 1.30f);
    if (check(cloud.ready, "supported low-frequency cloud plan remains ready", passed)) return 1;
    if (check(cloud.redAuthority > 0.115f && cloud.redAuthority <= 0.26f,
              "red cloud authority raised but bounded", passed)) return 1;
    if (check(cloud.blueAuthority > 0.070f && cloud.blueAuthority <= 0.26f,
              "blue cloud authority raised but bounded", passed)) return 1;
    if (check(cloud.maxAbsoluteCorrection <= 0.00201f,
              "measured residual half-rms safety budget preserved", passed)) return 1;
    if (check(cloud.correctionP90 > 1.0e-7f && cloud.correctionP90 <= cloud.maxAbsoluteCorrection,
              "cloud correction remains evidence-derived and capped", passed)) return 1;

    std::cout << "PHASE9_CHROMA_RESIDUAL_AUTHORITY_CONTRACT_PASS=" << passed << '\n';
    return passed == 9 ? 0 : 2;
}
