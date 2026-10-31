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
// Specialize away every extra register/read on existing non-detail routes.
layout(constant_id=0) const bool bncamCoherentDetail=false;
// CPU oracle: coherentDetail in PhysicalChromaDenoise.h. Reread the immutable
// 5x5 source instead of materializing 100 private floats per GPU invocation.
vec4 physicalDetailSample(ivec2 xy,mat3 transform,vec3 centre,float s,float sr,float sb,float rho,float tail,float ySigma) {
    vec3 q=opponentAtRgb(transform*physicalChromaRead(xy));
    float u=(q.y-centre.y)/sr,v=((q.z-centre.z)/sb-rho*u)/tail;
    float sigma=physicalChromaShape(xy);
    return vec4(u,v,(q.x-centre.x)/ySigma,sigma*sigma/s);
}
vec2 physicalChromaCoherence(ivec2 xy,mat3 transform,vec3 centre,vec4 model,float s,float sr,float sb,float rho,float tail) {
    vec2 protection=vec2(0);float lumaProtection=0.0,ySigma=sqrt(model.x*s);
    const ivec2 along[4]=ivec2[4](ivec2(0,1),ivec2(1,0),ivec2(1,1),ivec2(-1,1));
    const ivec2 across[4]=ivec2[4](ivec2(1,0),ivec2(0,1),ivec2(-1,1),ivec2(1,1));
    for(int axis=0;axis<4;++axis) {
        int halfWidth=axis<2?2:1,count=2*halfWidth+1;
        float critical=axis<2?264.99796:11998.5;
        for(int radius=1;radius<=(axis<2?2:1);++radius) {
            vec3 mean=vec3(0),scatter=vec3(0);int samples=0;
            vec2 phaseMean=vec2(0),phaseScatter=vec2(0);ivec2 phaseCount=ivec2(0);
            for(int k=-halfWidth;k<=halfWidth;++k) {
                ivec2 c=xy+k*along[axis],a=c-radius*across[axis],b=c+radius*across[axis];
                vec4 pc0=physicalDetailSample(c,transform,centre,s,sr,sb,rho,tail,ySigma);
                vec4 pa=physicalDetailSample(a,transform,centre,s,sr,sb,rho,tail,ySigma);
                vec4 pb=physicalDetailSample(b,transform,centre,s,sr,sb,rho,tail,ySigma);
                float n=pc0.w+.25*(pa.w+pb.w);
                vec3 d=pc0.xyz-.5*(pa.xyz+pb.xyz),r=d/sqrt(n);
                ++samples;vec3 delta=r-mean;mean+=delta/float(samples);scatter+=delta*(r-mean);
                if(axis<2) {
                    int phase=(k+2)&1;int countY=++phaseCount[phase];
                    float deltaY=d.z-phaseMean[phase];phaseMean[phase]+=deltaY/float(countY);
                    phaseScatter[phase]+=deltaY*(d.z-phaseMean[phase]);
                }
            }
            vec3 signal=mean*mean,meanVariance=max(vec3(0),scatter)/float(count*(count-1));
            vec3 evidence=vec3(0);
            for(int channel=0;channel<3;++channel) {
                evidence[channel]=signal[channel]>1e-10?max(0.0,(signal[channel]-critical*meanVariance[channel])/
                    (signal[channel]+critical*meanVariance[channel])):0.0;
            }
            protection=max(protection,evidence.xy);lumaProtection=max(lumaProtection,evidence.z);
            if(axis<2)for(int phase=0;phase<2;++phase) {
                int n=phaseCount[phase];
                float signalY=phaseMean[phase]*phaseMean[phase],noiseY=max(0.0,phaseScatter[phase])/float(n*(n-1));
                float threshold=n==3?11998.5:58361000.0;
                float e=signalY>1e-10?max(0.0,(signalY-threshold*noiseY)/(signalY+threshold*noiseY)):0.0;
                lumaProtection=max(lumaProtection,e);
            }
        }
    }
    return protection*(1.0-lumaProtection);
}
vec3 physicalChromaDenoise(ivec2 xy,vec3 inputRgb,out vec2 authority) {
    authority=vec2(0);
    if(!(pc.cfaEvidence1.w>0.0) || !physicalChromaFinite(inputRgb)) return inputRgb;
    vec4 m=pc.cfaEvidence0;
    mat3 transform=physicalChromaTransform();
    vec3 p=opponentAtRgb(transform*inputRgb);
    float s=physicalChromaShape(xy);s*=s;
    if(!(s>0.0)||isnan(s)||isinf(s))return inputRgb;
    float sr=sqrt(m.y*s),sb=sqrt(m.z*s);
    float rho=clamp(m.w/sqrt(m.y*m.z),-0.99999,0.99999),tail=sqrt(1.0-rho*rho);
    vec2 mean=vec2(0);vec3 moment=vec3(0);float weight=0.0,weight2=0.0;
    float noiseWeight=0.0,noiseWeight2=0.0;
    bool protect=bncamCoherentDetail && (pc.residualStride&2u)!=0u && all(greaterThanEqual(xy,ivec2(2))) &&
                 all(lessThan(xy,ivec2(pc.frameWidth,pc.frameHeight)-2));
    for(int dy=-2;dy<=2;++dy) for(int dx=-2;dx<=2;++dx) {
        ivec2 pos=xy+ivec2(dx,dy);
        vec3 q=opponentAtRgb(transform*physicalChromaRead(pos));
        if(!physicalChromaFinite(q)){if(protect)return inputRgb;continue;}
        float ns=physicalChromaShape(pos),yd=q.x-p.x;
        if(!(ns>0.0)||isnan(ns)||isinf(ns))return inputRgb;
        float w=exp(-yd*yd/(4.0*m.x*(s+ns*ns)));
        float u=(q.y-p.y)/sr,v=((q.z-p.z)/sb-rho*u)/tail;
        mean+=w*vec2(u,v);moment+=w*vec3(u*u,v*v,u*v);weight+=w;weight2+=w*w;
        noiseWeight+=w*ns*ns;noiseWeight2+=w*w*ns*ns;
    }
    if(!(weight>1.001))return inputRgb;
    mean/=weight;
    vec3 cov=(moment/weight-vec3(mean.x*mean.x,mean.y*mean.y,mean.x*mean.y))*
        (weight*weight/max(1e-6,weight*weight-weight2));
    float a=max(0.0,cov.x),d=max(0.0,cov.y),b=cov.z;
    if((pc.residualStride&1u)!=0u) {
        float expected=(weight*noiseWeight-noiseWeight2)/((weight*weight-weight2)*s);
        if(!(expected>0.0)||isnan(expected)||isinf(expected))return inputRgb;
        a/=expected;d/=expected;b/=expected;
    }
    float gap=sqrt((a-d)*(a-d)+4.0*b*b);
    float lo=max(0.0,0.5*(a+d-gap)),hi=0.5*(a+d+gap);
    float sl=physicalChromaShrink(lo),sh=physicalChromaShrink(hi);
    vec2 delta=sl*mean;
    if(gap>1e-5)delta+=(sh-sl)/gap*vec2((a-lo)*mean.x+b*mean.y,b*mean.x+(d-lo)*mean.y);
    float v=0.5*(m.y+m.z)*s;
    float strength=min(0.90,v/(v+0.01*p.x*p.x));
    if(protect)delta*=vec2(1)-physicalChromaCoherence(xy,transform,p,m,s,sr,sb,rho,tail);
    if(strength<1e-6)return inputRgb;
    vec2 chromaDelta=strength*vec2(sr*delta.x,sb*(rho*delta.x+tail*delta.y));
    float gd=-0.2126*chromaDelta.x-0.0722*chromaDelta.y;
    vec3 correction=vec3(gd+chromaDelta.x,gd,gd+chromaDelta.y);
    vec3 result=inputRgb+inverse(transform)*correction;
    if(!physicalChromaFinite(result))return inputRgb;
    authority=vec2(strength,0);
    return result;
}
