from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence

import numpy as np
import torch
import yaml

from .contracts import RecordKind
from .dataset import RawManifestDataset
from .evaluation import evaluate_prediction
from .export_student import verify_exported_student
from .manifest import SensorSplitPolicy, load_manifest, records_for_split
from .model_student import StudentOutput
from .reference_inference import load_exported_student
from .reproducibility import sha256_file
from .synthesis import NoiseSynthesisConfig, PhysicsConditionedSampleBuilder
from .student_model_card import write_student_model_card
from .train_student import _load_teacher, _mean_metrics, _synthesis_config, _teacher_student_gap

SCORECARD_SCHEMA_VERSION = 1


def _float_student_output(output: StudentOutput) -> StudentOutput:
    def cast(x):
        return None if x is None else x.float()
    return StudentOutput(
        clean_raw=output.clean_raw.float(),
        residual=output.residual.float(),
        sigma_normalized_residual=output.sigma_normalized_residual.float(),
        posterior_variance=output.posterior_variance.float(),
        posterior_log_variance_ratio=output.posterior_log_variance_ratio.float(),
        confidence=cast(output.confidence),
        fine_sigma_residual=cast(output.fine_sigma_residual),
        coarse_sigma_residual=cast(output.coarse_sigma_residual),
    )


def _center_crop(sample, size: int):
    h, w = sample.clean.shape[-2:]
    size = min(size, h, w)
    if size <= 0:
        raise ValueError("evaluation crop must be positive")
    y = (h - size) // 2
    x = (w - size) // 2
    for name in ("noisy", "clean", "conditioning", "input_variance", "remaining_lsc"):
        value = getattr(sample, name)
        setattr(sample, name, value[..., y:y + size, x:x + size])
    return sample


def _prefixed(prefix: str, metrics: Mapping[str, float]) -> Dict[str, float]:
    return {f"{prefix}{k}": float(v) for k, v in metrics.items()}


def _contract_gates(metrics: Mapping[str, float], residual_k_sigma: float) -> Dict[str, Any]:
    finite = all(np.isfinite(float(v)) for v in metrics.values())
    clipped = float(metrics.get("student_clipped_cells_touched_fraction", 0.0))
    p99 = float(metrics.get("student_correction_sigma_p99", 0.0))
    return {
        "all_metrics_finite": bool(finite),
        "clipped_cells_untouched": bool(clipped == 0.0),
        "p99_within_hard_k_bound": bool(p99 <= residual_k_sigma + 1.0e-3),
    }


def _render_markdown(scorecard: Mapping[str, Any]) -> str:
    lines = [
        "# SPECTRA Neural Student — held-out sensor scorecard",
        "",
        f"**Student:** `{scorecard['model_name']}` version `{scorecard['model_version']}`",
        f"**Model hash:** `{scorecard['model_content_sha256']}`",
        f"**Dataset:** `{scorecard['dataset_name']}:{scorecard['dataset_version']}`",
        f"**Dataset manifest SHA-256:** `{scorecard['dataset_manifest_sha256']}`",
        f"**Held-out sensors:** `{scorecard['heldout_sensors']}`",
        "",
        "This scorecard is sensor-disjoint by contract. Sensor identifiers are used only for governance/reporting and never as neural conditioning.",
        "",
    ]
    for sensor, row in scorecard["per_sensor"].items():
        lines.extend([
            f"## Held-out sensor `{sensor}`",
            "",
            f"- samples: {row['sample_count']}",
            f"- evidence: `{row['evidence']}`",
            f"- Student PSNR: {row['metrics'].get('student_psnr', float('nan')):.6f}",
            f"- Student MAE: {row['metrics'].get('student_mae', float('nan')):.8f}",
            f"- Student gradient error: {row['metrics'].get('student_gradient_error', float('nan')):.8f}",
            f"- Student residual/input variance ratio: {row['metrics'].get('student_residual_to_input_variance_ratio', float('nan')):.8f}",
            f"- Student posterior within 1σ: {row['metrics'].get('student_posterior_fraction_within_1sigma', float('nan')):.6f}",
            f"- Teacher residual sigma MAE: {row['metrics'].get('gap_teacher_residual_sigma_mae', float('nan')):.8f}",
            f"- Contract gates: `{row['contract_gates']}`",
            "",
        ])
    lines.extend([
        "## Release interpretation",
        "",
        "The invariant gates above prove schema/safety properties only. They are not substitute quality thresholds. Production quality requires governed real held-out sensor data and explicit image-quality acceptance criteria; this tool does not invent those thresholds.",
        "",
    ])
    return "\n".join(lines)


def run_heldout_scorecard(config_path: str | Path) -> Dict[str, Any]:
    config_path = Path(config_path).resolve()
    cfg = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    manifest = load_manifest((config_path.parent / cfg["dataset"]["manifest"]).resolve())
    split = SensorSplitPolicy.from_mapping(cfg["dataset"]["sensor_split"])
    split.validate(manifest.records)
    if not split.test_sensors:
        raise ValueError("held-out scorecard requires test_sensors")

    student_manifest_path = (config_path.parent / cfg["student"]["manifest"]).resolve()
    student_manifest = verify_exported_student(student_manifest_path)
    student, _ = load_exported_student(student_manifest_path)
    device = torch.device("cpu")

    teacher_path = (config_path.parent / cfg["teacher"]["checkpoint"]).resolve()
    expected_teacher_hash = str(cfg["teacher"].get("expected_sha256", student_manifest.get("teacher_checkpoint_sha256", "")))
    teacher, teacher_hash, _ = _load_teacher(teacher_path, expected_teacher_hash, device)
    bound_teacher_hash = str(student_manifest.get("teacher_checkpoint_sha256", ""))
    if bound_teacher_hash and bound_teacher_hash != teacher_hash:
        raise ValueError("exported Student is bound to a different Teacher checkpoint")

    synth_cfg = _synthesis_config(cfg.get("synthesis", {}))
    seed = int(cfg.get("evaluation", {}).get("seed", 9901))
    crop_size = int(cfg.get("evaluation", {}).get("crop_size", 512))
    residual_k_sigma = float(student_manifest["output_schema"]["residual_k_sigma"])

    per_sensor: Dict[str, Any] = {}
    macro_rows: List[Mapping[str, float]] = []
    previous_threads = torch.get_num_threads()
    try:
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True, warn_only=False)
        for sensor_index, sensor in enumerate(split.test_sensors):
            records = tuple(
                r for r in records_for_split(manifest, split, "test", [RecordKind.CLEAN, RecordKind.PAIRED])
                if r.sensor_id == sensor
            )
            if not records:
                raise ValueError(f"held-out sensor has no clean/paired evaluation records: {sensor}")
            ds = RawManifestDataset(manifest, records)
            builder = PhysicsConditionedSampleBuilder(synth_cfg, seed=seed + sensor_index * 1000, device=device)
            rows: List[Dict[str, float]] = []
            for i in range(len(ds)):
                item = ds[i]
                sample = builder.build(item, regime="moderate" if item.noisy is None else None)
                sample = _center_crop(sample, crop_size)
                cond_fp16 = sample.conditioning[None].half()
                global_fp16 = sample.global_condition[None].half()
                with torch.inference_mode():
                    student_output = _float_student_output(student(cond_fp16, global_fp16))
                    teacher_output = teacher(sample.conditioning[None], sample.global_condition[None])
                student_metrics = evaluate_prediction(
                    student_output, sample.conditioning[None], sample.noisy[None], sample.clean[None], residual_k_sigma
                )
                teacher_metrics = evaluate_prediction(
                    teacher_output, sample.conditioning[None], sample.noisy[None], sample.clean[None], teacher.config.residual_k_sigma
                )
                gap = _teacher_student_gap(student_output, teacher_output)
                row: Dict[str, float] = {}
                row.update(_prefixed("student_", student_metrics))
                row.update(_prefixed("teacher_", teacher_metrics))
                row.update(_prefixed("gap_", gap))
                rows.append(row)
            metrics = _mean_metrics(rows)
            gates = _contract_gates(metrics, residual_k_sigma)
            paired_count = sum(1 for r in records if r.kind == RecordKind.PAIRED)
            synthetic_clean_count = sum(1 for r in records if r.kind == RecordKind.CLEAN)
            evidence = {
                "real_paired_records": paired_count,
                "synthetic_from_clean_records": synthetic_clean_count,
                "evidence_class": "REAL_PAIRED_PRESENT" if paired_count else "SYNTHETIC_FROM_CLEAN_ONLY",
            }
            per_sensor[sensor] = {
                "sample_count": len(rows), "evidence": evidence,
                "metrics": metrics, "contract_gates": gates,
            }
            macro_rows.append(metrics)
    finally:
        torch.set_num_threads(previous_threads)

    macro = _mean_metrics(macro_rows)
    scorecard: Dict[str, Any] = {
        "schema_version": SCORECARD_SCHEMA_VERSION,
        "model_name": student_manifest["model_name"],
        "model_version": student_manifest["model_version"],
        "model_content_sha256": student_manifest["model_content_sha256"],
        "student_checkpoint_sha256": student_manifest["student_checkpoint_sha256"],
        "teacher_checkpoint_sha256": teacher_hash,
        "dataset_name": manifest.dataset_name,
        "dataset_version": manifest.dataset_version,
        "dataset_manifest_sha256": manifest.sha256,
        "train_sensors": list(split.train_sensors),
        "validation_sensors": list(split.validation_sensors),
        "heldout_sensors": list(split.test_sensors),
        "per_sensor": per_sensor,
        "heldout_evidence_summary": {
            sensor: row["evidence"]["evidence_class"] for sensor, row in per_sensor.items()
        },
        "macro_average": macro,
        "quality_thresholds": "NOT_INVENTED_BY_PHASE3_TOOLING",
        "release_interpretation": "contract/safety measurements only until governed quality thresholds and real held-out data are supplied",
    }
    out_dir = (config_path.parent / cfg.get("output", {}).get("dir", "heldout_scorecard")).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "heldout_scorecard.json").write_text(json.dumps(scorecard, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (out_dir / "heldout_scorecard.md").write_text(_render_markdown(scorecard), encoding="utf-8")
    write_student_model_card(out_dir / "MODEL_CARD_STUDENT.md", student_manifest, scorecard)
    return scorecard


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate exported SPECTRA Student on fully held-out sensors")
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    print(json.dumps(run_heldout_scorecard(args.config), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
