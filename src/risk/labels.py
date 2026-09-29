"""Phase 4.0B — CHIRPS dry-spell label generator (auditable, no tuning).

Primary definition: a dry spell is >= 7 consecutive days with rainfall
< 1.0 mm/day (DRY_MM in common.py; 1.00 mm is NOT dry). One row per
(block, run start): start, end, duration, JJAS flag (start month in JJAS).

Sensitivity: MIN_LEN in (5, 7, 10) — only the duration criterion changes.

Output: data/processed/risk/dryspell_runs_{5,7,10}d.csv + stdout frequency
report (JJAS run counts by block, pooled, plus train-period JJAS event rate
used later as the event-rate baseline).
"""

import pandas as pd

from src.risk.common import BLOCKS, CHIRPS, JJAS, OUT, dry_runs

MIN_LENS = (5, 7, 10)


def load_chirps():
    df = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    assert df["rainfall_mm"].notna().all(), "missing rainfall must never silently become zero"
    return df


def runs_for(df: pd.DataFrame, min_len: int) -> pd.DataFrame:
    rows = []
    for b in BLOCKS:
        g = df[df.block == b].sort_values("date").reset_index(drop=True)
        for s, e, d in dry_runs(g["date"], g["rainfall_mm"].to_numpy(), min_len):
            rows.append({"block": b, "start": s.date().isoformat(), "end": e.date().isoformat(),
                         "duration": d, "jjas": int(s.month in JJAS),
                         "train": int(s < pd.Timestamp("2020-01-01")),
                         "eval_period": int(s >= pd.Timestamp("2021-01-01"))})
    out = pd.DataFrame(rows)
    assert (out["duration"] >= min_len).all()
    return out


def main() -> None:
    df = load_chirps()
    assert sorted(df.block.unique().tolist()) == sorted(BLOCKS)
    assert (df["date"].min(), df["date"].max()) == (pd.Timestamp("2010-01-01"), pd.Timestamp("2025-12-31"))
    OUT.mkdir(parents=True, exist_ok=True)
    for L in MIN_LENS:
        runs = runs_for(df, L)
        runs.to_csv(OUT / f"dryspell_runs_{L}d.csv", index=False)
        j = runs[runs.jjas == 1]
        t = runs[(runs.jjas == 1) & (runs["train"] == 1)]
        e = runs[(runs.jjas == 1) & (runs["eval_period"] == 1)]
        print(f"L={L}: total runs={len(runs)} JJAS={len(j)} "
              f"train-JJAS={len(t)} eval-JJAS={len(e)}")
        print("  JJAS runs/block:", j.groupby("block").size().to_dict())
        print("  train JJAS run-starts per block-year:",
              round(len(t) / (6 * 10), 3))
        print("  median duration (JJAS):", float(j["duration"].median()))


if __name__ == "__main__":
    main()
