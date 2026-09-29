"""
Corrected historical CHIRPS-GEFS acquisition (Notebook 02 v2).

Verified archive structure (2026-09-10):
  folder = forecast ISSUE date D
  folder D contains 16 files: c3g_D.tif ... c3g_D+15.tif (filename = valid/target date)
  => gefs_dk for issue D = file c3g_(D+k).tif from folder D, k = 1..7

Method: /vsicurl/ streaming + block zonal extraction. No raw GeoTIFFs stored.
Outputs (*_v2 = corrected; original v1 files untouched):
  data/processed/historical/historical_gefs_leads_v2.csv
  data/processed/historical/historical_gefs_leads_v2.parquet (written at end)
  data/processed/historical/acquisition_v2_log.csv

Resumable: reruns skip forecast_dates already complete (42 rows = 6 blocks x 7 leads).
Usage:
  python src/data/build_gefs_leads_vsicurl.py --test            # 3 dates smoke test
  python src/data/build_gefs_leads_vsicurl.py --full            # all 1098 JJAS dates
  python src/data/build_gefs_leads_vsicurl.py --split train     # one split only
"""
import argparse
import concurrent.futures as cf
import os
import sys
import time
from datetime import date
from pathlib import Path

# Process-wide GDAL HTTP behavior (honored per request; avoids rasterio Env in threads)
os.environ.setdefault("GDAL_HTTP_TIMEOUT", "60")
os.environ.setdefault("GDAL_HTTP_CONNECTTIMEOUT", "20")
os.environ.setdefault("GDAL_DISABLE_READDIR_ON_OPEN", "EMPTY_DIR")

import geopandas as gpd
import numpy as np
import pandas as pd
import rasterio
from rasterio.features import geometry_mask

PROJECT = Path(__file__).resolve().parents[2]
BOUNDARY_GPKG = PROJECT / "data" / "raw" / "boundaries" / "sangrur_blocks_bhuvan.gpkg"
LAYER = "sangrur_blocks"
BLOCK_COL = "b_name"
HIST_DIR = PROJECT / "data" / "processed" / "historical"
OUT_CSV = HIST_DIR / "historical_gefs_leads_v2.csv"
OUT_PARQUET = HIST_DIR / "historical_gefs_leads_v2.parquet"
LOG_CSV = HIST_DIR / "acquisition_v2_log.csv"
ARCHIVE = "https://data.chc.ucsb.edu/products/CHIRPS-GEFS/v3/daily/global"

EXPECTED_BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
N_LEADS = 7
N_WORKERS = 8
RETRIES = 3


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


def issue_url(issue: pd.Timestamp, target: pd.Timestamp) -> str:
    return (f"{ARCHIVE}/{issue.strftime('%Y/%m/%d')}"
            f"/c3g_{target.strftime('%Y.%m.%d')}.tif")


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


def extract_one_file(vsicurl_url: str, blocks) -> dict:
    """Return {block: (mean_mm, valid_pixels)} for one remote target file."""
    last_err = None
    for attempt in range(1, RETRIES + 1):
        try:
            with rasterio.open(vsicurl_url) as src:
                if src.count < 1 or src.transform.is_identity:
                    raise RuntimeError("no geotransform (bad response)")
                bounds = [g.bounds for _, g in blocks]
                west = min(b[0] for b in bounds)
                south = min(b[1] for b in bounds)
                east = max(b[2] for b in bounds)
                north = max(b[3] for b in bounds)
                window = src.window(west, south, east, north)
                # snap window to raster
                window = window.round_lengths(op="ceil").round_offsets(op="floor")
                arr = src.read(1, window=window).astype(float)
                transform = src.window_transform(window)
                arr[arr == -9999] = np.nan
                out = {}
                for name, geom in blocks:
                    m = geometry_mask([geom], out_shape=arr.shape,
                                      transform=transform, invert=True)
                    vals = arr[m]
                    vals = vals[np.isfinite(vals)]
                    out[name] = (float(np.mean(vals)) if vals.size else float("nan"),
                                 int(vals.size))
                return out
        except Exception as e:  # noqa: BLE001 - retry transient curl errors
            last_err = e
            time.sleep(2 * attempt)
    raise RuntimeError(f"failed {vsicurl_url}: {last_err}")


def process_issue_date(issue: pd.Timestamp, blocks) -> pd.DataFrame:
    rows = []
    for k in range(1, N_LEADS + 1):
        target = issue + pd.Timedelta(days=k)
        url = "/vsicurl/" + issue_url(issue, target)
        vals = extract_one_file(url, blocks)
        for name, (mean_v, n) in vals.items():
            rows.append({
                "forecast_date": issue.strftime("%Y-%m-%d"),
                "split": split_for(issue.year),
                "lead_day": k,
                "block": name,
                "forecast_target_date": target.strftime("%Y-%m-%d"),
                "forecast_rainfall_mm": mean_v,
                "valid_pixel_count": n,
                "source_issue": issue.strftime("%Y.%m.%d"),
                "source_target": target.strftime("%Y.%m.%d"),
            })
    df = pd.DataFrame(rows)
    assert len(df) == 42 and set(df["block"]) == set(EXPECTED_BLOCKS)
    assert sorted(df["lead_day"].unique().tolist()) == [1, 2, 3, 4, 5, 6, 7]
    # lead-0 guard: target dates must be D+1..D+7, never D itself
    assert (pd.to_datetime(df["forecast_target_date"]) > pd.to_datetime(df["forecast_date"])).all()
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
    HIST_DIR.mkdir(parents=True, exist_ok=True)
    header = not OUT_CSV.exists()
    df.to_csv(OUT_CSV, mode="a", header=header, index=False)
    with open(LOG_CSV, "a") as f:
        if LOG_CSV.stat().st_size == 0 if LOG_CSV.exists() else True:
            f.write("forecast_date,split,status,n_rows,error\n")
        f.write(f"{df['forecast_date'].iloc[0]},{df['split'].iloc[0]},success,{len(df)},\n")


def log_failure(issue_str: str, err: str):
    HIST_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG_CSV, "a") as f:
        if not LOG_CSV.exists() or LOG_CSV.stat().st_size == 0:
            f.write("forecast_date,split,status,n_rows,error\n")
        safe = str(err).replace("\n", " ").replace(",", ";")[:300]
        f.write(f"{issue_str},,failed,0,{safe}\n")


def run(dates, blocks, workers: int, limit=None):
    done = completed_dates()
    todo = [d for d in dates if d.strftime("%Y-%m-%d") not in done]
    if limit:
        todo = todo[:limit]
    print(f"target dates: {len(dates)}, complete: {len(done)}, to process: {len(todo)}", flush=True)
    ok, failed = 0, []
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(process_issue_date, d, blocks): d for d in todo}
        for fut in cf.as_completed(futs):
            d = futs[fut]
            try:
                append_rows(fut.result(timeout=900))
                ok += 1
            except Exception as e:  # noqa: BLE001
                log_failure(d.strftime("%Y-%m-%d"), e)
                failed.append(d.strftime("%Y-%m-%d"))
            if (ok + len(failed)) % 25 == 0:
                print(f"  progress: {ok} ok, {len(failed)} failed", flush=True)
    print(f"DONE: {ok} ok, {len(failed)} failed", flush=True)
    if failed:
        print("failed dates:", failed, flush=True)
    return failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", action="store_true")
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--split", choices=["train", "val", "test"], default=None)
    ap.add_argument("--workers", type=int, default=N_WORKERS)
    ap.add_argument("--limit", type=int, default=None,
                    help="process at most N remaining dates (for chunked runs)")
    args = ap.parse_args()

    blocks = load_blocks()
    print(f"blocks ok: {[b for b, _ in blocks]}", flush=True)
    all_dates = build_target_dates()
    print(f"JJAS issue dates 2016-19/21-25: {len(all_dates)} "
          f"({all_dates[0].date()}..{all_dates[-1].date()})", flush=True)

    if args.test:
        dates = [pd.Timestamp("2019-09-04"), pd.Timestamp("2016-07-15"),
                 pd.Timestamp("2024-08-01")]
    elif args.split:
        dates = [d for d in all_dates if split_for(d.year) == args.split]
    elif args.full:
        dates = list(all_dates)
    else:
        ap.error("pass --test, --full, or --split {train,val,test}")

    failed = run(dates, blocks, args.workers, limit=args.limit)
    # rewrite consolidated parquet (v2 only)
    if OUT_CSV.exists():
        df = pd.read_csv(OUT_CSV)
        df.to_parquet(OUT_PARQUET, index=False)
        print(f"wrote {OUT_PARQUET} shape={df.shape}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
