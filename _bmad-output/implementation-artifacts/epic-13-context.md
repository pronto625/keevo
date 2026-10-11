# Epic 13 Context: Stabilisation V1 — Concurrence & Intégrité données (Piste A, release-blocker)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization (v1s-13-*) epic, NOT the refonte-modulaire epic 13 nor V1 epic files. -->

## Goal

Remove the concurrency races and data-loss paths on the production V1 line that the 2026-07-20 audit found to be mechanically certain (B-CRIT-1/2/3, F-CRIT-1/2/3, F-HIGH-4), together with the residual integrity gaps routed from deferred-work on 2026-10-11. The aim is that offline-first sync, stock mutations, transfers, sale cancellation, inventory validation, schedulers, report windows and tenant-schema provisioning never double-apply, lose or silently drop data. Stories 13.1–13.11 are done and 13.12 is in progress. 13.8–13.12 are item-list stories made from redistributed review deferrals. All are tag A (urgent V1 patch) except 13.12, which is tag C (V1 patch that the refonte also absorbs).

## Stories

- Story 13.1: Optimistic locking on StockTransfer + StockLevel (done)
- Story 13.2: Fix the permanent 7-day sync-gate lockout (done)
- Story 13.3: Fix DayClosureDeltaProvider, weekly window and EOD timing (done)
- Story 13.4: Drift atomicity: sync_queue inside the transaction (Flutter) (done)
- Story 13.5: FR90: cancel/correct a completed sale with stock restoration (done)
- Story 13.6: Draft-sale status PENDING_VALIDATION (done)
- Story 13.7: Audit/movement events on offline inventory validation (done)
- Story 13.8: Residual sync integrity: offline write paths, handler parity (done)
- Story 13.9: Scheduler locking and check-then-act races (done)
- Story 13.10: Optimistic lock on Sale (@Version + 409) (done)
- Story 13.11: Day-closure and report windows (PO decisions) (done)
- Story 13.12: Tenant schema provisioning hygiene (in progress)

## Requirements & Constraints

- **Zero data loss after sync (FR73, NFR20).** Sync operations must be ACID. A crash between a local write and its queue entry must not lose the write, and a replay must not duplicate it.
- **Offline operation.** The app runs offline for up to 7 days (NFR19). After 7 days without sync, access is suspended until a sync succeeds (FR74). A device whose successful pull lifts the gate must be able to push again.
- **Sync performance and retry.** A full sync of 7 days of data completes in under 60 s (NFR2). Retries use exponential backoff (NFR21).
- **Stock correctness.** Concurrent transfers and stock mutations must never double-credit, lose an update or drive stock negative (FR32, FR37, FR48).
  - Re-receiving an already-completed transfer returns the existing transfer idempotently.
- **Sale cancel/correct (FR90).** OWNER-only (EMPLOYEE gets 403). A justification of at least 10 characters is required. Stock is restored through `SALE_CANCELLED` movements, and the action emits audit events. Concurrent cancel/correct on the same sale conflicts (409) instead of overwriting (13.10).
- **Draft carts.** A cart containing DRAFT items is recorded as `PENDING_VALIDATION` and does not decrement stock.
- **Offline inventory validation (FR48).** For each non-zero discrepancy, write a `StockMovement` of type `ADJUSTMENT` with `source=INVENTORY`, `inventorySessionId`, quantity before/after and actorId. Also queue a threshold event when stock falls below the minimum.
  - Guard against empty sessions and against re-validating an already-VALIDATED session.
- **Reports (FR42, FR52, FR53).** Daily recap, weekly report (Sunday evening, ISO Monday-to-Sunday week) and per-tenant `eodReportTime`. A manual closure or an automatic EOD fallback (20:00 by default) always produces a report. No sale may be silently excluded from a closure or report. The window rules are PO decisions recorded in the PRD (13.11, see dependencies).
- **Existing closure rules.** One closure per store per day, and closures more than 7 days old are refused. An EMPLOYEE can close only their own store.
- **Multi-instance safety.** Schedulers (day closure, account deletion, trends) must not double-run across instances or restarts.
- **Tenant isolation (NFR10).** 100% isolation, schema-per-tenant (`kv_xxxxxx`, created automatically at signup). Isolation also depends on tenant schemas being fully and consistently provisioned: a tenant must never end up half-provisioned, and provisioning failures must be visible.
- **Tests.** Each fix ships named TDD tests (red first) for the concurrency or integrity case.
- **Deferred items (13.8–13.12).** Each item is resolved, or rejected with a reason, with its box ticked in the epics file and the commit or decision noted. deferred-work.md stays empty; new follow-ups are routed to their owning story.

## Technical Decisions

- **Write strategy (no exceptions).** Backend-First-When-Online / Local-First-When-Offline.
  - Online: call remote first, then cache locally (`synced:true`, no sync_queue entry).
  - Offline: write to local Drift and add a `sync_queue` entry, in **one Drift transaction**.
  - Queue inserts belong in the Repository layer, never in DataSources.
  - The UI refreshes from Drift after every write.
- **Sync API.** `POST /api/v1/sync/push` is used only to replay queued operations, in batch. `GET /api/v1/sync/pull?since=` returns deltas through `DeltaEntityProvider`s.
  - Conflict resolution: stock uses delta sums (`DeltaStockResolver`); other entities use last-write-wins by timestamp.
  - IDs are UUID v4 assigned by the client or domain, never `@GeneratedValue`. Client ids make replays idempotent; for example, a SALE movement's id is the client sale item id (sent in the CREATE_SALE payload) on both app and backend, so a pull does not create a second SALE movement. Every path that writes SALE movements (record, validate pending, validation cascade) must follow this rule.
- **Sync handler parity.** Every sync handler must validate input like its REST DTO (for example a store name of 2–100 characters, and a range check before `intValue`). Every handler must also be idempotent on retry and must route through the use case, not `repository.save`, so that existence checks, single-active-session rules and domain events still apply (for example InventorySessionSyncHandler).
- **RBAC (Epic 12, merged).** Sync handlers keep the Epic 12 rules:
  - RBAC failure → op REJECTED with `DomainException(ErrorCode.FORBIDDEN)` thrown before the use case.
  - Null auth fails closed.
  - Role and store come from the authenticated actor, never from the payload.
  - An OWNER JWT carries no storeId.
  - EMPLOYEE inventory sessions and transfer sources are limited to the employee's own store.
  - Device ids are bound to the actor (insert guarded by ON CONFLICT, never reassigned).
- **Concurrency primitives.**
  - Optimistic locking uses `@Version long version` on the JPA entity and the domain object, mapped to 409. The Flutter client does a silent refresh and shows a human-readable SnackBar.
  - Alternatives are conditional or atomic `UPDATE … WHERE status=… / quantity+?>=0` (0 rows → idempotent) or `SELECT … FOR UPDATE`.
  - Check-then-act races are closed with unique constraints plus a catch, row locks, or a transaction-scoped advisory lock through `TransactionLockPort` taken as the first statement (keys like `day-closure:<storeId>`, `product-limit:<schema>`, `owners:<schema>`, `transfer:<clientId>`).
  - Sale status transitions are atomic conditional claims: PENDING_VALIDATION→COMPLETED (validate and cascade; loser gets `SALE_NOT_PENDING` and rolls back, cascade skips) and PENDING_VALIDATION→CANCELLED (sync cancel with `pendingOnly`; a COMPLETED sale gets FORBIDDEN).
  - `Sale` has `@Version` (13.10). Status claims bump the version. COMPLETED→CANCELLED is version-checked: a lost claim returns `SALE_ALREADY_CANCELLED` if the sale is now cancelled, otherwise `OPTIMISTIC_LOCK` (409). A correction saves with `saveAndFlush`, so a concurrent change raises a 409 and rolls back its movements. New guards on cancel/correct (such as a closed-day check) must sit alongside these claims.
  - Every `@Scheduled` job carries ShedLock `@SchedulerLock` (JDBC provider, DB time, `public.shedlock` table). `SchedulerLockCoverageTest` fails on a new unlocked job. Per-instance in-memory jobs (such as batch-window eviction) are deliberately not locked.
  - Notification cooldowns use one atomic `INSERT … ON CONFLICT DO UPDATE … WHERE` statement instead of check-then-upsert.
  - Long-lived in-memory maps get a TTL cache.
- **Time.** Inject `Clock` (as in DayClosureAutoScheduler) rather than calling `LocalDate.now()`. Validate the `eodReportTime` format on write (422) instead of silently falling back to 20:00.
- **Tenant schemas (13.12).**
  - All schema-name validation goes through the single `TenantSchema` helper (`^kv_[a-z0-9]{6}$`); the remaining ad-hoc helpers (connection provider, schema sync, provisioner) converge on it.
  - `public` is the source of truth, managed only by Flyway versioned migrations (`V{n}__*.sql`, baseline 0) with `ddl-auto=validate`. Never edit an applied migration; fix non-idempotent V1 inline CHECK constraints with `DO $$` blocks in a new migration.
  - `TenantSchemaProvisioner` creates the tenant tables by programmatic JDBC DDL at registration (users, subscriptions, roles, user_roles, stores, categories, tenant_preferences + seeds). Its DDL must be complete on its own (e.g. `users` lockout columns), not rely on a later sync.
  - `TenantSchemaSyncService` diffs `information_schema` (public vs tenant) and adds missing tables (`LIKE … INCLUDING ALL`, no FKs) and columns, cached per JVM, uses fully-qualified `"schema"."table"` names via raw JDBC, `tenants` excluded. It currently swallows `SQLException`; failures must become visible (ERROR log / fail) so per-tenant columns such as `version` never go silently missing.
  - Raw SQL rule: tenant tables are touched only via Hibernate or with a schema-qualified name from `TenantContext`; never `JdbcTemplate` inside `@Transactional`.
  - SQL correctness of migrations/DDL is verified against a real PostgreSQL (integration tests), not mocks.
- **Drift local retention.** Transactional tables are purged only once they are synced. PENDING queue rows are never purged.
- **Branches.** Work targets the V1 line (`deploy` → `develop` fast-forward). Each story notes how the refonte absorbs it, either natively or by re-applying.

## UX & Interaction Patterns

- Conflicts (409, `TRANSFER_INVALID_STATUS`) never show a technical error. The app refreshes silently and shows a SnackBar such as « Cette opération a été modifiée, actualisé ». An already-COMPLETED transfer just refreshes.
- For a draft cart, the POS checkout button reads « Vente brouillon ».
- The sale detail screen offers OWNER-only cancel/correct with a justification field (at least 10 characters).
- Once a pull lifts the 7-day gate, the app retries the push automatically.

## Cross-Story Dependencies

- **13.8** consolidates leftovers from 13.4, 13.6, 13.7, 16.6, 12.10 and the UI-3 reviews. Its CREATE_SALE movement-id change spans the app and the backend. The inventory variant unique index waits for variants (14.1). Its other follow-ups went to 13.10, 15.9 (intValue range checks in other handlers) and 16.12 (local inventory-session reconciliation after rejection).
- **13.9** (done) built on 13.3, 13.5, 14.5, 14.10 and 14.11. Its ShedLock setup, advisory-lock port and conditional sale-status claims are the base for 13.10 and 13.11.
- **13.10** (done) mirrored 13.1 for `Sale`, protecting the cancel/correct paths from 13.5, and made every SALE-movement path (validate, cascade, app and backend) reuse the sale item id.
- **13.11**'s PO decisions D1–D3 are recorded in prd.md (notes under FR51/FR52/FR90) and in spec-v1s-13-11-closure-report-windows.md: a closure covers every sale since the previous closure, with no 23:59 cut-off (D1); the weekly report chains on the previous weekly report, which closes the Sunday 20:00–23:59 gap (D2); cancel/correct after closure is allowed with an in-app warning and no regeneration or `SALE_DAY_CLOSED` (D3). It builds on 13.3, 13.5 and 13.10; injecting `Clock` into CloseDayService follows the DayClosureAutoScheduler pattern. Who may close a day (EMPLOYEE vs OWNER) and the `'default'` storeId in all-stores mode are separate open PO questions owned by 4-4 / UI-3, not by 13.11.
- **13.12** (in progress) relates to 12.5 (TenantSchema) and 13.1 (the V5 version column depends on the boot-time schema sync), and is absorbed natively by refonte 10.1/10.2.
- Sync-rejection feedback to users belongs to Epic 16 (16.12). FR90 in the refonte is 17-1.
