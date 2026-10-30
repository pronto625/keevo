---
title: '14.6 — Vision acceptance test: the 4 module-activation scenarios (invoicing alone, +crm, +pos, pos deactivated)'
type: 'chore'
created: '2026-10-30'
status: 'done'
baseline_revision: '737821addfa87127a84eb27e1530c06ea10623bc'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-4-bridge-pos-facturation.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-5-extension-checkout-facturer.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.6 (MOD-FR7/8/9/10). The "connected mini-apps" promise must be proven by one automated test that goes through the 4 activation combinations on a single tenant, so that no later change can break it unnoticed. Today each piece (14.1–14.5) is tested in isolation, with module states forced through `tenant_modules` inserts.

**Approach:** One full-context acceptance test class runs the 4 scenarios **in order on one tenant**. It changes module states through the real lifecycle service (`ModuleLifecycleService`, 13.8), the same path the super-admin uses, and asserts only at the REST surface the app uses, plus published events.

**Decisions (autonomous run; the user delegated all choices on 2026-10-28):**
- **Location**: `keevo/backend/src/test/java/com/keevo/acceptance/ModularVisionAcceptanceTest.java`, extending the existing full-context base used by `InvoicingFullContextTest` and authenticated with a real owner JWT. It is a test-only change; no production code changes unless the test exposes a defect. If it does, fix the defect in the owning module and record it in this spec's Auto Run Result.
- **Module toggling**: through the 13.8 lifecycle service (activate/deactivate with plan check and provisioning), not raw SQL. Start from a PREMIUM tenant, so that invoicing is allowed.
  - Scenario 1 is invoicing alone: pos and crm deactivated, and catalog/inventory as far as the dependency rules allow. Deactivate pos first.
  - Scenario 2 activates crm.
  - Scenario 3 activates pos and its requirements.
  - Scenario 4 deactivates pos.
- **Events**: assert `invoice.created` / `invoice.issued` / `invoice.paid` for the auto invoice, through `AssertablePublishedEvents` / `PublishedEvents` when Spring Modulith test support is on the test classpath. Otherwise use the existing pattern: rows in `public.event_publication`, plus the audit rows.
- **Scenario assertions (REST surface)**:
  1. **Invoicing alone**:
     - create a draft with a free-text client, issue it and pay it → numbered, PAID;
     - `GET /invoices/clients` → `crmAvailable:false`;
     - `POST /api/v1/sales` → 403 `MODULE_NOT_ENTITLED`;
     - slot `GET /tenants/me/extensions/pos.checkout.post-sale-actions` → `[]`.
  2. **+crm**:
     - create a CRM client via `POST /api/v1/clients`;
     - `GET /invoices/clients?search=` lists it;
     - a draft with `client.id` is linked and prefilled.
  3. **+pos**:
     - sell through `POST /api/v1/sales` (CRM client, CASH) → within seconds exactly one PAID invoice with `saleId`, CRM client name and TTC = the sale total;
     - the slot lists « Facturer »;
     - `GET /invoices?saleId=` returns it;
     - the 3 invoice events were published.
  4. **pos deactivated**:
     - `POST /sales` → 403;
     - a redelivery of the scenario-3 `SaleCompletedEvent` (and of a fresh one for a new sale id) → no new invoice;
     - manual invoicing still works (create, issue);
     - every invoice from scenarios 1–3 is still listed and unchanged (same numbers, statuses, totals);
     - slot → `[]`.
- **Ordering**: the class uses ordered test methods, or a single test with 4 clearly named steps, that share the tenant. It is self-contained and does not depend on other test classes.

## Boundaries & Constraints

**Always:**
- REST surface plus events only. No direct use-case calls except the lifecycle toggles and the event redelivery.
- French assertions where messages are checked.
- The test runs in the `acceptance` package and passes when run on its own.
- All backend packages pass one by one, plus prod-classpath.

**Never:**
- No Flutter.
- No new production feature.
- No change to module rules to make the test pass. A failing scenario is a defect to fix in its module.
- No deploy.

</intent-contract>

## Code Map

Paths are relative to `keevo/backend/src/` unless stated otherwise.

**Patterns to copy**
- `test/java/com/keevo/invoicing/InvoicingFullContextTest.java` -- full-context base, TenantFactory, owner JWT, `tenant_modules`, PREMIUM setup.
- `test/java/com/keevo/invoicing/SaleInvoicingFullContextTest.java` (14.4/14.5) -- real `POST /api/v1/sales` + Awaitility on the async auto invoice, `?saleId=`, event redelivery helpers.
- `test/java/com/keevo/shared/module/TenantExtensionsFullContextTest.java` (14.5) -- slot endpoint with JWT.
- `test/java/com/keevo/invoicing/InvoicingCrmIntegrationFullContextTest.java` (14.3) -- CRM on/off and client suggestions.

**Lifecycle**
- 13.8 lifecycle service: `main/java/com/keevo/shared/module/application/ModuleLifecycleService.java` (or the class behind `ModuleLifecycle`), plus its tests under `test/java/com/keevo/shared/module/` and `admin/module`. They show how to activate and deactivate with an actor and how dependency rules apply.

**Event support**
- `pom.xml` -- check for `spring-modulith-starter-test` / `spring-modulith-test`.
- `test/java/com/keevo/shared/infrastructure/event/EventPublicationRegistryIntegrationTest.java` -- the `event_publication` assertion pattern.

## Tasks & Acceptance

**Execution:**
- `test/java/com/keevo/acceptance/ModularVisionAcceptanceTest.java` -- the 4 ordered scenarios, as decided.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 14.6 implementation notes and record where the test lives.

**Acceptance Criteria:**
- Given the acceptance class, when run alone (`-Dtest='com.keevo.acceptance.**'`), then the 4 scenarios pass in order on one tenant.
- Given a regression in any 14.x link (CRM capability, bridge, slot, pos gating), when the class runs, then at least one named scenario step fails with a message naming the scenario.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 30 findings — high 0, medium 3, low 17, false 10, maybe-false 0
- findings:
  - `[false]` `[reject]` (verification-gap) no gaps — informational.
  - `[false]` `[reject]` (blind) Scenario-4 synthetic event could be ignored for reasons other than pos gating — the bridge reads only the event data (no sale lookup); the implementer's mutation check (pos gate removed) made scenario 4 fail at « aucune facture pour une nouvelle vente ».
  - `[low]` `[reject]` (blind, intent 6) Idempotency not tested in isolation / not through the registry's resubmission — covered by 14.4 `SaleInvoicingFullContextTest` (sequential registry replay + 6 concurrent deliveries); out of 14.6's 4-scenario scope.
  - `[medium]` `[patch]` (blind, intent 5/G2) Scenario 4 asserts no events and no POS/CRM data preservation — fixed: no invoice events for the redelivered/fresh sale; scenario-3 sale and CRM client still present.
  - `[medium]` `[patch]` (blind, intent 7) « invoicing alone » never pinned — fixed: scenario 1 asserts pos/crm DISABLED and invoicing ENABLED, naming catalog/inventory kept by dependents.
  - `[low]` `[reject]` (blind) Snapshot of invoices shallow — number/status/TTC/client/sale are the fields a deactivation could plausibly alter; issued invoices are immutable by construction (14.1 tests).
  - `[low]` `[patch]` (blind, intent 1) Epics note ticked although `@ApplicationModuleTest` was not used — fixed: note struck through and rewritten as a recorded decision.
  - `[false]` `[reject]` (blind) Spec lacks Auto Run Result / prod-classpath command — written at finalisation; the change is test-only.
  - `[medium]` `[patch]` (blind, edge) `assumeTrue` makes a single-scenario run green while verifying nothing — fixed: failing assertion naming the scenario.
  - `[low]` `[reject]` (blind) Teardown leaves the tenant — same pattern as every full-context test (throwaway Testcontainers DB).
  - `[low]` `[reject]` (blind, edge) Year computed once in Africa/Douala — the numbering uses the Africa/Douala clock (14.1); a run spanning New Year's midnight is negligible.
  - `[low]` `[reject]` (blind) No non-owner role coverage — role filtering is tested in 14.5; the 4 scenarios are about module activation.
  - `[false]` `[reject]` (intent 2/C2-C3) « aucune route POS » only checked on `POST /sales` and the slot — every pos endpoint carries the same `@RequiresEntitlement("pos")` guard (EndpointEntitlementCoverageTest); app routes are Epic 15.
  - `[false]` `[reject]` (intent 3/D2) « sélecteur » is UI — the picker UI is 15.5; 14.6 proves the backend contract.
  - `[low]` `[reject]` (intent 4/B2) invoicing+pos without crm never combined — the story's scenarios are cumulative (« +crm », « +pos »); the no-client path is covered in 14.4.
  - `[false]` `[reject]` (intent 8) consumption checked via `event_publication` — explicitly allowed by the spec.
  - `[low]` `[reject]` (edge) Redelivered events also reach other sale.completed listeners — audit/metrics side effects in a throwaway test DB; no assertion depends on them.
  - `[low]` `[reject]` (edge claim) Helper messages do not name the scenario — the JUnit display name of the failing test method names the scenario; lifecycle exceptions carry their own French messages.
  - `[low]` `[reject]` (edge claim) Some assertions use internal registry/table — the spec allows lifecycle toggles and registry checks; REST is used for every user-visible behaviour.
  - `[false]` `[reject]` (intent A) harness differs from `@ApplicationModuleTest` — duplicate of the epics-note patch above.
  - `[false]` `[reject]` (blind) shallow scenario-2 draft identity — counted with the snapshot row above.
  - `[low]` `[reject]` (blind) `deactivateIfAllowed` swallows MODULE_HAS_DEPENDENTS silently — now surfaced in the scenario-1 assertion message (patch above).
  - `[low]` `[reject]` (blind) Scenario 1 relies on the trial enabling invoicing — a Premium tenant enables paid modules by plan; that is the product rule under test.
  - `[false]` `[reject]` (intent F) re-sending the scenario-3 event hits idempotency regardless of pos — acknowledged by the test design; the fresh-sale event isolates the pos gate.
  - `[low]` `[reject]` (intent G) catalog/inventory states not asserted — superseded by the scenario-1 patch.
  - `[low]` `[reject]` (blind) paging of `GET /invoices?size=100` unchecked — 3 invoices in the test.
  - `[low]` `[reject]` (blind) `asText()` on missing fields — the compared fields are present in the DTO contract (14.1/14.4 tests).
  - `[low]` `[reject]` (edge) teardown fails if `@BeforeAll` failed — the original failure is still reported first.
  - `[false]` `[reject]` (blind) spec Verification lists only the acceptance package — prod-classpath was run at finalisation.
  - `[low]` `[reject]` (intent) the event recorder is hand-rolled — reason recorded (async thread), uses Modulith's `PublishedEvents` API.

## Verification

**Commands** (from `keevo/backend`):
- `mvn -q -o test -Dtest='com.keevo.acceptance.**' -DforkCount=1 -DargLine=-Xmx1536m -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures.
- The same command per package for every other package touched by any defect fix -- expected: 0 failures.

## Auto Run Result

Status: done

**Summary.** `keevo/backend/src/test/java/com/keevo/acceptance/ModularVisionAcceptanceTest.java` proves the 4 activation scenarios in order on one Premium tenant. Every module toggle goes through `ModuleLifecycle`, and the checks run at the REST surface with a real owner JWT.

| Scenario | What it proves |
|---|---|
| 1 — invoicing alone | Invoicing ENABLED, pos and crm DISABLED; manual invoice issued and paid; `crmAvailable:false`; `POST /sales` 403; slot empty |
| 2 — + crm | Client created and suggested; draft linked and prefilled |
| 3 — + pos | Sale gives exactly one PAID auto invoice with the CRM client and the sale total; « Facturer » in the slot; created, issued and paid events published |
| 4 — pos deactivated | Sale 403; redeliveries produce no invoice and no invoice event; manual invoicing goes on; invoices, sale and client kept; slot empty |

Running one scenario alone fails with a message naming it.

**Changes.** Test-only, plus the epics doc: both 14.6 notes are ticked, and `@ApplicationModuleTest` is struck through as a recorded decision, because the async auto-invoice events are invisible to `PublishedEventsExtension`. No defect was found in production code.

**Review.** 30 findings: 4 patched (3 medium, 1 low; all test and doc), 0 deferred, 26 rejected (see the triage log). Follow-up review recommended: false, since only tests and docs changed and no production risk is introduced.

**Verification.**
- `acceptance`: 4 tests, 0 failures, after patches.
- Mutation check by the implementer: with the 14.4 pos gate removed, scenario 4 fails.
- Production code is unchanged since 14.5, where all packages and prod-classpath were green.
