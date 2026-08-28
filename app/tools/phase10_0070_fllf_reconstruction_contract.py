from pathlib import Path
r=Path(__file__).resolve().parents[1]
sh=(r/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
c=(r/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
i=(r/'src/main/cpp/IspCore.cpp').read_text()
checks={
 'laplacian_band':'float laplacianBand = fineLog - coarseLog;' in sh,
 'edge_stop':'edgeStopEv' in sh and 'correction *= 1.0 - 0.84 * edge;' in sh,
 'coarse_seed':'seedFllfCoarseCorrection' in sh and 'push.mode = 12u' in c,
 'coarse_to_fine':'reconstructFllfCorrectionLevel' in sh and 'push.mode = 13u' in c,
 'scalar_rgb_recombine':'return sceneRgb * exp2(correctionEv);' in sh,
 'raw_order':'rgb *= pc.exposureGain;' in sh and 'rgb = applyFllfLocalExposure(gid, rgb);' in sh and 'rgb = applyAgXTonemap(rgb);' in sh,
 'yuv_legacy_isolated':'rgb = applyLocalTone(gid, rgb);' in sh and '// YUV capture path' in sh,
 'raw_legacy_request_off':'request.localToneStrength = !phase10RawToneArchitecture' in i,
 'raw_fllf_connected':'request.fllfEnabled = fllfPlan.enabled;' in i,
 'policy_connected':'resolveFastLocalLaplacianPlan' in i,
 'telemetry':'fllfAdjustedPixels' in c and 'fllfMeanAbsCorrectionEv' in c,
 'timing':'fllfRemapReconstructMs' in c,
 'architecture_truth':'GTM_SCENE_PLACEMENT__FLLF__AGX' in i,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0070_FLLF_RECONSTRUCTION_FAIL='+','.join(f))
print('PHASE10_0070_FLLF_RECONSTRUCTION_PASS='+str(len(checks)))
