from __future__ import annotations

import argparse
import copy
import json
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Sequence

import numpy as np
import torch
import yaml

from .contracts import RecordKind
from .dataset import RawManifestDataset
from .evaluation import evaluate_prediction
from .losses import LossWeights, TeacherLossBatch, compute_teacher_loss
from .manifest import SensorSplitPolicy, load_manifest, records_for_split
from .model_card import write_model_card
from .model_teacher import PhysicsConditionedTeacher, TeacherConfig
from .reproducibility import RunProvenance, environment_snapshot, git_commit, seed_everything, sha256_file, write_run_provenance
from .synthesis import DarkFrameBank, NoiseSynthesisConfig, PhysicsConditionedSampleBuilder


def _teacher_config(m: Mapping[str, Any]) -> TeacherConfig:
    return TeacherConfig(
        input_channels=int(m.get("input_channels", 14)),
        global_condition_dim=int(m.get("global_condition_dim", 18)),
        widths=tuple(int(v) for v in m.get("widths", (80,120,176,240))),
        encoder_blocks=tuple(int(v) for v in m.get("encoder_blocks", (4,6,8))),
        bottleneck_blocks=int(m.get("bottleneck_blocks", 10)),
        decoder_blocks=tuple(int(v) for v in m.get("decoder_blocks", (8,6,4))),
        residual_k_sigma=float(m.get("residual_k_sigma", 4.0)),
        posterior_log_ratio_min=float(m.get("posterior_log_ratio_min", -6.0)),
        posterior_log_ratio_max=float(m.get("posterior_log_ratio_max", 3.0)),
        confidence_head=bool(m.get("confidence_head", True)),
    )


def _loss_weights(m: Mapping[str, Any]) -> LossWeights:
    fields = LossWeights().__dict__
    return LossWeights(**{k: float(m.get(k, v)) for k, v in fields.items()})


def _synthesis_config(m: Mapping[str, Any]) -> NoiseSynthesisConfig:
    cfg = NoiseSynthesisConfig()
    allowed = cfg.__dataclass_fields__.keys()
    values = {}
    for k, v in m.items():
        if k not in allowed or k == "regimes":
            continue
        current = getattr(cfg, k)
        if isinstance(current, tuple):
            values[k] = tuple(float(x) for x in v)
        else:
            values[k] = type(current)(v)
    return replace(cfg, **values)


def _stage_synthesis(base: NoiseSynthesisConfig, stage: str) -> NoiseSynthesisConfig:
    if stage == "A_ideal_physics":
        return replace(base, heavy_tail_probability=0.0, prnu_sigma_fraction=0.0, dsnu_sigma_fraction=0.0,
                       row_banding_sigma_fraction=0.0, column_banding_sigma_fraction=0.0,
                       black_residual_sigma_fraction=0.0, hot_pixel_probability=0.0,
                       lsc_max_gain_range=(1.0, 1.0), lsc_channel_imbalance=0.0)
    if stage == "B_real_electronics":
        return replace(base, lsc_max_gain_range=(1.0, 1.0), lsc_channel_imbalance=0.0)
    if stage in {"C_spatial_optics", "D_real_paired", "E_extreme_low_light", "F_held_out_validation"}:
        return base
    raise ValueError(f"unknown curriculum stage {stage}")


def _crop_tensor(x: torch.Tensor, y: int, x0: int, size: int) -> torch.Tensor:
    return x[..., y:y+size, x0:x0+size]


def crop_training_sample(sample, size: int, generator: torch.Generator):
    h, w = sample.clean.shape[-2:]
    if size <= 0 or size > h or size > w:
        raise ValueError(f"crop_size {size} exceeds sample {h}x{w}")
    max_y, max_x = h - size, w - size
    y = int(torch.randint(max_y + 1, (1,), generator=generator).item()) if max_y else 0
    x0 = int(torch.randint(max_x + 1, (1,), generator=generator).item()) if max_x else 0
    sample.noisy = _crop_tensor(sample.noisy, y, x0, size)
    sample.clean = _crop_tensor(sample.clean, y, x0, size)
    sample.conditioning = _crop_tensor(sample.conditioning, y, x0, size)
    sample.input_variance = _crop_tensor(sample.input_variance, y, x0, size)
    sample.remaining_lsc = _crop_tensor(sample.remaining_lsc, y, x0, size)
    return sample


def _batch(samples):
    return {
        "conditioning": torch.stack([s.conditioning for s in samples]),
        "global": torch.stack([s.global_condition for s in samples]),
        "clean": torch.stack([s.clean for s in samples]),
        "noisy": torch.stack([s.noisy for s in samples]),
        "variance": torch.stack([s.input_variance for s in samples]),
        "lsc": torch.stack([s.remaining_lsc for s in samples]),
        "identity": torch.tensor([s.identity_target for s in samples], dtype=torch.bool, device=samples[0].clean.device),
    }


def _mean_metrics(metric_rows: Sequence[Mapping[str, float]]) -> Dict[str, float]:
    if not metric_rows:
        return {}
    keys = set.intersection(*(set(r.keys()) for r in metric_rows))
    return {k: float(np.mean([r[k] for r in metric_rows])) for k in sorted(keys)}


def run_training(config_path: str | Path) -> Dict[str, Any]:
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
    model_cfg = _teacher_config(cfg["model"])
    model = PhysicsConditionedTeacher(model_cfg).to(device)
    weights = _loss_weights(cfg.get("loss_weights", {}))
    synth_base = _synthesis_config(cfg.get("synthesis", {}))

    train_scene_records = records_for_split(manifest, split, "train", [RecordKind.CLEAN, RecordKind.PAIRED])
    paired_train_records = records_for_split(manifest, split, "train", [RecordKind.PAIRED])
    val_scene_records = records_for_split(manifest, split, "validation", [RecordKind.CLEAN, RecordKind.PAIRED])
    test_scene_records = records_for_split(manifest, split, "test", [RecordKind.CLEAN, RecordKind.PAIRED])
    train_dark_records = records_for_split(manifest, split, "train", [RecordKind.DARK])
    train_ds = RawManifestDataset(manifest, train_scene_records)
    paired_train_ds = RawManifestDataset(manifest, paired_train_records) if paired_train_records else None
    val_ds = RawManifestDataset(manifest, val_scene_records)
    test_ds = RawManifestDataset(manifest, test_scene_records)
    dark_bank = DarkFrameBank.from_dataset(RawManifestDataset(manifest, train_dark_records)) if train_dark_records else None

    optimizer_cfg = cfg["optimizer"]
    optimizer = torch.optim.AdamW(model.parameters(), lr=float(optimizer_cfg["lr"]), weight_decay=float(optimizer_cfg.get("weight_decay", 0.0)))
    total_epochs = sum(
        int(stage_cfg.get("epochs", 0))
        for stage_cfg in cfg["curriculum"]
        if not stage_cfg.get("validation_only", False)
        and not (str(stage_cfg.get("name")) == "D_real_paired" and not paired_train_records)
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(total_epochs, 1))
    crop_generator = torch.Generator().manual_seed(seed + 991)
    steps_per_epoch = int(cfg["training"].get("steps_per_epoch", max(1, len(train_ds))))
    batch_size = int(cfg["training"].get("batch_size", 1))

    provenance = RunProvenance(
        schema_version=1,
        git_commit=git_commit(cfg["training"].get("repo_root", ".")),
        dataset_manifest_sha256=manifest.sha256,
        dataset_name=manifest.dataset_name,
        dataset_version=manifest.dataset_version,
        sensor_split=cfg["dataset"]["sensor_split"],
        rng_seed=seed,
        deterministic_algorithms=deterministic,
        architecture_config=asdict(model_cfg),
        loss_weights=asdict(weights),
        optimizer=dict(optimizer_cfg),
        lr_schedule={"type": "CosineAnnealingLR", "T_max": max(total_epochs, 1)},
        augmentation_config={"cfa_safe": True, "creative_color": False, "crop_sizes": [s.get("crop_size") for s in cfg["curriculum"]]},
        curriculum=cfg["curriculum"],
        teacher_hash="UNTRAINED_AT_RUN_START",
        student_hash="NOT_APPLICABLE_PHASE2",
        export_tool_version="NOT_APPLICABLE_PHASE2",
        environment=environment_snapshot(),
    )
    provenance_hash = write_run_provenance(run_dir / "run_provenance.json", provenance)

    history: List[Dict[str, Any]] = []
    train_index_rng = np.random.default_rng(seed + 17)
    global_epoch = 0
    for stage_cfg in cfg["curriculum"]:
        stage = str(stage_cfg["name"])
        if stage_cfg.get("validation_only", False):
            continue
        stage_synth = _stage_synthesis(synth_base, stage)
        builder = PhysicsConditionedSampleBuilder(stage_synth, dark_bank=dark_bank if stage != "A_ideal_physics" else None, seed=seed + global_epoch, device=device)
        epochs = int(stage_cfg.get("epochs", 1))
        crop_size = int(stage_cfg.get("crop_size", cfg["training"].get("crop_size", 256)))
        if stage == "D_real_paired":
            if paired_train_ds is None:
                history.append({"epoch": global_epoch, "stage": stage, "skipped": True, "reason": "no_real_paired_training_records"})
                continue
            stage_ds = paired_train_ds
        else:
            stage_ds = train_ds
        for _ in range(epochs):
            model.train()
            epoch_losses = []
            for _step in range(steps_per_epoch):
                samples = []
                for _b in range(batch_size):
                    item = stage_ds[int(train_index_rng.integers(0, len(stage_ds)))]
                    forced = "heavy" if stage == "E_extreme_low_light" and item.noisy is None else None
                    sample = builder.build(item, regime=forced)
                    sample = crop_training_sample(sample, crop_size, crop_generator)
                    samples.append(sample)
                b = _batch(samples)
                optimizer.zero_grad(set_to_none=True)
                output = model(b["conditioning"], b["global"])
                loss_batch = TeacherLossBatch(b["clean"], b["noisy"], b["variance"], b["lsc"], b["identity"])
                total, terms = compute_teacher_loss(output, loss_batch, weights)
                total.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), float(cfg["training"].get("grad_clip_norm", 1.0)))
                optimizer.step()
                epoch_losses.append(float(total.detach().item()))
            scheduler.step()
            history.append({"epoch": global_epoch, "stage": stage, "loss": float(np.mean(epoch_losses)), "lr": optimizer.param_groups[0]["lr"]})
            global_epoch += 1

    def evaluate_dataset(ds: RawManifestDataset, seed_offset: int) -> Dict[str, float]:
        model.eval()
        rows = []
        builder = PhysicsConditionedSampleBuilder(synth_base, seed=seed + seed_offset, device=device)
        crop_size = int(cfg["training"].get("evaluation_crop_size", cfg["training"].get("crop_size", 256)))
        with torch.no_grad():
            for i in range(len(ds)):
                s = builder.build(ds[i], regime="moderate" if ds[i].noisy is None else None)
                size = min(crop_size, s.clean.shape[-2], s.clean.shape[-1])
                s = crop_training_sample(s, size, crop_generator)
                o = model(s.conditioning[None], s.global_condition[None])
                rows.append(evaluate_prediction(o, s.conditioning[None], s.noisy[None], s.clean[None], model_cfg.residual_k_sigma))
        return _mean_metrics(rows)

    validation_metrics = evaluate_dataset(val_ds, 1001)
    zero_shot_test_metrics = evaluate_dataset(test_ds, 2001)
    checkpoint = {
        "schema_version": 1,
        "model_config": asdict(model_cfg),
        "model_state": model.state_dict(),
        "optimizer_state": optimizer.state_dict(),
        "scheduler_state": scheduler.state_dict(),
        "history": history,
        "run_provenance_sha256": provenance_hash,
        "validation_metrics": validation_metrics,
        "zero_shot_test_metrics": zero_shot_test_metrics,
    }
    ckpt_path = run_dir / "teacher_last.pt"
    torch.save(checkpoint, ckpt_path)
    checkpoint_hash = sha256_file(ckpt_path)
    summary = {
        "checkpoint": str(ckpt_path),
        "checkpoint_sha256": checkpoint_hash,
        "parameter_count": model.parameter_count(),
        "validation_metrics": validation_metrics,
        "zero_shot_test_metrics": zero_shot_test_metrics,
        "history": history,
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_model_card(
        run_dir / "MODEL_CARD.md",
        model_version=str(cfg["model"].get("name", "spectra_teacher_v1")),
        teacher_version=str(cfg["model"].get("name", "spectra_teacher_v1")),
        architecture=asdict(model_cfg),
        parameter_count=model.parameter_count(),
        training_sensors=split.train_sensors,
        held_out_sensors=split.test_sensors,
        dataset_versions=[f"{manifest.dataset_name}:{manifest.dataset_version}"],
        safety_k=model_cfg.residual_k_sigma,
        posterior_calibration="metrics recorded; release calibration requires full held-out dataset",
        known_failures=["No production/release quality claim from Phase-2 stack alone"],
        status="TRAINING RUN COMPLETE / NOT A PRODUCTION STUDENT",
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the offline SPECTRA Neural Teacher")
    parser.add_argument("--config", required=True, help="Path to training YAML")
    args = parser.parse_args()
    summary = run_training(args.config)
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
