// NDK executable, not linked into the APK. Cross-checks the Python FP32 oracle.
#include "BncNeuralContract.h"
#include "vulkan/BncNeuralPackageEnvelope.h"
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
using namespace bncam::bnc;
static void require(bool condition,const char* message) { if(!condition) throw std::runtime_error(message); }
template<class T> static T read(std::ifstream& f) {T v;f.read(reinterpret_cast<char*>(&v),sizeof v);require(bool(f),"truncated fixture");return v;}
static std::vector<float> floats(std::ifstream& f,std::size_t n) {
    std::vector<float> v(n);f.read(reinterpret_cast<char*>(v.data()),n*4);require(bool(f),"truncated array");return v;
}
int main(int argc,char** argv) {
    try {
        require(argc==3,"usage: contract fixtures package");
        std::ifstream f(argv[1],std::ios::binary);auto count=read<std::uint32_t>(f);
        for(std::uint32_t i=0;i<count;++i) {
            auto w=read<std::uint32_t>(f),h=read<std::uint32_t>(f),p=read<std::uint32_t>(f);
            auto ox=read<std::int32_t>(f),oy=read<std::int32_t>(f),cx=read<std::int32_t>(f),cy=read<std::int32_t>(f);
            auto g=geometry(w,h,static_cast<Cfa>(p),ox,oy,cx,cy);
            auto raw=floats(f,std::size_t(w)*h), expectedPack=floats(f,g.packedPixels()*4);
            auto missing=floats(f,g.packedPixels()*8),expectedRgb=floats(f,raw.size()*3);
            auto packed=pack(raw,g),rgb=merge(raw,missing,g);
            require(std::memcmp(packed.data(),expectedPack.data(),packed.size()*4)==0,"Python/C++ pack mismatch");
            require(std::memcmp(rgb.data(),expectedRgb.data(),rgb.size()*4)==0,"Python/C++ merge mismatch");
        }
        std::ifstream mf(argv[2],std::ios::binary);
        std::vector<std::uint8_t> bytes{std::istreambuf_iterator<char>(mf),{}};
        auto sha=bncam::vulkan::neural::sha256(bytes.data(),bytes.size());
        require(validatePackageEnvelope(bytes.data(),bytes.size(),sha).valid,"valid envelope rejected");
        bytes.back()^=1;
        require(!validatePackageEnvelope(bytes.data(),bytes.size(),sha).valid,"SHA corruption accepted");
        auto corruptSha=bncam::vulkan::neural::sha256(bytes.data(),bytes.size());
        require(!validatePackageEnvelope(bytes.data(),bytes.size(),corruptSha).valid,"payload corruption accepted");
        require(!validatePackageEnvelope(nullptr,0,sha).valid,"missing package accepted");
        std::cout<<"BNC_NEURAL_NATIVE_CONTRACT_PASS cases="<<count<<" fp32PackMaxError=0 fp32MergeMaxError=0\n";
        return 0;
    } catch(const std::exception& e) {std::cerr<<e.what()<<"\n";return 1;}
}
