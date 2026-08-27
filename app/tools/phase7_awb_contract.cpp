#include "../src/main/cpp/PhysicalAwbEstimator.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <random>
#include <string>
#include <vector>

using bncam::awb::LinearOpponentSample;

namespace {
constexpr double wr = 0.2126, wg = 0.7152, wb = 0.0722;
LinearOpponentSample sampleFromRgb(double r, double g, double b, int tile, double structure = 0.002) {
    return {wr*r + wg*g + wb*b, r-g, b-g, structure, tile};
}
std::vector<LinearOpponentSample> scene(double r, double g, double b, int count = 10000, double jitter = 0.02) {
    std::vector<LinearOpponentSample> out;
    out.reserve(static_cast<size_t>(count));
    std::mt19937 rng(7);
    std::normal_distribution<double> n(0.0, jitter);
    for (int i=0;i<count;++i) {
        const double s = std::clamp(0.55 + n(rng), 0.08, 0.80);
        out.push_back(sampleFromRgb(std::max(0.001,r*s), std::max(0.001,g*s),
                                    std::max(0.001,b*s), i % 192));
    }
    return out;
}
void require(bool ok, const std::string& label, int& pass) {
    if (!ok) { std::cerr << "FAIL " << label << "\n"; std::exit(2); }
    ++pass;
}
}

int main() {
    int pass = 0;
    {
        auto s = scene(1.0, 0.72, 0.48);
        auto e = bncam::awb::resolve(s, {0.75,1.0,1.55}, 0.004);
        require(e.dataReady, "warm_ready", pass);
        require(e.finalGainsRgb[0] < 1.0 && e.finalGainsRgb[2] > 1.0, "warm_direction", pass);
        require(e.finalGainsRgb[1] == 1.0, "green_normalized", pass);
    }
    {
        auto s = scene(0.48, 0.72, 1.0);
        auto e = bncam::awb::resolve(s, {1.55,1.0,0.75}, 0.004);
        require(e.dataReady, "cool_ready", pass);
        require(e.finalGainsRgb[0] > 1.0 && e.finalGainsRgb[2] < 1.0, "cool_direction", pass);
    }
    {
        auto s = scene(0.72,0.72,0.72,8000);
        for (int i=0;i<3000;++i) s.push_back(sampleFromRgb(0.99,0.99,0.99,i%192));
        auto e = bncam::awb::resolve(s, {1.0,1.0,1.0}, 0.004);
        require(e.highlightRejectedFraction > 0.15, "highlight_exclusion", pass);
        require(std::abs(e.finalGainsRgb[0]-1.0) < 0.06 && std::abs(e.finalGainsRgb[2]-1.0)<0.06,
                "highlight_no_bias", pass);
    }
    {
        auto s = scene(0.72,0.72,0.72,6000);
        for (int i=0;i<6000;++i) s.push_back(sampleFromRgb(0.006,0.006,0.006,i%192));
        auto e = bncam::awb::resolve(s, {1.0,1.0,1.0}, 0.006);
        require(e.darkRejectedFraction > 0.35, "dark_exclusion", pass);
        require(e.dataReady, "dark_scene_still_supported", pass);
    }
    {
        std::vector<LinearOpponentSample> s;
        for (int i=0;i<9000;++i) {
            if ((i&1)==0) s.push_back(sampleFromRgb(0.75,0.06,0.05,i%192));
            else s.push_back(sampleFromRgb(0.04,0.08,0.78,i%192));
        }
        auto e = bncam::awb::resolve(s, {1.12,1.0,0.93}, 0.004);
        require(e.neutralSupport < 0.20, "saturated_neutral_support_low", pass);
        require(e.dataAuthority < 0.12, "saturated_prior_dominates", pass);
    }
    {
        std::vector<LinearOpponentSample> s;
        auto a = scene(1.0,0.72,0.48,6000,0.01);
        auto b = scene(0.48,0.72,1.0,6000,0.01);
        // Spatially separate the illuminants so tile dispersion remains visible.
        for (size_t i=0;i<a.size();++i) { a[i].tileIndex = static_cast<int>(i%96); s.push_back(a[i]); }
        for (size_t i=0;i<b.size();++i) { b[i].tileIndex = 96 + static_cast<int>(i%96); s.push_back(b[i]); }
        auto e = bncam::awb::resolve(s, {1.05,1.0,1.05}, 0.004);
        require(e.mixedIllumination, "mixed_detected", pass);
        require(e.dataAuthority <= 0.48 + 1e-9, "mixed_authority_bounded", pass);
    }
    {
        auto s = scene(1.0,0.72,0.48,12000,0.01);
        auto e = bncam::awb::resolve(s, {1.40,1.0,0.65}, 0.004);
        require(e.priorDisagreement > 0.20, "wrong_prior_detected", pass);
        require(e.dataAuthority > 0.05, "data_can_correct_prior", pass);
        require(e.finalGainsRgb[0] < e.priorGainsRgb[0], "prior_red_corrected", pass);
        require(e.finalGainsRgb[2] > e.priorGainsRgb[2], "prior_blue_corrected", pass);
    }
    {
        auto e = bncam::awb::resolve({}, {1.25,1.0,0.80}, 0.004);
        require(!e.dataReady, "empty_fallback", pass);
        require(std::abs(e.finalGainsRgb[0]-1.25)<1e-9 && std::abs(e.finalGainsRgb[2]-0.80)<1e-9,
                "empty_uses_prior", pass);
    }
    {
        auto s = scene(0.8,0.8,0.8,10000);
        auto e = bncam::awb::resolve(s, {NAN,0.0,INFINITY}, 0.004);
        require(!e.priorValid, "invalid_prior_flag", pass);
        require(std::isfinite(e.finalGainsRgb[0]) && std::isfinite(e.finalGainsRgb[2]),
                "invalid_prior_finite", pass);
        require(e.finalGainsRgb[0] >= 0.35 && e.finalGainsRgb[0] <= 3.5 &&
                e.finalGainsRgb[2] >= 0.35 && e.finalGainsRgb[2] <= 3.5,
                "gains_bounded", pass);
    }
    {
        std::vector<float> packed(24u, 0.0f);
        packed[3] = 0.003f; packed[4] = 17.0f; packed[5] = 1.42f; packed[6] = 0.08f; packed[7] = -0.04f;
        packed[8 + 5] = 0.0f; // invalid record
        packed[16 + 3] = 0.004f; packed[16 + 4] = 191.0f; packed[16 + 5] = 1.55f;
        packed[16 + 6] = -0.02f; packed[16 + 7] = 0.05f;
        const auto decoded = bncam::awb::decodeGpuResidualCandidates(packed);
        require(decoded.size() == 2u, "gpu_compact_decoder_validity", pass);
        require(std::abs(decoded[0].luma - 0.42) < 1.0e-5 && decoded[0].tileIndex == 17 &&
                decoded[1].tileIndex == 191, "gpu_compact_decoder_fields", pass);
    }
    std::cout << "PHASE7_AWB_CONTRACT_PASS=" << pass << "\n";
    return 0;
}
