# 09 — Next Steps (Ordered Roadmap)

> **Actionable roadmap starting from the immediate next action.** Do not start new work until `00_MASTER_CONTEXT.md` → `10_OPENCODE_INSTRUCTIONS.md` have been read.

---

## Immediate Next Action (for the brand-new OpenCode session)

**Read in order:**
`00_MASTER_CONTEXT.md` → `01_PROJECT_GOALS.md` → `02_SYSTEM_ARCHITECTURE.md` → `03_DATASETS.md` → `04_ML_STRATEGY.md` → `05_DATA_LEAKAGE_RULES.md` → `06_NOTEBOOK_STATUS.md` → `07_CURRENT_DECISIONS.md` → `08_UNRESOLVED_ISSUES.md` → `09_NEXT_STEPS.md` (this file) → `10_OPENCODE_INSTRUCTIONS.md`

Then verify:
- `notebooks/01_chirps_gefs_acquisition.ipynb:30` still `PASS` (6 `MultiPolygon`, `gefs_d1` 6.4–12.3)
- `notebooks/02_build_forecasting_dataset.ipynb:32` cells `14` `0.11s`, `18` cached `<1s`, `22` `3/3` `<5s`, `25` `3/3` `<5s`

Only then proceed below.

---

## After Cells 31–35 (Current State: Demo `4` Dates `24` Rows)

**Status just after this handoff:** `02` `Cells 31–35` are done per current spec with `gefs_d1` real, `gefs_d2..d7 = NaN` explicit, `forecast_features_base.csv:1294` + `.parquet:5982` (`24` rows demo). Full `5472` bulk is cached but not yet materialized as `32,832` rows.

**1. Inspect the resulting dataset (already done via Cells 31–35 demo, but re-verify for full if bulk is later run):**
- `data/processed/forecast_features_base.csv:1294` `shape (24,10)`, `gefs_d1` real, `gefs_d2..d7` all `NaN` (expected).

**2. Verify GEFS multi-day acquisition structure (critical before feature engineering):**
- Re-inspect `Cell 11` single-daily determination (`1` band = daily total for that date). Confirm whether `gefs_d1` for `D` should be file at `D` (currently) or at `D+1` (i.e., does `gefs_d1` for `D=2019-09-04` correspond to `2019-09-04` or `2019-09-05`?).
- If the archive requires `D+1..D+7` files for `gefs_d1..d7`, plan `Notebook 03` to join 7 daily files per `D` (currently `gefs_d2..d7` are `NaN` to be filled).

**Do NOT proceed to feature engineering until this is verified.**

---

## Notebook 03: Feature Engineering (Next Notebook to Create)

**3. Create recent rainfall features (≤D only):**
- `rain_1d, rain_3d, rain_7d, rain_14d, rain_30d` (sums ending at `D`)
- `rain_lag_1 = D, rain_lag_2 = D-1, ... rain_lag_7 = D-6`
- Use `Sangrur_Block_Daily_Rainfall_2010_2025.csv:35064` (already normalized in `02` `Cell 27`), strictly `≤D`, no future leakage.
- Also join the remaining `gefs_d2..d7` daily files per `D` to fill the `NaN`s in `forecast_features_base.csv` (from step 2 verification).

**4. Add ENSO:**
- Download `Nino3.4/ONI` monthly CSV to `data/raw/climate/` (e.g., `ersst5.nino.mth.81-10.ascii`).
- Join to each `forecast_date` by `year-month` of `D` (value available at `D`, not future).
- Adds `ENSO_index` + optional `ENSO_category`.

**5. Add seasonality:**
- `day_of_year` from `forecast_date` → `sin_doy = sin(2π·doy/365)`, `cos_doy = cos(2π·doy/365)` (cyclic).

**6. Add soil:**
- Download `SoilGrids` rasters (`clay, sand, silt, SOC, pH`) to `data/raw/soil/`.
- Clip to `sangrur_blocks_bhuvan.gpkg` 6 polygons, aggregate `mean`/`median` per block → `soil_clay, soil_sand, soil_silt, soil_soc, soil_ph` (static, same for all dates).

**7. Add spatial features:**
- Block centroid `latitude, longitude` from `sangrur_blocks_bhuvan.gpkg` (reproject to `EPSG:4326` if `OGC:CRS84`).

**8. Final ML dataset:**
- Join all above to `forecast_features_base` + `target_7d_rainfall_mm` → `historical_ml_dataset` (one row = `forecast_date+block` with all inputs + `target_7d`).
- Keep `target_d1..d7` as diagnostics if useful, but not as features.
- Save to `data/processed/historical_ml_dataset.csv` (and `.parquet`).

---

## Notebook 04: Model Training + Comparison (COMPLETE 2026-09-10)

- Chronological split actually used: train JJAS 2016–2019 (2928 rows) / val JJAS 2021–2022 (1464) / test JJAS 2023–2025 (2196), no shuffle.
- Baselines + RF/XGB/LGBM compared on MAE/RMSE/R²/Bias (val selects, test reports).
- **Result: Raw GEFS won (test MAE 19.539); ML did not beat it — recorded honestly in `07_CURRENT_DECISIONS.md §14`.**
- Categories (LOW<10.94 / HIGH>34.66 mm, train p33/p66) + residual-ECDF probabilities done in-Notebook 04; test accuracy 0.685, macro F1 0.644.
- Artifacts saved: `models/best_model.joblib` + `data/processed/model_results/` (6 files), reload-verified.

---

## Notebook 05: Backtesting / Evaluation / Probabilistic Calibration

**15. Probability/category outputs**
- Derive `Low/Normal/High` thresholds from historical `target_7d` percentiles (`p33/p66` in Sangrur, not invented).
- Calibrate `P(Low), P(Normal), P(High)` via `predict_proba` (classifier) or regression→calibration.
- Metrics `Accuracy, F1, log loss, Brier score` on chronological `test`.

---

## Notebook 06: Live Inference / Forecast Generation

**16. Live inference**
- `D = today` → `latest CHIRPS through D`, `latest CHIRPS-GEFS issued today` (via same `build_gefs_url`/`download_file` + `zonal_stats`), `current ENSO`, `static soil/spatial` → predict `next 7–10 days` per 6 blocks using best model from `04`/`05`.

**17. Dashboard**
- `dashboard/` block-level map: 6 `MultiPolygon` blocks colored by `expected mm` or most likely category, with `forecast_date` and probabilities.

---

## Adjustments If Actual Current State Differs

- If `02` `Cells 31–35` were rerun and now have full `5472×6=32,832` rows (not demo `24`), then `03` should start from that full `forecast_features_base.csv` (still `gefs_d1` real, `gefs_d2..d7` `NaN` to be filled).
- If `2025` `CHIRPS` is still `2010–2025` (5844 dates), keep `2010–2019` train split.
- If `ENSO`/`SoilGrids` are already in `data/raw/climate|soil`, skip download and go straight to aggregation.

---

## What Not to Do Next

- Do **not** start `Cells 36+` in `02`
- Do **not** start model training in `02`
- Do **not** add `IOD, MJO, ERA5, SMAP, DEM, ECMWF S2S, deep learning` until `03–06` core is complete

---

*Next session: Python pipeline (01–06) + Saarthi full-stack integration COMPLETE and Playwright-verified. Remaining: SIH demo polish/manual browser pass, forecast refresh procedure (rerun NB05–06 → copy package → rebuild), deployment packaging.*
