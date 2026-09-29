# 13 — Live IFS Shadow Validation (Phase 4.x Data-Collection Layer)

Status: IMPLEMENTED 2026-09-21. Validation/data-collection ONLY — not a
production risk API. Phase 4.0 rule stays FROZEN (Decision 27); this layer
collects real Open-Meteo/ECMWF-IFS forecast → CHIRPS-outcome pairs for a
future pre-registered confirmatory evaluation.

## 1. Purpose

Answer, after evidence accumulates: "did this exact IFS forecast correctly
predict a D+1–D+7 dry-spell event?" Every live forecast issue preserves its
D+1..D+7 values, rule inputs, frozen prediction, and (later) observed truth.

## 2. Frozen rule (unchanged, called — not copied)

`WARN = (dry_run_through_D-3 >= 3 and f_dry_D+1..D+7 >= 5) or (f_dry >= 6)`,
dry = rain < 1.0 mm/day, antecedent ends D-3, window exactly D+1..D+7.
Python source of truth: `src/risk/backtest.py`. Java port:
`website/Saarthi/.../shadow/DrySpellRule.java` (behaviour-identical,
parity-tested over the full (dry_run, f_dry) grid on both sides).

## 3. Capture (Java, at the retrieval boundary)

`ShadowCaptureService` hooks `LiveWeatherService.getForecast()` after a
fresh fetch — reusing the already-retrieved production forecast (no second
Open-Meteo request). Fail-soft: storage/CHIRPS failure is logged and the
weather response is served normally. Antecedent (D-30..D-3) comes from the
local CHIRPS CSV via `RecentRainfallService.dailyWindow()` (new additive
method); when CHIRPS is unconfigured the record is still saved with
`antecedent_available:false, predicted:null` and backfilled later by truth.py.

## 4. Ledger

`data/processed/shadow/ifs_shadow.jsonl` (repo-canonical; override via
`-Dsaarthi.shadow.path=...`). One JSON object per (issue, block),
`schema_version: ifs-shadow/v1`. Immutable first-write-wins on
`(issue_id, block)` — repeat retrievals return `duplicate_skipped`, never
overwrite. Foreign-schema lines are ignored, never parsed as shadow records.

## 5. Truth (Python, CHIRPS only)

`python -m src.shadow.truth --ledger <jsonl> --chirps <csv> [--today DATE]`:
for pending records with `today >= issue+7`, requires all 7 CHIRPS days
present (else `unavailable` — never zero-filled) and labels 1 iff a ≥7d
<1mm CHIRPS run overlaps D+1..D+7 (same definition as backtest). Also
backfills missing antecedent + frozen prediction from D-30..D-3 history
(`antecedent_backfilled:true`); forecast values are never modified. Atomic
rewrite; observed records are write-once.

## 6. Metrics (Python, separate namespace)

`python -m src.shadow.metrics --ledger <jsonl>` → `source: live_ifs_shadow`
contingency + issue-clustered bootstrap CIs (blocks of one issue travel
together). Frozen gate recall ≥ 0.60 / precision ≥ 0.40 is REPORTED only;
below 30 resolved issues the verdict is `INSUFFICIENT_EVIDENCE`. Historical
GEFS metrics (`data/processed/risk/backtest_gefs_*.json`) are never consulted.

## 7. Offline capture fallback

`python -m src.shadow.capture --envelope <saved /api/weather/forecast JSON>
--chirps <csv> --ledger <jsonl>` — same schema, no weather request.

## 8. Tests

Python: `pytest src/shadow/test_shadow.py src/risk/test_risk.py` (10 shadow +
9 Phase 4.0, all green). Java: `mvn test` in `website/Saarthi` (50/50 green:
39 pre-existing incl. all Phase 3B weather tests + 11 new shadow tests).

## 9. Limitations / next checkpoint

Ledger only grows via live serving (or manual envelope capture); truth lags
7+ days behind each issue plus CHIRPS file refresh latency. No validation
claim until ≥30 issues resolve. Next checkpoint (only when evidence
suffices): pre-registered confirmatory evaluation with the unchanged frozen
rule — never threshold tuning, never ML on this signal.
