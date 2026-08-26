#pragma once

#include <vulkan/vulkan.h>

#include <chrono>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

/**
 * Non-owning access to the authoritative runtime pipeline cache.
 *
 * VulkanRuntimeBootstrap remains the sole owner of VkPipelineCache. This registry only publishes
 * the matching (device, cache) pair so backend-local pipeline factories can actually use that
 * lifecycle-persistent cache without duplicating runtime ownership. Host access to the published
 * VkPipelineCache is externally synchronized here as required by Vulkan. Callers that explicitly
 * opt out of the published cache use VK_NULL_HANDLE and therefore do not serialize on that cache.
 */
class VulkanPipelineCacheRegistry final {
public:
    VulkanPipelineCacheRegistry() = delete;

    static void publish(
            VkDevice device,
            VkPipelineCache cache,
            const std::string& persistentPath = {}) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        device_ = device;
        cache_ = cache;
        persistentPath_ = persistentPath;
    }

    static void clear(VkDevice device, VkPipelineCache cache) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ == device && cache_ == cache) {
            device_ = VK_NULL_HANDLE;
            cache_ = VK_NULL_HANDLE;
            persistentPath_.clear();
        }
    }


    static bool persist(VkDevice device) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (device_ != device || cache_ == VK_NULL_HANDLE || persistentPath_.empty()) {
                return false;
            }

            std::size_t dataSize = 0u;
            VkResult result = vkGetPipelineCacheData(device, cache_, &dataSize, nullptr);
            if (result != VK_SUCCESS || dataSize == 0u || dataSize > kMaxPersistentCacheBytes) {
                return false;
            }

            std::vector<std::uint8_t> data(dataSize);
            result = vkGetPipelineCacheData(device, cache_, &dataSize, data.data());
            if (result != VK_SUCCESS || dataSize == 0u) return false;
            data.resize(dataSize);

            const std::string temporaryPath = persistentPath_ + ".tmp";
            {
                std::ofstream output(temporaryPath, std::ios::binary | std::ios::trunc);
                if (!output) return false;
                output.write(reinterpret_cast<const char*>(data.data()),
                             static_cast<std::streamsize>(data.size()));
                output.flush();
                if (!output.good()) {
                    output.close();
                    std::remove(temporaryPath.c_str());
                    return false;
                }
            }
            if (std::rename(temporaryPath.c_str(), persistentPath_.c_str()) != 0) {
                std::remove(temporaryPath.c_str());
                return false;
            }
            return true;
        } catch (...) {
            return false;
        }
    }

    static VkResult createComputePipelines(
            VkDevice device,
            std::uint32_t createInfoCount,
            const VkComputePipelineCreateInfo* createInfos,
            const VkAllocationCallbacks* allocator,
            VkPipeline* pipelines,
            float* mutexWaitMs = nullptr,
            bool* cachePresent = nullptr,
            float* createCallMs = nullptr,
            bool usePublishedCache = true) noexcept {
        if (!usePublishedCache) {
            if (mutexWaitMs != nullptr) *mutexWaitMs = 0.0f;
            if (cachePresent != nullptr) *cachePresent = false;
            const auto createStarted = std::chrono::steady_clock::now();
            const VkResult result = vkCreateComputePipelines(
                    device, VK_NULL_HANDLE, createInfoCount, createInfos, allocator, pipelines);
            if (createCallMs != nullptr) {
                *createCallMs = static_cast<float>(std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - createStarted).count());
            }
            return result;
        }

        const auto waitStarted = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(mutex_);
        if (mutexWaitMs != nullptr) {
            *mutexWaitMs = static_cast<float>(std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - waitStarted).count());
        }
        const VkPipelineCache activeCache = device_ == device ? cache_ : VK_NULL_HANDLE;
        if (cachePresent != nullptr) *cachePresent = activeCache != VK_NULL_HANDLE;
        const auto createStarted = std::chrono::steady_clock::now();
        const VkResult result = vkCreateComputePipelines(
                device, activeCache, createInfoCount, createInfos, allocator, pipelines);
        if (createCallMs != nullptr) {
            *createCallMs = static_cast<float>(std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - createStarted).count());
        }
        return result;
    }

private:
    inline static std::mutex mutex_{};
    inline static VkDevice device_ = VK_NULL_HANDLE;
    inline static VkPipelineCache cache_ = VK_NULL_HANDLE;
    inline static std::string persistentPath_{};
    inline static constexpr std::size_t kMaxPersistentCacheBytes = 64u * 1024u * 1024u;
};

} // namespace bncam::vulkan
