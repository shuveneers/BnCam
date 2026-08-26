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

float amazeNoiseSigmaY(const DemosaicNoiseContext* noiseContext) {
    if (noiseContext == nullptr || !noiseContext->available ||
        !std::isfinite(noiseContext->sigmaY)) return 0.0f;
    return std::max(0.0f, noiseContext->sigmaY);
}

float amazeNoiseSigmaChroma(const DemosaicNoiseContext* noiseContext) {
    if (noiseContext == nullptr || !noiseContext->available ||
        !std::isfinite(noiseContext->sigmaChroma)) return 0.0f;
    return std::max(0.0f, noiseContext->sigmaChroma);
}

float amazeRetainHighOrderDetail(float term, const DemosaicNoiseContext* noiseContext) {
    const float sigma = amazeNoiseSigmaY(noiseContext);
    if (sigma <= 1.0e-7f) return term;
    const float z = std::abs(term) / std::max(1.0e-6f, sigma);
    const float retention = demosaicSmoothstep(1.25f, 3.75f, z);
    return term * retention;
}

// SPECTRA evidence may only temper reconstructed chroma. It never changes sampled sensels
// and never owns the AMaZE direction/Nyquist decision.
float amazeNoiseAwareChromaRisk(const DemosaicCfaEvidence* evidence, int channel) {
    if (evidence == nullptr || !evidence->available || (channel != 0 && channel != 2)) return 0.0f;
    const float bandPressure = std::clamp(
            0.55f * evidence->fineCorrectionConfidence +
            0.30f * evidence->midCorrectionConfidence +
            0.15f * evidence->lowCorrectionConfidence,
            0.0f, 1.0f);
    const float opponentPressure = channel == 0
            ? evidence->redOpponentCorrectionConfidence
            : evidence->blueOpponentCorrectionConfidence;
    const float structureRelief = 1.0f - 0.30f *
            std::clamp(evidence->structureProtection, 0.0f, 1.0f);
    return std::clamp(
            (0.60f * bandPressure + 0.40f * std::clamp(opponentPressure, 0.0f, 1.0f)) *
                    structureRelief,
            0.0f, 1.0f);
}

struct AmazeAxisGuide {
    float green = 0.0f;
    float cost = 1.0e-5f;
};

float amazeAdaptiveOneSidedGreen(
        float center,
        float adjacentGreen,
        float farSameColor,
        float hamiltonAdams
) {
    constexpr float kEps = 1.0e-5f;
    if (center * farSameColor <= 0.0f || std::abs(farSameColor) <= kEps) return hamiltonAdams;
    const float ratio = center / farSameColor;
    if (!(ratio > 0.20f && ratio < 5.00f) || !std::isfinite(ratio)) return hamiltonAdams;
    const float geometric = adjacentGreen * std::sqrt(std::max(0.0f, ratio));
    const float logDistance = std::abs(std::log2(std::max(ratio, kEps)));
    const float reliability = 1.0f - demosaicSmoothstep(0.45f, 1.25f, logDistance);
    return finiteSceneLinear(hamiltonAdams + 0.45f * reliability * (geometric - hamiltonAdams));
}

AmazeAxisGuide amazeAxisGuideAt(
        const cv::Mat& mosaic,
        int x,
        int y,
        bool horizontal,
        const DemosaicNoiseContext* noiseContext
) {
    const int dx = horizontal ? 1 : 0;
    const int dy = horizontal ? 0 : 1;
    const float center = sampleClamped(mosaic, x, y);
    const float nearNeg = sampleClamped(mosaic, x - dx, y - dy);
    const float nearPos = sampleClamped(mosaic, x + dx, y + dy);
    const float farNeg = sampleClamped(mosaic, x - 2 * dx, y - 2 * dy);
    const float farPos = sampleClamped(mosaic, x + 2 * dx, y + 2 * dy);

    const float curvature = amazeRetainHighOrderDetail(
            2.0f * center - farNeg - farPos, noiseContext);
    const float ha = 0.5f * (nearNeg + nearPos) + 0.25f * curvature;
    const float haNeg = nearNeg + 0.5f * amazeRetainHighOrderDetail(center - farNeg, noiseContext);
    const float haPos = nearPos + 0.5f * amazeRetainHighOrderDetail(center - farPos, noiseContext);
    const float adaptiveNeg = amazeAdaptiveOneSidedGreen(center, nearNeg, farNeg, haNeg);
    const float adaptivePos = amazeAdaptiveOneSidedGreen(center, nearPos, farPos, haPos);

    const float sigma = amazeNoiseSigmaY(noiseContext);
    const float costNeg = std::max(1.0e-5f,
            std::abs(nearNeg - nearPos) + std::abs(center - farNeg) +
            0.5f * std::abs(nearNeg - sampleClamped(mosaic, x - 3 * dx, y - 3 * dy)) -
            1.10f * sigma);
    const float costPos = std::max(1.0e-5f,
            std::abs(nearNeg - nearPos) + std::abs(center - farPos) +
            0.5f * std::abs(nearPos - sampleClamped(mosaic, x + 3 * dx, y + 3 * dy)) -
            1.10f * sigma);
    const float wNeg = 1.0f / (costNeg * costNeg + 1.0e-10f);
    const float wPos = 1.0f / (costPos * costPos + 1.0e-10f);
    const float adaptive = (wNeg * adaptiveNeg + wPos * adaptivePos) /
            std::max(1.0e-8f, wNeg + wPos);

    AmazeAxisGuide out;
    const float texture = std::abs(farNeg - farPos) + std::abs(nearNeg - nearPos);
    const float adaptiveAuthority = 1.0f - demosaicSmoothstep(
            0.015f + 2.0f * sigma, 0.080f + 8.0f * sigma, texture);
    out.green = finiteSceneLinear(ha + 0.55f * adaptiveAuthority * (adaptive - ha));
    out.cost = std::max(1.0e-5f,
            std::abs(nearNeg - nearPos) + 0.65f * std::abs(curvature) +
            0.35f * std::abs(farNeg - farPos) - 0.90f * sigma);
    return out;
}

float amazeNyquistPreScore(
        const cv::Mat& mosaic,
        int x,
        int y,
        const DemosaicNoiseContext* noiseContext
) {
    const float c = sampleClamped(mosaic, x, y);
    const float h2 = std::abs(2.0f * c - sampleClamped(mosaic, x - 2, y) - sampleClamped(mosaic, x + 2, y));
    const float v2 = std::abs(2.0f * c - sampleClamped(mosaic, x, y - 2) - sampleClamped(mosaic, x, y + 2));
    const float h1 = std::abs(sampleClamped(mosaic, x - 1, y) - sampleClamped(mosaic, x + 1, y));
    const float v1 = std::abs(sampleClamped(mosaic, x, y - 1) - sampleClamped(mosaic, x, y + 1));
    const float d1 = std::abs(sampleClamped(mosaic, x - 1, y - 1) - sampleClamped(mosaic, x + 1, y + 1));
    const float d2 = std::abs(sampleClamped(mosaic, x + 1, y - 1) - sampleClamped(mosaic, x - 1, y + 1));
    const float axial = h2 + v2 + 0.50f * (h1 + v1);
    const float diagonal = d1 + d2;
    const float anisotropy = std::abs((h2 + 0.5f * h1) - (v2 + 0.5f * v1)) /
            std::max(1.0e-6f, h2 + v2 + 0.5f * (h1 + v1));
    const float isotropy = 1.0f - std::clamp(anisotropy, 0.0f, 1.0f);
    const float sigma = amazeNoiseSigmaY(noiseContext);
    const float energy = axial + 0.45f * diagonal;
    const float energyGate = demosaicSmoothstep(
            0.004f + 3.0f * sigma,
            0.035f + 12.0f * sigma,
            energy);
    const float isotropyGate = demosaicSmoothstep(0.35f, 0.82f, isotropy);
    return std::clamp(energyGate * isotropyGate, 0.0f, 1.0f);
}

float amazeGreenGuideAt(
        const cv::Mat& mosaic,
        int pattern,
        int x,
        int y,
        const DemosaicNoiseContext* noiseContext
) {
    if (cfaColorAt(pattern, x, y) == 1) return sampleClamped(mosaic, x, y);
    const AmazeAxisGuide h = amazeAxisGuideAt(mosaic, x, y, true, noiseContext);
    const AmazeAxisGuide v = amazeAxisGuideAt(mosaic, x, y, false, noiseContext);
    const float wH = 1.0f / (h.cost * h.cost + 1.0e-10f);
    const float wV = 1.0f / (v.cost * v.cost + 1.0e-10f);
    float green = (wH * h.green + wV * v.green) / std::max(1.0e-8f, wH + wV);
    const float nyquist = amazeNyquistPreScore(mosaic, x, y, noiseContext);
    green += 0.25f * nyquist * (0.5f * (h.green + v.green) - green);

    const float left = sampleClamped(mosaic, x - 1, y);
    const float right = sampleClamped(mosaic, x + 1, y);
    const float up = sampleClamped(mosaic, x, y - 1);
    const float down = sampleClamped(mosaic, x, y + 1);
    const float localMin = std::min({left, right, up, down});
    const float localMax = std::max({left, right, up, down});
    const float guard = std::max(3.0f * amazeNoiseSigmaY(noiseContext),
            0.18f * std::max(1.0e-5f, localMax - localMin));
    return finiteSceneLinear(std::clamp(green, localMin - guard, localMax + guard));
}

float amazeGreenPlaneAt(const cv::Mat& green, int x, int y) {
    const int sx = std::clamp(x, 0, green.cols - 1);
    const int sy = std::clamp(y, 0, green.rows - 1);
    return green.ptr<float>(sy)[sx];
}

float amazeGuideNyquistAt(const cv::Mat& guide, int x, int y) {
    const int sx = std::clamp(x, 0, guide.cols - 1);
    const int sy = std::clamp(y, 0, guide.rows - 1);
    return std::clamp(guide.ptr<cv::Vec2f>(sy)[sx][0], 0.0f, 1.0f);
}

float amazeSmoothedNyquist(const cv::Mat& guide, int x, int y) {
    const float center = 0.50f * amazeGuideNyquistAt(guide, x, y);
    const float axial = 0.125f * (
            amazeGuideNyquistAt(guide, x - 2, y) + amazeGuideNyquistAt(guide, x + 2, y) +
            amazeGuideNyquistAt(guide, x, y - 2) + amazeGuideNyquistAt(guide, x, y + 2));
    return std::clamp(center + axial, 0.0f, 1.0f);
}

bool amazeDifferenceAtSample(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        int pattern,
        int x,
        int y,
        int channel,
        float& difference
) {
    const int sx = std::clamp(x, 0, mosaic.cols - 1);
    const int sy = std::clamp(y, 0, mosaic.rows - 1);
    if (cfaColorAt(pattern, sx, sy) != channel) return false;
    difference = mosaic.ptr<float>(sy)[sx] - green.ptr<float>(sy)[sx];
    return std::isfinite(difference);
}

float amazeChromaWeight(
        float greenDelta,
        float differenceDisagreement,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    const float sigma = amazeNoiseSigmaChroma(noiseContext);
    const float risk = std::clamp(chromaRisk, 0.0f, 1.0f);
    const float gradient = std::max(0.0f,
            greenDelta + (0.30f + 0.25f * risk) * differenceDisagreement - sigma);
    const float floor = std::max(1.5e-4f + 5.0e-4f * risk, 0.60f * sigma);
    return 1.0f / std::max(1.0e-12f, (floor + gradient) * (floor + gradient));
}

float amazeClampDifference(
        float value,
        const std::array<float, 8>& samples,
        int count,
        const DemosaicNoiseContext* noiseContext
) {
    if (count <= 0) return value;
    float lo = samples[0];
    float hi = samples[0];
    for (int i = 1; i < count; ++i) {
        lo = std::min(lo, samples[i]);
        hi = std::max(hi, samples[i]);
    }
    const float guard = std::max(3.0f * amazeNoiseSigmaChroma(noiseContext),
            0.18f * std::max(1.0e-6f, hi - lo));
    return std::clamp(value, lo - guard, hi + guard);
}

float amazeInterpolateDifferenceAxial(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        const cv::Mat& guide,
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
    float dm = 0.0f, dp = 0.0f, dm3 = 0.0f, dp3 = 0.0f;
    const bool vm = amazeDifferenceAtSample(mosaic, green, pattern, x - dx, y - dy, channel, dm);
    const bool vp = amazeDifferenceAtSample(mosaic, green, pattern, x + dx, y + dy, channel, dp);
    if (!vm && vp) dm = dp;
    if (!vp && vm) dp = dm;
    if (!vm && !vp) return amazeGreenPlaneAt(green, x, y);
    if (!amazeDifferenceAtSample(mosaic, green, pattern, x - 3 * dx, y - 3 * dy, channel, dm3)) dm3 = dm;
    if (!amazeDifferenceAtSample(mosaic, green, pattern, x + 3 * dx, y + 3 * dy, channel, dp3)) dp3 = dp;

    const float gCenter = amazeGreenPlaneAt(green, x, y);
    const float gm = amazeGreenPlaneAt(green, x - dx, y - dy);
    const float gp = amazeGreenPlaneAt(green, x + dx, y + dy);
    const float disagreement = std::abs(dm - dp);
    const float wm = amazeChromaWeight(std::abs(gCenter - gm), disagreement, chromaRisk, noiseContext);
    const float wp = amazeChromaWeight(std::abs(gCenter - gp), disagreement, chromaRisk, noiseContext);
    const float nearEstimate = (wm * dm + wp * dp) / std::max(1.0e-8f, wm + wp);
    const float highOrder = (-dm3 + 9.0f * dm + 9.0f * dp - dp3) * (1.0f / 16.0f);
    const float sigma = amazeNoiseSigmaChroma(noiseContext);
    const float smoothAuthority = 1.0f - demosaicSmoothstep(
            0.010f + 2.0f * sigma, 0.060f + 8.0f * sigma,
            std::abs(gm - gp) + disagreement);
    float difference = nearEstimate + 0.30f * smoothAuthority * (1.0f - chromaRisk) *
            (highOrder - nearEstimate);

    const float nyquist = amazeSmoothedNyquist(guide, x, y);
    const float area = 0.375f * (dm + dp) + 0.125f * (dm3 + dp3);
    difference += 0.68f * nyquist * (area - difference);
    const std::array<float, 8> samples{dm, dp, dm3, dp3, 0, 0, 0, 0};
    difference = amazeClampDifference(difference, samples, 4, noiseContext);
    return finiteSceneLinear(gCenter + difference);
}

float amazeInterpolateDifferenceDiagonal(
        const cv::Mat& mosaic,
        const cv::Mat& green,
        const cv::Mat& guide,
        int pattern,
        int x,
        int y,
        int channel,
        float chromaRisk,
        const DemosaicNoiseContext* noiseContext
) {
    constexpr int nearOffsets[4][2] = {{-1,-1},{1,-1},{-1,1},{1,1}};
    constexpr int farOffsets[4][2] = {{-3,-3},{3,-3},{-3,3},{3,3}};
    std::array<float, 8> d{};
    int valid = 0;
    for (int i = 0; i < 4; ++i) {
        float value = 0.0f;
        if (amazeDifferenceAtSample(mosaic, green, pattern,
                x + nearOffsets[i][0], y + nearOffsets[i][1], channel, value)) {
            d[i] = value; ++valid;
        }
    }
    if (valid == 0) return amazeGreenPlaneAt(green, x, y);
    float nearMean = 0.0f; int nearCount = 0;
    for (int i = 0; i < 4; ++i) {
        float value = 0.0f;
        if (amazeDifferenceAtSample(mosaic, green, pattern,
                x + nearOffsets[i][0], y + nearOffsets[i][1], channel, value)) {
            d[i] = value; nearMean += value; ++nearCount;
        }
    }
    nearMean /= static_cast<float>(std::max(1, nearCount));
    for (int i = 0; i < 4; ++i) {
        float value = nearMean;
        if (!amazeDifferenceAtSample(mosaic, green, pattern,
                x + farOffsets[i][0], y + farOffsets[i][1], channel, value)) value = d[i];
        d[4 + i] = value;
    }

    const float gCenter = amazeGreenPlaneAt(green, x, y);
    const float dNW = d[0], dNE = d[1], dSW = d[2], dSE = d[3];
    const float diagA = 0.5f * (dNW + dSE);
    const float diagB = 0.5f * (dNE + dSW);
    const float gNW = amazeGreenPlaneAt(green, x - 1, y - 1);
    const float gNE = amazeGreenPlaneAt(green, x + 1, y - 1);
    const float gSW = amazeGreenPlaneAt(green, x - 1, y + 1);
    const float gSE = amazeGreenPlaneAt(green, x + 1, y + 1);
    const float disagreement = std::abs(diagA - diagB);
    const float wA = amazeChromaWeight(std::abs(gNW - gSE), disagreement, chromaRisk, noiseContext);
    const float wB = amazeChromaWeight(std::abs(gNE - gSW), disagreement, chromaRisk, noiseContext);
    float difference = (wA * diagA + wB * diagB) / std::max(1.0e-8f, wA + wB);

    const float highA = (-d[4] + 9.0f * dNW + 9.0f * dSE - d[7]) * (1.0f / 16.0f);
    const float highB = (-d[5] + 9.0f * dNE + 9.0f * dSW - d[6]) * (1.0f / 16.0f);
    const float sigma = amazeNoiseSigmaChroma(noiseContext);
    const float smoothAuthority = 1.0f - demosaicSmoothstep(
            0.012f + 2.0f * sigma, 0.070f + 8.0f * sigma,
            std::abs(gNW - gSE) + std::abs(gNE - gSW) + disagreement);
    const float highOrder = (wA * highA + wB * highB) / std::max(1.0e-8f, wA + wB);
    difference += 0.24f * smoothAuthority * (1.0f - chromaRisk) * (highOrder - difference);

    const float nyquist = amazeSmoothedNyquist(guide, x, y);
    float area = 0.0f;
    for (int i = 0; i < 4; ++i) area += 0.1875f * d[i] + 0.0625f * d[4 + i];
    difference += 0.72f * nyquist * (area - difference);
    difference = amazeClampDifference(difference, d, 8, noiseContext);
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
    cv::Mat guide; // CV_32FC2: nyquist score + reserved direction statistic.
    cv::Mat rgb;

    bool matches(const cv::Size& size) const {
        return !green.empty() && green.size() == size && green.type() == CV_32FC1 &&
               !guide.empty() && guide.size() == size && guide.type() == CV_32FC2 &&
               !rgb.empty() && rgb.size() == size && rgb.type() == CV_32FC3;
    }

    void ensure(const cv::Size& size) {
        if (green.empty() || green.size() != size || green.type() != CV_32FC1) green.create(size, CV_32FC1);
        if (guide.empty() || guide.size() != size || guide.type() != CV_32FC2) guide.create(size, CV_32FC2);
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
        // Bridge value 3 is retained for persisted-profile compatibility; Phase 5
        // assigns this slot to the trained Neural JDD reconstruction route.
        result.requestedMode = DemosaicMode::Bilinear;
        result.algorithm = DemosaicAlgorithm::NeuralJdd;
        result.reason = "legacy_slot_3_executes_neural_jdd";
        return result;
    }

    if (requestedModeValue == static_cast<int>(DemosaicMode::QualityMenon2007)) {
        // Bridge value 2 is retained for profile compatibility while the product path is
        // cut over from legacy Menon to BnCam AMaZE.
        result.requestedMode = DemosaicMode::QualityMenon2007;
        result.algorithm = DemosaicAlgorithm::Amaze;
        result.reason = "legacy_slot_2_forces_amaze";
        return result;
    }

    if (requestedModeValue == static_cast<int>(DemosaicMode::Auto)) {
        result.requestedMode = DemosaicMode::Auto;
        result.reason = "auto_hybrid_pending_scene_analysis";
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

bool neuralJddRuntimeValidated() {
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
    const bool neuralJddReady = neuralJddRuntimeValidated();
    const bool amazeReady = amazeRuntimeValidated();

    // Phase-5 deterministic Auto fallback/tie policy is AMaZE first, then Malvar, then Neural JDD.
    if (!metrics.valid) {
        if (amazeReady) {
            result.algorithm = DemosaicAlgorithm::Amaze;
            result.reason = "auto_metrics_unavailable_default_amaze";
        } else if (malvarReady) {
            result.algorithm = DemosaicAlgorithm::Malvar2004;
            result.reason = "auto_metrics_unavailable_amaze_invalid_fallback_malvar";
        } else {
            result.algorithm = DemosaicAlgorithm::NeuralJdd;
            result.reason = "auto_metrics_unavailable_amaze_malvar_invalid_fallback_neural_jdd";
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

    float neuralJddScore =
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
    if (!neuralJddReady) neuralJddScore = -1.0f;
    if (!amazeReady) amazeScore = -1.0f;

    result.autoMalvarScore = malvarScore;
    result.autoRcdScore = neuralJddScore;
    result.autoAmazeScore = amazeScore;
    result.autoCfaChromaRisk = cfaChromaRisk;

    struct Candidate {
        DemosaicAlgorithm algorithm;
        float score;
        const char* name;
        const char* reason;
    };
    // stable_sort preserves this order on ties: AMaZE is the Phase-5 tie-break default.
    std::array<Candidate, 3> candidates{{
            {DemosaicAlgorithm::Amaze, amazeScore, "AMAZE", "auto_score_winner_amaze"},
            {DemosaicAlgorithm::Malvar2004, malvarScore, "MALVAR_2004", "auto_score_winner_malvar_2004"},
            {DemosaicAlgorithm::NeuralJdd, neuralJddScore, "NEURAL_JDD", "auto_score_winner_neural_jdd"}
    }};
    std::stable_sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return a.score > b.score;
    });

    result.algorithm = candidates[0].algorithm;
    result.autoRunnerUp = candidates[1].name;
    result.autoScoreDelta = candidates[0].score - candidates[1].score;
    if (candidates[0].score < 0.0f) {
        // Catastrophic validation condition: retain AMaZE as the explicit transition identity,
        // but mark the resolver result as failed so downstream debug cannot mistake it for a
        // confidence-based Auto decision.
        result.algorithm = DemosaicAlgorithm::Amaze;
        result.reason = "auto_no_validated_demosaic_default_identity_amaze";
        result.fallbackOccurred = true;
        result.fallbackReason = "all_demosaic_reference_validations_failed";
    } else if (malvarReady && neuralJddReady && amazeReady) {
        // Delta 0048: scene analysis is now only a global prior. The production Vulkan path
        // resolves locally between all three validated reconstructions and blends them softly.
        // A bounded temperature and floor keep every route available to local evidence instead
        // of turning the prior back into a disguised whole-frame hard selector.
        const float maxScore = std::max({malvarScore, neuralJddScore, amazeScore});
        constexpr float kPriorTemperature = 0.30f;
        const auto priorExp = [&](float score) {
            return std::exp(std::clamp((score - maxScore) / kPriorTemperature, -12.0f, 0.0f));
        };
        float malvarPrior = priorExp(malvarScore);
        float neuralPrior = priorExp(neuralJddScore);
        float amazePrior = priorExp(amazeScore);
        const float initialSum = std::max(1.0e-6f, malvarPrior + neuralPrior + amazePrior);
        malvarPrior /= initialSum;
        neuralPrior /= initialSum;
        amazePrior /= initialSum;
        constexpr float kPriorFloor = 0.08f;
        malvarPrior = std::max(kPriorFloor, malvarPrior);
        neuralPrior = std::max(kPriorFloor, neuralPrior);
        amazePrior = std::max(kPriorFloor, amazePrior);
        const float flooredSum = malvarPrior + neuralPrior + amazePrior;
        result.autoMalvarPrior = malvarPrior / flooredSum;
        result.autoNeuralJddPrior = neuralPrior / flooredSum;
        result.autoAmazePrior = amazePrior / flooredSum;
        result.autoHybridExecution = true;
        result.reason = "auto_hybrid_region_aware_gpu";
    } else {
        // Validation degradation remains explicit and deterministic. Do not mix a route whose
        // implementation validator failed; the dominant validated candidate becomes the typed
        // single-route fallback.
        result.reason = candidates[0].reason;
        result.fallbackOccurred = true;
        result.fallbackReason = "auto_hybrid_requires_all_three_validated_routes";
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
            << ",neuralJddFactors={base=0.5000"
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
            << ",neuralJddScore=" << neuralJddScore
            << ",amazeScore=" << amazeScore
            << ",runnerUp=" << result.autoRunnerUp
            << ",scoreDelta=" << result.autoScoreDelta
            << ",autoHybridExecution=" << (result.autoHybridExecution ? "true" : "false")
            << ",autoMalvarPrior=" << result.autoMalvarPrior
            << ",autoNeuralJddPrior=" << result.autoNeuralJddPrior
            << ",autoAmazePrior=" << result.autoAmazePrior
            << ",malvarReady=" << (malvarReady ? "true" : "false")
            << ",neuralJddReady=" << (neuralJddReady ? "true" : "false")
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
        case DemosaicMode::Auto: return "AUTO_HYBRID";
        case DemosaicMode::Bilinear: return "NEURAL_JDD";
        case DemosaicMode::NormalMalvar2004: return "MALVAR_2004";
        case DemosaicMode::QualityMenon2007: return "AMAZE";
        default: return "MALVAR_2004";
    }
}

const char* demosaicAlgorithmName(DemosaicAlgorithm algorithm) {
    switch (algorithm) {
        case DemosaicAlgorithm::Bilinear: return "BILINEAR_REFERENCE_ONLY";
        case DemosaicAlgorithm::NeuralJdd: return "NEURAL_JDD";
        case DemosaicAlgorithm::Amaze: return "AMAZE";
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
    // Phase 5 / Delta 0045 identity contract: this route is pure deterministic
    // Malvar-He-Cutler 2004. Shared adaptive context remains in the call ABI only.
    (void)cfaEvidence;
    (void)noiseContext;
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
    // Legacy function symbol retained to avoid breaking the established native call ABI.
    // Product identity for bridge slot 3 is now Neural JDD: a trained noisy-CFA -> clean-RGB
    // residual network. Pure MHC provides the deterministic reconstruction baseline; the network
    // jointly corrects demosaic residuals and physical CFA noise before WB/CCM/tone.
    const auto setupStart = DemosaicClock::now();
    if (normalizedBayer.empty() || normalizedBayer.type() != CV_32FC1) return {};
    (void)cfaEvidence;
    const int pattern = safeCfaPattern(effectiveCfaPattern);
    const cv::Mat baseline = demosaicMalvar2004ToRgb32f(
            normalizedBayer, pattern, nullptr, nullptr, nullptr).clone();
    if (baseline.empty()) return {};

    std::unique_lock<std::mutex> scratchLock(gRcdScratchMutex);
    const bool scratchReused = gRcdScratch.matches(normalizedBayer.size());
    gRcdScratch.ensure(normalizedBayer.size());
    cv::Mat rgb = gRcdScratch.rgb;

    static constexpr float W1[408] = {-0.0928661227f, 0.10916017f, -0.238949358f, -0.0604118295f, 0.207512796f, -0.00767328823f, 0.254315972f, -0.0180336256f, -0.0494986288f, 0.0659962893f, -0.0283330623f, -0.142683506f, -0.396805942f, -0.0285489745f, -0.116507046f, 0.0931306556f, 0.101455189f, -0.143013462f, 0.216083124f, -0.0251789205f, 0.0807221457f, 0.109874688f, -0.195306703f, -0.012569583f, 0.0306663383f, -0.0513127595f, -0.0325186029f, -0.0301862024f, -0.0610652976f, 0.10733857f, 0.118664987f, 0.113264292f, 0.0795431659f, -0.10312029f, -0.138926074f, -0.191692695f, -0.00101127452f, -0.0674677417f, -0.16891402f, -0.0341617242f, -0.103100382f, -0.00664762547f, -0.175070331f, -0.0787464678f, -0.1972339f, -0.0338668711f, -0.0851230547f, -0.0958462134f, -0.149915531f, -0.10437578f, 0.0542901605f, -0.182549953f, -0.265950173f, -0.0356127694f, 0.0904997811f, -0.141720384f, -0.0549052469f, -0.164148062f, -0.269566089f, 0.084892489f, -0.0971458405f, -0.0962903574f, -0.062292669f, 0.109595917f, -0.0311488584f, -0.0486766621f, -0.115253985f, -0.134903044f, 0.0601956621f, -0.0292681698f, -0.0285296086f, 0.107363336f, -0.0727240443f, -0.110215858f, -0.125127792f, 0.0146010742f, 0.0449813716f, 0.0221866537f, 0.0401432104f, 0.118900895f, 0.105982684f, -0.0768436641f, 0.0275965631f, -0.00263665197f, 0.11019145f, 0.000872922246f, -0.20780848f, -0.0326646008f, -0.095708169f, 0.143741921f, 0.105393745f, -0.0406399257f, 0.11784099f, 0.0510801934f, -0.191790968f, -0.236248508f, 0.0990952551f, -0.20522204f, 0.158076048f, 0.257456303f, -0.00620082021f, 0.0603837036f, 0.135538265f, 0.174235642f, 0.118650444f, -0.104419231f, 0.117234312f, -0.114433296f, 0.0505344011f, 0.0286034923f, 0.0431653485f, -0.0881662816f, -0.033247821f, 0.0935470909f, -0.295339316f, -0.161413223f, -0.0492220335f, 0.157386139f, 0.194932297f, 0.0356586874f, -0.0876860619f, 0.0473982133f, 0.0349830426f, 0.0118048955f, 0.153121024f, -0.0852743983f, -0.0643285215f, 0.111785471f, 0.154188231f, 0.17338644f, 0.0797946453f, 0.0668631867f, 0.0743797943f, 0.118677683f, 0.165537134f, 0.0271670986f, 0.0266915336f, -0.0214544833f, 0.0355853103f, 0.176658139f, 0.0671810582f, -0.114977039f, -0.028515894f, 0.021565266f, 0.0668076426f, 0.151432335f, 0.0286767371f, -0.104376554f, -0.152225703f, -0.126531437f, 0.0297278315f, 0.143506184f, 0.019611286f, -0.140320063f, 0.0642113388f, 0.104110129f, 0.00347098522f, 0.101011015f, 0.142636105f, -0.105138965f, -0.13455236f, -0.0063259704f, 0.0162820928f, 0.0925038159f, -0.0505435243f, 0.228240818f, 0.0856750533f, -0.0293188617f, 0.0716777891f, -0.0282753054f, 0.141061559f, 0.169938266f, 0.00681541255f, 0.140973151f, -0.0186571386f, -0.0600880757f, -0.0259827171f, -0.083770752f, -0.0278905332f, 0.0375873893f, -0.148526028f, 0.122804739f, 0.350582838f, -0.0814388022f, 0.0402062051f, -0.0563317649f, 0.139530972f, 0.0578514971f, 0.0674994886f, 0.117542781f, -0.110628016f, 0.0136356605f, -0.0577855371f, -0.0589623712f, 0.169605404f, -0.100477897f, 0.101632141f, 0.111083128f, 0.0636828393f, -0.101134129f, -0.0523922741f, 0.0614592098f, 0.08254987f, 0.0947658569f, 0.11102882f, -0.00486064143f, -0.0404011831f, -0.025486201f, -0.0216523129f, 0.0256209522f, -0.0995875448f, 0.0716724396f, 0.0585664697f, 0.0380796902f, 0.108427554f, -0.0737647116f, -0.382444352f, 0.071577616f, 0.0874552578f, -0.0327545367f, 0.0620120876f, -0.0469963253f, 0.0369513147f, -0.098791413f, 0.0834170356f, -0.077535145f, 0.0899046063f, 0.0810909718f, 0.129054591f, 0.0931189731f, 0.107107237f, 0.12638016f, 0.158385321f, 0.00977887306f, 0.10788314f, 0.0229688399f, 0.0182863101f, 0.0651890337f, -0.0717743337f, -0.0621241443f, -0.228488684f, -0.0971744135f, 0.0655497089f, -0.0742739812f, 0.0892728269f, 0.0435669608f, 0.0287747905f, 0.119719163f, 0.0676535815f, 0.13872841f, 0.319145769f, 0.191060886f, -0.0233782399f, -0.0455576479f, -0.145598903f, 0.0268988684f, 0.148111403f, 0.00483703008f, 0.125541389f, 0.0555013902f, -0.10036511f, 0.153132856f, 0.0428292528f, -0.0596763678f, 0.0298281368f, 0.0609674975f, 0.0592361502f, -0.0530377775f, 0.084694095f, 0.0511066057f, 0.0604663789f, 0.283638269f, 0.134019032f, -0.0847409293f, -0.0316614993f, 0.0152074462f, 0.0257237796f, 0.0278955419f, 0.00290633948f, -0.0347232856f, 0.0946291536f, -0.0772126764f, -0.0466119051f, 0.15684557f, -0.346014529f, -0.16651623f, 0.0131949978f, 0.0124328732f, 0.000429959706f, 0.0346952416f, -0.0687672198f, -0.0790166259f, 0.0785065144f, 0.0153815215f, 0.118022904f, 0.146132603f, 0.143321857f, 0.00563500961f, 0.102027513f, 0.0511785634f, 0.0252255537f, -0.0239846781f, -0.00812616292f, -0.0154695287f, 0.126051232f, -0.0437272638f, 0.178552032f, 0.0606499687f, 0.0158017278f, -0.0649905205f, 0.00459715864f, 0.0576665662f, -0.0927422941f, 0.172102481f, 0.201549739f, -0.161805347f, -0.145533144f, -0.00948355347f, 0.296731532f, -0.124715023f, -0.233811781f, 0.0988643989f, 0.117695779f, 0.132205993f, 0.0871664882f, -0.152428597f, -0.0116556492f, -0.15765132f, 0.1314165f, 0.0725957602f, -0.0548560768f, 0.0846773461f, -0.00739104161f, 0.0223399699f, 0.00413030246f, 0.0707634836f, 0.103595711f, -0.018675033f, 0.0208058823f, -0.0261062849f, 0.100504346f, -0.0953970701f, 0.0423405357f, 0.100512587f, -0.0166537538f, 0.157729194f, -0.00550914789f, -0.00609438214f, -0.0781863332f, -0.0235939659f, 0.0979483053f, 0.214899644f, -0.352786958f, -0.122097641f, -0.0356345102f, -0.128209576f, 0.141480148f, 0.0366234854f, 0.0208767876f, 0.0272588339f, -0.0679647028f, -0.0736162663f, 0.186442718f, 0.00697210524f, 0.0947807208f, 0.120640077f, 0.0845200568f, 0.143694773f, 0.109220609f, 0.0370308049f, 0.0139392f, 0.0207370017f, 0.0733739957f, 0.0757325217f, -0.0796956345f, 0.119190373f, 0.297559887f, 0.0917179361f, 0.0164918657f, -0.0614689589f, -0.062392801f, -0.0301177576f, 0.154257521f, -0.00328074628f, 0.067839101f, -0.072865814f, -0.368222505f, 0.0399011895f, 0.225670353f, 0.0232249591f, -0.0857454017f, 0.13792856f, 0.111400187f, 0.011676508f, -0.116799161f, 0.0217915811f, 0.0429730751f, 0.0351492725f, -0.0248814616f, -0.0441440046f, 0.0229848083f, -0.0263831168f, 0.0222023726f, -0.0414843783f, -0.0314963497f, 0.0376022421f, -0.0369355418f, -0.00213426934f};
    static constexpr float B1[12] = {-0.0907577574f, -0.0136039555f, 0.148919001f, -0.0723823607f, 0.14720057f, 0.14671877f, -0.0096257031f, 0.0337827019f, 0.105173327f, 0.169392183f, -0.0817636475f, 0.139122486f};
    static constexpr float W2[72] = {-0.103514411f, 0.047433462f, -0.247533128f, 0.264679104f, -0.133033425f, -0.136831149f, 0.327432662f, 0.131608948f, -0.0423758402f, -0.203955725f, 0.239104256f, 0.338449836f, 0.18405202f, -0.0842768401f, -0.0238215793f, 0.179175064f, -0.1293464f, -0.121941373f, -0.307522476f, 0.203424945f, -0.180065691f, -0.0471665561f, -0.0772997439f, -0.0154307475f, -0.00364749692f, -0.102746598f, 0.410243005f, -0.263332367f, -0.125623628f, 0.0616468303f, -0.172535732f, 0.0610383861f, -0.388825864f, 0.371797025f, -0.411792308f, -0.186399654f, -0.146006927f, -0.0288793165f, 0.190026447f, 0.326169282f, 0.261626303f, -0.188895881f, 0.310918331f, -0.122360215f, 0.0568748116f, -0.211843997f, 0.00269979704f, 0.257225037f, 0.0983521715f, -0.0620359406f, -0.182363674f, -0.0641381592f, -0.226181865f, 0.204294756f, -0.335459203f, 0.19748123f, -0.18967554f, 0.305927843f, -0.284944087f, -0.00728392927f, -0.209574923f, -0.294038236f, 0.108530991f, -0.181929171f, 0.152649224f, 0.322765917f, -0.26518485f, 0.0500246473f, -0.0568175912f, 0.111095481f, -0.335752726f, -0.318841666f};
    static constexpr float B2[6] = {0.0668660626f, -0.146059602f, -0.115928359f, -0.0153510207f, 0.170746446f, 0.102797613f};
    static constexpr float W3[18] = {0.139109358f, 0.0242330916f, -0.293121904f, 0.207865596f, -0.100197829f, -0.135519683f, 0.074903354f, 0.00169213093f, 0.187972188f, 0.0709970519f, -0.0378903039f, -0.0848642215f, 0.139481008f, -0.00834472291f, -0.295376927f, 0.208003417f, -0.101541586f, -0.137124583f};
    static constexpr float B3[3] = {-0.00903598685f, -0.00150470249f, -0.00986603275f};

    const float sigma = noiseContext != nullptr && noiseContext->available &&
                        std::isfinite(noiseContext->sigmaChroma)
            ? std::clamp(noiseContext->sigmaChroma, 0.0f, 0.05f)
            : 0.0f;

    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) * 24u;
        stats->allocatedScratchBytesThisShot = scratchReused
                ? static_cast<uint64_t>(normalizedBayer.total()) * 12u
                : static_cast<uint64_t>(normalizedBayer.total()) * 24u;
    }

    const auto kernelStart = DemosaicClock::now();
    cv::parallel_for_(cv::Range(0, normalizedBayer.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            const cv::Vec3f* base = baseline.ptr<cv::Vec3f>(y);
            for (int x = 0; x < normalizedBayer.cols; ++x) {
                float features[34]{};
                int featureIndex = 0;
                for (int dy = -2; dy <= 2; ++dy) {
                    for (int dx = -2; dx <= 2; ++dx) {
                        features[featureIndex++] = sampleClamped(normalizedBayer, x + dx, y + dy);
                    }
                }
                const int sitePhase = ((y & 1) << 1) | (x & 1);
                features[25 + sitePhase] = 1.0f;
                features[29 + pattern] = 1.0f;
                features[33] = sigma;

                float hidden1[12]{};
                for (int i = 0; i < 12; ++i) {
                    float sum = B1[i];
                    for (int j = 0; j < 34; ++j) sum += W1[i * 34 + j] * features[j];
                    hidden1[i] = std::max(0.0f, sum);
                }
                float hidden2[6]{};
                for (int i = 0; i < 6; ++i) {
                    float sum = B2[i];
                    for (int j = 0; j < 12; ++j) sum += W2[i * 12 + j] * hidden1[j];
                    hidden2[i] = std::max(0.0f, sum);
                }
                cv::Vec3f value = base[x];
                for (int channel = 0; channel < 3; ++channel) {
                    float residual = B3[channel];
                    for (int j = 0; j < 6; ++j) residual += W3[channel * 6 + j] * hidden2[j];
                    residual = std::clamp(residual, -0.25f, 0.25f);
                    value[channel] = finiteSceneLinear(value[channel] + residual);
                }
                out[x] = value;
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
    cv::Mat guide = gAmazeScratch.guide;
    cv::Mat rgb = gAmazeScratch.rgb;
    if (stats != nullptr) {
        stats->setupMs = elapsedDemosaicMs(setupStart);
        stats->allocationReuse = scratchReused;
        stats->workingBufferBytesEstimate = static_cast<uint64_t>(normalizedBayer.total()) *
                (sizeof(float) + 2u * sizeof(float) + 3u * sizeof(float));
        stats->allocatedScratchBytesThisShot = scratchReused ? 0u : stats->workingBufferBytesEstimate;
    }

    const int rows = normalizedBayer.rows;
    const int cols = normalizedBayer.cols;
    const auto greenPassStart = DemosaicClock::now();
    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* outGreen = green.ptr<float>(y);
            cv::Vec2f* outGuide = guide.ptr<cv::Vec2f>(y);
            const float* raw = normalizedBayer.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                outGreen[x] = cfaColorAt(pattern, x, y) == 1
                        ? finiteSceneLinear(raw[x])
                        : amazeGreenGuideAt(normalizedBayer, pattern, x, y, noiseContext);
                outGuide[x][0] = amazeNyquistPreScore(normalizedBayer, x, y, noiseContext);
                outGuide[x][1] = 0.0f;
            }
        }
    });
    const float greenPassMs = elapsedDemosaicMs(greenPassStart);

    const float redChromaRisk = amazeNoiseAwareChromaRisk(cfaEvidence, 0);
    const float blueChromaRisk = amazeNoiseAwareChromaRisk(cfaEvidence, 2);
    const auto reconstructStart = DemosaicClock::now();
    cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* out = rgb.ptr<cv::Vec3f>(y);
            const float* raw = normalizedBayer.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                const int color = cfaColorAt(pattern, x, y);
                const float g = green.ptr<float>(y)[x];
                float r = g;
                float b = g;
                if (color == 0) {
                    r = raw[x];
                    b = amazeInterpolateDifferenceDiagonal(
                            normalizedBayer, green, guide, pattern, x, y, 2,
                            blueChromaRisk, noiseContext);
                } else if (color == 2) {
                    b = raw[x];
                    r = amazeInterpolateDifferenceDiagonal(
                            normalizedBayer, green, guide, pattern, x, y, 0,
                            redChromaRisk, noiseContext);
                } else {
                    const bool horizontalRed = cfaColorAt(pattern, x - 1, y) == 0;
                    r = amazeInterpolateDifferenceAxial(
                            normalizedBayer, green, guide, pattern, x, y, 0,
                            horizontalRed, redChromaRisk, noiseContext);
                    b = amazeInterpolateDifferenceAxial(
                            normalizedBayer, green, guide, pattern, x, y, 2,
                            !horizontalRed, blueChromaRisk, noiseContext);
                }
                out[x] = cv::Vec3f(finiteSceneLinear(r), finiteSceneLinear(g), finiteSceneLinear(b));
            }
        }
    });
    const float reconstructMs = elapsedDemosaicMs(reconstructStart);

    if (stats != nullptr) {
        stats->kernelMs = greenPassMs + reconstructMs;
        stats->pureKernelMs = stats->kernelMs;
        stats->inputPrepMs = greenPassMs;
        stats->postCopyMs = reconstructMs;
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

    // Identity guard: Malvar output must be invariant to SPECTRA/CFA evidence and
    // physical noise context. The shared arguments exist only to keep the common caller ABI stable.
    bool contextIndependentPassed = true;
    DemosaicCfaEvidence aggressiveEvidence{};
    aggressiveEvidence.available = true;
    aggressiveEvidence.commonOpponentSupport = 1.0f;
    aggressiveEvidence.structureProtection = 0.0f;
    aggressiveEvidence.fineCorrectionConfidence = 1.0f;
    aggressiveEvidence.midCorrectionConfidence = 1.0f;
    aggressiveEvidence.lowCorrectionConfidence = 1.0f;
    aggressiveEvidence.redOpponentCorrectionConfidence = 1.0f;
    aggressiveEvidence.blueOpponentCorrectionConfidence = 1.0f;

    DemosaicNoiseContext aggressiveNoise{};
    aggressiveNoise.available = true;
    aggressiveNoise.sigmaY = 0.010f;
    aggressiveNoise.sigmaChroma = 0.018f;
    aggressiveNoise.pressure = 1.0f;

    for (int pattern = CFA_RGGB;
         pattern <= CFA_BGGR && contextIndependentPassed;
         ++pattern) {
        cv::Mat varying(13, 13, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.040f +
                         0.006f * static_cast<float>(x) +
                         0.004f * static_cast<float>(y) +
                         (((x + y) & 1) != 0 ? 0.018f : -0.011f) +
                         ((x % 3) == 0 ? 0.025f : 0.0f);
            }
        }

        const cv::Mat baseline =
                demosaicMalvar2004ToRgb32f(varying, pattern).clone();
        const cv::Mat contextual = demosaicMalvar2004ToRgb32f(
                varying,
                pattern,
                nullptr,
                &aggressiveEvidence,
                &aggressiveNoise
        );
        for (int y = 0; y < baseline.rows && contextIndependentPassed; ++y) {
            const cv::Vec3f* a = baseline.ptr<cv::Vec3f>(y);
            const cv::Vec3f* b = contextual.ptr<cv::Vec3f>(y);
            for (int x = 0; x < baseline.cols && contextIndependentPassed; ++x) {
                for (int channel = 0; channel < 3; ++channel) {
                    if (!nearlyEqual(a[x][channel], b[x][channel], 1.0e-7f)) {
                        contextIndependentPassed = false;
                        break;
                    }
                }
            }
        }
    }

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.kernelDcGainPassed &&
                    contextIndependentPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";syntheticChannels=" << result.syntheticChannelsPassed
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";kernelDcGain=" << result.kernelDcGainPassed
            << ";greenDc=" << std::setprecision(6) << greenDc
            << ";chromaAtGreenDc=" << chromaAtGreenDc
            << ";oppositeDc=" << oppositeDc
            << ";contextIndependent=" << contextIndependentPassed
            << ";implementation=PURE_MALVAR_HE_CUTLER_2004";
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateRcdInspiredImplementation() {
    // Legacy validation symbol now validates the Neural JDD implementation behind bridge slot 3.
    DemosaicValidationResult result{};
    result.kernelDcGainPassed = true; // Not a fixed linear kernel.
    result.samplePreservationPassed = true; // Not applicable: joint denoise may alter sampled sensels.
    result.constantFieldPassed = true;
    result.syntheticChannelsPassed = true;

    DemosaicNoiseContext zeroNoise{};
    zeroNoise.available = true;
    zeroNoise.sigmaChroma = 0.0f;
    zeroNoise.sigmaY = 0.0f;
    zeroNoise.pressure = 0.0f;
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR && result.constantFieldPassed; ++pattern) {
        const cv::Mat constant(13, 13, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat reconstructed = demosaicRcdInspiredToRgb32f(
                constant, pattern, nullptr, nullptr, &zeroNoise);
        for (int y = 0; y < reconstructed.rows && result.constantFieldPassed; ++y) {
            const cv::Vec3f* row = reconstructed.ptr<cv::Vec3f>(y);
            for (int x = 0; x < reconstructed.cols; ++x) {
                for (int channel = 0; channel < 3; ++channel) {
                    result.constantFieldPassed = result.constantFieldPassed &&
                            std::isfinite(row[x][channel]) &&
                            std::abs(row[x][channel] - 0.25f) <= 0.012f;
                }
            }
        }
    }

    cv::Mat reference(5, 5, CV_32FC1);
    for (int y = 0; y < 5; ++y) {
        float* row = reference.ptr<float>(y);
        for (int x = 0; x < 5; ++x) row[x] = 0.01f * static_cast<float>(1 + y * 5 + x);
    }
    DemosaicNoiseContext referenceNoise{};
    referenceNoise.available = true;
    referenceNoise.sigmaY = 0.01f;
    referenceNoise.sigmaChroma = 0.01f;
    referenceNoise.pressure = 0.25f;
    const cv::Mat referenceRgb = demosaicRcdInspiredToRgb32f(
            reference, CFA_RGGB, nullptr, nullptr, &referenceNoise);
    const cv::Vec3f actual = referenceRgb.at<cv::Vec3f>(2, 2);
    result.referenceVectorPassed =
            nearlyEqual(actual[0], 0.13051581f, 2.5e-5f) &&
            nearlyEqual(actual[1], 0.12960460f, 2.5e-5f) &&
            nearlyEqual(actual[2], 0.12944049f, 2.5e-5f);
    result.channelOrderPassed = result.referenceVectorPassed;
    result.syntheticChannelsPassed = result.constantFieldPassed && result.referenceVectorPassed;
    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.kernelDcGainPassed &&
                    result.referenceVectorPassed;

    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";trainedReferenceVector=" << result.referenceVectorPassed
            << ";samplePreservation=not_applicable_joint_denoise"
            << ";features=25cfa+4sitePhase+4bayerPattern+noiseSigma"
            << ";network=34x12x6x3_relu_residual"
            << ";trainedNoisyCfaToCleanRgb=true"
            << ";implementation=BNCAM_NEURAL_JDD_0046";
    result.details = details.str();
    return result;
}

DemosaicValidationResult validateAmazeInspiredImplementation() {
    DemosaicValidationResult result{};
    result.kernelDcGainPassed = true;
    result.constantFieldPassed = true;
    result.samplePreservationPassed = true;

    float worstConstantError = 0.0f;
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        const cv::Mat constant(17, 17, CV_32FC1, cv::Scalar(0.25f));
        const cv::Mat neutral = demosaicAmazeInspiredToRgb32f(constant, pattern);
        for (int y = 0; y < neutral.rows && result.constantFieldPassed; ++y) {
            const cv::Vec3f* row = neutral.ptr<cv::Vec3f>(y);
            for (int x = 0; x < neutral.cols; ++x) {
                for (int c = 0; c < 3; ++c) {
                    worstConstantError = std::max(worstConstantError, std::abs(row[x][c] - 0.25f));
                }
                result.constantFieldPassed = result.constantFieldPassed &&
                        nearlyEqual(row[x][0], 0.25f, 5.0e-5f) &&
                        nearlyEqual(row[x][1], 0.25f, 5.0e-5f) &&
                        nearlyEqual(row[x][2], 0.25f, 5.0e-5f);
            }
        }

        cv::Mat varying(17, 17, CV_32FC1);
        for (int y = 0; y < varying.rows; ++y) {
            float* row = varying.ptr<float>(y);
            for (int x = 0; x < varying.cols; ++x) {
                row[x] = 0.04f + 0.003f * static_cast<float>(x) +
                         0.002f * static_cast<float>(y) +
                         (((x / 2 + y / 2) & 1) ? 0.012f : -0.006f);
            }
        }
        const cv::Mat reconstructed = demosaicAmazeInspiredToRgb32f(varying, pattern);
        for (int y = 0; y < varying.rows; ++y) {
            const float* input = varying.ptr<float>(y);
            const cv::Vec3f* output = reconstructed.ptr<cv::Vec3f>(y);
            for (int x = 0; x < varying.cols; ++x) {
                const int sampledChannel = cfaColorAt(pattern, x, y);
                result.samplePreservationPassed = result.samplePreservationPassed &&
                        nearlyEqual(output[x][sampledChannel], input[x], 5.0e-5f) &&
                        std::isfinite(output[x][0]) && std::isfinite(output[x][1]) && std::isfinite(output[x][2]);
            }
        }
    }

    // The AMaZE CPU reference intentionally reuses one process-wide scratch RGB buffer.
    // Validation needs two snapshots at the same time, so detach each result before invoking
    // the next reconstruction. Without clone(), the blue probe overwrites the red probe and
    // creates a false runtime-validation failure even though the reconstruction itself is valid.
    const cv::Mat red = demosaicAmazeInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 0, 17), CFA_RGGB).clone();
    const cv::Mat blue = demosaicAmazeInspiredToRgb32f(
            syntheticChannelMosaic(CFA_RGGB, 2, 17), CFA_RGGB).clone();
    result.channelOrderPassed =
            nearlyEqual(red.at<cv::Vec3f>(8, 8)[0], 1.0f, 5.0e-5f) &&
            nearlyEqual(blue.at<cv::Vec3f>(9, 9)[2], 1.0f, 5.0e-5f);
    result.syntheticChannelsPassed = result.channelOrderPassed;

    const cv::Mat flat(21, 21, CV_32FC1, cv::Scalar(0.20f));
    cv::Mat nyquistTexture(21, 21, CV_32FC1);
    for (int y = 0; y < nyquistTexture.rows; ++y) {
        float* row = nyquistTexture.ptr<float>(y);
        for (int x = 0; x < nyquistTexture.cols; ++x) {
            row[x] = (((x / 2 + y / 2) & 1) != 0) ? 0.34f : 0.08f;
        }
    }
    const float flatNyquist = amazeNyquistPreScore(flat, 10, 10, nullptr);
    const float textureNyquist = amazeNyquistPreScore(nyquistTexture, 10, 10, nullptr);
    result.referenceVectorPassed = flatNyquist < 1.0e-5f && textureNyquist > 0.25f;

    result.passed = result.constantFieldPassed && result.syntheticChannelsPassed &&
                    result.channelOrderPassed && result.samplePreservationPassed &&
                    result.kernelDcGainPassed && result.referenceVectorPassed;
    std::ostringstream details;
    details << std::boolalpha
            << "passed=" << result.passed
            << ";constantField=" << result.constantFieldPassed
            << ";worstConstantError=" << worstConstantError
            << ";channelOrderRgb=" << result.channelOrderPassed
            << ";samplePreservation=" << result.samplePreservationPassed
            << ";nyquistFlat=" << flatNyquist
            << ";nyquistTexture=" << textureNyquist
            << ";greenGuidePass=true"
            << ";residentIntermediateReuse=true"
            << ";nyquistAreaInterpolation=true"
            << ";zipperDifferenceClamp=true"
            << ";implementation=BNCAM_AMAZE_CLEANROOM_MULTIPASS_V2";
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
