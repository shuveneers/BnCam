#pragma once
// SF2-D02: analytic, read-only Bayer fixed-kernel Y noise overlap.
// The calculation assumes locally stationary, independent CFA sensor sites.
// It is a KERNEL-ONLY PROXY, NOT a measured total post-demosaic noise correlation.
// Adaptive AMaZE/BnC Neural/Auto Hybrid, and known neural-posterior inputs
// must remain unknown. Never use unknown values as rho=0 for filtering.
#include "SpectraNoisePropagation.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <limits>

namespace bncam::sf2 {

struct DemosaicSpatialNoise final {
    bool kernelProxyAvailable = false;
    bool inputIndependenceVerified = false;  // Never verified by the source model.
    bool measured = false;                   // No new readback or pixel sampling.
    // rho entries are meaningful ONLY if kernelProxyAvailable is true.
    double rhoHorizontal = 0.0;
    double rhoVertical = 0.0;
    double rhoDiagonalDown = 0.0; // +1,+1
    double rhoDiagonalUp = 0.0;   // +1,-1
    // Derived from the same phase-weighted kernels (not a second S/O estimate).
    double kernelVarianceY = 0.0;
    double existingPredictionVarianceY = 0.0;
    double existingPredictionConfidence = 0.0; // Copied, never upgraded.
    const char* status = "UNAVAILABLE";
    const char* method = "NONE";
};

// Fixed RGGB reference phase: 0=R, 1=Gr, 2=Gb, 3=B.
// Other Bayer layouts shift/permute the four phases, so the phase average
// is invariant for an interior, locally stationary Bayer field.
inline std::array<double, 3> fixedDemosaicImpulseRgb(
        int phase, int x, int y, bool malvar) noexcept {
    const auto at = [=](int cx, int cy) {
        return (x == cx && y == cy) ? 1.0 : 0.0;
    };
    const double center = at(0, 0);
    const double horizontal = at(-1, 0) + at(1, 0);
    const double vertical = at(0, -1) + at(0, 1);
    const double horizontal2 = at(-2, 0) + at(2, 0);
    const double vertical2 = at(0, -2) + at(0, 2);
    const double diagonal = at(-1, -1) + at(-1, 1) +
                            at(1, -1) + at(1, 1);
    const double green = malvar
            ? (4.0*center + 2.0*(horizontal+vertical) - horizontal2-vertical2)/8.0
            : (horizontal+vertical)/4.0;
    const double opposite = malvar
            ? (6.0*center + 2.0*diagonal - 1.5*(horizontal2+vertical2))/8.0
            : diagonal/4.0;
    const double crossH = malvar
            ? (5.0*center + 4.0*horizontal - horizontal2 - diagonal +
               0.5*vertical2)/8.0
            : horizontal/2.0;
    const double crossV = malvar
            ? (5.0*center + 4.0*vertical - vertical2 - diagonal +
               0.5*horizontal2)/8.0
            : vertical/2.0;
    if (phase == 0) return {center, green, opposite};
    if (phase == 3) return {opposite, green, center};
    if (phase == 1) return {crossH, center, crossV};
    return {crossV, center, crossH};
}

inline int bayerColourAt(int phase, int x, int y) noexcept {
    const int cx = ((phase & 1) + x + 8) & 1;
    const int cy = (((phase >> 1) & 1) + y + 8) & 1;
    return (cx == 0 && cy == 0) ? 0 : ((cx == 1 && cy == 1) ? 2 : 1);
}

inline double fixedImpulseY(int phase, int x, int y, bool malvar) noexcept {
    const auto rgb = fixedDemosaicImpulseRgb(phase, x, y, malvar);
    return 0.2126*rgb[0] + 0.7152*rgb[1] + 0.0722*rgb[2];
}

inline bool validIndependentCfaPrediction(const spectra2::NoiseState& noise) noexcept {
    if (!(noise.confidence > 0.0 && noise.confidence <= 1.0) ||
        !std::isfinite(noise.varianceY) || !(noise.varianceY > 0.0)) return false;
    for (int row=0; row<3; ++row) {
        for (int col=0; col<3; ++col) {
            const double v = noise.covariance.at(row, col);
            if (!std::isfinite(v) || (row==col ? !(v>0.0) : v!=0.0)) return false;
        }
    }
    return true;
}

struct NoiseOverlap final {
    double cov = 0.0;
    double var0 = 0.0;
    double var1 = 0.0;
};

// Correlate two outputs at (0,0), (dx,dy) via shared input sample indices.
// Both output stencils are evaluated against the SAME physical CFA site and
// weighted by that site's channel variance; no kernel-energy-as-colour-matrix shortcut.
inline NoiseOverlap fixedKernelOverlap(
        const std::array<double,3>& variance,
        bool malvar, int dx, int dy) noexcept {
    NoiseOverlap output{};
    for (int phase=0; phase<4; ++phase) {
        const int shifted = ((((phase >> 1) + dy + 8) & 1) << 1) |
                            (((phase & 1) + dx + 8) & 1);
        // Radius 2 and displacement at most 1: +/-3 covers both stencils.
        for (int y=-3; y<=3; ++y) {
            for (int x=-3; x<=3; ++x) {
                const auto ch = static_cast<std::size_t>(bayerColourAt(phase,x,y));
                const double a = fixedImpulseY(phase,x,y,malvar);
                const double b = fixedImpulseY(shifted,x-dx,y-dy,malvar);
                const double v = variance[ch];
                output.cov += v*a*b;
                output.var0 += v*a*a;
                output.var1 += v*b*b;
            }
        }
    }
    constexpr double invPhases=0.25;
    output.cov*=invPhases;
    output.var0*=invPhases;
    output.var1*=invPhases;
    return output;
}

inline DemosaicSpatialNoise predictDemosaicSpatialNoise(
        const spectra2::NoiseState& finalized,
        const spectra2::NoiseState& demosaiced,
        spectra2::DemosaicModel mode,
        bool autoHybrid,
        bool physicalAvailable,
        bool neuralPosteriorSeedApplied) noexcept {
    DemosaicSpatialNoise out{};
    out.existingPredictionVarianceY = demosaiced.varianceY;
    out.existingPredictionConfidence = demosaiced.confidence;
    if (!physicalAvailable) {
        out.status = "PHYSICAL_NOISE_UNAVAILABLE";
        return out;
    }
    if (autoHybrid) {
        out.status = "AUTO_HYBRID_SPATIAL_WEIGHTS_UNKNOWN";
        return out;
    }
    if (mode != spectra2::DemosaicModel::Malvar2004 &&
        mode != spectra2::DemosaicModel::Bilinear) {
        out.status = "ADAPTIVE_OR_UNSUPPORTED_KERNEL_UNKNOWN";
        return out;
    }
    // The learned posterior already has unknown spatial correlations; even a
    // fixed demosaic cannot recover a trustworthy TOTAL noise rho from variances.
    if (neuralPosteriorSeedApplied) {
        out.status = "NEURAL_POSTERIOR_INPUT_SPATIAL_CORRELATION_UNKNOWN";
        return out;
    }
    if (!validIndependentCfaPrediction(finalized) ||
        !(demosaiced.confidence>0.0 && demosaiced.confidence<=1.0) ||
        !(demosaiced.varianceY>0.0) || !std::isfinite(demosaiced.varianceY)) {
        out.status = "INVALID_OR_NONDIAGONAL_INPUT_COVARIANCE";
        return out;
    }

    const std::array<double,3> v{
        finalized.covariance.at(0,0),
        finalized.covariance.at(1,1),
        finalized.covariance.at(2,2)};
    const bool malvar = mode == spectra2::DemosaicModel::Malvar2004;
    const auto h = fixedKernelOverlap(v,malvar,1,0);
    const auto vertical = fixedKernelOverlap(v,malvar,0,1);
    const auto diagonalDown = fixedKernelOverlap(v,malvar,1,1);
    const auto diagonalUp = fixedKernelOverlap(v,malvar,1,-1);

    auto rho = [](const NoiseOverlap& e) noexcept {
        const double divisor = std::sqrt(e.var0 * e.var1);
        return divisor>0.0 && std::isfinite(divisor)
                ? e.cov/divisor : std::numeric_limits<double>::quiet_NaN();
    };
    const std::array<double,4> correlations{
        rho(h), rho(vertical), rho(diagonalDown), rho(diagonalUp)};
    for (double c: correlations)
        if (!std::isfinite(c) || std::abs(c)>1.0+1.0e-10) {
            out.status = "INVALID_KERNEL_OVERLAP";
            return out;
        }

    if (!(h.var0>0.0) || !std::isfinite(h.var0)) {
        out.status = "INVALID_KERNEL_VARIANCE";
        return out;
    }
    out.kernelProxyAvailable = true;
    out.rhoHorizontal = std::clamp(correlations[0],-1.0,1.0);
    out.rhoVertical = std::clamp(correlations[1],-1.0,1.0);
    out.rhoDiagonalDown = std::clamp(correlations[2],-1.0,1.0);
    out.rhoDiagonalUp = std::clamp(correlations[3],-1.0,1.0);
    out.kernelVarianceY = h.var0;
    out.status = "FIXED_KERNEL_PROXY_INDEPENDENT_CFA_ASSUMED_NOT_MEASURED";
    out.method = malvar
            ? "MALVAR_2004_PHASE_AVERAGED_IMPULSE_OVERLAP"
            : "BILINEAR_PHASE_AVERAGED_IMPULSE_OVERLAP";
    return out;
}

} // namespace bncam::sf2
