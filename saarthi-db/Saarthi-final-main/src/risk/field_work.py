"""Phase 4.1B — field-work disruption rule (NO ML, NO tuning, NO crop claims).

Generic field-work interference from forecast rainfall. Two windows:
  primary   D+1..D+3 -> tier HIGH (>=2 wet days) / MODERATE (1) / LOW (0)
  secondary D+1..D+7 -> wet-day count + heavy-rain evidence, NO tier

Wet day: precipitation >= 1.0 mm/day (exact 1.00 mm IS wet; mirrors the
Phase 4.0 dry boundary). Missing values never become zero: any missing day
in the window makes the tier/count unavailable (None).

Evidence flags (reported separately, never fused into a score here):
  FIELD_WET_DAYS_2OF3 / FIELD_WET_DAY_1OF3 : tier reasons for D+1..D+3
  FIELD_HEAVY_RAIN    : max daily in window exceeds the block JJAS daily_p95
                        (reuses the excess-rainfall threshold as the heavy marker)
  FIELD_HIGH_PRECIP_PROB : max forecast precip probability in window >= 70%.
                        Probability is forecast evidence ONLY, never treated
                        as observed rainfall. (GEFS archive has no probs, so
                        this flag is unit-tested + IFS-compatible, NOT
                        historically validated.)

The D+1..D+3 tier rule is PROPOSED a priori; its historical prevalence is
reported by backtest_41.py and the rule is NOT altered to improve metrics.
"""

from __future__ import annotations

WET_MM = 1.0  # wet day: rainfall >= 1.0 mm/day (1.00 mm IS wet)
PROB_FLAG_PCT = 70.0
METHOD_VERSION = "field-v1"

TIER_REASONS = {
    "HIGH": "FIELD_WET_DAYS_2OF3",
    "MODERATE": "FIELD_WET_DAY_1OF3",
    "LOW": "FIELD_DRY_3D",
}


def is_wet(x) -> bool | None:
    """True/False per the 1.0mm boundary; None when missing (never zero)."""
    if x is None:
        return None
    import math
    if isinstance(x, float) and math.isnan(x):
        return None
    return float(x) >= WET_MM


def classify_3d(f3: list, heavy_p95: float | None = None,
                probs3: list | None = None) -> dict:
    """Tier + evidence over forecast D+1..D+3."""
    assert len(f3) == 3, "exact D+1..D+3 window required"
    flags = [is_wet(v) for v in f3]
    if any(w is None for w in flags):
        return {"window": "D+1..D+3", "tier": None, "wet_days": None,
                "max_daily_mm": None, "heavy_rain": None,
                "high_prob": None if probs3 is None else None,
                "reason_codes": ["FIELD_UNAVAILABLE"],
                "method_version": METHOD_VERSION}
    n = sum(flags)
    tier = "HIGH" if n >= 2 else ("MODERATE" if n == 1 else "LOW")
    mx = max(float(v) for v in f3)
    heavy = (None if heavy_p95 is None else bool(mx > heavy_p95))
    reasons = [TIER_REASONS[tier]]
    if heavy:
        reasons.append("FIELD_HEAVY_RAIN")
    high_prob = None
    if probs3 is not None:
        assert len(probs3) == 3
        avail = [p for p in probs3 if p is not None]
        high_prob = bool(avail and max(avail) >= PROB_FLAG_PCT)
        if high_prob:
            reasons.append("FIELD_HIGH_PRECIP_PROB")
    return {"window": "D+1..D+3", "tier": tier, "wet_days": n,
            "max_daily_mm": round(mx, 3), "heavy_rain": heavy,
            "high_prob": high_prob, "reason_codes": reasons,
            "method_version": METHOD_VERSION}


def describe_7d(f7: list, heavy_p95: float | None = None) -> dict:
    """Evidence-only summary over forecast D+1..D+7 (no tier by design)."""
    assert len(f7) == 7, "exact D+1..D+7 window required"
    flags = [is_wet(v) for v in f7]
    if any(w is None for w in flags):
        return {"window": "D+1..D+7", "tier": None, "wet_days": None,
                "max_daily_mm": None, "heavy_rain": None,
                "reason_codes": ["FIELD_UNAVAILABLE"],
                "method_version": METHOD_VERSION}
    mx = max(float(v) for v in f7)
    heavy = (None if heavy_p95 is None else bool(mx > heavy_p95))
    reasons = ["FIELD_7D_WET_COUNT_%dOF7" % sum(flags)]
    if heavy:
        reasons.append("FIELD_HEAVY_RAIN")
    return {"window": "D+1..D+7", "tier": None, "wet_days": int(sum(flags)),
            "max_daily_mm": round(mx, 3), "heavy_rain": heavy,
            "reason_codes": reasons, "method_version": METHOD_VERSION}


def observed_3d(o3: list) -> dict:
    """CHIRPS-truth summary over observed D+1..D+3 (same tier rule, for agreement)."""
    assert len(o3) == 3
    flags = [is_wet(v) for v in o3]
    assert all(w is not None for w in flags), "truth days must all be present"
    n = sum(flags)
    return {"tier": "HIGH" if n >= 2 else ("MODERATE" if n == 1 else "LOW"),
            "wet_days": n, "max_daily_mm": round(max(float(v) for v in o3), 3),
            "disrupted": int(n >= 2)}
