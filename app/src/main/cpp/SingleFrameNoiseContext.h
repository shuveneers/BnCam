#pragma once
// SF2-D01-01: immutable, per-capture view of existing physical noise evidence.
// This is NOT a second sensor noise resolver and may not change pixel processing.
#include "RawSpatialNoiseCalibrationPolicy.h"
#include "SpectraNoiseCalibration.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <iomanip>
#include <sstream>
#include <string>

namespace bncam::sf2 {

struct SingleFrameNoiseContext final {
    // Capture-local effective S/O in normalized sensor Bayer domain, in R, Gr, Gb, B order.
    const std::array<double, 4> sensorS;
    const std::array<double, 4> sensorO;
    // These are EXISTING predicted residual states; neither is raw sensor variance.
    // postFinalize has already traversed RAW finalization, including known gains.
    const spectra2::NoiseState postFinalize;
    const spectra2::NoiseState postDemosaic;
    const spectra2::DemosaicModel model;
    const bool autoHybrid;
    const bool physicalAvailable;
    const bool predictionAvailable;
    // Existing spatial map is a relative multiplier, not another absolute sigma.
    const bool relativeSpatialSigmaAvailable;
    const bool spatialShapeIncludesLscVariance;
    // Student posterior, if used, already includes fusion; normal S/O does not.
    const bool fusionVarianceAlreadyIncluded;
    // Informational measurement only; it never adjusts S/O or filter coefficients.
    const double observedPostDemosaicY;
    const std::size_t observedSamples;
    const double predictionConfidence;
    const std::string sensorProvenance;
    const std::string postFinalizeProvenance;
    const std::string postDemosaicProvenance;

    static bool validSo(const std::array<double, 4>& s,
                        const std::array<double, 4>& o) noexcept {
        for (std::size_t ch = 0; ch < 4; ++ch) {
            if (!std::isfinite(s[ch]) || !std::isfinite(o[ch]) ||
                s[ch] < 0.0 || o[ch] < 0.0 || !(s[ch] + o[ch] > 0.0)) {
                return false;
            }
        }
        return true;
    }

    static bool validPrediction(const spectra2::NoiseState& value) noexcept {
        if (!(value.confidence > 0.0 && value.confidence <= 1.0) ||
            !std::isfinite(value.varianceY) || !(value.varianceY > 0.0)) {
            return false;
        }
        for (int row = 0; row < 3; ++row) {
            for (int column = 0; column < 3; ++column) {
                if (!std::isfinite(value.covariance.at(row, column))) return false;
            }
            if (!(value.covariance.at(row, row) > 0.0)) return false;
        }
        return true;
    }

    static SingleFrameNoiseContext observe(
            const std::array<double, 4>& s,
            const std::array<double, 4>& o,
            const spectra2::NoiseState& finalized,
            const spectra2::NoiseState& demosaiced,
            const spectra2::ResidualObservation& measurement,
            spectra2::DemosaicModel demosaicModel,
            bool hybrid,
            bool physical,
            bool relativeSpatialMap,
            bool spatialIncludesLscVariance,
            bool fusionAlreadyIncluded) {
        const bool soReady = physical && validSo(s, o);
        const bool predictionReady = soReady &&
                validPrediction(finalized) && validPrediction(demosaiced);
        const bool observationReady =
                measurement.status == "OBSERVATION_READY_NO_AUTO_CALIBRATION" &&
                measurement.stage == demosaiced.stage &&
                measurement.acceptedSampleCount >= 2048u &&
                measurement.validTileCount >= 6u &&
                std::isfinite(measurement.robustVarianceY) &&
                measurement.robustVarianceY > 0.0;
        return {
            s, o, finalized, demosaiced, demosaicModel, hybrid,
            soReady, predictionReady,
            relativeSpatialMap, relativeSpatialMap && spatialIncludesLscVariance,
            fusionAlreadyIncluded,
            observationReady ? measurement.robustVarianceY : 0.0,
            observationReady ? measurement.acceptedSampleCount : 0u,
            predictionReady ? demosaiced.confidence : 0.0,
            soReady ? "EXISTING_EFFECTIVE_SO" : "NO_VALID_PHYSICAL_SO",
            finalized.stage + ":" + finalized.method,
            demosaiced.stage + ":" + demosaiced.method
        };
    }

    // Sensor-domain ONLY; never reuse this result as post-finalize or RGB variance.
    double rawVariance(std::size_t channel, double normalizedSignal) const noexcept {
        if (!physicalAvailable || channel >= 4u) return 0.0;
        return raw_noise::sensorVariance(
                normalizedSignal, sensorS[channel], sensorO[channel]);
    }

    // Diagnostic only; nonzero means a same-domain observation was accepted.
    double observedToPredictedY() const noexcept {
        return predictionAvailable && observedPostDemosaicY > 0.0
                ? observedPostDemosaicY / postDemosaic.varianceY : 0.0;
    }
};

inline std::string formatNoiseContextOnly(const SingleFrameNoiseContext& ctx) {
    std::ostringstream out;
    out << std::setprecision(9)
        << "; sf2NoiseContext={appliedToPixels=false"
        << ";physicalAvailable=" << (ctx.physicalAvailable ? "true" : "false")
        << ";predictionAvailable=" << (ctx.predictionAvailable ? "true" : "false")
        << ";sensorDomain=NORMALIZED_BAYER"
        << ";finalizedDomain=" << ctx.postFinalize.stage
        << ";demosaicDomain=" << ctx.postDemosaic.stage
        << ";sensorProvenance=" << ctx.sensorProvenance
        << ";postFinalizeProvenance=" << ctx.postFinalizeProvenance
        << ";postDemosaicProvenance=" << ctx.postDemosaicProvenance
        << ";model=" << static_cast<int>(ctx.model)
        << ";autoHybrid=" << (ctx.autoHybrid ? "true" : "false")
        << ";relativeSpatialSigma=" << (ctx.relativeSpatialSigmaAvailable ? "true" : "false")
        << ";spatialLscVarianceAlreadyIncluded="
        << (ctx.spatialShapeIncludesLscVariance ? "true" : "false")
        << ";fusionVarianceAlreadyIncluded="
        << (ctx.fusionVarianceAlreadyIncluded ? "true" : "false")
        << ";postFinalizeVarianceY=" << ctx.postFinalize.varianceY
        << ";postDemosaicVarianceY=" << ctx.postDemosaic.varianceY
        << ";predictionConfidence=" << ctx.predictionConfidence
        << ";observedPostDemosaicY=" << ctx.observedPostDemosaicY
        << ";observedSamples=" << ctx.observedSamples
        << ";observationToPredictionY=" << ctx.observedToPredictedY()
        << ";extraRawGainApplications=0;extraFusionGainApplications=0}";
    return out.str();
}
} // namespace bncam::sf2
