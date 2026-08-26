#include "Demosaic.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <vector>

namespace {

constexpr int CFA_RGGB = 0;
constexpr int CFA_GRBG = 1;
constexpr int CFA_GBRG = 2;
constexpr int CFA_BGGR = 3;

constexpr float MHC_SCALE = 1.0f / 8.0f;
using DemosaicClock = std::chrono::steady_clock;
std::atomic<float> gLastMenonTimeMs{0.0f};

float elapsedDemosaicMs(DemosaicClock::time_point start) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(
            DemosaicClock::now() - start
    ).count());
}

int safeCfaPattern(int pattern) {
    return pattern >= CFA_RGGB && pattern <= CFA_BGGR ? pattern : CFA_RGGB;
}

// Returns physical color index R=0, G=1, B=2. The two green phases intentionally collapse
// here: MHC needs color and neighborhood orientation, not separate green gains.
int cfaColorAt(int pattern, int x, int y) {
    const int xm = x & 1;
    const int ym = y & 1;
    switch (safeCfaPattern(pattern)) {
        case CFA_RGGB: return ym == 0 ? (xm == 0 ? 0 : 1) : (xm == 0 ? 1 : 2);
        case CFA_GRBG: return ym == 0 ? (xm == 0 ? 1 : 0) : (xm == 0 ? 2 : 1);
        case CFA_GBRG: return ym == 0 ? (xm == 0 ? 1 : 2) : (xm == 0 ? 0 : 1);
        case CFA_BGGR: return ym == 0 ? (xm == 0 ? 2 : 1) : (xm == 0 ? 1 : 0);
        default: return 0;
    }
}

float sampleClamped(const cv::Mat& mosaic, int x, int y) {
    const int cx = std::clamp(x, 0, mosaic.cols - 1);
    const int cy = std::clamp(y, 0, mosaic.rows - 1);
    return mosaic.ptr<float>(cy)[cx];
}

int reflectIndex(int value, int length) {
    if (length <= 1) return 0;
    // scipy.ndimage's "reflect" mode, used by the Colour Science bilinear oracle, is
    // half-sample symmetric and corresponds to OpenCV BORDER_REFLECT.
    return cv::borderInterpolate(value, length, cv::BORDER_REFLECT);
}

float sampleBilinearChannelReflect(
        const cv::Mat& mosaic,
        int pattern,
        int x,
        int y,
        int channel
) {
    const int reflectedX = reflectIndex(x, mosaic.cols);
    const int reflectedY = reflectIndex(y, mosaic.rows);
    if (cfaColorAt(pattern, reflectedX, reflectedY) != channel) return 0.0f;
    return mosaic.ptr<float>(reflectedY)[reflectedX];
}

float finiteSceneLinear(float value) {
    if (!std::isfinite(value)) return 0.0f;
    // MHC is a linear reconstruction filter. Preserve legitimate negative lobes and overshoot;
    // range/tone policy belongs to the downstream ISP, not to demosaic.
    return value;
}

// Malvar-He-Cutler 2004 Eq. (8): green at a red or blue sample.
float greenAtRedOrBlue(const cv::Mat& m, int x, int y) {
    const float c = sampleClamped(m, x, y);
    const float axial1 = sampleClamped(m, x - 1, y) + sampleClamped(m, x + 1, y) +
                         sampleClamped(m, x, y - 1) + sampleClamped(m, x, y + 1);
    const float axial2 = sampleClamped(m, x - 2, y) + sampleClamped(m, x + 2, y) +
                         sampleClamped(m, x, y - 2) + sampleClamped(m, x, y + 2);
    return finiteSceneLinear((4.0f * c + 2.0f * axial1 - axial2) * MHC_SCALE);
}

// Malvar-He-Cutler 2004 Eq. (10), oriented so the requested chroma samples are horizontal.
// Kernel /8:
//       0  0  .5  0  0
//       0 -1   0 -1  0
//      -1  4   5  4 -1
//       0 -1   0 -1  0
//       0  0  .5  0  0
float chromaAtGreenHorizontal(const cv::Mat& m, int x, int y) {
    const float c = sampleClamped(m, x, y);
    const float horizontal1 = sampleClamped(m, x - 1, y) + sampleClamped(m, x + 1, y);
    const float horizontal2 = sampleClamped(m, x - 2, y) + sampleClamped(m, x + 2, y);
    const float vertical2 = sampleClamped(m, x, y - 2) + sampleClamped(m, x, y + 2);
    const float diagonals = sampleClamped(m, x - 1, y - 1) + sampleClamped(m, x + 1, y - 1) +
                            sampleClamped(m, x - 1, y + 1) + sampleClamped(m, x + 1, y + 1);
    return finiteSceneLinear(
            (5.0f * c + 4.0f * horizontal1 - horizontal2 - diagonals + 0.5f * vertical2) *
            MHC_SCALE
    );
}

// Transpose of the preceding kernel: requested chroma samples are vertical.
float chromaAtGreenVertical(const cv::Mat& m, int x, int y) {
    const float c = sampleClamped(m, x, y);
    const float vertical1 = sampleClamped(m, x, y - 1) + sampleClamped(m, x, y + 1);
    const float vertical2 = sampleClamped(m, x, y - 2) + sampleClamped(m, x, y + 2);
    const float horizontal2 = sampleClamped(m, x - 2, y) + sampleClamped(m, x + 2, y);
    const float diagonals = sampleClamped(m, x - 1, y - 1) + sampleClamped(m, x + 1, y - 1) +
                            sampleClamped(m, x - 1, y + 1) + sampleClamped(m, x + 1, y + 1);
    return finiteSceneLinear(
            (5.0f * c + 4.0f * vertical1 - vertical2 - diagonals + 0.5f * horizontal2) *
            MHC_SCALE
    );
}

// Malvar-He-Cutler 2004 Eq. (11): red at blue or blue at red.
float oppositeAtRedOrBlue(const cv::Mat& m, int x, int y) {
    const float c = sampleClamped(m, x, y);
    const float diagonals = sampleClamped(m, x - 1, y - 1) + sampleClamped(m, x + 1, y - 1) +
                            sampleClamped(m, x - 1, y + 1) + sampleClamped(m, x + 1, y + 1);
    const float axial2 = sampleClamped(m, x - 2, y) + sampleClamped(m, x + 2, y) +
                         sampleClamped(m, x, y - 2) + sampleClamped(m, x, y + 2);
    return finiteSceneLinear((6.0f * c + 2.0f * diagonals - 1.5f * axial2) * MHC_SCALE);
}

// Delta 28: Malvar Inspired remains the conservative linear choice. SPECTRA evidence only
// stabilizes reconstructed (never sampled) R/B values. Green stays bit-for-bit on the existing
// MHC path; strong scene structure suppresses this authority.
float malvarNoiseAwareChromaRisk(const DemosaicCfaEvidence* evidence, int channel) {
    if (evidence == nullptr || !evidence->available || (channel != 0 && channel != 2)) return 0.0f;
    const float bandPressure = std::clamp(
            0.30f * evidence->fineCorrectionConfidence +
            0.40f * evidence->midCorrectionConfidence +
            0.30f * evidence->lowCorrectionConfidence,
            0.0f,
            1.0f
    );
    const float opponentPressure = channel == 0
            ? evidence->redOpponentCorrectionConfidence
            : evidence->blueOpponentCorrectionConfidence;
    const float structureRelief = 1.0f - 0.45f *
            std::clamp(evidence->structureProtection, 0.0f, 1.0f);
    return std::clamp(
            (0.70f * bandPressure + 0.30f * std::clamp(opponentPressure, 0.0f, 1.0f)) *
                    structureRelief,
            0.0f,
            1.0f
    );
}

float malvarRobustMissingColor(
        const cv::Mat& mosaic,
        int pattern,
        int x,
        int y,
        int channel
) {
    const int centerColor = cfaColorAt(pattern, x, y);
    if (centerColor == channel) return sampleClamped(mosaic, x, y);
    if (centerColor == 1) {
        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
        const bool horizontal = channel == 0 ? horizontalRed : !horizontalRed;
        return horizontal
                ? 0.5f * (sampleClamped(mosaic, x - 1, y) + sampleClamped(mosaic, x + 1, y))
                : 0.5f * (sampleClamped(mosaic, x, y - 1) + sampleClamped(mosaic, x, y + 1));
    }
    return 0.25f * (
            sampleClamped(mosaic, x - 1, y - 1) + sampleClamped(mosaic, x + 1, y - 1) +
            sampleClamped(mosaic, x - 1, y + 1) + sampleClamped(mosaic, x + 1, y + 1));
}

float demosaicSmoothstep(float edge0, float edge1, float x) {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

float demosaicNoiseSupport(const DemosaicNoiseContext* noiseContext) {
    if (noiseContext == nullptr || !noiseContext->available) return 0.0f;
    const float sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaChroma)
            ? noiseContext->sigmaChroma : 0.0f);
    const float pressure = std::clamp(std::isfinite(noiseContext->pressure)
            ? noiseContext->pressure : 0.0f, 0.0f, 1.0f);
    return std::max(pressure, demosaicSmoothstep(7.5e-4f, 6.0e-3f, sigma));
}

float malvarStabilizeMissingColor(
        const cv::Mat& mosaic,
        int pattern,
        int x,
        int y,
        int channel,
        float baseline,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    if (cfaColorAt(pattern, x, y) == channel || chromaRisk <= 0.0f) return baseline;
    const float robust = malvarRobustMissingColor(mosaic, pattern, x, y, channel);
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    float blend = 0.14f * risk;
    if (noiseContext != nullptr && noiseContext->available) {
        const float sigma = std::max(1.0e-6f, std::isfinite(noiseContext->sigmaChroma)
                ? noiseContext->sigmaChroma : 0.0f);
        const float z = std::abs(baseline - robust) / sigma;
        const float noiseLike = 1.0f - demosaicSmoothstep(1.50f, 4.00f, z);
        blend += 0.12f * risk * noiseLike * demosaicNoiseSupport(noiseContext);
    }
    return finiteSceneLinear(baseline + std::clamp(blend, 0.0f, 0.26f) * (robust - baseline));
}

bool nearlyEqual(float a, float b, float epsilon = 1.0e-5f) {
    return std::abs(a - b) <= epsilon;
}

cv::Mat syntheticChannelMosaic(int pattern, int channel, int size = 11) {
    cv::Mat mosaic(size, size, CV_32FC1, cv::Scalar(0.0f));
    for (int y = 0; y < size; ++y) {
        float* row = mosaic.ptr<float>(y);
        for (int x = 0; x < size; ++x) {
            row[x] = cfaColorAt(pattern, x, y) == channel ? 1.0f : 0.0f;
        }
    }
    return mosaic;
}

int mirrorIndex(int value, int length) {
    if (length <= 1) return 0;
    return cv::borderInterpolate(value, length, cv::BORDER_REFLECT_101);
}

float sampleMirror(const cv::Mat& image, int x, int y) {
    return image.ptr<float>(mirrorIndex(y, image.rows))[mirrorIndex(x, image.cols)];
}

float sampleConstantZero(const cv::Mat& image, int x, int y) {
    if (x < 0 || y < 0 || x >= image.cols || y >= image.rows) return 0.0f;
    return image.ptr<float>(y)[x];
}

void buildMenonGreenCandidates(const cv::Mat& mosaic, int pattern, cv::Mat& greenH, cv::Mat& greenV) {
    greenH.create(mosaic.size(), CV_32FC1);
    greenV.create(mosaic.size(), CV_32FC1);
    const int rows = mosaic.rows;
    const int cols = mosaic.cols;

    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outH = greenH.ptr<float>(y);
            float* outV = greenV.ptr<float>(y);
            const float* row_0 = mosaic.ptr<float>(y);
            const bool isBorderRow = (y < 2 || y >= rows - 2);

            if (!isBorderRow) {
                const float* row_m2 = mosaic.ptr<float>(y - 2);
                const float* row_m1 = mosaic.ptr<float>(y - 1);
                const float* row_p1 = mosaic.ptr<float>(y + 1);
                const float* row_p2 = mosaic.ptr<float>(y + 2);

                for (int x = 0; x < 2; ++x) {
                    const float center = row_0[x];
                    if (cfaColorAt(pattern, x, y) == 1) {
                        outH[x] = center;
                        outV[x] = center;
                    } else {
                        outH[x] = -0.25f * (sampleMirror(mosaic, x - 2, y) + sampleMirror(mosaic, x + 2, y)) +
                                  0.50f * (sampleMirror(mosaic, x - 1, y) + center + sampleMirror(mosaic, x + 1, y));
                        outV[x] = -0.25f * (sampleMirror(mosaic, x, y - 2) + sampleMirror(mosaic, x, y + 2)) +
                                  0.50f * (sampleMirror(mosaic, x, y - 1) + center + sampleMirror(mosaic, x, y + 1));
                    }
                }

                for (int x = 2; x < cols - 2; ++x) {
                    const float center = row_0[x];
                    if (cfaColorAt(pattern, x, y) == 1) {
                        outH[x] = center;
                        outV[x] = center;
                    } else {
                        outH[x] = -0.25f * (row_0[x - 2] + row_0[x + 2]) +
                                  0.50f * (row_0[x - 1] + center + row_0[x + 1]);
                        outV[x] = -0.25f * (row_m2[x] + row_p2[x]) +
                                  0.50f * (row_m1[x] + center + row_p1[x]);
                    }
                }

                for (int x = cols - 2; x < cols; ++x) {
                    const float center = row_0[x];
                    if (cfaColorAt(pattern, x, y) == 1) {
                        outH[x] = center;
                        outV[x] = center;
                    } else {
                        outH[x] = -0.25f * (sampleMirror(mosaic, x - 2, y) + sampleMirror(mosaic, x + 2, y)) +
                                  0.50f * (sampleMirror(mosaic, x - 1, y) + center + sampleMirror(mosaic, x + 1, y));
                        outV[x] = -0.25f * (sampleMirror(mosaic, x, y - 2) + sampleMirror(mosaic, x, y + 2)) +
                                  0.50f * (sampleMirror(mosaic, x, y - 1) + center + sampleMirror(mosaic, x, y + 1));
                    }
                }
            } else {
                for (int x = 0; x < cols; ++x) {
                    const float center = row_0[x];
                    if (cfaColorAt(pattern, x, y) == 1) {
                        outH[x] = center;
                        outV[x] = center;
                        continue;
                    }
                    outH[x] = -0.25f * (sampleMirror(mosaic, x - 2, y) + sampleMirror(mosaic, x + 2, y)) +
                              0.50f * (sampleMirror(mosaic, x - 1, y) + center + sampleMirror(mosaic, x + 1, y));
                    outV[x] = -0.25f * (sampleMirror(mosaic, x, y - 2) + sampleMirror(mosaic, x, y + 2)) +
                              0.50f * (sampleMirror(mosaic, x, y - 1) + center + sampleMirror(mosaic, x, y + 1));
                }
            }
        }
    });
}

void buildMenonDirectionalDifference(
        const cv::Mat& mosaic,
        const cv::Mat& greenCandidate,
        int pattern,
        bool horizontal,
        cv::Mat& difference
) {
    difference.create(mosaic.size(), CV_32FC1);
    const auto colorDifferenceAt = [&](int x, int y) -> float {
        if (cfaColorAt(pattern, x, y) == 1) return 0.0f;
        return mosaic.ptr<float>(y)[x] - greenCandidate.ptr<float>(y)[x];
    };
    cv::parallel_for_(cv::Range(0, mosaic.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* out = difference.ptr<float>(y);
            for (int x = 0; x < mosaic.cols; ++x) {
                const int x2 = horizontal ? mirrorIndex(x + 2, mosaic.cols) : x;
                const int y2 = horizontal ? y : mirrorIndex(y + 2, mosaic.rows);
                out[x] = std::abs(colorDifferenceAt(x, y) - colorDifferenceAt(x2, y2));
            }
        }
    });
}

void convolveMenonDecisionKernel(
        const cv::Mat& difference,
        bool transposeKernel,
        cv::Mat& result
) {
    static constexpr std::array<std::array<int, 3>, 8> taps{{
            {{0, 2, 1}}, {{-2, 2, 1}}, {{-1, 1, 1}}, {{0, 0, 3}},
            {{-2, 0, 3}}, {{-1, -1, 1}}, {{0, -2, 1}}, {{-2, -2, 1}}
    }};
    result.create(difference.size(), CV_32FC1);
    const int rows = difference.rows;
    const int cols = difference.cols;

    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* out = result.ptr<float>(y);
            const bool isBorderRow = (y < 2 || y >= rows - 2);

            if (!isBorderRow) {
                const float* r_m2 = difference.ptr<float>(y - 2);
                const float* r_m1 = difference.ptr<float>(y - 1);
                const float* r_0  = difference.ptr<float>(y);
                const float* r_p1 = difference.ptr<float>(y + 1);
                const float* r_p2 = difference.ptr<float>(y + 2);

                for (int x = 0; x < 2; ++x) {
                    float sum = 0.0f;
                    for (const auto& tap : taps) {
                        const int dx = transposeKernel ? tap[1] : tap[0];
                        const int dy = transposeKernel ? tap[0] : tap[1];
                        sum += static_cast<float>(tap[2]) * sampleConstantZero(difference, x + dx, y + dy);
                    }
                    out[x] = sum;
                }

                if (!transposeKernel) {
                    // tap[0] = dx, tap[1] = dy
                    // taps: {0,2,1}, {-2,2,1}, {-1,1,1}, {0,0,3}, {-2,0,3}, {-1,-1,1}, {0,-2,1}, {-2,-2,1}
                    for (int x = 2; x < cols - 2; ++x) {
                        float sum = 1.0f * r_p2[x]
                                  + 1.0f * r_p2[x - 2]
                                  + 1.0f * r_p1[x - 1]
                                  + 3.0f * r_0[x]
                                  + 3.0f * r_0[x - 2]
                                  + 1.0f * r_m1[x - 1]
                                  + 1.0f * r_m2[x]
                                  + 1.0f * r_m2[x - 2];
                        out[x] = sum;
                    }
                } else {
                    // transposed: tap[0] = dy, tap[1] = dx
                    // taps: {dy=0,dx=2,w=1}, {dy=-2,dx=2,w=1}, {dy=-1,dx=1,w=1}, {dy=0,dx=0,w=3}, {dy=-2,dx=0,w=3}, {dy=-1,dx=-1,w=1}, {dy=0,dx=-2,w=1}, {dy=-2,dx=-2,w=1}
                    for (int x = 2; x < cols - 2; ++x) {
                        float sum = 1.0f * r_0[x + 2]
                                  + 1.0f * r_m2[x + 2]
                                  + 1.0f * r_m1[x + 1]
                                  + 3.0f * r_0[x]
                                  + 3.0f * r_m2[x]
                                  + 1.0f * r_m1[x - 1]
                                  + 1.0f * r_0[x - 2]
                                  + 1.0f * r_m2[x - 2];
                        out[x] = sum;
                    }
                }

                for (int x = cols - 2; x < cols; ++x) {
                    float sum = 0.0f;
                    for (const auto& tap : taps) {
                        const int dx = transposeKernel ? tap[1] : tap[0];
                        const int dy = transposeKernel ? tap[0] : tap[1];
                        sum += static_cast<float>(tap[2]) * sampleConstantZero(difference, x + dx, y + dy);
                    }
                    out[x] = sum;
                }
            } else {
                for (int x = 0; x < cols; ++x) {
                    float sum = 0.0f;
                    for (const auto& tap : taps) {
                        const int dx = transposeKernel ? tap[1] : tap[0];
                        const int dy = transposeKernel ? tap[0] : tap[1];
                        sum += static_cast<float>(tap[2]) * sampleConstantZero(difference, x + dx, y + dy);
                    }
                    out[x] = sum;
                }
            }
        }
    });
}

float meanTwoMirror(const cv::Mat& image, int x, int y, bool horizontal) {
    return horizontal
            ? 0.5f * (sampleMirror(image, x - 1, y) + sampleMirror(image, x + 1, y))
            : 0.5f * (sampleMirror(image, x, y - 1) + sampleMirror(image, x, y + 1));
}

float meanThreeMirror(const cv::Mat& image, int x, int y, bool horizontal) {
    return horizontal
            ? (sampleMirror(image, x - 1, y) + image.ptr<float>(y)[x] + sampleMirror(image, x + 1, y)) / 3.0f
            : (sampleMirror(image, x, y - 1) + image.ptr<float>(y)[x] + sampleMirror(image, x, y + 1)) / 3.0f;
}

void subtractPlanes(const cv::Mat& a, const cv::Mat& b, cv::Mat& result) {
    cv::subtract(a, b, result, cv::noArray(), CV_32F);
}

float percentileSorted(const std::vector<float>& values, float percentile) {
    if (values.empty()) return 0.0f;
    const size_t index = std::min(
            values.size() - 1u,
            static_cast<size_t>(std::floor(
                    std::clamp(percentile, 0.0f, 1.0f) * static_cast<float>(values.size() - 1u)
            ))
    );
    return values[index];
}

AutoDemosaicSceneMetrics analyzeAutoDemosaicScene(const cv::Mat& mosaic) {
    AutoDemosaicSceneMetrics metrics{};
    if (mosaic.empty() || mosaic.type() != CV_32FC1 || mosaic.cols < 9 || mosaic.rows < 9) {
        return metrics;
    }

    constexpr size_t TARGET_SAMPLES = 50000u;
    constexpr float EDGE_THRESHOLD = 0.025f;
    constexpr float COHERENT_EDGE_THRESHOLD = 0.030f;
    constexpr float LOW_SIGNAL_THRESHOLD = 0.060f;
    const size_t interiorPixels = static_cast<size_t>(mosaic.cols - 8) * static_cast<size_t>(mosaic.rows - 8);
    const int stride = std::max(
            1,
            static_cast<int>(std::floor(std::sqrt(
                    static_cast<double>(interiorPixels) / static_cast<double>(TARGET_SAMPLES)
            )))
    );

    std::vector<float> signals;
    std::vector<float> gradients;
    signals.reserve(std::min(TARGET_SAMPLES, interiorPixels));
    gradients.reserve(std::min(TARGET_SAMPLES, interiorPixels));
    double gradientSum = 0.0;
    int edgeCount = 0;
    int coherentEdgeCount = 0;
    int lowSignalCount = 0;

    const auto coherentSameSign = [](float center, float a, float b) {
        return center * a > 0.0f && center * b > 0.0f &&
               std::abs(a) >= 0.015f && std::abs(b) >= 0.015f;
    };
    for (int y = 4; y < mosaic.rows - 4; y += stride) {
        for (int x = 4; x < mosaic.cols - 4; x += stride) {
            const float center = mosaic.ptr<float>(y)[x];
            const float signedDx = 0.5f * (mosaic.ptr<float>(y)[x + 2] - mosaic.ptr<float>(y)[x - 2]);
            const float signedDy = 0.5f * (mosaic.ptr<float>(y + 2)[x] - mosaic.ptr<float>(y - 2)[x]);
            const float dx = std::abs(signedDx);
            const float dy = std::abs(signedDy);
            const float gradient = std::max(dx, dy);
            signals.push_back(center);
            gradients.push_back(gradient);
            gradientSum += gradient;
            if (gradient >= EDGE_THRESHOLD) ++edgeCount;
            if (center < LOW_SIGNAL_THRESHOLD) ++lowSignalCount;

            bool coherent = false;
            if (dx >= COHERENT_EDGE_THRESHOLD && dx > 1.5f * dy + 0.005f) {
                const float above = 0.5f * (
                        mosaic.ptr<float>(y - 2)[x + 2] - mosaic.ptr<float>(y - 2)[x - 2]
                );
                const float below = 0.5f * (
                        mosaic.ptr<float>(y + 2)[x + 2] - mosaic.ptr<float>(y + 2)[x - 2]
                );
                coherent = coherentSameSign(signedDx, above, below);
            } else if (dy >= COHERENT_EDGE_THRESHOLD && dy > 1.5f * dx + 0.005f) {
                const float left = 0.5f * (
                        mosaic.ptr<float>(y + 2)[x - 2] - mosaic.ptr<float>(y - 2)[x - 2]
                );
                const float right = 0.5f * (
                        mosaic.ptr<float>(y + 2)[x + 2] - mosaic.ptr<float>(y - 2)[x + 2]
                );
                coherent = coherentSameSign(signedDy, left, right);
            }
            if (coherent) ++coherentEdgeCount;
        }
    }

    if (signals.empty()) return metrics;
    std::sort(signals.begin(), signals.end());
    std::sort(gradients.begin(), gradients.end());
    metrics.valid = true;
    metrics.sampleCount = static_cast<int>(signals.size());
    const float denominator = static_cast<float>(metrics.sampleCount);
    metrics.medianSignal = percentileSorted(signals, 0.50f);
    metrics.meanGradient = static_cast<float>(gradientSum / static_cast<double>(metrics.sampleCount));
    metrics.p90Gradient = percentileSorted(gradients, 0.90f);
    metrics.edgeFraction = static_cast<float>(edgeCount) / denominator;
    metrics.coherentEdgeFraction = static_cast<float>(coherentEdgeCount) / denominator;
    metrics.lowSignalFraction = static_cast<float>(lowSignalCount) / denominator;
    return metrics;
}

void refineMenon2007(
        cv::Mat& red,
        cv::Mat& green,
        cv::Mat& blue,
        const cv::Mat& horizontalDirection,
        int pattern,
        cv::Mat& redMinusGreen,
        cv::Mat& blueMinusGreen
) {
    // Update green at red/blue sites using directional three-tap color differences.
    subtractPlanes(red, green, redMinusGreen);
    subtractPlanes(blue, green, blueMinusGreen);
    cv::parallel_for_(cv::Range(0, green.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* out = green.ptr<float>(y);
            const uint8_t* direction = horizontalDirection.ptr<uint8_t>(y);
            for (int x = 0; x < green.cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const bool horizontal = direction[x] != 0;
                if (color == 0) {
                    out[x] = red.ptr<float>(y)[x] - meanThreeMirror(redMinusGreen, x, y, horizontal);
                } else if (color == 2) {
                    out[x] = blue.ptr<float>(y)[x] - meanThreeMirror(blueMinusGreen, x, y, horizontal);
                }
            }
        }
    });
    // Update red and blue at green sites using the correct CFA-axis color difference.
    subtractPlanes(red, green, redMinusGreen);
    subtractPlanes(blue, green, blueMinusGreen);
    cv::parallel_for_(cv::Range(0, green.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outR = red.ptr<float>(y);
            float* outB = blue.ptr<float>(y);
            for (int x = 0; x < green.cols; ++x) {
                if (cfaColorAt(pattern, x, y) != 1) continue;
                const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                outR[x] = green.ptr<float>(y)[x] +
                          meanTwoMirror(redMinusGreen, x, y, horizontalRed);
                outB[x] = green.ptr<float>(y)[x] +
                          meanTwoMirror(blueMinusGreen, x, y, !horizontalRed);
            }
        }
    });
    // Update red at blue and blue at red with the selected directional R-B difference.
    subtractPlanes(red, blue, redMinusGreen); // Reuse the first difference plane.
    cv::parallel_for_(cv::Range(0, red.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outR = red.ptr<float>(y);
            const uint8_t* direction = horizontalDirection.ptr<uint8_t>(y);
            for (int x = 0; x < red.cols; ++x) {
                if (cfaColorAt(pattern, x, y) == 2) {
                    outR[x] = blue.ptr<float>(y)[x] +
                              meanThreeMirror(redMinusGreen, x, y, direction[x] != 0);
                }
            }
        }
    });
    cv::parallel_for_(cv::Range(0, blue.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outB = blue.ptr<float>(y);
            const uint8_t* direction = horizontalDirection.ptr<uint8_t>(y);
            for (int x = 0; x < blue.cols; ++x) {
                if (cfaColorAt(pattern, x, y) == 0) {
                    outB[x] = red.ptr<float>(y)[x] -
                              meanThreeMirror(redMinusGreen, x, y, direction[x] != 0);
                }
            }
        }
    });
}

float rcdLowPass(const cv::Mat& mosaic, int x, int y) {
    const float corners = sampleClamped(mosaic, x - 1, y - 1) +
                          sampleClamped(mosaic, x + 1, y - 1) +
                          sampleClamped(mosaic, x - 1, y + 1) +
                          sampleClamped(mosaic, x + 1, y + 1);
    const float axial = sampleClamped(mosaic, x - 1, y) +
                        sampleClamped(mosaic, x + 1, y) +
                        sampleClamped(mosaic, x, y - 1) +
                        sampleClamped(mosaic, x, y + 1);
    return (4.0f * sampleClamped(mosaic, x, y) + 2.0f * axial + corners) * (1.0f / 16.0f);
}

float rcdRatioCorrectedGreenCandidate(
        float adjacentGreen,
        float centerLowPass,
        float farLowPass,
        const DemosaicNoiseContext* noiseContext
) {
    constexpr float kEpsilon = 1.0e-5f;
    float denominator = centerLowPass + farLowPass;
    if (std::abs(denominator) < kEpsilon) {
        denominator = denominator < 0.0f ? -kEpsilon : kEpsilon;
    }
    float correction = std::clamp(
            (centerLowPass - farLowPass) / denominator,
            -0.75f,
            0.75f
    );
    if (noiseContext != nullptr && noiseContext->available) {
        const float sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaY)
                ? noiseContext->sigmaY : 0.0f);
        if (sigma > 1.0e-7f) {
            const float localSignal = std::abs(centerLowPass) + std::abs(farLowPass);
            // Ratio arithmetic becomes unstable when both low-pass terms live near the noise floor.
            // Fade only the ratio correction; the adjacent sampled green remains untouched.
            const float signalSupport = demosaicSmoothstep(2.5f * sigma, 8.0f * sigma, localSignal);
            correction *= signalSupport;
        }
    }
    return finiteSceneLinear(adjacentGreen * (1.0f + correction));
}

float rcdDirectionalWeight(float gradient, const DemosaicNoiseContext* noiseContext) {
    constexpr float kFloor = 2.0e-4f;
    float sigma = 0.0f;
    float support = 0.0f;
    if (noiseContext != nullptr && noiseContext->available) {
        sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaY)
                ? noiseContext->sigmaY : 0.0f);
        support = demosaicNoiseSupport(noiseContext);
    }
    const float debiasedGradient = std::max(0.0f, gradient - 1.25f * sigma);
    const float adaptiveFloor = std::max(kFloor, 0.70f * sigma * support);
    const float stabilized = adaptiveFloor + debiasedGradient;
    return 1.0f / std::max(1.0e-12f, stabilized * stabilized);
}

// Delta 26: RCD keeps its green reconstruction unchanged. Only missing R/B colour-difference
// interpolation becomes noise-aware. Higher supported CFA chroma pressure slightly stabilizes
// directional weights and blends them toward a local opponent mean, reducing noise-driven
// single-neighbour selection without flattening the green/luma edge solution.
float rcdNoiseAwareChromaRisk(const DemosaicCfaEvidence* evidence, int channel) {
    if (evidence == nullptr || !evidence->available || (channel != 0 && channel != 2)) return 0.0f;
    const float bandPressure = std::clamp(
            0.45f * evidence->fineCorrectionConfidence +
            0.35f * evidence->midCorrectionConfidence +
            0.20f * evidence->lowCorrectionConfidence,
            0.0f,
            1.0f
    );
    const float opponentPressure = channel == 0
            ? evidence->redOpponentCorrectionConfidence
            : evidence->blueOpponentCorrectionConfidence;
    const float structureRelief = 1.0f - 0.35f *
            std::clamp(evidence->structureProtection, 0.0f, 1.0f);
    return std::clamp(
            (0.65f * bandPressure + 0.35f * std::clamp(opponentPressure, 0.0f, 1.0f)) *
                    structureRelief,
            0.0f,
            1.0f
    );
}

float rcdChromaDirectionalWeight(
        float gradient,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    float sigma = 0.0f;
    float support = 0.0f;
    if (noiseContext != nullptr && noiseContext->available) {
        sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaChroma)
                ? noiseContext->sigmaChroma : 0.0f);
        support = demosaicNoiseSupport(noiseContext);
    }
    const float floor = std::max(2.0e-4f + 4.0e-4f * risk, 0.65f * sigma * support);
    const float debiasedGradient = std::max(0.0f, gradient - 1.10f * sigma);
    const float stabilized = floor + debiasedGradient;
    return 1.0f / std::max(1.0e-12f, stabilized * stabilized);
}

float rcdInterpolateDifferenceAxial(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        int pattern,
        int x,
        int y,
        int channel,
        bool horizontal,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const int dx = horizontal ? 1 : 0;
    const int dy = horizontal ? 0 : 1;
    const int x0 = std::clamp(x - dx, 0, mosaic.cols - 1);
    const int y0 = std::clamp(y - dy, 0, mosaic.rows - 1);
    const int x1 = std::clamp(x + dx, 0, mosaic.cols - 1);
    const int y1 = std::clamp(y + dy, 0, mosaic.rows - 1);
    const float gCenter = green.ptr<float>(y)[x];
    const float d0 = cfaColorAt(pattern, x0, y0) == channel
            ? mosaic.ptr<float>(y0)[x0] - green.ptr<float>(y0)[x0]
            : 0.0f;
    const float d1 = cfaColorAt(pattern, x1, y1) == channel
            ? mosaic.ptr<float>(y1)[x1] - green.ptr<float>(y1)[x1]
            : 0.0f;
    const float w0 = rcdChromaDirectionalWeight(
            std::abs(gCenter - green.ptr<float>(y0)[x0]), chromaRisk, noiseContext);
    const float w1 = rcdChromaDirectionalWeight(
            std::abs(gCenter - green.ptr<float>(y1)[x1]), chromaRisk, noiseContext);
    const float weightedDifference = (w0 * d0 + w1 * d1) / std::max(1.0e-8f, w0 + w1);
    const bool bothSamplesValid = cfaColorAt(pattern, x0, y0) == channel &&
            cfaColorAt(pattern, x1, y1) == channel;
    const float robustDifference = bothSamplesValid ? 0.5f * (d0 + d1) : weightedDifference;
    const float robustBlend = 0.18f * std::clamp(chromaRisk, 0.0f, 1.0f);
    const float difference = weightedDifference +
            robustBlend * (robustDifference - weightedDifference);
    return finiteSceneLinear(gCenter + difference);
}

float rcdInterpolateDifferenceDiagonal(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        int pattern,
        int x,
        int y,
        int channel,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const float gCenter = green.ptr<float>(y)[x];
    float weightedDifference = 0.0f;
    float totalWeight = 0.0f;
    float simpleDifference = 0.0f;
    int validDifferenceCount = 0;
    constexpr int offsets[4][2] = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    for (const auto& offset : offsets) {
        const int sx = std::clamp(x + offset[0], 0, mosaic.cols - 1);
        const int sy = std::clamp(y + offset[1], 0, mosaic.rows - 1);
        if (cfaColorAt(pattern, sx, sy) != channel) continue;
        const float neighborGreen = green.ptr<float>(sy)[sx];
        const float difference = mosaic.ptr<float>(sy)[sx] - neighborGreen;
        const float weight = rcdChromaDirectionalWeight(
                std::abs(gCenter - neighborGreen), chromaRisk, noiseContext);
        weightedDifference += weight * difference;
        totalWeight += weight;
        simpleDifference += difference;
        ++validDifferenceCount;
    }
    const float directionalDifference = weightedDifference / std::max(1.0e-8f, totalWeight);
    const float robustDifference = validDifferenceCount > 0
            ? simpleDifference / static_cast<float>(validDifferenceCount)
            : directionalDifference;
    const float robustBlend = 0.18f * std::clamp(chromaRisk, 0.0f, 1.0f);
    const float difference = directionalDifference +
            robustBlend * (robustDifference - directionalDifference);
    return finiteSceneLinear(gCenter + difference);
}

float amazeDirectionalWeight(float gradient, const DemosaicNoiseContext* noiseContext) {
    constexpr float kFloor = 1.5e-4f;
    float sigma = 0.0f;
    float support = 0.0f;
    if (noiseContext != nullptr && noiseContext->available) {
        sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaY)
                ? noiseContext->sigmaY : 0.0f);
        support = demosaicNoiseSupport(noiseContext);
    }
    const float debiasedGradient = std::max(0.0f, gradient - 1.10f * sigma);
    const float adaptiveFloor = std::max(kFloor, 0.55f * sigma * support);
    const float stabilized = adaptiveFloor + debiasedGradient;
    return 1.0f / std::max(1.0e-12f, stabilized * stabilized);
}

float amazeRetainHighOrderDetail(float term, const DemosaicNoiseContext* noiseContext) {
    if (noiseContext == nullptr || !noiseContext->available) return term;
    const float sigma = std::max(1.0e-6f, std::isfinite(noiseContext->sigmaY)
            ? noiseContext->sigmaY : 0.0f);
    const float z = std::abs(term) / sigma;
    const float retention = demosaicSmoothstep(1.25f, 3.75f, z);
    return term * retention;
}

// Delta 27: preserve AMAZE Inspired's green/detail reconstruction exactly. Only missing R/B
// colour-difference interpolation consumes SPECTRA CFA evidence. AMAZE intentionally weights
// fine-band pressure most heavily because its detail-oriented interpolation is most exposed to
// noise-driven high-frequency chroma excursions.
float amazeNoiseAwareChromaRisk(const DemosaicCfaEvidence* evidence, int channel) {
    if (evidence == nullptr || !evidence->available || (channel != 0 && channel != 2)) return 0.0f;
    const float bandPressure = std::clamp(
            0.55f * evidence->fineCorrectionConfidence +
            0.30f * evidence->midCorrectionConfidence +
            0.15f * evidence->lowCorrectionConfidence,
            0.0f,
            1.0f
    );
    const float opponentPressure = channel == 0
            ? evidence->redOpponentCorrectionConfidence
            : evidence->blueOpponentCorrectionConfidence;
    const float structureRelief = 1.0f - 0.30f *
            std::clamp(evidence->structureProtection, 0.0f, 1.0f);
    return std::clamp(
            (0.60f * bandPressure + 0.40f * std::clamp(opponentPressure, 0.0f, 1.0f)) *
                    structureRelief,
            0.0f,
            1.0f
    );
}

float amazeChromaDirectionalWeight(
        float gradient,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    float sigma = 0.0f;
    float support = 0.0f;
    if (noiseContext != nullptr && noiseContext->available) {
        sigma = std::max(0.0f, std::isfinite(noiseContext->sigmaChroma)
                ? noiseContext->sigmaChroma : 0.0f);
        support = demosaicNoiseSupport(noiseContext);
    }
    const float floor = std::max(1.5e-4f + 5.0e-4f * risk, 0.60f * sigma * support);
    const float debiasedGradient = std::max(0.0f, gradient - 1.00f * sigma);
    const float stabilized = floor + debiasedGradient;
    return 1.0f / std::max(1.0e-12f, stabilized * stabilized);
}

float amazeGreenAtRedOrBlue(
        const cv::Mat& mosaic, int x, int y, const DemosaicNoiseContext* noiseContext
) {
    const float center = sampleClamped(mosaic, x, y);
    const float left = sampleClamped(mosaic, x - 1, y);
    const float right = sampleClamped(mosaic, x + 1, y);
    const float up = sampleClamped(mosaic, x, y - 1);
    const float down = sampleClamped(mosaic, x, y + 1);
    const float left2 = sampleClamped(mosaic, x - 2, y);
    const float right2 = sampleClamped(mosaic, x + 2, y);
    const float up2 = sampleClamped(mosaic, x, y - 2);
    const float down2 = sampleClamped(mosaic, x, y + 2);

    const float horizontalSecond = 2.0f * center - left2 - right2;
    const float verticalSecond = 2.0f * center - up2 - down2;
    const float horizontalCandidate = 0.5f * (left + right) +
            0.25f * amazeRetainHighOrderDetail(horizontalSecond, noiseContext);
    const float verticalCandidate = 0.5f * (up + down) +
            0.25f * amazeRetainHighOrderDetail(verticalSecond, noiseContext);
    const float horizontalGradient = std::abs(left - right) +
            std::abs(2.0f * center - left2 - right2) +
            0.5f * (std::abs(left2 - left) + std::abs(right2 - right));
    const float verticalGradient = std::abs(up - down) +
            std::abs(2.0f * center - up2 - down2) +
            0.5f * (std::abs(up2 - up) + std::abs(down2 - down));
    const float wH = amazeDirectionalWeight(horizontalGradient, noiseContext);
    const float wV = amazeDirectionalWeight(verticalGradient, noiseContext);
    float green = (wH * horizontalCandidate + wV * verticalCandidate) /
            std::max(1.0e-8f, wH + wV);

    // BnCam adaptation of AMaZE's high-frequency caution: in nearly isotropic high-frequency
    // regions, blend a small MHC estimate to reduce zipper/checkerboard instability without
    // flattening strongly directional detail.
    const float diagonalEnergy =
            std::abs(sampleClamped(mosaic, x - 1, y - 1) - sampleClamped(mosaic, x + 1, y + 1)) +
            std::abs(sampleClamped(mosaic, x + 1, y - 1) - sampleClamped(mosaic, x - 1, y + 1));
    const float gradientSum = horizontalGradient + verticalGradient + 1.0e-6f;
    const float anisotropy = std::abs(horizontalGradient - verticalGradient) / gradientSum;
    if (diagonalEnergy > 1.10f * gradientSum && anisotropy < 0.18f) {
        green = 0.75f * green + 0.25f * greenAtRedOrBlue(mosaic, x, y);
    }

    const float localMin = std::min({left, right, up, down});
    const float localMax = std::max({left, right, up, down});
    const float guard = 0.15f * std::max(1.0e-5f, localMax - localMin);
    return finiteSceneLinear(std::clamp(green, localMin - guard, localMax + guard));
}

float amazeInterpolateDifferenceAxial(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        int pattern,
        int x,
        int y,
        int channel,
        bool horizontal,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const int dx = horizontal ? 1 : 0;
    const int dy = horizontal ? 0 : 1;
    const int x0 = std::clamp(x - dx, 0, mosaic.cols - 1);
    const int y0 = std::clamp(y - dy, 0, mosaic.rows - 1);
    const int x1 = std::clamp(x + dx, 0, mosaic.cols - 1);
    const int y1 = std::clamp(y + dy, 0, mosaic.rows - 1);
    const float gCenter = green.ptr<float>(y)[x];
    const float g0 = green.ptr<float>(y0)[x0];
    const float g1 = green.ptr<float>(y1)[x1];
    const float d0 = cfaColorAt(pattern, x0, y0) == channel
            ? mosaic.ptr<float>(y0)[x0] - g0 : 0.0f;
    const float d1 = cfaColorAt(pattern, x1, y1) == channel
            ? mosaic.ptr<float>(y1)[x1] - g1 : 0.0f;
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    const float disagreementWeight = 0.35f + 0.20f * risk;
    const float disagreement = std::abs(d0 - d1);
    const float w0 = amazeChromaDirectionalWeight(
            std::abs(gCenter - g0) + disagreementWeight * disagreement, risk, noiseContext);
    const float w1 = amazeChromaDirectionalWeight(
            std::abs(gCenter - g1) + disagreementWeight * disagreement, risk, noiseContext);
    const float directionalDifference = (w0 * d0 + w1 * d1) / std::max(1.0e-8f, w0 + w1);
    const bool bothSamplesValid = cfaColorAt(pattern, x0, y0) == channel &&
            cfaColorAt(pattern, x1, y1) == channel;
    const float robustDifference = bothSamplesValid ? 0.5f * (d0 + d1) : directionalDifference;
    const float robustBlend = 0.22f * risk;
    const float difference = directionalDifference +
            robustBlend * (robustDifference - directionalDifference);
    return finiteSceneLinear(gCenter + difference);
}

float amazeInterpolateDifferenceDiagonal(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        int pattern,
        int x,
        int y,
        int channel,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    constexpr int offsets[4][2] = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    const float gCenter = green.ptr<float>(y)[x];
    float weightedDifference = 0.0f;
    float totalWeight = 0.0f;
    float simpleDifference = 0.0f;
    int validDifferenceCount = 0;
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    const float disagreementWeight = 0.30f + 0.25f * risk;
    for (const auto& offset : offsets) {
        const int sx = std::clamp(x + offset[0], 0, mosaic.cols - 1);
        const int sy = std::clamp(y + offset[1], 0, mosaic.rows - 1);
        if (cfaColorAt(pattern, sx, sy) != channel) continue;
        const float neighborGreen = green.ptr<float>(sy)[sx];
        const float difference = mosaic.ptr<float>(sy)[sx] - neighborGreen;
        const int ox = std::clamp(x - offset[0], 0, mosaic.cols - 1);
        const int oy = std::clamp(y - offset[1], 0, mosaic.rows - 1);
        const float oppositeGreen = green.ptr<float>(oy)[ox];
        const float oppositeDifference = cfaColorAt(pattern, ox, oy) == channel
                ? mosaic.ptr<float>(oy)[ox] - oppositeGreen : difference;
        const float gradient = std::abs(gCenter - neighborGreen) +
                disagreementWeight * std::abs(difference - oppositeDifference);
        const float weight = amazeChromaDirectionalWeight(gradient, risk, noiseContext);
        weightedDifference += weight * difference;
        totalWeight += weight;
        simpleDifference += difference;
        ++validDifferenceCount;
    }
    const float directionalDifference = weightedDifference / std::max(1.0e-8f, totalWeight);
    const float robustDifference = validDifferenceCount > 0
            ? simpleDifference / static_cast<float>(validDifferenceCount)
            : directionalDifference;
    const float robustBlend = 0.22f * risk;
    const float difference = directionalDifference +
            robustBlend * (robustDifference - directionalDifference);
    return finiteSceneLinear(gCenter + difference);
}

struct MenonScratch {
    cv::Mat greenH;
    cv::Mat greenV;
    cv::Mat difference;
    cv::Mat decisionH;
    cv::Mat decisionV;
    cv::Mat horizontalDirection;
    cv::Mat green;

    bool matches(const cv::Size& size) const {
        return !greenH.empty() && greenH.size() == size &&
               !greenV.empty() && greenV.size() == size &&
               !difference.empty() && difference.size() == size &&
               !decisionH.empty() && decisionH.size() == size &&
               !decisionV.empty() && decisionV.size() == size &&
               !horizontalDirection.empty() && horizontalDirection.size() == size &&
               !green.empty() && green.size() == size;
    }

    void ensure(const cv::Size& size) {
        greenH.create(size, CV_32FC1);
        greenV.create(size, CV_32FC1);
        difference.create(size, CV_32FC1);
        decisionH.create(size, CV_32FC1);
        decisionV.create(size, CV_32FC1);
        horizontalDirection.create(size, CV_8UC1);
        green.create(size, CV_32FC1);
    }
};

MenonScratch gMenonScratch;
std::mutex gMenonScratchMutex;

struct MalvarScratch {
    cv::Mat rgb;

    bool matches(const cv::Size& size) const {
        return !rgb.empty() && rgb.size() == size && rgb.type() == CV_32FC3;
    }

    void ensure(const cv::Size& size) {
        if (!matches(size)) {
            rgb.create(size, CV_32FC3);
        }
    }
};

MalvarScratch gMalvarScratch;
std::mutex gMalvarScratchMutex;

struct RcdScratch {
    cv::Mat green;
    cv::Mat rgb;

    bool matches(const cv::Size& size) const {
        return !green.empty() && green.size() == size && green.type() == CV_32FC1 &&
               !rgb.empty() && rgb.size() == size && rgb.type() == CV_32FC3;
    }

    void ensure(const cv::Size& size) {
        if (green.empty() || green.size() != size || green.type() != CV_32FC1) {
            green.create(size, CV_32FC1);
        }
        if (rgb.empty() || rgb.size() != size || rgb.type() != CV_32FC3) {
            rgb.create(size, CV_32FC3);
        }
    }
};

RcdScratch gRcdScratch;
std::mutex gRcdScratchMutex;

struct AmazeScratch {
    cv::Mat green;
    cv::Mat rgb;

    bool matches(const cv::Size& size) const {
        return !green.empty() && green.size() == size && green.type() == CV_32FC1 &&
               !rgb.empty() && rgb.size() == size && rgb.type() == CV_32FC3;
    }

    void ensure(const cv::Size& size) {
        if (green.empty() || green.size() != size || green.type() != CV_32FC1) green.create(size, CV_32FC1);
        if (rgb.empty() || rgb.size() != size || rgb.type() != CV_32FC3) rgb.create(size, CV_32FC3);
    }
};

AmazeScratch gAmazeScratch;
std::mutex gAmazeScratchMutex;

struct BilinearScratch {
    cv::Mat rgb;

    bool matches(const cv::Size& size) const {
        return !rgb.empty() && rgb.size() == size && rgb.type() == CV_32FC3;
    }

    void ensure(const cv::Size& size) {
        if (!matches(size)) {
            rgb.create(size, CV_32FC3);
        }
    }
};

BilinearScratch gBilinearScratch;
std::mutex gBilinearScratchMutex;

} // namespace

DemosaicResolution resolveDemosaicMode(int requestedModeValue) {
    DemosaicResolution result{};
    if (requestedModeValue == static_cast<int>(DemosaicMode::NormalMalvar2004)) {
        return result;
    }

    if (requestedModeValue == static_cast<int>(DemosaicMode::Bilinear)) {
        // Bridge value 3 is retained for profile compatibility while the product path is
        // cut over from legacy Bilinear to BnCam RCD Inspired.
        result.requestedMode = DemosaicMode::Bilinear;
        result.algorithm = DemosaicAlgorithm::RcdInspired;
        result.reason = "legacy_slot_3_forces_rcd_inspired";
        return result;
    }

    if (requestedModeValue == static_cast<int>(DemosaicMode::QualityMenon2007)) {
        // Bridge value 2 is retained for profile compatibility while the product path is
        // cut over from legacy Menon to BnCam AMAZE Inspired.
        result.requestedMode = DemosaicMode::QualityMenon2007;
        result.algorithm = DemosaicAlgorithm::AmazeInspired;
        result.reason = "legacy_slot_2_forces_amaze_inspired";
        return result;
    }

    if (requestedModeValue == static_cast<int>(DemosaicMode::Auto)) {
        result.requestedMode = DemosaicMode::Auto;
        result.reason = "auto_pending_scene_analysis";
        return result;
    }

    result.reason = "invalid_mode_forced_malvar_2004";
    result.fallbackOccurred = true;
    result.fallbackReason = "invalid_native_demosaic_mode";
    return result;
}

bool menonRuntimeValidated() {
    static std::once_flag validationOnce;
    static bool validationPassed = false;
    std::call_once(validationOnce, [] {
        validationPassed = validateMenon2007Implementation().passed;
    });
    return validationPassed;
}

bool amazeRuntimeValidated() {
    static std::once_flag validationOnce;
    static bool validationPassed = false;
    std::call_once(validationOnce, [] {
        validationPassed = validateAmazeInspiredImplementation().passed;
    });
    return validationPassed;
}

bool rcdRuntimeValidated() {
    static std::once_flag validationOnce;
    static bool validationPassed = false;
    std::call_once(validationOnce, [] {
        validationPassed = validateRcdInspiredImplementation().passed;
    });
    return validationPassed;
}

bool malvarRuntimeValidated() {
    static std::once_flag validationOnce;
    static bool validationPassed = false;
    std::call_once(validationOnce, [] {
        validationPassed = validateMalvar2004Implementation().passed;
    });
    return validationPassed;
}

DemosaicResolution resolveDemosaicForSceneMetrics(
        int requestedModeValue,
        const AutoDemosaicSceneMetrics& metrics,
        const AutoDemosaicContext& context
) {
    DemosaicResolution result = resolveDemosaicMode(requestedModeValue);
    if (requestedModeValue == static_cast<int>(DemosaicMode::QualityMenon2007)) {
        if (!amazeRuntimeValidated()) {
            result.algorithm = DemosaicAlgorithm::Malvar2004;
            result.reason = "AMAZE_REQUESTED_BUT_VALIDATION_FAILED_FALLBACK_TO_MALVAR";
            result.fallbackOccurred = true;
            result.fallbackReason = "amaze_runtime_validation_failed";
        }
        return result;
    }
    if (requestedModeValue != static_cast<int>(DemosaicMode::Auto)) return result;

    result.requestedMode = DemosaicMode::Auto;
    result.autoSceneAnalysisUsed = true;
    result.autoSampleCount = metrics.sampleCount;
    result.autoMedianSignal = metrics.medianSignal;
    result.autoMeanGradient = metrics.meanGradient;
    result.autoP90Gradient = metrics.p90Gradient;
    result.autoEdgeFraction = metrics.edgeFraction;
    result.autoCoherentEdgeFraction = metrics.coherentEdgeFraction;
    result.autoLowSignalFraction = metrics.lowSignalFraction;

    const bool malvarReady = malvarRuntimeValidated();
    const bool rcdReady = rcdRuntimeValidated();
    const bool amazeReady = amazeRuntimeValidated();

    // Invalid scene metrics are the only place Auto uses a deterministic fallback. Prefer the
    // BnCam default (RCD Inspired) when its reference validates, then Malvar, then AMAZE.
    if (!metrics.valid) {
        if (rcdReady) {
            result.algorithm = DemosaicAlgorithm::RcdInspired;
            result.reason = "auto_metrics_unavailable_default_rcd_inspired";
        } else if (malvarReady) {
            result.algorithm = DemosaicAlgorithm::Malvar2004;
            result.reason = "auto_metrics_unavailable_rcd_invalid_fallback_malvar";
        } else {
            result.algorithm = DemosaicAlgorithm::AmazeInspired;
            result.reason = "auto_metrics_unavailable_rcd_malvar_invalid_fallback_amaze";
        }
        result.fallbackOccurred = true;
        result.fallbackReason = "auto_scene_metrics_unavailable";
        result.autoSignals = "scene_metrics=unavailable";
        return result;
    }

    const auto unit = [](float value) { return std::clamp(value, 0.0f, 1.0f); };
    const float isoStops = context.captureIso > 100
            ? static_cast<float>(std::log2(static_cast<double>(context.captureIso) / 100.0))
            : 0.0f;
    const float isoRiskFallback = unit(isoStops / 5.0f); // metadata fallback only.
    const bool physicalNoiseKnown = context.physicalNoiseKnown &&
            std::isfinite(context.noiseSigmaY) && context.noiseSigmaY > 0.0f &&
            std::isfinite(context.physicalNoisePressure);
    const float sigmaY = physicalNoiseKnown ? std::max(1.0e-6f, context.noiseSigmaY) : 0.0f;
    const float physicalPressure = physicalNoiseKnown
            ? unit(context.physicalNoisePressure) : isoRiskFallback;
    const float darkness = unit((0.18f - metrics.medianSignal) / 0.18f);
    const float lowSignal = unit(metrics.lowSignalFraction);
    const float noiseRisk = physicalNoiseKnown
            ? unit(0.70f * physicalPressure + 0.20f * lowSignal + 0.10f * darkness)
            : unit(0.55f * isoRiskFallback + 0.30f * lowSignal + 0.15f * darkness);

    // The raw-finalize scene metrics are intentionally compact and use fixed absolute
    // thresholds. Debias them here when physical sigma is known so residual sensor grain
    // cannot masquerade as high-frequency scene detail in Auto. Coherent edges get the
    // weakest attenuation because repeated same-sign structure is already strong evidence.
    const float p90Sigma = physicalNoiseKnown ? metrics.p90Gradient / sigmaY : 8.0f;
    const float gradientReliability = physicalNoiseKnown
            ? demosaicSmoothstep(1.35f, 4.25f, p90Sigma) : 1.0f;
    const float coherentReliability = physicalNoiseKnown
            ? demosaicSmoothstep(1.00f, 2.75f, p90Sigma) : 1.0f;
    const float gradientNoiseFloor = physicalNoiseKnown ? 1.25f * sigmaY : 0.0f;
    const float edgeDetail = unit(metrics.edgeFraction / 0.18f) * gradientReliability;
    const float coherentDetail = unit(metrics.coherentEdgeFraction / 0.12f) * coherentReliability;
    const float gradientDetail = unit(std::max(0.0f, metrics.p90Gradient - gradientNoiseFloor) / 0.18f) *
            gradientReliability;
    const float detailConfidence = unit(
            0.35f * edgeDetail + 0.40f * coherentDetail + 0.25f * gradientDetail);
    const float signalQuality = unit((metrics.medianSignal - 0.04f) / 0.30f);
    const float temporalReliability = context.temporalStabilityKnown
            ? unit(context.temporalObserverConfidence / 0.20f) : 0.0f;
    const float temporalStaticRaw = context.temporalStabilityKnown
            ? unit(context.temporalStaticConfidence) : 0.0f;
    // Pull low-confidence temporal estimates toward neutral instead of treating an uncertain
    // observer as proof that the scene is moving or static.
    const float temporalStaticConfidence = context.temporalStabilityKnown
            ? unit(0.50f + (temporalStaticRaw - 0.50f) * temporalReliability) : 0.0f;
    const float temporalMotionRisk = context.temporalStabilityKnown
            ? unit(1.0f - temporalStaticConfidence) : 0.0f;
    const float coarseMotionRisk = context.temporalStabilityKnown
            ? temporalMotionRisk
            : (context.motionRiskKnown ? (context.highMotionRisk ? 1.0f : 0.0f) : 0.15f);
    const float focusStability = context.focusStabilityKnown
            ? unit(context.focusStabilityConfidence) : 0.0f;
    const float focusSharpness = context.focusStabilityKnown
            ? unit(context.focusSharpConfidence) : 0.0f;
    const float focusMotionRisk = context.focusStabilityKnown
            ? unit(context.focusMotionRisk) : 0.0f;
    const float motionRisk = std::max(coarseMotionRisk, focusMotionRisk);
    const float personConfidence = context.personEvidenceKnown
            ? unit(context.personConfidence) : 0.0f;
    const float integrityRisk = unit(
            (context.cfaCertain ? 0.0f : 0.45f) +
            (context.rawColorAuditFailed ? 0.35f : 0.0f) +
            (context.greenPlaneAnomaly ? 0.20f : 0.0f));
    const float cfaBandChromaPressure = context.cfaChromaEvidenceKnown
            ? unit(0.45f * context.cfaFineCorrectionConfidence +
                   0.35f * context.cfaMidCorrectionConfidence +
                   0.20f * context.cfaLowCorrectionConfidence)
            : 0.0f;
    const float cfaOpponentAverage = context.cfaChromaEvidenceKnown
            ? 0.5f * (unit(context.cfaRedOpponentCorrectionConfidence) +
                      unit(context.cfaBlueOpponentCorrectionConfidence))
            : 0.0f;
    const float cfaOpponentPeak = context.cfaChromaEvidenceKnown
            ? std::max(unit(context.cfaRedOpponentCorrectionConfidence),
                       unit(context.cfaBlueOpponentCorrectionConfidence))
            : 0.0f;
    const float cfaOpponentPressure = unit(0.65f * cfaOpponentPeak + 0.35f * cfaOpponentAverage);
    const float cfaStructureRelief = context.cfaChromaEvidenceKnown
            ? 1.0f - 0.25f * unit(context.cfaStructureProtection)
            : 1.0f;
    const float cfaChromaRisk = context.cfaChromaEvidenceKnown
            ? unit((0.60f * cfaBandChromaPressure + 0.40f * cfaOpponentPressure) *
                   cfaStructureRelief)
            : 0.0f;
    const float memoryRisk = context.memoryPressureHigh ? 1.0f : 0.0f;

    // Independent suitability scores. No algorithm inherits another algorithm's result and no
    // hard-coded "try Malvar first" branch exists. The weights encode each route's intended
    // character and are deliberately observable/tunable rather than hidden if/else gates.
    float malvarScore =
            0.42f +
            0.42f * noiseRisk +
            0.18f * cfaChromaRisk +
            0.18f * motionRisk +
            0.10f * focusMotionRisk -
            0.04f * focusStability * focusSharpness +
            0.02f * personConfidence +
            0.06f * personConfidence * noiseRisk -
            0.03f * temporalStaticConfidence * detailConfidence * signalQuality +
            0.18f * integrityRisk +
            0.10f * memoryRisk -
            0.10f * detailConfidence * signalQuality;

    float rcdScore =
            0.50f +
            0.28f * detailConfidence +
            0.10f * signalQuality +
            0.08f * focusStability +
            0.06f * focusSharpness +
            0.10f * personConfidence +
            0.04f * personConfidence * (1.0f - noiseRisk) +
            0.06f * temporalStaticConfidence +
            0.03f * cfaChromaRisk -
            0.18f * noiseRisk -
            0.10f * motionRisk -
            0.12f * integrityRisk -
            0.06f * memoryRisk;

    float amazeScore =
            0.38f +
            0.48f * detailConfidence +
            0.18f * signalQuality +
            0.18f * focusStability +
            0.14f * focusSharpness +
            0.04f * personConfidence * detailConfidence * signalQuality -
            0.06f * personConfidence * noiseRisk +
            0.14f * temporalStaticConfidence -
            0.22f * cfaChromaRisk -
            0.38f * noiseRisk -
            0.38f * motionRisk -
            0.20f * integrityRisk -
            0.10f * memoryRisk;

    if (!malvarReady) malvarScore = -1.0f;
    if (!rcdReady) rcdScore = -1.0f;
    if (!amazeReady) amazeScore = -1.0f;

    result.autoMalvarScore = malvarScore;
    result.autoRcdScore = rcdScore;
    result.autoAmazeScore = amazeScore;
    result.autoCfaChromaRisk = cfaChromaRisk;

    struct Candidate {
        DemosaicAlgorithm algorithm;
        float score;
        const char* name;
        const char* reason;
    };
    std::array<Candidate, 3> candidates{{
            {DemosaicAlgorithm::RcdInspired, rcdScore, "RCD_INSPIRED", "auto_score_winner_rcd_inspired"},
            {DemosaicAlgorithm::Malvar2004, malvarScore, "MALVAR_INSPIRED", "auto_score_winner_malvar_inspired"},
            {DemosaicAlgorithm::AmazeInspired, amazeScore, "AMAZE_INSPIRED", "auto_score_winner_amaze_inspired"}
    }};
    std::stable_sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return a.score > b.score;
    });

    result.algorithm = candidates[0].algorithm;
    result.reason = candidates[0].reason;
    result.autoRunnerUp = candidates[1].name;
    result.autoScoreDelta = candidates[0].score - candidates[1].score;
    if (candidates[0].score < 0.0f) {
        // Catastrophic validation condition: retain RCD as the explicit BnCam default identity,
        // but mark the resolver result as failed so downstream debug cannot mistake it for a
        // confidence-based Auto decision.
        result.algorithm = DemosaicAlgorithm::RcdInspired;
        result.reason = "auto_no_validated_demosaic_default_identity_rcd";
        result.fallbackOccurred = true;
        result.fallbackReason = "all_demosaic_reference_validations_failed";
    }

    std::ostringstream signals;
    signals << std::fixed << std::setprecision(4)
            << "iso=" << (context.captureIso > 0 ? std::to_string(context.captureIso) : "unknown")
            << ",noiseSource=" << (physicalNoiseKnown ? "PHYSICAL_SO" : "ISO_FALLBACK")
            << ",sigmaY=" << sigmaY
            << ",physicalPressure=" << physicalPressure
            << ",p90Sigma=" << p90Sigma
            << ",noiseRisk=" << noiseRisk
            << ",detailConfidence=" << detailConfidence
            << ",signalQuality=" << signalQuality
            << ",motionRisk=" << motionRisk
            << ",focusStabilityKnown=" << (context.focusStabilityKnown ? "true" : "false")
            << ",focusStability=" << focusStability
            << ",focusSharpness=" << focusSharpness
            << ",focusMotionRisk=" << focusMotionRisk
            << ",focusVelocityDps=" << context.focusVelocityDioptersPerSec
            << ",predictiveAfConfidence=" << context.predictiveAfConfidence
            << ",personEvidenceKnown=" << (context.personEvidenceKnown ? "true" : "false")
            << ",personConfidence=" << personConfidence
            << ",detectedFaceCount=" << context.detectedFaceCount
            << ",maxFaceCoverage=" << context.maxFaceCoverage
            << ",temporalStabilityKnown=" << (context.temporalStabilityKnown ? "true" : "false")
            << ",temporalStaticRaw=" << temporalStaticRaw
            << ",temporalStatic=" << temporalStaticConfidence
            << ",temporalMotionRisk=" << temporalMotionRisk
            << ",temporalObserverConfidence=" << context.temporalObserverConfidence
            << ",temporalMotionAcceptance=" << context.temporalMotionAcceptance
            << ",temporalAcceptedPairs=" << context.temporalAcceptedPairs
            << ",integrityRisk=" << integrityRisk
            << ",cfaChromaEvidenceKnown=" << (context.cfaChromaEvidenceKnown ? "true" : "false")
            << ",cfaBandChromaPressure=" << cfaBandChromaPressure
            << ",cfaOpponentPressure=" << cfaOpponentPressure
            << ",cfaChromaRisk=" << cfaChromaRisk
            << ",memoryRisk=" << memoryRisk
            // Delta 38: exact score-factor audit. These terms mirror the score equations above
            // and do not participate in the decision; they only explain it after the fact.
            << ",malvarFactors={base=0.4200"
            << ",noise=" << (0.42f * noiseRisk + 0.18f * cfaChromaRisk)
            << ",motion=" << (0.18f * motionRisk + 0.10f * focusMotionRisk)
            << ",focus=" << (-0.04f * focusStability * focusSharpness)
            << ",portrait=" << (0.02f * personConfidence + 0.06f * personConfidence * noiseRisk)
            << ",staticDetail=" << (-0.03f * temporalStaticConfidence * detailConfidence * signalQuality)
            << ",integrityMemory=" << (0.18f * integrityRisk + 0.10f * memoryRisk)
            << ",detail=" << (-0.10f * detailConfidence * signalQuality) << "}"
            << ",rcdFactors={base=0.5000"
            << ",detailSignal=" << (0.28f * detailConfidence + 0.10f * signalQuality)
            << ",focus=" << (0.08f * focusStability + 0.06f * focusSharpness)
            << ",portrait=" << (0.10f * personConfidence + 0.04f * personConfidence * (1.0f - noiseRisk))
            << ",static=" << (0.06f * temporalStaticConfidence)
            << ",chroma=" << (0.03f * cfaChromaRisk)
            << ",noiseMotion=" << (-0.18f * noiseRisk - 0.10f * motionRisk)
            << ",integrityMemory=" << (-0.12f * integrityRisk - 0.06f * memoryRisk) << "}"
            << ",amazeFactors={base=0.3800"
            << ",detailSignal=" << (0.48f * detailConfidence + 0.18f * signalQuality)
            << ",focus=" << (0.18f * focusStability + 0.14f * focusSharpness)
            << ",portrait=" << (0.04f * personConfidence * detailConfidence * signalQuality -
                    0.06f * personConfidence * noiseRisk)
            << ",static=" << (0.14f * temporalStaticConfidence)
            << ",chroma=" << (-0.22f * cfaChromaRisk)
            << ",noiseMotion=" << (-0.38f * noiseRisk - 0.38f * motionRisk)
            << ",integrityMemory=" << (-0.20f * integrityRisk - 0.10f * memoryRisk) << "}"
            << ",malvarScore=" << malvarScore
            << ",rcdScore=" << rcdScore
            << ",amazeScore=" << amazeScore
            << ",runnerUp=" << result.autoRunnerUp
            << ",scoreDelta=" << result.autoScoreDelta
            << ",malvarReady=" << (malvarReady ? "true" : "false")
            << ",rcdReady=" << (rcdReady ? "true" : "false")
            << ",amazeReady=" << (amazeReady ? "true" : "false");
    result.autoSignals = signals.str();
    return result;
}

DemosaicResolution resolveDemosaicForFrame(
        int requestedModeValue,
        const cv::Mat& normalizedBayer,
        const AutoDemosaicContext& context
) {
    const AutoDemosaicSceneMetrics metrics =
            requestedModeValue == static_cast<int>(DemosaicMode::Auto)
            ? analyzeAutoDemosaicScene(normalizedBayer)
            : AutoDemosaicSceneMetrics{};
    return resolveDemosaicForSceneMetrics(requestedModeValue, metrics, context);
}

void recordMenonDemosaicTimeMs(float elapsedMs) {
    if (std::isfinite(elapsedMs) && elapsedMs >= 0.0f) {
        gLastMenonTimeMs.store(elapsedMs, std::memory_order_relaxed);
    }
}

const char* demosaicModeName(DemosaicMode mode) {
    switch (mode) {
        case DemosaicMode::Auto: return "AUTO";
        case DemosaicMode::Bilinear: return "RCD_INSPIRED_LEGACY_SLOT_3";
        case DemosaicMode::NormalMalvar2004: return "MALVAR_2004";
        case DemosaicMode::QualityMenon2007: return "AMAZE_INSPIRED_LEGACY_SLOT_2";
        default: return "MALVAR_2004";
    }
}

const char* demosaicAlgorithmName(DemosaicAlgorithm algorithm) {
    switch (algorithm) {
        case DemosaicAlgorithm::Bilinear: return "BILINEAR_REFERENCE_ONLY";
        case DemosaicAlgorithm::RcdInspired: return "RCD_INSPIRED";
        case DemosaicAlgorithm::AmazeInspired: return "AMAZE_INSPIRED";
        case DemosaicAlgorithm::Malvar2004: return "MALVAR_2004";
        case DemosaicAlgorithm::Menon2007: return "MENON_2007_DDFAPD";
        default: return "MALVAR_2004";
    }
}

cv::Mat demosaicBilinearToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats
) {
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    const int pattern = safeCfaPattern(effectiveCfaPattern);

    std::unique_lock<std::mutex> scratchLock(gBilinearScratchMutex);
    const bool scratchReused = gBilinearScratch.matches(normalizedBayer.size());
    gBilinearScratch.ensure(normalizedBayer.size());
    cv::Mat rgb = gBilinearScratch.rgb;

    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
    }

    const auto kernelStart = DemosaicClock::now();
    const int rows = normalizedBayer.rows;
    const int cols = normalizedBayer.cols;

    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            const float* rowCurr = normalizedBayer.ptr<float>(y);

            const bool isBorderRow = (y == 0 || y == rows - 1);

            if (!isBorderRow) {
                const float* rowPrev = normalizedBayer.ptr<float>(y - 1);
                const float* rowNext = normalizedBayer.ptr<float>(y + 1);

                // Border pixels for column x=0
                {
                    const auto rbBorder = [&](int channel) {
                        const float center = 4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y, channel);
                        const float axial = 2.0f * (sampleBilinearChannelReflect(normalizedBayer, pattern, -1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, 1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y - 1, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y + 1, channel));
                        const float diagonal = sampleBilinearChannelReflect(normalizedBayer, pattern, -1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, 1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, -1, y + 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, 1, y + 1, channel);
                        return 0.25f * (center + axial + diagonal);
                    };
                    const float gBorder = 0.25f * (4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, -1, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, 1, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y - 1, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, 0, y + 1, 1));
                    out[0] = cv::Vec3f(finiteSceneLinear(rbBorder(0)), finiteSceneLinear(gBorder), finiteSceneLinear(rbBorder(2)));
                }

                // Fast interior loop (99.9% of image pixels)
                for (int x = 1; x < cols - 1; ++x) {
                    const int color = cfaColorAt(pattern, x, y);
                    const float center = rowCurr[x];
                    float r;
                    float g;
                    float b;

                    if (color == 0) { // Red sample
                        r = center;
                        g = 0.25f * (rowPrev[x] + rowNext[x] + rowCurr[x - 1] + rowCurr[x + 1]);
                        b = 0.25f * (rowPrev[x - 1] + rowPrev[x + 1] + rowNext[x - 1] + rowNext[x + 1]);
                    } else if (color == 2) { // Blue sample
                        b = center;
                        g = 0.25f * (rowPrev[x] + rowNext[x] + rowCurr[x - 1] + rowCurr[x + 1]);
                        r = 0.25f * (rowPrev[x - 1] + rowPrev[x + 1] + rowNext[x - 1] + rowNext[x + 1]);
                    } else { // Green sample
                        g = center;
                        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                        if (horizontalRed) {
                            r = 0.5f * (rowCurr[x - 1] + rowCurr[x + 1]);
                            b = 0.5f * (rowPrev[x] + rowNext[x]);
                        } else {
                            b = 0.5f * (rowCurr[x - 1] + rowCurr[x + 1]);
                            r = 0.5f * (rowPrev[x] + rowNext[x]);
                        }
                    }

                    out[x] = cv::Vec3f(
                            finiteSceneLinear(r),
                            finiteSceneLinear(g),
                            finiteSceneLinear(b)
                    );
                }

                // Border pixels for column x=cols-1
                {
                    const int lastX = cols - 1;
                    const auto rbBorder = [&](int channel) {
                        const float center = 4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y, channel);
                        const float axial = 2.0f * (sampleBilinearChannelReflect(normalizedBayer, pattern, lastX - 1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, lastX + 1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y - 1, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y + 1, channel));
                        const float diagonal = sampleBilinearChannelReflect(normalizedBayer, pattern, lastX - 1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, lastX + 1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, lastX - 1, y + 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, lastX + 1, y + 1, channel);
                        return 0.25f * (center + axial + diagonal);
                    };
                    const float gBorder = 0.25f * (4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, lastX - 1, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, lastX + 1, y, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y - 1, 1) +
                                                   sampleBilinearChannelReflect(normalizedBayer, pattern, lastX, y + 1, 1));
                    out[lastX] = cv::Vec3f(finiteSceneLinear(rbBorder(0)), finiteSceneLinear(gBorder), finiteSceneLinear(rbBorder(2)));
                }
            } else {
                // Border rows (y=0 or y=rows-1)
                for (int x = 0; x < cols; ++x) {
                    const auto rb = [&](int channel) {
                        const float center = 4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, x, y, channel);
                        const float axial = 2.0f * (sampleBilinearChannelReflect(normalizedBayer, pattern, x - 1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, x + 1, y, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, x, y - 1, channel) +
                                                    sampleBilinearChannelReflect(normalizedBayer, pattern, x, y + 1, channel));
                        const float diagonal = sampleBilinearChannelReflect(normalizedBayer, pattern, x - 1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, x + 1, y - 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, x - 1, y + 1, channel) +
                                               sampleBilinearChannelReflect(normalizedBayer, pattern, x + 1, y + 1, channel);
                        return 0.25f * (center + axial + diagonal);
                    };
                    const float green = 0.25f * (4.0f * sampleBilinearChannelReflect(normalizedBayer, pattern, x, y, 1) +
                                                 sampleBilinearChannelReflect(normalizedBayer, pattern, x - 1, y, 1) +
                                                 sampleBilinearChannelReflect(normalizedBayer, pattern, x + 1, y, 1) +
                                                 sampleBilinearChannelReflect(normalizedBayer, pattern, x, y - 1, 1) +
                                                 sampleBilinearChannelReflect(normalizedBayer, pattern, x, y + 1, 1));
                    out[x] = cv::Vec3f(finiteSceneLinear(rb(0)), finiteSceneLinear(green), finiteSceneLinear(rb(2)));
                }
            }
        }
    });
    if (stats != nullptr) {
        stats->kernelMs = elapsedDemosaicMs(kernelStart);
        stats->finalizeMs = 0.0f;
        stats->workingBufferBytesEstimate =
                static_cast<uint64_t>(normalizedBayer.total()) * 16u;
        stats->allocatedScratchBytesThisShot = scratchReused
                ? 0u
                : static_cast<uint64_t>(normalizedBayer.total()) * 12u;
    }
    return rgb;
}

cv::Mat demosaicMalvar2004ToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats,
        const DemosaicCfaEvidence* cfaEvidence,
        const DemosaicNoiseContext* noiseContext
) {
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    const int pattern = safeCfaPattern(effectiveCfaPattern);

    std::unique_lock<std::mutex> scratchLock(gMalvarScratchMutex);
    const bool scratchReused = gMalvarScratch.matches(normalizedBayer.size());
    gMalvarScratch.ensure(normalizedBayer.size());
    cv::Mat rgb = gMalvarScratch.rgb;

    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
    }
    const auto kernelStart = DemosaicClock::now();
    const int rows = normalizedBayer.rows;
    const int cols = normalizedBayer.cols;
    const float redChromaRisk = malvarNoiseAwareChromaRisk(cfaEvidence, 0);
    const float blueChromaRisk = malvarNoiseAwareChromaRisk(cfaEvidence, 2);

    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            const float* rowCurr = normalizedBayer.ptr<float>(y);
            const bool isBorderRow = (y < 2 || y >= rows - 2);

            if (!isBorderRow) {
                const float* rowPrev2 = normalizedBayer.ptr<float>(y - 2);
                const float* rowPrev1 = normalizedBayer.ptr<float>(y - 1);
                const float* rowNext1 = normalizedBayer.ptr<float>(y + 1);
                const float* rowNext2 = normalizedBayer.ptr<float>(y + 2);

                // Boundary columns (x=0, x=1)
                for (int x = 0; x < 2; ++x) {
                    const int color = cfaColorAt(pattern, x, y);
                    const float center = sampleClamped(normalizedBayer, x, y);
                    float r, g, b;
                    if (color == 0) {
                        r = center;
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = oppositeAtRedOrBlue(normalizedBayer, x, y);
                    } else if (color == 2) {
                        r = oppositeAtRedOrBlue(normalizedBayer, x, y);
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = center;
                    } else {
                        g = center;
                        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                        r = horizontalRed ? chromaAtGreenHorizontal(normalizedBayer, x, y) : chromaAtGreenVertical(normalizedBayer, x, y);
                        b = horizontalRed ? chromaAtGreenVertical(normalizedBayer, x, y) : chromaAtGreenHorizontal(normalizedBayer, x, y);
                    }
                    r = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 0, r, redChromaRisk, noiseContext);
                    b = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 2, b, blueChromaRisk, noiseContext);
                    out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
                }

                // Fast interior loop (99.8% of image pixels)
                for (int x = 2; x < cols - 2; ++x) {
                    const int color = cfaColorAt(pattern, x, y);
                    const float c = rowCurr[x];
                    const float axial1 = rowCurr[x - 1] + rowCurr[x + 1] + rowPrev1[x] + rowNext1[x];
                    const float axial2 = rowCurr[x - 2] + rowCurr[x + 2] + rowPrev2[x] + rowNext2[x];
                    const float greenRedOrBlue = (4.0f * c + 2.0f * axial1 - axial2) * MHC_SCALE;

                    const float diagonals = rowPrev1[x - 1] + rowPrev1[x + 1] + rowNext1[x - 1] + rowNext1[x + 1];
                    const float oppositeRedOrBlue = (6.0f * c + 2.0f * diagonals - 1.5f * axial2) * MHC_SCALE;

                    const float chromaH = (5.0f * c + 4.0f * (rowCurr[x - 1] + rowCurr[x + 1]) - (rowCurr[x - 2] + rowCurr[x + 2]) - diagonals + 0.5f * (rowPrev2[x] + rowNext2[x])) * MHC_SCALE;
                    const float chromaV = (5.0f * c + 4.0f * (rowPrev1[x] + rowNext1[x]) - (rowPrev2[x] + rowNext2[x]) - diagonals + 0.5f * (rowCurr[x - 2] + rowCurr[x + 2])) * MHC_SCALE;

                    float r, g, b;
                    if (color == 0) { // Red sample
                        r = c;
                        g = greenRedOrBlue;
                        b = oppositeRedOrBlue;
                    } else if (color == 2) { // Blue sample
                        r = oppositeRedOrBlue;
                        g = greenRedOrBlue;
                        b = c;
                    } else { // Green sample
                        g = c;
                        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                        r = horizontalRed ? chromaH : chromaV;
                        b = horizontalRed ? chromaV : chromaH;
                    }

                    out[x] = cv::Vec3f(
                            finiteSceneLinear(r),
                            finiteSceneLinear(g),
                            finiteSceneLinear(b)
                    );
                }

                // Boundary columns (x=cols-2, x=cols-1)
                for (int x = cols - 2; x < cols; ++x) {
                    const int color = cfaColorAt(pattern, x, y);
                    const float center = sampleClamped(normalizedBayer, x, y);
                    float r, g, b;
                    if (color == 0) {
                        r = center;
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = oppositeAtRedOrBlue(normalizedBayer, x, y);
                    } else if (color == 2) {
                        r = oppositeAtRedOrBlue(normalizedBayer, x, y);
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = center;
                    } else {
                        g = center;
                        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                        r = horizontalRed ? chromaAtGreenHorizontal(normalizedBayer, x, y) : chromaAtGreenVertical(normalizedBayer, x, y);
                        b = horizontalRed ? chromaAtGreenVertical(normalizedBayer, x, y) : chromaAtGreenHorizontal(normalizedBayer, x, y);
                    }
                    r = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 0, r, redChromaRisk, noiseContext);
                    b = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 2, b, blueChromaRisk, noiseContext);
                    out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
                }
            } else {
                // Boundary rows (y < 2 or y >= rows - 2)
                for (int x = 0; x < cols; ++x) {
                    const int color = cfaColorAt(pattern, x, y);
                    const float center = sampleClamped(normalizedBayer, x, y);
                    float r, g, b;
                    if (color == 0) {
                        r = center;
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = oppositeAtRedOrBlue(normalizedBayer, x, y);
                    } else if (color == 2) {
                        r = oppositeAtRedOrBlue(normalizedBayer, x, y);
                        g = greenAtRedOrBlue(normalizedBayer, x, y);
                        b = center;
                    } else {
                        g = center;
                        const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                        r = horizontalRed ? chromaAtGreenHorizontal(normalizedBayer, x, y) : chromaAtGreenVertical(normalizedBayer, x, y);
                        b = horizontalRed ? chromaAtGreenVertical(normalizedBayer, x, y) : chromaAtGreenHorizontal(normalizedBayer, x, y);
                    }
                    r = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 0, r, redChromaRisk, noiseContext);
                    b = malvarStabilizeMissingColor(
                            normalizedBayer, pattern, x, y, 2, b, blueChromaRisk, noiseContext);
                    out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
                }
            }
        }
    });
    if (stats != nullptr) {
        stats->kernelMs = elapsedDemosaicMs(kernelStart);
        stats->finalizeMs = 0.0f;
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) * 16u;
        stats->allocatedScratchBytesThisShot = scratchReused
                ? 0u
                : static_cast<uint64_t>(normalizedBayer.total()) * 12u;
    }
    return rgb;
}

cv::Mat demosaicRcdInspiredToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats,
        const DemosaicCfaEvidence* cfaEvidence,
        const DemosaicNoiseContext* noiseContext
) {
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    const int pattern = safeCfaPattern(effectiveCfaPattern);

    std::unique_lock<std::mutex> scratchLock(gRcdScratchMutex);
    const bool scratchReused = gRcdScratch.matches(normalizedBayer.size());
    gRcdScratch.ensure(normalizedBayer.size());
    cv::Mat green = gRcdScratch.green;
    cv::Mat rgb = gRcdScratch.rgb;
    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) *
                (sizeof(float) + 3u * sizeof(float));
        stats->allocatedScratchBytesThisShot = scratchReused ? 0u : stats->workingBufferBytesEstimate;
    }

    const auto kernelStart = DemosaicClock::now();
    const int rows = normalizedBayer.rows;
    const int cols = normalizedBayer.cols;
    const float redChromaRisk = rcdNoiseAwareChromaRisk(cfaEvidence, 0);
    const float blueChromaRisk = rcdNoiseAwareChromaRisk(cfaEvidence, 2);

    // Pass 1: preserve sampled green and reconstruct green at R/B sites. Directional weights are
    // derived from a stable low-pass intensity proxy; ratio correction is deliberately bounded.
    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* greenRow = green.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const float center = normalizedBayer.ptr<float>(y)[x];
                if (color == 1) {
                    greenRow[x] = finiteSceneLinear(center);
                    continue;
                }

                const float centerLp = rcdLowPass(normalizedBayer, x, y);
                const float west = rcdRatioCorrectedGreenCandidate(
                        sampleClamped(normalizedBayer, x - 1, y), centerLp,
                        rcdLowPass(normalizedBayer, x - 2, y), noiseContext);
                const float east = rcdRatioCorrectedGreenCandidate(
                        sampleClamped(normalizedBayer, x + 1, y), centerLp,
                        rcdLowPass(normalizedBayer, x + 2, y), noiseContext);
                const float north = rcdRatioCorrectedGreenCandidate(
                        sampleClamped(normalizedBayer, x, y - 1), centerLp,
                        rcdLowPass(normalizedBayer, x, y - 2), noiseContext);
                const float south = rcdRatioCorrectedGreenCandidate(
                        sampleClamped(normalizedBayer, x, y + 1), centerLp,
                        rcdLowPass(normalizedBayer, x, y + 2), noiseContext);

                const float horizontalGradient =
                        std::abs(rcdLowPass(normalizedBayer, x - 2, y) -
                                 rcdLowPass(normalizedBayer, x + 2, y)) +
                        0.5f * std::abs(sampleClamped(normalizedBayer, x - 1, y) -
                                        sampleClamped(normalizedBayer, x + 1, y));
                const float verticalGradient =
                        std::abs(rcdLowPass(normalizedBayer, x, y - 2) -
                                 rcdLowPass(normalizedBayer, x, y + 2)) +
                        0.5f * std::abs(sampleClamped(normalizedBayer, x, y - 1) -
                                        sampleClamped(normalizedBayer, x, y + 1));
                const float wH = rcdDirectionalWeight(horizontalGradient, noiseContext);
                const float wV = rcdDirectionalWeight(verticalGradient, noiseContext);
                float reconstructed = (wH * 0.5f * (west + east) +
                                       wV * 0.5f * (north + south)) /
                                      std::max(1.0e-8f, wH + wV);

                const float localMin = std::min({
                        sampleClamped(normalizedBayer, x - 1, y),
                        sampleClamped(normalizedBayer, x + 1, y),
                        sampleClamped(normalizedBayer, x, y - 1),
                        sampleClamped(normalizedBayer, x, y + 1)});
                const float localMax = std::max({
                        sampleClamped(normalizedBayer, x - 1, y),
                        sampleClamped(normalizedBayer, x + 1, y),
                        sampleClamped(normalizedBayer, x, y - 1),
                        sampleClamped(normalizedBayer, x, y + 1)});
                const float guard = 0.10f * std::max(1.0e-5f, localMax - localMin);
                reconstructed = std::clamp(reconstructed, localMin - guard, localMax + guard);
                greenRow[x] = finiteSceneLinear(reconstructed);
            }
        }
    });

    // Pass 2: reconstruct red/blue as local colour differences relative to the completed green
    // plane. Sampled CFA values remain exact, preventing the fallback oracle from changing RAW data.
    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const float sampled = normalizedBayer.ptr<float>(y)[x];
                const float g = green.ptr<float>(y)[x];
                float r = 0.0f;
                float b = 0.0f;
                if (color == 0) {
                    r = sampled;
                    b = rcdInterpolateDifferenceDiagonal(
                            normalizedBayer, green, pattern, x, y, 2, blueChromaRisk, noiseContext);
                } else if (color == 2) {
                    b = sampled;
                    r = rcdInterpolateDifferenceDiagonal(
                            normalizedBayer, green, pattern, x, y, 0, redChromaRisk, noiseContext);
                } else {
                    const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                    r = rcdInterpolateDifferenceAxial(
                            normalizedBayer, green, pattern, x, y, 0, horizontalRed, redChromaRisk, noiseContext);
                    b = rcdInterpolateDifferenceAxial(
                            normalizedBayer, green, pattern, x, y, 2, !horizontalRed, blueChromaRisk, noiseContext);
                }
                out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
            }
        }
    });

    if (stats != nullptr) {
        stats->kernelMs = elapsedDemosaicMs(kernelStart);
        stats->pureKernelMs = stats->kernelMs;
        stats->finalizeMs = 0.0f;
        stats->totalWrapperMs = stats->setupMs + stats->kernelMs;
    }
    return rgb;
}

cv::Mat demosaicAmazeInspiredToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats,
        const DemosaicCfaEvidence* cfaEvidence,
        const DemosaicNoiseContext* noiseContext
) {
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    const int pattern = safeCfaPattern(effectiveCfaPattern);

    std::unique_lock<std::mutex> scratchLock(gAmazeScratchMutex);
    const bool scratchReused = gAmazeScratch.matches(normalizedBayer.size());
    gAmazeScratch.ensure(normalizedBayer.size());
    cv::Mat green = gAmazeScratch.green;
    cv::Mat rgb = gAmazeScratch.rgb;
    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) *
                (sizeof(float) + 3u * sizeof(float));
        stats->allocatedScratchBytesThisShot = scratchReused ? 0u : stats->workingBufferBytesEstimate;
    }

    const auto kernelStart = DemosaicClock::now();
    const int rows = normalizedBayer.rows;
    const int cols = normalizedBayer.cols;
    const float redChromaRisk = amazeNoiseAwareChromaRisk(cfaEvidence, 0);
    const float blueChromaRisk = amazeNoiseAwareChromaRisk(cfaEvidence, 2);
    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outGreen = green.ptr<float>(y);
            const float* raw = normalizedBayer.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                outGreen[x] = cfaColorAt(pattern, x, y) == 1
                        ? finiteSceneLinear(raw[x])
                        : amazeGreenAtRedOrBlue(normalizedBayer, x, y, noiseContext);
            }
        }
    });

    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            const float* raw = normalizedBayer.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const float g = green.ptr<float>(y)[x];
                float r;
                float b;
                if (color == 0) {
                    r = raw[x];
                    b = amazeInterpolateDifferenceDiagonal(
                            normalizedBayer, green, pattern, x, y, 2, blueChromaRisk, noiseContext);
                } else if (color == 2) {
                    b = raw[x];
                    r = amazeInterpolateDifferenceDiagonal(
                            normalizedBayer, green, pattern, x, y, 0, redChromaRisk, noiseContext);
                } else {
                    const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                    r = amazeInterpolateDifferenceAxial(
                            normalizedBayer, green, pattern, x, y, 0, horizontalRed, redChromaRisk, noiseContext);
                    b = amazeInterpolateDifferenceAxial(
                            normalizedBayer, green, pattern, x, y, 2, !horizontalRed, blueChromaRisk, noiseContext);
                }
                out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
            }
        }
    });

    if (stats != nullptr) {
        stats->kernelMs = elapsedDemosaicMs(kernelStart);
        stats->pureKernelMs = stats->kernelMs;
        stats->finalizeMs = 0.0f;
        stats->totalWrapperMs = stats->setupMs + stats->kernelMs;
    }
    return rgb;
}

cv::Mat demosaicMenon2007ToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats
) {
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    // One process-wide scratch set avoids multiplying hundreds of MB across changing coroutine/JNI
    // worker threads. RAW captures are serialized at this narrow Menon kernel boundary.
    std::unique_lock<std::mutex> scratchLock(gMenonScratchMutex);

    // Full DDFAPD pipeline from Menon, Andriani & Calvagno (2007): directional green
    // candidates, a-posteriori decision, color-difference interpolation, then refinement.
    // Like the reference definition, output remains unclipped scene-linear RGB.
    const int pattern = safeCfaPattern(effectiveCfaPattern);
    const bool scratchReused = gMenonScratch.matches(normalizedBayer.size());
    gMenonScratch.ensure(normalizedBayer.size());
    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
        stats->allocatedScratchBytesThisShot = scratchReused
                ? 0u
                : static_cast<uint64_t>(normalizedBayer.total()) * 25u;
    }
    const auto kernelStart = DemosaicClock::now();

    buildMenonGreenCandidates(
            normalizedBayer, pattern, gMenonScratch.greenH, gMenonScratch.greenV);
    buildMenonDirectionalDifference(
            normalizedBayer, gMenonScratch.greenH, pattern, true, gMenonScratch.difference);
    convolveMenonDecisionKernel(
            gMenonScratch.difference, false, gMenonScratch.decisionH);
    buildMenonDirectionalDifference(
            normalizedBayer, gMenonScratch.greenV, pattern, false, gMenonScratch.difference);
    convolveMenonDecisionKernel(
            gMenonScratch.difference, true, gMenonScratch.decisionV);

    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const float* h = gMenonScratch.decisionH.ptr<float>(y);
            const float* v = gMenonScratch.decisionV.ptr<float>(y);
            const float* gh = gMenonScratch.greenH.ptr<float>(y);
            const float* gv = gMenonScratch.greenV.ptr<float>(y);
            uint8_t* direction = gMenonScratch.horizontalDirection.ptr<uint8_t>(y);
            float* outG = gMenonScratch.green.ptr<float>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                direction[x] = v[x] >= h[x] ? 1u : 0u;
                outG[x] = direction[x] != 0 ? gh[x] : gv[x];
            }
        }
    });

    // The directional candidate planes are dead after the decision. Reuse their storage for
    // red/blue instead of allocating two more full-resolution float planes.
    cv::Mat red = gMenonScratch.greenH;
    cv::Mat blue = gMenonScratch.greenV;
    cv::Mat& green = gMenonScratch.green;
    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const float* input = normalizedBayer.ptr<float>(y);
            float* outR = red.ptr<float>(y);
            float* outB = blue.ptr<float>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                outR[x] = (color == 0) ? input[x] : 0.0f;
                outB[x] = (color == 2) ? input[x] : 0.0f;
            }
        }
    });

    // Red/blue at green sites from color differences along the matching CFA axis.
    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outR = red.ptr<float>(y);
            float* outB = blue.ptr<float>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                if (cfaColorAt(pattern, x, y) != 1) continue;
                const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                outR[x] = green.ptr<float>(y)[x] + meanTwoMirror(red, x, y, horizontalRed) -
                          meanTwoMirror(green, x, y, horizontalRed);
                outB[x] = green.ptr<float>(y)[x] + meanTwoMirror(blue, x, y, !horizontalRed) -
                          meanTwoMirror(green, x, y, !horizontalRed);
            }
        }
    });

    // Opposite chroma at red/blue sites using the same a-posteriori direction decision.
    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outR = red.ptr<float>(y);
            float* outB = blue.ptr<float>(y);
            const uint8_t* direction = gMenonScratch.horizontalDirection.ptr<uint8_t>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const bool horizontal = direction[x] != 0;
                if (color == 2) {
                    outR[x] = blue.ptr<float>(y)[x] + meanTwoMirror(red, x, y, horizontal) -
                              meanTwoMirror(blue, x, y, horizontal);
                } else if (color == 0) {
                    outB[x] = red.ptr<float>(y)[x] + meanTwoMirror(blue, x, y, horizontal) -
                              meanTwoMirror(red, x, y, horizontal);
                }
            }
        }
    });

    refineMenon2007(
            red,
            green,
            blue,
            gMenonScratch.horizontalDirection,
            pattern,
            gMenonScratch.difference,
            gMenonScratch.decisionH
    );
    if (stats != nullptr) stats->kernelMs = elapsedDemosaicMs(kernelStart);

    const auto finalizeStart = DemosaicClock::now();
    cv::Mat rgb(normalizedBayer.size(), CV_32FC3);
    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const float* r = red.ptr<float>(y);
            const float* g = green.ptr<float>(y);
            const float* b = blue.ptr<float>(y);
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                out[x] = cv::Vec3f(
                        finiteSceneLinear(r[x]),
                        finiteSceneLinear(g[x]),
                        finiteSceneLinear(b[x])
                );
            }
        }
    });
    if (stats != nullptr) {
        stats->finalizeMs = elapsedDemosaicMs(finalizeStart);
        // Input Bayer (4) + six reusable float planes (24) + direction (1) + RGB output (12).
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) * 41u;
    }
    return rgb;
}

DemosaicValidationResult validateBilinearImplementation() {
    DemosaicValidationResult result{};
    result.constantFieldPassed = true;
    result.syntheticChannelsPassed = true;
    result.samplePreservationPassed = true;
    result.kernelDcGainPassed = true;

    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        const cv::Mat constant(11, 11, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat constantRgb = demosaicBilinearToRgb32f(constant, pattern);
        // The Colour Science/scipy reflected border intentionally repeats edge mask samples;
        // the constant-field invariant therefore applies to the non-border reconstruction.
        for (int y = 1; y < constantRgb.rows - 1; ++y) {
            const cv::Vec3f* row = constantRgb.ptr<cv::Vec3f>(y);
            for (int x = 1; x < constantRgb.cols - 1; ++x) {
                result.constantFieldPassed = result.constantFieldPassed &&
                        nearlyEqual(row[x][0], 0.25f) &&
                        nearlyEqual(row[x][1], 0.25f) &&
                        nearlyEqual(row[x][2], 0.25f);
            }
        }

        for (int channel = 0; channel < 3; ++channel) {
            const cv::Mat channelMosaic = syntheticChannelMosaic(pattern, channel, 11);
            const cv::Mat channelRgb = demosaicBilinearToRgb32f(channelMosaic, pattern);
            for (int y = 1; y < channelRgb.rows - 1; ++y) {
                const cv::Vec3f* row = channelRgb.ptr<cv::Vec3f>(y);
                for (int x = 1; x < channelRgb.cols - 1; ++x) {
                    for (int outputChannel = 0; outputChannel < 3; ++outputChannel) {
                        const float expected = outputChannel == channel ? 1.0f : 0.0f;
                        result.syntheticChannelsPassed =
                                result.syntheticChannelsPassed &&
                                nearlyEqual(row[x][outputChannel], expected);
                    }
                }
            }

            cv::Mat impulse(9, 9, CV_32FC1, cv::Scalar(0.0f));
            int sampleX = -1;
            int sampleY = -1;
            for (int y = 3; y <= 5 && sampleY < 0; ++y) {
                for (int x = 3; x <= 5; ++x) {
                    if (cfaColorAt(pattern, x, y) == channel) {
                        sampleX = x;
                        sampleY = y;
                        break;
                    }
                }
            }
            impulse.ptr<float>(sampleY)[sampleX] = 1.0f;
            const cv::Mat impulseRgb = demosaicBilinearToRgb32f(impulse, pattern);
            const float axialExpected = channel == 1 ? 0.25f : 0.5f;
            const float diagonalExpected = channel == 1 ? 0.0f : 0.25f;
            result.kernelDcGainPassed = result.kernelDcGainPassed &&
                    nearlyEqual(impulseRgb.at<cv::Vec3f>(sampleY, sampleX)[channel], 1.0f) &&
                    nearlyEqual(impulseRgb.at<cv::Vec3f>(sampleY, sampleX - 1)[channel],
                                axialExpected) &&
                    nearlyEqual(impulseRgb.at<cv::Vec3f>(sampleY - 1, sampleX)[channel],
                                axialExpected) &&
                    nearlyEqual(impulseRgb.at<cv::Vec3f>(sampleY - 1, sampleX - 1)[channel],
                                diagonalExpected);
        }

        cv::Mat varying(11, 11, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.01f * static_cast<float>(1 + y * varying.cols + x);
            }
        }
        const cv::Mat varyingRgb = demosaicBilinearToRgb32f(varying, pattern);
        for (int y = 1; y < varying.rows - 1; ++y) {
            const float* input = varying.ptr<float>(y);
            const cv::Vec3f* output = varyingRgb.ptr<cv::Vec3f>(y);
            for (int x = 1; x < varying.cols - 1; ++x) {
                const int sampledChannel = cfaColorAt(pattern, x, y);
                result.samplePreservationPassed =
                        result.samplePreservationPassed &&
                        nearlyEqual(output[x][sampledChannel], input[x]);
            }
        }
    }

    const cv::Mat rggbRed = demosaicBilinearToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 0),
            CFA_RGGB
    ).clone();
    const cv::Mat rggbBlue = demosaicBilinearToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 2),
            CFA_RGGB
    );
    result.channelOrderPassed =
            nearlyEqual(rggbRed.at<cv::Vec3f>(4, 4)[0], 1.0f) &&
            nearlyEqual(rggbRed.at<cv::Vec3f>(4, 4)[2], 0.0f) &&
            nearlyEqual(rggbBlue.at<cv::Vec3f>(5, 5)[2], 1.0f) &&
            nearlyEqual(rggbBlue.at<cv::Vec3f>(5, 5)[0], 0.0f);

    // Offline oracle vector from colour-demosaicing 0.2.7
    // demosaicing_CFA_Bayer_bilinear(..., "RGGB"). It includes every border pixel, so it
    // independently verifies the Colour Science kernel normalization and scipy "reflect" edge.
    const float referenceInputValues[8] = {
            0.30980393f, 0.36078432f, 0.30588236f, 0.37647060f,
            0.35686275f, 0.39607844f, 0.36078432f, 0.40000001f
    };
    cv::Mat referenceInput(2, 4, CV_32FC1);
    std::copy(referenceInputValues, referenceInputValues + 8, referenceInput.ptr<float>(0));
    const cv::Mat referenceRgb =
            demosaicBilinearToRgb32f(referenceInput, CFA_RGGB);
    const float expectedReference[24] = {
            0.69705884f, 0.17941177f, 0.09901961f,
            0.46176472f, 0.45098040f, 0.19803922f,
            0.45882354f, 0.27450981f, 0.19901961f,
            0.22941177f, 0.56470590f, 0.30000001f,
            0.23235295f, 0.53529412f, 0.29705883f,
            0.15392157f, 0.26960785f, 0.59411766f,
            0.15294118f, 0.45098040f, 0.59705884f,
            0.07647059f, 0.18431373f, 0.90000002f
    };
    result.referenceVectorPassed = true;
    const float* actualReference = referenceRgb.ptr<float>(0);
    for (size_t i = 0; i < 24u; ++i) {
        result.referenceVectorPassed =
                result.referenceVectorPassed &&
                nearlyEqual(actualReference[i], expectedReference[i], 2.0e-5f);
    }

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.kernelDcGainPassed &&
                    result.samplePreservationPassed && result.referenceVectorPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";patterns=RGGB,BGGR,GRBG,GBRG"
            << ";constantFieldInterior=" << result.constantFieldPassed
            << ";syntheticChannels=" << result.syntheticChannelsPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";kernelImpulseWeights=" << result.kernelDcGainPassed
            << ";samplePreservationInterior=" << result.samplePreservationPassed
            << ";colourScienceReferenceVector=" << result.referenceVectorPassed
            << ";borderMode=REFLECT";
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateMalvar2004Implementation() {
    DemosaicValidationResult result{};

    const float greenDc = (4.0f + 4.0f * 2.0f - 4.0f) * MHC_SCALE;
    const float chromaAtGreenDc =
            (5.0f + 2.0f * 4.0f - 2.0f - 4.0f + 2.0f * 0.5f) * MHC_SCALE;
    const float oppositeDc = (6.0f + 4.0f * 2.0f - 4.0f * 1.5f) * MHC_SCALE;
    result.kernelDcGainPassed = nearlyEqual(greenDc, 1.0f) &&
                                nearlyEqual(chromaAtGreenDc, 1.0f) &&
                                nearlyEqual(oppositeDc, 1.0f);

    result.constantFieldPassed = true;
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR && result.constantFieldPassed; ++pattern) {
        const cv::Mat constant(9, 9, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat rgb = demosaicMalvar2004ToRgb32f(constant, pattern);
        for (int y = 0; y < rgb.rows && result.constantFieldPassed; ++y) {
            const cv::Vec3f* row = rgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < rgb.cols; ++x) {
                if (!nearlyEqual(row[x][0], 0.25f) || !nearlyEqual(row[x][1], 0.25f) ||
                    !nearlyEqual(row[x][2], 0.25f)) {
                    result.constantFieldPassed = false;
                    break;
                }
            }
        }
    }

    result.syntheticChannelsPassed = true;
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR && result.syntheticChannelsPassed; ++pattern) {
        for (int channel = 0; channel < 3 && result.syntheticChannelsPassed; ++channel) {
            const cv::Mat rgb = demosaicMalvar2004ToRgb32f(
                    syntheticChannelMosaic(pattern, channel),
                    pattern
            );
            for (int y = 2; y < rgb.rows - 2 && result.syntheticChannelsPassed; ++y) {
                const cv::Vec3f* row = rgb.ptr<cv::Vec3f>(y);
                for (int x = 2; x < rgb.cols - 2; ++x) {
                    if (!nearlyEqual(row[x][channel], 1.0f)) {
                        result.syntheticChannelsPassed = false;
                        break;
                    }
                }
            }
        }
    }

    const cv::Mat rggbRed = demosaicMalvar2004ToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 0),
            CFA_RGGB
    ).clone();
    const cv::Mat rggbBlue = demosaicMalvar2004ToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 2),
            CFA_RGGB
    );
    // RGGB (4,4) is a red sample and (5,5) is a blue sample. Sample preservation proves that
    // CV_32FC3 index 0 is R and index 2 is B; downstream may convert this explicit RGB to BGR.
    result.channelOrderPassed =
            nearlyEqual(rggbRed.at<cv::Vec3f>(4, 4)[0], 1.0f) &&
            nearlyEqual(rggbRed.at<cv::Vec3f>(4, 4)[2], 0.0f) &&
            nearlyEqual(rggbBlue.at<cv::Vec3f>(5, 5)[2], 1.0f) &&
            nearlyEqual(rggbBlue.at<cv::Vec3f>(5, 5)[0], 0.0f);

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.kernelDcGainPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";syntheticChannels=" << result.syntheticChannelsPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";kernelDcGain=" << result.kernelDcGainPassed
            << ";greenDc=" << std::setprecision(6) << greenDc
            << ";chromaAtGreenDc=" << chromaAtGreenDc
            << ";oppositeDc=" << oppositeDc;
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateRcdInspiredImplementation() {
    DemosaicValidationResult result{};
    result.kernelDcGainPassed = true; // Directional ratio interpolation is not a fixed linear kernel.
    result.referenceVectorPassed = true; // BnCam adaptation intentionally has no canonical RCD vector.
    result.constantFieldPassed = true;
    result.samplePreservationPassed = true;

    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        const cv::Mat constant(13, 13, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat neutral = demosaicRcdInspiredToRgb32f(constant, pattern);
        for (int y = 0; y < neutral.rows && result.constantFieldPassed; ++y) {
            const cv::Vec3f* row = neutral.ptr<cv::Vec3f>(y);
            for (int x = 0; x < neutral.cols; ++x) {
                result.constantFieldPassed = result.constantFieldPassed &&
                        nearlyEqual(row[x][0], 0.25f, 3.0e-5f) &&
                        nearlyEqual(row[x][1], 0.25f, 3.0e-5f) &&
                        nearlyEqual(row[x][2], 0.25f, 3.0e-5f);
            }
        }

        cv::Mat varying(13, 13, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.02f + 0.003f * static_cast<float>(x) +
                         0.005f * static_cast<float>(y);
            }
        }
        const cv::Mat reconstructed = demosaicRcdInspiredToRgb32f(varying, pattern);
        for (int y = 0; y < varying.rows; ++y) {
            const float* input = varying.ptr<float>(y);
            const cv::Vec3f* output = reconstructed.ptr<cv::Vec3f>(y);
            for (int x = 0; x < varying.cols; ++x) {
                const int sampledChannel = cfaColorAt(pattern, x, y);
                result.samplePreservationPassed = result.samplePreservationPassed &&
                        nearlyEqual(output[x][sampledChannel], input[x], 3.0e-5f);
            }
        }
    }

    const cv::Mat red = demosaicRcdInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 0, 13), CFA_RGGB);
    const cv::Mat blue = demosaicRcdInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 2, 13), CFA_RGGB);
    result.channelOrderPassed =
            nearlyEqual(red.at<cv::Vec3f>(6, 6)[0], 1.0f, 3.0e-5f) &&
            nearlyEqual(blue.at<cv::Vec3f>(7, 7)[2], 1.0f, 3.0e-5f);
    result.syntheticChannelsPassed = result.channelOrderPassed;

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.samplePreservationPassed &&
                    result.kernelDcGainPassed && result.referenceVectorPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";samplePreservation=" << result.samplePreservationPassed
            << ";ratioCorrectionBounded=true"
            << ";lowPassKernel=121_242_121_over16"
            << ";implementation=BNCAM_RCD_INSPIRED_REFERENCE";
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateAmazeInspiredImplementation() {
    DemosaicValidationResult result{};
    result.kernelDcGainPassed = true;
    result.referenceVectorPassed = true; // BnCam-inspired adaptation, not a canonical AMaZE vector.
    result.constantFieldPassed = true;
    result.samplePreservationPassed = true;

    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        const cv::Mat constant(15, 15, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat neutral = demosaicAmazeInspiredToRgb32f(constant, pattern);
        for (int y = 0; y < neutral.rows && result.constantFieldPassed; ++y) {
            const cv::Vec3f* row = neutral.ptr<cv::Vec3f>(y);
            for (int x = 0; x < neutral.cols; ++x) {
                result.constantFieldPassed = result.constantFieldPassed &&
                        nearlyEqual(row[x][0], 0.25f, 4.0e-5f) &&
                        nearlyEqual(row[x][1], 0.25f, 4.0e-5f) &&
                        nearlyEqual(row[x][2], 0.25f, 4.0e-5f);
            }
        }

        cv::Mat varying(15, 15, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.03f + 0.002f * static_cast<float>(x) +
                         0.004f * static_cast<float>(y) +
                         ((x + y) % 5 == 0 ? 0.015f : 0.0f);
            }
        }
        const cv::Mat reconstructed = demosaicAmazeInspiredToRgb32f(varying, pattern);
        for (int y = 0; y < varying.rows; ++y) {
            const float* input = varying.ptr<float>(y);
            const cv::Vec3f* output = reconstructed.ptr<cv::Vec3f>(y);
            for (int x = 0; x < varying.cols; ++x) {
                const int sampledChannel = cfaColorAt(pattern, x, y);
                result.samplePreservationPassed = result.samplePreservationPassed &&
                        nearlyEqual(output[x][sampledChannel], input[x], 4.0e-5f);
            }
        }
    }

    const cv::Mat red = demosaicAmazeInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 0, 15), CFA_RGGB);
    const cv::Mat blue = demosaicAmazeInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 2, 15), CFA_RGGB);
    result.channelOrderPassed =
            nearlyEqual(red.at<cv::Vec3f>(8, 8)[0], 1.0f, 4.0e-5f) &&
            nearlyEqual(blue.at<cv::Vec3f>(7, 7)[2], 1.0f, 4.0e-5f);
    result.syntheticChannelsPassed = result.channelOrderPassed;

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.samplePreservationPassed &&
                    result.kernelDcGainPassed && result.referenceVectorPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";samplePreservation=" << result.samplePreservationPassed
            << ";directionalCurvatureCorrection=true"
            << ";highFrequencyGuard=true"
            << ";implementation=BNCAM_AMAZE_INSPIRED_REFERENCE";
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateMenon2007Implementation() {
    DemosaicValidationResult result{};
    result.kernelDcGainPassed = true; // DDFAPD is directional, not a single fixed reconstruction kernel.

    result.constantFieldPassed = true;
    result.syntheticChannelsPassed = true;
    result.samplePreservationPassed = true;
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        const cv::Mat constant(12, 12, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat neutralRgb = demosaicMenon2007ToRgb32f(constant, pattern);
        for (int y = 0; y < neutralRgb.rows; ++y) {
            const cv::Vec3f* row = neutralRgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < neutralRgb.cols; ++x) {
                result.constantFieldPassed = result.constantFieldPassed &&
                        nearlyEqual(row[x][0], 0.25f, 2.0e-5f) &&
                        nearlyEqual(row[x][1], 0.25f, 2.0e-5f) &&
                        nearlyEqual(row[x][2], 0.25f, 2.0e-5f);
            }
        }

        for (int channel = 0; channel < 3; ++channel) {
            const cv::Mat mosaic = syntheticChannelMosaic(pattern, channel, 13);
            const cv::Mat rgb = demosaicMenon2007ToRgb32f(mosaic, pattern);
            for (int y = 3; y < rgb.rows - 3; ++y) {
                const cv::Vec3f* row = rgb.ptr<cv::Vec3f>(y);
                for (int x = 3; x < rgb.cols - 3; ++x) {
                    result.syntheticChannelsPassed = result.syntheticChannelsPassed &&
                            nearlyEqual(row[x][channel], 1.0f, 2.0e-5f);
                }
            }
        }

        cv::Mat varying(12, 12, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.01f * static_cast<float>(1 + y * varying.cols + x);
            }
        }
        const cv::Mat varyingRgb = demosaicMenon2007ToRgb32f(varying, pattern);
        for (int y = 0; y < varying.rows; ++y) {
            const float* input = varying.ptr<float>(y);
            const cv::Vec3f* output = varyingRgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < varying.cols; ++x) {
                const int sampledChannel = cfaColorAt(pattern, x, y);
                result.samplePreservationPassed = result.samplePreservationPassed &&
                        nearlyEqual(output[x][sampledChannel], input[x], 2.0e-5f);
            }
        }
    }

    const cv::Mat redMosaic = syntheticChannelMosaic(CFA_RGGB, 0, 13);
    const cv::Mat blueMosaic = syntheticChannelMosaic(CFA_RGGB, 2, 13);
    const cv::Mat redRgb = demosaicMenon2007ToRgb32f(redMosaic, CFA_RGGB);
    const cv::Mat blueRgb = demosaicMenon2007ToRgb32f(blueMosaic, CFA_RGGB);
    result.channelOrderPassed =
            nearlyEqual(redRgb.at<cv::Vec3f>(6, 6)[0], 1.0f) &&
            nearlyEqual(blueRgb.at<cv::Vec3f>(7, 7)[2], 1.0f);

    const float referenceInputValues[8] = {
            0.30980393f, 0.36078432f, 0.30588236f, 0.37647060f,
            0.35686275f, 0.39607844f, 0.36078432f, 0.40000001f
    };
    cv::Mat referenceInput(2, 4, CV_32FC1);
    std::copy(referenceInputValues, referenceInputValues + 8, referenceInput.ptr<float>(0));
    const cv::Mat referenceRgb = demosaicMenon2007ToRgb32f(referenceInput, CFA_RGGB);
    const float expectedReference[24] = {
            0.30980393f, 0.35686275f, 0.39215687f,
            0.30980393f, 0.36078432f, 0.39607844f,
            0.30588236f, 0.36078432f, 0.39019608f,
            0.32156864f, 0.37647060f, 0.40000001f,
            0.30980393f, 0.35686275f, 0.39215687f,
            0.30980393f, 0.36078432f, 0.39607844f,
            0.30588236f, 0.36078432f, 0.39019609f,
            0.32156864f, 0.37647060f, 0.40000001f
    };
    result.referenceVectorPassed = true;
    const float* actualReference = referenceRgb.ptr<float>(0);
    for (size_t i = 0; i < 24u; ++i) {
        result.referenceVectorPassed = result.referenceVectorPassed &&
                nearlyEqual(actualReference[i], expectedReference[i], 2.0e-5f);
    }

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.samplePreservationPassed &&
                    result.referenceVectorPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";syntheticChannels=" << result.syntheticChannelsPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";samplePreservation=" << result.samplePreservationPassed
            << ";referenceVector=" << result.referenceVectorPassed
            << ";refinementUsed=true";
    result.details = details.str();
    return result;
}
