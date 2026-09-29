"""Lightweight checks for Phase 3C Checkpoint 0 pure functions.

No data access, no fitting, no network. Run: python -m pytest src/w3w4/test_checkpoint0.py -q
"""

import numpy as np

from src.w3w4.checkpoint0 import brier, label, logloss, wheel_doy


def test_wheel_doy_feb29_maps_to_60():
    import pandas as pd

    assert wheel_doy(pd.Timestamp("2020-02-29")) == 60
    # byte-identical to Phase 3A wheel: Feb-28 -> 59, Feb-29 -> 60, Mar-01 -> 61
    assert wheel_doy(pd.Timestamp("2019-02-28")) == 59
    assert wheel_doy(pd.Timestamp("2021-03-01")) == 61


def test_three_classes_never_collapsed():
    assert label(5.0, 10.0, 20.0) == 0
    assert label(15.0, 10.0, 20.0) == 1
    assert label(25.0, 10.0, 20.0) == 2
    assert label(10.0, 10.0, 20.0) == 0  # boundary: x <= t33 -> BELOW
    assert label(20.0, 10.0, 20.0) == 1  # boundary: x <= t66 -> NEAR
    assert {0, 1, 2} == {label(5.0, 10.0, 20.0), label(15.0, 10.0, 20.0), label(25.0, 10.0, 20.0)}


def test_flat_prior_scores_chance():
    P = np.full((90, 3), 1 / 3)
    y = np.array([0, 1, 2] * 30)
    assert abs(brier(P, y) - 2 / 3) < 1e-9
    assert abs(logloss(P, y) - np.log(3)) < 1e-9


def test_perfect_forecast_beats_flat():
    y = np.array([0, 1, 2] * 10)
    P = np.eye(3)[y] * 0.98 + 0.01
    assert brier(P, y) < brier(np.full_like(P, 1 / 3), y)
