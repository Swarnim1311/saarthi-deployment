"""Phase 4.1C — historical validation of excess-rain + field-work rules.

Forecast source: HISTORICAL GEFS PSEUDO-FORECAST (leads 1..7), explicitly NOT
live IFS. Truth: CHIRPS only (future CHIRPS builds labels, never features).

Issues: weekly Wednesdays, JJAS, eval 2021-2025 (2020 excluded by the archive).
Thresholds/rules are fixed a priori (excess-v1 train-fit 2010-2019,
field-v1 proposed); NOTHING is tuned on eval. GEFS has no probability
column, so FIELD_HIGH_PRECIP_PROB is IFS-compatibility-only (unit-tested,
not historically validated).

Usage:
  python -m src.risk.excess_rain --build-thresholds   # once, train-only
  python -m src.risk.backtest_41
"""

from __future__ import annotations

import argparse
import json

import numpy as np
import pandas as pd

from src.risk.common import (BLOCKS, BOOT_REPS, BOOT_SEED, CHIRPS, EVAL_START,
                             GEFS, JJAS, OUT, boot_ci_issue_grouped,
                             contingency)
from src.risk.excess_rain import METHOD_VERSION as EXCESS_V, load_thresholds
from src.risk.excess_rain import detect_excess
from src.risk.field_work import METHOD_VERSION as FIELD_V
from src.risk.field_work import classify_3d, describe_7d, observed_3d


def wednesdays(dates) -> list:
    return [d for d in dates if d.weekday() == 2]


def roll_max_3(vals: list) -> float:
    return max(vals[i] + vals[i + 1] + vals[i + 2] for i in range(5))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--eval-start", default=EVAL_START)
    ap.add_argument("--eval-end", default=None)
    args = ap.parse_args()

    thr = load_thresholds()
    assert thr["method_version"] == EXCESS_V and thr["fit_period"] == "2010-2019"

    chirps = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"],
                         parse_dates=["date"])
    chirps["block"] = chirps["block"].replace({"Lehragaga": "Lehra"})
    assert chirps["rainfall_mm"].notna().all()
    pv = chirps.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    dates, pos = pv.index, {d: i for i, d in enumerate(pv.index)}
    arr = {b: pv[b].to_numpy() for b in BLOCKS}

    gefs = pd.read_csv(GEFS, parse_dates=["forecast_date", "forecast_target_date"])
    assert not ((gefs["forecast_date"] >= "2020-01-01")
                & (gefs["forecast_date"] < "2021-01-01")).any(), "2020 must stay excluded"
    assert gefs["forecast_rainfall_mm"].notna().all()
    gidx = gefs.set_index(["forecast_date", "block", "lead_day"])["forecast_rainfall_mm"]

    last_issue = gefs["forecast_date"].max()
    if args.eval_end:
        last_issue = min(last_issue, pd.Timestamp(args.eval_end))
    issues = [d for d in wednesdays(dates)
              if d >= pd.Timestamp(args.eval_start) and d.month in JJAS and d <= last_issue]
    gdates = set(gefs["forecast_date"].unique())
    issues = [d for d in issues if d in gdates]
    assert len(issues) > 0
    train_issues = [d for d in wednesdays(dates)
                    if d < pd.Timestamp("2020-01-01") and d.month in JJAS]

    ex_rows, fw_rows = [], []
    for D in issues + train_issues:
        split = "eval" if D in set(issues) else "train_ref"
        i = pos[D]
        if i < 30 or i + 14 >= len(dates):
            continue
        for b in BLOCKS:
            a = arr[b]
            o = [float(a[i + k]) for k in range(1, 8)]  # CHIRPS D+1..D+7 (truth only)
            t = thr["blocks"][b]
            if split == "eval":
                try:
                    f = [float(gidx[(D, b, ld)]) for ld in range(1, 8)]
                except KeyError:
                    continue
                det = detect_excess(f, t)
                fw = classify_3d(f[:3], t["daily_p95"], None)
                fw7 = describe_7d(f, t["daily_p95"])
                ex_rows.append({
                    "split": split, "issue": D.date().isoformat(), "block": b,
                    "EXCESS_DAILY": int(det["daily"]["triggered"]),
                    "EXCESS_3D": int(det["d3"]["triggered"]),
                    "EXCESS_7D": int(det["d7"]["triggered"]),
                    "LABEL_DAILY": int(any(v > t["daily_p95"] for v in o)),
                    "LABEL_3D": int(roll_max_3(o) > t["d3_p90"]),
                    "LABEL_7D": int(sum(o) > t["d7_p90"]),
                    "f_max": round(max(f), 3), "f_3dmax": round(roll_max_3(f), 3),
                    "f_7d": round(sum(f), 3),
                    "o_max": round(max(o), 3), "o_3dmax": round(roll_max_3(o), 3),
                    "o_7d": round(sum(o), 3)})
                obs = observed_3d(o[:3])
                fw_rows.append({
                    "split": split, "issue": D.date().isoformat(), "block": b,
                    "PRED_HIGH": int(fw["tier"] == "HIGH"),
                    "PRED_TIER": fw["tier"], "PRED_WET3": fw["wet_days"],
                    "PRED_HEAVY": (None if fw["heavy_rain"] is None
                                   else int(fw["heavy_rain"])),
                    "OBS_DISRUPTED": obs["disrupted"], "OBS_TIER": obs["tier"],
                    "OBS_WET3": obs["wet_days"],
                    "PRED_WET7": fw7["wet_days"], "OBS_WET7": int(sum(1 for v in o if v >= 1.0))})
            else:
                ex_rows.append({
                    "split": split, "issue": D.date().isoformat(), "block": b,
                    "LABEL_DAILY": int(any(v > t["daily_p95"] for v in o)),
                    "LABEL_3D": int(roll_max_3(o) > t["d3_p90"]),
                    "LABEL_7D": int(sum(o) > t["d7_p90"])})
                obs = observed_3d(o[:3])
                fw_rows.append({"split": split, "issue": D.date().isoformat(),
                                "block": b, "OBS_DISRUPTED": obs["disrupted"],
                                "OBS_TIER": obs["tier"]})

    OUT.mkdir(parents=True, exist_ok=True)
    ex = pd.DataFrame(ex_rows)
    fw = pd.DataFrame(fw_rows)
    ex.to_csv(OUT / "backtest_41_excess_predictions.csv", index=False)
    fw.to_csv(OUT / "backtest_41_field_predictions.csv", index=False)

    ev_ex = ex[ex.split == "eval"]
    metrics_ex: dict = {"arms": {}, "baseline_prevalence_train": {}, "ci": {},
                        "block": {}}
    for comp, lab in (("EXCESS_DAILY", "LABEL_DAILY"), ("EXCESS_3D", "LABEL_3D"),
                      ("EXCESS_7D", "LABEL_7D")):
        y = ev_ex[lab].to_numpy()
        p = ev_ex[comp].to_numpy()
        iss = ev_ex["issue"].to_numpy()
        metrics_ex["arms"][f"JJAS/{comp}"] = contingency(y, p)
        metrics_ex["arms"][f"JJAS/{comp}_NEVER"] = contingency(y, np.zeros_like(p))
        metrics_ex["arms"][f"JJAS/{comp}_ALWAYS"] = contingency(y, np.ones_like(p))
        for stat in ("precision", "recall", "f1"):
            metrics_ex["ci"][f"JJAS/{comp}/{stat}"] = boot_ci_issue_grouped(
                iss, y, p, stat=stat)
        metrics_ex["block"][comp] = {
            b: contingency(g[lab].to_numpy(), g[comp].to_numpy())
            for b, g in ev_ex.groupby("block")}
        tr = ex[(ex.split == "train_ref")]
        metrics_ex["baseline_prevalence_train"][comp] = round(float(tr[lab].mean()), 4)
    metrics_ex["method"] = {
        "source": "HISTORICAL GEFS PSEUDO-FORECAST, explicitly NOT live IFS",
        "thresholds": f"{EXCESS_V} fit 2010-2019 JJAS only",
        "issues": f"weekly Wednesday JJAS eval {args.eval_start}.., n_issues={len(issues)}",
        "uncertainty": f"issue-clustered bootstrap {BOOT_REPS} reps seed {BOOT_SEED}",
    }
    (OUT / "backtest_41_excess_metrics.json").write_text(
        json.dumps(metrics_ex, indent=2), encoding="utf-8")

    ev_fw = fw[fw.split == "eval"]
    metrics_fw: dict = {"arms": {}, "baseline_prevalence_train": {}, "ci": {},
                        "block": {}, "tier": {}}
    y = ev_fw["OBS_DISRUPTED"].to_numpy()
    p = ev_fw["PRED_HIGH"].to_numpy()
    iss = ev_fw["issue"].to_numpy()
    metrics_fw["arms"]["JJAS/FIELD_HIGH"] = contingency(y, p)
    metrics_fw["arms"]["JJAS/FIELD_HIGH_NEVER"] = contingency(y, np.zeros_like(p))
    metrics_fw["arms"]["JJAS/FIELD_HIGH_ALWAYS"] = contingency(y, np.ones_like(p))
    for stat in ("precision", "recall", "f1"):
        metrics_fw["ci"][f"JJAS/FIELD_HIGH/{stat}"] = boot_ci_issue_grouped(
            iss, y, p, stat=stat)
    metrics_fw["block"]["FIELD_HIGH"] = {
        b: contingency(g["OBS_DISRUPTED"].to_numpy(), g["PRED_HIGH"].to_numpy())
        for b, g in ev_fw.groupby("block")}
    metrics_fw["tier"]["pred_distribution"] = ev_fw["PRED_TIER"].value_counts().to_dict()
    metrics_fw["tier"]["obs_distribution"] = ev_fw["OBS_TIER"].value_counts().to_dict()
    agree = (ev_fw["PRED_TIER"] == ev_fw["OBS_TIER"]).mean()
    metrics_fw["tier"]["tier_agreement"] = round(float(agree), 4)
    tr = fw[fw.split == "train_ref"]
    metrics_fw["baseline_prevalence_train"]["FIELD_HIGH"] = round(
        float(tr["OBS_DISRUPTED"].mean()), 4)
    metrics_fw["baseline_prevalence_train"]["tier_obs_train"] = \
        tr["OBS_TIER"].value_counts(normalize=True).round(4).to_dict()
    metrics_fw["method"] = {
        "source": "HISTORICAL GEFS PSEUDO-FORECAST, explicitly NOT live IFS",
        "rule": f"{FIELD_V}: HIGH if >=2 wet days (>=1mm) in D+1..D+3; proposed a priori",
        "prob_flag": "FIELD_HIGH_PRECIP_PROB unit-tested + IFS-compatible; NOT in GEFS validation",
        "issues": f"weekly Wednesday JJAS eval {args.eval_start}.., n_issues={len(issues)}",
        "uncertainty": f"issue-clustered bootstrap {BOOT_REPS} reps seed {BOOT_SEED}",
    }
    (OUT / "backtest_41_field_metrics.json").write_text(
        json.dumps(metrics_fw, indent=2), encoding="utf-8")

    print("EXCESS:", json.dumps(metrics_ex["arms"], indent=2))
    print("FIELD HIGH:", json.dumps(metrics_fw["arms"], indent=2))
    print("FIELD tiers pred/obs:", metrics_fw["tier"]["pred_distribution"],
          metrics_fw["tier"]["obs_distribution"],
          "agree:", metrics_fw["tier"]["tier_agreement"])


if __name__ == "__main__":
    main()
