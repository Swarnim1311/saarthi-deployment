"""Block rainfall climatology builder (Phase 3A).

Source (read-only, never modified):
    data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv
    columns: system:index, block, date, rainfall_mm (.geo empty)

Outputs (data/processed/climatology/):
    block_doy_normals.csv   per-block x issue-day-of-year climatology
    method.json             methodology metadata incl. leakage statement

Method (explicit, documented):
  - wet day: rainfall_mm >= WET_MM (1.0, same prototype heuristic as NB05/live
    pipeline; wet >= 1.0 mm/day is a project convention, not an IMD definition).
  - zeros: the source has zero NaNs (verified 2010-01-01..2025-12-31, 6 blocks x
    5844 days, no dupes); every 0.0 is an observed no-rain day. Nothing filled.
  - leap day: Feb-29 observations pooled into DOY 60 (Feb 28); the table has
    DOY 1..366 with DOY 60 carrying n = 16 Feb-28 + 4 Feb-29 samples.
    (4 extra samples in 16 years: negligible, documented here.)
  - pooling: for each block x DOY d, the distribution pools observations from
    all years x circular window d-7..d+7 (15 calendar days). This is the ONLY
    smoothing. No post-smoothing of quantiles.
  - daily_mean_mm: mean of pooled daily values.
  - wet_prob: fraction of pooled days with rain >= WET_MM.
  - W3/W4 terciles: for issue-DOY d, W3(d) = sum of rain over dates D+17..D+23
    and W4(d) = D+24..D+30 for every historical issue date with that DOY;
    t33/t66 are the 33rd/66th percentiles of those window sums (linear
    interpolation, numpy default). n = number of historical windows pooled.
  - jjas: 1 when the calendar day falls in Jun-Sep (month in 6..9).

LEAKAGE RULE (critical):
  --end-date D builds the climatology from dates STRICTLY BEFORE D
  (date < D). An issue date D may therefore only use a climatology built with
  --end-date <= D. The default (no --end-date) uses the full verified span and
  is labelled kind="deployment" -- it is a DEPLOYMENT artifact and MUST NOT be
  used to claim a leakage-free backtest over the same span. Backtests use
  kind="train-frozen" artifacts (e.g. --end-date 2020-01-01 for a 2020+ test).

Usage:
    python src/climatology/build_normals.py [--end-date 2020-01-01]
        [--out-dir data/processed/climatology] [--tag train_2010_2019]
"""

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[2]
SRC_CSV = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
WET_MM = 1.0
POOL_HALF = 7  # circular pooling half-window (days)
W3_START, W3_END = 17, 23
W4_START, W4_END = 24, 30


def load_source() -> pd.DataFrame:
    df = pd.read_csv(SRC_CSV, usecols=["block", "date", "rainfall_mm"])
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values(["block", "date"]).reset_index(drop=True)
    return df


def _wheel_doy(ts: pd.Timestamp) -> int:
    if ts.month == 2 and ts.day == 29:
        return 60
    base = pd.Timestamp(2021, ts.month, ts.day)  # non-leap reference
    return int(base.dayofyear) + (1 if base.dayofyear >= 60 else 0)


def build(df: pd.DataFrame, end_date: str | None) -> tuple[pd.DataFrame, dict]:
    cutoff = pd.to_datetime(end_date) if end_date else None
    use = df[df["date"] < cutoff] if cutoff is not None else df
    span = (use["date"].min().date().isoformat(), use["date"].max().date().isoformat())
    rows = []
    for block in BLOCKS:
        g = use[use["block"] == block].set_index("date")["rainfall_mm"].sort_index()
        dates = g.index
        vals = g.to_numpy()
        # Precompute W3/W4 window sums keyed by issue position for speed.
        # Window sums via cumsum: sum over issue i of dates i+a..i+b (inclusive)
        # = C[i+b+1] - C[i+a]. Valid issues: i <= n-1-b.
        n = len(vals)
        w3 = np.full(n, np.nan)
        w4 = np.full(n, np.nan)
        if n > W4_END:
            c = np.concatenate([[0.0], np.cumsum(vals)])
            nv3 = n - W3_END
            w3[:nv3] = c[W3_END + 1 : nv3 + W3_END + 1] - c[W3_START : nv3 + W3_START]
            nv4 = n - W4_END
            w4[:nv4] = c[W4_END + 1 : nv4 + W4_END + 1] - c[W4_START : nv4 + W4_START]
        wheel = dates.map(_wheel_doy).to_numpy()
        for doy in range(1, 367):
            delta = np.minimum((wheel - doy) % 366, (doy - wheel) % 366)
            sel = delta <= POOL_HALF
            pv = vals[sel]
            w3v = w3[sel & ~np.isnan(w3)]
            w4v = w4[sel & ~np.isnan(w4)]
            mnum = _doy_month(doy)
            rows.append(
                {
                    "block": block,
                    "doy": doy,
                    "n_days": int(sel.sum()),
                    "daily_mean_mm": round(float(np.mean(pv)), 4),
                    "wet_prob": round(float(np.mean(pv >= WET_MM)), 4),
                    "w3_t33_mm": round(float(np.percentile(w3v, 33)), 3) if len(w3v) else None,
                    "w3_t66_mm": round(float(np.percentile(w3v, 33 * 2)), 3) if len(w3v) else None,
                    "w3_n": int(len(w3v)),
                    "w4_t33_mm": round(float(np.percentile(w4v, 33)), 3) if len(w4v) else None,
                    "w4_t66_mm": round(float(np.percentile(w4v, 33 * 2)), 3) if len(w4v) else None,
                    "w4_n": int(len(w4v)),
                    "jjas": int(mnum in (6, 7, 8, 9)),
                }
            )
    table = pd.DataFrame(rows)
    method = {
        "source": str(SRC_CSV.relative_to(REPO)),
        "span_used": list(span),
        "rows_used": int(len(use)),
        "kind": "train-frozen" if cutoff is not None else "deployment",
        "causal_cutoff": end_date,
        "leakage_statement": (
            "train-frozen: built from dates strictly before cutoff; valid for "
            "issue dates >= cutoff. deployment: full-span artifact for live use "
            "only, NOT a leakage-free backtest artifact."
            if cutoff is not None
            else "DEPLOYMENT artifact over the full verified span; do NOT use "
            "for leakage-free historical backtests."
        ),
        "wet_threshold_mm": WET_MM,
        "leap_handling": "Feb-29 pooled into DOY 60 (Feb 28)",
        "pooling": f"circular +- {POOL_HALF} days, no post-smoothing",
        "w3_def": f"sum of rain over issue+{W3_START}..issue+{W3_END}",
        "w4_def": f"sum of rain over issue+{W4_START}..issue+{W4_END}",
        "terciles": "33rd/66th percentiles, linear interpolation",
        "zeros": "source has zero NaNs; all 0.0 are observed no-rain days",
        "blocks": BLOCKS,
    }
    return table, method


def _doy_month(doy: int) -> int:
    cum = [0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335, 366]
    for m in range(1, 13):
        if cum[m - 1] < doy <= cum[m]:
            return m
    return 12


def main() -> None:
    ap = argparse.ArgumentParser(description="Build block x DOY rainfall climatology.")
    ap.add_argument("--end-date", default=None, help="Causal cutoff: use dates strictly before D (YYYY-MM-DD).")
    ap.add_argument("--out-dir", default=str(REPO / "data" / "processed" / "climatology"))
    ap.add_argument("--tag", default=None, help="Filename tag; default: train_<cutoff> or full.")
    args = ap.parse_args()

    df = load_source()
    table, method = build(df, args.end_date)
    tag = args.tag or ("train_" + args.end_date if args.end_date else "full")
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    tpath = out / f"block_doy_normals_{tag}.csv"
    mpath = out / f"method_{tag}.json"
    table.to_csv(tpath, index=False)
    mpath.write_text(json.dumps(method, indent=2), encoding="utf-8")
    print(f"wrote {tpath} ({table.shape[0]} rows) + {mpath}")
    print(f"kind={method['kind']} span={method['span_used']}")
    j = table[table["jjas"] == 1]
    print(f"JJAS daily_mean range: {j['daily_mean_mm'].min():.2f}..{j['daily_mean_mm'].max():.2f} mm; "
          f"non-JJAS: {table[table['jjas']==0]['daily_mean_mm'].min():.2f}..{table[table['jjas']==0]['daily_mean_mm'].max():.2f} mm")


if __name__ == "__main__":
    main()
