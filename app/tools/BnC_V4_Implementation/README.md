# BnC Neural V4 — installatie en eerste GPU-kwalificatie

Dit pakket bevat de uitgewerkte runnercorrecties en twee nieuwe onderzoeksmodellen. Het bevat geen getraind V4-model. Het werkt met de broncode en bestanden uit de aangeleverde training-ZIP's. De uitvoering op de echte Windows-pc, CUDA en de volledige natural-image corpus moet daar nog gebeuren.

## Starten

Pak dit pakket uit buiten de BnCam-projectmap. Open PowerShell in de uitgepakte map `BnC_V4_Implementation` en voer uit:

```powershell
.\Apply-BnC-V4.ps1 -RepoRoot 'C:\Users\shuve\Desktop\BnCam'
Set-Location 'C:\Users\shuve\Desktop\BnCam'
.\Start-BnC-V4-Qualification.ps1
```

Pas het projectpad aan als BnCam elders staat. De installer controleert eerst alle bronbestanden en payload-checksums. Hij maakt een backup van de drie bestaande bestanden en voegt acht bestanden toe. Hij start zelf geen training. Bij afwijkende lokale code stopt hij vóór installatie: laat lokale Codex dan de verschillen beoordelen met `changes.patch`; verwijder geen eigen wijzigingen om de hashcontrole te omzeilen. Opnieuw installeren met dezelfde bestanden is toegestaan en verandert niets.

Het kwalificatiescript draait achtereenvolgens de 33 tests, de bekende A/C-checkpointdiagnose op CUDA, en de kandidaten N en S ieder tot maximaal step 2.000. Beide beginnen met exact dezelfde nieuwe gewichten en een nieuwe optimizer. Het script vervolgt geen oude A/C-training en start niet automatisch een 100k-run. Als één kandidaat faalt, wordt dat vastgelegd en kan de andere afzonderlijk worden onderzocht. Een gemelde onderzoeksstop wordt niet automatisch herstart.

Aan het einde — ook na een fout tijdens de tests of training — staat het resultatenpakket in:

```text
build/bnc-neural-v2/v4-verification/V4-qualification-<tijdstip>.zip
```

Stuur dat ZIP terug voor de volgende beoordeling. De bestaande checkpoints blijven beschikbaar. De V4-resultaten staan afzonderlijk in `build/bnc-neural-v2/v4-research/N` en `S`.

## Wat is verbeterd

- Iedere optimizer-update krijgt controle vóór en direct ná de update. Ook eindige maar te grote gradients die bij het kwadrateren FP32 AdamW-momenten laten overlopen worden afgewezen.
- De bekende stresscase en de opgeslagen isoluminante kleurrand worden vóór training, iedere 250 stappen en bij de sessiestop gecontroleerd. Probes gebruiken een onafhankelijke kopie en veranderen geen live weights, gradients of optimizer-state.
- Numerieke fouten en kwaliteitsregressies krijgen afzonderlijke tellers en rapporten. Trainings-, validatie- en probe-maxima hebben een eigen meetbereik.
- Onveilige toestand wordt niet als nieuwe `latest.pt` opgeslagen. Een numeriek geldig maar door de onderzoekscontrole afgewezen V4-checkpoint krijgt een afzonderlijke rejected-naam.
- Resume controleert modelidentiteit, broncode, dataset, parameters en vergelijkingsbestanden. De oorspronkelijke V3-model-, loss- en datacode is behouden.

De relatieve **4×-regressiecontrole** is een expliciete onderzoeksgrens om ernstige verslechtering tijdig te stoppen. Het is geen productkwalificatie. De bestaande synthetische kwaliteitsdrempels zijn niet versoepeld. Vanaf step 2.000 gebruikt V4 de beste tot dan gemeten stress-loss en kleurrand-L1 als vastgelegde referenties, met hun eigen referentiestappen.

## Twee vergelijkbare nieuwe modellen

| Eigenschap | N | S |
|---|---|---|
| Learned feature-normalisatie | Per pixel over de kanalen, vóór depthwise-convolutie | Dezelfde normalisatie |
| Blokbewerking | `16*tanh(a*b/16)` | `0.5*(SiLU(a)+SiLU(b))` |
| Trainbare parameters | 27.632 | 27.632 |
| Start | Nieuwe, identieke seeded weights | Nieuwe, identieke seeded weights |
| Doel | Onderzoeken of normalisatie de multiplicatieve gate voldoende stabiliseert | Onderzoeken hoe reconstructie presteert zonder multiplicatieve gate |

Breedte 24, negen blokken, dilaties, receptive field, tile-halo, output-heads, oorspronkelijke loss, datasetmix, FP32, microbatch 32, workers 4 en de oorspronkelijke 100k-cosine-schedule blijven gelijk. De 432 extra parameters zijn affine feature-normalisatie. Dit is geen bewezen kwaliteitsupgrade: dat moeten training en onafhankelijke validatie aantonen.

**BnC Neural blijft een zelfstandig demosaic.** Input is finalized Bayer; output is scene-linear sensor-RGB. Het reconstrueert ontbrekende kleurmonsters met green/opponent-heads en behoudt gemeten Bayer-monsters exact in FP32. Het ontvangt geen RGB-resultaat van Malvar of AMaZE en is geen denoiser bovenop die algoritmes. Learned feature-normalisatie is geen normalisatie van RAW-exposure. WB, CCM, tone en Spectra-ruisverwerking behoren niet tot deze modelinput.

## Benodigde lokale bestanden

Gebruik de bestaande Python/CUDA-runtime en dependencies van de aangeleverde PowerShell-training. Vereist zijn de volledige source-group-gescheiden corpus onder `build/bnc-neural-v2/corpus`, de opgeslagen procedural-validatie onder `build/bnc-neural-v2/training/w24`, A/C-checkpoints van 15k, 29k en 30k onder `build/bnc-neural-v2/v3-phase-b`, de bestaande klassieke synthetische fixtures/outputs onder `build/bnc-neural/synthetic`, en `build/bnc-neural/training/opponent-v2-full/candidate.bncmodel` met SHA-sidecar. Ontbrekende bestanden worden gemeld; er wordt geen kleinere dataset als gelijkwaardige validatie gebruikt.

## Na deze eerste sessie

1. Beoordeel voor beide kandidaten numerical failures, stress- en kleurrandcurves, alle 116 vaste cases, bestaande synthetische gates en beelden op gelijke schaal. Step 2.000 is een eerste technische controle; het is te vroeg voor een definitieve kwaliteitswinnaar.
2. Onderzoek geschikte kandidaten verder op expliciete mijlpalen, bijvoorbeeld 5k, 15k en 30k. Bewaar dezelfde data, loss, schedule en rapportage. Controleer snelheid en GPU-geheugen: deze controles en feature-normalisatie hebben nog geen gemeten GPU-kosten.
3. Alleen een stabiele kandidaat met voldoende reconstructiekwaliteit gaat verder richting 100k. Het halen van 100k is geen releasebewijs. Vergelijk ook eerdere checkpoints; het hoogste stapnummer hoeft niet het beste model te zijn.
4. Maak daarna een exportformaat en Vulkan-implementatie die daadwerkelijk feature-normalisatie en de gekozen nieuwe blokbewerking ondersteunen. Dit pakket blokkeert export van V4 via het oude graphsysteem om foutieve modelinterpretatie te voorkomen. Test PyTorch tegenover export/Vulkan, volledige frames tegenover tiles, alle Bayer-fases, odd sizes, signed chroma en headroom, plus tijd/geheugen op het doeltoestel.
5. Integreer de gekwalificeerde **BnC Neural** als derde zelfstandige keuze naast Malvar en AMaZE. Auto Hybrid moet alle drie kunnen combineren op dezelfde Bayer-input, met expliciete beschikbaarheid en fallback. Laat UI, instellingen, persistente namen, dispatch en tests aansluiten op de dan actuele BnCam-code. Dit trainingspakket wijzigt die app-integratie nog niet.

Zie `VALIDATION.md` voor uitgevoerd bewijs en beperkingen. `CODEX_LOCAL_PROMPT.md` is een kant-en-klare instructie voor lokale Codex als je die de installatie wilt laten uitvoeren.
