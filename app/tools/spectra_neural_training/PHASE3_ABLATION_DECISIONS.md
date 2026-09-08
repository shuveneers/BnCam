# SPECTRA Neural — Phase 3 architecture ablation decisions

## Baseline selected for the first production Student

`normalization-free NAF-like convolutional Student + FiLM physics conditioning + one bounded residual head + posterior head`

Reasons:
- operator set maps directly to the planned Vulkan primitive set;
- HQ/Lite parameter classes fit the masterprompt targets;
- residual semantics are identical to the Teacher/Phase-1 ABI;
- no device/sensor identity shortcut is introduced;
- no full-resolution attention, LayerNorm-heavy graph or transposed convolution.

## Low-resolution global context

**Status: implemented as an explicit offline research branch, not selected in v1 baseline.**

It requires a separate whole-frame spatial conditioning input. Baseline export rejects this branch rather than silently pretending a per-tile input is global context. Selection requires governed held-out evidence for structured/banding/low-frequency improvement and Phase-4 runtime/memory evidence.

## Fine/coarse residual heads

**Status: implemented as an offline ablation, not selected in v1 baseline.**

Both heads sum *before* the single K-sigma physical bound, so enabling the ablation never creates a second denoise owner. Selection requires real data evidence that low-frequency cleanup improves without texture loss or instability.

## Mamba / SSM bottleneck

**Status: deliberately not implemented or selected for v1.**

The masterprompt marks Mamba/SSM as optional research. No governed held-out quality/runtime evidence currently justifies adding custom recurrent/state-space operators to the mobile backend contract. A fake placeholder would violate the no-half-work rule. This candidate can be revisited later as an offline bottleneck ablation if evidence warrants it.

## Quantization

**FP16 selected. INT8 not selected.**

RAW residual magnitude and channel-bias sensitivity make quantization error high-risk. INT8 remains conditional on later QAT + held-out gradient/texture/sky/bias parity evidence.
