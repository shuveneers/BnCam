// CPU oracle: PhysicalLumaDenoise.h. Mode 3 only; immutable final demosaic Y
// equals post-chroma Y. Apply a common RGB delta AFTER physical chroma.
vec3 physicalLumaDenoise(ivec2 xy,vec3 postChroma,out vec3 telemetry) {
    telemetry=vec3(0);
    if(pc.cfaEvidence1.y<=0.0 || !physicalChromaFinite(postChroma)) return postChroma;
    float center=opponentAtRgb(physicalChromaRead(xy)).x;
    float s=physicalChromaShape(xy),variance=pc.padding0;
    float v=variance*s*s;
    if(!(v>0.0) || isnan(v) || isinf(v)) return postChroma;
    float physical=pc.cfaEvidence1.y*v/(v+0.001*center*center);
    float pressure=physicalNoisePressure(v,center);
    float correction=0.0;
    const ivec2 directions[4]=ivec2[4](ivec2(1,0),ivec2(0,1),ivec2(1,1),ivec2(1,-1));
    for(int band=0;band<2;++band) {
        int radius=band+1;
        float residuals[4],likelihood[4];float structure=0.0;
        for(int dir=0;dir<4;++dir) {
            ivec2 d=directions[dir],t=ivec2(-d.y,d.x);
            float sum=0.0,power=0.0,noise=0.0;
            for(int k=-2;k<=2;++k) {
                ivec2 p=xy+k*t,a=p-radius*d,b=p+radius*d;
                float cY=opponentAtRgb(physicalChromaRead(p)).x;
                float aY=opponentAtRgb(physicalChromaRead(a)).x;
                float bY=opponentAtRgb(physicalChromaRead(b)).x;
                float cs=physicalChromaShape(p),as=physicalChromaShape(a),bs=physicalChromaShape(b);
                float n=variance*(cs*cs+0.25*(as*as+bs*bs));
                float r=cY-0.5*(aY+bY);
                if(isnan(r)||isinf(r)||isnan(n)||isinf(n)||!(n>0.0)) return postChroma;
                if(k==0)residuals[dir]=r;
                sum+=r;power+=r*r;noise+=n;
            }
            power*=0.2;noise*=0.2;
            float mean=sum*0.2,coherentPower=max(0.0,mean*mean-noise*0.2);
            float signal=max(0.0,power-noise);
            structure=max(structure,coherentPower/(coherentPower+noise*0.2));
            likelihood[dir]=noise/(noise+4.0*signal);
        }
        telemetry.z=max(telemetry.z,structure);
        float available=band==0 ? 0.28+0.32*pressure : 0.16+0.20*pressure;
        float a=physical*(1.0-structure)*available;
        for(int dir=0;dir<4;++dir) {
            correction+=0.25*a*likelihood[dir]*residuals[dir];
            telemetry[band]+=0.25*a*likelihood[dir];
        }
    }
    vec3 result=postChroma-vec3(correction);
    return physicalChromaFinite(result)?result:postChroma;
}
