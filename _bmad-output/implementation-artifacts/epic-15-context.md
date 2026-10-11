# Epic 15 Context: Discipline de tests & dette technique (Pistes B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization remediation epic (v1s-epic-15-tests-debt-v1, v1s-15-* keys), NOT the refonte-modulaire epic 15 (15-1..15-8 keys in sprint-status). -->

## Goal

Bring the V1 line's tests up to the project's testing standard (strict TDD, real-database persistence tests, slice tests for wiring/security, E2E on Flutter) and pay down the technical debt surfaced by the 2026-07-20 audit and by later code reviews. Nothing here blocks the V1 release; it is hygiene. On the V1 tracker only the tag-C stories (V1 patch that the refonte also absorbs) are in scope. Tag-B stories (15.1, 15.2, 15.4, 15.6) belong to the refonte line and are excluded. 15.3 and 15.5 are done. 15.7–15.11 are item-list stories built from review deferrals redistributed on 2026-10-11. Their interim AC: every listed item is resolved or explicitly rejected with a recorded reason (tick the box, note the commit or decision).

## Stories

- Story 15.1: Testcontainers PostgreSQL base + @DataJpaTest migration [B, refonte, out of V1 scope]
- Story 15.2: Flutter integration_test E2E + riverpod_test [B, refonte, out of V1 scope]
- Story 15.3: Missing RED tests for untested shipped classes [C] (done)
- Story 15.4: ArchUnit/Modulith ratchet mode [B, refonte, out of V1 scope]
- Story 15.5: Reconcile deferred-work, dead code, TODOs [C] (done)
- Story 15.6: Standardize pagination envelope [B, refonte, out of V1 scope]
- Story 15.11: Testcontainers integration test base + pending Postgres tests [C] (in progress)
- Story 15.7: Backend unit/slice test gaps [C]
- Story 15.8: Flutter test hygiene (red tests, mocks, missing widget tests) [C]
- Story 15.9: Backend refactor hygiene (duplicated helpers, constants, casts) [C]
- Story 15.10: Flutter code hygiene + stale docs [C]

## Requirements & Constraints

- **Strict TDD.** Every feature goes RED → GREEN → REFACTOR. Each story's first task is writing the RED tests. Order: domain model → use case → repository → controller/provider → widget, and it is done only when everything is green.
- **Tests must exercise real code.** A test that still passes after the production class is deleted is invalid. Mock only at system boundaries, never the class under test.
- **Persistence on a real database.** JPA behaviour (ID strategy, constraints, @Version, lifecycle, JSONB) and Flyway migrations/SQL must be verified against real PostgreSQL. Mocked repositories or mocked migration classes do not count.
- **Real Spring wiring.** Security rules, bean wiring and HTTP mapping are tested with real context slices (`@WebMvcTest` + spring-security-test, `@SpringBootTest`).
- **CI-portable.** Integration tests must not depend on a pre-existing local PostgreSQL. Today some connect to `localhost:5444`, which is why the existing `@SpringBootTest` classes fail in the baseline. Testcontainers must provide the database.
- **Concurrency verified, not inspected.** Atomic single-statement updates and optimistic locks get multi-thread / multi-connection tests (e.g. CountDownLatch). Examples: concurrent transfer complete, parallel wrong/correct OTPs (attempts capped at 5, exactly one success), concurrent device claims never reassigning a user.
- **Idempotent replay.** Persistence-level tests must show that replaying a sync operation (e.g. a transfer) returns the same id and does not double-apply.
- **Trackers stay truthful.** `deferred-work.md` must stay empty. Any new deferral goes to the story that owns the affected code (as a `[Deferred-from: …]` checkbox in that story). If that story is done, it is reopened in sprint-status with a dated comment.

## Technical Decisions

- **Backend stack:** Spring Boot 3.5.x, Java 21, JPA, Flyway, PostgreSQL. Test tools are JUnit 5 + Mockito (unit), `@WebMvcTest` MockMvc (REST/security), `@DataJpaTest` on Testcontainers PostgreSQL (repositories), `@SpringBootTest` + Testcontainers (end to end), plus custom multi-tenant isolation tests. Use `@MockitoBean`, not the deprecated `@MockBean`.
- **Testcontainers base:** a shared `@Testcontainers` base with a static `PostgreSQLContainer` (postgres:16). docker-java versions must be aligned: pin all of api, transport and core so `docker-java-core` does not drift. Check with `mvn dependency:tree`.
- **Schema model:** schema-per-tenant (`kv_xxxxxx`), with the tenant set through a ThreadLocal. The `public` schema is managed by Flyway versioned migrations (`V{n}__*.sql`, baseline-on-migrate, baseline version 0) with `ddl-auto=validate`. Tenant schemas are provisioned programmatically by JDBC DDL at registration. Integration tests should cover both public migrations and tenant provisioning/isolation.
- **Flyway baseline test fixes:** the V2 idempotency assertion must target V2 only, because later migrations now exist. Dropping a stale database must first terminate its backends. Remove a profile annotation that has no profile file behind it.
- **Context-load test:** once the container base exists, a `@SpringBootTest` context-load test must prove the auth beans wire, including the `Clock` bean they take as a constructor argument.
- **Flutter stack:** `test` + `mocktail` (unit), `riverpod_test`/`ProviderContainer` (providers), `flutter_test` WidgetTester (widgets), `integration_test` (E2E). Set `GoogleFonts.config.allowRuntimeFetching = false` in `setUpAll()`. Dispose ProviderContainers with `addTearDown`. Use mocktail mocks instead of hand-rolled fakes.
- **Architecture conventions for refactors:** hexagonal (ports & adapters); domain layers have no framework dependencies; controllers go through the application layer. RBAC helpers (store-scope extraction, owner checks, actor extraction, role constants) get a single shared source of truth, and role names come from the Role enum, not magic strings.
- **API envelope:** success is `{data, meta:{page,total,…}}`, errors are `{error, code, domainCode, details, timestamp}`.

## Cross-Story Dependencies

- 15.11 builds the real-Postgres base that 15.7 and later persistence/concurrency tests rely on. It also absorbs the V1 share of 15.1 and the integration tests that Epic 13 stories (13.1 transfer locking, 13.5 sale merge-in-place) and 12.12 (auth/session concurrency, Clock wiring) deferred to it.
- 15.7 closes the test gaps left by the reviews of 15.3, 12.1/12.2/12.3/12.5 and 14.12. 15.9 refactors code from 12.x/13.8/14.x. Keep tests green across both.
- 15.8 and 15.10 touch Flutter code from Epic 13 (13.4–13.6) and Epic 16. Every commit touching the mobile app must bump the app version in `pubspec.yaml`.
- 15.6 (pagination) relates to 14.8 and to `ApiResponseWrapper.paginated()` validation in 15.9.
