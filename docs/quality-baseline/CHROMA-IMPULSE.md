# Low-light chroma quality impulse — 8 oktober 2026

**Besluit: REVERT.** Twee pogingen getest; beide kwaliteitswijzigingen zijn teruggedraaid. De herstelde APK is gebouwd, geïnstalleerd en gericht op device getest. Geen nieuwe fysieke captures gemaakt.

## A. Root cause

De actieve full-resolution fysieke chromafilter werkt na demosaic en vóór WB/CCM, op Y, R−G en B−G. Hij gebruikt de bestaande Spectra post-demosaic covariance, echte shot/read-coëfficiënten en lokale shading/noise scaling. De extra klassieke Spectra-chromastage staat uit; er is geen tweede denoiser aangezet.

De buurafstand bevat ook de ruis van beide observaties. Daardoor wijst de bestaande predictor een deel van noise-only buren af. De same-RAW daling ondersteunt dit als een beperkte bijdrage, niet als volledige verklaring van alle zichtbare ruis. B1 modelleert WB-versterking van RG/BG op 2,46×/1,68× en gecombineerde WB/CCM-chromaversterking op 3,33×. Geen WB-bug aangetoond; noise-model provenance is geldig.

## B. Implementatie

Poging 1 verdisconteerde de drie verwachte gewhite-noisedimensies vloeiend met d²/(d+3), uitsluitend in de bestaande fijne buurgewichten. De winst was te klein. Poging 2 paste dezelfde correctie ook op de bestaande ruimere support toe, met behoud van de intervening-edge-gate. CPU en GPU waren gelijk aangepast; geen wijziging aan luma-averaging, radius, WB, kleur, tone, demosaic of Hybrid. Beide wijzigingen zijn verwijderd. Alleen de kleine native testharness-replayoptie en een stale testcheck (`const auto` → `auto`) blijven.

## C. Same-RAW resultaat

Poging 2 hieronder: Hybrid, gemiddelde van twee vooraf vastgelegde donkere fysieke ROIs [60,1200,192,256] en [60,1800,192,256]. Waarden zijn genormaliseerde JPEG-proxies, geen absolute ruis- of kwaliteitsmeting met ground truth.

| Frame | R−G std OLD → NEW | B−G std OLD → NEW | Chroma spatial OLD → NEW | Detail gradient / HF-luma delta | Hoge JPEG-eindpunten OLD → NEW |
|---|---|---|---|---|---|
| B1 | 0.030650 → 0.029637 (-3.30%) | 0.026336 → 0.024888 (-5.50%) | 0.016553 → 0.016031 (-3.15%) | +0.118% / +0.141% | 257 → 254 |
| B2 | 0.031144 → 0.030135 (-3.24%) | 0.027289 → 0.025850 (-5.27%) | 0.017499 → 0.016833 (-3.80%) | -0.035% / -0.118% | 304 → 306 |
| B3 | 0.030963 → 0.029941 (-3.30%) | 0.026722 → 0.025293 (-5.35%) | 0.016614 → 0.016132 (-2.90%) | +0.062% / -0.005% | 331 → 332 |

Alle drie B-inputs verbeteren in dezelfde richting. Malvar en AMaZE eveneens: alle donkere RG/BG/spatial-proxies dalen. De zichtbare winst in de vaste 100%/200% crops blijft bescheiden.

De native final-clippingtelemetrie blijft 0; hoge JPEG-eindpunten nemen bij B2 met 2 en B3 met 1 kanaalwaarden toe. DCT/kwantisatie kan dat verklaren: dit is geen bewezen extra scene-linear clippingbug, maar ook geen volledig bewijs voor de strikte eis “geen extra clipping”. Daarom geen PASS daarvoor. Lage JPEG-eindpunten nemen af, geen gewijzigde black-level/tone-code; donkere mean-luma stijgt slechts 0,10–0,12%.

Alle 81 dubbele replayparen (OLD + poging 1 + poging 2; A/B/D × 3 frames × 3 modes) zijn bitexact. Alle 162 renders voeren de gevraagde demosaic uit, zonder fallback, met actieve fysieke GPU-chromafilter. Afmetingen blijven 4096×3072. Globale gemiddelde RGB-verschuivingen op B zijn maximaal 0,000042; zie kleur/border-JSON voor gemeten randgemiddelden en saturatie. Er is geen globale saturatiebewerking toegevoegd.

NaN/Inf: 20 CPU- en 20 productie-GPU-filtercases, inclusief invalid-model en near-singular, leveren geldige eindige output en behouden lumastructuur. De werkelijke replays hebben eindige gerapporteerde outputgemiddelden. Er is geen volledige scene-linear pixel-readback; dus geen claim van een bewezen full-frame NaN/Inf-count. De inf-sentinels voor niet-beschikbare optionele kleurprofielen zijn geen pixelwaarden.

Edge width/overshoot/undershoot: N/A voor deze fysieke crops; er is geen geldig gedeclareerd geïsoleerd recht edge-profiel. Geen verzonnen metingen. Synthetische rand-, tekst-, textile- en lijncases zijn groen.

Methode: exact dezelfde negen canonical RAW-checksums voor OLD/NEW, inclusief frame-eigen DNG-LSC; DNG-stripbytes zijn tegen RAW-SHA geverifieerd. De native replaycontext is uit opgeslagen metadata gereconstrueerd. OLD is geen bitexacte reproductie van de oorspronkelijke publicatie: bij B1 is JPEG mean absolute RGB difference 0,0000523. Het causale OLD/NEW-paar gebruikt wel identieke RAW/context.

## D. A/D regressie

A: PASS binnen de gemeten kleur/detail-proxies; geen betekenisvolle kleurverschuiving. D: PASS voor de beschikbare gedrukte tekst/diagonalen; Hybrid HF-luma delta −0,0093% tot +0,0162%. Geen volledige fysieke woven-detail-kwalificatie uit deze foto geclaimd. Alle drie algoritmen zijn meegenomen.

## E. Fysieke post-B

Niet uitgevoerd: de wijziging is niet geaccepteerd. Geen extra scène-input nodig voor deze afgesloten poging.

## F. Performance en tests

Matched tweede Hybrid-render, mediaan over B1–B3: OLD 3438,90 ms; poging 1 3427,61 ms; poging 2 3054,41 ms. OLD/poging 1 thermal=2, poging 2 thermal=1. Dit is geen thermisch gekwalificeerde voor/na-delta en geen bewezen snelheidswinst. Geen aanwijzing voor catastrofale vertraging; geen extra dispatch toegevoegd.

APK-builds van beide pogingen en herstel geslaagd. 21 gerichte JVM-tests groen na stale-checkfix. PhysicalChromaDeviceTest + NoiseModelNativeConnectedTest groen op poging 2 én op de herstelde geïnstalleerde APK. CPU/GPU elk 20 chromacases allPassed=true.

## G. Besluit

**REVERT**: consistente maar bescheiden chromawinst, geen overtuigende zichtbare kwaliteitsimpuls met volledig groene strikte veiligheidsvoorwaarden. Productfilter terug op de bestaande versie; overige working-treewerk behouden.

## H. Exact één volgende aanbeveling

Een volgende chroma-impuls moet de resterende fijne chromaresiduals in het daadwerkelijk versterkte WB/CCM-domein beoordelen, met expliciete scene-linear clipping-readback; niet nogmaals buurgewichten blind sterker maken.

## Bewijsbestanden

- `work/chroma-impulse/same-raw-new2.json`: alle 27 OLD/NEW vergelijkingen en vaste ROIs.
- `work/chroma-impulse/crops-new2/`: B1/B2/B3 × Hybrid/Malvar/AMaZE, OLD/NEW/ABS DIFF ×16, 100% en 200% nearest-neighbour.
- `work/chroma-impulse/render-checks.json`: alle 162 daadwerkelijke routes.
- `work/chroma-impulse/global-colour-borders.json`: globale kleur/saturatie/luma en randen.
- `work/chroma-impulse/{attempt2,restored}-connected.txt`: device-testresultaten.
