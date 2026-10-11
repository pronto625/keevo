# Epic 15 Context: Discipline de tests & dette technique (Pistes B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization remediation epic (v1s-epic-15-tests-debt-v1, v1s-15-* keys), NOT the refonte-modulaire epic 15 (15-1..15-8 keys in sprint-status). -->

## Goal

Bring the V1 line's tests up to the project's testing standard and pay down the technical debt found by the 2026-07-20 audit and by later code reviews. The standard is strict TDD, real-database persistence tests, slice tests for wiring and security, and E2E tests on Flutter. Nothing here blocks the V1 release; it is hygiene. The V1 tracker covers only the tag-C stories, meaning V1 patches that the refonte also absorbs. Tag-B stories (15.1, 15.2, 15.4, 15.6) belong to the refonte line and are excluded. Stories 15.3, 15.5 and 15.11 are done. Stories 15.7 to 15.10 are item lists built from review deferrals that were redistributed on 2026-10-11. Each has the same interim AC: every listed item is either resolved or explicitly rejected with a recorded reason (tick the box and note the commit or decision).

## Stories

- Story 15.1: Testcontainers PostgreSQL base + @DataJpaTest migration [B, refonte, out of V1 scope]
- Story 15.2: Flutter integration_test E2E + riverpod_test [B, refonte, out of V1 scope]
- Story 15.3: Missing RED tests for untested shipped classes [C] (done)
- Story 15.4: ArchUnit/Modulith ratchet mode [B, refonte, out of V1 scope]
- Story 15.5: Reconcile deferred-work, dead code, TODOs [C] (done)
- Story 15.6: Standardize pagination envelope [B, refonte, out of V1 scope]
- Story 15.11: Testcontainers integration test base + pending Postgres tests [C] (done)
- Story 15.7: Backend unit/slice test gaps [C]
- Story 15.8: Flutter test hygiene (red tests, mocks, missing widget tests) [C]
- Story 15.9: Backend refactor hygiene (duplicated helpers, constants, casts) [C] (in progress)
- Story 15.10: Flutter code hygiene + stale docs [C]

## Requirements & Constraints

- **Strict TDD.** Every change goes RED, then GREEN, then REFACTOR, and each story starts by writing its RED tests. Work in this order: domain model, use case, repository, controller/provider, widget. A story is done only when everything is green.
- **Tests must exercise real code.** A test that still passes after the production class is deleted is invalid. Mock only at system boundaries, never the class under test.
- **Persistence runs on real PostgreSQL.** JPA behaviour (ID strategy, constraints, @Version, lifecycle, JSONB), Flyway migrations and SQL must be checked against a real database. Mocked repositories and mocked migrations do not count.
- **Spring wiring is tested for real.** Use real context slices (`@WebMvcTest` with spring-security-test, `@SpringBootTest`) for security rules (`@PreAuthorize`, path enforcement, anonymous → 401), bean wiring and HTTP mapping. A standalone MockMvc setup does not count for security.
- **Concurrency is tested, not reasoned about.** Atomic updates and optimistic locks need multi-thread or multi-connection tests, for example a CountDownLatch start gate. Replaying a sync operation must return the same id and must not apply twice.
- **Refactors must not change behaviour.** For 15.9, keep the full backend suite green (about 2,229 tests, 0 failures). Fix behaviour only where the item calls for it, and add a test for each fix: the tenant-deletion races, the fallback label shown when the firstName claim is missing, and the requireSuperAdmin decision if it is kept.
- **Trackers stay truthful.** `deferred-work.md` must stay empty. Each new deferral goes to the story that owns the affected code, as a `[Deferred-from: …]` checkbox. If that story is done, reopen it in sprint-status with a dated comment.
- **Flutter changes bump the version.** Every commit that touches the mobile app must bump the version in `pubspec.yaml` (applies to 15.8 and 15.10).

## Technical Decisions

- **Backend stack:** Spring Boot 3.5.x, Java 21, JPA, Flyway and PostgreSQL. Tests use JUnit 5 + Mockito for units, `@WebMvcTest` for REST and security, and the shared Testcontainers PostgreSQL 16 base for repository, Flyway and full-context tests (H2 has been removed). Use `@MockitoBean`; `@MockBean` is deprecated. Inject a `Clock` bean for time-dependent logic so tests can control time (the rate-limiter tests need this).
- **Schema model:** each tenant has its own schema (`kv_xxxxxx`), selected through a ThreadLocal. The `public` schema is managed by Flyway versioned migrations with `ddl-auto=validate`. Tenant schemas are created by JDBC DDL at registration.
- **Hexagonal architecture:** ports and adapters. Domain models have no framework dependencies and validate their invariants (`requireNonNull`, as `Tenant`/`User` already do). Controllers go through use cases, never directly to repositories.
- **RBAC helpers have one source of truth.** Store-scope extraction, owner-role checks and actor extraction should live in one shared auth helper (AuthDetails or a security helper). In the refonte this becomes `identity.api`. Role names must come from the Role enum, never from magic strings such as `SUPER_ADMIN`.
- **Tenant lifecycle invariant:** `DELETED` means the tenant's schema is gone. Cancelling a deletion must be a conditional update (`WHERE status = 'DELETION_PENDING'`) that fails when it changes 0 rows, and it must refuse if the schema has already been dropped. Admin force-delete must go through the same deletion routine as the scheduler; it must not just flip the status. The scheduler processes one transaction per tenant, locks the tenant row (FOR UPDATE), drops the schema before writing DELETED, and sends WhatsApp only after commit.
- **Defensive numeric coercion in sync/delta code:** never use a bare `((Number) x).intValue()` or an unchecked cast. Hoist the existing integral-int guard into the abstract sync handler and apply it in every handler. Delta providers should type-check values and use long or BigDecimal instead of truncating.
- **WhatsApp port:** `isConfigured()` must be abstract so a new adapter cannot silently claim to be configured.
- **API envelope:** success is `{data, meta:{page,total,…}}`; errors are `{error, code, domainCode, details, timestamp}`. Validate `paginated()` inputs (page, size, totals).
- **Flutter tests:** `test` + `mocktail` for units, `ProviderContainer`/`riverpod_test` for providers, WidgetTester for widgets, `integration_test` for E2E. Set `GoogleFonts.config.allowRuntimeFetching = false` in `setUpAll()`, dispose containers with `addTearDown`, and prefer mocktail mocks over hand-rolled fakes.

## Cross-Story Dependencies

- 15.11 (done) provides the real-Postgres base that 15.7 and later persistence and concurrency tests build on. It also routed three tenant-deletion race items to 15.9.
- 15.9 refactors code delivered by 12.3/12.5/12.6/12.9 (RBAC), 14.5 (account deletion, feedback), 14.8 (delta providers, pagination wrapper), 14.10/14.13 (product actor, quick-add) and 13.8 (sync handlers). 15.7 adds tests over some of the same controllers and the security chain. If both are in flight, land the helper extraction first to avoid conflicts.
- 15.6 (pagination envelope, refonte) depends on the `paginated()` validation done in 15.9.
- 15.8 and 15.10 touch Flutter code from Epic 13 (13.4–13.6) and Epic 16. The DayCloseButton doc cleanup in 15.10 spans the UX spec and the epic 4 docs.
