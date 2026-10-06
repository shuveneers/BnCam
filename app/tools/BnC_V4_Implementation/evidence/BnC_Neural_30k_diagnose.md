# BnC Neural — onafhankelijke diagnose bij 30k

Datum: 1 oktober 2026. Status: **A pauzeren; C pauzeren; geen verdere oorspronkelijke training, export of Vulkan/productintegratie.**

## Uitkomst

De twee eerder gemelde probleemgevallen zijn opnieuw uitgevoerd met de aangeleverde V3-broncode en de 15k-, 29k- en 30k-checkpoints. De uitkomsten ondersteunen de gerapporteerde problemen. Het is geen uitsluitend cosmetisch rapportageprobleem. Het experiment wijzigde geen aangeleverde broncode of checkpoint. AdamW-updates gebeurden uitsluitend op wegwerpmodellen in geheugen; er is geen nieuwe training opgeslagen.

**A:** bij 29k en 30k groeit de onbegrensde multiplicatieve verwerking op de bekende geldige sample tot circa 10^16/10^15 in de gestructureerde output. Forward, loss en gradients blijven finite, maar één losse-sample AdamW-update maakt tweede-momenttensors niet-finite. Dit is een daadwerkelijk uitgevoerde diagnostische update, geen claim dat de oorspronkelijke opgeslagen optimizer al corrupt is.

**C:** de begrensde gate voorkomt die specifieke optimizer-overflow in deze tests, maar waarborgt geen correcte RGB-reconstructie. De stresstest is bij 29k al sterk verslechterd. De aparte frozen-validation kleur-edge ontspoort tussen 29k en 30k. De meetbare versterking zit in de opponent-route; de oorzaak van de trainingsdynamiek is nog niet volledig geïsoleerd.

## Bewijs en reproduceerbaarheid

- Bronnen: `bnc_neural_training.zip`, `training.zip`, `Tot aan setp 30k.zip`, en `train-bnc-v3.ps1`, door de gebruiker aangeleverd.
- Alle zes gebruikte checkpoint-SHA256-sidecars zijn gecontroleerd. Strict state loading en alle acht checkpoint objectiveIdentity-bronhashes komen overeen met de aangeleverde code.
- Gate-implementatiehash komt overeen met beide A/C identity.json-bestanden: `4cf3649d61e596a3b2a7eff186478dd098214aaa4962fe6607c0d773cc3b69dd`.
- Het exacte frozen-validation archive is gebruikt; SHA256: `62c51c87018c755b700f10a1624998e25e7a023cb9652041016212a1fac2b00b`.
- De stresstest gebruikt TrainingData met seed 823109 en index 1009779. Deze index kiest de procedural tak, zodat de natural fotodataset voor deze specifieke test niet nodig is. De oorspronkelijke CFA-keuze en encoding worden door de aangeleverde code bepaald.
- De kleur-edge is `analytic:validation:16:isoluminant_edge`, uit `rgb_16` van het oorspronkelijke archive, via de oorspronkelijke BGGR encode-functie. Dit is een andere fixture dan de algemene synthetische `isoluminant_color_edge`.
- Omgeving: 2.14.1+cpu, Python 3.12.14, CPU FP32, 2 threads, deterministische algoritmen. Geen GPU/CUDA aanwezig in dit experiment.
- Geen claim van bit-identieke oorspronkelijke CUDA-uitvoer. De gereproduceerde orde van grootte en metriekregressies sluiten nauw aan op de aangeleverde GPU-rapporten.
- De volledige natural corpus en de 64 natural validatiebeelden ontbreken; er is geen nieuwe volledige 116-case selectionScore of nieuwe test-holdout-beoordeling berekend. De procedural subset en de gerapporteerde totale score worden apart behandeld.

## Stresstest 1009779

Alle outputwaarden hieronder zijn maxima van de acht gestructureerde modelkanalen in de genormaliseerde /4-domeinrepresentatie. Het zijn geen uiteindelijke display-RGB-waarden. Sommige opponent-kanalen zijn signed; alleen het teken of een kleine overshoot is geen afkeurcriterium.

| Model | Stap | Output max | Loss | Gradient max | AdamW vóór update | AdamW na één geheugenupdate |
|---|---:|---:|---:|---:|---|---|
| A | 15000 | 1.113585 | 0.1273263 | 0.06083006 | finite | finite |
| A | 29000 | 1.325178e+16 | 5.093732e+28 | 2.188915e+31 | finite | niet-finite |
| A | 30000 | 3.520531e+15 | 1.029076e+28 | 6.133185e+30 | finite | niet-finite |
| C | 15000 | 1.103809 | 0.1282148 | 0.07536277 | finite | finite |
| C | 29000 | 11.41401 | 2.897171 | 22.14632 | finite | finite |
| C | 30000 | 9.795136 | 3.657443 | 27.21117 | finite | finite |

De modelparameters bleven na deze enkele testupdate bij alle zes tests finite. Bij A29k/A30k werden respectievelijk 70 en 68 optimizer-tensors (`exp_avg_sq`) niet-finite. Alleen finite modelparameters controleren mist deze fout. De gebruikte optimizer is de uit het betreffende checkpoint geladen AdamW-state. Dit is één sample, geen reconstructie van een oorspronkelijke batch van 32, en er is geen trainingsvervolg uitgevoerd.

De opgeslagen 30k-checkpoints hebben nextSampleIndex=960000; de bekende sample ligt verder in de deterministische volgorde. De uitkomst toont kwetsbaarheid van de huidige gewichten. Zij voorspelt niet exact met welke gewichten de toekomstige batch zou worden verwerkt.

Bij A30k groeien de gate-producten achtereenvolgens van shared.4 ≈62 en shared.5 ≈483 naar opponent.0 ≈4,29e4, opponent.1 ≈2,57e8 en opponent.2 ≈1,31e17. Dat is versterking door de modelroute, geen ongeldig inputbereik. De fout zit ook in het binnengebied van de afbeelding, niet alleen aan de paddingrand.

Bij C30k blijft de gate-uitvoer begrensd tot 16, maar het laatste opponent-block bereikt een raw product ≈4862; circa 77,49% van de productposities in die block heeft een lokale tanh-afgeleide <0,01. De structured output max is toch ≈9,80. Projecties, residual optellingen en output-heads worden door de gate-cap niet automatisch begrensd. Dit is beschrijvende diagnostiek, geen bewijs dat alleen tanh-saturatie de oorzaak is.

## Frozen isoluminante kleur-edge

| Model | Stap | Missing RGB L1 | RG RMS fout | BG RMS fout | Opponent-head max |
|---|---:|---:|---:|---:|---:|
| A | 15000 | 0.00534275 | 0.00979282 | 0.01920218 | 0.8782383 |
| A | 29000 | 0.00851840 | 0.00974131 | 0.01994464 | 0.7575462 |
| A | 30000 | 0.00610056 | 0.01012201 | 0.01770155 | 0.7820771 |
| C | 15000 | 0.00572739 | 0.01025843 | 0.02081928 | 0.8953246 |
| C | 29000 | 0.00636128 | 0.00879797 | 0.01872109 | 0.8195608 |
| C | 30000 | 0.05990671 | 0.14643425 | 0.14176033 | 1.724166 |

C30k missing RGB L1 is circa 9,42× die van C29k en 10,46× die van C15k. De green-head blijft op deze case beperkt; de opponent-route groeit sterk. Op C30k heeft opponent.1 raw-product max ≈20,51 en block-output max ≈11,77. Opponent.2 bereikt raw-product max ≈573,53, met circa 15,58% sterk verzadigde productposities. Op C29k blijven alle raw-product maxima op deze edge onder circa 3,04.

Er zijn uitsluitend in-memory inferenceproeven uitgevoerd waarin één parametergroep uit 29k in 30k werd gezet en omgekeerd. Een 29k shared-route in het 30k-model verlaagt de casefout tot circa 0,00474; alleen opponent.2 terugzetten verlaagt de fout onvoldoende, tot circa 0,04675. Geen afzonderlijke 30k-parametergroep veroorzaakt in het 29k-model dezelfde grote ontsporing. Dit wijst op een interactie tussen featurevorming en opponent-verwerking. Deze gemengde modellen zijn geen getrainde kandidaten, zijn niet opgeslagen en zijn geen voorgestelde productfix.

## Alle 52 frozen procedural cases

De exacte bestaande scenes zijn behouden; er zijn geen cases verwijderd of drempels aangepast.

| Model | Stap | Gemiddelde missing RGB L1 | Gemiddelde oorspronkelijke total loss |
|---|---:|---:|---:|
| A | 15000 | 0.00291896 | 0.00667072 |
| A | 29000 | 0.00307644 | 0.00695639 |
| A | 30000 | 0.00299281 | 0.00679046 |
| C | 15000 | 0.00295695 | 0.00679071 |
| C | 29000 | 0.00309249 | 0.00699933 |
| C | 30000 | 0.00414040 | 0.00885446 |

Op deze subset verslechtert A15k→30k gemiddeld circa 2,53% in missing RGB L1; C circa 40,02%. Dit wijkt niet noodzakelijk af van verbetering van een andere metriek over alle 116 cases. De C kleur-edge verklaart circa 88% van de netto gemiddelde L1-toename in deze subset, maar 30 van 52 cases hebben een hogere L1 dan op 15k (diagnostische vergelijking, geen formele 30-case afkeuring). De edge uit de beoordeling verwijderen zou de fout verbergen.

## Gevonden runnerproblemen

Bestand: `app/tools/bnc_neural_training/v2/v3_phase_b/run.py`.

1. `stats["numericalFailures"]` wordt op nul geïnitialiseerd en in deze runner nooit verhoogd. Bij fouten wordt een exception gegooid en eindigt de run. Een succesvolle eindrapportage met nul kan daarom niet als zelfstandige telling van numerieke incidenten worden gelezen.
2. De globale `stats` combineert trainingsmaxima, fixed-validationmaxima en de dry stress backward. `TrainingRawObserver` wordt vóór de stresstest verwijderd; `model.peaks()` blijft opgelopen maxima bewaren en bevat de stresstest wel. Daardoor hebben `maxRawProduct` en `maxActualGateOutput` in één rapport verschillende scope.
3. `failed_probe` controleert finite forward/loss/gradients en doet geen optimizer-update. Daarmee kan een model dat het A29k/A30k-probleem heeft toch `finite=True` krijgen.
4. Na `optimizer.step()` wordt optimizer/model-finiteness pas bij checkpointmomenten gecontroleerd (elke 1000 stappen en einde sessie). Een ongeldige update kan daardoor tijdelijk in het live model blijven bestaan vóór de run daadwerkelijk stopt.
5. De bekende stresstest en full validation gebeuren aan het einde van een gevraagde sessie. Er is geen kwaliteitstop op de sterke C-edge-regressie; `productReady=False` verhindert productactivering, maar voorkomt slechte verdere onderzoeksupdates niet.
6. De V3-gatebron is gehasht; de run-/rapportagecode is niet opgenomen in de acht `objectiveIdentity`-hashes. Voeg afzonderlijke runner/evaluator-provenance toe wanneer diagnostiek wordt aangepast, zonder de oorspronkelijke checkpointidentiteit te vervalsen.

## Concreet voorgesteld vervolg — nog niet geïmplementeerd

**Fase 1: runner en rapportage betrouwbaar maken.** Alleen na toestemming de lokale broncode wijzigen; geen push en geen automatische training.

| Bestand/integratiepunt | Voorgestelde wijziging | Verificatie |
|---|---|---|
| `v2/v3_phase_b/run.py` — stats, observers, report | Scheid training / fixed validation / known stress. Reset of gebruik observers per scope. Bewaar echte failure-events met attempted step, last valid checkpoint en oorzaak. | Zelfde weights/inputs houden dezelfde output; scopewaarden worden niet meer gemengd. |
| `v2/v3_phase_a/run.py` — `failed_probe` of afzonderlijke diagnostische module | Voeg afzonderlijke forward/backward/stress-state-status toe; optionele AdamW-proef alleen op een onafhankelijke kopie. Maak duidelijk dat single-sample geen echte batch is. | A29k/A30k wordt als optimizer-kwetsbaar gemarkeerd; C blijft finite maar krijgt reconstructiefoutdiagnostiek. |
| `v2/v3_phase_b/run.py` — update/checkpoint boundary | Verifieer model en optimizer direct na updates. Valideer last-valid status vóór een checkpoint als hervatbaar wordt gepubliceerd. Bewaar failure-info zonder vorige geldige checkpoint te overschrijven. | Geïnjecteerde ongeldige update wordt meteen afgevangen; vorige checkpoint-hash blijft behouden. |
| `v2/v3_phase_b/run.py` — periodieke evaluatie/stop | Voeg vooraf afgesproken korte sentinels toe voor bekende stress en frozen kleur-edge, plus bron/probe/evaluator-hashes. Behandel numerieke status en kwaliteitsstatus afzonderlijk. | De huidige regressies stoppen een onderzoeksproef; measured-sensel contract en originele fixtures blijven behouden. |
| `v2/v3_phase_b/test_runner.py` / gerichte probe-tests | Test failure-counter, scope-isolatie, checkpointbehoud en regressiedetectie. Geen tests die alleen de implementatie spiegelen. | Gerichte tests op daadwerkelijke eerder gereproduceerde faalmodi. |

Finite-controles alleen lossen A niet op. Een pre-update controle op tweede-moment-overflow of een expliciet ontworpen gradientbehandeling moet als aparte beslissing worden behandeld. De uitgevoerde single-sample AdamW-proef bewijst die specifieke faalmodus; het exacte CUDA/batch-gedrag moet lokaal bevestigd worden.

**Fase 2: oorzaak en kandidaatkeuze.** Behoud C15k als vergelijking; C29k is nuttig om de edge-regressie te begrenzen maar is door de slechte stresstest geen bewezen veilige hervatbasis. Gebruik de 29k/30k-vergelijking, block-observers en korte onafhankelijke onderzoeksruns om de instabiliteit in feature/opponent-verwerking te onderzoeken.

Een cap verlagen, gradient clipping toevoegen, LR veranderen, loss wijzigen, fixtures weglaten of blind naar 100k lopen is geen bewezen oplossing. Als een model-/optimizerwijziging wordt gekozen, geef de onderzoeksrun een nieuwe identiteit en behandel overgenomen optimizerstate expliciet. Oude A/C-artifacts blijven intact.

**Fase 3: korte gecontroleerde proef.** Pas na goedkeuring van de concrete wijziging en criteria: afzonderlijke outputmap, vaste data/loss/fixtures waar die niet onderwerp van de proef zijn, korte evaluatie-intervallen, rollback naar geldige checkpoints en rapportage per case. De volledige natural validatie en echte batchreplay vragen het lokale corpus of uitvoering op de bestaande trainingscomputer. Geen voorstel om de dataset opnieuw te uploaden zolang lokale uitvoering volstaat.

**100k/product:** 100k blijft een trainingsmijlpaal, geen kwaliteitsbewijs. Geen modelpackage/Vulkan-activering tot standalone reconstructie, numerieke stabiliteit, kleuren/detailcriteria, CFA-geometry en exact gemeten-sensel behoud slagen. Daarna pas gewichtsexport, FP32-reference/Vulkan-/tiling-/devicevalidatie en vervolgens 3-way Auto Hybrid met Malvar en AMaZE. BnC Neural blijft een directe Bayer→scene-linear RGB demosaic; een Malvar/AMaZE-gebaseerde denoiser zou dit doel niet vervullen.

## Uitgevoerde checkpoints

| Model | Stap | SHA256 |
|---|---:|---|
| A | 15000 | `c7391490056350c9d8120a5c23e390fd64d7f3651fc5af99847a7a3bd4e7ef09` |
| A | 29000 | `00938dd77132d8cbc009e3aa2ad98b6d7854ec6339cd258ca5035402cd0e73c4` |
| A | 30000 | `a39c0c4abf48f743acd68aa4e75b34aa1a762cd6060e4bb9b4983da907b7178d` |
| C | 15000 | `9f72fc4e58c2257b3d80efd5f14524f774884b80c3739afa8e6a16ab6e0fed46` |
| C | 29000 | `ca950776a67f85e434d13f9fe12fae9b3d6f5a5954e58f8d64eb2de13420896f` |
| C | 30000 | `58c3c965ca9a89e41bd429819c7ce733d2c38a3b53de32a2bb753f7d98ce436a` |

## Replayscript (diagnostische kopie)

Dit script verwacht de uitgepakte aangeleverde bronmap in `diagnosis/source/bnc_neural_training`, de originele checkpoints in `training-30k/{A,C}/checkpoints` en de drie archivebestanden in `diagnosis/fixed-validation`. Het importeert alleen helpers/modelcode en start geen trainingsrunner. Installeer PyTorch/numpy/Pillow in een aparte omgeving. Het schrijft uitsluitend diagnostische JSON naast zichzelf.

```python
"""Read-only checkpoint diagnosis; any optimizer update is in memory only."""
import sys, json, hashlib, platform, copy, math
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent / 'source'))
import torch
import numpy as np
from bnc_neural_training.v2.v3_phase_a.model import PhaseModel, PhaseBlock
from bnc_neural_training.v2.data import TrainingData, encode
from bnc_neural_training.v2.losses import reconstruction_loss, diagnostics
from bnc_neural_training.v2.checkpoint import objective_identity

torch.set_num_threads(2)
torch.use_deterministic_algorithms(True)
BASE = Path(__file__).resolve().parent.parent
OUT = BASE / 'diagnosis'

def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def finite(v):
    if isinstance(v, torch.Tensor): return bool(torch.isfinite(v).all())
    if isinstance(v, dict): return all(finite(x) for x in v.values())
    if isinstance(v, (tuple, list)): return all(finite(x) for x in v)
    return True
def clean(v):
    if isinstance(v, float) and not math.isfinite(v): return str(v)
    if isinstance(v, dict): return {k:clean(x) for k,x in v.items()}
    if isinstance(v, (tuple,list)): return [clean(x) for x in v]
    return v
def peak(v): return float(v.detach().abs().amax())

def state_for(c, step):
    name = 'recovery_step_029000.pt' if step == 29000 else f'stop_step_{step:06d}.pt'
    p = BASE / 'training-30k' / c / 'checkpoints' / name
    assert digest(p) == p.with_suffix('.pt.sha256').read_text().strip()
    s = torch.load(p, map_location='cpu', weights_only=True)
    assert s['step'] == step
    assert s['objectiveIdentity'] == objective_identity(), 'source/objective mismatch'
    assert s['provenance']['generatorReliabilityMigration']['validationArchiveSHA256'] == digest(OUT / 'fixed-validation/fixed-validation-procedural.npz')
    m = PhaseModel(c).eval()
    assert s['modelIdentity'] == m.config.description()
    m.load_state_dict(s['model'], strict=True)
    return m, s, dict(checkpoint=str(p.relative_to(BASE)), sha256=digest(p), step=step)

def measure(m, p, t, c, backward=False, optimizer_state=None):
    rows, handles = {}, []
    def expanded(name):
        def hook(module, inputs, output):
            a,b=output.detach().chunk(2,dim=1);raw=a*b
            g=raw if c=='A' else 16*torch.tanh(raw/16)
            rows[name] = dict(inputMax=peak(inputs[0]), aMax=peak(a),bMax=peak(b),
                rawProductMax=peak(raw),gateMax=peak(g),
                fractionAbsRawOver16=float((raw.abs()>16).float().mean()),
                fractionTanhDerivativeBelow001=float((raw.abs()/16 > 2.993222846).float().mean()) if c=='C' else None)
        return hook
    def blockout(name):
        def hook(module, inputs, output): rows[name]['blockOutputMax']=peak(output)
        return hook
    for name,b in m.named_modules():
        if isinstance(b,PhaseBlock):
            handles.append(b.expand.register_forward_hook(expanded(name)))
            handles.append(b.register_forward_hook(blockout(name)))
    m.zero_grad(set_to_none=True)
    try:
        o=m(p)
        loss,terms,rgb=reconstruction_loss(p,o,t,True)
        e=(rgb-t).detach().abs()
        result=dict(structuredOutputMax=peak(o),greenHeadMax=peak(o[:,:2]),
            opponentHeadMax=peak(o[:,2:]),rgbMax=peak(rgb), loss=float(loss.detach()),
            finiteForwardLoss=finite((o,loss,terms,rgb)),
            missingAndMeasuredErrorMax=float(e.amax()), interiorRgbErrorMax=float(e[:,:,42:-42,42:-42].amax()),
            lossTerms={k:float(v.detach()) for k,v in terms.items()},
            metrics=diagnostics(p,o,t,True), blocks=rows)
        if backward:
            loss.backward()
            grads={n:v.grad for n,v in m.named_parameters() if v.grad is not None}
            result['gradientFinite']=finite(grads)
            result['gradientMax']=max(peak(v) for v in grads.values())
            result['gradientNormFP64']=math.sqrt(sum(float(v.detach().double().square().sum()) for v in grads.values()))
            result['gradientPeaksByParameter']={k:peak(v) for k,v in grads.items()}
            if optimizer_state is not None:
                opt=torch.optim.AdamW(m.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
                opt.load_state_dict(copy.deepcopy(optimizer_state))
                result['adamWBeforeFinite']=finite(opt.state_dict()['state'])
                before={n:v.detach().clone() for n,v in m.named_parameters()}
                opt.step()
                result['adamWAfterOneInMemoryUpdateFinite']=finite(opt.state_dict()['state'])
                result['modelAfterOneInMemoryUpdateFinite']=finite(m.state_dict())
                result['nonfiniteOptimizerTensors']=[f'{key}:{name}' for key,row in opt.state_dict()['state'].items() for name,v in row.items() if not finite(v)]
                result['maxParameterChange']=max(peak(v-before[n]) for n,v in m.named_parameters())
    finally:
        for h in handles:h.remove()
        m.zero_grad(set_to_none=True)
    return result

archive=OUT/'fixed-validation/fixed-validation-procedural.npz'
fixed=np.load(archive,allow_pickle=False)
labels=json.loads((OUT/'fixed-validation/validation-manifest.json').read_text())['cases'][64:]
p,t=TrainingData([],1,823109,1009779)[0]
stress=(p.unsqueeze(0),t.unsqueeze(0))
case=encode(fixed['rgb_16'],'BGGR')
edge=(case[0].unsqueeze(0),case[1].unsqueeze(0))
result=dict(environment=dict(torch=torch.__version__,python=platform.python_version(),device='CPU',threads=2,
    caveat='CPU FP32 diagnostic replay; not the original CUDA runtime or training batch. No source/checkpoint writes or persisted optimizer updates.'),
    inputIdentities=dict(archiveSHA256=digest(archive),stressIndex=1009779,stressSeed=823109,case=labels[16],
    stressPackedSHA256=hashlib.sha256(stress[0].numpy().tobytes()).hexdigest(),
    edgeRGBSHA256=hashlib.sha256(fixed['rgb_16'].tobytes()).hexdigest()), replays=[])
for c in 'AC':
    for step in (15000,29000,30000):
        m,s,identity=state_for(c,step)
        row=dict(candidate=c,identity=identity,
            stress=measure(m,*stress,c,backward=True,optimizer_state=s['optimizer']))
        m,s,_=state_for(c,step)
        row['fixedEdge']=measure(m,*edge,c,backward=True)
        result['replays'].append(row)
        (OUT/'replay-results.json').write_text(json.dumps(clean(result),indent=2))
        print(json.dumps(clean(dict(candidate=c,step=step,stressOutput=row['stress']['structuredOutputMax'],
            stressGradient=row['stress']['gradientMax'],optimizerFiniteAfter=row['stress']['adamWAfterOneInMemoryUpdateFinite'],
            edgeMissingRgb=row['fixedEdge']['metrics']['missing_rgb_l1'],edgeOutput=row['fixedEdge']['structuredOutputMax']))),flush=True)
print('REPLAY_COMPLETE',flush=True)
```

## Script voor 52-case subset en exploratieve in-memory substituties

```python
"""Frozen procedural validation and diagnostic inference-only block swaps."""
import sys,json,hashlib,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent/'source'))
import torch,numpy as np
from bnc_neural_training.v2.v3_phase_a.model import PhaseModel
from bnc_neural_training.v2.checkpoint import objective_identity
from bnc_neural_training.v2.data import encode
from bnc_neural_training.v2.losses import diagnostics
torch.set_num_threads(2)
torch.use_deterministic_algorithms(True)
base=Path(__file__).resolve().parent.parent
out=base/'diagnosis'
z=np.load(out/'fixed-validation/fixed-validation-procedural.npz',allow_pickle=False)
labels=json.loads((out/'fixed-validation/validation-manifest.json').read_text())['cases'][64:]
def load(c,step):
    name='recovery_step_029000.pt' if step==29000 else f'stop_step_{step:06d}.pt'
    p=base/'training-30k'/c/'checkpoints'/name
    assert hashlib.sha256(p.read_bytes()).hexdigest()==p.with_suffix('.pt.sha256').read_text().strip()
    s=torch.load(p,map_location='cpu',weights_only=True)
    assert s['objectiveIdentity']==objective_identity()
    m=PhaseModel(c).eval();m.load_state_dict(s['model'],strict=True)
    return m,s
def evaluate(m,rgb):
    p,t=encode(rgb,'BGGR');p,t=p[None],t[None]
    with torch.no_grad():return diagnostics(p,m(p),t,True)
results=dict(scope='52 exact archived procedural cases; natural 64 cases unavailable; CPU FP32',rows=[])
for c in 'AC':
 for step in (15000,29000,30000):
    m,s=load(c,step);rows=[]
    for i,label in enumerate(labels):rows.append(dict(case=label,**evaluate(m,z[f'rgb_{i:02d}'])))
    result=dict(candidate=c,step=step,cases=rows,
      meanMissingRgb=float(np.mean([r['missing_rgb_l1'] for r in rows])),
      meanTotalLoss=float(np.mean([r['total_loss'] for r in rows])),
      worstMissingRgb=sorted([(r['missing_rgb_l1'],r['case']) for r in rows],reverse=True)[:5])
    results['rows'].append(result)
    (out/'procedural-scan.json').write_text(json.dumps(results,indent=2))
    print(json.dumps({k:v for k,v in result.items() if k!='cases'}),flush=True)

# Exploratory parameter substitutions in RAM, never a proposed trained model.
edge=z['rgb_16'];m29,s29=load('C',29000);m30,s30=load('C',30000)
groups=('stem.','shared.','green.','opponent_input.','opponent.0.','opponent.1.','opponent.2.','opponent_output.')
swaps=[]
for direction,src,target in [('30k_with_29k_group',s29,s30),('29k_with_30k_group',s30,s29)]:
 for group in groups:
    state=dict(target['model']);state.update({k:v for k,v in src['model'].items() if k.startswith(group)})
    m=PhaseModel('C').eval();m.load_state_dict(state,strict=True)
    swaps.append(dict(direction=direction,group=group,metrics=evaluate(m,edge)))
results['exploratoryGroupSwaps']=swaps
(out/'procedural-scan.json').write_text(json.dumps(results,indent=2))
print('SCAN_COMPLETE',flush=True)
```
