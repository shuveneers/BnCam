# Single-frame devicevalidatie — 6 oktober 2026

De actuele lokale working tree is op de aangesloten telefoon gebouwd, geïnstalleerd en uitgevoerd. De finale connected ronde: **11 tests, 11 passed, 0 failed, 0 skipped**. De native demosaic-regressie omvat daarnaast **80 daadwerkelijk uitgevoerde combinaties**. De laatste gerichte JVM-ronde: **69 passed, 0 failed**. Er zijn geen commits, pushes, delta-ZIPs, beeldkwaliteitstuning of architectuurwijzigingen gemaakt.

Dit is een validatie van de beschikbare binnenscène. De gebruiker kon de verlichting niet veranderen. Goede verlichting versus gecontroleerd low light, een zeer heldere scène en een lichtstap vlak voor shutter zijn dus **niet gekwalificeerd**. De beschikbare scène geeft wel hoge ISO en de RAW-sceneclassificatie meldt low light.

## A. Device

| Veld | Waarde |
|---|---|
| Fabrikant/model | HONOR BKQ-N49 |
| Android / API | 17 / 37 |
| ABI | arm64-v8a |
| GPU | Qualcomm Adreno 840 |
| Expliciet gekozen ADB-serial | AUWE025B03006422 |
| App / Git-basis | debug 1.0 / 30a0f68f399662d07bde9479398a3b632b2d28e7 + lokale wijzigingen |

ADB was aanvankelijk leeg door de USB-verbinding en viel tijdens de finale replay kort offline. Na opnieuw aansluiten is dezelfde serial gebruikt. Er is geen native crash vastgesteld bij die verbindingsonderbreking; de onderbroken meting telt niet mee.

## B. Native en connected tests

De finale Gradle connected ronde voert deze classes uit: `DemosaicNativeValidationTest`, `VulkanDeviceVerificationTest`, `RawPreviewRecoveryDeviceTest`, `PhysicalLumaDeviceTest`, `PhysicalChromaDeviceTest`, `PublicationPolicyDeviceTest`, `NoiseModelNativeConnectedTest`, `ExampleInstrumentedTest` (2 methods), `StillExposureDeviceTest` en `ManagerShutdownDeviceTest`.

**11/11 passed; geen failures/skips/errors.** Bewijs: `work/device-validation/instrumentation-final11-passed.xml`, `connected-final-fixed.log` en `connected-final-passed-proof.txt`.

De demosaic-suite rapporteert `normalization`, `cropParity`, `determinism`, `outputOwnership`, `samplePreservation`, `finiteBorders`, `malvarImpulseGolden`, `hybridPolicyDeterminism` en `unsupportedCfaRejected` allemaal true. Vier CFA's × vier origin-parities × vijf geometrieën = 80. Retained output blijft behouden na een volgende call; parallelle calls slagen. Malvar en AMaZE leveren onafhankelijke RGB-output. Geen SIGSEGV/SIGABRT of corruptie in de uitgevoerde tests. Dit is geen ASAN-build.

Eerdere exacte failures waren `DemosaicNativeValidationTest.bilinearMalvarAndMenonSyntheticValidationPassInNativeLibrary`, `VulkanDeviceVerificationTest.verifyVulkanRuntimeTruthOnConnectedDevice` en `NoiseModelNativeConnectedTest.productionNativeNoiseModelSamplesVarianceAndChangesConsumer`. De eerste ronde legde een echte normalisatiefout bloot; de andere assertions/fixtures pasten niet volledig bij het huidige native contract. Zie K voor onderscheid tussen productiebugs en testreparaties.

## C. Malvar

Echte referentie: capture `c6a3cd0e-2267-4174-bb29-d5941ca6718f-1`, main camera 2, RAW_SENSOR, 4096×3072, RAW16 stride 8192 bytes, BGGR, origin (0,0), white 1023, black ongeveer [63.984375,64,64,63.984375], ISO 1019, exposure 29.999995 ms. WB [1.3388672,1,1,2.553711], exacte CCM, fysieke SO/noise- en LSC-metadata zijn opgeslagen met de recipe/processing snapshot.

RAW-payload 25.165.824 bytes; SHA256 `07cac3e40cbca321e79a8207f7db2c8b7a1cf5a629bbf698af8dc89dc2b335af`. Fixture: `build/replay/device/main/`; debugexport alleen na een expliciete marker, release-exporter is een no-op.

Alle drie finale replays gebruiken exact deze payload. Malvar-JPEG SHA256 is in alle herhalingen `a8879da66325cc06e69fb2f5c74e9a924988903439c713d3491f97485b3e05d9`. Genormaliseerde samples zijn finite en in [0,1]. Een aanvullende CPU-audit op de volledige echte fixture bewijst retained ownership, determinisme en behoud van sampled sensels; RGB min/max -0.118465/1.02737. Interpolatie-overshoot wordt dus vóór downstream processing niet voortijdig naar [0,1] geklemd.

Visuele inspectie van de werkelijke opgeslagen JPEGs/replayvergelijking toont correcte oriëntatie en complete beelden, zonder zichtbare kanaalverwisseling, Bayer-checkerboard of corrupte borders. De scène bevat warm gemengd licht; zonder kleurreferentie is absolute kleurnauwkeurigheid geen bewezen resultaat. Timings staan in L.

## D. AMaZE

Dezelfde RAW, geometry, metadata en outputoriëntatie. Drie finale replays zijn bitexact: JPEG SHA256 `ec7d8cd7e9310a9ea2e37e090b7ea1f2990c84bc694fa529fe4a204fb564f89e`. Volledige CPU-fixtureaudit: finite RGB, retained ownership, determinisme, sample preservation; min/max -0.18508/1.01933. Native synthetische suite controleert green guide/Nyquist, resident intermediates, reconstructie en borders.

De echte replays rapporteren Vulkan-uitvoering en `fallbackReason=none`; geen Vulkan failure in de capture-evidence. Demosaic GPU-kernel en synchronisatietijd zijn afzonderlijk gelogd. Native heap peaks tijdens de tien AMaZE-stresscaptures: 435.315.936–435.621.584 bytes; app RSS 1.087.772–1.120.952 KiB. Geen monotone groei over elke capture vastgesteld.

## E. Auto Hybrid

Runtime bevestigt `demosaicResolveReason=auto_hybrid_region_aware_gpu`, beide kandidaten ready, actieve routes Malvar/AMaZE en geen Neural-route. De fixture heeft Malvar-prior **0.814891**, AMaZE-prior **0.185109**; scores circa 0.6294 en 0.1848. Fysieke noise pressure circa 0.2065; motion risk 0.15. GPU guide/blend-stagetimings zijn niet nul. `fallbackReason=none`; geen onbedoelde CPU-fallback.

Drie finale JPEGs zijn bitexact: SHA256 `7bcf244fb8f50a9f192fe8d69b3edbe3e3b274dad2f18a3bda971b2dd2b0472c`. De policybeslissing blijft gelijk. Priors zijn **geen gemeten gemiddelde lokale blendbijdragen**: het volledige regionale float-mask is niet uitgelezen. Daarmee is de regionale uitvoering aangetoond, maar een onafhankelijke bounds/NaN-audit van iedere maskwaarde blijft open. Geen thresholds of blendlogica gewijzigd.

## F. RAW10 / RAW_SENSOR per lens

24 geldige smoke-captures: vier actieve lensrollen × twee formats × drie demosaicpaden, allemaal PUBLISHED. Zes aanvankelijk als tele gelabelde captures bleken via de recipe camera 4 te gebruiken: die zijn uitgesloten van de lenskwalificatie. Tele is opnieuw op camera 5 getest met asserts op werkelijk camera-ID en format.

| Actieve standalone sensor | Formats / resultaat | RAW dimensions / CFA | Effective exposure / ISO in deze scène |
|---|---|---|---|
| Main 2 | RAW10 + RAW_SENSOR, 6/6 | 4096×3072, BGGR | 30 ms / 898–955 |
| Ultrawide 4 | RAW10 + RAW_SENSOR, 6/6 | 4032×3024, BGGR | 60 ms / 2825–3666 |
| Tele 5 | RAW10 + RAW_SENSOR, 6/6 | 4080×3072, BGGR | RAW10 33.333 ms / 5413–5463; RAW_SENSOR 40 ms / 4594–4875 |
| Front 1 | RAW10 + RAW_SENSOR, 6/6 | 4096×3072, GRBG | 70 ms / 4159–6745 |

Dit zijn direct geopende standalone camera-ID's; `physicalCameraId=null` betekent hier STANDALONE en geen gefabriceerde logical-to-physical fallback. Origin (0,0) is gelogd; formaat, black/white levels, afmetingen, exact image timestamp/frame number, request, effective AE/AF, exposure/FPS en publication zijn per capture behouden in `work/device-validation/capture-evidence.csv` en de volledige `capture-runs.jsonl`.

Op de geïnspecteerde echte JPEGs geen zwart/half beeld, duidelijke parityverschuiving, gecorrumpeerde rand of verkeerd geroteerd beeld. Hoge ISO toont zichtbare ruis; dat is niet getuned. Er is geen gecontroleerde helderlicht- versus low-lightmatrix uitgevoerd.

## G. AF

Vier focuscaptures PUBLISHED: continuous zonder tap, direct na tap, snelle tweede capture en herstel naar AUTO. Requested AF mode 4/trigger 0; effective AF state 2, focusafstand 1.8518518 diopter. De snelle tweede capture heeft eigenaar TAP en gewogen tap-regions. De direct-na-tapcapture selecteerde terecht een reeds bestaande pre-shutter AUTO-frame; dat wordt als zodanig gerapporteerd, niet als bewijs dat de taprequest al dat beeld stuurde.

Front: AF mode 0, state 0, distance 0; vaste focus. Tijdens succesvolle captures geen permanente focuswait. Een eerdere harnesspoging vond WhatsApp op de voorgrond en een ontbrekende actieve pipeline; die timeout is geen AF-timeouttest. Een expliciet geforceerde HAL-AF-timeout is niet uitgevoerd.

## H. AE

AUTO main: requested shutter/ISO AUTO, AE converged state 2, circa 16.667–30 ms met ISO afhankelijk van de scène. Wide/tele/front halen langere exposures zoals F beschrijft. Manual warm test requested ISO 100 / 100.000.000 ns; werkelijk **ISO 100 / 99.999.971 ns**, frame duration 100.089.173 ns, AE off (0). Na herstel AUTO: 16.666.662 ns, ISO 996, frame duration 33.325.557 ns, AE state 2.

Elke effectieve waarde komt uit het resultaat van het geselecteerde frame. Er is geen verkeerde sensor/result-associatie gezien in de geldige captures. Een stilstaande scène en enkele samples bewijzen geen afwezigheid van oscillatie of convergenceproblemen bij een lichtstap; die scènes zijn niet beschikbaar gesteld.

## I. Preview FPS versus dedicated still

De onafhankelijke Camera2/HAL-regressie opent expliciet camera 2. Preview request [10,30], actual preview duration **33.325.557 ns**. Verse RAW still met AE off, ISO 100, requested exposure **100.000.000 ns**, requested frame duration 110.000.000 ns en zonder expliciete FPS-key: actual exposure **99.999.971 ns**, actual frame duration **109.976.839 ns**, ISO 100 en RAW timestamp exact gelijk aan CaptureResult. De HAL kan dus langer dan een 30fps-previewframe belichten.

De BnCam manual+flash-proef blijft warm REPEATING, omdat manual exposure de hardwareflash uitschakelt. Die aanvankelijke diagnostische label `exposure-dedicated-manual-100ms` is geen dedicated bewijs. De echte BnCam flash-regressies zijn ONE_SHOT/STILL_CAPTURE en PUBLISHED voor alle drie paden, met exact hetzelfde capture-ID op de request en recipe. De HAL koos daar circa 20 ms, ISO 167–194 en 33.325557 ms frame duration. De template rapporteert [10,30]; dit bewijst bij AUTO-flash geen langere exposure. Productiepolicy schrijft de aangepaste preview-FPS niet in `applyExposurePolicy` voor STILL_CAPTURE. De 100ms-proof toont HAL-capaciteit afzonderlijk; niet een niet-bestaande BnCam manual dedicated route.

## J. Stress, lifecycle en geheugen

Tien sequentiële captures per pad: **30/30 PUBLISHED**. Daarna bursts: zes aanvullende publicaties. Snelle triggers oefenen begrensde backpressure uit; er is een expliciete queue-full rejection gelogd. Een burst is geen garantie dat iedere trigger wordt aangenomen. Vroege afwijzingen hebben nog niet allemaal een durable phase0-terminalregel; er is geen exact afwijzingsaantal verzonnen.

Het lokale bewijs bevat 91 terminal capture-records en 91 unieke ID's, inclusief de zes verkeerd gelabelde extra wide captures. Drie eerder opgeslagen outputs A/B/C blijven na de volledige reeks SHA256-identiek (`preserved-outputs-after.json`).

Lifecycle: 15/15 PUBLISHED over drie demosaicpaden: initial capture, rotatieverzoek, background/foreground, lensswitch en snelle tweede lensswitch. Generations veranderen; de geldige frames houden de juiste request/sensoridentiteit. De Activity is portrait locked: displayRotation bleef 0. Het software-rotatieverzoek is dus geen fysieke oriëntatiekwalificatie. Een echte teardowncrash is gevonden en opgelost; de nieuwe connected test sluit en heropent de Activity twee keer zonder die crash.

RSS-bereiken (KiB): Malvar 1.013.680–1.068.232; AMaZE 1.087.772–1.120.952; Hybrid 1.085.668–1.124.572. Graphics circa 192 MiB; warm ring houdt tot 15 RAW-images, circa 360 MiB. Native heap peaks bij de 30 sequentiële captures circa 415–416 MiB. Median gemeten combined heap peak delta: Malvar 65.1 MiB, AMaZE 64.0 MiB, Hybrid 64.4 MiB. Dit is heapobservatie, geen exacte malloc-telling. Geen monotone lekcurve of ImageReader-starvation vastgesteld in deze reeks; geen optimalisatie geforceerd. Thermal status liep op van 1/2 naar 3.

## K. Bewezen bugs en testreparaties

| Probleem / bewijs | Root cause en fix | Hercontrole |
|---|---|---|
| Native 80-case normalisatie-oracle faalde bij odd origins | Scalar tail verschoof black-phase tweemaal; al origin-gecorrigeerde blk0/blk1 worden nu op x-parity gekozen | Alle 80 cases slagen; echte RAW-normalisatie finite |
| `removeObserver must be called on the main thread`, crash PID 11737 om 21:36:04 | Manager shutdown deed lifecycle observer removal op IO; removal wordt naar main dispatch gepost | Twee echte Activity-close/reopen cycles in connected ManagerShutdownDeviceTest |
| Flash callback completed maar exact RAW+metadata ontbreekt | Dedicated callback registreerde geen exact getagde metadata in de ring; gebruikt nu dezelfde sensor snapshot/provenance registratie | Exact pair bereikt vervolgroute |
| Daarna `Post-shutter still contract violated` | Flash lease werd vóór runner vrijgegeven en collector ontving andere candidates; de bewezen exact lease wordt tot runner dispatch/einde behouden | 3/3 echte flashcaptures PUBLISHED, exact requestCaptureId; naburige JVM en connected regressies slagen |
| Oude demosaic/Vulkan assertions | Verouderd outputtoken, aanname dat GPU in dezelfde testprocess nog niet actief was en hardcoded GPU-naam | Assertions volgen het huidige echte device/stages-contract |
| Noise harness `provenanceValid=false` | 192×256 fixture gaf slechts 16 samples/tile bij cadence 16, terwijl observer minimaal 32 vereist; oude consumer-fields waren al verwijderd | Alleen validatorfixture naar 768×1024 en huidige sterke asserts; physical SO-response/provenance/ISO-monotonicity slagen |

Noise/tone/Neural/Spectra-productiealgoritmen zijn niet getuned. De wijziging in `validateNoiseModelImplementation` is uitsluitend testfixturegrootte.

## L. Performance

Mediane **ms over drie finale runs per pad**, exact dezelfde RAW. Elk replayproces start koud en compileert/initialiseert GPU-resources. Thermal status vóór deze reeks was 3 en veranderde gedurende de reeks; de volgorde is Malvar → AMaZE → Hybrid. Deze getallen zijn geen eerlijke snelheidsrangschikking en geen steady-state shutter-to-savebench.

| Path | ingest | normalize | demosaic | post | encode | save | total |
|---|---:|---:|---:|---:|---:|---:|---:|
| Malvar | 205.0 | 59.4 | 2017.5 | 5341.6 | 214.8 | 16.1 | 7947.4 |
| AMaZE | 138.3 | 50.6 | 1628.6 | 3814.3 | 128.7 | 13.0 | 5830.3 |
| Auto Hybrid | 104.0 | 40.0 | 1079.0 | 3294.8 | 172.1 | 10.1 | 4790.9 |

Ingest is fixture-read/RAW16 preparation; normalize is production RawDomain. Demosaic/encode komen uit native stagevelden. Post is render-wall minus demosaic en encode en omvat overige ISP/downstream/overhead. Save is schrijven van dezelfde JPEG naar replaybestand, niet MediaStore. Total omvat ook bootstrap/initialisatie; componentmedians hoeven niet op te tellen. Ingest begint vanaf de reeds opgeslagen canonical RAW16-payload, niet vanaf Camera2 RAW10-unpacking.

Demosaic median GPU-kernel / synchronization ms: Malvar **14.81 / 24.24**, AMaZE **55.81 / 65.56**, Hybrid **68.88 / 76.81**. Synchronization is wachten/completion; er is geen apart betrouwbaar API-submission-wallveld. Cold wall timings bevatten setup/shadercompilatie en readback. De eerste eerdere runs waren aanzienlijk sneller in total dan de thermisch belaste finale reeks; dat is bewaard, niet als optimalisatieclaim gebruikt. Volledige per-run waarden, hashes en fallback staan in `docs/single-frame-device-validation-summary.json` en `work/device-validation/*-timing.txt`.

## M. Openstaande punten, op afhankelijkheid

1. Complete terminal observability voor vroege router/backpressure-failures; noodzakelijke basis voor exacte trigger/admission/failure-tellingen.
2. Onafhankelijke read-only numerieke audit van alle regionale GPU-blendweights en float-intermediates; huidige bewijs toont uitvoering/determinisme en geldige output, geen full-mask rangecheck.
3. Gecontroleerde scenevalidatie: goede verlichting versus low light, zeer helder, lightstep voor shutter, kleur-/Nyquistreferentie, geforceerde AF-timeout en fysieke rotatie. Deze zijn niet afgevinkt.
4. Performance opnieuw met gecontroleerde thermische toestand en warm persistent replayproces; daarna pas eventueel optimaliseren. Huidige thermal/order/cold-startinvloed verhindert snelheidsranking.
5. Bestaande brede JVM-testschulden oplossen vóór een claim dat de hele suite groen is. De eerdere baseline had 355 failures; deze ronde gaf in een aanvullende 41-testcluster 17 failures, exact allemaal al in die baseline. Finale relevante 69 tests slagen; de volledige suite is niet opnieuw groen verklaard.

Finale checks: `assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest` geslaagd na alle fixes; 69 relevante JVM-tests geslaagd; `git diff --check` zonder fouten. App is daarna opnieuw geïnstalleerd/geopend, bewaarde calibratieprofielen/RAW-evidence zijn hersteld. Diagnostiek staat onder genegeerde `work/device-validation/`, geen productie-assets. De working tree bevat de eerdere ronde plus deze lokale wijzigingen.
