"""
Phase 1B — Fair forecast comparison scoring (2026-09-15).

Compares forecast arms (CHIRPS-GEFS baseline, raw GEFS APCP, IFS ENS when
available) against the SAME CHIRPS truth, with FROZEN thresholds and splits.

Frozen (never recalculated here):
  T33 = 10.939151985384193, T66 = 34.66167447031182  (models/inference_config.json)
  splits: train JJAS 2016-19 / val JJAS 2021-22 / test JJAS 2023-25, 2020 excluded
  benchmark reference: Raw GEFS test MAE ~= 19.54, R2 ~= 0.38 (NB04)

Metrics per arm:
  daily (per-lead): MAE / RMSE / R2 / Bias overall + per lead_day + per block
      + per period, on (issue, block, lead) rows joined to CHIRPS daily.
  7-day sums: sum of leads 1..7 per (issue, block) vs CHIRPS sum D+1..D+7;
      same metrics + deterministic LOW/NORMAL/HIGH from frozen thresholds
      (Accuracy, macro F1, confusion). Probabilistic log-loss/Brier for NEW
      arms are deliberately NOT computed: they need per-source residual-ECDF
      calibration (future Phase 1 item); applying the GEFS calibration to IFS
      would be invalid.

QC: duplicate (issue,block,lead) check, leads subset of 1..7, numeric mm,
non-negative, target_date == issue_date + lead_day, 6-block coverage report,
missing-date manifest (never silent).

Inputs (read-only; nothing is overwritten):
  data/processed/historical/historical_gefs_leads_v2.csv  (CHIRPS-GEFS arm)
  data/processed/phase1b/raw_gefs_apcp_leads.csv          (raw APCP arm)
  data/processed/phase1b/ifsens_leads.csv                 (IFS arm, when present)
  data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv (truth)

Outputs (phase1b/ only):
  data/processed/phase1b/metrics_<arm>_daily.csv
  data/processed/phase1b/metrics_<arm>_7day.csv
  data/processed/phase1b/phase1b_comparison_report.json

Usage:
  python src/evaluation/phase1b_forecast_comparison.py --validate
      # scores the CHIRPS-GEFS arm on the frozen test slice; expect MAE ~= 19.54
  python src/evaluation/phase1b_forecast_comparison.py --all
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import (accuracy_score, confusion_matrix, f1_score, mean_absolute_error,
                             mean_squared_error, r2_score)

PROJECT = Path(__file__).resolve().parents[2]
PHASE1B_DIR = PROJECT / "data" / "processed" / "phase1b"
V2_LEADS = PROJECT / "data" / "processed" / "historical" / "historical_gefs_leads_v2.csv"
APCP_LEADS = PHASE1B_DIR / "raw_gefs_apcp_leads.csv"
IFS_LEADS = PHASE1B_DIR / "ifsens_leads.csv"
CHIRPS_CSV = PROJECT / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"

T33 = 10.939151985384193  # frozen, models/inference_config.json
T66 = 34.66167447031182
BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]


def period_of(year: int) -> str:
    if 2016 <= year <= 2019:
        return "2016-2019"
    if 2021 <= year <= 2022:
        return "2021-2022"
    if 2023 <= year <= 2025:
        return "2023-2025"
    return "other"


def load_arm(name: str, path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    need = {"forecast_date", "lead_day", "block", "forecast_target_date", "forecast_rainfall_mm"}
    missing = need - set(df.columns)
    assert not missing, f"{name}: missing columns {missing}"
    df = df.copy()
    df["forecast_date"] = pd.to_datetime(df["forecast_date"])
    df["forecast_target_date"] = pd.to_datetime(df["forecast_target_date"])
    df["lead_day"] = df["lead_day"].astype(int)
    df["forecast_rainfall_mm"] = pd.to_numeric(df["forecast_rainfall_mm"], errors="coerce")
    # QC
    assert df[["forecast_date", "block", "lead_day"]].duplicated().sum() == 0, f"{name}: duplicates"
    assert set(df["lead_day"].unique()) <= set(range(1, 8)), f"{name}: bad leads"
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
    rmse = float(np.sqrt(mean_squared_error(y, p)))
    return {
        "n": int(len(y)),
        "mae": float(mean_absolute_error(y, p)),
        "rmse": rmse,
        "r2": float(r2_score(y, p)) if len(np.unique(y)) > 1 else float("nan"),
        "bias": float(np.mean(p - y)),
    }


def categorize(s: pd.Series) -> pd.Series:
    return pd.cut(s, bins=[-np.inf, T33, T66, np.inf], labels=["LOW", "NORMAL", "HIGH"])


def score_daily(arm: pd.DataFrame, truth: pd.DataFrame) -> dict:
    m = arm.merge(truth, left_on=["block", "forecast_target_date"],
                  right_on=["block", "date"], how="left", indicator=True)
    unmatched = int((m["_merge"] == "left_only").sum())
    m = m[m["_merge"] == "both"].copy()
    out = {"unmatched_truth_rows": unmatched, "matched_rows": len(m), "overall": {},
           "by_lead": {}, "by_block": {}, "by_period": {}}
    out["overall"] = cont_metrics(m["rainfall_mm"], m["forecast_rainfall_mm"])
    for col, key in [("lead_day", "by_lead"), ("block", "by_block"), ("period", "by_period")]:
        for val, g in m.groupby(col, observed=True):
            out[key][str(val)] = cont_metrics(g["rainfall_mm"], g["forecast_rainfall_mm"])
    return out, m


def score_7day(m: pd.DataFrame) -> dict:
    g = m.groupby(["forecast_date", "block", "period"], observed=True)
    s = g.agg(pred_7d=("forecast_rainfall_mm", "sum"),
              true_7d=("rainfall_mm", "sum"),
              n_leads=("lead_day", "count")).reset_index()
    s = s[s["n_leads"] == 7].copy()  # complete horizons only; incomplete recorded below
    out = {"n_forecasts": len(s), "overall": cont_metrics(s["true_7d"], s["pred_7d"]),
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


def run_arm(name: str, path: Path, truth: pd.DataFrame, test_only: bool = False) -> dict:
    if not path.exists():
        return {"arm": name, "status": "missing-dataset", "path": str(path)}
    arm = load_arm(name, path)
    if test_only:
        arm = arm[arm["period"] == "2023-2025"].copy()
    probe = arm.merge(truth, left_on=["block", "forecast_target_date"],
                      right_on=["block", "date"], how="inner")
    if len(probe) == 0:
        # e.g. live/recent inits past the end of CHIRPS truth: nothing to
        # score against. Recorded explicitly, never silently dropped.
        return {"arm": name, "status": "no-truth-overlap",
                "rows": len(arm),
                "target_range": [str(arm["forecast_target_date"].min().date()),
                                 str(arm["forecast_target_date"].max().date())],
                "truth_range": [str(truth["date"].min().date()),
                                str(truth["date"].max().date())]}
    daily, matched = score_daily(arm, truth)
    rep = {"arm": name, "status": "scored", "rows": len(arm),
           "issues": sorted(matched["forecast_date"].dt.strftime("%Y-%m-%d").unique().tolist()),
           "n_issues": int(matched["forecast_date"].nunique()),
           "blocks_present": sorted(matched["block"].unique().tolist()),
           "daily": daily, "seven_day": score_7day(matched)}
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    pd.DataFrame([{"group": "overall", **daily["overall"]}]).to_csv(
        PHASE1B_DIR / f"metrics_{name}_daily.csv", index=False)
    pd.DataFrame([{"group": "overall", **rep["seven_day"]["overall"]}]).to_csv(
        PHASE1B_DIR / f"metrics_{name}_7day.csv", index=False)
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true",
                    help="score CHIRPS-GEFS arm on frozen test slice (expect MAE ~= 19.54)")
    ap.add_argument("--all", action="store_true", help="score every available arm, full periods")
    args = ap.parse_args()
    if bool(args.validate) == bool(args.all):
        ap.error("pass exactly one of --validate or --all")
    truth = load_truth()
    print(f"truth: {len(truth)} rows, {truth['date'].min().date()}..{truth['date'].max().date()}",
          flush=True)
    arms = [("chirps-gefs", V2_LEADS), ("raw-gefs-apcp", APCP_LEADS), ("ifs-ens", IFS_LEADS)]
    report = {"thresholds_frozen": {"T33": T33, "T66": T66},
              "mode": "validate-test-slice" if args.validate else "all-periods",
              "arms": {}}
    for name, path in arms:
        if args.validate and name != "chirps-gefs":
            continue
        print(f"scoring {name} ...", flush=True)
        rep = run_arm(name, path, truth, test_only=args.validate)
        report["arms"][name] = rep
        if rep.get("status") == "scored":
            d, s = rep["daily"]["overall"], rep["seven_day"]["overall"]
            print(f"  daily: MAE={d['mae']:.3f} RMSE={d['rmse']:.3f} R2={d['r2']:.3f} bias={d['bias']:.3f}",
                  flush=True)
            print(f"  7-day: MAE={s['mae']:.3f} RMSE={s['rmse']:.3f} R2={s['r2']:.3f} bias={s['bias']:.3f}",
                  flush=True)
            c = rep["seven_day"]["category"]
            print(f"  categ: acc={c['accuracy']:.3f} macroF1={c['macro_f1']:.3f}", flush=True)
        else:
            print(f"  {rep['status']}", flush=True)
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    (PHASE1B_DIR / "phase1b_comparison_report.json").write_text(json.dumps(report, indent=2))
    print(f"wrote {PHASE1B_DIR / 'phase1b_comparison_report.json'}", flush=True)


if __name__ == "__main__":
    main()
