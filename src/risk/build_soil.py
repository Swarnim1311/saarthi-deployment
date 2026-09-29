"""Phase 4.0A — soil block aggregation (reproducible, provenance-explicit).

Reads the authoritative Bhuvan GeoPackage + 5 SoilGrids rasters, computes
per-block zonal means, writes data/processed/risk/block_soil_context.csv +
method_soil.json (paths, sha256 hashes, CRS, resolution, method, timestamp).

Units: clay/sand/silt g/kg (raw), SOC dg/kg -> g/kg (/10), pH*10 -> pH (/10),
matching the conversions documented in final_feature_metadata.csv.
Aggregation: rasterio.mask over block polygon, mean of unmasked pixels
(pixel-center inclusion, the rasterio default). Both layers are geographic
WGS84; geometry is matched to the raster CRS whenever they differ.
"""

import datetime
import hashlib
import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import rasterio
from rasterio.mask import mask as rio_mask

from src.risk.common import BLOCKS, GPKG, GPKG_LAYER, OUT, REPO, SOIL_DIR

SOIL_VARS = {
    "clay": ("Clay_0-5cm_mean.tif", 1.0, "g/kg"),
    "sand": ("Sand_0-5cm_mean.tif", 1.0, "g/kg"),
    "silt": ("Silt_0-5cm_mean.tif", 1.0, "g/kg"),
    "soc": ("OrganicCarbon_0-5cm_mean.tif", 0.1, "g/kg (raw dg/kg /10)"),
    "ph": ("pH_0-5cm_mean.tif.tif", 0.1, "pH units (raw pH*10 /10)"),
}
BLOCK_COL_CANDIDATES = ("b_name", "block", "block_name", "name")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    gdf = gpd.read_file(GPKG, layer=GPKG_LAYER)
    bcol = next(c for c in BLOCK_COL_CANDIDATES if c in gdf.columns)
    names = sorted(gdf[bcol].unique().tolist())
    assert names == sorted(BLOCKS), f"expected 6 legacy blocks, got {names}"

    rows = []
    rasters_meta = {}
    for var, (fname, scale, unit) in SOIL_VARS.items():
        fpath = SOIL_DIR / fname
        assert fpath.exists(), f"missing raster {fpath}"
        with rasterio.open(fpath) as src:
            if var == "clay":
                rasters_meta["crs"] = str(src.crs)
                rasters_meta["resolution_deg"] = [float(src.res[0]), float(src.res[1])]
                rasters_meta["dimensions"] = {"width": src.width, "height": src.height}
                rasters_meta["bounds"] = [float(v) for v in src.bounds]
                rasters_meta["dtype"] = str(src.profile["dtype"])
                rasters_meta["nodata"] = src.nodata
            rasters_meta[var] = {"file": str(fpath.relative_to(REPO)), "sha256": sha256(fpath),
                                 "scale": scale, "unit": unit}
            g = gdf if gdf.crs == src.crs else gdf.to_crs(src.crs)
            for _, row in g.iterrows():
                out, _ = rio_mask(src, [row.geometry], crop=True, filled=False)
                band = out[0]
                vals = band.compressed()  # unmasked pixels only
                assert vals.size > 0, f"no valid pixels for {row[bcol]}/{var}"
                rows.append({"block": row[bcol], "variable": var,
                             "raw_mean": round(float(vals.mean()), 3),
                             "mean": round(float(vals.mean()) * scale, 4),
                             "n_pixels": int(vals.size),
                             "raw_min": int(vals.min()), "raw_max": int(vals.max())})

    tidy = pd.DataFrame(rows)
    wide = tidy.pivot(index="block", columns="variable", values="mean").reindex(BLOCKS)
    wide.columns = [f"soil_{c}" for c in wide.columns]
    counts = tidy.pivot(index="block", columns="variable", values="n_pixels").reindex(BLOCKS)
    assert wide.shape == (6, 5) and wide.notna().all().all()
    assert (counts > 0).all().all()
    # sensible ranges
    assert ((wide["soil_clay"] > 0) & (wide["soil_clay"] < 1000)).all()
    assert ((wide["soil_sand"] > 0) & (wide["soil_sand"] < 1000)).all()
    assert ((wide["soil_silt"] > 0) & (wide["soil_silt"] < 1000)).all()
    assert ((wide["soil_soc"] > 0) & (wide["soil_soc"] < 200)).all()
    assert ((wide["soil_ph"] > 3) & (wide["soil_ph"] < 11)).all()

    out_csv = OUT / "block_soil_context.csv"
    OUT.mkdir(parents=True, exist_ok=True)
    wide.reset_index().to_csv(out_csv, index=False)
    method = {
        "kind": "soil-block-context",
        "boundary": {"file": str(GPKG.relative_to(REPO)), "layer": GPKG_LAYER,
                     "sha256": sha256(GPKG), "block_column": bcol, "blocks": BLOCKS},
        "rasters": rasters_meta,
        "aggregation": "rasterio.mask per-block polygon, mean of unmasked pixels "
                       "(pixel-center inclusion); geometry matched to raster CRS when different",
        "generated_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "code": "src/risk/build_soil.py",
    }
    (OUT / "method_soil.json").write_text(json.dumps(method, indent=2), encoding="utf-8")

    # comparison vs pre-existing values (provenance check, never silent substitution)
    print(wide.round(2).to_string())
    print("pixels/block:", counts.iloc[0].to_dict())
    try:
        old = pd.read_csv(REPO / "data" / "processed" / "final_ml_dataset.csv")
        oldm = old.groupby("block")[["soil_clay", "soil_sand", "soil_silt", "soil_soc", "soil_ph"]].mean()
        print("--- delta recomputed-minus-existing ---")
        print((wide[oldm.columns] - oldm).round(3).to_string())
    except Exception as e:  # noqa: BLE001 - comparison is diagnostic only
        print("comparison skipped:", e)


if __name__ == "__main__":
    main()
