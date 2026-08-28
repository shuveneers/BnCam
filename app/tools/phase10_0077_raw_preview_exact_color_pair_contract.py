from pathlib import Path
r=Path(__file__).resolve().parents[1]
m=(r/'src/main/java/com/bncam/core/engine/BnCameraManager.kt').read_text()
p=(r/'src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt').read_text()
checks={
 'timestamp_keyed_pair_store':'exactFrameColorPairs = ConcurrentHashMap<Long, ExactFrameColorPair>()' in p,
 'pair_contains_wb_and_ccm':'val wbGains: FloatArray' in p and 'val colorMatrix: FloatArray' in p,
 'render_consumes_pair_by_frame_timestamp':'exactFrameColorPairs.remove(request.sensorTimestampNs)' in p,
 'pair_controls_both_native_inputs':'effectiveWbGains = liveWb ?: exactFramePair?.wbGains' in p and 'effectiveColorMatrix = exactFramePair?.colorMatrix' in p,
 'native_uses_effective_pair':'wbGains = effectiveWbGains' in p and 'colorMatrix = effectiveColorMatrix' in p,
 'camera_result_pair_includes_transform':'calibrationResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)' in m,
 'camera_result_pair_includes_gains':'calibrationResult.get(CaptureResult.COLOR_CORRECTION_GAINS)' in m,
 'camera_result_pair_keyed_sensor_timestamp':'calibrationResult.get(CaptureResult.SENSOR_TIMESTAMP)' in m and 'updateExactFrameCamera2ColorPair' in m,
 'stable_wb_not_used_when_exact_pair':'quality.whiteBalanceGains.fromMetadata && !exactPreviewColorPair' in m,
 'system_does_not_push_stable_wb_only':'rawPreviewRenderer.updateWhiteBalanceGains(after.copyGains())' not in m,
 'manual_wb_clears_system_pairs':'rawPreviewRenderer.clearExactFrameCamera2ColorPairs()' in m,
 'system_clears_manual_override':'rawPreviewRenderer.updateWhiteBalanceGains(null)' in m,
}
f=[k for k,v in checks.items() if not v]
if f: raise SystemExit('PHASE10_0077_RAW_PREVIEW_EXACT_COLOR_PAIR_FAIL='+','.join(f))
print('PHASE10_0077_RAW_PREVIEW_EXACT_COLOR_PAIR_PASS='+str(len(checks)))
