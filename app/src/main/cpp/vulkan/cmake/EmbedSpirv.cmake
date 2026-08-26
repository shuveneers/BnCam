if(NOT DEFINED INPUT OR NOT DEFINED OUTPUT OR NOT DEFINED SYMBOL)
    message(FATAL_ERROR "EmbedSpirv.cmake requires INPUT, OUTPUT and SYMBOL")
endif()
if(NOT EXISTS "${INPUT}")
    message(FATAL_ERROR "SPIR-V input not found: ${INPUT}")
endif()
file(READ "${INPUT}" SPIRV_HEX HEX)
string(LENGTH "${SPIRV_HEX}" HEX_LENGTH)
math(EXPR REMAINDER "${HEX_LENGTH} % 8")
if(NOT REMAINDER EQUAL 0)
    message(FATAL_ERROR "SPIR-V byte length is not a multiple of four")
endif()
# SPIR-V files are little-endian uint32 words. Transform every 4 bytes in one
# CMake regex pass instead of iterating word-by-word in the interpreter.
string(REGEX REPLACE
    "([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])"
    "0x\\4\\3\\2\\1u,"
    WORDS
    "${SPIRV_HEX}")
# Remove the final comma for a clean initializer. No semantic difference, but
# preserves the shape produced by the old generator.
string(REGEX REPLACE ",$" "" WORDS "${WORDS}")
set(CONTENT "#pragma once\n#include <cstdint>\n#include <vector>\n\nnamespace bncam::vulkan {\ninline const std::vector<std::uint32_t>& ${SYMBOL}() {\n    static const std::vector<std::uint32_t> words = {\n        ${WORDS}\n    };\n    return words;\n}\n} // namespace bncam::vulkan\n")
get_filename_component(OUTPUT_DIR "${OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${OUTPUT_DIR}")
file(WRITE "${OUTPUT}" "${CONTENT}")
