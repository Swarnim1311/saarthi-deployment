"""Shadow validation tests (local, no network, no downloads).

Run: python -m pytest src/shadow/test_shadow.py -q
Covers the 10 checkpoint items: six-block capture, frozen-rule invocation,
exact D+1..D+7 window, immutability, duplicates, missing-never-zero,
unresolved-incomplete-truth, no-future-as-feature, capture-failure safety,
GEFS/IFS metric separation. Phase 4.0 rule constants are asserted, never tuned.
"""

import json
from datetime import date, timedelta
from pathlib import Path

import pytest

from src.shadow import (BLOCKS, FEAT_LAG, MIN_ISSUES_FOR_GATE, MODEL_SELECTOR,
                        PROVIDER, RULE_VERSION, SCHEMA_VERSION)
from src.shadow.capture import (append_immutable, build_record, capture_from_envelope,
                                count_dry, frozen_predict, issue_id_for,
                                trailing_dry_run)
from src.shadow.metrics import evaluate
from src.shadow.truth import attach_truth


def _envelope(issue: date, rain: float = 0.0, prob: float = 10.0) -> dict:
    days = [{"date": (issue + timedelta(days=k)).isoformat(), "horizon_day": k,
             "rainfall_mm": rain, "rain_probability_pct": prob}
            for k in range(0, 16)]
    return {"provider": PROVIDER, "model": "ECMWF IFS (ecmwf_ifs)",
            "issue_date": issue.isoformat(), "retrieved_at": issue.isoformat() + "T00:00:00Z",
            "horizon_days": 16,
            "blocks": [{"block_name": b, "spatial_method": "multipoint_mean_n5",
                        "days": days} for b in BLOCKS]}


def _chirps(tmp_path: Path, start: date, days: int, rain: float = 0.0,
            skip: set | None = None) -> Path:
    p = tmp_path / "chirps.csv"
    rows = ["block,date,rainfall_mm"]
    for b in BLOCKS:
        for k in range(days):
            d = start + timedelta(days=k)
            if skip and (b, d) in skip:
                continue
            rows.append(f"{b},{d.isoformat()},{rain}")
    p.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return p


# 1. six-block capture -------------------------------------------------------
def test_six_block_capture(tmp_path: Path):
    issue = date(2021, 7, 7)
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=0.0)
    ledger = tmp_path / "shadow.jsonl"
    stats = capture_from_envelope(_envelope(issue), chirps, ledger)
    assert stats == {"written": 6, "duplicate_skipped": 0}
    assert sum(1 for _ in ledger.open()) == 6


# 2. frozen rule invocation --------------------------------------------------
def test_frozen_rule_vectors():
    assert frozen_predict(4, 6) == (1, ["f_dry>=6"])
    assert frozen_predict(3, 5) == (1, ["dry_run>=3&f_dry>=5"])
    assert frozen_predict(2, 5) == (0, ["no_dryspell_signal"])
    assert frozen_predict(9, 4) == (0, ["no_dryspell_signal"])
    assert frozen_predict(None, 6)[0] is None
    assert frozen_predict(4, None)[0] is None
    # parity with the backtest WARN formula on a grid
    for dr in range(0, 10):
        for fd in range(0, 8):
            expect = int((dr >= 3 and fd >= 5) or (fd >= 6))
            assert frozen_predict(dr, fd)[0] == expect


# 3. exact D+1..D+7 window ---------------------------------------------------
def test_exact_window_excludes_today_and_day8(tmp_path: Path):
    issue = date(2021, 7, 7)
    env = _envelope(issue, rain=0.0)
    # today (D+0) wet, D+8 wet: must not affect f_dry
    for b in env["blocks"]:
        b["days"][0]["rainfall_mm"] = 9.9
        b["days"][8]["rainfall_mm"] = 9.9
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=0.0)
    ledger = tmp_path / "w.jsonl"
    capture_from_envelope(env, chirps, ledger)
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["truth_window"] == [(issue + timedelta(days=k)).isoformat() for k in range(1, 8)]
    assert rec["f_dry"] == 7 and rec["forecast_d1_d7_mm"] == [0.0] * 7


# 4+5. immutability + duplicate handling --------------------------------------
def test_immutable_first_write_wins(tmp_path: Path):
    issue = date(2021, 7, 7)
    ledger = tmp_path / "imm.jsonl"
    r1 = build_record(issue, "2021-07-07T00:00:00Z", "Dhuri",
                      [0.0] * 7, [10.0] * 7, [0.0] * 28, "m", 16)
    assert append_immutable(ledger, r1) == "written"
    before = ledger.read_bytes()
    # "same issue retrieved again" with DIFFERENT values: must not overwrite
    r2 = build_record(issue, "2021-07-07T06:00:00Z", "Dhuri",
                      [9.9] * 7, [90.0] * 7, [9.9] * 28, "m", 16)
    assert append_immutable(ledger, r2) == "duplicate_skipped"
    assert ledger.read_bytes() == before
    assert json.loads(before.decode().strip())["forecast_d1_d7_mm"] == [0.0] * 7


# 6. missing CHIRPS never becomes zero ----------------------------------------
def test_missing_chirps_never_zero(tmp_path: Path):
    issue = date(2021, 7, 7)
    # drop one antecedent day for Dhuri only
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=0.0,
                     skip={("Dhuri", date(2021, 7, 4))})
    ledger = tmp_path / "z.jsonl"
    capture_from_envelope(_envelope(issue), chirps, ledger)
    recs = {json.loads(ln)["block"]: json.loads(ln) for ln in ledger.read_text().splitlines()}
    dh = recs["Dhuri"]
    assert dh["antecedent_available"] is False
    assert dh["chirps_antecedent_mm"] is None and dh["predicted_dryspell"] is None
    assert "antecedent_unavailable" in dh["reason_codes"]
    assert recs["Lehra"]["antecedent_available"] is True  # others unaffected
    # missing forecast day likewise
    env = _envelope(issue)
    env["blocks"][0]["days"][3]["rainfall_mm"] = None
    ledger2 = tmp_path / "z2.jsonl"
    capture_from_envelope(env, None, ledger2)
    rec = json.loads(ledger2.read_text().splitlines()[0])
    assert rec["forecast_complete"] is False and rec["f_dry"] is None
    assert count_dry([0.0, None]) is None and trailing_dry_run([0.0, None]) is None


# 7. incomplete truth remains unresolved --------------------------------------
def test_truth_needs_complete_window(tmp_path: Path):
    issue = date(2021, 7, 7)
    full = _chirps(tmp_path / "a" if False else tmp_path, date(2021, 5, 1), 90, rain=0.0)
    ledger = tmp_path / "t.jsonl"
    capture_from_envelope(_envelope(issue), full, ledger)
    # before window elapses: stays pending
    s = attach_truth(ledger, full, issue + timedelta(days=3))
    assert s["still_pending"] == 6
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["truth_status"] == "pending" and rec["observed_label"] is None
    # window elapsed but one observation day missing file-wide -> unavailable
    gap = tmp_path / "gap.csv"
    rows = ["block,date,rainfall_mm"]
    for b in BLOCKS:
        for k in range(90):
            d = date(2021, 5, 1) + timedelta(days=k)
            if d == issue + timedelta(days=4):
                continue
            rows.append(f"{b},{d.isoformat()},0.0")
    gap.write_text("\n".join(rows) + "\n", encoding="utf-8")
    s = attach_truth(ledger, gap, issue + timedelta(days=10))
    assert s["unavailable"] == 6
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["truth_status"] == "unavailable" and rec["observed_window_mm"] is None


# 8. future CHIRPS never enters prediction features ---------------------------
def test_antecedent_cutoff_is_dminus3(tmp_path: Path):
    issue = date(2021, 7, 7)
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=5.0)  # all wet
    ledger = tmp_path / "f.jsonl"
    capture_from_envelope(_envelope(issue), chirps, ledger)
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["chirps_antecedent_cutoff"] == (issue - timedelta(days=FEAT_LAG)).isoformat()
    assert rec["chirps_antecedent_mm"] == [5.0] * 28
    assert rec["dry_run_through_dminus3"] == 0
    assert max(date.fromisoformat(rec["chirps_antecedent_cutoff"]),
               date.fromisoformat(rec["chirps_antecedent_start"])) < \
        date.fromisoformat(rec["truth_window"][0])


# 9. capture failure never corrupts the ledger --------------------------------
def test_capture_failure_leaves_ledger_untouched(tmp_path: Path):
    ledger = tmp_path / "ok.jsonl"
    r = build_record(date(2021, 7, 7), "t", "Dhuri", [0.0] * 7, [0.0] * 7,
                     [0.0] * 28, "m", 16)
    append_immutable(ledger, r)
    before = ledger.read_bytes()
    with pytest.raises(ValueError):
        capture_from_envelope({"bogus": True}, None, ledger)
    with pytest.raises(ValueError):
        capture_from_envelope(_envelope(date(2021, 7, 7)) | {"model": "GFS"},
                              None, ledger)
    assert ledger.read_bytes() == before


# 10. GEFS and IFS metrics stay separate --------------------------------------
def test_metrics_ignores_foreign_lines_and_reports_source(tmp_path: Path):
    ledger = tmp_path / "m.jsonl"
    with ledger.open("w", encoding="utf-8") as fh:
        fh.write('{"issue_id": "gefs:2021-07-07", "block": "Dhuri", "label": 1}\n')
    issue = date(2021, 7, 7)
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=0.0)
    capture_from_envelope(_envelope(issue), chirps, ledger)
    attach_truth(ledger, chirps, issue + timedelta(days=10))
    out = evaluate(ledger)
    assert out["source"] == "live_ifs_shadow"
    assert out["ignored_lines"] == 1
    assert out["n_observed_predictions"] == 6
    assert out["verdict"] == "INSUFFICIENT_EVIDENCE"  # < MIN_ISSUES_FOR_GATE
    assert MIN_ISSUES_FOR_GATE == 30


def test_schema_and_rule_version_pinned():
    assert SCHEMA_VERSION == "ifs-shadow/v1"
    assert RULE_VERSION == "phase4.0-frozen"
    assert issue_id_for(date(2026, 9, 21)) == "ifs:2026-09-21"
