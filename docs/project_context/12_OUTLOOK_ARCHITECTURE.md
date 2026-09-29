# 12 — Outlook Architecture (Phase 3: 7–30 Day Agricultural Outlook)

Status: Phase 3A VALIDATION FOUNDATION complete (2026-09-19). Phase 3B DISPLAY-ONLY BASELINE complete (2026-09-20).

## IMPLEMENTED

### Phase 3A (validation foundation, frozen — do not rebuild)
- `src/climatology/build_normals.py` — block × issue-DOY climatology from the
  authoritative CHIRPS block-daily file (read-only source). Pooling ±7 d circular,
  wet ≥ 1.0 mm, Feb-29 → DOY 60, zeros are observed (0 NaNs in source).
  Artifacts: `data/processed/climatology/block_doy_normals_train_2010_2019.csv`
  (kind=train-frozen, for backtests) + `block_doy_normals_full.csv`
  (kind=**deployment**, live display only) + `method_*.json` with leakage statements.
- `src/climatology/mjo_composites.py` — MJO phase → rainfall-anomaly composites
  (train 2010–2019, active = amp ≥ 1.0, lead scan 3/10/17/24, JJAS focus).
  Outputs `mjo_composites.csv` + `mjo_gate.json`. In-sample gate: GO (phases 2, 6, 7).
- `src/climatology/backtest_w3w4.py` — W3 (D+17..23) / W4 (D+24..D+30) tercile backtest,
  fit 2010–2019, weekly JJAS 2020–2025 test. Arms: CLIM / CLIM+RECENT / CLIM+RECENT+MJO
  (transparent conditional frequencies + Laplace smoothing; NO ML).
  Outputs `backtest_predictions.csv` + `backtest_metrics.json`.
- `notebooks/09_phase3_climate_outlook_validation.ipynb` (executed, 0 errors) and
  `reports/phase3/PHASE3A_CLIMATOLOGY_MJO_REPORT.md`.
- Decision 24 records the outcome (see `07_CURRENT_DECISIONS.md`).

### Phase 3B (display-only W3/W4 baseline — this phase)
- `com.saarthi.outlook.OutlookService` — loads the frozen deployment CSV
  (packaged `classpath:/climatology/block_doy_normals_full.csv`, optional
  `saarthi.outlook.climatology-path` override); W3=D+17..23 / W4=D+24..D+30
  from issue date D (today IST, explicit); climatological normals as expected
  7-day sums of frozen daily means; probabilities = tercile prior (1/3 each,
  honestly labelled); `RecentRainfallService` trailing 14/30 d as separate
  observed context (null when unavailable, never zero); climate context read
  from `ClimateContextService` with explicit stale flags; confidence
  MODERATE/LOW (HIGH never issued); deterministic narrative from actual fields.
- `com.saarthi.outlook.OutlookController` — `GET /api/outlook/17-30`,
  `GET /api/outlook/17-30/{blockId}`, `GET /api/outlook/freshness`
  (unknown block → 404 `unknown_block`; missing climatology → 503
  `outlook_unavailable`). Additive tree; weather provider untouched.
- `OutlookServiceTest` (9 tests, all pass, no live network).
- Frontend Weeks 3–4 panel in the timeline view (`portal.html` + `portal.js`
  `loadWeeks34Outlook`, wired to the block selector; loading / stale /
  unavailable / error states explicit; "Extended climate outlook" wording,
  never "30-day weather forecast").
- Decision 25 records the outcome (see `07_CURRENT_DECISIONS.md`).

## KEY RESULT (unchanged from 3A)

Out-of-sample Brier skill vs flat climatology is NEGATIVE for both enhanced arms
(W3 −1.1%/−3.4%, W4 −1.5%/−2.5%; sensitivity also negative). **Final: NO-GO for MJO
mathematics — MJO remains explanatory-only; trailing rainfall stays display-only.**
The in-sample gate GO did not survive validation, which is exactly what the gate →
backtest structure is for. Nothing was tuned to force a positive.

## Freshness / updater decision (Phase 3B)

No new external updater was built (per plan). Instead the outlook is
freshness-aware: MJO stale if flagged or `observation_end` > 14 d old vs D;
ENSO/IOD stale if vintage month > 2 months old vs D (IOD explicit flag also
honoured). Stale inputs cap confidence at LOW and are surfaced in
`climate_context` + `freshness` + frontend banners. The outlook functions on
climatology alone when climate inputs are stale.

## NOT implemented (explicit)

- MJO mathematical modifier (context-only label enforced in code + API).
- IOD mathematical modifier (context-only).
- ENSO mathematical modifier (regime context only).
- Any new ML, any retraining, any calibrated probabilistic W3/W4 forecast.
- Automated climate updater (manual SOP vs fetch still open for live W3/W4).
- Deterministic daily mm for days 17–30 (never served).

## What Phase 3B serves (bounds)

Per block, per week (W3/W4): climatological tercile normals, observed trailing
14/30 d totals as separate context, confidence (MODERATE/LOW) with reason,
narrative, freshness, explicit stale/unavailable states. NEVER deterministic
daily mm for days 17–30, NEVER zero-filled missingness, NEVER MJO/recent
probability modifiers without a new validated gate.
