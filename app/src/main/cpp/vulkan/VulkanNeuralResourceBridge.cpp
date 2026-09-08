#include "VulkanNeuralResourceBridge.h"

#include <algorithm>
#include <limits>
#include <type_traits>

namespace bncam::vulkan::neural {
namespace {

using bncam::spectra::neural::NeuralElementType;
using bncam::spectra::neural::NeuralExternalSyncKind;
using bncam::spectra::neural::NeuralResourceKind;
using bncam::spectra::neural::NeuralResourceView;

template <class T>
T fromToken(std::uint64_t token) noexcept {
    if constexpr (std::is_pointer_v<T>) {
        return reinterpret_cast<T>(static_cast<std::uintptr_t>(token));
    } else {
        return static_cast<T>(token);
    }
}

std::uint32_t bytesPerElement(NeuralElementType type) noexcept {
    switch (type) {
        case NeuralElementType::Fp16:
            return 2u;
        case NeuralElementType::Fp32:
        case NeuralElementType::U32:
            return 4u;
        default:
            return 0u;
    }
}

bool checkedResourceSpan(const NeuralResourceView& view, std::uint64_t& bytes) noexcept {
    const std::uint32_t elementBytes = bytesPerElement(view.elementType);
    if (elementBytes == 0u || view.width == 0u || view.height == 0u || view.channels == 0u) {
        return false;
    }
    const std::uint64_t minimumRow =
            std::uint64_t{view.width} * std::uint64_t{view.channels} * elementBytes;
    if (view.rowStrideBytes < minimumRow) {
        return false;
    }
    const std::uint64_t rows = view.height;
    if (rows > std::numeric_limits<std::uint64_t>::max() / view.rowStrideBytes) {
        return false;
    }
    bytes = rows * view.rowStrideBytes;
    return view.byteOffset <= std::numeric_limits<std::uint64_t>::max() - bytes;
}

std::uint32_t chooseMemoryType(
        VkPhysicalDevice physicalDevice,
        std::uint32_t compatibleBits) noexcept {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    for (std::uint32_t i = 0u; i < memoryProperties.memoryTypeCount; ++i) {
        if ((compatibleBits & (1u << i)) != 0u) {
            return i;
        }
    }
    return UINT32_MAX;
}

VkSemaphore borrowedWaitSemaphore(const NeuralResourceView& view) noexcept {
    if (view.externalSync.kind != NeuralExternalSyncKind::VulkanSemaphore) {
        return VK_NULL_HANDLE;
    }
    return fromToken<VkSemaphore>(view.externalSync.token);
}

} // namespace

NeuralResolvedGpuInput VulkanNeuralResourceBridge::resolveInput(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        const NeuralResourceView& primary,
        const NeuralResourceView& fallback) noexcept {
    NeuralResolvedGpuInput out{};

    if (primary.kind == NeuralResourceKind::VulkanBuffer && primary.token != 0u) {
        std::uint64_t span = 0u;
        if (!checkedResourceSpan(primary, span)) {
            out.failureReason = "NEURAL_RESIDENT_BUFFER_SPAN_INVALID";
            return out;
        }
        out.valid = true;
        out.path = NeuralGpuInputPath::ResidentVulkanBuffer;
        out.buffer = fromToken<VkBuffer>(primary.token);
        out.waitSemaphore = borrowedWaitSemaphore(primary);
        out.byteOffset = primary.byteOffset;
        out.accessibleBytes = span;
        out.failureReason = "none";
        return out;
    }

    if (primary.kind == NeuralResourceKind::AndroidHardwareBuffer && primary.token != 0u) {
        auto direct = importAhbBlob(
                physicalDevice,
                device,
                fromToken<AHardwareBuffer*>(primary.token),
                primary);
        if (direct.valid) {
            return direct;
        }

        if (fallback.kind == NeuralResourceKind::VulkanBuffer && fallback.token != 0u) {
            std::uint64_t span = 0u;
            if (!checkedResourceSpan(fallback, span)) {
                out.failureReason = "NEURAL_GPU_STAGING_FALLBACK_SPAN_INVALID";
                return out;
            }
            out.valid = true;
            out.path = NeuralGpuInputPath::GpuStagingFallback;
            out.buffer = fromToken<VkBuffer>(fallback.token);
            out.waitSemaphore = borrowedWaitSemaphore(fallback);
            out.byteOffset = fallback.byteOffset;
            out.accessibleBytes = span;
            out.failureReason = "none";
            return out;
        }

        out.failureReason = direct.failureReason.empty()
                ? "NEURAL_AHB_NOT_STORAGE_IMPORTABLE_AND_NO_GPU_FALLBACK"
                : direct.failureReason;
        return out;
    }

    if (fallback.kind == NeuralResourceKind::VulkanBuffer && fallback.token != 0u) {
        std::uint64_t span = 0u;
        if (!checkedResourceSpan(fallback, span)) {
            out.failureReason = "NEURAL_GPU_STAGING_FALLBACK_SPAN_INVALID";
            return out;
        }
        out.valid = true;
        out.path = NeuralGpuInputPath::GpuStagingFallback;
        out.buffer = fromToken<VkBuffer>(fallback.token);
        out.waitSemaphore = borrowedWaitSemaphore(fallback);
        out.byteOffset = fallback.byteOffset;
        out.accessibleBytes = span;
        out.failureReason = "none";
        return out;
    }

    out.failureReason = "NEURAL_INPUT_RESOURCE_KIND_UNSUPPORTED";
    return out;
}

NeuralResolvedGpuInput VulkanNeuralResourceBridge::importAhbBlob(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        AHardwareBuffer* ahb,
        const NeuralResourceView& view) noexcept {
    NeuralResolvedGpuInput out{};
    if (physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE || ahb == nullptr) {
        out.failureReason = "NEURAL_AHB_NULL";
        return out;
    }
    if (view.externalSync.kind == NeuralExternalSyncKind::None) {
        out.failureReason = "NEURAL_AHB_PRODUCER_SYNC_MISSING";
        return out;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_BLOB || desc.height != 1u ||
        (desc.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) == 0u) {
        out.failureReason = "NEURAL_AHB_DIRECT_IMPORT_REQUIRES_GPU_BLOB_BUFFER";
        return out;
    }

    std::uint64_t requestedSpan = 0u;
    if (!checkedResourceSpan(view, requestedSpan) ||
        view.byteOffset > desc.width || requestedSpan > desc.width - view.byteOffset) {
        out.failureReason = "NEURAL_AHB_BLOB_BOUNDS_INVALID";
        return out;
    }

    VkPhysicalDeviceExternalBufferInfo externalInfo{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO};
    externalInfo.flags = 0u;
    externalInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    externalInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkExternalBufferProperties externalProperties{
            VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES};
    vkGetPhysicalDeviceExternalBufferProperties(
            physicalDevice,
            &externalInfo,
            &externalProperties);
    const auto externalFeatures =
            externalProperties.externalMemoryProperties.externalMemoryFeatures;
    if ((externalFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0u) {
        out.failureReason = "NEURAL_AHB_EXTERNAL_BUFFER_NOT_IMPORTABLE";
        return out;
    }
    out.externalImportCapabilityVerified = true;

    auto getAhbProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getAhbProperties == nullptr) {
        out.failureReason = "NEURAL_AHB_EXTENSION_UNAVAILABLE";
        return out;
    }

    VkExternalMemoryBufferCreateInfo externalMemoryInfo{
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO};
    externalMemoryInfo.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.pNext = &externalMemoryInfo;
    bufferInfo.size = static_cast<VkDeviceSize>(desc.width);
    bufferInfo.usage = externalInfo.usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &bufferInfo, nullptr, &out.buffer) != VK_SUCCESS) {
        out.buffer = VK_NULL_HANDLE;
        out.failureReason = "NEURAL_AHB_BUFFER_CREATE_FAILED";
        return out;
    }

    VkMemoryDedicatedRequirements dedicatedRequirements{
            VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
    VkMemoryRequirements2 requirements2{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
    requirements2.pNext = &dedicatedRequirements;
    VkBufferMemoryRequirementsInfo2 requirementsInfo{
            VK_STRUCTURE_TYPE_BUFFER_MEMORY_REQUIREMENTS_INFO_2};
    requirementsInfo.buffer = out.buffer;
    vkGetBufferMemoryRequirements2(device, &requirementsInfo, &requirements2);

    VkAndroidHardwareBufferPropertiesANDROID ahbProperties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    if (getAhbProperties(device, ahb, &ahbProperties) != VK_SUCCESS) {
        vkDestroyBuffer(device, out.buffer, nullptr);
        out.buffer = VK_NULL_HANDLE;
        out.failureReason = "NEURAL_AHB_PROPERTIES_FAILED";
        return out;
    }

    const std::uint32_t memoryType = chooseMemoryType(
            physicalDevice,
            requirements2.memoryRequirements.memoryTypeBits & ahbProperties.memoryTypeBits);
    if (memoryType == UINT32_MAX) {
        vkDestroyBuffer(device, out.buffer, nullptr);
        out.buffer = VK_NULL_HANDLE;
        out.failureReason = "NEURAL_AHB_MEMORY_TYPE_UNAVAILABLE";
        return out;
    }

    VkMemoryDedicatedAllocateInfo dedicatedAllocate{
            VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicatedAllocate.buffer = out.buffer;

    VkImportAndroidHardwareBufferInfoANDROID importInfo{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    importInfo.buffer = ahb;
    // AHB imports can require dedicated allocation. Always providing the
    // dedicated buffer is valid for this one-buffer import and satisfies both
    // requiresDedicatedAllocation and prefersDedicatedAllocation devices.
    importInfo.pNext = &dedicatedAllocate;

    VkMemoryAllocateInfo allocationInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocationInfo.pNext = &importInfo;
    allocationInfo.allocationSize = ahbProperties.allocationSize;
    allocationInfo.memoryTypeIndex = memoryType;

    AHardwareBuffer_acquire(ahb);
    out.acquiredAhb = ahb;
    if (vkAllocateMemory(device, &allocationInfo, nullptr, &out.importedMemory) != VK_SUCCESS) {
        releaseImported(device, out);
        out.failureReason = "NEURAL_AHB_IMPORT_ALLOCATION_FAILED";
        return out;
    }
    if (vkBindBufferMemory(device, out.buffer, out.importedMemory, 0u) != VK_SUCCESS) {
        releaseImported(device, out);
        out.failureReason = "NEURAL_AHB_BIND_FAILED";
        return out;
    }

    out.valid = true;
    out.path = NeuralGpuInputPath::DirectAhbBlobBuffer;
    out.waitSemaphore = borrowedWaitSemaphore(view);
    out.byteOffset = view.byteOffset;
    out.accessibleBytes = requestedSpan;
    out.dedicatedAllocation = true;
    out.dedicatedAllocationRequired =
            dedicatedRequirements.requiresDedicatedAllocation == VK_TRUE;
    out.failureReason = "none";
    return out;
}

void VulkanNeuralResourceBridge::releaseImported(
        VkDevice device,
        NeuralResolvedGpuInput& input) noexcept {
    if (device != VK_NULL_HANDLE && input.buffer != VK_NULL_HANDLE &&
        input.importedMemory != VK_NULL_HANDLE) {
        vkDestroyBuffer(device, input.buffer, nullptr);
        vkFreeMemory(device, input.importedMemory, nullptr);
    }
    if (input.acquiredAhb != nullptr) {
        AHardwareBuffer_release(input.acquiredAhb);
    }
    input = {};
}

} // namespace bncam::vulkan::neural
