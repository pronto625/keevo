---
title: '14.5 — POS checkout extension point: « Facturer » contribution, permission-aware slot endpoint, invoice lookup by sale'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '03d32f00e85bdbfce4803e908d57e936eff462cf'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-4-bridge-pos-facturation.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.5 (MOD-FR10, UX-DR3). POS declares the slot `pos.checkout.post-sale-actions` (13.7). When Invoicing is active, a « Facturer » contribution must be listed in it; when Invoicing is inactive, none. The Flutter rendering comes in 15.3, so the backend must expose the slot to the app. A routed item (review 13-7) also asks that the button's visibility follow the cashier's permission and the per-sale context, since since 14.4 the sale is usually already invoiced automatically.

**Approach:**
- Invoicing contributes a declarative extension (manifest `fills` + `@ContributesExtension`).
- `Extension` gains an optional required permission.
- A tenant endpoint returns the slot's contributions, filtered by the caller's permissions.
- The invoice list can be filtered by sale, so that the action opens the sale's invoice.

**Decisions (autonomous run; the user delegated all choices on 2026-10-28):**
- **Extension permission**:
  - `Extension` gains `default String permission() { return null; }` (null means anyone who can see the slot), and `ExtensionView` carries it.
  - `ExtensionRegistryService` fails the boot when a non-null permission is not declared in the contributing module's manifest `permissions`. The message names the class, the module and the permission.
  - `ExtensionRegistry.contributions(point, schema)` is unchanged: it is tenant-level and does no user filtering.
- **Contribution**: `invoicing/…/adapter/in/extension/CheckoutInvoiceAction` (`@Component @ContributesExtension(module="invoicing", point="pos.checkout.post-sale-actions")`):
  - id `invoicing.invoice-sale`
  - label « Facturer »
  - order 100
  - action `invoicing.open-sale-invoice`
  - permission `INVOICE_VIEW`
  - Invoicing manifest: `extensionPoints.fills: ["pos.checkout.post-sale-actions"]`, version 1.2.0.
- **Slot endpoint**: `GET /api/v1/tenants/me/extensions/{pointId}` in `shared/module/adapter/in/rest/` (new `TenantExtensionsController`, or inside `TenantModulesController`).
  - Annotations: `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` and `@EntitlementExempt("points d'extension du commerçant")`.
  - Returns `[{id, label, order, action}]` = `contributions(point, currentTenant)` filtered to those whose permission is null or included in `ModulePermissions.permissionsOf(caller roles)`.
  - An unknown point gives 404 `NOT_FOUND` « Point d'extension inconnu ». A malformed point id gives the same 404, with no technical data.
- **Per-sale context**: `GET /api/v1/invoices` accepts an optional `saleId` (UUID; malformed gives 400 like the other params) that returns the invoice of that sale, if any (0 or 1 item, same paging envelope).
  - The app's `invoicing.open-sale-invoice` action uses it to open the auto invoice. An empty result means the invoice is not created yet (async or offline), and the app shows « Facture en cours de création » (Flutter work in 15.3/15.5).
  - No create-from-sale endpoint: since 14.4 the invoice is created by the bridge.
- `docs/entitlements/endpoints.md` is regenerated.

## Boundaries & Constraints

**Always:**
- Declarative path only (13.7). No new programmatic registration.
- Invoicing still depends on `shared` + `crm :: api` only.
- The extension endpoint never shows a contribution of a module that is not ENABLED (registry rule).
- French label; no UUID in labels.
- All backend packages pass one by one, plus prod-classpath.

**Never:**
- No Flutter work.
- No change to the existing `contributions` semantics.
- No deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Invoicing enabled, owner | GET `/tenants/me/extensions/pos.checkout.post-sale-actions` | `[{id:"invoicing.invoice-sale", label:"Facturer", order:100, action:"invoicing.open-sale-invoice"}]` | — |
| Employee | same | same (employees hold INVOICE_VIEW) | — |
| Invoicing DISABLED / FREE / READ_ONLY | same | `[]` | — |
| POS DISABLED | same | `[]` (declaring module off) | — |
| Unknown point | `/extensions/foo.bar` | — | 404 « Point d'extension inconnu » |
| Contribution with permission not held | test-only extension requiring a permission the role lacks | filtered out | — |
| Undeclared permission | contribution permission not in its manifest | boot fails (registry validation test) | — |
| Sale invoice lookup | GET `/invoices?saleId=<auto-invoiced sale>` | 1 item with that `saleId` | — |
| No invoice yet | `saleId` unknown | empty page | — |
| Malformed saleId | `saleId=abc` | — | 400 |

</intent-contract>

## Code Map

Paths are relative to `keevo/backend/src/main/java/com/keevo/` unless stated otherwise.

**Extension registry (`shared/module/`)**
- `Extension.java`, `ExtensionView.java`, `ContributesExtension.java` -- the contribution contract (id, label, order, action).
- `application/ExtensionRegistryService.java` -- collects the beans and validates them at boot; add the permission validation here.
- `application/ModulePermissions.java` -- `permissionsOf(Collection<String> roles)` and `isDeclared(permission)`.

**Endpoint**
- `adapter/in/rest/TenantModulesController.java` -- pattern for a tenant endpoint (`@EntitlementExempt`, `ApiResponseWrapper`, `TenantSchema.validate(TenantContext.getCurrentTenant())`).
- `shared/infrastructure/security/CurrentActor` -- the caller's roles.

**Manifests**
- `src/main/resources/keevo/modules/pos/module.json` -- declares the point.
- `src/main/resources/keevo/modules/invoicing/module.json` -- `fills` + version.
- `src/test/java/com/keevo/shared/module/ClasspathModuleManifestsTest.java` -- the manifest assertions.

**Invoicing**
- `invoicing/invoice/adapter/in/rest/InvoiceController.java:103-115` -- the list params; add `saleId`.
- `InvoiceQueryUseCase` + `JdbcInvoiceRepository` -- list query; filter on `source_sale_id` (V3, 14.4).

**Tests**
- Existing extension tests under `src/test/java/com/keevo/shared/module/` (ExtensionRegistry*). Reuse their fixtures for the permission validation.

## Tasks & Acceptance

**Execution:**
- `shared/module/Extension.java`, `ExtensionView.java`, `ExtensionRegistryService.java` -- optional permission + boot validation; unit tests (declared OK, undeclared fails the boot, null allowed).
- `shared/module/adapter/in/rest/TenantExtensionsController.java` -- the slot endpoint with role filtering; full-context tests for the extension rows of the I/O matrix (owner, employee, invoicing disabled/FREE/READ_ONLY, pos disabled, unknown point, filtered permission via a test contribution).
- `invoicing/invoice/adapter/in/extension/CheckoutInvoiceAction.java` + invoicing `module.json` 1.2.0 `fills` + manifest test -- the contribution.
- `InvoiceController` / `InvoiceQueryUseCase` / `JdbcInvoiceRepository` -- `saleId` filter; tests: auto-invoiced sale found, unknown → empty, malformed → 400.
- `docs/entitlements/endpoints.md` -- regenerate.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 14.5 routed item, with the decision noted.

**Acceptance Criteria:**
- Given pos and invoicing active, when the app reads the checkout slot, then it gets the « Facturer » action and, after a sale, `GET /invoices?saleId=` returns that sale's invoice.
- Given invoicing inactive, when the app reads the slot, then it gets an empty list, and the invoices endpoints answer 403 as before.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 25 findings — high 0, medium 3, low 15, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (verification-gap) no gaps — informational.
  - `[low]` `[reject]` (intent 1, blind, edge) Per-sale visibility not on the slot endpoint — the spec's decision: 14.4 auto-invoices every sale, so the action opens the sale's invoice (`?saleId=`); the app behaviour is now routed explicitly to 15.5 and the tick note says « server side only ».
  - `[low]` `[reject]` (intent 2) Permission `INVOICE_VIEW` filters no shipped role — the action only opens an existing invoice; VIEW is the right permission; the filter mechanism is tested with a non-held permission.
  - `[false]` `[reject]` (intent 3, blind) « Facturer » label vs open action — the label is fixed by the story AC (« une contribution « Facturer » »).
  - `[false]` `[reject]` (intent 4) New REST endpoint beyond the AC — the AC is also met on `ExtensionRegistry.contributions` (registry full-context test); the endpoint is required for the app (15.3) to read the slot.
  - `[false]` `[reject]` (intent 5) No UI tests — the story states « rendu UI en Epic 15 ».
  - `[medium]` `[patch]` (intent 6, blind) The ticked 13-7 item still carried the `@Bean`-method gap and the app-side per-sale behaviour — fixed in the epics doc: tick note restricted to the server side; `@Bean` gap routed to 15.3, per-sale app behaviour + slot caching routed to 15.5.
  - `[medium]` `[patch]` (blind) Role extraction duplicated from `PermissionAuthorizationManager` (slot could show a button whose endpoint answers 403) — fixed: one shared helper used by both.
  - `[low]` `[reject]` (blind) 404 built by hand to dodge the NOT_FOUND mapping — correct French output here; the pre-existing mapping gap is routed to 16.7.
  - `[low]` `[reject]` (blind, edge) Filtered-permission case only in the WebMvc test — real `ModulePermissions` role mapping (EMPLOYEE lacks MANAGE) is covered by 14.1's permission tests; the filter itself is tested.
  - `[low]` `[reject]` (blind) No programmatic-path permission test — the programmatic path calls the same `indexOne` (verified by the verification-gap layer).
  - `[low]` `[reject]` (blind) `otherModulesPermission` fixture may pass for the wrong reason — the assertion still proves an undeclared permission fails the boot; negligible.
  - `[low]` `[reject]` (blind) `anonymous()` accepts 401 or 403 — either status refuses the call; cosmetic.
  - `[low]` `[reject]` (blind) English `invalid sale id` message — same convention as the other list params (`page`, `size`); the app maps `VALIDATION_ERROR`, not the text.
  - `[low]` `[reject]` (blind) Employee row not repeated for every module state — module-state filtering does not depend on the role.
  - `[low]` `[reject]` (blind) No slot caching guidance — routed with the app behaviour to 15.5.
  - `[low]` `[reject]` (blind) Test tenants not dropped — same cleanup pattern as the other full-context tests (Testcontainers DB per run).
  - `[low]` `[reject]` (edge) READ_ONLY hides a view-only action — 13.7 registry rule (« a READ_ONLY module contributes nothing ») kept on purpose; the invoices stay reachable from the invoicing screens.
  - `[false]` `[reject]` (edge) `TenantSchema.validate` IllegalArgumentException for OWNER/EMPLOYEE — these tokens always carry a valid tenant (JWT filter); not reachable.
  - `[medium]` `[patch]` (edge) A 36-char id with misplaced dashes passes `UUID.fromString` leniently → empty page instead of 400 — fixed: canonical pattern check.
  - `[low]` `[reject]` (edge) Blank permission gives a « does not declare » message — boot still fails; message wording only.
  - `[false]` `[reject]` (edge claim) full-context filtered-permission case missing — duplicate of the row above.
  - `[false]` `[reject]` (edge claim) ticked item misleads readers — duplicate of the epics-doc patch above.
  - `[low]` `[reject]` (blind) spec test row moved to WebMvc without a change-log note — fix would edit this build's spec.
  - `[low]` `[reject]` (blind) role extraction drift risk counted once with the patch above (second phrasing).

## Verification

**Commands** (from `keevo/backend`, one package at a time):
- `mvn -q -o test -Dtest='com.keevo.<pkg>.**' -DforkCount=1 -DargLine=-Xmx1536m -Dsurefire.failIfNoSpecifiedTests=false` for shared, invoicing, architecture, admin, then the others -- expected: 0 failures.
- `mvn -q -o -Pprod-classpath test -Dtest='com.keevo.architecture.**'` -- expected: rc 0.

**Manual checks:**
- Live on dev, tenant `kv_zggt7g`:
  - owner GET of the slot → « Facturer »;
  - after a sale, `GET /invoices?saleId=` → the auto invoice.

## Auto Run Result

Status: done

**Summary.** Invoicing contributes « Facturer » (`invoicing.invoice-sale`, action `invoicing.open-sale-invoice`, permission `INVOICE_VIEW`) to `pos.checkout.post-sale-actions` through the manifest `fills` and `@ContributesExtension`. `Extension` gains an optional `permission()`, validated at boot against the contributing manifest. The new `GET /api/v1/tenants/me/extensions/{pointId}` returns the slot filtered by the caller's permissions, through the same role helper as `@RequiresPermission`; an unknown point gives 404 « Point d'extension inconnu ». `GET /api/v1/invoices?saleId=` returns a sale's auto invoice; a malformed id gives 400.

**Files.**
- shared.module: `Extension`, `ExtensionView`, `ContributesExtension` (doc), `ExtensionRegistryService`, `ModulePermissions.rolesOf`, `PermissionAuthorizationManager`, `adapter/in/rest/TenantExtensionsController` (new).
- invoicing: `adapter/in/extension/CheckoutInvoiceAction` (new), `InvoiceController`/`InvoiceQueryUseCase`/`InvoiceRepository`/`JdbcInvoiceRepository` (`saleId`), manifest 1.2.0 `fills`.
- `docs/entitlements/endpoints.md` regenerated.
- Epics doc: 13-7 item ticked (server side), `@Bean` gap → 15.3, app per-sale behaviour → 15.5, pre-existing NOT_FOUND message leak → 16.7.

**Review.** 25 findings: 3 patched (medium: role extraction duplicated, ticked item over-claiming / unrouted parts; low→medium: lenient UUID parsing), 0 deferred, 22 rejected (triage log). Follow-up review recommended: **true** (two or more mediums patched). The unverified risk is the shared role helper now also used by `@RequiresPermission` on every gated endpoint; it is covered by `PermissionGatingSliceTest`, but the change sits on the security path.

**Verification.**
- Before patches all 15 packages were green. After patches: shared 1 044, invoicing 102, architecture 32, admin 517, identity 446 — 0 failures; prod-classpath rc 0.
- Live (dev, kv_zggt7g, owner JWT):
  - boot `updated=[invoicing]`;
  - slot → `[{invoicing.invoice-sale, « Facturer », 100, invoicing.open-sale-invoice}]`;
  - `foo.bar` → 404 « Point d'extension inconnu »;
  - `?saleId=<14.4 sale>` → 1 item `FAC-2026-000005`; unknown → empty page; `abc` → 400.
- The dev Postgres container had stopped during a session restart and was started again (no data change).

**Residual risks.** The app must implement the open/“en cours de création” behaviour (15.5).
