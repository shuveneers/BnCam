#include "../../main/cpp/tests/PhysicalLumaValidation.h"
#include <iostream>
int main(){
    bool passed=true;
    for(unsigned seed=9137;seed<9145;++seed) {
        auto report=bncam::luma::test::run(bncam::luma::test::reference,seed);
        std::cout<<"seed="<<seed<<'\n'<<report;
        passed &= report.find("allPassed=false")==std::string::npos;
    }
    using namespace bncam::luma;
    const Pixel p{.3f,.2f,.1f};
    auto read=[&](int,int){return p;};
    for(Model m : {Model{},Model{-1,1},Model{1,-1},Model{NAN,1},Model{1,NAN}})
        passed &= filter(0,0,m,p,read,[](int,int){return 1.f;}).pixel==p;
    passed &= filter(0,0,{.01f,1},p,read,[](int,int){return NAN;}).pixel==p;
    std::cout<<"nativeAllPassed="<<passed<<'\n';return !passed;
}
