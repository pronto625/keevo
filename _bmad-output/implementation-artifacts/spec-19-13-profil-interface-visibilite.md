---
title: '19.13 — Interface profile: owner activation of plan modules, navigation preset, new modules disabled'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** After the merge, an existing user (Keevo or Bref) would suddenly see every module of their plan, including ones they never used (e.g. FREE gains `invoicing`, which would also turn every POS sale into an automatic invoice). Today a module is usable as soon as the plan includes it; the owner cannot hide a plan module, and the app has no navigation preset.

**Approach:** Add an owner-activation layer distinct from entitlement: a module is usable only when the plan includes it AND the owner has not disabled it; an owner-disabled module has effective state `DISABLED`, so every existing gate (REST `@RequiresEntitlement`, sync push, sync pull, `@ModuleScoped` listeners, scheduler gates) already stops it. Expose it on `GET /tenants/me/modules` (additive fields + `meta.navigationPreset` CAISSE/DOCUMENT) and preferences, an owner toggle endpoint, a go-live backfill (existing tenants: modules new to them disabled, FREE granted `invoicing`), and DB activation rules (by sale flow, optional sector) applied at onboarding and reusable by the Bref importer (24.2), editable by super-admin.

## Boundaries & Constraints

**Always:**
- Storage: new public table `public.tenant_module_activation(tenant_id VARCHAR(50), module_id VARCHAR(50), activated BOOLEAN NOT NULL, updated_at TIMESTAMPTZ NOT NULL, updated_by UUID NULL, PRIMARY KEY(tenant_id, module_id))` (same tenant_id type/format as `tenant_modules`, module_id kebab CHECK). No row ⇒ activated. It is NOT a `tenant_modules` row: plan sync, admin lifecycle and the nightly expiry job never read or write it, so an owner choice survives plan changes and an admin/plan change never re-enables an owner-disabled module.
- `ModuleStateRules`: after the existing computation, if the result is ENABLED or READ_ONLY and the module is owner-toggleable and has an activation row with `activated=false` ⇒ `DISABLED`. Core rule unchanged. "In plan" (entitled) = state computed WITHOUT the owner layer is not DISABLED.
- Owner-toggleable = manifest new optional field `"ownerToggleable"` (default `true`); set `false` in the manifests of `core`, `sync`, `admin`, `store`, `messaging`. Platform-category modules are never listed/toggleable.
- Registry: owner rows are read in the same lookup as module statuses (one indexed query per tenant lookup, request-scoped cache as today); `evict(schema)` now and after commit on every owner-row write.
- Owner toggle `PUT /api/v1/tenants/me/modules/{moduleId}/activation` body `{"activated": true|false}`, OWNER only, `@EntitlementExempt("core: Mes modules")`; serialized per tenant (transaction-scoped advisory lock on the tenant id); dependency rules use manifest `requires`: disabling X refused while an activated, in-plan module requires X; activating X refused while a module X requires is owner-disabled. No-op (same value) ⇒ 200, no audit. Change ⇒ upsert row, publish `ModuleOwnerActivationChangedEvent(tenantId, moduleId, activated, actorId)` audited `MODULE_ENABLED_BY_OWNER` / `MODULE_DISABLED_BY_OWNER` with the module display name (French labels in `AuditLabels`). Never raises `ModuleLicencesChangedEvent`, never purges data, never calls `onDeactivate` hooks.
- REST write/read on an owner-disabled module ⇒ 403 `MODULE_DISABLED_BY_OWNER` « Ce module est désactivé : le propriétaire peut l'activer dans Paramètres › Mes modules » (the aspect asks the registry for the reason only when the state is DISABLED). Sync push keeps `REJECTED` / `MODULE_INACTIVE` (V1-safe).
- `GET /api/v1/tenants/me/modules`: `data` stays the same list; each `TenantModuleDto` gains `inPlan` (boolean), `activated` (owner choice, true when no row), `ownerToggleable` (boolean); `meta = {"navigationPreset": "CAISSE"|"DOCUMENT"}` (POS→CAISSE). `GET /api/v1/tenant/preferences` gains `navigationPreset` as LAST field. Preset read through an `identity.api` query (extend `TenantPreferencesView` or a small port), never by SQL from `shared.module`.
- Go-live backfill = public Flyway `V50`: create the tables; `INSERT INTO public.tenant_module_activation SELECT <every existing tenant>, 'invoicing', false … ON CONFLICT DO NOTHING` (set-based, one statement) and `INSERT INTO public.profile_modules('FREE','invoicing',true) ON CONFLICT DO NOTHING` in the SAME migration, so no FREE tenant ever sees invoicing enabled. Every other current module existed in V1 ⇒ stays activated (no row).
- Activation rules (data, ADR-05): `public.module_activation_rule_sets(id UUID PK, sale_flow VARCHAR(10) NOT NULL CHECK IN ('POS','DOCUMENT'), sector_type VARCHAR(30) NULL, updated_at, UNIQUE NULLS NOT DISTINCT (sale_flow, sector_type))` + `public.module_activation_rule_modules(rule_set_id FK ON DELETE CASCADE, module_id, PRIMARY KEY(rule_set_id, module_id))` listing modules DISABLED at start. Resolution: (flow, sector) set if it exists, else (flow, NULL). Seeds: (POS, NULL) = none; (DOCUMENT, NULL) = `pos, inventory, counting, multi-store` (dependency-closed).
- `ModuleActivationRulesPort.applyInitialRules(schema)` (shared.module api): reads the tenant's flow + sector, inserts `activated=false` rows for the resolved set's modules with ONE `INSERT … SELECT … ON CONFLICT DO NOTHING`, evicts. Called by a listener on `OnboardingCompletedEvent` (after commit) and by 24.2 after setting DOCUMENT.
- `POST /api/v1/onboarding/complete` accepts optional `defaultSaleFlow` (`POS`|`DOCUMENT`, absent ⇒ unchanged/POS, other ⇒ 422 « Parcours de vente inconnu : choisissez Caisse ou Documents »), saved before the event.
- Super-admin: `GET /api/v1/admin/module-activation-rules` (all sets), `PUT /api/v1/admin/module-activation-rules` `{saleFlow, sectorType|null, disabledModules:[...]}` (upsert, replaces the module list), `DELETE /api/v1/admin/module-activation-rules?saleFlow=&sectorType=` (sector sets only; the two defaults are not deletable → 409). Validation 422 `MODULE_ACTIVATION_RULE_INVALID` with French reason: unknown/non-toggleable module, unknown sector, list not dependency-closed (a disabled module's activated dependents). `requireSuperAdmin`, `[ADMIN AUDIT]` log like `BundleAdminService`. Rules never touch existing tenants.
- Close the "runs nothing" gaps for owner-disabled `pos` / `multi-store`: gate `DayClosureAutoScheduler` per tenant on `pos`, add `@ModuleScoped("pos")` to `DayClosureNotificationListener`, `DayClosureWhatsAppListener`, `SaleDraftValidatedNotificationListener`, `@ModuleScoped("multi-store")` to `TransferCreatedNotificationListener`.
- Admin `TenantModuleDto` gains `ownerActivated` (additive).

**Never:**
- No new `tenant_modules` origin, no change to plan sync / expiry / admin lifecycle semantics, no data purge, no reseed. No Bref equivalence logic or import (24.2), no RN/Flutter UI, no change to V1 response fields (additive only). Sale-flow changes after onboarding do NOT re-apply rules. No hard-coded per-plan or per-sector module lists in Java.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| List | any member `GET /tenants/me/modules` | list with `inPlan/activated/ownerToggleable`; `meta.navigationPreset` | — |
| Disable | OWNER PUT `invoicing` `{activated:false}` | 200 module dto `activated:false,state:DISABLED`; audit row; invoice REST 403 `MODULE_DISABLED_BY_OWNER`; POS sale creates no invoice | — |
| Re-enable | OWNER PUT `{activated:true}` | state back to plan state; data intact | — |
| Same value | PUT current value | 200, no audit | — |
| Not in plan | FREE OWNER PUT `exports` `{activated:true}` | — | 409 `MODULE_NOT_IN_PLAN` « Ce module n'est pas compris dans votre plan : voyez Mon plan » |
| Not toggleable | PUT `core`/`sync`/`store`/`messaging` | — | 409 `MODULE_NOT_TOGGLEABLE` « Ce module ne peut pas être désactivé » |
| Unknown module | PUT `foo` | — | 404 `MODULE_NOT_FOUND` |
| Dependents | disable `inventory` while `pos` activated | — | 409 `MODULE_HAS_DEPENDENTS` « Désactivez d'abord : Caisse » (display names) |
| Requirement off | activate `pos` while `inventory` owner-disabled | — | 409 `MODULE_REQUIREMENT_DISABLED` « Activez d'abord : Stock » |
| Non-owner | MANAGER/EMPLOYEE/VIEWER PUT | — | 403 `FORBIDDEN` |
| Body | missing/null `activated` | — | 422 `VALIDATION_ERROR` « Indiquez si le module est activé » |
| Go-live | existing FREE tenant after V50 | invoicing `inPlan:true, activated:false, state:DISABLED`; others unchanged | — |
| Onboarding DOCUMENT | complete `{sectorType, storeName, defaultSaleFlow:"DOCUMENT"}` | preset DOCUMENT; pos/inventory/counting/multi-store `activated:false` | — |
| Plan change | owner-disabled `exports`, plan TEAM→PRO→TEAM | stays `activated:false` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleStateRules.java:54-131` -- effective state; add owner layer after the existing result.
- `.../shared/module/application/ModuleRegistryService.java:35,110-142` -- lookup (statuses + plan), `evict`; fetch owner rows in the same lookup; expose `ownerDisabled(schema, moduleId)` / per-module owner flag from the cached lookup.
- `.../shared/module/adapter/out/persistence/JdbcTenantModuleStateStore.java:73-89` -- FIND_STATUSES pattern to extend/sibling.
- `.../shared/module/ModuleManifest.java:35-130` + `src/main/resources/keevo/modules/<id>/module.json` -- add optional `ownerToggleable` (default true); false for core, sync, admin, store, messaging.
- `.../shared/module/adapter/in/rest/TenantModulesController.java:39-90` (`TenantModuleDto` :62) -- additive fields, `meta`, new PUT route; pinned by `src/test/.../shared/module/adapter/in/rest/TenantModulesControllerTest.java:76-101`.
- `.../shared/infrastructure/web/ApiResponseWrapper.java:20-28` -- `meta` field (NON_NULL).
- `.../shared/module/EntitlementAspect.java` + `ModuleEntitlementService.java:68-70` -- raise `MODULE_DISABLED_BY_OWNER` when DISABLED by owner.
- `.../shared/module/application/ModuleLifecycleService.java:310,~900-924` -- dependency/core checks and audit patterns to mirror (do not route owner toggles through it).
- `.../shared/module/application/PlanModuleRowSyncService.java:110-159` -- untouched; `evictAfterCommit` pattern.
- `.../shared/module/application/BundleAdminService.java:80-108`, `.../admin/module/adapter/in/rest/BundleAdminController.java` -- super-admin CRUD pattern (Map body, unknown keys refused, `requireSuperAdmin`, `[ADMIN AUDIT]`).
- `.../admin/module/adapter/in/rest/dto/TenantModuleDto.java` -- add `ownerActivated`.
- `.../identity/onboarding/adapter/in/rest/OnboardingController.java:67` + `OnboardingRequest` + `OnboardingService.complete:58-110` -- optional `defaultSaleFlow`; save via the 19.12 sale-flow path before publishing `OnboardingCompletedEvent` (`shared/event/catalog/onboarding/OnboardingCompletedEvent.java`).
- `.../identity/api/TenantPreferencesView.java`, `identity/api/SaleFlow.java`, `TenantPreferencesResponseDto.java:33` -- preset source and `navigationPreset` last field.
- `.../shared/infrastructure/web/AuditEventListener.java:607-620` (SaleFlowChangedEvent pattern), `.../shared/infrastructure/export/AuditLabels.java:36,97-98,237` -- new actions; do not reuse `MODULE_ACTIVATED/DEACTIVATED`.
- `.../shared/domain/exception/ErrorCode.java`, `ErrorMessages.java`, `GlobalExceptionHandler.java:249-275,372-374` -- new codes (403 / 409 / 422 / 404 mapping).
- `.../shared/package-info.java:10-13` -- shared may depend only on `identity :: api`.
- Gaps: `DayClosureAutoScheduler`, `DayClosureNotificationListener`, `DayClosureWhatsAppListener`, `SaleDraftValidatedNotificationListener`, `TransferCreatedNotificationListener`; `@ModuleScoped` via `ModuleScopedListenerInterceptor.java:127`.
- `.../invoicing/invoice/adapter/in/event/SaleInvoicingListener.java:35,58-70` -- already gated; covered by a test with owner-disabled invoicing.
- `src/main/resources/db/migration/V50__module_owner_activation.sql` (new), V33 `profile_modules(plan_type, module_id, granted)`, V43 FREE plan code.
- Tests to update: `TenantModulesControllerTest`, `EndpointEntitlementCoverageTest`, `ViewerAllowListPinTest`, `ApplicationModulesBaselineTest`, `PlanCodeLiteralGateTest` (no FREE literal in Java), `ModuleRoutesContractFileTest` (unchanged), `keevo/contracts` tenant-shape json if it pins the modules response.

## Tasks & Acceptance

**Execution:**
- `db/migration/V50__module_owner_activation.sql` -- tables, seeds (two default rule sets), backfill invoicing=false for every existing tenant, FREE invoicing grant; idempotent (`IF NOT EXISTS`, `ON CONFLICT DO NOTHING`), `SET LOCAL lock_timeout='10s'`.
- manifests + `ModuleManifest` -- `ownerToggleable`.
- `shared/module` -- owner-activation store (JDBC), state rule + registry, `OwnerModuleActivationService` (toggle, lock, dependency checks, event), `ModuleActivationRulesPort` + JDBC rules store + service (resolution, apply), onboarding listener, admin rules service.
- `TenantModulesController` -- dto fields, meta preset, PUT route; admin rules controller under `admin/module/adapter/in/rest/`.
- `identity` -- `navigationPreset` in preferences + view/port; onboarding `defaultSaleFlow`.
- Entitlement aspect -- `MODULE_DISABLED_BY_OWNER`; error codes/messages/handler.
- Audit -- event, listener, labels.
- Gap listeners/scheduler gating.
- `docs/entitlements/endpoints.md` -- new endpoints; `db/modules/README.md` -- rule: a module introduced after go-live ships a public migration inserting `activated=false` for existing tenants.
- Tests: unit (`ModuleStateRules` owner layer incl. READ_ONLY→DISABLED and not-in-plan unaffected; toggle service every matrix row; rules resolution + validation); controller (list shape incl. meta, PUT matrix, roles); full-context Postgres test: V50 backfill on a FREE tenant (invoicing DISABLED, POS sale creates no invoice, enable → sale invoiced), plan change keeps owner choice, onboarding DOCUMENT applies rules, owner-disabled `pos` blocks REST 403 `MODULE_DISABLED_BY_OWNER` and sync push `REJECTED/MODULE_INACTIVE`, admin rules CRUD.

**Acceptance Criteria:**
- Given an existing tenant migrated by V50, when it lists modules, then every module that existed in V1 keeps its previous state and `invoicing` is `activated:false` (FREE: `inPlan:true`).
- Given an owner-disabled module, when its scheduled job, listener or REST endpoint would run for that tenant, then nothing runs/writes and no notification is sent.
- Given a super-admin edits the DOCUMENT rule set, when a new tenant completes onboarding with DOCUMENT, then exactly the edited list is disabled; existing tenants are untouched.

## Spec Change Log

## Review Triage Log

## Design Notes

**Pattern.** Decorator/Specification over the existing effective-state rule: entitlement stays computed exactly as today; the owner layer is a separate, orthogonal specification combined in `ModuleStateRules` ("usable = entitled ∧ activated"). The architecture note says owner deactivation "reuses `DISABLED` with an owner origin": the effective state IS `DISABLED` and the API reports it as owner-disabled, but the owner choice is stored apart from `tenant_modules`, because a row there would be overwritten by plan sync / expiry or would freeze the module against later plan changes (investigated: `PlanModuleRowSyncService.java:125`, `JdbcModuleExpiryStore.java:38-44`). Rules = Strategy-by-data (most specific rule set wins). Toggle = Command with per-tenant advisory lock.

**Complexity.** Request path: +0 queries when owner rows are fetched in the existing lookup round trip (at most +1 PK-prefix query per tenant lookup, request-scoped cache). Toggle: lock + 1 read of the tenant's owner rows + 1 upsert. V50 backfill: 1 `INSERT … SELECT` over tenants. Apply rules: 1 `INSERT … SELECT`. Admin: O(rule sets) reads, no tenant loops. No per-tenant loops anywhere.

**Sync pull after re-enable.** While a module is owner-disabled its writes are refused on every path and its listeners/jobs are skipped, so no server-side rows change for it; nothing needs replay when it is re-enabled. Devices learn changes by re-fetching `/tenants/me/modules` (login, resume > 60 s, network recovery, « Mes modules ») and on a `MODULE_INACTIVE` rejection.

**Bref suppliers.** Bref owners must not see suppliers, but suppliers live in `crm` with clients; handled by 24.2 (routed note) rather than splitting `crm` here.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/shared/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then admin, architecture, identity, invoicing, pos-owning packages one at a time (full suite package by package before finalizing).

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off): `GET /tenants/me/modules` on a FREE dev tenant shows invoicing `activated:false`; PUT toggles; invoice endpoint 403 `MODULE_DISABLED_BY_OWNER`; onboarding with DOCUMENT; admin rules GET/PUT; `psql` rows in `public.tenant_module_activation`.
