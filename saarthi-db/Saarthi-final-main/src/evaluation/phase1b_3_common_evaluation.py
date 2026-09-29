"""
Phase 1B.3 — Fair common-period GEFS vs CHIRPS-GEFS evaluation (2026-09-18).

Compares the FROZEN raw GEFS APCP arm against the FROZEN CHIRPS-GEFS baseline
on genuinely COMMON forecast dates, same protocol, same CHIRPS truth.

Frozen (never recalculated here):
  T33 = 10.939151985384193, T66 = 34.66167447031182 (models/inference_config.json)
  splits: val JJAS 2021-22 / test JJAS 2023-25, 2020 excluded
  00Z init, leads D+1..D+7, daily 00-00 UTC target, 6 legacy Bhuvan blocks
  thresholds LOW < 10.94 / NORMAL 10.94-34.66 / HIGH > 34.66 (7-day sums)

Read-only inputs (never modified):
  data/processed/phase1b/raw_gefs_apcp_leads.parquet  (frozen, 25620x14, 610 dates)
  data/processed/historical/historical_gefs_leads_v2.csv (CHIRPS-GEFS arm)
  data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv (truth)

New outputs only (phase1b_3_* prefix; frozen files untouched):
  data/processed/phase1b/phase1b_3_common_report.json
  data/processed/phase1b/phase1b_3_common_metrics_daily_by_lead.csv
  data/processed/phase1b/phase1b_3_common_metrics_7day.csv
  reports/phase1b/PHASE1B_3_GEFS_VS_CHIRPSGEFS_REPORT.md

Probabilistic metrics: both arms are deterministic forecasts, no per-source
residual-ECDF calibration exists for raw GEFS APCP, so log-loss / Brier are
recorded as not-applicable (same rationale as phase1b_forecast_comparison.py).

Usage:
  python src/evaluation/phase1b_3_common_evaluation.py
"""
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import (accuracy_score, confusion_matrix, f1_score,
                             mean_absolute_error, mean_squared_error, r2_score)

PROJECT = Path(__file__).resolve().parents[2]
PHASE1B_DIR = PROJECT / "data" / "processed" / "phase1b"
REPORTS_DIR = PROJECT / "reports" / "phase1b"
GEFS_PARQUET = PHASE1B_DIR / "raw_gefs_apcp_leads.parquet"
V2_LEADS = PROJECT / "data" / "processed" / "historical" / "historical_gefs_leads_v2.csv"
CHIRPS_CSV = PROJECT / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"

T33 = 10.939151985384193
T66 = 34.66167447031182
BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]


def period_of(year: int) -> str:
    if 2021 <= year <= 2022:
        return "2021-2022"
    if 2023 <= year <= 2025:
        return "2023-2025"
    return "other"


def load_arm(path: Path, name: str) -> pd.DataFrame:
    if path.suffix == ".parquet":
        df = pd.read_parquet(path)
    else:
        df = pd.read_csv(path)
    need = {"forecast_date", "lead_day", "block", "forecast_target_date", "forecast_rainfall_mm"}
    missing = need - set(df.columns)
    assert not missing, f"{name}: missing columns {missing}"
    df = df.copy()
    df["forecast_date"] = pd.to_datetime(df["forecast_date"])
    df["forecast_target_date"] = pd.to_datetime(df["forecast_target_date"])
    df["lead_day"] = df["lead_day"].astype(int)
    df["forecast_rainfall_mm"] = pd.to_numeric(df["forecast_rainfall_mm"], errors="coerce")
    assert df[["forecast_date", "block", "lead_day"]].duplicated().sum() == 0, f"{name}: duplicates"
    assert set(df["lead_day"].unique()) <= set(range(1, 8)), f"{name}: bad leads {sorted(df['lead_day'].unique())}"
    assert df["forecast_rainfall_mm"].notna().all(), f"{name}: non-numeric rainfall"
    assert (df["forecast_rainfall_mm"] >= -1e-6).all(), f"{name}: negative rainfall"
    delta = (df["forecast_target_date"] - df["forecast_date"]).dt.days
    assert (delta == df["lead_day"]).all(), f"{name}: target != issue+lead"
    df["period"] = df["forecast_date"].dt.year.map(period_of)
    df["arm"] = name
    return df


def load_truth() -> pd.DataFrame:
    c = pd.read_csv(CHIRPS_CSV, usecols=["block", "date", "rainfall_mm"])
    c["block"] = c["block"].astype(str).str.strip()
    c.loc[c["block"].str.lower() == "lehragaga", "block"] = "Lehra"
    c["date"] = pd.to_datetime(c["date"])
    c["rainfall_mm"] = pd.to_numeric(c["rainfall_mm"], errors="coerce")
    assert c["rainfall_mm"].notna().all() and (c["rainfall_mm"] >= 0).all()
    return c


def cont_metrics(y: np.ndarray, p: np.ndarray) -> dict:
    y = np.asarray(y, float)
    p = np.asarray(p, float)
    return {
        "n": int(len(y)),
        "mae": float(mean_absolute_error(y, p)),
        "rmse": float(np.sqrt(mean_squared_error(y, p))),
        "r2": float(r2_score(y, p)) if len(np.unique(y)) > 1 else float("nan"),
        "bias": float(np.mean(p - y)),
    }


def categorize(s: pd.Series) -> pd.Series:
    return pd.cut(s, bins=[-np.inf, T33, T66, np.inf], labels=["LOW", "NORMAL", "HIGH"])


def score_daily(m: pd.DataFrame) -> dict:
    out: dict = {"overall": cont_metrics(m["rainfall_mm"], m["forecast_rainfall_mm"]),
                 "by_lead": {}, "by_block": {}, "by_period": {}}
    for col, key in [("lead_day", "by_lead"), ("block", "by_block"), ("period", "by_period")]:
        for val, g in m.groupby(col, observed=True):
            out[key][str(val)] = cont_metrics(g["rainfall_mm"], g["forecast_rainfall_mm"])
    return out


def score_7day(m: pd.DataFrame) -> dict:
    g = m.groupby(["forecast_date", "block", "period"], observed=True)
    s = g.agg(pred_7d=("forecast_rainfall_mm", "sum"),
              true_7d=("rainfall_mm", "sum"),
              n_leads=("lead_day", "count")).reset_index()
    incomplete = int((s["n_leads"] != 7).sum())
    s = s[s["n_leads"] == 7].copy()
    out: dict = {"n_forecasts": len(s), "incomplete_horizons_excluded": incomplete,
                 "overall": cont_metrics(s["true_7d"], s["pred_7d"]),
                 "by_block": {}, "by_period": {}}
    for col, key in [("block", "by_block"), ("period", "by_period")]:
        for val, gg in s.groupby(col, observed=True):
            out[key][str(val)] = cont_metrics(gg["true_7d"], gg["pred_7d"])
    s["pred_cat"] = categorize(s["pred_7d"])
    s["true_cat"] = categorize(s["true_7d"])
    out["category"] = {
        "accuracy": float(accuracy_score(s["true_cat"], s["pred_cat"])),
        "macro_f1": float(f1_score(s["true_cat"], s["pred_cat"], average="macro")),
        "confusion": confusion_matrix(s["true_cat"], s["pred_cat"],
                                      labels=["LOW", "NORMAL", "HIGH"]).tolist(),
        "confusion_labels": ["LOW", "NORMAL", "HIGH"],
    }
    return out


def main() -> None:
    assert GEFS_PARQUET.exists(), f"missing frozen {GEFS_PARQUET}"
    assert V2_LEADS.exists(), f"missing {V2_LEADS}"
    assert CHIRPS_CSV.exists(), f"missing {CHIRPS_CSV}"

    gefs = load_arm(GEFS_PARQUET, "raw-gefs-apcp")
    cgef = load_arm(V2_LEADS, "chirps-gefs")
    truth = load_truth()

    # Complete-date sets per arm (42 rows = 6 blocks x 7 leads).
    def complete_dates(df: pd.DataFrame) -> list:
        counts = df.groupby("forecast_date").size()
        return sorted(counts[counts == 42].index)

    gefs_complete = set(complete_dates(gefs))
    cgef_complete = set(complete_dates(cgef))
    common = sorted(gefs_complete & cgef_complete)
    assert len(common) > 0, "no common complete dates"
    gefs_only = sorted(gefs_complete - cgef_complete)
    cgef_only_count = len(cgef_complete - gefs_complete)

    gefs_c = gefs[gefs["forecast_date"].isin(common)].copy()
    cgef_c = cgef[cgef["forecast_date"].isin(common)].copy()

    mg = gefs_c.merge(truth, left_on=["block", "forecast_target_date"],
                      right_on=["block", "date"], how="left", indicator=True)
    mc = cgef_c.merge(truth, left_on=["block", "forecast_target_date"],
                      right_on=["block", "date"], how="left", indicator=True)
    assert (mg["_merge"] == "both").all(), "GEFS rows missing CHIRPS truth"
    assert (mc["_merge"] == "both").all(), "CHIRPS-GEFS rows missing CHIRPS truth"
    mg = mg[mg["_merge"] == "both"].copy()
    mc = mc[mc["_merge"] == "both"].copy()

    rep = {
        "protocol": "Phase1B.3 fair common-period: 00Z init, leads D+1..D+7, "
                    "daily 00-00 UTC CHIRPS target, 6 legacy Bhuvan blocks, "
                    "frozen thresholds T33/T66, complete 42-row dates only",
        "thresholds_frozen": {"T33": T33, "T66": T66},
        "sources": {"gefs": str(GEFS_PARQUET), "chirps_gefs": str(V2_LEADS),
                    "truth": str(CHIRPS_CSV)},
        "common_period": [pd.Timestamp(common[0]).strftime("%Y-%m-%d"),
                          pd.Timestamp(common[-1]).strftime("%Y-%m-%d")],
        "n_common_dates": len(common),
        "n_common_daily_rows_per_arm": len(mg),
        "n_common_7day_forecasts_per_arm": int(len(mg) / 7),
        "gefs_complete_dates": len(gefs_complete),
        "chirps_gefs_complete_dates": len(cgef_complete),
        "gefs_only_complete_dates": gefs_only,
        "chirps_gefs_only_complete_count": cgef_only_count,
        "blocks": sorted(mg["block"].unique().tolist()),
        "probabilistic_note": "log-loss/Brier not-applicable: both arms deterministic, "
                              "no per-source residual-ECDF calibration for raw GEFS APCP",
    }

    arms = {}
    for name, m in [("raw-gefs-apcp", mg), ("chirps-gefs", mc)]:
        arms[name] = {"status": "scored", "rows": len(m),
                      "n_issues": int(m["forecast_date"].nunique()),
                      "daily": score_daily(m), "seven_day": score_7day(m)}
    rep["arms"] = arms

    # Differences (GEFS minus CHIRPS-GEFS): negative dMAE favours GEFS.
    diff = {}
    for scope in ["overall"] + [f"lead_{i}" for i in range(1, 8)]:
        if scope == "overall":
            g, c = arms["raw-gefs-apcp"]["daily"]["overall"], arms["chirps-gefs"]["daily"]["overall"]
        else:
            lead = scope.split("_")[1]
            g = arms["raw-gefs-apcp"]["daily"]["by_lead"][lead]
            c = arms["chirps-gefs"]["daily"]["by_lead"][lead]
        diff[scope] = {k: round(g[k] - c[k], 4) for k in ["mae", "rmse", "r2", "bias"]}
    g7, c7 = arms["raw-gefs-apcp"]["seven_day"], arms["chirps-gefs"]["seven_day"]
    diff["seven_day_overall"] = {k: round(g7["overall"][k] - c7["overall"][k], 4)
                                 for k in ["mae", "rmse", "r2", "bias"]}
    diff["seven_day_category"] = {
        "d_accuracy": round(g7["category"]["accuracy"] - c7["category"]["accuracy"], 4),
        "d_macro_f1": round(g7["category"]["macro_f1"] - c7["category"]["macro_f1"], 4)}
    rep["differences_gefs_minus_chirpsgefs"] = diff

    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)

    (PHASE1B_DIR / "phase1b_3_common_report.json").write_text(json.dumps(rep, indent=2))

    rows = []
    for arm in ["raw-gefs-apcp", "chirps-gefs"]:
        d = arms[arm]["daily"]
        rows.append({"arm": arm, "group": "overall", **d["overall"]})
        for lead in sorted(d["by_lead"], key=int):
            rows.append({"arm": arm, "group": f"lead_{lead}", **d["by_lead"][lead]})
    pd.DataFrame(rows).to_csv(PHASE1B_DIR / "phase1b_3_common_metrics_daily_by_lead.csv", index=False)

    rows7 = []
    for arm in ["raw-gefs-apcp", "chirps-gefs"]:
        s = arms[arm]["seven_day"]
        rows7.append({"arm": arm, "scope": "overall", **s["overall"],
                      "accuracy": s["category"]["accuracy"], "macro_f1": s["category"]["macro_f1"]})
        for per in ["by_block", "by_period"]:
            for k, v in s[per].items():
                rows7.append({"arm": arm, "scope": f"{per}:{k}", **v,
                              "accuracy": np.nan, "macro_f1": np.nan})
    pd.DataFrame(rows7).to_csv(PHASE1B_DIR / "phase1b_3_common_metrics_7day.csv", index=False)

    # Markdown report.
    L = []
    L.append("# Phase 1B.3 — GEFS vs CHIRPS-GEFS fair common-period evaluation\n")
    L.append("Methodology: common complete forecast dates (42 rows = 6 blocks x leads 1-7) "
             "from frozen `raw_gefs_apcp_leads.parquet` and `historical_gefs_leads_v2.csv`, "
             "00Z init, leads D+1..D+7, daily 00-00 UTC CHIRPS truth, 6 legacy Bhuvan blocks, "
             "frozen thresholds LOW<10.94 / NORMAL 10.94-34.66 / HIGH>34.66.\n")
    L.append(f"Common evaluation period: {rep['common_period'][0]} .. {rep['common_period'][1]}")
    L.append(f"Common dates: {rep['n_common_dates']}")
    L.append(f"Common daily observations per arm: {rep['n_common_daily_rows_per_arm']}")
    L.append(f"Common 7-day forecasts per arm: {rep['n_common_7day_forecasts_per_arm']}")
    L.append(f"GEFS-only complete dates: {len(gefs_only)}; "
             f"CHIRPS-GEFS-only complete dates: {cgef_only_count} (train-era 2016-2019 + 2018-07-15 v11 date)\n")
    L.append("## Daily metrics by lead (MAE / RMSE / R2 / bias)\n")
    L.append("| lead | GEFS MAE | C-GEFS MAE | GEFS RMSE | C-GEFS RMSE | GEFS R2 | C-GEFS R2 | GEFS bias | C-GEFS bias |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for i in range(1, 8):
        g = arms["raw-gefs-apcp"]["daily"]["by_lead"][str(i)]
        c = arms["chirps-gefs"]["daily"]["by_lead"][str(i)]
        L.append(f"| {i} | {g['mae']:.3f} | {c['mae']:.3f} | {g['rmse']:.3f} | {c['rmse']:.3f} | "
                 f"{g['r2']:.3f} | {c['r2']:.3f} | {g['bias']:.3f} | {c['bias']:.3f} |")
    go, co = arms["raw-gefs-apcp"]["daily"]["overall"], arms["chirps-gefs"]["daily"]["overall"]
    L.append(f"| overall | {go['mae']:.3f} | {co['mae']:.3f} | {go['rmse']:.3f} | {co['rmse']:.3f} | "
             f"{go['r2']:.3f} | {co['r2']:.3f} | {go['bias']:.3f} | {co['bias']:.3f} |\n")
    L.append("## 7-day sum metrics\n")
    g7o, c7o = g7["overall"], c7["overall"]
    L.append(f"GEFS 7-day: MAE={g7o['mae']:.3f} RMSE={g7o['rmse']:.3f} R2={g7o['r2']:.3f} bias={g7o['bias']:.3f} "
             f"acc={g7['category']['accuracy']:.3f} macroF1={g7['category']['macro_f1']:.3f}")
    L.append(f"CHIRPS-GEFS 7-day: MAE={c7o['mae']:.3f} RMSE={c7o['rmse']:.3f} R2={c7o['r2']:.3f} bias={c7o['bias']:.3f} "
             f"acc={c7['category']['accuracy']:.3f} macroF1={c7['category']['macro_f1']:.3f}")
    L.append(f"Difference (GEFS minus C-GEFS): {json.dumps(diff['seven_day_overall'])} "
             f"category {json.dumps(diff['seven_day_category'])}\n")
    L.append("Limitations: JJAS-season only (Jun-Sep); 2020 excluded by design; "
             "common set = 2021-2022 val + 2023-2025 test overlap (no 2016-2019 train overlap "
             "since frozen GEFS arm starts 2021); single 00Z init; deterministic forecasts only "
             "(log-loss/Brier not applicable); 2018-07-15 v11-era date excluded from GEFS arm by design.\n")
    L.append("Sources:")
    L.append(f"- {rep['sources']['gefs']}")
    L.append(f"- {rep['sources']['chirps_gefs']}")
    L.append(f"- {rep['sources']['truth']}")
    L.append("Reproducibility: `python src/evaluation/phase1b_3_common_evaluation.py` "
             "(read-only inputs; writes only `phase1b_3_*` artifacts).")
    L.append("Conclusion (factual): see `differences_gefs_minus_chirpsgefs` in "
             "`data/processed/phase1b/phase1b_3_common_report.json` for the empirical result; "
             "carry-forward choice must cite those numbers.\n")
    (REPORTS_DIR / "PHASE1B_3_GEFS_VS_CHIRPSGEFS_REPORT.md").write_text("\n".join(L))

    print(f"common period {rep['common_period']} dates={rep['n_common_dates']} "
          f"daily_rows/arm={rep['n_common_daily_rows_per_arm']}")
    for arm in ["raw-gefs-apcp", "chirps-gefs"]:
        d, s = arms[arm]["daily"]["overall"], arms[arm]["seven_day"]["overall"]
        c = arms[arm]["seven_day"]["category"]
        print(f"{arm} daily MAE={d['mae']:.3f} RMSE={d['rmse']:.3f} R2={d['r2']:.3f} bias={d['bias']:.3f}")
        print(f"{arm} 7-day MAE={s['mae']:.3f} RMSE={s['rmse']:.3f} R2={s['r2']:.3f} bias={s['bias']:.3f} "
              f"acc={c['accuracy']:.3f} macroF1={c['macro_f1']:.3f}")
    print("wrote phase1b_3_* artifacts (frozen sources untouched)")


if __name__ == "__main__":
    main()
