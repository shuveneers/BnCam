#pragma once

#include <algorithm>
#include <cctype>
#include <string>

namespace bncam::raw_support_telemetry {

enum class RejectionCategory {
    NONE,
    CANONICALIZATION,
    ALIGNMENT,
    FORWARD_BACKWARD,
    SPECTRA_CONSENSUS,
    ZERO_WEIGHTED_CONTRIBUTION,
    OTHER,
};

struct RejectionBreakdown {
    int canonicalization = 0;
    int alignment = 0;
    int forwardBackward = 0;
    int spectraConsensus = 0;
    int zeroWeightedContribution = 0;
    int other = 0;

    int total() const noexcept {
        return canonicalization + alignment + forwardBackward + spectraConsensus +
               zeroWeightedContribution + other;
    }
};

inline std::string normalizedReason(std::string reason) {
    std::transform(reason.begin(), reason.end(), reason.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return reason;
}

inline RejectionCategory classifyRejectionReason(const std::string& reason) {
    const std::string normalized = normalizedReason(reason);
    if (normalized.empty() || normalized == "none" || normalized == "pending_fusion" ||
        normalized == "observer_only") {
        return RejectionCategory::NONE;
    }
    if (normalized.rfind("source_unavailable:", 0) == 0 ||
        normalized == "source_geometry_mismatch") {
        return RejectionCategory::CANONICALIZATION;
    }
    if (normalized == "hdr_forward_backward_inconsistent" ||
        normalized == "forward_backward_inconsistent") {
        return RejectionCategory::FORWARD_BACKWARD;
    }
    if (normalized == "spectra observer/consensus rejected support") {
        return RejectionCategory::SPECTRA_CONSENSUS;
    }
    if (normalized == "no_weighted_pixel_contribution") {
        return RejectionCategory::ZERO_WEIGHTED_CONTRIBUTION;
    }
    if (normalized == "no valid gpu alignment candidate" ||
        normalized == "shift exceeds maxshiftpixels" ||
        normalized == "gpu alignment response below threshold") {
        return RejectionCategory::ALIGNMENT;
    }
    return RejectionCategory::OTHER;
}

inline void record(RejectionBreakdown& breakdown, RejectionCategory category) noexcept {
    switch (category) {
        case RejectionCategory::CANONICALIZATION:
            ++breakdown.canonicalization;
            break;
        case RejectionCategory::ALIGNMENT:
            ++breakdown.alignment;
            break;
        case RejectionCategory::FORWARD_BACKWARD:
            ++breakdown.forwardBackward;
            break;
        case RejectionCategory::SPECTRA_CONSENSUS:
            ++breakdown.spectraConsensus;
            break;
        case RejectionCategory::ZERO_WEIGHTED_CONTRIBUTION:
            ++breakdown.zeroWeightedContribution;
            break;
        case RejectionCategory::OTHER:
            ++breakdown.other;
            break;
        case RejectionCategory::NONE:
            break;
    }
}

} // namespace bncam::raw_support_telemetry
