from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping


def render_student_model_card(student_manifest: Mapping[str, Any], scorecard: Mapping[str, Any]) -> str:
    per_sensor = scorecard.get("per_sensor", {})
    posterior = {
        sensor: row.get("metrics", {}).get("student_posterior_fraction_within_1sigma")
        for sensor, row in per_sensor.items()
    }
    return f"""# SPECTRA Neural Student model card — {student_manifest['model_name']} {student_manifest['model_version']}

**Status:** CANDIDATE EXPORT / VULKAN + ON-DEVICE QUALITY NOT YET VALIDATED

- Model name: `{student_manifest['model_name']}`
- Model version: `{student_manifest['model_version']}`
- Model content SHA-256: `{student_manifest['model_content_sha256']}`
- Teacher checkpoint SHA-256: `{student_manifest.get('teacher_checkpoint_sha256', '')}`
- Student architecture: `{student_manifest['architecture_config']}`
- Parameter count: `{student_manifest['parameter_count']}`
- Precision: `{student_manifest['precision']}` weights/production reference activations
- Supported CFA: canonical `[R,G1,G2,B]` from RGGB/GRBG/GBRG/BGGR
- Training sensors: `{scorecard.get('train_sensors', [])}`
- Validation sensors: `{scorecard.get('validation_sensors', [])}`
- Held-out sensors: `{scorecard.get('heldout_sensors', [])}`
- Held-out evidence class: `{scorecard.get('heldout_evidence_summary', {})}`
- Dataset: `{scorecard.get('dataset_name')}:{scorecard.get('dataset_version')}`
- Dataset manifest SHA-256: `{scorecard.get('dataset_manifest_sha256')}`
- Posterior calibration observation (fraction within 1σ by held-out sensor): `{posterior}`
- Safety K: `{student_manifest['output_schema']['residual_k_sigma']}` sigma hard model bound
- Theoretical packed receptive field: `{student_manifest['theoretical_receptive_field_packed']}`
- Minimum symmetric theoretical halo: `{student_manifest['minimum_symmetric_halo_packed']}`

## Selected v1 architecture decisions

- NAF-like normalization-free convolutional Student: selected.
- Low-resolution global context branch: implemented as research ablation, not selected in baseline export without held-out quality/runtime evidence.
- Fine/coarse residual heads: implemented as research ablation, not selected in baseline export without held-out evidence.
- Mamba/SSM bottleneck: not selected or faked in v1; reconsider only if a real ablation demonstrates a quality/runtime advantage.
- FP16: selected baseline. INT8/QAT is not enabled.

## Known unproven areas

- Vulkan numerical parity is Phase 4 work.
- Android device latency/memory is not measured by Phase 3.
- On-device RAW10/RAW_SENSOR image quality is not measured by Phase 3.
- No production quality claim is valid without governed real paired/dark-frame training data and real sensor-held-out evaluation.
- User-control projection and production OOD/fail-bypass integration remain later phases.

## Non-goals

The Student does not learn color, tone, style, highlight reconstruction, semantic enhancement or a classical fallback. Its output contract remains a physically bounded RAW residual plus posterior uncertainty.
"""


def write_student_model_card(path: str | Path, student_manifest: Mapping[str, Any], scorecard: Mapping[str, Any]) -> None:
    Path(path).write_text(render_student_model_card(student_manifest, scorecard), encoding="utf-8")
