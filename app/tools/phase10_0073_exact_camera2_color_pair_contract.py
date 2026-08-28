from pathlib import Path
r=Path(__file__).resolve().parents[1]
k=(r/'src/main/java/com/bncam/core/quality/SensorCalibration.kt').read_text()
i=(r/'src/main/cpp/IspCore.cpp').read_text()
checks={
 'system_route_explicit':'val systemAwbRequested' in k,
 'exact_wb_provenance':'CaptureResult.COLOR_CORRECTION_GAINS' in k and 'exactFrameCamera2Wb' in k,
 'exact_ccm_provenance':'CaptureResult.COLOR_CORRECTION_TRANSFORM' in k and 'exactFrameCamera2Ccm' in k,
 'atomic_pair':'exactFrameCamera2ColorPair = systemAwbRequested && exactFrameCamera2Wb && exactFrameCamera2Ccm' in k,
 'still_prefers_exact_wb':'systemAwbRequested && exactFrameCamera2Wb -> base.baseWbGains.copyOf()' in k,
 'stable_not_mixed_with_current_ccm':'systemAwbRequested && !exactFrameCamera2Wb && !exactFrameCamera2Ccm' in k,
 'stable_source_not_false_capture_provenance':'BnCam stable Camera2 AWB bootstrap' in k and 'BnCam stable CaptureResult AWB' not in k,
 'incomplete_pair_warning':'Historical stable WB is not mixed with current-frame color metadata.' in k,
 'exact_pair_source':'CaptureResult exact-frame color pair: COLOR_CORRECTION_GAINS + COLOR_CORRECTION_TRANSFORM' in k,
 'phase7_exact_pair_gate':'const bool exactCamera2ColorPair = uiConfig.wbFromMetadata && uiConfig.colorMatrixFromMetadata;' in i,
 'phase7_no_phone_cct_rewrite':'if (!exactCamera2ColorPair && meta.phoneAssistanceSensorsEnabled' in i,
 'phase7_observer_only':'CAMERA2_EXACT_FRAME_COLOR_PAIR_OBSERVER_ONLY' in i and 'phase7AwbEstimate.dataAuthority = 0.0;' in i,
 'phase7_restores_prior':'phase7AwbEstimate.finalGainsRgb[0] = phase7AwbEstimate.priorGainsRgb[0];' in i and 'phase7AwbEstimate.finalGainsRgb[2] = phase7AwbEstimate.priorGainsRgb[2];' in i,
 'phase7_pair_telemetry':'phase7ExactCamera2ColorPair=' in i,
 'phase8_direct_ccm_preserved':'CaptureResult.COLOR_CORRECTION_TRANSFORM -> linear_sRGB' in k,
 'phase8_no_row_normalization':'Phase8 validated matrix accepted without row normalization' in k,
 'phase8_forward_calibration_preserved':'inverse(SENSOR_CALIBRATION_TRANSFORM2)' in k and 'inverse(SENSOR_CALIBRATION_TRANSFORM1)' in k,
}
f=[x for x,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0073_EXACT_CAMERA2_COLOR_PAIR_FAIL='+','.join(f))
print('PHASE10_0073_EXACT_CAMERA2_COLOR_PAIR_PASS='+str(len(checks)))
