# 06 — Notebook Status

## Notebook 01: `01_chirps_gefs_acquisition.ipynb` (294854 bytes, 30 cells + title, last executed 2026-09-08)

**Purpose:** Acquire and test the current CHIRPS-GEFS forecast data and extract rainfall for the 6 real Sangrur Bhuvan blocks. Proves single-date extraction before historical bulk.

**Completed cells/work:**
- **1 Imports** (`Path, requests, rasterio, geopandas, pandas, numpy`)
- **2 Paths** (robust `CWD.name=="notebooks"? parent : Path("..")`, creates `forecast/boundaries`, now also `BOUNDARY_DIR` mkdir)
- **3 Forecast date** (`date(2026,9,4)` single source, `forecast_date_str` `2026.09.04`, `forecast_date_path` `2026/09/04`, `url` `.../v3/daily/global/2026/09/04/c3g_2026.09.04.tif`, `output_file` derived, no hardcoded `2026/09/04` elsewhere, atomic `>50 MB + rasterio` skip)
- **4 HEAD** (`requests.head(url)` `200 OK 61.9 MB`)
- **5 Download** (atomic `stream → .tmp → rename`, `>50 MB + rasterio` skip `Already exists: c3g_2026.09.04.tif (64.9 MB) — skipping 0.02s`, `curl -C -` resume)
- **6 Open** (`rasterio.open(output_file)` → `tif_path`)
- **7 Dims** (`7200×2400×1, 17M pixels, float32`)
- **8 Res** (`0.05°`, `transform |0.05,0,-180|`)
- **9 CRS/Bounds** (`EPSG:4326, -180,-60,180,60`, `Sangrur 75.55-76.20` inside)
- **10 Bands/metadata** (`count 1, descriptions (None,), nodata None` sentinel `-9999` 0.83% ocean, `1` band = daily total, not 7-day stack, `LZW`)
- **11 Load boundaries** (now authoritative `sangrur_blocks_bhuvan.gpkg:184320` layer `sangrur_blocks`, not synthetic; lists `6` shp/geojson/gpkg, prefers `bhuvan.gpkg`)
- **12 Inspect 6 blocks** (`b_name` column, `Dhuri/Lehra/Malerkotla/Moonak/Sangrur/Sunam`, `MultiPolygon ×6`, `12-28` pixels/block varying with real admin vs synthetic `48` uniform)
- **13 CRS** (`OGC:CRS84` vs `EPSG:4326` → `to_crs` → `EPSG:4326`, `Match YES`)
- **14 Reproject** (copy, `6` preserved)
- **15 Visual** (`28×30` window `840` valid, red borders)
- **16 Final GDF** (`sangrur_final` 6, `BLOCK_COL=b_name`)
- **17 BLOCK_COL** (`b_name`, 6 unique)
- **18 Coverage** (`Sangrur fully covered True`)
- **19 Overlay** (windowed `Blues` + red)
- **20 Crop** (`rio_mask` `crop=True` → `(1,17,19)`, `75.55-76.45`, do not overwrite `64.9 MB`)
- **21 Inspect cropped** (`288` valid, `4-15 mm`, `NaN` preserved)
- **22 Zonal** (`mean` per block `6.4–12.3 mm` with real admin `7.34/22, 9.91/16, 7.14/28, 8.76/12, 8.28/28, 8.82/28`, `valid_pixel_count` varying)
- **23 Inspect values** (no missing/negative, not all identical)
- **24 Lead structure** (single daily, need 7 daily files for `gefs_d1..d7`)
- **25 Tidy** (`forecast_date,block,lead_day,rainfall_mm` 6 rows)
- **26 Wide** (`gefs_d1` real, `gefs_d2..d7 = NaN` explicit, `gefs_3d/7d NaN`)
- **27 Save** (`data/processed/chirps_gefs/chirps_gefs_sangrur_2026-09-04.csv:280` + `_qc.csv` + `_wide.csv`)
- **28 Validate** (6 blocks, date match, numeric, no negative, `PASS`)
- **29 Final display** (CSV, stats `mean 9.14`, 6 names, `NoData 0, Negative 0`, map, `9× PASS`)

**Important outputs:**
- `data/raw/forecast/c3g_2026.09.04.tif:64908286` (`7200×2400×1`, `65 MB`, valid)
- `data/raw/forecast/c3g_2019.09.04.tif:67698552` (`67 MB`, historical test)
- `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg:184320` (authoritative 6 `MultiPolygon`)
 - `data/processed/chirps_gefs/chirps_gefs_sangrur_2026-09-04.csv:280` (6 rows `gefs_d1`) + `data/processed/forecast_features_base.csv:1143` (18 rows `3` monsoon demo `2010-07-15/2019-09-04/2025-08-01 ×6`, `gefs_d1` 0.0/2.6/14.6, `gefs_d2..d7` `NaN` explicit)

**Current state:** Fully validated after switch from synthetic `sangrur_blocks.shp:916` (`48` uniform) to Bhuvan `MultiPolygon` (`12-28` varying). Final validation `PASS`. No further work needed unless a genuine bug is found.

**Known limitations:** `c3g_2026.09.04.tif` is `1` band daily total for `2026-09-04`, not `D+1..D+7`; Notebook 01 correctly documents this and does not invent `D+1..D+7`.

---

## Notebook 02: `02_build_forecasting_dataset.ipynb` (250113 bytes, 42 cells: title +41, last executed 2026-09-09)

**Purpose:** Build the historical forecast-vs-observation dataset (`forecast_date+block` → `CHIRPS-GEFS` forecast + `CHIRPS` target `D+1..D+7`), with `Cells 31–35` pivoting to `forecast_features_base.csv`.

**Cells 1–15 (planned 1–15, completed as demo `3` dates due to 90 KB/s):**
- **1 Imports** (`pathlib, datetime, pandas, numpy, geopandas, rasterio, requests, tqdm` + `src/data/chirps_gefs.py`)
- **2 Paths** (robust `PROJECT`, `RAINFALL_DIR, FORECAST_DIR, CLIMATE_DIR, SOIL_DIR, BOUNDARY_DIR, PROCESSED_DIR, HISTORICAL_DIR, CHIRPS_GEFS_PROCESSED`)
- **3 Boundaries** (`sangrur_blocks_bhuvan.gpkg:184320` `6` `b_name` `Dhuri…Sunam` verified)
- **4 Period** (`HIST_START 2001-01-01, HIST_END 2025-12-31, EXCLUDE 2020`, `CHIRPS 2010-01-01 to 2025-12-31 5844 dates, 35064 rows, latest_complete_D 2025-12-24`)
- **5 Candidates** (`9131` all → `8765` after `2020` → `8758` after `D+7 ≤ 2025-12-31`, `8758` candidate `2001-01-01` to `2025-12-24`, `2025:358`)
- **6 Representative** (5 dates `2001-06-15, 2010-07-15, 2019-09-04, 2021-08-20, 2025-08-01` → filtered to valid)
- **7 URL builder** (`build_chirps_gefs_url(date)` → `.../v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif`, tested `2026-09-04`)
- **8 Availability** (`HEAD` 5 reps, `AVAILABILITY_DF` `5×`)
- **9 Coverage** (5/5 `OK`, `2020` excluded, naming `YYYY/MM/DD` vs `YYYY.MM.DD`, sizes `50-70 MB`)
- **10 Single** (download `2019-09-04` `67 MB` `c3g_2019.09.04.tif:67698552`, `extract_block_rainfall` → `6` rows `Dhuri…`, `PASS` 5 checks)
- **11 Lead structure** (`1` band → `single_daily` daily total for `D`, not `D+1..D+7` stack, need 7 daily files for `gefs_d1..d7`)
- **12 Sample def** (`INPUTS ≤D: rain lags, GEFS at D (7 files), ENSO at D, soil, lat/lon, sin/cos` → `TARGET D+1..D+7`, `FORECAST_HORIZON=7, MIN_TARGET_DAYS=7`, anti-leakage)
- **13 CHIRPS inspect** (`Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` programmatically, `5844` dates `2010-2025`, `6` blocks, no gaps)
- **14 Valid samples** (optimized `0.11s` via `set + dict`, `CANDIDATE_DATES 8758` → `VALIDATION_DF` `8758` rows `forecast_date,target_start,target_end,valid,reason` → `valid 5472, invalid 3286` (mostly `2001-2009` where `D+7 <2010-01-01`), `First valid 2009-12-31`, `Last 2025-12-24`)
- **15 Estimate** (`5472` valid `×6 =32,832` samples, `5472` files `~346 GB` single, `38,304` files `~2.4 TB` for 7×, demo `10` → `0.6 GB` / `3` → `0.18 GB`)

**Cells 16–30 (planned 16–30, completed as demo `3` dates due to 90 KB/s):**
- **16 Config** (`HIST_CONFIG` with `REQUEST_TIMEOUT=(10,60), RETRY_COUNT=3, CHUNK_SIZE=1MB, SKIP_EXISTING=True, KEEP_RAW=True, VERIFY_RASTERIO=True`, `HISTORICAL_DIR/extracted`, `LOG` path, `LEAD_STRUCTURE=single_daily`, resumable)
- **17 URL builder** (`build_gefs_url`, `build_gefs_local_path` flat `c3g_YYYY.MM.DD.tif`, tested `2001,2019,2025`)
- **18 Preflight** (`HEAD` `5472` with `ThreadPool(20)` `~7 mins` first, then `CACHE preflight_availability.csv:760080` `5472` rows → `<1s` after, `HEAD` sequential was `1.59s/it` → `2:25:08`)
- **19 Diagnostics** (`requested 5472, available, unavailable, local, est size 346 GB, by year, missing, earliest/latest, 2020 excluded PASS`, `VALID_GEFS_FORECAST_DATES` `5472`)
- **20 Download function** (`download_file(url,dest,timeout,retries,chunk_size,skip_existing,verify_rasterio)` → `tmp → rename`, `retry 2×`, `rasterio` verify, tested on `2019`)
- **21 Process function** (`process_historical_forecast_date(D)` → `1` file per `D` + `extract_block_rainfall` → `6` rows `forecast_date,lead_day,block,forecast_target_date,forecast_rainfall_mm,valid_pixel_count,source_file` for `single_daily`)
- **22 Test** (originally 5 dates `2001-2025`, now optimized to **3 dates `2010-07-15,2019-09-04,2025-08-01`** for speed: `2019` cached `0.02s` `1/3`, `2010`+`2025` `~11m` each at `90 KB/s` → `~22 mins` first, `<5s` after; previous `5` took `9m41s` at `0/3` on `2009-12-31` early reforecast hang)
- **23 Schema** (markdown + code documenting `forecast_date,block,lead_day,forecast_target_date,forecast_rainfall_mm` unique key)
 - **24 Init resumable** (`OUTPUT_CSV=historical_gefs_block_forecasts.csv:1555`, `LOG_CSV:205`, recovers `PROCESSED_DATES`, `3` monsoon demo `18` rows)
- - **25 Bulk** (`DEMO_LIMIT=3` now, was `10` → `1.5 hours` at `90 KB/s`; `3` → `22 mins` first, `<5s` after; loops `VALID_GEFS_FORECAST_DATES` with `tqdm`, `expected 6` per `D`, `tif.tmp→rename`, `log.csv` `success/failed/skipped`; current `3` monsoon `2010-07-15/2019-09-04/2025-08-01` → `18` rows, gefs_d1 0.0/2.6/14.6 vs target 62-76/9-11/7-15)
- - **26 Summary** (`requested 5472, success 3, failed 0, skipped, blocks 6, leads 1, forecast records 18, date range 2010-2025, per block/lead, failures by year, missing, duplicates 0`)
- - **27 CHIRPS normalize** (`df_raw` → `CHIRPS_NORM` `35064`, `date→datetime, block→strip, rainfall→numeric, Lehragaga→Lehra`, `6` blocks, `2010-2025`, no gaps)
- - **28 7-day target** (`target_7d = sum D+1..D+7` per `D,block`, requires 7 valid days, `TARGET_DF` `5472×6=32,832` rows, `VALID_TARGET_DF` `5472×6` valid, monsoon demo 7-76mm)
- - **29 Join** (`MERGED_DF` left join `forecast 18` + `target 32k` on `forecast_date+block` → `18` rows `forecast_date,block,lead_day,forecast_target_date,forecast_rainfall_mm,target_7d_rainfall_mm,...` monsoon, reports `unmatched` 0)
- - **30 Final validation + save** (`historical_forecast_targets.csv:4750` + `.parquet:13008` `18` rows demo `3` monsoon `2010-07-15/2019-09-04/2025-08-01`, `30` code checks, `CHIRPS-GEFS + CHIRPS target dataset created successfully` if critical pass)

**Cells 31–35 (completed per spec `gefs_d1..d7` explicit NaN, both CSV+Parquet, monsoon demo):**
- - **31 Load/inspect** (`historical_forecast_targets.csv:4750`, normalize types, `shape 18×18`, `6` blocks, `lead 1`, `isna 0`, monsoon 7-76mm)
- - **32 Analyze lead** (`n_blocks 6, n_leads 1, min/max 1`, `lead 1` only vs `1..7`, `forecast_target_date == D` for `single_daily` vs `D+lead`)
- - **33 Pivot** (`pivot → gefs_d1` real, `gefs_d2..d7` explicit `NaN` (not zero, to be filled in `03` by joining 7 daily files), `gefs_d1..d7 + target_7d` `18×10`, gefs_d1 0.0/2.6/14.6)
- - **34 Validate wide** (`1. forecast_date+block unique 0 duplicates PASS, 2. 6 blocks per D PASS, 3. gefs_d1 0 missing PASS, gefs_d2..d7 18 missing EXPECTED (not failure), 4. no negative, 5. temporal gefs_d1 is D, 6. target is D+1..D+7, 7. no leakage PASS`)
- - **35 Save** (`forecast_features_base.csv:1143` + `.parquet:6064` `18×10` `forecast_date,block,gefs_d1,gefs_d2..d7,target_7d` with `gefs_d1` 0.0/2.6/14.6 vs `target_7d` 62-76/9-11/7-15, `gefs_d2..d7` all `NaN` explicit, `0` duplicates, `0` negative → `Forecast feature base created successfully`)

**Current demo state (as of 2026-09-08, after monsoon regeneration):**
- `data/raw/forecast/` has `8` tifs: `c3g_2009.12.31.tif:67284246`, `c3g_2010.01.01.tif:67966292`, `c3g_2010.01.02.tif:68501224`, `c3g_2010.01.03.tif:67721490`, `c3g_2010.07.15.tif:71828194`, `c3g_2019.09.04.tif:67698552`, `c3g_2025.08.01.tif:66387426`, `c3g_2026.09.04.tif:64908286` (8 total, all `>50 MB` valid)
- `data/processed/historical/` `historical_gefs_block_forecasts.csv:1555` (`3` monsoon `2010-07-15/2019-09-04/2025-08-01` → `18` rows) + `preflight_availability.csv:760080` (`5472` HEAD cache) + `historical_forecast_targets.csv:4750` (`3` monsoon `18` rows) + `forecast_features_base.csv:1143` (`18` rows monsoon) + `.parquet:6064`
- `data/processed/` `forecast_features_base.csv:1143` + `.parquet:6064` (18 rows, `gefs_d1` 0.0/2.6/14.6, `gefs_d2..d7` `NaN`, `target_7d` 7-76mm, validated)

**Known unresolved / next:** `Cells 31–35` verified monsoon demo `18` rows; full `5472` bulk remains as one-time background job (not interactive). `Notebook 03` must acquire/join remaining `gefs_d2..d7` daily files to fill `NaN`s, then add `rain_7d` etc., `ENSO`, `soil`, `lat/lon`, `sin/cos`.

---

## Notebooks 03–06 (planned, not yet created)

### Notebook 03: Feature Engineering (Cells 1–10 complete, 2026-09-09)
- **Purpose:** From `forecast_features_rainfall.parquet` + `ENSO` + (later) `SoilGrids` + centroids + calendar, build `historical_ml_dataset` (one row = `forecast_date+block` with all inputs + `target_7d`).
- **Cells 1–5:** imports, load (36×22), audit (all PASS), feature groups, clean frame (`ML_FEATURES 36×22`, `X_BASE 36×19`).
- **Cells 6–10 (ENSO):** `6` discovered `data/raw/climate/` empty → downloaded documented NOAA CPC ERSSTv5 `ersst5.nino.mth.91-20.ascii:67087` (81-10 file frozen at 2020-12, insufficient; same product family, documented in-cell); monthly 1950-01→2026-06, 918 rows, primary index `NINO34_ANOM` only. `7` cleaned (`enso_date,enso_value`, 0 NaN/dupes, sorted). `8` as-of rule = previous calendar month (`get_enso_asof_date`), markdown rationale in-cell. `9` validated (0 violations over 36 rows, full coverage). `10` merged (`ML_FEATURES 36×23`, `X_BASE_03 36×20`, `ENSO_FEATURES=["enso_value"]`, 0 missing, target unchanged, NOT saved to disk).
- **Status:** COMPLETE 2026-09-09 (31 cells, 2× full runs ALL PASS). Cells 11–30: inventory, calendar sin/cos, soil discovery/compat/extract (masked polygons, closure 968–979, pH 7.66–7.87)/QC/join, spatial centroids/join, GEFS diagnostics (d2–d7 NaN expected, no aggregates), ENSO audit, FINAL_FEATURES 29, X/y, validation ALL PASS, eligibility 36/36, final_ml_dataset 36×32 + metadata saved + reload 10/10 PASS.
- **03b validation gate (2026-09-09):** `03b_pipeline_validation.ipynb` (15 cells, ALL PASS) independently confirmed target = D+1..D+7 vs raw CHIRPS (4/4 MATCH), rain features match <=D recompute incl. NaN rule, ENSO global constancy, soil static, boundaries match. Gate 9 PASS / 2 WARN (expected GEFS/rain NaNs) / 0 FAIL → `pipeline_validation_report.txt` says READY FOR NOTEBOOK 4. Next: Notebook 04.

### Notebook 04: Model Training + Comparison (COMPLETE 2026-09-10)
- **Purpose:** Chronological train/val/test, compare baselines vs `RF/XGB/LGBM`.
- **Data:** `final_ml_dataset` 6588 rows / 1098 JJAS dates (train 2928 JJAS 2016–19 / val 1464 JJAS 2021–22 / test 2196 JJAS 2023–25), 31 features, seed 42. No imputation needed (0 missing).
- **Result (HONEST): Raw GEFS wins.** Test MAE: Raw GEFS 19.539 / RF 20.176 / XGB 21.082 / LGBM 21.344 / Climatology 24.640 / Persistence 30.536. Val MAE selected the winner (Raw GEFS 19.031); ML does not add skill here — reported as-is.
- **Categories:** train p33/p66 thresholds LOW<10.94 / HIGH>34.66 mm; residual-ECDF probabilities (train-only); test accuracy 0.685, macro F1 0.644, log loss 2.078, brier 1.087.
- **Artifacts:** `models/best_model.joblib` (baseline rule dict, reload-verified), `data/processed/model_results/` (comparison, test_predictions, category_metrics, feature_importance incl. XGB supplement, thresholds, metadata).
- **Status:** COMPLETE 2026-09-10 (41 cells, executed top-to-bottom, 0 errors).

### Notebook 05: Backtesting / Evaluation / Probabilistic Calibration (planned)
- **Purpose:** Backtest, metrics, calibrate `Low/Normal/High` (thresholds from `target_7d` percentiles `p33/p66`, not invented), `Brier/log loss`.
- **Status:** Not started.

### Notebook 05: Live Inference (COMPLETE 2026-09-10)
- **Purpose:** Turn current data into a 7-day block outlook for the 6 Sangrur blocks (live, not backtest).
- **Method:** Loads `models/inference_config.json` + `best_model.joblib` (model_type raw_gefs) + `probability_calibration.npz`; issue date D = latest fully-available GEFS bundle (walk-back on gaps); streams 7 target files (folder D, files D+1..D+7) via /vsicurl/; prediction = sum of leads; probabilities via NB04 residual-ECDF with NB04 thresholds (never refit); CHIRPS/ENSO/soil as explicitly-flagged context only.
- **First live run:** D=2026-09-09 (valid 09-10..09-16); all 6 blocks NORMAL, totals 17.8–22.8 mm; CHIRPS/ENSO context unavailable (local file currency) — flagged, not fabricated.
- **Outputs:** `data/processed/live_forecast/` (latest_block_forecast.csv/.json, latest_forecast_metadata.json).
- **Status:** COMPLETE 2026-09-10 (22 cells, executed top-to-bottom, 0 errors, gate printed READY FOR NOTEBOOK 6).

### Notebook 06: Application Integration (COMPLETE 2026-09-10)
- **Purpose:** Package NB05 live outlook as a validated data contract for Spring Boot + React (no retraining, no new science).
- **Outputs:** `data/processed/application/` (latest_forecast.json, blocks.json with Bhuvan `bhuvan_b_<b_code>` IDs, sangrur_blocks.geojson EPSG:4326, metadata.json, README.md, latest_forecast.csv, api/{latest-forecast,blocks,health}.json) + `docs/api/` (forecast-schema.json, blocks-schema.json, api-contract.md).
- **Advisories:** generic cautious prototype-labeled messages (info/watch); no dosages/yield/disease claims.
- **Status:** COMPLETE 2026-09-10 (23 cells, executed top-to-bottom, 0 errors, 25/25 gate checks PASS, printed READY FOR SPRING BOOT + REACT INTEGRATION).

**Overall:** `01`–`06` COMPLETE. Python pipeline frozen. Next: Spring Boot REST API + React frontend consuming `data/processed/application/`.

**Dashboard:** `dashboard/` (planned, not yet built) — block-level map of 6 `MultiPolygon` blocks colored by expected rainfall or most likely category.

**Overall:** `01`–`06` COMPLETE (NB06 gate 25/25 PASS, READY FOR SPRING BOOT + REACT INTEGRATION). Python pipeline frozen. Next: Spring Boot REST API + React frontend.

## Notebook 08: `08_MJO_CNN_LSTM.ipynb` (COMPLETE 2026-09-11, 15 cells, 0 errors)

- RMM data 18,802 rows (1974–2026, 1 gap never bridged); L=84/H=7; splits train≤2005 (11,067) / val 2006–15 (3,652) / test≥2016 (3,903); train-only scaler; all leakage audits PASS.
- Compact CNN-LSTM (9,106 params, CPU 0.9 min): test RMM1 MAE 0.627 vs persist 0.884; RMM2 0.560 vs 0.849; phase acc 0.349 vs 0.207.
- Artifacts: `models/mjo_cnn_lstm.keras + mjo_scaler.pkl + mjo_metadata.json`, `data/processed/mjo_predictions.csv`; reload PASS; readiness READY.

## IOD Notebook: `IOD_nb.ipynb` (teammate, UNMODIFIED — method reference)

- Preprocessing + DMI method (W50–70E/10S–10N, E90–110E/10S–0, thresholds ±0.4°C) reused as-is; no `*.nc` needed since HadISST monthly DMI series supplied directly.

## Notebook 07: `07_IOD_LightGBM.ipynb` (COMPLETE 2026-09-11, 11 cells, 0 errors)

- Monthly HadISST DMI 1,877 rows; target DMI(M+1); ~25 causal features; splits train≤2000 (309) / val (144) / test≥2013 (160); leakage PASS.
- LightGBM (val 0.1639) ≈ persistence (0.1624); TEST 0.1936 vs persist 0.1573 — persistence wins, reported honestly; phase acc 0.744 vs 0.825.
- Artifacts: `models/iod_best_model.joblib + iod_metadata.json`, `data/processed/iod_predictions.csv`; reload PASS; live 2026-06 +0.033 Neutral (stale).
