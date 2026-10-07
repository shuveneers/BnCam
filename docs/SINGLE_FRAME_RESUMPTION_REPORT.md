# BnCam — single-frame hervattingsronde, 6 oktober 2026

## 1. Uitgevoerd

Direct gewijzigd in de bestaande repository. Beginstatus was schoon, commit
`30a0f68f399662d07bde9479398a3b632b2d28e7`. Geen ZIP, voorbeeldproject, commit of push.
Deze ronde levert geïntegreerde fixes, fixtures en tests, maar voltooit niet de
hele roadmap. Vooral fysieke cameravalidatie en volledige export van alle
replay-authority ontbreken nog.

| Roadmapfase | Status | Resultaat en beperking |
| --- | --- | --- |
| 1 Capturecontract/observability | DONE | Bestaande keten getraceerd; één canonieke single-frame ID door recipe, one-shot tag, provenance, runner, native heartbeat, diagnostics en performance/save-report. Bestaande snapshot uitgebreid en beschermd. |
| 2 RAW geometry/metadata | PARTIAL | Bestaande RawDomain/CFA/level-authority behouden; expliciete afwijzing van ongeldige CFA in Malvar/AMaZE en ongealigneerde RAW16-rows; replaytests voor CFA/pariteit/normalisatie toegevoegd. Device-inputs niet opnieuw fysiek gevalideerd. |
| 3 Frame/result-pairing | NOT NEEDED | Geen tweede pairing-systeem: timestamp, generation, control epoch, tagged request en sensor authority bestaan al. Bestaande behavioral pairingtests slagen. Capture-ID toegevoegd aan provenance. |
| 4 Deterministische replay | PARTIAL | Bestaande production scene replay als CMake-target geïntegreerd en loader gehard; kleine fixtures/exporter. Niet iedere live observerhint/profileparameter is geserialiseerd; geen nieuwe device-replay uitgevoerd. |
| 5 Malvar | PARTIAL | Bestaande MHC-kernels/oracles behouden; output-ownership en unsupported-CFA gecorrigeerd; extra native golden/determinism/border/parity-dekking compileert. Native uitvoering vereist device. |
| 6 AMaZE | PARTIAL | Zelfde productie-normalisatie; RGB-ownership gecorrigeerd, green/guide-scratch behouden onder bestaande mutex. Extra native dekking compileert; geen GPU-kwaliteitstuning of nieuwe device-uitvoering. |
| 7 Auto Hybrid | PARTIAL | Regionale GPU-blend behouden; bestaande priors/reasons/metrics/timings behouden. Deterministische policychecks op dezelfde RAW-input toegevoegd. GPU-blend nog niet opnieuw uitgevoerd. |
| 8 AF/focus | PARTIAL | Bestaande focusownership, tap/manual/continuous-preservation en bounded waits behouden. Shutter-AF-state en requested/effective focus in manifest; vijf API-guardfouten in focus/zoomdiagnostiek hersteld. Device-stress ontbreekt. |
| 9 AE | PARTIAL | Shutter-AE-state bevroren; producer-request versus werkelijk sensor-result zichtbaar. Bestaande exposure-allocator/realization-policytests slagen. Geen nieuwe fysieke convergence/flashmetingen. |
| 10 FPS/still | PARTIAL | Dedicated still krijgt geen opnieuw toegepaste preview-FPS-policy. Warm RAW/ZSL gebruikt bewust repeating-exposure. Low-lightgedrag moet nog op fysieke camera worden gemeten. |
| 11 Settings | PARTIAL | Bestaande CaptureSettingsSchema, Libpatcher-catalog/resolver en captured runtimewaarden behouden; gegenereerde index van 219 persisted key-declaraties. Geen device DataStore export of volledige UI/default/range-equivalentie bewezen. |
| 12 Debugmanifest | PARTIAL | Bestaand terminal JSONL-report uitgebreid tot schema 3 met canonieke ID, recipe, build, control/source/outputmetrics en timings. Vroege router/admissionfouten hebben nog niet altijd dit volledige manifest. |
| 13 Timings | PARTIAL | Bestaande native stage- en overlap-aware performancegegevens behouden; render/save/processingduur nu monotonic; dispatchlatency gebruikt echte elapsed-realtime shuttersource. Fused GPU-stages blijven als zodanig gerapporteerd. |
| 14 Threading/lifecycle | PARTIAL | Bestaande generation/lease/queue/lifecyclearchitectuur behouden; klassieke CPU-outputaliasing gerepareerd en concurrentiechecks toegevoegd. Geen rotate/pause/lens-switch/device-stress uitgevoerd. |
| 15 Tests | PARTIAL | Zes nieuwe JVM-tests slagen; Python fixtures 2/2; native 80 cases en concurrentietests compileerden. Volledige JVM-suite blijft 355 bestaande failures houden. |
| 16 Build/config | DONE | Debug-APK, instrumentation-APK en replaytarget bouwen. Git/build/version/featureinformatie in report. Bestaande SDK/NDK/CMake-versies behouden. Lintresultaat en commands hieronder. |

## 2. Werkelijke keten en behouden architectuur

`CameraScreen` geeft shuttertijd, lens/profile en displayrotation aan
`BnCameraManager.executeCapture`. De manager gebruikt de bestaande
`CaptureAttemptCoordinator`, resolveert de route en maakt `CaptureRecipe` via
`CaptureRecipeFactory`.

RAW SINGLE is in de actuele code primair een generatiegebonden RAW-producer/ZSL-pad.
Een gepinde pre-shutter frame/result-pair gaat rechtstreeks naar de runner;
ontbreekt die, dan wordt de bestaande producer-delivery/reservation gebruikt.
De eerdere beschrijving in `RAW_STABILITY_PHASE_1.md` van een universele cold
direct-still fallback is dus niet de volledige actuele waarheid. Het ontwerp
is niet teruggezet op basis van die oudere documentatie.

Dedicated flash gebruikt een nieuwe Camera2 still-template en zijn eigen
CaptureResult. Canonieke ImageReaderframes en results komen samen in
`FrameRingBuffer`: exacte timestamps, pipelinegeneration, controlrequest epoch
en embedded requestprovenance. Physical sensor authority wordt afzonderlijk
gevalideerd. Images blijven ring-owned; leases beschermen verwerking, en
deferred processing/save krijgt het bestaande ownershipcontract.

`SingleFrameRunner` kiest één exact gebonden frame; RAW-materialisatie loopt
via `SingleRaw16FrameBuilder` en `NativeRaw16Buffer`. DNG gebruikt de canonical
RAW16-publicatiebuffer. JPEG gebruikt `ImageUtils` en de bestaande native
entrypoint, RawDomain/resident normalization, IspCore, demosaic, kleur/tone,
encoding en de bestaande bounded processing/save queues naar MediaStore.
RAW-only blijft zonder JPEG-ISP; geen multi-frame masterbuilder toegevoegd.

De bestaande fysieke noise-authority, Spectra, WB/CCM, kleurtransforms,
tonemapping, denoise en YUV-algoritmen zijn behouden. Neural Bn/model/training
zijn niet inhoudelijk gewijzigd. De gedeelde RAW-JNI kreeg uitsluitend een
capture-ID-argument met compatibele defaults voor overige callers.

## 3. Belangrijkste gevonden bugs

| Bestand / functie | Oorzaak en mogelijk gedrag | Fix / validatie |
| --- | --- | --- |
| CaptureRecipe, SingleFrameRunner, CapturePerformanceTracker, ShotLogger, requestprovenance | Routerattempt, shotdirectory, trace en performancecounter gebruikten verschillende identiteiten; logging-off leverde `Unknown_Shot_ID`. Moeilijk of verkeerd correleren van captures. | `CaptureIds.forAttempt` met manager-UUID en bestaande attemptcounter; recipe-ID doorgeven. Nieuwe identity/provenance-tests slagen. |
| CaptureRecipe.frozenCopy / ResolvedIspSettings | `toList()` kopieerde maar maakte de exposed lijsten niet werkelijk immutable; ook derived renderlijsten waren mutabel. Late mutatie kon captureconfig veranderen. | Unmodifiable defensive copies en derived-list wrappers. Twee nieuwe mutatietests plus bestaande defensive-copytest slagen. |
| Demosaic.cpp, Malvar/AMaZE CPU entrypoints | Teruggegeven `cv::Mat` aliasde process-global RGB-scratch; volgende call met dezelfde geometry overschreef eerdere pixels nadat de mutex los was. | Caller-owned RGB rechtstreeks alloceren, zonder extra pixelcopy. AMaZE green/guide blijft herbruikbaar en locked. Native retained-output/concurrencyregressie toegevoegd en gecompileerd; uitvoering open. |
| Demosaic.cpp, klassieke entrypoints | `safeCfaPattern` kon een ongeldige/non-Bayer waarde stil naar RGGB coerceren. | Malvar/AMaZE wijzen unsupported CFA af met lege output. Productiecaller houdt bestaande failure/fallbackdiagnostiek; native regressie compileert. |
| RawDomain.cpp, normalizer/sample-view | Een oneven RAW16-rowstride kon tot ongealigneerde `uint16_t` row-access leiden. | Reject vóór toegang, met gecontroleerde failure reason. Geen productieassert/crash toegevoegd. |
| RawSampleReaders | `Int`-producten voor dimensions/stride konden overlopen; volledige laatste-rowpadding werd onnodig geëist. Deze helper werd niet door de actieve native ingest gebruikt. | Checked Long-arithmetic en minimaal leesbare last-rowpayload. Twee nieuwe JVM-tests slagen; geen claim dat dit de actieve camera-ingest rewritet. |
| BnCameraManager.applyExposurePolicy | Helper paste ook op een still-intent de preview-FPS-policy toe. Kon previewcadence aan still-AE koppelen. | Voor still-intent behoudt de nieuwe template zijn eigen cadence; manual frame-duration wordt niet generiek weggegooid. Compileert; fysieke low-lightvergelijking open. |
| SingleFrameRunner timings/AE-before | Wallclock voor elapsed durations; dispatchvergelijking gebruikte soms een sensor/eligibility timestamp; `lastAeState` werd pas later als “before capture” gelezen. | Monotonic durations, echte user-shuttertijd, AF/AE op entry bevroren. Geen sensor timestamp vergeleken alsof zijn klok altijd elapsed-realtime is. |
| DefaultRawSceneReplay | Assertions konden in release wegvallen; width×height en LSC-products onvoldoende gevalideerd; geometry/crop-origin ontbrak. | Runtimevalidatie, exacte payloadgrootte, overflowchecks en optionele geometry. Replaytarget bouwt; exporterfixtures 2/2 tests. |
| AfGroundTruthTrace / BnCameraManager tap mapping | Vijf API-30 zoomkeys ongeguarded terwijl minSdk 29 is. | Expliciete API-guards, unavailable/null op API 29. Geen lint-suppressies of minSdk-verhoging. |
| Phase0PerformanceTrace evidence writer | Filesystem-/rotatiefouten verdwenen in `runCatching`. | Rotatie en mkdir gecontroleerd, failures gelogd. |

## 4. Capture-ID, snapshot en manifest

Een admitted shutter krijgt een ID onafhankelijk van shotlogging. Iedere manager
heeft een eigen UUID-namespace zodat recreatie met een geresette attemptcounter
geen eerder ID hergebruikt; dit is ook door de identitytest afgedekt. De bestaande
numerieke coordinatorattempt en queuework-ID blijven interne lifecycle-identiteiten;
zij worden niet vervangen door een tweede coordinator.

Voor one-shot requests blijft `CameraRequestTag` het bestaande type. Het krijgt
een optionele capture-ID, die de exacte callbackprovenance behoudt. Repeating requests kunnen al vóór shutter lopen: hun geselecteerde frame kan
pre- of post-shutter zijn. Zij krijgen niet achteraf een fictieve still-tag. Een
ontbrekende request-capture-ID heet daarom `UNASSIGNED_TO_SHUTTER`, zonder een
onbewezen temporele classificatie.
Het manifest bindt hun echte image timestamp/frame number/controlrequest aan de
capture-ID van de geselecteerde opname.

Recipe schema 4 bevat ID, displayrotation en AF/AE-state op entry naast de bestaande
camera/lens/generation/output/demosaic/processing/outputpreferences. Processing
blijft de captured `RenderQualityPreferencesSnapshot` gebruiken. Een recipe wordt
niet opnieuw uit live UI-preferences geladen. Dit is geen bewijs van een
atomische transactie over alle losse DataStore reads tijdens recipeconstructie;
die bredere garantie blijft open.

Het app-private manifest staat als één terminalrecord per verwerkte capture in
`files/phase0/capture_performance.jsonl`, met bestaande begrensde rotatie. Schema 3
voegt `captureRecipe` en `build` toe; de top-level `captureId` is nu een string.
Consumers die een numerieke ID verwachtten moeten deze schemawijziging respecteren.

`metrics` bevat de exacte producer-request, requested shutter/ISO/AE-lock/EV/FPS/AF,
effective sensor exposure/ISO/frame-duration/AE-state/AF-state/lensdistance,
RAW-domain dump, timestamp/frame number, native ISP-resolve/timingvelden en
publicatiestatus/bytecounts. JPEG-outputafmetingen worden uit de werkelijk
gecodeerde bytes gelezen; source dimensions worden apart benoemd. DNG dimensions
komen uit de RAW16-input. Unavailable metadata wordt zo benoemd, niet verzonnen.

Native IspCore en heartbeat krijgen dezelfde ID. Outputpaths/personalisatie in
recipe blijven geredacteerd/hashed zoals in de bestaande export. Nieuwe velden
bevatten geen absolute privébestandspaden. `gitRevision` identificeert de basecommit;
deze lokale wijzigingen zijn nog ongecommit en zitten niet in die hash.

## 5. Malvar, AMaZE en Auto Hybrid

Malvar blijft pure Malvar-He-Cutler 2004 op scene-linear `CV_32FC1`, met RGB
`CV_32FC3` output. De bestaande finite-value policy behoudt negatieve lobes en
overshoot; downstream blijft eigenaar van clipping/tone. Geen kernels, adaptive
evidence of kleur/tone aangepast. Bestaande reference/context-independence-tests
blijven aanwezig. De nieuwe analytische golden controleert een interior red
impulse: RGB `(1, 0.5, 0.75)` met `1e-6` tolerantie.

AMaZE blijft de huidige BnCam-implementatie: CPU validation/fallback en twee
resident Vulkanpasses in productie. Green/Nyquist-guide en reconstructie zijn
niet opnieuw ontworpen. Alleen returned-RGB ownership, CFA-inputvalidatie en
allocationtelemetry zijn veranderd. Malvar moet zijn output nu zelf bezitten;
AMaZE rapporteert guide-reuse plus de noodzakelijke caller-owned outputallocatie.
Dit is een bewuste correctnesskostenpost, geen claim van snellere full-frame CPU-render.

Auto Hybrid was vóór deze ronde al een regionale GPU-blend van Malvar en AMaZE.
Scene/noise/focus/motion/CFA-evidence bepaalt soft priors; `algorithm` is de
dominante candidate/fallback/noiseproxy. Hybrid is dus geen eenvoudige
whole-frame selector. Na deze ronde doet Hybrid hetzelfde. Bestaande reasons,
priors, scores, GPU ownership en fallbacktelemetry zijn behouden; nieuwe replay
controleert deterministische policy op dezelfde input. Er is geen CPU-blend
verzonnen om een GPU-test groen te maken.

De nieuwe native suite heeft 80 combinaties: vier CFA's × vier crop-pariteiten ×
vijf geometries, waaronder 1×1 en oneven dimensions. Zij gebruikt productie-
normalisatie en beide echte demosaicfuncties, test sample preservation en finite
borders, retained output, herhaling en concurrente calls. Pixelverschillen tussen
Malvar/AMaZE worden gemeten; verschil op zichzelf is geen failure.
De suite is gecompileerd en gekoppeld aan de bestaande instrumentationtest,
maar zonder toestel is haar numerieke resultaat nog niet vastgesteld.

## 6. Camera control en settings

Focusownership voor auto/continuous, tap/tracking/manual wordt niet vervangen.
Normale shutter start niet alsnog een tweede AF-solver. Flash-AF/precapture
gebruiken bestaande begrensde waits en continuation/failure-policy; fixed-focus
wordt niet tot een eeuwige AF-lockwait gedwongen. Dit is code-inspectie plus
bestaande policy/ownership-tests, geen fysieke lenskwalificatie.

Warm ZSL is bewust een repeating-frame: zijn exposure en cadence horen bij zijn
echte request/result. Een still-template is een afzonderlijke request en krijgt
nu geen generieke preview-FPS herapplicatie. UI-FPS, requested FPS-range en werkelijke
frame-duration staan afzonderlijk in bestaande stream/policydiagnostiek en het
capturereport. De frame-selection en fysieke exposureallocator zijn behouden.

| Setting / groep | Bevinding | Behandeling |
| --- | --- | --- |
| Legacy `noiseA/B/C/D` | Actuele hardware snapshot/fingerprint noemt retired-neutral transport; oude test verwacht nog noiseB in fingerprint. | Niet opnieuw activeren of AI/noisepolicy wijzigen. Bestaande failure blijft expliciet zichtbaar. |
| Oude `spectraStrength` / `noiseReductionProfile` testverwachtingen | Recipe export gebruikt inmiddels andere bestaande authoritybenamingen; dit veroorzaakt al baselinefailures. | Geen profiel/Neural/Spectra herontwerp om source-tests tevreden te stellen. |
| `demosaic_mode` | Auto is regionaal hybrid; een simpele selectorbeschrijving zou misleidend zijn. | Bestaande resolver en productsemantiek behouden; replaydocumentatie gecorrigeerd. |
| RAW preview quality / SHARP | Bestaande policy houdt SHARP inactief; previewkwaliteit is geen nieuw still-demosaicbeleid. | Behouden; volledige UI-equivalentieaudit niet afgerond. |
| `liveViewfinderTuning` WB/saturation/contrast | Wordt bewust in de capture-renderpreferences vastgelegd en kan de foto beïnvloeden. | Capture-affecting, niet zomaar als preview-only verwijderen. Geen kleurpipelinewijziging. |
| `mirrorFrontPreview` | Bestaand outputcontract gebruikt deze captured waarde ook voor outputmirroring; naam alleen bewijst geen bug. | Gedrag behouden en zichtbaar in recipe; productsemantiek moet afzonderlijk worden beoordeeld. |
| Preview FPS versus dedicated still | Camera-session/repeatingbeleid werd via helper toegepast op still. | Bij still-intent gescheiden. |
| Private output/watermarkwaarden | Capture-affecting maar privacygevoelig in exports. | Bestaande redactie/hashes behouden. |

`audit_single_frame_settings.py` genereert `single-frame-settings-index.json`
met 219 literal DataStore key-declaraties/types/templates en source references.
Dit is een ontwikkelindex, geen export van actuele toestelwaarden. Defaults/scope/
migrations blijven onder de bestaande CaptureSettingsSchema en Libpatcher catalog;
captured runtime/mapped waarden staan in recipe. Dynamische profile/lens-templates
moeten worden resolved vóór keys als duplicates worden aangemerkt.

## 7. Tests en build

Commands vanuit repositoryroot, met
`$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`.

| Command | Resultaat |
| --- | --- |
| `.\gradlew.bat assembleDebug testDebugUnitTest --continue --console=plain` — nulmeting | APK/native build geslaagd; JVM 1.954 tests, 355 failures; combined exit 1. |
| Eerste cluster `compileDebugKotlin testDebugUnitTest --tests '*CaptureRecipeAndTraceTest' --tests '*ControlRequestProvenanceTest' --tests '*RawSampleReadersTest' --console=plain` | Compilerproblemen direct hersteld. Daarna 22 tests, alleen de twee bestaande recipe/fingerprint failures. |
| `.\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain` — native cluster | Geslaagd, inclusief nieuwe native suite en instrumentation-APK. |
| `python app/tools/test_single_frame_replay.py` | 2 tests geslaagd: deterministic export/geometry en malformed fixture. |
| `python app/tools/prepare_single_frame_replay.py app/src/test/fixtures/raw/odd-crop-grbg.json build/replay/odd-crop-grbg` | Export 100 bytes, SHA256 in fixture-manifest. |
| `python app/tools/prepare_single_frame_replay.py app/src/test/fixtures/raw/tiny-bggr.json build/replay/tiny-bggr` | Export 12 bytes, SHA256 in fixture-manifest. |
| `python app/tools/audit_single_frame_settings.py --output docs/single-frame-settings-index.json` | 219 declaraties geïndexeerd. |
| SDK `cmake.exe --build app/.cxx/Debug/713w4n3u/arm64-v8a --target bncam_raw_replay` | Replaytarget bouwt. Tool: bestaande SDK CMake 3.22.1. |
| `.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest --continue --console=plain` | Debug en test-APK geslaagd; JVM 1.960 tests, 355 failures, geen nieuwe failure-namen versus baseline. |
| `.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --continue --console=plain` — eindcontrole | Zie definitieve lint/buildresultaten hieronder; testtask blijft exit 1 door 355 bestaande failures. |
| ADB `devices` | Geen verbonden toestel; geen install, capture-smoke of native runtime-uitvoering mogelijk. |
| `git diff --check` | Geslaagd. |

Gerichte command, 56 tests geslaagd:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest --tests '*RawSampleReadersTest' --tests '*ControlRequestProvenanceTest' --tests '*CaptureAttemptCoordinatorTest' --tests '*ColdRawSinglePairingTest' --tests '*FocusOwnershipStateTest' --tests '*DefaultRawExposureAllocatorTest' --tests '*DefaultRawExposureRealizationEvaluatorTest' --tests '*RawFlickerCadencePolicyTest' --tests '*CaptureRecipeAndTraceTest.capturedLists*' --tests '*CaptureRecipeAndTraceTest.derivedProcessing*' --tests '*CaptureRecipeAndTraceTest.identity*' --tests '*CaptureRecipeAndTraceTest.recipeIsImmutable*' --tests '*CaptureRecipeAndTraceTest.traceSerializes*' --tests '*SpectraMilestone1SourceContractTest.milestoneOneTrace*' --console=plain
```

Gedekte aantallen: pairing 7, attempt lifecycle 8, provenance 10, exposureallocator
6, exposure realization 3, flickercadence 6, focusownership 2, RAW-reader 8,
vijf recipe-tests en één tracecontract. Zes JVM-tests zijn nieuw.
Alle bestaande tests blijven aanwezig. Twee assertions voor recipe schema 4 en
het performance schema 3 zijn aangepast aan de intentional contractwijziging;
de tracecontracttest is daarbij uitgebreid, niet uitgeschakeld.

`single-frame-validation-summary.json` bevat de volledige baseline/current
failurelijsten en het lege verschil. 355 failures herstellen zou aanvullende
triage van eerdere, deels buiten deze scope gelegen productwijzigingen vragen;
zij zijn niet stilzwijgend verwijderd of omzeild.

Toolchain: bestaande NDK 28.2.13676358, CMake 3.22.1, C++17 en arm64 app-ABI.
Geen versies gewijzigd. Geen nieuwe fast-math/SIMD/GPU-rewriteflags. Bestaande
debug GPU-benchmark/validationflags verschillen van release en worden in het
manifest benoemd. Geen release-runtime of cross-device bit-identieke output bewezen.

## 8. Nog open, op afhankelijkheid en prioriteit

1. Native instrumentation op een echt toestel draaien; de 80 replaycases,
   ownership/concurrencygoldens en bestaande native validations moeten werkelijk
   numeriek worden uitgevoerd. Buildsucces is daarvoor geen vervanging.
2. Eén volledige device RAW+calibration/profile/observer snapshot exporteren;
   dezelfde input in Malvar/AMaZE/Hybrid afspelen en GPU-resultaten/timings bewaren.
   De compacte bestaande replayformat dekt niet elke observerhint.
3. RAW10 en RAW_SENSOR low-light/focus/crop/physical-lens capture-smokes; dedicated
   still-cadence en warm repeating-policy afzonderlijk meten.
4. Back-to-back, processing overlap, rotate, pause/resume, lens-switch en cancellation
   op fysieke hardware testen met dezelfde capture-ID/provenanceketen.
5. Vroege router/admissionfouten ook het volledige manifest laten publiceren,
   met heldere ownership van de ene terminalreportauthority.
6. Atomische preference-source snapshot en volledige settingsaudit: UI/storage
   defaults, stored values, runtime clamps, migrations en dynamische keytemplates
   vergelijken. De bronindex alleen bewijst deze equivalentie niet.
7. De 355 baselinefailures classificeren op stale source-contract versus actuele
   functionele fout; alleen bewezen fouten repareren binnen de toegestane scope.
8. Pas na runtime-goldens gemeten performanceoptimalisaties overwegen. De nieuwe
   RGB-ownership moet behouden blijven bij eventuele pooling; geen output weer
   laten aliasen met herbruikbare scratch.

## Definitieve validatie

De laatste volledige command `assembleDebug assembleDebugAndroidTest testDebugUnitTest
lintDebug --continue --console=plain` voltooide alle taken: beide APK-targets en
lint slaagden; alleen `testDebugUnitTest` faalde met de 355 bestaande failures.
Er waren 1.960 JVM-tests en geen nieuwe failure-namen versus de nulmeting.

De losse bevestiging `.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
--console=plain` eindigde met **BUILD SUCCESSFUL**. Lint: **0 errors, 111 warnings,
8 hints**. De vijf NewApi-errors zijn gerepareerd, niet gesuppressed. Android/native
instrumentation blijft **niet uitgevoerd** wegens het ontbreken van een ADB-device.
De twee Python fixturetests en 56 gerichte JVM-regressies slagen.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Instrumentation-APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
Build/testcommandlogs staan lokaal onder `work/single-frame-*.log`; machineleesbare
validatiesamenvatting staat in `docs/single-frame-validation-summary.json`.

Na de laatste ID-namespace/metadata-labelcorrectie zijn `assembleDebug assembleDebugAndroidTest testDebugUnitTest --continue` opnieuw uitgevoerd: beide APKs bouwden; de 1.960 tests hadden dezelfde 355 failures. De losse finale `assembleDebug assembleDebugAndroidTest --console=plain` slaagde ook.
# Update na fysieke toestelvalidatie (6 oktober 2026)

De hieronder beschreven eerdere beperking zonder ADB-device is inmiddels opgeheven.
De actuele hardwarebevindingen, fixes en eindresultaten staan in
[SINGLE_FRAME_DEVICE_VALIDATION_REPORT.md](SINGLE_FRAME_DEVICE_VALIDATION_REPORT.md).
Finale connected tests: 11/11; native replaycases: 80; gerichte JVM-tests: 69/69.

