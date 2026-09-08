include_guard(GLOBAL)

# Phase-4 SPECTRA Neural Vulkan backend build module.  The main BnCam native
# CMake includes this module only when it is ready to attach the backend to the
# authoritative VulkanRuntime (production callsite integration is Phase 5).
# No second Vulkan runtime or CPU reference backend is created here.

get_filename_component(_BNCAM_NEURAL_VULKAN_DIR "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
get_filename_component(_BNCAM_NEURAL_CPP_ROOT "${_BNCAM_NEURAL_VULKAN_DIR}/.." ABSOLUTE)

set(_BNCAM_NEURAL_SHADER_SPECS
    "neural_condition|NeuralConditionSpirv.h|getNeuralConditionSpirv"
    "neural_conv|NeuralConvSpirv.h|getNeuralConvSpirv"
    "neural_film_params|NeuralFilmParamsSpirv.h|getNeuralFilmParamsSpirv"
    "neural_film_apply|NeuralFilmApplySpirv.h|getNeuralFilmApplySpirv"
    "neural_gate|NeuralGateSpirv.h|getNeuralGateSpirv"
    "neural_add|NeuralAddSpirv.h|getNeuralAddSpirv"
    "neural_scaled_add|NeuralScaledAddSpirv.h|getNeuralScaledAddSpirv"
    "neural_writeback|NeuralWritebackSpirv.h|getNeuralWritebackSpirv"
)

set(BNCAM_NEURAL_GENERATED_HEADERS "")
if(BNCAM_GLSLC)
    foreach(_SPEC IN LISTS _BNCAM_NEURAL_SHADER_SPECS)
        string(REPLACE "|" ";" _PARTS "${_SPEC}")
        list(GET _PARTS 0 _NAME)
        list(GET _PARTS 1 _HEADER)
        list(GET _PARTS 2 _SYMBOL)
        set(_SPV "${BNCAM_GENERATED_SHADER_DIR}/${_NAME}.spv")
        set(_OUT "${BNCAM_GENERATED_SHADER_DIR}/${_HEADER}")
        add_custom_command(
            OUTPUT "${_OUT}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${BNCAM_GENERATED_SHADER_DIR}"
            COMMAND "${BNCAM_GLSLC}" --target-env=vulkan1.1 -O
                    "${_BNCAM_NEURAL_VULKAN_DIR}/shaders/${_NAME}.comp"
                    -o "${_SPV}"
            COMMAND "${CMAKE_COMMAND}"
                    -DINPUT=${_SPV}
                    -DOUTPUT=${_OUT}
                    -DSYMBOL=${_SYMBOL}
                    -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            DEPENDS
                    "${_BNCAM_NEURAL_VULKAN_DIR}/shaders/${_NAME}.comp"
                    "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            VERBATIM
        )
        list(APPEND BNCAM_NEURAL_GENERATED_HEADERS "${_OUT}")
    endforeach()
    add_custom_target(bncam_neural_shaders DEPENDS ${BNCAM_NEURAL_GENERATED_HEADERS})
    set(BNCAM_NEURAL_SHADERS_AVAILABLE 1)
else()
    set(BNCAM_NEURAL_SHADERS_AVAILABLE 0)
endif()

function(bncam_attach_spectra_neural_backend TARGET_NAME)
    if(NOT TARGET "${TARGET_NAME}")
        message(FATAL_ERROR "bncam_attach_spectra_neural_backend: target '${TARGET_NAME}' does not exist")
    endif()
    target_sources("${TARGET_NAME}" PRIVATE
        "${_BNCAM_NEURAL_VULKAN_DIR}/NeuralSha256.cpp"
        "${_BNCAM_NEURAL_VULKAN_DIR}/VulkanNeuralModelPackage.cpp"
        "${_BNCAM_NEURAL_VULKAN_DIR}/VulkanNeuralExecutionPlan.cpp"
        "${_BNCAM_NEURAL_VULKAN_DIR}/VulkanNeuralResourceBridge.cpp"
        "${_BNCAM_NEURAL_VULKAN_DIR}/VulkanNeuralRawDenoiseBackend.cpp"
    )
    target_compile_definitions("${TARGET_NAME}" PRIVATE
        BNCAM_NEURAL_SHADERS_AVAILABLE=${BNCAM_NEURAL_SHADERS_AVAILABLE}
    )
    target_include_directories("${TARGET_NAME}" PRIVATE
        "${_BNCAM_NEURAL_VULKAN_DIR}"
        "${BNCAM_GENERATED_SHADER_DIR}"
    )
    if(TARGET bncam_neural_shaders)
        add_dependencies("${TARGET_NAME}" bncam_neural_shaders)
    endif()
endfunction()
