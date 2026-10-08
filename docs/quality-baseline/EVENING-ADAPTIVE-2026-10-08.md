# Adaptive Evening Exposure & Natural Night Rendering — 2026-10-08

## Besluit

**REVERT voor de ene nieuwe toonproef.** De proef is werkelijk in de bestaande GPU-toonstage geïmplementeerd, gebouwd en op de Honor getest. Hij maakt de twee lichte avondbeelden iets donkerder, maar maakt het al donkere derde beeld ook donkerder. Op 100% is geen overtuigende onafhankelijke kwaliteitswinst aangetoond. Geen RAW-denoise gewijzigd.

Candidate1, highlightvariant A en de geaccepteerde natural-tonal schaduwcorrectie blijven exact behouden. Alle 636 productiebronbestanden zijn byte-identiek aan de baseline van deze opdracht. De herbouwde native library én APK zijn byte-identiek aan de eerder geaccepteerde build. De telefoon-APK is via `pm path` en `sha256sum` gecontroleerd en heeft dezelfde SHA256: `36740dacac3ecb6cdfc0ab7bf1e7e655011a40cbdb9956a3269eb99bb33f8e4d`. Geen proef-APK geïnstalleerd.

## Daadwerkelijke AUTO-keten

`PhysicalSensorExposureAuthorityPolicy.resolve(false,false)` kiest `CAMERA2_HAL_AE`. `BnCameraManager.applyExposurePolicy()` schrijft in Standard Auto AE ON, priority OFF en null voor sensitivity, exposure time en frame duration; vervolgens keert hij terug. `shouldRequestDefaultRawShutterMotionAnalysis()` retourneert false. De verderop aanwezige oude API36/sluitertijdprioriteit en manual-fallback code is dus **niet de actieve Standard-Auto-belichtingslus**.

`CameraMeteringPolicy` herstelt voor Auto de Camera2-template-meetregio. `applyMeteringPolicy()` past logical/physical coördinaten afzonderlijk toe. Een tik kan in Auto tijdelijk een lokale AE-regio kiezen; bij expliciet gekozen andere metering blijft de tik AF-only. De camera rapporteert voor camera 2 maximaal één logical AE-regio in de bewaarde snapshot. BnCam heeft in Standard Auto geen actieve previewhistogram-naar-EV-controller en geen verborgen automatische EV-compensatie. De previewhistogrammen zijn waarneming/diagnostiek.

De opgeslagen telefoontelemetrie vermeldt `standardAuto=true;owner=CAMERA2_HAL;defaultRawPriority=false`, Auto/default-regio en geen touch override. **Die snapshot dateert van 10:48, niet van de drie avondshots om 21:06–21:07.** Een uitleesverzoek levert hetzelfde oude bestand op; dit telt niet als nieuwe of tijdgesynchroniseerde previewreeks. De drie capture-performance records bewaren de finale ISO/exposure en native rendering, maar geen complete request/result/previewtijdlijn of AE-regiohistorie. De bewaarde logcat bevat daarvoor evenmin een bruikbare Camera2-sequentie. Het display-ambient-luxlog is geen gekalibreerde camerameting.

De drie ISO×tijd-producten tonen een verschil van circa 2.7–2.8 stops wanneer de lampen prominent in beeld komen. Dat is een aanwijzing voor metering/compositiegevoeligheid, geen bewijs voor een HAL-fout, een tap-effect of een specifieke fout in BnCam. **AE is niet aangepast.**

## Gecontroleerde sequenties

Nieuwe `EveningMeteringSequenceTest` speelt lamp binnenkomen/verplaatsen/verdwijnen, tijdelijke tapregio/herstel, verouderde meetdata/generatiewissel en oude referentiecontinuïteit door de bestaande pure policies. De actieve Standard-Auto-route houdt HAL-ownership en default-regio ongeacht lampstatistiek. Dit bewijst de applicatie-authority en regioselectie; het simuleert niet Honor's interne AE-algoritme.

Een afzonderlijke controle van de **inactieve** oude median/clip-policy toont dat een klein lampoppervlak de mediane luminantie ongemoeid kan laten terwijl de oude clippingconstraint bij 0.5% begint. Die constraint wordt niet als oorzaak van de actieve avondshots aangewezen en is niet gewijzigd.

31 functionele/control-sequentietests slagen. Een bredere bestaande testselectie had daarnaast 9 fouten: 7 sourcecontract-asserties en 2 gedragstests van de oude manual-fallback policy. Alle daarbij betrokken productie-Java/Kotlinbestanden zijn exact ongewijzigd ten opzichte van de opdrachtbaseline. De fouten zijn bewaard in `build-tests.log`, `active-auto-tests.log` en XML; de volledige suite wordt niet groen genoemd en er zijn geen AE-wijzigingen gemaakt om deze verwachtingen te laten slagen.

## Eén afgebakende toonproef

Uitsluitend `applyBroadShadowPlacement()` kreeg een extra factor:

`noiseGuard = Y² / (Y² + 256 × sigmaY²)`

`sigmaY` kwam uit de reeds aanwezige gepropageerde scene-lineaire physical-noise-authority. Deze continue SNR-knee beperkte alleen de aanvullende positieve schaduwlift. Geen ISO/EV-tabel, scene-key/grijsnormalisatie, nieuwe lift, blur, denoise, kleurmatrixwijziging of tweede mapper. Signed FLLF, LOG en Khronos bleven intact. Bij ontbrekend sigma bleef de geaccepteerde mapping identiek, boven Y=0.5 was de proef identiek. De dimensieloze knee 16 was een prototypekeuze, geen als bewezen aangenomen perceptuele grens.

Een dichte numerieke responscontrole over negen sigmawaarden bevestigt positieve/monotone eindrespons en eindige waarden. Dat is onvoldoende voor acceptatie: de fysieke noise-authority vertelt hoeveel ruis een lift zichtbaar maakt, maar **niet of de opname volgens de gewenste avondweergave te licht of te donker is**. Dezelfde beperking van positieve lift treft daarom ook de derde opname en andere donkere scènes.

## Same-RAW OLD/NEW op Honor

S1–S3 en A1–A3/B1–B3/D1–D3, elk Auto Hybrid/Malvar/AMaZE, twee herhalingen: 72 nieuwe renders en 36/36 byte-identieke herhaalparen. De 72 JPEGs blijven 4:4:4, zonder demosaic fallback. OLD is de accepted Candidate1 + highlight A + natural-tonal baseline; de vorige expliciete EV-richtingproef is niet als OLD gebruikt.

S1–S3 gebruiken de bewaarde DNG-sensels met gereconstrueerde replaycontext. Zoals eerder vastgelegd: de DNG bewaart drie noise-kanalen, niet afzonderlijke groene noise-coëfficiënten of AF-hints. OLD/NEW hebben onderling identieke input, maar OLD wijkt gemiddeld circa 1.68/1.25/1.15 RGB-codes af van de oorspronkelijke telefoon-JPEG. Voor A/B/D is de oorspronkelijke frozen fixturecontext behouden. Alle fixture-RAW-hashes zijn geverifieerd.

Onderstaande waarden zijn full-frame gecodeerde Rec.709-Y in 8-bit-codes, Auto Hybrid; geen claim van fysiek gemeten scenehelderheid of ruisherstel. Alle drie paden staan in `comparison.json`.

| Frame | Mediane Y OLD → proef | Gemiddelde RGB-verandering | Componenten op JPEG-code 255 OLD → proef |
|---|---:|---:|---:|
| S1 | 47.80 → 44.81 | -1.99 | 1038 → 1024 |
| S2 | 58.88 → 54.57 | -2.83 | 8 → 8 |
| S3 | 32.04 → 30.90 | -1.27 | 67 → 79 |
| A1 | 40.18 → 38.13 | -1.42 | 0 → 0 |
| A2 | 36.31 → 34.86 | -1.18 | 0 → 0 |
| A3 | 36.13 → 34.69 | -1.17 | 0 → 0 |
| B1 | 25.11 → 24.50 | -1.21 | 0 → 0 |
| B2 | 25.24 → 24.63 | -1.25 | 0 → 0 |
| B3 | 25.25 → 24.63 | -1.22 | 0 → 0 |
| D1 | 128.11 → 127.55 | -0.85 | 0 → 0 |
| D2 | 122.35 → 121.70 | -1.06 | 0 → 0 |
| D3 | 121.86 → 121.08 | -1.15 | 0 → 0 |

De marginale toename van 255-componenten in S3 ondanks minder scene-lineaire lift komt uit het finale JPEG-resultaat; deze telling bewijst geen nieuwe sensorclipping. Er is geen goedgekeurde proef waarvoor dit als veilige highlightregressie wordt weggewuifd. De geaccepteerde APK is volledig hersteld.

### Werkelijke OLD/NEW-beelden — proef REVERT

![S1 OLD links / verworpen proef rechts](../../work/evening-adaptive/S1-overview.jpg)

![S3 OLD links / verworpen proef rechts](../../work/evening-adaptive/S3-overview.jpg)

![S2 fijne details, 100%](../../work/evening-adaptive/crops/S2/0/detail.png)

![D1 tekst, 100%](../../work/evening-adaptive/crops/D1/0/text.png)

Alle vaste 100%-crops: `work/evening-adaptive/crops/<frame>/<mode>/`. Er is geen ruimtelijke blur in de proef, maar donkerder weergegeven detail wordt minder leesbaar; daarom is onveranderd filterdetail niet gelijk aan een geslaagde visuele verbetering. Ruis is niet opgelost of als opgelost geclaimd.

## Precies ontbrekende informatie

Voor een verantwoorde AE-ingreep is nodig:

1. Een tijdgesynchroniseerde previewreeks met sensor timestamp/frame number en request epoch; requested én result AE mode/state, exposure time, ISO, post-RAW boost, exposure compensation, crop/zoom en actual/requested AE-regio's. Ook tap/resetmomenten en het tijdstip van geselecteerde ZSL-frame moeten daarin terugkomen.
2. Voor diezelfde frames een scene-lineair ruimtelijk luminantie-/clippingraster, inclusief lampoppervlak/positie en een stabiel gevolgd achtergrondgebied. De huidige drie finale histogrammen vertellen niet of de lamp, een tik, AE-convergentie of gewijzigde beeldinhoud de referentie verschoof.
3. Een gecontroleerde vaste-scène-reeks met direct zicht op de lamp tijdelijk afgeschermd en weer vrij, **zonder de kamerbelichting zelf te veranderen**, gevolgd door een aparte wall-tap/lamp-tap/Auto-resetreeks. Elke toestand moet AE laten uitconvergeren. Zo worden emitterinvloed en tap/regio-invloed van elkaar gescheiden. Deze fysieke sequenties zijn niet in de bewaarde gegevens aanwezig; de policy-replay wordt er niet voor uitgegeven.

Voor automatische avondtonaliteit is bovendien een onafhankelijke referentie voor gewenste donkere presentatie nodig: bijvoorbeeld een gevalideerde gekalibreerde scènehelderheidsreferentie samen met stabiele metering en expliciete beeldintentie, getoetst tegen donkere onderwerpen bij normaal licht. ISO, sluitertijd, WB, noise sigma of RAW-P50 afzonderlijk identificeren die intentie niet. De drie gewenste correctierichtingen vormen geen betrouwbare algemene beslisgrens.

Tot die observatie er is blijven de bewezen correcties behouden en wordt geen automatische nachtclassifier of nieuwe schaduwboost ingevoerd.

## Build en telefoon

`assembleDebug` geslaagd na exact herstel en expliciete shader-hercompilatie. Native library byte-identiek aan de accepted baseline; zelfstandig hercompileerde baseline-SPIR-V byte-identiek aan de gebouwde shader. APK-hash lokaal en op Honor gelijk. Geen nieuwe installatie nodig of uitgevoerd, geen nieuwe fysieke captures gemaakt. De nieuwe controletest en dit verslag blijven beschikbaar; alle productiecode is de geaccepteerde baseline.
