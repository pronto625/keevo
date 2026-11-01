---
title: '16-2 Bundles: named sets of modules applied in one activation plan through the à-la-carte engine'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: 'f6492009a76aca64ddbbc1bdf3ce69a0f6f77065'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-1-cycle-vie-licences-grace-temps-reel.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The super-admin can activate modules one by one (13.8 lifecycle, 16.1 rules) but cannot apply a commercial pack. Planning module by module also gives several activation plans whose dependency orders may conflict (deferred from 13-3) (MOD-FR12, decision D1: the engine stays 100 % à-la-carte).

**Approach:** Store bundles as named lists of module ids (`public.bundle_definitions` + `public.bundle_modules`), let the super-admin list/define them, and apply one to a tenant with `POST /api/v1/admin/tenants/{id}/bundles/{bundleId}`: one activation plan over the union of the bundle's dependency closures (`planActivation(schema, Set<String>)`, one topological order), executed by the same lifecycle code as a single activation, which becomes the one-element case.

## Boundaries & Constraints

**Always:**
- `DependencyResolverService.planActivation(schema, Set<String> targets)`: union of the closures, each module once, one topological order (dependencies first), same "already ENABLED = nothing to do / non-ENABLED dependency renewed" rules as 16.1. The single-module method delegates to it.
- `ModuleLifecycleService`: one activation entry point for a set of targets (single activation = set of one); same validations (status ACTIVE/TRIAL, expiry rules incl. the 16.1 14-day TRIAL default and "never shorten an ENABLED module"), same per-tenant lock, same provisioning, hooks, audit (`[ADMIN AUDIT]` + per-module audit entries) and `origin = 'ADMIN'` rows. Paid modules of the bundle get the request's status/expiry; free modules follow the free rule.
- All-or-nothing on rows: if planning or a validation fails, nothing is written; provisioning failures behave as for a single activation (module reported, rows not written for it and for its dependents).
- Bundle definitions: id = kebab-case slug, French display name, optional description, at least one module, only installed non-core, non-platform modules; unknown/duplicate ids → 422 French message naming modules by display name. Seed one example bundle `pro` « Pack Pro » = `pos`, `inventory`, `invoicing`, `crm` (Flyway `public` V34).
- Super-admin API under `/api/v1/admin` (`@EntitlementExempt("super-admin")`, `CurrentActor.requireSuperAdmin()`): `GET /bundles` (id, name, description, modules with display names), `PUT /bundles/{bundleId}` (create or replace; all fields required), `DELETE /bundles/{bundleId}` (removes the definition only; tenants keep their modules), `POST /tenants/{tenantId}/bundles/{bundleId}` with the same optional body as module activation (`status`, `expiresAt`), returning the activated modules (display names) and the already-active ones, like the single activation response.
- French messages everywhere, no technical ids in messages except where existing activation responses already return module ids.

**Never:**
- No dashboard UI (console section « Bundles » belongs to 16.4 — route it), no app change, no billing/payment.
- A bundle is never a stored state on the tenant: after application only per-module `tenant_modules` rows exist (D1).
- Do not change plan profiles (16.8) or the plan sync.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Apply Pack Pro | Free tenant, `invoicing` DISABLED, others free ENABLED | one plan; `invoicing` ADMIN row ACTIVE with the requested expiry; free ones already active → listed as already active | — |
| TRIAL no date | body `{status: TRIAL}` | paid modules not ENABLED get now+14 d; ENABLED paid modules unchanged | — |
| Shared dependency | bundle {counting, multi-store} both requiring `inventory` | `inventory` planned once, before both | — |
| Same as single | bundle with one module vs single activation of it | identical rows, audit and response | — |
| Unknown bundle | POST with `nope` | 404 French « Pack introuvable » | — |
| Invalid definition | PUT with `core` or an unknown id or empty list | 422, nothing saved | French message |
| Validation failure | ACTIVE without date on a bundle with a paid module | 422, no row written for any module | French message |
| Non super-admin | OWNER token | 403 | — |
| Delete | DELETE `pro` then GET | gone; tenants that applied it keep their modules | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/application/DependencyResolverService.java:80-109` -- `planActivation(schema, moduleId)`; topological order, `alreadyActive`, 16.1 non-ENABLED dependency renewal.
- `.../shared/module/application/ModuleLifecycleService.java:154-252` -- `activate` (validation, 14-day default, `checkExpiryRules`, lock, provisioning, hooks, audit), `deactivate` 368; `[ADMIN AUDIT]` logs.
- `.../shared/module/adapter/out/persistence/JdbcTenantModuleLifecycleStore.java:50-70` -- upserts (origin ADMIN).
- `.../admin/module/adapter/in/rest/ModuleAdminController.java` -- single activation endpoint + error mapping style; add bundle endpoints here or in a sibling `BundleAdminController`.
- `.../shared/domain/exception/ErrorCode.java`, `.../shared/infrastructure/web/GlobalExceptionHandler.java` -- French messages for new codes.
- `keevo/backend/src/main/resources/db/migration/` -- latest V33 → add `V34__bundles.sql`.
- `keevo/backend/docs/entitlements/endpoints.md` -- regenerated by the architecture test when endpoints change.
- Tests to extend: `shared/module/application/{DependencyResolverServiceTest,ModuleLifecycleServiceTest}`, `shared/module/ModuleLifecyclePostgresIntegrationTest`, `admin/module/adapter/in/rest/ModuleAdminControllerTest`, `TestModuleCatalogs` (paid→paid fixture `lcpremium` from 16.1).

## Tasks & Acceptance

**Execution:**
- `V34__bundles.sql` -- tables + seed `pro`.
- `DependencyResolverService` -- set-based `planActivation`; single delegates; tests (shared dependency once, order, already active, cycle-free).
- `ModuleLifecycleService` -- set-based activation; single = set of one; all-or-nothing validation; tests (bundle vs single identical, validation failure writes nothing).
- Bundle store/port + `BundleAdminService` -- CRUD + apply (resolve tenant, load bundle, call lifecycle).
- Controller(s) -- 4 endpoints, OpenAPI docs; `ErrorCode`/handler French messages (`BUNDLE_NOT_FOUND` 404, `BUNDLE_INVALID` 422).
- Tests -- `BundlePostgresIntegrationTest` (seed present, apply Pack Pro on a Free tenant, TRIAL default, delete keeps modules, 403/404/422), controller slice tests.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 13-3 item of 16.2; add a routed item to 16.4: console « Packs » section using these endpoints.

**Acceptance Criteria:**
- Given keevo_dev with the backend started with WhatsApp noop and FCM off, when the super-admin applies `pro` to a Free test tenant through the API, then `GET /admin/tenants/{id}/modules` shows `invoicing` enabled with the requested expiry and the free modules unchanged, and applying it again reports everything already active.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 26 findings — high 0, medium 4, low 16, false 3, maybe-false 3
- findings:
  - `[low]` `[reject]` (intent) Loop replaced by one set plan — required by the 13-3 deferred item and the spec.
  - `[low]` `[reject]` (intent) Single vs bundle response shapes differ — different endpoints; rows/audit identical (tested).
  - `[low]` `[reject]` (intent) Definition CRUD beyond the ACs — spec decision (super-admin must define packs).
  - `[low]` `[reject]` (intent) No console UI — routed to 16.4.
  - `[false]` `[reject]` (intent) Manual keevo_dev check not evidenced — run by the orchestrator, recorded below.
  - `[medium]` `[patch]` (blind, edge) Applying a pack with dates rewrote already-ENABLED paid modules — bundle application never rewrites ENABLED targets (`setNeverRewritesEnabledTargets`); single activation still renews deliberately.
  - `[medium]` `[patch]` (blind, edge, verification-gap) Single activation behaviour changes for TRIAL without date (free target with paid dependency; kept target's repair dependencies) — kept as the intended rule, documented in the javadoc and pinned by two tests.
  - `[low]` `[patch]` (blind) `activate(core)` now validated the request — all-core returns before validation (`activateCore`).
  - `[low]` `[patch]` (blind) `ActivationPlan.targets` kept core / `moduleId()` arbitrary — core filtered, `moduleId()` deprecated.
  - `[low]` `[reject]` (blind) Bundle definition changes only in `[ADMIN AUDIT]` logs — same mechanism as every other super-admin change.
  - `[medium]` `[patch]` (blind, verification-gap) Stale pack invisible in GET and re-check untested — `applicable` flag, `BUNDLE_NOT_APPLICABLE` with a clean French message, `stalePack` test (nothing written).
  - `[low]` `[patch]` (blind) Stale-apply message with double colon — whole message rewritten.
  - `[low]` `[reject]` (blind) Apply response lacks resulting terms — console reloads `GET /admin/tenants/{id}/modules`.
  - `[low]` `[reject]` (blind) No unit tests of `BundleAdminService`/`JdbcBundleStore` — covered by the full-context Postgres test (10 cases).
  - `[low]` `[reject]` (blind) Parsing helpers shared across controllers — cosmetic.
  - `[low]` `[reject]` (blind) No UNIQUE(bundle_id, position) / empty pack at DB level — API enforces; order ties are deterministic.
  - `[false]` `[reject]` (blind) Spec artifact unfinished — finalized now.
  - `[low]` `[reject]` (blind) Integration test leaves tenants in the test database — throwaway container data.
  - `[maybe-false]` `[reject]` (blind) Mixed enabled/inactive case untested — now covered by `setNeverRewritesEnabledTargets`.
  - `[medium]` `[patch]` (edge ×2) duplicates of the TRIAL-without-date entry — grouped above.
  - `[low]` `[patch]` (edge) Pack with a date cutting a plan-granted open-ended module — grouped with the ENABLED-target entry.
  - `[low]` `[reject]` (edge) Unknown id not named in the 422 — ids are technical data (no-technical-data rule).
  - `[maybe-false]` `[reject]` (verification-gap other) Concurrent PUT on the same new id → 500 — super-admin only, unlikely.
  - `[false]` `[reject]` (intent) Path prefix `/api/v1` — codebase convention.
  - `[maybe-false]` `[reject]` (intent) « Module indisponible » edge untested — covered by `stalePack`.
  - `[low]` `[reject]` (intent) Provisioning rollback only tested at lifecycle level — same engine as the bundle path.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.module.**'` then `'com.keevo.admin.**'`, `'com.keevo.architecture.**'`, `'com.keevo.shared.infrastructure.**'` -- expected: green, one package at a time

**Manual checks:**
- keevo_dev AC above. Start the backend ONLY with `-Dspring-boot.run.arguments="--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false"` (`.env` points to the real WhatsApp provider); stop it with `fuser -k 4500/tcp`; remove the test rows written on keevo_dev afterwards or use a dedicated test tenant (e.g. KV-I3NPYM, Free).

## Auto Run Result

Status: done

**Summary:** packs of modules (`public.bundle_definitions` + `bundle_modules`, V34, seed `pro` « Pack Pro » = pos, inventory, invoicing, crm) with super-admin `GET/PUT/DELETE /api/v1/admin/bundles[/{id}]` and `POST /api/v1/admin/tenants/{id}/bundles/{bundleId}`. Applying a pack builds one activation plan over the union of dependency closures (`planActivation(schema, Set)`) and runs the same lifecycle as a single activation (single = set of one); all-or-nothing on validation and provisioning; already-enabled modules are never rewritten by a pack. Stale packs are flagged `applicable=false` and refused with a French message.

**Review:** 26 findings — 7 patched entries (4 medium, 3 low), 19 rejected with reasons. Follow-up review recommended: true — several medium entries patched on a first pass; named risk: the `renewEnabled` split between single activation and bundle application in `activateAll`.

**Verification:** backend packages one at a time, all green — shared.module 429, admin 547, architecture 32, shared.infrastructure 607, subscription 83. keevo_dev (backend with WhatsApp noop, FCM off), super-admin API: `GET /admin/bundles` → `pro`; apply `pro` to Free test tenant KV-I3NPYM with ACTIVE until 2027-06-30 → Facturation activated (ENABLED, expires 2027-06-30), pos/inventory/crm already active; re-apply → everything already active. Test row restored afterwards. No app change.
