---
title: '13.7 — ExtensionRegistry: extension points declared by modules, contributions filtered per tenant'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '450b8bdcd614c1f9353f2035568a72494ada7e16'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-6-capability-registry.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A module must be able to enrich another module's workflow/UI when it is active — e.g. Invoicing adding « Facturer » to the POS checkout (MOD-FR10, spec-socle §8, consumed by 14.5 and the Flutter `ExtensionSlot` in 15.3). There is no way to declare such a slot or contribute to it.

**Approach:** Same shape as 13.6: extension points are declared in the declaring module's manifest (`extensionPoints.declares`); contributions are beans annotated `@ContributesExtension` in the contributing module, cross-checked with its manifest (`extensionPoints.fills`) at boot; a core `ExtensionRegistry.contributions(pointId, tenantSchema)` returns only the contributions usable for that tenant. POS declares `pos.checkout.post-sale-actions` now.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Contract** (published, `com.keevo.shared.module`): interface `Extension` (`String id()` — unique per point, `String label()` — French, user-facing, no technical data, `int order()`, `String action()` — an action key the client maps, e.g. `invoicing.create-from-sale`); annotation `@ContributesExtension(module = "<id>", point = "<pointId>")` on singleton beans implementing `Extension`; `ExtensionRegistry`: `List<Extension> contributions(String pointId, String tenantSchema)`, `Set<String> declaredPoints()`. Programmatic `declare/contribute` of spec §8 is not added (no `KeevoModule` before 13.9) — manifest + annotation are the registration path.
- **Point id format**: `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$` and must start with the declaring module id + `.` (e.g. `pos.`).
- **Resolution rule**: returns the contributions whose contributor module is ENABLED for the tenant (contributions are actions → READ_ONLY excluded), and only if the declaring module is not DISABLED (no slot for an inactive module); sorted by `order`, then module id, then extension id; immutable list; unknown point id → `IllegalArgumentException` (no raw value echoed); platform-admin schema / unknown tenant → empty.
- **Boot validation** (fail-fast, all problems in one message naming class/module/point): point declared by two manifests; `declares` entry not matching the format/prefix; `fills` entry naming a point nobody declares; a contribution bean whose module is not installed, whose point is not in its manifest `fills`, or that is not a singleton; a `fills` entry with no contribution bean; duplicate extension id within a point; blank label. Contribution beans are non-autowire-candidates (same mechanism as 13.6) and the ArchUnit rule forbids injecting `Extension` beans outside `shared.module`.
- **Manifests**: `pos` declares `["pos.checkout.post-sale-actions"]` (pos version 1.1.0). No contribution exists yet (Invoicing is 14.x) — contributions are proven with a test catalogue and test beans.
- **Not here**: HTTP endpoint for the app (→ 15.3), Flutter `ExtensionSlot` (→ 15.3), Invoicing contribution (→ 14.5).

## Boundaries & Constraints

**Always:** no behaviour change for existing flows; reuse the 13.6 infrastructure (bean discovery by name, singleton rule, autowire exclusion, lazy init) instead of a copy; all test packages green; architecture suite green.

**Never:** no Flutter change, no endpoint, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Active contributor | declarer ENABLED, contributor ENABLED | its extension listed | — |
| Contributor read-only | contributor READ_ONLY | not listed | — |
| Contributor disabled | DISABLED row | not listed | — |
| Declarer disabled | declaring module DISABLED | empty | — |
| Two contributors | orders 20 and 10 | sorted 10, 20 | — |
| Unknown point | `foo.bar` | — | IllegalArgumentException |
| Platform / unknown tenant | `public` / no row | empty | — |
| Fill without bean | manifest `fills` X, no bean | — | boot fails |
| Bean without fill | bean on X, manifest lacks X | — | boot fails |
| Point declared twice | two manifests | — | boot fails |
| Bad point id | `checkout` / `crm.x` declared by pos | — | boot fails |
| Shipped POS slot | real catalogue | `declaredPoints` contains `pos.checkout.post-sale-actions`, contributions empty | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` — 13.6 `CapabilityRegistryService`, `CapabilityProviderAutowireExclusion`, `ProvidesCapability`, manifest model `extensionPoints`, `ModuleManifestLoader`.
- `src/test/java/com/keevo/architecture/KeevoArchitectureRules*` (13.6 rule `capability-injection`).
- `keevo/backend/src/main/resources/keevo/modules/pos/module.json`.

## Tasks & Acceptance

**Execution:**
- [x] Shared bean-discovery helper factored out of 13.6 (no behaviour change for capabilities; their tests stay green).
- [x] `Extension`, `@ContributesExtension`, `ExtensionRegistry` + service + boot validation + autowire exclusion + ArchUnit rule extension; unit tests for every matrix row.
- [x] Manifest loader: point format/prefix, unique declarations, `fills` must target a declared point; tests.
- [x] POS manifest declares the slot (1.1.0); full-context test on the shipped catalogue.

**Acceptance Criteria:**
- Given a module declaring `pos.checkout.post-sale-actions`, when an active module contributes to it, then `contributions(slotId, tenant)` returns only the contributions of modules active for that tenant.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** Bean discovery of 13.6 factored into package-private `shared.module.application.ModuleBeans` (`collect(factory, annotation, role)` by bean name, `scopedTarget.*` skipped, non-singleton → `Found.problem`; `describe(found)`); `CapabilityRegistryService` now uses it (its private `Candidate`/`collect`/`providerName` body removed). `CapabilityProviderAutowireExclusion` renamed `infrastructure.ModuleBeanAutowireExclusion`, covering `@ProvidesCapability` and `@ContributesExtension`. `CapabilityRegistryServiceTest` (21) + `CapabilityRegistryContextTest` (7) green, unchanged except the renamed class.

**Progress — task 2 done (2026-10-29).** Published in `shared.module`: `Extension` (`id/label/order/action`, values read once at boot), `@ContributesExtension(module, point)` (TYPE + METHOD), `ExtensionRegistry.contributions/declaredPoints`. `shared.module.application.ExtensionRegistryService` (`SmartInitializingSingleton` + lazy synchronized init on first call, same as 13.6; `initialize(Collection)` for tests): declared points from the catalogue (sorted, immutable); unknown/null point → IAE "Unknown extension point" (value not echoed), checked first; `public` → empty (no registry call); schema validated (13.2); declarer state not readable → empty; contributions kept iff contributor `writable()` (ENABLED); index pre-sorted order → module → id, `List.copyOf`. Boot validation, one ISE: non-singleton (via `ModuleBeans`), not annotated, not implementing `Extension`, module not installed, point not in its manifest `fills`, blank id/label/action, duplicate id in a point (both beans named), `fills` entry with no bean (not re-reported for a bean already in error). Tests: `ExtensionRegistryServiceTest` (22, every registry-side matrix row + CGLIB proxy + same id on two points), `ExtensionRegistryContextTest` (4: class + `@Bean` discovery sorted, prototype → boot fails, direct injection → `NoSuchBeanDefinitionException` and `List<Extension>` injection gets an empty list (Spring 6.2), lazy init). ArchUnit rule (f) `capability-injection` extended to `Extension` and to parameterized types (`getAllInvolvedRawTypes`, so `List<Extension>` is caught); fixtures `ExtensionListConsumer` / `ExtensionConstructorConsumer` / compliant `ExtensionRegistryUser`; `com.keevo.architecture.**` green.

**Progress — task 3 done (2026-10-29).** `ModuleManifestLoader`: public `POINT_PATTERN` `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$`; per file, every `declares` entry matches it and starts with `<id>.`, every `fills` entry matches it; across files (`validateExtensionPoints`, after the capability checks, same all-or-nothing report): a point declared by two modules (both named + locations), a `fills` entry no installed module declares. Note: with the owner-prefix rule two *valid, distinct* modules can never declare the same point (only two manifests of one module id, already "duplicate module id"), so the cross-file duplicate check is defensive; `ExtensionRegistryService` also reports a point declared twice in the catalogue it is given (test `declaredTwice`). Tests: `ModuleManifestLoaderTest` +4 (`extensionPoints`, `declaredPointFormat`, `fillsUndeclared`, `pointDeclaredTwice`), `ExtensionRegistryServiceTest` +1; `ClasspathModuleManifestsTest` still green.

**Progress — task 4 done (2026-10-29).** `pos/module.json`: `extensionPoints.declares: ["pos.checkout.post-sale-actions"]`, version `1.1.0`. `ClasspathModuleManifestsTest` pins it (crm + pos 1.1.0, only pos declares, nobody fills); `ModuleCatalogSeederPostgresIntegrationTest` expects pos 1.1.0. New `shared.module.ExtensionRegistryFullContextTest` (production context, Testcontainers PostgreSQL): `declaredPoints() == [pos.checkout.post-sale-actions]`, a fresh tenant (pos ENABLED) gets an empty slot, `public` → empty, `foo.bar` → IAE. `com.keevo.shared.**` run: 882 tests, the only failure was `TenantSchemaInterpolationGuardTest` (textual heuristic flagged the `"\", point = \""` quoting of a boot message) — message reworded ("no @ContributesExtension bean of module 'x' contributes to it"), guard + Extension tests re-run green.

**Verification (2026-10-30).** 14-package sequential loop (`com.keevo.<pkg>.**`, one at a time: shared, crm, architecture, admin, catalog, commerce, feedback, identity, inventory, messaging, reporting, store, subscription, sync) — all rc=0; surefire reports total 4,110 tests, 0 failures/errors. Live dev boot ×2 on `keevo_dev`: boot 1 `Module catalogue seeded — inserted=[] updated=[pos] unchanged=9` (`pos updated (version 1.1.0)`), `Extension point 'pos.checkout.post-sale-actions' declared by module 'pos' (no contribution in this build)`; boot 2 `Module catalogue unchanged — 10 module(s) up to date`; `module_definitions` pos row = 1.1.0, `extensionPoints.declares ["pos.checkout.post-sale-actions"]`. Backend stopped with `fuser -k 4500/tcp`. No tenant data touched.

**Review fixes (2026-10-30, coordinator review of the first pass):**
- *1 — snapshots.* New published record `shared.module.ExtensionView(module, id, label, order, action) implements Extension`, built once at initialization from the validated values; `contributions()` returns only these (never the bean, not castable to it, later bean changes not seen). Javadoc of `Extension` / `ExtensionRegistry` updated. Tests: `ExtensionRegistryServiceTest.snapshot` (mutable label changed after init → frozen value, not the bean, `module()` set) and `proxied` (equals the expected view).
- *2 — annotation placement.* `ModuleBeans` (shared with capabilities): the annotation must be directly on the bean's user class (`MergedAnnotations` DIRECT, through AOP proxies) or on its `@Bean` factory method (`AnnotatedBeanDefinition.getFactoryMethodMetadata`); otherwise the bean is a boot problem naming the class ("must be declared on the bean class itself or on its @Bean method, not inherited from a superclass or an interface") and is not registered (no double registration, no spurious manifest error). `ModuleBeans.ofInstance` applies the same rule to `initialize(Collection)`. Tests: annotated base + subclass and annotated interface in `ExtensionRegistryServiceTest.inheritedAnnotation`, `CapabilityRegistryServiceTest.inheritedAnnotation`, and in a container `ExtensionRegistryContextTest.inheritedAnnotation`, `CapabilityRegistryContextTest.inheritedAnnotation`.
- *3 — lazy init.* Both registries `@Lazy(false)`, so `afterSingletonsInstantiated` always validates; a build failure is cached and rethrown as is by every later call (no rebuild), `initialize` refuses after a failure; the first-call path stays as a fallback. Tests: `*ContextTest.lazyInitializationStillValidates` (global `LazyInitializationBeanFactoryPostProcessor` + manifest entry without bean → refresh fails), `ExtensionRegistryServiceTest.failureCached`, failure identity in `CapabilityRegistryServiceTest.inheritedAnnotation`.
- *4 — unknown tenant.* `contributions` returns empty when `!registry.isKnownTenant(schema)` (before any state read). Test `unknownTenant`: `isKnownTenant=false` with core/pos/contributors ENABLED → empty, no `moduleState` call.
- *5 — keys.* `id()` and `action()` must match `POINT_PATTERN` and start with the contributor module id + `.` (boot problem names the value); `POINT_PATTERN` now `^[a-z][a-z0-9]*(-[a-z0-9]+)*(\.[a-z][a-z0-9]*(-[a-z0-9]+)*)+$` (no leading/trailing/double hyphen); loader rejects a module filling a point it declares; duplicate `fills` entries were already rejected by the generic list rule (now tested); `Extension.action()` Javadoc: client routing key, no backend dispatcher. Tests: `ExtensionRegistryServiceTest.keys`, updated fixtures (`SameIdAsLoyalty`, `CrmOtherSlot` → `crm.*` ids), `ModuleManifestLoaderTest.declaredPointFormat` (+ hyphen cases) and `fillsOwnPointOrTwice`.
- *6 — loader dead branch.* The cross-file "declared by two modules" branch removed from `validateExtensionPoints` (now only "fills an undeclared point"; comment explains why the registry check stays); test renamed `duplicateModuleDeclaringAPoint` (asserts the duplicate module id rejection).
- *7 — ArchUnit.* Rule (f) also covers parameters of `@Bean` and `@Autowired` methods; involved types sorted by name before `findFirst` (deterministic detail). New fixtures `CapabilityGeneric` (`Optional<ClientLookupPort>` field), `CapabilityBeanMethod`, `ExtensionAutowiredSetter` (`List<Extension>` setter) flagged; compliant `ExtensionPlainMethod` (plain method parameter, `@Bean` returning an `Extension`) not flagged; details asserted in the self-check.
- *8 — tests.* `ExtensionRegistryContextTest.beanMethodNotInjectable` (`@Bean`-method contribution: constructor injection fails, definition non-candidate, registry finds it); `sorted` now proves the extension-id tie-break (`crm.a-first` before `crm.z-last`, same module and order, registered in reverse).
- Also: `ModuleBeanAutowireExclusion` Javadoc states the explicit-lookup (`getBean` / `getBeansOfType`) limit.
- Verification after fixes: `com.keevo.shared.**`, `com.keevo.architecture.**`, `com.keevo.crm.**` run one at a time — all rc=0, 1,043 tests, 0 failures/errors.
- Accepted / routed by the coordinator (not done here): declarer READ_ONLY still lists (spec rule); `getBeansOfType` bypass (documented); registration wording, permission / per-sale context → 14.5; server-driven vs client registry and DTO → 15.3.

**Final verification (orchestrator, 2026-10-30, after review patches).** 14 packages sequential: 4,121 tests, 0 failures/errors; Modulith `✓ No module violations detected.` Live dev boot: started 45.4 s, « Module catalogue unchanged — 10 module(s) up to date » (pos 1.1.0 seeded by the implementer's earlier boot), 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass (13-7)
- verdicts: 2 layers (Blind Hunter 9, Edge+Gap+Intent 12; overlap) — high 0, medium 5, low 9 after dedup
- findings:
  - `[medium]` `[patch]` live beans returned (values re-read, castable, not serialisable) — immutable `ExtensionView` snapshots built at init
  - `[medium]` `[patch]` annotation inherited from a superclass/interface registered subclasses twice — must be on the bean class or `@Bean` method (both registries)
  - `[medium]` `[patch]` lazy init skipped boot validation, retried failures — `@Lazy(false)` on both registries, failure cached
  - `[medium]` `[patch]` ArchUnit gaps (`@Bean`/`@Autowired` method params, generic capability fixture, non-deterministic detail)
  - `[medium]` `[defer]` server-driven slots vs Flutter registry, DTO → routed → 15-3
  - `[low]` `[patch]` unknown tenant could see core contributions — `isKnownTenant` guard
  - `[low]` `[patch]` keys: `id`/`action` prefixed by contributor module, stricter point pattern, no self-fill, duplicate fills tested
  - `[low]` `[patch]` unreachable loader branch removed, misnamed test fixed; `@Bean` non-injectable + id tie-break tests
  - `[low]` `[reject]` declarer READ_ONLY still lists contributions — spec rule (slot unused without sales)
  - `[low]` `[reject]` `getBeansOfType` bypass — documented in `ModuleBeanAutowireExclusion`
  - `[low]` `[defer]` permission / per-sale context, registration wording → routed → 14-5 (wording fixed in the epic)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... com.keevo.architecture.**`, `com.keevo.commerce.**` -- expected: green
- 14-package sequential loop -- expected: green
- Live: dev boot → catalogue `pos` updated to 1.1.0, then unchanged on a second boot.
