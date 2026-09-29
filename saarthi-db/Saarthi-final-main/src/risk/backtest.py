"""Phase 4.0C/D — dry-spell rule backtest (deterministic rule, NO ML, NO tuning).

Label (from future CHIRPS only): issue D, block, run-length L ->
  1 if any CHIRPS dry run of length >= L overlaps D+1..D+7, else 0.
Onset diagnostic: 1 if the overlapping run started after D-FEAT_LAG
  (i.e. not already visible in the antecedent window).

Rule WARN(D), fixed a priori (never tuned on eval):
  dry_run  = trailing consecutive dry days ending at D-FEAT_LAG (CHIRPS)
  f_dry    = dry days in D+1..D+7 from the forecast source
  WARN = (dry_run >= 3 and f_dry >= 5) or (f_dry >= 6)

Forecast source:
  perfect: CHIRPS D+1..D+7 itself (upper bound / diagnostic).
  gefs:    HISTORICAL GEFS PSEUDO-FORECAST (historical_gefs_leads_v2.csv,
           leads 1..7 of the issue; explicitly NOT the live IFS model).

Arms (all fixed): RULE, ANTECEDENT_ONLY (dry_run >= 3, no forecast),
NEVER (all 0), ALWAYS (all 1). Train-period (2010-2019) JJAS weekly
prevalence reported as the event-rate reference.

Issues: weekly Wednesdays, JJAS, eval 2021-2025. Perfect mode last issue
2025-12-17 (needs +13d run visibility); GEFS mode limited by archive end.

Usage: python -m src.risk.backtest --source perfect|gefs
"""

import argparse
import json

import numpy as np
import pandas as pd

from src.risk.common import (BLOCKS, BOOT_REPS, BOOT_SEED, CHIRPS, EVAL_START, FEAT_LAG, GEFS, JJAS,
                             OUT, boot_ci_issue_grouped, contingency, dry_run_ending_at, dry_runs,
                             is_dry)

MIN_LENS = (5, 7, 10)


def wednesdays(dates) -> list:
    return [d for d in dates if d.weekday() == 2]


def load_chirps_pivot():
    df = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    assert df["rainfall_mm"].notna().all()
    pv = df.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    assert (pv.index.min(), pv.index.max()) == (pd.Timestamp("2010-01-01"), pd.Timestamp("2025-12-31"))
    for b in BLOCKS:
        assert b in pv.columns
    return pv


def runs_by_block(pv, L: int) -> dict:
    out = {}
    dates = pv.index
    for b in BLOCKS:
        out[b] = dry_runs(dates, pv[b].to_numpy(), L)
    return out


def label_overlap(runs_b, D: pd.Timestamp) -> tuple[int, int]:
    """(overlap_label, onset_label) for window D+1..D+7."""
    lo, hi = D + pd.Timedelta(days=1), D + pd.Timedelta(days=7)
    vis = D - pd.Timedelta(days=FEAT_LAG)
    ov = onset = 0
    for s, e, _ in runs_b:
        if s <= hi and e >= lo:
            ov = 1
            if s > vis:
                onset = 1
    return ov, onset


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["perfect", "gefs"], required=True)
    ap.add_argument("--eval-start", default=EVAL_START)
    ap.add_argument("--eval-end", default=None)
    ap.add_argument("--tag", default=None)
    args = ap.parse_args()
    tag = args.tag or args.source

    pv = load_chirps_pivot()
    dates, pos = pv.index, {d: i for i, d in enumerate(pv.index)}
    arr = {b: pv[b].to_numpy() for b in BLOCKS}

    gefs = None
    if args.source == "gefs":
        gefs = pd.read_csv(GEFS, parse_dates=["forecast_date", "forecast_target_date"])
        assert set(gefs["block"]) == set(BLOCKS)
        assert sorted(gefs["lead_day"].unique().tolist()) == [1, 2, 3, 4, 5, 6, 7]
        assert (gefs["forecast_date"] < "2020-01-01").any() and (gefs["forecast_date"] >= "2021-01-01").any()
        assert not ((gefs["forecast_date"] >= "2020-01-01") & (gefs["forecast_date"] < "2021-01-01")).any(), \
            "2020 must be excluded from GEFS issues"
        assert gefs["forecast_rainfall_mm"].notna().all()
        gidx = gefs.set_index(["forecast_date", "block", "lead_day"])["forecast_rainfall_mm"]

    last_issue = (pd.Timestamp("2025-12-17") if args.source == "perfect"
                  else gefs["forecast_date"].max())
    if args.eval_end:
        last_issue = min(last_issue, pd.Timestamp(args.eval_end))
    issues = [d for d in wednesdays(dates)
              if d >= pd.Timestamp(args.eval_start) and d.month in JJAS and d <= last_issue]
    if args.source == "gefs":
        gdates = set(gefs["forecast_date"].unique())
        issues = [d for d in issues if d in gdates]
    assert len(issues) > 0

    # train-period JJAS weekly prevalence reference (2010-2019, fixed, never eval)
    train_issues = [d for d in wednesdays(dates)
                    if d < pd.Timestamp("2020-01-01") and d.month in JJAS]

    all_rows, metrics = [], {"arms": {}, "baseline_prevalence_train": {}, "ci": {}}
    for L in MIN_LENS:
        runs = runs_by_block(pv, L)
        for is_eval_run, iss_list in (("train_ref", train_issues), ("eval", issues)):
            for D in iss_list:
                i = pos[D]
                if i < 30 or i + 14 >= len(dates):
                    continue
                for b in BLOCKS:
                    a = arr[b]
                    dry_run = dry_run_ending_at(a[i - 30:i - 2])  # ends D-3; 30d lookback cap
                    assert (i - 3) < (i + 1)  # antecedent strictly before label window
                    r14 = float(a[i - 16:i - 2].sum())  # D-16..D-3 context only
                    if args.source == "perfect":
                        f = a[i + 1:i + 8]
                    else:
                        try:
                            f = np.array([gidx[(D, b, ld)] for ld in range(1, 8)], dtype=float)
                        except KeyError:
                            continue
                    f_dry = int(sum(is_dry(v) for v in f))
                    f_sum = float(np.sum(f))
                    ov, onset = label_overlap(runs[b], D)
                    warn = int((dry_run >= 3 and f_dry >= 5) or (f_dry >= 6))
                    ant = int(dry_run >= 3)
                    all_rows.append({"L": L, "split": is_eval_run, "issue": D.date().isoformat(),
                                     "block": b, "label": ov, "onset_label": onset,
                                     "RULE": warn, "ANTECEDENT_ONLY": ant, "NEVER": 0, "ALWAYS": 1,
                                     "dry_run_Dminus3": dry_run, "f_dry": f_dry,
                                     "f_sum": round(f_sum, 3), "antecedent_14d": round(r14, 3)})

    pred = pd.DataFrame(all_rows)
    OUT.mkdir(parents=True, exist_ok=True)
    pred.to_csv(OUT / f"backtest_{tag}_predictions.csv", index=False)

    ev = pred[pred.split == "eval"]
    for L in MIN_LENS:
        sw = ev[ev.L == L]
        y = sw["label"].to_numpy()
        iss = sw["issue"].to_numpy()
        for arm in ("RULE", "ANTECEDENT_ONLY", "NEVER", "ALWAYS"):
            c = contingency(y, sw[arm].to_numpy())
            metrics["arms"][f"JJAS/L{L}/{arm}"] = c
            if arm == "RULE":
                for stat in ("precision", "recall", "f1"):
                    metrics["ci"][f"JJAS/L{L}/RULE/{stat}"] = boot_ci_issue_grouped(
                        iss, y, sw[arm].to_numpy(), stat=stat)
        # block-level RULE breakdown (diagnostic; no independence claimed)
        metrics.setdefault("block", {})
        for b in BLOCKS:
            sb = sw[sw.block == b]
            metrics["block"][f"JJAS/L{L}/{b}"] = contingency(
                sb["label"].to_numpy(), sb["RULE"].to_numpy())
        # onset-only diagnostic for RULE
        yo = sw["onset_label"].to_numpy()
        metrics["arms"][f"JJAS/L{L}/RULE_onset_only"] = contingency(yo, sw["RULE"].to_numpy())
        tr = pred[(pred.split == "train_ref") & (pred.L == L)]
        metrics["baseline_prevalence_train"][f"L{L}"] = round(float(tr["label"].mean()), 4)

    metrics["method"] = {
        "source": ("CHIRPS-as-perfect-forecast (upper bound)" if args.source == "perfect"
                   else "HISTORICAL GEFS PSEUDO-FORECAST, explicitly NOT live IFS"),
        "rule": "WARN=(dry_run_through_D-3>=3 and f_dry_D+1..7>=5) or (f_dry>=6); fixed a priori",
        "label": f"overlap of a >={{5,7,10}}d <1mm CHIRPS run with D+1..D+7; onset=start after D-{FEAT_LAG}",
        "issues": f"weekly Wednesday JJAS eval {args.eval_start}.., n_issues={len(issues)}",
        "uncertainty": f"issue-clustered bootstrap {BOOT_REPS} reps seed {BOOT_SEED}",
    }
    (OUT / f"backtest_{tag}_metrics.json").write_text(json.dumps(metrics, indent=2),
                                                              encoding="utf-8")
    key = "JJAS/L7/RULE"
    print(json.dumps({k: v for k, v in metrics["arms"].items() if k.startswith("JJAS/L7")}, indent=2))
    print(json.dumps(metrics["ci"], indent=2))
    print("gate L7 RULE:", metrics["arms"][key]["recall"], metrics["arms"][key]["precision"])


if __name__ == "__main__":
    main()
