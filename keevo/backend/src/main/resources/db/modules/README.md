# Module migrations (`db/modules/<module>/`)

Each module owns the tables it creates in a tenant schema (`kv_xxxxxx`). Its versioned scripts
(`V<version>__<description>.sql`) run per tenant through Flyway, history table `flyway_history_<module>`
(`FlywayModuleMigrations`).

## Foundation modules (Story 16.12)

`core, store, catalog, inventory, crm, pos, sync, reporting, messaging` (the free, non-platform modules) are the
**single author of every tenant table**. (`client-portal`, Story 21.7, is free too but owns no tenant table: its data is
public, so it has no folder here.) Their scripts:

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
- When a new tenant column is JPA-mapped, also mirror it on the public template table in a public migration
  (`ALTER TABLE public.<table> ADD COLUMN IF NOT EXISTS …`), for boot validation only (`ddl-auto: validate` checks
  the mapping against `public`); the tenant schemas still get it only from the module migration (Story 19.12, V49).
- No `${` in a script: placeholders are off so Flyway runs exactly the text registration runs (a test enforces it).
- Keyset pull indexes (`idx_*_sync_keyset`) are not created here: `SyncKeysetIndex` / `SyncKeysetIndexJob` own them.

## Modules introduced after the go-live (Story 19.13, interface profile)

A **new module** (a new `keevo/modules/<id>/module.json`) is new to every existing user: it must not appear in their
menus until their owner enables it in *Paramètres › Mes modules*. Every such module therefore ships, in the same
release, a **public** migration (`db/migration`) that owner-disables it for the existing tenants, set-based and
idempotent:

```sql
INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, '<module-id>', false, now() FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
```

Tenants created afterwards follow the initial activation rules (`public.module_activation_rule_sets`, editable by the
super-admin). A module the owner must never disable declares `"ownerToggleable": false` in its manifest. Never write
`tenant_module_activation` from `tenant_modules` code paths (plan sync, admin lifecycle, expiry job): the owner's choice
is orthogonal to the entitlement.

Shipped so far (each pinned in `ModuleIdsAtGoLivePinTest`, which fails when a new module ships without its line): `expenses`
(V56), `finance` (V58), `ai-assistant` (V65), `mcp-access` (V66), `whatsapp-cloud` (V68), `client-portal` (V70).

**Exception — `client-portal` (V84, user decision of 11/11/2026):** the client portal behaves like Bref's « retrouver
mes factures », where every merchant's invoices are visible to the client: V84 removes V70's own rows (owner-disabled,
no actor, tenant created before V70 ran), so the portal is on for every company; an owner who switched it off since
keeps his choice, and the owner can still switch it off in *Mes modules*. A future exception follows the same shape
(delete only the rows the go-live migration wrote, never an owner's choice).

## Paid modules with tables (lazy provisioning)

A paid module's tables (e.g. `ai-assistant`, `whatsapp-cloud`) are created only for the tenants that use it, on their
first gated call (`ModuleProvisioner.ensureProvisioned`). Code that writes to them outside a gated request (a webhook,
a job) must call `ensureProvisioned(schema, moduleId)` itself before writing (`whatsapp-cloud`'s Meta webhook does).
