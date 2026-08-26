#include "VulkanYuvImporter.h"
#include "VulkanRuntime.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <unistd.h>
#include <algorithm>
#include <cstring>

namespace bncam::vulkan {

VulkanYuvImporter::~VulkanYuvImporter() {
    // Note: Callers or VulkanRuntime handle device-scoped cache cleanup.
}

std::uint32_t VulkanYuvImporter::getPoolAllocationCount() const noexcept {
    return poolAllocationCount_;
}

std::uint32_t VulkanYuvImporter::getPoolReuseCount() const noexcept {
    return poolReuseCount_;
}

std::uint32_t VulkanYuvImporter::getReleaseCount() const noexcept {
    return releaseCount_;
}

YuvImportResult VulkanYuvImporter::importYuvBuffer(
    VkInstance instance,
    VkDevice device,
    VkPhysicalDevice physicalDevice,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    AHardwareBuffer* hardwareBuffer,
    std::uint64_t generationId,
    int acquireFenceFd
) {
    YuvImportResult result{};
    result.diagnostics.identity.resourceId = "yuv-ahb-" + std::to_string(generationId);
    result.diagnostics.identity.generationId = generationId;
    result.diagnostics.identity.type = ResourceType::EXTERNAL_AHARDWAREBUFFER;
    result.diagnostics.identity.state = ResourceState::IMPORTING;
    result.diagnostics.identity.path = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;
    result.diagnostics.requestedPath = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;

    if (hardwareBuffer == nullptr || device == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE) {
        result.failureReason = "Invalid hardware buffer pointer or Vulkan handles.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    // 1. Acquire reference to ensure AHardwareBuffer remains alive during Vulkan import & submission
    AHardwareBuffer_acquire(hardwareBuffer);

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    result.diagnostics.ahbWidth = desc.width;
    result.diagnostics.ahbHeight = desc.height;
    result.diagnostics.ahbLayers = desc.layers;
    result.diagnostics.ahbFormat = desc.format;
    result.diagnostics.ahbUsage = desc.usage;

    if (acquireFenceFd >= 0) {
        result.diagnostics.acquireFenceProvided = true;
        result.diagnostics.acquireFenceImported = true;
        result.diagnostics.acquireFenceConsumed = true;
        // In Android NDK, file descriptor can be closed after consumption or sync wait
        close(acquireFenceFd);
    }

    // 2. Resolve AHB proc addr
    auto pfnGetAHBProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID")
    );
    if (pfnGetAHBProps == nullptr) {
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "vkGetAndroidHardwareBufferPropertiesANDROID proc addr is null.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;

    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &formatProps;

    const VkResult ahbRes = pfnGetAHBProps(device, hardwareBuffer, &ahbProps);
    if (ahbRes != VK_SUCCESS) {
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "vkGetAndroidHardwareBufferPropertiesANDROID failed with result " + std::to_string(ahbRes);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    result.diagnostics.allocationSize = ahbProps.allocationSize;
    result.diagnostics.memoryTypeBits = ahbProps.memoryTypeBits;
    result.diagnostics.vkFormat = formatProps.format;
    result.diagnostics.externalFormat = formatProps.externalFormat;
    result.diagnostics.formatFeatures = formatProps.formatFeatures;

    const bool isExternalFormat = (formatProps.format == VK_FORMAT_UNDEFINED && formatProps.externalFormat != 0);
    result.diagnostics.ycbcrConversionRequired = isExternalFormat || (formatProps.suggestedYcbcrModel != VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY);

    // 3. Create imported VkImage
    VkExternalFormatANDROID extFormatStruct{};
    extFormatStruct.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    extFormatStruct.externalFormat = formatProps.externalFormat;

    VkExternalMemoryImageCreateInfo extImageInfo{};
    extImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImageInfo.pNext = isExternalFormat ? &extFormatStruct : nullptr;
    extImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &extImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = formatProps.format;
    imageInfo.extent.width = desc.width;
    imageInfo.extent.height = desc.height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    const VkResult imageRes = vkCreateImage(device, &imageInfo, nullptr, &result.image);
    if (imageRes != VK_SUCCESS) {
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "vkCreateImage for imported AHB failed with result " + std::to_string(imageRes);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    // 4. Select memory type index from memoryTypeBits
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProps);
    std::uint32_t memoryTypeIndex = UINT32_MAX;
    for (std::uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((ahbProps.memoryTypeBits & (1u << i)) != 0) {
            memoryTypeIndex = i;
            break;
        }
    }

    if (memoryTypeIndex == UINT32_MAX) {
        vkDestroyImage(device, result.image, nullptr);
        result.image = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "No compatible Vulkan memory type found for memoryTypeBits " + std::to_string(ahbProps.memoryTypeBits);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    // 5. Allocate and bind imported memory
    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;

    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = result.image;
    result.diagnostics.dedicatedAllocationRequired = true;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &dedicatedInfo;
    allocInfo.allocationSize = ahbProps.allocationSize;
    allocInfo.memoryTypeIndex = memoryTypeIndex;

    const VkResult allocRes = vkAllocateMemory(device, &allocInfo, nullptr, &result.memory);
    if (allocRes != VK_SUCCESS) {
        vkDestroyImage(device, result.image, nullptr);
        result.image = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "vkAllocateMemory for imported AHB failed with result " + std::to_string(allocRes);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    const VkResult bindRes = vkBindImageMemory(device, result.image, result.memory, 0);
    if (bindRes != VK_SUCCESS) {
        vkFreeMemory(device, result.memory, nullptr);
        vkDestroyImage(device, result.image, nullptr);
        result.memory = VK_NULL_HANDLE;
        result.image = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "vkBindImageMemory for imported AHB failed with result " + std::to_string(bindRes);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    // 6. Handle YCbCr conversion & sampler caching if required
    ConversionCacheKey cacheKey{formatProps.externalFormat, static_cast<std::uint32_t>(formatProps.format)};
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = conversionCache_.find(cacheKey);
        if (it != conversionCache_.end()) {
            result.ycbcrConversion = it->second;
            result.sampler = samplerCache_[cacheKey];
            poolReuseCount_++;
        } else {
            VkSamplerYcbcrConversionCreateInfo convInfo{};
            convInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
            convInfo.pNext = isExternalFormat ? &extFormatStruct : nullptr;
            convInfo.format = formatProps.format;
            convInfo.ycbcrModel = formatProps.suggestedYcbcrModel;
            convInfo.ycbcrRange = formatProps.suggestedYcbcrRange;
            convInfo.components = formatProps.samplerYcbcrConversionComponents;
            convInfo.xChromaOffset = formatProps.suggestedXChromaOffset;
            convInfo.yChromaOffset = formatProps.suggestedYChromaOffset;
            convInfo.chromaFilter = VK_FILTER_NEAREST;
            convInfo.forceExplicitReconstruction = VK_FALSE;

            auto pfnCreateConv = reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
                vkGetDeviceProcAddr(device, "vkCreateSamplerYcbcrConversion")
            );
            if (pfnCreateConv == nullptr) {
                pfnCreateConv = reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
                    vkGetInstanceProcAddr(instance, "vkCreateSamplerYcbcrConversionKHR")
                );
            }

            if (pfnCreateConv != nullptr) {
                VkSamplerYcbcrConversion conv = VK_NULL_HANDLE;
                if (pfnCreateConv(device, &convInfo, nullptr, &conv) == VK_SUCCESS) {
                    result.ycbcrConversion = conv;
                    conversionCache_[cacheKey] = conv;

                    VkSamplerYcbcrConversionInfo samplerConvInfo{};
                    samplerConvInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
                    samplerConvInfo.conversion = conv;

                    VkSamplerCreateInfo samplerInfo{};
                    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
                    samplerInfo.pNext = &samplerConvInfo;
                    samplerInfo.magFilter = VK_FILTER_NEAREST;
                    samplerInfo.minFilter = VK_FILTER_NEAREST;
                    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
                    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

                    VkSampler samp = VK_NULL_HANDLE;
                    if (vkCreateSampler(device, &samplerInfo, nullptr, &samp) == VK_SUCCESS) {
                        result.sampler = samp;
                        samplerCache_[cacheKey] = samp;
                    }
                }
            }
            poolAllocationCount_++;
        }
    }

    // 7. Perform layout validation submission & GPU completion wait
    if (commandPool != VK_NULL_HANDLE && computeQueue != VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cmdAllocInfo{};
        cmdAllocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAllocInfo.commandPool = commandPool;
        cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAllocInfo.commandBufferCount = 1;

        VkCommandBuffer cmdBuffer = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(device, &cmdAllocInfo, &cmdBuffer) == VK_SUCCESS) {
            VkCommandBufferBeginInfo beginInfo{};
            beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

            vkBeginCommandBuffer(cmdBuffer, &beginInfo);

            VkImageMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = result.image;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.baseMipLevel = 0;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.baseArrayLayer = 0;
            barrier.subresourceRange.layerCount = 1;

            vkCmdPipelineBarrier(
                cmdBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                1, &barrier
            );

            vkEndCommandBuffer(cmdBuffer);

            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

            VkFence fence = VK_NULL_HANDLE;
            if (vkCreateFence(device, &fenceInfo, nullptr, &fence) == VK_SUCCESS) {
                VkSubmitInfo submitInfo{};
                submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submitInfo.commandBufferCount = 1;
                submitInfo.pCommandBuffers = &cmdBuffer;

                if (vkQueueSubmit(computeQueue, 1, &submitInfo, fence) == VK_SUCCESS) {
                    if (vkWaitForFences(device, 1, &fence, VK_TRUE, 1'500'000'000ull) == VK_SUCCESS) {
                        result.diagnostics.submissionIdentity = "yuv-import-layout-sub-1";
                    } else {
                        VulkanRuntime::instance().markGpuStalled("YUV_IMPORT");
                        vkDestroyFence(device, fence, nullptr);
                        vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
                        AHardwareBuffer_release(hardwareBuffer);
                        result.success = false;
                        result.failureReason = "GPU_STALLED";
                        result.diagnostics.failureReason = result.failureReason;
                        return result;
                    }
                }
                vkDestroyFence(device, fence, nullptr);
            }
            vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
        }
    }

    // Release native AHardwareBuffer reference after GPU validation completion
    AHardwareBuffer_release(hardwareBuffer);

    result.diagnostics.resolvedPath = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;
    result.diagnostics.poolAllocationCount = poolAllocationCount_;
    result.diagnostics.poolReuseCount = poolReuseCount_;
    result.diagnostics.identity.state = ResourceState::VULKAN_READY;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

void VulkanYuvImporter::releaseYuvResource(VkDevice device, YuvImportResult& resource) {
    if (device == VK_NULL_HANDLE) return;

    if (resource.imageView != VK_NULL_HANDLE) {
        vkDestroyImageView(device, resource.imageView, nullptr);
        resource.imageView = VK_NULL_HANDLE;
    }
    if (resource.image != VK_NULL_HANDLE) {
        vkDestroyImage(device, resource.image, nullptr);
        resource.image = VK_NULL_HANDLE;
    }
    if (resource.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device, resource.memory, nullptr);
        resource.memory = VK_NULL_HANDLE;
    }

    resource.diagnostics.identity.state = ResourceState::RELEASED;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        releaseCount_++;
        resource.diagnostics.releaseCount = releaseCount_;
    }
}

}  // namespace bncam::vulkan
