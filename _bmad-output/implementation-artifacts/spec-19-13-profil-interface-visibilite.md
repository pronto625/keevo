---
title: '19.13 — Interface profile: owner activation of plan modules, navigation preset, new modules disabled'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: '4de03c49203bb66b6fe40921cc11736ff23586db'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred:
  - summary: >-
      Bref owner profile is keyed on the DOCUMENT flow, not on each owner's Bref plan; other in-plan modules stay activated.
    evidence: |-
      Entitlement already follows the plan; the Bref-equivalence list belongs to the importer. Routed to story 24.2 in epics-fusion-keevo-bref.md.
    location: >-
      keevo/backend/src/main/resources/db/migration/V50__module_owner_activation.sql
    severity: low
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

- 2026-11-05 (review): blind-hunter / edge-case finding — new FREE tenants on the (POS, NULL) default rule would get `invoicing` activated (automatic invoice for every POS sale, documents quota burnt). Amended the seed — this OVERRIDES the contract's « Seeds: (POS, NULL) = none » line (seeds are editable data, ADR-05; the contract text is left untouched as read-only): (POS, NULL) disables `invoicing`; (DOCUMENT, NULL) keeps `pos, inventory, counting, multi-store`. Avoids: FREE POS owners invoicing every sale without having chosen it. Applied as a one-line seed patch instead of a full re-derivation (the rest of the code is KEEP). Also: the owner layer propagates through manifest `requires` (a module whose requirement is owner-disabled is DISABLED). KEEP: separate `tenant_module_activation` table, lateral read in the status query, entitlement vs owner layer split, advisory lock per tenant, rules as data with most-specific-wins.

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 39 findings — high 0, medium 7, low 19, false 6, maybe-false 0 (+7 descriptive intent-alignment observations, triaged below)
- findings:
  - Patterns/complexity (FUS-NFR11):
    - `[low]` `[patch]` `ModuleLifecycleService` activation loop re-reads `entitledState` after each `evict` (k+1 lookups; loop pre-existing, line touched by this diff) — snapshot `entitledStates` once before the loop.
    - `[low]` `[reject]` `ModuleActivationRulesService.save` re-reads every rule set — super-admin path on a table of a handful of rows; not worth a new store contract.
    - `[low]` `[reject]` dependency-closure validation O(k·M·r) — M ≈ 18 manifests, admin-only path.
    - `[false]` `[reject]` `Object[]` accumulators in `JdbcModuleActivationRuleStore` — style; no harm named.
    - `[false]` `[reject]` `setActivation` rebuilds the module list — reviewer says no change required (2 queries, keeps the response identical to the list).
    - `[false]` `[reject]` `ModuleRegistry.disabledByOwner` default method builds 3 maps — only test doubles use it; the service overrides it.
    - `[low]` `[reject]` lateral array repeated per `tenant_modules` row / `findRows` pays for it — ~10 rows, negligible.
  - Intent alignment (descriptive):
    - `[false]` `[reject]` UI surfaces (menus, tabs, « Mes modules ») not exercised — project rule API-only; the mobile section carries the app requirements.
    - `[low]` `[reject]` « synchronisé vers les appareils » = devices re-fetch `/tenants/me/modules` (+ `MODULE_INACTIVE` rejection) — design note of the spec; the mobile section states when to re-fetch.
    - `[medium]` `[patch]` V50 backfill never executed by a test — same as the verification-gap finding below.
    - `[low]` `[defer]` Bref owner profile keyed on flow, not per Bref plan; other in-plan modules (e.g. exports) stay on — entitlement already follows the plan; the Bref-equivalence decision belongs to the importer → routed to 24.2 (with suppliers).
    - `[false]` `[reject]` storage differs from the note's wording (separate table instead of an OWNER origin) — deliberate, justified in Design Notes (plan sync / expiry would overwrite it); the effective state is DISABLED as the note requires.
    - `[false]` `[reject]` V1 sees `MODULE_DISABLED_BY_OWNER` instead of `MODULE_NOT_ENTITLED` on invoicing — V1 2.0.1 has no invoicing and never calls these endpoints.
    - `[medium]` `[patch]` new `@ModuleScoped` listener gates untested — same as the verification-gap finding below.
  - Blind hunter:
    - `[medium]` `[patch]` new FREE tenants on the POS default rule get invoicing activated, so every POS sale becomes an automatic invoice and burns the 300 documents/month quota — the harm V50 prevents for existing tenants — seed `invoicing` in the (POS, NULL) default set (spec amended, see change log); a new owner enables it in « Mes modules ».
    - `[medium]` `[patch]` `OnboardingModuleRulesListener` swallows failures: the Modulith publication completes and the rules are never applied — log and rethrow so the publication stays incomplete and is republished on restart (`republish-outstanding-events-on-restart: true`); the insert is idempotent.
    - `[low]` `[patch]` stored rule sets never re-checked against manifests — test that every seeded/stored default set passes the service validation (dependency-closed, toggleable).
    - `[low]` `[patch]` a sector set replaces the flow default entirely, undocumented — state « most specific set wins, no merge » in the admin API description and `ModuleActivationRulesAdmin` Javadoc.
    - `[medium]` `[patch]` the « new module ⇒ public migration owner-disabling it for existing tenants » rule is only a README convention — architecture test pinning the module ids known at V50; a new `module.json` fails it with a message pointing to the README rule.
    - `[low]` `[patch]` owner-toggle audit row has entity type `Module` with the tenant UUID as id — use entity type `Tenant`.
    - `[low]` `[patch]` `list()` Javadoc promises defaults first but SQL orders by flow then sector — fix the Javadoc to the actual order.
    - `[low]` `[patch]` blank `sectorType` silently targets the default set — 422 `MODULE_ACTIVATION_RULE_INVALID` « Secteur inconnu » for a blank string (null = default).
    - `[low]` `[reject]` admin `TenantModuleDto` lacks `inPlan` — new public surface; the console can read `state` + `ownerActivated`; not needed by the intent.
    - `[low]` `[patch]` `DayClosureAutoScheduler` counts skipped tenants as processed and its comment says « owner » while it skips any DISABLED pos — count after the skip; comment « pos disabled (owner or entitlement) ». Skipping on any DISABLED pos is the intended « runs nothing ».
    - `[medium]` `[patch]` missing tests: rules listener failure path, `TenantSaleFlowQueryAdapter`, annotation placement of the 4 gated listeners — add them (annotation pins in the `AlertsModuleScopedFullContextTest` style).
    - `[low]` `[reject]` super-admin rule edits only log `[ADMIN AUDIT]` — same as every super-admin catalogue edit (bundles, plan profiles); public schema has no audit table.
    - `[false]` `[reject]` patch omits `endpoints.md` — excluded from the review diff on purpose (generated); it is modified and committed with the change.
  - Verification gap:
    - `[medium]` `[patch]` V50 backfill + FREE grant never replayed on a pre-existing tenant — Postgres integration test re-running `V50__module_owner_activation.sql` (V41/V43 pattern): pre-existing tenant gets `invoicing=false`, an existing owner choice is kept, an edited DOCUMENT default is not reseeded.
    - `[medium]` `[patch]` lifecycle / dependency resolver switched to `entitledStates` with no owner-disabled case — tests: super-admin deactivate of an owner-disabled in-plan module writes the plan row; activate keeps `activated_at`; deactivating a requirement of an owner-disabled in-plan dependent → `MODULE_HAS_DEPENDENTS`; `FakeRegistry` overrides `entitledStates` separately.
    - `[medium]` `[patch]` the 4 new `@ModuleScoped` gates untested — grouped with the blind-hunter missing-tests entry.
    - `[low]` `[patch]` admin `ownerActivated` not asserted — `jsonPath` assertion in `ModuleAdminControllerTest`.
    - `[low]` `[patch]` manifest `ownerToggleable` type check untested — `ModuleManifestLoaderTest` cases (string → problem, absent → true).
    - `[medium]` `[patch]` listener swallows failures — grouped with the blind-hunter entry.
  - Edge case hunter:
    - `[medium]` `[patch]` new FREE POS tenants get invoicing — grouped with the blind-hunter entry.
    - `[medium]` `[patch]` a module can become usable while a module it requires is owner-disabled (owner disables `reporting` while `exports` is outside the plan; the plan later grants `exports`; or super-admin activation) — the owner layer propagates through `requires`: a module whose (transitive) requirement is owner-disabled is effectively DISABLED and `disabledByOwner` reports it as owner-caused; `activated` stays the owner's own choice.
    - `[medium]` `[patch]` same root as the previous row (dependent outside the plan when the requirement was disabled).
    - `[low]` `[reject]` rule validation message when a dependent is non-toggleable — no non-toggleable manifest requires a toggleable one (they all require only `core`); unreachable today.
    - `[medium]` `[patch]` rules listener log-and-drop — grouped with the blind-hunter entry.
    - `[low]` `[patch]` scheduler comment says owner but skips any DISABLED pos — grouped with the blind-hunter scheduler entry.

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

## Auto Run Result

Status: done

**Summary.** A module is now usable only when the plan includes it AND the owner has not disabled it. The owner's choice lives in `public.tenant_module_activation` (no row = activated), read in the same statement as module statuses and applied as a layer over the entitlement state (propagated through manifest `requires`), so every existing gate stops an owner-disabled module (REST 403 `MODULE_DISABLED_BY_OWNER`, sync push `REJECTED/MODULE_INACTIVE`, sync pull skip, `@ModuleScoped` listeners, scheduler gates; four more notification listeners and the day-closure auto scheduler now gated). `GET /tenants/me/modules` adds `inPlan`, `activated`, `ownerToggleable` and `meta.navigationPreset` (CAISSE/DOCUMENT from `default_sale_flow`); preferences add `navigationPreset`. Owner toggle `PUT /tenants/me/modules/{id}/activation` (locked per tenant, dependency rules, audited, never touches `tenant_modules`). V50: tables, go-live backfill (`invoicing` owner-disabled for every existing tenant) and the FREE `invoicing` grant in one migration. Initial activation rules are data (by sale flow, optional sector; most specific set wins) applied after onboarding (optional `defaultSaleFlow`) and via `ModuleActivationRulesPort` for 24.2; super-admin CRUD on `/api/v1/admin/module-activation-rules`.

**Files.** New: `V50__module_owner_activation.sql`; `OwnerModuleActivation(Service)`, `OwnerModuleActivationStore` + JDBC, `ModuleActivationRulesPort`/`Admin`/`Service`, `ModuleActivationRuleStore` + JDBC, `OnboardingModuleRulesListener`, `ModuleOwnerActivationChangedEvent`, `ModuleActivationRulesAdminController` + DTO, `TenantSaleFlowQueryPort` + adapter, `NavigationPreset`; tests (`InterfaceProfileFullContextTest`, `ModuleOwnerLayerTest`, `OwnerModuleActivationServiceTest`, `ModuleActivationRulesServiceTest`, `OnboardingModuleRulesListenerTest`, `ModuleOwnerActivationMigrationPostgresIntegrationTest`, `DefaultActivationRuleSetsPostgresIntegrationTest`, `TenantSaleFlowQueryAdapterPostgresIntegrationTest`, `OwnerGatedListenersPlacementTest`, `ModuleIdsAtGoLivePinTest`). Changed: `ModuleStateRules`, `ModuleRegistry(Service)`, `JdbcTenantModuleStateStore`, `ModuleEntitlementService`, `ModuleLifecycleService` + `DependencyResolverService` (plan-only state), `ModuleManifest`(+loader, `ownerToggleable`, 5 manifests), `TenantModulesController`, admin `TenantModuleDto` (`ownerActivated`), onboarding request/service, preferences DTO, `ApiResponseWrapper` (meta helper), error codes, audit labels/listener, 5 gated listeners/scheduler, `db/modules/README.md`, `docs/entitlements/endpoints.md`, export datasets contract.

**Review.** 39 findings (5 layers): 0 high, 7 medium, 19 low, 6 false (+7 intent observations). Patched: all 7 medium (new FREE POS tenants getting invoicing → POS default rule disables it; owner layer propagated through `requires`; onboarding rules listener rethrows so the publication is republished; V50 replay test; lifecycle/dependency tests with owner-disabled modules; listener annotation pins; new-module pin test) and 10 low. Deferred: 1 low → 24.2 (Bref-equivalence per Bref plan). Rejected: 12 (reasons in the triage log). Spec change log: the (POS, NULL) seed now disables `invoicing` (overrides the contract's seed line).

**Follow-up review: recommended (true)** — 7 medium patches; unverified risk: the requires-propagation of the owner layer and the POS-default `invoicing` seed change what new and existing tenants see, verified by tests and on `keevo_dev` but not yet on the RN app; the onboarding-rules republish path is unit-tested only.

**Verification.** Full suite package by package: 5 785 tests, 0 failures. Live `keevo_dev` (WhatsApp noop, FCM/Expo off) after V50: 362 tenants, 361 `invoicing` rows disabled (one re-enabled by the test owner), FREE grant present, rule sets POS→`invoicing`, DOCUMENT→`counting,inventory,multi-store,pos`. Existing tenant: list with `meta.navigationPreset` DOCUMENT, `invoicing` `inPlan:true, activated:false, DISABLED`; `GET /invoices` 403 `MODULE_DISABLED_BY_OWNER`; enable → 200 and `/invoices` 200; same value → 200 not audited; disable `inventory` while `pos` on → 409 `MODULE_HAS_DEPENDENTS`; `store` → 409 `MODULE_NOT_TOGGLEABLE`; `foo` → 404; `{}` → 422; `pos` while `inventory` off → 409 `MODULE_REQUIREMENT_DISABLED`; audit 4 `MODULE_DISABLED_BY_OWNER` + 5 `MODULE_ENABLED_BY_OWNER`. New tenant onboarded with `defaultSaleFlow:"DOCUMENT"` → preset DOCUMENT and pos/inventory/counting/multi-store DISABLED; `defaultSaleFlow:"FOO"` → 422. Super-admin rules API exercised by `InterfaceProfileFullContextTest` only (no super-admin login used live).

**Residual risks.** Devices learn an owner change only on re-fetch; existing `keevo_dev` FREE tenants holding a PLAN `DISABLED` invoicing row show `inPlan:false` until their next plan resync (prod tenants get their rows after V50, so they are not affected).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit 54aad8e1), branche `refonte/modulaire` / `develop`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** chaque module compris dans le plan peut être activé ou désactivé par le propriétaire pour toute l'entreprise ; un module désactivé ne fait plus rien (écritures refusées, pas de notification, pas de tâche planifiée) ; ses données sont gardées. L'API indique aussi le préréglage de navigation : Caisse ou Documents. À la mise en prod, la facturation est désactivée pour toutes les entreprises existantes ; une entreprise qui démarre en « Documents » a la caisse, le stock, l'inventaire et les transferts désactivés, une entreprise en « Caisse » a la facturation désactivée (règles modifiables par le super-admin).

**À utiliser :**
1. `GET /api/v1/tenants/me/modules` (tous les rôles) : `data` = liste des modules ; champs ajoutés `inPlan` (compris dans le plan), `activated` (choix du propriétaire), `ownerToggleable` (peut être changé dans « Mes modules ») ; `state` reste l'état effectif (`ENABLED` / `READ_ONLY` / `DISABLED`). `meta.navigationPreset` = `CAISSE` ou `DOCUMENT`. Code : `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java`.
2. `GET /api/v1/tenant/preferences` : nouveau dernier champ `navigationPreset`.
3. `PUT /api/v1/tenants/me/modules/{moduleId}/activation` (OWNER) corps `{"activated": true|false}` → 200 avec le module à jour. Erreurs :

| Code | HTTP | Message | Réaction de l'app |
|---|---|---|---|
| `MODULE_NOT_IN_PLAN` | 409 | « Ce module n'est pas compris dans votre plan : voyez Mon plan » | proposer « Mon plan » |
| `MODULE_NOT_TOGGLEABLE` | 409 | « Ce module ne peut pas être désactivé » | ne pas afficher d'interrupteur (`ownerToggleable:false`) |
| `MODULE_HAS_DEPENDENTS` | 409 | « Désactivez d'abord : Point de vente, … » | afficher le message |
| `MODULE_REQUIREMENT_DISABLED` | 409 | « Activez d'abord : Stock et inventaire » | afficher le message |
| `MODULE_NOT_FOUND` | 404 | « Module introuvable » | recharger la liste |
| `VALIDATION_ERROR` | 422 | « Indiquez si le module est activé » | — |
| `FORBIDDEN` | 403 | — | interrupteurs réservés au propriétaire |

4. Toute requête vers un module désactivé par le propriétaire → 403 `MODULE_DISABLED_BY_OWNER` « Ce module est désactivé : le propriétaire peut l'activer dans Paramètres › Mes modules » ; en synchro, l'opération revient `REJECTED` / `MODULE_INACTIVE`.
5. `POST /api/v1/onboarding/complete` accepte `defaultSaleFlow` (`POS` | `DOCUMENT`, facultatif, POS par défaut) : le choix fait à l'inscription fixe le préréglage et les modules activés au départ. Valeur inconnue → 422 « Parcours de vente inconnu : choisissez Caisse ou Documents ».

**À implémenter côté mobile :**
1. Navigation : n'afficher dans les menus, onglets, accueil et raccourcis que les modules `state` ≠ `DISABLED` (et permis par le rôle) ; ordre et écran d'accueil selon `navigationPreset` (Caisse → caisse en premier ; Documents → documents en premier).
2. Écran Paramètres › Mes modules (propriétaire) : un interrupteur par module `inPlan && ownerToggleable`, libellé `displayName` ; les modules `inPlan:false` renvoient vers « Mon plan » ; pas de bannière promotionnelle.
3. Rafraîchir `GET /tenants/me/modules` à la connexion, au choix d'entreprise, au retour au premier plan (> 60 s), au retour du réseau, après un changement d'interrupteur et à toute réponse `MODULE_DISABLED_BY_OWNER` / `MODULE_INACTIVE`.
4. Inscription : proposer « Caisse » ou « Documents » avec l'activité, envoyé dans `defaultSaleFlow`.
5. Hors ligne : garder la dernière liste en cache ; ne pas mettre d'opérations en file pour un module désactivé.

**Implémentation de référence :** `keevo/app/lib/core/entitlements/entitlements_provider.dart` (client de test Flutter : rafraîchissement de la liste).

**Critères d'acceptation mobile :**
- Given une entreprise existante, when l'app s'ouvre, then la facturation n'apparaît nulle part et « Mes modules » montre « Facturation » désactivée ; when le propriétaire l'active, then le menu Factures apparaît sans redémarrer.
- Given une inscription en « Documents », when l'accueil s'affiche, then ni caisse ni stock ne sont visibles.
- Given un employé, when le propriétaire désactive un module, then l'employé ne le voit plus au prochain rafraîchissement.
- Mise en place : `curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"activated":true}' $API/api/v1/tenants/me/modules/invoicing/activation`.

**Clé de suivi mobile :** `m19-13-profil-interface-visibilite`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit 54aad8e1).

**Ce qui est fait côté API :** les modules activés au départ pour une nouvelle entreprise sont des règles en base, par parcours de vente (Caisse / Documents) et, si besoin, par secteur ; la règle la plus précise l'emporte, sans fusion. La vue des modules d'une entreprise indique aussi le choix du propriétaire.

**À utiliser :**
1. `GET /api/v1/admin/module-activation-rules` → liste `{saleFlow, saleFlowLabel, sectorType (null = règle par défaut), disabledModules:[{moduleId, displayName}], defaultSet, updatedAt}`.
2. `PUT /api/v1/admin/module-activation-rules` corps `{saleFlow, sectorType, disabledModules:[ids]}` (trois clés obligatoires, `sectorType` null pour la règle par défaut) → 201 créée / 200 remplacée. 422 `MODULE_ACTIVATION_RULE_INVALID` avec la raison en français (module inconnu ou non désactivable, secteur inconnu ou vide, liste incomplète vis-à-vis des dépendances).
3. `DELETE /api/v1/admin/module-activation-rules?saleFlow=&sectorType=` → 204 ; la règle par défaut d'un parcours ne se supprime pas (409).
4. `GET /api/v1/admin/tenants/{tenantId}/modules` : champ ajouté `ownerActivated`.
Code : `keevo/backend/src/main/java/com/keevo/admin/module/adapter/in/rest/ModuleActivationRulesAdminController.java`.

**À implémenter côté dashboard :**
1. Écran « Modules au démarrage » : une carte par parcours (Caisse, Documents) et par secteur, cases à cocher des modules désactivés au départ (libellés `displayName`), message d'erreur 422 affiché tel quel ; rappeler qu'une règle de secteur remplace la règle par défaut et ne touche pas les entreprises existantes.
2. Fiche entreprise › Modules : afficher « Désactivé par le propriétaire » quand `ownerActivated:false`.

**Critères d'acceptation dashboard :**
- Given la règle Documents modifiée, when une entreprise s'inscrit en Documents, then exactement les modules cochés sont désactivés ; les entreprises existantes ne changent pas.

**Clé de suivi dashboard :** `d19-13-profil-interface-visibilite`.
