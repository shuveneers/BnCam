#include "VulkanRuntime.h"

#include <jni.h>
#include <string>
#include <vector>

namespace {
using bncam::vulkan::RuntimeConfig;
using bncam::vulkan::RuntimeSnapshot;
using bncam::vulkan::VulkanRuntime;

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jobjectArray toJStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(values.size()), stringClass, nullptr
    );
    if (result == nullptr) return nullptr;
    for (std::size_t index = 0; index < values.size(); ++index) {
        jstring value = env->NewStringUTF(values[index].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeInitialize(
    JNIEnv* env, jobject, jboolean debugValidationRequested, jboolean requireAndroidHardwareBuffer,
    jstring pipelineCachePath
) {
    RuntimeConfig config;
    config.debugValidationRequested = debugValidationRequested == JNI_TRUE;
    config.requireAndroidHardwareBuffer = requireAndroidHardwareBuffer == JNI_TRUE;
    config.pipelineCachePath = fromJString(env, pipelineCachePath);
    return static_cast<jint>(VulkanRuntime::instance().initialize(config).state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeConfigureSpectraNeuralModel(
    JNIEnv* env, jobject, jbyteArray packageBytes
) {
    if (env == nullptr || packageBytes == nullptr) return JNI_FALSE;
    const jsize size = env->GetArrayLength(packageBytes);
    if (size <= 0) return JNI_FALSE;
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(packageBytes, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return VulkanRuntime::instance().configureSpectraNeuralModel(
        bytes.data(), bytes.size(), true, 3u
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeShutdown(JNIEnv*, jobject) {
    return static_cast<jint>(VulkanRuntime::instance().shutdown().state);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetRuntimeStateCode(JNIEnv*, jobject) {
    return static_cast<jint>(VulkanRuntime::instance().snapshot().state);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetSchemaVersion(JNIEnv*, jobject) {
    return bncam::vulkan::kRuntimeSchemaVersion;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetRuntimeIdentity(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().snapshot().runtimeIdentity);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeIsLoaderAvailable(JNIEnv*, jobject) {
    return VulkanRuntime::instance().snapshot().loaderAvailable ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeIsRuntimeInitialized(JNIEnv*, jobject) {
    return VulkanRuntime::instance().snapshot().runtimeInitialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetSelectedDeviceName(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().snapshot().selectedDeviceName);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetEnabledExtensions(JNIEnv* env, jobject) {
    return toJStringArray(env, VulkanRuntime::instance().snapshot().enabledExtensions);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetEnabledFeatures(JNIEnv* env, jobject) {
    return toJStringArray(env, VulkanRuntime::instance().snapshot().enabledFeatures);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetMissingRequirements(JNIEnv* env, jobject) {
    return toJStringArray(env, VulkanRuntime::instance().snapshot().missingRequirements);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetActiveProductionStages(JNIEnv* env, jobject) {
    return toJStringArray(env, VulkanRuntime::instance().snapshot().activeProductionStages);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetCreationCounters(JNIEnv* env, jobject) {
    const RuntimeSnapshot snapshot = VulkanRuntime::instance().snapshot();
    const jlong values[4] = {
        static_cast<jlong>(snapshot.instanceCreationCount),
        static_cast<jlong>(snapshot.deviceCreationCount),
        static_cast<jlong>(snapshot.initializeRequestCount),
        static_cast<jlong>(snapshot.shutdownRequestCount),
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetInFlightSubmissionCount(JNIEnv*, jobject) {
    return static_cast<jlong>(VulkanRuntime::instance().snapshot().inFlightSubmissionCount);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetRawPreviewOutputHardwareBufferUsage(
        JNIEnv*, jobject) {
    return static_cast<jlong>(VulkanRuntime::instance().rawPreviewOutputHardwareBufferUsage());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativePrepareRawPreviewBackend(JNIEnv*, jobject) {
    return VulkanRuntime::instance().prepareRawPreviewBackend() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetLastFailureCode(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().snapshot().lastFailure.code);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetLastFailureMessage(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().snapshot().lastFailure.message);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeIsLastFailureRetryable(JNIEnv*, jobject) {
    return VulkanRuntime::instance().snapshot().lastFailure.retryable ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetRuntimeSnapshotJson(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().snapshot().toJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetCapabilitiesJson(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().capabilities().toJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetDiagnosticsJson(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().diagnosticsJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetValidationMessagesJson(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().validationSnapshot().toJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetRuntimeHumanReadable(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().diagnosticsHumanReadable());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetCapabilitiesHumanReadable(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().capabilities().toHumanReadable());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_vulkan_VulkanNativeBridge_nativeGetValidationHumanReadable(JNIEnv* env, jobject) {
    return toJString(env, VulkanRuntime::instance().validationSnapshot().toHumanReadable());
}
