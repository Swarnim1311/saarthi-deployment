"""
Rebuild corrected ML dataset from verified v2 GEFS leads (Notebook 02/03 logic, corrected mapping).

Inputs (read-only):
  data/processed/historical/historical_gefs_leads_v2.csv   (1098 D x 6 blocks x 7 leads, verified mapping)
  data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv
  data/raw/climate/ersst5.nino.mth.91-20.ascii
  data/raw/soil/*.tif, data/raw/boundaries/sangrur_blocks_bhuvan.gpkg

Outputs (*_v2; v1 originals untouched):
  data/processed/historical_forecast_targets_v2.csv/.parquet
  data/processed/forecast_features_base_v2.csv/.parquet
  data/processed/forecast_features_rainfall_v2.csv/.parquet
  data/processed/final_ml_dataset_v2.csv/.parquet
  data/processed/final_feature_metadata_v2.csv
  data/processed/pipeline_validation_report_v2.txt

Conventions (verified against v1 + 03b recompute rule):
  rain_1d/3d/7d/14d/30d = windows ending at D (<=D); NaN if any window day missing (never zero)
  rain_lag_k = CHIRPS at D-k (lag_1 = D-1, ..., lag_7 = D-7)
  target_7d_rainfall_mm = sum CHIRPS D+1..D+7 (label only)
  ENSO as-of = previous calendar month value (conservative, published before D)
"""
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import rasterio
from rasterio.crs import CRS
from rasterio.mask import mask as rio_mask

PROJECT = Path(__file__).resolve().parents[2]
PROC = PROJECT / "data" / "processed"
HIST = PROC / "historical"
LEADS_CSV = HIST / "historical_gefs_leads_v2.csv"
CHIRPS_CSV = PROJECT / "data" / "raw" / "rainfall" / "Sangrur_Block_Daily_Rainfall_2010_2025.csv"
ENSO_FILE = PROJECT / "data" / "raw" / "climate" / "ersst5.nino.mth.91-20.ascii"
SOIL_DIR = PROJECT / "data" / "raw" / "soil"
GPKG = PROJECT / "data" / "raw" / "boundaries" / "sangrur_blocks_bhuvan.gpkg"

BLOCKS = ["Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]
RAIN_WINDOWS = {"rain_1d": 1, "rain_3d": 3, "rain_7d": 7, "rain_14d": 14, "rain_30d": 30}


def main():
    # ---- 1. leads v2: validate then pivot wide ----
    long = pd.read_csv(LEADS_CSV)
    long["forecast_date"] = pd.to_datetime(long["forecast_date"])
    long["forecast_target_date"] = pd.to_datetime(long["forecast_target_date"])
    assert len(long) == 1098 * 42, len(long)
    assert (long.groupby("forecast_date").size() == 42).all()
    assert sorted(long["block"].unique()) == BLOCKS
    assert sorted(long["lead_day"].unique()) == [1, 2, 3, 4, 5, 6, 7]
    assert (long["forecast_rainfall_mm"] >= 0).all() and long["forecast_rainfall_mm"].notna().all()
    # no lead-0: every target date strictly after its issue date
    assert (long["forecast_target_date"] > long["forecast_date"]).all()
    # target-date arithmetic exact: lead k == D+k
    for k in range(1, 8):
        sub = long[long["lead_day"] == k]
        assert ((sub["forecast_target_date"] - sub["forecast_date"]).dt.days == k).all(), k

    wide = long.pivot_table(index=["forecast_date", "split", "block"],
                            columns="lead_day", values="forecast_rainfall_mm")
    wide.columns = [f"gefs_d{i}" for i in wide.columns]
    base = wide.reset_index().rename(columns={"split": "_split"})
    assert base[[f"gefs_d{i}" for i in range(1, 8)]].notna().all().all()
    base["gefs_3d_total"] = base[["gefs_d1", "gefs_d2", "gefs_d3"]].sum(axis=1)
    base["gefs_7d_total"] = base[[f"gefs_d{i}" for i in range(1, 8)]].sum(axis=1)
    print(f"base wide: {base.shape} (expect {(6588, 12)})", flush=True)
    assert base.shape == (6588, 12)

    # ---- 2. CHIRPS + D+1..D+7 target ----
    ch = pd.read_csv(CHIRPS_CSV)
    ch["date"] = pd.to_datetime(ch["date"])
    ch["block"] = ch["block"].astype(str).str.strip()
    ch["rainfall_mm"] = pd.to_numeric(ch["rainfall_mm"], errors="coerce")
    assert set(ch["block"].unique()) == set(BLOCKS) and ch["rainfall_mm"].notna().all()
    ch_idx = ch.set_index(["block", "date"])["rainfall_mm"]

    def target_7d(B, D):
        days = [D + pd.Timedelta(days=i) for i in range(1, 8)]
        try:
            return float(sum(ch_idx[(B, d)] for d in days))
        except KeyError:
            return np.nan

    base["target_7d_rainfall_mm"] = [target_7d(b, d) for d, b in
                                     zip(base["forecast_date"], base["block"])]
    assert base["target_7d_rainfall_mm"].notna().all(), "target missing — CHIRPS gap?"
    assert (base["target_7d_rainfall_mm"] >= 0).all()
    base[["forecast_date", "block", "gefs_d1", "gefs_d2", "gefs_d3", "gefs_d4",
          "gefs_d5", "gefs_d6", "gefs_d7", "target_7d_rainfall_mm"]].to_csv(
        PROC / "forecast_features_base_v2.csv", index=False)
    base[["forecast_date", "block", "gefs_d1", "gefs_d2", "gefs_d3", "gefs_d4",
          "gefs_d5", "gefs_d6", "gefs_d7", "target_7d_rainfall_mm"]].to_parquet(
        PROC / "forecast_features_base_v2.parquet", index=False)
    long.assign(target_7d_rainfall_mm=long.merge(
        base[["forecast_date", "block", "target_7d_rainfall_mm"]],
        on=["forecast_date", "block"])["target_7d_rainfall_mm"]).to_csv(
        PROC / "historical_forecast_targets_v2.csv", index=False)

    # ---- 3. recent rainfall features (<= D only, NaN if incomplete) ----
    def window_sum(B, D, ndays):
        days = [D - pd.Timedelta(days=i) for i in range(ndays)]
        try:
            return float(sum(ch_idx[(B, d)] for d in days))
        except KeyError:
            return np.nan

    def lag(B, D, k):
        try:
            return float(ch_idx[(B, D - pd.Timedelta(days=k))])
        except KeyError:
            return np.nan

    feat = base[["forecast_date", "block"]].copy()
    for col, nd in RAIN_WINDOWS.items():
        feat[col] = [window_sum(b, d, nd) for d, b in
                     zip(feat["forecast_date"], feat["block"])]
    for k in range(1, 8):
        feat[f"rain_lag_{k}"] = [lag(b, d, k) for d, b in
                                 zip(feat["forecast_date"], feat["block"])]
    assert feat["rain_30d"].notna().all(), "JJAS>=2016 always has 30d history"
    rain_cols = list(RAIN_WINDOWS) + [f"rain_lag_{k}" for k in range(1, 8)]
    rain_enriched = base.merge(feat, on=["forecast_date", "block"])
    keep_rain = (["forecast_date", "block"] + [f"gefs_d{i}" for i in range(1, 8)]
                 + ["target_7d_rainfall_mm"] + rain_cols)
    rain_enriched[keep_rain].to_csv(PROC / "forecast_features_rainfall_v2.csv", index=False)
    rain_enriched[keep_rain].to_parquet(PROC / "forecast_features_rainfall_v2.parquet", index=False)
    print(f"rainfall-enriched: {rain_enriched.shape}, rain NaN total: "
          f"{int(rain_enriched[rain_cols].isna().sum().sum())} (expect 0)", flush=True)

    ml = rain_enriched.copy()

    # ---- 4. ENSO as-of previous month ----
    cols = ["YR", "MON", "NINO12", "NINO12_ANOM", "NINO3", "NINO3_ANOM",
            "NINO4", "NINO4_ANOM", "NINO34", "NINO34_ANOM"]
    raw = pd.read_csv(ENSO_FILE, sep=r"\s+", names=cols, skiprows=1)
    enso = pd.DataFrame({
        "enso_date": pd.to_datetime(dict(year=raw["YR"].astype(int),
                                         month=raw["MON"].astype(int), day=1)),
        "enso_value": pd.to_numeric(raw["NINO34_ANOM"], errors="coerce")})
    enso.loc[enso["enso_value"] == -99.99, "enso_value"] = np.nan
    assert not enso.duplicated("enso_date").any() and enso["enso_value"].notna().all()
    lut = {(int(r["enso_date"].year), int(r["enso_date"].month)): r["enso_value"]
           for _, r in enso.iterrows()}
    asof = (ml["forecast_date"].dt.to_period("M").dt.to_timestamp() - pd.offsets.MonthBegin(1))
    ml["enso_value"] = [lut.get((d.year, d.month), np.nan) for d in asof]
    assert ml["enso_value"].notna().all(), "ENSO as-of coverage gap"
    # global-index constancy: one value per date across blocks
    assert (ml.groupby("forecast_date")["enso_value"].nunique() == 1).all()

    # ---- 5. calendar ----
    doy = ml["forecast_date"].dt.dayofyear.astype("int64")
    diy = np.where(ml["forecast_date"].dt.is_leap_year, 366, 365)
    ang = 2 * np.pi * (doy - 1) / diy
    ml["sin_day_of_year"] = np.sin(ang)
    ml["cos_day_of_year"] = np.cos(ang)

    # ---- 6. soil (same rasters/code path as NB03; cross-checked vs v1) ----
    def same_crs(c1, c2):
        try:
            A, B = CRS.from_user_input(c1), CRS.from_user_input(c2)
            if A.to_epsg() is not None and A.to_epsg() == B.to_epsg():
                return True
            return A.to_proj4() == B.to_proj4()
        except Exception:
            return str(c1) == str(c2)

    kw = {"clay": ["clay"], "sand": ["sand"], "silt": ["silt"],
          "soc": ["organic", "soc", "carbon"], "ph": ["ph"]}
    tifs = sorted(SOIL_DIR.glob("*.tif"))
    var_files = {v: [str(t) for t in tifs if any(k in t.name.lower() for k in ks)]
                 for v, ks in kw.items()}
    assert all(len(h) == 1 for h in var_files.values()), var_files
    gdf = gpd.read_file(GPKG, layer="sangrur_blocks")
    bcol = next(c for c in gdf.columns
                if c != "geometry" and set(gdf[c].astype(str).str.strip()) == set(BLOCKS))
    soil = pd.DataFrame({"block": BLOCKS})
    for var, (path,) in var_files.items():
        with rasterio.open(path) as src:
            g = gdf if same_crs(src.crs, gdf.crs) else gdf.to_crs(src.crs)
            vals = {}
            for _, r in g.iterrows():
                arr, _ = rio_mask(src, [r.geometry.__geo_interface__], crop=True, filled=False)
                v = arr[0].compressed().astype("float64")
                if src.nodata is not None:
                    v = v[v != src.nodata]
                vals[str(r[bcol]).strip()] = float(np.nanmean(v)) if v.size else np.nan
        s = pd.Series(vals)
        if var in ("clay", "sand", "silt"):
            assert ((s >= 0) & (s <= 1000)).all(), var
            soil[f"soil_{var}"] = soil["block"].map(s)
        elif var == "soc":
            soil["soil_soc"] = soil["block"].map(s / 10.0)   # dg/kg -> g/kg
        elif var == "ph":
            assert s.max() > 14, "pH scaling assumption broken"
            soil["soil_ph"] = soil["block"].map(s / 10.0)    # pH x10 -> pH
    assert ((soil["soil_clay"] + soil["soil_sand"] + soil["soil_silt"]).between(900, 1050)).all()
    assert soil["soil_ph"].between(0, 14).all()
    # determinism proof: identical rasters+method must reproduce v1 block means
    v1 = pd.read_csv(PROC / "final_ml_dataset.csv")
    chk = soil.merge(v1[["block", "soil_clay", "soil_sand", "soil_silt", "soil_soc", "soil_ph"]]
                     .drop_duplicates(), on="block", suffixes=("", "_v1"))
    for c in ["soil_clay", "soil_sand", "soil_silt", "soil_soc", "soil_ph"]:
        assert np.allclose(chk[c], chk[f"{c}_v1"], atol=1e-6), c
    print("soil block means identical to v1 (deterministic re-extraction) PASS", flush=True)
    SOIL_FEATURES = ["soil_clay", "soil_sand", "soil_silt", "soil_soc", "soil_ph"]
    ml = ml.merge(soil[["block"] + SOIL_FEATURES], on="block", how="left", validate="many_to_one")

    # ---- 7. spatial centroids (UTM-43N, cross-checked vs v1) ----
    g_utm = gdf[[bcol, "geometry"]].to_crs(epsg=32643)
    cent = gpd.GeoSeries(g_utm.geometry.centroid, crs=32643).to_crs(epsg=4326)
    spat = pd.DataFrame({"block": gdf[bcol].astype(str).str.strip().tolist(),
                         "longitude": [float(p.x) for p in cent],
                         "latitude": [float(p.y) for p in cent]})
    chk2 = spat.merge(v1[["block", "latitude", "longitude"]].drop_duplicates(),
                      on="block", suffixes=("", "_v1"))
    assert np.allclose(chk2["latitude"], chk2["latitude_v1"], atol=1e-9)
    assert np.allclose(chk2["longitude"], chk2["longitude_v1"], atol=1e-9)
    print("centroids identical to v1 PASS", flush=True)
    ml = ml.merge(spat, on="block", how="left", validate="many_to_one")

    # ---- 8. final frame + metadata ----
    GEFS = [f"gefs_d{i}" for i in range(1, 8)]
    DERIVED = ["gefs_3d_total", "gefs_7d_total"]
    RAIN = list(RAIN_WINDOWS) + [f"rain_lag_{k}" for k in range(1, 8)]
    FINAL = GEFS + DERIVED + RAIN + ["enso_value", "sin_day_of_year", "cos_day_of_year"] \
        + SOIL_FEATURES + ["latitude", "longitude"]
    TARGET = "target_7d_rainfall_mm"
    assert TARGET not in FINAL and all(c in ml.columns for c in FINAL)
    final = ml[["forecast_date", "block"] + FINAL + [TARGET]].copy()
    final = final.sort_values(["forecast_date", "block"]).reset_index(drop=True)
    assert not final.duplicated(subset=["forecast_date", "block"]).any()
    assert final[TARGET].notna().all() and (final[TARGET] >= 0).all()
    assert final[FINAL].isna().sum().sum() == 0, "unexpected NaN in features"
    final.to_parquet(PROC / "final_ml_dataset_v2.parquet", index=False)
    final.to_csv(PROC / "final_ml_dataset_v2.csv", index=False)

    META = [("forecast_date", "identifier", "Forecast issue date", "Derived", "date", "date"),
            ("block", "identifier", "Sangrur block (Bhuvan legacy name)",
             "Bhuvan GPKG sangrur_blocks", "static", "name")]
    for c in GEFS:
        META.append((c, "GEFS", f"CHIRPS-GEFS daily forecast lead {c.split('_d')[1]} "
                                "(target file D+k from issue folder D, verified 2026-09-10)",
                     "CHIRPS-GEFS", "known at D", "mm"))
    for c in DERIVED:
        META.append((c, "GEFS", "Sum of GEFS daily leads (all components required)",
                     "Derived from CHIRPS-GEFS", "known at D", "mm"))
    for c in RAIN:
        META.append((c, "Recent rainfall", "Observed CHIRPS rainfall ending at D (window/lag)",
                     "CHIRPS", "known at D", "mm"))
    META.append(("enso_value", "ENSO", "Nino3.4 SSTA, previous calendar month as-of D "
                                      "(NOAA CPC ERSSTv5 91-20)",
                 "ersst5.nino.mth.91-20.ascii", "as-of D", "degC anomaly"))
    for c in ["sin_day_of_year", "cos_day_of_year"]:
        META.append((c, "Seasonal", "Cyclic day-of-year encoding", "Derived from forecast_date",
                     "known at D", "unitless"))
    units = {"soil_clay": "g/kg (raw SoilGrids v2, range-verified 0-1000)",
             "soil_sand": "g/kg (raw SoilGrids v2, range-verified 0-1000)",
             "soil_silt": "g/kg (raw SoilGrids v2, range-verified 0-1000)",
             "soil_soc": "g/kg (SoilGrids v2 dg/kg /10)",
             "soil_ph": "unitless (SoilGrids v2 pH x10 /10, verified raw max > 14)"}
    for c in SOIL_FEATURES:
        META.append((c, "Soil", f"Block-mean SoilGrids 0-5cm {c.split('soil_')[1]} (static)",
                     "data/raw/soil/*.tif", "static", units[c]))
    for c in ["latitude", "longitude"]:
        META.append((c, "Spatial", f"Block centroid {c} (UTM-43N centroid, WGS84)",
                     "sangrur_blocks_bhuvan.gpkg", "static", "degrees"))
    META.append((TARGET, "Target", "Actual CHIRPS rainfall D+1 through D+7 (label only, never a feature)",
                 "CHIRPS", "future label", "mm"))
    pd.DataFrame(META, columns=["feature", "feature_group", "description", "source",
                                "availability_timing", "units"]).to_csv(
        PROC / "final_feature_metadata_v2.csv", index=False)

    # ---- 9. 03b-equivalent validation gate ----
    rep = []
    rep.append(f"rows: {len(final)} | dates: {final['forecast_date'].nunique()} | "
               f"blocks: 6 | span: {final['forecast_date'].min().date()}..{final['forecast_date'].max().date()}")
    for s in ["train", "val", "test"]:
        bd = base[base["_split"] == s]
        rep.append(f"split {s}: {len(bd)} rows ({bd['forecast_date'].nunique()} dates x6)")
    rep.append(f"GEFS leads complete: {(final[GEFS].notna().all().all())} "
               f"(missing cells: {int(final[GEFS].isna().sum().sum())})")
    rep.append(f"target complete: {final[TARGET].notna().mean()*100:.1f}%, min {final[TARGET].min():.2f}, "
               f"median {final[TARGET].median():.2f}, mean {final[TARGET].mean():.2f}, max {final[TARGET].max():.2f}")
    # target spot-check vs raw CHIRPS (4 dates, tol 1e-6)
    dates = sorted(final["forecast_date"].unique())
    ok = True
    for D, tag in [(dates[0], "earliest"), (dates[len(dates)//2], "middle"),
                   (dates[-2], "late"), (dates[-1], "latest")]:
        D = pd.Timestamp(D)
        blk = sorted(final[final["forecast_date"] == D]["block"].unique())[0]
        stored = float(final[(final["forecast_date"] == D) & (final["block"] == blk)][TARGET].iloc[0])
        win = ch[(ch["block"] == blk) & (ch["date"] > D) & (ch["date"] <= D + pd.Timedelta(days=7))]
        assert len(win) == 7, (tag, len(win))
        indep = float(win["rainfall_mm"].sum())
        m = abs(stored - indep) < 1e-6
        ok = ok and m
        rep.append(f"target {tag} {D.date()} {blk}: stored {stored:.3f} vs indep {indep:.3f} -> {'MATCH' if m else 'MISMATCH'}")
    assert ok
    # leakage: rain recompute (<=D rule) on 2 rows
    r1 = final[final["rain_3d"].notna()].iloc[0]
    for col, nd in (("rain_1d", 1), ("rain_3d", 3)):
        D, B = pd.Timestamp(r1["forecast_date"]), r1["block"]
        days = [D - pd.Timedelta(days=i) for i in range(nd)]
        exp = float(ch[(ch["block"] == B) & (ch["date"].isin(days))]["rainfall_mm"].sum())
        assert abs(r1[col] - exp) < 1e-6, (col, r1[col], exp)
    rep.append("leakage recompute rain_1d/rain_3d (<=D): MATCH")
    rep.append(f"ENSO constant per date: {(final.groupby('forecast_date')['enso_value'].nunique()==1).all()}")
    rep.append(f"soil static per block: {all((final.groupby('block')[c].nunique()==1).all() for c in SOIL_FEATURES)}")
    rep.append(f"chronological: {final['forecast_date'].is_monotonic_increasing}")
    rep.append("PIPELINE STATUS: READY FOR NOTEBOOK 4" if ok else "PIPELINE STATUS: BLOCKED")
    (PROC / "pipeline_validation_report_v2.txt").write_text(
        "PIPELINE VALIDATION REPORT v2 (corrected GEFS mapping) | " + " | ".join(rep))
    print("\n".join(rep), flush=True)


if __name__ == "__main__":
    main()
