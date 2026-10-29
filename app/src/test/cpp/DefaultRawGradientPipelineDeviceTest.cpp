// Full production ISP regression: exercises CFA provenance across resident backend calls.
#include "IspCore.h"
#include "vulkan/VulkanRuntime.h"
#include <opencv2/imgcodecs.hpp>
#include <cassert>
#include <iostream>
cv::Mat render(int cfa,bool sensor,bool full){
    constexpr int w=128,h=96;std::vector<uint16_t> raw(w*h);
    const int red[]={0,1,2,3};
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
        int site=(y%2)*2+x%2;
        float v=(site==red[cfa] && !full) ? .35f+.40f*x/(w-1) : 1.f;
        raw[y*w+x]=uint16_t(std::lround(64+v*959));
    }
    RawDomainInfo info;info.sourceFormat=sensor?RawSourceFormat::RAW_SENSOR:RawSourceFormat::RAW10;
    info.width=w;info.height=h;info.masterRowStrideBytes=w*2;
    info.sensorCfaPattern=cfa;info.effectiveCfaPattern=cfa;info.sourceBitDepth=10;
    info.effectiveWhiteLevelInMasterUnits=1023;
    for(auto& b:info.effectiveBlackLevelPatternInMasterUnits)b=64;
    auto normalized=normalizeRawForJpeg(raw.data(),info);assert(normalized.diagnostics.valid);
    IspFrameMetadata meta;meta.singleShotRaw=true;meta.cfaPattern=cfa;meta.isRaw10=!sensor;
    meta.captureSensitivityIso=100;meta.captureExposureTimeNs=2000000;meta.requestedDemosaicMode=1;
    auto& c=meta.calibration;c.spectraProcessingMode=0;c.lensId="5";
    c.hasWbGains=true;c.hasColorMatrix=true;c.colorMatrixFromMetadata=true;
    c.hasWhiteLevel=true;c.hasBlackLevel=true;c.calibrationApplied=true;
    c.effectiveWhiteLevel=1023;c.postRawSensitivityBoost=100;
    NativeRenderQualityConfig quality;quality.wbRed=3;quality.wbGreenEven=quality.wbGreenOdd=1;
    quality.wbBlue=1.5f;quality.wbFromMetadata=true;quality.colorMatrixFromMetadata=true;
    quality.captureSensitivityIso=100;
    for(int i=0;i<4;++i){c.effectiveWbGains[i]=i==0?3:i==3?1.5f:1;c.effectiveBlackLevels[i]=64;}
    for(int i=0;i<9;++i)quality.colorMatrix[i]=c.effectiveColorMatrix[i]=(i%4==0?1:0);
    std::string debug;auto bytes=IspCore::renderRawBaselineJpeg(std::move(normalized),meta,quality,&debug,95);
    assert(!bytes.empty());
    cv::Mat encoded(1,int(bytes.size()),CV_8UC1,bytes.data());
    auto image=cv::imdecode(encoded,cv::IMREAD_COLOR);assert(!image.empty());return image;
}
int main(){
    std::cout.setf(std::ios::unitbuf);bncam::vulkan::VulkanRuntime::instance().initialize({});
    std::array<cv::Mat,4> sensor;
    for(int pattern=0;pattern<4;++pattern){
        sensor[pattern]=render(pattern,true,false);auto raw10=render(pattern,false,false);
        assert(cv::norm(raw10,sensor[pattern],cv::NORM_INF)==0);
        const auto& image=sensor[pattern];
        double min=255,max=0;int maxChroma=0;
        // JPEG is published clockwise: source-x cloud ramp runs along output-y.
        for(int y=12;y<image.rows-12;++y){auto p=image.at<cv::Vec3b>(y,image.cols/2);
            double v=(p[0]+p[1]+p[2])/3.;min=std::min(min,v);max=std::max(max,v);
            maxChroma=std::max(maxChroma,int(std::max({p[0],p[1],p[2]})-std::min({p[0],p[1],p[2]})));
        }
        assert(max-min>=12 && maxChroma<=2);
        std::cout<<"PRODUCTION_CFA_PASS pattern="<<pattern<<" cloud_codes="<<max-min<<" RAW10_SENSOR_exact=1\n";
    }
    auto full=render(3,true,true);cv::Scalar mean,stddev;cv::meanStdDev(full,mean,stddev);
    for(int c=0;c<3;++c)assert(mean[c]>=250 && stddev[c]<.5);
    assert(std::abs(mean[0]-mean[1])<1 && std::abs(mean[1]-mean[2])<1);
    bncam::vulkan::VulkanRuntime::instance().shutdown();
    std::cout<<"PRODUCTION_GRADIENT_PIPELINE_PASS fully_lost_neutral=1\n";
}
