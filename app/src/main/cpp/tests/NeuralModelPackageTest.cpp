#include "vulkan/VulkanNeuralModelPackage.h"
#include "vulkan/NeuralSha256.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
int main(int argc,char**argv){using namespace bncam::vulkan::neural;assert(sha256Hex(sha256("abc",3))=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");if(argc<2)return 2;auto m=loadNeuralModelPackageFile(argv[1]);if(!m.valid){std::cerr<<m.failureReason<<"\n";return 3;}assert(m.inputChannels==14&&m.globalConditionDim==18);assert(m.halo*2u+1u>=m.receptiveField);assert(m.findTensor("stem.weight"));assert(m.findTensor("residual_head.weight"));assert(m.findTensor("posterior_head.weight"));auto b=m.bytes;b.back()^=1;auto bad=loadNeuralModelPackage(b.data(),b.size());assert(!bad.valid);std::cout<<"NeuralModelPackageTest PASS tensors="<<m.tensors.size()<<"\n";return 0;}
