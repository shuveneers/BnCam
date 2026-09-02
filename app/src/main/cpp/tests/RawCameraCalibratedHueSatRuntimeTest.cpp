#include "../RawCameraCalibratedHueSatRuntime.h"
#include <cassert>
#include <cmath>
using namespace bncam::color;

static RawCameraHueSatMap identityMap() {
    RawCameraHueSatMap m{};
    m.hueDivisions=2; m.saturationDivisions=2; m.valueDivisions=1;
    m.source=RawCameraHueSatMapSource::DNG_PROFILE_DATA_1;
    m.sourceId="p"; m.trustedCalibration=true;
    m.entries.resize(4);
    return m;
}
int main() {
    auto m=identityMap();
    bool applied=false;
    const RawCameraVec3 in{1.6f,0.5f,0.2f};
    auto out=rawCameraApplyCalibratedHueSatMapLinearSrgb(m,in,&applied);
    assert(applied);
    for(int i=0;i<3;i++) assert(std::isfinite(out[i]));
    assert(std::abs(out[0]-in[0]) < 2.0e-4f);
    assert(std::abs(out[1]-in[1]) < 2.0e-4f);
    assert(std::abs(out[2]-in[2]) < 2.0e-4f);

    auto second=m;
    second.entries[1].saturationScale=0.5f;
    auto blended=rawCameraBlendHueSatMaps(m,&second,0.25f);
    assert(blended.productEligible());
    assert(std::abs(blended.entries[1].saturationScale-0.625f)<1e-6f);
    return 0;
}
