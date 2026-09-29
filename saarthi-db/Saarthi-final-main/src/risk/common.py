"""Phase 4.0 shared conventions (NO ML, NO network, NO live data).

Leakage rule (same as Phase 3A/3C): at issue date D, antecedent features may
only use CHIRPS dates <= D-FEAT_LAG. Future CHIRPS (D+1 onward) is allowed
ONLY to construct ground-truth labels, never as a feature.

Reference periods: TRAIN 2010-2019 (thresholds/references), 2020 excluded
transition year, EVAL 2021-2025.
"""

from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
CHIRPS = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
GEFS = REPO / "data" / "processed" / "historical" / "historical_gefs_leads_v2.csv"
GPKG = REPO / "data" / "raw" / "boundaries" / "sangrur_blocks_bhuvan.gpkg"
GPKG_LAYER = "sangrur_blocks"
SOIL_DIR = REPO / "data" / "raw" / "soil"
OUT = REPO / "data" / "processed" / "risk"

BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
DRY_MM = 1.0  # dry day: rainfall < 1.0 mm (1.00 mm is NOT dry). Never changed.
FEAT_LAG = 3  # antecedent windows end at D-FEAT_LAG (Checkpoint 0 convention)
TRAIN_END = "2020-01-01"  # train = dates < TRAIN_END
EVAL_START = "2021-01-01"  # eval = dates >= EVAL_START (2020 excluded)
JJAS = {6, 7, 8, 9}
BOOT_REPS = 2000
BOOT_SEED = 11


def is_dry(x: float) -> bool:
    """Strictly less than DRY_MM. NaN is never dry (missing != zero)."""
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return False
    return float(x) < DRY_MM


def dry_runs(dates, vals, min_len: int):
    """Return list of (start, end, duration) runs of consecutive dry days.

    `dates`: sequence of pd.Timestamp (ascending, consecutive days).
    `vals`: rainfall array aligned with dates. NaN breaks a run (never zero).
    """
    runs = []
    start = None
    prev = None
    for d, v in zip(dates, vals):
        if is_dry(v):
            if start is None:
                start = d
            prev = d
        else:
            if start is not None and (prev - start).days + 1 >= min_len:
                runs.append((start, prev, (prev - start).days + 1))
            start = None
            prev = None
    if start is not None and (prev - start).days + 1 >= min_len:
        runs.append((start, prev, (prev - start).days + 1))
    return runs


def dry_run_ending_at(vals_through_end: np.ndarray) -> int:
    """Length of trailing consecutive dry-day run ending at array end."""
    n = 0
    for v in vals_through_end[::-1]:
        if is_dry(v):
            n += 1
        else:
            break
    return n


def contingency(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    y_true = np.asarray(y_true, dtype=int)
    y_pred = np.asarray(y_pred, dtype=int)
    tp = int(((y_pred == 1) & (y_true == 1)).sum())
    fp = int(((y_pred == 1) & (y_true == 0)).sum())
    tn = int(((y_pred == 0) & (y_true == 0)).sum())
    fn = int(((y_pred == 0) & (y_true == 1)).sum())
    prev = (tp + fn) / max(len(y_true), 1)
    prec = tp / (tp + fp) if (tp + fp) else float("nan")
    rec = tp / (tp + fn) if (tp + fn) else float("nan")
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) and not np.isnan(prec + rec) else float("nan")
    far = fp / (fp + tn) if (fp + tn) else float("nan")
    miss = fn / (tp + fn) if (tp + fn) else float("nan")
    return {"n": int(len(y_true)), "prevalence": round(prev, 4), "TP": tp, "FP": fp,
            "TN": tn, "FN": fn, "precision": _r(prec), "recall": _r(rec),
            "f1": _r(f1), "false_alarm_rate": _r(far), "miss_rate": _r(miss)}


def _r(x):
    return round(float(x), 4) if x == x else None


def boot_ci_issue_grouped(issues: np.ndarray, y_true: np.ndarray, y_pred: np.ndarray,
                          stat: str = "f1", reps: int = BOOT_REPS, seed: int = BOOT_SEED) -> dict:
    """Issue-clustered bootstrap 95% CI for a contingency stat.

    Resamples issue dates with replacement (all 6 blocks of a sampled issue
    travel together). Never treats blocks of one issue as independent.
    """
    rng = np.random.default_rng(seed)
    u = np.unique(issues)
    vals = np.empty(reps)
    for r in range(reps):
        samp = rng.choice(u, size=len(u), replace=True)
        # duplicated sampled issues contribute duplicated rows (correct bootstrap)
        idx = np.concatenate([np.flatnonzero(issues == s) for s in samp])
        c = contingency(y_true[idx], y_pred[idx])
        vals[r] = c[stat] if c[stat] is not None else np.nan
    return {"lo": _r(float(np.nanpercentile(vals, 2.5))), "hi": _r(float(np.nanpercentile(vals, 97.5)))}
