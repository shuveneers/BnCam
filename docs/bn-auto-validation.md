# Bn Auto — implementatie en validatie

De latere fotografische afstelling en actuele kwaliteitsbeperkingen staan in [Bn Auto — fotografische afstelling](bn-auto-photographic-tuning.md).

Honor BKQ-N49, Android 17, serial `AUWE025B03006422`, 8 oktober 2026. Lokaal geïmplementeerd; geen commit/push. Bn Auto blijft experimenteel en Standard Auto blijft de standaardkeuze.

| Fase | Status | Gewijzigde bestanden |
| --- | --- | --- |
| 1: fysieke autoriteit | Gereed: eigen `BN_AUTO`-owner, AE OFF, priority OFF, fysieke grenzen en request/result-controle. Expliciete manual blijft voorrang houden. | `PhysicalSensorExposureAuthorityPolicy.kt`, `CaptureExposurePreferences.kt`, `BnCameraManager.kt` |
| 2: zelfstandige engine | Gereed: onafhankelijke manual seed, lineaire RAW-percentielen, vier CFA-clippingmetingen, ruimtelijke highlights, sensorradiantie/noise-model, beweging, flicker en begrensde feedback per lens/generation. | Nieuw: `BnAutoExposureEngine.kt`, `BnAutoRawMeter.kt`, `BnAutoExposureEngineTest.kt` |
| 3: capture en instellingen | Gereed: bestaande Near-ZSL-route plus één exact getagd RAW-still-frame voor lange exposure, previewherstel en bestaand JPEG/DNG-pad. Modus wordt per profiel opgeslagen. | `BnCameraManager.kt`, `CaptureRecipeFactory.kt`, `CaptureSettingsSchema.kt`, `LibpatcherProfileResolver.kt`, `ProfileCaptureExposureSettingsScreen.kt`, debugondersteuning in `DebugTestReceiver.kt`; nieuw: `BnAutoSensorDeviceTest.kt` |

Tijdens live validatie zijn twee fouten gericht hersteld: de warme producer mag een korte bewegingsbelichting niet opnieuw verlengen, en een korte stilstand bij het omkeren van beweging mag de bewegingsgrens niet onmiddellijk vrijgeven. Sensorruis telt alleen als beweging wanneer de RAW-verandering fysiek significant is. Bestaande native beeldverwerkingswijzigingen en de bestaande runner zijn behouden.

## Hardwarebewijs

Alle vier rechtstreeks toegankelijke fysieke camera's realiseren **125 ms / ISO 100**, in RAW10 én RAW_SENSOR, met exact gepaarde beeldtimestamp en metadata. Exposure/ISO-afwijking blijft binnen 2%. Werkelijke maximale sluitertijd: main 546 ms, ultra 30 s, tele 393 ms, front circa 213 ms. Dit zijn lensgrenzen, geen belofte dat iedere scène zo lang belicht moet worden.

De geïntegreerde Bn Auto-stillroute realiseert op main:

| Bron | Gevraagd | Werkelijk | ISO gevraagd/werkelijk | Frame duration |
| --- | --- | --- | --- | --- |
| RAW10 | 540.000000 ms | 539.999749 ms | 1557 / 1557 | 540.096533 ms |
| RAW_SENSOR | 540.000000 ms | 539.999749 ms | 1137 / 1137 | 540.096533 ms |

Beide JPEG-publicaties zijn pixelidentiek aan de geselecteerde replay. Beide DNG-payloads hebben exact dezelfde SHA-256 als het geselecteerde canonieke RAW-frame; DNG-exposure en ISO komen overeen met het echte sensorresultaat. Geen warm frame als vervanging voor de lange opname.

## Live scènevergelijking

| Scène | Standard Auto werkelijk | Bn Auto werkelijk | RAW-saturatie Standard → Bn |
| --- | --- | --- | --- |
| Normaal verlicht | 30 ms / ISO 1491 | 30 ms / ISO 676 | 0.00608% → 0.00126% |
| Donker, stabiel | 60 ms / ISO 18000 | 540 ms / ISO 1557, RAW10 | 0.00447% → 0% |
| Donker met lampen | 40 ms / ISO 1640 | 260 ms / ISO 102 | 0.27090% → 0.14620% |
| Zwaaiend onderwerp | 33.3 ms / ISO 2724 | 10 ms / ISO 8948, na correcties | Geen gecontroleerde clippingvergelijking door bewegend onderwerp |

De donkere Bn-hertest volgde na correcties; dit is geen gelijktijdige vergelijking. De normale en lampenscène tonen een donkerdere Bn-weergave en minder clipping. Bij lampen is de integratietijd 6.5× langer; dit betekent alleen bij gelijke lichtinval meer verzamelde fotonen. Een gekwantificeerde SNR-winst of bewegingsonscherptereductie is met deze beperkte, niet gesynchroniseerde scènes niet bewezen. De bewegingsopname bewijst de daadwerkelijke korte sluitertijd en bijpassende metadata.

De warme Bn-producer blijft op circa 33.3 ms frame duration (ongeveer 30 fps); de lange still onderbreekt repeating en herstelt dat daarna. Korte Near-ZSL-frameselectie kostte circa 10–31 ms. De kwaliteitscaptures hadden circa 9–21 s verwerking/publicatie inclusief kwalificatie-overhead; dit is geen zuivere productie-latencybenchmark. Telemetrie en capturetraces bewaren de afzonderlijke tijden.

## Tests en grenzen

- **PASS:** 32 gerichte tests, waaronder 16 Bn-tests; onafhankelijke bootstrap, daglicht, avond, lampen, tegenlicht, beweging, statief, ontbrekende metering, fysieke grenzen, flicker, authority en RAW-calibratie.
- **PASS:** laatste lokale single-frame-gate, 56 tests plus build en `git diff --check`.
- **PASS:** Manual-smoke met AE OFF; terugschakelen naar Standard Auto met AE ON en behoud na app-herstart. Toestel teruggezet op Standard Auto, RAW10 en JPEG.
- **PASS:** fysieke sensorprobe, acht RAW-exposures; bestaande connected suite (9 tests), native numerical checks, camera-smoke en exacte terminal accounting bij 100 triggers (6 gepubliceerd, 94 admission-rejects).
- **PASS:** alle geslaagde live captures publiceren één frame en leveren bitexacte replays voor de drie bestaande demosaic-modes. JPEG/DNG-controles hierboven zijn uitgevoerd op de lange opnamen.
- **FAIL:** de gevraagde volledige `validateBnCamSingleFrame.ps1 -Serial AUWE025B03006422` eindigt in de performancekwalificatie: thermische status stijgt van 0 naar 1; alle modes blijven bitexact, maar `rankingQualified=false`. Daarom geen volledige performancekwalificatie claimen.
- **Bestaande failures:** een bredere exposure-sourcecontractselectie heeft 14 failures die ook met de oorspronkelijke `BnCameraManager.kt` reproduceren. Niet als nieuwe regressies weggewerkt of als PASS gerapporteerd.

Bn Auto gebruikt nog een interpreteerbare heuristische radiantiedoelfunctie; universeel optimale scènehelderheid is niet bewezen. Bij ontbrekend betrouwbaar stabiliteitsbewijs geldt de conservatieve lensfallback. Voor de andere lenzen is hardwarevrijheid bewezen, geen uitgebreide Bn-kwaliteitsmatrix. YUV gebruikt de bestaande route.

## Bewijsbestanden

In de lokale, genegeerde map `work/`: `bn-auto-physical-lenses.txt`, `bn-auto-motion-hysteresis.log`, `bn-auto-final-targeted-tests.log`, `bn-auto-final-local-gate.log`, `bn-auto/live/scene-measurements.json`, `bn-auto/live/dark-fixed/dng-checks.json`, de `publication-replay-checks.json`-bestanden en alle captures/telemetrie. De afgekeurde volledige benchmark staat in `work/single-frame-gate/benchmark-summary.log`; eerdere gekwalificeerde baselinebestanden zijn geen bewijs dat deze nieuwe run slaagde.
