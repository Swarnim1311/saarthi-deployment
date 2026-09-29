"""
src/data/download_sangrur_boundaries.py
Inspect Bhuvan/Bharat Atlas block boundaries parquet for Sangrur legacy 6-block verification.

Source: https://bharatlas.com/api/dl/admin/blocks/bhuvan_blocks.parquet
Goal: Determine whether Bhuvan source contains expected six legacy Sangrur blocks:
  Dhuri, Lehragaga/Lehra, Malerkotla, Moonak, Sangrur, Sunam
Do NOT modify existing synthetic shapefile yet — inspection only.
"""

from pathlib import Path
import sys

# URL and local paths
URL = "https://bharatlas.com/api/dl/admin/blocks/bhuvan_blocks.parquet"
PROJECT = Path(__file__).resolve().parents[2]
BOUNDARY_DIR = PROJECT / "data" / "raw" / "boundaries"
LOCAL_PARQUET = BOUNDARY_DIR / "bhuvan_blocks.parquet"

EXPECTED_BLOCKS = ["Dhuri", "Lehragaga", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"]

def download_parquet(url=URL, out_path=LOCAL_PARQUET, timeout=120):
    """Download parquet if not exists, else skip. Returns path."""
    import requests

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    if out_path.exists() and out_path.stat().st_size > 10_000:
        print(f"Already exists: {out_path.name} ({out_path.stat().st_size/1e6:.2f} MB) — skipping download")
        print(f"Path: {out_path.resolve()}")
        print("Delete file to force re-download")
        return out_path

    print(f"Downloading Bhuvan blocks parquet")
    print(f"URL: {url}")
    print(f"To: {out_path.resolve()}")

    # Use stream + atomic temp
    tmp = out_path.with_suffix(".parquet.tmp")
    with requests.get(url, stream=True, timeout=timeout) as r:
        r.raise_for_status()
        total = int(r.headers.get("content-length", 0))
        print(f"Total: {total/1e6:.2f} MB" if total else "Total: unknown")
        with open(tmp, "wb") as f:
            for chunk in r.iter_content(chunk_size=1024*1024):
                if chunk:
                    f.write(chunk)
    # Atomic rename with retry for Windows file lock
    import time, os
    for attempt in range(3):
        try:
            # Use replace for atomic overwrite
            tmp.replace(out_path)
            break
        except PermissionError as e:
            if attempt < 2:
                time.sleep(0.5)
                continue
            # Fallback: copy then delete
            import shutil
            shutil.copyfile(tmp, out_path)
            try:
                tmp.unlink()
            except:
                pass
            break
    print(f"Saved: {out_path.resolve()} ({out_path.stat().st_size/1e6:.2f} MB)")
    return out_path


def inspect_parquet(parquet_path=LOCAL_PARQUET):
    """Read parquet with GeoPandas/Pandas and inspect columns before filtering."""
    import geopandas as gpd
    import pandas as pd

    parquet_path = Path(parquet_path)
    print(f"\n=== Reading {parquet_path.name} ===")
    print(f"Path: {parquet_path.resolve()}")
    print(f"Size: {parquet_path.stat().st_size/1e6:.2f} MB")

    # Try GeoPandas first (handles geometry), fallback to pandas
    try:
        gdf = gpd.read_parquet(parquet_path)
        print("Read via geopandas.read_parquet")
    except Exception as e:
        print(f"geopandas.read_parquet failed: {e} — trying pandas")
        df = pd.read_parquet(parquet_path)
        # Try to convert to GeoDataFrame if geometry column exists
        geom_col = None
        for cand in ["geometry", "geom", "wkt", "the_geom"]:
            if cand in df.columns:
                geom_col = cand
                break
        if geom_col:
            print(f"Found geometry column: {geom_col}")
            try:
                gdf = gpd.GeoDataFrame(df, geometry=geom_col, crs="EPSG:4326")
            except Exception as e2:
                print(f"GeoDataFrame conversion failed: {e2}")
                gdf = df
        else:
            gdf = df

    print(f"\nShape: {gdf.shape} (rows, cols)")
    print(f"Columns ({len(gdf.columns)}): {gdf.columns.tolist()}")
    print(f"CRS: {getattr(gdf, 'crs', 'N/A (not a GeoDataFrame)')}")

    # Show dtypes and sample
    print(f"\nDtypes:")
    try:
        print(gdf.dtypes.to_string())
    except:
        print(gdf.dtypes)

    print(f"\nFirst 5 rows:")
    try:
        # Use to_string for pandas, head for gdf
        print(gdf.head(5).to_string())
    except Exception as e:
        print(f"head failed: {e}")
        print(gdf.head())

    # Inspect all column names for filtering candidates
    print(f"\n=== Column inspection (do NOT assume names) ===")
    for col in gdf.columns:
        # Show unique sample for potential state/district/block columns
        try:
            uniq = gdf[col].dropna().astype(str).unique()[:10]
            print(f"  {col}: {gdf[col].dtype}, unique sample {uniq.tolist()[:5]} (n_unique={gdf[col].nunique()})")
        except Exception as e:
            print(f"  {col}: error {e}")

    return gdf


def filter_sangrur(gdf):
    """Filter to Punjab then Sangrur, without assuming column names — inspect first."""
    print(f"\n=== Filtering to Punjab then Sangrur ===")

    # Identify state/district/block columns by inspecting column names
    cols_lower = {c.lower(): c for c in gdf.columns}
    print(f"Lowercase columns: {list(cols_lower.keys())}")

    # Heuristic: find state column (handle s_name, d_name, b_name from BharAtlas)
    state_col = None
    for cand in ["s_name", "state", "state_name", "st_name", "state_nm", "state_ut", "state_code"]:
        if cand.lower() in cols_lower:
            state_col = cols_lower[cand.lower()]
            break
    if not state_col:
        # Fallback: search any column containing 'state' or s_name pattern
        for k, v in cols_lower.items():
            if "state" in k or k == "s_name":
                state_col = v
                break
    print(f"State column identified: {state_col}")

    district_col = None
    for cand in ["d_name", "district", "district_name", "dt_name", "dist_name", "district_nm"]:
        if cand.lower() in cols_lower:
            district_col = cols_lower[cand.lower()]
            break
    if not district_col:
        for k, v in cols_lower.items():
            if "district" in k or k in ["dt_name", "dist_name", "d_name"]:
                district_col = v
                break
    print(f"District column identified: {district_col}")

    block_col = None
    for cand in ["b_name", "block", "block_name", "blk_name", "block_nm", "tehsil", "subdistrict"]:
        if cand.lower() in cols_lower:
            block_col = cols_lower[cand.lower()]
            break
    if not block_col:
        for k, v in cols_lower.items():
            if "block" in k or k == "b_name":
                block_col = v
                break
    print(f"Block column identified: {block_col}")

    # Filter to Punjab
    if state_col:
        # Try exact and case-insensitive
        punjab_mask = gdf[state_col].astype(str).str.lower().str.contains("punjab", na=False)
        # Also try state code if numeric?
        print(f"\nUnique values in {state_col} (sample 20):")
        try:
            print(gdf[state_col].value_counts().head(20).to_string())
        except:
            print(gdf[state_col].unique()[:20])
        print(f"Punjab matching rows: {punjab_mask.sum()} / {len(gdf)}")
        gdf_punjab = gdf[punjab_mask].copy()
        print(f"Punjab shape: {gdf_punjab.shape}")
    else:
        print("No state column found — cannot filter to Punjab, using full dataset")
        gdf_punjab = gdf.copy()

    # Filter to Sangrur district
    if district_col and not gdf_punjab.empty:
        print(f"\nUnique values in {district_col} within Punjab (sample 20):")
        try:
            print(gdf_punjab[district_col].value_counts().head(20).to_string())
        except:
            print(gdf_punjab[district_col].unique()[:20])
        # Try sangrur variations
        sangrur_mask = gdf_punjab[district_col].astype(str).str.lower().str.contains("sangrur", na=False)
        print(f"Sangrur matching rows: {sangrur_mask.sum()} / {len(gdf_punjab)}")
        gdf_sangrur = gdf_punjab[sangrur_mask].copy()
        print(f"Sangrur shape: {gdf_sangrur.shape}")
    else:
        print("No district column — cannot filter to Sangrur")
        gdf_sangrur = gdf_punjab.copy()

    # Report Sangrur block details
    if not gdf_sangrur.empty and block_col and block_col in gdf_sangrur.columns:
        print(f"\n=== Sangrur block names ===")
        # Show value counts
        try:
            print(gdf_sangrur[block_col].value_counts().to_string())
        except:
            print(gdf_sangrur[block_col].unique())
        block_names = sorted(gdf_sangrur[block_col].astype(str).str.strip().unique().tolist())
        print(f"Unique block names ({len(block_names)}): {block_names}")
        print(f"Number of matching features: {len(gdf_sangrur)}")
        print(f"CRS: {getattr(gdf_sangrur, 'crs', 'N/A')}")
        if hasattr(gdf_sangrur, 'total_bounds'):
            print(f"Bounds: {gdf_sangrur.total_bounds.tolist()}")
        # Geometry types
        if hasattr(gdf_sangrur, 'geometry'):
            print(f"Geometry types: {gdf_sangrur.geometry.type.value_counts().to_string()}")
            print(f"Valid: {gdf_sangrur.geometry.is_valid.all()}")
    elif not gdf_sangrur.empty:
        print(f"\nSangrur rows {len(gdf_sangrur)} but no block column {block_col}")
        print(gdf_sangrur.head().to_string())
    else:
        print("\nNo Sangrur matching features found — check district column values")

    return gdf, gdf_punjab if 'gdf_punjab' in locals() else gdf, gdf_sangrur


def main():
    print("=== Bhuvan Sangrur Boundaries Inspection ===")
    print(f"URL: {URL}")
    print(f"Expected 6 legacy blocks: Dhuri, Lehragaga/Lehra, Malerkotla, Moonak, Sangrur, Sunam")

    # 1. Download
    parquet_path = download_parquet()

    # 2. Inspect columns before filtering
    gdf = inspect_parquet(parquet_path)

    # 3. Filter to Punjab then Sangrur
    gdf_all, gdf_punjab, gdf_sangrur = filter_sangrur(gdf)

    # 4. Report whether expected six legacy blocks are present
    print(f"\n=== Verdict ===")
    print(f"Total rows in parquet: {len(gdf_all):,}")
    try:
        print(f"Punjab rows: {len(gdf_punjab):,}")
        print(f"Sangrur rows: {len(gdf_sangrur):,}")
    except:
        pass

    if not gdf_sangrur.empty:
        # Identify block column again for final check
        block_col = None
        cols_lower = {c.lower(): c for c in gdf_sangrur.columns}
        for cand in ["b_name", "block", "block_name", "blk_name"]:
            if cand in cols_lower:
                block_col = cols_lower[cand]
                break
        if block_col:
            found = set(gdf_sangrur[block_col].astype(str).str.strip().str.lower().unique())
            expected_lower = {b.lower() for b in ["dhuri", "lehragaga", "lehra", "malerkotla", "moonak", "sangrur", "sunam"]}
            # Check each expected
            print(f"Expected blocks (lower): {expected_lower}")
            print(f"Found blocks (lower): {found}")
            for exp in ["dhuri", "lehra", "lehragaga", "malerkotla", "moonak", "sangrur", "sunam"]:
                present = exp in found
                print(f"  {exp:12s}: {'FOUND' if present else 'MISSING'}")
            # Special: Lehra vs Lehragaga
            if "lehra" in found or "lehragaga" in found:
                print("  Note: Lehra/Lehragaga may be same block with alternate spelling")
            # Overall
            # Consider success if at least 6 of expected are present (allowing Lehra/Lehragaga as one)
            found_expected = sum(1 for b in ["dhuri", "malerkotla", "moonak", "sangrur", "sunam"] if b in found) + (1 if ("lehra" in found or "lehragaga" in found) else 0)
            print(f"\nFound {found_expected}/6 legacy blocks (counting Lehra/Lehragaga as one)")
            if len(gdf_sangrur) == 6 and found_expected == 6:
                print("PASS: Bhuvan source contains expected six legacy Sangrur blocks")
            elif len(gdf_sangrur) == 6:
                print("PARTIAL: 6 features but names differ — check spelling/variants")
            elif len(gdf_sangrur) > 6:
                print(f"NOTE: Found {len(gdf_sangrur)} features — may be newer 8-block dataset or includes sub-blocks")
                print("  Legacy 6-block expected per PROJECT_CONTEXT — do NOT use 8-block without instruction")
            else:
                print(f"FAIL: Expected 6, found {len(gdf_sangrur)}")
            print(f"\nBlock names in Bhuvan Sangrur:")
            print(gdf_sangrur[block_col].value_counts().to_string())
            print(f"CRS: {gdf_sangrur.crs}")
        else:
            print("No block column identified — cannot verify expected names")
    else:
        print("No Sangrur features — cannot verify 6 legacy blocks")

    print("\n=== Inspection complete — synthetic shapefile NOT modified ===")
    print("Next: Compare with synthetic sangrur_blocks.shp (6 rectangular grid) and decide whether to replace")
    return gdf_sangrur


if __name__ == "__main__":
    main()
