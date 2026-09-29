"""MJO phase -> Sangrur rainfall anomaly composites (Phase 3A, Step 4).

Question: "When MJO is in phase p and active at issue date D, how does
Sangrur rainfall over subsequent weekly windows differ from climatology?"

Inputs (read-only):
  data/raw/mjo/MJO_RMM_cleaned_core.csv      (date, RMM1, RMM2, phase, amplitude)
  data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv
  data/processed/climatology/block_doy_normals_train_2010_2019.csv (causal)

Conventions (documented, not tuned):
  - active MJO: amplitude >= 1.0 (Wheeler-Hendon convention).
  - MJO provenance NOT verified (repo status preserved; not claimed here).
  - MJO state at issue date D uses the date-D observation. Operations incur
    ~2-3 d RMM latency; Phase 3B MUST lag this input (sensitivity: backtest
    repeats the candidate with state at D-3).
  - target windows: 7-day sums starting at offsets 3/10/17/24 from D
    (lead-scan documents the lag choice; product needs 17/24 = W3/W4).
  - anomaly: window sum minus climatological expected sum, where expected =
    sum of train daily_mean_mm over the window's 7 calendar dates.
    pct = 100 * anomaly / max(expected, eps).
  - fit period for ALL conditioning stats: 2010-01-01..2019-12-31 (train).
    Nothing from 2020+ enters any composite (backtest consistency).

GO / NO-GO GATE (pre-registered rule, evaluated on train JJAS, active MJO,
pooled-then-block-checked at W3 offset 17):
  GO iff ALL hold:
    (a) >= 2 phases with |mean pct anomaly| >= 15% AND 95% CI excludes 0;
    (b) each qualifying phase has the same sign in >= 5 of 6 blocks;
    (c) each qualifying phase keeps its sign in both halves
        (2010-2014 vs 2015-2019).
  Else NO-GO (MJO stays explanatory-only). No tuning to force GO.

Outputs (data/processed/climatology/):
  mjo_composites.csv   one row per (phase, active, season, offset, block)
  mjo_gate.json        gate inputs, per-phase stats, decision
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[2]
CHIRPS = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
MJO = REPO / "data" / "raw" / "mjo" / "MJO_RMM_cleaned_core.csv"
CLIM = REPO / "data" / "processed" / "climatology" / "block_doy_normals_train_2010_2019.csv"
OUT = REPO / "data" / "processed" / "climatology"
BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
ACTIVE_AMP = 1.0
OFFSETS = [3, 10, 17, 24]
TRAIN_END = "2020-01-01"
HALVES = [("2010-2014", "2010-01-01", "2015-01-01"), ("2015-2019", "2015-01-01", "2020-01-01")]


def main() -> None:
    rain = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    mjo = pd.read_csv(MJO, parse_dates=["date"])
    clim = pd.read_csv(CLIM)
    exp = {(r.block, int(r.doy)): float(r.daily_mean_mm) for r in clim.itertuples()}

    pivot = rain.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    mjo = mjo.set_index("date").sort_index()
    common = pivot.index.intersection(mjo.index)
    train_days = common[common < TRAIN_END]

    comp_rows = []
    for off in OFFSETS:
        starts = train_days
        # window sums + expected sums for every issue date with full window
        for D in starts:
            win = pd.date_range(D + pd.Timedelta(days=off), periods=7)
            if win[-1] > pivot.index.max():
                continue
            try:
                wsum = pivot.loc[win].sum()
            except KeyError:
                continue
            # align to builder wheel (Feb29->60; post-Feb +1 like the builder)
            wdoy = [60 if (d.month == 2 and d.day == 29) else _wheel(d) for d in win]
            prow = mjo.loc[D]
            for b in BLOCKS:
                e = sum(exp[(b, w)] for w in wdoy)
                a = float(wsum[b]) - e
                comp_rows.append(
                    {
                        "issue": D, "offset": off, "block": b,
                        "phase": int(prow["phase"]), "amp": float(prow["amplitude"]),
                        "active": bool(prow["amplitude"] >= ACTIVE_AMP),
                        "jjas": int(D.month in (6, 7, 8, 9)),
                        "anom_mm": a,
                        "expected_mm": e,
                        "pct": 100.0 * a / max(e, 1e-6),
                    }
                )
    comp = pd.DataFrame(comp_rows)
    comp.to_csv(OUT / "mjo_composite_issues_train.csv", index=False)

    # Aggregate: (phase, active, season, offset, block)
    agg = []
    for keys, g in comp.groupby(["phase", "active", "jjas", "offset", "block"]):
        v = g["anom_mm"].to_numpy()
        p = g["pct"].to_numpy()
        n = len(v)
        se = float(v.std(ddof=1) / np.sqrt(n)) if n > 1 else float("nan")
        agg.append(
            {
                "phase": keys[0], "active": bool(keys[1]), "jjas": keys[2],
                "offset": keys[3], "block": keys[4], "n": n,
                "mean_anom_mm": round(float(v.mean()), 3),
                "median_anom_mm": round(float(np.median(v)), 3),
                "mean_pct": round(float(p.mean()), 2),
                "ci_lo_mm": round(float(v.mean() - 1.96 * se), 3),
                "ci_hi_mm": round(float(v.mean() + 1.96 * se), 3),
                "frac_days_above_normal": round(float((v > 0).mean()), 3),
            }
        )
    aggdf = pd.DataFrame(agg)
    aggdf.to_csv(OUT / "mjo_composites.csv", index=False)

    # Gate on train JJAS, active, offset 17, pooled + block + halves
    gate_src = comp[(comp["jjas"] == 1) & (comp["active"]) & (comp["offset"] == 17)]
    phases = {}
    for p in range(1, 9):
        g = gate_src[gate_src["phase"] == p]
        v = g["anom_mm"].to_numpy()
        pp = g["pct"].to_numpy()
        n = len(v)
        se = float(v.std(ddof=1) / np.sqrt(n)) if n > 1 else float("nan")
        lo, hi = float(v.mean() - 1.96 * se), float(v.mean() + 1.96 * se)
        signs = {}
        for b in BLOCKS:
            bv = g[g["block"] == b]["anom_mm"].to_numpy()
            signs[b] = int(np.sign(bv.mean())) if len(bv) else 0
        halves = {}
        for name, s, e in HALVES:
            hv = g[(g["issue"] >= s) & (g["issue"] < e)]["anom_mm"].to_numpy()
            halves[name] = round(float(hv.mean()), 3) if len(hv) else None
        phases[p] = {
            "n_pooled": n, "n_per_block": n // 6,
            "mean_pct": round(float(pp.mean()), 2),
            "mean_mm": round(float(v.mean()), 3),
            "ci_mm": [round(lo, 3), round(hi, 3)],
            "ci_excludes_zero": bool(lo > 0 or hi < 0),
            "block_signs": signs,
            "n_blocks_same_sign_as_pooled": int(sum(1 for b in BLOCKS if signs[b] == int(np.sign(v.mean())))),
            "halves_mean_mm": halves,
            "halves_same_sign": bool(
                halves["2010-2014"] is not None and halves["2015-2019"] is not None
                and np.sign(halves["2010-2014"]) == np.sign(halves["2015-2019"])
                and halves["2010-2014"] != 0
            ),
        }
    qualifying = [
        p for p, s in phases.items()
        if abs(s["mean_pct"]) >= 15 and s["ci_excludes_zero"]
        and s["n_blocks_same_sign_as_pooled"] >= 5 and s["halves_same_sign"]
    ]
    decision = "GO" if len(qualifying) >= 2 else "NO-GO"
    gate = {
        "rule": "GO iff >=2 phases with |mean pct|>=15%, CI excludes 0, same sign in >=5/6 blocks, same sign in both halves (train JJAS, active, offset 17)",
        "scope": "train 2010-2019, JJAS issues, active (amp>=1.0), W3 offset 17",
        "active_amp_threshold": ACTIVE_AMP,
        "phases": phases,
        "qualifying_phases": qualifying,
        "decision": decision,
    }
    (OUT / "mjo_gate.json").write_text(json.dumps(gate, indent=2), encoding="utf-8")
    print(f"composite issues: {len(comp)} | agg rows: {len(aggdf)}")
    for p, s in phases.items():
        print(f"phase {p}: n={s['n_pooled']} mean_pct={s['mean_pct']}% ci={s['ci_mm']} "
              f"blocks_same={s['n_blocks_same_sign_as_pooled']}/6 halves={s['halves_mean_mm']} stablesign={s['halves_same_sign']}")
    print("DECISION:", decision, "| qualifying:", qualifying)


def _wheel(d: pd.Timestamp) -> int:
    base = pd.Timestamp(2021, d.month, d.day)
    return int(base.dayofyear) + (1 if base.dayofyear >= 60 else 0)


if __name__ == "__main__":
    main()
