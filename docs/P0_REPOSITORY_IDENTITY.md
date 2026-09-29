# P0 — Repository Identity Note (DO NOT PUSH until resolved)

Date recorded: 2026-09-14 (P0 hardening, no remote change made).

- Expected authoritative repository (per project brief):
  `https://github.com/Swarnim1311/Saarthi-final`
- Actual local `git remote -v` (verified 2026-09-14, unchanged by P0):
  `origin  https://github.com/aditya28052006/Saarthi-final.git (fetch+push)`

Decision (P0-A): **origin left unchanged.** No `git remote set-url`, no push,
no fetch of another repository, no replacement of the working directory.
Equivalence of the two repositories was NOT verified (would require
network comparison against an authority we do not control from here).

Required before any push: confirm with the repository owners which URL is
canonical, then set/push explicitly. Until then, treat the LOCAL clone as
the source of truth for all P0 work.

Branch: `main` (up to date with `origin/main` at P0 start; 3 commits).

## P0 completion record (2026-09-15, validated, NOT pushed)

Prior session (uncommitted): freshness rule (`stale = expired OR age_days > 2`)
in `src/utils/forecast_freshness.py` + `RealForecastService` + `app.js`/`portal.js`;
`POST /api/forecast/reload` (rollback on invalid); `/api/health` + `/api/forecast/freshness`
freshness fields; `DistrictDataService` unknown-block 404 (was silent Sunam fallback);
`iod_status.json` DMI numeric (`0.033`); agronomy dry-spell heuristic wording;
`forecast-schema.json` + `api-contract.md` NB06 contract; frontend stale banners.

This session (2026-09-15): removed `FarmerAnalysisRequest` silent Sunam default
(missing/blank/unknown block → 404 `unknown_block`); added
`POST /api/climate-context/reload` (fail-soft) + `saarthi.climate.path`;
documented `saarthi.forecast.path`/`saarthi.climate.path` in
`application.properties`; fixed `portal.html`/`portal.css` "30-day" wording → 7-day;
documented reload endpoint + 404 behavior in `api-contract.md`.

Validation: `mvn clean package -DskipTests` BUILD SUCCESS; bounded live run
(JDK 21, port 5000, server stopped afterwards) — 13/13 checks PASS:
health/blocks/geojson/latest/freshness/summary, Sunam real data (19.48 mm NORMAL),
invalid-block 404, panchayat-invalid 404, farmer-invalid/missing 404, farmer-Sunam 200,
forecast-reload 200, climate-reload 200, IOD DMI numeric (`0.033`);
`node --check` app.js/portal.js clean; `forecast_freshness.py` exit 0 (stale=true).

Protected baseline untouched: Raw CHIRPS-GEFS method, benchmarks, splits,
thresholds (LOW<10.94/HIGH>34.66), MJO/IOD/ENSO models, artifacts. No downloads,
no retraining, no Phase 1, no remote change, no push.
