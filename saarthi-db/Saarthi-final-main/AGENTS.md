# AGENTS — Hyperlocal Monsoon (Sangrur 6-Block)

> **Entry point for all future AI sessions (OpenCode, Codex, Cursor, Claude, etc.)**

This repository has **persistent engineering context** in:

```
docs/project_context/
```

**Before modifying any file in `hyperlocal-monsoon/` (`saarthi-2`), you MUST read:**

```
docs/project_context/00_MASTER_CONTEXT.md
```

`00_MASTER_CONTEXT.md` is the **single source of truth** for the current project state. It contains:

- What we are building (SIH Hyperlocal Monsoon Onset & Break, 6-block prototype)
- Why (SIH 7–30 day hyperlocal outlook, reduced to 6-day 7-day prototype)
- Geographic scope (Sangrur, 6 legacy Bhuvan blocks: Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam)
- Authoritative boundaries (`data/raw/boundaries/sangrur_blocks_bhuvan.gpkg` layer `sangrur_blocks`, not synthetic `sangrur_blocks.shp`)
- Datasets, roles, exclusions, prediction target (`target_7d = CHIRPS D+1..D+7`), forecasting philosophy (`D` vs `D+1..D+7`), feature/model/validation strategy
- Architecture, repository structure, notebook status (01:30 validated, 02:42 cells with rainfall features, 03:31 cells COMPLETE with final_ml_dataset), completed work, decisions, unresolved issues, next steps, critical leakage rules

**Then read in order:**

```
01_PROJECT_GOALS.md
02_SYSTEM_ARCHITECTURE.md
03_DATASETS.md
04_ML_STRATEGY.md
05_DATA_LEAKAGE_RULES.md
06_NOTEBOOK_STATUS.md
07_CURRENT_DECISIONS.md
08_UNRESOLVED_ISSUES.md
09_NEXT_STEPS.md
10_OPENCODE_INSTRUCTIONS.md
```

**Do NOT resurrect the old project.** This is a **brand-new SIH prototype** — the old project is not the source of truth.

**Saarthi full-stack integration COMPLETE (2026-09-10, branch `real-forecast-integration`):**
**Spring Boot serves the validated NB06 package (Raw CHIRPS-GEFS, 6 blocks); vanilla JS + Leaflet UI**
**fetch-first with zero console errors (Playwright-verified). Before further app work, read `00` → `10`.**
**Do NOT retrain on test; do NOT introduce Streamlit/React migration.**

**For detailed rules, see `10_OPENCODE_INSTRUCTIONS.md` — it defines: do not guess (inspect), preserve completed work, don't overwrite raw, don't silently change 6-block geography, don't introduce excluded datasets (IOD/MJO/ERA5/SMAP/DEM/ECMWF S2S/deep learning), don't leak future `D+1` as input (target is label only), ask/stop when `CHIRPS-GEFS` issue vs target mapping is unresolved, prefer incremental changes, preserve resumability, update docs when decisions change.**

*If `00_MASTER_CONTEXT.md` contradicts any older file, `00` wins.*

---

*This file is intentionally concise. Full context is in `docs/project_context/`. Do not duplicate every detail here.*
