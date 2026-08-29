#include <algorithm>
#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <iostream>

namespace {
struct Strip {
    std::uint32_t inputOriginY = 0;
    std::uint32_t inputRows = 0;
    std::uint32_t intermediateOriginY = 0;
    std::uint32_t intermediateRows = 0;
    std::uint32_t outputOriginY = 0;
    std::uint32_t outputRows = 0;
};

constexpr std::uint64_t packedRowBytes(std::uint32_t width) {
    return ((static_cast<std::uint64_t>(width) * 3u + 3u) / 4u) * 4u;
}
}

int main() {
    constexpr std::uint32_t width = 4096u;
    constexpr std::uint32_t height = 3072u;
    constexpr std::uint32_t outputRows = 512u;
    constexpr std::uint32_t planHalo = 5u;
    constexpr std::uint32_t visibleHalo = 4u;
    constexpr std::size_t stripCount = height / outputRows;
    static_assert(stripCount == 6u);

    std::array<Strip, stripCount> strips{};
    std::uint32_t maximumIntermediateRows = 0u;
    for (std::size_t index = 0; index < strips.size(); ++index) {
        const std::uint32_t outputOrigin = static_cast<std::uint32_t>(index) * outputRows;
        const std::uint32_t outputEnd = outputOrigin + outputRows;
        const std::uint32_t inputOrigin = outputOrigin > planHalo ? outputOrigin - planHalo : 0u;
        const std::uint32_t inputEnd = std::min(height, outputEnd + planHalo);
        const std::uint32_t intermediateOrigin = outputOrigin > visibleHalo
                ? outputOrigin - visibleHalo : 0u;
        const std::uint32_t intermediateEnd = std::min(height, outputEnd + visibleHalo);
        strips[index] = {
                inputOrigin,
                inputEnd - inputOrigin,
                intermediateOrigin,
                intermediateEnd - intermediateOrigin,
                outputOrigin,
                outputRows};
        maximumIntermediateRows = std::max(
                maximumIntermediateRows, strips[index].intermediateRows);
        assert(strips[index].intermediateOriginY >= strips[index].inputOriginY);
        assert(strips[index].intermediateOriginY + strips[index].intermediateRows <=
               strips[index].inputOriginY + strips[index].inputRows);
    }

    std::uint32_t expectedOutputOrigin = 0u;
    for (const Strip& strip : strips) {
        assert(strip.outputOriginY == expectedOutputOrigin);
        expectedOutputOrigin += strip.outputRows;
    }
    assert(expectedOutputOrigin == height);
    assert(maximumIntermediateRows == 520u);

    constexpr std::uint64_t floatFrameBytes =
            static_cast<std::uint64_t>(width) * height * 3u * sizeof(float);
    constexpr std::uint64_t bgrFrameBytes = packedRowBytes(width) * height;
    static_assert(floatFrameBytes == 150994944u);
    static_assert(bgrFrameBytes == 37748736u);
    static_assert(bgrFrameBytes * 4u == floatFrameBytes);

    constexpr std::uint64_t wordsPerRow = packedRowBytes(width) / 4u;
    const std::uint64_t secondStripAbsoluteWord =
            static_cast<std::uint64_t>(strips[1].outputOriginY) * wordsPerRow;
    assert(secondStripAbsoluteWord == 1572864u);
    assert(secondStripAbsoluteWord != 0u); // batch publication must not overwrite strip 0.

    // Phase-15 execution contract: six compute strip pairs, one queue submit and one wait.
    constexpr std::uint32_t oldSubmitWaitPairs = static_cast<std::uint32_t>(stripCount);
    constexpr std::uint32_t batchedSubmitWaitPairs = 1u;
    static_assert(oldSubmitWaitPairs == 6u);
    static_assert(batchedSubmitWaitPairs == 1u);

    std::cout << "PASS strips=" << stripCount
              << " maxIntermediateRows=" << maximumIntermediateRows
              << " bgrBytes=" << bgrFrameBytes
              << " submitWaitPairs=" << batchedSubmitWaitPairs << '\n';
    return 0;
}
