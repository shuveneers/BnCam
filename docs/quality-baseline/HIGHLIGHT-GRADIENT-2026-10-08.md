# Purple highlights and highlight gradients — REVERT

Scope: explicit user request to remove purple highlight casts and flat/block-like highlight transitions. Frozen fine-chroma Candidate1 is OLD. Only existing A1–3/D1–3 RAW10 fixtures, same three demosaics and same exact coordinates; no new development captures. Microdetail remains REVERT/frozen (separate MICRODETAIL-2026-10-08.md).

## Reproducible cause

The A highlight ROI (display 1600,900,384,384; sensor 2112,1788,384,384; rotation180) contains 1283–1366 Bayer cells with at least one channel at/near the physical ceiling, and zero cells with all four clipped. Both green planes clip; red still spans roughly 0.44–0.86 normalized signal. Around the highlight, intact G/R ratios remain near2.17–2.21. The frozen default RAW confidence reduces colour only after all-CFA loss, so this partial clipping retains false magenta. Existing native stage exports show the visible luminance valley/rim emerging at Khronos mapping from these invalid ratios. Khronos itself was not modified.

## Attempt1: physical green-pair colour confidence

Smooth near-ceiling green-pair loss, bilinear confidence interpolation only, existing luma-preserving colour safety mix. No RGB blur, WB/CCM or tone change. Nine A frame/mode comparisons removed magenta: 3691–5260 classified pixels ->0. Whole D JPEGs, A detail and checked flower ROIs were exact; JPEG clipping did not increase. However it neutralized the core without recovering the lost brightness gradient. The user correctly rejected the remaining flat/block-like core. **REVERT as a complete highlight solution.** It was installed temporarily during its gate and is being replaced by the frozen accepted baseline.

The isolated same-RAW candidate1 evidence is retained. Earlier mixed native objects still contained the rejected microdetail bypass; those outputs are explicitly excluded in invalid-build-*.

## Attempt2: clipped-channel reconstruction

Single-stage correction in existing RAW-finalize dispatch, before demosaic: reconstruct physically clipped G/B samples from still-intact red intensity and nearest intact CFA colour ratios. Source confidence provenance remains immutable and all-CFA policy restored. Physical R S/O checks anchor SNR; valid donor ranges, R/B agreement, at least4 directions, and smooth ratio-scatter/support gates protect unrelated colour. Maximum search256pixels estimates ratios only; it does not spatially blur RGB or extend the frozen 5×5 estimator. CPU fallback uses an immutable conditional RAW copy. WB/CCM, demosaic algorithms, fine-chroma, luma-denoise, sharpening and LOG→FLLF→KHRONOS remained unchanged.

Eight ideal synthetic cases/all4CFA reduced clipped-channel RMSE .20–.40 ->.005–.008 and preserved pre-clip samples exactly. Actual production RAW-finalize shader versus CPU oracle on Adreno840/all4CFA agreed within4.77e-7. The initial parity test harness had an undersized buffer; it was corrected before the valid parity result. These synthetic results do not qualify visual quality.

Same-RAW native replay completed A1–3/D1–3 ×3modes ×2renders, including continuation after the user-requested pause. It is deterministic. **REVERT:** the actual A JPEGs develop pink speckles/patches where per-cell ratio support varies. Chroma HF in the bright ROI rises18.65–39.18%. Removing part of the purple area is insufficient; local colour continuity is visibly worse. No third implementation attempt.

| Check | Attempt1 | Attempt2 |
|---|---|---|
| Purple cast | Removed in9/9 A cases | Reduced but pink islands remain |
| Smooth, natural highlight gradient | Flat core remains | Patchy reconstruction; fails |
| Bright-ROI chroma HF | −1.60…−0.79% | +18.65…+39.18% |
| D regression | Whole JPEGs exact | Whole JPEGs exact |
| JPEG clipping | No increase | No increase |
| Determinism |18/18 repeated pairs exact |18/18 repeated pairs exact |
| Colour finite/fallback |0nonfinite/no demosaic fallback |0nonfinite/no demosaic fallback |
| Performance |No qualified matched result |No qualification after visual failure |
| Verdict |REVERT for requested complete fix |REVERT |

## Device state and gate

Attempt1 gate passed local56, connected9, native CPU80/hybrid numerical, physical smoke PUBLISHED1 and stress exact accounting (PUBLISHED5/REJECTED95). These smoke captures occurred only after a visible same-RAW purple-removal candidate existed; no additional A/D fixture captures were made. Full gate was NOT GREEN: thermal throttling and subsequent ADB disappearance prevented benchmark qualification. Matched old10-sample timing throttled and candidate cooldown failed; do not quote a performance delta.

Attempt2 was never installed. Its archived binaries ran only native same-RAW replay. Production source has been restored byte-exactly to frozen accepted Candidate1, and native shader/source timestamps were explicitly invalidated for the restored build. Build passed. Final restore replay and device installation are recorded separately; this report does not claim a new full gate PASS. No new capture is needed for a reverted change.

Evidence: work/highlight-gradient/{candidate1/,candidate2/,candidate2-source/,candidate2-source.patch,candidate2-device-test.log,final-restored-source-checks.json,final-restored-build.log,final-restored-replay.log}. Crops are exact native JPEG pixels, with identical OLD/NEW boxes and no gain, resizing or cosmetic correction. The wider highlight box is1450,950,500,350. Host reference previews are diagnostic only.

The requested highlight-gradient improvement was not achieved safely. The accepted fine-chroma cleanup remains intact; neither highlight experiment is retained in production.

Final restoration completed: all12 A1/D1 restore renders (3modes ×2repeats ×2fixtures) match the frozen reference JPEGs byte-for-byte. Restored debug APK installation on Honor succeeded. Source hashes all match the saved accepted baseline; git diff --check passed. Restore evidence: work/highlight-gradient/{final-restored-identity.json,final-restored-install.log,installed-apk.json}. No highlight experiment remains in production or on the device.
