# 05 — Data Leakage Rules (NON-NEGOTIABLE)

> **This file is non-negotiable.** Historical forecasting must simulate real forecasting. If this rule is violated, the model will appear to work in training but will fail live.

## Core Principle

**At forecast issue date `D`, the model may only see information available *on or before* `D`.**

```
D = forecast issue date (e.g., 2019-09-04)
Target = actual CHIRPS rainfall from D+1 through D+7 (e.g., 2019-09-05 to 2019-09-11)
```

`D+1..D+7` is **strictly after** `D` — temporal separation is a core project rule.

## What Is D, D+1, ... D+7?

- `D` = day we make the forecast (e.g., we issue on `2019-09-04` using all data up to end of `2019-09-04`)
- `D+1` = next day `2019-09-05`
- `D+2` = `2019-09-06`
- ...
- `D+7` = `2019-09-11`
- `target_7d_rainfall_mm` = sum of **observed** CHIRPS rainfall on `D+1, D+2, ..., D+7` for that block
- `target_start = D+1`, `target_end = D+7` (verified in `02` `Cell 28` and `Cell 30` `target_start = D+1`)

**Rainfall on `D` itself is NOT part of the target.** Rainfall after `D+7` is NOT part of the target.

## Valid Input (Allowed at D)

**All of these are available by end of `D` and may be used to predict `D+1..D+7`:**

| Input | Example for D=2019-09-04 | Why allowed |
|-------|--------------------------|-------------|
| **CHIRPS rainfall through D** | `rain_7d = sum CHIRPS 2019-08-29..2019-09-04` (ending at `D`), `rain_lag_1 = CHIRPS D (2019-09-04)`, `rain_lag_7 = D-6 (2019-08-29)` | All `≤D` |
| **CHIRPS-GEFS forecast issued at D** | `gefs_d1..d7` from GEFS file(s) issued on `2019-09-04` (for single_daily product, currently `gefs_d1` from file at `D`, `gefs_d2..d7` to be joined from `D+1..D+7` daily files, but all issued on `D` in a true 7-day product) | Issued on `D` |
| **ENSO available at D** | `ENSO_index` = Nino3.4 value for `2019-08` or `2019-09` monthly value available by `2019-09-04` | At `D`, not future `2019-10` |
| **Soil** | `soil_clay=20, soil_sand=40, ...` for block `Dhuri` | Static, same for all `D` |
| **Spatial** | `latitude=30.2, longitude=75.6` (block centroid) | Static |
| **Calendar** | `day_of_year=247 → sin_doy, cos_doy` for `2019-09-04` | Calendar of `D` |

**All of these end at `D` or are static.**

## Invalid Input (Leakage — Forbidden)

**Any of these would be cheating — they use future observations that would not be known at `D`:**

| Forbidden Input | Why it is leakage | Correct handling |
|-----------------|-------------------|------------------|
| `CHIRPS 2019-09-05` as a feature | That is `D+1`, part of the target | It **is** `target_d1_mm` (label), not a feature |
| `CHIRPS D+1, D+2, ... D+7` as features (e.g., `actual_D1`, `rain_next_7d`) | Future observed rainfall — would make model look perfect in training but fail live | Only `target_7d_rainfall_mm = sum D+1..D+7` as **label** |
| `rain_7d = sum D-3..D+3` (centered window) | Includes `D+1..D+3` future | Must be `sum D-6..D` (ending at `D`) |
| `ENSO 2019-10` value when `D=2019-09-04` | Future ENSO | Use `2019-08` or `2019-09` at `D` |
| `GEFS forecast issued on 2019-09-05` when `D=2019-09-04` | Future GEFS run | Use GEFS issued on `D` only |
| `target_7d_rainfall_mm` as an input feature | That is the label | Only as `y`, never as `X` |

**If you see `actual_D1`, `obs_D+1`, `future_rain`, or `target` in `X`, it is leakage.**

## Valid Target (Label)

**Only this is the target:**

- `target_7d_rainfall_mm = sum CHIRPS D+1..D+7` for `forecast_date=D, block=B`
- Optional `target_10d = sum D+1..D+10`
- Optional `target_d1_mm .. target_d7_mm` (each day `D+1` ... `D+7` observed, as diagnostics)

**Target is observed CHIRPS, never forecast.** It is the `y` that `gefs_d1..d7` etc. try to predict.

## Invalid Target (and Invalid Leakage as Target)

- Do not create target as `sum D..D+6` (includes `D` itself) — `Cell 28` explicitly verifies `target_start = D+1`.
- Do not create target as `sum D+2..D+8` (shifted) — must be `D+1..D+7`.
- Do not use `target` as a feature in the same row.

## How Recent Rainfall Features Must Be Calculated (Example)

**Correct (≤D):**
```
For D=2019-09-04, block Dhuri:
rain_1d = CHIRPS 2019-09-04
rain_3d = CHIRPS 2019-09-02 + 2019-09-03 + 2019-09-04  (ending at D)
rain_7d = CHIRPS 2019-08-29 .. 2019-09-04
rain_lag_1 = CHIRPS 2019-09-04 (=D)
rain_lag_7 = CHIRPS 2019-08-29 (=D-6)
```

**Incorrect (leakage):**
```
rain_7d = CHIRPS 2019-09-01 .. 2019-09-07  (includes D+1..D+3, future)
rain_lag_1 = CHIRPS 2019-09-05 (=D+1, future)
```

## Why Random Train/Test Splitting Is Forbidden

- CHIRPS rainfall is a **time series** with strong autocorrelation and seasonality.
- Random splitting (e.g., `train_test_split(random_state=42)`) would put `D=2019-09-10` in train and `D=2019-09-04` in test — then `test`'s `D+1..D+7` (`2019-09-05..11`) would have been seen as `train`'s `D` (`2019-09-04` is before `2019-09-10`), leaking future information via overlapping windows.
- **Must be chronological:** `train 2010–2019, val 2021–2023, test 2024–2025` (adapted from `2001–2019` due to `2010` CHIRPS start), `2020` excluded. No shuffling, no `random_state`.

## Checklist Before Any Model Training

- [ ] All `rain_*` features end at `D` (check `max date` in `rain_7d` window `≤ D`)
- [ ] `gefs_d1..d7` are from GEFS issued on `D` (not future runs)
- [ ] `ENSO_index` is for month `≤ D` (not `D+1` month)
- [ ] `target_7d` is `sum D+1..D+7` (check `target_start = D+1` in `02` `Cell 28` `VALID_TARGET_DF`)
- [ ] No `actual_D1..` columns in `X`
- [ ] `train` dates are all `< val` dates `< test` dates

**If any of these fail, the model is leaking and must be fixed before evaluation.**

## What Happens If This Rule Is Violated

The model will show artificially high `R²` / low `MAE` in training/validation (because it peeked at the future), but will **fail catastrophically live** when `D+1..D+7` is not yet known. The historical backtest would be **worthless**.

**When in doubt, ask: “Was this value known at the end of day D?” If no, it cannot be a feature — it can only be the target.**
