"""Phase 4.3 field shadow: live FIELD_HIGH validation ledger (NO ML, NO tuning).

Separate ledger from the dry-spell shadow
(`data/processed/shadow/field_shadow.jsonl`, schema `field-shadow/v1`,
issue `field:<date>`). The dry-spell ledger file/schema are never touched;
foreign lines (ifs-shadow, GEFS) are ignored, never parsed.

Prediction reuses the frozen `src.risk.field_work.classify_3d` rule
(HIGH iff >=2 wet days >=1mm in D+1..D+3). Truth: CHIRPS D+1..D+3 wet-day
count >= 2, attached once today >= issue+3 with all 3 days present.
Missing CHIRPS is NEVER zero-filled (-> unavailable).

Pre-registered gate (reported only): recall >= 0.60, precision >= 0.40,
minimum 30 resolved issues before any claim. Historical GEFS metrics stay
separate and are never consulted.

Usage:
  python -m src.shadow.field_shadow capture --envelope fc.json --ledger <jsonl>
  python -m src.shadow.field_shadow attach --ledger <jsonl> --chirps <csv> [--today DATE]
  python -m src.shadow.field_shadow evaluate --ledger <jsonl>
"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from src.risk.common import BLOCKS, boot_ci_issue_grouped, contingency
from src.risk.excess_rain import load_thresholds
from src.risk.field_work import METHOD_VERSION as FIELD_V
from src.risk.field_work import classify_3d

SCHEMA_VERSION = "field-shadow/v1"
SOURCE_TAG = "live_ifs_field"
PROVIDER = "Open-Meteo"
MODEL = "ECMWF IFS (ecmwf_ifs)"
MODEL_SELECTOR = "ecmwf_ifs"
RECALL_GATE = 0.60
PRECISION_GATE = 0.40
MIN_ISSUES_FOR_GATE = 30


def issue_id_for(issue: date) -> str:
    return f"field:{issue.isoformat()}"


def ledger_keys(path: Path) -> set[tuple[str, str]]:
    """Existing (issue_id, block) keys for THIS schema; foreign lines ignored."""
    keys: set[tuple[str, str]] = set()
    if not path.exists():
        return keys
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != SCHEMA_VERSION:
                continue
            keys.add((obj["issue_id"], obj["block"]))
    return keys


def append_immutable(ledger_path: Path, record: dict) -> str:
    assert record.get("schema_version") == SCHEMA_VERSION
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    key = (record["issue_id"], record["block"])
    if key in ledger_keys(ledger_path):
        return "duplicate_skipped"
    with ledger_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")
    return "written"


def build_record(issue: date, retrieved_at: str, block: str,
                 f3: list, heavy_p95: float | None,
                 spatial_method: str | None, horizon_days: int | None) -> dict:
    assert len(f3) == 3, "exact D+1..D+3 window required"
    res = classify_3d(list(f3), heavy_p95, None)
    tier = res["tier"]
    pred = None if tier is None else int(tier == "HIGH")
    heavy = res.get("heavy_rain")
    return {
        "schema_version": SCHEMA_VERSION,
        "record_kind": "forecast_issue",
        "issue_id": issue_id_for(issue),
        "issue_date": issue.isoformat(),
        "forecast_retrieved_at": retrieved_at,
        "block": block,
        "provider": PROVIDER,
        "model": MODEL,
        "model_selector": MODEL_SELECTOR,
        "horizon_days": horizon_days,
        "spatial_method": spatial_method,
        "truth_window": [(issue + timedelta(days=k)).isoformat() for k in range(1, 4)],
        "forecast_d1_d3_mm": [None if v is None else round(float(v), 3) for v in f3],
        "forecast_complete": res["wet_days"] is not None,
        "wet_days": res["wet_days"],
        "predicted_high": pred,
        "heavy_present": heavy,
        "heavy_threshold_mm": heavy_p95,
        "method_version": FIELD_V,
        "reason_codes": res["reason_codes"],
        "truth_status": "pending",
        "observed_wet_days": None,
        "observed_disrupted": None,
        "observed_window_mm": None,
        "chirps_vintage": None,
        "truth_attached_at": None,
        "validation_result": None,
    }


def capture_from_envelope(envelope: dict, ledger_path: Path,
                          retrieved_at: str | None = None) -> dict:
    """Offline capture from a saved /api/weather/forecast envelope (no request)."""
    try:
        issue = date.fromisoformat(str(envelope["issue_date"])[:10])
        blocks = envelope["blocks"]
        assert isinstance(blocks, list) and blocks
    except (KeyError, TypeError, AssertionError, ValueError) as exc:
        raise ValueError(f"malformed forecast envelope: {exc}") from exc
    if envelope.get("provider") != PROVIDER or MODEL_SELECTOR not in str(envelope.get("model", "")):
        raise ValueError("envelope is not an Open-Meteo/ECMWF-IFS forecast; refusing")
    try:
        thr = {b: v["daily_p95"] for b, v in load_thresholds()["blocks"].items()}
    except Exception:
        thr = {}
    retrieved = retrieved_at or envelope.get("retrieved_at") \
        or datetime.now(tz=timezone.utc).isoformat()
    stats = {"written": 0, "duplicate_skipped": 0}
    for b in blocks:
        name = b.get("block_name")
        if name not in BLOCKS:
            raise ValueError(f"unknown block {name!r}")
        by_date = {str(d.get("date"))[:10]: d for d in b.get("days", [])}
        f3 = [by_date.get((issue + timedelta(days=k)).isoformat(), {}).get("rainfall_mm")
              for k in range(1, 4)]
        rec = build_record(issue, retrieved, name, f3, thr.get(name),
                           b.get("spatial_method"), envelope.get("horizon_days"))
        stats[append_immutable(ledger_path, rec)] += 1
    return stats


def attach_truth(ledger_path: Path, chirps_csv: Path, today: date) -> dict:
    """Attach CHIRPS D+1..D+3 truth to pending records. Atomic rewrite."""
    from src.shadow.truth import chirps_vintage
    if not ledger_path.exists():
        return {"ledger": str(ledger_path), "error": "ledger_missing", "observed": 0}
    df = pd.read_csv(chirps_csv, usecols=["block", "date", "rainfall_mm"],
                     parse_dates=["date"])
    df["block"] = df["block"].replace({"Lehragaga": "Lehra"})
    assert df["rainfall_mm"].notna().all(), "missing CHIRPS must never become zero"
    series: dict = {}
    for b, g in df.groupby("block"):
        series[b] = {d.date() if hasattr(d, "date") else d: float(v)
                     for d, v in zip(g["date"].dt.date, g["rainfall_mm"])}
    vintage = chirps_vintage(chirps_csv)
    stamped = datetime.now(tz=timezone.utc).isoformat()
    stats = {"observed": 0, "unavailable": 0, "still_pending": 0,
             "already_resolved": 0, "ignored_lines": 0}
    out_lines: list[str] = []
    with ledger_path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != SCHEMA_VERSION:
                stats["ignored_lines"] += 1
                out_lines.append(line if line.endswith("\n") else line + "\n")
                continue
            if obj.get("truth_status") in ("observed", "unavailable"):
                stats["already_resolved"] += 1
                out_lines.append(json.dumps(obj) + "\n")
                continue
            issue = date.fromisoformat(obj["issue_date"])
            window = [date.fromisoformat(d) for d in obj["truth_window"]]
            assert len(window) == 3 and window[0] == issue + timedelta(days=1)
            if today < issue + timedelta(days=3):
                stats["still_pending"] += 1
                out_lines.append(json.dumps(obj) + "\n")
                continue
            obs = [series.get(obj["block"], {}).get(d) for d in window]
            if any(v is None or pd.isna(v) for v in obs):
                obj["truth_status"] = "unavailable"
                obj["truth_attached_at"] = stamped
                obj["chirps_vintage"] = vintage
                stats["unavailable"] += 1
                out_lines.append(json.dumps(obj) + "\n")
                continue
            nwet = int(sum(1 for v in obs if float(v) >= 1.0))
            obj["observed_window_mm"] = [round(float(v), 3) for v in obs]
            obj["observed_wet_days"] = nwet
            obj["observed_disrupted"] = int(nwet >= 2)
            obj["truth_status"] = "observed"
            obj["truth_attached_at"] = stamped
            obj["chirps_vintage"] = vintage
            pred, y = obj.get("predicted_high"), obj["observed_disrupted"]
            obj["validation_result"] = (
                None if pred is None
                else ("TP" if (pred == 1 and y == 1) else
                      "FP" if (pred == 1 and y == 0) else
                      "TN" if (pred == 0 and y == 0) else "FN"))
            stats["observed"] += 1
            out_lines.append(json.dumps(obj) + "\n")
    fd, tmp_name = tempfile.mkstemp(prefix=ledger_path.name + ".",
                                    dir=str(ledger_path.parent))
    os.close(fd)
    tmp = Path(tmp_name)
    try:
        tmp.write_text("".join(out_lines), encoding="utf-8")
        os.replace(tmp, ledger_path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return {"ledger": str(ledger_path), "today": today.isoformat(), **stats}


def evaluate(ledger_path: Path) -> dict:
    rows, ignored = [], 0
    with ledger_path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != SCHEMA_VERSION:
                ignored += 1
                continue
            if obj.get("truth_status") == "observed" and obj.get("predicted_high") is not None:
                rows.append(obj)
    out: dict = {"source": SOURCE_TAG, "ledger": str(ledger_path),
                 "ignored_lines": ignored, "n_observed_predictions": len(rows),
                 "gate": {"recall_gte": RECALL_GATE, "precision_gte": PRECISION_GATE,
                          "frozen": True, "min_issues": MIN_ISSUES_FOR_GATE}}
    if not rows:
        out["verdict"] = "INSUFFICIENT_EVIDENCE"
        out["reason"] = "no observed live-IFS field prediction/outcome pairs yet"
        return out
    y = np.array([r["observed_disrupted"] for r in rows], dtype=int)
    p = np.array([r["predicted_high"] for r in rows], dtype=int)
    issues = np.array([r["issue_id"] for r in rows])
    out["n_issues"] = int(len(np.unique(issues)))
    out["contingency"] = contingency(y, p)
    out["ci"] = {s: __import__("src.risk.common", fromlist=["boot_ci_issue_grouped"])
                 .boot_ci_issue_grouped(issues, y, p, stat=s)
                 for s in ("precision", "recall", "f1")}
    c = out["contingency"]
    gate_pass = (c["recall"] is not None and c["precision"] is not None
                 and c["recall"] >= RECALL_GATE and c["precision"] >= PRECISION_GATE)
    if out["n_issues"] < MIN_ISSUES_FOR_GATE:
        out["verdict"] = "INSUFFICIENT_EVIDENCE"
        out["reason"] = (f"only {out['n_issues']} issues resolved; "
                         f"need >= {MIN_ISSUES_FOR_GATE} before the gate is meaningful")
    else:
        out["verdict"] = "GATE_PASS" if gate_pass else "GATE_FAIL"
    out["gate_point_estimates"] = {"recall": c["recall"], "precision": c["precision"],
                                   "gate_pass_on_point_estimates": bool(gate_pass)}
    out["note"] = ("Live IFS field-shadow evidence only; historical GEFS metrics "
                   "are separate artifacts and were not consulted.")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="FIELD_HIGH live shadow ledger tools.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--envelope", required=True)
    c.add_argument("--ledger", required=True)
    c.add_argument("--retrieved-at", default=None)
    a = sub.add_parser("attach")
    a.add_argument("--ledger", required=True)
    a.add_argument("--chirps", required=True)
    a.add_argument("--today", default=None)
    e = sub.add_parser("evaluate")
    e.add_argument("--ledger", required=True)
    args = ap.parse_args(argv)
    if args.cmd == "capture":
        env = json.loads(Path(args.envelope).read_text(encoding="utf-8"))
        print(json.dumps({"ledger": args.ledger,
                          **capture_from_envelope(env, Path(args.ledger),
                                                  args.retrieved_at)}, indent=2))
    elif args.cmd == "attach":
        today = date.fromisoformat(args.today) if args.today \
            else datetime.now(tz=timezone.utc).date()
        print(json.dumps(attach_truth(Path(args.ledger), Path(args.chirps), today), indent=2))
    else:
        print(json.dumps(evaluate(Path(args.ledger)), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
