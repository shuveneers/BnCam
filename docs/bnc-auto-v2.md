# BnC Auto v2 — fotografische afstelling

9 oktober 2026, Honor BKQ-N49, hoofdsensor 2.

De bestaande engine gebruikt nu diffuse RAW-percentielen, 12 ruimtelijke regio's,
contrast, schaduwbezetting en compacte lichtbronnen. Kleine lampen worden apart
beoordeeld; grote lichte oppervlakken behouden hun kanaal-headroombegrenzing.
De oude SNR-gestuurde lift van diepe schaduwen is verwijderd. De natuurlijke
radiancecurve blijft continu; scènelabels zijn diagnostiek, geen gemeten lux.

Fysiek realiseerbare sluitertijd/ISO-kandidaten over het veilige spectrum worden
vergeleken op verwachte SNR, detailrisico, clipping, exposure-product en latency.
Flicker en sensorgrenzen worden vóór de vergelijking toegepast. Een kandidaat
die door maximale ISO het benodigde exposure-product verliest, krijgt geen
onterecht voordeel. Hysteresis voorkomt wisselen voor kleine utilityverschillen.
De bestaande onafhankelijke, tweedimensionale RAW-detailtest blijft vereist voor
lange sluitertijden; ontbrekende metingen trekken die toestemming direct in.

## Werkelijk gerealiseerde CaptureResults

| Scène | Controller | Sluitertijd | ISO | Hoogste CFA-clipping |
|---|---|---:|---:|---:|
| Gedimd, kleine lampen | Standard Auto | 59,999959 ms | 5856 | 0,1195% |
| Gedimd, kleine lampen | Oude BnC Auto | 539,999749 ms | 180 | 0,0476% |
| Gedimd, kleine lampen | BnC Auto v2 | 539,999749 ms | 126 | 0,0366% |
| Normaal binnenlicht | Standard Auto | 29,999995 ms | 1259 | 0,0276% |
| Normaal binnenlicht | BnC Auto v2 | 169,999867 ms | 104 | 0,0119% |
| RAW_SENSOR-smoke | BnC Auto v2 | 539,999749 ms | 158 | 0,0432% |

Gedimd kiest v2 circa 0,51 EV minder product dan de oude engine, zonder extra
fotonen te claimen: de sluitertijd is gelijk. De omgeving blijft donker en kleine
lampen mogen verzadigen. Kader en AF veranderden hier enigszins: geen bewijs van
detail- of SNR-winst tegenover de oude engine. De stabiliteitstelemetrie bewijst
een detailveilige grens boven de fysieke 540-ms-limiet en voorspelt ongeveer
3,43× scènebody-SNR tegenover dezelfde productkeuze met 40 ms/hogere ISO.
Die voorspelling gebruikt het bestaande lokale gain-scaled noise-model, geen
nieuwe ISO-calibratie. De legacy diagnostieknaam `predictedShadowSnr*` blijft
compatibel, maar beschrijft nu diffuse scènebody-SNR.

Normaal is het product circa 1,10 EV lager dan Standard Auto. De AF-afstand is
bij beide exact 0,8976661; het kader is gelijk. De JPEG toont minder korrel en
behoudt houtstructuur, maar is donkerder. In een handmatig bekeken glad metalen
RAW-vlak [700,1200,320,160] daalt het relatieve groene hoogfrequente residu van
13,6% naar 9,7–10,0%. Dit omvat ook textuur/FPN/quantisatie en is geen geïsoleerde
temporele sensorruismeting. Algemene superioriteit of betere handheldkwaliteit
is met deze kleine vergelijking niet bewezen. Na de opname trok actuele
RAW-verandering de lange toestemming in en verkortte de engine tot circa 9,8 ms.

## Validatie en grenzen

54 gerichte JVM-tests slagen, inclusief RAW10/RAW_SENSOR-meteringequivalentie,
kleine lamp versus brede clipping, tussenliggende shutter, quantisatiewinst,
stabiliteitsverlies, subpixelbeweging, flicker, sensorautoriteit en Manual.
Debug-build slaagt en is geïnstalleerd. Twee extra bestaande broncodechecks in
`RawExposureAwbStabilitySourceContractTest` verwachten oudere ISP/AWB-code;
die ongewijzigde bestanden zijn niet aangepast om de checks te laten slagen.

RAW10 en RAW_SENSOR publiceren FULL_SUCCESS. Beide gepubliceerde DNG's bevatten
bitexact de geëxporteerde RAW-pixels en juiste ISO/ExposureTime/NoiseProfile.
De geproduceerde JPEG-pixels zijn identiek aan de geselecteerde bevroren replay.
De foto's zijn gemaakt vóór een laatste uitsluitend diagnostische correctie van
de NIGHT/DIM_INTERIOR-labelgrenzen; de fysieke beslisregels zijn gelijk. De
laatste build is opnieuw getest en geïnstalleerd.

Candidate1, highlight A, demosaic, tone mapping, AF/AWB/OIS, Near-ZSL,
exact long still, metadata en publicatie zijn behouden. Op dit toestel meldt
ook RAW_SENSOR whiteLevel 1023; de engine gebruikt de werkelijk gemelde precisie.
Standard Auto blijft de standaardkeuze. Geen commit of push.

Detailbewijs en APK-herkomst staan in `work/bnc-auto-v2/`: `live/`,
`measured-comparison.json`, `normal-smooth-patch.json`, `dng-checks.json`,
publicatiechecks, telemetrie en `final-tests-build.log`.
