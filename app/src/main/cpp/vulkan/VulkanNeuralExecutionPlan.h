#pragma once
#include "VulkanNeuralModelPackage.h"
#include "VulkanNeuralTensorLayout.h"
#include <array>
#include <cstdint>
#include <string>
#include <vector>
namespace bncam::vulkan::neural {
enum class NeuralPrimitive : std::uint8_t { Conditioning=0, Copy=1, Conv=2, Film=3, SimpleGate=4, ScaledAdd=5, Add=6, Writeback=7, Evidence=8 };
enum class NeuralSlot : std::uint8_t { Conditioning=0,A=1,B=2,C=3,Skip0=4,Skip1=5,Skip2=6,Global=7,RawOriginal=8,ResidualLogits=9,PosteriorLogits=10,ConfidenceLogits=11,CleanOutput=12,PosteriorOutput=13,ResidualDebug=14,EvidenceMask=15,EvidenceHeadroom=16 };
struct NeuralDispatchOp {NeuralPrimitive primitive=NeuralPrimitive::Conv; NeuralSlot src0=NeuralSlot::A,src1=NeuralSlot::B,dst=NeuralSlot::A; std::string weight,bias,scale; std::uint32_t channelsIn=0,channelsOut=0,kernel=0,stride=1,spatialDivisor=1; bool upsample2x=false;};
struct NeuralExecutionPlan {bool valid=false;std::string failureReason;std::vector<NeuralDispatchOp> ops;std::uint32_t kernelDispatchesPerTile=0;std::uint64_t activationBytesPerSlot=0;std::uint64_t persistentArenaBytesPerSlot=0;std::uint32_t maxChannels=0;std::uint32_t tileCount=0;NeuralTileGeometry tiles{};};
NeuralExecutionPlan buildNeuralExecutionPlan(const NeuralModelPackage& model,std::uint32_t packedWidth,std::uint32_t packedHeight,std::uint32_t inFlightSlots=2u) noexcept;
} // namespace bncam::vulkan::neural
