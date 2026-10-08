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
vec3 physicalChromaRead(ivec2 p) {
    p=clamp(p,ivec2(0),ivec2(pc.frameWidth,pc.frameHeight)-1);
    uint i=(uint(p.y)*pc.frameWidth+uint(p.x))*3u;
    return vec3(outputRgb[i],outputRgb[i+1u],outputRgb[i+2u]);
}
// Exact linear WB/CCM analysis; same production colour transform still runs once.
mat3 physicalChromaTransform() {
    if(pc.padding3<0.5)return mat3(1);
    return mat3(vec3(pc.ccm0,pc.ccm3,pc.ccm6)*pc.wbR,
                vec3(pc.ccm1,pc.ccm4,pc.ccm7)*pc.wbG,
                vec3(pc.ccm2,pc.ccm5,pc.ccm8)*pc.wbB);
}
float physicalChromaShrink(float eigen) {
    return eigen>=16.0?0.0:min(1.0,1.0/max(1.0,eigen));
}
vec3 physicalChromaDenoise(ivec2 xy,vec3 inputRgb,out vec2 authority) {
    authority=vec2(0);
    if(pc.cfaEvidence1.w<0.5 || !physicalChromaFinite(inputRgb)) return inputRgb;
    vec4 m=pc.cfaEvidence0;
    mat3 transform=physicalChromaTransform();
    vec3 p=opponentAtRgb(transform*inputRgb);
    float s=physicalChromaShape(xy);s*=s;
    float sr=sqrt(m.y*s),sb=sqrt(m.z*s);
    float rho=clamp(m.w/sqrt(m.y*m.z),-0.99999,0.99999),tail=sqrt(1.0-rho*rho);
    vec2 mean=vec2(0);vec3 moment=vec3(0);float weight=0.0,weight2=0.0;
    for(int dy=-2;dy<=2;++dy) for(int dx=-2;dx<=2;++dx) {
        ivec2 pos=xy+ivec2(dx,dy);
        vec3 q=opponentAtRgb(transform*physicalChromaRead(pos));
        if(!physicalChromaFinite(q))continue;
        float ns=physicalChromaShape(pos),yd=q.x-p.x;
        float w=exp(-yd*yd/(4.0*m.x*(s+ns*ns)));
        float u=(q.y-p.y)/sr,v=((q.z-p.z)/sb-rho*u)/tail;
        mean+=w*vec2(u,v);moment+=w*vec3(u*u,v*v,u*v);weight+=w;weight2+=w*w;
    }
    if(!(weight>1.001))return inputRgb;
    mean/=weight;
    vec3 cov=(moment/weight-vec3(mean.x*mean.x,mean.y*mean.y,mean.x*mean.y))*
        (weight*weight/max(1e-6,weight*weight-weight2));
    float a=max(0.0,cov.x),d=max(0.0,cov.y),b=cov.z;
    float gap=sqrt((a-d)*(a-d)+4.0*b*b);
    float lo=max(0.0,0.5*(a+d-gap)),hi=0.5*(a+d+gap);
    float sl=physicalChromaShrink(lo),sh=physicalChromaShrink(hi);
    vec2 delta=sl*mean;
    if(gap>1e-5)delta+=(sh-sl)/gap*vec2((a-lo)*mean.x+b*mean.y,b*mean.x+(d-lo)*mean.y);
    float v=0.5*(m.y+m.z)*s;
    float strength=min(0.90,v/(v+0.01*p.x*p.x));
    if(strength<1e-6)return inputRgb;
    vec2 chromaDelta=strength*vec2(sr*delta.x,sb*(rho*delta.x+tail*delta.y));
    float gd=-0.2126*chromaDelta.x-0.0722*chromaDelta.y;
    vec3 correction=vec3(gd+chromaDelta.x,gd,gd+chromaDelta.y);
    vec3 result=inputRgb+inverse(transform)*correction;
    if(!physicalChromaFinite(result))return inputRgb;
    authority=vec2(strength,0);
    return result;
}
