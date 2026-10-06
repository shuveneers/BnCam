#pragma once
#include <algorithm>
#include <cmath>

namespace bncam::tone {
struct DefaultRawSceneEvidence {
    float indoor = 0.0f;
    float daylight = 0.0f;
    float displayGate = 0.0f;
};
// Geometric rectangles and a dark RAW median alone do not prove an indoor display.
// Missing exposure metadata leaves the classification ambiguous (gate zero).
// Broad sky touching the image edge also cannot be an isolated display island.
inline DefaultRawSceneEvidence defaultRawSceneEvidence(
        int iso, float exposureMs, bool exposureKnown, bool brightRegionTouchesEdge) {
    const auto smooth = [](float a, float b, float v) {
        const float t = std::clamp((v-a)/(b-a),0.0f,1.0f);
        return t*t*(3.0f-2.0f*t);
    };
    DefaultRawSceneEvidence out;
    if (!exposureKnown || iso <= 0 || !std::isfinite(exposureMs) || exposureMs <= 0.0f) return out;
    out.indoor = std::max(smooth(300.0f,1000.0f,float(iso)), smooth(12.0f,30.0f,exposureMs));
    out.daylight = (1.0f-smooth(200.0f,640.0f,float(iso))) *
                   (1.0f-smooth(4.0f,12.0f,exposureMs));
    out.displayGate = brightRegionTouchesEdge ? 0.0f : out.indoor*(1.0f-out.daylight);
    return out;
}
} // namespace bncam::tone
