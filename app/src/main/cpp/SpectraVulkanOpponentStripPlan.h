#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace bncam::spectra2 {

struct VulkanOpponentStrip {
    int outputStartY = 0;
    int outputRowCount = 0;
    int inputStartY = 0;
    int inputRowCount = 0;
    int topHaloRows = 0;
    int bottomHaloRows = 0;
};

struct VulkanOpponentStripPlan {
    bool valid = false;
    int width = 0;
    int height = 0;
    int haloRows = 0;
    int targetOutputRows = 0;
    int maximumInputRows = 0;
    std::uint64_t fullFrameOutputBytes = 0;
    std::uint64_t maximumStripInputBytes = 0;
    std::uint64_t maximumStripOutputBytes = 0;
    std::uint64_t estimatedPeakTransientBytes = 0;
    std::uint64_t maximumAllowedTransientBytes = 0;
    std::vector<VulkanOpponentStrip> strips;
    std::string status = "UNAVAILABLE";
};

inline VulkanOpponentStripPlan buildVulkanOpponentStripPlan(
        int width,
        int height,
        int requestedOutputRows = 256,
        int haloRows = 2,
        std::uint64_t maximumAllowedTransientBytes = 320ull * 1024ull * 1024ull
) {
    VulkanOpponentStripPlan plan{};
    plan.width = width;
    plan.height = height;
    plan.haloRows = std::max(0, haloRows);
    plan.maximumAllowedTransientBytes = maximumAllowedTransientBytes;
    if (width <= 0 || height <= 0 || requestedOutputRows <= 0) {
        plan.status = "INVALID_FRAME_OR_STRIP_DIMENSIONS";
        return plan;
    }

    constexpr std::uint64_t kRgbaBytesPerPixel = 4u * sizeof(float);
    const std::uint64_t pixelWidth = static_cast<std::uint64_t>(width);
    const std::uint64_t pixelHeight = static_cast<std::uint64_t>(height);
    if (pixelWidth > std::numeric_limits<std::uint64_t>::max() / pixelHeight ||
        pixelWidth * pixelHeight > std::numeric_limits<std::uint64_t>::max() /
                kRgbaBytesPerPixel) {
        plan.status = "FRAME_BYTE_COUNT_OVERFLOW";
        return plan;
    }
    plan.fullFrameOutputBytes = pixelWidth * pixelHeight * kRgbaBytesPerPixel;
    if (plan.fullFrameOutputBytes >= maximumAllowedTransientBytes) {
        plan.status = "FULL_OUTPUT_FRAME_EXCEEDS_TRANSIENT_BUDGET";
        return plan;
    }

    const std::uint64_t bytesPerInputRow = pixelWidth * kRgbaBytesPerPixel;
    // Peak memory contains three strip-sized allocations: persistent mapped input,
    // persistent mapped GPU output, and the per-dispatch CPU readback vector.
    constexpr std::uint64_t kStripSizedAllocationCount = 3u;
    if (bytesPerInputRow > std::numeric_limits<std::uint64_t>::max() /
            kStripSizedAllocationCount) {
        plan.status = "STRIP_ROW_BYTE_COUNT_OVERFLOW";
        return plan;
    }
    const std::uint64_t bytesPerStripRowSet =
            bytesPerInputRow * kStripSizedAllocationCount;
    const std::uint64_t remainingBytes = maximumAllowedTransientBytes -
            plan.fullFrameOutputBytes;
    if (bytesPerStripRowSet == 0u || remainingBytes < bytesPerStripRowSet) {
        plan.status = "NO_TRANSIENT_BUDGET_FOR_STRIP_BUFFERS";
        return plan;
    }

    const std::uint64_t maximumRowsByBudget = remainingBytes / bytesPerStripRowSet;
    if (maximumRowsByBudget <= static_cast<std::uint64_t>(2 * plan.haloRows)) {
        plan.status = "TRANSIENT_BUDGET_TOO_SMALL_FOR_REQUIRED_HALO";
        return plan;
    }
    const int maximumOutputRowsByBudget = static_cast<int>(std::min<std::uint64_t>(
            static_cast<std::uint64_t>(std::numeric_limits<int>::max()),
            maximumRowsByBudget - static_cast<std::uint64_t>(2 * plan.haloRows)
    ));
    plan.targetOutputRows = std::max(1, std::min({
            requestedOutputRows,
            height,
            maximumOutputRowsByBudget
    }));

    for (int outputStart = 0; outputStart < height; outputStart += plan.targetOutputRows) {
        VulkanOpponentStrip strip{};
        strip.outputStartY = outputStart;
        strip.outputRowCount = std::min(plan.targetOutputRows, height - outputStart);
        strip.inputStartY = std::max(0, outputStart - plan.haloRows);
        const int outputEnd = outputStart + strip.outputRowCount;
        const int inputEnd = std::min(height, outputEnd + plan.haloRows);
        strip.inputRowCount = inputEnd - strip.inputStartY;
        strip.topHaloRows = outputStart - strip.inputStartY;
        strip.bottomHaloRows = inputEnd - outputEnd;
        plan.maximumInputRows = std::max(plan.maximumInputRows, strip.inputRowCount);
        plan.strips.push_back(strip);
    }

    if (plan.strips.empty()) {
        plan.status = "NO_STRIPS_GENERATED";
        return plan;
    }
    plan.maximumStripInputBytes = pixelWidth *
            static_cast<std::uint64_t>(plan.maximumInputRows) * kRgbaBytesPerPixel;
    plan.maximumStripOutputBytes = plan.maximumStripInputBytes;
    if (plan.maximumStripOutputBytes > std::numeric_limits<std::uint64_t>::max() /
            2u) {
        plan.status = "TRANSIENT_BYTE_COUNT_OVERFLOW";
        return plan;
    }
    const std::uint64_t stripOutputAndReadbackBytes =
            plan.maximumStripOutputBytes * 2u;
    if (plan.maximumStripInputBytes > std::numeric_limits<std::uint64_t>::max() -
            stripOutputAndReadbackBytes) {
        plan.status = "TRANSIENT_BYTE_COUNT_OVERFLOW";
        return plan;
    }
    const std::uint64_t allStripBytes = plan.maximumStripInputBytes +
            stripOutputAndReadbackBytes;
    if (plan.fullFrameOutputBytes > std::numeric_limits<std::uint64_t>::max() -
            allStripBytes) {
        plan.status = "TRANSIENT_BYTE_COUNT_OVERFLOW";
        return plan;
    }
    plan.estimatedPeakTransientBytes = plan.fullFrameOutputBytes + allStripBytes;
    if (plan.estimatedPeakTransientBytes > maximumAllowedTransientBytes) {
        plan.status = "STRIP_PLAN_EXCEEDS_TRANSIENT_BUDGET";
        return plan;
    }

    int expectedOutputStart = 0;
    for (const VulkanOpponentStrip& strip : plan.strips) {
        if (strip.outputStartY != expectedOutputStart || strip.outputRowCount <= 0 ||
            strip.inputRowCount < strip.outputRowCount || strip.topHaloRows < 0 ||
            strip.bottomHaloRows < 0 || strip.topHaloRows > plan.haloRows ||
            strip.bottomHaloRows > plan.haloRows) {
            plan.status = "NON_CONTIGUOUS_OR_INVALID_STRIP_PLAN";
            return plan;
        }
        expectedOutputStart += strip.outputRowCount;
    }
    if (expectedOutputStart != height) {
        plan.status = "STRIP_PLAN_DOES_NOT_COVER_FRAME";
        return plan;
    }

    plan.valid = true;
    plan.status = "M8F_STRIPED_OPPONENT_PLAN_READY";
    return plan;
}

inline bool copyVulkanOpponentStripInterior(
        const VulkanOpponentStrip& strip,
        int width,
        const std::vector<float>& stripRgba,
        std::vector<float>& fullFrameRgba
) {
    if (width <= 0 || strip.outputRowCount <= 0 || strip.inputRowCount <= 0 ||
        strip.topHaloRows < 0 ||
        strip.topHaloRows + strip.outputRowCount > strip.inputRowCount) {
        return false;
    }
    constexpr std::size_t kFloatsPerPixel = 4u;
    const std::size_t rowFloats = static_cast<std::size_t>(width) * kFloatsPerPixel;
    const std::size_t requiredStripFloats = static_cast<std::size_t>(strip.inputRowCount) *
            rowFloats;
    const std::size_t destinationOffset = static_cast<std::size_t>(strip.outputStartY) *
            rowFloats;
    const std::size_t copyFloats = static_cast<std::size_t>(strip.outputRowCount) * rowFloats;
    const std::size_t sourceOffset = static_cast<std::size_t>(strip.topHaloRows) * rowFloats;
    if (stripRgba.size() < requiredStripFloats ||
        sourceOffset > stripRgba.size() || copyFloats > stripRgba.size() - sourceOffset ||
        destinationOffset > fullFrameRgba.size() ||
        copyFloats > fullFrameRgba.size() - destinationOffset) {
        return false;
    }
    std::copy_n(
            stripRgba.begin() + static_cast<std::ptrdiff_t>(sourceOffset),
            static_cast<std::ptrdiff_t>(copyFloats),
            fullFrameRgba.begin() + static_cast<std::ptrdiff_t>(destinationOffset)
    );
    return true;
}

} // namespace bncam::spectra2
