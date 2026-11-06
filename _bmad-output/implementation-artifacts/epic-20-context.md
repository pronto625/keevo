# Epic 20 Context: Modules métier Bref dans Keevo — documents, catalogue, clients, dépenses, finance (P1b)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Bring everything a Bref Point merchant does day to day into the Keevo Spring Boot API without breaking what a Keevo merchant already does. That covers products that do not track stock and deduplicate, customers kept per store with recorded marketing consent, quotes, payment collection (deposit, partial payments, schedule, overdue), Bref-identical invoice amounts (discount, delivery, VAT per country), automatic catalogue/CRM creation from documents, Bref's 8 rendering templates, signatures, long-lived public links, sending by SMS/email, revenue reporting without double counting, plus the new `expenses` and `finance` modules and the super-admin API inherited from Bref. Together with epic 21 this closes milestone G1: every Bref feature exists in the Keevo API, the Bref journey passes on Keevo (20.12), and the Flutter V1 suite stays green.

## Stories

- Story 20.1: Extended catalogue — products without stock, key, barcode, merge
- Story 20.2: Extended CRM, customers per store (ADR-08)
- Story 20.3: Quotes (`kind = QUOTE`)
- Story 20.4: Payment collection — deposit, partial payments, schedule, overdue
- Story 20.5: Discount, delivery, per-invoice VAT, due date, customer data
- Story 20.6: Derive catalogue and CRM from a document
- Story 20.7: Rendering templates, signatures, public links
- Story 20.8: Send documents by SMS and email
- Story 20.9: Multi-source reporting without double counting (R4)
- Story 20.10: `expenses` module
- Story 20.11: `finance` module
- Story 20.12: Acceptance test "Bref journey on Keevo"
- Story 20.13: Super-admin API inherited from Bref (platform view, cross-account lists, users)

## Requirements & Constraints

- **Cross-cutting rules from epic 19 still apply:** strictly additive `/api/v1` (nullable columns, new endpoints, new delta fields; DTOs never return `null` where Flutter V1 expects a value; Flutter tests and `curl-tests-story-*.sh` stay green). Backend only: each spec has a mobile section (and a dashboard section when it exposes super-admin API), tracked as `m20-*` / `d20-*` keys. No technical data in UI (French labels, no UUIDs/raw codes in screens, PDFs, exports, audit detail). VIEWER never writes. FUS-NFR11 is blocking in review: each spec names its pattern and hot-path complexity; no N+1, set-based SQL, keyset pagination, indexes for new filters, batch jobs.
- **Two sale paths:** POS (sale + stock + automatic invoice) and DOCUMENT (invoice or quote, no stock), chosen through `default_sale_flow`. Every feature must work for both paths.
- **Interface profile:** a module works only when it is in the plan AND activated by the owner. Inactive modules have no screens, jobs or notifications. Any new module (`expenses`, `finance`) ships a public migration that owner-disables it for existing tenants (pinned by an architecture test). Cross-module effects (derivation, reporting) must degrade cleanly when the other module is inactive.
- **Invoices are compliance-locked:** an issued invoice is never edited (DGI); corrections = credit note + new invoice. Quotes are editable (`expected_updated_at` → 409), cancellable, and convertible. Conversion yields an ISSUED invoice with a gapless `FAC-` number and `issue_date` = conversion date.
- **Quotas (19.8):** a quote does not consume DOCUMENTS; converting it does (`UsageQuotaService.assertAndIncrement`). SMS/email/WhatsApp sends are charged only after provider success. A failed send never alters the invoice. Replayed sync is never counted twice.
- **Amounts (FUS-NFR8, ADR-01):** multi-country (CM, GA, TD, CD, SN). Default VAT per shop country (Bref table: CM 19.25 %, SN/GA… 18 %, CD 16 %…). Currency and rounding reproduce Bref country by country (verify in Bref code first). Discount is applied before VAT, delivery is outside the VAT base, rounding is HALF_UP per line then on the total, quantities have 3 decimals. Results must match Bref on 50 reference invoices from the prod copy. Calculation cases (payments, totals) are published in `contracts/` for reuse by the RN app.
- **Business days (FUS-NFR9):** use the shop's country time zone rather than hard-coded WAT. Imported civil dates are kept as-is.
- **Payment state:** `UNPAID/PARTIAL/PAID/OVERDUE` is computed server-side under a row lock. Overpayment is refused (`OVERPAYMENT`). A daily job (`@SchedulerLock`) marks overdue invoices and notifies the owner. Existing Keevo invoices with a single `paid_at` read as one payment, with no destructive migration.
- **Customers per store (ADR-08):** each new customer has a required origin store. Lists are filtered on the current store: an employee sees only theirs; the owner can see "all my stores"; a store can opt in to sharing with other sharing stores. `DUPLICATE_PHONE` is a warning, never a DB uniqueness constraint, and only applies within the same store. There is no automatic merging. Withdrawn consent excludes the customer from campaigns immediately.
- **Revenue definition (R4):** completed POS sales ∪ ISSUED/PAID invoices with `source_sale_id IS NULL`. Quotes and cancelled invoices are excluded and credit notes are deducted. One shared source (`RevenueQueryPort` or SQL view) feeds the dashboard, reports, profitability, seller ranking and exports. Invoices without linked products count toward revenue but are shown as "unknown margin". KPIs for imported Bref owners match Bref's owner dashboard exactly.
- **Legacy Bref documents:** `BRF-…` references and old public tokens stay valid. The public link TTL is configurable per tenant (30 d for Keevo, 90 d for imported tenants), HMAC-signed, and can be rotated.
- **Super-admin parity (FUS-FR38):** every Bref admin route has a tested Keevo equivalent (existing, extended or new) or an abandonment decision written by the user. Platform KPIs cover Keevo tenants plus migrated owners without double counting.
- **Bref keeps evolving (no freeze):** check the Bref evolution journal at the start of each story. Mirrored changes, such as customer address/contact/website copied onto documents, belong to the owning story.

## Technical Decisions

- **Reference behaviour:** api-bref `develop` (`src/invoice/*`, `src/expense/*`, `src/finance/*`, `product-key.ts`, `customer.repository.ts`, `owner-dashboard.service.ts`, `finance-math.ts` and its spec). Bref is ported functionally; its non-transactional side effects are not copied.
- **Modular platform:** new modules come with a manifest (`requires`, premium flag, `permissionsFor(Role)`), `db/modules/<id>/V*__*.sql`, `DeltaEntityProvider`, and entitlement + permission gates. ArchUnit, Modulith `verify`, `EndpointEntitlementCoverageTest` and the feature→module table apply. `expenses` is premium (PRO+). `finance` requires `expenses` and `reporting`.
- **Sync:** every new operation is idempotent per `operationId` (a second push → `DUPLICATE`), uses LWW on `clientTimestamp`, and sends tombstones for deletes. New operations: `CONVERT_QUOTE`, `ADD_INVOICE_PAYMENT`, `SETTLE_INVOICE_PAYMENT`, `SEND_INVOICE`, `CREATE/UPDATE/DELETE_EXPENSE`. Each one is added to `contracts/invoice-sync-payloads.json` or a dedicated contract.
- **Catalogue:** `products.track_stock` was delivered by 17.12, so 20.1 only adds non-regression checks and the `false` default for derivation and import. `product_key` = `bc:<barcode>` or `nm:<normalized name>`. Merge archives the merged product with `merged_into_id`. Lookups (`GET /products/lookup?barcode=`) and the sync pull follow the redirect, and history lines stay intact.
- **Data model additions:** `clients` get type, NIU, RCCM, address, contact, website, birth date, `store_id`, `phone_normalized` and consent at/opt-out at/by. Invoices get discount, delivery fee, nullable `vat_rate`, `vat_exempt`, `due_date`, client RCCM/email/contact/website, template (8 values), ticket width (58/80/A4), signature paths, `legacy_public_token`, `legacy_reference`. `invoice_items` get nullable `product_id` and `reference`. New `invoice_payments(id, invoice_id, amount, due_date, paid_at, method, note)`. Tenant-level `expenses(id, store_id, category[10 Bref values], amount, date, label, note, created_by, timestamps, archived_at)`.
- **Derivation (20.6):** behind the preference `derive_from_documents` (default on for DOCUMENT). Uses `ClientLookupPort` (phone within the current store) and `ProductLookupPort` (`product_key`, otherwise create with `track_stock=false` at the line price), in the same transaction as the invoice.
- **Rendering:** 8 server Thymeleaf templates mirror Bref's React templates. Signatures and files go through the file-storage port (as with store logos in 19.12).
- **Messaging:** reuse the 19.9/19.9b provider chain (Bref providers prevail). Owner-number WhatsApp (`bref_facture`) belongs to 21.5.

## Cross-Story Dependencies

- **Upstream:** depends on 19.1, 19.5, 19.7, 19.8 (all done), 19.12 (`default_sale_flow`, store country), 19.13 (activation layer), 17.12 (`track_stock`) and 17.13 (restore/merge of archived product names).
- **Ordering within epic 20:**
  - 20.5 amounts feed 20.4 (payments), 20.7 (PDF) and 20.9 (revenue).
  - 20.3/20.4 provide the invoice states that 20.9 counts.
  - 20.6 needs 20.1 (`product_key`) and 20.2 (store-scoped lookup).
  - 20.8 needs 20.7 (public link).
  - 20.11 needs 20.9 and 20.10.
  - 20.12 replays the whole journey (OTP login → store → quote → conversion → deposit → schedule → overdue → payment → SMS send → expense → finance summary → public link) plus the symmetric POS scenario, and closes G1 together with 20.13 and epic 21.
- **Overlaps:** 20.13 overlaps 19.10 (app versions), 16-4 console / `/admin/tenants`, 16-11 sessions, 16-8 profiles and 21.6 (platform campaigns).
- **Downstream:**
  - 21.6 targets consenting customers per store (20.2).
  - 24.3/24.4 import products, customers, documents, payments and expenses into these schemas.
  - 23.2–23.5 RN screens reuse the shared calculation cases.
