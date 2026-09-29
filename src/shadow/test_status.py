"""Status + current-truth updater tests (local, no network).

Run: python -m pytest src/shadow/test_status.py -q
Covers: ledger summarisation, gate reporting (never a verdict), truth
coverage ends, updater URL patterns, final-upgrades-prelim merge,
idempotent re-runs, missing-never-zero (omitted rows).
"""

import json
from datetime import date
from pathlib import Path

from src.shadow import status as st
from src.shadow.update_chirps_current import (
    SOURCE_FINAL,
    SOURCE_PRELIM,
    merge_rows,
    select_stage,
    update_range,
    url_for,
)


def _ledger(path: Path, schema: str, rows: list) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            rec = {"schema_version": schema, "record_kind": "forecast_issue",
                   "truth_status": "pending"}
            rec.update(r)
            fh.write(json.dumps(rec) + "\n")


def test_status_counts_and_gate(tmp_path: Path, monkeypatch):
    ifs = tmp_path / "ifs.jsonl"
    fld = tmp_path / "field.jsonl"
    _ledger(ifs, st.IFS_SCHEMA, [
        {"issue_id": "ifs:2026-09-21", "issue_date": "2026-09-21", "block": "Dhuri",
         "truth_status": "observed"},
        {"issue_id": "ifs:2026-09-22", "issue_date": "2026-09-22", "block": "Dhuri"},
    ])
    _ledger(fld, st.FIELD_SCHEMA, [
        {"issue_id": "field:2026-09-21", "issue_date": "2026-09-21", "block": "Dhuri"},
    ])
    monkeypatch.setattr(st, "IFS_LEDGER", ifs)
    monkeypatch.setattr(st, "FIELD_LEDGER", fld)
    monkeypatch.setattr(st, "FROZEN_TRUTH", tmp_path / "nofrozen.csv")
    cur = tmp_path / "cur.csv"
    cur.write_text("block,date,rainfall_mm,source\nDhuri,2026-09-15,1.5,x\n",
                   encoding="utf-8")
    monkeypatch.setattr(st, "CURRENT_TRUTH", cur)
    out = st.build_status(date(2026, 9, 22))
    assert out["ifs"]["records"] == 2
    assert out["ifs"]["observed_issue_dates"] == 1
    assert out["ifs"]["eligible_issue_dates"] == 0  # 9/21+7 > 9/22; 9/22+7 > 9/22
    assert out["field"]["eligible_issue_dates"] == 0  # 9/21+3 > 9/22
    assert out["field"]["gate_30_observed_issue_dates"] is False
    assert out["truth"]["current_end"] == "2026-09-15"
    assert out["truth"]["frozen_end"] is None
    assert out["evaluation"].startswith("INSUFFICIENT EVIDENCE")


def test_status_missing_ledgers(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(st, "IFS_LEDGER", tmp_path / "no.jsonl")
    monkeypatch.setattr(st, "FIELD_LEDGER", tmp_path / "no2.jsonl")
    monkeypatch.setattr(st, "FROZEN_TRUTH", tmp_path / "no3.csv")
    monkeypatch.setattr(st, "CURRENT_TRUTH", tmp_path / "no4.csv")
    out = st.build_status(date(2026, 9, 22))
    assert out["ifs"]["exists"] is False
    assert out["truth"]["combined_end"] is None


def test_url_patterns():
    assert url_for("final", date(2026, 8, 31)) == (
        "https://data.chc.ucsb.edu/products/CHIRPS/v3.0/daily/final/sat/2026/"
        "chirps-v3.0.sat.2026.08.31.tif")
    assert url_for("prelim", date(2026, 9, 15)) == (
        "https://data.chc.ucsb.edu/products/CHIRPS/v3.0/daily/prelim/sat/2026/"
        "chirps-v3.0.prelim.2026.09.15.tif")


def test_select_stage_prefers_final():
    assert select_stage(date(2026, 8, 1), probe=lambda u: True) == "final"
    assert select_stage(date(2026, 9, 20),
                        probe=lambda u: "final" not in u) == "prelim"
    assert select_stage(date(2026, 9, 30), probe=lambda u: False) is None


def test_merge_final_upgrades_prelim_idempotent():
    existing = {("Dhuri", "2026-09-01"): (2.0, SOURCE_PRELIM)}
    new = {("Dhuri", "2026-09-01"): (2.5, SOURCE_FINAL),
           ("Lehra", "2026-09-01"): (0.0, SOURCE_FINAL)}
    merged, stats = merge_rows(existing, new)
    assert merged[("Dhuri", "2026-09-01")] == (2.5, SOURCE_FINAL)
    assert stats["upgraded_prelim_to_final"] == 1
    assert stats["added"] == 1
    # Same-stage re-run keeps values even on mismatch (never silently rewrite).
    merged2, stats2 = merge_rows(merged, {("Dhuri", "2026-09-01"): (9.9, SOURCE_FINAL)})
    assert merged2[("Dhuri", "2026-09-01")] == (2.5, SOURCE_FINAL)
    assert stats2["mismatch_kept"] == 1


def test_update_range_offline_merges_without_network(tmp_path: Path):
    import pandas as pd

    out = tmp_path / "cur.csv"
    out.write_text("block,date,rainfall_mm,source\nDhuri,2026-09-01,2.0,"
                   + SOURCE_PRELIM + "\n", encoding="utf-8")
    calls = {"n": 0}

    def fake_probe(url: str) -> bool:
        return True  # final always "published"

    def fake_extract(url: str, boundary) -> dict:
        calls["n"] += 1
        if "2026.09.02" in url:
            return {}  # missing day -> no rows, never zero
        return {"Dhuri": 2.5}

    res = update_range(date(2026, 9, 1), date(2026, 9, 2), out,
                       boundary=object(), probe=fake_probe, extract=fake_extract)
    assert res["days_final"] == 2
    df = pd.read_csv(out)
    assert len(df) == 1  # 09-02 omitted (missing, never zero-filled)
    assert df.iloc[0]["source"] == SOURCE_FINAL  # prelim upgraded
    # Idempotent re-run: nothing changes.
    res2 = update_range(date(2026, 9, 1), date(2026, 9, 2), out,
                        boundary=object(), probe=fake_probe, extract=fake_extract)
    assert res2["added"] == 0 and res2["upgraded_prelim_to_final"] == 0
    assert (tmp_path / "cur.csv.provenance.json").exists()
