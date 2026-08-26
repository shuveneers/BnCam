#include "SpectraVulkanOpponentStripPlan.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

int main() {
    using namespace bncam::spectra2;

    constexpr std::uint64_t kBudget = 320ull * 1024ull * 1024ull;
    const VulkanOpponentStripPlan plan = buildVulkanOpponentStripPlan(
            4000,
            3000,
            256,
            2,
            kBudget
    );
    assert(plan.valid);
    assert(plan.status == "M8F_STRIPED_OPPONENT_PLAN_READY");
    assert(plan.targetOutputRows == 256);
    assert(plan.haloRows == 2);
    assert(plan.strips.size() == 12u);
    assert(plan.strips.front().outputStartY == 0);
    assert(plan.strips.front().topHaloRows == 0);
    assert(plan.strips.front().bottomHaloRows == 2);
    assert(plan.strips[1].topHaloRows == 2);
    assert(plan.strips[1].bottomHaloRows == 2);
    assert(plan.strips.back().outputStartY == 2816);
    assert(plan.strips.back().outputRowCount == 184);
    assert(plan.strips.back().bottomHaloRows == 0);
    assert(plan.maximumInputRows == 260);
    assert(plan.estimatedPeakTransientBytes <= kBudget);
    const std::uint64_t expectedPeak = plan.fullFrameOutputBytes +
            plan.maximumStripInputBytes + 2u * plan.maximumStripOutputBytes;
    assert(plan.estimatedPeakTransientBytes == expectedPeak);

    const std::uint64_t oldFullFrameTransient =
            static_cast<std::uint64_t>(4000) * 3000u * 3u * 4u * sizeof(float);
    assert(plan.estimatedPeakTransientBytes < oldFullFrameTransient);

    const VulkanOpponentStrip copyStrip{2, 2, 1, 4, 1, 1};
    std::vector<float> stripRgba(4u * 4u * 4u, -1.0f);
    for (int row = 0; row < 4; ++row) {
        for (int x = 0; x < 4; ++x) {
            const std::size_t index = (static_cast<std::size_t>(row) * 4u + x) * 4u;
            stripRgba[index] = static_cast<float>(row * 10 + x);
            stripRgba[index + 1u] = static_cast<float>(row * 10 + x + 100);
            stripRgba[index + 2u] = static_cast<float>(row * 10 + x + 200);
            stripRgba[index + 3u] = 1.0f;
        }
    }
    std::vector<float> fullRgba(6u * 4u * 4u, 0.0f);
    assert(copyVulkanOpponentStripInterior(copyStrip, 4, stripRgba, fullRgba));
    const std::size_t copiedFirst = (2u * 4u) * 4u;
    const std::size_t copiedSecond = (3u * 4u) * 4u;
    assert(fullRgba[copiedFirst] == 10.0f);
    assert(fullRgba[copiedFirst + 1u] == 110.0f);
    assert(fullRgba[copiedSecond] == 20.0f);
    assert(fullRgba[copiedSecond + 2u] == 220.0f);

    const VulkanOpponentStripPlan invalid = buildVulkanOpponentStripPlan(
            12000,
            12000,
            256,
            2,
            64ull * 1024ull * 1024ull
    );
    assert(!invalid.valid);
    assert(invalid.status == "FULL_OUTPUT_FRAME_EXCEEDS_TRANSIENT_BUDGET");

    std::cout << "SPECTRA_VULKAN_OPPONENT_STRIP_PLAN_TESTS_OK\n";
}
