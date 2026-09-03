#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>

using V3 = std::array<float, 3>;

static float luma(const V3& c) {
    return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
}

static V3 strictLumaRatio(const V3& rgb, float targetY) {
    const float oldY = std::max(1.0e-7f, luma(rgb));
    const float ratio = std::max(0.0f, targetY) / oldY;
    return {rgb[0] * ratio, rgb[1] * ratio, rgb[2] * ratio};
}

static V3 pbrNeutral(V3 color) {
    for (float& v : color) v = std::max(0.0f, v);
    constexpr float startCompression = 0.8f - 0.04f;
    constexpr float desaturation = 0.15f;
    const float x = std::min({color[0], color[1], color[2]});
    const float offset = x < 0.08f ? x - 6.25f * x * x : 0.04f;
    for (float& v : color) v -= offset;
    const float peak = std::max({color[0], color[1], color[2]});
    if (peak < startCompression) return color;
    constexpr float d = 1.0f - startCompression;
    const float newPeak = 1.0f - d * d / (peak + d - startCompression);
    const float peakScale = newPeak / std::max(peak, 1.0e-8f);
    for (float& v : color) v *= peakScale;
    const float g = 1.0f - 1.0f / (desaturation * (peak - newPeak) + 1.0f);
    for (float& v : color) v = v * (1.0f - g) + newPeak * g;
    return color;
}

static bool near(float a, float b, float eps = 2.0e-5f) {
    return std::abs(a - b) <= eps;
}

int main() {
    const V3 scene{0.22f, 0.11f, 0.055f};
    const float target = luma(scene) * std::exp2(0.55f);
    const V3 lifted = strictLumaRatio(scene, target);
    // Strict alpha=1 luminance-ratio reconstruction is exactly one scalar RGB multiplication.
    assert(near(lifted[0] / lifted[1], scene[0] / scene[1]));
    assert(near(lifted[1] / lifted[2], scene[1] / scene[2]));
    assert(near(luma(lifted), target));

    // Khronos PBR Neutral keeps neutral input neutral.
    const V3 neutral = pbrNeutral({1.4f, 1.4f, 1.4f});
    assert(near(neutral[0], neutral[1]));
    assert(near(neutral[1], neutral[2]));
    assert(neutral[0] >= 0.0f && neutral[0] <= 1.0f);

    // Neutral luminance response must stay ordered through the highlight mapper.
    float previous = -1.0f;
    for (int i = 0; i <= 200; ++i) {
        const float x = 2.0f * static_cast<float>(i) / 200.0f;
        const V3 y = pbrNeutral({x, x, x});
        assert(y[0] + 1.0e-5f >= previous);
        assert(y[0] >= -1.0e-6f && y[0] <= 1.00001f);
        previous = y[0];
    }

    // In-gamut ordinary scene colour remains finite and ordered, while an over-range highlight
    // is mapped below display white without a per-channel hard clip.
    const V3 color = pbrNeutral({1.8f, 0.9f, 0.45f});
    for (float v : color) assert(std::isfinite(v));
    assert(color[0] > color[1] && color[1] > color[2]);
    assert(color[0] <= 1.0f);

    return 0;
}
