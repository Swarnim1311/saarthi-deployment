"""Phase 4.1 focused tests (local, no network, no downloads, no tuning).

Run: python -m pytest src/risk/test_phase41.py -q
Covers the 17 checkpoint items: excess threshold boundaries, rolling-window
math, block specificity, train-cutoff isolation, missing-never-zero,
field-work tiers + 1mm boundary, prob-never-observed, window exactness,
leakage guards, and GEFS/IFS namespace separation.
"""

import json

import numpy as np
import pandas as pd

from src.risk.common import BLOCKS
from src.risk.excess_rain import (METHOD_VERSION, THRESHOLD_PATH,
                                  build_thresholds, detect_excess,
                                  load_thresholds)
from src.risk.field_work import (PROB_FLAG_PCT, WET_MM, classify_3d,
                                 describe_7d, is_wet, observed_3d)

THR = {"daily_p95": 20.0, "d3_p90": 35.0, "d7_p90": 80.0}


# Excess rainfall 1-3: threshold boundaries ----------------------------------
def test_daily_boundary_strictly_exceeds():
    assert detect_excess([20.0] + [0.0] * 6, THR)["daily"]["triggered"] is False
    assert detect_excess([20.001] + [0.0] * 6, THR)["daily"]["triggered"] is True
    assert detect_excess([19.999] + [0.0] * 6, THR)["daily"]["triggered"] is False


def test_below_threshold_no_trigger():
    det = detect_excess([5.0] * 7, THR)
    assert all(det[k]["triggered"] is False for k in ("daily", "d3", "d7"))
    assert det["daily"]["reason_code"] == "EXCESS_DAILY_P95"


def test_above_threshold_triggers_with_full_result_shape():
    det = detect_excess([30.0] * 7, THR)
    for k, metric, pct, reason in (
            ("daily", "daily_max_mm", 95, "EXCESS_DAILY_P95"),
            ("d3", "rolling_3d_max_mm", 90, "EXCESS_3D_P90"),
            ("d7", "accum_7d_mm", 90, "EXCESS_7D_P90")):
        c = det[k]
        assert c["triggered"] is True
        assert c["metric"] == metric and c["percentile"] == pct
        assert c["reason_code"] == reason
        for field in ("block", "issue_date", "forecast_window", "method_version"):
            assert field not in c, "caller attaches envelope fields, not the detector"


# Excess 4-5: rolling math ----------------------------------------------------
def test_rolling_3d_uses_max_of_five_windows():
    f7 = [0.0, 0.0, 12.0, 12.0, 12.0, 0.0, 0.0]  # peak window idx 2..4 = 36
    det = detect_excess(f7, THR)
    assert det["d3"]["forecast_value"] == 36.0
    assert det["d3"]["triggered"] is True
    det2 = detect_excess([0.0, 11.0, 11.0, 11.0, 0.0, 0.0, 0.0], THR)
    assert det2["d3"]["forecast_value"] == 33.0
    assert det2["d3"]["triggered"] is False


def test_7d_accumulation_is_full_window_sum():
    f7 = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0]
    det = detect_excess(f7, THR)
    assert det["d7"]["forecast_value"] == 70.0
    assert det["d7"]["triggered"] is False
    det = detect_excess([12.0] * 7, THR)
    assert det["d7"]["forecast_value"] == 84.0
    assert det["d7"]["triggered"] is True


# Excess 6: block specificity --------------------------------------------------
def test_block_specific_thresholds_loaded():
    thr = load_thresholds()
    assert thr["method_version"] == METHOD_VERSION
    vals = [thr["blocks"][b]["daily_p95"] for b in BLOCKS]
    assert len(set(vals)) > 1, "blocks must not share one pooled threshold"
    same_forecast = [19.0] + [0.0] * 6
    results = {b: detect_excess(same_forecast, thr["blocks"][b])["daily"]["triggered"]
               for b in BLOCKS}
    assert any(results.values()) and not all(results.values()), \
        "19mm must trigger some blocks (Moonak 17.6) but not others (Sangrur 21.2)"


# Excess 7: training cutoff ----------------------------------------------------
def test_threshold_fit_excludes_eval_years():
    thr = load_thresholds()
    assert thr["fit_period"] == "2010-2019"
    df = pd.read_csv("data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv",
                     usecols=["date"], parse_dates=["date"])
    only_train = df[df["date"] < "2020-01-01"]
    rebuilt = build_thresholds(
        pd.read_csv("data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv",
                    usecols=["block", "date", "rainfall_mm"], parse_dates=["date"])
        .assign(block=lambda d: d["block"].replace({"Lehragaga": "Lehra"})))
    assert rebuilt["blocks"] == thr["blocks"], "thresholds must reproduce from train only"
    assert (only_train["date"].dt.year <= 2019).all()


# Excess 8: missing never zero --------------------------------------------------
def test_missing_rainfall_never_zero_in_excess():
    # a missing day could hide a higher value -> components needing it are unavailable
    det = detect_excess([30.0] * 6 + [None], THR)
    assert det["daily"]["forecast_value"] is None and det["daily"]["triggered"] is None
    det = detect_excess([5.0] * 6 + [None], THR)
    assert det["d7"]["forecast_value"] is None and det["d7"]["triggered"] is None
    assert det["d3"]["triggered"] is None
    assert "UNAVAILABLE" in det["d7"]["reason_code"]


# Field work 9-11: tiers ---------------------------------------------------------
def test_field_tiers_0_1_2_wet_days():
    assert classify_3d([0.0, 0.0, 0.0])["tier"] == "LOW"
    assert classify_3d([0.0, 5.0, 0.0])["tier"] == "MODERATE"
    assert classify_3d([5.0, 0.0, 5.0])["tier"] == "HIGH"
    assert classify_3d([5.0, 5.0, 5.0])["tier"] == "HIGH"
    assert classify_3d([0.0, 0.0, 0.0])["reason_codes"] == ["FIELD_DRY_3D"]
    assert classify_3d([5.0, 0.0, 5.0])["reason_codes"] == ["FIELD_WET_DAYS_2OF3"]


# Field work 12: 1mm boundary ------------------------------------------------------
def test_exact_1mm_is_wet():
    assert WET_MM == 1.0
    assert is_wet(1.00) is True
    assert is_wet(0.99) is False
    assert is_wet(None) is None
    assert classify_3d([1.0, 0.0, 0.0])["tier"] == "MODERATE"


# Field work 13: prob never observed -----------------------------------------------
def test_probability_is_evidence_only():
    r = classify_3d([0.0, 0.0, 0.0], None, [95.0, 95.0, 95.0])
    assert r["tier"] == "LOW" and r["wet_days"] == 0, \
        "high probability must not fabricate wet days"
    assert r["high_prob"] is True
    assert "FIELD_HIGH_PRECIP_PROB" in r["reason_codes"]
    r2 = classify_3d([0.0, 0.0, 0.0], None, [10.0, 20.0, 30.0])
    assert r2["high_prob"] is False
    assert "FIELD_HIGH_PRECIP_PROB" not in r2["reason_codes"]
    assert PROB_FLAG_PCT == 70.0


# Field work 14: window exactness -----------------------------------------------------
def test_field_windows_are_exact():
    import pytest
    with pytest.raises(AssertionError):
        classify_3d([0.0, 0.0])  # not D+1..D+3
    with pytest.raises(AssertionError):
        describe_7d([0.0] * 6)  # not D+1..D+7
    d7 = describe_7d([2.0] * 7)
    assert d7["tier"] is None and d7["wet_days"] == 7  # evidence only, no tier
    obs = observed_3d([0.5, 1.5, 2.5])
    assert obs["tier"] == "HIGH" and obs["disrupted"] == 1 and obs["wet_days"] == 2
    assert obs["max_daily_mm"] == 2.5


# Leakage 15: future never a feature --------------------------------------------------
def test_detector_takes_forecast_only():
    import inspect
    for fn in (detect_excess, classify_3d, describe_7d):
        src = inspect.getsource(fn)
        assert "CHIRPS" not in src and "chirps" not in src and "observed" not in src, \
            f"{fn.__name__} must not reference observations"


# Leakage 16: fit isolation --------------------------------------------------------------
def test_backtest_predictions_use_train_only_thresholds():
    thr = load_thresholds()
    assert thr["fit_period"] == "2010-2019"
    pred = pd.read_csv("data/processed/risk/backtest_41_excess_predictions.csv", nrows=5)
    assert set(pred["split"]) <= {"eval"}
    assert "2020" not in pred["issue"].str[:4].unique()


# Leakage 17: namespace separation ----------------------------------------------------------
def test_gefs_ifs_namespaces_separate():
    for p in ("data/processed/risk/backtest_41_excess_metrics.json",
              "data/processed/risk/backtest_41_field_metrics.json"):
        m = json.loads(open(p, encoding="utf-8").read())
        assert "GEFS" in m["method"]["source"] and "NOT live IFS" in m["method"]["source"]
        assert "live_ifs_shadow" not in json.dumps(m)
    assert (THRESHOLD_PATH.exists())
