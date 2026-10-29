---
title: '13.3 — DependencyResolver: activation plan (requires cascade) and deactivation check'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: 'd4e9d5b42c889689bdd1697bdd99acc49aeaaf35'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-2-module-registry.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Activating or deactivating a module for a tenant (13.8) must never leave an inconsistent state: a module cannot run without its hard dependencies (`requires`), and a module required by an active module cannot be switched off (MOD-FR4/5/6, spec-socle §5.2).

**Approach:** A pure `DependencyResolver` in the core, computing from the validated manifest set (`ModuleCatalog`, 13.2) and the tenant's effective active set (`ModuleRegistry.activeModules`, 13.2) an ordered activation plan (topological, Kahn) and a deactivation check. Read-only — it decides, 13.8 executes.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **API** (published, `com.keevo.shared.module`):
  - `ActivationPlan planActivation(String tenantSchema, String moduleId)` → ordered list of modules to activate (dependencies first, target last), excluding those already active; `alreadyActive` list; `blocked` flag with reasons.
  - `DeactivationCheck checkDeactivation(String tenantSchema, String moduleId)` → `allowed` or `blocked` with the sorted list of **active** dependents (direct and transitive, through `requires` only).
  - Plain records, French-free (no UI text here), immutable.
- **Rules:**
  - Closure over `requires` only; `enhances`/`needs` never add constraints.
  - Order: Kahn topological sort restricted to the needed set, ties broken by module id (deterministic).
  - Dependents are computed from `ModuleRegistry.activeModules(tenantSchema)` — a free module without a `tenant_modules` row is active (13.2 rule) — never from raw rows (routed from 13.2).
  - `core` is never in a plan (always active) and can never be deactivated (`blocked`, reason `CORE_MODULE`).
  - Unknown module id → `IllegalArgumentException` (programming error at the API level; 13.8 maps it to 404). Platform-admin schema → `IllegalArgumentException` (no tenant modules).
  - Already active target → empty plan, `alreadyActive` = [target].
- **Entitlement step** (AC: "après vérification d'entitlement de chacun"): a core port `ModuleActivationEligibility` (`Eligibility check(String tenantSchema, ModuleManifest module)`); the plan marks each step eligible/ineligible and is `blocked` if any step is ineligible (reason `NOT_ENTITLED` + module id). Default implementation until 13.5: a module with `free: true` is eligible, a paid module is not (no payment flow exists yet). 13.5 replaces the bean.
- **Cycles:** already rejected at boot by the 13.1 loader (Tarjan); the resolver re-checks defensively when it builds its graph (cycle → `IllegalStateException`) — no second implementation of cycle reporting, reuse `ModuleManifestLoader.findRequiresCycles`.

## Boundaries & Constraints

**Always:** no DB access of its own (registry only); deterministic output; all test packages green; architecture suite green.

**Never:** no writes, no activation endpoint (13.8), no provisioning (13.4), no real entitlement logic (13.5), no Flutter.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cascade | X requires [catalog, inventory], none active; inventory requires catalog | plan [catalog, inventory, X] | — |
| Partly active | catalog active | plan [inventory, X], alreadyActive [catalog] | — |
| Multi-level | A→B→C, none active | [C, B, A] | — |
| Target active | X active | empty plan, alreadyActive [X] | — |
| Paid dependency | X requires paid P | blocked NOT_ENTITLED P | — |
| Deactivate required | A active requires B | blocked, dependents [A] | — |
| Deactivate transitive | A→B→C all active, deactivate C | blocked, dependents [A, B] | — |
| Deactivate free | no active dependents | allowed | — |
| Deactivate core | core | blocked CORE_MODULE | — |
| Enhances only | A enhances B | no constraint either way | — |
| Unknown module | "ghost" | — | IllegalArgumentException |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` — `ModuleRegistry`, `application/ModuleCatalog`, `ModuleManifest`, `ModuleManifestLoader.findRequiresCycles`.
- Tests: use `src/test/java/com/keevo/shared/module/TestModuleCatalogs.java` (13.2) and extend it for multi-level graphs.

## Tasks & Acceptance

**Execution:**
- [x] `DependencyResolver` port + records + service; `ModuleActivationEligibility` port + default free-only bean.
- [x] Unit tests for every matrix row (fake registry / test catalogue); one full-context test that the beans wire and that `planActivation` on the shipped catalogue for a tenant with `pos` DISABLED returns `[pos]` (its requires are active).
- [x] Tick Story 13.3 routed item with evidence.

**Acceptance Criteria:**
- Given the matrix scenarios, when the resolver is called, then it returns exactly the listed plans/checks.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** Published in `shared.module`: `DependencyResolver` (`planActivation`, `checkDeactivation`), records `ActivationPlan` (`steps` of `Step(moduleId, eligible)`, `alreadyActive`, `reasons`, derived `blocked()`/`moduleIds()`), `DeactivationCheck` (`dependents`, `reasons`, derived `allowed()`/`blocked()`), `BlockReason(Code, moduleId)` with codes `NOT_ENTITLED`/`CORE_MODULE`/`REQUIRED_BY_ACTIVE_MODULE`, port `ModuleActivationEligibility` (+ nested `Eligibility` record). `application.DependencyResolverService` (graph + reverse graph built once from `ModuleCatalog`, defensive `findRequiresCycles` → `IllegalStateException`; closure over `requires` without core; Kahn with a `PriorityQueue` for id tie-break; dependents = transitive reverse closure filtered by `ModuleRegistry.activeModules`) and `application.FreeModulesOnlyEligibility` (free → eligible). Platform schema / invalid schema / unknown id → `IllegalArgumentException`.

**Progress — task 2 done (2026-10-29).** `TestModuleCatalogs` gained a `loaded(id, category, free, requires, enhances)` overload and `dependencyCatalog()` (x→{catalog,inventory}, inventory→catalog, alpha→beta→gamma, buyer→paid (paid), helper enhances beta, solo). `application.DependencyResolverServiceTest` 20 tests (every matrix row + middle-active multi-level, paid dependency already active, core plan, inactive dependents don't block, enhances both ways, determinism, invalid/platform schemas with 0 registry reads, defensive cycle → ISE, default eligibility). `DependencyResolverBootIntegrationTest` (full context): beans wired, default eligibility bean, tenant with `pos` DISABLED → plan `[pos]`, alreadyActive `[catalog, inventory]`. `com.keevo.shared.module.**` green (all classes 0 failures). `com.keevo.architecture.**` 28 tests green.

**Progress — task 3 done (2026-10-29).** Epic Story 13.3 routed item (dependents from `activeModules`, from review 13-2) ticked with evidence. 

**Verification done (2026-10-29).** 14-package sequential loop all green (rc=0, 0 failures): admin 504, architecture 28, catalog 247, commerce 251, crm 114, feedback 52, identity 437, inventory 376, messaging 163, reporting 389, shared 741, store 60, subscription 70, sync 525.

**Review fixes (2026-10-29, orchestrator decisions):**
- *Item 1 done — active target.* Early return removed: an active target with a fully active closure → empty plan; with inactive hard dependencies → repair plan of the missing ones in topological order, target not re-planned. `alreadyActive` = active members of {target} ∪ closure on every path, `core` always excluded (planning `core` → empty plan, empty `alreadyActive`; documented on `ActivationPlan`). Tests `targetActive`, `targetWithoutRequiresActive` (matrix row), `targetActiveWithInactiveDependencies`, `coreNeverPlanned`.
- *Item 2 done — module id guard.* Null/blank id → `IllegalArgumentException("A module id is required")` before any registry read; unknown id → `"Unknown module (length=N)"`, raw id never echoed (12.12 D5). Tests `unknownModule`, `missingModuleId`.
- *Item 3 done — constructor validation.* Every `requires` entry must be a catalogue id and appear once per module → `IllegalStateException` naming module + dependency (before the cycle check). Test `invalidRequiresEntries`.
- *Item 4 done — tie-break/diamond.* `dependencyCatalog()` gained `top` → `[zeta, eta]` → `base`; `tieBreakAndDiamond` asserts `[base, eta, zeta, top]` (and `[base, zeta, top]` with `eta` active); vacuous `deterministic()` removed.
- *Item 5 done — derived reasons.* `DeactivationCheck(moduleId, dependents, coreModule)`: dependents sorted + deduplicated, `reasons()`/`allowed()` derived (CORE_MODULE, or one REQUIRED_BY_ACTIVE_MODULE per dependent); core with dependents rejected. Test `deactivationReasonsDerived`.
- *Item 6 done — boot IT.* Random `kv_` + 6 [a-z0-9] schema and matching code per run; cleanup also calls `ModuleRegistry.evict(schema)`.
- Verification: `com.keevo.shared.module.**` 101 tests (resolver unit 25, boot IT 1), `com.keevo.architecture.**` 28 — all green.

**Final verification (orchestrator, 2026-10-29).** Before review patches: 14 packages sequential green (shared 741, …). After patches (changes confined to `shared.module`, no other consumer): `com.keevo.shared.module.**` 101 green, `com.keevo.architecture.**` 28 green. Pure service — no live device scenario (no endpoint until 13.8); exercised end-to-end by the full-context boot IT on Postgres.

Matrix note: "Target active" now returns `alreadyActive` = active members of target + closure (core excluded), e.g. `[catalog, inventory, x]`.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-3)
- verdicts: 3 layers (Blind Hunter 10, Edge+Gap 10, Intent 11; overlap) — high 0, medium 3, low 8 after dedup
- findings:
  - `[medium]` `[patch]` active target with inactive hard dependencies → empty plan hides the inconsistency — orchestrator decision: plan the missing dependencies (repair); `alreadyActive` consistent on every path (core excluded)
  - `[medium]` `[defer]` §10 order (row written before the plan) would make the cascade a silent no-op; unknown tenant; concurrency; display names → routed → 13-8
  - `[medium]` `[defer]` second eligibility bean breaks the context; richer `Eligibility`; null/throwing port → routed → 13-5
  - `[low]` `[patch]` null/blank id relied on catalogue internals; raw id echoed in messages — explicit guard, length only
  - `[low]` `[patch]` hand-built catalogue with unknown/duplicate `requires` → bare exceptions / false cycle — constructor validation
  - `[low]` `[patch]` tie-break never exercised (`deterministic()` vacuous) — diamond test `[base, eta, zeta, top]`
  - `[low]` `[patch]` `DeactivationCheck` reasons could disagree with dependents — derived inside the record
  - `[low]` `[patch]` boot IT fixed schema/code + no cache eviction — random schema, evict
  - `[low]` `[reject]` walking through inactive intermediates — conservative, correct in consistent states
  - `[low]` `[reject]` duplicate `requires` false cycle — loader rejects duplicates; constructor check covers hand-built catalogues
  - `[low]` `[defer]` multi-module bundle planning → routed → 16-2

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.module.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... -Dtest='com.keevo.architecture.**'` -- expected: green
- 14-package sequential loop -- expected: green
