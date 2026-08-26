#include "../../main/cpp/RawDefectCorrectionPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::raw_defect;
    assert(std::abs(predictedSigma(0.2f, 0.001f, 0.0001f) - std::sqrt(0.0003f)) < 1e-7f);
    assert(predictedSigma(0.2f, -1.0f, 0.0f) == 0.0f);

    const float base = 0.05f;
    const float lowIso = finalThreshold(base, 0.006f, 0.10f, 0.0001f, 0.000001f, true);
    assert(lowIso == base);

    const float highIso = finalThreshold(base, 0.006f, 0.10f, 0.001f, 0.0002f, true);
    assert(highIso > base); // high sensor variance must raise the outlier evidence requirement

    const float structured = finalThreshold(base, 0.04f, 0.10f, 0.0f, 0.0f, false);
    assert(structured >= 0.16f); // real local structure remains protected

    const float noModel = preScanThreshold(0.07f, 0.10f, 0.105f, 0.102f, 1.0f, 1.0f, false);
    assert(std::abs(noModel - 0.07f) < 1e-7f); // exact legacy floor when model unavailable
    return 0;
}
