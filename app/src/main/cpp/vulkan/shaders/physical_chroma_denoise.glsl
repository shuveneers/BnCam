// Common POST_DEMOSAIC_LINEAR_RGB -> POST_BASELINE_CHROMA_RGB.
// Fused immediately before AWB, reading only immutable binding 1. No demosaic
// or Spectra mode is inspected. See PhysicalChromaDenoise.h for the CPU oracle.
bool physicalChromaFinite(vec3 p) { return !any(isnan(p)) && !any(isinf(p)); }
float physicalNoisePressure(float v,float signal) {
    if(!(v>0.0)||isnan(v)||isinf(v)||isnan(signal)||isinf(signal)) return 0.0;
    float q=v/(v+0.0004);
    return (q*q/(q*q+(1.0-q)*(1.0-q)))*(v/(v+0.01*signal*signal));
}
float physicalChromaShape(ivec2 p) {
    uint cols=uint(pc.padding1), rows=uint(pc.padding2);
    if(cols==0u || rows==0u) return 1.0;
    p=clamp(p,ivec2(0),ivec2(pc.frameWidth,pc.frameHeight)-1);
    vec2 grid=clamp((vec2(p)+0.5)*vec2(cols,rows)/vec2(pc.frameWidth,pc.frameHeight)-0.5,
                    vec2(0),vec2(cols,rows)-1.0);
    ivec2 t=ivec2(grid),u=min(t+1,ivec2(cols,rows)-1);vec2 f=grid-vec2(t);
    vec4 s=vec4(cloudCorrectionMap[uint(t.y)*cols+uint(t.x)].x,
                cloudCorrectionMap[uint(t.y)*cols+uint(u.x)].x,
                cloudCorrectionMap[uint(u.y)*cols+uint(t.x)].x,
                cloudCorrectionMap[uint(u.y)*cols+uint(u.x)].x);
    s*=s;
    return sqrt(mix(mix(s.x,s.y,f.x),mix(s.z,s.w,f.x),f.y));
}
float physicalChromaDistance(vec3 a,vec3 b,vec4 m,float scale) {
    if(!physicalChromaFinite(b)) return 1e20;
    float rho=clamp(m.w/(sqrt(m.y)*sqrt(m.z)),-1.0,1.0);
    float u=(a.y-b.y)/sqrt(m.y),v=(a.z-b.z)/sqrt(m.z);
    float dc=0.5*((u+v)*(u+v)/(1.0+rho+1e-5)+(u-v)*(u-v)/(1.0-rho+1e-5));
    float dy=a.x-b.x;
    return dc/scale+dy*dy/(m.x*scale);
}
vec3 physicalChromaRead(ivec2 p) {
    p=clamp(p,ivec2(0),ivec2(pc.frameWidth,pc.frameHeight)-1);
    uint i=(uint(p.y)*pc.frameWidth+uint(p.x))*3u;
    return vec3(outputRgb[i],outputRgb[i+1u],outputRgb[i+2u]);
}
vec3 physicalChromaDenoise(ivec2 xy,vec3 inputRgb,out vec2 authority) {
    authority=vec2(0);
    if(pc.cfaEvidence1.w<0.5 || !physicalChromaFinite(inputRgb)) return inputRgb;
    vec4 m=pc.cfaEvidence0; // variance Y, RG, BG; covariance RG/BG
    vec3 p=opponentAtRgb(inputRgb);
    float s=physicalChromaShape(xy); s*=s;
    float v=0.5*(m.y+m.z)*s;
    float a=v/(v+0.01*p.x*p.x);
    a*=1.0+0.75*physicalNoisePressure(v,p.x);
    vec2 hs=p.yz,ls=p.yz; float hw=1.0,lw=1.0;
    for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx) {
        if(dx==0&&dy==0) continue;
        ivec2 offset=ivec2(dx,dy);
        vec3 q=opponentAtRgb(physicalChromaRead(xy+offset));
        float ns=physicalChromaShape(xy+offset);
        float d=physicalChromaDistance(p,q,m,s+ns*ns);
        float w=exp(-0.25*d);
        if(physicalChromaFinite(q)) {hs+=w*q.yz;hw+=w;}
        vec3 mid=opponentAtRgb(physicalChromaRead(xy+2*offset));
        vec3 far=opponentAtRgb(physicalChromaRead(xy+3*offset));
        float ms=physicalChromaShape(xy+2*offset),fs=physicalChromaShape(xy+3*offset);
        float gate=max(d,max(physicalChromaDistance(p,mid,m,s+ms*ms),physicalChromaDistance(p,far,m,s+fs*fs)));
        float wl=exp(-gate);
        if(physicalChromaFinite(far)) {ls+=wl*far.yz;lw+=wl;}
    }
    float support=0.75*(1.0-1.0/hw)+0.25*(1.0-1.0/lw);
    if(support>0.0)a=min(a,0.90/support);
    vec2 c=p.yz+a*(0.75*(hs/hw-p.yz)+0.25*(ls/lw-p.yz));
    if(all(equal(c,p.yz))) return inputRgb;
    float g=p.x-0.2126*c.x-0.0722*c.y;
    vec3 result=vec3(g+c.x,g,g+c.y);
    if(!physicalChromaFinite(result)) return inputRgb;
    authority=a*vec2(0.75*(1.0-1.0/hw),0.25*(1.0-1.0/lw));
    return result; // no clipping, no Y averaging, no saturation adjustment
}
