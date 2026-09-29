# SIH Monsoon Outlook — API contract (Python package → Spring Boot → vanilla JS + Leaflet)

Source of truth: `data/processed/application/latest_forecast.json` (canonical), `blocks.json`, `sangrur_blocks.geojson`, fixtures in `api/`.

## Implemented endpoints (Spring Boot)

| Endpoint | Returns |
|---|---|
| `GET /api/health` | `{status, forecast_available, issue_date, valid_from, valid_to, generated_at, source, stale, age_days, expires_at, blocks_available, forecast_horizon_days, model}` |
| `GET /api/blocks` | static block metadata (`blocks.json`) |
| `GET /api/blocks/geojson` | `sangrur_blocks.geojson` (`application/geo+json`) |
| `GET /api/forecast/latest` | full canonical forecast (`system` + `forecast` + `summary`) |
| `GET /api/forecast/freshness` | freshness only (`available, issue_date, valid_from, valid_to, generated_at, source, stale, expired, age_days, expires_at`) |
| `POST /api/forecast/reload` | re-reads the NB06 package (no restart); returns freshness; HTTP 500 + previous forecast kept on invalid package |
| `GET /api/forecast/{blockId}` | single block outlook (`block_id` = `bhuvan_b_<b_code>` or name, case-insensitive; unknown → 404 `unknown_block`) |
| `GET /api/forecast/summary` | `summary` object (district totals, category lists) |
| `GET /api/advisories/{blockId}` | `advisories[]` of the block (prototype-labeled; unknown block → 404) |
| `GET /api/panchayats[?block=]` | panchayat reference lists; unknown `block` → 404 `unknown_block` (never a silent fallback) |
| `POST /api/farmer-analysis` | **Advisory rework (live contract)**: deterministic 9-section advisory built ONLY from `GET /api/weather/forecast/{block}` (ECMWF IFS) + `agronomy/crop_reference.json` (PAU/ICAR-cited) + SoilGrids context. Sections: `inputs, location, crop, stage, weather, soil, water_demand, risks, advisory, sources`. Unknown/missing/blank block → 404 `unknown_block`; no synthetic soil-moisture gauge, no dry-spell percentages, no frozen CHIRPS-GEFS inputs. |
| `GET /api/climate-context` | MJO/IOD/ENSO summary (explanatory only; unavailable → `{available:false}`, rainfall unaffected; DMI numeric) |
| `POST /api/climate-context/reload` | re-reads the climate package (no restart; fail-soft → `{available:false}`, HTTP 200) |
| `GET /api/mjo/latest` | MJO node only |

## Live operational weather endpoints (Phase 1+2: Open-Meteo + ECMWF IFS)

Source: live third-party NWP via Open-Meteo (`models=ecmwf_ifs`), aggregated to the
6 legacy Sangrur blocks. This tree is ADDITIVE — the validated CHIRPS-GEFS endpoints
above are untouched. Live-verified 2026-09-19 (HTTP 200, keyless, 16 daily dates,
384 hourly steps, `Asia/Kolkata`).

| Endpoint | Returns |
|---|---|
| `GET /api/weather/forecast` | all 6 blocks: `{provider, model, issue_date, retrieved_at, stale, stale_warning?, horizon_days, horizon_note, blocks[], recent_observed}` |
| `GET /api/weather/forecast/{blockId}` | one block (`blockId` = name or Bhuvan id, case-insensitive; unknown → 404 `unknown_block`): `{provider, model, issue_date, retrieved_at, stale, horizon_days, block, recent_observed}` |
| `GET /api/weather/freshness` | cache freshness WITHOUT upstream fetch: `{available, provider, model, model_run_time:null, model_run_note, retrieved_at, age_minutes, stale, cache_ttl_minutes, issue_date}` (before any fetch: `{available:false, reason}`) |

### Per-block shape (`block`)

- `block_name`, `spatial_method` (e.g. `multipoint_mean_n7_3x3_bbox_filtered …`; centroid fallback labelled, never presented as the solution),
- `days[]` each `{date, horizon_day 1..16, rainfall_mm (mm; `null` = no data at any sample point, NEVER zero-filled), rain_probability_pct, note?}`,
- `cum_3d_mm / cum_7d_mm / cum_15d_mm` each `{available:true, rainfall_mm}` or `{available:false, reason}` (cumulatives require ALL n days present with non-null rainfall),
- `cum_30d` ALWAYS `{available:false, reason}` (30-day deterministic rainfall not available from the 16-day feed; Phase 3 climate outlook, not synthesised here),
- `recent_observed` (same object at both levels): `{available:false, reason}` unless `saarthi.chirps.path` points at the local CHIRPS block CSV, then `{available, window_days, period_start/end, source, observed_last_<n>d_mm_by_block}` — strictly OBSERVED history, never mixed into forecast fields.

### Units / metadata / errors

- Rainfall mm, probability %, temperature °C (provider-native; passed through, never converted).
- `provider` = `Open-Meteo` (delivery layer), `model` = `ECMWF IFS (ecmwf_ifs)` (NWP model — never the delivery layer). `model_run_time` is `null` by design: Open-Meteo exposes no per-run initialisation timestamp, so `retrieved_at` is the freshness anchor.
- Horizon served honestly: days 1–7 operational, 8–15 extended/lower-confidence, 16–30 NOT served.
- Cache: one provider call serves all blocks; TTL `saarthi.weather.cache-ttl-minutes` (default 60). Provider failure WITH cache → cached payload + `stale:true` + `stale_warning` (HTTP 200). Provider failure WITHOUT cache → 502 `provider_error` (`{error, message, retry}`), never synthetic data. Unknown block → 404 `unknown_block` with `valid_blocks`.

Synthetic endpoints (`/outlook`, `/predict`, `/map-data`) remain REMOVED. No synthetic forecast fallback exists: API failure surfaces an explicit error in the UI.

## Agricultural risk endpoints (Phase 4.3: composite_v1 priority composite)

Source: derived server-side from the already-retrieved live IFS forecast
(`LiveWeatherService` object — never a second Open-Meteo request). Additive
tree — weather, outlook, and shadow paths are untouched. Deterministic
priority, NOT a score (composite_v1): FIELD_HIGH → HIGH; provisional
dry-spell watch → MODERATE (never HIGH); stale → MODERATE; else LOW;
incomplete D+1..D+3 → UNAVAILABLE (never LOW; NO DATA != NO RISK).

| Endpoint | Returns |
|---|---|
| `GET /api/risks?window=3d` | all 6 blocks: legacy keys + `{composite_method_version:"composite_v1", method_note, blocks[]}` (each entry extended as below) |
| `GET /api/risks/{blockId}?window=3d` | one block (name or Bhuvan id, case-insensitive; unknown → 404 `unknown_block`): legacy `{block, issue_date, risk:"FIELD_WORK_DISRUPTION", category, confidence, window, window_dates[3], method_version:"field_work_v1", validation_note, evidence:{wet_days, max_precipitation_mm, daily_precipitation_mm}, reasons[], advisory?, unavailable_reason?, provider, model, retrieved_at, stale, stale_warning?}` PLUS `{overall_risk:HIGH|MODERATE|LOW|UNAVAILABLE, primary_concern:FIELD_WORK_DISRUPTION|DRY_SPELL_WATCH|NONE, composite_method_version:"composite_v1", risks:[{name:FIELD_WORK_DISRUPTION,state,reasons,validation:GEFS_VALIDATED_IFS_PENDING},{name:DRY_SPELL_WATCH,state:ACTIVE|QUIET|UNKNOWN,reasons,validation:PROVISIONAL_IFS_PENDING,pending_ifs_validation:true,f_dry_d1_d7?,dry_run_through_dminus3?},{name:HEAVY_RAIN_EVIDENCE,state:PRESENT|ABSENT|UNKNOWN,reasons,validation:DISPLAY_ONLY,evidence_only:true,threshold_mm?}], context:{recent_rainfall:{available,through?,d7_mm?,d14_mm?,d30_mm?,source?|reason},climatology:{available,normal_d1_d3_mm?,vintage?|reason},soil:{available,line?|reason}}, advisories[]}` |

### Category / confidence semantics

- `category`: `HIGH` (≥2 wet days) | `LOW` (0–1 wet days) | `UNAVAILABLE`
  (incomplete D+1..D+3 — missing days never zero-filled, never LOW).
- `confidence`: `MODERATE` when fresh, `LOW` when the underlying forecast is
  stale. NEVER "validated": the rule was validated historically on GEFS
  (precision 0.721, recall 0.809, F1 0.763); live IFS transfer is not yet
  confirmed (`validation_note` carried in every response).
- `window`: only `3d` is served (missing defaults to `3d`); anything else →
  400 `invalid_window`. Provider failure without cache → 503
  `risk_unavailable`, never synthetic risk. Freshness fields (`provider,
  model, retrieved_at, stale`) are reused from the weather forecast — no
  separate freshness system.
- Frontend (timeline page): "Agricultural Risk (D+1–D+3)" card per selected
  block showing Overall HIGH/MODERATE/LOW/UNAVAILABLE, primary concern + why,
  other signals (dry-spell watch state, heavy evidence if present, recent
  rainfall if available, soil context), confidence, freshness, and generic
  advisories (no crops, no agronomic prescriptions).

## Weeks 3–4 extended climate outlook endpoints (Phase 3B: display-only, climatology-based)

Source: frozen Phase 3A deployment climatology
(`data/processed/climatology/block_doy_normals_full.csv`, packaged copy
`classpath:/climatology/block_doy_normals_full.csv`; never rebuilt here).
This tree is ADDITIVE — the validated 7-day and live weather trees are untouched.

| Endpoint | Returns |
|---|---|
| `GET /api/outlook/17-30` | all 6 blocks: `{issue_date, generated_at, method, w3_definition, w4_definition, blocks[], climate_context, freshness}` |
| `GET /api/outlook/17-30/{blockId}` | one block (`blockId` = name or Bhuvan id, case-insensitive; unknown → 404 `unknown_block`): `{block_name, horizon_label, w3, w4, recent_observed, recent_anomaly, confidence, confidence_reason, climate_context, narrative, status, issue_date, generated_at}` |
| `GET /api/outlook/freshness` | input freshness WITHOUT upstream fetch: `{available, issue_date, generated_at, climatology_vintage, recent_14d, recent_30d, climate_context, confidence_cap_note}` (missing climatology → `{available:false, reason}`) |

### W3/W4 semantics

- D = issue date (today IST, explicit `issue_date` in every response).
- W3 = sum over D+17..D+23; W4 = sum over D+24..D+30, per block.
- DOY wheel follows Phase 3A (`Feb-29 → 60`, else non-leap reference +1 from Mar-01).

### Probability semantics

- `below_probability / near_probability / above_probability` are the
  climatological tercile prior (1/3 each, sum ≈ 1) with
  `probability_method = "climatological_tercile_prior …"`.
- The frozen artifact stores tercile thresholds, not a distribution — so the
  baseline honestly reports the prior rather than inventing a calibrated
  forecast. MJO/IOD/ENSO/recent rainfall NEVER modify these probabilities.

### Climatological reference semantics

- `climatological_normal_mm` = expected 7-day window sum from frozen daily
  means (sum over the window's 7 calendar DOYs), labelled
  `"CLIMATOLOGICAL NORMAL / REFERENCE — not forecast rainfall"`.
- `tercile_t33_mm / tercile_t66_mm` = frozen W3/W4 tercile thresholds for the
  issue DOY. `wet_day_probability` = frozen wet-day probability.
- NEVER deterministic daily mm for days 17–30; missing rows →
  `{status:"unavailable", reason}` (never zero-filled).

### Confidence semantics

- `confidence` ∈ {MODERATE, LOW} in Phase 3B (HIGH is never issued for a
  climatology-only baseline). Rules: base MODERATE in JJAS; LOW outside JJAS
  ("climatology-dominated / low information"); stale/unavailable climate
  context caps at LOW; unavailable recent rainfall caps at LOW.
- Freshness thresholds (conservative, explicit): MJO stale if flagged stale
  or `observation_end` older than 14 days vs D; ENSO/IOD stale if vintage
  month older than 2 months vs D (IOD explicit `stale` flag also honoured).
  Unavailable inputs count as stale for capping.

### Stale/unavailable behavior

- Missing climatology → 503 `outlook_unavailable` (explicit, never synthetic).
- Recent rainfall via `RecentRainfallService` (trailing 14/30 d); unconfigured
  or gappy → `{available:false}` with nulls (never zero).
- `recent_anomaly` is `{available:false, reason}` (no validated trailing-window
  reference in the frozen artifact; totals shown as context only).
- Unknown block → 404 `unknown_block` with `valid_blocks` (same semantics as
  `/api/weather/*`).

### Climate-context labeling

- `mjo.label` = "Context only — not used in W3/W4 probability calculation".
- `iod.label` = "Context only — not used in W3/W4 probability calculation".
- `enso.label` = "Climate regime context — not used directly to calculate
  W3/W4 probabilities". Each carries value/status, vintage, and stale flag.

## Field reference (per block)

- `block_id` (string, stable Bhuvan scheme), `block_name`, `forecast_7d_total_rainfall_mm` (mm),
- `category` ∈ {LOW, NORMAL, HIGH} (thresholds LOW<10.94 / HIGH>34.66 mm, training-only),
- `probability` {low, normal, high} ∈ [0,1], sum ≈ 1 within 1e-3 (residual-ECDF, train-only; enforced by backend validation, documented — not expressible in vanilla JSON Schema),
- `daily_forecast[7]` each {date, day 1..7, rainfall_mm}; total == sum(daily) within 0.05 (presentation rounding),
- `indicators` {max_daily_rainfall_mm, max_daily_rainfall_date, wet_days, dry_days} (wet ≥ 1.0 mm/day prototype heuristic),
- `advisories[]` each {level ∈ {info, watch}, topic, message, label=prototype}.

## Freshness (P0)

Staleness rule (identical in `src/utils/forecast_freshness.py`, `RealForecastService`, `app.js`/`portal.js`):

```
age_days = today − issue_date (calendar days)
expired  = today > valid_to
stale    = expired OR age_days > 2
```

- A stale package is a VALID state (the latest AVAILABLE CHIRPS-GEFS bundle may be
  older than today) and MUST be exposed: `/api/health` (`stale`, `age_days`,
  `generated_at`, `expires_at`), `/api/forecast/freshness`, and frontend banners.
- A stale forecast must NEVER render identically to a fresh one. Frontend shows
  `Issued <d> · Valid <a>–<b> · STALE/EXPIRED (<n>d old)`.
- Refresh procedure (no restart required when `saarthi.forecast.path` points at the
  NB06 package, else rebuild): re-run NB05 → NB06 → copy
  `data/processed/application/{latest_forecast.json,blocks.json,sangrur_blocks.geojson}`
  → `POST /api/forecast/reload`. On failure the previous forecast keeps serving.

`api/health.json` fixture mirrors the health response shape.

## NB05 vs NB06 representations

- NB05 internal (`data/processed/live_forecast/latest_block_forecast.json`): per-block
  `daily_rainfall_mm[7]`, flat `prob_low/prob_normal/prob_high`, `rainfall_category`,
  `max_daily_rainfall_day`, `context{soil…}`. Pipeline-internal only.
- NB06 public (`data/processed/application/latest_forecast.json` → served by the API):
  `system` + `forecast` (`daily_forecast[{date,day,rainfall_mm}]`, `probability{low,normal,high}`,
  `indicators`, `advisories`) + `summary`. Validated by `docs/api/forecast-schema.json`.

## Limits (must surface in UI)

7-day block outlook only; no village-level, onset-guarantee, yield, IOD/MJO/ERA5/SMAP/S2S claims. Method: Raw CHIRPS-GEFS (ML did not beat it). Dry-spell / "dry break" guidance is a prototype rainfall-deficit heuristic — not an IMD onset/break forecast. No IMD onset/withdrawal criteria are implemented.
