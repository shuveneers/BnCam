#include "../../main/cpp/SrgbByteLut.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {
std::uint32_t pack4(std::uint8_t b0, std::uint8_t b1, std::uint8_t b2, std::uint8_t b3) {
    return static_cast<std::uint32_t>(b0) |
           (static_cast<std::uint32_t>(b1) << 8u) |
           (static_cast<std::uint32_t>(b2) << 16u) |
           (static_cast<std::uint32_t>(b3) << 24u);
}
void require(bool condition, const char* message) {
    if (!condition) { std::cerr << "FAIL " << message << '\n'; std::exit(1); }
}
} // namespace

int main() {
    constexpr std::uint64_t width = 4096u;
    constexpr std::uint64_t height = 3072u;
    constexpr std::uint64_t floatBytes = width * height * 3u * sizeof(float);
    constexpr std::uint64_t bgrBytes = width * height * 3u;
    constexpr std::uint64_t reductionPct = 100u - 100u * bgrBytes / floatBytes;
    constexpr std::uint64_t strips = (height + 512u - 1u) / 512u;
    const auto& lut = bncam::color::srgbByteLut();
    require(lut.size() == 4097u, "LUT size");
    require(lut.front() == 0u && lut.back() == 255u, "LUT endpoints");
    for (std::size_t i = 1; i < lut.size(); ++i) require(lut[i] >= lut[i - 1], "LUT monotonicity");
    require(bncam::color::packedBgr8RowStride(width) == width * 3u, "4096 packed stride");
    require(bncam::color::packedBgr8RowStride(1u) == 4u, "1px stride");
    require(bncam::color::packedBgr8RowStride(17u) == 52u, "17px stride");
    const std::array<std::uint8_t, 12> expected{{1,2,3,4,5,6,7,8,9,10,11,12}};
    const std::array<std::uint32_t,3> words{{pack4(1,2,3,4),pack4(5,6,7,8),pack4(9,10,11,12)}};
    std::array<std::uint8_t,12> actual{};
    for (std::size_t w=0; w<3; ++w) for (std::size_t b=0; b<4; ++b)
        actual[w*4+b]=static_cast<std::uint8_t>((words[w]>>(8u*b))&0xffu);
    require(actual == expected, "BGR word packing order");
    require(floatBytes == 150994944u, "float bytes");
    require(bgrBytes == 37748736u, "BGR bytes");
    require(reductionPct == 75u, "reduction");
    require(strips == 6u, "strip count");
    std::cout << "PASS lut=" << lut.size() << " floatBytes=" << floatBytes
              << " bgrBytes=" << bgrBytes << " reduction=" << reductionPct
              << "% strips=" << strips << '\n';
}
