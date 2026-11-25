// SF2-D07: opt-in on-device Vulkan-vs-CPU integration qualification.
// Not part of any capture pipeline. Only explicitly called from debug receiver.
#include "SingleFrameIntegrationQualification.h"
#include "PerceptualDetailPolicy.h"
#include "tests/Sf2PerformanceLinear.h"
#include "vulkan/VulkanRuntime.h"
#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(BNCAM_ENABLE_GPU_BENCHMARKS) && BNCAM_ENABLE_GPU_BENCHMARKS
extern "C" JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_setOriginalSingleFrameDetailForReplayNative(
        JNIEnv*, jobject, jboolean useOriginal) {
    bncam::perceptual_detail::replayOriginalSingleFrameDetail = useOriginal == JNI_TRUE;
}
#endif

#ifndef NDEBUG
namespace bncam::luma {
thread_local bool benchmarkReadLinear = false;
namespace {
thread_local std::vector<float> benchmarkOriginalRgb;
thread_local std::string benchmarkLinearReport;
}

void recordBenchmarkLinear(Stage stage,const std::vector<float>& rgb) {
    if(stage==Stage::GUIDED_V2_BALANCED_REFERENCE) {
        benchmarkOriginalRgb=rgb;
        return;
    }
    if(stage!=Stage::GUIDED_V2_BALANCED || rgb.empty() || rgb.size()!=benchmarkOriginalRgb.size())
        return;
    std::vector<float> pixelDeltas(rgb.size()/3u);
    double sum=0.0;
    float maximum=0.f,opponentMaximum=0.f;
    std::size_t changed=0;
    for (std::size_t pixel=0;pixel<pixelDeltas.size();++pixel) {
        const auto i=3u*pixel;
        const Pixel before{benchmarkOriginalRgb[i],benchmarkOriginalRgb[i+1],benchmarkOriginalRgb[i+2]};
        const Pixel after{rgb[i],rgb[i+1],rgb[i+2]};
        if(!chroma::finite(before)||!chroma::finite(after)) return;
        for(std::size_t c=0;c<3;++c) {
            const float delta=std::abs(before[c]-after[c]);
            sum+=delta;
            pixelDeltas[pixel]=std::max(pixelDeltas[pixel],delta);
        }
        changed+=pixelDeltas[pixel]!=0.f;
        maximum=std::max(maximum,pixelDeltas[pixel]);
        const auto prior=chroma::opponent(before), current=chroma::opponent(after);
        opponentMaximum=std::max({opponentMaximum,std::abs(prior[1]-current[1]),
                                 std::abs(prior[2]-current[2])});
    }
    const auto p99=pixelDeltas.begin()+(pixelDeltas.size()*99u-1u)/100u;
    std::nth_element(pixelDeltas.begin(),p99,pixelDeltas.end());
    std::ostringstream report;
    report<<std::setprecision(10)<<"{\"pixels\":"<<pixelDeltas.size()
          <<",\"changedPixels\":"<<changed<<",\"meanRgbDifference\":"<<sum/rgb.size()
          <<",\"p99PixelDifference\":"<<*p99<<",\"maxRgbDifference\":"<<maximum
          <<",\"maxOpponentDifference\":"<<opponentMaximum<<"}";
    benchmarkLinearReport=report.str();
}
}
#endif

extern "C" JNIEXPORT void JNICALL
Java_com_bncam_core_engine_ImageUtils_setSf2BenchmarkStageNative(JNIEnv*,jobject,jint stage,jboolean readLinear) {
#ifndef NDEBUG
    using bncam::luma::Stage;
    bncam::luma::benchmarkStage = stage == static_cast<int>(Stage::OFF) ||
        stage == static_cast<int>(Stage::GUIDED_V2_BALANCED) ||
        stage == static_cast<int>(Stage::GUIDED_V2_BALANCED_REFERENCE) ? stage : -1;
    bncam::luma::benchmarkReadLinear = readLinear == JNI_TRUE;
    if(stage < 0 || (readLinear == JNI_TRUE && stage == static_cast<int>(Stage::GUIDED_V2_BALANCED_REFERENCE))) {
        std::vector<float>().swap(bncam::luma::benchmarkOriginalRgb);
        bncam::luma::benchmarkLinearReport.clear();
    }
#else
    (void)stage;
    (void)readLinear;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_getSf2BenchmarkLinearReportNative(JNIEnv* env,jobject) {
#ifndef NDEBUG
    return env->NewStringUTF(bncam::luma::benchmarkLinearReport.c_str());
#else
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bncam_core_engine_ImageUtils_validateSf2IntegrationNative(JNIEnv* env,jobject) {
#ifndef NDEBUG
    using bncam::luma::Stage;
    namespace q=bncam::sf2::qualification;
    try {
        const auto input=q::fixture();
        static_assert(sizeof(q::Pixel)==3*sizeof(float),"RGB qualification fixture must be tight");
        if(input.size()!=std::size_t(q::kWidth*q::kHeight))
            throw std::runtime_error("BAD_QUALIFICATION_FIXTURE");
        std::ostringstream report;
        report<<std::fixed<<std::setprecision(9)
              <<"sf2QualificationVersion=1;source=SYNTHETIC_IMMUTABLE_RGB"
              <<";gpuValidation=DEVICE_REQUIRED;pixelTolerance="<<q::kGpuParityTolerance<<"\n";
        std::vector<float> initialOff;
        float maxDifference=0.f;
        float maxOpponentError=0.f;
        int stageCount=0;
        const std::array<std::pair<Stage,bool>,7> matrix{{
            {Stage::OFF,false},
            {Stage::LEGACY_REFERENCE,false},
            {Stage::GUIDED_V2,false},
            {Stage::GUIDED_V2_BALANCED,false},
            {Stage::GUIDED_V2_FINE_COARSE,false},
            {Stage::GUIDED_V2_CONFIDENCE,false},
            {Stage::GUIDED_V2_CONFIDENCE,true}
        }};
        for(const auto& entry:matrix) {
            bncam::vulkan::SpectraResidentColorTransformRequest r{};
            r.frameWidth=q::kWidth;
            r.frameHeight=q::kHeight;
            r.rgbData=input.front().data();
            r.rowStrideFloats=q::kWidth*3;
            r.baselinePhysicalLuma=entry.first==Stage::OFF?bncam::luma::Model{}:q::noise();
            r.lumaStage=entry.first;
            r.lumaDemosaicKernelProxyAvailable=entry.second;
            r.physicalChromaValidationOnly=true; // exact linear RGB: no AWB/CCM
            const auto gpu=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(r);
            if(!gpu.success) {
                report<<"stage="<<static_cast<unsigned>(entry.first)
                      <<";reason="<<gpu.status<<":"<<gpu.failureReason<<"\n";
                throw std::runtime_error("GPU_STAGE_DISPATCH_FAILED");
            }
            const bool coarse=entry.first==Stage::GUIDED_V2_FINE_COARSE ||
                    entry.first==Stage::GUIDED_V2_CONFIDENCE;
            if(gpu.baselinePhysicalLumaApplied==(entry.first==Stage::OFF) ||
               gpu.guidedCoarseApplied!=coarse ||
               gpu.guidedConfidenceGateApplied!=(entry.first==Stage::GUIDED_V2_CONFIDENCE))
                throw std::runtime_error("GPU_STAGE_ROUTING_MISMATCH");
            const auto expected=q::cpuReference(input,entry.first,entry.second);
            const auto observed=q::compare(expected,gpu.outputRgb);
            if(!observed.valid || !observed.finite ||
               observed.maxAbsoluteRgb>q::kGpuParityTolerance ||
               observed.maxOpponentDelta>q::kGpuParityTolerance) {
                report<<"stage="<<static_cast<unsigned>(entry.first)
                      <<";maxRgbDifference="<<observed.maxAbsoluteRgb
                      <<";maxOpponentDifference="<<observed.maxOpponentDelta<<"\n";
                throw std::runtime_error("CPU_GPU_PARITY_FAILURE");
            }
            if(entry.first==Stage::OFF)initialOff=gpu.outputRgb;
            maxDifference=std::max(maxDifference,observed.maxAbsoluteRgb);
            maxOpponentError=std::max(maxOpponentError,observed.maxOpponentDelta);
            report<<"stage="<<static_cast<unsigned>(entry.first)
                  <<";kernelProxy="<<(entry.second?"true":"false")
                  <<";gpuKernelMs="<<gpu.kernelMs
                  <<";gpuTotalMs="<<gpu.totalMs
                  <<";coarse="<<(gpu.guidedCoarseApplied?"true":"false")
                  <<";gate="<<(gpu.guidedConfidenceGateApplied?"true":"false")
                  <<";maxRgbDifference="<<observed.maxAbsoluteRgb
                  <<";maxOpponentDifference="<<observed.maxOpponentDelta
                  <<"\n";
            ++stageCount;
        }
        // Ensure the identity route remains pixel-identical AFTER coarse and
        // gated dispatches; this detects stale SSBO/descriptor/global-stage data.
        bncam::vulkan::SpectraResidentColorTransformRequest off{};
        off.frameWidth=q::kWidth;off.frameHeight=q::kHeight;
        off.rgbData=input.front().data();off.rowStrideFloats=q::kWidth*3;
        off.physicalChromaValidationOnly=true;
        const auto repeated=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(off);
        if(!repeated.success || repeated.outputRgb.size()!=initialOff.size())
            throw std::runtime_error("POST_STAGE_OFF_GPU_DISPATCH_FAILED");
        float repeatDiff=0.f;
        for(std::size_t i=0;i<initialOff.size();++i)
            repeatDiff=std::max(repeatDiff,std::abs(initialOff[i]-repeated.outputRgb[i]));
        if(!(repeatDiff<=2.e-6f))
            throw std::runtime_error("POST_STAGE_OFF_PIXEL_IDENTITY_FAILED");
#if defined(BNCAM_ENABLE_GPU_BENCHMARKS)
        const std::array<float,6> spatialSigma{0.72f,0.91f,1.13f,1.37f,1.62f,1.94f};
        for (const bool spatial : {false,true}) {
            auto balanced=off;
            auto reference=off;
            balanced.lumaStage=Stage::GUIDED_V2_BALANCED;
            reference.lumaStage=Stage::GUIDED_V2_BALANCED_REFERENCE;
            balanced.baselinePhysicalLuma=reference.baselinePhysicalLuma=q::noise();
            if (spatial) {
                balanced.physicalSpatialSigma=reference.physicalSpatialSigma=spatialSigma.data();
                balanced.physicalSpatialColumns=reference.physicalSpatialColumns=3;
                balanced.physicalSpatialRows=reference.physicalSpatialRows=2;
            }
            const auto original=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(reference);
            const auto optimized=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(balanced);
            if (!original.success || !optimized.success || !original.baselinePhysicalLumaApplied ||
                !optimized.baselinePhysicalLumaApplied || original.outputRgb.size()!=input.size()*3u)
                throw std::runtime_error("BALANCED_COMPARISON_DISPATCH_FAILED");
            std::vector<q::Pixel> originalRgb(input.size());
            std::memcpy(originalRgb.data(),original.outputRgb.data(),original.outputRgb.size()*sizeof(float));
            const auto delta=q::compare(originalRgb,optimized.outputRgb);
            if (!delta.valid || !delta.finite || delta.maxAbsoluteRgb>q::kGpuParityTolerance ||
                delta.maxOpponentDelta>q::kGpuParityTolerance)
                throw std::runtime_error("BALANCED_REFERENCE_PIXEL_DIFFERENCE");
            std::vector<float> pixelDeltas(input.size());
            for (std::size_t i=0;i<input.size();++i)
                for (std::size_t c=0;c<3;++c)
                    pixelDeltas[i]=std::max(pixelDeltas[i],
                        std::abs(originalRgb[i][c]-optimized.outputRgb[3*i+c]));
            const auto p99=pixelDeltas.begin()+(pixelDeltas.size()*99u-1u)/100u;
            std::nth_element(pixelDeltas.begin(),p99,pixelDeltas.end());
            report<<"balancedReferenceSpatial="<<(spatial?"true":"false")
                  <<";meanRgbDifference="<<delta.meanAbsoluteRgb
                  <<";p99PixelDifference="<<*p99
                  <<";maxRgbDifference="<<delta.maxAbsoluteRgb
                  <<";maxOpponentDifference="<<delta.maxOpponentDelta
                  <<";changedPixelsAbove1e-7="<<delta.changedPixels<<"\n";
        }
        auto invalid=off;
        invalid.lumaStage=Stage::GUIDED_V2_BALANCED;
        invalid.baselinePhysicalLuma={-1.f,0.9f};
        const auto bypass=bncam::vulkan::VulkanRuntime::instance().executeSpectraResidentAwbCcm(invalid);
        if (!bypass.success || bypass.baselinePhysicalLumaApplied || bypass.outputRgb!=initialOff)
            throw std::runtime_error("BALANCED_INVALID_MODEL_BYPASS_FAILED");
        report<<"balancedInvalidModelOffIdentity=true\n";
#endif
        report<<"testedStages="<<stageCount
              <<";maxRgbDifference="<<maxDifference
              <<";maxOpponentDifference="<<maxOpponentError
              <<";offRepeatMaxDifference="<<repeatDiff
              <<";allPassed=true\n";
        return env->NewStringUTF(report.str().c_str());
    } catch(const std::exception& e) {
        const std::string failure=std::string("sf2QualificationVersion=1;allPassed=false;reason=")+e.what();
        return env->NewStringUTF(failure.c_str());
    }
#else
    return env->NewStringUTF("DEBUG_ONLY");
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bncam_core_engine_ImageUtils_getPhysicalLumaStageDebug(JNIEnv*,jobject) {
#ifndef NDEBUG
    return static_cast<jint>(bncam::luma::selectedStage.load(std::memory_order_relaxed));
#else
    return 0;
#endif
}
