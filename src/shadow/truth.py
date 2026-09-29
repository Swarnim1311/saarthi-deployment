"""CHIRPS truth attachment for the IFS shadow ledger (NO ML, NO tuning).

For a forecast issued on D the truth window is D+1..D+7 (CHIRPS only).
Rules (hard):
  - truth is attached only when `today >= issue + 7` AND all 7 CHIRPS days
    are present for the block. Anything else -> truth_status stays
    'pending' (window incomplete) or becomes 'unavailable' (window elapsed
    but observations missing). Missing CHIRPS is NEVER zero-filled.
  - future CHIRPS (D+1 onward) is used ONLY for the label, never as a feature.
  - records captured without antecedent (CHIRPS unconfigured at issue time)
    get their antecedent + frozen prediction BACKFILLED here from the same
    historical CHIRPS file (D-30..D-3 only), flagged antecedent_backfilled=true.
    The forecast values themselves are never modified.
  - observed records are never rewritten (immutable outcome write-once).

Label (identical to Phase 4.0 backtest): 1 if any CHIRPS dry run of length
>= 7 overlaps D+1..D+7, else 0.

Usage:
  python -m src.shadow.truth --ledger data/processed/shadow/ifs_shadow.jsonl \\
      --chirps data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv \\
      [--today YYYY-MM-DD]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

from src.risk.common import BLOCKS, FEAT_LAG, dry_runs
from src.shadow import ANTECEDENT_DAYS, RULE_VERSION, SCHEMA_VERSION
from src.shadow.capture import antecedent_for, frozen_predict, trailing_dry_run

LABEL_MIN_LEN = 7


def chirps_vintage(chirps_csv: Path) -> dict:
    st = chirps_csv.stat()
    h = hashlib.sha256()
    with chirps_csv.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return {
        "path": str(chirps_csv),
        "mtime_utc": datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).isoformat(),
        "sha256_16": h.hexdigest()[:16],
        "bytes": st.st_size,
    }


def label_overlap(runs_b: list, lo: date, hi: date) -> int:
    """1 if any >=7d dry run overlaps [lo, hi] (same as backtest.label_overlap)."""
    for s, e, _ in runs_b:
        if s <= hi and e >= lo:
            return 1
    return 0


def attach_truth(ledger_path: Path, chirps_csv: Path, today: date) -> dict:
    """Resolve pending records whose window has elapsed. Atomic rewrite.
    Returns summary counts."""
    if not ledger_path.exists():
        return {"ledger": str(ledger_path), "error": "ledger_missing", "observed": 0}
    df = pd.read_csv(chirps_csv, usecols=["block", "date", "rainfall_mm"],
                     parse_dates=["date"])
    df["block"] = df["block"].replace({"Lehragaga": "Lehra"})
    assert df["rainfall_mm"].notna().all(), "missing CHIRPS must never silently become zero"
    pv = df.pivot(index="date", columns="block", values="rainfall_mm").sort_index()
    idx_dates = [d.date() if hasattr(d, "date") else d for d in pv.index]
    series = {b: {dd: v for dd, v in zip(idx_dates, pv[b].to_numpy())}
              for b in BLOCKS if b in pv.columns}
    runs = {b: dry_runs(list(series[b].keys()),
                        __import__("numpy").array(list(series[b].values())),
                        LABEL_MIN_LEN)
            for b in series}
    vintage = chirps_vintage(chirps_csv)
    stamped = datetime.now(tz=timezone.utc).isoformat()

    stats = {"observed": 0, "unavailable": 0, "still_pending": 0,
             "already_resolved": 0, "ignored_lines": 0, "backfilled": 0}
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
            assert len(window) == 7 and window[0] == issue + timedelta(days=1)
            block = obj["block"]
            if today < issue + timedelta(days=7):
                stats["still_pending"] += 1  # window not complete: never label early
                out_lines.append(json.dumps(obj) + "\n")
                continue
            obs = [series.get(block, {}).get(d) for d in window]
            if any(v is None or pd.isna(v) for v in obs):
                obj["truth_status"] = "unavailable"
                obj["truth_attached_at"] = stamped
                obj["chirps_vintage"] = vintage
                stats["unavailable"] += 1
                out_lines.append(json.dumps(obj) + "\n")
                continue
            # Backfill antecedent + frozen prediction if missing at capture time.
            if not obj.get("antecedent_available"):
                ant = antecedent_for(series, block, issue)
                if ant is not None:
                    obj["chirps_antecedent_mm"] = [round(float(v), 3) for v in ant]
                    obj["antecedent_available"] = True
                    obj["antecedent_backfilled"] = True
                    obj["dry_run_through_dminus3"] = trailing_dry_run(ant)
                    warn, reasons = frozen_predict(
                        obj["dry_run_through_dminus3"], obj["f_dry"])
                    obj["predicted_dryspell"] = warn
                    obj["reason_codes"] = reasons
                    obj["rule_version"] = RULE_VERSION
                    stats["backfilled"] += 1
            obj["observed_window_mm"] = [round(float(v), 3) for v in obs]
            obj["observed_label"] = label_overlap(runs.get(block, []), window[0], window[-1])
            obj["truth_status"] = "observed"
            obj["truth_attached_at"] = stamped
            obj["chirps_vintage"] = vintage
            pred = obj.get("predicted_dryspell")
            y = obj["observed_label"]
            obj["validation_result"] = (
                None if pred is None
                else ("TP" if (pred == 1 and y == 1) else
                      "FP" if (pred == 1 and y == 0) else
                      "TN" if (pred == 0 and y == 0) else "FN"))
            stats["observed"] += 1
            out_lines.append(json.dumps(obj) + "\n")

    fd, tmp_name = tempfile.mkstemp(prefix=ledger_path.name + ".", dir=str(ledger_path.parent))
    os.close(fd)  # mkstemp leaves the fd open; Windows locks it until closed
    tmp = Path(tmp_name)
    try:
        tmp.write_text("".join(out_lines), encoding="utf-8")
        os.replace(tmp, ledger_path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return {"ledger": str(ledger_path), "today": today.isoformat(), **stats}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Attach CHIRPS truth to pending shadow records.")
    ap.add_argument("--ledger", required=True)
    ap.add_argument("--chirps", required=True)
    ap.add_argument("--today", default=None, help="YYYY-MM-DD (default: today UTC)")
    args = ap.parse_args(argv)
    today = date.fromisoformat(args.today) if args.today else datetime.now(tz=timezone.utc).date()
    print(json.dumps(attach_truth(Path(args.ledger), Path(args.chirps), today), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
