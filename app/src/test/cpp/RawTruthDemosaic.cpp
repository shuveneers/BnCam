// Standalone diagnostic executable. Never linked into the application.
#include "Demosaic.h"
#include "vulkan/VulkanRuntime.h"
#include <opencv2/imgcodecs.hpp>
#include <fstream>
#include <iostream>
#include <cmath>
#include <algorithm>
int main(int argc,char**argv) {
 if(argc!=4)return 2;
 const std::string dir=argv[1],name=argv[2]; const int alg=std::atoi(argv[3]);
 int w,h,cfa;float wb[4],ccm[9];std::ifstream config(dir+"/golden.txt");
 config>>w>>h>>cfa;for(float&v:wb)config>>v;for(float&v:ccm)config>>v;
 if(!config || w<8 || h<8)return 3;
 if(std::abs(wb[1]-wb[2])>1.e-7f){
  std::cerr<<"Unequal Camera2 green gains require explicit CFA prebalancing; refusing an averaged approximation\n";return 7;
 }
 cv::Mat raw(h,w,CV_32FC1);std::ifstream in(dir+"/normalized.f32",std::ios::binary);
 in.read((char*)raw.data,size_t(w)*h*4);if(!in)return 4;
 auto&runtime=bncam::vulkan::VulkanRuntime::instance();runtime.initialize({});
 bncam::vulkan::SpectraResidentDemosaicRequest req{};
 req.mosaicData=raw.ptr<float>();req.frameWidth=w;req.frameHeight=h;req.rowStrideFloats=w;req.cfaPattern=cfa;
 req.algorithm=static_cast<bncam::vulkan::SpectraGpuDemosaicAlgorithm>(alg);
 auto result=runtime.executeSpectraResidentDemosaic(req);
 std::ofstream log(dir+"/"+name+".status.txt");log<<result.status<<"\n"<<result.failureReason<<"\n";
 if(!result.success){std::cerr<<result.status;runtime.shutdown();return 5;}
 // Read back the resident demosaic via the existing diagnostic identity colour
 // pass. Both physical models are absent, WB/CCM identity, validation-only output.
 bncam::vulkan::SpectraResidentColorTransformRequest read{};
 read.frameWidth=w;read.frameHeight=h;read.rowStrideFloats=size_t(w)*3;
 read.residentDemosaicGeneration=result.residentDemosaicGeneration;
 read.physicalChromaValidationOnly=true;
 auto pixels=runtime.executeSpectraResidentAwbCcm(read);
 log<<pixels.status<<" physicalLuma="<<pixels.baselinePhysicalLumaApplied<<" physicalChroma="<<pixels.baselinePhysicalChromaApplied<<"\n";
 log.flush();
 if(!pixels.success || pixels.baselinePhysicalLumaApplied || pixels.baselinePhysicalChromaApplied || pixels.outputRgb.size()!=size_t(w)*h*3){runtime.shutdown();return 6;}
 result.outputRgb=std::move(pixels.outputRgb);
 std::ofstream out(dir+"/"+name+".rgb.f32",std::ios::binary);
 out.write((char*)result.outputRgb.data(),result.outputRgb.size()*4);out.close();
 cv::Mat bgr(h,w,CV_8UC3);
 for(int y=0;y<h;++y)for(int x=0;x<w;++x){
  const float*p=result.outputRgb.data()+(size_t(y)*w+x)*3;
  float s[3]={p[0]*wb[0],p[1]*0.5f*(wb[1]+wb[2]),p[2]*wb[3]};
  for(int c=0;c<3;++c){float v=std::clamp(ccm[c*3]*s[0]+ccm[c*3+1]*s[1]+ccm[c*3+2]*s[2],0.f,1.f);
   v=v<=.0031308f?12.92f*v:1.055f*std::pow(v,1.f/2.4f)-.055f;
   bgr.at<cv::Vec3b>(y,x)[2-c]=cv::saturate_cast<unsigned char>(v*255.f);}
 }
 cv::imwrite(dir+"/"+name+".jpg",bgr,{cv::IMWRITE_JPEG_QUALITY,100,cv::IMWRITE_JPEG_SAMPLING_FACTOR,cv::IMWRITE_JPEG_SAMPLING_FACTOR_444});
 runtime.shutdown();return 0;
}
