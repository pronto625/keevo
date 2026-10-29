---
title: '12.2e — Public APIs of messaging, reporting and feedback; one tenant convention for every published API'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: 'bf609e8730038277f7dcce556beb958afd88b980'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2d-api-publiques-identity-store-subscription.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The last modules without a published API are still reached through internals: `messaging` (7 types: `WhatsAppPort` used by 5 modules, `NotificationPort`, `NotificationPayload`, `PushSendResult`, draft-notification types used by catalog), `reporting` (`DailyReportFormatter`, `EndOfDayReportBuilder` used by identity — an `identity ↔ reporting` cycle), `feedback` (6 types used by admin and sync). The published APIs also disagree on tenant passing: catalog/crm/inventory/commerce rely on the ambient tenant, identity/store/subscription take an explicit schema checked by `TenantContext.requireCurrent`.

**Approach:** Same API pattern as 12.2a–d (read spec-12-2d's Decisions and Implementation Notes): `messaging/api`, `reporting/api`, `feedback/api`, all non-`shared` consumers rewired, published-module assertion extended to them; then align catalog/crm/inventory/commerce ports on the explicit `tenantSchema` + `TenantContext.requireCurrent` convention.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Making `verify()` blocking is **moved out of 12.2e**: it cannot pass while 107 cycles and all `shared → X` violations remain. It becomes the closing step of 12.6, after 12.2f (core extraction) and 12.3/12.4. Story 12.2e = remaining APIs + tenant alignment.
- `WhatsAppPort` and `NotificationPort` are capabilities other modules consume: move them (and their payload/result records) into `messaging.api`; their implementations stay internal.
- The draft-notification lookup catalog uses (`GetPendingDraftsCountUseCase`, `DraftPendingValidation`, `DraftNotificationRepository`) goes through a `messaging.api` query port.
- identity's daily-report use (`DailyReportFormatter`, `EndOfDayReportBuilder`) goes through a `reporting.api` port; `identity` lists `reporting :: api` only.

## Boundaries & Constraints

**Always:** REST/sync contracts, WhatsApp/push message texts, tables, Flyway unchanged; all test packages green; not-allowed 0; published-module assertion green for every module except `shared`, `sync` and `admin` (consumers only).

**Never:** no change to `shared` beyond imports forced by moves; no new behaviour; no blocking `verify()`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| WhatsApp sends (closure, report, plan activation, OTP-like flows) | as today | same texts and recipients | provider failures handled as today |
| Super-admin global push | admin endpoint | same `PushSendResult` and audit | unchanged |
| Draft products pending count (catalog) | drafts exist | same count | unchanged |
| Feedback submit via sync / admin list & handle | same payloads | same results | same domainCodes |
| Tenant-scoped port called with a schema ≠ bound tenant | programming error | `IllegalStateException` from `requireCurrent` | — |
| Architecture report | after change | 0 non-exposed violations from non-`shared` modules into any published module; no `identity ↔ reporting` cycle | — |

</frozen-after-approval>

## Code Map

- Consumers (violations report after 12.2d): messaging ← admin (`NotificationPayload`, `NotificationPort`, `PushSendResult`, `WhatsAppPort`), catalog (`GetPendingDraftsCountUseCase`, `DraftPendingValidation`, `DraftNotificationRepository`), commerce/identity/reporting/subscription (`WhatsAppPort`), reporting (`NotificationPayload`, `NotificationPort`); reporting ← identity (`DailyReportFormatter`, `EndOfDayReportBuilder`); feedback ← admin (`Feedback`, `HandleFeedbackUseCase`, `ListFeedbackUseCase`+query), sync (`Feedback`, `SubmitFeedbackUseCase`+command).
- `identity/package-info.java` still lists whole `"reporting"` — narrow it.
- Tenant alignment targets: `catalog/api`, `crm/api`, `inventory/api`, `commerce/api` port signatures and their adapters + callers (pass `TenantContext.getCurrentTenant()` at call sites like 12.2d did). `crm` ports already take a `tenantId` on some methods — unify naming to `tenantSchema`.
- `ApplicationModulesBaselineTest.PUBLISHED_API_MODULES` — add messaging, reporting, feedback.

## Tasks & Acceptance

**Execution:**
- [x] Create `messaging/api`, `reporting/api`, `feedback/api` (+ `events` where they publish), package-private adapters, null-validated input records.
- [x] Rewire consumers; narrow `allowedDependencies`; extend the published-module assertion.
- [x] Align catalog/crm/inventory/commerce ports on explicit `tenantSchema` + `TenantContext.requireCurrent`; bind the tenant in unit tests that call them without a context (production binds it in `JwtAuthFilter`).
- [x] Unit-test new adapters; record before/after counts and remaining cycles in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no module other than `shared` violates into any published module, `identity ↔ reporting` is gone, and the total decreases vs 270.
- Given all test packages run one at a time, then all pass (including every controller test that hits a tenant-scoped port).

## Implementation Notes

**Moves (`git mv`; `shared` edit is one import).** Into `messaging.api`: `WhatsAppPort`, `NotificationPort`, `NotificationPayload`, `PushSendResult` (implementations stay internal).

**Published APIs.**
- `messaging.api` (+ the four moved types): `DraftValidationCommandPort` (`recordPending(tenantSchema, PendingDraftInput)`, `acknowledge(tenantSchema, productId)`), `DraftValidationQueryPort.countPending(tenantSchema, actorRole)` (FORBIDDEN for non-OWNER, same use case). Adapter `messaging.notification.adapter.in.api.DraftValidationApiAdapter` (package-private, `requireCurrent`); it now derives the draft's tenant UUID from the schema (same `nameUUIDFromBytes` formula catalog used). Catalog's `CreateDraftProductUseCase` / `UpdateProductUseCase` / `ProductImportController` use it.
- `feedback.api`: `FeedbackSubmissionPort.submit(SubmitFeedbackInput)` → id (sync), `FeedbackInboxPort` (`list` → `Page<FeedbackView>`, `count`, `markHandled`, `markUnhandled`; admin controller, label service, alerts). Feedback is a `public`-schema table: no method is tenant-scoped; the tenant is data (`SubmitFeedbackInput.tenantSchema`, null-checked with `userId`). Adapter `feedback.feedback.adapter.in.api.FeedbackApiAdapter`. Admin JSON unchanged (`AdminFeedbackDto` mapped from `FeedbackView`).
- `reporting`: `reporting/api` package (named interface, no types yet) + `reporting.export.domain.event` named `events` (`DataExportedEvent`).

**Deviation — identity ↔ reporting.** The Decision said identity goes through a `reporting.api` port, but reporting already depends on `identity :: api`, so any identity → reporting edge (named interface or not) keeps the module cycle the AC requires gone. Applied the 12.2b/12.2d "port declared by the needing module" pattern instead: `identity.api.DailyReportTextPort` + `DailyReportTextRequest`, implemented by `reporting.report.adapter.in.api.DailyReportTextAdapter` (same builder + formatter, `requireCurrent`). `identity` lists no reporting dependency at all; the test-report text is unchanged.

**Tenant alignment (catalog/crm/inventory/commerce).** Every method of the 18 published ports now takes `String tenantSchema` first; the five methods that had a trailing `String tenantId` (category `createWithId`/`toggleActive`/`rename`, client/supplier `archive`/`unarchive`) lost it, and `CloseDayInput.tenantId` was removed (`closeDay(tenantSchema, input)`). Every adapter calls `TenantContext.requireCurrent(tenantSchema)` first and passes `tenantSchema` where it passed `tenantId`. All callers (sync handlers, services, listeners, controllers) pass `TenantContext.getCurrentTenant()`; the async/scheduled callers (`SaleDraftValidatedNotificationListener`, `ExportJobRunner` → `EndOfDayPdfModel`, onboarding) already bind the tenant before calling. Port Javadoc/package-info updated.

**allowedDependencies.** `messaging` → `messaging :: api` in admin, catalog, commerce, identity, reporting, subscription; `feedback` → `feedback :: api` in admin, sync; `reporting` removed from identity. `PUBLISHED_API_MODULES` += messaging, reporting, feedback (unpinned now: shared, sync, admin); classifier test updated.

**Violations (`target/modulith-violations.txt`).**

| | Before (12.2d) | After |
|---|---|---|
| Total | 270 | **149** |
| Cycles | 107 | 55 |
| Non-exposed (all from `shared`) | 163 (36 from non-shared) | 94 (0 from non-shared) |
| Not-allowed | 0 | 0 |
| shared → messaging / reporting | 6 / 27 | 0 / 0 (`WhatsAppPort`, `DataExportedEvent` now exposed) |

Remaining direct 2-module cycles: `catalog ↔ messaging`, `commerce ↔ messaging`, `identity ↔ messaging` (messaging listens to their events, they call `messaging :: api`) — to break with the 12.3 event catalogue; `identity ↔ subscription` (12-2f / 16-8). `identity ↔ reporting` is gone; no cycle involves reporting or feedback.

**Tests.** New: `DraftValidationApiAdapterTest`, `FeedbackApiAdapterTest`, `DailyReportTextAdapterTest` (mapping, delegation, tenant guard, null-validated inputs) and test-only `DraftValidationApiTestSupport`, `FeedbackApiTestSupport`, `DailyReportTextApiTestSupport` so consumer tests keep driving the real adapters over their internal mocks. Tenant alignment: stubs/verifies gained the tenant argument (`any()` when the call already used matchers/captors), `getArgument(n)` indexes shifted, and tests reaching a real adapter bind/clear `TenantContext` (`bindTenantContext12e`). `ProductImportControllerSecurityTest` mocks `DraftValidationQueryPort`.

**Accepted by the orchestrator (2026-10-29).** The inverted `identity.api.DailyReportTextPort` (declared by identity, implemented by reporting) instead of a `reporting.api` port, and the draft-validation command port (`DraftValidationCommandPort`) next to the query port in `messaging.api`.

**Review fixes (2026-10-29, after the first verification).**
- Sync handlers pass their `tenantId` argument (not `TenantContext.getCurrentTenant()`) to every port call, so `requireCurrent` really compares the claim with the bound tenant (supersedes the 12.2d choice); `AbstractSyncOperationHandler` logs unexpected exceptions (guard failures included) at ERROR with op id/type before the unchanged `REJECTED / INTERNAL_ERROR`. Sync tests verify the tenant slot with `eq(TENANT_ID)`.
- Every catalog/crm/inventory/commerce adapter test has `tenantGuard_otherOrMissingSchemaFailsFast`.
- `FeedbackSubmissionPort.submit`: when a tenant is bound, `input.tenantSchema()` must equal it (`requireCurrent`); platform-level calls (none bound) stay allowed and documented. `FeedbackInboxPort` documents that callers enforce SUPER_ADMIN.
- `SendTestReportService` builds the report for its `tenantId` argument and logs guard / validation failures (`IllegalStateException | DomainException`) at ERROR — result unchanged (not sent). `DailyReportTextRequest` rejects a blank store name and `windowStart` after `windowEnd` (`VALIDATION_ERROR`).
- `DraftValidationQueryPort.countPending(tenantSchema)` lost its role parameter; the OWNER-only check (same FORBIDDEN code and message) moved to `ProductImportController`; the internal `GetPendingDraftsCountUseCase` (and its test) was removed — the adapter reads the repository.
- Javadoc: tenant parameter is checked against the bound tenant, repositories read the bound tenant; `reporting :: api` intentionally empty (boundary pin), `reporting :: events` has no business consumer yet; `NotificationPort` names the `@Primary` implementation. `AdminAlertService` field renamed `feedbackInbox`. `TenantContext` imports moved to sorted position; the tenant is read once into a local in `RecordSaleService`, `ValidateSaleService`, `SaleValidationCascadeService`, `FullScopeResolver`, `PartialScopeResolver`. Classifier test covers internal access into reporting and feedback.

**Verification (2026-10-29).** `compile test-compile` OK. 14 packages sequential: architecture 13, catalog 234, crm 103, inventory 370, commerce 261, sync 496, identity 386, messaging 145, reporting 388, shared 666, admin 504, store 60, subscription 66, feedback 50 — 3,742 tests, 0 failures/errors. Non-shared violation grep: 0. Report status text now says blocking at the end of 12.6 (per the Decision). Not live-tested on a device/dev backend.

### Verification & live run (2026-10-29, after review patches)

14 packages sequential: 3,757 tests, 0 failures/errors. Violations 149 (55 cycles, 94 non-exposed all from `shared`, 0 not-allowed). Live (dev backend): API smoke (products, suppliers, clients, transfers, overview, pull, supplier/client create, registration, trigger-weekly) OK; Redmi POS sale through sync push with handlers passing their own `tenantId` → server 4 sales / 28,000, stock 22→21; backend log 0 ERROR. Observation: app subscription screen shows « Produits 0 » while `/subscription/me` returns 2 (local computation, pre-existing) → v1s-16-25.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (12-2e)
- verdicts: 24 findings — high 0, medium 6, low 14, false 2, maybe-false 0 (+ intent-alignment notes)
- findings:
  - `[medium]` `[patch]` B1/E3 sync handlers drop their `tenantId` and pass the context → guard can never fire — handlers pass `tenantId` (supersedes the 12.2d call-site choice)
  - `[medium]` `[patch]` E4 guard failure in sync swallowed as INTERNAL_ERROR without cause — ERROR log with exception
  - `[medium]` `[patch]` V1/B3/IA-c guard untested on 14 catalog/crm/inventory/commerce adapters — tests added
  - `[medium]` `[patch]` V2 sync handler tests accept any tenant (`any()`) — `eq(TENANT_ID)`
  - `[medium]` `[patch]` B4 feedback submission accepts another tenant's schema — `requireCurrent` when bound
  - `[medium]` `[patch]` E2 `SendTestReportService` builds from context while resolving phone for its argument — argument passed
  - `[low]` `[patch]` E1 guard/validation failure reported as WhatsApp failure — separate catch + ERROR log
  - `[low]` `[patch]` B10/E5 `DailyReportTextRequest` accepts inverted window / blank store name — VALIDATION_ERROR
  - `[low]` `[patch]` B8 role check by free string inside `DraftValidationQueryPort` — moved to controller
  - `[low]` `[patch]` B11 `FeedbackInboxPort` admin-only actions undocumented — Javadoc
  - `[low]` `[patch]` B2 Javadoc overstates tenant scoping — corrected
  - `[low]` `[patch]` B7 classifier test lacks reporting/feedback cases — added
  - `[low]` `[patch]` B6 `PUBLISHED_API_MODULES` Javadoc stale — updated
  - `[low]` `[patch]` B14 empty `reporting :: api` / unconsumed `reporting :: events` — documented as boundary pins
  - `[low]` `[patch]` B12 stale names/Javadoc (`handleFeedback`, `@Primary` note, `SubmitFeedbackSyncHandler`) — fixed
  - `[low]` `[patch]` B13 misplaced imports / repeated `getCurrentTenant()` in loops — fixed
  - `[low]` `[defer]` B5 `NotificationPort` keeps `tenantId`, no guard → routed → 12-2f
  - `[low]` `[defer]` B9 catalog calls messaging synchronously to track drafts; could be events (`ProductCreatedProgressivelyEvent`/`ProductActivatedEvent`) removing catalog → messaging → routed → 12-3
  - `[low]` `[reject]` B10b test report sends an empty window (`now, now`) — pre-existing V1 behaviour (test message proves delivery, not content)
  - `[low]` `[reject]` IA-e identity tests build the port with reporting internals — test scope, not covered by module rules; acceptable
  - `[low]` `[reject]` IA-b draft command port beyond the "query port" wording — accepted (catalog's writes need it); recorded
  - `[low]` `[reject]` IA-d no explicit test for the absent identity ↔ reporting cycle — enforced by the not-allowed assertion (identity lists no reporting)
  - `[false]` `[reject]` IA-a / E6 reporting port not in `reporting.api` — the literal layout would recreate the cycle (reporting → identity :: api); inversion accepted by orchestrator 2026-10-29
  - `[false]` `[reject]` public export download without tenant — controller binds the tenant from the signed link before any port call (verified)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green (do not skip packages you did not edit: tenant alignment touches callers everywhere)
- `grep -E "within module '|depends on (module|named interface\(s\))" keevo/backend/target/modulith-violations.txt | grep -v "^- Module 'shared'" | grep -v "^- Cycle" | wc -l` -- expected: 0
