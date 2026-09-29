# PHASE 3A — Climatology + MJO Composite Gate + Validation Report

Date: 2026-09-19. Scope: validation foundation ONLY. No new ML trained, MJO CNN-LSTM
frozen, no API/service/frontend built, no large downloads. Repro scripts:
`src/climatology/build_normals.py`, `mjo_composites.py`, `backtest_w3w4.py`,
`make_notebook09.py` → `notebooks/09_phase3_climate_outlook_validation.ipynb`
(executed, 0 errors). Artifacts: `data/processed/climatology/`.

## 1. Data coverage

- CHIRPS block-daily `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv`:
  35,064 rows, 2010-01-01..2025-12-31, 6 blocks × 5844 d, 0 NaN, 0 dupes,
  no negatives, units mm. Every 0.0 is an observed no-rain day. Source NEVER modified.
- MJO `data/raw/mjo/MJO_RMM_cleaned_core.csv`: 18,802 rows, 1974-06-01..2026-09-07,
  phases 1–8, one 291-d gap (1978, never bridged). **Provenance NOT verified** (unchanged).

## 2. Climatology methodology

- Builder pools, per block × issue-DOY, all years × circular ±7 d window (only smoothing).
  Wet day ≥ 1.0 mm (project convention). Feb-29 pooled into DOY 60 (wheel has no
  other hole; coverage complete; doy-60 n=142 vs 150 elsewhere — documented asymmetry,
  negligible). Zeros never filled (nothing to fill).
- Outputs per row: `n_days, daily_mean_mm, wet_prob, w3_t33/t66_mm, w3_n, w4_t33/t66_mm,
  w4_n, jjas`. Two artifacts: `block_doy_normals_train_2010_2019.csv` (kind=train-frozen,
  span 2010-01-01..2019-12-31) and `block_doy_normals_full.csv` (kind=**deployment** —
  live use only, never a backtest artifact). Each ships a `method_*.json` with span,
  kind, and an explicit leakage statement.
- Sanity: JJAS daily means 0.5–8.7 mm; non-JJAS 0.0–1.5 mm. W3 window sums independently
  spot-checked (Sangrur Jun-01 issues: direct t33/t66 0.0/11.2 mm on 10 samples vs pooled
  0.2/14.1 mm on 150 — consistent given pooling). w3_n/w4_n 135–150 (Dec tail-edge losses,
  expected). Example: Sangrur mid-July (doy 200) mean 5.5 mm, wet_prob 0.69; mid-Jan (doy 15)
  mean 0.66 mm, wet_prob 0.08.
- LEAKAGE RULE (enforced in code): `--end-date D` uses dates strictly before D; backtests
  use only the train-frozen artifact; `assert max(train_issues) < 2020-01-01` in backtest.

## 3. W3/W4 target definitions

Issue D → W3 = Σ rain(D+17..D+23), W4 = Σ rain(D+24..D+30), per block. Labels vs the
TRAIN W3/W4 terciles for the issue DOY (frozen 7-day 10.94/34.66 mm thresholds NOT reused):
below if x ≤ t33, above if x > t66, else near (deterministic boundary rule).

## 4. MJO methodology

Active = amplitude ≥ 1.0 (Wheeler–Hendon). State at issue D (primary) and D-3
(operations-latency proxy). Lead scan offsets 3/10/17/24 (product needs 17/24).
Anomaly = window sum − train-climatology expected sum; pct relative to expected.
Fit scope for gate: train 2010–2019. Gate rule (pre-registered in script before running):
GO iff ≥2 phases with |mean pct| ≥ 15% AND 95% CI excludes 0 AND same sign in ≥5/6 blocks
AND same sign in 2010-2014 vs 2015-2019 halves (train JJAS, active, offset 17).

## 5. MJO phase/lag analysis (train JJAS, active)

| phase | n pooled | mean % | 95% CI (mm) | blocks agree | halves (mm) |
|---|---|---|---|---|---|
| 1 | 846 | +4.6 | [2.3, 7.2] | 6/6 | +9.0 / −1.6 ✗ |
| 2 | 654 | +19.2 | [3.8, 10.0] | 6/6 | +6.6 / +7.7 ✓ |
| 3 | 372 | −6.2 | [−2.0, 3.1] | 3/6 | +13.5 / −10.8 ✗ |
| 4 | 456 | −12.2 | [−4.6, −0.4] | 6/6 | −10.6 / +3.8 ✗ |
| 5 | 594 | −6.2 | [−4.4, −1.4] | 6/6 | −1.8 / −4.2 ✓ (|pct|<15 ✗) |
| 6 | 480 | −20.8 | [−11.5, −6.1] | 6/6 | −11.5 / −7.8 ✓ |
| 7 | 198 | −58.1 | [−25.5, −20.0] | 6/6 | −26.9 / −18.3 ✓ |
| 8 | 258 | −7.2 | [−2.4, 3.6] | 4/6 | −7.2 / +5.8 ✗ |

Gate: **GO (in-sample)** — qualifying phases **2, 6, 7**. Physically coherent with the
canonical MJO–monsoon teleconnection (wet phases 2–3, suppressed phases 6–7 over India),
6/6 block agreement at every lead, weak-MJO counterparts show opposite/muted signs
(phase 2 weak −13%, phase 6 weak +17% — amplitude matters, as theory says). Lead decay:
phase 2 grows to +38% at offset 24 (consistent with eastward propagation into phases 3–4);
phase 7 peaks at offset 17 (−58%) then weakens. Caveats: phase 7 n=198 pooled (~33/block);
pooled CIs overstate independence (blocks spatially correlated) — block agreement is the
stronger argument; gate is IN-SAMPLE (same train period as fit).

## 6. Backtest (test: weekly JJAS 2020–2025, 105 issues × 6 blocks = 630/arm/window)

| arm | W3 Brier | W3 RPS | W3 acc | W4 Brier | W4 RPS | W4 acc |
|---|---|---|---|---|---|---|
| CLIM (1/3,1/3,1/3) | 0.6667 | 0.4683 | 0.332 | 0.6667 | 0.4656 | 0.333 |
| CLIM+RECENT | 0.6740 (−1.1%) | 0.4750 | 0.271 | 0.6770 (−1.5%) | 0.4754 | 0.300 |
| CLIM+RECENT+MJO | 0.6896 (−3.4%) | 0.4888 | 0.262 | 0.6835 (−2.5%) | 0.4782 | 0.297 |

FULLYR (309 issues, n=1854): same story (skills −0.0%…−0.8%). MJO lag-3 ≈ lag-0 (latency
is not the cause). JJAS-only-fit + block-pooled-MJO sensitivity: still negative
(−0.5%…−2.0%). Reliability: candidates' P(above) bins miscalibrated (e.g. W3 0.4–0.6 bin:
mean_p 0.43, obs 0.29); test JJAS ran wetter than train terciles (obs above-freq 0.41 vs
1/3 — all arms underforecast). CLIM accuracy 0.33 JJAS is exact-chance; its FULLYR 0.41
is an argmax tie-break artifact (always predicts below; dry season obliges) — Brier is
the metric, and it is flat 0.6667 by construction.

## 7. Leakage checks

Fit maxima asserted < 2020-01-01; thresholds from train-frozen artifact only; trailing
window strictly D-14..D-1; MJO state at D/D-3 (never future); last issue 2025-12-01
(D+30 observed). PASS.

## 8. Final MJO GO/NO-GO: NO-GO for outlook mathematics

In-sample gate GO did **not** survive out-of-sample validation. Directional information
exists (sensitivity accuracy 0.35–0.36 > 0.30) but probability estimates are
miscalibrated → negative Brier/RPS skill twice (primary + sensitivity). **MJO remains
explanatory-only. Trailing-rainfall conditioning also fails → recent rainfall stays
display-only** (observed totals/anomalies shown, not wired into probabilities).

## 9. Limitations

16-yr normals (short for WMO-style); weekly issues n=630/arm (reliability bins coarse);
train/test regime shift possible (test JJAS wetter); product scope is JJAS (outside
monsoon, outlook is climatology-dominated by construction); MJO provenance unverified;
RMM latency unaddressed (no updater — out of 3A scope by design).

## 10. Exact recommendation for Phase 3B

Ship: frozen deployment climatology + observed trailing totals/anomalies as DISPLAY;
OutlookService serving W3/W4 tercile normals + recent-observed context + narrative with
confidence capped by input vintage. Do NOT ship: MJO/recent probability modifiers, any
new ML, IOD/ENSO math (unchanged: context-only). Revisit MJO math only via calibrated
probabilities on more data — never by tuning this result. Updater decision (manual SOP
vs fetch) is the precondition for anything called "live" in weeks 3–4.
