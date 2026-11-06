# Load measurement — many tenants (Story 19.11)

`measure-tenants.sh` measures the four FUS-NFR3 targets on a **throwaway clone** of a database:

| Step | What is timed | Target |
|---|---|---|
| boot | first boot on the clone (catch-up of newer scripts), then a warm boot: `Started KeevoApplication` and the `Tenant tables: …` summary | < 2 min |
| migration | latest `flyway_history_<module>` row (default `messaging`) deleted in every live schema, restart: the boot upgrade re-applies it everywhere | < 10 min |
| provisioning | `POST /api/v1/auth/register` × N (default 10), throwaway numbers `+2376990xxxxx` created only in the clone | < 5 s each |
| backup / restore | `pg_dump -Fc` inside the container, `pg_restore` into a second throwaway database | measured |

```bash
scripts/load/measure-tenants.sh --help
scripts/load/measure-tenants.sh                       # clone of keevo_dev → keevo_load, app on port 4600
scripts/load/measure-tenants.sh --out /tmp/load.env   # also key=value measures
```

Safety, by construction:

- the source is only used as `TEMPLATE` (it must have **no open connection**: stop the local backend first — the
  script refuses, it never kills anything); the load database cannot be the source, `keevo_dev`, `postgres`,
  `template*` or a name containing `prod`; port 4500 is refused;
- the app runs from an empty directory (no `.env` import) with `--keevo.whatsapp.provider=noop`, FCM, Expo, SMS and
  email off, `--keevo.scheduling.enabled=false`, profile `load` (no `dev` SQL/TRACE logging);
- available memory ≥ 4 GB is awaited before each boot and before the dump;
- the app is stopped by port (`fuser -k <port>/tcp`), throwaway databases are dropped on exit (also on failure or
  Ctrl-C) unless `--keep`.

Staging (routed to 17-9): the same script with `--container`, `--pg-host/--pg-port/--pg-user` and `--source-db`
pointing at the staging copy.

Guard tests (no database touched): `bash scripts/tests/measure-tenants-guard-test.sh`.

Latest report: `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md`.
