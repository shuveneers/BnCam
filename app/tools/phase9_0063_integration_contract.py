from pathlib import Path
root = Path(__file__).resolve().parents[1]
shader = (root/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
header = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
backend = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
isp = (root/'src/main/cpp/IspCore.cpp').read_text()
checks = {
    '16x16_shader_workgroup': 'layout(local_size_x = 16, local_size_y = 16' in shader,
    'tile_shared_opponent': 'shared vec4 phase9TileOpponent[256]' in shader,
    'all_lanes_barrier_before_valid_return': shader.index('barrier();', shader.index('void runPreTonePreparation')) < shader.index('if (!valid) return;', shader.index('void runPreTonePreparation')),
    'tile_linear_chroma_model': 'slopes = (meanYC - meanY * meanC)' in shader,
    'tile_residual_variance': 'residualSq += residual * residual' in shader,
    'measured_noise_pressure': 'physicalPressure = clamp(pc.shoulderStart' in shader and 'observedPressure = smoothstep' in shader,
    'fine_5x5_regression_retained': 'const int radius = 2;' in shader and 'finePredictedC' in shader,
    'coherent_isoluminant_chroma_edge_guard': 'coherentChromaGradient' in shader and 'chromaDetailProtection' in shader,
    'independent_luma_edge_guard': 'lumaDetailProtection' in shader,
    'stochastic_variance_gate': 'stochasticEvidence' in shader and 'localResidualVariance' in shader,
    'per_opponent_channel_evidence': 'vec2 channelEvidence' in shader and 'vec2 opponentAuthority' in shader,
    'no_extra_luma_authority': 'float lumaStrength = min(strength, 0.64);' in shader and 'float lumaAuthority = clamp(0.08 + 0.28 * lumaStrength, 0.0, 0.34);' in shader,
    'no_upper_scene_linear_clamp': 'return max(outRgb, vec3(0.0));' in shader,
    'request_physical_pressure': 'preToneChromaNoisePressure' in header and 'preToneChromaWbCcmPressure' in header,
    'mode0_push_reuse_no_layout_growth': 'push.shoulderStart = std::clamp(request.preToneChromaNoisePressure' in backend,
    'compact_tile_telemetry': 'preToneChromaTilesScanned = telemetry[0]' in backend and 'preToneChromaMaxCorrection' in backend,
    'isp_passes_measured_evidence': 'request.preToneChromaNoisePressure = galoshPreToneChromaPlan.noisePressure' in isp,
    'isp_debug_tile_scan': 'phase9ChromaTileScanApplied=' in isp and 'phase9ChromaDetailProtectedPixels=' in isp,
    'no_cpu_tile_scan': 'phase9TileOpponent' not in isp,
    'no_box_rgb_blur': 'boxBlur' not in shader and 'blurRgb' not in shader[shader.index('vec3 preToneChroma444FromInput'):shader.index('void writeSceneSample')],
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE9_0063_INTEGRATION_CONTRACT_FAIL=' + ','.join(failed))
print('PHASE9_0063_INTEGRATION_CONTRACT_PASS=' + str(len(checks)))
