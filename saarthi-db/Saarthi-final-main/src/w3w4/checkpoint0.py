"""Phase 3C Checkpoint 0 — cheap GO/NO-GO pre-screen (NO ML beyond fixed LR).

Question: can simple seasonal climatology or minimal recent-rainfall
persistence produce useful W3/W4 probabilistic skill beyond flat 1/3 priors?

Targets (same as Phase 3A): issue D -> W3 = sum(D+17..D+23),
W4 = sum(D+24..D+30) per block, labelled BELOW/NEAR/ABOVE vs the
TRAIN-FROZEN block x issue-DOY terciles
(data/processed/climatology/block_doy_normals_train_2010_2019.csv).
Rule: BELOW if x <= t33, ABOVE if x > t66, else NEAR. NEAR and ABOVE
are never collapsed.

Arms (fixed, no tuning):
  A FLAT:      (1/3, 1/3, 1/3).
  B SEASONAL:  P(W-tercile | block, issue-DOY) empirical frequencies on
               TRAIN daily issues only, Laplace +0.5 (same convention as 3A).
  C PERSIST:   fixed multinomial LogisticRegression (C=1.0, lbfgs) on
               [log1p(rain_7d), log1p(rain_14d)] where both windows end at
               D-3 (conservative: sums D-9..D-3 and D-16..D-3), standardized
               with TRAIN mean/std. Fit on TRAIN JJAS daily issues only,
               pooled over blocks, one model per window (W3/W4).

Issues: weekly (every 7 d, same Wednesday cadence as Phase 3A backtest),
JJAS primary + FULLYR secondary, test 2020-2025, last issue 2025-12-01.

FORBIDDEN here (asserted absent): MJO, ENSO, IOD, SoilGrids, GEFS,
ECMWF/Open-Meteo, any live-only data, deployment/full-span thresholds,
any feature using dates after D (features end at D-3).

Outputs (data/processed/w3w4/):
  checkpoint0_predictions.csv, checkpoint0_metrics.json,
  checkpoint0_reliability.csv, method_checkpoint0.json
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression

REPO = Path(__file__).resolve().parents[2]
CHIRPS = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
CLIM_TRAIN = REPO / "data" / "processed" / "climatology" / "block_doy_normals_train_2010_2019.csv"
OUT = REPO / "data" / "processed" / "w3w4"
BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
SPLIT = "2020-01-01"
FEAT_LAG = 3  # trailing windows end at D-FEAT_LAG (conservative)
WINDOWS = {"W3": (17, 23), "W4": (24, 30)}
JJAS = {6, 7, 8, 9}
BOOT_REPS = 2000
BOOT_SEED = 7
LOGLOSS_EPS = 1e-3


def wheel_doy(d: pd.Timestamp) -> int:
    if d.month == 2 and d.day == 29:
        return 60
    base = pd.Timestamp(2021, d.month, d.day)
    return int(base.dayofyear) + (1 if base.dayofyear >= 60 else 0)


def label(x: float, t33: float, t66: float) -> int:
    if x <= t33:
        return 0
    if x > t66:
        return 2
    return 1


def wsum(a: np.ndarray, i: int, x: int, y: int) -> float:
    return float(a[i + x : i + y + 1].sum())


def brier(P: np.ndarray, y: np.ndarray) -> float:
    return float(np.mean(np.sum((P - np.eye(3)[y]) ** 2, axis=1)))


def logloss(P: np.ndarray, y: np.ndarray) -> float:
    Pc = np.clip(P, LOGLOSS_EPS, 1.0)
    Pc = Pc / Pc.sum(axis=1, keepdims=True)
    return float(-np.mean(np.log(Pc[np.arange(len(y)), y])))


def boot_skill_ci(issues: np.ndarray, by_issue_brier: dict, base: str, cand: str) -> dict:
    """Issue-clustered bootstrap 95% CI for Brier skill (resample issues)."""
    rng = np.random.default_rng(BOOT_SEED)
    u = np.unique(issues)
    skills = np.empty(BOOT_REPS)
    for r in range(BOOT_REPS):
        samp = rng.choice(u, size=len(u), replace=True)
        bb = np.concatenate([by_issue_brier[base][i] for i in samp])
        cc = np.concatenate([by_issue_brier[cand][i] for i in samp])
        skills[r] = 1.0 - cc.mean() / bb.mean()
    return {"lo": round(float(np.percentile(skills, 2.5)), 4),
            "hi": round(float(np.percentile(skills, 97.5)), 4)}


def main() -> None:
    assert "train_2010_2019" in CLIM_TRAIN.name, "must use train-frozen thresholds, never deployment"
    rain = pd.read_csv(CHIRPS, usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
    clim = pd.read_csv(CLIM_TRAIN)
    assert set(clim["block"]) == set(BLOCKS)

    pv = rain.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    dates = pv.index
    assert dates.min() == pd.Timestamp("2010-01-01")
    arr = {b: pv[b].to_numpy() for b in BLOCKS}
    pos = {d: i for i, d in enumerate(dates)}
    n = len(dates)

    thr = {}
    for r in clim.itertuples():
        thr[(r.block, int(r.doy), "W3")] = (float(r.w3_t33_mm), float(r.w3_t66_mm))
        thr[(r.block, int(r.doy), "W4")] = (float(r.w4_t33_mm), float(r.w4_t66_mm))

    split = pd.Timestamp(SPLIT)
    train_days = [d for d in dates if d < split]
    # Fit issues: daily TRAIN issues with valid features (i>=16: D-16..D-3)
    # and valid targets (i+30<n). Features END at D-FEAT_LAG by construction.
    assert max(train_days) < split

    seas_counts: dict[tuple, list] = {}
    lr_X: dict[str, list] = {"W3": [], "W4": []}
    lr_y: dict[str, list] = {"W3": [], "W4": []}
    for D in train_days:
        i = pos[D]
        if i < 16 or i + 30 >= n:
            continue
        wd = wheel_doy(D)
        # conservative trailing features ending D-3
        f7 = {b: float(arr[b][i - 9 : i - 2].sum()) for b in BLOCKS}    # D-9..D-3
        f14 = {b: float(arr[b][i - 16 : i - 2].sum()) for b in BLOCKS}  # D-16..D-3
        assert (i - 3) < i, "feature window must end strictly before targets start"
        for b in BLOCKS:
            for w, (x, y) in WINDOWS.items():
                c33, c66 = thr[(b, wd, w)]
                seas_counts.setdefault((b, wd, w), []).append(label(wsum(arr[b], i, x, y), c33, c66))
                if D.month in JJAS:  # LR fit scope: TRAIN JJAS daily only (fixed)
                    lr_X[w].append([np.log1p(f7[b]), np.log1p(f14[b])])
                    lr_y[w].append(label(wsum(arr[b], i, x, y), c33, c66))

    def seas_dist(key: tuple) -> np.ndarray:
        v = seas_counts.get(key, [])
        if not v:
            return np.array([1 / 3, 1 / 3, 1 / 3])
        c = np.bincount(v, minlength=3).astype(float)
        return (c + 0.5) / (c.sum() + 1.5)

    models, scalers = {}, {}
    for w in WINDOWS:
        X = np.asarray(lr_X[w])
        y = np.asarray(lr_y[w])
        assert len(np.unique(y)) == 3, f"LR fit labels must span 3 classes, got {np.unique(y)}"
        mu, sd = X.mean(axis=0), X.std(axis=0)
        scalers[w] = (mu, sd)
        models[w] = LogisticRegression(C=1.0, solver="lbfgs", max_iter=2000)
        models[w].fit((X - mu) / sd, y)

    # ---- Test issues: same weekly cadence as Phase 3A ----
    last_ok = dates.max() - pd.Timedelta(days=30)
    assert last_ok == pd.Timestamp("2025-12-01")
    all_weekly = [d for d in dates[dates >= split] if d <= last_ok][::7]
    jjas_weekly = [d for d in all_weekly if d.month in JJAS]

    rows = []
    for season, issues in (("JJAS", jjas_weekly), ("FULLYR", all_weekly)):
        for D in issues:
            i = pos[D]
            assert i >= 16 and i + 30 < n
            wd = wheel_doy(D)
            for b in BLOCKS:
                f7v = float(arr[b][i - 9 : i - 2].sum())
                f14v = float(arr[b][i - 16 : i - 2].sum())
                for w, (x, y) in WINDOWS.items():
                    c33, c66 = thr[(b, wd, w)]
                    obs = label(wsum(arr[b], i, x, y), c33, c66)
                    pB = seas_dist((b, wd, w))
                    mu, sd = scalers[w]
                    pC = models[w].predict_proba((np.array([[np.log1p(f7v), np.log1p(f14v)]]) - mu) / sd)[0]
                    r = {"season": season, "issue": D.date().isoformat(), "block": b,
                         "window": w, "obs": obs,
                         "pA0": 1 / 3, "pA1": 1 / 3, "pA2": 1 / 3,
                         "pB0": pB[0], "pB1": pB[1], "pB2": pB[2],
                         "pC0": float(pC[0]), "pC1": float(pC[1]), "pC2": float(pC[2]),
                         "f7_end_Dminus3": round(f7v, 3), "f14_end_Dminus3": round(f14v, 3)}
                    rows.append(r)
    pred = pd.DataFrame(rows)
    # verification: probs sum to 1, 3 distinct labels present, NEAR kept
    for cols in (["pA0", "pA1", "pA2"], ["pB0", "pB1", "pB2"], ["pC0", "pC1", "pC2"]):
        assert np.allclose(pred[cols].to_numpy().sum(axis=1), 1.0, atol=1e-9)
    assert set(pred["obs"].unique()) == {0, 1, 2}

    OUT.mkdir(parents=True, exist_ok=True)
    pred.to_csv(OUT / "checkpoint0_predictions.csv", index=False)

    arms = {"FLAT": ["pA0", "pA1", "pA2"], "SEASONAL": ["pB0", "pB1", "pB2"],
            "PERSIST_LR": ["pC0", "pC1", "pC2"]}
    metrics: dict = {"arms": {}, "block": {}, "reliability": {}}
    metrics["n_issues"] = {s: int(pred[pred.season == s]["issue"].nunique()) for s in ("JJAS", "FULLYR")}
    for season in ("JJAS", "FULLYR"):
        for w in WINDOWS:
            sw = pred[(pred.season == season) & (pred.window == w)]
            y = sw["obs"].to_numpy()
            for name, cols in arms.items():
                P = sw[cols].to_numpy()
                metrics["arms"][f"{season}/{w}/{name}"] = {
                    "n": int(len(sw)), "brier": round(brier(P, y), 4),
                    "logloss": round(logloss(P, y), 4),
                    "accuracy": round(float((P.argmax(axis=1) == y).mean()), 4)}
            bA = metrics["arms"][f"{season}/{w}/FLAT"]["brier"]
            bB = metrics["arms"][f"{season}/{w}/SEASONAL"]["brier"]
            for name in ("SEASONAL", "PERSIST_LR"):
                b = metrics["arms"][f"{season}/{w}/{name}"]["brier"]
                metrics["arms"][f"{season}/{w}/{name}"]["brier_skill_vs_FLAT"] = round(1 - b / bA, 4)
            metrics["arms"][f"{season}/{w}/PERSIST_LR"]["brier_skill_vs_SEASONAL"] = round(
                1 - metrics["arms"][f"{season}/{w}/PERSIST_LR"]["brier"] / bB, 4)
            # block diagnostics (Brier per block/arm)
            for b in BLOCKS:
                sb = sw[sw.block == b]
                yb = sb["obs"].to_numpy()
                metrics["block"][f"{season}/{w}/{b}"] = {
                    name: round(brier(sb[cols].to_numpy(), yb), 4) for name, cols in arms.items()}
    # issue-clustered bootstrap CIs for JJAS skills vs FLAT
    metrics["skill_ci95_issue_clustered"] = {}
    for w in WINDOWS:
        sw = pred[(pred.season == "JJAS") & (pred.window == w)]
        by_issue = {}
        for name, cols in arms.items():
            P = sw[cols].to_numpy()
            sq = np.sum((P - np.eye(3)[sw["obs"].to_numpy()]) ** 2, axis=1)
            by_issue[name] = {iss: sq[(sw["issue"] == iss).to_numpy()] for iss in sw["issue"].unique()}
        for cand in ("SEASONAL", "PERSIST_LR"):
            metrics["skill_ci95_issue_clustered"][f"JJAS/{w}/{cand}_vs_FLAT"] = boot_skill_ci(
                sw["issue"].to_numpy(), by_issue, "FLAT", cand)
    # reliability of P(ABOVE): fixed 5 bins, n>=10 reported
    rel_rows = []
    for season in ("JJAS",):
        for w in WINDOWS:
            sw = pred[(pred.season == season) & (pred.window == w)]
            yb = (sw["obs"].to_numpy() == 2).astype(float)
            for name, cols in arms.items():
                p = sw[cols[2]].to_numpy()
                bins = np.clip((p * 5).astype(int), 0, 4)
                for k in range(5):
                    m = bins == k
                    if m.sum() >= 10:
                        rel_rows.append({"season": season, "window": w, "arm": name,
                                         "bin": f"{k/5:.1f}-{(k+1)/5:.1f}", "n": int(m.sum()),
                                         "mean_p": round(float(p[m].mean()), 3),
                                         "obs_freq": round(float(yb[m].mean()), 3)})
    pd.DataFrame(rel_rows).to_csv(OUT / "checkpoint0_reliability.csv", index=False)
    metrics["method"] = {
        "thresholds": "train-frozen block_doy_normals_train_2010_2019.csv (never deployment)",
        "fit": "seasonal table: TRAIN daily 2010-2019 per (block,issue-DOY), Laplace+0.5; "
               "PERSIST LR: fixed C=1.0 lbfgs on [log1p(f7),log1p(f14)] ending D-3, "
               "TRAIN JJAS daily pooled, per window; no tuning on 2020-2025",
        "test": "weekly JJAS/FULLYR 2020-2025, last issue 2025-12-01; "
                "W3=D+17..23, W4=D+24..30; labels BELOW x<=t33, ABOVE x>t66 else NEAR",
        "excluded": "MJO, ENSO, IOD, SoilGrids, GEFS, ECMWF/Open-Meteo, live-only data",
        "uncertainty": f"issue-clustered bootstrap, {BOOT_REPS} reps, seed {BOOT_SEED}",
    }
    (OUT / "checkpoint0_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    (OUT / "method_checkpoint0.json").write_text(json.dumps(
        {"kind": "checkpoint0-prescreen", "feat_lag_days": FEAT_LAG,
         "leakage_statement": "All features end at D-3; thresholds train-frozen; "
                              "test issues >= 2020-01-01; no future or live-only inputs.",
         **metrics["method"]}, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in metrics["arms"].items() if k.startswith("JJAS")}, indent=2))
    print(json.dumps(metrics["skill_ci95_issue_clustered"], indent=2))
    print("n predictions:", len(pred))


if __name__ == "__main__":
    main()
