# Final bounded pre-Neural-Bn RAW10 baseline

Scope: camera **2**, **RAW10**, scenes **A/B/D**, **three valid distinct physical inputs per scene**. No other scene, lens, RAW_SENSOR series, ISP settings change, benchmark or dashboard is part of this runner.

## Run

One-time prerequisite: leave the existing measurement debug APK installed, BnCam foreground/unlocked, camera 2 / main 1×, active RAW10 profile and Single Frame Photo selected. The runner reuses this profile; it does not select Disabled or rewrite image/source settings. It checks actual camera/source/recipe after capture and stops on a mismatch. The installed APK must match the local debug APK, as in the existing collector. No new app build is required for this host-only extension.

Position and stabilize the phone, then run from the repository in PowerShell:

```powershell
# A: good constant light, fine printed text/fabric, white/gray, colors and a dark area.
.\scripts\runRaw10Baseline.ps1 -Scene A

# B: same setup, clearly reduced light, not completely dark.
.\scripts\runRaw10Baseline.ps1 -Scene B

# D: good constant light, fine printed text and woven fabric/repeating or diagonal detail.
.\scripts\runRaw10Baseline.ps1 -Scene D
```

Keep the scene and settings stable until the command ends. Center the relevant detail so the existing positional crop is useful. Each command finishes by itself; no intermediate confirmations or ROI drawing are required. Outputs: `work/quality/pre-neural-bn-raw10/A`, `B`, `D`. Each contains `scorecard.md` and `scorecard.json`, canonical RAW/checksums, six replay JPEGs/native stats per input, crops/differences and publication checks. Large files stay in ignored `work/`.

The wrapper finds the bundled numpy/Pillow Python on this computer; `-Python <path>` can override it. The default serial is AUWE025B03006422. Existing nonempty output is refused to prevent accidentally exceeding the three-frame baseline. Use `-Output <new-empty-directory>` only for a deliberate new run; partial evidence is never silently overwritten or resumed.

## Readiness and validity

The existing collector waits for camera settling. **The app's shutter admission gate is the authoritative readiness probe**: if it refuses an attempt for a recognized transient readiness state, the host preserves its terminal record, waits five seconds, and tries again. Maximum five retries per valid-frame slot (six attempts); no indefinite loop, forced app restart, profile rewrite or hidden fallback. Camera/format mismatches, lost/ambiguous terminal accounting, render failures, checksums and publication mismatches are fatal rather than retried. A missing pipeline can recover unattended; an incorrectly configured or locked phone still needs correction after a bounded failure.

Only a frame passing all checks counts toward three: actual camera 2/RAW10; constant camera/profile identity within the scene; distinct canonical RAW SHA; exactly Malvar/AMaZE/Hybrid, two saved renders each; requested native algorithm and no fallback in **both** renders; independently verified duplicate JPEG checksums; actual published JPEG checksum and exact decoded-pixel equality with the selected replay; checksum-verified canonical RAW. Failed admission attempts do not count as valid frames. Device errors never become fabricated quality conclusions.

## Decision evidence and conclusions

Existing metrics only: same-RAW disagreement mean/p95/p99/max; region gradient as a detail proxy; existing declared-edge 10–90 profile/overshoot/undershoot; high-frequency R−G/B−G spread as a chroma proxy; spatial luma/chroma spread as a limited noise proxy. The final JPEG is encoded sRGB; gradient can include noise/sharpening and spatial spread can include texture. These cannot establish true detail retention, zipper artifacts, false colour or causal noise amplification on arbitrary mixed content.

Without optional manually declared regions, the existing center crop is explicitly position-only. `-Rois <existing-json>` can reuse suitable predeclared neutral-texture, flat or edge regions; coordinates must belong to this actual setup. Old scene coordinates are never silently reused. Invalid/ambiguous edges remain unmeasurable. This option is not required for the three runs and does not trigger further capture work.

The conclusion vocabulary is `MALVAR_BETTER`, `AMAZE_BETTER`, `HYBRID_BETTER`, `HYBRID_WEIGHTING_NEEDS_WORK`, `NO_MEANINGFUL_DIFFERENCE`, `INCONCLUSIVE`. The automated policy emits **NO_MEANINGFUL_DIFFERENCE only for identical decoded pixels across all modes on all three inputs**; otherwise it emits **INCONCLUSIVE** with the measured disagreement and proxy evidence. It deliberately does not invent thresholds or infer a winner/Hybrid defect from sharpness, less chroma or closeness to a candidate alone. The other labels require independent reference/artifact evidence; no such evidence is manufactured by this runner.

Three valid inputs plus their scorecard completes each scene, including an INCONCLUSIVE scorecard. There is no automated request for more scenes, ROI analysis, a winner, or further captures. Once A/B/D complete, this baseline is finished.

## Validation

Six host regression tests cover transient/bounded retries, fatal mismatches, both saved replay checksums/native fallback, publication RAW/pixel mismatches and conservative conclusions; eight existing analyzer tests also pass. Saved real RAW10-E data independently passed the strengthened six-render checks. This validates tooling, not completion of the new physical A/B/D baseline; those commands have not been triggered without the new physical setup.

Replay captures deliberately perform seven total renders (six diagnostic plus the ordinary published render). Their displayed processing time includes diagnostic overhead and is not ordinary shutter latency. The replay request is cleared on exit, including failure. No ISP/shader/native app change was required.

## Completed device baseline, 2026-10-08

A, B and D have now each completed exactly three valid physical frames on camera 2 / RAW10. All nine canonical RAW inputs are distinct. All 54 diagnostic renders passed duplicate bitexact/native-algorithm checks; all nine actual publications passed checksum and selected-replay pixel equality. Nine attempts published, zero retries. Each scene's conclusion is INCONCLUSIVE: measured rendition differences without independent ground truth establishing a winner. This does not invalidate the technically verified captures.

Final per-scene scorecards and evidence are under `work/quality/pre-neural-bn-raw10/{A,B,D}/scorecard.md` and `scorecard.json`. No RAW_SENSOR coverage or quality tuning was performed for this baseline. Collection and analysis have stopped as requested; no further scenes or quality work are implied.
