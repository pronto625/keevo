---
title: '12.4 — Durable cross-module events: Spring Modulith JDBC registry in public, tenant-aware module listeners'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '4dacb9ad98ed37742b700ee30a5050d986193b84'  # shared with 12-3 (batched review)
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-3-catalogue-events-partage.md'
  - '{project-root}/_bmad-output/planning-artifacts/refonte-journal-incoherences.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Cross-module reactions (notifications, end-of-day report, WhatsApp closure/plan messages) run in `@Async @TransactionalEventListener(AFTER_COMMIT)` listeners: if the app stops or the listener fails after commit, the reaction is lost for good — no outbox, no replay (spec-socle §7.2). Each listener re-binds `TenantContext` by hand.

**Approach:** Add the Spring Modulith JDBC Event Publication Registry (outbox) with its table in `public`, convert the cross-module after-commit listeners to `@ApplicationModuleListener` (same async/after-commit semantics + new transaction + durable publication), and bind/clear the tenant automatically from the `DomainEvent` envelope (12.3) around every module listener.

**Decisions (autonomous run, user delegated all choices 2026-10-28; journal A2/A3/A4):**
- Dependency `spring-modulith-starter-jdbc`; bump `spring-modulith-bom` 1.4.0 → latest 1.4.x patch available on Maven Central (1.4 line = Boot 3.5; never 2.x).
- `spring.modulith.events.jdbc.schema=public`, `spring.modulith.events.jdbc.schema-initialization.enabled=false`; table created by a new Flyway migration in the `public` location using the exact DDL Spring Modulith 1.4 expects for PostgreSQL (copy from the jar's `schema-postgresql.sql`).
- `spring.modulith.events.republish-outstanding-events-on-restart=true`; `completion-mode=UPDATE`; completed publications older than 7 days purged by a ShedLock-guarded daily job.
- Converted: every listener that is cross-module AND currently `@TransactionalEventListener(phase = AFTER_COMMIT)` (with or without `@Async`). Not converted: synchronous `@EventListener`s (audit trail stays in the business transaction — append-only audit atomicity), metrics (`BusinessMetricsListener`, after-commit but in-process counters, no durability value), intra-module listeners.
- Tenant binding: an aspect (or Modulith listener decorator) around `@ApplicationModuleListener` methods binds `TenantContext` from `DomainEvent.tenantId()` and clears/restores it in `finally`; remove the per-listener manual binding where it becomes redundant. Handlers must be idempotent: document per converted listener why a replay is harmless (or add a guard, e.g. notification dedup key) — no double WhatsApp on replay.
- Events must round-trip through the registry's Jackson serialisation: test each converted event type.

## Boundaries & Constraints

**Always:** notification/report/WhatsApp content, recipients and timing (after commit, async) unchanged in the nominal path; tenant isolation (listener runs in the event's tenant schema); all test packages green; app boots with Flyway validate in prod profile.

**Never:** no broker/externalisation, no change to synchronous audit, no change to event payloads (12.3 contracts).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Nominal: day closed → closure WhatsApp + report | event after commit | same messages, publication marked completed | — |
| Transaction rolls back | business error after publish | no publication row, no listener call | — |
| Listener fails (e.g. provider down) | exception in listener | publication stays incomplete; republished on restart | logged |
| App restarts with incomplete publications | rows without completion date | listeners re-invoked once each, tenant bound from event | idempotent: no duplicate notification |
| Two tenants publish concurrently | events for kv_a and kv_b | each listener runs in its own tenant schema | — |
| Prod profile boot | Flyway validate | `public.event_publication` present and valid | fail-fast if missing |

</frozen-after-approval>

## Code Map

- `keevo/backend/pom.xml:34-36` (BOM), add `spring-modulith-starter-jdbc`.
- `keevo/backend/src/main/resources/db/migration/` (public Flyway location — check `spring.flyway.locations` in `application.yml`) — new `V<next>__event_publication.sql`.
- Listeners to convert (verify each is cross-module + AFTER_COMMIT): `messaging/notification/application/listener/{DayClosure,ProductCreation,SaleDraftValidated,StockAlert,StockModification,TransferCreated}NotificationListener`, `reporting/report/application/service/EndOfDayReportListener`, `commerce/sale/application/service/DayClosureWhatsAppListener` (consumes commerce's own event? if intra-module, leave), `subscription/plan/application/service/PlanWhatsAppNotificationListener`, `admin/subscription/application/service/TenantStatusWhatsAppNotificationListener`, `admin/notification/application/service/GlobalPushSentEventLogger`, `identity/onboarding/application/service/DayClosureSchedulerNotifier`.
- `shared/infrastructure/persistence/TenantContext.java` — binding helper for the aspect.
- Existing async executor config (`AsyncConfig`) — Modulith listeners use the application task executor; keep MDC propagation item (routed 12-4 from review 11) here: add a context-propagating `TaskDecorator` (MDC traceId/tenantId/userId) to the executor(s).

## Tasks & Acceptance

**Execution:**
- [x] BOM bump + JDBC starter + config + Flyway migration (public) + purge job.
- [x] Tenant-binding aspect + MDC-propagating TaskDecorator; tests (binding, clearing on exception, two tenants).
- [x] Convert the listeners; idempotency note/guard per listener; serialisation round-trip test per event type.
- [x] Integration test with Testcontainers: publish in a tx → commit → listener invoked → publication completed; rollback → no row; failing listener → incomplete row → `IncompleteEventPublications.resubmitIncompletePublications` → invoked once.

**Acceptance Criteria:**
- Given the integration test suite, when run, then the nominal, rollback, failure+resubmit and two-tenant rows of the matrix pass against PostgreSQL.
- Given the prod profile boot test, when run, then Flyway validates `public.event_publication`.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

- Input from 12.3 (2026-10-29): 7 events not yet enveloped — `AccountDeletionRequested/Cancelled`, `UserRegistered`, `PlanActivated/Downgraded` (UUID `tenantId`), two password-reset events (no tenant). Any of them consumed by a converted listener must get the envelope (schema-name accessor) here; the others may stay as is.
- Draft tracking via events (routed from 12-2e) — do it here only if trivial, otherwise leave routed.

**Progress — task 1 done (2026-10-29).** BOM 1.4.0 → 1.4.14 (latest 1.4.x on Central) + `spring-modulith-starter-jdbc`; `spring.modulith.events.*` (schema public, init off, republish on restart, completion-mode update) in `application.yml` (test profile: republish off, shared test DB); Flyway `V28__event_publication.sql` (exact 1.4.14 PostgreSQL DDL, schema-qualified, + `public.event_listener_receipt`); `EventPublicationPurgeJob` (daily 03:50 WAT, ShedLock, 7 days); `EventPublicationSchemaGuard` (fail-fast at boot). Compile OK.

**Progress — task 2 code written (2026-10-29).** `ModuleListenerInterceptor` + `ModuleListenerConfig` (advisor on `@ApplicationModuleListener`, order HIGHEST+20: inside async, before the tx) binds/restores `TenantContext` + MDC `tenantId` from `TenantScopedEvent.tenantSchema()` (`DomainEvent` extends it; `PlanActivated/Downgraded` implement it with `schemaName`); `@DeliverOnce` receipts; `MdcPropagatingTaskDecorator` on `notificationExecutor` (queue 50 → 200, caller-runs on saturation). Tests `ModuleListenerInterceptorTest` (bind/restore, exception, plan schema, no-tenant, two tenants concurrently, `@DeliverOnce`) + `MdcPropagatingTaskDecoratorTest` green — **task 2 done**.

**Progress — task 3 listeners converted (2026-10-29).** `@ApplicationModuleListener`: `ProductCreation.onProductCreated`, `SaleDraftValidated` ×3 (+`@DeliverOnce`), `TransferCreated` (+`@DeliverOnce`), `EndOfDayReportListener` (no receipt: report idempotent by key), `DayClosureWhatsAppListener` legacy (+`@DeliverOnce`), `PlanWhatsApp.onActivated/onDowngraded` (+`@DeliverOnce`). Manual `TenantContext` binding removed from them; unit tests wrap listeners via `ModuleListenerTestSupport.bound(...)`. Listener tests green. `RegistryEventSerializationTest` (12 types, auto-discovered from every AFTER_COMMIT listener) green — **task 3 done**.

**Progress — task 4 done (2026-10-29).** `EventPublicationRegistryIntegrationTest` (full context, PostgreSQL: nominal, rollback, failure + `resubmitIncompletePublications`, two tenants concurrently incl. listener-tx `search_path`, `@DeliverOnce` replay) 5/5 and `EventRegistryProdBootIntegrationTest` (prod profile boot on a pristine DB: Flyway validate, exact table shape, registry properties; dropped table → `[FailFast]`) 2/2 green. Remaining: 14-package run.

**Verification (2026-10-29).** `compile test-compile` OK. 14 packages sequential, all rc=0: architecture 16, catalog 240, crm 114, inventory 376, commerce 264, sync 496, identity 435, messaging 155, reporting 389, shared 632, admin 504, store 60, subscription 69, feedback 52 (surefire XML). First run caught `KeevoApplicationContextIntegrationTest.schedulingDisabledInTestProfile`: `spring-modulith-moments` (pulled by the starter, on by default) adds `@EnableScheduling` → `spring.modulith.moments.enabled=false`; shared + architecture re-run green. Modulith violations unchanged (38 cycles, 0 not-allowed). Not live-tested on a dev backend.

**Converted listeners & replay safety.**
| Listener | Event (producer → consumer) | Replay |
|---|---|---|
| `ProductCreationNotificationListener.onProductCreated` | catalog → messaging | no receipt: only feeds the in-memory batch window (lost with the process, so a replay sends exactly one) |
| `SaleDraftValidatedNotificationListener` ×3 | commerce/sync → messaging | `@DeliverOnce` |
| `TransferCreatedNotificationListener` | inventory → messaging | `@DeliverOnce` |
| `EndOfDayReportListener` | commerce → reporting | **stays synchronous AFTER_COMMIT (R1)**, recorded by the registry; no receipt: each report REQUIRES_NEW and skipped if (store, date, type, actor) exists — replay fills gaps |
| `DayClosureWhatsAppListener` (legacy, off by default) | commerce → messaging | **stays synchronous AFTER_COMMIT (R1)**; `@DeliverOnce` |
| `PlanWhatsApp.onExpired`, `TenantStatusWhatsApp.onSuspended/onReactivated` | intra-module, sync | not converted; `@DeliverOnce` added (review fix) |
| `PlanWhatsAppNotificationListener.onActivated/onDowngraded` | admin → subscription | `@DeliverOnce`; tenant bound from `schemaName` (`TenantScopedEvent`) |

**Not converted (by the spec rule).** Sync `@EventListener`s (`AuditEventListener`, `GlobalPushSentEventLogger`, `DayClosureSchedulerNotifier`); `BusinessMetricsListener`; intra-module after-commit `PlanWhatsApp.onExpired`, `TenantStatusWhatsApp*` (admin → admin). `@Async @EventListener` (not after-commit): `DayClosureNotificationListener`, `StockAlert`, `StockModification`, `ProductCreation.onCsvImportCompleted` — left as is (converting would change their timing to after-commit); candidates for a follow-up decision. NB: the 1.4 registry stores a row for **every** after-commit listener, converted or not (journal I18); all such event types are covered by the round-trip test.

**Decisions / deviations** (1) and (3) partly superseded by the review rulings below (R1 sync listeners, R2 send failures rethrown). (1) Listener-internal try/catch kept (best-effort contract, content unchanged): a provider outage inside a handler is logged and the publication completes; incomplete publications come from crashes/stops/rejections/unexpected errors — the failure+resubmit row is proven with a probe listener. (2) `@DeliverOnce` receipts (journal A11) — at-most-once: a crash between receipt and send loses that message. (3) `notificationExecutor` queue 50 → 200 + caller-runs (EOD report now async). (4) `EventPublicationSchemaGuard` fail-fast (Modulith alone would only fail at the first publish). (5) Test profile: republish-on-restart off (shared test DB across contexts). (6) Staleness monitor / FAILED status of spec-socle §7.2 do not exist in 1.4 (journal I17). (7) Non-enveloped auth events (account deletion, registration, password reset) are not consumed by a converted listener → unchanged. (8) Draft tracking via events (12-2e) — not trivial, stays routed (no cycle depends on it since 12.3).


**Orchestrator rulings 2026-10-29 (review of 12.3 + 12.4).**
- **R1** — listeners that were synchronous (`EndOfDayReportListener`, `DayClosureWhatsAppListener`) go back to synchronous `@TransactionalEventListener(AFTER_COMMIT)` with their own tenant binding; the registry still records them (durable); the end-of-day report is again persisted before the close-day response (timing unchanged), which also removes the parallel combined-summary race.
- **R2** — in `@DeliverOnce` listeners, provider exceptions from the actual send are rethrown (`DeliveryFailedException`) so the publication stays incomplete and the receipt is released; non-retryable conditions (no recipient, feature disabled, invalid phone, missing sale) keep returning normally.
- **R3** — production is single-instance (HA deferred): overlapping-instance races are out of scope, documented, routed → 16-7; not coded.

**Review fixes (2026-10-29).**
- R1 applied: `EndOfDayReportListener` / `DayClosureWhatsAppListener` back to sync AFTER_COMMIT, previous Javadoc guarantee + manual `TenantContext` binding/restore restored; their tests construct them directly again (`DayClosureWhatsAppListenerTest` wraps it for the receipt only).
- R2 applied to the seven `@DeliverOnce` methods + the three newly annotated: `SaleDraftValidated` ×3 (both channels attempted, then the first failure rethrown with the other suppressed — a partial failure may repeat the successful channel on retry), `TransferCreated`, `DayClosureWhatsApp`, `PlanWhatsApp` ×3, `TenantStatusWhatsApp` ×2 (owner lookup failure stays logged and skipped). After commit, a rethrow never reaches the publisher (Spring logs `afterCompletion` exceptions); all publishers verified transactional. Tests: send throws → `DeliveryFailedException` propagates; receipt released (`SaleDraftValidated…sendFailures_propagate…`, interceptor tests, registry IT).
- `EventPublicationResubmissionJob` (new, `0 */10 * * * *`, ShedLock `PT9M`/`PT30S`): gauges `keevo.events.incomplete` / `keevo.events.incomplete.oldest.seconds`, WARN listing listener ids of publications incomplete > 24 h, `resubmitIncompletePublicationsOlderThan(10 min)`; in `SchedulerLockCoverageTest`. `EventPublicationMaintenance` (new) holds the count/age/purge SQL.
- Receipts retention **30 days** (`EventListenerReceipts.RETENTION`, documented: far beyond the 24 h alert, so a late replay still finds its receipt); publications 7 days; the purge job logs deleted counts and logs failures with the stack trace.
- `ModuleListenerInterceptor`: pointcut = `@ApplicationModuleListener` ∪ `@DeliverOnce`; tenant binding only for module listeners; a failing `release` is attached as suppressed to the listener's exception; an unhashable event is logged at ERROR and delivered without de-duplication; key text fixed (V28, `DeliverOnce`, interceptor): `sha256(event class name + ':' + application ObjectMapper JSON)`.
- `BusinessMetricsListener`: Javadoc — a replay after a crash may double-count (accepted, low).
- `AsyncConfig` implements `AsyncConfigurer` (`notificationExecutor` explicit default). **Real bug confirmed**: with scheduling on, `AsyncExecutorWiringIntegrationTest` (scheduling enabled) ran `@Async` on `SimpleAsyncTaskExecutor-1` without the override, `notification-*` with it (MDC propagated for a bare `@Async` and a module listener). Saturation: `LoggingCallerRunsPolicy` (WARN + counter `keevo.async.saturated`, then caller runs); tested.
- `ProductCommandAdapter.register`: `@Transactional(propagation = MANDATORY)`; test outside a tx → `IllegalTransactionStateException`, nothing saved or published. `PlanWhatsAppNotificationListener` Javadoc no longer mentions `fallbackExecution` as a guarantee.
- `CatalogueEventPayloadTest`: constant parity `MovementType`/`MovementKind`, `SectorType`/`SectorKind`, `StoreType`/`StoreKind`, `InventoryScope`/`InventoryScopeKind`.
- `EventPublicationRegistryIntegrationTest` 7/7: + failing `@DeliverOnce` probe (no receipt left, incomplete → resubmission delivers once), + `EventListenerReceipts.purgeOlderThan` on PostgreSQL.
- `ClosureWindow.of(DayClosedEvent)` moved after `COMMIT_SAFETY_MARGIN` (Javadoc back on its constant).
- Tests run (only the touched ones): listener tests (DayClosureWhatsApp, EndOfDay, SaleDraftValidated, TransferCreated, PlanWhatsApp, TenantStatusWhatsApp, ProductCreation), CatalogueEventPayload, SchedulerLockCoverage, ModuleListenerInterceptor, purge/resubmission jobs, ProductCommandAdapter, MdcPropagatingTaskDecorator, LoggingCallerRunsPolicy, RegistryEventSerialization, BusinessMetricsListener, EventPublicationRegistry IT, EventRegistryProdBoot IT, AsyncExecutorWiring IT, KeevoApplicationContext IT — all green.

### Verification & live run (2026-10-29, after review patches)

14 packages sequential: 3,802 tests, 0 failures/errors. Violations 38 — cycles only. Live (dev DB, Flyway V28 applied): API sale + close day → publications `BusinessMetricsListener.onSaleCompleted` and `EndOfDayReportListener.onDayClosed` completed; reports = store-level (actor null, WhatsApp FAILED in dev — no provider) + personal (closer) as in V1. Replay: EndOfDay publication forced incomplete → restart republished it once, completed, still 2 reports (idempotent skip). Redmi employee sale → server 9 sales, stock 2→1, 3/3 publications completed; backend log 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (batched 12-3 + 12-4, one diff)
- verdicts: 30 findings — high 2, medium 12, low 12, false 1, maybe-false 3 (+ intent-alignment notes)
- findings:
  - `[high]` `[patch]` B1 every `@DeliverOnce` listener (and the report listener) swallows delivery exceptions → publication completes, outbox never replays a failed send — delivery failures rethrown (R2)
  - `[high]` `[patch]` IA-d/B9/E9 `EndOfDayReportListener` (and legacy `DayClosureWhatsAppListener`) went sync → async against the spec's "timing unchanged" (report no longer persisted before the close-day response; parallel combined-summary race) — back to synchronous AFTER_COMMIT, still registry-recorded (R1)
  - `[medium]` `[patch]` B5 failures only retried at restart, no backlog visibility — scheduled resubmission + gauges + 24 h WARN
  - `[medium]` `[patch]` B8/E1/E2 `@Async` default executor ambiguous once Boot adds `taskScheduler` (prod scheduling on); test runs with scheduling off — `AsyncConfigurer` + scheduling-enabled context test
  - `[medium]` `[patch]` B4/E5 receipts purged after 7 days while incomplete publications replay later — 30-day retention documented
  - `[medium]` `[patch]` V-other/E11 sync WhatsApp senders (tenant status, plan expired) recorded by the registry without dedup → duplicate on crash replay — `@DeliverOnce` extended to them
  - `[medium]` `[patch]` V1 catalogue enum mirrors mapped by `valueOf(name())` without parity test — parity test
  - `[medium]` `[patch]` V2 executor wiring (MDC decorator, saturation policy) unverified — context test + logging saturation handler
  - `[medium]` `[patch]` V3 receipt release/purge SQL never run on Postgres; no failing-`@DeliverOnce` end-to-end case — IT cases added
  - `[medium]` `[patch]` V4/B7 `fallbackExecution` removed without guard; stale Javadoc — `MANDATORY` on `ProductCommandAdapter.register`, Javadoc fixed
  - `[medium]` `[defer]` B2 four `@Async @EventListener` notification listeners (closure push, stock alert, stock modification, CSV import) fire before commit and are not durable — pre-existing; converting changes timing → routed → 12-6
  - `[medium]` `[reject]` B6/E3/E7/E13 overlapping-instance races (republish during rolling deploy; skip-path completion) — production is single-instance (HA deferred); documented, routed → 16-7
  - `[low]` `[patch]` B10 interceptor release failure masks the original exception — `addSuppressed`
  - `[low]` `[patch]` E4 hashing failure makes a publication fail forever — log + deliver without dedup
  - `[low]` `[patch]` B3 V28 comment misdescribes the receipt key — corrected (key stays class name + app JSON; rename-across-deploy risk documented, prod had no registry before 12.4)
  - `[low]` `[patch]` B10b purge job logs no counts / no stack trace — logging
  - `[low]` `[patch]` B8b ClosureWindow Javadoc orphaned — method moved after the constant
  - `[low]` `[patch]` B9b CallerRuns saturation silent — WARN + counter
  - `[low]` `[reject]` E10 CallerRuns runs plain `@Async` listeners inside the publisher tx when saturated — queue 200, now logged/counted; dedicated executor not worth the split at < 100 tenants
  - `[low]` `[reject]` E12 `BusinessMetricsListener` registry replay double-counts after a crash — accepted (in-process counters), documented
  - `[low]` `[reject]` B11 interceptor ORDER assumption untested — two-tenant IT proves tenant bound inside the listener tx (search_path asserted)
  - `[low]` `[reject]` IA-a shared allow-list keeps `identity :: api` — accepted in 12.2f (audit `UserQueryPort`) and plan events carry `PlanType`
  - `[low]` `[defer]` IA-b no assertion for identity ↔ messaging cycle / total < 55 — cycle routed → 12-6 (blocking verify will pin all cycles)
  - `[low]` `[reject]` IA-c `DayClosureWhatsAppListener` relocation commerce → messaging — accepted ruling (12.3)
  - `[low]` `[reject]` IA-g draft-tracking via events — left routed (no cycle depends on it)
  - `[maybe-false]` `[reject]` E8 report replay re-sends WhatsApp after a crash between send and commit — with R1 the listener is synchronous in the original flow; a crash in that window is a pre-existing at-least-once edge, low
  - `[maybe-false]` `[reject]` E6 DAILY_COMBINED race with parallel workers — removed by R1 (synchronous again)
  - `[maybe-false]` `[reject]` E13 `ProductCreation` duplicate across overlapping instances — single-instance (R3)
  - `[false]` `[reject]` IA-h registry behaviour proven only on probe listeners — probe IT is the intended registry contract test; real listeners covered by unit tests + the added failing `@DeliverOnce` IT + live run

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
