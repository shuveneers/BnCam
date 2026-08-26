#include "../../main/cpp/RawSupportRejectionTelemetry.h"

#include <cassert>

using bncam::raw_support_telemetry::RejectionBreakdown;
using bncam::raw_support_telemetry::RejectionCategory;
using bncam::raw_support_telemetry::classifyRejectionReason;
using bncam::raw_support_telemetry::record;

int main() {
    assert(classifyRejectionReason("source_unavailable:RAW_MULTIFRAME_AHB_NULL") == RejectionCategory::CANONICALIZATION);
    assert(classifyRejectionReason("source_geometry_mismatch") == RejectionCategory::CANONICALIZATION);
    assert(classifyRejectionReason("GPU alignment response below threshold") == RejectionCategory::ALIGNMENT);
    assert(classifyRejectionReason("shift exceeds maxShiftPixels") == RejectionCategory::ALIGNMENT);
    assert(classifyRejectionReason("hdr_forward_backward_inconsistent") == RejectionCategory::FORWARD_BACKWARD);
    assert(classifyRejectionReason("forward_backward_inconsistent") == RejectionCategory::FORWARD_BACKWARD);
    assert(classifyRejectionReason("SPECTRA observer/consensus rejected support") == RejectionCategory::SPECTRA_CONSENSUS);
    assert(classifyRejectionReason("no_weighted_pixel_contribution") == RejectionCategory::ZERO_WEIGHTED_CONTRIBUTION);
    assert(classifyRejectionReason("none") == RejectionCategory::NONE);
    assert(classifyRejectionReason("pending_fusion") == RejectionCategory::NONE);
    assert(classifyRejectionReason("unexpected_backend_reason") == RejectionCategory::OTHER);

    RejectionBreakdown breakdown{};
    record(breakdown, RejectionCategory::CANONICALIZATION);
    record(breakdown, RejectionCategory::ALIGNMENT);
    record(breakdown, RejectionCategory::FORWARD_BACKWARD);
    record(breakdown, RejectionCategory::SPECTRA_CONSENSUS);
    record(breakdown, RejectionCategory::ZERO_WEIGHTED_CONTRIBUTION);
    record(breakdown, RejectionCategory::OTHER);
    record(breakdown, RejectionCategory::NONE);
    assert(breakdown.total() == 6);
    return 0;
}
