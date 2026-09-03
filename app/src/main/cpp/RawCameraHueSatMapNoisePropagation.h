#pragma once

#include "RawCameraCalibratedHueSatRuntime.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace bncam::color {

/**
 * Compact Phase-6 propagation model for the nonlinear DNG ProfileHueSatMap stage.
 *
 * This is deliberately not a per-pixel CPU path. It evaluates the already resolved/blended
 * calibrated profile at a fixed, compact RIMM-HSV quadrature grid and numerically differentiates
 * the exact CPU reference transform. The resulting row-norm RMS gains describe how local
 * perturbations in the (Y, R-G, B-G) basis are amplified by the profile, including hue-induced
 * cross-coupling between the opponent axes. Production pixels remain Vulkan-resident.
 */
struct RawCameraHueSatMapNoisePropagationPlan {
    bool ready = false;
    double lumaRmsGain = 1.0;
    double redGreenRmsGain = 1.0;
    double blueGreenRmsGain = 1.0;
    double maximumOpponentRowGain = 1.0;
    std::size_t sampleCount = 0u;
    const char* method = "HUESATMAP_NOT_ACTIVE";
};

namespace detail {

inline std::array<double, 3> hsmOpponentCoordinates(const RawCameraVec3& rgb) noexcept {
    const double r = static_cast<double>(rgb[0]);
    const double g = static_cast<double>(rgb[1]);
    const double b = static_cast<double>(rgb[2]);
    return {
        0.2126 * r + 0.7152 * g + 0.0722 * b,
        r - g,
        b - g
    };
}

inline RawCameraVec3 hsmPerturb(
        const RawCameraVec3& input,
        const std::array<double, 3>& direction,
        double step) noexcept {
    return {
        static_cast<float>(static_cast<double>(input[0]) + step * direction[0]),
        static_cast<float>(static_cast<double>(input[1]) + step * direction[1]),
        static_cast<float>(static_cast<double>(input[2]) + step * direction[2])
    };
}

} // namespace detail

inline RawCameraHueSatMapNoisePropagationPlan resolveRawCameraHueSatMapNoisePropagation(
        const RawCameraHueSatMap& map) noexcept {
    RawCameraHueSatMapNoisePropagationPlan out{};
    if (!map.productEligible() || !map.directReferenceApplicationSupported()) {
        out.method = "HUESATMAP_REFERENCE_UNAVAILABLE";
        return out;
    }

    // q = (Y, R-G, B-G), using Rec.709 luma. These are the exact RGB directions for unit
    // perturbations of q while holding the other two opponent coordinates fixed.
    constexpr std::array<std::array<double, 3>, 3> kOpponentToRgbDirections{{
        {{1.0, 1.0, 1.0}},
        {{0.7874, -0.2126, -0.2126}},
        {{-0.0722, -0.0722, 0.9278}}
    }};
    constexpr std::array<float, 12> kHues{
        0.0f, 30.0f, 60.0f, 90.0f, 120.0f, 150.0f,
        180.0f, 210.0f, 240.0f, 270.0f, 300.0f, 330.0f};
    constexpr std::array<float, 3> kSaturations{0.15f, 0.45f, 0.75f};
    constexpr std::array<float, 3> kValues{0.12f, 0.35f, 0.70f};

    double rowEnergy[3]{0.0, 0.0, 0.0};
    double maximumRowGain = 0.0;
    std::size_t accepted = 0u;

    for (float value : kValues) {
        for (float saturation : kSaturations) {
            for (float hue : kHues) {
                const RawCameraHueSatValue hsv{hue, saturation, value};
                const RawCameraVec3 rimm = rawCameraHsvToLinearRgb(hsv);
                const RawCameraVec3 input = rawCameraLinearRimmToLinearSrgb(rimm);
                const double step = std::max(1.0e-5, static_cast<double>(value) * 1.0e-3);

                double jacobian[3][3]{};
                bool validSample = true;
                for (int axis = 0; axis < 3; ++axis) {
                    const RawCameraVec3 plusInput = detail::hsmPerturb(
                            input, kOpponentToRgbDirections[static_cast<std::size_t>(axis)], step);
                    const RawCameraVec3 minusInput = detail::hsmPerturb(
                            input, kOpponentToRgbDirections[static_cast<std::size_t>(axis)], -step);
                    bool plusApplied = false;
                    bool minusApplied = false;
                    const RawCameraVec3 plus = rawCameraApplyCalibratedHueSatMapLinearSrgb(
                            map, plusInput, &plusApplied);
                    const RawCameraVec3 minus = rawCameraApplyCalibratedHueSatMapLinearSrgb(
                            map, minusInput, &minusApplied);
                    if (!plusApplied || !minusApplied) {
                        validSample = false;
                        break;
                    }
                    const auto plusQ = detail::hsmOpponentCoordinates(plus);
                    const auto minusQ = detail::hsmOpponentCoordinates(minus);
                    for (int row = 0; row < 3; ++row) {
                        const double derivative =
                                (plusQ[static_cast<std::size_t>(row)] -
                                 minusQ[static_cast<std::size_t>(row)]) / (2.0 * step);
                        if (!std::isfinite(derivative)) {
                            validSample = false;
                            break;
                        }
                        jacobian[row][axis] = derivative;
                    }
                    if (!validSample) break;
                }
                if (!validSample) continue;

                for (int row = 0; row < 3; ++row) {
                    double energy = 0.0;
                    for (int column = 0; column < 3; ++column) {
                        energy += jacobian[row][column] * jacobian[row][column];
                    }
                    rowEnergy[row] += energy;
                    maximumRowGain = std::max(maximumRowGain, std::sqrt(std::max(0.0, energy)));
                }
                ++accepted;
            }
        }
    }

    if (accepted == 0u) {
        out.method = "HUESATMAP_NUMERIC_JACOBIAN_NO_VALID_SAMPLES";
        return out;
    }

    out.lumaRmsGain = std::sqrt(std::max(0.0, rowEnergy[0] / static_cast<double>(accepted)));
    out.redGreenRmsGain = std::sqrt(std::max(0.0, rowEnergy[1] / static_cast<double>(accepted)));
    out.blueGreenRmsGain = std::sqrt(std::max(0.0, rowEnergy[2] / static_cast<double>(accepted)));
    out.maximumOpponentRowGain = maximumRowGain;
    out.sampleCount = accepted;
    out.ready = std::isfinite(out.lumaRmsGain) && out.lumaRmsGain > 0.0 &&
            std::isfinite(out.redGreenRmsGain) && out.redGreenRmsGain > 0.0 &&
            std::isfinite(out.blueGreenRmsGain) && out.blueGreenRmsGain > 0.0;
    out.method = out.ready
            ? "PROFILE_DOMAIN_NUMERIC_OPPONENT_JACOBIAN_ROW_NORM_RMS"
            : "HUESATMAP_NUMERIC_JACOBIAN_INVALID";
    if (!out.ready) {
        out.lumaRmsGain = 1.0;
        out.redGreenRmsGain = 1.0;
        out.blueGreenRmsGain = 1.0;
        out.maximumOpponentRowGain = 1.0;
        out.sampleCount = 0u;
    }
    return out;
}

} // namespace bncam::color
