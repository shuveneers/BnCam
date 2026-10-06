#pragma once

#include "NeuralSha256.h"
#include <cstring>
#include <string>

namespace bncam::bnc {
// Envelope integrity only: does NOT certify JSON provenance, trained quality or readiness.
// Python's full reference loader validates the manifest/tensor table as well.
// A future production loader must perform both checks against a pinned model identity.
struct PackageEnvelope {
    bool valid=false;
    std::string failureReason="BNC_NEURAL_PACKAGE_INVALID";
    std::uint32_t width=0, blocks=0, manifestBytes=0;
    std::uint64_t weightBytes=0;
};
inline PackageEnvelope validatePackageEnvelope(const void* data, std::size_t size,
        const vulkan::neural::Sha256Digest& expectedFileSha) {
    PackageEnvelope out;
    if(!data || size<64 || size>2*1024*1024) return out;
    const auto* p=static_cast<const std::uint8_t*>(data);
    auto u32=[](const std::uint8_t* q) {return std::uint32_t(q[0])|(std::uint32_t(q[1])<<8)|
        (std::uint32_t(q[2])<<16)|(std::uint32_t(q[3])<<24);};
    if(vulkan::neural::sha256(data,size)!=expectedFileSha) {
        out.failureReason="BNC_NEURAL_SHA_MISMATCH"; return out;
    }
    if(std::memcmp(p,"BNCDEM1\0",8)!=0 || u32(p+8)!=1) return out;
    out.width=u32(p+12); out.blocks=u32(p+16); out.manifestBytes=u32(p+20);
    out.weightBytes=u32(p+24)|(std::uint64_t(u32(p+28))<<32);
    if((out.width!=8 && out.width!=12 && out.width!=16) || (out.blocks!=4 && out.blocks!=6)) return out;
    auto parameters=109u*out.width+8u+out.blocks*(19u*out.width*out.width+3u*out.width);
    if(!out.manifestBytes || out.manifestBytes>1024*1024 || out.weightBytes!=parameters*2u ||
       64u+std::uint64_t(out.manifestBytes)+out.weightBytes!=size) return out;
    auto payloadSha=vulkan::neural::sha256(p+64,size-64);
    if(std::memcmp(payloadSha.data(),p+32,32)!=0) {
        out.failureReason="BNC_NEURAL_PAYLOAD_SHA_MISMATCH"; return out;
    }
    out.valid=true; out.failureReason="none"; return out;
}
} // namespace bncam::bnc
