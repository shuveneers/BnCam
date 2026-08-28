from pathlib import Path
r=Path(__file__).resolve().parents[1]
isp=(r/'src/main/cpp/IspCore.cpp').read_text()
prof=(r/'src/main/cpp/ProfileToneRenderPolicy.h').read_text()
checks={
 'raw_arch_flag':'const bool phase10RawToneArchitecture = isRawBayer;' in isp,
 'raw_auto_contrast_removed':'phase10RawToneArchitecture ? 0.0f : dynamicRangeTonePlan.contrastStrength' in isp,
 'raw_auto_lower_mid_removed':'requestedSceneLowerMidtoneLiftAmount = phase10RawToneArchitecture' in isp,
 'raw_auto_black_anchor_removed':'effectiveBlackAnchor = phase10RawToneArchitecture' in isp and '? 0.0f' in isp,
 'raw_display_white_expansion_removed':'if (!phase10RawToneArchitecture)' in isp and 'applyDisplayWhiteExpansion' in isp,
 'raw_pre_agx_shoulder_propagation_removed':'if (phase10RawToneArchitecture)' in isp and 'phase10AgxCoreCpu(cv::Vec3f(x, x, x))' in isp and 'else if (exposedLuma > shoulderStartForPropagation)' in isp,
 'raw_cpu_duplicate_shoulder_removed':'if (!phase10RawToneArchitecture && postNeutralY > shoulderStart)' in isp,
 'profile_blacks_preserve_zero':'float blackRangeDelta = 0.0f;' in prof and 'out.blackAnchorDelta = 0.0f;' in prof,
 'profile_black_range_window':'blackWindow' in prof and 'plan.blackRangeDelta' in prof,
 'debug_truth':'phase10RawGtmSceneReferredOnly=' in isp,
}
fail=[k for k,v in checks.items() if not v]
if fail: raise SystemExit('PHASE10_0067_GTM_OWNERSHIP_FAIL='+','.join(fail))
print('PHASE10_0067_GTM_OWNERSHIP_PASS='+str(len(checks)))
