"""W3/W4 tercile backtest (Phase 3A, Steps 5-7). NO ML — transparent conditional
frequency arms only. Existing MJO CNN-LSTM stays frozen (unused here).

Targets: issue date D -> W3 = sum(D+17..D+23), W4 = sum(D+24..D+30) per block,
labelled below/near/above vs TRAIN climatology terciles for the issue DOY
(w3_t33/t66, w4_t33/t66 from block_doy_normals_train_2010_2019.csv).
Rule: below if x <= t33, above if x > t66, else near. Boundary ties are
deterministic by this rule (documented; JJAS windows are rarely exactly 0).

Arms (all conditioning stats fit on 2010-01-01..2019-12-31 ONLY):
  CLIM:          (1/3, 1/3, 1/3).
  CLIM+RECENT:   P(W-tercile | trailing-14d-sum tercile at D), train contingency.
                 Trailing window D-14..D-1 (strictly before D); its terciles come
                 from the train trailing-14d distribution per issue DOY.
  CLIM+RECENT+MJO: independence-product (linear opinion pool alternative
                 rejected as less principled): elementwise P_recent * P_mjo,
                 renormalized, where P_mjo = P(W-tercile | active MJO phase at D)
                 from train (weak MJO -> climatology). The conditional-
                 independence assumption is NOT claimed true; the backtest
                 itself judges whether the product helps.
  Sensitivity: MJO state at D (primary) and at D-3 (operations latency proxy).

Issues: weekly (every 7 d) JJAS 2020-2025 (primary) + weekly full-year
(secondary). Last issue 2025-12-01 (need D+30 observed <= 2025-12-31).

Metrics per arm/window/season: multiclass Brier, RPS (ordinal terciles),
accuracy, confusion matrix, Brier skill vs CLIM, reliability of P(above)
in 5 bins. Leakage assertions: fit maxima < 2020-01-01; thresholds from the
train-frozen artifact only.

Outputs: backtest_predictions.csv, backtest_metrics.json
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
SPLIT = "2020-01-01"
LABELS = ["below", "near", "above"]


def wsum(arr: np.ndarray, i: int, a: int, b: int) -> float:
    return float(arr[i + a : i + b + 1].sum())


def terciles_for(doy: int, window: str, lut: dict) -> tuple[float, float]:
    # nearest-DOY lookup in train table per block handled by caller
    return lut[window]


def main() -> None:
    rain = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    mjo = pd.read_csv(MJO, parse_dates=["date"]).set_index("date").sort_index()
    clim = pd.read_csv(CLIM)
    assert (clim["block"].unique().tolist() == BLOCKS) or set(clim["block"]) == set(BLOCKS)

    pv = rain.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    dates = pv.index
    train_mask = dates < SPLIT
    assert train_mask.any() and (~train_mask).any()

    # Threshold LUT: (block, doy) -> (t33, t66) per window, train artifact only
    thr = {}
    for r in clim.itertuples():
        thr[(r.block, int(r.doy), "W3")] = (float(r.w3_t33_mm), float(r.w3_t66_mm))
        thr[(r.block, int(r.doy), "W4")] = (float(r.w4_t33_mm), float(r.w4_t66_mm))

    arr = {b: pv[b].to_numpy() for b in BLOCKS}
    dlist = dates.to_numpy()
    pos = {d: i for i, d in enumerate(dates)}

    def label(x: float, t33: float, t66: float) -> int:
        if x <= t33:
            return 0
        if x > t66:
            return 2
        return 1

    def wheel_doy(d: pd.Timestamp) -> int:
        if d.month == 2 and d.day == 29:
            return 60
        base = pd.Timestamp(2021, d.month, d.day)
        return int(base.dayofyear) + (1 if base.dayofyear >= 60 else 0)

    # ---- Fit conditioning tables on TRAIN only ----
    train_issues = [d for d in dates[train_mask] if d + pd.Timedelta(days=30) <= dates.max()]
    # trailing-14d terciles per (block, doy): collect then quantile
    trail_vals: dict[tuple, list] = {}
    rec_cont: dict[tuple, list] = {}   # (block, window, trail_terc) -> [w_terc...]
    mjo_cont: dict[tuple, list] = {}   # (block, window, phase|0) -> [w_terc...]
    for D in train_issues:
        i = pos[D]
        wd = wheel_doy(D)
        prow = mjo.loc[D] if D in mjo.index else None
        for b in BLOCKS:
            a = arr[b]
            if i < 14:
                continue
            t14 = float(a[i - 14 : i].sum())
            trail_vals.setdefault((b, wd), []).append(t14)
    trail_thr = {k: (float(np.percentile(v, 33)), float(np.percentile(v, 66))) for k, v in trail_vals.items() if len(v) >= 5}
    for D in train_issues:
        i = pos[D]
        wd = wheel_doy(D)
        if D not in mjo.index:
            continue
        prow = mjo.loc[D]
        ph = int(prow["phase"])
        active = bool(prow["amplitude"] >= 1.0)
        for b in BLOCKS:
            a = arr[b]
            if i < 14 or i + 30 >= len(a):
                continue
            t33, t66 = trail_thr.get((b, wd), (np.nan, np.nan))
            if np.isnan(t33):
                continue
            tt = label(float(a[i - 14 : i].sum()), t33, t66)
            for w, (x, y) in (("W3", (17, 23)), ("W4", (24, 30))):
                c33, c66 = thr[(b, wd, w)]
                wt = label(wsum(a, i, x, y), c33, c66)
                rec_cont.setdefault((b, w, tt), []).append(wt)
                mjo_cont.setdefault((b, w, ph if active else 0), []).append(wt)

    def dist(counter: dict, key: tuple) -> np.ndarray:
        v = counter.get(key, [])
        if not v:
            return np.array([1 / 3, 1 / 3, 1 / 3])
        c = np.bincount(v, minlength=3).astype(float)
        return (c + 0.5) / (c.sum() + 1.5)  # Laplace smoothing, documented

    # Leakage assertions
    assert max(train_issues) < pd.to_datetime(SPLIT), "train issues leak into test"

    # ---- Test issues ----
    last_ok = dates.max() - pd.Timedelta(days=30)
    all_weekly = [d for d in dates[~train_mask] if d <= last_ok]
    all_weekly = all_weekly[::7]
    jjas_weekly = [d for d in all_weekly if d.month in (6, 7, 8, 9)]

    pred_rows = []
    for season, issues in (("JJAS", jjas_weekly), ("FULLYR", all_weekly)):
        for D in issues:
            i = pos[D]
            wd = wheel_doy(D)
            for lag in (0, 3):
                DD = D - pd.Timedelta(days=lag)
                if DD not in mjo.index:
                    continue
                prow = mjo.loc[DD]
                ph = int(prow["phase"])
                active = bool(prow["amplitude"] >= 1.0)
                for b in BLOCKS:
                    a = arr[b]
                    t33, t66 = trail_thr.get((b, wd), (np.nan, np.nan))
                    if np.isnan(t33):
                        continue
                    tt = label(float(a[i - 14 : i].sum()), t33, t66)
                    for w, (x, y) in (("W3", (17, 23)), ("W4", (24, 30))):
                        c33, c66 = thr[(b, wd, w)]
                        obs = label(wsum(a, i, x, y), c33, c66)
                        p_r = dist(rec_cont, (b, w, tt))
                        p_m = dist(mjo_cont, (b, w, ph if active else 0))
                        prod = p_r * p_m
                        p_rm = prod / prod.sum()
                        pred_rows.append(
                            {
                                "season": season, "issue": D.date().isoformat(), "block": b,
                                "window": w, "mjo_lag": lag, "obs": obs,
                                "pC0": 1 / 3, "pC1": 1 / 3, "pC2": 1 / 3,
                                "pR0": p_r[0], "pR1": p_r[1], "pR2": p_r[2],
                                "pRM0": p_rm[0], "pRM1": p_rm[1], "pRM2": p_rm[2],
                            }
                        )
    pred = pd.DataFrame(pred_rows)
    pred.to_csv(OUT / "backtest_predictions.csv", index=False)

    # ---- Metrics ----
    arms = {"CLIM": ("pC0", "pC1", "pC2"), "CLIM+RECENT": ("pR0", "pR1", "pR2"),
            "CLIM+RECENT+MJO": ("pRM0", "pRM1", "pRM2")}
    metrics = {"n_issues": {}, "arms": {}}
    for season in ("JJAS", "FULLYR"):
        s = pred[pred.season == season]
        metrics["n_issues"][season] = int(s["issue"].nunique())
        for lag in (0, 3):
            sl = s[s.mjo_lag == lag]
            for w in ("W3", "W4"):
                sw = sl[sl.window == w]
                y = sw["obs"].to_numpy()
                oh = np.eye(3)[y]
                for name, cols in arms.items():
                    P = sw[list(cols)].to_numpy()
                    brier = float(np.mean(np.sum((P - oh) ** 2, axis=1)))
                    # RPS for ordered terciles
                    C = np.cumsum(P, axis=1)[:, :2]
                    O = np.cumsum(oh, axis=1)[:, :2]
                    rps = float(np.mean(np.sum((C - O) ** 2, axis=1)))
                    acc = float((P.argmax(axis=1) == y).mean())
                    key = f"{season}/{w}/mjolag{lag}/{name}"
                    metrics["arms"][key] = {
                        "n": int(len(sw)), "brier": round(brier, 4), "rps": round(rps, 4),
                        "accuracy": round(acc, 4),
                    }
    # skill vs CLIM + confusion + reliability for JJAS lag0
    for w in ("W3", "W4"):
        base = metrics["arms"][f"JJAS/{w}/mjolag0/CLIM"]["brier"]
        for name in ("CLIM+RECENT", "CLIM+RECENT+MJO"):
            b = metrics["arms"][f"JJAS/{w}/mjolag0/{name}"]["brier"]
            metrics["arms"][f"JJAS/{w}/mjolag0/{name}"]["brier_skill_vs_clim"] = round(1 - b / base, 4)
    s = pred[(pred.season == "JJAS") & (pred.mjo_lag == 0)]
    metrics["confusion"] = {}
    for w in ("W3", "W4"):
        sw = s[s.window == w]
        for name, cols in arms.items():
            P = sw[list(cols)].to_numpy()
            cm = pd.crosstab(sw["obs"], P.argmax(axis=1)).reindex(index=[0, 1, 2], columns=[0, 1, 2], fill_value=0)
            metrics["confusion"][f"{w}/{name}"] = cm.values.tolist()
    metrics["reliability_P_above"] = {}
    for w in ("W3", "W4"):
        sw = s[s.window == w]
        yb = (sw["obs"].to_numpy() == 2).astype(float)
        for name, cols in arms.items():
            p = sw[cols[2]].to_numpy()
            bins = np.clip((p * 5).astype(int), 0, 4)
            rel = []
            for k in range(5):
                m = bins == k
                if m.sum() >= 10:
                    rel.append({"bin": f"{k/5:.1f}-{(k+1)/5:.1f}", "n": int(m.sum()),
                                "mean_p": round(float(p[m].mean()), 3), "obs_freq": round(float(yb[m].mean()), 3)})
            metrics["reliability_P_above"][f"{w}/{name}"] = rel
    metrics["notes"] = {
        "split": "fit 2010-2019, test JJAS/full-year weekly 2020-2025 (last issue 2025-12-01)",
        "laplace": "conditional tables Laplace-smoothed (+0.5); empty key -> (1/3,1/3,1/3)",
        "combination": "independence product renormalized; assumption judged by backtest, not claimed",
        "mjo_state": "primary lag0 (date-D obs); lag3 = operations-latency proxy",
    }
    (OUT / "backtest_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in metrics["arms"].items() if "mjolag0" in k and ("JJAS" in k)}, indent=2))
    print("n predictions:", len(pred))


if __name__ == "__main__":
    main()
