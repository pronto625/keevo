# Epic 16 Context: Licences, Migration & Go-Live (Phase 6)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make modular selling real and ship the refonte to production. The module engine (Epics 13–15) already exists. This epic adds the full per-tenant licence lifecycle (hybrid trial, read-only grace, real-time enforcement), à-la-carte modules and bundles, plan profiles and limits that the super-admin can edit, a super-admin "Modules" console, a backfill of existing tenants into `tenant_modules`, a GDPR check, two pre-go-live security items (employee store scope on transfers, long and safe sessions) and a canary go-live with a runbook. As of 2026-11-01 production still runs V1 2.0.1 (branch `deploy`): nothing from the refonte is live. This epic, together with 17.9 (E2E regression gate), is the remaining path to production. The Keevo × Bref fusion (Epics 18–25) starts only after this go-live.

## Stories

- Story 16.1: Licence lifecycle (hybrid trial, read-only grace, real-time)
- Story 16.2: À-la-carte + bundles
- Story 16.3: Active modules in the app: refresh without a JWT claim, offline gating
- Story 16.4: Super-admin "Modules" console
- Story 16.5: Existing-tenant migration (backfill `tenant_modules`)
- Story 16.6: GDPR account/data deletion (verify the V1-delivered core against refonte ACs)
- Story 16.7: Progressive go-live (canary) + runbook
- Story 16.8: Configurable plan profiles and limits (super-admin)
- Story 16.9: DGI e-invoicing (real-time taxation): waiting for DGI specs, does not block go-live
- Story 16.10: Employee store scope on transfer history
- Story 16.11: Long and safe sessions (short access token, one-year sliding session, rights read server-side)
- Story 16.12: Convert the remaining tenant DDL to `db/modules/<id>` (split from 16.5 on 2026-11-01; `TenantSchemaSyncService` stays a shim until then)

## Requirements & Constraints

- Lifecycle per tenant and module: `AVAILABLE → TRIAL → ACTIVE → EXPIRED → DISABLED`, and back to ACTIVE on renewal or reactivation. EXPIRED gives 7 days of read-only grace (`grace_until = expires_at + 7d`) with an upgrade CTA. Writes are refused with `MODULE_READ_ONLY`. Once grace ends the module becomes DISABLED: routes, UI and handlers are removed, and its data is **kept forever**. Reactivating it brings all the data back.
- Enforcement is **real time**: the entitlement state is recomputed from `expires_at`/`grace_until` on every request. The nightly job only materialises statuses and sends notifications, so the old "free premium until 02:00" gap must not come back.
- Hybrid trial: at signup, every paid module gets a 6-month trial. A module reactivated later gets a 14-day trial.
- Core (free) modules never enter the lifecycle and are never auto-blocked. Free tenants are limited only by plan caps. `SUSPENDED` stays a manual anti-fraud lockout.
- Data is purged only by GDPR account deletion (`DROP SCHEMA` of the tenant + anonymise/delete the public identity, with an audited request). There is no purge job for disabled modules.
- Free/Premium split (validated). Free covers: catalog (500 products), POS/sales/day closure, basic stock and thresholds, 1 store, 2 employees, basic CRM, and a fixed end-of-day WhatsApp report. Premium adds: multi-store, transfers, central view, counting, advanced reports, alerts and owner notifications, profitability, PDF/Excel exports and report configuration. Invoicing, Expenses, Import-tracking and Affiliation are sold à-la-carte.
- Plan limits move out of the `PlanType` enum into DB config. Defaults: Free = 1 store / 500 products / 2 employees. A change takes effect without a redeploy. The product count must fail closed (today `ProductCountPort` fails open).
- The backfill must be idempotent and validated by a dry-run on a prod copy (staging), with a documented rollback. PREMIUM_TRIAL tenants keep exactly their remaining trial days (none lost, none granted twice). FREE tenants get Core only. After the backfill, the state rules must give the same results as the transition rule already in place.
- Service continuity (strangler approach): existing tenants and installed apps must keep working. Migration must be reversible step by step (Flyway, backups).
- Go-live gate: backend enforcement of `MODULE_INACTIVE`/`MODULE_READ_ONLY`/`MODULE_NOT_ENTITLED` must not reach production before a minimum app version that understands these codes is forced, or before a compatibility path ships. Free tenants must be told which features become Premium.
- Required go-live config: a stable invoice/export link secret (≥ 32 chars), `KEEVO_PUBLIC_BASE_URL=https://keevo.ad2s.cm`, and the company logo directory on a persistent volume.
- Nothing technical on screen: French labels only, and no UUIDs or raw codes in the UI, exports or audit detail.

## Technical Decisions

- The source of truth is the DB: `public.module_definitions`, `public.tenant_modules` (status, tier, `expires_at`, `grace_until`, `disabled_at`, `provisioned_version`, UNIQUE(tenant, module)), and the new `public.plan_profiles` + `profile_modules` (Flyway `public` migration with seeded defaults). `PlanLimitGuard` and the downgrade/expiry service read limits from this config. The enum is kept, at most, as a seed fallback.
- `EntitlementService` (`isEnabled`/`isReadOnly`/`assertWritable`) backs `@RequiresEntitlement`. Every write to an expiring row must set `grace_until`. Renewing a READ_ONLY module goes through the lifecycle, not through `planActivation` (which counts READ_ONLY as already active).
- The expiry cron reuses or refactors `SubscriptionExpiryService` and runs under ShedLock `@SchedulerLock`.
- The engine is 100% à-la-carte. A bundle is just a list of module IDs. Applying one must use a single activation plan over the union of the dependency closures, in one topological order (`planActivation(schema, Set<String>)`), through the same engine as single activation. Admin API: `POST /admin/tenants/{id}/modules/{moduleId}/activate|deactivate`, `POST /admin/tenants/{id}/bundles/{bundleId}`, `GET /admin/modules`, `GET /admin/tenants/{id}/modules` (already returns `provisionedVersion`).
- **No `modules` claim in the JWT** (decided 2026-11-01; this overrides older docs). The app reads `GET /api/v1/tenants/me/modules` and keeps the answer on the device. That answer must carry each module's state (`{id: state}`: ENABLED/READ_ONLY/TRIAL…) built from `ModuleRegistry.moduleStates`, and must list every usable module, free ones included.
- The JWT holds identity only: `sub`, `tenantId`, `sid`, `iat`, `exp`. `JwtAuthFilter` loads role, store, membership/employee status, password-change flag, account status and session validity in one server-side read each request. It caches this per user and tenant (TTL ≤ 30 s), and invalidates the cache on every change across all instances via broadcast (Postgres `LISTEN/NOTIFY`, or Redis if present). This replaces the 5-minute `CachedTokenRevocationAdapter`. Access token: 30 min. Refresh token: 365 days sliding, with rotation and replay detection, revoking the device chain. One session per device in `refresh_tokens` (device id, readable label, last used). Rate limit on `/auth/refresh`. The super-admin gets a shorter session (about 12 h sliding). A purely indicative `role` claim stays for V1 apps until the minimum app version is forced. `STORE_REASSIGNED` disappears.
- Provisioning: `ModuleProvisioner.provisionAll` is costly (~0.2 s per tenant even when nothing changes) and does not run at boot. When exposed in the console it runs as an async job with progress, and skips tenants already at the module's highest version. `provisionAll` on a free module materialises explicit ACTIVE/FREE rows, which the backfill and any later reclassification must take into account.
- Tenant DDL cleanup is tied to the backfill. Convert the remaining tenant DDL to `db/modules/<id>`, or record a decision to keep `TenantSchemaSyncService` as a shim. Stop mirroring the tables of converted modules. Remove the crm DDL from `TenantSchemaProvisioner` while handling the `sales.client_id → clients` FK order. Use a dry-run decision on repairing drifted early tenant schemas (missing crm indexes, defaults and FKs).
- Undecided rule (decide before the first paid module ships): a free module that `requires` a paid one is currently enabled even when that dependency is DISABLED.
- Pending user decision: stray copies of global-only tables in tenant schemas, either kept forever or removed by a one-shot reviewed cleanup.
- Missing error mappings: unsupported `Content-Type` → 415 (today 500), and a French generic "Ressource introuvable" for `ErrorCode.NOT_FOUND`.
- HA, Redis and IaC stay deferred below about 500 tenants. Canary is per tenant or per instance, with a rollback runbook (Flyway rollback, backup restore) and monitoring from the observability stack.

## UX & Interaction Patterns

- Super-admin console (Next.js, reuses dashboard patterns): per-tenant module checkboxes, apply bundle, and status and expiry per module. It explains dependency blocks and offers the cascade. For example, deactivating a free module on a Premium tenant is refused because paid modules depend on it, so the console proposes deactivating them first. Every action is audited. It also has a "Profils & plafonds" section, an async provisioning job with progress, and an audited forced-deactivation override.
- Mobile app: modules refresh without re-login after a token renewal, a tenant selection or reconnection. If the refresh fails, the last known state is kept. Read-only modules show an upgrade CTA. Role, store, first name and the password-change flag come from `GET /api/v1/auth/profile` and are cached for offline display only. A new "Appareils connectés" screen lets a user close their own sessions, and lets the owner close employees' sessions. Token renewal is silent. Offline work (sales, closure, sync queue) never depends on the access token.
- Remaining gating wording: an upgrade action on the Audit/Product/Contact feature exceptions, and correct XLSX wording for a premium dataset on a Premium tenant.
- Any change under `keevo/app/` bumps `pubspec.yaml`.

## Cross-Story Dependencies

- Recommended order: 16.5 (backfill of plan-derived `tenant_modules` rows) → 16.12 (tenant DDL under Flyway) → 16.1 → 16.8 → 16.2 → 16.3. Then 16.4, 16.10 and 16.11. Then 16.6 and 10-8 (server runbooks). Then the 17.9 E2E regression on staging (go-live gate). Then 16.7 canary with a forced minimum app version.
- 16.5 unblocks 16.8. 16.2 and 16.8 feed the 16.4 console. 16.3 and 16.11 both depend on the forced minimum app version from 16.7 before the legacy JWT claims can be dropped.
- Builds on Epic 13 (registry, resolver, provisioner, lifecycle API), Epic 15 (`entitlementsProvider`, route guards, "Mes modules"), and Epic 10 (staging, backups).
- Blocker before disabling or expiring `pos`/`crm` in prod: held offline operations are invisible in server-first reads (inventory session, stock ops badges, day totals).
- Epic 17 items (17.10–17.12, 17.8, 17.7, 17.2) can slot in alongside.
