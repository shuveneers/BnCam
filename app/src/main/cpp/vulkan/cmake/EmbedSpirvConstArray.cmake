if(NOT DEFINED INPUT OR NOT DEFINED OUTPUT OR NOT DEFINED SYMBOL)
    message(FATAL_ERROR "EmbedSpirvConstArray.cmake requires INPUT, OUTPUT and SYMBOL")
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
math(EXPR WORD_COUNT "${HEX_LENGTH} / 8")
# SPIR-V files are little-endian uint32 words. Keep the generated storage as a
# compile-time std::array at namespace scope. Unlike a function-local std::vector
# initializer, this requires no first-use guard, heap allocation, or multi-megabyte
# initializer_list copy on the RAW preview worker thread.
string(REGEX REPLACE
    "([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])"
    "0x\\4\\3\\2\\1u,"
    WORDS
    "${SPIRV_HEX}")
string(REGEX REPLACE ",$" "" WORDS "${WORDS}")
set(CONTENT "#pragma once\n#include <array>\n#include <cstdint>\n\nnamespace bncam::vulkan {\ninline constexpr std::array<std::uint32_t, ${WORD_COUNT}> ${SYMBOL}Words = {\n    ${WORDS}\n};\ninline constexpr const std::array<std::uint32_t, ${WORD_COUNT}>& ${SYMBOL}() noexcept {\n    return ${SYMBOL}Words;\n}\n} // namespace bncam::vulkan\n")
get_filename_component(OUTPUT_DIR "${OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${OUTPUT_DIR}")
file(WRITE "${OUTPUT}" "${CONTENT}")
