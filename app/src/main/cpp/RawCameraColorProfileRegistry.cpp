#include "RawCameraColorProfileRegistry.h"

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <limits>

namespace bncam::color {
namespace {

bool finiteMatrix(const std::array<float, 9>& m) noexcept {
    float maxAbs = 0.0f;
    for (float v : m) {
        if (!std::isfinite(v)) return false;
        maxAbs = std::max(maxAbs, std::abs(v));
    }
    const float det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                      m[1] * (m[3] * m[8] - m[5] * m[6]) +
                      m[2] * (m[3] * m[7] - m[4] * m[6]);
    return maxAbs <= 64.0f && std::isfinite(det) && std::abs(det) >= 1.0e-8f;
}

bool tableValid(const RawCameraNativeHueSatProfile& p, const std::vector<float>& table) noexcept {
    const std::uint64_t entries = static_cast<std::uint64_t>(p.hueDivisions) *
            static_cast<std::uint64_t>(p.saturationDivisions) * static_cast<std::uint64_t>(p.valueDivisions);
    if (entries == 0u || entries > (1u << 20) || table.size() != entries * 3u) return false;
    for (std::size_t i = 0; i < table.size(); i += 3u) {
        if (!std::isfinite(table[i]) || !std::isfinite(table[i + 1]) || !std::isfinite(table[i + 2]) ||
            table[i + 1] < 0.0f || table[i + 2] < 0.0f) return false;
    }
    for (int v = 0; v < p.valueDivisions; ++v) {
        for (int h = 0; h < p.hueDivisions; ++h) {
            const std::size_t cell = (static_cast<std::size_t>(v * p.hueDivisions + h) *
                                      static_cast<std::size_t>(p.saturationDivisions)) * 3u;
            if (std::abs(table[cell + 2u] - 1.0f) > 1.0e-5f) return false;
        }
    }
    return true;
}

std::array<float, 9> identity3() noexcept {
    return {1,0,0, 0,1,0, 0,0,1};
}

std::array<float, 9> readMatrix(JNIEnv* env, jfloatArray a, bool required, bool* present) {
    if (present) *present = false;
    if (a == nullptr || env->GetArrayLength(a) != 9) return required ? std::array<float,9>{} : identity3();
    std::array<float, 9> out{};
    env->GetFloatArrayRegion(a, 0, 9, out.data());
    if (present) *present = true;
    return out;
}


std::array<float, 3> readPositiveVec3(JNIEnv* env, jfloatArray a, bool* valid) {
    if (valid) *valid = false;
    std::array<float, 3> out{1.0f, 1.0f, 1.0f};
    if (a == nullptr || env->GetArrayLength(a) != 3) return out;
    env->GetFloatArrayRegion(a, 0, 3, out.data());
    for (float v : out) {
        if (!std::isfinite(v) || v <= 0.0f || v > 64.0f) return {1.0f, 1.0f, 1.0f};
    }
    if (valid) *valid = true;
    return out;
}

std::vector<float> readFloatVector(JNIEnv* env, jfloatArray a) {
    if (a == nullptr) return {};
    const jsize n = env->GetArrayLength(a);
    if (n <= 0 || n > static_cast<jsize>((1u << 20) * 3u)) return {};
    std::vector<float> out(static_cast<std::size_t>(n));
    env->GetFloatArrayRegion(a, 0, n, out.data());
    return out;
}

std::string readString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

bool RawCameraNativeHueSatProfile::valid() const noexcept {
    if (profileId.empty() || sourcePriority <= 0 || calibrationIlluminant1 == 0) return false;
    if (!finiteMatrix(colorMatrix1) || !finiteMatrix(discoveryEffectiveCcm)) return false;
    if (!hasForwardMatrix1 || !finiteMatrix(forwardMatrix1)) return false;
    if (hasColorMatrix2 && !finiteMatrix(colorMatrix2)) return false;
    if (hasCameraCalibration1 && !finiteMatrix(cameraCalibration1)) return false;
    if (hasCameraCalibration2 && !finiteMatrix(cameraCalibration2)) return false;
    if (hasForwardMatrix2 && !finiteMatrix(forwardMatrix2)) return false;
    for (float v : analogBalance) {
        if (!std::isfinite(v) || v <= 0.0f || v > 64.0f) return false;
    }

    const bool anySecondary = calibrationIlluminant2 != 0 || hasColorMatrix2 ||
            hasCameraCalibration2 || hasForwardMatrix2;
    if (anySecondary && !dualCharacterization()) return false;

    // DNG matrix characterization is independently valid. ProfileHueSatMap is optional profile
    // data and must not be fabricated merely to make the physical ForwardMatrix route usable.
    if (!hasHueSatMap()) {
        return hueDivisions == 0 && saturationDivisions == 0 && valueDivisions == 0 &&
                hueSatData2.empty();
    }
    if (hueDivisions < 1 || saturationDivisions < 2 || valueDivisions < 1 ||
        (encoding != 0 && encoding != 1)) return false;
    if (!tableValid(*this, hueSatData1)) return false;
    if (dualHueSatMap() && !tableValid(*this, hueSatData2)) return false;
    if (dualHueSatMap() && !dualCharacterization()) return false;
    return true;
}

RawCameraColorProfileRegistry& RawCameraColorProfileRegistry::instance() noexcept {
    static RawCameraColorProfileRegistry registry;
    return registry;
}

bool RawCameraColorProfileRegistry::install(RawCameraNativeHueSatProfile profile) noexcept {
    if (!profile.valid()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = std::find_if(profiles_.begin(), profiles_.end(), [&](const auto& p) { return p.profileId == profile.profileId; });
    if (it != profiles_.end() && it->sourcePriority > profile.sourcePriority) return false;
    profile.installGeneration = ++generation_;
    if (it == profiles_.end()) profiles_.push_back(std::move(profile)); else *it = std::move(profile);
    return true;
}

RawCameraProfileRegistrySnapshot RawCameraColorProfileRegistry::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return {profiles_, generation_};
}

void RawCameraColorProfileRegistry::clear() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    profiles_.clear();
    ++generation_;
}

} // namespace bncam::color

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_isp_raw10_RawCameraColorProfileNativeBridge_nativeInstallProfile(
        JNIEnv* env, jclass,
        jstring profileId,
        jint sourcePriority,
        jint calibrationIlluminant1,
        jint calibrationIlluminant2,
        jfloatArray colorMatrix1,
        jfloatArray colorMatrix2,
        jfloatArray cameraCalibration1,
        jfloatArray cameraCalibration2,
        jfloatArray forwardMatrix1,
        jfloatArray forwardMatrix2,
        jfloatArray analogBalance,
        jfloatArray discoveryEffectiveCcm,
        jint hueDivisions,
        jint saturationDivisions,
        jint valueDivisions,
        jint encoding,
        jfloatArray hueSatData1,
        jfloatArray hueSatData2) {
    using namespace bncam::color;
    RawCameraNativeHueSatProfile p{};
    p.profileId = readString(env, profileId);
    p.sourcePriority = sourcePriority;
    p.calibrationIlluminant1 = calibrationIlluminant1;
    p.calibrationIlluminant2 = calibrationIlluminant2;
    bool present = false;
    p.colorMatrix1 = readMatrix(env, colorMatrix1, true, &present);
    if (!present) return JNI_FALSE;
    p.colorMatrix2 = readMatrix(env, colorMatrix2, false, &p.hasColorMatrix2);
    p.cameraCalibration1 = readMatrix(env, cameraCalibration1, false, &p.hasCameraCalibration1);
    p.cameraCalibration2 = readMatrix(env, cameraCalibration2, false, &p.hasCameraCalibration2);
    p.forwardMatrix1 = readMatrix(env, forwardMatrix1, false, &p.hasForwardMatrix1);
    p.forwardMatrix2 = readMatrix(env, forwardMatrix2, false, &p.hasForwardMatrix2);
    bool analogBalanceValid = false;
    p.analogBalance = readPositiveVec3(env, analogBalance, &analogBalanceValid);
    if (!analogBalanceValid) return JNI_FALSE;
    p.discoveryEffectiveCcm = readMatrix(env, discoveryEffectiveCcm, true, &present);
    if (!present) return JNI_FALSE;
    p.hueDivisions = hueDivisions;
    p.saturationDivisions = saturationDivisions;
    p.valueDivisions = valueDivisions;
    p.encoding = encoding;
    p.hueSatData1 = readFloatVector(env, hueSatData1);
    p.hueSatData2 = readFloatVector(env, hueSatData2);
    return RawCameraColorProfileRegistry::instance().install(std::move(p)) ? JNI_TRUE : JNI_FALSE;
}
