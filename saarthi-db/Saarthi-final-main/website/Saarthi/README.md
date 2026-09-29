# SAARTHI — Hyperlocal Monsoon Intelligence Platform

Spring Boot backend + vanilla HTML/JS/CSS + Leaflet frontend for a **7-day block-level
rainfall outlook** over 6 legacy Bhuvan blocks of Sangrur district, Punjab
(Dhuri, Lehra, Malerkotla, Moonak, Sangrur, Sunam).

## Data flow (no synthetic forecasting)

```
Python pipeline (NB01–NB06, validated)
  → data/processed/application/ (canonical JSON + GeoJSON + API fixtures)
  → src/main/resources/forecast/ (packaged copy served by Spring Boot)
  → REST API (/api/*)
  → vanilla JS + Leaflet frontend
```

Selected method: **Raw CHIRPS-GEFS** (bias-corrected/downscaled 7-day forecast;
ML models did not beat it — see Notebook 04). Categories LOW (<10.94 mm) /
NORMAL / HIGH (>34.66 mm) with train-only calibrated probabilities.

## Run Locally

### Prerequisites
* Java JDK 17+ (JDK 21 used here; run the jar with the same major version it was built with)
* Apache Maven 3.x

### Start Server

```powershell
mvn clean package -DskipTests
java -jar target/saarthi-backend-1.0.0.jar
```

Open `http://127.0.0.1:5000` in your browser.

## REST API

| Endpoint | Description |
|---|---|
| `GET /api/health` | status, issue/valid dates, blocks, horizon, model |
| `GET /api/blocks` | 6 blocks with Bhuvan IDs + centroids |
| `GET /api/blocks/geojson` | 6 block polygons (Leaflet-ready, EPSG:4326) |
| `GET /api/forecast/latest` | full canonical 7-day forecast |
| `GET /api/forecast/{blockId}` | one block (`bhuvan_b_269..274` or name; 404 otherwise) |
| `GET /api/forecast/summary` | district totals, extremes, category lists |
| `GET /api/advisories/{blockId}` | prototype advisories + category + probabilities |
| `GET /api/panchayats[?block=]` | panchayat lists for the farmer form |
| `POST /api/farmer-analysis` | farmer advisory (9 sections) built ONLY from the live `/api/weather/forecast/{block}` ECMWF IFS contract + `agronomy/crop_reference.json` (PAU/ICAR-cited) + SoilGrids context. Stage uses `sowing_date` + cited durations; soil moisture is the forecast surface layer (labelled, never a synthetic gauge); dry-spell risk is worded, never a fabricated %. |

## Updating the forecast package

Regenerate with Notebooks 05–06, then copy into the backend resources
(relative paths only — never absolute machine paths):

```
data/processed/application/latest_forecast.json → src/main/resources/forecast/
data/processed/application/blocks.json          → src/main/resources/forecast/
data/processed/application/sangrur_blocks.geojson → src/main/resources/forecast/
```

Rebuild + restart. No backend code changes needed. The app fails loudly at
startup if the package is missing or has ≠6 blocks.

## Prototype limits (shown in UI)

7-day horizon only · 6 blocks only · no village-level/onset-guarantee/yield claims ·
advisories are prototype decision-support guidance · wet day ≥1.0 mm is a heuristic.

## Application database (PostgreSQL) vs ML data

Two data planes, kept strictly separate:

```
ML DATA / MODEL ARTIFACTS (untouched)      APPLICATION DATABASE (new)
  data/, models/, notebooks/                 PostgreSQL via Spring Data JPA
  forecast/ + climate/ JSON packages         Flyway migration V1__farmer_schema.sql
  shadow JSONL ledgers                       panchayat → officials → farmers
                                             farmer → farms → crop_plantings
```

* The farmer database is **operational application data** (Panchayat
  officials, farmers, farms, historical crop plantings). It is **not**
  training data and never feeds model training.
* Schema lives in `src/main/resources/db/migration/V1__farmer_schema.sql`
  and is applied by Flyway; Hibernate runs with `ddl-auto=validate`.
* Configure with environment variables (never commit credentials):
  `SPRING_DATASOURCE_URL` (default
  `jdbc:postgresql://localhost:5432/saarthidb`),
  `SPRING_DATASOURCE_USERNAME` (default `saarthi_app`),
  `SPRING_DATASOURCE_PASSWORD` (default empty).
* * Tests use ephemeral in-memory H2
  (`src/test/resources/application.properties`); production uses
  PostgreSQL. Crop history is append-only; "current crop" is a
  repository query (`findFirstByFarmIdOrderByCropYearDescCreatedAtDescIdDesc`),
  never an overwrite.
* Panchayat officials authenticate using username/password credentials.
  Passwords are stored as BCrypt hashes, and successful login returns a
  JWT used to access protected APIs. Panchayat-level access control is
  enforced by the backend.
  