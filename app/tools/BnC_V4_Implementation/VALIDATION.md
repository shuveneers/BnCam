# Uitgevoerde controles

De wijzigingen zijn uitgevoerd op kopieën van de aangeleverde trainingsbroncode. Er is niets op de echte Windows-pc toegepast en niets naar GitHub gepusht.

## Resultaat

- **33 unittests slagen**, bestaande contracttests en fase-gatetests inbegrepen. Getest: gemeten sensels exact behouden, Bayer-fases/offsets/odd sizes, tile-pariteit, nieuwe modelidentiteit, gelijke N/S-startgewichten, overflow afwijzen vóór optimizer-mutatie, fouten na update herkennen, probe-isolatie, exacte volgende update na resume, RNG-behoud tijdens evaluatie, checkpointbehoud bij fouten en blokkade van onjuiste V4-export.
- De originele A/C-checkpoints van 15k, 29k en 30k zijn opnieuw uitgevoerd op CPU met oorspronkelijke source-hashes en het echte opgeslagen validatiearchief. A/C 15k passeren de onderzoekscontrole. A 29k/30k worden afgewezen wegens onveilige FP32 AdamW-gradientkwadraten én zware stressregressie. C 29k/30k worden afgewezen wegens stressregressie; C 30k ook wegens kleurrandregressie. Dit is diagnose op onafhankelijke model/optimizer-kopieën, geen voortzetting van de originele trainingsbatch.
- Nieuwe, ongetrainde N/S passeren beide de numerieke startprobes. Dit bewijst geen beeldkwaliteit.
- Alle drie PowerShell-scripts zijn geparseerd met officiële PowerShell 7.6.6 op Linux. De installer is daar op een tijdelijke projectkopie uitgevoerd: CheckOnly, installatie van 11 bestanden, controle van alle hashes, herhaalde installatie zonder wijzigingen, en weigering van een bronconflict vóór installatie.
- Het kwalificatiecontrollerscript is op die tijdelijke kopie gestart. De 33 tests slagen. De ontbrekende CUDA stopt vervolgens de workflow vóór aanmaak/training van N/S; de controller maakt wel een resultaten-ZIP met de foutmelding. Dit bevestigt die foutafhandeling, niet de volledige GPU-workflow.

## Grenzen van deze verificatie

Runtime: Python met Torch 2.14.1+cpu. Windows PowerShell, CUDA-kernels, werkelijke GPU-snelheid/geheugen, de lokale multiprocessing-training en de volledige 64 natural + 52 procedural validatieset zijn hier niet uitgevoerd. De upload bevat niet de volledige natuurlijke corpus. Het lokale kwalificatiescript is de noodzakelijke volgende uitvoering. Het model is nog niet getraind en niet productklaar.

## Exacte wijzigingspunten

Paden hieronder zijn relatief aan de BnCam-projectroot. `PATCH_MANIFEST.json` bevat de volledige oude/nieuwe SHA256-hashes en acht behouden kernbestanden.

| Bestand | Wijziging |
|---|---|
| `app/tools/bnc_neural_training/package.py` | V4-export via het bestaande graphsysteem expliciet blokkeren |
| `app/tools/bnc_neural_training/v2/v3_phase_a/run.py` | Onafhankelijke stressprobe; app-afhankelijke evaluatie-imports uitstellen |
| `app/tools/bnc_neural_training/v2/v3_phase_b/run.py` | Updatecontroles, periodieke sentinels, gescheiden metingen/fouten en runner-provenance |
| `app/tools/bnc_neural_training/v2/training_safety.py` | Gedeelde numerical checks, observer, geïsoleerde probes en onderzoeksregressiecontrole |
| `app/tools/bnc_neural_training/v2/v4_research/__init__.py` | Nieuw onderzoekspakket |
| `app/tools/bnc_neural_training/v2/v4_research/model.py` | Nieuwe N/S-modellen en afzonderlijke architectuuridentiteit |
| `app/tools/bnc_neural_training/v2/v4_research/run.py` | Fresh start, strikte resume, corpus/resourcecontrole, kwalificatie en veilige checkpointopslag |
| `app/tools/bnc_neural_training/v2/v4_research/test_research.py` | 13 gerichte onderzoekstests |
| `app/tools/bnc_neural_training/v2/v4_research/verify_local.py` | Herhaalbare diagnose van echte A/C-checkpoints plus N/S-startprobes |
| `train-bnc-v4.ps1` | Afzonderlijke Windows-start/diagnose/validatie voor N en S |
| `Start-BnC-V4-Qualification.ps1` | Eén opdracht voor tests, CUDA-replay, twee 2k-sessies en resultaten-ZIP |

Geen wijzigingen aan de oorspronkelijke V3-modelgewichten, model/loss/data-kern, app-demosaicdispatch, Vulkan-shaders of gebruikersinstellingen. De installatiebackup staat in `build/bnc-neural-v2/v4-install-backups`.

De oorspronkelijke checkpointdiagnose en ruwe nieuwe test-/replaylogs zijn meegeleverd in `evidence`.
