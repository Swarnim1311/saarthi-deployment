# Phase 1B.3 — GEFS vs CHIRPS-GEFS fair common-period evaluation

Methodology: common complete forecast dates (42 rows = 6 blocks x leads 1-7) from frozen `raw_gefs_apcp_leads.parquet` and `historical_gefs_leads_v2.csv`, 00Z init, leads D+1..D+7, daily 00-00 UTC CHIRPS truth, 6 legacy Bhuvan blocks, frozen thresholds LOW<10.94 / NORMAL 10.94-34.66 / HIGH>34.66.

Common evaluation period: 2021-06-01 .. 2025-09-30
Common dates: 610
Common daily observations per arm: 25620
Common 7-day forecasts per arm: 3660
GEFS-only complete dates: 0; CHIRPS-GEFS-only complete dates: 488 (train-era 2016-2019 + 2018-07-15 v11 date)

## Daily metrics by lead (MAE / RMSE / R2 / bias)

| lead | GEFS MAE | C-GEFS MAE | GEFS RMSE | C-GEFS RMSE | GEFS R2 | C-GEFS R2 | GEFS bias | C-GEFS bias |
|---|---|---|---|---|---|---|---|---|
| 1 | 3.901 | 3.814 | 7.995 | 7.692 | 0.156 | 0.218 | -0.968 | -0.825 |
| 2 | 4.059 | 3.899 | 8.231 | 7.862 | 0.104 | 0.183 | -0.662 | -0.557 |
| 3 | 4.152 | 4.043 | 8.034 | 7.746 | 0.147 | 0.207 | -0.606 | -0.527 |
| 4 | 4.177 | 4.114 | 8.072 | 7.956 | 0.138 | 0.163 | -0.553 | -0.477 |
| 5 | 4.181 | 4.145 | 8.040 | 7.947 | 0.144 | 0.164 | -0.566 | -0.511 |
| 6 | 4.202 | 4.211 | 8.114 | 8.068 | 0.126 | 0.136 | -0.728 | -0.670 |
| 7 | 4.395 | 4.380 | 8.270 | 8.257 | 0.091 | 0.094 | -0.724 | -0.658 |
| overall | 4.153 | 4.087 | 8.109 | 7.935 | 0.129 | 0.166 | -0.687 | -0.604 |

## 7-day sum metrics

GEFS 7-day: MAE=20.457 RMSE=30.490 R2=0.246 bias=-4.808 acc=0.626 macroF1=0.604
CHIRPS-GEFS 7-day: MAE=19.336 RMSE=28.575 R2=0.338 bias=-4.225 acc=0.638 macroF1=0.608
Difference (GEFS minus C-GEFS): {"mae": 1.1206, "rmse": 1.9155, "r2": -0.0918, "bias": -0.5826} category {"d_accuracy": -0.012, "d_macro_f1": -0.004}

Limitations: JJAS-season only (Jun-Sep); 2020 excluded by design; common set = 2021-2022 val + 2023-2025 test overlap (no 2016-2019 train overlap since frozen GEFS arm starts 2021); single 00Z init; deterministic forecasts only (log-loss/Brier not applicable); 2018-07-15 v11-era date excluded from GEFS arm by design.

Sources:
- C:\Users\Swarnim\Desktop\ML projects\saarthi-2\data\processed\phase1b\raw_gefs_apcp_leads.parquet
- C:\Users\Swarnim\Desktop\ML projects\saarthi-2\data\processed\historical\historical_gefs_leads_v2.csv
- C:\Users\Swarnim\Desktop\ML projects\saarthi-2\data\raw\rainfall\Sangrur_Block_Daily_Rainfall_2010_2025.csv
Reproducibility: `python src/evaluation/phase1b_3_common_evaluation.py` (read-only inputs; writes only `phase1b_3_*` artifacts).
Conclusion (factual): see `differences_gefs_minus_chirpsgefs` in `data/processed/phase1b/phase1b_3_common_report.json` for the empirical result; carry-forward choice must cite those numbers.
