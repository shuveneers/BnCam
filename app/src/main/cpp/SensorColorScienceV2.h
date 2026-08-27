#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::color {

struct MatrixAudit {
    std::array<float, 3> neutralAxis{};
    float neutralAxisMean = 0.0f;
    float neutralAxisSpread = 0.0f;
    float determinant = 0.0f;
    float maxAbsCoefficient = 0.0f;
    bool finite = false;
};

inline MatrixAudit auditLinearSrgbMatrix(const float* matrix9) {
    MatrixAudit out{};
    if (matrix9 == nullptr) return out;

    bool finite = true;
    float maxAbs = 0.0f;
    for (int i = 0; i < 9; ++i) {
        finite = finite && std::isfinite(matrix9[i]);
        maxAbs = std::max(maxAbs, std::abs(matrix9[i]));
    }
    if (!finite) return out;

    for (int row = 0; row < 3; ++row) {
        out.neutralAxis[row] =
                matrix9[row * 3] + matrix9[row * 3 + 1] + matrix9[row * 3 + 2];
    }
    out.neutralAxisMean =
            (out.neutralAxis[0] + out.neutralAxis[1] + out.neutralAxis[2]) / 3.0f;
    const float neutralMin = std::min({out.neutralAxis[0], out.neutralAxis[1], out.neutralAxis[2]});
    const float neutralMax = std::max({out.neutralAxis[0], out.neutralAxis[1], out.neutralAxis[2]});
    out.neutralAxisSpread = (neutralMax - neutralMin) /
            std::max(1.0e-6f, std::abs(out.neutralAxisMean));

    out.determinant =
            matrix9[0] * (matrix9[4] * matrix9[8] - matrix9[5] * matrix9[7]) -
            matrix9[1] * (matrix9[3] * matrix9[8] - matrix9[5] * matrix9[6]) +
            matrix9[2] * (matrix9[3] * matrix9[7] - matrix9[4] * matrix9[6]);
    out.maxAbsCoefficient = maxAbs;
    out.finite = std::isfinite(out.determinant) &&
            std::isfinite(out.neutralAxisSpread) &&
            std::isfinite(out.neutralAxisMean);
    return out;
}

struct PrePresentationSceneAudit {
    double meanR = 0.0;
    double meanG = 0.0;
    double meanB = 0.0;
    double normalizedRgbSpread = 0.0;
    bool finite = false;
};

inline PrePresentationSceneAudit auditPrePresentationSceneMean(
        double r,
        double g,
        double b
) {
    PrePresentationSceneAudit out{};
    out.meanR = r;
    out.meanG = g;
    out.meanB = b;
    if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) return out;

    const double mean = (r + g + b) / 3.0;
    const double minimum = std::min({r, g, b});
    const double maximum = std::max({r, g, b});
    out.normalizedRgbSpread = (maximum - minimum) /
            std::max(1.0e-12, std::abs(mean));
    out.finite = std::isfinite(out.normalizedRgbSpread);
    return out;
}

} // namespace bncam::color
