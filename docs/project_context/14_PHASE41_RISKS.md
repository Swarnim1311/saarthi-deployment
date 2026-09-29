# 14 — Phase 4.1 Excess Rainfall + Field-Work Risks

Status: IMPLEMENTED 2026-09-21. Mixed result: FIELD_HIGH is a GO candidate
(not integrated); all EXCESS detectors are NO-GO (diagnostic only).
Phase 4.0 dry-spell + shadow system frozen and untouched (Decision 27/28).

## 1. Definitions (frozen method versions)

* excess-v1 (`src/risk/excess_rain.py`): three separate evidence components
  over D+1..D+7 — daily max vs block JJAS train p95 (`EXCESS_DAILY_P95`),
  max rolling-3 sum vs p90 (`EXCESS_3D_P90`), 7-day sum vs p90
  (`EXCESS_7D_P90`). Strictly-exceeds trigger; missing → unavailable.
* field-v1 (`src/risk/field_work.py`): D+1..D+3 tier HIGH (≥2 wet days ≥1mm)
  / MODERATE (1) / LOW (0); D+1..D+7 evidence-only (count + heavy, no tier).
  Flags: `FIELD_HEAVY_RAIN` (max > block daily_p95), `FIELD_HIGH_PRECIP_PROB`
  (max prob ≥ 70%, forecast-evidence only, NOT historically validated).

## 2. Thresholds

`data/processed/risk/excess_thresholds_v1.json`, fit 2010–2019 JJAS only
(n=1220/window/block). Daily p95: 17.6 (Moonak)–21.2 (Sangrur); 3d p90:
32.1–39.0; 7d p90: 74.7–85.9. Block-specific, no pooling.

## 3. Validation setup

HISTORICAL GEFS PSEUDO-FORECAST leads 1–7 (NOT IFS) vs CHIRPS truth; weekly
Wednesday JJAS eval 2021–2025 (n=522); 2020 excluded; issue-clustered
bootstrap CIs; NEVER/ALWAYS baselines. `python -m src.risk.backtest_41`.

## 4. Results

FIELD_HIGH: prec 0.721 / rec 0.809 / F1 0.763, uniform blocks → GO candidate,
integration deferred. EXCESS daily/3d/7d: prec 0.585/0.598/0.325, rec
0.242/0.325/0.283 → NO-GO, diagnostic only. Full evidence:
`reports/phase4/PHASE4_1_EXCESS_FIELD_REPORT.md`.

## 5. What stays frozen

Phase 4.0 rule + gate, shadow ledger/schema/system, excess-v1/field-v1
rules + thresholds, no ML, no tuning on eval folds.

## 6. Next phase

Only sanctioned: (a) dry-spell live-shadow accumulation (≥30 issues);
(b) future FIELD_HIGH production integration (threshold packaging, Java
port, live collection). Never: excess tuning, composites, soil drivers
(4.2), planting/irrigation, ML on these signals.
