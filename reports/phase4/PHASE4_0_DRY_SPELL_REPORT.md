# PHASE 4.0 — Dry-Spell Risk Architecture Validation Report

Date: 2026-09-21. Scope: cheapest architecture gate ONLY — soil aggregation,
CHIRPS labels, fixed transparent rule, perfect-forecast upper bound,
HISTORICAL GEFS PSEUDO-FORECAST test. No ML, no Java, no frontend, no API,
no downloads, no network. Code: `src/risk/` (common, build_soil, labels,
backtest, test_risk). Rule fixed a priori, zero fitted parameters.

## 1. Executive Summary

**VERIFIED:** Soil aggregates reproducibly (6 blocks, 10,447 px each,
reproduces existing values to rounding). JJAS dry spells are well-posed
targets (L=7: 193 runs/16y, ~1.85 starts/block-year train, median 10 d).
Perfect-forecast rule PASSES the gate (recall 0.668, precision 0.876).
**HISTORICAL GEFS PSEUDO-FORECAST MISSES the recall gate by 2.1pp
(recall 0.579 vs 0.60 required; precision 0.759 passes)**, stable across a
second fold (2016–2019: recall 0.568). **Decision: NO-GO for expanding this
dry-spell architecture.** No ML, no tuning, stopped after this report.

## 2. Git Safety / Pre-existing Changes (VERIFIED)

HEAD `0351f46` (unchanged, verified before any edit). Pre-existing,
protected and untouched: `M notebooks/03_feature_engineering.ipynb`,
`M docs/project_context/07_CURRENT_DECISIONS.md` (now +Decision 27 only),
untracked Phase 1B leftovers (`.kilo/`, `data/processed/phase1b/`,
`reports/phase1b/`, `requirements-phase1b.txt`,
`src/data/build_{ifsens,raw_gefs}_leads.py`, `src/evaluation/`,
`verify_audit.py`), Phase 3C files (`src/w3w4/`, `data/processed/w3w4/`,
`reports/phase3/PHASE3C_CHECKPOINT0_REPORT.md`), Phase 3B/weather code.
Nothing committed, pushed, reset, or cleaned.

## 3. Data Used (VERIFIED)

* CHIRPS `data/raw/rainfall/...2010_2025.csv`: 35,064 rows, 6 blocks,
  2010-01-01..2025-12-31, 0 NaN (asserted in code).
* GEFS pseudo-forecast `data/processed/historical/historical_gefs_leads_v2.csv`:
  1098 issues x 42 rows (6 blocks x leads 1–7), 2016-06-01..2025-09-30,
  2020 absent (asserted), 0 NaN forecasts. Explicitly NOT live IFS.
* Soil: 5 SoilGrids 0–5 cm GeoTIFFs (incl. `pH_0-5cm_mean.tif.tif` typo),
  EPSG:4326 ~250 m; boundaries `sangrur_blocks_bhuvan.gpkg` layer
  `sangrur_blocks` (6 legacy blocks, OGC:CRS84; geometry matched to raster CRS).

## 4. Soil Aggregation Results (VERIFIED)

`data/processed/risk/block_soil_context.csv` + `method_soil.json`
(paths, sha256, CRS, resolution, method, timestamp). Six blocks, no missing
fields, 10,447 valid pixels per block per variable. Ranges sane
(clay 246–292, sand 293–415, silt 310–386 g/kg; SOC 10.9–12.8 g/kg;
pH 7.65–7.87). **Recomputed values match `final_ml_dataset.csv` to
rounding (all deltas 0.000)** — provenance confirmed, no silent
substitution. Spread is small: soil can only ever be a tie-breaker.

## 5. Dry-Spell Definition (fixed, no tuning)

Run of >= L consecutive days with rain < 1.00 mm (1.00 mm is NOT dry;
NaN breaks runs, never zero). Primary L=7, sensitivity L=5/10 (only the
duration criterion changes). Label per (block, run start) with JJAS flag.

## 6. JJAS Event Frequency (VERIFIED)

| L | total runs | JJAS | train JJAS | eval JJAS | starts/block-yr (train) | median dur |
|---|----|----|----|----|----|----|
| 5 | 1083 | 319 | 176 | 108 | 2.93 | 8 d |
| 7 | 836 | 193 | 111 | 57 | 1.85 | 10 d |
| 10 | 667 | 105 | 60 | 30 | 1.00 | 14 d |

Uniform across blocks (L7 JJAS: 30–37/block). Neither rare nor trivial.
Train weekly-issue prevalence (2010–2019): L5 0.430, L7 0.343, L10 0.239.

## 7. Perfect-Forecast Upper Bound (JJAS weekly Wed, eval 2021–2025, n=522)

Rule WARN = (dry_run through D-3 >= 3 AND forecast dry days in D+1..D+7 >= 5)
OR (forecast dry days >= 6). Antecedent strictly < D+1 (asserted).

| arm (L7, prev 0.364) | prec | rec | F1 | FAR | miss |
|---|---|---|---|---|---|
| RULE | **0.876** [0.76,0.96] | **0.668** [0.53,0.79] | 0.758 | 0.054 | 0.332 |
| ANTECEDENT_ONLY | 0.624 | 0.532 | 0.574 | 0.184 | 0.468 |
| ALWAYS / NEVER | 0.364 / — | 1.0 / 0.0 | — | — | — |

Forecast adds value over antecedent alone (+14pp recall, +25pp precision).
Onset-only diagnostic (spell started after D-3): recall 0.516, precision
0.338 — the rule catches continuations well, fresh onsets modestly.
CIs are issue-clustered bootstrap (2000 reps); blocks never treated as
independent. **Gate on perfect info: PASS.**

## 8. Historical GEFS Pseudo-Forecast (same issues/forecast D+1–7 from GEFS)

| arm (L7, prev 0.364) | prec | rec | F1 | FAR | miss |
|---|---|---|---|---|---|
| RULE | **0.759** [0.61,0.89] | **0.579** [0.42,0.74] | 0.657 | 0.105 | 0.421 |
| ANTECEDENT_ONLY | 0.624 | 0.532 | 0.574 | 0.184 | 0.468 |
| ALWAYS (F1 0.534) / NEVER | — | — | — | — | — |

Rule still beats antecedent-only and both trivial baselines, but recall
falls 0.668 -> 0.579 through forecast error. Confirmatory fold GEFS
2016–2019 (same frozen rule, n=414, prev 0.285): recall 0.568, precision
0.578 — the shortfall replicates; it is not sampling noise around the gate.
Pooled GEFS recall 0.575. Labeled HISTORICAL GEFS PSEUDO-FORECAST throughout;
never IFS. Sensitivity: L5 (rec 0.522/prec 0.910), L10 (rec 0.554/prec 0.566).

## 9. Baselines

NEVER (recall 0) and ALWAYS (precision = prevalence) both lose to RULE on F1
in every fold. Train-period event rate (L7 0.343) confirms eval prevalence
(0.364) is not regime-shifted. ANTECEDENT_ONLY is the binding comparator:
the forecast contributes, but not enough to clear the gate.

## 10. Metrics + CIs

Reported above (§7–8). All CIs issue-clustered; L7 GEFS recall CI
[0.42,0.74] includes 0.60 but the point estimate governs the pre-registered
gate, and the independent 2016–2019 fold lands at the same 0.57.

## 11. Block-Level Results (RULE L7, diagnostic)

Perfect 2021–25 recall 0.64–0.71 / precision 0.84–0.92, all blocks uniform.
GEFS 2021–25: Dhuri 0.60/0.75, Lehra 0.56/0.73, Malerkotla 0.57/0.80,
Moonak 0.55/0.67, Sangrur 0.59/0.83, Sunam 0.61/0.80 (n=87 each).
No block contradicts the pooled verdict; shortfall is systematic, not local.

## 12. Sensitivity 5/7/10 Days

L5: very precise (0.91 GEFS) but low recall (0.52) — long-window overlap is
hard for short spells. L10: balanced but weaker (0.55/0.57). L7 is the best
operating point and still misses recall. No definition rescues the gate.

## 13. Leakage Checks (all PASS, asserted in code + 9 unit tests green)

Antecedent indices end at D-3 (`max feat = i-3 < i+1 = min label`);
future CHIRPS used for labels only; train references strictly < 2020-01-01;
2020 excluded from GEFS; prob/NaN guards; D+1..D+7 window exact;
`pytest src/risk/test_risk.py`: 9 passed.

## 14. GO/NO-GO Decision: NO-GO

Pre-registered gate required recall >= 0.60 AND precision >= 0.40 on
2021–2025 JJAS with historical forecasts. Result: recall **0.579** (FAIL by
2.1pp / ~4 events), precision 0.759 (pass). The miss replicates in an
independent fold (0.568). **Phase 4.0 is NO-GO for expanding this dry-spell
architecture.** Per checkpoint discipline: no ML attempted, no thresholds
tuned, stopped after this report. The shortfall is forecast-driven, not
rule-driven (perfect-info passes) — recorded as evidence, not as license
to tune.

## 15. Limitations

GEFS-history != live IFS (transfer gap unquantified; needs a live shadow
period before any production claim). Weekly Wednesday issues only. Onset
detection weak by construction (persistence-heavy rule). Soil unused in the
rule (spread too small to matter at 6 blocks). No waterlogging/soil-moisture
ground truth exists, so C/D-type risks inherit this ceiling. D+8–16 risks
remain unvalidatable (no archive).

## 16. Exact Next Step

STOP expanding the dry-spell rule path. If Phase 4 is revisited, the only
sanctioned next checkpoint is a *pre-registered confirmatory evaluation*
(unchanged frozen rule, new data — e.g. live-IFS shadow comparison), never
threshold tuning on these eval folds and never ML on this signal. Artifacts:
`src/risk/` (common, build_soil, labels, backtest, test_risk),
`data/processed/risk/` (soil CSV+method, run CSVs x3, predictions x3,
metrics x3), this report. Repro: `python -m src/risk/build_soil`,
`python -m src/risk/labels`, `pytest src/risk/test_risk.py -q`,
`python -m src/risk/backtest --source perfect|gefs`.

(End of file)
