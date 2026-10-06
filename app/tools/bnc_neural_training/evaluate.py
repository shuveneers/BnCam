"""Held-out Phase-2 comparison. Missing neural results remain null, never fallback scores.

All numerical metrics are camera-linear. Colored scenes use error against truth;
the existing neutral-suite definitions stay unchanged.
"""
from pathlib import Path
import argparse
import json
import numpy as np
from app.tools.raw_truth.quality_metrics import measure
from .contracts import pack, merge, measured_mask, sample
from .opponent import opponent_metrics


def metrics(rgb, truth):
    result = measure(rgb, truth)
    # Phase-2 assumes neutral truth. Correct the truth luma for new colored tests.
    lumaw = np.array([.2126, .7152, .0722])
    y, t = rgb @ lumaw, truth @ lumaw
    gradient = lambda v: np.sqrt((np.mean(np.diff(v, axis=0)**2) + np.mean(np.diff(v, axis=1)**2)) / 2)
    refgrad = gradient(t)
    result.update(luma_rmse=float(np.sqrt(np.mean((y-t)**2))),
                  # FP32 isoluminant truth can retain ~1e-9 numerical luma variation.
                  # A ratio to that roundoff is undefined, not millions of times retained detail.
                  luma_gradient_retention=float(gradient(y)/refgrad) if refgrad > 1e-7 else None,
                  luma_retention=float(y.std()/t.std()) if t.std() > 1e-7 else None,
                  opponent_error_rms=float(np.sqrt(np.mean(np.stack([
                      (rgb[..., 0]-rgb[..., 1])-(truth[..., 0]-truth[..., 1]),
                      (rgb[..., 2]-rgb[..., 1])-(truth[..., 2]-truth[..., 1])], -1)**2))))
    refhf = measure(truth)["high_frequency_energy"]
    result["high_frequency_retention"] = result["high_frequency_energy"]/refhf if refhf > 1e-8 else None
    result["opponent_channels"] = opponent_metrics(rgb, truth)
    return result


def quality_gate(rows):
    failures = []
    required = ("two_pixel_vertical", "two_pixel_horizontal", "frequency_sweep", "diagonal_edge", "fine_repetitive")
    for name in required:
        q = rows.get(name, {}).get("bnc_neural")
        if q is None:
            failures.append(name+": neural result unavailable")
            continue
        if not np.isfinite(q["opponent_rms"]) or q["opponent_rms"] > .005:
            failures.append(name+": false color > 0.005")
        for field in ("luma_retention", "luma_gradient_retention"):
            value = q.get(field)
            if value is None or not np.isfinite(value) or not .98 <= value <= 1.02:
                failures.append(name+": "+field+" outside [0.98,1.02]")
        if q["sampled_sensel_max_error"] != 0:
            failures.append(name+": measured samples changed")
    # Added objective: retain real isoluminant/color edges, never win by neutralizing them.
    for name in ("color_edge", "isoluminant_color_edge"):
        q = rows.get(name, {}).get("bnc_neural")
        if q is None:
            failures.append(name+": chromatic-edge evidence unavailable")
            continue
        for channel in ("RG", "BG"):
            retained = q["opponent_channels"][channel]["chromatic_gradient_retention"]
            if retained is None or not np.isfinite(retained) or not .98 <= retained <= 1.02:
                failures.append(name+": "+channel+" edge gradient outside [0.98,1.02]")
    # Ambiguous CFA Nyquist is descriptive, never claimed as recovered truth.
    return dict(passed=not failures, failures=failures, productReady=False,
                note="Numerical synthetic gate only; natural, camera, GPU and performance gates also required.")


def evaluate(model, phase2, fixture_root=None):
    import torch
    from .model import infer_tiled
    # Schema-2 outputs are signed Green+Opponent values, not eight RGB values.
    from .v2.model import BncNeuralV2, MissingRgbAdapter
    if isinstance(model, BncNeuralV2):
        model = MissingRgbAdapter(model)
    rows = {}
    fixture_root = fixture_root or phase2/"fixtures"
    for fixture in sorted(fixture_root.glob("synthetic-*")):
        name = fixture.name.removeprefix("synthetic-")
        truth = np.fromfile(fixture/"ground-truth.rgb.f32", dtype="f4").reshape(256, 256, 3)
        raw = sample(truth, "BGGR")
        packed, g = pack(raw, "BGGR")
        row = {}
        for label in ("malvar", "amaze"):
            path = phase2/"demosaic"/fixture.name/(label+"-lsc-off.rgb.f32")
            if path.is_file():
                v = np.fromfile(path, "f4").reshape(truth.shape)
                row[label] = metrics(v[8:-8, 8:-8], truth[8:-8, 8:-8])
                row[label]["sampled_sensel_max_error"] = float(np.max(abs(v[measured_mask(g)]-raw.ravel())))
            else:
                row[label] = None
        row["bnc_neural"] = None
        if model is not None:
            inp = torch.from_numpy(packed.transpose(2, 0, 1).copy()).unsqueeze(0)
            prediction = infer_tiled(model, inp).squeeze(0).permute(1, 2, 0).numpy()
            rgb = merge(raw, prediction, g)
            row["bnc_neural"] = metrics(rgb[8:-8, 8:-8], truth[8:-8, 8:-8])
            row["bnc_neural"]["sampled_sensel_max_error"] = float(np.max(abs(rgb[measured_mask(g)]-raw.ravel())))
            if all(row.get(label) for label in ("malvar", "amaze")):
                row["neural_worse_than_both_chroma"] = row["bnc_neural"]["opponent_error_rms"] > max(
                    row[label]["opponent_error_rms"] for label in ("malvar", "amaze"))
        rows[name] = row
    return rows


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--phase2", type=Path, default=Path("build/phase2-raw-quality"))
    p.add_argument("--package", type=Path)
    p.add_argument("--sha")
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    model, identity = None, None
    if args.package:
        from .package import load_reference
        model, identity = load_reference(args.package, args.sha)
        if identity["purpose"] != "research_candidate" or identity["trainingSteps"] <= 0:
            raise ValueError("contract/random models cannot be reported as quality candidates")
    rows = evaluate(model, args.phase2)
    args.out.write_text(json.dumps(dict(model=identity, metrics=rows, gate=quality_gate(rows)), indent=2))


if __name__ == "__main__":
    main()
