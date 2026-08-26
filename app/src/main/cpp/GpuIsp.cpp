#include "GpuIsp.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <android/log.h>
#include <vector>
#include <chrono>

#define GPU_LOG_TAG "BnCam_GpuIsp"
#define GPU_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GPU_LOG_TAG, __VA_ARGS__)
#define GPU_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GPU_LOG_TAG, __VA_ARGS__)

GpuIsp::EngineState GpuIsp::s_state;

namespace {
    // =========================================================================
    // SHADER DEFINITIES
    // =========================================================================

    const char* BENTO_EXTRACT_SRC = R"GLSL(#version 310 es
    precision highp float;
    layout(local_size_x = 16, local_size_y = 16) in;
    layout(binding = 0) uniform sampler2D texIn;
    layout(binding = 1, rgba16f) uniform writeonly highp image2D imgChroma;

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 outSize = imageSize(imgChroma);
        if(pos.x >= outSize.x || pos.y >= outSize.y) return;

        vec2 uv = (vec2(pos) + 0.5) / vec2(outSize);
        vec3 color = texture(texIn, uv).rgb;

        float r = max(1.0, color.r);
        float g = max(1.0, color.g);
        float b = max(1.0, color.b);
        float maxVal = max(r, max(g, b));

        vec4 outColor;
        if(maxVal > 63500.0) {
            outColor = vec4(1.0, 1.0, 1.0, 1.0);
        } else {
            outColor = vec4(r/maxVal, g/maxVal, b/maxVal, 1.0);
        }
        imageStore(imgChroma, pos, outColor);
    }
    )GLSL";

    const char* BENTO_BLUR_X_SRC = R"GLSL(#version 310 es
    precision highp float;
    layout(local_size_x = 16, local_size_y = 16) in;
    layout(binding = 0) uniform sampler2D texIn;
    layout(binding = 1, rgba16f) uniform writeonly highp image2D imgOut;

    const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = imageSize(imgOut);
        if(pos.x >= size.x || pos.y >= size.y) return;

        vec2 uv = (vec2(pos) + 0.5) / vec2(size);
        vec2 offset = vec2(1.0 / float(size.x), 0.0);

        vec3 sum = texture(texIn, uv).rgb * weights[0];
        for(int i = 1; i < 5; ++i) {
            sum += texture(texIn, uv + offset * float(i)).rgb * weights[i];
            sum += texture(texIn, uv - offset * float(i)).rgb * weights[i];
        }
        imageStore(imgOut, pos, vec4(sum, 1.0));
    }
    )GLSL";

    const char* BENTO_BLUR_Y_SRC = R"GLSL(#version 310 es
    precision highp float;
    layout(local_size_x = 16, local_size_y = 16) in;
    layout(binding = 0) uniform sampler2D texIn;
    layout(binding = 1, rgba16f) uniform writeonly highp image2D imgOut;

    const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = imageSize(imgOut);
        if(pos.x >= size.x || pos.y >= size.y) return;

        vec2 uv = (vec2(pos) + 0.5) / vec2(size);
        vec2 offset = vec2(0.0, 1.0 / float(size.y));

        vec3 sum = texture(texIn, uv).rgb * weights[0];
        for(int i = 1; i < 5; ++i) {
            sum += texture(texIn, uv + offset * float(i)).rgb * weights[i];
            sum += texture(texIn, uv - offset * float(i)).rgb * weights[i];
        }
        imageStore(imgOut, pos, vec4(sum, 1.0));
    }
    )GLSL";

    const char* BENTO_RECONSTRUCT_SRC = R"GLSL(#version 310 es
    precision highp float;
    layout(local_size_x = 16, local_size_y = 16) in;
    layout(binding = 0) uniform sampler2D texOrig;
    layout(binding = 1) uniform sampler2D texBlurredChroma;
    layout(binding = 2, rgba32f) uniform writeonly highp image2D imgOut;

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = imageSize(imgOut);
        if(pos.x >= size.x || pos.y >= size.y) return;

        vec2 uv = (vec2(pos) + 0.5) / vec2(size);
        vec3 color = texture(texOrig, uv).rgb;

        float r = color.r; float g = color.g; float b = color.b;
        float maxVal = max(r, max(g, b));

        if(maxVal > 62000.0) {
            vec3 targetRatio = texture(texBlurredChroma, uv).rgb;
            float refBase = (g < 64000.0) ? (g / max(0.01, targetRatio.g)) : maxVal;
            float vR = refBase * targetRatio.r;
            float vG = refBase * targetRatio.g;
            float vB = refBase * targetRatio.b;
            float vMax = max(vR, max(vG, vB));

            if(vMax > 65000.0) {
                float gain = 65000.0 / vMax;
                color = vec3(vR * gain, vG * gain, vB * gain);
            } else {
                color = vec3(vR, vG, vB);
            }
        }
        imageStore(imgOut, pos, vec4(color, 1.0));
    }
    )GLSL";

    const char* COMPUTE_SHADER_SRC = R"GLSL(#version 310 es
    precision highp float;
    precision highp int;
    precision highp image2D;
    precision highp usampler2D;

    layout(local_size_x = 16, local_size_y = 16) in;

    layout(binding = 0) uniform highp usampler2D texIn;
    layout(binding = 1, rgba8) uniform writeonly highp image2D imgOut;

    // Parameters
    uniform mat3 u_colorMatrix;
    uniform bool u_applyMatrix;
    uniform float u_bitDepth;
    uniform int u_isBgr;

    // Dynamic exposure & normalization uniforms
    uniform float u_exposureGain;
    uniform vec3 u_dynamicBlackLevel;
    uniform float u_dynamicWhiteLevel;

    float srgbEncode(float v) {
        v = clamp(v, 0.0f, 1.0f);
        if (v <= 0.0031308) return 12.92 * v;
        return 1.055 * pow(v, 1.0 / 2.4) - 0.055;
    }

    float applyHighlightRollOff(float val) {
        float threshold = 0.7;
        if (val <= threshold) {
            return val;
        } else {
            float diff = val - threshold;
            float range = 1.0 - threshold;
            return threshold + range * (diff / (diff + range));
        }
    }

    float ign(vec2 p) {
        vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
        return fract(magic.z * fract(dot(p, magic.xy)));
    }

    vec3 fetchInputRgb(ivec2 p, ivec2 size) {
        ivec2 safePos = clamp(p, ivec2(0), size - ivec2(1));
        vec3 c = vec3(texelFetch(texIn, safePos, 0).rgb) / u_bitDepth;
        if (u_isBgr == 1) c = c.bgr;
        return c;
    }

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = imageSize(imgOut);
        if(pos.x >= size.x || pos.y >= size.y) return;

        vec3 rgb = fetchInputRgb(pos, size);

        if (u_isBgr == 1) {
            // YUV direct copy
            // Add subtle dither to prevent quantization banding during 8-bit output conversion
            rgb = clamp(rgb + vec3((ign(vec2(pos)) - 0.5) / 255.0), vec3(0.0), vec3(1.0));
            imageStore(imgOut, pos, vec4(rgb, 1.0));
            return;
        }

        // RAW pipeline
        // Input rgb is already normalized on CPU (black level subtracted, range-normalized, LSC and WB corrected)
        vec3 normalized = clamp(rgb.rgb, vec3(0.0), vec3(1.0));

        // 3. Color correction matrix (CCM) multiplication and strict post-CCM clamp
        vec3 corrected = normalized;
        if (u_applyMatrix) {
            corrected = max(u_colorMatrix * normalized, vec3(0.0));
        }

        // 4. Exposure gain. Legacy Shadow Lift / Toe Exponent / Soft Black controls were
        // removed; GTM/LTM and the Lightroom profile tone model are the sole tone authority.
        vec3 exposed = corrected * u_exposureGain;

        // 5. Highlight roll-off
        rgb.r = applyHighlightRollOff(exposed.r);
        rgb.g = applyHighlightRollOff(exposed.g);
        rgb.b = applyHighlightRollOff(exposed.b);

        // Ensure no out of bounds before gamma encoding
        rgb = clamp(rgb, vec3(0.0), vec3(1.0));

        // 9. Standard sRGB gamma encoding
        rgb.r = srgbEncode(rgb.r);
        rgb.g = srgbEncode(rgb.g);
        rgb.b = srgbEncode(rgb.b);

        // 10. Dithering
        rgb = clamp(rgb + vec3((ign(vec2(pos)) - 0.5) / 255.0), vec3(0.0), vec3(1.0));

        imageStore(imgOut, pos, vec4(rgb, 1.0));
    }
    )GLSL";

    // =========================================================================
    // UTILITY FUNCTIES
    // =========================================================================

    GLuint compileShader(GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint success;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
        if (!success) {
            char infoLog[512];
            glGetShaderInfoLog(shader, 512, nullptr, infoLog);
            GPU_LOGE("Shader Compile Error: %s", infoLog);
            return 0;
        }
        return shader;
    }

    GLuint createComputeProgram(const char* source) {
        GLuint prog = glCreateProgram();
        GLuint shader = compileShader(GL_COMPUTE_SHADER, source);
        if (shader == 0) return 0;
        glAttachShader(prog, shader);
        glLinkProgram(prog);
        glDeleteShader(shader);
        return prog;
    }
}

// =========================================================================
// LIFECYCLE & HIGHLIGHT RECOVERY
// =========================================================================

bool GpuIsp::initializeEngine() {
    if (s_state.isInitialized) return true;

    auto startInit = std::chrono::steady_clock::now();

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        GPU_LOGE("EGL Geen display gevonden.");
        return false;
    }
    eglInitialize(display, nullptr, nullptr);

    const EGLint configAttribs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE };
    EGLConfig config; EGLint numConfigs;
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);

    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

    if (eglMakeCurrent(display, surface, surface, context) == EGL_FALSE) {
        GPU_LOGE("Kon EGL context niet actief maken.");
        return false;
    }

    // Compileer alle shaders één keer en bewaar de ID's
    s_state.progExtract = createComputeProgram(BENTO_EXTRACT_SRC);
    s_state.progBlurX = createComputeProgram(BENTO_BLUR_X_SRC);
    s_state.progBlurY = createComputeProgram(BENTO_BLUR_Y_SRC);
    s_state.progReconstruct = createComputeProgram(BENTO_RECONSTRUCT_SRC);

    if (!s_state.progExtract || !s_state.progBlurX || !s_state.progBlurY || !s_state.progReconstruct) {
        GPU_LOGE("Fatale fout: Shaders konden niet gecompileerd worden tijdens init.");
        releaseEngine();
        return false;
    }

    s_state.display = display;
    s_state.context = context;
    s_state.surface = surface;
    s_state.isInitialized = true;

    auto endInit = std::chrono::steady_clock::now();
    GPU_LOGI("GPU Engine Persistente Init Klaar in %.2f ms",
             std::chrono::duration<float, std::milli>(endInit - startInit).count());

    return true;
}

void GpuIsp::releaseEngine() {
    if (!s_state.isInitialized) return;

    auto display = static_cast<EGLDisplay>(s_state.display);
    auto context = static_cast<EGLContext>(s_state.context);
    auto surface = static_cast<EGLSurface>(s_state.surface);

    eglMakeCurrent(display, surface, surface, context);

    if (s_state.progExtract) glDeleteProgram(s_state.progExtract);
    if (s_state.progBlurX) glDeleteProgram(s_state.progBlurX);
    if (s_state.progBlurY) glDeleteProgram(s_state.progBlurY);
    if (s_state.progReconstruct) glDeleteProgram(s_state.progReconstruct);

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);

    s_state = EngineState();
    GPU_LOGI("GPU Engine Succesvol afgesloten en VRAM vrijgegeven.");
}

bool GpuIsp::applyBentoHighlightRecovery(cv::Mat& rgb32f) {
    if (!s_state.isInitialized) {
        GPU_LOGE("Engine niet gestart! Roep initializeEngine() aan via JNI.");
        return false;
    }
    if (rgb32f.empty() || rgb32f.type() != CV_32FC3) return false;

    auto startTotal = std::chrono::steady_clock::now();

    auto display = static_cast<EGLDisplay>(s_state.display);
    auto context = static_cast<EGLContext>(s_state.context);
    auto surface = static_cast<EGLSurface>(s_state.surface);

    eglMakeCurrent(display, surface, surface, context);

    int fullWidth = rgb32f.cols;
    int fullHeight = rgb32f.rows;
    int smallWidth = std::max(1, fullWidth / 8);
    int smallHeight = std::max(1, fullHeight / 8);

    cv::Mat rgba32f;
    cv::cvtColor(rgb32f, rgba32f, cv::COLOR_RGB2RGBA);

    auto createTex = [](int w, int h, GLenum internalFormat) -> GLuint {
        GLuint tex; glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
        return tex;
    };

    GLuint texOrig = createTex(fullWidth, fullHeight, GL_RGBA32F);
    glBindTexture(GL_TEXTURE_2D, texOrig);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fullWidth, fullHeight, GL_RGBA, GL_FLOAT, rgba32f.data);

    GLuint texChromaRaw = createTex(smallWidth, smallHeight, GL_RGBA16F);
    GLuint texChromaBlurX = createTex(smallWidth, smallHeight, GL_RGBA16F);
    GLuint texChromaFinal = createTex(smallWidth, smallHeight, GL_RGBA16F);
    GLuint texOut = createTex(fullWidth, fullHeight, GL_RGBA32F);

    // PASS 1
    glUseProgram(s_state.progExtract);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texOrig);
    glUniform1i(glGetUniformLocation(s_state.progExtract, "texIn"), 0);
    glBindImageTexture(1, texChromaRaw, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((smallWidth + 15) / 16, (smallHeight + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // PASS 2A
    glUseProgram(s_state.progBlurX);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texChromaRaw);
    glUniform1i(glGetUniformLocation(s_state.progBlurX, "texIn"), 0);
    glBindImageTexture(1, texChromaBlurX, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((smallWidth + 15) / 16, (smallHeight + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // PASS 2B
    glUseProgram(s_state.progBlurY);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texChromaBlurX);
    glUniform1i(glGetUniformLocation(s_state.progBlurY, "texIn"), 0);
    glBindImageTexture(1, texChromaFinal, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((smallWidth + 15) / 16, (smallHeight + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // PASS 3
    glUseProgram(s_state.progReconstruct);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texOrig);
    glUniform1i(glGetUniformLocation(s_state.progReconstruct, "texOrig"), 0);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, texChromaFinal);
    glUniform1i(glGetUniformLocation(s_state.progReconstruct, "texBlurredChroma"), 1);
    glBindImageTexture(2, texOut, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA32F);
    glDispatchCompute((fullWidth + 15) / 16, (fullHeight + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // --- Readback ---
    GLuint fbo; glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOut, 0);
    glReadPixels(0, 0, fullWidth, fullHeight, GL_RGBA, GL_FLOAT, rgba32f.data);

    // --- Cleanup ---
    glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texOrig); glDeleteTextures(1, &texChromaRaw);
    glDeleteTextures(1, &texChromaBlurX); glDeleteTextures(1, &texChromaFinal); glDeleteTextures(1, &texOut);

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    cv::cvtColor(rgba32f, rgb32f, cv::COLOR_RGB2RGBA);
    cv::cvtColor(rgb32f, rgb32f, cv::COLOR_RGBA2RGB);

    auto endTotal = std::chrono::steady_clock::now();
    GPU_LOGI("Bento GPU VRAM Pass voltooid in %.2f ms",
             std::chrono::duration<float, std::milli>(endTotal - startTotal).count());

    return true;
}

// =========================================================================
// PROCESS TO BGR (STANDALONE)
// =========================================================================

bool GpuIsp::processToBgr(
        const cv::Mat& inputMat,
        cv::Mat& bgr8,
        const NativeRenderQualityConfig& cfg,
        bool applyMatrix,
        bool is8BitInput
) {
    if (inputMat.empty()) return false;
    auto startTotal = std::chrono::steady_clock::now();

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) return false;
    eglInitialize(display, nullptr, nullptr);

    const EGLint configAttribs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE };
    EGLConfig config; EGLint numConfigs;
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    eglMakeCurrent(display, surface, surface, context);

    GLuint computeShader = compileShader(GL_COMPUTE_SHADER, COMPUTE_SHADER_SRC);
    GLuint computeProgram = glCreateProgram();
    glAttachShader(computeProgram, computeShader); glLinkProgram(computeProgram);
    glUseProgram(computeProgram);

    glUniform1f(glGetUniformLocation(computeProgram, "u_bitDepth"), is8BitInput ? 255.0f : 65535.0f);
    glUniform1i(glGetUniformLocation(computeProgram, "u_isBgr"), is8BitInput ? 1 : 0);

    glUniform1i(glGetUniformLocation(computeProgram, "u_applyMatrix"), applyMatrix ? 1 : 0);

    GLfloat matArray[9] = { cfg.colorMatrix[0], cfg.colorMatrix[3], cfg.colorMatrix[6], cfg.colorMatrix[1], cfg.colorMatrix[4], cfg.colorMatrix[7], cfg.colorMatrix[2], cfg.colorMatrix[5], cfg.colorMatrix[8] };
    glUniformMatrix3fv(glGetUniformLocation(computeProgram, "u_colorMatrix"), 1, GL_FALSE, matArray);

    // Dynamic exposure and BLC normalization uniforms binding
    float bitDepthMax = is8BitInput ? 255.0f : 65535.0f;
    glUniform1f(glGetUniformLocation(computeProgram, "u_exposureGain"), cfg.exposureGain);
    glUniform1f(glGetUniformLocation(computeProgram, "u_dynamicWhiteLevel"), cfg.dynamicWhiteLevel / bitDepthMax);

    float rBlack = cfg.dynamicBlackLevels[0] / bitDepthMax;
    float gBlack = ((cfg.dynamicBlackLevels[1] + cfg.dynamicBlackLevels[2]) * 0.5f) / bitDepthMax;
    float bBlack = cfg.dynamicBlackLevels[3] / bitDepthMax;
    glUniform3f(glGetUniformLocation(computeProgram, "u_dynamicBlackLevel"), rBlack, gBlack, bBlack);

    int width = inputMat.cols; int height = inputMat.rows;
    cv::Mat contigMat = inputMat.isContinuous() ? inputMat : inputMat.clone();

    GLuint texIn; glGenTextures(1, &texIn); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texIn);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    GLint internalFmt = is8BitInput ? GL_RGBA8UI : GL_RGB16UI;
    GLenum type = is8BitInput ? GL_UNSIGNED_BYTE : GL_UNSIGNED_SHORT;

    if (is8BitInput) {
        cv::Mat bgraMat;
        cv::cvtColor(contigMat, bgraMat, cv::COLOR_BGR2BGRA);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFmt, width, height, 0, GL_RGBA_INTEGER, type, bgraMat.data);
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, internalFmt, width, height, 0, GL_RGB_INTEGER, type, contigMat.data);
    }
    glUniform1i(glGetUniformLocation(computeProgram, "texIn"), 0);

    GLuint texOut; glGenTextures(1, &texOut); glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, texOut);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    glBindImageTexture(1, texOut, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glUniform1i(glGetUniformLocation(computeProgram, "imgOut"), 1);

    auto startCompute = std::chrono::steady_clock::now();
    glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    auto endCompute = std::chrono::steady_clock::now();

    GLuint fbo; glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOut, 0);

    cv::Mat rgba8(height, width, CV_8UC4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba8.data);
    cv::cvtColor(rgba8, bgr8, cv::COLOR_RGBA2BGR);

    glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texIn); glDeleteTextures(1, &texOut);
    glDeleteProgram(computeProgram); glDeleteShader(computeShader);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context); eglDestroySurface(display, surface);

    GPU_LOGI("God-Tier GPU Pipeline Klaar! (8bit=%d) ComputeTime=%.2f ms",
             is8BitInput, std::chrono::duration<float, std::milli>(endCompute - startCompute).count());
    return true;
}
