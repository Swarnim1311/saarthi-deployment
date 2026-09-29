"""Field-shadow tests (local, no network, no downloads, no tuning).

Run: python -m pytest src/shadow/test_field_shadow.py -q
Covers: six-block capture, frozen-rule reuse, immutability, D+1..D+3 window
exactness, incomplete-truth pending, missing-never-zero, dry-ledger
namespace separation, pre-registered gate.
"""

import json
from datetime import date, timedelta
from pathlib import Path

import pytest

from src.shadow import BLOCKS, PROVIDER
from src.shadow.field_shadow import (MIN_ISSUES_FOR_GATE, PRECISION_GATE,
                                     RECALL_GATE, SCHEMA_VERSION, SOURCE_TAG,
                                     attach_truth, build_record,
                                     capture_from_envelope, evaluate)


def _envelope(issue: date, rain: float = 0.0) -> dict:
    days = [{"date": (issue + timedelta(days=k)).isoformat(), "horizon_day": k,
             "rainfall_mm": rain, "rain_probability_pct": 10.0}
            for k in range(0, 16)]
    return {"provider": PROVIDER, "model": "ECMWF IFS (ecmwf_ifs)",
            "issue_date": issue.isoformat(), "retrieved_at": issue.isoformat() + "T00:00:00Z",
            "horizon_days": 16,
            "blocks": [{"block_name": b, "spatial_method": "m", "days": days}
                       for b in BLOCKS]}


def _chirps(tmp_path: Path, start: date, days: int, rain: float = 0.0) -> Path:
    p = tmp_path / "chirps.csv"
    rows = ["block,date,rainfall_mm"]
    for b in BLOCKS:
        for k in range(days):
            rows.append(f"{b},{(start + timedelta(days=k)).isoformat()},{rain}")
    p.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return p


def test_six_block_capture_and_frozen_rule(tmp_path: Path):
    issue = date(2021, 7, 7)
    ledger = tmp_path / "field.jsonl"
    stats = capture_from_envelope(_envelope(issue, rain=5.0), ledger)
    assert stats == {"written": 6, "duplicate_skipped": 0}
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["schema_version"] == SCHEMA_VERSION
    assert rec["issue_id"] == "field:2021-07-07"
    assert rec["truth_window"] == [(issue + timedelta(days=k)).isoformat()
                                   for k in range(1, 4)]
    assert rec["wet_days"] == 3 and rec["predicted_high"] == 1
    assert rec["method_version"] == "field-v1"
    dry = capture_from_envelope(_envelope(issue, rain=0.0), tmp_path / "d2.jsonl")
    rec2 = json.loads((tmp_path / "d2.jsonl").read_text().splitlines()[0])
    assert rec2["wet_days"] == 0 and rec2["predicted_high"] == 0


def test_immutable_duplicates(tmp_path: Path):
    issue = date(2021, 7, 7)
    ledger = tmp_path / "imm.jsonl"
    capture_from_envelope(_envelope(issue), ledger)
    before = ledger.read_bytes()
    stats = capture_from_envelope(_envelope(issue), ledger)
    assert stats["duplicate_skipped"] == 6 and ledger.read_bytes() == before


def test_malformed_envelope_rejected(tmp_path: Path):
    ledger = tmp_path / "bad.jsonl"
    with pytest.raises(ValueError):
        capture_from_envelope({"bogus": True}, ledger)
    with pytest.raises(ValueError):
        capture_from_envelope(_envelope(date(2021, 7, 7)) | {"model": "GFS"}, ledger)
    assert not ledger.exists()


def test_truth_after_d3_and_missing_never_zero(tmp_path: Path):
    issue = date(2021, 7, 7)
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=5.0)
    ledger = tmp_path / "t.jsonl"
    capture_from_envelope(_envelope(issue, rain=5.0), ledger)
    s = attach_truth(ledger, chirps, issue + timedelta(days=1))
    assert s["still_pending"] == 6
    s = attach_truth(ledger, chirps, issue + timedelta(days=5))
    assert s["observed"] == 6
    rec = json.loads(ledger.read_text().splitlines()[0])
    assert rec["observed_wet_days"] == 3 and rec["observed_disrupted"] == 1
    assert rec["validation_result"] == "TP"
    out = evaluate(ledger)
    assert out["source"] == SOURCE_TAG and out["verdict"] == "INSUFFICIENT_EVIDENCE"


def test_dry_ledger_lines_ignored(tmp_path: Path):
    ledger = tmp_path / "mix.jsonl"
    with ledger.open("w", encoding="utf-8") as fh:
        fh.write('{"schema_version": "ifs-shadow/v1", "issue_id": "ifs:2021-07-07", "block": "Dhuri"}\n')
        fh.write('{"issue_id": "gefs:2021-07-07", "block": "Dhuri"}\n')
    capture_from_envelope(_envelope(date(2021, 7, 7)), ledger)
    chirps = _chirps(tmp_path, date(2021, 5, 1), 90, rain=0.0)
    s = attach_truth(ledger, chirps, date(2021, 7, 20))
    assert s["ignored_lines"] == 2 and s["observed"] == 6
    out = evaluate(ledger)
    assert out["ignored_lines"] == 2 and out["n_observed_predictions"] == 6


def test_gate_preregistered():
    assert (RECALL_GATE, PRECISION_GATE, MIN_ISSUES_FOR_GATE) == (0.60, 0.40, 30)


def test_exact_1mm_wet_in_record():
    rec = build_record(date(2021, 7, 7), "t", "Dhuri", [1.0, 0.99, 0.0],
                       None, "m", 16)
    assert rec["wet_days"] == 1 and rec["predicted_high"] == 0
    rec = build_record(date(2021, 7, 7), "t", "Dhuri", [None, 5.0, 5.0],
                       None, "m", 16)
    assert rec["predicted_high"] is None and rec["forecast_complete"] is False
