#include "SingleFrameRawReplayValidation.h"
#include "Demosaic.h"
#include "RawDomain.h"
#include <algorithm>
#include <cmath>
#include <future>
#include <sstream>
#include <vector>

std::string validateSingleFrameRawReplay() {
    bool normalization = true, parity = true, deterministic = true, ownership = true;
    bool samples = true, finite = true, hybrid = true, golden = true;
    double maxDifference = 0.0;
    int cases = 0;
    const cv::Size sizes[] = {{1, 1}, {2, 3}, {8, 6}, {7, 9}, {17, 15}};
    for (int cfa = 0; cfa < 4; ++cfa) {
        for (int origin = 0; origin < 4; ++origin) {
            for (const auto size : sizes) {
                RawDomainInfo info;
                info.width = size.width;
                info.height = size.height;
                // Padded RAW16 rows, including odd dimensions.
                const int rowPixels = size.width + 3;
                info.masterRowStrideBytes = size_t(rowPixels) * sizeof(uint16_t);
                info.sensorCfaPattern = cfa;
                info.cfaOffsetX = origin & 1;
                info.cfaOffsetY = origin >> 1;
                info.effectiveWhiteLevelInMasterUnits = 1023.0f;
                info.effectiveBlackLevelPatternInMasterUnits = {32.0f, 64.0f, 96.0f, 128.0f};
                std::vector<uint16_t> raw(size_t(rowPixels) * size.height, 65535);
                for (int y = 0; y < size.height; ++y) {
                    for (int x = 0; x < size.width; ++x) {
                        raw[size_t(y) * rowPixels + x] = uint16_t((x * 137 + y * 71) % 1150);
                    }
                }
                const auto linear = normalizeRawForJpeg(raw.data(), info);
                if (!linear.diagnostics.valid || linear.mosaic.empty()) {
                    normalization = false;
                    continue;
                }
                const int effective = effectiveCfaPatternAtOrigin(cfa, origin & 1, origin >> 1);
                for (int y = 0; y < size.height; ++y) {
                    for (int x = 0; x < size.width; ++x) {
                        const int channel = (((y + info.cfaOffsetY) & 1) << 1) | ((x + info.cfaOffsetX) & 1);
                        const float black = info.effectiveBlackLevelPatternInMasterUnits[channel];
                        const float expected = std::clamp((raw[size_t(y) * rowPixels + x] - black) / (1023.0f - black), 0.0f, 1.0f);
                        normalization &= std::abs(linear.mosaic.at<float>(y, x) - expected) < 1.e-6f;
                        parity &= bncam::raw::bayerColorAt(effective, x, y) ==
                            bncam::raw::bayerColorAt(cfa, x + info.cfaOffsetX, y + info.cfaOffsetY);
                    }
                }
                const auto malvar = demosaicMalvar2004ToRgb32f(linear.mosaic, effective);
                const auto amaze = demosaicAmazeInspiredToRgb32f(linear.mosaic, effective);
                const auto savedMalvar = malvar.clone(), savedAmaze = amaze.clone();
                // A different capture with the same allocation geometry must not mutate either result.
                cv::Mat other(size, CV_32FC1, cv::Scalar(0.0f));
                demosaicMalvar2004ToRgb32f(other, effective);
                demosaicAmazeInspiredToRgb32f(other, effective);
                ownership &= cv::norm(malvar, savedMalvar, cv::NORM_INF) == 0.0;
                ownership &= cv::norm(amaze, savedAmaze, cv::NORM_INF) == 0.0;
                deterministic &= cv::norm(malvar, demosaicMalvar2004ToRgb32f(linear.mosaic, effective), cv::NORM_INF) < 1.e-6;
                deterministic &= cv::norm(amaze, demosaicAmazeInspiredToRgb32f(linear.mosaic, effective), cv::NORM_INF) < 1.e-6;
                for (int y = 0; y < size.height; ++y) {
                    for (int x = 0; x < size.width; ++x) {
                        const int channel = bncam::raw::bayerColorAt(effective, x, y);
                        const float input = linear.mosaic.at<float>(y, x);
                        const auto m = malvar.at<cv::Vec3f>(y, x), a = amaze.at<cv::Vec3f>(y, x);
                        samples &= std::abs(m[channel] - input) < 1.e-6f && std::abs(a[channel] - input) < 1.e-6f;
                        for (int c = 0; c < 3; ++c) finite &= std::isfinite(m[c]) && std::isfinite(a[c]);
                    }
                }
                maxDifference = std::max(maxDifference, cv::norm(malvar, amaze, cv::NORM_INF));
                AutoDemosaicContext context;
                context.captureIso = 800;
                const auto r1 = resolveDemosaicForFrame(0, linear.mosaic, context);
                const auto r2 = resolveDemosaicForFrame(0, linear.mosaic, context);
                hybrid &= r1.algorithm == r2.algorithm && r1.reason == r2.reason &&
                    r1.autoMalvarPrior == r2.autoMalvarPrior && r1.autoAmazePrior == r2.autoAmazePrior &&
                    r1.autoBncNeuralPrior == 0.0f;
                ++cases;
            }
        }
    }
    // Analytic MHC impulse oracle at an interior red sample: kernels /8 => (1, 4/8, 6/8).
    cv::Mat impulse(9, 9, CV_32FC1, cv::Scalar(0.0f));
    impulse.at<float>(4, 4) = 1.0f;
    const auto rgb = demosaicMalvar2004ToRgb32f(impulse, 0).at<cv::Vec3f>(4, 4);
    golden &= std::abs(rgb[0] - 1.0f) < 1.e-6f && std::abs(rgb[1] - 0.5f) < 1.e-6f && std::abs(rgb[2] - 0.75f) < 1.e-6f;
    std::vector<std::future<cv::Mat>> workers;
    for (int i = 0; i < 4; ++i) {
        workers.emplace_back(std::async(std::launch::async, [i] {
            cv::Mat input(17, 17, CV_32FC1, cv::Scalar(0.1f * (i + 1)));
            return i & 1 ? demosaicAmazeInspiredToRgb32f(input, 0) : demosaicMalvar2004ToRgb32f(input, 0);
        }));
    }
    for (int i = 0; i < 4; ++i) {
        const auto output = workers[i].get();
        ownership &= cv::norm(output, cv::Mat(output.size(), CV_32FC3, cv::Scalar::all(0.1f * (i + 1))), cv::NORM_INF) < 1.e-5;
    }
    bool rejected = demosaicMalvar2004ToRgb32f(impulse, 5).empty() && demosaicAmazeInspiredToRgb32f(impulse, -1).empty();
    std::ostringstream out;
    out << std::boolalpha << "replayCases=" << cases << ";normalization=" << normalization
        << ";cropParity=" << parity << ";determinism=" << deterministic << ";outputOwnership=" << ownership
        << ";samplePreservation=" << samples << ";finiteBorders=" << finite << ";malvarImpulseGolden=" << golden
        << ";hybridPolicyDeterminism=" << hybrid << ";unsupportedCfaRejected=" << rejected
        << ";malvarAmazeMaxDifference=" << maxDifference
        << ";replayPassed=" << (cases == 80 && normalization && parity && deterministic && ownership && samples && finite && golden && hybrid && rejected);
    return out.str();
}
