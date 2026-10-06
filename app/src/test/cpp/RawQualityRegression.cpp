// Device regression: real Vulkan RAW-finalize against the Camera2 gain contract.
#include "Demosaic.h"
#include "LensShadingMetadata.h"
#include "vulkan/VulkanRuntime.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>
using namespace bncam::vulkan;
#ifdef BNCAM_TEST_CPU_LSC
extern "C" void rawAuditCpuLsc(const float*,std::size_t,int,int,int,int,float*);
#endif
static void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
static void routing() {
    auto malvar=resolveDemosaicMode(1), amaze=resolveDemosaicMode(2), neural=resolveDemosaicMode(3);
    require(malvar.algorithm==DemosaicAlgorithm::Malvar2004 && !malvar.fallbackOccurred,"explicit Malvar");
    require(amaze.algorithm==DemosaicAlgorithm::Amaze && !amaze.fallbackOccurred,"explicit AMaZE");
    require(neural.requestedMode==DemosaicMode::BncNeural && neural.algorithm==DemosaicAlgorithm::Malvar2004 &&
            neural.fallbackOccurred && neural.fallbackReason=="BNC_NEURAL_BACKEND_UNAVAILABLE","neural fallback");
    require(resolveDemosaicMode(-1).requestedMode==DemosaicMode::AutoHybrid,"missing native automatic default");
    require(resolveDemosaicMode(0).requestedMode==DemosaicMode::AutoHybrid,"explicit Auto Hybrid");
    for (int i=0;i<20;++i) {
        AutoDemosaicSceneMetrics m{};m.valid=true;m.sampleCount=4096;m.medianSignal=.1f+.02f*i;
        m.p90Gradient=.01f*i;m.meanGradient=.005f*i;m.edgeFraction=.015f*i;m.coherentEdgeFraction=.01f*i;
        AutoDemosaicContext c{};c.captureIso=100+i*200;
        auto a=resolveDemosaicForSceneMetrics(0,m,c);
        auto missing=resolveDemosaicForSceneMetrics(-1,m,c);
        require(missing.requestedMode==DemosaicMode::AutoHybrid && missing.autoHybridExecution &&
                missing.autoMalvarPrior==a.autoMalvarPrior && missing.autoAmazePrior==a.autoAmazePrior &&
                missing.autoBncNeuralPrior==0,"missing native setting executes Auto routing");
        require(a.autoHybridExecution && a.autoBncNeuralPrior==0 &&
                std::abs(a.autoMalvarPrior+a.autoAmazePrior-1)<1.e-6f &&
                a.algorithm!=DemosaicAlgorithm::BncNeural,"two active Auto routes");
    }
}
static void gainTest(VulkanRuntime& runtime,const std::vector<float>& map,int cols,int rows,int w,int h,bool malformed=false) {
    std::vector<float> raw(size_t(w)*h,.01f);
    SpectraRawFinalizeRequest q{};q.mosaicData=raw.data();q.frameWidth=w;q.frameHeight=h;q.rowStrideFloats=w;
    q.sensorCfaPattern=3;q.effectiveCfaPattern=3;q.neutralDefaultRaw=true;q.preserveExactCamera2Pair=true;
    q.allowGreenResidualCorrection=false;q.deferFullFrameReadback=false;
    q.lensShadingMap=map.data();q.lensShadingColumns=cols;q.lensShadingRows=rows;
    q.lensShadingElementCount=malformed?1:map.size();
    auto result=runtime.executeSpectraRawFinalize(q);
    require(result.success && result.outputMosaic.size()==raw.size(),"GPU finalize/readback");
#ifdef BNCAM_TEST_CPU_LSC
    std::vector<float> cpu(raw.size());
    rawAuditCpuLsc(map.data(),q.lensShadingElementCount,cols,rows,w,h,cpu.data());
    double cpuError=0;
    for(size_t i=0;i<raw.size();++i) cpuError=std::max(cpuError,double(std::abs(cpu[i]-result.outputMosaic[i])/.01f));
    require(cpuError<2.e-5,"actual CPU/GPU LSC parity");
    std::cout<<"actualCpuGpuGainMaxAbs="<<cpuError<<" ";
#endif
    double maxError=0;float minGain=std::numeric_limits<float>::max(),maxGain=0;
    auto safe=[](float v){return std::isfinite(v)&&v>=1 ? v : 1.f;};
    for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
        float gx=float(x)*(cols-1)/(w-1),gy=float(y)*(rows-1)/(h-1);
        int x0=int(gx),y0=int(gy),x1=std::min(x0+1,cols-1),y1=std::min(y0+1,rows-1);
        int c=(y&1)?((x&1)?0:2):((x&1)?1:3);
        auto node=[&](int xx,int yy){return safe(map[(yy*cols+xx)*4+c]);};
        float top=node(x0,y0)+(node(x1,y0)-node(x0,y0))*(gx-x0);
        float bottom=node(x0,y1)+(node(x1,y1)-node(x0,y1))*(gx-x0);
        float gain=malformed?1:top+(bottom-top)*(gy-y0);
        float applied=result.outputMosaic[size_t(y)*w+x]/.01f;
        maxError=std::max(maxError,double(std::abs(applied-gain)));
        minGain=std::min(minGain,applied);maxGain=std::max(maxGain,applied);
    }
    require(maxError<2.e-5,"CPU-contract/GPU gain parity");
    require(result.lensShadingApplied!=malformed,"malformed map rejected");
    std::cout<<"gainParity maxAbs="<<maxError<<" appliedMin="<<minGain<<" appliedMax="<<maxGain<<" malformed="<<malformed<<"\n";
}
int main(int argc,char**argv) {
 try {
    routing();
    if(argc==2 && std::string(argv[1])=="--routing-only") {
        std::cout<<"DEMOSAIC_DEFAULT_ROUTING_PASS\n";return 0;
    }
    auto& runtime=VulkanRuntime::instance();runtime.initialize({});
    std::vector<float> safety={1,4.74121f,11.13184f,32,std::nanf(""),INFINITY,-2,.9f,0,2,8,16,4,5,6,7};
    for(float v:safety) require(bncam::lsc::safeGain(v)==(std::isfinite(v)&&v>=1?v:1),"CPU safety contract");
    gainTest(runtime,safety,2,2,128,96);gainTest(runtime,safety,2,2,128,96,true);
    for(int i=1;i<argc;++i) {
        std::ifstream f(argv[i]);int rows,cols;f>>rows>>cols;std::vector<float> map(size_t(rows)*cols*4);
        for(auto&v:map) f>>v;require(!f.fail(),"map fixture");
        // Actual capture dimensions supplied after gains, for full-grid parity statistics.
        int w,h;f>>w>>h;require(!f.fail(),"map dimensions");gainTest(runtime,map,cols,rows,w,h);
    }
    runtime.shutdown();std::cout<<"RAW_QUALITY_REGRESSION_PASS\n";return 0;
 } catch(const std::exception& e) { std::cerr<<e.what()<<"\n";return 1; }
}
