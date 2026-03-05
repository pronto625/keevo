# Keevo E2E Test Scripts

Python-based end-to-end test suite for the Keevo backend API.

## Structure

```
scripts/e2e/
├── utils.py                    # Shared: Api, Db, output helpers, Config
├── run_all.py                  # Run all stories at once
├── story_1_4_onboarding.py     # Story 1-4: Onboarding Wizard
└── README.md
```

## Prerequisites

- Python 3.10+
- Backend running on `http://localhost:8080` (or set `--base-url`)
- PostgreSQL accessible (for DB assertions). Either:
  - `psql` installed locally + Postgres reachable on port 5444, **or**
  - Docker container `keevo_postgres` running (`docker compose up -d`)
  - If neither is available, DB assertions are automatically skipped

## Quick start

```bash
# From the keevo/ root — run a single story
python3 scripts/e2e/story_1_4_onboarding.py

# Run with verbose HTTP output
python3 scripts/e2e/story_1_4_onboarding.py --verbose

# Run against a different backend
python3 scripts/e2e/story_1_4_onboarding.py --base-url http://192.168.1.10:8080

# Run all stories
python3 scripts/e2e/run_all.py

# Run a specific story via the runner
python3 scripts/e2e/run_all.py --story 1-4
```

## Configuration

All settings can be overridden with environment variables (CI-friendly):

| Env var           | Default               | Description                       |
|-------------------|-----------------------|-----------------------------------|
| `KEEVO_BASE_URL`  | `http://localhost:8080` | Backend base URL                |
| `KEEVO_DB_HOST`   | `localhost`           | PostgreSQL host                   |
| `KEEVO_DB_PORT`   | `5444`                | PostgreSQL port                   |
| `KEEVO_DB_NAME`   | `keevo_dev`           | PostgreSQL database               |
| `KEEVO_DB_USER`   | `keevo`               | PostgreSQL user                   |
| `KEEVO_DB_PASS`   | `keevo_local_pwd`     | PostgreSQL password               |
| `KEEVO_DOCKER_PG` | `keevo_postgres`      | Docker container name (fallback)  |
| `KEEVO_VERBOSE`   | `false`               | Print raw HTTP responses          |

## Story 1-4 Test Cases

| ID  | Description                                                   |
|-----|---------------------------------------------------------------|
| T01 | `POST /register` → 201 + JWT token + tenantCode               |
| T02 | `POST /onboarding/complete` CLOTHING → 200 + 13 catégories    |
| T03 | DB: 13 lignes dans `categories`                               |
| T04 | DB: `stores.name` mis à jour                                  |
| T05 | DB: `tenant_preferences` créées (sector, eod_time, alert)     |
| T06 | Sécurité: 401 sans JWT                                        |
| T07 | Validation: 400/422 secteur inconnu                           |
| T08 | Validation: 400/422 nom boutique vide                         |
| T09 | Idempotence: 400 ONBOARDING_ALREADY_COMPLETED                 |
| T10 | Tous les 8 secteurs: compte exact de catégories               |
| T11 | TenantSchemaSyncService: schema sync non-bloquant             |

## Adding a new story

1. Create `story_X_Y_<name>.py` (copy the structure of `story_1_4_onboarding.py`)
2. Use `utils.Api` for HTTP calls and `utils.Db` for DB assertions
3. Register it in `run_all.py` → `STORIES` dict

## Exit codes

- `0` — all assertions passed
- `1` — one or more assertions failed (first failure prints details and stops the suite)
