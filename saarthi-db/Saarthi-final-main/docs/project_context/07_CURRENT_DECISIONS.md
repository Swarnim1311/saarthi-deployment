# 07 — Current Decisions (Decision Log)

> **Log of important decisions made during this session.** Each decision lists *Decision, Reason, Consequence*. Do not silently reverse these without updating this log.

---

### 1. Sangrur Six-Block Legacy Bhuvan Geography

**Decision:** Use **exactly 6 legacy Bhuvan/ISRO blocks** (`Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam`) from `sangrur_blocks_bhuvan.gpkg:184320` layer `sangrur_blocks` as the **authoritative, sole spatial unit** for the prototype.

**Reason:** SIH prototype was already established on the legacy 6-block representation; the current 8-block administrative structure would change the spatial join, the `6×` block-level samples, and all downstream joins. The synthetic `sangrur_blocks.shp:916` was only a temporary test artifact.

**Consequence:** All zonal stats, maps, and `6×` joins use `b_name` from the GPKG (`MultiPolygon` irregular, `12-28` pixels/block varying). The 8-block dataset must be ignored unless explicitly instructed.

---

### 2. Bhuvan GeoPackage as Authoritative Boundary (Not Synthetic Shapefile)

**Decision:** `data/raw/boundaries/sangrur_blocks_bhuvan.gpkg:184320` (`6` `MultiPolygon`, `OGC:CRS84`, `75.55-76.20`) is the **authoritative** boundary. `sangrur_blocks.shp:916` (`Polygon ×6` rectangular `48` uniform) remains in the repo **only as a fallback/test artifact** and must **not** be used by `01`/`02` pipelines (both now explicitly prefer `*_bhuvan.gpkg`).

**Reason:** The Bhuvan parcel `bhuvan_blocks.parquet:97298502` (6393 national, 74 Punjab) filtered `s_name=="Punjab" AND d_name=="Sangrur"` was verified to produce exactly the 6 expected blocks with correct names and irregular geometries. The synthetic grid was `75.55-76.45` 2×3 rectangles, not administrative.

**Consequence:** `01` `c11_load_boundaries:1` and `02` `c3_boundaries:1` now do `gpd.read_file(bhuvan_gpkg, layer="sangrur_blocks")` and log `Using AUTHORITATIVE GeoPackage`. All `12-28` varying pixel counts prove real admin is used.

---

### 3. CHIRPS v3 as Observed / Ground Truth

**Decision:** Use **CHIRPS v3** `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv:3804691` (`35064` rows `2010-01-01` to `2025-12-31`, `6` per date) as the **sole observed rainfall source** for both historical features (≤`D`) and the **target** `target_7d = sum D+1..D+7`.

**Reason:** CHIRPS v3 is the `0.05°` quasi-global, 40+ year, `60N-60S` dataset on which CHIRPS-GEFS is calibrated (`c3g_` = CHIRPS3-GEFS). It is already aggregated to the 6 Sangrur blocks, continuous (no missing dates), and validated in `02` `Cell 13`.

**Consequence:** `02` `Cell 14` must validate `D+1..D+7` all have 6 blocks; early `2001-2009` `D` are automatically `invalid` (no CHIRPS target yet) — `8758→5472` valid. The intended `2001–2019` train must be **restricted to `2010–2019`** unless earlier CHIRPS is acquired.

---

### 4. CHIRPS-GEFS v3 Daily as Forecast Predictor

**Decision:** Use **CHIRPS-GEFS v3 daily** `https://data.chc.ucsb.edu/products/CHIRPS-GEFS/v3/daily/global/YYYY/MM/DD/c3g_YYYY.MM.DD.tif` (`7200×2400×1` `64.9 MB` `float32` `EPSG:4326`) as the **most important predictor**.

**Reason:** It is the CHIRPS-calibrated GEFS forecast (`c3g`), bias-corrected, `0.05°`, `60N-60S`, with pre-2000 reformats and operational `2000–2019` + `2021–2025` archive (2020 gap) — directly comparable to CHIRPS.

**Consequence:** Historical system simulates `At D, use GEFS issued on D, compare to CHIRPS D+1..D+7`. Archive is date-based folders with `c3g_YYYY.MM.DD.tif` per date.

---

### 5. Seven-Day Primary Target

**Decision:** **Primary target is 7-day accumulated observed rainfall** `target_7d_rainfall_mm = sum CHIRPS D+1..D+7` per `forecast_date+block`. `D` itself **must NOT** be included, nor `D+8`.

**Reason:** SIH asks for 7–30 day hyperlocal outlook; 7-day is the focused prototype that balances skill and demo feasibility. A 7-day window is also the most actionable for agricultural advisories.

**Consequence:** `02` `Cell 28` builds `target_7d` with `target_start = D+1`, `target_end = D+7`, requires 7 valid days, `target_valid=False` if any missing (no zero fill). Secondary `target_10d` only if core 7-day works.

---

### 6. Shared Model Across Six Blocks with `lat/lon`

**Decision:** Train **one shared model** across all 6 blocks, not 6 independent models. Represent spatial differences via `centroid latitude, longitude` (from `sangrur_blocks_bhuvan.gpkg`) + soil.

**Reason:** `PROJECT_CONTEXT` explicitly says *do not initially train six completely independent models*; a shared model can learn `history + forecast + ENSO + season + location + soil` jointly.

**Consequence:** `Notebook 03` will add `latitude, longitude` per block; block ID is not the only spatial feature.

---

### 7. Recent Rainfall Features (≤D Only)

**Decision:** Later `Notebook 03` will add `rain_1d, rain_3d, rain_7d, rain_14d, rain_30d` (sums ending at `D`) and `rain_lag_1..7` (`rain_lag_1 = D, ..., rain_lag_7 = D-6`).

**Reason:** Recent observed rainfall is a strong predictor, but must be **strictly ≤D** to avoid leakage.

**Consequence:** For `D=2019-09-04`, `rain_7d = sum 2019-08-29..2019-09-04`, never `2019-09-05`.

---

### 8. Chronological Validation (No Random Split)

**Decision:** **Never randomly shuffle** `forecast_date`. Use **chronological** splits, keep `2020` excluded.

**Intended (adapted to `2010` CHIRPS start):** `2010–2019` train / `2021–2023` val / `2024–2025` test (original `2001–2019` train is truncated to `2010–2019` because `2001-2009` has no target). `CANDIDATE_DATES 8758 → VALID_DATES 5472` already enforces chronological, `2020` excluded, `D+7 ≤ 2025-12-31`.

**Reason:** Time series with seasonality and autocorrelation; random split leaks future `D+1..D+7` into train via overlapping windows.

**Consequence:** `Notebook 04` will do `df[year <=2019]` etc., no `train_test_split(random_state=)`.

---

### 9. RF / XGBoost / LightGBM Candidates, No Winner Yet

**Decision:** Compare **Climatology, Persistence, Raw GEFS** baselines vs **Random Forest, XGBoost, LightGBM** — all tabular tree models. **No model declared winner yet.**

**Reason:** `PROJECT_CONTEXT` says choose on **unseen chronological test performance** (`MAE/RMSE/R²` + `Accuracy/F1/log loss/Brier`), not on popularity. Deep learning is explicitly **not** part of core prototype unless core is complete and substantial time remains.

**Consequence:** `Notebook 04` will train all 6 and select objectively.

---

### 10. CSV + Parquet for `Cell 35`

**Decision:** `Cell 35` saves **both** `data/processed/forecast_features_base.csv:1294` and `.parquet:5982` (and `01` saves both for `chirps_gefs_sangrur_*`).

**Reason:** User explicitly requested `csv + parquet` — Parquet is the preferred downstream format (efficient), CSV is human-readable/debugging/export.

**Consequence:** `Notebook 03` can read either; Parquet is preferred for `32k` rows.

---

### 11. Explicit `NaN` for `gefs_d2..d7`

**Decision:** `Cell 35` schema is `forecast_date,block,gefs_d1,gefs_d2,gefs_d3,gefs_d4,gefs_d5,gefs_d6,gefs_d7,target_7d_rainfall_mm` where **`gefs_d1` is real** (from single daily file at `D`), **`gefs_d2..d7` are `NaN`** (not zero, not dropped) — `NaN` means “not available yet, to be filled in Notebook 03 by joining 7 daily files per `D`”.

**Reason:** Current single-daily product has `count 1` (`descriptions (None,)`), not a 7-band stack. `gefs_d2..d7` cannot be invented; `NaN` correctly represents missing, `0` would be wrong (would imply 0 mm forecast).

**Consequence:** `Cell 34` does **not** mark `gefs_d2..d7` all-`NaN` as `FAIL`; it reports `gefs_d1 0 missing PASS` and `gefs_d2..d7 all NaN — expected at this stage (to be filled in Notebook 03)`. `Notebook 03`'s job is to acquire/join the remaining daily files.

---

### 12. Six-Day Scope Constraint

**Decision:** Prioritize **correct pipeline → backtest → baselines → RF/XGB/LGBM → probabilistic → live → dashboard** in six days, in that order. Avoid expanding to `IOD, MJO, ERA5, SMAP, DEM, ECMWF S2S` etc. unless core is complete.

**Reason:** `PROJECT_CONTEXT` explicitly says the prototype must prioritize correct functioning over maximizing datasets.

**Consequence:** `02` `Cells 31–35` are a demo `3` dates (`18` rows) / `4` dates (`24` rows) `forecast_features_base` with `gefs_d1` only, not full `5472` bulk — full `5472` (`346 GB` raw) is a one-time background job with `KEEP_RAW=False`.

---

### 13. No Deep Learning Initially

**Decision:** No deep learning for core prototype.

**Reason:** Tabular `forecast_date+block` with `~32k` samples is well-suited to `RF/XGB/LightGBM`; deep learning adds overhead without clear gain for this prototype and is explicitly excluded in `PROJECT_CONTEXT`.

**Consequence:** If core `01–06` + dashboard are complete and time remains, deep learning could be *considered* as an extension, not a replacement.

---

### 14. Notebook 04 Winner: Raw GEFS (Honest Baseline Win, 2026-09-10)

**Decision:** Declare **Raw GEFS (`gefs_7d_total`)** the winning model (val MAE 19.031, test MAE 19.539) over RF/XGB/LGBM, and report that ML does not add skill on this JJAS task — rather than forcing an ML winner.

**Reason:** Selection was strictly lowest validation MAE per the agreed rule; test confirmed it (RF 20.176 / XGB 21.082 / LGBM 21.344). Hiding this would be dishonest and would corrupt downstream inference.

**Consequence:** `models/best_model.joblib` stores the baseline rule (reload-verified); feature importances shown are the best-ML (XGBoost) supplement, labeled as such. Future work (more seasons, calibrated post-processing) may revisit, but must beat 19.539 test MAE honestly.

---

### 15. Inference Artifacts + Live Pipeline (2026-09-10)

**Decision:** NB04 produces explicit inference artifacts (`best_model.joblib` with `model_type: raw_gefs`, `models/inference_config.json`, `models/probability_calibration.npz` holding the winner's train-residual ECDF + thresholds); NB05 consumes them without refitting anything, branching on `model_type` instead of assuming `.predict(X)`.

**Reason:** The winner is a rule, not an estimator; probabilities must reuse NB04's exact residual-ECDF methodology with train-only parameters. Lead 0 is forbidden by config flag.

**Consequence:** Live issue date D = latest fully-available GEFS bundle (walk-back ≤30d on gaps, else fail loudly); prediction = sum of D+1..D+7 block means; wet day ≥1.0 mm/day is a labeled prototype heuristic; CHIRPS/ENSO/soil are explicitly-flagged context only (NaN + availability flags when local files lag D, never fabricated).

---

### 16. Application Data Contract (2026-09-10)

**Decision:** NB06 packages NB05 output into `data/processed/application/` with Bhuvan-derived block IDs (`bhuvan_b_<b_code>`), static GeoJSON kept separate from dynamic forecast JSON, generic prototype-labeled advisories (no agronomic claims), and Spring Boot fixtures + `docs/api/` contract. No Streamlit/Flask/FastAPI; backend = Spring Boot, frontend = React.

**Reason:** Backend needs a stable, validated, reload-verified contract; geometry and forecast must version independently.

**Consequence:** Spring Boot DTOs bind directly to `latest_forecast.json`/`blocks.json`/`health.json`; React renders map + cards + charts from JSON (CSV is compatibility-only).

---

### 17. Saarthi Full-Stack Integration (2026-09-10)
**Decision:** Integrate the validated NB06 package into the existing Saarthi app (Spring Boot + vanilla HTML/JS/CSS + Leaflet — NO React migration, NO Streamlit/Flask) on branch `real-forecast-integration`, replacing the synthetic engine end-to-end while preserving UI structure.

**Reason:** Round-1 app used hardcoded 8-block data, 0.15 stub probability, pattern-math forecasts and false RF/92.4% claims; the validated contract (`data/processed/application/`) is the2014 fix point.

**Consequence:** New `RealForecastService` (classpath `forecast/` resources, fail-loud validation) is the single source of truth; endpoints `/health /blocks /blocks/geojson /forecast/latest /forecast/{blockId} /forecast/summary /advisories/{blockId} /panchayats /farmer-analysis` serve real data; frontend is fetch-first with 6-block GeoJSON polygons, 7-day-only UI, explicit error messages (no silent synthetic fallback); farmer analysis driven by real P(LOW); verified end-to-end (API + Playwright browser tests, 0 console errors).

---

*All decisions above are reflected in the current code and must be preserved. If a decision needs to change, update this file and `00_MASTER_CONTEXT.md` together.*

### 18. MJO + Climate-Context Extension (2026-09-11)

**Decision:** Ship compact CNN-LSTM MJO forecaster as validated climate CONTEXT ONLY (test beats persistence: RMM1 0.627 vs 0.884); keep Raw GEFS rainfall winner; IOD stays fail-soft Unavailable (no OISST locally, no fabrication); website gains `/api/climate-context` + `/api/mjo/latest` + context card.

**Reason:** No held-out experiment showed climate features beating GEFS, so honesty requires context-only. Time budget forced accepting IOD BLOCKED.

**Consequence:** Demo story = GEFS rainfall + MJO/ENSO context + IOD method documented; `07_IOD_CNN_LSTM` remains future work.

### 19. IOD LightGBM Result — Honest Persistence Win (2026-09-11)

**Decision:** Ship LightGBM IOD forecaster as experimental context (test MAE 0.1936 vs persistence 0.1573 — persistence wins, reported as-is); display live DMI +0.033 Neutral (2026-06, stale-flagged) with model label; keep rainfall untouched.

**Reason:** Monthly HadISST DMI (1,877 rows) supports a valid pipeline but not superiority; hiding this would corrupt the demo story.

**Consequence:** Climate card shows IOD available+stale; integration decision stays USE CLIMATE CONTEXT ONLY.

---
### 20. Frozen GEFS Dataset — Phase 1B.1 Ingestion Complete (2026-09-17)

**Decision:** Raw NOAA GEFS APCP dataset ingested and frozen for the SAARTHI Phase 1B comparison arm. All 610 dates fully processed; dataset is closed for modifications unless a genuine integrity issue is found.

**Reason:** Phase 1B.1 completed bounded 25-date batches until 366/366 test dates, 610/610 total dates, and 25,620 × 14 parquet were achieved. The dataset is now the authoritative baseline for the GEFS comparison arm. No further ingestion runs should be performed unless new dates or data sources require addition.

**Consequence:**
- `data/processed/phase1b/raw_gefs_apcp_leads.parquet` is the authoritative frozen artifact: exactly 25,620 rows × 14 columns, 610 unique forecast dates, 42 rows per date, leads 1–7 only, six legacy Sangrur blocks (Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam).
- 2018-07-15 is intentionally excluded (GEFS v11-era exclusion by design; different model from v12, excluded from raw-APCP arm; CHIRPS-GEFS baseline covers this period).
- 15 failed dates recorded in manifest: `2018-07-15` (v11 exclusion) + 14 dates from historical network/availability attempts. The 14 non-v11 failed dates must NOT be treated as incomplete since their data is already present in the final parquet (42 rows each, no incomplete groups).
- Train/validation/test split convention preserved: val = 244 dates (2021–2022), test = 366 dates (2023–2025). No train dates in the parquet split (2016–2019 years are excluded from the raw-APCP arm by design; CHIRPS-GEFS baseline covers those years consistently).
- All zonal statistics use the 6 legacy Bhuvan blocks from `sangrur_blocks_bhuvan.gpkg:184320` layer `sangrur_blocks`.
- No model retraining, IFS/AIFS work, or rainfall methodology changes are permitted on the basis of this dataset.
- Dataset is immutable: any future additions must be in a new phase (1B.3+) and must not modify the existing 25,620 × 14 artifact.

**Frozen GEFS Convention (immutable):**
- 00Z initialization only
- Lead 1 through Lead 7 only (lead 0 excluded)
- Forecast buckets f030 through f192 (D+1 through D+7)
- Six legacy Sangrur Bhuvan blocks: Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam
- APCP 6-h bucket accumulation: [H-6, H] in kg/m² == mm
- Pre-2021 operational GEFS is v11 (1.0 grid, undecodable range slices) — excluded by design
- 2018-07-15 is the single v11-era exclusion date (recorded in manifest failed_dates, excluded from parquet)
- CHIRPS-GEFS v3 is the existing rainfall baseline; this dataset is the new comparison arm
- Area-weighted zonal means over 6 blocks using `all_touched=True` geometry mask at 0.25-degree resolution
- Ensemble mean (v12-31mem, 31 members + geavg) for v12 era (2021+)

---
*All decisions above are reflected in the current code and must be preserved. If a decision needs to change, update this file and `00_MASTER_CONTEXT.md` together.*

### 21. Phase 1B.3 Fair Common-Period Result — CHIRPS-GEFS Carried Forward (2026-09-18)

**Decision:** Carry forward **CHIRPS-GEFS** as the rainfall source on the empirical common-period result; keep the frozen raw GEFS APCP parquet untouched as a documented comparison arm.

**Reason:** On the genuinely common 610-date JJAS set (2021-06-01..2025-09-30, 25,620 daily rows/arm, 3,660 7-day forecasts/arm, 00Z, leads D+1..D+7, frozen T33/T66), CHIRPS-GEFS 7-day MAE 19.336 / R² 0.338 beats raw GEFS APCP 7-day MAE 20.457 / R² 0.246 (ΔMAE +1.121, ΔR² −0.092 favouring CHIRPS-GEFS); daily overall MAE 4.087 vs 4.153 (Δ +0.066). Test-slice reproduction check passes (CHIRPS-GEFS test MAE 19.539 ≈ frozen 19.54). Repro: `python src/evaluation/phase1b_3_common_evaluation.py` → `data/processed/phase1b/phase1b_3_*` + `reports/phase1b/PHASE1B_3_GEFS_VS_CHIRPSGEFS_REPORT.md`.

**Consequence:**
- No frozen source parquet/CSV modified; no downloads; no retraining; no threshold/geography changes.
- Prior `phase1b_comparison_report.json` (34-date partial GEFS arm) is superseded for comparison purposes by `phase1b_3_common_report.json`; it is left in place, not deleted.

### 22. Phase 1B.4 IFS Access Check — TIGGE History BLOCKED, No Download (2026-09-18)

**Decision:** STOP before any IFS download; historical IFS ENS acquisition is BLOCKED on credentials. No files under `data/processed/phase1b/` were modified in this phase.

**Reason:** Access check 2026-09-18 confirmed: (a) intended history source is TIGGE via ECDS portal/MARS (registration + SSO required) — no anonymous bulk route; (b) no credentials in this environment (no `ECMWF_API_KEY`/`ECMWF_API_URL`/`MARS_API_KEY`, no `~/.ecmwfrc`/`~/.ecmwfapirc`); (c) `ecmwf-opendata` + `eccodes` are installed but Open Data keeps only the last ~12 runs (live/format-validation only, not 2016–2025 history); (d) existing `src/data/build_ifsens_leads.py` already encodes this honestly (`--tigge` fails loudly, `--opendata-test` proven 2026-09-15 on 2 live `ifs-hres` deterministic dates, NOT the ENS headline). Re-running `--opendata-test` would append live deterministic rows unrelated to the historical ENS task, so it was deliberately not run.

**Consequence:**
- To unblock, user must: register at `ecmwf.int`, request MARS/TIGGE access, configure credentials, then run `python src/data/build_ifsens_leads.py --tigge YYYY-MM-DD` for a one-date pilot, then bounded JJAS acquisition (2016–2019 / 2021–2022 / 2023–2025, 00Z, tp steps 0..168 differenced to D+1..D+7, 6 Bhuvan blocks). Estimated volume once unblocked: ~4 MB tp-only per init × ~1098 JJAS inits ≈ ~4.4 GB + processing.
- Frozen GEFS parquet untouched; no retraining; no threshold/geography changes; Phase 1B.5 three-way comparison stays pending.

### 23. Phase 1+2 Live Operational Weather — Open-Meteo + ECMWF IFS (2026-09-19)

**Decision:** SAARTHI's live rainfall outlook is now served from an operational NWP feed — Open-Meteo delivery layer + ECMWF IFS model (`models=ecmwf_ifs`, 16 `Asia/Kolkata` days) — aggregated to the 6 legacy Sangrur blocks. New additive endpoints `GET /api/weather/forecast`, `/forecast/{blockId}`, `/freshness`; timeline page shows a "Live Operational Outlook" card. Historical TIGGE/IFS bulk acquisition is no longer required for the live product.

**Reason:** Live-verified 2026-09-19 (keyless HTTP 200, 16 daily dates, 384 hourly steps, units mm/°C/%/km/h; backend smoke: 6 blocks × 16 days, multipoint n4–n7, no centroid fallback; weather tests 30/30). SAARTHI does NWP nowhere — its value is block downscaling, freshness guarantees, and farm advisories. Full rationale: `docs/project_context/11_LIVE_WEATHER_ARCHITECTURE.md`.

**Consequence:**
- CHIRPS-GEFS/GEFS work (decisions 20–22) retained as historical validation/background; `/api/forecast/*` legacy endpoints, NB01–NB06, frozen parquets, thresholds, and geography unchanged.
- Days 1–7 operational, 8–15 extended/lower-confidence, 16–30 NOT served (never synthesised); missing rainfall stays null, never zero-filled.
- SAARTHI makes no outperformance claim vs ECMWF IFS. Phase 3 adds ENSO/IOD/MJO climate intelligence for days 16–30 and agricultural risk, strictly separate from the deterministic feed.

### 24. Phase 3A Climatology + MJO Gate — NO-GO for Outlook Mathematics (2026-09-19)

**Decision:** Ship block climatology and observed trailing rainfall as DISPLAY ONLY.
MJO stays explanatory-only; no MJO/recent probability modifiers, no new ML, no
OutlookService yet. Full evidence: `reports/phase3/PHASE3A_CLIMATOLOGY_MJO_REPORT.md`.

**Reason:** Built train-frozen block×DOY climatology (2010–2019 fit; deployment `full`
artifact labelled as such) + W3/W4 tercile targets + MJO phase composites.
In-sample gate GO (JJAS active phases 2 +19%, 6 −21%, 7 −58%, 6/6 block agreement,
stable halves, physically coherent). Out-of-sample backtest (weekly JJAS 2020–2025,
n=630/arm/window): CLIM+RECENT Brier skill −1.1%/−1.5%, CLIM+RECENT+MJO −3.4%/−2.5%
(W3/W4); JJAS-only-fit sensitivity also negative. Directional signal exists but
probabilities are miscalibrated — net negative skill, so the math does not ship.

**Consequence:**
- Phase 3B serves frozen deployment normals + observed trailing totals/anomalies +
  narrative, confidence capped by input vintage. IOD/ENSO unchanged (context-only).
- MJO math revisit only via calibrated probabilities on more data, never by tuning.
- Updater decision (manual SOP vs fetch) is the precondition for live weeks 3–4.

### 25. Phase 3B Display-Only Weeks 3–4 Outlook (2026-09-20)

**Decision:** Ship a climatology-only W3 (D+17..23) / W4 (D+24..D+30) display
baseline via `OutlookService` + `OutlookController`
(`GET /api/outlook/17-30`, `/{blockId}`, `/freshness`) + timeline Weeks 3–4
panel. Probabilities are the honestly-labelled tercile prior (1/3 each);
W3/W4 amounts are climatological normals for reference only; MJO/IOD/ENSO are
context-only by construction (cannot alter probabilities); no new ML; no
external updater — freshness-aware capping instead.

**Reason:** Phase 3A proved MJO/recent modifiers have negative out-of-sample
Brier skill, so the only shippable baseline is the frozen deployment
climatology plus observed trailing rainfall as separate context. Confidence is
MODERATE at best (HIGH never issued); stale climate inputs or outside-JJAS
cap at LOW with explicit reasons.

**Consequence:**
- `website/Saarthi/src/main/resources/climatology/block_doy_normals_full.csv`
  is the packaged deployment copy (read-only; source of truth remains
  `data/processed/climatology/`).
- Frontend says "Extended climate outlook", never "30-day weather forecast";
  existing 7–16 day Live Operational Outlook untouched.
- Full endpoint semantics: `docs/api/api-contract.md` (Weeks 3–4 section).

### 26. Phase 3C Checkpoint 0 Pre-screen — NO-GO, Stop ML (2026-09-20)

**Decision:** NO-GO. Stop Phase 3C ML development. Keep Phase 3B
display-only W3/W4 as production. No Checkpoint 1, no RF/LightGBM, no
probabilistic endpoint, no frontend change, no updater.

**Reason:** Fixed pre-screen (`src/w3w4/checkpoint0.py`, train-frozen
thresholds, features ending D-3, weekly JJAS 2020–2025, issue-clustered
bootstrap CIs): SEASONAL Brier skill vs FLAT −0.54% (W3) / +0.01% (W4),
PERSIST_LR −1.89% (W3) / −1.26% (W4); all JJAS CIs include zero or are
negative; logloss agrees; no block subgroup contradicts. Evidence:
`reports/phase3/PHASE3C_CHECKPOINT0_REPORT.md` +
`data/processed/w3w4/checkpoint0_metrics.json`.

**Consequence:**
- `src/w3w4/` + `data/processed/w3w4/` + Phase 3C report are frozen
  negative-result artifacts; nothing from them is served.
- Phase 3B endpoint/frontend, weather engine, Phase 1B artifacts, and all
  frozen parquets untouched.
- Revisit only via a new pre-registered calibrated design, never by tuning.

### 27. Phase 4.0 Dry-Spell Architecture Gate — NO-GO, Stop Expansion (2026-09-21)

**Decision:** NO-GO for expanding the dry-spell risk architecture. No ML,
no threshold tuning, no Phase 4.1, no backend/frontend work.

**Reason:** Fixed transparent rule (zero fitted parameters, antecedent
ending D-3, weekly JJAS issues): perfect-forecast leg PASSES (L7 recall
0.668/precision 0.876), but HISTORICAL GEFS PSEUDO-FORECAST leg misses the
pre-registered recall gate (L7 recall 0.579 vs 0.60 required; precision
0.759 passes), replicated in an independent 2016–2019 fold (recall 0.568).
Shortfall is forecast-driven, not rule-driven. Evidence:
`reports/phase4/PHASE4_0_DRY_SPELL_REPORT.md` + `src/risk/` +
`data/processed/risk/`.

**Consequence:**
- `src/risk/` + `data/processed/risk/` + Phase 4.0 report are frozen
  artifacts; nothing from them is served. Soil re-aggregation confirmed
  existing values to rounding (provenance only, no substitution).
- Only sanctioned revisit: pre-registered confirmatory evaluation with the
  unchanged frozen rule (e.g. live-IFS shadow comparison), never tuning on
  these eval folds, never ML on this signal.

### 28. Live IFS Shadow Validation Layer — Additive Data Collection (2026-09-21)

**Decision:** Build a lightweight live-IFS shadow validation layer that
preserves one immutable record per (forecast issue, block) on every fresh
Open-Meteo/ECMWF-IFS retrieval and later attaches CHIRPS D+1..D+7 truth.
Validation/data-collection only — not a production risk API, no frontend,
no ML, no tuning, Phase 4.0 rule frozen and reused (not copied).

**Reason:** Phase 4.0 proved the rule viable under perfect information but
limited by historical GEFS forecast skill (recall 0.579 vs 0.60 gate). The
only sanctioned next step per Decision 27 is confirmatory evaluation on new
data; live IFS evidence must be collected first, before any production claim.

**Consequence:**
- Java: `com.saarthi.shadow` (`DrySpellRule` frozen port, `ShadowLedger`
  JSONL first-write-wins, `ShadowCaptureService` fail-soft hook in
  `LiveWeatherService`); additive `RecentRainfallService.dailyWindow()`;
  ledger default `data/processed/shadow/ifs_shadow.jsonl` (override via
  `-Dsaarthi.shadow.path=`). No second weather client, no serving-path
  behaviour change (50/50 backend tests green).
- Python: `src/shadow/` (`capture` incl. offline envelope fallback, `truth`
  updater, `metrics` with `source: live_ifs_shadow` + frozen gate reported
  only, `test_shadow.py` 10 tests green alongside 9 Phase 4.0 tests).
- Architecture: `docs/project_context/13_SHADOW_VALIDATION.md`. Verdict
  stays `INSUFFICIENT_EVIDENCE` until ≥30 issues resolve; historical GEFS
  and live IFS metrics are never mixed.

### 29. Phase 4.1 Excess Rainfall + Field-Work Validation — Mixed (2026-09-21)

**Decision:** Ship two NEW transparent risk detectors as validated
diagnostics with split verdicts: FIELD_HIGH (D+1..D+3, ≥2 wet days ≥1mm)
is a GO candidate for later production integration (NOT yet integrated);
all three EXCESS components are NO-GO (diagnostic evidence only). No ML,
no tuning, no Java/API/frontend, no soil drivers. Phase 4.0 + shadow
system untouched.

**Reason:** Fixed a priori rules (excess-v1 train-fit 2010–2019, field-v1
proposed), validated on HISTORICAL GEFS PSEUDO-FORECAST vs CHIRPS (weekly
Wednesday JJAS 2021–2025, n=522, issue-clustered CIs): FIELD_HIGH prec
0.721/rec 0.809/F1 0.763 beats both baselines uniformly across blocks;
EXCESS daily/3d/7d recall only 0.242/0.325/0.283 (miss 2/3+ of events).
Evidence: `reports/phase4/PHASE4_1_EXCESS_FIELD_REPORT.md` +
`src/risk/{excess_rain,field_work,backtest_41,test_phase41}` +
`data/processed/risk/{excess_thresholds_v1,backtest_41_*}`.

**Consequence:**
- FIELD_HIGH may proceed to a future production-integration checkpoint
  (threshold packaging, Java port, live collection) — integration has NOT
  happened. `FIELD_HIGH_PRECIP_PROB` is unit-tested + IFS-compatible only,
  NOT historically verified (no GEFS probs).
- EXCESS detectors stay diagnostic; never tune on eval, never ML.
- Context: `docs/project_context/14_PHASE41_RISKS.md`. Next: live-shadow
  accumulation (dry-spell track) or FIELD_HIGH integration — never excess
  tuning, composites, or soil drivers (4.2).

### 30. Phase 4.2 FIELD_HIGH Production Integration — Shipped (2026-09-21)

**Decision:** Integrate ONLY the validated FIELD_HIGH rule into the live
Open-Meteo/ECMWF-IFS pipeline as `GET /api/risks[/{blockId}]?window=3d` +
timeline "Agricultural Risk" card. Frozen binary rule (`field_work_v1` =
Python `field-v1` HIGH condition: ≥2 wet days ≥1mm in D+1..D+3; MODERATE
collapses to LOW, 1-wet-day evidence kept in reason codes). No threshold
change, no ML, no excess-rain, no dry-spell/shadow changes, no soil, no
crop claims.

**Reason:** Phase 4.1 validated FIELD_HIGH on historical GEFS (prec 0.721 /
rec 0.809 / F1 0.763, uniform blocks); excess detectors are NO-GO and stay
diagnostic-only. Integration reuses the served forecast object (no second
request), is fail-soft (risk never breaks weather), and labels confidence
MODERATE/LOW with an explicit GEFS-not-IFS disclaimer until live transfer
is confirmed.

**Consequence:**
- Backend: `com.saarthi.risks` (`FieldWorkRule`, `FieldWorkService`,
  `RiskController`); contract in `docs/api/api-contract.md` (risks section).
  Verified live 2026-09-21 (all 6 blocks, 404/400 states, shadow captured
  6 lines independently, evidence matches forecast).
- Frontend: additive risk card (HIGH/LOW/UNAVAILABLE + evidence +
  freshness); weather UI unchanged. Tests: 65/65 backend green (15 new),
  `node --check` clean, 38/38 Python green.
- Frozen: rule, excess NO-GO, dry-spell + shadow system. Next: shadow
  accumulation (≥30 issues) or a future integration checkpoint — never
  composites, waterlogging, planting/irrigation, or ML.

### 31. Phase 4.3 Composite Agricultural Risk (composite_v1) � Shipped (2026-09-21)

**Decision:** Ship a deterministic rule-based priority composite (NOT a score): FIELD_HIGH -> HIGH; provisional dry-spell watch (same frozen DrySpellRule, labelled pending IFS validation) -> MODERATE, never HIGH; stale -> MODERATE; else LOW; incomplete D+1..D+3 -> UNAVAILABLE. Heavy-rain p95 flag display-only; CHIRPS/climatology/soil context-only (never severity/confidence). Extended /api/risks additively (legacy keys preserved) + timeline card. Separate field_shadow.jsonl ledger (field-shadow/v1) with pre-registered gate recall >=0.60/precision >=0.40/>=30 issues. No weights, no tuning, no ML, no excess production, no crop advice.

**Reason:** Approved Phase 4.3 plan: scores cannot be weighted honestly (HIGH/DRY share one axis; antecedent/soil add no skill; excess NO-GO). Priority logic is traceable, truth-table tested, and each claim keeps its own validation status.

**Consequence:**
- Backend: com.saarthi.risks (CompositeRiskService, FieldShadowService, HeavyRainThresholds/SoilContext/ClimatologyContext readers, RiskController delegation); resources risk/excess_thresholds_v1.json (byte-identical train-frozen copy) + risk/soil_context.json. Contract extended in docs/api/api-contract.md.
- Verified live 2026-09-21 (all 6 blocks LOW on a dry day, both shadow ledgers 6 lines each on one fetch, 404/400 OK, timeline card live). Tests: 92/92 backend (incl. Spring context-load + 13 truth-table), 45/45 Python, node clean.
- Frozen: all rules/thresholds/gates, dry-spell ledger/schema untouched, excess diagnostic-only.

### 32. Live Evidence Operations — OPTION 1 truth (CHIRPS v3 sat) — Operational (2026-09-22)

**Decision:** Validate live Open-Meteo/ECMWF-IFS against independent **CHIRPS v3.0 daily `sat`** truth (UCSB CHC, satellite-IR + stations, public domain). Maintain a separate versioned current-truth CSV (`Sangrur_Block_Daily_Rainfall_2026_current.csv`, per-row `source` final/prelim-sat) via `src/shadow/update_chirps_current.py` (windowed /vsicurl reads, idempotent, missing-never-zero); frozen 2010–2025 file untouched. `rnl` (ERA5-split) daily excluded by design; Open-Meteo Previous/Single/Historical archives are backtest-only, never truth. Status via `python -m src.shadow.status`; gate stays >=30 observed issue dates per ledger (currently 0/0 — INSUFFICIENT EVIDENCE).

**Reason:** CHIRPS v3 prelim/final is the official current observation product in the same v3 family as the frozen truth, with operational latency (final 2026-08-31, prelim 2026-09-15 observed) and no credentials; `sat` keeps within-pentad daily structure ECMWF-free.

**Consequence:**
- Live-verified 2026-09-22 (HTTP 200, 6 blocks x 16 days, issue captured in both ledgers; duplicate-safe). Truth attach run: all 24 records correctly `pending` (windows incomplete; 2026-09-16..18 unpublished, never zero-filled).
- Tests: 47/47 Python (6 new), 92/92 backend, diff clean. Full reference: `docs/project_context/16_LIVE_EVIDENCE_OPERATIONS.md`, `reports/phase4/PHASE4_LIVE_EVIDENCE_OPERATIONS_REPORT.md`. Next: daily fetch + updater/attach as pentads publish — never tuning, ML, or sub-30 evaluation.
- Frozen: all rules/thresholds/gates, dry-spell ledger/schema untouched, excess diagnostic-only. Next: shadow accumulation (dry >=30, field >=30) � never scores, soil weights, composites-of-composites, or ML.

---

### Farmer Advisory Rebuild on the Live Weather Contract

**Decision:** `POST /api/farmer-analysis` was rebuilt as a deterministic 9-section advisory (`inputs, location, crop, stage, weather, soil, water_demand, risks, advisory, sources`) driven ONLY by the live `/api/weather/forecast/{block}` ECMWF IFS contract, the versioned crop reference `website/Saarthi/src/main/resources/agronomy/crop_reference.json` (PAU PoP Kharif/Rabi + ICAR, cited per crop), and SoilGrids soil context.

**Reason:** The previous advisory mixed the frozen CHIRPS-GEFS 7-day package with a synthetic root-zone moisture gauge (42 âˆ’ P(Low)Ã—26 + soil/irrigation offsets) and a fabricated dry-spell percentage. Outputs were not materially driven by real inputs.

**Consequence:** Soil moisture shown to farmers is the ECMWF IFS 0â€“7 cm forecast (labelled "model forecast â€” not a measurement"); dry-spell risk is worded (watch/no watch) from live rainfall patterns, never a percentage; crop stage appears only when `sowing_date` + cited durations support it, otherwise explicitly "not available"; missing live values surface as "No data". Portal timeline and map now consume the live contract; the old `Outputs` shape (`dry_spell_probability`, `four_pillars`, `root_zone_soil_moisture_pct`, `whatsapp_share`) is removed.