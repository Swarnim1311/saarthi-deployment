# PHASE 4.3 — Composite Agricultural Risk Report (composite_v1)

Date: 2026-09-21. Scope: deterministic rule-based priority composite ONLY.
No score, no weights, no ML, no tuning, no new provider, no crop advice.
Frozen components reused, never modified.

## 1. Executive Summary

**VERIFIED SHIPPED.** `composite_v1` serves overall HIGH/MODERATE/LOW/
UNAVAILABLE from a frozen 5-rule precedence table over frozen components.
Live smoke 2026-09-21: all 6 blocks LOW (dry IFS day, consistent), both
shadow ledgers captured 6 lines each on one provider fetch, 404/400 OK,
timeline card live. Tests: 92/92 backend, 45/45 Python, node clean —
0 regressions. Field-shadow gate pre-registered (0.60/0.40, ≥30 issues);
no validation claimed until evidence accumulates.

## 2. Architecture (VERIFIED)

`CompositeRiskService` consumes the SAME `LiveForecast` object (no second
request). Pure `assess(field, watch, stale, heavy)` holds the precedence;
I/O (forecast extraction, antecedent, context) is separate and
truth-table tests lock the table before any wiring. New:
`CompositeRiskService`, `FieldShadowService`, readers
(`HeavyRainThresholds`, `SoilContext`, `ClimatologyContext`), resources
`risk/excess_thresholds_v1.json` (byte-identical train-frozen copy) +
`risk/soil_context.json`. `RiskController` delegates (legacy keys preserved).

## 3. Precedence Table (VERIFIED, 13 truth-table tests)

1. D+1..D+3 incomplete → UNAVAILABLE. 2. FIELD_HIGH → HIGH
(FIELD_WORK_DISRUPTION). 3. Watch ACTIVE → MODERATE (DRY_SPELL_WATCH +
PENDING_IFS_VALIDATION). 4. Stale → MODERATE (STALE_FORECAST; watch
outranks stale, both reasons recorded). 5. Else LOW. Unknown watch never
blocks 2/4/5. Heavy/context structurally cannot affect severity
(signature-level + test-locked: assess takes no context parameter).

## 4. Component Status

* FIELD_HIGH (field_work_v1): GEFS-validated (0.721/0.809/0.763) — PROPOSED
  production candidate, IFS transfer NOT VERIFIED → confidence capped.
* Dry watch (phase4.0-frozen, SAME class as shadow): NOT VERIFIED on IFS —
  provisional display, MODERATE max, pending flag on every output.
* Heavy evidence (excess-v1 p95): NO-GO family member reused as marker —
  DISPLAY ONLY, never severity/confidence.
* Context (CHIRPS/climatology/soil): display-only by construction.

## 5. Dry-Watch Handling (VERIFIED)

`evaluateWatch(f7, ant28)`: incomplete/missing → UNKNOWN; else frozen
`DrySpellRule.evaluate`. D-3 cutoff preserved (D-30..D-3 slice of one
D-32..D-1 read); future CHIRPS never a feature (antecedent strictly < D+1,
asserted by construction + tests). Live CHIRPS unconfigured → UNKNOWN +
context omitted, evaluation continues.

## 6. Heavy-Rain Evidence (VERIFIED display-only)

Strictly-above-p95 comparison; null when incomputable. Tests prove
identical severity with heavy true/false/null. Threshold resource carries
method_version + fit_period metadata; runtime read-only.

## 7. Context Inputs (VERIFIED present-but-inert)

Recent CHIRPS 7/14/30d totals anchored at issue−1 (one read, null-safe);
climatology D+1..D+3 normal sum (Phase 3A wheel); soil factual line (no
texture interpretation). Absence → explicit unavailable lines, no penalty.

## 8. API Contract (VERIFIED live)

`GET /api/risks[/{blockId}]?window=3d`: all legacy keys byte-compatible +
`overall_risk, primary_concern, risks[3], context, advisories[],
composite_method_version`. 404/400/503 preserved. No scores, no fake
probabilities; validation tags per component (GEFS_VALIDATED_IFS_PENDING /
PROVISIONAL_IFS_PENDING / DISPLAY_ONLY). Contract doc updated.

## 9. Frontend (VERIFIED live)

Timeline Agricultural Risk card: Overall badge (4 states incl. MODERATE
amber), primary concern + why, other-signals lines (watch/heavy/recent/
soil, each conditional), advisories, freshness. Backward-compatible reads;
existing loading/error/stale patterns reused. Served live with new card.

## 10. Advisory Templates (VERIFIED strings)

HIGH / watch-provisional / stale-caution / LOW / UNAVAILABLE per frozen
wording + issue/retrieved tail; stale + pending notes appended. Banned
vocabulary (harvest/spray/irrigate/transplant/sow/yield) absent — asserted
by code review (templates contain none).

## 11. Field Shadow Validation (PROPOSED infra, NOT VERIFIED results)

`field_shadow.jsonl` (`field-shadow/v1`, `field:<date>`), Java live hook +
`src/shadow/field_shadow.py` (capture/attach/evaluate, 7 tests green).
Truth: CHIRPS D+1..D+3 wet ≥2. Demo on real CHIRPS: 12/12 observed,
verdict correctly INSUFFICIENT_EVIDENCE. Gate 0.60/0.40, ≥30 issues.
Dry-spell ledger/schema/code path untouched (overload-only extension,
all dry tests green).

## 12. Tests (VERIFIED)

Backend 92/92 (13 precedence + 7 service + 5 field-shadow + 6 rule + 10
controller + 1 Spring context-load + 50 pre-existing). Python 45/45
(15 phase41 + 9 phase40 + 17 shadow incl. 7 field-shadow + 4 w3w4).
`node --check` clean. New Spring context test guards DI wiring (caught one
real startup failure pre-smoke: missing @Component — fixed, suite green).

## 13. Live Smoke Test (VERIFIED 2026-09-21)

Live Open-Meteo/ECMWF IFS, issue 2026-09-21, 6 blocks fresh. Composite all
LOW (dry D+1..D+3 everywhere — evidence cross-checked vs forecast).
risks[3] names correct; context rain unavailable (CHIRPS unconfigured —
correct), climatology + soil present; advisory correct. 404/400 verified.
Dry ledger 6 lines + field ledger 6 lines from the single fetch (no second
request — instant risk responses + call-count test). Timeline serves with
new card. No IFS validation claimed (integration only).

## 14. Leakage Checks (VERIFIED)

Thresholds byte-identical train fit (loader asserts excess-v1/2010–2019);
no eval fitting; antecedent strictly historical; future CHIRPS labels-only
in Python tools; GEFS/IFS/dry/field namespaces asserted separate
(field metrics ignore foreign lines; dry tests untouched and green).

## 15. Limitations (NOT VERIFIED / known)

IFS transfer unconfirmed for both HIGH and watch (caps confidence);
CHIRPS live path typically unconfigured (context/watch often unknown);
heavy uplift n=45 GEFS-only; MODERATE conflates watch + stale (distinct
reasons shown); D+8+ unvalidated; no yield/moisture/crop claims by design.

## 16. Git Status

Modified: portal.html/js, api-contract.md, 07_CURRENT_DECISIONS.md (+31),
RiskController.java, LiveWeatherService.java (field-shadow hook only),
ShadowLedger.java (schema overload only), application.properties (2 comment
lines) + prior-checkpoint files (untouched this phase) + pre-existing
notebook (preserved). New: risks service/readers/tests, resources,
field_shadow.py + tests, docs 15, this report. No commit/push/reset/clean.

## 17. Exact Next Step

STOP building. Operate: serve normally (ledgers accumulate per fresh
fetch); run `truth.py` / `field_shadow attach` periodically. Next
checkpoint ONLY when a ledger reaches ≥30 resolved issues: pre-registered
confirmatory evaluation (frozen rules/gates), then a confidence-wording
ratification. Never: scores, tuning, soil weights, excess production,
crop/irrigation modules, ML.

(End of file)
