---
title: '12.6b — Blocking architecture suite: Modulith verify() + Keevo ArchUnit rules'
type: 'chore'
created: '2026-10-29'
status: 'done'
baseline_revision: 'de5cd17515cf3c225ec2a3aafbcb75088a23c07a'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-6a-ports-capacite-core-cycles.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** After 12.6a the Modulith report is clean (0 violations), but the architecture tests are still a warning-mode baseline with text-parsing guards; the Keevo-specific invariants Modulith does not cover (spec-socle §13 rules 4–7) are not enforced, and the 10-6 / 12.x items routed to 12.6 are open. Any regression would only show in a report file.

**Approach:** Turn the architecture suite blocking: `ApplicationModules.verify()` fails the build; add ArchUnit rules for the Keevo invariants; close the routed items; keep the generated docs.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- `verify()` blocking replaces the text-classifier assertions (classifier test and report writer kept only for diagnostics: report still written on failure).
- ArchUnit rules (all blocking, `DO_NOT_INCLUDE_TESTS`):
  (a) any SQL string passed to `JdbcTemplate`/`NamedParameterJdbcTemplate` that concatenates a schema must go through `TenantSchema.validate`/`TenantSchema.isValid` (rule: classes calling `JdbcTemplate` with string concatenation of a schema variable must also call `TenantSchema` — implement as a reasonable static rule + allow-list file for justified exceptions);
  (b) `@Entity` and Spring Data `Repository` types only under `..adapter.out.persistence..`;
  (c) no `org.springframework.web..`, `jakarta.servlet..`, `org.springframework.http..` types in `..domain.port.in..` or `..application.service..` / `..application.usecase..`;
  (d) `requires`-graph acyclicity test created and `@Disabled("enabled with Epic 13 manifests")`;
  (e) publishers of events consumed only by `@ApplicationModuleListener`s publish inside a transaction — rule: methods publishing those catalogue event types are in classes/methods annotated `@Transactional` (or use a `TransactionTemplate`), with an allow-list for justified exceptions.
- Existing violations of (a)–(c)/(e): fix if mechanical; otherwise list them in a versioned allow-list (`src/test/resources/architecture/*-allowlist.txt`) with one reason per line — the suite fails on any new entry (ratchet).
- Routed 10-6 items: Documenter wrapped so an invalid model reports instead of crashing; `if-no-files-found: warn` on both CI uploads; `spring-modulith-api` and `archunit-junit5` versions from BOMs (no pinned 1.3.0), scopes checked.
- The identity ↔ messaging cycle and the four pre-commit listeners routed to 12.6 were resolved by 12.6a — mark them done with that evidence.

## Boundaries & Constraints

**Always:** no production behaviour change except mechanical fixes needed to satisfy the rules; all test packages green; the architecture package runs in the normal test phase (CI included).

**Never:** no new modules, no manifests (Epic 13), no CI secret/key changes (backend CI keys issue stays with v1s-12-13 per user decision).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean tree | current code | architecture suite green | — |
| New cross-module internal import | e.g. commerce imports inventory internals | build fails with Modulith message | report file still written |
| New cycle | module A ↔ B | build fails | — |
| `@Entity` outside persistence adapter | new entity in domain | build fails | — |
| Web type in a use case | `HttpServletRequest` param | build fails | — |
| Unvalidated schema concatenation | `"SELECT … FROM \"" + schema` without `TenantSchema` | build fails unless allow-listed | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` (+ `ModulithViolationClassifierTest`, `KeevoArchUnitBaselineTest`, `OwnerRecipientResolutionArchTest`).
- `keevo/backend/target/archunit-violations.txt` — current ArchUnit baseline (after 12.2f only `AuditLogJpaEntity` was flagged by the entity rule; verify).
- `shared/infrastructure/persistence/TenantSchema.java` — validation helper for rule (a).
- `.github/workflows/backend-ci.yml:59-70` — upload steps.
- `keevo/backend/pom.xml:207-230` — modulith/archunit dependencies.
- Planning: `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` Story 12.6 items (tick them with evidence).

## Tasks & Acceptance

**Execution:**
- [x] `verify()` blocking; diagnostics report kept; Documenter guarded.
- [x] ArchUnit rules (a)–(e) + allow-list ratchet files; fix mechanical violations.
- [x] CI upload `if-no-files-found: warn`; BOM-managed versions.
- [x] Tick the Story 12.6 items in the epic with evidence; record rule counts / allow-list sizes in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the current tree, when `com.keevo.architecture.**` runs, then it passes; given a deliberately introduced violation of each rule (local experiment, not committed), then it fails — record the six experiments in Implementation Notes.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** `ApplicationModulesBaselineTest.verifyModuleBoundaries` (was `_warningModeOnly`): `detectViolations()` → report `target/modulith-violations.txt` written first → `violations.throwIfPresent()` (= `ApplicationModules.verify()`, without a second detection) — BLOCKING; the text-classifier assertions are gone (classifier kept for the report + `ModulithViolationClassifierTest`). `detectModulesAndGenerateDocumentation` wrapped in try/catch (`RuntimeException | LinkageError`) → `target/spring-modulith-docs/GENERATION-FAILED.txt` + stderr, no crash.

**Progress — task 2 done (2026-10-29).** `KeevoArchUnitBaselineTest` → `KeevoArchitectureRulesTest` (git mv) + `KeevoArchitectureRules` (rule logic) + `KeevoArchitectureRulesSelfCheckTest` (fixtures in `architecture.fixture.application.service`) + `RequiresGraphAcyclicityTest` (d, manifest test `@Disabled("enabled with Epic 13 manifests")`, cycle-finder self-check enabled). Main classes only (`DO_NOT_INCLUDE_TESTS`). Ratchet: `src/test/resources/architecture/<rule>-allowlist.txt`, `<key>   # <reason>` (reason mandatory), new violation or stale entry fails; report `target/archunit-violations.txt` written in `@AfterAll` (also on failure). Mechanical fix for (b): `AuditLogJpaEntity`, `AuditLogSpringRepository`, `AuditLogRepositoryAdapter` (+ its test) moved to `shared.audit.adapter.out.persistence` (package move only; entity name/table unchanged). Allow-lists: (a) 2, (b) 0, (c) 0, (e) 1.

**Progress — task 3 done (2026-10-29).** CI: `if-no-files-found: warn` on both uploads. pom: pinned `archunit-junit5` 1.3.0 removed (no test uses the JUnit engine) — `archunit` now comes transitively from `spring-modulith-core` (1.4.2 fixed by its own pom, not managed by the Modulith BOM; the pin overrode it); `spring-modulith-api` stays compile scope, version from the BOM (starter-jdbc already brings it at compile scope). *(Wording corrected in the review fixes below.)*

**Experiments (2026-10-29, local only, removed after).** Six throw-away classes added to `src/main` at once, then `mvn test -Dtest='com.keevo.architecture.**'`: 24 tests, 4 failures + 1 error, each expected one fired:
1. `commerce.zzexp.Exp1InternalImport` field of `inventory.stock.domain.entity.MovementType` → `verifyModuleBoundaries` error (`org.springframework.modulith.core.Violations`: "Module 'commerce' depends on non-exposed type … within module 'inventory'"); report written (Total violations: 3).
2. `store.zzexp.Exp2Cycle` field of `identity.api.PlanType` → same error: "Cycle detected: Slice identity -> Slice store …" + not-allowed `store -> identity :: api`.
3. `store.zzexp.domain.model.Exp3Entity` `@Entity` → `persistenceTypes_onlyInPersistenceAdapters` fails.
4. `catalog.zzexp.application.usecase.Exp4WebUseCase` with an `HttpServletRequest` param → `applicationLayer_hasNoWebTypes` fails.
5. `catalog.zzexp.adapter.out.jdbc.Exp5SchemaSql` `"SELECT … FROM \"" + TenantContext.getCurrentTenant() …` without `TenantSchema` → `tenantSchemaJdbc_validatedThroughTenantSchema` fails.
6. `catalog.zzexp.Exp6NonTxPublisher` publishing a `DayClosedEvent` from a non-transactional method → `moduleListenedEvents_publishedInsideATransaction` fails.
After removal: 24 tests, 0 failures (1 skipped = rule d), `✓ No module violations detected.` The same shapes are kept as committed fixtures for (a)/(c)/(e) in `KeevoArchitectureRulesSelfCheckTest` (no `@Entity`/Spring Data fixture: it would be picked up by JPA scanning in `@SpringBootTest`/`@DataJpaTest`).

**Progress — task 4 done (2026-10-29).** Epic Story 12.6: all 9 routed items ticked with evidence (10-6 ×6, identity ↔ messaging cycle + four listeners → resolved by 12.6a, transactional-publisher rule → 12.6b). `spring-modulith-api` `provided` explicitly rejected with reason.
**Rule counts / allow-list sizes:** 4 blocking ArchUnit rules + 1 disabled (d), plus blocking `verify()`. Current violations = allow-list sizes: (a) `tenant-schema-jdbc` 2 (`AdminTenantSuspensionService`, `TenantDeletionService` — schema only bound/forwarded, never concatenated), (b) `persistence-location` 0 (after the audit move), (c) `web-free-application` 0, (e) `transactional-publishers` 1 (`SaleSyncHandler#apply`, runs in SyncPushService's TransactionTemplate via the handler registry). Modulith: `✓ No module violations detected.`
**Known limits (static approximations):** (a) is class-level (reads a schema + runs JDBC ⇒ must call TenantSchema), the line-level check stays `TenantSchemaInterpolationGuardTest`; (e) sees the published type only when the publishing method constructs it (or calls a method returning it), lambda bodies count for the enclosing method (so a TransactionTemplate call anywhere in the method satisfies it), and callers are followed in the static call graph only (polymorphic dispatch → allow-list). (e) considers events with at least one `@ApplicationModuleListener` consumer (superset of "only").

**Verification (2026-10-29).** `mvn test -Dtest='com.keevo.architecture.**'`: 24 tests, 0 failures, 1 skipped (d). 14 packages sequential, all rc=0, 0 failures/errors (surefire XML, counts include a few stale XML of renamed classes): architecture 26, catalog 247, crm 114, inventory 376, commerce 251, sync 496, identity 437, messaging 163, reporting 389, shared 650, admin 504, store 60, subscription 70, feedback 52.

**Review fixes (2026-10-29), done.**
- Rule (e): caller walk = backward reachability with per-method memo (a revisit is neutral → no diamond/recursion false positives); transactional = annotated or meta-annotated `@Transactional` (Spring/jakarta) on the method, its class, a superclass, an interface or the same method on an interface (so `@ApplicationModuleListener` counts), or a `TransactionOperations` call; key = `Cls#name(RawParamSimpleNames)`, synthetic/bridge skipped; event set + `@TransactionalEventListener` AFTER_COMMIT/AFTER_COMPLETION/AFTER_ROLLBACK without `fallbackExecution`; blind spots in the Javadoc. Fixtures: diamond, recursive helper, publishing module listener (pass), overload (only `publish(int)` flagged), AFTER_COMMIT listener event.
- Rule (a): Javadoc + allow-list header — AC (a) = this class-level rule + `TenantSchemaInterpolationGuardTest` (line level); an entry exempts the whole class from (a) only.
- Rule (b): + `@Embeddable`, `@MappedSuperclass`, `@org.springframework.stereotype.Repository`; self-check on fixtures in `src/test/java/archfixture/` (outside `com.keevo`, never scanned by Boot/JPA). Surfaced `shared.infrastructure.persistence.JpaBaseEntity` (`@MappedSuperclass` extended by identity/store/subscription/feedback entities) → allow-listed with reason (core-wide base, not one module's adapter).
- Rule (c): widened to `..domain..` + `..application..` — 0 violations.
- Ratchet: `ArchitectureAllowlist` (`parse(Reader)` — BOM stripped, `strip()`, duplicate key and missing reason fail; `unexpected`, `stale`) + `ArchitectureAllowlistTest` (5); unexpected and stale reported in one failure.
- Reports: ArchUnit report per rule = status (NOT RUN / THREW / PASSED / FAILED), unexpected, stale, allow-listed (with reasons); `target/archunit-violations.txt` and `target/modulith-violations.txt` deleted at start; Documenter failure → `GENERATION-FAILED.txt` + `Assumptions.abort` (skipped, not passed). Dead classifier helpers (`isInternalAccessToPublishedModule`, `isInternalAccessFromShared`, `isTwoModuleCycle`, `PUBLISHED_API_MODULES`) and their tests removed.
- Docs: `AuditPort` Javadoc path → `shared/audit/adapter/out/persistence`; pom comments reworded. `mvn dependency:tree -Dincludes=com.tngtech.archunit` → `spring-modulith-core:1.4.14:runtime` → `com.tngtech.archunit:archunit:jar:1.4.2:runtime` (transitive from modulith-core's own pom, not BOM-managed).
- Allow-list sizes: (a) 2, (b) 1, (c) 0, (e) 1. `com.keevo.architecture.**`: 27 tests, 0 failures, 1 skipped (d); Modulith `✓ No module violations detected.`
- Rejected / routed by the orchestrator: (a) parameter-schema detection (guard test covers), CI `if-no-files-found` kept as explicit intent, excluding modulith-core/archunit from the prod jar → 13.2, manifest loading robustness of `RequiresGraphAcyclicityTest` → 13.1.

**Final verification (orchestrator, 2026-10-29, after review patches).** 14 packages sequential: 3,830 tests, 0 failures/errors; Modulith `✓ No module violations detected.`; boot smoke (dev profile, new dependency set — ArchUnit 1.4.2 runtime): started in 54.6 s, `GET /api/v1/products` 200, `GET /api/v1/products/drafts/count` 200, 0 ERROR in log.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (12-6b)
- verdicts: 4 layers (Blind Hunter 15, Edge Case 25, Verification Gap 9, Intent 7; heavy overlap) — high 0, medium 7, low 12 after dedup, false 1
- findings:
  - `[medium]` `[patch]` rule (e) shared `visiting` set → order-dependent false positives on diamonds/recursion — memoised backward search
  - `[medium]` `[patch]` rule (e) `@Transactional` only direct → `@ApplicationModuleListener`/composed annotations missed — meta-annotation + interfaces
  - `[medium]` `[patch]` rule (e) key `Class#method` collides on overloads/bridges — key with raw param types, synthetic skipped
  - `[medium]` `[patch]` rule (e) events consumed only by plain AFTER_COMMIT `@TransactionalEventListener` not covered — added
  - `[medium]` `[patch]` rule (c) narrower than the deleted rule (all `..domain..`) — widened to `..domain..` + `..application..` (0 violations)
  - `[medium]` `[patch]` rule (b) no self-check, `@Repository`/`@Embeddable`/`@MappedSuperclass` missed — fixtures outside `com.keevo`; `JpaBaseEntity` (`@MappedSuperclass`, shared base of 12 entities) allow-listed, accepted by orchestrator
  - `[medium]` `[patch]` ratchet untested (stale, reason, duplicates, BOM) — `ArchitectureAllowlist` + 5 unit tests; unexpected + stale in one failure
  - `[low]` `[patch]` reports: stale/clean-looking report after a crash — files deleted at start, per-rule status; Documenter failure → `Assumptions.abort` (skipped, not passed)
  - `[low]` `[patch]` dead classifier helpers removed; `AuditPort` Javadoc path; pom comment (ArchUnit transitive from modulith-core 1.4.2 runtime, `dependency:tree` recorded)
  - `[low]` `[patch]` rule (a) is class-level — Javadoc + epic tick: AC (a) = ArchUnit (a) + `TenantSchemaInterpolationGuardTest`
  - `[low]` `[reject]` rule (a) schema as method parameter / native SQL via EntityManager — covered line-level by `TenantSchemaInterpolationGuardTest`
  - `[low]` `[reject]` rule (e) TransactionTemplate anywhere in method / helper `publish(Object)` / self-invocation / propagation — static-analysis limits, documented in Javadoc; all current publishers checked by hand (intent layer)
  - `[low]` `[reject]` CI `if-no-files-found: warn` is the v4 default — kept as explicit intent
  - `[low]` `[reject]` no negative fixture for Modulith `verify()` — library behaviour, wiring is one line; proven by local experiments 1–2
  - `[low]` `[defer]` manifest loading of `RequiresGraphAcyclicityTest` → routed → 13-1
  - `[low]` `[defer]` modulith-core + ArchUnit shipped unused in the prod jar (pre-existing) → routed → 13-2
  - `[false]` `[reject]` audit package move breaks JPA scanning — no `@EntityScan`/`@EnableJpaRepositories`, default root scan; covered by Postgres `@DataJpaTest`

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- Same 14-package sequential loop as spec-12-2a -- expected: green
