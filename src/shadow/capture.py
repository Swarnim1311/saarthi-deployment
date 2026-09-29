"""Shadow forecast capture: build immutable per-(issue, block) records.

Two entry paths share this schema (no second weather client):
  1. Java `ShadowCaptureService` at the LiveWeatherService retrieval boundary
     (primary live path; see website/Saarthi/.../shadow/).
  2. `capture_from_envelope()` here: offline capture from a saved
     GET /api/weather/forecast response (fallback / test utility).

Both write JSONL via append_immutable(): first-write-wins on (issue_id, block).
Repeat retrievals of the same issue are SKIPPED (logged), never overwritten,
so two different retrievals are never silently treated as identical.

Usage:
  python -m src.shadow.capture --envelope forecast.json --chirps <chirps.csv> \\
      --ledger data/processed/shadow/ifs_shadow.jsonl
"""

from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np

from src.risk.common import dry_run_ending_at, is_dry
from src.shadow import (ANTECEDENT_DAYS, BLOCKS, FEAT_LAG, MODEL, MODEL_SELECTOR,
                        PROVIDER, RULE_VERSION, SCHEMA_VERSION, DRY_RUN_MIN,
                        F_DRY_STRONG, F_DRY_WARN)


def issue_id_for(issue: date) -> str:
    return f"ifs:{issue.isoformat()}"


def frozen_predict(dry_run: int | None, f_dry: int | None) -> tuple[int | None, list[str]]:
    """Frozen Phase 4.0 rule. Returns (warn, reason_codes). None inputs -> (None, reasons)."""
    if dry_run is None or f_dry is None:
        reasons = []
        if dry_run is None:
            reasons.append("antecedent_unavailable")
        if f_dry is None:
            reasons.append("incomplete_forecast")
        return None, reasons
    warn = int((dry_run >= DRY_RUN_MIN and f_dry >= F_DRY_WARN) or (f_dry >= F_DRY_STRONG))
    if warn:
        if f_dry >= F_DRY_STRONG:
            return warn, ["f_dry>=6"]
        return warn, ["dry_run>=3&f_dry>=5"]
    return warn, ["no_dryspell_signal"]


def count_dry(values: list) -> int | None:
    """Dry-day count; None if ANY value is missing (never zero-fill)."""
    if any(v is None for v in values):
        return None
    return int(sum(1 for v in values if is_dry(float(v))))


def trailing_dry_run(values: list) -> int | None:
    """Trailing dry-day run; None if ANY value is missing (never zero-fill)."""
    if any(v is None for v in values):
        return None
    arr = np.array([float(v) for v in values], dtype=float)
    return int(dry_run_ending_at(arr))


def build_record(issue: date, retrieved_at: str, block: str,
                 forecast_d1_d7: list, prob_d1_d7: list,
                 antecedent_vals: list | None, spatial_method: str | None,
                 horizon_days: int | None,
                 antecedent_backfilled: bool = False) -> dict:
    """Pure record builder. `forecast_d1_d7`: 7 values for D+1..D+7 (None = missing).
    `antecedent_vals`: ANTECEDENT_DAYS values for D-30..D-3, or None if unavailable.
    Future CHIRPS (D+1 onward) must NEVER appear in antecedent_vals (asserted)."""
    assert len(forecast_d1_d7) == 7, "exact D+1..D+7 window required"
    assert len(prob_d1_d7) == 7
    if antecedent_vals is not None:
        assert len(antecedent_vals) == ANTECEDENT_DAYS
    window = [issue + timedelta(days=k) for k in range(1, 8)]
    cutoff = issue - timedelta(days=FEAT_LAG)
    ant_start = issue - timedelta(days=FEAT_LAG + ANTECEDENT_DAYS - 1)
    # Contract: antecedent ends at D-3, strictly before the truth window.
    assert cutoff < window[0]

    forecast_complete = all(v is not None for v in forecast_d1_d7)
    f_dry = count_dry(forecast_d1_d7)
    antecedent_available = antecedent_vals is not None and all(v is not None for v in antecedent_vals)
    dry_run = trailing_dry_run(antecedent_vals) if antecedent_available else None
    warn, reasons = frozen_predict(dry_run, f_dry)

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
        "truth_window": [d.isoformat() for d in window],
        "forecast_d1_d7_mm": [None if v is None else round(float(v), 3) for v in forecast_d1_d7],
        "forecast_d1_d7_prob_pct": list(prob_d1_d7),
        "forecast_complete": bool(forecast_complete),
        "chirps_antecedent_cutoff": cutoff.isoformat(),
        "chirps_antecedent_start": ant_start.isoformat(),
        "chirps_antecedent_mm": (
            None if not antecedent_available
            else [round(float(v), 3) for v in antecedent_vals]),
        "antecedent_available": bool(antecedent_available),
        "antecedent_backfilled": bool(antecedent_backfilled),
        "dry_run_through_dminus3": dry_run,
        "f_dry": f_dry,
        "rule_version": RULE_VERSION,
        "predicted_dryspell": warn,
        "reason_codes": reasons,
        "truth_status": "pending",
        "observed_label": None,
        "observed_window_mm": None,
        "chirps_vintage": None,
        "truth_attached_at": None,
        "validation_result": None,
    }


def ledger_keys(path: Path) -> set[tuple[str, str]]:
    """Existing (issue_id, block) keys. Lines with a foreign schema are ignored
    here (metrics.py counts them separately); corrupt lines raise."""
    keys: set[tuple[str, str]] = set()
    if not path.exists():
        return keys
    with path.open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("schema_version") != SCHEMA_VERSION:
                continue  # foreign line (e.g. historical GEFS): never parsed as shadow
            keys.add((obj["issue_id"], obj["block"]))
    return keys


def append_immutable(ledger_path: Path, record: dict) -> str:
    """Append unless (issue_id, block) already exists. Returns
    'written' or 'duplicate_skipped'. Never overwrites."""
    assert record.get("schema_version") == SCHEMA_VERSION
    assert record.get("record_kind") == "forecast_issue"
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    keys = ledger_keys(ledger_path)
    key = (record["issue_id"], record["block"])
    if key in keys:
        return "duplicate_skipped"
    with ledger_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")
    return "written"


def load_chirps_daily(chirps_csv: Path) -> dict:
    """block -> {date: rainfall_mm}. 'Lehragaga' normalised to 'Lehra'."""
    import pandas as pd
    df = pd.read_csv(chirps_csv, usecols=["block", "date", "rainfall_mm"],
                     parse_dates=["date"])
    df["block"] = df["block"].replace({"Lehragaga": "Lehra"})
    out: dict = {}
    for b, g in df.groupby("block"):
        out[b] = {(d.date() if hasattr(d, "date") else d): float(v)
                  for d, v in zip(g["date"].dt.date, g["rainfall_mm"])}
    return out


def antecedent_for(series: dict, block: str, issue: date) -> list | None:
    """ANTECEDENT_DAYS daily CHIRPS values D-30..D-3. None if the file lacks
    the block OR any day is missing (missing is never zero-filled)."""
    if block not in series:
        return None
    days = [issue - timedelta(days=k)
            for k in range(FEAT_LAG + ANTECEDENT_DAYS - 1, FEAT_LAG - 1, -1)]
    vals = [series[block].get(d) for d in days]
    if any(v is None for v in vals):
        return None
    return vals


def capture_from_envelope(envelope: dict, chirps_csv: Path | None,
                          ledger_path: Path, retrieved_at: str | None = None) -> dict:
    """Capture one six-block issue from a saved /api/weather/forecast envelope.

    Raises ValueError on malformed envelopes (ledger left untouched).
    Never performs a weather request itself (no second weather client).
    """
    try:
        issue = date.fromisoformat(str(envelope["issue_date"])[:10])
        blocks = envelope["blocks"]
        assert isinstance(blocks, list) and blocks
    except (KeyError, TypeError, AssertionError, ValueError) as exc:
        raise ValueError(f"malformed forecast envelope: {exc}") from exc
    if envelope.get("provider") != PROVIDER or MODEL_SELECTOR not in str(envelope.get("model", "")):
        raise ValueError("envelope is not an Open-Meteo/ECMWF-IFS forecast; refusing to ledger it")

    series = load_chirps_daily(chirps_csv) if chirps_csv is not None else {}
    retrieved = retrieved_at or envelope.get("retrieved_at") or datetime.utcnow().isoformat() + "Z"
    stats = {"written": 0, "duplicate_skipped": 0}
    for b in blocks:
        name = b.get("block_name")
        if name not in BLOCKS:
            raise ValueError(f"unknown block {name!r}; expected one of {BLOCKS}")
        by_date = {str(d.get("date"))[:10]: d for d in b.get("days", [])}
        fc, pr = [], []
        for k in range(1, 8):
            d = by_date.get((issue + timedelta(days=k)).isoformat(), {})
            fc.append(d.get("rainfall_mm"))
            pr.append(d.get("rain_probability_pct"))
        rec = build_record(
            issue, retrieved, name, fc, pr,
            antecedent_for(series, name, issue) if series else None,
            b.get("spatial_method"), envelope.get("horizon_days"))
        stats[append_immutable(ledger_path, rec)] += 1
    return stats


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Capture a live IFS issue into the shadow ledger (offline, from a saved envelope).")
    ap.add_argument("--envelope", required=True, help="saved GET /api/weather/forecast JSON")
    ap.add_argument("--chirps", default=None, help="CHIRPS block CSV for antecedent (optional; backfilled later by truth.py)")
    ap.add_argument("--ledger", required=True, help="shadow JSONL ledger path")
    ap.add_argument("--retrieved-at", default=None)
    args = ap.parse_args(argv)
    env = json.loads(Path(args.envelope).read_text(encoding="utf-8"))
    stats = capture_from_envelope(
        env, Path(args.chirps) if args.chirps else None, Path(args.ledger), args.retrieved_at)
    print(json.dumps({"ledger": str(args.ledger), **stats}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
