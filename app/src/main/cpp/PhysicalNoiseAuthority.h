#pragma once
#include <algorithm>
#include <cmath>

namespace bncam::physical {
// Local variance is post-demosaic/posterior variance times the existing LSC
// relative variance shape. No ISO, second LSC gain or downstream WB/CCM gain.
inline float noisePressure(float variance, float signal) {
    if (!(variance > 0.f) || !std::isfinite(variance) || !std::isfinite(signal)) return 0.f;
    const float q = variance / (variance + 0.0004f); // sigma knee = 0.02 linear units
    const float severity = q*q / (q*q + (1.f-q)*(1.f-q));
    const float poorSnr = variance / (variance + 0.01f*signal*signal);
    return severity * poorSnr;
}
// Interpolate variance, not a second gain. Continuous at tile boundaries.
inline float spatialSigma(int x,int y,int width,int height,int cols,int rows,const float* map) {
    if (!map || cols<=0 || rows<=0) return 1.f;
    float gx=std::clamp((std::clamp(x,0,width-1)+.5f)*cols/width-.5f,0.f,float(cols-1));
    float gy=std::clamp((std::clamp(y,0,height-1)+.5f)*rows/height-.5f,0.f,float(rows-1));
    int ix=int(gx),iy=int(gy),jx=std::min(ix+1,cols-1),jy=std::min(iy+1,rows-1);
    auto v=[&](int xx,int yy){float s=map[yy*cols+xx];return s*s;};
    float a=v(ix,iy)+(v(jx,iy)-v(ix,iy))*(gx-ix);
    float b=v(ix,jy)+(v(jx,jy)-v(ix,jy))*(gx-ix);
    return std::sqrt(a+(b-a)*(gy-iy));
}
}
