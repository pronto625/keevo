---
title: '13.5 — EntitlementService + declarative @RequiresEntitlement gating (real-time expiry, read-only grace)'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '95574b5b9d91c6481713c19970f9b2fd499bbf59'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-2-module-registry.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-3-dependency-resolver.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-7-sync-module-aware.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing refuses access to a module a tenant has not subscribed to, and module state ignores time: an ACTIVE/TRIAL row past `expires_at` still counts as active, and the EXPIRED read-only grace (spec-socle §2.2, §9.1) does not exist. `ModuleRegistry` (13.2) and a future entitlement check could disagree (routed from 13.2).

**Approach:** One effective-state computation in the core, evaluated in real time with the injected `Clock`, giving per (tenant, module) a state `ENABLED | READ_ONLY | DISABLED`. `ModuleRegistry` and a new `EntitlementService` both use it. A `@RequiresEntitlement` aspect gates use cases/controllers, with `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` mapped in `GlobalExceptionHandler`. The 12.7 sync guard distinguishes read-only.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Effective state** (single implementation, `Clock`-based, evaluated at every call — no dependence on a cron):
  1. module not installed → DISABLED; `core` → ENABLED always;
  2. row TRIAL/ACTIVE: `expires_at` null or > now → ENABLED; past `expires_at` → READ_ONLY while `grace_until` > now, else DISABLED;
  3. row EXPIRED: `grace_until` > now → READ_ONLY, else DISABLED;
  4. row DISABLED / AVAILABLE → DISABLED;
  5. no row → `free` → ENABLED, paid → DISABLED; unknown/DELETED tenant → only `core` ENABLED (13.2 rules kept).
  `TenantModuleStatePort` returns status + `expires_at` + `grace_until` + `tier` (one query, same request cache).
- **`ModuleRegistry`**: `activeModules`/`isActive` = state ≠ DISABLED (a read-only module is still "active" for reads, so its data stays visible); new `moduleState(schema, id)` and `moduleStates(schema)`. `DependencyResolver` keeps using `activeModules` (a read-only dependency still satisfies `requires`).
- **`EntitlementService`** (published, core): `isEnabled` (≠ DISABLED), `isReadOnly`, `assertEnabled` (DISABLED → `DomainException(MODULE_NOT_ENTITLED)`), `assertWritable` (DISABLED → `MODULE_NOT_ENTITLED`, READ_ONLY → `MODULE_READ_ONLY`), `enabledModules`. Tenant key = schema string (13.2 convention); platform-admin schema → every installed module ENABLED for entitlement purposes (super-admin is not gated by subscriptions) — document.
- **`@RequiresEntitlement(value = "moduleId", access = WRITE|READ)`** (default `WRITE`) on methods or types; aspect reads the tenant from `TenantContext` (no tenant bound → `IllegalStateException`, never an open door); `READ` → `assertEnabled`, `WRITE` → `assertWritable`. Unknown module id in the annotation → fail at startup (bean post-processing scan) with a message naming the class.
- **HTTP mapping**: `MODULE_NOT_ENTITLED` and `MODULE_READ_ONLY` → 403 with `domainCode`, French messages « Ce module n'est pas inclus dans votre abonnement » / « Ce module est en lecture seule : abonnement expiré ». No existing endpoint is annotated here (→ 13.10).
- **Sync guard (12.7 routed item)**: per operation, READ_ONLY → `REJECTED MODULE_READ_ONLY` (sync ops are writes), DISABLED → `MODULE_INACTIVE` as today. Flutter: `MODULE_READ_ONLY` is a **held** reason like `MODULE_INACTIVE` (data kept, automatic retry), French message « Module en lecture seule : abonnement expiré » ; pubspec bump 2.0.2+73 → 2.0.3+74.
- **Eligibility (13.3 routed item)**: delete `FreeModulesOnlyEligibility`; a single `EntitlementActivationEligibility` bean; `Eligibility(boolean eligible, String reason, String initialStatus)` — free → eligible, `initialStatus ACTIVE`; paid → not eligible `NOT_ENTITLED` (trials and payments are 16.1). Port contract: implementations must not return null; the resolver treats an exception or null as not eligible with reason `ELIGIBILITY_UNAVAILABLE` (logged, no data echoed).
- **Tenant status**: SUSPENDED / DELETION_PENDING tenants are not handled here — access is already refused at authentication; documented.
- **Not here**: `@RequiresPermission` (no permission model exists yet — routed to 13.10 with a decision), JWT `modules` claim (16.3), expiry cron/notifications (16.1), annotating existing endpoints (13.10).

## Boundaries & Constraints

**Always:** time comes from the injected `Clock`; no behaviour change for current tenants (no rows → every free module ENABLED); all test packages green; Flutter `core/sync` tests green; architecture suite green.

**Never:** no writes to `tenant_modules` from production code, no endpoint annotated in production code, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Free, no row | — | ENABLED | — |
| Paid, no row | — | DISABLED | `MODULE_NOT_ENTITLED` |
| ACTIVE, expires tomorrow | — | ENABLED | — |
| ACTIVE, expired 1 h ago, grace +7 d | — | READ_ONLY: read OK, write refused | `MODULE_READ_ONLY` |
| ACTIVE, grace ended | — | DISABLED | `MODULE_NOT_ENTITLED` |
| EXPIRED, in grace | — | READ_ONLY | — |
| DISABLED row | — | DISABLED | — |
| Clock moves past `expires_at` | same row, two calls | ENABLED then READ_ONLY (no cron) | — |
| Annotated WRITE endpoint, read-only | test controller | 403 `MODULE_READ_ONLY` | — |
| Annotated READ endpoint, read-only | test controller | 200 | — |
| No tenant bound | aspect | — | IllegalStateException |
| Sync op, module read-only | push | REJECTED `MODULE_READ_ONLY`, others applied | client holds it |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` — `ModuleRegistry`, `ModuleRegistryService`, `TenantModuleStatePort`, `JdbcTenantModuleStateStore`, `ModuleActivationEligibility`, `FreeModulesOnlyEligibility`, `DependencyResolverService`.
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/GlobalExceptionHandler.java`.
- `sync/sync/application/service/SyncPushService.java` (module gate).
- Flutter: `keevo/app/lib/core/sync/sync_rejection_policy.dart`, `sync_rejection_messages.dart`, tests; `keevo/app/pubspec.yaml`.
- Existing `Clock` bean (shared config).

## Tasks & Acceptance

**Execution:**
- [x] State model + port extension + registry/service; unit tests for every state row with a fixed `Clock`.
- [x] `EntitlementService` + `@RequiresEntitlement` aspect + startup validation + error codes/HTTP mapping; slice tests through a test-only controller (403 not entitled, 403 read-only on WRITE, 200 on READ, 200 entitled).
- [x] Sync guard READ_ONLY + Flutter held reason + message + tests + pubspec bump.
- [x] Replace the eligibility bean; resolver null/exception contract; update `DependencyResolverBootIntegrationTest`.
- [x] Tick Story 13.5 routed items; route `@RequiresPermission` to 13.10.

**Acceptance Criteria:**
- Given a tenant without module X (paid, no row), when it calls an endpoint annotated `@RequiresEntitlement("X")`, then it gets 403 `MODULE_NOT_ENTITLED`; with X TRIAL/ACTIVE it passes; with X expired in grace, writes get `MODULE_READ_ONLY` and reads pass.
- Given all backend packages run one at a time and `flutter test test/core/sync`, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** `ModuleState` (ENABLED/READ_ONLY/DISABLED, published) computed only by `shared.module.application.ModuleStateRules` (Clock instant passed in). `TenantModuleStatePort.findStatuses` → `findRows` returning `Row(status, expiresAt, graceUntil, tier)` (same single query, columns added). `ActiveModulesCache.Entry` now caches raw rows; state recomputed at every call with the injected `Clock` (so expiry is seen within the same request). `ModuleRegistry` gained `moduleState`/`moduleStates`; `activeModules`/`isActive` = state ≠ DISABLED. `TenantModuleStatus.isActive()` removed (no other user). Tests: `ModuleRegistryServiceTest` (+5, movable clock), `ModuleRegistryPostgresIntegrationTest` (+1, expiry columns read from PG); `com.keevo.shared.module.**` green.

**Progress — task 2 done (2026-10-29).** Published `EntitlementService` (+ `state`), `RequiresEntitlement(value, access=WRITE|READ)`; impl `shared.module.application.ModuleEntitlementService` (thin layer over `ModuleRegistry.moduleState(s)`; platform-admin `public` → every installed module ENABLED; refusal messages never echo the module id). `shared.module.infrastructure.EntitlementAspect` (`@Around @annotation || @within`, method overrides type, tenant from `TenantContext`, none → `IllegalStateException`; `@Order(1000)`: after Spring Security method interceptors, before `@Transactional`). `RequiresEntitlementValidator` (BeanPostProcessor + SmartInitializingSingleton: collects ids, checks against the registry once singletons exist → startup `IllegalStateException` naming class + id). `ErrorCode.MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` → 403 + French messages in `GlobalExceptionHandler`. No AOP dependency added: `aspectjweaver` already on the compile classpath (via data-jpa). Tests: `EntitlementGatingSliceTest` (WebMvc + real SecurityConfig, test-only controller: 403 not entitled, 403 read-only on WRITE, 200 READ, 200 entitled, grace over), `EntitlementAspectTest` (5), `RequiresEntitlementValidatorTest` (4), `ModuleEntitlementServiceTest` (7) — `com.keevo.shared.module.**` green.

**Progress — task 3 done (2026-10-29).** Backend: `SyncPushService` reads `ModuleRegistry.moduleStates` once per batch (still one `tenant_modules` read); per operation ENABLED → proceed, READ_ONLY → REJECTED `MODULE_READ_ONLY`, DISABLED/absent → `MODULE_INACTIVE`. Sync unit tests now stub `moduleStates` (`ModuleStatesFixture.enabled`); +2 guard unit tests, +1 full-context PG test (ACTIVE past expiry in grace → `MODULE_READ_ONLY`, sale applied, 1 read). `com.keevo.sync.**` green (528). Flutter: `heldSyncRejectionReasons = [MODULE_INACTIVE, MODULE_READ_ONLY]`; `isHeldSyncRejection` + every Drift/SQL held filter (`hasPendingOperations`, `_referencedHeldReason` — formerly `_referencesHeldEntity`, now returns the referenced op's reason —, `pendingSyncCount`, stale-queue check) use the set; the recorded `lastError` is the actual held reason (re-notified when it changes). French message « Module en lecture seule : abonnement expiré ». Tests: policy, messages, held (+2), monitoring (+1); `flutter test test/core/sync` green (333). pubspec 2.0.2+73 → 2.0.3+74.

**Progress — task 4 done (2026-10-29).** `FreeModulesOnlyEligibility` deleted; single bean `shared.module.application.EntitlementActivationEligibility` (free → `eligible(ACTIVE)`, paid → `refused(NOT_ENTITLED)`). `Eligibility(boolean eligible, String reason, String initialStatus)` with invariants (eligible ⇒ status, no reason; refused ⇒ reason, no status) + factories; contract "never null" documented. `DependencyResolverService` treats a null answer or a `RuntimeException` as not eligible, reason `ELIGIBILITY_UNAVAILABLE` (new `BlockReason.Code`), logged with the module id and exception class only. Tests: `DependencyResolverServiceTest` (+2), `DependencyResolverBootIntegrationTest` (exactly one eligibility bean, `initialStatus` ACTIVE, `EntitlementService` + `EntitlementAspect` wired in the full context). `com.keevo.shared.**` green (800).

**Progress — task 5 done (2026-10-29).** `epics-refonte-modulaire.md`: Story 13.5's two routed items (13-2 single source / expiry / SUSPENDED decision; 13-3 eligibility bean + contract) ticked with evidence; the 12.7 `MODULE_READ_ONLY` remainder annotated as done; Story 13.10 gets a routed item for `@RequiresPermission` with its decision (thin annotation over the existing role checks, combinable — entitlement aspect order 1000 runs after Spring Security's method interceptors, max 600, and before `@Transactional`) and the Spring-AOP limits (no interface-only / self-invocation match).

**Verification (2026-10-29).** All 14 backend packages run one at a time, all green: shared 800, sync 528, architecture 28, admin 505, catalog 247, commerce 251, crm 114, feedback 52, identity 437, inventory 376, messaging 163, reporting 389, store 60, subscription 70. `flutter test test/core/sync` green (333). Not done: device (adb) check, no commit.

**Review fixes (2026-10-29, coordinator review of 13.5).**
- *R1 gate silently open — done.* `RequiresEntitlementValidator.placementProblems` refuses at bean post-processing (`BeanCreationException` naming the class) every placement the pointcut cannot advise: annotation on a supertype / interface / supertype or interface method (incl. abstract base), class-level annotation with inherited non-overridden public methods, annotated method not public or static/final, public final method under class level. Validator and aspect now only look at the annotation declared on the bean class / its own methods; the aspect fails closed if the pointcut matched but no annotation resolves. JDK/Jakarta/Spring types skipped, `LinkageError` tolerated. Tests: `RequiresEntitlementValidatorTest` (10).
- *R2 non-request methods — done.* Pointcut excludes `execution(* java.lang.Object.*(..))` (overrides included) and `@ExceptionHandler`/`@InitBinder`/`@ModelAttribute`; validator refuses an annotated handler-infrastructure method, an annotated `@Scheduled`/listener method, and a class-level annotation on a bean declaring `@Scheduled`/`@EventListener`/`@TransactionalEventListener`/`@ApplicationModuleListener` methods. Tests: validator + `EntitlementAspectTest.excludedJoinPoints`.
- *R3 real-context proof — done.* `EntitlementAspectFullContextTest` (full app, normal auto-proxying, test-only `@Import`ed bean on shipped `crm`): pass when entitled (inside its transaction), `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` with **no transaction begun** (`TransactionExecutionListener` counter), read passes in grace, no tenant → ISE; advisor order on the real proxy method security < entitlement < transaction, plus behaviour (unauthenticated / wrong role refused by method security before the entitlement).
- *R4 timestamps — done.* V29 `expires_at` / `grace_until` are `TIMESTAMPTZ`; read with `getObject(.., OffsetDateTime.class)`. PG test with the JVM default zone set to UTC+14 reads the exact instants.
- *R5 small tests — done.* `grace_until` exclusive boundary, ACTIVE past expiry with null grace (`ModuleRegistryServiceTest`), unknown and DELETED tenant through the aspect (full-context test).
- *R6 typed Eligibility — done.* `Eligibility(boolean, BlockReason.Code reason, TenantModuleStatus initialStatus)`; `refused(Code)` / `eligible(TenantModuleStatus)`; resolver uses the code directly.
- *R7 Javadoc — done.* `ModuleEntitlementService` / `EntitlementService` state the platform-admin exception (registry: core + platform modules; entitlement: every installed module).
- *R8 Flutter — done.* `pendingSyncCount` placeholders built from `heldSyncRejectionReasons.length`; new `dominantHeldReason` (MODULE_INACTIVE wins), `_referencedHeldReason` collects every referenced held reason (entity + payload) → deterministic, no re-notify flip-flop; stale-queue query extracted to `oldestPendingOperation(db)` (`sync_monitoring_providers.dart`) and used by the trigger. Tests: policy (dominant), held (both reasons + restart no notice; READ_ONLY throttling by `due()`), monitoring (stale check ignores both held reasons).
- *Review-fix verification.* `com.keevo.shared.**` 816 green (validator test classes carrying `@Scheduled` made abstract so `SchedulerLockCoverageTest`, which scans concrete `@Scheduled` classes incl. tests, stays green), `com.keevo.sync.**` 528, `com.keevo.architecture.**` 28, `flutter test test/core/sync` 337 — all green. No commit.

**Final verification (orchestrator, 2026-10-29, after review patches).** 14 packages sequential: 4,036 tests, 0 failures/errors; Modulith `✓ No module violations detected.`; `flutter test test/core/sync` 337 green. Live (Redmi, dev APK 2.0.3+74, tenant kv_zggt7g): `crm` row ACTIVE with `expires_at` = now − 1 h, `grace_until` = now + 7 d → offline client « Paul Essomba » → reconnect → server `CREATE_CLIENT REJECTED MODULE_READ_ONLY`, no 500, client kept on the device → row removed + app restart → `CREATE_CLIENT APPLIED`, client on the server.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-5)
- verdicts: 3 layers (Blind Hunter 13, Edge+Gap 15, Intent 12; overlap) — high 2, medium 5, low 9 after dedup
- findings:
  - `[high]` `[patch]` gate silently open: validator accepted interface / supertype / inherited / non-public placements the pointcut never advises — startup failure for each; aspect refuses when the pointcut matches but no annotation resolves
  - `[high]` `[patch]` no proof the aspect advises in the real context — full-context test with normal auto-proxying, advisor order asserted (method security → entitlement → transaction), no transaction begun on refusal
  - `[medium]` `[patch]` class-level annotation gated Object/handler methods and tenant-less scheduled/listener methods — excluded / refused at startup
  - `[medium]` `[patch]` `expires_at`/`grace_until` read via `Timestamp` (zone shift risk) — `OffsetDateTime` (columns are TIMESTAMPTZ), tested with a UTC+14 JVM zone
  - `[medium]` `[defer]` super-admin activation of paid modules → routed → 13-8
  - `[medium]` `[defer]` class-level default WRITE on GET endpoints, combined `@RequiresPermission` slice + order, Flutter 403, role guard vs platform bypass → routed → 13-10
  - `[low]` `[patch]` typed `Eligibility` (BlockReason.Code, TenantModuleStatus); Javadoc platform-admin exception; boundary tests (grace exclusive, ACTIVE + null grace, unknown/DELETED tenant via aspect)
  - `[low]` `[patch]` Flutter: placeholders from the held-reason list; deterministic dominant held reason (MODULE_INACTIVE wins), no re-notify flip-flop; `due()` and stale-queue tests
  - `[low]` `[patch]` `@RequiresPermission` routed decision reworded as a proposal (13.10 confirms); 13.8 held-sales item extended to expiry
  - `[low]` `[reject]` platform-admin "all enabled" for entitlement vs core+platform in the registry — spec decision, now documented
  - `[low]` `[reject]` READ_ONLY counted as active in resolver/provisioner — spec decision; renewal → routed → 16-1
  - `[low]` `[defer]` always set `grace_until`; renewal of READ_ONLY → routed → 16-1; JWT claim with read-only → routed → 16-3

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... com.keevo.sync.**`, `com.keevo.architecture.**` -- expected: green
- `cd keevo/app && flutter test test/core/sync` -- expected: green
- 14-package sequential loop -- expected: green
