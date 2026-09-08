from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence

import numpy as np
import torch
import yaml

from .contracts import RecordKind
from .dataset import RawManifestDataset
from .distillation import DistillationWeights, compute_student_distillation_loss, freeze_teacher
from .evaluation import evaluate_prediction
from .losses import LossWeights, TeacherLossBatch
from .manifest import SensorSplitPolicy, load_manifest, records_for_split
from .model_student import PhysicsConditionedStudent, StudentConfig
from .model_teacher import PhysicsConditionedTeacher, TeacherConfig
from .reproducibility import RunProvenance, environment_snapshot, git_commit, seed_everything, sha256_file, write_run_provenance
from .synthesis import DarkFrameBank, NoiseSynthesisConfig, PhysicsConditionedSampleBuilder
from .train_teacher import _batch, _loss_weights, _mean_metrics, _synthesis_config, crop_training_sample


def _student_config(m: Mapping[str, Any]) -> StudentConfig:
    return StudentConfig(
        name=str(m.get("name", "spectra_student_hq_v1")),
        variant=str(m.get("variant", "hq")),
        input_channels=int(m.get("input_channels", 14)),
        global_condition_dim=int(m.get("global_condition_dim", 18)),
        widths=tuple(int(v) for v in m.get("widths", (48, 72, 104, 144))),
        encoder_blocks=tuple(int(v) for v in m.get("encoder_blocks", (3, 4, 5))),
        bottleneck_blocks=int(m.get("bottleneck_blocks", 6)),
        decoder_blocks=tuple(int(v) for v in m.get("decoder_blocks", (5, 4, 3))),
        residual_k_sigma=float(m.get("residual_k_sigma", 4.0)),
        posterior_log_ratio_min=float(m.get("posterior_log_ratio_min", -6.0)),
        posterior_log_ratio_max=float(m.get("posterior_log_ratio_max", 3.0)),
        confidence_head=bool(m.get("confidence_head", True)),
        global_lowres_context=bool(m.get("global_lowres_context", False)),
        fine_coarse_residual_head=bool(m.get("fine_coarse_residual_head", False)),
    )


def _teacher_config_from_checkpoint(m: Mapping[str, Any]) -> TeacherConfig:
    return TeacherConfig(
        input_channels=int(m.get("input_channels", 14)),
        global_condition_dim=int(m.get("global_condition_dim", 18)),
        widths=tuple(int(v) for v in m.get("widths", (80, 120, 176, 240))),
        encoder_blocks=tuple(int(v) for v in m.get("encoder_blocks", (4, 6, 8))),
        bottleneck_blocks=int(m.get("bottleneck_blocks", 10)),
        decoder_blocks=tuple(int(v) for v in m.get("decoder_blocks", (8, 6, 4))),
        residual_k_sigma=float(m.get("residual_k_sigma", 4.0)),
        posterior_log_ratio_min=float(m.get("posterior_log_ratio_min", -6.0)),
        posterior_log_ratio_max=float(m.get("posterior_log_ratio_max", 3.0)),
        confidence_head=bool(m.get("confidence_head", True)),
    )


def _distillation_weights(m: Mapping[str, Any]) -> DistillationWeights:
    defaults = DistillationWeights()
    return DistillationWeights(**{k: float(m.get(k, v)) for k, v in defaults.__dict__.items()})


def _load_teacher(path: Path, expected_sha256: str, device: torch.device) -> tuple[PhysicsConditionedTeacher, str, Mapping[str, Any]]:
    actual_hash = sha256_file(path)
    if expected_sha256 and actual_hash.lower() != expected_sha256.lower():
        raise ValueError("teacher checkpoint SHA-256 mismatch")
    payload = torch.load(path, map_location="cpu", weights_only=False)
    if int(payload.get("schema_version", -1)) != 1 or "model_config" not in payload or "model_state" not in payload:
        raise ValueError("unsupported teacher checkpoint contract")
    teacher = PhysicsConditionedTeacher(_teacher_config_from_checkpoint(payload["model_config"]))
    teacher.load_state_dict(payload["model_state"], strict=True)
    teacher.to(device)
    freeze_teacher(teacher)
    return teacher, actual_hash, payload


def _teacher_student_gap(student_output, teacher_output) -> Dict[str, float]:
    return {
        "teacher_clean_mae": float(torch.mean(torch.abs(student_output.clean_raw - teacher_output.clean_raw)).item()),
        "teacher_residual_sigma_mae": float(torch.mean(torch.abs(student_output.sigma_normalized_residual - teacher_output.sigma_normalized_residual)).item()),
        "teacher_posterior_logratio_mae": float(torch.mean(torch.abs(student_output.posterior_log_variance_ratio - teacher_output.posterior_log_variance_ratio)).item()),
    }


def run_student_training(config_path: str | Path) -> Dict[str, Any]:
    config_path = Path(config_path).resolve()
    cfg = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    seed = int(cfg["training"].get("seed", 1337))
    deterministic = bool(cfg["training"].get("deterministic", True))
    seed_everything(seed, deterministic)

    manifest = load_manifest((config_path.parent / cfg["dataset"]["manifest"]).resolve())
    split = SensorSplitPolicy.from_mapping(cfg["dataset"]["sensor_split"])
    split.validate(manifest.records)
    run_dir = (config_path.parent / cfg["training"]["run_dir"]).resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device(cfg["training"].get("device", "cuda" if torch.cuda.is_available() else "cpu"))

    teacher_path = (config_path.parent / cfg["teacher"]["checkpoint"]).resolve()
    teacher, teacher_hash, teacher_checkpoint = _load_teacher(
        teacher_path, str(cfg["teacher"].get("expected_sha256", "")), device
    )
    student_cfg = _student_config(cfg["student"])
    student = PhysicsConditionedStudent(student_cfg).to(device)
    fidelity_weights = _loss_weights(cfg.get("loss_weights", {}))
    distill_weights = _distillation_weights(cfg.get("distillation_weights", {}))
    synth_cfg = _synthesis_config(cfg.get("synthesis", {}))

    train_records = records_for_split(manifest, split, "train", [RecordKind.CLEAN, RecordKind.PAIRED])
    val_records = records_for_split(manifest, split, "validation", [RecordKind.CLEAN, RecordKind.PAIRED])
    test_records = records_for_split(manifest, split, "test", [RecordKind.CLEAN, RecordKind.PAIRED])
    dark_records = records_for_split(manifest, split, "train", [RecordKind.DARK])
    train_ds = RawManifestDataset(manifest, train_records)
    val_ds = RawManifestDataset(manifest, val_records)
    test_ds = RawManifestDataset(manifest, test_records)
    dark_bank = DarkFrameBank.from_dataset(RawManifestDataset(manifest, dark_records)) if dark_records else None

    optimizer_cfg = cfg["optimizer"]
    optimizer = torch.optim.AdamW(
        student.parameters(),
        lr=float(optimizer_cfg["lr"]),
        weight_decay=float(optimizer_cfg.get("weight_decay", 0.0)),
    )
    stages = tuple(cfg["curriculum"])
    total_epochs = sum(int(s.get("epochs", 0)) for s in stages)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(total_epochs, 1))
    crop_generator = torch.Generator().manual_seed(seed + 881)
    train_rng = np.random.default_rng(seed + 19)
    batch_size = int(cfg["training"].get("batch_size", 1))
    steps_per_epoch = int(cfg["training"].get("steps_per_epoch", max(1, len(train_ds))))

    provenance = RunProvenance(
        schema_version=1,
        git_commit=git_commit(cfg["training"].get("repo_root", ".")),
        dataset_manifest_sha256=manifest.sha256,
        dataset_name=manifest.dataset_name,
        dataset_version=manifest.dataset_version,
        sensor_split=cfg["dataset"]["sensor_split"],
        rng_seed=seed,
        deterministic_algorithms=deterministic,
        architecture_config=asdict(student_cfg),
        loss_weights={"fidelity": asdict(fidelity_weights), "distillation": asdict(distill_weights)},
        optimizer=dict(optimizer_cfg),
        lr_schedule={"type": "CosineAnnealingLR", "T_max": max(total_epochs, 1)},
        augmentation_config={"cfa_safe": True, "progressive_packed_crops": [int(s["crop_size"]) for s in stages]},
        curriculum=stages,
        teacher_hash=teacher_hash,
        student_hash="UNEXPORTED_AT_RUN_START",
        export_tool_version="PHASE3_EXPORT_NOT_RUN_YET",
        environment=environment_snapshot(),
    )
    provenance_hash = write_run_provenance(run_dir / "run_provenance.json", provenance)

    history: List[Dict[str, Any]] = []
    epoch_index = 0
    for stage in stages:
        crop_size = int(stage["crop_size"])
        epochs = int(stage.get("epochs", 1))
        regime = stage.get("regime")
        for _ in range(epochs):
            student.train()
            epoch_losses = []
            for _step in range(steps_per_epoch):
                samples = []
                builder = PhysicsConditionedSampleBuilder(
                    synth_cfg, dark_bank=dark_bank, seed=seed + epoch_index * 1000 + _step, device=device
                )
                for _b in range(batch_size):
                    item = train_ds[int(train_rng.integers(0, len(train_ds)))]
                    sample = builder.build(item, regime=regime)
                    sample = crop_training_sample(sample, crop_size, crop_generator)
                    samples.append(sample)
                batch = _batch(samples)
                optimizer.zero_grad(set_to_none=True)
                student_output, student_features = student(batch["conditioning"], batch["global"], return_features=True)
                with torch.no_grad():
                    teacher_output, teacher_features = teacher.forward_with_features(batch["conditioning"], batch["global"])
                fidelity_batch = TeacherLossBatch(
                    batch["clean"], batch["noisy"], batch["variance"], batch["lsc"], batch["identity"]
                )
                total, _terms = compute_student_distillation_loss(
                    student_output, teacher_output, student_features, teacher_features,
                    fidelity_batch, fidelity_weights, distill_weights,
                )
                total.backward()
                torch.nn.utils.clip_grad_norm_(student.parameters(), float(cfg["training"].get("grad_clip_norm", 1.0)))
                optimizer.step()
                epoch_losses.append(float(total.detach().item()))
            scheduler.step()
            history.append({
                "epoch": epoch_index,
                "stage": str(stage.get("name", f"stage_{epoch_index}")),
                "crop_size": crop_size,
                "loss": float(np.mean(epoch_losses)),
                "lr": optimizer.param_groups[0]["lr"],
            })
            epoch_index += 1

    def evaluate_dataset(ds: RawManifestDataset, seed_offset: int) -> Dict[str, float]:
        student.eval()
        teacher.eval()
        rows: List[Dict[str, float]] = []
        builder = PhysicsConditionedSampleBuilder(synth_cfg, seed=seed + seed_offset, device=device)
        crop_size = int(cfg["training"].get("evaluation_crop_size", 512))
        with torch.no_grad():
            for i in range(len(ds)):
                item = ds[i]
                sample = builder.build(item, regime="moderate" if item.noisy is None else None)
                size = min(crop_size, sample.clean.shape[-2], sample.clean.shape[-1])
                sample = crop_training_sample(sample, size, crop_generator)
                student_output = student(sample.conditioning[None], sample.global_condition[None])
                teacher_output = teacher(sample.conditioning[None], sample.global_condition[None])
                metrics = evaluate_prediction(
                    student_output, sample.conditioning[None], sample.noisy[None], sample.clean[None], student_cfg.residual_k_sigma
                )
                metrics.update(_teacher_student_gap(student_output, teacher_output))
                rows.append(metrics)
        return _mean_metrics(rows)

    validation_metrics = evaluate_dataset(val_ds, 3001)
    zero_shot_test_metrics = evaluate_dataset(test_ds, 4001)
    checkpoint = {
        "schema_version": 1,
        "student_config": asdict(student_cfg),
        "student_state": student.state_dict(),
        "teacher_checkpoint_sha256": teacher_hash,
        "teacher_model_config": teacher_checkpoint["model_config"],
        "optimizer_state": optimizer.state_dict(),
        "scheduler_state": scheduler.state_dict(),
        "history": history,
        "run_provenance_sha256": provenance_hash,
        "validation_metrics": validation_metrics,
        "zero_shot_test_metrics": zero_shot_test_metrics,
    }
    ckpt_path = run_dir / "student_last.pt"
    torch.save(checkpoint, ckpt_path)
    checkpoint_hash = sha256_file(ckpt_path)
    summary = {
        "checkpoint": str(ckpt_path),
        "checkpoint_sha256": checkpoint_hash,
        "teacher_checkpoint_sha256": teacher_hash,
        "parameter_count": student.parameter_count(),
        "validation_metrics": validation_metrics,
        "zero_shot_test_metrics": zero_shot_test_metrics,
        "history": history,
    }
    (run_dir / "student_summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Train/distill the SPECTRA Neural mobile Student")
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    print(json.dumps(run_student_training(args.config), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
