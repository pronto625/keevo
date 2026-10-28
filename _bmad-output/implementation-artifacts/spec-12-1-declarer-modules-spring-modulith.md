---
title: '12.1 — Spring Modulith module declarations reflect the real dependency graph'
type: 'refactor'
created: '2026-10-28'
status: 'done'
baseline_revision: '1e515e39e2e0d2fefeecaceb95ae0235a83e0c2f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 10-6 already declared the 12 top-level packages as `@ApplicationModule`s, but their `allowedDependencies` are guesses: none lists the OPEN `shared` module (~560 false "not an allowed target" violations), `identity`/`messaging` declare nothing, and the baseline report does not state a total. The boundary map is therefore not a trustworthy artifact to start decoupling from (MOD-NFR3).

**Approach:** Make each declaration describe today's real module graph (plus `shared`), assert in a test that all 12 modules are detected, generate the module docs, and turn the warning-mode report into a counted baseline grouped by violation kind and module — still non-blocking.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Modules are the 12 real packages: `admin, catalog, commerce, feedback, identity, inventory, messaging, reporting, shared, store, subscription, sync`. `shared` keeps `Type.OPEN` (it is the epic's `core`).
- Docs stay in Documenter's default `target/spring-modulith-docs/` (what CI already uploads); the epic's `target/modulith-docs` wording is superseded.
- `allowedDependencies` = observed outgoing module dependencies + `shared`. Cycles stay visible as cycle violations; removing them is 12.2a–12.5 work.
- `shared`'s reverse dependencies (JPA entities for Product/Stock/Supplier/Client in `shared.infrastructure.persistence.entity`, identity repositories) are accepted for now and listed in the report; relocation belongs to 12.5.

## Boundaries & Constraints

**Always:** build stays green; `verify()` stays warning-only until 12.2e; no production code other than `package-info.java` changes.

**Never:** no class moves, no `api`/`internal` packages (12.2x), no ratchet or blocking suite (12.6 items stay in 12.6), no change to `KeevoArchUnitBaselineTest` rules.

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/*/package-info.java` -- the 12 declarations to rewrite (current lists in `epic-12-context.md`).
- `keevo/backend/src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` -- `detectModulesAndGenerateDocumentation` (Documenter) and `verifyModuleBoundaries_warningModeOnly` (writes `target/modulith-violations.txt`, header says "counted in details below" — no count).
- `keevo/backend/target/modulith-violations.txt` (2026-10-19, 2,153 violations: 77 cycles, 1,196 non-exposed, ~880 not-allowed, ~560 of which X→`shared`) -- before/after comparison input; regenerate, never commit.
- `.github/workflows/backend-ci.yml` -- uploads `target/spring-modulith-docs/`; leave as is.

## Tasks & Acceptance

**Execution:**
- [x] Derive the real graph: temporary helper (or one-off test run) iterating `ApplicationModules.of(KeevoApplication.class)` → for each module, `getDependencies(modules)` target module names. Do not commit the helper.
- [x] `keevo/backend/src/main/java/com/keevo/*/package-info.java` -- set `allowedDependencies` to observed targets + `"shared"` for every non-shared module (identity and messaging included); sorted alphabetically; one-line comment "observed graph 2026-10-28 (12.1) — tightened in 12.2x".
- [x] `ApplicationModulesBaselineTest.java` -- new test asserting the detected module names equal the 12 above; report header gets the real total and per-kind counts (cycle / non-exposed / not-allowed), plus a per-source-module count of not-allowed violations; Documenter call unchanged.
- [x] Record before/after counts in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the backend, when `ApplicationModulesBaselineTest` runs, then it passes, `target/spring-modulith-docs/components.puml` exists, and the 12 modules are asserted by name.
- Given the regenerated `target/modulith-violations.txt`, when read, then its header states the total and per-kind counts and it contains zero "not an allowed target" violations towards `shared`.
- Given package `com.keevo.architecture`, when its tests run, then all pass.

## Implementation Notes

**Observed graph (2026-10-28, `getDependencies(modules).uniqueModules()`, helper not committed):**
- admin → feedback, identity, messaging, shared, subscription
- catalog → identity, messaging, shared, store, subscription
- commerce → catalog, identity, messaging, shared, store
- feedback → shared
- identity → catalog, messaging, reporting, shared, store, subscription
- inventory → catalog, shared, store, subscription
- messaging → catalog, commerce, identity, shared, store
- reporting → commerce, identity, messaging, shared, store, subscription
- store → shared, subscription
- subscription → identity, messaging, shared
- sync → catalog, commerce, feedback, identity, inventory, shared, store, subscription
- shared (OPEN, unchanged) → admin, catalog, commerce, identity, inventory, messaging, reporting, store, subscription

Some previously declared targets were never used and were dropped (e.g. admin→catalog/commerce/inventory/store/sync/reporting, inventory→commerce, reporting→catalog/inventory).

**Before / after (`target/modulith-violations.txt`):**

| Kind | Before (2026-10-19) | After |
|---|---|---|
| Total | 2,165 | 1,213 |
| Cycle | 77 | 77 |
| Non-exposed type ("depends on non-exposed type") | 1,208 | 751 (all from `shared`) |
| Internal type of a *declared* module ("depends on module X … Allowed targets" with X listed) | 301 | 385 |
| Not-allowed (target module not declared) | 579 (562 of them X→`shared`) | 0 |

(The spec's 2,153 was an approximation; the old file holds 2,165 top-level entries.)

**Report classification:** Modulith 1.4 uses the "depends on module X via … Allowed targets: …" wording both for an undeclared target and for an internal (non-exposed) type of a declared target. The report therefore parses the `Allowed targets` list and counts a message as *not-allowed* only when X is absent from it; otherwise it goes under *non-exposed*. This is why identity's/messaging's former 457 "non-exposed type" messages now appear as "internal type of a declared module" — same violations, different wording once an allow-list exists. All 385 remaining such messages are 12.2x work (publish `api` packages).

**Test changes:** `detectsTheTwelveDeclaredModules` asserts the 12 names; `verifyModuleBoundaries_warningModeOnly` now calls `detectViolations()` (same checks as `verify()` with default options, no exception juggling) and writes header counts + per-source-module and per-edge breakdowns; the `shared → X` edges in the non-exposed breakdown are shared's reverse dependencies (12.5). Documenter call unchanged.

## Spec Change Log

## Review Triage Log

Reviewed together with 12-5 (single batched diff) — full triage in `spec-12-5-realigner-modules-inventory-crm.md` § Review Triage Log (2026-10-29).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `head -20 keevo/backend/target/modulith-violations.txt` -- expected: total + per-kind counts, lower than 2,153
