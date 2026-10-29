---
title: '13.6 — CapabilityRegistry: tenant-aware optional capabilities between modules'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '569e6206aa65a5a6564e171fbd9088f8bd043614'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-5-entitlement-service.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-1-registre-modules-manifest-seed.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A module that can use another module's function when present (e.g. POS showing a CRM client name) must not hard-depend on it, and must degrade cleanly when the provider is off for the tenant (MOD-FR9, MOD-NFR10, spec-socle §6). No such mechanism exists; today consumers call `crm.api` ports directly whatever the tenant's modules.

**Approach:** A core `CapabilityRegistry` resolving, per tenant, a capability implementation contributed by its provider module only when that module is usable for the tenant; providers declare their capabilities in code (annotation) and in their manifest (`provides`), cross-checked at boot. Ship one real capability: CRM `ClientLookupPort`.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Contract** (published, `com.keevo.shared.module`): marker interface `Capability`; `CapabilityRegistry.find(Class<T extends Capability> type, String tenantSchema) → Optional<T>`; `has(type, tenantSchema)`. Tenant key = schema (13.2 convention); platform-admin schema → `Optional.empty()` (no tenant capabilities).
- **Provision**: a provider bean implements the capability interface and is annotated `@ProvidesCapability(module = "<id>")`. The registry collects them at startup. Programmatic `provide(...)` from spec §6 is not added (no `KeevoModule` lifecycle before 13.9) — the annotation is the registration path; 13.9 may add a programmatic one.
- **Resolution rule**: `find` returns the implementation iff the provider module's state for the tenant is not DISABLED (ENABLED or READ_ONLY — capabilities are lookups; a write capability must itself call `assertWritable`) — through `ModuleRegistry.moduleState`, request-cached; otherwise `Optional.empty()`, never an exception.
- **Boot validation** (fail-fast, message naming class/module): provider module not installed; two providers for the same capability type; a capability type that is not an interface extending `Capability`; a provider bean whose capability simple name is missing from its module manifest `provides`; a manifest `provides` entry with no provider bean. Manifest loader (routed from 13.1): `provides` names unique across manifests; `needs` entries match `^[A-Z][A-Za-z0-9]*Port\??$`; a `needs` entry without `?` (hard need) must be provided by a module in the consumer's `requires` closure.
- **Capability interfaces live in the provider's `api` named interface** (`crm.api.ClientLookupPort`); consumers depend on that `api` only (Modulith `allowedDependencies` already govern this).
- **First capability**: `crm.api.ClientLookupPort extends Capability` with `Optional<String> displayName(String tenantSchema, UUID clientId)` and `boolean exists(String tenantSchema, UUID clientId)`; CRM implementation annotated `@ProvidesCapability(module = "crm")` (backed by the existing client query); `crm` manifest `provides: ["ClientLookupPort"]`, version bumped to `1.1.0` (the 13.1 seed updates the catalogue row). No consumer migrated here — `SaleSyncHandler` / REST sale move to the capability in 14.3 (already routed).

## Boundaries & Constraints

**Always:** no behaviour change for existing flows; all test packages green; architecture suite green (`verify()` + rules); the capability call path does not open a transaction of its own.

**Never:** no new hard module dependency, no Flutter change, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Provider active | crm ENABLED | `find(ClientLookupPort)` → CRM impl | — |
| Provider read-only | crm READ_ONLY | impl returned | — |
| Provider disabled | crm DISABLED row | `Optional.empty()` | — |
| Unknown tenant | no tenants row | empty | — |
| Platform schema | `public` | empty | — |
| No provider at all | capability without bean | — | boot fails |
| Two providers | same type twice | — | boot fails |
| Manifest mismatch | bean without `provides` entry, or entry without bean | — | boot fails |
| Hard need unmet | `needs: ["XPort"]` not provided in requires closure | — | boot fails |
| Optional need | `needs: ["XPort?"]` absent | boot OK | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` (registry, manifest loader, `ModuleCatalog`).
- `keevo/backend/src/main/java/com/keevo/crm/api/` (`ClientQueryPort`), CRM client query implementation.
- `keevo/backend/src/main/resources/keevo/modules/crm/module.json`.
- Test catalogue helpers: `src/test/java/com/keevo/shared/module/TestModuleCatalogs.java`.

## Tasks & Acceptance

**Execution:**
- [x] `Capability`, `@ProvidesCapability`, `CapabilityRegistry` + implementation + boot validation; unit tests for every matrix row.
- [x] Manifest loader: `provides` uniqueness, `needs` format, hard-need closure check; tests.
- [x] `ClientLookupPort` + CRM provider + manifest `provides` + version 1.1.0; full-context Postgres test: crm ENABLED → impl resolves a real client's name; crm DISABLED row → empty.
- [x] Tick Story 13.6 routed item.

**Acceptance Criteria:**
- Given CRM active providing `ClientLookupPort`, when another module calls `find(ClientLookupPort.class, tenant)`, then it gets the CRM implementation; given CRM inactive for that tenant, then `Optional.empty()`.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** Published in `shared.module`: marker `Capability`, `@ProvidesCapability(module, value = {})` (empty `value` = every `Capability` sub-interface the class implements), `CapabilityRegistry.find/has`. Implementation `shared.module.application.CapabilityRegistryService` (`SmartInitializingSingleton`: collects `getBeansWithAnnotation(ProvidesCapability)` once all singletons exist, target class via `AopProxyUtils`, the proxy is what is returned). Validation is all-or-nothing in one `IllegalStateException` (class + module named): module not installed, declared type not an interface extending `Capability` / not implemented, bean with no capability interface, bean not annotated, duplicate provider, two types with the same simple name in one module, bean ↔ manifest `provides` both ways. `find`: platform schema → empty (no registry call); invalid schema → IAE (13.2 convention, checked before the lookup); no provider → empty; else `ModuleRegistry.moduleState(...).readable()`; before init → ISE. No `@Transactional`. `CapabilityRegistryServiceTest` (18 tests, every matrix row incl. a CGLIB-proxied provider); test helper `TestModuleCatalogs.withCapabilities`.

**Progress — task 2 done (2026-10-29).** `ModuleManifestLoader`: per file, every `needs` entry matches the public `NEEDS_PATTERN` `^[A-Z][A-Za-z0-9]*Port\??$`; across files (`validateCapabilities`, after the cycle check, same all-or-nothing report): `provides` names unique across manifests (both modules + locations named), and every hard need (no `?`) is provided by a module of the consumer's transitive `requires` closure (self excluded; the message says whether nobody provides it or which module to add to `requires` / mark the need optional). Optional needs are not checked. `ModuleManifestLoaderTest` +3 (`needsFormat`, `providesUnique`, `hardNeeds`); `ClasspathModuleManifestsTest` still green.

**Progress — task 3 done (2026-10-29).** `crm.api.ClientLookupPort extends Capability` (`displayName`, `exists`; same tenant convention as the other crm ports, archived clients included, null id → empty/false). Provider: new package-private `crm.contact.adapter.in.api.ClientLookupAdapter`, `@Component @ProvidesCapability(module = "crm")`, backed by `ClientRepository.findById` (the `existsById` read), separate from `ClientApiAdapter` so the existing ports are untouched. `crm/module.json`: `provides: ["ClientLookupPort"]`, version `1.1.0`; `ClasspathModuleManifestsTest` pins it (crm 1.1.0 + provides, others 1.0.0 + no provides). Tests: `ClientLookupAdapterTest` (3), `ClientLookupCapabilityPostgresIntegrationTest` (full context, real tenant via `TenantFactory`: free/ACTIVE crm → real client name resolved, `find` begins no transaction; DISABLED row → empty; unknown tenant / `public` → empty). Boot log: `Capability ClientLookupPort provided by module 'crm'`.

**Progress — task 4 done (2026-10-29).** Story 13.6 routed item (13-1 review: `needs` format / `provides` uniqueness) ticked in `epics-refonte-modulaire.md` with evidence.

**Verification (2026-10-29).** 14-package sequential loop (`com.keevo.<pkg>.**`, one at a time: shared, crm, architecture, admin, catalog, commerce, feedback, identity, inventory, messaging, reporting, store, subscription, sync) — all rc=0. Live dev boot ×2 on `keevo_dev`: boot 1 `Module catalogue seeded — inserted=[] updated=[crm] unchanged=9` (`crm updated (version 1.1.0)`), `Capability ClientLookupPort provided by module 'crm'`; boot 2 `Module catalogue unchanged — 10 module(s) up to date`; `module_definitions` crm row = 1.1.0, `provides ["ClientLookupPort"]`. Backend stopped with `fuser -k 4500/tcp`.

**Review fixes (2026-10-29, coordinator review of the first pass):**
- *1 — tenant check bypass.* `shared.module.infrastructure.CapabilityProviderAutowireExclusion` (`BeanFactoryPostProcessor`) sets `autowireCandidate=false` on every `@ProvidesCapability` definition; the registry still finds them by annotation. ArchUnit rule (f) `capability-injection` in `KeevoArchitectureRules` (no field / constructor parameter typed as a `Capability` sub-interface outside `com.keevo.shared.module..`), empty allow-list `architecture/capability-injection-allowlist.txt`, fixtures + `KeevoArchitectureRulesSelfCheckTest.capabilityInjectionRuleFlagsFieldAndConstructorButNotTheRegistryUser`; `CapabilityRegistryContextTest.notInjectable` (constructor injection → `NoSuchBeanDefinitionException` at refresh).
- *2 — init robustness.* `ensureInitialized()` (double-checked, synchronized, idempotent) from `afterSingletonsInstantiated` and from the first `find`. Tests: `CapabilityRegistryContextTest.lazyInitialization` (`LazyInitializationBeanFactoryPostProcessor`) and `initHook` (`@PostConstruct` consumer calling `find`).
- *3 — bean discovery.* Providers resolved by bean name (`getBeanNamesForAnnotation` + `findAnnotationOnBean` + `getType`); `@ProvidesCapability` now also targets `METHOD` (`@Bean` providers); `scopedTarget.*` skipped; prototype or scoped-proxy provider → boot failure naming the bean (and its manifest entries are not reported a second time). Implicit branch registers only the most specific capability interfaces, each checked with `type.isInstance(bean)`. Tests: `CapabilityRegistryContextTest.jdkProxy/beanMethodProvider/subCapability/nonSingletonProviders`, `CapabilityRegistryServiceTest.mostSpecificOnly`.
- *4 — manifest rules.* `PROVIDES_PATTERN` `^[A-Z][A-Za-z0-9]*Port$`; same name as hard + optional need rejected; optional need nobody provides → WARN only; a skipped duplicate provider no longer also yields "manifest lists … but no bean". Tests: `ModuleManifestLoaderTest.providesFormat/hardAndOptionalNeed/optionalNeedProvided` + WARN asserted in `hardNeeds`; `CapabilityRegistryServiceTest.duplicateReportedOnce/sameSimpleNameInOneModule`.
- *5 — provider data.* Clients have no soft-delete (only `archived`, kept on purpose) — no behaviour change. `ClientLookupAdapter` Javadoc corrected: no `@Transactional` of its own, but Spring Data's `findById` joins the caller's transaction or opens a short read-only one.
- *6 — tests/docs.* Integration test `activeThenDisabled` (same tenant: ACTIVE row → resolves the client's name, `find` opens no transaction; row set DISABLED + `evict` → empty) + `freeWithoutRow`; unit test renamed "provider module DISABLED for the tenant → empty"; `CapabilityRegistry` Javadoc: "absent provider or module off → empty; infrastructure errors propagate".
- Routed by the orchestrator, not done here (→ 14.3): FK `sales_client_id_fkey` with crm off, commerce `allowedDependencies += crm :: api`, client search method, `needs: ["ClientLookupPort?"]` in pos, provider exception handling in consumers, write capabilities on a READ_ONLY provider.

**Final verification (orchestrator, 2026-10-29, after review patches).** 14 packages sequential: 4,078 tests, 0 failures/errors; Modulith `✓ No module violations detected.` Live (implementer, before patches): two dev boots — `updated=[crm]` to 1.1.0 with `ClientLookupPort`, then unchanged. No device scenario (no consumer until 14.3).

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-6)
- verdicts: 2 layers (Blind Hunter 14, Edge+Gap+Intent 15; overlap) — high 3, medium 5, low 8 after dedup
- findings:
  - `[high]` `[patch]` tenant check bypassed by injecting the capability port directly — providers made non-autowire-candidates + ArchUnit rule `capability-injection`
  - `[high]` `[patch]` registry never initialised under lazy init / early `find` from an init hook — lazy synchronized initialisation on first `find`
  - `[high]` `[patch]` scoped/prototype providers broke the boot or were frozen — singleton-only (boot failure naming the bean), `scopedTarget.*` skipped
  - `[medium]` `[patch]` providers by class (missed `@Bean` methods, JDK proxies); implicit branch registered parent interfaces without `isInstance` — by bean name, most specific interfaces only
  - `[medium]` `[patch]` `provides` format unchecked; hard+optional same need; silent unprovided optional need; misleading duplicate error — rules + WARN + single error
  - `[medium]` `[patch]` integration ENABLED assertion vacuous — ACTIVE→DISABLED+evict in one tenant
  - `[medium]` `[defer]` FK `sales_client_id_fkey` when crm is off, commerce `crm :: api`, search method, pos `needs`, provider exceptions, write capability on READ_ONLY → routed → 14-3
  - `[low]` `[patch]` Javadoc (`find` errors propagate; provider transaction), misnamed unit test, same-simple-name branch tested
  - `[low]` `[reject]` soft-deleted clients — clients have no soft delete (archived kept on purpose)
  - `[low]` `[reject]` `@ProvidesCapability.value()` and extra boot checks — cheap, coherent, documented
  - `[low]` `[reject]` "no provider at all" enforced as "manifest entry without bean" — consistent with optional needs

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... com.keevo.crm.**`, `com.keevo.architecture.**` -- expected: green
- 14-package sequential loop -- expected: green
- Live: dev boot → catalogue log shows `crm` updated to 1.1.0, then unchanged on a second boot.
