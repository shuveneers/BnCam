#pragma once
#include "../NeuralRawDenoiseBackend.h"
#include "VulkanNeuralExecutionPlan.h"
#include "VulkanNeuralKernelRegistry.h"
#include "VulkanNeuralResourceBridge.h"
#include "VulkanVmaIntegration.h"
#include <vulkan/vulkan.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
namespace bncam::vulkan::neural {
struct NeuralVulkanSubmissionTicket {bool accepted=false;std::uint64_t generation=0;std::uint32_t slot=0;bncam::spectra::neural::NeuralRawDenoiseResult immediate{};};
struct NeuralVulkanDiagnostics {std::uint64_t modelLoads=0,submissions=0,completed=0,bypassed=0,failed=0;std::uint32_t inFlightSlots=0,lastTileCount=0,lastKernelDispatches=0;NeuralGpuInputPath lastInputPath=NeuralGpuInputPath::None;std::uint64_t fullFrameCpuReadbackBytes=0;bool cpuFallbackUsed=false;std::string lastFailure="none";};
class VulkanNeuralRawDenoiseBackend final : public bncam::spectra::neural::INeuralRawDenoiseBackend {
public:
 VulkanNeuralRawDenoiseBackend()=default;~VulkanNeuralRawDenoiseBackend() override;VulkanNeuralRawDenoiseBackend(const VulkanNeuralRawDenoiseBackend&)=delete;VulkanNeuralRawDenoiseBackend& operator=(const VulkanNeuralRawDenoiseBackend&)=delete;
 bool initialize(VkPhysicalDevice,VkDevice,VkQueue,VkCommandPool,VulkanAllocatorOwner&,std::mutex& externalQueueMutex,const void* packageBytes,std::size_t packageSize,std::uint32_t inFlightSlots=3u) noexcept;
 void destroy() noexcept; const char* backendName() const noexcept override{return "VulkanNeuralRawDenoiseBackend";} bool available() const noexcept override{return ready_.load();}
 bncam::spectra::neural::NeuralRawDenoiseResult run(const bncam::spectra::neural::NeuralRawDenoiseRequest&) noexcept override;
 NeuralVulkanSubmissionTicket submitAsync(const bncam::spectra::neural::NeuralRawDenoiseRequest&) noexcept; bncam::spectra::neural::NeuralRawDenoiseResult resolve(const NeuralVulkanSubmissionTicket&,std::uint64_t timeoutNs=UINT64_MAX) noexcept;
 NeuralVulkanDiagnostics diagnostics() const noexcept;
private:
 struct Buffer{VkBuffer buffer=VK_NULL_HANDLE;VmaAllocation allocation=nullptr;void*mapped=nullptr;std::uint64_t bytes=0;};
 struct Pipelines{std::array<VkShaderModule,static_cast<std::size_t>(NeuralKernel::Count)> modules{};std::array<VkPipeline,static_cast<std::size_t>(NeuralKernel::Count)> pipelines{};VkDescriptorSetLayout setLayout=VK_NULL_HANDLE;VkPipelineLayout pipelineLayout=VK_NULL_HANDLE;VkDescriptorPool pool=VK_NULL_HANDLE;};
 struct Slot{VkCommandBuffer command=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;std::vector<VkDescriptorSet> sets;Buffer conditioning,a,b,c,skip0,skip1,skip2,filmParams,physicsGlobal,residualLogits,posteriorLogits,confidenceLogits;bool busy=false;std::uint64_t generation=0;bncam::spectra::neural::NeuralRawDenoiseResult pending{};NeuralResolvedGpuInput imported{};};
 void destroyLocked() noexcept;bool ensureBuffer(Buffer&,std::uint64_t,bool hostVisible) noexcept;void freeBuffer(Buffer&) noexcept;bool createPipelines() noexcept;bool uploadWeights() noexcept;bool ensureSlots(const NeuralExecutionPlan&) noexcept;bool validateRequestForVulkan(const bncam::spectra::neural::NeuralRawDenoiseRequest&,std::string&) const noexcept;std::uint32_t acquireFreeSlot() noexcept;bool recordAndSubmit(Slot&,const NeuralExecutionPlan&,const bncam::spectra::neural::NeuralRawDenoiseRequest&,NeuralResolvedGpuInput&,std::string&) noexcept;VkBuffer slotBuffer(Slot&,NeuralSlot) const noexcept;std::uint64_t slotCapacity(const Slot&,NeuralSlot) const noexcept;
 mutable std::mutex mutex_;VkPhysicalDevice physical_=VK_NULL_HANDLE;VkDevice device_=VK_NULL_HANDLE;VkQueue queue_=VK_NULL_HANDLE;VkCommandPool commandPool_=VK_NULL_HANDLE;VmaAllocator allocator_=nullptr;std::mutex*queueMutex_=nullptr;NeuralModelPackage model_{};Buffer weights_{},weightStaging_{},dummy_{};Pipelines pipes_{};std::vector<Slot> slots_;std::atomic<bool>ready_{false};std::uint64_t generation_=0;NeuralVulkanDiagnostics diag_{};
};
} // namespace bncam::vulkan::neural
