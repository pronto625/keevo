---
title: '20-6 Derive CRM clients and catalog products from issued documents; client list keyset pagination and trigram search'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: 'e1862565157a106d3d695924d98bf7c5e19404ee'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-5-remise-livraison-tva-echeance.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-2-crm-etendu-clients-par-etablissement.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** In Bref, issuing a sale (or validating a quote) automatically records the customer (by shop + phone) and the products (by product key) without any prior data entry (FUS-FR16). In Keevo an invoice only links a CRM client the user picked by id, and lines never create catalog products, so Bref merchants would lose this behaviour. Separately (routed from the 20.2 review), the client list/search is unbounded and searches with `LIKE '%q%'` without a usable index.

**Approach:** At the single issue choke point (`InvoiceLifecycleUseCase.issue(..., counted=true)` — user issue, sync issue, quote conversion; never the POS bridge), when `crm`/`catalog` are active and the tenant preference `derive_from_documents` resolves to true, rewrite the locked draft before issuing: link (find or create) the client by normalised phone in the actor's establishment via `ClientLookupPort`, and link (find or create, `track_stock=false`) each unlinked line's product via a new catalog capability `ProductLookupPort` — set-based, same transaction, never failing the invoice. Add opt-in keyset pagination and pg_trgm indexes to `GET /clients`.

## Boundaries & Constraints

**Always:**
- **When derivation runs:** only in `InvoiceLifecycleUseCase` private `issue` with `counted == true`, after the locked draft read and before `assertIssuable`, on `kind = INVOICE` drafts. Covers REST `POST /invoices/{id}/issue`, sync `ISSUE_INVOICE`, quote conversion (`ConvertQuoteUseCase` → counted issue). Never on draft create/update, on quotes themselves, on the POS sale bridge (`counted=false`), on credit notes or imports.
- **Preference:** new nullable column `invoicing_settings.derive_from_documents` (invoicing `V13`, additive, `SET LOCAL lock_timeout`). Resolved value = explicit value if not null, else `true` when the tenant `default_sale_flow` is `DOCUMENT` and `false` otherwise (no preferences row → false). Read the sale flow through `identity/api/TenantSaleFlowQueryPort` (add `"identity :: api"` to invoicing `allowedDependencies`; identity does not depend on invoicing). `GET /api/v1/invoicing/settings` returns the resolved `deriveFromDocuments` boolean; `PUT` accepts an optional `deriveFromDocuments` (absent → stored value unchanged, so V1 clients that do not send it change nothing).
- **Module autonomy:** client derivation only if `CapabilityRegistry.find(ClientLookupPort.class, schema)` is present AND `EntitlementService.isEnabled(schema, "crm")` (READ_ONLY → skip); product derivation likewise with `ProductLookupPort` / `"catalog"`. Either module off → that half is skipped, the invoice is issued normally.
- **Never fails the invoice:** derivation problems (unparseable phone, member without store, product plan limit reached, blank description) skip that item silently (log at debug, no exception). No exception may cross a `@Transactional` proxy inside derivation (it would mark the whole transaction rollback-only): ports return `Optional`/maps, they check limits with queries, not by catching thrown business exceptions.
- **Client rule:** only when the draft's `ClientInfo.clientId` is null and its phone normalises with `PhoneNumbers.toE164(phone, CAMEROON)`. Store = actor's establishment: OWNER → `ClientStoreScope.storeForNewClient(SYSTEM, null)` (oldest active store, may be null); member → assigned store; member without store → skip. Match = non-archived client with the same `phone_normalized` and `store_id` = that store, else with `store_id IS NULL`; several → oldest `created_at`, then smallest id. Found → link it and fill only its NULL `email`/`rccm`/`niu` from the invoice (COALESCE, Bref semantics; never overwrite name or other fields). Not found → create it (name, phone, email, rccm, niu, address from the invoice snapshot, the store above, marketing consent untouched) through the existing creation rules (`Client` record validation, `ClientCreatedEvent` with the issuing actor so audit and pull see it). Serialise concurrent issues for the same phone with `pg_advisory_xact_lock` on (schema, store, phone). The invoice keeps its typed client snapshot; only `clientId` is set.
- **Product rule:** for every line with `productId == null` and a non-blank description: key = `ProductKeys.key(description, reference)`. Resolve all lines in one call: one SELECT by `product_key = ANY(:keys)` OR `lower(name) = ANY(:names)` (name uniqueness `uq_products_name` exists), follow `merged_into_id` (one hop, as `LookupProductByBarcodeUseCase`); deduplicate identical keys within the invoice. Found → link; if it is not archived, `price_manual = false` and its price differs, set price = line unit price (Bref: last sale price unless manual). Not found → create in batch: name = description (trimmed, column length), `product_key`, `barcode` = reference when `ProductKeys.isBarcodeLike`, price = line unit price as stored on the line (the tenant's price mode), `track_stock = false`, `price_manual = false`, no category, auto SKU, plan product limit checked by count first (over the limit → the remaining lines stay unlinked), creation events as the normal product creation path. Take the existing per-tenant catalog advisory lock once per invoice.
- **Linking:** rebuild the draft with `Invoice.withDraftContent(...)` (new `LineInput`s carrying `productId`, `ClientInfo` with `clientId`), persist it, then issue as today; totals must be unchanged by derivation (asserted in tests).
- **Capability:** `catalog/api/ProductLookupPort extends Capability`, provided by catalog via `CapabilityRegistrar.provide` (or `@ProvidesCapability`), declared `provides: ["ProductLookupPort"]` in `keevo/modules/catalog/module.json`; invoicing manifest `needs` gains `"ProductLookupPort?"`, `enhances` gains `catalog`. `ClientLookupPort` gains one method for find-or-create (resolved through the registry, never injected — ArchUnit `CAPABILITY_INJECTION`).
- **Client list pagination (additive):** `GET /api/v1/clients` gains optional `limit` (1–100) and `cursor`. Without `limit` → exactly today's unbounded list and order (V1 apps). With `limit` → ordered by `(lower(name), id)`, keyset `WHERE (lower(name), id) > (:n, :id)`, `limit + 1` fetched, `meta.nextCursor` = opaque base64url token (absent on the last page); invalid cursor → 400 `VALIDATION_ERROR` « Curseur de pagination invalide ». Search (`search=`) works with and without `limit`, in the same store scope as today.
- **Trigram indexes:** public Flyway `V55__pg_trgm_extension.sql`: `CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public`. crm module `V3__clients_search_indexes.sql` (`SET LOCAL lock_timeout`): GIN `lower(name) public.gin_trgm_ops`, GIN `phone public.gin_trgm_ops`, btree `(lower(name), id)`. Search query rewritten to `lower(name) LIKE lower(:pattern)` / `phone LIKE :pattern` so the indexes apply (schema-qualify the opclass: tenant scripts run without `public` in search_path).

**Never:**
- No derivation on POS-bridged invoices, quotes, drafts, credit notes or imports; no auto-merge of clients; no overwriting existing client names or product names; no stock movement (derived products are untracked).
- No cross-module repository access: invoicing talks to crm/catalog only through their `api` capabilities.
- No customer/product sales statistics columns (Bref's `totalInvoices`, `timesSold`…): Keevo reporting computes them (20.9).
- No change to existing responses other than additive fields; no new sync operation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| New client | DOCUMENT tenant, crm on, draft client « Awa » 699 00 00 01, no clientId | Issued; new client in owner's oldest store, invoice `client.clientId` = it | — |
| Known client | Same phone exists in store with null email | Linked to it; email filled; name kept | — |
| Bad phone | Phone « 12 » | Issued, no client link | Skipped silently |
| Member without store | Employee with no assigned store issues | Issued, no client link | Skipped |
| Products | Lines « Riz 25kg » (no ref) and « Huile » ref `6001234567890` | Products `nm:riz 25kg` and `bc:6001234567890` found or created untracked at line price; lines linked | — |
| Merged product | Key matches a product merged into P2 | Line linked to P2 | — |
| Plan limit | Product limit reached | Issued; new lines unlinked, existing matches linked | Skipped |
| Pref off / POS tenant | `derive_from_documents` null + `POS` flow | No derivation | — |
| Module off | `catalog` disabled | Client derived, products not | — |
| POS bridge | Sale completed → automatic invoice | No derivation | — |
| Quote conversion | Quote with unlinked client and lines → convert | Issued invoice linked (derivation runs) | — |
| Paged clients | `GET /clients?limit=2` then `cursor=` | Pages of 2 by name, `nextCursor` until last | Bad cursor → 400 |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/java/com/keevo/` unless stated.
- `invoicing/invoice/application/usecase/InvoiceLifecycleUseCase.java` -- public `issue` L72-75 (counted) and L82-85 (bridge, `counted=false`); private `issue(id, actor, tenant, pricesIncludeVat, counted)` L87-112: `findByIdForUpdate` L88, `assertIssuable` L100, quota L102-104, `draft.issue` L105, `invoices.issue` L108, `InvoiceIssuedEvent` L109; `withPricesIncludingVat`/`withLegalName` L115-125 copy `InvoicingSettings` fully (add the new field).
- `invoicing/invoice/application/usecase/InvoiceClientResolver.java` L60-86 -- existing pattern for resolving `ClientLookupPort` per call via `capabilities.find`; put the new derivation in a sibling `DocumentDerivationService` (application service) called from the lifecycle use case.
- `invoicing/invoice/application/usecase/ConvertQuoteUseCase.java` L81-122 -- conversion → counted issue L116 (no change expected; covered by test).
- `invoicing/invoice/adapter/in/sync/InvoiceSyncHandler.java` L153-179 -- sync paths reuse the use cases (no change).
- `invoicing/invoice/domain/model/Invoice.java` -- `withDraftContent(ClientInfo, List<LineInput>, InvoiceTerms, InvoicingSettings, Instant)` L158, `issue` L313; `ClientInfo.java` L12 (`clientId`, name, niu, address, phone, rccm, email, contactName, website), `resolved` L41; `LineInput.java` L57 (`description, quantity, unitPrice, treatment, productId, reference`), `of(InvoiceLine)` L96; `InvoicingSettings.java` record.
- `invoicing/invoice/adapter/out/persistence/JdbcInvoicingSettingsRepository.java`, `adapter/in/rest/InvoicingSettingsController.java` (GET L35, PUT L42), `adapter/in/rest/dto/InvoicingSettingsDto.java` (`toDomain`, `of`), `application/usecase/InvoicingSettingsUseCase.java` L34/L39.
- `invoicing/package-info.java` -- `allowedDependencies = {"shared","crm :: api","store :: api"}` → add `"catalog :: api"`, `"identity :: api"`.
- `identity/api/TenantSaleFlowQueryPort.java` -- `Optional<TenantSaleFlowView(SaleFlow saleFlow, String sectorType)> find(String tenantSchema)`.
- `shared/module/CapabilityRegistry.java` (`find`, `has`), `shared/module/EntitlementService.java` (`isEnabled(schema, moduleId)` L25; used in `SaleInvoicingListener` L61/L66).
- `crm/api/ClientLookupPort.java` L19 (`displayName`, `exists`, `find`, `search`), `crm/contact/adapter/in/api/ClientLookupAdapter.java` L26 (not a bean; built in `crm/module/CrmModule.java` L45-46), `ClientSummary`.
- `crm/contact/application/usecase/CreateClientUseCase.java` L50 (command), L82 `execute`; `crm/contact/domain/entity/Client.java` L34-46 (phone validation, `PhoneNumbers.CAMEROON`); `crm/contact/application/service/ClientStoreScope.java` `storeForNewClient` L62; `ClientAccess.java` (`SYSTEM`, `member`); `crm/contact/adapter/in/CurrentClientAccess.java` (actor → access, usable in REST and sync threads); `shared/domain/PhoneNumbers.java` L34.
- `crm/contact/adapter/out/persistence/ClientSpringRepository.java` L24-39 (`findAllFiltered`, `searchByNameOrPhone`, `findAllScoped`, `searchScoped`); `crm/contact/adapter/in/web/ClientController.java` L84-96 (`GET /clients`, `ApiResponseWrapper.ok(data, meta)` available); `crm/contact/application/usecase/GetClientsUseCase.java` L32-47.
- `catalog/product/domain/service/ProductKeys.java` (`key`, `normalizeName`, `isBarcodeLike`, `cleanBarcode`); `catalog/product/application/usecase/LookupProductByBarcodeUseCase.java` (merge hop); `catalog/product/application/usecase/CreateProductUseCase.java` L127-170 (advisory lock, `planLimit.checkProductLimit` L168, `priceManual = price != null`, auto SKU, events); `catalog/api/ProductQueryPort.java`, `catalog/api/ProductCommandPort.java` (beans, not capabilities); `catalog/package-info.java`.
- Manifests (`src/main/resources/keevo/modules/`): `crm/module.json` (`provides: ["ClientLookupPort"]`), `catalog/module.json` (`provides: []`), `invoicing/module.json` (`needs: ["ClientLookupPort?"]`, `enhances: [pos, crm]`); validated by `CapabilityRegistryService.checkManifests` L328.
- DDL (`src/main/resources/db/`): `modules/invoicing/V1__invoicing.sql` L18-32 (`invoicing_settings`), latest `V12__invoices_due_date_index.sql`; `modules/crm/V1__crm_baseline.sql` L101-104, `V2__clients_bref_fields_store.sql` L139-140 (`idx_clients_store_phone`); `modules/catalog/V1` L84 (`uq_products_name`), `V2__products_bref_fields.sql` L48 (`idx_products_product_key`, partial, non-unique); `modules/core/V3__tenant_preferences_sale_flow.sql`; public `migration/` latest `V54`.
- Tests: `src/test/java/com/keevo/architecture/KeevoArchitectureRules.java` L60-150 (`CAPABILITY_INJECTION`), `ApplicationModulesBaselineTest` L40, `TenantSchemaInterpolationGuardTest` (allowlist for new SQL builders if flagged).
- Bref reference (read-only): `api-bref/src/invoice/invoice.service.ts` L157-185, L277-325 (`applySaleSideEffects`: at sale creation or quote validation, not for quotes); `customer/.../customer.repository.ts` L24-90 (upsert on (shop, phone), COALESCE email/nui/rccm); `product/.../product.repository.ts` L50-125 (key lookup, merge redirect, price kept when `priceManual`).

## Tasks & Acceptance

**Execution:**
- `src/main/resources/db/modules/invoicing/V13__invoicing_settings_derive_from_documents.sql` -- nullable boolean column -- preference storage.
- `invoicing/invoice/domain/model/InvoicingSettings.java`, `JdbcInvoicingSettingsRepository`, `InvoicingSettingsDto`, `InvoicingSettingsUseCase`, `InvoiceLifecycleUseCase` copies -- carry `deriveFromDocuments` (nullable stored, resolved in responses) -- preference API.
- `catalog/api/ProductLookupPort.java` + `catalog/product/application/usecase/DeriveProductsFromDocumentUseCase.java` + adapter + registration + `catalog/module.json` -- set-based resolve-or-create for a list of `(description, reference, unitPrice)` returning a map key → productId -- product derivation.
- `crm/api/ClientLookupPort.java` + `ClientLookupAdapter` + a crm use case (e.g. `DeriveClientFromDocumentUseCase`) -- find-or-create by normalised phone in the actor's store with advisory lock, COALESCE fill -- client derivation.
- `invoicing/invoice/application/usecase/DocumentDerivationService.java` + hook in `InvoiceLifecycleUseCase` private `issue` (counted only) + `invoicing/package-info.java` + `invoicing/module.json` -- orchestrate gates, rebuild and save the draft -- single choke point.
- `crm/...ClientSpringRepository`, `GetClientsUseCase`, `ClientController` + `migration/V55__pg_trgm_extension.sql` + `modules/crm/V3__clients_search_indexes.sql` -- keyset page + trigram search -- routed 20.2 item.
- Tests: `DocumentDerivationServiceTest` (each I/O row, totals unchanged, gates), crm derivation test (store choice, null-store match, COALESCE, bad phone, member without store), catalog derivation test (key, name, merge hop, dedupe, price update rules, plan limit, one SELECT for N lines), Testcontainers IT for client keyset pages + search on trigram query, `InvoiceLifecycleUseCase` test that the bridge path never derives, settings DTO test (absent field unchanged), architecture/Modulith tests green.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` + epics doc routed checkbox -- mark the 20.2-routed item done; add `m20-6-derivation-catalogue-crm` to the MOBILE block (finalize).
- This spec -- append `## Mobile (React Native — dépôt AI/bref)` (finalize).

**Acceptance Criteria:**
- Given a DOCUMENT tenant with crm and catalog active and no explicit preference, when a draft with an unknown client phone and two unlinked lines is issued over REST, then the response carries the new client's id and both lines' product ids, the products are untracked with the line prices, and the totals equal those of the draft.
- Given the same tenant and a second invoice for the same phone and same line description, when it is issued, then no new client or product is created and both are linked to the existing ones.
- Given a POS tenant (or `deriveFromDocuments=false`, or crm/catalog disabled), when an invoice is issued, then nothing is derived for the disabled part and the invoice is issued normally.
- Given a completed POS sale, when its automatic invoice is created, then no client or product is created by derivation.
- Given 120 clients, when `GET /clients?limit=50` is followed through `nextCursor`, then 120 distinct clients come back in name order over 3 pages; when `GET /clients` is called without `limit`, then the response is the same as before the change.
- Given the full backend suite run package by package, when the build runs, then it passes, including architecture, Modulith and manifest checks.

## Spec Change Log

## Review Triage Log

### 2026-11-07 — Review pass
- verdicts: 38 findings — high 0, medium 8, low 17, false 9, maybe-false 4
- findings:
  - `[false]` `[reject]` Intent: per-module gating instead of "no derivation at all" when crm or catalog is off — the epic context states "no derivation when crm/catalog inactive (module autonomy)"; each half depends only on its own module, the invoice stays valid either way (spec Always: Module autonomy).
  - `[false]` `[reject]` Intent: a client without usable phone is never created — Bref upserts on (shop, phone) only and skips a phone-less customer (invoice.service.ts L284); same behaviour.
  - `[medium]` `[patch]` Intent/verification: atomicity of derivation with a refused issue never exercised — checks now run before derivation (InvoiceLifecycleUseCase) and a full-context test asserts a refused issue creates nothing.
  - `[low]` `[defer]` Intent/patterns: `GET /clients` without `limit` stays unbounded for V1 apps — routed to 25-3 (remove transitional code once V1 apps are retired).
  - `[low]` `[reject]` Blind: a technical DB error inside derivation fails the issue — the issue then rolls back atomically (nothing half-written) and can be retried; isolating with savepoints would add complexity for a fault that signals a bug.
  - `[low]` `[patch]` Blind: derivation use cases used REQUIRED propagation — now MANDATORY (must join the issue transaction).
  - `[false]` `[reject]` Blind: derived products notify the owner "employee created products" — the derived ProductCreatedEvent carries actorRole null and ProductCreationNotificationListener skips non-member roles (L97).
  - `[low]` `[reject]` Blind: automatic price updates publish no event — `updated_at` is bumped so the pull carries the new price; Bref updates silently too.
  - `[medium]` `[patch]` Blind: a line priced 0 reset a catalogue price — price updates now require a line price > 0.
  - `[maybe-false]` `[reject]` Blind: HT line price copied into a TTC catalogue — the tenant's catalogue and invoices share one price mode (prices_include_vat); would need a mixed-mode tenant to matter (low if true).
  - `[false]` `[reject]` Blind: owner's client lands in oldest store, not the invoice's store — invoices carry no store; spec rule (owner → oldest active store, owner sees all stores).
  - `[medium]` `[patch]` Blind: cheap checks ran after derivation (refused issues took locks and wrote rows) — assertIssuable/due date/quota now run on the locked draft first.
  - `[low]` `[patch]` Blind: rows dropped by ON CONFLICT DO NOTHING were not logged — debug log added.
  - `[medium]` `[patch]` Blind/patterns/edge: user `%`/`_` not escaped in LIKE search — containsPattern escapes `\`, `%`, `_` (PostgreSQL default escape).
  - `[medium]` `[patch]` Blind: test gaps (concurrency, sync issue, merge/archived) — sync issue, merge, name match and archived tests added (concurrency race not unit-testable cheaply; advisory locks reviewed).
  - `[low]` `[patch]` Blind: debug log counted products, not lines — fixed.
  - `[low]` `[reject]` Blind: names cut at 200 chars collapse long descriptions — the column limit; two 200-char-identical descriptions are the same product for a merchant.
  - `[low]` `[reject]` Blind/edge: the preference cannot be reset to "not chosen" — an explicit true/false covers every need; unlikely and adds a tri-state API.
  - `[medium]` `[patch]` Patterns: the tenant catalogue lock was taken on every issue with free lines — now only when a product is missing, with a re-match under the lock.
  - `[low]` `[patch]` Patterns: ClientLookupAdapter.search loaded every client — now one bounded findPage query.
  - `[low]` `[reject]` Patterns: member keyset page may scan many rows — acceptable at current volumes; UNION rewrite only if measured.
  - `[low]` `[reject]` Patterns: 1–2 char trigram search falls back to a scan — bounded by `limit`; not worth a minimum length rule.
  - `[low]` `[reject]` Patterns: product count once per creating issue — runs only when products are missing.
  - `[low]` `[reject]` Patterns: sync batch of N issues costs N × constant — inherent to issuing invoices one by one.
  - `[false]` `[reject]` Patterns: no cache to invalidate — informational.
  - `[medium]` `[patch]` Verification: paged list store-visibility filter untested — member-token test added.
  - `[low]` `[patch]` Verification: paged list archive filter untested — test added.
  - `[medium]` `[patch]` Verification: name-match and merge-target SQL never run on PostgreSQL — full-context cases added.
  - `[low]` `[patch]` Verification: archived product price unchanged untested — unit case added.
  - `[low]` `[patch]` Verification: member store of derived client untested end to end — member-token full-context test added.
  - `[low]` `[reject]` Edge: a DRAFT-status product can be matched — linking a line to a draft product is harmless; it is still the merchant's product.
  - `[false]` `[reject]` Edge: archived match gets linked — intended (Bref links regardless); its price is never touched.
  - `[maybe-false]` `[reject]` Edge: plan query failing inside its proxy marks rollback — would need a failing plan read; such a failure already fails manual creations (low if true).
  - `[maybe-false]` `[reject]` Edge: member's assigned store deactivated — same rule as a manual client creation by that member (low if true).
  - `[low]` `[patch]` Edge: client name > 200 chars skipped the client — now cut to 200.
  - `[false]` `[reject]` Edge: one-arg ClientRepositoryAdapter constructor NPE — Spring uses the @Autowired two-arg constructor; the one-arg form is test-only.
  - `[maybe-false]` `[patch]` Edge: pg_trgm installed in another schema breaks `public.gin_trgm_ops` — V3 now builds the trigram indexes only when the extension lives in public.
  - `[false]` `[patch]` Edge: concurrent CREATE EXTENSION raises unique_violation — added to the handled exceptions (harmless hardening).

## Design Notes

**Pattern:** *Application service orchestrating optional capabilities* (Hexagonal ports + Capability registry, the 13.x module-autonomy pattern): invoicing owns the "when" (choke point, gates, rebuild), crm and catalog own the "how" behind `Capability` ports, each half independently skippable. A synchronous event listener was rejected: `InvoiceIssuedEvent` fires after the lines are frozen, and linking must happen before issue in the same transaction.

**Cost per issue:** client = 1 advisory lock + 1 indexed SELECT on `idx_clients_store_phone` (+1 INSERT or 1 UPDATE). Products = 1 advisory lock + 1 SELECT for all lines (`idx_products_product_key`, `uq_products_name`) + 1 merge-target SELECT if needed + 1 batch INSERT + 1 batch UPDATE of prices + 1 count for the plan limit — constant number of queries whatever the line count (no per-line query). Draft rewrite = 1 UPDATE + line rewrite already used by draft updates. Client list with `limit` = 1 query on `(lower(name), id)` btree, O(limit); search uses GIN trigram indexes instead of sequential scans.

**Preference resolution example:** `stored=null, flow=DOCUMENT → true`; `stored=null, flow=POS → false`; `stored=false, flow=DOCUMENT → false`.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` then crm, catalog, architecture packages -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false --keevo.push.expo.enabled=false`), test tenant KV-EW02A2 with invoicing activated and `deriveFromDocuments=true` for the check, then preference and modules restored -- expected: issued invoice linked to a derived client and derived untracked products; `GET /clients?limit=` pages correctly.

## Auto Run Result

**Summary:** At a user's issue (REST issue, sync `ISSUE_INVOICE`, quote conversion — never the POS bridge), when the tenant preference `deriveFromDocuments` resolves to true (explicit choice, else on for a DOCUMENT sale flow), the invoice's client is found by normalised phone in the actor's store (or legacy no-store client) or created, and each free-text line is linked to a catalogue product found by key / name (following merges) or created untracked at the line price — set-based, in the issue transaction, after the issue checks, never failing the invoice. `GET /clients` gains opt-in keyset pagination; client search is escaped and served by pg_trgm GIN indexes; the invoicing client picker no longer loads every client.

**Files (backend):** invoicing V13 (`derive_from_documents`), public V55 (`pg_trgm`), crm V3 (trigram + keyset indexes); `invoicing/.../DocumentDerivationService`, `DeriveFromDocumentsPreference`, `InvoiceLifecycleUseCase` (hook after checks), settings record/DTO/repository/use case, `package-info` (+ `catalog :: api`, `identity :: api`), manifests; `crm/api/ClientLookupPort` (+ `findOrCreateForDocument`), `DocumentClientInput`, `DeriveClientFromDocumentUseCase`, `ClientLookupAdapter` (bounded search), `ClientRepositoryAdapter.findPage`/escaping, `ClientSpringRepository` (`ESCAPE '\'`), `GetClientsUseCase.executePage`, `ClientController`; `catalog/api/ProductLookupPort`, `DeriveProductsFromDocumentUseCase`, `JpaProductDerivationRepository`, `ProductLookupAdapter`; tests (unit, full-context, Postgres IT, sync).

**Review:** 38 findings — 20 patched (8 medium, 11 low/maybe-false/false hardening), 1 deferred → 25-3 (`GET /clients` without `limit` unbounded for V1), 17 rejected (reasons in the triage log). One real bug found by the new tests and fixed: Hibernate 6.6 emits `escape ''` on JPQL LIKE, so the escaped search needed an explicit `ESCAPE '\'`.

**Follow-up review:** recommended (`true`) — 8 medium entries patched, including the lock restructuring (two-pass match) whose concurrent behaviour is covered only by a mocked second pass, not by a real two-transaction race.

**Verification:** touched packages run one by one — invoicing 324, crm 237, catalog 371, architecture 122, shared 1 320, sync 675, commerce 268: 0 failures. Live on keevo_dev (WhatsApp noop, FCM/Expo off), test tenant with invoicing activated and `deriveFromDocuments=true` for the check then restored (preference reset to unset, invoicing deactivated): `FAC-2026-000005` issued with client and both lines linked, a second identical invoice reused the same client and products, derived product untracked, paged list = unpaged list (3 clients over 2 pages), bad cursor → 400 « Curseur de pagination invalide », search « _ » → 0.

**Residual risks:** advisory-lock races verified by design and mocks, not by a concurrent IT; on a database refusing `pg_trgm` the trigram indexes are skipped (search still correct, slower); price updates of derived products are not audited (Bref parity).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit 79f032c2).

**Ce qui est fait côté API**
- À l'émission d'une facture (`POST /api/v1/invoices/{id}/issue`, sync `ISSUE_INVOICE`, conversion de devis), si la préférence est active : le client est retrouvé par téléphone dans l'établissement de l'utilisateur ou créé ; chaque ligne sans produit est liée à un produit du catalogue retrouvé (clé, nom, produit fusionné) ou créé sans suivi de stock au prix de la ligne. Rien ne change pour les ventes caisse ni pour les brouillons et devis.
- Préférence `deriveFromDocuments` dans les réglages de facturation.
- Liste clients paginée (optionnelle) et recherche rapide.

**À utiliser**
- `GET /api/v1/invoicing/settings` → `data.deriveFromDocuments` (booléen résolu : choix du propriétaire, sinon vrai en mode Document, faux en mode Caisse).
- `PUT /api/v1/invoicing/settings` avec `"deriveFromDocuments": true|false` (champ facultatif : absent = valeur inchangée). Code : `invoicing/invoice/adapter/in/rest/dto/InvoicingSettingsDto.java`.
- Réponse de l'émission (`InvoiceResponseDto`) : `client.clientId` et `lines[].productId` sont désormais remplis quand la dérivation a eu lieu (champs existants). Les nouveaux clients et produits arrivent aussi par le pull (`clients`, `products`) comme toute création.
- `GET /api/v1/clients?limit=50[&cursor=…][&search=…][&storeId=…][&includeArchived=true]` → `data` (une page triée par nom), `meta.nextCursor` (à renvoyer tel quel dans `cursor`, absent sur la dernière page). Sans `limit` : la liste complète comme avant. Erreurs : 400 `VALIDATION_ERROR` « La taille de page doit être comprise entre 1 et 100 » (limit hors bornes), 400 `VALIDATION_ERROR` « Curseur de pagination invalide » (recharger depuis la première page). Code : `crm/contact/adapter/in/web/ClientController.java` `listClients`.

**À implémenter côté mobile**
1. Écran réglages facturation : interrupteur « Créer automatiquement clients et articles depuis mes factures » lié à `deriveFromDocuments`.
2. Après émission, rafraîchir la fiche facture (client lié, articles liés) ; ne rien afficher de technique.
3. Liste clients : passer à la pagination `limit`/`cursor` (défilement infini, recherche avec délai de saisie) ; hors ligne, garder la liste locale issue du pull.
4. Ne jamais créer côté app un client ou un article « en double » pour imiter ce comportement : le serveur s'en charge à l'émission.

**Implémentation de référence :** aucune (keevo/app non modifiée).

**Critères d'acceptation mobile / scénarios de test**
- Given un tenant en mode Document, réglage par défaut, When j'émets une facture pour « Awa » 699 00 00 01 avec une ligne « Riz 25kg » 15 000, Then la facture émise montre le client lié et l'article lié, et « Awa » et « Riz 25kg » apparaissent dans les listes après le pull.
- Given le réglage désactivé, When j'émets la même facture, Then aucun client ni article n'est créé.
- Given 120 clients, When je fais défiler la liste paginée par 50, Then 120 clients distincts s'affichent sans doublon.
- Setup curl : `curl -H "Authorization: Bearer $T" "$API/api/v1/clients?limit=2"` puis `…&cursor=<meta.nextCursor>`.

**Clé de suivi mobile :** `m20-6-derivation-catalogue-crm`.
