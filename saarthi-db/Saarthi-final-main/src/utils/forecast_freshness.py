"""P0 forecast freshness + validation entry point (read-only on artifacts).

Reads the NB06 application package (data/processed/application/latest_forecast.json),
validates its structure (6 blocks, 7 daily values, probability sums, categories,
totals), and reports explicit freshness metadata:

  issue_date, valid_from, valid_to, generated_at, source,
  age_days, stale, expired, expires_at (= valid_to)

Staleness rule (also documented in docs/api/api-contract.md and implemented
server-side in RealForecastService/ApiController):
  age_days = today - issue_date (calendar days)
  expired  = today > valid_to
  stale    = expired OR age_days > 2

A stale result is a VALID state (latest AVAILABLE bundle may legitimately be
older than today) — it must be exposed, never hidden. Exit codes:
  0 = package present and structurally valid (fresh or stale)
  2 = missing file, unparsable JSON, or structural validation failure

Optional CHC probe (--probe-days N): HEAD-requests the CHIRPS-GEFS archive for
the latest fully-available D+1..D+7 bundle walking back from today (same
semantics as notebooks/05 Cell 6). HEAD only — NEVER downloads rasters, NEVER
writes forecasts. Reports latest_available_issue + whether the local package
matches it. Network failures are reported, not fatal.

This script does NOT run inference, does NOT modify model artifacts, and does
NOT rewrite the application package. Refresh procedure:
  1. python src/utils/forecast_freshness.py [--probe-days 7]
  2. If a newer bundle is available: re-run NB05 -> NB06 -> copy package into
     website/Saarthi/src/main/resources/forecast/ -> POST /api/forecast/reload
     (or restart Spring Boot).
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = PROJECT / "data" / "processed" / "application" / "latest_forecast.json"

EXPECTED_BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
VALID_CATEGORIES = {"LOW", "NORMAL", "HIGH"}
ARCHIVE = "https://data.chc.ucsb.edu/products/CHIRPS-GEFS/v3/daily/global"
STALE_AFTER_DAYS = 2


def parse_iso_day(value: str) -> date:
    return date.fromisoformat(str(value)[:10])


def validate_package(doc: dict) -> list[str]:
    errors: list[str] = []
    system = doc.get("system")
    forecast = doc.get("forecast")
    summary = doc.get("summary")
    if not isinstance(system, dict):
        errors.append("missing 'system' object")
    if not isinstance(forecast, dict):
        errors.append("missing 'forecast' object")
    if not isinstance(summary, dict):
        errors.append("missing 'summary' object (NB06 public contract requires system+forecast+summary)")
    if not isinstance(forecast, dict):
        return errors
    for key in ("issue_date", "valid_from", "valid_to", "blocks"):
        if forecast.get(key) is None:
            errors.append(f"forecast missing '{key}'")
    blocks = forecast.get("blocks")
    if not isinstance(blocks, list) or len(blocks) != 6:
        errors.append(f"forecast.blocks must be a list of exactly 6 (found {len(blocks) if isinstance(blocks, list) else type(blocks).__name__})")
        return errors
    names = []
    for b in blocks:
        name = b.get("block_name")
        names.append(name)
        daily = b.get("daily_forecast")
        if not isinstance(daily, list) or len(daily) != 7:
            errors.append(f"block {name}: daily_forecast must have exactly 7 entries")
            continue
        total = b.get("forecast_7d_total_rainfall_mm")
        try:
            day_sum = sum(float(d.get("rainfall_mm", 0.0)) for d in daily)
            if total is None or abs(float(total) - day_sum) > 0.05:
                errors.append(f"block {name}: total {total} != sum(daily) {day_sum:.3f}")
        except (TypeError, ValueError):
            errors.append(f"block {name}: non-numeric rainfall values")
        prob = b.get("probability")
        if not isinstance(prob, dict):
            errors.append(f"block {name}: missing probability object")
        else:
            try:
                s = float(prob.get("low", 0)) + float(prob.get("normal", 0)) + float(prob.get("high", 0))
                if abs(s - 1.0) > 1e-3:
                    errors.append(f"block {name}: probabilities sum to {s}, expected ~1")
                for k in ("low", "normal", "high"):
                    v = float(prob.get(k, -1))
                    if not 0.0 <= v <= 1.0:
                        errors.append(f"block {name}: probability.{k}={v} outside [0,1]")
            except (TypeError, ValueError):
                errors.append(f"block {name}: non-numeric probabilities")
        if b.get("category") not in VALID_CATEGORIES:
            errors.append(f"block {name}: category {b.get('category')!r} not in {sorted(VALID_CATEGORIES)}")
    if sorted(names) != sorted(EXPECTED_BLOCKS):
        errors.append(f"block names {names} != 6 legacy Bhuvan blocks")
    return errors


def freshness(issue: str, valid_from: str, valid_to: str, generated_at, today: date) -> dict:
    issue_d = parse_iso_day(issue)
    valid_to_d = parse_iso_day(valid_to)
    age_days = (today - issue_d).days
    expired = today > valid_to_d
    stale = bool(expired or age_days > STALE_AFTER_DAYS)
    return {
        "issue_date": str(issue_d),
        "valid_from": str(parse_iso_day(valid_from)),
        "valid_to": str(valid_to_d),
        "generated_at": generated_at,
        "source": "Raw CHIRPS-GEFS (model_type=raw_gefs)",
        "age_days": age_days,
        "stale": stale,
        "expired": expired,
        "expires_at": str(valid_to_d),
    }


def probe_latest_bundle(days: int, timeout: int = 15) -> dict:
    """HEAD-only probe for the latest fully-available 7-file bundle. No downloads."""
    import datetime as _dt

    try:
        import requests
    except ImportError:
        return {"probed": False, "reason": "requests not installed; probe skipped"}
    today = date.today()
    for back in range(days):
        cand = today - _dt.timedelta(days=back)
        ok = True
        for k in range(1, 8):
            tgt = cand + _dt.timedelta(days=k)
            url = f"{ARCHIVE}/{cand.strftime('%Y/%m/%d')}/c3g_{tgt.strftime('%Y.%m.%d')}.tif"
            good = False
            for _ in range(2):
                try:
                    r = requests.head(url, timeout=timeout)
                    good = r.status_code == 200
                    if good:
                        break
                except Exception:
                    good = False
            if not good:
                ok = False
                break
        if ok:
            return {
                "probed": True,
                "today": str(today),
                "latest_available_issue": str(cand),
                "behind_by_days": back,
            }
    return {"probed": True, "today": str(today), "latest_available_issue": None,
            "reason": f"no complete 7-file bundle in last {days} days"}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Validate + report freshness of the NB06 forecast package (read-only).")
    ap.add_argument("--package", default=str(DEFAULT_PACKAGE))
    ap.add_argument("--probe-days", type=int, default=0,
                    help="HEAD-probe CHC for latest available bundle walking back N days (0 = skip network)")
    ap.add_argument("--fail-on-stale", action="store_true",
                    help="exit 3 when the package is structurally valid but stale")
    args = ap.parse_args(argv)

    pkg = Path(args.package)
    if not pkg.exists():
        print(json.dumps({"available": False, "package": str(pkg),
                          "error": "forecast package missing; no fallback forecast fabricated"}, indent=2))
        return 2
    try:
        doc = json.loads(pkg.read_text(encoding="utf-8"))
    except Exception as exc:
        print(json.dumps({"available": False, "package": str(pkg), "error": f"unparsable JSON: {exc}"}, indent=2))
        return 2

    errors = validate_package(doc)
    if errors:
        print(json.dumps({"available": False, "package": str(pkg), "validation_errors": errors}, indent=2))
        return 2

    fc = doc["forecast"]
    report = {"available": True, "package": str(pkg),
              **freshness(fc["issue_date"], fc["valid_from"], fc["valid_to"],
                          fc.get("generated_at"), date.today()),
              "blocks": 6, "horizon_days": 7}
    if args.probe_days and args.probe_days > 0:
        probe = probe_latest_bundle(args.probe_days)
        report["bundle_probe"] = probe
        if probe.get("latest_available_issue") and probe["latest_available_issue"] != report["issue_date"]:
            report["matches_latest_bundle"] = False
        elif probe.get("latest_available_issue"):
            report["matches_latest_bundle"] = True
    print(json.dumps(report, indent=2))
    if args.fail_on_stale and report["stale"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
