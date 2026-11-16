#pragma once
// SF2-D03: optional, diagnostic-only single-frame Poisson-Gaussian RAW
// variance-versus-signal fit. No S/O authority and no Vulkan readback.
// The caller MUST provide an existing, black-subtracted, normalized,
// PRE_LSC / PRE_DEMOSAIC, CPU-accessible Bayer mosaic with matching CFA origin.
// One exposure with scene content does NOT establish true sensor calibration.
#include "RawCfaLevelMapping.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <limits>
#include <sstream>
#include <iomanip>
#include <string>
#include <vector>

namespace bncam::sf2 {

struct RawNoiseView final {
    const float* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t strideFloats = 0; // element stride, not bytes
    int cfaPattern = -1;
};

struct RawPoissonGaussianObservation final {
    bool valid = false;
    bool overridesPhysicalModel = false; // diagnostic only; NEVER true
    bool appliedToPixels = false;         // NEVER true
    bool inputWasReadBackFromGpu = false;  // NEVER true
    const char* status = "NOT_RUN";
    std::array<double,4> slope{};
    std::array<double,4> offset{};
    std::array<double,4> fitScore{};
    std::array<double,4> supportConfidence{}; // heuristic fit support, NOT model authority
    std::array<double,4> signalSpan{};
    std::array<int,4> acceptedTiles{};
    std::array<int,4> rejectedTiles{};
    std::array<const char*,4> channelStatus{{"NOT_RUN","NOT_RUN","NOT_RUN","NOT_RUN"}};
    int scannedTiles = 0;
};

inline bool validPhysicalRawSo(const std::array<double,4>& s,
                               const std::array<double,4>& o) noexcept {
    for (std::size_t i=0;i<4;++i) {
        if (!std::isfinite(s[i]) || !std::isfinite(o[i]) ||
            s[i]<0 || o[i]<0 || !(s[i]+o[i]>0)) return false;
    }
    return true;
}

struct RawNoiseTileSample final {
    double signal = 0;
    double sampleVariance = 0;
};

inline RawPoissonGaussianObservation estimateRawPoissonGaussian(
        const RawNoiseView& view, const std::array<double,4>& physicalS,
        const std::array<double,4>& physicalO) {
    RawPoissonGaussianObservation result{};
    if (view.data == nullptr) {
        result.status="NO_HOST_RAW";
        return result;
    }
    if (view.width<128 || view.height<128 || view.width>20000 || view.height>20000 ||
        view.strideFloats<static_cast<std::size_t>(view.width) ||
        view.strideFloats>static_cast<std::size_t>(std::numeric_limits<int>::max()) ||
        view.cfaPattern<0 || view.cfaPattern>3 ||
        view.strideFloats>std::numeric_limits<std::size_t>::max() /
                                     static_cast<std::size_t>(view.height)) {
        result.status="INVALID_VIEW_OR_CFA";
        return result;
    }
    if (!validPhysicalRawSo(physicalS,physicalO)) {
        result.status="PHYSICAL_SO_UNAVAILABLE_NO_FIT";
        return result;
    }

    constexpr int tileSide=16;
    constexpr int tileStride=32;  // bounded debug sampling; never full-frame scan
    std::array<std::vector<RawNoiseTileSample>,4> tiles{};
    for (int startY=0; startY+tileSide<=view.height; startY+=tileStride) {
        for (int startX=0; startX+tileSide<=view.width; startX+=tileStride) {
            ++result.scannedTiles;
            for (int py=0;py<2;++py) {
                for (int px=0;px<2;++px) {
                    const int channel=bncam::raw::canonicalPlaneAtMosaicSite(
                            view.cfaPattern,startX+px,startY+py);
                    const auto ch=static_cast<std::size_t>(channel);
                    // Exactly 64 same-CFA positions, Welford stable sample variance.
                    double mean=0, m2=0, minSignal=1, maxSignal=0;
                    int count=0;
                    bool rejected=false;
                    for (int y=startY+py;y<startY+tileSide && !rejected;y+=2) {
                        for (int x=startX+px;x<startX+tileSide;x+=2) {
                            const double value=view.data[
                                static_cast<std::size_t>(y)*view.strideFloats+
                                static_cast<std::size_t>(x)];
                            if (!std::isfinite(value) || value<=0.005 || value>=0.985) {
                                rejected=true;
                                break;
                            }
                            ++count;
                            const double diff=value-mean;
                            mean+=diff/static_cast<double>(count);
                            m2+=diff*(value-mean);
                            minSignal=std::min(minSignal,value);
                            maxSignal=std::max(maxSignal,value);
                        }
                    }
                    if (!rejected && count==64) {
                        const double variance=m2/63.0;
                        const double expected=physicalS[ch]*mean+physicalO[ch];
                        // Rejection depends on the existing physical S/O; the
                        // result must NOT be advertised as an independent calibration.
                        if (std::isfinite(expected) && expected>0 &&
                            std::isfinite(variance) && variance>expected*0.10 &&
                            variance<expected*3.0 &&
                            (maxSignal-minSignal)<
                              std::max(0.10,12.0*std::sqrt(expected))) {
                            tiles[ch].push_back({mean,variance});
                            continue;
                        }
                    }
                    ++result.rejectedTiles[ch];
                }
            }
        }
    }

    bool allValid=true;
    for (std::size_t ch=0;ch<4;++ch) {
        const auto& samples=tiles[ch];
        const double n=static_cast<double>(samples.size());
        result.acceptedTiles[ch]=static_cast<int>(samples.size());
        if (samples.size()<24) {
            result.channelStatus[ch]="INSUFFICIENT_FLAT_TILE_SUPPORT";
            allValid=false;
            continue;
        }
        double minX=1,maxX=0,sumX=0,sumY=0,sumXX=0,sumXY=0;
        for (const auto& tile:samples) {
            minX=std::min(minX,tile.signal);
            maxX=std::max(maxX,tile.signal);
            sumX+=tile.signal;
            sumY+=tile.sampleVariance;
            sumXX+=tile.signal*tile.signal;
            sumXY+=tile.signal*tile.sampleVariance;
        }
        result.signalSpan[ch]=maxX-minX;
        if (result.signalSpan[ch]<0.25) {
            result.channelStatus[ch]="INSUFFICIENT_SIGNAL_SPAN";
            allValid=false;
            continue;
        }
        const double det=n*sumXX-sumX*sumX;
        if (!(det>1.e-8) || !std::isfinite(det)) {
            result.channelStatus[ch]="UNIDENTIFIABLE_SLOPE";
            allValid=false;
            continue;
        }
        const double slope=(n*sumXY-sumX*sumY)/det;
        const double offset=(sumY-slope*sumX)/n;
        if (!std::isfinite(slope) || !std::isfinite(offset) ||
            !(slope>0) || !(offset>0)) {
            result.channelStatus[ch]="NONPHYSICAL_FIT";
            allValid=false;
            continue;
        }
        result.slope[ch]=slope;
        result.offset[ch]=offset;
        double error=0,energy=0;
        for (const auto& tile:samples) {
            const double residual=tile.sampleVariance-(slope*tile.signal+offset);
            error+=residual*residual;
            energy+=tile.sampleVariance*tile.sampleVariance;
        }
        result.fitScore[ch]=std::clamp(1.0-error/std::max(1.e-20,energy),0.0,1.0);
        // This is observational support, NEVER a regulatory or calibrated
        // probability/confidence. Clip for bounded diagnostics.
        result.supportConfidence[ch]=std::min(1.0,n/96.0) *
            std::min(1.0,result.signalSpan[ch]/0.55)*result.fitScore[ch];

        // Existing physical S/O is the ONLY authority. The model-guided
        // rejection and comparability gate deliberately limit identifiability.
        if (!(physicalS[ch]>0 && physicalO[ch]>0)) {
            result.channelStatus[ch]="ZERO_COMPONENT_NOT_IDENTIFIABLE";
            allValid=false;
            continue;
        }
        const double slopeRatio=slope/physicalS[ch];
        const double offsetRatio=offset/physicalO[ch];
        if (!(slopeRatio>=0.25 && slopeRatio<=4.0 &&
              offsetRatio>=0.25 && offsetRatio<=4.0)) {
            result.channelStatus[ch]="PHYSICAL_MODEL_DISAGREEMENT";
            allValid=false;
            continue;
        }
        if (!(result.supportConfidence[ch]>=0.18)) {
            result.channelStatus[ch]="LOW_FIT_SUPPORT";
            allValid=false;
            continue;
        }
        result.channelStatus[ch]="DIAGNOSTIC_FIT_ONLY";
    }
    result.valid=allValid;
    result.status=allValid
        ? "DIAGNOSTIC_FIT_READY_NO_COEFFICIENT_UPDATE"
        : "INSUFFICIENT_OR_CONFLICTING_EVIDENCE_PHYSICAL_SO_UNCHANGED";
    return result;
}

inline std::string formatRawPoissonGaussianDiagnostic(
        const RawPoissonGaussianObservation& fit) {
    std::ostringstream out;
    out<<std::setprecision(9)
       <<"; sf2RawPoissonGaussian={status="<<fit.status
       <<";valid="<<(fit.valid?"true":"false")
       <<";appliedToPixels=false;overridesPhysicalModel=false"
       <<";inputWasReadBackFromGpu=false"
       <<";domain=PRE_LSC_NORMALIZED_BAYER"
       <<";method=SAMPLED_16X16_CFA_SITE_VARIANCE_FIT_OEM_SO_GUIDED"
       <<";scannedTiles="<<fit.scannedTiles
       <<";acceptedTiles=[";
    for (std::size_t i=0;i<4;++i)out<<(i?",":"")<<fit.acceptedTiles[i];
    out<<"];channelStatus=[";
    for (std::size_t i=0;i<4;++i)out<<(i?",":"")<<fit.channelStatus[i];
    out<<"]";
    if (fit.valid) {
        out<<";diagnosticSlope=[";
        for (std::size_t i=0;i<4;++i)out<<(i?",":"")<<fit.slope[i];
        out<<"];diagnosticOffset=[";
        for (std::size_t i=0;i<4;++i)out<<(i?",":"")<<fit.offset[i];
        out<<"];fitSupport=[";
        for (std::size_t i=0;i<4;++i)out<<(i?",":"")<<fit.supportConfidence[i];
        out<<"]";
    } else {
        out<<";diagnosticCoefficients=UNAVAILABLE";
    }
    out<<"}";
    return out.str();
}
} // namespace bncam::sf2
