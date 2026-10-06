# BnCam Phase 2 — LSC en demosaic quality baseline

**De twee productwijzigingen zijn uitgevoerd. De beeldkwaliteit is nog niet volledig geaccepteerd.** Geldige LSC-gains komen onbegrensd door; AMaZE is de standaard voor ontbrekende/nieuwe demosaic-instellingen. Expliciet opgeslagen Malvar blijft Malvar. AMaZE verbetert meerdere fijne structuren, maar verliest van Malvar op één nieuwe neutrale repetitieve test. De strenge kwaliteitssuite rapporteert dat als failure.

[100%-crops en vóór/na-stages](../build/phase2-raw-quality/index.html) · [Numerieke vergelijking](../build/phase2-raw-quality/metrics/real-comparisons.csv) · [Synthetische regressies](../build/phase2-raw-quality/metrics/synthetic-regression.json)

## LSC: gevonden en gecorrigeerd

De 3,5-clamp zat in Kotlin `LensShadingGrid` (nodes én interpolatie), de CPU-finalizer en twee GPU-LSC-shaders (`spectra_raw_finalize` en `neural_remaining_lsc`). De twee preview-shaders hadden bovendien een verborgen 16×-limiet. Alle zes implementaties gebruiken nu hetzelfde contract: eindige gains ≥1 blijven intact; NaN, Inf en waarden <1 worden identiteit. Dit volgt de [Camera2 LensShadingMap-specificatie](https://developer.android.com/reference/android/hardware/camera2/params/LensShadingMap#MINIMUM_GAIN_FACTOR). Er is geen lensafhankelijke grens toegevoegd.

Kotlin controleert kaartafmetingen zonder integer-overflow. De native GPU-request draagt nu ook het aantal beschikbare elementen; onvolledige kaarten worden afgewezen vóór upload/gebruiken. Geen upload- of opslagclamp gevonden. Bilineaire interpolatie en kanaaloriëntatie blijven gelijk. De bestaande scene-linear uitvoergrens 0…4 is behouden; die begrenst radiantie, niet metadata-gains, en raakt 0% van deze drie fixtures.

| Lens | Metadata min–max | Toegepast vóór | Toegepast na | Sensels geraakt door oude cap | Safety-mutaties nu |
|---|---:|---:|---:|---:|---:|
| Main | 1,00098–4,74121 | 1,00111–3,50000 | 1,00111–4,73504 | 7,42% | 0% nodes / sensels |
| Ultra | 1,00098–11,13184 | 1,00111–3,50000 | 1,00111–11,13184 | 50,13% | 0% nodes / sensels |
| Tele | 1,00098–2,42969 | 1,00101–2,42794 | 1,00101–2,42794 | 0% | 0% nodes / sensels |

Het verschil tussen node-max en toegepast max komt door interpolatie en de werkelijk bemonsterde CFA-posities. De **echte CPU-fallback versus GPU** is op alle senselcoördinaten getest: maximale gain-afwijking main 1,49e−6, ultra 6,71e−6, tele 7,45e−7. Aanvullende kaarten met gains tot 32, NaN/Inf/negatieve waarden en een onvolledige kaart slagen. De bekende constante invoer wordt met `gain` vermenigvuldigd, niet `gain²`: één software-LSC-toepassing. Vendor-shading vóór onze invoer blijft een apart Camera2-contract.

Centrumkleur en residualvariantie blijven bij dezelfde Malvar-route exact gelijk. Ultra linksboven stijgt de gemiddelde gain van 3,50 naar 8,98; gemiddelde lineaire RGB van (0,152; 0,124; 0,122) naar (0,380; 0,320; 0,333), residualvariantie van 0,000137 naar 0,000918. Main linksboven: gain 3,456→3,939 en variantie 0,000116→0,000148. Tele blijft gelijk. Dit is verwachte versterking van **scene-residuals**, geen bewijs dat alle residuals willekeurige ruis zijn. Alle centrum-/hoekcijfers staan in [LSC vóór/na](../build/phase2-raw-quality/metrics/lsc-before-after.json). Geen vlakveldtest of absolute kleurnauwkeurigheidsclaim.

## Productrouting

- **Malvar, ID 1:** dezelfde deterministische reconstructie; expliciete profielen blijven geldig.
- **AMaZE, ID 2:** klassieke quality-default voor ontbrekende/nieuwe instellingen in profielresolver, capture-schema en native metadata. Geen migratie die een expliciete Malvar-keuze overschrijft. Alle drie productiereplays melden `GPU_RESIDENT_INPUT_PRIMARY_AMAZE`; guide/reconstructie blijven resident.
- **BnC Neural, ID 3:** toekomstige onafhankelijke Bayer→RGB-route. Geen backend geïmplementeerd. Ook oude `NEURAL_JDD`, `Neural JDD`, `RCD` en `BILINEAR`-profielen migreren naar de bewaarde slotidentiteit. Telemetry: requested `BNC_NEURAL`, actual `MALVAR_2004`, fallback `true`, reason `BNC_NEURAL_BACKEND_UNAVAILABLE`.
- **Auto Hybrid, ID 0:** uitsluitend `MALVAR_2004,AMAZE`. Resolverpriors tellen op tot 1; BnC-prior is exact 0, zonder floor voor de ontbrekende route. Ook preview-Auto selecteert geen ontbrekende neural-route meer. De bestaande GPU-blend gebruikte al twee routes; resolver, propagatie en telemetry zijn daarmee gelijkgetrokken. De derde dataslot blijft beschikbaar voor een toekomstig echt model.

Het oude CPU-residualnetwerk blijft alleen als expliciet benoemde legacy-reference bestaan. Het wordt niet gepresenteerd als BnC Neural en is uit productfallbacks geweerd. Malvar- en AMaZE-kernels zijn niet aangepast; geen nieuwe cleanup, smoothing, saturatiereductie of sharpen-back.

## Reconstructie: winst én resterende failures

Opnieuw gemeten met echte GPU-uitvoer, bekende zero-chroma invoer en identieke mapping:

| Test | Chroma RMS Malvar → AMaZE | Luma-contrastretentie Malvar → AMaZE |
|---|---:|---:|
| 2px verticale/horizontale lijnen | 0,05303 → 0 | 1,00079 → 1,00000 |
| Frequentiesweep | 0,09931 → ≈0 | 0,93857 → 1,00000 |
| Diagonale rand | 0,01677 → 0,00690 | 1,00044 → 0,99920 |
| Repetitieve 2D-textuur, periode 8px | **0,01122 → 0,01357** | 1,05234 → 1,01676 |

De diagonale luma-gradientretentie blijft vrijwel gelijk: 0,86387→0,86295. De repetitieve textuur overschrijdt het nieuwe strenge kwaliteitsdoel van chroma RMS <0,005; `check_phase2.py` eindigt daarom met **één failure**. Die grens is een expliciet engineeringdoel, geen gemeten sensorruisgrens. De grens is na deze uitkomst niet versoepeld en het algoritme is niet gefilterd om groen te scoren.

Bij 1px horizontale/verticale lijnen houden beide routes slechts circa 0,707 luma-contrast over met chroma RMS 0,3. Het checkerboard op CFA-Nyquist verliest alle luma-contrast en heeft chroma RMS 0,6. Dit zijn vastgelegde reconstructiefailures in ambigue invoer; ze tellen **niet** als succesvolle detailpreservatie. Gemeten CFA-sensels blijven daarentegen in alle geteste routes en echte golden-renders exact behouden binnen de uitgesloten 8px buitenrand. De neural-fallback is byte-identiek aan Malvar.

De suite meet alternating chroma, opponent edge-residual, cyan/magenta high-pass, tekenwisselingen, luminantiegradienten, hoge-frequentie-energie en truth-retentie. Meetfilters worden uitsluitend voor statistiek gebruikt en wijzigen nooit renderpixels. De oude low-frequency `phase16FalseColorRiskEvidence` is niet tot algemene edge-detector hernoemd.

## Echte crops en FLLF

Dezelfde drie Phase 1-RAW's, sensorcoördinaten, capture-WB/CCM en fysieke correctie-instellingen zijn gebruikt. De metadatafloat-export blijft afgerond op vijf decimalen; dit is geen claim van bit-exacte oorspronkelijke Camera2-floats. Alle 39 oorspronkelijke RAW/JPEG/fixture-hashes zijn behouden. Een extra opgeslagen main-RAW10 is met beide routes ontwikkeld en bewaart gemeten sensels exact; geen RAW10-versus-RAW_SENSOR ruisrangorde geclaimd.

- Main `branch_sky`: alternating-chroma-proxy 0,01920→0,01744; luma-gradient 0,05143→0,05231. Minder fijne kleurafwisseling zonder gemeten detaildaling, maar zichtbare restartefacten.
- Main `foliage`: proxy 0,02060→0,02125; **geen uniforme verbetering**. `car_diagonal` verbetert evenmin op iedere chromaproxy.
- Tele takken/foliage/dakranden: lagere proxies met behouden of licht hogere luminantiegradienten. Bijvoorbeeld takken 0,002370→0,001993, gradient 0,01173→0,01179.
- Ultra-hoeken worden duidelijk lichter door correcte metadata-gains. Vergelijkingen gebruiken dezelfde displaymapping: deze helderheidsverandering is niet weggeschaald. De zichtbare fijne structuur blijft imperfect.

**FLLF is niet gewijzigd.** Op main takken is de lokale chromavariantieversterking CCM→FLLF 1,782× vóór en 1,679× na. Foliage: 1,746×→1,233×. Toch verandert de uiteindelijke foliage-chromavariantie vrijwel niet (0,001596→0,001599). Op takken daalt die ongeveer 11,4%. Dit zijn scene-proxies, geen percentage “opgeloste beeldkwaliteit”. [Alle vóór/na-stages](../build/phase2-raw-quality/metrics/stage-before-after.json).

## Validatie en grens van deze oplevering

- Debug-APK gebouwd; **14 gerichte unit-tests** geslaagd, plus **40 AMaZE-sourcecontractchecks** en de echte native/GPU gain-/routing-/sampletests.
- Volledige unit-suite: 1953 tests, **355 failures**. Een geïsoleerde reconstructie van de Phase 1-werkboom geeft 1951 tests en **dezelfde 355 failures**, zonder nieuwe failing testidentiteiten. [Baselinevergelijking](../build/phase2-raw-quality/metrics/unit-baseline-comparison-final.json).
- Strenge synthetische beeldkwaliteitssuite: **niet volledig groen**, wegens de repetitieve 2D-textuur. De kernwinst op 2px, sweep en diagonaal is wel bevestigd.
- Geen wijzigingen aan fysieke luma/chroma-filters, FLLF, KHRONOS, AWB, CCM, highlight-reconstructie, sharpening, YUV of fusion-algoritmen. Gemeenschappelijke LSC-consumenten en demosaic-keuzeplumbing ontvangen wel de gevraagde fix. De bestaande inputafhankelijke fysieke verwerking krijgt daardoor andere correcte invoer, zonder aangepaste filtercoëfficiënten.
- APK niet geïnstalleerd; geen push. Geen verdere reconstruction- of Phase 3-filterfix uitgevoerd.

**Conclusie:** de LSC-fix en eerlijke productrouting zijn klaar. AMaZE is een aantoonbaar betere quality-baseline voor verschillende structuren, maar geen algemene oplossing voor false colour. De visuele eindacceptatie blijft open; vooral repetitieve structuur, foliage en latere versterking verdienen gericht vervolgwerk.

Reproduceren vanuit de repo met de bestaande Python-dependencies op `PYTHONPATH`: `prepare_phase2.py`, een debugbuild, `run_phase2.py`, daarna `check_phase2.py` (verwachte huidige kwaliteitsfailure). Voor productiestages: `prepare_stages.py <root> <capture-id>`, `run_stages.py <root> <capture-id> --mode 2`, `stage_report.py`, `report_phase2.py`. `probe_cpu_lsc.py` voegt alleen aan de gegenereerde diagnostische bronkopie een CPU-meetfunctie toe. `snapshot/` bevat de oorspronkelijke werkboom; `changes/`, logs en `metrics/` bewaren de bewijsvoering.
