#pragma once
#include "ChromaDetailValidation.h"
#include "../PhysicalLumaDenoise.h"
#include "../FastLocalLaplacianPolicy.h"
#include "../JpegEncodingPolicy.h"
#include "../IspCore.h"
#include "../vulkan/VulkanRuntime.h"
#include <fstream>
#include <map>
#include <tuple>
#include <cstring>
#include <opencv2/imgcodecs.hpp>

namespace bncam::chroma::detailtest {
inline Image pixels(const std::vector<float>& data) {
    if(data.size()!=test::W*test::H*3)throw std::runtime_error("stage readback size");
    Image out(test::W*test::H);std::memcpy(out.data(),data.data(),data.size()*sizeof(float));return out;
}
inline std::array<double,2> lineAmplitude(const Image& image,int width) {
    std::array<double,2> line{},background{};
    for(int y=8;y<test::H-8;++y) {
        for(int x=32;x<32+width;++x)for(int c=0;c<2;++c)line[c]+=opponent(image[y*test::W+x])[c+1]/double(width*(test::H-16));
        for(int x=8;x<16;++x)for(int c=0;c<2;++c)background[c]+=opponent(image[y*test::W+x])[c+1]/double(8*(test::H-16));
    }
    return {line[0]-background[0],line[1]-background[1]};
}
// Executes the actual production Vulkan colour/tone/JPEG stages on known
// post-demosaic RGB. It does not pretend a near-Nyquist RGB line is Bayer truth.
inline std::string runStages(vulkan::VulkanRuntime& runtime,const std::string& directory) {
    using namespace vulkan;
    std::ofstream csv(directory+"/colour-detail-stages.csv");
    csv<<"channel,width,variant,stage,amplitudeRG,amplitudeBG\n";
    std::string origin;
    auto emit=[&](int channel,int width,const char* variant,const char* stage,const Image& image) {
        auto a=lineAmplitude(image,width);csv<<channel<<','<<width<<','<<variant<<','<<origin<<stage<<','<<std::setprecision(10)<<a[0]<<','<<a[1]<<'\n';
    };
    bool pass=true;
    std::map<std::tuple<int,int,int>,std::vector<uint8_t>> oracleJpeg;
    std::map<std::tuple<int,int,int>,std::vector<uint8_t>> baselineJpeg;
    std::ofstream jpegErrors(directory+"/colour-detail-jpeg-errors.csv");
    jpegErrors<<"source,channel,width,variant,mseR,mseG,mseB\n";
    for(int source=0;source<3;++source)for(int channel:{1,2})for(int width:{1,2,4})for(int variant=0;variant<3;++variant) {
        const char* name=variant==0?"bypass":(variant==1?"baseline04":"candidate05");
        Image input(test::W*test::H);
        for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x) {
            float v=x>=32&&x<32+width?.02f:0;
            input[y*test::W+x]=rgb({.03f,channel==1?v:0.f,channel==2?v:0.f});
        }
        origin=source==0?"RGB_ORACLE/":(source==1?"RAW10_MALVAR/":"RAW_SENSOR_MALVAR/");
        emit(channel,width,name,"ground_truth",input);
        if(source!=0) {
            std::vector<uint16_t> raw(input.size());const int channels[4]={0,1,1,2};
            for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x)
                raw[y*test::W+x]=uint16_t(std::lround(64+959*input[y*test::W+x][channels[(y%2)*2+x%2]]));
            RawDomainInfo info;info.width=test::W;info.height=test::H;info.masterRowStrideBytes=test::W*2;
            info.sourceFormat=source==1?RawSourceFormat::RAW10:RawSourceFormat::RAW_SENSOR;
            info.sensorCfaPattern=info.effectiveCfaPattern=0;info.sourceBitDepth=10;
            info.effectiveWhiteLevelInMasterUnits=1023;info.effectiveBlackLevelPatternInMasterUnits={64,64,64,64};
            auto linear=normalizeRawForJpeg(raw.data(),info);
            if(!linear.diagnostics.valid)throw std::runtime_error("stage RAW normalization");
            auto reconstructed=demosaicMalvar2004ToRgb32f(linear.mosaic,0);
            std::memcpy(input.data(),reconstructed.ptr<float>(),input.size()*sizeof(Pixel));
        }
        emit(channel,width,name,"post_demosaic",input);
        SpectraResidentColorTransformRequest r{};
        r.frameWidth=test::W;r.frameHeight=test::H;r.rowStrideFloats=test::W*3;r.rgbData=input[0].data();
        // Known synthetic, white- and Y-preserving nonidentity CCM. Transport
        // its noise covariance exactly, as the production colour owner does.
        r.colorMatrix={1.07874f,-.08596f,.00722f,-.02126f,1.01404f,.00722f,-.02126f,.11404f,.90722f};
        const std::array<double,9> opponentToRgb{1,.7874,-.0722,1,-.2126,-.0722,1,-.2126,.9278};
        auto noise=spectra2::makeState("TEST","KNOWN_OPPONENT_COVARIANCE","VALID",1.,
                spectra2::multiply(opponentToRgb,spectra2::diagonalCovariance(.000025,.0004,.0004)));
        auto model=inColorDomain(fromNoiseState(noise,true),noise,r.colorMatrix,1.f);
        model.adaptiveSpatialNoise=true;model.protectCoherentDetail=variant==2;
        if(variant!=0)r.baselinePhysicalChroma=model;
        r.physicalChromaValidationOnly=true;
        auto chroma=runtime.executeSpectraResidentAwbCcm(r);if(!chroma.success)throw std::runtime_error(chroma.failureReason);
        auto chromaPixels=pixels(chroma.outputRgb);emit(channel,width,name,"physical_chroma",chromaPixels);
        if(source==0&&width==1&&variant==1)pass &= std::abs(lineAmplitude(chromaPixels,width)[channel-1]/.02-.28)<1e-4;
        if(source==0&&variant==2)pass &= std::abs(lineAmplitude(chromaPixels,width)[channel-1]/.02-1)<1e-4;
        r.baselinePhysicalLuma={.000025f,1};
        auto luma=runtime.executeSpectraResidentAwbCcm(r);if(!luma.success)throw std::runtime_error(luma.failureReason);
        emit(channel,width,name,"physical_luma",pixels(luma.outputRgb));
        // All samples are <0.05: no sensor clipping provenance and no lost
        // highlight channel. This stage is deliberately an inactive control.
        emit(channel,width,name,"highlight_recovery_inactive_unclipped",pixels(luma.outputRgb));
        // RGB-oracle uploads have no original-CFA clipping provenance. Use the
        // existing unclipped reference entrance, not a fabricated source map.
        // Full RAW replay separately exercises the strict neutral RAW entrance.
        r.physicalChromaValidationOnly=false;
        auto colour=runtime.executeSpectraResidentAwbCcm(r);if(!colour.success)throw std::runtime_error(colour.failureReason);
        emit(channel,width,name,"awb_ccm_no_genuine_huesatmap",pixels(colour.outputRgb));
        r.deferFullReadback=true;
        colour=runtime.executeSpectraResidentAwbCcm(r);
        if(!colour.success)throw std::runtime_error(colour.failureReason);
        SpectraResidentSceneObserverRequest observer{};observer.frameWidth=test::W;observer.frameHeight=test::H;
        auto scene=runtime.executeSpectraResidentSceneObserverFromAwbCcm(observer,colour.residentColorGeneration);
        if(!scene.success)throw std::runtime_error(scene.failureReason);
        std::array<float,8192> lut{};for(int i=0;i<4096;++i){lut[i*2]=i/4095.f;lut[i*2+1]=1;}
        SpectraResidentToneRequest tone{};tone.frameWidth=test::W;tone.frameHeight=test::H;
        tone.residentSceneGeneration=scene.residentSceneGeneration;tone.rawJpegBaseVibrance=-1;
        tone.toneLut=lut.data();tone.toneLutFloatCount=lut.size();tone.bgr8PublicationRequested=true;
        // Constant-Y dark scene with no dynamic-range pressure: use the exact
        // existing neutral FLLF resolver, not a new exposure/tone setting.
        bncam::tone::FastLocalLaplacianInput ti{};ti.p50=ti.p75=ti.p95=ti.p99=.03f;
        ti.physicalNoiseSigmaY=.005f;ti.noiseModelConfidence=1;
        auto plan=bncam::tone::resolveFastLocalLaplacianPlan(ti);
        tone.fllfEnabled=plan.enabled;tone.fllfStrength=plan.strength;tone.fllfSceneKey=plan.sceneKey;
        tone.fllfMaxLiftEv=plan.maxLiftEv;tone.fllfMaxCompressEv=plan.maxCompressEv;
        tone.fllfEdgeStopEv=plan.edgeStopEv;tone.fllfRefinement=plan.refinement;
        tone.fllfPhysicalNoiseSigmaY=plan.physicalNoiseSigmaY;tone.fllfPyramidLevels=plan.pyramidLevels;
        auto display=runtime.executeSpectraResidentTone(tone);if(!display.success)throw std::runtime_error(display.failureReason);
        emit(channel,width,name,"fused_fllf_pbr_gamut_display_linear",pixels(display.outputRgb));
        const uint8_t* bytes=display.bgr8PublicationData?display.bgr8PublicationData:display.outputBgr8.data();
        if(!bytes||!display.bgr8PublicationGenerated)throw std::runtime_error("BGR8 publication unavailable");
        cv::Mat bgr(test::H,test::W,CV_8UC3,const_cast<uint8_t*>(bytes),display.bgr8RowStrideBytes);
        auto decoded=[&](const cv::Mat& b) {Image out(test::W*test::H);for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x){auto p=b.at<cv::Vec3b>(y,x);out[y*test::W+x]={p[2]/255.f,p[1]/255.f,p[0]/255.f};}return out;};
        emit(channel,width,name,"quantized_srgb_pre_jpeg",decoded(bgr));
        std::vector<uint8_t> jpeg;if(!cv::imencode(".jpg",bgr,jpeg,bncam::jpeg444EncodingParameters(98)))throw std::runtime_error("JPEG encode");
        auto key=std::make_tuple(source,channel,width);
        if(variant==0)oracleJpeg[key]=jpeg;
        if(variant==1)baselineJpeg[key]=jpeg;
        if(variant>0) {
            auto reference=cv::imdecode(oracleJpeg[key],cv::IMREAD_COLOR);
            auto output=cv::imdecode(jpeg,cv::IMREAD_COLOR);
            std::array<double,3> error{};
            for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x) {
                auto a=reference.at<cv::Vec3b>(y,x),b=output.at<cv::Vec3b>(y,x);
                for(int c=0;c<3;++c)error[c]+=std::pow((int(a[2-c])-int(b[2-c]))/255.,2)/(test::W*test::H);
            }
            jpegErrors<<source<<','<<channel<<','<<width<<','<<name<<','<<error[0]<<','<<error[1]<<','<<error[2]<<'\n';
            if(source==0&&variant==2) {
                auto baseline=cv::imdecode(baselineJpeg[key],cv::IMREAD_COLOR);
                // Include the unchanged two-pixel border in this full-image MSE.
                // Require a real JPEG improvement, not byte identity to a route
                // that intentionally bypasses that boundary policy too.
                pass &= cv::norm(output,reference,cv::NORM_L2SQR)<.5*cv::norm(baseline,reference,cv::NORM_L2SQR);
                auto a=lineAmplitude(decoded(output),width),b=lineAmplitude(decoded(reference),width);
                pass &= a[channel-1]>=.99*b[channel-1];
            }
        }
        emit(channel,width,name,"jpeg_srgb",decoded(cv::imdecode(jpeg,cv::IMREAD_COLOR)));
        if(source==0&&channel==1&&width==1){std::ofstream file(directory+"/colour-line-"+name+".jpg",std::ios::binary);file.write(reinterpret_cast<const char*>(jpeg.data()),jpeg.size());}
    }
    return pass?"stagePassed=true\n":"stagePassed=false\n";
}
}
