---
title: 'v1s-15-7 — Backend unit/slice test gaps (security slices, adapters, JWT filter, @MockitoBean)'
type: 'chore'
created: '2026-10-11'
status: 'done'
baseline_revision: 'b4dcc59e71370aaccc1d4d26c167f80017b583dd'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: [multiple-goals, oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Story 15.7 brief (`epics-remediation-audit.md`) lists 20 test gaps left by the 12.x / 14.12 / 15.3 reviews: method security never exercised through a real filter chain, untested adapter branches, a JWT-filter fixture that silently uses `jdbcTemplate=null`, missing revocation-boundary tests, deprecated `@MockBean`. Story 15.9 also migrated DeviceToken / Subscription / ProductImport / AdminSubscription controllers to `CurrentActor` without adding access-denial tests.

**Approach:** Verify each item against current code; close already-covered items with the named existing test; add the missing tests (test-only), except two behaviour decisions recorded in Design Notes (ReportHistoryService resend without an owner phone; EmployeeStatus on corrupt data). Tick every item in the epic brief with evidence.

## Boundaries & Constraints

**Always:**
- Test code only, except the single ReportHistoryService fix (D1).
- Security-slice tests use `@WebMvcTest(<Controller>.class)` + `@Import(SecurityConfig.class)` + `@MockitoBean JwtAuthFilter` whose `doFilter` stub sets the authentication then calls the chain — the pattern of `SecurityConfigAdminPathEnforcementTest`. Anonymous = stub that just calls the chain without authentication → 401 (`HttpStatusEntryPoint`).
- Every `@WebMvcTest` / admin controller test class clears `SecurityContextHolder` in `@AfterEach`.
- `mvn test` stays 0 failures / 0 errors (baseline 2308 tests).

**Never:**
- No production behaviour change other than D1. No new DB tests (none needed). No `deferred-work.md` entries.
- Do not touch the Category IllegalArgumentException → 500 behaviour (`*_currentlyReturns500` tests): owned by `v1s-14-15-category-hardening`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Category, anonymous | GET `/api/v1/categories` no auth | 401 |
| Category, SUPER_ADMIN | GET `/api/v1/categories` | 403 JSON `FORBIDDEN` (class `@PreAuthorize`) |
| Category, EMPLOYEE | DELETE `/api/v1/categories/{id}` | 403, use case never called |
| Category, EMPLOYEE / OWNER | GET list | 200 |
| Category, malformed UUID | PATCH `/api/v1/categories/not-a-uuid/toggle` | 400 `VALIDATION_ERROR` |
| JWT, tva null | revocation port returns null | chain proceeds, `extractIssuedAt` never called |
| JWT, iat == tva (to the second) / tva sub-second in same second | | accepted (contract `iat >= truncate(tva)`) |
| JWT, iat 1 s before truncated tva | | 401 `SESSION_REVOKED` |
| JWT, EMPLOYEE INACTIVE | validated `kv_` schema, row status INACTIVE | 401 `ACCOUNT_INACTIVE`, chain not called, SQL on `"kv_…"` |
| Resend, owner phone null/blank/owner missing | | `DomainException WHATSAPP_DELIVERY_FAILED`, WhatsApp never called, report not reset/saved |
| Resend SENT / FAILED(3 attempts) report | WhatsApp OK | attempts reset → 1, status SENT |

</intent-contract>

## Code Map

Backend root `keevo/backend/src`. Item numbers = order in the epic brief.

- `test/.../catalog/category/adapter/in/web/CategoryControllerTest.java` -- standalone (no security). Items 1–2. Add malformed-UUID test here (GlobalExceptionHandler is wired, `handleTypeMismatch` → 400 `VALIDATION_ERROR`).
- `main/.../catalog/category/adapter/in/web/CategoryController.java:37,125,156` -- class `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")`, toggle/delete `hasRole('OWNER')`. New `CategoryControllerSecurityTest` (same package) covers item 1.
- `test/.../catalog/stock/adapter/out/persistence/StockMovementRepositoryAdapterTest.java` -- item 3: all cases pass `variantId=null`; add a round-trip with non-null variantId (adapter maps it at `StockMovementRepositoryAdapter.java:93,109`).
- `test/.../identity/employee/adapter/out/persistence/impl/EmployeeRepositoryAdapterTest.java` -- items 4, 5, 11. `setField` reflection on `JpaBaseEntity.createdAt` (lines 66-81) → replace with a test-local subclass of `EmployeeJpaEntity` (not final) overriding `getCreatedAt()/getUpdatedAt()`. Add `findByUserId` empty case; add unknown-status case asserting `IllegalArgumentException` (D2).
- `test/.../messaging/whatsapp/adapter/out/external/FailoverWhatsAppAdapterTest.java` -- item 6: add (a) Wassender blank + Twilio throws → `WhatsAppDeliveryException`; (b) Wassender throws + Twilio unconfigured (`new TwilioProperties("", "", "")`) → exception, Twilio template never called.
- `test/.../messaging/whatsapp/adapter/out/external/TwilioWhatsAppAdapterTest.java` -- item 7: add `"237699000080"` → `To = whatsapp:+237699000080` (mirror `sendReport_sanitizesPhoneNumber`).
- `test/.../reporting/profitability/adapter/out/persistence/JdbcProfitabilityRepositoryTest.java` -- item 8: RowMappers of findRawByPeriod / findRawStoreByPeriod / findProductCosts / findTopStoreByUnits are already captured and asserted; only `findDailyMarginLast7` (mapper at `JdbcProfitabilityRepository.java:172`) is not → add one capture test.
- `main/.../reporting/report/application/service/ReportHistoryService.java:58-84` + its test -- items 9, 10, D1.
- `test/.../identity/auth/application/service/PasswordResetRateLimiterTest.java` -- item 12 ALREADY COVERED (v1s-12-12): `shouldBlockSixthRequestWithinOneHour` drives a `MutableTestClock`; close with evidence, no change.
- `test/.../shared/infrastructure/security/JwtAuthFilterTest.java:52-66` -- item 13: `setUp()` passes `null` jdbcTemplate → pass the `@Mock jdbcTemplate`; keep `filterWithJdbc()` callers working (make it return `filter`, or inline). Items 14 and 20: add tests per I/O matrix; reuse `buildClaims` and the stubbing pattern of `shouldAcceptValidTenantId` (line 357) / `shouldRejectTokenIssuedBeforeRevocation` (line 391). Filter logic: `JwtAuthFilter.java:164-171` (tva), `:203-212` (INACTIVE).
- `test/.../shared/infrastructure/config/ProdSecretsValidatorTest.java` -- item 15: `Files.setPosixFilePermissions` in key fixture (line ~46) and `JwtKeyPermissionsValidation` (229-280) → `Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))` in the fixture creation path and those tests.
- `test/.../shared/infrastructure/security/SecurityConfigAdminPathEnforcementTest.java` -- item 16: add EMPLOYEE → 403, anonymous → 401, authenticated with no authority → 403, OWNER POST → 403 (add a `@PostMapping` to the stub controller; CSRF is disabled in SecurityConfig — verify).
- Item 17 (`@AfterEach SecurityContextHolder.clearContext()`) and item 18 (`@MockBean` → `@MockitoBean`, `org.springframework.test.context.bean.override.mockito.MockitoBean`, Spring 6.2.7): the 9 files using `@MockBean` — `AdminTenantControllerTest`, `AdminCatalogControllerTest`, `AdminSubscriptionControllerTest`, `AdminSyncMonitoringControllerTest`, `AuditControllerTest`, `SubscriptionControllerTest`, `AuthControllerTest`, `OnboardingControllerTest`, `SecurityConfigAdminPathEnforcementTest`; plus `DeviceTokenControllerTest` and `ProductImportControllerTest` (standalone, set context, no clear). `AdminFeedbackControllerTest` already clears (line 58).
- `test/.../identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapterTest.java` -- item 19: capture the SQL string + RowMapper; assert SQL joins `public.tenants` on `schema_name = ?`; invoke mapper with mocked `ResultSet` (non-null Timestamp → Instant, null → null); assert `revokeAllSessions` / `revokeAllSessionsEverywhere` SQL.
- CurrentActor follow-up (from 15-9 review): `SubscriptionControllerTest`, `AdminSubscriptionControllerTest` (WebMvc) and new `DeviceTokenControllerSecurityTest`, `ProductImportControllerSecurityTest` (WebMvc). Controllers: `SubscriptionController.requireTenantRole` (OWNER|EMPLOYEE), `AdminSubscriptionController` → `CurrentActor.requireSuperAdmin` behind `/api/v1/admin/**`, `DeviceTokenController` (`/api/v1/devices/token`, authenticated, role null → "OWNER"), `ProductImportController.extractRole` (role null → 403 FORBIDDEN).

## Tasks & Acceptance

**Execution:**
- `CategoryControllerSecurityTest.java` (new) -- WebMvc slice: anonymous 401, SUPER_ADMIN 403, OWNER/EMPLOYEE GET 200, EMPLOYEE toggle/delete 403 with use case never invoked, OWNER delete 204 -- item 1.
- `CategoryControllerTest.java` -- add malformed UUID 400 test -- item 2.
- `StockMovementRepositoryAdapterTest.java` -- non-null variantId round trip, also asserting the entity passed to `save` -- item 3.
- `EmployeeRepositoryAdapterTest.java` -- fixture subclass instead of reflection; findByUserId empty; unknown status → IAE -- items 4, 5, 11.
- `FailoverWhatsAppAdapterTest.java` / `TwilioWhatsAppAdapterTest.java` -- items 6, 7.
- `JdbcProfitabilityRepositoryTest.java` -- daily-margin RowMapper -- item 8.
- `ReportHistoryService.java` + `ReportHistoryServiceTest.java` -- D1 + replace `resendReport_whenOwnerPhoneNotResolved_usesDefaultPhoneConstant` with null/blank/missing-owner tests; resend SENT and FAILED tests -- items 9, 10.
- `JwtAuthFilterTest.java` -- items 13, 14, 20.
- `ProdSecretsValidatorTest.java` -- item 15.
- `SecurityConfigAdminPathEnforcementTest.java` -- item 16.
- All files listed under items 17/18 -- `@AfterEach clearContext`, `@MockitoBean`.
- `JdbcTokenRevocationAdapterTest.java` -- item 19.
- `SubscriptionControllerTest.java` (+ anonymous 401, EMPLOYEE 200, no-authority 403), `AdminSubscriptionControllerTest.java` (+ anonymous 401, EMPLOYEE 403, use case never called), new `DeviceTokenControllerSecurityTest.java` (anonymous POST/DELETE 401, OWNER 200), new `ProductImportControllerSecurityTest.java` (anonymous 401; authenticated without role on `/drafts/count` → 403 FORBIDDEN; OWNER 200) -- CurrentActor follow-up.
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` -- tick each Story 15.7 item with test name / decision.

**Acceptance Criteria:**
- Given the story branch, when `mvn test` runs, then 0 failures / 0 errors and the test count is above 2308.
- Given `grep -rn "boot.test.mock.mockito.MockBean" keevo/backend/src/test`, then no match.
- Given the epic brief, then every Story 15.7 item is `[x]` with the covering test name or the recorded decision.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 31 findings — high 0, medium 1, low 20, false 10, maybe-false 0
- findings:
  - `[low]` `[reject]` (verification-gap) D1 refusal reuses WHATSAPP_DELIVERY_FAILED → 502 + "vérifiez votre connexion" — real but practically unreachable: `users.phone_number` is NOT NULL (V1 baseline :401) and normalised at registration, so only a tenant without an owner row hits it; previously the same case sent the report to a stranger. A dedicated ErrorCode adds client-visible surface for an unreachable path.
  - `[low]` `[reject]` (verification-gap) `ReportControllerTest.POST_resend_returns409_whenAlreadySent` mocks a REPORT_ALREADY_SENT the service never throws — pre-existing mock-only controller test, no user impact.
  - `[low]` `[reject]` (intent) item 9 changes production code (D1) — intended: the item asks to settle null/blank phone; decision recorded in Design Notes.
  - `[false]` `[reject]` (intent) item 5 CHECK constraint asserted only in prose — the CHECK is in `TenantSchemaProvisioner.java:478-479`; D2 keeps behaviour unchanged.
  - `[low]` `[reject]` (intent) DeviceToken CurrentActor FORBIDDEN path unreachable via slice — by design (`anyRequest().authenticated()` stops anonymous first); `CurrentActorTest` covers `actorId()` fail-closed.
  - `[low]` `[reject]` (intent + edge-case) AdminSubscription EMPLOYEE 403 comes from the URL rule, `requireSuperAdmin` never reached — second barrier is unit-tested by `CurrentActorTest.requireSuperAdmin_passesForSuperAdmin_forbiddenOtherwise`.
  - `[low]` `[reject]` (intent + blind) ProductImport slice covers only `/drafts/count` — every endpoint shares the same `extractRole()` and the global filter rule; one endpoint proves both.
  - `[false]` `[reject]` (intent) item 15 mechanism differs from the literal guard — the spec's intent (no failure on non-POSIX) is met; `ProdSecretsValidator.java:302-304` skips the check on UnsupportedOperationException, and the permission tests carry `assumeTrue(posix)`.
  - `[false]` `[reject]` (intent) item 8 "already covered" rests on unseen tests — existing captures verified in planning (findRawByPeriod/findRawStoreByPeriod/findProductCosts/findTopStoreByUnits).
  - `[false]` `[reject]` (intent) Story 14.16 + sprint key are out-of-intent additions — mandated by the project rule (route out-of-scope items to a key + brief).
  - `[false]` `[reject]` (intent) no green-suite evidence in diff — `mvn test` run: 2354 tests, 0 failures, 0 errors (recorded in Auto Run Result).
  - `[low]` `[reject]` (blind) D1 HTTP status/message — same as first row.
  - `[low]` `[patch]` (blind) D1 refusal logs nothing — added `log.warn` with report id + tenant id before the throw.
  - `[low]` `[reject]` (blind) no controller-level test of D1 — controller only delegates; WHATSAPP_DELIVERY_FAILED mapping is pre-existing.
  - `[medium]` `[patch]` (blind + edge-case) item 17 only closed for admin/slice tests; 16 standalone tests + JwtAuthFilterTest set an authentication without clearing — added `@AfterEach clearContext()` to all 17 classes; grep `setAuthentication` without `clearContext` now empty.
  - `[low]` `[patch]` (blind + edge-case) DeviceToken `role == null → "OWNER"` fallback and authenticated DELETE untested — added `noAuthority_post_fallsBackToOwnerRole` and `owner_delete_returns200`.
  - `[low]` `[reject]` (blind) ProductImport one endpoint — duplicate of intent row above.
  - `[low]` `[patch]` (blind) Category slice lacks OWNER toggle 200 — added `owner_toggle_returns200`; SUPER_ADMIN on toggle/delete and EMPLOYEE create/rename rejected (class rule already proven on GET).
  - `[low]` `[reject]` (blind) JWT boundary tests do not assert SecurityContext — context population is covered by `should_set_tenant_context_and_proceed_for_valid_jwt`; these tests target the revocation boundary. Context clearing added via item 17 patch.
  - `[low]` `[patch]` (blind) `filterWithJdbc()` dead indirection — inlined and removed.
  - `[false]` `[reject]` (blind) D2 ignores multi-row reads — the CHECK constraint makes a corrupt row unstorable for every query, not just findById.
  - `[low]` `[reject]` (blind) exact SQL fragments in JdbcTokenRevocationAdapterTest are brittle — the item explicitly asks to assert the SQL; churn risk accepted.
  - `[low]` `[reject]` (blind) import-order / @AfterEach placement churn — cosmetic.
  - `[false]` `[reject]` (blind) tracking artifacts disagree (sprint status, empty logs) — sprint status and logs are written at finalisation (this step).
  - `[false]` `[reject]` (blind) item 15 departs from spec silently — see intent row; the deviation keeps non-permission tests running and is documented in the epic tick.
  - `[low]` `[reject]` (blind) new Failover tests only check exception type — message content already pinned by `sendReport_whenBothFail_throwsWhatsAppDeliveryException_withBothProviderNames`.
  - `[low]` `[reject]` (edge-case) D1 502 mapping — same as first row.
  - `[false]` `[reject]` (edge-case) separator-only phone passes isBlank — phones are normalised to `+<digits>` at registration (v1s-12-12), cannot be stored.
  - `[false]` `[reject]` (edge-case) NULL employee status → NPE not IAE — `employees.status` is NOT NULL DEFAULT 'ACTIVE'.
  - `[low]` `[reject]` (edge-case) blank phone refused on resend but sent by retry/daily paths — pre-existing in other services and unreachable (NOT NULL + normalisation).
  - `[low]` `[reject]` (edge-case) item-17 leak and DeviceToken no-role — duplicates, patched above.

## Design Notes

**D1 — resend without an owner phone (item 9).** `ReportHistoryService.resendReport` falls back to the hardcoded `+243000000000` when the owner or their phone is missing, i.e. it would send a tenant's sales report to an unrelated number. The other delivery paths already refuse to guess (`ReportDeliveryRetryService` skips, `DailyReportGenerator` → IN_APP_ONLY). Decision: resolve the phone first; if the owner is missing or the phone is null/blank, throw `DomainException(WHATSAPP_DELIVERY_FAILED, …)` before `resetForResend()` — no WhatsApp call, no save, report untouched. `DEFAULT_OWNER_PHONE` is removed from this class.

**D2 — corrupt employee status (item 5).** The tenant `employees.status` column carries `CHECK (status IN ('ACTIVE','INACTIVE'))` (`TenantSchemaProvisioner.java:478`), so an unknown value cannot be stored. Decision: keep fail-fast `EmployeeStatus.valueOf` (no silent mapping to ACTIVE/INACTIVE, which would grant or deny access on corrupt data); the test pins the `IllegalArgumentException` contract.

**Out of scope, routed:** `DayClosureWhatsAppListener` (`commerce/sale/application/service/DayClosureWhatsAppListener.java:43,73`) sends every day-closure report to `System.getProperty("keevo.owner.whatsapp", "+243000000000")` — same hardcoded-number leak as D1 but in a different module with no owner lookup; routed to a new backlog key `v1s-14-16-day-closure-owner-phone`.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CategoryController*Test,StockMovementRepositoryAdapterTest,EmployeeRepositoryAdapterTest,*WhatsAppAdapterTest,JdbcProfitabilityRepositoryTest,ReportHistoryServiceTest,JwtAuthFilterTest,ProdSecretsValidatorTest,SecurityConfigAdminPathEnforcementTest,JdbcTokenRevocationAdapterTest,*SubscriptionControllerTest,DeviceTokenController*Test,ProductImportController*Test,Admin*ControllerTest,AuditControllerTest,AuthControllerTest,OnboardingControllerTest'` -- expected: all green.
- `cd keevo/backend && mvn test` -- expected: 0 failures, 0 errors.

## Auto Run Result

Status: done

**Summary.** All 20 Story 15.7 items closed (19 by new/changed tests, item 12 already covered by v1s-12-12 `PasswordResetRateLimiterTest.shouldBlockSixthRequestWithinOneHour`), plus access-denial slices for the four controllers migrated to `CurrentActor` in v1s-15-9. One production change: D1, `ReportHistoryService.resendReport` refuses (WHATSAPP_DELIVERY_FAILED + warn log) instead of sending the report to the hardcoded `+243000000000` when no owner phone exists. D2: fail-fast `EmployeeStatus.valueOf` kept (DB CHECK), pinned by a test.

**Files changed (keevo/backend/src).**
- `main/.../reporting/report/application/service/ReportHistoryService.java` — D1.
- New tests: `CategoryControllerSecurityTest`, `DeviceTokenControllerSecurityTest`, `ProductImportControllerSecurityTest` (WebMvc + real SecurityConfig).
- Extended: `CategoryControllerTest`, `StockMovementRepositoryAdapterTest`, `EmployeeRepositoryAdapterTest` (fixture subclass, no reflection), `FailoverWhatsAppAdapterTest`, `TwilioWhatsAppAdapterTest`, `JdbcProfitabilityRepositoryTest`, `ReportHistoryServiceTest`, `JwtAuthFilterTest`, `ProdSecretsValidatorTest`, `SecurityConfigAdminPathEnforcementTest`, `JdbcTokenRevocationAdapterTest`, `SubscriptionControllerTest`, `AdminSubscriptionControllerTest`.
- `@MockBean` → `@MockitoBean` in 9 classes; `@AfterEach clearContext()` in every test class that sets an authentication (~30 classes).
- Trackers: Story 15.7 items ticked in `epics-remediation-audit.md`; new Story 14.16 brief + `v1s-14-16-day-closure-owner-phone: backlog`.

**Review.** 1 pass, 31 findings: 5 patched (1 medium, 4 low), 0 deferred, 26 rejected with reasons (see Review Triage Log). Routed out of scope: `DayClosureWhatsAppListener` hardcoded owner phone → `v1s-14-16-day-closure-owner-phone`.

**Follow-up review recommendation:** false — patched: high 0, medium 1, low 4.

**Verification.** Targeted command from Verification: green. `mvn test` (after review patches): 2354 tests, 0 failures, 0 errors, 0 skipped (baseline 2308).

**Residual risks.** D1 refusal surfaces as 502 "vérifiez votre connexion" (path practically unreachable — phone NOT NULL). Security slices stub `JwtAuthFilter`, so JWT→authority mapping stays covered only by `JwtAuthFilterTest`.
