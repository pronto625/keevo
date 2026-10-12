# Epic 15 Context: Discipline de tests & dette technique (Pistes B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization remediation epic (v1s-epic-15-tests-debt-v1, v1s-15-* keys), NOT the refonte-modulaire epic 15 (15-1..15-8 keys in sprint-status). -->

## Goal

Bring the V1 line up to the project's testing standard and pay down the technical debt found by the audit and by later code reviews. The standard is strict TDD, real-database persistence tests, real Spring slices for wiring and security, and green, deterministic Flutter tests. Nothing here blocks the V1 release; it is hygiene. The V1 tracker covers only the tag-C stories, meaning V1 patches that the refonte also absorbs. Tag-B stories (15.1, 15.2, 15.4, 15.6) belong to the refonte line and are out of scope. All backend stories (15.3, 15.5, 15.7, 15.9, 15.11) and the Flutter test story 15.8 are done. The only remaining work is 15.10 (Flutter code hygiene + stale DayCloseButton docs), which is in progress. Stories 15.7 to 15.10 are item lists built from redistributed review deferrals. Each has the same interim AC: every listed item is either resolved or explicitly rejected with a recorded reason (tick the box and note the commit or decision next to it).

## Stories

- Story 15.1: Testcontainers PostgreSQL base + @DataJpaTest migration [B, refonte, out of scope]
- Story 15.2: Flutter integration_test E2E + riverpod_test [B, refonte, out of scope]
- Story 15.3: Missing RED tests for untested shipped classes [C] (done)
- Story 15.4: ArchUnit/Modulith ratchet mode [B, refonte, out of scope]
- Story 15.5: Reconcile deferred-work, dead code, TODOs [C] (done)
- Story 15.6: Standardize pagination envelope [B, refonte, out of scope]
- Story 15.11: Testcontainers integration test base + pending Postgres tests [C] (done)
- Story 15.7: Backend unit/slice test gaps [C] (done)
- Story 15.8: Flutter test hygiene (red tests, mocks, missing widget tests) [C] (done)
- Story 15.9: Backend refactor hygiene (duplicated helpers, constants, casts) [C] (done)
- Story 15.10: Flutter code hygiene + stale docs [C] (in progress)

## Requirements & Constraints

- **Strict TDD.** Every change goes RED, then GREEN, then REFACTOR. Work in this order: domain model, use case, repository, provider, widget. A story is done only when everything is green.
- **Tests must exercise real code.** A test that still passes after the production code is deleted is invalid. Mock only at system boundaries, never the class under test.
- **Refactors keep behaviour.** Renames, enum extraction and helper factoring must leave every existing test green. If an item exposes a real defect, fix it and add the test that proves the fix.
- **Flutter suite stays green and deterministic.** Every app batch of the chunked runner (`run-tests-chunked.sh app`: test/core, widget_test.dart, each test/features/*) must pass with 0 failures, about 1,650 tests after 15.8. Tests must not depend on the real clock or on execution order. Use injected clocks, fake timers or completers, never real waits.
- **Trackers stay truthful.** `deferred-work.md` must stay empty. Each new deferral goes to the story that owns the affected code, as a `[Deferred-from: …]` checkbox. If that story is done, reopen it in sprint-status with a dated comment.
- **Flutter changes bump the version.** Every commit that touches the mobile app must bump the version in `pubspec.yaml`. The current line is 1.1.31+30.
- **Doc-only items** (DayCloseButton references, SyncService contract, today-summary semantics) count as resolved once the docs or doc comments are corrected. They need no test.

## Technical Decisions

- **Flutter architecture:** Riverpod for state, Drift (SQLite) for local storage, GoRouter for routing. Writes go to the backend first when online and to local Drift + sync_queue when offline. After every write, notifiers refresh from Drift. The domain layer has no Flutter or Drift dependencies.
- **SyncService is a Strategy interface** (REST implementation today, others possible). Every implementation must share the app's single Drift connection. Document that contract on the interface, and add an integration test only when a second implementation appears.
- **Flutter tests:** `test` + `mocktail` for units, `ProviderContainer` for providers, WidgetTester for widgets. Set `GoogleFonts.config.allowRuntimeFetching = false` in `setUpAll()` and dispose every container with `addTearDown(container.dispose)`. Use mocktail mocks (e.g. `MockSyncService`), not hand-rolled fakes. When a test is red only because a design token changed, update its expected value to the current theme instead of deleting the test.
- **15.10 code conventions:**
  - Use `ProductStatus`/`SaleStatus` enums instead of the raw strings `'ACTIVE'`, `'DRAFT'` and `'COMPLETED'`.
  - Use one `routeForRole()` helper for OWNER → `/dashboard` and EMPLOYEE → `/pos`. The router, the startup route and the shop-name page all call it.
  - Use one debug HttpClient factory for the dev-only certificate bypass.
  - Take the role at registration from the server's registration result, not a hardcoded `'OWNER'`.
  - Cache session state in a provider that is refreshed on login, logout and token refresh, so the router does not read secure storage on every navigation.
  - The sync trigger dispatcher must not keep a captured `Ref` after its scope is recreated.
  - Rename `DayCloseButtonState` to `DayClosureState`, because the DayCloseButton widget was deleted.
  - Document that a null `employeeId` in the today-summary computation means "all sales of the store", at repository/domain level.
- **Backend (done, for reference):** tests run on a shared PostgreSQL 16 Testcontainer (H2 removed) and use `@MockitoBean`. RBAC and actor lookups go through the shared `CurrentActor` helper. The full backend suite is green.

## Cross-Story Dependencies

- 15.10 refactors code that 15.8 just covered with tests: the checkout/draft flow, the today summary, the router and the auth providers. Re-run 15.8's tests after the renames and enum changes; they are the safety net.
- 15.10 touches code from 12.7 (router/session, debug HTTP client), 13.4 (SyncService/Drift contract), 13.6 (status strings), 16.10 (DayCloseButton removal) and HF-1/HF-2 (registration role, today-summary semantics).
- The DayCloseButton doc cleanup covers the UX design specification, the epic 4 POS doc, the 4-4 day-closure story, the requirements inventory and other planning/implementation docs that still name the widget (about 11 docs).
- 15.6 (pagination envelope, refonte) builds on the `paginated()` validation from 15.9. 15.2 (E2E harness, refonte) is not a prerequisite for any V1 story.
