---
title: 'v1s-14-16 — Day-closure WhatsApp report sent to the real tenant owner'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '511cedace2a7566d43d5a167e871467b45757384'
review_loop_iteration: 1
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Owner lookup is nondeterministic with several active OWNER memberships (LIMIT 1, no ORDER BY).
    evidence: |-
      UserRepositoryAdapter OWNER_BY_SCHEMA_SQL; still an owner of the same tenant (no cross-tenant leak). Routed to v1s-14-17-owner-recipient-resolution.
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:42-50
    severity: low
  - summary: >-
      Owner lookup does not filter deactivated users (u.is_active).
    evidence: |-
      Query filters m.is_active only. Routed to v1s-14-17-owner-recipient-resolution.
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:48
    severity: low
  - summary: >-
      Owner phone is not trimmed before use.
    evidence: |-
      Phones are normalized to +digits at write time since v1s-12-12; legacy data only. Routed to v1s-14-17-owner-recipient-resolution.
    severity: low
  - summary: >-
      Active EOD path sends to a blank owner phone instead of marking IN_APP_ONLY.
    evidence: |-
      DailyReportGenerator.resolveOwnerPhone has no blank filter; AbstractReportGenerator checks null only. Routed to v1s-14-17-owner-recipient-resolution.
    location: >-
      keevo/backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportGenerator.java:101-105
    severity: low
  - summary: >-
      Owner-phone resolution rule duplicated across ~9 call sites.
    evidence: |-
      Copies in notification listeners, ReportHistoryService, MultiStoreSummaryService, DailyReportGenerator, DayClosureWhatsAppListener. Routed to v1s-14-17-owner-recipient-resolution.
    severity: low
---

<intent-contract>

## Intent

**Problem:** `DayClosureWhatsAppListener` sends every day-closure report (store sales figures) to `System.getProperty("keevo.owner.whatsapp", "+243000000000")` — a JVM-wide property or a hardcoded number — instead of the closing tenant's owner, so one tenant's figures can reach an unrelated phone.

**Approach:** Resolve the recipient per event through `UserRepository.findOwnerByTenantSchemaName(event.tenantId())` (same rule as `ReportHistoryService.resendReport` after v1s-15-7 D1); when no non-blank owner phone exists, skip the WhatsApp call and `log.warn`. Remove the constant and the system-property lookup entirely.

## Boundaries & Constraints

**Always:** recipient is only ever the phone of the OWNER of `event.tenantId()`; null/blank tenantId, missing owner, null or blank phone → no `WhatsAppPort` call + one `log.warn` naming tenant and storeId (never the report content); a lookup exception must not propagate out of the `@EventListener` (closure already committed) — log and skip.

**Never:** no default/fallback number, no system property or config recipient; no change to `EndOfDayReportListener`/`DailyReportGenerator` (the active pipeline, already owner-based); do not re-enable the listener (it stays behind `keevo.reporting.legacy-listener=true`, default off); no schema/migration change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner phone present | tenant `kv_a`, owner phone `+237600000001` | `sendReport("+237600000001", report)` once | none |
| No owner | `findOwnerByTenantSchemaName` → empty | no `sendReport`; warn logged | none |
| Owner phone null / blank | phone `null` or `"   "` | no `sendReport`; warn logged | none |
| tenantId null/blank | event.tenantId() null | no lookup, no `sendReport`; warn | none |
| System property set | `keevo.owner.whatsapp=+243000000000`, owner `+237600000001` | sends to `+237600000001` only | none |
| Lookup throws | repository throws RuntimeException | no `sendReport`; warn; listener returns normally | caught |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java` -- target. L43 `DEFAULT_OWNER_PHONE`, L73 `System.getProperty(...)`. `UserRepository` already imported but unused. Constructor `(WhatsAppPort, StoreRepository)`; only Spring instantiates it (conditional bean).
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/ReportHistoryService.java:64-75` -- reference pattern (v1s-15-7 D1): `findOwnerByTenantSchemaName(..).map(User::getPhoneNumber).filter(!isBlank)`.
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/UserRepository.java:39` -- `Optional<User> findOwnerByTenantSchemaName(String schemaName)`; `event.tenantId()` is the tenant schema name (see `DayClosedEvent` javadoc).
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/MultiStoreSummaryService.java:102-120`, `messaging/notification/application/listener/*NotificationListener.java` -- other owner-phone recipients: already resolve via owner lookup, no fallback number (read-only evidence for the backend-wide sweep).
- Backend-wide grep for `243000000000` / `keevo.owner.whatsapp` (main, test, yml, properties, app, dashboard) -- only hit is the target file.
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/ReportHistoryServiceTest.java` -- test style to mirror (Mockito, `mock(User.class)`).
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/CloseDayService.java:36,123` -- class-level `@Transactional`; the ONLY publisher of `DayClosedEvent`. A plain `@EventListener` therefore runs inside the open closure transaction (NOT after commit): any SQL failure there poisons the PostgreSQL transaction, and any exception escaping rolls back the closure.
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportListener.java:55` -- reference: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on the same event. TenantContext is still set at AFTER_COMMIT time (request thread / `DayClosureAutoScheduler` sets it around `closeDay` and clears after), so no TenantContext handling is needed in this listener.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java` -- inject `UserRepository`; replace the constant + property lookup with a private `resolveOwnerPhone(tenantId)` returning `Optional<String>` per the matrix; skip + warn when empty; switch `@EventListener` to `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` so the owner lookup and the send happen only once the closure is committed (no report for a rolled-back closure, no poisoned transaction); wrap the WHOLE handler body (lookup, store lookup, report build, send) so no RuntimeException escapes — log `warn` with tenant + storeId + exception class/message, never the report content; update javadoc/comments to state AFTER_COMMIT semantics accurately -- removes the cross-tenant leak without endangering the closure.
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListenerTest.java` -- new Mockito unit test covering every matrix row (system-property row sets and clears the property in try/finally), plus: `sendReport` throwing → no exception escapes; `storeRepository.findById` throwing → no exception escapes and nothing sent; the happy-path test asserts the sent report contains the store name (not just `anyString()`); one test asserts via reflection that `onDayClosed` carries `@TransactionalEventListener` with phase `AFTER_COMMIT` -- proves the fix.

**Acceptance Criteria:**
- Given the backend sources, when grepping `src/main` for `243000000000` or `keevo.owner.whatsapp`, then there are no matches.
- Given a `DayClosedEvent` for tenant A, when the listener handles it, then the only `WhatsAppPort` recipient ever used is tenant A's owner phone.

## Spec Change Log

### 2026-10-11 — Loop 1 (review pass 1, bad_spec)
- **Trigger:** reviewers (blind-hunter, edge-case-hunter, verification-gap) showed the listener is a plain `@EventListener` invoked synchronously inside `CloseDayService`'s `@Transactional` — the closure is NOT committed when it runs. The new owner lookup therefore added an in-transaction SQL whose failure poisons the PostgreSQL transaction even when caught; send/store-lookup exceptions still roll back the closure; and the owner (now a real recipient) could get a report for a closure that is then rolled back.
- **Amended:** Code Map (CloseDayService + EndOfDayReportListener anchors), Execution task for the listener (AFTER_COMMIT + whole-handler guard + accurate comments), test task (send/store failure, report content, AFTER_COMMIT annotation).
- **Known-bad state avoided:** comment claiming "closure already committed" while running pre-commit; a lookup/WhatsApp failure losing the day closure.
- **KEEP:** `resolveOwnerPhone(tenantId)` Optional-returning helper exactly as before (null/blank tenant → no lookup; `.map(getPhoneNumber).filter(!isBlank)`); constant + `System.getProperty` fully removed; constructor `(WhatsAppPort, StoreRepository, UserRepository)`; owner check before building the report; the 8 existing matrix tests (owner present, no owner, null phone, blank phone, null tenant, blank tenant, system property ignored, lookup throws).

## Review Triage Log

### 2026-10-11 — Review pass 1
- verdicts: 24 findings — high 1, medium 8, low 8, false 7, maybe-false 0
- findings:
  - `[medium]` `[bad_spec]` (verification-gap, other) Comment "Closure is already committed" is wrong; listener runs pre-commit inside CloseDayService @Transactional — amended spec: AFTER_COMMIT listener + accurate comments.
  - `[medium]` `[bad_spec]` (blind) Same wrong-comment claim — same root cause, same amendment.
  - `[high]` `[bad_spec]` (blind) Report can go to the (now real) owner for a closure later rolled back — verified: CloseDayService.java:36 class-level @Transactional, publishEvent at :123; amended spec to AFTER_COMMIT.
  - `[medium]` `[bad_spec]` (blind) Only the lookup is guarded; store lookup / sendReport exceptions still roll back the closure — amended spec: whole-handler guard.
  - `[medium]` `[bad_spec]` (blind) No test for failure after the lookup — amended test task (sendReport / findById throwing).
  - `[low]` `[defer]` (blind) Owner lookup ignores users.is_active (UserRepositoryAdapter OWNER_BY_SCHEMA_SQL:48 filters only m.is_active) — pre-existing in shared adapter, all callers affected → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[defer]` (blind) Phone sent untrimmed — phones are normalized to +<digits> at write time (v1s-12-12) so legacy data only; belongs to a shared resolver → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[defer]` (blind) Owner-phone rule duplicated in ~9 places — pre-existing pattern; a shared resolver is its own change → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[bad_spec]` (blind) Report content never asserted (`anyString()`) — amended test task: happy path asserts the store name is in the report.
  - `[false]` `[reject]` (blind) System-property test vacuous / mutates JVM state — it is the regression guard the matrix demands (re-adding the property read fails it); surefire runs forkCount=1 without parallel test execution, and the property is restored in finally.
  - `[false]` `[reject]` (blind) Nothing records why the legacy listener is fixed rather than deleted / whether the active path follows the rule — Design Notes D1 records it; EndOfDayReportListener → DailyReportGenerator.resolveOwnerPhone (:103) uses the owner lookup, no fallback number.
  - `[medium]` `[bad_spec]` (edge) Caught lookup DataAccessException still aborts the PG transaction — member of the AFTER_COMMIT entry.
  - `[medium]` `[bad_spec]` (edge) findById / sendReport throwing roll back the closure — member of the whole-handler-guard entry.
  - `[low]` `[defer]` (edge) Several active OWNER memberships + LIMIT 1 without ORDER BY → nondeterministic recipient (still an owner of the same tenant, no cross-tenant leak); pre-existing in UserRepositoryAdapter:42-50 → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[defer]` (edge) Deactivated owner user still receives reports — duplicate of the is_active row → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[defer]` (edge) Untrimmed phone — duplicate of the trim row → v1s-14-17-owner-recipient-resolution.
  - `[medium]` `[bad_spec]` (edge, claim) "closure already committed" claim false — member of the AFTER_COMMIT entry.
  - `[medium]` `[bad_spec]` (edge, claim) "lookup failure never affects the closure" only partly true — member of the whole-handler-guard entry.
  - `[false]` `[reject]` (intent) Changed class is disabled by default; the user-facing promise lives on the active path — the intent names DayClosureWhatsAppListener explicitly; the active path already resolves the owner per tenant and never uses a fallback number (DailyReportGenerator:101-105, AbstractReportGenerator:113 → IN_APP_ONLY).
  - `[false]` `[reject]` (intent) Tests exercise the class in isolation, not bean wiring — wiring is governed by an unchanged @ConditionalOnProperty; the recipient rule lives in the class and is what the intent asks to prove.
  - `[low]` `[defer]` (intent) Active path DailyReportGenerator.resolveOwnerPhone has no blank filter → sendReport("") instead of IN_APP_ONLY — blank recipient, not a third party; pre-existing → v1s-14-17-owner-recipient-resolution.
  - `[false]` `[reject]` (intent) Sweep was a text search, other recipient choices not audited — the planner also checked every `sendReport` caller (notification listeners, MultiStoreSummaryService, ReportHistoryService, ReportDeliveryRetryService, DailyReportGenerator): all use the owner lookup, none a fallback number.
  - `[false]` `[reject]` (intent) "Mark" reduced to a log line — legacy listener persists no report row; D1 records this; the active path marks IN_APP_ONLY.
  - `[false]` `[reject]` (intent) epic-14-context.md and spec outside the reviewed diff — they are BMAD artifacts committed with the story, not code under review.

### 2026-10-12 — Review pass 2
- verdicts: 20 findings — high 0, medium 3, low 10, false 7, maybe-false 0
- findings:
  - `[medium]` `[patch]` (edge) AFTER_COMMIT store lookup relies on leftover TenantContext; EndOfDayReportListener (AFTER_COMMIT, unordered) clears it in finally (EndOfDayReportListener.java:110-111) — fixed: listener sets TenantContext to event.tenantId() before the store lookup and restores the previous value (or clears) in finally; 2 tests added.
  - `[medium]` `[patch]` (edge) Same root cause (store read routed by caller state) — same fix.
  - `[low]` `[reject]` (edge) Exception message from the WhatsApp adapter could embed report content in logs — unverified and unlikely (adapters log status/body errors, not the request payload); pre-existing logging style across all senders; not worth a sanitizer here.
  - `[low]` `[defer]` (edge) Untrimmed phone — carried from pass 1 → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[patch]` (edge) No test for tenant context in the AFTER_COMMIT phase — fixed with the 2 TenantContext tests above.
  - `[medium]` `[patch]` (blind) Store lookup has no tenant context — member of the TenantContext entry (fixed).
  - `[low]` `[patch]` (blind) Catch drops the stack trace; javadoc says "every exception" while only RuntimeException is caught — fixed: `e` passed to log.warn, javadoc reworded.
  - `[false]` `[reject]` (blind) No integration test of real transaction behaviour / no fallbackExecution — CloseDayService (class-level @Transactional) is the only DayClosedEvent publisher, so the event is never published outside a transaction; AFTER_COMMIT semantics are Spring's and covered by the annotation test.
  - `[low]` `[patch]` (blind) Automatic-closure path and store-not-found fallback untested — fixed: 2 tests added.
  - `[false]` `[reject]` (blind) Null-phone test covers an impossible state — harmless defensive case mirroring ReportHistoryServiceTest; matrix row requires it.
  - `[false]` `[reject]` (blind) System-property test mutates JVM state — carried false from pass 1.
  - `[low]` `[patch]` (blind) Stale javadoc on DayClosedEvent — fixed: lists EndOfDayReportListener as active path and the legacy listener as off-by-default, AFTER_COMMIT, owner-only. (CloseDayService step comment "triggers WhatsApp listener + audit" is still accurate — left.)
  - `[low]` `[defer]` (blind) Owner lookup ignores users.is_active — carried → v1s-14-17-owner-recipient-resolution.
  - `[low]` `[patch]` (blind) Double send when the legacy flag is on (now to the real owner) — fixed: javadoc warns about it; the flag is deprecated and opt-in.
  - `[false]` `[reject]` (blind) Send blocks the close-day request — pre-existing synchronous behaviour (previously even inside the transaction); EndOfDayReportListener behaves identically by design.
  - `[false]` `[reject]` (intent) Production path vs dormant code — carried false from pass 1.
  - `[false]` `[reject]` (intent) Unit-level vs wiring-level surface — carried false from pass 1.
  - `[low]` `[defer]` (intent) Active path blank-phone + sweep semantics — carried → v1s-14-17-owner-recipient-resolution.
  - `[false]` `[reject]` (intent) 14.16 epic checkbox not ticked — done at finalization (ticked with commit/decision).
  - `[low]` `[defer]` (intent) Nondeterministic owner (LIMIT 1, no ORDER BY) / inactive owner / untrimmed — carried → v1s-14-17-owner-recipient-resolution.

## Design Notes

Decision D1 (aligned with v1s-15-7 D1): no recipient → no send, `warn` only. The legacy listener has no report row to mark `IN_APP_ONLY` (it persists nothing), so "mark the delivery" from the epic scope reduces to the log; the active pipeline (`EndOfDayReportListener` → `DailyReportGenerator`) already marks `IN_APP_ONLY`. Deleting the deprecated listener was considered and rejected: the brief asks for an owner-resolution fix and the flag remains a supported (if off) switch.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=DayClosureWhatsAppListenerTest,ReportHistoryServiceTest,DayClosedEventTest -DforkCount=1 -DargLine=-Xmx1536m` -- expected: all green
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend commerce reporting shared` -- expected: 0 failure / 0 error

## Auto Run Result

Status: done

**Summary:** `DayClosureWhatsAppListener` (legacy, behind `keevo.reporting.legacy-listener=true`) no longer sends the day-closure report to `System.getProperty("keevo.owner.whatsapp", "+243000000000")`. It resolves the OWNER phone of `event.tenantId()` via `UserRepository.findOwnerByTenantSchemaName` (non-blank), skips with `log.warn` otherwise, runs AFTER_COMMIT, sets/restores TenantContext around the store lookup, and never lets an exception escape. Backend-wide sweep: no other use of `+243000000000` / `keevo.owner.whatsapp` (main, config, app, dashboard); every other owner-directed sender already uses the owner lookup.

**Files changed:**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java` -- owner-only recipient, AFTER_COMMIT, whole-handler guard, TenantContext set/restore, unused formatters removed.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosedEvent.java` -- javadoc: active vs legacy listeners.
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListenerTest.java` -- 15 Mockito tests (matrix rows + failures + AFTER_COMMIT + TenantContext + auto closure + store fallback).
- `_bmad-output/implementation-artifacts/epic-14-context.md` -- compiled (first epic-14 story of this pass).
- Planning: Story 14.17 brief + `v1s-14-17-owner-recipient-resolution: backlog` key (5 routed items).

**Review:** pass 1 → bad_spec loop (pre-commit listener: AFTER_COMMIT + whole-handler guard), 1 high + 4 medium entries fixed by re-derivation; pass 2 → patches: 2 medium (TenantContext), 5 low (stack trace/javadoc, tests auto/fallback/tenant, DayClosedEvent javadoc, double-send note). Deferred: 5 low items → v1s-14-17-owner-recipient-resolution. Rejected findings and reasons: see Review Triage Log.

**Follow-up review recommendation:** false — pass 2 patched 2 medium entries sharing one root cause (TenantContext), covered by dedicated tests and green regression suites; no named unverified risk remains.

**Verification:** `mvn -o test -Dtest=DayClosureWhatsAppListenerTest,ReportHistoryServiceTest,DayClosedEventTest` → 15 + 13 + 5, 0 failure; `run-tests-chunked.sh backend commerce reporting shared` → 222 / 220 / 386, 0 failure / 0 error; `architecture` → 3/3; grep `src/main` for `243000000000|keevo.owner.whatsapp` → none.

**Residual risks:** the listener stays off by default (behaviour change only when the deprecated flag is on, and then the owner gets two reports); owner-resolution weaknesses shared by all senders are tracked in v1s-14-17.
