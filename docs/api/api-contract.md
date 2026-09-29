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
| `POST /api/chat` | **SAARTHI Agri-Advisor**: `{reply, mode, language, contextUsed}`. `mode` is `gemini` or `local_fallback`. Answers from live SAARTHI context (16-day ECMWF IFS forecast, identity, crop reference facts) when `GOOGLE_API_KEY` is configured, otherwise from the deterministic local reference corpus. Optional `farmerId` (top-level, or `context.farmerId`): the stored crop/location fills any blank context field; explicit context always wins; unknown ids degrade to anonymous. `contextUsed` echoes an agronomic-only `farmer` block (no PII beyond crop/location). 400 blank/missing message, 413 over 2000 chars. |
| `GET /api/farmers` | All stored farmer profiles: `{farmers[], count}` (in-memory store seeded from the teammate's starting data; no standalone server). |
| `GET /api/farmers/{id}` | Exactly one farmer profile `{farmer}` (case-insensitive id); unknown → 404 `unknown_farmer`. Only the requested record is returned. |
| `POST /api/farmers` | Create/update a farmer profile (required: `farmerId, farmerName, crop, cropLocation, district, state`; optional: `landSize, sowingDate`). Returns 201 `{message, farmer}`; missing fields → 400 `bad_request`. |
| `POST /api/voice/transcribe` | **Bhashini ASR** (Hindi/Punjabi/Telugu voice layer): `{audio (base64 WAV), language}` → `{text, language, available, source:"bhashini"}`. Fail-soft: unconfigured credentials, timeouts and provider errors return HTTP 200 `{text:"", available:false, fallback:"browser"}` — never a 500. Missing audio → 400 `audio_required`; oversized → 413. |
| `POST /api/voice/speak` | **Bhashini Indic TTS** (Hindi/Punjabi/Telugu voice layer): `{text, language}` → `{audio (base64 WAV), text, language, available, source:"bhashini", contentType:"audio/wav"}`. English and unavailable-provider cases return HTTP 200 `{audio:null, available:false, fallback:"browser"}` for browser speech synthesis. Missing text → 400 `text_required`; over 2000 chars → 413. Credentials never leave the server. |

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

## Climate Intelligence endpoints (`/api/intelligence`)

Cross-sector, rule-based reading of the SAME live ECMWF IFS forecast already
served by `/api/weather/*`. `ForecastContextService` builds **one** context per
request (cached bulk path for the 6 legacy Sangrur blocks, single-point centroid
path for every other registry block) and all four sector evaluators consume that
one object — a request never issues more than one provider call.

DISPLAY-ONLY. No probabilities, no risk scores, no calibrated likelihoods. Road
status, warehouse condition, grid load and groundwater level are never measured
or inferred as measurement; each sector result always carries `assumptions[]` and
`validation_note`. Thresholds live in `resources/intelligence/sector-thresholds.json`
(`method_version` = `sector-v1`); the frozen `FieldWorkRule` wet-day rule
(≥ 1.0 mm/day) and `HeavyRainThresholds.dailyP95` are reused unmodified.
Missing input stays `null` through every derived aggregate — never zero-filled,
and a sector whose window is incomplete returns `state:"UNAVAILABLE"` rather
than a low-risk verdict.

| Endpoint | Returns |
|---|---|
| `GET /api/intelligence/context` | envelope only — the shared forecast + environment context, no sector verdicts |
| `GET /api/intelligence/risks` | envelope + `sectors` with all four sectors (`agriculture`, `logistics`, `warehouse`, `energy_groundwater`) |
| `GET /api/intelligence/grid-groundwater` | envelope + `energy_groundwater` (irrigation-pressure indicator only) |
| `GET /api/intelligence/logistics-warehouse` | envelope + `sectors` with `logistics` and `warehouse` only |

### Selection modes

All parameters are optional; exactly one selection mode must resolve. Resolved in
this order (`ForecastContextService.build`):

| Mode | Parameters | Path |
|---|---|---|
| Registry triple | `state` + `district` + `code` (codes) | `geography.findBlock(state, district, code)` → single-point centroid at the block centroid (`location_method` = `polygon_centroid` or `single_point_centroid`) |
| Coordinates | `lat` + `lon` (+ optional `name` as the label) | single-point centroid; `name` defaults to `centroid (lat, lon)` |
| Legacy block | `block` (name or Bhuvan id, case-insensitive) | the 6 Sangrur blocks only, served from the cached bulk forecast |

`lat`/`lon` must be supplied together and stay within ±90 / ±180. Incomplete
triples and out-of-range coordinates are rejected rather than defaulted — no
silent fallback to a default block.

### Envelope

Every endpoint returns `{provider, model, stale, context}` at the top level.
`provider` = `Open-Meteo` (delivery layer), `model` = `ECMWF IFS (ecmwf_ifs)`
(NWP model). The issue date is `context.issue_date` (there is no top-level
`issue_date` field); clients read `context.issue_date`.

`context` (`ForecastContext.toMap()`):

- identity: `block_name, display_name, state_code, state_name, district_code,
  district_name, block_code, latitude, longitude, location_method`,
- provenance: `provider, model, issue_date, retrieved_at, stale, stale_warning?,
  spatial_method`,
- `days[]` each `{date, horizon_day 1..16, rainfall_mm, rain_probability_pct,
  temperature_max_c, temperature_min_c, et0_mm, soil_moisture_0_to_7cm_vwc,
  rain_mm, showers_mm, weather_code}` (mm / % / °C, provider-native; `null` when
  the provider did not return the field), plus `horizon_days`,
- `aggregates`: `rain_3d_mm, rain_7d_mm, et0_7d_mm, rain_minus_et0_7d_mm,
  max_daily_mm, max_daily_date, wet_days_d1_d3, wet_days_d1_d7, dry_days_d1_d7,
  longest_dry_run_d1_d7, trailing_dry_run, soil_moisture_day0_vwc` — each `null`
  unless every contributing day is present,
- `soil`: `{available:true, line, source}` or `{available:false, reason}` —
  fail-soft SoilGrids context, never a measurement,
- `missing[]`: which inputs were unavailable, e.g. `rain_3d_unavailable`,
  `et0_7d_unavailable`, `soil_moisture_day0_unavailable`, `soil_line_unavailable`.

### Sector result

Each entry of `sectors` (and the top-level `energy_groundwater` on
`/grid-groundwater`):

- `sector`, `state` ∈ {`LOW`, `MODERATE`, `HIGH`, `UNAVAILABLE`}, `available` (bool),
- `unavailable_reason` (only when `available:false`),
- `reasons[]` (why), `actions[]` (suggested), `assumptions[]` (limits),
  `validation_note`,
- `evidence` object — `agriculture`: `wet_days_d1_d3, max_daily_mm_d1_d3,
  daily_mm_d1_d3, heavy_rain_p95_mm, heavy_day_present`; `logistics`:
  `wet_days_d1_d3, rain_3d_mm, max_daily_mm, max_daily_date`; `warehouse`:
  `wet_days_d1_d7, rain_7d_mm, soil_moisture_day0_vwc`; `energy_groundwater`:
  `dry_days_d1_d7, longest_dry_run_d1_d7, rain_7d_mm, et0_7d_mm,
  rain_minus_et0_7d_mm, soil_moisture_day0_vwc, dry_surface_soil`. An
  unavailable sector carries `evidence.unavailable_reason` instead.

### Errors

| HTTP | `error` | Cause |
|---|---|---|
| 400 | `bad_request` | no usable selection mode; incomplete registry triple; `lat` without `lon`; coordinates out of range |
| 404 | `unknown_block` | `?block=` names a block outside the 6 legacy Sangrur blocks (response includes `valid_blocks`), or a `state`/`district`/`code` triple is not in the registry |
| 502 | `provider_error` | upstream Open-Meteo failure with no cached forecast to serve |
| 503 | `forecast_unavailable` | the live forecast carries no usable data for the requested block |

All error bodies are `{error, message}` (plus `valid_blocks` on the
Sangrur-only 404). A sector-level problem is NOT an HTTP error: it is reported
in-band as `state:"UNAVAILABLE"` with `available:false`.

## CropAtlas endpoints (`/api/cropatlas`)

Global Crop Discovery. An explainable, requirements-driven **compatibility**
assessment of the selected block against documented crop requirements. This is
NOT an ML prediction, NOT a probability of success, and NOT a yield guarantee —
`methodology.is_ml_prediction`, `is_probability` and `is_yield_guarantee` are
all `false` in every response.

Reuses the existing stack: `GeographyService` (identity), `LiveWeatherService`
(live IFS, shared cache), `SoilGridsClient` + the bundled `risk/soil_context.json`
(soil), `ClimatologyContext` (reference rainfall normals). No second geography,
weather or soil system exists.

| Endpoint | Returns |
|---|---|
| `GET /api/cropatlas/context` | `location` + `fingerprint` + `methodology`. The measured environment only; no crop assessment. |
| `GET /api/cropatlas/crops` | the crop requirement catalogue as served, plus `not_available_dimensions`. No location needed. |
| `GET /api/cropatlas/recommendations` | `location` + `fingerprint` + `groups` + `candidates` + `global_comparison` + `methodology` + `sources`. |

### Selection

Same identity convention as `/api/weather/*`: the registry triple, or a legacy
block name. There is **no default block** — an absent selection is 400, never a
silent Sangrur substitution.

| Mode | Parameters | Path |
|---|---|---|
| Registry triple | `state` + `district` + `code` | `geography.findBlock(...)` → live forecast at the block centroid (`location_method` = `polygon_centroid` or `single_point_centroid`) |
| Legacy block | `block` (name or Bhuvan id) | the 6 Sangrur blocks only, from the cached bulk forecast, with bundled block soil means |

`state`, `district` and `code` must all be present when any one of them is used.
When both `block` and a triple are supplied, `block` wins (legacy behaviour).

### Fingerprint

`fingerprint` carries only observed values; each dimension is tagged
`live` / `cached` / `historical` / `reference` / `unavailable`.

- `location` — `block_name, block_code, state_code, state_name, district_code,
  district_name, latitude, longitude, location_method, label`
- `climate` (`live`) — `provider, model, issue_date, spatial_method, stale,
  horizon_days, temp_min_mean_c, temp_max_mean_c, temp_min_low_c, temp_max_high_c,
  rain_7d_mm, rain_16d_mm, et0_7d_mm, water_balance_7d_mm, wet_days_d1_d7,
  dry_days_d1_d7, soil_moisture_day0_vwc`. Aggregates require every contributing
  day present; otherwise `null` (never zero-filled).
- `soil` (`reference`, or `cached` on a coordinate-cache hit) — `available,
  clay_g_kg, sand_g_kg, silt_g_kg, soc_g_kg, ph, cec_cmol_kg, texture_class,
  source, depth`. `texture_class` is always `null`: CropAtlas does not derive a
  texture taxonomy. Values come from the official SoilGrids WCS subset service
  (`maps.isric.org`, coverages `{phh2o,sand,silt,clay,soc,cec}_0-5cm_Q0.5`,
  0–5 cm median at the block coordinate, ~2 km subset, centre pixel read);
  `phh2o`/`soc`/`cec` rasters are stored ×10 and divided by 10, texture rasters
  are served at bundle scale (g/kg). The bundled block means (six legacy
  blocks) backfill only the properties WCS could not serve and carry no CEC,
  so CEC stays `null` there; mixed provenance is disclosed in `source`.
  Each property is independently fail-soft — one failing coverage never hides
  the properties that did serve.
- `climatology` (`historical`) — `available, block, growing_window_normal_mm,
  growing_window_days, vintage`. Present only for blocks that ship a day-of-year
  normal record. `growing_window_normal_mm` stays `null` at block level because
  the growing window is crop-specific; per-crop window normals are computed in
  the `climate` suitability component for blocks with normals.
- `elevation_m` / `elevation_provenance` — block elevation from the keyless
  Open-Meteo elevation API (Copernicus DEM), cached per coordinate; `reference`
  fresh, `cached` on repeat, `unavailable` when the coordinate or source is
  missing. Context only, never a field survey.
- `cec_cmol_kg` / `cec_provenance` — SoilGrids CEC where the point query
  returned it, else `null` / `unavailable`. Informational only: the crop
  reference states no per-crop CEC requirement, so CEC is never scored.
- `missing[]` — machine reason codes, e.g. `soil_unavailable`,
  `climatology_normals_unavailable`, `water_balance_7d_unavailable`,
  `elevation_unavailable`, `cec_unavailable`, `cec_unavailable_no_source`
  (soil itself unavailable).
- `sources[]` — `id, title, publisher, url, provenance, note`.

### Candidate

`groups` partitions `candidates` into `strong_matches`, `potential_matches`,
`limited_matches` and `insufficient_data` (good/moderate match vs limited/poor vs
not assessable). No crop is ever labelled "best".

Each candidate is `{band, crop, block_context}` where `crop` is:

- `crop_id, crop_name, aliases, group, season`,
- `suitability` ∈ {`excellent_match`, `good_match`, `moderate_match`,
  `limited_match`, `poor_match`, `insufficient_data`},
- `compatibility_score` (0–1) with `score_available` and a `score_label` stating
  it is a rule-based compatibility score, **not** a probability. `null` when
  fewer than `methodology.min_components_for_overall` components could be
  evaluated.
- `components.{climate,soil,water,growing_season}` each
  `{label, available, score, unavailable_reason?, reasons[], constraints[]}`. An
  unavailable component keeps `score:null` plus a reason and is excluded from the
  overall score.
- `why[]`, `watch[]`, `agronomic_considerations[]`, `missing_data[]`,
  `data_confidence` ∈ {`high`, `moderate`, `low`, `insufficient`},
  `reference_ids[]`, `source_ids[]`.

`block_context` pairs the crop's demand with the block's measured values
(`water_balance_7d_mm`, `rain_7d_mm`, `et0_7d_mm`, `soil_*`, plus
`crop_demand.water_need_class / waterlogging_tolerance / drought_sensitivity /
duration_days`).

### `global_comparison`

Returns `framing` ("Comparable agricultural environments"), `disclaimer`,
`available`, `regions[]` and, when unavailable, a `message`. **This repository
contains no verified global agricultural-region dataset, so `regions` is empty
and `available:false`**; the matching implementation is live and activates when
sourced regions are added to `cropatlas/global-regions.json`. No region is
invented.

### Requirements provenance

`cropatlas/crop-requirements.json` (`cropatlas-req-v1`) carries crop-side
requirements **unchanged** from the repository's source-cited
`agronomy/crop_reference.json` (PAU Package of Practices Kharif/Rabi +
ICAR-IARI). Block-side classification bands and the combination rule live in
`cropatlas/atlas-method.json` (`cropatlas-method-v1`) and are explicitly
rule-based planning defaults, not calibrated science.

`not_available_dimensions` declares what the consulted reference does **not**
state — per-crop temperature range, soil pH range, soil texture class, seasonal
rainfall mm, CEC requirement, quantitative irrigation mm. Those dimensions are
therefore never scored, are reported in `methodology.not_scored_dimensions`, and
lower `data_confidence` instead of being invented.

### Errors

| HTTP | `error` | Cause |
|---|---|---|
| 400 | `bad_request` | no selection supplied, or an incomplete registry triple |
| 404 | `unknown_block` | `?block=` is not one of the six legacy blocks, or the triple is not in the registry |
| 502 | `provider_error` | upstream Open-Meteo failure with no cached forecast |
| 503 | `forecast_unavailable` | the live forecast carries no usable data for the block |

All error bodies are `{error, message, no_fallback:true, note}` — CropAtlas
returns no synthetic or substitute data.

## Prices & Inputs endpoints (`/api/prices`) - REMOVED

This endpoint tree **no longer exists**. The Prices & Inputs feature was removed in
full on 2026-09-25 because no verified, reproducible price source was available:
`agmarknet.gov.in` publishes no machine-readable data, `https://fert.gov.in/` serves
HTML only, and `api.data.gov.in` requires a registered API key. `GET /api/prices/mandi`
and `GET /api/prices/inputs` now return **404**, and `/prices` is no longer routed.

No price figures were ever fabricated. If a verified source is connected later, the
contract documented in git history (live -> snapshot -> unavailable, and never
zero-filling an unavailable price) should be restored as-is.
## Agri-Advisor chat endpoint (`POST /api/chat`)

The SAARTHI Agri-Advisor is a floating assistant available on every main page
(`/`, `/farmer`, `/timeline`, `/map`, `/intelligence`, `/cropatlas`). It answers
agricultural questions using context the platform already holds, and is not a
generic chatbot.

### Request

```json
{
  "message": "What should I do if heavy rain is forecast?",
  "context": {
    "state": "3",
    "district": "43",
    "block": "340",
    "crop": "Wheat (HD-2967)",
    "language": "en"
  }
}
```

`message` is required and **must be a JSON string**. A number, boolean, array,
object, JSON `null`, an absent field, or a blank/whitespace-only string is
rejected with HTTP 400 — Jackson is prevented from coercing a non-string into
text. Every `context` field is optional and may be absent. `language` is `en` or
`hi` only. Context strings are trimmed, length-capped and stripped of newlines,
so a client cannot smuggle prompt scaffolding through a location or crop field.
Messages are capped at **2000 characters**.

Optional `farmerId` (top-level `"farmerId": "F001"`, or `context.farmerId` for
older clients) attaches the stored farmer profile: its crop/location fills any
blank `context` field, with explicit context winning on conflict. Weather still
comes from the existing `LiveWeatherService` forecast — never mock values. An
unknown id degrades to an anonymous answer (with a `farmer.contextUsed:false`
note in `contextUsed`), never an error. `contextUsed` carries an
agronomic-only `farmer` summary (id, crop, location — no names or other PII).

### Response

```json
{
  "reply": "...",
  "mode": "local_fallback",
  "language": "en",
  "contextUsed": {
    "location": { "block": "Andana", "state": "Punjab", "district": "Sangrur" },
    "forecast": { "rain7dMm": 6.4, "horizonDays": 16, "status": "live forecast — a forecast, not an observation" },
    "crop": { "crop": "Wheat (HD-2967)", "season": "rabi", "waterNeedClass": "high" },
    "unavailable": ["soil_moisture_forecast"]
  }
}
```

`mode` is `gemini` or `local_fallback`, and the client renders it verbatim so the
farmer always knows which engine answered.

### Errors

| HTTP | `error` | Cause |
|---|---|---|
| 400 | `bad_request` | missing or blank `message` |
| 400 | `malformed_request` | body is not the expected JSON shape |
| 413 | `message_too_long` | message exceeds 2000 characters |

Error bodies are `{error, message}` (plus `maxChars` for 413) and never contain a
stack trace or the parser's own message. A Gemini failure is **not** a client
error: the request still returns 200 with a local-fallback answer.

### Gemini configuration

`GOOGLE_API_KEY` is read server-side only, in this order:

1. the `GOOGLE_API_KEY` environment variable;
2. `./.env`;
3. `../.env`;
4. `../../.env`.

Only relative paths are opened — there is deliberately no API for an absolute
path. Exactly one mode line is logged at startup: `GEMINI MODE (API key found via
<origin>)` or `LOCAL FALLBACK MODE (no GOOGLE_API_KEY configured)`. The key is
never logged, never returned, and never placed in a URL — it travels only in the
`x-goog-api-key` request header.

`.env` is gitignored; `.env.example` is the tracked, value-free template.

The Gemini model is `gemini-3.8-flash` by default (older Flash models are shut
down or refuse new API keys with 404) and overridable without a rebuild via
`saarthi.chat.gemini.model`. The request follows the `generateContent`
contract (`contents[].role/parts[].text`, `systemInstruction`, parsed via
`candidates[].content.parts[].text` across any number of candidates/parts).
At debug level the client logs `GEMINI REQUEST START`, `GEMINI HTTP STATUS`,
`GEMINI RESPONSE PARSED` / `GEMINI EMPTY CANDIDATES`, and `GEMINI FALLBACK:
<reason>` (`NO_API_KEY`, `INVALID_API_KEY`, `INVALID_MODEL`, `RATE_LIMITED`,
`HTTP_<code>`, `TIMEOUT`, `MALFORMED_RESPONSE`, …) — never the key, headers,
prompts, or raw provider errors.

### SAARTHI context passed to the model

`SaarthiChatContext` builds the context from existing services, reusing their
logic rather than duplicating it:

| Source | Contributes |
|---|---|
| `GeographyService` | block / district / state identity from the (state, district, code) triple, or a legacy block name |
| `LiveWeatherService` | 16-day ECMWF IFS forecast: 3-day and 7-day and 16-day rainfall, 7-day ET0, mean and peak temperature, dry-day count, modelled 0–5 cm soil moisture, provider/model, staleness |
| `ClimatologyContext` | block day-of-year rainfall-normal vintage |
| `ForecastContextService` + `GridGroundwaterService` + `SectorRiskService` (Climate Intelligence) | the four existing sector verdicts — `agriculture`, `logistics`, `warehouse`, `energy_groundwater` — each with the engine's own `state`, `reasons`, supporting `evidence`/`actions`, `unavailableReason`, and `validationNote` |
| `agronomy/crop_reference.json` | season, reference duration, sowing window, water-need class, waterlogging/drought sensitivity, growth stages, irrigation guidance — each with its `source_ids` |
| `CropAtlasSoilSource` (shared with CropAtlas, informational only) | measured 0–5 cm soil properties actually served for the block (pH, sand/silt/clay, SOC, CEC) with `reference`/`cached` provenance; missing values stay missing |

A quantity that is unavailable is `null` and is named in `unavailable`; a missing
forecast day never contributes a zero to a total. The context also carries a
`notAvailable` block stating that SAARTHI has no market prices, no yield estimate
and no government-scheme data, and that its soil figures are modelled rather than
field-measured.

**Sector verdicts are reused, never recomputed.** The chatbot calls the same
services the Intelligence page calls, so it cannot disagree with it. No new risk
score, ranking, or probability is created, and each verdict travels with the
engine's own `validationNote` (rule-based planning defaults; live IFS transfer of
the FIELD_HIGH rule still pending) so the model repeats the engine's own caveat
rather than implying scientific validation. When the engine could not decide, the
sector state is omitted rather than quoted as a level.

### Local fallback

The fallback is **context-aware**, not a fixed paragraph. The topic rules still
choose the shape of the answer; the numbers in it come from the platform, and
where a value is missing the answer says so instead of substituting one.

| Question | Context used |
|---|---|
| heavy rain | 3-day and 7-day rainfall, block name; if rainfall is absent it states it cannot give a rain-based recommendation |
| field work | the Agriculture sector `state` and its own `reasons`; if the engine has no verdict it says it has not enough forecast information |
| explain my forecast | horizon, 3/7-day rainfall, temperature range, staleness, and an explicit "forecast, not a measurement" caveat |
| irrigation | 7-day rainfall and the crop's reference water-need class; it refuses to compute an irrigation quantity |
| sowing | the crop's reference season, sowing window and duration, plus 7-day rainfall; it never claims a calendar alone makes sowing safe |
| soil | modelled 0–7 cm soil moisture, always labelled modelled and never a field reading |
| growth stages | the reference stage sequence, noting the real stage is only visible in the field |
| Agriculture / Logistics / Warehouse / Energy | the corresponding sector `state`, the engine's own `reasons`, and its `validationNote` |

Sector questions are answered by the existing engine. When no verdict exists the
answer is "SAARTHI does not currently have enough information to determine the
\<sector\> risk for this location", never an invented level.

When Gemini is unconfigured, times out, errors, or returns nothing usable, the
answer comes from `LocalAdvisoryCorpus`: a deterministic, keyword-matched set of
guidance topics (heavy rain, field work, forecast explanation, sowing,
irrigation, pests, soil, growth stages) available in English and Hindi, plus crop
facts loaded from the repository's cited reference. There is no sampling and no
generation, so the same question always yields the same answer.

A question with no matching topic and no crop is **refused explicitly** rather
than improvised, pointing the farmer to the crop-specific advisory or a local
agricultural officer.

### Limits

- The local fallback is not conversational: it answers the topics it covers and
  declines the rest.
- The fallback is in English and Hindi only; there is no Punjabi.
- Gemini answers are model output, not verified fact. The system instruction
  forbids inventing rainfall, temperature, soil, crop suitability, prices,
  yields, probabilities or schemes, and requires that a forecast never be
  presented as certain.
- High-stakes decisions (spraying, irrigating a sensitive stage, a costly input
  purchase) should be confirmed with a local KVK, agriculture department, or
  certified adviser.


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
