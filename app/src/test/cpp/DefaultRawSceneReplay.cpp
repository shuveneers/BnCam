
#include "IspCore.h"
#include "vulkan/VulkanRuntime.h"
#include <fstream>
#include <iostream>
#include <cassert>
int main(int argc,char**argv){
 if(argc!=4 && argc!=5)return 2;
 std::string root=argv[1],name=argv[2];bool neutral=std::atoi(argv[3])!=0;
 std::ifstream cfg(root+"/fixture.txt");int w,h,cfa,iso,format;float exposure,white;float black[4],wb[4],matrix[9];
 cfg>>w>>h>>cfa>>iso>>format>>exposure>>white;for(auto&v:black)cfg>>v;for(auto&v:wb)cfg>>v;for(auto&v:matrix)cfg>>v;assert(cfg.good());
 std::vector<uint16_t> raw(w*h);std::ifstream in(root+"/raw.bin",std::ios::binary);in.read((char*)raw.data(),raw.size()*2);assert(in.good());
 RawDomainInfo info;info.sourceFormat=format?RawSourceFormat::RAW_SENSOR:RawSourceFormat::RAW10;info.width=w;info.height=h;info.masterRowStrideBytes=w*2;info.sensorCfaPattern=cfa;info.effectiveCfaPattern=cfa;info.sourceBitDepth=10;info.effectiveWhiteLevelInMasterUnits=white;for(int i=0;i<4;++i)info.effectiveBlackLevelPatternInMasterUnits[i]=black[i];
 auto linear=normalizeRawForJpeg(raw.data(),info);assert(linear.diagnostics.valid);
 IspFrameMetadata meta;meta.singleShotRaw=neutral;meta.cfaPattern=cfa;meta.isRaw10=!format;meta.captureSensitivityIso=iso;meta.captureExposureTimeNs=int64_t(exposure*1e6f);meta.calibration.spectraProcessingMode=0;meta.calibration.lensId="5";meta.calibration.hasWbGains=true;meta.calibration.hasColorMatrix=true;meta.calibration.colorMatrixFromMetadata=true;meta.calibration.hasWhiteLevel=true;meta.calibration.hasBlackLevel=true;meta.calibration.calibrationApplied=true;meta.calibration.effectiveWhiteLevel=white;meta.calibration.postRawSensitivityBoost=100;
 if(argc==5) meta.requestedDemosaicMode=std::atoi(argv[4]);
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
   assert(meta.lensShadingRows>0 && meta.lensShadingColumns>0);
   meta.lensShadingMap.resize(size_t(meta.lensShadingRows)*meta.lensShadingColumns*4);
   for(auto&v:meta.lensShadingMap)capture>>v;
   assert(!capture.fail());
   meta.lensShadingFromMetadata=true;
   meta.calibration.physicalNoiseJniPayloadReceived=true;
   assert(meta.calibration.physicalNoiseModelAvailable());
 }
 if(std::atoi(argv[3])!=2) bncam::vulkan::VulkanRuntime::instance().initialize({});
 std::string debug;auto jpeg=IspCore::renderRawBaselineJpeg(std::move(linear),meta,quality,&debug,90);
 std::ofstream(root+"/"+name+".txt")<<debug;
 if(jpeg.empty()){std::cerr<<"empty render\n";return 1;}
 std::ofstream out(root+"/"+name+".jpg",std::ios::binary);out.write((char*)jpeg.data(),jpeg.size());std::cout<<name<<" bytes="<<jpeg.size()<<"\n";out.close();bncam::vulkan::VulkanRuntime::instance().shutdown();
}
