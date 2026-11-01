---
title: '16-8 Plan profiles and configurable limits (plan_profiles + profile_modules), read live by the guard and the plan sync'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: '6ea22538e5d4d287849dc4890c14b95ac50555a4'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-5-migration-backfill-tenant-modules.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Plan limits are hardcoded in `PlanType` (`FREE(1, 500, 3)`, premium = `Integer.MAX_VALUE`), so changing the commercial offer needs a redeploy; the validated Free employee limit is 2, not 3 (journal I14); `GET /subscription/me` reports the `subscriptions.max_*` columns while the guard reads the enum; the app hardcodes `kFreePlanLimits`; the product count fails open; and which paid modules a plan includes is hardwired ("every paid module" for PREMIUM/PREMIUM_TRIAL, none for FREE) (MOD-FR13b).

**Approach:** Persist plan profiles in `public.plan_profiles` (limits per plan type) and `public.profile_modules` (paid modules granted by each plan type), seeded with the validated defaults; make the limit guard, `GET /subscription/me`, the plan-derived module rows (16.5 `PlanModuleRows`) and the no-row fallback read them live; expose super-admin endpoints to read/edit a profile, re-syncing the PLAN rows of affected tenants when the composition changes; make the product count fail closed; let the app show the limit the server sends.

## Boundaries & Constraints

**Always:**
- Seed (Flyway `public` V33): `FREE` = 1 store / 500 products / **2 employees**, no paid module; `PREMIUM_TRIAL` and `PREMIUM` = unlimited (NULL) for the three limits, every paid module installed today (`counting`, `exports`, `profitability`, `alerts`, `report-config`, `invoicing`, `multi-store`, `advanced-reports`). Free (manifest `free: true`) and core modules are always available to every plan and are not listed in `profile_modules`.
- A limit is either a positive integer or NULL (= unlimited). A change takes effect on the next check, with no restart and no cache that outlives a request.
- Limits source of truth = `plan_profiles`. `PlanType` keeps its constants only as the fallback used when the profile row cannot be read (log ERROR); `PlanLimitGuard`, `PlanView.max*`, `ImportCsvProductsUseCase` quota and `GET /subscription/me` read the profile. Subscription writers keep filling `subscriptions.max_*` / `tenants.max_*` from the profile (legacy columns, no longer read for decisions).
- Existing tenants already above a lowered limit keep their data and records; only new creations/reactivations are refused (`PLAN_LIMIT_EXCEEDED` with `limit` and `current` in details, as today).
- Product count fails closed: when the count cannot be read for a tenant whose product limit is finite, the creation is refused with a French 503 (« Impossible de vérifier votre quota de produits pour le moment. Réessayez dans quelques instants. »); unlimited plans never count.
- Plan-derived module rows: for plan type P, a paid module granted by P's profile follows the 16.5 mapping (TRIAL/ACTIVE/EXPIRED/DISABLED); a paid module granted to `FREE` gets `ACTIVE`, tier `FREE`, no expiry; a paid module not granted by P's profile gets `DISABLED` (PREMIUM/PREMIUM_TRIAL) or the existing FREE mapping (EXPIRED in post-expiry grace, else DISABLED). `ModuleStateRules.planState` (tenants without rows) gives the same state for the same plan + profiles (parity property test). ADMIN and SYSTEM rows are never touched (16.5).
- Super-admin API (`/api/v1/admin/plan-profiles`, `@EntitlementExempt("super-admin")`, `CurrentActor.requireSuperAdmin()`): `GET` list (plan type, French label, limits, granted modules with display names); `PUT /{planType}` with `maxStores`, `maxProducts`, `maxEmployees` (positive or null) and `modules` (ids of installed **paid** modules only). Validation errors → 422 with French messages naming modules by display name. A composition change re-syncs the PLAN rows of every live tenant whose subscription plan type is P, in the request, and returns the per-tenant counts (same report shape as the 16.5 backfill, failures by tenant code). Each change logged `[ADMIN AUDIT]` with actor, plan type, before/after.
- App: `plan_limit_bottom_sheet.dart` shows the limit from the `PLAN_LIMIT_EXCEEDED` details (or the subscription info) — no hardcoded numbers; when no number is known, a sentence without a number. Bump `keevo/app/pubspec.yaml`.

**Never:**
- No dashboard page in this story (the « Profils & plafonds » console section belongs to 16.4 — route the item there).
- Do not delete or modify tenant data, nor existing rows above a lowered limit.
- Do not let a profile list free/core/platform modules, nor make a free module paid (manifest `free` flag stays the only free/paid switch).
- No change to the 16.1 lifecycle job or notifications beyond what the sync already publishes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Seed | fresh DB | FREE 1/500/2, premium NULL; premium profiles list the 8 paid modules | — |
| Raise Free stores | PUT FREE maxStores 2; Free tenant with 1 store creates a 2nd | allowed without restart | — |
| Lower Free employees | Free tenant with 3 active employees, limit 2 | the 3 stay; creating or reactivating a 4th/3rd refused `PLAN_LIMIT_EXCEEDED` limit 2 | French message |
| Grant exports to Free | PUT FREE modules [exports] | every live FREE tenant: `exports` PLAN row ACTIVE/FREE, state ENABLED; report counts | — |
| Remove invoicing from Premium | PUT PREMIUM without invoicing | PREMIUM tenants' PLAN `invoicing` rows DISABLED (data kept, 16.1 message published by the sync); ADMIN invoicing rows untouched | — |
| Invalid module | PUT with `crm` or unknown id | 422, nothing saved | French message |
| Invalid limit | maxProducts 0 or -1 | 422 | French message |
| Count unreadable | product count throws for a Free tenant | product creation refused 503 | French message |
| Premium | PREMIUM tenant creates its 600th product | allowed, no count query | — |
| Profile row missing | `plan_profiles` row deleted | guard falls back to the enum values, ERROR logged | — |
| App limit sheet | 403 PLAN_LIMIT_EXCEEDED limit 2 employees | sheet says 2, not 3 | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/api/PlanType.java:11-29` -- enum limits (`FREE(1,500,3)`), keep as fallback only.
- `.../subscription/plan/application/service/PlanLimitGuard.java:33-61` -- checks read the enum; `PlanLimitGuardTest` asserts 3 employees.
- `.../subscription/plan/adapter/in/api/SubscriptionPlanAdapter.java:33-61` -- implements `TenantPlanQuery` + `PlanLimitCheck`, builds `PlanView`; `.../shared/capability/entitlement/PlanView.java:34-44` (`max*` → enum, javadoc "until 16.8").
- Enforcement callers (unchanged signatures): `CreateStoreService:64`, `ReactivateStoreService:64`, `StoreApiAdapter:106`, `CreateEmployeeService:94`, `ReactivateEmployeeService:89`, `CreateProductUseCase:137`, `CreateDraftProductUseCase:117`, `QuickAddProductService:116`, `ImportCsvProductsUseCase:157-187` (`plan.maxProducts()`).
- `.../catalog/product/adapter/out/persistence/ProductCountAdapter.java:48-60` -- fail-open (returns 0); `StoreCountAdapter:31`, `EmployeeCountAdapter:25` throw on error.
- Column writers: `TenantSchemaProvisioner.java:985`, `ActivatePlanService.java:124-126`, `ChangeTenantPlanService.java:44-45,74`, `SubscriptionExpiryService.java:72,146-148`, `TenantRepositoryAdapter.java:76-78`.
- `.../subscription/plan/application/service/GetSubscriptionService.java:45-66` -- `/subscription/me` limits from columns (null when not FREE); DTO `SubscriptionResponse.java:13-15`.
- `.../shared/module/application/PlanModuleRows.java:67,76,116` -- `planDerived`, `rowFor`, `compute`; `ModuleStateRules.java:43-117` -- `stateOf` no-row branch + `planState`; `PlanModuleRowSyncService` / `JdbcPlanModuleRowStore` (16.5); backfill loop and report in `ModuleBackfillService`.
- Admin patterns: `.../admin/subscription/adapter/in/rest/AdminTenantPlanController.java:58-61`, `.../admin/module/adapter/in/rest/ModuleAdminController.java:48-50`, `[ADMIN AUDIT]` logs in `ModuleLifecycleService.java:239,413`.
- App: `keevo/app/lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart:10-44` (`kFreePlanLimits`), callers `team/presentation/page/create_employee_page.dart:116-120`, `team/presentation/widget/employee_card.dart:183`, `stores/presentation/page/stores_list_page.dart:102`, `inventory/presentation/widget/quick_add_product_sheet.dart:342`; `subscription_page.dart:103-119`.
- Next migration: `keevo/backend/src/main/resources/db/migration/V33__plan_profiles.sql`. Test bases: `PostgresIntegrationTest`, `FullContextIntegrationTest`; model `ModuleBackfillPostgresIntegrationTest`.

## Tasks & Acceptance

**Execution:**
- `V33__plan_profiles.sql` -- tables (`plan_profiles(plan_type PK CHECK IN ('FREE','PREMIUM_TRIAL','PREMIUM'), max_stores, max_products, max_employees CHECK > 0 or NULL, updated_at, updated_by)`, `profile_modules(plan_type FK, module_id, PK(plan_type, module_id))`) + seed.
- `.../subscription/plan/...` (or `shared/capability/entitlement`) `PlanProfiles` port + JDBC adapter -- read limits/composition per call; enum fallback with ERROR log.
- `PlanLimitGuard`, `SubscriptionPlanAdapter`, `PlanView`, `ImportCsvProductsUseCase` -- limits from profiles; unlimited → no count.
- `ProductCountAdapter` -- fail closed (throw), mapped to the French 503 at the guard for finite limits.
- Column writers -- fill from the profile; `GetSubscriptionService` -- limits from the profile (null = unlimited).
- `PlanModuleRows`, `ModuleStateRules`, `PlanModuleRowSyncService` -- profile composition; parity property test updated.
- `.../admin/plan/adapter/in/rest/AdminPlanProfileController.java` + service -- GET/PUT, validation, re-sync of affected tenants, `[ADMIN AUDIT]` log; `ErrorCode`/`GlobalExceptionHandler` French messages if new codes are needed.
- App -- `plan_limit_bottom_sheet.dart` and its callers use the server limit; tests; bump `pubspec.yaml`.
- Tests -- `PlanLimitGuardTest` (2 employees, profile-driven, unlimited), `PlanProfilePostgresIntegrationTest` (seed, live change without restart through the real create services, lowered limit keeps existing records, missing row fallback), `ModuleBackfillPostgresIntegrationTest`/`PlanModuleRowsTest` (composition), admin controller tests (403 non-super-admin, 422s, re-sync report), `ProductCountAdapter` fail-closed test, app widget test of the sheet.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- 16.8 items ticked; add to 16.4 a routed item « Profils & plafonds » console section (GET/PUT `/api/v1/admin/plan-profiles`); journal I14 resolved note.

**Acceptance Criteria:**
- Given keevo_dev running, when the super-admin raises the Free store limit to 2 through the API, then a Free tenant can create a second store immediately (no restart), and after setting it back to 1 a third creation is refused with the French limit message.
- Given the Redmi on a Free test tenant with 2 active employees, when the owner tries to add a third, then the sheet shows « 2 » as the limit.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 33 findings — high 1, medium 9, low 15, false 4, maybe-false 4
- findings:
  - `[low]` `[reject]` (intent) Console « Profils & plafonds » UI not delivered — spec Never clause; routed to 16.4 with the API contract.
  - `[low]` `[reject]` (intent) Free modules not stored in `profile_modules` — spec decision (manifest `free` flag is the only free/paid switch).
  - `[false]` `[reject]` (intent) Enum still a source — kept only as logged fallback, allowed by the epic note.
  - `[medium]` `[patch]` (intent, blind) Employees/products only unit-tested through the live profile — `employeeLimitLowered` (create + reactivate refused, 3 kept) and product use-case tests added.
  - `[false]` `[reject]` (intent) Manual ACs not evidenced — run by the implementer (keevo_dev store limit live change; Redmi sheet « limite de 2 employés »), recorded below.
  - `[low]` `[reject]` (intent) Downgrade reads limits only through the guard — legacy columns no longer drive decisions, by design.
  - `[medium]` `[patch]` (edge, blind, verification-gap other) Profile read on the caller's transaction aborts it on SQL fault — read on its own connection (3 s timeout), message logged.
  - `[medium]` `[patch]` (edge) Re-sync reports unreadable plans of other plan types — resync selects tenants by `tenants.plan_type` first.
  - `[high]` `[patch]` (edge, blind) A paid module shipped after V33 would be DISABLED for every Premium tenant — `profile_modules.granted` records explicit decisions; no row = granted to Premium/trial, refused to Free; tested.
  - `[low]` `[patch]` (edge, blind) CSV import message and quota from two reads — guard enforces the `PlanView` limits; one source.
  - `[low]` `[patch]` (edge, blind) `parsePlanType` case-sensitive — case-folded.
  - `[medium]` `[patch]` (verification-gap) Employee page test passed through the string fallback — DioException with body only; string fallback removed (`isPlanLimitError`).
  - `[medium]` `[patch]` (verification-gap) Fail-closed count only tested in the guard — draft + CSV use-case tests (503, nothing saved; Premium never counts).
  - `[medium]` `[patch]` (verification-gap) Registry rowless profile path untested — `ModuleRegistryProfileTest`.
  - `[low]` `[patch]` (verification-gap other) `knownPlanLimit` fallback untested — tests added.
  - `[medium]` `[patch]` (blind) Omitted/misspelled limit key silently makes the plan unlimited — all three keys required, unknown keys 422.
  - `[low]` `[patch]` (blind) Two profile reads per creation — fixed with the PlanView entry above.
  - `[low]` `[patch]` (blind) Fail-open eager overloads still public — removed.
  - `[low]` `[patch]` (blind) No `fallback` flag in the admin view — added.
  - `[medium]` `[defer]` (blind, dev run) Composition re-sync and notifications run synchronously in the PUT (~2 min for 282 tenants) — routed to 16.4 (async job + async/batched notifications).
  - `[medium]` `[patch]` (blind) App offers « Passer au plan Premium » to a Premium tenant and promises « illimités » — CTA only for Free/trial, support wording otherwise; copy changed.
  - `[low]` `[patch]` (blind) `/subscription/me` shows an unreadable count as 0 — null, app shows « — ».
  - `[maybe-false]` `[reject]` (blind) Spec scenarios without tests — covered by the added tests above (if true, only low).
  - `[low]` `[patch]` (blind) `stores_list_page` keeps a stale limit — reset after the sheet.
  - `[low]` `[patch]` (blind) Plan-limit detection written three times — `isPlanLimitError` shared helper.
  - `[low]` `[reject]` (blind) Unknown-module 422 does not name the id — ids are technical data (no-technical-data rule); display names are given for known modules.
  - `[low]` `[reject]` (blind) Audit logged when before == after — harmless.
  - `[low]` `[reject]` (blind) Confusing ticked-item wording in the epics doc — cosmetic.
  - `[maybe-false]` `[reject]` (edge) Missing-row fallback for premium shows all paid modules — now flagged `fallback: true` in GET.
  - `[false]` `[reject]` (verification-gap duplicate) — grouped with the profile-read entry.
  - `[false]` `[reject]` (blind duplicate of re-sync scope) — grouped with the resync entry.
  - `[maybe-false]` `[reject]` (blind) Notification storm on keevo_dev — verified in logs: all 179 logged Wassender calls answered 401, no successful send, no FCM token; memory rule added (local backend runs with WhatsApp noop + FCM off).
  - `[maybe-false]` `[reject]` (edge) Concurrent edit between view and guard — single read now.

## Design Notes

Free and core modules stay outside profiles on purpose: the manifest `free` flag is what makes a module free for everyone (16.5, 16.1 rules); a profile only grants paid modules. Granting a paid module to FREE writes an `ACTIVE`/`FREE` PLAN row without expiry, so the 16.1 job never expires it, and removing it later turns the row `DISABLED` through the same sync.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.subscription.**'` then `'com.keevo.shared.module.**'`, `'com.keevo.admin.**'`, `'com.keevo.catalog.**'`, `'com.keevo.store.**'`, `'com.keevo.identity.**'`, `'com.keevo.inventory.**'`, `'com.keevo.shared.infrastructure.**'`, `'com.keevo.architecture.**'` -- expected: green, one package at a time
- `cd keevo/app && flutter analyze && flutter test --concurrency=4 test/features/settings` then `test/features/team`, `test/features/stores`, `test/features/inventory` -- expected: green

**Manual checks:**
- keevo_dev + API AC above; Redmi AC (debug build installs as `com.example.keevo`; local backend on 4500 with `adb reverse tcp:4500 tcp:4500`; never open share sheets / wa.me; restore test data and stop the backend with `fuser -k 4500/tcp` afterwards).

## Auto Run Result

Status: done

**Summary:** plan limits and the paid modules each plan grants now live in `public.plan_profiles` / `public.profile_modules` (V33, explicit granted/refused decisions; a paid module without a decision is granted to Premium/trial and refused to Free). The limit guard, `/subscription/me`, the plan-derived module rows and the no-row fallback read them live (own connection, no cache); `PlanType` constants are only a logged fallback. Free is now 1 store / 500 products / 2 employees. Super-admin `GET`/`PUT /api/v1/admin/plan-profiles[/{planType}]` (all limit keys required, French 422s, re-sync of the plan's tenants on a composition change, `[ADMIN AUDIT]`). Product count fails closed (French 503). App 2.0.19+91: limit sheet shows the server's limit, upgrade CTA only for Free/trial.

**Review:** 33 findings — 20 patched (1 high, 8 medium, 11 low entries), 1 deferred (async re-sync → 16.4), 12 rejected with reasons. Follow-up review recommended: true — a high entry and several medium entries were patched on a first pass; named risk: the explicit-decision model of `profile_modules` (granted flag + default per plan type) and the PUT map parsing, covered by unit/integration tests but not re-reviewed.

**Verification:** backend packages one at a time, all green — subscription 83, shared.module 412, admin 529, catalog 251, store 60, identity 448, inventory 376, shared.infrastructure 606, sync 491, architecture 32, invoicing 170. App: `flutter analyze` 0 errors/warnings; core 521, settings 120, team 128, stores 62, inventory 248 passed. keevo_dev (backend with WhatsApp noop, FCM off): V33 applied (FREE 1/500/2, premium 8 granted); `GET /admin/plan-profiles` 200 with `fallback=false`; PUT with a missing key or an unknown key → 422. Earlier implementer run: Free store limit raised to 2 → second store created without restart, back to 1 → third refused; Redmi: « limite de 2 employés » sheet.

**Incident during the implementer's dev run:** the backend ran with `.env`'s real Wassender provider while a composition change re-synced 282 Free tenants; every logged WhatsApp call got 401 (token rejected), no successful send in the logs, no FCM owner token. Memory rule added: local runs force WhatsApp noop and FCM off.

**Residual risks:** composition re-sync is synchronous (16.4); keevo_dev keeps test tenant KV-I3NPYM.
