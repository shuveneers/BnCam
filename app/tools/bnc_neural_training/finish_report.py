"""Reproduce honest candidate scorecards and the local Phase-3 release report."""
from pathlib import Path
import argparse
import json
import numpy as np
import torch
from PIL import Image, ImageDraw
from .contracts import sample, pack, merge
from .evaluate import evaluate, quality_gate
from .model import infer_tiled
from .package import load_reference


def number(value):
    return "not measured" if value is None else f"{value:.6g}"


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--root", type=Path, default=Path("build/bnc-neural"))
    args = p.parse_args()
    r = args.root
    torch.set_num_threads(2)
    scores, packages, learned_parity = {}, {}, {}
    selected_name = "opponent-v2-full"  # prescribed objective arm, not selected using held-out test
    for path in sorted((r/"training").glob("*/candidate.bncmodel")):
        name = path.parent.name
        sha = path.with_suffix(".bncmodel.sha256").read_text().strip()
        model, manifest = load_reference(path, sha)
        rows = evaluate(model, r/"synthetic")
        scores[name] = dict(packageSHA256=sha, model=manifest, metrics=rows, gate=quality_gate(rows))
        (r/"metrics"/(name+"-extended.json")).write_text(json.dumps(scores[name], indent=2))
        packages[name] = dict(path=str(path), packageSHA256=sha, bytes=path.stat().st_size,
                              fp16WeightBytes=manifest["fp16WeightBytes"], parameterCount=manifest["parameterCount"],
                              trainingSteps=manifest["trainingSteps"], gate=scores[name]["gate"])
        torch.manual_seed(429)
        inp = torch.rand(1, 4, 63, 71)
        with torch.inference_mode():
            full = model(inp)
            tiled = infer_tiled(model, inp, 23)
            error = float((full-tiled).abs().max())
            assert error <= 2e-6, (name, error)
            half = model.half()
            fp16 = half(inp.half()).float()
            half_tiled = infer_tiled(half, inp.half(), 23).float()
            learned_parity[name] = dict(fp32TiledMaxAbsNormalized=error,
                fp32TiledMaxAbsSceneLinear=4*error,
                cpuHalfVsFloatMaxAbsNormalized=float((fp16-full).abs().max()),
                cpuHalfTiledMaxAbsNormalized=float((fp16-half_tiled).abs().max()),
                gpuVsReferenceMaxAbs=None,
                note="CPU FP16 experiment only, not the Vulkan primitive's arithmetic parity")
        if name == selected_name:
            model = model.float()
            for fixture in sorted((r/"synthetic/fixtures").glob("synthetic-*")):
                truth = np.fromfile(fixture/"ground-truth.rgb.f32", "f4").reshape(256, 256, 3)
                packed, g = pack(sample(truth, "BGGR"), "BGGR")
                pred = infer_tiled(model, torch.from_numpy(packed.transpose(2,0,1).copy()).unsqueeze(0)).squeeze(0).permute(1,2,0).numpy()
                rgb = merge(sample(truth, "BGGR"), pred, g)
                panels = [("Known truth", truth)]
                for alg in ("malvar", "amaze"):
                    panels.append((alg, np.fromfile(r/"synthetic/demosaic"/fixture.name/(alg+"-lsc-off.rgb.f32"),"f4").reshape(truth.shape)))
                panels.append(("BnC reference: rejected",rgb))
                canvas = Image.new("RGB",(1024,286),"#181818")
                draw = ImageDraw.Draw(canvas)
                for i,(label,v) in enumerate(panels):
                    # Fixed scene-linear display; no auto-gain, no sharpen, no resampling.
                    canvas.paste(Image.fromarray(np.rint(np.clip(v,0,1)*255).astype("u1")),(256*i,30))
                    draw.text((256*i+4,6),label,fill="white")
                canvas.save(r/"crops"/(fixture.name+".png"))
    (r/"model/research-packages.json").write_text(json.dumps(packages, indent=2))
    (r/"metrics/learned-tiling-and-half.json").write_text(json.dumps(learned_parity, indent=2))
    selected = scores[selected_name]
    corpus = json.loads((r/"training/corpus/corpus.json").read_text())
    architecture = json.loads((r/"architecture/candidates.json").read_text())
    doc = ["# BnC Neural Phase 3 — release gate FAILED", "",
        "Independent Bayer-to-missing-RGB research candidates were trained locally. None passes the standalone quality gate. "
        "No production Vulkan backend was implemented or activated: this obeys the explicit pre-backend quality gate. "
        "Product ID 3 remains requested=BNC_NEURAL, actual=MALVAR_2004, fallback=true, reason=BNC_NEURAL_BACKEND_UNAVAILABLE. "
        "bncNeuralAvailable=false. Auto Hybrid remains two classical routes, with neural prior zero.", "",
        "All 77 pre-existing modified/untracked files remain byte-identical to the initial snapshot. "
        "No reset, checkout, commit, push, FLLF change, LSC change or downstream change was made.", "",
        "## 1–2. Actual data and suitability", "",
        "LUCID CC0 v2 HC512 shard 00000, revision `"+corpus["revision"]+"`. "
        "Verified archive SHA256 `"+corpus["archiveSHA256"]+"`. The publisher identifies CC0/PxHere as its source. "
        "The import preserves per-image hashes, source-photo links and the declared transfer function. "
        "No private phone photo, classical demosaic output, JDD weight or BnCam JPEG served as training truth.", "",
        "Source-group split: "+json.dumps(corpus["counts"])+". All crops of each source photograph stay together; "
        "exact file duplicates are excluded. Phase-2 and new procedural fixtures are evaluation-only. "
        "The 295 test tiles come from 12 source photographs; they are correlated tiles, not 295 independent scenes.", "",
        "Suitability: known full-RGB high-detail crops can be linearized and independently mosaiced. "
        "Limitations: inverse sRGB of rendered images approximates a linear signal, not calibrated sensor radiance; "
        "source processing artifacts remain, one shard has limited coverage, and different source IDs can still contain similar scenes. "
        "This is an exploratory corpus, not evidence of release-level training coverage.", "",
        "Sources: [dataset card](https://huggingface.co/datasets/Phips/lucid-cc0-v2-hc-512), "
        "[source corpus](https://huggingface.co/datasets/nyuuzyou/pxhere), "
        "[publisher source-ID naming](https://github.com/Phhofm/lucid-sisr/blob/main/lucid/core/finalize.py). "
        "Cards and importer provenance are saved under training/. DIV2K was investigated but its academic-research-only terms "
        "were not adopted for product training.", "",
        "## 3–7. Architecture, size, contract, receptive field, losses", "",
        "See ../../BNC_NEURAL_ARCHITECTURE.md for exact equations. Conv3(4,W), B local gated residual blocks "
        "(Conv3 W→2W, elementwise gate, Conv1 W→W, add), Conv3 W→8. No classical RGB input/skip. "
        "Half-resolution canonical Bayer [R,Gr,Gb,B] /4 in; eight missing components /4 out; original FP32 sensels copied at merge. "
        "All four CFA phases, origins, crop parity and odd sizes are tested. No ISO/noise/sigma input.", "",
        "| W | Blocks | Parameters | FP16 weights (bytes) | RF packed/raw | Planned tile scratch bytes |",
        "|---:|---:|---:|---:|---:|---:|"]
    for a in architecture:
        doc.append(f"| {a['width']} | {a['blocks']} | {a['parameterCount']} | {a['fp16WeightBytes']} | {a['receptiveFieldPacked']}/{a['receptiveFieldRaw']} | {a['plan12mp']['plannedFp16ScratchBytes']} |")
    doc += ["", "These six shapes have numerical reference tests. Three shapes were actually trained in the first comparison "
            "(8/4, 12/6, 16/6; 2,500 steps each), plus a missing-only ablation. "
            "The requested opponent extension was then trained in two matched 12/6 runs of 5,000 steps each. "
            "The prescribed opponent-v2-full arm is reported below; it was not selected by held-out test scores. "
            "There is no release Pareto winner because every tested trained candidate fails quality.", "",
            "Opponent-v2 weights: missing Charbonnier=1, luma-gradient=.2, RGB-Laplacian=.1, opponent-L1=.2, "
            "opponent-gradient=.1, alternating opponent sign-score error=.01, conditional zero-chroma energy=.1. "
            "The no_opponent arm retains the same non-opponent terms. Both use identical initialization, data sequence, "
            "25% known-neutral same-signal crops, and cosine LR .001→.0001. Camera linear transforms and range rejection "
            "are documented in architecture. Best checkpoint is selected by fixed validation missing-channel L1 only.", "",
            "## 8. Sample preservation and numerical infrastructure", "",
            "26 host tests pass (including geometry, package corruption, leakage guards, losses, color-edge protection, confidence and tiling). "
            "80 NDK arm64 test cases ran on the HONOR BKQ-N49: Python/native pack and FP32 merge maximum error both zero. "
            "FP16 predictions still copy the original FP32 measured channels exactly. Signed zero, subnormals and values >1 are covered. "
            "This proves the merge boundary; it is NOT a Vulkan inference claim.", "",
            "## 9–10. Held-out synthetic results", "",
            "No threshold was relaxed: false-color RMS target .005; non-ambiguous luma/detail ratios [0.98,1.02]. "
            "All per-case metrics, separate RG/BG errors, alternating sign diagnostics, and worse-than-both flags are retained "
            "in metrics/*-extended.json. No aggregate replaces a bad testcase.", "",
            "| Fixture | Malvar opponent RMS | AMaZE opponent RMS | BnC opponent RMS | BnC luma-gradient ratio | Worse chroma than both |",
            "|---|---:|---:|---:|---:|---|"]
    for name,row in selected["metrics"].items():
        q=row["bnc_neural"]
        doc.append(f"| {name} | {number(row['malvar']['opponent_rms'])} | {number(row['amaze']['opponent_rms'])} | {number(q['opponent_rms'])} | {number(q['luma_gradient_retention'])} | {row.get('neural_worse_than_both_chroma')} |")
    doc += ["", "Opponent magnitude on colored edges is real scene color; use the truth-relative opponent error and edge "
            "retention below, rather than interpreting color itself as an artifact. Exact one-pixel/checkerboard Nyquist "
            "is information-ambiguous and is never described as recovered missing detail.", "",
            "### Explicit opponent extension / matched ablation", "",
            "| Fixture | Full RG/BG error RMS | No-opponent RG/BG error RMS | Full RG/BG gradient retention |",
            "|---|---:|---:|---:|"]
    for name in ("two_pixel_vertical","two_pixel_horizontal","diagonal_edge","frequency_sweep","fine_repetitive","repetitive_period_7","color_edge","isoluminant_color_edge"):
        q=selected["metrics"][name]["bnc_neural"]["opponent_channels"]
        b=scores["opponent-v2-no_opponent"]["metrics"][name]["bnc_neural"]["opponent_channels"]
        doc.append(f"| {name} | {number(q['RG']['reconstruction_rms'])}/{number(q['BG']['reconstruction_rms'])} | {number(b['RG']['reconstruction_rms'])}/{number(b['BG']['reconstruction_rms'])} | {number(q['RG']['chromatic_gradient_retention'])}/{number(q['BG']['chromatic_gradient_retention'])} |")
    doc += ["", "The objective is supervision/confidence evidence only; no opponent blur or post-demosaic neutralization is executed. "
            "The isoluminant and chromatic retention checks can reject a desaturated result independently of the neutral-pattern scores. "
            "Future regional Auto Hybrid RG/BG consistency is a documented experiment proposal only, not a blender modification.", "",
            "Measured ablation conclusion: full opponent supervision reduces vertical 2px false-color RMS from 0.05332 to "
            "0.04597 and horizontal from 0.04419 to 0.03893. Both remain far above .005. Frequency-sweep RMS changes "
            "0.10768→0.10568; diagonal changes 0.01622→0.01638 and period-8 changes 0.007154→0.007192 (slightly WORSE). "
            "The full arm retains only 50.2%/64.3% of the isoluminant RG/BG gradient. The losses encode the intended "
            "constraint, but these trained weights do not satisfy it. No color-preservation success is claimed.", "",
            "An offline 32px-region study actually ranks the three independent reconstructions by disagreement with median "
            "RG/BG gradients. It never modifies product weights or publishes a blended image. It demonstrates a counterexample: "
            "frequency-sweep selected opponent RMS is 0.06714 while the best per-region oracle is approximately 2.85e-8. "
            "For period-8 it selects RMS 0.01357 despite an available 0.007192 oracle. Cross-route consistency is not calibrated "
            "correctness and can prefer jointly incorrect classical predictions. Full region scores/regrets are in "
            "metrics/regional-opponent-confidence-study.json. A third-route confidence change is not justified.", "",
            "### Failures for the prescribed full opponent arm", ""]
    doc += ["- "+v for v in selected["gate"]["failures"]]
    doc += ["", "### All actually trained candidates", "",
            "| Run | Best checkpoint step | Parameters | FP16 weight bytes | Package bytes | Standalone gate |",
            "|---|---:|---:|---:|---:|---|"]
    for name, pkg in packages.items():
        doc.append(f"| {name} | {pkg['trainingSteps']} | {pkg['parameterCount']} | {pkg['fp16WeightBytes']} | {pkg['bytes']} | {'PASS' if pkg['gate']['passed'] else 'FAIL'} |")
    doc += ["", "## 11. Existing main / ultrawide / tele RAW evidence", "",
            "The prescribed full-arm FP32 reference is evaluated on the same finalized Bayer crops (with receptive-field halo) "
            "as the classical routes. real-raw/ contains per-crop post-demosaic and shared metadata WB/CCM proxy metrics, "
            "FP32 RGB crops, Bayer input hashes and 100% PNG comparisons. Sensor IDs and capture IDs are retained. "
            "Natural held-out center-crop metrics are also recorded, without treating correlated tiles as independent photographs. "
            "Foliage chroma/texture residuals are descriptive evidence, not labels of noise.", "",
            "Evaluated: main sensor 2 / IMG_BNC_20260927_143700_632; ultra sensor 4 / IMG_BNC_20260927_143709_928; "
            "tele sensor 5 / IMG_BNC_20260927_143007_948. There are 30 real regions × 3 routes = 90 proxy records. "
            "The 295 natural held-out RGB tiles have median RGB RMSE 0.01724 (fixed central crop, no camera augmentation). "
            "This single reference result does not establish a natural-image win over classical demosaics.", "",
            "Post-FLLF and final production captures: NOT RUN for neural. The failed standalone gate prevents a dedicated "
            "production backend and device neural captures. Existing Phase-2 stages remain available as baseline evidence; "
            "they are not relabeled as BnC Neural. RAW10 neural comparison is also NOT RUN. No claim of absolute reconstruction "
            "accuracy is made for real RAWs without RGB truth.", "",
            "## 12–15. GPU/reference, tiling, Adreno performance and APK size", "",
            "GPU-vs-reference: NOT RUN (no qualified model/backend). Adreno model load/warmup/inference/peak memory/total "
            "demosaic latency: NOT MEASURED. Native phone tests exercised CPU contracts; classical synthetic baselines "
            "used the existing actual Vulkan implementation. No GPU neural throughput is inferred from CPU timings.", "",
            "| Run | FP32 tiled max error, scene-linear | CPU half vs float max error, normalized | CPU half tile max error |",
            "|---|---:|---:|---:|"]
    for name,v in learned_parity.items():
        doc.append(f"| {name} | {number(v['fp32TiledMaxAbsSceneLinear'])} | {number(v['cpuHalfVsFloatMaxAbsNormalized'])} | {number(v['cpuHalfTiledMaxAbsNormalized'])} |")
    doc += ["", "CPU FP16 is an experiment, not a match to Vulkan's FP16-storage/FP32-accumulation shader. "
            "Reference plans use 192 tiles for 4000×3000 raw / 2000×1500 packed at 128-cell cores. "
            "Listed scratch memory is an allocation estimate, not measured GPU memory. Reusable original/input/output "
            "resident allocations, weight storage, descriptors and driver overhead are separate.", "",
            "APK impact: zero new packaged model or shader bytes; added C++ headers are not linked into production and "
            "training tools are outside app assets. assembleDebug and focused DemosaicMode, LensShadingGrid, CaptureSettingsSchema "
            "JVM tests passed. Existing native RAW-quality routing/gain regression passed on the device. No new APK was installed "
            "because no product route changed or qualified for the requested capture validation.", "",
            "## 16–19. Release decision and remaining failure modes", "",
            "**Product-ready: NO. bncNeuralAvailable=true justified: NO. Auto Hybrid: TWO routes.** "
            "The candidate reconstructs from Bayer independently, but its false-color/detail behavior fails the requested "
            "engineering targets. It cannot be used as a neural product option or a third Auto Hybrid route.", "",
            "Known limitations: limited corpus diversity and rendered-sRGB priors; short exploratory training budgets; "
            "failure on fine/repetitive structure; chromatic/isoluminant edge attenuation; CFA-Nyquist ambiguity; unvalidated "
            "FP16 activation propagation and camera generalization; no Vulkan neural execution, RAW10 neural test, "
            "FLLF/final comparison or mobile memory/latency evidence. More data/training may help, but these runs do not "
            "establish that the architecture can meet the gate. Do not substitute smoother texture for correct detail.", "",
            "### Evidence locations", "",
            "- provenance/: pre-change patch, all 77 files and hashes, preservation check.",
            "- training/: licensed source card, archive/corpus, opponent-v2 source snapshots, losses, optimizer history and candidates; earlier baseline source is under provenance/.",
            "- architecture/: six shape/RF/memory plans; root BNC_NEURAL_ARCHITECTURE.md is permanent.",
            "- model/: full package SHA identities; contract-test weights explicitly NOT TRAINED.",
            "- metrics/: per-candidate and per-fixture scores, no hidden averages; host tests and numeric parity.",
            "- synthetic/: held-out truth and unchanged classical GPU outputs, including isoluminant edge.",
            "- real-raw/ and crops/: reference-only 100% comparisons and proxy evidence.",
            "- device/: build/JVM logs, native CPU oracle parity and preserved Phase-2 regression.",
            "- benchmarks/: CPU reference timings and clearly labeled GPU estimates/unmeasured fields.", ""]
    (r/"report.md").write_text("\n".join(doc), encoding="utf-8")
    print(json.dumps({name: dict(passed=v["gate"]["passed"],failures=len(v["gate"]["failures"])) for name,v in scores.items()}, indent=2))


if __name__ == "__main__":
    main()
