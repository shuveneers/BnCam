# BnCam Single-Frame V2 — Fase 6: Residual Confidence Protection

**Basis:** exact afgeronde fase-5-commit `701fcb0df6541d5ede0d0b451159934ae9a6928b`. Branch `deliverables/sf2-phase6`. `main` blijft ongewijzigd. Fase 7 valt buiten deze delta.

**Status:** code en synthetische native-kwaliteitsvalidatie compleet. Vulkan shader-compile CI geslaagd. Android APK, numerieke CPU/GPU-pariteit, toestelprestaties en fotografische acceptatie op HONOR Magic 8 Pro moeten apart worden vastgesteld. De gate wordt **niet** standaard geactiveerd.

## D06.1 — Deterministisch confidence-model

`SingleFrameResidualConfidence.h` is de CPU-referentie. `vulkan/shaders/physical_luma_confidence.glsl` bevat dezelfde berekeningen. Geen ML, nieuw noise-model, RAW-readback of extra devicebuffers.

De gate evalueert vóór toepassing van de bestaande kandidaatcorrectie de volgende **verschillende** aanwijzingen:

1. **Physical noise**: de reeds gepropageerde post-demosaic Y-variantie met de bestaande relatieve ruimtelijke sigma-shape, slechts eenmaal toegepast. Bij ongeldige fysieke metadata: exacte bypass.
2. **Demosaic-onzekerheid**: bij vaste Malvar/Bilinear-kernel is hoogstens een *berekende kernproxy* beschikbaar; ruimtelijke correlatie is niet gemeten. De heuristische bovengrens voor het gategewicht is daarom `0.92` (kernproxy) of `0.78` (onbekende adaptieve/neurale input). **Geen onbekende rho wordt ooit als nul gebruikt.**
3. **Local structure**: vier richtingen (horizontaal, verticaal, twee diagonalen) met elk drie parallelle tweedeverschilstencils. Een coherente dunne lijn geeft herhaalbaar signaal over de lijnrichting; willekeurige enkelpixelruis minder.
4. **Edge consistency**: dezelfde georiënteerde drie parallelle stencils meten ook het consistente eerste verschil aan weerszijden. De gate beschermt hierdoor harde randen zonder uitsluitend een drempel op één gradient te gebruiken.
5. **Residual significance**: een kandidaatcorrectie die onverwacht groot is ten opzichte van de bestaande fysieke Y-sigma wordt verder beperkt.
6. **Fine/coarse agreement**: tegengesteld gerichte fine- en coarse-correcties verlagen het gewicht vloeiend. Eensgezinde correcties worden niet versterkt.

De correlaties in deze stencils zijn onbekend en `varianceOfMean` is bewust een *onafhankelijkheidsproxy*, geen empirisch bewezen demosaic-covariantie. De richtingscoherentie en bovengrenzen zijn engineeringheuristieken; numerieke gategewichten zijn **geen gekalibreerde posteriorwaarschijnlijkheden**.

## D06.2 — Eén begrensde correctiegate

De bestaande Guided Fine+Coarse-bewerking blijft exact dezelfde kandidaat `ΔYfine + ΔYcoarse` leveren als fase 5.

```text
Youtput = YpostChroma + w(x,y) * (DeltaYfine + DeltaYcoarse)
0 <= w(x,y) <= 1
```

Het gewicht is een begrensd product van modelondersteuning, demosaic-onzekerheid, georiënteerde structuur/edge-coherentie, residual-significantie en overeenstemming tussen de twee niveaus. De afhankelijkheden zijn vloeiend in plaats van binair. Confidence uit de originele fysieke modellen is reeds verwerkt in de fine/coarse-correcties; alleen zwakke modelconfidence (onder 0,5) veroorzaakt extra begrenzing.

De gate leest uitsluitend de **ongewijzigde demosaiced RGB-guide**. Hij past één neutrale scalaire Y-delta toe aan de post-chroma RGB-kanalen. Daarom blijven `R-G` en `B-G` gelijk; er is geen nieuwe kleur-, contrast-, tone-mapping- of sharpeningstap.

Bij ongeldige sample-/noisegegevens of een niet-eindige kandidaat wordt de correctionele output exact de post-chroma baseline. Dit is **geen** verplichte bewerking op productiebeelden.

## D06.3 — Stages en Vulkan-routing

| Debugstage | Effect |
|---|---|
| 0 | OFF — chroma-only |
| 1 | LEGACY_REFERENCE |
| 2 | Guided Fine |
| 4 | Guided Fine+Coarse **zonder** extra gate |
| 5 | Dezelfde Guided Fine+Coarse **met** Residual Confidence Gate |

De nieuwe stage 5 wordt alleen via de bestaande debug JNI/benchmarkselector geactiveerd. Buiten debugbuilds accepteert de setter geen stagewijziging.

De GPU gebruikt dezelfde bestaande Vulkan-kleurpass, dezelfde optionele compacte fase-5 coarse-buffers en dezelfde 128-byte pushconstants. Een vrij bit van het mode-3 `residualStride` veld brengt alleen de *kernproxy-beschikbaar* provenance over. Er is **geen extra GPU-pass, CPU-readback, kopie van het volledige frame, descriptorbinding of full-resolution buffer** voor fase 6.

Als coarse-buffers niet beschikbaar zijn, keert de GPU terug naar de fase-4 fine-only-verwerking. De CPU-fallback gebruikt een reeds CPU-beschikbare immutable guide; hij forceert geen readback.

## D06.4 — Dezelfde RAW-master, vijf JPEG-varianten

De bestaande debug-only `physicalLumaAbDirectory` produceert vijf versies met identieke input/metadata en aparte `.txt` diagnostiek:

- `off.jpg`: chroma-only.
- `on.jpg`: legacy.
- `guided.jpg`: Guided Fine-only.
- `combined.jpg`: Guided Fine+Coarse zonder gate.
- `confidence.jpg`: dezelfde kandidaat met gate.

De `finally`-afhandeling herstelt stage 0. Normale opnames maken deze vijf extra renders niet.

## D06.5 — Bewijs en beperkingen

De native suite toetst:

- Gewichten begrensd tot `[0,1]`, determinisme, fysieke modelzekerheid, onbekende versus kernel-only demosaic-provenance.
- Coherente haren, zwakke lijnen, textiel, harde overgangen, egale vlakken, te grote correcties, tegengestelde fine/coarse-residuals.
- Exacte invalid-data bypass, geen NaN/Inf naar pixels en behoud van post-chroma kleurverschillen.
- Twee soorten synthetische detailtests: een *gecontroleerde kandidaatkorruptie* die de bijdrage van de gate zelf isoleert; én een aparte simulatie waarin de **echte fase-4-fine- en fase-5-coarse-referentie** eerst de kandidaten berekenen.
- Bescherming van alle eerdere fasen door de **20 ongewijzigde fase-5-tests uit een Git worktree op de exacte oude commit** opnieuw uit te voeren. Daarnaast de huidige fase-6 native tests plus een strikte fingerprint/source-isolation-test.
- Het werkelijke `spectra_demosaic_resident.comp` shaderbestand wordt inclusief de nieuwe confidence-code naar Vulkan 1.1 SPIR-V gecompileerd.

**Acceptatiegrens:** Een lagere fout in synthetische structuursimulaties vormt nog geen bewijs voor haar/gras/textiel op het toestel. De confidence-gate blijft een *optionele debug-variant* zolang CPU/GPU-numerieke pariteit, geheugen, GPU-latency en echte HONOR RAW-JPEG vergelijkingen geen meerwaarde aantonen. Zelfs bij positieve synthetische tests kan hij op vlakke gebieden nuttige ruisreductie terugschroeven; vergelijk daarom ook altijd flat-noise en huidtextuur. Voer geen validatieclaim uit op basis van alleen source checks of SPIR-V compile.

## Build/levering

De workflow `.github/workflows/sf2-phase6.yml` produceert:

- `BnCam_SF2_F6_Delta.zip`: uitsluitend gewijzigde bestanden plus `BnCam_SF2_F6_From_Phase5.patch`.
- Een volledige project-ZIP is beschikbaar via de phase-6 GitHub branch.
- De CI bevat een **best-effort Android debug-APK build** voor de definitieve fase-6-commit; alleen bij daadwerkelijke build-success wordt `BnCam_SF2_F6_Confidence_APK` als downloadbaar artifact gepubliceerd. Een geslaagde native CI betekent niet automatisch een geslaagde Android-build.

Lokale Android-build als CI-APK ontbreekt: `./gradlew :app:assembleDebug` met de door het project geëiste Android SDK/NDK/CMake/Gradle toolchain, daarna het testprotocol voor vijf-way same-RAW benchmark volgen.

**Volgende afzonderlijke fase:** fase 7, complete pipelineintegratie/kwalificatie. Niet opgenomen in deze fase-6-delta.
