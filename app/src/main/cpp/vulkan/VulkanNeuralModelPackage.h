#pragma once
#include "NeuralSha256.h"
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>
namespace bncam::vulkan::neural {
enum class NeuralWeightPacking : std::uint32_t { O4I4HW=1, O4I4=2, C4=3, Scalar=4 };
struct NeuralTensorRecord {
 std::string name; std::array<std::uint32_t,4> dims{1,1,1,1}; std::uint16_t rank=0; NeuralWeightPacking packing=NeuralWeightPacking::C4;
 std::uint32_t logicalOut=0, logicalIn=0, kernelH=0, kernelW=0; std::uint64_t weightOffset=0, weightBytes=0; Sha256Digest sha{};
};
struct NeuralModelPackage {
 bool valid=false; std::string failureReason; std::uint32_t flags=0; std::array<std::uint32_t,4> widths{}; std::array<std::uint32_t,3> encoderBlocks{}; std::array<std::uint32_t,3> decoderBlocks{}; std::uint32_t bottleneckBlocks=0;
 std::uint32_t inputChannels=0,globalConditionDim=0,innerTile=0,halo=0,receptiveField=0; float residualKSigma=0,posteriorMin=0,posteriorMax=0;
 Sha256Digest sourceModelSha{}, packedWeightsSha{}, payloadSha{}; std::uint64_t weightsFileOffset=0,weightsBytes=0; std::vector<NeuralTensorRecord> tensors; std::vector<std::uint8_t> bytes;
 const NeuralTensorRecord* findTensor(const std::string& name) const noexcept;
 const std::uint8_t* tensorBytes(const NeuralTensorRecord& t) const noexcept;
};
NeuralModelPackage loadNeuralModelPackage(const void* data,std::size_t size) noexcept;
NeuralModelPackage loadNeuralModelPackageFile(const std::string& path) noexcept;
} // namespace bncam::vulkan::neural
