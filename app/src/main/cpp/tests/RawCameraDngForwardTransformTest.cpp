#include "../RawCameraDngForwardTransform.h"
#include <cassert>
#include <cmath>

using namespace bncam::color;

int main() {
    RawCameraNativeHueSatProfile p{};
    p.profileId = "synthetic";
    p.sourcePriority = 300;
    p.calibrationIlluminant1 = 21; // D65, only validity provenance here.
    p.colorMatrix1 = {1,0,0, 0,1,0, 0,0,1};
    p.discoveryEffectiveCcm = {1,0,0, 0,1,0, 0,0,1};
    p.forwardMatrix1 = {1,0,0, 0,1,0, 0,0,1};
    p.hasForwardMatrix1 = true;
    p.hueDivisions = 1; p.saturationDivisions = 2; p.valueDivisions = 1;
    p.hueSatData1 = {0,1,1, 0,1,1};
    assert(p.valid());

    auto r = resolveRawCameraDngForwardTransform({&p, 1.0f, 0.0f, {2.0f,1.0f,1.5f}});
    assert(r.ready);
    assert(r.neutralD50Error < 0.025f);
    for (float x : r.postWbToLinearSrgb) assert(std::isfinite(x));

    p.hasForwardMatrix1 = false;
    r = resolveRawCameraDngForwardTransform({&p, 1.0f, 0.0f, {1,1,1}});
    assert(!r.ready);
    assert(r.status == "PAIRED_FORWARD_MATRIX_UNAVAILABLE");
    return 0;
}
