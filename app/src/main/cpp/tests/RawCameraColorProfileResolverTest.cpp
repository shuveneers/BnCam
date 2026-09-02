#include "../RawCameraColorProfileResolver.h"
#include <cassert>
#include <cmath>

using namespace bncam::color;

static RawCameraNativeHueSatProfile profile(bool dual) {
    RawCameraNativeHueSatProfile p{};
    p.profileId = "lens/physical";
    p.sourcePriority = 300;
    p.calibrationIlluminant1 = 17;
    p.calibrationIlluminant2 = dual ? 21 : 0;
    p.colorMatrix1 = {0.65f,0.15f,0.05f, 0.12f,0.75f,0.08f, 0.02f,0.12f,0.72f};
    p.colorMatrix2 = {0.58f,0.19f,0.06f, 0.10f,0.77f,0.09f, 0.03f,0.10f,0.74f};
    p.discoveryEffectiveCcm = {1.55f,-0.42f,-0.13f, -0.19f,1.31f,-0.12f, 0.02f,-0.28f,1.26f};
    p.hasColorMatrix2 = dual;
    p.hueDivisions = 2;
    p.saturationDivisions = 2;
    p.valueDivisions = 1;
    p.encoding = 0;
    p.hueSatData1 = {0,1,1, 2,1.05f,1, 0,1,1, -2,0.95f,1};
    if (dual) p.hueSatData2 = {0,1,1, 1,1.02f,1, 0,1,1, -1,0.98f,1};
    return p;
}

int main() {
    {
        auto p = profile(false);
        assert(p.valid());
        RawCameraProfileRegistrySnapshot s{{p}, 1};
        RawCameraColorProfileResolveRequest r{};
        r.currentEffectiveCcm = p.discoveryEffectiveCcm;
        auto resolved = resolveRawCameraColorProfile(s, r);
        assert(resolved.ready);
        assert(resolved.status == "READY_SINGLE_ILLUMINANT_HUESATMAP");
    }
    {
        auto p = profile(true);
        assert(p.valid());
        std::array<float,3> wb{};
        assert(rawCameraPredictProfileWbRgb(p, 5000.0f, wb));
        RawCameraProfileRegistrySnapshot s{{p}, 1};
        RawCameraColorProfileResolveRequest r{};
        r.currentEffectiveCcm = p.discoveryEffectiveCcm;
        r.currentWbRgb = wb;
        auto resolved = resolveRawCameraColorProfile(s, r);
        assert(resolved.ready);
        assert(resolved.status == "READY_DUAL_ILLUMINANT_ADAPTIVE_HUESATMAP");
        assert(std::abs(resolved.sceneCctKelvin - 5000.0f) < 80.0f);
        assert(resolved.hueSatWeightFirst >= 0.0f && resolved.hueSatWeightFirst <= 1.0f);
    }
    {
        auto p = profile(false);
        RawCameraProfileRegistrySnapshot s{{p}, 1};
        RawCameraColorProfileResolveRequest r{};
        r.currentEffectiveCcm = {0,1,0, 1,0,0, 0,0,1};
        auto resolved = resolveRawCameraColorProfile(s, r);
        assert(!resolved.ready);
    }
    return 0;
}
