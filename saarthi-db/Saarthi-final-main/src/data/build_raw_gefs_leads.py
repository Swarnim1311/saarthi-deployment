"""
Phase 1B — Raw NOAA GEFS APCP ingestion (separate comparator arm, 2026-09-15).

Product: NOAA GEFS operational APCP (accumulated precipitation) 6-hour buckets,
00Z init ONLY, leads D+1..D+7 (lead 0 excluded), ensemble mean, Punjab box,
area-weighted zonal means over the 6 Bhuvan blocks.

Verified archive facts (probed 2026-09-15, no bulk download):
  bucket: noaa-gefs-pds (S3, anonymous, us-east-1)
  2016-2020 layout (flat): gefs.YYYYMMDD/HH/{gec00,gep01..gep20}.tHz.pgrb2afHHH
      + .idx ; 21 members (GEFS v11 era; v12 from Sep 2020)
  2021+ layout: gefs.YYYYMMDD/HH/atmos/pgrb2sp25/{gec00,gep01..gep30,
      geavg}.tHz.pgrb2s.0p25.fHHH + .idx ; 31 members
  APCP message: 6-h bucket [H-6,H], units kg/m^2 == mm, 1:1.
  Daily target T=D+k (k=1..7): APCP(f{24k+6})+APCP(f{24k+12})+APCP(f{24k+18})
      +APCP(f{24k+24})  =>  f030..f192.
  Per-message byte ranges come from the adjacent .idx file, fetched with HTTP
  Range requests (KBs per message, nothing global is staged on disk).

Method: S3 index + Range GET -> rasterio/GDAL in-memory GRIB2 decode
(existing stack, no new dependency) -> windowed read over Sangrur bounds ->
geometry_mask zonal mean (same code path as build_gefs_leads_vsicurl.py).

Outputs (NEVER touch the CHIRPS-GEFS baseline files):
  data/processed/phase1b/raw_gefs_apcp_leads.csv
  data/processed/phase1b/raw_gefs_apcp_leads.parquet (rewritten at end)
  data/processed/phase1b/raw_gefs_apcp_log.csv
  data/processed/phase1b/raw_gefs_apcp_manifest.json (missing/failed dates)

Resumable: reruns skip forecast_dates already complete (42 rows).
Usage:
  python src/data/build_raw_gefs_leads.py --test
  python src/data/build_raw_gefs_leads.py --verify-mean --test
  python src/data/build_raw_gefs_leads.py --split test --limit 20
  python src/data/build_raw_gefs_leads.py --full
"""
import argparse
import concurrent.futures as cf
import io
import json
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import rasterio
from rasterio.features import geometry_mask
from rasterio.io import MemoryFile

PROJECT = Path(__file__).resolve().parents[2]
BOUNDARY_GPKG = PROJECT / "data" / "raw" / "boundaries" / "sangrur_blocks_bhuvan.gpkg"
LAYER = "sangrur_blocks"
BLOCK_COL = "b_name"
PHASE1B_DIR = PROJECT / "data" / "processed" / "phase1b"
OUT_CSV = PHASE1B_DIR / "raw_gefs_apcp_leads.csv"
OUT_PARQUET = PHASE1B_DIR / "raw_gefs_apcp_leads.parquet"
LOG_CSV = PHASE1B_DIR / "raw_gefs_apcp_log.csv"
MANIFEST = PHASE1B_DIR / "raw_gefs_apcp_manifest.json"

BUCKET = "https://noaa-gefs-pds.s3.amazonaws.com"
EXPECTED_BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
N_LEADS = 7
N_WORKERS = 4
RETRIES = 4
INIT_HOUR = "00"

SOURCE = "noaa-gefs-pds"
PRODUCT = "raw-gefs-apcp"
STATISTIC = "ensemble_mean"
INIT_TIME = "00Z"


def split_for(year: int) -> str:
    if 2016 <= year <= 2019:
        return "train"
    if 2021 <= year <= 2022:
        return "val"
    if 2023 <= year <= 2025:
        return "test"
    raise ValueError(f"year {year} not in JJAS acquisition plan")


def build_target_dates() -> pd.DatetimeIndex:
    days = pd.date_range("2016-06-01", "2025-09-30", freq="D")
    days = [d for d in days if d.month in (6, 7, 8, 9) and d.year != 2020
            and (2016 <= d.year <= 2019 or 2021 <= d.year <= 2025)]
    return pd.DatetimeIndex(sorted(days))


def load_blocks():
    gdf = gpd.read_file(BOUNDARY_GPKG, layer=LAYER).copy()
    if str(gdf.crs) == "OGC:CRS84":
        gdf = gdf.set_crs("EPSG:4326", allow_override=True)
    else:
        gdf = gdf.to_crs("EPSG:4326")
    blocks = []
    for _, row in gdf.iterrows():
        name = str(row[BLOCK_COL]).strip()
        if name.lower() == "lehragaga":
            name = "Lehra"
        blocks.append((name, row.geometry))
    blocks = sorted(blocks)
    assert [b for b, _ in blocks] == EXPECTED_BLOCKS, [b for b, _ in blocks]
    return blocks


def _http_get(url: str, byte_range: tuple = None, timeout=60) -> bytes:
    req = urllib.request.Request(url)
    if byte_range is not None:
        lo, hi = byte_range
        req.add_header("Range", f"bytes={lo}-{hi}" if hi is not None else f"bytes={lo}-")
    last = None
    for attempt in range(1, RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - retry transient network errors
            last = e
            time.sleep(2 * attempt)
    raise RuntimeError(f"GET failed {url[:120]}: {last}")


def _key_exists(key: str) -> bool:
    url = f"{BUCKET}/?list-type=2&prefix={key}&max-keys=2"
    try:
        import xml.etree.ElementTree as ET
        d = _http_get(url, timeout=30).decode()
        root = ET.fromstring(d)
        ns = {"s": "http://s3.amazonaws.com/doc/2006-03-01/"}
        return any(k.text == key for k in root.findall("s:Contents/s:Key", ns))
    except Exception:
        return False


def layout_for(issue: pd.Timestamp):
    """Return (members, base, file-pattern fn) for an issue date.

    Verified 2026-09-15: 2016-2020 operational files are GEFS v11 on a 1.0°
    grid whose range-sliced APCP messages GDAL decodes as (1,1) — unusable for
    zonal means (probed: gefs.20180715/00/gec00.t00z.pgrb2af030). Full-file
    v11 pulls (~4 MB x 28 steps x 21 members per init) are cost-prohibitive,
    and v11 is a DIFFERENT model from v12, so 2016-2020 is excluded from the
    raw-APCP arm (recorded in manifest, never silently filled). The frozen
    CHIRPS-GEFS baseline already covers those years consistently.
    """
    if issue.year <= 2020:
        raise RuntimeError(
            f"{issue.date()}: pre-2021 operational GEFS is v11 (1.0 grid, "
            "range-slice undecodable, different model from v12). Excluded from "
            "raw-APCP arm by design; CHIRPS-GEFS baseline covers this period.")
    members = ["gec00"] + [f"gep{i:02d}" for i in range(1, 31)]

    def pat(member: str, step: int, for_issue: pd.Timestamp) -> str:
        # Key MUST be built from the target issue date (never a cached probe
        # date): a per-era closure over one date silently fetched one run's
        # files for every date (caught 2026-09-15 by REF_TIME validation).
        base = f"gefs.{for_issue.strftime('%Y%m%d')}/{INIT_HOUR}/atmos/pgrb2sp25"
        return f"{base}/{member}.t{INIT_HOUR}z.pgrb2s.0p25.f{step:03d}"
    base = f"gefs.{issue.strftime('%Y%m%d')}/{INIT_HOUR}/atmos/pgrb2sp25"
    return members, base, pat


def verify_members(issue: pd.Timestamp, members, base, pat) -> list:
    """Confirm the assumed member list against the archive (cheap key probes)."""
    step = 30
    ok = []
    for m in members:
        try:
            if _key_exists(pat(m, step, issue)):
                ok.append(m)
        except RuntimeError:
            pass
    if ok != members:
        raise RuntimeError(f"member mismatch {issue.date()}: assumed {len(members)}, found {len(ok)}")
    return ok


def apcp_byte_range(file_key: str):
    """Parse .idx for the APCP surface 6-h accumulation message byte range."""
    idx = _http_get(f"{BUCKET}/{file_key}.idx", timeout=60).decode()
    lines = idx.splitlines()
    for i, line in enumerate(lines):
        parts = line.split(":")
        if len(parts) > 6 and parts[3] == "APCP" and parts[4] == "surface" and "acc" in line:
            start = int(parts[1])
            end = int(lines[i + 1].split(":")[1]) - 1 if i + 1 < len(lines) else None
            return start, end, line[:140]
    raise RuntimeError(f"no APCP surface accumulation message in {file_key}.idx")


def fetch_apcp_window(file_key: str, bounds, timeout=90):
    """Range-fetch one APCP message, decode, return cropped array + transform + tags."""
    start, end, _desc = apcp_byte_range(file_key)
    raw = _http_get(f"{BUCKET}/{file_key}", byte_range=(start, end), timeout=timeout)
    if not raw.startswith(b"GRIB") or not raw.rstrip().endswith(b"7777"):
        raise RuntimeError(f"bad GRIB payload for {file_key} ({len(raw)} bytes)")
    with MemoryFile(raw) as mem:
        with mem.open() as src:
            if src.count < 1:
                raise RuntimeError(f"no bands in {file_key}")
            tags = dict(src.tags(1))
            tags["_file_key"] = file_key  # provenance for REF-mismatch diagnosis
            if "APCP" not in tags.get("GRIB_ELEMENT", ""):
                raise RuntimeError(f"unexpected element {tags} in {file_key}")
            west, south, east, north = bounds
            # Pad by >1 cell: floor(offsets)+ceil(lengths) can otherwise shave
            # the partial edge row/col (probed 2026-09-15: south blocks fell
            # outside the window on the 0.25-degree grid). Padding keeps the
            # outward rounding conservative; geometry_mask still clips exactly.
            pad = max(abs(src.res[0]), abs(src.res[1])) * 1.5
            window = src.window(west - pad, south - pad, east + pad, north + pad)
            window = window.round_lengths(op="ceil").round_offsets(op="floor")
            if window.row_off < 0 or window.col_off < 0 or window.height < 1 or window.width < 1:
                raise RuntimeError(f"empty raster window for {file_key} (grid mismatch?)")
            arr = src.read(1, window=window).astype(float)
            transform = src.window_transform(window)
            if transform.is_identity:
                raise RuntimeError(f"identity transform for {file_key} (undecodable grid)")
            if arr.size < 4:
                raise RuntimeError(f"degenerate {arr.shape} field for {file_key}")
            return arr, transform, tags


def zonal_means(arr, transform, blocks):
    # all_touched=True: at 0.25-degree cells (~27 km) some blocks (e.g. Lehra)
    # contain zero cell CENTERS; touched-cell means keep all 6 blocks mapped.
    # (The 0.05-degree CHIRPS-GEFS path uses center-rule; the difference is
    # documented in the Phase 1B report as a coarse-grid necessity.)
    out = {}
    for name, geom in blocks:
        m = geometry_mask([geom], out_shape=arr.shape, transform=transform, invert=True,
                          all_touched=True)
        vals = arr[m]
        vals = vals[np.isfinite(vals)]
        out[name] = (float(np.mean(vals)) if vals.size else float("nan"), int(vals.size))
    if not all(v[1] > 0 for v in out.values()):
        empty = [k for k, v in out.items() if v[1] == 0]
        raise RuntimeError(f"blocks with zero grid cells: {empty}")
    return out


def bucket_mean(issue: pd.Timestamp, step: int, blocks, bounds, members, base, pat,
                use_geavg: bool):
    """Ensemble-mean APCP bucket (mm) per block for one forecast step."""
    if use_geavg:
        arr, transform, tags = fetch_apcp_window(pat("geavg", step, issue), bounds)
        check_tags(tags, issue, step)
        return zonal_means(arr, transform, blocks), len(members), "geavg"
    acc = {name: [] for name, _ in blocks}
    pix = {}
    for m in members:
        arr, transform, tags = fetch_apcp_window(pat(m, step, issue), bounds)
        check_tags(tags, issue, step)
        z = zonal_means(arr, transform, blocks)
        for name, (v, n) in z.items():
            acc[name].append(v)
            pix[name] = n
    out = {}
    for name, vals in acc.items():
        a = np.array(vals, dtype=float)
        out[name] = (float(np.nanmean(a)) if np.isfinite(a).any() else float("nan"), pix[name])
    return out, len(members), "member_mean"


def check_tags(tags: dict, issue: pd.Timestamp, step: int):
    # GDAL reports GRIB_REF_TIME as epoch seconds ("1531612800") or ISO.
    ref = tags.get("GRIB_REF_TIME", "")
    try:
        ref_dt = datetime.fromtimestamp(int(ref), tz=timezone.utc)
    except (TypeError, ValueError):
        ref_dt = datetime.fromisoformat(str(ref).replace("Z", "+00:00"))
    exp_dt = datetime(issue.year, issue.month, issue.day, tzinfo=timezone.utc)
    if ref_dt != exp_dt:
        raise RuntimeError(f"REF_TIME {ref} != init {exp_dt.isoformat()} "
                           f"(file {tags.get('_file_key')})")
    # GDAL reports the accumulation-interval START for APCP buckets:
    # f{step} holds [step-6, step]h, so FORECAST_SECONDS == (step-6)*3600.
    fsec = int(tags.get("GRIB_FORECAST_SECONDS", "-1"))
    if fsec != (step - 6) * 3600:
        raise RuntimeError(f"FORECAST_SECONDS {fsec} != bucket start f{step - 6}")
    if tags.get("GRIB_UNIT", "") != "[kg/(m^2)]":
        raise RuntimeError(f"unexpected units {tags.get('GRIB_UNIT')}")


def process_issue_date(issue: pd.Timestamp, blocks, bounds, members, base, pat,
                       use_geavg: bool) -> pd.DataFrame:
    rows = []
    era = "v12-31mem" if issue.year >= 2021 else "v11-21mem"
    for k in range(1, N_LEADS + 1):
        steps = [24 * k + h for h in (6, 12, 18, 24)]
        day_vals, day_pix, mean_src, member_n = None, {}, None, 0
        acc = {name: [] for name, _ in blocks}
        for s in steps:
            z, member_n, mean_src = bucket_mean(issue, s, blocks, bounds, members, base, pat,
                                                use_geavg)
            for name, (v, n) in z.items():
                acc[name].append(v)
                day_pix[name] = n
        target = issue + pd.Timedelta(days=k)
        for name in [b for b, _ in blocks]:
            a = np.array(acc[name], dtype=float)
            mm = float(np.nansum(a)) if np.isfinite(a).all() else float("nan")
            rows.append({
                "forecast_date": issue.strftime("%Y-%m-%d"),
                "split": split_for(issue.year),
                "lead_day": k,
                "block": name,
                "forecast_target_date": target.strftime("%Y-%m-%d"),
                "forecast_rainfall_mm": mm,
                "valid_pixel_count": day_pix[name],
                "source": SOURCE,
                "product": PRODUCT,
                "statistic": STATISTIC,
                "init_time": INIT_TIME,
                "member_count": member_n,
                "mean_source": mean_src,
                "model_era": era,
            })
    df = pd.DataFrame(rows)
    assert len(df) == 42 and set(df["block"]) == set(EXPECTED_BLOCKS)
    assert sorted(df["lead_day"].unique().tolist()) == [1, 2, 3, 4, 5, 6, 7]
    assert (pd.to_datetime(df["forecast_target_date"]) > pd.to_datetime(df["forecast_date"])).all()
    if (df["forecast_rainfall_mm"] < -1e-6).any():
        raise RuntimeError("negative rainfall from bucket sums (bad differencing?)")
    return df


def completed_dates() -> set:
    if not OUT_CSV.exists():
        return set()
    try:
        df = pd.read_csv(OUT_CSV, usecols=["forecast_date", "block", "lead_day"])
    except Exception:
        return set()
    counts = df.groupby("forecast_date").size()
    return set(counts[counts == 42].index.astype(str).tolist())


def append_rows(df: pd.DataFrame):
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    header = not OUT_CSV.exists()
    df.to_csv(OUT_CSV, mode="a", header=header, index=False)
    with open(LOG_CSV, "a") as f:
        if LOG_CSV.stat().st_size == 0 if LOG_CSV.exists() else True:
            f.write("forecast_date,split,status,n_rows,error\n")
        f.write(f"{df['forecast_date'].iloc[0]},{df['split'].iloc[0]},success,{len(df)},\n")


def log_failure(issue_str: str, err: str):
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG_CSV, "a") as f:
        if not LOG_CSV.exists() or LOG_CSV.stat().st_size == 0:
            f.write("forecast_date,split,status,n_rows,error\n")
        safe = str(err).replace("\n", " ").replace(",", ";")[:300]
        f.write(f"{issue_str},,failed,0,{safe}\n")


def update_manifest(done: set, failed: list):
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    man = {}
    if MANIFEST.exists():
        try:
            man = json.loads(MANIFEST.read_text())
        except Exception:
            man = {}
    man.update({
        "updated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "completed_dates": sorted(done),
        "failed_dates": sorted(set(man.get("failed_dates", [])) | set(failed)),
        "source": SOURCE, "product": PRODUCT,
    })
    MANIFEST.write_text(json.dumps(man, indent=2))


def run(dates, blocks, bounds, workers: int, limit=None, use_geavg_new_era=True):
    done = completed_dates()
    todo = [d for d in dates if d.strftime("%Y-%m-%d") not in done]
    if limit:
        todo = todo[:limit]
    print(f"target dates: {len(dates)}, complete: {len(done)}, to process: {len(todo)}", flush=True)
    # per-era layout (verified once per era, not per date)
    era_cfg, era_failed = {}, {}
    for era_year in sorted(set(d.year for d in todo)):
        probe = next(d for d in todo if d.year == era_year)
        try:
            members, base, pat = layout_for(probe)
            members = verify_members(probe, members, base, pat)
            era_cfg[era_year] = (members, base, pat)
            print(f"era {era_year}: {len(members)} members, base={base}", flush=True)
        except RuntimeError as e:
            era_failed[era_year] = str(e)
            print(f"era {era_year} EXCLUDED: {e}", flush=True)
    ok, failed = 0, []
    for d in [d for d in todo if d.year in era_failed]:
        log_failure(d.strftime("%Y-%m-%d"), era_failed[d.year])
        failed.append(d.strftime("%Y-%m-%d"))
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {}
        for d in todo:
            if d.year in era_failed:
                continue
            members, base, pat = era_cfg[d.year]
            use_geavg = use_geavg_new_era and d.year >= 2021
            if use_geavg and not _key_exists(pat("geavg", 30, d)):
                use_geavg = False
            futs[ex.submit(process_issue_date, d, blocks, bounds, members, base, pat,
                           use_geavg)] = d
        for fut in cf.as_completed(futs):
            d = futs[fut]
            try:
                append_rows(fut.result(timeout=1800))
                ok += 1
            except Exception as e:  # noqa: BLE001
                log_failure(d.strftime("%Y-%m-%d"), e)
                failed.append(d.strftime("%Y-%m-%d"))
            if (ok + len(failed)) % 10 == 0:
                print(f"  progress: {ok} ok, {len(failed)} failed", flush=True)
    done = completed_dates()
    update_manifest(done, failed)
    print(f"DONE: {ok} ok, {len(failed)} failed", flush=True)
    if failed:
        print("failed dates:", failed, flush=True)
    return failed


def verify_geavg(issue: pd.Timestamp, blocks, bounds, step=30):
    """One-step check: geavg APCP == mean of all member APCPs ( same bucket)."""
    members, base, pat = layout_for(issue)
    members = verify_members(issue, members, base, pat)
    arr_g, tr_g, _ = fetch_apcp_window(pat("geavg", step, issue), bounds)
    stack = []
    for m in members:
        arr, tr, _ = fetch_apcp_window(pat(m, step, issue), bounds)
        assert arr.shape == arr_g.shape, (m, arr.shape, arr_g.shape)
        stack.append(arr)
    mean_arr = np.nanmean(np.stack(stack), axis=0)
    diff = np.abs(arr_g - mean_arr)
    print(f"geavg vs member-mean step f{step}: max|diff|={np.nanmax(diff):.6f} mm, "
          f"mean|diff|={np.nanmean(diff):.6f} mm", flush=True)
    return float(np.nanmax(diff))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", action="store_true",
                    help="3 dates: 2018-07-15 (v11, records designed exclusion), "
                         "2022-08-01 + 2024-08-01 (v12 geavg, must succeed)")
    ap.add_argument("--verify-mean", action="store_true",
                    help="compare geavg vs member mean on one step, then exit")
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--split", choices=["train", "val", "test"], default=None)
    ap.add_argument("--workers", type=int, default=N_WORKERS)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--no-geavg", action="store_true",
                    help="force member-mean even where geavg exists")
    args = ap.parse_args()

    blocks = load_blocks()
    bounds = [g.bounds for _, g in blocks]
    sangrur_box = (min(b[0] for b in bounds), min(b[1] for b in bounds),
                   max(b[2] for b in bounds), max(b[3] for b in bounds))
    print(f"blocks ok: {[b for b, _ in blocks]} box={sangrur_box}", flush=True)

    if args.verify_mean:
        issue = pd.Timestamp("2024-08-01")
        verify_geavg(issue, blocks, sangrur_box)
        return

    all_dates = build_target_dates()
    if args.test:
        dates = [pd.Timestamp("2018-07-15"), pd.Timestamp("2022-08-01"),
                 pd.Timestamp("2024-08-01")]
    elif args.split:
        dates = [d for d in all_dates if split_for(d.year) == args.split]
    elif args.full:
        dates = list(all_dates)
    else:
        ap.error("pass --test, --full, or --split {train,val,test}")

    failed = run(dates, blocks, sangrur_box, args.workers, limit=args.limit,
                 use_geavg_new_era=not args.no_geavg)
    if OUT_CSV.exists():
        df = pd.read_csv(OUT_CSV)
        df.to_parquet(OUT_PARQUET, index=False)
        print(f"wrote {OUT_PARQUET} shape={df.shape}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
