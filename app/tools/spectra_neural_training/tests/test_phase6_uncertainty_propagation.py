from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ISP = ROOT / "app/src/main/cpp/IspCore.cpp"

def test_neural_posterior_replaces_pre_demosaic_covariance_when_published():
    s = ISP.read_text()
    assert "neuralProductionTrace.neuralPublished" in s
    assert "posteriorSummaryReady" in s
    assert '"POST_NEURAL_POST_LSC_PRE_DEMOSAIC"' in s
    assert '"STUDENT_POSTERIOR_GPU_REDUCED_PLUS_LSC_VARIANCE_GAIN"' in s
    assert "neuralPosteriorSeedApplied = true" in s

def test_remaining_lsc_is_variance_gain_not_iso_model():
    s = ISP.read_text()
    assert "visibleVarianceByChannel[channel] / rawVarianceByChannel[channel]" in s
    assert "posterior * varianceGain" in s
    block = s[s.index("// Phase 6: when neural pixels"):s.index("// Spatial exposure is a real multiplicative RAW transform")]
    assert "captureSensitivityIso" not in block
    assert "exposureTime" not in block

def test_existing_downstream_covariance_chain_consumes_neural_seed():
    s = ISP.read_text()
    seed = s.index('"POST_NEURAL_POST_LSC_PRE_DEMOSAIC"')
    demosaic = s.index("propagateDemosaic(", seed)
    awb = s.index("propagateAwb(", demosaic)
    ccm = s.index("propagateColourMatrix(", awb)
    detail = s.index("resolve(\n            {uiConfig.profileDetailAmount", ccm)
    assert seed < demosaic < awb < ccm < detail
    assert "residualNoiseState.postTone.confidence" in s[detail:detail+900]

def test_auto_hybrid_and_typed_demosaic_both_have_uncertainty_paths():
    s = ISP.read_text()
    assert "propagateAutoHybridDemosaic(" in s
    assert "propagateDemosaic(" in s
