---
title: '14.3 — Optional CRM integration (ClientLookupPort): invoice client picker, sale client check, crm sync handlers moved to crm'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '9c4912a11ef66e43dc77ea77858eb023eafa4f1e'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-1-module-facturation-autonome.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.3 (MOD-FR9): when `crm` is active, an invoice should take its client from the CRM base; when `crm` is off, free text must keep working with no error. Three routed items ride along:
- `POST /api/v1/sales` with an unknown `clientId` hits the FK `sales_client_id_fkey` and returns 500.
- Client consumers must move to the optional `ClientLookupPort` capability instead of the hard `crm.api.ClientQueryPort`.
- The sync handlers must be relocated so that `sync` loses its `crm :: api` edge.

**Approach:**
1. Widen `ClientLookupPort` (crm 1.2.0) with a summary lookup and a bounded search.
2. `invoicing` resolves the port per call through `CapabilityRegistry.find`. It exposes a client-suggestion endpoint and links or prefills the invoice client.
3. `commerce` checks the sale client through the same capability, which fixes the 500 for the REST and sync paths.
4. The sync handler SPI moves to `shared`, and the four client and supplier handlers move into `crm`.

**Decisions (autonomous run; the user delegated all choices on 2026-10-28):**
- **Port** (`crm.api`):
  - Add record `ClientSummary(UUID id, String name, String phone, boolean archived)`.
  - Add `Optional<ClientSummary> find(String tenantSchema, UUID clientId)`, which includes archived clients.
  - Add `List<ClientSummary> search(String tenantSchema, String query, int limit)`. It excludes archived clients, matches name or phone (case-insensitive contains; reuse `searchByNameOrPhone`, or `findAll(false)` when the query is blank), is sorted by name, and caps `limit` to 1–50.
  - Keep `displayName` and `exists` unchanged.
  - Bump the crm manifest version to `1.2.0`. No DDL. The port stays read-only, so no `assertWritable` is needed.
- **Invoicing**:
  - `package-info` `allowedDependencies = {"shared", "crm :: api"}`. The comment states that only the capability interface is used and never injected.
  - New `GET /api/v1/invoices/clients?search=&limit=20`:
    - annotations: `@RequiresEntitlement(invoicing, READ)` and `@RequiresPermission(INVOICE_CREATE)`;
    - returns `{crmAvailable: boolean, clients: [{id, name, phone}]}`;
    - capability absent → `{crmAvailable:false, clients:[]}` with status 200.
  - Create and update of a draft:
    - `client.id` null → free text, as in 14.1.
    - `client.id` set and the capability present:
      - unknown id → 404 `CLIENT_NOT_FOUND` « Client introuvable »;
      - known id → the link is kept; a blank `name` is filled from the CRM name and a blank `phone` from the CRM phone; non-blank typed values win.
    - `client.id` set and the capability absent (crm off): the id is dropped and the typed text is kept. This is degraded mode with no error.
  - `InvoiceClientDto.name` loses `@NotBlank` (it keeps `@Size(200)`). After resolution, a blank name gives 400 `VALIDATION_ERROR` « Le nom du client est obligatoire ».
  - Provider exceptions propagate; they are never swallowed.
- **Commerce sale client** (REST and sync go through `RecordSaleService`):
  - Add `"crm :: api"` to commerce `allowedDependencies`.
  - If `clientId` is non-null:
    - capability present and `!exists` → `DomainException(CLIENT_NOT_FOUND)`, so REST returns 404 and sync REJECTs as in 12.7;
    - capability absent → the sale is recorded **without** client, with a WARN log (no id). The sale is money and stock, so it must never be refused because an optional module is off.
  - The check runs after the idempotency guard and before the stock check.
  - `SaleSyncHandler` drops its own `ClientQueryPort` check.
  - The pos manifest gets `needs: ["ClientLookupPort?"]` and version `1.2.0`.
- **Sync SPI relocation**:
  - `SyncOperation`, `SyncOperationResult`, `SyncOperationStatus`, `SyncOperationHandler` and `AbstractSyncOperationHandler` move to `com.keevo.shared.application.port.sync`. This is a public SPI in the open `shared` module, following the `ExportDatasetProvider` precedent. Only the package moves; there are no code changes.
  - `CreateClientSyncHandler`, `ClientSyncHandler`, `CreateSupplierSyncHandler` and `SupplierSyncHandler` move to `com.keevo.crm.contact.adapter.in.sync`. They stay `@Component`, keep `moduleId()="crm"` and keep using the crm api ports. Their tests move with them.
  - `SyncOperationHandlerRegistry` still discovers handlers through `List<SyncOperationHandler>`.
  - Remove `"crm :: api"` from `sync` `allowedDependencies`.
  - The other 15 handlers stay in `sync`. Their `X :: api` edges are legitimate (sync orchestrates and its targets don't depend on sync), so moving them adds no value. **Decision recorded and the routed item is closed.**

## Boundaries & Constraints

**Always:**
- The capability is resolved per call via `CapabilityRegistry.find` and never injected (ArchUnit `capabilities_neverInjected` stays green with no new allowlist entry).
- No import of crm internals outside crm.
- No DDL and no data change.
- French messages, and no UUID in messages or logs shown to users.
- `docs/entitlements/endpoints.md` is regenerated.
- All backend packages pass one by one, plus prod-classpath.
- The sync behaviour and the operation-type→module mapping are unchanged (`SyncModuleGuardPostgresIntegrationTest` still asserts 19 handlers).

**Never:**
- No Flutter work (the picker UI is 15.5).
- No write capability.
- No CRM NIU or address columns.
- No change to `sales_client_id_fkey` or to the `TenantSchemaProvisioner` DDL.
- No handler relocation beyond the four crm handlers.
- No deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Suggestions, crm on | `GET /invoices/clients?search=mir` | `crmAvailable:true`, matching non-archived clients sorted by name, ≤ limit | — |
| Suggestions, crm off | crm DISABLED | 200 `{crmAvailable:false, clients:[]}` | — |
| Invoice from CRM client | `client:{id:<known>, name:""}` | name and phone copied from CRM, `client.id` kept | — |
| Invoice CRM id, typed name | `{id:<known>, name:"SARL X"}` | name « SARL X » kept, id kept | — |
| Invoice unknown id, crm on | `{id:<random>}` | — | 404 `CLIENT_NOT_FOUND` |
| Invoice id, crm off | `{id:<any>, name:"Paul"}` | id dropped, « Paul » kept | — |
| Invoice no name | `{name:""}`, no id | — | 400 « Le nom du client est obligatoire » |
| Sale unknown client, crm on | REST `POST /sales` clientId random | nothing written | 404 `CLIENT_NOT_FOUND` (was 500) |
| Sale client, crm off | clientId set | sale stored with `client_id` null, stock decremented | — |
| Sync sale unknown client | CREATE_SALE op | REJECTED `CLIENT_NOT_FOUND` (unchanged) | — |
| Sync CREATE_CLIENT | crm on / off | APPLIED / REJECTED `MODULE_INACTIVE` (unchanged) | — |

</intent-contract>

## Code Map

Paths are relative to `keevo/backend/src/main/java/com/keevo/` unless stated otherwise.

**crm**
- `crm/api/ClientLookupPort.java:12` -- the capability; add `find` and `search`, plus the new `crm/api/ClientSummary.java`.
- `crm/contact/adapter/in/api/ClientLookupAdapter.java` -- the implementation (not a bean); it is provided in `crm/module/CrmModule.java:33-35`. Uses `ClientRepository.searchByNameOrPhone(q)` / `findAll(false)` (`crm/contact/domain/port/out/ClientRepository.java:21-23`) and `TenantContext.requireCurrent`.
- `src/main/resources/keevo/modules/{crm,pos}/module.json` -- version `1.2.0`; pos gets `needs`.

**Capability machinery**
- `shared/module/CapabilityRegistry.java:20` -- `find(type, schema)`; it is empty when the module is DISABLED, and returns the implementation when ENABLED or READ_ONLY (`CapabilityRegistryService.java:137-150`).

**invoicing**
- `invoicing/package-info.java` -- add `"crm :: api"`.
- `invoicing/invoice/adapter/in/rest/InvoiceController.java` -- add the suggestions endpoint; client mapping at :225-231.
- `invoicing/invoice/adapter/in/rest/dto/InvoiceClientDto.java` -- relax `@NotBlank`.
- `invoicing/invoice/application/usecase/ManageDraftInvoiceUseCase.java:48,59` -- resolve the client before `create` / `update`. Put the logic in a new `invoicing/invoice/application/usecase/InvoiceClientResolver.java` (`@Component`, uses `CapabilityRegistry`), which also serves the suggestions.
- `invoicing/invoice/domain/model/ClientInfo.java:9` -- record; trims fields to null.

**commerce**
- `commerce/package-info.java` -- add `"crm :: api"`.
- `commerce/sale/application/service/RecordSaleService.java:55-83` -- client check after the idempotency guard.
- `commerce/sale/adapter/in/rest/SaleController.java:40-86` -- unchanged; it shows the REST path.
- `commerce/sale/adapter/in/api/SaleCommandAdapter.java:44` -- the sync path into the same use case.

**sync**
- `sync/sync/application/handler/SaleSyncHandler.java:10,28,33,78-83` -- remove the `ClientQueryPort` check and its field.
- SPI files to move:
  - `sync/sync/domain/model/{SyncOperation,SyncOperationResult,SyncOperationStatus}.java`
  - `sync/sync/domain/port/in/SyncOperationHandler.java`
  - `sync/sync/application/handler/AbstractSyncOperationHandler.java`
  - All of them move to `shared/application/port/sync/`. Update the imports in about 64 main and test files.
- `sync/sync/application/handler/{CreateClient,Client,CreateSupplier,Supplier}SyncHandler.java` (and the matching tests under `src/test/java/com/keevo/sync/...`) → `crm/contact/adapter/in/sync/`.
- `sync/package-info.java:3` -- remove `"crm :: api"`.
- `sync/sync/application/service/SyncOperationHandlerRegistry.java` -- unchanged discovery.

**Other**
- `shared/domain/exception/ErrorCode.java:41` -- `CLIENT_NOT_FOUND`, which maps to 404 in `GlobalExceptionHandler.java:336`.
- Tests:
  - `src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java:97-109` -- blocking `verify`.
  - `KeevoArchitectureRulesTest.capabilities_neverInjected`.
  - `src/test/java/com/keevo/sync/.../SyncModuleGuardPostgresIntegrationTest.java:392-413` -- 19 handlers.
  - `ClasspathModuleManifestsTest.java:55-59` -- crm provides.

## Tasks & Acceptance

**Execution:**
- `crm/api/ClientSummary.java`, `ClientLookupPort.java`, `ClientLookupAdapter.java`, crm `module.json` -- add the summary find and search, bump to 1.2.0 -- picker data. Unit-test the adapter (archived excluded from search, limit clamp, blank query, wrong tenant → `IllegalStateException`).
- `shared/application/port/sync/*` + import rewrite + move the four crm handlers and their tests + `sync/package-info.java` -- relocate the SPI and the crm handlers -- remove the `sync → crm :: api` edge. Package moves only.
- `RecordSaleService.java` + `commerce/package-info.java` + `SaleSyncHandler.java` + pos `module.json` -- capability client check (404 / drop) -- fixes the 500. Tests:
  - service unit tests for capability present-known, present-unknown and absent (sale saved with null client);
  - a REST Postgres integration test proving 404 instead of 500;
  - the existing sync tests adapted.
- `InvoiceClientResolver.java`, `ManageDraftInvoiceUseCase.java`, `InvoiceController.java`, `InvoiceClientDto.java`, `invoicing/package-info.java` -- resolve/prefill and the suggestions endpoint -- MOD-FR9. Tests cover every invoice row of the I/O matrix (unit + controller/integration).
- `docs/entitlements/endpoints.md` -- regenerate (`-Dkeevo.entitlements.regenerate=true`).
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the three routed 14.3 items, with the decisions noted.

**Acceptance Criteria:**
- Given crm active, when the owner lists `/invoices/clients` and creates a draft with a chosen `client.id`, then the invoice stores the link with the CRM name and phone.
- Given crm DISABLED for the tenant, when a draft is created with or without `client.id`, then it succeeds with the free-text client and `/invoices/clients` reports `crmAvailable:false`.
- Given the build, when Modulith `verify` runs, then `sync` no longer depends on crm, invoicing and commerce use only `crm :: api`, and no capability is injected.
- Given an existing tenant and the dev DB, when the backend boots, then the catalogue updates crm and pos to 1.2.0 with no tenant DDL and no `tenant_modules` row change.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 24 findings — high 0, medium 2, low 13, false 6, maybe-false 0 (+3 descriptive intent-alignment notes counted below)
- findings:
  - `[low]` `[reject]` (intent) Story ACs live at the merchant UI; diff is API-only — the epics place the visible picker in 15.5 (UX-DR5 listed under Epic 15, Story 15.5 « sélecteur client CRM si actif »); 14.3 delivers the server contract. No harm in this story.
  - `[low]` `[reject]` (intent) Routed item C asked for every handler registered through KeevoModule; diff moves the 4 crm handlers via @Component — the item's own trigger was removing `sync → crm :: api`, which is done; the other handlers' `X :: api` edges are legitimate and moving them changes nothing observable (no named harm). Decision recorded in the epics doc.
  - `[false]` `[reject]` (intent) crm-off sale drop goes beyond « clean domain error » — the routed item explicitly asks to « decide the clientId policy when the capability is absent (reject vs drop) »; the decision is in the spec.
  - `[low]` `[reject]` (intent, verification-gap, blind) Blank client name answers 422 while the spec says 400 — 422 is the codebase convention for domain `VALIDATION_ERROR` (same as `Invoice.java:256` « Le nom du client est requis » in 14.1); the French reason is in `details.reason`; reaching 400 would need a new exception path. Not worth the complexity.
  - `[false]` `[reject]` (intent) No evidence Modulith verify ran — `architecture` package (ApplicationModulesBaselineTest, KeevoArchitectureRulesTest) run green by implementer and orchestrator (final-143.log).
  - `[low]` `[reject]` (edge) LIKE wildcards `%`/`_` not escaped in search — same pre-existing query as the gated CRM REST search; only widens results for a user who already sees all their clients.
  - `[low]` `[reject]` (edge, blind) Search loads every matching client then limits in memory — merchant client tables are small (dev max 5, shops typically < 1 000); pushing a Pageable needs a new repository method; no observed cost.
  - `[medium]` `[patch]` (edge, blind) Editing a CRM-linked draft while crm is off drops the stored link — fixed: `InvoiceClientResolver.resolve(typed, schema, storedClientId)` keeps the typed id when it equals the draft's stored one; tests `InvoiceClientResolverTest.crmOff_update_storedLinkKept_newOrDifferentIdDropped`, `InvoicingCrmIntegrationFullContextTest.crmOff_updateKeepsStoredLink`.
  - `[low]` `[reject]` (edge, blind) crm-off sale drops the client silently (device keeps it until next pull) — accepted trade-off recorded in Design Notes; the next pull realigns the device; surfacing a warning needs a new result field (public surface).
  - `[false]` `[reject]` (blind) `details.reason` breaks a field-keyed convention — `reason` is the convention for domain validation in invoicing/identity/admin (`Invoice.java:120,158,256`, `CompanyProfileService.java:130`).
  - `[low]` `[reject]` (blind) Archived CRM client can be linked via API although the picker hides it — consistent with `ClientLookupPort` including archived clients on purpose (past documents keep their client); only reachable by a hand-crafted request.
  - `[low]` `[reject]` (blind) Redundant archived filter / re-sort in `search` — harmless; keeps the port contract independent of the repository's current query.
  - `[low]` `[reject]` (blind) Three similar records (ClientSummary / ClientSuggestion / DTO) — layer separation used across the codebase (api record, use-case record, REST DTO); no divergence harm named.
  - `[low]` `[reject]` (blind) Duplicate `SyncTestAuth` helper — both copies are package-private fixtures for two packages (moved SPI tests and remaining sync handler tests); unifying needs a public test fixture; negligible.
  - `[low]` `[reject]` (blind) No READ_ONLY test for the consumers — READ_ONLY → provider returned is the CapabilityRegistry contract tested in 13.6; consumers only read.
  - `[low]` `[patch]` (blind) `RecordSaleCommand` rebuilt with 11 positional args — fixed: `RecordSaleCommand.withoutClient()` used by `withCheckedClient`.
  - `[false]` `[reject]` (blind) invoicing manifest not bumped — its manifest content did not change (version tracks manifest/migrations, not endpoints; crm/pos changed `provides`/`needs`).
  - `[low]` `[reject]` (blind) CRM lookup while the draft row is locked — a single primary-key read; the update needs the stored client id (patch above), so resolving after the lock is required.
  - `[low]` `[reject]` (blind) Spec verification points at a scratchpad script — fix would edit this build's spec; the per-package `mvn -Dtest='com.keevo.<pkg>.**'` command is spelled out next to it.
  - `[false]` `[reject]` (verification-gap) no gaps — informational.
  - `[medium]` `[patch]` grouped with the stored-link finding above (second reporter).
  - `[false]` `[reject]` (edge) duplicate of the crm-off sale drop finding — same claim, same verdict as above.
  - `[low]` `[reject]` (blind) `search` param has no max length — the value only feeds a parameterised LIKE; no harm named.
  - `[low]` `[reject]` (blind) sort order differs from DB ORDER BY — case-insensitive Java sort is the intended UI order.

## Design Notes

**Why drop the sale client when crm is off, rather than reject it:** a sale records money and stock, and an optional module must never block it (MOD-FR9 degraded mode). While crm is off the capability cannot vouch for the id, and the FK would turn an unverified id into a 500. The trade-off is that a sale made offline against a client whose creation was held loses its client link. That is accepted and logged.

**Why the SPI goes to `shared`:** a crm handler implementing a `sync :: api` interface would create a `crm → sync` edge, and `sync` orchestrates crm. `shared` already hosts cross-module SPIs (`ExportDatasetProvider`), so placing it there keeps the graph acyclic.

## Verification

**Commands** (from `keevo/backend`, one package at a time; `runpkg.sh` in the scratchpad):
- `mvn -q test -Dtest='com.keevo.<pkg>.**'` for each of invoicing, crm, commerce, sync, architecture, shared, then the remaining packages -- expected: 0 failures.
- `mvn -q -P prod-classpath verify -DskipTests` (or the CI equivalent used in 14.2) -- expected: rc 0.

**Manual checks:**
- Live on dev (WhatsApp provider noop), tenant `kv_zggt7g`:
  - `/invoices/clients` with crm on, then off (via `tenant_modules` crm DISABLED), then restored;
  - draft with a CRM client;
  - `POST /sales` with a random clientId → 404;
  - `tenant_modules` row count unchanged apart from the toggle (restored).

## Auto Run Result

Status: done

**Summary.** Invoicing consumes the optional `ClientLookupPort` capability (crm 1.2.0, new `find`/`search` + `ClientSummary`) per call through `CapabilityRegistry.find`: `GET /api/v1/invoices/clients` (picker data, `crmAvailable:false` when crm is off), CRM link + name/phone prefill on draft create/update, unknown id → 404 `CLIENT_NOT_FOUND`, crm off → free text (a link already stored on the draft is kept). Sales check their client through the same capability (REST and sync): unknown → 404 / REJECTED instead of the FK 500; crm off → sale recorded without client. The sync handler SPI moved to `shared.application.port.sync`, the four client/supplier handlers moved to `crm.contact.adapter.in.sync`, and `sync` no longer depends on `crm`.

**Files.** crm: `api/ClientLookupPort`, `api/ClientSummary` (new), `contact/adapter/in/api/ClientLookupAdapter`, `contact/adapter/in/sync/*` (moved), manifest 1.2.0. invoicing: `package-info`, `InvoiceClientResolver` (new), `ManageDraftInvoiceUseCase`, `InvoiceController`, `InvoiceClientDto`, `InvoiceClientSuggestionsDto` (new), `ClientInfo` (doc). commerce: `package-info`, `RecordSaleService`, `RecordSaleUseCase.RecordSaleCommand.withoutClient()`; pos manifest 1.2.0 + `needs`. sync: SPI files moved to `shared/application/port/sync`, `SaleSyncHandler` without `ClientQueryPort`, `package-info` without crm; ~60 import rewrites. Docs: `docs/entitlements/endpoints.md` regenerated; epics doc routed items ticked.

**Review.** 24 findings: 2 patched (medium: stored CRM link dropped on draft edit while crm off; low: positional command rebuild), 0 deferred, 22 rejected (reasons in the triage log). Follow-up review recommended: false (one medium patched, no high).

**Verification.** Per package after patches: invoicing 78, commerce 259, crm 158, sync 493, architecture 32, shared 1 027 — 0 failures; before patches all 15 packages green (inventory 376, identity 446, admin 517, reporting 403, catalog 247, store 60, messaging 168, subscription 70, feedback 52) and prod-classpath rc 0. Live (dev, noop WhatsApp, kv_zggt7g): boot seeded `updated=[crm, pos]` to 1.2.0, `tenant_modules` 331 rows before/after; suggestions returned 5 clients sorted; draft with CRM id and blank name → « Alice Mbarga » + phone; unknown id → 404; blank name → 422 « Le nom du client est obligatoire »; `POST /sales` unknown client → 404 « Client introuvable », no row written; crm DISABLED → suggestions `crmAvailable:false`, draft keeps typed text with id dropped, sale 201 with `client_id` null; crm row restored ACTIVE.

**Residual risks.** A sale made while crm is off loses its client link (accepted, logged). Blank client name answers 422 (codebase convention) where the spec text said 400. Dev data: one 100 XAF test sale and two draft invoices added to kv_zggt7g.
