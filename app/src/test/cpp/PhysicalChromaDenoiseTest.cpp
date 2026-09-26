#include "../../main/cpp/tests/PhysicalChromaValidation.h"
#include <iostream>
int main() {
    auto result=bncam::chroma::test::run();
    std::cout<<result;
    return result.find("allPassed=true")!=std::string::npos?0:1;
}
