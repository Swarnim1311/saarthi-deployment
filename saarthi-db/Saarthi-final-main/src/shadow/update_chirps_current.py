"""Idempotent CHIRPS v3 current-truth updater for the live IFS shadow pipeline.

Fetches official CHIRPS v3.0 daily block rainfall for 2026+ and maintains a
SEPARATE, clearly-versioned current-truth CSV. The frozen 2010-2025 file is
never touched.

Truth product (OPTION 1, verified 2026-09-22):
  Climate Hazards Center, UC Santa Barbara — CHIRPS v3.0 daily (satellite IR
  + stations, 0.05 deg, public domain). Grids are fetched windowed
  (/vsicurl, Sangrur bbox only); full global rasters are never downloaded.
  `sat` (IMERG-disaggregated) daily is used so the within-pentad daily
  structure stays independent of ECMWF; `rnl` (ERA5-disaggregated) daily is
  deliberately NOT used as IFS validation truth.

Stage policy per day (recorded per row in `source`):
  final (chirps-v3.0.sat) when published, else prelim
  (chirps-v3.0.prelim). A later final row upgrades the prelim row for the
  same (block, date) — the only permitted revision, and it is explicit in
  `source`, never silent. Same-stage re-runs keep existing values
  (idempotent); mismatches are logged and the existing value is kept.

Hard rules:
  - missing days (no file, no valid pixels, read failure) produce NO row:
    missing truth is never zero-filled (truth.py then marks `unavailable`).
  - forecast values are never consulted here; this module only reads CHIRPS.

Usage:
  python -m src.shadow.update_chirps_current --start 2026-08-20 --end 2026-09-15
  python -m src.shadow.update_chirps_current --start 2026-01-01  # full backfill
"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from src.risk.common import BLOCKS, REPO

BASE = "https://data.chc.ucsb.edu/products/CHIRPS/v3.0/daily"
BOUNDARY = REPO / "data" / "raw" / "boundaries" / "sangrur_blocks_bhuvan.gpkg"
BOUNDARY_LAYER = "sangrur_blocks"
DEFAULT_OUT = REPO / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2026_current.csv"

SOURCE_FINAL = "chirps-v3.0-final-sat"
SOURCE_PRELIM = "chirps-v3.0-prelim-sat"


def url_for(stage: str, day: date) -> str:
    """Remote GeoTIFF URL for one day. `stage` is 'final' or 'prelim'."""
    d = day.isoformat().replace("-", ".")
    if stage == "final":
        return f"{BASE}/final/sat/{day.year}/chirps-v3.0.sat.{d}.tif"
    if stage == "prelim":
        return f"{BASE}/prelim/sat/{day.year}/chirps-v3.0.prelim.{d}.tif"
    raise ValueError(f"unknown stage {stage!r}")


def remote_exists(url: str, timeout: int = 30) -> bool:
    """HEAD probe; False on 404/timeout (day not published yet)."""
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=timeout):
            return True
    except Exception:
        return False


def extract_day(url: str, boundary) -> dict:
    """Windowed block means for one remote day. Blocks with no valid pixels
    are omitted (never zero). Raises on unreadable rasters (day is skipped)."""
    from src.data.chirps_gefs import zonal_stats

    out: dict = {}
    for _, row in boundary.iterrows():
        block = str(row["b_name"]).strip()
        if block not in BLOCKS:
            continue
        mean_mm, n_valid = zonal_stats(row.geometry, "/vsicurl/" + url)
        if n_valid == 0:
            continue
        import math

        if mean_mm is None or (isinstance(mean_mm, float) and math.isnan(mean_mm)):
            continue
        out[block] = round(float(mean_mm), 3)
    return out


def select_stage(day: date, probe=remote_exists) -> str | None:
    """'final' if published else 'prelim' if published else None (too recent)."""
    if probe(url_for("final", day)):
        return "final"
    if probe(url_for("prelim", day)):
        return "prelim"
    return None


def merge_rows(existing: dict, new: dict) -> tuple[dict, dict]:
    """Merge {(block, date): (value, source)} maps. Final upgrades prelim;
    same-stage re-runs keep existing values (idempotent). Returns
    (merged, stats)."""
    rank = {SOURCE_PRELIM: 0, SOURCE_FINAL: 1}
    stats = {"added": 0, "upgraded_prelim_to_final": 0, "kept": 0, "mismatch_kept": 0}
    merged = dict(existing)
    for key, (val, src) in new.items():
        if key not in merged:
            merged[key] = (val, src)
            stats["added"] += 1
        else:
            old_val, old_src = merged[key]
            if rank.get(src, -1) > rank.get(old_src, -1):
                merged[key] = (val, src)
                stats["upgraded_prelim_to_final"] += 1
            else:
                stats["kept"] += 1
                if abs(float(val) - float(old_val)) > 1e-9:
                    stats["mismatch_kept"] += 1
    return merged, stats


def read_current(path: Path) -> dict:
    """Existing {(block, date): (value, source)} from the current-truth CSV."""
    rows: dict = {}
    if not path.exists():
        return rows
    import pandas as pd

    df = pd.read_csv(path, usecols=["block", "date", "rainfall_mm", "source"],
                     parse_dates=["date"])
    for _, r in df.iterrows():
        d = r["date"].date() if hasattr(r["date"], "date") else r["date"]
        rows[(str(r["block"]), d.isoformat())] = (float(r["rainfall_mm"]), str(r["source"]))
    return rows


def write_current(path: Path, rows: dict, provenance: dict) -> None:
    """Deterministic full rewrite (sorted) + provenance sidecar, atomic."""
    import pandas as pd

    recs = [{"block": b, "date": d, "rainfall_mm": v, "source": s}
            for (b, d), (v, s) in sorted(rows.items())]
    df = pd.DataFrame(recs, columns=["block", "date", "rainfall_mm", "source"])
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    os.close(fd)
    try:
        df.to_csv(tmp, index=False)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    Path(str(path) + ".provenance.json").write_text(
        json.dumps(provenance, indent=2), encoding="utf-8")


def update_range(start: date, end: date, out: Path, boundary=None,
                 probe=remote_exists, extract=extract_day) -> dict:
    """Fetch [start, end], merge into `out`. Returns summary stats."""
    import geopandas as gpd

    if boundary is None:
        boundary = gpd.read_file(BOUNDARY, layer=BOUNDARY_LAYER)
    existing = read_current(out)
    new: dict = {}
    stats = {"days_final": 0, "days_prelim": 0, "days_missing": 0, "day_errors": []}
    day = start
    while day <= end:
        stage = select_stage(day, probe)
        if stage is None:
            stats["days_missing"] += 1
            day += timedelta(days=1)
            continue
        try:
            vals = extract(url_for(stage, day), boundary)
        except Exception as exc:  # unreadable raster: skip day, never fabricate
            stats["day_errors"].append(f"{day.isoformat()}: {exc}")
            day += timedelta(days=1)
            continue
        src = SOURCE_FINAL if stage == "final" else SOURCE_PRELIM
        for block, val in vals.items():
            new[(block, day.isoformat())] = (val, src)
        stats["days_final" if stage == "final" else "days_prelim"] += 1
        day += timedelta(days=1)
    merged, mstats = merge_rows(existing, new)
    provenance = {
        "product": "CHIRPS v3.0 daily sat (UCSB Climate Hazards Center)",
        "disaggregation": "sat (IMERG-split); rnl (ERA5-split) excluded by design",
        "window": [start.isoformat(), end.isoformat()],
        "updated_at_utc": datetime.now(tz=timezone.utc).isoformat(),
        "frozen_file_untouched": "data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv",
        "blocks": list(BLOCKS),
    }
    write_current(out, merged, provenance)
    return {"out": str(out), "rows": len(merged), **stats, **mstats}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Idempotent CHIRPS v3 current-truth update (2026+).")
    ap.add_argument("--start", default="2026-01-01")
    ap.add_argument("--end", default=None, help="YYYY-MM-DD (default: today UTC)")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--dry-run", action="store_true",
                    help="probe stage availability per day without reading rasters")
    args = ap.parse_args(argv)
    start = date.fromisoformat(args.start)
    end = date.fromisoformat(args.end) if args.end else datetime.now(tz=timezone.utc).date()
    if args.dry_run:
        for k in range((end - start).days + 1):
            d = start + timedelta(days=k)
            print(f"{d.isoformat()}: {select_stage(d)}")
        return 0
    print(json.dumps(update_range(start, end, Path(args.out)), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
