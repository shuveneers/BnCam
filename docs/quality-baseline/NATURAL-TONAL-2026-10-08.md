# KEEP — natuurlijke schaduw- en middentoonweergave, 2026-10-08

De brede schaduwplaatsing direct na de lokale FLLF-reconstructie veroorzaakte extra grijze schaduwlift, steile donkere overgangen en een sterk samengedrukt aangrenzend middentoongebied. De bestaande FLLF-Laplacianreconstructie zelf versterkte de onderzochte D-contouren niet overmatig; sharpening/detail staat in deze vaste replayinstellingen uit. Er is daarom geen sharpening- of denoisewijziging gedaan.

Definitief KEEP: uitsluitend `applyBroadShadowPlacement` in `spectra_tone_resident.comp`. De aanvullende adaptieve lift is begrensd van maximaal .95 naar .50 EV; bij A1 is het budget ongeveer .807 naar .406 EV. De smalle lineaire black gate is vervangen door een rationeel zwartanker met schaal .024. De terugkeer naar identiteit verloopt nu met een C2 quintic venster over log-luminantie .05–.50. Zwart blijft gepind; de mapping is monotonic en er komt geen globale exposure, RGB-blur, soft-focus, denoiser, nieuwe tone mapper, extra beeldbuffer of GPU-pass bij. De lokale FLLF-correctie en de volledige uitgangsresolutie blijven behouden. Donkere oppervlakken ogen minder grijs en flets; stofstructuur en tekst blijven zichtbaar.

Namen in de werkmap: `A` is deze natuurlijke schaduwcorrectie; `AB` voegt de verworpen nieuwe C2-shoulder toe. Deze namen staan los van de reeds geaccepteerde **highlightvariant A**, die ongewijzigd geïntegreerd blijft.

| Controle | OLD: Candidate1 + highlightvariant A | Definitief NEW |
|---|---:|---:|
| Synthetische GPU logramp: relatieve contrastversterking | 0.130–1.695× | 0.773–1.146× |
| Maximaal brede schaduwlift | .95 EV | .50 EV |
| Identieke upstream/FLLF native planes | referentie | 432/432 |
| A1–3 × drie routes: magenta highlightpixels | 0 | 0 |
| 27 RAW10 JPEG herhaalparen | referentie | 27/27 exact |
| Debug-/productie-JPEG's | referentie | 27/27 exact |
| Native stage-herhaalparen | referentie | 648/648 exact, finite |
| Full-resolution JPEG 4:4:4 | 4096×3072 | 4096×3072, alle 27 |
| Gemeten pre-JPEG 255 / float >1 | 0 / 0 | 0 / 0 |
| Synthetische geïsoleerde randen: extra overshoot/undershoot | 0 | 0 |
| RAW_SENSOR: drie fixtures × drie routes | eigen OLD-baseline | 9/9 deterministisch, geen fallback |

De GPU-probe voert de echte shaderfuncties uit op lineaire en log-luminantierampen, een gekleurde ramp, een zachte overgang, een harde rand en 4-/16-pixeltextuur. De afzonderlijke stage-uitvoer komt binnen 1.20e-7 overeen met de volledige tone-dispatch. FLLF-uitvoer is bit-identiek. De monotone lineaire/log- en gekleurde rampen hebben geen nieuwe omkeringen; de logramp behoudt 248 afzonderlijke 8-bit sRGB-codes met maximaal één code verschil per stap. De lineaire 0–2-ramp stijgt van 185 naar 202 codes, zonder een grotere maximale stap. In de zachte synthetische overgang blijven bestaande kleine lokale FLLF-wobbles aanwezig; de nieuwe schaduwfunctie creëert geen nieuwe extrema en er is geen claim dat iedere ruimtelijke FLLF-afgeleide strikt positief is.

Echte 4- en 16-pixeltextuur wordt niet weggefilterd: de relatieve synthetische amplitudes blijven behouden of nemen toe waar de oude middentooncompressie ze verzwakte. Dezelfde D-glyphprofielen en HF-energy zijn vastgelegd; de echte 100%-crops tonen behoud van tekst- en stofstructuur zonder nieuwe contourranden. Dit is geen geïsoleerde MTF-meting en de fixtures bevatten geen apart gekwalificeerde haar-scène.

Candidate1 covariance-aware fine-chroma en de fysieke luma/noise-implementaties zijn byte-identiek. De 432 gelijke native planes omvatten demosaic, noise/chroma, de bestaande kleurdispatch, confidence, pre-LOG, LOG en FLLF. Het inherited debuglabel `wb-ccm` dupliceert de uiteindelijke kleurdispatch en wordt niet als onafhankelijke pre-fade meting gebruikt. Het lineaire RGB-resultaat van schaduwplaatsing blijft een enkele positieve scalar: gemeten maximale relatieve RGB-residu 9.36e-08. Er is geen saturatie-, hue- of kleur-LUT-truc. De confidence-maps en broncode van highlightvariant A zijn exact behouden; gemeten q=0-highlightkernen blijven exact neutraal.

Er is geen nieuwe numerieke highlightclipping. Bij enkele near-zero floatcomponenten verandert bestaand gamutafrondingsresidu naar exact nul; hun OLD-maximum is 1.49e-8, ver onder één 8-bit-code. In de uiteindelijke JPEGs kan de bedoelde lagere schaduwlift enkele componenten naar code nul laten kwantiseren: A1 +1061, B1 +465 en D1 +144 van telkens 37.75 miljoen componenten. Er is geen nieuwe zwarte clamp; de schaduwgradaties blijven zichtbaar in de werkelijke crops. Dit onderscheidt lagere schaduwplaatsing van het afsnijden van RAW-detail.

RAW_SENSOR is gecontroleerd met dezelfde bewaarde, uitgepakte sensels en dezelfde black/white-, WB/CCM-, noise- en capturemetadata, via de daadwerkelijke RAW_SENSOR-route. Dit is een transport-/ISP-regressiecontrole, geen nieuwe fysieke RAW_SENSOR-capture. Beide formaten gebruiken dezelfde behouden shaderfunctie. De oorspronkelijke defectpixelcorrectie is formaatadaptief: A1 corrigeert 4 sensels via RAW10 en 8 via RAW_SENSOR; D1 0 versus 9. Die bestaande puntverschillen zijn intact en worden tegen een eigen OLD RAW_SENSOR-replay gecontroleerd. B1 is ook tussen formaten byte-identiek. Er zijn geen formaat-hardcodes toegevoegd. Alle negen OLD/NEW RAW_SENSOR-gevallen hebben dezelfde demosaic-, noise-, FLLF-, clipping- en detailautoriteiten en exact herhaalbare uitvoer. [Volledige formaatcontrole](../../work/natural-tonal/sensor-regression.json).

De tweede implementatie, een C2 Khronos-shoulder met geleidelijker voorbereidingscurve, is **REVERT**. Hoewel de afgeleiden en rampmetrics verbeterden, tonen de onafhankelijke A-versus-AB 100%-crops geen overtuigende extra fotografische winst. Alleen die wijziging is verwijderd. De originele Khronos-mapper, WB/CCM, Malvar, AMaZE, Auto Hybrid en LOG → FLLF → KHRONOS blijven staan. Het vlakke fysiek geclipte highlightcentrum is niet opgelost en wordt niet als winst geclaimd. De huidige scalar Khronos-shoulder blijft C1 met bestaande tweede-afgeleideknikken; de behouden schaduwovergang is C2.

Build `assembleDebug` PASS. Honor Adreno 840 CPU/GPU-oracle: 24 CFA/lens/RAW10-/RAW_SENSOR-gevallen ALL PASSED; confidence-map error 0, colour error 7.16e-7, tone error 2.99e-7. Exacte Camera2-greens en legacy RAW-/YUV-controles slagen. Geen fysieke captures genomen. Definitieve APK geïnstalleerd en hash geverifieerd: `36740dacac3ecb6cdfc0ab7bf1e7e655011a40cbdb9956a3269eb99bb33f8e4d`. App-start `Status: ok`; proces actief, crashbuffer voor dat proces leeg van fatale fouten.

Er zijn geen extra passes of buffers. De native timingbestanden zijn bewaard; de korte opeenvolgende replays zijn geen gerandomiseerde performancegate en onderbouwen geen precieze procentuele latencyclaim.

![Echte 100%-crops OLD/NEW: stof, schaduwgradient en tekst](../../work/natural-tonal/result-100pct.png)

Alle crops gebruiken dezelfde RAW, pixelcoördinaten, uitvoerresolutie en belichting; geen resampling, exposure-normalisatie of lokale contrastnormalisatie. Volledige uitsneden per route:

| Fixture / route | 100% OLD/NEW |
|---|---|
| A1 / Hybrid | [detail](../../work/natural-tonal/A-analysis/crops/A1/Hybrid/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A1/Hybrid/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A1/Hybrid/highlight.png) |
| A1 / Malvar | [detail](../../work/natural-tonal/A-analysis/crops/A1/Malvar/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A1/Malvar/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A1/Malvar/highlight.png) |
| A1 / AMaZE | [detail](../../work/natural-tonal/A-analysis/crops/A1/AMaZE/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A1/AMaZE/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A1/AMaZE/highlight.png) |
| A2 / Hybrid | [detail](../../work/natural-tonal/A-analysis/crops/A2/Hybrid/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A2/Hybrid/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A2/Hybrid/highlight.png) |
| A2 / Malvar | [detail](../../work/natural-tonal/A-analysis/crops/A2/Malvar/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A2/Malvar/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A2/Malvar/highlight.png) |
| A2 / AMaZE | [detail](../../work/natural-tonal/A-analysis/crops/A2/AMaZE/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A2/AMaZE/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A2/AMaZE/highlight.png) |
| A3 / Hybrid | [detail](../../work/natural-tonal/A-analysis/crops/A3/Hybrid/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A3/Hybrid/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A3/Hybrid/highlight.png) |
| A3 / Malvar | [detail](../../work/natural-tonal/A-analysis/crops/A3/Malvar/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A3/Malvar/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A3/Malvar/highlight.png) |
| A3 / AMaZE | [detail](../../work/natural-tonal/A-analysis/crops/A3/AMaZE/detail.png) · [bright](../../work/natural-tonal/A-analysis/crops/A3/AMaZE/bright.png) · [highlight](../../work/natural-tonal/A-analysis/crops/A3/AMaZE/highlight.png) |
| B1 / Hybrid | [shadow](../../work/natural-tonal/A-analysis/crops/B1/Hybrid/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B1/Hybrid/gradient.png) |
| B1 / Malvar | [shadow](../../work/natural-tonal/A-analysis/crops/B1/Malvar/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B1/Malvar/gradient.png) |
| B1 / AMaZE | [shadow](../../work/natural-tonal/A-analysis/crops/B1/AMaZE/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B1/AMaZE/gradient.png) |
| B2 / Hybrid | [shadow](../../work/natural-tonal/A-analysis/crops/B2/Hybrid/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B2/Hybrid/gradient.png) |
| B2 / Malvar | [shadow](../../work/natural-tonal/A-analysis/crops/B2/Malvar/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B2/Malvar/gradient.png) |
| B2 / AMaZE | [shadow](../../work/natural-tonal/A-analysis/crops/B2/AMaZE/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B2/AMaZE/gradient.png) |
| B3 / Hybrid | [shadow](../../work/natural-tonal/A-analysis/crops/B3/Hybrid/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B3/Hybrid/gradient.png) |
| B3 / Malvar | [shadow](../../work/natural-tonal/A-analysis/crops/B3/Malvar/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B3/Malvar/gradient.png) |
| B3 / AMaZE | [shadow](../../work/natural-tonal/A-analysis/crops/B3/AMaZE/shadow.png) · [gradient](../../work/natural-tonal/A-analysis/crops/B3/AMaZE/gradient.png) |
| D1 / Hybrid | [text](../../work/natural-tonal/A-analysis/crops/D1/Hybrid/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D1/Hybrid/texture.png) |
| D1 / Malvar | [text](../../work/natural-tonal/A-analysis/crops/D1/Malvar/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D1/Malvar/texture.png) |
| D1 / AMaZE | [text](../../work/natural-tonal/A-analysis/crops/D1/AMaZE/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D1/AMaZE/texture.png) |
| D2 / Hybrid | [text](../../work/natural-tonal/A-analysis/crops/D2/Hybrid/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D2/Hybrid/texture.png) |
| D2 / Malvar | [text](../../work/natural-tonal/A-analysis/crops/D2/Malvar/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D2/Malvar/texture.png) |
| D2 / AMaZE | [text](../../work/natural-tonal/A-analysis/crops/D2/AMaZE/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D2/AMaZE/texture.png) |
| D3 / Hybrid | [text](../../work/natural-tonal/A-analysis/crops/D3/Hybrid/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D3/Hybrid/texture.png) |
| D3 / Malvar | [text](../../work/natural-tonal/A-analysis/crops/D3/Malvar/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D3/Malvar/texture.png) |
| D3 / AMaZE | [text](../../work/natural-tonal/A-analysis/crops/D3/AMaZE/text.png) · [texture](../../work/natural-tonal/A-analysis/crops/D3/AMaZE/texture.png) |

Bewijs: [source-/stage-audit](../../work/natural-tonal/final-audit.json), [synthetische GPU-resultaten](../../work/natural-tonal/synthetic-A-metrics.json), [toonresponsplot](../../work/natural-tonal/synthetic-A-response.png), [werkelijke JPEG-metrics](../../work/natural-tonal/A-analysis/metrics.json), [native stagemetingen](../../work/natural-tonal/final-stage-metrics.json), [D-glyphprofielen](../../work/natural-tonal/glyph-profiles.json), [afzonderlijke verworpen shoulder](../../work/natural-tonal/B-isolated-A1.png), [device-oracle](../../work/natural-tonal/device-tests.log), [build](../../work/natural-tonal/final-build.log), [installatie](../../work/natural-tonal/installed-apk.json).

Werkelijke 100%-RAW_SENSOR OLD/NEW-crops met eigen formaatbaseline: [A1 Hybrid detail](../../work/natural-tonal/sensor-crops/A1/Hybrid/detail.png) · [A1 Hybrid bright](../../work/natural-tonal/sensor-crops/A1/Hybrid/bright.png) · [A1 Malvar detail](../../work/natural-tonal/sensor-crops/A1/Malvar/detail.png) · [A1 Malvar bright](../../work/natural-tonal/sensor-crops/A1/Malvar/bright.png) · [A1 AMaZE detail](../../work/natural-tonal/sensor-crops/A1/AMaZE/detail.png) · [A1 AMaZE bright](../../work/natural-tonal/sensor-crops/A1/AMaZE/bright.png) · [B1 Hybrid shadow](../../work/natural-tonal/sensor-crops/B1/Hybrid/shadow.png) · [B1 Hybrid gradient](../../work/natural-tonal/sensor-crops/B1/Hybrid/gradient.png) · [B1 Malvar shadow](../../work/natural-tonal/sensor-crops/B1/Malvar/shadow.png) · [B1 Malvar gradient](../../work/natural-tonal/sensor-crops/B1/Malvar/gradient.png) · [B1 AMaZE shadow](../../work/natural-tonal/sensor-crops/B1/AMaZE/shadow.png) · [B1 AMaZE gradient](../../work/natural-tonal/sensor-crops/B1/AMaZE/gradient.png) · [D1 Hybrid text](../../work/natural-tonal/sensor-crops/D1/Hybrid/text.png) · [D1 Hybrid texture](../../work/natural-tonal/sensor-crops/D1/Hybrid/texture.png) · [D1 Malvar text](../../work/natural-tonal/sensor-crops/D1/Malvar/text.png) · [D1 Malvar texture](../../work/natural-tonal/sensor-crops/D1/Malvar/texture.png) · [D1 AMaZE text](../../work/natural-tonal/sensor-crops/D1/AMaZE/text.png) · [D1 AMaZE texture](../../work/natural-tonal/sensor-crops/D1/AMaZE/texture.png)
