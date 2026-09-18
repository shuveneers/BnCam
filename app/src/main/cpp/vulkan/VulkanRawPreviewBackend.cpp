#include "VulkanRawPreviewBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_RAW_PREVIEW_SHADER_AVAILABLE
#define BNCAM_RAW_PREVIEW_SHADER_AVAILABLE 0
#endif
#if BNCAM_RAW_PREVIEW_SHADER_AVAILABLE
#include "RawPreviewSpirv.h"
#endif
#ifndef BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE
#define BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE 0
#endif
#if BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE
#include "RawPreviewImageSpirv.h"
#endif

#include "VulkanRuntime.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan_android.h>
#include <atomic>
#include <cmath>

static std::atomic<std::uint64_t> gPreviewAcquireCount{0u};
static std::atomic<std::uint64_t> gPreviewReleaseCount{0u};
static std::atomic<std::uint64_t> gReleaseAfterGpuCompletionCount{0u};
static std::atomic<std::uint64_t> gReleaseBeforeGpuCompletionCount{0u};
static std::atomic<std::uint64_t> gMaxPreviewBuffersInFlight{0u};

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(
            std::chrono::duration<double, std::milli>(Clock::now() - started).count());
}

constexpr std::uint64_t kHueSatHeaderFloats = 16u;

bool hueSatTableValid(const RawPreviewGpuRequest& request, const float* table,
                      std::size_t floatCount) noexcept {
    if (table == nullptr || request.hueSatHueDivisions < 1u ||
        request.hueSatSaturationDivisions < 2u || request.hueSatValueDivisions < 1u ||
        request.hueSatEncoding > 1u) return false;
    const std::uint64_t entries = static_cast<std::uint64_t>(request.hueSatHueDivisions) *
            request.hueSatSaturationDivisions * request.hueSatValueDivisions;
    if (entries == 0u || entries > (1u << 20) || floatCount != entries * 3u) return false;
    for (std::uint64_t i = 0u; i < entries; ++i) {
        const float h = table[i * 3u + 0u];
        const float sat = table[i * 3u + 1u];
        const float val = table[i * 3u + 2u];
        if (!std::isfinite(h) || !std::isfinite(sat) || !std::isfinite(val) ||
            sat < 0.0f || val < 0.0f) return false;
    }
    // DNG requires the zero-saturation value scale to remain identity. Enforce the same strict
    // invariant as capture so malformed profile data can never become preview-only colour truth.
    for (std::uint32_t v = 0u; v < request.hueSatValueDivisions; ++v) {
        for (std::uint32_t h = 0u; h < request.hueSatHueDivisions; ++h) {
            const std::uint64_t cell =
                    (static_cast<std::uint64_t>(v) * request.hueSatHueDivisions + h) *
                    request.hueSatSaturationDivisions;
            if (std::abs(table[cell * 3u + 2u] - 1.0f) > 1.0e-5f) return false;
        }
    }
    return true;
}

struct alignas(16) PushConstants {
    std::uint32_t sourceWidth = 0;
    std::uint32_t sourceHeight = 0;
    std::uint32_t sourceRowStrideBytes = 0;
    std::uint32_t sourceLayout = 0;
    std::uint32_t previewWidth = 0;
    std::uint32_t previewHeight = 0;
    std::uint32_t cfaCellDecimation = 1;
    std::uint32_t cfaAndMode = 0;
    float blackLevels[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float whiteLevel = 1.0f;
    float captureIso = 100.0f;
    float captureExposureMs = 0.0f;
    float histogramStride = 8.0f;
    float wbR = 1.0f;
    float wbG = 1.0f;
    float wbB = 1.0f;
    std::uint32_t sourceCropPacked = 0u;
    float ccm[9]{1.0f, 0.0f, 0.0f,
                 0.0f, 1.0f, 0.0f,
                 0.0f, 0.0f, 1.0f};
    float profileSaturation = 0.0f;
    float profileContrast = 0.0f;
    float profileVibrance = 0.0f;
};
static_assert(sizeof(PushConstants) == 128u, "RAW preview push constant layout mismatch");
constexpr std::uint64_t PREVIEW_AWB_STATS_START_WORD = 572u;
constexpr std::uint64_t PREVIEW_AWB_SAMPLE_WORDS = 5u;
constexpr std::uint64_t PREVIEW_AWB_STATS_WORDS =
        static_cast<std::uint64_t>(RAW_PREVIEW_AWB_SAMPLE_COUNT) * PREVIEW_AWB_SAMPLE_WORDS;
constexpr std::uint64_t PREVIEW_PHYSICAL_NOISE_START_WORD =
        PREVIEW_AWB_STATS_START_WORD + PREVIEW_AWB_STATS_WORDS;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_GRID_WIDTH = 64u;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_GRID_HEIGHT = 48u;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_TILE_COUNT =
        PREVIEW_SPATIAL_EXPOSURE_GRID_WIDTH * PREVIEW_SPATIAL_EXPOSURE_GRID_HEIGHT;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_EVIDENCE_WORDS = 4u;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_EVIDENCE_START_WORD =
        PREVIEW_PHYSICAL_NOISE_START_WORD + 3u;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_MAP_START_WORD =
        PREVIEW_SPATIAL_EXPOSURE_EVIDENCE_START_WORD +
        PREVIEW_SPATIAL_EXPOSURE_TILE_COUNT * PREVIEW_SPATIAL_EXPOSURE_EVIDENCE_WORDS;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_LOG_HIST_BINS = 192u;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_LOG_HIST_START_WORD =
        PREVIEW_SPATIAL_EXPOSURE_MAP_START_WORD + PREVIEW_SPATIAL_EXPOSURE_TILE_COUNT;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD =
        PREVIEW_SPATIAL_EXPOSURE_LOG_HIST_START_WORD + PREVIEW_SPATIAL_EXPOSURE_LOG_HIST_BINS;
constexpr std::uint64_t PREVIEW_SPATIAL_EXPOSURE_SUMMARY_WORDS = 12u;
constexpr std::uint64_t PREVIEW_ANALYSIS_NV21_START_WORD =
        PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + PREVIEW_SPATIAL_EXPOSURE_SUMMARY_WORDS;
static_assert(PREVIEW_PHYSICAL_NOISE_START_WORD == 15932u, "RAW preview physical-noise ABI mismatch");
static_assert(PREVIEW_ANALYSIS_NV21_START_WORD == 31499u, "RAW preview Phase-5 statistics ABI mismatch");
}  // namespace

bool VulkanRawPreviewBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        std::uint32_t hostAccess,
        PersistentBuffer& buffer,
        bool& reallocated,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_RAW_PREVIEW_BUFFER_REQUEST";
        return false;
    }
    const bool mappedRequired = hostAccess != 0u;
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr &&
        buffer.capacityBytes >= bytes && (!mappedRequired || buffer.mapped != nullptr)) {
        return true;
    }
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
    buffer = {};
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(bytes);
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = mappedRequired
            ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    allocationInfo.flags = mappedRequired
            ? hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0u;
    VmaAllocationInfo allocationResult{};
    const VkResult create = vmaCreateBuffer(
            allocator, &info, &allocationInfo, &buffer.buffer, &buffer.allocation,
            &allocationResult);
    if (create != VK_SUCCESS || buffer.buffer == VK_NULL_HANDLE || buffer.allocation == nullptr ||
        (mappedRequired && allocationResult.pMappedData == nullptr)) {
        buffer = {};
        failureReason = "vmaCreateBuffer_raw_preview_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    vmaGetAllocationMemoryProperties(allocator, buffer.allocation, &buffer.memoryProperties);
    reallocated = true;
    return true;
#endif
}

void VulkanRawPreviewBackend::destroyImportedInputLocked(
        VkDevice device, ImportedInputBuffer& input) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (input.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, input.buffer, nullptr);
        if (input.memory != VK_NULL_HANDLE) vkFreeMemory(device, input.memory, nullptr);
    }
    if (input.hardwareBuffer != nullptr) AHardwareBuffer_release(input.hardwareBuffer);
    input = {};
}

bool VulkanRawPreviewBackend::tryImportInputBufferLocked(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        bool foreignQueueFamilyEnabled,
        FrameSlot& slot,
        AHardwareBuffer* inputBuffer,
        VkDeviceSize requiredBytes,
        RawPreviewGpuResult& result,
        std::string& failureReason) noexcept {
    if (inputBuffer == nullptr || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE ||
        requiredBytes == 0u) {
        result.inputInteropStatus = 2u;
        return false;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(inputBuffer, &desc);
    result.inputAhbFormat = desc.format;
    result.inputAhbUsage = desc.usage;

    // Android only defines AHardwareBuffer BLOB allocations carrying GPU_DATA_BUFFER usage as
    // byte-addressable shader storage/uniform buffers. Camera RAW image allocations must not be
    // reinterpreted as VkBuffer memory merely because the AHB extension exists.
    if (desc.format != AHARDWAREBUFFER_FORMAT_BLOB || desc.height != 1u || desc.layers != 1u ||
        (desc.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) == 0u ||
        static_cast<VkDeviceSize>(desc.width) < requiredBytes) {
        result.inputInteropStatus = 2u;
        return false;
    }
    if (!foreignQueueFamilyEnabled) {
        result.inputInteropStatus = 3u;
        failureReason = "RAW_PREVIEW_INPUT_FOREIGN_QUEUE_FAMILY_UNAVAILABLE";
        return false;
    }

    if (slot.importedInput.hardwareBuffer == inputBuffer &&
        slot.importedInput.buffer != VK_NULL_HANDLE && slot.importedInput.size >= requiredBytes) {
        result.directHardwareBufferInputUsed = true;
        result.inputInteropStatus = 1u;
        return true;
    }
    destroyImportedInputLocked(device, slot.importedInput);

    auto getAhbProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getAhbProperties == nullptr) {
        result.inputInteropStatus = 3u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_VULKAN_EXTENSION_UNAVAILABLE";
        return false;
    }

    VkPhysicalDeviceExternalBufferInfo externalQuery{};
    externalQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO;
    externalQuery.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    externalQuery.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkExternalBufferProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES;
    vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, &externalQuery, &externalProperties);
    if ((externalProperties.externalMemoryProperties.externalMemoryFeatures &
         VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0u) {
        result.inputInteropStatus = 3u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_BUFFER_NOT_IMPORTABLE";
        return false;
    }

    VkExternalMemoryBufferCreateInfo externalCreate{};
    externalCreate.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    externalCreate.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.pNext = &externalCreate;
    bufferInfo.size = requiredBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkBuffer importedBuffer = VK_NULL_HANDLE;
    const VkResult createResult = vkCreateBuffer(device, &bufferInfo, nullptr, &importedBuffer);
    if (createResult != VK_SUCCESS) {
        result.inputInteropStatus = 4u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_BUFFER_CREATE_FAILED_" + std::to_string(createResult);
        return false;
    }

    VkAndroidHardwareBufferPropertiesANDROID ahbProperties{};
    ahbProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    const VkResult propsResult = getAhbProperties(device, inputBuffer, &ahbProperties);
    if (propsResult != VK_SUCCESS) {
        vkDestroyBuffer(device, importedBuffer, nullptr);
        result.inputInteropStatus = 4u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_PROPERTIES_FAILED_" + std::to_string(propsResult);
        return false;
    }

    VkMemoryRequirements memoryRequirements{};
    vkGetBufferMemoryRequirements(device, importedBuffer, &memoryRequirements);
    const std::uint32_t compatibleTypes =
            memoryRequirements.memoryTypeBits & ahbProperties.memoryTypeBits;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    std::uint32_t memoryTypeIndex = UINT32_MAX;
    for (std::uint32_t index = 0u; index < memoryProperties.memoryTypeCount; ++index) {
        if ((compatibleTypes & (1u << index)) != 0u) {
            memoryTypeIndex = index;
            break;
        }
    }
    if (memoryTypeIndex == UINT32_MAX) {
        vkDestroyBuffer(device, importedBuffer, nullptr);
        result.inputInteropStatus = 4u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_MEMORY_TYPE_MISSING";
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = inputBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.buffer = importedBuffer;
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.pNext = &dedicatedInfo;
    allocateInfo.allocationSize = ahbProperties.allocationSize;
    allocateInfo.memoryTypeIndex = memoryTypeIndex;
    VkDeviceMemory importedMemory = VK_NULL_HANDLE;
    const VkResult allocateResult = vkAllocateMemory(device, &allocateInfo, nullptr, &importedMemory);
    if (allocateResult != VK_SUCCESS) {
        vkDestroyBuffer(device, importedBuffer, nullptr);
        result.inputInteropStatus = 4u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_MEMORY_IMPORT_FAILED_" + std::to_string(allocateResult);
        return false;
    }
    const VkResult bindResult = vkBindBufferMemory(device, importedBuffer, importedMemory, 0u);
    if (bindResult != VK_SUCCESS) {
        vkFreeMemory(device, importedMemory, nullptr);
        vkDestroyBuffer(device, importedBuffer, nullptr);
        result.inputInteropStatus = 4u;
        failureReason = "RAW_PREVIEW_INPUT_AHB_BIND_FAILED_" + std::to_string(bindResult);
        return false;
    }

    AHardwareBuffer_acquire(inputBuffer);
    slot.importedInput.hardwareBuffer = inputBuffer;
    slot.importedInput.buffer = importedBuffer;
    slot.importedInput.memory = importedMemory;
    slot.importedInput.size = requiredBytes;
    result.directHardwareBufferInputUsed = true;
    result.inputInteropStatus = 1u;
    return true;
}

void VulkanRawPreviewBackend::destroyImportedOutputLocked(
        VkDevice device, ImportedOutputImage& output) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (output.view != VK_NULL_HANDLE) vkDestroyImageView(device, output.view, nullptr);
        if (output.image != VK_NULL_HANDLE) vkDestroyImage(device, output.image, nullptr);
        if (output.memory != VK_NULL_HANDLE) vkFreeMemory(device, output.memory, nullptr);
    }
    if (output.hardwareBuffer != nullptr) AHardwareBuffer_release(output.hardwareBuffer);
    output = {};
}

bool VulkanRawPreviewBackend::ensureImportedOutputLocked(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        FrameSlot& slot,
        AHardwareBuffer* outputBuffer,
        std::uint32_t expectedWidth,
        std::uint32_t expectedHeight,
        std::string& failureReason) noexcept {
    if (outputBuffer == nullptr || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_INVALID";
        return false;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(outputBuffer, &desc);
    if (desc.width != expectedWidth || desc.height != expectedHeight || desc.layers != 1u) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_DIMENSION_MISMATCH";
        return false;
    }
    if (slot.importedOutput.hardwareBuffer == outputBuffer &&
        slot.importedOutput.image != VK_NULL_HANDLE && slot.importedOutput.view != VK_NULL_HANDLE &&
        slot.importedOutput.width == expectedWidth && slot.importedOutput.height == expectedHeight) {
        return true;
    }
    destroyImportedOutputLocked(device, slot.importedOutput);

    auto getAhbProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getAhbProperties == nullptr) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_VULKAN_EXTENSION_UNAVAILABLE";
        return false;
    }
    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &formatProps;
    const VkResult propsResult = getAhbProperties(device, outputBuffer, &ahbProps);
    if (propsResult != VK_SUCCESS) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_PROPERTIES_FAILED_" + std::to_string(propsResult);
        return false;
    }
    if (formatProps.format != VK_FORMAT_R8G8B8A8_UNORM ||
        (formatProps.formatFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0u) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_NOT_RGBA8_STORAGE_IMAGE";
        return false;
    }

    VkPhysicalDeviceExternalImageFormatInfo externalQuery{};
    externalQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalQuery.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkPhysicalDeviceImageFormatInfo2 imageFormatQuery{};
    imageFormatQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    imageFormatQuery.pNext = &externalQuery;
    imageFormatQuery.format = formatProps.format;
    imageFormatQuery.type = VK_IMAGE_TYPE_2D;
    imageFormatQuery.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageFormatQuery.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageFormatQuery.flags = 0u;
    VkExternalImageFormatProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    VkImageFormatProperties2 imageFormatProperties{};
    imageFormatProperties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    imageFormatProperties.pNext = &externalProperties;
    const VkResult externalFormatResult = vkGetPhysicalDeviceImageFormatProperties2(
            physicalDevice, &imageFormatQuery, &imageFormatProperties);
    if (externalFormatResult != VK_SUCCESS ||
        (externalProperties.externalMemoryProperties.externalMemoryFeatures &
         VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0u) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_EXTERNAL_IMAGE_NOT_IMPORTABLE";
        return false;
    }

    VkExternalMemoryImageCreateInfo externalMemory{};
    externalMemory.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalMemory.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalMemory;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = formatProps.format;
    imageInfo.extent = {expectedWidth, expectedHeight, 1u};
    imageInfo.mipLevels = 1u;
    imageInfo.arrayLayers = 1u;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage image = VK_NULL_HANDLE;
    const VkResult imageResult = vkCreateImage(device, &imageInfo, nullptr, &image);
    if (imageResult != VK_SUCCESS) {
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_IMAGE_CREATE_FAILED_" + std::to_string(imageResult);
        return false;
    }

    VkMemoryRequirements imageMemoryRequirements{};
    vkGetImageMemoryRequirements(device, image, &imageMemoryRequirements);
    const std::uint32_t compatibleMemoryTypes =
            ahbProps.memoryTypeBits & imageMemoryRequirements.memoryTypeBits;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    std::uint32_t memoryTypeIndex = UINT32_MAX;
    for (std::uint32_t index = 0u; index < memoryProperties.memoryTypeCount; ++index) {
        if ((compatibleMemoryTypes & (1u << index)) != 0u) {
            memoryTypeIndex = index;
            break;
        }
    }
    if (memoryTypeIndex == UINT32_MAX) {
        vkDestroyImage(device, image, nullptr);
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_MEMORY_TYPE_MISSING";
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = outputBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = image;
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.pNext = &dedicatedInfo;
    allocateInfo.allocationSize = ahbProps.allocationSize;
    allocateInfo.memoryTypeIndex = memoryTypeIndex;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    const VkResult allocationResult = vkAllocateMemory(device, &allocateInfo, nullptr, &memory);
    if (allocationResult != VK_SUCCESS) {
        vkDestroyImage(device, image, nullptr);
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_MEMORY_IMPORT_FAILED_" + std::to_string(allocationResult);
        return false;
    }
    const VkResult bindResult = vkBindImageMemory(device, image, memory, 0u);
    if (bindResult != VK_SUCCESS) {
        vkFreeMemory(device, memory, nullptr);
        vkDestroyImage(device, image, nullptr);
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_BIND_FAILED_" + std::to_string(bindResult);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = formatProps.format;
    viewInfo.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                           VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0u;
    viewInfo.subresourceRange.levelCount = 1u;
    viewInfo.subresourceRange.baseArrayLayer = 0u;
    viewInfo.subresourceRange.layerCount = 1u;
    VkImageView view = VK_NULL_HANDLE;
    const VkResult viewResult = vkCreateImageView(device, &viewInfo, nullptr, &view);
    if (viewResult != VK_SUCCESS) {
        vkFreeMemory(device, memory, nullptr);
        vkDestroyImage(device, image, nullptr);
        failureReason = "RAW_PREVIEW_OUTPUT_AHB_VIEW_FAILED_" + std::to_string(viewResult);
        return false;
    }

    AHardwareBuffer_acquire(outputBuffer);
    slot.importedOutput.hardwareBuffer = outputBuffer;
    slot.importedOutput.image = image;
    slot.importedOutput.memory = memory;
    slot.importedOutput.view = view;
    slot.importedOutput.format = formatProps.format;
    slot.importedOutput.width = expectedWidth;
    slot.importedOutput.height = expectedHeight;
    slot.importedOutput.initializedForShaderWrite = false;
    return true;
}

bool VulkanRawPreviewBackend::initializeLocked(
        VkDevice device, VkCommandPool commandPool, RawPreviewGpuResult& diagnostics,
        std::string& failureReason, bool useSharedPipelineCache) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "RAW_PREVIEW_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_RAW_PREVIEW_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RAW_PREVIEW_SHADER_NOT_COMPILED";
    return false;
#else
    const auto spirvStarted = Clock::now();
    const auto& spirv = getRawPreviewSpirv();
    diagnostics.spirvLookupMs = elapsedMs(spirvStarted);
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RAW_PREVIEW_INITIALIZATION_INPUT_INVALID";
        return false;
    }
    const auto descriptorLayoutStarted = Clock::now();
    VkDescriptorSetLayoutBinding bindings[6]{};
    for (std::uint32_t index = 0u; index < 6u; ++index) {
        bindings[index].binding = index;
        bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[index].descriptorCount = 1u;
        bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 6u;
    descriptorInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_raw_preview_failed";
        destroyLocked(device); return false;
    }

#if BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE
    VkDescriptorSetLayoutBinding imageBindings[6]{};
    imageBindings[0] = bindings[0];
    imageBindings[1].binding = 1u;
    imageBindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageBindings[1].descriptorCount = 1u;
    imageBindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    imageBindings[2] = bindings[2];
    imageBindings[3] = bindings[3];
    imageBindings[4] = bindings[4];
    imageBindings[5] = bindings[5];
    VkDescriptorSetLayoutCreateInfo imageDescriptorInfo{};
    imageDescriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    imageDescriptorInfo.bindingCount = 6u;
    imageDescriptorInfo.pBindings = imageBindings;
    if (vkCreateDescriptorSetLayout(device, &imageDescriptorInfo, nullptr, &imageDescriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_raw_preview_image_failed";
        destroyLocked(device); return false;
    }
#endif
    diagnostics.descriptorLayoutMs = elapsedMs(descriptorLayoutStarted);

    const auto pipelineLayoutStarted = Clock::now();
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_raw_preview_failed";
        destroyLocked(device); return false;
    }
#if BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE
    layoutInfo.pSetLayouts = &imageDescriptorSetLayout_;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &imagePipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_raw_preview_image_failed";
        destroyLocked(device); return false;
    }
#endif
    diagnostics.pipelineLayoutMs = elapsedMs(pipelineLayoutStarted);

    const auto shaderModuleStarted = Clock::now();
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_raw_preview_failed";
        destroyLocked(device); return false;
    }
    diagnostics.shaderModuleMs = elapsedMs(shaderModuleStarted);
    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shaderModule_;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stage;
#if BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE
    const auto imageSpirvStarted = Clock::now();
    const auto& imageSpirv = getRawPreviewImageSpirv();
    diagnostics.imageSpirvLookupMs = elapsedMs(imageSpirvStarted);
    if (!imageSpirv.empty()) {
        shaderInfo.codeSize = imageSpirv.size() * sizeof(std::uint32_t);
        shaderInfo.pCode = imageSpirv.data();
        const auto imageShaderModuleStarted = Clock::now();
        if (vkCreateShaderModule(device, &shaderInfo, nullptr, &imageShaderModule_) != VK_SUCCESS) {
            failureReason = "vkCreateShaderModule_raw_preview_image_failed";
            destroyLocked(device); return false;
        }
        diagnostics.imageShaderModuleMs = elapsedMs(imageShaderModuleStarted);
        stage.module = imageShaderModule_;
        pipelineInfo.stage = stage;
        pipelineInfo.layout = imagePipelineLayout_;
        float imageCacheMutexWaitMs = 0.0f;
        bool imageCachePresent = false;
        float imageComputePipelineCallMs = 0.0f;
        const VkResult imageComputePipelineResult = VulkanPipelineCacheRegistry::createComputePipelines(
                device, 1u, &pipelineInfo, nullptr, &imagePipeline_,
                &imageCacheMutexWaitMs, &imageCachePresent, &imageComputePipelineCallMs,
                useSharedPipelineCache);
        diagnostics.imageComputePipelineMs = imageComputePipelineCallMs;
        diagnostics.pipelineCacheMutexWaitMs += imageCacheMutexWaitMs;
        diagnostics.pipelineCachePresent = diagnostics.pipelineCachePresent || imageCachePresent;
        if (imageComputePipelineResult != VK_SUCCESS) {
            failureReason = "vkCreateComputePipelines_raw_preview_image_failed";
            destroyLocked(device); return false;
        }
    }
#endif

    // The GPU-resident AHardwareBuffer image path is the normal production preview path. Do not
    // spend several seconds compiling the CPU-visible legacy fallback before it is actually needed.
    // On devices without the image shader, keep the legacy pipeline as the initialization path.
    if (imagePipeline_ == VK_NULL_HANDLE &&
        !ensureLegacyPipelineLocked(device, diagnostics, failureReason, useSharedPipelineCache)) {
        destroyLocked(device);
        return false;
    }

    if (useSharedPipelineCache) {
        // Persist only pipelines that were actually created against the shared cache.
        VulkanPipelineCacheRegistry::persist(device);
    }

    const auto descriptorCommandResourcesStarted = Clock::now();
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    // Legacy sets: 6 storage buffers each. Image sets: input + statistics + tone + local base + HSM = 5.
    poolSizes[0].descriptorCount = 11u * RAW_PREVIEW_FRAMES_IN_FLIGHT;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = RAW_PREVIEW_FRAMES_IN_FLIGHT;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 2u * RAW_PREVIEW_FRAMES_IN_FLIGHT;
    poolInfo.poolSizeCount = imagePipeline_ != VK_NULL_HANDLE ? 2u : 1u;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_raw_preview_failed";
        destroyLocked(device); return false;
    }

    for (std::uint32_t i = 0u; i < RAW_PREVIEW_FRAMES_IN_FLIGHT; ++i) {
        VkDescriptorSetAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocateInfo.descriptorPool = descriptorPool_;
        allocateInfo.descriptorSetCount = 1u;
        allocateInfo.pSetLayouts = &descriptorSetLayout_;
        if (vkAllocateDescriptorSets(device, &allocateInfo, &slots_[i].descriptorSet) != VK_SUCCESS) {
            failureReason = "vkAllocateDescriptorSets_raw_preview_failed";
            destroyLocked(device); return false;
        }
        if (imagePipeline_ != VK_NULL_HANDLE) {
            allocateInfo.pSetLayouts = &imageDescriptorSetLayout_;
            if (vkAllocateDescriptorSets(device, &allocateInfo, &slots_[i].imageDescriptorSet) != VK_SUCCESS) {
                failureReason = "vkAllocateDescriptorSets_raw_preview_image_failed";
                destroyLocked(device); return false;
            }
        }

        VkCommandBufferAllocateInfo commandInfo{};
        commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        commandInfo.commandPool = commandPool;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = 1u;
        if (vkAllocateCommandBuffers(device, &commandInfo, &slots_[i].commandBuffer) != VK_SUCCESS) {
            failureReason = "vkAllocateCommandBuffers_raw_preview_failed";
            destroyLocked(device); return false;
        }

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(device, &fenceInfo, nullptr, &slots_[i].fence) != VK_SUCCESS) {
            failureReason = "vkCreateFence_raw_preview_failed";
            destroyLocked(device); return false;
        }
        slots_[i].fenceSubmitted = false;
    }

    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 2u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) {
        queryPool_ = VK_NULL_HANDLE;
    }
    diagnostics.descriptorCommandResourcesMs = elapsedMs(descriptorCommandResourcesStarted);
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    return true;
#endif
}

bool VulkanRawPreviewBackend::ensureLegacyPipelineLocked(
        VkDevice device, RawPreviewGpuResult& diagnostics, std::string& failureReason,
        bool useSharedPipelineCache) noexcept {
    if (pipeline_ != VK_NULL_HANDLE) return true;
    if (device == VK_NULL_HANDLE || shaderModule_ == VK_NULL_HANDLE ||
        pipelineLayout_ == VK_NULL_HANDLE) {
        failureReason = "RAW_PREVIEW_LEGACY_PIPELINE_PREREQUISITES_MISSING";
        return false;
    }

    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shaderModule_;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stage;
    pipelineInfo.layout = pipelineLayout_;

    float cacheMutexWaitMs = 0.0f;
    bool cachePresent = false;
    float computePipelineCallMs = 0.0f;
    const VkResult computePipelineResult = VulkanPipelineCacheRegistry::createComputePipelines(
            device, 1u, &pipelineInfo, nullptr, &pipeline_,
            &cacheMutexWaitMs, &cachePresent, &computePipelineCallMs,
            useSharedPipelineCache);
    diagnostics.computePipelineMs += computePipelineCallMs;
    diagnostics.pipelineCacheMutexWaitMs += cacheMutexWaitMs;
    diagnostics.pipelineCachePresent = diagnostics.pipelineCachePresent || cachePresent;
    if (computePipelineResult != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_raw_preview_failed";
        return false;
    }
    if (useSharedPipelineCache) VulkanPipelineCacheRegistry::persist(device);
    return true;
}

bool VulkanRawPreviewBackend::prepare(VkDevice device, VkCommandPool commandPool) noexcept {
    RawPreviewGpuResult diagnostics{};
    std::string failureReason;
    std::lock_guard<std::mutex> lock(mutex_);
    return initializeLocked(device, commandPool, diagnostics, failureReason, true);
}

RawPreviewGpuResult VulkanRawPreviewBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        std::uint32_t computeQueueFamilyIndex,
        bool foreignQueueFamilyEnabled,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        std::mutex* queueSubmissionMutex,
        const RawPreviewGpuRequest& request) noexcept {
    RawPreviewGpuResult result{};
    result.attempted = true;
    const auto totalStarted = Clock::now();
    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.previewWidth) * request.previewHeight;
    const std::uint64_t rgbaBytes = pixelCount * 4u;
    const bool hasLegacyOutput = request.outputRgba != nullptr && request.outputCapacityBytes >= rgbaBytes;
    const bool hasGpuResidentOutput = request.outputHardwareBuffer != nullptr;
    if ((request.inputHardwareBuffer == nullptr && request.rawData == nullptr) ||
        request.sourceWidth == 0u || request.sourceHeight == 0u ||
        request.sourceRowStrideBytes == 0u || request.previewWidth == 0u ||
        request.previewHeight == 0u || request.cfaCellDecimation == 0u ||
        request.toneLut == nullptr || request.toneLutSize != 4096u ||
        (!hasLegacyOutput && !hasGpuResidentOutput) || device == VK_NULL_HANDLE ||
        computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.failureReason = "INVALID_RAW_PREVIEW_REQUEST";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_RAW_PREVIEW_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner;
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE
            ? "VMA_HEADER_NOT_AVAILABLE" : "RAW_PREVIEW_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    const auto backendMutexWaitStarted = Clock::now();
    std::lock_guard<std::mutex> lock(mutex_);
    result.backendMutexWaitMs = elapsedMs(backendMutexWaitStarted);
    std::string failure;
    const bool backendWasInitialized = initialized_;
    const auto backendInitializationStarted = Clock::now();
    if (!initializeLocked(device, commandPool, result, failure, true)) {
        result.backendInitializationMs = elapsedMs(backendInitializationStarted);
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.backendInitializationMs = elapsedMs(backendInitializationStarted);
    result.backendInitializationPerformed = !backendWasInitialized;
    VmaAllocator allocator = allocatorOwner.handle();
    if (allocator == nullptr) {
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    allocator_ = allocator;

    const std::uint32_t slotIdx = request.frameSlotIndex < RAW_PREVIEW_FRAMES_IN_FLIGHT
            ? request.frameSlotIndex
            : currentFrameSlot_;
    currentFrameSlot_ = (slotIdx + 1u) % RAW_PREVIEW_FRAMES_IN_FLIGHT;
    FrameSlot& slot = slots_[slotIdx];
    result.activeSlotIndex = slotIdx;

    // Check non-blocking GPU fence status for this 3-frames-in-flight slot
    if (slot.fenceSubmitted) {
        const VkResult status = vkGetFenceStatus(device, slot.fence);
        const VkResult waitRes = (status == VK_SUCCESS) ? VK_SUCCESS : vkWaitForFences(device, 1u, &slot.fence, VK_TRUE, 2'000'000ull);
        if (waitRes == VK_SUCCESS) {
            slot.fenceSubmitted = false;
            destroyImportedInputLocked(device, slot.importedInput);
            if (slot.boundHardwareBuffer != nullptr) {
                AHardwareBuffer_release(slot.boundHardwareBuffer);
                gPreviewReleaseCount.fetch_add(1u, std::memory_order_relaxed);
                gReleaseAfterGpuCompletionCount.fetch_add(1u, std::memory_order_relaxed);
                slot.boundHardwareBuffer = nullptr;
            }
        } else {
            result.droppedBusy = true;
            result.failureReason = "GPU_SLOT_BUSY_DROPPED";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
    }

    const std::uint64_t rawBytes = static_cast<std::uint64_t>(request.sourceRowStrideBytes) * request.sourceHeight;
    const std::uint64_t inputBytes = (rawBytes + 3u) & ~std::uint64_t{3u};
    constexpr std::uint64_t toneLutBytes = 4096u * sizeof(float);
    const bool hueSatData1Valid = request.calibratedHueSatMapEnabled &&
            hueSatTableValid(request, request.hueSatData1, request.hueSatData1FloatCount);
    const bool hueSatSecondRequested = request.hueSatData2 != nullptr ||
            request.hueSatData2FloatCount != 0u || request.hueSatWeightSecond > 1.0e-6f;
    const bool hueSatData2Valid = !hueSatSecondRequested ||
            hueSatTableValid(request, request.hueSatData2, request.hueSatData2FloatCount);
    const float hueSatWeightFirst = std::clamp(request.hueSatWeightFirst, 0.0f, 1.0f);
    const float hueSatWeightSecond = std::clamp(request.hueSatWeightSecond, 0.0f, 1.0f);
    const bool hueSatWeightsValid = std::isfinite(request.hueSatWeightFirst) &&
            std::isfinite(request.hueSatWeightSecond) &&
            std::abs((hueSatWeightFirst + hueSatWeightSecond) - 1.0f) <= 1.0e-3f;
    const bool hueSatMapContractValid = request.calibratedHueSatMapEnabled && hueSatData1Valid &&
            hueSatData2Valid && hueSatWeightsValid;
    const std::uint64_t hueSatEntryCount = hueSatMapContractValid
            ? static_cast<std::uint64_t>(request.hueSatHueDivisions) *
                    request.hueSatSaturationDivisions * request.hueSatValueDivisions
            : 0u;
    const std::uint64_t hueSatTableFloats = hueSatEntryCount * 3u;
    const std::uint64_t hueSatProfileFloats = kHueSatHeaderFloats + hueSatTableFloats +
            (hueSatSecondRequested && hueSatMapContractValid ? hueSatTableFloats : 0u);
    const std::uint64_t hueSatProfileBytes = std::max<std::uint64_t>(
            kHueSatHeaderFloats * sizeof(float), hueSatProfileFloats * sizeof(float));
    const std::uint32_t analysisWidth = (request.previewWidth / 4u) & ~1u;
    const std::uint32_t analysisHeight = (request.previewHeight / 4u) & ~1u;
    const std::uint64_t analysisPixels = static_cast<std::uint64_t>(analysisWidth) * analysisHeight;
    const std::uint64_t analysisNv21Bytes = analysisPixels + analysisPixels / 2u;
    constexpr std::uint32_t kPreviewLocalToneDecimation = 8u;
    const std::uint32_t localToneMapWidth =
            (request.previewWidth + kPreviewLocalToneDecimation - 1u) / kPreviewLocalToneDecimation;
    const std::uint32_t localToneMapHeight =
            (request.previewHeight + kPreviewLocalToneDecimation - 1u) / kPreviewLocalToneDecimation;
    const std::uint64_t localToneMapBytes = static_cast<std::uint64_t>(localToneMapWidth) *
            localToneMapHeight * sizeof(float);
    const bool compactAnalysisRequested = request.analysisNv21 != nullptr && analysisPixels > 0u &&
            request.analysisNv21CapacityBytes >= analysisNv21Bytes;
    const std::uint64_t statisticsWords = PREVIEW_ANALYSIS_NV21_START_WORD +
            (compactAnalysisRequested ? analysisNv21Bytes : 0u);
    const std::uint64_t statisticsBytes = statisticsWords * sizeof(std::uint32_t);
    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;

    std::string inputImportFailure;
    const auto inputAhbProbeStarted = Clock::now();
    const bool directHardwareInput = tryImportInputBufferLocked(
            physicalDevice, device, foreignQueueFamilyEnabled, slot,
            request.inputHardwareBuffer, static_cast<VkDeviceSize>(inputBytes),
            result, inputImportFailure);
    result.inputAhbProbeMs = elapsedMs(inputAhbProbeStarted);
    const auto releaseUnsubmittedDirectInput = [&]() {
        if (directHardwareInput && !slot.fenceSubmitted) {
            destroyImportedInputLocked(device, slot.importedInput);
        }
    };

    bool directHostInput = false;
    if (!directHardwareInput) {
        if (!ensureBufferLocked(allocator, inputBytes, writeAccess, inputStaging_, reallocated, failure)) {
            result.failureReason = failure;
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        const VkMemoryPropertyFlags directInputRequired =
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        directHostInput =
                (inputStaging_.memoryProperties & directInputRequired) == directInputRequired;
        if (!directHostInput &&
            !ensureBufferLocked(allocator, inputBytes, 0u, deviceInput_, reallocated, failure)) {
            result.failureReason = failure;
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        if (result.inputInteropStatus == 0u) result.inputInteropStatus = 5u;
    }
    result.directHostInputUsed = !directHardwareInput && directHostInput;
    if (!ensureBufferLocked(allocator, toneLutBytes, writeAccess, toneLutBuffer_, reallocated, failure)) {
        releaseUnsubmittedDirectInput();
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (!ensureBufferLocked(allocator, statisticsBytes, 0u, deviceStatistics_, reallocated, failure)) {
        releaseUnsubmittedDirectInput();
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (!ensureBufferLocked(allocator, localToneMapBytes, 0u, localToneBase_, reallocated, failure)) {
        releaseUnsubmittedDirectInput();
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (!ensureBufferLocked(allocator, hueSatProfileBytes, writeAccess, hueSatProfile_, reallocated, failure)) {
        releaseUnsubmittedDirectInput();
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    bool gpuResidentOutput = false;
    if (hasGpuResidentOutput && imagePipeline_ != VK_NULL_HANDLE && slot.imageDescriptorSet != VK_NULL_HANDLE) {
        std::string outputFailure;
        const auto outputAhbImportStarted = Clock::now();
        gpuResidentOutput = ensureImportedOutputLocked(
                physicalDevice, device, slot, request.outputHardwareBuffer,
                request.previewWidth, request.previewHeight, outputFailure);
        result.outputAhbImportMs = elapsedMs(outputAhbImportStarted);
        if (!gpuResidentOutput && !hasLegacyOutput) {
            releaseUnsubmittedDirectInput();
            result.failureReason = outputFailure;
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
    }
    result.gpuResidentOutputUsed = gpuResidentOutput;

    // The normal GPU-resident output path never needs the CPU-visible legacy pipeline. Compile
    // that fallback only after an actual AHB/EGL interop miss instead of charging every cold RAW
    // activation several seconds for a pipeline that is not used on the healthy path.
    if (!gpuResidentOutput && pipeline_ == VK_NULL_HANDLE) {
        if (!ensureLegacyPipelineLocked(device, result, failure, true)) {
            releaseUnsubmittedDirectInput();
            result.failureReason = failure;
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
    }

    const std::uint64_t readbackBytes = gpuResidentOutput ? statisticsBytes : rgbaBytes + statisticsBytes;
    if ((!gpuResidentOutput &&
         !ensureBufferLocked(allocator, rgbaBytes, 0u, slot.deviceOutput, reallocated, failure)) ||
        !ensureBufferLocked(allocator, readbackBytes, readAccess, slot.outputReadback, reallocated, failure)) {
        releaseUnsubmittedDirectInput();
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.persistentBufferReuseHit = !reallocated;

    const VkBuffer inputBuffer = directHardwareInput ? slot.importedInput.buffer :
            (directHostInput ? inputStaging_.buffer : deviceInput_.buffer);
    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = inputBuffer;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo statisticsInfo{};
    statisticsInfo.buffer = deviceStatistics_.buffer;
    statisticsInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo toneInfo{};
    toneInfo.buffer = toneLutBuffer_.buffer;
    toneInfo.range = static_cast<VkDeviceSize>(toneLutBytes);
    VkDescriptorBufferInfo localToneInfo{};
    localToneInfo.buffer = localToneBase_.buffer;
    localToneInfo.range = static_cast<VkDeviceSize>(localToneMapBytes);
    VkDescriptorBufferInfo hueSatInfo{};
    hueSatInfo.buffer = hueSatProfile_.buffer;
    hueSatInfo.range = static_cast<VkDeviceSize>(hueSatProfileBytes);

    VkPipeline activePipeline = pipeline_;
    VkPipelineLayout activePipelineLayout = pipelineLayout_;
    VkDescriptorSet activeDescriptorSet = slot.descriptorSet;
    if (gpuResidentOutput) {
        VkDescriptorImageInfo outputImageInfo{};
        outputImageInfo.imageView = slot.importedOutput.view;
        outputImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet writes[6]{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = slot.imageDescriptorSet;
        writes[0].dstBinding = 0u;
        writes[0].descriptorCount = 1u;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[0].pBufferInfo = &inputInfo;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = slot.imageDescriptorSet;
        writes[1].dstBinding = 1u;
        writes[1].descriptorCount = 1u;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &outputImageInfo;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = slot.imageDescriptorSet;
        writes[2].dstBinding = 2u;
        writes[2].descriptorCount = 1u;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[2].pBufferInfo = &statisticsInfo;
        writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[3].dstSet = slot.imageDescriptorSet;
        writes[3].dstBinding = 3u;
        writes[3].descriptorCount = 1u;
        writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[3].pBufferInfo = &toneInfo;
        writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[4].dstSet = slot.imageDescriptorSet;
        writes[4].dstBinding = 4u;
        writes[4].descriptorCount = 1u;
        writes[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[4].pBufferInfo = &localToneInfo;
        writes[5].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[5].dstSet = slot.imageDescriptorSet;
        writes[5].dstBinding = 5u;
        writes[5].descriptorCount = 1u;
        writes[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[5].pBufferInfo = &hueSatInfo;
        vkUpdateDescriptorSets(device, 6u, writes, 0u, nullptr);
        activePipeline = imagePipeline_;
        activePipelineLayout = imagePipelineLayout_;
        activeDescriptorSet = slot.imageDescriptorSet;
    } else {
        VkDescriptorBufferInfo outputInfo{};
        outputInfo.buffer = slot.deviceOutput.buffer;
        outputInfo.range = VK_WHOLE_SIZE;
        VkDescriptorBufferInfo infos[6]{inputInfo, outputInfo, statisticsInfo, toneInfo, localToneInfo, hueSatInfo};
        VkWriteDescriptorSet writes[6]{};
        for (std::uint32_t index = 0u; index < 6u; ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = slot.descriptorSet;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1u;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[index].pBufferInfo = &infos[index];
        }
        vkUpdateDescriptorSets(device, 6u, writes, 0u, nullptr);
    }

    const auto packingStarted = Clock::now();
    if (!directHardwareInput) {
        const std::uint8_t* rawSource = request.rawData;
        void* lockedAddress = nullptr;
        AHardwareBuffer_Planes lockedPlanes{};
        bool inputLocked = false;
        if (rawSource == nullptr) {
            if (request.inputHardwareBuffer == nullptr) {
                result.failureReason = "RAW_PREVIEW_INPUT_STAGING_BUFFER_MISSING";
                result.totalMs = elapsedMs(totalStarted);
                return result;
            }

            // Prefer lockPlanes on API 29+ because it reports the mapper's actual row/pixel
            // layout. Camera2's Image plane layout is the shader contract. If the mapper reports
            // a different layout, do not silently memcpy bytes using the wrong row stride: that
            // can turn padding/optical-black memory into a visible RAW corruption block.
            const int planesStatus = AHardwareBuffer_lockPlanes(
                    request.inputHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                    -1, nullptr, &lockedPlanes);
            if (planesStatus == 0 && lockedPlanes.planeCount == 1u &&
                lockedPlanes.planes[0].data != nullptr) {
                inputLocked = true;
                const std::uint32_t mappedRowStride =
                        static_cast<std::uint32_t>(lockedPlanes.planes[0].rowStride);
                const std::uint32_t mappedPixelStride =
                        static_cast<std::uint32_t>(lockedPlanes.planes[0].pixelStride);
                if (mappedRowStride > 0u) {
                    const_cast<RawPreviewGpuRequest&>(request).sourceRowStrideBytes = mappedRowStride;
                }
                // RAW_SENSOR is byte-addressed per pixel. RAW10 is packed and Camera2 reports a
                // pixelStride of zero, so only validate pixel stride for non-packed RAW input.
                if (request.sourceFormat == 32u && request.sourcePixelStrideBytes > 0u &&
                    mappedPixelStride > 0u &&
                    mappedPixelStride != request.sourcePixelStrideBytes) {
                    AHardwareBuffer_unlock(request.inputHardwareBuffer, nullptr);
                    result.failureReason = "RAW_PREVIEW_INPUT_PLANE_PIXEL_STRIDE_MISMATCH";
                    result.totalMs = elapsedMs(totalStarted);
                    return result;
                }
                rawSource = static_cast<const std::uint8_t*>(lockedPlanes.planes[0].data);
            } else {
                // Some vendor RAW mappers do not expose lockPlanes even though the same CPU-read
                // AHB can be flat-locked. Preserve that existing byte-staging compatibility, but
                // never reinterpret the buffer as a different image layout.
                if (planesStatus == 0) {
                    AHardwareBuffer_unlock(request.inputHardwareBuffer, nullptr);
                }
                if (AHardwareBuffer_lock(
                            request.inputHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                            -1, nullptr, &lockedAddress) != 0 || lockedAddress == nullptr) {
                    result.failureReason = "RAW_PREVIEW_INPUT_STAGING_LOCK_FAILED";
                    result.totalMs = elapsedMs(totalStarted);
                    return result;
                }
                inputLocked = true;
                rawSource = static_cast<const std::uint8_t*>(lockedAddress);
            }
        }
        std::memcpy(inputStaging_.mapped, rawSource, static_cast<std::size_t>(rawBytes));
        if (inputLocked) AHardwareBuffer_unlock(request.inputHardwareBuffer, nullptr);
        vmaFlushAllocation(allocator, inputStaging_.allocation, 0u, static_cast<VkDeviceSize>(inputBytes));
        if (result.inputInteropStatus == 0u) result.inputInteropStatus = 5u;
    }
    std::memcpy(toneLutBuffer_.mapped, request.toneLut, static_cast<std::size_t>(toneLutBytes));
    vmaFlushAllocation(allocator, toneLutBuffer_.allocation, 0u, static_cast<VkDeviceSize>(toneLutBytes));

    // Same 16-float header + dense table ABI as the capture colour backend. Invalid/missing
    // profile data produces an all-zero disabled header rather than a preview-specific fallback.
    float* hueSatPacked = static_cast<float*>(hueSatProfile_.mapped);
    std::fill(hueSatPacked,
              hueSatPacked + static_cast<std::ptrdiff_t>(hueSatProfileBytes / sizeof(float)), 0.0f);
    hueSatPacked[0] = hueSatMapContractValid ? 1.0f : 0.0f;
    if (hueSatMapContractValid) {
        hueSatPacked[1] = static_cast<float>(request.hueSatHueDivisions);
        hueSatPacked[2] = static_cast<float>(request.hueSatSaturationDivisions);
        hueSatPacked[3] = static_cast<float>(request.hueSatValueDivisions);
        hueSatPacked[4] = static_cast<float>(request.hueSatEncoding);
        hueSatPacked[5] = hueSatWeightFirst;
        hueSatPacked[6] = hueSatWeightSecond;
        hueSatPacked[7] = hueSatSecondRequested ? 1.0f : 0.0f;
        hueSatPacked[8] = static_cast<float>(hueSatEntryCount);
        std::memcpy(hueSatPacked + kHueSatHeaderFloats, request.hueSatData1,
                    static_cast<std::size_t>(hueSatTableFloats) * sizeof(float));
        if (hueSatSecondRequested) {
            std::memcpy(hueSatPacked + kHueSatHeaderFloats + hueSatTableFloats, request.hueSatData2,
                        static_cast<std::size_t>(hueSatTableFloats) * sizeof(float));
        }
    }
    vmaFlushAllocation(allocator, hueSatProfile_.allocation, 0u,
                       static_cast<VkDeviceSize>(hueSatProfileBytes));
    result.inputPackingMs = elapsedMs(packingStarted);

    vkResetFences(device, 1u, &slot.fence);
    const auto commandRecordStarted = Clock::now();
    vkResetCommandBuffer(slot.commandBuffer, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(slot.commandBuffer, &begin) != VK_SUCCESS) {
        releaseUnsubmittedDirectInput();
        result.failureReason = "vkBeginCommandBuffer_raw_preview_failed";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    if (!directHardwareInput && !directHostInput) {
        VkBufferCopy inputCopy{};
        inputCopy.size = static_cast<VkDeviceSize>(inputBytes);
        vkCmdCopyBuffer(slot.commandBuffer, inputStaging_.buffer, deviceInput_.buffer, 1u, &inputCopy);
    }
    vkCmdFillBuffer(slot.commandBuffer, deviceStatistics_.buffer, 0u,
                    static_cast<VkDeviceSize>(statisticsBytes), 0u);
    vkCmdFillBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                    static_cast<VkDeviceSize>(258u * sizeof(std::uint32_t)),
                    sizeof(std::uint32_t), 0xffffffffu);
    // Seed the two scalar inputs that must survive the per-frame statistics clear. Both are
    // metadata-sized writes; all scene analysis and tone application remain GPU-resident.
    const float seededExposureGain = std::isfinite(previousExposureGain_) && previousExposureGain_ > 0.001f
            ? previousExposureGain_ : 0.0f;
    std::uint32_t seededExposureBits = 0u;
    std::memcpy(&seededExposureBits, &seededExposureGain, sizeof(seededExposureBits));
    vkCmdUpdateBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                      static_cast<VkDeviceSize>(256u * sizeof(std::uint32_t)),
                      sizeof(seededExposureBits), &seededExposureBits);
    const float profileToneSeeds[7] = {
            std::clamp(request.profileToneExposure, -1.0f, 1.0f),
            std::clamp(request.profileToneHighlights, -1.0f, 1.0f),
            std::clamp(request.profileToneShadows, -1.0f, 1.0f),
            std::clamp(request.profileToneWhites, -1.0f, 1.0f),
            std::clamp(request.profileToneBlacks, -1.0f, 1.0f),
            std::clamp(request.profileToneContrast, -1.0f, 1.0f),
            std::clamp(request.profileLocalToneBias, -1.0f, 1.0f)};
    std::uint32_t profileToneBits[7]{};
    static_assert(sizeof(profileToneBits) == sizeof(profileToneSeeds));
    std::memcpy(profileToneBits, profileToneSeeds, sizeof(profileToneSeeds));
    vkCmdUpdateBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                      static_cast<VkDeviceSize>(552u * sizeof(std::uint32_t)),
                      sizeof(profileToneBits), profileToneBits);
    const float profileDetailSeeds[4] = {
            std::clamp(request.profileDetailAmount, 0.0f, 1.0f),
            std::clamp(request.profileDetailRadius, 0.50f, 3.00f),
            std::clamp(request.profileDetailDetail, 0.0f, 1.0f),
            std::clamp(request.profileDetailMasking, 0.0f, 1.0f)};
    std::uint32_t profileDetailBits[4]{};
    static_assert(sizeof(profileDetailBits) == sizeof(profileDetailSeeds));
    std::memcpy(profileDetailBits, profileDetailSeeds, sizeof(profileDetailSeeds));
    vkCmdUpdateBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                      static_cast<VkDeviceSize>(562u * sizeof(std::uint32_t)),
                      sizeof(profileDetailBits), profileDetailBits);
    const float profileNrSeeds[6] = {
            std::clamp(request.profileNrLuminance, 0.0f, 1.0f),
            std::clamp(request.profileNrLuminanceDetail, 0.0f, 1.0f),
            std::clamp(request.profileNrLuminanceContrast, 0.0f, 1.0f),
            std::clamp(request.profileNrColor, 0.0f, 1.0f),
            std::clamp(request.profileNrColorDetail, 0.0f, 1.0f),
            std::clamp(request.profileNrColorSmoothness, 0.0f, 1.0f)};
    std::uint32_t profileNrBits[6]{};
    static_assert(sizeof(profileNrBits) == sizeof(profileNrSeeds));
    std::memcpy(profileNrBits, profileNrSeeds, sizeof(profileNrSeeds));
    vkCmdUpdateBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                      static_cast<VkDeviceSize>(566u * sizeof(std::uint32_t)),
                      sizeof(profileNrBits), profileNrBits);
    const float physicalNoiseSeeds[3] = {
            std::isfinite(request.physicalGreenNoiseS) ? std::max(0.0f, request.physicalGreenNoiseS) : 0.0f,
            std::isfinite(request.physicalGreenNoiseO) ? std::max(0.0f, request.physicalGreenNoiseO) : 0.0f,
            std::isfinite(request.physicalNoiseConfidence)
                    ? std::clamp(request.physicalNoiseConfidence, 0.0f, 1.0f) : 0.0f};
    std::uint32_t physicalNoiseBits[3]{};
    static_assert(sizeof(physicalNoiseBits) == sizeof(physicalNoiseSeeds));
    std::memcpy(physicalNoiseBits, physicalNoiseSeeds, sizeof(physicalNoiseSeeds));
    vkCmdUpdateBuffer(slot.commandBuffer, deviceStatistics_.buffer,
                      static_cast<VkDeviceSize>(PREVIEW_PHYSICAL_NOISE_START_WORD * sizeof(std::uint32_t)),
                      sizeof(physicalNoiseBits), physicalNoiseBits);

    if (directHardwareInput) {
        VkBufferMemoryBarrier externalInputBarrier{};
        externalInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        externalInputBarrier.srcAccessMask = 0u;
        externalInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        externalInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        externalInputBarrier.dstQueueFamilyIndex = computeQueueFamilyIndex;
        externalInputBarrier.buffer = slot.importedInput.buffer;
        externalInputBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &externalInputBarrier, 0u, nullptr);
    } else if (directHostInput) {
        VkBufferMemoryBarrier hostInputBarrier{};
        hostInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        hostInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        hostInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostInputBarrier.buffer = inputStaging_.buffer;
        hostInputBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &hostInputBarrier, 0u, nullptr);
    } else {
        VkBufferMemoryBarrier deviceInputBarrier{};
        deviceInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        deviceInputBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        deviceInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        deviceInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        deviceInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        deviceInputBarrier.buffer = deviceInput_.buffer;
        deviceInputBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &deviceInputBarrier, 0u, nullptr);
    }

    VkBufferMemoryBarrier toneInputBarrier{};
    toneInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toneInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    toneInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toneInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toneInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toneInputBarrier.buffer = toneLutBuffer_.buffer;
    toneInputBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &toneInputBarrier, 0u, nullptr);

    VkBufferMemoryBarrier hueSatInputBarrier{};
    hueSatInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hueSatInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    hueSatInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    hueSatInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hueSatInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hueSatInputBarrier.buffer = hueSatProfile_.buffer;
    hueSatInputBarrier.size = static_cast<VkDeviceSize>(hueSatProfileBytes);
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &hueSatInputBarrier, 0u, nullptr);

    VkBufferMemoryBarrier statsInputBarrier{};
    statsInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    statsInputBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    statsInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    statsInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    statsInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    statsInputBarrier.buffer = deviceStatistics_.buffer;
    statsInputBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &statsInputBarrier, 0u, nullptr);

    if (gpuResidentOutput) {
        VkImageMemoryBarrier acquireOutput{};
        acquireOutput.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        acquireOutput.srcAccessMask = 0u;
        acquireOutput.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquireOutput.oldLayout = slot.importedOutput.initializedForShaderWrite
                ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
        acquireOutput.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        acquireOutput.srcQueueFamilyIndex = slot.importedOutput.initializedForShaderWrite
                ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED;
        acquireOutput.dstQueueFamilyIndex = slot.importedOutput.initializedForShaderWrite
                ? computeQueueFamilyIndex : VK_QUEUE_FAMILY_IGNORED;
        acquireOutput.image = slot.importedOutput.image;
        acquireOutput.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        acquireOutput.subresourceRange.baseMipLevel = 0u;
        acquireOutput.subresourceRange.levelCount = 1u;
        acquireOutput.subresourceRange.baseArrayLayer = 0u;
        acquireOutput.subresourceRange.layerCount = 1u;
        vkCmdPipelineBarrier(
                slot.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr,
                1u, &acquireOutput);
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(slot.commandBuffer, queryPool_, 0u, 2u);
        vkCmdWriteTimestamp(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 0u);
    }
    vkCmdBindPipeline(slot.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, activePipeline);
    vkCmdBindDescriptorSets(slot.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, activePipelineLayout,
                            0u, 1u, &activeDescriptorSet, 0u, nullptr);
    PushConstants push{};
    push.sourceWidth = request.sourceWidth;
    push.sourceHeight = request.sourceHeight;
    push.sourceRowStrideBytes = request.sourceRowStrideBytes;
    push.sourceLayout = (request.sourceFormat & 0xffffu) |
            ((request.sourcePixelStrideBytes & 0xffffu) << 16u);
    push.previewWidth = request.previewWidth;
    push.previewHeight = request.previewHeight;
    push.cfaCellDecimation = request.cfaCellDecimation;
    // raw_preview.comp currently uses one resident Malvar preview demosaic regardless of the
    // legacy demosaicMode transport. Reuse that otherwise-dead byte for a quantized Camera2
    // green-even/green-odd ratio without growing the exact 128-byte Vulkan push layout.
    const float safeGreenEvenOddRatio = std::clamp(
            std::isfinite(request.greenEvenOddRatio) ? request.greenEvenOddRatio : 1.0f,
            0.50f, 2.0f);
    const auto greenRatioByte = static_cast<std::uint32_t>(std::lround(
            ((safeGreenEvenOddRatio - 0.50f) / 1.50f) * 255.0f));
    const std::uint32_t packedGreenRatio = (greenRatioByte & 0xffu) << 16u;
    const auto focusDetailByte = static_cast<std::uint32_t>(std::lround(
            std::clamp(request.focusDetailPriority, 0.0f, 1.0f) * 255.0f));
    const std::uint32_t packedFocusDetail = (focusDetailByte & 0xffu) << 24u;
    const auto packedMode = [&](std::uint32_t mode) {
        return std::min(request.cfaPattern, 3u) | ((mode & 0xffu) << 8u) |
                packedGreenRatio | packedFocusDetail;
    };
    // Packing calibration/focus metadata preserves the exact 128-byte push-constant layout.
    push.cfaAndMode = packedMode(0u);
    std::copy(request.blackLevels.begin(), request.blackLevels.end(), push.blackLevels);
    push.whiteLevel = request.whiteLevel;
    push.captureIso = static_cast<float>(request.captureSensitivityIso);
    push.captureExposureMs = request.captureExposureTimeMs;
    push.histogramStride = compactAnalysisRequested ? -8.0f : 8.0f;
    push.wbR = request.wbRgb[0];
    push.wbG = request.wbRgb[1];
    push.wbB = request.wbRgb[2];
    const std::uint32_t packedCropLeft = std::min(request.sourceCropLeft, 0xffffu);
    const std::uint32_t packedCropTop = std::min(request.sourceCropTop, 0xffffu);
    push.sourceCropPacked = packedCropLeft | (packedCropTop << 16u);
    std::copy(request.colorMatrix.begin(), request.colorMatrix.end(), push.ccm);
    push.profileSaturation = request.profileSaturation;
    push.profileContrast = request.profileContrast;
    push.profileVibrance = request.profileVibrance;
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    constexpr std::uint32_t histogramStride = 8u;
    vkCmdDispatch(slot.commandBuffer,
                  (request.previewWidth + histogramStride * 16u - 1u) /
                          (histogramStride * 16u),
                  (request.previewHeight + histogramStride * 16u - 1u) /
                          (histogramStride * 16u), 1u);

    // Compact 64x48 pre-WB physical-AWB observer. This is twelve small workgroups in the
    // existing command buffer; it adds no queue submission, fence wait or full-frame readback.
    push.cfaAndMode = packedMode(4u);
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(slot.commandBuffer,
                  (RAW_PREVIEW_AWB_GRID_COLUMNS + 15u) / 16u,
                  (RAW_PREVIEW_AWB_GRID_ROWS + 15u) / 16u, 1u);

    VkBufferMemoryBarrier statsBarrier{};
    statsBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    statsBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    statsBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    statsBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    statsBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    statsBarrier.buffer = deviceStatistics_.buffer;
    statsBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &statsBarrier, 0u, nullptr);

    // FASE 5: derive the signed 64x48 exposure field entirely inside the existing preview
    // command buffer. There is no CPU round-trip and no additional queue submission/wait.
    push.cfaAndMode = packedMode(5u);
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(slot.commandBuffer,
                  static_cast<std::uint32_t>((PREVIEW_SPATIAL_EXPOSURE_GRID_WIDTH + 15u) / 16u),
                  static_cast<std::uint32_t>((PREVIEW_SPATIAL_EXPOSURE_GRID_HEIGHT + 15u) / 16u), 1u);
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &statsBarrier, 0u, nullptr);

    push.cfaAndMode = packedMode(6u);
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    // Exactly one 16x16 workgroup: the resolver uses workgroup-shared scene percentiles.
    vkCmdDispatch(slot.commandBuffer, 1u, 1u, 1u);
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &statsBarrier, 0u, nullptr);

    push.cfaAndMode = packedMode(1u);
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(slot.commandBuffer, 1u, 1u, 1u);
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &statsBarrier, 0u, nullptr);

    // The old preview local-tone exposure pass is intentionally not dispatched in FASE 5.
    // Spatial exposure has one owner; FLLF/local contrast is rebuilt in the later tone phase.

    push.cfaAndMode = packedMode(2u);
    vkCmdPushConstants(slot.commandBuffer, activePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(slot.commandBuffer, (request.previewWidth + 15u) / 16u,
                  (request.previewHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    }
    if (directHardwareInput) {
        VkBufferMemoryBarrier releaseInput{};
        releaseInput.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        releaseInput.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        releaseInput.dstAccessMask = 0u;
        releaseInput.srcQueueFamilyIndex = computeQueueFamilyIndex;
        releaseInput.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        releaseInput.buffer = slot.importedInput.buffer;
        releaseInput.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0u, 0u, nullptr,
                             1u, &releaseInput, 0u, nullptr);
    }
    if (gpuResidentOutput) {
        VkImageMemoryBarrier releaseOutput{};
        releaseOutput.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        releaseOutput.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        releaseOutput.dstAccessMask = 0u;
        releaseOutput.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        releaseOutput.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        releaseOutput.srcQueueFamilyIndex = computeQueueFamilyIndex;
        releaseOutput.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
        releaseOutput.image = slot.importedOutput.image;
        releaseOutput.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        releaseOutput.subresourceRange.baseMipLevel = 0u;
        releaseOutput.subresourceRange.levelCount = 1u;
        releaseOutput.subresourceRange.baseArrayLayer = 0u;
        releaseOutput.subresourceRange.layerCount = 1u;
        vkCmdPipelineBarrier(
                slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0u, 0u, nullptr, 0u, nullptr,
                1u, &releaseOutput);
        VkBufferMemoryBarrier statisticsTransfer{};
        statisticsTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        statisticsTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        statisticsTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        statisticsTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        statisticsTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        statisticsTransfer.buffer = deviceStatistics_.buffer;
        statisticsTransfer.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                             1u, &statisticsTransfer, 0u, nullptr);
        VkBufferCopy statisticsCopy{};
        statisticsCopy.size = static_cast<VkDeviceSize>(statisticsBytes);
        vkCmdCopyBuffer(slot.commandBuffer, deviceStatistics_.buffer, slot.outputReadback.buffer,
                        1u, &statisticsCopy);
    } else {
        VkBufferMemoryBarrier outputBarriers[2]{};
        for (VkBufferMemoryBarrier& barrier : outputBarriers) {
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.size = VK_WHOLE_SIZE;
        }
        outputBarriers[0].buffer = slot.deviceOutput.buffer;
        outputBarriers[1].buffer = deviceStatistics_.buffer;
        vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                             2u, outputBarriers, 0u, nullptr);
        VkBufferCopy outputCopy{};
        outputCopy.size = static_cast<VkDeviceSize>(rgbaBytes);
        vkCmdCopyBuffer(slot.commandBuffer, slot.deviceOutput.buffer, slot.outputReadback.buffer,
                        1u, &outputCopy);
        VkBufferCopy statisticsCopy{};
        statisticsCopy.srcOffset = 0u;
        statisticsCopy.dstOffset = static_cast<VkDeviceSize>(rgbaBytes);
        statisticsCopy.size = static_cast<VkDeviceSize>(statisticsBytes);
        vkCmdCopyBuffer(slot.commandBuffer, deviceStatistics_.buffer, slot.outputReadback.buffer,
                        1u, &statisticsCopy);
    }
    VkBufferMemoryBarrier hostBarrier{};
    hostBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarrier.buffer = slot.outputReadback.buffer;
    hostBarrier.size = static_cast<VkDeviceSize>(readbackBytes);
    vkCmdPipelineBarrier(slot.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         1u, &hostBarrier, 0u, nullptr);
    if (vkEndCommandBuffer(slot.commandBuffer) != VK_SUCCESS) {
        releaseUnsubmittedDirectInput();
        result.failureReason = "vkEndCommandBuffer_raw_preview_failed";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.commandRecordMs = elapsedMs(commandRecordStarted);
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &slot.commandBuffer;
    const auto submitStarted = Clock::now();
    VkResult submitRes = VK_SUCCESS;
    if (queueSubmissionMutex != nullptr) {
        const auto queueMutexWaitStarted = Clock::now();
        std::lock_guard<std::mutex> queueLock(*queueSubmissionMutex);
        result.queueMutexWaitMs = elapsedMs(queueMutexWaitStarted);
        const auto submitCallStarted = Clock::now();
        submitRes = vkQueueSubmit(computeQueue, 1u, &submit, slot.fence);
        result.queueSubmitCallMs = elapsedMs(submitCallStarted);
    } else {
        const auto submitCallStarted = Clock::now();
        submitRes = vkQueueSubmit(computeQueue, 1u, &submit, slot.fence);
        result.queueSubmitCallMs = elapsedMs(submitCallStarted);
    }
    if (submitRes != VK_SUCCESS) {
        releaseUnsubmittedDirectInput();
        result.failureReason = "vkQueueSubmit_raw_preview_failed";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    slot.fenceSubmitted = true;

    // Host readback is only valid after this exact submission completed. The former path
    // invalidated and copied slot.outputReadback immediately after vkQueueSubmit(), which is not a
    // Vulkan completion guarantee and could expose stale/partially-written RGBA to the GL thread.
    // Keep preview latency bounded: if the GPU cannot finish inside the preview budget, drop this
    // frame and let the newest pending RAW frame replace it rather than displaying undefined data.
    constexpr std::uint64_t kPreviewCompletionTimeoutNs = 20'000'000ull;
    const auto fenceWaitStarted = Clock::now();
    const VkResult completion = vkWaitForFences(
            device, 1u, &slot.fence, VK_TRUE, kPreviewCompletionTimeoutNs);
    result.fenceWaitMs = elapsedMs(fenceWaitStarted);
    result.synchronizationMs = elapsedMs(submitStarted);
    if (completion != VK_SUCCESS) {
        result.droppedBusy = completion == VK_TIMEOUT;
        result.failureReason = completion == VK_TIMEOUT
                ? "GPU_PREVIEW_COMPLETION_TIMEOUT_DROPPED"
                : "GPU_PREVIEW_COMPLETION_WAIT_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    slot.fenceSubmitted = false;
    if (directHardwareInput) destroyImportedInputLocked(device, slot.importedInput);
    if (gpuResidentOutput) {
        slot.importedOutput.initializedForShaderWrite = true;
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[2]{};
        if (vkGetQueryPoolResults(
                device, queryPool_, 0u, 2u, sizeof(timestamps), timestamps,
                sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            result.kernelMs = static_cast<float>(
                    (timestamps[1] - timestamps[0]) *
                    (static_cast<double>(properties.limits.timestampPeriod) / 1.0e6));
        }
    }
    if (!(result.kernelMs > 0.0f) || !std::isfinite(result.kernelMs)) {
        result.kernelMs = result.synchronizationMs;
    }

    const auto readbackStarted = Clock::now();
    vmaInvalidateAllocation(allocator, slot.outputReadback.allocation, 0u,
                            static_cast<VkDeviceSize>(readbackBytes));
    if (!gpuResidentOutput) {
        std::memcpy(request.outputRgba, slot.outputReadback.mapped, static_cast<std::size_t>(rgbaBytes));
    }
    const auto* statistics = reinterpret_cast<const std::uint32_t*>(
            static_cast<const std::uint8_t*>(slot.outputReadback.mapped) +
            (gpuResidentOutput ? 0u : rgbaBytes));
    float exposureGain = 1.0f;
    float sceneMidtone = 0.0f;
    std::memcpy(&exposureGain, statistics + 256u, sizeof(float));
    std::memcpy(&sceneMidtone, statistics + 257u, sizeof(float));
    const float automaticExposureGain = std::isfinite(exposureGain) ? exposureGain : 1.0f;
    previousExposureGain_ = automaticExposureGain;
    const float profileExposureMultiplier = std::exp2(
            2.0f * std::clamp(request.profileToneExposure, -1.0f, 1.0f));
    result.exposureGain = std::clamp(automaticExposureGain * profileExposureMultiplier, 0.025f, 32.0f);
    result.sceneMidtone = std::isfinite(sceneMidtone) ? sceneMidtone : 0.0f;
    auto readFloatStat = [&](std::size_t index, float fallback) {
        float value = fallback;
        std::memcpy(&value, statistics + index, sizeof(float));
        return std::isfinite(value) ? value : fallback;
    };
    result.sceneMidtoneTarget = readFloatStat(544u, 0.125f);
    result.gtmShoulderStart = readFloatStat(545u, 0.72f);
    result.gtmShoulderStrength = readFloatStat(546u, 0.90f);
    result.gtmBlackAnchor = readFloatStat(547u, 0.0065f);
    result.gtmLowerMidLift = readFloatStat(548u, 0.0f);
    result.gtmContrastStrength = readFloatStat(549u, 0.10f);
    result.gtmDynamicRangePressure = readFloatStat(550u, 0.0f);
    result.ltmStrength = readFloatStat(559u, 0.05f);
    result.ltmMaxLiftEv = readFloatStat(560u, 0.18f);
    result.ltmMaxCompressEv = readFloatStat(561u, 0.08f);
    result.normalizedRawMin = statistics[258u] == 0xffffffffu
            ? 0.0f : static_cast<float>(statistics[258u]) / 1.0e6f;
    result.normalizedRawMax = static_cast<float>(statistics[259u]) / 1.0e6f;
    result.commonHighlightScalePixels = statistics[260u];
    for (std::size_t index = 0u; index < result.linearLumaHistogram.size(); ++index) {
        result.linearLumaHistogram[index] = statistics[index];
    }
    for (std::size_t index = 0u; index < result.displayLumaHistogram.size(); ++index) {
        result.displayLumaHistogram[index] = statistics[261u + index];
    }
    for (std::size_t index = 0u; index < 64u; ++index) {
        result.displayLumaHistogram64[index] = statistics[283u + index];
        result.displayRHistogram64[index] = statistics[347u + index];
        result.displayGHistogram64[index] = statistics[411u + index];
        result.displayBHistogram64[index] = statistics[475u + index];
    }
    result.rawNearClipSampleCount = statistics[539u];
    result.rawSampleCount = statistics[540u];
    result.displayRClipSampleCount = statistics[541u];
    result.displayGClipSampleCount = statistics[542u];
    result.displayBClipSampleCount = statistics[543u];
    result.awbSampleCount = 0u;
    for (std::uint32_t index = 0u; index < RAW_PREVIEW_AWB_SAMPLE_COUNT; ++index) {
        const std::size_t base = static_cast<std::size_t>(PREVIEW_AWB_STATS_START_WORD +
                static_cast<std::uint64_t>(index) * PREVIEW_AWB_SAMPLE_WORDS);
        RawPreviewAwbSample sample{};
        std::memcpy(&sample.luma, statistics + base + 0u, sizeof(float));
        std::memcpy(&sample.redMinusGreen, statistics + base + 1u, sizeof(float));
        std::memcpy(&sample.blueMinusGreen, statistics + base + 2u, sizeof(float));
        std::memcpy(&sample.structure, statistics + base + 3u, sizeof(float));
        sample.tileIndex = statistics[base + 4u];
        sample.valid = std::isfinite(sample.luma) && sample.luma > 0.0f && sample.luma <= 1.25f &&
                std::isfinite(sample.redMinusGreen) &&
                std::isfinite(sample.blueMinusGreen) &&
                std::isfinite(sample.structure) && sample.structure >= 0.0f &&
                sample.tileIndex < 192u;
        result.awbSamples[index] = sample;
        if (sample.valid) result.awbSampleCount++;
    }
    result.exposureTileCount = statistics[PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 0u];
    result.exposureSceneP10 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 1u, 0.0f);
    result.exposureSceneP25 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 2u, 0.0f);
    result.exposureSceneP50 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 3u, 0.0f);
    result.exposureSceneP75 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 4u, 0.0f);
    result.exposureSceneP90 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 5u, 0.0f);
    result.exposureSceneP95 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 6u, 0.0f);
    result.exposureSceneP99 = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 7u, 0.0f);
    result.exposureMeasuredSceneDrEv = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 8u, 0.0f);
    result.exposureLowerNeutralBoundaryEv = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 9u, 0.0f);
    result.exposureUpperNeutralBoundaryEv = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 10u, 0.0f);
    result.exposureSpatialAuthority = readFloatStat(PREVIEW_SPATIAL_EXPOSURE_SUMMARY_START_WORD + 11u, 0.0f);
    result.displayShadowSampleCount = statistics[277u];
    result.displayHighlightSampleCount = statistics[278u];
    result.displaySampleCount = statistics[279u];
    const std::uint32_t highlightWeight = statistics[282u];
    if (highlightWeight > 0u) {
        result.displayHighlightX = request.previewWidth > 1u
                ? (static_cast<float>(statistics[280u]) / static_cast<float>(highlightWeight)) /
                        static_cast<float>(request.previewWidth - 1u)
                : 0.5f;
        result.displayHighlightY = request.previewHeight > 1u
                ? (static_cast<float>(statistics[281u]) / static_cast<float>(highlightWeight)) /
                        static_cast<float>(request.previewHeight - 1u)
                : 0.5f;
    }
    if (compactAnalysisRequested) {
        const std::uint32_t* analysis = statistics + PREVIEW_ANALYSIS_NV21_START_WORD;
        for (std::uint64_t index = 0u; index < analysisNv21Bytes; ++index) {
            request.analysisNv21[index] = static_cast<std::uint8_t>(std::min(analysis[index], 255u));
        }
        result.analysisNv21Width = analysisWidth;
        result.analysisNv21Height = analysisHeight;
    }
    result.readbackMs = elapsedMs(readbackStarted);
    result.success = true;
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#endif
}

void VulkanRawPreviewBackend::destroyLocked(VkDevice device) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (std::uint32_t i = 0u; i < RAW_PREVIEW_FRAMES_IN_FLIGHT; ++i) {
            if (slots_[i].deviceOutput.buffer != VK_NULL_HANDLE && slots_[i].deviceOutput.allocation != nullptr) {
                vmaDestroyBuffer(allocator_, slots_[i].deviceOutput.buffer, slots_[i].deviceOutput.allocation);
            }
            slots_[i].deviceOutput = {};
        }
        for (std::uint32_t i = 0u; i < RAW_PREVIEW_FRAMES_IN_FLIGHT; ++i) {
            PersistentBuffer& readback = slots_[i].outputReadback;
            if (readback.buffer != VK_NULL_HANDLE && readback.allocation != nullptr) {
                vmaDestroyBuffer(allocator_, readback.buffer, readback.allocation);
            }
            readback = {};
        }
        for (PersistentBuffer* buffer : {&inputStaging_, &deviceInput_, &toneLutBuffer_,
                                         &deviceStatistics_, &localToneBase_, &hueSatProfile_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
    if (device != VK_NULL_HANDLE) {
        for (std::uint32_t i = 0u; i < RAW_PREVIEW_FRAMES_IN_FLIGHT; ++i) {
            if (slots_[i].boundHardwareBuffer != nullptr) {
                if (slots_[i].fence != VK_NULL_HANDLE && slots_[i].fenceSubmitted) {
                    vkWaitForFences(device, 1u, &slots_[i].fence, VK_TRUE, 100'000'000ull);
                }
                AHardwareBuffer_release(slots_[i].boundHardwareBuffer);
                gPreviewReleaseCount.fetch_add(1u, std::memory_order_relaxed);
                gReleaseAfterGpuCompletionCount.fetch_add(1u, std::memory_order_relaxed);
                slots_[i].boundHardwareBuffer = nullptr;
            }
            if (slots_[i].fence != VK_NULL_HANDLE && slots_[i].fenceSubmitted) {
                vkWaitForFences(device, 1u, &slots_[i].fence, VK_TRUE, 100'000'000ull);
                slots_[i].fenceSubmitted = false;
            }
            destroyImportedInputLocked(device, slots_[i].importedInput);
            destroyImportedOutputLocked(device, slots_[i].importedOutput);
            if (slots_[i].fence != VK_NULL_HANDLE) vkDestroyFence(device, slots_[i].fence, nullptr);
            slots_[i].fence = VK_NULL_HANDLE;
            slots_[i].commandBuffer = VK_NULL_HANDLE;
            slots_[i].descriptorSet = VK_NULL_HANDLE;
            slots_[i].imageDescriptorSet = VK_NULL_HANDLE;
        }
        if (queryPool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool_, nullptr);
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (imagePipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, imagePipeline_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (imageShaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, imageShaderModule_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (imagePipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, imagePipelineLayout_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (imageDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, imageDescriptorSetLayout_, nullptr);
        }
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        }
    }
    initialized_ = false;
    initializedDevice_ = VK_NULL_HANDLE;
    initializedCommandPool_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    imageDescriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    imagePipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    imageShaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    imagePipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    queryPool_ = VK_NULL_HANDLE;
    previousExposureGain_ = 0.0f;
}

void VulkanRawPreviewBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

}  // namespace bncam::vulkan
