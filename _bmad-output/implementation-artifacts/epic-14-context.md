# Epic 14 Context: Pilot module Invoicing (Facturation) — refactor Phase 4

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Deliver Keevo's first sellable module, one that works alone and also plugs into other modules, to prove the "connected mini-apps" vision of the modular refactor. Invoicing must work on its own (manual invoices, PDF, WhatsApp). It picks up CRM clients when CRM is active and creates invoices automatically from POS sales when POS is active. When POS is later deactivated, nothing breaks: invoices already created are kept and manual invoicing keeps working. Invoicing is the template every future module follows: its own manifest, its own tables, its own permissions, gating, events, capabilities and extension contributions. It is a Premium (paid) module.

## Stories

- Story 14.1: Standalone Invoicing module (domain, provisioning, CRUD, `@RequiresPermission`) — done
- Story 14.2: Invoice PDF and WhatsApp sending (plus company designation and logo) — done
- Story 14.3: Optional CRM integration through the `ClientLookupPort` capability
- Story 14.4: POS → Invoicing bridge (automatic invoice on `sale.completed`)
- Story 14.5: POS checkout extension point → « Facturer » action (backend contribution)
- Story 14.6: Vision acceptance test (4 activation scenarios)
- Story 14.7: Credit notes (avoirs) on invoices

## Requirements & Constraints

- **Standalone module:** with only core and invoicing active (pos, crm and catalog DISABLED), a tenant can create, edit, list, issue, pay and cancel invoices. The manifest has `requires: ["core"]` only, `enhances: ["pos","crm"]` and `needs: ["ClientLookupPort?"]`. Its permissions are `INVOICE_CREATE`, `INVOICE_VIEW`, `INVOICE_SEND` and `INVOICE_MANAGE`.
- **Degraded mode:** an absent optional integration never causes an error. Without CRM, the client is free text. Without POS, no invoice is created automatically and no POS slot or route appears.
- **Automatic integration:** when POS and Invoicing are both active, each `sale.completed` creates exactly one invoice, mapped from the sale. Replaying the event must not create a duplicate. Once POS is deactivated, the handler is no longer invoked and existing invoices are kept.
- **Cameroon legal content (CGI art. 150):** an invoice shows the seller's identity (legal name, address, NIU, RCCM), the client's identity, a description of each line, HT / rate / VAT / TTC per line and in total, per-line « Exonérée » / « Prise en charge État » mentions, and « TVA non applicable » for sellers not registered for VAT.
- **Numbering:** numbers are unique, continuous and chronological, with no gaps even under concurrency or rollback. A series restarts each calendar year (Africa/Douala): `FAC-<yyyy>-<000001>` for invoices and `AV-<yyyy>-<000001>` for credit notes.
- **Immutability:** issued invoices are never modified or deleted (10-year retention). A PAID invoice can only be corrected or cancelled through a credit note (14.7). A credit note references an ISSUED/PAID invoice for all or some of its lines, carries negative totals that mirror the VAT treatment of those lines, and is audited and published as an event.
- **Amounts:** XAF integers; rounding HALF_UP to the franc.
- **Gating:** every endpoint is annotated with both `@RequiresEntitlement("invoicing")` (READ or WRITE per HTTP verb) and `@RequiresPermission`, and appears in the endpoint coverage doc. A Free or disabled tenant gets 403 `MODULE_NOT_ENTITLED`. A tenant in its grace period is read-only (`MODULE_READ_ONLY`).
- **UX/data hygiene:** all user messages and audit labels are in French. No UUID or raw code appears in any message, PDF, WhatsApp text, audit detail or export.
- **Process:** TDD. Run backend tests one package at a time (never the full suite at once), plus the prod-classpath architecture check.

## Technical Decisions

- **Module shape:** top-level Spring Modulith module `com.keevo.invoicing`. It is hexagonal, has a public `api/` package and `allowedDependencies = {"shared"}`. It never imports pos, crm or catalog code; it reads them only through `shared` ports, the shared event catalog or another module's `api`. Modulith `verify()` and ArchUnit block the build on violations. The module implements the `KeevoModule` contract.
- **Persistence:** the module owns its tables. They are created only through per-module Flyway migrations (`db/modules/invoicing/V*.sql`, history in `flyway_history_invoicing`), are accessed through JDBC adapters, and have no JPA entity and nothing in `public`. On the first entitled call, the socle provisions them lazily in each tenant's schema (`ensureProvisioned`). Each new migration must be additive and safe for prod. Tenant SQL is resolved via `TenantSchema.validate`.
- **Linking modules** (fixed rule): events and capabilities are the default; bridges are reserved for orchestration.
  - 14.3 resolves `ClientLookupPort` via `CapabilityRegistry.find(...)` → `Optional`. Consumers must not swallow provider exceptions. A write capability must call `assertWritable` when the provider is READ_ONLY.
  - 14.4 is an `@ApplicationModuleListener` on `SaleCompletedEvent` from the shared event catalog (no POS import). It is idempotent, keyed on eventId or saleId, and runs only if `invoicing` is active (`ModuleRegistry`). Extract a dedicated `bridge-pos-invoicing` only if the sale→invoice mapping grows (taxes, discounts, currencies).
  - 14.5 fills POS's declared slot `pos.checkout.post-sale-actions` through the manifest `fills` plus `@ContributesExtension(module="invoicing", ...)`. This declarative path is the only one; the Flutter rendering is done in Epic 15.
- **Delivery:** events go through the transactional outbox, and handlers must be idempotent. Invoice events live in `shared/event/catalog/invoicing` (created, issued, paid, cancelled, sent) and are audited with French labels and the invoice number.
- **Permissions:** OWNER has every manifest permission. EMPLOYEE has VIEW, CREATE and SEND, declared by the module. SUPER_ADMIN has none. Evaluation order is role → permission → entitlement.
- **Seller identity:** the seller profile (`invoicing_settings`) is snapshotted onto the invoice at issue. The displayed company name is the designation if set, otherwise the tenant name, read through a `shared` `CompanyIdentity` port. The PDF engine lives in `shared`.
- **PDFs and WhatsApp:** PDFs are rendered on demand and not stored. WhatsApp sends text with a signed, time-limited public link (the existing text-only sender).
- **Lifecycle:** DRAFT → ISSUED → PAID; ISSUED → CANCELLED (a reason is required and the number is kept). Error codes: `INVOICE_NOT_FOUND` 404, `INVOICE_NOT_EDITABLE` / `INVOICE_INVALID_TRANSITION` 409, `INVOICE_NOT_ISSUABLE` 422.
- **Module data is kept for life:** deactivating a module hides its routes, permissions and handlers but keeps its data. Only a GDPR deletion purges it.

## UX & Interaction Patterns

- The invoicing UI is built in Epic 15 (15.5). It covers the list, manual creation, PDF preview and WhatsApp sending. The client picker uses CRM when its capability is present, otherwise a free-text field.
- An `ExtensionSlot(pointId)` renders contributions from active modules; for example, « Facturer » appears in the POS checkout (Flutter work in 15.3). Open question: the « Facturer » button should be hidden based on the cashier's permission and the sale's context (for example, a sale already invoiced automatically). This can be done with a client-side filter or an optional `permission()` on `Extension`.
- `complianceWarnings` (French sentences) must be shown before issuing when the NIU, RCCM, address or legal name is missing.

## Cross-Story Dependencies

- **Built on Epic 13 (socle):** `ModuleRegistry`, `ModuleProvisioner`, `EntitlementService`/`@RequiresEntitlement`, `CapabilityRegistry` (CRM has provided `ClientLookupPort` since 13.6), `ExtensionRegistry` (the POS slot declared in 13.7), `ModuleLifecycleService` (activation used by 14.6) and the `KeevoModule` contract (13.9).
- **14.3** also takes over items routed to it:
  - Move commerce/sync consumers to `ClientLookupPort`; today an unknown `clientId` on a sale hits an FK → 500.
  - Decide the `clientId` policy when CRM is absent.
  - Possibly add a search method to the port.
  - After the `sync → crm::api` edge is removed, relocate `SyncOperationHandler`s into their owning modules.
- **14.4 and 14.5** need POS publishing `sale.completed` and declaring the slot; 14.5's UI depends on 15.3.
- **14.6** runs only after 14.3–14.5. It combines `@ApplicationModuleTest` with `AssertablePublishedEvents` and toggles modules per tenant across 4 scenarios: invoicing alone; +crm; +pos (automatic invoice plus slot); pos disabled (no automatic invoice, manual still works, data kept).
- **14.7** extends the 14.1 lifecycle and numbering.
- **Epic 15:** 15.4 and 15.5 own the Flutter package `keevo_invoicing`. 15.5 also owns:
  - offline sync operations and the `invoices` delta provider (numbering stays server-side);
  - list search and date filters, and a choosable payment date;
  - the seller fiscal settings screen.
- **Epic 16 (16.7):** go-live config for the public link secret, the public base URL and the persistent logo directory.
