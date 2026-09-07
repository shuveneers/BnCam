#ifndef BNCAM_PROFILE_LOCAL_PRESENCE_GLSL
#define BNCAM_PROFILE_LOCAL_PRESENCE_GLSL

// Exact integer-backed transport, shared with ProfileColorTransport.h. Ordinary
// legacy controls [-1,1] cannot collide with the negative packed carrier.
vec2 bncamDecodeProfilePresence(float carrier) {
    if (isnan(carrier) || isinf(carrier) || carrier > -2.0 || carrier < -2097152.0)
        return vec2(0.0);
    uint packed = uint(-carrier) - 2u;
    return vec2(float(packed / 2048u) / 1023.0,
                (float(packed % 2048u) - 1023.0) / 1023.0);
}

// The route supplies immutable, unsharpened, display/base-tone luminance. No
// invocation may write that source while a Pop proposal dispatch is reading it.
float profilePopSourceLuma(ivec2 pixel);

const vec2 kProfilePopDisk[12] = vec2[12](
    vec2(0.35, 0.0), vec2(-0.35, 0.0), vec2(0.0, 0.35), vec2(0.0, -0.35),
    vec2(0.92, 0.0), vec2(-0.92, 0.0), vec2(0.0, 0.92), vec2(0.0, -0.92),
    vec2(0.65, 0.65), vec2(-0.65, -0.65), vec2(0.65, -0.65), vec2(-0.65, 0.65));

vec3 applyProfileLocalPresence(vec3 rgb, ivec2 pixel, ivec2 imageSize,
                              float popControl, float noiseSigmaY) {
    // Exact bypass precedes ALL source/neighbour loads and transcendental work.
    if (!(popControl > 0.0)) return rgb;
    float amount = clamp(popControl, 0.0, 1.0);
    float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float peak = max(rgb.r, max(rgb.g, rgb.b));
    float toneGate = smoothstep(0.035, 0.16, y) *
                     (1.0 - smoothstep(0.68, 0.94, y)) *
                     (1.0 - smoothstep(0.78, 0.98, peak));
    if (toneGate <= 0.0) return rgb;

    float centerY = max(profilePopSourceLuma(pixel), 0.0001);
    float centerEv = log2(centerY);
    float shortSide = float(min(imageSize.x, imageSize.y));
    float nearRadius = clamp(shortSide / 260.0, 3.0, 14.0);
    float farRadius = clamp(shortSide / 110.0, 8.0, 32.0);
    float nearSum = 0.0, farSum = 0.0, nearWeight = 0.0, farWeight = 0.0;
    float localMinEv = centerEv, localMaxEv = centerEv;
    for (int i = 0; i < 12; ++i) {
        ivec2 nearPixel = clamp(pixel + ivec2(round(kProfilePopDisk[i] * nearRadius)),
                                ivec2(0), imageSize - ivec2(1));
        ivec2 farPixel = clamp(pixel + ivec2(round(kProfilePopDisk[i] * farRadius)),
                               ivec2(0), imageSize - ivec2(1));
        float nearEv = log2(max(profilePopSourceLuma(nearPixel), 0.0001));
        float farEv = log2(max(profilePopSourceLuma(farPixel), 0.0001));
        // Reject cross-boundary samples, with a separate neighbourhood-range veto
        // below. This prevents a bright/dark step acquiring glowing side lobes.
        float nw = 1.0 - smoothstep(0.28, 0.85, abs(nearEv - centerEv));
        float fw = 1.0 - smoothstep(0.28, 0.85, abs(farEv - centerEv));
        nearSum += nearEv * nw;
        farSum += farEv * fw;
        nearWeight += nw;
        farWeight += fw;
        localMinEv = min(localMinEv, min(nearEv, farEv));
        localMaxEv = max(localMaxEv, max(nearEv, farEv));
    }
    if (nearWeight < 7.0 || farWeight < 7.0) return rgb;
    float nearEv = nearSum / nearWeight;
    float farEv = farSum / farWeight;
    // Difference of two smoothed bases: the center-minus-base high-frequency
    // residual is NEVER added. Sensor noise, pores and sharpening are not Pop.
    float bandEv = nearEv - farEv;
    float centerToFar = centerEv - farEv;
    if (bandEv * centerToFar <= 0.0) return rgb;
    float noiseEv = 1.442695 * max(noiseSigmaY, 1.0 / 255.0) / max(centerY, 0.04);
    float confidence = smoothstep(max(0.012, 0.45 * noiseEv),
                                  max(0.060, 1.5 * noiseEv), abs(bandEv));
    float edgeGate = 1.0 - smoothstep(0.55, 1.20, localMaxEv - localMinEv);
    float microResidual = abs(centerEv - nearEv);
    float coherence = clamp(3.0 * abs(bandEv) / max(microResidual, 0.008), 0.0, 1.0);
    float rangeGate = smoothstep(0.045, 0.16, localMaxEv - localMinEv);

    // Conservative warm-skin evidence requires real chroma; smooth weak-colour
    // areas still receive tonal depth but no chroma multiplication or texture gain.
    float chroma = peak - min(rgb.r, min(rgb.g, rgb.b));
    float warmSkin = smoothstep(0.008, 0.065, rgb.r - rgb.b) *
                     smoothstep(0.0, 0.035, rgb.g - rgb.b) *
                     (1.0 - smoothstep(0.25, 0.50, chroma / max(peak, 0.01)));
    float skinGate = 1.0 - 0.45 * warmSkin;
    float ev = 0.20 * tanh(2.0 * bandEv / 0.20) * amount * toneGate *
               confidence * edgeGate * coherence * rangeGate * skinGate;
    // A common RGB ratio preserves hue and chromaticity. Bound positive gain by
    // channel headroom; darkening is limited to 0.20 EV, so there is no black crush.
    float gain = exp2(ev);
    if (gain > 1.0) gain = min(gain, 1.0 / max(peak, 0.0001));
    return rgb * gain;
}

#endif
