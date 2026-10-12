# Epic 15 Context: Discipline de tests & dette technique (Pistes B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization remediation epic (v1s-epic-15-tests-debt-v1, v1s-15-* keys), NOT the refonte-modulaire epic 15 (15-1..15-8 keys in sprint-status). -->

## Goal

Bring the V1 line's tests up to the project's testing standard and pay down the technical debt found by the 2026-07-20 audit and by later code reviews. The standard is strict TDD, real-database persistence tests, slice tests for wiring and security, and E2E tests on Flutter. Nothing here blocks the V1 release; it is hygiene. The V1 tracker covers only the tag-C stories, meaning V1 patches that the refonte also absorbs. Tag-B stories (15.1, 15.2, 15.4, 15.6) belong to the refonte line and are excluded. Stories 15.3, 15.5, 15.7, 15.9 and 15.11 are done, so the backend work is finished. 15.8 (Flutter test hygiene) is in progress and 15.10 (Flutter code hygiene + stale docs) is in the backlog. The remaining work is Flutter-only. Stories 15.7 to 15.10 are item lists built from review deferrals that were redistributed on 2026-10-11. Each has the same interim AC: every listed item is either resolved or explicitly rejected with a recorded reason (tick the box and note the commit or decision).

## Stories

- Story 15.1: Testcontainers PostgreSQL base + @DataJpaTest migration [B, refonte, out of V1 scope]
- Story 15.2: Flutter integration_test E2E + riverpod_test [B, refonte, out of V1 scope]
- Story 15.3: Missing RED tests for untested shipped classes [C] (done)
- Story 15.4: ArchUnit/Modulith ratchet mode [B, refonte, out of V1 scope]
- Story 15.5: Reconcile deferred-work, dead code, TODOs [C] (done)
- Story 15.6: Standardize pagination envelope [B, refonte, out of V1 scope]
- Story 15.11: Testcontainers integration test base + pending Postgres tests [C] (done)
- Story 15.7: Backend unit/slice test gaps [C] (done)
- Story 15.8: Flutter test hygiene (red tests, mocks, missing widget tests) [C] (in progress)
- Story 15.9: Backend refactor hygiene (duplicated helpers, constants, casts) [C] (done)
- Story 15.10: Flutter code hygiene + stale docs [C] (backlog)

## Requirements & Constraints

- **Strict TDD.** Every change goes RED, then GREEN, then REFACTOR, and each story starts by writing its RED tests. Work in this order: domain model, use case, repository, controller/provider, widget. A story is done only when everything is green.
- **Tests must exercise real code.** A test that still passes after the production class is deleted is invalid. Mock only at system boundaries, never the class under test.
- **Persistence runs on real PostgreSQL.** JPA behaviour (ID strategy, constraints, @Version, lifecycle, JSONB), Flyway migrations and SQL must be checked against a real database. Mocked repositories and mocked migrations do not count.
- **Spring wiring is tested for real.** Use real context slices (`@WebMvcTest` with spring-security-test, `@SpringBootTest`) for security rules (`@PreAuthorize`, path enforcement, anonymous → 401), bean wiring and HTTP mapping. A standalone MockMvc setup does not count for security.
- **Concurrency is tested, not reasoned about.** Atomic updates and optimistic locks need multi-thread or multi-connection tests, for example a CountDownLatch start gate. Replaying a sync operation must return the same id and must not apply twice.
- **No regressions.** Keep the full backend suite green (about 2,308 tests, 0 failures after 15.9). Test-only stories (15.7) change production code only when an item exposes a real defect, and then add the test that proves the fix (e.g. unknown EmployeeStatus on corrupt data needs a handling decision). Use `@MockitoBean`, never `@MockBean`; clear `SecurityContextHolder` after each test that sets it; guard POSIX-only file-permission tests with an assumption so they skip on non-POSIX runners.
- **Trackers stay truthful.** `deferred-work.md` must stay empty. Each new deferral goes to the story that owns the affected code, as a `[Deferred-from: …]` checkbox. If that story is done, reopen it in sprint-status with a dated comment.
- **Flutter changes bump the version.** Every commit that touches the mobile app must bump the version in `pubspec.yaml` (applies to 15.8 and 15.10).

## Technical Decisions

- **Backend stack:** Spring Boot 3.5.x, Java 21, JPA, Flyway and PostgreSQL. Tests use JUnit 5 + Mockito for units, `@WebMvcTest` for REST and security, and the shared Testcontainers PostgreSQL 16 base for repository, Flyway and full-context tests (H2 has been removed). Use `@MockitoBean`; `@MockBean` is deprecated. Inject a `Clock` bean for time-dependent logic so tests can control time (the rate-limiter tests need this).
- **Schema model:** each tenant has its own schema (`kv_xxxxxx`), selected through a ThreadLocal. The `public` schema is managed by Flyway versioned migrations with `ddl-auto=validate`. Tenant schemas are created by JDBC DDL at registration.
- **Hexagonal architecture:** ports and adapters. Domain models have no framework dependencies and validate their invariants (`requireNonNull`, as `Tenant`/`User` already do). Controllers go through use cases, never directly to repositories.
- **RBAC helpers have one source of truth.** Store scope, owner checks, actor id/label and the super-admin barrier go through the shared `CurrentActor` helper (fail-closed; the actor label falls back to a generic French label, never the raw UUID). In the refonte this becomes `identity.api`. Role names come from the Role enum, never magic strings. `requireSuperAdmin()` is kept as a second barrier behind the `/api/v1/admin/**` URL rule. Security-slice tests should assert both layers (EMPLOYEE, anonymous → 401, token with no role, non-GET methods).
- **Tenant lifecycle invariant:** `DELETED` means the tenant's schema is gone. Cancelling a deletion must be a conditional update (`WHERE status = 'DELETION_PENDING'`) that fails when it changes 0 rows, and it must refuse if the schema has already been dropped. Cancels (owner and admin) are conditional UPDATEs that also require the schema to still exist. Admin force-delete goes through the same deletion routine as the scheduler (`deleteNow`); it must not just flip the status. The scheduler processes one transaction per tenant, locks the tenant row (FOR UPDATE), drops the schema before writing DELETED, and sends WhatsApp only after commit.
- **Defensive numeric coercion in sync/delta code:** never use a bare `((Number) x).intValue()` or an unchecked cast. Sync handlers use the integral-int guards in the abstract sync handler; delta providers use the shared type-checked value helpers (int when it fits, long otherwise).
- **WhatsApp port:** `isConfigured()` is abstract; adapters are NoOp, Wassender, Twilio and a Failover composite. Failover tests must cover blank primary + failing secondary and an unconfigured secondary.
- **API envelope:** success is `{data, meta:{page,total,…}}`; errors are `{error, code, domainCode, details, timestamp}`. A malformed UUID path parameter must map to 400, not 500. `paginated()` rejects invalid page/size/totals.
- **Flutter tests:** `test` + `mocktail` for units, `ProviderContainer`/`riverpod_test` for providers, WidgetTester for widgets, `integration_test` for E2E. Set `GoogleFonts.config.allowRuntimeFetching = false` in `setUpAll()`, dispose every `ProviderContainer` with `addTearDown(container.dispose)`, and use mocktail mocks (e.g. a `MockSyncService`) instead of hand-rolled no-op fakes. `flutter test` must end fully green. A test that is red only because a design token changed (e.g. a rebranded banner colour) gets its expected value updated to the current theme; it is not deleted.
- **Flutter code conventions (15.10):** use status enums instead of the raw strings `'ACTIVE'`, `'DRAFT'` and `'COMPLETED'`. Use one `routeForRole()` helper for the OWNER → `/dashboard` and EMPLOYEE → `/pos` choice. Use one debug HttpClient factory for the dev-only certificate bypass. Take the role at registration from the server result, not a hardcoded value. Cache session state in a provider that is refreshed on login, logout and token refresh, so the router does not read secure storage on every navigation. Do not hold a captured `Ref` past its scope. Name types after live concepts: `DayClosureState`, not the deleted DayCloseButton.

## Cross-Story Dependencies

- 15.11 (done) provides the real-Postgres base that 15.7 and later persistence and concurrency tests build on. It also routed three tenant-deletion race items to 15.9.
- 15.7 tests code from 12.1/12.2/12.3/12.5 (secrets validator, token revocation, admin path enforcement, JWT filter), 14.12 (password-reset rate limiter) and the classes covered by 15.3. 15.9 (done) already refactored the controllers and security helpers it touches, so new tests target `CurrentActor` and the post-refactor admin barrier, not the deleted per-controller copies.
- 15.6 (pagination envelope, refonte) depends on the `paginated()` validation done in 15.9.
- 15.8 adds the missing tests for code shipped by 13.5/13.6 (draft sales, checkout), 16.1 (FCM cleanup on logout; an OWNER variant is needed), 16.4 (catalog search), 16.8 (reset-password provider), 16.9 (router: duplicate-route guard) and HF-2 (EMPLOYEE-scoped today summary). The E2E harness itself (15.2) is refonte work and not a prerequisite.
- 15.10 refactors code from 12.7 (router/session, debug HTTP client), 13.4 (SyncService/Drift contract), 13.6 (status strings) and 16.10 (DayCloseButton removal). 15.10's renames and enum changes will touch files that 15.8 tests, so run 15.8's tests again after 15.10. The DayCloseButton doc cleanup spans the UX spec and the epic 4 docs.
