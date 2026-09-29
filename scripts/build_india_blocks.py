#!/usr/bin/env python3
"""Build Saarthi's India-wide block geography registry from LGD data.

Pipeline (pandas + shapely + stdlib, resumable, no fabrication):
  1. Fetch the Ministry of Panchayati Raj LGD block table (India Data Portal
     CKAN mirror, ODbL): block_name + block_code + district + state codes.
  2. Normalize + dedupe on (state_code, district_code, block_code).
  3. Attach TRUE polygon bbox-centres from the bharatlas LGD block polygons
     (CC0, LGD snapshot 2024, full LGD code chain) joined on block_lgd.
     Same bbox-centre method as the verified Sangrur seed.
  4. Overlay the verified Sangrur seed rows (Bhuvan polygon bbox-centres) --
     seed wins on code collision; seed-only rows (Moonak/Bhuvan) are kept.
  5. Geocode ONLY rows with no polygon (keyless Open-Meteo geocoder),
     accepting ONLY results whose admin1 matches the LGD state
     (normalized). Unmatched/unreachable rows are SKIPPED and reported --
     never invented.
  6. Write website/Saarthi/src/main/resources/geography/blocks.csv with a
     location_method column (bhuvan_polygon_centroid | polygon_centroid |
     geocoded_centroid).

Usage:
  python scripts/build_india_blocks.py [--limit N] [--no-geocode]
      [--out PATH] [--polygons PATH]
  Defaults: --polygons points at the bharatlas LGD_Blocks.parquet scratch
  copy (download once, CC0). Without --polygons every non-seed row is
  geocoded (slow, lower coverage).
  Progress/cache live in data/processed/geography/ (safe to re-run; geocode
  results are cached so a re-run only fetches new rows).

НЕ invents coordinates: rows without verified coordinates are dropped.
"""

import csv
import json
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_CSV = REPO / "website" / "Saarthi" / "src" / "main" / "resources" / "geography" / "blocks.csv"
WORK = REPO / "data" / "processed" / "geography"
CACHE_FILE = WORK / "geocode_cache.json"
PROVENANCE_FILE = WORK / "blocks_build.json"

BLOCK_CSV_URL = (
    "https://ckandev.indiadataportal.com/dataset/a7419751-ac37-46ad-b938-638cda7b7b60"
    "/resource/bdf22015-a213-4e23-b693-ca40bec4346e/download/block-lgd-codes.csv"
)
POLYGON_PARQUET_DEFAULT = (
    "C:/Users/Swarnim/AppData/Local/Temp/opencode/LGD_Blocks.parquet")
GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
GEOCODE_DELAY_S = 0.05
GEOCODE_TIMEOUT_S = 25
GEOCODE_WORKERS = 6
GEOCODE_COUNT = 3

# India bounds (generous, includes islands). Outside -> row dropped.
LAT_MIN, LAT_MAX = 6.0, 38.0
LON_MIN, LON_MAX = 68.0, 98.0

# Seed rows whose coordinates are Bhuvan polygon bbox-centres
# (keys: state_code, district_code, block_code). All other seed rows use
# admin-verified Open-Meteo geocoder town locations.
BHUVAN_SEED_KEYS = {("3", "43", "343"), ("3", "43", "344"), ("3", "43", "347"),
                    ("3", "43", "350"), ("3", "43", "bhuvan_b_274"),
                    ("3", "737", "339")}

# Verified seed rows: (state_code, state_name, district_code, district_name,
# block_code, block_name, lat, lon). BHUVAN_SEED_KEYS rows are Bhuvan polygon
# bbox-centres; the rest are admin-verified geocoder town locations.
SEED_ROWS = [
    ("3", "Punjab", "43", "Sangrur", "343", "Dhuri", 30.41125, 75.83215),
    ("3", "Punjab", "43", "Sangrur", "344", "Lehragaga", 29.92550, 75.81969),
    ("3", "Punjab", "43", "Sangrur", "347", "Sangrur", 30.24907, 75.88043),
    ("3", "Punjab", "43", "Sangrur", "350", "Sunam", 30.06990, 75.86470),
    ("3", "Punjab", "43", "Sangrur", "bhuvan_b_274", "Moonak", 29.82521, 75.96939),
    ("3", "Punjab", "737", "Malerkotla", "339", "Malerkotla (Head Quarter)", 30.53317, 75.87087),
    ("3", "Punjab", "36", "Ludhiana", "295", "Khanna", 30.70547, 76.22196),
    ("3", "Punjab", "36", "Ludhiana", "296", "Ludhiana-1", 30.91204, 75.85379),
    ("6", "Haryana", "63", "Hisar", "489", "Hisar-I", 29.15394, 75.72294),
    ("6", "Haryana", "63", "Hisar", "486", "Barwala", 29.36747, 75.90809),
    ("9", "Uttar Pradesh", "162", "Lucknow", "1334", "Malihabad", 26.92223, 80.71078),
    ("9", "Uttar Pradesh", "162", "Lucknow", "1335", "Mohanlalganj", 26.67985, 80.98237),
    ("10", "Bihar", "212", "Patna", "1965", "Patna Sadar", 25.59408, 85.13563),
    ("10", "Bihar", "212", "Patna", "1950", "Bihta", 25.55885, 84.87141),
]


def log(msg):
    print(f"[build-india-blocks] {msg}", flush=True)


def norm(s):
    return "".join(ch for ch in (s or "").lower() if ch.isalnum())


def fetch_text(url, timeout=90):
    req = urllib.request.Request(url, headers={"User-Agent": "Saarthi-geography-builder/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8-sig", "replace")


def load_lgd_rows():
    log(f"fetching LGD block table: {BLOCK_CSV_URL}")
    text = fetch_text(BLOCK_CSV_URL)
    rows = list(csv.DictReader(text.splitlines()))
    log(f"LGD source rows: {len(rows)}")
    return rows


def dedupe(rows):
    """Dedupe on (state_code, district_code, block_code); report collisions."""
    seen = {}
    collisions = []
    for r in rows:
        key = (code_str(r["state_code"]), code_str(r["district_code"]),
               code_str(r["block_code"]))
        if not all(key):
            collisions.append({"dropped_blank_key": r})
            continue
        if key in seen and seen[key]["block_name"].strip() != r["block_name"].strip():
            collisions.append({"key": key, "kept": seen[key]["block_name"],
                               "dropped": r["block_name"]})
            continue
        seen.setdefault(key, r)
    log(f"deduped blocks: {len(seen)} (collisions/drops: {len(collisions)})")
    return seen, collisions


def code_str(v):
    """LGD codes as canonical strings ("343.0"/343 -> "343")."""
    s = str(v).strip()
    if s.endswith(".0"):
        s = s[:-2]
    return s


def load_polygon_centroids(parquet_path):
    """(state, district, block) LGD triple -> bbox-centre + names.

    True geometric centres (same bbox-centre method as the verified Sangrur
    seed). Duplicate block_lgd values keep the highest block_ver row.
    """
    import pandas as pd
    from shapely import wkb
    df = pd.read_parquet(parquet_path)
    df = df.dropna(subset=["block_lgd"]).copy()
    df["block_lgd"] = df["block_lgd"].astype(int).astype(str)
    if "block_ver" in df.columns:
        df = df.sort_values("block_ver").drop_duplicates("block_lgd", keep="last")
    out, bad = {}, 0
    for _, row in df.iterrows():
        if not str(row.get("block_name", "")).strip():
            bad += 1  # junk geometry row (e.g. block_lgd=0, no name)
            continue
        try:
            g = wkb.loads(bytes(row["geometry"]))
            minx, miny, maxx, maxy = g.bounds
        except Exception:
            bad += 1
            continue
        lat, lon = round((miny + maxy) / 2, 5), round((minx + maxx) / 2, 5)
        if not (LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX):
            bad += 1
            continue
        if lat == 0 and lon == 0:
            bad += 1
            continue
        out[(code_str(row["state_lgd"]), code_str(row["dist_lgd"]),
             code_str(row["block_lgd"]))] = {
            "lat": lat, "lon": lon,
            "state_name": str(row.get("state", "")).strip(),
            "district_name": str(row.get("district", "")).strip(),
            "block_name": str(row.get("block_name", "")).strip()}
    log(f"polygon centroids: {len(out)} (unparseable/out-of-range: {bad})")
    return out


def geocode_one(args):
    """Worker: geocode a single (block, district, state); return result tuple."""
    block, district, state, cache, lock = args
    cache_key = f"{block}|{district}|{state}"
    with lock:
        if cache_key in cache:
            hit = cache[cache_key]
            return ("cached", hit, None)
    queries = [f"{block}, {district}, {state}, India", f"{block}, {state}, India"]
    for qi, q in enumerate(queries):
        params = urllib.parse.urlencode(
            {"name": q, "count": GEOCODE_COUNT, "language": "en", "format": "json"})
        try:
            req = urllib.request.Request(
                GEOCODE_URL + "?" + params,
                headers={"User-Agent": "Saarthi-geography-builder/1.0"})
            with urllib.request.urlopen(req, timeout=GEOCODE_TIMEOUT_S) as r:
                data = json.loads(r.read().decode("utf-8", "replace"))
        except Exception:
            time.sleep(1.0)
            continue
        finally:
            time.sleep(GEOCODE_DELAY_S)
        for res in data.get("results") or []:
            admin1, admin2 = res.get("admin1") or "", res.get("admin2") or ""
            admin3 = res.get("admin3") or ""
            if norm(state) not in norm(admin1) and norm(admin1) not in norm(state):
                continue
            d_match = (norm(district) in norm(admin2) or norm(admin2) in norm(district)
                       or norm(district) in norm(admin3) or norm(admin3) in norm(district)
                       or norm(block) in norm(admin3) or norm(admin3) in norm(block)
                       or norm(block) in norm(admin2))
            if not d_match and qi == 0:
                continue
            lat, lon = res.get("latitude"), res.get("longitude")
            if lat is None or lon is None:
                continue
            if not (LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX):
                continue
            if lat == 0 and lon == 0:
                continue
            prov = {"query": q,
                    "matched_name": res.get("name"),
                    "matched_admin1": admin1,
                    "matched_admin2": admin2,
                    "matched_lat": lat, "matched_lon": lon}
            hit = {"lat": lat, "lon": lon, "prov": prov}
            with lock:
                cache[cache_key] = hit
            return ("ok", hit, None)
    with lock:
        cache[cache_key] = None
    return ("skip", None, {"block": block, "district": district, "state": state,
                           "reason": "no verified geocode match"})


def canonicalize_names(out_rows):
    """One display name per state/district code (codes are the identity).

    States: CKAN Title-Case spelling (state codes are never split).
    Districts: most-frequent 2024-parquet spelling first (district codes WERE
    reused across 2023-24 splits, e.g. Rajasthan), CKAN fallback.
    Seed rows keep verified names. Returns the remap list for provenance.
    """
    state_ckan = {}
    dist_parquet_votes, dist_ckan_votes = defaultdict(Counter), defaultdict(Counter)
    for row in out_rows:
        if row.get("_src") == "seed":
            continue
        if row.get("_src") == "ckan":
            state_ckan.setdefault(row["state_code"], row["state_name"])
            dist_ckan_votes[(row["state_code"], row["district_code"])][row["district_name"]] += 1
        elif row.get("_src") == "parquet":
            dist_parquet_votes[(row["state_code"], row["district_code"])][row["district_name"]] += 1
    dist_canon = {}
    for k in set(dist_parquet_votes) | set(dist_ckan_votes):
        if dist_parquet_votes[k]:
            dist_canon[k] = dist_parquet_votes[k].most_common(1)[0][0]
        else:
            dist_canon[k] = dist_ckan_votes[k].most_common(1)[0][0]
    remaps = []
    for row in out_rows:
        if row.get("_src") == "seed":
            continue
        sc = row["state_code"]
        if state_ckan.get(sc) and row["state_name"] != state_ckan[sc]:
            remaps.append({"code": sc, "from": row["state_name"],
                           "to": state_ckan[sc], "level": "state"})
            row["state_name"] = state_ckan[sc]
        dk = (row["state_code"], row["district_code"])
        if dk in dist_canon and row["district_name"] != dist_canon[dk]:
            remaps.append({"code": dk[1], "from": row["district_name"],
                           "to": dist_canon[dk], "level": "district"})
            row["district_name"] = dist_canon[dk]
    # Parquet UPPER-CASE state names where CKAN has no row for the code.
    for row in out_rows:
        if row.get("_src") == "parquet" and not state_ckan.get(row["state_code"]):
            titled = row["state_name"].title()
            if titled != row["state_name"]:
                remaps.append({"code": row["state_code"], "from": row["state_name"],
                               "to": titled, "level": "state"})
                row["state_name"] = titled
    return remaps


def main():
    limit = None
    do_geocode = True
    out_csv = OUT_CSV
    polygons_arg = None
    for a in sys.argv[1:]:
        if a.startswith("--limit="):
            limit = int(a.split("=", 1)[1])
        elif a == "--no-geocode":
            do_geocode = False
        elif a.startswith("--out="):
            out_csv = Path(a.split("=", 1)[1])
        elif a.startswith("--polygons="):
            polygons_arg = a.split("=", 1)[1]
    WORK.mkdir(parents=True, exist_ok=True)
    cache = {}
    if CACHE_FILE.exists():
        cache = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
        log(f"geocode cache loaded: {len(cache)} entries")

    lgd_rows = load_lgd_rows()
    if limit:
        lgd_rows = lgd_rows[:limit]
    by_key, collisions = dedupe(lgd_rows)

    polygons = {}
    if polygons_arg != "none":
        ppath = polygons_arg or POLYGON_PARQUET_DEFAULT
        if Path(ppath).exists():
            log(f"loading block polygons: {ppath}")
            polygons = load_polygon_centroids(ppath)
        else:
            log(f"WARNING: polygon parquet not found at {ppath}; "
                "non-seed rows without polygons will be geocoded")

    # Seed overlay (verified coords win; seed-only rows kept).
    seed_keys = set()
    for s in SEED_ROWS:
        key = (code_str(s[0]), code_str(s[2]), code_str(s[4]))
        seed_keys.add(key)
        by_key[key] = {"state_code": s[0], "state_name": s[1],
                       "district_code": s[2], "district_name": s[3],
                       "block_code": s[4], "block_name": s[5],
                       "_seed_lat": s[6], "_seed_lon": s[7]}

    out_rows = []
    skipped = []
    provenance_geocoded = {}
    n_polygon_attached = 0
    n_parquet_only = 0
    todo = []  # (key, row) needing geocoding
    for key, r in sorted(by_key.items()):
        if key in seed_keys:
            out_rows.append({"state_code": r["state_code"], "state_name": r["state_name"],
                             "district_code": r["district_code"],
                             "district_name": r["district_name"],
                             "block_code": r["block_code"], "block_name": r["block_name"],
                             "latitude": r["_seed_lat"], "longitude": r["_seed_lon"],
                             "location_method": "bhuvan_polygon_centroid"
                             if key in BHUVAN_SEED_KEYS else "geocoded_centroid",
                             "_src": "seed"})
            continue
        if key in polygons:
            # CKAN names win (LGD table authority); coords are true geometry.
            p = polygons[key]
            out_rows.append({"state_code": r["state_code"].strip(),
                             "state_name": r["state_name"].strip(),
                             "district_code": r["district_code"].strip(),
                             "district_name": r["district_name"].strip(),
                             "block_code": r["block_code"].strip(),
                             "block_name": r["block_name"].strip(),
                             "latitude": p["lat"], "longitude": p["lon"],
                             "location_method": "polygon_centroid",
                             "_src": "ckan"})
            n_polygon_attached += 1
            continue
        if not do_geocode:
            skipped.append({"key": ":".join(key), "reason": "geocoding disabled"})
            continue
        todo.append((key, r))

    # Parquet-only codes (2024 LGD snapshot additions missing from the 2023
    # CKAN table): included with parquet names, flagged in provenance.
    for key in sorted(set(polygons) - set(by_key) - seed_keys):
        p = polygons[key]
        out_rows.append({"state_code": key[0], "state_name": p["state_name"],
                         "district_code": key[1], "district_name": p["district_name"],
                         "block_code": key[2], "block_name": p["block_name"],
                         "latitude": p["lat"], "longitude": p["lon"],
                         "location_method": "polygon_centroid",
                         "_src": "parquet"})
        n_parquet_only += 1
    if n_parquet_only:
        log(f"parquet-only rows added: {n_parquet_only}")

    # Canonical display names per code (codes are the identity; names are labels).
    remaps = canonicalize_names(out_rows)
    if remaps:
        log(f"name canonicalizations (pass 1): {len(remaps)}")

    if todo:
        import threading
        from concurrent.futures import ThreadPoolExecutor
        lock = threading.Lock()
        total = len(todo)
        done = [0]

        def work(item):
            key, r = item
            status, hit, skip = geocode_one(
                (r["block_name"], r["district_name"], r["state_name"], cache, lock))
            with lock:
                done[0] += 1
                n = done[0]
            if n % 500 == 0:
                log(f"geocode progress {n}/{total}")
            if hit is None:
                if skip is None:
                    skip = {"block": r["block_name"],
                            "district": r["district_name"],
                            "state": r["state_name"],
                            "reason": "no verified geocode match (cached)"}
                skip.setdefault("key", ":".join(key))
                return (key, None, skip)
            return (key, {"row": r, "lat": hit["lat"],
                          "lon": hit["lon"], "prov": hit["prov"]}, None)

        with ThreadPoolExecutor(max_workers=GEOCODE_WORKERS) as ex:
            for key, got, skip in ex.map(work, todo):
                if skip is not None:
                    skipped.append(skip)
                    continue
                r = got["row"]
                provenance_geocoded[":".join(key)] = got["prov"]
                out_rows.append({"state_code": r["state_code"], "state_name": r["state_name"],
                                 "district_code": r["district_code"],
                                 "district_name": r["district_name"],
                                 "block_code": r["block_code"], "block_name": r["block_name"],
                                 "latitude": round(got["lat"], 5),
                                 "longitude": round(got["lon"], 5),
                                 "location_method": "geocoded_centroid"})
        CACHE_FILE.write_text(json.dumps(cache), encoding="utf-8")

    # Second canonicalization pass: geocoded rows adopt canonical names.
    remaps.extend(canonicalize_names(out_rows))
    if remaps:
        log(f"name canonicalizations total: {len(remaps)} (see provenance)")
    # Final belt-and-braces: no blank/zero key fields ever reach the CSV
    # (the Java registry fails loud on these by design; district "0" is a
    # junk-geometry artefact, e.g. bharatlas block_lgd rows with dist_lgd=0).
    clean = []
    for row in out_rows:
        keys = ("state_code", "state_name", "district_code", "district_name",
                "block_code", "block_name", "location_method")
        if any(not str(row.get(k, "")).strip() for k in keys) \
                or row.get("district_code", "").strip() == "0" \
                or row.get("state_code", "").strip() == "0":
            skipped.append({"key": f"{row.get('state_code')}:{row.get('district_code')}"
                                   f":{row.get('block_code')}",
                            "block": row.get("block_name"),
                            "reason": "blank or zero key field"})
            continue
        clean.append(row)
    out_rows = clean
    for row in out_rows:
        row.pop("_src", None)

    header_comment = (
        "# Saarthi dynamic block geography — India-wide LGD registry.\n"
        "# GENERATED by scripts/build_india_blocks.py — do not hand-edit rows.\n"
        "# Hierarchy + LGD codes: Ministry of Panchayati Raj LGD block table via the\n"
        "# India Data Portal CKAN mirror (ODbL):\n"
        "# https://ckandev.indiadataportal.com/dataset/lgd-codes (block-lgd-codes.csv).\n"
        "# Coordinates: Sangrur/Malerkotla seed rows are Bhuvan polygon bbox-centres\n"
        "# (location_method=bhuvan_polygon_centroid); rows joined to the bharatlas\n"
        "# LGD block polygons use true polygon bbox-centres\n"
        "# (location_method=polygon_centroid); the small remainder without polygons\n"
        "# uses Open-Meteo geocoding town locations accepted ONLY on admin1\n"
        "# (state) match (location_method=geocoded_centroid). Per-row queries in\n"
        "# data/processed/geography/blocks_build.json. Rows without verified\n"
        "# coordinates are DROPPED (never invented) — see skipped list there.\n"
        "# Legacy deviations: 'Moonak' keeps Bhuvan ID bhuvan_b_274 (no current LGD\n"
        "# development-block entry); legacy 'Lehra' = LGD 'Lehragaga' (344).\n"
    )
    with open(out_csv, "w", encoding="utf-8", newline="") as f:
        f.write(header_comment)
        w = csv.DictWriter(f, fieldnames=["state_code", "state_name", "district_code",
                                          "district_name", "block_code", "block_name",
                                          "latitude", "longitude", "location_method"])
        w.writeheader()
        w.writerows(out_rows)

    states = sorted({r["state_code"] + "|" + r["state_name"] for r in out_rows})
    districts = {(r["state_code"], r["district_code"]) for r in out_rows}
    prov = {"sources": {
                "lgd_blocks": BLOCK_CSV_URL,
                "lgd_polygons": "bharatlas LGD Blocks (2024), CC0 "
                "(true polygon bbox-centres; CKAN names kept on join)",
                "geocoder": GEOCODE_URL,
                "seed": "verified 2026-09-23 (Bhuvan polygons + admin-verified geocodes)"},
            "lgd_source_rows": len(lgd_rows),
            "n_polygon_rows": len(polygons),
            "n_polygon_attached": n_polygon_attached,
            "n_parquet_only": n_parquet_only,
            "collisions": collisions[:200],
            "n_collisions": len(collisions),
            "name_canonicalizations": remaps[:300],
            "n_canonicalizations": len(remaps),
            "n_kept": len(out_rows),
            "n_states": len(states),
            "n_districts": len(districts),
            "n_skipped_no_coords": len(skipped),
            "skipped_no_coords": skipped[:500],
            "states": states,
            "geocoded_provenance": provenance_geocoded}
    PROVENANCE_FILE.write_text(json.dumps(prov, indent=1), encoding="utf-8")

    log(f"DONE: kept={len(out_rows)} states={len(states)} "
        f"districts={len(districts)} skipped={len(skipped)}")
    log(f"wrote {out_csv}")
    log(f"wrote {PROVENANCE_FILE}")


if __name__ == "__main__":
    main()
