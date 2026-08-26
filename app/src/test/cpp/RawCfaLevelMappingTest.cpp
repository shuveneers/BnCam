#include "../../main/cpp/RawCfaLevelMapping.h"

#include <array>
#include <cassert>

int main() {
    constexpr std::array<float, 4> canonical{{10.0f, 20.0f, 30.0f, 40.0f}};
    const std::array<std::array<float, 4>, 4> expected{{
            {{10.0f, 20.0f, 30.0f, 40.0f}},
            {{20.0f, 10.0f, 40.0f, 30.0f}},
            {{30.0f, 40.0f, 10.0f, 20.0f}},
            {{40.0f, 30.0f, 20.0f, 10.0f}}
    }};
    for (int cfa = 0; cfa < 4; ++cfa) {
        const auto mosaic = bncam::raw::canonicalLevelsToMosaic(canonical, cfa);
        assert(mosaic == expected[static_cast<std::size_t>(cfa)]);
        const auto roundTrip = bncam::raw::mosaicLevelsToCanonical(mosaic, cfa);
        assert(roundTrip == canonical);
    }
    const auto fallback = bncam::raw::canonicalLevelsToMosaic(canonical, 99);
    assert(fallback == expected[0]);
    return 0;
}
