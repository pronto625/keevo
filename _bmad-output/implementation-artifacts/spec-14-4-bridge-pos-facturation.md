---
title: '14.4 — POS → Invoicing bridge: an invoice issued and paid automatically for each completed sale'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '9a2b86d47b137a997ebe96be755f6d345370c2a5'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-1-module-facturation-autonome.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.4 (MOD-FR8). When POS and Invoicing are both active, each completed sale must produce its invoice automatically and idempotently: a replay of the event never creates a duplicate. With POS off, nothing is created automatically, manual invoicing goes on, and existing invoices are kept. Today `SaleCompletedEvent` carries no client, no payment mode and no typed lines. Invoicing (which depends on `shared` + `crm :: api` only) therefore cannot build an invoice from it, and nothing links an invoice to a sale.

**Approach:**
- Enrich `SaleCompletedEvent` with what an invoice needs.
- Add an `@ApplicationModuleListener` in `invoicing` that creates, issues and marks paid an invoice in one transaction, keyed on the sale id.
- Expose the sale link on the invoice.

**Decisions (autonomous run; the user delegated all choices on 2026-10-28):**
- **Event**:
  - `SaleCompletedEvent` gains:
    - `UUID clientId` (nullable);
    - `String paymentMode` (`CASH` | `MOBILE_MONEY`);
    - `List<SaleCompletedEvent.Line> lines`, where `Line(String productName, long unitPrice, int quantity)` and `unitPrice` is the applied TTC unit price.
  - Its three publishers fill the new fields: `RecordSaleService`, `SaleValidationCascadeService` and `ValidateSaleService`. `itemsSnapshot` and every existing field are unchanged.
  - Deserialisation of events already stored in `public.event_publication` without the new fields must succeed, with null fields.
  - The pos manifest gets `publishes: ["sale.completed"]` (version 1.3.0).
- **Listener** `invoicing/…/adapter/in/event/SaleInvoicingListener`:
  - It is `@ApplicationModuleListener` + `@ModuleScoped("invoicing")`. The tenant is bound by the existing interceptor.
  - It returns without doing anything when invoicing is not ENABLED (`EntitlementService.isEnabled(schema, "invoicing")`; READ_ONLY and DISABLED are logged at INFO).
  - It returns with a WARN when `lines` is null or empty (old events).
  - It calls `ModuleProvisioner.ensureProvisioned(schema, "invoicing")` before any write.
  - Delegate to a use case `invoicing/…/application/usecase/InvoiceSaleUseCase` (`@Transactional`). In one transaction it:
    1. does nothing if an invoice already has `source_sale_id = saleId`;
    2. creates the draft;
    3. issues it;
    4. pays it.
  - A unique violation on `source_sale_id` (a concurrent replay) is caught and treated as already done. The counter increment rolls back with it, so numbering stays gapless.
  - Any other failure propagates, so the publication stays incomplete and the registry retries it. The sale itself is never affected (the listener runs after commit).
- **Mapping**:
  - **Client**: with `clientId` and the `ClientLookupPort` present and the client found, use the CRM name, phone and id. Otherwise the client is « Client comptoir » (no id).
  - **Lines**: description = `productName`, quantity = `quantity`, unit price = `unitPrice`, treatment STANDARD.
  - **Prices are always VAT-inclusive** for an auto invoice, whatever the setting, because they are what the client paid. The invoice snapshot carries `pricesIncludeVat=true`; the VAT split follows the 14.1 rules.
  - **Discount** (`discountAmount > 0`, where `totalAmount` = sum of the lines − discount):
    - The discount is spread over the lines in proportion to their totals (HALF_UP to the franc), and the remainder goes to the largest line.
    - Each line's net total `n` must be a whole multiple of its quantity `q`:
      - if it is, the line keeps `q` with unit `n/q`;
      - if not, the line becomes quantity 1 at `n`, described « <productName> (× q) ».
    - The invoice TTC equals the sale `totalAmount` exactly.
  - **Actor** = the sale's `actorId` for the created, issued and paid events and the audit.
  - **Payment method**: « Espèces » for CASH, « Mobile Money » for MOBILE_MONEY.
  - **Dates**: issue and payment dates are the processing time (clock), as for manual invoices. Offline sales synced later are invoiced when the server records them.
- **Persistence**:
  - `db/modules/invoicing/V3__invoice_source_sale.sql`: `ALTER TABLE invoices ADD COLUMN IF NOT EXISTS source_sale_id UUID` plus a unique index on `source_sale_id` (nulls allowed). It is applied lazily like V2.
  - The invoicing manifest goes to version 1.1.0 with `consumes: ["sale.completed"]`.
  - The response DTO exposes `saleId` (null for manual invoices).
- **Sale cancellation and correction** of an auto-invoiced (PAID) sale are out of scope here and need a credit note. They are routed to 14.7.

## Boundaries & Constraints

**Always:**
- Invoicing still depends only on `shared` + `crm :: api` (no commerce import).
- The capability is resolved per call.
- Replays (restart republish, 10-minute resubmission, sync re-push) never create a second invoice or burn a number.
- A DISABLED or READ_ONLY invoicing module, or a FREE tenant, gets no invoice and no tables created.
- POS off means no `sale.completed`, so no auto invoice. Manual invoicing is unaffected.
- French labels; no UUID in descriptions or audit.
- Prod-safe additive migration.
- All backend packages pass one by one, plus prod-classpath.

**Never:**
- No change to sale behaviour or sale-side transactions.
- No invoice for PENDING_VALIDATION sales (the event is published only at completion, including on later validation).
- No credit note.
- No Flutter.
- No deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Sale, invoicing ENABLED | 2 lines, no client, CASH | 1 invoice ISSUED → PAID, `FAC-yyyy-n`, client « Client comptoir », method « Espèces », TTC = sale total, `saleId` set | — |
| CRM client | `clientId` known, crm on | client name, phone and id from CRM | — |
| Client, crm off | `clientId` set, crm DISABLED | « Client comptoir » | — |
| Replay | same event delivered twice (sequential and concurrent) | still exactly 1 invoice, counter +1 only | — |
| Discount divisible | 2 × 1 000 + 1 × 500, discount 250 | lines net to 2 250; TTC 2 250 | — |
| Discount not divisible | 3 × 1 000, discount 100 | 1 line « Produit (× 3) » qty 1 at 2 900 | — |
| Invoicing DISABLED / FREE | sale completed | no invoice, no invoicing tables created | — |
| Invoicing READ_ONLY | sale completed | no invoice (INFO log) | — |
| Old event | `lines` null | no invoice, WARN | — |
| Pending sale validated | PENDING → COMPLETED (manual or cascade) | invoice created at validation | — |
| VAT-registered seller | settings vatRegistered, pricesIncludeVat=false | invoice still treats prices as TTC; TTC = sale total | — |

</intent-contract>

## Code Map

Paths are relative to `keevo/backend/src/main/java/com/keevo/` unless stated otherwise.

**Sale event**
- `shared/event/catalog/sale/SaleCompletedEvent.java` -- a class with getters, `tenantId` = schema. Add the fields and the nested `Line` record. Check its Jackson (de)serialisation shape against `RegistryEventSerializationTest` (`src/test/java/.../RegistryEventSerializationTest.java:97`) and `CatalogueEventPayloadTest`.
- Publishers:
  - `commerce/sale/application/service/RecordSaleService.java:138` -- COMPLETED path; it has the command with `clientId`, `paymentMode` and items.
  - `commerce/sale/application/service/SaleValidationCascadeService.java:124` -- has the domain `Sale`.
  - `commerce/sale/application/service/ValidateSaleService.java:143` -- has the domain `Sale`.
  - Data sources: `Sale` (`commerce/sale/domain/model/Sale.java`: clientId, paymentMode, items with productName, appliedUnitPrice, quantity) and `RecordSaleUseCase.SaleItemCommand`.

**Listener infrastructure**
- `shared/infrastructure/event/ModuleListenerInterceptor.java` -- binds the tenant for `@ApplicationModuleListener`.
- `shared/module/ModuleScoped.java` + `ModuleScopedListenerInterceptor.java` -- skips delivery when the module is DISABLED, delivers on READ_ONLY.
- Example listener: `messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java:81`. Do not use `@DeliverOnce`: the work is idempotent through data.
- `shared/module/EntitlementService.java` (`isEnabled`).
- `shared/module/ModuleProvisioner` (`ensureProvisioned(schema, id)`, `ModuleProvisionerService.java:164`).

**Invoicing**
- `invoicing/invoice/application/usecase/ManageDraftInvoiceUseCase.java:53` -- `create`.
- `InvoiceLifecycleUseCase.java:50,78` -- `issue` and `pay(id, method, actor, tenant)`.
- `InvoiceClientResolver.java` -- client lookup through the capability.
- Reuse these internals rather than duplicating the domain rules. `InvoiceSaleUseCase` may call the domain and repository directly, as long as numbering and events stay identical to the manual path.
- `invoicing/invoice/domain/model/{Invoice,LineInput,ClientInfo,InvoicingSettings,VatTreatment}.java` -- `LineInput(description, BigDecimal qty, long unitPrice, treatment)` needs `unitPrice ≥ 0`. The `pricesIncludeVat` snapshot is taken from the settings at draft creation, and the auto path needs an override.
- `invoicing/invoice/adapter/out/persistence/JdbcInvoiceRepository.java` -- insert and find; add `existsBySourceSale` / the `source_sale_id` column.
- `src/main/resources/db/modules/invoicing/V1__invoicing.sql`, `V2__invoice_sending.sql` -- the existing columns.
- `invoicing/invoice/adapter/in/rest/dto/InvoiceResponseDto.java` -- add `saleId`.

**Manifests and tests**
- `src/main/resources/keevo/modules/{invoicing,pos}/module.json`.
- `src/test/java/com/keevo/shared/module/ClasspathModuleManifestsTest.java:63`.
- Tests: `src/test/java/com/keevo/invoicing/InvoicingFullContextTest.java` (TenantFactory, `tenant_modules` inserts), `EventPublicationRegistryIntegrationTest` (Awaitility pattern on async listeners).

## Tasks & Acceptance

**Execution:**
- `SaleCompletedEvent.java` + its 3 publishers + the serialization fixtures -- enrich the event -- invoicing gets its data from the shared catalogue.
- `db/modules/invoicing/V3__invoice_source_sale.sql`, `JdbcInvoiceRepository`, `Invoice` (source sale), `InvoiceResponseDto` -- the sale link and idempotency key.
- `invoicing/.../application/usecase/InvoiceSaleUseCase.java` (+ a pure `SaleInvoiceMapper` for the client, lines and discount) -- create, issue and pay in one transaction, idempotent. Unit-test the mapper (discount rows, non-divisible lines, totals equality, counter client, payment labels).
- `invoicing/.../adapter/in/event/SaleInvoicingListener.java` -- gating (ENABLED only), provisioning, delegation.
- invoicing and pos `module.json` + the manifest tests -- 1.1.0 `consumes` / 1.3.0 `publishes`.
- `src/test/java/com/keevo/invoicing/SaleInvoicingFullContextTest.java` -- end to end with a real sale through `POST /api/v1/sales`, plus Awaitility: ENABLED → 1 PAID invoice; replay of the same event (call the listener twice, and concurrently) → 1; DISABLED → none and no tables; READ_ONLY → none; pending sale validated → invoice; crm client mapping.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 14.4 implementation notes and add the routed sale cancel/correct items to Story 14.7.

**Acceptance Criteria:**
- Given pos and invoicing active, when a sale is recorded via REST or sync, then within seconds exactly one PAID invoice exists with `saleId` = the sale, its TTC = the sale total, and the audit shows created, issued and paid under the seller.
- Given the outstanding-publication resubmission or a restart, when the same `sale.completed` is redelivered, then no second invoice and no skipped number appear.
- Given invoicing disabled for the tenant (FREE plan or a DISABLED row), when sales happen, then no invoice is created and manual invoicing endpoints keep answering as before.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 24 findings — high 1, medium 3, low 13, false 4, maybe-false 0 (intent-alignment's AC1/AC2/AC3 notes counted as 3 rows)
- findings:
  - `[medium]` `[patch]` (intent) Listener gates only on invoicing; after POS is turned off, a retried event or a cascade validation (SaleValidationCascadeService is not pos-gated) still creates invoices, contrary to AC « le handler cesse d'être invoqué » — fixed: the listener also requires `pos` ENABLED; full-context test with pos DISABLED.
  - `[low]` `[reject]` (intent) AC1 concurrency test calls the listener directly instead of through dispatch — the dispatch path is proven by the sequential-replay and REST tests; the concurrent test targets the data-level guard, which is the right layer.
  - `[low]` `[reject]` (intent) Manual invoicing with POS off not tested — manual endpoints do not read pos at all (gated on invoicing only, `@RequiresEntitlement("invoicing")`); covered by the pos-DISABLED test added above (GET /invoices 200).
  - `[medium]` `[patch]` (verification-gap) Validated pending sales: no test asserts `clientId` is passed into `SaleCompletedEvent` — fixed: non-null clientId in ValidateSaleServiceTest and SaleValidationCascadeServiceTest fixtures, asserted.
  - `[medium]` `[patch]` (verification-gap) Applied vs catalogue price indistinguishable in fixtures — fixed: RecordSaleServiceTest case catalogue 1500 / applied 1200 asserts the line unit price is 1200.
  - `[low]` `[reject]` (blind) Non-divisible discounted line shown as quantity 1 « (× q) » — the spec's chosen representation (I/O matrix); the real quantity stays printed in the designation and totals are exact; splitting into two lines is an alternative, not a defect.
  - `[low]` `[reject]` (blind) Discount not mentioned on the invoice — line prices are the net prices actually paid, as on any invoice with prices negotiated per line; totals equal the amount received.
  - `[low]` `[patch]` (blind, edge) Some permanent failures retried forever: `Invoice.draft` validation after the mapper accepted the content — fixed: caught before any write → NOT_INVOICEABLE + WARN. The description length overflow is impossible (product names ≤ 200 + suffix ≤ 500 column); CRM provider errors are transient by nature (retry is right).
  - `[low]` `[reject]` (blind, edge) Sales completed while invoicing was DISABLED / READ_ONLY / before 14.4 are never invoiced later — the story's AC: auto invoices exist « quand POS et Facturation sont tous deux actifs »; no backfill is asked; manual invoicing covers past sales.
  - `[low]` `[reject]` (blind) No business metric for skipped sales — logs at INFO/WARN name the tenant and reason; no harm named beyond observability preference.
  - `[low]` `[reject]` (blind) Draft creation logic duplicated from `ManageDraftInvoiceUseCase.create` — both call `Invoice.draft` + the same `InvoiceCreatedEvent`; the auto path deliberately skips the REST client resolver; extraction is refactoring without an observed divergence.
  - `[low]` `[reject]` (blind) Settings read twice / boolean flag on `issue` — negligible cost; the flag is internal to the module.
  - `[low]` `[reject]` (blind, edge) Unknown payment mode → PAID with null method — publishers always set CASH/MOBILE_MONEY from a two-value enum; method is optional on manual invoices too.
  - `[low]` `[reject]` (blind) Untested paths: sync (same RecordSaleService as REST), cascade end-to-end (publisher unit-tested + listener path shared), duplicate rethrow branch, NOT_INVOICEABLE through the use case (mapper unit-tested) — covered at the layer that owns each behaviour.
  - `[low]` `[reject]` (blind) Invoice dated at processing time with no sale reference — spec decision (issue date = server processing, like manual invoices); `saleId` exposed in the API for the app (15.5) to link the sale.
  - `[high]` `[patch]` (edge) Two DB connections held per delivery (outer listener transaction + REQUIRES_NEW use case): a burst of concurrent deliveries (sync push of many sales) can exhaust the pool (8 executor threads × 2 > 10) and deadlock — fixed: the listener holds no outer transaction while the use case runs.
  - `[false]` `[reject]` (edge) `assertIssuable` fails when legal name and display name are null — `CompanyIdentity.displayName` never returns null (designation › tenant name › « Mon commerce »).
  - `[low]` `[reject]` (edge) Archived CRM client used on the invoice — the sale itself was recorded for that client (the sale check includes archived clients on purpose); the invoice mirrors the sale.
  - `[false]` `[reject]` (edge claim) NOT_INVOICEABLE completes the publication instead of retrying — intended: an inconsistent sale is a permanent condition; retrying would loop forever (the spec's « other failure propagates » applies to transient failures).
  - `[false]` `[reject]` (edge claim) READ_ONLY sales dropped for good — the spec's matrix row « Invoicing READ_ONLY → no invoice ».
  - `[false]` `[reject]` (intent) diff gates POS only on the publishing side — superseded by the pos-gating patch above (same finding, counted once as patch).
  - `[low]` `[reject]` (intent) « existing invoices kept » not tested — nothing in the diff deletes invoices; deactivation never touches module data (13.8 contract, tested there).

## Design Notes

**Why issued and paid rather than a draft:** a completed sale has already been paid. The point of the bridge is that compliant invoices exist with no work from the merchant. A draft per sale would only add manual issuing and fill the drafts list. The gapless counter stays correct because creation, issue and payment share one transaction with the uniqueness check.

**Why enrich the event:** invoicing must not read commerce. The shared catalogue event is the contract between pos and invoicing. Typed lines replace parsing the audit JSON, which has two inconsistent shapes.

## Verification

**Commands** (from `keevo/backend`, one package at a time):
- `mvn -q -o test -Dtest='com.keevo.<pkg>.**' -DforkCount=1 -DargLine=-Xmx1536m -Dsurefire.failIfNoSpecifiedTests=false` for invoicing, commerce, shared, sync, architecture, then every other package -- expected: 0 failures.
- `mvn -q -o -Pprod-classpath test -Dtest='com.keevo.architecture.**'` -- expected: rc 0.

**Manual checks:**
- Live on dev (WhatsApp noop), tenant `kv_zggt7g` (PREMIUM_TRIAL):
  - `POST /api/v1/sales` → a PAID invoice appears with `saleId` and the right totals;
  - restart the backend → no duplicate;
  - set invoicing DISABLED in `tenant_modules`, sell → no invoice; restore the row.

## Auto Run Result

Status: done

**Summary.** Each completed sale (REST, sync, manual or cascade validation) now yields exactly one invoice, created, issued and paid in a single transaction by `SaleInvoicingListener` → `InvoiceSaleUseCase`, keyed on `invoices.source_sale_id` (unique, V3). It runs only while invoicing **and** pos are ENABLED. `SaleCompletedEvent` carries `clientId`, `paymentMode` and typed `lines` (events stored before 14.4 deserialise with nulls and are skipped). The client comes from CRM when available, else « Client comptoir »; the payment method is « Espèces » / « Mobile Money »; prices are always treated as TTC; the discount is spread so that TTC equals the sale total. The listener holds no outer transaction (`propagation = NOT_SUPPORTED`), so a delivery uses one connection.

**Files.**
- shared: `SaleCompletedEvent` (+ fields, `Line`).
- commerce: `SaleCompletedLines` (new), with `RecordSaleService`, `ValidateSaleService` and `SaleValidationCascadeService` filling the new fields.
- invoicing: `SaleInvoicingListener`, `InvoiceSaleUseCase`, `SaleInvoiceMapper` (new); `Invoice.sourceSaleId`/`fromSale`; `JdbcInvoiceRepository.existsBySourceSale`; `InvoiceLifecycleUseCase.issue(..., pricesIncludeVat)`; `InvoiceClientResolver.saleClient`; `InvoiceResponseDto.saleId`; `db/modules/invoicing/V3__invoice_source_sale.sql`.
- Manifests: invoicing 1.1.0 `consumes`, pos 1.3.0 `publishes`.
- Epics doc: 14.4 notes ticked; sale cancel/correct → credit note routed to 14.7.

**Review.** 24 findings: 5 patched — high: two connections held per delivery (pool deadlock risk); medium: pos not gated, clientId untested on validated sales, applied price untested; low: domain refusal retried forever. 0 deferred; 19 rejected (reasons in the triage log). Follow-up review recommended: **true**, because a high (transaction/connection model change) and two mediums were patched. The specific unverified risk is the `NOT_SUPPORTED` listener under real concurrent load (only the 6-delivery test).

**Verification.**
- After patches: invoicing 101, commerce 260, shared 1 029, sync 493, architecture 32, messaging 168, reporting 403 — 0 failures; prod-classpath rc 0. Before patches, all 15 packages were green.
- Live (dev, noop WhatsApp, kv_zggt7g, before patches):
  - boot seeded `updated=[invoicing, pos]`; `tenant_modules` 331 → 331;
  - `POST /sales` (Mobile Money, CRM client Alice, 3 × 1 000, discount 100) → `FAC-2026-000005` PAID, client Alice + phone, « Mobile Money », TTC 2 900, line « Chargeur USB-C (× 3) » qty 1 at 2 900;
  - backend restart → still 1 invoice for the sale;
  - invoicing DISABLED row → sale 201, no invoice, `GET /invoices` 403; row removed (331).

**Residual risks.**
- Sales made while invoicing or pos is off, or during grace, are never auto-invoiced later (by design).
- Dev data: 2 extra test sales and 2 auto invoices on kv_zggt7g.
