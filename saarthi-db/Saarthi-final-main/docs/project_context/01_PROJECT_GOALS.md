# 01 — Project Goals

## Original SIH Objective

Build a **Hyperlocal Monsoon Onset & Break Prediction System** at block/village scale that provides probabilistic outlooks for approximately **7–30 days**, covering:

- Monsoon onset timing
- Breaks / dry spells
- Revival / resurgence
- Heavy rainfall / localized precipitation events
- Influenced by climate drivers (e.g., ENSO) and regional atmospheric information

The system is intended to support block- and eventually village-scale agricultural decisions.

## Prototype Objective (Six-Day Scope)

Because only **six days including today** are available, the objective is intentionally reduced to a **focused, demonstrable prototype**:

- Prove the end-to-end pipeline is correct and defensible, not to deliver a full 7–30-day operational system.
- Provide a **block-scale** (not village) probabilistic outlook for the **next 7 days** for Sangrur district.
- Use **only reliable, already-understood datasets** to build the prototype quickly.

## What the Final Demo Should Demonstrate

For any forecast issue date `D` (e.g., `2019-09-04` or `today`), for each of the 6 Sangrur blocks, the demo must show:

1.  **Expected rainfall:** `X mm` for the next 7 days (`D+1` through `D+7`) per block
2.  **Probabilistic category:** `P(Low), P(Normal), P(High)` per block, where thresholds are derived from historical Sangrur climatology (not invented)
3.  **A block-level map** of Sangrur with 6 polygons colored by expected rainfall or by most likely category

The demo must be able to run in two modes: **historical backtest** and **live**.

## Primary User-Facing Output

For `D + B` (forecast date + block), the system outputs:

```
Block: Sangrur (or Dhuri, etc.)
Forecast issued: D
Expected 7-day rainfall: X mm
Low:    P1%
Normal: P2%
High:   P3%
Map: 6 polygons with rainfall or category
```

Categories `Low/Normal/High` must be explainable via historical percentiles (e.g., `p33 / p66` of `target_7d_rainfall_mm` in Sangrur).

## Historical vs Live Objectives

**Historical (prove skill):**
- Simulate past forecast dates `D` (e.g., `2010-2025`)
- Use only information available at `D` (no future leakage)
- Predict `D+1..D+7` and compare to **actual CHIRPS** `D+1..D+7`
- Evaluate with chronological backtesting, not random splits
- Purpose: prove the model is better than climatology/persistence/raw GEFS

**Live (demonstrate product):**
- Use *current* real data: `latest CHIRPS through today`, `latest CHIRPS-GEFS issued today`, `current ENSO`, `static soil/spatial`
- Generate **next 7–10 day outlook** for the 6 Sangrur blocks for `D = today`
- Display on a simple map/dashboard
- Purpose: show the system works operationally for the SIH demo

These are **separate workflows** — do not mix them. Do not display an old historical example as if it were a live forecast.

## Success Criteria (Prototype)

The prototype is considered successful if:

- **Correct pipeline:** No data leakage (see `05_DATA_LEAKAGE_RULES.md`), no future `D+1` as input, chronological validation only
- **Historical backtest:** At least `Climatology, Persistence, Raw GEFS, RF, XGBoost, LightGBM` compared objectively on a held-out chronological test set
- **Probabilistic output:** `Low/Normal/High` probabilities calibrated (e.g., via historical percentiles and `predict_proba` or `Brier/log loss`)
- **Live:** A live `7-day` forecast for the 6 Sangrur blocks can be generated from current data and visualized on a map
- **Maps:** Block-level visualization works for both historical and live outputs

## What Is Intentionally Out of Scope (for the six-day prototype)

Do NOT let these delay the core:

- Village-level downscaling (block level is sufficient)
- IOD, MJO, ERA5-Land, SMAP, DEM, ECMWF S2S, extensive atmospheric reanalysis
- Deep-learning models (unless core tabular pipeline is already complete and substantial time remains)
- Complex ensemble handling (only if the accessible CHIRPS-GEFS product cleanly supports it)
- A full agricultural advisory engine (beyond the block forecast + map)
- A polished production dashboard (a simple block map is sufficient)

The prototype must prioritize **correct functioning and defensible methodology over unnecessary complexity or dataset maximization.**

## Who Should Understand This File

A new engineer, without any prior conversation, should after reading this (and `00_MASTER_CONTEXT.md`) be able to answer: *What are we building, why, what should the demo show, and what is the difference between historical and live?*
