# Epic 16 Context: Licences, Migration & Go-Live (Phase 6)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make modular commercialisation real on the refonte branch: per-tenant module lifecycle (trial, read-only grace, real-time enforcement), à-la-carte modules and bundles, super-admin-configurable plan profiles and limits, a super-admin "Modules" console, idempotent backfill of existing tenants into `tenant_modules`, GDPR account deletion, hardened sessions and employee store scoping, then a controlled canary go-live. Production still runs V1 2.0.1; nothing from the refonte is live. Most stories are done (16.1–16.6, 16.8, 16.10, 16.11, 16.13, 16.14). Three remain: 16.12 (tenant DDL under module Flyway), 16.7 (canary go-live) and 16.9 (DGI e-invoicing, waiting for DGI specs).

## Stories

- Story 16.1: Licence lifecycle (hybrid trial, read-only grace, real-time) — done
- Story 16.2: À-la-carte + bundles — done
- Story 16.3: Active modules in the app without a JWT claim + offline gating — done
- Story 16.4: Super-admin "Modules" console — done
- Story 16.5: Migration of existing tenants (backfill `tenant_modules`) — done
- Story 16.6: GDPR account/data deletion — done
- Story 16.7: Progressive go-live (canary) + runbook — backlog, waits for milestone GL
- Story 16.8: Configurable plan profiles & limits — done
- Story 16.9: DGI electronic invoicing (real-time taxation) — backlog, blocked on DGI specs
- Story 16.10: Employee store scope on transfer history — done
- Story 16.11: Long, safe sessions — done
- Story 16.12: Convert the remaining tenant DDL into module migrations — backlog, next actionable
- Story 16.13: Other stores' data confidentiality on employee devices (sync scope + purge) — done (API)
- Story 16.14: API ready for React Native mobile and dashboard clients — done

## Requirements & Constraints

- **Backend only.** This repo delivers the API. The React Native app and the Next.js dashboard live elsewhere. The Flutter app is only a test client. Each spec still writes a "Mobile"/"Dashboard" section, and mobile work goes under separate `m<story>`/`d<story>` tracker keys.
- **Compatible with installed V1 2.0.1 apps.** Every API change is additive: no existing response shape, field or error code changes meaning. The two success shapes (`/auth/*` raw vs `{data}`) and the two pagination shapes stay as they are until V1 apps are retired.
- **Design pattern and complexity (blocking review finding if violated):** use the pattern that fits the problem. Also required: no N+1, set-based SQL instead of per-row loops, keyset pagination on growing lists, an index for every new filter/join/sort, and batched work over many tenants. Long multi-tenant operations run as async admin jobs with progress and a retrievable report (`public.admin_jobs`). They never run inside one HTTP request.
- **`deferred-work.md` stays empty.** Route every deferred item to its owning story (inline checkbox in the epics file plus a tracker comment).
- **Lifecycle rules (locked):** the hybrid trial is 6 months global at sign-up and 14 days when a module is reactivated. An expired paid module gets a 7-day read-only grace, then DISABLED (hidden). Module data is kept forever. Only GDPR account deletion purges (`DROP SCHEMA`). Core modules are never auto-disabled. Enforcement is computed in real time from `expires_at`/`grace_until`; the nightly job only materialises states and sends notifications.
- **Offer is data:** limits and plan composition come from `plan_profiles`/`profile_modules`. The `PlanType` constants are only a fallback. Changes take effect without a redeploy.
- **Tokens carry identity only** (`sub`, `tenantId`, `sid`, `iat`, `exp`). Rights are read server-side on every request. Legacy indicative claims stay until the minimum app version is forced.
- **No technical data in the UI** (UUIDs, raw codes). Every user-facing or admin error has a French message. A guard test enforces a French message for every `ErrorCode`.
- **Activation is super-admin only** (V1 decision). Owner self-activation arrives with the fusion (19.13).

## Technical Decisions

- **Module engine:** `ModuleRegistry`, `DependencyResolver` (`planActivation(schema, Set<String>)` builds one topological plan over the union of closures; a bundle is just a list of module ids), `ModuleLifecycleService`, `EntitlementService`/`@RequiresEntitlement` and `ModuleProvisioner` (Flyway per module under `db/modules/<id>/`, history table `flyway_history_<id>`, `baselineOnMigrate`).
- **`tenant_modules.origin` ∈ {SYSTEM, PLAN, ADMIN}:** plan sync and backfill never touch SYSTEM or ADMIN rows. `PlanModuleRows` reproduces the transition rule row for row, with a property-tested parity check. Every write of an expiring row sets `grace_until = expires_at + 7 d`.
- **No `modules` claim in the JWT.** The app reads `GET /api/v1/tenants/me/modules`. It returns the whole catalogue with a `{moduleId, state}` entry per module and the grace end date, which employees also see.
- **Admin async jobs:** `MODULE_BACKFILL`, `MODULE_PROVISION_ALL` and `PROFILE_RESYNC` return 202 with a `jobId` and are tracked through `GET /admin/jobs/{id}`. `provisionAll` skips tenants already at the latest shipped version.
- **Access state:** `AccessStateService` caches for at most 30 s. The cache is invalidated on every instance through PostgreSQL `LISTEN/NOTIFY keevo_access_state`. Sessions are tracked per device in `refresh_tokens`.
- **Sync scope:** `PullCommand.scope` (`PullScope`) is resolved only in `SyncController.pull`. The pull response carries `scope` and `reseed`. When a device's scope changes, the server re-seeds it from EPOCH.
- **16.12 (tenant DDL):** `TenantSchemaSyncService` (the runtime `public → tenant` copy, called from the security filter) stays a shim until this story. The story has four parts:
  - Convert the remaining core tenant DDL (catalog, inventory, commerce, sync, identity, store…) to `db/modules/<id>`.
  - Remove the crm DDL from `TenantSchemaProvisioner`. This requires fixing the order of the FK `sales.client_id → clients`, either by provisioning crm before the sales DDL or by moving the FK into a migration.
  - Decide whether drifted early schemas get repaired: 157 of 331 dev schemas were created by the old `LIKE public` copy and lack crm indexes, defaults and FKs. Base the decision on a dry run against a prod copy. A repair uses `CREATE INDEX CONCURRENTLY` outside Flyway's transaction, `SET DEFAULT`, and FKs added `NOT VALID` then validated after an orphan report.
  - Stop mirroring converted tables.

  No tenant data may change. The fusion's tenant-volume work (19.11) builds on this: core DDL under module Flyway, the sync service off the boot path, and load targets (boot < 2 min, tenant provisioning < 5 s, one module migration across all schemas < 10 min).
- **Global-only tables stray-copied into tenant schemas** (`shedlock`, `refresh_tokens`, …): they are kept and only reported at boot. Whether to clean them up is a user decision, attached to 16.7.

## Cross-Story Dependencies

- **Go-live (16.7) happens only at milestone GL (decision of 03/11/2026).** GL requires the whole Keevo × Bref merge on the branch and staging: epics 19–21 (API parity), the RN app at parity (22.1–22.13 and 23.1–23.11, parity inventory 22.17, user tests 22.18) and the migration tool with a 100 % reconciled dry run (24.1–24.10, 24.12). Then the 17.9 multi-surface E2E regression (the go-live gate, also covering Bref journeys and the RN app), then 16.7. The refonte and the Bref modules ship to production together. 10-8 (server runbooks) is also a prerequisite.
- **16.12 is done before go-live, on the branch** (also before the fusion's tenant load). The tracker note placing it "after go-live" is outdated. It feeds 19.11.
- **16.7 carries the go-live checklist:**
  - Force a minimum app version that understands `MODULE_INACTIVE`/`MODULE_READ_ONLY`/`MODULE_NOT_ENTITLED`, or ship a compatibility path.
  - Turn off `keevo.jwt.legacy-claims` in that same release.
  - Run the backfill dry run plus two real runs on a prod copy.
  - Set the invoice link secret, `KEEVO_PUBLIC_BASE_URL` and a persistent logo directory.
  - Map 415 for unsupported media types and add a French "Ressource introuvable" message for `NOT_FOUND`.
- **Still open at epic level:** before disabling or expiring `pos`/`crm` in prod, offline held operations need visibility (inventory session reads, held stock-op badges, closed-day totals). The app also still needs an upgrade action on Audit/Product/Contact feature exceptions and the XLSX premium-dataset wording. These are client-side items.
- **16.9** is independent of go-live. Until the DGI specs exist, invoices must never claim to be « normalisée DGI ».
