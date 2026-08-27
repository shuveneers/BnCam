#include "../src/main/cpp/HighlightGamutProtectionV2.h"

#include <array>
#include <cmath>
#include <iostream>

using bncam::highlight::Rgb;

namespace {
int passed = 0;
int failed = 0;
void check(bool condition, const char* name) {
    if (condition) {
        ++passed;
    } else {
        ++failed;
        std::cerr << "FAIL: " << name << '\n';
    }
}

std::array<Rgb, 25> flatNeighborhood(Rgb value) {
    std::array<Rgb, 25> n{};
    n.fill(value);
    return n;
}
}

int main() {
    using namespace bncam::highlight;

    check(!classifySensorClip({0.8f, 0.7f, 0.6f}).any, "unclipped_sensor_not_classified");
    check(classifySensorClip({1.0f, 0.7f, 0.6f}).clippedChannels == 1, "single_sensor_clip_classified");
    check(classifySensorClip({1.0f, 1.0f, 1.0f}).all, "all_sensor_clip_classified");
    check(wbAboveUnityWithoutSensorClip({0.7f, 0.7f, 0.7f}, {1.4f, 0.7f, 0.7f}),
          "wb_over_unity_distinguished_from_sensor_clip");

    auto warm = flatNeighborhood({0.78f, 0.58f, 0.42f});
    warm[12] = {1.0f, 0.80f, 0.58f};
    const auto recovered = reconstructSensorClipped(warm[12], warm);
    check(recovered.applied, "sensor_clip_reconstructed_with_consistent_support");
    check(recovered.rgb.r > 1.0f && std::abs(recovered.rgb.g - 0.80f) < 1.0e-6f &&
          std::abs(recovered.rgb.b - 0.58f) < 1.0e-6f,
          "only_clipped_channel_reconstructed_and_unclipped_channels_preserved");

    auto allClip = flatNeighborhood({0.8f, 0.8f, 0.8f});
    const auto irrecoverable = reconstructSensorClipped({1.0f, 1.0f, 1.0f}, allClip);
    check(!irrecoverable.applied, "fully_clipped_pixel_not_given_invented_hue");

    auto edge = flatNeighborhood({0.8f, 0.2f, 0.1f});
    for (std::size_t i = 0; i < edge.size(); i += 2u) edge[i] = {0.1f, 0.8f, 0.2f};
    edge[12] = {1.0f, 0.5f, 0.3f};
    const auto edgeRecovery = reconstructSensorClipped(edge[12], edge);
    check(edgeRecovery.supportConfidence < recovered.supportConfidence,
          "mixed_hue_edge_reduces_reconstruction_authority");

    const Rgb signedCcm{2.0f, -0.02f, 0.50f};
    const float beforeY = luma(signedCcm);
    const auto protectedCcm = protectSignedCcmLowerGamut(signedCcm);
    check(protectedCcm.excursion && protectedCcm.applied, "negative_ccm_excursion_protected");
    check(std::min({protectedCcm.rgb.r, protectedCcm.rgb.g, protectedCcm.rgb.b}) >= -2.0e-6f,
          "protected_ccm_is_nonnegative");
    check(std::abs(luma(protectedCcm.rgb) - beforeY) < 2.0e-5f,
          "gamut_compression_preserves_linear_luma");
    check(protectedCcm.rgb.r > 1.0f,
          "pre_tone_scene_linear_headroom_above_one_is_not_forced_into_unit_gamut");

    const Rgb hdrInGamut{1.6f, 1.2f, 0.8f};
    const auto hdrProtected = protectSignedCcmLowerGamut(hdrInGamut);
    check(!hdrProtected.applied && std::abs(hdrProtected.rgb.r - 1.6f) < 1.0e-6f,
          "positive_hdr_headroom_is_bit_preserved");

    std::cout << "PHASE9_HIGHLIGHT_GAMUT_CONTRACT_PASS=" << passed << '\n';
    if (failed != 0) std::cout << "PHASE9_HIGHLIGHT_GAMUT_CONTRACT_FAIL=" << failed << '\n';
    return failed == 0 ? 0 : 1;
}
