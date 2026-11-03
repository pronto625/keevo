# Module migrations (`db/modules/<module>/`)

Each module owns the tables it creates in a tenant schema (`kv_xxxxxx`). Its versioned scripts
(`V<version>__<description>.sql`) run per tenant through Flyway, history table `flyway_history_<module>`
(`FlywayModuleMigrations`).

## Foundation modules (Story 16.12)

`core, store, catalog, inventory, crm, pos, sync, reporting, messaging` (the free, non-platform modules) are the
**single author of every tenant table**. Their scripts:

- are executed **twice** on every new tenant: inside the registration transaction by `TenantSchemaProvisioner`
  (Flyway cannot see the uncommitted schema), then by Flyway after commit, which records the history;
- run on tenants built by any earlier code (old programmatic DDL, old `LIKE public` copies);

so they must stay **idempotent forever**: `CREATE … IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, guarded `DO $$ … $$`
blocks for constraints, `SET DEFAULT` for missing defaults, a foreign key only together with its table, never
`DROP`/`UPDATE`/`DELETE` of data, `SET LOCAL lock_timeout = '10s'` at the top. A constraint added to an existing
table is added `NOT VALID` and validated in a guarded block (legacy rows never abort an upgrade).

## Rules

- A new tenant column or table ships as a **module `V2+` migration**, never as a public migration
  (`db/migration`): nothing copies public tables into tenant schemas any more.
- A released script is never edited (`validateOnMigrate`: an edited script fails that module on every tenant
  until restored). A change is a new version, idempotent as well.
- No `${` in a script: placeholders are off so Flyway runs exactly the text registration runs (a test enforces it).
- Keyset pull indexes (`idx_*_sync_keyset`) are not created here: `SyncKeysetIndex` / `SyncKeysetIndexJob` own them.
