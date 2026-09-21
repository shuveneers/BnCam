#include "vulkan/RawPreviewComputeSlotLifecycle.h"

#include <array>
#include <cassert>

using namespace bncam::vulkan;

int main() {
    std::array<RawPreviewComputeSlotLifecycle, 3> slots{};
    for (int i = 0; i < 3; ++i) {
        assert(slots[i].begin(7, 100 + i));
        assert(slots[i].submit());
    }
    assert(!slots[0].begin(7, 103)); // All compute slots occupied; no wait or replacement.
    assert(slots[0].complete()); // Frame 100 completes first.
    assert(slots[0].phase() == RawPreviewComputeSlotPhase::GPU_COMPLETE);
    assert(!slots[0].complete());
    assert(slots[0].recycle());
    assert(slots[0].begin(8, 200)); // Generation changes only after retirement.
    slots[0].cancelRecording();
    assert(slots[0].phase() == RawPreviewComputeSlotPhase::FREE);
    assert(!slots[1].submittedFor(8, 101));
    assert(slots[2].submittedFor(7, 102));
    assert(slots[2].complete()); // Completion need not follow CPU submission order.
    assert(slots[2].recycle());
    assert(!slots[2].recycle()); // No double retirement.
    assert(slots[1].complete());
    assert(slots[1].recycle());
    return 0;
}
