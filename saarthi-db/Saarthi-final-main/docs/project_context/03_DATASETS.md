# 03 — Datasets

## Dataset Catalog

### 1. CHIRPS v3 Historical Rainfall

**Name:** CHIRPS v3 (Climate Hazards Center InfraRed Precipitation with Station data, Version 3)
**Source:** University of California, Santa Barbara, Climate Hazards Center — `https://data.chc.ucsb.edu/products/CHIRPS/v3.0/` (historical) and as aggregated per-block CSV locally.
**Local path:** `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` (currently the only CHIRPS file in the repo; `data/raw/rainfall/` contains 1 file)
**Spatial resolution:** `0.05°` (~5 km) native, aggregated to 6 Sangrur blocks via Bhuvan polygons (not centroid)
**Temporal resolution:** Daily
**Coverage (actual file):** `2010-01-01` to `2025-12-31` — `5844` unique dates × `6` blocks = `35064` rows. Continuous **no missing dates**, `0` missing `rainfall_mm`, `0` negative. Earlier `PROJECT_CONTEXT` approximated `2015-06-01`, but actual file is `2010-01-01`.
**Purpose:** **Ground-truth observed rainfall.** Builds the ML target `target_7d_rainfall_mm = sum CHIRPS D+1..D+7` per `forecast_date+block`. Also builds input features `rain_1d/3d/7d/14d/30d` + `rain_lag_1..7` (all ending at `D`).
**Input vs Target:** **Target only** as `D+1..D+7` (label). As input, only `≤D` windows are allowed.
**Preprocessing:** Already aggregated to block-level `rainfall_mm` in CSV (do not re-aggregate unless earlier raw `CHIRPS` rasters are acquired). In `02` `Cell 13` `c13_chirps_inspect:1`, normalized `date→datetime, block→strip, rainfall_mm→numeric`, normalized `Lehragaga→Lehra` if needed, checked `6` blocks.
**Caveats:** `2010-01-01` start means `2001-2009` forecast dates have no `target` (Cell 14 marks them invalid). The intended `2001–2019` training split must be **restricted to `2010–2019`** unless earlier CHIRPS is acquired. Do not pretend `2001-2009` targets exist.
**Current status:** Available, validated, used in `02` `Cells 4,5,13,14,27,28` for `VALIDATION_DF` `5472` valid.

### 2. CHIRPS-GEFS Forecast Rainfall (v3 daily)

**Name:** CHIRPS-GEFS v3 daily (CHIRPS-calibrated GEFS forecast, `c3g_YYYY.MM.DD.tif`)
**Source:** CHC archive `https://data.chc.ucsb.edu/products/CHIRPS-GEFS/v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` (also `05_day/10_day/15_day/pentad` separate)
**Local path:** `data/raw/forecast/c3g_*.tif` — e.g., `c3g_2026.09.04.tif:64908286`, `c3g_2019.09.04.tif:67698552`, `c3g_2010.07.15.tif:71828194`, plus `c3g_2009.12.31.tif:67284246` etc. (currently 7 files `67-71 MB` each). Each is `7200×2400×1` `float32` `LZW` `EPSG:4326` `Bounds -180,-60,180,60` `res 0.05°` `transform |0.05,0, -180 : 0,-0.05,60|`, `Area` tag, `nodata None` but sentinel `-9999` for ocean.
**Spatial resolution:** `0.05°`
**Temporal resolution:** Daily (one file per calendar date)
**Coverage:** Archive `2001–2019` + `2021–2025` (2020 gap), `2001-01-01` to `2025-12-31` valid `8758` candidate, `5472` with complete `D+7` target.
**Purpose:** **Most important predictor.** Historical system simulates: at issue date `D`, use GEFS forecast issued on `D` (via `build_gefs_url(D)`), then compare to CHIRPS actual `D+1..D+7`.
**Input vs Target:** **Input only.**
**Preprocessing:** In `01` `Cells 6-10` + `02` `Cells 11,18–22`, `rasterio.open` inspect, `zonal_stats` `mean` per block via `rio_mask` (not centroid), `-9999→nan`, `nan` preserved.
**Caveats:** **Single daily product:** `count 1, descriptions (None,), no lead metadata` — `Band 1` = **daily total for that date**, not `D+1..D+7` stack. Therefore `gefs_d1` is real, `gefs_d2..d7` are `NaN` placeholders until `Notebook 03` joins 7 daily files per `D` (`D+1` file → `gefs_d1`, etc.). Archive folder structure `daily/global/YYYY/MM/DD/` confirms one file per date. Do NOT assume `Band 1 = D+1` or invent 7 leads. File naming date is the **forecast target/issue date** (for daily product), not an ensemble. `60-67 MB` per file, `346 GB` for `5472` single, `2.4 TB` for 7×.
**Current status:** `01` validated `c3g_2026.09.04.tif` `65 MB` single band, `02` validated `c3g_2019.09.04.tif` `67 MB` 6-block extraction `6.4–12.3 mm`, `preflight_availability.csv:760080` `5472` `HEAD` cache (20-thread, `~7 mins` first, `<1s` after).

### 3. ENSO

**Name:** ENSO (El Niño–Southern Oscillation) — e.g., Nino3.4 / ONI
**Source:** NOAA CPC `https://www.cpc.ncep.noaa.gov/data/indices/ersst5.nino.mth.81-10.ascii` or ERSSTv5 (to be downloaded to `data/raw/climate/`)
**Local path:** `data/raw/climate/` (currently empty placeholder)
**Spatial resolution:** Global climate index (not spatial)
**Temporal resolution:** Monthly
**Coverage:** `2001–2025` (and ongoing)
**Purpose:** Climate-scale predictor: `ENSO_index` (numeric, e.g., Nino3.4 anomaly for month of `D`) + optional `ENSO_category` (El Niño/La Niña/Neutral derived from index). Represents large-scale forcing on monsoon.
**Input vs Target:** Input.
**Preprocessing:** In `Notebook 03`, join monthly `ENSO_index` to each `forecast_date` by `year-month` of `D`, **using value available at `D`** (not future months). For `D=2019-09-04`, use `2019-08` or `2019-09` monthly value depending on availability at `D` (document).
**Caveats:** Use one primary index first, not multiple. Do not use future `ENSO` values. Monthly → daily via forward fill is acceptable.
**Current status:** Not yet acquired — planned for `Notebook 03`.

### 4. SoilGrids

**Name:** SoilGrids v2 (ISRIC) — clay, sand, silt, SOC, pH
**Source:** `https://files.isric.org/soilgrids/` (or SoilGrids API) → rasters to be clipped to `sangrur_blocks_bhuvan.gpkg`
**Local path:** `data/raw/soil/` (currently empty)
**Spatial resolution:** `250m` native, aggregated to block-level `mean`/`median` per Sangrur block
**Temporal resolution:** Static (one value per block, not time-varying)
**Coverage:** Sangrur district, 6 blocks.
**Purpose:** Static spatial/agricultural context, not direct atmospheric predictor. Helps distinguish block-level rainfall behavior via local soil.
**Input vs Target:** Input (static).
**Preprocessing:** In `Notebook 03`, `rasterio` clip `SoilGrids` GeoTIFFs (e.g., `clay_0-5cm`, `sand`, `silt`, `soc`, `phh2o`) to each `sangrur_blocks_bhuvan.gpkg` polygon → per-block `mean` (or median) → columns `soil_clay, soil_sand, soil_silt, soil_soc, soil_ph`.
**Caveats:** Static — same for all dates, not a time series. Do not treat as atmospheric predictor.
**Current status:** Not yet acquired/aggregated — `data/raw/soil/` empty.

### 5. Bhuvan 6-Block Boundaries

**Name:** Sangrur 6-block Bhuvan/ISRO legacy administrative boundaries
**Source:** Derived from `https://bharatlas.com/api/dl/admin/blocks/bhuvan_blocks.parquet:97298502` (6393 national, 74 Punjab) filtered `s_name=="Punjab" AND d_name=="Sangrur"` via `src/data/download_sangrur_boundaries.py:13066`
**Local path:** `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg:184320` layer `sangrur_blocks` — **authoritative, 6 irregular MultiPolygon `OGC:CRS84`**, `b_name` `Dhuri/Lehra/Malerkotla/Moonak/Sangrur/Sunam` verified.
**Spatial resolution:** Vector polygons, `total_bounds [75.5564575, 29.7271805, 76.2043993, 30.6891803]`
**Temporal resolution:** Static
**Coverage:** 6 legacy Sangrur blocks (not current 8).
**Purpose:** Defines prediction units, zonal aggregation (`zonal_stats` `mean`), maps, `lat/lon` centroids, soil aggregation.
**Input vs Target:** Input (spatial).
**Preprocessing:** In `01` `Cells 11–14` and `02` `Cell 3`, `gpd.read_file(..., layer="sangrur_blocks")` → `b_name` column, `to_crs("EPSG:4326")` if needed (from `OGC:CRS84`), `union_all()` for district crop.
**Caveats:** Legacy 6, not 8. Synthetic `sangrur_blocks.shp:916` (`Polygon ×6` rectangular `48` px uniform) is **test artifact only, must NOT be used**.
**Current status:** Verified 6, irregular, valid, used in `01` `6.4–12.3 mm` and `02` `6.4–12.3 mm` with `MultiPolygon` `12–28` pixels/block varying.

### 6. Synthetic Fallback Boundaries (Test Artifact)

**Name:** Synthetic `sangrur_blocks.shp` + `.geojson:1410`
**Local path:** `data/raw/boundaries/sangrur_blocks.shp:916` (+ `.shx/.dbf/.prj/.cpg`)
**Purpose:** Test artifact for `01` Cells 11–15 before Bhuvan GPKG existed.
**Status:** Kept, **not used** after switch to `sangrur_blocks_bhuvan.gpkg`.

## Explicitly Excluded Datasets

**Do NOT introduce unless core `01–06` is complete and substantial extra time remains:**
- `IOD` (Indian Ocean Dipole)
- `MJO` (Madden-Julian Oscillation)
- `ERA5-Land` (reanalysis)
- `SMAP` (soil moisture)
- `DEM` / terrain / `Copernicus/SRTM`
- `ECMWF S2S` / `ENS`
- Deep-learning-specific atmospheric fields
- Any other reanalysis beyond `CHIRPS, CHIRPS-GEFS, ENSO, SoilGrids`

**Reason:** Six-day prototype must finish reliably, not maximize dataset count. These are future extensions (see `09_NEXT_STEPS.md`).

## Where Every Dataset Lives (Summary)

```
data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691
data/raw/forecast/c3g_YYYY.MM.DD.tif (7200×2400, 64.9 MB, 7 files currently)
data/raw/boundaries/sangrur_blocks_bhuvan.gpkg:184320 (6 MultiPolygon) + bhuvan_blocks.parquet:97298502 (source)
data/raw/boundaries/sangrur_blocks.shp:916 (synthetic, do NOT use)
data/raw/climate/ (empty, ENSO to be added)
data/raw/soil/ (empty, SoilGrids to be added)
data/processed/chirps_gefs/*, historical/*, historical_forecast_targets.*, forecast_features_base.*
```

