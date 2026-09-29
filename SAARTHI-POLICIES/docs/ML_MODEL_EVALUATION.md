# Saarthi — ML Models, Data & Evaluation

> Quick reference for SIH presentation, viva, and future development.
> Every metric below was verified against the actual project artifacts
> (notebooks, `models/*_metadata.json`, `data/processed/*predictions.csv`,
> `data/processed/model_results/*`). Nothing is invented.
> Date of writing: 2026-09-11.

## 1. Project ML Overview

Saarthi uses three data-driven components plus one climate indicator:

| # | Component | Type | Status |
|---|-----------|------|--------|
| 1 | Hyperlocal rainfall forecasting (Raw CHIRPS-GEFS) | Actual prediction model (numerical forecast, used live) | **Final / live** |
| 2 | MJO prediction (CNN-LSTM on RMM1/RMM2) | Actual prediction model (beats its baseline) | **Final / live as context** |
| 3 | IOD prediction (LightGBM on monthly DMI) | Experimental prediction model (does NOT beat its baseline) | **Final / live as context, honestly labeled** |
| 4 | ENSO (Niño-3.4 index) | Climate-context indicator (no ML trained) | **Final / live as context** |

Baselines tested but **not selected**: Climatology, Persistence, Random Forest,
XGBoost (rainfall), train-mean (MJO/IOD).

**Architecture in one line:** Raw CHIRPS-GEFS → hyperlocal rainfall forecast;
MJO + IOD + ENSO → large-scale climate context. Decision: **USE CLIMATE CONTEXT ONLY**
(no validated experiment showed climate signals improving rainfall, so none is claimed).

## 2. Data Used

| Component | Dataset | Source / Provenance | Period | Frequency | Purpose |
|---|---|---|---|---|---|
| Rainfall truth | CHIRPS v3 block aggregates (`Sangrur_Block_Daily_Rainfall_2010_2025.csv`, 35,064 rows) | Project CSV (prior pipeline) | 2010-01-01 → 2025-12-31 | Daily per block | Target `sum(D+1..D+7)` + history features |
| Rainfall forecast | CHIRPS-GEFS v3 daily (`c3g_*.tif`, ~65 MB each) | CHC UCSB archive streaming | 2016-06 → 2025-09 used (JJAS) | Daily, 0.05° | Main predictor `gefs_d1..d7` |
| MJO | `MJO_RMM_cleaned_core.csv` (18,802 rows; RMM1, RMM2, phase, amplitude) | **PROVENANCE NOT VERIFIED** (no source in repo) | 1974-06-01 → 2026-09-07, gap 1978-03-16 → 1979-01-01 (never bridged) | Daily | MJO model input/target |
| IOD | `dmi.had.long.csv` (1,877 valid rows, DMI only) | **Verified from file header:** HadISST1.1, PSL/NOAA monthly timeseries page | 1870-01 → 2026-05 (7 trailing -9999 dropped) | Monthly | IOD model input/target |
| ENSO | `ersst5.nino.mth.91-20.ascii` (Niño-3.4 anomaly) | NOAA CPC ERSSTv5 (local file) | Monthly, latest Jun 2026: **+1.44°C → El Niño** | Monthly | Context + IOD feature |
| Soil | SoilGrids 0–5 cm means (clay/sand/silt/SOC/pH per block) | SoilGrids rasters, block-averaged | Static | Static | Feature in rainfall ML comparison only (not in winner) |
| Geography | Bhuvan 6-block Sangrur GPKG | Bhuvan/ISRO legacy blocks | Static | Static | Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam |

## 3. Rainfall Forecasting

### Target
`target_7d_rainfall_mm` = observed CHIRPS sum over D+1..D+7 per block.

### Forecast horizon
7 days. Geography: the six Sangrur blocks above.

### Models evaluated (chronological splits; `model_comparison.csv`)

| Model | Validation MAE | Test MAE | Test R² | Selected? |
|---|---:|---:|---:|---|
| Raw CHIRPS-GEFS | 19.03 | **19.54** | 0.38 | **YES — final** |
| Random Forest | 22.22 | 20.18 | 0.38 | No |
| XGBoost | 21.64 | 21.08 | 0.36 | No |
| LightGBM | 23.09 | 21.34 | 0.33 | No |
| Climatology | 24.49 | 24.64 | 0.10 | No (baseline) |
| Persistence | 26.92 | 30.54 | -0.40 | No (baseline) |

**FINAL RAINFALL APPROACH: Raw CHIRPS-GEFS** — lowest validation and test MAE,
~21% better than climatology on test.

### Rainfall categories (train p33/p66 of target, `category_thresholds.json`)
- LOW: < **10.94** mm · NORMAL: 10.94–34.66 mm · HIGH: > **34.66** mm
- Test: accuracy **0.685**, macro F1 **0.644**, log loss 2.078, Brier 1.087.
- Probabilities: residual-ECDF calibration from the winner's train residuals (train-only).

## 4. MJO Model (`notebooks/08_MJO_CNN_LSTM.ipynb`)

- **Dataset:** 18,802 daily rows, 1974-06-01 → 2026-09-07; 1 gap (1978-03-16 → 1979-01-01, sequences never cross it); provenance NOT VERIFIED.
- **Inputs:** 84-day lookback of RMM1/RMM2. **Target:** 7-day-ahead RMM1/RMM2.
- **Algorithm:** Conv1D(32, k=3, causal, ReLU) → LSTM(32, dropout 0.2) → Dense(16, ReLU) → Dense(2).
  9,106 params; Adam lr 1e-3, MSE loss, batch 128, early stopping (patience 6, best weights restored;
  stopped at epoch 17, ~0.9 min CPU, seed 42, TF 2.21 CPU-only).
- **Baseline:** persistence (future = latest observed). Splits chronological:
  train ≤2005 (11,067) / val 2006–15 (3,652) / test ≥2016 (3,903). Scaler fit on train only.

| Metric (test) | Persistence | CNN-LSTM |
|---|---:|---:|
| RMM1 MAE | 0.884 | **0.627** |
| RMM1 RMSE | 1.133 | **0.789** |
| RMM1 correlation | 0.411 | **0.657** |
| RMM2 MAE | 0.849 | **0.560** |
| Amplitude MAE | 0.569 | 0.574 |
| Phase accuracy | 20.7% | **34.9%** |
| Phase macro F1 | 0.206 | **0.347** |

Phase/amplitude are **derived from predicted RMM1/RMM2** (convention matches supplied phases 100%).
Leakage checks passed (gap-safe geometry, disjoint chronological splits, train-only scaler).
Live: observed 2026-09-07 (P7) → forecast 2026-09-14 (P8, amp 0.67), flagged stale.

## 5. IOD Model (`notebooks/07_IOD_LightGBM.ipynb`)

- **Dataset:** monthly HadISST1.1 DMI, 1,877 rows, 1870-01 → 2026-05; provenance verified from header.
- **Target:** next-month DMI, `DMI(M+1)`; phase derived via ±0.4°C.
- **Features (~25, all causal, ≤M):** DMI lags 1–12, rolling means 3/6/12, rolling std-6,
  12-month trend, sin/cos(month), ENSO anomaly (previous-month as-of) + lag-3,
  MJO monthly means (RMM1/RMM2/amplitude; NaN-tolerant pre-1974).
- **Algorithm:** LightGBM regressor (lr 0.05, 15 leaves, min-child 20, feat/bag 0.8,
  ≤500 rounds, early stopping 50, seed 42; best iteration 26). XGBoost was also evaluated
  (val MAE 0.1654 vs LightGBM 0.1639) — LightGBM selected on validation.
- **Baseline:** persistence. Splits: train ≤2000 (309) / val 2001–12 (144) / test ≥2013 (160).

| Metric (test) | Persistence | LightGBM |
|---|---:|---:|
| DMI MAE | **0.157** | 0.194 |
| DMI RMSE | 0.200 | 0.251 |
| DMI correlation | 0.83 | 0.81 |
| R² | 0.66 | 0.47 |
| Phase accuracy | **82.5%** | 74.4% |
| Phase macro F1 | 0.723 | 0.417 |

**Honest conclusion (also stored in `models/iod_metadata.json`):**
"LightGBM provides an ML-based IOD forecast, but persistence remains the stronger
benchmark on the held-out test set. Therefore IOD is used as climate context rather
than as a demonstrated improvement to rainfall prediction."
Live: data 2026-05 → forecast 2026-06 DMI **+0.033 Neutral**, flagged stale.

## 6. ENSO

- Real Niño-3.4 anomaly from local ERSSTv5 file; latest **+1.44°C (Jun 2026) → El Niño**
  (±0.5°C convention, documented as contextual).
- Used as large-scale climate context and as a causal input feature to the IOD model.
- **Not** a trained model and **not** a rainfall predictor.

## 7. Climate Context Integration

```
Raw CHIRPS-GEFS  →  hyperlocal 7-day rainfall forecast (6 blocks)
MJO CNN-LSTM     →  RMM/phase climate signal (beats persistence)
IOD LightGBM     →  next-month DMI forecast (below persistence, labeled experimental)
ENSO index       →  background climate state
MJO + IOD + ENSO →  large-scale climate context (website card + /api/climate-context)
```

Current decision: **USE CLIMATE CONTEXT ONLY** — no validated experiment showed climate
signals improving rainfall, so no such claim is made anywhere (code, UI, or docs).

## 8. Data Leakage & Validation Strategy

- Chronological train/val/test splits everywhere (rainfall JJAS 2016–19/2021–22/2023–25;
  MJO ≤2005/2006–15/≥2016; IOD ≤2000/2001–12/≥2013); never random.
- Training-only preprocessing (MJO StandardScaler; rainfall residual-ECDF; IOD needs no scaler).
- Gap-safe MJO sequences (0 crossings of the 1978–79 gap, asserted); causal IOD features
  (inputs ≤M, target M+1, asserted); untouched test sets; model selection on validation only.
- Status: all notebook leakage audits PASS.

## 9. Final Model Inventory

| Component | Final approach | Target | Horizon | Status |
|---|---|---|---|---|
| Rainfall | Raw CHIRPS-GEFS | 7-day rainfall | 7 days | Final (live) |
| MJO | CNN-LSTM | RMM1/RMM2 | 7 days | Final (live context) |
| IOD | LightGBM | DMI | 1 month | Final (live context, experimental) |
| ENSO | Climate index | ENSO state | Context | Final (live context) |

## 10. Saved Model/Data Artifacts

Rainfall: `models/best_model.joblib`, `models/inference_config.json`,
`models/probability_calibration.npz`, `data/processed/model_results/`
(`model_comparison.csv`, `test_predictions.csv`, `category_metrics.csv`,
`feature_importance.csv`, `category_thresholds.json`, `model_metadata.json`),
`data/processed/live_forecast/`, `data/processed/application/`.

MJO: `models/mjo_cnn_lstm.keras`, `models/mjo_scaler.pkl`,
`models/mjo_metadata.json`, `data/processed/mjo_predictions.csv` (3,903×9).

IOD: `models/iod_best_model.joblib`, `models/iod_metadata.json`,
`data/processed/iod_predictions.csv` (613×6).

Climate context: `data/processed/climate_context/`
(`climate_context_summary.json`, `mjo_current_context.json`, `iod_status.json`;
mirrored under `website/Saarthi/src/main/resources/climate/`).

Notebooks: `01–06` (rainfall, frozen), `07_IOD_LightGBM.ipynb`, `08_MJO_CNN_LSTM.ipynb`,
`IOD_nb.ipynb` (teammate reference, unmodified).

## 11. Final Evaluation Summary

| System | Algorithm | Main metric | Result |
|---|---|---|---|
| Rainfall | Raw CHIRPS-GEFS | Test MAE | 19.54 mm (best; climatology 24.64) |
| MJO | CNN-LSTM | Test RMM1 MAE | 0.627 vs 0.884 persistence |
| IOD | LightGBM | Test DMI MAE | 0.194 vs 0.157 persistence |
| ENSO | Index (no model) | Jun 2026 anomaly | +1.44°C El Niño |

- Raw CHIRPS-GEFS is the final rainfall forecasting approach.
- MJO CNN-LSTM outperformed persistence on the tested RMM task.
- IOD LightGBM provides an ML-based DMI forecast but did not outperform persistence.
- ENSO, MJO and IOD are used as large-scale climate context.
- No unsupported claim of rainfall improvement from climate context is made.

## 12. Important Limitations

- **Freshness (labeled stale, not live):** MJO input ends 2026-09-07; ENSO Jun 2026; IOD May 2026.
- **IOD skill** is below persistence on the held-out test (reported honestly).
- **MJO provenance** could not be verified from the repo (likely BOM RMM — not claimed).
- **No in-browser console check** was possible in this environment (APIs verified 13/13 HTTP 200 instead).
- IOD test set is small (160 months) and Positive-IOD months are few (32) — phase metrics are noisy.
