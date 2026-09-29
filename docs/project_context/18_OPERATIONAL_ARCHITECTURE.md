# 18 — Operational Architecture (FINAL: live Open-Meteo ECMWF IFS)

Status: OPERATIONAL and authoritative for the live Saarthi application.
This document freezes the final operational weather architecture. Research
and validation infrastructure (CHIRPS history, shadow ledgers, GEFS
backtests, reports, notebooks) is preserved in the repository but is NOT
required for normal application operation.

## 1. Operational product

LIVE WEATHER (Open-Meteo ECMWF IFS) -> LiveWeatherService ->
Sangrur block aggregation (multipoint, 6 blocks) ->
rainfall forecast (D+1..D+16) -> Risk engine + Forecast UI.

## 2. Live forecast source (authoritative)

- Delivery: Open-Meteo GET /v1/forecast (keyless).
- Model selector: models=ecmwf_ifs.
- Provider label: Open-Meteo; model label: ECMWF IFS (ecmwf_ifs).
- Timezone: Asia/Kolkata; horizon: forecast_days=16 (16 daily dates).
- Authoritative rainfall: provider daily.precipitation_sum.
- Missing rainfall stays null — never zero-filled, never substituted
  with CHIRPS or GEFS, never synthesised.

## 3. Forecast horizons

- D+1..D+16: LIVE ECMWF IFS forecast (short/medium range).
- D+17..D+30: EXISTING Saarthi W3/W4 outlook (W3=D+17..D+23,
  W4=D+24..D+30, frozen Phase 3A climatology). Climatological outlook,
  NOT a literal IFS forecast. UI: "Live ECMWF IFS Outlook" vs
  "17-30 Day Climatological Outlook".

## 4. Block aggregation (frozen)

- Six blocks: Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam.
- Multipoint sampling over block geometry; centroid fallback only.
- Boundaries unchanged; no 8-block geography.

## 5. Observed data (OPTIONAL only)

- RecentRainfallService reads OPTIONAL local CHIRPS CSV
  (saarthi.chirps.path; unset by default).
- CHIRPS unavailable -> observed = available:false with nulls, NEVER 0,
  NEVER mixed into forecast fields.
- Forecast-available + observed-unavailable is a normal state.

## 6. CHIRPS role (research/validation/optional context)

- Preserved: history, current artifact, updater, truth attachment.
- NOT a live forecast dependency. All live endpoints work with CHIRPS
  missing, stale, or unconfigured.

## 7. Shadow role (optional validation only)

- Preserved: src/shadow, capture services, ledgers, truth, status.
- Fail-soft setter-injected hooks in LiveWeatherService; reuse the
  already-retrieved forecast (no second weather client).
- Shadow failure is never a user-facing forecast failure; no gate blocks
  live endpoints.

## 8. GEFS role (historical validation/reference)

- FIELD_HIGH validated historically on GEFS (0.721/0.809/0.763).
- Operational input is ECMWF IFS; IFS transfer NOT confirmed.
- Responses carry GEFS_VALIDATED_IFS_PENDING + MODERATE confidence.

## 9. Risk (deterministic frozen rules)

- composite_v1 priority: incomplete -> UNAVAILABLE; FIELD_HIGH -> HIGH;
  dry watch -> MODERATE (never HIGH); stale -> MODERATE; else LOW.
- Thresholds/windows/confidence frozen. Context display-only.
- GET /api/risks works with no CHIRPS/ledger/truth/gate.

## 10. API contract (all work without CHIRPS/shadow)

- GET /api/weather/forecast (6 blocks x 16 days + optional
  recent_observed), GET /api/weather/forecast/{blockId},
  GET /api/weather/freshness (no upstream fetch),
  GET /api/risks/{blockId}?window=3d,
  GET /api/outlook/freshness, GET /api/outlook/17-30/{blockId}.
- Provider failure without cache -> 502 provider_error (never synthetic);
  unknown block -> 404 unknown_block; incomplete risk window ->
  UNAVAILABLE; missing climatology -> 503 outlook_unavailable.
  Stale is an explicit labelled state, never rendered as fresh.

## 11. Frontend states

- PRIMARY: "Live ECMWF IFS Outlook" (block, daily dates, daily rainfall,
  probability, cumulatives, provider, model, freshness, stale state).
- RISK: overall risk, primary concern, components, advisories, confidence.
- LONG RANGE: "17-30 Day Climatological Outlook" (clearly not IFS).
- OPTIONAL: observed rainfall context when available, else an explicit
  "observed unavailable" line that never blocks the forecast.
- Normal use never requires CHIRPS/shadow status, evidence counts,
  a 30-event gate, or validation status.

## 12. Explicit non-goals

No new provider, no new model, no 30-day IFS forecast, no ML, no forecast
correction, no second weather client, no retraining, no threshold or
geography changes, no local IFS-validation claims (our project has NOT
performed the 30-event live validation).

