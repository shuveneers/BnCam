#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>
#include "SpectraPixelBackend.h"

using namespace bncam::spectra2;

int main() {
    constexpr int width = 19;
    constexpr int height = 11;
    std::vector<float> rgb(static_cast<std::size_t>(width * height * 3));
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const std::size_t i = static_cast<std::size_t>((y * width + x) * 3);
            rgb[i] = 0.01f * static_cast<float>(x + 1);
            rgb[i + 1] = 0.015f * static_cast<float>(y + 1);
            rgb[i + 2] = 0.005f * static_cast<float>(x + y + 1);
        }
    }
    RgbFloatFrameView view{rgb.data(), width, height, static_cast<std::size_t>(width * 3)};
    PixelKernelSelection selection{};
    selection.selected = PixelKernelBackendKind::TiledCpu;
    selection.tileSize = 8;
    OpponentTileBuffer tile{};
    OpponentTileBuildTelemetry telemetry{};
    buildOpponentTile(view, 0, 0, 8, 7, 2, selection, tile, telemetry);
    assert(tile.width == 12 && tile.height == 11);
    assert(telemetry.scalarPixelCount == static_cast<std::uint64_t>(tile.width * tile.height));
    assert(telemetry.vectorizedPixelCount == 0);
    for (int ly = 0; ly < tile.height; ++ly) {
        for (int lx = 0; lx < tile.width; ++lx) {
            const int gx = std::clamp(lx - 2, 0, width - 1);
            const int gy = std::clamp(ly - 2, 0, height - 1);
            const std::size_t src = static_cast<std::size_t>((gy * width + gx) * 3);
            float yv, rg, bg, sat;
            opponentScalar(rgb[src], rgb[src + 1], rgb[src + 2], yv, rg, bg, sat);
            const std::size_t dst = tile.index(lx, ly);
            assert(std::abs(tile.luma[dst] - yv) < 1e-7f);
            assert(std::abs(tile.rg[dst] - rg) < 1e-7f);
            assert(std::abs(tile.bg[dst] - bg) < 1e-7f);
            assert(std::abs(tile.saturation[dst] - sat) < 1e-7f);
        }
    }
    rgb[0] = std::numeric_limits<float>::quiet_NaN();
    buildOpponentTile(view, 0, 0, 4, 4, 2, selection, tile, telemetry);
    assert(telemetry.rejectedNonFinitePixelCount > 0);
    assert(std::isnan(tile.luma[tile.index(0, 0)]));

    const auto small = selectPixelKernelBackend(64, 64, true);
    assert(small.selected == PixelKernelBackendKind::ScalarReference);
    const auto large = selectPixelKernelBackend(4000, 3000, false);
    assert(large.selected == PixelKernelBackendKind::TiledCpu);
    assert(large.fallbackReason == "UPSTREAM_SIMD_BACKEND_NOT_QUALIFIED");
    std::cout << "SPECTRA_PIXEL_BACKEND_TESTS_OK\n";
}
