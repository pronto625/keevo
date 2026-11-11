# Epic 16 Context: Licences, Migration & Go-Live (Phase 6)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make modular commercialisation real on the refonte branch: per-tenant module lifecycle (trial, read-only grace, real-time enforcement), à-la-carte modules and bundles, super-admin-configurable plan profiles and limits, a super-admin "Modules" console, idempotent backfill of existing tenants into `tenant_modules`, GDPR account deletion, hardened sessions and employee store scoping, tenant DDL owned by module migrations, then a controlled canary go-live. Production still runs V1 2.0.1; nothing from the refonte is live. Most stories are done. Remaining: 16.12 (reopened: the parallel boot upgrade fails from the packaged jar, fix required before any deploy), 16.7 (canary go-live, waits for milestone GL) and 16.9 (DGI e-invoicing, waiting for DGI specs).

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
- Story 16.12: Convert the remaining tenant DDL into module migrations — in-progress (reopened 06/11)
- Story 16.13: Other stores' data confidentiality on employee devices (sync scope + purge) — done (API)
- Story 16.14: API ready for React Native mobile and dashboard clients — done

## Requirements & Constraints

- **Backend only.** This repo delivers the API; the React Native app and the Next.js dashboard live elsewhere, the Flutter app is only a test client. Each spec still writes "Mobile"/"Dashboard" sections; client work goes under separate `m<story>`/`d<story>` tracker keys.
- **Compatible with installed V1 2.0.1 apps.** Every API change is additive until V1 is retired: no existing response shape, field or error code changes meaning.
- **Pattern and complexity (blocking review finding):** the right design pattern for the problem; no N+1, set-based SQL instead of per-row loops, keyset pagination on growing lists, an index for every new read path, batched work across tenants. Long multi-tenant operations run as async admin jobs with progress and a retrievable report, never inside one HTTP request.
- **`deferred-work.md` stays empty.** Route every deferred item to its owning story (inline checkbox in the epics file plus a tracker comment).
- **Lifecycle rules (locked):** hybrid trial (6 months at sign-up, 14 days on reactivation); expired paid module → 7-day read-only grace → DISABLED (hidden); module data kept forever, only GDPR account deletion purges (`DROP SCHEMA`); Core never auto-disabled; enforcement computed in real time from `expires_at`/`grace_until`, the nightly job only materialises states and notifies.
- **Offer is data:** limits and plan composition come from `plan_profiles`/`profile_modules`; `PlanType` constants are only a logged fallback. Changes apply without redeploy.
- **Tokens carry identity only** (`sub`, `tenantId`, `sid`, `iat`, `exp`); rights are read server-side on every request. Legacy indicative claims stay until the minimum app version is forced.
- **No technical data in the UI** (UUIDs, raw codes); every user-facing or admin error has a French message (guard test on every `ErrorCode`).
- **No tenant data may change** during schema conversion or upgrade.

## Technical Decisions

- **Module engine:** `ModuleRegistry`, `DependencyResolver` (`planActivation(schema, Set<String>)`, one topological plan over the union of closures; a bundle is a list of module ids), `ModuleLifecycleService`, `EntitlementService`/`@RequiresEntitlement`, `ModuleProvisioner` (Flyway per module under `db/modules/<id>/`, history `flyway_history_<id>`).
- **`tenant_modules.origin` ∈ {SYSTEM, PLAN, ADMIN}:** plan sync and backfill never touch SYSTEM/ADMIN rows; `PlanModuleRows` matches the transition rule row for row (property-tested). Every expiring row sets `grace_until = expires_at + 7 d`.
- **No `modules` claim in the JWT.** The app reads `GET /api/v1/tenants/me/modules` (whole catalogue, `{moduleId, state}`, grace end visible to employees).
- **Admin async jobs** (`MODULE_BACKFILL`, `MODULE_PROVISION_ALL`, `PROFILE_RESYNC`) return 202 + `jobId`, tracked via `GET /admin/jobs/{id}`.
- **Access state:** `AccessStateService` cache ≤ 30 s, invalidated on every instance via PostgreSQL `LISTEN/NOTIFY keevo_access_state`; sessions per device in `refresh_tokens`.
- **Sync scope:** `PullCommand.scope` resolved only in `SyncController.pull`; pull response carries `scope` and `reseed`.
- **16.12 state:** all tenant DDL lives in `db/modules/<id>/V1` (idempotent, adds missing indexes/defaults/PKs, never an FK on an existing table); `TenantSchemaSyncService` is gone; a set-based foundation upgrade runs at boot. **Open defect:** the parallel boot upgrade (`ModuleProvisionerService.upgradeFoundation`, `FlywayModuleMigrations`) fails a random share of tenants when run from the packaged jar — concurrent Flyway instances read module scripts from the same open jar and one closes it under another (`Stream closed`, `Zip file closed`; once broke the class loader). Expected fix: read every module script once into memory at startup and give each Flyway run a `resourceProvider` over that list (no classpath scan per tenant), plus a test running the parallel upgrade from a packaged jar. Stopgap: `keevo.tenant-schema.upgrade-parallelism=1` (0 failures on 362 tenants in ~10 s).
- **Load targets (fusion):** boot < 2 min, tenant provisioning < 5 s, one module migration across all schemas < 10 min; a reusable harness exists (`scripts/load/measure-tenants.sh`).
- **Stray global-only tables in tenant schemas** (`shedlock`, `refresh_tokens`, …) are kept and reported at boot; cleanup is a user decision attached to 16.7.

## Cross-Story Dependencies

- **Go-live (16.7) only at milestone GL (decision 03/11/2026):** the whole Keevo × Bref merge first (epics 19–21 API parity, RN app at parity 22.x/23.x, migration tool with a 100 % reconciled dress rehearsal 24.2–24.10/24.12/24.13; since 2026-11-11 (ADR-10) go-live day is also the one-shot Bref cutover), then the 17.9 E2E regression gate, then 16.7. 10-8 (server runbooks, incl. backup at > ~100 tenants) is also a prerequisite.
- **16.12 must be fixed before any production deploy**, on the branch; it feeds 19.11 (tenant volume).
- **16.7 go-live checklist:** force a minimum app version that understands `MODULE_INACTIVE`/`MODULE_READ_ONLY`/`MODULE_NOT_ENTITLED` (or a compatibility path) and turn off `keevo.jwt.legacy-claims` in that release; decide whether to refuse clients without `X-App-Version` for all roles (`LegacyClients`) at V1 retirement, warning owners first; V1 Flutter does not know `trackStock` (products without stock tracking are unsellable there — plan for mixed tenants); backfill dry run + two real runs on a prod copy; on that copy recheck missing FKs of derived schemas and measure first-boot tenant upgrade to size parallelism and probes; invoice link secret, `KEEVO_PUBLIC_BASE_URL`, persistent logo dir; 415 mapping and a French « Ressource introuvable » for `NOT_FOUND`; ghost tenants in prod (2 real of 25) deleted by ops after user confirmation and a full backup, through the existing tenant deletion, never raw SQL.
- **Still open at epic level (client side):** before disabling/expiring `pos`/`crm` in prod, held offline operations need visibility (inventory session reads, held stock-op badges, closed-day totals); upgrade action on Audit/Product/Contact feature exceptions and the XLSX premium-dataset wording.
- **16.9** is independent of go-live; until DGI specs exist, invoices never claim « normalisée DGI ».
