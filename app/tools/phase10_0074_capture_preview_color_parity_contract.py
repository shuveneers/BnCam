from pathlib import Path
r=Path(__file__).resolve().parents[1]
i=(r/'src/main/cpp/IspCore.cpp').read_text()
p=(r/'src/main/cpp/ProfileColorManagement.h').read_text()
a=(r/'src/main/cpp/vulkan/shaders/raw_preview.comp').read_text()
b=(r/'src/main/cpp/vulkan/shaders/raw_preview_image.comp').read_text()
checks={
 'profile_optional_finalizer':'bool finalizeGamut = true' in p and 'if (finalizeGamut) bncamCompressToUnitGamutPreserveLuma' in p,
 'profile_signed_luma_finalizer':'bncamCompressToUnitGamutPreserveLuma' in p and 'minimum < y - 1.0e-6f' in p,
 'cpu_raw_profile_then_one_gamut':'applyBncamProfileColorManagement(r, g, b, uiConfig, false);' in i and 'phase10CompressUnitGamutCpu(cv::Vec3f(r, g, b))' in i,
 'preview_a_signed_ccm':'vec3 signedCcm = vec3(' in a and 'protectPreviewSignedLowerGamut(signedCcm)' in a,
 'preview_b_signed_ccm':'vec3 signedCcm = vec3(' in b and 'protectPreviewSignedLowerGamut(signedCcm)' in b,
 'preview_no_independent_ccm_clamp':'return max(vec3(0.0), vec3(' not in a[a.index('vec3 colorTransform'):a.index('float tone')] and 'return max(vec3(0.0), vec3(' not in b[b.index('vec3 colorTransform'):b.index('float tone')],
 'preview_profile_not_finalizer':'vec3 applyProfile(vec3 rgb)' in a and 'return rgb;' in a[a.index('vec3 applyProfile'):a.index('vec3 compressPreviewToUnitGamutPreserveLuma')],
 'preview_one_final_gamut':'rgb = applyProfile(rgb);\n    rgb = compressPreviewToUnitGamutPreserveLuma(rgb);\n    rgb = srgb(rgb);' in a,
 'preview_image_one_final_gamut':'rgb = applyProfile(rgb);\n    rgb = compressPreviewToUnitGamutPreserveLuma(rgb);\n    rgb = srgb(rgb);' in b,
 'preview_sharpen_luma_scale':'float sharpenedLuma = max(0.0, denoisedLuma + previewDetailDelta);' in a and 'rgb *= sharpenedLuma / max(denoisedLuma, 1.0e-6);' in a,
 'preview_image_sharpen_luma_scale':'float sharpenedLuma = max(0.0, denoisedLuma + previewDetailDelta);' in b and 'rgb *= sharpenedLuma / max(denoisedLuma, 1.0e-6);' in b,
 'agx_domain_parity':all(x in a and x in b for x in ['const float min_ev = -12.47393;','const float max_ev = +4.026069;']),
}
f=[x for x,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0074_CAPTURE_PREVIEW_COLOR_PARITY_FAIL='+','.join(f))
print('PHASE10_0074_CAPTURE_PREVIEW_COLOR_PARITY_PASS='+str(len(checks)))
