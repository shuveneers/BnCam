cmake_minimum_required(VERSION 3.22.1)
project("bncam")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep this overridable from Gradle/local.properties instead of hardcoding one workstation path.
# Example Gradle externalNativeBuild argument:
# arguments "-DOpenCV_SDK_PATH=C:/Users/Admin/Desktop/BnCam/opencv"
if(NOT DEFINED OpenCV_SDK_PATH)
    set(OpenCV_SDK_PATH "${CMAKE_SOURCE_DIR}/../../../../opencv")
endif()

set(OpenCV_INCLUDE_DIR "${OpenCV_SDK_PATH}/native/jni/include")
set(OpenCV_LIBS_DIR "${OpenCV_SDK_PATH}/native/libs/${ANDROID_ABI}")

add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES
        IMPORTED_LOCATION "${OpenCV_LIBS_DIR}/libopencv_java4.so"
)

if(BNCAM_ENABLE_GPU_BENCHMARKS)
    add_compile_definitions(BNCAM_ENABLE_GPU_BENCHMARKS=1)
endif()

# The live RAW viewfinder is Vulkan-primary in production. The legacy OpenCV/CPU implementation
# remains available only as explicit reference tooling; it must never become an implicit runtime
# fallback with unbounded frame cost on a production camera transition.
option(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE "Enable legacy CPU RAW preview reference path" OFF)
if(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)
    add_compile_definitions(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE=1)
endif()

if(NOT DEFINED BNCAM_VULKAN_VALIDATION_ENABLED)
    set(BNCAM_VULKAN_VALIDATION_ENABLED 0)
endif()
add_compile_definitions(
        BNCAM_VULKAN_RUNTIME_PREPARED=1
        BNCAM_VULKAN_VALIDATION_ENABLED=${BNCAM_VULKAN_VALIDATION_ENABLED}
)

# Pinned VMA preparation. The header is fetched once and committed/vendor-copied into this folder.
# The project remains buildable before it is fetched; runtime diagnostics then report VMA missing.
set(BNCAM_VMA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/vma-3.3.0")
if(EXISTS "${BNCAM_VMA_ROOT}/vk_mem_alloc.h")
    set(BNCAM_VMA_HEADER_AVAILABLE 1)
else()
    set(BNCAM_VMA_HEADER_AVAILABLE 0)
endif()
add_compile_definitions(BNCAM_VMA_HEADER_AVAILABLE=${BNCAM_VMA_HEADER_AVAILABLE})

set(BNCAM_VULKAN_PREP_SOURCES
        vulkan/VulkanJson.cpp
        vulkan/VulkanRuntimeContracts.cpp
        vulkan/VulkanResourceContracts.cpp
        vulkan/VulkanYuvImporter.cpp
        vulkan/VulkanRawStagingManager.cpp
        vulkan/VulkanComputePipelineManager.cpp
        vulkan/VulkanValidationCollector.cpp
        vulkan/VulkanDebugUtilsAdapter.cpp
        vulkan/VulkanVmaIntegration.cpp
        vulkan/VulkanRuntimeBootstrap.cpp
        vulkan/VulkanRuntime.cpp
        vulkan/VulkanRuntimeJni.cpp
        vulkan/VulkanSpectraOpponentBackend.cpp
        vulkan/VulkanSpectraVisibleChromaBackend.cpp
        vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp
        vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp
        vulkan/VulkanSpectraResidentChromaBackend.cpp
        vulkan/VulkanSpectraTemporalObserverBackend.cpp
        vulkan/VulkanSpectraResidentDemosaicBackend.cpp
        vulkan/VulkanRawPreviewBackend.cpp
        vulkan/VulkanRawCaptureBackend.cpp
        vulkan/VulkanRawMultiFrameBackend.cpp
        vulkan/VulkanRawJpegNormalizeBackend.cpp
        vulkan/VulkanYuvMultiFrameBackend.cpp
        vulkan/VulkanYuvSingleFrameBackend.cpp
        vulkan/VulkanYuvExposureStatisticsBackend.cpp
        vulkan/VulkanSpectraPass3PlannerBackend.cpp
        vulkan/VulkanSpectraRawFinalizeBackend.cpp
        vulkan/VulkanSpectraResidentToneBackend.cpp
)


# Build the production SPECTRA resident post-demosaic shader with the NDK shader tool.
# One persistent GPU command chain executes spatial NR and visible-chroma final decision
# without an intermediate CPU readback.
if(CMAKE_HOST_WIN32)
    set(BNCAM_SHADER_HOST_TAG "windows-x86_64")
elseif(CMAKE_HOST_APPLE)
    set(BNCAM_SHADER_HOST_TAG "darwin-x86_64")
else()
    set(BNCAM_SHADER_HOST_TAG "linux-x86_64")
endif()
find_program(BNCAM_GLSLC
        NAMES glslc glslc.exe
        HINTS
        "${CMAKE_ANDROID_NDK}/shader-tools/${BNCAM_SHADER_HOST_TAG}"
        "${ANDROID_NDK}/shader-tools/${BNCAM_SHADER_HOST_TAG}"
        "$ENV{ANDROID_NDK_ROOT}/shader-tools/${BNCAM_SHADER_HOST_TAG}"
        "$ENV{ANDROID_NDK_HOME}/shader-tools/${BNCAM_SHADER_HOST_TAG}"
)
set(BNCAM_GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/shaders")
set(BNCAM_RESIDENT_POST_DEMOSAIC_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_post_demosaic_resident.spv")
set(BNCAM_RESIDENT_POST_DEMOSAIC_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraResidentPostDemosaicSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RESIDENT_POST_DEMOSAIC_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_post_demosaic_resident.comp"
                    -o "${BNCAM_RESIDENT_POST_DEMOSAIC_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RESIDENT_POST_DEMOSAIC_SPV}
                    -DOUTPUT=${BNCAM_RESIDENT_POST_DEMOSAIC_HEADER}
                    -DSYMBOL=getSpectraResidentPostDemosaicSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_post_demosaic_resident.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_post_demosaic_shader
            DEPENDS "${BNCAM_RESIDENT_POST_DEMOSAIC_HEADER}")
    set(BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE 1)
else()
    message(FATAL_ERROR
            "glslc was not found in the configured Android NDK. "
            "The SPECTRA resident Vulkan production shader is mandatory; "
            "verify CMAKE_ANDROID_NDK/ANDROID_NDK points to the installed NDK.")
endif()

# Lightweight fused live RAW viewfinder kernel. This is deliberately separate from the capture
# resident demosaic backend so preview never runs capture-only residual/statistics passes.
set(BNCAM_RAW_PREVIEW_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_preview.spv")
set(BNCAM_RAW_PREVIEW_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawPreviewSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_PREVIEW_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview.comp"
                    -o "${BNCAM_RAW_PREVIEW_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_PREVIEW_SPV}
                    -DOUTPUT=${BNCAM_RAW_PREVIEW_HEADER}
                    -DSYMBOL=getRawPreviewSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirvConstArray.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirvConstArray.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_preview_shader DEPENDS "${BNCAM_RAW_PREVIEW_HEADER}")
    set(BNCAM_RAW_PREVIEW_SHADER_AVAILABLE 1)
else()
    set(BNCAM_RAW_PREVIEW_SHADER_AVAILABLE 0)
endif()

# GPU-resident RAW preview output kernel. Identical image processing to raw_preview.comp,
# but writes RGBA8 directly into an imported AHardwareBuffer-backed VkImage so the live viewfinder
# does not perform full-frame Vulkan->host readback followed by GLES upload.
set(BNCAM_RAW_PREVIEW_IMAGE_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_preview_image.spv")
set(BNCAM_RAW_PREVIEW_IMAGE_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawPreviewImageSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_PREVIEW_IMAGE_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview_image.comp"
                    -o "${BNCAM_RAW_PREVIEW_IMAGE_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_PREVIEW_IMAGE_SPV}
                    -DOUTPUT=${BNCAM_RAW_PREVIEW_IMAGE_HEADER}
                    -DSYMBOL=getRawPreviewImageSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirvConstArray.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview_image.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirvConstArray.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_preview_image_shader DEPENDS "${BNCAM_RAW_PREVIEW_IMAGE_HEADER}")
    set(BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE 1)
else()
    set(BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE 0)
endif()

# Canonical RAW10 unpack kernel. Keep this generated from GLSL so the production binary cannot
# silently drift from the checked-in shader source. One invocation writes one RAW16 pair.
set(BNCAM_RAW10_UNPACK_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw10_unpack.spv")
set(BNCAM_RAW10_UNPACK_HEADER "${BNCAM_GENERATED_SHADER_DIR}/Raw10UnpackSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW10_UNPACK_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw10_unpack.comp"
                    -o "${BNCAM_RAW10_UNPACK_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW10_UNPACK_SPV}
                    -DOUTPUT=${BNCAM_RAW10_UNPACK_HEADER}
                    -DSYMBOL=getRaw10UnpackSpirvGenerated
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw10_unpack.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw10_unpack_shader DEPENDS "${BNCAM_RAW10_UNPACK_HEADER}")
endif()

# Production RAW capture canonicalization. Moves RAW10/RAW_SENSOR unpack, pre-correction crop
# and sample-domain mapping off CPU/OpenCV while preserving the existing DNG payload contract.
set(BNCAM_RAW_CAPTURE_CANONICALIZE_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_capture_canonicalize.spv")
set(BNCAM_RAW_CAPTURE_CANONICALIZE_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawCaptureCanonicalizeSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_CAPTURE_CANONICALIZE_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_capture_canonicalize.comp"
                    -o "${BNCAM_RAW_CAPTURE_CANONICALIZE_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_CAPTURE_CANONICALIZE_SPV}
                    -DOUTPUT=${BNCAM_RAW_CAPTURE_CANONICALIZE_HEADER}
                    -DSYMBOL=getRawCaptureCanonicalizeSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_capture_canonicalize.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_capture_canonicalize_shader
            DEPENDS "${BNCAM_RAW_CAPTURE_CANONICALIZE_HEADER}")
    set(BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE 1)
else()
    set(BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE 0)
endif()

# Phase 13: exact resident RAW16 -> normalized float JPEG/SPECTRA entry.
# Semantics intentionally match RawDomain::normalizeRawForJpeg: black/white normalization + [0,1] clamp only.
set(BNCAM_RAW_JPEG_NORMALIZE_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_jpeg_normalize.spv")
set(BNCAM_RAW_JPEG_NORMALIZE_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawJpegNormalizeSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_JPEG_NORMALIZE_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_jpeg_normalize.comp"
                    -o "${BNCAM_RAW_JPEG_NORMALIZE_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_JPEG_NORMALIZE_SPV}
                    -DOUTPUT=${BNCAM_RAW_JPEG_NORMALIZE_HEADER}
                    -DSYMBOL=getRawJpegNormalizeSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_jpeg_normalize.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_jpeg_normalize_shader
            DEPENDS "${BNCAM_RAW_JPEG_NORMALIZE_HEADER}")
    set(BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE 1)
else()
    set(BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE 0)
endif()

# GPU Migration Phase 9: resident RAW multi-frame alignment/fusion. Alignment reads only
# compact candidate scores back to CPU; support RAW pixels and fusion accumulators remain device-resident.
set(BNCAM_RAW_MULTIFRAME_ALIGNMENT_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_multiframe_alignment.spv")
set(BNCAM_RAW_MULTIFRAME_ALIGNMENT_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawMultiFrameAlignmentSpirv.h")
set(BNCAM_RAW_MULTIFRAME_FUSION_SPV "${BNCAM_GENERATED_SHADER_DIR}/raw_multiframe_fusion.spv")
set(BNCAM_RAW_MULTIFRAME_FUSION_HEADER "${BNCAM_GENERATED_SHADER_DIR}/RawMultiFrameFusionSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_MULTIFRAME_ALIGNMENT_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_multiframe_alignment.comp"
                    -o "${BNCAM_RAW_MULTIFRAME_ALIGNMENT_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_MULTIFRAME_ALIGNMENT_SPV}
                    -DOUTPUT=${BNCAM_RAW_MULTIFRAME_ALIGNMENT_HEADER}
                    -DSYMBOL=getRawMultiFrameAlignmentSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_multiframe_alignment.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_multiframe_alignment_shader
            DEPENDS "${BNCAM_RAW_MULTIFRAME_ALIGNMENT_HEADER}")
    add_custom_command(
            OUTPUT "${BNCAM_RAW_MULTIFRAME_FUSION_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_multiframe_fusion.comp"
                    -o "${BNCAM_RAW_MULTIFRAME_FUSION_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_MULTIFRAME_FUSION_SPV}
                    -DOUTPUT=${BNCAM_RAW_MULTIFRAME_FUSION_HEADER}
                    -DSYMBOL=getRawMultiFrameFusionSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_multiframe_fusion.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_raw_multiframe_fusion_shader
            DEPENDS "${BNCAM_RAW_MULTIFRAME_FUSION_HEADER}")
    set(BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 1)
    set(BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE 1)
else()
    set(BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 0)
    set(BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE 0)
endif()

# GPU Migration Phase 11: YUV multi-frame alignment. Full-frame luma correlation runs on
# Vulkan; CPU receives only a compact candidate-score grid for shift selection/refinement.
set(BNCAM_YUV_MULTIFRAME_ALIGNMENT_SPV "${BNCAM_GENERATED_SHADER_DIR}/yuv_multiframe_alignment.spv")
set(BNCAM_YUV_MULTIFRAME_ALIGNMENT_HEADER "${BNCAM_GENERATED_SHADER_DIR}/YuvMultiFrameAlignmentSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_YUV_MULTIFRAME_ALIGNMENT_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_multiframe_alignment.comp"
                    -o "${BNCAM_YUV_MULTIFRAME_ALIGNMENT_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_YUV_MULTIFRAME_ALIGNMENT_SPV}
                    -DOUTPUT=${BNCAM_YUV_MULTIFRAME_ALIGNMENT_HEADER}
                    -DSYMBOL=getYuvMultiFrameAlignmentSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_multiframe_alignment.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_yuv_multiframe_alignment_shader
            DEPENDS "${BNCAM_YUV_MULTIFRAME_ALIGNMENT_HEADER}")
    set(BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 1)
else()
    set(BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 0)
endif()

# GPU Migration Phase 11: YUV multi-frame warp/resample/fusion. One invocation owns each
# packed 4-pixel luma output word; anchor chroma remains untouched until the JPEG boundary.
set(BNCAM_YUV_MULTIFRAME_FUSION_SPV "${BNCAM_GENERATED_SHADER_DIR}/yuv_multiframe_fusion.spv")
set(BNCAM_YUV_MULTIFRAME_FUSION_HEADER "${BNCAM_GENERATED_SHADER_DIR}/YuvMultiFrameFusionSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_YUV_MULTIFRAME_FUSION_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_multiframe_fusion.comp"
                    -o "${BNCAM_YUV_MULTIFRAME_FUSION_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_YUV_MULTIFRAME_FUSION_SPV}
                    -DOUTPUT=${BNCAM_YUV_MULTIFRAME_FUSION_HEADER}
                    -DSYMBOL=getYuvMultiFrameFusionSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_multiframe_fusion.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_yuv_multiframe_fusion_shader
            DEPENDS "${BNCAM_YUV_MULTIFRAME_FUSION_HEADER}")
    set(BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE 1)
else()
    set(BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE 0)
endif()

# GPU Migration Phase 12: YUV single-frame full-frame ISP. Tone/YUV->BGR/profile WB/color
# management/rotation execute in one Vulkan dispatch; CPU resumes only at the JPEG encoder boundary.
set(BNCAM_YUV_SINGLE_FRAME_ISP_SPV "${BNCAM_GENERATED_SHADER_DIR}/yuv_single_frame_isp.spv")
set(BNCAM_YUV_SINGLE_FRAME_ISP_HEADER "${BNCAM_GENERATED_SHADER_DIR}/YuvSingleFrameIspSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_YUV_SINGLE_FRAME_ISP_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_single_frame_isp.comp"
                    -o "${BNCAM_YUV_SINGLE_FRAME_ISP_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_YUV_SINGLE_FRAME_ISP_SPV}
                    -DOUTPUT=${BNCAM_YUV_SINGLE_FRAME_ISP_HEADER}
                    -DSYMBOL=getYuvSingleFrameIspSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_single_frame_isp.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_yuv_single_frame_isp_shader
            DEPENDS "${BNCAM_YUV_SINGLE_FRAME_ISP_HEADER}")
    set(BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE 1)
else()
    set(BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE 0)
endif()

# Phase 2: compact GPU-primary YUV exposure/histogram statistics. Kotlin only gathers a
# regular Y/U/V sample grid; all color conversion, histograms and clipping analysis run in Vulkan.
set(BNCAM_YUV_EXPOSURE_STATISTICS_SPV "${BNCAM_GENERATED_SHADER_DIR}/yuv_exposure_statistics.spv")
set(BNCAM_YUV_EXPOSURE_STATISTICS_HEADER "${BNCAM_GENERATED_SHADER_DIR}/YuvExposureStatisticsSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_YUV_EXPOSURE_STATISTICS_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_exposure_statistics.comp"
                    -o "${BNCAM_YUV_EXPOSURE_STATISTICS_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_YUV_EXPOSURE_STATISTICS_SPV}
                    -DOUTPUT=${BNCAM_YUV_EXPOSURE_STATISTICS_HEADER}
                    -DSYMBOL=getYuvExposureStatisticsSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/yuv_exposure_statistics.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_yuv_exposure_statistics_shader
            DEPENDS "${BNCAM_YUV_EXPOSURE_STATISTICS_HEADER}")
    set(BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE 1)
else()
    set(BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-B: GPU-primary pre-demosaic Pass 1. The candidate, tile
# statistics and No-Regret blend stay on-device; only the compact acceptance
# decision is made on CPU before the final mosaic readback needed by Pass 2.
set(BNCAM_RESIDENT_PRE_DEMOSAIC_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_pass1_resident.spv")
set(BNCAM_RESIDENT_PRE_DEMOSAIC_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraResidentPreDemosaicSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RESIDENT_PRE_DEMOSAIC_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_pass1_resident.comp"
                    -o "${BNCAM_RESIDENT_PRE_DEMOSAIC_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RESIDENT_PRE_DEMOSAIC_SPV}
                    -DOUTPUT=${BNCAM_RESIDENT_PRE_DEMOSAIC_HEADER}
                    -DSYMBOL=getSpectraResidentPreDemosaicSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_pass1_resident.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_pre_demosaic_shader
            DEPENDS "${BNCAM_RESIDENT_PRE_DEMOSAIC_HEADER}")
    set(BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-C: GPU-primary Pass 2 + Pass 3 chroma/banding application.
set(BNCAM_RESIDENT_CHROMA_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_chroma_resident.spv")
set(BNCAM_RESIDENT_CHROMA_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraResidentChromaSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RESIDENT_CHROMA_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_chroma_resident.comp"
                    -o "${BNCAM_RESIDENT_CHROMA_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RESIDENT_CHROMA_SPV}
                    -DOUTPUT=${BNCAM_RESIDENT_CHROMA_HEADER}
                    -DSYMBOL=getSpectraResidentChromaSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_chroma_resident.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_chroma_resident_shader
            DEPENDS "${BNCAM_RESIDENT_CHROMA_HEADER}")
    set(BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-H: compact GPU Pass-3 planner. Row/column residual profiles and
# the low-frequency R-G/B-G field are sampled directly from the resident Pass-2
# mosaic; no full-frame CPU scan/readback is required for planning.
set(BNCAM_PASS3_PLANNER_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_pass3_planner.spv")
set(BNCAM_PASS3_PLANNER_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraPass3PlannerSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_PASS3_PLANNER_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_pass3_planner.comp"
                    -o "${BNCAM_PASS3_PLANNER_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_PASS3_PLANNER_SPV}
                    -DOUTPUT=${BNCAM_PASS3_PLANNER_HEADER}
                    -DSYMBOL=getSpectraPass3PlannerSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_pass3_planner.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_pass3_planner_shader
            DEPENDS "${BNCAM_PASS3_PLANNER_HEADER}")
    set(BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-I: GPU-primary JPEG-only RAW finalization. Defect correction, bounded
# G1/G2 balancing and lens-shading application execute on Vulkan and can hand the
# finalized Bayer mosaic directly to resident demosaic without a full-frame readback.
set(BNCAM_RAW_FINALIZE_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_raw_finalize.spv")
set(BNCAM_RAW_FINALIZE_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraRawFinalizeSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RAW_FINALIZE_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_raw_finalize.comp"
                    -o "${BNCAM_RAW_FINALIZE_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RAW_FINALIZE_SPV}
                    -DOUTPUT=${BNCAM_RAW_FINALIZE_HEADER}
                    -DSYMBOL=getSpectraRawFinalizeSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_raw_finalize.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_raw_finalize_shader
            DEPENDS "${BNCAM_RAW_FINALIZE_HEADER}")
    set(BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-D: GPU-primary temporal observer. Pixel-scale normalized
# innovation/static-probability work moves to Vulkan; compact robust fits stay CPU.
set(BNCAM_TEMPORAL_OBSERVER_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_temporal_observer.spv")
set(BNCAM_TEMPORAL_OBSERVER_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraTemporalObserverSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_TEMPORAL_OBSERVER_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_temporal_observer.comp"
                    -o "${BNCAM_TEMPORAL_OBSERVER_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_TEMPORAL_OBSERVER_SPV}
                    -DOUTPUT=${BNCAM_TEMPORAL_OBSERVER_HEADER}
                    -DSYMBOL=getSpectraTemporalObserverSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_temporal_observer.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_temporal_observer_shader
            DEPENDS "${BNCAM_TEMPORAL_OBSERVER_HEADER}")
    set(BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-J: post-CCM resident highlight recovery, compact scene observer and
# pointwise tone/vibrance/profile-colour processing.
set(BNCAM_RESIDENT_TONE_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_tone_resident.spv")
set(BNCAM_RESIDENT_TONE_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraResidentToneSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RESIDENT_TONE_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_tone_resident.comp"
                    -o "${BNCAM_RESIDENT_TONE_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RESIDENT_TONE_SPV}
                    -DOUTPUT=${BNCAM_RESIDENT_TONE_HEADER}
                    -DSYMBOL=getSpectraResidentToneSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_tone_resident.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_tone_shader DEPENDS "${BNCAM_RESIDENT_TONE_HEADER}")
    set(BNCAM_SPECTRA_TONE_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_TONE_SHADER_AVAILABLE 0)
endif()

# Milestone 8H-E: GPU-primary stable demosaic path. Bilinear and Malvar 2004
# dispatch on Vulkan without a duplicate CPU demosaic; Menon remains a typed
# fallback pending the planned RCD/AMAZE migration.
set(BNCAM_RESIDENT_DEMOSAIC_SPV "${BNCAM_GENERATED_SHADER_DIR}/spectra_demosaic_resident.spv")
set(BNCAM_RESIDENT_DEMOSAIC_HEADER "${BNCAM_GENERATED_SHADER_DIR}/SpectraResidentDemosaicSpirv.h")
if(BNCAM_GLSLC)
    add_custom_command(
            OUTPUT "${BNCAM_RESIDENT_DEMOSAIC_HEADER}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_demosaic_resident.comp"
                    -o "${BNCAM_RESIDENT_DEMOSAIC_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${BNCAM_RESIDENT_DEMOSAIC_SPV}
                    -DOUTPUT=${BNCAM_RESIDENT_DEMOSAIC_HEADER}
                    -DSYMBOL=getSpectraResidentDemosaicSpirv
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            DEPENDS
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/spectra_demosaic_resident.comp"
                    "${CMAKE_CURRENT_SOURCE_DIR}/vulkan/cmake/EmbedSpirv.cmake"
            VERBATIM
    )
    add_custom_target(bncam_spectra_demosaic_shader
            DEPENDS "${BNCAM_RESIDENT_DEMOSAIC_HEADER}")
    set(BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE 1)
else()
    set(BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE 0)
endif()

add_library(bncam SHARED
        native-lib.cpp
        RawPreview.cpp
        DngMerger.cpp
        RawDomain.cpp
        Demosaic.cpp
        IspCore.cpp
        ultrahdr/UltraHdrJpegPackager.cpp
        VulkanCapabilities.cpp
        GpuCandidateAnalyzer.cpp
        VulkanBenchmarkKernels.cpp
        ${BNCAM_VULKAN_PREP_SOURCES}
)


if(TARGET bncam_spectra_post_demosaic_shader)
    add_dependencies(bncam bncam_spectra_post_demosaic_shader)
endif()
if(TARGET bncam_spectra_pre_demosaic_shader)
    add_dependencies(bncam bncam_spectra_pre_demosaic_shader)
endif()
if(TARGET bncam_spectra_chroma_resident_shader)
    add_dependencies(bncam bncam_spectra_chroma_resident_shader)
endif()
if(TARGET bncam_spectra_temporal_observer_shader)
    add_dependencies(bncam bncam_spectra_temporal_observer_shader)
endif()
if(TARGET bncam_spectra_pass3_planner_shader)
    add_dependencies(bncam bncam_spectra_pass3_planner_shader)
endif()
if(TARGET bncam_spectra_demosaic_shader)
    add_dependencies(bncam bncam_spectra_demosaic_shader)
endif()
if(TARGET bncam_raw_preview_shader)
    add_dependencies(bncam bncam_raw_preview_shader)
endif()
if(TARGET bncam_raw_preview_image_shader)
    add_dependencies(bncam bncam_raw_preview_image_shader)
endif()
if(TARGET bncam_raw10_unpack_shader)
    add_dependencies(bncam bncam_raw10_unpack_shader)
endif()
if(TARGET bncam_raw_capture_canonicalize_shader)
    add_dependencies(bncam bncam_raw_capture_canonicalize_shader)
endif()
if(TARGET bncam_raw_jpeg_normalize_shader)
    add_dependencies(bncam bncam_raw_jpeg_normalize_shader)
endif()
if(TARGET bncam_raw_multiframe_alignment_shader)
    add_dependencies(bncam bncam_raw_multiframe_alignment_shader)
endif()
if(TARGET bncam_raw_multiframe_fusion_shader)
    add_dependencies(bncam bncam_raw_multiframe_fusion_shader)
endif()
if(TARGET bncam_yuv_multiframe_alignment_shader)
    add_dependencies(bncam bncam_yuv_multiframe_alignment_shader)
endif()
if(TARGET bncam_yuv_multiframe_fusion_shader)
    add_dependencies(bncam bncam_yuv_multiframe_fusion_shader)
endif()
if(TARGET bncam_yuv_single_frame_isp_shader)
    add_dependencies(bncam bncam_yuv_single_frame_isp_shader)
endif()
if(TARGET bncam_yuv_exposure_statistics_shader)
    add_dependencies(bncam bncam_yuv_exposure_statistics_shader)
endif()
if(TARGET bncam_spectra_raw_finalize_shader)
    add_dependencies(bncam bncam_spectra_raw_finalize_shader)
endif()
if(TARGET bncam_spectra_tone_shader)
    add_dependencies(bncam bncam_spectra_tone_shader)
endif()
target_compile_definitions(bncam PRIVATE
        BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE=${BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE}
        BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE=${BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE}
        BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE=${BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE}
        BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE=${BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE}
        BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE=${BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE}
        BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE=${BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE}
        BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE=${BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE}
        BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE=${BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE}
        BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE=${BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE}
        BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE=${BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE}
        BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE=${BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE}
        BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE=${BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE}
        BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE=${BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE}
        BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE=${BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE}
        BNCAM_RAW_PREVIEW_SHADER_AVAILABLE=${BNCAM_RAW_PREVIEW_SHADER_AVAILABLE}
        BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE=${BNCAM_RAW_PREVIEW_IMAGE_SHADER_AVAILABLE}
        BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE=${BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE}
        BNCAM_SPECTRA_TONE_SHADER_AVAILABLE=${BNCAM_SPECTRA_TONE_SHADER_AVAILABLE}
)
target_include_directories(bncam PRIVATE "${BNCAM_GENERATED_SHADER_DIR}")

target_include_directories(bncam PRIVATE
        ${OpenCV_INCLUDE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/vulkan
        ${BNCAM_VMA_ROOT}
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

target_link_libraries(bncam
        lib_opencv
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
        ${vulkan-lib}
        ${egl-lib}
        ${gles3-lib}
)
