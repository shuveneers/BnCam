#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace bncam::spectra2 {

constexpr double kEpsilon = 1.0e-12;

struct Covariance3 {
    std::array<double, 9> values{
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0
    };

    double& at(int row, int column) {
        return values[static_cast<std::size_t>(row * 3 + column)];
    }

    double at(int row, int column) const {
        return values[static_cast<std::size_t>(row * 3 + column)];
    }
};

struct NoiseState {
    std::string stage = "UNKNOWN";
    std::string method = "UNINITIALIZED";
    std::string status = "UNAVAILABLE";
    double confidence = 0.0;
    Covariance3 covariance{};
    double varianceY = 0.0;
    double varianceRG = 0.0;
    double varianceBG = 0.0;
    double covarianceRgBg = 0.0;
};

struct DerivativeStats {
    double mean = 1.0;
    double rms = 1.0;
    double p10 = 1.0;
    double p50 = 1.0;
    double p90 = 1.0;
    double minimum = 1.0;
    double maximum = 1.0;
    std::size_t sampleCount = 0;
};

enum class DemosaicModel {
    Bilinear,
    Malvar2004,
    Menon2007,
    RcdInspired,
    AmazeInspired,
    Unknown
};

inline bool finite(double value) {
    return std::isfinite(value);
}


inline double piecewiseLinearCurveDerivative(const std::vector<float>& curve, double x) {
    if (curve.size() <= 1u) return 1.0;
    const double clampedX = std::clamp(finite(x) ? x : 0.0, 0.0, 1.0);
    const double position = clampedX * static_cast<double>(curve.size() - 1u);
    const int index = std::clamp(
            static_cast<int>(std::floor(position)),
            0,
            static_cast<int>(curve.size()) - 2
    );
    const double left = std::clamp(
            finite(curve[static_cast<std::size_t>(index)])
                    ? static_cast<double>(curve[static_cast<std::size_t>(index)])
                    : 0.0,
            0.0,
            1.0
    );
    const double right = std::clamp(
            finite(curve[static_cast<std::size_t>(index + 1)])
                    ? static_cast<double>(curve[static_cast<std::size_t>(index + 1)])
                    : left,
            0.0,
            1.0
    );
    const double derivative = (right - left) * static_cast<double>(curve.size() - 1u);
    return finite(derivative) ? std::max(0.0, derivative) : 1.0;
}

inline bool finiteTransform(const std::array<double, 9>& transform) {
    return std::all_of(transform.begin(), transform.end(), [](double value) {
        return finite(value);
    });
}

inline Covariance3 diagonalCovariance(double varianceR, double varianceG, double varianceB) {
    Covariance3 covariance{};
    covariance.at(0, 0) = std::max(0.0, finite(varianceR) ? varianceR : 0.0);
    covariance.at(1, 1) = std::max(0.0, finite(varianceG) ? varianceG : 0.0);
    covariance.at(2, 2) = std::max(0.0, finite(varianceB) ? varianceB : 0.0);
    return covariance;
}

inline double determinant(const Covariance3& covariance) {
    const double a = covariance.at(0, 0);
    const double b = covariance.at(0, 1);
    const double c = covariance.at(0, 2);
    const double d = covariance.at(1, 0);
    const double e = covariance.at(1, 1);
    const double f = covariance.at(1, 2);
    const double g = covariance.at(2, 0);
    const double h = covariance.at(2, 1);
    const double i = covariance.at(2, 2);
    return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
}

inline Covariance3 sanitizeCovariance(Covariance3 covariance) {
    for (double& value : covariance.values) {
        if (!finite(value)) value = 0.0;
    }

    for (int index = 0; index < 3; ++index) {
        covariance.at(index, index) = std::max(0.0, covariance.at(index, index));
    }

    for (int row = 0; row < 3; ++row) {
        for (int column = row + 1; column < 3; ++column) {
            const double symmetric = 0.5 * (
                    covariance.at(row, column) + covariance.at(column, row)
            );
            const double bound = std::sqrt(std::max(
                    0.0,
                    covariance.at(row, row) * covariance.at(column, column)
            ));
            const double clipped = std::clamp(symmetric, -bound, bound);
            covariance.at(row, column) = clipped;
            covariance.at(column, row) = clipped;
        }
    }

    // Pairwise correlation bounds are necessary but not sufficient for a 3x3 PSD matrix.
    // Uniformly attenuate off-diagonal terms until the determinant is non-negative.
    for (int iteration = 0; iteration < 24 && determinant(covariance) < -kEpsilon; ++iteration) {
        for (int row = 0; row < 3; ++row) {
            for (int column = row + 1; column < 3; ++column) {
                covariance.at(row, column) *= 0.85;
                covariance.at(column, row) = covariance.at(row, column);
            }
        }
    }
    if (determinant(covariance) < -kEpsilon) {
        for (int row = 0; row < 3; ++row) {
            for (int column = row + 1; column < 3; ++column) {
                covariance.at(row, column) = 0.0;
                covariance.at(column, row) = 0.0;
            }
        }
    }

    return covariance;
}

inline Covariance3 multiply(const std::array<double, 9>& transform, const Covariance3& input) {
    Covariance3 temporary{};
    Covariance3 output{};
    for (int row = 0; row < 3; ++row) {
        for (int column = 0; column < 3; ++column) {
            double sum = 0.0;
            for (int inner = 0; inner < 3; ++inner) {
                sum += transform[static_cast<std::size_t>(row * 3 + inner)] * input.at(inner, column);
            }
            temporary.at(row, column) = sum;
        }
    }
    for (int row = 0; row < 3; ++row) {
        for (int column = 0; column < 3; ++column) {
            double sum = 0.0;
            for (int inner = 0; inner < 3; ++inner) {
                sum += temporary.at(row, inner) *
                        transform[static_cast<std::size_t>(column * 3 + inner)];
            }
            output.at(row, column) = sum;
        }
    }
    return sanitizeCovariance(output);
}

inline NoiseState makeState(
        std::string stage,
        std::string method,
        std::string status,
        double confidence,
        Covariance3 covariance
) {
    covariance = sanitizeCovariance(covariance);
    static constexpr std::array<double, 3> yWeights{0.2126, 0.7152, 0.0722};
    static constexpr std::array<double, 3> rgWeights{1.0, -1.0, 0.0};
    static constexpr std::array<double, 3> bgWeights{0.0, -1.0, 1.0};

    const auto quadratic = [&](const std::array<double, 3>& left,
                               const std::array<double, 3>& right) -> double {
        double value = 0.0;
        for (int row = 0; row < 3; ++row) {
            for (int column = 0; column < 3; ++column) {
                value += left[static_cast<std::size_t>(row)] * covariance.at(row, column) *
                        right[static_cast<std::size_t>(column)];
            }
        }
        return finite(value) ? value : 0.0;
    };

    NoiseState state{};
    state.stage = std::move(stage);
    state.method = std::move(method);
    state.status = std::move(status);
    state.confidence = std::clamp(finite(confidence) ? confidence : 0.0, 0.0, 1.0);
    state.covariance = covariance;
    state.varianceY = std::max(0.0, quadratic(yWeights, yWeights));
    state.varianceRG = std::max(0.0, quadratic(rgWeights, rgWeights));
    state.varianceBG = std::max(0.0, quadratic(bgWeights, bgWeights));
    state.covarianceRgBg = quadratic(rgWeights, bgWeights);
    return state;
}

inline NoiseState propagateLinear(
        const NoiseState& input,
        const std::array<double, 9>& transform,
        std::string stage,
        std::string method,
        double confidenceMultiplier = 1.0
) {
    const double multiplier = std::clamp(
            finite(confidenceMultiplier) ? confidenceMultiplier : 0.0,
            0.0,
            1.0
    );
    return makeState(
            std::move(stage),
            std::move(method),
            "PROPAGATED",
            input.confidence * multiplier,
            multiply(transform, input.covariance)
    );
}

inline NoiseState propagateAwb(
        const NoiseState& input,
        const std::array<double, 3>& gains,
        std::string stage = "POST_AWB"
) {
    std::array<double, 9> diagonal{
            gains[0], 0.0, 0.0,
            0.0, gains[1], 0.0,
            0.0, 0.0, gains[2]
    };
    bool valid = true;
    for (double gain : gains) valid = valid && finite(gain) && gain >= 0.0;
    if (!valid) {
        diagonal = {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
    }
    return propagateLinear(
            input,
            diagonal,
            std::move(stage),
            valid ? "AWB_DIAGONAL_G_SIGMA_GT" : "AWB_INVALID_GAIN_IDENTITY_FALLBACK",
            valid ? 1.0 : 0.45
    );
}

inline NoiseState propagateColourMatrix(
        const NoiseState& input,
        std::array<double, 9> transform,
        std::string stage = "POST_COLOUR_MATRIX_RGB"
) {
    const bool valid = finiteTransform(transform);
    if (!valid) {
        transform = {
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
        };
    }
    return propagateLinear(
            input,
            transform,
            std::move(stage),
            valid
                    ? "A_SIGMA_AT_PRE_NONNEGATIVE_CLAMP"
                    : "INVALID_COLOUR_MATRIX_IDENTITY_FALLBACK",
            valid ? 0.90 : 0.45
    );
}

inline std::array<double, 9> demosaicNoiseTransferMatrix(DemosaicModel model) {
    switch (model) {
        case DemosaicModel::Bilinear:
            // Average kernel-energy attenuation over the four Bayer phases.
            return {
                    0.7500000000, 0.0, 0.0,
                    0.0, 0.7905694150, 0.0,
                    0.0, 0.0, 0.7500000000
            };
        case DemosaicModel::Malvar2004:
            // Square roots of the phase-averaged MHC kernel-energy contributions.
            // This is a noise-amplitude transfer model, not a claim that MHC is a global RGB matrix.
            return {
                    0.7500000000, 0.4960783708, 0.4192627458,
                    0.2795084972, 0.7905694150, 0.2795084972,
                    0.4192627458, 0.4960783708, 0.7500000000
            };
        case DemosaicModel::Menon2007:
            // Directional Menon interpolation is scene-dependent. Use a bounded empirical
            // energy model and lower confidence rather than presenting an exact analytical model.
            return {
                    0.7874007874, 0.4242640687, 0.2828427125,
                    0.2449489743, 0.8485281374, 0.2449489743,
                    0.2828427125, 0.4242640687, 0.7874007874
            };
        case DemosaicModel::RcdInspired:
            // Delta 40: topology-calibrated flat-field prior for BnCam RCD Inspired. The
            // coefficients were estimated by injecting independent low-amplitude CFA noise
            // one physical colour at a time through the implemented two-pass RCD reconstruction.
            // The same calibration method reproduces the analytical Malvar matrix closely.
            // R/B symmetry is enforced to remove Monte-Carlo sampling asymmetry.
            return {
                    0.7505545000, 0.4832215000, 0.0,
                    0.0,          0.8432630000, 0.0,
                    0.0,          0.4832215000, 0.7505545000
            };
        case DemosaicModel::AmazeInspired:
            // Delta 40: equivalent flat-field prior for BnCam AMAZE Inspired. The higher
            // G-to-chroma transfer reflects its more detail-preserving directional green
            // reconstruction. Scene-dependent residual calibration remains authoritative.
            return {
                    0.7640750000, 0.5296955000, 0.0,
                    0.0,          0.7902400000, 0.0,
                    0.0,          0.5296955000, 0.7640750000
            };
        case DemosaicModel::Unknown:
        default:
            return {
                    0.7500000000, 0.4960783708, 0.4192627458,
                    0.2795084972, 0.7905694150, 0.2795084972,
                    0.4192627458, 0.4960783708, 0.7500000000
            };
    }
}

inline NoiseState propagateDemosaic(
        const NoiseState& input,
        DemosaicModel model,
        std::string stage = "POST_DEMOSAIC_RGB"
) {
    const bool fallback = model == DemosaicModel::Unknown;
    const double confidenceMultiplier = model == DemosaicModel::Menon2007
            ? 0.72
            : (model == DemosaicModel::RcdInspired
                    ? 0.70
                    : (model == DemosaicModel::AmazeInspired
                            ? 0.66
                            : (fallback ? 0.55 : 0.86)));
    std::string method;
    switch (model) {
        case DemosaicModel::Bilinear:
            method = "BILINEAR_PHASE_AVERAGED_KERNEL_ENERGY";
            break;
        case DemosaicModel::Malvar2004:
            method = "MALVAR_2004_PHASE_AVERAGED_KERNEL_ENERGY";
            break;
        case DemosaicModel::Menon2007:
            method = "MENON_2007_DIRECTIONAL_EMPIRICAL_ENERGY";
            break;
        case DemosaicModel::RcdInspired:
            method = "RCD_INSPIRED_FLAT_FIELD_TOPOLOGY_CALIBRATED_ENERGY";
            break;
        case DemosaicModel::AmazeInspired:
            method = "AMAZE_INSPIRED_FLAT_FIELD_TOPOLOGY_CALIBRATED_ENERGY";
            break;
        case DemosaicModel::Unknown:
        default:
            method = "UNKNOWN_DEMOSAIC_MALVAR_ENERGY_FALLBACK";
            break;
    }
    NoiseState output = propagateLinear(
            input,
            demosaicNoiseTransferMatrix(model),
            std::move(stage),
            std::move(method),
            confidenceMultiplier
    );
    if (fallback) output.status = "FALLBACK_PROPAGATED";
    return output;
}

inline NoiseState propagateOpponentGains(
        const NoiseState& input,
        double yGain,
        double rgGain,
        double bgGain,
        std::string stage,
        std::string method,
        double confidenceMultiplier = 1.0
) {
    const std::array<double, 9> rgbToOpponent{
            0.2126, 0.7152, 0.0722,
            1.0, -1.0, 0.0,
            0.0, -1.0, 1.0
    };
    // Exact inverse of the matrix above.
    const std::array<double, 9> opponentToRgb{
            1.0, 0.7874, -0.0722,
            1.0, -0.2126, -0.0722,
            1.0, -0.2126, 0.9278
    };
    const std::array<double, 9> gains{
            std::max(0.0, finite(yGain) ? yGain : 1.0), 0.0, 0.0,
            0.0, std::max(0.0, finite(rgGain) ? rgGain : 1.0), 0.0,
            0.0, 0.0, std::max(0.0, finite(bgGain) ? bgGain : 1.0)
    };

    const Covariance3 opponent = multiply(rgbToOpponent, input.covariance);
    const Covariance3 scaledOpponent = multiply(gains, opponent);
    const Covariance3 rgb = multiply(opponentToRgb, scaledOpponent);
    return makeState(
            std::move(stage),
            std::move(method),
            "PROPAGATED",
            input.confidence * std::clamp(confidenceMultiplier, 0.0, 1.0),
            rgb
    );
}

inline double chromaAmplification(const NoiseState& input, const NoiseState& output) {
    const double inputChroma = std::max(kEpsilon, 0.5 * (input.varianceRG + input.varianceBG));
    const double outputChroma = std::max(0.0, 0.5 * (output.varianceRG + output.varianceBG));
    return outputChroma / inputChroma;
}

} // namespace bncam::spectra2
