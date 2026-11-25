cmake_minimum_required(VERSION 3.22)
project(BnCamPhysicalNoiseTests LANGUAGES CXX)
enable_testing()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Git REQUIRED)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
# Build an independent frozen reference from the requested commit, not a copy
# of the current implementation. Any future pixel change must fail identity.
set(BASELINE "277826469978febf4e243b15363b8340840bd971")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/baseline_2778264")
foreach(header IN ITEMS PhysicalNoiseAuthority.h PhysicalChromaDenoise.h PhysicalLumaDenoise.h)
    execute_process(COMMAND "${GIT_EXECUTABLE}" show "${BASELINE}:app/src/main/cpp/${header}"
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" OUTPUT_VARIABLE reference
        RESULT_VARIABLE status)
    if(NOT status EQUAL 0)
        message(FATAL_ERROR "Cannot load frozen baseline ${header}")
    endif()
    string(REPLACE "bncam::" "baseline2778264::" reference "${reference}")
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/baseline_2778264/${header}" "${reference}")
endforeach()
foreach(test IN ITEMS AdaptiveNoiseAuthorityTest PhysicalNoisePropagationTest PhysicalNoisePixelIdentityTest
        SingleFrameLumaStageContractTest SingleFrameNoiseContextTest
        DemosaicNoiseCorrelationTest SingleFrameDemosaicContextTest
        RawPoissonGaussianEstimatorTest SingleFrameGuidedLumaTest SingleFrameGuidedCoarseTest
        SingleFrameResidualConfidenceTest SingleFrameResidualCandidateQualityTest
        SingleFrameIntegrationQualificationTest SingleFrameGuidedBalancedTest SingleFrameBalancedDefaultContractTest
        PhysicalChromaDenoiseTest PhysicalLumaDenoiseTest PhysicalNoiseNativeContractTest
        RawCfaLevelMappingTest SingleFrameJpegDetailTest)
    add_executable(${test} ${test}.cpp)
    target_include_directories(${test} PRIVATE "${CMAKE_CURRENT_BINARY_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
    # Assertions remain enabled in release test builds.
    if(MSVC)
        target_compile_options(${test} PRIVATE /UNDEBUG)
    else()
        target_compile_options(${test} PRIVATE -UNDEBUG)
    endif()
    add_test(NAME ${test} COMMAND ${test})
endforeach()
target_compile_definitions(SingleFrameJpegDetailTest PRIVATE BNCAM_ENABLE_GPU_BENCHMARKS=1)
option(SF2_ISOLATED_PHASE6 "Run phase-6 current source gates; archived phase-5 source gates are tested separately in CI" OFF)
option(SF2_ISOLATED_PHASE7 "Run current phase-7 source contracts; frozen phases are built separately in CI" OFF)
option(SF2_ISOLATED_PHASE8 "Run phase-8 source contracts against frozen phase-7 UI base" OFF)
option(SF2_ISOLATED_PHASE9 "Run Balanced product promotion source contracts" OFF)
if(NOT SF2_ISOLATED_PHASE6 AND NOT SF2_ISOLATED_PHASE7 AND NOT SF2_ISOLATED_PHASE8 AND NOT SF2_ISOLATED_PHASE9)
add_test(NAME PhysicalModelIsolationSourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/PhysicalModelIsolationSourceTest.py")
add_test(NAME SingleFrameNoiseContextSourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/SingleFrameNoiseContextSourceTest.py")

add_test(NAME Phase2ReadOnlySourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/Phase2ReadOnlySourceTest.py")

add_test(NAME Phase3ReadOnlySourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/Phase3ReadOnlySourceTest.py")

add_test(NAME Phase4GuidedOptInSourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/Phase4GuidedOptInSourceTest.py")

add_test(NAME Phase5CoarseSourceGuardTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/Phase5CoarseSourceGuardTest.py")

elseif(SF2_ISOLATED_PHASE9)
    add_test(NAME Phase9DefaultSourceTest COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/Phase9DefaultSourceTest.py")
elseif(SF2_ISOLATED_PHASE8)
    add_test(NAME Phase8BalancedSourceTest COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/Phase8BalancedSourceTest.py")
elseif(SF2_ISOLATED_PHASE7)
    add_test(NAME Phase7IntegrationSourceTest COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/Phase7IntegrationSourceTest.py")
else()
    add_test(NAME Phase6ResidualConfidenceSourceTest COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/Phase6ResidualConfidenceSourceTest.py")
endif()
