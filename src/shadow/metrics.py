"""Live-IFS shadow metrics (NO ML, NO tuning, NO gate changes).

Reads ONLY the IFS shadow ledger. Historical GEFS metrics live in
data/processed/risk/backtest_gefs_*.json and are NEVER mixed in here:
this script refuses foreign-schema lines (counted as ignored_lines) and
labels every output with source=live_ifs_shadow.

Uncertainty: issue-date clustered bootstrap (six blocks of one issue travel
together), reusing src.risk.common. The frozen gate (recall >= 0.60,
precision >= 0.40) is REPORTED, never re-tuned; below MIN_ISSUES_FOR_GATE
the verdict is INSUFFICIENT_EVIDENCE, never a validation claim.

Usage:
  python -m src.shadow.metrics --ledger data/processed/shadow/ifs_shadow.jsonl
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from src.risk.common import (BOOT_REPS, BOOT_SEED, boot_ci_issue_grouped,
                             contingency)
from src.shadow import (MIN_ISSUES_FOR_GATE, PRECISION_GATE, RECALL_GATE,
                        SCHEMA_VERSION, SOURCE_TAG)


def load_observed(ledger_path: Path) -> tuple[list, int]:
    """(rows with observed truth + non-null prediction, ignored_lines)."""
    rows, ignored = [], 0
    with ledger_path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != SCHEMA_VERSION:
                ignored += 1
                continue
            if obj.get("truth_status") == "observed" and obj.get("predicted_dryspell") is not None:
                rows.append(obj)
    return rows, ignored


def evaluate(ledger_path: Path) -> dict:
    rows, ignored = load_observed(ledger_path)
    out: dict = {
        "source": SOURCE_TAG,
        "ledger": str(ledger_path),
        "ignored_lines": ignored,
        "n_observed_predictions": len(rows),
        "gate": {"recall_gte": RECALL_GATE, "precision_gte": PRECISION_GATE,
                 "frozen": True},
    }
    if not rows:
        out["verdict"] = "INSUFFICIENT_EVIDENCE"
        out["reason"] = "no observed live-IFS prediction/outcome pairs yet"
        return out
    y = np.array([r["observed_label"] for r in rows], dtype=int)
    p = np.array([r["predicted_dryspell"] for r in rows], dtype=int)
    issues = np.array([r["issue_id"] for r in rows])
    out["n_issues"] = int(len(np.unique(issues)))
    out["contingency"] = contingency(y, p)
    out["ci"] = {s: boot_ci_issue_grouped(issues, y, p, stat=s)
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
    out["note"] = ("Live IFS shadow evidence only; historical GEFS metrics are "
                   "separate artifacts and were not consulted.")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Evaluate observed live-IFS shadow pairs.")
    ap.add_argument("--ledger", required=True)
    args = ap.parse_args(argv)
    print(json.dumps(evaluate(Path(args.ledger)), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
