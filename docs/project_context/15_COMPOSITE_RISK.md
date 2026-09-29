# 15 — Phase 4.3 Composite Agricultural Risk (composite_v1)

Status: IMPLEMENTED 2026-09-21. Deterministic rule-based priority, NOT a
score. No weights, no ML, no tuning. Frozen components only.

## 1. Precedence (first match wins)

1. D+1..D+3 incomplete → UNAVAILABLE (NO DATA != NO RISK).
2. FIELD_HIGH (≥2 wet days ≥1mm) → HIGH, primary FIELD_WORK_DISRUPTION.
3. Dry-spell watch ACTIVE → MODERATE, primary DRY_SPELL_WATCH
   (+PENDING_IFS_VALIDATION; NEVER drives HIGH).
4. Forecast stale → MODERATE, reason STALE_FORECAST.
5. Else → LOW, primary NONE.

## 2. Components

* FIELD_HIGH: `FieldWorkRule` (field_work_v1) — GEFS-validated
  (0.721/0.809/0.763), IFS transfer pending. Only alert driver.
* Dry watch: SAME `DrySpellRule` (phase4.0-frozen) as the shadow ledger;
  antecedent D-30..D-3 via `RecentRainfallService.dailyWindow`; missing →
  UNKNOWN (never blocks other rules).
* Heavy evidence: train-frozen `daily_p95` (`risk/excess_thresholds_v1.json`,
  excess-v1 2010–2019, byte-identical copy) — display-only, never severity.
* Context (display-only, never severity/confidence): recent CHIRPS 7/14/30d
  totals anchored at issue−1 (omitted when file unconfigured/incomplete);
  climatology D+1..D+3 normal sum (Phase 3A wheel); soil factual line
  (`risk/soil_context.json`, SoilGrids means, no interpretation).

## 3. Confidence (structural)

MODERATE normally; LOW when stale, watch-driven, or unavailable.
Pre-registered (not applied): field-shadow ≥30 issues + recall ≥0.60 +
precision ≥0.40 → IFS-confirmed wording.

## 4. API

`GET /api/risks[/{blockId}]?window=3d` extended additively: legacy
FIELD_WORK_DISRUPTION keys preserved + `overall_risk, primary_concern,
risks[3], context, advisories[], composite_method_version`. 404/400/503
semantics unchanged. Contract: `docs/api/api-contract.md` (composite section).

## 5. Field shadow

`data/processed/shadow/field_shadow.jsonl` (`field-shadow/v1`,
`field:<date>`), first-write-wins, Java live hook + Python
`src/shadow/field_shadow.py` (capture/attach/evaluate). Truth: CHIRPS
D+1..D+3 wet count ≥2. Gate: recall ≥0.60, precision ≥0.40, ≥30 issues.
Dry-spell ledger/schema untouched.

## 6. What stays frozen

All rules/thresholds/gates; excess NO-GO (diagnostic only); W3/W4;
provider/retrieval; no scores, soil weights, crop advice, or ML.

## 7. Verification

Backend 92/92, Python 45/45, node clean, Spring context test, live smoke
2026-09-21 (6 blocks LOW on a dry day, both ledgers 6 lines, 404/400 OK).
Full evidence: `reports/phase4/PHASE4_3_COMPOSITE_REPORT.md`.
