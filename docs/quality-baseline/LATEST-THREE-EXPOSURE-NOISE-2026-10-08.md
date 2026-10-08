# Laatste drie avondopnamen — belichting en ruis, 2026-10-08

## Status

Diagnose en afgebakende RAW-proef voltooid. Geen productiecode, profielinstellingen of foto's op de telefoon gewijzigd; geen captures gemaakt en geen nieuwe APK geïnstalleerd. De laatst geïnstalleerde natural-tonal build blijft behouden, inclusief Candidate1, highlightvariant A en de eerder geaccepteerde schaduwcorrectie.

## Bewijs uit de oorspronkelijke captures

Drie JPEG/DNG-paren rechtstreeks van `/sdcard/DCIM/BnCam`, tijden 21:06:44, 21:07:03 en 21:07:15. Camera 2, RAW10, single-frame. Capture-performance en terminalregistratie bevestigen drie succesvolle publicaties.

| Shot | ISO | Sluitertijd | FLLF gemiddelde signed correctie | Gemeten finale Y/chroma residusigma ×255 | Proefcorrectie |
|---|---:|---:|---:|---:|---:|
| 1 | 2530 | 49.13 ms | -0.0040 EV | 4.44 / 6.18 | -0.50 EV |
| 2 | 2302 | 50.00 ms | -0.0016 EV | 4.36 / 6.86 | -0.50 EV |
| 3 | 582 | 30.08 ms | -0.0037 EV | 2.60 / 3.00 | +0.33 EV |

De ISO × sluitertijd-producten verschillen tussen shot 1/2 en shot 3 met 2.83 / 2.72 stops. Dit is een gain/exposure-product, geen directe maat voor fotonen of zelfstandig bewijs van een AE-bug. De composities verschillen. De zichtbare lampen in shot 3 en de gelijktijdige daling van het exposure-product passen bij een sterker op de lichtbronnen reagerende meting.

Automatische RAW exposure is bij alle drie 1×, expliciete profiel-EV 0, post-RAW multiplier 1; sharpening en linear detail staan uit. FLLF corrigeert gemiddeld vrijwel neutraal. De aanvullende brede-schaduwlift heeft een budget van circa 0.33/0.33/0.39 EV. De bijna drie stops capture-productverschil worden dus niet verklaard door een grote automatische post-RAW gain of sharpening.

De twee lichte foto's zijn vooral te helder ten opzichte van de gewenste avondsfeer; dit is geen algemene sensorverzadiging. RAW-saturatie is 0.020%, 0.053%, 0.346%. Shot 3 heeft de meeste verzadigde sensels door de lampen terwijl de rest van de kamer donker is.

## RAW-proef

De bestaande productie-ISP en de bestaande expliciete profiel-EV-regeling zijn gebruikt. Alleen een losse device-test executable leest per proef een diagnostische EV. Deze executable wordt niet in de APK opgenomen. OLD en proef gebruiken identieke opgeslagen RAW-sensels, CCM, WB, ruisprofiel, shading en overige replay-invoer. Drie demosaics × twee herhalingen × drie foto's × OLD/proef = 36 renders, alle 18 herhaalparen byte-identiek.

De DNG bewaart drie-channel NoiseProfile en niet de afzonderlijke Camera2 groene noise coefficients of AF hints. Replay gebruikt een gedeeld groen noise-paar en onbekende AF hints. De exacte vastgelegde CCM/WB zijn uit capturetelemetrie op hun oorspronkelijke rationele stappen hersteld, en de DNG GainMap bevat de shading samples. Daardoor is OLD niet byte-identiek met de originele telefoon-JPEG: gemiddelde absolute RGB-afwijking 1.68/1.25/1.15 codes. Dit is een gecontroleerde vergelijking binnen de replay, geen claim van volledige reproductie van de capturecontext.

![OLD boven, RAW-EV proef onder](../../work/latest-three/balance-comparison.jpg)

De proef maakt de eerste twee beelden zichtbaar rustiger/donkerder en geeft de derde wat meer leesbaarheid. De kleine lift van shot 3 maakt ook zijn ruis zichtbaarder. Het aantal finale JPEG-componenten op code 255 gaat bij shot 3 van 67 naar 426; dit omvat JPEG-quantisatie/ringing en is geen nieuwe RAW-clipping, maar verhindert dat we deze globale EV-proef zonder verdere highlightcontrole als automatische veilige oplossing accepteren.

## Ruis en echte details

De 100%-crops tonen zowel luminantiegrain als gekleurde spikkels. Blauwe WB-gain is 2.55/2.52/2.65 onder warm licht, waarna CCM ook opponent noise kan versterken. De eerste twee captures hebben circa 1.7× het finale luminantieresidu en ruim 2× het chromaresidu van shot 3. Dit zijn bestaande native residue estimates; fijne structuur kan de absolute schatting beïnvloeden.

![Muur, 100%, OLD links / EV-proef rechts](../../work/latest-three/S1-wall-100pct.png)

![Vaas en beker, 100%, OLD links / EV-proef rechts](../../work/latest-three/S2-detail-100pct.png)

EV verlaagt de zichtbaarheid van ruis én signaal tegelijk en verbetert de opgeslagen SNR niet. De proef levert dus geen bewezen ruis- of detailherstel. De bestaande luma-authority is in deze captures terughoudend (gemiddelde HF-authority ongeveer 0.075/0.070); zonder stage-isolatie is niet bewezen dat die te zwak is of veilig verhoogd kan worden. De frozen Candidate1 wordt niet heropend.

## Gerichte verbeterroute

1. Maak capture-meting minder gevoelig voor een klein aandeel felle lichtbronnen en behoud tegelijk een aparte clippinggrens. Onderzoek hiervoor de bestaande robuuste ruimtelijke meting; bewijs de controlelus met opgeslagen preview/capture-sequenties voordat een AE-authority wordt gewijzigd. Eén statische RAW per compositie bewijst die lus niet.
2. Gebruik een beperkte, highlightbeschermde helderheidscorrectie voor resterende verschillen, in de bestaande toon-authority. Geen algemene schaduwlift of vaste mediaan-naar-grijs-normalisatie: een donkere avondscène moet donker mogen blijven. −0.5/−0.5/+0.33 EV zijn richtingproeven, geen per-bestandsproductiehardcodes.
3. Behandel ruis onafhankelijk: meer echte exposure als de gemeten bewegingsgrens dit toelaat; analyseer vervolgens camera-lineaire luma/chroma-residuen op deze nieuwe fixtures en bescherm coherente textuur. Alleen ISO verlagen bij dezelfde sluitertijd levert geen extra fotonen op. Geen belofte dat reeds onder de ruisvloer verdwenen textuur uit één RAW terugkomt.

Geen automatische renderingwijziging KEEP op basis van alleen deze EV-proef. Er is een bruikbare visuele balansrichting aangetoond, maar geen nieuwe ruisoplossing en geen gevalideerde automatische capturepolicy.
