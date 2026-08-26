#include "../../main/cpp/SpectraNoisePropagation.h"

#include <cassert>
#include <cmath>
#include <iostream>

namespace {

bool nearlyEqual(double left, double right, double tolerance = 1.0e-9) {
    return std::abs(left - right) <= tolerance * std::max({1.0, std::abs(left), std::abs(right)});
}

void assertSymmetricFiniteNonNegativeDiagonal(const bncam::spectra2::Covariance3& covariance) {
    for (int row = 0; row < 3; ++row) {
        assert(std::isfinite(covariance.at(row, row)));
        assert(covariance.at(row, row) >= 0.0);
        for (int column = 0; column < 3; ++column) {
            assert(std::isfinite(covariance.at(row, column)));
            assert(nearlyEqual(covariance.at(row, column), covariance.at(column, row)));
        }
    }
}

void identityTransformPreservesState() {
    const auto input = bncam::spectra2::makeState(
            "INPUT",
            "FIXTURE",
            "AVAILABLE",
            1.0,
            bncam::spectra2::diagonalCovariance(0.002, 0.003, 0.004)
    );
    const std::array<double, 9> identity{
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
    };
    const auto output = bncam::spectra2::propagateLinear(
            input,
            identity,
            "IDENTITY",
            "IDENTITY"
    );
    for (std::size_t index = 0; index < 9; ++index) {
        assert(nearlyEqual(input.covariance.values[index], output.covariance.values[index]));
    }
    assert(nearlyEqual(input.varianceY, output.varianceY));
    assert(nearlyEqual(input.varianceRG, output.varianceRG));
    assert(nearlyEqual(input.varianceBG, output.varianceBG));
}


void identityAwbAndOpponentTransformsPreserveState() {
    const auto input = bncam::spectra2::makeState(
            "INPUT",
            "FIXTURE",
            "AVAILABLE",
            1.0,
            bncam::spectra2::diagonalCovariance(0.002, 0.003, 0.004)
    );
    const auto awb = bncam::spectra2::propagateAwb(input, {1.0, 1.0, 1.0});
    const auto opponent = bncam::spectra2::propagateOpponentGains(
            input,
            1.0,
            1.0,
            1.0,
            "IDENTITY_OPPONENT",
            "IDENTITY"
    );
    for (std::size_t index = 0; index < 9; ++index) {
        assert(nearlyEqual(input.covariance.values[index], awb.covariance.values[index]));
        assert(nearlyEqual(input.covariance.values[index], opponent.covariance.values[index]));
    }
}

void piecewiseCurveDerivativeFixturesMatchActualSlopes() {
    const std::vector<float> identity{0.0f, 0.25f, 0.50f, 0.75f, 1.0f};
    const std::vector<float> steepShadow{0.0f, 0.50f, 0.70f, 0.85f, 1.0f};
    const std::vector<float> flatShadow{0.0f, 0.10f, 0.35f, 0.65f, 1.0f};
    assert(nearlyEqual(bncam::spectra2::piecewiseLinearCurveDerivative(identity, 0.10), 1.0));
    assert(nearlyEqual(bncam::spectra2::piecewiseLinearCurveDerivative(steepShadow, 0.10), 2.0));
    assert(nearlyEqual(bncam::spectra2::piecewiseLinearCurveDerivative(flatShadow, 0.10), 0.4, 1.0e-7));
}

void invalidColourMatrixUsesExplicitIdentityFallback() {
    const auto input = bncam::spectra2::makeState(
            "POST_AWB",
            "FIXTURE",
            "AVAILABLE",
            0.9,
            bncam::spectra2::diagonalCovariance(0.001, 0.002, 0.003)
    );
    std::array<double, 9> invalid{
            1.0, 0.0, 0.0,
            0.0, std::numeric_limits<double>::quiet_NaN(), 0.0,
            0.0, 0.0, 1.0
    };
    const auto output = bncam::spectra2::propagateColourMatrix(input, invalid);
    assert(output.method == "INVALID_COLOUR_MATRIX_IDENTITY_FALLBACK");
    assert(output.confidence < input.confidence);
    for (std::size_t index = 0; index < 9; ++index) {
        assert(nearlyEqual(input.covariance.values[index], output.covariance.values[index]));
    }
}

void awbGainsIncreaseRedAndBlueOpponentVariance() {
    const auto input = bncam::spectra2::makeState(
            "POST_DEMOSAIC",
            "FIXTURE",
            "AVAILABLE",
            1.0,
            bncam::spectra2::diagonalCovariance(0.001, 0.001, 0.001)
    );
    const auto output = bncam::spectra2::propagateAwb(input, {2.0, 1.0, 1.8});
    assert(output.varianceRG > input.varianceRG);
    assert(output.varianceBG > input.varianceBG);
    assert(nearlyEqual(output.covariance.at(0, 0), 4.0 * input.covariance.at(0, 0)));
    assert(nearlyEqual(output.covariance.at(2, 2), 3.24 * input.covariance.at(2, 2)));
}

void colourMatrixCreatesCovarianceWithoutInvalidVariance() {
    const auto input = bncam::spectra2::makeState(
            "POST_AWB",
            "FIXTURE",
            "AVAILABLE",
            1.0,
            bncam::spectra2::diagonalCovariance(0.001, 0.002, 0.003)
    );
    const std::array<double, 9> matrix{
            1.10, -0.08, -0.02,
            -0.04, 1.08, -0.04,
            -0.02, -0.10, 1.12
    };
    const auto output = bncam::spectra2::propagateLinear(
            input,
            matrix,
            "POST_CCM",
            "A_SIGMA_AT"
    );
    assertSymmetricFiniteNonNegativeDiagonal(output.covariance);
    assert(bncam::spectra2::determinant(output.covariance) >= -bncam::spectra2::kEpsilon);
    assert(std::abs(output.covariance.at(0, 1)) > 0.0);
    assert(std::abs(output.covariance.at(1, 2)) > 0.0);
}

void toneDerivativeScalesLumaVarianceAndFlatCurveReducesIt() {
    const auto input = bncam::spectra2::makeState(
            "POST_CCM",
            "FIXTURE",
            "AVAILABLE",
            1.0,
            bncam::spectra2::diagonalCovariance(0.001, 0.001, 0.001)
    );
    const auto steep = bncam::spectra2::propagateOpponentGains(
            input,
            2.0,
            1.0,
            1.0,
            "STEEP",
            "DERIVATIVE"
    );
    const auto flat = bncam::spectra2::propagateOpponentGains(
            input,
            0.5,
            1.0,
            1.0,
            "FLAT",
            "DERIVATIVE"
    );
    assert(steep.varianceY > input.varianceY);
    assert(flat.varianceY < input.varianceY);
    assert(nearlyEqual(steep.varianceRG, input.varianceRG, 1.0e-7));
    assert(nearlyEqual(flat.varianceBG, input.varianceBG, 1.0e-7));
}

void unknownDemosaicUsesExplicitBoundedFallback() {
    const auto input = bncam::spectra2::makeState(
            "PRE_DEMOSAIC",
            "FIXTURE",
            "AVAILABLE",
            0.9,
            bncam::spectra2::diagonalCovariance(0.001, 0.0015, 0.002)
    );
    const auto output = bncam::spectra2::propagateDemosaic(
            input,
            bncam::spectra2::DemosaicModel::Unknown
    );
    assert(output.status == "FALLBACK_PROPAGATED");
    assert(output.method == "UNKNOWN_DEMOSAIC_MALVAR_ENERGY_FALLBACK");
    assert(output.confidence < input.confidence);
    assertSymmetricFiniteNonNegativeDiagonal(output.covariance);
}

void invalidCovarianceIsSanitized() {
    bncam::spectra2::Covariance3 covariance{};
    covariance.at(0, 0) = -1.0;
    covariance.at(1, 1) = std::numeric_limits<double>::quiet_NaN();
    covariance.at(2, 2) = 0.001;
    covariance.at(0, 1) = 100.0;
    covariance.at(1, 0) = -20.0;
    const auto sanitized = bncam::spectra2::sanitizeCovariance(covariance);
    assertSymmetricFiniteNonNegativeDiagonal(sanitized);
}

} // namespace

int main() {
    identityTransformPreservesState();
    identityAwbAndOpponentTransformsPreserveState();
    piecewiseCurveDerivativeFixturesMatchActualSlopes();
    awbGainsIncreaseRedAndBlueOpponentVariance();
    colourMatrixCreatesCovarianceWithoutInvalidVariance();
    toneDerivativeScalesLumaVarianceAndFlatCurveReducesIt();
    unknownDemosaicUsesExplicitBoundedFallback();
    invalidColourMatrixUsesExplicitIdentityFallback();
    invalidCovarianceIsSanitized();
    std::cout << "SPECTRA_NOISE_PROPAGATION_TESTS_OK\n";
    return 0;
}
