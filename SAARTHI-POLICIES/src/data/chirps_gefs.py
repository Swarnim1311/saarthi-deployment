"""
src/data/chirps_gefs.py
Reusable helpers for CHIRPS-GEFS extraction — used in 01_chirps_gefs_acquisition.ipynb
Cells 22+ and later in 02_build_forecasting_dataset.ipynb (thousands of dates).

Keeps notebook readable and ensures single source of truth for zonal logic.
"""
from pathlib import Path
from datetime import date
import numpy as np
import rasterio
from rasterio.mask import mask as rio_mask


def zonal_stats(geom, src_path):
    """
    Calculate mean rainfall and valid pixel count for a block geometry.

    - Uses spatial masking (not centroid sampling)
    - Masks sentinel -9999 and NaN correctly (does not convert NaN to 0)
    - Returns (mean_mm, valid_pixel_count) — mean is NaN if no valid pixels

    Reused from Cell 22 of 01_chirps_gefs_acquisition.ipynb.
    """
    with rasterio.open(src_path) as src:
        out, _ = rio_mask(src, [geom], crop=True, nodata=np.nan, filled=True)
        arr = out[0].astype(float)
        arr[arr == -9999] = np.nan
        valid = arr[np.isfinite(arr)]
        if valid.size == 0:
            return float("nan"), 0
        return float(np.nanmean(valid)), int(valid.size)


def extract_block_rainfall(sangrur_gdf, block_col, tif_path):
    """
    Extract mean rainfall per block for a single daily GeoTIFF.

    Args:
        sangrur_gdf: GeoDataFrame with 6 blocks (already in raster CRS, e.g., sangrur_final)
        block_col: str column name for block identifier (e.g., BLOCK_COL)
        tif_path: Path to CHIRPS-GEFS daily GeoTIFF (e.g., output_file)

    Returns:
        DataFrame with columns [block, rainfall_mm, valid_pixel_count] sorted by block.
    """
    import pandas as pd

    rows = []
    for _, row in sangrur_gdf.iterrows():
        block_name = str(row[block_col]).strip()
        mean_val, n_valid = zonal_stats(row.geometry, tif_path)
        rows.append({"block": block_name, "rainfall_mm": mean_val, "valid_pixel_count": n_valid})

    import pandas as pd
    df = pd.DataFrame(rows).sort_values("block").reset_index(drop=True)
    return df


def build_tidy_forecast(block_rainfall_df, forecast_date):
    """
    Build tidy long-format forecast table for single daily product.

    Args:
        block_rainfall_df: DataFrame from extract_block_rainfall
        forecast_date: date object (single source of truth)

    Returns:
        DataFrame with columns [forecast_date, block, lead_day, rainfall_mm]
    """
    import pandas as pd

    forecast_date_iso = forecast_date.strftime("%Y-%m-%d")
    rows = []
    for _, r in block_rainfall_df.iterrows():
        rows.append({
            "forecast_date": forecast_date_iso,
            "block": r["block"],
            "lead_day": 1,
            "rainfall_mm": r["rainfall_mm"]
        })
    return pd.DataFrame(rows).sort_values(["block", "lead_day"]).reset_index(drop=True)
