#pragma once
#include <array>
#include <cstdint>
namespace bncam::vulkan::neural {
enum class NeuralKernel : std::uint8_t { Conditioning=0,Conv=1,FilmParams=2,FilmApply=3,Gate=4,Add=5,ScaledAdd=6,Writeback=7,Count=8 };
struct NeuralKernelContract {const char* source;const char* generatedHeader;const char* symbol;std::uint32_t descriptorBindings;};
constexpr std::array<NeuralKernelContract,static_cast<std::size_t>(NeuralKernel::Count)> kNeuralKernelContracts={{
 {"neural_condition.comp","NeuralConditionSpirv.h","getNeuralConditionSpirv",4},
 {"neural_conv.comp","NeuralConvSpirv.h","getNeuralConvSpirv",4},
 {"neural_film_params.comp","NeuralFilmParamsSpirv.h","getNeuralFilmParamsSpirv",4},
 {"neural_film_apply.comp","NeuralFilmApplySpirv.h","getNeuralFilmApplySpirv",3},
 {"neural_gate.comp","NeuralGateSpirv.h","getNeuralGateSpirv",2},
 {"neural_add.comp","NeuralAddSpirv.h","getNeuralAddSpirv",3},
 {"neural_scaled_add.comp","NeuralScaledAddSpirv.h","getNeuralScaledAddSpirv",4},
 {"neural_writeback.comp","NeuralWritebackSpirv.h","getNeuralWritebackSpirv",10}
}};
} // namespace bncam::vulkan::neural
