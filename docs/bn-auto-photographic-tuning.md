# Bn Auto — fotografische afstelling

8 oktober 2026, Honor BKQ-N49. Alleen de bestaande `BnAutoExposureEngine.kt`, `BnAutoRawMeter.kt` en `BnAutoExposureEngineTest.kt` zijn voor deze afstelling aangepast. Sensorautoriteit, beide RAW-formaten, Near-ZSL, long-exposure still, metadata, JPEG/DNG en alle beeldverwerking zijn behouden. Standard Auto blijft standaard.

## Keuzes en reden

**Helderheid.** De eerdere verschillen van −1,14 EV (normaal) en −1,31 EV (lampen) kwamen hoofdzakelijk uit de doelwet `0.6 × sqrt(sensorRadiance)`, niet uit gebrek aan RAW-headroom. Bij de oude Bn-opnamen was het 98e percentiel van het helderste CFA-kanaal ongeveer 0,105 respectievelijk 0,047 van het fysieke bereik. De lampen zelf mogen verzadigen zonder de hele kamer donker te maken. De zeer terughoudende diffuse helderheid was daarmee onvoldoende gerechtvaardigd. De coëfficiënt is 1,0 geworden: maximaal circa +0,74 EV vóór de overige constraints. De radiantieafhankelijkheid, donkere ondergrens en avondweergave blijven; geen HAL-doel of vaste middengrijswaarde. Het helderste diffuse CFA-kanaal begrenst de verhoging afzonderlijk. RAW-percentielen gebruiken nu de resolutie van de vier-site gemiddelden.

**Bewegingsveiligheid.** Weinig verandering in het grof verspreide raster is voortaan alleen een kandidaat voor stabiliteit. Lange exposure vereist daarnaast actuele, noise-gewogen detailgradiënten in beide richtingen, voldoende ruimtelijke dekking, minstens 750 ms observatie en een grens met 99% meetonzekerheid binnen een budget van 0,75 sensorpixel. Symmetrische buurmetingen voorkomen dat gedeelde pixelruis drift suggereert. Ontbrekende/zwakke informatie, vlakke scènes, eenzijdige textuur en verlies van metering autoriseren geen lange shutter. De bestaande bewegingshoudperiode blijft. Zonder positief bewijs geldt hoogstens de lensfallback; geen vaste 1/30-regel.

**Fotonen versus ISO.** Binnen de veilige tijd worden de lange en conservatieve allocatie vergeleken met het bestaande `S×signal+O`-model. De lokale projectie schaalt shot-noise met gain en read-variance met gain², inclusief kwantisatie en werkelijk realiseerbaar exposure-product. Lange exposure vereist minstens 10% voorspelde SNR-winst. Beweging, clipping, flicker en hardwaregrenzen blijven onafhankelijk begrenzen. Dit is een interpreteerbare lokale projectie, geen nieuw gekalibreerde ISO/read-noise-curve.

## Kleine live vergelijking

Zelfde kader en verlichting; zes opnamen totaal inclusief twee tussentijdse afstelcontroles. De tabel bevat de oude referenties en de definitieve build. Werkelijke CaptureResults:

| Keuze | Shutter / ISO | RAW-saturatie | Gemiddelde JPEG-luma |
| --- | --- | --- | --- |
| Standard Auto | 50 ms / 2951 | 0,01072% | 0,2731 |
| Oude Bn Auto | 340 ms / 102 | 0,00149% | 0,1320 |
| Nieuwe Bn Auto, RAW10 | 39,999967 ms / 1377 | 0,00158% | 0,1646 |
| Nieuwe Bn Auto, RAW_SENSOR | 39,999967 ms / 1377 | 0,00144% | 0,1648 |

Nieuwe Bn kiest hier **+0,67 EV exposure-product** tegenover oude Bn; de JPEG-schaduwen worden zichtbaar lichter en de warme, donkere scèneweergave blijft. Vergeleken met Standard blijft de keuze circa 1,42 EV lager. Dat is een conservatieve scènekeuze, geen bewijs van universeel optimale helderheid.

In een positief gemeten live venster liet dezelfde definitieve build **540 ms / ISO 102** toe, met een detailveiligheidsgrens van circa 3,53 s en voorspelde schaduw-SNR **3,01 → 10,01 (3,32×)**. Op beide shutters faalde de actuele lange-baseline-detailtoets; de foto's gebruikten daarom 40 ms. De 540 ms is in deze ronde een gecontroleerde enginebeslissing, geen opnieuw gepubliceerde 540 ms-foto. De eerder bewezen fysieke 540 ms-route is behouden; tests laten ook langere tijden toe waar sensor en positief bewijs dat ondersteunen.

De RAW-ruisproxy (MAD van diagonale groene CFA-verschillen in dezelfde 535 gezamenlijk geselecteerde rustige tegels) stijgt van **0,00109 naar 0,00437** van het sensorbereik; signaal/ruisproxy daalt van circa **23 naar 8,6**. De korte fallback verzamelt 8,5× minder fotonen dan 340 ms. Dit is een echte veiligheidsafweging, geen gemeten ruiswinst. De proxy bevat resttextuur en is geen temporele noise-calibratie. Autofocus veranderde bovendien tussen de oude app met AF-lock (2,44 dioptrie) en de nieuwe app (circa 4,1): de zichtbaar scherpere voorgrond bewijst daarom geen afzonderlijke shutterwinst. De engine wijzigt AF/AWB/OIS niet.

## Verificatie

- **PASS:** 39 gerichte tests, waaronder 23 Bn-tests; nieuwe dekking voor zwakke/missende informatie, twee-assige textuur, subpixel-drift, verlies van stabiliteit, SNR-afweging en identieke RAW10/RAW_SENSOR-metering en gradiënten.
- **PASS:** lokale single-frame-gate, 56 tests, builds en `git diff --check`.
- **PASS:** beide eindopnamen realiseren exact de gevraagde ISO; exposure-afwijking 33 ns. JPEG-publicatie is pixelidentiek aan de geselecteerde replay, drie bestaande demosaic-modes blijven bitexact. DNG-checks vergelijken de canonieke RAW-SHA en echte exposure/ISO.
- **PASS:** Manual AE OFF, Standard Auto AE ON en behoud na herstart. Het toestel staat weer op Standard Auto, RAW10 en JPEG. Geen commit/push; geen nieuwe claim over de eerder thermisch afgekeurde volledige performancegate.

Bewijs staat lokaal in `work/bn-auto-tuning/`: `comparison.json`, `existing-raw-meter.json`, `unit-tests.log`, `unit-summary.json`, `local-gate.log`, `publication-checks.log`, `live/final/dng-checks.json` en de originele captures/telemetrie. Ruis- en scherpteresultaten rechtvaardigen nog geen standaardactivering van Bn Auto.
