# PHASE 3C CHECKPOINT 0 — Validation Report (pre-screen, NO tuning)

Date: 2026-09-20. Question: can simple seasonal climatology or minimal
recent-rainfall persistence produce useful W3/W4 probabilistic skill beyond
flat 1/3 priors? Code: `src/w3w4/checkpoint0.py` (+ `test_checkpoint0.py`,
4 tests pass). No MJO/ENSO/IOD/soil/GEFS/ECMWF/live data used anywhere.

## 1. Experiment

* Target: per issue D, block: W3 = sum CHIRPS D+17..D+23, W4 = sum D+24..D+30;
  labels BELOW (x<=t33) / NEAR / ABOVE (x>t66) vs TRAIN-FROZEN
  `block_doy_normals_train_2010_2019.csv` thresholds (never deployment).
* Arms (fixed, no search): A FLAT (1/3 each); B SEASONAL P(W|block,issue-DOY)
  from TRAIN daily 2010–2019 empirical freqs, Laplace +0.5 (3A convention);
  C PERSIST_LR fixed multinomial LR (C=1.0, lbfgs) on [log1p(f7),log1p(f14)],
  f7 = D-9..D-3, f14 = D-16..D-3 (end D-3), train-standardized, fit on TRAIN
  JJAS daily 2010–2019 pooled, per window.
* Test: weekly (Wednesday, same cadence as 3A) JJAS primary + FULLYR
  secondary, 2020–2025, last issue 2025-12-01. JJAS n=105 issues x6=630
  rows/arm/window; FULLYR 309x6=1854. Uncertainty: issue-clustered bootstrap
  (2000 reps, seed 7) for Brier skill — weekly issues are dependent, 6 blocks
  are not independent series.

## 2. Results — W3 (JJAS, primary)

| arm | Brier | BSS vs FLAT [95% CI] | logloss | acc |
|---|---|---|---|---|
| FLAT | 0.6667 | — | 1.0986 | 0.332 |
| SEASONAL | 0.6703 | −0.0054 [−0.054, +0.045] | 1.1050 | 0.357 |
| PERSIST_LR | 0.6793 | −0.0189 [−0.045, +0.008] / −0.0134 vs SEASONAL | 1.1201 | 0.346 |

## 3. Results — W4 (JJAS, primary)

| arm | Brier | BSS vs FLAT [95% CI] | logloss | acc |
|---|---|---|---|---|
| FLAT | 0.6667 | — | 1.0986 | 0.333 |
| SEASONAL | 0.6666 | +0.0001 [−0.048, +0.051] | 1.0984 | 0.354 |
| PERSIST_LR | 0.6751 | −0.0126 [−0.038, +0.013] / −0.0128 vs SEASONAL | 1.1129 | 0.335 |

FULLYR (secondary): SEASONAL +1.26% (W3) / +1.74% (W4) vs FLAT — expected
dry-season artifact (correctly predicts BELOW outside monsoon), not monsoon
skill. PERSIST_LR FULLYR: −0.40% (W3), +1.23% vs FLAT / −0.52% vs SEASONAL (W4).

## 4. Calibration / blocks

* Reliability P(ABOVE), JJAS: SEASONAL 0.4–0.6 bin mean_p 0.483→obs 0.482
  (W3, n=83), 0.486→0.452 (W4, n=84) — locally calibrated but tiny mass;
  bulk stays in 0.2–0.4 bin near climatology. PERSIST_LR W4 0.4–0.6 bin
  0.41→0.231 (n=13): miscalibrated when confident.
* Block Brier (JJAS): no block shows consistent candidate gain; PERSIST_LR
  worse than FLAT in 11/12 block×window cells; SEASONAL within ±0.02 of FLAT
  everywhere (noise).
* Accuracy is descriptive only (FLAT FULLYR 0.41 is the argmax tie-break
  artifact documented in 3A); decision rests on Brier/logloss.

## 5. Leakage checks (all PASS, asserted in code)

Train issues < 2020-01-01; thresholds from train-frozen file only (filename
asserted); features end D-3 (window indices i-9..i-3, i-16..i-3 < i+17);
test issues >= 2020-01-01; prob rows sum to 1 (1e-9); labels span {0,1,2};
no MJO/ENSO/IOD/soil/GEFS/ECMWF import or reference in fit/predict path.

## 6. Decision: NO-GO

Neither SEASONAL nor PERSIST_LR shows credible positive out-of-sample JJAS
probabilistic skill: W3 skills negative, W4 SEASONAL +0.01% with CI
[−0.048,+0.051] (zero), PERSIST negative; logloss agrees in sign; no block
subgroup contradicts. The gate is not loosened for closeness — nothing is close.

## 7. Recommendation

STOP Phase 3C ML development. Keep Phase 3B display-only W3/W4 as
production. Revisit only via a pre-registered calibrated design on more
data/signal (Checkpoint 1 proposal), never by tuning this result.
Artifacts: `data/processed/w3w4/checkpoint0_{predictions.csv,metrics.json,
reliability.csv}` + `method_checkpoint0.json`. Repro: `python
src/w3w4/checkpoint0.py`; checks: `python -m pytest src/w3w4/test_checkpoint0.py -q`.

(End of file)
