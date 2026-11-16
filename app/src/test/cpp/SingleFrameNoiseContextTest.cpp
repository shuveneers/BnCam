#include "../../main/cpp/SingleFrameNoiseContext.h"
#include <cassert>
#include <cmath>
#include <limits>
#include <string>
#include <type_traits>
#include <utility>

using namespace bncam;

static bool near(double a, double b, double eps = 1e-12) {
    return std::abs(a - b) <= eps * std::max({1.0, std::abs(a), std::abs(b)});
}
static_assert(!std::is_assignable_v<decltype(std::declval<sf2::SingleFrameNoiseContext&>().sensorS), std::array<double, 4>>);
static_assert(!std::is_assignable_v<sf2::SingleFrameNoiseContext&, sf2::SingleFrameNoiseContext>);

int main() {
    const std::array<double, 4> s{0.001, 0.002, 0.003, 0.004};
    const std::array<double, 4> o{0.0001, 0.0002, 0.0003, 0.0004};

    // Already-finalized residual covariance includes a prior gain: do NOT apply it again.
    const auto finalized = spectra2::makeState(
        "PRE_DEMOSAIC_AFTER_SPATIAL_EXPOSURE", "EXISTING_FINALIZER", "PROPAGATED",
        0.9, spectra2::diagonalCovariance(0.004, 0.006, 0.008));
    const auto demosaiced = spectra2::propagateDemosaic(
        finalized, spectra2::DemosaicModel::Malvar2004);
    spectra2::ResidualObservation measured{};
    measured.stage = "POST_DEMOSAIC_RGB";
    measured.status = "OBSERVATION_READY_NO_AUTO_CALIBRATION";
    measured.robustVarianceY = demosaiced.varianceY * 1.2;
    measured.acceptedSampleCount = 4096;
    measured.validTileCount = 12;

    const auto ctx = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, true, true, false);
    assert(ctx.physicalAvailable && ctx.predictionAvailable);
    assert(ctx.relativeSpatialSigmaAvailable && ctx.spatialShapeIncludesLscVariance);
    assert(!ctx.fusionVarianceAlreadyIncluded);
    assert(ctx.model == spectra2::DemosaicModel::Malvar2004 && !ctx.autoHybrid);
    assert(near(ctx.rawVariance(0, .5), .0006));
    assert(near(ctx.rawVariance(1, .5), .0012));
    assert(near(ctx.rawVariance(2, .5), .0018));
    assert(near(ctx.rawVariance(3, .5), .0024));
    assert(near(ctx.rawVariance(1, 2.0), .0022)); // clamp normalized signal
    assert(ctx.rawVariance(4, .5) == 0);
    assert(ctx.rawVariance(0, std::numeric_limits<double>::quiet_NaN()) == 0);
    assert(near(ctx.postFinalize.varianceY, finalized.varianceY));
    assert(near(ctx.postDemosaic.varianceY, demosaiced.varianceY));
    assert(near(ctx.predictionConfidence, demosaiced.confidence));
    assert(ctx.observedSamples == 4096);
    assert(near(ctx.observedToPredictedY(), 1.2));
    assert(ctx.postFinalizeProvenance.find("EXISTING_FINALIZER") != std::string::npos);
    assert(ctx.postDemosaicProvenance.find("POST_DEMOSAIC_RGB") != std::string::npos);
    const auto formatted = sf2::formatNoiseContextOnly(ctx);
    assert(formatted.find("appliedToPixels=false") != std::string::npos);
    assert(formatted.find("extraRawGainApplications=0;extraFusionGainApplications=0") != std::string::npos);

    // Observation has no authority to alter post-demosaic prediction or raw S/O.
    measured.robustVarianceY = 100.0;
    const auto biggerObservation = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, false, false, true);
    assert(near(biggerObservation.rawVariance(0, .5), ctx.rawVariance(0, .5)));
    assert(near(biggerObservation.postDemosaic.varianceY, ctx.postDemosaic.varianceY));
    assert(biggerObservation.fusionVarianceAlreadyIncluded);
    assert(!biggerObservation.relativeSpatialSigmaAvailable);
    assert(!biggerObservation.spatialShapeIncludesLscVariance);

    // Wrong-stage and under-supported measurements must not masquerade as evidence.
    measured.stage = "POST_COLOUR_MATRIX_RGB";
    const auto stageMismatch = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::AmazeInspired, true, true, false, false, false);
    assert(stageMismatch.observedSamples == 0u && stageMismatch.observedPostDemosaicY == 0);
    assert(stageMismatch.autoHybrid);

    measured.stage = "POST_DEMOSAIC_RGB";
    measured.validTileCount = 1;
    const auto insufficient = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, false, false, false);
    assert(insufficient.observedSamples == 0u);

    // Invalid physical S/O must not gain authority from a healthy prediction.
    auto invalidS = s;
    invalidS[0] = -1.0;
    const auto invalid = sf2::SingleFrameNoiseContext::observe(
        invalidS, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, false, false, false);
    assert(!invalid.physicalAvailable && !invalid.predictionAvailable);
    assert(invalid.rawVariance(1, .5) == 0);
    assert(invalid.observedToPredictedY() == 0);

    const auto absent = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, demosaiced, measured,
        spectra2::DemosaicModel::Malvar2004, false, false, false, false, false);
    assert(!absent.physicalAvailable && absent.rawVariance(0, .5) == 0);

    // Invalid post-demosaic prediction must fail closed without repairing/mutating.
    auto badPrediction = demosaiced;
    badPrediction.covariance.at(0, 0) = std::numeric_limits<double>::infinity();
    const auto noPrediction = sf2::SingleFrameNoiseContext::observe(
        s, o, finalized, badPrediction, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, false, false, false);
    assert(noPrediction.physicalAvailable && !noPrediction.predictionAvailable);
    assert(noPrediction.predictionConfidence == 0);

    // An explicit scalar gain is applied exactly once by the existing propagation,
    // and observing that state does not apply it again.
    const std::array<double, 9> exposureGain{
        2.0,0,0, 0,2.0,0, 0,0,2.0};
    const auto once = spectra2::propagateLinear(
        finalized, exposureGain, "PRE_DEMOSAIC_AFTER_SPATIAL_EXPOSURE", "EXISTING_EXPOSURE_GAIN");
    const auto onceDem = spectra2::propagateDemosaic(once, spectra2::DemosaicModel::Malvar2004);
    const auto gainContext = sf2::SingleFrameNoiseContext::observe(
        s, o, once, onceDem, measured,
        spectra2::DemosaicModel::Malvar2004, false, true, true, true, false);
    assert(near(once.varianceY, 4 * finalized.varianceY));
    assert(near(gainContext.postFinalize.varianceY, once.varianceY));
    assert(near(gainContext.postDemosaic.varianceY, onceDem.varianceY));
    assert(near(gainContext.rawVariance(0, .5), ctx.rawVariance(0, .5)));
    return 0;
}
