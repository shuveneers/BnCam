
#include "IspCore.h"
#include "Demosaic.h"
#include "vulkan/VulkanRuntime.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <cmath>
#include <limits>
#include <cctype>
#include <chrono>
int replay(int argc,char**argv){
 const auto started=std::chrono::steady_clock::now();
 const auto elapsed=[](auto begin,auto end){return std::chrono::duration<double,std::milli>(end-begin).count();};
 const auto check=[](bool condition,const char* message){if(!condition)throw std::runtime_error(message);};
 if(argc<4 || argc>6)return 2;
 std::string root=argv[1],name=argv[2];bool neutral=std::atoi(argv[3])!=0;
 check(!name.empty() && name.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")==std::string::npos,"output name must be a simple file stem");
 std::ifstream cfg(root+"/fixture.txt");int w=0,h=0,cfa=-1,iso=0,format=-1;float exposure=0,white=0;float black[4]{},wb[4]{},matrix[9]{};
 cfg>>w>>h>>cfa>>iso>>format>>exposure>>white;for(auto&v:black)cfg>>v;for(auto&v:wb)cfg>>v;for(auto&v:matrix)cfg>>v;check(!cfg.fail(),"invalid fixture header/calibration");
 check(w>0 && h>0 && cfa>=0 && cfa<=3 && iso>0 && (format==0 || format==1),"invalid RAW geometry/CFA/format/ISO");
 check(std::isfinite(white) && white>0 && white<=65535 && std::isfinite(exposure) && exposure>0,"invalid white/exposure");
 for(float v:black)check(std::isfinite(v) && v>=0 && v<white,"invalid positional black levels");
 for(float v:wb)check(std::isfinite(v) && v>0,"invalid white balance");
 for(float v:matrix)check(std::isfinite(v),"invalid color matrix");
 int originX=0,originY=0;size_t stride=size_t(w)*2;
 std::ifstream geometry(root+"/geometry.txt");
 if(geometry){geometry>>originX>>originY>>stride;check(!geometry.fail() && originX>=0 && originY>=0 && stride>=size_t(w)*2 && stride%2==0,"invalid geometry.txt");}
 check(size_t(h)<=std::numeric_limits<size_t>::max()/stride,"RAW byte count overflow");
 const size_t byteCount=stride*size_t(h);
 check(byteCount<=size_t(std::numeric_limits<std::streamsize>::max()),"RAW too large for stream");
 std::ifstream in(root+"/raw.bin",std::ios::binary|std::ios::ate);
 check(in.good() && in.tellg()==std::streamoff(byteCount),"raw.bin size does not match fixture geometry");
 std::vector<uint16_t> raw(byteCount/2);in.seekg(0);in.read((char*)raw.data(),byteCount);check(!in.fail(),"RAW read failed");
 RawDomainInfo info;info.sourceFormat=format?RawSourceFormat::RAW_SENSOR:RawSourceFormat::RAW10;info.width=w;info.height=h;info.masterRowStrideBytes=stride;info.sensorCfaPattern=cfa;info.cfaOffsetX=originX;info.cfaOffsetY=originY;info.effectiveCfaPattern=effectiveCfaPatternAtOrigin(cfa,originX,originY);info.sourceBitDepth=std::max(1,int(std::ceil(std::log2(white+1))));info.effectiveWhiteLevelInMasterUnits=white;for(int i=0;i<4;++i)info.effectiveBlackLevelPatternInMasterUnits[i]=black[i];
 const auto ingested=std::chrono::steady_clock::now();
 auto linear=normalizeRawForJpeg(raw.data(),info);check(linear.diagnostics.valid,"production RAW normalization rejected fixture");
 const auto normalized=std::chrono::steady_clock::now();
 check(cv::checkRange(linear.mosaic,true,nullptr,0.0,1.000001),"normalized fixture contains invalid or out-of-range samples");
 if(argc==6){
   check(std::string(argv[5])=="audit","unknown replay audit option");
   for(int algorithm=1;algorithm<=2;++algorithm){
     const auto run=[&](const cv::Mat& mosaic){return algorithm==1?demosaicMalvar2004ToRgb32f(mosaic,info.effectiveCfaPattern):demosaicAmazeInspiredToRgb32f(mosaic,info.effectiveCfaPattern);};
     auto first=run(linear.mosaic);auto saved=first.clone();
     check(!first.empty() && cv::checkRange(first,true,nullptr,-1.e30,1.e30),"real-fixture CPU RGB contains NaN/Inf");
     cv::Mat other;linear.mosaic.convertTo(other,CV_32F,0.37);run(other);
     check(cv::norm(first,saved,cv::NORM_INF)==0,"real-fixture retained RGB changed after second call");
     check(cv::norm(first,run(linear.mosaic),cv::NORM_INF)<1.e-6,"real-fixture CPU replay is nondeterministic");
     for(int y=0;y<h;++y)for(int x=0;x<w;++x){
       const int channel=bncam::raw::bayerColorAt(info.effectiveCfaPattern,x,y);
       check(std::abs(first.at<cv::Vec3f>(y,x)[channel]-linear.mosaic.at<float>(y,x))<1.e-6,"real-fixture sampled sensel was changed");
     }
     double lo=0,hi=0;cv::minMaxLoc(first.reshape(1),&lo,&hi);
     std::cout<<"cpuAudit algorithm="<<algorithm<<" finite=true ownership=true deterministic=true samplePreservation=true min="<<lo<<" max="<<hi<<"\n";
   }
 }
 IspFrameMetadata meta;meta.singleShotRaw=neutral;meta.cfaPattern=info.effectiveCfaPattern;meta.captureAttemptId="replay_"+name;meta.isRaw10=!format;meta.captureSensitivityIso=iso;meta.captureExposureTimeNs=int64_t(exposure*1e6f);meta.calibration.spectraProcessingMode=0;meta.calibration.lensId="replay-fixture";meta.calibration.hasWbGains=true;meta.calibration.hasColorMatrix=true;meta.calibration.colorMatrixFromMetadata=true;meta.calibration.hasWhiteLevel=true;meta.calibration.hasBlackLevel=true;meta.calibration.calibrationApplied=true;meta.calibration.effectiveWhiteLevel=white;meta.calibration.postRawSensitivityBoost=100;
 if(argc>=5){meta.requestedDemosaicMode=std::atoi(argv[4]);check(meta.requestedDemosaicMode>=0 && meta.requestedDemosaicMode<=2,"replay scope supports Auto Hybrid/Malvar/AMaZE only");}
 for(int i=0;i<4;++i){meta.calibration.effectiveWbGains[i]=wb[i];meta.calibration.effectiveBlackLevels[i]=black[i];}
 NativeRenderQualityConfig quality;quality.wbRed=wb[0];quality.wbGreenEven=wb[1];quality.wbGreenOdd=wb[2];quality.wbBlue=wb[3];quality.wbFromMetadata=true;quality.colorMatrixFromMetadata=true;quality.captureSensitivityIso=iso;
 for(int i=0;i<9;++i){quality.colorMatrix[i]=matrix[i];meta.calibration.effectiveColorMatrix[i]=matrix[i];}
 // Optional capture metadata restores the existing physical denoise and LSC;
 // neither algorithm nor its strength is changed by this replay.
 std::ifstream capture(root+"/capture-metadata.txt");
 if(capture){
   capture>>meta.calibration.signalModelConfidence;
   for(int i=0;i<4;++i)capture>>meta.calibration.effectiveS[i]>>meta.calibration.effectiveO[i];
   capture>>meta.lensShadingRows>>meta.lensShadingColumns;
   check(meta.lensShadingRows>0 && meta.lensShadingColumns>0 && size_t(meta.lensShadingRows)<=std::numeric_limits<size_t>::max()/size_t(meta.lensShadingColumns)/4,"invalid lens shading dimensions");
   meta.lensShadingMap.resize(size_t(meta.lensShadingRows)*meta.lensShadingColumns*4);
   for(auto&v:meta.lensShadingMap)capture>>v;
   check(!capture.fail(),"invalid capture metadata");
   meta.lensShadingFromMetadata=true;
   meta.calibration.physicalNoiseJniPayloadReceived=true;
   check(meta.calibration.physicalNoiseModelAvailable(),"invalid physical noise metadata");
 }
 if(std::atoi(argv[3])!=2) bncam::vulkan::VulkanRuntime::instance().initialize({});
 int rotation=0;std::ifstream orientation(root+"/orientation.txt");
 if(orientation){orientation>>rotation;check(!orientation.fail() && (rotation==0 || rotation==90 || rotation==180 || rotation==270),"invalid output orientation");}
 const auto renderStart=std::chrono::steady_clock::now();
 std::string debug;auto jpeg=IspCore::renderRawBaselineJpeg(std::move(linear),meta,quality,&debug,rotation);
 const auto rendered=std::chrono::steady_clock::now();
 std::ofstream(root+"/"+name+".txt")<<debug;
 if(jpeg.empty()){std::cerr<<"empty render\n";return 1;}
 const auto saveStart=std::chrono::steady_clock::now();
 std::ofstream out(root+"/"+name+".jpg",std::ios::binary);out.write((char*)jpeg.data(),jpeg.size());out.close();check(out.good(),"JPEG save failed");
 const auto saved=std::chrono::steady_clock::now();
 std::ofstream(root+"/"+name+"-timing.txt")<<"ingestMs="<<elapsed(started,ingested)<<";normalizeMs="<<elapsed(ingested,normalized)<<";renderMs="<<elapsed(renderStart,rendered)<<";saveMs="<<elapsed(saveStart,saved)<<";totalMs="<<elapsed(started,saved)<<";normalizedFinite=true\n";
 std::cout<<name<<" bytes="<<jpeg.size()<<"\n";bncam::vulkan::VulkanRuntime::instance().shutdown();return 0;
}

int main(int argc,char**argv){
 try {return replay(argc,argv);}
 catch(const std::exception& failure){
   std::cerr<<"RAW replay failed: "<<failure.what()<<"\n";
   bncam::vulkan::VulkanRuntime::instance().shutdown();return 1;
 }
}
