#pragma once
#include "SpectraNoisePropagation.h"
#include <iomanip>
#include <sstream>

namespace bncam::physical {
// Diagnostic prediction only; never a filter input. Kernel energy roots are NOT a colour transform:
// covariance requires products of coefficients at the SAME CFA sample.
// Exact phase-averaged interior covariance for independent, locally stationary
// CFA noise. Spatial covariance/borders and adaptive demosaicers remain estimates.
inline spectra2::NoiseState propagateFixedDemosaic(
        const spectra2::NoiseState& input, spectra2::DemosaicModel model,
        const spectra2::NoiseState& fallback) {
    using namespace spectra2;
    if(model!=DemosaicModel::Malvar2004 && model!=DemosaicModel::Bilinear) return fallback;
    for(int r=0;r<3;++r) for(int c=0;c<3;++c) {
        double v=input.covariance.at(r,c);
        if(!std::isfinite(v) || (r==c ? v<=0 : v!=0)) return fallback;
    }
    const double r=input.covariance.at(0,0),g=input.covariance.at(1,1),b=input.covariance.at(2,2);
    Covariance3 cov=diagonalCovariance(.5625*r,.625*g,.5625*b);
    if(model==DemosaicModel::Malvar2004) {
        cov.at(0,0)+=.24609375*g+.17578125*b;
        cov.at(1,1)+=.078125*(r+b);
        cov.at(2,2)+=.24609375*g+.17578125*r;
        cov.at(0,1)=cov.at(1,0)=.125*r+.3125*g+.1171875*b;
        cov.at(1,2)=cov.at(2,1)=.1171875*r+.3125*g+.125*b;
        cov.at(0,2)=cov.at(2,0)=.1875*(r+b)+.2109375*g;
    }
    // Preserve the existing uncertainty discount; exact kernel overlap does not
    // make a spatially averaged, scene-independent noise model perfectly certain.
    return makeState("POST_DEMOSAIC_RGB", "PHYSICAL_FIXED_KERNEL_OVERLAP",
                     "PROPAGATED", fallback.confidence, cov);
}

// Deliberately separate from chroma::Model / luma::Model and Vulkan requests.
// Preserves provenance and corrected prediction without authorizing pixel changes.
struct NoisePrediction {
    spectra2::NoiseState state{};
    double sourceConfidence = 0;
    bool valid = false;
};
inline NoisePrediction predictNoiseOnly(const spectra2::NoiseState& input,
        spectra2::DemosaicModel model, const spectra2::NoiseState& legacy,
        bool physicalAvailable) {
    NoisePrediction prediction{};
    prediction.sourceConfidence = legacy.confidence;
    if(!physicalAvailable || !std::isfinite(legacy.confidence) ||
       legacy.confidence<=0 || legacy.confidence>1) return prediction;
    if(model!=spectra2::DemosaicModel::Malvar2004 &&
       model!=spectra2::DemosaicModel::Bilinear) return prediction;
    // No synthetic trust or repaired covariance is substituted for invalid evidence.
    for(int r=0;r<3;++r) for(int c=0;c<3;++c) {
        const double v=input.covariance.at(r,c);
        if(!std::isfinite(v) || (r==c ? v<=0 : v!=0)) return prediction;
    }
    const auto state=propagateFixedDemosaic(input,model,legacy);
    if(!std::isfinite(state.varianceY) || !std::isfinite(state.varianceRG) ||
       !std::isfinite(state.varianceBG) || !std::isfinite(state.covarianceRgBg) ||
       state.varianceY<=0 || state.varianceRG<=0 || state.varianceBG<=0 ||
       std::abs(state.covarianceRgBg)>std::sqrt(state.varianceRG)*std::sqrt(state.varianceBG))
        return prediction;
    prediction.state=state;
    prediction.valid=true;
    return prediction;
}
inline std::string formatNoisePredictionOnly(const spectra2::NoiseState& input,
        spectra2::DemosaicModel model, const spectra2::NoiseState& legacy,
        bool physicalAvailable, bool eligible) {
    if(!eligible) return {};
    const auto p=predictNoiseOnly(input,model,legacy,physicalAvailable);
    std::ostringstream out;
    out << std::setprecision(17)
        << "; physicalNoisePredictionOnly={appliedToPixels=false;filterBaseline=8aee2d2"
        << ";valid=" << (p.valid?"true":"false")
        << ";sourceConfidence=" << p.sourceConfidence
        << ";modelConfidence=" << p.state.confidence
        << ";method=" << (p.valid?p.state.method:"UNAVAILABLE_OR_UNSUPPORTED_EVIDENCE")
        << ";varianceY=" << p.state.varianceY << ";varianceRG=" << p.state.varianceRG
        << ";varianceBG=" << p.state.varianceBG << ";covarianceRgBg=" << p.state.covarianceRgBg << "}";
    return out.str();
}
}
