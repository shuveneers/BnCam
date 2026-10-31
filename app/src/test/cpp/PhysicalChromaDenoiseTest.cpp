#include "../../main/cpp/tests/PhysicalChromaValidation.h"
#include <iostream>
int main() {
    auto result=bncam::chroma::test::run();
    auto spatial=bncam::chroma::test::runSpatial();
    std::cout<<result<<spatial;
    return result.find("allPassed=true")!=std::string::npos&&
           spatial.find("spatialPassed=true")!=std::string::npos?0:1;
}
