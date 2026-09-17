#include "../../main/cpp/NeuralRawDenoiseBackend.h"

#include <cassert>
#include <cstring>
#include <iostream>

int main() {
    using namespace bncam::spectra::neural;
    assert(std::strcmp(neuralBypassReasonName(NeuralBypassReason::UserDisabled),
                       "user_disabled") == 0);
    assert(std::strcmp(neuralBypassReasonName(NeuralBypassReason::PosteriorInvalid),
                       "posterior_invalid") == 0);
    assert(std::strcmp(neuralBackendFailureCodeName(NeuralBackendFailureCode::None),
                       "none") == 0);
    assert(std::strcmp(neuralBackendFailureCodeName(NeuralBackendFailureCode::InvalidRequest),
                       "invalid_request") == 0);
    assert(std::strcmp(neuralBackendFailureCodeName(NeuralBackendFailureCode::DispatchFailed),
                       "dispatch_failed") == 0);
    assert(std::strcmp(neuralBackendFailureCodeName(NeuralBackendFailureCode::InvalidPosteriorOutput),
                       "invalid_posterior_output") == 0);
    std::cout << "NeuralTelemetryNameTest PASS\n";
    return 0;
}
