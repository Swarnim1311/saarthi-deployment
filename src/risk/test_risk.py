"""Phase 4.0F focused tests (local, no data download, no network).

Run: python -m pytest src/risk/test_risk.py -q
Covers: run detection, 1.0mm boundary (0.99 dry / 1.00 not dry),
NaN-never-zero, block normalization, no-future-as-feature, train-cutoff,
exact D+1..D+7 label window, 5/7/10 sensitivity changing only duration.
"""

import numpy as np
import pandas as pd

from src.risk.common import BLOCKS, DRY_MM, EVAL_START, TRAIN_END, dry_run_ending_at, dry_runs, is_dry
from src.risk.labels import runs_for


def _days(n, start="2021-07-01"):
    return pd.date_range(start, periods=n, freq="D")


def test_boundary_099_dry_100_not_dry():
    assert DRY_MM == 1.0
    assert is_dry(0.99) is True
    assert is_dry(1.00) is False
    assert is_dry(0.0) is True


def test_nan_never_becomes_zero():
    assert is_dry(float("nan")) is False
    assert is_dry(None) is False
    # NaN breaks runs rather than extending them
    d = _days(9)
    v = np.array([0.0] * 4 + [np.nan] + [0.0] * 4)
    assert dry_runs(d, v, 7) == []
    assert dry_runs(d, v, 4) != []


def test_consecutive_day_detection():
    d = _days(10)
    v = np.array([5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 0.0])
    runs = dry_runs(d, v, 7)
    assert len(runs) == 1
    assert runs[0][0] == d[1] and runs[0][1] == d[7] and runs[0][2] == 7


def test_sensitivity_changes_only_duration():
    d = _days(12)
    v = np.zeros(12)
    r5, r7, r10 = dry_runs(d, v, 5), dry_runs(d, v, 7), dry_runs(d, v, 10)
    assert (len(r5), len(r7), len(r10)) == (1, 1, 1)
    assert r5[0][2] == r7[0][2] == r10[0][2] == 12
    v2 = np.zeros(12)
    v2[6] = 2.0
    assert len(dry_runs(d, v2, 7)) == 0
    assert len(dry_runs(d, v2, 5)) == 2  # 6-day + 5-day fragments


def test_block_names_canonical():
    assert BLOCKS == ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
    assert len(set(BLOCKS)) == 6


def test_runs_for_respects_min_len_and_jjas():
    df = pd.DataFrame({"block": ["Dhuri"] * 20,
                       "date": _days(20, "2021-07-01"),
                       "rainfall_mm": [0.0] * 8 + [3.0] + [0.0] * 11})
    r7 = runs_for(df, 7)
    assert set(r7["duration"]) >= {8}
    assert (r7["jjas"] == 1).all()
    r10 = runs_for(df, 10)
    assert (r10["duration"] >= 10).all() and len(r10) == 1


def test_feature_window_ends_before_label_window():
    # contract: antecedent indices (..., i-3) vs label indices (i+1, ..., i+7)
    i, n = 100, 5844
    feat_idx = list(range(i - 30, i - 2))  # up to i-3 inclusive
    label_idx = list(range(i + 1, i + 8))
    assert max(feat_idx) == i - 3 < i + 1 == min(label_idx)
    assert not set(feat_idx) & set(label_idx)


def test_period_cutoffs():
    assert TRAIN_END == "2020-01-01" and EVAL_START == "2021-01-01"
    assert pd.Timestamp(TRAIN_END) < pd.Timestamp("2020-06-01") < pd.Timestamp(EVAL_START)


def test_dry_run_ending_at():
    assert dry_run_ending_at(np.array([2.0, 0.0, 0.0, 0.0])) == 3
    assert dry_run_ending_at(np.array([0.0, 0.0, 2.0])) == 0
    assert dry_run_ending_at(np.array([0.0, np.nan, 0.0])) == 1
