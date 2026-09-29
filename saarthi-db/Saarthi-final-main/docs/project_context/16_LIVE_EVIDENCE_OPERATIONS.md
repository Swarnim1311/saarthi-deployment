# 16. Live Evidence Operations (Phase 4.x IFS shadow pipeline)

Operational reference for accumulating live Open-Meteo/ECMWF-IFS forecast →
independent-truth pairs. No ML, no tuning, no evaluation below the gate.

## 1. Forecast source (system under validation)

- Provider: **Open-Meteo**, model selector `ecmwf_ifs`, model label
  `ECMWF IFS (ecmwf_ifs)`, 16-day horizon, six Sangrur blocks
  (Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam).
- Live-verified 2026-09-22: HTTP 200, `stale:false`, 6 blocks × 16 days,
  multipoint means n4–n7, no centroid fallback, no synthetic data.

## 2. Truth source (OPTION 1 — selected 2026-09-22)

- **CHIRPS v3.0 daily `sat`, UCSB Climate Hazards Center** (satellite IR +
  station observations, 0.05°, public domain, keyless FTP/HTTP).
- Independent from IFS? **YES.** Observation-based pentad totals; daily
  structure from IMERG (`sat`) disaggregation. `rnl` (ERA5-disaggregated)
  daily is excluded by design — ERA5 is an ECMWF product and must not shape
  IFS validation truth.
- Latency: prelim ~2 days after pentad end (days 2/7/12/17/22/27 of month);
  final monthly, 3rd week of following month. Observed 2026-09-22:
  final through 2026-08-31, prelim through 2026-09-15.
- Same v3 family as the frozen 2010–2025 truth (see `03_DATASETS.md`).
- Open-Meteo Previous Runs / Single Runs / Historical Forecast archives are
  **model-history/backtesting resources only** — never truth for IFS.

## 3. Truth files

- Frozen (untouched): `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv`
  (2010-01-01 → 2025-12-31).
- Current (versioned, per-row `source` = `chirps-v3.0-final-sat` |
  `chirps-v3.0-prelim-sat`):
  `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2026_current.csv`
  + `.provenance.json` sidecar. Final rows may upgrade prelim rows for the
  same (block, date) — the only permitted revision, explicit in `source`.
- Update: `python -m src.shadow.update_chirps_current --start YYYY-MM-DD
  [--end YYYY-MM-DD]` (windowed /vsicurl reads of the Sangrur bbox only;
  idempotent; missing days produce no row — never zero-filled).
  Full backfill: `--start 2026-01-01`.

## 4. Shadow ledgers

- `data/processed/shadow/ifs_shadow.jsonl` (`ifs-shadow/v1`, DrySpellRule
  D+1..D+7) and `data/processed/shadow/field_shadow.jsonl`
  (`field-shadow/v1`, FIELD_HIGH D+1..D+3). One record per (issue, block).
- Capture: `LiveWeatherService.getForecast()` → `ShadowCaptureService` +
  `FieldShadowService` `tryCapture()` (fail-soft, no second weather client).
  First-write-wins on `(issue_id, block)`; repeats → `duplicate_skipped`.
- Offline fallback: `python -m src.shadow.capture --envelope <saved
  /api/weather/forecast JSON> --ledger <jsonl>` and
  `python -m src.shadow.field_shadow capture --envelope fc.json
  --ledger <jsonl>`.

## 5. Truth attachment (existing engines only)

- `python -m src.shadow.truth --ledger <ifs> --chirps <csv> [--today DATE]`
  (resolve after D+7, backfills D-30..D-3 antecedent) and
  `python -m src.shadow.field_shadow attach --ledger <field> --chirps <csv>
  [--today DATE]` (resolve after D+3).
- Run frozen file first, then current file; `chirps_vintage` records which
  file resolved each record. Incomplete windows stay `pending`; missing
  observations → `unavailable`, never zero.

## 6. Status command

`python -m src.shadow.status [--today DATE]` → JSON: per-ledger records,
issue dates, pending/observed/unavailable, latest issue, latest observed
issue, eligible issue dates, observed issue dates, 30-gate flags, truth
coverage ends, and the single evaluation verdict string
(`INSUFFICIENT EVIDENCE — CONTINUE ACCUMULATING LIVE ISSUES` until both
ledgers hold ≥30 observed issue dates).

## 7. Gates (frozen)

- ≥30 resolved (observed) live issue dates **per ledger** before any
  confirmatory claim; precision ≥0.40, recall ≥0.60, report F1 + CIs.
- IFS evidence stays separate from historical GEFS evidence. No tuning
  after seeing evaluation results.

## 8. Current state (2026-09-22)

- Ledgers: 12 records / 2 issue dates each (2026-09-21, 2026-09-22), all
  `pending`, 0 observed. Combined truth end: 2026-09-15.
- 2026-09-21/22 issues cannot resolve until their windows complete AND the
  corresponding CHIRPS pentads publish (expected within ~2–7 days).
- Antecedent backfill for the 2026-09-21 IFS issue is correctly deferred:
  2026-09-16..18 are unpublished, and missing is never zero-filled.
- Daily discipline: one fresh `GET /api/weather/forecast` per day grows
  both ledgers by 6 pending records; re-run
  `python -m src.shadow.update_chirps_current` + truth attach as new
  pentads publish.
