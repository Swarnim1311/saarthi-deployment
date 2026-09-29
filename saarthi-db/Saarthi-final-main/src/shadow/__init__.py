"""Phase 4.x live-IFS shadow validation (data-collection layer, NO ML, NO tuning).

Collects real Open-Meteo/ECMWF-IFS forecast -> CHIRPS-outcome pairs so a future
checkpoint can answer: "did this exact IFS forecast predict a D+1..D+7 dry spell?".

The Phase 4.0 dry-spell RULE is FROZEN (see src/risk/backtest.py):
  WARN = (dry_run_through_D-3 >= 3 and f_dry_D+1..D+7 >= 5) or (f_dry >= 6)
with dry day = rainfall < 1.0 mm/day (1.00 mm is NOT dry), NaN never zero.

Ledger: local JSONL, one object per (issue, block). Immutable first-write-wins.
Truth: CHIRPS D+1..D+7 only, attached after the window completes. Missing data
is NEVER zero-filled; incomplete truth stays pending/unavailable.
"""

from src.risk.common import BLOCKS, DRY_MM, FEAT_LAG

SCHEMA_VERSION = "ifs-shadow/v1"
RULE_VERSION = "phase4.0-frozen"
SOURCE_TAG = "live_ifs_shadow"
PROVIDER = "Open-Meteo"
MODEL = "ECMWF IFS (ecmwf_ifs)"
MODEL_SELECTOR = "ecmwf_ifs"

# Frozen Phase 4.0 rule parameters (mirrors src/risk/backtest.py; DO NOT TUNE).
DRY_RUN_MIN = 3
F_DRY_WARN = 5
F_DRY_STRONG = 6
ANTECEDENT_DAYS = 28  # D-30..D-3 lookback cap (matches backtest a[i-30:i-2])

# Pre-registered gate (unchanged; live IFS must accumulate evidence first).
RECALL_GATE = 0.60
PRECISION_GATE = 0.40
MIN_ISSUES_FOR_GATE = 30  # below this the gate is reported, never declared

__all__ = [
    "BLOCKS", "DRY_MM", "FEAT_LAG", "SCHEMA_VERSION", "RULE_VERSION",
    "SOURCE_TAG", "PROVIDER", "MODEL", "MODEL_SELECTOR",
    "DRY_RUN_MIN", "F_DRY_WARN", "F_DRY_STRONG", "ANTECEDENT_DAYS",
    "RECALL_GATE", "PRECISION_GATE", "MIN_ISSUES_FOR_GATE",
]
