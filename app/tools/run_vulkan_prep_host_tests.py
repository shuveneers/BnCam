#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import shutil
import subprocess
import tempfile

APP = pathlib.Path(__file__).resolve().parents[1]
CPP_ROOT = APP / "src" / "main" / "cpp"
CPP = CPP_ROOT / "vulkan"
TEST_ROOT = APP / "src" / "test" / "cpp"
COMPILER = shutil.which("clang++") or shutil.which("g++")
if not COMPILER:
    raise SystemExit("No host C++ compiler available")


def compile_and_run(name: str, sources: list[pathlib.Path], include_dirs: list[pathlib.Path], defines: list[str] | None = None) -> None:
    with tempfile.TemporaryDirectory(prefix=f"bncam-{name}-") as tmp:
        binary = pathlib.Path(tmp) / name
        command = [
            COMPILER,
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-pthread",
            *(defines or []),
            *[f"-I{directory}" for directory in include_dirs],
            *map(str, sources),
            "-o",
            str(binary),
        ]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)


compile_and_run(
    "vulkan_validation_test",
    [
        TEST_ROOT / "VulkanValidationCollectorHostTest.cpp",
        CPP / "VulkanValidationCollector.cpp",
        CPP / "VulkanJson.cpp",
    ],
    [CPP],
)

with tempfile.TemporaryDirectory(prefix="bncam-vulkan-stubs-") as raw_tmp:
    stubs = pathlib.Path(raw_tmp)
    (stubs / "vulkan").mkdir()
    (stubs / "vulkan" / "vulkan.h").write_text(r'''#pragma once
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
inline void* vkGetInstanceProcAddr = nullptr;
inline void* vkGetDeviceProcAddr = nullptr;
''', encoding="utf-8")

    compile_and_run(
        "vulkan_runtime_prep_test",
        [
            TEST_ROOT / "VulkanRuntimePrepHostTest.cpp",
            CPP / "VulkanRuntime.cpp",
            CPP / "VulkanRuntimeBootstrap.cpp",
            CPP / "VulkanRuntimeContracts.cpp",
            CPP / "VulkanValidationCollector.cpp",
            CPP / "VulkanVmaIntegration.cpp",
            CPP / "VulkanJson.cpp",
        ],
        [stubs, CPP],
        ["-DBNCAM_VMA_HEADER_AVAILABLE=0"],
    )

print("Host-side Vulkan preparation tests PASS")
