# Epic 20 Context: Modules métier Bref dans Keevo — documents, catalogue, clients, dépenses, finance (P1b)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make everything a Bref merchant does day to day (quotes, invoices with payments and schedules, rich client records, products without stock, expenses, financial results) exist in the Keevo backend, without breaking anything a Keevo merchant already does (POS, stock, legal invoicing). Keevo's Spring Boot backend absorbs api-bref capability by capability as modules; this epic delivers the business modules, plus the super-admin API the Bref dashboard uses today, so that gate G1 (all Bref features in the Keevo API, Bref journey green) can be passed. It is part of the go-live milestone GL: the refonte only goes to production once the whole merge is done.

## Stories

- Story 20.1: Catalogue étendu — produits sans stock, clé, code-barres, fusion (done)
- Story 20.2: CRM étendu, clients par établissement (ADR-08) (done)
- Story 20.3: Devis (`kind = QUOTE`)
- Story 20.4: Encaissements — acompte, paiements partiels, échéancier, retard
- Story 20.5: Remise, livraison, TVA par facture, échéance, données client
- Story 20.6: Dérivation catalogue et CRM depuis un document
- Story 20.7: Modèles de rendu, signatures, liens publics
- Story 20.8: Envoi des documents par SMS et email
- Story 20.9: Reporting multi-source sans double comptage (R4)
- Story 20.10: Module `expenses`
- Story 20.11: Module `finance`
- Story 20.12: Test d'acceptation « parcours Bref sur Keevo »
- Story 20.13: API super-admin héritée de Bref (vue plateforme, listes inter-comptes, utilisateurs)

## Requirements & Constraints

- **Golden rule of the merge:** no user reinstalls, loses data, changes password or pays twice. Any story touching production data proves it in its acceptance criteria.
- **Backward compatibility of `/api/v1`:** every backend change is additive (nullable columns, new endpoints, new delta fields). The Flutter V1 app and the existing curl test scripts stay green. An existing Keevo invoice with a single `paid_at` reads as one payment; no destructive migration.
- **Functional reference = api-bref `develop`** (invoice, expense, finance, product-key, customer repository). Bref keeps evolving during the merge (no freeze): changes tracked in the Bref change journal are mirrored into the owning story.
- **Products:** per-product `track_stock` (already delivered by 17.12; Bref products default to `false` on import and derivation); `product_key` (`bc:<barcode>` / `nm:<normalised name>`), barcode, manual price; product merge archives the merged product with a redirect.
- **Clients per establishment (ADR-08):** `store_id` mandatory on new records; list filtered on the current establishment; employee limited to theirs; owner can view "all my establishments"; optional per-establishment sharing; `DUPLICATE_PHONE` is a warning (no DB uniqueness) only within the same establishment; never auto-merge. Marketing consent tracked (given/withdrawn/by whom); withdrawal excludes from campaigns immediately.
- **Documents:** quotes are editable/cancellable, have no `FAC-` number and don't consume the legal counter or the DOCUMENTS quota; conversion creates an ISSUED invoice (gapless number, issue date = conversion date) and consumes the quota. Issued invoices stay immutable (DGI). Payment state (UNPAID/PARTIAL/PAID/OVERDUE) is server-computed under row lock, overpayment refused, daily overdue job notifies the owner.
- **Amounts (ADR-01, multi-country CM/GA/TD/CD/SN):** discount before VAT, delivery outside VAT base, HALF_UP per line then total; per-invoice VAT rate or exemption, default = country rate (CM 19.25 %, SN/GA 18 %, CD 16 %…); currency and rounding reproduce Bref per country (verify in Bref code before writing the 20.5 spec). Results must match Bref on reference invoices from the prod copy. Calculation test cases are published in `contracts/` for reuse by the RN app.
- **Business days** in the establishment country's time zone; KPIs use the document's civil date.
- **Revenue (R4):** sales COMPLETED ∪ invoices ISSUED/PAID with `source_sale_id IS NULL`, quotes and cancelled excluded, credit notes deducted — never double counted; invoice lines without product count in revenue but not in per-product margin.
- **Sending** (SMS/email/WhatsApp): message carries the public link, sending is logged, quota decremented after provider success in a short transaction (never around the HTTP call); failure never alters the invoice; offline `SEND_INVOICE` idempotent per `operationId`.
- **Public links:** HMAC-signed, lifetime configurable per tenant (30 d Keevo, 90 d imported), rotatable; old Bref links (`legacy_public_token`, `BRF-` reference) keep working.
- **Super-admin API (20.13):** every Bref admin route has a delivered, tested Keevo equivalent or a written abandonment decision by the user; platform indicators cover Keevo tenants and migrated owners without double counting.
- **Acceptance (20.12):** an end-to-end Bref day (OTP login → store → quote → conversion → deposit → schedule → overdue → payment → SMS send → expense → finance summary → public link) on a DOCUMENT/PRO tenant, plus the symmetric Keevo POS scenario staying green.
- **Client lookup at scale (routed to 20.6 from the 20.2 review):** client list and search are still unbounded and use `LIKE '%q%'` without a trigram index; when client lookup by phone/name starts serving document derivation, add keyset pagination and a `pg_trgm` index.
- **Patterns and complexity (blocking in review):** state the chosen design pattern and hot-path complexity in each spec; no N+1, set-based SQL, keyset pagination (never OFFSET on growing tables), indexes for every new filter/join/sort (e.g. `clients(store_id, phone_normalized)`), batch operations.
- **No technical data in UI/exports:** French labels only, no UUIDs or raw codes.

## Technical Decisions

- **Two sale paths:** *Caisse* (module `pos`: sale + stock movements + automatic invoice bridged via `source_sale_id`) and *Document* (module `invoicing`: invoice with no sale row and no stock movement). Imported Bref invoices are documents. Tenant preference `default_sale_flow` (POS/DOCUMENT) comes from 19.12.
- **Every Bref capability is a Keevo module:** manifest, `db/modules/<id>/` Flyway scripts, per-role permissions, `DeltaEntityProvider`, app routes, `@RequiresEntitlement` on endpoints. ArchUnit, Modulith verify, `EndpointEntitlementCoverageTest` and the feature→module table apply without exception.
- **Invoicing extensions are additive in `db/modules/invoicing/`:** `kind ∈ {INVOICE, QUOTE}`; `invoice_payments(id, invoice_id, amount, due_date, paid_at, method, note)`; `discount_amount`, `delivery_fee`, `vat_rate` (nullable), `vat_exempt`, `due_date`, copied client fields (`client_rccm`, `client_email`, `client_contact_name`, `client_website`); nullable `invoice_items.product_id` + `reference`; `invoice_template` (8 values), `ticket_width ∈ {58, 80, A4}`, signature paths; `legacy_public_token`, `legacy_reference`. Legal status machine ISSUED/PAID/CANCELLED is kept; `payment_state` is derived. Quantities `NUMERIC(12,3)`.
- **Sync:** new idempotent operations (`CONVERT_QUOTE`, `ADD_INVOICE_PAYMENT`, `SETTLE_INVOICE_PAYMENT`, `SEND_INVOICE`, `CREATE/UPDATE/DELETE_EXPENSE`) — duplicates return `DUPLICATE`, LWW on `clientTimestamp`, tombstones in pull; optimistic `expected_updated_at` → 409 on edit conflicts. Every new operation is added to `contracts/invoice-sync-payloads.json` (or a dedicated contract) so the RN app checks it.
- **Derivation (20.6):** via capability ports (`ClientLookupPort` exists; product lookup port is new), in the same transaction as the invoice; client found by normalised phone in the current establishment; products found by `product_key` or created `track_stock=false`; tenant preference `derive_from_documents` (default on for DOCUMENT); no derivation when `crm`/`catalog` inactive (module autonomy).
- **Reporting:** a single revenue source (query port or SQL view) consumed by owner dashboard, daily/weekly reports, profitability, seller ranking and exports — no consumer reimplements the union.
- **Scheduled jobs:** multi-tenant, `@SchedulerLock`; a module disabled by the owner runs no job or notification.
- **New modules:** `expenses` (premium, PRO+ profile; `expenses(id, store_id, category, amount, date, label, note, created_by, timestamps, archived_at)`, 10 Bref categories, employee scoped to their store) and `finance` (`requires: [expenses, reporting]`; `GET /finance/summary?period=&storeId=` matching Bref's `finance-math` test cases).
- **Plans/quotas:** quotas go through `UsageQuotaService/Port.assertAndIncrement` (19.8); no plan code, price, limit or quota literal in code (offer is data, ADR-05).
- **Server PDF:** Thymeleaf templates reproducing Bref's 8 receipt templates.

## Cross-Story Dependencies

- Depends on epic 19: 19.1 (identity), 19.5/19.5b (MANAGER/VIEWER roles and fine rights — the product-merge permission question from the 20.1 review is routed there), 19.7 (plans as data), 19.8 (quotas), 19.9 (SMS/email providers), 19.12 (store fields, sale-flow preference), 19.13 (new modules disabled by default, `invoicing` granted to FREE but owner-activated).
- 20.3 → 20.4 (payments on converted invoices); 20.5 feeds 20.4/20.9 amounts; 20.1 + 20.2 → 20.6; 20.7 public link → 20.8 sending; 20.9 → 20.11 (CA HT) and 20.13 (platform KPIs); 20.10 → 20.11; 20.12 closes the epic after all others; G1 requires 20.12, 20.13 and epic 21.
- Already delivered and to build on: 20.1 (product key, barcode lookup `GET /products/lookup`, merge with `merged_into_id` redirect transmitted in pull) and 20.2 (Bref client fields, per-establishment list with owner sharing, `DUPLICATE_PHONE` warning, marketing consent via REST and sync). Open question routed to 19.5b: whether product merge is restricted to owner/manager. Routed to 22.5: pull tombstone when a client leaves an establishment's scope.
- Downstream: 21.6 campaigns use consent and per-establishment targets (20.2); 23.x RN screens consume these APIs and shared contracts; 24.3/24.4 import uses product keys, per-establishment clients, a dedicated invoicing import port (no counter consumption, no bridge events) and legacy references/tokens.
- Mobile work is done in the Bref repo by another developer: each spec carries a mobile section, tracked as separate `m20-x` keys in sprint status.
