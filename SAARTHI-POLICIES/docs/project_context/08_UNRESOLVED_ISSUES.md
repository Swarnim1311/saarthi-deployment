# 08 — Unresolved Issues

> **Do not "solve" these by guessing in documentation. Surface the exact uncertainty so the next session inspects the source and decides.**

---

## 1. CHIRPS Block CSV Coverage is 2010–2025, While CHIRPS-GEFS Begins 2001

**What we know:**
- `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` covers `2010-01-01` to `2025-12-31` (`5844` unique dates × `6` blocks = `35064` rows), continuous, `0` missing dates, `0` missing `rainfall_mm`.
- `Cell 14` `VALIDATION_DF` therefore finds `CANDIDATE_DATES 8758 (2001-01-01 to 2025-12-24, no 2020)` → `VALID_DATES 5472` where `D+7 ≤ 2025-12-31` **and** `D+1 ≥ 2010-01-01` → `invalid 3286` are exactly `2001–2009` where `D+7 < 2010-01-01`.
- Intended training split `2001–2019` (per `PROJECT_CONTEXT`) would therefore be **truncated to `2010–2019`** for any model that requires `target_7d`.

**Unresolved decision:**
- **Option A:** Acquire/aggregate earlier `2001–2009` CHIRPS observations (e.g., via `CHIRPS/v3.0` `pentad` or `daily` rasters clipped to `sangrur_blocks_bhuvan.gpkg` to extend `Sangrur_Block_Daily_Rainfall` back to `2001`) — if time permits and the aggregation can be done correctly.
- **Option B:** Restrict the historical prototype to the **actually available `2010–2025` target coverage** and document that `2001–2009` is intentionally excluded due to missing observed targets. Then adjust `Notebook 04` split to `2010–2019` train / `2021–2023` val / `2024–2025` test.

**What not to do:** Do **not** pretend `2001–2009` targets exist, do **not** fill `target_7d` with zero or interpolation for those years, do **not** silently make up historical targets. The next session must explicitly decide A or B after inspecting whether `2001–2009` CHIRPS can be aggregated in time.

---

## 2. Exact CHIRPS-GEFS Issue-Date → Target-Date Mapping Must Be Verified

**What we know:**
- `01` `Cells 6–10` and `02` `Cell 11` inspected `c3g_2026.09.04.tif:64908286` and `c3g_2019.09.04.tif:67698552` → both `7200×2400×1` `float32` `1` band `descriptions (None,)` `nodata None` sentinel `-9999`, `global tags` only `TIFFTAG_DOCUMENTNAME:/home/CHIRPS-GEFS/v3/.../c3g_YYYY.MM.DD.tif`, **no lead metadata**.
- **Interpretation so far:** `Band 1` = **daily total precipitation for that date** (`v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` is single-day total). Therefore `gefs_d1` is real, `gefs_d2..d7` are `NaN` in `forecast_features_base.csv:1294` (24 rows demo `4` dates).

**Unresolved:**
- **Is `gefs_d1` for issue date `D` the file at `D` (total for `D` itself) or the file at `D+1` (forecast for `D+1`)?** `Cell 33` currently sets `gefs_d1` from file at `D` and `forecast_target_date = D` (single_daily file at `D` is total for `D`). For a **7-day horizon `D+1..D+7`**, the correct mapping might be `gefs_d1 = file at D+1`, `gefs_d2 = file at D+2`, ..., `gefs_d7 = file at D+7` (i.e., 7 daily files per `D`). The archive folder structure `daily/global/YYYY/MM/DD/` suggests one file per calendar date, so `D+1..D+7` would be 7 files per `D`.

**What to do next:**
- In `Notebook 03`, before filling `gefs_d2..d7`, **explicitly verify** by comparing `2019-09-04` `gefs_d1` file date vs `CHIRPS` target `2019-09-05..11`: does the file at `2019-09-04` correspond to `2019-09-04` or `2019-09-05`? Check CHC documentation for `v3/daily` vs `v3/05_day` etc., and optionally compare `05_day` product if needed.
- **Do not** assume `Band 1 = D+1` or invent `D+1..D+7` values. `Cell 34` correctly does not mark `gefs_d2..d7` all-`NaN` as invalid — it is **expected** until `03` joins the 7 daily files.

---

## 3. Current Single-Daily File Only Provides the Immediately Represented Daily Forecast

**What we know:**
- Current demo `forecast_features_base.csv:1294` has `gefs_d1` `6.4–12.3` (real) and `gefs_d2..d7` all `NaN` (24 rows, `4` dates ×6).
- `Cell 15` estimated `5472` files `~346 GB` for **one file per `D`** (single_daily), and `38,304` files `~2.4 TB` for **7 files per `D`** (to get `gefs_d1..d7`).

**Unresolved:**
- **Full 7-day acquisition:** For each valid `D` (`5472`), do we need to download **7 daily files** (`D+1` file, `D+2` file, ..., `D+7` file) to populate `gefs_d1..d7`? The `Cell 11` documentation says **yes** for `single_daily` product, but this has not yet been implemented in `02` `Cells 21–25` (which currently do **1 file per `D`** → `6` rows `lead 1` only, `18` rows demo `3` dates).

**What to do next:**
- In `Notebook 03`, implement acquisition/join of the remaining 6 daily files per `D` (if the `D+1..D+7` mapping is confirmed as above) and populate `gefs_d2..d7` (currently `NaN`). Keep `gefs_d2..d7` as `NaN` (not zero) until then.

---

## 4. Ensemble Uncertainty Is Optional and Should Not Be Forced

**What we know:**
- The `v3/daily` product tested is `1` band `float32` daily total, not obviously an ensemble. The `CHC` product also has ensemble means, but the `daily` GeoTIFF does not expose ensemble spread in its tags.

**Unresolved:**
- Should we add ensemble spread features? Only if the accessible product (`NetCDF` ensemble or separate `spread` GeoTIFF) cleanly supports it without complicating the pipeline.

**What not to do:** Do **not** invent ensemble features (e.g., `gefs_std`, `gefs_p10/p90`) if the `daily` GeoTIFF does not contain them. `Notebook 03` should inspect whether the `CHC` NetCDF or `CHIRPS-GEFS` ensemble product is available before adding.

---

## 5. Full 5472 Bulk Not Yet Run Interactively

**What we know:**
- `02` `Cell 18` `preflight_availability.csv:760080` (`5472` `HEAD` cache, `20-thread` `~7 mins` first, `<1s` after) is ready.
- `02` `Cell 25` demo is `3` dates `2010-07-15, 2019-09-04, 2025-08-01` → `18` rows (`3×6`) in `historical_gefs_block_forecasts.csv:1874` (was `10` → `1.5 hours` at `90 KB/s`, now `3` → `22 mins` first, `<5s` after, to fit `90 KB/s` and demo).
- `02` `Cell 30` `historical_forecast_targets.csv:4210` is demo `4` dates `2009-12-31..2010-01-03 ×6 =24` rows (not `5472×6=32,832`).
- `02` `Cells 31–35` `forecast_features_base.csv:1294` is `24` rows demo (not `32,832`).

**Unresolved:**
- Full `5472` bulk (`32,832` block samples, `346 GB` raw for single file per `D`, `2.4 TB` for 7×) has **not** been run interactively — it is a one-time background job with `KEEP_RAW_GEFS_FILES=False` (delete after extract) and `ThreadPool` to stay `<1 GB` disk, not `346 GB`. When to run it (now vs after `03` feature engineering is proven on demo) is a scheduling decision.

**What not to do:** Do **not** attempt `5472×60 MB` interactive `Run All` without `KEEP_RAW=False` and `ThreadPool` — it will take `~6 hours` at `90 KB/s` and fill `346 GB`.

---

## 6. SoilGrids + ENSO Acquired and Merged (resolved 2026-09-09)

**What we know:**
- `data/raw/climate/` has `ersst5.nino.mth.91-20.ascii:67087` (NOAA CPC ERSSTv5, monthly 1950-01→2026-06); in `final_ml_dataset` as `enso_value` (previous-month as-of, 0 violations).
- `data/raw/soil/` has 5 user-provided SoilGrids 0-5cm mean tifs (Clay/Sand/Silt/OrganicCarbon/pH); block means extracted via polygon masks in `03` Cells 16–18 (texture closure 968–979, pH 7.66–7.87) and present in `final_ml_dataset` as `soil_*` (0 missing, static per block).

**Remaining:**
- None for Notebook 03 (COMPLETE). Full 5472-date bulk rebuild remains a future background job.

---

*These issues are intentionally left unresolved here so the next session inspects the source (CHC docs, CHIRPS rasters, actual GeoTIFFs) and decides, rather than guessing in documentation.*
