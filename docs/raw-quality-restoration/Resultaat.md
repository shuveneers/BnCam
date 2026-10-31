# BnCam — Delta 04/05: herstel van kleurdetail

De wijzigingen staan direct in het lokale project. Delta 01 FIXED, Delta 02, Delta 03 en de master-RAW/DNG-route zijn behouden. Alleen Single Frame / Spectra OFF krijgt de nieuwe bescherming. Andere RAW-routes houden de oorspronkelijke filters; YUV is niet gewijzigd.

## Bewezen oorzaak

De oorspronkelijke 28%-proef begint met volledig RGB **na demosaic**, met een lijn van R−G=0,02 en constante Y=0,03. Het is dus geen meting van Bayer-reconstructie. Het 5×5-venster bevat vijf lijnpixels en twintig achtergrondpixels. De bestaande covariance-shrinkage ziet de ruimtelijke herhaling niet en gebruikt maximale mengsterkte 0,9: retentie = `0,1 + 0,9×5/25 = 0,28`. De fout ontstaat in de fysieke chroma-filter, niet in een verkeerd geschaalde referentie. Een profielprojectie die ook lek naar aangrenzende achtergrond meeneemt is circa 26,69%; dat is een andere maat dan de centrale lijnamplitude.

## Geaccepteerde correctie

Dezelfde 5×5-support wordt op herhaling onderzocht in horizontale, verticale en diagonale richting, op afstand 1 en waar passend 2. Chroma wordt met de bestaande fysieke covariance gewhitened. Een conservatieve Student/F-repeatabilitytest verlaagt alleen de bestaande correctie voor de chromacomponent met betrouwbaar coherent bewijs. Een zwakke consistente R−G-lijn schakelt dus niet tevens B−G-ruisonderdrukking uit.

Samenhangende luminantiestructuur is een conservatief veto: ook 2×2-Bayer-pariteiten worden gecontroleerd. Dit voorkomt dat Malvar-valsekleur rond een achromatische lijn als echt kleurdetail wordt gered. Bij ambiguïteit blijft de bestaande filter actief. Een ontbrekend fysiek model behoudt de bestaande bypass; ongeldige support behoudt de input. De buitenste twee pixels krijgen geen nieuwe bescherming wegens kunstmatige herhaling door randclamping.

De F-kwantielen komen van een nominale familie-foutkans van 0,1% voor twaalf componenttests onder onafhankelijke Gaussische ruis. Die onafhankelijkheid wordt **niet** geclaimd voor gedemosaicte pixels: aparte CFA-ruisproeven toetsen de werkelijke Malvar/AMaZE-correlatie. Welford-scatter voorkomt numerieke subtractie van vrijwel gelijke momenten.

Geen grotere kernel, verscherping, saturatieboost, ISO-tabel, lens-ID-keuze of extra volledige renderpass. CPU gebruikt een kleine lokale support; GPU leest dezelfde onveranderde support opnieuw en gebruikt geen volledige tijdelijke beeldbuffer. Een lazy shader-specialisatie houdt de extra logica uit de bestaande demosaic-, Spectra ON- en multi-frame-pipeline.

## Retentie per stage

Hieronder R−G, lijnbreedte 1, steeds relatief aan de **bypass van dezelfde stage**. Zo worden CCM-schaal en sRGB-transfer niet verward met detailverlies.

| Stage | Delta 04 | Delta 05 |
|---|---:|---:|
| Bekende RGB-input na demosaic | 100% | 100% |
| Fysieke chroma | 28,0% | 100% |
| Fysieke luma | 28,0% | 100% |
| Highlight-recovery, inactieve ongeclipte controle | 28,0% | 100% |
| AWB/CCM, geen echte HueSatMap | 28,0% | 100% |
| FLLF/PBR/gamut, gefuseerde werkelijke Vulkan-stage | 28,0% | 100% |
| Gekwantiseerde sRGB vóór JPEG | 29,4% | 100% |
| JPEG | 25,0% | 100% |

B−G-lijn: fysieke chroma 28%→100%, JPEG circa 35,7%→100%. Breedten 2 en 4: fysieke chroma circa 46% en 73%→100%. De bekende RGB-oracle gebruikt een niet-identieke, Y-behoudende test-CCM en exact getransporteerde covariance. De JPEG-fout tegenover de bypass neemt voor deze zes RGB-oracles met minstens 50% af, inclusief de onveranderde beeldranden. Er is geen algemene claim van byte-identiteit met de bypass aan die randen.

`Delta05_StageAmplitudes.csv` bevat de absolute amplitudes per kanaal/stage en ook afzonderlijke synthetische RAW10/RAW_SENSOR-normalisatie + Malvar-reconstructie. `Delta05_JpegErrors.csv` geeft afzonderlijke R/G/B-fouten. `work/detail05/stage-summary.json` bevat de toegevoegde amplitudefout versus bypass. FLLF, PBR en gamut zijn bewust als **gefuseerd** gerapporteerd: geen verzonnen afzonderlijke meetpunten. Bij de RGB-oracle ontbreekt originele CFA-clippingprovenance en wordt de bestaande ongeclipte referentie-ingang gebruikt; de echte RAW-replays toetsen afzonderlijk de strikte neutrale RAW-ingang.

## Ruis, textuur en artefacten

- 162 detailcombinaties per CPU/GPU: twee chromacomponenten, negen patronen waaronder diagonalen en fijne luminantietextuur, drie amplitudes en drie niveaus met zowel luma- als chromaruis. Ruisvrije coherente lijnen worden behouden; de criteria voor ruisende structuren en afzonderlijke kanaalfouten slagen. Volledig herstel van een werkelijk ruisende 1σ-lijn is **niet** bewezen; de bescherming blijft daar conservatief.
- IID-vlakvelden: maximale gemeten kanaal-MSE-ratio 1.001017 tegenover Delta 04. Vier seeds, drie sigma's en drie cross-correlaties.
- Werkelijke Malvar/AMaZE-reconstructie van synthetische CFA-ruis: maximale kanaal-MSE-ratio 1.012700 (ongeveer 1.27% variantie, minder dan 1% RMS). Twee CFA-patronen, drie ruisniveaus en vier seeds. De vooraf gehanteerde bovengrens is 2% extra kanaal-MSE.
- Achromatische dunne lijn, rand, checkerboard en sinus: geen extra valsekleurenergie in de geteste gevallen. De eerste kandidaat faalde op Malvar; het Bayer-pariteitsveto is juist vanwege dat aangetoonde probleem toegevoegd.
- Echte RAW_SENSOR-vlakke deur/muur-ROI's: hoogfrequente chroma-energie maximaal circa +0,68%. Dit is een sRGB-spatiale proxy, inclusief kwantisatie en eventuele textuur, geen zuivere sensorruismeting.
- Echte screen/fabric-ROI's: luma-HF vrijwel gelijk; kleur-HF bevat zowel detail als ruis. Geen grote algemene winst in natuurlijke foto's geclaimd. Er is geen gekalibreerde natuurlijke textuur-ground-truth beschikbaar. De synthetische weef-/lijnproeven bewijzen de beperkte detailclaim.

## CPU/GPU en apparaatvalidatie

Maximaal CPU/GPU RGB-verschil in de uitgebreidere identiteitstests: **6.068498e-06** lineaire eenheden. De expliciete tolerantie is `1/(32×255×12,92)` = circa 9,49e−6: minder dan 1/32 van één UNORM8-sRGB-codestap bij de maximale transferhelling. De aparte niet-identieke WB/CCM-tests houden hun strengere 2e−6-grens. Luminantie-identiteit houdt de bestaande float-tolerantie.

De vijf gevraagde instrumentatietests zijn op de shaderimplementatie uitgevoerd. De laatste volledige ronde had vier geslaagde tests en alleen de oude algemene 2e−6-identiteitsgrens als failure; daarna is de expliciete codestapgrens toegepast en de uitgebreide PhysicalChromaDeviceTest opnieuw geslaagd. Dit is een wijziging van de numerieke acceptatie, niet van de retentie-, ruis- of artefactcriteria. De APK met die expliciete testgrens is geïnstalleerd.

Gradle debug-APK, instrumentation-APK en elf gerichte neutral-color/shadow/tone/demosaic-JVM-tests slagen. De eerdere bredere baseline-selectie had 19 bestaande failures; de volledige historische JVM-suite is niet groen verklaard. `git diff --check` slaagt.

SPIR-V `64d8b64e173bc078eae5788e91c4e37f4ed3ab9662cd5fb49c45a3bd710a2a70`, 463808 bytes, is byte voor byte teruggevonden in de native library in de verpakte APK. Beide Vulkan-specialisaties gebruiken datzelfde module. Gemeten kernelmicrobenchmark: `gpuDetailBenchmark1024 baseline04Ms=6.338550 candidate05Ms=12.027968 deltaMs=5.689418`. Geen volledige extra beeldbuffer of volledige extra renderpass. Twee totale replayherhalingen zijn onvoldoende voor een statistisch gekwalificeerde snelheidsclaim.

## Same-RAW-praktijkcontrole

Twee opgeslagen fysieke bronnen, RAW_SENSOR en RAW10, elk Delta 04 versus Delta 05, alle drie demosaic-opties tweemaal: **24 bitexact reproduceerbare renders**. De gepaarde RAW-SHA256's zijn identiek, alle gerapporteerde thermal-statussen zijn 0. Demosaic bleef op de gevraagde GPU-optie, zonder fallback. Exposure-gains, FLLF-sterkte, kleurmatrixbron en de bestaande resident-bufferinschatting zijn per paar gelijk.

De RAW_SENSOR-fixture bevat zijn opgeslagen S/O en LSC. De oude RAW10-export bevat geen LSC-map; die ontbreekt daarom expliciet in de nieuwe vergelijking. S/O, WB en effectieve CCM zijn uit de bewaarde capture/native-metadata overgenomen. Beide varianten gebruiken exact dezelfde gereconstrueerde controls. Dit is geen pixelreproductie van de historische app-JPEG en geen verzonnen LSC-kalibratie. Er zijn geen nieuwe fysieke opnamen nodig geweest.

## Reproduceren

De bestaande `bncam_single_frame_qualification` heeft testacties erbij:

- `DIR detail-cpu`: RGB-detail, CFA-correlatie en valse kleur op CPU.
- `DIR colour-stages`: werkelijke Vulkan-stages plus JPEG op bekende RGB- en RAW-fixtures; schrijft stage-CSV's.
- `FIXTURE detail-replay baseline04`, `... production` of `... bypass`: hetzelfde RAW-bestand met vastgelegde testvariant. Overrides zijn thread-local, alleen debug en niet op te slaan in profielen.

Push executable én bijbehorende `libbncam.so` van dezelfde build. De normale `replay`/`numerical`/`benchmark`-acties blijven bestaan. Bewijslogs en alle oorspronkelijke fixtures blijven behouden in de werkmap van deze chat.

## Bestanden en ZIP's

Delta 04 bevat uitsluitend de acht behouden covariancebestanden uit de lokale vorige ronde. Delta 05 is cumulatief vanaf diezelfde lokale baseline, inclusief Delta 04, de nieuwe bescherming, referentietests en dit meetrapport. Geen APK, volledige repository, master-RAW of DNG in de ZIP's.

Gewijzigde of nieuwe codebestanden:

- `app/src/main/cpp/IspCore.cpp`
- `app/src/main/cpp/PhysicalChromaDenoise.h`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/tests/ChromaDemosaicCorrelationValidation.h`
- `app/src/main/cpp/tests/ChromaDetailValidation.h`
- `app/src/main/cpp/tests/ColorDetailStageValidation.h`
- `app/src/main/cpp/tests/PhysicalChromaValidation.h`
- `app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp`
- `app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h`
- `app/src/main/cpp/vulkan/shaders/physical_chroma_denoise.glsl`
- `app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp`
- `app/src/test/cpp/ChromaDetailValidationTest.cpp`
- `app/src/test/cpp/PhysicalChromaDenoiseTest.cpp`
- `app/src/test/cpp/SingleFrameQualification.cpp`

De volledige ZIP-paden en bestandshashes staan in `Delta05_Files.sha256`. Bestaande gebruikerswijzigingen in gedeelde bestanden, waaronder IspCore.cpp, zijn behouden; uitpakken is bedoeld over diezelfde lokale projectbaseline.
