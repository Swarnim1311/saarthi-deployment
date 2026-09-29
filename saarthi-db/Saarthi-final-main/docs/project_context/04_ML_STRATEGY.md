# 04 — ML Strategy

## Prediction Target

**Primary target (regression, then probabilistic):**

For `forecast_date = D` and `block = B` (one of 6 Sangrur blocks):

```
target_7d_rainfall_mm
=
sum of observed CHIRPS rainfall
from D+1 through D+7 inclusive
for block B
```

- Rainfall on `D` itself **must NOT** be included.
- Rainfall after `D+7` **must NOT** be included.
- `target_7d` is **observed** `CHIRPS` `D+1..D+7` (label), never an input.
- Requires all 7 days valid for that block; if even one of `D+1..D+7` missing, `target_valid=False` and **do not** replace with zero or interpolate — report and exclude that `D+block` from training.

**Secondary target (if time permits, do not delay primary):**
`target_10d = sum D+1..D+10` (same rules).

**Optional diagnostic:**
`target_d1_mm .. target_d7_mm` (each day's `CHIRPS` `D+1` ... `D+7` separately) — useful for diagnostics, but primary is 7-day sum.

**Potential optional output (for dashboard):**
Retain daily predictions `D+1, D+2, ..., D+7` (each day's `CHIRPS-GEFS` forecast vs `CHIRPS` observed) to show daily rainfall as well as 7-day total.

## Input Features (for Notebook 03, not yet built in 02)

A **shared model across all 6 blocks** (do not train 6 independent models). The model learns `history + forecast + ENSO + season + location + soil` while predicting per block.

| Feature Group | Features | Available at | Notes |
|---------------|----------|--------------|-------|
| **Historical rainfall** | `rain_1d, rain_3d, rain_7d, rain_14d, rain_30d` (sums ending at `D`) + `rain_lag_1..7` (`rain_lag_1 = D, rain_lag_2 = D-1, ... rain_lag_7 = D-6`) | `D` (all ≤`D`) | Using `CHIRPS` through `D` only. Example: `rain_7d = sum CHIRPS D-6..D`. |
| **CHIRPS-GEFS forecast** | `gefs_d1, gefs_d2, gefs_d3, gefs_d4, gefs_d5, gefs_d6, gefs_d7` + `gefs_3d_total = sum(d1:d3)`, `gefs_7d_total = sum(d1:d7)`, `gefs_10d_total` (secondary) | `D` (forecast issued on `D`) | **Current single_daily product:** `gefs_d1` is real (from file at `D`), `gefs_d2..d7` are `NaN` placeholders (not zero) until `Notebook 03` joins 7 daily files per `D` (`D+1` file → `gefs_d1`, etc.). Do NOT invent ensemble. |
| **Climate** | `ENSO_index` (e.g., Nino3.4/ONI for month of `D`) + optionally `ENSO_category` | `D` (value available at `D`, not future) | One primary numeric ENSO index first; do not use future `ENSO`. |
| **Seasonal** | `day_of_year` cyclically encoded `sin_doy = sin(2π·doy/365)`, `cos_doy = cos(2π·doy/365)` | `D` (calendar) | Avoids Dec-Jan discontinuity. |
| **Spatial** | `latitude, longitude` (block centroid `lat/lon` from `sangrur_blocks_bhuvan.gpkg`) | Static | Single shared model learns spatial differences via `lat/lon` + soil, not 6 separate models. |
| **Soil** | `soil_clay, soil_sand, soil_silt, soil_soc, soil_ph` (per-block mean/median from `SoilGrids`) | Static | Not direct atmospheric predictor; local environmental context. |

**Final ML sample schema (for `historical_ml_dataset`):**
```
forecast_date, block,
rain_1d, rain_3d, rain_7d, rain_14d, rain_30d, rain_lag_1..7,
gefs_d1..d7, gefs_3d_total, gefs_7d_total,
ENSO_index, ENSO_category (optional),
sin_doy, cos_doy,
latitude, longitude,
soil_clay, soil_sand, soil_silt, soil_soc, soil_ph,
target_7d_rainfall_mm (label)
```
Plus optional `target_10d`, `target_d1..d7` as diagnostics.

**Current `02` `forecast_features_base.csv:1294` has only:** `forecast_date, block, gefs_d1, gefs_d2..d7 (NaN), target_7d_rainfall_mm` (`24` rows demo `4` dates `2009-12-31..2010-01-03 ×6`). `Notebook 03` will add the remaining feature groups.

## Forecast Horizon

**Primary horizon:** `7` days (`D+1..D+7`). **Secondary:** `10` days if core 7-day system works without delay. The horizon is the period after `D` that the model predicts; inputs are all `≤D`.

## Baselines

We will compare several approaches to determine whether ML improves over naive:

1.  **Climatology** — Historical typical `target_7d` for that date/block (e.g., same `day_of_year` historical mean). Tests whether ML beats seasonal expectation.
2.  **Persistence** — Recent rainfall behavior (e.g., `rain_7d` as prediction). Tests whether ML beats naive "tomorrow is like today".
3.  **Raw CHIRPS-GEFS** — Use `gefs_7d_total` (or `gefs_d1..d7` sum) directly as prediction, without post-processing. Tests whether ML post-processing improves the raw numerical forecast.

These baselines are required before claiming ML value.

## Primary ML Candidates

**Good first ML baseline — Random Forest:**
- Explainable, handles non-linear, little preprocessing, strong for tabular.

**Strong tabular — XGBoost:**
- Strong tabular, handles `NaN` (`gefs_d2..d7` until `03` fills), good with `tqdm`.

**Alternative strong tabular — LightGBM:**
- Fast, strong tabular, use if install/training straightforward.

**All are tabular tree models** — appropriate for this tabular `forecast_date+block` dataset. No deep learning for core prototype (too much overhead, not needed for tabular).

## Model Comparison Philosophy

**Do NOT choose because of theoretical preference.** Choose strictly on **chronological validation/test performance** (see next section).

**For regression (expected rainfall `X mm`):**
- `MAE, RMSE, R²` (primary), `Bias` (optional)

**For probabilistic categories (`Low/Normal/High`):**
- `Accuracy, F1` (if hard categories), `log loss, Brier score` (if `P(Low),P(Normal),P(High)`)

**Final model = best on *unseen* chronological test while remaining explainable and stable.** Do not simply choose `XGBoost` because it is popular; do not claim a model is best until evaluated.

## Temporal Split (Chronological, Not Random)

**Do NOT randomly split.** Preserve time ordering.

**Intended split (per `PROJECT_CONTEXT`, but adapted to actual `2010–2025` CHIRPS):**
- **Training:** `2001–2019` → **restricted to `2010–2019`** (since `CHIRPS` targets only `2010-01-01` onward, `2001–2009` has `0` valid `target_7d` as seen in `02` `Cell 14` `Valid per year: 2001-2009 0`)
- **Validation:** `2021–2023` (tune, select model)
- **Testing:** `2024–2025` (final unseen, never used for tuning)

`2020` is **excluded** throughout (CHIRPS-GEFS gap). `CANDIDATE_DATES 8758 → VALID_DATES 5472` already excludes `2020` (`366` days) and `D+7 > 2025-12-31` (`7` days).

**Implementation:** In `Notebook 04`, `df["forecast_date"] = pd.to_datetime`; `train = df[year <=2019]`, `val = df[year 2021-2023]`, `test = df[year 2024-2025]`. Keep `forecast_date+block` chronological, no shuffle, no `train_test_split(random_state)`.

## Probabilistic Output Strategy

**Goal:** For each `D+block`, provide:

- **Expected rainfall:** `X mm` (regression `predict`)
- **Category probabilities:** `P(Low), P(Normal), P(High)` summing to 1

**Possible approach:**
- Train **classifier** for `Low/Normal/High` (labels from historical `target_7d` percentiles) and use `predict_proba`, **or**
- Train **regression** for `target_7d` and convert to categories via **calibration** (e.g., `quantile` or `temperature scaling`) — decision to be made after `Cell 34` validation and `04` model comparison.

**Category thresholds:** Must be derived from **historical Sangrur `target_7d` distribution** (e.g., `p33 / p66` percentiles of `target_7d_rainfall_mm` in `2010–2025`), **not** invented meteorological thresholds (e.g., `10 mm`). Exact percentiles to be determined after inspecting `target_7d` histogram in `05`.

**Metrics for probabilistic:** `Accuracy, F1` (hard), `log loss, Brier` (probabilistic).

## Model Selection Criteria

**Final model =**
- Best **chronological test** `R²` (or lowest `MAE/RMSE`) **and** best **probabilistic** `Brier/log loss` (if both regression + classifier)
- While remaining **explainable** (feature importances), **stable** (no overfitting `train` vs `val` gap), and **fast** enough for live inference (`D=today` → 6 blocks).

**Do NOT declare a winner until `04` has evaluated all 6 on the held-out `2024–2025` test.**

## What Has NOT Been Done Yet

- No `ENSO`, `soil`, `rain_7d`, `sin/cos`, `lat/lon` features yet (only `gefs_d1` + `target_7d` in `forecast_features_base.csv`).
- No `gefs_d2..d7` filled (currently `NaN`, `Notebook 03` will join 7 daily files per `D`).
- No model trained, no comparison, no winner declared.

**Next:** `Notebook 03` builds full `historical_ml_dataset` with all feature groups, then `04` compares models.
