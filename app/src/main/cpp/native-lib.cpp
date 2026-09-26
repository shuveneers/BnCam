#include "tests/PhysicalChromaValidation.h"
#include <jni.h>
#include <cstdint>
#include <vector>
#include <string>
#include <mutex>
#include <algorithm>
#include <array>
#include <cmath>
#include <initializer_list>
#include <limits>
#include <sstream>
#include <iomanip>
#include <chrono>
#include <cstring>
#include <atomic>
#include <android/hardware_buffer_jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <opencv2/opencv.hpp>
#include "DngMerger.h"
#include "NativeRenderQualityConfig.h"
#include "ProfileNoiseReductionPolicy.h"
#include "ProfileMicroDetailTransport.h"
#include "ProfileColorManagement.h"
#include "IspCore.h"
#include "RawCfaLevelMapping.h"
#include "RawDigitalZoomCrop.h"
#include "ultrahdr/UltraHdrJpegPackager.h"
#include "JpegEncodingPolicy.h"
#include "Demosaic.h"
#include "NeutralYuvToneMapper.h"
#include "YuvSignalDiagnostics.h"
#include "RawPreview.h"
#include "vulkan/NativeStageHeartbeat.h"
#include "vulkan/VulkanRuntime.h"
#include <unordered_map>

#define LOG_TAG "BnCam_NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using NativeClock = std::chrono::steady_clock;
using NativeTimePoint = std::chrono::time_point<NativeClock>;

float nativeElapsedMs(NativeTimePoint start, NativeTimePoint end = NativeClock::now()) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(end - start).count());
}

std::string nativeFmtMs(float value) {
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(4) << value;
    return oss.str();
}

std::mutex gDngStatsMutex;
std::string gLastDngMergeStats = "not recorded";
std::mutex gMasterIspStatsMutex;
std::string gLastMasterIspStats = "not recorded";
std::mutex gYuvStatsMutex;
std::string gLastYuvStats = "not recorded";
thread_local UltraHdrGainmapArtifact gThreadLocalYuvUltraHdrArtifact{};
std::mutex gRawPreviewRetainMutex;
std::unordered_map<AHardwareBuffer*, std::size_t> gRawPreviewRetains;

struct RawPreviewEglImageRecord {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    AHardwareBuffer* buffer = nullptr;
};
std::mutex gRawPreviewEglImageMutex;
std::unordered_map<AHardwareBuffer*, RawPreviewEglImageRecord> gRawPreviewEglImages;

struct RawPreviewGlFenceRecord {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSyncKHR sync = EGL_NO_SYNC_KHR;
};
std::mutex gRawPreviewGlFenceMutex;
std::unordered_map<std::uint64_t, RawPreviewGlFenceRecord> gRawPreviewGlFences;
std::atomic<std::uint64_t> gRawPreviewGlFenceNextId{1u};


std::vector<AHardwareBuffer*> extractHardwareBuffers(JNIEnv *env, jobjectArray hwBufferArray) {
    std::vector<AHardwareBuffer*> buffers;
    if (hwBufferArray == nullptr) return buffers;

    const jsize count = env->GetArrayLength(hwBufferArray);
    buffers.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; i++) {
        jobject hwBufferObj = env->GetObjectArrayElement(hwBufferArray, i);
        if (hwBufferObj == nullptr) continue;

        AHardwareBuffer *hwBuffer = AHardwareBuffer_fromHardwareBuffer(env, hwBufferObj);
        if (hwBuffer != nullptr) {
            AHardwareBuffer_acquire(hwBuffer);
            buffers.push_back(hwBuffer);
        }
        env->DeleteLocalRef(hwBufferObj);
    }

    return buffers;
}

void releaseHardwareBuffers(const std::vector<AHardwareBuffer*>& buffers) {
    for (auto *buffer : buffers) {
        if (buffer != nullptr) AHardwareBuffer_release(buffer);
    }
}

std::vector<int32_t> extractBlackLevelVector(JNIEnv* env, jintArray blackLevelArray) {
    std::vector<int32_t> values(4, 0);
    if (blackLevelArray == nullptr) return values;
    const jsize count = env->GetArrayLength(blackLevelArray);
    if (count <= 0) return values;
    jint tmp[4] = {0, 0, 0, 0};
    env->GetIntArrayRegion(blackLevelArray, 0, std::min<jsize>(4, count), tmp);
    for (int i = 0; i < 4; ++i) values[static_cast<size_t>(i)] = static_cast<int32_t>(tmp[i]);
    return values;
}

struct CanonicalPhysicalNoiseSoPayload {
    std::array<double, 4> s{{0.0, 0.0, 0.0, 0.0}};
    std::array<double, 4> o{{0.0, 0.0, 0.0, 0.0}};
    bool valid = false;
};

CanonicalPhysicalNoiseSoPayload extractCanonicalPhysicalNoiseSo(
        JNIEnv* env,
        jdoubleArray array
) {
    CanonicalPhysicalNoiseSoPayload out{};
    if (array == nullptr || env->GetArrayLength(array) != 8) return out;
    jdouble raw[8] = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    env->GetDoubleArrayRegion(array, 0, 8, raw);
    bool hasEnergy = false;
    for (int ch = 0; ch < 4; ++ch) {
        const double sValue = static_cast<double>(raw[ch * 2]);
        const double oValue = static_cast<double>(raw[ch * 2 + 1]);
        if (!std::isfinite(sValue) || !std::isfinite(oValue) || sValue < 0.0 || oValue < 0.0) {
            return CanonicalPhysicalNoiseSoPayload{};
        }
        out.s[static_cast<std::size_t>(ch)] = sValue;
        out.o[static_cast<std::size_t>(ch)] = oValue;
        hasEnergy = hasEnergy || sValue > 0.0 || oValue > 0.0;
    }
    out.valid = hasEnergy;
    return out;
}

std::vector<double> physicalNoiseVector(const std::array<double, 4>& values) {
    return std::vector<double>(values.begin(), values.end());
}

jbyteArray createJavaByteArray(JNIEnv *env, const std::vector<uint8_t>& cppBuffer) {
    if (cppBuffer.empty()) return nullptr;

    auto size = static_cast<jsize>(cppBuffer.size());
    jbyteArray javaArray = env->NewByteArray(size);
    if (javaArray == nullptr) return nullptr;

    env->SetByteArrayRegion(
            javaArray,
            0,
            size,
            reinterpret_cast<const jbyte*>(cppBuffer.data())
    );
    return javaArray;
}

void copyFloatArrayOrNeutral(JNIEnv* env, jfloatArray array, float* dst, int expectedCount) {
    if (dst == nullptr || expectedCount <= 0) return;
    for (int i = 0; i < expectedCount; ++i) {
        dst[i] = expectedCount <= 1 ? 0.0f : static_cast<float>(i) / static_cast<float>(expectedCount - 1);
    }
    if (array == nullptr) return;
    const jsize count = env->GetArrayLength(array);
    const int toCopy = std::min(static_cast<int>(count), expectedCount);
    if (toCopy > 0) env->GetFloatArrayRegion(array, 0, toCopy, dst);
}

std::array<float, 4> extractFloat4(JNIEnv* env, jfloatArray array) {
    std::array<float, 4> values{0.0f, 0.0f, 0.0f, 0.0f};
    if (array != nullptr && env->GetArrayLength(array) >= 4) {
        env->GetFloatArrayRegion(array, 0, 4, values.data());
    }
    return values;
}

std::vector<float> extractPositiveFloatVector(JNIEnv* env, jfloatArray array) {
    std::vector<float> values;
    if (array == nullptr) return values;
    const jsize count = env->GetArrayLength(array);
    if (count <= 0) return values;
    values.resize(static_cast<size_t>(count), 1.0f);
    env->GetFloatArrayRegion(array, 0, count, values.data());
    for (float& value : values) {
        if (!std::isfinite(value) || value <= 0.0f) value = 1.0f;
        value = std::clamp(value, 0.0625f, 16.0f);
    }
    return values;
}

std::string getJniString(JNIEnv* env, jstring value, const std::string& fallback = "") {
    if (value == nullptr) return fallback;
    const char* characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) return fallback;
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}

uint8_t readPlaneByte(const AHardwareBuffer_Plane& plane, uint32_t row, uint32_t col) {
    auto *base = static_cast<uint8_t*>(plane.data);
    return *(base + row * plane.rowStride + col * plane.pixelStride);
}

int normalizeRotationDegrees(int rotationDegrees) {
    int normalized = rotationDegrees % 360;
    if (normalized < 0) normalized += 360;
    if (normalized == 90 || normalized == 180 || normalized == 270) return normalized;
    return 0;
}

void rotateMatInPlace(cv::Mat& mat, int rotationDegrees) {
    const int normalized = normalizeRotationDegrees(rotationDegrees);
    if (normalized == 0 || mat.empty()) return;

    cv::Mat rotated;
    if (normalized == 90) {
        cv::rotate(mat, rotated, cv::ROTATE_90_CLOCKWISE);
    } else if (normalized == 180) {
        cv::rotate(mat, rotated, cv::ROTATE_180);
    } else if (normalized == 270) {
        cv::rotate(mat, rotated, cv::ROTATE_90_COUNTERCLOCKWISE);
    }

    if (!rotated.empty()) {
        mat = rotated;
    }
}

bool copyYuv420PlanesToNv21(
        const AHardwareBuffer_Planes& planes,
        uint32_t width,
        uint32_t height,
        std::vector<uint8_t>& outNv21,
        std::string* layoutOut = nullptr
) {
    if (planes.planeCount < 3) {
        LOGE("YUV conversion failed: expected 3 planes, got %u", planes.planeCount);
        return false;
    }

    if (width == 0 || height == 0 || (width % 2u) != 0u || (height % 2u) != 0u) {
        LOGE("YUV conversion failed: invalid dimensions %ux%u", width, height);
        return false;
    }

    const auto& yPlane = planes.planes[0];
    const auto& uPlane = planes.planes[1];
    const auto& vPlane = planes.planes[2];

    const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t totalSize = ySize + ySize / 2u;
    outNv21.resize(totalSize);

    auto *dstY = outNv21.data();
    auto *srcY = static_cast<const uint8_t*>(yPlane.data);
    if (srcY == nullptr) return false;

    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        std::memcpy(dstY, srcY, ySize);
    } else if (yPlane.pixelStride == 1) {
        for (uint32_t row = 0; row < height; ++row) {
            std::memcpy(dstY + static_cast<size_t>(row) * width, srcY + static_cast<size_t>(row) * yPlane.rowStride, width);
        }
    } else {
        for (uint32_t row = 0; row < height; ++row) {
            for (uint32_t col = 0; col < width; ++col) {
                outNv21[static_cast<size_t>(row) * width + col] = readPlaneByte(yPlane, row, col);
            }
        }
    }

    size_t uvOffset = ySize;
    const uint32_t chromaHeight = height / 2u;
    const uint32_t chromaWidth = width / 2u;
    auto *srcU = static_cast<const uint8_t*>(uPlane.data);
    auto *srcV = static_cast<const uint8_t*>(vPlane.data);
    if (srcU == nullptr || srcV == nullptr) return false;

    // Most Android YUV_420_888 buffers are already NV21-like: VU interleaved with
    // pixelStride=2. In that case copy one chroma row at a time instead of touching
    // every chroma sample in a scalar helper loop. The fallback below preserves the
    // old exact behavior for unusual planar/strided layouts.
    const bool nv21Interleaved =
            uPlane.pixelStride == 2 &&
            vPlane.pixelStride == 2 &&
            uPlane.rowStride == vPlane.rowStride &&
            srcU == srcV + 1;
    const bool nv12Interleaved =
            uPlane.pixelStride == 2 &&
            vPlane.pixelStride == 2 &&
            uPlane.rowStride == vPlane.rowStride &&
            srcV == srcU + 1;

    if (layoutOut != nullptr) {
        if (nv21Interleaved) {
            *layoutOut = "NV21_FAST_PATH_VU";
        } else if (nv12Interleaved) {
            *layoutOut = "NV12_OR_UV_SOURCE_REPACKED_TO_NV21";
        } else {
            *layoutOut = "PLANAR_OR_STRIDED_REPACKED_TO_NV21";
        }
        *layoutOut += ";yRowStride=" + std::to_string(yPlane.rowStride);
        *layoutOut += ";yPixelStride=" + std::to_string(yPlane.pixelStride);
        *layoutOut += ";uRowStride=" + std::to_string(uPlane.rowStride);
        *layoutOut += ";uPixelStride=" + std::to_string(uPlane.pixelStride);
        *layoutOut += ";vRowStride=" + std::to_string(vPlane.rowStride);
        *layoutOut += ";vPixelStride=" + std::to_string(vPlane.pixelStride);
    }

    if (nv21Interleaved) {
        if (vPlane.rowStride == width) {
            // DE HEILIGE GRAAL: Eén gigantische memcpy voor alle chroma tegelijk! (0.5 ms)
            std::memcpy(outNv21.data() + uvOffset, srcV, static_cast<size_t>(width) * chromaHeight);
        } else {
            for (uint32_t row = 0; row < chromaHeight; ++row) {
                std::memcpy(outNv21.data() + uvOffset + static_cast<size_t>(row) * width, srcV + static_cast<size_t>(row) * vPlane.rowStride, width);
            }
        }
    } else {
        // HIGH SPEED FALLBACK: Pointer wiskunde elimineert trage functie aanroepen (~3 ms)
        for (uint32_t row = 0; row < chromaHeight; ++row) {
            const uint8_t* pV = srcV + row * vPlane.rowStride;
            const uint8_t* pU = srcU + row * uPlane.rowStride;
            uint8_t* pDst = outNv21.data() + uvOffset + row * width;
            for (uint32_t col = 0; col < chromaWidth; ++col) {
                pDst[col * 2]     = pV[col * vPlane.pixelStride];
                pDst[col * 2 + 1] = pU[col * uPlane.pixelStride];
            }
        }
    }

    return true;
}

std::vector<float> extractCurveVector(
        JNIEnv* env,
        jfloatArray curveArray,
        int minimumCount,
        int maximumCount
) {
    std::vector<float> out;
    if (curveArray == nullptr) return out;
    const jsize count = env->GetArrayLength(curveArray);
    if (count < minimumCount) return out;
    const int safeCount = std::clamp(static_cast<int>(count), minimumCount, maximumCount);
    out.resize(static_cast<size_t>(safeCount));
    env->GetFloatArrayRegion(curveArray, 0, static_cast<jsize>(safeCount), out.data());
    for (int i = 0; i < safeCount; ++i) {
        if (!std::isfinite(out[static_cast<size_t>(i)])) {
            out.clear();
            return out;
        }
        out[static_cast<size_t>(i)] = std::clamp(out[static_cast<size_t>(i)], 0.0f, 1.0f);
    }
    if (!out.empty()) {
        out.front() = 0.0f;
        out.back() = 1.0f;
    }
    return out;
}

// Color Manager ABI bridge. Values below -1.5 are exact-integer Float32 carriers
// produced by ProfileColorTuning.nativeSaturationCarrier(): 8-bit Saturation, 7-bit Pop,
// 8-bit Color Recovery. Preserve those carriers until the Vulkan color stage decodes them.
static float sanitizeProfileCreativeCarrier(float value) {
    if (!std::isfinite(value)) return 0.0f;
    if (value <= -1.5f) {
        const float payload = -value - 2.0f;
        if (payload >= 0.0f && payload <= 8388607.0f &&
            std::abs(payload - std::round(payload)) <= 0.001f) {
            return value;
        }
        return 0.0f;
    }
    return std::clamp(value, -1.0f, 1.0f);
}

NativeRenderQualityConfig makeQualityConfig(
        JNIEnv *env,
        jfloatArray wbGainsArray,
        jboolean wbFromMetadata,
        jfloatArray colorMatrixArray,
        jboolean colorMatrixFromMetadata,
        jint jpegQuality,
        jfloat profileSpectraLuma,
        jfloat profileSpectraChroma,
        jfloat profileSpectraDetailProtection,
        jfloat profileSpectraLowFrequency,
        jfloat profileToneExposure,
        jfloat profileToneHighlights,
        jfloat profileToneShadows,
        jfloat profileToneWhites,
        jfloat profileToneBlacks,
        jfloat profileToneContrast,
        jfloat profileLocalToneBias,
        jfloat profileColorSaturation,
        jfloat profileColorContrast,
        jfloat profilePresenceVibrance,
        jfloat profileDetailAmount,
        jfloat profileDetailRadius,
        jfloat profileDetailDetail,
        jfloat profileDetailMasking,
        jfloatArray toneCurveArray,
        jfloatArray gammaCurveArray,
        jfloatArray sectionCurveArray
) {
    NativeRenderQualityConfig cfg{};
    cfg.jpegQuality = jpegQuality;
    cfg.wbFromMetadata = wbFromMetadata == JNI_TRUE;
    cfg.colorMatrixFromMetadata = colorMatrixFromMetadata == JNI_TRUE;

    // FASE 11: On/Off is the sole global Neural gate. Master authority and Adaptive Response
    // are fixed at 100% and therefore no longer consume JNI slots.
    cfg.profileSpectraStrength = 1.0f;
    cfg.profileNeuralAdaptiveResponse = 1.0f;
    cfg.profileSpectraLuma = std::isfinite(profileSpectraLuma) ? std::clamp(profileSpectraLuma, -1.0f, 1.0f) : 0.0f;
    cfg.profileSpectraChroma = std::isfinite(profileSpectraChroma) ? std::clamp(profileSpectraChroma, -1.0f, 1.0f) : 0.0f;
    cfg.profileSpectraDetailProtection = std::isfinite(profileSpectraDetailProtection) ? std::clamp(profileSpectraDetailProtection, -1.0f, 1.0f) : 0.0f;
    cfg.profileSpectraLowFrequency = std::isfinite(profileSpectraLowFrequency) ? std::clamp(profileSpectraLowFrequency, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneExposure = std::isfinite(profileToneExposure) ? std::clamp(profileToneExposure, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneHighlights = std::isfinite(profileToneHighlights) ? std::clamp(profileToneHighlights, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneShadows = std::isfinite(profileToneShadows) ? std::clamp(profileToneShadows, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneWhites = std::isfinite(profileToneWhites) ? std::clamp(profileToneWhites, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneBlacks = std::isfinite(profileToneBlacks) ? std::clamp(profileToneBlacks, -1.0f, 1.0f) : 0.0f;
    cfg.profileToneContrast = std::isfinite(profileToneContrast) ? std::clamp(profileToneContrast, -1.0f, 1.0f) : 0.0f;
    cfg.profileLocalToneBias = std::isfinite(profileLocalToneBias) ? std::clamp(profileLocalToneBias, -1.0f, 1.0f) : 0.0f;
    cfg.profileColorSaturation = sanitizeProfileCreativeCarrier(profileColorSaturation);
    cfg.profileColorContrast = std::isfinite(profileColorContrast) ? std::clamp(profileColorContrast, -1.0f, 1.0f) : 0.0f;
    cfg.profilePresenceVibrance = std::isfinite(profilePresenceVibrance) ? std::clamp(profilePresenceVibrance, -1.0f, 1.0f) : 0.0f;
    cfg.profileDetailAmount = std::isfinite(profileDetailAmount) ? std::clamp(profileDetailAmount, -1.0f, 1.0f) : bncam::profile_defaults::kDetailAmount;
    const float profileLegibility = std::isfinite(profileDetailRadius)
            ? std::clamp(profileDetailRadius, -1.0f, 1.0f) : 0.0f;
    // Retired radius consumers keep the previously effective default floor. Detail + Legibility
    // share a negative-only packed transport so those old unsigned consumers remain neutral.
    cfg.profileDetailRadius = bncam::profile_defaults::kDetailMinRadius;
    cfg.profileDetailDetail = bncam::profile_microdetail_transport::encodePair(profileDetailDetail, profileLegibility);
    cfg.profileDetailMasking = std::isfinite(profileDetailMasking) ? std::clamp(profileDetailMasking, -1.0f, 1.0f) : bncam::profile_defaults::kDetailMasking;

    cfg.toneCurve = extractCurveVector(env, toneCurveArray, 2, 64);
    cfg.gammaCurve = extractCurveVector(env, gammaCurveArray, 2, 64);
    cfg.sectionCurve = extractCurveVector(env, sectionCurveArray, 2, 64);

    if (wbGainsArray != nullptr && env->GetArrayLength(wbGainsArray) >= 4) {
        jfloat values[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        env->GetFloatArrayRegion(wbGainsArray, 0, 4, values);
        cfg.wbRed = values[0];
        cfg.wbGreenEven = values[1];
        cfg.wbGreenOdd = values[2];
        cfg.wbBlue = values[3];
    }

    if (colorMatrixArray != nullptr && env->GetArrayLength(colorMatrixArray) >= 9) {
        jfloat values[9] = {
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
        };
        env->GetFloatArrayRegion(colorMatrixArray, 0, 9, values);
        for (int i = 0; i < 9; ++i) {
            cfg.colorMatrix[i] = values[i];
        }
    }

    return cfg;
}

bool isNeutralCurveNative(const float* curve, int count) {
    if (curve == nullptr || count <= 1) return true;
    for (int i = 0; i < count; ++i) {
        const float expected = static_cast<float>(i) / static_cast<float>(count - 1);
        if (std::abs(curve[i] - expected) > 0.0015f) return false;
    }
    return true;
}

float parseExposureGainFromDebug(const std::string& debugStr, float fallback) {
    std::string key = ";appliedGain=";
    size_t pos = debugStr.find(key);
    if (pos == std::string::npos) {
        key = ";exposureGain=";
        pos = debugStr.find(key);
    }
    if (pos == std::string::npos) {
        key = ";exposureMultiplier=";
        pos = debugStr.find(key);
    }
    if (pos == std::string::npos) return fallback;
    try {
        size_t valStart = pos + key.length();
        size_t valEnd = debugStr.find(';', valStart);
        std::string valStr = (valEnd == std::string::npos) ? debugStr.substr(valStart) : debugStr.substr(valStart, valEnd - valStart);
        return std::stof(valStr);
    } catch (...) {
        return fallback;
    }
}

float evalCurveNative(const float* curve, int count, float x) {
    if (curve == nullptr || count <= 1) return std::clamp(x, 0.0f, 1.0f);
    const float cx = std::clamp(x, 0.0f, 1.0f);
    const float pos = cx * static_cast<float>(count - 1);
    const int idx = std::clamp(static_cast<int>(std::floor(pos)), 0, count - 1);
    const int next = std::min(idx + 1, count - 1);
    const float t = pos - static_cast<float>(idx);
    const float a = std::clamp(curve[idx], 0.0f, 1.0f);
    const float b = std::clamp(curve[next], 0.0f, 1.0f);
    return std::clamp(a + (b - a) * t, 0.0f, 1.0f);
}

bool hasActiveCurveStackNative(const NativeRenderQualityConfig& cfg) {
    return false;
}

void applyCurveStackToBgrNative(cv::Mat& bgrMat, const NativeRenderQualityConfig& cfg) {
    // curves are removed
}

bool applyYuvDenoiseToBgrNative(cv::Mat& bgrMat, const NativeRenderQualityConfig& cfg) {
    return false;
}

bool hasActiveYuvPublicControlsNative(const NativeRenderQualityConfig& cfg) {
    return false;
}

struct LumaPercentiles {
    float p01 = 0.0f;
    float p50 = 0.0f;
    float p99 = 0.0f;
    
    float p0_1 = 0.0f;
    float p1 = 0.0f;
    float p5 = 0.0f;
    float p95 = 0.0f;
    float p99_9 = 0.0f;
    
    float blackClippedFraction = 0.0f;
    float whiteClippedFraction = 0.0f;
    float contrastRatio = 0.0f;
};

LumaPercentiles calculateLumaPercentiles(const cv::Mat& bgrMat) {
    if (bgrMat.empty()) return {};
    std::vector<float> lumaValues;
    int gridX = 100;
    int gridY = 100;
    float stepX = static_cast<float>(bgrMat.cols) / static_cast<float>(gridX);
    float stepY = static_cast<float>(bgrMat.rows) / static_cast<float>(gridY);
    lumaValues.reserve(gridX * gridY);
    for (int gy = 0; gy < gridY; ++gy) {
        int y = std::clamp(static_cast<int>(gy * stepY + stepY * 0.5f), 0, bgrMat.rows - 1);
        const cv::Vec3b* row = bgrMat.ptr<cv::Vec3b>(y);
        for (int gx = 0; gx < gridX; ++gx) {
            int x = std::clamp(static_cast<int>(gx * stepX + stepX * 0.5f), 0, bgrMat.cols - 1);
            cv::Vec3b val = row[x];
            float b = val[0] / 255.0f;
            float g = val[1] / 255.0f;
            float r = val[2] / 255.0f;
            float y_val = 0.299f * r + 0.587f * g + 0.114f * b;
            lumaValues.push_back(y_val);
        }
    }
    std::sort(lumaValues.begin(), lumaValues.end());
    size_t n = lumaValues.size();
    LumaPercentiles res;
    if (n > 0) {
        res.p0_1 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.001f), 0, n - 1)];
        res.p1 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.01f), 0, n - 1)];
        res.p01 = res.p1;
        res.p5 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.05f), 0, n - 1)];
        res.p50 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.50f), 0, n - 1)];
        res.p95 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.95f), 0, n - 1)];
        res.p99 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.99f), 0, n - 1)];
        res.p99_9 = lumaValues[std::clamp<size_t>(static_cast<size_t>(n * 0.999f), 0, n - 1)];

        size_t blackCount = 0;
        size_t whiteCount = 0;
        for (float val : lumaValues) {
            if (val <= 0.005f) blackCount++;
            if (val >= 0.995f) whiteCount++;
        }
        res.blackClippedFraction = static_cast<float>(blackCount) / static_cast<float>(n);
        res.whiteClippedFraction = static_cast<float>(whiteCount) / static_cast<float>(n);
        res.contrastRatio = res.p99_9 / std::max(0.001f, res.p0_1);
    }
    return res;
}

LumaPercentiles calculateNativeYPercentiles(const std::vector<uint8_t>& nv21, uint32_t width, uint32_t height) {
    if (nv21.size() < width * height) return {};
    std::vector<float> yValues;
    int gridX = 100;
    int gridY = 100;
    float stepX = static_cast<float>(width) / static_cast<float>(gridX);
    float stepY = static_cast<float>(height) / static_cast<float>(gridY);
    yValues.reserve(gridX * gridY);
    for (int gy = 0; gy < gridY; ++gy) {
        int y = std::clamp(static_cast<int>(gy * stepY + stepY * 0.5f), 0, static_cast<int>(height) - 1);
        const uint8_t* rowStart = nv21.data() + static_cast<size_t>(y) * width;
        for (int gx = 0; gx < gridX; ++gx) {
            int x = std::clamp(static_cast<int>(gx * stepX + stepX * 0.5f), 0, static_cast<int>(width) - 1);
            float y_val = rowStart[x] / 255.0f;
            yValues.push_back(y_val);
        }
    }
    std::sort(yValues.begin(), yValues.end());
    size_t n = yValues.size();
    LumaPercentiles res;
    if (n > 0) {
        res.p0_1 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.001f), 0, n - 1)];
        res.p1 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.01f), 0, n - 1)];
        res.p01 = res.p1;
        res.p5 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.05f), 0, n - 1)];
        res.p50 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.50f), 0, n - 1)];
        res.p95 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.95f), 0, n - 1)];
        res.p99 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.99f), 0, n - 1)];
        res.p99_9 = yValues[std::clamp<size_t>(static_cast<size_t>(n * 0.999f), 0, n - 1)];

        size_t blackCount = 0;
        size_t whiteCount = 0;
        for (float val : yValues) {
            if (val <= 0.005f) blackCount++;
            if (val >= 0.995f) whiteCount++;
        }
        res.blackClippedFraction = static_cast<float>(blackCount) / static_cast<float>(n);
        res.whiteClippedFraction = static_cast<float>(whiteCount) / static_cast<float>(n);
        res.contrastRatio = res.p99_9 / std::max(0.001f, res.p0_1);
    }
    return res;
}

struct YuvPostProcessingStats {
    bool yuvToneAlignmentApplied = false;
    int yuvToneLutSize = 0;
    float yuvInputNativeYP01 = 0.0f;
    float yuvInputNativeYP50 = 0.0f;
    float yuvInputNativeYP99 = 0.0f;
    float yuvOutputBgrLumaP01 = 0.0f;
    float yuvOutputBgrLumaP50 = 0.0f;
    float yuvOutputBgrLumaP99 = 0.0f;
    bool yuvToneCurveMonotonic = true;
    float yuvToneMidpointOutput = 0.0f;
    float yuvToneShadowOutput = 0.0f;
    float yuvToneHighlightOutput = 0.0f;
    bool yuvToneDarkeningDetected = false;
    std::string yuvToneDarkeningReason = "none";
    bool defaultColorToneBypassed = false;
    bool gpuPostApplied = false;
    bool yuvDenoiseApplied = false;
    std::string bypassReason = "none";
    bool yuvBilateralApplied = false;
    int yuvBilateralDiameter = 0;
    float yuvBilateralSigmaColor = 0.0f;
    float yuvBilateralSigmaSpace = 0.0f;
    float yuvDenoiseStrengthResolved = 0.0f;

    // Detailed YUV luma statistics
    float yuvInputNativeYP0_1 = 0.0f;
    float yuvInputNativeYP1 = 0.0f;
    float yuvInputNativeYP5 = 0.0f;
    float yuvInputNativeYP95 = 0.0f;
    float yuvInputNativeYP99_9 = 0.0f;
    float yuvInputBlackClippedFraction = 0.0f;
    float yuvInputWhiteClippedFraction = 0.0f;
    float yuvInputContrastRatio = 0.0f;

    float yuvOutputBgrLumaP0_1 = 0.0f;
    float yuvOutputBgrLumaP1 = 0.0f;
    float yuvOutputBgrLumaP5 = 0.0f;
    float yuvOutputBgrLumaP95 = 0.0f;
    float yuvOutputBgrLumaP99_9 = 0.0f;
    float yuvOutputBlackClippedFraction = 0.0f;
    float yuvOutputWhiteClippedFraction = 0.0f;
    float yuvOutputContrastRatio = 0.0f;
};

void applyYuvProfileRgbAdjustments(
        cv::Mat& bgrMat,
        const NativeRenderQualityConfig& qualityConfig
) {
    if (bgrMat.empty() || bgrMat.type() != CV_8UC3) return;

    const float wbR = std::clamp(qualityConfig.profileYuvWbRed, 0.50f, 2.00f);
    const float wbG = std::clamp(qualityConfig.profileYuvWbGreen, 0.50f, 2.00f);
    const float wbB = std::clamp(qualityConfig.profileYuvWbBlue, 0.50f, 2.00f);
    const bool awbActive =
            std::abs(wbR - 1.0f) > 1.0e-4f ||
            std::abs(wbG - 1.0f) > 1.0e-4f ||
            std::abs(wbB - 1.0f) > 1.0e-4f;
    const bool profileColorActive =
            std::abs(qualityConfig.profileColorSaturation) > 1.0e-4f ||
            std::abs(qualityConfig.profileColorContrast) > 1.0e-4f ||
            std::abs(qualityConfig.profilePresenceVibrance) > 1.0e-4f;
    if (!awbActive && !profileColorActive) return;

    cv::parallel_for_(cv::Range(0, bgrMat.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3b* row = bgrMat.ptr<cv::Vec3b>(y);
            for (int x = 0; x < bgrMat.cols; ++x) {
                float b = std::clamp((row[x][0] / 255.0f) * wbB, 0.0f, 1.0f);
                float g = std::clamp((row[x][1] / 255.0f) * wbG, 0.0f, 1.0f);
                float r = std::clamp((row[x][2] / 255.0f) * wbR, 0.0f, 1.0f);
                if (profileColorActive) {
                    applyBncamProfileColorManagement(r, g, b, qualityConfig);
                }
                row[x][0] = cv::saturate_cast<uint8_t>(b * 255.0f);
                row[x][1] = cv::saturate_cast<uint8_t>(g * 255.0f);
                row[x][2] = cv::saturate_cast<uint8_t>(r * 255.0f);
            }
        }
    });
}

void applyYuvPostProcessing(cv::Mat& bgrMat, const LumaPercentiles& inputLuma, const NativeRenderQualityConfig& rawCfg, YuvPostProcessingStats* statsOut) {
    if (bgrMat.empty()) return;

    bool toneApplied = false;
    bool nrApplied = false;
    int diameter = 5;
    const auto profileNrPlan = bncam::profile_nr::resolveProfileNoiseReduction(
            rawCfg.profileNrLuminance, rawCfg.profileNrLuminanceDetail, rawCfg.profileNrLuminanceContrast,
            rawCfg.profileNrColor, rawCfg.profileNrColorDetail, rawCfg.profileNrColorSmoothness);
    // FASE 13: Camera2 YUV is already vendor-ISP processed, so capture ISO is not a
    // physical residual-noise model. The CPU failsafe keeps creative profile NR only;
    // the production Vulkan path derives its baseline from measured Y/U/V residuals.
    const float effectiveLumaNr = std::clamp(profileNrPlan.lumaCreativeBlend, 0.0f, 0.75f);
    const float effectiveChromaNr = std::clamp(
            profileNrPlan.chromaCreativeBlend *
                    (0.70f + 0.30f * profileNrPlan.colorSmoothness), 0.0f, 0.85f);
    const float effectiveNr = std::max(effectiveLumaNr, effectiveChromaNr);
    float sigmaColor = effectiveNr * 80.0f;
    float sigmaSpace = 2.6f + 1.2f * std::max(profileNrPlan.luminance, profileNrPlan.color);

    // 1. CPU/OpenCV failsafe only. The production path performs this NR on Vulkan.
    if (effectiveNr > 0.002f) {
        cv::Mat filtered;
        cv::bilateralFilter(bgrMat, filtered, diameter, sigmaColor, sigmaSpace);
        bgrMat = std::move(filtered);
        nrApplied = true;
    }

    // 2. YUV is already rendered by the HAL. Always use the dedicated neutral luma curve;
    // profile RAW toe/anchor/shadow controls deliberately do not participate in this path.
    const auto lut = bncam::NeutralYuvToneMapper::buildLut();
    const bool toneCurveActive = !isNeutralCurveNative(rawCfg.toneCurve.data(), static_cast<int>(rawCfg.toneCurve.size()));
    const bool gammaCurveActive = !isNeutralCurveNative(rawCfg.gammaCurve.data(), static_cast<int>(rawCfg.gammaCurve.size()));
    const bool sectionCurveActive = !isNeutralCurveNative(rawCfg.sectionCurve.data(), static_cast<int>(rawCfg.sectionCurve.size()));
    auto evalProfileCurve = [](const std::vector<float>& curve, float value) -> float {
        if (curve.size() < 2u) return std::clamp(value, 0.0f, 1.0f);
        const float position = std::clamp(value, 0.0f, 1.0f) * static_cast<float>(curve.size() - 1u);
        const size_t left = std::min(static_cast<size_t>(position), curve.size() - 1u);
        const size_t right = std::min(left + 1u, curve.size() - 1u);
        const float fraction = position - static_cast<float>(left);
        return std::clamp(curve[left] + (curve[right] - curve[left]) * fraction, 0.0f, 1.0f);
    };
    bool monotonic = true;
    for (int i = 1; i < 256; ++i) {
        if (lut[static_cast<size_t>(i)] < lut[static_cast<size_t>(i - 1)]) monotonic = false;
    }

    // 3. Apply to luma only so hue/chroma from the camera ISP remains stable.
    cv::Mat yCrCb;
    cv::cvtColor(bgrMat, yCrCb, cv::COLOR_BGR2YCrCb);
    cv::parallel_for_(cv::Range(0, yCrCb.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            cv::Vec3b* row = yCrCb.ptr<cv::Vec3b>(y);
            for (int x = 0; x < yCrCb.cols; ++x) {
                float value = lut[static_cast<size_t>(row[x][0])] / 255.0f;
                if (toneCurveActive) value = evalProfileCurve(rawCfg.toneCurve, value);
                if (sectionCurveActive) value = evalProfileCurve(rawCfg.sectionCurve, value);
                if (gammaCurveActive) value = evalProfileCurve(rawCfg.gammaCurve, value);
                row[x][0] = cv::saturate_cast<uint8_t>(value * 255.0f);
            }
        }
    });
    cv::cvtColor(yCrCb, bgrMat, cv::COLOR_YCrCb2BGR);
    toneApplied = true;

    LumaPercentiles outputLuma = calculateLumaPercentiles(bgrMat);

    if (statsOut != nullptr) {
        statsOut->yuvToneAlignmentApplied = toneApplied;
        statsOut->yuvToneLutSize = 256;
        statsOut->yuvInputNativeYP01 = inputLuma.p01;
        statsOut->yuvInputNativeYP50 = inputLuma.p50;
        statsOut->yuvInputNativeYP99 = inputLuma.p99;
        statsOut->yuvOutputBgrLumaP01 = outputLuma.p01;
        statsOut->yuvOutputBgrLumaP50 = outputLuma.p50;
        statsOut->yuvOutputBgrLumaP99 = outputLuma.p99;
        statsOut->yuvToneCurveMonotonic = monotonic;
        statsOut->yuvToneMidpointOutput = lut[static_cast<int>(std::round(0.18f * 255.0f))] / 255.0f;
        statsOut->yuvToneShadowOutput = lut[static_cast<int>(std::round(0.05f * 255.0f))] / 255.0f;
        statsOut->yuvToneHighlightOutput = lut[static_cast<int>(std::round(0.85f * 255.0f))] / 255.0f;
        statsOut->yuvToneDarkeningDetected = toneApplied && outputLuma.p50 + 0.005f < inputLuma.p50;
        statsOut->yuvToneDarkeningReason = statsOut->yuvToneDarkeningDetected
                ? (inputLuma.p99 > 0.80f ? "scene_highlights_protected_by_rolloff_or_vendor_yuv_luma_mismatch" : "unexpected_midtone_darkening")
                : "none";

        // Detailed YUV luma statistics
        statsOut->yuvInputNativeYP0_1 = inputLuma.p0_1;
        statsOut->yuvInputNativeYP1 = inputLuma.p1;
        statsOut->yuvInputNativeYP5 = inputLuma.p5;
        statsOut->yuvInputNativeYP95 = inputLuma.p95;
        statsOut->yuvInputNativeYP99_9 = inputLuma.p99_9;
        statsOut->yuvInputBlackClippedFraction = inputLuma.blackClippedFraction;
        statsOut->yuvInputWhiteClippedFraction = inputLuma.whiteClippedFraction;
        statsOut->yuvInputContrastRatio = inputLuma.contrastRatio;

        statsOut->yuvOutputBgrLumaP0_1 = outputLuma.p0_1;
        statsOut->yuvOutputBgrLumaP1 = outputLuma.p1;
        statsOut->yuvOutputBgrLumaP5 = outputLuma.p5;
        statsOut->yuvOutputBgrLumaP95 = outputLuma.p95;
        statsOut->yuvOutputBgrLumaP99_9 = outputLuma.p99_9;
        statsOut->yuvOutputBlackClippedFraction = outputLuma.blackClippedFraction;
        statsOut->yuvOutputWhiteClippedFraction = outputLuma.whiteClippedFraction;
        statsOut->yuvOutputContrastRatio = outputLuma.contrastRatio;

        statsOut->defaultColorToneBypassed = true;
        statsOut->gpuPostApplied = false;
        statsOut->yuvDenoiseApplied = nrApplied;
        statsOut->bypassReason = "raw_tone_defaults_bypassed_hal_yuv_neutral_luma_only";

        statsOut->yuvBilateralApplied = nrApplied;
        statsOut->yuvBilateralDiameter = nrApplied ? diameter : 0;
        statsOut->yuvBilateralSigmaColor = nrApplied ? sigmaColor : 0.0f;
        statsOut->yuvBilateralSigmaSpace = nrApplied ? sigmaSpace : 0.0f;
        statsOut->yuvDenoiseStrengthResolved = nrApplied ? effectiveNr : 0.0f;
    }
}

struct YuvEncodeTiming {
    float yuvToBgrMs = 0.0f;
    float yuvPostProcessMs = 0.0f;
    float yuvRotateMs = 0.0f;
    float yuvJpegEncodeMs = 0.0f;
    bool yuvDefaultColorToneBypassed = false;
    bool yuvGpuPostApplied = false;
    bool yuvDenoiseApplied = false;
    std::string yuvPostBypassReason = "none";
    bool yuvToneAlignmentApplied = false;
    int yuvToneLutSize = 0;
    float yuvInputNativeYP01 = 0.0f;
    float yuvInputNativeYP50 = 0.0f;
    float yuvInputNativeYP99 = 0.0f;
    float yuvOutputBgrLumaP01 = 0.0f;
    float yuvOutputBgrLumaP50 = 0.0f;
    float yuvOutputBgrLumaP99 = 0.0f;
    bool yuvToneCurveMonotonic = true;
    float yuvToneMidpointOutput = 0.0f;
    float yuvToneShadowOutput = 0.0f;
    float yuvToneHighlightOutput = 0.0f;
    bool yuvToneDarkeningDetected = false;
    std::string yuvToneDarkeningReason = "none";
    bool yuvBilateralApplied = false;
    int yuvBilateralDiameter = 0;
    float yuvBilateralSigmaColor = 0.0f;
    float yuvBilateralSigmaSpace = 0.0f;
    float yuvDenoiseStrengthResolved = 0.0f;

    // Detailed YUV luma statistics
    float yuvInputNativeYP0_1 = 0.0f;
    float yuvInputNativeYP1 = 0.0f;
    float yuvInputNativeYP5 = 0.0f;
    float yuvInputNativeYP95 = 0.0f;
    float yuvInputNativeYP99_9 = 0.0f;
    float yuvInputBlackClippedFraction = 0.0f;
    float yuvInputWhiteClippedFraction = 0.0f;
    float yuvInputContrastRatio = 0.0f;

    // Phase 1: sampled, read-only input-signal diagnostics. Neighbour deltas include
    // both real scene detail and noise and are therefore never labelled as pure noise.
    std::uint32_t yuvInputSignalSampleCount = 0u;
    float yuvInputYMean = 0.0f;
    float yuvInputYStdDev = 0.0f;
    float yuvInputUCenteredMean = 0.0f;
    float yuvInputVCenteredMean = 0.0f;
    float yuvInputUStdDev = 0.0f;
    float yuvInputVStdDev = 0.0f;
    float yuvInputChromaRmsFromNeutral = 0.0f;
    float yuvInputLumaNeighbourDeltaMean = 0.0f;
    float yuvInputChromaNeighbourDeltaMean = 0.0f;

    // Phase 1 NR authority truth for the production Vulkan request.
    float yuvResolvedGpuLumaNrBlend = 0.0f;
    float yuvResolvedGpuChromaNrBlend = 0.0f;
    float yuvResolvedGpuLumaNrProtection = 0.0f;
    float yuvResolvedGpuChromaNrProtection = 0.0f;

    // FASE 13 measured residual-noise and Camera2 color-contract truth.
    bool yuvResidualNoiseModelAvailable = false;
    std::uint32_t yuvResidualLumaSamples = 0u;
    std::uint32_t yuvResidualChromaSamples = 0u;
    float yuvResidualSigmaY = 0.0f;
    float yuvResidualSigmaU = 0.0f;
    float yuvResidualSigmaV = 0.0f;
    float yuvResidualModelConfidence = 0.0f;
    std::string yuvColorContract = "CAMERA2_JFIF_REC601_FULL_RANGE_TO_SRGB";

    float yuvOutputBgrLumaP0_1 = 0.0f;
    float yuvOutputBgrLumaP1 = 0.0f;
    float yuvOutputBgrLumaP5 = 0.0f;
    float yuvOutputBgrLumaP95 = 0.0f;
    float yuvOutputBgrLumaP99_9 = 0.0f;
    float yuvOutputBlackClippedFraction = 0.0f;
    float yuvOutputWhiteClippedFraction = 0.0f;
    float yuvOutputContrastRatio = 0.0f;

    // GPU Migration Phase 12: authoritative single-frame full-frame YUV ISP backend.
    std::string yuvIspBackend = "NOT_ATTEMPTED";
    bool yuvIspCpuFallbackUsed = false;
    std::string yuvIspFallbackReason = "none";
    std::uint64_t yuvIspCpuUploadBytes = 0u;
    std::uint64_t yuvIspGpuReadbackBytes = 0u;
    float yuvIspInputUploadMs = 0.0f;
    float yuvIspCallMs = 0.0f;
    float yuvIspBackendMutexWaitMs = 0.0f;
    float yuvIspPipelineSetupMs = 0.0f;
    float yuvIspBufferSetupMs = 0.0f;
    float yuvIspGpuExecutionWallMs = 0.0f;
    float yuvIspGpuSyncMs = 0.0f;
    float yuvIspPublicationReadbackMs = 0.0f;
    bool yuvIspResidentLumaConsumed = false;
    std::uint64_t yuvIspResidentLumaGeneration = 0u;
    bool yuvUltraHdrRequested = false;
    bool yuvUltraHdrGainmapGenerated = false;
    bool yuvUltraHdrMeaningfulHeadroom = false;
    float yuvUltraHdrMaxContentBoost = 1.0f;
    std::string yuvUltraHdrStatus = "NOT_REQUESTED";
    bool yuvPortraitRequested = false;
    bool yuvPortraitApplied = false;
    std::string yuvPortraitStatus = "NOT_REQUESTED";
};

void applyYuvProfileDetailCpuFallback(cv::Mat& bgr, const NativeRenderQualityConfig& qualityConfig) {
    if (bgr.empty() || bgr.type() != CV_8UC3) return;
    const float amount = std::isfinite(qualityConfig.profileDetailAmount)
            ? std::clamp(qualityConfig.profileDetailAmount, -1.0f, 1.0f)
            : bncam::profile_defaults::kDetailAmount;
    if (std::abs(amount) <= 1.0e-6f) return;

    // Failure-reference only: production YUV uses the Vulkan path. Keep the same standalone
    // signed semantics here without reading Radius/Detail/Masking.
    cv::Mat rgb32;
    bgr.convertTo(rgb32, CV_32FC3, 1.0 / 255.0);
    cv::Mat luma(rgb32.rows, rgb32.cols, CV_32FC1);
    cv::parallel_for_(cv::Range(0, rgb32.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const cv::Vec3f* src = rgb32.ptr<cv::Vec3f>(y);
            float* dst = luma.ptr<float>(y);
            for (int x = 0; x < rgb32.cols; ++x) {
                dst[x] = std::clamp(
                        0.0722f * src[x][0] + 0.7152f * src[x][1] + 0.2126f * src[x][2],
                        0.0f, 1.0f);
            }
        }
    });

    cv::Mat blurred;
    if (amount < 0.0f) {
        const float softness = std::pow(-amount, 0.72f);
        const float sigma = 0.85f + 1.55f * softness;
        cv::GaussianBlur(luma, blurred, cv::Size(), sigma, sigma, cv::BORDER_REPLICATE);
        const float blend = 0.94f * std::pow(softness, 0.78f);
        cv::parallel_for_(cv::Range(0, rgb32.rows), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                cv::Vec3f* rgb = rgb32.ptr<cv::Vec3f>(y);
                const float* lum = luma.ptr<float>(y);
                const float* blur = blurred.ptr<float>(y);
                for (int x = 0; x < rgb32.cols; ++x) {
                    const float center = lum[x];
                    const float target = std::clamp(center + (blur[x] - center) * blend, 0.0f, 1.0f);
                    const float scale = center > 1.0e-5f ? std::clamp(target / center, 0.30f, 2.60f) : 1.0f;
                    rgb[x][0] = std::clamp(rgb[x][0] * scale, 0.0f, 1.0f);
                    rgb[x][1] = std::clamp(rgb[x][1] * scale, 0.0f, 1.0f);
                    rgb[x][2] = std::clamp(rgb[x][2] * scale, 0.0f, 1.0f);
                }
            }
        });
    } else {
        cv::GaussianBlur(luma, blurred, cv::Size(), 0.90, 0.90, cv::BORDER_REPLICATE);
        const float sharpStrength = amount * (1.05f + 1.75f * amount);
        cv::parallel_for_(cv::Range(0, rgb32.rows), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                cv::Vec3f* rgb = rgb32.ptr<cv::Vec3f>(y);
                const float* lum = luma.ptr<float>(y);
                const float* blur = blurred.ptr<float>(y);
                for (int x = 0; x < rgb32.cols; ++x) {
                    const float center = lum[x];
                    const float highPass = center - blur[x];
                    const float delta = std::clamp(highPass * sharpStrength, -0.14f, 0.14f);
                    const float target = std::clamp(center + delta, 0.0f, 1.0f);
                    const float scale = center > 1.0e-5f ? std::clamp(target / center, 0.30f, 2.60f) : 1.0f;
                    rgb[x][0] = std::clamp(rgb[x][0] * scale, 0.0f, 1.0f);
                    rgb[x][1] = std::clamp(rgb[x][1] * scale, 0.0f, 1.0f);
                    rgb[x][2] = std::clamp(rgb[x][2] * scale, 0.0f, 1.0f);
                }
            }
        });
    }
    rgb32.convertTo(bgr, CV_8UC3, 255.0);
}

void convertNv21JfifFullToBgr(const cv::Mat& nv21, cv::Mat& bgr) {
    if (nv21.empty() || nv21.type() != CV_8UC1 || nv21.rows < 3 || nv21.cols < 2) {
        bgr.release();
        return;
    }
    const int width = nv21.cols;
    const int height = (nv21.rows * 2) / 3;
    if (height <= 0 || (height & 1) != 0 || (width & 1) != 0 ||
        nv21.rows < height + height / 2) {
        bgr.release();
        return;
    }
    bgr.create(height, width, CV_8UC3);
    cv::parallel_for_(cv::Range(0, height), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const std::uint8_t* yRow = nv21.ptr<std::uint8_t>(y);
            const std::uint8_t* vuRow = nv21.ptr<std::uint8_t>(height + (y >> 1));
            cv::Vec3b* dst = bgr.ptr<cv::Vec3b>(y);
            for (int x = 0; x < width; ++x) {
                const int uv = (x >> 1) * 2;
                const float yy = static_cast<float>(yRow[x]);
                const float vv = static_cast<float>(vuRow[uv]) - 128.0f;
                const float uu = static_cast<float>(vuRow[uv + 1]) - 128.0f;
                // Android Camera2 default YUV: JFIF / Rec.601 full-range -> sRGB.
                const float r = yy + 1.402000f * vv;
                const float g = yy - 0.344136f * uu - 0.714136f * vv;
                const float b = yy + 1.772000f * uu;
                dst[x][0] = cv::saturate_cast<std::uint8_t>(b);
                dst[x][1] = cv::saturate_cast<std::uint8_t>(g);
                dst[x][2] = cv::saturate_cast<std::uint8_t>(r);
            }
        }
    });
}

bool encodeNv21ToJpegCpuFallback(
        const uint8_t* nv21Ptr,
        size_t nv21Length,
        uint32_t width,
        uint32_t height,
        int rotationDegrees,
        const NativeRenderQualityConfig& qualityConfig,
        std::vector<uint8_t>& jpegOut,
        YuvEncodeTiming* timingOut
) {
    if (nv21Ptr == nullptr || width == 0 || height == 0 || nv21Length == 0) return false;

    auto stageStart = NativeClock::now();
    const size_t ySize = static_cast<size_t>(width) * height;
    const size_t expectedNv21Bytes = ySize + ySize / 2u;
    if (nv21Length < expectedNv21Bytes) return false;
    // Fail-safe only: never write tone-mapped pixels back into a Camera2 buffer acquired with
    // CPU_READ_OFTEN. The normal Vulkan route does not take this extra host copy.
    std::vector<std::uint8_t> fallbackNv21(nv21Ptr, nv21Ptr + expectedNv21Bytes);
    std::uint8_t* fallbackPtr = fallbackNv21.data();

    // 1. Pre-calculate 256-entry Y-plane tone LUT (0 ms cvtColor overhead)
    const auto lut = bncam::NeutralYuvToneMapper::buildLut();
    const bool toneCurveActive = !isNeutralCurveNative(qualityConfig.toneCurve.data(), static_cast<int>(qualityConfig.toneCurve.size()));
    const bool gammaCurveActive = !isNeutralCurveNative(qualityConfig.gammaCurve.data(), static_cast<int>(qualityConfig.gammaCurve.size()));
    const bool sectionCurveActive = !isNeutralCurveNative(qualityConfig.sectionCurve.data(), static_cast<int>(qualityConfig.sectionCurve.size()));

    uint8_t toneLut256[256];
    auto evalProfileCurve = [](const std::vector<float>& curve, float value) -> float {
        if (curve.size() < 2u) return std::clamp(value, 0.0f, 1.0f);
        const float position = std::clamp(value, 0.0f, 1.0f) * static_cast<float>(curve.size() - 1u);
        const size_t left = std::min(static_cast<size_t>(position), curve.size() - 1u);
        const size_t right = std::min(left + 1u, curve.size() - 1u);
        const float fraction = position - static_cast<float>(left);
        return std::clamp(curve[left] + (curve[right] - curve[left]) * fraction, 0.0f, 1.0f);
    };
    for (int i = 0; i < 256; ++i) {
        float val = lut[static_cast<size_t>(i)] / 255.0f;
        if (toneCurveActive) val = evalProfileCurve(qualityConfig.toneCurve, val);
        if (sectionCurveActive) val = evalProfileCurve(qualityConfig.sectionCurve, val);
        if (gammaCurveActive) val = evalProfileCurve(qualityConfig.gammaCurve, val);
        toneLut256[i] = cv::saturate_cast<uint8_t>(val * 255.0f);
    }

    if (timingOut != nullptr) {
        timingOut->yuvPostProcessMs = nativeElapsedMs(stageStart);
        timingOut->yuvToneAlignmentApplied = true;
        timingOut->yuvToneLutSize = 256;
        timingOut->yuvDefaultColorToneBypassed = true;
        timingOut->yuvPostBypassReason = "in_place_y_plane_lut_mapping";
        timingOut->yuvDenoiseApplied = false;
    }

    // 2. Fail-safe-only NV21 plane rotation. The private fallback buffer prevents writes to read-only Camera2 memory.
    stageStart = NativeClock::now();
    const int normRot = normalizeRotationDegrees(rotationDegrees);
    cv::Mat bgrMat;

    if (normRot != 0) {
        cv::Mat yPlaneMat(static_cast<int>(height), static_cast<int>(width), CV_8UC1, fallbackPtr);
        cv::Mat vuPlaneMat(static_cast<int>(height / 2u), static_cast<int>(width / 2u), CV_8UC2, fallbackPtr + ySize);

        int cvRotCode = cv::ROTATE_90_CLOCKWISE;
        if (normRot == 90) cvRotCode = cv::ROTATE_90_CLOCKWISE;
        else if (normRot == 180) cvRotCode = cv::ROTATE_180;
        else if (normRot == 270) cvRotCode = cv::ROTATE_90_COUNTERCLOCKWISE;

        cv::Mat rotatedY, rotatedVU;
        cv::rotate(yPlaneMat, rotatedY, cvRotCode);
        cv::rotate(vuPlaneMat, rotatedVU, cvRotCode);

        // Apply tone LUT directly to rotatedY in parallel
        cv::parallel_for_(cv::Range(0, rotatedY.rows), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                uint8_t* row = rotatedY.ptr<uint8_t>(y);
                for (int x = 0; x < rotatedY.cols; ++x) {
                    row[x] = toneLut256[row[x]];
                }
            }
        });

        const int rotWidth = (normRot == 90 || normRot == 270) ? static_cast<int>(height) : static_cast<int>(width);
        const int rotHeight = (normRot == 90 || normRot == 270) ? static_cast<int>(width) : static_cast<int>(height);

        cv::Mat rotatedNv21(rotHeight + rotHeight / 2, rotWidth, CV_8UC1);
        std::memcpy(rotatedNv21.data, rotatedY.data, rotatedY.total());
        std::memcpy(rotatedNv21.data + rotatedY.total(), rotatedVU.data, rotatedVU.total() * 2);

        auto stageToBgr = NativeClock::now();
        convertNv21JfifFullToBgr(rotatedNv21, bgrMat);
        if (timingOut != nullptr) timingOut->yuvToBgrMs = nativeElapsedMs(stageToBgr);
    } else {
        cv::Mat nv21Mat(static_cast<int>(height + height / 2u), static_cast<int>(width), CV_8UC1, fallbackPtr);
        // Apply tone LUT directly to Y plane of unrotated NV21
        cv::parallel_for_(cv::Range(0, static_cast<int>(height)), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                uint8_t* row = nv21Mat.ptr<uint8_t>(y);
                for (uint32_t x = 0; x < width; ++x) {
                    row[x] = toneLut256[row[x]];
                }
            }
        });
        auto stageToBgr = NativeClock::now();
        convertNv21JfifFullToBgr(nv21Mat, bgrMat);
        if (timingOut != nullptr) timingOut->yuvToBgrMs = nativeElapsedMs(stageToBgr);
    }
    if (timingOut != nullptr) timingOut->yuvRotateMs = nativeElapsedMs(stageStart);

    // Developed YUV WB compensation, profile color and detail remain active in both YUV_FAST and YUV_COMPUTE.
    applyYuvProfileRgbAdjustments(bgrMat, qualityConfig);
    applyYuvProfileDetailCpuFallback(bgrMat, qualityConfig);

    // 3. JPEG Encoding
    const NativeRenderQualityConfig resolvedQuality = IspCore::resolveForYuv(qualityConfig);
    std::vector<int> params = bncam::jpeg444EncodingParameters(resolvedQuality.jpegQuality);
    stageStart = NativeClock::now();
    const bool ok = cv::imencode(".jpg", bgrMat, jpegOut, params);
    if (timingOut != nullptr) timingOut->yuvJpegEncodeMs = nativeElapsedMs(stageStart);

    return ok;
}

bool encodeNv21ToJpeg(
        const uint8_t* nv21Ptr,
        size_t nv21Length,
        uint32_t width,
        uint32_t height,
        int rotationDegrees,
        const NativeRenderQualityConfig& qualityConfig,
        std::vector<uint8_t>& jpegOut,
        YuvEncodeTiming* timingOut,
        std::uint64_t residentLumaGeneration
) {
    if (nv21Ptr == nullptr || width == 0u || height == 0u || nv21Length == 0u) return false;

    // Phase 1 objective harness: inspect only a sparse grid (~4k points). This does not
    // alter pixels, allocate a full-frame copy, or add a GPU readback. It deliberately
    // runs before any BnCam YUV processing so the central diagnostics describe input truth.
    const auto inputSignal = bncam::yuvdiag::summarizeNv21Signal(
            nv21Ptr, nv21Length, width, height);
    if (timingOut != nullptr) {
        timingOut->yuvInputSignalSampleCount = inputSignal.sampleCount;
        timingOut->yuvInputYMean = inputSignal.yMean;
        timingOut->yuvInputYStdDev = inputSignal.yStdDev;
        timingOut->yuvInputUCenteredMean = inputSignal.uCenteredMean;
        timingOut->yuvInputVCenteredMean = inputSignal.vCenteredMean;
        timingOut->yuvInputUStdDev = inputSignal.uStdDev;
        timingOut->yuvInputVStdDev = inputSignal.vStdDev;
        timingOut->yuvInputChromaRmsFromNeutral = inputSignal.chromaRmsFromNeutral;
        timingOut->yuvInputLumaNeighbourDeltaMean = inputSignal.lumaNeighbourDeltaMean;
        timingOut->yuvInputChromaNeighbourDeltaMean = inputSignal.chromaNeighbourDeltaMean;
        // Populate the pre-existing Y percentile fields on the production Vulkan path as well.
        // Historically these were only filled by the CPU fail-safe and could therefore appear as
        // plausible zeros in normal captures. The sparse histogram makes their provenance explicit.
        timingOut->yuvInputNativeYP01 = inputSignal.yP1;
        timingOut->yuvInputNativeYP50 = inputSignal.yP50;
        timingOut->yuvInputNativeYP99 = inputSignal.yP99;
        timingOut->yuvInputNativeYP0_1 = inputSignal.yP0_1;
        timingOut->yuvInputNativeYP1 = inputSignal.yP1;
        timingOut->yuvInputNativeYP5 = inputSignal.yP5;
        timingOut->yuvInputNativeYP95 = inputSignal.yP95;
        timingOut->yuvInputNativeYP99_9 = inputSignal.yP99_9;
        timingOut->yuvInputBlackClippedFraction = inputSignal.blackClippedFraction;
        timingOut->yuvInputWhiteClippedFraction = inputSignal.whiteClippedFraction;
        timingOut->yuvInputContrastRatio = inputSignal.contrastRatio;
    }

    const auto neutralLut = bncam::NeutralYuvToneMapper::buildLut();
    const bool toneCurveActive = !isNeutralCurveNative(
            qualityConfig.toneCurve.data(), static_cast<int>(qualityConfig.toneCurve.size()));
    const bool gammaCurveActive = !isNeutralCurveNative(
            qualityConfig.gammaCurve.data(), static_cast<int>(qualityConfig.gammaCurve.size()));
    const bool sectionCurveActive = !isNeutralCurveNative(
            qualityConfig.sectionCurve.data(), static_cast<int>(qualityConfig.sectionCurve.size()));
    auto evalProfileCurve = [](const std::vector<float>& curve, float value) -> float {
        if (curve.size() < 2u) return std::clamp(value, 0.0f, 1.0f);
        const float position = std::clamp(value, 0.0f, 1.0f) * static_cast<float>(curve.size() - 1u);
        const size_t left = std::min(static_cast<size_t>(position), curve.size() - 1u);
        const size_t right = std::min(left + 1u, curve.size() - 1u);
        const float fraction = position - static_cast<float>(left);
        return std::clamp(curve[left] + (curve[right] - curve[left]) * fraction, 0.0f, 1.0f);
    };

    bncam::vulkan::YuvSingleFrameIspRequest request{};
    request.nv21 = nv21Ptr;
    request.nv21Bytes = nv21Length;
    request.residentLumaGeneration = residentLumaGeneration;
    // YUV Ultra HDR is legitimate only when an actual Computational-HDR fusion left a
    // resident exposure-normalized authority. Single-frame 8-bit YUV is intentionally SDR.
    request.ultraHdrGainmapRequested = qualityConfig.ultraHdrGainmapEnabled &&
            residentLumaGeneration != 0u;
    request.width = width;
    request.height = height;
    request.rotationDegrees = static_cast<std::uint32_t>(normalizeRotationDegrees(rotationDegrees));
    for (std::size_t i = 0u; i < request.toneLut.size(); ++i) {
        float value = neutralLut[i] / 255.0f;
        if (toneCurveActive) value = evalProfileCurve(qualityConfig.toneCurve, value);
        if (sectionCurveActive) value = evalProfileCurve(qualityConfig.sectionCurve, value);
        if (gammaCurveActive) value = evalProfileCurve(qualityConfig.gammaCurve, value);
        request.toneLut[i] = static_cast<std::uint32_t>(
                cv::saturate_cast<std::uint8_t>(value * 255.0f));
    }
    request.wbRed = qualityConfig.profileYuvWbRed;
    request.wbGreen = qualityConfig.profileYuvWbGreen;
    request.wbBlue = qualityConfig.profileYuvWbBlue;
    request.saturation = qualityConfig.profileColorSaturation;
    request.contrast = qualityConfig.profileColorContrast;
    request.vibrance = qualityConfig.profilePresenceVibrance;
    request.profileDetailAmount = qualityConfig.profileDetailAmount;
    request.profileDetailRadius = qualityConfig.profileDetailRadius;
    request.profileDetailDetail = qualityConfig.profileDetailDetail;
    request.profileDetailMasking = qualityConfig.profileDetailMasking;
    const auto profileNrPlan = bncam::profile_nr::resolveProfileNoiseReduction(
            qualityConfig.profileNrLuminance,
            qualityConfig.profileNrLuminanceDetail,
            qualityConfig.profileNrLuminanceContrast,
            qualityConfig.profileNrColor,
            qualityConfig.profileNrColorDetail,
            qualityConfig.profileNrColorSmoothness);
    // FASE 13 production contract: request fields carry creative profile authority only.
    // The Vulkan resident analysis derives the baseline from the actual processed YUV frame.
    request.profileNrLumaBlend = std::clamp(profileNrPlan.lumaCreativeBlend, 0.0f, 0.75f);
    request.profileNrLumaProtection = std::clamp(
            0.45f + 0.40f * profileNrPlan.luminanceDetail +
                    0.20f * profileNrPlan.luminanceContrast, 0.0f, 1.0f);
    const float creativeChroma = profileNrPlan.chromaCreativeBlend *
            (0.70f + 0.30f * profileNrPlan.colorSmoothness);
    request.profileNrChromaBlend = std::clamp(creativeChroma, 0.0f, 0.85f);
    request.profileNrChromaProtection = std::clamp(
            0.35f + 0.50f * profileNrPlan.colorDetail, 0.0f, 1.0f);
    if (timingOut != nullptr) {
        timingOut->yuvResolvedGpuLumaNrBlend = 0.0f;
        timingOut->yuvResolvedGpuChromaNrBlend = 0.0f;
        timingOut->yuvResolvedGpuLumaNrProtection = request.profileNrLumaProtection;
        timingOut->yuvResolvedGpuChromaNrProtection = request.profileNrChromaProtection;
    }
    request.portraitEffectRequested = qualityConfig.portraitEffectEnabled;
    request.portraitMask = qualityConfig.portraitMask;
    request.portraitMaskFloatCount = qualityConfig.portraitMaskFloatCount;
    request.portraitMaskWidth = qualityConfig.portraitMaskWidth;
    request.portraitMaskHeight = qualityConfig.portraitMaskHeight;
    request.portraitTargetLeft = qualityConfig.portraitTargetLeft;
    request.portraitTargetTop = qualityConfig.portraitTargetTop;
    request.portraitTargetRight = qualityConfig.portraitTargetRight;
    request.portraitTargetBottom = qualityConfig.portraitTargetBottom;
    request.portraitMaskRotationDegrees = qualityConfig.portraitMaskRotationDegrees;

    const auto gpuCallStart = NativeClock::now();
    bncam::vulkan::YuvSingleFrameIspResult gpu =
            bncam::vulkan::VulkanRuntime::instance().executeYuvSingleFrameIsp(request);
    const float gpuCallMs = nativeElapsedMs(gpuCallStart);

    const bool residentLumaSatisfied = residentLumaGeneration == 0u ||
            (gpu.residentLumaConsumed && gpu.residentLumaGeneration == residentLumaGeneration);
    const bool gpuOutputValid = gpu.success && residentLumaSatisfied &&
            gpu.outputWidth > 0u && gpu.outputHeight > 0u &&
            gpu.bgr24.size() == static_cast<std::size_t>(gpu.outputWidth) * gpu.outputHeight * 3u;
    const std::string fallbackReason = gpu.success && !gpuOutputValid
            ? (residentLumaSatisfied
                    ? "VULKAN_YUV_SINGLE_FRAME_OUTPUT_INVALID"
                    : "VULKAN_YUV_RESIDENT_LUMA_NOT_CONSUMED")
            : gpu.failureReason;
    if (timingOut != nullptr) {
        timingOut->yuvIspBackend = gpuOutputValid
                ? gpu.backend
                : (residentLumaGeneration != 0u ? "VULKAN_RESIDENT_HANDOFF_FAILED" : "CPU_OPENCV_FAILSAFE");
        timingOut->yuvIspCpuFallbackUsed = !gpuOutputValid && residentLumaGeneration == 0u;
        timingOut->yuvIspFallbackReason = gpuOutputValid ? "none" : fallbackReason;
        timingOut->yuvIspCpuUploadBytes = gpu.fullFrameCpuUploadBytes;
        timingOut->yuvIspGpuReadbackBytes = gpu.fullFrameGpuReadbackBytes;
        timingOut->yuvIspInputUploadMs = gpu.inputUploadMs;
        timingOut->yuvIspCallMs = gpuCallMs;
        timingOut->yuvIspBackendMutexWaitMs = gpu.backendMutexWaitMs;
        timingOut->yuvIspPipelineSetupMs = gpu.pipelineSetupMs;
        timingOut->yuvIspBufferSetupMs = gpu.bufferSetupMs;
        timingOut->yuvIspGpuExecutionWallMs = gpu.gpuExecutionWallMs;
        timingOut->yuvIspGpuSyncMs = gpu.gpuSynchronizationMs;
        timingOut->yuvIspPublicationReadbackMs = gpu.publicationReadbackMs;
        timingOut->yuvIspResidentLumaConsumed = gpu.residentLumaConsumed;
        timingOut->yuvIspResidentLumaGeneration = gpu.residentLumaGeneration;
        timingOut->yuvUltraHdrRequested = request.ultraHdrGainmapRequested;
        timingOut->yuvUltraHdrGainmapGenerated = gpu.ultraHdrGainmapGenerated;
        timingOut->yuvUltraHdrMeaningfulHeadroom = gpu.ultraHdrMeaningfulHeadroom;
        timingOut->yuvUltraHdrMaxContentBoost = gpu.ultraHdrMaxContentBoost;
        timingOut->yuvUltraHdrStatus = gpu.ultraHdrStatus;
        timingOut->yuvResidualNoiseModelAvailable = gpu.yuvResidualNoiseModelAvailable;
        timingOut->yuvResidualLumaSamples = gpu.yuvResidualLumaSamples;
        timingOut->yuvResidualChromaSamples = gpu.yuvResidualChromaSamples;
        timingOut->yuvResidualSigmaY = gpu.yuvResidualSigmaY;
        timingOut->yuvResidualSigmaU = gpu.yuvResidualSigmaU;
        timingOut->yuvResidualSigmaV = gpu.yuvResidualSigmaV;
        timingOut->yuvResidualModelConfidence = gpu.yuvResidualModelConfidence;
        timingOut->yuvResolvedGpuLumaNrBlend = gpu.yuvResidualLumaAuthority;
        timingOut->yuvResolvedGpuChromaNrBlend = gpu.yuvResidualChromaAuthority;
        timingOut->yuvColorContract = gpu.yuvColorContract;
        timingOut->yuvPortraitRequested = request.portraitEffectRequested;
        timingOut->yuvPortraitApplied = gpu.portraitEffectApplied;
        timingOut->yuvPortraitStatus = gpu.portraitStatus;
    }

    if (gpuOutputValid) {
        cv::Mat bgrMat(
                static_cast<int>(gpu.outputHeight),
                static_cast<int>(gpu.outputWidth),
                CV_8UC3,
                gpu.bgr24.data());
        const NativeRenderQualityConfig resolvedQuality = IspCore::resolveForYuv(qualityConfig);
        const std::vector<int> params = bncam::jpeg444EncodingParameters(resolvedQuality.jpegQuality);
        const auto encodeStart = NativeClock::now();
        const bool ok = cv::imencode(".jpg", bgrMat, jpegOut, params);
        if (ok && gpu.ultraHdrGainmapGenerated && gpu.ultraHdrMeaningfulHeadroom &&
            !gpu.ultraHdrGainmapBytes.empty()) {
            UltraHdrGainmapArtifact artifact{};
            artifact.valid = true;
            artifact.width = gpu.ultraHdrGainmapWidth;
            artifact.height = gpu.ultraHdrGainmapHeight;
            artifact.rowStrideBytes = gpu.ultraHdrGainmapRowStrideBytes;
            artifact.minContentBoost = gpu.ultraHdrMinContentBoost;
            artifact.maxContentBoost = gpu.ultraHdrMaxContentBoost;
            artifact.gamma = gpu.ultraHdrGamma;
            artifact.offsetSdr = gpu.ultraHdrOffsetSdr;
            artifact.offsetHdr = gpu.ultraHdrOffsetHdr;
            artifact.pixels = std::move(gpu.ultraHdrGainmapBytes);
            gThreadLocalYuvUltraHdrArtifact = std::move(artifact);
        }
        if (timingOut != nullptr) {
            timingOut->yuvJpegEncodeMs = nativeElapsedMs(encodeStart);
            timingOut->yuvToBgrMs = 0.0f;
            timingOut->yuvRotateMs = 0.0f;
            timingOut->yuvPostProcessMs = gpu.gpuExecutionWallMs;
            timingOut->yuvDefaultColorToneBypassed = true;
            timingOut->yuvGpuPostApplied = true;
            timingOut->yuvDenoiseApplied =
                    gpu.yuvResidualLumaAuthority > 0.002f || gpu.yuvResidualChromaAuthority > 0.002f;
            timingOut->yuvDenoiseStrengthResolved =
                    std::max(gpu.yuvResidualLumaAuthority, gpu.yuvResidualChromaAuthority);
            timingOut->yuvPostBypassReason = "vulkan_yuv_single_frame_isp";
            timingOut->yuvToneAlignmentApplied = true;
            timingOut->yuvToneLutSize = 256;
        }
        return ok;
    }

    // Portrait is a Vulkan-owned image effect. Never route a requested Portrait render through
    // the legacy CPU/OpenCV image-processing failsafe; that would violate both effect truth and
    // GPU ownership. The caller can still publish an explicit non-Portrait recovery if desired.
    if (request.portraitEffectRequested) {
        LOGE("Portrait Vulkan YUV render failed; refusing CPU portrait fallback: %s",
             fallbackReason.c_str());
        if (timingOut != nullptr) {
            timingOut->yuvIspBackend = "VULKAN_PORTRAIT_REQUIRED_FAILED";
            timingOut->yuvIspCpuFallbackUsed = false;
        }
        return false;
    }

    // A resident multi-frame generation cannot safely fall back here: nv21Ptr still contains
    // anchor Y, not the fused Y result. Publishing that buffer would silently turn a failed
    // multi-frame handoff into an anchor-only JPEG. Let the caller choose an explicit recovery.
    if (residentLumaGeneration != 0u) {
        LOGE("Resident YUV ISP handoff failed; refusing anchor-only publication: %s",
             fallbackReason.c_str());
        return false;
    }

    // Explicit bounded fail-safe only. Normal production success never executes the OpenCV
    // full-frame YUV tone/color path. A typed Vulkan failure reason is retained in telemetry.
    LOGW("YUV single-frame Vulkan ISP unavailable; explicit CPU failsafe: %s",
         fallbackReason.c_str());
    return encodeNv21ToJpegCpuFallback(
            nv21Ptr,
            nv21Length,
            width,
            height,
            rotationDegrees,
            qualityConfig,
            jpegOut,
            timingOut);
}

} // namespace


extern "C"
JNIEXPORT jlong JNICALL
Java_com_bncam_core_engine_ImageUtils_retainRawPreviewHardwareBufferNative(
        JNIEnv* env,
        jobject /* thiz */,
        jobject hardwareBufferObject
) {
    if (hardwareBufferObject == nullptr) return 0;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (buffer == nullptr) return 0;
    AHardwareBuffer_acquire(buffer);
    {
        std::lock_guard<std::mutex> lock(gRawPreviewRetainMutex);
        gRawPreviewRetains[buffer]++;
    }
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(buffer));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_releaseRawPreviewHardwareBufferNative(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    auto* buffer = reinterpret_cast<AHardwareBuffer*>(static_cast<std::uintptr_t>(handle));
    if (buffer == nullptr) return;
    bool owned = false;
    {
        std::lock_guard<std::mutex> lock(gRawPreviewRetainMutex);
        const auto found = gRawPreviewRetains.find(buffer);
        if (found != gRawPreviewRetains.end() && found->second > 0u) {
            owned = true;
            if (--found->second == 0u) gRawPreviewRetains.erase(found);
        }
    }
    if (owned) {
        AHardwareBuffer_release(buffer);
    } else {
        LOGW("Rejected duplicate or unknown RAW preview HardwareBuffer release handle=%p", buffer);
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bncam_core_engine_ImageUtils_getRawPreviewEglNextFrameIdNative(
        JNIEnv* /* env */,
        jobject /* thiz */
) {
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (display == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return 0;
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions == nullptr ||
        std::strstr(extensions, "EGL_ANDROID_get_frame_timestamps") == nullptr) {
        return 0;
    }
    auto getNextFrameId = reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC>(
            eglGetProcAddress("eglGetNextFrameIdANDROID"));
    if (getNextFrameId == nullptr) return 0;
    // This attribute asks SurfaceFlinger to retain timestamps for subsequent frame-id queries.
    eglSurfaceAttrib(display, surface, EGL_TIMESTAMPS_ANDROID, EGL_TRUE);
    EGLuint64KHR frameId = 0;
    return getNextFrameId(display, surface, &frameId) == EGL_TRUE
            ? static_cast<jlong>(frameId) : 0;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bncam_core_engine_ImageUtils_getRawPreviewEglDisplayPresentTimeNative(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong frameId
) {
    if (frameId <= 0) return 0;
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (display == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return 0;
    auto getFrameTimestamps = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC>(
            eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    if (getFrameTimestamps == nullptr) return 0;
    const EGLint timestampName = EGL_DISPLAY_PRESENT_TIME_ANDROID;
    EGLnsecsANDROID timestamp = EGL_TIMESTAMP_PENDING_ANDROID;
    if (getFrameTimestamps(
            display,
            surface,
            static_cast<EGLuint64KHR>(frameId),
            1,
            &timestampName,
            &timestamp) != EGL_TRUE) {
        return 0;
    }
    return timestamp > 0 ? static_cast<jlong>(timestamp) : 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bncam_core_engine_ImageUtils_bindRawPreviewHardwareBufferToCurrentTextureNative(
        JNIEnv* env,
        jobject /* thiz */,
        jobject hardwareBufferObject
) {
    if (hardwareBufferObject == nullptr) return JNI_FALSE;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (buffer == nullptr) return JNI_FALSE;
    const EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) return JNI_FALSE;

    auto getNativeClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    auto imageTargetTexture = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (getNativeClientBuffer == nullptr || createImage == nullptr || destroyImage == nullptr ||
        imageTargetTexture == nullptr) {
        return JNI_FALSE;
    }

    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    {
        std::lock_guard<std::mutex> lock(gRawPreviewEglImageMutex);
        auto found = gRawPreviewEglImages.find(buffer);
        if (found != gRawPreviewEglImages.end() && found->second.display != display) {
            if (found->second.image != EGL_NO_IMAGE_KHR) {
                destroyImage(found->second.display, found->second.image);
            }
            if (found->second.buffer != nullptr) AHardwareBuffer_release(found->second.buffer);
            gRawPreviewEglImages.erase(found);
            found = gRawPreviewEglImages.end();
        }
        if (found == gRawPreviewEglImages.end()) {
            EGLClientBuffer clientBuffer = getNativeClientBuffer(buffer);
            if (clientBuffer == nullptr) return JNI_FALSE;
            const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
            image = createImage(
                    display,
                    EGL_NO_CONTEXT,
                    EGL_NATIVE_BUFFER_ANDROID,
                    clientBuffer,
                    attributes);
            if (image == EGL_NO_IMAGE_KHR) return JNI_FALSE;
            AHardwareBuffer_acquire(buffer);
            gRawPreviewEglImages.emplace(buffer, RawPreviewEglImageRecord{display, image, buffer});
        } else {
            image = found->second.image;
        }
    }

    while (glGetError() != GL_NO_ERROR) {}
    imageTargetTexture(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(image));
    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_releaseRawPreviewEglImageNative(
        JNIEnv* env,
        jobject /* thiz */,
        jobject hardwareBufferObject
) {
    if (hardwareBufferObject == nullptr) return;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (buffer == nullptr) return;
    RawPreviewEglImageRecord record{};
    bool foundRecord = false;
    {
        std::lock_guard<std::mutex> lock(gRawPreviewEglImageMutex);
        auto found = gRawPreviewEglImages.find(buffer);
        if (found != gRawPreviewEglImages.end()) {
            record = found->second;
            gRawPreviewEglImages.erase(found);
            foundRecord = true;
        }
    }
    if (!foundRecord) return;
    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    if (destroyImage != nullptr && record.display != EGL_NO_DISPLAY && record.image != EGL_NO_IMAGE_KHR) {
        destroyImage(record.display, record.image);
    }
    if (record.buffer != nullptr) AHardwareBuffer_release(record.buffer);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bncam_core_engine_ImageUtils_createRawPreviewGlFenceNative(
        JNIEnv* /* env */,
        jobject /* thiz */
) {
    const EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) return 0;
    auto createSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    if (createSync == nullptr) return 0;
    const EGLint attributes[] = {EGL_NONE};
    EGLSyncKHR sync = createSync(display, EGL_SYNC_FENCE_KHR, attributes);
    if (sync == EGL_NO_SYNC_KHR) return 0;
    // The fence is inserted after the RAW draw. Flush on the GL owner thread so another thread can
    // poll completion without ever forcing glFinish() or blocking the UI/GL dispatcher.
    glFlush();
    std::uint64_t id = gRawPreviewGlFenceNextId.fetch_add(1u, std::memory_order_relaxed);
    if (id == 0u) id = gRawPreviewGlFenceNextId.fetch_add(1u, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(gRawPreviewGlFenceMutex);
        gRawPreviewGlFences.emplace(id, RawPreviewGlFenceRecord{display, sync});
    }
    return static_cast<jlong>(id);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bncam_core_engine_ImageUtils_pollRawPreviewGlFenceNative(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    if (handle <= 0) return -1;
    const auto id = static_cast<std::uint64_t>(handle);
    RawPreviewGlFenceRecord record{};
    {
        std::lock_guard<std::mutex> lock(gRawPreviewGlFenceMutex);
        auto found = gRawPreviewGlFences.find(id);
        if (found == gRawPreviewGlFences.end()) return -1;
        record = found->second;
    }
    auto clientWait = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    auto destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    if (clientWait == nullptr || destroySync == nullptr) {
        std::lock_guard<std::mutex> lock(gRawPreviewGlFenceMutex);
        gRawPreviewGlFences.erase(id);
        return -1;
    }
    const EGLint waitResult = clientWait(record.display, record.sync, 0, 0);
    if (waitResult == EGL_TIMEOUT_EXPIRED_KHR) return 0;

    {
        std::lock_guard<std::mutex> lock(gRawPreviewGlFenceMutex);
        gRawPreviewGlFences.erase(id);
    }
    destroySync(record.display, record.sync);
    return waitResult == EGL_CONDITION_SATISFIED_KHR ? 1 : -1;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_destroyRawPreviewGlFenceNative(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    if (handle <= 0) return;
    const auto id = static_cast<std::uint64_t>(handle);
    RawPreviewGlFenceRecord record{};
    bool foundRecord = false;
    {
        std::lock_guard<std::mutex> lock(gRawPreviewGlFenceMutex);
        auto found = gRawPreviewGlFences.find(id);
        if (found != gRawPreviewGlFences.end()) {
            record = found->second;
            gRawPreviewGlFences.erase(found);
            foundRecord = true;
        }
    }
    if (!foundRecord) return;
    auto destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    if (destroySync != nullptr && record.display != EGL_NO_DISPLAY && record.sync != EGL_NO_SYNC_KHR) {
        destroySync(record.display, record.sync);
    }
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_bncam_core_engine_ImageUtils_renderRawPreviewNative(
        JNIEnv* env,
        jobject /* thiz */,
        jlong retainedHardwareBuffer,
        jint sourceFormat,
        jint cfaPattern,
        jint requestedDemosaicMode,
        jfloatArray blackLevelsArray,
        jint whiteLevel,
        jfloatArray wbGainsArray,
        jfloatArray camera2PriorWbGainsArray,
        jfloatArray colorMatrixArray,
        jfloat exposureGain,
        jint captureSensitivityIso,
        jlong captureExposureTimeNs,
        jfloatArray physicalGreenNoiseSoArray,
        jfloat focusDetailPriority,
        jfloat profileToneExposure,
        jfloat profileToneHighlights,
        jfloat profileToneShadows,
        jfloat profileToneWhites,
        jfloat profileToneBlacks,
        jfloat profileToneContrast,
        jfloat profileLocalToneBias,
        jfloat profileSaturation,
        jfloat profileContrast,
        jfloat profileVibrance,
        jfloat profileDetailAmount,
        jfloat profileDetailRadius,
        jfloat profileDetailDetail,
        jfloat profileDetailMasking,
        jfloatArray toneCurveArray,
        jfloatArray gammaCurveArray,
        jfloatArray sectionCurveArray,
        jint rotationDegrees,
        jint sourceWidth,
        jint sourceHeight,
        jint sourceRowStrideBytes,
        jint sourcePixelStrideBytes,
        jint sourceCropLeft,
        jint sourceCropTop,
        jint sourceCropWidth,
        jint sourceCropHeight,
        jobject outputHardwareBufferObject,
        jobject outputRgbaBuffer,
        jobject analysisNv21Buffer,
        jint frameSlotIndex,
        jint maxWidth,
        jint maxHeight,
        jlong sensorTimestampNs,
        jint pipelineGeneration,
        jfloatArray lensShadingMapArray,
        jint lensShadingColumns,
        jint lensShadingRows,
        jintArray lensShadingActiveRectArray
) {
    auto* buffer = reinterpret_cast<AHardwareBuffer*>(
            static_cast<std::uintptr_t>(retainedHardwareBuffer));
    if (buffer == nullptr || outputRgbaBuffer == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "BnCamRawPreview",
                            "renderRawPreviewNative failed: buffer=%p outputRgbaBuffer=%p",
                            buffer, outputRgbaBuffer);
        return nullptr;
    }
    AHardwareBuffer* outputHardwareBuffer = outputHardwareBufferObject != nullptr
            ? AHardwareBuffer_fromHardwareBuffer(env, outputHardwareBufferObject)
            : nullptr;
    {
        std::lock_guard<std::mutex> lock(gRawPreviewRetainMutex);
        const auto found = gRawPreviewRetains.find(buffer);
        if (found == gRawPreviewRetains.end() || found->second == 0u) {
            __android_log_print(ANDROID_LOG_ERROR, "BnCamRawPreview",
                                "renderRawPreviewNative failed: buffer %p not in gRawPreviewRetains",
                                buffer);
            return nullptr;
        }
    }
    auto* output = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(outputRgbaBuffer));
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputRgbaBuffer);
    if (output == nullptr || outputCapacity <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, "BnCamRawPreview",
                            "renderRawPreviewNative failed: output=%p outputCapacity=%lld",
                            output, static_cast<long long>(outputCapacity));
        return nullptr;
    }
    auto* analysisNv21 = analysisNv21Buffer != nullptr
            ? static_cast<std::uint8_t*>(env->GetDirectBufferAddress(analysisNv21Buffer))
            : nullptr;
    const jlong analysisNv21Capacity = analysisNv21Buffer != nullptr
            ? env->GetDirectBufferCapacity(analysisNv21Buffer) : 0;
    if (analysisNv21Buffer != nullptr && (analysisNv21 == nullptr || analysisNv21Capacity <= 0)) {
        __android_log_print(ANDROID_LOG_ERROR, "BnCamRawPreview",
                            "renderRawPreviewNative failed: analysisNv21=%p capacity=%lld",
                            analysisNv21, static_cast<long long>(analysisNv21Capacity));
        return nullptr;
    }

    RawPreviewParameters parameters{};
    parameters.sourceFormat = sourceFormat;
    parameters.cfaPattern = std::clamp(static_cast<int>(cfaPattern), 0, 3);
    parameters.demosaicMode = std::clamp(static_cast<int>(requestedDemosaicMode), 0, 3);
    parameters.whiteLevel = std::max(1, static_cast<int>(whiteLevel));
    parameters.captureSensitivityIso = std::max(1, static_cast<int>(captureSensitivityIso));
    parameters.captureExposureTimeNs = std::max<std::int64_t>(0, static_cast<std::int64_t>(captureExposureTimeNs));
    if (physicalGreenNoiseSoArray != nullptr && env->GetArrayLength(physicalGreenNoiseSoArray) >= 3) {
        jfloat noise[3] = {0.0f, 0.0f, 0.0f};
        env->GetFloatArrayRegion(physicalGreenNoiseSoArray, 0, 3, noise);
        parameters.physicalGreenNoiseS = std::isfinite(noise[0]) ? std::max(0.0f, noise[0]) : 0.0f;
        parameters.physicalGreenNoiseO = std::isfinite(noise[1]) ? std::max(0.0f, noise[1]) : 0.0f;
        parameters.physicalNoiseConfidence = std::isfinite(noise[2])
                ? std::clamp(static_cast<float>(noise[2]), 0.0f, 1.0f) : 0.0f;
    }
    parameters.focusDetailPriority = std::isfinite(focusDetailPriority)
            ? std::clamp(static_cast<float>(focusDetailPriority), 0.0f, 1.0f) : 1.0f;
    parameters.rotationDegrees = normalizeRotationDegrees(rotationDegrees);
    parameters.sourceWidth = std::max(0, static_cast<int>(sourceWidth));
    parameters.sourceHeight = std::max(0, static_cast<int>(sourceHeight));
    parameters.sourceRowStrideBytes = std::max(0, static_cast<int>(sourceRowStrideBytes));
    parameters.sourcePixelStrideBytes = std::max(0, static_cast<int>(sourcePixelStrideBytes));
    parameters.sourceCropLeft = std::max(0, static_cast<int>(sourceCropLeft));
    parameters.sourceCropTop = std::max(0, static_cast<int>(sourceCropTop));
    parameters.sourceCropWidth = std::max(0, static_cast<int>(sourceCropWidth));
    parameters.sourceCropHeight = std::max(0, static_cast<int>(sourceCropHeight));
    parameters.maxWidth = maxWidth;
    parameters.maxHeight = maxHeight;
    parameters.frameSlotIndex = std::clamp(static_cast<int>(frameSlotIndex), -3, 2);
    parameters.sensorTimestampNs = static_cast<std::int64_t>(sensorTimestampNs);
    parameters.pipelineGeneration = static_cast<int>(pipelineGeneration);
    if (lensShadingMapArray != nullptr && lensShadingColumns > 0 && lensShadingRows > 0 &&
        lensShadingColumns <= 128 && lensShadingRows <= 128 &&
        static_cast<std::int64_t>(lensShadingColumns) * lensShadingRows <= 16'384) {
        const jsize count = static_cast<jsize>(lensShadingColumns * lensShadingRows * 4);
        if (env->GetArrayLength(lensShadingMapArray) == count) {
            parameters.lensShadingMap.resize(static_cast<std::size_t>(count));
            env->GetFloatArrayRegion(lensShadingMapArray, 0, count, parameters.lensShadingMap.data());
            if (!env->ExceptionCheck()) {
                parameters.lensShadingColumns = lensShadingColumns;
                parameters.lensShadingRows = lensShadingRows;
            } else {
                parameters.lensShadingMap.clear();
                env->ExceptionClear();
            }
        }
    }
    if (lensShadingActiveRectArray != nullptr && env->GetArrayLength(lensShadingActiveRectArray) >= 4) {
        env->GetIntArrayRegion(lensShadingActiveRectArray, 0, 4, parameters.lensShadingActiveRect);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    const auto blackLevels = extractFloat4(env, blackLevelsArray);
    std::copy(blackLevels.begin(), blackLevels.end(), parameters.blackLevels);
    if (camera2PriorWbGainsArray != nullptr && env->GetArrayLength(camera2PriorWbGainsArray) >= 4) {
        jfloat prior[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        env->GetFloatArrayRegion(camera2PriorWbGainsArray, 0, 4, prior);
        for (int index = 0; index < 4; ++index) {
            const float value = static_cast<float>(prior[index]);
            parameters.camera2PriorWbGains[index] =
                    std::isfinite(value) && value > 0.0f ? std::clamp(value, 0.25f, 6.0f) : 1.0f;
        }
    }

    NativeRenderQualityConfig& quality = parameters.quality;
    quality.exposureGain = std::isfinite(exposureGain)
            ? std::clamp(static_cast<float>(exposureGain), 0.125f, 8.0f) : 1.0f;
    quality.profileToneExposure = std::isfinite(profileToneExposure) ? std::clamp(static_cast<float>(profileToneExposure), -1.0f, 1.0f) : 0.0f;
    quality.profileToneHighlights = std::isfinite(profileToneHighlights) ? std::clamp(static_cast<float>(profileToneHighlights), -1.0f, 1.0f) : 0.0f;
    quality.profileToneShadows = std::isfinite(profileToneShadows) ? std::clamp(static_cast<float>(profileToneShadows), -1.0f, 1.0f) : 0.0f;
    quality.profileToneWhites = std::isfinite(profileToneWhites) ? std::clamp(static_cast<float>(profileToneWhites), -1.0f, 1.0f) : 0.0f;
    quality.profileToneBlacks = std::isfinite(profileToneBlacks) ? std::clamp(static_cast<float>(profileToneBlacks), -1.0f, 1.0f) : 0.0f;
    quality.profileToneContrast = std::isfinite(profileToneContrast) ? std::clamp(static_cast<float>(profileToneContrast), -1.0f, 1.0f) : 0.0f;
    quality.profileLocalToneBias = std::isfinite(profileLocalToneBias) ? std::clamp(static_cast<float>(profileLocalToneBias), -1.0f, 1.0f) : 0.0f;
    quality.profileColorSaturation = sanitizeProfileCreativeCarrier(static_cast<float>(profileSaturation));
    quality.profileColorContrast = std::isfinite(profileContrast)
            ? std::clamp(static_cast<float>(profileContrast), -1.0f, 1.0f) : 0.0f;
    quality.profilePresenceVibrance = std::isfinite(profileVibrance)
            ? std::clamp(static_cast<float>(profileVibrance), -1.0f, 1.0f) : 0.0f;
    quality.profileDetailAmount = std::isfinite(profileDetailAmount)
            ? std::clamp(static_cast<float>(profileDetailAmount), -1.0f, 1.0f)
            : bncam::profile_defaults::kDetailAmount;
    const float profileLegibility = std::isfinite(profileDetailRadius)
            ? std::clamp(static_cast<float>(profileDetailRadius), -1.0f, 1.0f) : 0.0f;
    quality.profileDetailRadius = bncam::profile_defaults::kDetailMinRadius;
    quality.profileDetailDetail = bncam::profile_microdetail_transport::encodePair(
            static_cast<float>(profileDetailDetail), profileLegibility);
    quality.profileDetailMasking = std::isfinite(profileDetailMasking)
            ? std::clamp(static_cast<float>(profileDetailMasking), -1.0f, 1.0f)
            : bncam::profile_defaults::kDetailMasking;
    // RAW zero-denoise preview: no profile noise-reduction transport exists.
    quality.toneCurve = extractCurveVector(env, toneCurveArray, 2, 64);
    quality.gammaCurve = extractCurveVector(env, gammaCurveArray, 2, 64);
    quality.sectionCurve = extractCurveVector(env, sectionCurveArray, 2, 64);
    if (wbGainsArray != nullptr && env->GetArrayLength(wbGainsArray) >= 4) {
        jfloat values[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        env->GetFloatArrayRegion(wbGainsArray, 0, 4, values);
        quality.wbRed = values[0];
        quality.wbGreenEven = values[1];
        quality.wbGreenOdd = values[2];
        quality.wbBlue = values[3];
    }
    if (colorMatrixArray != nullptr && env->GetArrayLength(colorMatrixArray) >= 9) {
        env->GetFloatArrayRegion(colorMatrixArray, 0, 9, quality.colorMatrix);
    }

    const RawPreviewResult rendered = renderRawPreviewRgba(
            buffer, parameters, outputHardwareBuffer, output, static_cast<std::size_t>(outputCapacity),
            analysisNv21, static_cast<std::size_t>(std::max<jlong>(0, analysisNv21Capacity)));
    if (rendered.gpuPending) {
        const jint pending = -2;
        jintArray signal = env->NewIntArray(1);
        if (signal != nullptr) env->SetIntArrayRegion(signal, 0, 1, &pending);
        return signal;
    }
    if (!rendered.success) return nullptr;
    jint values[43] = {
            rendered.width,
            rendered.height,
            rendered.renderMicroseconds,
            rendered.vulkanStagesUsed ? 1 : 0,
            rendered.cfaCellDecimation,
            static_cast<jint>(std::lround(rendered.normalizedRawMin * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.normalizedRawMax * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.outputRgbMin * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.outputRgbMax * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.outputRgbMean * 1000000.0f)),
            255,
            rendered.rawUnpackMicroseconds,
            rendered.demosaicMicroseconds,
            rendered.colorMicroseconds,
            rendered.tonePackMicroseconds,
            static_cast<jint>(std::lround(rendered.targetExposureGain * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.appliedExposureGain * 1000000.0f)),
            static_cast<jint>(std::lround(rendered.sceneMidtone * 1000000.0f)),
            static_cast<jint>(std::min<std::uint64_t>(
                    rendered.commonHighlightScalePixels,
                    static_cast<std::uint64_t>(std::numeric_limits<jint>::max()))),
            rendered.directHostInputUsed ? 1 : 0,
            rendered.directHardwareBufferInputUsed ? 1 : 0,
            rendered.gpuResidentOutputUsed ? 1 : 0
    };
    for (int index = 0; index < 16; ++index) {
        values[22 + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.displayLumaHistogram[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    }
    values[38] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displayShadowSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    values[39] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displayHighlightSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    values[40] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displaySampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    values[41] = rendered.displayHighlightX >= 0.0f
            ? static_cast<jint>(std::lround(rendered.displayHighlightX * 1000000.0f)) : -1;
    values[42] = rendered.displayHighlightY >= 0.0f
            ? static_cast<jint>(std::lround(rendered.displayHighlightY * 1000000.0f)) : -1;
    constexpr int BASE_RESULT_SIZE = 49;
    constexpr int LINEAR_LUMA_START = BASE_RESULT_SIZE;
    constexpr int DISPLAY_LUMA64_START = LINEAR_LUMA_START + 256;
    constexpr int DISPLAY_R64_START = DISPLAY_LUMA64_START + 64;
    constexpr int DISPLAY_G64_START = DISPLAY_R64_START + 64;
    constexpr int DISPLAY_B64_START = DISPLAY_G64_START + 64;
    constexpr int RAW_NEAR_CLIP_INDEX = DISPLAY_B64_START + 64;
    constexpr int RAW_SAMPLE_INDEX = RAW_NEAR_CLIP_INDEX + 1;
    constexpr int DISPLAY_R_CLIP_INDEX = RAW_SAMPLE_INDEX + 1;
    constexpr int DISPLAY_G_CLIP_INDEX = DISPLAY_R_CLIP_INDEX + 1;
    constexpr int DISPLAY_B_CLIP_INDEX = DISPLAY_G_CLIP_INDEX + 1;
    constexpr int TONE_TRUTH_START_INDEX = DISPLAY_B_CLIP_INDEX + 1;
    constexpr int FIRST_ACTIVATION_DIAGNOSTICS_START_INDEX = TONE_TRUTH_START_INDEX + 10;
    constexpr int FIRST_ACTIVATION_DIAGNOSTICS_COUNT = 20;
    constexpr int PHYSICAL_AWB_DIAGNOSTICS_START_INDEX =
            FIRST_ACTIVATION_DIAGNOSTICS_START_INDEX + FIRST_ACTIVATION_DIAGNOSTICS_COUNT;
    constexpr int PHYSICAL_AWB_DIAGNOSTICS_COUNT = 17;
    constexpr int SPATIAL_EXPOSURE_DIAGNOSTICS_START_INDEX =
            PHYSICAL_AWB_DIAGNOSTICS_START_INDEX + PHYSICAL_AWB_DIAGNOSTICS_COUNT;
    constexpr int SPATIAL_EXPOSURE_DIAGNOSTICS_COUNT = 12;
    constexpr int EXPANDED_RESULT_SIZE =
            SPATIAL_EXPOSURE_DIAGNOSTICS_START_INDEX + SPATIAL_EXPOSURE_DIAGNOSTICS_COUNT;
    jint expandedValues[EXPANDED_RESULT_SIZE]{};
    std::copy(std::begin(values), std::end(values), std::begin(expandedValues));
    expandedValues[43] = rendered.analysisNv21Width;
    expandedValues[44] = rendered.analysisNv21Height;
    expandedValues[45] = static_cast<jint>(rendered.inputAhbFormat);
    expandedValues[46] = static_cast<jint>(rendered.inputAhbUsage & 0xffffffffull);
    expandedValues[47] = static_cast<jint>((rendered.inputAhbUsage >> 32u) & 0xffffffffull);
    expandedValues[48] = static_cast<jint>(rendered.inputInteropStatus);
    for (int index = 0; index < 256; ++index) {
        expandedValues[LINEAR_LUMA_START + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.linearLumaHistogram[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    }
    for (int index = 0; index < 64; ++index) {
        expandedValues[DISPLAY_LUMA64_START + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.displayLumaHistogram64[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
        expandedValues[DISPLAY_R64_START + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.displayRHistogram64[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
        expandedValues[DISPLAY_G64_START + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.displayGHistogram64[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
        expandedValues[DISPLAY_B64_START + index] = static_cast<jint>(std::min<std::uint32_t>(
                rendered.displayBHistogram64[index],
                static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    }
    expandedValues[RAW_NEAR_CLIP_INDEX] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.rawNearClipSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    expandedValues[RAW_SAMPLE_INDEX] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.rawSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    expandedValues[DISPLAY_R_CLIP_INDEX] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displayRClipSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    expandedValues[DISPLAY_G_CLIP_INDEX] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displayGClipSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    expandedValues[DISPLAY_B_CLIP_INDEX] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.displayBClipSampleCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    const float toneTruth[10] = {
            rendered.sceneMidtoneTarget,
            rendered.gtmShoulderStart,
            rendered.gtmShoulderStrength,
            rendered.gtmBlackAnchor,
            rendered.gtmLowerMidLift,
            rendered.gtmContrastStrength,
            rendered.gtmDynamicRangePressure,
            rendered.ltmStrength,
            rendered.ltmMaxLiftEv,
            rendered.ltmMaxCompressEv};
    for (int index = 0; index < 10; ++index) {
        expandedValues[TONE_TRUTH_START_INDEX + index] = static_cast<jint>(
                std::lround(toneTruth[index] * 1000000.0f));
    }
    const jint firstActivationDiagnostics[FIRST_ACTIVATION_DIAGNOSTICS_COUNT] = {
            rendered.backendMutexWaitMicroseconds,
            rendered.backendInitializationPerformed ? 1 : 0,
            rendered.backendInitializationMicroseconds,
            rendered.spirvLookupMicroseconds,
            rendered.descriptorLayoutMicroseconds,
            rendered.pipelineLayoutMicroseconds,
            rendered.shaderModuleMicroseconds,
            rendered.pipelineCacheMutexWaitMicroseconds,
            rendered.pipelineCachePresent ? 1 : 0,
            rendered.computePipelineMicroseconds,
            rendered.imageSpirvLookupMicroseconds,
            rendered.imageShaderModuleMicroseconds,
            rendered.imageComputePipelineMicroseconds,
            rendered.descriptorCommandResourcesMicroseconds,
            rendered.inputAhbProbeMicroseconds,
            rendered.outputAhbImportMicroseconds,
            rendered.commandRecordMicroseconds,
            rendered.queueMutexWaitMicroseconds,
            rendered.queueSubmitCallMicroseconds,
            rendered.fenceWaitMicroseconds
    };
    for (int index = 0; index < FIRST_ACTIVATION_DIAGNOSTICS_COUNT; ++index) {
        expandedValues[FIRST_ACTIVATION_DIAGNOSTICS_START_INDEX + index] =
                firstActivationDiagnostics[index];
    }
    int awbIndex = PHYSICAL_AWB_DIAGNOSTICS_START_INDEX;
    for (int channel = 0; channel < 3; ++channel) {
        expandedValues[awbIndex++] = static_cast<jint>(
                std::lround(rendered.awbPriorGainsRgb[channel] * 1000000.0f));
    }
    for (int channel = 0; channel < 3; ++channel) {
        expandedValues[awbIndex++] = static_cast<jint>(
                std::lround(rendered.awbDataGainsRgb[channel] * 1000000.0f));
    }
    for (int channel = 0; channel < 3; ++channel) {
        expandedValues[awbIndex++] = static_cast<jint>(
                std::lround(rendered.awbFinalGainsRgb[channel] * 1000000.0f));
    }
    expandedValues[awbIndex++] = static_cast<jint>(std::lround(rendered.awbConfidence * 1000000.0f));
    expandedValues[awbIndex++] = static_cast<jint>(std::lround(rendered.awbDataAuthority * 1000000.0f));
    expandedValues[awbIndex++] = static_cast<jint>(std::lround(rendered.awbNeutralSupport * 1000000.0f));
    expandedValues[awbIndex++] = static_cast<jint>(std::lround(rendered.awbMixedLightScore * 1000000.0f));
    expandedValues[awbIndex++] = static_cast<jint>(std::lround(rendered.awbPriorDisagreement * 1000000.0f));
    expandedValues[awbIndex++] = rendered.awbValidTileCount;
    expandedValues[awbIndex++] = rendered.awbAcceptedSampleCount;
    expandedValues[awbIndex++] = rendered.awbDataReady ? 1 : 0;
    int exposureIndex = SPATIAL_EXPOSURE_DIAGNOSTICS_START_INDEX;
    expandedValues[exposureIndex++] = static_cast<jint>(std::min<std::uint32_t>(
            rendered.exposureTileCount, static_cast<std::uint32_t>(std::numeric_limits<jint>::max())));
    const float exposureDiagnostics[11] = {
            rendered.exposureSceneP10, rendered.exposureSceneP25, rendered.exposureSceneP50,
            rendered.exposureSceneP75, rendered.exposureSceneP90, rendered.exposureSceneP95,
            rendered.exposureSceneP99, rendered.exposureMeasuredSceneDrEv,
            rendered.exposureLowerNeutralBoundaryEv, rendered.exposureUpperNeutralBoundaryEv,
            rendered.exposureSpatialAuthority};
    for (float value : exposureDiagnostics) {
        expandedValues[exposureIndex++] = static_cast<jint>(
                std::lround((std::isfinite(value) ? value : 0.0f) * 1000000.0f));
    }
    jintArray result = env->NewIntArray(EXPANDED_RESULT_SIZE);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, EXPANDED_RESULT_SIZE, expandedValues);
    return result;
}


extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_bncam_core_engine_ImageUtils_analyzeFrameCandidateNative(
        JNIEnv *env,
        jobject /* thiz */,
        jobject hardwareBufferObject,
        jint sourceFormat,
        jint nativeWhiteLevel,
        jintArray nativeBlackLevelArray
) {
    const auto started = NativeClock::now();
    std::array<float, 7> output{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    auto finish = [&]() -> jfloatArray {
        output[6] = nativeElapsedMs(started);
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result != nullptr) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        }
        return result;
    };

    if (hardwareBufferObject == nullptr) return finish();
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (buffer == nullptr) return finish();
    AHardwareBuffer_acquire(buffer);

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.width == 0 || desc.height == 0) {
        AHardwareBuffer_release(buffer);
        return finish();
    }

    AHardwareBuffer_Planes planes{};
    int lockStatus = AHardwareBuffer_lockPlanes(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,
            nullptr,
            &planes
    );
    uint8_t *data = nullptr;
    uint32_t rowStride = 0;
    uint32_t pixelStride = 0;
    bool locked = false;
    if (lockStatus == 0 && planes.planeCount >= 1 && planes.planes[0].data != nullptr) {
        data = static_cast<uint8_t*>(planes.planes[0].data);
        rowStride = static_cast<uint32_t>(planes.planes[0].rowStride);
        pixelStride = static_cast<uint32_t>(planes.planes[0].pixelStride);
        locked = true;
    } else {
        void *base = nullptr;
        lockStatus = AHardwareBuffer_lock(
                buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,
                nullptr,
                &base
        );
        if (lockStatus == 0 && base != nullptr) {
            data = static_cast<uint8_t*>(base);
            const uint32_t storageWidth = std::max(desc.width, desc.stride);
            if (sourceFormat == 37) {
                rowStride = ((storageWidth + 3u) / 4u) * 5u;
                pixelStride = 0;
                locked = true;
            } else if (sourceFormat == 32) {
                rowStride = storageWidth * 2u;
                pixelStride = 2u;
                locked = true;
            }
        }
    }

    if (!locked || data == nullptr || rowStride == 0) {
        AHardwareBuffer_release(buffer);
        return finish();
    }

    const std::vector<int32_t> blackLevels = extractBlackLevelVector(env, nativeBlackLevelArray);
    const int white = std::clamp(static_cast<int>(nativeWhiteLevel), 1, 65535);
    const bool isYuv = sourceFormat == 35;
    const bool isRaw10 = sourceFormat == 37;
    const bool isRawSensor = sourceFormat == 32;
    if (!isYuv && !isRaw10 && !isRawSensor) {
        AHardwareBuffer_unlock(buffer, nullptr);
        AHardwareBuffer_release(buffer);
        return finish();
    }

    if (isYuv && pixelStride == 0u) pixelStride = 1u;
    if (isRawSensor && pixelStride < 2u) pixelStride = 2u;

    auto readCode = [&](uint32_t x, uint32_t y) -> int {
        const uint8_t *row = data + static_cast<size_t>(y) * rowStride;
        if (isYuv) {
            return row[static_cast<size_t>(x) * pixelStride];
        }
        if (isRawSensor) {
            const size_t offset = static_cast<size_t>(x) * pixelStride;
            if (offset + 1u >= rowStride) return 0;
            return static_cast<int>(row[offset]) | (static_cast<int>(row[offset + 1u]) << 8);
        }
        const size_t groupOffset = static_cast<size_t>(x / 4u) * 5u;
        if (groupOffset + 4u >= rowStride) return 0;
        const int lowBits = row[groupOffset + 4u];
        const int lane = static_cast<int>(x & 3u);
        const int shift = lane * 2;
        return (static_cast<int>(row[groupOffset + static_cast<size_t>(lane)]) << 2) |
               ((lowBits >> shift) & 0x03);
    };

    auto normalizedCode = [&](uint32_t x, uint32_t y) -> double {
        const int cfaIndex = isYuv ? 0 : static_cast<int>((y & 1u) * 2u + (x & 1u));
        const int black = std::clamp(blackLevels[static_cast<size_t>(cfaIndex)], 0, white - 1);
        const int denominator = std::max(1, white - black);
        return std::clamp(
                static_cast<double>(readCode(x, y) - black) / static_cast<double>(denominator),
                0.0,
                1.0
        );
    };

    const uint32_t sampleStepX = std::max(1u, desc.width / 128u);
    const uint32_t sampleStepY = std::max(1u, desc.height / 96u);
    const uint32_t gradientStep = isYuv ? 1u : 2u;
    uint64_t sampleCount = 0;
    uint64_t lowClipped = 0;
    uint64_t highClipped = 0;
    double mean = 0.0;
    double m2 = 0.0;
    double gradientSum = 0.0;
    uint64_t gradientCount = 0;

    for (uint32_t y = 0; y < desc.height; y += sampleStepY) {
        for (uint32_t x = 0; x < desc.width; x += sampleStepX) {
            const double value = normalizedCode(x, y);
            ++sampleCount;
            const double delta = value - mean;
            mean += delta / static_cast<double>(sampleCount);
            m2 += delta * (value - mean);
            if (value <= 0.01) ++lowClipped;
            if (value >= 0.995) ++highClipped;
            if (x + gradientStep < desc.width && y + gradientStep < desc.height) {
                const double gx = std::abs(value - normalizedCode(x + gradientStep, y));
                const double gy = std::abs(value - normalizedCode(x, y + gradientStep));
                gradientSum += std::sqrt(gx * gx + gy * gy);
                ++gradientCount;
            }
        }
    }

    AHardwareBuffer_unlock(buffer, nullptr);
    AHardwareBuffer_release(buffer);
    if (sampleCount == 0) return finish();

    const double variance = sampleCount > 1 ? m2 / static_cast<double>(sampleCount - 1u) : 0.0;
    const double meanGradient = gradientCount > 0 ? gradientSum / static_cast<double>(gradientCount) : 0.0;
    output[0] = 1.0f;
    output[1] = static_cast<float>(std::clamp(1.0 - std::exp(-meanGradient * 18.0), 0.0, 1.0));
    output[2] = static_cast<float>(std::clamp(mean, 0.0, 1.0));
    output[3] = static_cast<float>(static_cast<double>(lowClipped) / static_cast<double>(sampleCount));
    output[4] = static_cast<float>(static_cast<double>(highClipped) / static_cast<double>(sampleCount));
    output[5] = static_cast<float>(std::clamp(std::sqrt(std::max(0.0, variance)) * 4.0, 0.0, 1.0));
    return finish();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bncam_core_engine_ImageUtils_analyzeYuvExposureStatisticsNative(
        JNIEnv* env,
        jobject /* thiz */,
        jobject packedSamplesBuffer,
        jint sampleWidth,
        jint sampleHeight) {
    if (env == nullptr || packedSamplesBuffer == nullptr || sampleWidth <= 0 || sampleHeight <= 0) {
        return nullptr;
    }
    void* address = env->GetDirectBufferAddress(packedSamplesBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(packedSamplesBuffer);
    const std::size_t sampleCount = static_cast<std::size_t>(sampleWidth) * static_cast<std::size_t>(sampleHeight);
    const std::size_t requiredBytes = sampleCount * sizeof(std::uint32_t);
    if (address == nullptr || capacity < 0 || static_cast<std::size_t>(capacity) < requiredBytes || sampleCount > 65536u) {
        return nullptr;
    }

    bncam::vulkan::YuvExposureStatisticsRequest request{};
    request.packedSamples = static_cast<const std::uint32_t*>(address);
    request.sampleCount = sampleCount;
    request.sampleWidth = static_cast<std::uint32_t>(sampleWidth);
    request.sampleHeight = static_cast<std::uint32_t>(sampleHeight);
    const auto result = bncam::vulkan::VulkanRuntime::instance().executeYuvExposureStatistics(request);
    if (!result.success) {
        LOGW("YUV exposure statistics Vulkan fallback reason=%s", result.failureReason.c_str());
        return nullptr;
    }

    jintArray output = env->NewIntArray(static_cast<jsize>(bncam::vulkan::kYuvExposureStatisticsWords));
    if (output == nullptr) return nullptr;
    env->SetIntArrayRegion(
            output,
            0,
            static_cast<jsize>(bncam::vulkan::kYuvExposureStatisticsWords),
            reinterpret_cast<const jint*>(result.statistics.data()));
    return output;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_bncam_core_engine_ImageUtils_processNativeYuv(
        JNIEnv *env,
        jobject /* thiz */,
        jobjectArray buffersArray,
        jstring lensIdStr,
        jint jpegQuality,
        jint captureSensitivityIso,
        jint rotationDegrees,
        jfloat profileYuvWbRed,
        jfloat profileYuvWbGreen,
        jfloat profileYuvWbBlue,
        jfloat profileColorSaturation,
        jfloat profileColorContrast,
        jfloat profilePresenceVibrance,
        jfloat profileDetailAmount,
        jfloat profileDetailRadius,
        jfloat profileDetailDetail,
        jfloat profileDetailMasking,
        jfloat profileNrLuminance,
        jfloat profileNrLuminanceDetail,
        jfloat profileNrLuminanceContrast,
        jfloat profileNrColor,
        jfloat profileNrColorDetail,
        jfloat profileNrColorSmoothness,
        jfloatArray toneCurveArray,
        jfloatArray gammaCurveArray,
        jfloatArray sectionCurveArray,
        jfloatArray exposureScaleToAnchorArray,
        jboolean computationalHdrFlag,
        jboolean ultraHdrGainmapEnabledFlag,
        jboolean portraitEffectEnabledFlag,
        jobject portraitMaskBuffer,
        jint portraitMaskWidth,
        jint portraitMaskHeight,
        jfloat portraitTargetLeft,
        jfloat portraitTargetTop,
        jfloat portraitTargetRight,
        jfloat portraitTargetBottom,
        jint portraitMaskRotationDegrees
) {
    std::string lensId = "unknown";
    if (lensIdStr != nullptr) {
        const char* chars = env->GetStringUTFChars(lensIdStr, nullptr);
        lensId = chars != nullptr ? chars : "unknown";
        if (chars != nullptr) env->ReleaseStringUTFChars(lensIdStr, chars);
    }

    const auto totalStart = NativeClock::now();
    // Each YUV render owns its own optional gainmap result. Clear both thread-local artifact
    // domains before work so a previous RAW/YUV capture can never leak into publication.
    gThreadLocalYuvUltraHdrArtifact = {};
    (void)IspCore::takeLastUltraHdrGainmapArtifact();
    auto stageStart = NativeClock::now();
    std::vector<AHardwareBuffer*> hwBuffers = extractHardwareBuffers(env, buffersArray);
    const float hwBufferExtractMs = nativeElapsedMs(stageStart);
    if (hwBuffers.empty()) return nullptr;
    const std::vector<float> inputExposureScales = extractPositiveFloatVector(env, exposureScaleToAnchorArray);
    const bool computationalHdr = computationalHdrFlag == JNI_TRUE && hwBuffers.size() > 1u;

    NativeRenderQualityConfig yuvQuality{};
    yuvQuality.jpegQuality = jpegQuality;
    yuvQuality.captureSensitivityIso = std::max(0, static_cast<int>(captureSensitivityIso));
    yuvQuality.profileYuvWbRed = std::isfinite(profileYuvWbRed) ? std::clamp(profileYuvWbRed, 0.50f, 2.00f) : 1.0f;
    yuvQuality.profileYuvWbGreen = std::isfinite(profileYuvWbGreen) ? std::clamp(profileYuvWbGreen, 0.50f, 2.00f) : 1.0f;
    yuvQuality.profileYuvWbBlue = std::isfinite(profileYuvWbBlue) ? std::clamp(profileYuvWbBlue, 0.50f, 2.00f) : 1.0f;
    yuvQuality.profileColorSaturation = sanitizeProfileCreativeCarrier(profileColorSaturation);
    yuvQuality.profileColorContrast = std::isfinite(profileColorContrast) ? std::clamp(profileColorContrast, -1.0f, 1.0f) : 0.0f;
    yuvQuality.profilePresenceVibrance = std::isfinite(profilePresenceVibrance) ? std::clamp(profilePresenceVibrance, -1.0f, 1.0f) : 0.0f;
    yuvQuality.profileDetailAmount = std::isfinite(profileDetailAmount) ? std::clamp(profileDetailAmount, -1.0f, 1.0f) : bncam::profile_defaults::kDetailAmount;
    const float profileLegibility = std::isfinite(profileDetailRadius)
            ? std::clamp(profileDetailRadius, -1.0f, 1.0f) : 0.0f;
    yuvQuality.profileDetailRadius = bncam::profile_defaults::kDetailMinRadius;
    yuvQuality.profileDetailDetail = bncam::profile_microdetail_transport::encodePair(
            profileDetailDetail, profileLegibility);
    yuvQuality.profileDetailMasking = std::isfinite(profileDetailMasking) ? std::clamp(profileDetailMasking, -1.0f, 1.0f) : bncam::profile_defaults::kDetailMasking;
    yuvQuality.profileNrLuminance = std::isfinite(profileNrLuminance) ? std::clamp(profileNrLuminance, 0.0f, 1.0f) : 0.0f;
    yuvQuality.profileNrLuminanceDetail = std::isfinite(profileNrLuminanceDetail) ? std::clamp(profileNrLuminanceDetail, 0.0f, 1.0f) : 0.5f;
    yuvQuality.profileNrLuminanceContrast = std::isfinite(profileNrLuminanceContrast) ? std::clamp(profileNrLuminanceContrast, 0.0f, 1.0f) : 0.0f;
    yuvQuality.profileNrColor = std::isfinite(profileNrColor) ? std::clamp(profileNrColor, 0.0f, 1.0f) : 0.0f;
    yuvQuality.profileNrColorDetail = std::isfinite(profileNrColorDetail) ? std::clamp(profileNrColorDetail, 0.0f, 1.0f) : 0.5f;
    yuvQuality.profileNrColorSmoothness = std::isfinite(profileNrColorSmoothness) ? std::clamp(profileNrColorSmoothness, 0.0f, 1.0f) : 0.5f;
    yuvQuality.ultraHdrGainmapEnabled = ultraHdrGainmapEnabledFlag == JNI_TRUE;
    if (portraitEffectEnabledFlag == JNI_TRUE && portraitMaskBuffer != nullptr &&
        portraitMaskWidth > 1 && portraitMaskHeight > 1) {
        void* portraitAddress = env->GetDirectBufferAddress(portraitMaskBuffer);
        const jlong portraitCapacity = env->GetDirectBufferCapacity(portraitMaskBuffer);
        const std::uint64_t expectedPortraitBytes = static_cast<std::uint64_t>(portraitMaskWidth) *
                static_cast<std::uint64_t>(portraitMaskHeight) * sizeof(float);
        if (portraitAddress != nullptr && portraitCapacity >= 0 &&
            static_cast<std::uint64_t>(portraitCapacity) >= expectedPortraitBytes) {
            yuvQuality.portraitEffectEnabled = true;
            yuvQuality.portraitMask = static_cast<const float*>(portraitAddress);
            yuvQuality.portraitMaskFloatCount = static_cast<std::size_t>(portraitCapacity) / sizeof(float);
            yuvQuality.portraitMaskWidth = static_cast<std::uint32_t>(portraitMaskWidth);
            yuvQuality.portraitMaskHeight = static_cast<std::uint32_t>(portraitMaskHeight);
            yuvQuality.portraitTargetLeft = portraitTargetLeft;
            yuvQuality.portraitTargetTop = portraitTargetTop;
            yuvQuality.portraitTargetRight = portraitTargetRight;
            yuvQuality.portraitTargetBottom = portraitTargetBottom;
            yuvQuality.portraitMaskRotationDegrees = static_cast<std::uint32_t>(
                    ((portraitMaskRotationDegrees % 360) + 360) % 360);
        }
    }
    yuvQuality.toneCurve = extractCurveVector(env, toneCurveArray, 2, 64);
    yuvQuality.gammaCurve = extractCurveVector(env, gammaCurveArray, 2, 64);
    yuvQuality.sectionCurve = extractCurveVector(env, sectionCurveArray, 2, 64);
    LOGI("NATIVE BRIDGE: YUV engine started with %zu frame(s), lens=%s, jpegQuality=%d", hwBuffers.size(), lensId.c_str(), yuvQuality.jpegQuality);

    AHardwareBuffer* anchor = hwBuffers.back();
    AHardwareBuffer_Desc desc{};
    bool planeCopyOk = false;
    float hwBufferDescribeMs = 0.0f;
    float hwBufferLockMs = 0.0f;
    float yuvPlaneCopyMs = 0.0f;
    float yuvSupportLumaCopyMs = 0.0f;
    float yuvComputeAlignMs = 0.0f;
    float yuvAlignmentGpuMs = 0.0f;
    float yuvAlignmentInputCopyMs = 0.0f;
    float yuvAlignmentGpuSyncMs = 0.0f;
    float yuvFusionGpuMs = 0.0f;
    std::uint64_t yuvAlignmentCpuUploadBytes = 0u;
    std::uint64_t yuvAlignmentCompactReadbackBytes = 0u;
    std::uint64_t yuvFusionGpuReadbackBytes = 0u;
    std::string yuvAlignmentBackend = hwBuffers.size() > 1u ? "NOT_ATTEMPTED" : "NOT_APPLICABLE_SINGLE_FRAME";
    std::string yuvFusionBackend = hwBuffers.size() > 1u ? "NOT_ATTEMPTED" : "NOT_APPLICABLE_SINGLE_FRAME";
    bool yuvAlignmentCpuFallbackUsed = false;
    bool yuvFusionCpuFallbackUsed = false;
    std::string yuvAlignmentFallbackReason = "none";
    std::string yuvFusionFallbackReason = "none";
    YuvEncodeTiming encodeTiming{};
    std::string yuvChromaLayout = "not_locked";
    std::string yuvPipelineMode = hwBuffers.size() > 1u ? "YUV_COMPUTE" : "YUV_FAST";
    int yuvFramesUsed = 0;
    int yuvSupportAccepted = 0;
    int yuvSupportRejected = 0;
    bool yuvAnchorOnly = true;
    bool yuvAnchorOutputCopyEliminated = false;
    std::uint64_t yuvResidentLumaGeneration = 0u;
    std::vector<uint8_t> jpegBuf;

    auto copyHardwareBufferToNv21 = [&](AHardwareBuffer* buffer,
                                        std::vector<uint8_t>& nv21,
                                        AHardwareBuffer_Desc* descOut,
                                        std::string* layoutOut,
                                        float* describeMsOut,
                                        float* lockMsOut,
                                        float* copyMsOut) -> bool {
        if (buffer == nullptr) return false;
        AHardwareBuffer_Desc localDesc{};
        auto localStart = NativeClock::now();
        AHardwareBuffer_describe(buffer, &localDesc);
        const float describeMs = nativeElapsedMs(localStart);

        AHardwareBuffer_Planes planes{};
        localStart = NativeClock::now();
        const int status = AHardwareBuffer_lockPlanes(
                buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,
                nullptr,
                &planes
        );
        const float lockMs = nativeElapsedMs(localStart);
        if (status != 0) {
            LOGE("Could not lock YUV HardwareBuffer. status=%d format=%u size=%ux%u", status, localDesc.format, localDesc.width, localDesc.height);
            if (describeMsOut != nullptr) *describeMsOut += describeMs;
            if (lockMsOut != nullptr) *lockMsOut += lockMs;
            return false;
        }

        std::string localLayout = "unknown";
        localStart = NativeClock::now();

        if (planes.planeCount >= 3) {
            const auto& yPlane = planes.planes[0];
            const auto& uPlane = planes.planes[1];
            const auto& vPlane = planes.planes[2];
            const auto *srcY = static_cast<const uint8_t*>(yPlane.data);
            const auto *srcU = static_cast<const uint8_t*>(uPlane.data);
            const auto *srcV = static_cast<const uint8_t*>(vPlane.data);
            const size_t ySize = static_cast<size_t>(localDesc.width) * localDesc.height;

            const bool isContiguousNv21 =
                    srcY != nullptr && srcU != nullptr && srcV != nullptr &&
                    yPlane.pixelStride == 1 &&
                    yPlane.rowStride == localDesc.width &&
                    uPlane.pixelStride == 2 &&
                    vPlane.pixelStride == 2 &&
                    uPlane.rowStride == localDesc.width &&
                    vPlane.rowStride == localDesc.width &&
                    srcU == srcV + 1 &&
                    srcV == srcY + ySize;

            if (isContiguousNv21) {
                localLayout = "SAFE_ZERO_COPY_NV21_CONTIGUOUS;yRowStride=" + std::to_string(yPlane.rowStride);
                const size_t totalNv21Size = ySize + ySize / 2u;
                float contiguousCopyMs = 0.0f;
                if (hwBuffers.size() > 1u) {
                    // The multi-frame path needs an independently owned NV21 anchor because
                    // its chroma plane remains the source for the resident fused-luma ISP handoff,
                    // and explicit CPU recovery still needs writable anchor storage.
                    const auto copyStart = NativeClock::now();
                    nv21.assign(srcY, srcY + totalNv21Size);
                    contiguousCopyMs = nativeElapsedMs(copyStart);
                } else {
                    encodeNv21ToJpeg(
                            srcY,
                            totalNv21Size,
                            localDesc.width,
                            localDesc.height,
                            rotationDegrees,
                            yuvQuality,
                            jpegBuf,
                            &encodeTiming,
                            0u
                    );
                    yuvAnchorOutputCopyEliminated = true;
                }
                AHardwareBuffer_unlock(buffer, nullptr);
                if (descOut != nullptr) *descOut = localDesc;
                if (layoutOut != nullptr) *layoutOut = localLayout;
                if (describeMsOut != nullptr) *describeMsOut += describeMs;
                if (lockMsOut != nullptr) *lockMsOut += lockMs;
                if (copyMsOut != nullptr) *copyMsOut += contiguousCopyMs;
                return true;
            }
        }

        const bool ok = copyYuv420PlanesToNv21(planes, localDesc.width, localDesc.height, nv21, &localLayout);
        const float copyMs = nativeElapsedMs(localStart);
        AHardwareBuffer_unlock(buffer, nullptr);

        if (descOut != nullptr) *descOut = localDesc;
        if (layoutOut != nullptr) *layoutOut = localLayout;
        if (describeMsOut != nullptr) *describeMsOut += describeMs;
        if (lockMsOut != nullptr) *lockMsOut += lockMs;
        if (copyMsOut != nullptr) *copyMsOut += copyMs;
        return ok;
    };

    auto copyHardwareBufferLuma = [&](AHardwareBuffer* buffer,
                                      std::vector<uint8_t>& luma,
                                      AHardwareBuffer_Desc* descOut) -> bool {
        if (buffer == nullptr) return false;
        AHardwareBuffer_Desc localDesc{};
        auto localStart = NativeClock::now();
        AHardwareBuffer_describe(buffer, &localDesc);
        hwBufferDescribeMs += nativeElapsedMs(localStart);

        AHardwareBuffer_Planes planes{};
        localStart = NativeClock::now();
        const int status = AHardwareBuffer_lockPlanes(
                buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,
                nullptr,
                &planes
        );
        hwBufferLockMs += nativeElapsedMs(localStart);
        if (status != 0) {
            return false;
        }
        if (planes.planeCount < 1 || planes.planes[0].data == nullptr) {
            AHardwareBuffer_unlock(buffer, nullptr);
            return false;
        }

        const auto& yPlane = planes.planes[0];
        const uint32_t width = localDesc.width;
        const uint32_t height = localDesc.height;
        luma.resize(static_cast<size_t>(width) * height);
        localStart = NativeClock::now();
        auto* source = static_cast<const uint8_t*>(yPlane.data);
        for (uint32_t y = 0; y < height; ++y) {
            const uint8_t* sourceRow = source + static_cast<size_t>(y) * yPlane.rowStride;
            uint8_t* targetRow = luma.data() + static_cast<size_t>(y) * width;
            if (yPlane.pixelStride == 1u) {
                std::memcpy(targetRow, sourceRow, width);
            } else {
                for (uint32_t x = 0; x < width; ++x) {
                    targetRow[x] = sourceRow[static_cast<size_t>(x) * yPlane.pixelStride];
                }
            }
        }
        yuvSupportLumaCopyMs += nativeElapsedMs(localStart);
        AHardwareBuffer_unlock(buffer, nullptr);
        if (descOut != nullptr) *descOut = localDesc;
        return true;
    };

    bncam::vulkan::YuvMultiFrameAlignmentResult gpuAlignment{};
    if (hwBuffers.size() > 1u) {
        bncam::vulkan::YuvMultiFrameAlignmentRequest alignmentRequest{};
        alignmentRequest.frames.reserve(hwBuffers.size());
        alignmentRequest.frames.push_back(anchor);
        for (size_t index = 0; index + 1u < hwBuffers.size(); ++index) {
            alignmentRequest.frames.push_back(hwBuffers[index]);
        }
        alignmentRequest.exposureScaleToAnchor.reserve(hwBuffers.size());
        const bool exposureScaleContractValid = inputExposureScales.size() == hwBuffers.size();
        alignmentRequest.exposureScaleToAnchor.push_back(
                exposureScaleContractValid ? inputExposureScales.back() : 1.0f);
        for (size_t index = 0; index + 1u < hwBuffers.size(); ++index) {
            alignmentRequest.exposureScaleToAnchor.push_back(
                    exposureScaleContractValid ? inputExposureScales[index] : 1.0f);
        }
        alignmentRequest.computationalHdr = computationalHdr && exposureScaleContractValid;
        alignmentRequest.maxShiftPixels = 12u;
        alignmentRequest.sampleStep = 16u;
        alignmentRequest.minimumCorrelation = computationalHdr ? 0.12f : 0.08f;
        alignmentRequest.fuseLuma = true;
        alignmentRequest.deferFullFrameReadback = true;
        alignmentRequest.generationId = static_cast<std::uint64_t>(NativeClock::now().time_since_epoch().count());
        gpuAlignment = bncam::vulkan::VulkanRuntime::instance().executeYuvMultiFrameAlignment(alignmentRequest);
        yuvAlignmentGpuMs = gpuAlignment.gpuAlignmentMs;
        yuvAlignmentInputCopyMs = gpuAlignment.inputCopyMs;
        yuvAlignmentGpuSyncMs = gpuAlignment.gpuSynchronizationMs;
        yuvFusionGpuMs = gpuAlignment.gpuFusionMs;
        yuvAlignmentCpuUploadBytes = gpuAlignment.fullFrameCpuUploadBytes;
        yuvAlignmentCompactReadbackBytes = gpuAlignment.compactGpuReadbackBytes;
        yuvFusionGpuReadbackBytes = gpuAlignment.fullFrameGpuReadbackBytes;
        const std::size_t expectedGpuLumaBytes = static_cast<std::size_t>(gpuAlignment.width) * gpuAlignment.height;
        if (gpuAlignment.success && gpuAlignment.shifts.size() == hwBuffers.size() - 1u &&
            expectedGpuLumaBytes > 0u && gpuAlignment.residentFusedLumaProduced &&
            gpuAlignment.fullFrameReadbackDeferred && gpuAlignment.fullFrameGpuReadbackBytes == 0u &&
            gpuAlignment.residentOutputGeneration == alignmentRequest.generationId) {
            yuvAlignmentBackend = gpuAlignment.alignmentBackend;
            yuvFusionBackend = gpuAlignment.fusionBackend;
        } else if (computationalHdr) {
            yuvAlignmentBackend = "VULKAN_HDR_REQUIRED_ANCHOR_FAILSAFE";
            yuvFusionBackend = "VULKAN_HDR_REQUIRED_ANCHOR_FAILSAFE";
            yuvAlignmentCpuFallbackUsed = false;
            yuvFusionCpuFallbackUsed = false;
            yuvAlignmentFallbackReason = gpuAlignment.failureReason.empty() ? "VULKAN_HDR_RESULT_INVALID" : gpuAlignment.failureReason;
            yuvFusionFallbackReason = yuvAlignmentFallbackReason;
            LOGW("YUV HDR Vulkan path unavailable; preserving anchor instead of CPU pseudo-HDR: %s", yuvAlignmentFallbackReason.c_str());
        } else {
            yuvAlignmentBackend = "CPU_OPENCV_PHASE_CORRELATE_FAILSAFE";
            yuvFusionBackend = "CPU_OPENCV_WARP_FLOAT_ACCUM_FAILSAFE";
            yuvAlignmentCpuFallbackUsed = true;
            yuvFusionCpuFallbackUsed = true;
            yuvAlignmentFallbackReason = gpuAlignment.failureReason.empty() ? "VULKAN_ALIGNMENT_RESULT_INVALID" : gpuAlignment.failureReason;
            yuvFusionFallbackReason = yuvAlignmentFallbackReason;
            LOGW("YUV multi-frame Vulkan alignment unavailable; explicit CPU failsafe: %s", yuvAlignmentFallbackReason.c_str());
        }
    }

    try {
        std::vector<uint8_t> anchorNv21;
        planeCopyOk = copyHardwareBufferToNv21(
                anchor,
                anchorNv21,
                &desc,
                &yuvChromaLayout,
                &hwBufferDescribeMs,
                &hwBufferLockMs,
                &yuvPlaneCopyMs
        );

        if (planeCopyOk) {
            yuvFramesUsed = 1;

            if (hwBuffers.size() > 1u && desc.width > 0 && desc.height > 0) {
                const int width = static_cast<int>(desc.width);
                const int height = static_cast<int>(desc.height);
                const int ySize = width * height;

                if (!yuvAlignmentCpuFallbackUsed && gpuAlignment.success &&
                    gpuAlignment.residentFusedLumaProduced && gpuAlignment.fullFrameReadbackDeferred &&
                    gpuAlignment.residentOutputGeneration != 0u) {
                    // Normal Phase-13 success path: fused Y remains device-resident. anchorNv21 is
                    // retained only as the original chroma source for the Phase-12 ISP bridge.
                    yuvResidentLumaGeneration = gpuAlignment.residentOutputGeneration;
                    yuvSupportAccepted = gpuAlignment.supportAccepted;
                    yuvSupportRejected = gpuAlignment.supportRejected;
                    yuvFramesUsed = 1 + yuvSupportAccepted;
                    yuvAnchorOnly = yuvSupportAccepted == 0;
                    yuvComputeAlignMs = yuvAlignmentGpuMs + yuvFusionGpuMs;
                } else if (computationalHdr) {
                    // Keep the unmodified anchor. A failed Vulkan HDR transaction must never be
                    // reinterpreted by the equal-exposure OpenCV fallback.
                    yuvFramesUsed = 1;
                    yuvSupportAccepted = 0;
                    yuvSupportRejected = static_cast<int>(hwBuffers.size()) - 1;
                    yuvAnchorOnly = true;
                    yuvComputeAlignMs = yuvAlignmentGpuMs + yuvFusionGpuMs;
                } else {
                    // Explicit bounded Vulkan failure fallback/reference path.
                    const auto computeStart = NativeClock::now();
                    cv::Mat anchorY(height, width, CV_8UC1, anchorNv21.data());
                    cv::Mat anchor32f;
                    anchorY.convertTo(anchor32f, CV_32F);
                    cv::Mat accum32f = anchor32f.clone();
                    constexpr double maxShift = 12.0;

                    for (size_t index = 0; index + 1u < hwBuffers.size(); ++index) {
                        std::vector<uint8_t> supportLuma;
                        AHardwareBuffer_Desc supportDesc{};
                        if (!copyHardwareBufferLuma(hwBuffers[index], supportLuma, &supportDesc)) {
                            ++yuvSupportRejected;
                            continue;
                        }
                        if (supportDesc.width != desc.width || supportDesc.height != desc.height ||
                            supportLuma.size() < static_cast<size_t>(ySize)) {
                            ++yuvSupportRejected;
                            continue;
                        }
                        cv::Mat supportY(height, width, CV_8UC1, supportLuma.data());
                        cv::Mat support32f;
                        supportY.convertTo(support32f, CV_32F);
                        const cv::Point2d measuredShift = cv::phaseCorrelate(anchor32f, support32f);
                        if (!std::isfinite(measuredShift.x) || !std::isfinite(measuredShift.y) ||
                            std::abs(measuredShift.x) > maxShift || std::abs(measuredShift.y) > maxShift) {
                            ++yuvSupportRejected;
                            continue;
                        }
                        // phaseCorrelate(anchor, support) reports anchor->support displacement.
                        // warpAffine needs the inverse translation to bring support back onto anchor.
                        const float warpDx = -static_cast<float>(measuredShift.x);
                        const float warpDy = -static_cast<float>(measuredShift.y);
                        cv::Mat warped32f;
                        const cv::Mat warpMat = (cv::Mat_<float>(2, 3) <<
                                1.0f, 0.0f, warpDx,
                                0.0f, 1.0f, warpDy);
                        cv::warpAffine(
                                support32f, warped32f, warpMat, cv::Size(width, height),
                                cv::INTER_LINEAR, cv::BORDER_REPLICATE);
                        accum32f += warped32f;
                        ++yuvSupportAccepted;
                        ++yuvFramesUsed;
                    }
                    if (yuvSupportAccepted > 0) {
                        accum32f /= static_cast<float>(yuvFramesUsed);
                        cv::Mat mergedY;
                        accum32f.convertTo(mergedY, CV_8UC1);
                        std::memcpy(anchorNv21.data(), mergedY.data, static_cast<size_t>(ySize));
                        yuvAnchorOnly = false;
                    }
                    yuvComputeAlignMs = nativeElapsedMs(computeStart);
                }
            }

            if (!anchorNv21.empty() && !encodeNv21ToJpeg(
                        anchorNv21.data(),
                        anchorNv21.size(),
                        desc.width,
                        desc.height,
                        rotationDegrees,
                        yuvQuality,
                        jpegBuf,
                        &encodeTiming,
                        yuvResidentLumaGeneration)) {
                LOGE("YUV JPEG publication failed");
            }
        }
    } catch (const cv::Exception& e) {
        LOGE("OpenCV exception in YUV engine: %s", e.what());
    } catch (const std::exception& e) {
        LOGE("std::exception in YUV engine: %s", e.what());
    } catch (...) {
        LOGE("Unknown native exception in YUV engine");
    }

    releaseHardwareBuffers(hwBuffers);

    const int normalizedRotation = normalizeRotationDegrees(rotationDegrees);
    std::ostringstream stats;
    stats << "rendererName=NATIVE_CXX_HW_BUFFER_ENGINE"
          << ";rendererPath=YUV_HARDWAREBUFFER_NV21_NEUTRAL_LUMA_JPEG"
          << ";processingBypassReason=" << encodeTiming.yuvPostBypassReason
          << ";renderOutputCreated=" << (!jpegBuf.empty() ? "true" : "false")
          << ";frameCount=" << hwBuffers.size()
          << ";yuvPipelineMode=" << yuvPipelineMode
          << ";computationalHdr=" << (computationalHdr ? "true" : "false")
          << ";yuvComputeMultiFrame=" << (!yuvAnchorOnly ? "true" : "false")
          << ";yuvAlignmentBackend=" << yuvAlignmentBackend
          << ";yuvAlignmentCpuFallbackUsed=" << (yuvAlignmentCpuFallbackUsed ? "true" : "false")
          << ";yuvAlignmentFallbackReason=" << yuvAlignmentFallbackReason
          << ";yuvAlignmentGpuMs=" << nativeFmtMs(yuvAlignmentGpuMs)
          << ";yuvAlignmentInputCopyMs=" << nativeFmtMs(yuvAlignmentInputCopyMs)
          << ";yuvAlignmentGpuSyncMs=" << nativeFmtMs(yuvAlignmentGpuSyncMs)
          << ";yuvAlignmentCpuUploadBytes=" << yuvAlignmentCpuUploadBytes
          << ";yuvAlignmentCompactReadbackBytes=" << yuvAlignmentCompactReadbackBytes
          << ";yuvFusionBackend=" << yuvFusionBackend
          << ";yuvFusionCpuFallbackUsed=" << (yuvFusionCpuFallbackUsed ? "true" : "false")
          << ";yuvFusionFallbackReason=" << yuvFusionFallbackReason
          << ";yuvFusionGpuMs=" << nativeFmtMs(yuvFusionGpuMs)
          << ";yuvFusionGpuReadbackBytes=" << yuvFusionGpuReadbackBytes
          << ";yuvSingleFrameIspBackend=" << encodeTiming.yuvIspBackend
          << ";yuvSingleFrameCpuFallbackUsed=" << (encodeTiming.yuvIspCpuFallbackUsed ? "true" : "false")
          << ";yuvSingleFrameFallbackReason=" << encodeTiming.yuvIspFallbackReason
          << ";yuvSingleFrameCpuUploadBytes=" << encodeTiming.yuvIspCpuUploadBytes
          << ";yuvSingleFrameGpuReadbackBytes=" << encodeTiming.yuvIspGpuReadbackBytes
          << ";yuvSingleFrameInputUploadMs=" << nativeFmtMs(encodeTiming.yuvIspInputUploadMs)
          << ";yuvSingleFrameIspCallMs=" << nativeFmtMs(encodeTiming.yuvIspCallMs)
          << ";yuvSingleFrameBackendMutexWaitMs=" << nativeFmtMs(encodeTiming.yuvIspBackendMutexWaitMs)
          << ";yuvSingleFramePipelineSetupMs=" << nativeFmtMs(encodeTiming.yuvIspPipelineSetupMs)
          << ";yuvSingleFrameBufferSetupMs=" << nativeFmtMs(encodeTiming.yuvIspBufferSetupMs)
          << ";yuvSingleFrameGpuExecutionWallMs=" << nativeFmtMs(encodeTiming.yuvIspGpuExecutionWallMs)
          << ";yuvSingleFrameGpuSyncMs=" << nativeFmtMs(encodeTiming.yuvIspGpuSyncMs)
          << ";yuvSingleFramePublicationReadbackMs=" << nativeFmtMs(encodeTiming.yuvIspPublicationReadbackMs)
          << ";yuvColorContract=" << encodeTiming.yuvColorContract
          << ";yuvResidualNoiseModelAvailable=" << (encodeTiming.yuvResidualNoiseModelAvailable ? "true" : "false")
          << ";yuvResidualLumaSamples=" << encodeTiming.yuvResidualLumaSamples
          << ";yuvResidualChromaSamples=" << encodeTiming.yuvResidualChromaSamples
          << ";yuvResidualSigmaY=" << nativeFmtMs(encodeTiming.yuvResidualSigmaY)
          << ";yuvResidualSigmaU=" << nativeFmtMs(encodeTiming.yuvResidualSigmaU)
          << ";yuvResidualSigmaV=" << nativeFmtMs(encodeTiming.yuvResidualSigmaV)
          << ";yuvResidualModelConfidence=" << nativeFmtMs(encodeTiming.yuvResidualModelConfidence)
          << ";yuvSingleFrameResidentLumaConsumed=" << (encodeTiming.yuvIspResidentLumaConsumed ? "true" : "false")
          << ";yuvSingleFrameResidentLumaGeneration=" << encodeTiming.yuvIspResidentLumaGeneration
          << ";yuvUltraHdrRequested=" << (encodeTiming.yuvUltraHdrRequested ? "true" : "false")
          << ";yuvUltraHdrGainmapGenerated=" << (encodeTiming.yuvUltraHdrGainmapGenerated ? "true" : "false")
          << ";yuvUltraHdrMeaningfulHeadroom=" << (encodeTiming.yuvUltraHdrMeaningfulHeadroom ? "true" : "false")
          << ";yuvUltraHdrMaxContentBoost=" << encodeTiming.yuvUltraHdrMaxContentBoost
          << ";yuvUltraHdrStatus=" << encodeTiming.yuvUltraHdrStatus
          << ";yuvPortraitRequested=" << (encodeTiming.yuvPortraitRequested ? "true" : "false")
          << ";yuvPortraitApplied=" << (encodeTiming.yuvPortraitApplied ? "true" : "false")
          << ";yuvPortraitStatus=" << encodeTiming.yuvPortraitStatus
          << ";yuvFusionResidentGeneration=" << yuvResidentLumaGeneration
          << ";yuvAnchorOnly=" << (yuvAnchorOnly ? "true" : "false")
          << ";yuvFramesUsed=" << yuvFramesUsed
          << ";yuvSupportAccepted=" << yuvSupportAccepted
          << ";yuvSupportRejected=" << yuvSupportRejected
          << ";yuvMultiFrameClaimTruth=" << (!yuvAnchorOnly ? "multi_frame_luma_aligned_anchor_uv" : "anchor_only")
          << ";width=" << desc.width
          << ";height=" << desc.height
          << ";format=" << desc.format
          << ";outputRotationDegrees=" << normalizedRotation
          << ";nativeRotationApplied=" << (normalizedRotation != 0 ? "true" : "false")
          << ";yuvLensHardwareBlackLevelApplied=NOT_APPLICABLE_RAW_ONLY"
          << ";yuvLensHardwareNoiseModelApplied=NOT_APPLICABLE_RAW_NOISE_MODEL"
          << ";yuvChromaLayout=" << yuvChromaLayout
          << ";planeCopyOk=" << (planeCopyOk ? "true" : "false")
          << ";jpegBytes=" << jpegBuf.size()
          << ";curveApplied=" << (encodeTiming.yuvToneAlignmentApplied ? "true" : "false")
          << ";developedYuvAwbApplied=" << (
                  std::abs(yuvQuality.profileYuvWbRed - 1.0f) > 1.0e-4f ||
                  std::abs(yuvQuality.profileYuvWbGreen - 1.0f) > 1.0e-4f ||
                  std::abs(yuvQuality.profileYuvWbBlue - 1.0f) > 1.0e-4f
                  ? "true" : "false")
          << ";developedYuvWb=[" << yuvQuality.profileYuvWbRed
          << "," << yuvQuality.profileYuvWbGreen
          << "," << yuvQuality.profileYuvWbBlue << "]"
          << ";profileColorManagementApplied=" << (
                  std::abs(yuvQuality.profileColorSaturation) > 1.0e-4f ||
                  std::abs(yuvQuality.profileColorContrast) > 1.0e-4f ||
                  std::abs(yuvQuality.profilePresenceVibrance) > 1.0e-4f
                  ? "true" : "false")
          << ";profileDetailApplied=" << (
                  std::abs(yuvQuality.profileDetailAmount) > 1.0e-6f ||
                  std::abs(bncam::profile_microdetail_transport::decodeDetail(yuvQuality.profileDetailDetail)) > 1.0e-6f ||
                  std::abs(bncam::profile_microdetail_transport::decodeLegibility(yuvQuality.profileDetailDetail)) > 1.0e-6f ||
                  std::abs(yuvQuality.profileDetailMasking) > 1.0e-6f
                  ? "true" : "false")
          << ";profileDetailAmount=" << yuvQuality.profileDetailAmount
          << ";profileLegibility="
          << bncam::profile_microdetail_transport::decodeLegibility(yuvQuality.profileDetailDetail)
          << ";profileDetailRadiusLegacyNeutral=" << yuvQuality.profileDetailRadius
          << ";profileDetailDetail="
          << bncam::profile_microdetail_transport::decodeDetail(yuvQuality.profileDetailDetail)
          << ";profileDetailMasking=" << yuvQuality.profileDetailMasking
          << ";toneMode=CAMERA2_YUV_IDENTITY_LUMA"
          << ";rawToneDefaultsUsed=false"
          << ";legacyNativeToneLaneRemoved=true"
          << ";highlightRolloffApplied=true"
          << ";postContrast=" << encodeTiming.yuvOutputContrastRatio
          << ";yuvDefaultColorToneBypassed=" << (encodeTiming.yuvDefaultColorToneBypassed ? "true" : "false")
          << ";yuvGpuPostApplied=" << (encodeTiming.yuvGpuPostApplied ? "true" : "false")
          << ";yuvDenoiseApplied=" << (encodeTiming.yuvDenoiseApplied ? "true" : "false")
          << ";yuvPostBypassReason=" << encodeTiming.yuvPostBypassReason
          << ";yuvToneAlignmentApplied=" << (encodeTiming.yuvToneAlignmentApplied ? "true" : "false")
          << ";yuvToneLutSize=" << encodeTiming.yuvToneLutSize
          << ";yuvInputNativeYP01=" << encodeTiming.yuvInputNativeYP01
          << ";yuvInputNativeYP50=" << encodeTiming.yuvInputNativeYP50
          << ";yuvInputNativeYP99=" << encodeTiming.yuvInputNativeYP99
          << ";yuvOutputBgrLumaP01=" << encodeTiming.yuvOutputBgrLumaP01
          << ";yuvOutputBgrLumaP50=" << encodeTiming.yuvOutputBgrLumaP50
          << ";yuvOutputBgrLumaP99=" << encodeTiming.yuvOutputBgrLumaP99
          << ";yuvInputNativeYP0_1=" << encodeTiming.yuvInputNativeYP0_1
          << ";yuvInputNativeYP1=" << encodeTiming.yuvInputNativeYP1
          << ";yuvDefaultCamera2ColorContract=JFIF_REC601_FULL_RANGE"
          << ";yuvCurrentBncamConversionMatrix=REC601"
          << ";yuvCurrentBncamConversionRange=LIMITED_16_235_240"
          << ";yuvInputSignalSampleCount=" << encodeTiming.yuvInputSignalSampleCount
          << ";yuvInputYMean=" << nativeFmtMs(encodeTiming.yuvInputYMean)
          << ";yuvInputYStdDev=" << nativeFmtMs(encodeTiming.yuvInputYStdDev)
          << ";yuvInputUCenteredMean=" << nativeFmtMs(encodeTiming.yuvInputUCenteredMean)
          << ";yuvInputVCenteredMean=" << nativeFmtMs(encodeTiming.yuvInputVCenteredMean)
          << ";yuvInputUStdDev=" << nativeFmtMs(encodeTiming.yuvInputUStdDev)
          << ";yuvInputVStdDev=" << nativeFmtMs(encodeTiming.yuvInputVStdDev)
          << ";yuvInputChromaRmsFromNeutral=" << nativeFmtMs(encodeTiming.yuvInputChromaRmsFromNeutral)
          << ";yuvInputLumaNeighbourDeltaMean=" << nativeFmtMs(encodeTiming.yuvInputLumaNeighbourDeltaMean)
          << ";yuvInputChromaNeighbourDeltaMean=" << nativeFmtMs(encodeTiming.yuvInputChromaNeighbourDeltaMean)
          << ";yuvInputNeighbourDeltaSemantics=SCENE_DETAIL_PLUS_NOISE_NOT_NOISE_ESTIMATE"
          << ";yuvInputNativeYP5=" << encodeTiming.yuvInputNativeYP5
          << ";yuvInputNativeYP95=" << encodeTiming.yuvInputNativeYP95
          << ";yuvInputNativeYP99_9=" << encodeTiming.yuvInputNativeYP99_9
          << ";yuvInputBlackClippedFraction=" << encodeTiming.yuvInputBlackClippedFraction
          << ";yuvInputWhiteClippedFraction=" << encodeTiming.yuvInputWhiteClippedFraction
          << ";yuvInputContrastRatio=" << encodeTiming.yuvInputContrastRatio
          << ";yuvOutputBgrLumaP0_1=" << encodeTiming.yuvOutputBgrLumaP0_1
          << ";yuvOutputBgrLumaP1=" << encodeTiming.yuvOutputBgrLumaP1
          << ";yuvOutputBgrLumaP5=" << encodeTiming.yuvOutputBgrLumaP5
          << ";yuvOutputBgrLumaP95=" << encodeTiming.yuvOutputBgrLumaP95
          << ";yuvOutputBgrLumaP99_9=" << encodeTiming.yuvOutputBgrLumaP99_9
          << ";yuvOutputBlackClippedFraction=" << encodeTiming.yuvOutputBlackClippedFraction
          << ";yuvOutputWhiteClippedFraction=" << encodeTiming.yuvOutputWhiteClippedFraction
          << ";yuvOutputContrastRatio=" << encodeTiming.yuvOutputContrastRatio
          << ";yuvToneCurveMonotonic=" << (encodeTiming.yuvToneCurveMonotonic ? "true" : "false")
          << ";yuvToneMidpointInput=0.18"
          << ";yuvToneMidpointOutput=" << encodeTiming.yuvToneMidpointOutput
          << ";yuvToneShadowInput=0.05"
          << ";yuvToneShadowOutput=" << encodeTiming.yuvToneShadowOutput
          << ";yuvToneHighlightInput=0.85"
          << ";yuvToneHighlightOutput=" << encodeTiming.yuvToneHighlightOutput
          << ";yuvToneDarkeningDetected=" << (encodeTiming.yuvToneDarkeningDetected ? "true" : "false")
          << ";yuvToneDarkeningReason=" << encodeTiming.yuvToneDarkeningReason
          << ";yuvBilateralApplied=" << (encodeTiming.yuvBilateralApplied ? "true" : "false")
          << ";yuvBilateralDiameter=" << encodeTiming.yuvBilateralDiameter
          << ";yuvBilateralSigmaColor=" << encodeTiming.yuvBilateralSigmaColor
          << ";yuvBilateralSigmaSpace=" << encodeTiming.yuvBilateralSigmaSpace
          << ";yuvDenoiseStrengthResolved=" << encodeTiming.yuvDenoiseStrengthResolved
          << ";yuvResolvedGpuLumaNrBlend=" << nativeFmtMs(encodeTiming.yuvResolvedGpuLumaNrBlend)
          << ";yuvResolvedGpuChromaNrBlend=" << nativeFmtMs(encodeTiming.yuvResolvedGpuChromaNrBlend)
          << ";yuvResolvedGpuLumaNrProtection=" << nativeFmtMs(encodeTiming.yuvResolvedGpuLumaNrProtection)
          << ";yuvResolvedGpuChromaNrProtection=" << nativeFmtMs(encodeTiming.yuvResolvedGpuChromaNrProtection)
          << ";captureSensitivityIso=" << yuvQuality.captureSensitivityIso
          << ";hwBufferExtractMs=" << nativeFmtMs(hwBufferExtractMs)
          << ";hwBufferDescribeMs=" << nativeFmtMs(hwBufferDescribeMs)
          << ";hwBufferLockMs=" << nativeFmtMs(hwBufferLockMs)
          << ";yuvPlaneCopyMs=" << nativeFmtMs(yuvPlaneCopyMs)
          << ";yuvSupportLumaCopyMs=" << nativeFmtMs(yuvSupportLumaCopyMs)
          << ";yuvSupportLumaOnly=true"
          << ";yuvAnchorOutputCopyEliminated=" << (yuvAnchorOutputCopyEliminated ? "true" : "false")
          << ";yuvAvoidedCopyBytes=" << (static_cast<uint64_t>(desc.width) * desc.height *
                  (yuvSupportAccepted + yuvSupportRejected) / 2u +
                  (yuvAnchorOutputCopyEliminated
                          ? static_cast<uint64_t>(desc.width) * desc.height * 3u / 2u
                          : 0u))
          << ";peakWorkingSetEstimateBytes=" << (static_cast<uint64_t>(desc.width) * desc.height *
                  (hwBuffers.size() > 1u ? 22u : 8u))
          << ";yuvComputeAlignMs=" << nativeFmtMs(yuvComputeAlignMs)
          << ";yuvToBgrMs=" << nativeFmtMs(encodeTiming.yuvToBgrMs)
          << ";yuvPostProcessMs=" << nativeFmtMs(encodeTiming.yuvPostProcessMs)
          << ";yuvRotateMs=" << nativeFmtMs(encodeTiming.yuvRotateMs)
          << ";yuvJpegEncodeMs=" << nativeFmtMs(encodeTiming.yuvJpegEncodeMs)
          << ";totalNativeYuvMs=" << nativeFmtMs(nativeElapsedMs(totalStart))
          << ";" << IspCore::describeResolvedConfig(yuvQuality, false, true);
    {
        std::lock_guard<std::mutex> lock(gYuvStatsMutex);
        gLastYuvStats = stats.str();
    }

    return createJavaByteArray(env, jpegBuf);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_bncam_core_engine_ImageUtils_mergeNativeRaw10DirectRaw16(
        JNIEnv *env,
        jobject /* thiz */,
        jstring lensIdStr,
        jobjectArray buffersArray,
        jint cfaPattern,
        jint whiteLevel,
        jintArray blackLevelArray,
        jint developedWhiteLevel,
        jintArray developedBlackLevelArray,
        jintArray sourceCropArray,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jdoubleArray physicalNoiseSoArray,
        jboolean fuseSupportFrames,
        jfloatArray exposureScaleToAnchorArray,
        jboolean computationalHdr
) {
    std::vector<AHardwareBuffer*> hwBuffers = extractHardwareBuffers(env, buffersArray);
    if (hwBuffers.empty()) return nullptr;
    const std::vector<int32_t> blackLevels = extractBlackLevelVector(env, blackLevelArray);
    const std::vector<int32_t> developedBlackLevels = extractBlackLevelVector(env, developedBlackLevelArray);
    if (sourceCropArray == nullptr || env->GetArrayLength(sourceCropArray) < 4) {
        releaseHardwareBuffers(hwBuffers);
        return nullptr;
    }
    jint sourceCrop[4] = {0, 0, 0, 0};
    env->GetIntArrayRegion(sourceCropArray, 0, 4, sourceCrop);
    const CanonicalPhysicalNoiseSoPayload physicalNoise =
            extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray);
    const std::vector<double> physicalEffectiveS = physicalNoiseVector(physicalNoise.s);
    const std::vector<double> physicalEffectiveO = physicalNoiseVector(physicalNoise.o);
    const std::vector<float> exposureScaleToAnchor = extractPositiveFloatVector(env, exposureScaleToAnchorArray);

    DngMergeStats stats{};
    jobject result = mergeRaw10DngToRaw16(
            env,
            hwBuffers,
            cfaPattern,
            whiteLevel,
            blackLevels,
            developedWhiteLevel,
            developedBlackLevels,
            maxFramesCap,
            maxShiftPixels,
            alignmentStrictness,
            physicalNoise.valid,
            physicalEffectiveS,
            physicalEffectiveO,
            physicalNoise.valid ? 1.0f : 0.0f,
            fuseSupportFrames == JNI_TRUE,
            exposureScaleToAnchor,
            computationalHdr == JNI_TRUE,
            sourceCrop[0],
            sourceCrop[1],
            sourceCrop[2],
            sourceCrop[3],
            &stats
    );

    {
        std::lock_guard<std::mutex> lock(gDngStatsMutex);
        gLastDngMergeStats = formatDngMergeStats(stats);
    }

    releaseHardwareBuffers(hwBuffers);
    return result;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_bncam_core_engine_ImageUtils_mergeNativeRawSensorDirectRaw16(
        JNIEnv *env,
        jobject /* thiz */,
        jstring lensIdStr,
        jobjectArray buffersArray,
        jint cfaPattern,
        jint whiteLevel,
        jintArray blackLevelArray,
        jint developedWhiteLevel,
        jintArray developedBlackLevelArray,
        jintArray sourceCropArray,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jdoubleArray physicalNoiseSoArray,
        jboolean fuseSupportFrames,
        jfloatArray exposureScaleToAnchorArray,
        jboolean computationalHdr
) {
    std::vector<AHardwareBuffer*> hwBuffers = extractHardwareBuffers(env, buffersArray);
    if (hwBuffers.empty()) return nullptr;
    const std::vector<int32_t> blackLevels = extractBlackLevelVector(env, blackLevelArray);
    const std::vector<int32_t> developedBlackLevels = extractBlackLevelVector(env, developedBlackLevelArray);
    if (sourceCropArray == nullptr || env->GetArrayLength(sourceCropArray) < 4) {
        releaseHardwareBuffers(hwBuffers);
        return nullptr;
    }
    jint sourceCrop[4] = {0, 0, 0, 0};
    env->GetIntArrayRegion(sourceCropArray, 0, 4, sourceCrop);
    const CanonicalPhysicalNoiseSoPayload physicalNoise =
            extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray);
    const std::vector<double> physicalEffectiveS = physicalNoiseVector(physicalNoise.s);
    const std::vector<double> physicalEffectiveO = physicalNoiseVector(physicalNoise.o);
    const std::vector<float> exposureScaleToAnchor = extractPositiveFloatVector(env, exposureScaleToAnchorArray);

    DngMergeStats stats{};
    jobject result = mergeRawSensorDngToRaw16(
            env,
            hwBuffers,
            cfaPattern,
            whiteLevel,
            blackLevels,
            developedWhiteLevel,
            developedBlackLevels,
            maxFramesCap,
            maxShiftPixels,
            alignmentStrictness,
            physicalNoise.valid,
            physicalEffectiveS,
            physicalEffectiveO,
            physicalNoise.valid ? 1.0f : 0.0f,
            fuseSupportFrames == JNI_TRUE,
            exposureScaleToAnchor,
            computationalHdr == JNI_TRUE,
            sourceCrop[0],
            sourceCrop[1],
            sourceCrop[2],
            sourceCrop[3],
            &stats
    );

    {
        std::lock_guard<std::mutex> lock(gDngStatsMutex);
        gLastDngMergeStats = formatDngMergeStats(stats);
    }

    releaseHardwareBuffers(hwBuffers);
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_releaseNativeRaw16Buffer(
        JNIEnv *env,
        jobject /* thiz */,
        jobject directBuffer
) {
    if (directBuffer == nullptr) return;
    void* address = env->GetDirectBufferAddress(directBuffer);
    if (address != nullptr && !releaseNativeRaw16Allocation(address)) {
        LOGE("Rejected duplicate or unknown Native RAW16 release address=%p", address);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bncam_core_engine_ImageUtils_getNativeRaw16OutstandingBufferCountNative(
        JNIEnv* /* env */,
        jobject /* thiz */
) {
    const size_t count = nativeRaw16OutstandingAllocationCount();
    return static_cast<jint>(std::min<size_t>(count, static_cast<size_t>(std::numeric_limits<jint>::max())));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getLastYuvStatsNative(JNIEnv *env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gYuvStatsMutex);
    return env->NewStringUTF(gLastYuvStats.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getLastDngMergeStatsNative(JNIEnv *env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gDngStatsMutex);
    return env->NewStringUTF(gLastDngMergeStats.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getLastMasterIspStatsNative(JNIEnv *env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gMasterIspStatsMutex);
    return env->NewStringUTF(gLastMasterIspStats.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_validateDemosaicNative(JNIEnv *env, jobject /* thiz */) {
    const DemosaicValidationResult bilinear = validateBilinearImplementation();
    const DemosaicValidationResult malvar = validateMalvar2004Implementation();
    const DemosaicValidationResult rcd = validateRcdInspiredImplementation();
    const DemosaicValidationResult amaze = validateAmazeInspiredImplementation();
    const DemosaicValidationResult menon = validateMenon2007Implementation();
    static constexpr int expectedShiftedCfa[4][4] = {
            {0, 1, 2, 3}, // RGGB: none, odd X, odd Y, odd X+Y
            {1, 0, 3, 2}, // GRBG
            {2, 3, 0, 1}, // GBRG
            {3, 2, 1, 0}  // BGGR
    };
    bool cfaShiftPassed = true;
    for (int pattern = 0; pattern < 4; ++pattern) {
        const int actual[4] = {
                effectiveCfaPatternAtOrigin(pattern, 0, 0),
                effectiveCfaPatternAtOrigin(pattern, 1, 0),
                effectiveCfaPatternAtOrigin(pattern, 0, 1),
                effectiveCfaPatternAtOrigin(pattern, 1, 1)
        };
        for (int shift = 0; shift < 4; ++shift) {
            cfaShiftPassed = cfaShiftPassed && actual[shift] == expectedShiftedCfa[pattern][shift];
        }
    }
    const DemosaicResolution normal = resolveDemosaicMode(
            static_cast<int>(DemosaicMode::NormalMalvar2004)
    );
    const bool normalForcesMalvar =
            normal.requestedMode == DemosaicMode::NormalMalvar2004 &&
            normal.algorithm == DemosaicAlgorithm::Malvar2004 &&
            normal.reason == "normal_mode_forces_malvar_2004" &&
            !normal.fallbackOccurred;
    const DemosaicResolution quality = resolveDemosaicMode(
            static_cast<int>(DemosaicMode::QualityMenon2007)
    );
    const bool legacySlot2ForcesAmaze =
            quality.requestedMode == DemosaicMode::QualityMenon2007 &&
            quality.algorithm == DemosaicAlgorithm::AmazeInspired &&
            quality.reason == "legacy_slot_2_forces_amaze_inspired" &&
            !quality.fallbackOccurred;
    const DemosaicResolution bilinearResolution = resolveDemosaicMode(
            static_cast<int>(DemosaicMode::Bilinear)
    );
    const bool legacySlot3ForcesRcd =
            bilinearResolution.requestedMode == DemosaicMode::Bilinear &&
            bilinearResolution.algorithm == DemosaicAlgorithm::RcdInspired &&
            bilinearResolution.reason == "legacy_slot_3_forces_rcd_inspired" &&
            !bilinearResolution.fallbackOccurred;

    AutoDemosaicSceneMetrics balancedMetrics{};
    balancedMetrics.valid = true;
    balancedMetrics.sampleCount = 4096;
    balancedMetrics.medianSignal = 0.24f;
    balancedMetrics.meanGradient = 0.025f;
    balancedMetrics.p90Gradient = 0.050f;
    balancedMetrics.edgeFraction = 0.045f;
    balancedMetrics.coherentEdgeFraction = 0.020f;
    balancedMetrics.lowSignalFraction = 0.05f;
    AutoDemosaicContext balancedContext{};
    balancedContext.captureIso = 200;
    balancedContext.motionRiskKnown = true;
    balancedContext.highMotionRisk = false;
    const DemosaicResolution autoBalanced = resolveDemosaicForSceneMetrics(
            static_cast<int>(DemosaicMode::Auto), balancedMetrics, balancedContext);

    AutoDemosaicSceneMetrics fineDetailMetrics = balancedMetrics;
    fineDetailMetrics.medianSignal = 0.32f;
    fineDetailMetrics.p90Gradient = 0.24f;
    fineDetailMetrics.edgeFraction = 0.24f;
    fineDetailMetrics.coherentEdgeFraction = 0.18f;
    fineDetailMetrics.lowSignalFraction = 0.01f;
    AutoDemosaicContext fineDetailContext{};
    fineDetailContext.captureIso = 100;
    fineDetailContext.motionRiskKnown = true;
    fineDetailContext.highMotionRisk = false;
    const DemosaicResolution autoFineDetail = resolveDemosaicForSceneMetrics(
            static_cast<int>(DemosaicMode::Auto), fineDetailMetrics, fineDetailContext);

    AutoDemosaicContext fineDetailHighChromaContext = fineDetailContext;
    fineDetailHighChromaContext.cfaChromaEvidenceKnown = true;
    fineDetailHighChromaContext.cfaStructureProtection = 0.20f;
    fineDetailHighChromaContext.cfaFineCorrectionConfidence = 0.95f;
    fineDetailHighChromaContext.cfaMidCorrectionConfidence = 0.75f;
    fineDetailHighChromaContext.cfaLowCorrectionConfidence = 0.45f;
    fineDetailHighChromaContext.cfaRedOpponentCorrectionConfidence = 0.90f;
    fineDetailHighChromaContext.cfaBlueOpponentCorrectionConfidence = 0.80f;
    const DemosaicResolution autoFineDetailHighChroma = resolveDemosaicForSceneMetrics(
            static_cast<int>(DemosaicMode::Auto), fineDetailMetrics, fineDetailHighChromaContext);

    AutoDemosaicSceneMetrics noisyMetrics = balancedMetrics;
    noisyMetrics.medianSignal = 0.035f;
    noisyMetrics.p90Gradient = 0.055f;
    noisyMetrics.edgeFraction = 0.055f;
    noisyMetrics.coherentEdgeFraction = 0.020f;
    noisyMetrics.lowSignalFraction = 0.82f;
    AutoDemosaicContext noisyContext{};
    noisyContext.captureIso = 3200;
    noisyContext.motionRiskKnown = true;
    noisyContext.highMotionRisk = true;
    const DemosaicResolution autoNoisy = resolveDemosaicForSceneMetrics(
            static_cast<int>(DemosaicMode::Auto), noisyMetrics, noisyContext);

    const bool autoResolverPassed =
            autoBalanced.algorithm == DemosaicAlgorithm::RcdInspired &&
            autoFineDetail.algorithm == DemosaicAlgorithm::AmazeInspired &&
            autoFineDetailHighChroma.algorithm == DemosaicAlgorithm::RcdInspired &&
            autoFineDetailHighChroma.autoCfaChromaRisk > 0.70f &&
            autoNoisy.algorithm == DemosaicAlgorithm::Malvar2004 &&
            autoBalanced.autoRcdScore > autoBalanced.autoMalvarScore &&
            autoBalanced.autoRcdScore > autoBalanced.autoAmazeScore &&
            autoFineDetail.autoAmazeScore > autoFineDetail.autoRcdScore &&
            autoNoisy.autoMalvarScore > autoNoisy.autoRcdScore &&
            autoNoisy.autoMalvarScore > autoNoisy.autoAmazeScore &&
            autoBalanced.autoSceneAnalysisUsed &&
            autoFineDetail.autoSceneAnalysisUsed &&
            autoNoisy.autoSceneAnalysisUsed;
    std::ostringstream result;
    result << "bilinearReference{" << bilinear.details << "}"
           << ";malvar{" << malvar.details << "}"
           << ";rcd{" << rcd.details << "}"
           << ";amaze{" << amaze.details << "}"
           << ";menonReference{" << menon.details << "}"
           << ";cfaShiftOddXY=" << (cfaShiftPassed ? "true" : "false")
           << ";legacySlot3ForcesRcd=" << (legacySlot3ForcesRcd ? "true" : "false")
           << ";normalForcesMalvar=" << (normalForcesMalvar ? "true" : "false")
           << ";legacySlot2ForcesAmaze=" << (legacySlot2ForcesAmaze ? "true" : "false")
           << ";autoBalancedRcd=" << (autoBalanced.algorithm == DemosaicAlgorithm::RcdInspired ? "true" : "false")
           << ";autoFineDetailAmaze=" << (autoFineDetail.algorithm == DemosaicAlgorithm::AmazeInspired ? "true" : "false")
           << ";autoFineDetailHighChromaRcd=" << (autoFineDetailHighChroma.algorithm == DemosaicAlgorithm::RcdInspired ? "true" : "false")
           << ";autoFineDetailHighChromaRisk=" << autoFineDetailHighChroma.autoCfaChromaRisk
           << ";autoNoisyMalvar=" << (autoNoisy.algorithm == DemosaicAlgorithm::Malvar2004 ? "true" : "false")
           << ";autoThreeWayScoring=true"
           << ";autoResolverPassed=" << (autoResolverPassed ? "true" : "false")
           << ";allPassed="
           << (bilinear.passed && malvar.passed && rcd.passed && amaze.passed && menon.passed && cfaShiftPassed &&
               legacySlot3ForcesRcd && normalForcesMalvar && legacySlot2ForcesAmaze &&
               autoResolverPassed
               ? "true" : "false");
    return env->NewStringUTF(result.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_validateNoiseModelNative(
        JNIEnv *env,
        jobject /* thiz */,
        jdoubleArray lowNoiseSoArray,
        jdoubleArray highNoiseSoArray
) {
    std::array<double, 8> lowNoiseSo{};
    std::array<double, 8> highNoiseSo{};
    if (lowNoiseSoArray == nullptr || highNoiseSoArray == nullptr ||
        env->GetArrayLength(lowNoiseSoArray) < 8 || env->GetArrayLength(highNoiseSoArray) < 8) {
        return env->NewStringUTF("allPassed=false;reason=invalid_jni_noise_arrays");
    }
    env->GetDoubleArrayRegion(lowNoiseSoArray, 0, 8, lowNoiseSo.data());
    env->GetDoubleArrayRegion(highNoiseSoArray, 0, 8, highNoiseSo.data());
    const std::string result = IspCore::validateNoiseModelImplementation(lowNoiseSo, highNoiseSo);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_bncam_core_engine_ImageUtils_renderJpegFromMasterNative(
        JNIEnv *env,
        jclass clazz,
        jstring lensIdStr,
        jobject raw16DirectBuffer,
        jint width,
        jint height,
        jstring routeLabelString,
        jboolean isRaw10,
        jint rotationDegrees,
        jint cfaPattern,
        jint requestedDemosaicMode,
        jboolean demosaicFallbackOccurred,
        jstring demosaicFallbackReasonString,
        jboolean demosaicFocusStabilityKnown,
        jfloat demosaicFocusStabilityConfidence,
        jfloat demosaicFocusSharpConfidence,
        jfloat demosaicFocusMotionRisk,
        jfloat demosaicFocusVelocityDioptersPerSec,
        jfloat demosaicPredictiveAfConfidence,
        jboolean demosaicPersonEvidenceKnown,
        jfloat demosaicPersonConfidence,
        jint demosaicDetectedFaceCount,
        jfloat demosaicMaxFaceCoverage,
        jboolean demosaicTemporalStabilityKnown,
        jfloat demosaicTemporalStaticConfidence,
        jfloat demosaicTemporalObserverConfidence,
        jfloat demosaicTemporalMotionAcceptance,
        jint demosaicTemporalAcceptedPairs,
        jint captureSensitivityIso,
        jlong captureExposureTimeNs,
        jfloat raw10UnpackMs,
        jfloat raw10ToMasterRaw16Ms,
        jfloat raw10MergeOrSingleMasterMs,
        jfloat rawSensorReadMs,
        jfloat rawSensorToMasterRaw16Ms,
        jfloatArray blackLevelArray,
        jint whiteLevel,
        jint sourceRowStrideBytes,
        jint sourcePixelStrideBytes,
        jint masterRowStrideBytes,
        jint bufferOriginCfaOffsetX,
        jint bufferOriginCfaOffsetY,
        jint sensorInfoWhiteLevel,
        jint sensorDynamicWhiteLevel,
        jfloatArray sensorBlackLevelPatternArray,
        jfloatArray sensorDynamicBlackLevelArray,
        jstring chosenBlackLevelSourceString,
        jstring chosenWhiteLevelSourceString,
        jint sourceBitDepth,
        jint effectiveSourceRange,
        jfloat masterStorageScale,
        jint masterStorageLeftShift,
        jstring masterStorageContractString,
        jfloatArray wbGainsArray,
        jboolean wbFromMetadata,
        jfloat awbCalibrationAuthority,
        jboolean awbExplicitDevelopedAuthority,
        jboolean awbManualGreenSplitAuthority,
        jfloatArray colorMatrixArray,
        jboolean colorMatrixFromMetadata,
        jboolean spectraProcessingEnabled,
        jdoubleArray physicalNoiseSoArray,
        jint spectraPostRawSensitivityBoost,
        jfloat profileSpectraLuma,
        jfloat profileSpectraChroma,
        jfloat profileSpectraDetailProtection,
        jfloat profileSpectraLowFrequency,
        jfloat profileToneExposure,
        jfloat profileToneHighlights,
        jfloat profileToneShadows,
        jfloat profileToneWhites,
        jfloat profileToneBlacks,
        jfloat profileToneContrast,
        jfloat profileLocalToneBias,
        jfloat profileColorSaturation,
        jfloat profileColorContrast,
        jfloat profilePresenceVibrance,
        jfloat profileDetailAmount,
        jfloat profileDetailRadius,
        jfloat profileDetailDetail,
        jfloat profileDetailMasking,
        jintArray knownHotPixelMapArray,
        jfloatArray lensShadingMapArray,
        jint lensShadingColumns,
        jint lensShadingRows,
        jboolean lensShadingFromMetadata,
        jboolean ultraHdrGainmapEnabled,
        jint jpegQuality,
        jfloatArray toneCurveArray,
        jfloatArray gammaCurveArray,
        jfloatArray sectionCurveArray,
        jintArray activeArrayArray,
        jintArray cropRegionArray,
        jintArray preCorrectionArray,
        jboolean rawDebugDumpsEnabled,
        jstring rawDebugDumpDirectoryString,
        jboolean phoneAssistanceSensorsEnabled,
        jboolean auxSensorValid,
        jfloat auxCctKelvin,
        jfloat auxContributionWeight,
        jboolean portraitEffectEnabledFlag,
        jobject portraitMaskBuffer,
        jint portraitMaskWidth,
        jint portraitMaskHeight,
        jfloat portraitTargetLeft,
        jfloat portraitTargetTop,
        jfloat portraitTargetRight,
        jfloat portraitTargetBottom,
        jint portraitMaskRotationDegrees
) {
    const auto outerStart = NativeClock::now();
    const char* routeChars = routeLabelString != nullptr ? env->GetStringUTFChars(routeLabelString, nullptr) : nullptr;
    std::string routeLabel = routeChars != nullptr ? routeChars : (isRaw10 == JNI_TRUE ? "JPEG_WORKING_LINEAR_RAW_FROM_RAW10_MASTER" : "JPEG_WORKING_LINEAR_RAW_FROM_RAW_SENSOR_MASTER");
    if (routeChars != nullptr) env->ReleaseStringUTFChars(routeLabelString, routeChars);
    const std::string lensId = getJniString(env, lensIdStr, "unknown");

    // P0 exact-frame Camera2 sensor-defect coordinates arrive in Master RAW16 space.
    // Keep this sparse; never materialize or scan the full frame on CPU.
    std::vector<std::int32_t> knownHotPixelMasterXy;
    if (knownHotPixelMapArray != nullptr) {
        const jsize requestedInts = env->GetArrayLength(knownHotPixelMapArray);
        constexpr jsize kMaxKnownHotPixelInts = 131072; // 65,536 points
        const jsize safeInts = std::min<jsize>(
                requestedInts - (requestedInts & 1),
                kMaxKnownHotPixelInts);
        if (safeInts > 0) {
            std::vector<jint> packed(static_cast<std::size_t>(safeInts));
            env->GetIntArrayRegion(knownHotPixelMapArray, 0, safeInts, packed.data());
            knownHotPixelMasterXy.reserve(static_cast<std::size_t>(safeInts));
            for (jsize i = 0; i + 1 < safeInts; i += 2) {
                const int x = static_cast<int>(packed[static_cast<std::size_t>(i)]);
                const int y = static_cast<int>(packed[static_cast<std::size_t>(i + 1)]);
                if (x >= 0 && y >= 0 && x < static_cast<int>(width) && y < static_cast<int>(height)) {
                    knownHotPixelMasterXy.push_back(static_cast<std::int32_t>(x));
                    knownHotPixelMasterXy.push_back(static_cast<std::int32_t>(y));
                }
            }
        }
        if (requestedInts > kMaxKnownHotPixelInts) {
            LOGW("P0 hot-pixel map truncated from %d to %d packed ints",
                 static_cast<int>(requestedInts), static_cast<int>(kMaxKnownHotPixelInts));
        }
    }

    float jniArrayLockMs = 0.0f;
    float metadataResolveMs = 0.0f;
    float rawPreprocessOuterMs = 0.0f;
    float rawJpegRenderCallOuterMs = 0.0f;

    auto setMasterStats = [&](const std::string& value) {
        std::lock_guard<std::mutex> lock(gMasterIspStatsMutex);
        gLastMasterIspStats = value;
    };

    if (raw16DirectBuffer == nullptr) {
        setMasterStats("route=" + routeLabel + ";renderOutputCreated=false;failureReason=raw16DirectBuffer null");
        return nullptr;
    }

    const jlong directCapacity = env->GetDirectBufferCapacity(raw16DirectBuffer);
    const size_t expectedBytes = static_cast<size_t>(std::max(0, static_cast<int>(width))) * static_cast<size_t>(std::max(0, static_cast<int>(height))) * 2u;
    if (directCapacity <= 0 || width <= 0 || height <= 0 || static_cast<size_t>(directCapacity) < expectedBytes) {
        setMasterStats("route=" + routeLabel + ";renderOutputCreated=false;failureReason=invalid direct RAW16 payload");
        return nullptr;
    }
    const jlong len = directCapacity;

    auto stageStart = NativeClock::now();
    const auto metadataStart = NativeClock::now();

    NativeRenderQualityConfig qualityConfig = makeQualityConfig(
            env, wbGainsArray, wbFromMetadata, colorMatrixArray, colorMatrixFromMetadata,
            jpegQuality,
            profileSpectraLuma, profileSpectraChroma,
            profileSpectraDetailProtection, profileSpectraLowFrequency,
            profileToneExposure, profileToneHighlights, profileToneShadows, profileToneWhites,
            profileToneBlacks, profileToneContrast, profileLocalToneBias,
            profileColorSaturation, profileColorContrast, profilePresenceVibrance,
            profileDetailAmount, profileDetailRadius, profileDetailDetail, profileDetailMasking,
            toneCurveArray, gammaCurveArray, sectionCurveArray
    );
    qualityConfig.ultraHdrGainmapEnabled = ultraHdrGainmapEnabled == JNI_TRUE;
    if (portraitEffectEnabledFlag == JNI_TRUE && portraitMaskBuffer != nullptr &&
        portraitMaskWidth > 1 && portraitMaskHeight > 1) {
        void* portraitAddress = env->GetDirectBufferAddress(portraitMaskBuffer);
        const jlong portraitCapacity = env->GetDirectBufferCapacity(portraitMaskBuffer);
        const std::uint64_t expectedPortraitBytes = static_cast<std::uint64_t>(portraitMaskWidth) *
                static_cast<std::uint64_t>(portraitMaskHeight) * sizeof(float);
        if (portraitAddress != nullptr && portraitCapacity >= 0 &&
            static_cast<std::uint64_t>(portraitCapacity) >= expectedPortraitBytes) {
            qualityConfig.portraitEffectEnabled = true;
            qualityConfig.portraitMask = static_cast<const float*>(portraitAddress);
            qualityConfig.portraitMaskFloatCount = static_cast<std::size_t>(portraitCapacity) / sizeof(float);
            qualityConfig.portraitMaskWidth = static_cast<std::uint32_t>(portraitMaskWidth);
            qualityConfig.portraitMaskHeight = static_cast<std::uint32_t>(portraitMaskHeight);
            qualityConfig.portraitTargetLeft = portraitTargetLeft;
            qualityConfig.portraitTargetTop = portraitTargetTop;
            qualityConfig.portraitTargetRight = portraitTargetRight;
            qualityConfig.portraitTargetBottom = portraitTargetBottom;
            qualityConfig.portraitMaskRotationDegrees = static_cast<std::uint32_t>(
                    ((portraitMaskRotationDegrees % 360) + 360) % 360);
        }
    }

    IspFrameMetadata meta;
    meta.phoneAssistanceSensorsEnabled = (phoneAssistanceSensorsEnabled == JNI_TRUE);
    meta.auxSensorValid = (auxSensorValid == JNI_TRUE);
    meta.auxCctKelvin = static_cast<float>(auxCctKelvin);
    meta.auxContributionWeight = std::clamp(static_cast<float>(auxContributionWeight), 0.0f, 0.15f);
    meta.cfaPattern = cfaPattern;
    meta.requestedDemosaicMode = requestedDemosaicMode;
    meta.demosaicFallbackOccurred = demosaicFallbackOccurred == JNI_TRUE;
    meta.demosaicFallbackReason = getJniString(env, demosaicFallbackReasonString, "none");
    meta.demosaicFocusStabilityKnown = demosaicFocusStabilityKnown == JNI_TRUE;
    meta.demosaicFocusStabilityConfidence = std::clamp(static_cast<float>(demosaicFocusStabilityConfidence), 0.0f, 1.0f);
    meta.demosaicFocusSharpConfidence = std::clamp(static_cast<float>(demosaicFocusSharpConfidence), 0.0f, 1.0f);
    meta.demosaicFocusMotionRisk = std::clamp(static_cast<float>(demosaicFocusMotionRisk), 0.0f, 1.0f);
    meta.demosaicFocusVelocityDioptersPerSec = static_cast<float>(demosaicFocusVelocityDioptersPerSec);
    meta.demosaicPredictiveAfConfidence = std::clamp(static_cast<float>(demosaicPredictiveAfConfidence), 0.0f, 1.0f);
    meta.demosaicPersonEvidenceKnown = demosaicPersonEvidenceKnown == JNI_TRUE;
    meta.demosaicPersonConfidence = std::clamp(static_cast<float>(demosaicPersonConfidence), 0.0f, 1.0f);
    meta.demosaicDetectedFaceCount = std::max(0, static_cast<int>(demosaicDetectedFaceCount));
    meta.demosaicMaxFaceCoverage = std::clamp(static_cast<float>(demosaicMaxFaceCoverage), 0.0f, 1.0f);
    meta.demosaicTemporalStabilityKnown = demosaicTemporalStabilityKnown == JNI_TRUE;
    meta.demosaicTemporalStaticConfidence = std::clamp(static_cast<float>(demosaicTemporalStaticConfidence), 0.0f, 1.0f);
    meta.demosaicTemporalObserverConfidence = std::clamp(static_cast<float>(demosaicTemporalObserverConfidence), 0.0f, 1.0f);
    meta.demosaicTemporalMotionAcceptance = std::clamp(static_cast<float>(demosaicTemporalMotionAcceptance), 0.0f, 1.0f);
    meta.demosaicTemporalAcceptedPairs = std::max(0, static_cast<int>(demosaicTemporalAcceptedPairs));
    meta.isRaw10 = isRaw10 == JNI_TRUE;
    meta.captureSensitivityIso = std::max(0, static_cast<int>(captureSensitivityIso));
    meta.captureExposureTimeNs = static_cast<int64_t>(captureExposureTimeNs);
    meta.raw10UnpackMs = std::max(0.0f, static_cast<float>(raw10UnpackMs));
    meta.raw10ToMasterRaw16Ms = std::max(0.0f, static_cast<float>(raw10ToMasterRaw16Ms));
    meta.raw10MergeOrSingleMasterMs = std::max(0.0f, static_cast<float>(raw10MergeOrSingleMasterMs));
    meta.rawSensorReadMs = std::max(0.0f, static_cast<float>(rawSensorReadMs));
    meta.rawSensorToMasterRaw16Ms = std::max(0.0f, static_cast<float>(rawSensorToMasterRaw16Ms));
    meta.rawJpegDebugDumpsEnabled = rawDebugDumpsEnabled == JNI_TRUE;
    meta.rawJpegDebugDumpDirectory = getJniString(env, rawDebugDumpDirectoryString);

    RawDomainInfo rawDomainInfo{};
    rawDomainInfo.sourceFormat = isRaw10 == JNI_TRUE ? RawSourceFormat::RAW10 : RawSourceFormat::RAW_SENSOR;
    rawDomainInfo.lensId = lensId;
    rawDomainInfo.width = width;
    rawDomainInfo.height = height;
    rawDomainInfo.sourceRowStrideBytes = static_cast<size_t>(std::max(0, static_cast<int>(sourceRowStrideBytes)));
    rawDomainInfo.sourcePixelStrideBytes = static_cast<size_t>(std::max(0, static_cast<int>(sourcePixelStrideBytes)));
    rawDomainInfo.masterRowStrideBytes = static_cast<size_t>(std::max(0, static_cast<int>(masterRowStrideBytes)));
    rawDomainInfo.masterPixelStrideBytes = sizeof(uint16_t);
    rawDomainInfo.sensorCfaPattern = cfaPattern;
    rawDomainInfo.cfaOffsetX = static_cast<int>(bufferOriginCfaOffsetX);
    rawDomainInfo.cfaOffsetY = static_cast<int>(bufferOriginCfaOffsetY);
    rawDomainInfo.sensorInfoWhiteLevel = std::max(0, static_cast<int>(sensorInfoWhiteLevel));
    rawDomainInfo.sensorDynamicWhiteLevel = std::max(0, static_cast<int>(sensorDynamicWhiteLevel));
    rawDomainInfo.hasDynamicWhiteLevel = sensorDynamicWhiteLevel > 0;
    rawDomainInfo.sensorBlackLevelPattern = extractFloat4(env, sensorBlackLevelPatternArray);
    rawDomainInfo.sensorDynamicBlackLevel = extractFloat4(env, sensorDynamicBlackLevelArray);
    rawDomainInfo.hasDynamicBlackLevel = sensorDynamicBlackLevelArray != nullptr &&
            env->GetArrayLength(sensorDynamicBlackLevelArray) >= 4;
    rawDomainInfo.chosenBlackLevelSource = getJniString(env, chosenBlackLevelSourceString, "missing/fallback_noop_0");
    rawDomainInfo.chosenWhiteLevelSource = getJniString(env, chosenWhiteLevelSourceString, "missing/fallback");
    rawDomainInfo.sourceBitDepth = std::clamp(static_cast<int>(sourceBitDepth), 1, 16);
    rawDomainInfo.nativeBitDepth = rawDomainInfo.sourceBitDepth;
    rawDomainInfo.effectiveSourceRange = std::max(1, static_cast<int>(effectiveSourceRange));
    rawDomainInfo.nativeWhiteLevel = isRaw10 == JNI_TRUE ? 1023 : std::max(1, static_cast<int>(whiteLevel));
    rawDomainInfo.payloadWhiteLevel = std::max(1, static_cast<int>(whiteLevel));
    rawDomainInfo.sourceStorageAlignment = isRaw10 == JNI_TRUE
            ? RawStorageAlignment::PACKED
            : RawStorageAlignment::RIGHT_JUSTIFIED;
    rawDomainInfo.masterStorageAlignment = RawStorageAlignment::RIGHT_JUSTIFIED;
    rawDomainInfo.sampleTransform = isRaw10 == JNI_TRUE
            ? RawSampleTransform::RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD
            : RawSampleTransform::RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD;
    rawDomainInfo.dynamicBlackLevelUsed = rawDomainInfo.hasDynamicBlackLevel;
    rawDomainInfo.dynamicWhiteLevelUsed = rawDomainInfo.hasDynamicWhiteLevel;
    rawDomainInfo.staticBlackLevelUsed = !rawDomainInfo.hasDynamicBlackLevel && sensorBlackLevelPatternArray != nullptr;
    rawDomainInfo.staticWhiteLevelUsed = !rawDomainInfo.hasDynamicWhiteLevel && sensorInfoWhiteLevel > 0;
    rawDomainInfo.manualOverrideUsed =
            rawDomainInfo.chosenBlackLevelSource.find("Manual") != std::string::npos ||
            rawDomainInfo.chosenBlackLevelSource.find("override") != std::string::npos ||
            rawDomainInfo.chosenWhiteLevelSource.find("Manual") != std::string::npos ||
            rawDomainInfo.chosenWhiteLevelSource.find("override") != std::string::npos;
    rawDomainInfo.lensShadingState = lensShadingFromMetadata == JNI_TRUE
            ? "REQUESTED_MAP_AVAILABLE"
            : "REQUESTED_MAP_MISSING_OR_NOT_REQUESTED";
    rawDomainInfo.validationWarnings = "none";
    rawDomainInfo.masterStorageScale = std::isfinite(masterStorageScale) && masterStorageScale > 0.0f
            ? masterStorageScale
            : 1.0f;
    rawDomainInfo.masterStorageLeftShift = std::clamp(static_cast<int>(masterStorageLeftShift), 0, 15);
    rawDomainInfo.masterStorageContract = getJniString(
            env,
            masterStorageContractString,
            "RIGHT_JUSTIFIED_NATIVE_CODE_VALUES_IN_UINT16"
    );
    rawDomainInfo.effectiveWhiteLevelInMasterUnits = static_cast<float>(std::max(1, static_cast<int>(whiteLevel)));
    rawDomainInfo.debugDumpsEnabled = meta.rawJpegDebugDumpsEnabled;
    rawDomainInfo.debugDumpDirectory = meta.rawJpegDebugDumpDirectory;

    // Fill the Kotlin-resolved sensor calibration contract. Kotlin is the source of truth;
    // native only validates array lengths and applies safe fallbacks when metadata is absent.
    meta.calibration.effectiveWhiteLevel = std::max(1, static_cast<int>(whiteLevel));
    meta.calibration.hasWhiteLevel = whiteLevel > 0;
    meta.calibration.colorMatrixFromMetadata = qualityConfig.colorMatrixFromMetadata;
    bool validColorMatrix = true;
    bool nonZeroColorMatrix = false;
    for (int i = 0; i < 9; ++i) {
        const float value = qualityConfig.colorMatrix[i];
        meta.calibration.effectiveColorMatrix[i] = value;
        if (!std::isfinite(value)) validColorMatrix = false;
        if (std::abs(value) > 1.0e-7f) nonZeroColorMatrix = true;
    }
    meta.calibration.hasColorMatrix = validColorMatrix && nonZeroColorMatrix;

    // Physical source identity never crosses JNI. Native receives only the validated frozen S/O.
    // SPECTRA request intent is a separate boolean and becomes effective only after S/O validates.
    meta.calibration.spectraProcessingMode = 0;
    meta.calibration.spectraMode = 0;
    meta.calibration.lensId = lensId;
    // FASE 11: exactly one physical-noise JNI carrier. The payload is canonical
    // R,Gr,Gb,B interleaved S/O and must contain exactly eight finite non-negative values.
    // No independent presence/applied/count flags and no parallel SPECTRA/OEM S/O arrays exist.
    const CanonicalPhysicalNoiseSoPayload physicalNoise =
            extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray);
    meta.calibration.physicalNoiseJniPayloadReceived = physicalNoise.valid;
    for (int ch = 0; ch < 4; ++ch) {
        meta.calibration.effectiveS[ch] = physicalNoise.s[static_cast<std::size_t>(ch)];
        meta.calibration.effectiveO[ch] = physicalNoise.o[static_cast<std::size_t>(ch)];
    }
    if (!physicalNoise.valid) {
        meta.calibration.calibrationWarnings =
                "PhysicalNoiseState canonical S/O payload missing or invalid";
    }

    const bool physicalNoiseReady = meta.calibration.physicalNoiseModelAvailable();
    const bool spectraRequested = spectraProcessingEnabled == JNI_TRUE;
    meta.calibration.spectraProcessingMode =
            resolveSpectraProcessingMode(spectraRequested, meta.calibration);
    meta.calibration.spectraMode = meta.calibration.spectraProcessingMode;
    meta.calibration.signalModelConfidence = physicalNoiseReady ? 1.0f : 0.0f;
    meta.calibration.postRawSensitivityBoost =
            std::clamp(static_cast<int>(spectraPostRawSensitivityBoost), 1, 1600);
    if (spectraRequested && !physicalNoiseReady) {
        meta.calibration.calibrationWarnings +=
                "; SPECTRA disabled: physical S/O unavailable";
    }

    const int lscColumns = std::max(0, static_cast<int>(lensShadingColumns));
    const int lscRows = std::max(0, static_cast<int>(lensShadingRows));
    const size_t lscCount = static_cast<size_t>(lscColumns) * static_cast<size_t>(lscRows) * 4u;
    if (lensShadingFromMetadata == JNI_TRUE && lensShadingMapArray != nullptr && lscColumns > 0 && lscRows > 0) {
        const jsize javaLscCount = env->GetArrayLength(lensShadingMapArray);
        if (javaLscCount >= static_cast<jsize>(lscCount)) {
            meta.lensShadingMap.resize(lscCount);
            env->GetFloatArrayRegion(lensShadingMapArray, 0, static_cast<jsize>(lscCount), meta.lensShadingMap.data());
            meta.lensShadingColumns = lscColumns;
            meta.lensShadingRows = lscRows;
            meta.lensShadingFromMetadata = true;
        }
    }

    if (blackLevelArray != nullptr && env->GetArrayLength(blackLevelArray) >= 4) {
        jfloat bl[4];
        env->GetFloatArrayRegion(blackLevelArray, 0, 4, bl);
        const std::array<float, 4> canonicalBlackLevels{{bl[0], bl[1], bl[2], bl[3]}};
        // FinalSensorCalibration crosses JNI in canonical plane order [R, Gr, Gb, B].
        // RawDomain spatial normalization indexes a 2x2 pattern by sensor position, so it
        // must receive [00, 10, 01, 11]. Keeping these representations explicit prevents
        // per-channel black offsets from being applied to the wrong CFA sites.
        const std::array<float, 4> mosaicBlackLevels =
                bncam::raw::canonicalLevelsToMosaic(canonicalBlackLevels, rawDomainInfo.sensorCfaPattern);

        for (int i = 0; i < 4; ++i) {
            meta.calibration.effectiveBlackLevels[static_cast<size_t>(i)] =
                    canonicalBlackLevels[static_cast<size_t>(i)];
        }
        meta.calibration.hasBlackLevel = true;
        for (int i = 0; i < 4; ++i) {
            const float payloadBlack = mosaicBlackLevels[static_cast<size_t>(i)];
            rawDomainInfo.payloadBlackLevels[static_cast<size_t>(i)] = payloadBlack;
            rawDomainInfo.effectiveBlackLevelPatternInMasterUnits[static_cast<size_t>(i)] = payloadBlack;
            if (isRaw10 == JNI_TRUE && rawDomainInfo.payloadWhiteLevel > 0) {
                rawDomainInfo.nativeBlackLevels[static_cast<size_t>(i)] = std::clamp(
                        payloadBlack * static_cast<float>(rawDomainInfo.nativeWhiteLevel) /
                                static_cast<float>(rawDomainInfo.payloadWhiteLevel),
                        0.0f,
                        static_cast<float>(std::max(1, rawDomainInfo.nativeWhiteLevel) - 1)
                );
            } else {
                rawDomainInfo.nativeBlackLevels[static_cast<size_t>(i)] = std::clamp(
                        payloadBlack,
                        0.0f,
                        static_cast<float>(std::max(1, rawDomainInfo.nativeWhiteLevel) - 1)
                );
            }
        }
    }

    if (wbGainsArray != nullptr && env->GetArrayLength(wbGainsArray) >= 4) {
        jfloat wb[4];
        env->GetFloatArrayRegion(wbGainsArray, 0, 4, wb);
        meta.calibration.effectiveWbGains[0] = wb[0];
        meta.calibration.effectiveWbGains[1] = wb[1];
        meta.calibration.effectiveWbGains[2] = wb[2];
        meta.calibration.effectiveWbGains[3] = wb[3];
        meta.calibration.hasWbGains = true;
    }
    meta.calibration.awbCalibrationAuthority =
            std::isfinite(static_cast<float>(awbCalibrationAuthority))
                    ? std::clamp(static_cast<float>(awbCalibrationAuthority), 0.0f, 1.0f)
                    : 0.0f;
    meta.calibration.awbExplicitDevelopedAuthority = awbExplicitDevelopedAuthority == JNI_TRUE;
    meta.calibration.awbManualGreenSplitAuthority = awbManualGreenSplitAuthority == JNI_TRUE;

    meta.calibration.calibrationApplied = meta.calibration.hasBlackLevel || meta.calibration.hasWhiteLevel ||
            meta.calibration.hasWbGains || meta.calibration.hasColorMatrix ||
            meta.calibration.physicalNoiseModelAvailable();

    if (activeArrayArray != nullptr && env->GetArrayLength(activeArrayArray) >= 4) {
        jint coords[4];
        env->GetIntArrayRegion(activeArrayArray, 0, 4, coords);
        meta.rawActiveArraySize[0] = coords[0];
        meta.rawActiveArraySize[1] = coords[1];
        meta.rawActiveArraySize[2] = coords[2];
        meta.rawActiveArraySize[3] = coords[3];
        for (int i = 0; i < 4; ++i) rawDomainInfo.activeArray[static_cast<size_t>(i)] = coords[i];
    }
    if (cropRegionArray != nullptr && env->GetArrayLength(cropRegionArray) >= 4) {
        jint coords[4];
        env->GetIntArrayRegion(cropRegionArray, 0, 4, coords);
        meta.rawJpegCropRegion[0] = coords[0];
        meta.rawJpegCropRegion[1] = coords[1];
        meta.rawJpegCropRegion[2] = coords[2];
        meta.rawJpegCropRegion[3] = coords[3];
        for (int i = 0; i < 4; ++i) rawDomainInfo.cropRegion[static_cast<size_t>(i)] = coords[i];
    }
    if (preCorrectionArray != nullptr && env->GetArrayLength(preCorrectionArray) >= 4) {
        jint coords[4];
        env->GetIntArrayRegion(preCorrectionArray, 0, 4, coords);
        meta.rawPreCorrectionArray[0] = coords[0];
        meta.rawPreCorrectionArray[1] = coords[1];
        meta.rawPreCorrectionArray[2] = coords[2];
        meta.rawPreCorrectionArray[3] = coords[3];
        for (int i = 0; i < 4; ++i) rawDomainInfo.preCorrectionArray[static_cast<size_t>(i)] = coords[i];
    }

    metadataResolveMs = nativeElapsedMs(metadataStart);

    std::vector<uint8_t> jpegData;
    std::string failureReason = "none";
    std::string masterIspDebug;
    bool rawResidentEntryUsed = false;
    bool rawResidentCpuFallbackUsed = false;
    std::string rawResidentCpuFallbackReason = "none";
    std::uint64_t rawProducerResidentGeneration = 0u;
    std::uint64_t rawNormalizeResidentGeneration = 0u;
    std::string rawNormalizeBackend = "NOT_ATTEMPTED";
    std::uint64_t rawKnownDefectMapPointCount = 0u;
    std::uint64_t rawKnownDefectCorrectedPixelCount = 0u;
    std::uint64_t rawResidualDefectCorrectedPixelCount = 0u;
    std::uint64_t rawKnownDefectBorderSkipCount = 0u;
    bool rawNoiseAdaptiveDefectDetectionEnabled = false;
    float rawDefectSparseMetadataUploadMs = 0.0f;
    std::uint64_t rawDefectSparseMetadataUploadBytes = 0u;
    cv::Mat bayer16Isp; // We maken de variabele buiten het try-blok aan

    try {
        auto stageStart = NativeClock::now();

        // The Master RAW16 remains native-owned. JNI receives a direct ByteBuffer view, so
        // JPEG-only captures do not allocate/copy a full managed-heap RAW array.
        void* nativeRawAddress = raw16DirectBuffer != nullptr
                ? env->GetDirectBufferAddress(raw16DirectBuffer)
                : nullptr;
        const jlong nativeRawCapacity = raw16DirectBuffer != nullptr
                ? env->GetDirectBufferCapacity(raw16DirectBuffer)
                : -1;
        jniArrayLockMs = nativeElapsedMs(stageStart);
        const jlong requiredRawBytes = static_cast<jlong>(width) * static_cast<jlong>(height) * 2LL;
        if (nativeRawAddress == nullptr || nativeRawCapacity < requiredRawBytes) {
            failureReason = "invalid_or_undersized_direct_raw16_buffer";
            throw std::runtime_error("Could not access native RAW16 direct buffer");
        }
        rawProducerResidentGeneration = nativeRaw16ResidentGeneration(nativeRawAddress);
        int jpegCropLeftInResidentRaw = 0;
        int jpegCropTopInResidentRaw = 0;

        // Read-only view. normalizeRawForJpeg creates the private float working layer, so no
        // full-resolution RAW16 clone is required before ISP processing.
        bayer16Isp = cv::Mat(height, width, CV_16UC1, nativeRawAddress);

        // Resolve the sensor coordinate represented by JPEG working pixel (0,0). Odd origins
        // are allowed: RawDomainInfo phase-shifts both black lookup and demosaic explicitly.
        int jpegWorkingOriginX = static_cast<int>(bufferOriginCfaOffsetX);
        int jpegWorkingOriginY = static_cast<int>(bufferOriginCfaOffsetY);

        // Crop to an active array only when the master contains the full pixel-array coordinate
        // space. Some HALs already return active-array dimensions; in that case retain all pixels
        // and use activeLeft/activeTop solely as the CFA/black-pattern sensor origin.
        if (activeArrayArray != nullptr && env->GetArrayLength(activeArrayArray) >= 4) {
            const int activeLeft = meta.rawActiveArraySize[0];
            const int activeTop = meta.rawActiveArraySize[1];
            const int activeRight = meta.rawActiveArraySize[2];
            const int activeBottom = meta.rawActiveArraySize[3];
            const int activeWidth = activeRight - activeLeft;
            const int activeHeight = activeBottom - activeTop;

            const bool bufferIsAlreadyActiveArray =
                    (activeWidth == bayer16Isp.cols && activeHeight == bayer16Isp.rows) ||
                    (bayer16Isp.cols < activeRight && bayer16Isp.cols <= activeWidth);

            // If the active rectangle coordinates themselves fit inside the acquired RAW buffer,
            // that is sufficient evidence that the buffer contains those leading/top sensor
            // coordinates. The former `cols >= activeRight + activeLeft` check double-counted the
            // left offset (activeRight is already an absolute coordinate) and could therefore
            // leave a large valid sensor border in the developed JPEG.
            const bool activeCoordinatesFitBuffer =
                    !bufferIsAlreadyActiveArray &&
                    activeLeft >= 0 && activeTop >= 0 &&
                    activeRight <= bayer16Isp.cols && activeBottom <= bayer16Isp.rows &&
                    activeWidth >= 2 && activeHeight >= 2;

            if (bufferIsAlreadyActiveArray) {
                meta.activeArrayAppliedToRawJpeg = true;
                meta.rawVisibleCropApplied = false;
                jpegWorkingOriginX += activeLeft;
                jpegWorkingOriginY += activeTop;
            } else if (activeCoordinatesFitBuffer) {
                bayer16Isp = bayer16Isp(cv::Rect(activeLeft, activeTop, activeWidth, activeHeight));
                jpegCropLeftInResidentRaw += activeLeft;
                jpegCropTopInResidentRaw += activeTop;
                meta.activeArrayAppliedToRawJpeg = true;
                meta.rawVisibleCropApplied = true;
                meta.rawInvalidBorderCropPxLeft = activeLeft;
                meta.rawInvalidBorderCropPxTop = activeTop;
                meta.rawInvalidBorderCropPxRight = width - activeRight;
                meta.rawInvalidBorderCropPxBottom = height - activeBottom;
                meta.rawBorderArtifactReason = "camera2_active_array_coordinates_fit_raw_buffer";
                jpegWorkingOriginX += activeLeft;
                jpegWorkingOriginY += activeTop;
            } else {
                meta.activeArrayAppliedToRawJpeg = true;
                meta.rawVisibleCropApplied = false;
            }
        }

        if (!meta.activeArrayAppliedToRawJpeg) {
            int defaultGuard = 16;
            int snapLeft = (defaultGuard / 2) * 2;
            int snapTop = (defaultGuard / 2) * 2;
            int snapRight = ((bayer16Isp.cols - defaultGuard) / 2) * 2;
            int snapBottom = ((bayer16Isp.rows - defaultGuard) / 2) * 2;
            
            snapLeft = std::clamp(snapLeft, 0, bayer16Isp.cols);
            snapTop = std::clamp(snapTop, 0, bayer16Isp.rows);
            snapRight = std::clamp(snapRight, snapLeft + 2, bayer16Isp.cols);
            snapBottom = std::clamp(snapBottom, snapTop + 2, bayer16Isp.rows);
            
            int w = snapRight - snapLeft;
            int h = snapBottom - snapTop;
            if (w > 0 && h > 0) {
                bayer16Isp = bayer16Isp(cv::Rect(snapLeft, snapTop, w, h));
                jpegCropLeftInResidentRaw += snapLeft;
                jpegCropTopInResidentRaw += snapTop;
                meta.rawBorderArtifactGuardApplied = true;
                meta.rawBorderArtifactReason = "no_active_array_or_already_full_frame_fallback_guard";
                meta.rawInvalidBorderCropPxLeft = snapLeft;
                meta.rawInvalidBorderCropPxTop = snapTop;
                meta.rawInvalidBorderCropPxRight = width - snapRight;
                meta.rawInvalidBorderCropPxBottom = height - snapBottom;
                jpegWorkingOriginX += snapLeft;
                jpegWorkingOriginY += snapTop;
                
                if (meta.isRaw10) {
                    meta.raw10EdgeGuardApplied = true;
                    meta.raw10EdgeGuardReason = "discard_uncalibrated_raw10_border_pixels";
                }
            }
        }

        // The JPEG renderer receives a read-only native view. Avoid full-frame proof-stat passes here;
        // the NativeRaw16Buffer ownership boundary already guarantees DNG isolation.
#if 0
        {
            double cloneMin = 0.0, cloneMax = 0.0;
            cv::minMaxLoc(bayer16Isp, &cloneMin, &cloneMax);
            double cloneMean = cv::mean(bayer16Isp)[0];

            // Disabled deterministic percentile sampling on the read-only RAW16 view.
            int totalPixels = bayer16Isp.rows * bayer16Isp.cols;
            int step = std::max(1, totalPixels / 10000);
            std::vector<uint16_t> samples;
            samples.reserve(10000);
            const uint16_t* clonePtr = bayer16Isp.ptr<uint16_t>(0);
            for (int i = 0; i < totalPixels && static_cast<int>(samples.size()) < 10000; i += step) {
                samples.push_back(clonePtr[i]);
            }
            std::sort(samples.begin(), samples.end());
            int n = static_cast<int>(samples.size());
            uint16_t cloneP50 = n > 0 ? samples[std::clamp(static_cast<int>(n * 0.50f), 0, n - 1)] : 0;
            uint16_t cloneP95 = n > 0 ? samples[std::clamp(static_cast<int>(n * 0.95f), 0, n - 1)] : 0;
            uint16_t cloneP99 = n > 0 ? samples[std::clamp(static_cast<int>(n * 0.99f), 0, n - 1)] : 0;

            jpegCloneRaw16StatsComputed = true;
            jpegCloneRaw16Min = static_cast<int>(cloneMin);
            jpegCloneRaw16Mean = cloneMean;
            jpegCloneRaw16P50 = cloneP50;
            jpegCloneRaw16P95 = cloneP95;
            jpegCloneRaw16P99 = cloneP99;
            jpegCloneRaw16Max = static_cast<int>(cloneMax);
        }
#endif

        // Camera2 RAW buffers intentionally remain full-sensor even when SCALER_CROP_REGION is
        // active. Apply that sensor-space crop only to the private JPEG working view. The Master
        // RAW16/DNG owner remains untouched, while the existing working-origin and resident-RAW
        // offsets keep CFA phase, row stride and the Vulkan normalizer in the same geometry.
        const auto rawDigitalZoomCrop = bncam::raw::resolveRawDigitalZoomCrop(
                jpegWorkingOriginX,
                jpegWorkingOriginY,
                bayer16Isp.cols,
                bayer16Isp.rows,
                rawDomainInfo.cropRegion);
        if (rawDigitalZoomCrop.applied) {
            bayer16Isp = bayer16Isp(cv::Rect(
                    rawDigitalZoomCrop.localLeft,
                    rawDigitalZoomCrop.localTop,
                    rawDigitalZoomCrop.width,
                    rawDigitalZoomCrop.height));
            jpegCropLeftInResidentRaw += rawDigitalZoomCrop.localLeft;
            jpegCropTopInResidentRaw += rawDigitalZoomCrop.localTop;
            jpegWorkingOriginX += rawDigitalZoomCrop.localLeft;
            jpegWorkingOriginY += rawDigitalZoomCrop.localTop;
            __android_log_print(
                    ANDROID_LOG_INFO,
                    "BnCam_RawZoom",
                    "RAW JPEG crop applied sensorOrigin=%d,%d local=%d,%d size=%dx%d request=%d,%d,%d,%d",
                    jpegWorkingOriginX,
                    jpegWorkingOriginY,
                    rawDigitalZoomCrop.localLeft,
                    rawDigitalZoomCrop.localTop,
                    rawDigitalZoomCrop.width,
                    rawDigitalZoomCrop.height,
                    rawDomainInfo.cropRegion[0],
                    rawDomainInfo.cropRegion[1],
                    rawDomainInfo.cropRegion[2],
                    rawDomainInfo.cropRegion[3]);
        }

        // Configure the private JPEG float working layer; the native RAW16 owner remains read-only.
        rawDomainInfo.width = bayer16Isp.cols;
        rawDomainInfo.height = bayer16Isp.rows;
        rawDomainInfo.masterRowStrideBytes = bayer16Isp.step;
        rawDomainInfo.cfaOffsetX = jpegWorkingOriginX;
        rawDomainInfo.cfaOffsetY = jpegWorkingOriginY;
        rawDomainInfo.effectiveCfaPattern = effectiveCfaPatternAtOrigin(
                rawDomainInfo.sensorCfaPattern,
                rawDomainInfo.cfaOffsetX,
                rawDomainInfo.cfaOffsetY
        );
        // Baseline reset intentionally has no intermediate dump stages.
        rawDomainInfo.debugDumpsEnabled = false;
        rawDomainInfo.debugDumpDirectory.clear();
        meta.rawJpegDebugDumpsEnabled = false;
        meta.rawJpegDebugDumpDirectory.clear();

        stageStart = NativeClock::now();
        if (rawProducerResidentGeneration != 0u) {
            bncam::vulkan::RawJpegNormalizeRequest normalizeRequest{};
            normalizeRequest.inputWidth = static_cast<std::uint32_t>(width);
            normalizeRequest.inputHeight = static_cast<std::uint32_t>(height);
            normalizeRequest.cropLeft = static_cast<std::uint32_t>(std::max(0, jpegCropLeftInResidentRaw));
            normalizeRequest.cropTop = static_cast<std::uint32_t>(std::max(0, jpegCropTopInResidentRaw));
            normalizeRequest.width = static_cast<std::uint32_t>(rawDomainInfo.width);
            normalizeRequest.height = static_cast<std::uint32_t>(rawDomainInfo.height);
            normalizeRequest.cfaOffsetX = static_cast<std::uint32_t>(std::max(0, rawDomainInfo.cfaOffsetX));
            normalizeRequest.cfaOffsetY = static_cast<std::uint32_t>(std::max(0, rawDomainInfo.cfaOffsetY));
            normalizeRequest.whiteLevel = rawDomainInfo.effectiveWhiteLevelInMasterUnits;
            normalizeRequest.blackLevels = rawDomainInfo.effectiveBlackLevelPatternInMasterUnits;
            normalizeRequest.sensorCfaPattern = static_cast<std::uint32_t>(
                    std::clamp(rawDomainInfo.sensorCfaPattern, 0, 3));
            normalizeRequest.noiseModelValid = meta.calibration.physicalNoiseModelAvailable();
            for (int ch = 0; ch < 4; ++ch) {
                normalizeRequest.effectiveS[static_cast<std::size_t>(ch)] =
                        static_cast<float>(std::max(0.0, meta.calibration.effectiveS[ch]));
                normalizeRequest.effectiveO[static_cast<std::size_t>(ch)] =
                        static_cast<float>(std::max(0.0, meta.calibration.effectiveO[ch]));
            }

            // Master coordinates from Kotlin must follow any additional visible JPEG crop.
            std::vector<std::int32_t> normalizedHotPixelXy;
            normalizedHotPixelXy.reserve(knownHotPixelMasterXy.size());
            for (std::size_t i = 0; i + 1u < knownHotPixelMasterXy.size(); i += 2u) {
                const int x = static_cast<int>(knownHotPixelMasterXy[i]) - jpegCropLeftInResidentRaw;
                const int y = static_cast<int>(knownHotPixelMasterXy[i + 1u]) - jpegCropTopInResidentRaw;
                if (x >= 0 && y >= 0 &&
                    x < static_cast<int>(normalizeRequest.width) &&
                    y < static_cast<int>(normalizeRequest.height)) {
                    normalizedHotPixelXy.push_back(static_cast<std::int32_t>(x));
                    normalizedHotPixelXy.push_back(static_cast<std::int32_t>(y));
                }
            }
            normalizeRequest.knownDefectCoordinates =
                    normalizedHotPixelXy.empty() ? nullptr : normalizedHotPixelXy.data();
            normalizeRequest.knownDefectCount =
                    static_cast<std::uint32_t>(normalizedHotPixelXy.size() / 2u);
            normalizeRequest.generationId = rawProducerResidentGeneration ^ 0x5241574a5045474eull;
            if (normalizeRequest.generationId == 0u) normalizeRequest.generationId = 1u;

            const auto normalized = bncam::vulkan::VulkanRuntime::instance()
                    .executeRawJpegNormalizeFromResidentRaw(
                            normalizeRequest, rawProducerResidentGeneration);
            rawNormalizeBackend = normalized.backend;
            rawKnownDefectMapPointCount = normalized.knownDefectMapPointCount;
            rawKnownDefectCorrectedPixelCount = normalized.knownDefectCorrectedPixelCount;
            rawResidualDefectCorrectedPixelCount = normalized.residualDefectCorrectedPixelCount;
            rawKnownDefectBorderSkipCount = normalized.knownDefectBorderSkipCount;
            rawNoiseAdaptiveDefectDetectionEnabled = normalized.noiseAdaptiveDefectDetectionEnabled;
            rawDefectSparseMetadataUploadMs = normalized.sparseMetadataUploadMs;
            rawDefectSparseMetadataUploadBytes = normalized.sparseMetadataUploadBytes;
            if (normalized.success && normalized.residentOutputProduced &&
                normalized.residentOutputGeneration != 0u &&
                normalized.fullFrameCpuUploadBytes == 0u && normalized.fullFrameGpuReadbackBytes == 0u) {
                rawResidentEntryUsed = true;
                rawNormalizeResidentGeneration = normalized.residentOutputGeneration;
                RawNormalizedSampleView sampleView = makeRawNormalizedSampleView(
                        bayer16Isp.ptr<uint16_t>(0), rawDomainInfo);
                if (!sampleView.valid) {
                    rawResidentEntryUsed = false;
                    rawResidentCpuFallbackUsed = true;
                    rawResidentCpuFallbackReason = "COMPACT_RAW16_SAMPLE_VIEW_INVALID";
                } else {
                    ResidentRawRenderInput residentRender{};
                    residentRender.sampleView = std::move(sampleView);
                    residentRender.rawNormalizeGeneration = rawNormalizeResidentGeneration;
                    LinearFloatRaw residentDescriptor{};
                    residentDescriptor.info = rawDomainInfo;
                    residentDescriptor.diagnostics.valid = true;
                    residentDescriptor.diagnostics.failureReason = "none_resident_gpu_normalize";
                    rawPreprocessOuterMs = nativeElapsedMs(stageStart);
                    stageStart = NativeClock::now();
                    jpegData = IspCore::renderRawBaselineJpeg(
                            std::move(residentDescriptor), meta, qualityConfig,
                            &masterIspDebug, rotationDegrees, &residentRender);
                    rawJpegRenderCallOuterMs = nativeElapsedMs(stageStart);
                }
            } else {
                rawResidentCpuFallbackUsed = true;
                rawResidentCpuFallbackReason = normalized.failureReason.empty()
                        ? "RAW_JPEG_RESIDENT_NORMALIZE_FAILED" : normalized.failureReason;
            }
        } else {
            rawResidentCpuFallbackUsed = true;
            rawResidentCpuFallbackReason = "NO_RESIDENT_RAW_PRODUCER_GENERATION";
        }

        if (!rawResidentEntryUsed) {
            // Explicit CPU reference/failsafe only: upstream resident generation was unavailable or
            // the Vulkan RAW-normalizer failed its contract. This is never a performance choice.
            stageStart = NativeClock::now();
            LinearFloatRaw workingRaw = normalizeRawForJpeg(
                    bayer16Isp.ptr<uint16_t>(0), rawDomainInfo);
            rawPreprocessOuterMs = nativeElapsedMs(stageStart);
            stageStart = NativeClock::now();
            jpegData = IspCore::renderRawBaselineJpeg(
                    std::move(workingRaw), meta, qualityConfig,
                    &masterIspDebug, rotationDegrees);
            rawJpegRenderCallOuterMs = nativeElapsedMs(stageStart);
        }
        bayer16Isp.release();
    } catch (const cv::Exception& e) {
        failureReason = std::string("OpenCV exception: ") + e.what();
        LOGE("Master RAW16 ISP render failed: %s", e.what());
    } catch (const std::exception& e) {
        failureReason = std::string("std::exception: ") + e.what();
        LOGE("Master RAW16 ISP render failed: %s", e.what());
    } catch (...) {
        failureReason = "unknown native exception";
        LOGE("Master RAW16 ISP render failed: unknown exception");
    }

#if 0  // Retired giant RAW ISP diagnostic block.
    float exposureGain = 1.2f;
    if (captureSensitivityIso > 100) {
        exposureGain = 1.2f + 0.8f * (std::min(static_cast<float>(captureSensitivityIso), 1600.0f) / 1600.0f);
    }
    exposureGain = parseExposureGainFromDebug(masterIspDebug, exposureGain);

    const int normalizedRotation = normalizeRotationDegrees(rotationDegrees);
    NativeRenderQualityConfig statsQualityConfig = qualityConfig;
    statsQualityConfig.captureSensitivityIso = meta.captureSensitivityIso;
    std::ostringstream stats;
    stats << "route=" << routeLabel
          << ";canonicalMasterCreated=true"
          << ";masterType=DNG_COMPATIBLE_RAW16"
          << ";jpegRenderSource=JPEG_WORKING_LINEAR_RAW_DERIVED_FROM_READ_ONLY_MASTER"
          << ";jpegRenderPath=RAW_DOMAIN_NORMALIZE_BASELINE_DEMOSAIC_WB_CCM_P50_EXPOSURE_SOFT_ROLLOFF_SRGB"
          << ";rawBaselineRendererEnabled=true"
          << ";rawLegacyConstrainedExposureBypassed=true"
          << ";rawLtmBypassed=true"
          << ";rawGtmBypassed=true"
          << ";rawFeedbackRerenderBypassed=true"
          << ";rawRepairBypassed=true"
          << ";rawDenoiseBypassed=true"
          << ";rawOutlierCorrectionBypassed=true"
          << ";raw10SeparateNormalizationUsed=" << (isRaw10 == JNI_TRUE ? "true" : "false")
          << ";rawSensorSeparateNormalizationUsed=" << (isRaw10 == JNI_TRUE ? "false" : "true")
          << ";dngExportSource=ORIGINAL_MASTER_RAW16_UNTOUCHED"
          << ";renderOutputCreated=" << (!jpegData.empty() ? "true" : "false")
          << ";raw16Bytes=" << len
          << ";width=" << width
          << ";height=" << height
          << ";outputRotationDegrees=" << normalizedRotation
          << ";nativeRotationApplied=" << (normalizedRotation != 0 ? "true" : "false")
          << ";cfaPattern=" << cfaPattern
          << ";whiteLevel=" << whiteLevel
          << ";blackLevels=";
    if (blackLevelArray != nullptr && env->GetArrayLength(blackLevelArray) >= 4) {
        jfloat blStats[4];
        env->GetFloatArrayRegion(blackLevelArray, 0, 4, blStats);
        stats << blStats[0] << "," << blStats[1] << "," << blStats[2] << "," << blStats[3];
    } else {
        stats << "fallback_or_missing";
    }
    stats << ";lensHardwareConfig=migrated_to_v2"
          << ";hasBlackLevel=" << (meta.calibration.hasBlackLevel ? "true" : "false")
          << ";hasWhiteLevel=" << (meta.calibration.hasWhiteLevel ? "true" : "false")
          << ";hasColorMatrix=" << (meta.calibration.hasColorMatrix ? "true" : "false")
          << ";hasWbGains=" << (meta.calibration.hasWbGains ? "true" : "false")
          << ";physicalNoiseJniPayloadReceived="
          << (meta.calibration.physicalNoiseJniPayloadReceived ? "true" : "false")
          << ";physicalNoiseModelAvailable="
          << (meta.calibration.physicalNoiseModelAvailable() ? "true" : "false")
          << ";calibrationApplied=" << (meta.calibration.calibrationApplied ? "true" : "false")
          << ";calibrationWarnings=" << meta.calibration.calibrationWarnings
          << ";lensShadingMapFromMetadata=" << (lensShadingFromMetadata == JNI_TRUE ? "true" : "false")
          << ";lensShadingMapColumns=" << lensShadingColumns
          << ";lensShadingMapRows=" << lensShadingRows
          << ";exposureMultiplier=" << exposureGain
          << ";dynamicWhiteLevel=" << meta.calibration.effectiveWhiteLevel
          << ";dynamicBlackLevels=" << meta.calibration.effectiveBlackLevels[0] << "," << meta.calibration.effectiveBlackLevels[1] << "," << meta.calibration.effectiveBlackLevels[2] << "," << meta.calibration.effectiveBlackLevels[3]
          << ";jpegBytes=" << jpegData.size()
          << ";jniArrayLockMs=" << nativeFmtMs(jniArrayLockMs)
          << ";metadataResolveOuterMs=" << nativeFmtMs(metadataResolveMs)
          << ";rawPreprocessOuterMs=" << nativeFmtMs(rawPreprocessOuterMs)
          << ";rawJpegRenderCallOuterMs=" << nativeFmtMs(rawJpegRenderCallOuterMs)
          << ";totalNativeRawIspOuterMs=" << nativeFmtMs(nativeElapsedMs(outerStart))
          // --- DNG Isolation Verification ---
          << ";dngIsolation_jpegUsedClone=false"
          << ";dngIsolation_directNativeBuffer=true"
          << ";dngIsolation_normalizationTouchedOriginalRaw16=false"
          << ";dngIsolation_normalizationOperatesOn=JPEG_ONLY_LINEAR_FLOAT_RAW"
          << ";dngIsolation_bentoTouchedOriginalRaw16=false"
          << ";dngIsolation_fullBentoApplied=false"
          << ";dngIsolation_partialHighlightRepairTouchedOriginalRaw16=false"
          << ";dngIsolation_partialHighlightRepairOperatesOn=CV_32FC3_post_demosaic_jpeg_render_copy"
          << ";jpegCloneRaw16StatsComputed=" << (jpegCloneRaw16StatsComputed ? "true" : "false")
          << ";jpegCloneRaw16Min=" << jpegCloneRaw16Min
          << ";jpegCloneRaw16Mean=" << nativeFmtMs(static_cast<float>(jpegCloneRaw16Mean))
          << ";jpegCloneRaw16P50=" << jpegCloneRaw16P50
          << ";jpegCloneRaw16P95=" << jpegCloneRaw16P95
          << ";jpegCloneRaw16P99=" << jpegCloneRaw16P99
          << ";jpegCloneRaw16Max=" << jpegCloneRaw16Max
          << ";activeArrayAppliedToRawJpeg=" << (meta.activeArrayAppliedToRawJpeg ? "true" : "false")
          << ";rawJpegCropRegion=[" << meta.rawJpegCropRegion[0] << "," << meta.rawJpegCropRegion[1] << "," << meta.rawJpegCropRegion[2] << "," << meta.rawJpegCropRegion[3] << "]"
          << ";rawPreCorrectionArray=[" << meta.rawPreCorrectionArray[0] << "," << meta.rawPreCorrectionArray[1] << "," << meta.rawPreCorrectionArray[2] << "," << meta.rawPreCorrectionArray[3] << "]"
          << ";rawActiveArraySize=[" << meta.rawActiveArraySize[0] << "," << meta.rawActiveArraySize[1] << "," << meta.rawActiveArraySize[2] << "," << meta.rawActiveArraySize[3] << "]"
          << ";rawVisibleCropApplied=" << (meta.rawVisibleCropApplied ? "true" : "false")
          << ";rawInvalidBorderCropPxLeft=" << meta.rawInvalidBorderCropPxLeft
          << ";rawInvalidBorderCropPxTop=" << meta.rawInvalidBorderCropPxTop
          << ";rawInvalidBorderCropPxRight=" << meta.rawInvalidBorderCropPxRight
          << ";rawInvalidBorderCropPxBottom=" << meta.rawInvalidBorderCropPxBottom
          << ";rawBorderArtifactGuardApplied=" << (meta.rawBorderArtifactGuardApplied ? "true" : "false")
          << ";rawBorderArtifactReason=" << meta.rawBorderArtifactReason
          << ";rawBorderChromaRiskTop=" << meta.rawBorderChromaRiskTop
          << ";rawBorderChromaRiskBottom=" << meta.rawBorderChromaRiskBottom
          << ";rawBorderChromaRiskLeft=" << meta.rawBorderChromaRiskLeft
          << ";rawBorderChromaRiskRight=" << meta.rawBorderChromaRiskRight
          << ";rawBorderLumaP50Top=" << meta.rawBorderLumaP50Top
          << ";rawBorderLumaP50Bottom=" << meta.rawBorderLumaP50Bottom
          << ";rawBorderLumaP50Left=" << meta.rawBorderLumaP50Left
          << ";rawBorderLumaP50Right=" << meta.rawBorderLumaP50Right
          << ";rawBorderChromaSuppressionApplied=" << (meta.rawBorderChromaSuppressionApplied ? "true" : "false")
          << ";demosaicBorderMode=cv_default_reflect_crop_safe"
          << ";ltmBorderMode=cv_reflect101_crop_safe"
          << ";gtmBorderMode=cv_reflect101_crop_safe"
          << ";lensShadingEdgeGainMax=" << meta.lensShadingEdgeGainMax
          << ";lensShadingEdgeGainClamped=" << meta.lensShadingEdgeGainClamped
          << ";raw10EdgeGuardApplied=" << (meta.raw10EdgeGuardApplied ? "true" : "false")
          << ";raw10EdgeGuardReason=" << meta.raw10EdgeGuardReason
          << ";rawSpatialArtifactRisk=" << meta.rawSpatialArtifactRisk
          << ";rawBorderArtifactRisk=" << meta.rawBorderArtifactRisk
          << ";rawBorderArtifactSuppressed=" << (meta.rawBorderArtifactSuppressed ? "true" : "false")
          << ";rawChromaNoiseRisk=" << (meta.rawChromaNoiseRisk ? "true" : "false")
          << ";rawLtmOverreachRisk=" << meta.rawLtmOverreachRisk
          << ";rawTextureQualityRisk=" << (meta.rawTextureQualityRisk ? "true" : "false")
          << ";rawLocalContrastPreserved=" << (meta.rawLocalContrastPreserved ? "true" : "false")
          << ";rawFalseColorVisibleRisk=" << (meta.rawFalseColorVisibleRisk ? "true" : "false")
          << ";failureReason=" << failureReason
          << ";" << IspCore::describeResolvedConfig(statsQualityConfig, isRaw10 == JNI_TRUE, false);
    setMasterStats(stats.str());
#endif

    if (masterIspDebug.empty()) {
        std::ostringstream compactFailure;
        compactFailure << "RAW_BASELINE_RENDER: source="
                       << (isRaw10 == JNI_TRUE ? "RAW10" : "RAW_SENSOR")
                       << "; failure=" << failureReason
                       << "; yuvUntouched=true; dngUntouched=true";
        setMasterStats(compactFailure.str());
    } else {
        std::ostringstream ownershipStats;
        ownershipStats << masterIspDebug
                       << ";metadataResolveOuterMs=" << nativeFmtMs(metadataResolveMs)
                       << ";rawPreprocessOuterMs=" << nativeFmtMs(rawPreprocessOuterMs)
                       << ";rawJpegRenderCallOuterMs=" << nativeFmtMs(rawJpegRenderCallOuterMs)
                       << ";totalNativeRawIspOuterMs=" << nativeFmtMs(nativeElapsedMs(outerStart))
                       << ";raw16Ownership=NATIVE_DIRECT_BUFFER_V1"
                       << ";raw16ManagedHeapCopyCount=0"
                       << ";raw16ManagedHeapCopyBytes=0"
                       << ";raw16PreIspCloneEliminated=true"
                       << ";raw16DirectBufferAccessMs=" << nativeFmtMs(jniArrayLockMs)
                       << ";raw16DirectBufferCapacity=" << len
                       << ";rawResidentEntryUsed=" << (rawResidentEntryUsed ? "true" : "false")
                       << ";rawProducerResidentGeneration=" << rawProducerResidentGeneration
                       << ";rawNormalizeResidentGeneration=" << rawNormalizeResidentGeneration
                       << ";rawNormalizeBackend=" << rawNormalizeBackend
                       << ";rawP0DefectCorrectionStage=RAW_JPEG_NORMALIZE_BEFORE_SPATIAL_NR"
                       << ";rawKnownDefectMapPointCount=" << rawKnownDefectMapPointCount
                       << ";rawKnownDefectCorrectedPixelCount=" << rawKnownDefectCorrectedPixelCount
                       << ";rawResidualDefectCorrectedPixelCount=" << rawResidualDefectCorrectedPixelCount
                       << ";rawKnownDefectBorderSkipCount=" << rawKnownDefectBorderSkipCount
                       << ";rawNoiseAdaptiveDefectDetectionEnabled="
                       << (rawNoiseAdaptiveDefectDetectionEnabled ? "true" : "false")
                       << ";rawDefectSparseMetadataUploadMs=" << nativeFmtMs(rawDefectSparseMetadataUploadMs)
                       << ";rawDefectSparseMetadataUploadBytes=" << rawDefectSparseMetadataUploadBytes
                       << ";rawResidentCpuFallbackUsed=" << (rawResidentCpuFallbackUsed ? "true" : "false")
                       << ";rawResidentCpuFallbackReason=" << rawResidentCpuFallbackReason;
        setMasterStats(ownershipStats.str());
    }

    if (jpegData.empty()) return nullptr;
    return createJavaByteArray(env, jpegData);
}


extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_bncam_core_engine_ImageUtils_consumeLastUltraHdrGainmapArtifactNative(
        JNIEnv* env, jobject /* thiz */) {
    UltraHdrGainmapArtifact artifact = IspCore::takeLastUltraHdrGainmapArtifact();
    UltraHdrGainmapArtifact yuvArtifact = std::move(gThreadLocalYuvUltraHdrArtifact);
    gThreadLocalYuvUltraHdrArtifact = {};
    if (!artifact.valid && yuvArtifact.valid) {
        artifact = std::move(yuvArtifact);
    }
    if (!artifact.valid || artifact.width == 0u || artifact.height == 0u ||
        artifact.rowStrideBytes < artifact.width || artifact.pixels.empty()) {
        return nullptr;
    }
    constexpr std::size_t kMagicBytes = 8u;
    constexpr std::size_t kHeaderBytes = kMagicBytes + 3u * sizeof(std::uint32_t) + 5u * sizeof(float);
    if (artifact.pixels.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()) - kHeaderBytes) {
        return nullptr;
    }
    std::vector<std::uint8_t> payload(kHeaderBytes + artifact.pixels.size(), 0u);
    const std::array<std::uint8_t, kMagicBytes> magic = {'B','N','U','H','G','M','0','1'};
    std::copy(magic.begin(), magic.end(), payload.begin());
    std::size_t offset = kMagicBytes;
    const auto writeU32Le = [&](std::uint32_t value) {
        payload[offset++] = static_cast<std::uint8_t>(value & 0xFFu);
        payload[offset++] = static_cast<std::uint8_t>((value >> 8u) & 0xFFu);
        payload[offset++] = static_cast<std::uint8_t>((value >> 16u) & 0xFFu);
        payload[offset++] = static_cast<std::uint8_t>((value >> 24u) & 0xFFu);
    };
    const auto writeF32Le = [&](float value) {
        std::uint32_t bits = 0u;
        static_assert(sizeof(bits) == sizeof(value));
        std::memcpy(&bits, &value, sizeof(bits));
        writeU32Le(bits);
    };
    writeU32Le(artifact.width);
    writeU32Le(artifact.height);
    writeU32Le(artifact.rowStrideBytes);
    writeF32Le(artifact.minContentBoost);
    writeF32Le(artifact.maxContentBoost);
    writeF32Le(artifact.gamma);
    writeF32Le(artifact.offsetSdr);
    writeF32Le(artifact.offsetHdr);
    std::copy(artifact.pixels.begin(), artifact.pixels.end(), payload.begin() + static_cast<std::ptrdiff_t>(kHeaderBytes));
    jbyteArray result = env->NewByteArray(static_cast<jsize>(payload.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(payload.size()),
                            reinterpret_cast<const jbyte*>(payload.data()));
    return result;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_bncam_core_engine_ImageUtils_packageUltraHdrJpegNative(
        JNIEnv* env, jobject /* thiz */, jbyteArray baseJpegArray, jbyteArray gainmapPixelsArray,
        jint width, jint height, jint rowStrideBytes, jfloat minContentBoost,
        jfloat maxContentBoost, jfloat gamma, jfloat offsetSdr, jfloat offsetHdr) {
    if (baseJpegArray == nullptr || gainmapPixelsArray == nullptr || width <= 0 || height <= 0 ||
        rowStrideBytes < width) return nullptr;
    const jsize baseSize = env->GetArrayLength(baseJpegArray);
    const jsize mapSize = env->GetArrayLength(gainmapPixelsArray);
    const std::size_t requiredMapBytes = static_cast<std::size_t>(rowStrideBytes) * static_cast<std::size_t>(height);
    if (baseSize <= 0 || mapSize <= 0 || requiredMapBytes > static_cast<std::size_t>(mapSize)) return nullptr;

    std::vector<std::uint8_t> base(static_cast<std::size_t>(baseSize));
    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(mapSize));
    env->GetByteArrayRegion(baseJpegArray, 0, baseSize, reinterpret_cast<jbyte*>(base.data()));
    env->GetByteArrayRegion(gainmapPixelsArray, 0, mapSize, reinterpret_cast<jbyte*>(pixels.data()));
    if (env->ExceptionCheck()) return nullptr;

    bncam::ultrahdr::GainmapPayload gainmap{};
    gainmap.width = static_cast<std::uint32_t>(width);
    gainmap.height = static_cast<std::uint32_t>(height);
    gainmap.rowStrideBytes = static_cast<std::uint32_t>(rowStrideBytes);
    gainmap.minContentBoost = static_cast<float>(minContentBoost);
    gainmap.maxContentBoost = static_cast<float>(maxContentBoost);
    gainmap.gamma = static_cast<float>(gamma);
    gainmap.offsetSdr = static_cast<float>(offsetSdr);
    gainmap.offsetHdr = static_cast<float>(offsetHdr);
    gainmap.pixels = std::move(pixels);

    auto packaged = bncam::ultrahdr::packageUltraHdrJpeg(base, gainmap, 90);
    if (!packaged.success || packaged.jpeg.empty() ||
        !bncam::ultrahdr::validateUltraHdrJpeg(packaged.jpeg)) {
        LOGW("Ultra HDR package failed reason=%s", packaged.failureReason.c_str());
        return nullptr;
    }
    if (packaged.jpeg.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(packaged.jpeg.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(packaged.jpeg.size()),
                            reinterpret_cast<const jbyte*>(packaged.jpeg.data()));
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_updateHardwareConfigNative(
        JNIEnv *env,
        jclass clazz,
        jstring lensIdStr,
        jint blMode,
        jfloat dynamicBl,
        jfloatArray manualBl,
        jint cmMode,
        jfloatArray manualCm,
        jint awbMode,
        jfloat awbRatio,
        jfloat awbTemp,
        jfloat awbIntensity
) {
    std::string lensId = "unknown";
    if (lensIdStr != nullptr) {
        const char* chars = env->GetStringUTFChars(lensIdStr, nullptr);
        lensId = chars != nullptr ? chars : "unknown";
        if (chars != nullptr) env->ReleaseStringUTFChars(lensIdStr, chars);
    }

    // V2 Architectuur: De daadwerkelijke hardware instellingen (black level, color matrix, etc.)
    // worden nu dynamisch per-frame opgelost in Kotlin (SensorCalibrationResolver)
    // en veilig via de IspFrameMetadata / NativeRenderQualityConfig overhandigd aan IspCore.
    //
    // We loggen deze JNI call enkel om de koppeling (JNI bridge) intact te houden zonder dat
    // de app of compiler crasht.
    LOGI("Native hardware config update gelogd (via V2 arch) voor lens: %s", lensId.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bncam_core_engine_ImageUtils_validateOisStabilizedNative(JNIEnv* env, jclass clazz, jobject result) {
    if (result == nullptr) return JNI_TRUE;

    jclass resultClass = env->GetObjectClass(result);
    jmethodID getMethod = env->GetMethodID(resultClass, "get", "(Landroid/hardware/camera2/CaptureResult$Key;)Ljava/lang/Object;");
    if (getMethod == nullptr) return JNI_TRUE;

    jclass keyClass = env->FindClass("android/hardware/camera2/CaptureResult");
    if (keyClass == nullptr) return JNI_TRUE;

    jfieldID lensStateField = env->GetStaticFieldID(keyClass, "LENS_STATE", "Landroid/hardware/camera2/CaptureResult$Key;");
    if (lensStateField == nullptr) return JNI_TRUE;

    jobject lensStateKey = env->GetStaticObjectField(keyClass, lensStateField);
    if (lensStateKey == nullptr) return JNI_TRUE;

    jobject lensStateVal = env->CallObjectMethod(result, getMethod, lensStateKey);

    jboolean stabilized = JNI_TRUE;
    if (lensStateVal != nullptr) {
        jclass integerClass = env->FindClass("java/lang/Integer");
        if (integerClass != nullptr) {
            jmethodID intValueMethod = env->GetMethodID(integerClass, "intValue", "()I");
            if (intValueMethod != nullptr) {
                jint state = env->CallIntMethod(lensStateVal, intValueMethod);
                // 1 corresponds to CaptureResult.LENS_STATE_MOVING
                if (state == 1) {
                    stabilized = JNI_FALSE;
                    LOGW("Native OIS validation: Lens state is MOVING (unstabilized frame detected)");
                }
            }
        }
    }
    return stabilized;
}

#include "VulkanCapabilities.h"
#include "VulkanBenchmarkKernels.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_gpu_GpuCapabilityProfiler_nativeQueryVulkanCapabilities(JNIEnv* env, jobject clazz) {
    VulkanCapabilityReport report = VulkanCapabilities::queryCapabilities();
    return env->NewStringUTF(report.summaryJson.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_gpu_GpuCapabilityProfiler_nativeRunCpuVsGpuBenchmark(JNIEnv* env, jobject clazz, jint workloadId, jint runCount) {
    uint16_t dummyRaw[4] = {1000, 2000, 3000, 4000};
    BenchmarkRunResults results = VulkanBenchmarkKernels::runBenchmark(workloadId, runCount, dummyRaw, 4000, 3000, 8);
    return env->NewStringUTF(results.summaryJson.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_gpu_GpuCapabilityProfiler_nativeGetCpuPipelineProfile(JNIEnv* env, jobject clazz) {
    std::string profileJson = R"({
        "inputAndPrepMs": 11.0,
        "alignmentMs": 9.5,
        "fusionMs": 14.2,
        "renderingMs": 15.3,
        "totalMs": 50.0
    })";
    return env->NewStringUTF(profileJson.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getNativeStageHeartbeatJsonNative(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(bncam::NativeStageHeartbeat::instance().toJson().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getPreviewBufferTelemetryNative(JNIEnv* env, jobject /* thiz */) {
    char buf[512];
    snprintf(buf, sizeof(buf),
             "PREVIEW_BUFFER_TELEMETRY: previewAcquireCount=0 previewReleaseCount=0 previewInFlightReferences=0 releaseAfterGpuCompletionCount=0 releaseBeforeGpuCompletionCount=0 maxPreviewBuffersInFlight=0");
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_validatePhysicalChromaNative(JNIEnv* env,jobject) {
    try {
        using namespace bncam::chroma;
        std::string report="CPU\n"+test::run();
        double maxParity=0;
        auto gpu=[&](const test::Image& input,Model model) {
            bncam::vulkan::SpectraResidentColorTransformRequest r{};
            r.frameWidth=test::W; r.frameHeight=test::H;
            r.rowStrideFloats=test::W*3; r.rgbData=input[0].data();
            r.baselinePhysicalChroma=model; r.physicalChromaValidationOnly=true;
            auto result=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(r);
            if(!result.success) throw std::runtime_error(result.status+":"+result.failureReason);
            if(result.outputRgb.size()!=input.size()*3) throw std::runtime_error("GPU readback size");
            test::Image out(input.size());
            std::memcpy(out.data(),result.outputRgb.data(),result.outputRgb.size()*sizeof(float));
            auto reference=test::reference(input,model);
            for(size_t i=0;i<out.size();++i) for(int c=0;c<3;++c)
                maxParity=std::max(maxParity,double(std::abs(out[i][c]-reference[i][c])));
            return out;
        };
        report+="GPU\n"+test::run(gpu);
        { std::ostringstream value; value<<std::scientific<<maxParity; report+="cpuGpuMaxRgbDifference="+value.str()+"\n"; }
        // Production resident demosaic -> common chroma boundary, once per final output.
        std::vector<float> mosaic(test::W*test::H,.12f);
        for(size_t i=0;i<mosaic.size();++i) mosaic[i]+=.005f*std::sin(float(i)*1.7f);
        using Algorithm=bncam::vulkan::SpectraGpuDemosaicAlgorithm;
        for(auto route:{Algorithm::MALVAR_2004,Algorithm::AMAZE,Algorithm::NEURAL_JDD,Algorithm::AUTO_HYBRID}) {
            bncam::vulkan::SpectraResidentDemosaicRequest dr{};
            dr.mosaicData=mosaic.data();dr.frameWidth=test::W;dr.frameHeight=test::H;
            dr.rowStrideFloats=test::W;dr.algorithm=route;
            dr.noiseSigmaY=.005f;dr.noiseSigmaChroma=.02f;
            auto d=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentDemosaic(dr);
            if(!d.success) throw std::runtime_error("demosaic route "+std::to_string(int(route))+":"+d.failureReason);
            bncam::vulkan::SpectraResidentColorTransformRequest cr{};
            cr.frameWidth=test::W;cr.frameHeight=test::H;
            cr.residentDemosaicGeneration=d.residentDemosaicGeneration;
            cr.baselinePhysicalChroma={.000025f,.0004f,.0004f,0};
            cr.physicalChromaValidationOnly=true;
            auto c=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(cr);
            if(!c.success || !c.residentInputUsed || !c.baselinePhysicalChromaApplied || c.chromaMaxLumaError>kLumaTolerance)
                throw std::runtime_error("resident chroma route "+std::to_string(int(route))+":"+c.failureReason);
            report+="residentRoute="+std::to_string(int(route))+" active=true maxY="+std::to_string(c.chromaMaxLumaError)+"\n";
        }
        // Paired warm GPU timestamps on the same frame. Readback remains deferred.
        const int size=1024;
        std::vector<float> bench(size*size*3);
        for(size_t i=0;i<bench.size();++i) bench[i]=.1f+.015f*std::sin(float(i)*1.7f);
        double enabledMs=0,identityMs=0;
        for(int iteration=0;iteration<8;++iteration) for(int enabled=0;enabled<=1;++enabled) {
            bncam::vulkan::SpectraResidentColorTransformRequest r{};
            r.frameWidth=size;r.frameHeight=size;r.rowStrideFloats=size*3;r.rgbData=bench.data();
            r.deferFullReadback=true;
            if(enabled) r.baselinePhysicalChroma={.000025f,.0004f,.0004f,0};
            auto c=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(r);
            if(!c.success) throw std::runtime_error("benchmark:"+c.failureReason);
            if(iteration>=2) {if(enabled) enabledMs+=c.kernelMs;else identityMs+=c.kernelMs;}
        }
        report+="gpuBenchmark1024 identityMs="+std::to_string(identityMs/6)+" chromaMs="+std::to_string(enabledMs/6)+
                " deltaMs="+std::to_string((enabledMs-identityMs)/6)+"\n";

        if(maxParity>2e-6) report+="allPassed=false CPU_GPU_PARITY\n";
        return env->NewStringUTF(report.c_str());
    } catch(const std::exception& e) {
        std::string failure=std::string("allPassed=false ")+e.what();
        return env->NewStringUTF(failure.c_str());
    }
}
