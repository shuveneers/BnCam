#include "../../main/cpp/tests/ChromaDetailValidation.h"
#include <iostream>
int main(){auto report=bncam::chroma::detailtest::run();std::cout<<report;
    return report.find("detailPassed=true")==std::string::npos;}
