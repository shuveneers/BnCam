#include "SpectraChromaBands.h"
#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::spectra2;

    const float input = 8.53675e-6f;
    const float floor = 7.87940e-6f;

    const auto neutral = resolveChromaBandPlan(
            ChromaBandKind::Fine, input, floor,
            1.0f, 0.9989f, 0.80f, 0.45f, 0.0f);
    assert(std::abs(neutral.targetFloor - floor) < 1.0e-10f);
    assert(neutral.requiredReductionFraction < 0.10f);

    const auto deviceLike = resolveChromaBandPlan(
            ChromaBandKind::Fine, input, floor,
            2.8535457f, 0.9989f, 0.80f, 0.45f, 0.0f);
    assert(deviceLike.targetFloor < floor * 0.40f);
    assert(deviceLike.requiredReductionFraction > 0.60f);
    assert(deviceLike.enabled);

    const auto mid = resolveChromaBandPlan(
            ChromaBandKind::Mid, input, floor,
            2.8535457f, 0.9989f, 0.80f, 0.45f, 0.0f);
    assert(mid.targetFloor < floor * 0.20f);
    assert(mid.requiredReductionFraction > 0.80f);

    const auto lowNeutral = resolveChromaBandPlan(
            ChromaBandKind::Low, input, floor,
            1.0f, 0.9989f, 0.80f, 0.50f, 0.50f);
    const auto lowAmplified = resolveChromaBandPlan(
            ChromaBandKind::Low, input, floor,
            3.0f, 0.9989f, 0.80f, 0.50f, 0.50f);
    assert(std::abs(lowNeutral.targetFloor - lowAmplified.targetFloor) < 1.0e-12f);

    return 0;
}
