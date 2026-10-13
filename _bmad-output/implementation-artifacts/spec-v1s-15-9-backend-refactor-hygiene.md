---
title: 'v1s-15-9 — Backend refactor hygiene (shared actor helpers, constants, casts, account-deletion races)'
type: 'refactor'
created: '2026-10-11'
status: 'done'
baseline_revision: 'f054bf5b0d3eace452a642989f8a70d0b5e7d7f9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: [multiple-goals, oversized]
deferred:
  - summary: >-
      Inventory quick-add creates an ACTIVE product without the plan product limit (routed to v1s-14-13).
    evidence: |-
      QuickAddProductService.execute saves a Product with ProductStatus.ACTIVE and never takes the product-limit lock nor calls PlanLimitGuard.checkProductLimit, unlike CreateProductUseCase. Pre-existing, not caused by v1s-15-9.
    location: >-
      keevo/backend/src/main/java/com/keevo/inventory/counting/application/service/QuickAddProductService.java:85-95
    severity: medium
  - summary: >-
      Legacy DELETED tenants from the old forceDelete may still hold their schema and memberships (routed to 10-7-ops-hardening-backups-staging).
    evidence: |-
      Before v1s-15-9 forceDelete only flipped the status; the new forceDelete refuses DELETED tenants. Settled by a prod query: status = 'DELETED' AND to_regnamespace(schema_name) IS NOT NULL.
    location: >-
      keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java forceDelete
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** 20 routed items in the Story 15.9 brief (`epics-remediation-audit.md`). RBAC/actor helpers are copy-pasted across ~12 controllers and handlers with drifting behaviour (some fail closed, some do not). Role names are magic strings. Several `((Number) x).intValue()` casts silently truncate. Three account-deletion races remain after v1s-15-11: `cancelDeletion` blind-writes ACTIVE over a DELETED tenant, cancel can reactivate a tenant whose schema is already dropped, and admin `forceDelete` marks DELETED without dropping anything.

**Approach:** One static helper `CurrentActor` in `shared.infrastructure.security`, used by every listed caller. A shared integral-number guard for sync handlers and a shared value converter for delta providers. Account deletion: a conditional cancel UPDATE that also refuses a dropped schema or an expired grace period; `forceDelete` goes through the scheduler's per-tenant deletion routine. Each item is resolved or rejected with a reason in the epic brief.

## Boundaries & Constraints

**Always:**
- Behaviour-preserving refactors, except where an item asks for a behaviour change.
- Every concurrency claim about account deletion is proven on real PostgreSQL. Those tests extend `FullContextIntegrationTest`, and the API-side `AccountDeletionService.cancelDeletion` bean runs concurrently with the scheduler routine.
- `mvn test` stays at 0 failures / 0 errors.
- New DB tests use unique tenants and never assert global counts.

**Never:**
- New Flyway migration or new table column.
- Touching endpoints or response shapes beyond what an item states.
- Sweeping every `(UUID) getPrincipal()` in the codebase: only the files the items name are migrated.
- Writing to deferred-work.md.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner cancel, normal | DELETION_PENDING, future date, schema present | ACTIVE, date NULL, cancelled event published | — |
| Cancel races scheduler | scheduler holds the row lock (`FOR UPDATE`) mid-deletion; cancel already read DELETION_PENDING | cancel's UPDATE waits on the lock, then matches 0 rows after the DELETED commit; tenant stays DELETED, schema gone | `DomainException DELETION_NOT_PENDING` |
| Cancel after a post-DROP failure | DELETION_PENDING, schema dropped (`to_regnamespace` NULL) | 0 rows; still DELETION_PENDING; the next scheduler run completes the deletion | `DELETION_NOT_PENDING` (owner); `TENANT_NOT_FOUND` (admin) |
| Owner cancel after the grace period | DELETION_PENDING, `deletion_scheduled_at <= now()` | refused, unchanged | `DELETION_NOT_PENDING` |
| Admin forceDelete | ACTIVE, SUSPENDED or DELETION_PENDING tenant with an OWNER | schema dropped; memberships, orphan users and tokens purged; DELETED; final WhatsApp after commit | — |
| forceDelete, invalid target | DELETED tenant, system admin tenant, or unknown id | nothing changes | `TENANT_NOT_FOUND` |
| Sync integral fields | `quantity: 1.5`, `price: 3e10`, `discountAmount: "x"` | operation REJECTED with `Invalid <field>: …` | no truncation |
| Delta numbers | `BigDecimal 12`, `Long` > int, `BigDecimal 12.5`, boolean `"t"` | 12, the long unchanged, 12.5 unchanged, `true` | unknown type → `IllegalArgumentException` |

</intent-contract>

## Code Map

New shared helper `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/CurrentActor.java` (final class, static methods only). Each method reads `SecurityContextHolder`:
- `authentication()`
- `actorId()`: the principal as a `UUID`, parsing its `toString()` when the principal is a String. Throws `FORBIDDEN` when there is no authentication.
- `hasRole(Role)`, `isOwner()`, `isEmployee()`, `isSuperAdmin()`
- `role()`: "OWNER" > "EMPLOYEE" > "SUPER_ADMIN" > null
- `requireOwner(String msg)`, `requireSuperAdmin()`: both throw `DomainException FORBIDDEN`.
- `assignedStoreId()`: fails closed. No authentication → `FORBIDDEN "No authenticated actor"`. Returns the EMPLOYEE's `AuthDetails.storeId` when present. An EMPLOYEE without a store → `FORBIDDEN "EMPLOYEE token missing assigned store"`. Otherwise null. This is the exact body of `AbstractSyncOperationHandler.extractAssignedStoreId:55-70`.
- `actorLabel()`: `AuthDetails.firstName` when not blank. Otherwise "Un employé" for EMPLOYEE, "Le propriétaire" for OWNER, "Un utilisateur" for anyone else. It never returns a raw UUID.

Role strings come from `com.keevo.identity.auth.domain.model.Role` (`"ROLE_" + Role.X.name()`). `shared` already imports identity in `JwtAuthFilter` and `AdminAccountInitializer`.

Adopters (delete their private copies):
- `extractAssignedStoreId`:
  - `catalog/stock/adapter/in/web/StockController.java:209`
  - `catalog/stock/adapter/in/web/StockTransferController.java:168`
  - `commerce/sale/adapter/in/rest/DayClosureController.java:181`
  - `commerce/sale/adapter/in/rest/PendingSaleController.java:130`. Previously null auth → null; now fail-closed, and the endpoints are authenticated anyway.
  - `inventory/counting/adapter/in/rest/InventorySessionController.java:186`
  - `inventory/counting/adapter/in/rest/InventoryCountController.java:178`
- `isOwnerRole` / `requireOwner`:
  - `PendingSaleController:118`
  - `DayClosureController:195` (and `isEmployeeRole`)
  - `StockTransferController`
  - `store/store/adapter/in/rest/StoreController.java:118`
  - `identity/auth/adapter/in/rest/AccountController.java:70-90`
  - `catalog/category/adapter/in/web/CategoryController.java:163`
- `requireSuperAdmin` (keep as a second barrier, now one implementation): `admin/tenant/.../AdminTenantController.java:104`, `AdminSubscriptionController.java:73`, `admin/catalog/.../AdminCatalogController.java:~112`, `admin/sync_monitoring/.../AdminSyncMonitoringController.java:~79`.
- `SUPER_ADMIN` literals: `SecurityConfig.java:115` `hasRole(Role.SUPER_ADMIN.name())`; `AdminAccountInitializer.java:118,139`. The `JwtAuthFilter:181` reference in the brief is stale: role names are not compared there any more (lines 137/246 build `ROLE_` + the claim).
- `sync/sync/application/handler/AbstractSyncOperationHandler.java:55-100`: `extractAssignedStoreId` / `isOwnerRole` / `actorRole` become one-line delegates to `CurrentActor` (`AbstractSyncOperationHandlerTest` calls them). The integral guard is hoisted from `StockAdjustSyncHandler.java:48-63`, and its messages are kept for `field = "quantity"`:
  - `protected static int requireIntegralInt(Object raw, String field)`
  - `protected static Integer optionalIntegralInt(Object raw, String field)` (null → null)
- Actor extraction:
  - `catalog/product/adapter/in/web/ProductController.java:76,145,178,235-262`: `extractRole` / `extractActorName` → `CurrentActor.role()/actorLabel()/actorId()`.
  - `catalog/stock/domain/service/StockOperationService.java:148-176`: role + label via `CurrentActor`; keep the try/catch around the store lookup.
  - `inventory/counting/application/service/QuickAddProductService.java:107-133`: the same, plus inject `store.store.domain.port.out.StoreRepository` and resolve `storeName` from `session.getStoreId()`, mirroring `StockOperationService.167`.

Other items:
- `CreateProductUseCase.java:106-160`: order becomes lock → idempotency → `validateInput` → name uniqueness → plan limit → SKU → build → save, the same plan-check position as `CreateDraftProductUseCase:111-123`.
- `shared/infrastructure/web/ApiResponseWrapper.java:48`: `paginated` throws `IllegalArgumentException` when page < 0, size < 1, totalElements < 0, totalPages < 0, or totalPages is inconsistent with totalElements (0 ⇔ 0). A page past the end stays valid (Spring returns it empty; `GET ?page=99` must not become a 500). The real caller (`AdminFeedbackController`) passes Spring `Page` values that always satisfy this.
- Delta providers (`sync/sync/application/provider/*DeltaProvider.java`; 7 `num`, 2 `bool`): new package-private `DeltaValues` with static `num(Object)` and `bool(Object)` (rules in the matrix). Integer/Short/Byte → int. Long → int when it fits, else long. BigDecimal/BigInteger integral → int or long, non-integral → unchanged. Every private copy is deleted.
- `TenantSchemaSyncService.java:116`: already resolved by v1s-13-12, which gates through `TenantSchema.isValid` and a precompiled `SCHEMA_PATTERN`. Ticked, no code.
- `messaging/whatsapp/domain/port/out/WhatsAppPort.java:17`: `isConfigured()` becomes abstract. `Wassender` (no override today) gets `return true` with a comment. Check test doubles with `grep -rn "implements WhatsAppPort\|new WhatsAppPort" src/test`.
- Feedback:
  - New `feedback/feedback/domain/port/in/ListFeedbackUseCase.java` (`Page<Feedback> list(ListFeedbackQuery(int page, int size, String priority, String screenContext))`).
  - New `ListFeedbackService` (`@Transactional(readOnly = true)`, size capped at 100, priority first, then screenContext).
  - `AdminFeedbackController` injects the use case.
  - `Feedback.java`: `requireNonNull` on id, type, description, tenantId, userId, submittedAt and priority. These match the `V7` NOT NULL columns; appVersion/platform/screenContext stay nullable.
- Tenant port `identity/auth/domain/port/out/TenantRepository.java` (+ adapter, + `TenantSpringRepository`):
  - `List<Tenant> findDeletionsDueBy(Instant now)`, via the derived query `findByStatusAndDeletionScheduledAtLessThanEqual`.
  - `boolean cancelPendingDeletion(String schemaName)`: a native `@Modifying` `UPDATE public.tenants SET status='ACTIVE', deletion_scheduled_at=NULL WHERE schema_name=:s AND status='DELETION_PENDING' AND deletion_scheduled_at > now() AND to_regnamespace(schema_name) IS NOT NULL`, returns rows == 1.
- `identity/auth/application/service/AccountDeletionService.java:116-136`: keep the find and the status pre-check for messages, then `cancelPendingDeletion`. False → `DELETION_NOT_PENDING` ("deletion already executing, executed or past its grace period"). The event is published only on success.
- `shared/infrastructure/scheduling/AccountDeletionScheduler.java`:
  - `executeDeletions` uses `findDeletionsDueBy(now)`.
  - Extract `runTenantDeletion(UUID, boolean requireExpired)`: per-tenant `TransactionTemplate`, then WhatsApp after commit.
  - New public `boolean deleteNow(UUID tenantId)`, which calls `requireExpired=false` and propagates exceptions. It is not annotated with `@SchedulerLock`.
  - Update the `LOCK_TENANT_SQL` javadoc: the residual race is closed.
- `admin/tenant/application/service/AdminTenantService.java:157-178`:
  - `cancelDeletion` adds `AND to_regnamespace(schema_name) IS NOT NULL` to its UPDATE (the admin may still rescue an expired grace period).
  - `forceDelete` is no longer `@Transactional`. It auto-commits `UPDATE … SET status='DELETION_PENDING', deletion_scheduled_at=now() WHERE id=? AND id<>ADMIN AND status<>'DELETED'` (0 rows → `TENANT_NOT_FOUND`), then `accountDeletionScheduler.deleteNow(id)`; false → `DomainException DELETION_NOT_PENDING`.
  - The committed marker is the "DELETING" marker: if the routine fails, the expired DELETION_PENDING row is retried by the daily run, and the owner cannot cancel it because the date is ≤ now.
- Sync handlers:
  - `SaleSyncHandler.java:62,73-75`: discountAmount optional, prices and quantity required.
  - `TransferSyncHandler.java:61`
  - `InventoryCountSyncHandler.java:73-74`
  - `ValidateSaleSyncHandler.java:58`: map values.
  - `ProductSyncHandler.java:77-80,93-96,125,149-151`: optional fields.
  - `StockAdjustSyncHandler`: drop its copy and call the base with `"quantity"`.

Existing tests to update: `AccountDeletionServiceTest`, `AccountDeletionSchedulerTest` (stub `findDeletionsDueBy`), `AdminTenantServiceTest` (constructor), `AdminFeedbackControllerTest` (mock the use case), `SendTestReportService`/WhatsApp doubles if any.

## Tasks & Acceptance

**Execution:**
- `CurrentActor.java` + `CurrentActorTest.java` (new): helper + unit tests covering each branch, including the `actorLabel` fallbacks and fail-closed `assignedStoreId`.
- The 12 controllers/services listed above, `SecurityConfig`, `AdminAccountInitializer`, `AbstractSyncOperationHandler`: switch to `CurrentActor` / `Role` and delete the private copies.
- `ProductController`, `StockOperationService`, `QuickAddProductService`: centralised actor extraction, fallback label, and QuickAdd `storeName`. Add or extend tests: `QuickAddProductServiceTest` asserts storeName on the event; a test asserts that a missing firstName gives "Un employé", not the UUID.
- `CreateProductUseCase`: reorder; a test proves the plan limit is rejected before the SKU-uniqueness check.
- `ApiResponseWrapper.paginated` + `ApiResponseWrapperTest`: validation.
- `DeltaValues` + `DeltaValuesTest`; the 7 providers delegate to it.
- `WhatsAppPort` abstract `isConfigured` + adapters / test doubles.
- `ListFeedbackUseCase`, `ListFeedbackService` (+ test), `AdminFeedbackController` (+ test update), `Feedback` null-safety (+ test).
- `AbstractSyncOperationHandler` integral helpers + the 6 handlers; tests for fractional and out-of-range values in Sale, Transfer, InventoryCount, ValidateSale and Product.
- `TenantRepository` port/adapter/spring repo, `AccountDeletionService`, `AccountDeletionScheduler`, `AdminTenantService` (+ the Mockito tests above).
- `shared/infrastructure/scheduling/AccountDeletionConcurrencyPostgresIntegrationTest.java` (new, extends `FullContextIntegrationTest`, `NOT_SUPPORTED`; reuse the seeding of `AccountDeletionSchedulerPostgresIntegrationTest`). It covers matrix rows 2, 3, 4 and 5–6 with the real `AccountDeletionService` and `AdminTenantService` beans and a hand-built scheduler. Row 2 builds the scheduler with a `TenantSchemaProvisioner` spy that blocks inside `dropSchemaForDeletion` on a latch. The test asserts through `pg_stat_activity` that the cancel's UPDATE is waiting on a lock before it releases the latch.
- Epic brief: tick all 20 items with their resolution. Sprint status: done.

**Acceptance Criteria:**
- Given the source tree, when grepping `src/main/java`, then no private `extractAssignedStoreId`, `isOwnerRole`, `requireOwner` or `requireSuperAdmin` implementation and no `"ROLE_SUPER_ADMIN"` or `hasRole("SUPER_ADMIN")` literal remain outside `CurrentActor`. No `((Number) …).intValue()` remains in `sync/sync/application/handler` or `provider`.
- Given the full backend suite, when `mvn test` runs, then 0 failures and 0 errors, and the new concurrency tests pass on PostgreSQL 16.
- Given each of the 20 epic items, when the story closes, then it is ticked with a resolution or a rejection reason.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 39 findings (the blind layer's layering/QuickAdd bullet is logged as two rows) — high 0, medium 3, low 28, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent-alignment a) owner cancel now refused after the grace period, a rule no item asked for — decision recorded in Design Notes; it is what makes forceDelete's committed marker safe, and an expired tenant is already due for deletion.
  - `[low]` `[patch]` (intent-alignment b) only the owner cancel was raced against the scheduler; the admin cancel (no date guard) is the path that really overlaps the daily run — added `adminCancel_racingDailyRun_waitsThenRefused` (real AdminTenantService bean, real-clock daily path, pg_stat_activity lock wait, then TENANT_NOT_FOUND, DELETED, schema gone).
  - `[low]` `[patch]` (intent-alignment c) forceDelete only tested sequentially; concurrent daily-run completion unhandled — grouped with the blind-hunter/edge-case forceDelete false-negative row (fix below).
  - `[low]` `[reject]` (intent-alignment d) `DeltaStockStrategy` still narrows payload quantities with intValue — it only estimates conflict deltas; the owning handlers now reject a fractional/out-of-range quantity before anything is applied, so the estimate never reaches state. Not one of the brief's named sites.
  - `[false]` `[reject]` (intent-alignment e) green suite not shown — `mvn -o test` 2301 tests, 0 failures, 0 errors (before patches; re-run after patches below).
  - `[medium]` `[patch]` (verification-gap) PendingSaleController fail-closed EMPLOYEE-without-store path untested — added POST /validate and GET /pending 403 cases with verifyNoInteractions.
  - `[medium]` `[patch]` (verification-gap) StockOperationService fallback actor label never exercised — added a captor test: actorName "Un employé", actorRole EMPLOYEE.
  - `[low]` `[reject]` (blind) CurrentActor does not fail closed for an AnonymousAuthenticationToken; actorId() 500 on non-UUID principal — unreachable: SecurityConfig `anyRequest().authenticated()` and JwtAuthFilter always sets a UUID principal on protected paths; same behaviour as every removed copy; fix would add guards.
  - `[low]` `[reject]` (blind) forceDelete of a SUSPENDED tenant can end ACTIVE if the admin then cancels — admin-initiated only; restoring the prior status needs a new column (forbidden: no migration). Owner path is closed by the date guard.
  - `[low]` `[patch]` (blind) forceDelete reports DELETION_NOT_PENDING when a concurrent daily run deleted the tenant — on `deleteNow` false, re-read status; DELETED returns normally (+ 2 unit tests).
  - `[false]` `[reject]` (blind) forceDelete failures propagate unlogged — GlobalExceptionHandler `@ExceptionHandler(Exception.class)` logs at ERROR with stack trace; the tenant stays an expired DELETION_PENDING row retried by the daily run (tested).
  - `[low]` `[reject]` (blind) owner guard uses DB now() while the scheduler uses the app Clock — skew is seconds against a 30-day window; both clocks pre-existed (requestDeletion uses Instant.now()).
  - `[low]` `[reject]` (blind) admin cancel returns TENANT_NOT_FOUND, no updated_at, ADMIN_TENANT_ID concatenated — pre-existing code/contract; the constant is a compile-time literal, not input.
  - `[low]` `[reject]` (blind) AdminTenantServiceTest cancel tests assert SQL text — the behaviour is proven on PostgreSQL by the concurrency integration test (rows 3, 4 and the admin race).
  - `[low]` `[patch]` (blind) leftover role-extraction copies (ProductImportController.extractRole, SubscriptionController ROLE_ literals, DeviceTokenController findFirst) — replaced with CurrentActor; `@PreAuthorize("hasRole('SUPER_ADMIN')")` SpEL kept (annotation needs a constant expression).
  - `[low]` `[patch]` (blind) hand-written `(UUID) getPrincipal()` casts left in touched files — replaced with `CurrentActor.actorId()` in Stock, PendingSale, Employee, InventoryCount, Store, AdminSubscription, DeviceToken controllers; private helpers deleted.
  - `[medium]` `[patch]` (blind) PendingSaleController behaviour change without controller tests — same root cause as the verification-gap row; same fix.
  - `[false]` `[reject]` (blind) DeltaValues throws on unexpected types / num(null)→0 / archived raw — unknown-type throw is the spec matrix contract; null→0 is the providers' pre-existing contract; `archived` is a PostgreSQL boolean column already returned as Boolean.
  - `[low]` `[patch]` (blind) paginated javadoc claims a full consistency check — javadoc narrowed to what is checked (negatives, totalPages 0 iff totalElements 0).
  - `[low]` `[patch]` (blind) ListFeedbackService passes page<0 / size<1 to PageRequest.of (500) — clamped (+ test).
  - `[low]` `[patch]` (blind) QuickAddProductService store lookup shares the actor try/catch and log message — separate guarded block (+ edge-case rollback-only row).
  - `[low]` `[reject]` (blind) domain/application services import shared.infrastructure.security — pre-existing (AuthDetails/SecurityContextHolder were already imported there); ArchUnit is in warning mode.
  - `[false]` `[reject]` (blind) sprint-status done while spec in-review — trackers are finalised together at the end of this run.
  - `[false]` `[reject]` (blind, minor) role() OWNER-first vs actorLabel() EMPLOYEE-first — a JWT carries exactly one role authority (JwtAuthFilter:246), so the order never matters.
  - `[low]` `[reject]` (blind, minor) CurrentActor import placed out of order — cosmetic, no tooling enforces import order.
  - `[low]` `[reject]` (blind, minor) "Invalid initialStockEntries" does not name the key — diagnostic nicety; the op is still rejected.
  - `[low]` `[patch]` (blind, minor) empty "Tenant helper" banner in AccountController — removed.
  - `[low]` `[reject]` (blind, minor) ProductSyncHandler hardcodes "OWNER" — the sync module does not import identity's Role; value matches the use-case contract.
  - `[low]` `[reject]` (edge-case) actorId non-UUID principal → 500 — same as the anonymous-token row (unreachable on protected paths).
  - `[low]` `[reject]` (edge-case) assignedStoreId fails open for an anonymous token — same root cause and refutation as above.
  - `[low]` `[patch]` (edge-case) ListFeedbackService page/size bounds — same fix as the blind row.
  - `[low]` `[patch]` (edge-case) paginated inconsistent totals pass — javadoc narrowed (same root cause as the blind row); Spring Page always supplies consistent values.
  - `[low]` `[patch]` (edge-case) WassenderWhatsAppAdapter.isConfigured true with a blank token while the constructor logs "falling back to NoOp" — now token non-blank (+ test).
  - `[low]` `[reject]` (edge-case) Feedback requireNonNull NPE → 500 — those inputs already failed at INSERT on V7 NOT NULL columns; controller validates type/description, TenantContext is always set on authenticated paths, sync handler catches into REJECTED.
  - `[low]` `[patch]` (edge-case) QuickAdd store lookup with null storeId could mark the transaction rollback-only — guarded by `session.getStoreId() != null` in its own try/catch.
  - `[false]` `[reject]` (edge-case) DELETION_PENDING with NULL date can never be cancelled — no path writes that state: requestDeletion sets now+30d, forceDelete sets now().
  - `[low]` `[patch]` (edge-case) forceDelete false negative when a daily run wins — same fix as the blind row.
  - `[false]` `[reject]` (edge-case) DeltaValues passes fractional/NaN through — every delta numeric column is INTEGER/BIGINT; decision recorded in Design Notes.
  - `[low]` `[reject]` (edge-case) actorRole() now returns "SUPER_ADMIN" — a SUPER_ADMIN has no tenant schema and cannot reach sync/product tenant endpoints; ProductController's removed findFirst copy already returned it.

### 2026-10-13 — Follow-up review pass
- verdicts: 28 findings — high 0, medium 4, low 16, false 7, maybe-false 1
- scope: story-scoped diff (files of `237e13a6`, `baseline_revision` → current code), so later stories' edits on the same files (v1s-14-6, 14-15, 13-11, 13-13, 16-15, 18-1, 18-6) appear in it.
- findings:
  - `[low]` `[reject]` intent a: delta-number row tested at the helper only — every provider delegates to `DeltaValues.num/bool` in one line; `DeltaValuesTest` pins the contract.
  - `[false]` `[reject]` intent b: integral row tested at the handler, not `/sync` — the handler is where the item's truncation lived; the controller only dispatches.
  - `[medium]` `[patch]` intent c: forceDelete failure and its committed marker never run on PostgreSQL — grouped with verification-gap 1 (fix below). The forceDelete-vs-daily-run re-read stays covered by unit tests (low).
  - `[low]` `[reject]` intent d: forceDelete may now answer DELETION_NOT_PENDING — pass-1 patch, documented in the javadoc; only on a concurrent cancel.
  - `[false]` `[reject]` intent e: changes outside the contract (ListFeedback, Feedback guards, paginated, Wassender, QuickAdd, SecurityConfig exports path…) — brief items of 15-9 or later stories in the diff range; the exports path is v1s-18-1.
  - `[false]` `[reject]` intent f: no evidence on the epic brief — the brief was ticked in `237e13a6` (outside the code diff).
  - `[false]` `[reject]` blind 1: diff mixes other stories — diff range by construction (see scope).
  - `[low]` `[patch]` blind 2: raw `(UUID) getPrincipal()` casts left — fixed in the two files the story migrated (`SubscriptionController`, `ProductImportController` → `CurrentActor.actorId()`); the other controllers listed are not named by the items (Never: no codebase-wide sweep).
  - `[low]` `[reject]` blind 3: `assignedStoreId` fails open for an anonymous token — carried (pass 1, unreachable on protected paths).
  - `[low]` `[reject]` blind 4: `actorId()` 500 on a non-UUID principal / FORBIDDEN vs UNAUTHORIZED — carried (pass 1).
  - `[low]` `[reject]` blind 5: forceDelete overwrites SUSPENDED — carried (pass 1, needs a new column).
  - `[low]` `[reject]` blind 6: application layer imports the infrastructure scheduler / CurrentActor — carried (pass 1, ArchUnit in warning mode).
  - `[low]` `[reject]` blind 7: `requestDeletion` read-then-save, `Instant.now()`, DB vs app clock — pre-existing and outside the three races of the intent; the overwrite needs an admin suspension or force-delete in the same instant as the owner request; clock skew carried (pass 1).
  - `[medium]` `[defer]` blind 8: quick-add during inventory creates an ACTIVE product with no plan limit — pre-existing (not touched by the guard reorder) → routed to v1s-14-13 (plan-limit enforcement owner, reopened in-progress).
  - `[false]` `[reject]` blind 9: `DeltaValues` NaN / fractional / raw `archived` — carried (pass 1).
  - `[low]` `[reject]` blind 10: `paginated` IllegalArgumentException maps to 500 — its only caller clamps inputs; a 500 on an impossible page is the intended loud failure.
  - `[low]` `[reject]` blind 11: integral guard does not check sign; hand-written `discountAmount` ternary; `initialStockEntries` key — the item is truncation, sign checks belong to the use cases; the rest carried (pass 1).
  - `[low]` `[reject]` blind 12: DeviceToken OWNER fallback, double auth lookup, ADMIN_TENANT_ID concat, feedback filter precedence, Spring `Page` in a port, no admin audit event — pre-existing or cosmetic; the OWNER fallback is the pre-existing default for a role-less token, which `JwtAuthFilter` never issues.
  - `[low]` `[reject]` edge 1: SUSPENDED → force-delete fails → admin cancel → ACTIVE — carried (pass 1).
  - `[maybe-false]` `[defer]` edge 2: legacy DELETED tenants with a leaked schema can no longer be force-deleted — depends on prod data → routed to 10-7-ops-hardening-backups-staging with the settling query (if true: medium).
  - `[low]` `[reject]` edge 3: a failing store lookup in QuickAdd marks the transaction rollback-only — any PostgreSQL error aborts the transaction anyway; the label lookup cannot fail otherwise.
  - `[low]` `[reject]` edge 4: `actorId()` non-UUID principal → 500 — carried (pass 1).
  - `[low]` `[reject]` edge 5: anonymous token → unscoped store — carried (pass 1).
  - `[medium]` `[patch]` verification-gap 1: a failed force-delete routine leaving its committed marker is untested — added `forceDelete_routineFails_markerCommittedThenRetried` (real PG: exception propagates, DELETION_PENDING with an expired date, schema and membership kept, no WhatsApp, owner cancel refused, the next daily routine completes it).
  - `[medium]` `[patch]` verification-gap 2: integral guard untested on CREATE_DRAFT_PRODUCT / PROMOTE_PRODUCT / catalogueUnitPrice — added `handle_createDraftProduct_fractionalPriceVente_isRejectedNotTruncated`, `handle_promoteProduct_fractionalOrOutOfRangePrices_areRejected` (price, buyPrice, transportCost × 1.5, 3e10) and `SaleSyncHandlerTest.handle_fractionalCatalogueUnitPrice_isRejected`.
  - `[false]` `[reject]` verification-gap other 1: diff includes other stories — see scope.
  - `[false]` `[reject]` verification-gap other 2: Wassender `isConfigured` differs from the Code Map — deliberate pass-1 patch (blank token is not configured).
  - `[low]` `[patch]` orchestrator check: the refusal tests added by v1s-15-7 exist for the four migrated controllers (DeviceToken anonymous 401; Subscription anonymous 401, no-authority 403, SUPER_ADMIN 403; ProductImport anonymous 401, no-role 403; AdminSubscription anonymous 401, OWNER/EMPLOYEE 403). One stale label: `SubscriptionControllerTest` said "SUPER_ADMIN → 401" while asserting 403 — renamed.

## Design Notes

- **Owner cancel refuses an expired grace period.** This is new, but it is the cheapest correct way to close the forceDelete window: forceDelete must commit a marker before the DROP (the DROP is auto-commit). Without the date guard, an owner could cancel between the marker and the lock and turn a SUSPENDED tenant ACTIVE. A grace period that has expired is already due for deletion, so the scheduler may run at any moment and refusing the cancel matches the product rule of a 30-day window.
- **Fail-closed `assignedStoreId` everywhere.** This aligns with the v1s-12-10 decision for handlers. Controllers are always authenticated, so the only behavioural difference is on an impossible path.
- **`requireSuperAdmin` is kept** as defense in depth, behind the `/api/v1/admin/**` URL rule. The existing `SecurityConfigAdminPathEnforcementTest` and the controller 403 tests cover both layers.
- **Non-integral delta numbers are returned unchanged, not thrown.** Every column is INTEGER today. If one ever becomes NUMERIC, a lossless value beats a failed pull for every device.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CurrentActorTest,DeltaValuesTest,AccountDeletion*Test,AdminTenantServiceTest,AdminFeedbackControllerTest,*SyncHandlerTest,CreateProductUseCaseTest,ApiResponseWrapperTest,QuickAddProductServiceTest,ProductControllerTest'` -- expected: green
- `cd keevo/backend && mvn test` -- expected: `Failures: 0, Errors: 0`, test count ≥ 2229
- `rm -rf keevo/backend/target` -- expected: disk reclaimed

## Auto Run Result

Status: done

**Summary:** All 20 Story 15.9 items are closed: 19 were fixed, and 1 (the `TenantSchemaSyncService` regex) had already been resolved by v1s-13-12.

- **Shared helper:** `CurrentActor` (`shared.infrastructure.security`) is now the single source for:
  - the actor id, role, fail-closed assigned store and display label (it falls back to "Un employé" / "Le propriétaire" / "Un utilisateur", never a UUID);
  - `requireOwner` and `requireSuperAdmin`.

  Role names come from the `Role` enum. Every private copy is gone, and `requireSuperAdmin` is kept as a second barrier.
- **Number handling:**
  - Sync handlers share `requireIntegralInt` / `optionalIntegralInt`, so fractional or out-of-range values are rejected instead of being truncated.
  - Delta providers share `DeltaValues` (no truncation, type-checked booleans).
- **Smaller items:**
  - `paginated()` validates its inputs.
  - `WhatsAppPort.isConfigured()` is abstract, and Wassender now reports false when its token is missing.
  - The admin feedback endpoint goes through `ListFeedbackUseCase`.
  - `Feedback` checks for nulls.
  - `CreateProduct` checks the plan limit before the SKU.
  - QuickAdd sends the store name.
- **Account deletion:**
  - Owner cancel is one conditional UPDATE: the tenant must be DELETION_PENDING, inside its grace period, with its schema present.
  - Admin cancel refuses a tenant whose schema was dropped.
  - The daily run loads only due tenants.
  - `forceDelete` commits a DELETION_PENDING marker, then runs the scheduler's own deletion routine (`deleteNow`). A concurrent deletion by the daily run counts as success.
  - These behaviours are proven on PostgreSQL 16, with the real cancel beans racing the scheduler's row lock.

**Files changed (main):**
- `shared/infrastructure/security/CurrentActor.java` (new): RBAC/actor helper.
- Controllers (Stock, StockTransfer, DayClosure, PendingSale, InventorySession, InventoryCount, Store, Account, Category, Employee, TenantPreferences, Report, ProductImport, Subscription, DeviceToken, 4 admin controllers): helper adoption.
- `SecurityConfig`, `AdminAccountInitializer`: `Role.SUPER_ADMIN`.
- `ProductController`, `StockOperationService`, `QuickAddProductService`: actor role and label via `CurrentActor`; QuickAdd store name.
- `CreateProductUseCase`: order of the plan-limit check.
- `ApiResponseWrapper`: `paginated` validation.
- `sync/.../handler/AbstractSyncOperationHandler.java` + 6 handlers: integral guards.
- `sync/.../provider/DeltaValues.java` (new) + 7 providers.
- `WhatsAppPort` (abstract `isConfigured`); `WassenderWhatsAppAdapter` (reports false without a token).
- `ListFeedbackUseCase` / `ListFeedbackService` (new), `AdminFeedbackController`, `Feedback`.
- `TenantRepository` / adapter / `TenantSpringRepository`: `findDeletionsDueBy`, `cancelPendingDeletion`.
- `AccountDeletionService`, `AccountDeletionScheduler` (`runTenantDeletion`, `deleteNow`), `AdminTenantService` (cancel guard, `forceDelete`).
- Tests:
  - New: `CurrentActorTest`, `DeltaValuesTest`, `ListFeedbackServiceTest`, `FeedbackTest`, `AccountDeletionConcurrencyPostgresIntegrationTest` (11 tests).
  - Extended: the handler, controller, service and scheduler tests.
- Trackers: the epic brief (20 items ticked) and `sprint-status.yaml`.

**Review:** 1 pass, 4 layers, 39 rows; no bad_spec or intent_gap.
- **Patched (16 rows):** 3 medium and 13 low.
  - Controller test for the fail-closed check on PendingSale.
  - Test for the StockOperation fallback label.
  - Admin cancel raced against the daily run on PostgreSQL.
  - `forceDelete` treats a concurrent DELETED as success.
  - `ListFeedbackService` clamps page and size.
  - QuickAdd store lookup is guarded.
  - Wassender `isConfigured`.
  - Leftover role and principal reads are migrated to `CurrentActor`.
  - `paginated` javadoc; `AccountController` banner.
- **Rejected:** 15 low and 8 false, each with its reason in the triage log.
- **Deferred:** none (deferred-work.md untouched).
- Before review, the step-03 verification also removed the spec's "page past the end" rule in `paginated()`: `GET /admin/feedback?page=99` would have become a 500. The Code Map was updated accordingly.

**Follow-up review recommended: true.** Three medium entries were patched, which meets the "≥ 2 medium" bar. The unverified risk is that the review pass migrated the principal and role reads in `DeviceTokenController`, `SubscriptionController`, `ProductImportController` and `AdminSubscriptionController` to `CurrentActor`. Those controllers are covered only by their existing tests, with no new fail-closed cases.

**Verification:**
- `mvn -o test` (full) after implementation: 2301 tests, 0 failures, 0 errors.
- After the review patches: 2308 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.
- AC greps are clean: no private role or store helper, no `"ROLE_SUPER_ADMIN"` or `hasRole("SUPER_ADMIN")`, and no `(Number)…intValue()` in handlers or providers.
- `keevo/backend/target` was removed.

**Residual risks:**
- `forceDelete` of a SUSPENDED tenant followed by an admin cancel returns it to ACTIVE. Only an admin can do this, and fixing it would need a column storing the prior status.
- The owner can no longer cancel once the grace period has expired. This is a new rule, recorded in Design Notes.
- `DeltaStockStrategy` still narrows quantities with `intValue`. It only estimates conflicts; the handlers reject the operation.
- GitHub Actions remains unverified: it ends in `startup_failure`, per project memory.

### Follow-up review pass — 2026-10-13

**Summary:** fresh four-layer review of the story-scoped diff. The CurrentActor migration of DeviceToken, Subscription, ProductImport and AdminSubscription is backed by v1s-15-7 refusal tests (401 anonymous, 403 wrong role, use case never called); the two raw principal casts still left in Subscription and ProductImport now go through `CurrentActor.actorId()`. The force-delete failure path and the integral guard on draft/promote/catalogue prices are now tested.

**Files changed:** `SubscriptionController`, `ProductImportController` (CurrentActor.actorId); tests `AccountDeletionConcurrencyPostgresIntegrationTest` (+1), `ProductSyncHandlerTest` (+2), `SaleSyncHandlerTest` (+1), `SubscriptionControllerTest` (label).

**Review findings:** 28 — 3 medium rows patched in 2 entries (intent c grouped with verification-gap 1), 2 low patched, 2 deferred and routed (quick-add plan limit → v1s-14-13; legacy DELETED tenants → 10-7-ops-hardening-backups-staging), the rest rejected with reasons in the triage log.

**Follow-up review recommended: false** — no high entry patched in this follow-up pass.

**Verification:** batches backend/sync 436, subscription 34, catalog 395, shared 494 — 0 failures, 0 errors. Targeted run — `AccountDeletionConcurrencyPostgresIntegrationTest` 12, `ProductSyncHandlerTest` 22, `SaleSyncHandlerTest` 24, `SubscriptionControllerTest` 6, `ProductImportControllerTest` 9, `ProductImportControllerSecurityTest` 3: 0 failures.

**Residual risks:** the two routed items; owner `requestDeletion` remains read-then-save (narrow, pre-existing).
