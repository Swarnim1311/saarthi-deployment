#!/usr/bin/env python3
"""Extract per-block simplified display boundaries from the bharatlas LGD polygon source.

Reads the same polygon parquet used by scripts/build_india_blocks.py
(CC0, LGD snapshot 2024) and writes a compact JSON of SIMPLIFIED display
geometries keyed by the (state_lgd, dist_lgd, block_lgd) triple:

    website/Saarthi/src/main/resources/geography/block_boundaries.json

Simplification (tolerance 0.005 deg, coordinates rounded to 4 decimals) is
DISPLAY ONLY: centroids and all forecast math keep using the authoritative
sources. The backend serves ONE block geometry per request; the browser
never downloads the national file.

Re-run:
    python scripts/extract_block_boundaries.py [--polygons PATH] [--out PATH]
"""

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_PARQUET = Path(
    "C:/Users/Swarnim/AppData/Local/Temp/opencode/LGD_Blocks.parquet")
DEFAULT_OUT = (REPO / "website" / "Saarthi" / "src" / "main" / "resources"
               / "geography" / "block_boundaries.json")
TOLERANCE = 0.005
PRECISION = 4


def code_str(v):
    s = str(v).strip()
    if s.endswith(".0"):
        s = s[:-2]
    return s


def round_coords(obj):
    if isinstance(obj, float):
        return round(obj, PRECISION)
    if isinstance(obj, (list, tuple)):
        return [round_coords(x) for x in obj]
    return obj


def main():
    polygons = None
    out = DEFAULT_OUT
    for a in sys.argv[1:]:
        if a.startswith("--polygons="):
            polygons = Path(a.split("=", 1)[1])
        elif a.startswith("--out="):
            out = Path(a.split("=", 1)[1])
    polygons = polygons or DEFAULT_PARQUET
    if not polygons.exists():
        print(f"[extract-boundaries] polygon source not found: {polygons}")
        print("[extract-boundaries] nothing written (backend will 404 honestly).")
        return 1

    import pandas as pd
    from shapely import wkb

    df = pd.read_parquet(polygons)
    df = df.dropna(subset=["block_lgd"]).copy()
    df["block_lgd"] = df["block_lgd"].astype(int).astype(str)
    if "block_ver" in df.columns:
        df = df.sort_values("block_ver").drop_duplicates("block_lgd", keep="last")

    bounds = {}
    skipped = 0
    for _, row in df.iterrows():
        if not str(row.get("block_name", "")).strip():
            skipped += 1
            continue
        try:
            g = wkb.loads(bytes(row["geometry"]))
        except Exception:
            skipped += 1
            continue
        if g.is_empty:
            skipped += 1
            continue
        s = g.simplify(TOLERANCE, preserve_topology=True)
        if s.is_empty:
            skipped += 1
            continue
        key = (f"{code_str(row['state_lgd'])}:"
               f"{code_str(row['dist_lgd'])}:{code_str(row['block_lgd'])}")
        if key.startswith("0:") or ":0:" in key:
            skipped += 1  # junk-geometry artefact rows
            continue
        geo = s.__geo_interface__
        geo["coordinates"] = round_coords(geo["coordinates"])
        bounds[key] = geo

    doc = {"meta": {"source": "bharatlas LGD Blocks (2024), CC0 — same polygon "
                              "source used by scripts/build_india_blocks.py",
                    "simplify_tolerance_deg": TOLERANCE,
                    "coordinate_precision": PRECISION,
                    "note": "Display boundaries only; not used for any "
                            "forecast or risk calculation.",
                    "count": len(bounds)},
           "boundaries": bounds}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")
    print(f"[extract-boundaries] wrote {out} "
          f"({out.stat().st_size / 1e6:.1f} MB, {len(bounds)} blocks, "
          f"skipped {skipped})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
