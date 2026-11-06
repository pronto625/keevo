---
title: '20.1 — Extended catalogue: products without stock (non-regression), barcode, product key, manual price, merge, barcode lookup'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_revision: 'f2cfd0c070aee28d11e7a9f2244cae31551f793f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred:
  - summary: >-
      Product merge is irreversible but open to the same roles as archive (EMPLOYEE included).
    evidence: |-
      Specified as « roles that may archive »; fine-grained manager/employee rights are the pending product decisions of 19-5b. Routed there.
    location: >-
      keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java
    severity: medium
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

### 2026-11-06 — Review pass
- verdicts: 50 findings — high 0, medium 9, low 33, false 8, maybe-false 0 (intent-alignment observations included)
- findings:
  - Patterns/complexity (FUS-NFR11):
    - `[medium]` `[patch]` barcode lookup: one `findById` per merged candidate (≤ 21 queries) — resolve all targets in one query (≤ 2 queries total).
    - `[medium]` `[patch]` `Product` recomputes the key on every rehydration (lists, exports) — rehydrate the stored `product_key`; compute only on create and on name/barcode change.
    - `[low]` `[patch]` `RecordSaleService` re-resolves merges for every synced sale although the batch already redirected them — skip resolution for sync-originated commands.
    - `[low]` `[patch]` `ProductSyncHandler` archive/unarchive pre-query per operation duplicating use-case guards — drop the pre-checks; map the use case's merged refusal to `REJECTED PRODUCT_MERGED`.
    - `[low]` `[reject]` `OR product_key = 'bc:'||code` redundant today — kept per spec for imported keys (24.3); one indexed BitmapOr on a lookup.
    - `[low]` `[patch]` backfill: one transaction per tenant for all pages — commit per page, `lock_timeout` set.
    - `[medium]` `[patch]` merge stock guard reads `stock_levels` unlocked — lock the source's stock rows `FOR UPDATE` in the merge transaction (grouped with the in-transit entry).
  - Blind hunter:
    - `[low]` `[reject]` unarchive refusal code differs REST (`PRODUCT_MERGE_INVALID`) vs sync (`PRODUCT_MERGED`) — as specified (REST 409 `PRODUCT_MERGE_INVALID`, sync `REJECTED PRODUCT_MERGED`).
    - `[medium]` `[patch]` backfill's blanket `UPDATE … SET price_manual = true WHERE price_manual = false` flips products created after deploy without a price (and reruns while a tenant fails) — drop it; the per-row key backfill already sets `price_manual = true` on pre-existing rows only.
    - `[low]` `[reject]` devices do not re-pull backfilled `productKey`/`priceManual` — not used by apps yet; stated in the mobile section.
    - `[low]` `[patch]` drafts / quick-add default `priceManual = true` without a price — set it explicitly (price > 0 ⇒ true).
    - `[medium]` `[patch]` redirected offline `UPDATE_PRODUCT`/`PROMOTE_PRODUCT` keeps `barcode` and `trackStock` of the merged product (overwrites the target's barcode or tracking) — drop `barcode` and `trackStock` too.
    - `[medium]` `[defer]` merge open to EMPLOYEE (same as archive, as specified) although irreversible — fine-grained role rights are the pending product decisions of 19-5b → routed there.
    - `[medium]` `[patch]` merge guard ignores transfers in transit — refuse when the source has an IN_TRANSIT transfer (same check as the tracking switch).
    - `[low]` `[reject]` REST stock/transfer/count endpoints do not redirect merged ids — merged products are archived and hidden; only stale clients call them; spec scope is sales + sync.
    - `[medium]` `[patch]` redirected sale lines keep the source `variantId` — drop the variant on a merge redirection (REST and sync).
    - `[low]` `[reject]` supplier links/thresholds stay on the source — data kept, history intact; re-linking is a manual owner action.
    - `[medium]` `[patch]` lookup N+1 — grouped with the patterns entry; several products sharing a code → most recent visible one (non-unique by design, documented).
    - `[low]` `[patch]` per-operation sync pre-queries — grouped with the patterns entry.
    - `[low]` `[patch]` backfill locks — grouped. Missing tests (crossed merges, refusals) — patched below; audit only on the source row — `[low]` `[reject]` both names are in the details; endpoints.md/spec outside the diff — `[false]` `[reject]` excluded on purpose, committed with the change.
  - Edge case hunter:
    - `[medium]` `[patch]` unlocked stock check — grouped. `[medium]` `[patch]` transfers in transit — grouped.
    - `[low]` `[reject]` an update that read the source before a concurrent merge can write `archived=false` — narrow race; `merged_into_id` is not writable by JPA so redirection still holds; accepted.
    - `[medium]` `[patch]` variantId on REST and sync redirections — grouped.
    - `[medium]` `[patch]` redirected edits overwrite target barcode/tracking — grouped.
    - `[low]` `[reject]` non-canonical UUID text in payloads — clients send canonical UUIDs (existing alias redirection has the same contract).
    - `[low]` `[reject]` merge committed during a batch — window of one batch; next push lands on the target.
    - `[low]` `[reject]` merge target missing (drifted tenant without FK) in restore paths — FK present on every normal tenant; drifted copies cannot be merged into.
    - `[low]` `[reject]` CreateOrRestore with missing target — same.
    - `[low]` `[reject]` code mismatch — as specified (see above).
    - `[medium]` `[patch]` backfill flips new products — grouped.
    - `[low]` `[reject]` tenant without V2 columns keeps the job failing — V2 is a foundation script applied at boot before the job.
    - `[low]` `[patch]` key longer than 250 after NFD expansion — truncate to 250.
    - `[low]` `[patch]` REST `@Size` on the raw barcode before trim — rely on `ProductKeys.cleanBarcode`.
    - `[medium]` `[defer]` merge open to EMPLOYEE — grouped (→ 19-5b).
    - `[low]` `[reject]` `UNARCHIVE_PRODUCT` on a merged product rejected vs « no operation rejected » — the spec's explicit rule refuses resurrecting a merged product; a stale (pre-merge) unarchive is accepted as a no-op.
    - `[false]` `[reject]` NBSP-only names keep a null key — a blank name is refused at creation (`@NotBlank` + domain trim check); no such row.
    - `[false]` `[reject]` (duplicate) endpoints.md not in the diff — generated file, committed.
  - Verification gap:
    - `[low]` `[patch]` PATCH with `trackStock` + valid barcode untested — add the case.
    - `[low]` `[patch]` re-creating a merged name whose target was later archived untested (REST and sync) — add both cases.
    - `[low]` `[patch]` threshold DTO JSON test never uses non-default 20.1 values — use the full constructor.
    - `[low]` `[reject]` backfill rerun flips prices — grouped with the medium entry (patched).
    - `[low]` `[patch]` drafts `priceManual` — grouped.
    - `[false]` `[reject]` unused `ProductCommandPort.merge` — kept as the module's public command for 24.3; covered by the use-case tests.
  - Intent alignment (descriptive):
    - `[false]` `[reject]` « mouvement INITIAL » / zero quantity — epic note 2026-11-01: (a)/(b) are 17.12 non-regression (Design Notes).
    - `[false]` `[reject]` threshold/export/inventory clauses not re-tested here — pinned by 17.12's `StockTrackingFullContextTest`.
    - `[false]` `[reject]` Bref `track_stock=false` defaults at import/derivation not exercised — owned by 24.3 / 20.6.
    - `[low]` `[reject]` `GET /products/{id}` not redirected — returns `mergedIntoId`; clients follow it (mobile section).
    - `[low]` `[reject]` pull field name unverified against apps — V1 ignores unknown keys; RN contract in the mobile section.
    - `[false]` `[reject]` merge not key-driven — the AC only needs the key to exist; auto-merge is excluded.
    - `[low]` `[reject]` lookup returns 404 for an archived (unmerged) product — an archived product is not sellable; intended.
    - `[low]` `[patch]` Flutter test client export contract test will fail on `PRODUCT_MERGED`/`mergedInto` — sync the test client's mirror (as in 17-2) with a pubspec bump.

## Design Notes

**Pattern.** Domain service `ProductKeys` (pure function, ported from Bref with its examples as tests); merge = Command in one transaction with pessimistic locks in id order (no deadlock) and a flat redirection map (`merged_into_id` re-pointing keeps every lookup one hop); redirection centralised in one resolver used by REST and by the existing sync push choke point.

**Complexity.** Lookup: one indexed query (barcode partial index or product_key index) + at most one hop. Push redirection: one `SELECT id, merged_into_id FROM products WHERE id = ANY(?) AND merged_into_id IS NOT NULL` per pushed batch. Merge: 2 locked reads + stock check (one aggregate query) + 3 updates. Backfill: per tenant, keyset batches of 500 (`WHERE product_key IS NULL ORDER BY id`), batch UPDATE — O(products), no per-row query.

**AC « mouvement INITIAL ».** 17.12 records the starting stock as `STOCK_ENTRY` with the note « Stock de départ (activation du suivi) » and skips a zero movement; the epic note (2026-11-01) makes (a)/(b) non-regression of 17.12, so that behaviour is the reference.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/catalog/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then sync, inventory, commerce, shared, architecture one package at a time (full suite package by package before finalizing).

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off): create with barcode, lookup, merge, lookup of the merged barcode returns the target, sync pull shows `mergedIntoId`.

## Auto Run Result

Status: done

**Summary.** Products gain `barcode` (free reference or barcode, ≤ 100), `product_key` (Bref rules: `bc:<barcode>` for 8–14 digits, else `nm:<normalised name>`), `price_manual` and `merged_into_id` (catalog V2, public mirror V52). The key is computed on create and on name/barcode change and rehydrated from the column otherwise; a one-shot job keyed existing rows (`price_manual=true` for them). New `GET /api/v1/products/lookup?barcode=` (≤ 2 queries, follows the merge) and `POST /api/v1/products/{id}/merge` `{targetId}`: both rows locked in id order, source stock rows locked, refused when the source holds stock or a transfer in transit, already merged or archived target; the source is archived and redirected, chains stay flat, the target inherits the barcode, audited « Produit fusionné ». Every later reference to the merged product lands on the target: REST sales and sync push (one query per batch; redirected lines drop the variant, redirected edits drop name/SKU/barcode/tracking), re-creation by name; unarchive of a merged product refused. Untracked-product rules of 17.12 re-asserted (no stock check, movement or level row; starting stock recorded as « Stock de départ »). The Flutter test client's export/audit mirror was synced (2.0.24+96).

**Files.** New: catalog `V2__products_bref_fields.sql`, `V52__public_templates_products_bref_fields.sql`, `ProductKeys`, `MergeProductsUseCase`, `LookupProductByBarcodeUseCase`, `ProductKeyBackfillJob`, `ProductMergedEvent`, `ProductStockPresence` (catalog api) + inventory `CatalogStockPresenceAdapter`, tests (`ProductKeysTest`, `MergeProductsUseCaseTest`, `LookupProductByBarcodeUseCaseTest`, `ProductBrefFieldsUseCasesTest`, `ProductControllerBrefTest`, `ProductSyncHandlerBrefTest`, `SyncPushServiceMergeRedirectTest`, `CatalogueBrefFullContextTest`, `ProductKeyBackfillJobPostgresTest`, `MergeProductsConcurrencyPostgresTest`). Changed: `Product`, JPA entity/repositories/adapters, `ProductQueryPort`/`ProductCommandPort`/`ProductView`, `ProductController` + DTOs, `CreateOrRestoreProductUseCase`, `UpdateProductUseCase`, `UnarchiveProductUseCase`, `ArchiveProductUseCase`, `CreateDraftProductUseCase`, `ProductSyncHandler`, `SyncPushService`, `ProductDeltaProvider`, `RecordSaleService` (+ input/command flag), `SaleSyncHandler`, `ProductThresholdResponseDto`, `StockLevelRepository`, error codes, audit labels, `export-datasets.json`, tenant-shape fixture, `docs/entitlements/endpoints.md`; `keevo/app` audit/export mirror + version.

**Review.** 50 findings (5 layers): 0 high, 9 medium, 33 low, 8 false. Patched: 6 medium entries (backfill no longer flips new products; redirected edits keep the target's barcode/tracking; redirected lines drop the variant; merge stock guard locked + transfers in transit; lookup ≤ 2 queries; key rehydrated instead of recomputed) and 10 low. Deferred: 1 medium → 19-5b (who may merge). Rejected: 33 (reasons in the triage log).

**Follow-up review: recommended (true)** — 6 medium patches; unverified risk: concurrent stock writers vs. the merge lock (row lock on `stock_levels` covers existing rows only; a first stock entry racing a merge is not covered) and the sync redirection rules for partially merged batches.

**Verification.** Full suite package by package: 5 965 tests, 0 failures. Flutter test client: `local_export_contract_test.dart` + audit tests green. keevo_dev: catalog V2 history rows removed and V2 re-applied on 362 tenants, 0 failed (the first V2 version had been applied during the device test). Live (WhatsApp noop, FCM/Expo off): create with barcode → `bc:6111234567890`, without → `nm:creme brulee maison 20`, `priceManual: true`; 101-character barcode → 422; lookup → product, unknown → 404 « Aucun produit avec ce code »; merge self → 422, A→B → 200 (B gets the barcode), lookup of A's barcode → B, merge again → 409, merge of a product holding stock → 409 « Ce produit a encore du stock… »; re-creating A's name → answered with B, A stays archived; audit `PRODUCT_MERGED`; sync pull of A → `archived: true, mergedIntoId: B`.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `__COMMIT__`), branche `refonte/modulaire` / `develop`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** un produit peut porter une référence ou un code-barres ; on retrouve un produit en scannant son code ; deux produits en double peuvent être fusionnés : le doublon disparaît du catalogue, ses ventes passées restent, et tout ce qui le visait encore (ventes hors ligne comprises) va sur le produit gardé.

**À utiliser :**
1. Champs ajoutés aux produits (REST `GET/POST/PATCH /api/v1/products`, synchro `CREATE_PRODUCT`/`UPDATE_PRODUCT`, pull `products`) : `barcode` (texte ≤ 100, facultatif ; absent = inchangé, vide = effacé), `productKey` (lecture seule), `priceManual` (lecture seule), `mergedIntoId` (lecture seule, présent sur un produit fusionné). Erreur 422 `VALIDATION_ERROR` « La référence ou le code-barres ne doit pas dépasser 100 caractères ».
2. Scan : `GET /api/v1/products/lookup?barcode=<code>` → le produit à vendre (déjà redirigé si le code appartenait à un produit fusionné) ; 404 `PRODUCT_NOT_FOUND` « Aucun produit avec ce code » ; 422 si le code est vide.
3. Fusion : `POST /api/v1/products/{id}/merge` corps `{"targetId":"<produit gardé>"}` (`{id}` = le doublon) → 200 avec le produit gardé. Erreurs : 422 (même produit), 404 (produit inconnu), 409 `PRODUCT_MERGE_INVALID` avec le message (doublon déjà fusionné, produit gardé archivé, « Ce produit a encore du stock : mettez-le à zéro avant de le fusionner », « Ce produit a un transfert en cours : terminez-le avant de le fusionner »). Lecteur : 403.
4. Restaurer un produit fusionné : REST 409 `PRODUCT_MERGE_INVALID` « Ce produit a été fusionné avec « X » » ; synchro `REJECTED` `PRODUCT_MERGED`.
Code : `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java`, `MergeProductsUseCase.java`, `LookupProductByBarcodeUseCase.java`, `ProductKeys.java`.

**À implémenter côté mobile :**
1. Champ « Référence ou code-barres » dans la fiche produit ; scan à la caisse via `lookup` (hors ligne : chercher d'abord dans le catalogue local par `barcode`).
2. Écran « Fusionner avec… » depuis la fiche d'un produit (choisir le produit gardé, confirmation expliquant que l'opération est définitive).
3. Au pull, un produit avec `mergedIntoId` : le retirer des listes et rediriger localement toute référence (panier en cours, raccourcis) vers `mergedIntoId`. Les opérations déjà en file peuvent garder l'ancien identifiant : le serveur les redirige.
4. Aucune donnée technique à l'écran (ni `productKey`, ni identifiants).

**Critères d'acceptation mobile :**
- Given deux produits en double, when l'owner fusionne A dans B, then A disparaît du catalogue après synchro et scanner le code de A ouvre B.
- Given une vente hors ligne de A faite avant la synchro, when elle remonte, then elle est enregistrée sur B.
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"targetId":"<B>"}' $API/api/v1/products/<A>/merge`.

**Clé de suivi mobile :** `m20-1-catalogue-sans-stock-cle-fusion`.
