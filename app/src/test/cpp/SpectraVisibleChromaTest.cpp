#include "../../main/cpp/SpectraVisibleChroma.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

namespace {

void expectNear(float actual, float expected, float tolerance = 1.0e-5f) {
    assert(std::isfinite(actual));
    assert(std::abs(actual - expected) <= tolerance);
}

} // namespace

int main() {
    const auto diagonal = makeOpponentCovariance(0.04f, 0.09f, 0.0f);
    assert(diagonal.valid);
    expectNear(diagonal.inverse00, 25.0f);
    expectNear(diagonal.inverse11, 1.0f / 0.09f);
    expectNear(opponentMahalanobisSquared(diagonal, 0.2f, 0.0f), 1.0f);

    const auto correlated = makeOpponentCovariance(0.04f, 0.09f, 0.03f);
    assert(correlated.valid);
    assert(correlated.correlation > 0.0f);
    assert(std::abs(correlated.correlation) < 1.0f);

    const auto singular = makeOpponentCovariance(0.04f, 0.09f, 10.0f);
    assert(singular.valid);
    assert(std::abs(singular.correlation) < 1.0f);

    const auto offPlan = buildVisibleChromaPlan(
            false, 0.001f, 0.001f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f
    );
    assert(!offPlan.enabled);
    assert(offPlan.status == "SPECTRA_OFF");

    const auto plan = buildVisibleChromaPlan(
            true, 0.0016f, 0.0025f, 0.0004f, 0.9f, 0.8f, 0.75f, 1.4f
    );
    assert(plan.enabled);
    assert(plan.authority > 0.0f && plan.authority <= 0.96f);
    assert(plan.maximumCorrection > 0.0f && plan.maximumCorrection <= 0.18f);

    const auto model = makeOpponentCovariance(
            plan.predictedVarianceRG,
            plan.predictedVarianceBG,
            plan.predictedCovarianceRgBg
    );
    const auto accepted = resolveVisibleChromaDecision(
            plan,
            model,
            0.08f,
            -0.06f,
            0.01f,
            -0.01f,
            1.0f,
            0.2f,
            0.2f,
            0.4f,
            0.5f,
            0.04f,
            0.05f,
            0.8f
    );
    assert(accepted.acceptance >= 0.0f && accepted.acceptance <= 1.0f);
    assert(accepted.correctionMagnitude <= plan.maximumCorrection + 1.0e-6f);
    assert(accepted.mahalanobisAfter <= accepted.mahalanobisBefore + 1.0e-5f);

    const auto lumaEdgeRejected = resolveVisibleChromaDecision(
            plan,
            model,
            0.08f,
            -0.06f,
            0.01f,
            -0.01f,
            1.0f,
            5.0f,
            0.2f,
            8.0f,
            0.5f,
            0.30f,
            0.05f,
            0.8f
    );
    assert(lumaEdgeRejected.acceptance <= accepted.acceptance);

    const auto colourEdgeRejected = resolveVisibleChromaDecision(
            plan,
            model,
            0.08f,
            -0.06f,
            0.01f,
            -0.01f,
            1.0f,
            0.2f,
            5.0f,
            0.5f,
            8.0f,
            0.30f,
            0.05f,
            0.8f
    );
    assert(colourEdgeRejected.acceptance <= accepted.acceptance);

    const auto saturatedRejected = resolveVisibleChromaDecision(
            plan,
            model,
            0.08f,
            -0.06f,
            0.01f,
            -0.01f,
            1.0f,
            0.2f,
            0.2f,
            0.4f,
            0.5f,
            0.60f,
            0.95f,
            0.8f
    );
    assert(saturatedRejected.acceptance <= accepted.acceptance);

    const auto noSupport = resolveVisibleChromaDecision(
            plan,
            model,
            0.08f,
            -0.06f,
            0.01f,
            -0.01f,
            1.0f,
            0.2f,
            0.2f,
            0.4f,
            0.5f,
            0.04f,
            0.05f,
            0.0f
    );
    assert(noSupport.acceptance <= accepted.acceptance);

    // Deep-shadow saturation is unreliable: Context Fusion must still clean it.
    // The same saturation on a bright structured colour edge is protected.
    const auto darkFalseColour = resolveVisibleChromaDecision(
            plan, model, 0.08f, -0.06f, 0.01f, -0.01f, 1.0f,
            1.15f, 1.75f, 4.0f, 5.0f, 0.04f, 0.95f, 0.8f
    );
    const auto brightSaturatedStructure = resolveVisibleChromaDecision(
            plan, model, 0.08f, -0.06f, 0.01f, -0.01f, 1.0f,
            1.15f, 3.1f, 4.0f, 8.0f, 0.65f, 0.95f, 0.8f
    );
    assert(darkFalseColour.acceptance > brightSaturatedStructure.acceptance);

    const auto lowStrengthPlan = buildVisibleChromaPlan(
            true, 0.0016f, 0.0025f, 0.0004f, 0.9f, 0.8f, 0.25f, 1.4f
    );
    assert(lowStrengthPlan.authority < plan.authority);

    // Device A/B regression: SPECTRA predicted luma variance collapsed much more than
    // opponent chroma variance, causing ordinary chroma texture to be classified as hard
    // luma structure. The guard sigma may rise to a bounded fraction of opponent sigma,
    // but never below the actual predicted luma sigma.
    const float guarded = resolveVisibleChromaLumaGuardSigma(
            0.000018429f, 0.01081f, 0.00821f);
    assert(guarded > std::sqrt(0.000018429f));
    assert(guarded >= 0.65f * 0.01081f - 1.0e-6f);
    const float alreadyLumaDominant = resolveVisibleChromaLumaGuardSigma(
            0.0004f, 0.005f, 0.006f);
    expectNear(alreadyLumaDominant, 0.02f, 1.0e-6f);

    std::cout << "SPECTRA_VISIBLE_CHROMA_TESTS_OK\n";
    return 0;
}
