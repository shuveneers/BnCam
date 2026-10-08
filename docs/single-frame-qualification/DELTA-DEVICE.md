# Delta na hervatting met ADB — 8 oktober 2026

Dit overzicht vervangt de open device-status in REPORT.md. Er is doorgewerkt op dezelfde working tree en branch `main`, zonder reset, commit, push of beeldkwaliteitstuning. Baselinecommit: `5c3f7188a10f2a231f8192d9fe5b3a8f1d828b86`. Toestel: `AUWE025B03006422`, Honor BKQ-N49, Android 17 / API 37, arm64-v8a.

## De vier connected baselinefailures afzonderlijk

| Test | Bewezen oorzaak | Classificatie en wijziging |
|---|---|---|
| DemosaicNativeValidationTest | De native validator gaf al `allPassed=true`; de Kotlin-test vroeg oude bilinear/Menon-resolvervelden. De huidige ABI rapporteert `normalForcesMalvar` en `legacySlot2ForcesAmaze`. | **Stale test.** Asserties aangepast; device-uitvoering groen. Geen algoritmewijziging. |
| NoiseModelNativeConnectedTest | Afzonderlijk opnieuw gefaald met `provenanceValid=false/allPassed=false`. De 256×192-fixture wordt verdeeld in minimaal 12×9 tegels; bemonstering op stride 16 haalt de bestaande minimumdekking van 32 samples per tegel niet. De validator bevestigt nu expliciet 0 geldige kleine tegels en gebruikt dezelfde data, 4×4 herhaald, voor voldoende dekking. Die levert 108 geldige tegels, RAW-variantie 0,0001990630699 en chromaresiduvariantie 0,0003726467839. | **Fout in validatorfixture plus stale testasserties**, voor deze fout geen productbug aangetoond. `provenanceValid=true/allPassed=true` daadwerkelijk op device. De productdrempel, noiseformules en owners zijn ongewijzigd. Oude `offMultiplier/autoSamples/dynamic/noRegret`-asserties betroffen niet meer uitgegeven velden; nu wordt het huidige sampling/provenance/ownercontract gecontroleerd. |
| VulkanDeviceVerificationTest | Nieuwe run faalt precies op regel 51: `assertFalse(productionVulkanActive)`. `VulkanRuntime::snapshot()` registreert juist de aangesloten productiebackends. | **Stale test.** Verwacht nu actieve demosaic- en AWB/CCM-stages; identiteit, singleton-counts, extensies en exports blijven gecontroleerd. Device-uitvoering groen. |
| Phase4Block2ConnectedTest | Afzonderlijk opnieuw gefaald vóór de schermasserties: Espresso probeert via reflectie `android.hardware.input.InputManager.getInstance()` te vinden; die methode ontbreekt op dit toestel. Gradle dependencyInsight bevestigt werkelijk opgeloste `espresso-core:3.5.1`. | **Testinfra/device-compatibiliteit.** Geen productfix of blind bijgewerkte UI-asserties. Deze UI-test blijft rood en zijn schermasserties zijn niet gekwalificeerd. De gerichte devicegate sluit deze test expliciet uit; dat betekent niet dat de volledige connected suite groen is. |

Bewijs: `work/qualification-baseline-recheck.log`, `work/qualification-espresso-dependency.log`, `work/noise-model-device-proof.log` en de connected logs per gate-run.

## Gevonden productbug en fix

De eerste echte 100-triggerstress sloot exact, maar telde queue-full-afwijzingen als verwerkingfailure:

`100 = 7 PUBLISHED + 29 REJECTED + 64 FAILURE + 0 CANCELLED`.

Oorzaak: SingleFrameRunner gooide een IllegalStateException wanneer `tryReserve` geen capaciteit had. De router vertaalde die naar FAILURE. RAW en YUV gebruiken bij deze expliciete admission-afwijzing nu het bestaande `CaptureSubmissionResult.Rejected("processing_queue_full")`-pad. De bestaande `finally` behoudt de release van geselecteerde leases; geen verwerking of pixels zijn veranderd.

Na de fix, op het echte toestel in run 8:

`100 = 7 PUBLISHED + 93 REJECTED + 0 FAILURE + 0 CANCELLED`.

Van de 93 afwijzingen zijn 66 `submission_rejected:processing_queue_full` en 27 `shutter_dispatch_in_flight`. Router-admitted: 73; dat is geen processing-admissionteller. Alle 100 identiteiten zijn uniek en hebben precies één terminalrecord. Zie `capture-BACKPRESSURE.json`; de gate weigert voortaan deze queue-full-naar-FAILURE-misclassificatie.

## Nu bewezen groen

- **56 gerichte JVM-tests**, debug-APK, instrumentation-APK en native harness gebouwd. De volledige JVM-suite is ook herhaald: **1.959 tests, dezelfde 355 baselinefailures, geen nieuwe of verdwenen failures** (`device-followup-jvm-diff.json`).
- **Negen gerichte connected tests**, waaronder de gecorrigeerde Demosaic-, NoiseModel- en Vulkan-tests, physical chroma/luma, publication policy, RAW-preview recovery en twee backpressure/late-bindingtests.
- **Held production queue op device:** 3 reserveringen blijven vastgehouden, 97 van 100 triggers worden geweigerd en de 3 gehouden reserveringen eindigen expliciet geannuleerd: `100 = 0 + 97 + 0 + 3`. Geen dubbele terminalrecords. De late queue-bindingtest is eveneens groen. Dit is een gecontroleerde queue-test, geen claim over 100 werkelijk verwerkte foto's (`terminal-backpressure-device.json`).
- **80 native CPU-cases op device:** vier CFA-patronen × vier crop-pariteiten × vijf geometrieën; beide CPU-referentieroutes gecontroleerd op finite output, senselbehoud, determinisme en correct bewaarde snapshots. De aanvankelijke harnessassertie dat opeenvolgende CPU-results verschillende buffers moesten hebben was fout: bestaande CPU-referenties lenen bewust scratchgeheugen. De harness kloont nu alleen het te bewaren snapshot en bewijst behoud over een gewijzigde input en replay. Het productie-buffercontract is ongewijzigd.
- **Echte RAW_SENSOR-smokecapture:** één trigger, één gepubliceerde foto, één bijpassend performance/evidencerecord. De gebruiker heeft RAW_SENSOR via de bestaande UI geselecteerd.
- **qualification_evidence.jsonl end-to-end:** scoped koppeling met terminal-ID én monotone starttijd; volledige frozen recipe, gitrevision, RAW-hash/dimensies/CFA/crop/black/white, requested/effective exposure/ISO, sensor/shuttertimestamp, AE/AF/focuscontext, WB/noise/rotatie en JPEG-dimensies. Het script leest de gepubliceerde JPEG opnieuw via MediaStore en vergelijkt onafhankelijk SHA-256 (`evidence-checks-CURRENT_UNCONTROLLED.json` en `evidence-checks-BACKPRESSURE.json`). Alle gepubliceerde foto's in de scopes hebben evidence. RAW-SHA wordt binnen de bestaande native-bufferownership berekend; deze ronde exporteert die nieuwe RAW's niet voor een tweede onafhankelijke hashberekening. Ontbrekende HAL-waarden blijven null; bij AE-auto zijn bijvoorbeeld handmatige requested ISO/exposure niet ingevuld.

## Hybrid-mask op echte GPU

De test gebruikt een 257×193 crop van de bestaande echte 4096×3072 RAW_SENSOR-fixture, met alle cropborders en gedeeltelijke workgroups. Malvar en AMaZE draaien afzonderlijk als oracle-kandidaten; de validator leest ook de werkelijk residente Hybrid-uitvoer terug. Drie replays vergelijken alle 20 floats per pixel bitexact, inclusief mask, signalen, kandidaten, output en geschreven-coördinaten.

| Metriek | Gemeten |
|---|---:|
| Minimum gewicht | 0,0517537 |
| Maximum gewicht | 0,948246 |
| NaN / Inf | 0 / 0 |
| Gewichten buiten [0,1] | 0 |
| Ongeschreven/verkeerd geadresseerde pixels | 0 |
| Maximale fout M+A−1 | 8,56817×10⁻⁸ |
| Maximaal verschil afzonderlijke kandidaat | 0 |
| Maximale fout output tegenover M×Malvar+A×AMaZE | 1,88356×10⁻⁹ |
| Absolute tolerantie | 2×10⁻⁶ |
| Determinisme | 3/3 bitexact |
| Kandidaten / fallback | beschikbaar / none |

Scenepriors/scores/signalen/availability/fallback blijven exact gelijk bij replay. `work/single-frame-gate/fixture-directory.txt` wijst naar de actuele unieke exportmap met `hybrid-mask-evidence.f32`; samenvatting in `hybrid-numerical-device.json`. De volledige diagnostische readback is uitsluitend opt-in debug; de benchmark gebruikt die niet. Dit bewijst deze fixture/crop, geen fysieke scene-A–H-dekking of alle GPU-resoluties/CFA's.

## Warme benchmark en finale gate

**Finale gate (run 9, 8 oktober 2026): GESLAAGD**, inclusief 56 gerichte JVM-tests, negen connected tests, 80 native CPU-cases, Hybrid-GPU-readback, echte RAW_SENSOR-smoke, 100 shuttertriggers, onafhankelijke JPEG-hashcontrole en persistente warme benchmark. `git diff --check`: exit 0. Zie `work/qualification-device-run9.log` en `work/single-frame-gate/`.

**Volledige connected suite daarna afzonderlijk herhaald: 12 tests, 11 groen, 1 rood.** De enige failure blijft Phase4Block2ConnectedTest met dezelfde bewezen Espresso/InputManager/API-37-oorzaak. Deze is niet genegeerd of als groen geteld (`full-connected-device-results.json`, `work/qualification-final-full-connected.log`).

| Pad | n | min | mediaan | gemiddelde | p90 | max | steekproef-SD |
|---|---:|---:|---:|---:|---:|---:|---:|
| Auto Hybrid | 10 | 2650.11 | 2689.44 | 2690.77 | 2720.51 | 2723.87 | 24.51 |
| Malvar | 10 | 2632.08 | 2646.88 | 2656.89 | 2676.36 | 2722.46 | 26.74 |
| AMaZE | 10 | 2627.02 | 2673.07 | 2668.95 | 2689.52 | 2693.41 | 21.08 |

Alle tijden zijn milliseconden voor RAW-normalisatie plus native JPEG-pipeline, zonder fixturebestand lezen of bewijsbestanden schrijven. Dit is een processingbenchmark op dezelfde opgeslagen RAW, geen shutter-tot-publicatiebenchmark. Alle 30 meetruns: thermal-before=0, thermal-after=0, JPEG bitexact, geen ontbrekende thermische waarde, geen blokkerende (>1 seconde) onbekende timingrest. De negen warmups zijn uitgesloten. Deze kleine timingverschillen bewijzen geen kwaliteitsverschil en vormen geen tuningadvies.

| Pad | mediaan normalisatie | mediaan native core | mediaan post-core/diagnostiek | mediaan coördinatie | RSS na render min–max MiB |
|---|---:|---:|---:|---:|---:|
| Auto Hybrid | 26.38 | 2638.04 | 24.33 | 0.00 | 258.74–258.75 |
| Malvar | 27.30 | 2597.48 | 24.37 | 0.00 | 258.67–258.68 |
| AMaZE | 27.53 | 2620.45 | 24.40 | 0.00 | 258.42–258.42 |

De vier timingcategorieën zijn per sample disjunct en reconciliëren met totalMs; losse medianen hoeven niet exact op te tellen. Alle overige native stage-*Ms zijn geneste observaties en worden niet dubbel opgeteld. Volledige verdelingen, thermische arrays, native heap/RSS-reeksen, fixture-SHA-256 en native timings staan in `warm-benchmark-summary.json`. Geen permanente leakvrijheid bewezen uit deze korte reeks; graphics-memory is voor het standalone proces niet beschikbaar. Capture-meminfo is apart bewaard.

Finale shutterreeks: **100 = 7 PUBLISHED + 93 REJECTED + 0 FAILURE + 0 CANCELLED**. Iedere gepubliceerde foto heeft precies één bijpassend scoped evidencerecord en een onafhankelijk gecontroleerde MediaStore-JPEG-hash. Redenen: `{"shutter_dispatch_in_flight": 19, "submission_rejected:processing_queue_full": 74, "output_published:FULL_SUCCESS": 7}`.

Actuele exportmap: `work/single-frame-gate/fixture-e9ada040aef24114997eaf342e3b7086`. De gate gebruikt een unieke map; de bewaarde oude exports zijn niet overschreven.

Protocol: één persistent native proces en Vulkan-runtime; vaste RAW/recipe-identiteit, gerandomiseerde padvolgorde met seed 20261007; drie warmups en tien meetruns per pad; warmups uitgesloten. Camera-app gestopt na afgeronde terminal/evidencechecks. Voor de benchmark twee opeenvolgende status-0-observaties, maximaal vijf minuten cooldown. Thermische status wordt vóór én na elke render gemeten; iedere status >0 of ontbrekende meting verhindert kwalificatie. Er is geen thermal override, geforceerde GC of kwaliteitstuning gebruikt.

De vroege benchmark van run 2 had tien meetruns per pad en status 0 bij elke start, maar nog geen thermal-afterkolom. Run 6 na echte capturestress had status 1; die is **niet thermisch schoon**. De oude analyser liet status 1 door; de nieuwe gate weigert die expliciet. De eerdere groene tekst van run 6 is daarom geen finale kwalificatie. De oude metingen zijn behouden onder `work/single-frame-gate-run2` en `work/single-frame-gate-run6`.

De blokkade van run 8 bleek op 8 oktober een **exportpadfout**: bij een tweede `adb pull` naar een bestaande map komt de nieuwe fixture in een submap, terwijl de analyser de oude CSV bleef lezen. De werkelijk nieuwe CSV bevat wel thermal-after. Analyse van die bewaarde dataset geeft drie gekwalificeerde paden met overal 0 vóór én na de metingen, bitexact JPEG en geen blokkerende timingrest (`warm-benchmark-run8-actual.json`). De gate exporteert voortaan naar een unieke, nog niet bestaande map en is daarna volledig opnieuw gestart.

## Infrastructuurcorrecties tijdens daadwerkelijk uitvoeren

Elke reproduceerbare fout is gevolgd door opnieuw starten van dezelfde volledige devicegate; logs staan in `work/qualification-device-run2.log` tot en met de finale run, met snapshots `work/single-frame-gate-run*`.

- Connected-test cleanup verwijderde de app vóór camera-smoke. De gate zet nu `leaveApksInstalledAfterRun=true`, verifieert het held-queuebestand en installeert de debug-APK zo nodig met behoud van data. Eerdere runs hebben de app wel verwijderd; behoud van de settings van vóór die cleanup kan niet worden gegarandeerd. De gebruiker heeft de RAW_SENSOR-route daarna bevestigd.
- ADB exec-out transporteert hier een ontbrekend-bestandfout van `cat` als tekst met exit 0. Alleen dit exacte ontbrekend-bestandgeval wordt als leeg log behandeld; JSON-corruptie blijft een failure.
- Performance-metrics slaan IDs en dimensies als strings op. De analyser normaliseert die typen en filtert historische logs op de actuele triggerperiode.
- Een lege intent-string ging verloren bij ADB-shellargumenten; uitschakelen van scene-evidence gebruikt nu `--esn qualification_scene`.
- De broncontracttest voor YUV-release scande tot de eerste `return@withContext`, die nu vóór submit kan liggen bij admission-afwijzing. Hij begrenst nu de daadwerkelijke submitsectie; release-asserties zijn behouden.
- Thermal status 1 wordt niet meer als schoon geaccepteerd; before/afterstatus en cooldown worden bewaard.
- Herhaalde ADB-export leest geen vorige dataset meer: iedere gate-run gebruikt een unieke exportmap en bewaart het exacte pad in `fixture-directory.txt`.

## Nog fysieke input nodig

De nieuwe foto's zijn `CURRENT_UNCONTROLLED` en `BACKPRESSURE`. Ze bewijzen geen gecontroleerde kwaliteitsscène. Voor de bestaande A–H-matrix blijven fysieke onderwerpen, verlichting, afstand, beweging, focusovergangen en waar gevraagd echte licht-/oriëntatieveranderingen nodig. Dat omvat AF/AE-reacties bij echte sceneovergangen en beoordeling van details, chroma, ruis en highlights. Deze ronde heeft geen denoise/color/WB/tone/sharpness/Hybrid-thresholds gewijzigd.

Daarnaast blijven de eerder gerapporteerde gedeeltelijke settingsaudit, de 355 bestaande JVM-failures en de Compose/API-37-testinfra open. Die zijn geen fysieke-scèneblokkades en worden niet als opgelost gepresenteerd. Process-kill-journalrecovery en alle niet-uitgevoerde lifecycle-/GPU-geometrievarianten vallen evenmin onder de hierboven bewezen scopes. Kwaliteitstuning volgt pas na de daarvoor vereiste fysieke kwalificatie.
