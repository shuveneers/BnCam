#include "RawPreview.h"
#include "ProfileToneRenderPolicy.h"
#include "PhysicalAwbEstimator.h"
#include "RawCameraColorCharacterizationOwnership.h"
#include "RawCameraColorProfileRegistry.h"
#include "RawCameraColorProfileResolver.h"
#include "RawCameraDngForwardTransform.h"
#include "vulkan/VulkanRuntime.h"

#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
#include "Demosaic.h"
#include "ProfileColorManagement.h"
#include <opencv2/imgproc.hpp>
#endif

#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

// Kotlin owns the product RAW-preview resolution. Native keeps only a higher hard safety
// ceiling so a stale or malformed caller can never request an unbounded preview allocation.
constexpr int PREVIEW_HARD_MAX_WIDTH = 2048;
constexpr int PREVIEW_HARD_MAX_HEIGHT = 1536;
constexpr int RAW10_FORMAT = 37;
constexpr int RAW_SENSOR_FORMAT = 32;

void logPreviewFailure(const char* reason) {
    static std::atomic<std::uint64_t> failures{0u};
    const std::uint64_t count = failures.fetch_add(1u, std::memory_order_relaxed) + 1u;
    if (count == 1u || count % 60u == 0u) {
        __android_log_print(
                ANDROID_LOG_WARN, "BnCamRawPreview",
                "RAW_PREVIEW_NATIVE_FAILURE reason=%s count=%llu",
                reason, static_cast<unsigned long long>(count));
    }
}

#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
struct LockedRawBuffer {
    const std::uint8_t* data = nullptr;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rowStrideBytes = 0;
    std::uint32_t pixelStrideBytes = 0;
    bool locked = false;
};

std::uint32_t packedRaw10RowBytes(std::uint32_t width) {
    return ((width + 3u) / 4u) * 5u;
}

bool lockRaw(AHardwareBuffer* buffer, int sourceFormat, LockedRawBuffer& out) {
    if (buffer == nullptr || (sourceFormat != RAW10_FORMAT && sourceFormat != RAW_SENSOR_FORMAT)) {
        return false;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.width == 0u || desc.height == 0u) return false;
    out.width = desc.width;
    out.height = desc.height;

    AHardwareBuffer_Planes planes{};
    const int planesStatus = AHardwareBuffer_lockPlanes(
            buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &planes);
    if (planesStatus == 0 && planes.planeCount > 0u && planes.planes[0].data != nullptr) {
        out.data = static_cast<const std::uint8_t*>(planes.planes[0].data);
        out.rowStrideBytes = static_cast<std::uint32_t>(planes.planes[0].rowStride);
        out.pixelStrideBytes = sourceFormat == RAW_SENSOR_FORMAT
                ? std::max(2u, static_cast<std::uint32_t>(planes.planes[0].pixelStride)) : 0u;
        if (out.rowStrideBytes == 0u) {
            out.rowStrideBytes = sourceFormat == RAW10_FORMAT
                    ? packedRaw10RowBytes(std::max(desc.width, desc.stride))
                    : std::max(desc.width, desc.stride) * out.pixelStrideBytes;
        }
        out.locked = true;
        return true;
    }

    void* address = nullptr;
    const int status = AHardwareBuffer_lock(
            buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &address);
    if (status != 0 || address == nullptr) return false;
    out.data = static_cast<const std::uint8_t*>(address);
    out.pixelStrideBytes = sourceFormat == RAW_SENSOR_FORMAT ? 2u : 0u;
    out.rowStrideBytes = sourceFormat == RAW10_FORMAT
            ? packedRaw10RowBytes(std::max(desc.width, desc.stride))
            : std::max(desc.width, desc.stride) * out.pixelStrideBytes;
    out.locked = true;
    return true;
}

std::uint16_t readRaw10(const LockedRawBuffer& raw, std::uint32_t x, std::uint32_t y) {
    const std::uint8_t* row = raw.data + static_cast<std::size_t>(y) * raw.rowStrideBytes;
    const std::uint32_t block = (x / 4u) * 5u;
    if (block + 4u >= raw.rowStrideBytes) return 0u;
    const std::uint32_t lane = x & 3u;
    const std::uint32_t lowShift = lane * 2u;
    return static_cast<std::uint16_t>(
            (static_cast<std::uint16_t>(row[block + lane]) << 2u) |
            ((row[block + 4u] >> lowShift) & 0x03u));
}

std::uint16_t readRawSensor(const LockedRawBuffer& raw, std::uint32_t x, std::uint32_t y) {
    const std::uint8_t* address = raw.data + static_cast<std::size_t>(y) * raw.rowStrideBytes +
            static_cast<std::size_t>(x) * raw.pixelStrideBytes;
    return static_cast<std::uint16_t>(address[0] | (static_cast<std::uint16_t>(address[1]) << 8u));
}

#endif

float evalCurve(const std::vector<float>& curve, float value) {
    if (curve.size() <= 1u) return std::clamp(value, 0.0f, 1.0f);
    const float position = std::clamp(value, 0.0f, 1.0f) * static_cast<float>(curve.size() - 1u);
    const int first = std::clamp(static_cast<int>(std::floor(position)), 0,
                                 static_cast<int>(curve.size()) - 1);
    const int second = std::min(first + 1, static_cast<int>(curve.size()) - 1);
    const float fraction = position - static_cast<float>(first);
    return std::clamp(curve[static_cast<std::size_t>(first)] * (1.0f - fraction) +
                      curve[static_cast<std::size_t>(second)] * fraction, 0.0f, 1.0f);
}

#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
const std::array<std::uint8_t, 4096>& previewSrgb8Lut() {
    static const std::array<std::uint8_t, 4096> lut = [] {
        std::array<std::uint8_t, 4096> values{};
        for (std::size_t index = 0; index < values.size(); ++index) {
            const float linear = static_cast<float>(index) /
                    static_cast<float>(values.size() - 1u);
            const float encoded = linear <= 0.0031308f
                    ? 12.92f * linear
                    : 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
            values[index] = static_cast<std::uint8_t>(std::lround(encoded * 255.0f));
        }
        return values;
    }();
    return lut;
}

inline std::uint8_t quantizePreviewSrgb(float value) {
    const float bounded = std::clamp(value, 0.0f, 1.0f);
    const int index = static_cast<int>(bounded * 4095.0f + 0.5f);
    return previewSrgb8Lut()[static_cast<std::size_t>(index)];
}

cv::Mat cpuDemosaicFallback(
        const cv::Mat& normalizedMosaic,
        int cfaPattern,
        int requestedDemosaicMode
) {
    // Bridge values: 1 Malvar Inspired, 2 AMAZE Inspired, 3 RCD Inspired. Auto is resolved in
    // Kotlin before the live-preview request reaches native; an unexpected 0 therefore uses the
    // BnCam default (RCD Inspired) rather than resurrecting retired Bilinear behavior.
    switch (requestedDemosaicMode) {
        case 1:
            return demosaicMalvar2004ToRgb32f(normalizedMosaic, cfaPattern);
        case 2:
            return demosaicAmazeInspiredToRgb32f(normalizedMosaic, cfaPattern);
        case 3:
        default:
            return demosaicRcdInspiredToRgb32f(normalizedMosaic, cfaPattern);
    }
}

void applyCpuAwbCcm(cv::Mat& rgb, const NativeRenderQualityConfig& quality) {
    const float green = std::max(1.0e-4f, 0.5f * (quality.wbGreenEven + quality.wbGreenOdd));
    const float wb[3] = {quality.wbRed / green, 1.0f, quality.wbBlue / green};
    cv::parallel_for_(cv::Range(0, rgb.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3f* row = rgb.ptr<cv::Vec3f>(y);
            for (int x = 0; x < rgb.cols; ++x) {
                const cv::Vec3f value = row[x];
                const float r = value[0] * wb[0];
                const float g = value[1] * wb[1];
                const float b = value[2] * wb[2];
                row[x] = cv::Vec3f(
                        std::max(0.0f, quality.colorMatrix[0] * r + quality.colorMatrix[1] * g + quality.colorMatrix[2] * b),
                        std::max(0.0f, quality.colorMatrix[3] * r + quality.colorMatrix[4] * g + quality.colorMatrix[5] * b),
                        std::max(0.0f, quality.colorMatrix[6] * r + quality.colorMatrix[7] * g + quality.colorMatrix[8] * b));
            }
        }
    });
}

#endif

#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
struct PreviewExposureResult {
    float targetGain = 1.0f;
    float midtone = 0.0f;
};

float sortedPercentile(const std::vector<float>& sorted, float fraction) {
    if (sorted.empty()) return 0.0f;
    const std::size_t index = std::min(
            sorted.size() - 1u,
            static_cast<std::size_t>(std::floor(
                    static_cast<double>(sorted.size() - 1u) * fraction)));
    return sorted[index];
}

PreviewExposureResult resolvePreviewExposure(
        const cv::Mat& rgb,
        const RawPreviewParameters& parameters
) {
    PreviewExposureResult result{};
    result.targetGain = std::clamp(parameters.quality.exposureGain, 0.125f, 8.0f);
    if (rgb.empty() || rgb.type() != CV_32FC3) return result;

    constexpr std::size_t MAX_SAMPLES = 4096u;
    const std::size_t pixelCount = rgb.total();
    const std::size_t sampleStep = std::max<std::size_t>(1u, pixelCount / MAX_SAMPLES);
    std::vector<float> lumaSamples;
    lumaSamples.reserve(std::min(pixelCount, MAX_SAMPLES + 1u));
    for (std::size_t index = 0u; index < pixelCount; index += sampleStep) {
        const int y = static_cast<int>(index / static_cast<std::size_t>(rgb.cols));
        const int x = static_cast<int>(index % static_cast<std::size_t>(rgb.cols));
        const cv::Vec3f value = rgb.at<cv::Vec3f>(y, x);
        const float luma = 0.2126f * value[0] + 0.7152f * value[1] + 0.0722f * value[2];
        if (std::isfinite(luma)) lumaSamples.push_back(std::max(0.0f, luma));
    }
    if (lumaSamples.size() < 16u) return result;
    std::sort(lumaSamples.begin(), lumaSamples.end());
    const float p35 = sortedPercentile(lumaSamples, 0.35f);
    const float p50 = sortedPercentile(lumaSamples, 0.50f);
    const float p60 = sortedPercentile(lumaSamples, 0.60f);
    const float p75 = sortedPercentile(lumaSamples, 0.75f);
    const float p95 = sortedPercentile(lumaSamples, 0.95f);
    result.midtone = std::max(0.001f, 0.10f * p35 + 0.20f * p50 + 0.30f * p60 + 0.40f * p75);

    // This is the capture RAW exposure governor's lightweight viewfinder branch: identical
    // midtone statistic, low-light target and format-specific cap, without capture-only scene
    // classification or any change to sensor exposure/RAW data.
    const float exposureMs = parameters.captureExposureTimeNs > 0
            ? static_cast<float>(parameters.captureExposureTimeNs) / 1.0e6f : 0.0f;
    const int iso = std::max(1, parameters.captureSensitivityIso);
    const bool exposureKnown = parameters.captureExposureTimeNs > 0;
    const bool lowLight = (exposureKnown && iso >= 300 && exposureMs >= 20.0f) ||
            iso >= 800 || (p50 < 0.08f && p95 < 0.25f && iso >= 200);
    const float targetMidtone = lowLight ? 0.090f : 0.100f;
    const bool raw10 = parameters.sourceFormat == RAW10_FORMAT;
    const float maxGain = lowLight ? (raw10 ? 1.90f : 2.10f) : (raw10 ? 2.30f : 2.50f);
    result.targetGain = std::clamp(targetMidtone / result.midtone, 1.0f, maxGain);
    return result;
}


#endif

std::array<float, 4096> buildCaptureToneLut(const NativeRenderQualityConfig& quality) {
    constexpr int lutSize = 4096;
    std::array<float, lutSize> toneLut{};
    // Scene-adaptive + user black anchoring is now resolved in the Vulkan GTM pass. Keeping a
    // second fixed 0.010 anchor in this LUT crushed RAW-preview shadows and double-applied the
    // profile control. This LUT owns the remaining user tone/curve operations only.
    const float blackAnchor = 0.0f;
    const auto profileTonePlan = bncam::tone::resolveProfileToneRenderPlan({
            quality.profileToneExposure, quality.profileToneHighlights, quality.profileToneShadows,
            quality.profileToneWhites, quality.profileToneBlacks, quality.profileToneContrast,
            quality.profileLocalToneBias});
    for (int index = 0; index < lutSize; ++index) {
        const float input = static_cast<float>(index) / static_cast<float>(lutSize - 1);
        const float anchored = std::max(0.0f, (input - blackAnchor) / (1.0f - blackAnchor));
        float curved = bncam::tone::applyProfileTonalRanges(anchored, profileTonePlan);
        curved = evalCurve(quality.toneCurve, curved);
        curved = evalCurve(quality.sectionCurve, curved);
        curved = evalCurve(quality.gammaCurve, curved);
        toneLut[static_cast<std::size_t>(index)] = curved;
    }
    return toneLut;
}

#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
void toneAndPackPreview(
        const cv::Mat& rgb,
        const NativeRenderQualityConfig& quality,
        float exposureGain,
        std::uint8_t* outputRgba,
        RawPreviewResult& result
) {
    constexpr int lutSize = 4096;
    const auto toneLut = buildCaptureToneLut(quality);
    const bool profileColorActive =
            std::abs(quality.profileColorSaturation) >= 1.0e-4f ||
            std::abs(quality.profileColorContrast) >= 1.0e-4f ||
            std::abs(quality.profilePresenceVibrance) >= 1.0e-4f ||
            std::abs(quality.profileColorRecovery) >= 1.0e-4f;
    std::atomic<int> rgbMinimum{255};
    std::atomic<int> rgbMaximum{0};
    std::atomic<std::uint64_t> rgbSum{0u};
    std::atomic<std::uint64_t> rgbSampleCount{0u};
    std::atomic<std::uint64_t> commonScalePixels{0u};
    cv::parallel_for_(cv::Range(0, rgb.rows), [&](const cv::Range& range) {
        int localMinimum = 255;
        int localMaximum = 0;
        std::uint64_t localSum = 0u;
        std::uint64_t localSampleCount = 0u;
        std::uint64_t localCommonScalePixels = 0u;
        for (int y = range.start; y < range.end; ++y) {
            const cv::Vec3f* row = rgb.ptr<cv::Vec3f>(y);
            std::uint8_t* destination = outputRgba + static_cast<std::size_t>(y) * rgb.cols * 4u;
            for (int x = 0; x < rgb.cols; ++x) {
                float r = std::max(0.0f, row[x][0] * exposureGain);
                float g = std::max(0.0f, row[x][1] * exposureGain);
                float b = std::max(0.0f, row[x][2] * exposureGain);
                float luma = std::max(1.0e-6f, 0.2126f * r + 0.7152f * g + 0.0722f * b);
                constexpr float shoulderStart = 0.68f;
                if (luma > shoulderStart) {
                    constexpr float headroom = 1.0f - shoulderStart;
                    const float mapped = shoulderStart + headroom * (luma - shoulderStart) /
                            ((luma - shoulderStart) + headroom);
                    const float scale = mapped / luma;
                    r *= scale; g *= scale; b *= scale; luma = mapped;
                }
                // Preserve WB/CCM channel ratios when a RAW_SENSOR highlight exceeds display
                // gamut. Independent final clamping was the source of magenta highlights.
                const float maximumChannel = std::max(r, std::max(g, b));
                if (maximumChannel > 1.0f) {
                    const float commonScale = 1.0f / maximumChannel;
                    r *= commonScale;
                    g *= commonScale;
                    b *= commonScale;
                    luma *= commonScale;
                    ++localCommonScalePixels;
                }
                const float position = std::clamp(luma * static_cast<float>(lutSize - 1),
                                                  0.0f, static_cast<float>(lutSize - 1));
                const int first = static_cast<int>(position);
                const int second = std::min(lutSize - 1, first + 1);
                const float mapped = toneLut[static_cast<std::size_t>(first)] * (1.0f - (position - first)) +
                        toneLut[static_cast<std::size_t>(second)] * (position - first);
                const float scale = mapped / std::max(luma, 1.0e-6f);
                r *= scale; g *= scale; b *= scale;
                if (profileColorActive) applyBncamProfileColorManagement(r, g, b, quality);
                const std::uint8_t output[3] = {
                        quantizePreviewSrgb(r),
                        quantizePreviewSrgb(g),
                        quantizePreviewSrgb(b)};
                for (int channel = 0; channel < 3; ++channel) {
                    const int value = output[channel];
                    destination[x * 4 + channel] = static_cast<std::uint8_t>(value);
                    localMinimum = std::min(localMinimum, value);
                    localMaximum = std::max(localMaximum, value);
                    localSum += static_cast<std::uint64_t>(value);
                    ++localSampleCount;
                }
                destination[x * 4 + 3] = 255u;
            }
        }
        int observedMinimum = rgbMinimum.load(std::memory_order_relaxed);
        while (localMinimum < observedMinimum && !rgbMinimum.compare_exchange_weak(
                observedMinimum, localMinimum, std::memory_order_relaxed)) {}
        int observedMaximum = rgbMaximum.load(std::memory_order_relaxed);
        while (localMaximum > observedMaximum && !rgbMaximum.compare_exchange_weak(
                observedMaximum, localMaximum, std::memory_order_relaxed)) {}
        rgbSum.fetch_add(localSum, std::memory_order_relaxed);
        rgbSampleCount.fetch_add(localSampleCount, std::memory_order_relaxed);
        commonScalePixels.fetch_add(localCommonScalePixels, std::memory_order_relaxed);
    });
    const std::uint64_t finalSampleCount = rgbSampleCount.load(std::memory_order_relaxed);
    result.commonHighlightScalePixels = commonScalePixels.load(std::memory_order_relaxed);
    result.outputRgbMin = static_cast<float>(rgbMinimum.load(std::memory_order_relaxed)) / 255.0f;
    result.outputRgbMax = static_cast<float>(rgbMaximum.load(std::memory_order_relaxed)) / 255.0f;
    result.outputRgbMean = finalSampleCount > 0u
            ? static_cast<float>(rgbSum.load(std::memory_order_relaxed)) /
                    static_cast<float>(finalSampleCount * 255u) : 0.0f;
}
#endif

}  // namespace

RawPreviewResult renderRawPreviewRgba(
        AHardwareBuffer* buffer,
        const RawPreviewParameters& parameters,
        AHardwareBuffer* outputHardwareBuffer,
        std::uint8_t* outputRgba,
        std::size_t outputCapacityBytes,
        std::uint8_t* analysisNv21,
        std::size_t analysisNv21CapacityBytes
) {
    const auto started = std::chrono::steady_clock::now();
    RawPreviewResult result{};
    if (buffer == nullptr || (outputHardwareBuffer == nullptr && outputRgba == nullptr)) {
        logPreviewFailure("invalid_input");
        return result;
    }

    AHardwareBuffer_acquire(buffer);
    struct ReleaseNativeOnExit {
        AHardwareBuffer* buf;
        ~ReleaseNativeOnExit() { if (buf != nullptr) AHardwareBuffer_release(buf); }
    } releaseNativeOnExit{buffer};

    AHardwareBuffer_Desc inputDesc{};
    AHardwareBuffer_describe(buffer, &inputDesc);
    const int fullWidth = parameters.sourceWidth > 0
            ? parameters.sourceWidth : static_cast<int>(inputDesc.width);
    const int fullHeight = parameters.sourceHeight > 0
            ? parameters.sourceHeight : static_cast<int>(inputDesc.height);
    int sourceRowStrideBytes = parameters.sourceRowStrideBytes;
    if (sourceRowStrideBytes <= 0) {
        if (inputDesc.stride > 0) {
            if (parameters.sourceFormat == RAW10_FORMAT) {
                sourceRowStrideBytes = static_cast<int>(inputDesc.stride * 5 / 4);
            } else {
                sourceRowStrideBytes = static_cast<int>(inputDesc.stride * 2);
            }
        } else {
            if (parameters.sourceFormat == RAW10_FORMAT) {
                sourceRowStrideBytes = (fullWidth * 5 / 4 + 63) & ~63;
            } else {
                sourceRowStrideBytes = fullWidth * 2;
            }
        }
    }
    const int sourcePixelStrideBytes = parameters.sourcePixelStrideBytes;
    if (fullWidth <= 1 || fullHeight <= 1 || sourceRowStrideBytes <= 0) {
        logPreviewFailure("raw_source_layout_unavailable");
        return result;
    }

    const int boundedMaxWidth = std::clamp(parameters.maxWidth, 64, PREVIEW_HARD_MAX_WIDTH);
    const int boundedMaxHeight = std::clamp(parameters.maxHeight, 64, PREVIEW_HARD_MAX_HEIGHT);
    int cropLeft = std::clamp(parameters.sourceCropLeft, 0, std::max(0, fullWidth - 2));
    int cropTop = std::clamp(parameters.sourceCropTop, 0, std::max(0, fullHeight - 2));
    int cropWidth = parameters.sourceCropWidth > 0
            ? std::clamp(parameters.sourceCropWidth, 2, fullWidth - cropLeft)
            : fullWidth - cropLeft;
    int cropHeight = parameters.sourceCropHeight > 0
            ? std::clamp(parameters.sourceCropHeight, 2, fullHeight - cropTop)
            : fullHeight - cropTop;
    // Keep an even local Bayer domain. If Camera2 reports an odd active size we discard only the
    // final edge sample, never shift the crop origin or invent pixels.
    cropWidth = std::max(2, cropWidth & ~1);
    cropHeight = std::max(2, cropHeight & ~1);

    const float widthRatio = static_cast<float>(cropWidth) / boundedMaxWidth;
    const float heightRatio = static_cast<float>(cropHeight) / boundedMaxHeight;
    const int cellDecimation = std::max(1, static_cast<int>(std::ceil(std::max(widthRatio, heightRatio))));
    const int previewWidth = std::max(2, 2 * static_cast<int>(cropWidth / (2 * cellDecimation)));
    const int previewHeight = std::max(2, 2 * static_cast<int>(cropHeight / (2 * cellDecimation)));
    result.cfaCellDecimation = cellDecimation;

    const float safeWhite = static_cast<float>(std::max(1, parameters.whiteLevel));
    const std::size_t previewPixelCount = static_cast<std::size_t>(previewWidth) * previewHeight;
    const std::size_t requiredPreviewBytes = previewPixelCount * 4u;
    if (outputHardwareBuffer == nullptr && requiredPreviewBytes > outputCapacityBytes) {
        logPreviewFailure("rgba_output_capacity_too_small");
        return result;
    }

    // The live path submits the retained camera RAW AHardwareBuffer to Vulkan first. When the
    // actual buffer contract cannot be imported directly, the backend performs one byte-staging
    // copy; RAW10 unpack/RAW_SENSOR normalization, exposure analysis, demosaic, colour, tone and
    // RGBA production still remain in the Vulkan command chain. Heavy CPU development is confined
    // to the explicit compile-time reference branch below.
    const auto toneLut = buildCaptureToneLut(parameters.quality);
    bncam::vulkan::RawPreviewGpuRequest previewRequest{};
    previewRequest.inputHardwareBuffer = buffer;
    previewRequest.rawData = nullptr;
    previewRequest.sourceWidth = static_cast<std::uint32_t>(fullWidth);
    previewRequest.sourceHeight = static_cast<std::uint32_t>(fullHeight);
    previewRequest.sourceCropLeft = static_cast<std::uint32_t>(cropLeft);
    previewRequest.sourceCropTop = static_cast<std::uint32_t>(cropTop);
    previewRequest.sourceCropWidth = static_cast<std::uint32_t>(cropWidth);
    previewRequest.sourceCropHeight = static_cast<std::uint32_t>(cropHeight);
    previewRequest.sourceRowStrideBytes = static_cast<std::uint32_t>(sourceRowStrideBytes);
    previewRequest.sourcePixelStrideBytes = static_cast<std::uint32_t>(std::max(0, sourcePixelStrideBytes));
    previewRequest.sourceFormat = static_cast<std::uint32_t>(parameters.sourceFormat);
    previewRequest.previewWidth = static_cast<std::uint32_t>(previewWidth);
    previewRequest.previewHeight = static_cast<std::uint32_t>(previewHeight);
    previewRequest.cfaCellDecimation = static_cast<std::uint32_t>(cellDecimation);
    previewRequest.cfaPattern = static_cast<std::uint32_t>(std::clamp(parameters.cfaPattern, 0, 3));
    previewRequest.demosaicMode = static_cast<std::uint32_t>(std::clamp(parameters.demosaicMode, 0, 3));
    std::copy(std::begin(parameters.blackLevels), std::end(parameters.blackLevels),
              previewRequest.blackLevels.begin());
    previewRequest.whiteLevel = safeWhite;
    previewRequest.captureSensitivityIso = static_cast<std::uint32_t>(
            std::max(1, parameters.captureSensitivityIso));
    previewRequest.captureExposureTimeMs = parameters.captureExposureTimeNs > 0
            ? static_cast<float>(parameters.captureExposureTimeNs) / 1.0e6f : 0.0f;
    previewRequest.physicalGreenNoiseS = std::max(0.0f, parameters.physicalGreenNoiseS);
    previewRequest.physicalGreenNoiseO = std::max(0.0f, parameters.physicalGreenNoiseO);
    previewRequest.physicalNoiseConfidence = std::clamp(parameters.physicalNoiseConfidence, 0.0f, 1.0f);
    previewRequest.focusDetailPriority = std::clamp(parameters.focusDetailPriority, 0.0f, 1.0f);
    const float safeGreenEven = std::max(1.0e-4f, parameters.quality.wbGreenEven);
    const float safeGreenOdd = std::max(1.0e-4f, parameters.quality.wbGreenOdd);
    const float previewGreen = std::max(1.0e-4f, 0.5f * (safeGreenEven + safeGreenOdd));
    previewRequest.greenEvenOddRatio = std::clamp(safeGreenEven / safeGreenOdd, 0.50f, 2.0f);
    previewRequest.wbRgb = {
            parameters.quality.wbRed / previewGreen,
            1.0f,
            parameters.quality.wbBlue / previewGreen};

    // RAW preview consumes the same physical DNG camera characterization as capture. Resolve the
    // registered profile against the unmodified effective Camera2 CCM + current physical WB, then
    // replace only the matrix transport with the paired DNG ForwardMatrix result. A genuine
    // ProfileHueSatMap is optional augmentation of that same owner; no synthetic preview LUT is
    // created when the profile is matrix-only.
    std::array<float, 9> previewEffectiveCcm{};
    std::copy(std::begin(parameters.quality.colorMatrix),
              std::end(parameters.quality.colorMatrix), previewEffectiveCcm.begin());
    const auto previewProfileRegistry =
            bncam::color::RawCameraColorProfileRegistry::instance().snapshot();
    const auto previewProfileResolution = bncam::color::resolveRawCameraColorProfile(
            previewProfileRegistry, {previewEffectiveCcm, previewRequest.wbRgb});
    const bncam::color::RawCameraNativeHueSatProfile* previewProfile = nullptr;
    if (previewProfileResolution.profileIndex < previewProfileRegistry.profiles.size()) {
        previewProfile = &previewProfileRegistry.profiles[previewProfileResolution.profileIndex];
    }
    const bool previewProfileConfiguredForCurrentRoute = previewProfile != nullptr;
    bncam::color::RawCameraDngForwardTransformResult previewForwardTransform{};
    if (previewProfileConfiguredForCurrentRoute && previewProfileResolution.ready) {
        previewForwardTransform = bncam::color::resolveRawCameraDngForwardTransform({
                previewProfile,
                previewProfileResolution.hueSatWeightFirst,
                previewProfileResolution.hueSatWeightSecond,
                previewRequest.wbRgb});
    } else {
        previewForwardTransform.status = previewProfileConfiguredForCurrentRoute
                ? "PROFILE_RESOLUTION_NOT_READY"
                : "NO_MATCHING_PROFILE_FOR_CURRENT_ROUTE";
    }
    const bool previewHueSatMapAvailable = previewProfile != nullptr && previewProfile->hasHueSatMap();
    const auto previewCharacterizationPlan =
            bncam::color::resolveRawCameraColorCharacterizationOwnership({
                    previewProfileConfiguredForCurrentRoute,
                    previewProfileResolution.ready,
                    previewProfile != nullptr && previewProfile->valid(),
                    previewForwardTransform.ready,
                    previewHueSatMapAvailable,
                    previewHueSatMapAvailable && previewProfile != nullptr && previewProfile->valid(),
                    true});
    const bool previewCalibratedMatrixActive = previewCharacterizationPlan.calibratedMatrixApply;
    const bool previewCalibratedHueSatMapActive = previewCharacterizationPlan.calibratedHueSatMapApply;
    previewRequest.colorMatrix = previewCalibratedMatrixActive
            ? previewForwardTransform.postWbToLinearSrgb
            : previewEffectiveCcm;
    previewRequest.calibratedHueSatMapEnabled = previewCalibratedHueSatMapActive;
    if (previewCalibratedHueSatMapActive && previewProfile != nullptr) {
        previewRequest.hueSatHueDivisions = static_cast<std::uint32_t>(
                std::max(0, previewProfile->hueDivisions));
        previewRequest.hueSatSaturationDivisions = static_cast<std::uint32_t>(
                std::max(0, previewProfile->saturationDivisions));
        previewRequest.hueSatValueDivisions = static_cast<std::uint32_t>(
                std::max(0, previewProfile->valueDivisions));
        previewRequest.hueSatEncoding = static_cast<std::uint32_t>(
                std::clamp(previewProfile->encoding, 0, 1));
        previewRequest.hueSatData1 = previewProfile->hueSatData1.data();
        previewRequest.hueSatData1FloatCount = previewProfile->hueSatData1.size();
        if (previewProfile->dualHueSatMap()) {
            previewRequest.hueSatData2 = previewProfile->hueSatData2.data();
            previewRequest.hueSatData2FloatCount = previewProfile->hueSatData2.size();
        }
        previewRequest.hueSatWeightFirst = previewProfileResolution.hueSatWeightFirst;
        previewRequest.hueSatWeightSecond = previewProfileResolution.hueSatWeightSecond;
    }
    previewRequest.profileSaturation = parameters.quality.profileColorSaturation;
    previewRequest.profileContrast = parameters.quality.profileColorContrast;
    previewRequest.profileVibrance = parameters.quality.profilePresenceVibrance;
    previewRequest.profilePop = parameters.quality.profilePresencePop;
    previewRequest.profileColorRecovery = parameters.quality.profileColorRecovery;
    previewRequest.profileToneExposure = parameters.quality.profileToneExposure;
    previewRequest.profileToneHighlights = parameters.quality.profileToneHighlights;
    previewRequest.profileToneShadows = parameters.quality.profileToneShadows;
    previewRequest.profileToneWhites = parameters.quality.profileToneWhites;
    previewRequest.profileToneBlacks = parameters.quality.profileToneBlacks;
    previewRequest.profileToneContrast = parameters.quality.profileToneContrast;
    previewRequest.profileLocalToneBias = parameters.quality.profileLocalToneBias;
    previewRequest.profileDetailAmount = parameters.quality.profileDetailAmount;
    previewRequest.profileDetailRadius = parameters.quality.profileDetailRadius;
    previewRequest.profileDetailDetail = parameters.quality.profileDetailDetail;
    previewRequest.profileDetailMasking = parameters.quality.profileDetailMasking;
    previewRequest.toneLut = toneLut.data();
    previewRequest.toneLutSize = static_cast<std::uint32_t>(toneLut.size());
    previewRequest.outputHardwareBuffer = outputHardwareBuffer;
    previewRequest.outputRgba = outputRgba;
    previewRequest.pollOnly = parameters.frameSlotIndex < 0;
    previewRequest.sensorTimestampNs = parameters.sensorTimestampNs;
    previewRequest.pipelineGeneration = parameters.pipelineGeneration;
    previewRequest.lensShadingMap = parameters.lensShadingMap.empty()
            ? nullptr : parameters.lensShadingMap.data();
    previewRequest.lensShadingColumns = static_cast<std::uint32_t>(parameters.lensShadingColumns);
    previewRequest.lensShadingRows = static_cast<std::uint32_t>(parameters.lensShadingRows);
    for (int i = 0; i < 4; ++i) previewRequest.lensShadingActiveRect[i] = parameters.lensShadingActiveRect[i];
    previewRequest.frameSlotIndex = static_cast<std::uint32_t>(previewRequest.pollOnly
            ? std::clamp(-parameters.frameSlotIndex - 1, 0, 2)
            : std::clamp(parameters.frameSlotIndex, 0, 2));
    previewRequest.outputCapacityBytes = outputCapacityBytes;
    previewRequest.analysisNv21 = analysisNv21;
    previewRequest.analysisNv21CapacityBytes = analysisNv21CapacityBytes;
    auto& runtime = bncam::vulkan::VulkanRuntime::instance();
    const auto previewGpu = runtime.executeRawPreview(previewRequest);
    if (previewGpu.gpuPending) {
        result.gpuPending = true;
        return result;
    }
    if (!previewGpu.success && (
            previewGpu.droppedBusy ||
            previewGpu.failureReason == "GPU_SLOT_BUSY_DROPPED" ||
            previewGpu.failureReason == "GPU_PREVIEW_COMPLETION_TIMEOUT_DROPPED")) {
        logPreviewFailure(previewGpu.failureReason.c_str());
        return result;
    }
    if (previewGpu.success) {
        std::vector<bncam::awb::LinearOpponentSample> awbSamples;
        awbSamples.reserve(previewGpu.awbSampleCount);
        for (const auto& sample : previewGpu.awbSamples) {
            if (!sample.valid) continue;
            awbSamples.push_back({
                    static_cast<double>(sample.luma),
                    static_cast<double>(sample.redMinusGreen),
                    static_cast<double>(sample.blueMinusGreen),
                    static_cast<double>(sample.structure),
                    static_cast<int>(sample.tileIndex)});
        }
        const float priorGreen = std::max(1.0e-4f, 0.5f *
                (parameters.camera2PriorWbGains[1] + parameters.camera2PriorWbGains[2]));
        const std::array<double, 3> camera2PriorRgb{
                static_cast<double>(parameters.camera2PriorWbGains[0] / priorGreen),
                1.0,
                static_cast<double>(parameters.camera2PriorWbGains[3] / priorGreen)};
        // No calibrated preview-domain sigma is available at this boundary yet. Pass zero rather
        // than pretending an ISO heuristic is sensor calibration; the estimator then uses its
        // conservative absolute dark floor. Cross-device noise-model coupling remains a later phase.
        const auto awbEstimate = bncam::awb::resolve(awbSamples, camera2PriorRgb, 0.0);
        for (int channel = 0; channel < 3; ++channel) {
            result.awbPriorGainsRgb[channel] = static_cast<float>(awbEstimate.priorGainsRgb[channel]);
            result.awbDataGainsRgb[channel] = static_cast<float>(awbEstimate.dataGainsRgb[channel]);
            result.awbFinalGainsRgb[channel] = static_cast<float>(awbEstimate.finalGainsRgb[channel]);
        }
        result.awbConfidence = static_cast<float>(awbEstimate.confidence);
        result.awbDataAuthority = static_cast<float>(awbEstimate.dataAuthority);
        result.awbNeutralSupport = static_cast<float>(awbEstimate.neutralSupport);
        result.awbMixedLightScore = static_cast<float>(awbEstimate.mixedLightScore);
        result.awbPriorDisagreement = static_cast<float>(awbEstimate.priorDisagreement);
        result.awbValidTileCount = static_cast<int>(awbEstimate.validTileCount);
        result.awbAcceptedSampleCount = static_cast<int>(awbEstimate.acceptedSampleCount);
        result.awbDataReady = awbEstimate.dataReady;

        result.normalizedRawMin = previewGpu.normalizedRawMin;
        result.normalizedRawMax = previewGpu.normalizedRawMax;
        result.targetExposureGain = previewGpu.exposureGain;
        result.appliedExposureGain = previewGpu.exposureGain;
        result.sceneMidtone = previewGpu.sceneMidtone;
        result.sceneMidtoneTarget = previewGpu.sceneMidtoneTarget;
        result.gtmShoulderStart = previewGpu.gtmShoulderStart;
        result.gtmShoulderStrength = previewGpu.gtmShoulderStrength;
        result.gtmBlackAnchor = previewGpu.gtmBlackAnchor;
        result.gtmLowerMidLift = previewGpu.gtmLowerMidLift;
        result.gtmContrastStrength = previewGpu.gtmContrastStrength;
        result.gtmDynamicRangePressure = previewGpu.gtmDynamicRangePressure;
        result.ltmStrength = previewGpu.ltmStrength;
        result.ltmMaxLiftEv = previewGpu.ltmMaxLiftEv;
        result.ltmMaxCompressEv = previewGpu.ltmMaxCompressEv;
        result.commonHighlightScalePixels = previewGpu.commonHighlightScalePixels;
        std::copy(previewGpu.linearLumaHistogram.begin(), previewGpu.linearLumaHistogram.end(),
                  std::begin(result.linearLumaHistogram));
        std::copy(previewGpu.displayLumaHistogram.begin(), previewGpu.displayLumaHistogram.end(),
                  std::begin(result.displayLumaHistogram));
        std::copy(previewGpu.displayLumaHistogram64.begin(), previewGpu.displayLumaHistogram64.end(),
                  std::begin(result.displayLumaHistogram64));
        std::copy(previewGpu.displayRHistogram64.begin(), previewGpu.displayRHistogram64.end(),
                  std::begin(result.displayRHistogram64));
        std::copy(previewGpu.displayGHistogram64.begin(), previewGpu.displayGHistogram64.end(),
                  std::begin(result.displayGHistogram64));
        std::copy(previewGpu.displayBHistogram64.begin(), previewGpu.displayBHistogram64.end(),
                  std::begin(result.displayBHistogram64));
        result.rawNearClipSampleCount = previewGpu.rawNearClipSampleCount;
        result.rawSampleCount = previewGpu.rawSampleCount;
        result.displayRClipSampleCount = previewGpu.displayRClipSampleCount;
        result.displayGClipSampleCount = previewGpu.displayGClipSampleCount;
        result.displayBClipSampleCount = previewGpu.displayBClipSampleCount;
        result.displayShadowSampleCount = previewGpu.displayShadowSampleCount;
        result.displayHighlightSampleCount = previewGpu.displayHighlightSampleCount;
        result.displaySampleCount = previewGpu.displaySampleCount;
        result.displayHighlightX = previewGpu.displayHighlightX;
        result.displayHighlightY = previewGpu.displayHighlightY;
        result.exposureTileCount = previewGpu.exposureTileCount;
        result.exposureSceneP10 = previewGpu.exposureSceneP10;
        result.exposureSceneP25 = previewGpu.exposureSceneP25;
        result.exposureSceneP50 = previewGpu.exposureSceneP50;
        result.exposureSceneP75 = previewGpu.exposureSceneP75;
        result.exposureSceneP90 = previewGpu.exposureSceneP90;
        result.exposureSceneP95 = previewGpu.exposureSceneP95;
        result.exposureSceneP99 = previewGpu.exposureSceneP99;
        result.exposureMeasuredSceneDrEv = previewGpu.exposureMeasuredSceneDrEv;
        result.exposureLowerNeutralBoundaryEv = previewGpu.exposureLowerNeutralBoundaryEv;
        result.exposureUpperNeutralBoundaryEv = previewGpu.exposureUpperNeutralBoundaryEv;
        result.exposureSpatialAuthority = previewGpu.exposureSpatialAuthority;
        result.analysisNv21Width = static_cast<int>(previewGpu.analysisNv21Width);
        result.analysisNv21Height = static_cast<int>(previewGpu.analysisNv21Height);
        result.rawUnpackMicroseconds = static_cast<int>(previewGpu.inputPackingMs * 1000.0f);
        result.demosaicMicroseconds = static_cast<int>(previewGpu.kernelMs * 1000.0f);
        result.colorMicroseconds = static_cast<int>(
                std::max(0.0f, previewGpu.synchronizationMs - previewGpu.kernelMs) * 1000.0f);
        result.tonePackMicroseconds = static_cast<int>(
                previewGpu.readbackMs * 1000.0f);
        result.backendMutexWaitMicroseconds = static_cast<int>(std::lround(previewGpu.backendMutexWaitMs * 1000.0f));
        result.backendInitializationPerformed = previewGpu.backendInitializationPerformed;
        result.backendInitializationMicroseconds = static_cast<int>(std::lround(previewGpu.backendInitializationMs * 1000.0f));
        result.spirvLookupMicroseconds = static_cast<int>(std::lround(previewGpu.spirvLookupMs * 1000.0f));
        result.descriptorLayoutMicroseconds = static_cast<int>(std::lround(previewGpu.descriptorLayoutMs * 1000.0f));
        result.pipelineLayoutMicroseconds = static_cast<int>(std::lround(previewGpu.pipelineLayoutMs * 1000.0f));
        result.shaderModuleMicroseconds = static_cast<int>(std::lround(previewGpu.shaderModuleMs * 1000.0f));
        result.pipelineCacheMutexWaitMicroseconds = static_cast<int>(std::lround(previewGpu.pipelineCacheMutexWaitMs * 1000.0f));
        result.pipelineCachePresent = previewGpu.pipelineCachePresent;
        result.computePipelineMicroseconds = static_cast<int>(std::lround(previewGpu.computePipelineMs * 1000.0f));
        result.imageSpirvLookupMicroseconds = static_cast<int>(std::lround(previewGpu.imageSpirvLookupMs * 1000.0f));
        result.imageShaderModuleMicroseconds = static_cast<int>(std::lround(previewGpu.imageShaderModuleMs * 1000.0f));
        result.imageComputePipelineMicroseconds = static_cast<int>(std::lround(previewGpu.imageComputePipelineMs * 1000.0f));
        result.descriptorCommandResourcesMicroseconds = static_cast<int>(std::lround(previewGpu.descriptorCommandResourcesMs * 1000.0f));
        result.inputAhbProbeMicroseconds = static_cast<int>(std::lround(previewGpu.inputAhbProbeMs * 1000.0f));
        result.outputAhbImportMicroseconds = static_cast<int>(std::lround(previewGpu.outputAhbImportMs * 1000.0f));
        result.commandRecordMicroseconds = static_cast<int>(std::lround(previewGpu.commandRecordMs * 1000.0f));
        result.queueMutexWaitMicroseconds = static_cast<int>(std::lround(previewGpu.queueMutexWaitMs * 1000.0f));
        result.queueSubmitCallMicroseconds = static_cast<int>(std::lround(previewGpu.queueSubmitCallMs * 1000.0f));
        result.fenceWaitMicroseconds = static_cast<int>(std::lround(previewGpu.fenceWaitMs * 1000.0f));

        if (!previewGpu.gpuResidentOutputUsed && outputRgba != nullptr) {
            int minimum = 255;
            int maximum = 0;
            std::uint64_t sum = 0u;
            std::uint64_t count = 0u;
            const std::size_t pixelCount = previewPixelCount;
            const std::size_t diagnosticStep = std::max<std::size_t>(1u, pixelCount / 4096u);
            for (std::size_t pixel = 0u; pixel < pixelCount; pixel += diagnosticStep) {
                const std::uint8_t* rgba = outputRgba + pixel * 4u;
                for (int channel = 0; channel < 3; ++channel) {
                    const int value = rgba[channel];
                    minimum = std::min(minimum, value);
                    maximum = std::max(maximum, value);
                    sum += static_cast<std::uint64_t>(value);
                    ++count;
                }
            }
            result.outputRgbMin = static_cast<float>(minimum) / 255.0f;
            result.outputRgbMax = static_cast<float>(maximum) / 255.0f;
            result.outputRgbMean = count > 0u
                    ? static_cast<float>(sum) / static_cast<float>(count * 255u) : 0.0f;
        } else {
            // Full-frame RGB diagnostics must not force a GPU->CPU readback. Keep the image plane
            // resident and expose compact shader statistics separately when needed.
            result.outputRgbMin = -1.0f;
            result.outputRgbMax = -1.0f;
            result.outputRgbMean = -1.0f;
        }
        result.vulkanStagesUsed = true;
        result.directHostInputUsed = previewGpu.directHostInputUsed;
        result.directHardwareBufferInputUsed = previewGpu.directHardwareBufferInputUsed;
        result.gpuResidentOutputUsed = previewGpu.gpuResidentOutputUsed;
        result.inputAhbFormat = previewGpu.inputAhbFormat;
        result.inputAhbUsage = previewGpu.inputAhbUsage;
        result.inputInteropStatus = previewGpu.inputInteropStatus;
        result.success = true;
        result.width = previewWidth;
        result.height = previewHeight;
        result.renderMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - started).count());
        return result;
    }

#if !defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
    // Production live-viewfinder contract: a Vulkan failure never activates an unbounded CPU
    // image-processing fallback. The Kotlin renderer treats this unsuccessful completion as a
    // dropped preview frame, retains the last uploaded GL texture and offers the newest RAW frame
    // to Vulkan next. Capture/DNG ownership is independent from this display-only failure.
    logPreviewFailure(previewGpu.failureReason.empty()
            ? "GPU_PREVIEW_FAILED_NO_CPU_FALLBACK"
            : previewGpu.failureReason.c_str());
    return result;
#else
    __android_log_print(
            ANDROID_LOG_WARN, "BnCamRawPreview",
            "RAW_PREVIEW_CPU_REFERENCE_ACTIVE reason=%s",
            previewGpu.failureReason.empty() ? "unknown" : previewGpu.failureReason.c_str());

    LockedRawBuffer locked{};
    if (!lockRaw(buffer, parameters.sourceFormat, locked)) {
        logPreviewFailure("hardware_buffer_lock_failed_cpu_reference");
        return result;
    }
    struct UnlockReferenceOnExit {
        AHardwareBuffer* buffer;
        ~UnlockReferenceOnExit() { if (buffer != nullptr) AHardwareBuffer_unlock(buffer, nullptr); }
    } unlockReference{buffer};

    cv::Mat normalized(previewHeight, previewWidth, CV_32FC1);
    cv::parallel_for_(cv::Range(0, previewHeight), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            float* destination = normalized.ptr<float>(y);
            const std::uint32_t localY = std::min(
                    static_cast<std::uint32_t>(cropHeight - 1),
                    static_cast<std::uint32_t>((y / 2) * 2 * cellDecimation + (y & 1)));
            const std::uint32_t sourceY = static_cast<std::uint32_t>(cropTop) + localY;
            for (int x = 0; x < previewWidth; ++x) {
                const std::uint32_t localX = std::min(
                        static_cast<std::uint32_t>(cropWidth - 1),
                        static_cast<std::uint32_t>((x / 2) * 2 * cellDecimation + (x & 1)));
                const std::uint32_t sourceX = static_cast<std::uint32_t>(cropLeft) + localX;
                const float rawValue = parameters.sourceFormat == RAW10_FORMAT
                        ? static_cast<float>(readRaw10(locked, sourceX, sourceY))
                        : static_cast<float>(readRawSensor(locked, sourceX, sourceY));
                const int channel = (static_cast<int>(localY) & 1) * 2 +
                        (static_cast<int>(localX) & 1);
                const float black = std::clamp(
                        parameters.blackLevels[channel], 0.0f, safeWhite - 1.0f);
                float normalizedValue = std::clamp(
                        (rawValue - black) / std::max(1.0f, safeWhite - black), 0.0f, 1.0f);
                // Detect both green sites from CFA pattern and row/column parity.
                const int xm = static_cast<int>(localX) & 1;
                const int ym = static_cast<int>(localY) & 1;
                const int cfa = std::clamp(parameters.cfaPattern, 0, 3);
                const bool isGreen = (cfa == 0 || cfa == 3) ? (xm != ym) : (xm == ym);
                if (isGreen) {
                    const float ratio = std::clamp(
                            std::max(1.0e-4f, parameters.quality.wbGreenEven) /
                                    std::max(1.0e-4f, parameters.quality.wbGreenOdd),
                            0.50f, 2.0f);
                    const float greenScale = (ym == 0)
                            ? (2.0f * ratio) / (ratio + 1.0f)
                            : 2.0f / (ratio + 1.0f);
                    normalizedValue = std::clamp(normalizedValue * greenScale, 0.0f, 1.0f);
                }
                destination[x] = normalizedValue;
            }
        }
    });
    double normalizedMin = 0.0;
    double normalizedMax = 0.0;
    cv::minMaxLoc(normalized, &normalizedMin, &normalizedMax);
    result.normalizedRawMin = static_cast<float>(normalizedMin);
    result.normalizedRawMax = static_cast<float>(normalizedMax);
    result.rawUnpackMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - started).count());

    cv::Mat rgb;
    std::vector<float> vulkanRgb;
    bncam::vulkan::SpectraResidentDemosaicRequest demosaicRequest{};
    demosaicRequest.mosaicData = normalized.ptr<float>(0);
    demosaicRequest.frameWidth = static_cast<std::uint32_t>(normalized.cols);
    demosaicRequest.frameHeight = static_cast<std::uint32_t>(normalized.rows);
    demosaicRequest.rowStrideFloats = normalized.step1();
    demosaicRequest.cfaPattern = static_cast<std::uint32_t>(std::clamp(parameters.cfaPattern, 0, 3));
    switch (parameters.demosaicMode) {
        case 1:
            demosaicRequest.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::MALVAR_2004;
            break;
        case 2:
            demosaicRequest.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::AMAZE_INSPIRED;
            break;
        case 3:
        default:
            demosaicRequest.algorithm = bncam::vulkan::SpectraGpuDemosaicAlgorithm::RCD_INSPIRED;
            break;
    }
    const auto demosaicStarted = std::chrono::steady_clock::now();
    const auto demosaic = runtime.executeSpectraResidentDemosaic(demosaicRequest);
    result.demosaicMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - demosaicStarted).count());
    if (demosaic.success && demosaic.residentDemosaicGeneration != 0u) {
        bncam::vulkan::SpectraResidentColorTransformRequest colorRequest{};
        colorRequest.frameWidth = demosaicRequest.frameWidth;
        colorRequest.frameHeight = demosaicRequest.frameHeight;
        colorRequest.residentDemosaicGeneration = demosaic.residentDemosaicGeneration;
        const float green = std::max(1.0e-4f, 0.5f *
                (parameters.quality.wbGreenEven + parameters.quality.wbGreenOdd));
        colorRequest.wbRgb = {parameters.quality.wbRed / green, 1.0f,
                              parameters.quality.wbBlue / green};
        std::copy(std::begin(parameters.quality.colorMatrix),
                  std::end(parameters.quality.colorMatrix), colorRequest.colorMatrix.begin());
        colorRequest.deferFullReadback = false;
        const auto colorStarted = std::chrono::steady_clock::now();
        auto color = runtime.executeSpectraResidentAwbCcm(colorRequest);
        result.colorMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - colorStarted).count());
        if (color.success && color.outputRgb.size() == normalized.total() * 3u) {
            vulkanRgb = std::move(color.outputRgb);
            rgb = cv::Mat(normalized.rows, normalized.cols, CV_32FC3, vulkanRgb.data());
            result.vulkanStagesUsed = true;
        }
    }
    if (rgb.empty()) {
        rgb = cpuDemosaicFallback(normalized, parameters.cfaPattern, parameters.demosaicMode);
        applyCpuAwbCcm(rgb, parameters.quality);
    }

    const std::size_t requiredBytes = rgb.total() * 4u;
    if (requiredBytes > outputCapacityBytes) {
        logPreviewFailure("rgba_output_capacity_too_small");
        return result;
    }
    const PreviewExposureResult exposure = resolvePreviewExposure(rgb, parameters);
    result.targetExposureGain = exposure.targetGain;
    result.appliedExposureGain = exposure.targetGain;
    result.sceneMidtone = exposure.midtone;
    const auto tonePackStarted = std::chrono::steady_clock::now();
    toneAndPackPreview(
            rgb, parameters.quality, result.appliedExposureGain, outputRgba, result);
    result.tonePackMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - tonePackStarted).count());

    result.success = true;
    result.width = rgb.cols;
    result.height = rgb.rows;
    result.renderMicroseconds = static_cast<int>(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - started).count());
    return result;
#endif  // BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE
}
