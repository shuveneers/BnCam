#include "../../main/cpp/SpectraNoiseCalibration.h"

#include <cassert>
#include <cmath>
#include <iostream>

namespace {

bool nearlyEqual(double left, double right, double tolerance = 1.0e-9) {
    return std::abs(left - right) <= tolerance * std::max({1.0, std::abs(left), std::abs(right)});
}

bncam::spectra2::ResidualObservation readyObservation() {
    bncam::spectra2::ResidualObservation observation{};
    observation.stage = "POST_DEMOSAIC_RGB";
    observation.method = "FIXTURE";
    observation.status = "OBSERVATION_READY_NO_AUTO_CALIBRATION";
    observation.confidence = 0.8;
    observation.robustVarianceY = 0.004;
    observation.robustVarianceRG = 0.006;
    observation.robustVarianceBG = 0.010;
    observation.acceptedSampleCount = 12000;
    observation.validTileCount = 48;
    return observation;
}

void ratiosAreObservationOnlyAndUseRobustTileMedian() {
    const auto predicted = bncam::spectra2::makeState(
            "POST_DEMOSAIC_RGB",
            "FIXTURE",
            "PROPAGATED",
            0.7,
            bncam::spectra2::diagonalCovariance(0.002, 0.002, 0.004)
    );
    auto observation = readyObservation();
    const auto comparison = bncam::spectra2::comparePredictionToObservation(
            predicted,
            observation
    );

    assert(comparison.ready);
    assert(comparison.status == "READY_OBSERVATION_ONLY_NO_COEFFICIENT_UPDATE");
    assert(nearlyEqual(comparison.confidence, 0.7));
    assert(comparison.measuredToPredictedY > 0.0);
    assert(comparison.measuredToPredictedRG > 0.0);
    assert(comparison.measuredToPredictedBG > 0.0);
    assert(comparison.measuredToPredictedChroma > 0.0);
}

void insufficientSupportCannotBecomeCalibrationReady() {
    const auto predicted = bncam::spectra2::makeState(
            "POST_DEMOSAIC_RGB",
            "FIXTURE",
            "PROPAGATED",
            0.9,
            bncam::spectra2::diagonalCovariance(0.001, 0.001, 0.001)
    );
    auto observation = readyObservation();
    observation.acceptedSampleCount = 100;
    observation.validTileCount = 2;
    const auto comparison = bncam::spectra2::comparePredictionToObservation(
            predicted,
            observation
    );
    assert(!comparison.ready);
    assert(comparison.status == "INSUFFICIENT_FLAT_SUPPORT");
    assert(nearlyEqual(comparison.confidence, 0.0));
}

void unavailablePredictionCannotProduceAReadyScale() {
    bncam::spectra2::NoiseState predicted{};
    predicted.stage = "POST_DEMOSAIC_RGB";
    predicted.status = "UNAVAILABLE";
    predicted.confidence = 1.0;
    const auto comparison = bncam::spectra2::comparePredictionToObservation(
            predicted,
            readyObservation()
    );
    assert(!comparison.ready);
    assert(comparison.status == "PREDICTION_UNAVAILABLE");
}


void explicitStageBoundaryRemainsObservationOnly() {
    const auto predicted = bncam::spectra2::makeState(
            "POST_COLOUR_MATRIX_RGB",
            "FIXTURE",
            "PROPAGATED",
            0.8,
            bncam::spectra2::diagonalCovariance(0.001, 0.001, 0.001)
    );
    auto observation = readyObservation();
    observation.stage = "POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP";
    const auto comparison = bncam::spectra2::comparePredictionToObservation(
            predicted,
            observation
    );
    assert(comparison.ready);
    assert(comparison.status ==
            "READY_OBSERVATION_ONLY_EXPLICIT_STAGE_BOUNDARY_NO_COEFFICIENT_UPDATE");
    assert(comparison.confidence < std::min(predicted.confidence, observation.confidence));
}


void unrelatedStageMismatchIsRejected() {
    const auto predicted = bncam::spectra2::makeState(
            "POST_DEMOSAIC_RGB",
            "FIXTURE",
            "PROPAGATED",
            0.8,
            bncam::spectra2::diagonalCovariance(0.001, 0.001, 0.001)
    );
    auto observation = readyObservation();
    observation.stage = "POST_TONE_CURVES_PRE_VIBRANCE";
    const auto comparison = bncam::spectra2::comparePredictionToObservation(
            predicted,
            observation
    );
    assert(!comparison.ready);
    assert(comparison.status == "STAGE_MISMATCH");
    assert(nearlyEqual(comparison.confidence, 0.0));
}

void nonFiniteRatiosAreBoundedToUnavailable() {
    assert(nearlyEqual(bncam::spectra2::safeVarianceRatio(1.0, 0.0), 0.0));
    assert(nearlyEqual(
            bncam::spectra2::safeVarianceRatio(
                    std::numeric_limits<double>::quiet_NaN(),
                    1.0
            ),
            0.0
    ));
    assert(nearlyEqual(bncam::spectra2::absoluteLog2RatioError(0.0), 0.0));
}

} // namespace

int main() {
    ratiosAreObservationOnlyAndUseRobustTileMedian();
    insufficientSupportCannotBecomeCalibrationReady();
    unavailablePredictionCannotProduceAReadyScale();
    explicitStageBoundaryRemainsObservationOnly();
    unrelatedStageMismatchIsRejected();
    nonFiniteRatiosAreBoundedToUnavailable();
    std::cout << "SPECTRA_NOISE_CALIBRATION_TESTS_OK\n";
    return 0;
}
