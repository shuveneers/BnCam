#!/usr/bin/env python3
"""Offline verification for the Phase 3A Vulkan preparation layer.

This intentionally does not pretend to replace an Android/NDK build or device test. It checks
ownership/source contracts and host-compiles the new native preparation sources against minimal
API-shaped stubs so ordinary C++ syntax and JNI name drift are caught before handoff.
"""
from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import tempfile

APP = pathlib.Path(__file__).resolve().parents[1]
CPP = APP / "src" / "main" / "cpp"
VULKAN = CPP / "vulkan"
JAVA = APP / "src" / "main" / "java"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"VERIFY FAILED: {message}")


def read(relative: str) -> str:
    path = APP / relative
    require(path.is_file(), f"missing {relative}")
    return path.read_text(encoding="utf-8")


required_files = [
    "src/main/cpp/vulkan/VulkanRuntime.cpp",
    "src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp",
    "src/main/cpp/vulkan/VulkanRuntimeJni.cpp",
    "src/main/cpp/vulkan/VulkanValidationCollector.cpp",
    "src/main/cpp/vulkan/VulkanDebugUtilsAdapter.cpp",
    "src/main/cpp/vulkan/VulkanVmaIntegration.cpp",
    "src/main/cpp/vulkan/VulkanResourceContracts.h",
    "src/main/java/com/bncam/core/vulkan/VulkanRuntimeOwner.kt",
    "src/main/java/com/bncam/core/nativebridge/NativeEngineLoader.kt",
]
for item in required_files:
    require((APP / item).is_file(), f"missing required preparation file {item}")

runtime = read("src/main/cpp/vulkan/VulkanRuntime.cpp")
bootstrap = read("src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp")
capability = read("src/main/cpp/VulkanCapabilities.cpp")
benchmark = read("src/main/cpp/VulkanBenchmarkKernels.cpp")
application = read("src/main/java/com/bncam/BnCamApplication.kt")
compute = read("src/main/java/com/bncam/core/compute/ComputeBackend.kt")
collector = read("src/main/cpp/vulkan/VulkanValidationCollector.cpp")
adapter = read("src/main/cpp/vulkan/VulkanDebugUtilsAdapter.cpp")
cmake = read("src/main/cpp/CMakeLists.txt")

require("static VulkanRuntime runtime" in runtime, "authoritative native singleton missing")
require("VULKAN_BOOTSTRAP_NOT_INJECTED" in bootstrap, "placeholder must remain truthful")
require("vkCreateInstance" not in capability, "legacy capability probe still creates VkInstance")
require("vkCreateInstance" not in benchmark, "legacy benchmark still creates VkInstance")
require("RETIRED" in benchmark, "legacy comparison endpoint is not retired")
require("VulkanRuntimeOwner.attach(applicationContext)" in application,
        "application-scoped runtime attachment missing")
require("VulkanRuntimeOwner.initialize" not in application,
        "prep must not fake or activate the unimplemented runtime")
require('id = "vulkan"' in compute and "productionCaptureConnected = false" in compute,
        "ComputeBackend Vulkan truth is missing")
require("activeProductionStages = {}" in runtime,
        "prep must expose no active Vulkan production stages")
require("std::try_to_lock" in collector,
        "validation callback collector may block a driver callback thread")
for forbidden in ("JNIEnv", "NewStringUTF", "ofstream", "fopen("):
    require(forbidden not in collector + adapter,
            f"validation callback path contains forbidden operation: {forbidden}")
require("vma-3.3.0" in cmake and "BNCAM_VMA_HEADER_AVAILABLE" in cmake,
        "pinned VMA build integration missing")
require("vulkan-lib" in cmake, "Android Vulkan loader is not linked")

# Exactly one Kotlin System.loadLibrary owner.
load_callers = []
for path in JAVA.rglob("*.kt"):
    if "System.loadLibrary" in path.read_text(encoding="utf-8"):
        load_callers.append(path.name)
require(load_callers == ["NativeEngineLoader.kt"],
        f"competing native library loaders found: {load_callers}")
main_activity = read("src/main/java/com/bncam/MainActivity.kt")
require("OpenCVLoader.initDebug" not in main_activity,
        "MainActivity still owns a competing OpenCV/native initialization path")

# JNI methods remain exactly mirrored.
kotlin_bridge = read("src/main/java/com/bncam/core/vulkan/VulkanNativeBridge.kt")
native_bridge = read("src/main/cpp/vulkan/VulkanRuntimeJni.cpp")
kotlin_names = set(re.findall(r"external\s+fun\s+(native\w+)", kotlin_bridge))
native_names = set(re.findall(
    r"Java_com_bncam_core_vulkan_VulkanNativeBridge_(native\w+)", native_bridge
))
require(kotlin_names == native_names,
        f"JNI bridge mismatch Kotlin-only={sorted(kotlin_names-native_names)} "
        f"native-only={sorted(native_names-kotlin_names)}")

compiler = shutil.which("clang++") or shutil.which("g++")
require(compiler is not None, "no host C++ compiler available")

with tempfile.TemporaryDirectory(prefix="bncam-vulkan-syntax-") as raw_tmp:
    tmp = pathlib.Path(raw_tmp)
    (tmp / "vulkan").mkdir()
    (tmp / "android").mkdir()
    (tmp / "vulkan" / "vulkan.h").write_text(r'''#pragma once
#include <cstdint>
#define VKAPI_ATTR
#define VKAPI_CALL
#define VK_FALSE 0
#define VK_SUCCESS 0
#define VK_API_VERSION_1_1 1u
#define VK_NULL_HANDLE nullptr
using VkBool32 = std::uint32_t;
using VkResult = std::int32_t;
struct VkInstance_T; using VkInstance = VkInstance_T*;
struct VkPhysicalDevice_T; using VkPhysicalDevice = VkPhysicalDevice_T*;
struct VkDevice_T; using VkDevice = VkDevice_T*;
struct VkQueue_T; using VkQueue = VkQueue_T*;
struct VkCommandPool_T; using VkCommandPool = VkCommandPool_T*;
struct VkDescriptorPool_T; using VkDescriptorPool = VkDescriptorPool_T*;
struct VkPipelineCache_T; using VkPipelineCache = VkPipelineCache_T*;
struct VkDebugUtilsMessengerEXT_T; using VkDebugUtilsMessengerEXT = VkDebugUtilsMessengerEXT_T*;
struct VkBuffer_T; using VkBuffer = VkBuffer_T*;
struct VkImage_T; using VkImage = VkImage_T*;
struct VkImageView_T; using VkImageView = VkImageView_T*;
struct VkFence_T; using VkFence = VkFence_T*;
using VkObjectType = std::int32_t;
using VkDebugUtilsMessageSeverityFlagBitsEXT = std::uint32_t;
using VkDebugUtilsMessageTypeFlagsEXT = std::uint32_t;
constexpr auto VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT = 0x1u;
constexpr auto VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT = 0x2u;
constexpr auto VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT = 0x4u;
constexpr auto VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT = 0x1u;
constexpr auto VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT = 0x2u;
constexpr auto VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT = 0x4u;
struct VkDebugUtilsObjectNameInfoEXT { VkObjectType objectType; const char* pObjectName; };
struct VkDebugUtilsMessengerCallbackDataEXT {
    std::int32_t messageIdNumber;
    const char* pMessageIdName;
    const char* pMessage;
    std::uint32_t objectCount;
    const VkDebugUtilsObjectNameInfoEXT* pObjects;
};
inline void* vkGetInstanceProcAddr = nullptr;
inline void* vkGetDeviceProcAddr = nullptr;
''', encoding="utf-8")
    (tmp / "android" / "hardware_buffer.h").write_text(
        "#pragma once\nstruct AHardwareBuffer;\n", encoding="utf-8"
    )

    jdk = pathlib.Path("/usr/lib/jvm/java-21-openjdk-amd64/include")
    require((jdk / "jni.h").is_file(), "host JDK JNI headers unavailable")
    sources = sorted(VULKAN.glob("*.cpp")) + [
        CPP / "VulkanCapabilities.cpp",
        CPP / "VulkanBenchmarkKernels.cpp",
    ]
    command = [
        compiler,
        "-std=c++17",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-fsyntax-only",
        "-DBNCAM_VMA_HEADER_AVAILABLE=0",
        f"-I{tmp}",
        f"-I{VULKAN}",
        f"-I{CPP}",
        f"-I{jdk}",
        f"-I{jdk / 'linux'}",
        *map(str, sources),
    ]
    subprocess.run(command, check=True)


# Host-compile the new Kotlin ownership/contracts against tiny Android API stubs.
kotlinc = shutil.which("kotlinc")
require(kotlinc is not None, "host Kotlin compiler unavailable")
with tempfile.TemporaryDirectory(prefix="bncam-vulkan-kotlin-") as raw_tmp:
    tmp = pathlib.Path(raw_tmp)
    stubs = {
        "Context.kt": """package android.content
open class Context {
    open val applicationContext: Context get() = this
    open val packageName: String get() = \"com.bncam.test\"
}
""",
        "Application.kt": """package android.app
open class Application : android.content.Context() {
    open fun onCreate() {}
    open fun onTerminate() {}
}
""",
        "Build.kt": """package android.os
object Build {
    object VERSION { const val SDK_INT: Int = 36 }
    object VERSION_CODES { const val P: Int = 28 }
}
""",
        "Log.kt": """@file:Suppress(\"UNUSED_PARAMETER\")
package android.util
object Log {
    fun i(tag: String, message: String): Int = 0
    fun d(tag: String, message: String): Int = 0
    fun w(tag: String, message: String, failure: Throwable? = null): Int = 0
    fun e(tag: String, message: String, failure: Throwable? = null): Int = 0
}
""",
        "HiddenApiBypass.kt": """@file:Suppress(\"UNUSED_PARAMETER\")
package org.lsposed.hiddenapibypass
object HiddenApiBypass { fun addHiddenApiExemptions(vararg prefixes: String) {} }
""",
    }
    stub_paths = []
    for name, content in stubs.items():
        path = tmp / name
        path.write_text(content, encoding="utf-8")
        stub_paths.append(path)
    output = tmp / "vulkan-prep.jar"
    kotlin_sources = stub_paths + [
        JAVA / "com/bncam/core/tracing/StableJson.kt",
        JAVA / "com/bncam/core/nativebridge/NativeEngineLoader.kt",
        JAVA / "com/bncam/core/vulkan/VulkanRuntimeModels.kt",
        JAVA / "com/bncam/core/vulkan/VulkanNativeBridge.kt",
        JAVA / "com/bncam/core/vulkan/VulkanRuntimeOwner.kt",
        JAVA / "com/bncam/core/compute/ComputeBackend.kt",
        JAVA / "com/bncam/BnCamApplication.kt",
    ]
    subprocess.run([
        kotlinc,
        *map(str, kotlin_sources),
        "-d",
        str(output),
    ], check=True)

print("Vulkan preparation static/source/JNI/native syntax verification PASS")
print("NOTE: this is not an Android Gradle/NDK build and not a connected-device test")
