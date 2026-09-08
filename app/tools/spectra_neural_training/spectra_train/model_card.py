from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping, Sequence


def render_model_card(
    model_version: str,
    teacher_version: str,
    architecture: Mapping[str, Any],
    parameter_count: int,
    training_sensors: Sequence[str],
    held_out_sensors: Sequence[str],
    dataset_versions: Sequence[str],
    safety_k: float,
    posterior_calibration: str,
    known_failures: Sequence[str],
    runtime_targets: str = "offline Teacher only",
    status: str = "REFERENCE STACK / NOT YET TRAINED ON RELEASE DATA",
) -> str:
    return f"""# SPECTRA Neural model card — {model_version}

**Status:** {status}

- Model version: `{model_version}`
- Teacher version: `{teacher_version}`
- Student architecture: not applicable in Phase 2; Student belongs to Phase 3
- Teacher architecture: `{architecture}`
- Parameter count: `{parameter_count}`
- Precision: training FP32/BF16/FP16 as configured; production precision not claimed by this Teacher
- Supported CFA: standard Bayer, canonical `[R,G1,G2,B]`
- Training sensors: `{list(training_sensors)}`
- Held-out sensors: `{list(held_out_sensors)}`
- Dataset versions: `{list(dataset_versions)}`
- Noise ranges: physics-conditioned `Var=S*x+O` plus dark-frame/electronics/spatial randomization
- Posterior calibration: {posterior_calibration}
- Safety K: `{safety_k}` sigma starting research bound; requires held-out ablation
- Runtime targets: {runtime_targets}

## Known failures / unproven areas

{chr(10).join('- ' + x for x in known_failures) if known_failures else '- none recorded'}

## Non-goals

This Teacher is not an Android production model, does not perform color/tone/style correction, and does not authorize a classical fallback. No release-quality claim exists until real paired/dark-frame/held-out validation is complete.
"""


def write_model_card(path: str | Path, **kwargs: Any) -> None:
    Path(path).write_text(render_model_card(**kwargs), encoding="utf-8")
