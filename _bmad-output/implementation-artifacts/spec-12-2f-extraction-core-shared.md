---
title: '12.2f — Core extraction, step 1: shared no longer reaches into business-module internals'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: '2d5470a496c11c7dd6b6358b5c221eaa11eec3b0'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2e-api-reporting-messaging-feedback.md'
  - '{project-root}/spec-socle-modulaire.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `shared` — the future `core` that every module may use — still depends on 23 internal types of business modules (94 non-exposed violations): JPA entities of other modules live in `shared.infrastructure.persistence.entity` (Product, ProductSupplier, StockLevel, StockMovement, StockTransfer, Client, Supplier); central schedulers in `shared` call admin/subscription use cases; the admin bootstrap and account deletion use identity's JPA repositories and entities; audit and tenant provisioning use identity's `UserRepository`/`TenantRepository`. A core that depends on business modules defeats the modular target (spec-socle §0/§13: core depends on nothing).

**Approach:** Move each piece to the module that owns it, and make what must stay in `shared` use only published APIs. Pure relocation/rewiring, no behaviour change.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- JPA entities go to their owning module's `adapter/out/persistence` (catalog: Product, ProductSupplier if product-owned else crm; inventory: StockLevel, StockMovement, StockTransfer; crm: Client, Supplier). `AuditLogJpaEntity` stays (audit is core).
- Schedulers in `shared` that only trigger a module's use case move into that module (admin: alert-ack purge, sync-silence notices, global notification dispatch/purge, health sampling; subscription: trial downgrade, expiry reminders), keeping their cron expressions, ShedLock names and `@SchedulerLock` settings exactly.
- Admin account bootstrap (`AdminAccountInitializer` and friends) and account deletion scheduling move into `identity` (identity is part of the future core).
- What legitimately stays in `shared` (audit, tenant schema provisioning) uses `identity :: api` only — identity is the core-to-be, so `shared → identity :: api` is accepted until `core` is carved out.
- `shared → X :: events` (audit listener consuming module events) stays until 12.3 moves contracts to `core.event.catalog`.
- Out of scope (stay routed): the `identity ↔ subscription` cycle (needs tenant/plan ownership redesign — 16.8), `StoreCountAdapter`/`UserCountAdapter` table access and `NotificationPort` tenant convention are **in** scope only if trivially done along the way; otherwise leave them routed and say so.

## Boundaries & Constraints

**Always:** table names, columns, Flyway, entity mappings (`@Table`, `@Column`, schema) identical; cron/ShedLock identical (SchedulerLockCoverageTest stays green with updated class names); admin bootstrap idempotent as today; all test packages green; not-allowed 0; published-module assertion green.

**Never:** no behaviour change, no new tables, no event relocation (12.3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| App boot on an empty DB | first start | super-admin tenant + user created as today | partial state recovered as today |
| App boot on an existing DB | admin present | no change | — |
| Scheduled jobs (alerts purge, sync-silence, global push, health sample, trial downgrade, expiry reminders, account deletion) | cron fires | same effect, same lock names | same |
| Audit of any business action | event published | same audit row | unchanged |
| Architecture report | after change | 0 non-exposed violations with source `shared` | — |

</frozen-after-approval>

## Code Map

- Violations with source `shared` (report after 12.2e): identity ← `AdminAccountInitializer`/`AdminProperties` (`TenantJpaEntity`, `UserJpaEntity`, `UserTenantMembershipJpaEntity`, the three Spring repositories), `AccountDeletionScheduler` (`Tenant`, `TenantRepository`), `AuditPort`/`AuditWriteContextResolver` (`UserRepository`, `UserMembershipInfo`), `TenantSchemaProvisioner` (`TenantRepository`, `UserRepository`), a listener using `SectorType`, owner-phone lookup (`User`); catalog ← `ProductJpaEntity` (`ProductStatus`); inventory ← `StockTransferJpaEntity` (`TransferStatus`), audit listener (`InventoryScope`); admin ← schedulers calling `PurgeAlertAcksUseCase`, `SendSyncSilenceNoticesUseCase`, `DispatchGlobalNotificationsUseCase`, `PurgeGlobalNotificationsUseCase`, `RecordHealthSampleUseCase`; subscription ← schedulers calling `DowngradeExpiredTrialsUseCase`(+command), `SendPlanExpiryRemindersUseCase`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/` — 8 entities; their Spring Data repositories and mappers may also live in shared — move together.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/SchedulerLockCoverageTest.java` — lists scheduled classes by FQN.
- `ApplicationModulesBaselineTest` — add an assertion: no non-exposed / internal violation with source `shared`.

## Tasks & Acceptance

**Execution:**
- [x] Move the 7 business JPA entities (+ their repositories/mappers) to their owning modules.
- [x] Move the module-specific schedulers to admin / subscription; admin bootstrap + account deletion to identity.
- [x] Rewire remaining shared code (audit, provisioning, owner phones, sector type) to `identity :: api` (add ports there if missing); give `shared` explicit `allowedDependencies` reflecting what remains (`identity :: api` + `* :: events`).
- [x] Add the "no violation from shared" assertion; record before/after counts and remaining cycles in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no non-exposed or internal violation has source `shared`, and the total decreases vs 149.
- Given all test packages run one at a time, then all pass, including the Postgres integration tests that boot the full context.

## Implementation Notes

**Entity moves (`git mv`, mappings untouched).** `ProductJpaEntity` → `catalog.product.adapter.out.persistence.entity`; `ClientJpaEntity`, `SupplierJpaEntity`, `ProductSupplierJpaEntity` → `crm.contact.adapter.out.persistence.entity` (`product_suppliers` is only read/written by crm's `SupplierRepositoryAdapter`, so crm owns it); `StockLevelJpaEntity`, `StockMovementJpaEntity` → `inventory.stock.adapter.out.persistence.entity`; `StockTransferJpaEntity` → `inventory.transfer.adapter.out.persistence.entity` (the `entity` subpackage of architecture.md Layer 2, as identity/commerce/subscription/sync/messaging). Their Spring Data repositories and adapters were already in the owning module's `adapter/out/persistence` (no repository/mapper lived in shared). Tests `ProductJpaEntityTest`, `ClientJpaEntityMappingTest`, `SupplierJpaEntityMappingTest` moved with them. `AuditLogJpaEntity` stays (audit is core). Entity/repository scanning is by root package (`com.keevo`), so no config change. ArchUnit rule 1 now flags only `AuditLogJpaEntity` (was 8).

**Scheduler moves (cron, zone, `@SchedulerLock` name/bounds byte-identical).** admin: `PlatformHealthSampleScheduler` → `admin.platform.adapter.in.scheduling`, `SyncSilenceAlertScheduler` → `admin.alerts.adapter.in.scheduling`, `GlobalNotificationScheduler` + `GlobalNotificationPurgeJob` → `admin.notification.adapter.in.scheduling`. subscription: `SubscriptionExpiryScheduler` + `PlanExpiryReminderScheduler` → `subscription.plan.adapter.in.scheduling`. identity: the former `AccountDeletionScheduler` is split — the routine (daily run + `deleteNow`) becomes `identity.auth.application.service.TenantDeletionService` (tests renamed `TenantDeletionServiceTest`, `TenantDeletionServicePostgresIntegrationTest`; `AccountDeletionConcurrencyPostgresIntegrationTest` moved alongside), and a thin `identity.auth.adapter.in.scheduling.AccountDeletionScheduler` keeps the `@Scheduled` cron (`0 0 3 * * *`, no zone) and `@SchedulerLock("accountDeletionScheduler.executeDeletions", PT2H/PT1M)` and delegates (`AccountDeletionSchedulerTest`). `PlanTypeRealignJob` (+ test) → `subscription.plan.adapter.in.bootstrap` (it realigns `public.tenants.plan_type` from subscription data; same ready-event order and lock). `SchedulerLockCoverageTest` imports updated; the classified set is unchanged.

**Admin bootstrap.** `AdminAccountInitializer` (+ test) → `identity.auth.adapter.in.bootstrap` (inbound `ApplicationRunner` seeding identity's own tables through its JPA repositories; same idempotent existence checks and crash-recovery membership guard). `AdminProperties` stays in `shared.infrastructure.config`: it depends on nothing and `ProdSecretsValidator` (shared) reads it too; identity → shared is allowed.

**identity :: api additions.**
- `TenantDeletionPort.deleteNow(UUID)` — implemented by package-private `identity.auth.adapter.in.api.TenantDeletionAdapter` (null-checked id, delegates to `TenantDeletionService.deleteNow`; implementation notes live on the adapter/service, the port documents behaviour only). `AdminTenantService` now takes the port instead of the scheduler class (same call, same results); the concurrency IT drives it through the real adapter (`IdentityAuthApiTestSupport.tenantDeletionPort`).
- `UserQueryPort.findMembershipSchemas(UUID)` — schema names of `UserRepository.findMembershipsWithTenantInfo` in the same order. `AuditEventListener` uses it for the cross-tenant password-reset audit (same loop, same rows).

**Event-carried enums.** `SectorType` (carried by `OnboardingCompletedEvent`) and `InventoryScope` (carried by `InventorySessionCreatedEvent`) get a type-level `@NamedInterface("events")`, like `MovementType` in 12.2b.

**shared allowedDependencies** (still `Type.OPEN`): `catalog :: events`, `commerce :: api`, `commerce :: events`, `crm :: events`, `identity :: api`, `identity :: events`, `inventory :: events`, `reporting :: events`, `store :: api`, `store :: events`. Deviation from "identity :: api + * :: events": two api types are carried by consumed events (`commerce.api.DayClosureSummary` in `DayClosedEvent`, `store.api.StoreType` in store events), so `commerce :: api` and `store :: api` are listed; they leave with the event contracts in 12.3. With an explicit list, any new shared → X edge now fails the not-allowed assertion.

**Guard.** `ApplicationModulesBaselineTest` asserts no non-exposed / internal-of-declared violation with source `shared`, whatever the target (`isInternalAccessFromShared`); classifier samples were captured from a real `detectViolations()` run with a temporary probe class (not committed): with an explicit allow-list, internals of a listed module are reported as "depends on module X … Allowed targets" (flagged by the new guard), and a module outside the list (e.g. admin) as not-allowed (caught by the existing assertion).

**Violations (`target/modulith-violations.txt`).**

| | Before (12.2e) | After |
|---|---|---|
| Total | 149 | **55** |
| Cycles | 55 | 55 |
| Non-exposed (all from `shared`) | 94 | **0** |
| Not-allowed | 0 | 0 |

No cycle involves `shared` (Modulith does not report the OPEN module in cycles). Remaining direct 2-module cycles: `catalog ↔ messaging`, `commerce ↔ messaging`, `identity ↔ messaging` (12.3 event catalogue) and `identity ↔ subscription` (16.8); the 51 longer cycles are combinations of these edges through store/subscription/inventory.

**Verification (2026-10-29).** `compile test-compile` OK. 14 packages sequential: architecture 14, catalog 240, crm 114, inventory 376, commerce 264, sync 496, identity 434, messaging 142, reporting 389, shared 610, admin 504, store 60, subscription 66, feedback 52 — 3,761 tests, 0 failures/errors (Postgres ITs ran on Testcontainers, 0 skipped: account-deletion 16, admin tenant list 6). `grep -E "^- Module 'shared' depends on" target/modulith-violations.txt | wc -l` → 0. Not live-tested on a dev backend (boot-time bootstrap only covered by unit + context tests).

**Review fixes (2026-10-29).** Entities into `adapter/out/persistence/entity`; `AdminAccountInitializer` → `adapter.in.bootstrap`; `AccountDeletionScheduler` split into `TenantDeletionService` + thin scheduler; `PlanTypeRealignJob` → `subscription.plan.adapter.in.bootstrap`; concurrency IT uses the real `TenantDeletionAdapter`; `TenantDeletionPort` Javadoc reduced to behaviour; classifier samples captured from a real run; `shared/package-info` comment completed; formatting. Targeted tests for the edited files green (architecture 15, entity/repository adapters, identity deletion/bootstrap/api, admin tenant, subscription realign, SchedulerLockCoverageTest); violations still 55 / 0 from shared. Full 14-package run pending on the orchestrator side.

**Left routed (not trivial along the way).** `StoreCountAdapter` / `UserCountAdapter` cross-module table reads and the `NotificationPort` tenant convention (12.2e B5) are untouched — they stay with their routed stories.

### Verification & live run (2026-10-29, after review patches)

14 packages sequential: 3,763 tests, 0 failures/errors. Violations 55 — all cycles; 0 non-exposed, 0 not-allowed, 0 with source `shared`. Live: empty throwaway PostgreSQL → super-admin bootstrap created, second boot idempotent, super-admin login OK; dev DB → API smoke 200/201, Redmi POS sale → server 5 sales / 36,000, stock 9→8, audit rows `SALE_COMPLETED` + `STOCK_ADJUSTED` written; backend log 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (12-2f)
- verdicts: 16 findings — high 0, medium 3, low 10, false 3, maybe-false 0 (+ intent-alignment notes; verification-gap: none)
- findings:
  - `[medium]` `[patch]` B3 `AccountDeletionScheduler` mixes trigger and routine; public port adapter depends on a scheduler — split into `TenantDeletionService` + thin scheduler
  - `[medium]` `[patch]` B4 `PlanTypeRealignJob` left in shared (identity/subscription logic via raw JDBC) — moved to subscription
  - `[medium]` `[patch]` B5/E1/IA-a force-delete ITs bypass `TenantDeletionAdapter` (lambda) — real adapter used
  - `[low]` `[patch]` B1 entities outside the `adapter/out/persistence/entity/` convention — moved to `entity/`
  - `[low]` `[patch]` B2 `AdminAccountInitializer` (inbound runner) in `adapter/out/persistence` — moved to `adapter/in/bootstrap`
  - `[low]` `[patch]` B6 stale test field name `accountDeletionScheduler`
  - `[low]` `[patch]` B7 `TenantDeletionPort` Javadoc: unenforced precondition / ambiguous false / implementation details — behaviour-only contract
  - `[low]` `[patch]` B8 classifier samples not real Modulith output — captured from a real run
  - `[low]` `[patch]` B9 shared package-info omits event-carried enums and the closed allow-list rationale
  - `[low]` `[patch]` B10 formatting (blank lines, import order, FQN)
  - `[low]` `[patch]` B11 spec checkboxes unticked
  - `[low]` `[reject]` IA-b shared allow-list includes `commerce :: api`, `store :: api` and new `events` exposures (`SectorType`, `InventoryScope`) — accepted by orchestrator: types carried by events, removed with the event catalogue in 12.3
  - `[low]` `[reject]` IA-c new API (`TenantDeletionPort`, `findMembershipSchemas`) beyond pure relocation — required to cut shared → identity internals; accepted
  - `[false]` `[reject]` IA-a runtime matrix unproven — live run 2026-10-29 on an empty throwaway Postgres: super-admin tenant/user/membership created at boot; second boot skipped all three (idempotent, counts 1/1/1); super-admin login OK
  - `[false]` `[reject]` IA-e tenant schema provisioning missing from diff — `TenantSchemaProvisioner`/`TenantSchemaSyncService` already import only shared types (their identity edge was via the moved initializer)
  - `[false]` `[reject]` E-notes entity scanning / ShedLock / startup order risk — verified: default `com.keevo` scan, no duplicate entity names, lock names/cron unchanged, single ApplicationRunner without `@Order`

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `grep -E "^- Module 'shared' depends on" keevo/backend/target/modulith-violations.txt | wc -l` -- expected: 0
