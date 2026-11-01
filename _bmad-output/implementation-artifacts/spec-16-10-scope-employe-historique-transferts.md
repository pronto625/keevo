---
title: '16-10 Employee store scope on transfer history (REST + sync pull)'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_revision: 'fc1e431f424267892442ff4d15a80037fff262bd'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: []
deferred:
  - summary: >-
      Other stores' stock movements, data already pulled on employee devices, and the scope source are routed to story 16-13.
    evidence: |-
      Review 16-10, 2026-11-01: StockMovementDeltaProvider is unscoped; incremental pulls never delete rows already on devices; the provider reads the security context instead of the pull command.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Security residue of v1s-12-9: `GET /api/v1/stock/transfers` returns the transfers of every store to an EMPLOYEE, and the sync pull (`StockTransferDeltaProvider`) sends every store's transfers to an employee's device, while creation (`POST`) and reception (`/complete`) are already limited to the employee's store (`CurrentActor.assignedStoreId()`).

**Approach:** When the caller has an assigned store (EMPLOYEE), force the history query and the transfers delta pull to transfers whose source **or** destination is that store; an explicit filter on another store returns an empty page (no 403, no information about other stores). OWNER (no assigned store) is unchanged.

## Boundaries & Constraints

**Always:**
- Scope source of truth: `CurrentActor.assignedStoreId()` (null for OWNER / super-admin → no scope).
- REST: with a scope S, the effective filter is `(source = S OR destination = S)` AND the caller's own filters; `source`/`destination`/`store` pointing to another store → empty page with the usual page shape (`content: []`, `totalElements: 0`).
- Sync pull: with a scope S, the transfers provider returns only rows with `source_store_id = S OR destination_store_id = S`, keeping the keyset cursor semantics (pagination and `next` position unchanged for the filtered set).
- Both paths covered by tests with an EMPLOYEE of store A, transfers A→B, B→A, B→C: employee sees the first two only; OWNER sees all three.

**Never:**
- No change to other delta providers, to transfer creation/completion, or to the app.
- No 403 for other-store filters (avoid revealing store existence).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee, no filter | A→B, B→A, B→C | A→B, B→A | — |
| Employee, `store=B` | same | only transfers involving A and B (A→B, B→A) | — |
| Employee, `source=C` | same | empty page | — |
| Owner, no filter | same | all three | — |
| Employee pull | same, since epoch | A→B, B→A; cursor continues correctly over pages | — |
| Owner pull | same | all three | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/inventory/transfer/adapter/in/web/StockTransferController.java:128-155` -- `getHistory` builds `GetTransferHistoryQuery(source, destination, from, to, page, size, store, status)`; POST (73-80) and `/complete` (110-116) show the `CurrentActor.assignedStoreId()` pattern.
- `GetTransferHistoryQuery` + its service/repository (`getTransferHistoryService.execute`) -- add the scope field and predicate.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/StockTransferDeltaProvider.java:35-40` -- `DeltaPageQuery.fetch(em, columns, "stock_transfers", CURSOR, since, after, limit)`; `DeltaPageQuery` may need an optional extra predicate with bound parameters.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/CurrentActor.java:114-130` -- `assignedStoreId()`.
- Tests: existing transfer controller / history service tests, `StockTransferDeltaProvider` / sync pull integration tests (find with grep).

## Tasks & Acceptance

**Execution:**
- Query/service/repository -- scope predicate; controller passes `CurrentActor.assignedStoreId()`; tests per the matrix.
- `DeltaPageQuery` (optional predicate, parameters bound, never string-concatenated values) + `StockTransferDeltaProvider` scope from `CurrentActor`; Postgres test of the pull for employee vs owner incl. pagination.
- Epics doc -- tick the 16.10 item.

**Acceptance Criteria:**
- Given the matrix data, when an employee of A calls the history and pulls transfers, then B→C never appears in either; the owner sees it in both.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 22 findings — high 0, medium 4, low 11, false 2, maybe-false 5
- findings:
  - `[medium]` `[defer]` (blind) Stock movements of other stores still pulled by employees — outside this story's surface; routed to new story 16-13 (key + brief).
  - `[medium]` `[defer]` (blind, edge, intent, verification-gap other) Data pulled before the fix stays on devices; store reassignment keeps old rows — app purge routed to 16-13.
  - `[medium]` `[patch]` (blind, edge, intent) Employee without a store stalled the whole pull — empty page, WARN, pull not failed; provider + `SyncPushService.pull` tests.
  - `[low]` `[defer]` (blind, intent) Scope read from the security context instead of the pull command — routed to 16-13.
  - `[low]` `[patch]` (blind) Unscoped overloads (repository default, query constructors) — removed.
  - `[low]` `[reject]` (blind) Controller test partly asserts its own stub — the captor assertions are the point; end-to-end added.
  - `[medium]` `[patch]` (blind) Keyset test did not cover out-of-scope rows between in-scope ones / cursor ties — added (sizes 1, 2, full page).
  - `[low]` `[patch]` (blind, edge ×2) `DeltaPageQuery.fetch` filter arguments unguarded — validated (null params, params without filter, reserved names); tests.
  - `[false]` `[reject]` (blind) Tracking files not updated — updated at finalization.
  - `[low]` `[patch]` (blind, intent) No end-to-end pull test — through `SyncPushService.pull` for employee, owner and storeless employee.
  - `[low]` `[patch]` (blind) Filter combinations / A→C, C→A untested — history data and cases extended (status + date window).
  - `[low]` `[reject]` (intent) `store=B` returns A↔B instead of empty (literal AC1) — spec decision: scope ANDed with filters, nothing outside A leaks.
  - `[low]` `[reject]` (intent) 403 for a storeless employee on REST — existing `CurrentActor` rule, same as POST/complete.
  - `[maybe-false]` `[reject]` (verification-gap other) Keyset index not checked for the employee predicate — performance only; data volume per tenant small.
  - `[false]` `[reject]` (verification-gap) No gaps — n/a.
  - `[maybe-false]` `[reject]` (intent) UI already filtered by active store — confirms the visible symptom was limited; server fix stands.
  - `[low]` `[reject]` (intent) `SyncKeysetIndexesMatchProvidersTest` needs an actor — now irrelevant (provider no longer throws).
  - `[maybe-false]` `[reject]` (edge duplicate of reassignment) — routed with 16-13.
  - `[maybe-false]` `[reject]` (edge duplicate of storeless employee) — patched above.
  - `[low]` `[reject]` (edge duplicate of DeltaPageQuery null params) — patched above.
  - `[maybe-false]` `[reject]` (blind) Async pull refactor would drop the scope — covered by the 16-13 item (scope via the pull command).
  - `[low]` `[reject]` (verification-gap other) — duplicate of the device-data entry.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.inventory.**'` then `'com.keevo.sync.**'`, `'com.keevo.architecture.**'` -- expected: green, one package at a time

## Auto Run Result

Status: done

**Summary:** an employee only gets transfers whose source or destination is their store, both from `GET /api/v1/stock/transfers` (scope ANDed with the caller's filters, other-store filters → empty page) and from the sync pull (bound-parameter predicate in `DeltaPageQuery`, keyset paging unchanged). The owner is unscoped. An employee without a store gets an empty transfers page instead of a failed pull. Unscoped overloads removed.

**Review:** 22 findings — 7 patched entries (2 medium, 5 low), 3 routed to the new story 16-13 (stock movements of other stores, purge of data already on devices / reassignment, scope via the pull command), 12 rejected with reasons. Follow-up review recommended: false.

**Verification:** inventory 387, sync 506, architecture 32 — all green (one package at a time).
