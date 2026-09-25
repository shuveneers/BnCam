#include "VulkanNeuralModelPackage.h"
#include <algorithm>
#include <cstring>
#include <fstream>
#include <initializer_list>
#include <limits>
namespace bncam::vulkan::neural {
namespace {
template<class T> bool readLe(const std::vector<std::uint8_t>& b,std::size_t off,T& out){if(off+sizeof(T)>b.size())return false;std::memcpy(&out,b.data()+off,sizeof(T));return true;}
std::uint16_t u16(const std::uint8_t* p){return std::uint16_t(p[0])|(std::uint16_t(p[1])<<8);} std::uint32_t u32(const std::uint8_t* p){return std::uint32_t(p[0])|(std::uint32_t(p[1])<<8)|(std::uint32_t(p[2])<<16)|(std::uint32_t(p[3])<<24);} std::uint64_t u64(const std::uint8_t* p){std::uint64_t v=0;for(int i=7;i>=0;--i)v=(v<<8)|p[i];return v;}
float f32(const std::uint8_t* p){auto x=u32(p);float f;std::memcpy(&f,&x,4);return f;}
bool eq(const Sha256Digest&a,const Sha256Digest&b){return std::equal(a.begin(),a.end(),b.begin());}
}
const NeuralTensorRecord* NeuralModelPackage::findTensor(const std::string& n) const noexcept{for(const auto& t:tensors)if(t.name==n)return &t;return nullptr;}
const std::uint8_t* NeuralModelPackage::tensorBytes(const NeuralTensorRecord& t) const noexcept{if(!valid||t.weightOffset+t.weightBytes>weightsBytes)return nullptr;return bytes.data()+weightsFileOffset+t.weightOffset;}
namespace {
bool tensorShape(const NeuralModelPackage& m,const char* name,std::initializer_list<std::uint32_t> dims,NeuralWeightPacking packing){
 const auto*t=m.findTensor(name);if(!t||t->rank!=dims.size()||t->packing!=packing)return false;std::size_t i=0;for(auto d:dims)if(t->dims[i++]!=d)return false;return true;
}
bool filmGainColumnsAreZero(const NeuralModelPackage& m,const char* name,std::uint32_t outputRows){
 const auto*t=m.findTensor(name);if(!t||t->rank!=2||t->packing!=NeuralWeightPacking::O4I4||t->logicalOut!=outputRows||t->logicalIn!=18||t->dims[0]!=outputRows||t->dims[1]!=18)return false;
 const auto op=(outputRows+3u)&~3u,ip=(18u+3u)&~3u;if(t->weightBytes!=std::uint64_t(op)*ip*2u||t->weightOffset+t->weightBytes>m.weightsBytes)return false;
 const auto*base=m.bytes.data()+m.weightsFileOffset+t->weightOffset;const auto inputGroups=ip/4u;
 for(std::uint32_t row=0;row<outputRows;++row)for(std::uint32_t column=1;column<=2;++column){
  const auto element=(((row/4u)*inputGroups+(column/4u))*16u+(row%4u)*4u+(column%4u));
  const auto bits=u16(base+element*2u);if((bits&0x7fffu)!=0u)return false;
 }
 return true;
}
bool productionContractValid(const NeuralModelPackage& m){
 if(m.flags!=0u||m.inputChannels!=14u||m.globalConditionDim!=18u||m.widths!=std::array<std::uint32_t,4>{12u,16u,24u,32u}||m.encoderBlocks!=std::array<std::uint32_t,3>{1u,1u,1u}||m.bottleneckBlocks!=1u||m.decoderBlocks!=std::array<std::uint32_t,3>{1u,1u,1u}||m.residualKSigma!=3.0f||m.posteriorMin!=-6.0f||m.posteriorMax!=2.0f||m.tensors.size()!=116u)return false;
 if(!tensorShape(m,"residual_head.weight",{4u,12u,3u,3u},NeuralWeightPacking::O4I4HW)||!tensorShape(m,"residual_head.bias",{4u},NeuralWeightPacking::C4)||!tensorShape(m,"posterior_head.weight",{4u,12u,3u,3u},NeuralWeightPacking::O4I4HW)||!tensorShape(m,"posterior_head.bias",{4u},NeuralWeightPacking::C4))return false;
 return filmGainColumnsAreZero(m,"enc0.film.proj.weight",24u)&&filmGainColumnsAreZero(m,"enc1.film.proj.weight",32u)&&filmGainColumnsAreZero(m,"enc2.film.proj.weight",48u)&&filmGainColumnsAreZero(m,"bottleneck.film.proj.weight",64u)&&filmGainColumnsAreZero(m,"dec2.film.proj.weight",48u)&&filmGainColumnsAreZero(m,"dec1.film.proj.weight",32u)&&filmGainColumnsAreZero(m,"dec0.film.proj.weight",24u);
}
}
NeuralModelPackage loadNeuralModelPackage(const void* ptr,std::size_t size) noexcept{
 NeuralModelPackage o{}; try{if(!ptr||size<256){o.failureReason="MODEL_PACKAGE_TRUNCATED";return o;}o.bytes.assign(static_cast<const std::uint8_t*>(ptr),static_cast<const std::uint8_t*>(ptr)+size);const auto*p=o.bytes.data();if(std::memcmp(p,"BNCNVK1\0",8)!=0||u32(p+8)!=1||u32(p+12)!=256||u32(p+16)!=128){o.failureReason="MODEL_PACKAGE_HEADER_INVALID";return o;}
 o.flags=u32(p+20);auto count=u64(p+24),table=u64(p+32),strings=u64(p+40),weights=u64(p+48),wbytes=u64(p+56),total=u64(p+64); if(total!=size||table!=256||count>100000||strings!=table+count*128||weights<strings||weights+wbytes!=size){o.failureReason="MODEL_PACKAGE_BOUNDS_INVALID";return o;}
 o.inputChannels=u32(p+72);o.globalConditionDim=u32(p+76);o.innerTile=u32(p+80);o.halo=u32(p+84);for(int i=0;i<4;++i)o.widths[i]=u32(p+88+i*4);for(int i=0;i<3;++i)o.encoderBlocks[i]=u32(p+104+i*4);o.bottleneckBlocks=u32(p+116);for(int i=0;i<3;++i)o.decoderBlocks[i]=u32(p+120+i*4);o.receptiveField=u32(p+132);o.residualKSigma=f32(p+136);o.posteriorMin=f32(p+140);o.posteriorMax=f32(p+144);std::memcpy(o.sourceModelSha.data(),p+148,32);std::memcpy(o.packedWeightsSha.data(),p+180,32);std::memcpy(o.payloadSha.data(),p+212,32);
 if(o.inputChannels!=14||o.globalConditionDim!=18||o.halo*2u+1u<o.receptiveField||o.residualKSigma<=0.0f||o.posteriorMax<=o.posteriorMin){o.failureReason="MODEL_PACKAGE_SCHEMA_INCOMPATIBLE";return o;}auto payload=sha256(p+256,size-256);if(!eq(payload,o.payloadSha)){o.failureReason="MODEL_PACKAGE_PAYLOAD_HASH_MISMATCH";return o;}auto wh=sha256(p+weights,wbytes);if(!eq(wh,o.packedWeightsSha)){o.failureReason="MODEL_PACKAGE_WEIGHTS_HASH_MISMATCH";return o;}
 o.weightsFileOffset=weights;o.weightsBytes=wbytes;o.tensors.reserve(static_cast<std::size_t>(count));for(std::uint64_t i=0;i<count;++i){const auto*r=p+table+i*128;const auto no=u32(r);const auto nl=u16(r+4);const auto rank=u16(r+6);if(rank<1||rank>4||strings+no+nl>=weights){o.failureReason="MODEL_PACKAGE_TENSOR_RECORD_INVALID";return o;}NeuralTensorRecord t{};t.name.assign(reinterpret_cast<const char*>(p+strings+no),nl);if(t.name.empty()||p[strings+no+nl]!=0||o.findTensor(t.name)){o.failureReason="MODEL_PACKAGE_TENSOR_NAME_INVALID";return o;}for(int d=0;d<4;++d)t.dims[d]=u32(r+8+d*4);t.rank=rank;t.packing=static_cast<NeuralWeightPacking>(u32(r+24));t.logicalOut=u32(r+28);t.logicalIn=u32(r+32);t.kernelH=u32(r+36);t.kernelW=u32(r+40);t.weightOffset=u64(r+44);t.weightBytes=u64(r+52);std::memcpy(t.sha.data(),r+60,32);if(t.weightBytes==0||(t.weightOffset%16u)!=0u||t.weightOffset+t.weightBytes>wbytes){o.failureReason="MODEL_PACKAGE_TENSOR_BOUNDS_INVALID";return o;}auto th=sha256(p+weights+t.weightOffset,t.weightBytes);if(!eq(th,t.sha)){o.failureReason="MODEL_PACKAGE_TENSOR_HASH_MISMATCH";return o;}o.tensors.push_back(std::move(t));}
 if(!productionContractValid(o)){o.failureReason="MODEL_PACKAGE_PRODUCTION_CONTRACT_INCOMPATIBLE";return o;}o.valid=true;o.failureReason="none";return o;}catch(...){o.valid=false;o.failureReason="MODEL_PACKAGE_EXCEPTION";return o;}}
NeuralModelPackage loadNeuralModelPackageFile(const std::string& path) noexcept{try{std::ifstream f(path,std::ios::binary);if(!f){NeuralModelPackage o;o.failureReason="MODEL_PACKAGE_FILE_OPEN_FAILED";return o;}f.seekg(0,std::ios::end);auto n=f.tellg();if(n<=0){NeuralModelPackage o;o.failureReason="MODEL_PACKAGE_FILE_EMPTY";return o;}f.seekg(0);std::vector<std::uint8_t>b(static_cast<std::size_t>(n));f.read(reinterpret_cast<char*>(b.data()),n);if(!f){NeuralModelPackage o;o.failureReason="MODEL_PACKAGE_FILE_READ_FAILED";return o;}return loadNeuralModelPackage(b.data(),b.size());}catch(...){NeuralModelPackage o;o.failureReason="MODEL_PACKAGE_FILE_EXCEPTION";return o;}}
} // namespace bncam::vulkan::neural
