# 10 — OpenCode Instructions (For Future Sessions)

> **Read this before you write any code.**

## Before Modifying the Project, Read:

In this exact order:

1.  `00_MASTER_CONTEXT.md` — complete current state (read first)
2.  `01_PROJECT_GOALS.md` — what we are building and why
3.  `02_SYSTEM_ARCHITECTURE.md` — how the pipeline fits together
4.  `03_DATASETS.md` — every dataset, where it lives, what it is for
5.  `04_ML_STRATEGY.md` — target, features, models, comparison philosophy
6.  `05_DATA_LEAKAGE_RULES.md` — the non-negotiable D+1..D+7 rule
7.  `06_NOTEBOOK_STATUS.md` — what notebooks/cells are done and what remains
8.  `07_CURRENT_DECISIONS.md` — why we chose 6-block, single_daily, etc.
9.  `08_UNRESOLVED_ISSUES.md` — what is still uncertain (do not guess)
10. `09_NEXT_STEPS.md` — ordered roadmap starting from the immediate next action

**Do not start `Cells 36+`, model training, or new feature engineering until you have read 00–09.**

## Behavioral Rules for Future OpenCode Sessions

### 1. Do Not Guess — Inspect First
- Use the **Context7** or existing `read`/`glob`/`grep` tools to inspect the actual file before creating new code.
- If a `CHIRPS-GEFS` band meaning, `CHIRPS` coverage, or `sangrur_blocks_bhuvan.gpkg` schema is unclear, **read the actual GeoTIFF/CSV/GPKG** (as `01` `Cells 6–10` and `02` `Cells 11,13` did) — do not assume.
- When uncertain, **stop and surface the exact uncertainty** instead of inventing `gefs_d2..d7` values or historical targets.

### 2. Preserve Completed Work
- **Do not overwrite raw data** in `data/raw/` (never modify `Sangrur_Block_Daily_Rainfall_2010_2025.csv:38064` or `bhuvan_blocks.parquet:97298502`).
- **Do not silently change geography** — keep `6` legacy `Dhuri/Lehra/Malerkotla/Moonak/Sangrur/Sunam` from `sangrur_blocks_bhuvan.gpkg:184320`; do not switch to 8-block; keep `sangrur_blocks.shp:916` as fallback only.
- **Do not recreate `Cells 1–30` unless a genuine bug is found** (as documented in `06_NOTEBOOK_STATUS.md`). `01` `30` and `02` `31–35` are considered done for the demo `3` dates (`18` rows) / `4` dates (`24` rows).

### 3. Do Not Introduce Excluded Datasets
- **Do not add** `IOD, MJO, ERA5-Land, SMAP, DEM, ECMWF S2S, deep learning` until `01–06` + dashboard are complete and substantial time remains (see `03_DATASETS.md` exclusions and `00_MASTER_CONTEXT` §6).

### 4. Do Not Leak Future Information
- At `D`, inputs must be **`≤D`** only. **Never** use `CHIRPS D+1..D+7`, `actual_D1..`, `future ENSO`, or `target_7d` as a feature. `target_7d_rainfall_mm` is **label only** (see `05_DATA_LEAKAGE_RULES.md`).
- **Never randomly shuffle** `forecast_date`. Keep chronological split `2010–2019` train / `2021–2023` val / `2024–2025` test (adapted from `2001–2019` due to `2010` CHIRPS start), `2020` excluded.

### 5. Ask / Stop When a Critical Data Assumption Is Unresolved
- If the `CHIRPS-GEFS` issue vs target mapping (`D` vs `D+1` for `gefs_d1`) is still unclear after inspecting `HIST_TIF` `count 1` and `02` `Cell 11` single_daily docs, **stop and ask** instead of inventing `D+1..D+7`.
- If `CHIRPS` `2010–2025` vs `2001–2009` target coverage is still `5472` vs `8758`, **do not pretend** `2001–2009` targets exist — document the restriction.

### 6. Prefer Incremental, Reversible Changes
- Keep `forecast_date` as the **single source of truth** (`date(2026,9,4)` → `forecast_date_str` `2026.09.04`, `forecast_date_path` `2026/09/04`, `url`, `output_file` all derived, no hardcoded `2026/09/04` elsewhere).
- Reuse `src/data/chirps_gefs.py:2830` (`zonal_stats`, `extract_block_rainfall`, `build_tidy_forecast`) instead of duplicating raster logic in notebooks.
- Keep `gefs_d1` real, `gefs_d2..d7` as `NaN` (not zero) until `Notebook 03` joins 7 daily files — do not mark `D2..D7` `NaN` as `FAIL` in `Cell 34`.
- Preserve resumability: `SKIP_EXISTING=True`, `>50 MB + rasterio` skip, `*.tif.tmp → rename`, `preflight_availability.csv:760080` cache, `historical_gefs_processing_log.csv:205`, `ThreadPool(20)` for `HEAD 5472`.

### 7. Preserve Reproducibility
- Keep `CANDIDATE_DATES` chronological, `2020` excluded, `D+7 ≤ 2025-12-31` (via `latest_complete_D`), no random sampling.
- Keep `forecast_date+block` chronological, no shuffle.
- Do not load the entire `5472×60 MB` archive into RAM — process one `D` at a time, `tqdm`, `chunk_size 1 MB`, `stream=True`.
- Record failures in `log.csv` rather than hiding them; do not silently drop unavailable dates.

### 8. Update Documentation When Major Decisions Change
- If you change `6-block` to `8-block`, `single_daily` to multi-band, `2010–2025` to `2001–2025`, `gefs_d1` mapping, `KEEP_RAW` etc., **update `07_CURRENT_DECISIONS.md` and `00_MASTER_CONTEXT.md` together** so the next session does not need to ask again.

---

*If anything in `00_MASTER_CONTEXT.md` contradicts an older file, `00` wins. When in doubt, re-read `00` first.*
