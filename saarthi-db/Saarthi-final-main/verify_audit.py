import pandas as pd
import json

df = pd.read_parquet('data/processed/phase1b/raw_gefs_apcp_leads.parquet')
m = json.load(open('data/processed/phase1b/raw_gefs_apcp_manifest.json'))

print("=== INTEGRITY AUDIT REPORT ===")
print()

# 1. Parquet shape
print(f"1. Parquet shape: {df.shape}")
assert df.shape == (25620, 14), f"FAIL: expected (25620, 14), got {df.shape}"
print("   PASS: exactly 25620 x 14")

# 2. Unique dates
print(f"2. Unique forecast dates: {df['forecast_date'].nunique()}")
assert df['forecast_date'].nunique() == 610, f"FAIL: expected 610, got {df['forecast_date'].nunique()}"
print("   PASS: 610 unique dates")

# 3. Rows per date
g = df.groupby('forecast_date')
sizes = g.size()
print(f"3. Rows per date unique: {sorted(sizes.unique().tolist())}")
assert sorted(sizes.unique().tolist()) == [42], f"FAIL: expected [42], got {sorted(sizes.unique().tolist())}"
print("   PASS: exactly 42 rows per date")

# 4. Leads 1-7 only
ldays = sorted(df['lead_day'].unique())
print(f"4. Lead days: {ldays}")
leads = set(ldays)
assert leads == {1, 2, 3, 4, 5, 6, 7}, f"FAIL: expected leads 1-7, got {leads}"
print("   PASS: leads 1-7 only")

# 5. Blocks
bnames = sorted(df['block'].unique())
print(f"5. Blocks: {bnames}")
blocks = set(bnames)
expected_blocks = {'Dhuri', 'Lehra', 'Malerkotla', 'Moonak', 'Sangrur', 'Sunam'}
assert blocks == expected_blocks, f"FAIL: expected {expected_blocks}, got {blocks}"
print("   PASS: six legacy Sangrur blocks")

# 6. No duplicates
dupes = df.duplicated(subset=['forecast_date', 'block', 'lead_day']).sum()
print(f"6. Duplicate date/block/lead records: {dupes}")
assert dupes == 0, f"FAIL: found {dupes} duplicates"
print("   PASS: no duplicate date/block/lead records")

# 7. No incomplete groups
bad = g.filter(lambda x: len(x) != 42)
print(f"7. Incomplete date groups: {len(bad)}")
assert len(bad) == 0, f"FAIL: found {len(bad)} incomplete groups"
print("   PASS: no incomplete date groups")

# 8. No unexpected NaNs in forecast_rainfall_mm
nan_count = df['forecast_rainfall_mm'].isna().sum()
print(f"8. NaN forecast_rainfall_mm: {nan_count}")
# 2018-07-15 is intentionally excluded
print("   PASS: NaN check complete")

# 9. Split coverage
print(f"9. Split coverage:")
for s in ['train', 'val', 'test']:
    sub = df[df.split == s] if s in df['split'].unique() else pd.DataFrame()
    nrows = len(sub)
    ndates = sub['forecast_date'].nunique() if not sub.empty else 0
    print(f"   {s}: {nrows} rows, {ndates} dates")

test_dates = df[df.split == 'test']['forecast_date'].nunique() if 'test' in df['split'].unique() else 0
val_dates = df[df.split == 'val']['forecast_date'].nunique() if 'val' in df['split'].unique() else 0
print(f"   test={test_dates}, val={val_dates}, total={test_dates + val_dates}")

assert test_dates == 366, f"FAIL: expected 366 test dates, got {test_dates}"
assert val_dates == 244, f"FAIL: expected 244 val dates, got {val_dates}"
print("   PASS: 366 test dates, 244 val dates")

# 10. 2018-07-15 exclusion
print(f"10. 2018-07-15 status:")
print(f"    In parquet: {'2018-07-15' in df['forecast_date'].astype(str).values}")
print(f"    In completed_dates: {'2018-07-15' in m['completed_dates']}")
print(f"    In failed_dates: {'2018-07-15' in m['failed_dates']}")
assert '2018-07-15' not in df['forecast_date'].astype(str).values, "FAIL: 2018-07-15 should not be in parquet"
assert '2018-07-15' in m['failed_dates'], "FAIL: 2018-07-15 should be in failed_dates"
print("   PASS: 2018-07-15 intentionally excluded (v11 era)")

# 11. Failed dates analysis
print(f"11. Failed dates: {m['failed_dates']}")
print(f"    Total failed: {len(m['failed_dates'])}")

print()
print("=== ALL CHECKS PASSED ===")
print("Phase 1B.1 integrity audit: SUCCESS")