"""Live shadow evidence status (read-only, deterministic, no ML, no tuning).

Reports accumulation progress toward the pre-registered confirmatory gate
(>=30 resolved live issue dates per ledger) without performing any
evaluation. Exits 0 always; gate state is data in the payload, never a
pass/fail verdict.

Usage:
  python -m src.shadow.status [--today YYYY-MM-DD]
"""

from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from src.risk.common import REPO

IFS_LEDGER = REPO / "data" / "processed" / "shadow" / "ifs_shadow.jsonl"
FIELD_LEDGER = REPO / "data" / "processed" / "shadow" / "field_shadow.jsonl"
FROZEN_TRUTH = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
CURRENT_TRUTH = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2026_current.csv"

IFS_SCHEMA = "ifs-shadow/v1"
FIELD_SCHEMA = "field-shadow/v1"
MIN_ISSUES_FOR_GATE = 30


def ledger_status(path: Path, schema: str, resolve_lag_days: int, today: date) -> dict:
    """Summarise one shadow ledger. Foreign-schema lines are counted, never parsed."""
    out: dict = {
        "path": str(path),
        "exists": path.exists(),
        "records": 0,
        "issue_dates": [],
        "pending": 0,
        "observed": 0,
        "unavailable": 0,
        "foreign_lines": 0,
        "latest_issue": None,
        "latest_observed_issue": None,
        "eligible_issue_dates": 0,
        "observed_issue_dates": 0,
        "gate_30_observed_issue_dates": False,
    }
    if not path.exists():
        return out
    issues: set[str] = set()
    observed_issues: set[str] = set()
    latest_observed: str | None = None
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != schema:
                out["foreign_lines"] += 1
                continue
            out["records"] += 1
            issue = str(obj.get("issue_date", ""))[:10]
            issues.add(issue)
            status = obj.get("truth_status")
            if status == "pending":
                out["pending"] += 1
            elif status == "observed":
                out["observed"] += 1
                observed_issues.add(issue)
                if latest_observed is None or issue > latest_observed:
                    latest_observed = issue
            elif status == "unavailable":
                out["unavailable"] += 1
    out["issue_dates"] = sorted(issues)
    if issues:
        out["latest_issue"] = max(issues)
    out["latest_observed_issue"] = latest_observed
    out["eligible_issue_dates"] = sum(
        1 for d in issues
        if date.fromisoformat(d) + timedelta(days=resolve_lag_days) <= today
    )
    out["observed_issue_dates"] = len(observed_issues)
    out["gate_30_observed_issue_dates"] = len(observed_issues) >= MIN_ISSUES_FOR_GATE
    return out


def truth_end(path: Path) -> str | None:
    """Latest date present in a block-daily truth CSV (block,date,rainfall_mm)."""
    if not path.exists():
        return None
    latest: str | None = None
    with path.open(encoding="utf-8") as fh:
        header = fh.readline()
        cols = [c.strip() for c in header.split(",")]
        try:
            di = cols.index("date")
        except ValueError:
            return None
        for line in fh:
            parts = line.split(",")
            if len(parts) <= di:
                continue
            d = parts[di].strip()[:10]
            if len(d) == 10 and (latest is None or d > latest):
                latest = d
    return latest


def build_status(today: date) -> dict:
    frozen_end = truth_end(FROZEN_TRUTH)
    current_end = truth_end(CURRENT_TRUTH)
    ends = [e for e in (frozen_end, current_end) if e]
    ifs = ledger_status(IFS_LEDGER, IFS_SCHEMA, 7, today)
    field = ledger_status(FIELD_LEDGER, FIELD_SCHEMA, 3, today)
    both_gate = ifs["gate_30_observed_issue_dates"] and field["gate_30_observed_issue_dates"]
    return {
        "today": today.isoformat(),
        "ifs": ifs,
        "field": field,
        "truth": {
            "frozen_path": str(FROZEN_TRUTH),
            "frozen_end": frozen_end,
            "current_path": str(CURRENT_TRUTH),
            "current_end": current_end,
            "combined_end": max(ends) if ends else None,
        },
        "evaluation": (
            "READY FOR CONFIRMATORY EVALUATION (>=30 observed issue dates in BOTH ledgers)"
            if both_gate else
            "INSUFFICIENT EVIDENCE — CONTINUE ACCUMULATING LIVE ISSUES"
        ),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Report live shadow evidence accumulation (read-only).")
    ap.add_argument("--today", default=None, help="YYYY-MM-DD (default: today UTC)")
    args = ap.parse_args(argv)
    today = date.fromisoformat(args.today) if args.today else datetime.now(tz=timezone.utc).date()
    print(json.dumps(build_status(today), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
