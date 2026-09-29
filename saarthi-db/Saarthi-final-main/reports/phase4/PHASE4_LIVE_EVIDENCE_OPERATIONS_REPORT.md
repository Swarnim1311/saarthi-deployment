# Phase 4 Live Evidence Operations Report (2026-09-22)

Forecast/truth architecture finalised; live evidence pipeline operational.
No rules, thresholds, gates, or frontend behavior changed. No commit, no push.

## 1. Forecast

- Provider Open-Meteo, model `ECMWF IFS (ecmwf_ifs)`, live request HTTP 200.
- Issue 2026-09-22, horizon 16, `stale:false`; six blocks × 16 days,
  multipoint n4–n7 (Dhuri n7, Lehra n4, Malerkotla n4, Moonak n5,
  Sangrur n5, Sunam n4). No synthetic fallback.
- Capture: 2026-09-22 issue appended to both ledgers on the live fetch;
  saved-envelope re-capture → 6 `duplicate_skipped`, 0 written
  (first-write-wins verified).

## 2. Truth source decision — OPTION 1

- Selected: **CHIRPS v3.0 daily `sat`** (UCSB Climate Hazards Center).
- Product: satellite-IR + station pentad totals, IMERG-disaggregated daily,
  0.05°, public domain, keyless. Independent from IFS? **YES.**
- Coverage observed: final through 2026-08-31, prelim through 2026-09-15.
- Update mechanism: `src/shadow/update_chirps_current.py` (windowed
  /vsicurl Sangrur-bbox reads; per-row `source`; final-upgrades-prelim;
  idempotent; missing days omitted, never zero-filled).
- Reason: official current observation product in the same v3 family as the
  frozen truth; operational latency fits weekly resolution; `sat` keeps
  daily structure ECMWF-free. Alternatives (gauges/IMERG-Final) unnecessary
  while CHIRPS publishes; Open-Meteo archives documented as
  backtest-only, never truth.

## 3. Shadow

- IFS ledger: 12 records, issues 2026-09-21 + 2026-09-22, all pending.
- FIELD ledger: 12 records, same issues, all pending.
- Duplicate check: live re-fetch cached (no new capture); offline re-capture
  of the 2026-09-22 envelope skipped all 6 per ledger.
- Latest issue date: 2026-09-22 (both ledgers).

## 4. Truth attachment

- Frozen file (ends 2025-12-31, untouched): 12 still_pending per ledger.
- Current file (2026-08-20 → 2026-09-15, 162 rows, 0 nulls, 0 negatives):
  12 still_pending per ledger, 0 backfilled — correct, because the
  2026-09-16..18 antecedent days are unpublished and missing is never
  zero-filled.
- Combined truth end: 2026-09-15.

## 5. Status command

- `python -m src.shadow.status [--today DATE]` (new, tested).
- 2026-09-22 output: 2 issue dates per ledger, 0 observed, 0 eligible,
  gate flags false, verdict
  `INSUFFICIENT EVIDENCE — CONTINUE ACCUMULATING LIVE ISSUES`.

## 6. Tests

- Python: 47 passed (`src/shadow/`, `src/risk/`), incl. 6 new
  status/updater tests (offline).
- Java: 92 run, 0 failures (`mvn test`, full suite incl. Spring context).
- Frontend: unmodified → no `node --check` required.
- `git diff --check`: clean.

## 7. Evidence gate

- Resolved IFS issue dates: 0. Resolved FIELD issue dates: 0.
- ≥30 reached? NO (either ledger). Evaluation performed? NO.
- Status: INSUFFICIENT EVIDENCE — CONTINUE ACCUMULATING LIVE ISSUES.

## 8. Files changed (uncommitted, unpushed)

- New: `src/shadow/status.py`, `src/shadow/test_status.py`,
  `src/shadow/update_chirps_current.py`,
  `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2026_current.csv` (+
  `.provenance.json`), `docs/project_context/16_LIVE_EVIDENCE_OPERATIONS.md`,
  this report.
- Ledgers appended (untracked): 2026-09-22 issue in both files.
- `docs/project_context/07_CURRENT_DECISIONS.md`: decision 32 appended.
- Rebuilt: `website/Saarthi/target/saarthi-backend-1.0.0.jar` (build
  artifact, contains the pre-existing HeavyRainThresholds fix).

## 9. Limitations / next

- Resolution needs ~7 more days per issue plus CHIRPS pentad latency;
  re-run updater + attach as pentads publish (next: 2026-09-16..20).
- Daily: one fresh forecast fetch per day. Full 2026 backfill available via
  `--start 2026-01-01` (not run; not needed for current issues).
- No confirmatory evaluation until both ledgers reach 30 observed issues.
