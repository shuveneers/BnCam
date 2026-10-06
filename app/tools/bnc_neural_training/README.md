# BnC Neural research tooling

See `BNC_NEURAL_ARCHITECTURE.md` at repository root. The application is deliberately
not activated by any command here. Use an isolated Python environment with
`requirements.txt`; all commands run from the repository root.

```powershell
python -m unittest discover -s app/tools/bnc_neural_training/tests -t . -v
python -m app.tools.bnc_neural_training.validate --device
python -m app.tools.bnc_neural_training.prepare_lucid downloaded-shard-00000.tar build/bnc-neural/training/corpus
python -m app.tools.bnc_neural_training.train --manifest build/bnc-neural/training/corpus/corpus.json --out build/bnc-neural/training/new-run --width 12 --blocks 6 --steps 5000 --patch 64 --neutral-probability .25 --decay-lr
python -m app.tools.bnc_neural_training.synthetic --device
python -m app.tools.bnc_neural_training.evaluate --phase2 build/bnc-neural/synthetic --package candidate.bncmodel --sha FULL_FILE_SHA256 --out metrics.json
```

The importer only accepts the pinned archive identified in its source. The
trainer also accepts a reviewed `bnc-rgb-corpus-v1` manifest: each record must
contain path, SHA256, source, license, source_group, split, encoding and
ground_truth=known_full_rgb. Splits are train/validation/test; encoding is srgb
(RGB image) or linear_rgb (FP32 .npy). Keep all related crops in one source group.
Counts are a minimum resource guard, not an automatic quality approval.

`--ablation no_opponent` retains RGB/missing, luma-gradient and detail losses while
removing all opponent terms. Compare using identical arguments and seed. Never
use the held-out report to optimize exact acceptance fixtures.

For the local recorded run, Python 3.12 is at
`C:/Users/shuve/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`;
set `PYTHONPATH` to the absolute `build/bnc-neural/python` directory. Torch 2.8 CPU
and SciPy were installed there, leaving the system Python untouched. CPU numerical
tests on the attached phone use NDK 28.2.13676358. They are not GPU inference tests.

Research packages and downloaded data live only under ignored `build/bnc-neural`.
No training tooling, corpus or experimental weights are packaged into the APK.
