# 11 — Live Weather Architecture (Phase 1+2: Open-Meteo + ECMWF IFS)

Status: IMPLEMENTED and live-verified 2026-09-19. Authoritative for the operational
weather layer. The validated historical CHIRPS-GEFS package (NB01–NB06, `/api/forecast/*`)
is retained untouched as historical validation/background.

## 1. Purpose

Give SAARTHI a live, block-level rainfall feed for the 6 legacy Sangrur blocks without
SAARTHI doing numerical weather prediction itself. SAARTHI consumes an operational
forecast and adds hyperlocal agricultural intelligence on top.

## 2. Why an operational provider

Running NWP in-house is out of scope for an SIH prototype. An operational centre
(ECMWF) already produces a skilful deterministic medium-range forecast; SAARTHI's value
is spatial downscaling to blocks, honest presentation, caching/freshness, and farm
advisory logic — not beating the weather model.

## 3. Open-Meteo as the delivery layer

Open-Meteo (`https://api.open-meteo.com`) is a keyless data-delivery API, NOT a weather
model. `GET /v1/forecast` with batched comma-separated coordinates serves third-party
NWP output as JSON. No API key, no secret is stored anywhere (`application.properties`
contains only commented defaults).

## 4. ECMWF IFS as the selected model

Request pins `models=ecmwf_ifs` (ECMWF Integrated Forecasting System, deterministic).
`provider` = `Open-Meteo`, `model` = `ECMWF IFS (ecmwf_ifs)` — the two are never
conflated. Switching models later means changing one selector + re-verifying, not a
rewrite (`WeatherProvider` interface).

## 5. Forecast horizon

`forecast_days=16` → 16 `Asia/Kolkata` calendar-day dates. Days 1–7 are the operational
window, 8–15 extended/lower-confidence, 16–30 NOT served (no deterministic feed for
that range; days are never invented or zero-filled). The API says so explicitly
(`horizon_note`, `cum_30d.available=false`).

## 6. Daily precipitation calculation

Authoritative value: provider-aggregated `daily.precipitation_sum` (mm). Hourly
`precipitation` is fetched alongside and aggregated to local calendar days
(`aggregateHourlyToDaily`) to cross-check day-boundary behaviour. Missing precipitation
at a point stays `null` through every layer — never zero-filled.

## 7. Asia/Kolkata daily interpretation

Requests pin `timezone=Asia/Kolkata`; daily/hourly `time` stamps arrive provider-localised,
so grouping hourly steps by date part IS the local-day aggregation. Day-boundary tests
(23:00 vs 00:00 split, null-hour skipping) lock this in `DailyAggregationTest`.

## 8. Block spatial aggregation

Open-Meteo serves coordinate forecasts, not its native grid, so true area-weighted
grid/polygon intersection is impossible. Documented best-available approximation per
block (`BlockSampler`): 3×3 grid over the polygon bounding box → keep candidates inside
the exterior ring (ray-casting; holes NOT excluded — acceptable, none of the six
Sangrur polygons contain holes) → uniform mean of point forecasts. Centroid is a
labelled fallback only (`centroid_fallback …`), never presented as the solution.
Live smoke test 2026-09-19: all six blocks resolved to multipoint means (n4–n7
samples), zero fallbacks. Block rainfall = mean of `daily.precipitation_sum` across the
block's points per date; a day null at every point stays null. Cumulatives (3/7/15-day)
require all n days present and non-null.

## 9. Six Sangrur blocks

Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam — from the authoritative Bhuvan
polygons via `RealForecastService.getGeoJson()` (single source; no duplicated geography
in JS). Block lookup accepts name or Bhuvan id, case-insensitive.

## 10. Cache / freshness

One provider call serves all blocks; results cached for
`saarthi.weather.cache-ttl-minutes` (default 60). Fresh cache → served as-is (provider
not contacted). Expired cache + provider failure → cached payload with `stale:true` and
an explicit `stale_warning` (HTTP 200, never silent). No cache + provider failure →
502 `provider_error`, no synthetic data. `GET /api/weather/freshness` reports cache state
without fetching. Open-Meteo exposes no per-run initialisation timestamp (only
`generationtime_ms`), so `model_run_time` is `null` by design and `retrieved_at` is the
freshness anchor.

## 11. Recent rainfall separation

`RecentRainfallService` reads OPTIONAL local CHIRPS block CSV (`saarthi.chirps.path`;
unset by default → `{available:false}`) and exposes `observed_last_*_mm` strictly
separate from forecast fields. Observed history is never mixed into, or presented as,
forecast. `Lehragaga` normalises to `Lehra`.

## 12. API endpoints

- `GET /api/weather/forecast` — all six blocks (16 daily dates each).
- `GET /api/weather/forecast/{blockId}` — one block; unknown → 404 `unknown_block`.
- `GET /api/weather/freshness` — cache state, no fetch.
- Full shapes, units, and error semantics: `docs/api/api-contract.md` (Phase 1+2 section).
- Frontend: timeline page “Live Operational Outlook” card (`portal.html`/`portal.js`,
  vanilla JS, Leaflet untouched) fetches the block endpoint per selected block and shows
  loading / error / stale / unavailable states.

## 13. Error handling

429/timeout/empty-body/malformed/error-object → `WeatherProviderException` →
502 `provider_error` (no cache) or explicit stale serve (with cache). Unknown block →
404. Short horizon → `{available:false}` cumulatives, never zeros. UI shows each state
in words; nothing renders as fresh when it is not.

## 14. What this phase DOES NOT do

No 17–30-day deterministic forecast. No village-level downscaling. No onset/withdrawal
criteria, yield prediction, or IOD/MJO/ERA5/SMAP/S2S claims. No retraining, no threshold
or geography changes, no historical bulk download (exactly ONE bounded live request was
used for verification plus normal backend smoke tests against the live feed).

## 15. No outperformance claim

SAARTHI does not claim to outperform ECMWF IFS at rainfall prediction. The prototype's
claim is narrower: deliver the operational forecast honestly at block scale with
freshness guarantees, and convert it into farm-actionable advisories.

## 16. Phase 3 direction

Days 16–30 and seasonal context come from climate intelligence (ENSO/IOD/MJO +
CHIRPS-GEFS historical validation), kept strictly separate from the deterministic
16-day feed — a probabilistic/climate-informed outlook, not an extension of IFS
day 16. The `cum_30d.available=false` reason string already reserves this seam.

## 17. Known limitations

- Point-mean ≠ area-weighted truth; sub-block variance (e.g. convective cells) is smoothed.
- 16-day horizon only; day 16+ rainfall structurally unavailable.
- No per-run model timestamp — staleness is measured from retrieval, not model init.
- `recent_observed` unavailable unless an operator configures the CHIRPS path.
- Single-provider dependency (no multi-model ensemble yet).

## 18. Live verification performed (2026-09-19, this session)

1. Bounded request: `GET /v1/forecast?latitude=30.24000&longitude=75.89000&hourly=…&daily=precipitation_sum,…&timezone=Asia/Kolkata&forecast_days=16&models=ecmwf_ifs` → HTTP 200, ~16 KB, ~1.2 s, keyless.
2. Response: 16 daily dates (2026-09-19..2026-10-04), 384 hourly steps, units mm/°C/%/km/h, `utc_offset_seconds=19800`, daily keys exactly as the parser expects, trailing-day `precipitation_sum=null` (null-path validated by real data, not just mocks).
3. Backend smoke: rebuilt jar, `GET /api/weather/forecast` → 200, 6 blocks × 16 days, multipoint methods n4–n7; `GET /api/weather/forecast/{block}` → 200 for all six blocks; unknown block → 404 `unknown_block`; `GET /api/weather/freshness` → 200 with `retrieved_at`/`age_minutes`/`stale:false`.
4. Tests: `mvn test -Dtest='com.saarthi.weather.*Test'` → 30/30 green (one test bug fixed: stale-serve requires a fetch attempt — expired cache or forceRefresh — so the test now uses TTL 0).
