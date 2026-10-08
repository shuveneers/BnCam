# Single-frame observability en qualification — 7 oktober 2026

> **Actueel vervolg na opnieuw verbinden van ADB:** zie [DELTA-DEVICE.md](DELTA-DEVICE.md). Onderstaand verslag bewaart de oorspronkelijke nulmeting en de toenmalige open punten; de device-status daarin is historisch.

**Status: NIET AFGEROND.** Lokale implementatie, builds en een gerichte JVM-gate zijn geverifieerd. De telefoon verdween na de nulmeting uit ADB. Daardoor zijn de nieuwe GPU-readback, instrumentation-backpressure, echte captures, warme benchmark en einddevice-run **niet uitgevoerd**. De pipeline kan op basis van deze ronde nog niet als volledig gekwalificeerd worden vrijgegeven voor kwaliteitstuning.

Geen commit, push, ZIP, voorbeeldproject of nieuwe parallelle architectuur. Geen denoise-, kleur-, WB-, tone-, sharpness-, Hybrid-prior/threshold-, Malvar/AMaZE- of Neural-tuning.

## 1. Baseline

| Item | Waarneming |
|---|---|
| Branch | `main` |
| Commit | `5c3f7188a10f2a231f8192d9fe5b3a8f1d828b86` |
| Working tree bij start | schoon |
| Gekozen serial | `AUWE025B03006422` |
| Model | Honor `BKQ-N49` / ADB `BKQ_N49` |
| Android / API / ABI | 17 / 37 / arm64-v8a |
| JDK | `C:/Program Files/Android/Android Studio/jbr` |
| ADB | `C:/Users/shuve/AppData/Local/Android/Sdk/platform-tools/adb.exe` |
| Eerste builds | `assembleDebug assembleDebugAndroidTest`: geslaagd |
| Connected nulmeting | 10 tests, 6 geslaagd, 4 failures |
| JVM nulmeting | 1.954 tests, 355 failures |

ADB en Java stonden aanvankelijk niet op PATH. De bestaande SDK/JDK zijn gebruikt; niets geïnstalleerd.

De vier connected baselinefailures:

1. `DemosaicNativeValidationTest`: de native validator rapporteerde **`allPassed=true`**, maar de Kotlin-test verwachtte het oude `bilinearForcesBilinear`. De actuele stabiele mapping meldt `normalForcesMalvar=true` en `legacySlot2ForcesAmaze=true`. Alleen deze aantoonbaar oude testverwachting is bijgewerkt; de gewijzigde connected test is nog niet op device uitgevoerd.
2. `NoiseModelNativeConnectedTest`: verwacht meerdere oude proof-velden; bovendien meldt de native validator zelf `provenanceValid=false;allPassed=false`. De volledige root cause is **onzeker**. Niet opgelost door noise/productcode te veranderen.
3. `Phase4Block2ConnectedTest`: Espresso/Compose faalt met `NoSuchMethodException: android.hardware.input.InputManager.getInstance` op API 37. Framework/device-compatibiliteit is een onderzoekspunt.
4. `VulkanDeviceVerificationTest`: bestaande assertionfailure; contract/verwachting nog afzonderlijk onderzoeken.

De passing tests betroffen app-context/DataStore (2), fysieke chroma, fysieke luma, publication policy en RAW-preview recovery. Dit bevestigt delen van de bestaande native baseline, maar maakt de volledige baseline niet groen. De eerder genoemde 80-case standalone suite is in deze ronde nog niet opnieuw uitgevoerd.

Bronnen: `work/qualification-baseline-build.log`, `work/qualification-baseline-connected.xml`, `work/qualification-baseline-connected.log`, `work/qualification-baseline-jvm.log`. Build/device-identiteit staat in `run-manifest.json`.

## 2. Terminal observability

`CaptureTerminalAccounting` is de enige authority voor terminal trigger-outcomes. Identiteit is **session UUID + capture ID**; het ID wordt bij ontvangst gemaakt, vóór UI-guards/eventbus/admission. Een immutable terminalrecord bevat timestamp, source, router-admission, status, reason en context. Na terminal afronding verdwijnt de outstanding entry, zodat dubbele callbacks geen tweede record maken.

De bestaande attempt-coordinator blijft eigenaar van acquisition/processing/save-state. UI, router en queue voeren geen onafhankelijke terminalboekhouding. Het historische performance-`captureId` blijft een processing-report-ID; `recordType`, `captureTriggerId` en `terminalAuthority` maken die relatie expliciet. Tel voor outcomes uitsluitend `files/phase0/capture_terminal.jsonl` en de rotatie `.1`.

Geauditeerde/afgedekte paden:

- hardware-debounce, eventbuffer vol en geen actieve capture-UI;
- video niet ondersteund, timer actief, shutter dispatch actief;
- busy router met eigen ID voor de afgewezen trigger;
- vroege router-/pipeline-/frame-/RAW-/processing-/publication-failure via de bestaande attempt-finalizer;
- queue-submission rejection expliciet `REJECTED`;
- coroutine-annulering expliciet `CANCELLED`, met hergooien van `CancellationException`;
- camera-close annuleert acquisition-attempts;
- reeds submitted werk blijft queue-owned en krijgt zijn werkelijke publication/failure, ook na Activity-teardown;
- late worker binding reconcileert een reeds terminal queuesnapshot;
- een vóór routerdelivery geannuleerde trigger kan geen nieuw werk starten.

### Gemeten JVM-stressscope

Deze tabel betreft **twee deterministische JVM-tests met geïnjecteerde outcomes**, geen echte foto's of device-queue-throughput. De verwachte aantallen zijn door assertions in de geslaagde gate gecontroleerd.

| Outcome | Count |
|---|---:|
| Triggered | 200 |
| Admitted — router | 101 |
| Published | 25 |
| Rejected | 124 |
| Failed | 25 |
| Cancelled | 26 |

`200 = 25 + 124 + 25 + 26`. De eerste scope telt 100 triggers met 99 busy-rejections en één close-cancellation; de tweede scope telt 100 detached attempts met 25 van ieder outcome, afgerond in omgekeerde volgorde. Dubbele completion-callbacks produceren geen extra records; de sets trigger-ID's en terminal-ID's sluiten exact.

`CaptureTerminalBackpressureDeviceTest` gebruikt de **echte productie-admission**: drie reservations blijven bewust vastgehouden, zodat de volgende 97 triggers op queue-full moeten stranden. De drie held reservations worden expliciet opgeruimd. Een tweede connected test controleert late terminalbinding. Deze tests zijn gebouwd, **niet uitgevoerd**; 97/3 zijn hier testasserties, geen gemeten deviceresultaat.

Echte shutter-stress ontbreekt nog. Het ADB-script controleert terminal-ID's, duplicates, aantallen, reasons en minstens één daadwerkelijke publication via de single-frame route. Een uitsluitend afgewezen scope geldt niet als geslaagde camera-smoke.

Grenzen: abrupt process kill/OS-crash is niet als crash-recoveryjournal geïmplementeerd. De bestaande async writer en begrensde JSONL-rotatie blijven gelden. Export moet de relevante testscope inclusief `.1` bevatten; een onvolledige scope wordt afgekeurd. Racegevallen vóór daadwerkelijke queue-overdracht en echte lifecycle-hercontrole blijven device-gates.

## 3. Auto Hybrid numerical audit

De echte shader is geïnspecteerd: de eindrelatie is `M * MalvarRGB + A * AmazeRGB`, gevolgd door bestaande finite handling. De formule, thresholds, scores en priors zijn ongewijzigd.

Een expliciete `hybridValidationReadback` kan uitsluitend bij een validation-enabled native build worden geactiveerd. Gewone captures gebruiken dit niet; release negeert de aanvraag. Mode 12 voert een afzonderlijke diagnostic pass uit na de bestaande reconstructie. Er is geen permanente full-frame GPU-readback toegevoegd aan het productiepad.

Per pixel worden 20 floats uitgelezen: M, A, structure, Nyquist, noise pressure, chroma risk, low-signal, near-tie, beide kandidaat-RGB's, de werkelijk resident Hybrid-output, een written flag en x/y. Het volledige veld wordt als `.f32` geëxporteerd.

De harness gebruikt een volledig 257×193 veld uit de linkerbovenhoek van de echte RAW-fixture, inclusief alle borders en partial workgroups. Dit is **een crop-fixture**, geen bewijs over ieder pixel van de volledige sensorresolutie. Onafhankelijke Malvar- en AMaZE-executies vormen de output-oracle. Er zijn drie replays; de scene-resolver wordt opnieuw uitgevoerd voor vergelijking van priors, scores, availability en fallback. Tolerantie: absolute `2e-6` voor complement/oracle/candidates; determinisme wordt bitexact geëist.

| Controle | Nieuw gemeten resultaat |
|---|---|
| Mask/weight min/max | NIET GEMETEN |
| NaN / Inf / out-of-range | NIET GEMETEN |
| Unwritten / border garbage | NIET GEMETEN |
| Complementary-weight error | NIET GEMETEN |
| Candidate/oracle error | NIET GEMETEN |
| Priors/score/mask/output determinisme | NIET GEMETEN |

De executable en shader bouwen. Runtimebewijs ontbreekt door de verdwenen ADB-device. Ook de 4 CFA × 4 pariteiten × 5 geometrieën CPU-suite is in de harness opgenomen, met finite/sensel-preservation/outputownership/determinisme-asserties; deze nieuwe harness-run is nog niet uitgevoerd.

## 4. Scene qualification en evidence

`scene-matrix.json` bevat A–H, doelen, fysieke setup, RAW-formaten, paden, lensdekking, herhalingen en capturecommando's. Geen scene is als fysiek beschikbaar of succesvol verondersteld.

| Scene | Status | Nog nodig |
|---|---|---|
| A daylight | NOT AVAILABLE / NOT QUALIFIED | stabiele goed verlichte detail-/kleur-/clipscene |
| B low light | NOT AVAILABLE / NOT QUALIFIED | fysiek gedimde reproduceerbare verlichting |
| C highlights | NOT AVAILABLE / NOT QUALIFIED | heldere/speculaire patches plus aangrenzende kleur |
| D fine detail/Nyquist | NOT AVAILABLE / NOT QUALIFIED | tekst/lijnen/stof/raster/slanted edges op vaste afstand |
| E kleurreferentie | NOT AVAILABLE / NOT QUALIFIED | ColorChecker of vaste kleurrijke scene + lampbeschrijving |
| F exposure transition | NOT AVAILABLE / NOT QUALIFIED | getimede fysieke dark↔bright-overgangen |
| G focus challenge | NOT AVAILABLE / NOT QUALIFIED | near/far targets, tap en snelle tweede shutter |
| H orientation | NOT AVAILABLE / NOT QUALIFIED | fysiek portrait, landscape left en landscape right |

Ook een nieuwe capture van de huidige ongecontroleerde scene ontbreekt: de verbinding verdween vóór de nieuwe device-kwalificatie.

Opt-in evidence wordt automatisch via de bestaande achtergrondwriter opgeslagen in `files/phase0/qualification_evidence.jsonl`. De RAW-data worden onder hun bestaande ownershiplock gehasht via een read-only direct-buffer-view; er ontstaat geen extra volledige managed RAW-array. Het checksumdomein is expliciet **canonical dense RAW16 little-endian**, niet de oorspronkelijke packed Camera2 RAW10 bytes.

De evidence combineert trigger-ID, git revision, scene-label, bevroren recipe/resolved settings, camera/lens/source, RAW-dimensies/CFA/crop/black/white/checksum, requested Camera2 exposure/ISO/AE-mode, effective exposure/ISO/frame-duration/AE/AF/focus, focus-context, EV, WB/noise state, native processing/fallback/Hybrid-gegevens, bestaande timing en checksum/dimensies van de gepubliceerde JPEG na EXIF. Niet gerapporteerde HAL-waarden blijven null. Onder AUTO zijn ontbrekende handmatig gevraagde exposure/ISO-velden geen gemeten nul.

Hashing gebeurt alleen voor expliciete kwalificatiecaptures. De overhead wordt afzonderlijk gelogd. RAW-only heeft uiteraard geen JPEG-checksum. Hostmanifest en terminalrecord leveren build/session/timestamp-correlatie. Het complete on-device schema en de export zijn nog niet end-to-end geverifieerd.

## 5. AF

Geen nieuwe fysieke AF-resultaten. `FocusOwnershipStateTest` zit in de groene lokale gate; dat is state-policybewijs, geen lens/convergencebewijs.

Het bestaande engine-pad `focusAndLockAt(..., convergenceTimeoutMs)` heeft een begrensde 250–2.000 ms deadline en logt `POINT_LOCK settle=converged|timeout`. `holdTapFocusFor` is een tap-hold/deadline en mag niet worden verward met HAL-convergence-timeout. Geen HAL-states vervalst en geen production test override toegevoegd. Een veilige **gegarandeerd geforceerde** timeouttest is nog niet gerealiseerd/gekwalificeerd.

Open: continuous stabiele scene, near→far, far→near, tap+direct capture, tap+settled capture, tweede shutter, fixed-focus front, focus-loss/recovery en timeout→fallback→capturecontinuation.

## 6. AE

Geen nieuwe fysieke AE-transitions. `FrameSelectionExposurePolicyTest` is lokaal groen. De evidence legt selected sensor timestamp en exposure-result vast; bestaande Near-ZSL diagnostics bewaren shutter/frame-provenance. Dit bewijst nog niet de dynamiek rond een fysieke lichtverandering.

Open: dark→bright, bright→dark, shutter tijdens/na convergence, HDR-scene, zeer heldere clipping, preview AE timeline en expliciet shutter↔selected-frame timestampverschil met gecontroleerd changemoment. Er is geen uitspraak gedaan dat een pre-change frame nooit wordt geselecteerd.

## 7. Settingsaudit

`settings-audit.json` bevat 220 persisted-key-call-sites en 15 verder getraceerde prioriteitscategorieën. De inventory is uitdrukkelijk een bronindex; gedeelde/dynamische accessors bewijzen geen dead setting. Iedere prioriteitsrij bevat type/default/range/clamp/scope/session/capture-affecting/source-authority en expliciete ontbrekende devicevelden.

| Klasse | Bevinding |
|---|---|
| Bewezen nieuwe settingsbug | geen vastgesteld |
| Legacy/stale | oude exposure-priority/multiplierfields worden gesanitized naar Balanced/1/1; oude Spectra strength/dynamic en Detail NR keys zijn expliciet legacy/import-only |
| Naam/productsemantiek | `liveViewfinderTuning` wordt werkelijk op capture preferences toegepast; foto-effect is bewezen, productbesluit blijft open |
| Naam/productsemantiek | `Mirror front preview` beïnvloedt ook saved photo; UI-subtitle benoemt dit al expliciet |
| Correct/opmerkelijk | disabled profile forceert YUV; enum/schema demosaic-default zijn beide Auto Hybrid; persisted Menon-slot migreert naar AMaZE |
| FPS | range wordt uit fysieke Camera2-capabilities gekozen; geen onafhankelijke FPS-key aangetroffen in de onderzochte repository-accessor |
| Nog onvolledig | stored device values, runtime effective values, verscheidene UI/schema-defaults, complete ranges en session-rebuild-hercontrole |

Geen semantische productkeuze als codebug gefixt. De audit is **PARTIAL**, niet afgerond voor alle single-frame controls.

## 8. Performance

`bncam_single_frame_qualification FIXTURE benchmark 10` houdt één native proces en Vulkan-runtime vast, verwerkt drie warm-ups per pad en tien gemeten runs per pad, en interleavet de drie paden met vaste random seed 20261007. Warm-ups worden niet meegerekend. De fixture wordt éénmaal geladen; normalisatie en gehele warm pipeline worden per iteratie apart gemeten. Native timingdetails worden per run geëxporteerd; diskexports gebeuren ná het gemeten interval.

Per run worden thermal status, RSS en native heap opgeslagen. SEVERE of hoger (`>=3`) markeert data als thermisch beïnvloed. Ontbrekende thermal-data geeft geen schone ranking. Bitexact JPEG-determinisme wordt binnen ieder pad over alle iteraties geëist. Camera-identiteit wordt uit de fixture-recipe/lens-id gelezen; de harness weigert een ontbrekend ID te gokken.

| Path | n gemeten | median | p90 | mean | SD | GPU kernel | GPU sync | total |
|---|---:|---|---|---|---|---|---|---|
| Malvar | 0 | — | — | — | — | — | — | — |
| AMaZE | 0 | — | — | — | — | — | — | — |
| Auto Hybrid | 0 | — | — | — | — | — | — | — |

Thermal state: **niet nieuw gemeten**. Geen performancevergelijking of ranking. Analyzer rekent min/median/mean/p90/max/sample-SD (ddof=1); p90 gebruikt lineaire interpolatie. Warm-upuitsluiting/statistiek/thermal/memory/rankinglogica zijn met expliciet synthetische analyzerdata gecontroleerd. Dat is geen devicebenchmark.

## 9. Performance waterfall

De analyzer scheidt disjuncte buitencategorieën: normalization, native core, post-core inclusief diagnostics, en coördinatie. Overige kernel/sync/stagevelden blijven geneste observaties en worden niet nogmaals bij walltime opgeteld. De bestaande `rawIspAccountedMs`/`rawIspUnattributedMs` worden behouden.

Een onverklaarde core/tail van minstens één seconde markeert `needsFurtherInstrumentation` en sluit rankingkwalificatie uit. Dat maakt een brede tail niet automatisch verklaard: dan moet verder worden geïnstrumenteerd.

**Er is nog geen gemeten waterfall.** Shader/resource creation, upload/download, allocaties, sync, conversies, CPU color/tone, encode, diagnostics/logging en I/O zijn dus nog niet voldoende met nieuwe data gekwantificeerd. Geen veilige performancefix toegepast vóór meetbewijs.

## 10. Memory

Geen nieuwe devicewaarden/trends. Native RSS komt uit `/proc/self/status`, native heap uit `mallinfo`; de analyzer bewaart per-pad vóór/na-reeksen en signaleert strikt monotone RSS-groei. Graphics/GPU-memory is expliciet UNAVAILABLE voor de standalone executable zolang geen bruikbare devicebron is bevestigd. Het ADB-capturescript exporteert daarnaast app `dumpsys meminfo`.

Geen uitspraak over leakvrij gedrag of memory-growth op device na de wijzigingen.

## 11. Gevonden observabilitybugs en wijzigingen

| Bug / bewijs | Root cause | Fix / regressie | Device-hercontrole |
|---|---|---|---|
| busy begin had geen uniek rejected ID | ID werd pas ná busy-check gemaakt | ID vóór guards; 100-trigger JVM-scope | open |
| UI/eventbus/hardware reject kon verdwijnen | Unit-event + losse UI-guards, emitresult niet geadministreerd | typed trigger, expliciete rejection/cancellation | open |
| camera-close verloor attempts | `forceReset` wiste attempts/map zonder terminal outcome | acquisition cancel; detached work behouden | open |
| async completion afhankelijk van managercollector | Activity teardown beëindigt manager-owned observatie | queue-owned terminalcallback, late snapshot reconciliation | JVM lifecycle groen; connected callbacktest open |
| queue rejection als generic failure | submission reject gebruikte algemene finalizerresult | expliciet `REJECTED` | open |
| cancellation behandeld als generic exception | router catch retourneerde null na cancellation | `CANCELLED` + rethrow | lokaal getest; device open |
| cancelled queued trigger kon later admission krijgen | delivery en UI-disposal hebben afzonderlijke lifetimes | terminal trigger kan geen attempt starten | JVM groen |

Het bewijs voor de oorspronkelijke gaten bestaat uit de bron-audit en de nieuwe deterministische regressiecontracten. Er is geen aparte opgeslagen pre-fix red execution van alle nieuwe tests. Geen beeldkwaliteitsbug of mathematische Hybridbug geclaimd/gefixt.

Twee source-contracttests zijn aangepast wegens de nieuwe typed shutterlambda/accounted bus. Hun oorspronkelijke lifecycle/hardware-contract blijft gecontroleerd. Er zijn geen baselinefailures blind groen gemaakt.

## 12. JVM debt

Finale volledige run: **1.959 tests, 355 failures**. `jvm-diff.json` vergelijkt de feitelijke failurenamen: **geen nieuwe failures, geen verdwenen baselinefailures**. Vijf nieuwe terminaltests zijn toegevoegd.

| Categorie | Resterende failures |
|---|---:|
| STALE_TEST | 1 |
| REAL_PRODUCT_BUG | 0 bewezen binnen deze failure-audit |
| OUT_OF_SCOPE | 25 |
| DUPLICATE_FAILURE | 0 bewezen |
| UNCERTAIN | 329 |

De bevestigde stale JVM-test verwacht nog `qualityForcesMenon`/`autoDetailMenon`, terwijl de stabiele resolver AMaZE-slotmapping en regionale Malvar/AMaZE-blend gebruikt. Deze brede test is geclassificeerd, niet opportunistisch herschreven.

`jvm-debt.json` bevat per failure categorie, reden, evidence, stack/assertionsite en groep. OUT_OF_SCOPE is conservatieve triage op het genoemde Neural/multi-frame/HDR/night-contract. Groepen zijn **assertionsitegroepen**, geen voorgewende bewezen gedeelde root causes. De 329 UNCERTAIN failures vereisen nog individueel contract/root-causeonderzoek; dit onderdeel is niet inhoudelijk afgerond. Nul bewezen productbugs betekent niet dat er geen bugs onder die onzekerheid zitten.

## 13. Build/test en regression gate

Een command:

```powershell
.\scripts\validateBnCamSingleFrame.ps1 -Serial AUWE025B03006422
```

Lokale subset:

```powershell
.\scripts\validateBnCamSingleFrame.ps1 -LocalOnly
```

De volledige gate bouwt, draait gerichte JVM-tests en relevante connected/native tests, voert de numerical/replay-harness en persistente benchmark uit, exporteert/analyseert data, start de app en doet echte camera-smoke/shutter-stress. Hij stopt met een concreet command/log bij failure; thermal/onverklaarde timing blokkeert performancekwalificatie. De fixture moet reeds bestaan op het toestel; er wordt geen synthetische fixture als echte RAW gepresenteerd.

De lokaal uitgevoerde subset: **56 tests, 0 failures**, APK/instrumentation en native harness gebouwd. `git diff --check`: exit 0. De volledige devicegate is nog niet gevalideerd; mogelijke integratiefouten in die branches blijven open. De vier oude connected baselinefailures zijn geen onderdeel van de gerichte gate; ze blijven zichtbaar in dit rapport.

Alle commando's, resultaten, initiële omgevingsfouten en gecorrigeerde native harness-buildfouten staan in `commands.json`. Detail in `work/qualification-*` en `work/single-frame-gate/`. `local-gate-results.json` bevat de 56 afzonderlijk gegroepeerde tests.

Handmatige scenes:

```powershell
python scripts/single_frame_qualification.py capture --serial AUWE025B03006422 --scene A_MALVAR_RAW_SENSOR_MAIN_01
```

Stel vooraf de bedoelde bestaande Single Frame Photo-profielcontrols in en leg fysieke setup/licht/afstand vast. Het script kiest of tunet deze niet. Scene-labels bewijzen de fysieke scene niet. F/H moeten met echte licht-/oriëntatieverandering worden uitgevoerd.

## 14. Werkelijk resterende roadmap

1. **Herstel ADB.** Installeer de huidige build en voer numerical/80-case native suite, held-queue-stress, late binding en echte shutter/lifecycle accounting uit. Bevestig de nieuwe evidence end-to-end en de gewijzigde demosaic connected verwachting. Onderzoek de vier connected baselinefailures afzonderlijk.
2. **Maak de Hybridbewijsset compleet.** Inspecteer de echte readback/oracle/determinisme-resultaten. Breid daarna resolutie, CFA, pariteiten, borders en fysieke noise/evidence-context uit waar de crop-fixture geen dekking levert. Alleen bewezen correctnessbugs fixen.
3. **Voer de fysieke scene/AF/AE-matrix uit.** Begin met de huidige beschikbare scene, daarna gecontroleerde A–H. Kwalificeer timeout/fallback/continuation zonder HAL-vervalsing en sensor/shutter-provenance rond echte lichtovergangen.
4. **Draai de warme benchmark op voldoende koel toestel.** Verifieer alle 10 samples/pad, thermal/bitexact/memory, verklaar de waterfall en instrumenteer eventueel resterende grote tails. Daarna pas eventuele verspillingfix en A/B-replay.
5. **Rond settings af.** Lees stored/effective devicewaarden, controleer resterende UI/schema-defaults/ranges en session-rebuildgedrag. Behandel naam/productkeuzes apart.
6. **Onderzoek de 329 onzekere JVM-failures per contract/root cause.** Bevestig echte duplicates/stale tests; fix uitsluitend reproduceerbare in-scope productbugs. Geen blind groenmaken.
7. **Finale regression/devicegate en diffcheck.** Pas na succesvolle nieuwe devicegegevens en bovenstaande open criteria kan deze fase werkelijk worden afgesloten. Beeldkwaliteitstuning blijft een latere fase.
