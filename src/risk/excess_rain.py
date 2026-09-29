"""Phase 4.1A — excess rainfall thresholds + detector (NO ML, NO tuning).

Historical thresholds are fit on TRAIN-ONLY data: 2010-2019 JJAS block CHIRPS.
2020 is excluded; 2021-2025 is never used for fitting (asserted).

Three SEPARATE evidence components (never combined into one score here):
  DAILY_EXTREME : max(D+1..D+7 daily)      vs block JJAS daily p95
  3DAY_EXTREME  : max rolling 3-day sum    vs block JJAS 3-day p90
  7DAY_EXTREME  : 7-day accumulation       vs block JJAS 7-day p90

Trigger rule: forecast value STRICTLY exceeds the threshold (== threshold
does not trigger). Missing values never become zero: any missing day in the
required span makes that component unavailable (triggered=None).

Usage:
  python -m src.risk.excess_rain --build-thresholds   # train-only fit
  (detection itself is exercised by backtest_41.py and unit tests)
"""

from __future__ import annotations

import argparse
import json

import numpy as np
import pandas as pd

from src.risk.common import BLOCKS, JJAS, OUT

METHOD_VERSION = "excess-v1"
TRAIN_END = "2020-01-01"  # fit uses dates < TRAIN_END (2010-2019)
THRESHOLD_PATH = OUT / "excess_thresholds_v1.json"

REASONS = {
    "daily": "EXCESS_DAILY_P95",
    "d3": "EXCESS_3D_P90",
    "d7": "EXCESS_7D_P90",
}


def load_chirps() -> pd.DataFrame:
    from src.risk.common import CHIRPS
    df = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    df["block"] = df["block"].replace({"Lehragaga": "Lehra"})
    assert df["rainfall_mm"].notna().all(), "missing rainfall must never silently become zero"
    return df


def rolling_sums_ending_in_jjas(vals: np.ndarray, dates, window: int) -> np.ndarray:
    """Rolling `window`-day sums over the continuous daily series, keeping only
    sums whose END date falls in JJAS (so edge windows borrow May days honestly)."""
    s = pd.Series(vals)
    roll = s.rolling(window).sum().to_numpy()
    keep = np.array([d.month in JJAS for d in dates])
    out = roll[window - 1:][keep[window - 1:]]
    return out[~np.isnan(out)]


def build_thresholds(df: pd.DataFrame | None = None) -> dict:
    """Fit block x JJAS thresholds on 2010-2019 ONLY. Raises if eval years leak in."""
    df = load_chirps() if df is None else df
    assert sorted(df["block"].unique().tolist()) == sorted(BLOCKS)
    fit = df[df["date"] < TRAIN_END].copy()
    assert fit["date"].max() < pd.Timestamp(TRAIN_END), "threshold fit must exclude 2020+"
    assert (fit["date"].dt.year >= 2010).all()
    out: dict = {"method_version": METHOD_VERSION, "fit_period": "2010-2019",
                 "season": "JJAS", "blocks": {}}
    for b in BLOCKS:
        g = fit[fit.block == b].sort_values("date").reset_index(drop=True)
        daily = g[g["date"].dt.month.isin(JJAS)]["rainfall_mm"].to_numpy()
        s3 = rolling_sums_ending_in_jjas(g["rainfall_mm"].to_numpy(), g["date"], 3)
        s7 = rolling_sums_ending_in_jjas(g["rainfall_mm"].to_numpy(), g["date"], 7)
        assert len(daily) > 1000 and len(s3) > 1000 and len(s7) > 1000, \
            f"unstable threshold support for {b}"
        out["blocks"][b] = {
            "daily_p95": round(float(np.percentile(daily, 95)), 3),
            "d3_p90": round(float(np.percentile(s3, 90)), 3),
            "d7_p90": round(float(np.percentile(s7, 90)), 3),
            "n_daily": int(len(daily)), "n_3d": int(len(s3)), "n_7d": int(len(s7)),
        }
    return out


def save_thresholds(thr: dict) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    THRESHOLD_PATH.write_text(json.dumps(thr, indent=2), encoding="utf-8")


def load_thresholds() -> dict:
    return json.loads(THRESHOLD_PATH.read_text(encoding="utf-8"))


def _component(metric: str, value: float | None, threshold: float,
               percentile: float, reason: str) -> dict:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return {"metric": metric, "forecast_value": None, "threshold": threshold,
                "percentile": percentile, "triggered": None,
                "reason_code": reason + "_UNAVAILABLE"}
    return {"metric": metric, "forecast_value": round(float(value), 3),
            "threshold": threshold, "percentile": percentile,
            "triggered": bool(float(value) > threshold),
            "reason_code": reason}


def detect_excess(f7: list, thr_block: dict) -> dict:
    """Three evidence components over forecast D+1..D+7 (7 values, None=missing).

    Returns {daily, d3, d7} component dicts with block/issue-agnostic fields;
    callers add block, issue_date, forecast_window, method_version.
    """
    assert len(f7) == 7, "exact D+1..D+7 window required"
    vals = [None if v is None else float(v) for v in f7]
    daily_max = None if any(v is None for v in vals) else max(vals)
    r3 = ([vals[i] + vals[i + 1] + vals[i + 2] for i in range(5)]
          if all(v is not None for v in vals) else None)
    s7 = None if any(v is None for v in vals) else sum(vals)
    return {
        "daily": _component("daily_max_mm", daily_max, thr_block["daily_p95"], 95,
                            REASONS["daily"]),
        "d3": _component("rolling_3d_max_mm",
                         None if r3 is None else max(r3), thr_block["d3_p90"], 90,
                         REASONS["d3"]),
        "d7": _component("accum_7d_mm", s7, thr_block["d7_p90"], 90, REASONS["d7"]),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build-thresholds", action="store_true")
    args = ap.parse_args(argv)
    if args.build_thresholds:
        thr = build_thresholds()
        save_thresholds(thr)
        print(json.dumps({b: v for b, v in thr["blocks"].items()}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
