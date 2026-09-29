# PHASE 4.1 — Excess Rainfall + Field-Work Disruption Validation Report

Date: 2026-09-21. Scope: two NEW transparent risk detectors ONLY (excess
rainfall, field-work disruption). No ML, no tuning, no Java, no frontend, no
API, no downloads, no network. Code: `src/risk/` (excess_rain, field_work,
backtest_41, test_phase41 — new files; Phase 4.0 files untouched). Phase 4.0
dry-spell rule and the live IFS shadow system are frozen and unchanged.

## 1. Executive Summary

**MIXED — one GO candidate, one NO-GO family.** FIELD-WORK HIGH
(≥2 wet days in D+1..D+3) validates well on historical GEFS pseudo-forecasts
(JJAS 2021–2025, n=522: precision 0.721 [0.61,0.83], recall 0.809
[0.70,0.90], F1 0.763), beats both baselines, uniform across blocks.
**Candidate for later production integration (not yet integrated).**
All three EXCESS components miss most events (recall 0.24–0.33, miss rate
0.67–0.76) despite ~2×-prevalence precision: **NO-GO as production
detectors; retained as diagnostic evidence only.** Nothing was tuned on eval;
weak detectors were not rescued.

## 2. Scope

VERIFIED: two detectors implemented, threshold-fit on train only, validated
on eval with fixed rules. NOT in scope (deferred): waterlogging/moisture
composites, planting/irrigation advice, crop claims, scoring, Java/API/
frontend, soil drivers, ML of any kind.

## 3. Data Used (VERIFIED)

* CHIRPS `data/raw/rainfall/...2010_2025.csv`: 35,064 rows, 6 blocks,
  2010-01-01..2025-12-31, 0 NaN (asserted in code).
* HISTORICAL GEFS PSEUDO-FORECAST `data/processed/historical/
  historical_gefs_leads_v2.csv` leads 1–7 (explicitly NOT live IFS; labelled
  as such in every artifact). 2020 absent (asserted), 0 NaN forecasts.
* Reference 2010–2019 / transition 2020 excluded / eval 2021–2025 JJAS weekly
  Wednesday issues (n=522 = 87 issues × 6 blocks), consistent with Phase 4.0.

## 4. Excess Rainfall Definition (PROPOSED a priori, frozen as excess-v1)

Three SEPARATE evidence components over forecast D+1..D+7 (never one score):
1. DAILY_EXTREME: max daily vs block×JJAS train p95 → `EXCESS_DAILY_P95`.
2. 3-DAY EXTREME: max rolling 3-day sum (5 windows) vs p90 → `EXCESS_3D_P90`.
3. 7-DAY EXTREME: 7-day accumulation vs p90 → `EXCESS_7D_P90`.
Trigger = STRICTLY exceeds threshold (== does not trigger). Any missing day
in the span → component unavailable (None), never zero-filled. Each result
exposes block, issue_date, forecast_window, metric, forecast_value,
threshold, percentile, triggered, reason_code, method_version.

## 5. Field-Work Definition (PROPOSED a priori, frozen as field-v1)

Generic work only, no crop claims. Wet day: rain ≥ 1.00 mm (exact 1.00 IS
wet). Primary D+1..D+3 tier: HIGH (≥2 wet) / MODERATE (1) / LOW (0) —
`FIELD_WET_DAYS_2OF3` / `FIELD_WET_DAY_1OF3` / `FIELD_DRY_3D`. Secondary
D+1..D+7: wet-day count + heavy evidence, NO tier by design. Evidence flags:
`FIELD_HEAVY_RAIN` (window max > block JJAS daily_p95), PROPOSED
`FIELD_HIGH_PRECIP_PROB` (max forecast prob ≥ 70% — evidence only, never
observed rain). Tier prevalence check (train observed HIGH 0.499, eval 0.521):
fires ~half of JJAS windows — plausible for peak monsoon, NOT tuned.

## 6. Historical Thresholds (VERIFIED train-only)

`data/processed/risk/excess_thresholds_v1.json` (excess-v1, fit 2010–2019
JJAS, max fit date asserted < 2020-01-01, n=1220/window/block):

| block | daily_p95 | 3d_p90 | 7d_p90 |
|---|---|---|---|
| Dhuri | 19.136 | 35.116 | 79.842 |
| Lehra | 18.734 | 32.564 | 74.743 |
| Malerkotla | 20.655 | 35.349 | 80.820 |
| Moonak | 17.602 | 32.139 | 76.488 |
| Sangrur | 21.151 | 38.978 | 85.935 |
| Sunam | 20.107 | 35.715 | 78.698 |

Block-specific by design (spread 17.6–21.2 daily); support sizes reported,
no silent pooling. Reproducible: `python -m src.risk.excess_rain
--build-thresholds` (unit test asserts rebuild equality).

## 7. Ground Truth (VERIFIED, future CHIRPS only)

EXCESS DAILY: any CHIRPS D+1..D+7 day > block p95. EXCESS 3-DAY: max
rolling-3 sum inside D+1..D+7 > p90. EXCESS 7-DAY: 7-day total > p90.
FIELD D+1..D+3: disrupted iff ≥2 observed wet days (≥1mm); wet count + max
retained. Train-period reference rates (2010–2019 Wed JJAS): excess
0.239/0.244/0.096; field HIGH 0.499 — eval prevalence (0.301/0.324/0.088;
0.521) is not regime-shifted.

## 8. Historical GEFS Validation (VERIFIED, labelled GEFS — NOT IFS)

| detector (n=522) | prev | prec [CI] | rec [CI] | F1 | FAR | miss |
|---|---|---|---|---|---|---|
| EXCESS_DAILY | 0.301 | 0.585 [0.36,0.80] | 0.242 [0.11,0.38] | 0.342 | 0.074 | 0.758 |
| EXCESS_3D | 0.324 | 0.598 [0.40,0.79] | 0.325 [0.17,0.49] | 0.422 | 0.105 | 0.675 |
| EXCESS_7D | 0.088 | 0.325 [0.00,0.68] | 0.283 [0.00,0.57] | 0.302 | 0.057 | 0.717 |
| FIELD_HIGH | 0.521 | 0.721 [0.61,0.83] | 0.809 [0.70,0.90] | 0.763 | 0.340 | 0.191 |

CIs issue-clustered bootstrap (2000 reps); blocks never independent.
FIELD tier agreement 0.638 (pred HIGH/LOW/MOD 305/159/58 vs obs 272/151/99).

## 9. Baselines (VERIFIED)

Every binary event vs NEVER (recall 0) and ALWAYS (precision = prevalence):
excess detectors beat NEVER on F1 (0.30–0.42 vs null) and ALWAYS on
precision (0.33–0.60 vs 0.09–0.32) but lose to ALWAYS on F1 (0.46–0.49) —
selective but incomplete. FIELD_HIGH beats both: F1 0.763 vs ALWAYS 0.685,
precision 0.721 vs 0.521 with recall 0.809.

## 10. Metrics

Reported above (§8) via `src/risk/common.py` contingency (TP/FP/TN/FN,
precision, recall, F1, FAR, miss rate). Artifacts:
`data/processed/risk/backtest_41_{excess,field}_{predictions.csv,metrics.json}`.

## 11. Confidence Intervals

§8 table. Excess CIs are wide (7D precision CI touches 0 — rare-event
instability reported, not hidden). FIELD CIs are tight and exclude
trivial baselines (F1 CI [0.67,0.84] vs ALWAYS 0.685).

## 12. Block Results (diagnostic, no independence claimed)

FIELD_HIGH uniform: recall 0.77–0.88 / precision 0.67–0.75 all six blocks.
EXCESS recall systematically low everywhere (daily 0.18–0.30, 3D 0.29–0.36,
7D 0.25–0.38); shortfall is systematic, not local. Full tables in metrics JSONs.

## 13. Sensitivity / Boundary Checks (VERIFIED in 15 unit tests)

== threshold does not trigger; 1.00mm IS wet / 0.99 dry-adjacent not wet;
rolling-3 max over exactly 5 windows; 7d sum exact; block-specificity
(19mm triggers Moonak, not Sangrur); rebuild-equality of thresholds.

## 14. Leakage Checks (all PASS, asserted in code + tests)

Threshold fit max date < 2020-01-01; detectors take forecast arrays only
(source-inspected: no CHIRPS/observed reference); features use D+1..D+7
forecasts, labels use future CHIRPS; 2020 excluded from GEFS; prob flag
never touches observed fields; GEFS/IFS namespaces asserted separate in
metrics JSONs. `pytest src/risk src/shadow src/w3w4`: 38 green.

## 15. Production IFS Compatibility (PROPOSED, not yet wired)

Rules operate on aggregates the existing IFS response already serves:
daily precipitation (D+1..D+3/D+1..D+7), probability (BlockDaily.
rainProbabilityPct → FIELD_HIGH_PRECIP_PROB). No new client, no new
request, no endpoint change. Heavy-rain flag needs the thresholds JSON
packaged alongside the service when integration happens (later phase).

## 16. GO/NO-GO Per Detector

* EXCESS_DAILY / EXCESS_3D / EXCESS_7D: **NO-GO for production.** Selective
  (low FAR) but miss 2/3+ of events through GEFS forecast error. Kept as
  diagnostic evidence only. No tuning on eval (per stop rule).
* FIELD_HIGH (D+1..D+3 tier): **GO as candidate for later production
  integration.** NOT yet integrated (no Java/API/frontend in this phase).
* FIELD_HIGH_PRECIP_PROB: **NOT VERIFIED** historically (no GEFS probs);
  unit-tested + IFS-compatible evidence flag only.

## 17. Limitations

GEFS-history ≠ live IFS (transfer gap unquantified; needs the Phase 4.0
shadow-style live collection before any production claim). Weekly Wednesday
issues only. Excess 7D is a rare event (prev 0.09, wide CIs). D+8–D+16
unvalidatable (no archive). Soil unused by design (Phase 4.2 matter).
Prob flag unvalidated. Phase 4.0 dry-spell + shadow system untouched.

## 18. Exact Next Step

STOP expanding risk detectors. Sanctioned next checkpoints only: (a) live
IFS shadow accumulation for dry-spell (≥30 issues, separate track); (b) a
future production-integration phase for the FIELD_HIGH candidate (packaging
thresholds + Java port + shadow-style live collection) — never excess tuning,
never ML on these signals. Repro: `python -m src.risk.excess_rain
--build-thresholds`, `python -m src.risk.backtest_41`,
`pytest src/risk/test_phase41.py -q`.

(End of file)
