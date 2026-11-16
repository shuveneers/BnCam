#pragma once
// SF2-D01: immutable, read-only description of the CURRENT physical noise path.
// No second S/O resolver and no authority to mutate pixels or calibration.
#include "SpectraNoiseCalibration.h"
#include "DemosaicNoiseCorrelation.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <string>

namespace bncam::sf2 {
struct SingleFrameNoiseContext final {
    const std::array<double,4> sensorS; // R, Gr, Gb, B; existing normalized RAW S/O
    const std::array<double,4> sensorO;
    const spectra2::NoiseState postFinalize; // existing PRE_DEMOSAIC model, after RAW finalize
    const spectra2::NoiseState postDemosaic; // existing predicted camera RGB covariance
    const spectra2::DemosaicModel model;
    const bool autoHybrid;
    const bool physicalAvailable;
    const bool relativeSpatialSigmaAvailable; // existing tile map owns the actual data
    const double observedPostDemosaicY;
    const std::size_t observedSamples;
    const SpatialNoiseCorrelation correlation; // only exact fixed kernels are known
    const double modelConfidence; // prediction confidence, not estimator authority
    const std::string provenance;

    static bool validSo(const std::array<double,4>& s, const std::array<double,4>& o) {
        for(std::size_t i=0;i<4;++i)
            if(!std::isfinite(s[i]) || !std::isfinite(o[i]) || s[i]<0 || o[i]<0 ||
               !(s[i]+o[i]>0)) return false;
        return true;
    }
    static bool validCovariance(const spectra2::NoiseState& n) {
        if(!(n.confidence>0 && n.confidence<=1) || !std::isfinite(n.varianceY) ||
           !(n.varianceY>0)) return false;
        for(int i=0;i<3;++i)
            if(!std::isfinite(n.covariance.at(i,i)) || !(n.covariance.at(i,i)>0)) return false;
        return true;
    }
    // Strictly read existing post-finalize and post-demosaic states; no repeated gain.
    static SingleFrameNoiseContext observe(const std::array<double,4>& s,
            const std::array<double,4>& o, const spectra2::NoiseState& finalized,
            const spectra2::NoiseState& demosaiced,
            const spectra2::ResidualObservation& measurement,
            spectra2::DemosaicModel model, bool hybrid, bool physical,
            bool spatialReady) {
        const bool ready=physical && validSo(s,o) && validCovariance(demosaiced);
        const double observation=measurement.acceptedSampleCount>=2048 &&
                std::isfinite(measurement.robustVarianceY) &&
                measurement.robustVarianceY>0 ? measurement.robustVarianceY : 0;
        return {s,o,finalized,demosaiced,model,hybrid,ready,spatialReady,
                observation,measurement.acceptedSampleCount,
                ready?predictSpatialCorrelation(finalized,demosaiced,model,hybrid):SpatialNoiseCorrelation{},
                ready?demosaiced.confidence:0.0,
                ready?"PHYSICAL_SO_EXISTING_POST_DEMOSAIC":"NO_VALID_PHYSICAL_NOISE"};
    }
    double rawVariance(std::size_t channel,double normalizedSignal) const {
        if(!physicalAvailable || channel>=4 || !std::isfinite(normalizedSignal)) return 0;
        const double variance=sensorS[channel]*std::max(0.0,normalizedSignal)+sensorO[channel];
        return std::isfinite(variance) && variance>0?variance:0;
    }
    // Read-only: external estimates may be reported but cannot override S/O.
    double diagnosticObservedToPredictedY() const {
        return observedPostDemosaicY>0 && postDemosaic.varianceY>0
            ? observedPostDemosaicY/postDemosaic.varianceY : 0;
    }
};
} // namespace bncam::sf2
