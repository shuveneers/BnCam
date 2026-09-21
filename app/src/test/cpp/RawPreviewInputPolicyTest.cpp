#include "vulkan/RawPreviewInputPolicy.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <limits>

using namespace bncam::vulkan;

int main() {
    assert(rawPreviewBlobContract(0x21u, 4096u, 1u, 1u, 1ull << 24u, 4096u));
    assert(!rawPreviewBlobContract(0x21u, 4096u, 2u, 1u, 1ull << 24u, 4096u));
    assert(!rawPreviewBlobContract(0x21u, 4096u, 1u, 1u, 0, 4096u));
    assert(!rawPreviewBlobContract(0x21u, 100u, 1u, 1u, 1ull << 24u, 4096u));
    assert(!rawPreviewBlobContract(0x23u, 4096u, 1u, 1u, 1ull << 24u, 4096u));

    for (const auto format : {32u, 37u}) {
        const std::uint32_t visible = format == 32u ? 8u : 5u;
        const std::uint32_t pixel = format == 32u ? 2u : 0u;
        const auto layout = rawPreviewInputLayout(format, 4u, 3u + (format == 37u),
                visible + 3u, pixel, visible + 5u, pixel);
        assert(layout.valid());
        std::array<std::uint8_t, 64> source{};
        std::array<std::uint8_t, 64> destination{};
        std::fill(source.begin(), source.end(), 0xaau);
        std::fill(destination.begin(), destination.end(), 0xccu);
        for (std::uint32_t row = 0; row < 3u + (format == 37u); ++row) {
            std::fill_n(source.begin() + row * (visible + 3u), visible,
                        static_cast<std::uint8_t>(row + 1u));
        }
        assert(copyRawPreviewRows(destination.data(), destination.size(), source.data(),
                static_cast<std::size_t>(layout.sourceSpan), 3u + (format == 37u),
                visible + 3u, visible + 5u, layout));
        for (std::uint32_t row = 0; row < 3u + (format == 37u); ++row) {
            for (std::uint32_t col = 0; col < visible; ++col)
                assert(destination[row * (visible + 5u) + col] == row + 1u);
            for (std::uint32_t col = visible; col < visible + 5u; ++col)
                assert(destination[row * (visible + 5u) + col] == 0u);
        }
        assert(!copyRawPreviewRows(destination.data(), layout.destinationBytes - 1u,
                source.data(), source.size(), 3u + (format == 37u),
                visible + 3u, visible + 5u, layout));
        assert(!copyRawPreviewRows(destination.data(), destination.size(), source.data(),
                layout.sourceSpan - 1u, 3u + (format == 37u),
                visible + 3u, visible + 5u, layout));
    }
    assert(!rawPreviewInputLayout(37u, 5u, 2u, 10u, 0u, 10u, 0u).valid());
    assert(!rawPreviewInputLayout(37u, 4u, 2u, 4u, 0u, 5u, 0u).valid());
    assert(!rawPreviewInputLayout(32u, 4u, 2u, 8u, 4u, 8u, 2u).valid());
    assert(!rawPreviewInputLayout(32u, 4u, 2u, 8u, 2u, 8u, 4u).valid());
    assert(!rawPreviewInputLayout(32u, 4u, std::numeric_limits<std::uint32_t>::max(),
            8u, 2u, 8u, 2u).valid());
    return 0;
}
