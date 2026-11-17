# BnCam SF2 — phase 7.1 developer UI

Base: `deliverables/sf2-phase7` at `f2e4dc0879c24c67468a4d51f76d5d604cd3f77d`.

No phase-7 native kernel, shader, sensor format, demosaic or production noise model is modified. Only Android Compose debug UI, app navigation and CI/source contracts are added.

## Access

In **Settings → About**, under the existing **Info** row, select **Developer settings**. This row is present in debug builds only. The debug UI is invisible in release builds.

## Controls

- Experimental luminance switch, defaults OFF on fresh app process (session-local).
- Stage selector: 0 OFF chroma, 1 legacy luminance, 2 Guided Fine, 4 Guided Fine+Coarse, 5 Fine+Coarse+Confidence. Mode can be selected then applied for subsequent normal single-frame RAW captures. The switch controls whether the selected non-OFF stage is active.
- **Open camera** returns to live camera preview, retaining the session-only mode.
- **Reset to OFF** deactivates experimental luminance.
- **Capture & compare all six** invokes the phase-7 existing six-render branch against the exact same RAW16 source. Requires a RAW10 or RAW_SENSOR single-frame profile. Runs using the resident camera subscriber without opening a new camera session. Shows six downsampled previews and two selectable side-by-side views. The user can export six JPEG files to Pictures/BnCam/SF2 comparisons via Android MediaStore. Diagnostic TXT files remain private.
- **Run GPU/CPU validation** invokes existing `ImageUtils.validateSf2IntegrationNative()` on a worker thread. A visible PASS requires all six stage report lines and `allPassed=true`. Report saved privately at `files/sf2_qualification/report.txt`.

No automatic device-qualification claim is made: APK compilation and local syntax/source/native tests do not validate numerical parity or photographic improvement on the HONOR Magic 8 Pro.

## Device smoke test

1. Install phase-7.1 debug APK over prior BnCam if signing/package allow; open Settings, scroll to About, check new row below Info.
2. Open Developer settings. Verify active stage initially OFF, enable, select Guided Fine, use Open camera and take a RAW10 single-frame JPEG.
3. Return, set OFF, select Fine+Coarse, select Confidence; ensure stage labels change.
4. Run GPU/CPU validation; report must explicitly PASS before any qualification claim.
5. With RAW10 or RAW_SENSOR single-frame active, run Capture & compare all six on a static scene and inspect all six images. Export to gallery as needed.
6. Restart app and confirm experimental stage resets OFF.
