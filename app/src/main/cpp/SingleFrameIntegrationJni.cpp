// SF2-D07: opt-in on-device Vulkan-vs-CPU integration qualification.
// Not part of any capture pipeline. Only explicitly called from debug receiver.
#include "SingleFrameIntegrationQualification.h"
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
