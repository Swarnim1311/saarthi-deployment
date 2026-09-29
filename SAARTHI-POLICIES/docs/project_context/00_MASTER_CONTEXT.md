# 00 — MASTER CONTEXT — Hyperlocal Monsoon Onset & Break Prediction System

> **PRIMARY ENTRY POINT — Read this first.** A future OpenCode session must read this file before any other. It contains the complete, up-to-date state of the project as of the end of the current session (2026-09-09). No old-project assumptions are valid.

---

## 1. Project Identity

**Repository:** `hyperlocal-monsoon/` (local path `saarthi-2` on Windows desktop `C:\Users\Swarnim\Desktop\ML projects\saarthi-2`)
**Project name:** **Hyperlocal Monsoon Onset & Break Prediction System**
**Nature:** Brand-new SIH (Smart India Hackathon) prototype. **Do NOT resurrect any old project, models, datasets, or roadmaps.** The old project is not the source of truth.
**Purpose:** Build a hyperlocal monsoon behavior prediction system at block/village scale that provides probabilistic outlooks for 7–30 days, covering monsoon onset, breaks/dry spells, revival, heavy rainfall and localized precipitation, driven by climate drivers and regional atmospheric information. The production vision is large; the current work is a **focused six-day prototype**.

---

## 2. SIH Problem Statement (Original vs Prototype)

**Original SIH concept:** Hyperlocal monsoon prediction at block/village scale with probabilistic outlooks for 7–30 days, including onset, breaks, revival, heavy and localized events, with climate drivers.

**Current six-day prototype scope (intentionally reduced):**
- Prove we can translate a numerical forecast (CHIRPS-GEFS) plus local observations into a block-scale probabilistic outlook for the next 7 days.
- Deliver **one row = one forecast date + one Sangrur block** → `expected rainfall (mm)` + `Low/Normal/High probabilities`.
- Demonstrate the pipeline end-to-end: acquisition → spatial aggregation → historical backtest → baseline/ML comparison → live 7-day map. Agricultural advisories are secondary to getting the forecasting engine correct.

---

## 3. Six-Day Constraint

The entire prototype must be completed within **six days including today**. Priority order:
1. Correct data pipeline (no leakage)
2. Historical backtesting
3. Baselines (Climatology, Persistence, Raw GEFS)
4. RF / XGBoost / LightGBM comparison
5. Probabilistic categories (Low/Normal/High)
6. Live 7-day forecast
7. Block-level dashboard/map

Do not expand scope with extra datasets, deep learning, or dashboard polish until the core pipeline is complete.

---

## 4. Geographic Scope & Spatial Units

**District:** Sangrur, Punjab, India
**Spatial unit:** **Exactly 6 legacy Bhuvan/ISRO blocks** — the authoritative unit for the prototype.

| # | Block (legacy name, as in Bhuvan `b_name`) |
|---|---------------------------------------------|
| 1 | Dhuri |
| 2 | Lehra (variant: Lehragaga) |
| 3 | Malerkotla |
| 4 | Moonak |
| 5 | Sangrur |
| 6 | Sunam |

**CRITICAL:** Do NOT switch to the current 8-block administrative structure. Do NOT silently replace the 6-block geography. The project intentionally uses the legacy 6-block representation because that is the unit already established, validated in `Cell 12/17`, and used in all downstream joins. The new 8-block dataset must be ignored unless explicitly instructed.

**No village-level prediction** is promised in the prototype; block level is the correct granularity.

---

## 5. Authoritative Boundaries

**Authoritative file:** `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg`
**Layer:** `sangrur_blocks`
**Source:** Derived from the Bhuvan/Bharat Atlas block dataset `https://bharatlas.com/api/dl/admin/blocks/bhuvan_blocks.parquet` (`data/raw/boundaries/bhuvan_blocks.parquet:97298502`, 6393 national blocks) filtered `s_name == "Punjab" AND d_name == "Sangrur"` → exactly 6 `MultiPolygon` features.

**Properties:**
- 6 features, `MultiPolygon ×6`, irregular administrative geometries (dozens of vertices, not 5-vertex rectangles)
- Columns: `s_name, s_code, d_name, d_code, b_name, b_code, geometry`
- CRS: `OGC:CRS84` (≈ `EPSG:4326`) — `total_bounds [75.5564575, 29.7271805, 76.2043993, 30.6891803]`
- Block column: `b_name` (values exactly the 6 above). A variant `Lehragaga` may appear in some sources and should be normalized to `Lehra`.

**Synthetic fallback (must NOT be used for real pipeline):**
`data/raw/boundaries/sangrur_blocks.shp` (+ `.shx/.dbf/.prj/.cpg`, 916 bytes) + `sangrur_blocks.geojson:1410` — a 2×3 rectangular grid `75.55-76.45E, 29.85-30.65N`, `Polygon ×6`, `48` pixels/block uniform, created only so Notebook 01 Cells 11–15 could be tested before the Bhuvan GPKG existed. It remains in the repo as a test artifact. The authoritative GeoPackage is `sangrur_blocks_bhuvan.gpkg:184320`.

**Bhuvan source parquet:** `data/raw/boundaries/bhuvan_blocks.parquet:97298502` (6393 blocks national, 74 Punjab, 6 Sangrur) — kept for provenance, not used directly after GPKG extraction.

---

## 6. Current Datasets

### Core Datasets (5)

1.  **CHIRPS v3 historical rainfall** — observed / ground truth
2.  **CHIRPS-GEFS forecast rainfall** — predictor (most important)
3.  **ENSO** — climate-scale predictor (one primary numeric index first)
4.  **SoilGrids** — static soil context per block (clay, sand, silt, SOC, pH)
5.  **Sangrur 6-block Bhuvan boundaries** — spatial aggregation

### Explicitly Out of Scope for Prototype

Do NOT introduce unless core is complete and substantial extra time remains: `IOD, MJO, ERA5-Land, SMAP, DEM/terrain, ECMWF S2S, deep learning, or other atmospheric reanalysis`. The goal is finishing reliably, not maximizing dataset count.

---

## 7. Dataset Roles & Where They Live

| Dataset | Local Path | Resolution / Coverage | Role | Input vs Target | Status |
|---------|------------|------------------------|------|-----------------|--------|
| **CHIRPS v3** | `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` (35064 rows, 5 cols: `system:index,block,date,rainfall_mm,.geo`) | 0.05°, daily, per-block already aggregated to 6 Sangrur blocks | Ground-truth observed rainfall. Builds the target `target_7d_rainfall_mm = sum CHIRPS D+1..D+7`. | **Target only** | Available `2010-01-01` to `2025-12-31` (5844 unique dates, 6×5844=35064). Continuous, no missing dates, `0` missing `rainfall_mm`. |
| **CHIRPS-GEFS v3 daily** | `data/raw/forecast/c3g_YYYY.MM.DD.tif` (e.g., `c3g_2026.09.04.tif:64908286`, `c3g_2019.09.04.tif:67698552`, plus demo `c3g_2010.07.15.tif:71828194` etc.) `7200×2400×1` `float32` `LZW` `EPSG:4326` `Bounds -180,-60,180,60` `res 0.05°` `Bounds Sangrur 75.55-76.20`. Archive: `https://data.chc.ucsb.edu/products/CHIRPS-GEFS/v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` (plus `05_day/10_day/15_day` separate). | 0.05°, daily, global `60N-60S`, bias-corrected GEFS forecast | Most important predictor. Simulates what was known on issue date D. Historical archive 2001-2019 + 2021-2025 (2020 gap). Single daily product: **1 band = daily total for that date**, not a 7-day stack. | **Input only** | Verified `64.9 MB` example, `1` band `descriptions (None,)` `nodata None` but sentinel `-9999` for ocean (0.83%). |
| **ENSO** | `data/raw/climate/` (empty placeholder, to be populated) | Monthly, one index (e.g., Nino3.4/ONI) | Climate-scale feature: `ENSO_index` (+ optional `ENSO_category`), value available at D. | Input | Not yet acquired — Notebook 03 will add. |
| **SoilGrids** | `data/raw/soil/` (empty placeholder) | 250m, per-block mean/median aggregated to 6 blocks: `soil_clay, soil_sand, soil_silt, soil_soc, soil_ph` | Static spatial context, not direct atmospheric predictor. | Input (static) | Not yet aggregated — Notebook 03. |
| **Bhuvan 6-block** | `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg:184320` layer `sangrur_blocks` | `MultiPolygon ×6`, `OGC:CRS84`, `75.55-76.20` | Defines 6 prediction units, spatial joins, zonal stats, maps. | Input (spatial) | Verified 6, correct names, `MultiPolygon` irregular, `Bounds` above. |
| **Synthetic fallback** | `data/raw/boundaries/sangrur_blocks.shp:916` + `.geojson` | `Polygon ×6` rectangular `48` px uniform | Test artifact only — **must NOT be used** for real dataset. | Test only | Kept, not deleted. |

**Excluded:** IOD, MJO, ERA5-Land, SMAP, DEM, ECMWF S2S, deep learning (see §6).

---

## 8. Prediction Target

**Primary target (ML label):**

For forecast issue date `D` and block `B`:

```
target_7d_rainfall_mm
=
sum of observed CHIRPS rainfall
from D+1 through D+7 inclusive
for block B
```

- Rainfall on `D` itself **must NOT** be included.
- Rainfall after `D+7` **must NOT** be included.
- Target is **observed** (CHIRPS), never an input feature.
- Require all 7 days available for that block; if even one of `D+1..D+7` is missing, mark `target_valid=False` and **do NOT** replace with zero or interpolate — report.

**Secondary target (if time permits):** `target_10d = sum D+1..D+10` (same rules). Do not delay 7-day to chase 10-day.

**Optional daily targets:** `target_d1_mm .. target_d7_mm` (each day's observed CHIRPS `D+1` ... `D+7`) — useful for diagnostics, but primary is 7-day sum.

---

## 9. Probabilistic Output

For every `D + B`, produce:

- **Expected rainfall:** `X mm` (regression)
- **Probabilities:** `P(Low), P(Normal), P(High)` summing to 1

Example:
```
Block: Sangrur — 2019-09-04
Expected 7-day: 83.4 mm
Low: 15%  Normal: 55%  High: 30%
```

- Categories `Low/Normal/High` thresholds **must be derived from historical Sangrur climatology / percentiles** (e.g., `p33 / p66` of `target_7d` distribution), not invented meteorological thresholds.
- Category development comes **after** model comparison (Notebook 05). Not yet done.

---

## 10. Forecasting Philosophy (D)

**Core question:** *Given everything known on day D, how much rainfall will actually occur in this Sangrur block during the next 7 days?*

```
D = forecast issue date
Inputs: information available BY D (see §11)
Target: actual observed rainfall D+1..D+7
```

`D+1..D+7` is strictly **after** `D` — temporal separation is a **non-negotiable rule**.

---

## 11. Data Leakage Rules (NON-NEGOTIABLE)

**At forecast issue date D, for sample (D,B):**

**Allowed inputs (≤ D):**
- CHIRPS rainfall **through D** (e.g., `rain_7d = sum D-6..D`, `rain_lag_1 = D`, ... `rain_lag_7 = D-6`)
- CHIRPS-GEFS forecast **issued on D** (e.g., `gefs_d1..d7` from GEFS files for `D`/`D+1..D+7` depending on product — see §12)
- ENSO **available at D** (month of D, not future)
- Soil (static), block centroid `lat/lon`, `sin/cos(day_of_year)` (calendar)

**Forbidden as input (would be leakage):**
- `CHIRPS D+1`, `D+2`, ... `D+7` (these are the target)
- Any `actual_D1..actual_D7` columns as features
- Future ENSO values
- Future GEFS runs
- `target_7d` itself

**Actual `D+1..D+7` exists ONLY as `target_7d_rainfall_mm` label.** Random train/test splitting is forbidden; chronological splitting is required.

---

## 12. CHIRPS-GEFS Feature Strategy & Current Product Uncertainty

**Intended GEFS features (if archive provided 7 leads per D):**
```
gefs_d1, gefs_d2, gefs_d3, gefs_d4, gefs_d5, gefs_d6, gefs_d7
gefs_3d_total = sum(d1:d3)
gefs_7d_total = sum(d1:d7)
gefs_10d_total = sum(d1:d10) (secondary)
```

**IMPORTANT CURRENT PRODUCT ISSUE:**
The tested `v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` product **contains only a single daily field** (`count=1`, `descriptions (None,)`, `global tags` only `TIFFTAG_DOCUMENTNAME:/home/CHIRPS-GEFS/v3/.../c3g_YYYY.MM.DD.tif`, no lead metadata). It is **not** a 7-band stack.

**Interpretation after Notebook 01 Cells 6–10 + Notebook 02 Cell 11 inspection:**
- `Band 1` = **daily total precipitation for that date** (`v3/daily`), not `D+1..D+7` stack.
- **Therefore:**
  - `gefs_d1` (for issue date `D`) is genuinely available from the single file at `D` (or at `D+1` depending on issue vs target mapping — must be verified per archive structure).
  - `gefs_d2..d7` are **not** in that file and must be represented as **`NaN` placeholders**, not zero.
  - Example final `forecast_features_base.csv:1294` schema keeps `forecast_date,block,gefs_d1,gefs_d2,gefs_d3,gefs_d4,gefs_d5,gefs_d6,gefs_d7,target_7d_rainfall_mm` where `gefs_d1` is real and `gefs_d2..d7` are `NaN` (to be filled in Notebook 03 by joining 7 daily files per `D`: `D+1` file → `gefs_d1`, etc., if the archive requires that mapping).

**Archive structure uncertainty (critical unresolved):**
The CHC archive has `daily`, `05_day`, `10_day`, `15_day`, `pentad` folders. The `daily` folder path is `.../v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` — each folder contains a daily file. It is *plausible* that the 7-day horizon for `D` is assembled from **7 separate daily files** (`D+1` file, `D+2` file, ...). The exact mapping `issue date D → target date D+1` must be confirmed from the product (not assumed). Notebook 03 must inspect the `05_day/10_day` products and confirm whether `gefs_d1` should be the file at `D` or at `D+1`.

**Rule:** Do NOT assume `Band 1 = D+1` or invent `D+1..D+7` values. Use the actual product: `gefs_d1` real, `gefs_d2..d7 = NaN` for now, and document that `NaN` means “not available yet, not zero.”

**Ensemble uncertainty:** Only add if the accessible product cleanly supports it; do not invent ensemble features.

---

## 13. Recent Rainfall Features (for Notebook 03)

Later feature engineering should create, using **only observations ≤ D**:

- Sums: `rain_1d, rain_3d, rain_7d, rain_14d, rain_30d` (ending at `D`)
- Lags: `rain_lag_1 = D, rain_lag_2 = D-1, ... rain_lag_7 = D-6`

These are inputs; `rain_lag` must be `D` and earlier, never `D+1`.

---

## 14. Spatial, Seasonal, Climate, Soil Features (for Notebook 03)

- **Spatial:** Shared model across 6 blocks; represent location via `centroid lat/lon` (from `sangrur_blocks_bhuvan.gpkg`), not arbitrary block IDs.
- **Seasonal:** `sin(2π·day_of_year/365)`, `cos(...)` (cyclic).
- **Climate:** Initially one primary `ENSO_index` (e.g., Nino3.4/ONI) available at `D` (month of `D`), plus optional `ENSO_category`.
- **Soil:** Per-block mean/median of `clay, sand, silt, SOC, pH` from `SoilGrids` rasters clipped to `sangrur_blocks_bhuvan.gpkg` (static, same for all dates).

All not yet built in Notebook 02 — Notebook 02 only sets up the forecast-vs-observation join.

---

## 15. Model Strategy

**Not committing to one algorithm prematurely.** Planned comparison:

1.  Climatology baseline (historical typical for that date)
2.  Persistence baseline (recent rainfall)
3.  Raw CHIRPS-GEFS baseline (is post-processing needed?)
4.  Random Forest
5.  XGBoost
6.  LightGBM

**Primary ML candidates:** `Random Forest, XGBoost, LightGBM` (all tabular, handle non-linear, little preprocessing, explainable). Deep learning is **NOT** part of core prototype unless core is complete and substantial time remains.

**Selection:** Choose strictly on chronological validation/test performance (MAE, RMSE, R², bias for regression; Accuracy, F1, log loss, Brier for probabilistic), not on theoretical preference. Do not declare a winner until evaluated.

---

## 16. Temporal Validation & Historical Backtesting

**Do NOT randomly split.** Preserve time ordering.

**Intended chronological split (per PROJECT_CONTEXT, but must adapt to actual CHIRPS coverage):**
- Training: `2001–2019` (but `2001–2009` has no CHIRPS target, see §17)
- Validation: `2021–2023`
- Testing: `2024–2025`
- **2020 excluded** (CHIRPS-GEFS gap)

**However:** Current `Sangrur_Block_Daily_Rainfall_2010_2025.csv:35064` covers `2010-01-01 to 2025-12-31` (5844 unique dates, 6 per date, 0 missing dates, `0` missing `rainfall_mm`). Therefore **2001–2009 has no observed `target_7d`**. Notebook 02 `Cell 14` already handles this: it validates `D+1..D+7` all have 6 blocks, and marks `D` valid only if all 7 target dates exist. Result: `CANDIDATE_DATES 8758 → VALID_DATES 5472` (where `D+7 ≤ 2025-12-31` and `D+1 ≥ 2010-01-01`), with early `2001–2009` automatically invalid (reason `missing CHIRPS target`). This is correctly reported in `Cell 14` `Invalid per year`.

**Historical backtesting must simulate:** `Past D → Past inputs by D → Prediction → Compare to actual CHIRPS D+1..D+7`. The dataset `historical_forecast_targets.csv:4210` (currently demo `4` dates `2009-12-31 to 2010-01-03 ×6 =24 rows` in `data/processed/historical_forecast_targets.csv`) follows this.

**Unresolved:** Whether to restrict prototype to `2010–2025` (available targets) or to acquire/aggregate earlier CHIRPS (2001–2009) if time permits. See §20.

---

## 17. Live-Demo Strategy (vs Historical)

**Historical (prove skill):** `Past D → Past inputs ≤D → Prediction → Compare to CHIRPS D+1..D+7` (backtest, never seen in training).

**Live (demo):** `Current date → Latest CHIRPS through today, latest CHIRPS-GEFS issued today, current ENSO, static soil/spatial → Predict next 7–10 days` for 6 blocks, with map. Use real current data, not an old historical example. Do not mix workflows.

---

## 18. Architecture

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
             Sangrur Boundaries (Bhuvan 6-block)
                     │
                     ▼
              FEATURE ENGINE
              (rain_7d/lags, gefs_d1..d7, sin/cos, ENSO, soil, lat/lon)
                     │
                     ▼
          FORECASTING MODELS
                     │
       ┌─────────────┼─────────────┐
       │             │             │
   Baselines    Random Forest   XGBoost
                                  │
                               LightGBM
                     │
                     ▼
              MODEL SELECTION
            (chronological R²/MAE)
                     │
                     ▼
          PROBABILISTIC OUTPUT
              Low/Normal/High
                     │
                     ▼
           6 SANGRUR BLOCKS
                     │
          ┌──────────┴──────────┐
          │                     │
     Rainfall mm          Low/Normal/High
          │                     │
          └──────────┬──────────┘
                     ▼
              MAP / DASHBOARD
                     │
                     ▼
             AGRICULTURAL
                ADVISORY (secondary)
```

**Component mapping:**
- **Notebooks:** `01` acquisition/testing, `02` historical forecast + target (`forecast_features_base.csv`), `03` feature engineering, `04` model training/comparison, `05` backtest/probabilistic calibration, `06` live inference.
- **Src:** `src/data/chirps_gefs.py` (`zonal_stats`, `extract_block_rainfall`, `build_tidy_forecast`) — reused, not duplicated.
- **Dashboard:** `dashboard/` (planned, not yet built).
- **Configs:** `configs/` (planned).

---

## 19. Repository Structure (intended, current as of 2026-09-08)

```
hyperlocal-monsoon/ (local saarthi-2)
├── data/
│   ├── raw/
│   │   ├── rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691
│   │   ├── forecast/c3g_2026.09.04.tif:64908286, c3g_2019.09.04.tif:67698552, c3g_2010.07.15.tif:71828194, c3g_2025.08.01.tif:66387426, plus 2009-12-31..2010-01-03 winter tifs (7200×2400×1, 0.05°, -9999 sentinel) — 8 tifs total
│   │   ├── climate/ersst5.nino.mth.91-20.ascii:67087 (NOAA CPC ERSSTv5 Nino, monthly 1950-01→2026-06, primary index NINO34_ANOM)
│   │   ├── soil/Clay|Sand|Silt|OrganicCarbon|pH 0-5cm mean .tif ×5 (SoilGrids, EPSG:4326, block means extracted in 03)
│   │   ├── soil/ (empty, SoilGrids to be added)
│   │   └── boundaries/sangrur_blocks_bhuvan.gpkg:184320 (authoritative, 6 MultiPolygon, OGC:CRS84, b_name)
│   │       ├── bhuvan_blocks.parquet:97298502 (source, 6393 national, 74 Punjab, 6 Sangrur)
│   │       └── sangrur_blocks.shp (+.shx/.dbf/.prj/.cpg/.geojson) 916 bytes synthetic fallback (do NOT use)
│   └── processed/
│       ├── chirps_gefs/chirps_gefs_sangrur_2026-09-04.csv:280, _qc.csv, _wide.csv
│       ├── historical/historical_gefs_block_forecasts.csv:1555, processing_log.csv:205, preflight_availability.csv:760080, extracted/
│       ├── historical_forecast_targets.csv:4750, .parquet:13008 (3 dates ×6 =18 rows, monsoon demo 2010-07-15/2019-09-04/2025-08-01)
│       ├── forecast_features_base.csv:1143, .parquet:6064 (18 rows, gefs_d1 real, gefs_d2..d7 NaN, target_7d 7-76mm)
│       └── forecast_features_rainfall.csv:5261, .parquet:14822 (36 rows, 6 dates × 6 blocks, rain_1d/3d/7d/14d/30d + rain_lag_1..7, no leakage)
├── notebooks/
│   ├── 01_chirps_gefs_acquisition.ipynb:294854 (30 cells, validated, now 6-block Bhuvan)
│   ├── 02_build_forecasting_dataset.ipynb:250113 (42 cells: title +41, Cells 1–40 complete, demo 6 dates × 6 blocks = 36 rows)
│   └── 03_feature_engineering.ipynb (31 cells: title +30, COMPLETE 2026-09-09, final_ml_dataset 36×32 + metadata saved)
├── src/
│   ├── data/chirps_gefs.py:2830 (zonal_stats, extract_block_rainfall, build_tidy_forecast)
│   ├── data/download_sangrur_boundaries.py:13066 (Bhuvan parquet → GPKG, validated 6 blocks)
│   └── utils/__init__.py
├── dashboard/ (empty, planned)
├── configs/ (empty, planned)
├── tests/ (empty, planned)
├── requirements.txt:307 (rasterio 1.4.4, geopandas 1.1.4, pandas 2.3.3, numpy 2.4.1, etc.)
├── .gitignore:279 (ignores *.tif, *.tif.tmp, __pycache__, etc.)
├── docs/project_context/ (11 files, this handoff package)
└── AGENTS.md (entry point → docs/project_context/00_MASTER_CONTEXT.md)
```

---

## 20. Notebook Status (exact current state)

### Notebook 01: `01_chirps_gefs_acquisition.ipynb` (294854 bytes, 30 → actually 30 code/markdown inc. title)
**Purpose:** Acquire/test current CHIRPS-GEFS forecast and extract rainfall for 6 Bhuvan blocks.

**Cells 1–30 completed and validated (as of 2026-09-07, after switch to Bhuvan GPKG):**
- **1 Imports** (`Path, requests, rasterio, geopandas, pandas, numpy`)
- **2 Paths** (robust `CWD.name=="notebooks"? parent : Path("..")`, creates `forecast/boundaries`)
- **3 Forecast date** (`date(2026,9,4)` single source, `year/month/day`, `forecast_date_str` `2026.09.04`, `forecast_date_path` `2026/09/04`, `url` built, `output_file` derived, no hardcoded `2026/09/04` elsewhere)
- **4 HEAD** (`requests.head(url)` `200 OK 61.9 MB`)
- **5 Download** (atomic `stream → .tmp → rename`, `>50 MB + rasterio.open` skip `Already exists: c3g_2026.09.04.tif (64.9 MB) — skipping` `0.02s`)
- **6 Open** (`rasterio.open(output_file)` → `tif_path`)
- **7 Dims** (`7200×2400×1, 17M pixels, float32`)
- **8 Res** (`0.05°`, transform)
- **9 CRS/Bounds** (`EPSG:4326, -180,-60,180,60`)
- **10 Bands/metadata** (`count 1, descriptions (None,), nodata None` sentinel `-9999`, `1` band = daily total, **not 7-day stack**)
- **11 Load boundaries** (now authoritative `sangrur_blocks_bhuvan.gpkg` layer `sangrur_blocks`, not synthetic; lists `6` shp/geojson/gpkg, prefers `bhuvan.gpkg`)
- **12 Inspect 6 blocks** (`b_name` column, 6 names, `MultiPolygon ×6`, `48` pixels/block uniform was synthetic, now `12-28` varying with real admin)
- **13 CRS** (`OGC:CRS84` vs `EPSG:4326` → `to_crs` → `EPSG:4326`)
- **14 Reproject** (copy, not overwrite, `6` preserved)
- **15 Visual** (`28×30` window `840` valid, red borders)
- **16 Final GDF** (`sangrur_final` 6)
- **17 BLOCK_COL** (`b_name`)
- **18 Coverage** (`Sangrur fully covered True`)
- **19 Overlay** (windowed `Blues` + red)
- **20 Crop** (`rio_mask` `crop=True` → `(1,17,19)`, `75.5-76.45`)
- **21 Inspect cropped** (`288 valid, 4-15 mm`)
- **22 Zonal** (`mean` per block `6.4-12.3 mm`, `valid_pixel_count`)
- **23 Inspect values** (no missing/negative, not all identical)
- **24 Lead structure** (single daily, need 7 daily files for `gefs_d1..d7`)
- **25 Tidy** (`forecast_date,block,lead_day,rainfall_mm` 6 rows)
- **26 Wide** (`gefs_d1` real, `gefs_d2..d7 = NaN` explicit, `gefs_3d/7d NaN`)
- **27 Save** (`data/processed/chirps_gefs/chirps_gefs_sangrur_2026-09-04.csv:280`)
- **28 Validate** (6 blocks, date match, numeric, no negative, `PASS`)
- **29 Final display** (CSV, stats `mean 9.14`, 6 names, `NoData 0, Negative 0`, map, `9× PASS`)
- **(Cell 30 is the empty placeholder `0eff608f`)**

**Outputs:** `data/processed/chirps_gefs/*` (3 CSVs), `data/processed/forecast_features_base.csv:1294` (also validated via Notebook 02 Cells 31-35). Notebook 01 final validation `PASS` after switch to Bhuvan.

**Known limitation:** `c3g_2026.09.04.tif` is `1` band daily total for `2026-09-04`, not `D+1..D+7`. Notebook 01 correctly documents this and does not invent `D+1..D+7`.

### Notebook 02: `02_build_forecasting_dataset.ipynb` (250113 bytes, 42 cells: title +41, Cells 1–40 complete, demo 6 dates × 6 blocks = 36 rows)

**Purpose:** Build historical forecast-vs-observation dataset (`forecast_date+block` → `CHIRPS-GEFS` forecast + `CHIRPS` target `D+1..D+7`), with `Cells 31–35` pivoting to `forecast_features_base.csv` and `Cells 36–40` building rainfall features into `forecast_features_rainfall.csv`.

**Cells 1–15 (planned 1–15, completed as demo):**
- **1 Imports** (`pathlib, datetime, pandas, numpy, geopandas, rasterio, requests, tqdm` + `src/data/chirps_gefs.py`)
- **2 Paths** (robust, `RAINFALL_DIR, FORECAST_DIR, CLIMATE_DIR, SOIL_DIR, BOUNDARY_DIR, PROCESSED_DIR, HISTORICAL_DIR, CHIRPS_GEFS_PROCESSED`)
- **3 Boundaries** (`sangrur_blocks_bhuvan.gpkg:184320` `6` `b_name` `Dhuri…Sunam` verified, `MultiPolygon`, not synthetic)
- **4 Period** (`HIST_START 2001-01-01, HIST_END 2025-12-31, EXCLUDE 2020`, `CHIRPS 2010-01-01 to 2025-12-31 5844 dates, 35064 rows, latest_complete_D 2025-12-24`)
- **5 Candidates** (`9131` all → `8765` after `2020` → `8758` after `D+7 ≤ 2025-12-31`, `8758` candidate `2001-01-01` to `2025-12-24`, `2025:358`)
- **6 Representative** (5 dates `2001-06-15, 2010-07-15, 2019-09-04, 2021-08-20, 2025-08-01` → filtered to valid, `REPRESENTATIVE_DATES`)
- **7 URL builder** (`build_chirps_gefs_url(date)` → `.../v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif`, tested `2026-09-04`)
- **8 Availability** (`HEAD` 5 reps, `AVAILABILITY_DF` `5× [forecast_date,url,status_code,available,size_mb]`)
- **9 Coverage** (diagnostics: `5/5` available, `2020` excluded, naming `YYYY/MM/DD` vs `YYYY.MM.DD`, sizes `50-70 MB`)
- **10 Single** (download one historical `2019-09-04` `67 MB` `c3g_2019.09.04.tif:67698552`, `extract_block_rainfall` → `6` rows `Dhuri…`, `PASS` 5 checks)
- **11 Lead structure** (inspects `c3g_2019.09.04.tif` → `1` band → `single_daily` daily total for `D`, not `D+1..D+7` stack, need 7 daily files for `gefs_d1..d7`)
- **12 Sample def** (documents `INPUTS ≤D: CHIRPS lags, GEFS at D (7 files), ENSO at D, soil, lat/lon, sin/cos` → `TARGET D+1..D+7`, `FORECAST_HORIZON=7, MIN_TARGET_DAYS=7`, anti-leakage)
- **13 CHIRPS inspect** (finds `Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` programmatically, `5844` dates `2010-2025`, `6` blocks, no missing dates, `0` missing `rainfall_mm`)
- **14 Valid samples** (optimized `0.11s` via `set + dict`, not `2.1B` scans: `CANDIDATE_DATES 8758` → `chirps_dates_set 5844` + `date_block_count` → `VALIDATION_DF` `8758` rows `forecast_date,target_start,target_end,valid,reason` → `valid 5472, invalid 3286` (mostly `2001-2009` where `D+7 <2010-01-01`), `First valid 2009-12-31`, `Last 2025-12-24`)
- **15 Estimate** (`5472` valid `×6 = 32,832` block samples, `5472` files `~346 GB` single, `38,304` files `~2.4 TB` for 7×, demo `10` → `0.6 GB` / `3` → `0.18 GB`)

**Cells 16–30 (planned 16–30, completed as demo `3` dates due to 90 KB/s):**
- **16 Config** (`HIST_CONFIG` with `REQUEST_TIMEOUT=(10,60), RETRY_COUNT=3, CHUNK_SIZE=1MB, SKIP_EXISTING=True, KEEP_RAW=True, VERIFY_RASTERIO=True`, `HISTORICAL_DIR/extracted`, `LOG` path, `LEAD_STRUCTURE=single_daily`, resumable)
- **17 URL builder** (`build_gefs_url`, `build_gefs_local_path` flat `c3g_YYYY.MM.DD.tif`, tested 3 dates)
- **18 Preflight** (was `HEAD 5472` sequential `1.59s/it` → `2:25:08`, patched to `ThreadPool(20)` → `~7 mins` first, then `CACHE preflight_availability.csv:760080` `5472` rows → `<1s` after)
- **19 Diagnostics** (`requested 5472, available, unavailable, local, est size 346 GB, by year, missing, earliest/latest, 2020 excluded PASS`, `VALID_GEFS_FORECAST_DATES` `5472`)
- **20 Download function** (`download_file(url,dest,timeout,retries,chunk_size,skip_existing,verify_rasterio)` → `tmp → rename`, `retry 2×`, `rasterio` verify, tested on `2019-09-04`)
- **21 Process function** (`process_historical_forecast_date(D)` → `7` files logic but for `single_daily` does `1` file per `D` + `extract_block_rainfall` → `6` rows `forecast_date,lead_day,block,forecast_target_date,forecast_rainfall_mm,valid_pixel_count,source_file`)
- **22 Test** (originally 5 dates `2001-2025`, now optimized to **3 dates `2010-07-15, 2019-09-04, 2025-08-01`** for speed: `2019` cached `0.02s` `1/3`, `2010` + `2025` `~11m` each at `90 KB/s` → `~22 mins` first, then `<5s` for all 3; previous `5` took `9m41s` at `0/3` on `2009-12-31` early reforecast hang)
- **23 Schema** (markdown + code documenting `forecast_date,block,lead_day,forecast_target_date,forecast_rainfall_mm` unique key)
- **24 Init resumable** (`OUTPUT_CSV=historical_gefs_block_forecasts.csv:1874`, `LOG_CSV=historical_gefs_processing_log.csv:205`, recovers `PROCESSED_DATES`, `3` demo `18` rows so far)
- **25 Bulk** (`DEMO_LIMIT=3` now, was `10` → `1.5 hours` at `90 KB/s`; `3` → `22 mins` first, `<5s` after; loops `VALID_GEFS_FORECAST_DATES` with `tqdm`, `expected 6` per `D`, `tif.tmp→rename`, `log.csv` `success/failed/skipped`)
- **26 Summary** (`requested 5472, success 3, failed 0, skipped, blocks 6, leads 1, forecast records 18, date range 2010-2025, per block/lead, failures by year, missing, duplicates 0`)
- **27 CHIRPS normalize** (`df_raw` → `CHIRPS_NORM` `35064`, `date→datetime, block→strip, rainfall→numeric, Lehragaga→Lehra`, `6` blocks, `2010-2025`, no gaps)
- **28 7-day target** (`target_7d_rainfall_mm = sum D+1..D+7` per `D,block`, requires 7 valid days, `TARGET_DF` `5472×6=32832` rows, `VALID_TARGET_DF` `5472×6` valid)
- **29 Join** (`MERGED_DF` left join `forecast 18` + `target 32832` on `forecast_date+block` → `18` rows `forecast_date,block,lead_day,forecast_target_date,forecast_rainfall_mm,target_7d_rainfall_mm,...`, reports `unmatched`)
 - **30 Final validation + save** (`historical_forecast_targets.csv:4750` + `.parquet:13008` `18` rows demo `3` monsoon dates `2010-07-15/2019-09-04/2025-08-01`, `target_7d` 7-76mm per block, `30` code checks, `CHIRPS-GEFS + CHIRPS target dataset created successfully` if critical pass)
- - **31 Load/inspect historical forecast-target dataset** (loads `Cell 30` output, normalizes types, `shape 18×18`, `6` blocks, `lead 1`, `isna 0`, monsoon targets 7-76mm)
- - **32 Analyze lead structure** (`n_blocks 6, n_leads 1, min/max 1`, `lead 1` only)
- - **33 Pivot** (`pivot → gefs_d1 real, gefs_d2..d7 NaN explicit`, `gefs_d1..d7 + target_7d` `18×10`, gefs_d1 0.0/2.6/14.6 per date)
- - **34 Validate wide** (`1. forecast_date+block unique 0 duplicates PASS, 2. 6 blocks per D PASS, 3. gefs_d1 present 0 missing, gefs_d2..d7 18 missing EXPECTED (not failure), 4. no negative, 5. temporal gefs_d1 is D, 6. target is D+1..D+7, 7. no leakage`)
- - **35 Save** (`forecast_features_base.csv:1143` + `.parquet:6064` `18×10` `forecast_date,block,gefs_d1,gefs_d2..d7,target_7d` with `gefs_d1` 0.0 (2010-07-15), 2.6 (2019-09-04), 14.6 (2025-08-01) vs `target_7d` 62-76 / 9-11 / 7-15, `gefs_d2..d7` all `NaN` explicit, `0` duplicates, `0` negative → `Forecast feature base created successfully`)
- - **36 Load base + CHIRPS** (`forecast_features_base.parquet` + `Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691`, normalizes `forecast_date→datetime, block→strip, rainfall→numeric`, verifies `6` blocks, `forecast D-29` history OK for all dates)
- - **37 Feature function** (`calculate_rainfall_features(chirps_df, base_df)` reusable, per-block dict lookup, `rain_1d=D, rain_3d=D-2..D, rain_7d=D-6..D, rain_14d=D-13..D, rain_30d=D-29..D, rain_lag_1..7=D-1..D-7`, `only date<=D`, no cross-block, no target/GEFS, NaN if missing not zero)
- - **38 Manual verify** (4 combos `2010-07-15 Dhuri/Sunam, 2019-09-04 Sangrur, 2025-08-01 Moonak`: prints `D-30..D` + `D-7..D` + `12` features + manual `PASS` for `rain_1d/3d`)
- - **39 Automated validation** (9 checks: no D+1 leakage PASS, no target, per-block independence, lag direction, rolling windows, numeric/finite, non-negative, missing 0% for all 12 features, early-history 0 missing)
- - **40 Join + save** (`forecast_features_base (18)` + `feat (18)` → `18×22` merged, validates `0 unmatched, 0 duplicates, 0 missing`, saves `forecast_features_rainfall.csv:5261` + `.parquet:14822`, reloads parquet, prints `Leakage check PASS`)

**Current demo state (as of 2026-09-08, after monsoon regeneration + rainfall features):**
- `data/raw/forecast/` has `8` tifs: `c3g_2009.12.31.tif:67284246`, `c3g_2010.01.01.tif:67966292`, `c3g_2010.01.02.tif:68501224`, `c3g_2010.01.03.tif:67721490`, `c3g_2010.07.15.tif:71828194`, `c3g_2019.09.04.tif:67698552`, `c3g_2025.08.01.tif:66387426`, `c3g_2026.09.04.tif:64908286` (all `>50 MB` valid, 8 total)
- `data/processed/historical/` has `historical_gefs_block_forecasts.csv:1555` (3 monsoon demo dates `2010-07-15/2019-09-04/2025-08-01` → `18` rows) + `preflight_availability.csv:760080` (5472 `HEAD` cache) + `historical_gefs_processing_log.csv:205`
- `data/processed/historical_forecast_targets.csv:4750` (3 monsoon dates → `18` rows, target_7d 7-76mm) + `.parquet:13008`
- `data/processed/forecast_features_base.csv:1143` + `.parquet:6064` (18 rows monsoon, `gefs_d1` real, `gefs_d2..d7` NaN) — regenerated to 36 rows with 6 dates including `2009-12-31..2010-01-02`
- `data/processed/forecast_features_rainfall.csv:5261` + `.parquet:14822` (36 rows, 6 dates × 6 blocks, `rain_1d/3d/7d/14d/30d + rain_lag_1..7` (12 cols), no leakage, all validated)

**Next cells planned for 02:** None — `Cells 31–40` complete. `Notebook 02` is complete; full `5472` bulk remains as one-time background job.

### Notebook 03: `03_feature_engineering.ipynb` (6 cells: title +5, Cells 1–5 complete)

**Purpose:** Transform validated historical forecast/rainfall dataset into FINAL ML-ready feature dataset. Do NOT train models. Cells 1–5: imports, load, audit, feature groups, clean frame. Cells 6+ (future): ENSO, soil, spatial, calendar features.

**Cells 1–5 completed:**
- **1 Imports** (pathlib, pandas, numpy, geopandas, matplotlib, rasterio if available; robust `PROJECT_ROOT` detection; prints paths + python env)
- **2 Load** (`forecast_features_rainfall.parquet` preferred, CSV fallback; normalizes `forecast_date→datetime, block→string`; verifies 22 expected columns present; reports missing per column; `gefs_d2..d7` NaN explicit)
- **3 Audit** (10 checks: row=forecast_date+block, unique, 6 blocks, chronological, target exists, no negative/inf, GEFS numeric, rainfall numeric, no duplicates; compact audit table with dtype/missing/min/max/mean; summary: 36 rows, 6 dates, 6 blocks, 0 duplicates, 0 target missing)
- **4 Feature groups** (explicit lists: `GEFS_FEATURES (7)`, `RECENT_RAINFALL_FEATURES (12)`, `TARGET_COLUMNS (1)`, empty `ENSO_FEATURES, SEASONAL_FEATURES, SOIL_FEATURES, SPATIAL_FEATURES` for later; `IDENTIFIER_COLUMNS (2)`)
- **5 Clean frame** (`ml_features (36×22)`, `X_BASE (36×19)` = GEFS 7 + rainfall 12, `y (36,)` = target; NaNs kept explicit, no row drops, chronological preserved)

**Status:** COMPLETE 2026-09-09 (31 cells, run twice top-to-bottom, ALL PASS). Cells 11–30 added seasonal (sin/cos doy), soil (5 block means via polygon mask, texture closure 968–979, pH 7.66–7.87), spatial (UTM-43N centroids), GEFS diagnostics (d2–d7 NaN expected, no aggregates fabricated), FINAL_FEATURES 29, X (36×29)/y, eligibility 36/36, saved `final_ml_dataset.parquet:20.9KB` + `.csv:12.3KB` + `final_feature_metadata.csv` (32 rows), reload-validated 10/10. Next: Notebook 04 training.

### Notebooks 04–06 (planned):
- **04:** Train + compare `Climatology, Persistence, Raw GEFS, RF, XGBoost, LightGBM` (chronological `2001–2019` train / `2021–2023` val / `2024–2025` test, but adapted to `2010–2025` actual).
- **05:** Backtest, metrics `MAE/RMSE/R²` + `Accuracy/F1/log loss/Brier`, calibrate `Low/Normal/High` probabilities via percentiles.
- **06:** Live inference with `datetime.today()` + `latest CHIRPS/CHIRPS-GEFS/ENSO` + `src` → `6` blocks map.

**Dashboard:** `dashboard/` (planned, not yet built).

---

## 21. Decisions Made During This Session

| Decision | Reason | Consequence |
|----------|--------|-------------|
| **Legacy 6-block Bhuvan GeoPackage as authoritative** | SIH prototype established 6, not 8; synthetic grid is 5-vertex rectangles, not admin | `sangrur_blocks_bhuvan.gpkg` `MultiPolygon` `OGC:CRS84` is source of truth; `sangrur_blocks.shp` remains as fallback test artifact |
| **Single daily CHIRPS-GEFS product = 1 band** | `01` Cells 6–10 inspection: `count 1, descriptions (None,)`, `01` `c10` + `02` `c11` both `1` band | `gefs_d1` real, `gefs_d2..d7` must be `NaN` placeholders, not zero; `Notebook 03` must join 7 daily files per `D` for `gefs_d1..d7` |
| **Keep `forecast_date` as single source of truth** | `date(2026,9,4)` → `forecast_date_str` `2026.09.04`, `forecast_date_path` `2026/09/04`, `url`, `output_file` all derived | Changing to `2026-09-10` auto-targets `c3g_2026.09.10.tif` everywhere; reusable for bulk `2001–2025` |
| **Atomic download + skip + curl resume** | `01` `c3g_2026.09.04.tif` was truncated to `13 MB`/`5.2 MB` on interrupt, `60.8 MB` hang at `1.59s/it` | `stream → .tmp → rename`, `>50 MB + rasterio.open` skip `Already exists — skipping 0.02s`, `curl -C -` resume rescued `57→67 MB` in `15s` |
| **Shared model across 6 blocks with `lat/lon`** | `PROJECT_CONTEXT` says not 6 independent models | `lat/lon` centroids + soil provide spatial distinction |
| **Chronological, not random, split** | Time series; leakage if shuffled | `2001–2019` train / `2021–2023` val / `2024–2025` test (adapted to `2010–2025` actual) |
| **RF/XGBoost/LightGBM candidates, no winner yet** | `PROJECT_CONTEXT` says choose on validation, not popularity | `04` will compare objectively |
| **CSV + Parquet for `Cell 35`** | User explicitly requested `forecast_features_base.csv` **and** `.parquet` (human + efficient) | `01` saves both; `02` `Cell 35` saves `1294` + `5982` |
| **Explicit `NaN` for `gefs_d2..d7`** | User: `gefs_d1` real, `gefs_d2..d7` `NaN` = “not available yet”, not zero; keeps fixed schema for `03` | `Cell 34` does not mark `D2..D7` `NaN` as `FAIL` |
| **Demo `3` dates not `10` for `Cell 25`** | User has `90 KB/s` → `10×60 MB = 600 MB` would be `1.5 hours` (`9/10 [08:59<43:25]`), `3×60 MB = 180 MB` → `22 mins` once then `<5s` fits storage/time | `Cell 25` `DEMO_LIMIT=3` (was `10`), `Cell 22` `3` dates `2010-07-15,2019-09-04,2025-08-01` with `2019` cached first |
| **ThreadPool(20) for `Cell 18` `HEAD 5472`** | Sequential `5472×1.59s = 2:25:08` as seen `8/5472 [00:12]` → `7.93 it/s` after patch → `20 threads` → `~7 mins` first, `<1s` after via `preflight_availability.csv:760080` cache | `Cell 18` now has cache check at top → `Loaded cache — skipping 5472 HEADs` |
| **Optimized `Cell 14` `0.11s` vs `3-5 mins`** | Old `Cell 14` did `2.1B` row scans (`8758×7×35064`) → minutes | New `Cell 14` uses `set + dict` (`5844` dates, `date_block_count`) → `0.06s` precompute + `0.11s` loop → `Valid 5472` |
| **No deep learning** | `PROJECT_CONTEXT` explicitly excludes unless core complete | Prototype stays tabular ML |

---

## 22. Unresolved Issues

1.  **CHIRPS block CSV coverage `2010-01-01` vs `2015-06-01`:** `Cell 13` found `35064` rows `2010-01-01 to 2025-12-31` (5844 dates), not `2015-06-01` as `PROJECT_CONTEXT` initially approximated. `Cell 14` correctly uses `2010-01-01` as `chirps_min` and validates `D+7 ≤ 2025-12-31`, marking `2001-2009` as `invalid` (`3286` of `8758` candidates). Decision needed: restrict prototype to `2010–2025` (available) or acquire earlier `2001-2009` CHIRPS via `CHIRPS/v3.0` `pentad` aggregation.

2.  **CHIRPS-GEFS issue vs target mapping:** `Cell 11` determined `single_daily` `1` band = daily total for that date. It is **not yet verified** whether `gefs_d1` should be the file at `D` (issue date total) or at `D+1` (target date). `Cell 33` currently sets `gefs_d1` from `D`'s file and `forecast_target_date = D`. `Notebook 03` must verify: does `gefs_d1` for `D=2019-09-04` correspond to `2019-09-04` or `2019-09-05`? This affects `D+1..D+7` join logic (need `D+1` file vs `D` file).

3.  **Single daily vs 7-day acquisition:** Current `forecast_features_base.csv:1294` has `gefs_d2..d7` all `NaN` (expected). `Notebook 03` must acquire/join the **7 daily files per `D`** (`D+1` file → `gefs_d1`, etc.) if 7-day horizon is to be `gefs_d1..d7` forecast, not `gefs_d1` only.

4.  **Ensemble uncertainty:** Optional, should not be forced. The `daily` product’s NetCDF ensemble mean vs spread not yet inspected; do not invent ensemble features.

5.  **Full `5472` bulk not yet run interactively:** Demo is `3` monsoon dates (`18` rows) `2010-07-15/2019-09-04/2025-08-01` (target_7d 62-76/9-11/7-15). Full `5472×6=32,832` training samples (`346 GB` raw, `2.4 TB` for 7×) would require `KEEP_RAW_GEFS_FILES=False` (delete after extract) and `ThreadPool` background job, not interactive `Run All`. Winter 4 dates `2009-12-31..2010-01-03` tifs remain in `data/raw/forecast/` as early-period demo (0-1mm targets) but not in current `18`-row monsoon demo.

6.  **SoilGrids & ENSO not yet acquired:** `data/raw/climate/` and `data/raw/soil/` are empty placeholders. Need `Nino3.4` monthly CSV and `SoilGrids` per-block mean for `03`.

---

## 23. Exact Next Steps (ordered, actionable)

**Immediate (next session, no new development until reading this):**
1.  Read `00_MASTER_CONTEXT.md` + `01_PROJECT_GOALS.md` → `10_OPENCODE_INSTRUCTIONS.md` (this order).
2.  Verify `notebooks/01_chirps_gefs_acquisition.ipynb:30` still `PASS` (6 `MultiPolygon`, `gefs_d1` etc.) and `02:32` cells `Cell 14` `0.11s`, `Cell 18` `cached <1s`, `Cell 22` `3/3` `7.93 it/s`, `Cell 25` `3/3` after `22-min` first run.

**Then for `02` to be considered complete (if not already):**
3.  Let `Cell 18` finish its **one** `7-min` `20-thread` `HEAD 5472` run (if not yet cached) → `preflight_availability.csv:760080` → `Cell 19` `VALID_GEFS_FORECAST_DATES` `5472`.
4.  Let `Cell 25` finish its **one** `~22-min` run for `3` dates (`2010,2019,2025` → `18` rows) — after that, `Cell 25` and `Cell 22` will be `<5s` forever via `skip_existing`.
5.  Run `Cells 27–30` to confirm `historical_forecast_targets.csv:4210` → `Cells 31–35` to confirm `forecast_features_base.csv:1294` `gefs_d1` real, `gefs_d2..d7` `NaN` → `Forecast feature base created successfully`.

**For `03` (next notebook):**
6.  Verify `gefs_d1` temporal mapping (`D` vs `D+1`) by comparing `2019-09-04` `gefs_d1` file date vs `CHIRPS` target `2019-09-05..11`.
7.  Acquire/join the **7 daily GEFS files per `D`** to fill `gefs_d2..d7` (currently `NaN`) — implement in `03` `Cells` before feature engineering.
8.  Build recent rainfall features: `rain_1d, 3d, 7d, 14d, 30d` + `rain_lag_1..7` (≤`D` only, anti-leakage).
9.  Add `sin/cos(day_of_year)`, `ENSO_index` at `D`, `soil_*` per block, `lat/lon` centroids.
10. Create final `historical_ml_dataset` (one row = `forecast_date+block` with all inputs + `target_7d`) — keep `Punjab` `6` blocks, chronological, no future leakage.

**For `04–06` + dashboard (after `03`):**
11. `04`: Chronological split (`2010–2019` train / `2021–2023` val / `2024–2025` test, adapted from `2001–2019` due to `2010` CHIRPS start), train `Climatology, Persistence, Raw GEFS, RF, XGBoost, LightGBM`, compare `MAE/RMSE/R²` + `Bias`.
12. `05`: Backtest, calibrate `Low/Normal/High` via `p33/p66` of `target_7d`, metrics `Accuracy/F1/log loss/Brier`.
13. `06`: Live inference with `datetime.today()`, `latest CHIRPS/CHIRPS-GEFS/ENSO`, `6` blocks, map.
14. `dashboard/`: Block-level visualization.

---

## 24. Current State (as of end of session, 2026-09-08)

- **Code:** `src/data/chirps_gefs.py:2830` (`zonal_stats`, `extract_block_rainfall`, `build_tidy_forecast`) + `src/data/download_sangrur_boundaries.py:13066` (Bhuvan parquet → GPKG, validated) — both reusable, not duplicated in notebooks.
- **Data raw:** `rainfall 3.8 MB`, `forecast 8 tifs` (`2026-09-04 64.9 MB`, `2025-08-01 66.3 MB`, `2019-09-04 67.7 MB`, `2010-07-15 71.8 MB`, plus `2009-12-31..2010-01-03` 67 MB each winter), `bhuvan_blocks.parquet 97 MB`, `sangrur_blocks_bhuvan.gpkg 184 KB` (authoritative), `sangrur_blocks.shp 916 bytes` (synthetic fallback, untouched), `climate`/`soil` empty.
- **Data processed:** `chirps_gefs/` 3 CSVs, `historical/` extracted + `preflight_availability.csv:760080`, `historical_gefs_block_forecasts.csv:1555` (3 monsoon dates `18` rows), `historical_forecast_targets.csv:4750` (3 monsoon dates `18` rows, target_7d 7-76mm) + `.parquet:13008`, `forecast_features_base.csv:1143` + `.parquet:6064` (36 rows, 6 dates, `gefs_d1` real, `gefs_d2..d7` NaN), `forecast_features_rainfall.csv:5261` + `.parquet:14822` (36 rows, 6 dates × 6 blocks, 12 rainfall features, no leakage).
- **Notebooks:** `01:294854` `30` validated `6` `MultiPolygon` + `forecast_features_base` `PASS`; `02:250113` `42` (title +41) `Cells 1–40` complete, `Cells 36–40` rainfall features validated; `03` `31` (title +30) COMPLETE, `final_ml_dataset` 36×32 + metadata saved and reload-validated.
- **Requirements:** `requirements.txt:307` (`rasterio 1.4.4`, `geopandas 1.1.4`, etc.), `.gitignore:279` (`*.tif`, `*.tmp`, `__pycache__`).

---

## 25. Critical Rules for Next Session

- **Do NOT** switch to 8-block, use synthetic `sangrur_blocks.shp`, resurrect old-project assumptions, randomly split time, leak `D+1` as input, fill missing `target` with zero, invent `D+2..D+7` leads, invent historical data/ENSO/ensemble, add IOD/MJO/ERA5/SMAP/DEM/ECMWF S2S/deep learning, claim model winner without evaluation, silently drop failed dates, overwrite raw, break resumability, or create duplicates on rerun.
- **Do** inspect existing code before creating new code, preserve `01`/`02` completed work, keep `forecast_date` as single source of truth, keep `gefs_d1` real and `gefs_d2..d7` as `NaN` (not zero) until `03` joins 7 daily files, keep `forecast_date+block+lead_day` unique, keep `90 KB/s` network in mind (use `skip` + `curl` resume).

---

## 26. Decisions Made During This Session (log)

See §21.

---

## 27. Intended Future Workflow (no guessing)

`01` (done) → `02` (corrected 2026-09-10: 1098 JJAS dates, verified folder-D/files-D+1..D+7 mapping, vsicurl streaming)
→ `03` (re-executed on corrected data: 6588 rows, 31 features) → `03b` (gate 10 PASS / 0 FAIL, READY FOR NOTEBOOK 4)
→ `04` (COMPLETE 2026-09-10: honest winner Raw GEFS, test MAE 19.539; see §28)
→ `05` (COMPLETE 2026-09-10: live pipeline, first run D=2026-09-09, all NORMAL, READY FOR NOTEBOOK 6)
→ `06` (COMPLETE 2026-09-10: application package + API contract, gate 25/25 PASS,
READY FOR SPRING BOOT + REACT INTEGRATION) → Spring Boot REST API → React SIH web app.

---

## 28. Notebook 04 Results (2026-09-10, executed top-to-bottom, 0 errors)

- **Dataset:** 6588 rows, 31 features, 1098 JJAS dates, 6 blocks; splits train JJAS 2016–19 (2928) / val JJAS 2021–22 (1464) / test JJAS 2023–25 (2196); seed 42; no imputation (0 missing).
- **Test MAE:** Raw GEFS 19.539 (R² 0.377, bias −4.09) / RF 20.176 / XGB 21.082 / LGBM 21.344 / Climatology 24.640 / Persistence 30.536. Winner selected on val MAE (Raw GEFS 19.031). **ML does not beat the raw forecast — reported honestly** (`07_CURRENT_DECISIONS.md §14`).
- **Categories:** train p33/p66 → LOW<10.94 / HIGH>34.66 mm; residual-ECDF probabilities from winner's train residuals (train-only); test accuracy 0.685, macro F1 0.644, log loss 2.078, brier 1.087.
- **Artifacts:** `models/best_model.joblib` (baseline rule dict: doy means; reload-verified within 1e-9) + `data/processed/model_results/` (model_comparison.csv, test_predictions.csv, category_metrics.csv, feature_importance.csv incl. XGB supplement, category_thresholds.json, model_metadata.json).
- **Implication for demo:** the "model" behind the dashboard is the bias-corrected raw GEFS 7-day total + calibrated LOW/NORMAL/HIGH probabilities; do NOT claim ML superiority. Do NOT retrain on test.

## 29. Notebook 05 Live Inference (2026-09-10, executed top-to-bottom, 0 errors)

- **Method:** `models/inference_config.json` + `best_model.joblib` (`model_type: raw_gefs`) + `probability_calibration.npz` (winner train-residual ECDF, train-only). D = latest complete GEFS bundle (walk-back ≤30 d, fail loudly otherwise).
- **Mapping:** folder D, files D+1..D+7 via /vsicurl/ block means (same semantics as NB02); lead 0 never touched; prediction = sum of 7 leads; probabilities = NB04 residual-ECDF with NB04 thresholds (T33=10.939, T66=34.662); wet ≥1.0 mm/day is a labeled prototype heuristic.
- **First live run:** D=2026-09-09 (valid 09-10..09-16): totals Dhuri 22.50 / Lehra 22.81 / Malerkotla 17.82 / Moonak 17.81 / Sangrur 21.99 / Sunam 19.48 mm — all NORMAL (P(normal) ~0.53–0.54). CHIRPS/ENSO context NaN + flagged (local files end 2025-12-31 / 2026-06); forecast itself unaffected (GEFS-only).
- **Outputs:** `data/processed/live_forecast/latest_block_forecast.{csv,json}` + `latest_forecast_metadata.json`; gate printed READY FOR NOTEBOOK 6 (26/26 QC + files + reloads).
- **Decisions:** see `07_CURRENT_DECISIONS.md §15`–`§17`. Website integration COMPLETE (2026-09-10, branch `real-forecast-integration`): Spring Boot serves the NB06 package, vanilla JS + Leaflet UI on real data, Playwright-verified with 0 console errors.

---

## 30. MJO Pipeline (2026-09-11, notebooks/08_MJO_CNN_LSTM.ipynb, 15 cells, 0 errors)

- **Data:** `data/raw/mjo/MJO_RMM_cleaned_core.csv` (18,802 rows, 1974-06-01–2026-09-07; 1 gap 1978-03-16→1979-01-01 never bridged). Provenance NOT VERIFIED (no source in repo).
- **Setup:** lookback 84 daily, horizon 7, chronological split by target date (train ≤2005: 11,067 / val 2006–15: 3,652 / test ≥2016: 3,903), training-only StandardScaler, gap-safe sequences (0 crossings, audited).
- **Model:** Conv1D(32,3)→LSTM(32)→Dense(16)→Dense(2), 9,106 params, CPU (~0.9 min, 17 epochs, best val_loss 0.5085).
- **Test (beats persistence honestly):** CNN-LSTM RMM1 MAE 0.627 (persist 0.884) / RMM2 0.560 (0.849); phase acc 0.349 (0.207), macroF1 0.347. Phase derived from predicted RMM (convention agreement 1.000).
- **Artifacts:** `models/mjo_cnn_lstm.keras + mjo_scaler.pkl + mjo_metadata.json`, `data/processed/mjo_predictions.csv` (3,903×9). Reload max|diff| 0.00 PASS. Live: obs 2026-09-07 P7 → forecast 2026-09-14 P8, flagged stale. Gate: READY (probabilities N/A by design).
- **Rule kept:** MJO is climate CONTEXT ONLY — no rainfall-retraining experiment run, so no improvement claimed; Raw GEFS winner untouched.

## 31. IOD Status (2026-09-11): MODEL TRAINED — does NOT beat persistence (reported honestly)

- **Data:** `data/raw/iod/dmi.had.long.csv` (monthly HadISST1.1 DMI, 1,877 valid 1870-01–2026-05; 7 trailing -9999 dropped, no interior gaps). Provenance VERIFIED from file header (PSL/NOAA). `IOD_nb.ipynb` unmodified; method (W50–70E/10S–10N, E90–110E/10S–0, ±0.4°C) reused.
- **Notebook:** `notebooks/07_IOD_LightGBM.ipynb` (11 cells, 0 errors). Target DMI(M+1); ~25 causal features (DMI lags/rolls/trend, month sin/cos, ENSO prev-month, MJO monthly means); splits train≤2000 (309) / val 2001–12 (144) / test≥2013 (160); all leakage audits PASS.
- **Result (honest):** LightGBM selected on val (0.1639 ≈ persist 0.1624); TEST MAE 0.1936 vs persistence 0.1573, corr 0.81, phase acc 0.744 vs 0.825. **Persistence wins — displayed as experimental context, no superiority claimed.**
- **Artifacts:** `models/iod_best_model.joblib + iod_metadata.json`, `data/processed/iod_predictions.csv` (613×6). Reload PASS (diff 0.00). Live: data 2026-05 → forecast 2026-06 DMI +0.033 Neutral, stale=true.
- **Website:** `/api/climate-context` IOD node live (Neutral, LightGBM, stale shown); card supports available/stale/unavailable states; rainfall untouched.

## 32. Climate-Context + Website Integration (2026-09-11)

- `data/processed/climate_context/{mjo_current_context.json, climate_context_summary.json, iod_status.json}`: real MJO forecast (P8, 2026-09-14) + ENSO El Niño (+1.44°C, Jun 2026, local ERSST) + IOD unavailable. Decision: USE CLIMATE CONTEXT ONLY.
- Website (Spring Boot + vanilla JS, NO React): `ClimateContextService` (fail-soft) + `GET /api/climate-context` + `GET /api/mjo/latest`; timeline "Large-Scale Climate Context" card, fetch-first with unavailable fallback. Moonak verified 17.81 mm NORMAL; all endpoints HTTP 200; rainfall pipeline untouched.

---

*This file is the single source of truth for the next OpenCode session. If anything here contradicts an older file, this file wins.*
