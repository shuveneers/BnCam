#include "../RawCameraHueSatMapTelemetry.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::color;

static RawCameraNativeHueSatProfile makeProfile(bool dual) {
    RawCameraNativeHueSatProfile p{};
    p.profileId = "camera/physical";
    p.sourcePriority = 300;
    p.calibrationIlluminant1 = 17;
    p.calibrationIlluminant2 = dual ? 21 : 0;
    p.colorMatrix1 = {1,0,0, 0,1,0, 0,0,1};
    p.colorMatrix2 = p.colorMatrix1;
    p.discoveryEffectiveCcm = p.colorMatrix1;
    p.hueDivisions = 1;
    p.saturationDivisions = 2;
    p.valueDivisions = 1;
    p.encoding = 0;
    // saturation=0 identity cell, then one calibrated cell.
    p.hueSatData1 = {0.0f, 1.0f, 1.0f, 10.0f, 1.20f, 0.90f};
    if (dual) p.hueSatData2 = {0.0f, 1.0f, 1.0f, 30.0f, 0.80f, 1.10f};
    return p;
}

int main() {
    {
        const auto summary = summarizeRawCameraHueSatMapProfile(nullptr, 1.0f, 0.0f);
        assert(!summary.available);
        assert(summary.source == "NONE");
    }
    {
        auto p = makeProfile(false);
        const auto summary = summarizeRawCameraHueSatMapProfile(&p, 0.1f, 0.9f);
        assert(summary.available);
        assert(summary.illuminantCount == 1);
        assert(std::abs(summary.interpolationWeight) < 1.0e-6f);
        assert(std::abs(summary.meanAbsHueShift - 5.0) < 1.0e-6);
        assert(std::abs(summary.meanSaturationScale - 1.10) < 1.0e-6);
        assert(std::abs(summary.meanValueScale - 0.95) < 1.0e-6);
        assert(std::abs(summary.maxAbsHueShift - 10.0) < 1.0e-6);
        assert(summary.source == "OEM_DNGCREATOR");
    }
    {
        auto p = makeProfile(true);
        const auto summary = summarizeRawCameraHueSatMapProfile(&p, 0.25f, 0.75f);
        assert(summary.available);
        assert(summary.illuminantCount == 2);
        assert(std::abs(summary.interpolationWeight - 0.75f) < 1.0e-6f);
        // identity row remains zero/1/1; calibrated row becomes 25 deg, 0.9, 1.05.
        assert(std::abs(summary.meanAbsHueShift - 12.5) < 1.0e-6);
        assert(std::abs(summary.meanSaturationScale - 0.95) < 1.0e-6);
        assert(std::abs(summary.meanValueScale - 1.025) < 1.0e-6);
        assert(std::abs(summary.maxAbsHueShift - 25.0) < 1.0e-6);
    }
    std::cout << "RawCameraHueSatMapTelemetryTest PASS\n";
    return 0;
}
