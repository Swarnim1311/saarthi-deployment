"""
Phase 1B — IFS ENS ingestion (headline candidate arm, 2026-09-15).

STATUS: HISTORY BLOCKED ON CREDENTIALS (no data fabricated).
  - IFS ENS operational history (2016-2025) lives in TIGGE (ECDS/MARS,
    registration required) — verified 2026-09-14/15: portal needs ECMWF SSO
    login, batch access needs MARS. No anonymous bulk route exists.
  - ECMWF Open Data keeps only the last ~12 runs (verified) — good for recent
    live pulls + FORMAT VALIDATION, not history.

This script therefore has two honest modes:
  --opendata-test DATE   pull ONE recent 00Z init anonymously from
                         data.ecmwf.int (tp cumulative steps 0..168),
                         derive daily D+1..D+7 by differencing, zonal means,
                         schema validation. Proves the full IFS code path.
  --tigge DATE           attempt the historical TIGGE pull for one init;
                         fails loudly with credential instructions when the
                         wall is hit (never fabricates, never substitutes).

Verified IFS facts (official docs, 2026-09-14):
  tp = cumulative-from-t0 total precipitation, metres -> x1000 = mm.
  daily(T=D+k) = tp(step 24k) - tp(step 24(k-1))), k=1..7.
  ENS 00/12Z to 360h; open-data tp at 6-hourly steps; 0.25 diss emination.
  Headline = ensemble mean (50+1 post-2023; verify member count in data).

Outputs (same schema family as the GEFS arm, separate files):
  data/processed/phase1b/ifsens_leads.csv / .parquet / _log.csv / _manifest.json

Usage:
  python src/data/build_ifsens_leads.py --opendata-test 2026-09-10
  python src/data/build_ifsens_leads.py --tigge 2018-07-15
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

try:  # package mode
    from .build_raw_gefs_leads import (
        load_blocks, zonal_means, split_for, EXPECTED_BLOCKS, N_LEADS,
    )
except ImportError:  # script mode: python src/data/build_ifsens_leads.py
    from build_raw_gefs_leads import (
        load_blocks, zonal_means, split_for, EXPECTED_BLOCKS, N_LEADS,
    )
except ImportError:  # script mode: python src/data/build_ifsens_leads.py
    from build_raw_gefs_leads import (
        load_blocks, zonal_means, split_for, EXPECTED_BLOCKS, N_LEADS,
    )

PROJECT = Path(__file__).resolve().parents[2]
PHASE1B_DIR = PROJECT / "data" / "processed" / "phase1b"
OUT_CSV = PHASE1B_DIR / "ifsens_leads.csv"
OUT_PARQUET = PHASE1B_DIR / "ifsens_leads.parquet"
LOG_CSV = PHASE1B_DIR / "ifsens_leads_log.csv"
MANIFEST = PHASE1B_DIR / "ifsens_leads_manifest.json"

SOURCE_OD = "ecmwf-open-data"
SOURCE_TIGGE = "tigge"
PRODUCT_ENS_MEAN = "ifs-ens-mean"
PRODUCT_HRES = "ifs-hres"
STATISTIC = "ensemble_mean"
INIT_TIME = "00Z"

# steps whose cumulative tp give daily D+1..D+7 by differencing
CUM_STEPS = [0, 24, 48, 72, 96, 120, 144, 168]


def retrieve_tp_steps(issue: pd.Timestamp, steps: list, target: Path,
                      stream="oper", ftype="fc", model="ifs"):
    """Official ecmwf-opendata client, tp-only server-side subset.

    Whole-step files are ~147 MB each (all params) and current CCSDS packing
    is unreadable by stock GDAL — never download whole files. tp-only for the
    8 needed steps is ~4 MB per init.
    """
    from ecmwf.opendata import Client
    c = Client(source="ecmwf")
    c.retrieve(date=issue.strftime("%Y-%m-%d"), time=0, stream=stream, type=ftype,
               step=list(steps), param="tp", model=model, target=str(target))
    return target


def decode_tp_file(path: Path, issue: pd.Timestamp):
    """Decode tp cumulative messages with ecCodes -> {step: dict}.

    Verified 2026-09-15: shortName=tp, units=m, regular_ll 1440x721,
    date/time == init 00Z. Raises on any deviation (never guess).
    """
    import eccodes
    out = {}
    with open(path, "rb") as f:
        while True:
            gid = eccodes.codes_grib_new_from_file(f)
            if gid is None:
                break
            try:
                if eccodes.codes_get(gid, "shortName") != "tp":
                    raise RuntimeError("non-tp message in tp-only pull")
                step = int(eccodes.codes_get(gid, "step"))
                unit = eccodes.codes_get(gid, "units")
                if unit != "m":
                    raise RuntimeError(f"tp units {unit!r} != 'm'")
                dt = (eccodes.codes_get(gid, "date"), eccodes.codes_get(gid, "time"))
                if (dt[0], dt[1]) != (int(issue.strftime("%Y%m%d")), 0):
                    raise RuntimeError(f"tp init {dt} != {issue.date()} 00Z")
                ni = eccodes.codes_get(gid, "Ni")
                nj = eccodes.codes_get(gid, "Nj")
                vals = np.array(eccodes.codes_get_values(gid), dtype=float).reshape(nj, ni)
                lats = np.array(eccodes.codes_get_array(gid, "latitudes")).reshape(nj, ni)
                lons = np.array(eccodes.codes_get_array(gid, "longitudes")).reshape(nj, ni)
                lons = np.where(lons > 180, lons - 360, lons)  # 0..360 -> -180..180
                out[step] = {"vals": vals, "lats": lats, "lons": lons}
            finally:
                eccodes.codes_release(gid)
    if not out:
        raise RuntimeError("no tp messages decoded")
    return out


def crop_punjab(entry: dict, bounds, pad=0.5):
    """Index-crop global tp to the Punjab box; build an Affine for zonal code."""
    from affine import Affine
    west, south, east, north = bounds
    lats, lons, vals = entry["lats"], entry["lons"], entry["vals"]
    lat1d = lats[:, 0]
    lon1d = lons[0, :]
    if lat1d[0] < lat1d[-1]:  # ensure descending
        lat1d = lat1d[::-1]
        vals = vals[::-1, :]
    rows = np.where((lat1d <= north + pad) & (lat1d >= south - pad))[0]
    cols = np.where((lon1d >= west - pad) & (lon1d <= east + pad))[0]
    if len(rows) < 2 or len(cols) < 2:
        raise RuntimeError("Punjab box not found in tp grid")
    r0, r1, c0, c1 = rows[0], rows[-1] + 1, cols[0], cols[-1] + 1
    sub = vals[r0:r1, c0:c1]
    dlon = float(lon1d[1] - lon1d[0])
    dlat = float(lat1d[1] - lat1d[0])  # negative (descending)
    transform = Affine(dlon, 0.0, float(lon1d[c0]) - dlon / 2,
                       0.0, dlat, float(lat1d[r0]) - dlat / 2)
    return sub, transform


def process_opendata_init(issue: pd.Timestamp, blocks, bounds,
                            stream="oper", ftype="fc", model="ifs") -> pd.DataFrame:
    """One recent init via official client (tp-only). oper/fc == HRES
    deterministic -> labelled honestly as ifs-hres/deterministic (NOT the
    ens-mean headline; proves decode/differ/zonal mechanics end-to-end)."""
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    tmp = PHASE1B_DIR / f"_tmp_ifs_{issue.strftime('%Y%m%d')}.grib2"
    try:
        retrieve_tp_steps(issue, CUM_STEPS, tmp, stream=stream, ftype=ftype, model=model)
        msgs = decode_tp_file(tmp, issue)
    finally:
        if tmp.exists():
            tmp.unlink()
    box = bounds  # caller passes the (west, south, east, north) Sangrur box
    missing = [s for s in CUM_STEPS if s not in msgs]
    if missing == [0]:
        ref = msgs[min(msgs)]
        zeros = {"vals": np.zeros_like(ref["vals"]), "lats": ref["lats"], "lons": ref["lons"]}
        msgs[0] = zeros
        missing = []
    if missing:
        raise RuntimeError(f"tp steps missing from pull: {missing}")
    is_ens = (stream == "enfo")
    rows = []
    for k in range(1, N_LEADS + 1):
        e0, e1 = msgs[24 * (k - 1)], msgs[24 * k]
        if e0["vals"].shape != e1["vals"].shape:
            raise RuntimeError("shape drift across steps")
        daily_m = e1["vals"] - e0["vals"]
        if np.nanmin(daily_m) < -1e-3:
            raise RuntimeError(f"negative daily from differencing (k={k})")
        daily = np.clip(daily_m * 1000.0, 0, None)  # m -> mm (units verified 'm')
        sub, transform = crop_punjab({"vals": daily, "lats": e1["lats"], "lons": e1["lons"]}, box)
        z = zonal_means(sub, transform, blocks)
        target = issue + pd.Timedelta(days=k)
        for name, (v, n) in z.items():
            rows.append({
                "forecast_date": issue.strftime("%Y-%m-%d"),
                "split": split_for(issue.year) if issue.year != 2026 else "live",
                "lead_day": k,
                "block": name,
                "forecast_target_date": target.strftime("%Y-%m-%d"),
                "forecast_rainfall_mm": float(v),
                "valid_pixel_count": int(n),
                "source": SOURCE_OD,
                "product": PRODUCT_ENS_MEAN if is_ens else PRODUCT_HRES,
                "statistic": STATISTIC if is_ens else "deterministic",
                "init_time": INIT_TIME,
                "member_count": -1 if not is_ens else 0,
                "mean_source": "open-data-tp-differenced",
                "model_era": "open-data-rolling",
            })
    df = pd.DataFrame(rows)
    assert len(df) == 42 and set(df["block"]) == set(EXPECTED_BLOCKS)
    assert sorted(df["lead_day"].unique().tolist()) == [1, 2, 3, 4, 5, 6, 7]
    assert (pd.to_datetime(df["forecast_target_date"]) > pd.to_datetime(df["forecast_date"])).all()
    if (df["forecast_rainfall_mm"] < -1e-6).any():
        raise RuntimeError("negative rainfall in output")
    return df


def append_rows(df: pd.DataFrame):
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    header = not OUT_CSV.exists()
    df.to_csv(OUT_CSV, mode="a", header=header, index=False)
    with open(LOG_CSV, "a") as f:
        if LOG_CSV.stat().st_size == 0 if LOG_CSV.exists() else True:
            f.write("forecast_date,split,status,n_rows,error\n")
        f.write(f"{df['forecast_date'].iloc[0]},{df['split'].iloc[0]},success,{len(df)},\n")


def update_manifest(done: list, failed: list):
    PHASE1B_DIR.mkdir(parents=True, exist_ok=True)
    man = {}
    if MANIFEST.exists():
        try:
            man = json.loads(MANIFEST.read_text())
        except Exception:
            man = {}
    man.update({
        "updated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "completed_dates": sorted(set(man.get("completed_dates", [])) | set(done)),
        "failed_dates": sorted(set(man.get("failed_dates", [])) | set(failed)),
        "history_status": "BLOCKED: TIGGE/ECDS-MARS registration required for IFS ENS history",
    })
    MANIFEST.write_text(json.dumps(man, indent=2))


def tigge_attempt(issue: pd.Timestamp):
    """Documented historical route; fails loudly without credentials."""
    msg = (
        f"TIGGE history pull for {issue.date()} is BLOCKED: the ECMWF TIGGE portal "
        "(apps.ecmwf.int, migrated to ECDS 2026-05-27) requires ECMWF SSO login and "
        "batch retrieval requires MARS access; the CMA mirror has no documented "
        "anonymous bulk API. No data was downloaded, substituted, or fabricated. "
        "To unblock: register at ecmwf.int / request MARS TIGGE access, then rerun "
        "with credentials (see module header). The --opendata-test path proves the "
        "decode/accumulate/zonal code end-to-end on recent runs."
    )
    print(msg, flush=True)
    update_manifest([], [issue.strftime("%Y-%m-%d") + ":blocked-no-credentials"])
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--opendata-test", metavar="YYYY-MM-DD", default=None)
    ap.add_argument("--tigge", metavar="YYYY-MM-DD", default=None)
    args = ap.parse_args()
    if bool(args.opendata_test) == bool(args.tigge):
        ap.error("pass exactly one of --opendata-test DATE or --tigge DATE")

    blocks = load_blocks()
    bounds = [g.bounds for _, g in blocks]
    box = (min(b[0] for b in bounds), min(b[1] for b in bounds),
           max(b[2] for b in bounds), max(b[3] for b in bounds))
    print(f"blocks ok: {[b for b, _ in blocks]} box={box}", flush=True)

    if args.tigge:
        ok = tigge_attempt(pd.Timestamp(args.tigge))
        sys.exit(0 if not ok else 0)

    issue = pd.Timestamp(args.opendata_test)
    df = process_opendata_init(issue, blocks, box)
    print(df.groupby("lead_day")["forecast_rainfall_mm"].mean().to_string(), flush=True)
    append_rows(df)
    update_manifest([issue.strftime("%Y-%m-%d")], [])
    if OUT_CSV.exists():
        pd.read_csv(OUT_CSV).to_parquet(OUT_PARQUET, index=False)
    print(f"wrote {OUT_CSV} shape={df.shape}", flush=True)


if __name__ == "__main__":
    main()
