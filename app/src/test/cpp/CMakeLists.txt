cmake_minimum_required(VERSION 3.22)
project(BnCamPhysicalNoiseTests LANGUAGES CXX)
enable_testing()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Git REQUIRED)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
# Build an independent frozen reference from the requested commit, not a copy
# of the current implementation. Any future pixel change must fail identity.
set(BASELINE "8aee2d298bffb2daf9f1640e7ff654ab8c7a46bc")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/baseline_8aee2d2")
foreach(header IN ITEMS PhysicalNoiseAuthority.h PhysicalChromaDenoise.h PhysicalLumaDenoise.h)
    execute_process(COMMAND "${GIT_EXECUTABLE}" show "${BASELINE}:app/src/main/cpp/${header}"
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" OUTPUT_VARIABLE reference
        RESULT_VARIABLE status)
    if(NOT status EQUAL 0)
        message(FATAL_ERROR "Cannot load frozen baseline ${header}")
    endif()
    string(REPLACE "bncam::" "baseline8aee2d2::" reference "${reference}")
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/baseline_8aee2d2/${header}" "${reference}")
endforeach()
foreach(test IN ITEMS AdaptiveNoiseAuthorityTest PhysicalNoisePropagationTest PhysicalNoisePixelIdentityTest
        PhysicalChromaDenoiseTest PhysicalLumaDenoiseTest PhysicalNoiseNativeContractTest
        RawCfaLevelMappingTest)
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
add_test(NAME PhysicalModelIsolationSourceTest COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/PhysicalModelIsolationSourceTest.py")
