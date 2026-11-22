#pragma once

#ifndef NDEBUG
#include <vector>
#include "../PhysicalLumaDenoise.h"

namespace bncam::luma {
extern thread_local bool benchmarkReadLinear;
void recordBenchmarkLinear(Stage stage, const std::vector<float>& rgb);
}
#endif
