# 02 — System Architecture

## Text Architecture Diagram

```
                 DATA SOURCES
                     │
       ┌─────────────┼─────────────┐
       │             │             │
    CHIRPS       CHIRPS-GEFS     ENSO
       │             │             │
       └─────────────┼─────────────┘
                     │
               SoilGrids
                     │
             Sangrur Boundaries
          (Bhuvan 6-block, MultiPolygon)
                     │
                     ▼
              FEATURE ENGINE
   ┌─────────────────────────────────┐
   │ rain_1d/3d/7d/14d/30d, lags     │
   │ gefs_d1..d7, sin/cos(doy),     │
   │ ENSO, soil, lat/lon            │
   └─────────────────────────────────┘
                     │
                     ▼
          FORECASTING MODELS
                     │
       ┌─────────────┼─────────────┐
       │             │             │
   Baselines    Random Forest   XGBoost
     (Clim,                  │
      Persist,              LightGBM
      Raw GEFS)
                     │
                     ▼
              MODEL SELECTION
            (chronological MAE/RMSE/R²)
                     │
                     ▼
          PROBABILISTIC OUTPUT
              Low/Normal/High
            (percentiles + predict_proba)
                     │
                     ▼
           6 SANGRUR BLOCKS (per D)
                     │
          ┌──────────┴──────────┐
          │                     │
     Rainfall mm          Low/Normal/High
          │                     │
          └──────────┬──────────┘
                     ▼
              MAP / DASHBOARD
               (6 polygons)
                     │
                     ▼
             AGRICULTURAL
                ADVISORY (secondary)
```

## Stage-by-Stage Explanation

### 1. Raw Data
- **CHIRPS v3** `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv` — per-block daily `rainfall_mm`, `2010-01-01` to `2025-12-31`, 6×5844.
- **CHIRPS-GEFS v3 daily** `data/raw/forecast/c3g_YYYY.MM.DD.tif` — global `7200×2400×1` `0.05°` `64.9 MB` `float32` `EPSG:4326`, archive `https://data.chc.ucsb.edu/.../v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif`.
- **ENSO** `data/raw/climate/` — monthly `Nino3.4/ONI` (to be added).
- **SoilGrids** `data/raw/soil/` — rasters aggregated to 6 blocks: `clay, sand, silt, SOC, pH`.
- **Boundaries** `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg` (`6` `MultiPolygon`, `OGC:CRS84`, `75.55-76.20`) — authoritative; `sangrur_blocks.shp` is synthetic fallback.

### 2. Acquisition
- **Notebook 01** `01_chirps_gefs_acquisition.ipynb` (30 cells) — tests single daily file (`c3g_2026.09.04.tif`) download, `HEAD` `200 OK`, atomic `stream → .tmp → rename`, `rasterio.open` validate, `>50 MB` skip.
- **Notebook 02 Cells 1–10** — build candidate dates `8758`, `HEAD` 5 reps, download `c3g_2019.09.04.tif:67 MB` single-file validation.

### 3. Spatial Aggregation
- **Not centroid sampling.** Uses `rasterio.mask.mask(crop=True)` + `src/data/chirps_gefs.py:zonal_stats` / `extract_block_rainfall` — polygon zonal `mean` per block, `valid_pixel_count`, `-9999` sentinel masked, `nan` preserved (not zero).
- **Boundaries** are `MultiPolygon` irregular; synthetic `Polygon ×6` was `48` uniform pixels vs real `12-28` varying.

### 4. Historical Forecast Dataset (Notebook 02 Cells 11–35)
- **Cells 11–15** — determine `single_daily` (`1` band = daily total, not `7`-stack), validate `8758→5472` valid `D` where `D+7 ≤ 2025-12-31`, estimate `5472×6=32,832` samples `346 GB` raw (or `2.4 TB` for 7×).
- **Cells 16–20** — `HIST_CONFIG` (timeout `(10,60)`, retries 3, `skip_existing`, `keep_raw`, `preflight_availability.csv:760080` cache via `ThreadPool(20)` `~7 mins` first, `<1s` after), `build_gefs_url/local_path`, `download_file` (tmp→rename, `rasterio` verify).
- **Cells 21–22** — `process_historical_forecast_date(D)` → `6` rows `forecast_date,lead_day,block,forecast_target_date,forecast_rainfall_mm,valid_pixel_count,source_file` (reuses `src/data/chirps_gefs.py`), tested on `3` dates `2010,2019,2025` (`1.5 mins` first, `<5s` after).
- **Cells 23–26** — Tidy schema `forecast_date,block,lead_day,forecast_target_date,forecast_rainfall_mm` → resumable `historical_gefs_block_forecasts.csv:1874` + `log.csv:205` (`DEMO_LIMIT=3` → `18` rows demo, full `5472` not yet bulk).
- **Cells 27–28** — `CHIRPS_NORM` `35064` rows → pivot `date×block` → `target_7d = sum D+1..D+7` per `D,block` (requires 7 valid days, `TARGET_DF` `32,832` rows, `VALID_TARGET_DF` `32,832`).
- **Cells 29–30** — Left join `forecast 18` + `target 32k` on `forecast_date+block` → `MERGED_DF` → `historical_forecast_targets.csv:4210` (`24` rows demo `4` dates `2009-12-31..2010-01-03×6`) + `.parquet:12197`.
- **Cells 31–35** — Load `historical_forecast_targets.csv`, pivot `lead_day` → `gefs_d1` real, `gefs_d2..d7` explicit `NaN` (not zero, to be filled in **Notebook 03** by joining 7 daily files), validate `forecast_date+block` unique `0` duplicates, save `forecast_features_base.csv:1294` + `.parquet:5982` (`24×10` `forecast_date,block,gefs_d1..d7,target_7d` with `gefs_d1` 6.4–12.3, `gefs_d2..d7` all `NaN`).

### 5. Observed Target Construction
- For `D`, `target_7d = sum CHIRPS D+1..D+7` per block, `target_start = D+1`, `target_end = D+7`, `days_expected=7, days_available, target_valid` — if even one of `D+1..D+7` missing, `target_valid=False` and **do not** replace with zero.

### 6. Feature Engineering (Notebook 03, planned)
- From `forecast_features_base.csv` + raw CHIRPS + ENSO + SoilGrids + centroids + calendar:
  - `rain_1d/3d/7d/14d/30d` + `rain_lag_1..7` (all ≤`D`)
  - `gefs_d1..d7` (after joining 7 daily files per `D` to fill `NaN`s)
  - `gefs_3d_total, gefs_7d_total`
  - `ENSO_index` at `D`
  - `sin_doy, cos_doy`
  - `soil_clay/sand/silt/soc/ph`, `lat/lon`
- Produces `historical_ml_dataset` (one row = `forecast_date+block` with all inputs + `target_7d`).

### 7. Temporal Split (Notebook 04)
- Chronological, not random: `2001–2019` train / `2021–2023` val / `2024–2025` test (adapted to `2010–2019` train because `CHIRPS` starts `2010`), `2020` excluded. Never shuffle.

### 8. Baseline Models (Notebook 04)
- `Climatology` (historical typical for that date), `Persistence` (recent), `Raw GEFS` (no post-processing).

### 9. RF / XGBoost / LightGBM (Notebook 04)
- Train tabular models, handle `gefs_d2..d7` `NaN` after `03` fill, compare on validation.

### 10. Evaluation (Notebook 05)
- `MAE/RMSE/R²/bias` for regression, `Accuracy/F1/log loss/Brier` for `Low/Normal/High` (thresholds from historical `target_7d` percentiles `p33/p66`, not invented).

### 11. Probabilistic Output
- Regression → `expected mm` + classifier or calibrated `Low/Normal/High` probabilities.

### 12. Live Inference (Notebook 06)
- `D = today` → `latest CHIRPS through D`, `latest GEFS issued today`, `current ENSO`, `static soil/spatial` → `next 7–10 days` per block.

### 13. Block Dashboard (dashboard/)
- Map of 6 `MultiPolygon` blocks colored by `expected mm` or most likely category, with `forecast_date` and probabilities.

## Data Flow & Component Mapping

| Data | Notebook | Src Module | Output |
|------|----------|------------|--------|
| CHIRPS-GEFS daily `c3g_*.tif` | `01` `c3g_2026.09.04.tif` `65 MB` | `src/data/chirps_gefs.py:zonal_stats` | `data/processed/chirps_gefs/*` |
| Bhuvan GPKG | `01` `sangrur_blocks_bhuvan.gpkg` + `02` `Cell 3` | `src/data/download_sangrur_boundaries.py` | `sangrur_blocks_bhuvan.gpkg` |
| Historical `8758→5472` dates | `02` `Cells 4–5` | `02` `CELL 14` `0.11s` | `CANDIDATE_DATES`, `VALIDATION_DF` |
| `5472` `HEAD` cache | `02` `Cell 18` `20-thread` | `data/processed/historical/preflight_availability.csv` | `VALID_GEFS_FORECAST_DATES` `5472` |
| `18` rows demo | `02` `Cells 21–26` | `historical_gefs_block_forecasts.csv` | `18` rows `6×1` |
| `32,832` targets | `02` `Cells 27–28` | `TARGET_DF` | `target_7d` `D+1..D+7` |
| `24` rows demo join | `02` `Cells 29–30` | `historical_forecast_targets.csv` | `24` rows `4×6` |
| `24` wide | `02` `Cells 31–35` | `forecast_features_base.csv` | `gefs_d1` real, `gefs_d2..d7` `NaN` |

**Key principle:** `01` proves single-date extraction, `02` proves historical `D → D+1..D+7` join for a few dates (demo `3`), `03` will scale to full `5472` + add all features. `dashboard/` is last.

