---
title: '20.1 — Extended catalogue: products without stock (non-regression), barcode, product key, manual price, merge, barcode lookup'
type: 'feature'
created: '2026-11-06'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref products (services, documents) carry a free reference or barcode, a deduplication key, a "manual price" flag and can be merged; Keevo products have none of these, so imported or derived Bref articles cannot be found by barcode, deduplicated, or merged without losing history. Products without stock tracking already exist (17.12) and must keep behaving.

**Approach:** Keep 17.12's stock behaviour as the non-regression reference. Add `barcode`, `product_key`, `price_manual`, `merged_into_id` to products (catalog module V2), compute the key with Bref's rules, add an owner merge command that archives the merged product and redirects every later reference to the target (REST, sync push, barcode lookup), and a barcode lookup endpoint. All API changes additive.

## Boundaries & Constraints

**Always:**
- Non-regression (a)/(b): the 17.12 rules stay as they are and are re-asserted by tests: an untracked product sold at POS → no `INSUFFICIENT_STOCK`, no `SALE` movement, no `stock_levels` row, no threshold alert, excluded from stock exports/inventory; enabling tracking with a quantity > 0 records the starting stock as the existing « Stock de départ » movement (`STOCK_ENTRY` + `INITIAL_STOCK_NOTE` — this IS the AC's « INITIAL » movement, no new movement type), quantity 0/absent → no movement, product then tracked.
- catalog module `V2__products_bref_fields.sql` (idempotent, `SET LOCAL lock_timeout='10s'`): `barcode VARCHAR(100) NULL`, `product_key VARCHAR(250) NULL`, `price_manual BOOLEAN NOT NULL DEFAULT false`, `merged_into_id UUID NULL REFERENCES products(id)`; indexes `idx_products_barcode (barcode) WHERE barcode IS NOT NULL`, `idx_products_product_key (product_key) WHERE product_key IS NOT NULL`, `idx_products_merged_into (merged_into_id) WHERE merged_into_id IS NOT NULL`. Non-unique (several shops/products may share a reference; dedupe is the importer's job, 24.3). Public template mirror migration `V52` for the JPA-mapped columns (db/modules README rule).
- `ProductKeys` (catalog domain, pure): `normalizeName` = Unicode NFD, strip combining marks U+0300–U+036F, lower-case (Locale.ROOT), collapse whitespace, trim (punctuation kept); `isBarcodeLike` = trimmed `^\d{8,14}$`; `key(name, barcode)` = `bc:<trimmed barcode>` if barcode-like, else `nm:<normalized name>`, null for a blank name — byte-for-byte the Bref examples (`'  Thé   VERT  '`→`the vert`, `'CRÈME Brûlée'`→`creme brulee`, `REF-001`/7/15 digits not barcodes).
- `product_key` is recomputed on every create/update that changes name or barcode (REST, sync, CSV import, create-or-restore). Existing rows: one-shot backfill job (`data_migrations` marker, pattern `PlanMigrationJob`, after boot, ShedLock, per live tenant one transaction, `product_key IS NULL` rows only, batched updates) — no data UPDATE in the module script.
- `price_manual`: true when a price is set by a person (REST/sync create or update carrying `price`, CSV import); existing rows backfilled `true` by the same job (every Keevo price was typed by the owner); false only for future automatic creations (20.6 derivation, 24.3 import).
- `barcode` optional on REST `POST/PATCH /products` and sync `CREATE_PRODUCT`/`UPDATE_PRODUCT` payloads (absent ⇒ unchanged; blank ⇒ cleared; > 100 chars ⇒ 422 « La référence ou le code-barres ne doit pas dépasser 100 caractères »). Responses and the product sync delta gain `barcode`, `productKey`, `priceManual`, `mergedIntoId` (additive).
- Merge `POST /api/v1/products/{id}/merge` body `{ "targetId": "<uuid>" }` — `{id}` is the product merged AWAY (Bref convention). Allowed to the roles that may archive a product today; VIEWER refused by the existing write guard. Single transaction, both rows locked (`FOR UPDATE`, ordered by id): refuse same id (422), unknown/other-tenant id (404 `PRODUCT_NOT_FOUND`), source or target already merged or target archived (409 `PRODUCT_MERGE_INVALID` with French reason), source holding non-zero stock in any store (409 `PRODUCT_MERGE_INVALID` « Ce produit a encore du stock : mettez-le à zéro avant de le fusionner »). Effect: source `archived=true`, `merged_into_id=target`, `updated_at=now`; existing products merged into the source re-pointed to the target (one UPDATE — chains stay flat); target gets the source barcode if it has none (key recomputed). History untouched (sale lines keep their product id and name snapshot). Event `ProductMergedEvent` audited `PRODUCT_MERGED` « Produit fusionné » (source and target names). Response 200 = the target product.
- Redirection to the merge target (single resolver `ProductQueryPort.resolveMerged(id)` following `merged_into_id`, at most one hop since chains are flat): sale creation by REST (`RecordSaleService`) and every sync push payload `productId` (extend `SyncPushService.redirectMergedProductIds` to also resolve `products.merged_into_id`, set-based: one query for all ids of the batch); create-or-restore by name (REST and sync `mergeIntoExistingByName`) never resurrects a merged product — it answers with its target; `UNARCHIVE_PRODUCT` / unarchive of a merged product → refused (sync `REJECTED` `PRODUCT_MERGED`, REST 409 `PRODUCT_MERGE_INVALID` « Ce produit a été fusionné avec « X » »).
- Lookup `GET /api/v1/products/lookup?barcode=<code>` (all store roles, read): matches `barcode = code` OR `product_key = 'bc:'||code` including merged rows, direct matches first, follows `merged_into_id`; returns the active target (200, same DTO as `GET /{id}`), else 404 `PRODUCT_NOT_FOUND` « Aucun produit avec ce code ». Blank code → 422.

**Never:**
- No new stock movement type, no change to 17.12 stock rules, no uniqueness constraint on barcode/product_key, no automatic merge, no change to the export datasets/contract or to `keevo/app`, no invoice-line product link (20.6), no Bref import (24.3), no change to V1 fields.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Key name | create « CRÈME Brûlée », no barcode | `productKey: "nm:creme brulee"`, `priceManual: true` | — |
| Key barcode | barcode « 6111234567890 » | `productKey: "bc:6111234567890"` | — |
| Not barcode | barcode « REF-001 » | key from name | — |
| Lookup direct | `?barcode=6111234567890` | product | — |
| Lookup merged | barcode of a merged source | target product | — |
| Lookup none | unknown code | — | 404 `PRODUCT_NOT_FOUND` |
| Merge ok | A (no stock) → B | A archived, `mergedIntoId: B`; 200 B; audit row | — |
| Merge chain | C merged into A, then A → B | C and A point to B | — |
| Merge stock | A has stock 5 | — | 409 `PRODUCT_MERGE_INVALID` |
| Merge self / merged / archived target | — | — | 422 / 409 |
| Offline sale on A | sync `CREATE_SALE` item `productId: A` after merge | applied on B | — |
| Recreate by name | create « A's name » after merge | answered with B (`restored`/merged outcome), A stays archived | — |
| Unarchive merged | `UNARCHIVE_PRODUCT` A | — | `REJECTED` `PRODUCT_MERGED` |
| V1 body | PATCH without `barcode` | barcode unchanged | — |
| Untracked sale (17.12) | `track_stock=false` sold | no stock row / movement / check | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/modules/catalog/V1__catalog_baseline.sql:48-88` -- products columns, `uq_products_name` (includes archived — a merged product keeps its name).
- `.../catalog/product/domain/entity/Product.java:23-151`, `ProductJpaEntity.java:51`, `ProductRepository` + `ProductSpringRepository.java:70` + `ProductRepositoryAdapter.java:155` -- immutable aggregate with `with*`, conditional updates.
- `.../catalog/api/ProductQueryPort`, `ProductCommandPort`, `ProductView.java:27` -- public API (add `resolveMerged`, merge command, fields).
- `.../catalog/product/adapter/in/web/ProductController.java:73-228`, `ProductResponseDto.java:30`, `CreateProductRequestDto.java:47`, `UpdateProductRequestDto.java:38` -- REST; literal `/lookup` mapping wins over `/{id}`.
- `.../catalog/product/application/.../CreateOrRestoreProductUseCase.java:45,63-85` -- restore by name (must skip merged rows → target).
- `.../sync/sync/application/handler/ProductSyncHandler.java:23,42,84,191-216` -- payloads, `MERGED_INTO:` alias, `mergeIntoExistingByName`.
- `.../sync/sync/application/service/SyncPushService.java:300-340,396` -- `redirectMergedProductIds` (extend with `merged_into_id`, one query per batch); `SyncOperationsLogRepositoryAdapter.findMergedProductTargets:66`.
- `.../sync/sync/application/provider/ProductDeltaProvider.java:39-63` -- pull keys (additive).
- `.../commerce/sale/application/service/RecordSaleService.java:88-95` -- REST sale product resolution.
- `.../inventory/stock/...` -- stock level read for the merge guard through an inventory api port (no catalog→inventory internals; check Modulith baseline).
- 17.12 reference: `inventory/stock/domain/service/StockTrackingPolicy.java:39-61`, `StockCommandAdapter.java:50`, `ChangeStockTrackingUseCase.java:34,57-104`; tests `StockTrackingFullContextTest.java:263-591`.
- `.../subscription/plan/adapter/in/bootstrap/PlanMigrationJob.java:103-176` + `public.data_migrations` -- one-shot backfill pattern.
- `.../shared/event/catalog/product/*`, `AuditEventListener.java:217-280`, `AuditLabels.java:38-51,141,196` -- events/audit; `keevo/contracts/export-datasets.json` `audit` section must list `PRODUCT_MERGED` (regenerate as previous stories did).
- `ViewerWriteGuardFilter.java:47-58` -- merge POST blocked for VIEWER automatically (no allow-list change).
- Bref reference: `/home/toor/Project/FreeLance/AI/api-bref/src/product/product-key.ts:1-26`, `product-key.spec.ts`, `relational/repositories/product.repository.ts:193-226,352-400`.

## Tasks & Acceptance

**Execution:**
- `db/modules/catalog/V2__products_bref_fields.sql` + `db/migration/V52__public_templates_products_bref_fields.sql` -- columns + indexes.
- catalog domain/persistence/API -- fields, `ProductKeys`, key recomputation, `resolveMerged`, merge use case (+ inventory port for the stock guard), lookup query, event.
- REST controller/DTOs -- lookup, merge, barcode in/out, new fields in responses.
- sync -- payload `barcode`, delta keys, push redirection via `merged_into_id`, merged-product guards in create-or-restore and unarchive.
- backfill job (`product_key`, `price_manual`) with marker.
- errors (`PRODUCT_MERGE_INVALID` 409/422 mapping), audit label, export contract audit section, `docs/entitlements/endpoints.md` regenerated.
- Tests: `ProductKeysTest` (Bref examples), merge use case (every matrix row, lock order, chain flattening), lookup, REST/sync additive shape, push redirection (one query per batch), backfill job idempotence, full-context Postgres test of merge + offline sale redirection + 17.12 non-regression rows (untracked sale, enable with quantity / zero).

**Acceptance Criteria:**
- Given two products merged, when a device that was offline pushes operations on the merged product, then they land on the target and no operation is rejected for that reason.
- Given an existing tenant after deploy, when the backfill job has run, then every product has a `product_key` and `price_manual=true`, and a second run changes nothing.

## Spec Change Log

## Review Triage Log

## Design Notes

**Pattern.** Domain service `ProductKeys` (pure function, ported from Bref with its examples as tests); merge = Command in one transaction with pessimistic locks in id order (no deadlock) and a flat redirection map (`merged_into_id` re-pointing keeps every lookup one hop); redirection centralised in one resolver used by REST and by the existing sync push choke point.

**Complexity.** Lookup: one indexed query (barcode partial index or product_key index) + at most one hop. Push redirection: one `SELECT id, merged_into_id FROM products WHERE id = ANY(?) AND merged_into_id IS NOT NULL` per pushed batch. Merge: 2 locked reads + stock check (one aggregate query) + 3 updates. Backfill: per tenant, keyset batches of 500 (`WHERE product_key IS NULL ORDER BY id`), batch UPDATE — O(products), no per-row query.

**AC « mouvement INITIAL ».** 17.12 records the starting stock as `STOCK_ENTRY` with the note « Stock de départ (activation du suivi) » and skips a zero movement; the epic note (2026-11-01) makes (a)/(b) non-regression of 17.12, so that behaviour is the reference.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/catalog/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then sync, inventory, commerce, shared, architecture one package at a time (full suite package by package before finalizing).

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off): create with barcode, lookup, merge, lookup of the merged barcode returns the target, sync pull shows `mergedIntoId`.
