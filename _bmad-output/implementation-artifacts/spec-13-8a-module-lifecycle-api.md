---
title: '13.8a — ModuleLifecycleService + super-admin activate/deactivate API (backend)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '9813662173dcb530b55a62f853863ee1a395d65f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-3-dependency-resolver.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-4-module-provisioner.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-5-entitlement-service.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Modules can be planned (13.3), provisioned (13.4) and gated (13.5), but nothing turns them on or off for a tenant. The super-admin must activate/deactivate a module per tenant through an API, with dependency handling, provisioning and audit (MOD-FR2/3/16, spec-socle §10). Story 13.8 is split: **13.8a = backend lifecycle + API** (this spec); 13.8b = the app showing held operations (the routed blocking UX item) — required before any module is disabled in production.

**Approach:** A core `ModuleLifecycleService` orchestrating, under a per-tenant lock in one transaction: plan (13.3) → `tenant_modules` writes → provisioning (13.4) → cache eviction → audit. A SUPER_ADMIN-only `ModuleAdminController`, plus read endpoints for the console and the owner.

**Decisions (autonomous run, user delegated all choices 2026-10-28; routed items of Story 13.8 folded in):**
- **Tenant key**: the admin API takes the tenant UUID (`public.tenants.id`); the service resolves the schema (status ≠ DELETED) → unknown → `404 TENANT_NOT_FOUND`. Platform tenant refused.
- **Activation** `activate(tenantId, moduleId, ActivationRequest{status ACTIVE|TRIAL (default ACTIVE), expiresAt (optional, future)}, actor)`:
  1. one transaction; first statement: `TransactionLockPort` lock `module-lifecycle:<schema>`;
  2. `planActivation` **before any write** (routed 13.3); super-admin activation **grants** paid modules: a lifecycle-scoped eligibility treats every step as eligible for a SUPER_ADMIN actor (routed 13.5) — the default free-only bean stays for any other caller; `blocked()` → `409 MODULE_ACTIVATION_BLOCKED` with French reasons naming modules by display name;
  3. for each step in `moduleIds()` order: upsert the row — target gets the requested status/`expires_at`; a dependency gets `ACTIVE` without expiry if free, otherwise the target's status/expiry; `grace_until = expires_at + 7 days` whenever `expires_at` is set (routed 16.1 rule applied at write); `activated_at = now`, `disabled_at = null`; then `ModuleProvisioner.provision(schema, id)` (routed 13.4: after the row write);
  4. an already-ENABLED target with nothing to plan → idempotent 200 (row refreshed only if the request changes status/expiry);
  5. `ModuleRegistry.evict(schema)` after the writes and after commit;
  6. audit `MODULE_ACTIVATED` per activated module (actor, tenant code, module display name — no ids/UUIDs in the French detail).
  A provisioning failure → whole transaction rolled back (rows not written; module stays as before), `500 MODULE_PROVISIONING_FAILED` with a French message, no technical detail (routed 13.4).
- **Deactivation** `deactivate(tenantId, moduleId, actor)`: same lock + transaction; `checkDeactivation` → `core` → `409 CORE_MODULE_REQUIRED`; active dependents → `409 MODULE_HAS_DEPENDENTS` listing them by display name; else upsert the row `DISABLED` with `disabled_at = now` (**never delete the row** — a free module without a row is active; routed 13.2); tables and data untouched; evict; audit `MODULE_DEACTIVATED`. Already DISABLED → idempotent 200. "Routes/permissions/handlers retirés" = the state-driven gates already in place (13.5 aspect, 12.7 sync guard, 13.6/13.7 registries) — nothing else to unregister before `KeevoModule` (13.9).
- **Endpoints** (`/api/v1/admin/...`, SUPER_ADMIN only, OpenAPI-documented):
  - `POST /admin/tenants/{tenantId}/modules/{moduleId}/activate` → `{activated: [{moduleId, displayName}], status, expiresAt, provisionedVersion}`;
  - `POST /admin/tenants/{tenantId}/modules/{moduleId}/deactivate` → `{moduleId, displayName, status}`;
  - `GET /admin/modules` → catalogue `[{moduleId, displayName, category, free, version, requires}]`;
  - `GET /admin/tenants/{tenantId}/modules` → per module `{moduleId, displayName, state, status, expiresAt, graceUntil, provisionedVersion}`.
  - Owner: `GET /api/v1/tenants/me/modules` (OWNER, current tenant) → `[{moduleId, displayName, state, expiresAt, graceUntil}]` (feeds 15.1/15.6).
  Error codes with French messages: `TENANT_NOT_FOUND` 404, `MODULE_NOT_FOUND` 404, `MODULE_ACTIVATION_BLOCKED` 409, `MODULE_HAS_DEPENDENTS` 409, `CORE_MODULE_REQUIRED` 409, `MODULE_PROVISIONING_FAILED` 500.
- **Logs**: `logging.level.org.flywaydb: WARN` in the prod profile (Flyway INFO names tenant schemas — routed 13.4); application logs use the tenant code.
- **Not here**: Flutter (13.8b), bundles (16.2), owner self-service activation and payments (16.1), `provisionAll` endpoint (16.4), `KeevoModule` hooks `onActivate` seeds (13.9).

## Boundaries & Constraints

**Always:** writes only through the lifecycle service; one transaction + per-tenant lock per call; the request cache evicted after every write; all test packages green; architecture suite green; Postgres ITs.

**Never:** no `DELETE` of `tenant_modules` rows, no `DROP`, no change to tenant business data, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Activate free module with inactive requires | target X requires Y, both DISABLED rows | rows Y then X ACTIVE, both provisioned, 200 lists [Y, X] | — |
| Activate paid module | super-admin, `expiresAt` +30 d | row ACTIVE, `grace_until` = +37 d | — |
| Activate already active | ENABLED | 200 idempotent | — |
| Unknown tenant | random UUID | — | 404 TENANT_NOT_FOUND |
| Unknown module | `ghost` | — | 404 MODULE_NOT_FOUND |
| Provisioning fails | broken migration (test module) | rows unchanged | 500 MODULE_PROVISIONING_FAILED |
| Deactivate free module | crm, no dependents | row DISABLED + `disabled_at`, data kept | — |
| Deactivate with dependents | pos while ... (test catalogue A requires B) | — | 409 MODULE_HAS_DEPENDENTS [A] |
| Deactivate core | core | — | 409 CORE_MODULE_REQUIRED |
| Same request twice concurrently | two activations | serialised by the lock, consistent rows | — |
| Non super-admin | OWNER token on admin API | — | 403 |
| Owner reads own modules | OWNER `/tenants/me/modules` | states incl. READ_ONLY | — |
| After deactivate | sync push `CREATE_CLIENT` | REJECTED `MODULE_INACTIVE` | client holds it |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` — registry, resolver, provisioner, eligibility, state store/ports, `ModuleCatalog`.
- `shared/application/port/TransactionLockPort.java`, `AuditPort.java`; `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional` (extend for the new lock).
- Admin REST style: `admin/*/adapter/in/rest/*Controller.java` (SUPER_ADMIN), `AdminLabels.java`.
- `shared/infrastructure/web/GlobalExceptionHandler.java`, `ErrorCode`.
- `application-prod.yml` (logging level).

## Tasks & Acceptance

**Execution:**
- [x] `ModuleLifecycleService` (activate/deactivate) + lifecycle eligibility + row upsert port + audit; unit tests.
- [x] `ModuleAdminController` + owner endpoint + error codes/messages + OpenAPI; slice/security tests.
- [x] Postgres E2E ITs for every matrix row (test catalogue with a migrating module and a dependency chain; the shipped catalogue for the crm deactivate → sync push scenario).
- [x] Prod logging level; extend the lock-coverage test; tick the Story 13.8 routed items handled here (13.2, 13.3, 13.4, 13.5 items) with evidence; leave the Flutter item for 13.8b.

**Acceptance Criteria:**
- Given `POST …/activate` as SUPER_ADMIN, then plan → rows → provisioning → cache eviction → audit `MODULE_ACTIVATED`; given `…/deactivate` without active dependents, then row DISABLED with `disabled_at`, data kept, audit `MODULE_DEACTIVATED`; with active dependents → 409 listing them.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-30).** Published `shared.module.ModuleLifecycle` (activate / deactivate / catalogue / tenantModules by UUID or schema; records `ActivationRequest`, `Actor(userId, superAdmin)`, `ActivationResult`, `DeactivationResult`, `ModuleRef`, `TenantModuleView`). Impl `shared.module.application.ModuleLifecycleService` (`@Transactional` methods): tenant resolved by UUID (live, not platform, valid schema) → `TransactionLockPort.lock("module-lifecycle:<schema>")` → registry evict → `planActivation` before any write (super-admin: a private `DependencyResolverService` built with a lifecycle-scoped `GrantingEligibility` — every step eligible; not a bean, the free-only `EntitlementActivationEligibility` stays the only `ModuleActivationEligibility` bean) → per step in `moduleIds()` order: upsert row (target: requested status/expiry; dependency: ACTIVE/no expiry if free, else the target's; `grace_until = expires_at + 7 d`; `activated_at = now`, `disabled_at = null`; tier FREE/STANDARD) → evict → `provision` → evict; audit after commit, eviction after completion. Idempotence: target ENABLED with an empty plan and same status/expiry → 200 with empty `activated`, nothing written; a READ_ONLY target (not in the plan, still "active") is renewed. Provisioning failure (any non-domain exception of the provisioner) → `MODULE_PROVISIONING_FAILED` (French message with the display name; log = module id + tenant code + exception class). Deactivation: core or any category-`core` module (`sync`) → `CORE_MODULE_REQUIRED`; effective DISABLED → idempotent; dependents → `MODULE_HAS_DEPENDENTS` (details `dependents` « A », « B »); else `upsertDisabled` (status + `disabled_at` only, other columns kept, never DELETE). Decisions beyond the spec: `platform` modules are not tenant modules → `MODULE_NOT_FOUND` and hidden from the catalogue/tenant views; `expiresAt` truncated to µs (PG precision, so an identical re-request is idempotent); ~~the tenant read happens before the lock~~ (superseded by review fix R4: lock on the tenant UUID first). Audit: `AuditPort` entry in the **tenant's** `audit_log` (TenantContext switched to the tenant schema for the write, caller's context restored), `entityType Module`, `entityId` = the `tenant_modules` row id, `entityLabel` = display name, `valueAfter` French (`module`, `statut`, `finAbonnement` dd/MM/yyyy WAT, `entreprise` = tenant code), metadata `actorLabel « Administrateur Keevo »`; plus an `[ADMIN AUDIT]` log line with the tenant code. New port `TenantModuleLifecyclePort` + `adapter.out.persistence.JdbcTenantModuleLifecycleStore` (findTenant, findStoredRows, upsertActivated / upsertDisabled `ON CONFLICT … RETURNING id`). `ErrorCode` + French messages/HTTP status: `MODULE_NOT_FOUND` 404, `MODULE_ACTIVATION_BLOCKED` / `MODULE_HAS_DEPENDENTS` / `CORE_MODULE_REQUIRED` 409, `MODULE_PROVISIONING_FAILED` 500 (`{reasons}` / `{dependents}` placeholders filled from details). Test catalogue `TestModuleCatalogs.lifecycleCatalog()` (+ `named(...)`) and test migrations `db/modules/{lcbase,lctop,lcpaid V1, lcbroken V1 invalid}`. `ModuleLifecycleServiceTest` 18 green.

**Progress — task 2 done (2026-10-30).** `admin.module.adapter.in.rest.ModuleAdminController` (`/api/v1/admin`: `POST tenants/{tenantId}/modules/{moduleId}/activate` with optional body `{status: ACTIVE|TRIAL, expiresAt: ISO instant/date-time or yyyy-MM-dd = end of that day WAT}`, `POST …/deactivate`, `GET modules`, `GET tenants/{tenantId}/modules`), `CurrentActor.requireSuperAdmin()` in every method behind the SecurityConfig `/api/v1/admin/**` SUPER_ADMIN rule, `@Tag`/`@Operation` OpenAPI docs; DTOs under `dto/` (`ModuleActivationDto{activated[{moduleId, displayName}], status, expiresAt, provisionedVersion}`, `ModuleDeactivationDto`, `ModuleCatalogueEntryDto{moduleId, displayName, category, free, version, requires}`, `TenantModuleDto{…state, status, expiresAt, graceUntil, provisionedVersion}`). Owner: `shared.module.adapter.in.rest.TenantModulesController` `GET /api/v1/tenants/me/modules` (OWNER via `CurrentActor.requireOwner`, tenant from `TenantContext`) → `[{moduleId, displayName, state, expiresAt, graceUntil}]`. Bad status / date → 422 `VALIDATION_ERROR` with a French field message. Tests: `ModuleAdminControllerTest` (7: parsing, defaults, 422, 404/409/500 French messages filled from details, OWNER 403 on the 4 endpoints) and `TenantModulesControllerTest` (2: OWNER sees READ_ONLY, EMPLOYEE 403) — green.

**Progress — task 3 done (2026-10-30).** `shared.module.ModuleLifecyclePostgresIntegrationTest` (fresh DB of the shared container + public Flyway, real JDBC stores, real Flyway module migrations, service behind a Spring `TransactionInterceptor` proxy on a `DataSourceTransactionManager`, lock port = the production `pg_advisory_xact_lock(hashtextextended(…))` statement) — 10 tests: cascade [base, top] rows ACTIVE + tables + version 1 + audit; paid +30 d → `grace_until` +37 d, tier STANDARD; already active idempotent (row unchanged, no audit); unknown / DELETED tenant 404, `ghost` 404; broken migration → `MODULE_PROVISIONING_FAILED`, dependency and target rows unchanged, no audit; deactivate with data → DISABLED + `disabled_at`, table/data kept, idempotent second call, reactivation clears `disabled_at` and the data reappears; deactivate without row inserts DISABLED (never a delete); dependents 409 [« Module cassé », « Module supérieur »] and core 409; two concurrent activations → one activates both modules, the other is idempotent, migrations applied once; tenant view READ_ONLY in grace. `admin.module.ModuleAdminFullContextTest` (full context, shipped catalogue, real JWTs and security chain, production JPA transaction manager + advisory-lock adapter) — 5 tests: deactivate crm → owner `/tenants/me/modules` shows crm DISABLED → push `CREATE_CLIENT` REJECTED `MODULE_INACTIVE` (no client) → activate crm → the same operation re-pushed is APPLIED; tenant `audit_log` has MODULE_DEACTIVATED then MODULE_ACTIVATED (label « Clients et fournisseurs », actor = super-admin, no schema/UUID/id in `value_after`), nothing in `public.audit_log`; OWNER token on the admin API → 403, nothing written; inventory while pos active → 409 « Point de vente », core 409, ghost / unknown tenant 404; catalogue hides `admin` (platform); **JDBC writes join the JPA transaction** (an enclosing rollback leaves no row and no audit — the lock adapter's Javadoc warning about raw JdbcTemplate does not apply to the lifecycle store). Fix found by the ITs: an enabled free module without a row (free rule) was never provisioned by an idempotent activation → the idempotent path and a repair plan now also provision the target (no-op when up to date). `[ADMIN AUDIT]` log lines moved after commit (a rolled-back call logged a deactivation).

**Progress — task 4 done (2026-10-30).** `application-prod.yml`: `logging.level.org.flywaydb: WARN`. `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional` covers `ModuleLifecycleService.activate` / `deactivate`. `epics-refonte-modulaire.md` Story 13.8: routed items 13-2, 13-3, 13-4, 13-5 ticked with evidence; the Flutter held-operations item (12-7) left open for 13.8b. Newly routed: 13.8b — Flutter audit labels for `MODULE_ACTIVATED` / `MODULE_DEACTIVATED`; 16.4 — expose `provisionAll` in the console; 16.1 — a free module that `requires` a paid one is active by the free rule while its dependency is off (decide before the first paid module ships).

**Verification (2026-10-30).** 14 backend packages run one at a time, all green: shared 923, admin 517, sync 528, architecture 30, catalog 247, commerce 251, crm 120, feedback 52, identity 437, inventory 376, messaging 163, reporting 389, store 60, subscription 70. Not done: the live dev-backend / Redmi check (it would write `tenant_modules`, audit rows and a client into a dev tenant). The same flow runs over real HTTP and JWTs in `ModuleAdminFullContextTest.crmLifecycleWithSyncPush`. The device-side held-operation display is 13.8b. Nothing committed.

**Review fixes (2026-10-30, coordinator review of 13.8a).**
- *R1 repair-plan NPE: done.* The result is built through a null-safe `result(activated, row)`; a target without a row → ACTIVE / null / null. Tests: `ModuleLifecycleServiceTest.repairPlanTargetWithoutRow` (lctop without a row + lcbase DISABLED + `active()`; and lcbuyer without a row + inactive lcpaid). The lcbuyer case needs an `expiresAt` under R2, because its paid dependency is written. PG test `repairPlanTargetWithoutRow`.
- *R2 expiry rules: done.* Every paid module the call writes (target or dependency) needs an explicit `expiresAt`. Otherwise → 422 `VALIDATION_ERROR` « Une date de fin est requise pour un module payant (« X ») ». A free module is always written ACTIVE with no end date. `TRIAL` with no `expiresAt` → 422. `expiresAt` must be in the future and at most 10 years away (`MAX_EXPIRY_YEARS`) → 422 instead of a SQL 500. A paid dependency gets the request's status and end date. **Deviation (needs confirmation):** `expiresAt` / `TRIAL` with a free target → 422, *unless* the call writes a paid dependency, to which they then apply. Without that exception, a free module that requires an inactive paid one could never be activated in one call, because the paid dependency needs a date and the free target forbids one. `activated_at` is kept on an already-active module: `upsertActivated(…, keepActivatedAt, …)` uses `CASE WHEN ? THEN COALESCE(existing, new)` and is only set on an inactive → active transition. A read-only module also counts as active. Tests: `expiryRules`, `activatedAtKept`, `paidDependency` (unit); `renewalKeepsActivatedAt` (PG).
- *R3 already-enabled dependencies: done.* The idempotent path now provisions, deps first (depth-first over `requires`), the members of the plan's `alreadyActive` closure that have no row (active by the free rule), then the target. Tests: `alreadyEnabledProvisionsFreeDependencies` (unit, InOrder); PG `alreadyActive` (lcbase and lctop tables created by an idempotent call).
- *R4 lock first: done.* `lockTenant(tenantId)` → `module-lifecycle:<tenant UUID>` is the first statement of `activate` and `deactivate`. A null id → 404 with no statement. The tenant is read after the lock. Tests: `lockFirst` (lock, then findTenant, for both calls); `refusals` (null id never locks). The lock-coverage entry comment was updated; that test remains reflection-based (`@Transactional` on both methods).
- *R5 provisioning failure message: done.* The new message is « Le module « X » n'a pas été activé : la création de ses tables a échoué. Vous pouvez réessayer. », with the same generic text in `FR_MESSAGES`. **Documented behaviour:** the rows are rolled back, but tables already created by earlier migrations or earlier plan steps stay committed (Flyway runs on its own connection). A retry reuses them. PG `provisioningFails` asserts that lcbase's tables exist while its row is unchanged.
- *R6 audit after commit: done.* Each audit write has its own try/catch: a lost entry logs **ERROR** with action, module id and tenant code (the outer after-commit guard also logs ERROR). Tests: `auditFailureSwallowed` (unit); PG `auditAfterCommitOnly` runs in a real transaction. In it, the fake audit checks through a fresh physical connection that the row is already committed, and a call inside an enclosing transaction that rolls back writes no audit.
- *R7 tests/docs: done.* The PG IT Javadoc now points to `com.keevo.admin.module.ModuleAdminFullContextTest`. New PG `cacheEvictedAfterCompletion`: a map-backed request cache is filled with an uncommitted DISABLED row inside a rolled-back transaction, and the test asserts it is dropped at completion. Mutation check: with the after-completion eviction removed, this test fails. **Recorded choices:** `platform` modules are not tenant-assignable (404 `MODULE_NOT_FOUND`) and are hidden from the catalogue and tenant views. Category-`core` modules (`sync`, not only `core`) cannot be deactivated (`CORE_MODULE_REQUIRED`). An idempotent activation still runs migrations for the target and its free-rule dependencies: it may upgrade them to a newer shipped version, or insert an ACTIVE/FREE row through the provisioner for a free module that had none. Accepted or routed by the orchestrator (not changed here): partial DDL itself, 2 connections per activation, and the 16.1 / 13.8b / 16.4 items.
- *Review-fix verification.* Packages run one at a time: shared 932, admin 517, sync 528, architecture 30 — all green. Nothing committed.

**Final verification (orchestrator, 2026-10-30, after review patches).** 14 packages sequential: 4,172 tests, 0 failures/errors; Modulith `✓ No module violations detected.`; `-Pprod-classpath` green. The shipped-catalogue HTTP flow (super-admin deactivates crm → sync push `CREATE_CLIENT` REJECTED `MODULE_INACTIVE` → activate → APPLIED) runs in `ModuleAdminFullContextTest` with real JWTs. Live call of the super-admin API on the dev backend not done: reading the dev super-admin credentials was refused by the permission rules; the device behaviour of a disabled module was already proven live in 12.7/13.5 (row-driven) and is re-tested in 13.8b.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass (13-8a)
- verdicts: 2 layers (Blind Hunter 14, Edge+Gap+Intent 12; overlap) — high 4, medium 5, low 8 after dedup
- findings:
  - `[high]` `[patch]` repair-plan branch NPE (free target without row + inactive dependency) — null-safe result, tests
  - `[high]` `[patch]` paid dependency granted forever / empty body wiped expiry and trial / TRIAL without expiry — orchestrator rule: every paid module written needs `expiresAt` (422 otherwise), free modules never expire (date applies to the paid dependency when the call writes one), TRIAL needs a date, ≤ 10 years; `activated_at` kept
  - `[high]` `[patch]` provisioning failure message claimed nothing changed while earlier steps' tables stay — message rewritten, test asserts the earlier tables exist and the row is unchanged
  - `[high]` `[patch]` lost audit after commit only a WARN with a class name — ERROR with action/module/tenant code; audit-after-commit tests in a real transaction
  - `[medium]` `[patch]` tenant read before the lock — lock on the tenant UUID first
  - `[medium]` `[patch]` already-enabled path did not provision free-rule dependencies — provisioned dependencies first
  - `[medium]` `[defer]` audit French labels (server `AuditLabels`, contract, app maps) → routed → 13-8b
  - `[medium]` `[defer]` READ_ONLY paid dependency not renewed, shorter dependency expiry, free requires paid → routed → 16-1
  - `[low]` `[patch]` test reference fixed, eviction-after-completion test (mutation verified), implementer choices recorded (platform modules hidden, `sync` not deactivatable, idempotent activation migrates)
  - `[low]` `[reject]` partial DDL after a failed later step — accepted (re-provision is a no-op), documented
  - `[low]` `[reject]` 2 pool connections per activation; stale status after expiry-then-deactivate — accepted
  - `[low]` `[defer]` `provisionAll` endpoint → 16-4 (duplicate items merged)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... com.keevo.admin.**`, `com.keevo.sync.**`, `com.keevo.architecture.**` -- expected: green
- 14-package sequential loop -- expected: green
- Live: dev backend + super-admin token → deactivate crm for the test tenant → Redmi offline client held → activate crm → applied.
