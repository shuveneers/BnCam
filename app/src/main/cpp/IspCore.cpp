#include "ProfileColorManagement.h"
#include "SrgbByteLut.h"
#include "Bgr8PublicationStats.h"
#include "ObjectiveValidationSummary.h"
#include "JpegEncodingPolicy.h"
#include "IspCore.h"
#include "RawCfaLevelMapping.h"
#include "RawDefectCorrectionPolicy.h"
#include "RawSceneBlackAuthorityPolicy.h"
#include "RawSpatialNoiseCalibrationPolicy.h"
#include "RawGreenSplitPolicy.h"
#include "RawAdaptiveExposurePolicy.h"
#include "SpectraContextFusionNoRegret.h"
#include "SpectraChromaNoRegretPolicy.h"
#include "SpectraContextFusionChromaAuthority.h"
#include "SpectraResidualSeedConfidence.h"
#include "PhysicalAwbEstimator.h"
#include "SensorColorScienceV2.h"
#include "RawCameraColorCharacterizationOwnership.h"
#include "RawCameraColorProfileRegistry.h"
#include "RawCameraColorProfileResolver.h"
#include "RawCameraDngForwardTransform.h"
#include "RawCameraCalibratedHueSatRuntime.h"
#include "RawCameraHueSatMapTelemetry.h"
#include "RawCameraHueSatMapNoisePropagation.h"
#include "HighlightGamutProtectionV2.h"
#include "SpectraMultiscaleContext.h"
#include "SpectraMultiscaleResidualConsensus.h"
#include "SpectraMultiscaleChromaContext.h"
#include "SpectraCfaOrthonormalSupport.h"
#include "SpectraCfaSurfaceClassifier.h"
#include "Demosaic.h"
#include "FastLocalLaplacianPolicy.h"
#include "ProfileToneRenderPolicy.h"
#include "PerceptualDetailPolicy.h"
#include "SpectraCfaChromaConfidence.h"
#include "vulkan/VulkanRuntime.h"
#include "vulkan/NativeStageHeartbeat.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <iomanip>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <initializer_list>
#include <limits>
#include <numeric>
#include <sstream>
#include <utility>
#include <android/log.h>
#include <opencv2/imgcodecs.hpp>

#define ISP_LOG_TAG "BnCam_IspCore"
#define ISP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ISP_LOG_TAG, __VA_ARGS__)
#define ISP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ISP_LOG_TAG, __VA_ARGS__)

namespace {

thread_local UltraHdrGainmapArtifact g_threadLocalUltraHdrGainmapArtifact{};


constexpr int CFA_RGGB = 0;
constexpr int CFA_GRBG = 1;
constexpr int CFA_GBRG = 2;
constexpr int CFA_BGGR = 3;

// SPECTRA production runtime gates.
// Temporal observation, S/O adaptation and fusion weighting live in DngMerger,
// where genuinely aligned warm-buffer RAW frames are available. IspCore consumes
// the resulting immutable capture snapshot and owns the four pre-demosaic passes.




struct ThreadLocalIspStats {
    double meanSensorNoiseVariance = 0.0;
    double minSensorNoiseVariance = 0.0;
    double maxSensorNoiseVariance = 0.0;
    double meanNormalizedNoiseSignal = 0.0;
    double minNormalizedNoiseSignal = 0.0;
    double maxNormalizedNoiseSignal = 0.0;
    size_t sensorNoiseVarianceSamples = 0;
    bool noiseModelApplied = false;
    std::string noiseModelReason = "not_evaluated";

    // Filter effectiveness instrumentation
    uint64_t processedPixelCount = 0;
    uint64_t changedPixelCount = 0;
    float changedPixelFraction = 0.0f;
    float meanAbsLumaDelta = 0.0f;
    float meanAbsChromaDelta = 0.0f;
    float maxLumaDelta = 0.0f;
    float maxChromaDelta = 0.0f;
    float avgNeighbourAcceptanceRate = 0.0f;
    float avgNonCentreSampleWeight = 0.0f;
    float avgTotalFilterWeight = 0.0f;
    float avgAppliedBlend = 0.0f;
    float edgeProtectedPixelFraction = 0.0f;

    float absoluteMeanLumaSigma = 0.0f;
    float absoluteMeanChromaSigma = 0.0f;
    float effectiveLumaSigma = 0.0f;
    float effectiveChromaSigma = 0.0f;
    float lumaRangeThresholdMin = 0.0f;
    float lumaRangeThresholdMean = 0.0f;
    float lumaRangeThresholdMax = 0.0f;
    float chromaRangeThresholdMin = 0.0f;
    float chromaRangeThresholdMean = 0.0f;
    float chromaRangeThresholdMax = 0.0f;
    float preDenoiseResidualEstimate = 0.0f;
    float postDenoiseResidualEstimate = 0.0f;
    float postSharpenResidualEstimate = 0.0f;

    // Advanced sensor noise calibration & channel propagation telemetry
};

thread_local ThreadLocalIspStats g_threadLocalIspStats;


int safeJpegQuality(int value) {
    return std::clamp(value <= 0 ? 98 : value, 1, 100);
}

std::string fmtDouble(double value, int precision = 4) {
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(precision) << value;
    return oss.str();
}

using IspClock = std::chrono::steady_clock;
using IspTimePoint = std::chrono::time_point<IspClock>;

inline float elapsedMs(IspTimePoint start, IspTimePoint end = IspClock::now()) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(end - start).count());
}

float spectraPercentile(std::vector<float> values, float fraction, float fallback = 0.0f) {
    values.erase(
            std::remove_if(values.begin(), values.end(), [](float value) {
                return !std::isfinite(value);
            }),
            values.end()
    );
    if (values.empty()) return fallback;
    const float clamped = std::clamp(fraction, 0.0f, 1.0f);
    const size_t index = static_cast<size_t>(std::lround(
            static_cast<double>(clamped) * static_cast<double>(values.size() - 1u)
    ));
    std::nth_element(values.begin(), values.begin() + static_cast<long>(index), values.end());
    return values[index];
}

int cfaPatternOrDefault(int cfaPattern) {
    return (cfaPattern >= CFA_RGGB && cfaPattern <= CFA_BGGR) ? cfaPattern : CFA_RGGB;
}

int cfaColorChannel(int cfaPattern, int x, int y) {
    const int xm = x & 1;
    const int ym = y & 1;
    switch (cfaPatternOrDefault(cfaPattern)) {
        case CFA_RGGB:
            return ym == 0 ? (xm == 0 ? 0 : 1) : (xm == 0 ? 2 : 3);
        case CFA_GRBG:
            return ym == 0 ? (xm == 0 ? 1 : 0) : (xm == 0 ? 3 : 2);
        case CFA_GBRG:
            return ym == 0 ? (xm == 0 ? 1 : 3) : (xm == 0 ? 0 : 2);
        case CFA_BGGR:
            return ym == 0 ? (xm == 0 ? 3 : 1) : (xm == 0 ? 2 : 0);
        default:
            return ym == 0 ? (xm == 0 ? 0 : 1) : (xm == 0 ? 2 : 3);
    }
}

int cfaCanonicalNoiseChannel(int cfaPattern, int x, int y) {
    return bncam::raw::canonicalPlaneAtMosaicSite(cfaPattern, x, y);
}

float safeWbGain(float value) {
    if (!std::isfinite(value)) return 1.0f;
    return std::clamp(value, 0.10f, 8.0f);
}

float safeLensGain(float value) {
    if (!std::isfinite(value)) return 1.0f;
    return std::clamp(value, 0.25f, 3.5f);
}

bool lensShadingMapValid(const IspFrameMetadata& meta) {
    const int cols = meta.lensShadingColumns;
    const int rows = meta.lensShadingRows;
    if (!meta.lensShadingFromMetadata || cols <= 0 || rows <= 0) return false;
    const size_t needed = static_cast<size_t>(cols) * static_cast<size_t>(rows) * 4u;
    return meta.lensShadingMap.size() >= needed;
}

float lensShadingGainAt(const IspFrameMetadata& meta, int channel, int x, int y, int width, int height) {
    if (!lensShadingMapValid(meta)) return 1.0f;
    const int cols = meta.lensShadingColumns;
    const int rows = meta.lensShadingRows;
    const float colF = width > 1 ? (static_cast<float>(x) * static_cast<float>(cols - 1) / static_cast<float>(width - 1)) : 0.0f;
    const float rowF = height > 1 ? (static_cast<float>(y) * static_cast<float>(rows - 1) / static_cast<float>(height - 1)) : 0.0f;
    const int c0 = std::clamp(static_cast<int>(std::floor(colF)), 0, cols - 1);
    const int r0 = std::clamp(static_cast<int>(std::floor(rowF)), 0, rows - 1);
    const int c1 = std::min(c0 + 1, cols - 1);
    const int r1 = std::min(r0 + 1, rows - 1);
    const float tx = std::clamp(colF - static_cast<float>(c0), 0.0f, 1.0f);
    const float ty = std::clamp(rowF - static_cast<float>(r0), 0.0f, 1.0f);

    const auto gain = [&](int c, int r) -> float {
        const size_t idx = (static_cast<size_t>(r) * static_cast<size_t>(cols) + static_cast<size_t>(c)) * 4u + static_cast<size_t>(std::clamp(channel, 0, 3));
        return idx < meta.lensShadingMap.size() ? safeLensGain(meta.lensShadingMap[idx]) : 1.0f;
    };

    const float g00 = gain(c0, r0);
    const float g10 = gain(c1, r0);
    const float g01 = gain(c0, r1);
    const float g11 = gain(c1, r1);
    const float top = g00 + (g10 - g00) * tx;
    const float bottom = g01 + (g11 - g01) * tx;
    return safeLensGain(top + (bottom - top) * ty);
}

struct LensShadingLookup {
    bool valid = false;
    int cols = 0;
    int rows = 0;
    std::vector<float> safeMap;
    std::vector<int> x0;
    std::vector<int> x1;
    std::vector<float> tx;
    std::vector<int> y0;
    std::vector<int> y1;
    std::vector<float> ty;

    LensShadingLookup(const IspFrameMetadata& meta, int width, int height) {
        if (!lensShadingMapValid(meta) || width <= 0 || height <= 0) return;

        cols = meta.lensShadingColumns;
        rows = meta.lensShadingRows;
        const size_t needed = static_cast<size_t>(cols) * static_cast<size_t>(rows) * 4u;
        safeMap.resize(needed, 1.0f);
        for (size_t i = 0; i < needed; ++i) {
            safeMap[i] = safeLensGain(meta.lensShadingMap[i]);
        }

        x0.resize(static_cast<size_t>(width));
        x1.resize(static_cast<size_t>(width));
        tx.resize(static_cast<size_t>(width));
        y0.resize(static_cast<size_t>(height));
        y1.resize(static_cast<size_t>(height));
        ty.resize(static_cast<size_t>(height));

        const float widthDen = static_cast<float>(std::max(1, width - 1));
        const float heightDen = static_cast<float>(std::max(1, height - 1));
        for (int x = 0; x < width; ++x) {
            const float colF = width > 1 ? (static_cast<float>(x) * static_cast<float>(cols - 1) / widthDen) : 0.0f;
            const int c0 = std::clamp(static_cast<int>(std::floor(colF)), 0, cols - 1);
            x0[static_cast<size_t>(x)] = c0;
            x1[static_cast<size_t>(x)] = std::min(c0 + 1, cols - 1);
            tx[static_cast<size_t>(x)] = std::clamp(colF - static_cast<float>(c0), 0.0f, 1.0f);
        }
        for (int y = 0; y < height; ++y) {
            const float rowF = height > 1 ? (static_cast<float>(y) * static_cast<float>(rows - 1) / heightDen) : 0.0f;
            const int r0 = std::clamp(static_cast<int>(std::floor(rowF)), 0, rows - 1);
            y0[static_cast<size_t>(y)] = r0;
            y1[static_cast<size_t>(y)] = std::min(r0 + 1, rows - 1);
            ty[static_cast<size_t>(y)] = std::clamp(rowF - static_cast<float>(r0), 0.0f, 1.0f);
        }

        valid = true;
    }

    inline float rawGain(int c, int r, int channel) const {
        const size_t idx =
                (static_cast<size_t>(r) * static_cast<size_t>(cols) + static_cast<size_t>(c)) * 4u +
                static_cast<size_t>(std::clamp(channel, 0, 3));
        return idx < safeMap.size() ? safeMap[idx] : 1.0f;
    }

    inline float gain(int channel, int x, int y) const {
        if (!valid) return 1.0f;
        const size_t sx = static_cast<size_t>(x);
        const size_t sy = static_cast<size_t>(y);
        const int c0 = x0[sx];
        const int c1 = x1[sx];
        const int r0 = y0[sy];
        const int r1 = y1[sy];
        const float mixX = tx[sx];
        const float mixY = ty[sy];

        const float g00 = rawGain(c0, r0, channel);
        const float g10 = rawGain(c1, r0, channel);
        const float g01 = rawGain(c0, r1, channel);
        const float g11 = rawGain(c1, r1, channel);
        const float top = g00 + (g10 - g00) * mixX;
        const float bottom = g01 + (g11 - g01) * mixX;
        return safeLensGain(top + (bottom - top) * mixY);
    }
};

int normalizeOutputRotationDegrees(int rotationDegrees) {
    int r = rotationDegrees % 360;
    if (r < 0) r += 360;
    if (r < 45) return 0;
    if (r < 135) return 90;
    if (r < 225) return 180;
    if (r < 315) return 270;
    return 0;
}

inline size_t flatIndex2d(int row, int columns, int column) {
    return static_cast<size_t>(row) * static_cast<size_t>(columns) +
            static_cast<size_t>(column);
}

struct SpatialNoiseMap {
    int rawWidth = 0;
    int rawHeight = 0;
    int gridWidth = 0;
    int gridHeight = 0;
    // The downstream Vulkan kernels consume this field as a local/mean sigma ratio.
    // Before final physical provenance is available it may temporarily contain absolute
    // RAW sigma. Phase 3 replaces it with a dimensionless post-LSC physical shape so an
    // upstream RAW-domain sigma can never masquerade as an absolute post-tone sigma.
    std::vector<float> tileSigma;
    float minSigma = 0.0f;
    float meanSigma = 0.0f;
    float maxSigma = 0.0f;
    bool relativePhysicalShape = false;
    bool lensShadingSquaredIncluded = false;
    std::string authority = "UNSET";
};

SpatialNoiseMap g_spatialNoiseMap;

bool installPhysicalSpatialNoiseShape(
        const SpectraProvenanceField& field,
        int rawWidth,
        int rawHeight
) {
    if (field.gridCols <= 0 || field.gridRows <= 0 ||
        field.tiles.size() != static_cast<std::size_t>(field.gridCols) *
                static_cast<std::size_t>(field.gridRows)) {
        return false;
    }

    // The provenance field is sampled before lens shading is applied to the RAW pixels, but
    // predictedVisibleVarianceByChannel already contains SensorNoiseProfile S/O followed by
    // the exact mapped per-channel LensShadingMap gain squared. This is therefore the right
    // place to build spatial noise authority without rescanning or reading back the full RAW.
    std::array<double, 4> weightedVisibleVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> varianceWeight{0.0, 0.0, 0.0, 0.0};
    std::size_t validTileCount = 0u;
    for (const auto& tile : field.tiles) {
        if (!tile.valid || tile.sampleCount == 0u) continue;
        const double weight = static_cast<double>(tile.sampleCount);
        bool complete = true;
        for (int channel = 0; channel < 4; ++channel) {
            const double variance = static_cast<double>(
                    tile.predictedVisibleVarianceByChannel[static_cast<std::size_t>(channel)]);
            if (!(variance > 0.0) || !std::isfinite(variance)) {
                complete = false;
                break;
            }
        }
        if (!complete) continue;
        for (int channel = 0; channel < 4; ++channel) {
            const double variance = static_cast<double>(
                    tile.predictedVisibleVarianceByChannel[static_cast<std::size_t>(channel)]);
            weightedVisibleVariance[static_cast<std::size_t>(channel)] += variance * weight;
            varianceWeight[static_cast<std::size_t>(channel)] += weight;
        }
        ++validTileCount;
    }
    if (validTileCount == 0u) return false;

    std::array<double, 4> meanVisibleVariance{0.0, 0.0, 0.0, 0.0};
    for (int channel = 0; channel < 4; ++channel) {
        const std::size_t index = static_cast<std::size_t>(channel);
        if (!(varianceWeight[index] > 0.0)) return false;
        meanVisibleVariance[index] = weightedVisibleVariance[index] / varianceWeight[index];
        if (!(meanVisibleVariance[index] > 0.0) || !std::isfinite(meanVisibleVariance[index])) {
            return false;
        }
    }

    const auto projectedVariances = [](const std::array<double, 4>& cfaVariance) {
        const double red = std::max(0.0, cfaVariance[0]);
        const double green = 0.5 * (std::max(0.0, cfaVariance[1]) +
                                    std::max(0.0, cfaVariance[2]));
        const double blue = std::max(0.0, cfaVariance[3]);
        // These projections are used only to collapse a local four-channel spatial *shape*
        // into the existing scalar Vulkan ratio. Absolute Y/R-G/B-G covariance is propagated
        // separately through demosaic -> AWB -> CCM -> tone and remains the downstream owner.
        return std::array<double, 3>{
                0.2126 * 0.2126 * red + 0.7152 * 0.7152 * green +
                        0.0722 * 0.0722 * blue,
                red + green,
                blue + green
        };
    };
    const std::array<double, 3> meanProjected = projectedVariances(meanVisibleVariance);
    if (!(meanProjected[0] > 0.0) || !(meanProjected[1] > 0.0) ||
        !(meanProjected[2] > 0.0)) {
        return false;
    }

    SpatialNoiseMap physicalShape{};
    physicalShape.rawWidth = rawWidth;
    physicalShape.rawHeight = rawHeight;
    physicalShape.gridWidth = field.gridCols;
    physicalShape.gridHeight = field.gridRows;
    physicalShape.tileSigma.assign(field.tiles.size(), 1.0f);
    physicalShape.relativePhysicalShape = true;
    physicalShape.lensShadingSquaredIncluded = true;
    physicalShape.authority =
            "CAMERA2_SENSOR_NOISE_PROFILE_PLUS_PER_CHANNEL_LENS_SHADING_GAIN_SQUARED";

    double sigmaShapeSum = 0.0;
    std::size_t sigmaShapeCount = 0u;
    for (std::size_t tileIndex = 0u; tileIndex < field.tiles.size(); ++tileIndex) {
        const auto& tile = field.tiles[tileIndex];
        if (!tile.valid || tile.sampleCount == 0u) continue;
        std::array<double, 4> localVariance{};
        bool complete = true;
        for (int channel = 0; channel < 4; ++channel) {
            const double variance = static_cast<double>(
                    tile.predictedVisibleVarianceByChannel[static_cast<std::size_t>(channel)]);
            if (!(variance > 0.0) || !std::isfinite(variance)) {
                complete = false;
                break;
            }
            localVariance[static_cast<std::size_t>(channel)] = variance;
        }
        if (!complete) continue;

        const std::array<double, 3> localProjected = projectedVariances(localVariance);
        // The post-demosaic resident contract currently has one spatial ratio. Use the largest
        // physically predicted Y/R-G/B-G variance ratio so channel-dependent shading can never
        // make one opponent axis quieter on paper than the sensor model predicts. Structure and
        // colour-edge gates still decide whether that available authority may touch the pixel.
        const double varianceRatio = std::clamp(std::max({
                localProjected[0] / meanProjected[0],
                localProjected[1] / meanProjected[1],
                localProjected[2] / meanProjected[2]
        }), 0.0625, 16.0);
        const float sigmaShape = static_cast<float>(std::sqrt(varianceRatio));
        physicalShape.tileSigma[tileIndex] = sigmaShape;
        sigmaShapeSum += static_cast<double>(sigmaShape);
        ++sigmaShapeCount;
    }
    if (sigmaShapeCount == 0u) return false;

    // Normalize to mean 1.0. The Vulkan pass multiplies this relative field by the absolute
    // covariance propagated in the pass's own domain, eliminating the old RAW/post-tone unit mix.
    const float shapeMean = static_cast<float>(sigmaShapeSum /
            static_cast<double>(sigmaShapeCount));
    if (!(shapeMean > 1.0e-6f) || !std::isfinite(shapeMean)) return false;
    float minShape = std::numeric_limits<float>::infinity();
    float maxShape = 0.0f;
    for (std::size_t tileIndex = 0u; tileIndex < physicalShape.tileSigma.size(); ++tileIndex) {
        const auto& tile = field.tiles[tileIndex];
        float normalizedShape = tile.valid && tile.sampleCount > 0u
                ? physicalShape.tileSigma[tileIndex] / shapeMean
                : 1.0f;
        normalizedShape = std::clamp(normalizedShape, 0.25f, 4.0f);
        physicalShape.tileSigma[tileIndex] = normalizedShape;
        minShape = std::min(minShape, normalizedShape);
        maxShape = std::max(maxShape, normalizedShape);
    }
    physicalShape.meanSigma = 1.0f;
    physicalShape.minSigma = std::isfinite(minShape) ? minShape : 1.0f;
    physicalShape.maxSigma = std::max(1.0f, maxShape);
    g_spatialNoiseMap = std::move(physicalShape);
    return true;
}

void sampleNoiseModelFromProductionRaw(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta
) {
    auto& stats = g_threadLocalIspStats;
    stats.sensorNoiseVarianceSamples = 0;
    stats.meanSensorNoiseVariance = 0.0;
    stats.minSensorNoiseVariance = 0.0;
    stats.maxSensorNoiseVariance = 0.0;
    stats.meanNormalizedNoiseSignal = 0.0;
    stats.minNormalizedNoiseSignal = 0.0;
    stats.maxNormalizedNoiseSignal = 0.0;
    stats.noiseModelApplied = false;
    stats.noiseModelReason = "not_evaluated";

    g_spatialNoiseMap = SpatialNoiseMap();

    if (meta.calibration.noiseModelMode == 0) {
        stats.noiseModelReason = "off";
        return;
    }
    if (!meta.calibration.hasNoiseProfile || !meta.calibration.noiseProfileApplied ||
        meta.calibration.noiseProfilePairCount <= 0) {
        stats.noiseModelReason = "missing_or_invalid_effective_so";
        return;
    }
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) {
        stats.noiseModelReason = "normalized_raw_unavailable";
        return;
    }

    const int rawW = raw.mosaic.cols;
    const int rawH = raw.mosaic.rows;
    const int targetTileCount = 768;
    const float aspect = static_cast<float>(rawW) / static_cast<float>(std::max(1, rawH));
    const int gridW = std::clamp(static_cast<int>(std::round(std::sqrt(targetTileCount * aspect))), 8, 128);
    const int gridH = std::clamp(static_cast<int>(std::round(targetTileCount / static_cast<float>(gridW))), 8, 128);

    g_spatialNoiseMap.rawWidth = rawW;
    g_spatialNoiseMap.rawHeight = rawH;
    g_spatialNoiseMap.gridWidth = gridW;
    g_spatialNoiseMap.gridHeight = gridH;
    g_spatialNoiseMap.tileSigma.assign(static_cast<size_t>(gridW) * static_cast<size_t>(gridH), 0.0f);
    g_spatialNoiseMap.authority = "CAMERA2_SENSOR_NOISE_PROFILE_RAW_DOMAIN_TEMPORARY";

    const int pattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    const float tileW = static_cast<float>(rawW) / static_cast<float>(gridW);
    const float tileH = static_cast<float>(rawH) / static_cast<float>(gridH);

    double totalVarianceSum = 0.0;
    double xSum = 0.0;
    double xMin = std::numeric_limits<double>::infinity();
    double xMax = -std::numeric_limits<double>::infinity();
    double varianceMin = std::numeric_limits<double>::infinity();
    double varianceMax = -std::numeric_limits<double>::infinity();
    size_t validTiles = 0;
    size_t validSamples = 0;

    for (int gy = 0; gy < gridH; ++gy) {
        const int y0 = static_cast<int>(gy * tileH);
        const int y1 = std::min(rawH, static_cast<int>((gy + 1) * tileH));
        for (int gx = 0; gx < gridW; ++gx) {
            const int x0 = static_cast<int>(gx * tileW);
            const int x1 = std::min(rawW, static_cast<int>((gx + 1) * tileW));

            double tileVarSum = 0.0;
            size_t tileCount = 0;
            for (int y = y0; y < y1; ++y) {
                const float* row = raw.mosaic.ptr<float>(y);
                for (int x = x0; x < x1; ++x) {
                    const int channel = cfaColorChannel(pattern, x, y);
                    if (channel < 0 || channel >= meta.calibration.noiseProfilePairCount) continue;
                    const float sampleVal = row[x];
                    if (!std::isfinite(sampleVal)) continue;
                    const double normalized = std::clamp(static_cast<double>(sampleVal), 0.0, 1.0);
                    const double slopeS = meta.calibration.effectiveNoiseProfile[channel * 2];
                    const double offsetO = meta.calibration.effectiveNoiseProfile[channel * 2 + 1];
                    const double variance = slopeS * normalized + offsetO;
                    if (std::isfinite(variance) && variance >= 0.0) {
                        tileVarSum += variance;
                        xSum += normalized;
                        xMin = std::min(xMin, normalized);
                        xMax = std::max(xMax, normalized);
                        ++tileCount;
                        ++validSamples;
                    }
                }
            }

            const double meanTileVar = tileCount > 0 ? (tileVarSum / tileCount) : 0.0;
            const float tileSigmaVal = static_cast<float>(std::sqrt(std::max(0.0, meanTileVar)));
            g_spatialNoiseMap.tileSigma[flatIndex2d(gy, gridW, gx)] = tileSigmaVal;

            if (tileCount > 0) {
                totalVarianceSum += meanTileVar;
                varianceMin = std::min(varianceMin, meanTileVar);
                varianceMax = std::max(varianceMax, meanTileVar);
                ++validTiles;
            }
        }
    }

    stats.sensorNoiseVarianceSamples = validSamples;
    if (validTiles == 0 || validSamples == 0) {
        stats.meanNormalizedNoiseSignal = 0.0;
        stats.minNormalizedNoiseSignal = 0.0;
        stats.maxNormalizedNoiseSignal = 0.0;
        stats.meanSensorNoiseVariance = 0.0;
        stats.minSensorNoiseVariance = 0.0;
        stats.maxSensorNoiseVariance = 0.0;
        stats.noiseModelApplied = false;
        stats.noiseModelReason = "zero_valid_raw_samples";
        return;
    }

    const float meanVar = static_cast<float>(totalVarianceSum / validTiles);
    g_spatialNoiseMap.meanSigma = std::sqrt(std::max(0.0f, meanVar));
    g_spatialNoiseMap.minSigma = std::sqrt(std::max(0.0f, static_cast<float>(varianceMin)));
    g_spatialNoiseMap.maxSigma = std::sqrt(std::max(0.0f, static_cast<float>(varianceMax)));

    stats.meanNormalizedNoiseSignal = xSum / static_cast<double>(validSamples);
    stats.minNormalizedNoiseSignal = std::isfinite(xMin) ? xMin : 0.0;
    stats.maxNormalizedNoiseSignal = std::isfinite(xMax) ? xMax : 0.0;
    stats.meanSensorNoiseVariance = meanVar;
    stats.minSensorNoiseVariance = std::isfinite(varianceMin) ? varianceMin : 0.0;
    stats.maxSensorNoiseVariance = std::isfinite(varianceMax) ? varianceMax : 0.0;
    stats.noiseModelApplied = true;
    stats.noiseModelReason = "measurement_only_noise_model_no_classical_pixel_authority";
}

bool sampleNoiseModelFromResidentRaw(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta,
        std::uint64_t rawNormalizeGeneration,
        std::string* failureReason = nullptr
) {
    auto& stats = g_threadLocalIspStats;
    stats.sensorNoiseVarianceSamples = 0;
    stats.meanSensorNoiseVariance = 0.0;
    stats.minSensorNoiseVariance = 0.0;
    stats.maxSensorNoiseVariance = 0.0;
    stats.meanNormalizedNoiseSignal = 0.0;
    stats.minNormalizedNoiseSignal = 0.0;
    stats.maxNormalizedNoiseSignal = 0.0;
    stats.noiseModelApplied = false;
    stats.noiseModelReason = "not_evaluated";
    g_spatialNoiseMap = SpatialNoiseMap();

    auto fail = [&](const std::string& reason) {
        stats.noiseModelReason = reason;
        if (failureReason != nullptr) *failureReason = reason;
        return false;
    };

    if (meta.calibration.noiseModelMode == 0) {
        stats.noiseModelReason = "off";
        if (failureReason != nullptr) failureReason->clear();
        return true;
    }
    if (!meta.calibration.hasNoiseProfile || !meta.calibration.noiseProfileApplied ||
        meta.calibration.noiseProfilePairCount <= 0) {
        stats.noiseModelReason = "missing_or_invalid_effective_so";
        if (failureReason != nullptr) failureReason->clear();
        return true;
    }
    if (!raw.valid || raw.info.width <= 0 || raw.info.height <= 0 || rawNormalizeGeneration == 0u) {
        return fail("resident_normalized_raw_unavailable");
    }

    const int rawW = raw.info.width;
    const int rawH = raw.info.height;
    const int targetTileCount = 768;
    const float aspect = static_cast<float>(rawW) / static_cast<float>(std::max(1, rawH));
    const int gridW = std::clamp(
            static_cast<int>(std::round(std::sqrt(targetTileCount * aspect))), 8, 128);
    const int gridH = std::clamp(
            static_cast<int>(std::round(targetTileCount / static_cast<float>(gridW))), 8, 128);

    bncam::vulkan::SpectraNoiseMapPlannerRequest request{};
    request.frameWidth = static_cast<std::uint32_t>(rawW);
    request.frameHeight = static_cast<std::uint32_t>(rawH);
    request.cfaPattern = static_cast<std::uint32_t>(
            cfaPatternOrDefault(raw.info.effectiveCfaPattern));
    request.gridWidth = static_cast<std::uint32_t>(gridW);
    request.gridHeight = static_cast<std::uint32_t>(gridH);

    const auto gpu = bncam::vulkan::VulkanRuntime::instance()
            .executeRawNoiseMapPlannerFromNormalize(request, rawNormalizeGeneration);
    const std::size_t expectedTiles = static_cast<std::size_t>(gridW) *
            static_cast<std::size_t>(gridH);
    const std::uint64_t expectedCompactBytes = static_cast<std::uint64_t>(expectedTiles) *
            16u * sizeof(float);
    if (!gpu.success || !gpu.residentInputUsed || gpu.tiles.size() != expectedTiles ||
        gpu.compactBytes != expectedCompactBytes) {
        return fail(gpu.failureReason.empty()
                ? "resident_noise_map_compact_reduction_failed"
                : "resident_noise_map_compact_reduction_failed:" + gpu.failureReason);
    }

    g_spatialNoiseMap.rawWidth = rawW;
    g_spatialNoiseMap.rawHeight = rawH;
    g_spatialNoiseMap.gridWidth = gridW;
    g_spatialNoiseMap.gridHeight = gridH;
    g_spatialNoiseMap.tileSigma.assign(expectedTiles, 0.0f);
    g_spatialNoiseMap.authority = "CAMERA2_SENSOR_NOISE_PROFILE_RAW_DOMAIN_TEMPORARY_GPU_COMPACT";

    double totalVarianceSum = 0.0;
    double xSum = 0.0;
    double xMin = std::numeric_limits<double>::infinity();
    double xMax = -std::numeric_limits<double>::infinity();
    double varianceMin = std::numeric_limits<double>::infinity();
    double varianceMax = -std::numeric_limits<double>::infinity();
    std::size_t validTiles = 0u;
    std::size_t validSamples = 0u;
    const int activeChannels = std::clamp(meta.calibration.noiseProfilePairCount, 0, 4);

    for (std::size_t tileIndex = 0u; tileIndex < expectedTiles; ++tileIndex) {
        const auto& tile = gpu.tiles[tileIndex];
        double tileVarSum = 0.0;
        std::size_t tileCount = 0u;
        double tileSignalSum = 0.0;
        double tileMin = std::numeric_limits<double>::infinity();
        double tileMax = -std::numeric_limits<double>::infinity();

        for (int channel = 0; channel < activeChannels; ++channel) {
            const std::uint32_t count = tile.sampleCount[static_cast<std::size_t>(channel)];
            if (count == 0u) continue;
            const double signalSum = static_cast<double>(
                    tile.signalSum[static_cast<std::size_t>(channel)]);
            const double slopeS = meta.calibration.effectiveNoiseProfile[channel * 2];
            const double offsetO = meta.calibration.effectiveNoiseProfile[channel * 2 + 1];
            const double channelVarianceSum = slopeS * signalSum +
                    offsetO * static_cast<double>(count);
            if (!std::isfinite(channelVarianceSum) || channelVarianceSum < 0.0) continue;

            tileVarSum += channelVarianceSum;
            tileSignalSum += signalSum;
            tileCount += static_cast<std::size_t>(count);
            tileMin = std::min(tileMin, static_cast<double>(
                    tile.signalMin[static_cast<std::size_t>(channel)]));
            tileMax = std::max(tileMax, static_cast<double>(
                    tile.signalMax[static_cast<std::size_t>(channel)]));
        }

        const double meanTileVar = tileCount > 0u ? tileVarSum / static_cast<double>(tileCount) : 0.0;
        g_spatialNoiseMap.tileSigma[tileIndex] = static_cast<float>(
                std::sqrt(std::max(0.0, meanTileVar)));
        if (tileCount > 0u) {
            totalVarianceSum += meanTileVar;
            xSum += tileSignalSum;
            xMin = std::min(xMin, tileMin);
            xMax = std::max(xMax, tileMax);
            validSamples += tileCount;
            varianceMin = std::min(varianceMin, meanTileVar);
            varianceMax = std::max(varianceMax, meanTileVar);
            ++validTiles;
        }
    }

    stats.sensorNoiseVarianceSamples = validSamples;
    if (validTiles == 0u || validSamples == 0u) {
        stats.meanNormalizedNoiseSignal = 0.0;
        stats.minNormalizedNoiseSignal = 0.0;
        stats.maxNormalizedNoiseSignal = 0.0;
        stats.meanSensorNoiseVariance = 0.0;
        stats.minSensorNoiseVariance = 0.0;
        stats.maxSensorNoiseVariance = 0.0;
        stats.noiseModelApplied = false;
        stats.noiseModelReason = "zero_valid_raw_samples";
        if (failureReason != nullptr) failureReason->clear();
        return true;
    }

    const float meanVar = static_cast<float>(totalVarianceSum / static_cast<double>(validTiles));
    g_spatialNoiseMap.meanSigma = std::sqrt(std::max(0.0f, meanVar));
    g_spatialNoiseMap.minSigma = std::sqrt(std::max(0.0f, static_cast<float>(varianceMin)));
    g_spatialNoiseMap.maxSigma = std::sqrt(std::max(0.0f, static_cast<float>(varianceMax)));

    stats.meanNormalizedNoiseSignal = xSum / static_cast<double>(validSamples);
    stats.minNormalizedNoiseSignal = std::isfinite(xMin) ? xMin : 0.0;
    stats.maxNormalizedNoiseSignal = std::isfinite(xMax) ? xMax : 0.0;
    stats.meanSensorNoiseVariance = meanVar;
    stats.minSensorNoiseVariance = std::isfinite(varianceMin) ? varianceMin : 0.0;
    stats.maxSensorNoiseVariance = std::isfinite(varianceMax) ? varianceMax : 0.0;
    stats.noiseModelApplied = true;
    stats.noiseModelReason = "applied_gpu_exact_compact_measurement_only";
    if (failureReason != nullptr) failureReason->clear();
    return true;
}

// Physical S/O remains measurement-only here. Filter authority is intentionally absent;
// future SPECTRA Neural conditioning consumes the measured noise state explicitly.

const char* noiseModelModeName(int mode) {
    switch (mode) {
        case 1: return "Auto";
        case 2: return "Manual";
        default: return "Off";
    }
}

void rotateMatForOutput(cv::Mat& mat, int rotationDegrees) {
    const int rotation = normalizeOutputRotationDegrees(rotationDegrees);
    int rotationCode;
    if (rotation == 90) {
        rotationCode = cv::ROTATE_90_CLOCKWISE;
    } else if (rotation == 180) {
        rotationCode = cv::ROTATE_180;
    } else if (rotation == 270) {
        rotationCode = cv::ROTATE_90_COUNTERCLOCKWISE;
    } else {
        return;
    }

    cv::Mat rotated;
    cv::rotate(mat, rotated, rotationCode);
    mat = std::move(rotated);
}



inline float rawColorSmoothstepCpu(float edge0, float edge1, float value) noexcept {
    if (!(edge1 > edge0) || !std::isfinite(value)) return value >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline cv::Vec3f rawColorLinearSrgbToOklabCpu(const cv::Vec3f& rgb) noexcept {
    const float l = 0.4122214708f * rgb[0] + 0.5363325363f * rgb[1] + 0.0514459929f * rgb[2];
    const float m = 0.2119034982f * rgb[0] + 0.6806995451f * rgb[1] + 0.1073969566f * rgb[2];
    const float ss = 0.0883024619f * rgb[0] + 0.2817188376f * rgb[1] + 0.6299787005f * rgb[2];
    const float lp = std::cbrt(std::isfinite(l) ? l : 0.0f);
    const float mp = std::cbrt(std::isfinite(m) ? m : 0.0f);
    const float sp = std::cbrt(std::isfinite(ss) ? ss : 0.0f);
    return cv::Vec3f(
            0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp,
            1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp,
            0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp);
}

inline cv::Vec3f rawColorOklabToLinearSrgbCpu(const cv::Vec3f& lab) noexcept {
    const float lp = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2];
    const float mp = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2];
    const float sp = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2];
    const float l = lp * lp * lp;
    const float m = mp * mp * mp;
    const float ss = sp * sp * sp;
    return cv::Vec3f(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * ss,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * ss,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * ss);
}

inline bool rawColorInsideUnitGamutCpu(const cv::Vec3f& rgb) noexcept {
    return std::isfinite(rgb[0]) && std::isfinite(rgb[1]) && std::isfinite(rgb[2]) &&
            std::min({rgb[0], rgb[1], rgb[2]}) >= 0.0f &&
            std::max({rgb[0], rgb[1], rgb[2]}) <= 1.0f;
}

inline cv::Vec3f rawColorCompressUnitGamutPerceptualCpu(const cv::Vec3f& input) noexcept {
    if (!std::isfinite(input[0]) || !std::isfinite(input[1]) || !std::isfinite(input[2])) {
        return cv::Vec3f(0.0f);
    }
    if (rawColorInsideUnitGamutCpu(input)) return input;
    cv::Vec3f lab = rawColorLinearSrgbToOklabCpu(input);
    if (!std::isfinite(lab[0]) || !std::isfinite(lab[1]) || !std::isfinite(lab[2])) {
        return cv::Vec3f(0.0f);
    }
    lab[0] = std::clamp(lab[0], 0.0f, 1.0f);
    const float chroma = std::sqrt(std::max(0.0f, lab[1] * lab[1] + lab[2] * lab[2]));
    if (chroma <= 1.0e-7f) {
        cv::Vec3f neutral = rawColorOklabToLinearSrgbCpu(cv::Vec3f(lab[0], 0.0f, 0.0f));
        for (int c = 0; c < 3; ++c) neutral[c] = std::clamp(neutral[c], 0.0f, 1.0f);
        return neutral;
    }
    const float hueA = lab[1] / chroma;
    const float hueB = lab[2] / chroma;
    float low = 0.0f;
    float high = chroma;
    for (int i = 0; i < 5; ++i) {
        const float mid = 0.5f * (low + high);
        const cv::Vec3f candidate = rawColorOklabToLinearSrgbCpu(
                cv::Vec3f(lab[0], hueA * mid, hueB * mid));
        if (rawColorInsideUnitGamutCpu(candidate)) low = mid;
        else high = mid;
    }
    cv::Vec3f result = rawColorOklabToLinearSrgbCpu(cv::Vec3f(lab[0], hueA * low, hueB * low));
    for (int c = 0; c < 3; ++c) result[c] = std::clamp(result[c], 0.0f, 1.0f);
    return result;
}

inline cv::Vec3f phase5PbrNeutralCpu(const cv::Vec3f& input) noexcept {
    cv::Vec3f color(
            std::max(0.0f, input[0]),
            std::max(0.0f, input[1]),
            std::max(0.0f, input[2]));
    constexpr float startCompression = 0.8f - 0.04f;
    constexpr float desaturation = 0.15f;
    constexpr float shoulderPrepStart = 0.60f;
    const float inputPeak = std::max({color[0], color[1], color[2]});
    const float x = std::min({color[0], color[1], color[2]});
    const float canonicalOffset = x < 0.08f ? x - 6.25f * x * x : 0.04f;
    const float t = std::clamp((inputPeak - shoulderPrepStart) / (startCompression - shoulderPrepStart), 0.0f, 1.0f);
    const float shoulderPrepAuthority = t * t * (3.0f - 2.0f * t);
    color -= cv::Vec3f(canonicalOffset * shoulderPrepAuthority,
                       canonicalOffset * shoulderPrepAuthority,
                       canonicalOffset * shoulderPrepAuthority);
    const float peak = std::max({color[0], color[1], color[2]});
    if (peak < startCompression) {
        for (int c = 0; c < 3; ++c) color[c] = std::max(0.0f, color[c]);
        return color;
    }
    constexpr float d = 1.0f - startCompression;
    const float newPeak = 1.0f - d * d / (peak + d - startCompression);
    color *= newPeak / std::max(peak, 1.0e-8f);
    const float g = 1.0f - 1.0f / (desaturation * (peak - newPeak) + 1.0f);
    return color * (1.0f - g) + cv::Vec3f(newPeak, newPeak, newPeak) * g;
}

inline float phase5LumaCpu(const cv::Vec3f& rgb) noexcept {
    return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
}

inline cv::Vec3f phase5StrictLumaRatioCpu(
        const cv::Vec3f& rgb, float targetLuma) noexcept {
    const float yOld = std::max(1.0e-7f, phase5LumaCpu(rgb));
    const float yNew = std::max(0.0f, targetLuma);
    return rgb * (yNew / yOld);
}

inline cv::Vec3f phase5CompressUnitGamutCpu(const cv::Vec3f& input) {
    return rawColorCompressUnitGamutPerceptualCpu(input);
}


float evalCurveIsp(const std::vector<float>& curve, float x) {
    if (curve.size() <= 1u) return std::clamp(x, 0.0f, 1.0f);
    const float cx = std::clamp(x, 0.0f, 1.0f);
    const float pos = cx * static_cast<float>(curve.size() - 1u);
    const int idx = std::clamp(static_cast<int>(std::floor(pos)), 0, static_cast<int>(curve.size()) - 1);
    const int next = std::min(idx + 1, static_cast<int>(curve.size()) - 1);
    const float t = pos - static_cast<float>(idx);
    const float a = std::clamp(curve[static_cast<size_t>(idx)], 0.0f, 1.0f);
    const float b = std::clamp(curve[static_cast<size_t>(next)], 0.0f, 1.0f);
    return std::clamp(a + (b - a) * t, 0.0f, 1.0f);
}

bool curveActiveIsp(const std::vector<float>& curve) {
    if (curve.size() <= 1u) return false;
    for (size_t i = 0; i < curve.size(); ++i) {
        const float expected = static_cast<float>(i) / static_cast<float>(curve.size() - 1u);
        if (std::abs(std::clamp(curve[i], 0.0f, 1.0f) - expected) > 0.0015f) return true;
    }
    return false;
}


float evalCurveDerivativeIsp(const std::vector<float>& curve, float x) {
    return static_cast<float>(bncam::spectra2::piecewiseLinearCurveDerivative(curve, x));
}

bncam::spectra2::DerivativeStats derivativeStatsFromSamples(std::vector<double> samples) {
    bncam::spectra2::DerivativeStats stats{};
    samples.erase(
            std::remove_if(samples.begin(), samples.end(), [](double value) {
                return !std::isfinite(value) || value < 0.0;
            }),
            samples.end()
    );
    if (samples.empty()) return stats;
    std::sort(samples.begin(), samples.end());
    const auto percentile = [&](double fraction) -> double {
        const size_t index = std::min(
                samples.size() - 1u,
                static_cast<size_t>(std::floor(
                        fraction * static_cast<double>(samples.size() - 1u)
                ))
        );
        return samples[index];
    };
    double sum = 0.0;
    double sumSquares = 0.0;
    for (double value : samples) {
        sum += value;
        sumSquares += value * value;
    }
    stats.sampleCount = samples.size();
    stats.mean = sum / static_cast<double>(samples.size());
    stats.rms = std::sqrt(sumSquares / static_cast<double>(samples.size()));
    stats.p10 = percentile(0.10);
    stats.p50 = percentile(0.50);
    stats.p90 = percentile(0.90);
    stats.minimum = samples.front();
    stats.maximum = samples.back();
    return stats;
}


std::array<double, 4> meanPredictedVisibleVarianceByChannel(
        const SpectraProvenanceField& field
) {
    std::array<double, 4> sums{0.0, 0.0, 0.0, 0.0};
    std::array<int, 4> counts{0, 0, 0, 0};
    for (const SpectraProvenanceTile& tile : field.tiles) {
        if (!tile.valid) continue;
        for (int channel = 0; channel < 4; ++channel) {
            const double variance = tile.predictedVisibleVarianceByChannel[
                    static_cast<std::size_t>(channel)
            ];
            if (!std::isfinite(variance) || variance < 0.0) continue;
            sums[static_cast<std::size_t>(channel)] += variance;
            counts[static_cast<std::size_t>(channel)]++;
        }
    }
    for (int channel = 0; channel < 4; ++channel) {
        if (counts[static_cast<std::size_t>(channel)] > 0) {
            sums[static_cast<std::size_t>(channel)] /=
                    static_cast<double>(counts[static_cast<std::size_t>(channel)]);
        }
    }
    return sums;
}

bool hasThreeColourVariances(const std::array<double, 4>& variances) {
    const double green = 0.5 * (variances[1] + variances[2]);
    return std::isfinite(variances[0]) && variances[0] > 0.0 &&
            std::isfinite(green) && green > 0.0 &&
            std::isfinite(variances[3]) && variances[3] > 0.0;
}

struct GenericCurvePropagationEstimate {
    bncam::spectra2::DerivativeStats tone{};
    bncam::spectra2::DerivativeStats section{};
    bncam::spectra2::DerivativeStats gamma{};
    bncam::spectra2::DerivativeStats total{};
    bncam::spectra2::DerivativeStats chromaScale{};
};

GenericCurvePropagationEstimate estimateGenericCurvePropagation(
        const NativeRenderQualityConfig& config
) {
    std::vector<double> toneDerivatives;
    std::vector<double> sectionDerivatives;
    std::vector<double> gammaDerivatives;
    std::vector<double> totalDerivatives;
    std::vector<double> chromaScales;
    toneDerivatives.reserve(256);
    sectionDerivatives.reserve(256);
    gammaDerivatives.reserve(256);
    totalDerivatives.reserve(256);
    chromaScales.reserve(256);

    const bool toneActive = curveActiveIsp(config.toneCurve);
    const bool sectionActive = curveActiveIsp(config.sectionCurve);
    const bool gammaActive = curveActiveIsp(config.gammaCurve);

    for (int index = 1; index < 256; ++index) {
        double value = static_cast<double>(index) / 255.0;
        const double toneDerivative = toneActive
                ? evalCurveDerivativeIsp(config.toneCurve, static_cast<float>(value))
                : 1.0;
        if (toneActive) value = evalCurveIsp(config.toneCurve, static_cast<float>(value));
        const double sectionDerivative = sectionActive
                ? evalCurveDerivativeIsp(config.sectionCurve, static_cast<float>(value))
                : 1.0;
        if (sectionActive) value = evalCurveIsp(config.sectionCurve, static_cast<float>(value));
        const double gammaDerivative = gammaActive
                ? evalCurveDerivativeIsp(config.gammaCurve, static_cast<float>(value))
                : 1.0;
        if (gammaActive) value = evalCurveIsp(config.gammaCurve, static_cast<float>(value));

        const double input = static_cast<double>(index) / 255.0;
        toneDerivatives.push_back(toneDerivative);
        sectionDerivatives.push_back(sectionDerivative);
        gammaDerivatives.push_back(gammaDerivative);
        totalDerivatives.push_back(std::max(
                0.0,
                toneDerivative * sectionDerivative * gammaDerivative
        ));
        chromaScales.push_back(std::max(0.0, value / std::max(input, 1.0e-6)));
    }

    GenericCurvePropagationEstimate estimate{};
    estimate.tone = derivativeStatsFromSamples(std::move(toneDerivatives));
    estimate.section = derivativeStatsFromSamples(std::move(sectionDerivatives));
    estimate.gamma = derivativeStatsFromSamples(std::move(gammaDerivatives));
    estimate.total = derivativeStatsFromSamples(std::move(totalDerivatives));
    estimate.chromaScale = derivativeStatsFromSamples(std::move(chromaScales));
    return estimate;
}

bncam::spectra2::DemosaicModel requestedDemosaicNoiseModel(int requestedMode) {
    switch (static_cast<DemosaicMode>(requestedMode)) {
        case DemosaicMode::Bilinear:
            // Legacy bridge value 3 is the persisted identity for RCD Inspired.
            return bncam::spectra2::DemosaicModel::RcdInspired;
        case DemosaicMode::QualityMenon2007:
            // Legacy bridge value 2 is the persisted identity for AMAZE Inspired.
            return bncam::spectra2::DemosaicModel::AmazeInspired;
        case DemosaicMode::NormalMalvar2004:
            return bncam::spectra2::DemosaicModel::Malvar2004;
        case DemosaicMode::Auto:
        default:
            return bncam::spectra2::DemosaicModel::Unknown;
    }
}

bncam::spectra2::DemosaicModel resolvedDemosaicNoiseModel(DemosaicAlgorithm algorithm) {
    switch (algorithm) {
        case DemosaicAlgorithm::RcdInspired:
            return bncam::spectra2::DemosaicModel::RcdInspired;
        case DemosaicAlgorithm::AmazeInspired:
            return bncam::spectra2::DemosaicModel::AmazeInspired;
        case DemosaicAlgorithm::Bilinear:
            return bncam::spectra2::DemosaicModel::Bilinear;
        case DemosaicAlgorithm::Menon2007:
            return bncam::spectra2::DemosaicModel::Menon2007;
        case DemosaicAlgorithm::Malvar2004:
        default:
            return bncam::spectra2::DemosaicModel::Malvar2004;
    }
}

std::array<double, 3> normalizedWbRgb(const FinalSensorCalibrationNative& calibration) {
    const double r = safeWbGain(calibration.effectiveWbGains[0]);
    const double g1 = safeWbGain(calibration.effectiveWbGains[1]);
    const double g2 = safeWbGain(calibration.effectiveWbGains[2]);
    const double b = safeWbGain(calibration.effectiveWbGains[3]);
    const double green = std::max(1.0e-4, 0.5 * (g1 + g2));
    return {r / green, 1.0, b / green};
}

std::array<double, 9> colourMatrixArray(const float* matrix) {
    std::array<double, 9> result{};
    bool valid = matrix != nullptr;
    for (int index = 0; index < 9; ++index) {
        const double value = valid ? static_cast<double>(matrix[index]) : 0.0;
        valid = valid && std::isfinite(value);
        result[static_cast<size_t>(index)] = value;
    }
    if (!valid) {
        result = {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
    }
    return result;
}

struct VisibleResidualMeasurement {
    double varianceY = 0.0;
    double varianceRG = 0.0;
    double varianceBG = 0.0;
    double covarianceRgBg = 0.0;
    int sampleCount = 0;
};

VisibleResidualMeasurement measureVisibleResidual8Bit(const cv::Mat& bgr8) {
    VisibleResidualMeasurement measurement{};
    if (bgr8.empty() || bgr8.type() != CV_8UC3 || bgr8.cols < 5 || bgr8.rows < 5) {
        return measurement;
    }

    constexpr size_t targetSamples = 200000u;
    const size_t interiorPixels = static_cast<size_t>(bgr8.cols - 4) *
            static_cast<size_t>(bgr8.rows - 4);
    const int stride = std::max(
            1,
            static_cast<int>(std::floor(std::sqrt(
                    static_cast<double>(interiorPixels) /
                    static_cast<double>(targetSamples)
            )))
    );

    double sumY2 = 0.0;
    double sumRg2 = 0.0;
    double sumBg2 = 0.0;
    double sumRgBg = 0.0;
    int count = 0;
    const auto opponentAt = [&](int x, int y) -> std::array<double, 3> {
        const cv::Vec3b pixel = bgr8.ptr<cv::Vec3b>(y)[x];
        const double b = static_cast<double>(pixel[0]) / 255.0;
        const double g = static_cast<double>(pixel[1]) / 255.0;
        const double r = static_cast<double>(pixel[2]) / 255.0;
        return {0.2126 * r + 0.7152 * g + 0.0722 * b, r - g, b - g};
    };

    for (int y = 2; y < bgr8.rows - 2; y += stride) {
        for (int x = 2; x < bgr8.cols - 2; x += stride) {
            const auto center = opponentAt(x, y);
            const auto left = opponentAt(x - 1, y);
            const auto right = opponentAt(x + 1, y);
            const auto up = opponentAt(x, y - 1);
            const auto down = opponentAt(x, y + 1);
            const double hpY = center[0] - 0.25 * (left[0] + right[0] + up[0] + down[0]);
            const double hpRg = center[1] - 0.25 * (left[1] + right[1] + up[1] + down[1]);
            const double hpBg = center[2] - 0.25 * (left[2] + right[2] + up[2] + down[2]);
            if (!std::isfinite(hpY) || !std::isfinite(hpRg) || !std::isfinite(hpBg)) continue;
            sumY2 += hpY * hpY;
            sumRg2 += hpRg * hpRg;
            sumBg2 += hpBg * hpBg;
            sumRgBg += hpRg * hpBg;
            count++;
        }
    }

    if (count > 0) {
        // For spatially independent noise the five-point high-pass kernel has energy 1.25.
        // Dividing by it puts the residual proxy back on an approximate per-pixel variance scale.
        // Demosaic-induced spatial correlation and real texture are intentionally not claimed away.
        constexpr double filterEnergyGain = 1.25;
        const double inverse = 1.0 / (static_cast<double>(count) * filterEnergyGain);
        measurement.varianceY = sumY2 * inverse;
        measurement.varianceRG = sumRg2 * inverse;
        measurement.varianceBG = sumBg2 * inverse;
        measurement.covarianceRgBg = sumRgBg * inverse;
        measurement.sampleCount = count;
    }
    return measurement;
}

struct LinearResidualCandidate {
    double hpY = 0.0;
    double hpRg = 0.0;
    double hpBg = 0.0;
    double structure = 0.0;
    int tileIndex = 0;
    double centerY = 0.0;
    double centerRg = 0.0;
    double centerBg = 0.0;
};

struct LinearResidualTileAccumulator {
    double sumY2 = 0.0;
    double sumRg2 = 0.0;
    double sumBg2 = 0.0;
    double sumRgBg = 0.0;
    double sumCenterY = 0.0;
    double sumCenterRg = 0.0;
    double sumCenterBg = 0.0;
    int count = 0;
};

double percentileSorted(const std::vector<double>& sorted, double fraction) {
    if (sorted.empty()) return 0.0;
    const size_t index = std::min(
            sorted.size() - 1u,
            static_cast<size_t>(std::floor(
                    std::clamp(fraction, 0.0, 1.0) *
                    static_cast<double>(sorted.size() - 1u)
            ))
    );
    return sorted[index];
}

bncam::spectra2::ResidualObservation finalizeLinearResidualCandidates(
        const std::vector<LinearResidualCandidate>& candidates,
        const std::string& stage,
        const char* method
) {
    bncam::spectra2::ResidualObservation observation{};
    observation.stage = stage;
    observation.method = method;
    observation.filterEnergyGain = 1.25;
    constexpr int tileColumns = 16;
    constexpr int tileRows = 12;
    observation.totalTileCount = static_cast<size_t>(tileColumns) * static_cast<size_t>(tileRows);
    observation.candidateSampleCount = candidates.size();
    if (candidates.size() < 2048u) {
        observation.status = "INSUFFICIENT_VALID_LINEAR_SAMPLES";
        return observation;
    }

    std::vector<double> structures;
    structures.reserve(candidates.size());
    for (const LinearResidualCandidate& candidate : candidates) {
        structures.push_back(candidate.structure);
    }
    std::sort(structures.begin(), structures.end());
    const double p35Structure = percentileSorted(structures, 0.35);
    observation.structureThreshold = std::clamp(1.35 * p35Structure, 0.0015, 0.0300);

    std::array<LinearResidualTileAccumulator, tileColumns * tileRows> tiles{};
    double sumY2 = 0.0;
    double sumRg2 = 0.0;
    double sumBg2 = 0.0;
    double sumRgBg = 0.0;
    for (const LinearResidualCandidate& candidate : candidates) {
        if (candidate.structure > observation.structureThreshold) continue;
        sumY2 += candidate.hpY * candidate.hpY;
        sumRg2 += candidate.hpRg * candidate.hpRg;
        sumBg2 += candidate.hpBg * candidate.hpBg;
        sumRgBg += candidate.hpRg * candidate.hpBg;
        const size_t tileIndex = static_cast<size_t>(std::clamp(
                candidate.tileIndex, 0, tileColumns * tileRows - 1));
        LinearResidualTileAccumulator& tile = tiles[tileIndex];
        tile.sumY2 += candidate.hpY * candidate.hpY;
        tile.sumRg2 += candidate.hpRg * candidate.hpRg;
        tile.sumBg2 += candidate.hpBg * candidate.hpBg;
        tile.sumRgBg += candidate.hpRg * candidate.hpBg;
        tile.sumCenterY += candidate.centerY;
        tile.sumCenterRg += candidate.centerRg;
        tile.sumCenterBg += candidate.centerBg;
        tile.count++;
        observation.acceptedSampleCount++;
    }

    observation.flatSampleFraction = candidates.empty()
            ? 0.0
            : static_cast<double>(observation.acceptedSampleCount) /
                    static_cast<double>(candidates.size());
    if (observation.acceptedSampleCount < 2048u) {
        observation.status = "INSUFFICIENT_FLAT_SUPPORT";
        return observation;
    }

    const double inverse = 1.0 /
            (static_cast<double>(observation.acceptedSampleCount) * observation.filterEnergyGain);
    observation.varianceY = sumY2 * inverse;
    observation.varianceRG = sumRg2 * inverse;
    observation.varianceBG = sumBg2 * inverse;
    observation.covarianceRgBg = sumRgBg * inverse;

    std::vector<double> tileY;
    std::vector<double> tileRg;
    std::vector<double> tileBg;
    std::vector<double> tileRgBg;
    std::vector<double> tileMeanY;
    std::vector<double> tileMeanRg;
    std::vector<double> tileMeanBg;
    std::array<double, tileColumns * tileRows> tileMeanYGrid{};
    std::array<double, tileColumns * tileRows> tileMeanRgGrid{};
    std::array<double, tileColumns * tileRows> tileMeanBgGrid{};
    std::array<bool, tileColumns * tileRows> tileMeanValid{};
    tileY.reserve(tiles.size());
    tileRg.reserve(tiles.size());
    tileBg.reserve(tiles.size());
    tileRgBg.reserve(tiles.size());
    tileMeanY.reserve(tiles.size());
    tileMeanRg.reserve(tiles.size());
    tileMeanBg.reserve(tiles.size());
    for (size_t tileIndex = 0; tileIndex < tiles.size(); ++tileIndex) {
        const LinearResidualTileAccumulator& tile = tiles[tileIndex];
        if (tile.count < 16) continue;
        const double tileInverse = 1.0 /
                (static_cast<double>(tile.count) * observation.filterEnergyGain);
        tileY.push_back(tile.sumY2 * tileInverse);
        tileRg.push_back(tile.sumRg2 * tileInverse);
        tileBg.push_back(tile.sumBg2 * tileInverse);
        tileRgBg.push_back(tile.sumRgBg * tileInverse);
        const double sampleInverse = 1.0 / static_cast<double>(tile.count);
        const double meanY = tile.sumCenterY * sampleInverse;
        const double meanRg = tile.sumCenterRg * sampleInverse;
        const double meanBg = tile.sumCenterBg * sampleInverse;
        tileMeanY.push_back(meanY);
        tileMeanRg.push_back(meanRg);
        tileMeanBg.push_back(meanBg);
        tileMeanYGrid[tileIndex] = meanY;
        tileMeanRgGrid[tileIndex] = meanRg;
        tileMeanBgGrid[tileIndex] = meanBg;
        tileMeanValid[tileIndex] = true;
    }
    observation.validTileCount = tileY.size();
    if (observation.validTileCount < 6u) {
        observation.status = "INSUFFICIENT_VALID_TILES";
        return observation;
    }

    std::sort(tileY.begin(), tileY.end());
    std::sort(tileRg.begin(), tileRg.end());
    std::sort(tileBg.begin(), tileBg.end());
    std::sort(tileRgBg.begin(), tileRgBg.end());
    observation.varianceYP10 = percentileSorted(tileY, 0.10);
    observation.varianceYP50 = percentileSorted(tileY, 0.50);
    observation.varianceYP90 = percentileSorted(tileY, 0.90);
    observation.varianceRGP10 = percentileSorted(tileRg, 0.10);
    observation.varianceRGP50 = percentileSorted(tileRg, 0.50);
    observation.varianceRGP90 = percentileSorted(tileRg, 0.90);
    observation.varianceBGP10 = percentileSorted(tileBg, 0.10);
    observation.varianceBGP50 = percentileSorted(tileBg, 0.50);
    observation.varianceBGP90 = percentileSorted(tileBg, 0.90);
    observation.robustVarianceY = observation.varianceYP50;
    observation.robustVarianceRG = observation.varianceRGP50;
    observation.robustVarianceBG = observation.varianceBGP50;
    observation.robustCovarianceRgBg = percentileSorted(tileRgBg, 0.50);

    // Delta 19: reuse the same sparse flat-region samples to expose the coarse opponent-colour
    // field after demosaic. This is deliberately a FIELD metric, not a noise classification:
    // legitimate large coloured surfaces can also contribute. Later SPECTRA confidence/temporal
    // evidence can decide which part is likely chroma cloud rather than scene colour.
    std::sort(tileMeanY.begin(), tileMeanY.end());
    std::sort(tileMeanRg.begin(), tileMeanRg.end());
    std::sort(tileMeanBg.begin(), tileMeanBg.end());
    observation.lowFrequencyLumaP10 = percentileSorted(tileMeanY, 0.10);
    observation.lowFrequencyLumaP50 = percentileSorted(tileMeanY, 0.50);
    observation.lowFrequencyLumaP90 = percentileSorted(tileMeanY, 0.90);
    observation.lowFrequencyMedianRG = percentileSorted(tileMeanRg, 0.50);
    observation.lowFrequencyMedianBG = percentileSorted(tileMeanBg, 0.50);
    observation.lowFrequencyChromaValidTileCount = observation.validTileCount;

    std::vector<double> fieldEnergy;
    fieldEnergy.reserve(observation.validTileCount);
    static_assert(
            bncam::spectra2::ResidualObservation::kLowFrequencyChromaGridSize ==
                    static_cast<std::size_t>(tileColumns) * static_cast<std::size_t>(tileRows),
            "ResidualObservation chroma field grid must match residual tile grid"
    );
    for (size_t tileIndex = 0; tileIndex < tileMeanValid.size(); ++tileIndex) {
        if (!tileMeanValid[tileIndex]) continue;
        const double dRg = tileMeanRgGrid[tileIndex] - observation.lowFrequencyMedianRG;
        const double dBg = tileMeanBgGrid[tileIndex] - observation.lowFrequencyMedianBG;
        observation.lowFrequencyChromaFieldRG[tileIndex] = static_cast<float>(dRg);
        observation.lowFrequencyChromaFieldBG[tileIndex] = static_cast<float>(dBg);
        observation.lowFrequencyMeanLuma[tileIndex] = static_cast<float>(tileMeanYGrid[tileIndex]);
        observation.lowFrequencyChromaFieldValid[tileIndex] = 1u;
        fieldEnergy.push_back(0.5 * (dRg * dRg + dBg * dBg));
    }
    std::sort(fieldEnergy.begin(), fieldEnergy.end());
    observation.lowFrequencyChromaFieldEnergyP50 = percentileSorted(fieldEnergy, 0.50);
    observation.lowFrequencyChromaFieldEnergyP90 = percentileSorted(fieldEnergy, 0.90);

    std::vector<double> neighbourEnergy;
    neighbourEnergy.reserve(observation.validTileCount * 2u);
    const auto appendNeighbourEnergy = [&](size_t a, size_t b) {
        if (!tileMeanValid[a] || !tileMeanValid[b]) return;
        const double dRg = tileMeanRgGrid[a] - tileMeanRgGrid[b];
        const double dBg = tileMeanBgGrid[a] - tileMeanBgGrid[b];
        neighbourEnergy.push_back(0.5 * (dRg * dRg + dBg * dBg));
    };
    for (int tileYIndex = 0; tileYIndex < tileRows; ++tileYIndex) {
        for (int tileXIndex = 0; tileXIndex < tileColumns; ++tileXIndex) {
            const size_t index = flatIndex2d(tileYIndex, tileColumns, tileXIndex);
            if (tileXIndex + 1 < tileColumns) appendNeighbourEnergy(index, index + 1u);
            if (tileYIndex + 1 < tileRows) appendNeighbourEnergy(
                    index, index + static_cast<size_t>(tileColumns));
        }
    }
    std::sort(neighbourEnergy.begin(), neighbourEnergy.end());
    observation.lowFrequencyChromaNeighbourPairCount = neighbourEnergy.size();
    observation.lowFrequencyChromaNeighbourEnergyP50 = percentileSorted(neighbourEnergy, 0.50);
    observation.lowFrequencyChromaNeighbourEnergyP90 = percentileSorted(neighbourEnergy, 0.90);

    const double rgSpread = observation.varianceRGP50 > bncam::spectra2::kEpsilon
            ? observation.varianceRGP90 / observation.varianceRGP50 - 1.0
            : 10.0;
    const double bgSpread = observation.varianceBGP50 > bncam::spectra2::kEpsilon
            ? observation.varianceBGP90 / observation.varianceBGP50 - 1.0
            : 10.0;
    observation.textureContamination = std::clamp(0.5 * (rgSpread + bgSpread), 0.0, 10.0);

    const double sampleSupport = std::min(
            1.0,
            static_cast<double>(observation.acceptedSampleCount) / 30000.0
    );
    const double tileSupport = std::min(
            1.0,
            static_cast<double>(observation.validTileCount) / 48.0
    );
    const double flatSupport = std::clamp(observation.flatSampleFraction / 0.25, 0.0, 1.0);
    const double contaminationPenalty = 1.0 / (1.0 + 0.35 * observation.textureContamination);
    observation.confidence = std::clamp(
            std::pow(
                    std::max(0.0, sampleSupport * tileSupport * flatSupport * contaminationPenalty),
                    0.25
            ),
            0.0,
            1.0
    );

    if (observation.textureContamination > 4.0) {
        observation.status = "TEXTURE_CONTAMINATED_OBSERVATION";
        observation.confidence *= 0.35;
    } else {
        observation.status = "OBSERVATION_READY_NO_AUTO_CALIBRATION";
    }
    return observation;
}

std::vector<bncam::awb::LinearOpponentSample> phase7AwbSamplesFromCpuFailureRgb(
        const cv::Mat& rgb32f
) {
    std::vector<bncam::awb::LinearOpponentSample> samples;
    if (rgb32f.empty() || rgb32f.type() != CV_32FC3 ||
        rgb32f.cols < 5 || rgb32f.rows < 5) return samples;

    constexpr size_t targetSamples = 180000u;
    const size_t interiorPixels = static_cast<size_t>(rgb32f.cols - 4) *
            static_cast<size_t>(rgb32f.rows - 4);
    const int stride = std::max(1, static_cast<int>(std::floor(std::sqrt(
            static_cast<double>(interiorPixels) / static_cast<double>(targetSamples)))));
    samples.reserve(std::min(targetSamples, interiorPixels));
    const auto opponent = [](const cv::Vec3f& pixel) -> std::array<double, 3> {
        const double r = static_cast<double>(pixel[0]);
        const double g = static_cast<double>(pixel[1]);
        const double b = static_cast<double>(pixel[2]);
        return {0.2126 * r + 0.7152 * g + 0.0722 * b, r - g, b - g};
    };
    for (int y = 2; y < rgb32f.rows - 2; y += stride) {
        for (int x = 2; x < rgb32f.cols - 2; x += stride) {
            const auto center = opponent(rgb32f.at<cv::Vec3f>(y, x));
            const auto left = opponent(rgb32f.at<cv::Vec3f>(y, x - 1));
            const auto right = opponent(rgb32f.at<cv::Vec3f>(y, x + 1));
            const auto up = opponent(rgb32f.at<cv::Vec3f>(y - 1, x));
            const auto down = opponent(rgb32f.at<cv::Vec3f>(y + 1, x));
            if (!std::isfinite(center[0]) || !std::isfinite(center[1]) ||
                !std::isfinite(center[2])) continue;
            const double maxLumaDelta = std::max({
                    std::abs(center[0] - left[0]), std::abs(center[0] - right[0]),
                    std::abs(center[0] - up[0]), std::abs(center[0] - down[0])});
            const double maxChromaDelta = std::max({
                    std::abs(center[1] - left[1]), std::abs(center[1] - right[1]),
                    std::abs(center[1] - up[1]), std::abs(center[1] - down[1]),
                    std::abs(center[2] - left[2]), std::abs(center[2] - right[2]),
                    std::abs(center[2] - up[2]), std::abs(center[2] - down[2])});
            const double structure = std::max(maxLumaDelta, 0.45 * maxChromaDelta);
            const int tileX = std::clamp(x * bncam::awb::kAwbTileColumns /
                    std::max(1, rgb32f.cols), 0, bncam::awb::kAwbTileColumns - 1);
            const int tileY = std::clamp(y * bncam::awb::kAwbTileRows /
                    std::max(1, rgb32f.rows), 0, bncam::awb::kAwbTileRows - 1);
            samples.push_back({
                    center[0], center[1], center[2], structure,
                    tileY * bncam::awb::kAwbTileColumns + tileX});
        }
    }
    return samples;
}

bncam::spectra2::ResidualObservation measureLinearResidualGpuCandidates(
        const std::vector<float>& packed,
        const std::string& stage
) {
    std::vector<LinearResidualCandidate> candidates;
    candidates.reserve(packed.size() / 8u);
    for (size_t base = 0; base + 7u < packed.size(); base += 8u) {
        // Slot 5 encodes validFlag + centerY: invalid=0, valid=[1,2]. This retains the
        // existing eight-float compact record and avoids growing the GPU readback buffer.
        const float encodedValidAndLuma = packed[base + 5u];
        if (encodedValidAndLuma < 1.0f) continue;
        const double centerY = static_cast<double>(encodedValidAndLuma - 1.0f);
        const double hpY = static_cast<double>(packed[base + 0u]);
        const double hpRg = static_cast<double>(packed[base + 1u]);
        const double hpBg = static_cast<double>(packed[base + 2u]);
        const double structure = static_cast<double>(packed[base + 3u]);
        const int tileIndex = static_cast<int>(std::lround(packed[base + 4u]));
        const double centerRg = static_cast<double>(packed[base + 6u]);
        const double centerBg = static_cast<double>(packed[base + 7u]);
        if (!std::isfinite(hpY) || !std::isfinite(hpRg) || !std::isfinite(hpBg) ||
            !std::isfinite(structure) || !std::isfinite(centerY) ||
            !std::isfinite(centerRg) || !std::isfinite(centerBg)) continue;
        candidates.push_back({hpY, hpRg, hpBg, structure, tileIndex, centerY, centerRg, centerBg});
    }
    return finalizeLinearResidualCandidates(
            std::move(candidates), stage, "FLAT_REGION_CROSS_5_LINEAR_RGB_GPU_COMPACT_TILE_MEDIAN");
}

bncam::spectra2::ResidualObservation measureLinearResidualFlatRegions(
        const cv::Mat& rgb32f,
        const std::string& stage
) {
    bncam::spectra2::ResidualObservation observation{};
    observation.stage = stage;
    observation.method = "FLAT_REGION_CROSS_5_LINEAR_RGB_TILE_MEDIAN";
    observation.filterEnergyGain = 1.25;
    constexpr int tileColumns = 16;
    constexpr int tileRows = 12;
    observation.totalTileCount = static_cast<size_t>(tileColumns) * static_cast<size_t>(tileRows);

    if (rgb32f.empty() || rgb32f.type() != CV_32FC3 ||
        rgb32f.cols < 5 || rgb32f.rows < 5) {
        observation.status = "UNAVAILABLE_INVALID_LINEAR_RGB";
        return observation;
    }

    constexpr size_t targetSamples = 180000u;
    const size_t interiorPixels = static_cast<size_t>(rgb32f.cols - 4) *
            static_cast<size_t>(rgb32f.rows - 4);
    const int stride = std::max(
            1,
            static_cast<int>(std::floor(std::sqrt(
                    static_cast<double>(interiorPixels) /
                    static_cast<double>(targetSamples)
            )))
    );

    std::vector<LinearResidualCandidate> candidates;
    candidates.reserve(std::min(targetSamples, interiorPixels));
    const auto opponent = [](const cv::Vec3f& pixel) -> std::array<double, 3> {
        const double r = static_cast<double>(pixel[0]);
        const double g = static_cast<double>(pixel[1]);
        const double b = static_cast<double>(pixel[2]);
        return {
                0.2126 * r + 0.7152 * g + 0.0722 * b,
                r - g,
                b - g
        };
    };

    for (int y = 2; y < rgb32f.rows - 2; y += stride) {
        const cv::Vec3f* row = rgb32f.ptr<cv::Vec3f>(y);
        const cv::Vec3f* rowUp = rgb32f.ptr<cv::Vec3f>(y - 1);
        const cv::Vec3f* rowDown = rgb32f.ptr<cv::Vec3f>(y + 1);
        for (int x = 2; x < rgb32f.cols - 2; x += stride) {
            const cv::Vec3f centerPixel = row[x];
            const cv::Vec3f leftPixel = row[x - 1];
            const cv::Vec3f rightPixel = row[x + 1];
            const cv::Vec3f upPixel = rowUp[x];
            const cv::Vec3f downPixel = rowDown[x];

            bool finitePixels = true;
            bool boundedPixels = true;
            for (const cv::Vec3f& pixel : {centerPixel, leftPixel, rightPixel, upPixel, downPixel}) {
                for (int channel = 0; channel < 3; ++channel) {
                    const double value = static_cast<double>(pixel[channel]);
                    finitePixels = finitePixels && std::isfinite(value);
                    boundedPixels = boundedPixels && value > -0.05 && value < 0.98;
                }
            }
            if (!finitePixels || !boundedPixels) continue;

            const auto center = opponent(centerPixel);
            const auto left = opponent(leftPixel);
            const auto right = opponent(rightPixel);
            const auto up = opponent(upPixel);
            const auto down = opponent(downPixel);
            if (center[0] < 0.008 || center[0] > 0.90) continue;

            const double hpY = center[0] - 0.25 * (left[0] + right[0] + up[0] + down[0]);
            const double hpRg = center[1] - 0.25 * (left[1] + right[1] + up[1] + down[1]);
            const double hpBg = center[2] - 0.25 * (left[2] + right[2] + up[2] + down[2]);
            if (!std::isfinite(hpY) || !std::isfinite(hpRg) || !std::isfinite(hpBg)) continue;

            const auto maxNeighbourDelta = [&](int opponentIndex) -> double {
                return std::max({
                        std::abs(center[opponentIndex] - left[opponentIndex]),
                        std::abs(center[opponentIndex] - right[opponentIndex]),
                        std::abs(center[opponentIndex] - up[opponentIndex]),
                        std::abs(center[opponentIndex] - down[opponentIndex])
                });
            };
            const double lumaStructure = maxNeighbourDelta(0);
            const double chromaStructure = std::max(
                    maxNeighbourDelta(1),
                    maxNeighbourDelta(2)
            );
            // Chroma edges can exist without a strong luma edge. Give them lower but non-zero
            // authority so real coloured structure is not presented as sensor noise.
            const double structure = std::max(lumaStructure, 0.45 * chromaStructure);
            const int tileX = std::clamp(x * tileColumns / rgb32f.cols, 0, tileColumns - 1);
            const int tileY = std::clamp(y * tileRows / rgb32f.rows, 0, tileRows - 1);
            candidates.push_back({
                    hpY, hpRg, hpBg, structure, tileY * tileColumns + tileX,
                    center[0], center[1], center[2]
            });
        }
    }

    return finalizeLinearResidualCandidates(
            std::move(candidates), stage, "FLAT_REGION_CROSS_5_LINEAR_RGB_TILE_MEDIAN");
}




std::string formatCfaChromaConfidenceFields(
        const bncam::spectra2::CfaChromaConfidenceSummary& summary
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(6)
        << "; spectraCfaChromaConfidenceStatus=" << summary.status
        << "; spectraCfaChromaConfidenceAuthority=" << summary.authority
        << "; spectraCfaModelSupport=" << summary.modelSupport
        << "; spectraCfaBandMeasurementSupport=" << summary.bandMeasurementSupport
        << "; spectraCfaRedBlueSampleBalance=" << summary.redBlueSampleBalance
        << "; spectraCfaCommonOpponentSupport=" << summary.commonOpponentSupport
        << "; spectraCfaFineResidualPressure=" << summary.fineResidualPressure
        << "; spectraCfaMidResidualPressure=" << summary.midResidualPressure
        << "; spectraCfaLowResidualPressure=" << summary.lowResidualPressure
        << "; spectraCfaFineCorrectionConfidence=" << summary.fineCorrectionConfidence
        << "; spectraCfaMidCorrectionConfidence=" << summary.midCorrectionConfidence
        << "; spectraCfaLowCorrectionConfidence=" << summary.lowCorrectionConfidence
        << "; spectraCfaRedFineResidualPressure=" << summary.redFineResidualPressure
        << "; spectraCfaRedMidResidualPressure=" << summary.redMidResidualPressure
        << "; spectraCfaRedLowResidualPressure=" << summary.redLowResidualPressure
        << "; spectraCfaBlueFineResidualPressure=" << summary.blueFineResidualPressure
        << "; spectraCfaBlueMidResidualPressure=" << summary.blueMidResidualPressure
        << "; spectraCfaBlueLowResidualPressure=" << summary.blueLowResidualPressure
        << "; spectraCfaRedFineCorrectionConfidence=" << summary.redFineCorrectionConfidence
        << "; spectraCfaRedMidCorrectionConfidence=" << summary.redMidCorrectionConfidence
        << "; spectraCfaRedLowCorrectionConfidence=" << summary.redLowCorrectionConfidence
        << "; spectraCfaBlueFineCorrectionConfidence=" << summary.blueFineCorrectionConfidence
        << "; spectraCfaBlueMidCorrectionConfidence=" << summary.blueMidCorrectionConfidence
        << "; spectraCfaBlueLowCorrectionConfidence=" << summary.blueLowCorrectionConfidence
        << "; spectraCfaRedOpponentCorrectionConfidence="
        << summary.redOpponentCorrectionConfidence
        << "; spectraCfaBlueOpponentCorrectionConfidence="
        << summary.blueOpponentCorrectionConfidence
        << "; spectraCfaShadingRiskAvailable="
        << (summary.shadingRiskAvailable ? "true" : "false")
        << "; spectraCfaShadingRiskApplied="
        << (summary.shadingRiskApplied ? "true" : "false")
        << "; spectraCfaRedShadingRiskSupport=" << summary.redShadingRiskSupport
        << "; spectraCfaBlueShadingRiskSupport=" << summary.blueShadingRiskSupport
        << "; spectraCfaRedShadingRiskMultiplier=" << summary.redShadingRiskMultiplier
        << "; spectraCfaBlueShadingRiskMultiplier=" << summary.blueShadingRiskMultiplier
        << "; spectraCfaStructureProtection=" << summary.structureProtection
        << "; spectraCfaTensorConfidenceP50=" << summary.tensorConfidenceP50
        << "; spectraCfaEdgeProtectedFraction=" << summary.edgeProtectedFraction
        << "; spectraCfaGreenSplitTileConsensus=" << summary.greenSplitTileConsensus
        << "; spectraCfaGreenSplitMad=" << summary.greenSplitMad
        << "; spectraCfaGreenSplitResidualAbs=" << summary.greenSplitResidualAbs
        << "; spectraCfaGreenSplitTileCount=" << summary.greenSplitTileCount
        << "; spectraCfaLowFrequencyFieldSupport=" << summary.lowFrequencyFieldSupport;
    return out.str();
}

std::string formatPropagationStateFields(
        const char* prefix,
        const bncam::spectra2::NoiseState& state
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "; " << prefix << "Stage=" << state.stage
        << "; " << prefix << "Method=" << state.method
        << "; " << prefix << "Status=" << state.status
        << "; " << prefix << "Confidence=" << state.confidence
        << "; " << prefix << "VarianceY=" << state.varianceY
        << "; " << prefix << "VarianceRG=" << state.varianceRG
        << "; " << prefix << "VarianceBG=" << state.varianceBG
        << "; " << prefix << "CovarianceRgBg=" << state.covarianceRgBg
        << "; " << prefix << "CovarianceRgb=[";
    for (size_t index = 0; index < state.covariance.values.size(); ++index) {
        if (index > 0) out << ",";
        out << state.covariance.values[index];
    }
    out << "]";
    return out.str();
}



std::string formatDerivativeStatsFields(
        const char* prefix,
        const bncam::spectra2::DerivativeStats& stats
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "; " << prefix << "SampleCount=" << stats.sampleCount
        << "; " << prefix << "Mean=" << stats.mean
        << "; " << prefix << "Rms=" << stats.rms
        << "; " << prefix << "P10=" << stats.p10
        << "; " << prefix << "P50=" << stats.p50
        << "; " << prefix << "P90=" << stats.p90
        << "; " << prefix << "Min=" << stats.minimum
        << "; " << prefix << "Max=" << stats.maximum;
    return out.str();
}

std::string formatResidualObservationFields(
        const char* prefix,
        const bncam::spectra2::ResidualObservation& observation
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "; " << prefix << "Stage=" << observation.stage
        << "; " << prefix << "Method=" << observation.method
        << "; " << prefix << "Status=" << observation.status
        << "; " << prefix << "Confidence=" << observation.confidence
        << "; " << prefix << "FilterEnergyGain=" << observation.filterEnergyGain
        << "; " << prefix << "VarianceY=" << observation.varianceY
        << "; " << prefix << "VarianceRG=" << observation.varianceRG
        << "; " << prefix << "VarianceBG=" << observation.varianceBG
        << "; " << prefix << "CovarianceRgBg=" << observation.covarianceRgBg
        << "; " << prefix << "RobustVarianceY=" << observation.robustVarianceY
        << "; " << prefix << "RobustVarianceRG=" << observation.robustVarianceRG
        << "; " << prefix << "RobustVarianceBG=" << observation.robustVarianceBG
        << "; " << prefix << "RobustCovarianceRgBg=" << observation.robustCovarianceRgBg
        << "; " << prefix << "VarianceYP10=" << observation.varianceYP10
        << "; " << prefix << "VarianceYP50=" << observation.varianceYP50
        << "; " << prefix << "VarianceYP90=" << observation.varianceYP90
        << "; " << prefix << "VarianceRGP10=" << observation.varianceRGP10
        << "; " << prefix << "VarianceRGP50=" << observation.varianceRGP50
        << "; " << prefix << "VarianceRGP90=" << observation.varianceRGP90
        << "; " << prefix << "VarianceBGP10=" << observation.varianceBGP10
        << "; " << prefix << "VarianceBGP50=" << observation.varianceBGP50
        << "; " << prefix << "VarianceBGP90=" << observation.varianceBGP90
        << "; " << prefix << "LowFrequencyMedianRG=" << observation.lowFrequencyMedianRG
        << "; " << prefix << "LowFrequencyMedianBG=" << observation.lowFrequencyMedianBG
        << "; " << prefix << "LowFrequencyChromaFieldEnergyP50="
        << observation.lowFrequencyChromaFieldEnergyP50
        << "; " << prefix << "LowFrequencyChromaFieldEnergyP90="
        << observation.lowFrequencyChromaFieldEnergyP90
        << "; " << prefix << "LowFrequencyChromaNeighbourEnergyP50="
        << observation.lowFrequencyChromaNeighbourEnergyP50
        << "; " << prefix << "LowFrequencyChromaNeighbourEnergyP90="
        << observation.lowFrequencyChromaNeighbourEnergyP90
        << "; " << prefix << "LowFrequencyChromaValidTileCount="
        << observation.lowFrequencyChromaValidTileCount
        << "; " << prefix << "LowFrequencyChromaNeighbourPairCount="
        << observation.lowFrequencyChromaNeighbourPairCount
        << "; " << prefix << "LowFrequencyLumaP10=" << observation.lowFrequencyLumaP10
        << "; " << prefix << "LowFrequencyLumaP50=" << observation.lowFrequencyLumaP50
        << "; " << prefix << "LowFrequencyLumaP90=" << observation.lowFrequencyLumaP90
        << "; " << prefix << "LowFrequencyChromaFieldGrid="
        << bncam::spectra2::ResidualObservation::kLowFrequencyChromaGridColumns
        << "x" << bncam::spectra2::ResidualObservation::kLowFrequencyChromaGridRows
        << "; " << prefix << "LowFrequencyChromaFieldRetained="
        << (observation.lowFrequencyChromaValidTileCount > 0u ? "true" : "false")
        << "; " << prefix << "StructureThreshold=" << observation.structureThreshold
        << "; " << prefix << "FlatSampleFraction=" << observation.flatSampleFraction
        << "; " << prefix << "TextureContamination=" << observation.textureContamination
        << "; " << prefix << "CandidateSampleCount=" << observation.candidateSampleCount
        << "; " << prefix << "AcceptedSampleCount=" << observation.acceptedSampleCount
        << "; " << prefix << "ValidTileCount=" << observation.validTileCount
        << "; " << prefix << "TotalTileCount=" << observation.totalTileCount;
    return out.str();
}

std::string formatCalibrationComparisonFields(
        const char* prefix,
        const bncam::spectra2::CalibrationComparison& comparison
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "; " << prefix << "Stage=" << comparison.stage
        << "; " << prefix << "Status=" << comparison.status
        << "; " << prefix << "Ready=" << (comparison.ready ? "true" : "false")
        << "; " << prefix << "Confidence=" << comparison.confidence
        << "; " << prefix << "MeasuredToPredictedY=" << comparison.measuredToPredictedY
        << "; " << prefix << "MeasuredToPredictedRG=" << comparison.measuredToPredictedRG
        << "; " << prefix << "MeasuredToPredictedBG=" << comparison.measuredToPredictedBG
        << "; " << prefix << "MeasuredToPredictedChroma="
        << comparison.measuredToPredictedChroma
        << "; " << prefix << "AbsoluteLog2ErrorY=" << comparison.absoluteLog2ErrorY
        << "; " << prefix << "AbsoluteLog2ErrorRG=" << comparison.absoluteLog2ErrorRG
        << "; " << prefix << "AbsoluteLog2ErrorBG=" << comparison.absoluteLog2ErrorBG;
    return out.str();
}


float sampleMosaicClamped(const cv::Mat& mosaic, int x, int y) {
    const int cx = std::clamp(x, 0, mosaic.cols - 1);
    const int cy = std::clamp(y, 0, mosaic.rows - 1);
    return mosaic.ptr<float>(cy)[cx];
}

std::string computeRawShadowDiagnostics(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) {
        return "rawShadowDiagnostics=unavailable_mosaic_empty";
    }

    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);

    const int marginX = width / 10;
    const int marginY = height / 10;
    const int startX = std::max(0, marginX);
    const int endX = std::min(width, width - marginX);
    const int startY = std::max(0, marginY);
    const int endY = std::min(height, height - marginY);

    const float whiteLevel = static_cast<float>(std::max(1, raw.info.payloadWhiteLevel));
    const float nearBlackThreshold = 0.15f;

    // Tile grid setup (16x16)
    const int gridCols = 16;
    const int gridRows = 16;
    const float tileW = static_cast<float>(endX - startX) / gridCols;
    const float tileH = static_cast<float>(endY - startY) / gridRows;

    int totalTileCount = gridCols * gridRows;
    int acceptedTileCount = 0;
    int rejectedForBrightness = 0;
    int rejectedForTexture = 0;
    int rejectedForClipping = 0;
    int rejectedForInsufficientPixels = 0;

    std::array<double, 4> channelSumAfter{0.0, 0.0, 0.0, 0.0};
    std::array<uint64_t, 4> channelCount{0, 0, 0, 0};
    std::array<uint64_t, 4> clippedZeroCount{0, 0, 0, 0};

    std::vector<double> g1MinusG2Diffs;
    std::vector<double> rMinusGDiffs;
    std::vector<double> bMinusGDiffs;

    std::vector<double> rowSums(height, 0.0);
    std::vector<uint64_t> rowCounts(height, 0);
    std::vector<double> colSums(width, 0.0);
    std::vector<uint64_t> colCounts(width, 0);

    for (int gy = 0; gy < gridRows; ++gy) {
        const int ty0 = startY + static_cast<int>(gy * tileH);
        const int ty1 = startY + static_cast<int>((gy + 1) * tileH);

        for (int gx = 0; gx < gridCols; ++gx) {
            const int tx0 = startX + static_cast<int>(gx * tileW);
            const int tx1 = startX + static_cast<int>((gx + 1) * tileW);

            double tileSum = 0.0;
            double tileSqSum = 0.0;
            size_t tilePixels = 0;
            size_t tileClipped = 0;

            for (int y = ty0; y < ty1; ++y) {
                const float* rowPtr = raw.mosaic.ptr<float>(y);
                for (int x = tx0; x < tx1; ++x) {
                    const float val = rowPtr[x];
                    if (!std::isfinite(val)) continue;
                    tileSum += val;
                    tileSqSum += val * val;
                    tilePixels++;
                    if (val <= 0.0f) tileClipped++;
                }
            }

            if (tilePixels < 32) {
                rejectedForInsufficientPixels++;
                continue;
            }

            const double tileMean = tileSum / tilePixels;
            const double tileVar = std::max(0.0, (tileSqSum / tilePixels) - (tileMean * tileMean));
            const double tileStdDev = std::sqrt(tileVar);
            const double clipFraction = static_cast<double>(tileClipped) / tilePixels;

            if (tileMean > nearBlackThreshold) {
                rejectedForBrightness++;
            } else if (tileStdDev > 0.05) {
                rejectedForTexture++;
            } else if (clipFraction > 0.20) {
                rejectedForClipping++;
            } else {
                acceptedTileCount++;

                for (int y = ty0; y < ty1; ++y) {
                    const float* rowPtr = raw.mosaic.ptr<float>(y);
                    for (int x = tx0; x < tx1; ++x) {
                        const float val = rowPtr[x];
                        if (!std::isfinite(val)) continue;
                        const int ch = cfaColorChannel(cfaPattern, x, y);
                        if (ch < 0 || ch >= 4) continue;

                        if (val <= 0.0f) clippedZeroCount[ch]++;

                        channelSumAfter[ch] += val;
                        channelCount[ch]++;

                        rowSums[y] += val;
                        rowCounts[y]++;
                        colSums[x] += val;
                        colCounts[x]++;
                    }
                }

                for (int y = ty0; y < ty1 - 1; y += 2) {
                    const float* r0 = raw.mosaic.ptr<float>(y);
                    const float* r1 = raw.mosaic.ptr<float>(y + 1);
                    for (int x = tx0; x < tx1 - 1; x += 2) {
                        float p00 = r0[x];
                        float p10 = r0[x + 1];
                        float p01 = r1[x];
                        float p11 = r1[x + 1];
                        if (!std::isfinite(p00) || !std::isfinite(p10) || !std::isfinite(p01) || !std::isfinite(p11)) continue;

                        float rVal = 0.0f, g1Val = 0.0f, g2Val = 0.0f, bVal = 0.0f;
                        switch (cfaPattern) {
                            case CFA_RGGB: rVal = p00; g1Val = p10; g2Val = p01; bVal = p11; break;
                            case CFA_GRBG: g1Val = p00; rVal = p10; bVal = p01; g2Val = p11; break;
                            case CFA_GBRG: g1Val = p00; bVal = p10; rVal = p01; g2Val = p11; break;
                            case CFA_BGGR: bVal = p00; g2Val = p10; g1Val = p01; rVal = p11; break;
                        }

                        g1MinusG2Diffs.push_back(g1Val - g2Val);
                        float gAvg = (g1Val + g2Val) * 0.5f;
                        rMinusGDiffs.push_back(rVal - gAvg);
                        bMinusGDiffs.push_back(bVal - gAvg);
                    }
                }
            }
        }
    }

    const float darkTileConfidence = totalTileCount > 0 && acceptedTileCount >= 4
            ? static_cast<float>(acceptedTileCount) / static_cast<float>(totalTileCount)
            : 0.0f;

    std::array<double, 4> meanAfter{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> meanBefore{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> clippedZeroPct{0.0, 0.0, 0.0, 0.0};

    for (int ch = 0; ch < 4; ++ch) {
        if (channelCount[ch] > 0) {
            meanAfter[ch] = (channelSumAfter[ch] / channelCount[ch]) * whiteLevel;
            const float bl = meta.calibration.effectiveBlackLevels[ch];
            meanBefore[ch] = meanAfter[ch] + bl;
        }
        const uint64_t totalChPixels = static_cast<uint64_t>((endX - startX) / 2) * static_cast<uint64_t>((endY - startY) / 2);
        if (totalChPixels > 0) {
            clippedZeroPct[ch] = (static_cast<double>(clippedZeroCount[ch]) / totalChPixels) * 100.0;
        }
    }

    double g1g2Mean = 0.0, g1g2Var = 0.0;
    if (!g1MinusG2Diffs.empty()) {
        double sum = 0.0;
        for (double d : g1MinusG2Diffs) sum += d;
        g1g2Mean = (sum / g1MinusG2Diffs.size()) * whiteLevel;
        double sqSum = 0.0;
        for (double d : g1MinusG2Diffs) {
            double diff = (d * whiteLevel) - g1g2Mean;
            sqSum += diff * diff;
        }
        g1g2Var = sqSum / g1MinusG2Diffs.size();
    }

    double rgMean = 0.0, bgMean = 0.0;
    if (!rMinusGDiffs.empty()) {
        double sum = 0.0;
        for (double d : rMinusGDiffs) sum += d;
        rgMean = (sum / rMinusGDiffs.size()) * whiteLevel;
    }
    if (!bMinusGDiffs.empty()) {
        double sum = 0.0;
        for (double d : bMinusGDiffs) sum += d;
        bgMean = (sum / bMinusGDiffs.size()) * whiteLevel;
    }

    std::vector<double> validRowMeans;
    for (int y = startY; y < endY; ++y) {
        if (rowCounts[y] > 0) validRowMeans.push_back((rowSums[y] / rowCounts[y]) * whiteLevel);
    }
    double rowVar = 0.0;
    if (validRowMeans.size() > 1) {
        double rSum = 0.0;
        for (double rm : validRowMeans) rSum += rm;
        double rMean = rSum / validRowMeans.size();
        double rSq = 0.0;
        for (double rm : validRowMeans) rSq += (rm - rMean) * (rm - rMean);
        rowVar = rSq / validRowMeans.size();
    }

    std::vector<double> validColMeans;
    for (int x = startX; x < endX; ++x) {
        if (colCounts[x] > 0) validColMeans.push_back((colSums[x] / colCounts[x]) * whiteLevel);
    }
    double colVar = 0.0;
    if (validColMeans.size() > 1) {
        double cSum = 0.0;
        for (double cm : validColMeans) cSum += cm;
        double cMean = cSum / validColMeans.size();
        double cSq = 0.0;
        for (double cm : validColMeans) cSq += (cm - cMean) * (cm - cMean);
        colVar = cSq / validColMeans.size();
    }

    // Deterministic marker derived from the native snapshot actually consumed by SPECTRA.
    uint32_t spectraSnapshotHash = 0x811c9dc5u;
    for (int ch = 0; ch < 4; ++ch) {
        const double values[4] = {
            meta.calibration.cameraS[ch],
            meta.calibration.cameraO[ch],
            meta.calibration.effectiveS[ch],
            meta.calibration.effectiveO[ch]
        };
        for (double value : values) {
            const int64_t quantized = static_cast<int64_t>(std::llround(value * 1.0e12));
            spectraSnapshotHash ^= static_cast<uint32_t>(quantized & 0xFFFFFFFFu);
            spectraSnapshotHash *= 0x01000193u;
            spectraSnapshotHash ^= static_cast<uint32_t>((static_cast<uint64_t>(quantized) >> 32u) & 0xFFFFFFFFu);
            spectraSnapshotHash *= 0x01000193u;
        }
    }
    const double refSignalVar = meta.calibration.effectiveS[0] * 0.1 + meta.calibration.effectiveO[0];
    const float spectraEffectiveSigmaAtReferenceSignal = static_cast<float>(std::sqrt(std::max(0.0, refSignalVar)));

    std::ostringstream diag;
    diag << std::fixed << std::setprecision(4);
    diag << "rawShadowDiagnostics={"
         << "lensKey=" << meta.calibration.spectraLensKey
         << ";snapshotPresent=" << (meta.calibration.spectraSnapshotPresent ? "true" : "false")
         << ";sourceFormat=" << rawSourceFormatName(raw.info.sourceFormat)
         << ";cfaPattern=" << rawCfaPatternName(raw.info.effectiveCfaPattern)
         << ";iso=" << meta.captureSensitivityIso
         << ";exposureTimeNs=" << meta.captureExposureTimeNs
         << ";postRawBoost=" << meta.calibration.postRawSensitivityBoost
         << ";whiteLevel=" << raw.info.payloadWhiteLevel
         << ";blackLevelR=" << meta.calibration.effectiveBlackLevels[0]
         << ";blackLevelG1=" << meta.calibration.effectiveBlackLevels[1]
         << ";blackLevelG2=" << meta.calibration.effectiveBlackLevels[2]
         << ";blackLevelB=" << meta.calibration.effectiveBlackLevels[3]
         << ";totalTileCount=" << totalTileCount
         << ";acceptedTileCount=" << acceptedTileCount
         << ";rejectedForBrightness=" << rejectedForBrightness
         << ";rejectedForTexture=" << rejectedForTexture
         << ";rejectedForClipping=" << rejectedForClipping
         << ";rejectedForInsufficientPixels=" << rejectedForInsufficientPixels
         << ";darkTileConfidence=" << darkTileConfidence
         << ";meanNearBlackBeforeR=" << meanBefore[0]
         << ";meanNearBlackBeforeG1=" << meanBefore[1]
         << ";meanNearBlackBeforeG2=" << meanBefore[2]
         << ";meanNearBlackBeforeB=" << meanBefore[3]
         << ";meanNearBlackAfterR=" << meanAfter[0]
         << ";meanNearBlackAfterG1=" << meanAfter[1]
         << ";meanNearBlackAfterG2=" << meanAfter[2]
         << ";meanNearBlackAfterB=" << meanAfter[3]
         << ";clippedZeroPctR=" << clippedZeroPct[0]
         << ";clippedZeroPctG1=" << clippedZeroPct[1]
         << ";clippedZeroPctG2=" << clippedZeroPct[2]
         << ";clippedZeroPctB=" << clippedZeroPct[3]
         << ";g1MinusG2Mean=" << g1g2Mean
         << ";g1MinusG2Var=" << g1g2Var
         << ";rMinusGMean=" << rgMean
         << ";bMinusGMean=" << bgMean
         << ";rowMeanVar=" << rowVar
         << ";colMeanVar=" << colVar
         << ";cameraS=[" << meta.calibration.cameraS[0] << "," << meta.calibration.cameraS[1] << "," << meta.calibration.cameraS[2] << "," << meta.calibration.cameraS[3] << "]"
         << ";cameraO=[" << meta.calibration.cameraO[0] << "," << meta.calibration.cameraO[1] << "," << meta.calibration.cameraO[2] << "," << meta.calibration.cameraO[3] << "]"
         << ";effectiveS=[" << meta.calibration.effectiveS[0] << "," << meta.calibration.effectiveS[1] << "," << meta.calibration.effectiveS[2] << "," << meta.calibration.effectiveS[3] << "]"
         << ";effectiveO=[" << meta.calibration.effectiveO[0] << "," << meta.calibration.effectiveO[1] << "," << meta.calibration.effectiveO[2] << "," << meta.calibration.effectiveO[3] << "]"
         << ";spectraSnapshotHash=0x" << std::hex << spectraSnapshotHash << std::dec
         << ";spectraEffectiveSigmaAtReferenceSignal=" << spectraEffectiveSigmaAtReferenceSignal
         << ";physicalNoiseModelMode=" << noiseModelModeName(meta.calibration.noiseModelMode)
         << ";spectraProcessingMode=" << noiseModelModeName(meta.calibration.spectraProcessingMode)
         << ";spectraSignalModelConfidence=" << meta.calibration.signalModelConfidence
         << "}";

    return diag.str();
}

float clampSceneLinear(float value) {
    if (!std::isfinite(value)) return 0.0f;
    return std::clamp(value, 0.0f, 4.0f);
}

}

inline uint8_t quantizeSrgb8(float value) {
    return bncam::color::quantizeSrgbByte(value);
}

struct DefectCorrectionDebug {
    bool applied = false;
    int correctedPixels = 0;
    float elapsedMs = 0.0f;
    float preScanMs = 0.0f;
    float fullPassMs = 0.0f;
    bool expensivePassSkipped = false;
    std::string reason = "not_run";
};

struct GreenSplitDebug {
    bool applied = false;
    float greenEvenMedian = 0.0f;
    float greenOddMedian = 0.0f;
    float greenEvenScale = 1.0f;
    float greenOddScale = 1.0f;
    std::uint64_t pairedSampleCount = 0u;
    float relativeMedian = 0.0f;
    float relativeMad = 0.0f;
    float signConsensus = 0.0f;
    float elapsedMs = 0.0f;
    std::string reason = "not_run";
};

struct LensShadingDebug {
    bool applied = false;
    float maxGain = 1.0f;
    float overRangePct = 0.0f;
    float elapsedMs = 0.0f;
    std::string reason = "not_run";
};

struct HighlightRecoveryDebug {
    bool applied = false;
    int correctedPixels = 0;
    float elapsedMs = 0.0f;
    std::string reason = "not_run";
};

struct Phase9ColorProtectionDebug {
    bool gpuPrimary = false;
    bool cpuFallback = false;
    std::uint64_t sensorClipCandidatePixels = 0u;
    std::uint64_t singleChannelSensorClipPixels = 0u;
    std::uint64_t multiChannelSensorClipPixels = 0u;
    std::uint64_t fullySensorClippedPixels = 0u;
    std::uint64_t wbAboveUnityWithoutSensorClipPixels = 0u;
    std::uint64_t ccmNegativeExcursionPixels = 0u;
    std::uint64_t colorConfidenceAppliedPixels = 0u;
    std::uint64_t partialColorConfidencePixels = 0u;
    std::uint64_t gamutCompressedPixels = 0u;
    std::uint64_t legacyMagentaRiskPixels = 0u;
    std::uint64_t protectedMagentaRiskPixels = 0u;
    std::uint64_t sceneLinearOverUnityPixels = 0u;
    bool sourceRawConfidenceMapUsed = false;
    std::uint64_t sourceRawConfidenceMapBytes = 0u;
    std::uint64_t sourceRawConfidenceCandidatePixels = 0u;
    std::uint64_t sourceRawZeroConfidencePixels = 0u;
    std::uint64_t sourceRawPartialConfidencePixels = 0u;
    std::uint64_t sourceRawDemosaicDisagreementPixels = 0u;
    bool calibratedHueSatMapRequested = false;
    bool calibratedHueSatMapApplied = false;
    std::uint64_t calibratedHueSatMapAppliedPixels = 0u;
    std::uint64_t calibratedHueSatMapProfileBytes = 0u;
    float calibratedHueSatMapWeightFirst = 1.0f;
    float calibratedHueSatMapWeightSecond = 0.0f;
    float cpuFallbackMs = 0.0f;
};


float medianFromSamples(std::vector<float>& values) {
    if (values.empty()) return 0.0f;
    const size_t middle = values.size() / 2u;
    std::nth_element(values.begin(), values.begin() + static_cast<long>(middle), values.end());
    return values[middle];
}

void smoothSparseCfaProfile(std::vector<float>& profile, int stride) {
    if (profile.empty()) return;
    const std::vector<float> source = profile;
    for (int i = stride; i + stride < static_cast<int>(source.size()); ++i) {
        if (source[static_cast<std::size_t>(i)] == 0.0f) continue;
        std::vector<float> neighbours;
        neighbours.reserve(3);
        for (int d = -stride; d <= stride; d += stride) {
            const float value = source[static_cast<std::size_t>(i + d)];
            if (value != 0.0f && std::isfinite(value)) neighbours.push_back(value);
        }
        if (neighbours.size() < 2u) {
            profile[static_cast<std::size_t>(i)] = 0.0f;
            continue;
        }
        profile[static_cast<std::size_t>(i)] = medianFromSamples(neighbours);
    }
}

DefectCorrectionDebug applyDefectCorrectionToJpegRaw(LinearFloatRaw& raw, const IspFrameMetadata& meta) {
    DefectCorrectionDebug debug{};
    const auto start = IspClock::now();
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1 || raw.mosaic.cols < 6 || raw.mosaic.rows < 6) {
        debug.reason = "invalid_or_too_small_mosaic";
        debug.elapsedMs = elapsedMs(start);
        return debug;
    }

    const float baseThreshold = meta.isRaw10 ? 0.070f : 0.050f;
    const bool noiseModelValid = meta.calibration.noiseProfileApplied &&
            meta.calibration.normalizationCalibrationValid &&
            meta.calibration.signalModelConfidence > 0.0f;

    const auto preScanStart = IspClock::now();
    std::mutex candidateMutex;
    std::vector<std::pair<int, int>> defectCandidates;

    cv::parallel_for_(cv::Range(2, raw.mosaic.rows - 2), [&](const cv::Range& range) {
        std::vector<std::pair<int, int>> localCandidates;
        for (int y = range.start; y < range.end; ++y) {
            const float* row = raw.mosaic.ptr<float>(y);
            const float* rowAbove = raw.mosaic.ptr<float>(y - 2);
            const float* rowBelow = raw.mosaic.ptr<float>(y + 2);
            for (int x = 2; x < raw.mosaic.cols - 2; ++x) {
                const float n0 = row[x - 2];
                const float n1 = row[x + 2];
                const float n2 = rowAbove[x];
                const float n3 = rowBelow[x];
                const float neighborMin = std::min({n0, n1, n2, n3});
                const float neighborMax = std::max({n0, n1, n2, n3});
                const int noiseChannel = cfaCanonicalNoiseChannel(raw.info.effectiveCfaPattern, x, y);
                const float localSignal = 0.5f * (neighborMin + neighborMax);
                const float threshold = bncam::raw_defect::preScanThreshold(
                        baseThreshold,
                        neighborMin,
                        neighborMax,
                        localSignal,
                        static_cast<float>(meta.calibration.effectiveS[noiseChannel]),
                        static_cast<float>(meta.calibration.effectiveO[noiseChannel]),
                        noiseModelValid);
                const float center = row[x];
                const bool hot = center > 0.20f && center > neighborMax + threshold;
                const bool cold = center < 0.04f && neighborMin > 0.10f &&
                        neighborMin - center > threshold;
                if (hot || cold) {
                    localCandidates.emplace_back(x, y);
                }
            }
        }
        if (!localCandidates.empty()) {
            std::lock_guard<std::mutex> lock(candidateMutex);
            defectCandidates.insert(defectCandidates.end(), localCandidates.begin(), localCandidates.end());
        }
    });
    debug.preScanMs = elapsedMs(preScanStart);

    if (defectCandidates.empty()) {
        debug.expensivePassSkipped = true;
        debug.reason = "cheap_prescan_found_no_isolated_outliers";
        debug.elapsedMs = elapsedMs(start);
        return debug;
    }

    const auto fullPassStart = IspClock::now();
    int correctedCount = 0;
    std::array<float, 8> neighbors{};

    for (const auto& coord : defectCandidates) {
        const int x = coord.first;
        const int y = coord.second;
        neighbors[0] = sampleMosaicClamped(raw.mosaic, x - 2, y);
        neighbors[1] = sampleMosaicClamped(raw.mosaic, x + 2, y);
        neighbors[2] = sampleMosaicClamped(raw.mosaic, x, y - 2);
        neighbors[3] = sampleMosaicClamped(raw.mosaic, x, y + 2);
        neighbors[4] = sampleMosaicClamped(raw.mosaic, x - 2, y - 2);
        neighbors[5] = sampleMosaicClamped(raw.mosaic, x + 2, y - 2);
        neighbors[6] = sampleMosaicClamped(raw.mosaic, x - 2, y + 2);
        neighbors[7] = sampleMosaicClamped(raw.mosaic, x + 2, y + 2);
        std::sort(neighbors.begin(), neighbors.end());
        const float median = 0.5f * (neighbors[3] + neighbors[4]);
        const float spread = std::max(0.006f, neighbors[6] - neighbors[1]);
        const int noiseChannel = cfaCanonicalNoiseChannel(raw.info.effectiveCfaPattern, x, y);
        const float threshold = bncam::raw_defect::finalThreshold(
                baseThreshold,
                spread,
                median,
                static_cast<float>(meta.calibration.effectiveS[noiseChannel]),
                static_cast<float>(meta.calibration.effectiveO[noiseChannel]),
                noiseModelValid);
        const float center = raw.mosaic.ptr<float>(y)[x];
        const bool hot = center > 0.20f && center > median + threshold;
        const bool cold = center < 0.04f && median > 0.10f && median - center > threshold;
        if (hot || cold) {
            raw.mosaic.ptr<float>(y)[x] = median;
            ++correctedCount;
        }
    }

    debug.correctedPixels = correctedCount;
    debug.applied = debug.correctedPixels > 0;
    debug.fullPassMs = elapsedMs(fullPassStart);
    debug.reason = debug.applied ? "noise_aware_same_cfa_outlier_replaced_by_neighbor_median" : "no_isolated_outliers_detected";
    debug.elapsedMs = elapsedMs(start);
    return debug;
}

GreenSplitDebug applyGreenSplitCorrectionToJpegRaw(LinearFloatRaw& raw) {
    GreenSplitDebug debug{};
    const auto start = IspClock::now();
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) {
        debug.reason = "invalid_mosaic";
        debug.elapsedMs = elapsedMs(start);
        return debug;
    }

    std::vector<float> greenEven;
    std::vector<float> greenOdd;
    const int pattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);

    // Sample aligned 2x2 blocks to ensure equal channel representation
    const int yStep = std::max(2, (raw.mosaic.rows / 100) * 2);
    const int xStep = std::max(2, (raw.mosaic.cols / 100) * 2);
    greenEven.reserve(10000);
    greenOdd.reserve(10000);

    for (int y = 0; y < raw.mosaic.rows - 1; y += yStep) {
        const float* row0 = raw.mosaic.ptr<float>(y);
        const float* row1 = raw.mosaic.ptr<float>(y + 1);
        for (int x = 0; x < raw.mosaic.cols - 1; x += xStep) {
            const int c00 = cfaColorChannel(pattern, x, y);
            if (c00 == 1) greenEven.push_back(row0[x]);
            else if (c00 == 2) greenOdd.push_back(row0[x]);

            const int c01 = cfaColorChannel(pattern, x + 1, y);
            if (c01 == 1) greenEven.push_back(row0[x + 1]);
            else if (c01 == 2) greenOdd.push_back(row0[x + 1]);

            const int c10 = cfaColorChannel(pattern, x, y + 1);
            if (c10 == 1) greenEven.push_back(row1[x]);
            else if (c10 == 2) greenOdd.push_back(row1[x]);

            const int c11 = cfaColorChannel(pattern, x + 1, y + 1);
            if (c11 == 1) greenEven.push_back(row1[x + 1]);
            else if (c11 == 2) greenOdd.push_back(row1[x + 1]);
        }
    }

    const auto evidence = bncam::raw_green_split::evaluate(greenEven, greenOdd);
    debug.greenEvenMedian = medianFromSamples(greenEven);
    debug.greenOddMedian = medianFromSamples(greenOdd);
    debug.pairedSampleCount = static_cast<std::uint64_t>(evidence.pairCount);
    debug.relativeMedian = evidence.relativeMedian;
    debug.relativeMad = evidence.relativeMad;
    debug.signConsensus = evidence.signConsensus;
    debug.reason = bncam::raw_green_split::reasonName(evidence.reason);
    if (!evidence.apply) {
        debug.elapsedMs = elapsedMs(start);
        return debug;
    }

    debug.greenEvenScale = evidence.evenScale;
    debug.greenOddScale = evidence.oddScale;
    cv::parallel_for_(cv::Range(0, raw.mosaic.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* row = raw.mosaic.ptr<float>(y);
            for (int x = 0; x < raw.mosaic.cols; ++x) {
                const int channel = cfaColorChannel(pattern, x, y);
                if (channel == 1) row[x] = clampSceneLinear(row[x] * debug.greenEvenScale);
                if (channel == 2) row[x] = clampSceneLinear(row[x] * debug.greenOddScale);
            }
        }
    });

    debug.applied = true;
    debug.reason = "paired_green_split_consensus_applied_in_jpeg_clone";
    debug.elapsedMs = elapsedMs(start);
    return debug;
}

LensShadingDebug applyLensShadingToJpegRaw(LinearFloatRaw& raw, const IspFrameMetadata& meta) {
    LensShadingDebug debug{};
    const auto start = IspClock::now();
    const LensShadingLookup lookup(meta, raw.mosaic.cols, raw.mosaic.rows);
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1 || !lookup.valid) {
        debug.reason = lookup.valid ? "invalid_mosaic" : "missing_or_invalid_LensShadingMap";
        debug.elapsedMs = elapsedMs(start);
        return debug;
    }

    const int sensorPattern = cfaPatternOrDefault(raw.info.sensorCfaPattern);
    std::atomic<int> overRangeCount{0};
    std::mutex gainMutex;
    float globalMaxGain = 1.0f;
    cv::parallel_for_(cv::Range(0, raw.mosaic.rows), [&](const cv::Range& range) {
        int localOverRange = 0;
        float localMaxGain = 1.0f;
        for (int y = range.start; y < range.end; ++y) {
            float* row = raw.mosaic.ptr<float>(y);
            for (int x = 0; x < raw.mosaic.cols; ++x) {
                const int channel = cfaColorChannel(sensorPattern, x + raw.info.cfaOffsetX, y + raw.info.cfaOffsetY);
                const float gain = lookup.gain(channel, x, y);
                localMaxGain = std::max(localMaxGain, gain);
                row[x] = clampSceneLinear(row[x] * gain);
                if (row[x] > 1.0f) ++localOverRange;
            }
        }
        overRangeCount.fetch_add(localOverRange, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(gainMutex);
        globalMaxGain = std::max(globalMaxGain, localMaxGain);
    });

    debug.applied = true;
    debug.maxGain = globalMaxGain;
    debug.overRangePct = 100.0f * static_cast<float>(overRangeCount.load(std::memory_order_relaxed)) /
            std::max(1.0f, static_cast<float>(raw.mosaic.total()));
    debug.reason = "metadata_lens_shading_map_applied_to_jpeg_clone";
    debug.elapsedMs = elapsedMs(start);
    return debug;
}

// Phase 9 removed the legacy CPU post-CCM 3x3 highlight heuristic. Failure recovery now
// rebuilds the same sensor-domain clip reconstruction + signed-CCM gamut contract used by Vulkan.

NativeRenderQualityConfig IspCore::resolveForRaw(const NativeRenderQualityConfig& uiConfig, bool isRaw10) {
    NativeRenderQualityConfig cfg = uiConfig;
    cfg.jpegQuality = safeJpegQuality(uiConfig.jpegQuality);
    return cfg;
}

NativeRenderQualityConfig IspCore::resolveForYuv(const NativeRenderQualityConfig& uiConfig) {
    NativeRenderQualityConfig cfg = uiConfig;
    cfg.jpegQuality = safeJpegQuality(uiConfig.jpegQuality);
    return cfg;
}

std::string IspCore::describeResolvedConfig(const NativeRenderQualityConfig& uiConfig, bool isRaw10, bool isYuv) {
    const NativeRenderQualityConfig cfg = isYuv ? resolveForYuv(uiConfig) : resolveForRaw(uiConfig, isRaw10);
    std::ostringstream oss;
    oss << "ispCoreCentralized=true"
        << ";pipeline=" << (isYuv ? "YUV" : (isRaw10 ? "JPEG_WORKING_LINEAR_RAW_FROM_RAW10_MASTER" : "JPEG_WORKING_LINEAR_RAW_FROM_RAW_SENSOR_MASTER"))
        << ";jpegQuality=" << cfg.jpegQuality
        << ";lensIsoNrMode=" << cfg.lensIsoNrMode
        << ";lensDynamicIsoCoeff=" << fmtDouble(cfg.lensDynamicIsoCoeff, 4)
        << ";lensManualIsoValue=" << fmtDouble(cfg.lensManualIsoValue, 2)
        << ";captureSensitivityIso=" << (cfg.captureSensitivityIso > 0 ? std::to_string(cfg.captureSensitivityIso) : "unknown")
        << ";yuvLensIsoNrApplied=" << (cfg.yuvLensIsoNrApplied ? "true" : "false")
        << ";yuvLensIsoNoiseReductionBoost=" << fmtDouble(cfg.yuvLensIsoNoiseReductionBoost, 4);
    return oss.str();
}

std::string SpectraPass0State::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(4);
    out << "spectraPass0={"
        << "mode=" << (spectraMode == 1 ? "auto" : (spectraMode == 2 ? "manual" : "legacy"))
        << ";sourceFormat=" << sourceFormat
        << ";lensKey=" << lensKey
        << ";acceptedTiles=" << acceptedTileCount << "/" << totalTileCount
        << ";confidence=" << darkTileConfidence
        << ";classification=" << classification
        << ";applyChannelBias=" << (applyChannelBias ? "true" : "false")
        << ";appliedChannelBias=[" << appliedChannelBias[0] << "," << appliedChannelBias[1] << "," << appliedChannelBias[2] << "," << appliedChannelBias[3] << "]"
        << ";g1g2Before=" << g1g2Before
        << ";g1g2After=" << g1g2After
        << ";greenSplitMad=" << greenSplitMad
        << ";greenSplitTileConsensus=" << greenSplitTileConsensus
        << ";greenSplitTileCount=" << greenSplitTileCount
        << ";applyRowCorrection=" << (applyRowCorrection ? "true" : "false")
        << ";rowVarianceBefore=" << rowVarianceBefore
        << ";rowVarianceAfter=" << rowVarianceAfter
        << ";applyColumnCorrection=" << (applyColumnCorrection ? "true" : "false")
        << ";colVarianceBefore=" << colVarianceBefore
        << ";colVarianceAfter=" << colVarianceAfter
        << ";isoAuthority=" << isoAuthority
        << ";noRegretAcceptedTiles=" << noRegretAcceptedTileFraction
        << ";noRegretRollback=" << noRegretRollbackFraction
        << ";vulkanAttempted=" << (vulkanAttempted ? "true" : "false")
        << ";vulkanExecutionSucceeded=" << (vulkanExecutionSucceeded ? "true" : "false")
        << ";vulkanUsedForOutput=" << (vulkanUsedForOutput ? "true" : "false")
        << ";vulkanCpuFallbackUsed=" << (vulkanCpuFallbackUsed ? "true" : "false")
        << ";vulkanGpuNoRegretBlendUsed=" << (vulkanGpuNoRegretBlendUsed ? "true" : "false")
        << ";vulkanCandidateReadbackAvoided=" << (vulkanCandidateReadbackAvoided ? "true" : "false")
        << ";vulkanStatus=" << vulkanStatus
        << ";vulkanFailureReason=" << vulkanFailureReason
        << ";vulkanPass0KernelMs=" << vulkanPass0KernelMs
        << ";vulkanTileStatisticsKernelMs=" << vulkanTileStatisticsKernelMs
        << ";vulkanNoRegretDecisionMs=" << vulkanNoRegretDecisionMs
        << ";vulkanNoRegretBlendMs=" << vulkanNoRegretBlendMs
        << ";vulkanSynchronizationMs=" << vulkanSynchronizationMs
        << ";vulkanCompactReadbackMs=" << vulkanCompactReadbackMs
        << ";vulkanResidentGeneration=" << vulkanResidentGeneration
        << ";fallbackReason=" << fallbackReason
        << ";planningMethod=" << planningMethod
        << ";planningSampleCount=" << planningSampleCount
        << ";processingTimeMs=" << processingTimeMs
        << "}";
    return out.str();
}


std::string SpectraIsoAdaptiveState::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(4);
    out << "spectraIsoAdaptive={"
        << "captureIso=" << captureIso
        << ";postRawBoost=" << postRawSensitivityBoost
        << ";effectiveIso=" << effectiveIso
        << ";evAbove100=" << isoEvAbove100
        << ";regime=" << regime
        << ";isoPressure=" << isoPressure
        << ";modelPressure=" << modelNoisePressure
        << ";combinedPressure=" << combinedNoisePressure
        << ";lumaAuthority=" << lumaAuthority
        << ";chromaAuthority=" << chromaAuthority
        << ";lowFreqAuthority=" << lowFrequencyAuthority
        << ";temporalAuthority=" << temporalAuthority
        << ";detailRetentionFloor=" << detailRetentionFloor
        << ";minimumResidualRatio=" << minimumResidualRatio
        << ";targetFloorScale=" << targetFloorScale
        << ";maxLinearShift=" << maxLinearShift
        << "}";
    return out.str();
}

std::string SpectraProvenanceField::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(6);
    out << "spectraProvenance={"
        << "grid=" << gridCols << "x" << gridRows
        << ";tile=" << tileWidth << "x" << tileHeight
        << ";meanPredictedRawVar=" << meanPredictedRawVariance
        << ";meanPredictedVisibleVar=" << meanPredictedVisibleVariance
        << ";meanPredictedSpatialResidualVar=" << meanPredictedSpatialResidualVariance
        << ";meanPredictedChromaResidualVar=" << meanPredictedChromaResidualVariance
        << ";meanResidual=" << meanResidualEnergy
        << ";meanChromaResidual=" << meanChromaResidualEnergy
        << ";meanGreenSplitResidual=" << meanGreenSplitResidualEnergy
        << ";meanShadingGain=" << meanLensShadingGain
        << ";meanRuntimeConfidence=" << meanConfidence
        << ";meanDiagnosticConfidence=" << meanDiagnosticConfidence
        << ";totalTiles=" << totalTiles
        << ";validTiles=" << validTiles
        << ";confidenceP10=" << confidenceP10
        << ";confidenceP50=" << confidenceP50
        << ";confidenceP90=" << confidenceP90
        << ";shadingGainP10=" << lensShadingGainP10
        << ";shadingGainP50=" << lensShadingGainP50
        << ";shadingGainP90=" << lensShadingGainP90
        << ";shadingRedToGreenP50=" << lensShadingRedToGreenP50
        << ";shadingRedToGreenP90=" << lensShadingRedToGreenP90
        << ";shadingBlueToGreenP50=" << lensShadingBlueToGreenP50
        << ";shadingBlueToGreenP90=" << lensShadingBlueToGreenP90
        << ";shadingOpponentDifferentialStopsP50="
        << lensShadingOpponentDifferentialStopsP50
        << ";shadingOpponentDifferentialStopsP90="
        << lensShadingOpponentDifferentialStopsP90
        << ";shadingOuterMinusCenterRedToGreen="
        << lensShadingOuterMinusCenterRedToGreen
        << ";shadingOuterMinusCenterBlueToGreen="
        << lensShadingOuterMinusCenterBlueToGreen
        << ";noiseBudgetP10=" << noiseBudgetP10
        << ";noiseBudgetP50=" << noiseBudgetP50
        << ";noiseBudgetP90=" << noiseBudgetP90
        << ";modelMismatchP10=" << modelMismatchP10
        << ";modelMismatchP50=" << modelMismatchP50
        << ";modelMismatchP90=" << modelMismatchP90
        << "}";
    return out.str();
}

std::string SpectraNoRegretResult::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(4);
    out << "spectraNoRegretP" << passIndex << "={"
        << "tiles=" << totalTiles
        << ";evaluated=" << evaluatedTiles
        << ";invalid=" << invalidTiles
        << ";accepted=" << acceptedTiles
        << ";partial=" << partiallyAcceptedTiles
        << ";rejected=" << rejectedTiles
        << ";rejectOversmooth=" << rejectedOversmooth
        << ";rejectDetail=" << rejectedDetailLoss
        << ";rejectMeanDrift=" << rejectedMeanDrift
        << ";rejectNoImprovement=" << rejectedNoImprovement
        << ";meanAcceptance=" << meanAcceptance
        << ";acceptanceP10=" << acceptanceP10
        << ";acceptanceP50=" << acceptanceP50
        << ";acceptanceP90=" << acceptanceP90
        << ";attenuatedPixelFraction=" << attenuatedPixelFraction
        << ";rollbackPixelFraction=" << rolledBackPixelFraction
        << ";meanRiskImprovement=" << meanRiskImprovement
        << ";meanColourShift=" << meanColourShift
        << ";maxColourShift=" << maxColourShift
        << ";edgePreservationScore=" << edgePreservationScore
        << ";oversmoothingScore=" << oversmoothingScore
        << "}";
    return out.str();
}

std::string computeRawShadowDiagnosticsCompact(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    (void)uiConfig;
    if (!raw.valid || raw.info.width <= 0 || raw.info.height <= 0) {
        return "rawShadowDiagnostics=unavailable_compact_raw16_view";
    }

    const int width = raw.info.width;
    const int height = raw.info.height;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    const int marginX = width / 10;
    const int marginY = height / 10;
    const int startX = std::max(0, marginX);
    const int endX = std::min(width, width - marginX);
    const int startY = std::max(0, marginY);
    const int endY = std::min(height, height - marginY);
    if (endX - startX < 4 || endY - startY < 4) {
        return "rawShadowDiagnostics=unavailable_compact_region_too_small";
    }

    const float whiteLevel = static_cast<float>(std::max(1, raw.info.payloadWhiteLevel));
    constexpr float nearBlackThreshold = 0.15f;
    constexpr int gridCols = 16;
    constexpr int gridRows = 16;
    constexpr int sampleQuadsX = 8;
    constexpr int sampleQuadsY = 8;
    const float tileW = static_cast<float>(endX - startX) / static_cast<float>(gridCols);
    const float tileH = static_cast<float>(endY - startY) / static_cast<float>(gridRows);

    int acceptedTileCount = 0;
    int rejectedForBrightness = 0;
    int rejectedForTexture = 0;
    int rejectedForClipping = 0;
    int rejectedForInsufficientPixels = 0;
    std::array<double, 4> channelSumAfter{0.0, 0.0, 0.0, 0.0};
    std::array<std::uint64_t, 4> channelCount{0u, 0u, 0u, 0u};
    std::array<std::uint64_t, 4> clippedZeroCount{0u, 0u, 0u, 0u};
    std::array<std::uint64_t, 4> sampledRegionChannelCount{0u, 0u, 0u, 0u};
    std::vector<double> g1MinusG2Diffs;
    std::vector<double> rMinusGDiffs;
    std::vector<double> bMinusGDiffs;
    g1MinusG2Diffs.reserve(gridCols * gridRows * sampleQuadsX * sampleQuadsY);
    rMinusGDiffs.reserve(g1MinusG2Diffs.capacity());
    bMinusGDiffs.reserve(g1MinusG2Diffs.capacity());
    std::array<double, gridRows> rowSums{};
    std::array<std::uint64_t, gridRows> rowCounts{};
    std::array<double, gridCols> colSums{};
    std::array<std::uint64_t, gridCols> colCounts{};

    auto sampledQuadOrigin = [](int lo, int hi, int sampleIndex, int sampleCount) {
        const int span = std::max(2, hi - lo);
        const double fraction = (static_cast<double>(sampleIndex) + 0.5) /
                static_cast<double>(sampleCount);
        int coordinate = lo + static_cast<int>(std::floor(fraction * static_cast<double>(span - 1)));
        coordinate = std::clamp(coordinate, lo, hi - 2);
        if ((coordinate & 1) != 0) --coordinate;
        if (coordinate < lo) coordinate += 2;
        if (coordinate > hi - 2) coordinate = std::max(lo, hi - 2);
        return coordinate;
    };

    for (int gy = 0; gy < gridRows; ++gy) {
        const int ty0 = startY + static_cast<int>(gy * tileH);
        const int ty1 = std::min(endY, startY + static_cast<int>((gy + 1) * tileH));
        for (int gx = 0; gx < gridCols; ++gx) {
            const int tx0 = startX + static_cast<int>(gx * tileW);
            const int tx1 = std::min(endX, startX + static_cast<int>((gx + 1) * tileW));
            if (tx1 - tx0 < 2 || ty1 - ty0 < 2) {
                ++rejectedForInsufficientPixels;
                continue;
            }

            double tileSum = 0.0;
            double tileSqSum = 0.0;
            std::size_t tilePixels = 0u;
            std::size_t tileClipped = 0u;
            for (int sy = 0; sy < sampleQuadsY; ++sy) {
                const int y = sampledQuadOrigin(ty0, ty1, sy, sampleQuadsY);
                for (int sx = 0; sx < sampleQuadsX; ++sx) {
                    const int x = sampledQuadOrigin(tx0, tx1, sx, sampleQuadsX);
                    for (int dy = 0; dy < 2; ++dy) {
                        for (int dx = 0; dx < 2; ++dx) {
                            const int px = x + dx;
                            const int py = y + dy;
                            const float value = raw.sample(px, py);
                            if (!std::isfinite(value)) continue;
                            const int channel = cfaColorChannel(cfaPattern, px, py);
                            if (channel >= 0 && channel < 4) {
                                ++sampledRegionChannelCount[static_cast<std::size_t>(channel)];
                            }
                            tileSum += value;
                            tileSqSum += static_cast<double>(value) * value;
                            ++tilePixels;
                            if (value <= 0.0f) ++tileClipped;
                        }
                    }
                }
            }

            if (tilePixels < 32u) {
                ++rejectedForInsufficientPixels;
                continue;
            }
            const double tileMean = tileSum / static_cast<double>(tilePixels);
            const double tileVar = std::max(
                    0.0, tileSqSum / static_cast<double>(tilePixels) - tileMean * tileMean);
            const double tileStdDev = std::sqrt(tileVar);
            const double clipFraction = static_cast<double>(tileClipped) /
                    static_cast<double>(tilePixels);
            if (tileMean > nearBlackThreshold) {
                ++rejectedForBrightness;
                continue;
            }
            if (tileStdDev > 0.05) {
                ++rejectedForTexture;
                continue;
            }
            if (clipFraction > 0.20) {
                ++rejectedForClipping;
                continue;
            }

            ++acceptedTileCount;
            for (int sy = 0; sy < sampleQuadsY; ++sy) {
                const int y = sampledQuadOrigin(ty0, ty1, sy, sampleQuadsY);
                for (int sx = 0; sx < sampleQuadsX; ++sx) {
                    const int x = sampledQuadOrigin(tx0, tx1, sx, sampleQuadsX);
                    const float p00 = raw.sample(x, y);
                    const float p10 = raw.sample(x + 1, y);
                    const float p01 = raw.sample(x, y + 1);
                    const float p11 = raw.sample(x + 1, y + 1);
                    const float values[4] = {p00, p10, p01, p11};
                    const int px[4] = {x, x + 1, x, x + 1};
                    const int py[4] = {y, y, y + 1, y + 1};
                    for (int i = 0; i < 4; ++i) {
                        if (!std::isfinite(values[i])) continue;
                        const int channel = cfaColorChannel(cfaPattern, px[i], py[i]);
                        if (channel < 0 || channel >= 4) continue;
                        const std::size_t ch = static_cast<std::size_t>(channel);
                        if (values[i] <= 0.0f) ++clippedZeroCount[ch];
                        channelSumAfter[ch] += values[i];
                        ++channelCount[ch];
                        rowSums[static_cast<std::size_t>(gy)] += values[i];
                        ++rowCounts[static_cast<std::size_t>(gy)];
                        colSums[static_cast<std::size_t>(gx)] += values[i];
                        ++colCounts[static_cast<std::size_t>(gx)];
                    }
                    if (!std::isfinite(p00) || !std::isfinite(p10) ||
                        !std::isfinite(p01) || !std::isfinite(p11)) continue;
                    float rVal = 0.0f, g1Val = 0.0f, g2Val = 0.0f, bVal = 0.0f;
                    switch (cfaPattern) {
                        case CFA_RGGB: rVal = p00; g1Val = p10; g2Val = p01; bVal = p11; break;
                        case CFA_GRBG: g1Val = p00; rVal = p10; bVal = p01; g2Val = p11; break;
                        case CFA_GBRG: g1Val = p00; bVal = p10; rVal = p01; g2Val = p11; break;
                        case CFA_BGGR: bVal = p00; g2Val = p10; g1Val = p01; rVal = p11; break;
                    }
                    g1MinusG2Diffs.push_back(g1Val - g2Val);
                    const float gAvg = 0.5f * (g1Val + g2Val);
                    rMinusGDiffs.push_back(rVal - gAvg);
                    bMinusGDiffs.push_back(bVal - gAvg);
                }
            }
        }
    }

    const int totalTileCount = gridCols * gridRows;
    const float darkTileConfidence = acceptedTileCount >= 4
            ? static_cast<float>(acceptedTileCount) / static_cast<float>(totalTileCount)
            : 0.0f;
    std::array<double, 4> meanAfter{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> meanBefore{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> clippedZeroPct{0.0, 0.0, 0.0, 0.0};
    for (int channel = 0; channel < 4; ++channel) {
        const std::size_t ch = static_cast<std::size_t>(channel);
        if (channelCount[ch] > 0u) {
            meanAfter[ch] = channelSumAfter[ch] / static_cast<double>(channelCount[ch]) * whiteLevel;
            meanBefore[ch] = meanAfter[ch] + meta.calibration.effectiveBlackLevels[ch];
        }
        if (sampledRegionChannelCount[ch] > 0u) {
            clippedZeroPct[ch] = static_cast<double>(clippedZeroCount[ch]) /
                    static_cast<double>(sampledRegionChannelCount[ch]) * 100.0;
        }
    }

    double g1g2Mean = 0.0;
    double g1g2Var = 0.0;
    if (!g1MinusG2Diffs.empty()) {
        const double sum = std::accumulate(g1MinusG2Diffs.begin(), g1MinusG2Diffs.end(), 0.0);
        g1g2Mean = sum / static_cast<double>(g1MinusG2Diffs.size()) * whiteLevel;
        double sqSum = 0.0;
        for (double value : g1MinusG2Diffs) {
            const double delta = value * whiteLevel - g1g2Mean;
            sqSum += delta * delta;
        }
        g1g2Var = sqSum / static_cast<double>(g1MinusG2Diffs.size());
    }
    double rgMean = 0.0;
    double bgMean = 0.0;
    if (!rMinusGDiffs.empty()) {
        rgMean = std::accumulate(rMinusGDiffs.begin(), rMinusGDiffs.end(), 0.0) /
                static_cast<double>(rMinusGDiffs.size()) * whiteLevel;
    }
    if (!bMinusGDiffs.empty()) {
        bgMean = std::accumulate(bMinusGDiffs.begin(), bMinusGDiffs.end(), 0.0) /
                static_cast<double>(bMinusGDiffs.size()) * whiteLevel;
    }

    auto groupedVariance = [whiteLevel](const auto& sums, const auto& counts) {
        std::vector<double> means;
        means.reserve(sums.size());
        for (std::size_t i = 0; i < sums.size(); ++i) {
            if (counts[i] > 0u) {
                means.push_back(sums[i] / static_cast<double>(counts[i]) * whiteLevel);
            }
        }
        if (means.size() <= 1u) return 0.0;
        const double mean = std::accumulate(means.begin(), means.end(), 0.0) /
                static_cast<double>(means.size());
        double sumSq = 0.0;
        for (double value : means) {
            const double delta = value - mean;
            sumSq += delta * delta;
        }
        return sumSq / static_cast<double>(means.size());
    };
    const double rowVar = groupedVariance(rowSums, rowCounts);
    const double colVar = groupedVariance(colSums, colCounts);

    std::uint32_t spectraSnapshotHash = 0x811c9dc5u;
    for (int ch = 0; ch < 4; ++ch) {
        const double values[4] = {
            meta.calibration.cameraS[ch], meta.calibration.cameraO[ch],
            meta.calibration.effectiveS[ch], meta.calibration.effectiveO[ch]
        };
        for (double value : values) {
            const std::int64_t quantized = static_cast<std::int64_t>(std::llround(value * 1.0e12));
            spectraSnapshotHash ^= static_cast<std::uint32_t>(quantized & 0xFFFFFFFFu);
            spectraSnapshotHash *= 0x01000193u;
            spectraSnapshotHash ^= static_cast<std::uint32_t>(
                    (static_cast<std::uint64_t>(quantized) >> 32u) & 0xFFFFFFFFu);
            spectraSnapshotHash *= 0x01000193u;
        }
    }
    const double refSignalVar = meta.calibration.effectiveS[0] * 0.1 +
            meta.calibration.effectiveO[0];
    const float spectraEffectiveSigmaAtReferenceSignal = static_cast<float>(
            std::sqrt(std::max(0.0, refSignalVar)));

    std::ostringstream diag;
    diag << std::fixed << std::setprecision(4)
         << "rawShadowDiagnostics={"
         << "samplingMethod=COMPACT_RAW16_16X16_8X8_CFA_QUADS"
         << ";rowColVarianceScope=GRID_PROXY"
         << ";lensKey=" << meta.calibration.spectraLensKey
         << ";snapshotPresent=" << (meta.calibration.spectraSnapshotPresent ? "true" : "false")
         << ";sourceFormat=" << rawSourceFormatName(raw.info.sourceFormat)
         << ";cfaPattern=" << rawCfaPatternName(raw.info.effectiveCfaPattern)
         << ";iso=" << meta.captureSensitivityIso
         << ";exposureTimeNs=" << meta.captureExposureTimeNs
         << ";postRawBoost=" << meta.calibration.postRawSensitivityBoost
         << ";whiteLevel=" << raw.info.payloadWhiteLevel
         << ";blackLevelR=" << meta.calibration.effectiveBlackLevels[0]
         << ";blackLevelG1=" << meta.calibration.effectiveBlackLevels[1]
         << ";blackLevelG2=" << meta.calibration.effectiveBlackLevels[2]
         << ";blackLevelB=" << meta.calibration.effectiveBlackLevels[3]
         << ";totalTileCount=" << totalTileCount
         << ";acceptedTileCount=" << acceptedTileCount
         << ";rejectedForBrightness=" << rejectedForBrightness
         << ";rejectedForTexture=" << rejectedForTexture
         << ";rejectedForClipping=" << rejectedForClipping
         << ";rejectedForInsufficientPixels=" << rejectedForInsufficientPixels
         << ";darkTileConfidence=" << darkTileConfidence
         << ";meanNearBlackBeforeR=" << meanBefore[0]
         << ";meanNearBlackBeforeG1=" << meanBefore[1]
         << ";meanNearBlackBeforeG2=" << meanBefore[2]
         << ";meanNearBlackBeforeB=" << meanBefore[3]
         << ";meanNearBlackAfterR=" << meanAfter[0]
         << ";meanNearBlackAfterG1=" << meanAfter[1]
         << ";meanNearBlackAfterG2=" << meanAfter[2]
         << ";meanNearBlackAfterB=" << meanAfter[3]
         << ";clippedZeroPctR=" << clippedZeroPct[0]
         << ";clippedZeroPctG1=" << clippedZeroPct[1]
         << ";clippedZeroPctG2=" << clippedZeroPct[2]
         << ";clippedZeroPctB=" << clippedZeroPct[3]
         << ";g1MinusG2Mean=" << g1g2Mean
         << ";g1MinusG2Var=" << g1g2Var
         << ";rMinusGMean=" << rgMean
         << ";bMinusGMean=" << bgMean
         << ";rowMeanVar=" << rowVar
         << ";colMeanVar=" << colVar
         << ";cameraS=[" << meta.calibration.cameraS[0] << "," << meta.calibration.cameraS[1] << "," << meta.calibration.cameraS[2] << "," << meta.calibration.cameraS[3] << "]"
         << ";cameraO=[" << meta.calibration.cameraO[0] << "," << meta.calibration.cameraO[1] << "," << meta.calibration.cameraO[2] << "," << meta.calibration.cameraO[3] << "]"
         << ";effectiveS=[" << meta.calibration.effectiveS[0] << "," << meta.calibration.effectiveS[1] << "," << meta.calibration.effectiveS[2] << "," << meta.calibration.effectiveS[3] << "]"
         << ";effectiveO=[" << meta.calibration.effectiveO[0] << "," << meta.calibration.effectiveO[1] << "," << meta.calibration.effectiveO[2] << "," << meta.calibration.effectiveO[3] << "]"
         << ";spectraSnapshotHash=0x" << std::hex << spectraSnapshotHash << std::dec
         << ";spectraEffectiveSigmaAtReferenceSignal=" << spectraEffectiveSigmaAtReferenceSignal
         << ";physicalNoiseModelMode=" << noiseModelModeName(meta.calibration.noiseModelMode)
         << ";spectraProcessingMode=" << noiseModelModeName(meta.calibration.spectraProcessingMode)
         << ";spectraSignalModelConfidence=" << meta.calibration.signalModelConfidence
         << "}";
    return diag.str();
}

SpectraIsoAdaptiveState IspCore::resolveSpectraIsoAdaptiveState(
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    SpectraIsoAdaptiveState state{};
    state.captureIso = std::max(25, meta.captureSensitivityIso > 0
            ? meta.captureSensitivityIso
            : (uiConfig.captureSensitivityIso > 0 ? uiConfig.captureSensitivityIso : 100));
    const int metadataPostRawBoost = meta.calibration.postRawSensitivityBoost > 0
            ? meta.calibration.postRawSensitivityBoost
            : 100;
    state.postRawSensitivityBoost = std::clamp(metadataPostRawBoost, 50, 800);
    state.effectiveIso = static_cast<float>(state.captureIso) *
            static_cast<float>(state.postRawSensitivityBoost) / 100.0f;
    state.effectiveIso = std::clamp(state.effectiveIso, 25.0f, 102400.0f);
    state.isoEvAbove100 = std::log2(std::max(0.25f, state.effectiveIso / 100.0f));

    // Continuous ISO pressure. Regime names are diagnostic only; the processing
    // parameters never jump at a regime boundary.
    const float moderate = smoothstepIsp(0.75f, 3.0f, state.isoEvAbove100);
    const float high = smoothstepIsp(2.5f, 5.0f, state.isoEvAbove100);
    const float extreme = smoothstepIsp(4.75f, 7.5f, state.isoEvAbove100);
    state.isoPressure = std::clamp(0.28f * moderate + 0.42f * high + 0.30f * extreme, 0.0f, 1.0f);
    state.regime = state.effectiveIso < 200.0f ? "LOW"
            : (state.effectiveIso < 800.0f ? "MODERATE"
            : (state.effectiveIso < 3200.0f ? "HIGH" : "EXTREME"));

    double meanVariance = 0.0;
    int validChannels = 0;
    for (int ch = 0; ch < 4; ++ch) {
        const double sValue = meta.calibration.effectiveS[ch];
        const double oValue = meta.calibration.effectiveO[ch];
        if (!isVstValid(sValue, oValue)) continue;
        meanVariance += std::max(0.0, sValue * 0.18 + oValue);
        validChannels++;
    }
    meanVariance = validChannels > 0 ? meanVariance / static_cast<double>(validChannels) : 0.0;
    const float logVariance = static_cast<float>(std::log2(std::max(1.0e-12, meanVariance)));
    state.modelNoisePressure = smoothstepIsp(-16.5f, -8.5f, logVariance);

    const float confidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);
    const float modelWeight = 0.25f + 0.50f * confidence;
    state.combinedNoisePressure = std::clamp(
            (1.0f - modelWeight) * state.isoPressure + modelWeight * state.modelNoisePressure,
            0.0f,
            1.0f
    );

    // Existing lens controls remain authoritative, but now shape a continuous,
    // physically informed ISO response rather than acting as a single global gain.
    const float dynamicUserGain = 1.0f + std::clamp(uiConfig.lensDynamicIsoCoeff, 0.0f, 1.0f) *
            smoothstepIsp(0.0f, 6.0f, state.isoEvAbove100);
    const float profileOverall = std::exp2(std::clamp(uiConfig.profileSpectraStrength, -1.0f, 1.0f) * 0.75f);
    const float profileLuma = std::exp2(std::clamp(uiConfig.profileSpectraLuma, -1.0f, 1.0f) * 0.75f);
    const float profileChroma = std::exp2(std::clamp(uiConfig.profileSpectraChroma, -1.0f, 1.0f) * 0.75f);
    const float profileLowFrequency = std::exp2(std::clamp(uiConfig.profileSpectraLowFrequency, -1.0f, 1.0f) * 0.75f);
    const float lumaUser = std::sqrt(std::clamp(uiConfig.lumaUserScale, 0.25f, 2.0f)) * profileOverall * profileLuma;
    const float chromaUser = std::sqrt(std::clamp(uiConfig.chromaUserScale, 0.25f, 2.5f)) * profileOverall * profileChroma;
    state.lumaAuthority = std::clamp(
            (0.20f + 0.95f * state.combinedNoisePressure) * lumaUser * dynamicUserGain,
            0.10f,
            1.65f
    );
    // SPECTRA Context Fusion can carry more chroma authority because the shader
    // now measures structure relative to the predicted CFA noise floor instead
    // of treating dark sensor noise as protected detail.
    state.chromaAuthority = std::clamp(
            (0.28f + 1.08f * state.combinedNoisePressure) * chromaUser * dynamicUserGain,
            0.18f,
            1.50f
    );
    state.lowFrequencyAuthority = std::clamp(
            (0.10f + 0.95f * high) * (0.55f + 0.45f * confidence) * profileOverall * profileLowFrequency,
            0.04f,
            1.25f
    );
    state.temporalAuthority = std::clamp(
            (0.15f + 0.80f * state.combinedNoisePressure) * confidence * profileOverall,
            0.0f,
            0.98f
    );
    const float detailAdjustment = std::clamp(uiConfig.profileSpectraDetailProtection, -1.0f, 1.0f);
    state.detailRetentionFloor = std::clamp(
            0.965f - 0.165f * state.combinedNoisePressure + detailAdjustment * 0.075f,
            0.72f,
            0.995f
    );
    state.minimumResidualRatio = std::clamp(
            0.88f - 0.36f * state.combinedNoisePressure + detailAdjustment * 0.10f,
            0.40f,
            0.98f
    );
    state.targetFloorScale = std::clamp(
            (1.10f - 0.20f * state.combinedNoisePressure) / std::sqrt(profileOverall),
            0.72f,
            1.30f
    );
    state.maxLinearShift = std::clamp(
            (0.0015f + 0.0105f * state.combinedNoisePressure) * std::sqrt(profileOverall),
            0.0008f,
            0.0200f
    );
    return state;
}

SpectraProvenanceField IspCore::buildSpectraProvenanceField(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta
) {
    SpectraProvenanceField field{};
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) return field;
    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    if (width < 12 || height < 12) return field;

    field.gridCols = std::clamp(width / 128, 12, 36);
    field.gridRows = std::clamp(height / 128, 9, 28);
    field.tileWidth = (width + field.gridCols - 1) / field.gridCols;
    field.tileHeight = (height + field.gridRows - 1) / field.gridRows;
    field.tiles.resize(static_cast<size_t>(field.gridCols) * static_cast<size_t>(field.gridRows));
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    const float modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);

    double totalRawVar = 0.0;
    double totalVisibleVar = 0.0;
    double totalSpatialPredicted = 0.0;
    double totalChromaPredicted = 0.0;
    double totalResidual = 0.0;
    double totalChroma = 0.0;
    double totalGreenSplit = 0.0;
    double totalGain = 0.0;
    double totalConfidence = 0.0;
    double totalDiagnosticConfidence = 0.0;
    int validTiles = 0;
    std::vector<float> confidenceSamples;
    std::vector<float> shadingGainSamples;
    std::vector<float> shadingRedToGreenSamples;
    std::vector<float> shadingBlueToGreenSamples;
    std::vector<float> shadingOpponentDifferentialStopsSamples;
    double centerRedToGreenSum = 0.0;
    double centerBlueToGreenSum = 0.0;
    double outerRedToGreenSum = 0.0;
    double outerBlueToGreenSum = 0.0;
    int centerShadingTileCount = 0;
    int outerShadingTileCount = 0;
    std::vector<float> noiseBudgetSamples;
    std::vector<float> modelMismatchSamples;

    for (int gy = 0; gy < field.gridRows; ++gy) {
        const int y0 = std::max(4, gy * field.tileHeight);
        const int y1 = std::min(height - 4, (gy + 1) * field.tileHeight);
        for (int gx = 0; gx < field.gridCols; ++gx) {
            const int x0 = std::max(4, gx * field.tileWidth);
            const int x1 = std::min(width - 4, (gx + 1) * field.tileWidth);
            auto& tile = field.tiles[flatIndex2d(gy, field.gridCols, gx)];
            if (x1 <= x0 || y1 <= y0) continue;

            std::array<double, 4> signalSum{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> predictedSumByChannel{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> visiblePredictedSumByChannel{0.0, 0.0, 0.0, 0.0};
            std::array<uint64_t, 4> signalCount{0, 0, 0, 0};
            double residualSum = 0.0;
            double chromaSum = 0.0;
            double greenSplitSum = 0.0;
            double structureSum = 0.0;
            double gainSum = 0.0;
            std::array<double, 4> gainSumByChannel{0.0, 0.0, 0.0, 0.0};
            uint64_t sampleCount = 0;
            uint64_t chromaCount = 0;
            uint64_t greenSplitCount = 0;

            const int baseY = y0 + ((y0 & 1) ? 1 : 0);
            const int baseX = x0 + ((x0 & 1) ? 1 : 0);
            for (int y = baseY; y < y1; y += 16) {
                for (int x = baseX; x < x1; x += 16) {
                    std::array<float, 2> greenSites{0.0f, 0.0f};
                    std::array<bool, 2> greenFound{false, false};
                    for (int py = 0; py < 2; ++py) {
                        const int yy = y + py;
                        if (yy >= y1) continue;
                        const float* row = raw.mosaic.ptr<float>(yy);
                        const float* rowM2 = raw.mosaic.ptr<float>(yy - 2);
                        const float* rowP2 = raw.mosaic.ptr<float>(yy + 2);
                        const float* rowM1 = raw.mosaic.ptr<float>(yy - 1);
                        const float* rowP1 = raw.mosaic.ptr<float>(yy + 1);
                        for (int px = 0; px < 2; ++px) {
                            const int xx = x + px;
                            if (xx >= x1) continue;
                            const int ch = cfaColorChannel(cfaPattern, xx, yy);
                            const float center = row[xx];
                            const float left = row[xx - 2];
                            const float right = row[xx + 2];
                            const float up = rowM2[xx];
                            const float down = rowP2[xx];
                            if (ch < 0 || ch >= 4 || !std::isfinite(center) ||
                                !std::isfinite(left) || !std::isfinite(right) ||
                                !std::isfinite(up) || !std::isfinite(down)) continue;

                            const double signal = std::clamp(static_cast<double>(center), 0.0, 1.0);
                            signalSum[ch] += signal;
                            signalCount[ch]++;
                            const double sValue = meta.calibration.effectiveS[ch];
                            const double oValue = meta.calibration.effectiveO[ch];
                            const double predicted = isVstValid(sValue, oValue)
                                    ? bncam::raw_noise::sensorVariance(signal, sValue, oValue)
                                    : 0.0;
                            const float gain = lensShadingGainAt(meta, ch, xx, yy, width, height);
                            predictedSumByChannel[ch] += predicted;
                            visiblePredictedSumByChannel[ch] +=
                                    bncam::raw_noise::visibleVarianceAfterMultiplicativeGain(
                                            predicted, static_cast<double>(gain));
                            gainSum += gain;
                            gainSumByChannel[static_cast<size_t>(ch)] += gain;

                            const float neighbourMean = 0.25f * (left + right + up + down);
                            const float residual = center - neighbourMean;
                            residualSum += static_cast<double>(residual) * residual;
                            const float structure = 0.5f * (std::abs(left - right) + std::abs(up - down));
                            structureSum += static_cast<double>(structure) * structure;

                            if (ch == 1 || ch == 2) {
                                const int greenIndex = ch - 1;
                                greenSites[static_cast<size_t>(greenIndex)] = center;
                                greenFound[static_cast<size_t>(greenIndex)] = true;
                            }

                            if ((ch == 0 || ch == 3) && xx > 2 && xx + 3 < width) {
                                const float green = 0.25f * (rowM1[xx] + rowP1[xx] + row[xx - 1] + row[xx + 1]);
                                const float greenL = 0.25f * (
                                        raw.mosaic.ptr<float>(yy - 1)[xx - 2] +
                                        raw.mosaic.ptr<float>(yy + 1)[xx - 2] +
                                        row[xx - 3] + row[xx - 1]);
                                const float greenR = 0.25f * (
                                        raw.mosaic.ptr<float>(yy - 1)[xx + 2] +
                                        raw.mosaic.ptr<float>(yy + 1)[xx + 2] +
                                        row[xx + 1] + row[xx + 3]);
                                if (std::isfinite(green) && std::isfinite(greenL) && std::isfinite(greenR)) {
                                    const float chroma = center - green;
                                    const float chromaNeighbour = 0.5f * ((left - greenL) + (right - greenR));
                                    const float chromaResidual = chroma - chromaNeighbour;
                                    chromaSum += static_cast<double>(chromaResidual) * chromaResidual;
                                    chromaCount++;
                                }
                            }
                            sampleCount++;
                        }
                    }
                    if (greenFound[0] && greenFound[1]) {
                        const float greenSplit = greenSites[0] - greenSites[1];
                        greenSplitSum += static_cast<double>(greenSplit) * greenSplit;
                        greenSplitCount++;
                    }
                }
            }

            if (sampleCount < 16u) continue;
            int populatedChannels = 0;
            double predictedTotal = 0.0;
            double visiblePredictedTotal = 0.0;
            for (int ch = 0; ch < 4; ++ch) {
                if (signalCount[ch] == 0u) continue;
                populatedChannels++;
                tile.meanSignal[ch] = static_cast<float>(
                        signalSum[ch] / static_cast<double>(signalCount[ch])
                );
                tile.predictedRawVarianceByChannel[ch] = static_cast<float>(
                        predictedSumByChannel[ch] / static_cast<double>(signalCount[ch])
                );
                tile.predictedVisibleVarianceByChannel[ch] = static_cast<float>(
                        visiblePredictedSumByChannel[ch] / static_cast<double>(signalCount[ch])
                );
                tile.meanLensShadingGainByChannel[static_cast<size_t>(ch)] = static_cast<float>(
                        gainSumByChannel[static_cast<size_t>(ch)] /
                        static_cast<double>(signalCount[ch])
                );
                predictedTotal += predictedSumByChannel[ch];
                visiblePredictedTotal += visiblePredictedSumByChannel[ch];
            }

            tile.sampleCount = static_cast<uint32_t>(std::min<uint64_t>(
                    sampleCount,
                    static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())
            ));
            tile.valid = populatedChannels == 4 && sampleCount >= 32u;
            if (!tile.valid) continue;

            tile.predictedRawVariance = static_cast<float>(predictedTotal / static_cast<double>(sampleCount));
            tile.predictedVisibleVariance = static_cast<float>(visiblePredictedTotal / static_cast<double>(sampleCount));
            // The same-colour residual kernel is [1, -1/4, -1/4, -1/4, -1/4].
            tile.predictedSpatialResidualVariance = 1.25f * tile.predictedRawVariance;

            const float greenVariance = 0.5f * (
                    tile.predictedRawVarianceByChannel[1] +
                    tile.predictedRawVarianceByChannel[2]
            );
            double predictedChroma = 0.0;
            int predictedChromaChannels = 0;
            for (const int ch : {0, 3}) {
                const float colourVariance = tile.predictedRawVarianceByChannel[ch];
                if (!(colourVariance > 0.0f)) continue;
                // C-G4 has Vc + Vg/4. The provenance residual subtracts the mean
                // of two same-colour neighbours, adding a kernel energy of 1.5.
                predictedChroma += 1.5 * static_cast<double>(
                        colourVariance + 0.25f * greenVariance
                );
                predictedChromaChannels++;
            }
            tile.predictedChromaResidualVariance = predictedChromaChannels > 0
                    ? static_cast<float>(predictedChroma / predictedChromaChannels)
                    : 0.0f;
            tile.residualEnergy = static_cast<float>(residualSum / static_cast<double>(sampleCount));
            tile.chromaResidualEnergy = chromaCount > 0
                    ? static_cast<float>(chromaSum / static_cast<double>(chromaCount))
                    : 0.0f;
            tile.greenSplitResidualEnergy = greenSplitCount > 0
                    ? static_cast<float>(greenSplitSum / static_cast<double>(greenSplitCount))
                    : 0.0f;
            tile.structureEnergy = static_cast<float>(structureSum / static_cast<double>(sampleCount));
            tile.meanLensShadingGain = static_cast<float>(gainSum / static_cast<double>(sampleCount));
            const float sampleCoverage = std::clamp(static_cast<float>(sampleCount) / 96.0f, 0.0f, 1.0f);
            const float channelCoverage = std::clamp(static_cast<float>(populatedChannels) / 4.0f, 0.0f, 1.0f);
            tile.confidence = sampleCoverage * channelCoverage * (0.35f + 0.65f * modelConfidence);
            const float predictedFloor = std::max(1.0e-12f, tile.predictedSpatialResidualVariance);
            const float noiseBudgetRatio = std::max(0.0f, tile.residualEnergy / predictedFloor);
            const float modelMismatch = std::abs(std::log(std::max(0.04f, noiseBudgetRatio)));
            const float modelAgreement = std::exp(-0.45f * modelMismatch);
            const float structureRatio = tile.structureEnergy / predictedFloor;
            const float lowStructureSupport = 1.0f / (1.0f + 0.10f * std::max(0.0f, structureRatio));
            tile.diagnosticConfidence = std::clamp(
                    tile.confidence * modelAgreement * (0.35f + 0.65f * lowStructureSupport),
                    0.0f,
                    1.0f
            );
            confidenceSamples.push_back(tile.diagnosticConfidence);
            shadingGainSamples.push_back(tile.meanLensShadingGain);

            // Delta 36: use the same provenance samples to distinguish scalar vignetting from
            // channel-dependent lens/color shading. No additional RAW scan is performed.
            const float tileGreenShading = std::max(1.0e-4f, 0.5f * (
                    tile.meanLensShadingGainByChannel[1] +
                    tile.meanLensShadingGainByChannel[2]));
            const float tileRedToGreen = tile.meanLensShadingGainByChannel[0] / tileGreenShading;
            const float tileBlueToGreen = tile.meanLensShadingGainByChannel[3] / tileGreenShading;
            const float tileOpponentDifferentialStops = std::max(
                    std::abs(std::log2(std::max(1.0e-4f, tileRedToGreen))),
                    std::abs(std::log2(std::max(1.0e-4f, tileBlueToGreen)))
            );
            shadingRedToGreenSamples.push_back(tileRedToGreen);
            shadingBlueToGreenSamples.push_back(tileBlueToGreen);
            shadingOpponentDifferentialStopsSamples.push_back(tileOpponentDifferentialStops);

            const float normalizedX = 2.0f * (static_cast<float>(gx) + 0.5f) /
                    static_cast<float>(field.gridCols) - 1.0f;
            const float normalizedY = 2.0f * (static_cast<float>(gy) + 0.5f) /
                    static_cast<float>(field.gridRows) - 1.0f;
            const float normalizedRadius = std::sqrt(normalizedX * normalizedX +
                    normalizedY * normalizedY) / 1.41421356237f;
            if (normalizedRadius <= 0.35f) {
                centerRedToGreenSum += tileRedToGreen;
                centerBlueToGreenSum += tileBlueToGreen;
                centerShadingTileCount++;
            } else if (normalizedRadius >= 0.70f) {
                outerRedToGreenSum += tileRedToGreen;
                outerBlueToGreenSum += tileBlueToGreen;
                outerShadingTileCount++;
            }

            noiseBudgetSamples.push_back(noiseBudgetRatio);
            modelMismatchSamples.push_back(modelMismatch);

            totalRawVar += tile.predictedRawVariance;
            totalVisibleVar += tile.predictedVisibleVariance;
            totalSpatialPredicted += tile.predictedSpatialResidualVariance;
            totalChromaPredicted += tile.predictedChromaResidualVariance;
            totalResidual += tile.residualEnergy;
            totalChroma += tile.chromaResidualEnergy;
            totalGreenSplit += tile.greenSplitResidualEnergy;
            totalGain += tile.meanLensShadingGain;
            totalConfidence += tile.confidence;
            totalDiagnosticConfidence += tile.diagnosticConfidence;
            validTiles++;
        }
    }

    field.totalTiles = static_cast<int>(field.tiles.size());
    field.validTiles = validTiles;
    if (validTiles > 0) {
        const double inv = 1.0 / static_cast<double>(validTiles);
        field.meanPredictedRawVariance = static_cast<float>(totalRawVar * inv);
        field.meanPredictedVisibleVariance = static_cast<float>(totalVisibleVar * inv);
        field.meanPredictedSpatialResidualVariance = static_cast<float>(totalSpatialPredicted * inv);
        field.meanPredictedChromaResidualVariance = static_cast<float>(totalChromaPredicted * inv);
        field.meanResidualEnergy = static_cast<float>(totalResidual * inv);
        field.meanChromaResidualEnergy = static_cast<float>(totalChroma * inv);
        field.meanGreenSplitResidualEnergy = static_cast<float>(totalGreenSplit * inv);
        field.meanLensShadingGain = static_cast<float>(totalGain * inv);
        field.meanConfidence = static_cast<float>(totalConfidence * inv);
        field.meanDiagnosticConfidence = static_cast<float>(totalDiagnosticConfidence * inv);
        field.confidenceP10 = spectraPercentile(confidenceSamples, 0.10f);
        field.confidenceP50 = spectraPercentile(confidenceSamples, 0.50f);
        field.confidenceP90 = spectraPercentile(confidenceSamples, 0.90f);
        field.lensShadingGainP10 = spectraPercentile(shadingGainSamples, 0.10f, 1.0f);
        field.lensShadingGainP50 = spectraPercentile(shadingGainSamples, 0.50f, 1.0f);
        field.lensShadingGainP90 = spectraPercentile(shadingGainSamples, 0.90f, 1.0f);
        field.lensShadingRedToGreenP50 =
                spectraPercentile(shadingRedToGreenSamples, 0.50f, 1.0f);
        field.lensShadingRedToGreenP90 =
                spectraPercentile(shadingRedToGreenSamples, 0.90f, 1.0f);
        field.lensShadingBlueToGreenP50 =
                spectraPercentile(shadingBlueToGreenSamples, 0.50f, 1.0f);
        field.lensShadingBlueToGreenP90 =
                spectraPercentile(shadingBlueToGreenSamples, 0.90f, 1.0f);
        field.lensShadingOpponentDifferentialStopsP50 =
                spectraPercentile(shadingOpponentDifferentialStopsSamples, 0.50f, 0.0f);
        field.lensShadingOpponentDifferentialStopsP90 =
                spectraPercentile(shadingOpponentDifferentialStopsSamples, 0.90f, 0.0f);
        if (centerShadingTileCount > 0 && outerShadingTileCount > 0) {
            field.lensShadingOuterMinusCenterRedToGreen = static_cast<float>(
                    outerRedToGreenSum / static_cast<double>(outerShadingTileCount) -
                    centerRedToGreenSum / static_cast<double>(centerShadingTileCount));
            field.lensShadingOuterMinusCenterBlueToGreen = static_cast<float>(
                    outerBlueToGreenSum / static_cast<double>(outerShadingTileCount) -
                    centerBlueToGreenSum / static_cast<double>(centerShadingTileCount));
        }
        field.noiseBudgetP10 = spectraPercentile(noiseBudgetSamples, 0.10f);
        field.noiseBudgetP50 = spectraPercentile(noiseBudgetSamples, 0.50f);
        field.noiseBudgetP90 = spectraPercentile(noiseBudgetSamples, 0.90f);
        field.modelMismatchP10 = spectraPercentile(modelMismatchSamples, 0.10f);
        field.modelMismatchP50 = spectraPercentile(modelMismatchSamples, 0.50f);
        field.modelMismatchP90 = spectraPercentile(modelMismatchSamples, 0.90f);
    }
    return field;
}

SpectraProvenanceField IspCore::buildSpectraProvenanceFieldCompact(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta
) {
    SpectraProvenanceField field{};
    if (!raw.valid) return field;
    const int width = raw.info.width;
    const int height = raw.info.height;
    if (width < 12 || height < 12) return field;

    field.gridCols = std::clamp(width / 128, 12, 36);
    field.gridRows = std::clamp(height / 128, 9, 28);
    field.tileWidth = (width + field.gridCols - 1) / field.gridCols;
    field.tileHeight = (height + field.gridRows - 1) / field.gridRows;
    field.tiles.resize(static_cast<size_t>(field.gridCols) * static_cast<size_t>(field.gridRows));
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    const float modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);

    double totalRawVar = 0.0;
    double totalVisibleVar = 0.0;
    double totalSpatialPredicted = 0.0;
    double totalChromaPredicted = 0.0;
    double totalResidual = 0.0;
    double totalChroma = 0.0;
    double totalGreenSplit = 0.0;
    double totalGain = 0.0;
    double totalConfidence = 0.0;
    double totalDiagnosticConfidence = 0.0;
    int validTiles = 0;
    std::vector<float> confidenceSamples;
    std::vector<float> shadingGainSamples;
    std::vector<float> shadingRedToGreenSamples;
    std::vector<float> shadingBlueToGreenSamples;
    std::vector<float> shadingOpponentDifferentialStopsSamples;
    double centerRedToGreenSum = 0.0;
    double centerBlueToGreenSum = 0.0;
    double outerRedToGreenSum = 0.0;
    double outerBlueToGreenSum = 0.0;
    int centerShadingTileCount = 0;
    int outerShadingTileCount = 0;
    std::vector<float> noiseBudgetSamples;
    std::vector<float> modelMismatchSamples;

    for (int gy = 0; gy < field.gridRows; ++gy) {
        const int y0 = std::max(4, gy * field.tileHeight);
        const int y1 = std::min(height - 4, (gy + 1) * field.tileHeight);
        for (int gx = 0; gx < field.gridCols; ++gx) {
            const int x0 = std::max(4, gx * field.tileWidth);
            const int x1 = std::min(width - 4, (gx + 1) * field.tileWidth);
            auto& tile = field.tiles[flatIndex2d(gy, field.gridCols, gx)];
            if (x1 <= x0 || y1 <= y0) continue;

            std::array<double, 4> signalSum{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> predictedSumByChannel{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> visiblePredictedSumByChannel{0.0, 0.0, 0.0, 0.0};
            std::array<uint64_t, 4> signalCount{0, 0, 0, 0};
            double residualSum = 0.0;
            double chromaSum = 0.0;
            double greenSplitSum = 0.0;
            double structureSum = 0.0;
            double gainSum = 0.0;
            std::array<double, 4> gainSumByChannel{0.0, 0.0, 0.0, 0.0};
            uint64_t sampleCount = 0;
            uint64_t chromaCount = 0;
            uint64_t greenSplitCount = 0;

            const int baseY = y0 + ((y0 & 1) ? 1 : 0);
            const int baseX = x0 + ((x0 & 1) ? 1 : 0);
            for (int y = baseY; y < y1; y += 16) {
                for (int x = baseX; x < x1; x += 16) {
                    std::array<float, 2> greenSites{0.0f, 0.0f};
                    std::array<bool, 2> greenFound{false, false};
                    for (int py = 0; py < 2; ++py) {
                        const int yy = y + py;
                        if (yy >= y1) continue;
                        for (int px = 0; px < 2; ++px) {
                            const int xx = x + px;
                            if (xx >= x1) continue;
                            const int ch = cfaColorChannel(cfaPattern, xx, yy);
                            const float center = raw.sample(xx, yy);
                            const float left = raw.sample(xx - 2, yy);
                            const float right = raw.sample(xx + 2, yy);
                            const float up = raw.sample(xx, yy - 2);
                            const float down = raw.sample(xx, yy + 2);
                            if (ch < 0 || ch >= 4 || !std::isfinite(center) ||
                                !std::isfinite(left) || !std::isfinite(right) ||
                                !std::isfinite(up) || !std::isfinite(down)) continue;

                            const double signal = std::clamp(static_cast<double>(center), 0.0, 1.0);
                            signalSum[ch] += signal;
                            signalCount[ch]++;
                            const double sValue = meta.calibration.effectiveS[ch];
                            const double oValue = meta.calibration.effectiveO[ch];
                            const double predicted = isVstValid(sValue, oValue)
                                    ? bncam::raw_noise::sensorVariance(signal, sValue, oValue)
                                    : 0.0;
                            const float gain = lensShadingGainAt(meta, ch, xx, yy, width, height);
                            predictedSumByChannel[ch] += predicted;
                            visiblePredictedSumByChannel[ch] +=
                                    bncam::raw_noise::visibleVarianceAfterMultiplicativeGain(
                                            predicted, static_cast<double>(gain));
                            gainSum += gain;
                            gainSumByChannel[static_cast<size_t>(ch)] += gain;

                            const float neighbourMean = 0.25f * (left + right + up + down);
                            const float residual = center - neighbourMean;
                            residualSum += static_cast<double>(residual) * residual;
                            const float structure = 0.5f * (std::abs(left - right) + std::abs(up - down));
                            structureSum += static_cast<double>(structure) * structure;

                            if (ch == 1 || ch == 2) {
                                const int greenIndex = ch - 1;
                                greenSites[static_cast<size_t>(greenIndex)] = center;
                                greenFound[static_cast<size_t>(greenIndex)] = true;
                            }

                            if ((ch == 0 || ch == 3) && xx > 2 && xx + 3 < width) {
                                const float green = 0.25f * (
                                        raw.sample(xx, yy - 1) + raw.sample(xx, yy + 1) +
                                        raw.sample(xx - 1, yy) + raw.sample(xx + 1, yy));
                                const float greenL = 0.25f * (
                                        raw.sample(xx - 2, yy - 1) + raw.sample(xx - 2, yy + 1) +
                                        raw.sample(xx - 3, yy) + raw.sample(xx - 1, yy));
                                const float greenR = 0.25f * (
                                        raw.sample(xx + 2, yy - 1) + raw.sample(xx + 2, yy + 1) +
                                        raw.sample(xx + 1, yy) + raw.sample(xx + 3, yy));
                                if (std::isfinite(green) && std::isfinite(greenL) && std::isfinite(greenR)) {
                                    const float chroma = center - green;
                                    const float chromaNeighbour = 0.5f * ((left - greenL) + (right - greenR));
                                    const float chromaResidual = chroma - chromaNeighbour;
                                    chromaSum += static_cast<double>(chromaResidual) * chromaResidual;
                                    chromaCount++;
                                }
                            }
                            sampleCount++;
                        }
                    }
                    if (greenFound[0] && greenFound[1]) {
                        const float greenSplit = greenSites[0] - greenSites[1];
                        greenSplitSum += static_cast<double>(greenSplit) * greenSplit;
                        greenSplitCount++;
                    }
                }
            }

            if (sampleCount < 16u) continue;
            int populatedChannels = 0;
            double predictedTotal = 0.0;
            double visiblePredictedTotal = 0.0;
            for (int ch = 0; ch < 4; ++ch) {
                if (signalCount[ch] == 0u) continue;
                populatedChannels++;
                tile.meanSignal[ch] = static_cast<float>(
                        signalSum[ch] / static_cast<double>(signalCount[ch])
                );
                tile.predictedRawVarianceByChannel[ch] = static_cast<float>(
                        predictedSumByChannel[ch] / static_cast<double>(signalCount[ch])
                );
                tile.predictedVisibleVarianceByChannel[ch] = static_cast<float>(
                        visiblePredictedSumByChannel[ch] / static_cast<double>(signalCount[ch])
                );
                tile.meanLensShadingGainByChannel[static_cast<size_t>(ch)] = static_cast<float>(
                        gainSumByChannel[static_cast<size_t>(ch)] /
                        static_cast<double>(signalCount[ch])
                );
                predictedTotal += predictedSumByChannel[ch];
                visiblePredictedTotal += visiblePredictedSumByChannel[ch];
            }

            tile.sampleCount = static_cast<uint32_t>(std::min<uint64_t>(
                    sampleCount,
                    static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())
            ));
            tile.valid = populatedChannels == 4 && sampleCount >= 32u;
            if (!tile.valid) continue;

            tile.predictedRawVariance = static_cast<float>(predictedTotal / static_cast<double>(sampleCount));
            tile.predictedVisibleVariance = static_cast<float>(visiblePredictedTotal / static_cast<double>(sampleCount));
            // The same-colour residual kernel is [1, -1/4, -1/4, -1/4, -1/4].
            tile.predictedSpatialResidualVariance = 1.25f * tile.predictedRawVariance;

            const float greenVariance = 0.5f * (
                    tile.predictedRawVarianceByChannel[1] +
                    tile.predictedRawVarianceByChannel[2]
            );
            double predictedChroma = 0.0;
            int predictedChromaChannels = 0;
            for (const int ch : {0, 3}) {
                const float colourVariance = tile.predictedRawVarianceByChannel[ch];
                if (!(colourVariance > 0.0f)) continue;
                // C-G4 has Vc + Vg/4. The provenance residual subtracts the mean
                // of two same-colour neighbours, adding a kernel energy of 1.5.
                predictedChroma += 1.5 * static_cast<double>(
                        colourVariance + 0.25f * greenVariance
                );
                predictedChromaChannels++;
            }
            tile.predictedChromaResidualVariance = predictedChromaChannels > 0
                    ? static_cast<float>(predictedChroma / predictedChromaChannels)
                    : 0.0f;
            tile.residualEnergy = static_cast<float>(residualSum / static_cast<double>(sampleCount));
            tile.chromaResidualEnergy = chromaCount > 0
                    ? static_cast<float>(chromaSum / static_cast<double>(chromaCount))
                    : 0.0f;
            tile.greenSplitResidualEnergy = greenSplitCount > 0
                    ? static_cast<float>(greenSplitSum / static_cast<double>(greenSplitCount))
                    : 0.0f;
            tile.structureEnergy = static_cast<float>(structureSum / static_cast<double>(sampleCount));
            tile.meanLensShadingGain = static_cast<float>(gainSum / static_cast<double>(sampleCount));
            const float sampleCoverage = std::clamp(static_cast<float>(sampleCount) / 96.0f, 0.0f, 1.0f);
            const float channelCoverage = std::clamp(static_cast<float>(populatedChannels) / 4.0f, 0.0f, 1.0f);
            tile.confidence = sampleCoverage * channelCoverage * (0.35f + 0.65f * modelConfidence);
            const float predictedFloor = std::max(1.0e-12f, tile.predictedSpatialResidualVariance);
            const float noiseBudgetRatio = std::max(0.0f, tile.residualEnergy / predictedFloor);
            const float modelMismatch = std::abs(std::log(std::max(0.04f, noiseBudgetRatio)));
            const float modelAgreement = std::exp(-0.45f * modelMismatch);
            const float structureRatio = tile.structureEnergy / predictedFloor;
            const float lowStructureSupport = 1.0f / (1.0f + 0.10f * std::max(0.0f, structureRatio));
            tile.diagnosticConfidence = std::clamp(
                    tile.confidence * modelAgreement * (0.35f + 0.65f * lowStructureSupport),
                    0.0f,
                    1.0f
            );
            confidenceSamples.push_back(tile.diagnosticConfidence);
            shadingGainSamples.push_back(tile.meanLensShadingGain);

            // Delta 36: use the same provenance samples to distinguish scalar vignetting from
            // channel-dependent lens/color shading. No additional RAW scan is performed.
            const float tileGreenShading = std::max(1.0e-4f, 0.5f * (
                    tile.meanLensShadingGainByChannel[1] +
                    tile.meanLensShadingGainByChannel[2]));
            const float tileRedToGreen = tile.meanLensShadingGainByChannel[0] / tileGreenShading;
            const float tileBlueToGreen = tile.meanLensShadingGainByChannel[3] / tileGreenShading;
            const float tileOpponentDifferentialStops = std::max(
                    std::abs(std::log2(std::max(1.0e-4f, tileRedToGreen))),
                    std::abs(std::log2(std::max(1.0e-4f, tileBlueToGreen)))
            );
            shadingRedToGreenSamples.push_back(tileRedToGreen);
            shadingBlueToGreenSamples.push_back(tileBlueToGreen);
            shadingOpponentDifferentialStopsSamples.push_back(tileOpponentDifferentialStops);

            const float normalizedX = 2.0f * (static_cast<float>(gx) + 0.5f) /
                    static_cast<float>(field.gridCols) - 1.0f;
            const float normalizedY = 2.0f * (static_cast<float>(gy) + 0.5f) /
                    static_cast<float>(field.gridRows) - 1.0f;
            const float normalizedRadius = std::sqrt(normalizedX * normalizedX +
                    normalizedY * normalizedY) / 1.41421356237f;
            if (normalizedRadius <= 0.35f) {
                centerRedToGreenSum += tileRedToGreen;
                centerBlueToGreenSum += tileBlueToGreen;
                centerShadingTileCount++;
            } else if (normalizedRadius >= 0.70f) {
                outerRedToGreenSum += tileRedToGreen;
                outerBlueToGreenSum += tileBlueToGreen;
                outerShadingTileCount++;
            }

            noiseBudgetSamples.push_back(noiseBudgetRatio);
            modelMismatchSamples.push_back(modelMismatch);

            totalRawVar += tile.predictedRawVariance;
            totalVisibleVar += tile.predictedVisibleVariance;
            totalSpatialPredicted += tile.predictedSpatialResidualVariance;
            totalChromaPredicted += tile.predictedChromaResidualVariance;
            totalResidual += tile.residualEnergy;
            totalChroma += tile.chromaResidualEnergy;
            totalGreenSplit += tile.greenSplitResidualEnergy;
            totalGain += tile.meanLensShadingGain;
            totalConfidence += tile.confidence;
            totalDiagnosticConfidence += tile.diagnosticConfidence;
            validTiles++;
        }
    }

    field.totalTiles = static_cast<int>(field.tiles.size());
    field.validTiles = validTiles;
    if (validTiles > 0) {
        const double inv = 1.0 / static_cast<double>(validTiles);
        field.meanPredictedRawVariance = static_cast<float>(totalRawVar * inv);
        field.meanPredictedVisibleVariance = static_cast<float>(totalVisibleVar * inv);
        field.meanPredictedSpatialResidualVariance = static_cast<float>(totalSpatialPredicted * inv);
        field.meanPredictedChromaResidualVariance = static_cast<float>(totalChromaPredicted * inv);
        field.meanResidualEnergy = static_cast<float>(totalResidual * inv);
        field.meanChromaResidualEnergy = static_cast<float>(totalChroma * inv);
        field.meanGreenSplitResidualEnergy = static_cast<float>(totalGreenSplit * inv);
        field.meanLensShadingGain = static_cast<float>(totalGain * inv);
        field.meanConfidence = static_cast<float>(totalConfidence * inv);
        field.meanDiagnosticConfidence = static_cast<float>(totalDiagnosticConfidence * inv);
        field.confidenceP10 = spectraPercentile(confidenceSamples, 0.10f);
        field.confidenceP50 = spectraPercentile(confidenceSamples, 0.50f);
        field.confidenceP90 = spectraPercentile(confidenceSamples, 0.90f);
        field.lensShadingGainP10 = spectraPercentile(shadingGainSamples, 0.10f, 1.0f);
        field.lensShadingGainP50 = spectraPercentile(shadingGainSamples, 0.50f, 1.0f);
        field.lensShadingGainP90 = spectraPercentile(shadingGainSamples, 0.90f, 1.0f);
        field.lensShadingRedToGreenP50 =
                spectraPercentile(shadingRedToGreenSamples, 0.50f, 1.0f);
        field.lensShadingRedToGreenP90 =
                spectraPercentile(shadingRedToGreenSamples, 0.90f, 1.0f);
        field.lensShadingBlueToGreenP50 =
                spectraPercentile(shadingBlueToGreenSamples, 0.50f, 1.0f);
        field.lensShadingBlueToGreenP90 =
                spectraPercentile(shadingBlueToGreenSamples, 0.90f, 1.0f);
        field.lensShadingOpponentDifferentialStopsP50 =
                spectraPercentile(shadingOpponentDifferentialStopsSamples, 0.50f, 0.0f);
        field.lensShadingOpponentDifferentialStopsP90 =
                spectraPercentile(shadingOpponentDifferentialStopsSamples, 0.90f, 0.0f);
        if (centerShadingTileCount > 0 && outerShadingTileCount > 0) {
            field.lensShadingOuterMinusCenterRedToGreen = static_cast<float>(
                    outerRedToGreenSum / static_cast<double>(outerShadingTileCount) -
                    centerRedToGreenSum / static_cast<double>(centerShadingTileCount));
            field.lensShadingOuterMinusCenterBlueToGreen = static_cast<float>(
                    outerBlueToGreenSum / static_cast<double>(outerShadingTileCount) -
                    centerBlueToGreenSum / static_cast<double>(centerShadingTileCount));
        }
        field.noiseBudgetP10 = spectraPercentile(noiseBudgetSamples, 0.10f);
        field.noiseBudgetP50 = spectraPercentile(noiseBudgetSamples, 0.50f);
        field.noiseBudgetP90 = spectraPercentile(noiseBudgetSamples, 0.90f);
        field.modelMismatchP10 = spectraPercentile(modelMismatchSamples, 0.10f);
        field.modelMismatchP50 = spectraPercentile(modelMismatchSamples, 0.50f);
        field.modelMismatchP90 = spectraPercentile(modelMismatchSamples, 0.90f);
    }
    return field;
}
SpectraNoRegretResult IspCore::applySpectraNoRegretGate(
        const cv::Mat& before,
        LinearFloatRaw& candidate,
        const IspFrameMetadata& meta,
        const SpectraIsoAdaptiveState& isoState,
        const SpectraProvenanceField& beforeField,
        int passIndex
) {
    SpectraNoRegretResult result{};
    result.passIndex = passIndex;
    if (before.empty() || candidate.mosaic.empty() ||
        before.type() != CV_32FC1 || candidate.mosaic.type() != CV_32FC1 ||
        before.size() != candidate.mosaic.size() || beforeField.tiles.empty()) {
        candidate.mosaic = before.clone();
        return result;
    }

    const SpectraProvenanceField afterField = buildSpectraProvenanceField(candidate, meta);
    if (afterField.tiles.size() != beforeField.tiles.size()) {
        candidate.mosaic = before.clone();
        return result;
    }

    result.totalTiles = static_cast<int>(beforeField.tiles.size());
    std::vector<float> acceptance(beforeField.tiles.size(), 0.0f);
    std::vector<float> evaluatedAcceptance;
    double acceptanceSum = 0.0;
    double improvementSum = 0.0;
    double colourShiftSum = 0.0;
    double edgePreservationSum = 0.0;
    double oversmoothingSum = 0.0;
    float maximumColourShift = 0.0f;

    for (size_t i = 0; i < beforeField.tiles.size(); ++i) {
        const auto& b = beforeField.tiles[i];
        const auto& a = afterField.tiles[i];
        if (!b.valid || !a.valid || b.confidence < 0.05f || a.confidence < 0.05f) {
            continue;
        }
        result.evaluatedTiles++;

        float beforeEnergy = b.residualEnergy;
        float afterEnergy = a.residualEnergy;
        float targetBase = b.predictedSpatialResidualVariance;
        if (passIndex == 0) {
            beforeEnergy = b.greenSplitResidualEnergy;
            afterEnergy = a.greenSplitResidualEnergy;
            targetBase = b.predictedRawVarianceByChannel[1] +
                    b.predictedRawVarianceByChannel[2];
        } else if (passIndex == 2) {
            beforeEnergy = b.chromaResidualEnergy;
            afterEnergy = a.chromaResidualEnergy;
            targetBase = b.predictedChromaResidualVariance;
        } else if (passIndex == 3) {
            beforeEnergy = 0.55f * b.residualEnergy + 0.45f * b.chromaResidualEnergy;
            afterEnergy = 0.55f * a.residualEnergy + 0.45f * a.chromaResidualEnergy;
            targetBase = 0.55f * b.predictedSpatialResidualVariance +
                    0.45f * b.predictedChromaResidualVariance;
        }
        const float target = std::max(1.0e-12f, targetBase) * isoState.targetFloorScale;

        auto risk = [&](float energy) -> float {
            const float ratio = std::max(0.04f, energy / target);
            if (passIndex >= 2) {
                // Retired Pass 2 & 3: legacy chroma/low-frequency suppression is measurement-only pending neural ownership.
                // CFA opponent false-colour reduction at or below predicted target is desirable,
                // while true texture/edge preservation is guarded by structureRetention.
                return ratio > 1.0f ? std::log(ratio) : 0.0f;
            }
            if (ratio < 1.0f) {
                // Retired Pass 0 & 1: legacy luma suppression is measurement-only; keep objective scoring read-only;
                // texture preservation is enforced by detailRetentionFloor.
                return 0.35f * std::abs(std::log(std::max(0.40f, ratio)));
            }
            return std::log(ratio);
        };
        const float beforeRisk = risk(beforeEnergy);
        const float afterRisk = risk(afterEnergy);
        const float riskImprovement = beforeRisk - afterRisk;
        improvementSum += riskImprovement;

        const float minimumEnergy = target * isoState.minimumResidualRatio;
        const auto contextDecision = bncam::spectra::resolveContextFusionNoRegret({
                beforeEnergy,
                afterEnergy,
                target,
                b.structureEnergy,
                a.structureEnergy,
                isoState.minimumResidualRatio,
                isoState.detailRetentionFloor,
                b.confidence,
                riskImprovement,
                passIndex != 0
        });
        const float structureRetention = contextDecision.structureRetention;
        const float beforeGreen = 0.5f * (b.meanSignal[1] + b.meanSignal[2]);
        const float afterGreen = 0.5f * (a.meanSignal[1] + a.meanSignal[2]);
        const float deltaRg = (a.meanSignal[0] - afterGreen) - (b.meanSignal[0] - beforeGreen);
        const float deltaBg = (a.meanSignal[3] - afterGreen) - (b.meanSignal[3] - beforeGreen);
        const float colourShift = std::sqrt(deltaRg * deltaRg + deltaBg * deltaBg);
        colourShiftSum += colourShift;
        maximumColourShift = std::max(maximumColourShift, colourShift);
        edgePreservationSum += std::clamp(structureRetention, 0.0f, 1.0f);
        const float oversmoothingRisk = minimumEnergy > 1.0e-12f
                ? std::clamp((minimumEnergy - afterEnergy) / minimumEnergy, 0.0f, 1.0f)
                : 0.0f;
        oversmoothingSum += oversmoothingRisk;

        bool meanDrift = false;
        for (int ch = 0; ch < 4; ++ch) {
            const float channelDrift = std::abs(a.meanSignal[ch] - b.meanSignal[ch]);
            const float channelVariance = std::max(
                    1.0e-12f,
                    b.predictedRawVarianceByChannel[ch]
            );
            const float driftLimit = std::max(
                    0.00025f,
                    (1.20f + 0.35f * isoState.combinedNoisePressure) *
                            std::sqrt(channelVariance)
            );
            if (channelDrift > driftLimit) {
                meanDrift = true;
                break;
            }
        }

        float tileAcceptance = 0.0f;
        if (meanDrift) {
            result.rejectedMeanDrift++;
        } else if (contextDecision.worsened || contextDecision.noImprovement) {
            result.rejectedNoImprovement++;
        } else {
            tileAcceptance = contextDecision.acceptance;
            if (tileAcceptance <= 0.0f) {
                if (contextDecision.residualFloorLimited) result.rejectedOversmooth++;
                else if (contextDecision.detailFloorLimited) result.rejectedDetailLoss++;
                else result.rejectedNoImprovement++;
            } else if (tileAcceptance >= 0.90f) {
                result.acceptedTiles++;
            } else {
                result.partiallyAcceptedTiles++;
            }
        }
        acceptance[i] = tileAcceptance;
        evaluatedAcceptance.push_back(tileAcceptance);
        acceptanceSum += tileAcceptance;
    }

    result.invalidTiles = std::max(0, result.totalTiles - result.evaluatedTiles);
    result.rejectedTiles = std::max(
            0,
            result.evaluatedTiles - result.acceptedTiles - result.partiallyAcceptedTiles
    );

    result.meanAcceptance = result.evaluatedTiles > 0
            ? static_cast<float>(acceptanceSum / static_cast<double>(result.evaluatedTiles))
            : 0.0f;
    result.meanRiskImprovement = result.evaluatedTiles > 0
            ? static_cast<float>(improvementSum / static_cast<double>(result.evaluatedTiles))
            : 0.0f;
    result.acceptanceP10 = spectraPercentile(evaluatedAcceptance, 0.10f);
    result.acceptanceP50 = spectraPercentile(evaluatedAcceptance, 0.50f);
    result.acceptanceP90 = spectraPercentile(evaluatedAcceptance, 0.90f);
    result.meanColourShift = result.evaluatedTiles > 0
            ? static_cast<float>(colourShiftSum / static_cast<double>(result.evaluatedTiles))
            : 0.0f;
    result.maxColourShift = maximumColourShift;
    result.edgePreservationScore = result.evaluatedTiles > 0
            ? static_cast<float>(edgePreservationSum / static_cast<double>(result.evaluatedTiles))
            : 1.0f;
    result.oversmoothingScore = result.evaluatedTiles > 0
            ? static_cast<float>(oversmoothingSum / static_cast<double>(result.evaluatedTiles))
            : 0.0f;
    if (result.evaluatedTiles == 0) {
        candidate.mosaic = before.clone();
        result.attenuatedPixelFraction = 1.0f;
        result.rolledBackPixelFraction = result.attenuatedPixelFraction;
        return result;
    }

    const int width = candidate.mosaic.cols;
    const int height = candidate.mosaic.rows;
    std::atomic<uint64_t> rolledBack{0};
    cv::parallel_for_(cv::Range(0, height), [&](const cv::Range& range) {
        uint64_t localRollback = 0;
        for (int y = range.start; y < range.end; ++y) {
            const float* beforeRow = before.ptr<float>(y);
            float* candidateRow = candidate.mosaic.ptr<float>(y);
            const float gy = (static_cast<float>(y) + 0.5f) /
                    static_cast<float>(std::max(1, beforeField.tileHeight)) - 0.5f;
            const int gy0 = std::clamp(static_cast<int>(std::floor(gy)), 0, beforeField.gridRows - 1);
            const int gy1 = std::min(gy0 + 1, beforeField.gridRows - 1);
            const float ty = std::clamp(gy - static_cast<float>(gy0), 0.0f, 1.0f);
            for (int x = 0; x < width; ++x) {
                const float gx = (static_cast<float>(x) + 0.5f) /
                        static_cast<float>(std::max(1, beforeField.tileWidth)) - 0.5f;
                const int gx0 = std::clamp(static_cast<int>(std::floor(gx)), 0, beforeField.gridCols - 1);
                const int gx1 = std::min(gx0 + 1, beforeField.gridCols - 1);
                const float tx = std::clamp(gx - static_cast<float>(gx0), 0.0f, 1.0f);
                const auto weightAt = [&](int gxIndex, int gyIndex) -> float {
                    return acceptance[flatIndex2d(gyIndex, beforeField.gridCols, gxIndex)];
                };
                const float top = weightAt(gx0, gy0) +
                        (weightAt(gx1, gy0) - weightAt(gx0, gy0)) * tx;
                const float bottom = weightAt(gx0, gy1) +
                        (weightAt(gx1, gy1) - weightAt(gx0, gy1)) * tx;
                const float weight = std::clamp(top + (bottom - top) * ty, 0.0f, 1.0f);
                const float proposed = candidateRow[x];
                candidateRow[x] = beforeRow[x] + weight * (proposed - beforeRow[x]);
                if (weight < 0.999f) localRollback++;
            }
        }
        rolledBack.fetch_add(localRollback, std::memory_order_relaxed);
    });
    result.attenuatedPixelFraction = candidate.mosaic.total() > 0
            ? static_cast<float>(rolledBack.load(std::memory_order_relaxed)) /
                    static_cast<float>(candidate.mosaic.total())
            : 0.0f;
    result.rolledBackPixelFraction = result.attenuatedPixelFraction;
    return result;
}
SpectraPass0State IspCore::computePass0State(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    SpectraPass0State state{};
    state.spectraMode = meta.calibration.spectraProcessingMode;
    state.sourceFormat = rawSourceFormatName(raw.info.sourceFormat);
    state.lensKey = raw.info.lensId;
    const auto sceneBlackAuthority = bncam::raw_black::resolveSceneBlackAuthority(
            raw.info.dynamicBlackLevelUsed,
            raw.info.staticBlackLevelUsed);
    state.sceneBlackMetadataAuthoritative = sceneBlackAuthority.metadataAuthoritative;
    state.sceneBlackImageMutationAllowed = sceneBlackAuthority.imageDerivedMutationAllowed;
    state.sceneBlackAuthorityMode = sceneBlackAuthority.mode;
    const SpectraIsoAdaptiveState isoState = IspCore::resolveSpectraIsoAdaptiveState(meta, uiConfig);
    state.isoAuthority = isoState.lowFrequencyAuthority;

    if (state.spectraMode == 0) {
        state.fallbackReason = "legacy_mode";
        state.classification = "K. Insufficient evidence";
        return state;
    }

    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) {
        state.fallbackReason = "mosaic_empty";
        state.classification = "K. Insufficient evidence";
        return state;
    }

    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);

    const int marginX = width / 10;
    const int marginY = height / 10;
    const int startX = std::max(0, marginX);
    const int endX = std::min(width, width - marginX);
    const int startY = std::max(0, marginY);
    const int endY = std::min(height, height - marginY);

    const float whiteLevel = static_cast<float>(std::max(1, raw.info.payloadWhiteLevel));
    const float nearBlackThreshold = 0.15f;

    const int gridCols = 16;
    const int gridRows = 16;
    const float tileW = static_cast<float>(endX - startX) / gridCols;
    const float tileH = static_cast<float>(endY - startY) / gridRows;

    state.totalTileCount = gridCols * gridRows;

    std::vector<std::vector<float>> tileSamples(4);
    std::vector<float> g1g2Diffs;
    std::vector<float> g1g2TileMedians;
    std::vector<float> rMinusGDiffs;
    std::vector<float> bMinusGDiffs;

    std::vector<double> rowSums(height, 0.0);
    std::vector<uint64_t> rowCounts(height, 0);
    std::vector<double> colSums(width, 0.0);
    std::vector<uint64_t> colCounts(width, 0);

    for (int gy = 0; gy < gridRows; ++gy) {
        const int ty0 = startY + static_cast<int>(gy * tileH);
        const int ty1 = startY + static_cast<int>((gy + 1) * tileH);

        for (int gx = 0; gx < gridCols; ++gx) {
            const int tx0 = startX + static_cast<int>(gx * tileW);
            const int tx1 = startX + static_cast<int>((gx + 1) * tileW);

            double sum = 0.0, sqSum = 0.0;
            size_t count = 0, clipped = 0;
            for (int y = ty0; y < ty1; ++y) {
                const float* r = raw.mosaic.ptr<float>(y);
                for (int x = tx0; x < tx1; ++x) {
                    const float v = r[x];
                    if (!std::isfinite(v)) continue;
                    sum += v;
                    sqSum += v * v;
                    count++;
                    if (v <= 0.0f) clipped++;
                }
            }

            if (count < 32) continue;
            const double mean = sum / count;
            const double var = std::max(0.0, (sqSum / count) - (mean * mean));
            const double stdDev = std::sqrt(var);
            const double clipFrac = static_cast<double>(clipped) / count;

            if (mean <= nearBlackThreshold && stdDev <= 0.05 && clipFrac <= 0.20) {
                state.acceptedTileCount++;
                for (int y = ty0; y < ty1; ++y) {
                    const float* r = raw.mosaic.ptr<float>(y);
                    for (int x = tx0; x < tx1; ++x) {
                        const float v = r[x];
                        if (!std::isfinite(v)) continue;
                        const int ch = cfaColorChannel(cfaPattern, x, y);
                        if (ch >= 0 && ch < 4) {
                            tileSamples[ch].push_back(v);
                            rowSums[y] += v;
                            rowCounts[y]++;
                            colSums[x] += v;
                            colCounts[x]++;
                        }
                    }
                }

                std::vector<float> tileGreenDiffs;
                tileGreenDiffs.reserve(static_cast<size_t>(std::max(1, (ty1 - ty0) * (tx1 - tx0) / 4)));
                for (int y = ty0; y < ty1 - 1; y += 16) {
                    const float* r0 = raw.mosaic.ptr<float>(y);
                    const float* r1 = raw.mosaic.ptr<float>(y + 1);
                    for (int x = tx0; x < tx1 - 1; x += 16) {
                        float p00 = r0[x], p10 = r0[x + 1], p01 = r1[x], p11 = r1[x + 1];
                        if (!std::isfinite(p00) || !std::isfinite(p10) || !std::isfinite(p01) || !std::isfinite(p11)) continue;
                        float rVal = 0.0f, g1Val = 0.0f, g2Val = 0.0f, bVal = 0.0f;
                        switch (cfaPattern) {
                            case CFA_RGGB: rVal = p00; g1Val = p10; g2Val = p01; bVal = p11; break;
                            case CFA_GRBG: g1Val = p00; rVal = p10; bVal = p01; g2Val = p11; break;
                            case CFA_GBRG: g1Val = p00; bVal = p10; rVal = p01; g2Val = p11; break;
                            case CFA_BGGR: bVal = p00; g2Val = p10; g1Val = p01; rVal = p11; break;
                        }
                        const float greenDiffCode = (g1Val - g2Val) * whiteLevel;
                        g1g2Diffs.push_back(greenDiffCode);
                        tileGreenDiffs.push_back(greenDiffCode);
                        float gAvg = (g1Val + g2Val) * 0.5f;
                        rMinusGDiffs.push_back((rVal - gAvg) * whiteLevel);
                        bMinusGDiffs.push_back((bVal - gAvg) * whiteLevel);
                    }
                }
                if (tileGreenDiffs.size() >= 16u) {
                    g1g2TileMedians.push_back(medianFromSamples(tileGreenDiffs));
                }
            }
        }
    }

    state.darkTileConfidence = state.totalTileCount > 0 && state.acceptedTileCount >= 4
            ? static_cast<float>(state.acceptedTileCount) / static_cast<float>(state.totalTileCount)
            : 0.0f;

    if (state.acceptedTileCount < 4 || state.darkTileConfidence < 0.015f) {
        state.fallbackReason = "insufficient_tiles";
        state.classification = "K. Insufficient evidence";
        return state;
    }

    // Compute robust medians per channel
    std::array<float, 4> channelMedians{0.0f, 0.0f, 0.0f, 0.0f};
    for (int ch = 0; ch < 4; ++ch) {
        if (!tileSamples[ch].empty()) {
            std::sort(tileSamples[ch].begin(), tileSamples[ch].end());
            const size_t mid = tileSamples[ch].size() / 2;
            channelMedians[ch] = tileSamples[ch][mid] * whiteLevel;
            state.channelBiasBefore[ch] = channelMedians[ch];
        }
    }

    // G1/G2 divergence must agree across many independent tiles before a
    // frame-wide correction is allowed. A single coloured shadow or directional
    // texture is therefore unable to steer Pass 0.
    state.greenSplitTileCount = static_cast<int>(g1g2TileMedians.size());
    if (!g1g2TileMedians.empty()) {
        state.g1g2Before = medianFromSamples(g1g2TileMedians);
        int sameSign = 0;
        const float referenceSign = state.g1g2Before >= 0.0f ? 1.0f : -1.0f;
        std::vector<float> tileDeviations;
        tileDeviations.reserve(g1g2TileMedians.size());
        for (const float value : g1g2TileMedians) {
            if ((value >= 0.0f ? 1.0f : -1.0f) == referenceSign) sameSign++;
            tileDeviations.push_back(std::abs(value - state.g1g2Before));
        }
        state.greenSplitTileConsensus = static_cast<float>(sameSign) /
                static_cast<float>(g1g2TileMedians.size());
        state.greenSplitMad = medianFromSamples(tileDeviations);
    }

    const float greenRef = (channelMedians[1] + channelMedians[2]) * 0.5f;
    const float rDiff = std::abs(channelMedians[0] - greenRef);
    const float bDiff = std::abs(channelMedians[3] - greenRef);
    const float g1g2AbsDiff = std::abs(channelMedians[1] - channelMedians[2]);

    // Root-cause classification
    if (g1g2AbsDiff > 1.5f && state.darkTileConfidence >= 0.15f) {
        state.classification = "A. G1/G2 bias";
    } else if (rDiff > 2.0f && bDiff > 2.0f) {
        state.classification = "B. General green-channel black bias";
    } else if (rDiff > 2.0f || bDiff > 2.0f) {
        state.classification = "C. Red or blue channel under-response";
    } else {
        state.classification = "J. Random chroma noise without significant systematic bias";
    }

    // Pass 0 deliberately never derives a global R/B correction from scene content.
    // R and B may legitimately differ from green in a dark coloured scene. The two
    // green sites, however, measure the same spectral band, so a spatially consistent
    // G1/G2 offset is a defensible pre-demosaic correction target.
    const double greenSignal = std::clamp(
            static_cast<double>(greenRef / std::max(1.0f, whiteLevel)),
            0.0,
            1.0
    );
    const double greenS = 0.5 * (meta.calibration.effectiveS[1] + meta.calibration.effectiveS[2]);
    const double greenO = 0.5 * (meta.calibration.effectiveO[1] + meta.calibration.effectiveO[2]);
    const float predictedGreenSigmaCode = isVstValid(greenS, greenO)
            ? static_cast<float>(std::sqrt(std::max(0.0, greenS * greenSignal + greenO)) * whiteLevel)
            : 1.0f;
    const float activationThresholdCode = std::max(0.75f, 1.5f * predictedGreenSigmaCode);
    const float consistencyLimit = std::max(1.25f, 0.75f * std::abs(state.g1g2Before));
    const float modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);
    state.channelBiasConfidence = state.darkTileConfidence * modelConfidence *
            state.greenSplitTileConsensus *
            std::clamp(1.0f - state.greenSplitMad / std::max(consistencyLimit, 1.0e-6f), 0.0f, 1.0f);

    if (state.sceneBlackImageMutationAllowed &&
        state.acceptedTileCount >= 16 &&
        state.greenSplitTileCount >= 12 &&
        state.greenSplitTileConsensus >= 0.80f &&
        state.channelBiasConfidence >= 0.04f &&
        std::abs(state.g1g2Before) > activationThresholdCode &&
        state.greenSplitMad <= consistencyLimit) {
        const float halfCorrectionCode = std::clamp(
                0.5f * state.g1g2Before,
                -1.0f,
                1.0f
        );
        const float normalizedHalf = halfCorrectionCode / std::max(1.0f, whiteLevel);
        const float boundedIsoAuthority = std::clamp(0.45f + 0.55f * state.isoAuthority, 0.45f, 1.0f);
        state.appliedChannelBias[1] = -normalizedHalf * boundedIsoAuthority;
        state.appliedChannelBias[2] = normalizedHalf * boundedIsoAuthority;
        state.channelBiasAfter = channelMedians;
        state.channelBiasAfter[1] += state.appliedChannelBias[1] * whiteLevel;
        state.channelBiasAfter[2] += state.appliedChannelBias[2] * whiteLevel;
        state.g1g2After = state.g1g2Before +
                (state.appliedChannelBias[1] - state.appliedChannelBias[2]) * whiteLevel;
        state.applyChannelBias = true;
        state.fallbackReason = "none";
    } else {
        state.g1g2After = state.g1g2Before;
        state.fallbackReason = state.sceneBlackImageMutationAllowed
                ? "no_confident_green_split_bias"
                : "metadata_black_authoritative_scene_scan_validator_only";
    }

    // Row/column and low-frequency classical correction ownership is retired.
    // These flags remain false; observer-only pattern evidence must not mutate RAW pixels.
    state.applyRowCorrection = false;
    state.applyColumnCorrection = false;
    return state;
}

SpectraPass0State IspCore::computePass0StateCompact(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    SpectraPass0State state{};
    state.planningMethod = "COMPACT_RAW16_16X16_CFA_BLOCK_GRID";
    state.spectraMode = meta.calibration.spectraProcessingMode;
    state.sourceFormat = rawSourceFormatName(raw.info.sourceFormat);
    state.lensKey = raw.info.lensId;
    const auto sceneBlackAuthority = bncam::raw_black::resolveSceneBlackAuthority(
            raw.info.dynamicBlackLevelUsed,
            raw.info.staticBlackLevelUsed);
    state.sceneBlackMetadataAuthoritative = sceneBlackAuthority.metadataAuthoritative;
    state.sceneBlackImageMutationAllowed = sceneBlackAuthority.imageDerivedMutationAllowed;
    state.sceneBlackAuthorityMode = sceneBlackAuthority.mode;
    const SpectraIsoAdaptiveState isoState = IspCore::resolveSpectraIsoAdaptiveState(meta, uiConfig);
    state.isoAuthority = isoState.lowFrequencyAuthority;

    if (state.spectraMode == 0) {
        state.fallbackReason = "legacy_mode";
        state.classification = "K. Insufficient evidence";
        return state;
    }
    if (!raw.valid || raw.info.width <= 0 || raw.info.height <= 0) {
        state.fallbackReason = raw.failureReason.empty() ? "raw16_sample_view_invalid" : raw.failureReason;
        state.classification = "K. Insufficient evidence";
        return state;
    }

    const int width = raw.info.width;
    const int height = raw.info.height;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    const int marginX = width / 10;
    const int marginY = height / 10;
    const int startX = std::max(0, marginX);
    const int endX = std::min(width, width - marginX);
    const int startY = std::max(0, marginY);
    const int endY = std::min(height, height - marginY);
    const float whiteLevel = static_cast<float>(std::max(1, raw.info.payloadWhiteLevel));
    constexpr float nearBlackThreshold = 0.15f;
    constexpr int gridCols = 16;
    constexpr int gridRows = 16;
    const float tileW = static_cast<float>(endX - startX) / gridCols;
    const float tileH = static_cast<float>(endY - startY) / gridRows;
    state.totalTileCount = gridCols * gridRows;

    std::array<std::vector<float>, 4> acceptedChannelSamples;
    std::vector<float> g1g2TileMedians;

    const auto evenStepForSpan = [](int span) -> int {
        const int target = std::max(2, (span + 15) / 16);
        return std::max(2, (target + 1) & ~1);
    };

    for (int gy = 0; gy < gridRows; ++gy) {
        const int ty0 = startY + static_cast<int>(gy * tileH);
        const int ty1 = startY + static_cast<int>((gy + 1) * tileH);
        for (int gx = 0; gx < gridCols; ++gx) {
            const int tx0 = startX + static_cast<int>(gx * tileW);
            const int tx1 = startX + static_cast<int>((gx + 1) * tileW);
            if (tx1 - tx0 < 2 || ty1 - ty0 < 2) continue;

            const int stepX = evenStepForSpan(tx1 - tx0);
            const int stepY = evenStepForSpan(ty1 - ty0);
            const int baseX = tx0 + ((tx0 & 1) ? 1 : 0);
            const int baseY = ty0 + ((ty0 & 1) ? 1 : 0);
            double sum = 0.0;
            double sqSum = 0.0;
            std::size_t count = 0u;
            std::size_t clipped = 0u;
            std::array<std::vector<float>, 4> tileChannelSamples;
            std::vector<float> tileGreenDiffs;

            for (int y = baseY; y + 1 < ty1; y += stepY) {
                for (int x = baseX; x + 1 < tx1; x += stepX) {
                    float p[4] = {
                        raw.sample(x, y), raw.sample(x + 1, y),
                        raw.sample(x, y + 1), raw.sample(x + 1, y + 1)
                    };
                    bool blockValid = true;
                    for (float value : p) {
                        if (!std::isfinite(value)) { blockValid = false; break; }
                    }
                    if (!blockValid) continue;
                    for (int py = 0; py < 2; ++py) {
                        for (int px = 0; px < 2; ++px) {
                            const int index = py * 2 + px;
                            const float value = p[index];
                            sum += value;
                            sqSum += static_cast<double>(value) * value;
                            ++count;
                            ++state.planningSampleCount;
                            if (value <= 0.0f) ++clipped;
                            const int channel = cfaColorChannel(cfaPattern, x + px, y + py);
                            if (channel >= 0 && channel < 4) {
                                tileChannelSamples[static_cast<std::size_t>(channel)].push_back(value);
                            }
                        }
                    }

                    float rVal = 0.0f, g1Val = 0.0f, g2Val = 0.0f, bVal = 0.0f;
                    switch (cfaPattern) {
                        case CFA_RGGB: rVal = p[0]; g1Val = p[1]; g2Val = p[2]; bVal = p[3]; break;
                        case CFA_GRBG: g1Val = p[0]; rVal = p[1]; bVal = p[2]; g2Val = p[3]; break;
                        case CFA_GBRG: g1Val = p[0]; bVal = p[1]; rVal = p[2]; g2Val = p[3]; break;
                        case CFA_BGGR: bVal = p[0]; g2Val = p[1]; g1Val = p[2]; rVal = p[3]; break;
                    }
                    (void)rVal;
                    (void)bVal;
                    tileGreenDiffs.push_back((g1Val - g2Val) * whiteLevel);
                }
            }

            if (count < 32u) continue;
            const double mean = sum / static_cast<double>(count);
            const double variance = std::max(0.0, sqSum / static_cast<double>(count) - mean * mean);
            const double stdDev = std::sqrt(variance);
            const double clipFraction = static_cast<double>(clipped) / static_cast<double>(count);
            if (mean > nearBlackThreshold || stdDev > 0.05 || clipFraction > 0.20) continue;

            ++state.acceptedTileCount;
            for (int channel = 0; channel < 4; ++channel) {
                auto& destination = acceptedChannelSamples[static_cast<std::size_t>(channel)];
                const auto& source = tileChannelSamples[static_cast<std::size_t>(channel)];
                destination.insert(destination.end(), source.begin(), source.end());
            }
            if (tileGreenDiffs.size() >= 16u) {
                g1g2TileMedians.push_back(medianFromSamples(tileGreenDiffs));
            }
        }
    }

    state.darkTileConfidence = state.totalTileCount > 0 && state.acceptedTileCount >= 4
            ? static_cast<float>(state.acceptedTileCount) / static_cast<float>(state.totalTileCount)
            : 0.0f;
    if (state.acceptedTileCount < 4 || state.darkTileConfidence < 0.015f) {
        state.fallbackReason = "insufficient_tiles";
        state.classification = "K. Insufficient evidence";
        return state;
    }

    std::array<float, 4> channelMedians{0.0f, 0.0f, 0.0f, 0.0f};
    for (int channel = 0; channel < 4; ++channel) {
        auto& samples = acceptedChannelSamples[static_cast<std::size_t>(channel)];
        if (samples.empty()) continue;
        channelMedians[static_cast<std::size_t>(channel)] = medianFromSamples(samples) * whiteLevel;
        state.channelBiasBefore[static_cast<std::size_t>(channel)] =
                channelMedians[static_cast<std::size_t>(channel)];
    }

    state.greenSplitTileCount = static_cast<int>(g1g2TileMedians.size());
    if (!g1g2TileMedians.empty()) {
        state.g1g2Before = medianFromSamples(g1g2TileMedians);
        int sameSign = 0;
        const float referenceSign = state.g1g2Before >= 0.0f ? 1.0f : -1.0f;
        std::vector<float> tileDeviations;
        tileDeviations.reserve(g1g2TileMedians.size());
        for (const float value : g1g2TileMedians) {
            if ((value >= 0.0f ? 1.0f : -1.0f) == referenceSign) ++sameSign;
            tileDeviations.push_back(std::abs(value - state.g1g2Before));
        }
        state.greenSplitTileConsensus = static_cast<float>(sameSign) /
                static_cast<float>(g1g2TileMedians.size());
        state.greenSplitMad = medianFromSamples(tileDeviations);
    }

    const float greenRef = 0.5f * (channelMedians[1] + channelMedians[2]);
    const float rDiff = std::abs(channelMedians[0] - greenRef);
    const float bDiff = std::abs(channelMedians[3] - greenRef);
    const float g1g2AbsDiff = std::abs(channelMedians[1] - channelMedians[2]);
    if (g1g2AbsDiff > 1.5f && state.darkTileConfidence >= 0.15f) {
        state.classification = "A. G1/G2 bias";
    } else if (rDiff > 2.0f && bDiff > 2.0f) {
        state.classification = "B. General green-channel black bias";
    } else if (rDiff > 2.0f || bDiff > 2.0f) {
        state.classification = "C. Red or blue channel under-response";
    } else {
        state.classification = "J. Random chroma noise without significant systematic bias";
    }

    const double greenSignal = std::clamp(
            static_cast<double>(greenRef / std::max(1.0f, whiteLevel)), 0.0, 1.0);
    const double greenS = 0.5 * (meta.calibration.effectiveS[1] + meta.calibration.effectiveS[2]);
    const double greenO = 0.5 * (meta.calibration.effectiveO[1] + meta.calibration.effectiveO[2]);
    const float predictedGreenSigmaCode = isVstValid(greenS, greenO)
            ? static_cast<float>(std::sqrt(std::max(0.0, greenS * greenSignal + greenO)) * whiteLevel)
            : 1.0f;
    const float activationThresholdCode = std::max(0.75f, 1.5f * predictedGreenSigmaCode);
    const float consistencyLimit = std::max(1.25f, 0.75f * std::abs(state.g1g2Before));
    const float modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);
    state.channelBiasConfidence = state.darkTileConfidence * modelConfidence *
            state.greenSplitTileConsensus *
            std::clamp(1.0f - state.greenSplitMad / std::max(consistencyLimit, 1.0e-6f), 0.0f, 1.0f);

    if (state.sceneBlackImageMutationAllowed &&
        state.acceptedTileCount >= 16 && state.greenSplitTileCount >= 12 &&
        state.greenSplitTileConsensus >= 0.80f && state.channelBiasConfidence >= 0.04f &&
        std::abs(state.g1g2Before) > activationThresholdCode &&
        state.greenSplitMad <= consistencyLimit) {
        const float halfCorrectionCode = std::clamp(0.5f * state.g1g2Before, -1.0f, 1.0f);
        const float normalizedHalf = halfCorrectionCode / std::max(1.0f, whiteLevel);
        const float boundedIsoAuthority = std::clamp(0.45f + 0.55f * state.isoAuthority, 0.45f, 1.0f);
        state.appliedChannelBias[1] = -normalizedHalf * boundedIsoAuthority;
        state.appliedChannelBias[2] = normalizedHalf * boundedIsoAuthority;
        state.channelBiasAfter = channelMedians;
        state.channelBiasAfter[1] += state.appliedChannelBias[1] * whiteLevel;
        state.channelBiasAfter[2] += state.appliedChannelBias[2] * whiteLevel;
        state.g1g2After = state.g1g2Before +
                (state.appliedChannelBias[1] - state.appliedChannelBias[2]) * whiteLevel;
        state.applyChannelBias = true;
        state.fallbackReason = "none";
    } else {
        state.g1g2After = state.g1g2Before;
        state.fallbackReason = state.sceneBlackImageMutationAllowed
                ? "no_confident_green_split_bias"
                : "metadata_black_authoritative_scene_scan_validator_only";
    }
    state.applyRowCorrection = false;
    state.applyColumnCorrection = false;
    return state;
}

void IspCore::applySpectraPass0(
        LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        const SpectraPass0State& pass0State
) {
    if (!pass0State.applyChannelBias && !pass0State.applyRowCorrection && !pass0State.applyColumnCorrection) {
        return;
    }

    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) return;

    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);

    if (pass0State.applyChannelBias) {
        cv::parallel_for_(cv::Range(0, height), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                float* row = raw.mosaic.ptr<float>(y);
                for (int x = 0; x < width; ++x) {
                    const int ch = cfaColorChannel(cfaPattern, x, y);
                    if (ch >= 0 && ch < 4) {
                        row[x] += pass0State.appliedChannelBias[ch];
                    }
                }
            }
        });
    }
}



float spectraGreenGuideAt(
        const cv::Mat& mosaic,
        int cfaPattern,
        int x,
        int y
) {
    if (mosaic.empty()) return 0.0f;
    x = std::clamp(x, 0, mosaic.cols - 1);
    y = std::clamp(y, 0, mosaic.rows - 1);
    const int channel = cfaColorChannel(cfaPattern, x, y);
    if (channel == 1 || channel == 2) return mosaic.ptr<float>(y)[x];
    double sum = 0.0;
    int count = 0;
    constexpr int dx[4] = {-1, 1, 0, 0};
    constexpr int dy[4] = {0, 0, -1, 1};
    for (int i = 0; i < 4; ++i) {
        const int sx = std::clamp(x + dx[i], 0, mosaic.cols - 1);
        const int sy = std::clamp(y + dy[i], 0, mosaic.rows - 1);
        const int neighbourChannel = cfaColorChannel(cfaPattern, sx, sy);
        if (neighbourChannel == 1 || neighbourChannel == 2) {
            sum += mosaic.ptr<float>(sy)[sx];
            ++count;
        }
    }
    return count > 0 ? static_cast<float>(sum / static_cast<double>(count))
                     : mosaic.ptr<float>(y)[x];
}

struct SpectraStructureTensorCell {
    float jxx = 0.0f;
    float jxy = 0.0f;
    float jyy = 0.0f;
    float gradientNoiseVariance = 1.0e-8f;
};

struct SpectraStructureTensorField {
    int step = 16;
    int columns = 0;
    int rows = 0;
    int imageWidth = 0;
    int imageHeight = 0;
    bool valid = false;
    std::vector<SpectraStructureTensorCell> cells;

    [[nodiscard]] const SpectraStructureTensorCell& at(int x, int y) const {
        return cells[flatIndex2d(y, columns, x)];
    }
};

SpectraStructureTensorField buildSpectraStructureTensorField(
        const cv::Mat& mosaic,
        int cfaPattern,
        double greenS,
        double greenO
) {
    SpectraStructureTensorField field{};
    field.imageWidth = mosaic.cols;
    field.imageHeight = mosaic.rows;
    if (mosaic.empty() || mosaic.type() != CV_32FC1 || mosaic.cols < 16 || mosaic.rows < 16) {
        return field;
    }
    field.columns = std::max(2, (mosaic.cols + field.step - 1) / field.step + 1);
    field.rows = std::max(2, (mosaic.rows + field.step - 1) / field.step + 1);
    field.cells.resize(static_cast<size_t>(field.columns) * static_cast<size_t>(field.rows));

    cv::parallel_for_(cv::Range(0, field.rows), [&](const cv::Range& range) {
        constexpr float spatialWeights[9] = {
            0.50f, 0.72f, 0.50f,
            0.72f, 1.00f, 0.72f,
            0.50f, 0.72f, 0.50f
        };
        for (int gy = range.start; gy < range.end; ++gy) {
            const int cy = std::clamp(gy * field.step, 4, mosaic.rows - 5);
            for (int gx = 0; gx < field.columns; ++gx) {
                const int cx = std::clamp(gx * field.step, 4, mosaic.cols - 5);
                double jxx = 0.0;
                double jxy = 0.0;
                double jyy = 0.0;
                double weightSum = 0.0;
                int sampleIndex = 0;
                for (int oy = -2; oy <= 2; oy += 2) {
                    for (int ox = -2; ox <= 2; ox += 2) {
                        const int px = cx + ox;
                        const int py = cy + oy;
                        const float gradX = 0.25f * (
                                spectraGreenGuideAt(mosaic, cfaPattern, px + 2, py) -
                                spectraGreenGuideAt(mosaic, cfaPattern, px - 2, py)
                        );
                        const float gradY = 0.25f * (
                                spectraGreenGuideAt(mosaic, cfaPattern, px, py + 2) -
                                spectraGreenGuideAt(mosaic, cfaPattern, px, py - 2)
                        );
                        const float weight = spatialWeights[sampleIndex++];
                        if (!std::isfinite(gradX) || !std::isfinite(gradY)) continue;
                        jxx += static_cast<double>(weight * gradX * gradX);
                        jxy += static_cast<double>(weight * gradX * gradY);
                        jyy += static_cast<double>(weight * gradY * gradY);
                        weightSum += weight;
                    }
                }
                const float centerGreen = spectraGreenGuideAt(mosaic, cfaPattern, cx, cy);
                const double greenVariance = std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, centerGreen) + greenO
                );
                // Central differences and local green interpolation reduce the raw
                // per-sample variance. Keep a conservative floor so noise alone
                // does not acquire directional authority.
                const float gradientNoiseVariance = static_cast<float>(
                        std::max(1.0e-10, 0.42 * greenVariance)
                );
                SpectraStructureTensorCell cell{};
                if (weightSum > 1.0e-8) {
                    cell.jxx = static_cast<float>(jxx / weightSum);
                    cell.jxy = static_cast<float>(jxy / weightSum);
                    cell.jyy = static_cast<float>(jyy / weightSum);
                }
                cell.gradientNoiseVariance = gradientNoiseVariance;
                field.cells[flatIndex2d(gy, field.columns, gx)] = cell;
            }
        }
    });
    field.valid = !field.cells.empty();
    return field;
}

float spectraGreenGuideAt(
        const RawNormalizedSampleView& raw,
        int cfaPattern,
        int x,
        int y
) {
    if (!raw.valid || raw.info.width <= 0 || raw.info.height <= 0) return 0.0f;
    x = std::clamp(x, 0, raw.info.width - 1);
    y = std::clamp(y, 0, raw.info.height - 1);
    const int channel = cfaColorChannel(cfaPattern, x, y);
    if (channel == 1 || channel == 2) return raw.sample(x, y);
    double sum = 0.0;
    int count = 0;
    constexpr int dx[4] = {-1, 1, 0, 0};
    constexpr int dy[4] = {0, 0, -1, 1};
    for (int i = 0; i < 4; ++i) {
        const int sx = std::clamp(x + dx[i], 0, raw.info.width - 1);
        const int sy = std::clamp(y + dy[i], 0, raw.info.height - 1);
        const int neighbourChannel = cfaColorChannel(cfaPattern, sx, sy);
        if (neighbourChannel == 1 || neighbourChannel == 2) {
            const float value = raw.sample(sx, sy);
            if (std::isfinite(value)) {
                sum += value;
                ++count;
            }
        }
    }
    const float center = raw.sample(x, y);
    return count > 0 ? static_cast<float>(sum / static_cast<double>(count))
                     : (std::isfinite(center) ? center : 0.0f);
}

SpectraStructureTensorField buildSpectraStructureTensorFieldCompact(
        const RawNormalizedSampleView& raw,
        int cfaPattern,
        double greenS,
        double greenO
) {
    SpectraStructureTensorField field{};
    field.imageWidth = raw.info.width;
    field.imageHeight = raw.info.height;
    if (!raw.valid || raw.info.width < 16 || raw.info.height < 16) return field;
    field.columns = std::max(2, (raw.info.width + field.step - 1) / field.step + 1);
    field.rows = std::max(2, (raw.info.height + field.step - 1) / field.step + 1);
    field.cells.resize(static_cast<size_t>(field.columns) * static_cast<size_t>(field.rows));

    // CONTROL_OK: CPU work is bounded to the step-16 tensor grid; no full-frame pixel pass.
    cv::parallel_for_(cv::Range(0, field.rows), [&](const cv::Range& range) {
        constexpr float spatialWeights[9] = {
            0.50f, 0.72f, 0.50f,
            0.72f, 1.00f, 0.72f,
            0.50f, 0.72f, 0.50f
        };
        for (int gy = range.start; gy < range.end; ++gy) {
            const int cy = std::clamp(gy * field.step, 4, raw.info.height - 5);
            for (int gx = 0; gx < field.columns; ++gx) {
                const int cx = std::clamp(gx * field.step, 4, raw.info.width - 5);
                double jxx = 0.0;
                double jxy = 0.0;
                double jyy = 0.0;
                double weightSum = 0.0;
                int sampleIndex = 0;
                for (int oy = -2; oy <= 2; oy += 2) {
                    for (int ox = -2; ox <= 2; ox += 2) {
                        const int px = cx + ox;
                        const int py = cy + oy;
                        const float gradX = 0.25f * (
                                spectraGreenGuideAt(raw, cfaPattern, px + 2, py) -
                                spectraGreenGuideAt(raw, cfaPattern, px - 2, py));
                        const float gradY = 0.25f * (
                                spectraGreenGuideAt(raw, cfaPattern, px, py + 2) -
                                spectraGreenGuideAt(raw, cfaPattern, px, py - 2));
                        const float weight = spatialWeights[sampleIndex++];
                        if (!std::isfinite(gradX) || !std::isfinite(gradY)) continue;
                        jxx += static_cast<double>(weight * gradX * gradX);
                        jxy += static_cast<double>(weight * gradX * gradY);
                        jyy += static_cast<double>(weight * gradY * gradY);
                        weightSum += weight;
                    }
                }
                const float centerGreen = spectraGreenGuideAt(raw, cfaPattern, cx, cy);
                const double greenVariance = std::max(
                        1.0e-12, greenS * std::max(0.0f, centerGreen) + greenO);
                const float gradientNoiseVariance = static_cast<float>(
                        std::max(1.0e-10, 0.42 * greenVariance));
                SpectraStructureTensorCell cell{};
                if (weightSum > 1.0e-8) {
                    cell.jxx = static_cast<float>(jxx / weightSum);
                    cell.jxy = static_cast<float>(jxy / weightSum);
                    cell.jyy = static_cast<float>(jyy / weightSum);
                }
                cell.gradientNoiseVariance = gradientNoiseVariance;
                field.cells[flatIndex2d(gy, field.columns, gx)] = cell;
            }
        }
    });
    field.valid = !field.cells.empty();
    return field;
}

bncam::spectra2::StructureTensorEstimate interpolateSpectraStructureTensor(
        const SpectraStructureTensorField& field,
        int x,
        int y
) {
    if (!field.valid || field.columns < 2 || field.rows < 2) return {};
    const float fx = std::clamp(
            static_cast<float>(x) / static_cast<float>(field.step),
            0.0f,
            static_cast<float>(field.columns - 1)
    );
    const float fy = std::clamp(
            static_cast<float>(y) / static_cast<float>(field.step),
            0.0f,
            static_cast<float>(field.rows - 1)
    );
    const int x0 = std::min(field.columns - 2, static_cast<int>(std::floor(fx)));
    const int y0 = std::min(field.rows - 2, static_cast<int>(std::floor(fy)));
    const int x1 = x0 + 1;
    const int y1 = y0 + 1;
    const float tx = std::clamp(fx - static_cast<float>(x0), 0.0f, 1.0f);
    const float ty = std::clamp(fy - static_cast<float>(y0), 0.0f, 1.0f);
    auto interpolate = [&](float SpectraStructureTensorCell::*member) -> float {
        const float top = (field.at(x0, y0)).*member * (1.0f - tx) +
                (field.at(x1, y0)).*member * tx;
        const float bottom = (field.at(x0, y1)).*member * (1.0f - tx) +
                (field.at(x1, y1)).*member * tx;
        return top * (1.0f - ty) + bottom * ty;
    };
    return bncam::spectra2::structureTensorFromMoments(
            interpolate(&SpectraStructureTensorCell::jxx),
            interpolate(&SpectraStructureTensorCell::jxy),
            interpolate(&SpectraStructureTensorCell::jyy),
            interpolate(&SpectraStructureTensorCell::gradientNoiseVariance)
    );
}

float histogramPercentile(
        const std::array<std::uint64_t, 32>& histogram,
        std::uint64_t count,
        float quantile
) {
    if (count == 0) return 0.0f;
    const std::uint64_t target = static_cast<std::uint64_t>(
            std::ceil(std::clamp(quantile, 0.0f, 1.0f) * static_cast<float>(count))
    );
    std::uint64_t cumulative = 0;
    for (size_t i = 0; i < histogram.size(); ++i) {
        cumulative += histogram[i];
        if (cumulative >= std::max<std::uint64_t>(1, target)) {
            return (static_cast<float>(i) + 0.5f) /
                    static_cast<float>(histogram.size());
        }
    }
    return 1.0f;
}

std::string SpectraPass1State::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(4);
    out << "spectraPass1={"
        << "mode=" << (physicalBaselineMode ? "physical_single_frame" : (spectraMode == 1 ? "auto" : (spectraMode == 2 ? "manual" : "legacy")))
        << ";authoritySource=" << authoritySource
        << ";applied=" << (applied ? "true" : "false")
        << ";vstResidualVar=" << averageVstResidualVar
        << ";maxPixelShift=" << maxPixelShift
        << ";modelConfidence=" << modelConfidence
        << ";blendStrength=" << blendStrength
        << ";averageWienerGain=" << averageWienerGain
        << ";edgeProtectedFraction=" << edgeProtectedFraction
        << ";changedPixelFraction=" << changedPixelFraction
        << ";isoAuthority=" << isoAuthority
        << ";combinedNoisePressure=" << combinedNoisePressure
        << ";meanShadingAuthority=" << localShadingAuthorityMean
        << ";noRegretAcceptedTiles=" << noRegretAcceptedTileFraction
        << ";noRegretRollback=" << noRegretRollbackFraction
        << ";effectiveS=[" << effectiveS[0] << "," << effectiveS[1] << "," << effectiveS[2] << "," << effectiveS[3] << "]"
        << ";effectiveO=[" << effectiveO[0] << "," << effectiveO[1] << "," << effectiveO[2] << "," << effectiveO[3] << "]"
        << ";fallbackReason=" << fallbackReason
        << ";processingTimeMs=" << processingTimeMs
        << ";anisotropicStatus=" << anisotropicDetail.status
        << ";anisotropicApplied=" << (anisotropicDetail.applied ? "true" : "false")
        << ";tensorConfidenceP50=" << anisotropicDetail.confidenceP50
        << ";tensorCoherenceP50=" << anisotropicDetail.coherenceP50
        << ";tensorFallbackFraction=" << anisotropicDetail.fallbackFraction
        << ";contextFlatFraction=" << anisotropicDetail.contextFlatFraction
        << ";contextStructureProtectedFraction=" << anisotropicDetail.contextStructureProtectedFraction
        << ";contextBoostedFraction=" << anisotropicDetail.contextBoostedFraction
        << ";vulkanKernelConnected=" << (vulkanKernelConnected ? "true" : "false")
        << ";vulkanAttempted=" << (vulkanAttempted ? "true" : "false")
        << ";vulkanExecutionSucceeded=" << (vulkanExecutionSucceeded ? "true" : "false")
        << ";vulkanUsedForOutput=" << (vulkanUsedForOutput ? "true" : "false")
        << ";vulkanCpuFallbackUsed=" << (vulkanCpuFallbackUsed ? "true" : "false")
        << ";vulkanGpuNoRegretBlendUsed=" << (vulkanGpuNoRegretBlendUsed ? "true" : "false")
        << ";vulkanCandidateReadbackAvoided=" << (vulkanCandidateReadbackAvoided ? "true" : "false")
        << ";vulkanPersistentReuseHit=" << (vulkanPersistentReuseHit ? "true" : "false")
        << ";vulkanPersistentReallocated=" << (vulkanPersistentReallocated ? "true" : "false")
        << ";vulkanStatus=" << vulkanStatus
        << ";vulkanFailureReason=" << vulkanFailureReason
        << ";vulkanInputPackingMs=" << vulkanInputPackingMs
        << ";vulkanTensorUploadMs=" << vulkanTensorUploadMs
        << ";vulkanPass1KernelMs=" << vulkanPass1KernelMs
        << ";vulkanTileStatisticsKernelMs=" << vulkanTileStatisticsKernelMs
        << ";vulkanNoRegretDecisionMs=" << vulkanNoRegretDecisionMs
        << ";vulkanNoRegretBlendMs=" << vulkanNoRegretBlendMs
        << ";vulkanGpuKernelMs=" << vulkanGpuKernelMs
        << ";vulkanSynchronizationMs=" << vulkanSynchronizationMs
        << ";vulkanReadbackMs=" << vulkanReadbackMs
        << ";vulkanTransferAndSyncMs=" << vulkanTransferAndSyncMs
        << ";vulkanTotalMs=" << vulkanTotalMs
        << ";vulkanResidentBytes=" << vulkanResidentBytes
        << ";vulkanAllocationGeneration=" << vulkanAllocationGeneration
        << "}";
    return out.str();
}

SpectraPass1State computePass1StateForInputAvailability(
        bool inputAvailable,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    SpectraPass1State state{};
    state.spectraMode = meta.calibration.spectraProcessingMode;
    state.anisotropicDetail.enabled = state.spectraMode != 0;
    state.anisotropicDetail.status = state.spectraMode == 0
            ? "SPECTRA_OFF"
            : "WAITING_FOR_PASS1_ELIGIBILITY";

    if (state.spectraMode == 0) {
        state.fallbackReason = "legacy_mode";
        state.anisotropicDetail.status = "SPECTRA_OFF";
        return state;
    }

    if (!inputAvailable) {
        state.fallbackReason = "mosaic_empty";
        state.anisotropicDetail.status = "PASS1_NOT_ELIGIBLE_MOSAIC_EMPTY";
        return state;
    }

    if (!meta.calibration.spectraSnapshotPresent) {
        state.fallbackReason = "snapshot_not_present_or_invalid";
        state.anisotropicDetail.status = "PASS1_NO_CAPTURE_NOISE_SNAPSHOT";
        return state;
    }

    for (int ch = 0; ch < 4; ++ch) {
        state.effectiveS[ch] = meta.calibration.effectiveS[ch];
        state.effectiveO[ch] = meta.calibration.effectiveO[ch];
    }

    bool allChannelsValid = true;
    for (int ch = 0; ch < 4; ++ch) {
        if (!isVstValid(state.effectiveS[ch], state.effectiveO[ch])) {
            allChannelsValid = false;
            break;
        }
    }

    if (!allChannelsValid) {
        state.fallbackReason = "invalid_or_zero_so_parameters";
        state.anisotropicDetail.status = "PASS1_INVALID_SO_MODEL";
        return state;
    }

    state.modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);
    if (state.modelConfidence < 0.25f) {
        state.fallbackReason = "low_signal_model_confidence";
        state.anisotropicDetail.status = "PASS1_MODEL_CONFIDENCE_TOO_LOW";
        return state;
    }

    const SpectraIsoAdaptiveState isoState = IspCore::resolveSpectraIsoAdaptiveState(meta, uiConfig);
    state.isoAuthority = isoState.lumaAuthority;
    state.combinedNoisePressure = isoState.combinedNoisePressure;
    // ISO changes authority continuously, while the real S/O model still defines
    // the per-pixel sigma. Low ISO therefore preserves micro-detail; high ISO
    // unlocks stronger high-frequency shrinkage without a hard threshold jump.
    // Context Fusion separates stochastic CFA variation from coherent structure
    // in noise-sigma units. Authority can therefore follow the physical noise
    // pressure more directly than the old globally conservative blend. Profile
    // luma/strength still modulate isoState.lumaAuthority and remain authoritative.
    const float pressureAuthority = 0.22f + 1.35f * isoState.combinedNoisePressure;
    const float confidenceAuthority = 0.72f + 0.28f * state.modelConfidence;
    const float profileAuthority = std::sqrt(std::clamp(isoState.lumaAuthority, 0.08f, 1.65f));
    state.blendStrength = std::clamp(
            pressureAuthority * confidenceAuthority * profileAuthority,
            0.08f,
            1.18f
    );
    state.maxPixelShift = 0.85f + 1.85f * isoState.combinedNoisePressure;
    state.anisotropicDetail.status = "MILESTONE_5_PLAN_READY";
    state.applied = true;
    return state;
}

SpectraPass1State IspCore::computePass1State(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    return computePass1StateForInputAvailability(
            !raw.mosaic.empty() && raw.mosaic.type() == CV_32FC1, meta, uiConfig);
}

SpectraPass1State IspCore::computePass1StateCompact(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    return computePass1StateForInputAvailability(
            raw.valid && raw.info.width > 0 && raw.info.height > 0, meta, uiConfig);
}

void IspCore::applySpectraPass1(
        LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        SpectraPass1State& pass1State
) {
    if (!pass1State.applied || (pass1State.spectraMode == 0 && !pass1State.physicalBaselineMode)) return;
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) return;

    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    if (width < 17 || height < 17) return;
    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);

    // Milestone 5 retains the proven VST Wiener estimator, but replaces its
    // direction-blind support at credible edges with a continuously interpolated
    // green/luma structure tensor. Low-confidence tensor locations execute the
    // established isotropic path exactly, providing a safe per-pixel fallback.
    const cv::Mat source = raw.mosaic.clone();
    cv::Mat destination = source.clone();

    const double greenS = 0.5 * (
            pass1State.effectiveS[1] + pass1State.effectiveS[2]
    );
    const double greenO = 0.5 * (
            pass1State.effectiveO[1] + pass1State.effectiveO[2]
    );
    const auto tensorBuildStart = IspClock::now();
    const SpectraStructureTensorField tensorField = buildSpectraStructureTensorField(
            source,
            cfaPattern,
            greenS,
            greenO
    );
    pass1State.anisotropicDetail.tensorFieldBuildMs = elapsedMs(tensorBuildStart);
    pass1State.anisotropicDetail.enabled = true;

    struct RuntimeStats {
        double localVarianceSum = 0.0;
        double wienerGainSum = 0.0;
        double shadingAuthoritySum = 0.0;
        double tensorConfidenceSum = 0.0;
        double tensorCoherenceSum = 0.0;
        double directionalWeightSum = 0.0;
        double isotropicAuthorityScaleSum = 0.0;
        double finalAuthorityScaleSum = 0.0;
        std::uint64_t evaluated = 0;
        std::uint64_t changed = 0;
        std::uint64_t edgeProtected = 0;
        std::uint64_t validTensor = 0;
        std::uint64_t confidentTensor = 0;
        std::uint64_t fallback = 0;
        std::uint64_t directionalChanged = 0;
        std::uint64_t directionalWeightSamples = 0;
        std::uint64_t crossEdgeProtectedSamples = 0;
        std::uint64_t alongStructureSupportedSamples = 0;
        std::uint64_t contextFlatPixels = 0;
        std::uint64_t contextStructureProtectedPixels = 0;
        std::uint64_t contextBoostedPixels = 0;
        std::array<std::uint64_t, 4> orientationHistogram{0, 0, 0, 0};
        std::array<std::uint64_t, 32> confidenceHistogram{};
        std::array<std::uint64_t, 32> coherenceHistogram{};
        float maximumLinearCorrection = 0.0f;

        void addHistogram(
                std::array<std::uint64_t, 32>& histogram,
                float value
        ) {
            const int bin = std::clamp(
                    static_cast<int>(std::floor(
                            bncam::spectra2::anisotropicFiniteUnit(value) * 32.0f
                    )),
                    0,
                    31
            );
            histogram[static_cast<size_t>(bin)]++;
        }

        void merge(const RuntimeStats& other) {
            localVarianceSum += other.localVarianceSum;
            wienerGainSum += other.wienerGainSum;
            shadingAuthoritySum += other.shadingAuthoritySum;
            tensorConfidenceSum += other.tensorConfidenceSum;
            tensorCoherenceSum += other.tensorCoherenceSum;
            directionalWeightSum += other.directionalWeightSum;
            isotropicAuthorityScaleSum += other.isotropicAuthorityScaleSum;
            finalAuthorityScaleSum += other.finalAuthorityScaleSum;
            evaluated += other.evaluated;
            changed += other.changed;
            edgeProtected += other.edgeProtected;
            validTensor += other.validTensor;
            confidentTensor += other.confidentTensor;
            fallback += other.fallback;
            directionalChanged += other.directionalChanged;
            directionalWeightSamples += other.directionalWeightSamples;
            crossEdgeProtectedSamples += other.crossEdgeProtectedSamples;
            alongStructureSupportedSamples += other.alongStructureSupportedSamples;
            contextFlatPixels += other.contextFlatPixels;
            contextStructureProtectedPixels += other.contextStructureProtectedPixels;
            contextBoostedPixels += other.contextBoostedPixels;
            maximumLinearCorrection = std::max(
                    maximumLinearCorrection,
                    other.maximumLinearCorrection
            );
            for (size_t i = 0; i < orientationHistogram.size(); ++i) {
                orientationHistogram[i] += other.orientationHistogram[i];
            }
            for (size_t i = 0; i < confidenceHistogram.size(); ++i) {
                confidenceHistogram[i] += other.confidenceHistogram[i];
                coherenceHistogram[i] += other.coherenceHistogram[i];
            }
        }
    };

    RuntimeStats total{};
    std::mutex statsMutex;
    const auto directionalFilterStart = IspClock::now();
    cv::parallel_for_(cv::Range(8, height - 8), [&](const cv::Range& range) {
        RuntimeStats local{};
        constexpr int sampleDx[9] = {0, -2, 0, 2, -2, 2, -2, 0, 2};
        constexpr int sampleDy[9] = {0, -2, -2, -2, 0, 0, 2, 2, 2};

        for (int y = range.start; y < range.end; ++y) {
            float* outRow = destination.ptr<float>(y);
            const float* rowCurr = source.ptr<float>(y);
            const float* rowM2 = source.ptr<float>(y - 2);
            const float* rowP2 = source.ptr<float>(y + 2);
            const float* rowM4 = source.ptr<float>(y - 4);
            const float* rowP4 = source.ptr<float>(y + 4);

            for (int x = 8; x < width - 8; ++x) {
                const int ch = cfaColorChannel(cfaPattern, x, y);
                if (ch < 0 || ch >= 4) continue;
                const double S = pass1State.effectiveS[ch];
                const double O = pass1State.effectiveO[ch];
                if (!isVstValid(S, O)) continue;

                const float samples[9] = {
                    rowCurr[x],
                    rowM2[x - 2], rowM2[x], rowM2[x + 2],
                    rowCurr[x - 2], rowCurr[x + 2],
                    rowP2[x - 2], rowP2[x], rowP2[x + 2]
                };
                bool validDomain = true;
                for (float sample : samples) {
                    if (!std::isfinite(sample) || !isVstValidDomain(sample, S, O)) {
                        validDomain = false;
                        break;
                    }
                }
                if (!validDomain) continue;

                std::array<float, 9> vst{};
                for (int i = 0; i < 9; ++i) {
                    vst[static_cast<size_t>(i)] = spectraForwardVst(samples[i], S, O);
                }
                const float center = vst[0];
                std::array<float, 9> sorted = vst;
                std::sort(sorted.begin(), sorted.end());
                const float median = sorted[4];
                std::array<float, 9> madSamples{};
                for (size_t i = 0; i < vst.size(); ++i) {
                    madSamples[i] = std::abs(vst[i] - median);
                }
                std::sort(madSamples.begin(), madSamples.end());
                constexpr float kExpectedNineSampleGaussianMad = 0.58601043f;
                const float normalizedMad = madSamples[4] / kExpectedNineSampleGaussianMad;

                const auto tensor = interpolateSpectraStructureTensor(tensorField, x, y);
                if (tensor.valid) {
                    local.validTensor++;
                    local.tensorConfidenceSum += tensor.confidence;
                    local.tensorCoherenceSum += tensor.coherence;
                    local.addHistogram(local.confidenceHistogram, tensor.confidence);
                    local.addHistogram(local.coherenceHistogram, tensor.coherence);
                }
                if (tensor.confident) {
                    local.confidentTensor++;
                    local.orientationHistogram[static_cast<size_t>(
                            bncam::spectra2::orientationBin(tensor.tangentDegrees)
                    )]++;
                } else {
                    local.fallback++;
                }

                const float centerGreen = spectraGreenGuideAt(source, cfaPattern, x, y);
                const float greenSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, centerGreen) + greenO
                )));

                constexpr float kSqrt2 = 1.4142135623730951f;
                const float sameCfaHorizontalZ = std::abs(vst[4] - vst[5]) / kSqrt2;
                const float sameCfaVerticalZ = std::abs(vst[2] - vst[7]) / kSqrt2;
                const float sameCfaStructureZ = std::max(sameCfaHorizontalZ, sameCfaVerticalZ);
                const float greenLeft = spectraGreenGuideAt(source, cfaPattern, x - 2, y);
                const float greenRight = spectraGreenGuideAt(source, cfaPattern, x + 2, y);
                const float greenUp = spectraGreenGuideAt(source, cfaPattern, x, y - 2);
                const float greenDown = spectraGreenGuideAt(source, cfaPattern, x, y + 2);
                const float greenHorizontalSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, greenLeft) + greenO +
                        greenS * std::max(0.0f, greenRight) + greenO)));
                const float greenVerticalSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, greenUp) + greenO +
                        greenS * std::max(0.0f, greenDown) + greenO)));
                const float greenStructureZ = std::max(
                        std::abs(greenLeft - greenRight) / std::max(1.0e-6f, greenHorizontalSigma),
                        std::abs(greenUp - greenDown) / std::max(1.0e-6f, greenVerticalSigma));
                const float contextStructureZ = std::max(sameCfaStructureZ, 0.85f * greenStructureZ);

                // Context Pyramid: a real scene edge normally persists at both the
                // native CFA spacing and a broader same-CFA/green scaffold. Fine-only
                // energy is much more likely to be stochastic sensor noise. This is
                // the analytic SPECTRA equivalent of coarse-to-fine CFA conditioning.
                float coarseSameCfaStructureZ = 0.0f;
                const float coarseRawLeft = rowCurr[x - 4];
                const float coarseRawRight = rowCurr[x + 4];
                const float coarseRawUp = rowM4[x];
                const float coarseRawDown = rowP4[x];
                if (isVstValidDomain(coarseRawLeft, S, O) &&
                    isVstValidDomain(coarseRawRight, S, O) &&
                    isVstValidDomain(coarseRawUp, S, O) &&
                    isVstValidDomain(coarseRawDown, S, O)) {
                    coarseSameCfaStructureZ = std::max(
                            std::abs(spectraForwardVst(coarseRawLeft, S, O) -
                                     spectraForwardVst(coarseRawRight, S, O)) / kSqrt2,
                            std::abs(spectraForwardVst(coarseRawUp, S, O) -
                                     spectraForwardVst(coarseRawDown, S, O)) / kSqrt2);
                }
                const float coarseGreenLeft = spectraGreenGuideAt(source, cfaPattern, x - 4, y);
                const float coarseGreenRight = spectraGreenGuideAt(source, cfaPattern, x + 4, y);
                const float coarseGreenUp = spectraGreenGuideAt(source, cfaPattern, x, y - 4);
                const float coarseGreenDown = spectraGreenGuideAt(source, cfaPattern, x, y + 4);
                const float coarseGreenHorizontalSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, coarseGreenLeft) + greenO +
                        greenS * std::max(0.0f, coarseGreenRight) + greenO)));
                const float coarseGreenVerticalSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        greenS * std::max(0.0f, coarseGreenUp) + greenO +
                        greenS * std::max(0.0f, coarseGreenDown) + greenO)));
                const float coarseGreenStructureZ = std::max(
                        std::abs(coarseGreenLeft - coarseGreenRight) /
                                std::max(1.0e-6f, coarseGreenHorizontalSigma),
                        std::abs(coarseGreenUp - coarseGreenDown) /
                                std::max(1.0e-6f, coarseGreenVerticalSigma));
                const float coarseStructureZ = std::max(
                        coarseSameCfaStructureZ, 0.85f * coarseGreenStructureZ);
                const auto multiscaleContext = bncam::spectra2::resolveMultiscaleContext(
                        contextStructureZ,
                        coarseStructureZ,
                        pass1State.combinedNoisePressure,
                        pass1State.modelConfidence);
                const auto surfaceClass = bncam::spectra2::resolveCfaSurfaceClassification(
                        normalizedMad,
                        multiscaleContext.coherentStructureZ,
                        tensor.confidence,
                        pass1State.combinedNoisePressure,
                        pass1State.modelConfidence);
                const float flatContext = std::clamp(
                        0.62f * multiscaleContext.flatContext +
                        0.38f * surfaceClass.flatConfidence -
                        0.45f * surfaceClass.textureConfidence,
                        0.0f, 1.0f);

                // Robust directional neighbourhood mean. With low tensor
                // confidence every directional weight is exactly one, preserving
                // the previous isotropic estimator.
                double weightedSum = 0.0;
                double weightSum = 0.0;
                for (int i = 1; i < 9; ++i) {
                    const float distance = vst[static_cast<size_t>(i)] - median;
                    const float robustWeight = 1.0f /
                            (1.0f + 0.25f * distance * distance);
                    const float neighbourGreen = spectraGreenGuideAt(
                            source,
                            cfaPattern,
                            x + sampleDx[i],
                            y + sampleDy[i]
                    );
                    const float guideDeltaSigma = (neighbourGreen - centerGreen) /
                            std::max(1.0e-6f, greenSigma);
                    const auto directional =
                            bncam::spectra2::resolveDirectionalSampleWeight(
                                    tensor,
                                    static_cast<float>(sampleDx[i]),
                                    static_cast<float>(sampleDy[i]),
                                    guideDeltaSigma
                            );
                    const float weight = robustWeight * directional.weight;
                    weightedSum += static_cast<double>(
                            weight * vst[static_cast<size_t>(i)]
                    );
                    weightSum += weight;
                    if (tensor.confident) {
                        local.directionalWeightSum += directional.weight;
                        local.directionalWeightSamples++;
                        if (directional.crossEdgeProtected) {
                            local.crossEdgeProtectedSamples++;
                        }
                        if (directional.alongStructureSupported) {
                            local.alongStructureSupportedSamples++;
                        }
                    }
                }
                if (weightSum <= 1.0e-6) continue;
                const float localMean = static_cast<float>(weightedSum / weightSum);

                double varianceSum = 0.0;
                for (int i = 1; i < 9; ++i) {
                    const double delta = static_cast<double>(
                            vst[static_cast<size_t>(i)] - localMean
                    );
                    varianceSum += delta * delta;
                }
                const float localVariance = static_cast<float>(varianceSum / 7.0);
                const float signalVariance = std::max(0.0f, localVariance - 1.0f);
                const float wienerGain = signalVariance / (signalVariance + 1.0f);
                float target = localMean + wienerGain * (center - localMean);

                const float impulseScore = std::abs(center - median);
                if (impulseScore > 3.0f) {
                    const float impulseAuthority = smoothstepIsp(3.0f, 6.0f, impulseScore);
                    target += impulseAuthority * (median - target);
                }

                // SPECTRA Multiscale Residual Consensus. The primary Vulkan
                // path runs the same rule: two wider same-CFA contexts must agree
                // before they may steer the fine Wiener estimate. This CPU code is
                // only the explicit fail-safe/reference path.
                auto ringEstimate = [&](int radius, float& ringTarget, float& structureZ) {
                    constexpr int ringDx[8] = {-1, 1, 0, 0, -1, 1, 1, -1};
                    constexpr int ringDy[8] = {0, 0, -1, 1, -1, 1, -1, 1};
                    std::array<float, 8> ring{};
                    for (int i = 0; i < 8; ++i) {
                        const int sx = x + ringDx[i] * radius;
                        const int sy = y + ringDy[i] * radius;
                        const float rawValue = source.ptr<float>(sy)[sx];
                        if (!std::isfinite(rawValue) || !isVstValidDomain(rawValue, S, O)) {
                            return false;
                        }
                        ring[static_cast<size_t>(i)] = spectraForwardVst(rawValue, S, O);
                    }
                    std::array<float, 8> sortedRing = ring;
                    std::sort(sortedRing.begin(), sortedRing.end());
                    const float ringMedian = 0.5f * (sortedRing[3] + sortedRing[4]);
                    double ringSum = 0.0;
                    double ringWeightSum = 0.0;
                    for (float value : ring) {
                        const float delta = value - ringMedian;
                        const float weight = 1.0f / (1.0f + 0.20f * delta * delta);
                        ringSum += static_cast<double>(weight * value);
                        ringWeightSum += weight;
                    }
                    if (ringWeightSum <= 1.0e-6) return false;
                    ringTarget = static_cast<float>(ringSum / ringWeightSum);
                    structureZ = std::max(
                            std::max(std::abs(ring[0] - ring[1]),
                                     std::abs(ring[2] - ring[3])),
                            std::max(std::abs(ring[4] - ring[5]),
                                     std::abs(ring[6] - ring[7]))) / kSqrt2;
                    return std::isfinite(ringTarget) && std::isfinite(structureZ);
                };

                float midTarget = 0.0f;
                float midStructureZ = 8.0f;
                float coarseTarget = 0.0f;
                float coarseRingStructureZ = 8.0f;
                if (ringEstimate(4, midTarget, midStructureZ) &&
                    ringEstimate(8, coarseTarget, coarseRingStructureZ)) {
                    const float persistentStructureZ = std::max(
                            multiscaleContext.coherentStructureZ,
                            0.75f * std::min(midStructureZ, coarseRingStructureZ));
                    const auto residualConsensus =
                            bncam::spectra2::resolveMultiscaleResidualConsensus(
                                    std::abs(center - target),
                                    std::abs(midTarget - coarseTarget),
                                    persistentStructureZ,
                                    flatContext,
                                    pass1State.combinedNoisePressure,
                                    pass1State.modelConfidence);
                    const float contextualTarget =
                            residualConsensus.midWeight * midTarget +
                            residualConsensus.coarseWeight * coarseTarget;
                    const float surfaceContextMix = std::clamp(
                            residualConsensus.contextMix * surfaceClass.contextMixScale,
                            0.0f, 0.62f);
                    target += surfaceContextMix * (contextualTarget - target);
                }

                const float edgeProtection = multiscaleContext.edgeProtection;
                const auto authorityDecision =
                        bncam::spectra2::resolveAnisotropicAuthority(
                                tensor,
                                edgeProtection
                        );
                const float lensGain = lensShadingGainAt(
                        meta,
                        ch,
                        x,
                        y,
                        width,
                        height
                );
                const float shadingAuthority = std::clamp(
                        0.90f + 0.20f * (lensGain - 1.0f),
                        0.90f,
                        1.38f
                );
                const float isolatedNoiseEvidence = smoothstepIsp(
                        0.55f, 2.75f, impulseScore) * flatContext;
                const float contextBoost = 0.90f + (1.34f - 0.90f) *
                        flatContext * (0.70f + 0.30f * isolatedNoiseEvidence);
                const float authority = std::min(
                        1.35f,
                        pass1State.blendStrength * shadingAuthority *
                                authorityDecision.finalScale * contextBoost *
                                multiscaleContext.denoiseAuthorityScale *
                                surfaceClass.authorityScale);
                if (edgeProtection > 0.70f ||
                    (tensor.confident && authorityDecision.finalScale < 0.45f)) {
                    local.edgeProtected++;
                }
                if (flatContext > 0.70f) local.contextFlatPixels++;
                if (edgeProtection > 0.70f) local.contextStructureProtectedPixels++;
                if (contextBoost > 1.15f) local.contextBoostedPixels++;

                const float boundedVstDelta = std::clamp(
                        (target - center) * authority,
                        -pass1State.maxPixelShift,
                        pass1State.maxPixelShift
                );
                const float candidate = spectraInverseVst(
                        center + boundedVstDelta,
                        S,
                        O
                );
                if (!std::isfinite(candidate)) continue;

                const float localSigma = static_cast<float>(std::sqrt(std::max(
                        1.0e-12,
                        S * std::max(0.0f, samples[0]) + O
                )));
                const float edgeShiftGuard = tensor.confident
                        ? std::clamp(0.82f + 0.18f * tensor.confidence, 0.82f, 1.0f)
                        : 1.0f;
                const float contextShiftGuard = 1.12f + (0.78f - 1.12f) * edgeProtection;
                const float maxLinearShift = std::clamp(
                        (1.25f + 1.55f * pass1State.isoAuthority) *
                                localSigma * shadingAuthority * edgeShiftGuard * contextShiftGuard,
                        1.0e-5f,
                        0.018f
                );
                const float appliedDelta = std::clamp(
                        candidate - samples[0],
                        -maxLinearShift,
                        maxLinearShift
                );
                outRow[x] = samples[0] + appliedDelta;

                local.localVarianceSum += localVariance;
                local.wienerGainSum += wienerGain;
                local.shadingAuthoritySum += shadingAuthority;
                local.isotropicAuthorityScaleSum += authorityDecision.isotropicScale;
                local.finalAuthorityScaleSum += authorityDecision.finalScale;
                local.evaluated++;
                if (std::abs(appliedDelta) > 1.0e-7f) {
                    local.changed++;
                    if (tensor.confident) local.directionalChanged++;
                }
                local.maximumLinearCorrection = std::max(
                        local.maximumLinearCorrection,
                        std::abs(appliedDelta)
                );
            }
        }

        std::lock_guard<std::mutex> lock(statsMutex);
        total.merge(local);
    });
    pass1State.anisotropicDetail.directionalFilterMs = elapsedMs(
            directionalFilterStart
    );

    raw.mosaic = std::move(destination);
    if (total.evaluated > 0) {
        const double inv = 1.0 / static_cast<double>(total.evaluated);
        pass1State.averageVstResidualVar = static_cast<float>(
                total.localVarianceSum * inv
        );
        pass1State.averageWienerGain = static_cast<float>(
                total.wienerGainSum * inv
        );
        pass1State.localShadingAuthorityMean = static_cast<float>(
                total.shadingAuthoritySum * inv
        );
        pass1State.changedPixelFraction = static_cast<float>(total.changed) /
                static_cast<float>(total.evaluated);
        pass1State.edgeProtectedFraction = static_cast<float>(total.edgeProtected) /
                static_cast<float>(total.evaluated);
    }

    auto& telemetry = pass1State.anisotropicDetail;
    telemetry.evaluatedPixelCount = total.evaluated;
    telemetry.validTensorPixelCount = total.validTensor;
    telemetry.confidentTensorPixelCount = total.confidentTensor;
    telemetry.fallbackPixelCount = total.fallback;
    telemetry.directionalChangedPixelCount = total.directionalChanged;
    telemetry.crossEdgeProtectedSampleCount = total.crossEdgeProtectedSamples;
    telemetry.alongStructureSupportedSampleCount = total.alongStructureSupportedSamples;
    telemetry.contextFlatPixelCount = total.contextFlatPixels;
    telemetry.contextStructureProtectedPixelCount = total.contextStructureProtectedPixels;
    telemetry.contextBoostedPixelCount = total.contextBoostedPixels;
    telemetry.orientationHistogram = total.orientationHistogram;
    telemetry.maximumLinearCorrection = total.maximumLinearCorrection;
    if (total.evaluated > 0) {
        const float invEvaluated = 1.0f / static_cast<float>(total.evaluated);
        telemetry.validTensorFraction = static_cast<float>(total.validTensor) * invEvaluated;
        telemetry.confidentTensorFraction = static_cast<float>(total.confidentTensor) * invEvaluated;
        telemetry.fallbackFraction = static_cast<float>(total.fallback) * invEvaluated;
        telemetry.directionalChangedFraction = static_cast<float>(total.directionalChanged) *
                invEvaluated;
        telemetry.contextFlatFraction = static_cast<float>(total.contextFlatPixels) * invEvaluated;
        telemetry.contextStructureProtectedFraction = static_cast<float>(total.contextStructureProtectedPixels) * invEvaluated;
        telemetry.contextBoostedFraction = static_cast<float>(total.contextBoostedPixels) * invEvaluated;
        telemetry.meanIsotropicAuthorityScale = static_cast<float>(
                total.isotropicAuthorityScaleSum / static_cast<double>(total.evaluated)
        );
        telemetry.meanDirectionalAuthorityScale = static_cast<float>(
                total.finalAuthorityScaleSum / static_cast<double>(total.evaluated)
        );
    }
    if (total.validTensor > 0) {
        telemetry.meanConfidence = static_cast<float>(
                total.tensorConfidenceSum / static_cast<double>(total.validTensor)
        );
        telemetry.meanCoherence = static_cast<float>(
                total.tensorCoherenceSum / static_cast<double>(total.validTensor)
        );
        telemetry.confidenceP10 = histogramPercentile(
                total.confidenceHistogram, total.validTensor, 0.10f
        );
        telemetry.confidenceP50 = histogramPercentile(
                total.confidenceHistogram, total.validTensor, 0.50f
        );
        telemetry.confidenceP90 = histogramPercentile(
                total.confidenceHistogram, total.validTensor, 0.90f
        );
        telemetry.coherenceP10 = histogramPercentile(
                total.coherenceHistogram, total.validTensor, 0.10f
        );
        telemetry.coherenceP50 = histogramPercentile(
                total.coherenceHistogram, total.validTensor, 0.50f
        );
        telemetry.coherenceP90 = histogramPercentile(
                total.coherenceHistogram, total.validTensor, 0.90f
        );
    }
    if (total.directionalWeightSamples > 0) {
        telemetry.meanDirectionalWeight = static_cast<float>(
                total.directionalWeightSum /
                        static_cast<double>(total.directionalWeightSamples)
        );
    }
    telemetry.applied = total.changed > 0;
    telemetry.status = !tensorField.valid
            ? "CONTEXT_FUSION_APPLIED_WITHOUT_TENSOR_FIELD"
            : (telemetry.applied
                    ? "SPECTRA_CONTEXT_FUSION_APPLIED"
                    : "SPECTRA_CONTEXT_FUSION_NO_PIXEL_CHANGE");
}

std::uint64_t spectraLensMapGenerationId(const IspFrameMetadata& meta) {
    std::uint64_t hash = 1469598103934665603ull;
    for (float value : meta.lensShadingMap) {
        std::uint32_t bits = 0u;
        std::memcpy(&bits, &value, sizeof(bits));
        hash ^= bits;
        hash *= 1099511628211ull;
    }
    return hash;
}

std::string SpectraPass2State::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(4);
    out << "spectraCfaBandObserver={"
        << "mode=" << (spectraMode == 1 ? "auto" : (spectraMode == 2 ? "manual" : "off"))
        << ";status=" << observerStatus
        << ";modelConfidence=" << modelConfidence
        << ";bandEnergyStatus=" << bandEnergyStatus
        << ";bandEnergyMethod=" << bandEnergyMethod
        << ";sampleCount=" << bandEnergySampleCount
        << ";redSampleCount=" << bandEnergyRedSampleCount
        << ";blueSampleCount=" << bandEnergyBlueSampleCount
        << ";redBlueSampleBalance=" << bandEnergyRedBlueSampleBalance
        << ";bandEnergyConfidence=" << bandEnergyConfidence
        << ";measurementTimeMs=" << bandEnergyMeasurementMs
        << ";processingTimeMs=" << processingTimeMs
        << ";pixelAuthority=false"
        << "}";
    return out.str();
}



bncam::spectra2::ChromaBandEnergySnapshot measureSpectraChromaBandEnergies(
        const LinearFloatRaw& raw
) {
    bncam::spectra2::ChromaBandEnergySnapshot snapshot{};
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) {
        snapshot.status = "MOSAIC_UNAVAILABLE";
        return snapshot;
    }
    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    if (width < 21 || height < 21) {
        snapshot.status = "MOSAIC_TOO_SMALL";
        return snapshot;
    }

    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    auto greenReference = [&](int x, int y) -> float {
        const float* rowM1 = raw.mosaic.ptr<float>(y - 1);
        const float* rowP1 = raw.mosaic.ptr<float>(y + 1);
        const float* row = raw.mosaic.ptr<float>(y);
        return 0.25f * (rowM1[x] + rowP1[x] + row[x - 1] + row[x + 1]);
    };
    auto residual = [&](int x, int y) -> float {
        return raw.mosaic.ptr<float>(y)[x] - greenReference(x, y);
    };
    auto crossMean = [&](int x, int y, int radius) -> float {
        return 0.25f * (
                residual(x - radius, y) + residual(x + radius, y) +
                residual(x, y - radius) + residual(x, y + radius)
        );
    };

    double fineSum = 0.0;
    double midSum = 0.0;
    double lowSum = 0.0;
    double redFineSum = 0.0;
    double redMidSum = 0.0;
    double redLowSum = 0.0;
    double blueFineSum = 0.0;
    double blueMidSum = 0.0;
    double blueLowSum = 0.0;
    double rowSum = 0.0;
    double columnSum = 0.0;
    std::uint64_t samples = 0;
    std::uint64_t redSamples = 0;
    std::uint64_t blueSamples = 0;

    // Fixed sampling stride keeps observability bounded and deterministic. Each
    // sampled 2x2 CFA phase block is inspected so both red and blue sites are
    // represented for every supported Bayer pattern. The decomposition remains
    // a proxy: radius 2 captures local speckle, radius 4 the surviving clustered
    // component and radius 8 the broad residual component.
    constexpr int kSamplingStride = 16;
    for (int baseY = 9; baseY < height - 10; baseY += kSamplingStride) {
        for (int baseX = 9; baseX < width - 10; baseX += kSamplingStride) {
            for (int phaseY = 0; phaseY < 2; ++phaseY) {
                for (int phaseX = 0; phaseX < 2; ++phaseX) {
                    const int x = baseX + phaseX;
                    const int y = baseY + phaseY;
                    const int channel = cfaColorChannel(cfaPattern, x, y);
                    if (channel != 0 && channel != 3) continue;
                    const float centerGreen = greenReference(x, y);
                    const float horizontalStructure = std::abs(
                            greenReference(x + 2, y) - greenReference(x - 2, y)
                    );
                    const float verticalStructure = std::abs(
                            greenReference(x, y + 2) - greenReference(x, y - 2)
                    );
                    const float normalizedStructure =
                            (horizontalStructure + verticalStructure) /
                            std::max(0.02f, std::abs(centerGreen));
                    if (!std::isfinite(centerGreen) || !std::isfinite(normalizedStructure) ||
                        normalizedStructure > 0.28f) {
                        continue;
                    }
                    const float center = residual(x, y);
                    const float local = crossMean(x, y, 2);
                    const float middle = crossMean(x, y, 4);
                    const float broad = crossMean(x, y, 8);
                    const float rowBefore = residual(x, y - 8);
                    const float rowAfter = residual(x, y + 8);
                    const float colBefore = residual(x - 8, y);
                    const float colAfter = residual(x + 8, y);
                    if (!std::isfinite(center) || !std::isfinite(local) ||
                        !std::isfinite(middle) || !std::isfinite(broad) ||
                        !std::isfinite(rowBefore) || !std::isfinite(rowAfter) ||
                        !std::isfinite(colBefore) || !std::isfinite(colAfter)) {
                        continue;
                    }
                    const double fine = static_cast<double>(center - local);
                    const double mid = static_cast<double>(local - middle);
                    const double low = static_cast<double>(middle - broad);
                    const double rowPattern = 0.5 * static_cast<double>(rowBefore - rowAfter);
                    const double columnPattern = 0.5 * static_cast<double>(colBefore - colAfter);
                    const double fineSq = fine * fine;
                    const double midSq = mid * mid;
                    const double lowSq = low * low;
                    fineSum += fineSq;
                    midSum += midSq;
                    lowSum += lowSq;
                    rowSum += rowPattern * rowPattern;
                    columnSum += columnPattern * columnPattern;
                    ++samples;
                    if (channel == 0) {
                        ++redSamples;
                        redFineSum += fineSq;
                        redMidSum += midSq;
                        redLowSum += lowSq;
                    } else {
                        ++blueSamples;
                        blueFineSum += fineSq;
                        blueMidSum += midSq;
                        blueLowSum += lowSq;
                    }
                }
            }
        }
    }

    snapshot.sampleCount = samples;
    snapshot.redSampleCount = redSamples;
    snapshot.blueSampleCount = blueSamples;
    const std::uint64_t maxColourSamples = std::max(redSamples, blueSamples);
    snapshot.redBlueSampleBalance = maxColourSamples > 0
            ? static_cast<float>(std::min(redSamples, blueSamples)) /
                    static_cast<float>(maxColourSamples)
            : 0.0f;
    if (samples == 0 || redSamples == 0 || blueSamples == 0) {
        snapshot.status = "NO_BALANCED_RB_SAMPLES";
        return snapshot;
    }
    const double inv = 1.0 / static_cast<double>(samples);
    snapshot.fineEnergy = static_cast<float>(fineSum * inv);
    snapshot.midEnergy = static_cast<float>(midSum * inv);
    snapshot.lowEnergy = static_cast<float>(lowSum * inv);
    const double redInv = 1.0 / static_cast<double>(redSamples);
    const double blueInv = 1.0 / static_cast<double>(blueSamples);
    snapshot.redFineEnergy = static_cast<float>(redFineSum * redInv);
    snapshot.redMidEnergy = static_cast<float>(redMidSum * redInv);
    snapshot.redLowEnergy = static_cast<float>(redLowSum * redInv);
    snapshot.blueFineEnergy = static_cast<float>(blueFineSum * blueInv);
    snapshot.blueMidEnergy = static_cast<float>(blueMidSum * blueInv);
    snapshot.blueLowEnergy = static_cast<float>(blueLowSum * blueInv);
    snapshot.rowPatternProxy = static_cast<float>(rowSum * inv);
    snapshot.columnPatternProxy = static_cast<float>(columnSum * inv);
    snapshot.confidence = std::clamp(
            static_cast<float>(samples) / 2048.0f,
            0.0f,
            1.0f
    ) * snapshot.redBlueSampleBalance;
    snapshot.status = snapshot.redBlueSampleBalance < 0.75f
            ? "UNBALANCED_RB_SUPPORT_PRE_DEMOSAIC_PROXY"
            : (samples >= 256
                    ? "AVAILABLE_SAMPLED_PRE_DEMOSAIC_PROXY"
                    : "LOW_SUPPORT_SAMPLED_PRE_DEMOSAIC_PROXY");
    return snapshot;
}

float spectraDirectionalEvidence(
        const bncam::spectra2::ChromaBandEnergySnapshot& snapshot,
        float predictedChromaFloor
) {
    const float reference = std::max(1.0e-12f, 0.16f * std::max(0.0f, predictedChromaFloor));
    const float directional = std::max(snapshot.rowPatternProxy, snapshot.columnPatternProxy);
    return std::clamp((directional - reference) / (4.0f * reference), 0.0f, 1.0f);
}

struct CfaBandResidualEvidence {
    bncam::spectra2::ChromaBandKind kind = bncam::spectra2::ChromaBandKind::Fine;
    std::string status = "UNAVAILABLE";
    float inputEnergy = 0.0f;
    float expectedNoiseFloor = 0.0f;
    float excessEnergy = 0.0f;
    float residualPressure = 0.0f;
    float modelConfidence = 0.0f;
    float directionalEvidence = 0.0f;
};

CfaBandResidualEvidence resolveCfaBandResidualEvidence(
        bncam::spectra2::ChromaBandKind kind,
        float inputEnergy,
        float predictedChromaFloor,
        float modelConfidence,
        float directionalEvidence = 0.0f
) {
    CfaBandResidualEvidence out{};
    out.kind = kind;
    out.inputEnergy = std::isfinite(inputEnergy) ? std::max(0.0f, inputEnergy) : 0.0f;
    out.modelConfidence = std::isfinite(modelConfidence)
            ? std::clamp(modelConfidence, 0.0f, 1.0f)
            : 0.0f;
    out.directionalEvidence = std::isfinite(directionalEvidence)
            ? std::clamp(directionalEvidence, 0.0f, 1.0f)
            : 0.0f;

    const float baseFloor = std::isfinite(predictedChromaFloor)
            ? std::max(0.0f, predictedChromaFloor)
            : 0.0f;
    const float floorScale = kind == bncam::spectra2::ChromaBandKind::Fine
            ? 1.00f
            : (kind == bncam::spectra2::ChromaBandKind::Mid ? 0.38f : 0.16f);
    out.expectedNoiseFloor = baseFloor * floorScale;
    out.excessEnergy = std::max(0.0f, out.inputEnergy - out.expectedNoiseFloor);
    out.residualPressure = out.inputEnergy > 1.0e-12f
            ? std::clamp(out.excessEnergy / out.inputEnergy, 0.0f, 1.0f)
            : 0.0f;

    if (out.inputEnergy <= 0.0f) {
        out.status = "NO_VALID_BAND_ENERGY";
    } else if (baseFloor <= 0.0f) {
        out.status = "NO_CALIBRATED_NOISE_FLOOR";
    } else if (out.modelConfidence < 0.15f) {
        out.status = "LOW_MODEL_CONFIDENCE";
    } else {
        out.status = "READY_EVIDENCE_ONLY_NO_PIXEL_AUTHORITY";
    }
    return out;
}

// Transitional compatibility transport for the existing CFA-confidence builder. Only
// observation fields are populated; all kernel/enable/authority/correction fields remain neutral.
bncam::spectra2::ChromaBandPlan makeCfaEvidenceTransport(
        const CfaBandResidualEvidence& evidence
) {
    bncam::spectra2::ChromaBandPlan out{};
    out.kind = evidence.kind;
    out.status = evidence.status;
    out.targetStatus = "RAW_DOMAIN_EXPECTED_NOISE_FLOOR_EVIDENCE";
    out.inputEnergy = evidence.inputEnergy;
    out.targetFloor = evidence.expectedNoiseFloor;
    out.excessEnergy = evidence.excessEnergy;
    out.requiredReductionFraction = evidence.residualPressure;
    out.modelConfidence = evidence.modelConfidence;
    out.evidence = evidence.directionalEvidence;
    return out;
}

std::string formatCfaBandResidualEvidenceFields(
        const char* prefix,
        const CfaBandResidualEvidence& evidence
) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "; " << prefix << "Name=" << bncam::spectra2::chromaBandName(evidence.kind)
        << "; " << prefix << "Status=" << evidence.status
        << "; " << prefix << "InputEnergy=" << evidence.inputEnergy
        << "; " << prefix << "ExpectedNoiseFloor=" << evidence.expectedNoiseFloor
        << "; " << prefix << "ExcessEnergy=" << evidence.excessEnergy
        << "; " << prefix << "ResidualPressure=" << evidence.residualPressure
        << "; " << prefix << "ModelConfidence=" << evidence.modelConfidence
        << "; " << prefix << "DirectionalEvidence=" << evidence.directionalEvidence
        << "; " << prefix << "PixelAuthority=false";
    return out.str();
}

SpectraPass2State computePass2StateForInputAvailability(
        bool inputAvailable,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    static_cast<void>(uiConfig);
    SpectraPass2State state{};
    state.spectraMode = meta.calibration.spectraProcessingMode;

    if (state.spectraMode == 0) {
        state.observerStatus = "SPECTRA_OFF_NOT_OBSERVED";
        return state;
    }
    if (!inputAvailable) {
        state.observerStatus = "RAW_INPUT_UNAVAILABLE";
        return state;
    }
    if (!meta.calibration.spectraSnapshotPresent) {
        state.observerStatus = "NO_VALID_SPECTRA_SNAPSHOT";
        return state;
    }

    for (int ch = 0; ch < 4; ++ch) {
        if (!isVstValid(meta.calibration.effectiveS[ch], meta.calibration.effectiveO[ch])) {
            state.observerStatus = "INVALID_OR_ZERO_SO_PARAMETERS";
            return state;
        }
    }

    state.modelConfidence = std::clamp(meta.calibration.signalModelConfidence, 0.0f, 1.0f);
    if (state.modelConfidence < 0.25f) {
        state.observerStatus = "LOW_SIGNAL_MODEL_CONFIDENCE";
        return state;
    }

    state.observerStatus = "READY_READ_ONLY_CFA_BAND_OBSERVER";
    return state;
}

SpectraPass2State IspCore::computePass2State(
        const LinearFloatRaw& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    return computePass2StateForInputAvailability(
            !raw.mosaic.empty() && raw.mosaic.type() == CV_32FC1, meta, uiConfig);
}

SpectraPass2State IspCore::computePass2StateCompact(
        const RawNormalizedSampleView& raw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    return computePass2StateForInputAvailability(
            raw.valid && raw.info.width > 0 && raw.info.height > 0, meta, uiConfig);
}

// N006O: retired Pass-2 / Pass-3 correction implementations remain physically absent.
// This layer now contributes read-only CFA noise/structure evidence only.

float IspCore::computeResidualEnergy(const LinearFloatRaw& raw) {
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) return 0.0f;
    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;

    double sumSqDiff = 0.0;
    size_t sampleCount = 0;

    // Sample all four CFA phases. The previous x/y += 4 loop sampled only one
    // Bayer channel and could not be compared with an all-channel S/O target.
    for (int y = 2; y < height - 3; y += 4) {
        for (int x = 2; x < width - 3; x += 4) {
            for (int dy = 0; dy < 2; ++dy) {
                const int yy = y + dy;
                const float* rCurr = raw.mosaic.ptr<float>(yy);
                const float* rM2 = raw.mosaic.ptr<float>(yy - 2);
                const float* rP2 = raw.mosaic.ptr<float>(yy + 2);
                for (int dx = 0; dx < 2; ++dx) {
                    const int xx = x + dx;
                    const float p0 = rCurr[xx];
                    const float avgN = 0.25f * (rM2[xx] + rP2[xx] + rCurr[xx - 2] + rCurr[xx + 2]);
                    if (!std::isfinite(p0) || !std::isfinite(avgN)) continue;
                    const float diff = p0 - avgN;
                    sumSqDiff += static_cast<double>(diff) * static_cast<double>(diff);
                    sampleCount++;
                }
            }
        }
    }

    return sampleCount > 0 ? static_cast<float>(sumSqDiff / sampleCount) : 0.0f;
}

float IspCore::computeChromaResidualEnergy(const LinearFloatRaw& raw) {
    if (raw.mosaic.empty() || raw.mosaic.type() != CV_32FC1) return 0.0f;
    const int width = raw.mosaic.cols;
    const int height = raw.mosaic.rows;
    if (width < 9 || height < 9) return 0.0f;

    const int cfaPattern = cfaPatternOrDefault(raw.info.effectiveCfaPattern);
    double sumSqDiff = 0.0;
    size_t sampleCount = 0;

    auto greenReference = [&](int x, int y) -> float {
        const float* rowM1 = raw.mosaic.ptr<float>(y - 1);
        const float* rowP1 = raw.mosaic.ptr<float>(y + 1);
        const float* row = raw.mosaic.ptr<float>(y);
        return 0.25f * (rowM1[x] + rowP1[x] + row[x - 1] + row[x + 1]);
    };

    auto chromaResidual = [&](int x, int y) -> float {
        return raw.mosaic.ptr<float>(y)[x] - greenReference(x, y);
    };

    // Measure only red and blue CFA sites. The residual is high-passed against
    // same-colour neighbours so scene colour itself is not mistaken for noise.
    for (int y = 3; y < height - 3; y += 4) {
        for (int x = 3; x < width - 3; x += 4) {
            for (int dy = 0; dy < 2; ++dy) {
                const int yy = y + dy;
                for (int dx = 0; dx < 2; ++dx) {
                    const int xx = x + dx;
                    const int ch = cfaColorChannel(cfaPattern, xx, yy);
                    if (ch != 0 && ch != 3) continue;

                    const float center = chromaResidual(xx, yy);
                    const float neighbourMean = 0.25f * (
                            chromaResidual(xx - 2, yy) +
                            chromaResidual(xx + 2, yy) +
                            chromaResidual(xx, yy - 2) +
                            chromaResidual(xx, yy + 2)
                    );
                    if (!std::isfinite(center) || !std::isfinite(neighbourMean)) continue;
                    const float diff = center - neighbourMean;
                    sumSqDiff += static_cast<double>(diff) * static_cast<double>(diff);
                    sampleCount++;
                }
            }
        }
    }

    return sampleCount > 0 ? static_cast<float>(sumSqDiff / sampleCount) : 0.0f;
}





// N005: retired RAW post-demosaic/Profile-NR resident bridge removed.
// The remaining legacy backend ABI is purged structurally in N006.

std::string SpectraDownstreamIspState::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(9)
        << "spectraDownstreamArchitecture=" << architecture
        << "; spectraDownstreamInputStage=" << inputStage
        << "; spectraDownstreamOutputStage=" << outputStage
        << "; spectraDownstreamResultStatus=" << resultStatus
        << "; spectraDownstreamSpectraAware=" << (plan.spectraAware ? "true" : "false")
        << "; spectraDownstreamEnabled=" << (plan.enabled ? "true" : "false")
        << "; spectraDownstreamApplied=" << (applied ? "true" : "false")
        << "; spectraDownstreamMethod=" << plan.method
        << "; spectraDownstreamPlanStatus=" << plan.status
        << "; spectraDownstreamResidualSource=" << plan.residualSource
        << "; spectraDownstreamModelConfidence=" << plan.modelConfidence
        << "; spectraDownstreamPredictedSigmaY=" << plan.predictedSigmaY
        << "; spectraDownstreamMeasuredSigmaY=" << plan.measuredSigmaY
        << "; spectraDownstreamSigmaY=" << plan.sigmaY
        << "; spectraDownstreamLumaAuthority=" << plan.downstreamLumaAuthority
        << "; spectraDownstreamDetailProtection=" << plan.detailProtection
        << "; spectraDownstreamGlobalAuthority=" << plan.globalAuthority
        << "; spectraDownstreamBaseAmount=" << plan.baseAmount
        << "; spectraDownstreamMaximumAmount=" << plan.maximumAmount
        << "; spectraDownstreamMinimumEdgeSnr=" << plan.minimumEdgeSnr
        << "; spectraDownstreamFullEdgeSnr=" << plan.fullEdgeSnr
        << "; spectraDownstreamHaloProtection=" << plan.haloProtection
        << "; spectraDownstreamMaximumPredictedVarianceGain="
        << plan.maximumPredictedVarianceGain
        << "; spectraDownstreamLocalContrastEnabled="
        << (plan.localContrastEnabled ? "true" : "false")
        << "; spectraDownstreamLocalContrastAuthority=" << plan.localContrastAuthority
        << "; spectraDownstreamLocalContrastApplied="
        << (localContrastApplied ? "true" : "false")
        << "; spectraDownstreamProcessedPixelCount=" << processedPixelCount
        << "; spectraDownstreamCandidatePixelCount=" << candidatePixelCount
        << "; spectraDownstreamChangedPixelCount=" << changedPixelCount
        << "; spectraDownstreamChangedPixelFraction=" << changedPixelFraction
        << "; spectraDownstreamNoiseRejectedPixelCount=" << noiseRejectedPixelCount
        << "; spectraDownstreamHaloProtectedPixelCount=" << haloProtectedPixelCount
        << "; spectraDownstreamLocalContrastPixelCount=" << localContrastPixelCount
        << "; spectraDownstreamMeanLocalAuthority=" << meanLocalAuthority
        << "; spectraDownstreamAuthorityP10=" << authorityP10
        << "; spectraDownstreamAuthorityP50=" << authorityP50
        << "; spectraDownstreamAuthorityP90=" << authorityP90
        << "; spectraDownstreamMeanEdgeSnr=" << meanEdgeSnr
        << "; spectraDownstreamMaximumAppliedAmount=" << maximumAppliedAmount
        << "; spectraDownstreamMaximumLocalContrastAmount=" << maximumLocalContrastAmount
        << "; spectraDownstreamInputVarianceY=" << inputVarianceY
        << "; spectraDownstreamOutputVarianceY=" << outputVarianceY
        << "; spectraDownstreamMeasuredVarianceGainY=" << measuredVarianceGainY
        << "; spectraDownstreamPredictedVarianceGainY=" << predictedVarianceGainY
        << "; spectraDownstreamInputResidualSampleCount=" << inputResidualSampleCount
        << "; spectraDownstreamOutputResidualSampleCount=" << outputResidualSampleCount
        << "; spectraDownstreamProcessingTimeMs=" << processingTimeMs
        << "; spectraDownstreamInputMeasurementMs=" << inputMeasurementMs
        << "; spectraDownstreamOutputMeasurementMs=" << outputMeasurementMs;
    return out.str();
}

std::string SpectraResidualNoiseState::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(6);
    out << "spectraResidualNoiseState={"
        << "domain=" << domain
        << ";valueStage=" << valueStage
        << ";lastObservedStage=" << lastObservedStage
        << ";propagationStatus=" << propagationStatus
        << ";opponentVarianceStatus=" << opponentVarianceStatus
        << ";covarianceStatus=" << covarianceStatus
        << ";comparabilityStatus=" << comparabilityStatus
        << ";varianceY=" << varianceY
        << ";varianceRG=" << varianceRG
        << ";varianceBG=" << varianceBG
        << ";covarianceRgBg=" << covarianceRgBg
        << ";covarianceRgb=[";
    for (size_t i = 0; i < covarianceRgb.size(); ++i) {
        if (i > 0) out << ",";
        out << covarianceRgb[i];
    }
    out << "]"
        << ";highFrequencyBudget=" << highFrequencyBudget
        << ";midFrequencyBudget=" << midFrequencyBudget
        << ";lowFrequencyBudget=" << lowFrequencyBudget
        << ";correlationLength=" << correlationLength
        << ";directionalPatternEnergy=" << directionalPatternEnergy
        << ";rowPatternEnergy=" << rowPatternEnergy
        << ";columnPatternEnergy=" << columnPatternEnergy
        << ";temporalCorrelation=" << temporalCorrelation
        << ";independentNoiseFraction=" << independentNoiseFraction
        << ";effectiveFrameCount=" << effectiveFrameCount
        << ";modelConfidence=" << modelConfidence
        << ";motionConfidence=" << motionConfidence
        << ";alignmentConfidence=" << alignmentConfidence
        << ";demosaicChromaCloudClassificationReady="
        << (demosaicChromaCloudClassificationReady ? "true" : "false")
        << ";demosaicChromaFieldToResidualRmsRatio=" << demosaicChromaFieldToResidualRmsRatio
        << ";demosaicChromaFieldCoherence=" << demosaicChromaFieldCoherence
        << ";demosaicUnexpectedChromaAmplification=" << demosaicUnexpectedChromaAmplification
        << ";demosaicPreLowFrequencyChromaSupport=" << demosaicPreLowFrequencyChromaSupport
        << ";demosaicChromaCloudRiskEvidence=" << demosaicChromaCloudRiskEvidence
        << ";demosaicChromaCloudRiskStatus=" << demosaicChromaCloudRiskStatus
        << ";calibrationStatus=" << calibrationStatus
        << ";postDemosaicCalibrationReady="
        << (postDemosaicCalibration.ready ? "true" : "false")
        << ";postColourTransformCalibrationReady="
        << (postColourTransformCalibration.ready ? "true" : "false")
        << ";measuredPreSharpenVarianceY=" << measuredPreSharpenVarianceY
        << ";measuredPreSharpenVarianceRG=" << measuredPreSharpenVarianceRG
        << ";measuredPreSharpenVarianceBG=" << measuredPreSharpenVarianceBG
        << ";measuredPreSharpenSampleCount=" << measuredPreSharpenSampleCount
        << ";measuredPostIspVarianceY=" << measuredPostIspVarianceY
        << ";measuredPostIspVarianceRG=" << measuredPostIspVarianceRG
        << ";measuredPostIspVarianceBG=" << measuredPostIspVarianceBG
        << ";measuredPostIspSampleCount=" << measuredPostIspSampleCount
        << "}";
    return out.str();
}

std::string SpectraBudgetState::formatDebugString() const {
    std::ostringstream out;
    out << std::fixed << std::setprecision(6);
    out << "spectraBudget={"
        << "mode=" << (spectraMode == 1 ? "auto" : (spectraMode == 2 ? "manual" : "legacy"))
        << ";applied=" << (applied ? "true" : "false")
        << ";targetFloor=" << predictedNoiseFloor
        << ";initialEnergy=" << initialResidualEnergy
        << ";pass1Energy=" << pass1ResidualEnergy
        << ";pass2Energy=" << pass2ResidualEnergy
        << ";finalRemaining=" << finalRemainingEnergy
        << ";chromaTargetFloor=" << predictedChromaNoiseFloor
        << ";initialChromaEnergy=" << initialChromaResidualEnergy
        << ";pass2ChromaEnergy=" << pass2ChromaResidualEnergy
        << ";downstreamLumaAuthority=" << downstreamLumaAuthority
        << ";downstreamChromaAuthority=" << downstreamChromaAuthority
        << ";p1Skip=" << (pass1SkippedBudgetReached ? "true" : "false")
        << ";p2Skip=" << (pass2SkippedBudgetReached ? "true" : "false")
        << ";effectiveIso=" << effectiveIso
        << ";isoRegime=" << isoRegime
        << ";isoNoisePressure=" << isoNoisePressure
        << ";provenanceConfidence=" << provenanceMeanConfidence
        << ";provenanceShadingGain=" << provenanceMeanShadingGain
        << ";noRegretMeanAcceptance=" << noRegretMeanAcceptance
        << "}";
    return out.str();
}

UltraHdrGainmapArtifact IspCore::takeLastUltraHdrGainmapArtifact() {
    UltraHdrGainmapArtifact artifact = std::move(g_threadLocalUltraHdrGainmapArtifact);
    g_threadLocalUltraHdrGainmapArtifact = UltraHdrGainmapArtifact{};
    return artifact;
}

std::vector<uint8_t> IspCore::renderRawBaselineJpeg(
        LinearFloatRaw workingRaw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig,
        std::string* debugOut,
        int rotationDegrees,
        const ResidentRawRenderInput* residentInput
) {
    const auto totalRenderStart = IspClock::now();
    g_threadLocalUltraHdrGainmapArtifact = UltraHdrGainmapArtifact{};
    bncam::vulkan::VulkanRuntime::instance().resetShotCircuitBreaker();
    bncam::NativeStageHeartbeat::instance().update(meta.captureAttemptId.empty() ? "single_frame" : meta.captureAttemptId, "START_BASELINE_RENDER");
    std::vector<uint8_t> jpegData;
    const bool residentEntry = residentInput != nullptr && residentInput->sampleView.valid &&
            residentInput->rawNormalizeGeneration != 0u;
    if (residentEntry) {
        workingRaw.info = residentInput->sampleView.info;
        workingRaw.diagnostics.valid = true;
        workingRaw.diagnostics.failureReason = "none_resident_gpu_normalize";
    }
    const char* sourceName = rawSourceFormatName(workingRaw.info.sourceFormat);
    const bool isRaw10 = workingRaw.info.sourceFormat == RawSourceFormat::RAW10;
    const bool isRawSensor = workingRaw.info.sourceFormat == RawSourceFormat::RAW_SENSOR;
    if (!isRaw10 && !isRawSensor) {
        if (debugOut != nullptr) {
            *debugOut = std::string("RAW_BASELINE_RENDER: unsupported source format: ") + sourceName;
        }
        ISP_LOGE("RAW_BASELINE_RENDER rejected unsupported source format: %s", sourceName);
        return jpegData;
    }
    if (!rawCfaIsStandardBayer(workingRaw.info.effectiveCfaPattern)) {
        if (debugOut != nullptr) {
            *debugOut = std::string("RAW_BASELINE_RENDER: unsupported CFA contract: ") +
                    rawCfaPatternName(workingRaw.info.effectiveCfaPattern);
        }
        ISP_LOGE(
                "RAW_BASELINE_RENDER rejected unsupported effective CFA pattern: %d",
                workingRaw.info.effectiveCfaPattern);
        return jpegData;
    }
    const bool hostInputValid = workingRaw.diagnostics.valid && !workingRaw.mosaic.empty() &&
            workingRaw.mosaic.type() == CV_32FC1;
    if (!residentEntry && !hostInputValid) {
        std::ostringstream failed;
        failed << "RAW_BASELINE_RENDER: source=" << sourceName
               << "; failure=" << workingRaw.diagnostics.failureReason
               << "; yuvUntouched=true; dngUntouched=true";
        if (debugOut != nullptr) *debugOut = failed.str();
        ISP_LOGE("%s", failed.str().c_str());
        return jpegData;
    }
    const int rawWidth = residentEntry ? residentInput->sampleView.info.width : workingRaw.mosaic.cols;
    const int rawHeight = residentEntry ? residentInput->sampleView.info.height : workingRaw.mosaic.rows;
    const std::size_t rawPixelCount = static_cast<std::size_t>(std::max(0, rawWidth)) *
            static_cast<std::size_t>(std::max(0, rawHeight));
    if (rawWidth <= 0 || rawHeight <= 0 || rawPixelCount == 0u) {
        if (debugOut != nullptr) *debugOut = "RAW_BASELINE_RENDER: invalid RAW dimensions";
        return jpegData;
    }

    IspFrameMetadata workingMeta = meta;

    // Temporal observation, independent S/O fitting and fusion weighting were already
    // resolved against aligned capture frames in DngMerger. The JPEG ISP consumes that
    // immutable post-fusion snapshot and never compares unrelated rendered frames.
    std::ostringstream spectraCaptureIntegration;
    spectraCaptureIntegration << std::fixed << std::setprecision(6)
            << "spectraCaptureIntegration={source=capture_snapshot"
            << ";active=" << (workingMeta.calibration.spectraProcessingMode != 0 ? "true" : "false")
            << ";snapshotPresent=" << (workingMeta.calibration.spectraSnapshotPresent ? "true" : "false")
            << ";modelConfidence=" << workingMeta.calibration.signalModelConfidence
            << ";effectiveS=[" << workingMeta.calibration.effectiveS[0] << ","
            << workingMeta.calibration.effectiveS[1] << ","
            << workingMeta.calibration.effectiveS[2] << ","
            << workingMeta.calibration.effectiveS[3] << "]"
            << ";effectiveO=[" << workingMeta.calibration.effectiveO[0] << ","
            << workingMeta.calibration.effectiveO[1] << ","
            << workingMeta.calibration.effectiveO[2] << ","
            << workingMeta.calibration.effectiveO[3] << "]}";

    const auto spectraProcessingStart = IspClock::now();
    // RAW color render: normal resident success never constructs a full-frame CPU float RAW. This helper
    // is reachable only after an explicit Vulkan stage failure and uses the already-published
    // read-only RAW16 buffer as the bounded reference/failsafe source.
    std::uint32_t spectraFullFrameCpuReadbacks = 0u;
    std::uint32_t spectraFullFrameCpuClones = 0u;
    bool spectraPass0CpuPixelMutation = false;
    bool residentCpuFallbackUsed = false;
    std::string residentCpuFallbackReason = "none";
    auto ensureCpuWorkingRaw = [&](const char* reason) -> bool {
        if (!workingRaw.mosaic.empty() && workingRaw.mosaic.type() == CV_32FC1) return true;
        if (!residentEntry || residentInput->sampleView.masterRaw16 == nullptr) return false;
        workingRaw = normalizeRawForJpeg(
                residentInput->sampleView.masterRaw16, residentInput->sampleView.info);
        if (!workingRaw.diagnostics.valid || workingRaw.mosaic.empty() ||
            workingRaw.mosaic.type() != CV_32FC1) return false;
        residentCpuFallbackUsed = true;
        residentCpuFallbackReason = reason == nullptr ? "EXPLICIT_GPU_FAILURE" : reason;
        return true;
    };

    if (residentEntry) {
        std::string noiseFailure;
        if (!sampleNoiseModelFromResidentRaw(
                    residentInput->sampleView, workingMeta,
                    residentInput->rawNormalizeGeneration, &noiseFailure)) {
            if (!ensureCpuWorkingRaw("RESIDENT_NOISE_MAP_GPU_REDUCTION_FAILED")) {
                if (debugOut != nullptr) *debugOut = "RAW_BASELINE_RENDER: resident noise-map planning failed and CPU failsafe unavailable";
                return jpegData;
            }
            sampleNoiseModelFromProductionRaw(workingRaw, workingMeta);
        }
    } else {
        sampleNoiseModelFromProductionRaw(workingRaw, workingMeta);
    }
    const std::string shadowDiagnostics = residentEntry && !residentCpuFallbackUsed
            ? computeRawShadowDiagnosticsCompact(residentInput->sampleView, workingMeta, uiConfig)
            : computeRawShadowDiagnostics(workingRaw, workingMeta, uiConfig);
    const SpectraIsoAdaptiveState isoState = IspCore::resolveSpectraIsoAdaptiveState(workingMeta, uiConfig);
    const bool preDemosaicPhysicalNoiseModelAvailable =
            workingMeta.calibration.noiseModelMode != 0 &&
            workingMeta.calibration.hasNoiseProfile &&
            workingMeta.calibration.noiseProfileApplied &&
            workingMeta.calibration.signalModelConfidence >= 0.25f;
    SpectraIsoAdaptiveState preDemosaicAuthorityState = isoState;
    // Phase N006A: SPECTRA Core is measurement/conditioning only. The classical RAW
    // Pass 0/1/2/3 pixel owner is retired for every user mode; neural ownership is added later.
    preDemosaicAuthorityState.lumaAuthority = 0.0f;
    preDemosaicAuthorityState.chromaAuthority = 0.0f;
    preDemosaicAuthorityState.lowFrequencyAuthority = 0.0f;
    const auto captureProvenanceStart = IspClock::now();
    const SpectraProvenanceField captureProvenance = residentEntry && !residentCpuFallbackUsed
            ? buildSpectraProvenanceFieldCompact(residentInput->sampleView, workingMeta)
            : buildSpectraProvenanceField(workingRaw, workingMeta);
    const float captureProvenanceMs = elapsedMs(captureProvenanceStart);
    // Delta 37: channel-dependent LSC is a multiplier on already-observed opponent risk only.
    // Equal-channel shading and absent opponent evidence stay neutral.
    const bncam::spectra2::ChannelShadingRisk channelShadingRisk =
            bncam::spectra2::resolveChannelShadingRisk(
                    captureProvenance.lensShadingRedToGreenP90,
                    captureProvenance.lensShadingBlueToGreenP90,
                    captureProvenance.lensShadingOuterMinusCenterRedToGreen,
                    captureProvenance.lensShadingOuterMinusCenterBlueToGreen
            );

    const auto pass0Start = IspClock::now();
    SpectraPass0State pass0State = residentEntry && !residentCpuFallbackUsed
            ? computePass0StateCompact(residentInput->sampleView, workingMeta, uiConfig)
            : computePass0State(workingRaw, workingMeta, uiConfig);
    // N006A hard invariant: legacy Pass 0 may measure/provide telemetry but never mutate RAW.
    pass0State.applyChannelBias = false;
    pass0State.applyRowCorrection = false;
    pass0State.applyColumnCorrection = false;
    SpectraNoRegretResult pass0NoRegret{};
    pass0NoRegret.passIndex = 0;
    // N006D: legacy Pass-0 pixel orchestration is physically retired.
    // Measurement state remains available, but no resident generation or CPU mutation exists.
    pass0State.processingTimeMs = elapsedMs(pass0Start);

    SpectraBudgetState budgetState{};
    budgetState.spectraMode = workingMeta.calibration.spectraProcessingMode;
    budgetState.effectiveIso = isoState.effectiveIso;
    budgetState.isoNoisePressure = preDemosaicAuthorityState.combinedNoisePressure;
    budgetState.isoRegime = preDemosaicAuthorityState.regime;
    budgetState.provenanceMeanConfidence = captureProvenance.meanConfidence;
    budgetState.provenanceMeanShadingGain = captureProvenance.meanLensShadingGain;

    // Milestone 8A begins the performance backend in the roadmap-prescribed order:
    // deterministic CPU reference -> observability -> fused tiled CPU. This active path
    // combines the signal and residual sampling dispatch while retaining the exact sample
    // coordinates and a deterministic tile-order reduction. SIMD/Vulkan remain unselected
    // until physical latency, equivalence and thermal evidence exists.
    bncam::spectra2::PerformanceTelemetry spectraPerformance{};
    spectraPerformance.selection = bncam::spectra2::selectPerformanceBackend(
            rawWidth, rawHeight, budgetState.spectraMode != 0);
    spectraPerformance.frameBytes = static_cast<std::uint64_t>(rawPixelCount) * sizeof(float);
    bncam::spectra2::RawStatisticsRequest initialStatisticsRequest{};
    initialStatisticsRequest.collectSignal = true;
    initialStatisticsRequest.collectResidual = true;
    initialStatisticsRequest.collectChroma = false;
    if (residentEntry && !residentCpuFallbackUsed) {
        // SPECTRA-off has no pre-demosaic budget consumer. Do not materialize a host float frame
        // merely to populate optional performance statistics.
        spectraPerformance.initialStatistics.status = "SPECTRA_OFF_RESIDENT_STATS_NOT_REQUIRED";
    } else {
        const bncam::spectra2::FloatPlaneView performanceView{
                workingRaw.mosaic.ptr<float>(0), rawWidth, rawHeight,
                workingRaw.mosaic.step1(),
                cfaPatternOrDefault(workingRaw.info.effectiveCfaPattern)};
        spectraPerformance.initialStatistics = bncam::spectra2::collectRawStatistics(
                performanceView, spectraPerformance.selection, initialStatisticsRequest);
    }
    spectraPerformance.fusedStatisticsDispatchCount++;
    spectraPerformance.totalStatisticsMs += spectraPerformance.initialStatistics.elapsedMs;
    budgetState.initialResidualEnergy = spectraPerformance.initialStatistics.residualEnergy;

    // Predict the residual-domain floor using separate CFA channel signals.
    // The fused collector preserves the existing 8x8 2x2 CFA sampling grid.
    // computeResidualEnergy uses h = [1, -1/4, -1/4, -1/4, -1/4], whose
    // white-noise energy is sum(h^2) = 1.25.
    std::array<double, 4> signalSum = spectraPerformance.initialStatistics.signalSum;
    std::array<size_t, 4> signalCount{
            static_cast<size_t>(spectraPerformance.initialStatistics.signalCount[0]),
            static_cast<size_t>(spectraPerformance.initialStatistics.signalCount[1]),
            static_cast<size_t>(spectraPerformance.initialStatistics.signalCount[2]),
            static_cast<size_t>(spectraPerformance.initialStatistics.signalCount[3])
    };
    // Read-only physical noise intelligence may still need channel means even when
    // SPECTRA is off. Reuse compact provenance without granting pixel authority.
    if (preDemosaicPhysicalNoiseModelAvailable &&
        signalCount[0] == 0u && signalCount[1] == 0u &&
        signalCount[2] == 0u && signalCount[3] == 0u) {
        for (const auto& tile : captureProvenance.tiles) {
            if (!tile.valid) continue;
            const std::size_t weight = std::max<std::size_t>(1u, tile.sampleCount);
            for (int ch = 0; ch < 4; ++ch) {
                signalSum[static_cast<std::size_t>(ch)] +=
                        static_cast<double>(tile.meanSignal[static_cast<std::size_t>(ch)]) *
                        static_cast<double>(weight);
                signalCount[static_cast<std::size_t>(ch)] += weight;
            }
        }
        spectraPerformance.initialStatistics.status =
                "PHYSICAL_NOISE_MODEL_SIGNAL_FROM_CAPTURE_PROVENANCE";
    }
    const int budgetCfaPattern = cfaPatternOrDefault(workingRaw.info.effectiveCfaPattern);

    std::array<double, 4> predictedChannelVariance{0.0, 0.0, 0.0, 0.0};
    double predictedPixelVariance = 0.0;
    int validNoiseChannels = 0;
    for (int ch = 0; ch < 4; ++ch) {
        const double sValue = workingMeta.calibration.effectiveS[ch];
        const double oValue = workingMeta.calibration.effectiveO[ch];
        if (!isVstValid(sValue, oValue) || signalCount[ch] == 0) continue;
        const double meanSignal = signalSum[ch] / static_cast<double>(signalCount[ch]);
        predictedChannelVariance[static_cast<size_t>(ch)] =
                std::max(0.0, sValue * meanSignal + oValue);
        predictedPixelVariance += predictedChannelVariance[static_cast<size_t>(ch)];
        validNoiseChannels++;
    }
    const double meanPredictedPixelVariance = validNoiseChannels > 0
            ? predictedPixelVariance / static_cast<double>(validNoiseChannels)
            : 0.0;
    budgetState.predictedNoiseFloor = captureProvenance.meanPredictedSpatialResidualVariance > 0.0f
            ? captureProvenance.meanPredictedSpatialResidualVariance * preDemosaicAuthorityState.targetFloorScale
            : static_cast<float>(1.25 * meanPredictedPixelVariance * preDemosaicAuthorityState.targetFloorScale);

    // The CFA chroma observer works in an R-G / B-G residual domain. Predict a dedicated
    // raw-domain noise floor from the physical S/O model; this is evidence only and grants no
    // correction or reconstruction authority by itself.
    double predictedChromaVariance = 0.0;
    int validChromaChannels = 0;
    const double meanGreenS = 0.5 * (
            workingMeta.calibration.effectiveS[1] +
            workingMeta.calibration.effectiveS[2]
    );
    const double meanGreenO = 0.5 * (
            workingMeta.calibration.effectiveO[1] +
            workingMeta.calibration.effectiveO[2]
    );
    const double meanGreenSignal = (signalCount[1] > 0 && signalCount[2] > 0)
            ? 0.5 * (
                    signalSum[1] / static_cast<double>(signalCount[1]) +
                    signalSum[2] / static_cast<double>(signalCount[2])
            )
            : 0.0;
    const double greenVariance = std::max(0.0, meanGreenS * meanGreenSignal + meanGreenO);
    for (int ch : {0, 3}) {
        if (!isVstValid(workingMeta.calibration.effectiveS[ch], workingMeta.calibration.effectiveO[ch]) ||
            signalCount[ch] == 0) {
            continue;
        }
        const double meanSignal = signalSum[ch] / static_cast<double>(signalCount[ch]);
        const double colourVariance = std::max(
                0.0,
                workingMeta.calibration.effectiveS[ch] * meanSignal +
                workingMeta.calibration.effectiveO[ch]
        );
        predictedChromaVariance += colourVariance + 0.25 * greenVariance;
        validChromaChannels++;
    }
    budgetState.predictedChromaNoiseFloor = captureProvenance.meanPredictedChromaResidualVariance > 0.0f
            ? captureProvenance.meanPredictedChromaResidualVariance * preDemosaicAuthorityState.targetFloorScale
            : (validChromaChannels > 0
                    ? static_cast<float>(1.5 * predictedChromaVariance /
                            static_cast<double>(validChromaChannels) * preDemosaicAuthorityState.targetFloorScale)
                    : 0.0f);

    // Read-only downstream-risk observation: propagate the physical CFA noise estimate through
    // the requested demosaic model, AWB/CCM and configured curve stack. This is telemetry/conditioning
    // evidence only; it is deliberately not used to set the raw-domain band residual pressure.
    const double predictedGreenVariance = 0.5 * (
            predictedChannelVariance[1] + predictedChannelVariance[2]
    );
    const std::array<double, 4> captureVisibleVarianceByChannel =
            meanPredictedVisibleVarianceByChannel(captureProvenance);
    const bool captureVisibleVarianceReady = hasThreeColourVariances(
            captureVisibleVarianceByChannel
    );
    const double cfaRiskRedVariance = captureVisibleVarianceReady
            ? captureVisibleVarianceByChannel[0]
            : predictedChannelVariance[0];
    const double cfaRiskGreenVariance = captureVisibleVarianceReady
            ? 0.5 * (captureVisibleVarianceByChannel[1] + captureVisibleVarianceByChannel[2])
            : predictedGreenVariance;
    const double cfaRiskBlueVariance = captureVisibleVarianceReady
            ? captureVisibleVarianceByChannel[3]
            : predictedChannelVariance[3];
    const bncam::spectra2::NoiseState cfaRiskPreDemosaic = bncam::spectra2::makeState(
            "CFA_VISIBLE_RISK_POST_LENS_SHADING_PRE_DEMOSAIC",
            captureVisibleVarianceReady
                    ? "CAMERA2_SO_WITH_LENS_SHADING_GAIN_SQUARED"
                    : "CAMERA2_SO_SIGNAL_MODEL_LENS_SHADING_FALLBACK",
            validNoiseChannels >= 3
                    ? (captureVisibleVarianceReady ? "AVAILABLE" : "LENS_SHADING_FALLBACK")
                    : "PARTIAL_CHANNEL_FALLBACK",
            std::clamp(
                    static_cast<double>(captureProvenance.meanDiagnosticConfidence) *
                            static_cast<double>(validNoiseChannels) / 4.0 *
                            (captureVisibleVarianceReady ? 0.92 : 0.72),
                    0.0,
                    1.0
            ),
            bncam::spectra2::diagonalCovariance(
                    cfaRiskRedVariance,
                    cfaRiskGreenVariance,
                    cfaRiskBlueVariance
            )
    );
    const bncam::spectra2::NoiseState cfaRiskDemosaic =
            bncam::spectra2::propagateDemosaic(
                    cfaRiskPreDemosaic,
                    requestedDemosaicNoiseModel(workingMeta.requestedDemosaicMode),
                    "CFA_VISIBLE_RISK_POST_DEMOSAIC"
            );
    const bncam::spectra2::NoiseState cfaRiskAwb = bncam::spectra2::propagateAwb(
            cfaRiskDemosaic,
            normalizedWbRgb(workingMeta.calibration),
            "CFA_VISIBLE_RISK_POST_AWB"
    );
    const bncam::spectra2::NoiseState cfaRiskCcm = bncam::spectra2::propagateColourMatrix(
            cfaRiskAwb,
            colourMatrixArray(workingMeta.calibration.effectiveColorMatrix),
            "CFA_VISIBLE_RISK_POST_CCM"
    );
    const GenericCurvePropagationEstimate genericCurveEstimate =
            estimateGenericCurvePropagation(uiConfig);
    const bncam::spectra2::NoiseState cfaRiskVisible =
            bncam::spectra2::propagateOpponentGains(
                    cfaRiskCcm,
                    genericCurveEstimate.total.rms,
                    genericCurveEstimate.chromaScale.rms,
                    genericCurveEstimate.chromaScale.rms,
                    "CFA_VISIBLE_RISK_POST_PROFILE_CURVES",
                    "GENERIC_PROFILE_CURVE_DERIVATIVE_ESTIMATE",
                    0.80
            );
    const float cfaVisibleChromaAmplification = static_cast<float>(
            bncam::spectra2::chromaAmplification(
                    cfaRiskPreDemosaic,
                    cfaRiskVisible
            )
    );
    const float cfaVisibleRiskConfidence = static_cast<float>(cfaRiskVisible.confidence);
    const bool cfaVisibleRiskReady = validNoiseChannels >= 3 &&
            std::isfinite(cfaVisibleChromaAmplification);
    const char* cfaVisibleRiskStatus = cfaVisibleRiskReady
            ? "OBSERVATION_ONLY_NO_PIXEL_AUTHORITY"
            : "INSUFFICIENT_INPUT_FOR_VISIBLE_RISK_OBSERVATION";

    const auto pass1Start = IspClock::now();
    bncam::NativeStageHeartbeat::instance().update(workingMeta.captureAttemptId, "SPECTRA_PASS1");
    SpectraPass1State pass1State = residentEntry && !residentCpuFallbackUsed
            ? computePass1StateCompact(residentInput->sampleView, workingMeta, uiConfig)
            : computePass1State(workingRaw, workingMeta, uiConfig);
    // N006A hard invariant: legacy Pass 1 VST/Wiener filtering and its CPU fallback are retired.
    pass1State.applied = false;
    pass1State.fallbackReason = "legacy_raw_pixel_authority_retired_neural_pending";
    SpectraNoRegretResult pass1NoRegret{};
    pass1NoRegret.passIndex = 1;
    SpectraProvenanceField pass1BeforeField{};
    SpectraProvenanceField pass2BeforeField{};
    // N006D: no legacy Pass-1 resident generation and no classical CPU fallback remain.
    pass1State.processingTimeMs = elapsedMs(pass1Start);

    if (residentEntry && !residentCpuFallbackUsed) {
        spectraPerformance.postPass1Statistics.status = "LEGACY_PASS1_RETIRED_RESIDENT_STATS_NOT_REQUIRED";
    } else {
        const bncam::spectra2::FloatPlaneView postPass1PerformanceView{
                workingRaw.mosaic.ptr<float>(0), rawWidth, rawHeight,
                workingRaw.mosaic.step1(), budgetCfaPattern
        };
        bncam::spectra2::RawStatisticsRequest postPass1StatisticsRequest{};
        postPass1StatisticsRequest.collectSignal = false;
        postPass1StatisticsRequest.collectResidual = true;
        postPass1StatisticsRequest.collectChroma = true;
        spectraPerformance.postPass1Statistics = bncam::spectra2::collectRawStatistics(
                postPass1PerformanceView,
                spectraPerformance.selection,
                postPass1StatisticsRequest
        );
        spectraPerformance.fusedStatisticsDispatchCount++;
        spectraPerformance.totalStatisticsMs += spectraPerformance.postPass1Statistics.elapsedMs;
    }
    budgetState.pass1ResidualEnergy = spectraPerformance.postPass1Statistics.residualEnergy;
    budgetState.initialChromaResidualEnergy =
            spectraPerformance.postPass1Statistics.chromaResidualEnergy;

    const auto pass2Start = IspClock::now();
    bncam::NativeStageHeartbeat::instance().update(
            workingMeta.captureAttemptId, "SPECTRA_CFA_BAND_OBSERVER");
    SpectraPass2State pass2State = residentEntry && !residentCpuFallbackUsed
            ? computePass2StateCompact(residentInput->sampleView, workingMeta, uiConfig)
            : computePass2State(workingRaw, workingMeta, uiConfig);

    const auto pass2BandMeasureStart = IspClock::now();
    bncam::spectra2::ChromaBandEnergySnapshot initialChromaBands{};
    if (budgetState.spectraMode != 0 && (!residentEntry || residentCpuFallbackUsed)) {
        initialChromaBands = measureSpectraChromaBandEnergies(workingRaw);
    } else if (budgetState.spectraMode != 0) {
        initialChromaBands.status = "RESIDENT_MEASUREMENT_DEFERRED_NO_HOST_MATERIALIZATION";
        initialChromaBands.method = "READ_ONLY_CORE_NO_HOST_MATERIALIZATION";
    } else {
        initialChromaBands.status = "SPECTRA_OFF_NOT_MEASURED";
        initialChromaBands.method = "NOT_RUN";
    }
    pass2State.bandEnergyMeasurementMs = elapsedMs(pass2BandMeasureStart);
    pass2State.bandEnergyStatus = initialChromaBands.status;
    pass2State.bandEnergyMethod = initialChromaBands.method;
    pass2State.bandEnergySampleCount = initialChromaBands.sampleCount;
    pass2State.bandEnergyRedSampleCount = initialChromaBands.redSampleCount;
    pass2State.bandEnergyBlueSampleCount = initialChromaBands.blueSampleCount;
    pass2State.bandEnergyRedBlueSampleBalance = initialChromaBands.redBlueSampleBalance;
    pass2State.bandEnergyConfidence = initialChromaBands.confidence;

    const float bandEvidenceModelConfidence = std::min(
            pass2State.modelConfidence,
            initialChromaBands.confidence
    );
    const CfaBandResidualEvidence fineBandEvidence = resolveCfaBandResidualEvidence(
            bncam::spectra2::ChromaBandKind::Fine,
            initialChromaBands.fineEnergy,
            budgetState.predictedChromaNoiseFloor,
            bandEvidenceModelConfidence
    );
    const CfaBandResidualEvidence midBandEvidence = resolveCfaBandResidualEvidence(
            bncam::spectra2::ChromaBandKind::Mid,
            initialChromaBands.midEnergy,
            budgetState.predictedChromaNoiseFloor,
            bandEvidenceModelConfidence
    );

    // Classical Pass-2 execution is gone. The image is unchanged; reuse the same observation
    // instead of fabricating a post-filter measurement or reduction percentage.
    const bncam::spectra2::ChromaBandEnergySnapshot postPass2ChromaBands = initialChromaBands;
    spectraPerformance.postPass2Statistics = spectraPerformance.postPass1Statistics;
    budgetState.pass2ResidualEnergy = spectraPerformance.postPass2Statistics.residualEnergy;
    budgetState.pass2ChromaResidualEnergy =
            spectraPerformance.postPass2Statistics.chromaResidualEnergy;

    const auto lowBandObserverStart = IspClock::now();
    bncam::NativeStageHeartbeat::instance().update(
            workingMeta.captureAttemptId, "SPECTRA_CFA_LOW_BAND_OBSERVER");
    const float lowDirectionalEvidence = spectraDirectionalEvidence(
            postPass2ChromaBands,
            budgetState.predictedChromaNoiseFloor
    );
    const CfaBandResidualEvidence lowBandEvidence = resolveCfaBandResidualEvidence(
            bncam::spectra2::ChromaBandKind::Low,
            postPass2ChromaBands.lowEnergy,
            budgetState.predictedChromaNoiseFloor,
            std::min(
                    std::clamp(workingMeta.calibration.signalModelConfidence, 0.0f, 1.0f),
                    postPass2ChromaBands.confidence
            ),
            lowDirectionalEvidence
    );
    const float lowBandObserverMs = elapsedMs(lowBandObserverStart);
    const float postPass2BandMeasurementMs = 0.0f;

    // The CFA-confidence contract is still shared with demosaic. Feed it a neutral compatibility
    // transport containing only raw-domain evidence; correction/authority fields remain zero.
    const bncam::spectra2::ChromaBandPlan fineBandEvidenceTransport =
            makeCfaEvidenceTransport(fineBandEvidence);
    const bncam::spectra2::ChromaBandPlan midBandEvidenceTransport =
            makeCfaEvidenceTransport(midBandEvidence);
    const bncam::spectra2::ChromaBandPlan lowBandEvidenceTransport =
            makeCfaEvidenceTransport(lowBandEvidence);

    pass2State.processingTimeMs = elapsedMs(pass2Start);

    budgetState.finalRemainingEnergy = budgetState.initialResidualEnergy;
    const auto finalProvenanceStart = IspClock::now();
    const SpectraProvenanceField finalProvenance = captureProvenance;
    const bool physicalSpatialNoiseShapeInstalled = installPhysicalSpatialNoiseShape(
            finalProvenance, rawWidth, rawHeight);
    if (physicalSpatialNoiseShapeInstalled && g_threadLocalIspStats.noiseModelApplied) {
        g_threadLocalIspStats.noiseModelReason =
                "sensor_so_plus_lens_shading_g2_relative_shape_absolute_covariance_propagated_by_domain";
    }
    const float finalProvenanceMs = elapsedMs(finalProvenanceStart);
    double noRegretAcceptanceSum = 0.0;
    int noRegretEvaluatedPasses = 0;
    for (const SpectraNoRegretResult* passResult : {
            &pass0NoRegret,
            &pass1NoRegret
    }) {
        if (passResult->evaluatedTiles <= 0) continue;
        noRegretAcceptanceSum += passResult->meanAcceptance;
        noRegretEvaluatedPasses++;
    }
    budgetState.noRegretMeanAcceptance = noRegretEvaluatedPasses > 0
            ? static_cast<float>(noRegretAcceptanceSum /
                    static_cast<double>(noRegretEvaluatedPasses))
            : 0.0f;

    auto remainingDownstreamAuthority = [](float before, float after, float target) -> float {
        if (!(before > 0.0f) || !(target > 0.0f) || !std::isfinite(after)) return 1.0f;
        const float initialExcess = std::max(0.0f, before - target);
        const float remainingExcess = std::max(0.0f, after - target);
        if (initialExcess <= 1.0e-12f) return 0.35f;
        // The pre-demosaic and RGB residual domains are not identical. A square-root
        // bridge and a conservative floor avoid both double denoise and abrupt loss of
        // any future neural/downstream conditioning without granting classical pixel authority.
        return std::clamp(std::sqrt(remainingExcess / initialExcess), 0.35f, 1.0f);
    };
    if (budgetState.spectraMode != 0) {
        budgetState.downstreamLumaAuthority = remainingDownstreamAuthority(
                budgetState.initialResidualEnergy,
                budgetState.finalRemainingEnergy,
                budgetState.predictedNoiseFloor
        );
        budgetState.downstreamChromaAuthority = remainingDownstreamAuthority(
                budgetState.initialChromaResidualEnergy,
                budgetState.pass2ChromaResidualEnergy,
                budgetState.predictedChromaNoiseFloor
        );
    }
    // N006N: all retired classical RAW SPECTRA pixel owners are physically absent.
    budgetState.applied = false;

    // Delta 20: consolidate already-existing CFA chroma evidence into one immutable contract.
    // No additional scan and no pixel authority is introduced here. Red/blue are intentionally
    // represented as common opponent support until a later local per-colour confidence field is
    // measured; fabricating separate R/B confidence from aggregate band energy would be misleading.
    // Phase N001: CFA band/structure evidence is available only when its SPECTRA
    // measurement passes actually ran. Physical S/O remains separate read-only evidence.
    const int demosaicEvidenceMode = budgetState.spectraMode;
    bncam::spectra2::CfaChromaConfidenceSummary cfaChromaConfidence =
            bncam::spectra2::buildCfaChromaConfidenceSummary(
                    demosaicEvidenceMode,
                    workingMeta.calibration.signalModelConfidence,
                    initialChromaBands,
                    fineBandEvidenceTransport,
                    midBandEvidenceTransport,
                    lowBandEvidenceTransport,
                    0.0f, // retired Pass-3 spatial correction confidence: no pixel authority
                    pass1State.anisotropicDetail.confidenceP50,
                    pass1State.edgeProtectedFraction,
                    pass0State.greenSplitTileConsensus,
                    pass0State.greenSplitMad,
                    pass0State.g1g2After,
                    static_cast<std::uint64_t>(std::max(0, pass0State.greenSplitTileCount))
            );
    bncam::spectra2::applyChannelShadingRisk(cfaChromaConfidence, channelShadingRisk);

    // Delta 25: freeze the compact SPECTRA evidence handed to whichever active demosaic wins.
    // This snapshot is immutable for the capture and has no reconstruction authority yet.
    DemosaicCfaEvidence demosaicCfaEvidence{};
    demosaicCfaEvidence.available =
            budgetState.spectraMode != 0 &&
            cfaChromaConfidence.bandMeasurementSupport > 0.0f;
    demosaicCfaEvidence.commonOpponentSupport = cfaChromaConfidence.commonOpponentSupport;
    demosaicCfaEvidence.structureProtection = cfaChromaConfidence.structureProtection;
    demosaicCfaEvidence.fineCorrectionConfidence = cfaChromaConfidence.fineCorrectionConfidence;
    demosaicCfaEvidence.midCorrectionConfidence = cfaChromaConfidence.midCorrectionConfidence;
    demosaicCfaEvidence.lowCorrectionConfidence = cfaChromaConfidence.lowCorrectionConfidence;
    demosaicCfaEvidence.redOpponentCorrectionConfidence =
            cfaChromaConfidence.redOpponentCorrectionConfidence;
    demosaicCfaEvidence.blueOpponentCorrectionConfidence =
            cfaChromaConfidence.blueOpponentCorrectionConfidence;

    // Delta 52: pre-demosaic downstream chroma-amplification audit. At this point the camera
    // metadata WB and effective CCM are already known, so estimate how a unit R-G or B-G
    // opponent residual would be magnified later in the ISP. This is observability-only: the
    // demosaic evidence and pixels are deliberately left unchanged in this delta. The small
    // optional phone-assistance CCT adjustment is applied later and is therefore excluded here.
    const std::array<double, 3> preDemosaicMetadataWb = normalizedWbRgb(meta.calibration);
    const std::array<double, 9> preDemosaicCcm =
            colourMatrixArray(meta.calibration.effectiveColorMatrix);
    const double preDemosaicRedOpponentDirectionalGain = std::max(0.0,
            preDemosaicMetadataWb[0] * std::sqrt(
                    std::pow(preDemosaicCcm[0] - preDemosaicCcm[3], 2.0) +
                    std::pow(preDemosaicCcm[6] - preDemosaicCcm[3], 2.0)));
    const double preDemosaicBlueOpponentDirectionalGain = std::max(0.0,
            preDemosaicMetadataWb[2] * std::sqrt(
                    std::pow(preDemosaicCcm[2] - preDemosaicCcm[5], 2.0) +
                    std::pow(preDemosaicCcm[8] - preDemosaicCcm[5], 2.0)));
    const auto preDemosaicAmplificationPressure = [](double gain) -> double {
        const double u = std::clamp((gain - 1.05) / (1.80 - 1.05), 0.0, 1.0);
        return u * u * (3.0 - 2.0 * u);
    };
    const double preDemosaicRedOpponentAmplificationPressure =
            demosaicCfaEvidence.available
                    ? preDemosaicAmplificationPressure(preDemosaicRedOpponentDirectionalGain)
                    : 0.0;
    const double preDemosaicBlueOpponentAmplificationPressure =
            demosaicCfaEvidence.available
                    ? preDemosaicAmplificationPressure(preDemosaicBlueOpponentDirectionalGain)
                    : 0.0;

    // Delta 53: feed-forward only the channel-specific downstream amplification into the
    // demosaic's existing opponent confidence. This remains multiplicative: absent R-G/B-G
    // evidence stays absent, so WB/CCM amplification can never invent a denoise request. The
    // maximum +15% relative boost is deliberately small and affects only missing R/B chroma
    // reconstruction; green/luma reconstruction and the WB/CCM themselves are unchanged.
    DemosaicCfaEvidence demosaicExecutionCfaEvidence = demosaicCfaEvidence;
    const float preDemosaicRedOpponentConfidenceBoost = demosaicCfaEvidence.available
            ? static_cast<float>(0.15 * preDemosaicRedOpponentAmplificationPressure)
            : 0.0f;
    const float preDemosaicBlueOpponentConfidenceBoost = demosaicCfaEvidence.available
            ? static_cast<float>(0.15 * preDemosaicBlueOpponentAmplificationPressure)
            : 0.0f;
    if (demosaicExecutionCfaEvidence.available) {
        demosaicExecutionCfaEvidence.redOpponentCorrectionConfidence = std::clamp(
                demosaicCfaEvidence.redOpponentCorrectionConfidence *
                        (1.0f + preDemosaicRedOpponentConfidenceBoost),
                0.0f,
                1.0f
        );
        demosaicExecutionCfaEvidence.blueOpponentCorrectionConfidence = std::clamp(
                demosaicCfaEvidence.blueOpponentCorrectionConfidence *
                        (1.0f + preDemosaicBlueOpponentConfidenceBoost),
                0.0f,
                1.0f
        );
    }

    SpectraResidualNoiseState residualNoiseState{};
    // Build a physical RGB covariance seed from the capture-local S/O model, then calibrate its
    // channel energy with the measured pre-demosaic reduction. The measured provenance proxies
    // remain exported separately as budgets; they are not mislabelled as RGB covariance.
    const double lumaResidualScale = budgetState.initialResidualEnergy > 1.0e-12f
            ? std::clamp(
                    static_cast<double>(budgetState.finalRemainingEnergy) /
                            static_cast<double>(budgetState.initialResidualEnergy),
                    0.05,
                    2.0
            )
            : 1.0;
    const double chromaResidualScale = budgetState.initialChromaResidualEnergy > 1.0e-12f
            ? std::clamp(
                    static_cast<double>(budgetState.pass2ChromaResidualEnergy) /
                            static_cast<double>(budgetState.initialChromaResidualEnergy),
                    0.05,
                    2.0
            )
            : lumaResidualScale;
    const std::array<double, 4> finalVisibleVarianceByChannel =
            meanPredictedVisibleVarianceByChannel(finalProvenance);
    const bool finalVisibleVarianceReady = hasThreeColourVariances(
            finalVisibleVarianceByChannel
    );
    const double residualSeedRedVariance = finalVisibleVarianceReady
            ? finalVisibleVarianceByChannel[0]
            : predictedChannelVariance[0];
    const double residualSeedGreenVariance = finalVisibleVarianceReady
            ? 0.5 * (finalVisibleVarianceByChannel[1] + finalVisibleVarianceByChannel[2])
            : predictedGreenVariance;
    const double residualSeedBlueVariance = finalVisibleVarianceReady
            ? finalVisibleVarianceByChannel[3]
            : predictedChannelVariance[3];
    // Resolve the covariance confidence from the authority that actually produced the seed.
    // Resident SPECTRA-Off may intentionally have no diagnostic provenance field while still
    // carrying a validated Camera2 S/O model and complete capture-local channel variances. Do
    // not erase that physical authority merely because the optional diagnostic route was skipped.
    const bncam::spectra2::ResidualSeedConfidencePlan residualSeedConfidence =
            bncam::spectra2::resolveResidualSeedConfidence(
                    finalVisibleVarianceReady,
                    validNoiseChannels,
                    static_cast<double>(finalProvenance.meanDiagnosticConfidence),
                    preDemosaicPhysicalNoiseModelAvailable,
                    static_cast<double>(workingMeta.calibration.signalModelConfidence),
                    static_cast<double>(workingMeta.calibration.signalModelConfidence),
                    residualSeedRedVariance,
                    residualSeedGreenVariance,
                    residualSeedBlueVariance);
    residualNoiseState.preDemosaic = bncam::spectra2::makeState(
            "POST_LENS_SHADING_PRE_DEMOSAIC",
            residualSeedConfidence.method,
            residualSeedConfidence.status,
            residualSeedConfidence.confidence,
            bncam::spectra2::diagonalCovariance(
                    residualSeedRedVariance * chromaResidualScale,
                    residualSeedGreenVariance * lumaResidualScale,
                    residualSeedBlueVariance * chromaResidualScale
            )
    );
    residualNoiseState.varianceY = static_cast<float>(residualNoiseState.preDemosaic.varianceY);
    residualNoiseState.varianceRG = static_cast<float>(residualNoiseState.preDemosaic.varianceRG);
    residualNoiseState.varianceBG = static_cast<float>(residualNoiseState.preDemosaic.varianceBG);
    residualNoiseState.covarianceRgBg =
            static_cast<float>(residualNoiseState.preDemosaic.covarianceRgBg);
    for (size_t index = 0; index < residualNoiseState.covarianceRgb.size(); ++index) {
        residualNoiseState.covarianceRgb[index] = static_cast<float>(
                residualNoiseState.preDemosaic.covariance.values[index]
        );
    }
    residualNoiseState.highFrequencyBudget = std::max(
            0.0f,
            budgetState.finalRemainingEnergy - budgetState.predictedNoiseFloor
    );
    residualNoiseState.midFrequencyBudget = std::max(
            0.0f,
            budgetState.pass2ChromaResidualEnergy - budgetState.predictedChromaNoiseFloor
    );
    residualNoiseState.lowFrequencyBudget = std::max(
            0.0f,
            budgetState.finalRemainingEnergy - budgetState.predictedNoiseFloor
    );
    // Retired Pass-3 correction state no longer fabricates post-correction row/column energy.
    residualNoiseState.directionalPatternEnergy = 0.0f;
    residualNoiseState.rowPatternEnergy = 0.0f;
    residualNoiseState.columnPatternEnergy = 0.0f;
    residualNoiseState.modelConfidence = static_cast<float>(
            residualNoiseState.preDemosaic.confidence);
    residualNoiseState.cfaVisibleRiskReady = cfaVisibleRiskReady;
    residualNoiseState.cfaVisibleRiskStatus = cfaVisibleRiskStatus;
    residualNoiseState.cfaVisibleRiskConfidence = cfaVisibleRiskConfidence;
    residualNoiseState.cfaVisibleChromaAmplification = cfaVisibleChromaAmplification;
    const float spectraProcessingMs = elapsedMs(spectraProcessingStart);

    // workingRaw is already the private JPEG-only normalization result. Taking it by value and
    // moving at the production call sites removes one redundant full-frame float clone while the
    // OriginalMasterRaw16 remains read-only.
    LinearFloatRaw& jpegRaw = workingRaw;
    const int demosaicInputWidth = rawWidth;
    const int demosaicInputHeight = rawHeight;
    // Milestone 8H-I: the JPEG-only RAW finalizer is GPU-primary for fixed demosaic modes.
    // It replaces three O(N) CPU passes (defect correction, green-plane balance and lens shading),
    // keeps the finalized Bayer mosaic device-resident for demosaic, and derives the Auto-demosaic
    // scene metrics from that exact finalized GPU image through a compact observer. CPU runs only
    // as typed failure recovery, never as a normal duplicate full-frame shadow path.
    DefectCorrectionDebug defectDebug{};
    GreenSplitDebug greenSplitDebug{};
    LensShadingDebug lensDebug{};
    double fullRaw16SaturatedPct = 0.0;
    bool jpegRawCpuFinalized = false;
    bool rawFinalizeResident = false;
    bool rawFinalizeFailureResidentInputMaterialized = false;
    bool demosaicFailureRawFinalizeMaterialized = false;
    bncam::vulkan::SpectraRawFinalizeResult vulkanRawFinalize{};
    bncam::raw_exposure::Plan cpuAdaptiveExposurePlan{};
    cpuAdaptiveExposurePlan.status = "DISABLED_LOCAL_FLLF_OWNS_BRIGHTNESS";
    bool cpuAdaptiveExposureApplied = false;

    const auto materializeRawNormalizeForCpuFinalize = [&]() -> bool {
        if (residentEntry && !residentCpuFallbackUsed) {
            const bool ok = ensureCpuWorkingRaw("RAW_FINALIZE_VULKAN_FAILED_RAW_NORMALIZE_FALLBACK");
            rawFinalizeFailureResidentInputMaterialized = ok;
            return ok;
        }
        return true;
    };

    const auto runCpuRawFinalize = [&]() {
        if (jpegRawCpuFinalized) return;
        if (jpegRaw.mosaic.empty() && !ensureCpuWorkingRaw("CPU_RAW_FINALIZE_EXPLICIT_FAILSAFE")) return;
        std::atomic<uint64_t> fullRaw16SaturatedCount{0};
        cv::parallel_for_(cv::Range(0, jpegRaw.mosaic.rows), [&](const cv::Range& range) {
            uint64_t localSaturated = 0;
            for (int y = range.start; y < range.end; ++y) {
                const float* row = jpegRaw.mosaic.ptr<float>(y);
                for (int x = 0; x < jpegRaw.mosaic.cols; ++x) {
                    if (row[x] >= 1.0f) ++localSaturated;
                }
            }
            fullRaw16SaturatedCount.fetch_add(localSaturated, std::memory_order_relaxed);
        });
        fullRaw16SaturatedPct = jpegRaw.mosaic.total() > 0
                ? 100.0 * static_cast<double>(fullRaw16SaturatedCount.load(std::memory_order_relaxed)) /
                        static_cast<double>(jpegRaw.mosaic.total())
                : 0.0;
        defectDebug = applyDefectCorrectionToJpegRaw(jpegRaw, meta);
        greenSplitDebug = applyGreenSplitCorrectionToJpegRaw(jpegRaw);
        lensDebug = applyLensShadingToJpegRaw(jpegRaw, meta);
        // Automatic software exposure is intentionally neutral. Local FLLF tone mapping owns
        // automatic brightness/DR placement after calibrated scene-linear colour.
        jpegRawCpuFinalized = true;
        rawFinalizeResident = false;
    };

    const bool autoDemosaicRequested =
            meta.requestedDemosaicMode == static_cast<int>(DemosaicMode::Auto);
    {
        bncam::vulkan::SpectraRawFinalizeRequest request{};
        request.mosaicData = jpegRaw.mosaic.empty() ? nullptr : jpegRaw.mosaic.ptr<float>(0);
        request.deferFullFrameReadback = true;
        request.frameWidth = static_cast<std::uint32_t>(rawWidth);
        request.frameHeight = static_cast<std::uint32_t>(rawHeight);
        request.rowStrideFloats = jpegRaw.mosaic.empty()
                ? static_cast<std::size_t>(rawWidth) : jpegRaw.mosaic.step1();
        request.effectiveCfaPattern = static_cast<std::uint32_t>(
                cfaPatternOrDefault(jpegRaw.info.effectiveCfaPattern));
        request.sensorCfaPattern = static_cast<std::uint32_t>(
                cfaPatternOrDefault(jpegRaw.info.sensorCfaPattern));
        request.cfaOffsetX = jpegRaw.info.cfaOffsetX;
        request.cfaOffsetY = jpegRaw.info.cfaOffsetY;
        request.isRaw10 = meta.isRaw10;
        request.adaptiveExposureEnabled = false;
        request.noiseModelValid = meta.calibration.noiseProfileApplied &&
                meta.calibration.normalizationCalibrationValid &&
                meta.calibration.signalModelConfidence > 0.0f;
        for (int ch = 0; ch < 4; ++ch) {
            request.effectiveS[static_cast<std::size_t>(ch)] =
                    static_cast<float>(std::max(0.0, meta.calibration.effectiveS[ch]));
            request.effectiveO[static_cast<std::size_t>(ch)] =
                    static_cast<float>(std::max(0.0, meta.calibration.effectiveO[ch]));
        }
        if (lensShadingMapValid(meta)) {
            request.lensShadingMap = meta.lensShadingMap.data();
            request.lensShadingColumns = static_cast<std::uint32_t>(meta.lensShadingColumns);
            request.lensShadingRows = static_cast<std::uint32_t>(meta.lensShadingRows);
            request.lensShadingGenerationId = spectraLensMapGenerationId(meta);
        }
        if (residentEntry && !residentCpuFallbackUsed) {
            vulkanRawFinalize = bncam::vulkan::VulkanRuntime::instance()
                    .executeSpectraRawFinalizeFromRawNormalize(
                            request, residentInput->rawNormalizeGeneration);
        } else {
            vulkanRawFinalize =
                    bncam::vulkan::VulkanRuntime::instance().executeSpectraRawFinalize(request);
        }
        const bool autoMetricsReady = !autoDemosaicRequested ||
                vulkanRawFinalize.autoSceneMetricsReady;
        if (vulkanRawFinalize.success && vulkanRawFinalize.residentOutputGeneration != 0u &&
            autoMetricsReady) {
            rawFinalizeResident = true;
            const double totalPixels = static_cast<double>(std::max<std::size_t>(1u, rawPixelCount));
            fullRaw16SaturatedPct = 100.0 * static_cast<double>(
                    vulkanRawFinalize.sourceSaturatedPixelCount) / totalPixels;

            defectDebug.applied = vulkanRawFinalize.defectCorrectedPixelCount > 0u;
            defectDebug.correctedPixels = static_cast<int>(std::min<std::uint64_t>(
                    vulkanRawFinalize.defectCorrectedPixelCount,
                    static_cast<std::uint64_t>(std::numeric_limits<int>::max())));
            defectDebug.expensivePassSkipped = !defectDebug.applied;
            defectDebug.reason = defectDebug.applied
                    ? "vulkan_noise_aware_same_cfa_outlier_replaced_by_neighbor_median"
                    : "vulkan_no_isolated_outliers_detected";

            greenSplitDebug.applied = vulkanRawFinalize.greenSplitApplied;
            greenSplitDebug.greenEvenMedian = vulkanRawFinalize.greenEvenMedian;
            greenSplitDebug.greenOddMedian = vulkanRawFinalize.greenOddMedian;
            greenSplitDebug.greenEvenScale = vulkanRawFinalize.greenEvenScale;
            greenSplitDebug.greenOddScale = vulkanRawFinalize.greenOddScale;
            greenSplitDebug.pairedSampleCount = vulkanRawFinalize.greenPairSampleCount;
            greenSplitDebug.relativeMedian = vulkanRawFinalize.greenSplitRelativeMedian;
            greenSplitDebug.relativeMad = vulkanRawFinalize.greenSplitRelativeMad;
            greenSplitDebug.signConsensus = vulkanRawFinalize.greenSplitSignConsensus;
            greenSplitDebug.reason = vulkanRawFinalize.greenSplitReason.empty()
                    ? "green_split_evidence_not_reported"
                    : vulkanRawFinalize.greenSplitReason;
            greenSplitDebug.elapsedMs = vulkanRawFinalize.greenReductionCpuMs;

            lensDebug.applied = vulkanRawFinalize.lensShadingApplied;
            lensDebug.maxGain = vulkanRawFinalize.lensMaximumGain;
            lensDebug.overRangePct = 100.0f * static_cast<float>(vulkanRawFinalize.overRangePixelCount) /
                    std::max(1.0f, static_cast<float>(rawPixelCount));
            lensDebug.reason = lensDebug.applied
                    ? "metadata_lens_shading_map_applied_on_vulkan_resident_jpeg_mosaic"
                    : "missing_or_invalid_LensShadingMap";
        } else {
            // Failure-only recovery. When normalized RAW is still resident, materialize that
            // exact source once; never substitute a retired classical Pass1/2/3 generation.
            if (!materializeRawNormalizeForCpuFinalize()) {
                std::ostringstream failed;
                failed << "RAW_BASELINE_RENDER: RAW Finalize failed while normalized RAW remained resident; "
                       << "exact failure-recovery materialization was unavailable."
                       << " status=" << vulkanRawFinalize.status
                       << "; failure=" << vulkanRawFinalize.failureReason;
                if (debugOut != nullptr) *debugOut = failed.str();
                ISP_LOGE("%s", failed.str().c_str());
                return jpegData;
            }
            runCpuRawFinalize();
        }
    }

    // Spatial exposure is a real multiplicative RAW transform. Its global covariance effect is
    // E[g^2], so the equivalent RMS gain is sqrt(E[g^2]). Do not use ISO, exposure time or P90
    // gain as a second sensor-noise model: Camera2 S/O already describes this capture's normalized
    // sensor noise. This stage propagates only the gain that BnCam itself actually applied.
    const float spatialExposureMeanGainSquared = rawFinalizeResident
            ? vulkanRawFinalize.exposureMeanGainSquared
            : (cpuAdaptiveExposureApplied ? cpuAdaptiveExposurePlan.meanGainSquared : 1.0f);
    const float spatialExposureNoiseScale = (std::isfinite(spatialExposureMeanGainSquared) &&
                                             spatialExposureMeanGainSquared > 0.0f)
            ? std::sqrt(std::clamp(spatialExposureMeanGainSquared, 0.0625f, 16.0f))
            : 1.0f;
    if (std::abs(spatialExposureNoiseScale - 1.0f) > 1.0e-4f &&
        residualNoiseState.preDemosaic.confidence > 0.0) {
        const std::array<double, 9> exposureNoiseTransfer{
                spatialExposureNoiseScale, 0.0, 0.0,
                0.0, spatialExposureNoiseScale, 0.0,
                0.0, 0.0, spatialExposureNoiseScale
        };
        residualNoiseState.preDemosaic = bncam::spectra2::propagateLinear(
                residualNoiseState.preDemosaic,
                exposureNoiseTransfer,
                "PRE_DEMOSAIC_AFTER_SPATIAL_EXPOSURE",
                "SPATIAL_EXPOSURE_RMS_GAIN_FROM_MEAN_GAIN_SQUARED",
                0.99);
        residualNoiseState.varianceY = static_cast<float>(residualNoiseState.preDemosaic.varianceY);
        residualNoiseState.varianceRG = static_cast<float>(residualNoiseState.preDemosaic.varianceRG);
        residualNoiseState.varianceBG = static_cast<float>(residualNoiseState.preDemosaic.varianceBG);
        residualNoiseState.covarianceRgBg = static_cast<float>(residualNoiseState.preDemosaic.covarianceRgBg);
        for (std::size_t index = 0u; index < residualNoiseState.covarianceRgb.size(); ++index) {
            residualNoiseState.covarianceRgb[index] = static_cast<float>(
                    residualNoiseState.preDemosaic.covariance.values[index]);
        }
    }

    AutoDemosaicContext autoContext{};
    autoContext.captureIso = std::max(0, meta.captureSensitivityIso);
    const float autoDemosaicSigmaY = static_cast<float>(std::sqrt(
            std::max(0.0, residualNoiseState.preDemosaic.varianceY)));
    const float autoDemosaicSigmaChroma = static_cast<float>(std::sqrt(std::max({
            0.0,
            residualNoiseState.preDemosaic.varianceRG,
            residualNoiseState.preDemosaic.varianceBG})));
    const bool autoDemosaicPhysicalProfileKnown = preDemosaicPhysicalNoiseModelAvailable;
    const float autoDemosaicPhysicalPressure = isoState.modelNoisePressure;
    autoContext.physicalNoiseKnown = autoDemosaicPhysicalProfileKnown &&
            std::isfinite(autoDemosaicSigmaY) && autoDemosaicSigmaY > 0.0f &&
            std::isfinite(autoDemosaicPhysicalPressure);
    autoContext.noiseSigmaY = autoDemosaicSigmaY;
    autoContext.noiseSigmaChroma = autoDemosaicSigmaChroma;
    autoContext.physicalNoisePressure = std::clamp(autoDemosaicPhysicalPressure, 0.0f, 1.0f);
    autoContext.cfaCertain = meta.cfaPattern >= CFA_RGGB && meta.cfaPattern <= CFA_BGGR &&
                             jpegRaw.info.effectiveCfaPattern >= CFA_RGGB &&
                             jpegRaw.info.effectiveCfaPattern <= CFA_BGGR;
    autoContext.greenPlaneAnomaly = greenSplitDebug.applied;
    autoContext.focusStabilityKnown = meta.demosaicFocusStabilityKnown;
    autoContext.focusStabilityConfidence = std::clamp(meta.demosaicFocusStabilityConfidence, 0.0f, 1.0f);
    autoContext.focusSharpConfidence = std::clamp(meta.demosaicFocusSharpConfidence, 0.0f, 1.0f);
    autoContext.focusMotionRisk = std::clamp(meta.demosaicFocusMotionRisk, 0.0f, 1.0f);
    autoContext.focusVelocityDioptersPerSec = meta.demosaicFocusVelocityDioptersPerSec;
    autoContext.predictiveAfConfidence = std::clamp(meta.demosaicPredictiveAfConfidence, 0.0f, 1.0f);
    autoContext.personEvidenceKnown = meta.demosaicPersonEvidenceKnown;
    autoContext.personConfidence = std::clamp(meta.demosaicPersonConfidence, 0.0f, 1.0f);
    autoContext.detectedFaceCount = std::max(0, meta.demosaicDetectedFaceCount);
    autoContext.maxFaceCoverage = std::clamp(meta.demosaicMaxFaceCoverage, 0.0f, 1.0f);
    autoContext.temporalStabilityKnown = meta.demosaicTemporalStabilityKnown;
    autoContext.temporalStaticConfidence = std::clamp(meta.demosaicTemporalStaticConfidence, 0.0f, 1.0f);
    autoContext.temporalObserverConfidence = std::clamp(meta.demosaicTemporalObserverConfidence, 0.0f, 1.0f);
    autoContext.temporalMotionAcceptance = std::clamp(meta.demosaicTemporalMotionAcceptance, 0.0f, 1.0f);
    autoContext.temporalAcceptedPairs = std::max(0, meta.demosaicTemporalAcceptedPairs);
    autoContext.cfaChromaEvidenceKnown = demosaicCfaEvidence.available;
    autoContext.cfaStructureProtection = demosaicCfaEvidence.structureProtection;
    autoContext.cfaFineCorrectionConfidence = demosaicCfaEvidence.fineCorrectionConfidence;
    autoContext.cfaMidCorrectionConfidence = demosaicCfaEvidence.midCorrectionConfidence;
    autoContext.cfaLowCorrectionConfidence = demosaicCfaEvidence.lowCorrectionConfidence;
    // Delta 54: Auto sees the same bounded downstream-aware opponent confidence that the
    // selected demosaic will execute. Because Delta 53 is multiplicative, WB/CCM pressure
    // cannot create chroma risk when the CFA measurement itself is clean. This remains a soft
    // score input only; there is no algorithm hard-route.
    autoContext.cfaRedOpponentCorrectionConfidence =
            demosaicExecutionCfaEvidence.redOpponentCorrectionConfidence;
    autoContext.cfaBlueOpponentCorrectionConfidence =
            demosaicExecutionCfaEvidence.blueOpponentCorrectionConfidence;
    autoContext.memoryPressureHigh =
            static_cast<uint64_t>(rawPixelCount) * 29u > 480u * 1024u * 1024u;
    DemosaicResolution demosaicResolution{};
    if (autoDemosaicRequested && rawFinalizeResident && vulkanRawFinalize.autoSceneMetricsReady) {
        AutoDemosaicSceneMetrics metrics{};
        metrics.valid = true;
        metrics.sampleCount = static_cast<int>(std::min<std::uint64_t>(
                vulkanRawFinalize.autoSceneSampleCount,
                static_cast<std::uint64_t>(std::numeric_limits<int>::max())));
        metrics.medianSignal = vulkanRawFinalize.autoSceneMedianSignal;
        metrics.meanGradient = vulkanRawFinalize.autoSceneMeanGradient;
        metrics.p90Gradient = vulkanRawFinalize.autoSceneP90Gradient;
        metrics.edgeFraction = vulkanRawFinalize.autoSceneEdgeFraction;
        metrics.coherentEdgeFraction = vulkanRawFinalize.autoSceneCoherentEdgeFraction;
        metrics.lowSignalFraction = vulkanRawFinalize.autoSceneLowSignalFraction;
        demosaicResolution = resolveDemosaicForSceneMetrics(
                meta.requestedDemosaicMode, metrics, autoContext);
    } else {
        if (jpegRaw.mosaic.empty() && !ensureCpuWorkingRaw("AUTO_DEMOSAIC_GPU_METRICS_UNAVAILABLE")) {
            if (debugOut != nullptr) *debugOut = "RAW_BASELINE_RENDER: Auto demosaic metrics unavailable and CPU failsafe could not materialize RAW";
            return jpegData;
        }
        demosaicResolution = resolveDemosaicForFrame(
                meta.requestedDemosaicMode, jpegRaw.mosaic, autoContext);
    }
    if (meta.demosaicFallbackOccurred) {
        demosaicResolution.fallbackOccurred = true;
        demosaicResolution.fallbackReason = meta.demosaicFallbackReason;
    }

    // Delta 39: Bilinear and Menon remain compiled only as reference/validation kernels.
    // The product ISP must never execute them. If stale native state somehow produces one of
    // those retired algorithm values, preserve the historical bridge intent and hard-route it
    // to the corresponding BnCam Inspired replacement before any CPU/GPU dispatch occurs.
    if (demosaicResolution.algorithm == DemosaicAlgorithm::Bilinear) {
        demosaicResolution.algorithm = DemosaicAlgorithm::RcdInspired;
        demosaicResolution.fallbackOccurred = true;
        demosaicResolution.fallbackReason =
                "retired_bilinear_execution_hardened_to_rcd_inspired";
        demosaicResolution.reason = "legacy_bilinear_algorithm_retired_to_rcd_inspired";
    } else if (demosaicResolution.algorithm == DemosaicAlgorithm::Menon2007) {
        demosaicResolution.algorithm = DemosaicAlgorithm::AmazeInspired;
        demosaicResolution.fallbackOccurred = true;
        demosaicResolution.fallbackReason =
                "retired_menon_execution_hardened_to_amaze_inspired";
        demosaicResolution.reason = "legacy_menon_algorithm_retired_to_amaze_inspired";
    }

    DemosaicRunStats demosaicRunStats{};
    const auto demosaicStart = IspClock::now();
    cv::Mat linearRgb;
    bool vulkanDemosaicResident = false;
    bncam::vulkan::SpectraResidentDemosaicResult vulkanDemosaic{};
    const auto materializeRawFinalizeResidentForCpuDemosaic = [&]() -> bool {
        if (!rawFinalizeResident || vulkanRawFinalize.residentOutputGeneration == 0u) {
            return true;
        }
        std::vector<float> materialized;
        if (!bncam::vulkan::VulkanRuntime::instance().readbackSpectraRawFinalizeResident(
                    vulkanRawFinalize.residentOutputGeneration, materialized) ||
            materialized.size() != rawPixelCount) {
            return false;
        }
        ++spectraFullFrameCpuReadbacks;
        cv::Mat hostMosaic(rawHeight, rawWidth, CV_32FC1);
        std::memcpy(hostMosaic.ptr<float>(0), materialized.data(),
                    materialized.size() * sizeof(float));
        jpegRaw.mosaic = std::move(hostMosaic);
        residentCpuFallbackUsed = true;
        residentCpuFallbackReason = "DEMOSAIC_VULKAN_FAILED_RAW_FINALIZE_RECOVERED";
        jpegRawCpuFinalized = true;
        rawFinalizeResident = false;
        demosaicFailureRawFinalizeMaterialized = true;
        return true;
    };
    // Phase 5: noise-aware demosaic authority is physical-or-zero. A residual estimate that
    // does not originate from a valid applied S/O profile must not silently become a second
    // ISO/fallback noise authority inside Malvar/RCD/AMAZE.
    const bool demosaicPhysicalNoiseContextAvailable = autoContext.physicalNoiseKnown &&
            residualNoiseState.preDemosaic.confidence > 0.0;
    const float demosaicPhysicalSigmaY = demosaicPhysicalNoiseContextAvailable
            ? autoContext.noiseSigmaY : 0.0f;
    const float demosaicPhysicalSigmaChroma = demosaicPhysicalNoiseContextAvailable
            ? autoContext.noiseSigmaChroma : 0.0f;
    const float demosaicPhysicalNoisePressure = demosaicPhysicalNoiseContextAvailable
            ? autoContext.physicalNoisePressure : 0.0f;
    const DemosaicNoiseContext demosaicNoiseContext{
            demosaicPhysicalNoiseContextAvailable,
            demosaicPhysicalSigmaY,
            demosaicPhysicalSigmaChroma,
            demosaicPhysicalNoisePressure
    };
    // N006B: the legacy Phase-6 residual-chroma pixel owner is physically retired.
    // Physical sigma remains available only as read-only demosaic context.
    constexpr bool phase6ResidualChromaPlanEnabled = false;
    constexpr const char* phase6ResidualChromaPlanStatus =
            "RETIRED_CLASSICAL_PIXEL_OWNER_NEURAL_PENDING";
    constexpr bool phase6CpuFallbackApplied = false;


    const auto runCpuDemosaicFallback = [&]() -> cv::Mat {
        // Failure-only materialization path. Normal Vulkan execution never runs these full-frame
        // CPU RAW corrections in parallel with the resident finalizer. physical-luma parity is applied
        // to this CPU reference only when the GPU reconstruction path itself has failed.
        runCpuRawFinalize();
        cv::Mat cpuRgb;
        switch (demosaicResolution.algorithm) {
            case DemosaicAlgorithm::RcdInspired:
                cpuRgb = demosaicRcdInspiredToRgb32f(
                        jpegRaw.mosaic, jpegRaw.info.effectiveCfaPattern, &demosaicRunStats,
                        &demosaicExecutionCfaEvidence, &demosaicNoiseContext);
                break;
            case DemosaicAlgorithm::AmazeInspired:
                cpuRgb = demosaicAmazeInspiredToRgb32f(
                        jpegRaw.mosaic, jpegRaw.info.effectiveCfaPattern, &demosaicRunStats,
                        &demosaicExecutionCfaEvidence, &demosaicNoiseContext);
                break;
            case DemosaicAlgorithm::Bilinear:
                cpuRgb = demosaicBilinearToRgb32f(
                        jpegRaw.mosaic, jpegRaw.info.effectiveCfaPattern, &demosaicRunStats);
                break;
            case DemosaicAlgorithm::Menon2007:
                cpuRgb = demosaicMenon2007ToRgb32f(
                        jpegRaw.mosaic, jpegRaw.info.effectiveCfaPattern, &demosaicRunStats);
                break;
            case DemosaicAlgorithm::Malvar2004:
            default:
                cpuRgb = demosaicMalvar2004ToRgb32f(
                        jpegRaw.mosaic, jpegRaw.info.effectiveCfaPattern, &demosaicRunStats,
                        &demosaicExecutionCfaEvidence, &demosaicNoiseContext);
                break;
        }
        return cpuRgb;
    };
    {
        bncam::vulkan::SpectraResidentDemosaicRequest request{};
        request.mosaicData = jpegRaw.mosaic.empty() ? nullptr : jpegRaw.mosaic.ptr<float>(0);
        request.frameWidth = static_cast<std::uint32_t>(rawWidth);
        request.frameHeight = static_cast<std::uint32_t>(rawHeight);
        request.rowStrideFloats = jpegRaw.mosaic.empty()
                ? static_cast<std::size_t>(rawWidth) : jpegRaw.mosaic.step1();
        request.cfaPattern = static_cast<std::uint32_t>(
                cfaPatternOrDefault(jpegRaw.info.effectiveCfaPattern));
        request.cfaEvidenceAvailable = demosaicExecutionCfaEvidence.available ? 1.0f : 0.0f;
        request.cfaCommonOpponentSupport = demosaicExecutionCfaEvidence.commonOpponentSupport;
        request.cfaStructureProtection = demosaicExecutionCfaEvidence.structureProtection;
        request.cfaFineCorrectionConfidence = demosaicExecutionCfaEvidence.fineCorrectionConfidence;
        request.cfaMidCorrectionConfidence = demosaicExecutionCfaEvidence.midCorrectionConfidence;
        request.cfaLowCorrectionConfidence = demosaicExecutionCfaEvidence.lowCorrectionConfidence;
        request.cfaRedOpponentCorrectionConfidence =
                demosaicExecutionCfaEvidence.redOpponentCorrectionConfidence;
        request.cfaBlueOpponentCorrectionConfidence =
                demosaicExecutionCfaEvidence.blueOpponentCorrectionConfidence;
        request.noiseSigmaY = demosaicNoiseContext.sigmaY;
        request.noiseSigmaChroma = demosaicNoiseContext.sigmaChroma;
        request.noisePressure = demosaicNoiseContext.pressure;
        request.phase6ResidualChromaEnabled = false;
        request.phase6MaximumBlend = 0.0f;
        request.phase6MaximumCorrection = 0.0f;
        request.autoMalvarPrior = demosaicResolution.autoMalvarPrior;
        request.autoNeuralJddPrior = demosaicResolution.autoNeuralJddPrior;
        request.autoAmazePrior = demosaicResolution.autoAmazePrior;
        if (demosaicResolution.autoHybridExecution) {
            request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::AUTO_HYBRID;
        } else switch (demosaicResolution.algorithm) {
            case DemosaicAlgorithm::RcdInspired:
                request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::RCD_INSPIRED;
                break;
            case DemosaicAlgorithm::AmazeInspired:
                request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::AMAZE_INSPIRED;
                break;
            case DemosaicAlgorithm::Bilinear:
                request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::BILINEAR;
                break;
            case DemosaicAlgorithm::Menon2007:
                request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::MENON_2007;
                break;
            case DemosaicAlgorithm::Malvar2004:
            default:
                request.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::MALVAR_2004;
                break;
        }
        vulkanDemosaic = rawFinalizeResident
                ? bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentDemosaicFromRawFinalize(
                        request, vulkanRawFinalize.residentOutputGeneration)
                : bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentDemosaic(request);
        if (vulkanDemosaic.success && vulkanDemosaic.gpuUsedForOutput &&
            vulkanDemosaic.residentDemosaicGeneration != 0u) {
            // 8H-F: a successful Vulkan demosaic stays device-resident. Only compact residual
            // candidates cross back to the CPU; the ~150 MB full RGB readback is deferred until
            // after AWB+CCM (and disappears entirely once the remaining downstream graph is resident).
            vulkanDemosaicResident = true;
            demosaicRunStats.setupMs = vulkanDemosaic.inputPackingMs;
            demosaicRunStats.kernelMs = vulkanDemosaic.kernelMs;
            demosaicRunStats.finalizeMs = vulkanDemosaic.readbackMs;
            demosaicRunStats.allocationReuse = vulkanDemosaic.persistentBufferReuseHit;
            demosaicRunStats.workingBufferBytesEstimate = vulkanDemosaic.persistentResidentBytes;
            demosaicRunStats.allocatedScratchBytesThisShot =
                    vulkanDemosaic.persistentBufferReallocated
                    ? vulkanDemosaic.persistentResidentBytes : 0u;
        }
    }
    if (!vulkanDemosaicResident && linearRgb.empty()) {
        // CPU is a typed reference/fallback only. If RAW Finalize succeeded resident but
        // demosaic failed, first recover that exact finalized generation. Never demosaic
        // the older host mosaic and never rerun RAW Finalize on an already-finalized image.
        if (!materializeRawFinalizeResidentForCpuDemosaic()) {
            std::ostringstream failed;
            failed << "RAW_BASELINE_RENDER: resident RAW Finalize succeeded but Vulkan demosaic failed; "
                   << "exact finalized RAW recovery was unavailable."
                   << " demosaicStatus=" << vulkanDemosaic.status
                   << "; demosaicFailure=" << vulkanDemosaic.failureReason;
            if (debugOut != nullptr) *debugOut = failed.str();
            ISP_LOGE("%s", failed.str().c_str());
            return jpegData;
        }
        linearRgb = runCpuDemosaicFallback();
    }
    const float demosaicMs = elapsedMs(demosaicStart);
    const bool autoHybridUsedForOutput = demosaicResolution.autoHybridExecution && vulkanDemosaicResident;
    if (demosaicResolution.autoHybridExecution && !autoHybridUsedForOutput) {
        demosaicResolution.fallbackOccurred = true;
        demosaicResolution.fallbackReason = "auto_hybrid_gpu_failed_typed_single_route_cpu_fallback";
    }
    if (demosaicResolution.algorithm == DemosaicAlgorithm::Menon2007 && !vulkanDemosaic.gpuUsedForOutput) {
        recordMenonDemosaicTimeMs(demosaicMs);
    }
    const auto demosaicPropagationStart = IspClock::now();
    residualNoiseState.postDemosaic = autoHybridUsedForOutput
            ? bncam::spectra2::propagateAutoHybridDemosaic(
                    residualNoiseState.preDemosaic,
                    demosaicResolution.autoMalvarPrior,
                    demosaicResolution.autoNeuralJddPrior,
                    demosaicResolution.autoAmazePrior,
                    "POST_DEMOSAIC_RGB")
            : bncam::spectra2::propagateDemosaic(
                    residualNoiseState.preDemosaic,
                    resolvedDemosaicNoiseModel(demosaicResolution.algorithm),
                    "POST_DEMOSAIC_RGB");
    residualNoiseState.demosaicPropagationMs = elapsedMs(demosaicPropagationStart);
    const auto measuredPostDemosaicStart = IspClock::now();
    residualNoiseState.measuredPostDemosaic = vulkanDemosaicResident
            ? measureLinearResidualGpuCandidates(
                    vulkanDemosaic.residualCandidates, "POST_DEMOSAIC_RGB")
            : measureLinearResidualFlatRegions(linearRgb, "POST_DEMOSAIC_RGB");
    residualNoiseState.measuredPostDemosaicResidualMs = elapsedMs(measuredPostDemosaicStart);
    residualNoiseState.postDemosaicCalibration =
            bncam::spectra2::comparePredictionToObservation(
                    residualNoiseState.postDemosaic,
                    residualNoiseState.measuredPostDemosaic
            );

    // Delta 18: explicit demosaic chroma-amplification audit. This intentionally reuses the
    // already-computed SPECTRA states/flat-region observation and therefore adds no extra image
    // scan or resident readback. The pre-demosaic side is modelled/predicted; the post-demosaic
    // side is both predicted and measured, so the debug labels keep that distinction explicit.
    const double demosaicPreVarianceRg =
            std::max(0.0, residualNoiseState.preDemosaic.varianceRG);
    const double demosaicPreVarianceBg =
            std::max(0.0, residualNoiseState.preDemosaic.varianceBG);
    const double demosaicPredictedPostVarianceRg =
            std::max(0.0, residualNoiseState.postDemosaic.varianceRG);
    const double demosaicPredictedPostVarianceBg =
            std::max(0.0, residualNoiseState.postDemosaic.varianceBG);
    const double demosaicMeasuredPostVarianceRg =
            std::max(0.0, residualNoiseState.measuredPostDemosaic.robustVarianceRG);
    const double demosaicMeasuredPostVarianceBg =
            std::max(0.0, residualNoiseState.measuredPostDemosaic.robustVarianceBG);
    const double demosaicPreChromaVariance =
            0.5 * (demosaicPreVarianceRg + demosaicPreVarianceBg);
    const double demosaicPredictedPostChromaVariance =
            0.5 * (demosaicPredictedPostVarianceRg + demosaicPredictedPostVarianceBg);
    const double demosaicMeasuredPostChromaVariance =
            0.5 * (demosaicMeasuredPostVarianceRg + demosaicMeasuredPostVarianceBg);
    const bool demosaicChromaAuditReady =
            demosaicPreChromaVariance > 1.0e-12 &&
            residualNoiseState.measuredPostDemosaic.acceptedSampleCount >= 2048;
    const auto demosaicSafeRatio = [](double numerator, double denominator) -> double {
        return denominator > 1.0e-12 ? numerator / denominator : -1.0;
    };
    const auto demosaicSafeRmsRatio = [&](double numerator, double denominator) -> double {
        const double varianceRatio = demosaicSafeRatio(numerator, denominator);
        return varianceRatio >= 0.0 ? std::sqrt(varianceRatio) : -1.0;
    };
    const double demosaicPredictedRmsGain = demosaicSafeRmsRatio(
            demosaicPredictedPostChromaVariance, demosaicPreChromaVariance);
    const double demosaicMeasuredPostToPredictedPreRmsRatio = demosaicChromaAuditReady
            ? demosaicSafeRmsRatio(demosaicMeasuredPostChromaVariance, demosaicPreChromaVariance)
            : -1.0;
    const double demosaicMeasuredToPredictedPostRmsRatio = demosaicChromaAuditReady
            ? demosaicSafeRmsRatio(
                    demosaicMeasuredPostChromaVariance, demosaicPredictedPostChromaVariance)
            : -1.0;
    const bool demosaicLowFrequencyChromaFieldReady =
            residualNoiseState.measuredPostDemosaic.lowFrequencyChromaValidTileCount >= 6u &&
            residualNoiseState.measuredPostDemosaic.lowFrequencyChromaNeighbourPairCount >= 4u;

    // Delta 30: calibrate the existing downstream visible-chroma stage with the measured
    // post-demosaic residual, without adding another filter or image scan. Only under-prediction
    // can increase authority; a lower measured residual never weakens the existing plan.
    residualNoiseState.demosaicMeasuredChromaCalibrationReady =
            demosaicChromaAuditReady && demosaicMeasuredToPredictedPostRmsRatio > 0.0;
    residualNoiseState.demosaicMeasuredToPredictedPostChromaRmsRatio =
            residualNoiseState.demosaicMeasuredChromaCalibrationReady
                    ? static_cast<float>(demosaicMeasuredToPredictedPostRmsRatio)
                    : 1.0f;
    residualNoiseState.demosaicMeasuredChromaAuthorityPressure =
            residualNoiseState.demosaicMeasuredChromaCalibrationReady
                    ? std::clamp(
                            static_cast<float>(demosaicMeasuredToPredictedPostRmsRatio),
                            1.0f,
                            1.50f
                    )
                    : 1.0f;

    // Delta 41: classify the measured coarse post-demosaic opponent field without mutating it.
    // A smooth colour field alone is not noise. Cloud evidence can only become non-zero when
    // the pre-demosaic low-frequency SPECTRA evidence already supports an R-G/B-G residual.
    const double measuredPostChromaRms = std::sqrt(std::max(
            0.0, demosaicMeasuredPostChromaVariance));
    const double lowFieldRmsP90 = std::sqrt(std::max(
            0.0, residualNoiseState.measuredPostDemosaic.lowFrequencyChromaFieldEnergyP90));
    const double lowNeighbourRmsP50 = std::sqrt(std::max(
            0.0, residualNoiseState.measuredPostDemosaic.lowFrequencyChromaNeighbourEnergyP50));
    residualNoiseState.demosaicChromaCloudClassificationReady =
            demosaicLowFrequencyChromaFieldReady && measuredPostChromaRms > 1.0e-9;
    if (residualNoiseState.demosaicChromaCloudClassificationReady) {
        residualNoiseState.demosaicChromaFieldToResidualRmsRatio = static_cast<float>(
                lowFieldRmsP90 / std::max(1.0e-9, measuredPostChromaRms));
        residualNoiseState.demosaicChromaFieldCoherence = std::clamp(
                1.0f - static_cast<float>(
                        lowNeighbourRmsP50 / std::max(1.0e-9, lowFieldRmsP90)),
                0.0f,
                1.0f
        );
        residualNoiseState.demosaicUnexpectedChromaAmplification =
                residualNoiseState.demosaicMeasuredChromaCalibrationReady
                        ? std::clamp(
                                (residualNoiseState.demosaicMeasuredToPredictedPostChromaRmsRatio -
                                        1.0f) / 0.50f,
                                0.0f,
                                1.0f
                        )
                        : 0.0f;
        residualNoiseState.demosaicPreLowFrequencyChromaSupport = std::max(
                cfaChromaConfidence.redLowCorrectionConfidence,
                cfaChromaConfidence.blueLowCorrectionConfidence
        );
        const float fieldExcess = std::clamp(
                (residualNoiseState.demosaicChromaFieldToResidualRmsRatio - 0.75f) / 1.25f,
                0.0f,
                1.0f
        );
        const float structureRelief = 1.0f - 0.35f * std::clamp(
                cfaChromaConfidence.structureProtection, 0.0f, 1.0f);
        residualNoiseState.demosaicChromaCloudRiskEvidence = std::clamp(
                residualNoiseState.demosaicPreLowFrequencyChromaSupport * structureRelief *
                        (0.45f * fieldExcess +
                         0.30f * residualNoiseState.demosaicChromaFieldCoherence +
                         0.25f * residualNoiseState.demosaicUnexpectedChromaAmplification),
                0.0f,
                1.0f
        );
        if (residualNoiseState.demosaicPreLowFrequencyChromaSupport <= 1.0e-6f) {
            residualNoiseState.demosaicChromaCloudRiskStatus =
                    "FIELD_PRESENT_BUT_NO_PRE_DEMOSAIC_LOW_CHROMA_SUPPORT";
        } else if (residualNoiseState.demosaicChromaCloudRiskEvidence >= 0.40f) {
            residualNoiseState.demosaicChromaCloudRiskStatus =
                    "SUPPORTED_COHERENT_CHROMA_CLOUD_RISK";
        } else if (residualNoiseState.demosaicChromaCloudRiskEvidence >= 0.15f) {
            residualNoiseState.demosaicChromaCloudRiskStatus =
                    "POSSIBLE_COHERENT_CHROMA_FIELD_RISK";
        } else {
            residualNoiseState.demosaicChromaCloudRiskStatus =
                    "LOW_CHROMA_CLOUD_EVIDENCE";
        }
    } else {
        residualNoiseState.demosaicChromaCloudRiskStatus =
                "INSUFFICIENT_POST_DEMOSAIC_FIELD_SUPPORT";
    }

    // Phase 7: Camera2 AWB metadata is an anchor/prior, not the final WB answer. Normalize it
    // relative to green exactly once, then allow the optional bounded phone CCT signal to shape
    // that prior before scene evidence is evaluated. No full-resolution CPU scan is introduced:
    // normal Vulkan execution reuses the compact post-demosaic GPU sample grid already read back
    // for residual observability. CPU sampling exists only on the typed demosaic failure route.
    const float wbMetadata[4] = {
            safeWbGain(meta.calibration.effectiveWbGains[0]),
            safeWbGain(meta.calibration.effectiveWbGains[1]),
            safeWbGain(meta.calibration.effectiveWbGains[2]),
            safeWbGain(meta.calibration.effectiveWbGains[3])
    };
    const float greenReference = std::max(1.0e-4f, 0.5f * (wbMetadata[1] + wbMetadata[2]));
    float wbPriorRgb[3] = {
            wbMetadata[0] / greenReference,
            1.0f,
            wbMetadata[3] / greenReference
    };
    // ImageUtils sets wbFromMetadata only when the selected frame supplies both the exact
    // COLOR_CORRECTION_GAINS and exact COLOR_CORRECTION_TRANSFORM. Keep that coupled solution
    // as the strong System-AWB anchor; sufficiently supported physical scene evidence may only
    // apply a bounded gain refinement while the exact Camera2 colour transform remains intact.
    const bool exactCamera2ColorPair = uiConfig.wbFromMetadata && uiConfig.colorMatrixFromMetadata;

    float nativeAppliedContributionWeight = 0.0f;
    std::string nativeAdjustmentType = "none";
    float nativeAdjustmentMagnitude = 0.0f;

    const float rawContributionWeight = std::clamp(meta.auxContributionWeight, 0.0f, 0.15f);
    if (!exactCamera2ColorPair && meta.phoneAssistanceSensorsEnabled && meta.auxSensorValid &&
        rawContributionWeight > 0.001f && meta.auxCctKelvin >= 1500.0f && meta.auxCctKelvin <= 12000.0f) {
        const float cctRatio = std::clamp(5500.0f / meta.auxCctKelvin, 0.60f, 1.80f);
        const float sensorWbRScale = 1.0f + (cctRatio - 1.0f) * 0.15f * rawContributionWeight;
        const float sensorWbBScale = 1.0f + (1.0f / cctRatio - 1.0f) * 0.15f * rawContributionWeight;
        wbPriorRgb[0] *= std::clamp(sensorWbRScale, 0.85f, 1.15f);
        wbPriorRgb[2] *= std::clamp(sensorWbBScale, 0.85f, 1.15f);
        nativeAppliedContributionWeight = rawContributionWeight;
        nativeAdjustmentType = "bounded_cct_prior_shaping_before_phase7_awb";
        nativeAdjustmentMagnitude = std::abs(cctRatio - 1.0f) * 0.15f * rawContributionWeight;
    }

    const auto phase7AwbStart = IspClock::now();
    std::vector<bncam::awb::LinearOpponentSample> phase7AwbSamples;
    std::string phase7AwbSampleSource;
    if (vulkanDemosaicResident) {
        phase7AwbSamples = bncam::awb::decodeGpuResidualCandidates(vulkanDemosaic.residualCandidates);
        phase7AwbSampleSource = phase7AwbSamples.empty()
                ? "GPU_COMPACT_UNAVAILABLE_PRIOR_ONLY"
                : "GPU_COMPACT_POST_DEMOSAIC";
    } else {
        phase7AwbSamples = phase7AwbSamplesFromCpuFailureRgb(linearRgb);
        phase7AwbSampleSource = phase7AwbSamples.empty()
                ? "CPU_FAILURE_REFERENCE_UNAVAILABLE_PRIOR_ONLY"
                : "CPU_FAILURE_REFERENCE_SPARSE";
    }
    bncam::awb::Estimate phase7AwbEstimate = bncam::awb::resolve(
            phase7AwbSamples,
            {static_cast<double>(wbPriorRgb[0]), 1.0, static_cast<double>(wbPriorRgb[2])},
            static_cast<double>(demosaicNoiseContext.sigmaY));
    if (!vulkanDemosaicResident) {
        phase7AwbEstimate.method = "PHYSICAL_AWB_CPU_FAILURE_REFERENCE_GRAY_WORLD_V1";
    }
    if (exactCamera2ColorPair) {
        // Exact Camera2 System-AWB remains the anchor, but must not permanently suppress the
        // physical estimator when compact scene evidence is genuinely supported. The estimator's
        // own confidence/mixed-light gates remain authoritative and are capped again here because
        // the Camera2 gain+matrix pair is already a coherent per-frame colour solution.
        const bool physicalRefinementSupported =
                phase7AwbEstimate.dataReady &&
                std::isfinite(phase7AwbEstimate.confidence) &&
                std::isfinite(phase7AwbEstimate.dataAuthority) &&
                phase7AwbEstimate.confidence >= 0.12 &&
                phase7AwbEstimate.dataAuthority >= 0.10;
        if (physicalRefinementSupported) {
            const double exactPairAuthorityCap = phase7AwbEstimate.mixedIllumination ? 0.18 : 0.35;
            const double boundedAuthority = std::clamp(
                    phase7AwbEstimate.dataAuthority, 0.0, exactPairAuthorityCap);
            const auto blendGainLog = [boundedAuthority](double prior, double data) -> double {
                const double safePrior = std::clamp(
                        std::isfinite(prior) ? prior : 1.0, 0.35, 3.50);
                const double safeData = std::clamp(
                        std::isfinite(data) ? data : safePrior, 0.35, 3.50);
                return std::clamp(
                        std::exp(std::log(safePrior) + boundedAuthority *
                                (std::log(safeData) - std::log(safePrior))),
                        0.35, 3.50);
            };
            phase7AwbEstimate.finalGainsRgb[0] = blendGainLog(
                    phase7AwbEstimate.priorGainsRgb[0], phase7AwbEstimate.dataGainsRgb[0]);
            phase7AwbEstimate.finalGainsRgb[1] = 1.0;
            phase7AwbEstimate.finalGainsRgb[2] = blendGainLog(
                    phase7AwbEstimate.priorGainsRgb[2], phase7AwbEstimate.dataGainsRgb[2]);
            phase7AwbEstimate.dataAuthority = boundedAuthority;
            phase7AwbEstimate.method = "CAMERA2_EXACT_FRAME_PAIR_BOUNDED_PHYSICAL_REFINE";
            phase7AwbEstimate.status = "READY_EXACT_CAMERA2_PAIR_PHYSICAL_REFINE";
        } else {
            phase7AwbEstimate.finalGainsRgb[0] = phase7AwbEstimate.priorGainsRgb[0];
            phase7AwbEstimate.finalGainsRgb[1] = 1.0;
            phase7AwbEstimate.finalGainsRgb[2] = phase7AwbEstimate.priorGainsRgb[2];
            phase7AwbEstimate.dataAuthority = 0.0;
            phase7AwbEstimate.method = "CAMERA2_EXACT_FRAME_PAIR_LOW_CONFIDENCE_PRIOR_ONLY";
            phase7AwbEstimate.status = "READY_EXACT_CAMERA2_PAIR_PRIOR_ONLY";
        }
        // These native fields describe the optional phone assistance sensor, not physical AWB.
        nativeAppliedContributionWeight = 0.0f;
        nativeAdjustmentType = "none_exact_camera2_color_pair";
        nativeAdjustmentMagnitude = 0.0f;
    }
    float wbRgb[3] = {
            static_cast<float>(phase7AwbEstimate.finalGainsRgb[0]),
            1.0f,
            static_cast<float>(phase7AwbEstimate.finalGainsRgb[2])
    };
    const float phase7AwbEstimatorMs = elapsedMs(phase7AwbStart);

    const float* camera2Ccm = meta.calibration.effectiveColorMatrix;
    std::array<float, 9> activeColorMatrix{};
    for (std::size_t i = 0; i < activeColorMatrix.size(); ++i) {
        activeColorMatrix[i] = camera2Ccm[i];
    }

    // Phase 2: Android's public RAW characterization (reference illuminant + ColorMatrix +
    // CameraCalibration + ForwardMatrix) is the primary physical camera-colour owner when it
    // matches the current RAW route. ProfileHueSatMap is independent, optional profile data: when
    // a genuine table exists it augments the same owner, but its absence must not invalidate the
    // ForwardMatrix path. The Camera2 per-frame CCM and legacy OKLab presentation are never stacked
    // underneath an active calibrated matrix profile. A configured profile that cannot resolve its
    // paired PCS transform fails closed instead of silently falling back to a second colour owner.
    const bncam::color::RawCameraProfileRegistrySnapshot calibratedProfileRegistry =
            bncam::color::RawCameraColorProfileRegistry::instance().snapshot();
    const bncam::color::RawCameraColorProfileResolution calibratedProfileResolution =
            bncam::color::resolveRawCameraColorProfile(
                    calibratedProfileRegistry,
                    {activeColorMatrix, {wbRgb[0], wbRgb[1], wbRgb[2]}});
    const bncam::color::RawCameraNativeHueSatProfile* calibratedProfile = nullptr;
    if (calibratedProfileResolution.profileIndex < calibratedProfileRegistry.profiles.size()) {
        calibratedProfile = &calibratedProfileRegistry.profiles[calibratedProfileResolution.profileIndex];
    }
    const bool calibratedProfileConfiguredForCurrentRoute = calibratedProfile != nullptr;
    bncam::color::RawCameraDngForwardTransformResult calibratedForwardTransform{};
    if (calibratedProfileConfiguredForCurrentRoute && calibratedProfileResolution.ready) {
        calibratedForwardTransform = bncam::color::resolveRawCameraDngForwardTransform({
                calibratedProfile,
                calibratedProfileResolution.hueSatWeightFirst,
                calibratedProfileResolution.hueSatWeightSecond,
                {wbRgb[0], wbRgb[1], wbRgb[2]}
        });
    } else {
        calibratedForwardTransform.status = calibratedProfileConfiguredForCurrentRoute
                ? "PROFILE_RESOLUTION_NOT_READY"
                : "NO_MATCHING_PROFILE_FOR_CURRENT_ROUTE";
    }
    const bool calibratedHueSatMapAvailable =
            calibratedProfile != nullptr && calibratedProfile->hasHueSatMap();
    const bncam::color::RawCameraColorCharacterizationPlan cameraCharacterizationPlan =
            bncam::color::resolveRawCameraColorCharacterizationOwnership({
                    calibratedProfileConfiguredForCurrentRoute,
                    calibratedProfileResolution.ready,
                    calibratedProfile != nullptr && calibratedProfile->valid(),
                    calibratedForwardTransform.ready,
                    calibratedHueSatMapAvailable,
                    calibratedHueSatMapAvailable && calibratedProfile != nullptr && calibratedProfile->valid(),
                    true
            });
    const bool calibratedMatrixProfileActive = cameraCharacterizationPlan.calibratedMatrixApply;
    const bool calibratedHueSatMapActive = cameraCharacterizationPlan.calibratedHueSatMapApply;
    if (calibratedMatrixProfileActive) {
        activeColorMatrix = calibratedForwardTransform.postWbToLinearSrgb;
    }
    const bncam::color::RawCameraHueSatMapTelemetrySummary hueSatMapTelemetry =
            bncam::color::summarizeRawCameraHueSatMapProfile(
                    calibratedProfile,
                    calibratedProfileResolution.hueSatWeightFirst,
                    calibratedProfileResolution.hueSatWeightSecond);
    const bool phase2CameraMatrixInterpolated = calibratedMatrixProfileActive &&
            calibratedProfile != nullptr && calibratedProfile->dualCharacterization() &&
            calibratedProfileResolution.hueSatWeightFirst > 1.0e-5f &&
            calibratedProfileResolution.hueSatWeightSecond > 1.0e-5f;
    const char* phase2CameraMatrixSource = calibratedMatrixProfileActive
            ? "PAIRED_DNG_FORWARD_MATRIX_XYZ_D50"
            : (exactCamera2ColorPair
                    ? "CAMERA2_EXACT_FRAME_COLOR_CORRECTION_TRANSFORM"
                    : "RESOLVED_SENSOR_COLOR_MATRIX_FALLBACK");
    const char* phase2ColorWorkingSpace = calibratedHueSatMapActive
            ? "DNG_XYZ_D50_TO_LINEAR_RIMM_HSV_TO_SCENE_LINEAR_SRGB"
            : (calibratedMatrixProfileActive
                    ? "SCENE_LINEAR_SRGB_DNG_FORWARD_MATRIX_NO_HUESATMAP"
                    : "SCENE_LINEAR_SRGB_NO_CALIBRATED_PROFILE");
    const float* ccm = activeColorMatrix.data();

    const auto makeCpuHueSatMap = [&](const std::vector<float>& data,
                                      bncam::color::RawCameraHueSatMapSource source) {
        bncam::color::RawCameraHueSatMap map{};
        if (calibratedProfile == nullptr || data.empty()) return map;
        map.hueDivisions = calibratedProfile->hueDivisions;
        map.saturationDivisions = calibratedProfile->saturationDivisions;
        map.valueDivisions = calibratedProfile->valueDivisions;
        map.source = source;
        map.sourceId = calibratedProfile->profileId;
        map.trustedCalibration = true;
        map.encoding = calibratedProfile->encoding == 1
                ? bncam::color::RawCameraHueSatMapEncoding::SRGB
                : bncam::color::RawCameraHueSatMapEncoding::LINEAR;
        map.dynamicRange = bncam::color::RawCameraHueSatMapDynamicRange::SDR;
        const std::size_t entryCount = data.size() / 3u;
        map.entries.reserve(entryCount);
        for (std::size_t i = 0; i < entryCount; ++i) {
            const std::size_t base = i * 3u;
            map.entries.push_back({data[base], data[base + 1u], data[base + 2u]});
        }
        return map;
    };
    bncam::color::RawCameraHueSatMap calibratedHueSatMapCpu{};
    if (calibratedHueSatMapActive && calibratedProfile != nullptr) {
        const auto firstMap = makeCpuHueSatMap(
                calibratedProfile->hueSatData1,
                bncam::color::RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
        if (!calibratedProfile->hueSatData2.empty()) {
            const auto secondMap = makeCpuHueSatMap(
                    calibratedProfile->hueSatData2,
                    bncam::color::RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
            calibratedHueSatMapCpu = bncam::color::rawCameraBlendHueSatMaps(
                    firstMap, &secondMap, calibratedProfileResolution.hueSatWeightFirst);
            calibratedHueSatMapCpu.sourceId = calibratedProfile->profileId;
            calibratedHueSatMapCpu.trustedCalibration = true;
        } else {
            calibratedHueSatMapCpu = firstMap;
        }
    }
    const bool calibratedHueSatMapCpuReady = calibratedHueSatMapActive &&
            calibratedHueSatMapCpu.productEligible() &&
            calibratedHueSatMapCpu.directReferenceApplicationSupported();

    // Delta 50: direction-resolved downstream opponent amplification audit. A unit pre-WB
    // R-G error is represented by an R-only perturbation (G fixed); B-G analogously uses B.
    // The gain is normalized so identity CCM + unity WB equals 1.0 for either direction.
    const double wbCcmRedOpponentDirectionalGain = std::max(0.0,
            static_cast<double>(wbRgb[0]) * std::sqrt(
                    std::pow(static_cast<double>(ccm[0] - ccm[3]), 2.0) +
                    std::pow(static_cast<double>(ccm[6] - ccm[3]), 2.0)));
    const double wbCcmBlueOpponentDirectionalGain = std::max(0.0,
            static_cast<double>(wbRgb[2]) * std::sqrt(
                    std::pow(static_cast<double>(ccm[2] - ccm[5]), 2.0) +
                    std::pow(static_cast<double>(ccm[8] - ccm[5]), 2.0)));
    const double ccmOnlyRedOpponentDirectionalGain = wbRgb[0] > 1.0e-6f
            ? wbCcmRedOpponentDirectionalGain / static_cast<double>(wbRgb[0]) : 1.0;
    const double ccmOnlyBlueOpponentDirectionalGain = wbRgb[2] > 1.0e-6f
            ? wbCcmBlueOpponentDirectionalGain / static_cast<double>(wbRgb[2]) : 1.0;

    // N006B: pre-WB ChromaCloud and neighbour-opponent correction ownership is retired.
    // Keep only compact read-only diagnostics so downstream telemetry can distinguish
    // measurement from mutation while the future neural owner is not yet connected.
    const double preWbMeasuredToPredictedRmsRatioRg = demosaicChromaAuditReady
            ? demosaicSafeRmsRatio(
                    demosaicMeasuredPostVarianceRg, demosaicPredictedPostVarianceRg)
            : -1.0;
    const double preWbMeasuredToPredictedRmsRatioBg = demosaicChromaAuditReady
            ? demosaicSafeRmsRatio(
                    demosaicMeasuredPostVarianceBg, demosaicPredictedPostVarianceBg)
            : -1.0;
    constexpr float preWbGainRiskR = 0.0f;
    constexpr float preWbGainRiskB = 0.0f;
    constexpr float spectraPreWbNoiseAuthority = 0.0f;
    constexpr bool spectraCleanSceneCloudBypass = true;
    constexpr float preWbChromaCleanupRiskR = 0.0f;
    constexpr float preWbChromaCleanupRiskB = 0.0f;
    constexpr float preWbChromaCleanupProposedBlendR = 0.0f;
    constexpr float preWbChromaCleanupProposedBlendB = 0.0f;
    constexpr bool preWbChromaCleanupPlanReady = false;

    constexpr bool phase6ResidualChromaUsedForOutput = false;
    constexpr bool phase6ResidualChromaGpuUsed = false;
    constexpr std::uint64_t phase6ProcessedPixels = 0u;
    constexpr std::uint64_t phase6CandidatePixels = 0u;
    constexpr std::uint64_t phase6IsolatedOutlierPixels = 0u;
    constexpr std::uint64_t phase6ZipperPixels = 0u;
    constexpr std::uint64_t phase6EdgeProtectedPixels = 0u;
    constexpr std::uint64_t phase6SaturatedDetailProtectedPixels = 0u;
    constexpr double phase6MeanAbsCorrectionRG = 0.0;
    constexpr double phase6MeanAbsCorrectionBG = 0.0;
    constexpr float phase6MaximumAbsoluteCorrection = 0.0f;
    constexpr double phase6CandidateFraction = 0.0;
    constexpr const char* phase6ExecutionBackend =
            "RETIRED_CLASSICAL_PIXEL_OWNER_NEURAL_PENDING";

    constexpr bool preWbChromaCleanupExecutionReady = false;
    constexpr float preWbChromaCleanupAppliedBlendR = 0.0f;
    constexpr float preWbChromaCleanupAppliedBlendB = 0.0f;
    constexpr float preWbNoiseModelEffectiveBlendR = 0.0f;
    constexpr float preWbNoiseModelEffectiveBlendB = 0.0f;
    bncam::spectra2::NoiseState preWbNoiseState = residualNoiseState.postDemosaic;
    preWbNoiseState.stage = "POST_DEMOSAIC_PRE_AWB_IDENTITY";
    preWbNoiseState.method = "NO_CLASSICAL_PRE_WB_PIXEL_CORRECTION";
    preWbNoiseState.status = "PROPAGATED_IDENTITY";

    const auto awbPropagationStart = IspClock::now();
    residualNoiseState.postAwb = bncam::spectra2::propagateAwb(
            preWbNoiseState,
            {static_cast<double>(wbRgb[0]), static_cast<double>(wbRgb[1]), static_cast<double>(wbRgb[2])},
            "POST_AWB_RGB"
    );
    residualNoiseState.awbPropagationMs = elapsedMs(awbPropagationStart);
    residualNoiseState.propagationAwbGainsRgb = {wbRgb[0], wbRgb[1], wbRgb[2]};

    const auto colourPropagationStart = IspClock::now();
    residualNoiseState.postColourTransform = bncam::spectra2::propagateColourMatrix(
            residualNoiseState.postAwb,
            colourMatrixArray(ccm),
            "POST_COLOUR_MATRIX_RGB"
    );

    // Phase 6: the calibrated DNG HueSatMap is nonlinear and runs after the paired matrix in the
    // production colour shader. Propagate the residual model through that exact profile too. The
    // compact CPU reference below evaluates only 108 profile-domain samples; it never materializes
    // or scans the frame. Its numeric opponent Jacobian includes HSM value scaling and hue-induced
    // R-G/B-G cross-coupling, then the existing covariance owner applies the resulting RMS gains.
    const bncam::color::RawCameraHueSatMapNoisePropagationPlan hueSatNoisePropagation =
            calibratedHueSatMapActive
                    ? bncam::color::resolveRawCameraHueSatMapNoisePropagation(calibratedHueSatMapCpu)
                    : bncam::color::RawCameraHueSatMapNoisePropagationPlan{};
    if (calibratedHueSatMapActive && hueSatNoisePropagation.ready) {
        residualNoiseState.postColourTransform = bncam::spectra2::propagateOpponentGains(
                residualNoiseState.postColourTransform,
                hueSatNoisePropagation.lumaRmsGain,
                hueSatNoisePropagation.redGreenRmsGain,
                hueSatNoisePropagation.blueGreenRmsGain,
                "POST_DNG_HUESATMAP_SCENE_LINEAR_SRGB",
                hueSatNoisePropagation.method,
                1.0);
    } else if (calibratedHueSatMapActive) {
        // Never pretend matrix-only covariance is exact when the nonlinear HSM really ran. Keep
        // identity amplitude as the safest unavailable fallback but explicitly lower confidence.
        residualNoiseState.postColourTransform = bncam::spectra2::propagateOpponentGains(
                residualNoiseState.postColourTransform,
                1.0, 1.0, 1.0,
                "POST_DNG_HUESATMAP_SCENE_LINEAR_SRGB",
                hueSatNoisePropagation.method,
                0.50);
    }
    residualNoiseState.colourTransformPropagationMs = elapsedMs(colourPropagationStart);
    for (size_t index = 0; index < residualNoiseState.propagationColourMatrix.size(); ++index) {
        residualNoiseState.propagationColourMatrix[index] = ccm[index];
    }

    // N004: automatic Phase-11 physical detail recovery is retired. S/O remains read-only
    // sensor/noise evidence for explicit profile-owned detail, but it may not activate or scale
    // a separate capture-detail pixel owner. Keep the residual state as an exact identity edge.
    const bool perceptualDetailPhysicalNoiseAvailable = meta.calibration.noiseModelMode != 0 &&
            meta.calibration.hasNoiseProfile && meta.calibration.noiseProfileApplied &&
            meta.calibration.noiseProfilePairCount > 0;
    residualNoiseState.postLinearDetail = residualNoiseState.postColourTransform;
    residualNoiseState.linearDetailPropagationMs = 0.0f;

    // Delta 31: explicit WB/CCM opponent-noise amplification audit. Propagation already existed;
    // this only makes each stage's R-G/B-G RMS gain directly observable without another scan.
    const double postAwbVarianceRg = std::max(0.0, residualNoiseState.postAwb.varianceRG);
    const double postAwbVarianceBg = std::max(0.0, residualNoiseState.postAwb.varianceBG);
    const double postColourVarianceRg =
            std::max(0.0, residualNoiseState.postColourTransform.varianceRG);
    const double postColourVarianceBg =
            std::max(0.0, residualNoiseState.postColourTransform.varianceBG);
    const double postDemosaicCombinedChromaVariance =
            0.5 * (demosaicPredictedPostVarianceRg + demosaicPredictedPostVarianceBg);
    const double postAwbCombinedChromaVariance =
            0.5 * (postAwbVarianceRg + postAwbVarianceBg);
    const double postColourCombinedChromaVariance =
            0.5 * (postColourVarianceRg + postColourVarianceBg);
    const double wbChromaRmsGainRg =
            demosaicSafeRmsRatio(postAwbVarianceRg, demosaicPredictedPostVarianceRg);
    const double wbChromaRmsGainBg =
            demosaicSafeRmsRatio(postAwbVarianceBg, demosaicPredictedPostVarianceBg);
    const double wbChromaRmsGainCombined = demosaicSafeRmsRatio(
            postAwbCombinedChromaVariance, postDemosaicCombinedChromaVariance);
    const double ccmChromaRmsGainRg =
            demosaicSafeRmsRatio(postColourVarianceRg, postAwbVarianceRg);
    const double ccmChromaRmsGainBg =
            demosaicSafeRmsRatio(postColourVarianceBg, postAwbVarianceBg);
    const double ccmChromaRmsGainCombined = demosaicSafeRmsRatio(
            postColourCombinedChromaVariance, postAwbCombinedChromaVariance);
    const double wbCcmChromaRmsGainCombined = demosaicSafeRmsRatio(
            postColourCombinedChromaVariance, postDemosaicCombinedChromaVariance);

    const auto baselineSmoothstep = [](float edge0, float edge1, float value) -> float {
        const float u = std::clamp(
                (value - edge0) / std::max(edge1 - edge0, 1.0e-6f),
                0.0f,
                1.0f
        );
        return u * u * (3.0f - 2.0f * u);
    };



    // WB and CCM are the only full-resolution color transform before exposure. 8H-E keeps
    // this transform on Vulkan. If demosaic already ran in the same backend its device-local
    // RGB is consumed directly; otherwise (temporary Menon fallback) the CPU RGB is uploaded once.
    double rawRSum = 0.0, rawGSum = 0.0, rawBSum = 0.0;
    double wbRSum = 0.0, wbGSum = 0.0, wbBSum = 0.0;
    double ccmRSum = 0.0, ccmGSum = 0.0, ccmBSum = 0.0;
    std::atomic<std::uint64_t> cpuFallbackHueSatAppliedPixels{0u};
    bncam::vulkan::SpectraResidentColorTransformResult vulkanColorTransform{};
    const auto awbColourTransformStart = IspClock::now();
    {
        bncam::vulkan::SpectraResidentColorTransformRequest request{};
        request.rgbData = linearRgb.empty() ? nullptr : linearRgb.ptr<float>(0);
        request.frameWidth = static_cast<std::uint32_t>(
                linearRgb.empty() ? demosaicInputWidth : linearRgb.cols);
        request.frameHeight = static_cast<std::uint32_t>(
                linearRgb.empty() ? demosaicInputHeight : linearRgb.rows);
        request.rowStrideFloats = linearRgb.empty() ? 0u : linearRgb.step1();
        request.residentDemosaicGeneration = vulkanDemosaic.residentDemosaicGeneration;
        // 8H-J: do not materialize the ~150 MB post-CCM RGB on the CPU. The resident
        // scene observer consumes this generation directly and only returns compact samples.
        request.deferFullReadback = true;
        request.wbRgb = {wbRgb[0], wbRgb[1], wbRgb[2]};
        request.preWbOpponentCleanupBlend = {0.0f, 0.0f};
        request.preWbCloudCorrectionReady = false;
        for (size_t i = 0; i < request.colorMatrix.size(); ++i) {
            request.colorMatrix[i] = ccm[i];
        }
        request.calibratedHueSatMapEnabled = calibratedHueSatMapActive;
        if (calibratedHueSatMapActive && calibratedProfile != nullptr) {
            request.hueSatHueDivisions = static_cast<std::uint32_t>(calibratedProfile->hueDivisions);
            request.hueSatSaturationDivisions =
                    static_cast<std::uint32_t>(calibratedProfile->saturationDivisions);
            request.hueSatValueDivisions = static_cast<std::uint32_t>(calibratedProfile->valueDivisions);
            request.hueSatEncoding = static_cast<std::uint32_t>(calibratedProfile->encoding);
            request.hueSatData1 = calibratedProfile->hueSatData1.data();
            request.hueSatData1FloatCount = calibratedProfile->hueSatData1.size();
            if (!calibratedProfile->hueSatData2.empty()) {
                request.hueSatData2 = calibratedProfile->hueSatData2.data();
                request.hueSatData2FloatCount = calibratedProfile->hueSatData2.size();
            }
            request.hueSatWeightFirst = calibratedProfileResolution.hueSatWeightFirst;
            request.hueSatWeightSecond = calibratedProfileResolution.hueSatWeightSecond;
        }
        vulkanColorTransform =
                bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(request);
    }
    const size_t expectedColorPixels = static_cast<size_t>(demosaicInputWidth) *
            static_cast<size_t>(demosaicInputHeight);
    const bool vulkanColorResident = vulkanColorTransform.success &&
            vulkanColorTransform.residentColorGeneration != 0u;
    bool cpuColorTransformApplied = false;
    if (vulkanColorTransform.success) {
        const double total = static_cast<double>(expectedColorPixels);
        rawRSum = vulkanColorTransform.rawMean[0] * total;
        rawGSum = vulkanColorTransform.rawMean[1] * total;
        rawBSum = vulkanColorTransform.rawMean[2] * total;
        wbRSum = vulkanColorTransform.wbMean[0] * total;
        wbGSum = vulkanColorTransform.wbMean[1] * total;
        wbBSum = vulkanColorTransform.wbMean[2] * total;
        ccmRSum = vulkanColorTransform.ccmMean[0] * total;
        ccmGSum = vulkanColorTransform.ccmMean[1] * total;
        ccmBSum = vulkanColorTransform.ccmMean[2] * total;
        if (!vulkanColorTransform.outputRgb.empty()) {
            linearRgb = cv::Mat(
                    demosaicInputHeight, demosaicInputWidth, CV_32FC3,
                    vulkanColorTransform.outputRgb.data());
        }
    } else {
        // Typed CPU fallback only. If Vulkan colour processing fails after a resident demosaic,
        // regenerate the CPU reference once; this is failure recovery, never a normal shadow pass.
        if (linearRgb.empty()) {
            linearRgb = runCpuDemosaicFallback();
            vulkanDemosaicResident = false;
        }
        // N006B: failure recovery follows the same neutral contract as Vulkan: direct
        // demosaic -> WB/CCM, with no hidden neighbour/cloud denoise correction.
        std::mutex colorStatsMutex;
        cv::parallel_for_(cv::Range(0, linearRgb.rows), [&](const cv::Range& range) {
            double localRawR = 0.0, localRawG = 0.0, localRawB = 0.0;
            double localWbR = 0.0, localWbG = 0.0, localWbB = 0.0;
            double localCcmR = 0.0, localCcmG = 0.0, localCcmB = 0.0;
            for (int y = range.start; y < range.end; ++y) {
                cv::Vec3f* row = linearRgb.ptr<cv::Vec3f>(y);
                for (int x = 0; x < linearRgb.cols; ++x) {
                    const cv::Vec3f input = row[x];
                    localRawR += input[0];
                    localRawG += input[1];
                    localRawB += input[2];

                    const cv::Vec3f rawForWb = input;
                    const float wbR = rawForWb[0] * wbRgb[0];
                    const float wbG = rawForWb[1] * wbRgb[1];
                    const float wbB = rawForWb[2] * wbRgb[2];
                    localWbR += wbR;
                    localWbG += wbG;
                    localWbB += wbB;

                    const float signedR = ccm[0] * wbR + ccm[1] * wbG + ccm[2] * wbB;
                    const float signedG = ccm[3] * wbR + ccm[4] * wbG + ccm[5] * wbB;
                    const float signedB = ccm[6] * wbR + ccm[7] * wbG + ccm[8] * wbB;
                    cv::Vec3f rendered(
                            std::max(0.0f, signedR),
                            std::max(0.0f, signedG),
                            std::max(0.0f, signedB));
                    if (calibratedHueSatMapCpuReady) {
                        bool hsmApplied = false;
                        const auto corrected = bncam::color::rawCameraApplyCalibratedHueSatMapLinearSrgb(
                                calibratedHueSatMapCpu,
                                {signedR, signedG, signedB},
                                &hsmApplied);
                        if (hsmApplied) {
                            rendered = cv::Vec3f(
                                    std::max(0.0f, corrected[0]),
                                    std::max(0.0f, corrected[1]),
                                    std::max(0.0f, corrected[2]));
                            cpuFallbackHueSatAppliedPixels.fetch_add(1u, std::memory_order_relaxed);
                        }
                    }
                    // Keep Phase-8 matrix statistics in the linear post-matrix/pre-HSM domain
                    // on both GPU and failure-only CPU routes. The scene observer later sees
                    // the actual calibrated profile output.
                    localCcmR += std::max(0.0f, signedR);
                    localCcmG += std::max(0.0f, signedG);
                    localCcmB += std::max(0.0f, signedB);

                    row[x] = rendered;
                }
            }
            std::lock_guard<std::mutex> lock(colorStatsMutex);
            rawRSum += localRawR;
            rawGSum += localRawG;
            rawBSum += localRawB;
            wbRSum += localWbR;
            wbGSum += localWbG;
            wbBSum += localWbB;
            ccmRSum += localCcmR;
            ccmGSum += localCcmG;
            ccmBSum += localCcmB;
        });
        cpuColorTransformApplied = true;
    }
    const float awbColourTransformMs = elapsedMs(awbColourTransformStart);
    const auto measuredPostColourTransformStart = IspClock::now();
    residualNoiseState.measuredPostColourTransform = vulkanColorTransform.success
            ? measureLinearResidualGpuCandidates(
                    vulkanColorTransform.residualCandidates,
                    "POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP")
            : measureLinearResidualFlatRegions(
                    linearRgb, "POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP");
    residualNoiseState.measuredPostColourTransformResidualMs =
            elapsedMs(measuredPostColourTransformStart);
    // 8H-J delays releasing the CPU RAW mosaic until the resident scene/tone chain has either
    // succeeded or selected its typed CPU fallback. That keeps failure recovery available without
    // forcing a normal full-frame post-CCM readback.
    residualNoiseState.postColourTransformCalibration =
            bncam::spectra2::comparePredictionToObservation(
                    residualNoiseState.postColourTransform,
                    residualNoiseState.measuredPostColourTransform
            );
    if (residualNoiseState.postDemosaicCalibration.ready &&
        residualNoiseState.postColourTransformCalibration.ready) {
        residualNoiseState.calibrationStatus =
                "MILESTONE_2B_STAGE_OBSERVATIONS_READY_NO_AUTO_CALIBRATION";
    } else if (residualNoiseState.postDemosaicCalibration.ready ||
               residualNoiseState.postColourTransformCalibration.ready) {
        residualNoiseState.calibrationStatus =
                "MILESTONE_2B_PARTIAL_STAGE_OBSERVATION";
    } else {
        residualNoiseState.calibrationStatus =
                "MILESTONE_2B_INSUFFICIENT_FLAT_SUPPORT";
    }

    const double totalPixelsD = static_cast<double>(std::max<size_t>(1u, expectedColorPixels));
    const double rawMeanR = rawRSum / totalPixelsD;
    const double rawMeanG = rawGSum / totalPixelsD;
    const double rawMeanB = rawBSum / totalPixelsD;

    const double wbMeanR = wbRSum / totalPixelsD;
    const double wbMeanG = wbGSum / totalPixelsD;
    const double wbMeanB = wbBSum / totalPixelsD;

    const double ccmMeanR = ccmRSum / totalPixelsD;
    const double ccmMeanG = ccmGSum / totalPixelsD;
    const double ccmMeanB = ccmBSum / totalPixelsD;

    // Phase 8 neutral color-science validation is deliberately sampled at the output of
    // AWB + sensor->linear-sRGB CCM, before highlight heuristics, tone, hidden RAW presentation
    // vibrance, profile color controls or output encoding. Reuse existing compact statistics;
    // do not add a full-frame readback just to validate color.
    const bncam::color::MatrixAudit phase8ColorMatrixAudit =
            bncam::color::auditLinearSrgbMatrix(ccm);
    const bncam::color::PrePresentationSceneAudit phase8PrePresentationSceneAudit =
            bncam::color::auditPrePresentationSceneMean(ccmMeanR, ccmMeanG, ccmMeanB);

    Phase9ColorProtectionDebug phase9ColorDebug{};
    if (vulkanColorTransform.success) {
        phase9ColorDebug.gpuPrimary = true;
        phase9ColorDebug.sensorClipCandidatePixels = vulkanColorTransform.phase9SensorClipCandidatePixels;
        phase9ColorDebug.singleChannelSensorClipPixels = vulkanColorTransform.phase9SingleChannelSensorClipPixels;
        phase9ColorDebug.multiChannelSensorClipPixels = vulkanColorTransform.phase9MultiChannelSensorClipPixels;
        phase9ColorDebug.fullySensorClippedPixels = vulkanColorTransform.phase9FullySensorClippedPixels;
        phase9ColorDebug.wbAboveUnityWithoutSensorClipPixels =
                vulkanColorTransform.phase9WbAboveUnityWithoutSensorClipPixels;
        phase9ColorDebug.ccmNegativeExcursionPixels = vulkanColorTransform.phase9CcmNegativeExcursionPixels;
        phase9ColorDebug.colorConfidenceAppliedPixels =
                vulkanColorTransform.phase9ColorConfidenceAppliedPixels;
        phase9ColorDebug.partialColorConfidencePixels =
                vulkanColorTransform.phase9PartialColorConfidencePixels;
        phase9ColorDebug.gamutCompressedPixels = vulkanColorTransform.phase9GamutCompressedPixels;
        phase9ColorDebug.legacyMagentaRiskPixels = vulkanColorTransform.phase9LegacyMagentaRiskPixels;
        phase9ColorDebug.protectedMagentaRiskPixels = vulkanColorTransform.phase9ProtectedMagentaRiskPixels;
        phase9ColorDebug.sceneLinearOverUnityPixels = vulkanColorTransform.phase9SceneLinearOverUnityPixels;
        phase9ColorDebug.sourceRawConfidenceMapUsed =
                vulkanColorTransform.phase9SourceRawConfidenceMapUsed;
        phase9ColorDebug.sourceRawConfidenceMapBytes =
                vulkanColorTransform.phase9SourceRawConfidenceMapBytes;
        phase9ColorDebug.sourceRawConfidenceCandidatePixels =
                vulkanColorTransform.phase9SourceRawConfidenceCandidatePixels;
        phase9ColorDebug.sourceRawZeroConfidencePixels =
                vulkanColorTransform.phase9SourceRawZeroConfidencePixels;
        phase9ColorDebug.sourceRawPartialConfidencePixels =
                vulkanColorTransform.phase9SourceRawPartialConfidencePixels;
        phase9ColorDebug.sourceRawDemosaicDisagreementPixels =
                vulkanColorTransform.phase9SourceRawDemosaicDisagreementPixels;
        phase9ColorDebug.calibratedHueSatMapRequested =
                vulkanColorTransform.calibratedHueSatMapRequested;
        phase9ColorDebug.calibratedHueSatMapApplied =
                vulkanColorTransform.calibratedHueSatMapApplied;
        phase9ColorDebug.calibratedHueSatMapAppliedPixels =
                vulkanColorTransform.calibratedHueSatMapAppliedPixels;
        phase9ColorDebug.calibratedHueSatMapProfileBytes =
                vulkanColorTransform.calibratedHueSatMapProfileBytes;
        phase9ColorDebug.calibratedHueSatMapWeightFirst =
                vulkanColorTransform.calibratedHueSatMapWeightFirst;
        phase9ColorDebug.calibratedHueSatMapWeightSecond =
                vulkanColorTransform.calibratedHueSatMapWeightSecond;
    } else if (cpuColorTransformApplied) {
        phase9ColorDebug.calibratedHueSatMapRequested = calibratedHueSatMapActive;
        phase9ColorDebug.calibratedHueSatMapAppliedPixels =
                cpuFallbackHueSatAppliedPixels.load(std::memory_order_relaxed);
        phase9ColorDebug.calibratedHueSatMapApplied =
                phase9ColorDebug.calibratedHueSatMapAppliedPixels > 0u;
        phase9ColorDebug.calibratedHueSatMapProfileBytes = (calibratedProfile != nullptr
                        ? static_cast<std::uint64_t>((calibratedProfile->hueSatData1.size() +
                                calibratedProfile->hueSatData2.size()) * sizeof(float))
                        : 0u);
        phase9ColorDebug.calibratedHueSatMapWeightFirst =
                calibratedProfileResolution.hueSatWeightFirst;
        phase9ColorDebug.calibratedHueSatMapWeightSecond =
                calibratedProfileResolution.hueSatWeightSecond;
    }

    // Failure-only CPU materialization. The normal path stays device-resident from demosaic
    // through Phase-9 sensor-clip/gamut protection and scene observation. If the Vulkan color
    // stage fails, recreate the deterministic Phase-9 CPU reference exactly once.
    const auto materializeCpuPostCcmReference = [&]() {
        if (cpuColorTransformApplied && !linearRgb.empty()) {
            phase9ColorDebug.calibratedHueSatMapRequested = calibratedHueSatMapActive;
            phase9ColorDebug.calibratedHueSatMapAppliedPixels =
                    cpuFallbackHueSatAppliedPixels.load(std::memory_order_relaxed);
            phase9ColorDebug.calibratedHueSatMapApplied =
                    phase9ColorDebug.calibratedHueSatMapAppliedPixels > 0u;
            phase9ColorDebug.calibratedHueSatMapWeightFirst =
                    calibratedProfileResolution.hueSatWeightFirst;
            phase9ColorDebug.calibratedHueSatMapWeightSecond =
                    calibratedProfileResolution.hueSatWeightSecond;
            return;
        }
        const auto phase9CpuStart = IspClock::now();
        linearRgb = runCpuDemosaicFallback();
        vulkanDemosaicResident = false;
        if (linearRgb.empty() || linearRgb.type() != CV_32FC3) {
            cpuColorTransformApplied = true;
            phase9ColorDebug.cpuFallback = true;
            phase9ColorDebug.cpuFallbackMs = elapsedMs(phase9CpuStart);
            return;
        }

        // Failure-only reference path mirrors the GPU clipping-confidence owner. No neighbour
        // reconstruction or extra full-frame clone is needed: confidence is derived from each
        // immutable pre-WB pixel before that same pixel is overwritten with the protected result.
        const std::array<float, 9> phase9Ccm{
                ccm[0], ccm[1], ccm[2],
                ccm[3], ccm[4], ccm[5],
                ccm[6], ccm[7], ccm[8]};
        std::atomic<std::uint64_t> sensorClipCandidates{0u};
        std::atomic<std::uint64_t> singleClip{0u};
        std::atomic<std::uint64_t> multiClip{0u};
        std::atomic<std::uint64_t> fullClip{0u};
        std::atomic<std::uint64_t> wbOverUnity{0u};
        std::atomic<std::uint64_t> ccmExcursions{0u};
        std::atomic<std::uint64_t> colorConfidenceApplied{0u};
        std::atomic<std::uint64_t> partialColorConfidence{0u};
        std::atomic<std::uint64_t> gamutCompressed{0u};
        std::atomic<std::uint64_t> legacyMagentaRisk{0u};
        std::atomic<std::uint64_t> protectedMagentaRisk{0u};
        std::atomic<std::uint64_t> sceneLinearOverUnity{0u};
        std::atomic<std::uint64_t> calibratedHueSatAppliedPixels{0u};

        cv::parallel_for_(cv::Range(0, linearRgb.rows), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                cv::Vec3f* outRow = linearRgb.ptr<cv::Vec3f>(y);
                for (int x = 0; x < linearRgb.cols; ++x) {
                    const cv::Vec3f rawCv = outRow[x];
                    const bncam::highlight::Rgb raw{rawCv[0], rawCv[1], rawCv[2]};
                    const auto clip = bncam::highlight::classifySensorClip(raw);
                    const auto colorConfidence = bncam::highlight::resolveSensorColorConfidence(raw);
                    if (colorConfidence.reduced) {
                        sensorClipCandidates.fetch_add(1u, std::memory_order_relaxed);
                    }
                    if (clip.clippedChannels == 1) singleClip.fetch_add(1u, std::memory_order_relaxed);
                    if (clip.clippedChannels >= 2) multiClip.fetch_add(1u, std::memory_order_relaxed);
                    if (clip.all) fullClip.fetch_add(1u, std::memory_order_relaxed);
                    if (colorConfidence.confidence > 1.0e-6f &&
                            colorConfidence.confidence < 1.0f - 1.0e-6f) {
                        partialColorConfidence.fetch_add(1u, std::memory_order_relaxed);
                    }

                    const bncam::highlight::Rgb wb{
                            raw.r * wbRgb[0],
                            raw.g * wbRgb[1],
                            raw.b * wbRgb[2]};
                    if (bncam::highlight::wbAboveUnityWithoutSensorClip(raw, wb)) {
                        wbOverUnity.fetch_add(1u, std::memory_order_relaxed);
                    }
                    const bncam::highlight::Rgb signedCcm =
                            bncam::highlight::multiplyMatrix(phase9Ccm, wb);
                    const bncam::highlight::Rgb legacyPositive{
                            std::max(0.0f, signedCcm.r),
                            std::max(0.0f, signedCcm.g),
                            std::max(0.0f, signedCcm.b)};
                    if (bncam::highlight::heuristicMagentaHighlightRisk(legacyPositive)) {
                        legacyMagentaRisk.fetch_add(1u, std::memory_order_relaxed);
                    }
                    const bool originalCcmExcursion =
                            std::min({signedCcm.r, signedCcm.g, signedCcm.b}) <
                                    -bncam::highlight::kCcmNegativeTolerance;
                    if (originalCcmExcursion) {
                        ccmExcursions.fetch_add(1u, std::memory_order_relaxed);
                    }
                    const auto confidenceSafe =
                            bncam::highlight::applyClippingAwareHighlightColor(
                                    signedCcm, colorConfidence);
                    if (confidenceSafe.applied) {
                        colorConfidenceApplied.fetch_add(1u, std::memory_order_relaxed);
                    }
                    bncam::highlight::Rgb profileInput = confidenceSafe.rgb;
                    if (calibratedHueSatMapCpuReady) {
                        bool hsmApplied = false;
                        const auto corrected = bncam::color::rawCameraApplyCalibratedHueSatMapLinearSrgb(
                                calibratedHueSatMapCpu,
                                {profileInput.r, profileInput.g, profileInput.b},
                                &hsmApplied);
                        if (hsmApplied) {
                            profileInput = {corrected[0], corrected[1], corrected[2]};
                            calibratedHueSatAppliedPixels.fetch_add(1u, std::memory_order_relaxed);
                        }
                    }
                    const auto protectedCcm =
                            bncam::highlight::protectSignedCcmLowerGamut(profileInput);
                    if (protectedCcm.applied) gamutCompressed.fetch_add(1u, std::memory_order_relaxed);
                    if (bncam::highlight::heuristicMagentaHighlightRisk(protectedCcm.rgb)) {
                        protectedMagentaRisk.fetch_add(1u, std::memory_order_relaxed);
                    }
                    if (std::max({protectedCcm.rgb.r, protectedCcm.rgb.g, protectedCcm.rgb.b}) > 1.0f) {
                        sceneLinearOverUnity.fetch_add(1u, std::memory_order_relaxed);
                    }
                    outRow[x] = cv::Vec3f(
                            protectedCcm.rgb.r,
                            protectedCcm.rgb.g,
                            protectedCcm.rgb.b);
                }
            }
        });
        phase9ColorDebug = {};
        phase9ColorDebug.cpuFallback = true;
        phase9ColorDebug.sensorClipCandidatePixels = sensorClipCandidates.load(std::memory_order_relaxed);
        phase9ColorDebug.singleChannelSensorClipPixels = singleClip.load(std::memory_order_relaxed);
        phase9ColorDebug.multiChannelSensorClipPixels = multiClip.load(std::memory_order_relaxed);
        phase9ColorDebug.fullySensorClippedPixels = fullClip.load(std::memory_order_relaxed);
        phase9ColorDebug.wbAboveUnityWithoutSensorClipPixels = wbOverUnity.load(std::memory_order_relaxed);
        phase9ColorDebug.ccmNegativeExcursionPixels = ccmExcursions.load(std::memory_order_relaxed);
        phase9ColorDebug.colorConfidenceAppliedPixels =
                colorConfidenceApplied.load(std::memory_order_relaxed);
        phase9ColorDebug.partialColorConfidencePixels =
                partialColorConfidence.load(std::memory_order_relaxed);
        phase9ColorDebug.gamutCompressedPixels = gamutCompressed.load(std::memory_order_relaxed);
        phase9ColorDebug.legacyMagentaRiskPixels = legacyMagentaRisk.load(std::memory_order_relaxed);
        phase9ColorDebug.protectedMagentaRiskPixels = protectedMagentaRisk.load(std::memory_order_relaxed);
        phase9ColorDebug.sceneLinearOverUnityPixels = sceneLinearOverUnity.load(std::memory_order_relaxed);
        phase9ColorDebug.calibratedHueSatMapRequested = calibratedHueSatMapActive;
        phase9ColorDebug.calibratedHueSatMapAppliedPixels =
                calibratedHueSatAppliedPixels.load(std::memory_order_relaxed);
        phase9ColorDebug.calibratedHueSatMapApplied =
                phase9ColorDebug.calibratedHueSatMapAppliedPixels > 0u;
        phase9ColorDebug.calibratedHueSatMapWeightFirst =
                calibratedProfileResolution.hueSatWeightFirst;
        phase9ColorDebug.calibratedHueSatMapWeightSecond =
                calibratedProfileResolution.hueSatWeightSecond;
        phase9ColorDebug.cpuFallbackMs = elapsedMs(phase9CpuStart);
        cpuColorTransformApplied = true;
    };

    // Phase N002: classical pre-tone/Galosh and near-black chroma suppression are retired.
    // Physical covariance/S/O stays available as read-only noise intelligence.

    // Phase 9 Delta 0066: uniform evidence ownership. No demosaic-family or RAW-format
    // rule is allowed to scale chroma authority. Local structure, stochastic residuals,
    // physical sensor noise and WB+CCM amplification are the only decision inputs.

    bncam::vulkan::SpectraResidentSceneObserverResult vulkanSceneObserver{};
    if (vulkanColorResident) {
        bncam::vulkan::SpectraResidentSceneObserverRequest request{};
        request.frameWidth = static_cast<std::uint32_t>(demosaicInputWidth);
        request.frameHeight = static_cast<std::uint32_t>(demosaicInputHeight);
        request.targetSampleCount = 50000u;
        // N002: resident scene observation stays enabled, but it receives no classical
        // pre-tone chroma suppression request. Value-initialized request fields remain zero.
        vulkanSceneObserver =
                bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentSceneObserverFromAwbCcm(
                        request, vulkanColorTransform.residentColorGeneration);
    }
    const bool vulkanSceneObserverActive = vulkanSceneObserver.success &&
            vulkanSceneObserver.residentSceneGeneration != 0u &&
            vulkanSceneObserver.displayGrid.size() == 32u * 24u;

    HighlightRecoveryDebug highlightDebug{};
    const auto syncHighlightDebugFromPhase9 = [&]() {
        highlightDebug.applied = phase9ColorDebug.colorConfidenceAppliedPixels > 0u;
        highlightDebug.correctedPixels = static_cast<int>(std::min<std::uint64_t>(
                phase9ColorDebug.colorConfidenceAppliedPixels,
                static_cast<std::uint64_t>(std::numeric_limits<int>::max())));
        // Clipping-aware color is fused with AWB+CCM, so no separate kernel time is attributable.
        highlightDebug.elapsedMs = phase9ColorDebug.cpuFallback ? phase9ColorDebug.cpuFallbackMs : 0.0f;
        highlightDebug.reason = highlightDebug.applied
                ? (phase9ColorDebug.gpuPrimary
                        ? "phase9_clipping_aware_highlight_color_fused_awb_ccm_vulkan"
                        : "phase9_clipping_aware_highlight_color_cpu_failure_reference")
                : (phase9ColorDebug.gpuPrimary
                        ? "phase9_no_highlight_color_confidence_reduction_required_vulkan"
                        : "phase9_no_highlight_color_confidence_reduction_required_cpu_failure_reference");
    };
    syncHighlightDebugFromPhase9();
    std::vector<float> lumaSamples;
    std::vector<float> maximumChannelSamples;
    std::vector<float> relativeChromaSamples;
    std::vector<cv::Vec3f> sceneRgbSamples;
    const size_t totalPixels = expectedColorPixels;
    size_t sampleStep = std::max<size_t>(1u, totalPixels / 50000u);
    const size_t sampleCapacity = std::min<size_t>(50000u, totalPixels);
    lumaSamples.reserve(sampleCapacity + 1u);
    maximumChannelSamples.reserve(sampleCapacity + 1u);
    relativeChromaSamples.reserve(sampleCapacity + 1u);
    sceneRgbSamples.reserve(sampleCapacity + 1u);
    size_t nearWhiteSampleCount = 0;
    size_t overRangeSampleCount = 0;
    size_t skyLikeSampleCount = 0;
    constexpr int displayGridWidth = 32;
    constexpr int displayGridHeight = 24;
    std::array<float, displayGridWidth * displayGridHeight> displayGrid{};

    const auto accumulateSceneSample = [&](float r, float g, float b) {
        const float maximum = std::max({r, g, b});
        const float minimum = std::min({r, g, b});
        const float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        const float relativeChroma = (maximum - minimum) / std::max(maximum, 0.02f);
        const bool blueCyanRelationship = b > r + 0.015f &&
                g > r - 0.015f && b > 0.88f * g;
        if (maximum > 0.90f) ++nearWhiteSampleCount;
        if (maximum > 1.00f) ++overRangeSampleCount;
        if (blueCyanRelationship && relativeChroma > 0.08f && luma > 0.035f) {
            ++skyLikeSampleCount;
        }
        if (std::isfinite(luma)) lumaSamples.push_back(std::max(0.0f, luma));
        if (std::isfinite(maximum)) maximumChannelSamples.push_back(maximum);
        if (std::isfinite(relativeChroma)) relativeChromaSamples.push_back(std::max(0.0f, relativeChroma));
        if (std::isfinite(r) && std::isfinite(g) && std::isfinite(b)) {
            sceneRgbSamples.emplace_back(r, g, b);
        }
    };

    bool cpuSceneProcessingApplied = false;
    if (vulkanSceneObserverActive) {
        sampleStep = std::max<size_t>(1u, vulkanSceneObserver.sampleStep);
        const std::size_t compactSamples = vulkanSceneObserver.sampledRgb.size() / 3u;
        for (std::size_t i = 0u; i < compactSamples; ++i) {
            const std::size_t base = i * 3u;
            accumulateSceneSample(
                    vulkanSceneObserver.sampledRgb[base + 0u],
                    vulkanSceneObserver.sampledRgb[base + 1u],
                    vulkanSceneObserver.sampledRgb[base + 2u]);
        }
        std::copy_n(vulkanSceneObserver.displayGrid.begin(), displayGrid.size(), displayGrid.begin());
    } else {
        materializeCpuPostCcmReference();
        syncHighlightDebugFromPhase9();
        cpuSceneProcessingApplied = true;
        const cv::Vec3f* sampledPixels = linearRgb.ptr<cv::Vec3f>(0);
        for (size_t index = 0; index < totalPixels; index += sampleStep) {
            const cv::Vec3f& pixel = sampledPixels[index];
            accumulateSceneSample(pixel[0], pixel[1], pixel[2]);
        }
        for (int gy = 0; gy < displayGridHeight; ++gy) {
            const int y0 = gy * linearRgb.rows / displayGridHeight;
            const int y1 = std::max(y0 + 1, (gy + 1) * linearRgb.rows / displayGridHeight);
            for (int gx = 0; gx < displayGridWidth; ++gx) {
                const int x0 = gx * linearRgb.cols / displayGridWidth;
                const int x1 = std::max(x0 + 1, (gx + 1) * linearRgb.cols / displayGridWidth);
                double sum = 0.0;
                int count = 0;
                for (int sy = 0; sy < 3; ++sy) {
                    const int y = std::min(linearRgb.rows - 1, y0 + (2 * sy + 1) * (y1 - y0) / 6);
                    const cv::Vec3f* row = linearRgb.ptr<cv::Vec3f>(y);
                    for (int sx = 0; sx < 3; ++sx) {
                        const int x = std::min(linearRgb.cols - 1, x0 + (2 * sx + 1) * (x1 - x0) / 6);
                        const cv::Vec3f& pixel = row[x];
                        sum += 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2];
                        ++count;
                    }
                }
                displayGrid[flatIndex2d(gy, displayGridWidth, gx)] =
                        count > 0 ? static_cast<float>(sum / count) : 0.0f;
            }
        }
    }

    float p02 = 0.0f;
    float p35 = 0.10f;
    float p50 = 0.14f;
    float p60 = 0.16f;
    float p75 = 0.24f;
    float p95 = 0.50f;
    float p98 = 0.70f;
    if (!lumaSamples.empty()) {
        std::sort(lumaSamples.begin(), lumaSamples.end());
        const auto percentile = [&lumaSamples](float fraction) -> float {
            const size_t index = std::min(
                    lumaSamples.size() - 1u,
                    static_cast<size_t>(std::floor(
                            static_cast<double>(lumaSamples.size() - 1u) * fraction
                    ))
            );
            return lumaSamples[index];
        };
        p02 = percentile(0.02f);
        p35 = percentile(0.35f);
        p50 = percentile(0.50f);
        p60 = percentile(0.60f);
        p75 = percentile(0.75f);
        p95 = percentile(0.95f);
        p98 = percentile(0.98f);
    }

    const float midtoneStatistic = std::max(
            0.001f,
            0.10f * p35 + 0.20f * p50 + 0.30f * p60 + 0.40f * p75
    );

    float p99Maximum = 0.0f;
    if (!maximumChannelSamples.empty()) {
        std::sort(maximumChannelSamples.begin(), maximumChannelSamples.end());
        const size_t count = maximumChannelSamples.size();
        p99Maximum = maximumChannelSamples[std::min(
                count - 1u,
                static_cast<size_t>(std::floor(static_cast<double>(count - 1u) * 0.99))
        )];
    }

    float sceneChromaP50 = 0.0f;
    float sceneChromaP90 = 0.0f;
    if (!relativeChromaSamples.empty()) {
        std::sort(relativeChromaSamples.begin(), relativeChromaSamples.end());
        const auto chromaPercentile = [&relativeChromaSamples](float fraction) noexcept {
            const size_t index = std::min(
                    relativeChromaSamples.size() - 1u,
                    static_cast<size_t>(std::floor(
                            static_cast<double>(relativeChromaSamples.size() - 1u) * fraction)));
            return relativeChromaSamples[index];
        };
        sceneChromaP50 = chromaPercentile(0.50f);
        sceneChromaP90 = chromaPercentile(0.90f);
    }

    const float sampledCount = static_cast<float>(std::max<size_t>(1u, lumaSamples.size()));
    const float nearWhiteFraction = static_cast<float>(nearWhiteSampleCount) / sampledCount;
    const float overRangeFraction = static_cast<float>(overRangeSampleCount) / sampledCount;
    const float skyLikeFraction = static_cast<float>(skyLikeSampleCount) / sampledCount;
    const float highlightTailRatio = p98 / midtoneStatistic;

    // === EXPOSURE GOVERNOR V2 ===
    const bool exposureKnown = meta.captureExposureTimeNs > 0;
    const float exposureMs = exposureKnown
            ? static_cast<float>(static_cast<double>(meta.captureExposureTimeNs) / 1'000'000.0)
            : 0.0f;
    const int actualIso = std::max(1, meta.captureSensitivityIso);

    const bool lowLightScene = (exposureKnown && actualIso >= 300 && exposureMs >= 20.0f) ||
                               actualIso >= 800 ||
                               (p50 < 0.08f && p95 < 0.25f && actualIso >= 200);

    const float isoLowLightEvidence = baselineSmoothstep(300.0f, 1000.0f, static_cast<float>(actualIso));
    const float exposureLowLightEvidence = exposureKnown
            ? baselineSmoothstep(12.0f, 30.0f, exposureMs)
            : 0.0f;
    const float darkSceneEvidence = 1.0f - baselineSmoothstep(0.055f, 0.16f, p50);
    const float indoorLowLightConfidence = std::clamp(
            0.40f * isoLowLightEvidence +
            0.25f * exposureLowLightEvidence +
            0.35f * darkSceneEvidence,
            0.0f,
            1.0f
    );

    // Detect a localized, approximately rectangular bright-content island on a coarse grid.
    // Unlike the old blue/cyan count, this retains spatial evidence and can distinguish a TV
    // in a dark room from broad outdoor sky coverage.
    const float displayCellThreshold = std::max(0.10f, p50 * 2.0f);
    std::array<uint8_t, displayGridWidth * displayGridHeight> displayVisited{};
    int bestDisplayCells = 0;
    int bestDisplayMinX = 0, bestDisplayMaxX = -1, bestDisplayMinY = 0, bestDisplayMaxY = -1;
    std::vector<int> displayStack;
    displayStack.reserve(displayGridWidth * displayGridHeight);
    for (int start = 0; start < displayGridWidth * displayGridHeight; ++start) {
        if (displayVisited[static_cast<size_t>(start)] != 0 ||
            displayGrid[static_cast<size_t>(start)] < displayCellThreshold) {
            continue;
        }
        displayVisited[static_cast<size_t>(start)] = 1;
        displayStack.clear();
        displayStack.push_back(start);
        int cells = 0;
        int minX = displayGridWidth, maxX = -1, minY = displayGridHeight, maxY = -1;
        while (!displayStack.empty()) {
            const int current = displayStack.back();
            displayStack.pop_back();
            const int x = current % displayGridWidth;
            const int y = current / displayGridWidth;
            ++cells;
            minX = std::min(minX, x);
            maxX = std::max(maxX, x);
            minY = std::min(minY, y);
            maxY = std::max(maxY, y);
            const int neighbors[4] = {current - 1, current + 1, current - displayGridWidth, current + displayGridWidth};
            for (int direction = 0; direction < 4; ++direction) {
                const int neighbor = neighbors[direction];
                if (neighbor < 0 || neighbor >= displayGridWidth * displayGridHeight) continue;
                const int nx = neighbor % displayGridWidth;
                const int ny = neighbor / displayGridWidth;
                if (std::abs(nx - x) + std::abs(ny - y) != 1) continue;
                if (displayVisited[static_cast<size_t>(neighbor)] != 0 ||
                    displayGrid[static_cast<size_t>(neighbor)] < displayCellThreshold) continue;
                displayVisited[static_cast<size_t>(neighbor)] = 1;
                displayStack.push_back(neighbor);
            }
        }
        if (cells > bestDisplayCells) {
            bestDisplayCells = cells;
            bestDisplayMinX = minX;
            bestDisplayMaxX = maxX;
            bestDisplayMinY = minY;
            bestDisplayMaxY = maxY;
        }
    }

    const int displayBoxWidth = std::max(0, bestDisplayMaxX - bestDisplayMinX + 1);
    const int displayBoxHeight = std::max(0, bestDisplayMaxY - bestDisplayMinY + 1);
    const int displayBoxCells = displayBoxWidth * displayBoxHeight;
    const float displayBoxAreaFraction = static_cast<float>(displayBoxCells) /
            static_cast<float>(displayGridWidth * displayGridHeight);
    const float displayFill = displayBoxCells > 0
            ? static_cast<float>(bestDisplayCells) / static_cast<float>(displayBoxCells)
            : 0.0f;
    const float displayAspect = displayBoxHeight > 0
            ? (static_cast<float>(displayBoxWidth) * demosaicInputWidth / displayGridWidth) /
                    (static_cast<float>(displayBoxHeight) * demosaicInputHeight / displayGridHeight)
            : 0.0f;
    const float displayAreaGate = baselineSmoothstep(0.006f, 0.025f, displayBoxAreaFraction) *
            (1.0f - baselineSmoothstep(0.32f, 0.58f, displayBoxAreaFraction));
    const float displayRectGate = baselineSmoothstep(0.18f, 0.52f, displayFill) *
            baselineSmoothstep(0.55f, 1.05f, displayAspect) *
            (1.0f - baselineSmoothstep(3.2f, 4.8f, displayAspect));
    const float displayContrastGate = baselineSmoothstep(2.0f, 5.0f, highlightTailRatio) *
            baselineSmoothstep(0.10f, 0.42f, p98);
    // WB/CCM can lift an objectively dim RAW median well above the pre-color RAW fraction;
    // retain indoor-surroundings evidence through that expected perceptual range.
    const float darkSurroundingsGate = 1.0f - baselineSmoothstep(0.08f, 0.24f, p50);
    const float displayHighlightConfidence = std::clamp(
            displayAreaGate * darkSurroundingsGate *
                    (0.45f * displayRectGate + 0.35f * displayContrastGate +
                     0.20f * indoorLowLightConfidence),
            0.0f,
            1.0f
    );
    const bool displayHighlightScene = displayHighlightConfidence >= 0.48f;

    const float skyCoverageConfidence = baselineSmoothstep(0.06f, 0.22f, skyLikeFraction);
    const float skyBrightnessConfidence = baselineSmoothstep(0.12f, 0.45f, p95);
    const float skyTailConfidence = baselineSmoothstep(2.5f, 5.0f, highlightTailRatio);
    const float outdoorSkyEvidence =
            0.55f * skyCoverageConfidence +
            0.25f * skyBrightnessConfidence +
            0.20f * skyTailConfidence;
    const float outdoorSkyConfidence = std::clamp(
            outdoorSkyEvidence *
                    (1.0f - 0.82f * displayHighlightConfidence) *
                    (1.0f - 0.68f * indoorLowLightConfidence),
            0.0f,
            1.0f
    );
    const bool outdoorSkyScene = outdoorSkyConfidence >= 0.72f;
    std::string outdoorSkyRejectedReason = "none";
    if (!outdoorSkyScene) {
        if (displayHighlightConfidence >= 0.35f) {
            outdoorSkyRejectedReason = "localized_rectangular_display_evidence";
        } else if (indoorLowLightConfidence >= 0.60f) {
            outdoorSkyRejectedReason = "low_light_indoor_statistics";
        } else if (skyCoverageConfidence < 0.55f) {
            outdoorSkyRejectedReason = "insufficient_broad_sky_coverage";
        } else {
            outdoorSkyRejectedReason = "outdoor_sky_confidence_below_threshold";
        }
    }

    const bool strongHighlightScene = !outdoorSkyScene && p50 < 0.06f &&
            (p99Maximum > 0.92f || overRangeFraction > 0.0005f);

    // Phase 5: automatic RAW tone has exactly one scene-referred owner: physical-noise-aware
    // FLLF in pure log2 luminance. There is no post-demosaic global tone/exposure curve and no
    // automatic post-CCM camera-colour render. The Phase-2 calibrated matrix/HueSatMap result is
    // already present in this scene-linear RGB and remains the camera-colour authority.
    const float phase5FllfPhysicalNoiseSigmaY = static_cast<float>(std::sqrt(std::max(
            0.0, residualNoiseState.postLinearDetail.varianceY)));
    const float phase5FllfNoiseModelConfidence = static_cast<float>(std::clamp(
            residualNoiseState.postLinearDetail.confidence, 0.0, 1.0));
    const bncam::tone::FastLocalLaplacianPlan fllfPlan =
            bncam::tone::resolveFastLocalLaplacianPlan({
                    p50,
                    p75,
                    p95,
                    p99Maximum,
                    static_cast<float>(fullRaw16SaturatedPct),
                    nearWhiteFraction,
                    phase5FllfPhysicalNoiseSigmaY,
                    phase5FllfNoiseModelConfidence,
                    lowLightScene,
                    outdoorSkyScene,
                    strongHighlightScene
            });
    const bncam::tone::ProfileToneRenderPlan profileTonePlan =
            bncam::tone::resolveProfileToneRenderPlan({
                    uiConfig.profileToneExposure,
                    uiConfig.profileToneHighlights,
                    uiConfig.profileToneShadows,
                    uiConfig.profileToneWhites,
                    uiConfig.profileToneBlacks,
                    uiConfig.profileToneContrast
            });
    const float effectiveToneContrastStrength = std::clamp(
            profileTonePlan.contrastDelta, -0.08f, 0.22f);
    const float highlightOccupancyPct = 100.0f * nearWhiteFraction;

    // Camera2 acquisition remains responsible for shutter/ISO. Automatic software exposure is
    // neutral 1x, so FLLF sees the unmodified calibrated scene and is the sole automatic
    // brightness/DR placement owner. Explicit profile Exposure remains a separate scene-linear
    // user control AFTER FLLF and BEFORE the display mapper.
    const float profileExposureGain = std::clamp(
            profileTonePlan.exposureMultiplier, 0.025f, 32.0f);
    const float postRawGain = std::clamp(
            static_cast<float>(isoState.postRawSensitivityBoost) / 100.0f, 1.0f, 16.0f);
    const float exposureGain = profileExposureGain * postRawGain;

    const char* highlightProtectionMode =
            (highlightDebug.applied || displayHighlightScene || strongHighlightScene) ? "local" : "none";
    std::string localHighlightProtectionReason = "none";
    if (displayHighlightScene) {
        localHighlightProtectionReason = "localized_display_in_dark_indoor_scene";
    } else if (highlightDebug.applied) {
        localHighlightProtectionReason = "isolated_recoverable_highlights";
    } else if (strongHighlightScene) {
        localHighlightProtectionReason = "localized_highlight_tail";
    }
    const std::string sceneClassificationFinal = displayHighlightScene
            ? "indoor_display_highlight"
            : (outdoorSkyScene
                    ? "outdoor_sky"
                    : (indoorLowLightConfidence >= 0.60f
                            ? "indoor_low_light"
                            : (strongHighlightScene ? "localized_highlight" : "normal")));

    std::atomic<bool> highlightNeutralize{false};

    // === POINTWISE PASS 1: EXPOSURE, NEUTRALIZATION, ROLLOFF, LOOK ===
    const bool toneCurveActive = curveActiveIsp(uiConfig.toneCurve);
    const bool gammaCurveActive = curveActiveIsp(uiConfig.gammaCurve);
    const bool sectionCurveActive = curveActiveIsp(uiConfig.sectionCurve);
    const bool anyCurveActive = toneCurveActive || gammaCurveActive || sectionCurveActive;
    // Automatic lower-midtone presentation lift is retired; FLLF owns automatic local tone.

    // Delta 33: feed-forward Tone Guard. The automatic low-light lift is the only tone control
    // attenuated here; user tone/gamma/section curves, WB/CCM, exposure and explicit shadow-lift
    // settings remain untouched. SPECTRA Off is kept bit-for-bit on the former lift amount.
    const float toneGuardWbCcmAmplificationRisk = demosaicCfaEvidence.available &&
            wbCcmChromaRmsGainCombined > 0.0
            ? baselineSmoothstep(
                    1.05f,
                    1.80f,
                    static_cast<float>(wbCcmChromaRmsGainCombined)
            )
            : 0.0f;
    const float toneGuardDemosaicCalibrationRisk = demosaicCfaEvidence.available &&
            residualNoiseState.demosaicMeasuredChromaCalibrationReady
            ? baselineSmoothstep(
                    1.00f,
                    1.50f,
                    residualNoiseState.demosaicMeasuredChromaAuthorityPressure
            )
            : 0.0f;
    const float toneGuardCfaBandRisk = demosaicCfaEvidence.available
            ? std::clamp(
                    0.45f * demosaicCfaEvidence.fineCorrectionConfidence +
                    0.35f * demosaicCfaEvidence.midCorrectionConfidence +
                    0.20f * demosaicCfaEvidence.lowCorrectionConfidence,
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardCfaOpponentRisk = demosaicCfaEvidence.available
            ? std::clamp(
                    0.65f * std::max(
                            demosaicCfaEvidence.redOpponentCorrectionConfidence,
                            demosaicCfaEvidence.blueOpponentCorrectionConfidence
                    ) +
                    0.35f * 0.5f * (
                            demosaicCfaEvidence.redOpponentCorrectionConfidence +
                            demosaicCfaEvidence.blueOpponentCorrectionConfidence
                    ),
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardCfaRisk = demosaicCfaEvidence.available
            ? std::clamp(
                    (0.60f * toneGuardCfaBandRisk + 0.40f * toneGuardCfaOpponentRisk) *
                    (1.0f - 0.25f * std::clamp(
                            demosaicCfaEvidence.structureProtection,
                            0.0f,
                            1.0f
                    )),
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardPropagationConfidence = demosaicCfaEvidence.available
            ? std::clamp(
                    static_cast<float>(residualNoiseState.postColourTransform.confidence),
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardBaseFeedForwardRisk = demosaicCfaEvidence.available
            ? std::clamp(
                    (0.50f * toneGuardWbCcmAmplificationRisk +
                     0.30f * toneGuardDemosaicCalibrationRisk +
                     0.20f * toneGuardCfaRisk) * toneGuardPropagationConfidence,
                    0.0f,
                    1.0f
            )
            : 0.0f;
    // Delta 44: a supported low-frequency chroma-cloud signal can strengthen the existing
    // automatic low-light Tone Guard, but cannot exceed its existing 25% attenuation ceiling.
    // Cloud evidence only fills part of the remaining risk headroom, so it cannot dominate a
    // capture whose WB/CCM/demosaic/CFA propagation otherwise looks clean.
    const float toneGuardCloudRisk = demosaicCfaEvidence.available
            ? std::clamp(
                    residualNoiseState.demosaicChromaCloudRiskEvidence *
                            (0.60f + 0.40f * residualNoiseState.demosaicChromaFieldCoherence),
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardFeedForwardRisk = std::clamp(
            toneGuardBaseFeedForwardRisk +
                    0.35f * toneGuardCloudRisk * (1.0f - toneGuardBaseFeedForwardRisk),
            0.0f,
            1.0f
    );
    // Automatic lower-midtone and post-CCM camera-look mutations remain retired. Physical noise
    // continues to gate denoise/detail/FLLF; calibrated Phase-2 colour remains the camera owner.
    const auto phase5ToneStageStart = IspClock::now();

    struct ToneLutEntry {
        float curvedLuma;
        float safeMidtoneGate;
        float toneCurveDerivative;
        float sectionCurveDerivative;
        float gammaCurveDerivative;
    };
    std::array<ToneLutEntry, 4096> toneLookLut{};
    for (int i = 0; i < 4096; ++i) {
        const float lookLuma = std::max(1.0e-6f, static_cast<float>(i) / 4095.0f);
        // Exposure is already applied scene-linearly between FLLF and PBR Neutral (retired lookLuma * profileExposureGain).
        // This LUT owns only explicit display-linear range/contrast/curve controls.
        const float anchoredLuma = lookLuma;
        const float contrastHighlightGate = 1.0f - baselineSmoothstep(0.75f, 0.96f, anchoredLuma);
        const float contrastShadowGate = baselineSmoothstep(0.05f, 0.35f, anchoredLuma);
        const float contrastDelta = effectiveToneContrastStrength * contrastHighlightGate * contrastShadowGate *
                                     anchoredLuma * (1.0f - anchoredLuma) * (anchoredLuma - 0.5f);
        float curvedLuma = std::clamp(anchoredLuma + contrastDelta, 0.0f, 1.0f);
        // RAW Phase 5: automatic display mapping has already been completed by Khronos PBR
        // Neutral. Everything in this LUT is explicit profile intent only.
        curvedLuma = bncam::tone::applyProfileTonalRanges(curvedLuma, profileTonePlan);
        const float toneCurveDerivative = toneCurveActive
                ? evalCurveDerivativeIsp(uiConfig.toneCurve, curvedLuma)
                : 1.0f;
        if (toneCurveActive) curvedLuma = evalCurveIsp(uiConfig.toneCurve, curvedLuma);
        const float sectionCurveDerivative = sectionCurveActive
                ? evalCurveDerivativeIsp(uiConfig.sectionCurve, curvedLuma)
                : 1.0f;
        if (sectionCurveActive) curvedLuma = evalCurveIsp(uiConfig.sectionCurve, curvedLuma);
        const float gammaCurveDerivative = gammaCurveActive
                ? evalCurveDerivativeIsp(uiConfig.gammaCurve, curvedLuma)
                : 1.0f;
        if (gammaCurveActive) curvedLuma = evalCurveIsp(uiConfig.gammaCurve, curvedLuma);

        const float deepShadowNoiseGate = baselineSmoothstep(0.018f, 0.065f, curvedLuma);
        const float safeShadowGate = baselineSmoothstep(0.025f, 0.11f, curvedLuma) *
                (1.0f - baselineSmoothstep(0.20f, 0.42f, curvedLuma));
        const float safeMidtoneGate = baselineSmoothstep(0.065f, 0.18f, curvedLuma) *
                (1.0f - baselineSmoothstep(0.42f, 0.70f, curvedLuma));
        const float localDisplayHighlightProtection =
                1.0f - baselineSmoothstep(0.50f, 0.78f, curvedLuma);
        toneLookLut[i] = {
            curvedLuma,
            safeMidtoneGate,
            toneCurveDerivative,
            sectionCurveDerivative,
            gammaCurveDerivative
        };
    }
    // Compact interleaved LUT consumed by the 8H-J pointwise Vulkan tone kernel. The
    // derivative fields remain CPU-side because propagation works on ~50k compact samples.
    std::array<float, 4096u * 2u> vulkanToneLut{};
    for (std::size_t i = 0u; i < toneLookLut.size(); ++i) {
        vulkanToneLut[i * 2u + 0u] = toneLookLut[i].curvedLuma;
        vulkanToneLut[i * 2u + 1u] = toneLookLut[i].safeMidtoneGate;
    }

    const auto tonePropagationStart = IspClock::now();
    std::vector<double> actualToneDerivatives;
    std::vector<double> actualSectionDerivatives;
    std::vector<double> actualGammaDerivatives;
    std::vector<double> actualTotalToneDerivatives;
    std::vector<double> actualToneChromaScales;
    actualToneDerivatives.reserve(lumaSamples.size());
    actualSectionDerivatives.reserve(lumaSamples.size());
    actualGammaDerivatives.reserve(lumaSamples.size());
    actualTotalToneDerivatives.reserve(lumaSamples.size());
    actualToneChromaScales.reserve(lumaSamples.size());

    const auto lutTotalDerivativeAt = [&](int index) -> double {
        const int previous = std::max(0, index - 1);
        const int next = std::min(4095, index + 1);
        const double dx = static_cast<double>(next - previous) / 4095.0;
        if (dx <= 0.0) return 1.0;
        const double derivative = (
                static_cast<double>(toneLookLut[next].curvedLuma) -
                static_cast<double>(toneLookLut[previous].curvedLuma)
        ) / dx;
        return std::max(0.0, std::isfinite(derivative) ? derivative : 1.0);
    };

    // FLLF is spatial, so compact covariance planning cannot reproduce its exact per-pixel
    // Jacobian without a full-frame readback. Use the maximum permitted positive strict-ratio
    // gain as a conservative bound; local compression receives no fictitious denoise credit.
    const float phase5FllfConservativePropagationGain = fllfPlan.enabled
            ? std::exp2(std::max(0.0f, fllfPlan.maxLiftEv))
            : 1.0f;

    std::vector<double> actualToneRgJacobians;
    std::vector<double> actualToneBgJacobians;
    actualToneRgJacobians.reserve(sceneRgbSamples.size());
    actualToneBgJacobians.reserve(sceneRgbSamples.size());

    // Exact opponent inverse columns: a unit perturbation changes only Y, R-G, or B-G at input.
    const cv::Vec3f phase5YAxis(1.0f, 1.0f, 1.0f);
    const cv::Vec3f phase5RgAxis(0.7874f, -0.2126f, -0.2126f);
    const cv::Vec3f phase5BgAxis(-0.0722f, -0.0722f, 0.9278f);
    const auto phase5Opponent = [](const cv::Vec3f& rgb) noexcept -> cv::Vec3f {
        return cv::Vec3f(
                phase5LumaCpu(rgb),
                rgb[0] - rgb[1],
                rgb[2] - rgb[1]);
    };

    for (const cv::Vec3f& sampledRgb : sceneRgbSamples) {
        cv::Vec3f scene(
                std::max(0.0f, sampledRgb[0]),
                std::max(0.0f, sampledRgb[1]),
                std::max(0.0f, sampledRgb[2]));
        scene *= phase5FllfConservativePropagationGain;
        scene *= exposureGain;
        const cv::Vec3f pbrMid = phase5PbrNeutralCpu(scene);
        const double pbrLuma = std::max(0.0, static_cast<double>(phase5LumaCpu(pbrMid)));
        const float reference = std::max(0.02f, phase5LumaCpu(scene));
        const float eps = std::max(1.0e-5f, 0.002f * reference);

        const auto numericAxisDerivative = [&](const cv::Vec3f& axis) noexcept -> cv::Vec3f {
            const cv::Vec3f low = phase5PbrNeutralCpu(scene - axis * eps);
            const cv::Vec3f high = phase5PbrNeutralCpu(scene + axis * eps);
            return (phase5Opponent(high) - phase5Opponent(low)) *
                    (1.0f / std::max(2.0f * eps, 1.0e-8f));
        };
        const cv::Vec3f yJacobian = numericAxisDerivative(phase5YAxis);
        const cv::Vec3f rgJacobian = numericAxisDerivative(phase5RgAxis);
        const cv::Vec3f bgJacobian = numericAxisDerivative(phase5BgAxis);

        // PBR Neutral can weakly couple the opponent axes during its highlight desaturation. Use
        // the norm of each numerical opponent response as a conservative diagonal envelope for the
        // existing propagated Y/(R-G)/(B-G) covariance contract.
        const double pbrYGain = std::max(0.0, std::abs(static_cast<double>(yJacobian[0])));
        const double pbrRgGain = std::hypot(
                static_cast<double>(rgJacobian[1]), static_cast<double>(rgJacobian[2]));
        const double pbrBgGain = std::hypot(
                static_cast<double>(bgJacobian[1]), static_cast<double>(bgJacobian[2]));

        const double clampedLook = std::clamp(pbrLuma, 0.0, 1.0);
        const double lutPosition = clampedLook * 4095.0;
        const int lutIndex = std::clamp(static_cast<int>(std::floor(lutPosition)), 0, 4095);
        const int lutNext = std::min(4095, lutIndex + 1);
        const double fraction = lutPosition - static_cast<double>(lutIndex);
        const auto interpolate = [&](float ToneLutEntry::*member) -> double {
            return static_cast<double>(toneLookLut[lutIndex].*member) * (1.0 - fraction) +
                    static_cast<double>(toneLookLut[lutNext].*member) * fraction;
        };
        const double curvedLuma = interpolate(&ToneLutEntry::curvedLuma);
        const double profileLumaDerivative = lutTotalDerivativeAt(lutIndex);
        const double profileChromaScale = curvedLuma / std::max(pbrLuma, 1.0e-6);

        const double sceneLinearUserExposureGain = static_cast<double>(exposureGain);
        const double totalYGain = static_cast<double>(phase5FllfConservativePropagationGain) *
                sceneLinearUserExposureGain * pbrYGain * profileLumaDerivative;
        const double totalRgGain = static_cast<double>(phase5FllfConservativePropagationGain) *
                sceneLinearUserExposureGain * pbrRgGain * profileChromaScale;
        const double totalBgGain = static_cast<double>(phase5FllfConservativePropagationGain) *
                sceneLinearUserExposureGain * pbrBgGain * profileChromaScale;

        actualToneDerivatives.push_back(interpolate(&ToneLutEntry::toneCurveDerivative));
        actualSectionDerivatives.push_back(interpolate(&ToneLutEntry::sectionCurveDerivative));
        actualGammaDerivatives.push_back(interpolate(&ToneLutEntry::gammaCurveDerivative));
        actualTotalToneDerivatives.push_back(std::max(0.0, totalYGain));
        actualToneRgJacobians.push_back(std::max(0.0, totalRgGain));
        actualToneBgJacobians.push_back(std::max(0.0, totalBgGain));
        actualToneChromaScales.push_back(std::max({0.0, totalRgGain, totalBgGain}));
    }

    residualNoiseState.toneCurveDerivative =
            derivativeStatsFromSamples(std::move(actualToneDerivatives));
    residualNoiseState.sectionCurveDerivative =
            derivativeStatsFromSamples(std::move(actualSectionDerivatives));
    residualNoiseState.gammaCurveDerivative =
            derivativeStatsFromSamples(std::move(actualGammaDerivatives));
    residualNoiseState.totalToneDerivative =
            derivativeStatsFromSamples(std::move(actualTotalToneDerivatives));
    residualNoiseState.toneChromaScale =
            derivativeStatsFromSamples(std::move(actualToneChromaScales));
    const auto phase5ToneRgJacobian = derivativeStatsFromSamples(std::move(actualToneRgJacobians));
    const auto phase5ToneBgJacobian = derivativeStatsFromSamples(std::move(actualToneBgJacobians));
    residualNoiseState.postTone = bncam::spectra2::propagateOpponentGains(
            residualNoiseState.postLinearDetail,
            residualNoiseState.totalToneDerivative.rms,
            phase5ToneRgJacobian.rms,
            phase5ToneBgJacobian.rms,
            "POST_PHASE5_FLLF_PBR_NEUTRAL_PROFILE_TONE_PRE_PROFILE_COLOR",
            "FLLF_CONSERVATIVE_STRICT_RATIO_PLUS_NUMERIC_PBR_NEUTRAL_Y_RG_BG_JACOBIANS",
            0.86
    );
    residualNoiseState.tonePropagationMs = elapsedMs(tonePropagationStart);

    // Delta 32: explicit tone-domain amplification audit and bounded Tone Guard input.
    // This delta does not alter the LUT or pixels; it only quantifies whether tone processing is
    // enlarging propagated opponent noise enough to justify a later local guard.
    const double postToneVarianceRg = std::max(0.0, residualNoiseState.postTone.varianceRG);
    const double postToneVarianceBg = std::max(0.0, residualNoiseState.postTone.varianceBG);
    const double postToneVarianceY = std::max(0.0, residualNoiseState.postTone.varianceY);
    // Residual covariance planning freezes here, before optional perceptual detail.
    // Intentional profile sharpness must not be reclassified as sensor residual noise.
    const float postToneResidualModelConfidence = static_cast<float>(
            residualNoiseState.postTone.confidence);
    const double postLinearDetailVarianceY =
            std::max(0.0, residualNoiseState.postLinearDetail.varianceY);
    const double postToneCombinedChromaVariance =
            0.5 * (postToneVarianceRg + postToneVarianceBg);
    const double toneChromaRmsGainRg =
            demosaicSafeRmsRatio(postToneVarianceRg, postColourVarianceRg);
    const double toneChromaRmsGainBg =
            demosaicSafeRmsRatio(postToneVarianceBg, postColourVarianceBg);
    const double toneChromaRmsGainCombined = demosaicSafeRmsRatio(
            postToneCombinedChromaVariance, postColourCombinedChromaVariance);
    const double toneLumaRmsGain =
            demosaicSafeRmsRatio(postToneVarianceY, postLinearDetailVarianceY);
    const float toneChromaAmplificationRisk = toneChromaRmsGainCombined > 0.0
            ? baselineSmoothstep(
                    1.05f,
                    1.75f,
                    static_cast<float>(toneChromaRmsGainCombined)
            ) * std::clamp(
                    static_cast<float>(residualNoiseState.postColourTransform.confidence),
                    0.0f,
                    1.0f
            )
            : 0.0f;
    const float toneGuardSuggestedAttenuation = std::clamp(
            0.25f * toneChromaAmplificationRisk,
            0.0f,
            0.25f
    );

    // Phase 12 remains the explicit profile-owned perceptual/output detail stage. Automatic
    // Phase-11 capture detail is retired; with all profile detail controls at zero this policy is
    // exact identity by contract.
    const float phase12DisplayLumaSigma = static_cast<float>(std::sqrt(postToneVarianceY));
    const bncam::perceptual_detail::Plan perceptualDetailPlan = bncam::perceptual_detail::resolve(
            {uiConfig.profileDetailAmount, uiConfig.profileDetailRadius,
             uiConfig.profileDetailDetail, uiConfig.profileDetailMasking},
            {perceptualDetailPhysicalNoiseAvailable,
             phase12DisplayLumaSigma,
             meta.calibration.signalModelConfidence});
    if (perceptualDetailPlan.enabled) {
        residualNoiseState.postTone = bncam::spectra2::propagateOpponentGains(
                residualNoiseState.postTone,
                std::sqrt(std::max(1.0f, perceptualDetailPlan.predictedLumaVarianceGain)),
                1.0,
                1.0,
                "POST_PHASE12_PERCEPTUAL_DETAIL",
                "PROFILE_OUTPUT_DETAIL_PHYSICAL_NOISE_GATED_UPPER_BOUND",
                0.84);
    }

    residualNoiseState.varianceY = static_cast<float>(residualNoiseState.postTone.varianceY);
    residualNoiseState.varianceRG = static_cast<float>(residualNoiseState.postTone.varianceRG);
    residualNoiseState.varianceBG = static_cast<float>(residualNoiseState.postTone.varianceBG);
    residualNoiseState.covarianceRgBg =
            static_cast<float>(residualNoiseState.postTone.covarianceRgBg);
    for (size_t index = 0; index < residualNoiseState.covarianceRgb.size(); ++index) {
        residualNoiseState.covarianceRgb[index] = static_cast<float>(
                residualNoiseState.postTone.covariance.values[index]
        );
    }
    residualNoiseState.modelConfidence = static_cast<float>(residualNoiseState.postTone.confidence);

    bncam::vulkan::SpectraResidentToneResult vulkanTone{};
    bool vulkanToneApplied = false;
    if (vulkanSceneObserverActive) {
        bncam::vulkan::SpectraResidentToneRequest request{};
        request.frameWidth = static_cast<std::uint32_t>(demosaicInputWidth);
        request.frameHeight = static_cast<std::uint32_t>(demosaicInputHeight);
        request.residentSceneGeneration = vulkanSceneObserver.residentSceneGeneration;
        // Explicit profile Exposure and HAL post-RAW sensitivity boost are transported as one
        // scene-linear scalar. The shader applies it after FLLF and before PBR Neutral.
        request.exposureGain = exposureGain;
        request.rawJpegBaseVibrance = 1.0f;
        request.shoulderStart = 0.68f;
        request.shoulderStrength = 1.0f;
        request.localToneStrength = 0.0f;
        request.localToneSceneKey = fllfPlan.sceneKey;
        request.localToneMaxLiftEv = 0.0f;
        request.localToneMaxCompressEv = 0.0f;
        request.fllfEnabled = fllfPlan.enabled;
        request.fllfStrength = fllfPlan.strength;
        request.fllfSceneKey = fllfPlan.sceneKey;
        request.fllfMaxLiftEv = fllfPlan.maxLiftEv;
        request.fllfMaxCompressEv = fllfPlan.maxCompressEv;
        request.fllfEdgeStopEv = fllfPlan.edgeStopEv;
        request.fllfRefinement = fllfPlan.refinement;
        request.fllfPhysicalNoiseSigmaY = fllfPlan.physicalNoiseSigmaY;
        request.fllfPyramidLevels = fllfPlan.enabled ? fllfPlan.pyramidLevels : 0u;
        // N004 compatibility bridge: the mixed resident-tone ABI still carries Phase-11 fields,
        // but automatic linear-detail authority is hard-neutral. N006 removes the retired backend
        // fields/shader implementation after mixed responsibilities are split.
        request.linearDetailEnabled = false;
        request.linearDetailAuthority = 0.0f;
        request.linearDetailRadius = 1.0f;
        request.linearDetailEmphasis = 0.0f;
        request.linearDetailMasking = 0.0f;
        request.linearDetailMinimumResidualSnr = 1.0f;
        request.linearDetailMinimumGradientSnr = 1.0f;
        request.linearDetailHardHaloLimit = 0.0f;
        request.linearDetailNoiseSigmaY = 0.0f;
        request.linearDetailReferenceSignal = 0.18f;
        request.linearDetailShotNoiseFraction = 0.0f;
        request.linearDetailModelConfidence = 0.0f;
        request.perceptualDetailEnabled = perceptualDetailPlan.enabled;
        request.perceptualDetailAuthority = perceptualDetailPlan.authority;
        request.perceptualDetailRadius = perceptualDetailPlan.radius;
        request.perceptualDetailEmphasis = perceptualDetailPlan.detail;
        request.perceptualDetailMasking = perceptualDetailPlan.masking;
        request.perceptualDetailNoiseSigmaY = perceptualDetailPlan.displayLumaSigma;
        request.perceptualDetailMinimumResidualSnr = perceptualDetailPlan.minimumResidualSnr;
        request.perceptualDetailMinimumGradientSnr = perceptualDetailPlan.minimumGradientSnr;
        request.perceptualDetailHardHaloLimit = perceptualDetailPlan.hardHaloLimit;
        request.perceptualDetailModelConfidence = perceptualDetailPlan.modelConfidence;
        request.isRawBayer = true;
        request.profileColorSaturation = uiConfig.profileColorSaturation;
        request.profileColorContrast = uiConfig.profileColorContrast;
        request.profilePresenceVibrance = uiConfig.profilePresenceVibrance;
        request.profilePresencePop = uiConfig.profilePresencePop;
        request.profileColorRecovery = uiConfig.profileColorRecovery;
        request.toneLut = vulkanToneLut.data();
        request.toneLutFloatCount = vulkanToneLut.size();
        request.ultraHdrGainmapRequested = uiConfig.ultraHdrGainmapEnabled;
        request.outputRotationDegrees = rotationDegrees;
        request.portraitEffectRequested = uiConfig.portraitEffectEnabled;
        request.portraitMask = uiConfig.portraitMask;
        request.portraitMaskFloatCount = uiConfig.portraitMaskFloatCount;
        request.portraitMaskWidth = uiConfig.portraitMaskWidth;
        request.portraitMaskHeight = uiConfig.portraitMaskHeight;
        request.portraitTargetLeft = uiConfig.portraitTargetLeft;
        request.portraitTargetTop = uiConfig.portraitTargetTop;
        request.portraitTargetRight = uiConfig.portraitTargetRight;
        request.portraitTargetBottom = uiConfig.portraitTargetBottom;
        request.portraitMaskRotationDegrees = uiConfig.portraitMaskRotationDegrees;
        // Phase N003: legacy post-demosaic NR/visible-chroma ownership is retired.
        // Materialize the tone output directly for neutral quantization/publication.
        request.deferFullReadback = false;
        vulkanTone = bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentTone(request);
        if (vulkanTone.ultraHdrGainmapGenerated && vulkanTone.ultraHdrMeaningfulHeadroom &&
            !vulkanTone.ultraHdrGainmapBytes.empty()) {
            UltraHdrGainmapArtifact artifact{};
            artifact.valid = true;
            artifact.width = vulkanTone.ultraHdrGainmapWidth;
            artifact.height = vulkanTone.ultraHdrGainmapHeight;
            artifact.rowStrideBytes = vulkanTone.ultraHdrGainmapRowStrideBytes;
            artifact.minContentBoost = vulkanTone.ultraHdrMinContentBoost;
            artifact.maxContentBoost = vulkanTone.ultraHdrMaxContentBoost;
            artifact.gamma = vulkanTone.ultraHdrGamma;
            artifact.offsetSdr = vulkanTone.ultraHdrOffsetSdr;
            artifact.offsetHdr = vulkanTone.ultraHdrOffsetHdr;
            artifact.pixels = std::move(vulkanTone.ultraHdrGainmapBytes);
            artifact.status = vulkanTone.ultraHdrStatus;
            g_threadLocalUltraHdrGainmapArtifact = std::move(artifact);
        } else if (request.ultraHdrGainmapRequested) {
            g_threadLocalUltraHdrGainmapArtifact.status = vulkanTone.ultraHdrStatus;
        }
        if (vulkanTone.success && vulkanTone.residentToneGeneration != 0u) {
            if (vulkanTone.outputRgb.size() == expectedColorPixels * 3u) {
                linearRgb = cv::Mat(
                        demosaicInputHeight, demosaicInputWidth, CV_32FC3,
                        vulkanTone.outputRgb.data());
            }
            vulkanToneApplied = true;
            if (vulkanTone.highlightNeutralizeApplied) {
                highlightNeutralize.store(true, std::memory_order_relaxed);
            }
        }
    }
    if (!vulkanToneApplied && vulkanSceneObserverActive) {
        // The scene observer already mutated only its private resident working buffer. Rebuild
        // the post-CCM CPU reference and apply the same highlight recovery before the CPU tone
        // fallback. This path executes only after an explicit Vulkan tone failure.
        materializeCpuPostCcmReference();
        syncHighlightDebugFromPhase9();
        cpuSceneProcessingApplied = true;
    }

    const auto applyCpuToneAndProfile = [&]() {
        cv::parallel_for_(cv::Range(0, linearRgb.rows), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                cv::Vec3f* row = linearRgb.ptr<cv::Vec3f>(y);
                for (int x = 0; x < linearRgb.cols; ++x) {
                    // Failure-reference only. Heavy FLLF remains Vulkan-resident; if that backend
                    // failed, do not invent a CPU local-tone implementation. Preserve the same final
                    // display mapper and explicit profile stage for a deterministic degraded reference.
                    const cv::Vec3f pbr = phase5PbrNeutralCpu(row[x] * exposureGain);
                    float r = pbr[0];
                    float g = pbr[1];
                    float b = pbr[2];
                    const float lookLuma = std::max(1.0e-6f, phase5LumaCpu(pbr));

                    // Explicit profile/user LUT only; automatic scene mapping is already complete.
                    const float lutIdxF = std::clamp(lookLuma * 4095.0f, 0.0f, 4095.0f);
                    const int lutIdx = static_cast<int>(lutIdxF);
                    const float frac = lutIdxF - static_cast<float>(lutIdx);
                    const int nextIdx = std::min(4095, lutIdx + 1);
                    const float curvedLuma =
                            toneLookLut[lutIdx].curvedLuma * (1.0f - frac) +
                            toneLookLut[nextIdx].curvedLuma * frac;
                    const float lookScale = curvedLuma / lookLuma;
                    r *= lookScale;
                    g *= lookScale;
                    b *= lookScale;

                    // Perceptual Midtone Chromaticity & Saturation Adaptation (RAW display presentation parity)
                    const float toneLumaWeight = smoothstepIsp(0.008f, 0.07f, lookLuma) *
                            (1.0f - smoothstepIsp(0.72f, 0.98f, lookLuma));
                    const float maxC = std::max({r, g, b});
                    const float minC = std::min({r, g, b});
                    const float satC = maxC > 1.0e-5f ? (maxC - minC) / maxC : 0.0f;
                    const float satProt = 1.0f - 0.45f * satC;
                    const float adaptSat = 0.28f * toneLumaWeight * satProt;
                    const float adaptVib = 0.14f * toneLumaWeight * (1.0f - 0.70f * satC);
                    const float totalGain = 1.0f + adaptSat + adaptVib;
                    r = lookLuma + (r - lookLuma) * totalGain;
                    g = lookLuma + (g - lookLuma) * totalGain;
                    b = lookLuma + (b - lookLuma) * totalGain;

                    applyBncamProfileColorManagement(r, g, b, uiConfig, false);
                    const cv::Vec3f mapped = phase5CompressUnitGamutCpu(cv::Vec3f(r, g, b));
                    row[x] = mapped;
                }
            }
        });
    };
    if (!vulkanToneApplied) {
        applyCpuToneAndProfile();
    }
    // Keep the RAW mosaic until the 8H-K tone->post-demosaic resident handoff has either
    // succeeded or selected its typed fallback. That preserves deterministic recovery without
    // forcing a normal full-frame tone readback.
    const float phase5ToneStageMs = elapsedMs(phase5ToneStageStart);

    // === PHASE N003: NEUTRAL POST-TONE PATH ===
    // Sensor S/O and propagated covariance remain measurement-only. None of the values below
    // grants post-demosaic pixel authority; the future SPECTRA Neural owner will consume them.
    const bool spectraNoiseActive = meta.calibration.spectraProcessingMode != 0;
    const bool physicalNoiseModelAvailable = meta.calibration.noiseModelMode != 0 &&
            meta.calibration.hasNoiseProfile && meta.calibration.noiseProfileApplied;
    const float lensIsoNrReference = static_cast<float>(std::max(actualIso, 0));
    const float referenceFrameIso = std::max(1.0f, lensIsoNrReference);

    // Legacy late-denoise tuning values are deliberately neutralized. They remain named only
    // so existing diagnostics stay parseable until the final Phase-1 telemetry purge.
    constexpr float minimumDenoiseStrength = 0.0f;
    constexpr float maximumDenoiseCeiling = 0.0f;
    const float baseChromaNrStrength = 0.0f;
    const float residualChromaStrengthScale = 1.0f;
    const float physicalNoiseBaseline = 0.0f;
    const float clampedBaseline = 0.0f;
    const float availableHeadroom = 0.0f;
    const float noiseTruthActivation = 0.0f;
    const float configuredDynamicIsoCoeff = std::clamp(uiConfig.lensDynamicIsoCoeff, 0.0f, 1.0f);
    const float normalizedChromaAuthority = std::clamp(
            uiConfig.effectiveChromaAuthorityStops / 5.0f, 0.0f, 1.0f);
    const float dynamicBlend = 0.0f;
    const float requestedAdditionalStrength = 0.0f;
    const float finalChromaNrStrengthBeforeClamp = 0.0f;
    const float chromaNrStrength = 0.0f;
    const float dynamicIsoMultiplier = 1.0f;
    const bool ceilingReached = false;
    const float theoreticalSaturatingCoeff = 0.0f;

    std::ostringstream nativeNoiseSo;
    nativeNoiseSo << std::setprecision(17) << "[";
    const int nativeNoiseValueCount = std::clamp(meta.calibration.noiseProfilePairCount * 2, 0, 16);
    for (int i = 0; i < nativeNoiseValueCount; ++i) {
        if (i > 0) nativeNoiseSo << ",";
        nativeNoiseSo << meta.calibration.effectiveNoiseProfile[i];
    }
    nativeNoiseSo << "]";

    const auto finalOutputPassStart = IspClock::now();

    // Keep the physically propagated posterior as telemetry / future neural conditioning.
    const float postToneResidualLumaSigma =
            static_cast<float>(std::sqrt(postToneVarianceY));
    const float postToneResidualChromaSigma = static_cast<float>(std::sqrt(std::max(
            postToneVarianceRg, postToneVarianceBg)));

    const bool profileNoiseReductionRequested = false;
    const bool physicalChromaNoiseRequested = false;
    float requestedLumaSigma = 0.0f;
    float requestedChromaSigma = 0.0f;
    float appliedLumaSigma = 0.0f;
    float appliedChromaSigma = 0.0f;
    float effectiveOuterRingAuthority = 0.0f;
    const float lumaRangeThresholdMean = 0.0f;
    const float chromaRangeThresholdMean = 0.0f;

    g_threadLocalIspStats.absoluteMeanLumaSigma = postToneResidualLumaSigma;
    g_threadLocalIspStats.absoluteMeanChromaSigma = postToneResidualChromaSigma;
    g_threadLocalIspStats.effectiveLumaSigma = 0.0f;
    g_threadLocalIspStats.effectiveChromaSigma = 0.0f;
    g_threadLocalIspStats.lumaRangeThresholdMin = 0.0f;
    g_threadLocalIspStats.lumaRangeThresholdMean = 0.0f;
    g_threadLocalIspStats.lumaRangeThresholdMax = 0.0f;
    g_threadLocalIspStats.chromaRangeThresholdMin = 0.0f;
    g_threadLocalIspStats.chromaRangeThresholdMean = 0.0f;
    g_threadLocalIspStats.chromaRangeThresholdMax = 0.0f;
    g_threadLocalIspStats.preDenoiseResidualEstimate =
            postToneResidualChromaSigma * 1.414f + postToneResidualLumaSigma * 0.707f;
    g_threadLocalIspStats.postDenoiseResidualEstimate = g_threadLocalIspStats.preDenoiseResidualEstimate;
    g_threadLocalIspStats.postSharpenResidualEstimate = g_threadLocalIspStats.postDenoiseResidualEstimate;

    bool residentPostDemosaicApplied = false;
    bool residentOutputSrgbEncoded = false;
    cv::Mat residentPublishedBgr8;
    bncam::publication::Bgr8PublicationStats residentPublicationStats{};

    // 8H-K typed recovery: if the resident tone->post-demosaic handoff failed (or the
    // post-demosaic stage is intentionally bypassed), materialize the already-computed tone
    // generation once. Only if that readback itself fails do we regenerate the CPU reference.
    if (!residentPostDemosaicApplied && linearRgb.empty() &&
        vulkanToneApplied && vulkanTone.residentToneGeneration != 0u) {
        std::vector<float> toneReadback;
        std::uint32_t toneWidth = 0u;
        std::uint32_t toneHeight = 0u;
        std::string toneReadbackFailure;
        const bool toneReadbackOk =
                bncam::vulkan::VulkanRuntime::instance().readbackSpectraResidentTone(
                        vulkanTone.residentToneGeneration,
                        toneReadback,
                        toneWidth,
                        toneHeight,
                        toneReadbackFailure);
        if (toneReadbackOk &&
            toneWidth == static_cast<std::uint32_t>(demosaicInputWidth) &&
            toneHeight == static_cast<std::uint32_t>(demosaicInputHeight) &&
            toneReadback.size() == expectedColorPixels * 3u) {
            linearRgb = cv::Mat(
                    demosaicInputHeight, demosaicInputWidth, CV_32FC3,
                    toneReadback.data()).clone();
        } else {
            materializeCpuPostCcmReference();
            syncHighlightDebugFromPhase9();
            applyCpuToneAndProfile();
            cpuSceneProcessingApplied = true;
        }
    }

    // No later stage needs the Bayer mosaic once a post-tone RGB surface is committed.
    jpegRaw.mosaic.release();

    const auto quantizeStart = IspClock::now();
    cv::Mat bgr8;
    std::atomic<uint64_t> finalRedClipped{0};
    std::atomic<uint64_t> finalGreenClipped{0};
    std::atomic<uint64_t> finalBlueClipped{0};
    std::atomic<uint64_t> toneRedSum{0};
    std::atomic<uint64_t> toneGreenSum{0};
    std::atomic<uint64_t> toneBlueSum{0};

    std::string finalOutputStatsSource = "CPU_QUANTIZATION_FUSED_EXACT";
    if (!residentPublishedBgr8.empty()) {
        // FASE 15: exact final-output telemetry is fused into the mandatory resident
        // GPU->BGR8 publication copy. A second full-frame CPU scan is retained only
        // as an exact fail-safe if the publication contract is ever incomplete.
        bgr8 = std::move(residentPublishedBgr8);
        const std::uint64_t residentPixelCount = static_cast<std::uint64_t>(bgr8.total());
        if (bncam::publication::hasCompleteBgr8PublicationStats(
                    residentPublicationStats, residentPixelCount)) {
            finalRedClipped.store(residentPublicationStats.redClipped, std::memory_order_relaxed);
            finalGreenClipped.store(residentPublicationStats.greenClipped, std::memory_order_relaxed);
            finalBlueClipped.store(residentPublicationStats.blueClipped, std::memory_order_relaxed);
            toneRedSum.store(residentPublicationStats.redSum, std::memory_order_relaxed);
            toneGreenSum.store(residentPublicationStats.greenSum, std::memory_order_relaxed);
            toneBlueSum.store(residentPublicationStats.blueSum, std::memory_order_relaxed);
            finalOutputStatsSource = "RESIDENT_PUBLICATION_COPY_FUSED_EXACT";
        } else {
            finalOutputStatsSource = "RESIDENT_BGR8_RESCAN_EXACT_FAILSAFE";
            cv::parallel_for_(cv::Range(0, bgr8.rows), [&](const cv::Range& range) {
                bncam::publication::Bgr8PublicationStats localStats{};
                for (int y = range.start; y < range.end; ++y) {
                    bncam::publication::accumulateBgr8Row(
                            bgr8.ptr<std::uint8_t>(y),
                            static_cast<std::size_t>(bgr8.cols), localStats);
                }
                finalRedClipped.fetch_add(localStats.redClipped, std::memory_order_relaxed);
                finalGreenClipped.fetch_add(localStats.greenClipped, std::memory_order_relaxed);
                finalBlueClipped.fetch_add(localStats.blueClipped, std::memory_order_relaxed);
                toneRedSum.fetch_add(localStats.redSum, std::memory_order_relaxed);
                toneGreenSum.fetch_add(localStats.greenSum, std::memory_order_relaxed);
                toneBlueSum.fetch_add(localStats.blueSum, std::memory_order_relaxed);
            });
        }
    } else {
        bgr8 = cv::Mat(linearRgb.size(), CV_8UC3);
        cv::parallel_for_(cv::Range(0, linearRgb.rows), [&](const cv::Range& range) {
            uint64_t localRedClipped = 0;
            uint64_t localGreenClipped = 0;
            uint64_t localBlueClipped = 0;
            uint64_t localRedSum = 0;
            uint64_t localGreenSum = 0;
            uint64_t localBlueSum = 0;
            for (int y = range.start; y < range.end; ++y) {
                const cv::Vec3f* inputRow = linearRgb.ptr<cv::Vec3f>(y);
                uint8_t* outputPtr = bgr8.ptr<uint8_t>(y);
                for (int x = 0; x < linearRgb.cols; ++x) {
                    const float r = inputRow[x][0];
                    const float g = inputRow[x][1];
                    const float b = inputRow[x][2];
                    const uint8_t outB = residentOutputSrgbEncoded
                            ? cv::saturate_cast<uint8_t>(std::clamp(b, 0.0f, 1.0f) * 255.0f)
                            : quantizeSrgb8(b);
                    const uint8_t outG = residentOutputSrgbEncoded
                            ? cv::saturate_cast<uint8_t>(std::clamp(g, 0.0f, 1.0f) * 255.0f)
                            : quantizeSrgb8(g);
                    const uint8_t outR = residentOutputSrgbEncoded
                            ? cv::saturate_cast<uint8_t>(std::clamp(r, 0.0f, 1.0f) * 255.0f)
                            : quantizeSrgb8(r);
                    if (outR >= 254u) ++localRedClipped;
                    if (outG >= 254u) ++localGreenClipped;
                    if (outB >= 254u) ++localBlueClipped;
                    localRedSum += outR;
                    localGreenSum += outG;
                    localBlueSum += outB;
                    outputPtr[x * 3 + 0] = outB;
                    outputPtr[x * 3 + 1] = outG;
                    outputPtr[x * 3 + 2] = outR;
                }
            }
            finalRedClipped.fetch_add(localRedClipped, std::memory_order_relaxed);
            finalGreenClipped.fetch_add(localGreenClipped, std::memory_order_relaxed);
            finalBlueClipped.fetch_add(localBlueClipped, std::memory_order_relaxed);
            toneRedSum.fetch_add(localRedSum, std::memory_order_relaxed);
            toneGreenSum.fetch_add(localGreenSum, std::memory_order_relaxed);
            toneBlueSum.fetch_add(localBlueSum, std::memory_order_relaxed);
        });
    }
    const float finalOutClampQuantMs = elapsedMs(quantizeStart);
    const float finalOutputPassMs = elapsedMs(finalOutputPassStart);

    const double finalPixelCount = std::max(1.0, static_cast<double>(bgr8.total()));
    const double finalRedClippedPct = 100.0 * finalRedClipped.load(std::memory_order_relaxed) / finalPixelCount;
    const double finalGreenClippedPct = 100.0 * finalGreenClipped.load(std::memory_order_relaxed) / finalPixelCount;
    const double finalBlueClippedPct = 100.0 * finalBlueClipped.load(std::memory_order_relaxed) / finalPixelCount;

    const double toneMeanR = static_cast<double>(toneRedSum.load(std::memory_order_relaxed)) / (finalPixelCount * 255.0);
    const double toneMeanG = static_cast<double>(toneGreenSum.load(std::memory_order_relaxed)) / (finalPixelCount * 255.0);
    const double toneMeanB = static_cast<double>(toneBlueSum.load(std::memory_order_relaxed)) / (finalPixelCount * 255.0);

    const auto quantizationPropagationStart = IspClock::now();
    const bncam::spectra2::NoiseState preQuantizationResidual = residualNoiseState.postTone;
    residualNoiseState.postQuantization = bncam::spectra2::propagateQuantization8Bit(
            preQuantizationResidual
    );
    residualNoiseState.downstreamSharpenPropagationMs = elapsedMs(quantizationPropagationStart);

    VisibleResidualMeasurement measuredPreSharpenResidual{};
    if (spectraNoiseActive) {
        const auto measuredPreSharpenStart = IspClock::now();
        measuredPreSharpenResidual = measureVisibleResidual8Bit(bgr8);
        residualNoiseState.measuredPreSharpenResidualMs = elapsedMs(measuredPreSharpenStart);
        residualNoiseState.measuredPreSharpenVarianceY =
                static_cast<float>(measuredPreSharpenResidual.varianceY);
        residualNoiseState.measuredPreSharpenVarianceRG =
                static_cast<float>(measuredPreSharpenResidual.varianceRG);
        residualNoiseState.measuredPreSharpenVarianceBG =
                static_cast<float>(measuredPreSharpenResidual.varianceBG);
        residualNoiseState.measuredPreSharpenCovarianceRgBg =
                static_cast<float>(measuredPreSharpenResidual.covarianceRgBg);
        residualNoiseState.measuredPreSharpenSampleCount = measuredPreSharpenResidual.sampleCount;
    }

    SpectraDownstreamIspState downstreamIspState{};
    // Phase 11 removed RAW capture-detail ownership from the post-quantization sharpener.
    // FASE 12 may later add a distinct perceptual/output-detail stage; until then this domain
    // remains identity so capture deconvolution is not duplicated after tone/quantization.
    downstreamIspState.plan.enabled = false;
    downstreamIspState.plan.spectraAware = false;
    downstreamIspState.plan.status = "RETIRED_PHASE11_LINEAR_DETAIL_OWNER";
    downstreamIspState.predictedVarianceGainY = 1.0f;
    downstreamIspState.measuredVarianceGainY = 1.0f;
    downstreamIspState.resultStatus = "RETIRED_PHASE11_LINEAR_DETAIL_OWNER";


    const auto measuredVisibleResidualStart = IspClock::now();
    const VisibleResidualMeasurement measuredVisibleResidual = spectraNoiseActive
            ? measuredPreSharpenResidual
            : measureVisibleResidual8Bit(bgr8);
    residualNoiseState.measuredVisibleResidualMs = elapsedMs(measuredVisibleResidualStart);
    residualNoiseState.measuredPostIspVarianceY =
            static_cast<float>(measuredVisibleResidual.varianceY);
    residualNoiseState.measuredPostIspVarianceRG =
            static_cast<float>(measuredVisibleResidual.varianceRG);
    residualNoiseState.measuredPostIspVarianceBG =
            static_cast<float>(measuredVisibleResidual.varianceBG);
    residualNoiseState.measuredPostIspCovarianceRgBg =
            static_cast<float>(measuredVisibleResidual.covarianceRgBg);
    residualNoiseState.measuredPostIspSampleCount = measuredVisibleResidual.sampleCount;

    downstreamIspState.outputMeasurementMs = residualNoiseState.measuredVisibleResidualMs;
    downstreamIspState.outputVarianceY = static_cast<float>(measuredVisibleResidual.varianceY);
    downstreamIspState.outputResidualSampleCount = measuredVisibleResidual.sampleCount;
    const bool downstreamMeasurementReady = measuredPreSharpenResidual.sampleCount >= 2048 &&
            measuredVisibleResidual.sampleCount >= 2048 &&
            measuredPreSharpenResidual.varianceY > 1.0e-12;
    downstreamIspState.measuredVarianceGainY = downstreamMeasurementReady
            ? static_cast<float>(std::clamp(
                    measuredVisibleResidual.varianceY /
                            measuredPreSharpenResidual.varianceY,
                    0.75,
                    1.30
            ))
            : 1.0f;
    const auto downstreamIdentityPropagationStart = IspClock::now();
    // Phase 11 retires post-quantization RAW sharpening. Preserve the already-propagated
    // quantization state exactly until JPEG encoding; FASE 12 will own any later perceptual
    // output-detail stage and must be modeled separately rather than masquerading as sharpen.
    residualNoiseState.finalJpeg = residualNoiseState.postQuantization;
    residualNoiseState.finalJpeg.stage = "FINAL_JPEG_PRE_ENCODE";
    residualNoiseState.finalJpeg.method = "PHASE11_POST_QUANTIZATION_IDENTITY";
    residualNoiseState.finalJpeg.status = "PROPAGATED";
    residualNoiseState.downstreamSharpenPropagationMs += elapsedMs(downstreamIdentityPropagationStart);
    residualNoiseState.varianceY = static_cast<float>(residualNoiseState.finalJpeg.varianceY);
    residualNoiseState.varianceRG = static_cast<float>(residualNoiseState.finalJpeg.varianceRG);
    residualNoiseState.varianceBG = static_cast<float>(residualNoiseState.finalJpeg.varianceBG);
    residualNoiseState.covarianceRgBg =
            static_cast<float>(residualNoiseState.finalJpeg.covarianceRgBg);
    for (std::size_t index = 0; index < residualNoiseState.covarianceRgb.size(); ++index) {
        residualNoiseState.covarianceRgb[index] = static_cast<float>(
                residualNoiseState.finalJpeg.covariance.values[index]
        );
    }
    residualNoiseState.domain = "FINAL_JPEG_RGB";
    residualNoiseState.valueStage = "FINAL_JPEG_PRE_ENCODE";
    residualNoiseState.measuredPostIspStage = "FINAL_JPEG_PRE_ENCODE_POST_QUANTIZATION_IDENTITY";
    residualNoiseState.propagationStatus =
            "PHASE11_LINEAR_DETAIL_THEN_TONE_QUANTIZATION_IDENTITY_TO_JPEG";
    residualNoiseState.comparabilityStatus =
            "FINAL_JPEG_RESIDUAL_STATE_MEASURED_PRE_ENCODE_JPEG_COMPRESSION_NOT_PROPAGATED";
    g_threadLocalIspStats.postSharpenResidualEstimate =
            g_threadLocalIspStats.postDenoiseResidualEstimate;

    const auto rotateStart = IspClock::now();
    rotateMatForOutput(bgr8, rotationDegrees);
    const float outputRotateMs = elapsedMs(rotateStart);

    if (bgr8.empty() || bgr8.type() != CV_8UC3) {
        ISP_LOGE("RAW_BASELINE_RENDER: bgr8 output matrix is empty or invalid type before encoding");
        return jpegData;
    }

    const std::vector<int> encodeParameters = bncam::jpeg444EncodingParameters(
            safeJpegQuality(uiConfig.jpegQuality));
    const auto jpegEncodeStart = IspClock::now();
    const bool encoded = cv::imencode(".jpg", bgr8, jpegData, encodeParameters);
    const float jpegEncodeMs = elapsedMs(jpegEncodeStart);

    if (!encoded || jpegData.empty()) {
        ISP_LOGE("RAW_BASELINE_RENDER: cv::imencode failed or produced empty JPEG bytes");
        jpegData.clear();
        return jpegData;
    }
    residualNoiseState.lastObservedStage = "JPEG_ENCODED";
    residualNoiseState.propagationStatus =
            "PHASE11_FINAL_JPEG_RESIDUAL_STATE_MEASURED_PRE_ENCODE_POST_QUANTIZATION_IDENTITY";
    const float totalRawIspCoreMs = elapsedMs(totalRenderStart);

    // FASE 16: consolidate existing objective measurements into one reporting-only scorecard.
    // This introduces no thresholds into image processing and deliberately does not declare a
    // visual-quality PASS: actual-image/reference review remains mandatory for Phase 16.
    bncam::validation::ObjectiveValidationInput phase16ValidationInput{};
    phase16ValidationInput.raw10 = isRaw10;
    phase16ValidationInput.iso = actualIso;
    phase16ValidationInput.finalRedClippedPct = finalRedClippedPct;
    phase16ValidationInput.finalGreenClippedPct = finalGreenClippedPct;
    phase16ValidationInput.finalBlueClippedPct = finalBlueClippedPct;
    phase16ValidationInput.finalMeanR = toneMeanR;
    phase16ValidationInput.finalMeanG = toneMeanG;
    phase16ValidationInput.finalMeanB = toneMeanB;
    phase16ValidationInput.measuredVarianceY = measuredVisibleResidual.varianceY;
    phase16ValidationInput.measuredVarianceRG = measuredVisibleResidual.varianceRG;
    phase16ValidationInput.measuredVarianceBG = measuredVisibleResidual.varianceBG;
    phase16ValidationInput.measuredResidualSampleCount = measuredVisibleResidual.sampleCount;
    phase16ValidationInput.modeledVarianceY = residualNoiseState.finalJpeg.varianceY;
    phase16ValidationInput.modeledVarianceRG = residualNoiseState.finalJpeg.varianceRG;
    phase16ValidationInput.modeledVarianceBG = residualNoiseState.finalJpeg.varianceBG;
    phase16ValidationInput.modeledConfidence = residualNoiseState.finalJpeg.confidence;
    phase16ValidationInput.chromaCloudClassificationReady =
            residualNoiseState.demosaicChromaCloudClassificationReady;
    phase16ValidationInput.chromaCloudRiskEvidence =
            residualNoiseState.demosaicChromaCloudRiskEvidence;
    phase16ValidationInput.chromaCloudRiskStatus = residualNoiseState.demosaicChromaCloudRiskStatus;
    // Phase-11 automatic detail owner is retired; keep compatibility validation fields neutral.
    phase16ValidationInput.linearDetailEvaluatedPixels = 0u;
    phase16ValidationInput.linearDetailEdgeSupportedPixels = 0u;
    phase16ValidationInput.linearDetailNoiseRejectedPixels = 0u;
    phase16ValidationInput.linearDetailHaloClampedPixels = 0u;
    phase16ValidationInput.perceptualDetailEvaluatedPixels = vulkanTone.perceptualDetailEvaluatedPixels;
    phase16ValidationInput.perceptualDetailEdgeSupportedPixels = vulkanTone.perceptualDetailEdgeSupportedPixels;
    phase16ValidationInput.perceptualDetailNoiseRejectedPixels = vulkanTone.perceptualDetailNoiseRejectedPixels;
    phase16ValidationInput.perceptualDetailHaloClampedPixels = vulkanTone.perceptualDetailHaloClampedPixels;
    phase16ValidationInput.awbConfidence = phase7AwbEstimate.confidence;
    phase16ValidationInput.awbNeutralSupport = phase7AwbEstimate.neutralSupport;
    phase16ValidationInput.awbMixedLightScore = phase7AwbEstimate.mixedLightScore;
    phase16ValidationInput.awbPriorDisagreement = phase7AwbEstimate.priorDisagreement;
    phase16ValidationInput.awbMixedIllumination = phase7AwbEstimate.mixedIllumination;
    phase16ValidationInput.demosaicFallback = demosaicResolution.fallbackOccurred;
    phase16ValidationInput.demosaicMs = demosaicMs;
    phase16ValidationInput.rawIspMs = totalRawIspCoreMs;
    phase16ValidationInput.sceneMedianSignal = vulkanRawFinalize.autoSceneMedianSignal;
    phase16ValidationInput.sceneP90Gradient = vulkanRawFinalize.autoSceneP90Gradient;
    phase16ValidationInput.sceneEdgeFraction = vulkanRawFinalize.autoSceneEdgeFraction;
    phase16ValidationInput.sceneLowSignalFraction = vulkanRawFinalize.autoSceneLowSignalFraction;
    phase16ValidationInput.residentPublication = residentPostDemosaicApplied;
    phase16ValidationInput.finalOutputStatsSource = finalOutputStatsSource;
    const bncam::validation::ObjectiveValidationSummary phase16Validation =
            bncam::validation::summarizeObjectiveValidation(phase16ValidationInput);
    const std::string phase16CoreTelemetryStatus =
            !phase16Validation.coreTelemetryReady
                    ? "CORE_TELEMETRY_INCOMPLETE"
                    : (!phase16Validation.residualMeasurementReady
                            ? "CORE_READY_RESIDUAL_MEASUREMENT_UNAVAILABLE"
                            : (!phase16Validation.falseColorRiskTelemetryReady
                                    ? "CORE_RESIDUAL_READY_FALSE_COLOR_RISK_TELEMETRY_UNAVAILABLE"
                                    : "OBJECTIVE_CORE_RESIDUAL_TELEMETRY_READY_IMAGE_REVIEW_REQUIRED"));

    const uint64_t rawIspWorkingSetEstimateBytes =
            static_cast<uint64_t>(demosaicInputWidth) * demosaicInputHeight *
                    (demosaicResolution.algorithm == DemosaicAlgorithm::Menon2007 ? 41u : 19u);
    const int rawIspPassCount = 6 +
            (defectDebug.expensivePassSkipped ? 0 : 1) +
            (greenSplitDebug.applied ? 1 : 0) +
            (lensDebug.applied ? 1 : 0) +
            (highlightDebug.applied ? 1 : 0);
    const int bufferReuseHitCount = demosaicRunStats.allocationReuse ? 1 : 0;
    const auto reductionPct = [](float before, float after) -> float {
        if (!(before > 1.0e-12f) || !std::isfinite(after)) return 0.0f;
        return 100.0f * std::clamp((before - after) / before, -10.0f, 1.0f);
    };
    const float pass0MaxCorrection = std::max({
            std::abs(pass0State.appliedChannelBias[0]),
            std::abs(pass0State.appliedChannelBias[1]),
            std::abs(pass0State.appliedChannelBias[2]),
            std::abs(pass0State.appliedChannelBias[3])
    });
    const float spectraAccountedMs = captureProvenanceMs + finalProvenanceMs +
            pass0State.processingTimeMs + pass1State.processingTimeMs +
            pass2State.processingTimeMs + lowBandObserverMs;
    const float spectraUnattributedMs = std::max(0.0f, spectraProcessingMs - spectraAccountedMs);
    const float spectraPropagationMathMs = residualNoiseState.demosaicPropagationMs +
            residualNoiseState.awbPropagationMs +
            residualNoiseState.colourTransformPropagationMs +
            residualNoiseState.linearDetailPropagationMs +
            residualNoiseState.tonePropagationMs +
            residualNoiseState.measuredPostDemosaicResidualMs +
            residualNoiseState.measuredPostColourTransformResidualMs +
            residualNoiseState.measuredPreSharpenResidualMs +
            residualNoiseState.downstreamSharpenPropagationMs +
            residualNoiseState.measuredVisibleResidualMs;
    const float spectraPropagationSequentialMs = residualNoiseState.demosaicPropagationMs +
            residualNoiseState.awbPropagationMs +
            residualNoiseState.colourTransformPropagationMs +
            residualNoiseState.linearDetailPropagationMs +
            residualNoiseState.measuredPostDemosaicResidualMs +
            residualNoiseState.measuredPostColourTransformResidualMs +
            residualNoiseState.measuredPreSharpenResidualMs +
            residualNoiseState.downstreamSharpenPropagationMs +
            residualNoiseState.measuredVisibleResidualMs;
    const float rawFinalizeStageMs = vulkanRawFinalize.success
            ? vulkanRawFinalize.totalMs
            : (defectDebug.elapsedMs + greenSplitDebug.elapsedMs + lensDebug.elapsedMs);
    const float rawIspAccountedMs = rawFinalizeStageMs + spectraProcessingMs + demosaicMs +
            residualNoiseState.demosaicPropagationMs +
            residualNoiseState.measuredPostDemosaicResidualMs +
            residualNoiseState.awbPropagationMs +
            residualNoiseState.colourTransformPropagationMs +
            residualNoiseState.linearDetailPropagationMs +
            awbColourTransformMs +
            residualNoiseState.measuredPostColourTransformResidualMs +
            highlightDebug.elapsedMs + phase5ToneStageMs +
            finalOutputPassMs + residualNoiseState.measuredPreSharpenResidualMs +
            residualNoiseState.downstreamSharpenPropagationMs +
            residualNoiseState.measuredVisibleResidualMs + outputRotateMs + jpegEncodeMs;
    const float rawIspUnattributedMs = std::max(0.0f, totalRawIspCoreMs - rawIspAccountedMs);

    spectraPerformance.knownFullFrameCloneCount =
            ((residentEntry && !residentCpuFallbackUsed) ? 0 : 1) +
            (pass0NoRegret.evaluatedTiles > 0 ? 1 : 0) +
            (pass1NoRegret.evaluatedTiles > 0 ? 1 : 0);
    const std::uint64_t spectraStatisticsBytesRead =
            spectraPerformance.initialStatistics.estimatedBytesRead +
            spectraPerformance.postPass1Statistics.estimatedBytesRead +
            spectraPerformance.postPass2Statistics.estimatedBytesRead;
    const std::uint64_t spectraStatisticsScratchBytes = std::max({
            spectraPerformance.initialStatistics.scratchBytes,
            spectraPerformance.postPass1Statistics.scratchBytes,
            spectraPerformance.postPass2Statistics.scratchBytes
    });
    const std::uint64_t spectraSimdVectorizedLaneCount =
            spectraPerformance.initialStatistics.simdVectorizedLaneCount +
            spectraPerformance.postPass1Statistics.simdVectorizedLaneCount +
            spectraPerformance.postPass2Statistics.simdVectorizedLaneCount;
    const std::uint64_t spectraSimdRejectedNonFiniteLaneCount =
            spectraPerformance.initialStatistics.simdRejectedNonFiniteLaneCount +
            spectraPerformance.postPass1Statistics.simdRejectedNonFiniteLaneCount +
            spectraPerformance.postPass2Statistics.simdRejectedNonFiniteLaneCount;
    const int spectraSimdDispatchCount =
            (spectraPerformance.initialStatistics.simdKernelUsed ? 1 : 0) +
            (spectraPerformance.postPass1Statistics.simdKernelUsed ? 1 : 0) +
            (spectraPerformance.postPass2Statistics.simdKernelUsed ? 1 : 0);
    spectraPerformance.estimatedReadPasses = spectraPerformance.frameBytes > 0
            ? static_cast<float>(static_cast<double>(spectraStatisticsBytesRead) /
                    static_cast<double>(spectraPerformance.frameBytes))
            : 0.0f;

    // Milestone 8D records warm device evidence from actual capture processing. The first
    // successful sample for a width/height/SPECTRA signature is treated as warm-up and the
    // bounded in-process history is never persisted as sensor calibration.
    const bncam::spectra2::DeviceBackendProfileSample deviceProfileSample{
            rawWidth,
            rawHeight,
            budgetState.spectraMode != 0,
            std::string(sourceName) + "|" + demosaicAlgorithmName(demosaicResolution.algorithm),
            encoded && !jpegData.empty(),
            totalRawIspCoreMs,
            spectraPerformance.totalStatisticsMs,
            pass0State.processingTimeMs + pass1State.processingTimeMs +
                    pass2State.processingTimeMs + lowBandObserverMs,
            0.0f,
            residualNoiseState.measuredPreSharpenResidualMs +
                    residualNoiseState.downstreamSharpenPropagationMs +
                    residualNoiseState.measuredVisibleResidualMs
    };
    spectraPerformance.deviceProfile =
            bncam::spectra2::deviceBackendProfiler().record(deviceProfileSample);

    const auto vulkanRuntimeSnapshot = bncam::vulkan::VulkanRuntime::instance().snapshot();
    const auto vulkanCapabilities = bncam::vulkan::VulkanRuntime::instance().capabilities();

    // The former 8F/8G per-capture visible-chroma qualification path was retired when the
    // resident post-demosaic chain became the production owner. Delta 66 removed its last
    // execution entry, so route keys and benchmark/memory gates can no longer be populated.
    // Keep explicit retirement telemetry instead of evaluating an impossible selector.
    spectraPerformance.vulkanQualification = {};
    spectraPerformance.vulkanQualification.status = "VULKAN_FP32_NOT_QUALIFIED";
    spectraPerformance.vulkanQualification.blockedReason =
            "RETIRED_8F_8G_VISIBLE_CHROMA_QUALIFICATION";
    spectraPerformance.selection.vulkanSelected = false;
    spectraPerformance.vulkanStatus =
            "VULKAN_FP32_NOT_QUALIFIED:RETIRED_8F_8G_VISIBLE_CHROMA_QUALIFICATION";

    if (debugOut != nullptr) {
        std::ostringstream dbg;
        dbg << "RAW_BASELINE_RENDER: "
            << "source=" << sourceName
            << "; rawBayerRouteType=" << (isRaw10 ? "RAW10" : "RAW_SENSOR")
            << "; sharedRawBayerJpegPolicyUsed=true"
            << "; rawBayerJpegRenderPolicy=RAW_BAYER_JPEG_RENDER"
            << "; actualIso=" << actualIso
            << "; phase16ValidationSchema=1"
            << "; phase16ValidationRoute=" << phase16Validation.route
            << "; phase16ValidationIso=" << actualIso
            << "; phase16ValidationIsoBand=" << phase16Validation.isoBand
            << "; phase16TelemetryReady=" << (phase16Validation.coreTelemetryReady ? "true" : "false")
            << "; phase16ResidualMeasurementReady="
            << (phase16Validation.residualMeasurementReady ? "true" : "false")
            << "; phase16FalseColorRiskTelemetryReady="
            << (phase16Validation.falseColorRiskTelemetryReady ? "true" : "false")
            << "; phase16TelemetryStatus=" << phase16CoreTelemetryStatus
            << "; phase16MeasuredFinalLumaSigma=" << phase16Validation.measuredFinalLumaSigma
            << "; phase16MeasuredFinalChromaSigma=" << phase16Validation.measuredFinalChromaSigma
            << "; phase16ModeledFinalLumaSigma=" << phase16Validation.modeledFinalLumaSigma
            << "; phase16ModeledFinalChromaSigma=" << phase16Validation.modeledFinalChromaSigma
            << "; phase16ModeledFinalConfidence=" << residualNoiseState.finalJpeg.confidence
            << "; phase16MeasuredFinalResidualSamples=" << measuredVisibleResidual.sampleCount
            << "; phase16FalseColorRiskEvidence=" << phase16Validation.falseColorRiskEvidence
            << "; phase16FalseColorRiskStatus=" << residualNoiseState.demosaicChromaCloudRiskStatus
            << "; phase16LinearDetailEdgeSupportFraction="
            << phase16Validation.linearDetailEdgeSupportFraction
            << "; phase16LinearDetailNoiseRejectedFraction="
            << phase16Validation.linearDetailNoiseRejectedFraction
            << "; phase16LinearDetailHaloClampFraction="
            << phase16Validation.linearDetailHaloClampFraction
            << "; phase16PerceptualDetailEdgeSupportFraction="
            << phase16Validation.perceptualDetailEdgeSupportFraction
            << "; phase16PerceptualDetailNoiseRejectedFraction="
            << phase16Validation.perceptualDetailNoiseRejectedFraction
            << "; phase16PerceptualDetailHaloClampFraction="
            << phase16Validation.perceptualDetailHaloClampFraction
            << "; phase16FinalClipMaxPct=" << phase16Validation.finalClipMaxPct
            << "; phase16FinalMeanRgb=[" << toneMeanR << "," << toneMeanG << "," << toneMeanB << "]"
            << "; phase16AwbConfidence=" << phase7AwbEstimate.confidence
            << "; phase16AwbNeutralSupport=" << phase7AwbEstimate.neutralSupport
            << "; phase16AwbMixedLightScore=" << phase7AwbEstimate.mixedLightScore
            << "; phase16AwbPriorDisagreement=" << phase7AwbEstimate.priorDisagreement
            << "; phase16AwbMixedIllumination="
            << (phase7AwbEstimate.mixedIllumination ? "true" : "false")
            << "; phase16DemosaicRequested=" << demosaicModeName(demosaicResolution.requestedMode)
            << "; phase16DemosaicResolved="
            << (autoHybridUsedForOutput ? "AUTO_HYBRID" : demosaicAlgorithmName(demosaicResolution.algorithm))
            << "; phase16DemosaicFallback=" << (demosaicResolution.fallbackOccurred ? "true" : "false")
            << "; phase16DemosaicMs=" << demosaicMs
            << "; phase16RawIspMs=" << totalRawIspCoreMs
            << "; phase16SceneMedianSignal=" << vulkanRawFinalize.autoSceneMedianSignal
            << "; phase16SceneP90Gradient=" << vulkanRawFinalize.autoSceneP90Gradient
            << "; phase16SceneEdgeFraction=" << vulkanRawFinalize.autoSceneEdgeFraction
            << "; phase16SceneLowSignalFraction=" << vulkanRawFinalize.autoSceneLowSignalFraction
            << "; phase16ResidentPublication=" << (residentPostDemosaicApplied ? "true" : "false")
            << "; phase16FinalOutputStatsSource=" << finalOutputStatsSource
            << "; phase16ImageReviewRequired="
            << (phase16Validation.imageReviewRequired ? "true" : "false")
            << "; phase16ColorAccuracyReferenceRequired="
            << (phase16Validation.colorAccuracyReferenceRequired ? "true" : "false")
            << "; phase16HueStabilityReferenceRequired="
            << (phase16Validation.hueStabilityReferenceRequired ? "true" : "false")
            << "; phase16NearNyquistReferenceRequired="
            << (phase16Validation.nearNyquistReferenceRequired ? "true" : "false")
            << "; exposureKnown=" << (exposureKnown ? "true" : "false")
            << "; exposureMs=" << exposureMs
            << "; lowLightScene=" << (lowLightScene ? "true" : "false")
            << "; fullRaw16SaturatedPct=" << fullRaw16SaturatedPct
            << "; displayHighlightScene=" << (displayHighlightScene ? "true" : "false")
            << "; outdoorSkyScene=" << (outdoorSkyScene ? "true" : "false")
            << "; automaticPostDemosaicExposureOwner=RETIRED_PHASE5"
            << "; rawExposureOwner=" << "NEUTRAL_1X__LOCAL_FLLF_OWNS_AUTOMATIC_BRIGHTNESS"
            << "; rawBlackAnchorOwner=PREDEMOSAIC_CALIBRATED_SENSOR_BLACK_LEVEL"
            << "; rawAutomaticBlackAnchorApplied=false"
            << "; profileToneExposure=" << uiConfig.profileToneExposure
            << "; profileToneExposureEv=" << profileTonePlan.exposureEv
            << "; profileToneExposureMultiplier=" << profileTonePlan.exposureMultiplier
            << "; rawToneInputGain=" << 1.0f
            << "; profileToneExposureStage=POST_FLLF_SCENE_LINEAR_PRE_PBR_NEUTRAL"
            << "; profileToneExposure=" << uiConfig.profileToneExposure
            << "; profileToneExposureEv=" << profileTonePlan.exposureEv
            << "; profileToneExposureMultiplier=" << profileExposureGain
            << "; automaticGlobalToneCurveActive=false"
            << "; automaticPostCcmCameraLookActive=false"
            << "; phase5ToneArchitecture=LOG2_LUMA__PHYSICAL_NOISE_AWARE_FLLF__STRICT_YNEW_YOLD_ALPHA_1__KHRONOS_PBR_NEUTRAL"
            << "; phase5FllfDomain=SCENE_LINEAR_RGB_TO_PURE_LOG2_REC709_LUMA"
            << "; phase5FllfRgbReconstruction=STRICT_LUMINANCE_RATIO_ALPHA_1"
            << "; phase5FllfStrictRatioAlpha=" << 1.0f
            << "; phase5DisplayMapper=KHRONOS_PBR_NEUTRAL"
            << "; phase5DisplayMapperInput=FLLF_SCENE_LINEAR_RGB_PLUS_EXPLICIT_PROFILE_EXPOSURE"
            << "; phase5DisplayMapperOutput=DISPLAY_LINEAR"
            << "; phase5ProfileControlsStage=EXPOSURE_POST_FLLF_PRE_PBR__RANGE_CURVES_POST_PBR__COLOR_BEFORE_FINAL_GAMUT"
            << "; phase5FinalGamutOwner=POST_PROFILE_HUE_PRESERVING_UNIT_GAMUT"
            << "; rawColorAutomaticCameraRender=false"
            << "; rawColorAutomaticCameraOwner="
            << (calibratedHueSatMapActive
                    ? "PHASE2_CALIBRATED_DNG_FORWARD_MATRIX_PLUS_HUESATMAP"
                    : (calibratedMatrixProfileActive
                            ? "PHASE2_CALIBRATED_DNG_FORWARD_MATRIX"
                            : "PHASE2_CHARACTERIZATION_FALLBACK_OR_NONE"))
            << "; toneSensorClipPressure=" << fllfPlan.highlightPressure
            << "; toneShadowPressure=" << fllfPlan.shadowPressure
            << "; toneDynamicRangePressure=" << fllfPlan.dynamicRangePressure
            << "; toneSceneKey=" << fllfPlan.sceneKey
            << "; toneSceneRangeStops=" << fllfPlan.sceneRangeStops
            << "; fllfPhysicalNoiseSigmaY=" << phase5FllfPhysicalNoiseSigmaY
            << "; fllfNoiseModelConfidence=" << phase5FllfNoiseModelConfidence
            << "; fllfNoisePressure=" << fllfPlan.noisePressure
            << "; postToneNoiseJacobianOwner=NUMERIC_KHRONOS_PBR_NEUTRAL_Y_RG_BG_OPPONENT"
            << "; toneContrastStrength=" << effectiveToneContrastStrength
            << "; profileToneHighlights=" << uiConfig.profileToneHighlights
            << "; profileToneShadows=" << uiConfig.profileToneShadows
            << "; profileToneWhites=" << uiConfig.profileToneWhites
            << "; profileToneBlacks=" << uiConfig.profileToneBlacks
            << "; profileToneContrast=" << uiConfig.profileToneContrast
            << "; profileLocalToneBias=" << uiConfig.profileLocalToneBias
            << "; localToneEnabled=" << "false"
            << "; localToneBackend=" << "RETIRED_PHASE5_FLLF_SINGLE_TONE_OWNER"
            << "; localToneAdjustedPixels=" << vulkanTone.localToneAdjustedPixels
            << "; phase11LinearDetailEnabled=false"
            << "; phase11LinearDetailAuthoritySource=RETIRED_N004_AUTOMATIC_OWNER_REMOVED"
            << "; phase11LinearDetailRuntimeOrder=RETIRED"
            << "; phase11LinearDetailAuthority=0"
            << "; phase11LinearDetailRadius=0"
            << "; phase11LinearDetailEmphasis=0"
            << "; phase11LinearDetailMasking=0"
            << "; phase11LinearDetailMinimumResidualSnr=0"
            << "; phase11LinearDetailMinimumGradientSnr=0"
            << "; phase11LinearDetailHardHaloLimit=0"
            << "; phase11LinearDetailPreToneLumaSigma=0"
            << "; phase11LinearDetailReferenceSignal=0"
            << "; phase11LinearDetailShotNoiseFraction=0"
            << "; phase11LinearDetailModelConfidence=0"
            << "; phase11LinearDetailPredictedVarianceGain=1"
            << "; phase11LinearDetailBackend=RETIRED"
            << "; phase11LinearDetailApplied=false"
            << "; phase11LinearDetailEvaluatedPixels=0"
            << "; phase11LinearDetailChangedPixels=0"
            << "; phase11LinearDetailEdgeSupportedPixels=0"
            << "; phase11LinearDetailNoiseRejectedPixels=0"
            << "; phase11LinearDetailHaloClampedPixels=0"
            << "; phase11LinearDetailMeanAbsCorrection=0"
            << "; phase11LinearDetailMaxAbsCorrection=0"
            << "; phase11LinearDetailKernelMs=0"
            << "; phase11LinearDetailScratchBytes=0"
            << "; phase12PerceptualDetailEnabled=" << (perceptualDetailPlan.enabled ? "true" : "false")
            << "; phase12PerceptualDetailAuthoritySource=" << perceptualDetailPlan.authoritySource
            << "; phase12PerceptualDetailRuntimeOrder=POST_LOG2_FLLF_PBR_NEUTRAL_PROFILE_COLOR_BEFORE_OUTPUT_ARTIFACTS"
            << "; phase12PerceptualDetailAuthority=" << perceptualDetailPlan.authority
            << "; phase12PerceptualDetailRadius=" << perceptualDetailPlan.radius
            << "; phase12PerceptualDetailEmphasis=" << perceptualDetailPlan.detail
            << "; phase12PerceptualDetailMasking=" << perceptualDetailPlan.masking
            << "; phase12PerceptualDetailDisplayLumaSigma=" << perceptualDetailPlan.displayLumaSigma
            << "; phase12PerceptualDetailMinimumResidualSnr=" << perceptualDetailPlan.minimumResidualSnr
            << "; phase12PerceptualDetailMinimumGradientSnr=" << perceptualDetailPlan.minimumGradientSnr
            << "; phase12PerceptualDetailHardHaloLimit=" << perceptualDetailPlan.hardHaloLimit
            << "; phase12PerceptualDetailPredictedVarianceGain=" << perceptualDetailPlan.predictedLumaVarianceGain
            << "; phase12PerceptualDetailBackend="
            << (vulkanTone.perceptualDetailRequested ? "VULKAN_RESIDENT_DISPLAY_DETAIL" : "BYPASSED")
            << "; phase12PerceptualDetailApplied=" << (vulkanTone.perceptualDetailApplied ? "true" : "false")
            << "; phase12PerceptualDetailEvaluatedPixels=" << vulkanTone.perceptualDetailEvaluatedPixels
            << "; phase12PerceptualDetailChangedPixels=" << vulkanTone.perceptualDetailChangedPixels
            << "; phase12PerceptualDetailEdgeSupportedPixels=" << vulkanTone.perceptualDetailEdgeSupportedPixels
            << "; phase12PerceptualDetailNoiseRejectedPixels=" << vulkanTone.perceptualDetailNoiseRejectedPixels
            << "; phase12PerceptualDetailHaloClampedPixels=" << vulkanTone.perceptualDetailHaloClampedPixels
            << "; phase12PerceptualDetailMeanAbsCorrection=" << vulkanTone.perceptualDetailMeanAbsCorrection
            << "; phase12PerceptualDetailMaxAbsCorrection=" << vulkanTone.perceptualDetailMaxAbsCorrection
            << "; phase12PerceptualDetailKernelMs=" << vulkanTone.perceptualDetailKernelMs
            << "; phase12PerceptualDetailScratchBytes=" << vulkanTone.perceptualDetailScratchBytes
            << "; legacyPostToneSharpenOwner=RETIRED_PHASE11"
            << "; fllfEnabled=" << (fllfPlan.enabled ? "true" : "false")
            << "; fllfProductionOwner=VULKAN_LOG2_LUMA_PRIMARY_AUTOMATIC_TONE_AUTHORITY"
            << "; fllfAuthoritySource=SCENE_RANGE_PLUS_PROPAGATED_PHYSICAL_NOISE_COVARIANCE"
            << "; fllfStrength=" << fllfPlan.strength
            << "; fllfSceneKey=" << fllfPlan.sceneKey
            << "; fllfMaxLiftEv=" << fllfPlan.maxLiftEv
            << "; fllfMaxCompressEv=" << fllfPlan.maxCompressEv
            << "; fllfEdgeStopEv=" << fllfPlan.edgeStopEv
            << "; fllfRefinement=" << fllfPlan.refinement
            << "; fllfNoisePressure=" << fllfPlan.noisePressure
            << "; fllfPropagationPolicy=" << "MAX_POSITIVE_GAIN_BOUND_NO_NEGATIVE_NR_CREDIT"
            << "; fllfConservativePropagationGain=" << phase5FllfConservativePropagationGain
            << "; fllfBackend=" << (vulkanTone.fllfApplied
                    ? "VULKAN_RESIDENT"
                    : (fllfPlan.enabled ? "REQUESTED_NOT_APPLIED" : "BYPASSED_POLICY"))
            << "; fllfAdjustedPixels=" << vulkanTone.fllfAdjustedPixels
            << "; fllfEdgeProtectedSamples=" << vulkanTone.fllfEdgeProtectedSamples
            << "; fllfMeanAbsCorrectionEv=" << vulkanTone.fllfMeanAbsCorrectionEv
            << "; fllfMaxAbsCorrectionEv=" << vulkanTone.fllfMaxAbsCorrectionEv
            << "; fllfPyramidBuildMs=" << vulkanTone.fllfPyramidBuildMs
            << "; fllfRemapReconstructMs=" << vulkanTone.fllfRemapReconstructMs
            << "; fllfResidentBytes=" << vulkanTone.fllfResidentBytes
            << "; highlightOccupancyPct=" << highlightOccupancyPct
            << "; highlightProtectionMode=" << highlightProtectionMode
            << "; localHighlightProtectionReason=" << localHighlightProtectionReason
            << "; globalHighlightDarkening=false"
            << "; outdoorSkyConfidence=" << outdoorSkyConfidence
            << "; outdoorSkyRejectedReason=" << outdoorSkyRejectedReason
            << "; displayHighlightConfidence=" << displayHighlightConfidence
            << "; indoorLowLightConfidence=" << indoorLowLightConfidence
            << "; sceneClassificationFinal=" << sceneClassificationFinal
            << "; highlightNeutralize=" << (highlightNeutralize.load() ? "true" : "false")
            << "; localHighlightRecoveryApplied=" << (highlightDebug.applied ? "true" : "false")
            << "; localHighlightRecoveryPixels=" << highlightDebug.correctedPixels
            << "; localHighlightRecoveryReason=" << highlightDebug.reason
            << "; defectCorrectionApplied=" << (defectDebug.applied ? "true" : "false")
            << "; defectCorrectionPixels=" << defectDebug.correctedPixels
            << "; defectCorrectionReason=" << defectDebug.reason
            << "; greenSplitCorrectionApplied=" << (greenSplitDebug.applied ? "true" : "false")
            << "; greenSplitEvenMedian=" << greenSplitDebug.greenEvenMedian
            << "; greenSplitOddMedian=" << greenSplitDebug.greenOddMedian
            << "; greenSplitEvenScale=" << greenSplitDebug.greenEvenScale
            << "; greenSplitOddScale=" << greenSplitDebug.greenOddScale
            << "; greenSplitPairedSampleCount=" << greenSplitDebug.pairedSampleCount
            << "; greenSplitRelativeMedian=" << greenSplitDebug.relativeMedian
            << "; greenSplitRelativeMad=" << greenSplitDebug.relativeMad
            << "; greenSplitSignConsensus=" << greenSplitDebug.signConsensus
            << "; greenSplitReason=" << greenSplitDebug.reason
            << "; lensShadingApplied=" << (lensDebug.applied ? "true" : "false")
            << "; lensShadingReason=" << lensDebug.reason
            << "; lensShadingMaxGain=" << lensDebug.maxGain
            << "; lensShadingOverRangePct=" << lensDebug.overRangePct
            << "; rawAdaptiveExposureRequested=" << (rawFinalizeResident
                    ? (vulkanRawFinalize.adaptiveExposureRequested ? "true" : "false") : "cpu_fallback")
            << "; rawAdaptiveExposureApplied=" << (rawFinalizeResident
                    ? (vulkanRawFinalize.adaptiveExposureApplied ? "true" : "false")
                    : (cpuAdaptiveExposureApplied ? "true" : "false"))
            << "; rawAdaptiveExposureStatus=" << (rawFinalizeResident
                    ? vulkanRawFinalize.adaptiveExposureStatus : cpuAdaptiveExposurePlan.status)
            << "; rawAdaptiveExposureSceneP10=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP10 : cpuAdaptiveExposurePlan.p10)
            << "; rawAdaptiveExposureSceneP25=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP25 : cpuAdaptiveExposurePlan.p25)
            << "; rawAdaptiveExposureSceneP50=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP50 : cpuAdaptiveExposurePlan.p50)
            << "; rawAdaptiveExposureSceneP75=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP75 : cpuAdaptiveExposurePlan.p75)
            << "; rawAdaptiveExposureSceneP90=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP90 : cpuAdaptiveExposurePlan.p90)
            << "; rawAdaptiveExposureSceneP95=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP95 : cpuAdaptiveExposurePlan.p95)
            << "; rawAdaptiveExposureSceneP99=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSceneP99 : cpuAdaptiveExposurePlan.p99)
            << "; rawAdaptiveExposureMeasuredDrEv=" << (rawFinalizeResident ? vulkanRawFinalize.exposureMeasuredSceneDrEv : cpuAdaptiveExposurePlan.measuredSceneDrEv)
            << "; rawAdaptiveExposureLowerNeutralEv=" << (rawFinalizeResident ? vulkanRawFinalize.exposureLowerNeutralBoundaryEv : cpuAdaptiveExposurePlan.lowerNeutralBoundaryEv)
            << "; rawAdaptiveExposureUpperNeutralEv=" << (rawFinalizeResident ? vulkanRawFinalize.exposureUpperNeutralBoundaryEv : cpuAdaptiveExposurePlan.upperNeutralBoundaryEv)
            << "; rawAdaptiveExposureSpatialAuthority=" << (rawFinalizeResident ? vulkanRawFinalize.exposureSpatialAuthority : cpuAdaptiveExposurePlan.spatialAuthority)
            << "; rawAdaptiveExposureEvMin=" << (rawFinalizeResident ? vulkanRawFinalize.exposureMinEv : cpuAdaptiveExposurePlan.minEv)
            << "; rawAdaptiveExposureEvP10=" << (rawFinalizeResident ? vulkanRawFinalize.exposureP10Ev : cpuAdaptiveExposurePlan.p10Ev)
            << "; rawAdaptiveExposureEvP50=" << (rawFinalizeResident ? vulkanRawFinalize.exposureP50Ev : cpuAdaptiveExposurePlan.p50Ev)
            << "; rawAdaptiveExposureEvP90=" << (rawFinalizeResident ? vulkanRawFinalize.exposureP90Ev : cpuAdaptiveExposurePlan.p90Ev)
            << "; rawAdaptiveExposureEvMax=" << (rawFinalizeResident ? vulkanRawFinalize.exposureMaxEv : cpuAdaptiveExposurePlan.maxEv)
            << "; rawAdaptiveExposurePctPos=" << (100.0f * (rawFinalizeResident ? vulkanRawFinalize.exposurePositiveFraction : cpuAdaptiveExposurePlan.positiveFraction))
            << "; rawAdaptiveExposurePctNeutral=" << (100.0f * (rawFinalizeResident ? vulkanRawFinalize.exposureNeutralFraction : cpuAdaptiveExposurePlan.neutralFraction))
            << "; rawAdaptiveExposurePctNeg=" << (100.0f * (rawFinalizeResident ? vulkanRawFinalize.exposureNegativeFraction : cpuAdaptiveExposurePlan.negativeFraction))
            << "; rawAdaptiveExposureMeanPosEv=" << (rawFinalizeResident ? vulkanRawFinalize.exposureMeanPositiveEv : cpuAdaptiveExposurePlan.meanPositiveEv)
            << "; rawAdaptiveExposureMeanNegEv=" << (rawFinalizeResident ? vulkanRawFinalize.exposureMeanNegativeEv : cpuAdaptiveExposurePlan.meanNegativeEv)
            << "; rawAdaptiveExposureP90PositiveGain=" << spatialExposureNoiseScale
            << "; rawAdaptiveExposureNoiseContract=IDENTITY_1X__NO_AUTOMATIC_SPATIAL_EXPOSURE_GAIN"
            << "; rawFinalizeGpuAttempted=" << (vulkanRawFinalize.attempted ? "true" : "false")
            << "; rawFinalizeGpuSuccess=" << (vulkanRawFinalize.success ? "true" : "false")
            << "; rawFinalizeResident=" << (rawFinalizeResident ? "true" : "false")
            << "; rawFinalizeResidentInputUsed=" << (vulkanRawFinalize.residentInputUsed ? "true" : "false")
            << "; rawFinalizeReadbackDeferred=" << (vulkanRawFinalize.fullFrameReadbackDeferred ? "true" : "false")
            << "; rawFinalizeStatus=" << vulkanRawFinalize.status
            << "; rawFinalizeFailureReason=" << (vulkanRawFinalize.failureReason.empty() ? "none" : vulkanRawFinalize.failureReason)
            << "; rawFinalizeResidentOutputGeneration=" << vulkanRawFinalize.residentOutputGeneration
            << "; rawFinalizeSourceClipConfidenceMapReady="
            << (vulkanRawFinalize.sourceClipConfidenceMapReady ? "true" : "false")
            << "; rawFinalizeSourceClipConfidenceMapBytes="
            << vulkanRawFinalize.sourceClipConfidenceMapBytes
            << "; rawFinalizePersistentReuseHit=" << (vulkanRawFinalize.persistentBufferReuseHit ? "true" : "false")
            << "; rawFinalizePersistentReallocated=" << (vulkanRawFinalize.persistentBufferReallocated ? "true" : "false")
            << "; requestedDemosaicMode=" << demosaicModeName(demosaicResolution.requestedMode)
            << "; resolvedDemosaicAlgorithm="
            << (autoHybridUsedForOutput ? "AUTO_HYBRID" : demosaicAlgorithmName(demosaicResolution.algorithm))
            << "; demosaicCfaEvidenceContract="
            << (!demosaicCfaEvidence.available
                    ? "EVIDENCE_UNAVAILABLE"
                    : autoHybridUsedForOutput
                            ? "AUTO_HYBRID_LOCAL_STRUCTURE_NYQUIST_CHROMA_NOISE_ACTIVE"
                            : demosaicResolution.algorithm == DemosaicAlgorithm::RcdInspired
                                    ? (demosaicNoiseContext.available
                                            ? "NEURAL_JDD_PHYSICAL_NOISE_CONTEXT_ACTIVE"
                                            : "NEURAL_JDD_CFA_CONTEXT_ACTIVE")
                                    : demosaicResolution.algorithm == DemosaicAlgorithm::AmazeInspired
                                            ? (demosaicNoiseContext.available
                                                    ? "AMAZE_OPPONENT_NOISE_SIGNIFICANCE_V2_ACTIVE"
                                                    : "AMAZE_OPPONENT_STABILIZATION_V1_ACTIVE")
                                            : demosaicResolution.algorithm == DemosaicAlgorithm::Malvar2004
                                                    ? "PURE_MALVAR_HE_CUTLER_2004"
                                                    : "WIRED_NO_RECONSTRUCTION_AUTHORITY_FOR_SELECTED_ALGORITHM")
            << "; demosaicCfaEvidenceSource="
            << (budgetState.spectraMode != 0 ? "SPECTRA" : "NONE")
            << "; demosaicCfaEvidenceAvailable=" << (demosaicCfaEvidence.available ? "true" : "false")
            << "; demosaicCfaCommonOpponentSupport=" << demosaicCfaEvidence.commonOpponentSupport
            << "; demosaicNoiseContextAvailable=" << (demosaicNoiseContext.available ? "true" : "false")
            << "; demosaicNoiseContextSource=" << (demosaicNoiseContext.available ? "PHYSICAL_SO" : "UNAVAILABLE_ZERO_AUTHORITY")
            << "; demosaicNoiseSigmaY=" << demosaicNoiseContext.sigmaY
            << "; demosaicNoiseSigmaChroma=" << demosaicNoiseContext.sigmaChroma
            << "; demosaicNoisePressure=" << demosaicNoiseContext.pressure
            << "; phase6ResidualChromaPlanEnabled=" << (phase6ResidualChromaPlanEnabled ? "true" : "false")
            << "; phase6ResidualChromaPlanStatus=" << phase6ResidualChromaPlanStatus
            << "; phase6ResidualChromaAuthority=RETIRED_NO_PIXEL_AUTHORITY"
            << "; phase6ResidualChromaLumaMutation=false"
            << "; phase6ResidualChromaBandOwner=NONE"
            << "; phase6ResidualChromaLowFrequencyCloudOwner=NONE"
            << "; phase6ResidualChromaNoisePropagation=IDENTITY"
            << "; phase6ResidualChromaRequested=" << (phase6ResidualChromaPlanEnabled ? "true" : "false")
            << "; phase6ResidualChromaUsedForOutput=" << (phase6ResidualChromaUsedForOutput ? "true" : "false")
            << "; phase6ResidualChromaExecutionBackend=" << phase6ExecutionBackend
            << "; phase6ResidualChromaGpuUsed=" << (phase6ResidualChromaGpuUsed ? "true" : "false")
            << "; phase6ResidualChromaCpuFallbackUsed=" << (phase6CpuFallbackApplied ? "true" : "false")
            << "; phase6ClassifyPassMs=" << vulkanDemosaic.phase6ClassifyPassMs
            << "; phase6CorrectPassMs=" << vulkanDemosaic.phase6CorrectPassMs
            << "; phase6ProcessedPixels=" << phase6ProcessedPixels
            << "; phase6CandidatePixels=" << phase6CandidatePixels
            << "; phase6CandidateFraction=" << phase6CandidateFraction
            << "; phase6IsolatedOutlierPixels=" << phase6IsolatedOutlierPixels
            << "; phase6ZipperPixels=" << phase6ZipperPixels
            << "; phase6EdgeProtectedPixels=" << phase6EdgeProtectedPixels
            << "; phase6SaturatedDetailProtectedPixels=" << phase6SaturatedDetailProtectedPixels
            << "; phase6MeanAbsCorrectionRG=" << phase6MeanAbsCorrectionRG
            << "; phase6MeanAbsCorrectionBG=" << phase6MeanAbsCorrectionBG
            << "; phase6MaximumAbsoluteCorrection=" << phase6MaximumAbsoluteCorrection
            << "; demosaicCfaStructureProtection=" << demosaicCfaEvidence.structureProtection
            << "; demosaicCfaRedOpponentConfidence="
            << demosaicCfaEvidence.redOpponentCorrectionConfidence
            << "; demosaicCfaBlueOpponentConfidence="
            << demosaicCfaEvidence.blueOpponentCorrectionConfidence
            << "; preDemosaicMetadataWbR=" << preDemosaicMetadataWb[0]
            << "; preDemosaicMetadataWbB=" << preDemosaicMetadataWb[2]
            << "; preDemosaicRedOpponentDirectionalGain="
            << preDemosaicRedOpponentDirectionalGain
            << "; preDemosaicBlueOpponentDirectionalGain="
            << preDemosaicBlueOpponentDirectionalGain
            << "; preDemosaicRedOpponentAmplificationPressure="
            << preDemosaicRedOpponentAmplificationPressure
            << "; preDemosaicBlueOpponentAmplificationPressure="
            << preDemosaicBlueOpponentAmplificationPressure
            << "; preDemosaicAmplificationAudit=METADATA_WB_CCM_FEED_FORWARD_ACTIVE"
            << "; demosaicExecutionRedOpponentConfidence="
            << demosaicExecutionCfaEvidence.redOpponentCorrectionConfidence
            << "; demosaicExecutionBlueOpponentConfidence="
            << demosaicExecutionCfaEvidence.blueOpponentCorrectionConfidence
            << "; demosaicRedOpponentRelativeBoost="
            << preDemosaicRedOpponentConfidenceBoost
            << "; demosaicBlueOpponentRelativeBoost="
            << preDemosaicBlueOpponentConfidenceBoost
            << "; autoDownstreamAmplificationAware="
            << (demosaicExecutionCfaEvidence.available ? "true" : "false")
            << "; demosaicResolveReason=" << demosaicResolution.reason
            << "; effectiveCfaPattern=" << rawCfaPatternName(workingRaw.info.effectiveCfaPattern)
            << "; cfaOriginX=" << workingRaw.info.cfaOffsetX
            << "; cfaOriginY=" << workingRaw.info.cfaOffsetY
            << "; demosaicOutputChannelOrder=RGB"
            << "; demosaicSamplePreservationContract=EXACT_SAMPLED_CFA_SENSELS"
            << "; demosaicNoiseAuthorityContract=PHYSICAL_SO_OR_ZERO"
            << "; demosaicNoiseAwareReconstructionActive=" << (demosaicNoiseContext.available ? "true" : "false")
            << "; malvarInspiredAvailable=true"
            << "; rcdInspiredAvailable=true"
            << "; amazeInspiredAvailable=true"
            << "; legacyBilinearProductAvailable=false"
            << "; legacyMenonProductAvailable=false"
            << "; fallbackOccurred=" << (demosaicResolution.fallbackOccurred ? "true" : "false")
            << "; fallbackReason=" << demosaicResolution.fallbackReason
            << "; autoSceneAnalysisUsed=" << (demosaicResolution.autoSceneAnalysisUsed ? "true" : "false")
            << "; autoSampleCount=" << demosaicResolution.autoSampleCount
            << "; autoMedianSignal=" << demosaicResolution.autoMedianSignal
            << "; autoMeanGradient=" << demosaicResolution.autoMeanGradient
            << "; autoP90Gradient=" << demosaicResolution.autoP90Gradient
            << "; autoEdgeFraction=" << demosaicResolution.autoEdgeFraction
            << "; autoCoherentEdgeFraction=" << demosaicResolution.autoCoherentEdgeFraction
            << "; autoLowSignalFraction=" << demosaicResolution.autoLowSignalFraction
            << "; autoMalvarScore=" << demosaicResolution.autoMalvarScore
            << "; autoRcdScore=" << demosaicResolution.autoRcdScore
            << "; autoAmazeScore=" << demosaicResolution.autoAmazeScore
            << "; autoCfaChromaRisk=" << demosaicResolution.autoCfaChromaRisk
            << "; autoRunnerUp=" << demosaicResolution.autoRunnerUp
            << "; autoScoreDelta=" << demosaicResolution.autoScoreDelta
            << "; autoHybridExecutionRequested=" << (demosaicResolution.autoHybridExecution ? "true" : "false")
            << "; autoHybridUsedForOutput=" << (autoHybridUsedForOutput ? "true" : "false")
            << "; autoMalvarPrior=" << demosaicResolution.autoMalvarPrior
            << "; autoNeuralJddPrior=" << demosaicResolution.autoNeuralJddPrior
            << "; autoAmazePrior=" << demosaicResolution.autoAmazePrior
            << "; autoSignals=" << demosaicResolution.autoSignals
            << "; baseChromaDenoiseStrength=" << baseChromaNrStrength
            << "; physicalChromaModelDriven=false"
            << "; physicalChromaRawNoiseSigma=0.0000"
            << "; physicalChromaCalibratedNoiseSigma=0.0000"
            << "; physicalChromaModelConfidence=0.0000"
            << "; physicalChromaCombinedNoisePressure=0.0000"
            << "; physicalChromaAuthoritySource=RETIRED_N003"
            << "; chromaDenoiseStrength=" << chromaNrStrength
            << "; sensorNoiseVarianceFormula=S*x+O"
            << "; sensorNoiseVarianceSamples=" << g_threadLocalIspStats.sensorNoiseVarianceSamples
            << "; meanSensorNoiseVariance=" << g_threadLocalIspStats.meanSensorNoiseVariance
            << "; minSensorNoiseVariance=" << g_threadLocalIspStats.minSensorNoiseVariance
            << "; maxSensorNoiseVariance=" << g_threadLocalIspStats.maxSensorNoiseVariance
            << "; meanNormalizedNoiseSignal=" << g_threadLocalIspStats.meanNormalizedNoiseSignal
            << "; minNormalizedNoiseSignal=" << g_threadLocalIspStats.minNormalizedNoiseSignal
            << "; maxNormalizedNoiseSignal=" << g_threadLocalIspStats.maxNormalizedNoiseSignal
            << "; noiseModelMode=" << noiseModelModeName(meta.calibration.noiseModelMode)
            << "; spectraProcessingMode=" << noiseModelModeName(meta.calibration.spectraProcessingMode)
            << "; physicalChromaNoiseRequested=" << (physicalChromaNoiseRequested ? "true" : "false")
            << "; noiseModelSoReceivedByCpp=" << nativeNoiseSo.str()
            << "; noiseModelApplied=" << (g_threadLocalIspStats.noiseModelApplied ? "yes" : "no")
            << "; noiseModelReason=" << g_threadLocalIspStats.noiseModelReason
            << "; " << shadowDiagnostics
            << "; " << isoState.formatDebugString()
            << "; " << captureProvenance.formatDebugString()
            << "; " << finalProvenance.formatDebugString()
            << "; " << pass0State.formatDebugString()
            << "; " << pass1State.formatDebugString()
            << "; " << pass2State.formatDebugString()
            << formatCfaChromaConfidenceFields(cfaChromaConfidence)
            << "; " << pass0NoRegret.formatDebugString()
            << "; " << pass1NoRegret.formatDebugString()
            << "; " << budgetState.formatDebugString()
            << "; " << residualNoiseState.formatDebugString()
            << "; " << downstreamIspState.formatDebugString()
            << "; " << spectraCaptureIntegration.str()
            << "; spectraCaptureProvenanceTileCount=" << captureProvenance.totalTiles
            << "; spectraCaptureProvenanceValidTileCount=" << captureProvenance.validTiles
            << "; spectraCaptureProvenanceMeanDiagnosticConfidence=" << captureProvenance.meanDiagnosticConfidence
            << "; spectraCaptureProvenanceMeanResidualEnergy=" << captureProvenance.meanResidualEnergy
            << "; spectraCaptureProvenanceMeanChromaResidualEnergy=" << captureProvenance.meanChromaResidualEnergy
            << "; spectraCaptureProvenanceConfidenceP10=" << captureProvenance.confidenceP10
            << "; spectraCaptureProvenanceConfidenceP50=" << captureProvenance.confidenceP50
            << "; spectraCaptureProvenanceConfidenceP90=" << captureProvenance.confidenceP90
            << "; spectraCaptureProvenanceShadingGainP10=" << captureProvenance.lensShadingGainP10
            << "; spectraCaptureProvenanceShadingGainP50=" << captureProvenance.lensShadingGainP50
            << "; spectraCaptureProvenanceShadingGainP90=" << captureProvenance.lensShadingGainP90
            << "; spectraCaptureShadingRedToGreenP50=" << captureProvenance.lensShadingRedToGreenP50
            << "; spectraCaptureShadingRedToGreenP90=" << captureProvenance.lensShadingRedToGreenP90
            << "; spectraCaptureShadingBlueToGreenP50=" << captureProvenance.lensShadingBlueToGreenP50
            << "; spectraCaptureShadingBlueToGreenP90=" << captureProvenance.lensShadingBlueToGreenP90
            << "; spectraCaptureShadingOpponentDifferentialStopsP90="
            << captureProvenance.lensShadingOpponentDifferentialStopsP90
            << "; spectraCaptureShadingOuterMinusCenterRedToGreen="
            << captureProvenance.lensShadingOuterMinusCenterRedToGreen
            << "; spectraCaptureShadingOuterMinusCenterBlueToGreen="
            << captureProvenance.lensShadingOuterMinusCenterBlueToGreen
            << "; spectraCaptureProvenanceNoiseBudgetP10=" << captureProvenance.noiseBudgetP10
            << "; spectraCaptureProvenanceNoiseBudgetP50=" << captureProvenance.noiseBudgetP50
            << "; spectraCaptureProvenanceNoiseBudgetP90=" << captureProvenance.noiseBudgetP90
            << "; spectraCaptureProvenanceModelMismatchP10=" << captureProvenance.modelMismatchP10
            << "; spectraCaptureProvenanceModelMismatchP50=" << captureProvenance.modelMismatchP50
            << "; spectraCaptureProvenanceModelMismatchP90=" << captureProvenance.modelMismatchP90
            << "; spectraFinalProvenanceTileCount=" << finalProvenance.totalTiles
            << "; spectraFinalProvenanceValidTileCount=" << finalProvenance.validTiles
            << "; spectraFinalProvenanceMeanDiagnosticConfidence=" << finalProvenance.meanDiagnosticConfidence
            << "; spectraFinalProvenanceMeanResidualEnergy=" << finalProvenance.meanResidualEnergy
            << "; spectraFinalProvenanceMeanChromaResidualEnergy=" << finalProvenance.meanChromaResidualEnergy
            << "; spectraFinalProvenanceConfidenceP10=" << finalProvenance.confidenceP10
            << "; spectraFinalProvenanceConfidenceP50=" << finalProvenance.confidenceP50
            << "; spectraFinalProvenanceConfidenceP90=" << finalProvenance.confidenceP90
            << "; spectraFinalProvenanceShadingGainP10=" << finalProvenance.lensShadingGainP10
            << "; spectraFinalProvenanceShadingGainP50=" << finalProvenance.lensShadingGainP50
            << "; spectraFinalProvenanceShadingGainP90=" << finalProvenance.lensShadingGainP90
            << "; spectraFinalProvenanceNoiseBudgetP10=" << finalProvenance.noiseBudgetP10
            << "; spectraFinalProvenanceNoiseBudgetP50=" << finalProvenance.noiseBudgetP50
            << "; spectraFinalProvenanceNoiseBudgetP90=" << finalProvenance.noiseBudgetP90
            << "; spectraFinalProvenanceModelMismatchP10=" << finalProvenance.modelMismatchP10
            << "; spectraFinalProvenanceModelMismatchP50=" << finalProvenance.modelMismatchP50
            << "; spectraFinalProvenanceModelMismatchP90=" << finalProvenance.modelMismatchP90
            << "; rawResidentEntry=" << (residentEntry ? "true" : "false")
            << "; rawResidentNormalizeGeneration="
            << (residentEntry ? residentInput->rawNormalizeGeneration : 0u)
            << "; rawResidentCpuFallbackUsed=" << (residentCpuFallbackUsed ? "true" : "false")
            << "; rawResidentCpuFallbackReason=" << residentCpuFallbackReason
            << "; spectraPass0CpuPixelMutation=" << (spectraPass0CpuPixelMutation ? "true" : "false")
            << "; spectraFullFrameCpuReadbacks=" << spectraFullFrameCpuReadbacks
            << "; spectraFullFrameCpuClones=" << spectraFullFrameCpuClones
            << "; spectraResidentCompactObserverUsed=false"
            << "; spectraRawFinalizeFailureResidentInputMaterialized="
            << (rawFinalizeFailureResidentInputMaterialized ? "true" : "false")
            << "; spectraDemosaicFailureRawFinalizeMaterialized="
            << (demosaicFailureRawFinalizeMaterialized ? "true" : "false")
            << "; spectraPass0Applied=" << ((pass0State.applyChannelBias || pass0State.applyRowCorrection || pass0State.applyColumnCorrection) ? "true" : "false")
            << "; spectraPass0SkipReason=" << pass0State.fallbackReason
            << "; sceneBlackAuthorityMode=" << pass0State.sceneBlackAuthorityMode
            << "; sceneBlackMetadataAuthoritative="
            << (pass0State.sceneBlackMetadataAuthoritative ? "true" : "false")
            << "; sceneBlackImageMutationAllowed="
            << (pass0State.sceneBlackImageMutationAllowed ? "true" : "false")
            << "; spectraPass0InputEnergy=" << pass0State.g1g2Before
            << "; spectraPass0TargetFloor=" << captureProvenance.meanPredictedRawVariance
            << "; spectraPass0OutputEnergy=" << pass0State.g1g2After
            << "; spectraPass0ReductionPercentage=" << reductionPct(pass0State.g1g2Before, pass0State.g1g2After)
            << "; spectraPass0MaximumCorrection=" << pass0MaxCorrection
            << "; spectraPass0ProcessingTimeMs=" << pass0State.processingTimeMs
            << "; spectraPass0VulkanAttempted=" << (pass0State.vulkanAttempted ? "true" : "false")
            << "; spectraPass0VulkanExecutionSucceeded=" << (pass0State.vulkanExecutionSucceeded ? "true" : "false")
            << "; spectraPass0VulkanUsedForOutput=" << (pass0State.vulkanUsedForOutput ? "true" : "false")
            << "; spectraPass0VulkanCpuFallbackUsed=" << (pass0State.vulkanCpuFallbackUsed ? "true" : "false")
            << "; spectraPass0VulkanGpuNoRegretBlendUsed=" << (pass0State.vulkanGpuNoRegretBlendUsed ? "true" : "false")
            << "; spectraPass0VulkanCandidateReadbackAvoided=" << (pass0State.vulkanCandidateReadbackAvoided ? "true" : "false")
            << "; spectraPass0VulkanStatus=" << pass0State.vulkanStatus
            << "; spectraPass0VulkanFailureReason=" << pass0State.vulkanFailureReason
            << "; spectraPass0VulkanPass0KernelMs=" << pass0State.vulkanPass0KernelMs
            << "; spectraPass0VulkanTileStatisticsKernelMs=" << pass0State.vulkanTileStatisticsKernelMs
            << "; spectraPass0VulkanNoRegretDecisionMs=" << pass0State.vulkanNoRegretDecisionMs
            << "; spectraPass0VulkanNoRegretBlendMs=" << pass0State.vulkanNoRegretBlendMs
            << "; spectraPass0VulkanSynchronizationMs=" << pass0State.vulkanSynchronizationMs
            << "; spectraPass0VulkanCompactReadbackMs=" << pass0State.vulkanCompactReadbackMs
            << "; spectraPass0VulkanResidentGeneration=" << pass0State.vulkanResidentGeneration
            << "; spectraPass1Applied=" << (pass1State.applied ? "true" : "false")
            << "; spectraPass1SkipReason=" << pass1State.fallbackReason
            << "; spectraPass1InputEnergy=" << budgetState.initialResidualEnergy
            << "; spectraPass1TargetFloor=" << budgetState.predictedNoiseFloor
            << "; spectraPass1OutputEnergy=" << budgetState.pass1ResidualEnergy
            << "; spectraPass1ReductionPercentage=" << reductionPct(budgetState.initialResidualEnergy, budgetState.pass1ResidualEnergy)
            << "; spectraPass1MaximumCorrection=" << pass1State.maxPixelShift
            << "; spectraPass1ProcessingTimeMs=" << pass1State.processingTimeMs
            << "; spectraPass1VulkanKernelConnected=" << (pass1State.vulkanKernelConnected ? "true" : "false")
            << "; spectraPass1VulkanAttempted=" << (pass1State.vulkanAttempted ? "true" : "false")
            << "; spectraPass1VulkanExecutionSucceeded=" << (pass1State.vulkanExecutionSucceeded ? "true" : "false")
            << "; spectraPass1VulkanUsedForOutput=" << (pass1State.vulkanUsedForOutput ? "true" : "false")
            << "; spectraPass1VulkanCpuFallbackUsed=" << (pass1State.vulkanCpuFallbackUsed ? "true" : "false")
            << "; spectraPass1VulkanGpuNoRegretBlendUsed=" << (pass1State.vulkanGpuNoRegretBlendUsed ? "true" : "false")
            << "; spectraPass1VulkanCandidateReadbackAvoided=" << (pass1State.vulkanCandidateReadbackAvoided ? "true" : "false")
            << "; spectraPass1VulkanPersistentReuseHit=" << (pass1State.vulkanPersistentReuseHit ? "true" : "false")
            << "; spectraPass1VulkanPersistentReallocated=" << (pass1State.vulkanPersistentReallocated ? "true" : "false")
            << "; spectraPass1VulkanStatus=" << pass1State.vulkanStatus
            << "; spectraPass1VulkanFailureReason=" << pass1State.vulkanFailureReason
            << "; spectraPass1VulkanInputPackingMs=" << pass1State.vulkanInputPackingMs
            << "; spectraPass1VulkanTensorUploadMs=" << pass1State.vulkanTensorUploadMs
            << "; spectraPass1VulkanPass1KernelMs=" << pass1State.vulkanPass1KernelMs
            << "; spectraPass1VulkanTileStatisticsKernelMs=" << pass1State.vulkanTileStatisticsKernelMs
            << "; spectraPass1VulkanNoRegretDecisionMs=" << pass1State.vulkanNoRegretDecisionMs
            << "; spectraPass1VulkanNoRegretBlendMs=" << pass1State.vulkanNoRegretBlendMs
            << "; spectraPass1VulkanGpuKernelMs=" << pass1State.vulkanGpuKernelMs
            << "; spectraPass1VulkanSynchronizationMs=" << pass1State.vulkanSynchronizationMs
            << "; spectraPass1VulkanReadbackMs=" << pass1State.vulkanReadbackMs
            << "; spectraPass1VulkanTransferAndSyncMs=" << pass1State.vulkanTransferAndSyncMs
            << "; spectraPass1VulkanTotalMs=" << pass1State.vulkanTotalMs
            << "; spectraPass1VulkanResidentBytes=" << pass1State.vulkanResidentBytes
            << "; spectraPass1VulkanAllocationGeneration=" << pass1State.vulkanAllocationGeneration
            << "; spectraAnisotropicDetailArchitecture="
            << pass1State.anisotropicDetail.architecture
            << "; spectraAnisotropicDetailTensorMethod="
            << pass1State.anisotropicDetail.tensorMethod
            << "; spectraAnisotropicDetailFilterMethod="
            << pass1State.anisotropicDetail.filterMethod
            << "; spectraAnisotropicDetailFallbackMethod="
            << pass1State.anisotropicDetail.fallbackMethod
            << "; spectraAnisotropicDetailTimingAccounting="
            << pass1State.anisotropicDetail.timingAccounting
            << "; spectraAnisotropicDetailEnabled="
            << (pass1State.anisotropicDetail.enabled ? "true" : "false")
            << "; spectraAnisotropicDetailApplied="
            << (pass1State.anisotropicDetail.applied ? "true" : "false")
            << "; spectraAnisotropicDetailStatus="
            << pass1State.anisotropicDetail.status
            << "; spectraAnisotropicDetailEvaluatedPixelCount="
            << pass1State.anisotropicDetail.evaluatedPixelCount
            << "; spectraAnisotropicDetailValidTensorPixelCount="
            << pass1State.anisotropicDetail.validTensorPixelCount
            << "; spectraAnisotropicDetailConfidentTensorPixelCount="
            << pass1State.anisotropicDetail.confidentTensorPixelCount
            << "; spectraAnisotropicDetailFallbackPixelCount="
            << pass1State.anisotropicDetail.fallbackPixelCount
            << "; spectraAnisotropicDetailDirectionalChangedPixelCount="
            << pass1State.anisotropicDetail.directionalChangedPixelCount
            << "; spectraAnisotropicDetailCrossEdgeProtectedSampleCount="
            << pass1State.anisotropicDetail.crossEdgeProtectedSampleCount
            << "; spectraAnisotropicDetailAlongStructureSupportedSampleCount="
            << pass1State.anisotropicDetail.alongStructureSupportedSampleCount
            << "; spectraContextFusionFlatPixelCount="
            << pass1State.anisotropicDetail.contextFlatPixelCount
            << "; spectraContextFusionStructureProtectedPixelCount="
            << pass1State.anisotropicDetail.contextStructureProtectedPixelCount
            << "; spectraContextFusionBoostedPixelCount="
            << pass1State.anisotropicDetail.contextBoostedPixelCount
            << "; spectraContextFusionFlatFraction="
            << pass1State.anisotropicDetail.contextFlatFraction
            << "; spectraContextFusionStructureProtectedFraction="
            << pass1State.anisotropicDetail.contextStructureProtectedFraction
            << "; spectraContextFusionBoostedFraction="
            << pass1State.anisotropicDetail.contextBoostedFraction
            << "; spectraProfiledMultibandPixelCount="
            << pass1State.anisotropicDetail.profiledMultibandPixelCount
            << "; spectraProfiledHeavyFineShrinkPixelCount="
            << pass1State.anisotropicDetail.profiledHeavyFineShrinkPixelCount
            << "; spectraCoherentDetailRestitutionPixelCount="
            << pass1State.anisotropicDetail.coherentDetailRestitutionPixelCount
            << "; spectraProfiledMultibandFraction="
            << pass1State.anisotropicDetail.profiledMultibandFraction
            << "; spectraProfiledHeavyFineShrinkFraction="
            << pass1State.anisotropicDetail.profiledHeavyFineShrinkFraction
            << "; spectraCoherentDetailRestitutionFraction="
            << pass1State.anisotropicDetail.coherentDetailRestitutionFraction
            << "; spectraProfiledPatchConsensusPixelCount="
            << pass1State.anisotropicDetail.profiledPatchConsensusPixelCount
            << "; spectraProfiledStrongPatchConsensusPixelCount="
            << pass1State.anisotropicDetail.profiledStrongPatchConsensusPixelCount
            << "; spectraProfiledPatchConsensusFraction="
            << pass1State.anisotropicDetail.profiledPatchConsensusFraction
            << "; spectraProfiledStrongPatchConsensusFraction="
            << pass1State.anisotropicDetail.profiledStrongPatchConsensusFraction
            << "; spectraProfiledPatchPosteriorCleanPixelCount="
            << pass1State.anisotropicDetail.profiledPatchPosteriorCleanPixelCount
            << "; spectraProfiledPatchPosteriorCleanFraction="
            << pass1State.anisotropicDetail.profiledPatchPosteriorCleanFraction
            << "; spectraProfiledPatchGradientProtectedPixelCount="
            << pass1State.anisotropicDetail.profiledPatchGradientProtectedPixelCount
            << "; spectraProfiledPatchGradientProtectedFraction="
            << pass1State.anisotropicDetail.profiledPatchGradientProtectedFraction
            << "; spectraAnisotropicDetailOrientation0Count="
            << pass1State.anisotropicDetail.orientationHistogram[0]
            << "; spectraAnisotropicDetailOrientation45Count="
            << pass1State.anisotropicDetail.orientationHistogram[1]
            << "; spectraAnisotropicDetailOrientation90Count="
            << pass1State.anisotropicDetail.orientationHistogram[2]
            << "; spectraAnisotropicDetailOrientation135Count="
            << pass1State.anisotropicDetail.orientationHistogram[3]
            << "; spectraAnisotropicDetailValidTensorFraction="
            << pass1State.anisotropicDetail.validTensorFraction
            << "; spectraAnisotropicDetailConfidentTensorFraction="
            << pass1State.anisotropicDetail.confidentTensorFraction
            << "; spectraAnisotropicDetailFallbackFraction="
            << pass1State.anisotropicDetail.fallbackFraction
            << "; spectraAnisotropicDetailDirectionalChangedFraction="
            << pass1State.anisotropicDetail.directionalChangedFraction
            << "; spectraAnisotropicDetailMeanConfidence="
            << pass1State.anisotropicDetail.meanConfidence
            << "; spectraAnisotropicDetailConfidenceP10="
            << pass1State.anisotropicDetail.confidenceP10
            << "; spectraAnisotropicDetailConfidenceP50="
            << pass1State.anisotropicDetail.confidenceP50
            << "; spectraAnisotropicDetailConfidenceP90="
            << pass1State.anisotropicDetail.confidenceP90
            << "; spectraAnisotropicDetailMeanCoherence="
            << pass1State.anisotropicDetail.meanCoherence
            << "; spectraAnisotropicDetailCoherenceP10="
            << pass1State.anisotropicDetail.coherenceP10
            << "; spectraAnisotropicDetailCoherenceP50="
            << pass1State.anisotropicDetail.coherenceP50
            << "; spectraAnisotropicDetailCoherenceP90="
            << pass1State.anisotropicDetail.coherenceP90
            << "; spectraAnisotropicDetailMeanDirectionalWeight="
            << pass1State.anisotropicDetail.meanDirectionalWeight
            << "; spectraAnisotropicDetailMeanIsotropicAuthorityScale="
            << pass1State.anisotropicDetail.meanIsotropicAuthorityScale
            << "; spectraAnisotropicDetailMeanDirectionalAuthorityScale="
            << pass1State.anisotropicDetail.meanDirectionalAuthorityScale
            << "; spectraAnisotropicDetailMaximumLinearCorrection="
            << pass1State.anisotropicDetail.maximumLinearCorrection
            << "; spectraAnisotropicDetailTensorFieldBuildMs="
            << pass1State.anisotropicDetail.tensorFieldBuildMs
            << "; spectraAnisotropicDetailDirectionalFilterMs="
            << pass1State.anisotropicDetail.directionalFilterMs
            << "; spectraCfaBandObserverStatus=" << pass2State.observerStatus
            << "; spectraCfaBandObserverModelConfidence=" << pass2State.modelConfidence
            << "; spectraCfaBandObserverPixelAuthority=false"
            << "; spectraCfaBandObserverInputEnergy=" << budgetState.initialChromaResidualEnergy
            << "; spectraCfaBandObserverExpectedNoiseFloor=" << budgetState.predictedChromaNoiseFloor
            << "; spectraCfaBandObserverObservedOutputEnergy=" << budgetState.pass2ChromaResidualEnergy
            << "; spectraCfaBandObserverImageUnchanged=true"
            << "; spectraCfaBandObserverProcessingTimeMs=" << pass2State.processingTimeMs
            << "; spectraCfaLowBandObserverStatus=" << lowBandEvidence.status
            << "; spectraCfaLowBandObserverInputEnergy=" << lowBandEvidence.inputEnergy
            << "; spectraCfaLowBandObserverTargetFloor=" << lowBandEvidence.expectedNoiseFloor
            << "; spectraCfaLowBandObserverExcessEnergy=" << lowBandEvidence.excessEnergy
            << "; spectraCfaLowBandObserverResidualPressure=" << lowBandEvidence.residualPressure
            << "; spectraCfaLowBandObserverModelConfidence=" << lowBandEvidence.modelConfidence
            << "; spectraCfaLowBandObserverDirectionalEvidence=" << lowBandEvidence.directionalEvidence
            << "; spectraCfaLowBandObserverMeasurementReused=true"
            << "; spectraCfaLowBandObserverProcessingTimeMs=" << lowBandObserverMs
            << "; spectraChromaBandsArchitecture=READ_ONLY_CFA_BAND_ENERGY_OBSERVER"
            << "; spectraChromaBandEnergyMethod=" << pass2State.bandEnergyMethod
            << "; spectraChromaBandEnergyInputStatus=" << pass2State.bandEnergyStatus
            << "; spectraChromaBandEnergyInputSampleCount=" << pass2State.bandEnergySampleCount
            << "; spectraChromaBandEnergyInputRedSampleCount=" << pass2State.bandEnergyRedSampleCount
            << "; spectraChromaBandEnergyInputBlueSampleCount=" << pass2State.bandEnergyBlueSampleCount
            << "; spectraChromaBandEnergyInputRedBlueSampleBalance=" << pass2State.bandEnergyRedBlueSampleBalance
            << "; spectraChromaBandEnergyInputConfidence=" << pass2State.bandEnergyConfidence
            << "; spectraChromaBandEnergyInputRedFine=" << initialChromaBands.redFineEnergy
            << "; spectraChromaBandEnergyInputRedMid=" << initialChromaBands.redMidEnergy
            << "; spectraChromaBandEnergyInputRedLow=" << initialChromaBands.redLowEnergy
            << "; spectraChromaBandEnergyInputBlueFine=" << initialChromaBands.blueFineEnergy
            << "; spectraChromaBandEnergyInputBlueMid=" << initialChromaBands.blueMidEnergy
            << "; spectraChromaBandEnergyInputBlueLow=" << initialChromaBands.blueLowEnergy
            << "; spectraChromaBandEnergyInputMeasurementMs=" << pass2State.bandEnergyMeasurementMs
            << "; spectraChromaBandEnergyPostPass2Status=" << postPass2ChromaBands.status
            << "; spectraChromaBandEnergyPostPass2SampleCount=" << postPass2ChromaBands.sampleCount
            << "; spectraChromaBandEnergyPostPass2RedSampleCount=" << postPass2ChromaBands.redSampleCount
            << "; spectraChromaBandEnergyPostPass2BlueSampleCount=" << postPass2ChromaBands.blueSampleCount
            << "; spectraChromaBandEnergyPostPass2RedBlueSampleBalance=" << postPass2ChromaBands.redBlueSampleBalance
            << "; spectraChromaBandEnergyPostPass2Confidence=" << postPass2ChromaBands.confidence
            << "; spectraChromaBandEnergyPostPass2RedFine=" << postPass2ChromaBands.redFineEnergy
            << "; spectraChromaBandEnergyPostPass2RedMid=" << postPass2ChromaBands.redMidEnergy
            << "; spectraChromaBandEnergyPostPass2RedLow=" << postPass2ChromaBands.redLowEnergy
            << "; spectraChromaBandEnergyPostPass2BlueFine=" << postPass2ChromaBands.blueFineEnergy
            << "; spectraChromaBandEnergyPostPass2BlueMid=" << postPass2ChromaBands.blueMidEnergy
            << "; spectraChromaBandEnergyPostPass2BlueLow=" << postPass2ChromaBands.blueLowEnergy
            << "; spectraChromaBandEnergyPostPass2MeasurementMs=" << postPass2BandMeasurementMs
            << formatCfaBandResidualEvidenceFields("spectraCfaFineBand", fineBandEvidence)
            << formatCfaBandResidualEvidenceFields("spectraCfaMidBand", midBandEvidence)
            << "; spectraNoRegretP0TotalTiles=" << pass0NoRegret.totalTiles
            << "; spectraNoRegretP0EvaluatedTiles=" << pass0NoRegret.evaluatedTiles
            << "; spectraNoRegretP0InvalidTiles=" << pass0NoRegret.invalidTiles
            << "; spectraNoRegretP0FullyAcceptedTiles=" << pass0NoRegret.acceptedTiles
            << "; spectraNoRegretP0PartiallyAcceptedTiles=" << pass0NoRegret.partiallyAcceptedTiles
            << "; spectraNoRegretP0RejectedTiles=" << pass0NoRegret.rejectedTiles
            << "; spectraNoRegretP0RejectedOversmooth=" << pass0NoRegret.rejectedOversmooth
            << "; spectraNoRegretP0RejectedDetailLoss=" << pass0NoRegret.rejectedDetailLoss
            << "; spectraNoRegretP0RejectedMeanDrift=" << pass0NoRegret.rejectedMeanDrift
            << "; spectraNoRegretP0RejectedNoImprovement=" << pass0NoRegret.rejectedNoImprovement
            << "; spectraNoRegretP0MeanAcceptance=" << pass0NoRegret.meanAcceptance
            << "; spectraNoRegretP0AcceptanceP10=" << pass0NoRegret.acceptanceP10
            << "; spectraNoRegretP0AcceptanceP50=" << pass0NoRegret.acceptanceP50
            << "; spectraNoRegretP0AcceptanceP90=" << pass0NoRegret.acceptanceP90
            << "; spectraNoRegretP0AttenuatedPixelFraction=" << pass0NoRegret.attenuatedPixelFraction
            << "; spectraNoRegretP0MeanColourShift=" << pass0NoRegret.meanColourShift
            << "; spectraNoRegretP0MaxColourShift=" << pass0NoRegret.maxColourShift
            << "; spectraNoRegretP0EdgePreservationScore=" << pass0NoRegret.edgePreservationScore
            << "; spectraNoRegretP0OversmoothingScore=" << pass0NoRegret.oversmoothingScore
            << "; spectraNoRegretP1TotalTiles=" << pass1NoRegret.totalTiles
            << "; spectraNoRegretP1EvaluatedTiles=" << pass1NoRegret.evaluatedTiles
            << "; spectraNoRegretP1InvalidTiles=" << pass1NoRegret.invalidTiles
            << "; spectraNoRegretP1FullyAcceptedTiles=" << pass1NoRegret.acceptedTiles
            << "; spectraNoRegretP1PartiallyAcceptedTiles=" << pass1NoRegret.partiallyAcceptedTiles
            << "; spectraNoRegretP1RejectedTiles=" << pass1NoRegret.rejectedTiles
            << "; spectraNoRegretP1RejectedOversmooth=" << pass1NoRegret.rejectedOversmooth
            << "; spectraNoRegretP1RejectedDetailLoss=" << pass1NoRegret.rejectedDetailLoss
            << "; spectraNoRegretP1RejectedMeanDrift=" << pass1NoRegret.rejectedMeanDrift
            << "; spectraNoRegretP1RejectedNoImprovement=" << pass1NoRegret.rejectedNoImprovement
            << "; spectraNoRegretP1MeanAcceptance=" << pass1NoRegret.meanAcceptance
            << "; spectraNoRegretP1AcceptanceP10=" << pass1NoRegret.acceptanceP10
            << "; spectraNoRegretP1AcceptanceP50=" << pass1NoRegret.acceptanceP50
            << "; spectraNoRegretP1AcceptanceP90=" << pass1NoRegret.acceptanceP90
            << "; spectraNoRegretP1AttenuatedPixelFraction=" << pass1NoRegret.attenuatedPixelFraction
            << "; spectraNoRegretP1MeanColourShift=" << pass1NoRegret.meanColourShift
            << "; spectraNoRegretP1MaxColourShift=" << pass1NoRegret.maxColourShift
            << "; spectraNoRegretP1EdgePreservationScore=" << pass1NoRegret.edgePreservationScore
            << "; spectraNoRegretP1OversmoothingScore=" << pass1NoRegret.oversmoothingScore
            << "; spectraResidualDomain=" << residualNoiseState.domain
            << "; spectraResidualValueStage=" << residualNoiseState.valueStage
            << "; spectraResidualLastObservedStage=" << residualNoiseState.lastObservedStage
            << "; spectraResidualPropagationStatus=" << residualNoiseState.propagationStatus
            << "; spectraResidualOpponentVarianceStatus=" << residualNoiseState.opponentVarianceStatus
            << "; spectraResidualCovarianceStatus=" << residualNoiseState.covarianceStatus
            << "; spectraResidualComparabilityStatus=" << residualNoiseState.comparabilityStatus
            << "; spectraResidualVarianceY=" << residualNoiseState.varianceY
            << "; spectraResidualVarianceRG=" << residualNoiseState.varianceRG
            << "; spectraResidualVarianceBG=" << residualNoiseState.varianceBG
            << "; spectraResidualCovarianceRgBg=" << residualNoiseState.covarianceRgBg
            << "; spectraResidualCovarianceRgb=[" << residualNoiseState.covarianceRgb[0] << "," << residualNoiseState.covarianceRgb[1] << "," << residualNoiseState.covarianceRgb[2] << "," << residualNoiseState.covarianceRgb[3] << "," << residualNoiseState.covarianceRgb[4] << "," << residualNoiseState.covarianceRgb[5] << "," << residualNoiseState.covarianceRgb[6] << "," << residualNoiseState.covarianceRgb[7] << "," << residualNoiseState.covarianceRgb[8] << "]"
            << "; spectraResidualHighFrequencyBudget=" << residualNoiseState.highFrequencyBudget
            << "; spectraResidualMidFrequencyBudget=" << residualNoiseState.midFrequencyBudget
            << "; spectraResidualLowFrequencyBudget=" << residualNoiseState.lowFrequencyBudget
            << "; spectraResidualCorrelationLength=" << residualNoiseState.correlationLength
            << "; spectraResidualDirectionalPatternEnergy=" << residualNoiseState.directionalPatternEnergy
            << "; spectraResidualRowPatternEnergy=" << residualNoiseState.rowPatternEnergy
            << "; spectraResidualColumnPatternEnergy=" << residualNoiseState.columnPatternEnergy
            << "; spectraResidualTemporalCorrelation=" << residualNoiseState.temporalCorrelation
            << "; spectraResidualIndependentNoiseFraction=" << residualNoiseState.independentNoiseFraction
            << "; spectraResidualEffectiveFrameCount=" << residualNoiseState.effectiveFrameCount
            << "; spectraResidualModelConfidence=" << residualNoiseState.modelConfidence
            << "; spectraResidualMotionConfidence=" << residualNoiseState.motionConfidence
            << "; spectraResidualAlignmentConfidence=" << residualNoiseState.alignmentConfidence
            << formatPropagationStateFields("spectraPreDemosaic", residualNoiseState.preDemosaic)
            << formatPropagationStateFields("spectraPostDemosaic", residualNoiseState.postDemosaic)
            << formatPropagationStateFields("spectraPostAwb", residualNoiseState.postAwb)
            << formatPropagationStateFields("spectraPostColourTransform", residualNoiseState.postColourTransform)
            << "; phase6HueSatNoisePropagationRequested=" << (calibratedHueSatMapActive ? "true" : "false")
            << "; phase6HueSatNoisePropagationReady=" << (hueSatNoisePropagation.ready ? "true" : "false")
            << "; phase6HueSatNoisePropagationMethod=" << hueSatNoisePropagation.method
            << "; phase6HueSatNoiseJacobianSamples=" << hueSatNoisePropagation.sampleCount
            << "; phase6HueSatNoiseLumaRmsGain=" << hueSatNoisePropagation.lumaRmsGain
            << "; phase6HueSatNoiseRgRmsGain=" << hueSatNoisePropagation.redGreenRmsGain
            << "; phase6HueSatNoiseBgRmsGain=" << hueSatNoisePropagation.blueGreenRmsGain
            << "; phase6HueSatNoiseMaxOpponentRowGain=" << hueSatNoisePropagation.maximumOpponentRowGain
            << "; spectraWbChromaRmsGainRG=" << wbChromaRmsGainRg
            << "; spectraWbChromaRmsGainBG=" << wbChromaRmsGainBg
            << "; spectraWbChromaRmsGainCombined=" << wbChromaRmsGainCombined
            << "; spectraCcmChromaRmsGainRG=" << ccmChromaRmsGainRg
            << "; spectraCcmChromaRmsGainBG=" << ccmChromaRmsGainBg
            << "; spectraCcmChromaRmsGainCombined=" << ccmChromaRmsGainCombined
            << "; spectraWbCcmChromaRmsGainCombined=" << wbCcmChromaRmsGainCombined
            << "; spectraWbCcmRedOpponentDirectionalGain="
            << wbCcmRedOpponentDirectionalGain
            << "; spectraWbCcmBlueOpponentDirectionalGain="
            << wbCcmBlueOpponentDirectionalGain
            << "; spectraCcmOnlyRedOpponentDirectionalGain="
            << ccmOnlyRedOpponentDirectionalGain
            << "; spectraCcmOnlyBlueOpponentDirectionalGain="
            << ccmOnlyBlueOpponentDirectionalGain
            << formatPropagationStateFields("spectraPostTone", residualNoiseState.postTone)
            << formatPropagationStateFields(
                    "spectraPostQuantization",
                    residualNoiseState.postQuantization
            )
            << formatPropagationStateFields(
                    "spectraFinalJpeg",
                    residualNoiseState.finalJpeg
            )
            << formatResidualObservationFields(
                    "spectraMeasuredPostDemosaic",
                    residualNoiseState.measuredPostDemosaic
            )
            << formatResidualObservationFields(
                    "spectraMeasuredPostColourTransform",
                    residualNoiseState.measuredPostColourTransform
            )
            << formatCalibrationComparisonFields(
                    "spectraPostDemosaicCalibration",
                    residualNoiseState.postDemosaicCalibration
            )
            << "; spectraDemosaicChromaAuditStatus="
            << (demosaicChromaAuditReady ? "READY" : "INSUFFICIENT_FLAT_SUPPORT")
            << "; spectraDemosaicPreVarianceRG=" << demosaicPreVarianceRg
            << "; spectraDemosaicPreVarianceBG=" << demosaicPreVarianceBg
            << "; spectraDemosaicPredictedPostVarianceRG=" << demosaicPredictedPostVarianceRg
            << "; spectraDemosaicPredictedPostVarianceBG=" << demosaicPredictedPostVarianceBg
            << "; spectraDemosaicMeasuredPostRobustVarianceRG=" << demosaicMeasuredPostVarianceRg
            << "; spectraDemosaicMeasuredPostRobustVarianceBG=" << demosaicMeasuredPostVarianceBg
            << "; spectraDemosaicPredictedRmsGain=" << demosaicPredictedRmsGain
            << "; spectraDemosaicMeasuredPostToPredictedPreRmsRatio="
            << demosaicMeasuredPostToPredictedPreRmsRatio
            << "; spectraDemosaicMeasuredToPredictedPostRmsRatio="
            << demosaicMeasuredToPredictedPostRmsRatio
            << "; spectraDemosaicMeasuredPostAcceptedSamples="
            << residualNoiseState.measuredPostDemosaic.acceptedSampleCount
            << "; spectraDemosaicPreFineChromaEnergy=" << postPass2ChromaBands.fineEnergy
            << "; spectraDemosaicPreMidChromaEnergy=" << postPass2ChromaBands.midEnergy
            << "; spectraDemosaicPreLowChromaEnergy=" << postPass2ChromaBands.lowEnergy
            << "; spectraPreWbChromaCleanupPlanReady="
            << (preWbChromaCleanupPlanReady ? "true" : "false")
            << "; spectraPreWbMeasuredToPredictedRmsRatioRG="
            << preWbMeasuredToPredictedRmsRatioRg
            << "; spectraPreWbMeasuredToPredictedRmsRatioBG="
            << preWbMeasuredToPredictedRmsRatioBg
            << "; spectraPreWbGainRiskR=" << preWbGainRiskR
            << "; spectraPreWbGainRiskB=" << preWbGainRiskB
            << "; spectraPreWbNoiseAuthority=" << spectraPreWbNoiseAuthority
            << "; spectraCleanSceneCloudBypass="
            << (spectraCleanSceneCloudBypass ? "true" : "false")
            << "; spectraPreWbChromaCleanupRiskR=" << preWbChromaCleanupRiskR
            << "; spectraPreWbChromaCleanupRiskB=" << preWbChromaCleanupRiskB
            << "; spectraPreWbChromaCleanupProposedBlendR="
            << preWbChromaCleanupProposedBlendR
            << "; spectraPreWbChromaCleanupProposedBlendB="
            << preWbChromaCleanupProposedBlendB
            << "; spectraPreWbChromaCleanupExecutionReady="
            << (preWbChromaCleanupExecutionReady ? "true" : "false")
            << "; spectraPreWbChromaCleanupSuppressedByPhase6="
            << ((preWbChromaCleanupPlanReady && phase6ResidualChromaUsedForOutput) ? "true" : "false")
            << "; spectraPreWbChromaCleanupAppliedBlendR="
            << preWbChromaCleanupAppliedBlendR
            << "; spectraPreWbChromaCleanupAppliedBlendB="
            << preWbChromaCleanupAppliedBlendB
            << "; spectraPreWbNoiseModelEffectiveBlendR="
            << preWbNoiseModelEffectiveBlendR
            << "; spectraPreWbNoiseModelEffectiveBlendB="
            << preWbNoiseModelEffectiveBlendB
            << "; spectraPreWbChromaCleanupApplied="
            << (preWbChromaCleanupExecutionReady ? "true" : "false")
            << "; spectraPreWbChromaCleanupExecution="
            << (preWbChromaCleanupExecutionReady
                    ? (vulkanColorTransform.success
                            ? "FUSED_RESIDENT_PRE_WB"
                            : "CPU_FAILURE_RECOVERY_PRE_WB")
                    : "DISABLED")
            << formatPropagationStateFields("spectraPreWbNoiseModel", preWbNoiseState)
            << "; spectraDemosaicPostLowFrequencyChromaFieldStatus="
            << (demosaicLowFrequencyChromaFieldReady
                    ? "READY_FIELD_METRIC_NOT_NOISE_CLASSIFIED"
                    : "INSUFFICIENT_FLAT_TILE_SUPPORT")
            << "; spectraDemosaicPostLowFrequencyMedianRG="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyMedianRG
            << "; spectraDemosaicPostLowFrequencyMedianBG="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyMedianBG
            << "; spectraDemosaicPostLowFrequencyChromaFieldEnergyP50="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaFieldEnergyP50
            << "; spectraDemosaicPostLowFrequencyChromaFieldEnergyP90="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaFieldEnergyP90
            << "; spectraDemosaicPostLowFrequencyChromaNeighbourEnergyP50="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaNeighbourEnergyP50
            << "; spectraDemosaicPostLowFrequencyChromaNeighbourEnergyP90="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaNeighbourEnergyP90
            << "; spectraDemosaicPostLowFrequencyChromaValidTiles="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaValidTileCount
            << "; spectraDemosaicPostLowFrequencyChromaNeighbourPairs="
            << residualNoiseState.measuredPostDemosaic.lowFrequencyChromaNeighbourPairCount
            << "; spectraDemosaicPostBandEnergyStatus=LOCAL_AND_LOW_FREQUENCY_FIELD_AUDIT_READY"
            << formatCalibrationComparisonFields(
                    "spectraPostColourTransformCalibration",
                    residualNoiseState.postColourTransformCalibration
            )
            << "; spectraCalibrationStatus=" << residualNoiseState.calibrationStatus
            << "; spectraPropagationAwbGainsRgb=["
            << residualNoiseState.propagationAwbGainsRgb[0] << ","
            << residualNoiseState.propagationAwbGainsRgb[1] << ","
            << residualNoiseState.propagationAwbGainsRgb[2] << "]"
            << "; spectraPropagationColourMatrix=["
            << residualNoiseState.propagationColourMatrix[0] << ","
            << residualNoiseState.propagationColourMatrix[1] << ","
            << residualNoiseState.propagationColourMatrix[2] << ","
            << residualNoiseState.propagationColourMatrix[3] << ","
            << residualNoiseState.propagationColourMatrix[4] << ","
            << residualNoiseState.propagationColourMatrix[5] << ","
            << residualNoiseState.propagationColourMatrix[6] << ","
            << residualNoiseState.propagationColourMatrix[7] << ","
            << residualNoiseState.propagationColourMatrix[8] << "]"
            << formatDerivativeStatsFields("spectraToneCurveDerivative", residualNoiseState.toneCurveDerivative)
            << formatDerivativeStatsFields("spectraSectionCurveDerivative", residualNoiseState.sectionCurveDerivative)
            << formatDerivativeStatsFields("spectraGammaCurveDerivative", residualNoiseState.gammaCurveDerivative)
            << formatDerivativeStatsFields("spectraTotalToneDerivative", residualNoiseState.totalToneDerivative)
            << formatDerivativeStatsFields("spectraToneChromaScale", residualNoiseState.toneChromaScale)
            << "; spectraToneChromaRmsGainRG=" << toneChromaRmsGainRg
            << "; spectraToneChromaRmsGainBG=" << toneChromaRmsGainBg
            << "; spectraToneChromaRmsGainCombined=" << toneChromaRmsGainCombined
            << "; spectraToneLumaRmsGain=" << toneLumaRmsGain
            << "; spectraToneChromaAmplificationRisk=" << toneChromaAmplificationRisk
            << "; spectraToneGuardPostToneResidualSuggestedAttenuation="
            << toneGuardSuggestedAttenuation
            << "; spectraToneGuardWbCcmAmplificationRisk="
            << toneGuardWbCcmAmplificationRisk
            << "; spectraToneGuardDemosaicCalibrationRisk="
            << toneGuardDemosaicCalibrationRisk
            << "; spectraToneGuardCfaRisk=" << toneGuardCfaRisk
            << "; spectraToneGuardBaseFeedForwardRisk=" << toneGuardBaseFeedForwardRisk
            << "; spectraToneGuardCloudRisk=" << toneGuardCloudRisk
            << "; spectraToneGuardFeedForwardRisk=" << toneGuardFeedForwardRisk
            << "; spectraToneGuardMode=AUTO_LOW_LIGHT_LIFT_ONLY"
            << "; spectraDemosaicMeasuredChromaCalibrationReady="
            << (residualNoiseState.demosaicMeasuredChromaCalibrationReady ? "true" : "false")
            << "; spectraDemosaicMeasuredToPredictedPostChromaRmsRatio="
            << residualNoiseState.demosaicMeasuredToPredictedPostChromaRmsRatio
            << "; spectraDemosaicMeasuredChromaAuthorityPressure="
            << residualNoiseState.demosaicMeasuredChromaAuthorityPressure
            << "; spectraDemosaicChromaCloudClassificationReady="
            << (residualNoiseState.demosaicChromaCloudClassificationReady ? "true" : "false")
            << "; spectraDemosaicChromaFieldToResidualRmsRatio="
            << residualNoiseState.demosaicChromaFieldToResidualRmsRatio
            << "; spectraDemosaicChromaFieldCoherence="
            << residualNoiseState.demosaicChromaFieldCoherence
            << "; spectraDemosaicUnexpectedChromaAmplification="
            << residualNoiseState.demosaicUnexpectedChromaAmplification
            << "; spectraDemosaicPreLowFrequencyChromaSupport="
            << residualNoiseState.demosaicPreLowFrequencyChromaSupport
            << "; spectraDemosaicChromaCloudRiskEvidence="
            << residualNoiseState.demosaicChromaCloudRiskEvidence
            << "; spectraDemosaicChromaCloudRiskStatus="
            << residualNoiseState.demosaicChromaCloudRiskStatus
            << "; spectraDemosaicChromaCloudPlanReady=false"
            << "; spectraDemosaicChromaCloudPlanStatus=RETIRED_CLASSICAL_PIXEL_OWNER_NEURAL_PENDING"
            << "; spectraPreWbCloudTransportContractReady=false"
            << "; spectraPreWbCloudGpuMapUploaded=false"
            << "; spectraPreWbCloudSpatialCorrectionApplied=false"
            << "; spectraMeasuredPreSharpenStage=POST_QUANTIZATION_8BIT_PRE_SHARPEN"
            << "; spectraMeasuredPreSharpenVarianceY="
            << residualNoiseState.measuredPreSharpenVarianceY
            << "; spectraMeasuredPreSharpenVarianceRG="
            << residualNoiseState.measuredPreSharpenVarianceRG
            << "; spectraMeasuredPreSharpenVarianceBG="
            << residualNoiseState.measuredPreSharpenVarianceBG
            << "; spectraMeasuredPreSharpenCovarianceRgBg="
            << residualNoiseState.measuredPreSharpenCovarianceRgBg
            << "; spectraMeasuredPreSharpenSampleCount="
            << residualNoiseState.measuredPreSharpenSampleCount
            << "; spectraMeasuredPostIspStage=" << residualNoiseState.measuredPostIspStage
            << "; spectraMeasuredPostIspMethod=" << residualNoiseState.measuredPostIspMethod
            << "; spectraMeasuredPostIspStatus=" << residualNoiseState.measuredPostIspStatus
            << "; spectraMeasuredPostIspFilterEnergyGain=" << residualNoiseState.measuredPostIspFilterEnergyGain
            << "; spectraMeasuredPostIspVarianceY=" << residualNoiseState.measuredPostIspVarianceY
            << "; spectraMeasuredPostIspVarianceRG=" << residualNoiseState.measuredPostIspVarianceRG
            << "; spectraMeasuredPostIspVarianceBG=" << residualNoiseState.measuredPostIspVarianceBG
            << "; spectraMeasuredPostIspCovarianceRgBg=" << residualNoiseState.measuredPostIspCovarianceRgBg
            << "; spectraMeasuredPostIspSampleCount=" << residualNoiseState.measuredPostIspSampleCount
            << "; spectraCfaVisibleRiskReady=" << (residualNoiseState.cfaVisibleRiskReady ? "true" : "false")
            << "; spectraCfaVisibleRiskStatus=" << residualNoiseState.cfaVisibleRiskStatus
            << "; spectraCfaVisibleRiskConfidence=" << residualNoiseState.cfaVisibleRiskConfidence
            << "; spectraCfaVisibleChromaAmplification=" << residualNoiseState.cfaVisibleChromaAmplification
            << "; spectraCaptureProvenanceMs=" << captureProvenanceMs
            << "; spectraFinalProvenanceMs=" << finalProvenanceMs
            << "; spectraTotalProcessingMs=" << spectraProcessingMs
            << "; spectraAccountedProcessingMs=" << spectraAccountedMs
            << "; spectraUnattributedProcessingMs=" << spectraUnattributedMs
            << "; rawIspAccountedMs=" << rawIspAccountedMs
            << "; rawIspUnattributedMs=" << rawIspUnattributedMs
            << "; spatialNoiseMapGridWidth=" << g_spatialNoiseMap.gridWidth
            << "; spatialNoiseMapGridHeight=" << g_spatialNoiseMap.gridHeight
            << "; spatialNoiseMapMinSigma=" << g_spatialNoiseMap.minSigma
            << "; spatialNoiseMapMeanSigma=" << g_spatialNoiseMap.meanSigma
            << "; spatialNoiseMapMaxSigma=" << g_spatialNoiseMap.maxSigma
            << "; spatialNoiseMapRelativePhysicalShape=" << (g_spatialNoiseMap.relativePhysicalShape ? "true" : "false")
            << "; spatialNoiseMapLensShadingSquaredIncluded=" << (g_spatialNoiseMap.lensShadingSquaredIncluded ? "true" : "false")
            << "; spatialNoiseMapAuthority=" << g_spatialNoiseMap.authority
            << "; physicalSpatialNoiseShapeInstalled=" << (physicalSpatialNoiseShapeInstalled ? "true" : "false")
            << "; postDemosaicAbsoluteNoiseAuthority=PROPAGATED_RESIDUAL_COVARIANCE"
            << "; sensorNoiseProfileIsoExposureRescaled=false"
            << "; spatialExposureNoiseScaleRms=" << spatialExposureNoiseScale
            << "; spatialExposureMeanGainSquared=" << spatialExposureMeanGainSquared
            << "; noiseMapCoordinateSpace=" << g_spatialNoiseMap.rawWidth << "x" << g_spatialNoiseMap.rawHeight
            << "; denoiseConsumerCoordinateSpace=" << linearRgb.cols << "x" << linearRgb.rows
            << "; lensIsoNrMode=" << uiConfig.lensIsoNrMode
            << "; lensIsoNrReference=" << lensIsoNrReference
            << "; lensIsoNrMultiplier=" << dynamicIsoMultiplier
            << "; dynamicIsoCoefficient=" << uiConfig.lensDynamicIsoCoeff
            << "; frameIso=" << referenceFrameIso
            << "; dynamicIsoActivationSource=calibrated_so_noise_pressure"
            << "; dynamicNoiseTruthActivation=" << noiseTruthActivation
            << "; normalizedDynamicChromaAuthority=" << normalizedChromaAuthority
            << "; sensorIsoActivation=" << noiseTruthActivation
            << "; physicalNoiseModelBaseline=" << clampedBaseline
            << "; commonNormalizedDenoiseCeiling=" << maximumDenoiseCeiling
            << "; formatSpecificDenoiseCeiling=" << maximumDenoiseCeiling
            << "; availableDenoiseHeadroom=" << availableHeadroom
            << "; requestedHeadroomFraction=" << dynamicBlend
            << "; requestedAdditionalStrength=" << requestedAdditionalStrength
            << "; dynamicIsoMultiplier=" << dynamicIsoMultiplier
            << "; finalChromaDenoiseStrengthBeforeClamp=" << finalChromaNrStrengthBeforeClamp
            << "; finalChromaDenoiseStrengthAfterClamp=" << chromaNrStrength
            << "; budgetedChromaAdditionalStrength=0.0000"
            << "; physicalChromaBaselinePreserved=false"
            << "; ceilingReached=" << (ceilingReached ? "true" : "false")
            << "; theoreticalSaturatingCoeff=" << theoreticalSaturatingCoeff
            << "; processedPixelCount=" << g_threadLocalIspStats.processedPixelCount
            << "; changedPixelCount=" << g_threadLocalIspStats.changedPixelCount
            << "; changedPixelFraction=" << g_threadLocalIspStats.changedPixelFraction
            << "; meanAbsLumaDelta=" << g_threadLocalIspStats.meanAbsLumaDelta
            << "; meanAbsChromaDelta=" << g_threadLocalIspStats.meanAbsChromaDelta
            << "; maxLumaDelta=" << g_threadLocalIspStats.maxLumaDelta
            << "; maxChromaDelta=" << g_threadLocalIspStats.maxChromaDelta
            << "; avgNeighbourAcceptanceRate=" << g_threadLocalIspStats.avgNeighbourAcceptanceRate
            << "; avgNonCentreSampleWeight=" << g_threadLocalIspStats.avgNonCentreSampleWeight
            << "; avgTotalFilterWeight=" << g_threadLocalIspStats.avgTotalFilterWeight
            << "; avgAppliedBlend=" << g_threadLocalIspStats.avgAppliedBlend
            << "; edgeProtectedPixelFraction=" << g_threadLocalIspStats.edgeProtectedPixelFraction
            << "; absoluteMeanLumaSigma=" << g_threadLocalIspStats.absoluteMeanLumaSigma
            << "; absoluteMeanChromaSigma=" << g_threadLocalIspStats.absoluteMeanChromaSigma
            << "; physicalNoiseModelAvailable=" << (physicalNoiseModelAvailable ? "true" : "false")
            << "; physicalNoiseModelPixelAuthority=false"
            << "; spectraOffPhysicalLumaBaselineActive=false"
            << "; pass1EffectiveMaxLinearShift=" << pass1State.maxLinearShift
            << "; residualSeedConfidence=" << residualSeedConfidence.confidence
            << "; residualSeedConfidenceStatus=" << residualSeedConfidence.status
            << "; residualSeedConfidenceMethod=" << residualSeedConfidence.method
            << "; residualSeedPhysicalFallbackActive="
            << (residualSeedConfidence.physicalFallbackActive ? "true" : "false")
            << "; residualSeedChannelCoverage=" << residualSeedConfidence.channelCoverage
            << "; residualSeedVarianceReady="
            << (residualSeedConfidence.varianceReady ? "true" : "false")
            << "; phase4PlannerInputLumaSigma=" << postToneResidualLumaSigma
            << "; phase4PlannerInputChromaSigma=" << postToneResidualChromaSigma
            << "; phase4PlannerInputModelConfidence=" << postToneResidualModelConfidence
            << "; phase4PhysicalNoiseModelAvailable="
            << (physicalNoiseModelAvailable ? "true" : "false")
            << "; phase4SpectraContextFusionActive=" << (spectraNoiseActive ? "true" : "false")
            << "; phase4ResidualBudgetActive=false"
            << "; phase4ResidualAuthoritySource=RETIRED_N003"
            << "; phase4ResidualCovarianceAuthoritative=true"
            << "; phase4DuplicatePhysicalSigmaPrevented=true"
            << "; phase4ResidualInputLumaSigma=" << postToneResidualLumaSigma
            << "; phase4ResidualInputChromaSigma=" << postToneResidualChromaSigma
            << "; phase4ResidualModelConfidence=" << postToneResidualModelConfidence
            << "; phase4PhysicalBaselineLumaFraction=0.0000"
            << "; phase4PhysicalBaselineChromaFraction=0.0000"
            << "; phase4SpectraEnhancementActive=false"
            << "; phase4SpectraResidualLumaFraction=0.0000"
            << "; phase4SpectraResidualChromaFraction=0.0000"
            << "; phase4AppliedLumaSigma=0.0000"
            << "; phase4AppliedChromaSigma=0.0000"
            << "; phase4ResidualChromaStrengthScale=" << residualChromaStrengthScale
            << "; phase4DynamicIsoChromaSpectraGate=" << (spectraNoiseActive ? "ACTIVE" : "IDENTITY_OFF")
            << "; effectiveLumaSigma=" << g_threadLocalIspStats.effectiveLumaSigma
            << "; effectiveChromaSigma=" << g_threadLocalIspStats.effectiveChromaSigma
            << "; lumaRangeThresholdMin=" << g_threadLocalIspStats.lumaRangeThresholdMin
            << "; lumaRangeThresholdMean=" << g_threadLocalIspStats.lumaRangeThresholdMean
            << "; lumaRangeThresholdMax=" << g_threadLocalIspStats.lumaRangeThresholdMax
            << "; chromaRangeThresholdMin=" << g_threadLocalIspStats.chromaRangeThresholdMin
            << "; chromaRangeThresholdMean=" << g_threadLocalIspStats.chromaRangeThresholdMean
            << "; chromaRangeThresholdMax=" << g_threadLocalIspStats.chromaRangeThresholdMax
            << "; preDenoiseResidualEstimate=" << g_threadLocalIspStats.preDenoiseResidualEstimate
            << "; postDenoiseResidualEstimate=" << g_threadLocalIspStats.postDenoiseResidualEstimate
            << "; postSharpenResidualEstimate=" << g_threadLocalIspStats.postSharpenResidualEstimate
            << "; rawBlackAnchorOwner=PREDEMOSAIC_CALIBRATED_SENSOR_BLACK_LEVEL"
            << "; rawLowEndToneAnchorOwner=FLLF_PHYSICAL_NOISE_GATED_LOG2_LUMA"
            << "; fllfDeepBlackGuard=PROPAGATED_PHYSICAL_NOISE_RELATIVE"
            << "; rawAutomaticBlackAnchorApplied=false"
            << "; automaticGlobalToneCurveActive=false"
            << "; automaticPostCcmCameraLookActive=false"
            << "; phase5ToneArchitecture=LOG2_LUMA__PHYSICAL_NOISE_AWARE_FLLF__STRICT_YNEW_YOLD_ALPHA_1__KHRONOS_PBR_NEUTRAL"
            << "; phase5FllfDomain=SCENE_LINEAR_RGB_TO_PURE_LOG2_REC709_LUMA"
            << "; phase5FllfRgbReconstruction=STRICT_LUMINANCE_RATIO_ALPHA_1"
            << "; phase5FllfStrictRatioAlpha=" << 1.0f
            << "; phase5DisplayMapper=KHRONOS_PBR_NEUTRAL"
            << "; phase5DisplayMapperInput=FLLF_SCENE_LINEAR_RGB_PLUS_EXPLICIT_PROFILE_EXPOSURE"
            << "; phase5DisplayMapperOutput=DISPLAY_LINEAR"
            << "; phase5ProfileControlsStage=EXPOSURE_POST_FLLF_PRE_PBR__RANGE_CURVES_POST_PBR__COLOR_BEFORE_FINAL_GAMUT"
            << "; phase5OutputGamutOwner=POST_PROFILE_HUE_PRESERVING_UNIT_GAMUT"
            << "; rawColorAutomaticCameraRender=false"
            << "; rawColorAutomaticCameraOwner="
            << (calibratedHueSatMapActive
                    ? "PHASE2_CALIBRATED_DNG_FORWARD_MATRIX_PLUS_HUESATMAP"
                    : (calibratedMatrixProfileActive
                            ? "PHASE2_CALIBRATED_DNG_FORWARD_MATRIX"
                            : "PHASE2_CHARACTERIZATION_FALLBACK_OR_NONE"))
            << "; rawColorDisplayHighlightChromaSafety=SOURCE_RAW_PHASE9_OWNER"
            << "; phase5ColorPairOwner=" << (exactCamera2ColorPair
                    ? "CAMERA2_EXACT_FRAME_PAIR_WITH_BOUNDED_PHYSICAL_AWB"
                    : "RESOLVED_FALLBACK_OR_PROFILE")
            << "; toneSensorClipPressure=" << fllfPlan.highlightPressure
            << "; toneShadowPressure=" << fllfPlan.shadowPressure
            << "; toneDynamicRangePressure=" << fllfPlan.dynamicRangePressure
            << "; toneSceneKey=" << fllfPlan.sceneKey
            << "; toneSceneRangeStops=" << fllfPlan.sceneRangeStops
            << "; fllfPhysicalNoiseSigmaY=" << phase5FllfPhysicalNoiseSigmaY
            << "; fllfNoiseModelConfidence=" << phase5FllfNoiseModelConfidence
            << "; fllfNoiseAwareCoring=LOG2_SIGMA_FROM_PROPAGATED_SCENE_LINEAR_COVARIANCE"
            << "; postToneNoiseJacobianOwner=NUMERIC_KHRONOS_PBR_NEUTRAL_Y_RG_BG_OPPONENT"
            << "; toneContrastStrength=" << effectiveToneContrastStrength
            << "; profileToneHighlights=" << uiConfig.profileToneHighlights
            << "; profileToneShadows=" << uiConfig.profileToneShadows
            << "; profileToneWhites=" << uiConfig.profileToneWhites
            << "; profileToneBlacks=" << uiConfig.profileToneBlacks
            << "; profileToneContrast=" << uiConfig.profileToneContrast
            << "; profileLocalToneBias=" << uiConfig.profileLocalToneBias
            << "; localToneEnabled=" << "false"
            << "; localToneBackend=" << "RETIRED_PHASE5_FLLF_SINGLE_TONE_OWNER"
            << "; localToneAdjustedPixels=" << vulkanTone.localToneAdjustedPixels
            << "; fllfEnabled=" << (fllfPlan.enabled ? "true" : "false")
            << "; fllfProductionOwner=VULKAN_LOG2_LUMA_PRIMARY_AUTOMATIC_TONE_AUTHORITY"
            << "; fllfAuthoritySource=SCENE_RANGE_PLUS_PROPAGATED_PHYSICAL_NOISE_COVARIANCE"
            << "; fllfStrength=" << fllfPlan.strength
            << "; fllfSceneKey=" << fllfPlan.sceneKey
            << "; fllfMaxLiftEv=" << fllfPlan.maxLiftEv
            << "; fllfMaxCompressEv=" << fllfPlan.maxCompressEv
            << "; fllfEdgeStopEv=" << fllfPlan.edgeStopEv
            << "; fllfRefinement=" << fllfPlan.refinement
            << "; fllfNoisePressure=" << fllfPlan.noisePressure
            << "; fllfPropagationPolicy=" << "MAX_POSITIVE_GAIN_BOUND_NO_NEGATIVE_NR_CREDIT"
            << "; fllfConservativePropagationGain=" << phase5FllfConservativePropagationGain
            << "; fllfBackend=" << (vulkanTone.fllfApplied
                    ? "VULKAN_RESIDENT"
                    : (fllfPlan.enabled ? "REQUESTED_NOT_APPLIED" : "BYPASSED_POLICY"))
            << "; fllfAdjustedPixels=" << vulkanTone.fllfAdjustedPixels
            << "; fllfEdgeProtectedSamples=" << vulkanTone.fllfEdgeProtectedSamples
            << "; fllfMeanAbsCorrectionEv=" << vulkanTone.fllfMeanAbsCorrectionEv
            << "; fllfMaxAbsCorrectionEv=" << vulkanTone.fllfMaxAbsCorrectionEv
            << "; fllfPyramidBuildMs=" << vulkanTone.fllfPyramidBuildMs
            << "; fllfRemapReconstructMs=" << vulkanTone.fllfRemapReconstructMs
            << "; fllfResidentBytes=" << vulkanTone.fllfResidentBytes
            << "; curveStackApplied=" << (anyCurveActive ? "true" : "false")
            << "; toneCurveApplied=" << (toneCurveActive ? "true" : "false")
            << "; gammaCurveApplied=" << (gammaCurveActive ? "true" : "false")
            << "; sectionCurveApplied=" << (sectionCurveActive ? "true" : "false")
            << "; sharpenApplied=false"
            << "; sharpenAmount=0"
            << "; sharpenBackend=RETIRED_PHASE11_LINEAR_DETAIL_OWNER"
            << "; residentOutputSrgbEncoded=" << (residentOutputSrgbEncoded ? "true" : "false")
            << "; sharpenReason=" << downstreamIspState.resultStatus
            << "; finalOutputStatsSource=" << finalOutputStatsSource
            << "; finalRedClippedPct=" << finalRedClippedPct
            << "; finalGreenClippedPct=" << finalGreenClippedPct
            << "; finalBlueClippedPct=" << finalBlueClippedPct
            << "; defectCorrectionMs=" << defectDebug.elapsedMs
            << "; defectPreScanMs=" << defectDebug.preScanMs
            << "; defectFullPassMs=" << defectDebug.fullPassMs
            << "; expensiveDefectPassSkipped="
            << (defectDebug.expensivePassSkipped ? "true" : "false")
            << "; greenSplitMs=" << greenSplitDebug.elapsedMs
            << "; lensShadingMs=" << lensDebug.elapsedMs
            << "; rawFinalizeGpuTotalMs=" << vulkanRawFinalize.totalMs
            << "; rawFinalizeGpuGreenSamplingKernelMs=" << vulkanRawFinalize.greenSamplingKernelMs
            << "; rawFinalizeGpuGreenReductionCpuMs=" << vulkanRawFinalize.greenReductionCpuMs
            << "; rawFinalizeGpuKernelMs=" << vulkanRawFinalize.finalizeKernelMs
            << "; rawFinalizeGpuSynchronizationMs=" << vulkanRawFinalize.synchronizationMs
            << "; rawFinalizeGpuReadbackMs=" << vulkanRawFinalize.readbackMs
            << "; rawFinalizeGpuInputPackingMs=" << vulkanRawFinalize.inputPackingMs
            << "; rawFinalizeGpuLensMapUploadMs=" << vulkanRawFinalize.lensMapUploadMs
            << "; rawFinalizeAutoSceneMetricsReady=" << (vulkanRawFinalize.autoSceneMetricsReady ? "true" : "false")
            << "; rawFinalizeAutoSceneSampleCount=" << vulkanRawFinalize.autoSceneSampleCount
            << "; rawFinalizeAutoSceneMedianSignal=" << vulkanRawFinalize.autoSceneMedianSignal
            << "; rawFinalizeAutoSceneMeanGradient=" << vulkanRawFinalize.autoSceneMeanGradient
            << "; rawFinalizeAutoSceneP90Gradient=" << vulkanRawFinalize.autoSceneP90Gradient
            << "; rawFinalizeAutoSceneEdgeFraction=" << vulkanRawFinalize.autoSceneEdgeFraction
            << "; rawFinalizeAutoSceneCoherentEdgeFraction=" << vulkanRawFinalize.autoSceneCoherentEdgeFraction
            << "; rawFinalizeAutoSceneLowSignalFraction=" << vulkanRawFinalize.autoSceneLowSignalFraction
            << "; rawFinalizeGpuPersistentResidentBytes=" << vulkanRawFinalize.persistentResidentBytes
            << "; demosaicTimeMs=" << demosaicMs
            << "; demosaicMs=" << demosaicMs
            << "; demosaicTotalMs=" << demosaicMs
            << "; bilinearTimeMs="
            << (demosaicResolution.algorithm == DemosaicAlgorithm::Bilinear ? demosaicMs : 0.0f)
            << "; malvarTimeMs="
            << (demosaicResolution.algorithm == DemosaicAlgorithm::Malvar2004 ? demosaicMs : 0.0f)
            << "; menonTimeMs="
            << (demosaicResolution.algorithm == DemosaicAlgorithm::Menon2007 ? demosaicMs : 0.0f)
            << "; demosaicSetupMs=" << demosaicRunStats.setupMs
            << "; demosaicKernelMs=" << demosaicRunStats.kernelMs
            << "; demosaicFinalizeMs=" << demosaicRunStats.finalizeMs
            << "; demosaicInputWidth=" << demosaicInputWidth
            << "; demosaicInputHeight=" << demosaicInputHeight
            << "; workingBufferBytesEstimate=" << demosaicRunStats.workingBufferBytesEstimate
            << "; allocationReuse=" << (demosaicRunStats.allocationReuse ? "true" : "false")
            << "; demosaicScratchReused="
            << (demosaicRunStats.allocationReuse ? "true" : "false")
            << "; allocatedScratchBytesThisShot="
            << demosaicRunStats.allocatedScratchBytesThisShot
            << "; vulkanDemosaicPipelineAvailable="
            << (vulkanDemosaic.pipelineAvailable ? "true" : "false")
            << "; vulkanDemosaicAttempted=" << (vulkanDemosaic.attempted ? "true" : "false")
            << "; vulkanDemosaicExecutionSucceeded=" << (vulkanDemosaic.success ? "true" : "false")
            << "; vulkanDemosaicUsedForOutput=" << (vulkanDemosaic.gpuUsedForOutput ? "true" : "false")
            << "; vulkanDemosaicCpuFallbackRequired=" << (vulkanDemosaic.cpuFallbackRequired ? "true" : "false")
            << "; vulkanDemosaicStatus=" << vulkanDemosaic.status
            << "; vulkanDemosaicFailureReason=" << vulkanDemosaic.failureReason
            << "; vulkanDemosaicInputPackingMs=" << vulkanDemosaic.inputPackingMs
            << "; vulkanDemosaicUploadMs=" << vulkanDemosaic.uploadMs
            << "; vulkanDemosaicKernelMs=" << vulkanDemosaic.kernelMs
            << "; vulkanDemosaicAmazeGuideMs=" << vulkanDemosaic.amazeGreenPassMs
            << "; vulkanDemosaicAmazeReconstructMs=" << vulkanDemosaic.amazeReconstructPassMs
            << "; vulkanDemosaicAutoHybridGuideMs=" << vulkanDemosaic.autoHybridGuidePassMs
            << "; vulkanDemosaicAutoHybridBlendMs=" << vulkanDemosaic.autoHybridBlendPassMs
            << "; vulkanDemosaicResidualKernelMs=" << vulkanDemosaic.residualKernelMs
            << "; vulkanDemosaicReadbackMs=" << vulkanDemosaic.readbackMs
            << "; vulkanDemosaicFullReadbackDeferred="
            << (vulkanDemosaic.fullReadbackDeferred ? "true" : "false")
            << "; vulkanDemosaicSynchronizationMs=" << vulkanDemosaic.synchronizationMs
            << "; vulkanDemosaicTotalMs=" << vulkanDemosaic.totalMs
            << "; vulkanDemosaicPersistentReuse=" << (vulkanDemosaic.persistentBufferReuseHit ? "true" : "false")
            << "; vulkanDemosaicPersistentReallocated=" << (vulkanDemosaic.persistentBufferReallocated ? "true" : "false")
            << "; vulkanDemosaicPersistentResidentBytes=" << vulkanDemosaic.persistentResidentBytes
            << "; vulkanDemosaicAllocationGeneration=" << vulkanDemosaic.persistentAllocationGeneration
            << "; spectraDemosaicPropagationMs=" << residualNoiseState.demosaicPropagationMs
            << "; spectraAwbPropagationMs=" << residualNoiseState.awbPropagationMs
            << "; spectraColourTransformPropagationMs=" << residualNoiseState.colourTransformPropagationMs
            << "; spectraTonePropagationMs=" << residualNoiseState.tonePropagationMs
            << "; spectraTonePropagationTimingMode=nested_in_phase5ToneStageMs"
            << "; spectraMeasuredPostDemosaicResidualMs="
            << residualNoiseState.measuredPostDemosaicResidualMs
            << "; spectraMeasuredPostColourTransformResidualMs="
            << residualNoiseState.measuredPostColourTransformResidualMs
            << "; spectraMeasuredVisibleResidualMs=" << residualNoiseState.measuredVisibleResidualMs
            << "; spectraPropagationMathMs=" << spectraPropagationMathMs
            << "; spectraPropagationSequentialMs=" << spectraPropagationSequentialMs
            << "; phase7ExactCamera2ColorPair=" << (exactCamera2ColorPair ? "true" : "false")
            << "; phase7AwbEstimatorMs=" << phase7AwbEstimatorMs
            << "; phase7AwbSampleSource=" << phase7AwbSampleSource
            << "; phase7AwbStatus=" << phase7AwbEstimate.status
            << "; phase7AwbMethod=" << phase7AwbEstimate.method
            << "; phase7AwbPriorValid=" << (phase7AwbEstimate.priorValid ? "true" : "false")
            << "; phase7AwbDataReady=" << (phase7AwbEstimate.dataReady ? "true" : "false")
            << "; phase7AwbMixedIllumination="
            << (phase7AwbEstimate.mixedIllumination ? "true" : "false")
            << "; phase7AwbCandidateSamples=" << phase7AwbEstimate.candidateSampleCount
            << "; phase7AwbExposureValidSamples=" << phase7AwbEstimate.exposureValidSampleCount
            << "; phase7AwbAcceptedSamples=" << phase7AwbEstimate.acceptedSampleCount
            << "; phase7AwbValidTiles=" << phase7AwbEstimate.validTileCount
            << "; phase7AwbDarkFloor=" << phase7AwbEstimate.darkFloor
            << "; phase7AwbHighlightCeiling=" << phase7AwbEstimate.highlightCeiling
            << "; phase7AwbNeutralSupport=" << phase7AwbEstimate.neutralSupport
            << "; phase7AwbSampleSupport=" << phase7AwbEstimate.sampleSupport
            << "; phase7AwbTileSupport=" << phase7AwbEstimate.tileSupport
            << "; phase7AwbMixedLightScore=" << phase7AwbEstimate.mixedLightScore
            << "; phase7AwbLogGainMadR=" << phase7AwbEstimate.logGainMadR
            << "; phase7AwbLogGainMadB=" << phase7AwbEstimate.logGainMadB
            << "; phase7AwbLogGainSpanR=" << phase7AwbEstimate.logGainSpanR
            << "; phase7AwbLogGainSpanB=" << phase7AwbEstimate.logGainSpanB
            << "; phase7AwbPriorDisagreement=" << phase7AwbEstimate.priorDisagreement
            << "; phase7AwbConfidence=" << phase7AwbEstimate.confidence
            << "; phase7AwbDataAuthority=" << phase7AwbEstimate.dataAuthority
            << "; phase7AwbPriorRgb=[" << phase7AwbEstimate.priorGainsRgb[0] << ","
            << phase7AwbEstimate.priorGainsRgb[1] << "," << phase7AwbEstimate.priorGainsRgb[2] << "]"
            << "; phase7AwbDataRgb=[" << phase7AwbEstimate.dataGainsRgb[0] << ","
            << phase7AwbEstimate.dataGainsRgb[1] << "," << phase7AwbEstimate.dataGainsRgb[2] << "]"
            << "; phase7AwbFinalRgb=[" << phase7AwbEstimate.finalGainsRgb[0] << ","
            << phase7AwbEstimate.finalGainsRgb[1] << "," << phase7AwbEstimate.finalGainsRgb[2] << "]"
            << "; phase7AwbDarkRejectedFraction=" << phase7AwbEstimate.darkRejectedFraction
            << "; phase7AwbHighlightRejectedFraction=" << phase7AwbEstimate.highlightRejectedFraction
            << "; phase7AwbInvalidRejectedFraction=" << phase7AwbEstimate.invalidRejectedFraction
            << "; phase7AwbChromaticRejectedFraction=" << phase7AwbEstimate.chromaticRejectedFraction
            << "; awbColourTransformMs=" << awbColourTransformMs
            << "; awbTimingMode=phase7_compact_estimator_then_fused_vulkan_colour_transform"
            << "; colourTransformTimingMode=fused_with_awb"
            << "; vulkanAwbCcmAttempted=" << (vulkanColorTransform.attempted ? "true" : "false")
            << "; vulkanAwbCcmExecutionSucceeded=" << (vulkanColorTransform.success ? "true" : "false")
            << "; vulkanAwbCcmResidentInputUsed=" << (vulkanColorTransform.residentInputUsed ? "true" : "false")
            << "; vulkanAwbCcmCpuRgbUploadUsed=" << (vulkanColorTransform.cpuRgbUploadUsed ? "true" : "false")
            << "; vulkanAwbCcmStatus=" << vulkanColorTransform.status
            << "; vulkanAwbCcmFailureReason=" << vulkanColorTransform.failureReason
            << "; vulkanAwbCcmInputPackingMs=" << vulkanColorTransform.inputPackingMs
            << "; vulkanAwbCcmKernelMs=" << vulkanColorTransform.kernelMs
            << "; vulkanAwbCcmResidualKernelMs=" << vulkanColorTransform.residualKernelMs
            << "; vulkanAwbCcmReadbackMs=" << vulkanColorTransform.readbackMs
            << "; vulkanAwbCcmFullReadbackDeferred="
            << (vulkanColorTransform.fullReadbackDeferred ? "true" : "false")
            << "; vulkanAwbCcmResidentColorGeneration=" << vulkanColorTransform.residentColorGeneration
            << "; vulkanAwbCcmSynchronizationMs=" << vulkanColorTransform.synchronizationMs
            << "; vulkanAwbCcmCompactStatisticsReductionMs="
            << vulkanColorTransform.compactStatisticsReductionMs
            << "; vulkanAwbCcmCompactStatisticsBytes=" << vulkanColorTransform.compactStatisticsBytes
            << "; vulkanAwbCcmPersistentReuse="
            << (vulkanColorTransform.persistentBufferReuseHit ? "true" : "false")
            << "; vulkanAwbCcmPersistentReallocated="
            << (vulkanColorTransform.persistentBufferReallocated ? "true" : "false")
            << "; vulkanAwbCcmPersistentResidentBytes=" << vulkanColorTransform.persistentResidentBytes
            << "; vulkanAwbCcmAllocationGeneration=" << vulkanColorTransform.persistentAllocationGeneration
            << "; vulkanAwbCcmTotalMs=" << vulkanColorTransform.totalMs
            << "; vulkanSceneObserverAttempted=" << (vulkanSceneObserver.attempted ? "true" : "false")
            << "; vulkanSceneObserverExecutionSucceeded=" << (vulkanSceneObserver.success ? "true" : "false")
            << "; vulkanSceneObserverResidentInputUsed=" << (vulkanSceneObserver.residentInputUsed ? "true" : "false")
            << "; vulkanSceneObserverStatus=" << vulkanSceneObserver.status
            << "; vulkanSceneObserverFailureReason=" << vulkanSceneObserver.failureReason
            << "; vulkanSceneHighlightKernelMs=" << vulkanSceneObserver.highlightKernelMs
            << "; vulkanSceneSampleKernelMs=" << vulkanSceneObserver.sampleKernelMs
            << "; vulkanSceneCompactReadbackMs=" << vulkanSceneObserver.compactReadbackMs
            << "; vulkanSceneSynchronizationMs=" << vulkanSceneObserver.synchronizationMs
            << "; vulkanSceneTotalMs=" << vulkanSceneObserver.totalMs
            << "; vulkanSceneCompactBytes=" << vulkanSceneObserver.compactBytes
            << "; vulkanSceneSampleCount=" << vulkanSceneObserver.sampleCount
            << "; vulkanSceneCorrectedHighlightPixels=" << vulkanSceneObserver.correctedHighlightPixels
            << "; preToneClassicalChromaOwnerActive=false"
            << "; classicalNearBlackChromaOwnerActive=false"
            << "; nearBlackChromaWhiteningApplied=" << (vulkanSceneObserver.preToneChromaCovarianceWhiteningApplied ? "true" : "false")
            << "; nearBlackChromaCovarianceEvaluatedPixels=" << vulkanSceneObserver.preToneChromaCovarianceEvaluatedPixels
            << "; nearBlackChromaLowSnrPixels=" << vulkanSceneObserver.preToneChromaNearBlackPixels
            << "; nearBlackChromaStrongShrinkPixels=" << vulkanSceneObserver.preToneChromaStrongShrinkPixels
            << "; nearBlackChromaPreservedEvidencePixels=" << vulkanSceneObserver.preToneChromaPreservedEvidencePixels
            << "; nearBlackChromaMeanShrinkAuthority=" << vulkanSceneObserver.preToneChromaMeanShrinkAuthority
            << "; nearBlackChromaMaxMahalanobisRadius=" << vulkanSceneObserver.preToneChromaMaxMahalanobisRadius
            << "; nearBlackChromaCovarianceFallbackPixels=" << vulkanSceneObserver.preToneChromaCovarianceFallbackPixels
            << "; phase9ChromaTileScanApplied=" << (vulkanSceneObserver.preToneChromaTileScanApplied ? "true" : "false")
            << "; phase9ChromaTilesScanned=" << vulkanSceneObserver.preToneChromaTilesScanned
            << "; phase9ChromaEligibleTiles=" << vulkanSceneObserver.preToneChromaEligibleTiles
            << "; phase9ChromaCorrectedPixels=" << vulkanSceneObserver.preToneChromaCorrectedPixels
            << "; phase9ChromaDetailProtectedPixels=" << vulkanSceneObserver.preToneChromaDetailProtectedPixels
            << "; phase9ChromaStructuredTiles=" << vulkanSceneObserver.preToneChromaStructuredTiles
            << "; phase9ChromaMeanNoisePressure=" << vulkanSceneObserver.preToneChromaMeanNoisePressure
            << "; phase9ChromaMeanResidualSigma=" << vulkanSceneObserver.preToneChromaMeanResidualSigma
            << "; phase9ChromaMaxCorrection=" << vulkanSceneObserver.preToneChromaMaxCorrection
            << "; vulkanSceneResidentGeneration=" << vulkanSceneObserver.residentSceneGeneration
            << "; cpuSceneProcessingApplied=" << (cpuSceneProcessingApplied ? "true" : "false")
            << "; vulkanToneAttempted=" << (vulkanTone.attempted ? "true" : "false")
            << "; vulkanToneExecutionSucceeded=" << (vulkanTone.success ? "true" : "false")
            << "; vulkanToneUsedForOutput=" << (vulkanToneApplied ? "true" : "false")
            << "; toneMapperRequested=" << "KHRONOS_PBR_NEUTRAL"
            << "; toneMapperResolved="
            << (vulkanToneApplied ? "KHRONOS_PBR_NEUTRAL_VULKAN_RESIDENT" : "KHRONOS_PBR_NEUTRAL_CPU_FAILURE_REFERENCE")
            << "; toneProfileLookLutApplied=" << "true"
            << "; toneProfileLookLutOrder="
            << (vulkanToneApplied ? "POST_PBR_NEUTRAL_DISPLAY_LINEAR" : "CPU_FAILURE_REFERENCE_POST_PBR_NEUTRAL")
            << "; profileHighlightsExecution=POST_PBR_NEUTRAL_DISPLAY_LINEAR_TONE_LUT"
            << "; vulkanToneStatus=" << vulkanTone.status
            << "; vulkanToneFailureReason=" << vulkanTone.failureReason
            << "; vulkanToneLutUploadMs=" << vulkanTone.lutUploadMs
            << "; vulkanToneKernelMs=" << vulkanTone.kernelMs
            << "; vulkanToneReadbackMs=" << vulkanTone.readbackMs
            << "; vulkanToneSynchronizationMs=" << vulkanTone.synchronizationMs
            << "; vulkanToneTotalMs=" << vulkanTone.totalMs
            << "; vulkanToneFullReadbackDeferred=" << (vulkanTone.fullReadbackDeferred ? "true" : "false")
            << "; vulkanToneResidentGeneration=" << vulkanTone.residentToneGeneration
            << "; portraitRequested=" << (vulkanTone.portraitEffectRequested ? "true" : "false")
            << "; portraitApplied=" << (vulkanTone.portraitEffectApplied ? "true" : "false")
            << "; portraitStatus=" << vulkanTone.portraitStatus
            << "; highlightRecoveryMs=" << highlightDebug.elapsedMs
            << "; phase5ToneStageMs=" << phase5ToneStageMs
            << "; finalOutputPassMs=" << finalOutputPassMs
            << "; finalOutClampQuantMs=" << finalOutClampQuantMs
            << "; sharpenMs=0"
            << "; phase11LinearDetailPropagationMs=" << residualNoiseState.linearDetailPropagationMs
            << "; spectraDownstreamInputMeasurementMs="
            << residualNoiseState.measuredPreSharpenResidualMs
            << "; spectraDownstreamPropagationMs="
            << residualNoiseState.downstreamSharpenPropagationMs
            << "; outputRotateMs=" << outputRotateMs
            << "; jpegEncodeMs=" << jpegEncodeMs
            << "; totalRawIspCoreMs=" << totalRawIspCoreMs
            << "; totalRawBayerJpegIspMs=" << totalRawIspCoreMs
            << "; raw10UnpackMs=" << (isRaw10 ? meta.raw10UnpackMs : 0.0f)
            << "; raw10ToMasterRaw16Ms=" << (isRaw10 ? meta.raw10ToMasterRaw16Ms : 0.0f)
            << "; raw10MergeOrSingleMasterMs="
            << (isRaw10 ? meta.raw10MergeOrSingleMasterMs : 0.0f)
            << "; rawSensorReadMs=" << (isRawSensor ? meta.rawSensorReadMs : 0.0f)
            << "; rawSensorToMasterRaw16Ms="
            << (isRawSensor ? meta.rawSensorToMasterRaw16Ms : 0.0f)
            << "; raw10SharedRawBayerIspMs=" << (isRaw10 ? totalRawIspCoreMs : 0.0f)
            << "; rawSensorSharedRawBayerIspMs=" << (isRawSensor ? totalRawIspCoreMs : 0.0f)
            << "; spectraPerformanceMilestone=N006O_READ_ONLY_CFA_BAND_EVIDENCE"
            << "; spectraPerformancePrevious=M8H_J_GPU_RESIDENT_POST_CCM_SCENE_HIGHLIGHT_TONE_VIBRANCE_PROFILE_COLOR"
            << "; spectraPerformanceBase=N006O_SPECTRA_CORE_OBSERVATION_RAW_FINALIZE_DEMOSAIC_AWB_CCM_TONE"
            << "; spectraPerformanceEfContract=M8H_EF_GPU_PRIMARY_RAW_FINALIZE_DEMOSAIC_AWB_CCM_POST_DEMOSAIC"
            << "; spectraPerformanceHContract=N006D_READ_ONLY_NOISE_MAP_RAW_FINALIZE_DEMOSAIC_AWB_CCM"
            << "; spectraPerformanceIContract=N006D_TEMPORAL_OBSERVER_RAW_FINALIZE_DEMOSAIC_AWB_CCM"
            << "; spectraProductionBackend=VULKAN_GPU_PRIMARY_HYBRID_TRANSITION"
            << "; spectraPass1GpuPrimary="
            << (pass1State.vulkanUsedForOutput ? "true" : "false")
            << "; spectraCfaLowBandObserverPixelAuthority=false"
            << "; spectraDemosaicGpuPrimary="
            << (vulkanDemosaicResident ? "true" : "false")
            << "; spectraAwbCcmGpuPrimary="
            << (vulkanColorTransform.success ? "true" : "false")
            << "; spectraSceneObserverGpuPrimary="
            << (vulkanSceneObserverActive ? "true" : "false")
            << "; spectraToneGpuPrimary="
            << (vulkanToneApplied ? "true" : "false")
            << "; spectraAwbCcmToToneFullRgbRoundtripAvoided="
            << (vulkanColorResident && vulkanSceneObserverActive && vulkanToneApplied &&
                vulkanColorTransform.fullReadbackDeferred ? "true" : "false")
            << "; spectraDemosaicToAwbCcmFullRgbRoundtripAvoided="
            << (vulkanDemosaicResident && vulkanColorTransform.success &&
                vulkanColorTransform.residentInputUsed && vulkanDemosaic.fullReadbackDeferred
                    ? "true" : "false")
            << "; spectraPostDemosaicGpuPrimary="
            << (residentPostDemosaicApplied ? "true" : "false")
            << "; spectraCpuReferenceRole=TYPED_FALLBACK_AND_REMAINING_UNMIGRATED_STAGES"
            << "; spectraLegacyQualificationBackend="
            << bncam::spectra2::performanceBackendName(spectraPerformance.selection.selected)
            << "; spectraPerformanceBackend="
            << bncam::spectra2::performanceBackendName(spectraPerformance.selection.selected)
            << "; spectraPerformanceSelectionReason="
            << spectraPerformance.selection.selectionReason
            << "; spectraPerformanceNeonCompiled="
            << (spectraPerformance.selection.neonCompiled ? "true" : "false")
            << "; spectraPerformanceSimdKernelActive="
            << (spectraPerformance.selection.simdKernelActive ? "true" : "false")
            << "; spectraPerformanceSimdValidationPerformed="
            << (spectraPerformance.selection.simdValidationPerformed ? "true" : "false")
            << "; spectraPerformanceSimdValidationPassed="
            << (spectraPerformance.selection.simdValidationPassed ? "true" : "false")
            << "; spectraPerformanceSimdValidationStatus="
            << spectraPerformance.selection.simdValidationStatus
            << "; spectraPerformanceSimdValidationMaximumAbsoluteDelta="
            << spectraPerformance.selection.simdValidationMaximumAbsoluteDelta
            << "; spectraPerformanceSimdValidationMaximumRelativeDelta="
            << spectraPerformance.selection.simdValidationMaximumRelativeDelta
            << "; spectraPerformanceSimdValidationElapsedMs="
            << spectraPerformance.selection.simdValidationElapsedMs
            << "; spectraPerformanceSimdFallbackReason="
            << spectraPerformance.selection.simdFallbackReason
            << "; spectraPerformanceSimdDispatchCount=" << spectraSimdDispatchCount
            << "; spectraPerformanceSimdVectorizedLaneCount="
            << spectraSimdVectorizedLaneCount
            << "; spectraPerformanceSimdRejectedNonFiniteLaneCount="
            << spectraSimdRejectedNonFiniteLaneCount
            << "; spectraPerformanceInitialSimdKernelUsed="
            << (spectraPerformance.initialStatistics.simdKernelUsed ? "true" : "false")
            << "; spectraPerformancePostPass1SimdKernelUsed="
            << (spectraPerformance.postPass1Statistics.simdKernelUsed ? "true" : "false")
            << "; spectraPerformancePostPass2SimdKernelUsed="
            << (spectraPerformance.postPass2Statistics.simdKernelUsed ? "true" : "false")
            << "; spectraPerformanceVulkanPrepared="
            << (spectraPerformance.selection.vulkanRuntimePrepared ? "true" : "false")
            << "; spectraPerformanceVulkanSelected="
            << (spectraPerformance.selection.vulkanSelected ? "true" : "false")
            << "; spectraPerformanceVulkanStatus=" << spectraPerformance.vulkanStatus
            << "; spectraPerformanceDeviceProfileStatus="
            << spectraPerformance.deviceProfile.status
            << "; spectraPerformanceDeviceProfileRecommendation="
            << spectraPerformance.deviceProfile.recommendation
            << "; spectraPerformanceDeviceProfileWidth="
            << spectraPerformance.deviceProfile.width
            << "; spectraPerformanceDeviceProfileHeight="
            << spectraPerformance.deviceProfile.height
            << "; spectraPerformanceDeviceProfileSpectraEnabled="
            << (spectraPerformance.deviceProfile.spectraEnabled ? "true" : "false")
            << "; spectraPerformanceDeviceProfileRouteKey="
            << spectraPerformance.deviceProfile.routeKey
            << "; spectraPerformanceDeviceProfileSampleCount="
            << spectraPerformance.deviceProfile.sampleCount
            << "; spectraPerformanceDeviceProfileQualifiedSampleCount="
            << spectraPerformance.deviceProfile.qualifiedSampleCount
            << "; spectraPerformanceDeviceProfileWarmupDiscardedCount="
            << spectraPerformance.deviceProfile.warmupDiscardedCount
            << "; spectraPerformanceDeviceProfileReady="
            << (spectraPerformance.deviceProfile.profileReady ? "true" : "false")
            << "; spectraPerformanceDeviceProfileMedianTotalIspMs="
            << spectraPerformance.deviceProfile.medianTotalIspMs
            << "; spectraPerformanceDeviceProfileP95TotalIspMs="
            << spectraPerformance.deviceProfile.p95TotalIspMs
            << "; spectraPerformanceDeviceProfileMedianStatisticsMs="
            << spectraPerformance.deviceProfile.medianStatisticsMs
            << "; spectraPerformanceDeviceProfileMedianSpectraPassesMs="
            << spectraPerformance.deviceProfile.medianSpectraPassesMs
            << "; spectraPerformanceDeviceProfileMedianVisibleChromaMs="
            << spectraPerformance.deviceProfile.medianVisibleChromaMs
            << "; spectraPerformanceDeviceProfileMedianDownstreamIspMs="
            << spectraPerformance.deviceProfile.medianDownstreamIspMs
            << "; spectraPerformanceDeviceProfileFirstWindowMedianMs="
            << spectraPerformance.deviceProfile.firstWindowMedianMs
            << "; spectraPerformanceDeviceProfileRecentWindowMedianMs="
            << spectraPerformance.deviceProfile.recentWindowMedianMs
            << "; spectraPerformanceDeviceProfileThermalDriftRatio="
            << spectraPerformance.deviceProfile.thermalDriftRatio
            << "; spectraPerformanceDeviceProfileCoefficientOfVariation="
            << spectraPerformance.deviceProfile.coefficientOfVariation
            << "; spectraPerformanceDeviceProfileConfidence="
            << spectraPerformance.deviceProfile.confidence
            << "; spectraPerformanceDeviceProfileThermalStable="
            << (spectraPerformance.deviceProfile.thermalStable ? "true" : "false")
            << "; spectraPerformanceDeviceProfileThrottlingDetected="
            << (spectraPerformance.deviceProfile.throttlingDetected ? "true" : "false")
            << "; spectraPerformanceVulkanRuntimeReady="
            << (vulkanRuntimeSnapshot.state == bncam::vulkan::RuntimeState::READY ? "true" : "false")
            << "; spectraPerformanceVulkanTimestampQueriesSupported="
            << (vulkanCapabilities.timestampQueriesSupported ? "true" : "false")
            << "; spectraPerformanceVulkanVmaReady="
            << (vulkanCapabilities.vmaReady ? "true" : "false")
            << "; spectraPerformanceVulkanRuntimeGatePassed="
            << (spectraPerformance.vulkanQualification.runtimeGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanKernelGatePassed="
            << (spectraPerformance.vulkanQualification.kernelGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanBenchmarkGatePassed="
            << (spectraPerformance.vulkanQualification.benchmarkGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanNumericalGatePassed="
            << (spectraPerformance.vulkanQualification.numericalGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanLatencyGatePassed="
            << (spectraPerformance.vulkanQualification.latencyGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanTransferGatePassed="
            << (spectraPerformance.vulkanQualification.transferGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanThermalGatePassed="
            << (spectraPerformance.vulkanQualification.thermalGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanMemoryGatePassed="
            << (spectraPerformance.vulkanQualification.memoryGatePassed ? "true" : "false")
            << "; spectraPerformanceVulkanMeasuredSpeedup="
            << spectraPerformance.vulkanQualification.measuredSpeedup
            << "; spectraPerformanceVulkanTransferFraction="
            << spectraPerformance.vulkanQualification.transferFraction
            << "; spectraPerformanceVulkanMinimumRequiredSpeedup="
            << spectraPerformance.vulkanQualification.minimumRequiredSpeedup
            << "; spectraPerformanceVulkanMaximumTransferFraction="
            << spectraPerformance.vulkanQualification.maximumTransferFraction
            << "; spectraPerformanceVulkanMaximumAllowedAbsoluteDelta="
            << spectraPerformance.vulkanQualification.maximumAllowedAbsoluteDelta
            << "; spectraPerformanceVulkanBlockedReason="
            << spectraPerformance.vulkanQualification.blockedReason
            << "; spectraPerformanceMixedPrecisionStatus="
            << spectraPerformance.mixedPrecisionStatus
            << "; spectraPerformanceCorrectnessContract="
            << spectraPerformance.correctnessContract
            << "; spectraPerformanceFusedStatisticsDispatchCount="
            << spectraPerformance.fusedStatisticsDispatchCount
            << "; spectraPerformanceStatisticsTotalMs="
            << spectraPerformance.totalStatisticsMs
            << "; spectraPerformanceInitialStatisticsMs="
            << spectraPerformance.initialStatistics.elapsedMs
            << "; spectraPerformancePostPass1StatisticsMs="
            << spectraPerformance.postPass1Statistics.elapsedMs
            << "; spectraPerformancePostPass2StatisticsMs="
            << spectraPerformance.postPass2Statistics.elapsedMs
            << "; spectraPerformanceInitialStatisticsMethod="
            << spectraPerformance.initialStatistics.method
            << "; spectraPerformancePostPass1StatisticsMethod="
            << spectraPerformance.postPass1Statistics.method
            << "; spectraPerformancePostPass2StatisticsMethod="
            << spectraPerformance.postPass2Statistics.method
            << "; spectraPerformanceInitialTileCount="
            << spectraPerformance.initialStatistics.tileCount
            << "; spectraPerformanceInitialWorkerCount="
            << spectraPerformance.initialStatistics.workerCount
            << "; spectraPerformanceStatisticsBytesRead="
            << spectraStatisticsBytesRead
            << "; spectraPerformanceStatisticsBytesWritten=0"
            << "; spectraPerformanceStatisticsScratchBytes="
            << spectraStatisticsScratchBytes
            << "; spectraPerformanceFrameBytes=" << spectraPerformance.frameBytes
            << "; spectraPerformanceEstimatedReadPasses="
            << spectraPerformance.estimatedReadPasses
            << "; spectraPerformanceKnownFullFrameCloneCount="
            << spectraPerformance.knownFullFrameCloneCount
            << "; rawIspPassCount=" << rawIspPassCount
            << "; fusedPassesEnabled=true"
            << "; bufferReuseHitCount=" << bufferReuseHitCount
            << "; rawIspWorkingSetEstimateBytes=" << rawIspWorkingSetEstimateBytes
            << "; colorStageMeansRawR=" << rawMeanR
            << "; colorStageMeansRawG=" << rawMeanG
            << "; colorStageMeansRawB=" << rawMeanB
            << "; colorStageMeansWbR=" << wbMeanR
            << "; colorStageMeansWbG=" << wbMeanG
            << "; colorStageMeansWbB=" << wbMeanB
            << "; colorStageMeansCcmR=" << ccmMeanR
            << "; colorStageMeansCcmG=" << ccmMeanG
            << "; colorStageMeansCcmB=" << ccmMeanB
            << "; phase8ColorValidationDomain=POST_AWB_CCM_LINEAR_SRGB_PRE_PRESENTATION"
            << "; phase8ColorValidationFullFrameReadback=false"
            << "; phase8PresentationVibranceExcluded=true"
            << "; phase8ProfileColorControlsExcluded=true"
            << "; phase8ToneExcluded=true"
            << "; phase8EffectiveCcmFinite="
            << (phase8ColorMatrixAudit.finite ? "true" : "false")
            << "; phase8EffectiveCcmDeterminant=" << phase8ColorMatrixAudit.determinant
            << "; phase8EffectiveCcmMaxAbsCoefficient="
            << phase8ColorMatrixAudit.maxAbsCoefficient
            << "; phase8EffectiveCcmNeutralAxisR=" << phase8ColorMatrixAudit.neutralAxis[0]
            << "; phase8EffectiveCcmNeutralAxisG=" << phase8ColorMatrixAudit.neutralAxis[1]
            << "; phase8EffectiveCcmNeutralAxisB=" << phase8ColorMatrixAudit.neutralAxis[2]
            << "; phase8EffectiveCcmNeutralAxisMean=" << phase8ColorMatrixAudit.neutralAxisMean
            << "; phase8EffectiveCcmNeutralAxisSpread="
            << phase8ColorMatrixAudit.neutralAxisSpread
            << "; phase8PrePresentationSceneMeanFinite="
            << (phase8PrePresentationSceneAudit.finite ? "true" : "false")
            << "; phase8PrePresentationSceneMeanRgbSpread="
            << phase8PrePresentationSceneAudit.normalizedRgbSpread
            << "; ColorWorkingSpace=" << phase2ColorWorkingSpace
            << "; CameraMatrixSource=" << phase2CameraMatrixSource
            << "; CameraMatrixInterpolated=" << (phase2CameraMatrixInterpolated ? "true" : "false")
            << "; HueSatMapAvailable=" << (hueSatMapTelemetry.available ? "true" : "false")
            << "; HueSatMapSource=" << hueSatMapTelemetry.source
            << "; HueSatMapDims="
            << hueSatMapTelemetry.hueDivisions << "x"
            << hueSatMapTelemetry.saturationDivisions << "x"
            << hueSatMapTelemetry.valueDivisions
            << "; HueSatMapEncoding="
            << (hueSatMapTelemetry.available
                    ? (hueSatMapTelemetry.encoding == 1 ? "SRGB" : "LINEAR")
                    : "UNAVAILABLE")
            << "; HueSatMapIlluminantCount=" << hueSatMapTelemetry.illuminantCount
            << "; HueSatMapInterpolationWeight=" << hueSatMapTelemetry.interpolationWeight
            << "; HueSatMapApplied="
            << (phase9ColorDebug.calibratedHueSatMapApplied ? "true" : "false")
            << "; HueSatMapGpuPrimary="
            << (phase9ColorDebug.calibratedHueSatMapApplied && phase9ColorDebug.gpuPrimary
                    ? "true" : "false")
            << "; MeanAbsHueShift=" << hueSatMapTelemetry.meanAbsHueShift
            << "; MeanSaturationScale=" << hueSatMapTelemetry.meanSaturationScale
            << "; MeanValueScale=" << hueSatMapTelemetry.meanValueScale
            << "; MaxAbsHueShift=" << hueSatMapTelemetry.maxAbsHueShift
            << "; HueSatMapStatisticDomain=" << hueSatMapTelemetry.statisticDomain
            << "; ClippingConfidenceGateApplied="
            << (phase9ColorDebug.colorConfidenceAppliedPixels > 0u ? "true" : "false")
            << "; LegacyCameraProfileRenderApplied=false"
            << "; legacyPresentationReachableInTone=false"
            << "; rawColorCharacterizationOwner="
            << (cameraCharacterizationPlan.owner == bncam::color::RawCameraColorCharacterizationOwner::CALIBRATED_DNG_PROFILE
                    ? (cameraCharacterizationPlan.calibratedHueSatMapApply
                            ? "CALIBRATED_DNG_PROFILE_WITH_HUESATMAP" : "CALIBRATED_DNG_MATRIX_PROFILE")
                    : (cameraCharacterizationPlan.owner == bncam::color::RawCameraColorCharacterizationOwner::LEGACY_HUE_PRESERVING_PRESENTATION
                            ? "LEGACY_HUE_PRESERVING_PRESENTATION" : "NONE"))
            << "; rawColorCharacterizationReason=" << cameraCharacterizationPlan.reason
            << "; rawColorProfileRegistryGeneration=" << calibratedProfileRegistry.generation
            << "; rawColorProfileRegistryCount=" << calibratedProfileRegistry.profiles.size()
            << "; rawColorCalibratedProfileConfiguredForRoute="
            << (calibratedProfileConfiguredForCurrentRoute ? "true" : "false")
            << "; rawColorCalibratedProfileId="
            << (calibratedProfile != nullptr ? calibratedProfile->profileId : std::string("none"))
            << "; rawColorCalibratedProfileSourcePriority="
            << (calibratedProfile != nullptr ? calibratedProfile->sourcePriority : 0)
            << "; rawColorCalibratedProfileResolutionStatus=" << calibratedProfileResolution.status
            << "; rawColorCalibratedProfileMatrixShapeDistance="
            << calibratedProfileResolution.matrixShapeDistance
            << "; rawColorCalibratedProfileSecondMatrixShapeDistance="
            << calibratedProfileResolution.secondBestMatrixShapeDistance
            << "; rawColorCalibratedProfileSceneCctKelvin="
            << calibratedProfileResolution.sceneCctKelvin
            << "; rawColorCalibratedProfileWbFitLogRmse=" << calibratedProfileResolution.wbFitLogRmse
            << "; rawColorCalibratedProfileWeightFirst=" << calibratedProfileResolution.hueSatWeightFirst
            << "; rawColorCalibratedProfileWeightSecond=" << calibratedProfileResolution.hueSatWeightSecond
            << "; rawColorDngForwardTransformStatus=" << calibratedForwardTransform.status
            << "; rawColorDngForwardNeutralD50Error=" << calibratedForwardTransform.neutralD50Error
            << "; rawColorCalibratedMatrixApplied="
            << (cameraCharacterizationPlan.calibratedMatrixApply ? "true" : "false")
            << "; rawColorHueSatMapContract=OPTIONAL_GENUINE_PROFILE_AUGMENTATION"
            << "; rawColorHueSatMapRequested="
            << (phase9ColorDebug.calibratedHueSatMapRequested ? "true" : "false")
            << "; rawColorHueSatMapApplied="
            << (phase9ColorDebug.calibratedHueSatMapApplied ? "true" : "false")
            << "; rawColorHueSatMapAppliedPixels=" << phase9ColorDebug.calibratedHueSatMapAppliedPixels
            << "; rawColorHueSatMapProfileBytes=" << phase9ColorDebug.calibratedHueSatMapProfileBytes
            << "; rawColorHueSatMapRuntimeWeightFirst=" << phase9ColorDebug.calibratedHueSatMapWeightFirst
            << "; rawColorHueSatMapRuntimeWeightSecond=" << phase9ColorDebug.calibratedHueSatMapWeightSecond
            << "; rawColorAutomaticPostCcmRenderOwner=NONE"
            << "; rawColorCameraAuthority=PHASE2_CALIBRATED_CHARACTERIZATION"
            << "; rawColorCameraProfileHuePolicy="
            << (cameraCharacterizationPlan.calibratedHueSatMapApply
                    ? "CALIBRATED_PROFILE_OWNS_CAMERA_HUE"
                    : (cameraCharacterizationPlan.calibratedMatrixApply
                            ? "NO_CALIBRATED_HUESATMAP_MATRIX_PROFILE_ONLY"
                            : "NO_CAMERA_HUE_OWNER"))
            << "; rawColorCameraProfileRenderEnabled=false"
            << "; rawColorCameraProfileRenderReason=REMOVED_PHASE5_PHASE2_CHARACTERIZATION_SINGLE_OWNER"
            << "; rawColorCameraProfileSceneChromaP50=" << sceneChromaP50
            << "; rawColorCameraProfileSceneChromaP90=" << sceneChromaP90
            << "; phase9Owner=FUSED_AWB_CCM_SOURCE_RAW_CLIPPING_AWARE_HIGHLIGHT_GAMUT_V4"
            << "; phase9GpuPrimary=" << (phase9ColorDebug.gpuPrimary ? "true" : "false")
            << "; phase9CpuFailureReferenceUsed=" << (phase9ColorDebug.cpuFallback ? "true" : "false")
            << "; phase9SensorClipDomain=PRE_WB_RAW_FINALIZE_SOURCE_BAYER_2X2_CONFIDENCE"
            << "; phase9LegacyDemosaicConfidenceCandidatePixels=" << phase9ColorDebug.sensorClipCandidatePixels
            << "; phase9SourceRawConfidenceMapUsed="
            << (phase9ColorDebug.sourceRawConfidenceMapUsed ? "true" : "false")
            << "; phase9SourceRawConfidenceMapBytes=" << phase9ColorDebug.sourceRawConfidenceMapBytes
            << "; phase9SourceRawConfidenceCandidatePixels="
            << phase9ColorDebug.sourceRawConfidenceCandidatePixels
            << "; phase9SourceRawZeroConfidencePixels="
            << phase9ColorDebug.sourceRawZeroConfidencePixels
            << "; phase9SourceRawPartialConfidencePixels="
            << phase9ColorDebug.sourceRawPartialConfidencePixels
            << "; phase9SourceRawDemosaicDisagreementPixels="
            << phase9ColorDebug.sourceRawDemosaicDisagreementPixels
            << "; phase9CalibratedHueSatMapRequested="
            << (phase9ColorDebug.calibratedHueSatMapRequested ? "true" : "false")
            << "; phase9CalibratedHueSatMapApplied="
            << (phase9ColorDebug.calibratedHueSatMapApplied ? "true" : "false")
            << "; phase9CalibratedHueSatMapAppliedPixels="
            << phase9ColorDebug.calibratedHueSatMapAppliedPixels
            << "; phase9CalibratedHueSatMapProfileBytes="
            << phase9ColorDebug.calibratedHueSatMapProfileBytes
            << "; phase9SourceRawConfidencePropagation=2X2_BAYER_CELL_MIN_CONFIDENCE_PLUS_BOUNDED_3X3_DEMOSAIC_FOOTPRINT"
            << "; phase9LegacyDemosaicConfidenceRole=DIAGNOSTIC_AND_TYPED_CPU_FALLBACK_ONLY"
            << "; phase9SingleChannelSensorClipPixels=" << phase9ColorDebug.singleChannelSensorClipPixels
            << "; phase9MultiChannelSensorClipPixels=" << phase9ColorDebug.multiChannelSensorClipPixels
            << "; phase9FullySensorClippedPixels=" << phase9ColorDebug.fullySensorClippedPixels
            << "; phase9PartialColorConfidencePixels="
            << phase9ColorDebug.partialColorConfidencePixels
            << "; phase9ColorConfidenceAppliedPixels="
            << phase9ColorDebug.colorConfidenceAppliedPixels
            << "; phase9ColorConfidenceStart=0.960000"
            << "; phase9ColorConfidenceZero=0.995000"
            << "; phase9FullyClippedForcesZeroColorConfidence=true"
            << "; phase9HighlightColorPolicy=PRE_WB_CONFIDENCE_POST_CCM_NEUTRAL_LUMA_MIX"
            << "; phase9WbAboveUnityWithoutSensorClipPixels="
            << phase9ColorDebug.wbAboveUnityWithoutSensorClipPixels
            << "; phase9WbOverUnityPreserved=true"
            << "; phase9CcmNegativeExcursionPixels=" << phase9ColorDebug.ccmNegativeExcursionPixels
            << "; phase9GamutCompressedPixels=" << phase9ColorDebug.gamutCompressedPixels
            << "; phase9LegacyMagentaRiskPixels=" << phase9ColorDebug.legacyMagentaRiskPixels
            << "; phase9ProtectedMagentaRiskPixels=" << phase9ColorDebug.protectedMagentaRiskPixels
            << "; phase9SceneLinearOverUnityPixels=" << phase9ColorDebug.sceneLinearOverUnityPixels
            << "; phase9SceneLinearOverUnityPreserved=true"
            << "; phase9ToneOutputClippingOwner=PHASE5_PBR_NEUTRAL_FINAL_DISPLAY_MAPPER"
            << "; phase9LegacyPostCcmRecoveryOwner=false"
            << "; phase9LegacyRawPostToneNeutralizerOwner=false"
            << "; phase9NoGlobalDesaturation=true"
            << "; phase9FullFrameReadback=false"
            << "; phase9CpuFailureReferenceMs=" << phase9ColorDebug.cpuFallbackMs
            << "; colorStageMeansToneR=" << toneMeanR
            << "; colorStageMeansToneG=" << toneMeanG
            << "; colorStageMeansToneB=" << toneMeanB
            << "; nativeReceivedEnabled=" << (meta.phoneAssistanceSensorsEnabled ? "true" : "false")
            << "; nativeReceivedValid=" << (meta.auxSensorValid ? "true" : "false")
            << "; nativeReceivedCct=" << meta.auxCctKelvin
            << "; nativeReceivedContributionWeight=" << meta.auxContributionWeight
            << "; nativeAppliedContributionWeight=" << nativeAppliedContributionWeight
            << "; nativeAdjustmentType=" << nativeAdjustmentType
            << "; nativeAdjustmentMagnitude=" << nativeAdjustmentMagnitude
            << "; camera2AwbAnchorR=" << phase7AwbEstimate.priorGainsRgb[0]
            << "; camera2AwbAnchorB=" << phase7AwbEstimate.priorGainsRgb[2]
            << "; phase7FinalWbR=" << wbRgb[0]
            << "; phase7FinalWbG=" << wbRgb[1]
            << "; phase7FinalWbB=" << wbRgb[2]
            << "; cfaPattern=" << workingRaw.info.effectiveCfaPattern
            << "; lookStrength=1.0000"
            << "; legacyRawStackReachable=false"
            << "; yuvUntouched=true"
            << "; dngUntouched=true";
        *debugOut = dbg.str();
    }

    ISP_LOGI(
            "RAW_BASELINE_RENDER: source=%s; actualIso=%d; toneInputGain=%.4f; "
            "fllfEnabled=%s; pbrNeutral=true; scene=%s; profileVibrance=%.3f",
            sourceName,
            actualIso,
            exposureGain,
            fllfPlan.enabled ? "true" : "false",
            sceneClassificationFinal.c_str(),
            uiConfig.profilePresenceVibrance
    );
    return jpegData;
}

std::string IspCore::validateNoiseModelImplementation(
        const std::array<double, 8>& lowNoiseSo,
        const std::array<double, 8>& highNoiseSo
) {
    LinearFloatRaw raw{};
    raw.info.effectiveCfaPattern = CFA_BGGR;
    raw.diagnostics.valid = true;
    raw.diagnostics.failureReason = "none";
    raw.mosaic = cv::Mat(192, 256, CV_32FC1);
    for (int y = 0; y < raw.mosaic.rows; ++y) {
        float* row = raw.mosaic.ptr<float>(y);
        for (int x = 0; x < raw.mosaic.cols; ++x) {
            row[x] = static_cast<float>((x + y * raw.mosaic.cols) % 101) / 100.0f;
        }
    }

    auto metadataFor = [](int mode, const std::array<double, 8>& values) {
        IspFrameMetadata meta{};
        meta.cfaPattern = CFA_BGGR;
        meta.calibration.noiseModelMode = mode;
        meta.calibration.hasNoiseProfile = true;
        meta.calibration.noiseProfileApplied = true;
        meta.calibration.noiseProfilePairCount = 4;
        meta.calibration.noiseProfileChannelCount = 4;
        meta.calibration.spectraSnapshotPresent = mode != 0;
        meta.calibration.signalModelConfidence = mode != 0 ? 1.0f : 0.0f;
        meta.calibration.postRawSensitivityBoost = 100;
        for (int i = 0; i < 8; ++i) {
            meta.calibration.effectiveNoiseProfile[i] = values[static_cast<size_t>(i)];
        }
        for (int ch = 0; ch < 4; ++ch) {
            meta.calibration.effectiveS[ch] = values[static_cast<size_t>(ch * 2)];
            meta.calibration.effectiveO[ch] = values[static_cast<size_t>(ch * 2 + 1)];
        }
        return meta;
    };

    IspFrameMetadata off = metadataFor(0, highNoiseSo);
    sampleNoiseModelFromProductionRaw(raw, off);
    const size_t offSamples = g_threadLocalIspStats.sensorNoiseVarianceSamples;

    IspFrameMetadata low = metadataFor(1, lowNoiseSo);
    sampleNoiseModelFromProductionRaw(raw, low);
    const ThreadLocalIspStats lowStats = g_threadLocalIspStats;

    IspFrameMetadata high = metadataFor(1, highNoiseSo);
    sampleNoiseModelFromProductionRaw(raw, high);
    const ThreadLocalIspStats highStats = g_threadLocalIspStats;

    NativeRenderQualityConfig spectraConfig{};
    spectraConfig.lumaUserScale = 1.0f;
    spectraConfig.chromaUserScale = 1.0f;
    spectraConfig.lensDynamicIsoCoeff = 0.5f;
    IspFrameMetadata isoLowMeta = metadataFor(1, highNoiseSo);
    IspFrameMetadata isoMidMeta = metadataFor(1, highNoiseSo);
    IspFrameMetadata isoHighMeta = metadataFor(1, highNoiseSo);
    isoLowMeta.captureSensitivityIso = 100;
    isoMidMeta.captureSensitivityIso = 800;
    isoHighMeta.captureSensitivityIso = 6400;
    const SpectraIsoAdaptiveState isoLow = IspCore::resolveSpectraIsoAdaptiveState(isoLowMeta, spectraConfig);
    const SpectraIsoAdaptiveState isoMid = IspCore::resolveSpectraIsoAdaptiveState(isoMidMeta, spectraConfig);
    const SpectraIsoAdaptiveState isoHigh = IspCore::resolveSpectraIsoAdaptiveState(isoHighMeta, spectraConfig);
    const bool isoAdaptiveMonotonic =
            isoLow.effectiveIso < isoMid.effectiveIso && isoMid.effectiveIso < isoHigh.effectiveIso &&
            isoLow.combinedNoisePressure <= isoMid.combinedNoisePressure + 1.0e-6f &&
            isoMid.combinedNoisePressure <= isoHigh.combinedNoisePressure + 1.0e-6f;

    const SpectraProvenanceField provenance = buildSpectraProvenanceField(raw, isoHighMeta);
    const bool provenanceValid = provenance.validTiles > 0 &&
            provenance.meanPredictedRawVariance > 0.0f &&
            provenance.meanPredictedChromaResidualVariance > 0.0f;

    const bool measurementRespondsToSo =
            lowStats.sensorNoiseVarianceSamples > 0 && highStats.sensorNoiseVarianceSamples > 0 &&
            std::isfinite(lowStats.meanSensorNoiseVariance) &&
            std::isfinite(highStats.meanSensorNoiseVariance) &&
            lowStats.meanSensorNoiseVariance != highStats.meanSensorNoiseVariance;

    const bool allPassed =
            offSamples == 0 && measurementRespondsToSo && isoAdaptiveMonotonic && provenanceValid;

    std::ostringstream out;
    out << std::setprecision(17)
        << "lowSoReceived=[";
    for (size_t i = 0; i < lowNoiseSo.size(); ++i) {
        if (i > 0) out << ",";
        out << lowNoiseSo[i];
    }
    out << "];highSoReceived=[";
    for (size_t i = 0; i < highNoiseSo.size(); ++i) {
        if (i > 0) out << ",";
        out << highNoiseSo[i];
    }
    out << "]"
        << ";offSamples=" << offSamples
        << ";lowSamples=" << lowStats.sensorNoiseVarianceSamples
        << ";lowMeanVariance=" << lowStats.meanSensorNoiseVariance
        << ";highSamples=" << highStats.sensorNoiseVarianceSamples
        << ";highMeanVariance=" << highStats.meanSensorNoiseVariance
        << ";measurementRespondsToSo=" << (measurementRespondsToSo ? "true" : "false")
        << ";isoAdaptiveMonotonic=" << (isoAdaptiveMonotonic ? "true" : "false")
        << ";provenanceValid=" << (provenanceValid ? "true" : "false")
        << ";classicalPostDemosaicNrOwner=false"
        << ";profileNrOwner=false"
        << ";physicalBaselineNrOwner=false"
        << ";allPassed=" << (allPassed ? "true" : "false");
    return out.str();
}

std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(
        const cv::Mat& bayer16,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig,
        std::string* debugOut,
        int rotationDegrees
) {
    if (bayer16.empty() || bayer16.type() != CV_16UC1) {
        if (debugOut != nullptr) {
            *debugOut = "RAW_BASELINE_RENDER: source=invalid; failure=invalid_bayer16; "
                        "yuvUntouched=true; dngUntouched=true";
        }
        return {};
    }

    // PHASE13_REFERENCE_ONLY: compile-safe bridge retained for host/reference compatibility.
    // Production JNI RAW capture does not call this overload; it enters renderRawBaselineJpeg()
    // through ResidentRawRenderInput and only uses CPU normalization after explicit Vulkan failure.
    // The input here is treated as a read-only RAW16 master.
    RawDomainInfo info{};
    info.sourceFormat = meta.isRaw10 ? RawSourceFormat::RAW10 : RawSourceFormat::RAW_SENSOR;
    info.width = bayer16.cols;
    info.height = bayer16.rows;
    info.sourceRowStrideBytes = bayer16.step;
    info.sourcePixelStrideBytes = sizeof(uint16_t);
    info.masterRowStrideBytes = bayer16.step;
    info.masterPixelStrideBytes = sizeof(uint16_t);
    // Preserve the source arrangement for RawDomain contract resolution. Unsupported/non-Bayer
    // input must fail closed there rather than being silently coerced to RGGB.
    info.sensorCfaPattern = meta.cfaPattern;
    info.effectiveCfaPattern = info.sensorCfaPattern;
    info.sourceBitDepth = meta.isRaw10 ? 10 : 16;
    info.effectiveSourceRange = meta.isRaw10 ? 1023 : 65535;
    info.masterStorageScale = meta.isRaw10 ? (65535.0f / 1023.0f) : 1.0f;
    info.masterStorageContract = "LEGACY_BRIDGE_SCALED_RAW16";
    info.effectiveWhiteLevelInMasterUnits = static_cast<float>(
            std::max(1, meta.calibration.effectiveWhiteLevel)
    );
    for (size_t i = 0; i < 4; ++i) {
        info.effectiveBlackLevelPatternInMasterUnits[i] = static_cast<float>(
                std::max(0.0f, meta.calibration.effectiveBlackLevels[i])
        );
    }
    LinearFloatRaw workingRaw = normalizeRawForJpeg(bayer16.ptr<uint16_t>(0), info);
    return renderRawBaselineJpeg(std::move(workingRaw), meta, uiConfig, debugOut, rotationDegrees);

}

std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(
        LinearFloatRaw& workingRaw,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig,
        std::string* debugOut,
        int rotationDegrees
) {
    // PHASE13_REFERENCE_ONLY: retained for host/reference callers that intentionally own a
    // materialized CPU float RAW. Production JNI RAW capture does not use this clone path.
    LinearFloatRaw jpegOnlyCopy = workingRaw;
    jpegOnlyCopy.mosaic = workingRaw.mosaic.clone();
    return renderRawBaselineJpeg(std::move(jpegOnlyCopy), meta, uiConfig, debugOut, rotationDegrees);

}

std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(
        const cv::Mat& bayer16,
        const IspFrameMetadata& meta,
        const NativeRenderQualityConfig& uiConfig
) {
    return demosaicAndEncodeJpeg(bayer16, meta, uiConfig, nullptr);
}

std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(
        const cv::Mat& bayer16,
        const IspFrameMetadata& meta,
        float /* exposureUi */,
        float /* contrastUi */,
        float /* saturationUi */
) {
    NativeRenderQualityConfig cfg{};
    cfg.jpegQuality = 98;
    return demosaicAndEncodeJpeg(bayer16, meta, cfg, nullptr);
}

