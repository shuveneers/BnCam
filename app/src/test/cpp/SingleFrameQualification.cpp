// Device-only measurement harness. No code from this executable is linked into the APK.
#include "IspCore.h"
#include "vulkan/VulkanRuntime.h"
#include <android/thermal.h>
#include <malloc.h>
#include <dlfcn.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <random>
#include <regex>
#include <stdexcept>

using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point start) { return std::chrono::duration<double,std::milli>(Clock::now()-start).count(); }
static void check(bool ok, const std::string& reason) { if (!ok) throw std::runtime_error(reason); }
static long rssKb() {
    std::ifstream in("/proc/self/status"); std::string line;
    while (std::getline(in,line)) if (line.rfind("VmRSS:",0)==0) return std::stol(line.substr(6));
    return -1;
}
static std::vector<float> readRgb(bncam::vulkan::VulkanRuntime& runtime,
        const bncam::vulkan::SpectraResidentDemosaicResult& result, int w,int h) {
    bncam::vulkan::SpectraResidentColorTransformRequest read{};
    read.frameWidth=w; read.frameHeight=h; read.rowStrideFloats=size_t(w)*3;
    read.residentDemosaicGeneration=result.residentDemosaicGeneration;
    read.physicalChromaValidationOnly=true;
    auto pixels=runtime.executeSpectraResidentAwbCcm(read);
    check(pixels.success && !pixels.baselinePhysicalChromaApplied && !pixels.baselinePhysicalLumaApplied,
          "identity readback failed: "+pixels.status);
    return std::move(pixels.outputRgb);
}
static void numerical(bncam::vulkan::VulkanRuntime& runtime,const cv::Mat& raw,int cfa,int iso,const std::string& root) {
    using namespace bncam::vulkan;
    // A full, odd-sized fixture cut from real RAW; all borders and partial workgroups are audited.
    int w=std::min(257,raw.cols),h=std::min(193,raw.rows);
    cv::Mat fixture=raw(cv::Rect(0,0,w,h)).clone();
    AutoDemosaicContext context{};context.captureIso=iso;
    const auto scene=resolveDemosaicForFrame(0,fixture,context);
    check(scene.autoHybridExecution && !scene.fallbackOccurred,"Hybrid candidates unavailable");
    SpectraResidentDemosaicRequest req{};
    req.mosaicData=fixture.ptr<float>();req.frameWidth=w;req.frameHeight=h;req.rowStrideFloats=w;req.cfaPattern=cfa;
    req.algorithm=SpectraGpuDemosaicAlgorithm::MALVAR_2004;
    auto m=runtime.executeSpectraResidentDemosaic(req);check(m.success,m.status);
    auto malvar=readRgb(runtime,m,w,h);
    req.algorithm=SpectraGpuDemosaicAlgorithm::AMAZE;
    auto a=runtime.executeSpectraResidentDemosaic(req);check(a.success,a.status);
    auto amaze=readRgb(runtime,a,w,h);
    req.algorithm=SpectraGpuDemosaicAlgorithm::AUTO_HYBRID;req.hybridValidationReadback=true;
    req.autoMalvarPrior=scene.autoMalvarPrior;req.autoAmazePrior=scene.autoAmazePrior;
    std::vector<float> reference;float minimum=1,maximum=0;double complement=0,oracle=0,candidateError=0;
    size_t nan=0,inf=0,outOfRange=0,unwritten=0;
    for(int replay=0;replay<3;++replay) {
        const auto repeatedScene=resolveDemosaicForFrame(0,fixture,context);
        check(scene.autoMalvarPrior==repeatedScene.autoMalvarPrior && scene.autoAmazePrior==repeatedScene.autoAmazePrior &&
              scene.autoMalvarScore==repeatedScene.autoMalvarScore && scene.autoAmazeScore==repeatedScene.autoAmazeScore &&
              scene.autoSignals==repeatedScene.autoSignals && scene.fallbackReason==repeatedScene.fallbackReason &&
              scene.autoHybridExecution==repeatedScene.autoHybridExecution,"scene priors/score/availability/fallback changed");
        auto r=runtime.executeSpectraResidentDemosaic(req);check(r.success,r.status);
        check(r.hybridValidation.size()==size_t(w)*h*20,"debug readback absent or incomplete");
        if(replay==0) reference=r.hybridValidation;
        else check(reference==r.hybridValidation,"Hybrid mask/candidate/output not bitexact");
        for(size_t p=0;p<size_t(w)*h;++p) {
            const float* v=r.hybridValidation.data()+p*20;
            for(int k=0;k<20;++k) { nan+=std::isnan(v[k]);inf+=std::isinf(v[k]); }
            for(int k=0;k<2;++k) { minimum=std::min(minimum,v[k]);maximum=std::max(maximum,v[k]);outOfRange+=v[k]<0||v[k]>1; }
            unwritten+=v[17]!=1 || v[18]!=float(p%w) || v[19]!=float(p/w);
            complement=std::max(complement,std::abs(double(v[0])+v[1]-1));
            for(int c=0;c<3;++c) {
                // Independent executions of both candidates, plus the actual resident Hybrid RGB.
                candidateError=std::max(candidateError,std::abs(double(v[8+c])-malvar[p*3+c]));
                candidateError=std::max(candidateError,std::abs(double(v[11+c])-amaze[p*3+c]));
                double expected=double(v[0])*malvar[p*3+c]+double(v[1])*amaze[p*3+c];
                oracle=std::max(oracle,std::abs(expected-v[14+c]));
            }
        }
    }
    std::ofstream binary(root+"/hybrid-mask-evidence.f32",std::ios::binary);
    binary.write(reinterpret_cast<const char*>(reference.data()),reference.size()*sizeof(float));
    std::ofstream report(root+"/hybrid-numerical.json");
    report<<"{\"width\":"<<w<<",\"height\":"<<h<<",\"replays\":3,\"weightMin\":"<<minimum
          <<",\"weightMax\":"<<maximum<<",\"nan\":"<<nan<<",\"inf\":"<<inf<<",\"outOfRange\":"<<outOfRange
          <<",\"unwritten\":"<<unwritten<<",\"complementError\":"<<complement<<",\"candidateError\":"<<candidateError
          <<",\"oracleError\":"<<oracle<<",\"tolerance\":0.000002,\"bitexact\":true"
          <<",\"malvarPrior\":"<<scene.autoMalvarPrior<<",\"amazePrior\":"<<scene.autoAmazePrior
          <<",\"malvarScore\":"<<scene.autoMalvarScore<<",\"amazeScore\":"<<scene.autoAmazeScore
          <<",\"candidatesAvailable\":true,\"fallbackReason\":\""<<scene.fallbackReason<<"\"}";
    report.close();
    check(nan==0&&inf==0&&outOfRange==0&&unwritten==0&&complement<=2e-6&&candidateError<=2e-6&&oracle<=2e-6,"Hybrid numerical invariant failed");
    std::cout<<"hybrid numerical passed\n";
}
static void cpuRegression() {
    int cases=0;
    for(int cfa=0;cfa<4;++cfa) for(int parity=0;parity<4;++parity)
    for(auto size: {cv::Size(8,8),cv::Size(17,13),cv::Size(32,24),cv::Size(63,49),cv::Size(96,64)}) {
        cv::Mat raw(size,CV_32FC1);
        for(int y=0;y<raw.rows;++y) for(int x=0;x<raw.cols;++x)
            raw.at<float>(y,x)=float((x*37+y*53+(x*y)%31)%1000)/1000;
        int effective=(cfa ^ parity);
        for(int algorithm: {1,2}) {
            auto first=algorithm==1?demosaicMalvar2004ToRgb32f(raw,effective):demosaicAmazeInspiredToRgb32f(raw,effective);
            auto saved=first.clone();
            auto second=algorithm==1?demosaicMalvar2004ToRgb32f(raw,effective):demosaicAmazeInspiredToRgb32f(raw,effective);
            check(cv::checkRange(first,true,nullptr,-100,100),"nonfinite CPU output");
            check(cv::norm(saved,first,cv::NORM_INF)==0&&cv::norm(first,second,cv::NORM_INF)==0,"CPU ownership/determinism");
            // CPU reference APIs intentionally return borrowed process-wide scratch RGB.
            // Own the retained snapshot, as existing Demosaic.cpp validation does.
            check(saved.data!=second.data,"CPU snapshot aliases borrowed output");
            auto changed=raw.clone();changed.at<float>(0,0)+=0.125f;
            auto third=algorithm==1?demosaicMalvar2004ToRgb32f(changed,effective):demosaicAmazeInspiredToRgb32f(changed,effective);
            check(cv::norm(saved,third,cv::NORM_INF)>0,"changed input did not change CPU output");
            first=algorithm==1?demosaicMalvar2004ToRgb32f(raw,effective):demosaicAmazeInspiredToRgb32f(raw,effective);
            check(cv::norm(saved,first,cv::NORM_INF)==0,"retained CPU snapshot changed");
            static const int channels[4][4]={{0,1,1,2},{1,0,2,1},{1,2,0,1},{2,1,1,0}};
            for(int y=0;y<raw.rows;++y) for(int x=0;x<raw.cols;++x) {
                int channel=channels[effective][(y&1)*2+(x&1)];
                check(std::abs(first.at<cv::Vec3f>(y,x)[channel]-raw.at<float>(y,x))<=1e-6f,"sampled CFA sensel changed");
            }
        }
        ++cases;
    }
    check(cases==80,"CPU case count"); std::cout<<"cpuCases="<<cases<<" passed\n";
}
int main(int argc,char**argv) {
    try {
        check(argc>=3,"usage: bncam_single_frame_qualification FIXTURE_DIR numerical|benchmark [measured=10]");
        const std::string root=argv[1], action=argv[2];
        int w,h,cfa,iso,format;float exposure,white,black[4],wb[4],matrix[9];
        std::ifstream cfg(root+"/fixture.txt");cfg>>w>>h>>cfa>>iso>>format>>exposure>>white;
        for(auto&v:black)cfg>>v;for(auto&v:wb)cfg>>v;for(auto&v:matrix)cfg>>v;
        check(!cfg.fail()&&w>0&&h>0,"invalid fixture config");
        auto prepareStart=Clock::now();
        std::vector<uint16_t> raw(size_t(w)*h);std::ifstream in(root+"/raw.bin",std::ios::binary);
        in.read(reinterpret_cast<char*>(raw.data()),raw.size()*2);check(!in.fail(),"truncated fixture");
        double fixtureReadMs=ms(prepareStart);
        RawDomainInfo info;info.sourceFormat=format?RawSourceFormat::RAW_SENSOR:RawSourceFormat::RAW10;
        info.width=w;info.height=h;info.masterRowStrideBytes=w*2;info.sensorCfaPattern=cfa;info.effectiveCfaPattern=cfa;
        info.sourceBitDepth=10;info.effectiveWhiteLevelInMasterUnits=white;
        for(int i=0;i<4;++i)info.effectiveBlackLevelPatternInMasterUnits[i]=black[i];
        auto& runtime=bncam::vulkan::VulkanRuntime::instance();runtime.initialize({});
        if(action=="numerical") {
            cpuRegression();auto linear=normalizeRawForJpeg(raw.data(),info);check(linear.diagnostics.valid,"normalization");
            numerical(runtime,linear.mosaic,cfa,iso,root);runtime.shutdown();return 0;
        }
        const bool replayOnly=action=="replay";
        check(action=="benchmark"||replayOnly,"unknown action");
        const int measured=replayOnly?0:(argc>3?std::stoi(argv[3]):10);
        check(replayOnly||measured>=10,"minimum 10 measured samples");
        std::string fixtureLens;
        std::ifstream lensFile(root+"/lens-id.txt");lensFile>>fixtureLens;
        if(fixtureLens.empty()) {
            std::ifstream recipeFile(root+"/recipe.json");
            std::string recipe((std::istreambuf_iterator<char>(recipeFile)),std::istreambuf_iterator<char>());
            std::smatch match;
            if(std::regex_search(recipe,match,std::regex(R"json("lensIdentifier"\s*:\s*"([^"]+)")json"))) fixtureLens=match[1];
        }
        check(!fixtureLens.empty(),"fixture lens identity missing: supply recipe.json or lens-id.txt; never guess a camera");
        IspFrameMetadata meta;meta.singleShotRaw=true;meta.cfaPattern=cfa;meta.isRaw10=!format;
        meta.captureSensitivityIso=iso;meta.captureExposureTimeNs=int64_t(exposure*1e6f);meta.calibration.spectraProcessingMode=0;
        meta.calibration.lensId=fixtureLens;meta.calibration.hasWbGains=true;meta.calibration.hasColorMatrix=true;
        meta.calibration.colorMatrixFromMetadata=true;meta.calibration.hasWhiteLevel=true;meta.calibration.hasBlackLevel=true;
        meta.calibration.calibrationApplied=true;meta.calibration.effectiveWhiteLevel=white;meta.calibration.postRawSensitivityBoost=100;
        NativeRenderQualityConfig quality;quality.wbRed=wb[0];quality.wbGreenEven=wb[1];quality.wbGreenOdd=wb[2];quality.wbBlue=wb[3];
        quality.wbFromMetadata=true;quality.colorMatrixFromMetadata=true;quality.captureSensitivityIso=iso;
        for(int i=0;i<4;++i){meta.calibration.effectiveWbGains[i]=wb[i];meta.calibration.effectiveBlackLevels[i]=black[i];}
        for(int i=0;i<9;++i){quality.colorMatrix[i]=matrix[i];meta.calibration.effectiveColorMatrix[i]=matrix[i];}
        std::ifstream capture(root+"/capture-metadata.txt");
        if(capture){capture>>meta.calibration.signalModelConfidence;for(int i=0;i<4;++i)capture>>meta.calibration.effectiveS[i]>>meta.calibration.effectiveO[i];
            capture>>meta.lensShadingRows>>meta.lensShadingColumns;meta.lensShadingMap.resize(size_t(meta.lensShadingRows)*meta.lensShadingColumns*4);
            for(auto&v:meta.lensShadingMap)capture>>v;check(!capture.fail(),"capture metadata");meta.lensShadingFromMetadata=true;meta.calibration.physicalNoiseJniPayloadReceived=true;}
        int rotation=90;
        std::ifstream frozen(root+"/replay-context.txt");
        if(frozen) {
            frozen>>rotation>>meta.captureExposureTimeNs>>meta.demosaicFocusStabilityKnown
                  >>meta.demosaicFocusStabilityConfidence>>meta.demosaicFocusSharpConfidence
                  >>meta.demosaicFocusMotionRisk>>meta.demosaicFocusVelocityDioptersPerSec
                  >>meta.demosaicPredictiveAfConfidence;
            check(!frozen.fail(),"frozen replay context");
        }
        auto acquireThermal=reinterpret_cast<AThermalManager*(*)()>(dlsym(RTLD_DEFAULT,"AThermal_acquireManager"));
        auto getThermal=reinterpret_cast<AThermalStatus(*)(AThermalManager*)>(dlsym(RTLD_DEFAULT,"AThermal_getCurrentThermalStatus"));
        auto releaseThermal=reinterpret_cast<void(*)(AThermalManager*)>(dlsym(RTLD_DEFAULT,"AThermal_releaseManager"));
        AThermalManager* thermal=acquireThermal&&getThermal&&releaseThermal?acquireThermal():nullptr;
        std::mt19937 random(20261007);std::vector<unsigned char> reference[3];
        std::ofstream csv(root+"/warm-benchmark.csv");
        csv<<"round,mode,warmup,thermal,thermalAfter,rssBeforeKb,rssAfterKb,nativeBeforeBytes,nativeAfterBytes,fixtureReadMs,normalizeMs,pipelineMs,totalMs,bitexact\n";
        for(int round=0;round<(replayOnly?2:measured+3);++round) {
            std::array<int,3> order{0,1,2};std::shuffle(order.begin(),order.end(),random);
            for(int mode:order) {
                int thermalStatus=thermal?int(getThermal(thermal)):-1;
                long before=rssKb();auto heapBefore=mallinfo().uordblks;
                auto totalStart=Clock::now();auto normalizeStart=Clock::now();
                auto linear=normalizeRawForJpeg(raw.data(),info);check(linear.diagnostics.valid,"normalization");double normalizeMs=ms(normalizeStart);
                meta.requestedDemosaicMode=mode;std::string debug;auto pipelineStart=Clock::now();
                auto jpeg=IspCore::renderRawBaselineJpeg(std::move(linear),meta,quality,&debug,rotation);
                double pipelineMs=ms(pipelineStart),totalMs=ms(totalStart);check(!jpeg.empty(),"empty JPEG");
                int thermalAfter=thermal?int(getThermal(thermal)):-1;
                bool bitexact=reference[mode].empty()||reference[mode]==jpeg;
                if(reference[mode].empty())reference[mode]=jpeg;
                long after=rssKb();auto heapAfter=mallinfo().uordblks;
                // Exports happen after measured wall time. Diagnostics construction is included.
                std::string stem="warm-"+std::to_string(round)+"-"+std::to_string(mode);
                std::ofstream(root+"/"+stem+".txt")<<debug;
                if(round==3||replayOnly){std::ofstream out(root+"/"+(replayOnly?stem:"warm-mode-"+std::to_string(mode))+".jpg",std::ios::binary);out.write(reinterpret_cast<const char*>(jpeg.data()),jpeg.size());}
                csv<<round<<','<<mode<<','<<(round<3)<<','<<thermalStatus<<','<<thermalAfter<<','<<before<<','<<after<<','<<heapBefore<<','<<heapAfter<<','<<fixtureReadMs<<','<<normalizeMs<<','<<pipelineMs<<','<<totalMs<<','<<bitexact<<'\n';csv.flush();
                std::cout<<stem<<" thermal="<<thermalStatus<<" totalMs="<<totalMs<<" bitexact="<<bitexact<<std::endl;
                check(bitexact,"warm JPEG nondeterminism");
            }
        }
        if(thermal)releaseThermal(thermal);runtime.shutdown();return 0;
    } catch(const std::exception& e) { std::cerr<<"QUALIFICATION FAILED: "<<e.what()<<std::endl;return 1; }
}
