# SIH Monsoon Outlook — API contract (Python package → Spring Boot → React)

Source of truth: `data/processed/application/latest_forecast.json` (canonical), `blocks.json`, `sangrur_blocks.geojson`, fixtures in `api/`.

## Suggested endpoints (implemented by Spring Boot, not Python)

| Endpoint | Returns |
|---|---|
| `GET /api/forecast/latest` | full canonical forecast (`system` + `forecast` + `summary`) |
| `GET /api/forecast/{blockId}` | single block object from `forecast.blocks[]` (`block_id` = `bhuvan_b_<b_code>`) |
| `GET /api/blocks` | static block metadata (`blocks.json`) |
| `GET /api/forecast/summary` | `summary` object (district totals, category lists) |
| `GET /api/advisories/{blockId}` | `advisories[]` of the block (prototype-labeled) |

## Field reference (per block)

- `block_id` (string, stable Bhuvan scheme), `block_name`, `forecast_7d_total_rainfall_mm` (mm),
- `category` ∈ {LOW, NORMAL, HIGH} (thresholds LOW<10.94 / HIGH>34.66 mm, training-only),
- `probability` {low, normal, high} ∈ [0,1], sum ≈ 1 (residual-ECDF, train-only),
- `daily_forecast[7]` each {date, day 1..7, rainfall_mm}; total == sum(daily) within 0.05 (presentation rounding),
- `indicators` {max_daily_rainfall_mm, max_daily_rainfall_date, wet_days, dry_days} (wet ≥ 1.0 mm/day prototype heuristic),
- `advisories[]` each {level ∈ {info, watch}, topic, message, label=prototype}.

## Freshness

`api/health.json` {status, forecast_available, issue_date, blocks_available, forecast_horizon_days, generated_at} backs a Spring Boot health/freshness check.

## Limits (must surface in UI)

7-day block outlook only; no village-level, onset-guarantee, yield, IOD/MJO/ERA5/SMAP/S2S claims. Method: Raw CHIRPS-GEFS (ML did not beat it).
