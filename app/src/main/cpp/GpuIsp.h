#pragma once
#include <opencv2/opencv.hpp>
#include "NativeRenderQualityConfig.h"

class GpuIsp {
public:
    static bool initializeEngine();
    static void releaseEngine();
    static bool applyBentoHighlightRecovery(cv::Mat& rgb32f);
    static bool processToBgr(
            const cv::Mat& inputMat, cv::Mat& bgr8, const NativeRenderQualityConfig& cfg,
            bool applyMatrix, bool is8BitInput
    );

private:
    // Voorkom instantiatie, het is een statische manager
    GpuIsp() = delete;

    // Interne status voor de GPU context en gecompileerde shaders
    struct EngineState {
        bool isInitialized = false;
        void* display = nullptr; // EGLDisplay
        void* context = nullptr; // EGLContext
        void* surface = nullptr; // EGLSurface

        // Gecachete Shader Programs
        unsigned int progExtract = 0;
        unsigned int progBlurX = 0;
        unsigned int progBlurY = 0;
        unsigned int progReconstruct = 0;
    };

    static EngineState s_state;
};