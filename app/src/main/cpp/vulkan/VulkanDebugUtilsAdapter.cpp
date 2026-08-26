#include "VulkanDebugUtilsAdapter.h"

#include <chrono>
#include <utility>

namespace bncam::vulkan {
namespace {
ValidationSeverity mapSeverity(VkDebugUtilsMessageSeverityFlagBitsEXT severity) {
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) return ValidationSeverity::ERROR;
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) return ValidationSeverity::WARNING;
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) return ValidationSeverity::INFO;
    return ValidationSeverity::VERBOSE;
}

ValidationMessageType mapType(VkDebugUtilsMessageTypeFlagsEXT types) {
#if defined(VK_EXT_device_address_binding_report)
    if (types & VK_DEBUG_UTILS_MESSAGE_TYPE_DEVICE_ADDRESS_BINDING_BIT_EXT) {
        return ValidationMessageType::DEVICE_ADDRESS_BINDING;
    }
#endif
    if (types & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) return ValidationMessageType::VALIDATION;
    if (types & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) return ValidationMessageType::PERFORMANCE;
    if (types & VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT) return ValidationMessageType::GENERAL;
    return ValidationMessageType::UNKNOWN;
}
}  // namespace

VKAPI_ATTR VkBool32 VKAPI_CALL debugUtilsCallback(
    VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
    VkDebugUtilsMessageTypeFlagsEXT messageTypes,
    const VkDebugUtilsMessengerCallbackDataEXT* callbackData,
    void* userData
) noexcept {
    auto* collector = static_cast<ValidationCollector*>(userData);
    if (collector == nullptr || callbackData == nullptr) return VK_FALSE;

    ValidationMessageInput input;
    input.severity = mapSeverity(messageSeverity);
    input.type = mapType(messageTypes);
    input.messageIdNumber = callbackData->messageIdNumber;
    input.messageIdName = callbackData->pMessageIdName != nullptr
        ? callbackData->pMessageIdName : "";
    input.message = callbackData->pMessage != nullptr ? callbackData->pMessage : "";
    input.timestampEpochMs = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count()
    );
    const std::uint32_t boundedCount = callbackData->pObjects == nullptr
        ? 0
        : (callbackData->objectCount > 16 ? 16 : callbackData->objectCount);
    input.objects.reserve(boundedCount);
    for (std::uint32_t index = 0; index < boundedCount; ++index) {
        const auto& object = callbackData->pObjects[index];
        input.objects.push_back({
            std::to_string(static_cast<std::int32_t>(object.objectType)),
            object.pObjectName != nullptr ? object.pObjectName : "",
        });
    }
    collector->record(std::move(input));
    return VK_FALSE;
}

}  // namespace bncam::vulkan
