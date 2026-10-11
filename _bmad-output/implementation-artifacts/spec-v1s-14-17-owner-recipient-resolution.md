---
title: 'v1s-14-17 — One deterministic, active-only owner WhatsApp recipient resolver'
type: 'refactor'
created: '2026-10-12'
baseline_revision: '00e605485d2c6bd676b3ae050cad463b3273366e'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Every owner-directed WhatsApp sender (EOD/weekly/combined reports, resend, retry, test report, day-closure legacy listener, 4 notification listeners) repeats `userRepository.findOwnerByTenantSchemaName(t).map(getPhoneNumber)` with diverging rules. The underlying SQL picks an arbitrary owner (`LIMIT 1`, no `ORDER BY`) when several OWNER memberships exist, ignores `users.is_active`, and phones are used untrimmed / un-normalized; `DailyReportGenerator` and `WeeklyReportGenerator` hand a blank phone to `WhatsAppPort` instead of marking the report `IN_APP_ONLY`.

**Approach:** Fix the owner query (deterministic primary owner, active user + membership) and add one identity port `OwnerRecipientResolver` (adapter in identity) returning `Optional<String>` = the primary active owner's phone normalized with `PhoneNumbers.normalize` and accepted only when it matches `^\+\d+$`. Every owner-directed WhatsApp send uses it; nothing else calls `findOwnerByTenantSchemaName`.

## Boundaries & Constraints

**Always:** primary owner = active OWNER membership (`m.is_active`) of an active user (`u.is_active`) with the oldest `m.created_at`, tie-break `u.id`; null/blank tenant → empty without lookup; resolver output is either empty or `+<digits>`; empty ⇒ each caller keeps its existing "no recipient" behaviour (report generators and combined summary → `IN_APP_ONLY`, retry → skip + warn, resend → `DomainException WHATSAPP_DELIVERY_FAILED`, test report → `false`, listeners → no WhatsApp send); repository exceptions propagate exactly as before (callers' existing try/catch unchanged); the phone value is never logged by the resolver.

**Never:** no fallback to a second owner or any configured/default number; no change to `findOwnersByTenantSchemaName` (last-owner guards and transfer push fan-out rely on it); no change to `AccountDeletionScheduler` / `AccountDeletionService` (all-owners final notice / actor's own phone, not the single-owner rule); no migration or schema change; no change to push (FCM) recipients.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Single owner | tenant `kv_a`, one active owner `+237600000001` | `Optional.of("+237600000001")` | none |
| Several owners | two active OWNER memberships, A created before B | always A's phone (DB test) | none |
| Deactivated owner user | oldest owner `u.is_active=false`, second active | second owner's phone (DB test); only owner inactive → empty | none |
| Inactive membership | `m.is_active=false` | not selected (DB test) | none |
| Untrimmed / formatted phone | `"  237 600-000-001 "` | `"+237600000001"` | none |
| Blank / null phone | `"   "` or `null` | empty | none |
| Garbage phone | `"abc"` | empty, warn naming tenant only | none |
| Null / blank tenant | `null`, `" "` | empty, repository not called | none |
| EOD report, blank owner phone | `DailyReportGenerator`, WHATSAPP channel, owner phone `"  "` | report `IN_APP_ONLY`, `WhatsAppPort` never called | none |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:42-50` -- `OWNER_BY_SCHEMA_SQL`: add `AND u.is_active = true`, `ORDER BY m.created_at ASC, u.id ASC` before `LIMIT 1`. `OWNERS_BY_SCHEMA_SQL` (:51-59) untouched.
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/UserRepository.java:30-39` -- javadoc of `findOwnerByTenantSchemaName`: primary active owner rule; "use OwnerRecipientResolver for WhatsApp".
- `keevo/backend/src/main/java/com/keevo/shared/domain/PhoneNumbers.java` -- `normalize` (trim, strip spaces/-/./(), prefix `+`; blank → empty). Reuse, do not change.
- Call sites to migrate (each currently injects `UserRepository` only for the owner lookup, except SaleDraftValidated which also uses `findById`):
  - `reporting/report/application/service/DailyReportGenerator.java:101-105`, `WeeklyReportGenerator.java:155-160` -- `resolveOwnerPhone` → resolver `.orElse(null)`.
  - `reporting/report/application/service/AbstractReportGenerator.java:112-119` -- null check → also blank (defence).
  - `reporting/report/application/service/ReportHistoryService.java:64-75`, `ReportDeliveryRetryService.java:70-77`, `MultiStoreSummaryService.java:102-120`.
  - `identity/onboarding/application/service/SendTestReportService.java:59-65`.
  - `commerce/sale/application/service/DayClosureWhatsAppListener.java:45-51,67,100-107` -- private `resolveOwnerPhone` removed (resolver covers null/blank tenant); keep the whole-handler guard, AFTER_COMMIT, TenantContext handling.
  - `messaging/notification/application/listener/StockAlertNotificationListener.java:288-295`, `ProductCreationNotificationListener.java:253-257`, `StockModificationNotificationListener.java:240-244`, `SaleDraftValidatedNotificationListener.java:134,215,304` (keep `UserRepository` for `findById`).
- Tests mocking `findOwnerByTenantSchemaName` / constructing these classes (switch to a mocked `OwnerRecipientResolver`): `reporting/report/application/service/{DailyReportGenerator,WeeklyReportGenerator,MultiStoreSummaryService,ReportHistoryService,ReportDeliveryRetryService}Test.java`, `identity/onboarding/application/service/SendTestReportServiceTest.java`, `commerce/sale/application/service/DayClosureWhatsAppListenerTest.java`, `messaging/notification/application/listener/{StockAlert,ProductCreation,StockModification,SaleDraftValidated}NotificationListenerTest.java`. Blank/null-phone cases at call sites become "resolver returns empty" cases.
- DB test style: `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/AuthSessionPostgresIntegrationTest.java` (`@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=validate")`, `extends PostgresIntegrationTest`, `insertUser` via JdbcTemplate). Schema: `public.tenants` (id, created_at, updated_at, code≤10, max_employees, max_products, max_stores, plan_type, schema_name≤15, status), `public.user_tenant_memberships` (id, created_at, updated_at, is_active, role, tenant_id, user_id; unique user+tenant) in `db/migration/V1__baseline_public.sql`.
- `keevo/backend/src/test/java/com/keevo/architecture/KeevoArchUnitBaselineTest.java` -- existing rules are warning-only; the new guard must be a separate, failing ArchUnit test.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/OwnerRecipientResolver.java` -- new port: `Optional<String> resolveOwnerWhatsAppPhone(String tenantSchemaName)`, javadoc stating the rule.
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/OwnerRecipientResolverAdapter.java` -- new `@Component` implementing the matrix via `UserRepository.findOwnerByTenantSchemaName` + `User.isActive()` (defence) + `PhoneNumbers.normalize` + `^\+\d+$`; warn (tenant only) when a present phone is rejected.
- `UserRepositoryAdapter.java`, `UserRepository.java` -- SQL + javadoc per Code Map.
- The 11 call-site classes above -- inject `OwnerRecipientResolver` instead of `UserRepository` (SaleDraftValidated keeps both); drop now-unused imports/fields; `AbstractReportGenerator` treats blank like null.
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/impl/OwnerRecipientResolverAdapterTest.java` -- Mockito unit tests for every non-DB matrix row.
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/OwnerLookupPostgresIntegrationTest.java` -- `@DataJpaTest` + `@Import(UserRepositoryAdapter.class, OwnerRecipientResolverAdapter.class)` extends `PostgresIntegrationTest`: several owners (deterministic oldest membership, repeated calls stable, inserted in reverse order), deactivated user skipped, only owner deactivated → empty, inactive membership skipped, EMPLOYEE never chosen, unique schema/code per test.
- `keevo/backend/src/test/java/com/keevo/architecture/OwnerRecipientResolutionArchTest.java` -- blocking ArchUnit rule: no class except `OwnerRecipientResolverAdapter` calls `UserRepository.findOwnerByTenantSchemaName`.
- Existing tests listed in Code Map -- adapt; `DailyReportGeneratorTest` gains a test wiring the real `OwnerRecipientResolverAdapter` over a mocked `UserRepository` returning a blank phone → `IN_APP_ONLY`, no `sendReport` (same for `WeeklyReportGeneratorTest`).

**Acceptance Criteria:**
- Given the backend main sources, when searching for `findOwnerByTenantSchemaName(` calls, then only `OwnerRecipientResolverAdapter` calls it (enforced by the ArchUnit test).
- Given a tenant with two active owners, when any report or notification is sent twice, then both sends target the same (oldest-membership) owner.
- Given an owner whose user account is deactivated, when a report is generated, then that owner never receives it.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 26 findings — high 0, medium 6, low 8, false 12, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) AccountDeletionScheduler keeps its own all-owners recipient query — deliberate fan-out (every owner is told the tenant is wiped, D4); not the single-recipient rule; no wrong-tenant or wrong-person outcome.
  - `[medium]` `[patch]` (blind) ArchTest guards only `callMethod` on `UserRepository`; method references / adapter-typed receivers escape — fixed: predicate widened to all accesses (calls + method references) of `findOwnerByTenantSchemaName` on any owner assignable to `UserRepository`.
  - `[false]` `[reject]` (blind) `OWNERS_BY_SCHEMA_SQL` counts deactivated users in last-owner guards — no main code path sets `users.is_active=false` (grep: only memberships are deactivated), so the guard cannot be fooled; pre-existing and unreachable.
  - `[low]` `[reject]` (blind) `^\+\d+$` accepts `+1`/40 digits — phones are normalized and validated at write (12-12/V10); E.164 bounds risk rejecting numbers the provider accepts (D3); unlikely in practice.
  - `[low]` `[reject]` (blind) Rejection warn could spam logs — only for a malformed stored phone, which current write paths cannot produce; rate-limiting adds complexity for an unlikely case.
  - `[low]` `[reject]` (blind) Retry service skips forever (re-fetch every 5 min) when phone rejected; log wording misleading — skip behaviour is the pre-existing no-owner rule and malformed phones are unreachable via write paths; wording fixed under the edge-case row below.
  - `[false]` `[reject]` (blind) Adapter in wrong layer — intent explicitly asks "port + adapter"; adapter sits beside the owner SQL adapter it wraps (D2).
  - `[false]` `[reject]` (blind) Leftover defensive blank checks — `AbstractReportGenerator.resolveOwnerPhone` is an abstract extension point; the guard protects any subclass, cheap and intentional (item 4).
  - `[medium]` `[patch]` (blind) Only Daily/Weekly tested with the real resolver; notification listeners never assert the WhatsApp send — same root cause as verification-gap row 1; fixed: one send-to-resolved-phone test per listener.
  - `[low]` `[reject]` (blind) DB test lacks normalization rows / primary-invalid-secondary-valid case / randomPhone collision — normalization is pure logic covered by the adapter unit test; no-fallback is D1; collision odds ~1e-8.
  - `[low]` `[patch]` (blind) Stale test name `generateReport_shouldUseOwnerPhoneFromRepository` — renamed. (Null/blank-tenant listener tests relying on mock default: reject — resolver owns that rule and is unit-tested; the listener tests assert nothing is sent.)
  - `[false]` `[reject]` (blind) Ownership transfer keeps sending to the founder — no transfer feature exists; promotion (14.11) adds a co-owner, the founder stays an owner; deterministic founder choice is D1.
  - `[false]` `[reject]` (edge) National phone without country code becomes `+699…` — identical to the canonical form the write path (PhoneNumbers.normalize at 12-12, V10 migration) already stores; no new risk.
  - `[low]` `[reject]` (edge) E.164 bounds — same as blind row 4.
  - `[medium]` `[patch]` (edge) ArchTest misses method refs / adapter receivers — same entry as blind row 2 (fixed).
  - `[low]` `[patch]` (edge) Retry log "No owner found" misleading — reworded to "No usable owner WhatsApp phone".
  - `[low]` `[reject]` (edge, claim) Account-deletion sends not routed through resolver — D4: deletion confirmation goes to the requesting actor's own phone, final notice to all owners; neither is the single-owner rule; switching would reduce recipients of a destructive-action notice.
  - `[medium]` `[patch]` (edge, claim) "enforced by the ArchUnit test" overclaims — same entry as blind row 2 (fixed).
  - `[medium]` `[patch]` (verification-gap) Notification listeners never assert `sendReport(eq(phone), …)` — fixed: 4 tests added.
  - `[medium]` `[patch]` (verification-gap) ArchTest bypass via method reference / adapter type — same entry as blind row 2 (fixed).
  - `[false]` `[reject]` (intent) Readings R2/R3 (account deletion, plural owner lookup, push) not covered — the intent names the single WhatsApp owner recipient and its call sites; D4 records each exclusion with its reason.
  - `[false]` `[reject]` (intent) Item 3 tested only in the adapter unit test, not at ReportHistory/DayClosure — callers pass the resolver output verbatim and the ArchTest prevents any other lookup; the transformation is the resolver's.
  - `[false]` `[reject]` (intent) Arch rule keyed to one method — the rule targets the only owner-recipient query; widened per the patch above.
  - `[false]` `[reject]` (intent) Listener tests only constructor-updated — superseded by the verification-gap patch.
  - `[false]` `[reject]` (intent) Rejection reasons only in the spec — the spec is committed with the story and the epic checklist records each item's disposition.
  - `[false]` `[reject]` (intent) Format rule lives in an adapter — same as blind row 7.

## Design Notes

- D1 Primary owner = oldest active OWNER membership (the founder; Story 14.11 promotions come later), tie-break `u.id`. No fallback to the next owner when the primary's phone is unusable: phones are mandatory login identities, normalized at write since 12-12, so an unusable phone is a data defect to surface (warn), not to route around.
- D2 Port lives in `identity.auth.domain.port.out` next to `UserRepository` (already consumed cross-module by reporting/messaging/commerce); adapter in identity reuses the single owner SQL — one rule, one query.
- D3 Format check `^\+\d+$` only (no length/E.164 bounds): rejects garbage without risking real numbers the provider accepts.
- D4 `findOwnersByTenantSchemaName`, `AccountDeletionScheduler`, `AccountDeletionService` reviewed and intentionally out of scope (fan-out / self-recipient semantics, not the owner-recipient rule).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest='OwnerRecipientResolverAdapterTest,OwnerLookupPostgresIntegrationTest,OwnerRecipientResolutionArchTest,DailyReportGeneratorTest,WeeklyReportGeneratorTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: all green
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend identity reporting commerce messaging shared architecture` -- expected: 0 failure / 0 error per lot (one package at a time; never the full suite, never in parallel)

## Auto Run Result

Status: done

**Summary:** One owner-recipient rule for every owner-directed WhatsApp send. `OWNER_BY_SCHEMA_SQL` now picks the primary active owner deterministically (`u.is_active`, `ORDER BY m.created_at, u.id`). New port `OwnerRecipientResolver` + `OwnerRecipientResolverAdapter` (identity) return the owner phone normalized with `PhoneNumbers.normalize` and accepted only as `+<digits>`, else empty. The 11 senders (Daily/Weekly generators, ReportHistory, ReportDeliveryRetry, MultiStoreSummary, SendTestReport, DayClosureWhatsAppListener, 4 notification listeners) use it; a blocking ArchUnit rule forbids any other access to `findOwnerByTenantSchemaName`. Blank phone on the EOD/weekly path → `IN_APP_ONLY`.

**Items (epic 14.17):** (1) deterministic owner — SQL ORDER BY + DB test; (2) deactivated user skipped — SQL + adapter filter + DB test; (3) trim/normalize in resolver; (4) blank → IN_APP_ONLY via resolver + `AbstractReportGenerator` guard, real-resolver tests; (5) duplication removed + ArchUnit guard.

**Files changed (keevo/backend/src):**
- `main/.../identity/auth/domain/port/out/OwnerRecipientResolver.java` -- new port.
- `main/.../identity/auth/adapter/out/persistence/impl/OwnerRecipientResolverAdapter.java` -- new adapter (rule).
- `main/.../identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java`, `.../port/out/UserRepository.java` -- SQL + javadoc.
- 11 sender classes in reporting / messaging / commerce / identity.onboarding -- inject the resolver; `AbstractReportGenerator` blank guard; retry log wording.
- Tests: `OwnerRecipientResolverAdapterTest` (16), `OwnerLookupPostgresIntegrationTest` (7, PostgreSQL), `OwnerRecipientResolutionArchTest` (3, incl. rule self-checks), 11 adapted test classes (+ real-resolver blank-phone tests for Daily/Weekly, + send-to-resolved-phone tests for 4 listeners).

**Review:** 1 pass, 26 findings (0 high, 6 medium, 8 low, 12 false). Patched entries: ArchUnit rule widened to method references / adapter-typed receivers (medium), listener WhatsApp-send tests (medium), retry log wording (low), stale test name (low). Deferred: none. Rejected: see Review Triage Log (account-deletion fan-out D4, E.164 bounds D3, unreachable deactivated-user guard, layer placement, etc.).

**Follow-up review recommendation:** false — the two medium entries patched were test-coverage gaps closed by tests that ran green (the ArchUnit rule is self-checked against both bypass forms); no unverified risk can be named.

**Verification:** targeted `OwnerRecipientResolverAdapterTest,OwnerLookupPostgresIntegrationTest,OwnerRecipientResolutionArchTest,DailyReportGeneratorTest,WeeklyReportGeneratorTest` → 44 run, 0 failure; `run-tests-chunked.sh backend identity reporting commerce messaging shared architecture` → 338 / 220 / 220 / 125 / 386 / 6, 0 failure / 0 error (commerce 222→220 and reporting stable: null/blank-phone call-site tests collapsed into resolver tests).

**Residual risks:** no fallback to a second owner when the primary owner's phone is unusable (D1, warn only); `findOwnersByTenantSchemaName` and account-deletion senders keep their own semantics (D4).
