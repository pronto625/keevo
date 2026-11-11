---
title: '24.3 — Bref import: categories, products merged by key, clients per establishment'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '45eaa89b9ca8124d52e1042957b0f66e60fe93c4'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-2-migration-identite-boutiques-membres.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** 24.2 imports a Bref owner's identity, company, shops and team, but not the catalogue nor the clients, and the imported company has no category at all (no onboarding, so no `TenantOnboardedEvent`). The one-shot cutover (ADR-10, 24.9) needs every owner's products and clients in Keevo, traced and idempotent, before documents (24.4) can link to them.

**Approach:** Three new pipeline steps of the `migration` module (orders 60, 70, 80, the slots 24.2 reserved): categories (sector template + « Produits importés »), products (one Keevo product per Bref `productKey` across the owner's shops, Bref-merged products archived and redirected), clients (one Keevo client per Bref customer, in the store of its shop). The snapshot gains `products` and `customers`, read by both sources (database: two indexed queries; export: the two contract sections). Writes go through new import ports of `catalog`, `crm` (set-based, `ON CONFLICT (id) DO NOTHING`) and a sector-template query of `identity`.

## Boundaries & Constraints

**Always:**
- Keep Bref ids: a product row's id is the id of the Bref product that represents its key group; a client's id is the Bref customer id. Original `createdAt`/`updatedAt` kept.
- Every Bref product and customer row gets exactly one `migration_records` line (source `product` / `customer`, target `products` / `clients`, SHA-256 of the canonical row); merged sources point to their group's product with a French note. The categories step records the « Produits importés » category against the owner (`user` → `categories`).
- Plan before writing: a pure planner computes groups, names, prices and notes from the whole snapshot (deterministic, stable across re-runs), the validator blocks before any write.
- Idempotent: a re-run writes nothing (counted `alreadyImported`) or only the missing rows; the categories step runs once per tenant (its record).
- Set-based: one insert per step and table (`unnest` / `jsonb_to_recordset` … `RETURNING id`) + one records batch; source reads are two queries using Bref's `(shopId, productKey)` and `(shopId, phone)` indexes.
- Imported products: `track_stock = false`, `status = ACTIVE`, category « Produits importés », auto SKU `KEV-XXXXXX` (unique), no event, no plan limit (plan = 24.5), no stock movement.
- Imported clients: `store_id` = the shop's store; no merge across stores; store sharing stays off (ADR-08, set by 24.2); E.164 phone (`phone` and `phone_normalized`).
- No personal data in logs; notes and cases are French labels without values.

**Never:**
- Product sales counters (`timesSold`, `totalQuantity`, `totalRevenue`, `lastSoldAt`) and client counters (`totalInvoices`, `totalSpent`, `lastPurchaseAt`): recomputed from the imported documents by 24.4.
- Documents, payments, expenses (24.4), plan and credit (24.5), reconciliation (24.8), cutover command (24.9).
- `ProductCreatedEvent`, `CategoryCreatedEvent`, client events, plan-limit checks.
- Changes to api-bref (none requested since ADR-10).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | 2 shops; key `nm:riz` in both, `bc:6111…` in one, one product merged into another in shop A; 3 customers, one phone present in both shops | Categories: sector template + « Produits importés »; products: 1 per key group + 1 archived row per merged product (`merged_into_id` = target group's product); records for every Bref product; clients: 3 rows, each in its shop's store; batch COMPLETED with `categoriesCreated`, `productsCreated`, `productsMerged`, `clientsCreated` | — |
| Re-run | Same owner already imported | All creation counts 0, `alreadyImported` includes the categories record, every product and client record | — |
| Partial re-run | A product (new key) and a customer added since | Only that product, its record, that client and its record are written | — |
| Group attributes | Several Bref products, one key | Id, name, created date = oldest (createdAt, id); barcode = first non-blank reference (oldest first); price/`price_manual` = the most recent manual price, else the most recently sold (`lastSoldAt`, then `updatedAt`); archived only if every member is archived; updated date = latest | — |
| Merged in Bref | `mergedIntoId` → live product of the same owner (chains followed, one hop stored) | Own archived row, `merged_into_id` = Keevo product of the final target's group, note « Produit fusionné dans Bref, importé archivé » | Target missing, cyclic or of another owner → archived row without redirection, note `PRODUCT_MERGE_BROKEN` |
| Name clash | Two rows to create with the same lower-cased name (different keys, or merged row vs live row) | Later one renamed « Nom (2) », « (3) »… (≤ 200 chars), note `PRODUCT_RENAMED` | — |
| Long / blank name | Name > 200, or blank | Cut to 200 code points (`PRODUCT_NAME_SHORTENED`); blank → « Produit importé » (`PRODUCT_NAME_MISSING`) | — |
| Price | Decimal with cents; or < 0 or > 2 147 483 647 | Rounded half-up, note `PRODUCT_PRICE_ROUNDED` | Out of range → case `PRODUCT_INVALID`, nothing written |
| Reference | > 100 chars | Not kept as barcode, note `PRODUCT_REFERENCE_DROPPED` | — |
| Unknown shop | A product or customer whose shop is not one of the owner's (export) or duplicated id | — | Case `PRODUCT_INVALID` / `CLIENT_INVALID`, nothing written |
| Client phone | Not readable as E.164 | — | Case `CLIENT_INVALID` « téléphone invalide », nothing written |
| Client fields | Blank name; NIU/RCCM > 50, address > 255, contact > 120, website > 200, name > 200, email > 255 or without « @ »; birth date not ISO or in the future; unknown type | Name ← phone (`CLIENT_NAME_MISSING`); long text cut (`CLIENT_FIELD_SHORTENED`); bad email/birth date/type dropped (`CLIENT_FIELD_DROPPED`) | — |
| Consent | `marketingConsentAt`, `marketingConsentById`, `marketingOptOutAt` | Copied; `marketing_consent_by` kept only when it is the owner or a member of the snapshot, else null | — |
| No sector | No shop category mapped | Only « Produits importés » created | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/ImportStep.java` -- pipeline contract; 24.3 adds `@Order(60/70/80)` steps.
- `.../migration/application/ImportRun.java` -- counters (add `categoriesCreated`, `productsCreated`, `productsMerged`, `clientsCreated`), `target`/`remember`.
- `.../migration/application/step/StoresStep.java`, `MembersStep.java` -- the step pattern to copy (records inserted in the step's tenant transaction, run updated after commit).
- `.../migration/application/step/ProfileStep.java` -- sector of the oldest live shop (`BrefMappings.sector`); reuse the same choice (extract `BrefMappings.ownerSector(shops)`).
- `.../migration/domain/model/BrefSnapshot.java`, `BrefShop.java`, `Canonical.java`, `MigrationRecord.java`, `ImportNote.java`, `BlockedCase.java` -- extend with `BrefProduct`, `BrefCustomer`, constants `BREF_PRODUCT`/`BREF_CUSTOMER`/`PRODUCTS`/`CLIENTS`/`CATEGORIES`, new notes and cases.
- `.../migration/domain/service/BrefSnapshotValidator.java` -- add product/customer checks.
- `.../migration/adapter/out/source/BrefRows.java` -- shared row mapping (add `product`, `customer`, decimals from string or number).
- `.../migration/adapter/out/source/JdbcBrefSource.java` -- add `PRODUCTS_SQL` (`"shopId" = ANY(text[])`, lower- and upper-case ids, numerics as text) and `CUSTOMERS_SQL` (`"shopId" = ANY(uuid[])`), same read-only transaction.
- `.../migration/adapter/out/source/JsonExportBrefSource.java` -- read the `products` and `customers` sections.
- `.../migration/package-info.java` -- allow `catalog :: api`, `crm :: api`.
- `.../catalog/category/application/listener/TenantOnboardedCategorySeeder.java` -- name-idempotent seeding rule to mirror (case-insensitive root names).
- `.../catalog/product/application/usecase/DeriveProductsFromDocumentUseCase.java` -- SKU generation (`KEV-` + 6) and uniqueness check: extract to `catalog/product/domain/service/ProductSkus.java`, reused.
- `.../catalog/product/adapter/out/persistence/JpaProductDerivationRepository.java` -- insert shape of an untracked product.
- `.../crm/contact/domain/entity/Client.java`, `ClientType.java`, `application/service/ClientProfileRules.java` -- client invariants (E.164 phone, limits) the import must respect so rows stay readable.
- `.../identity/onboarding/application/factory/SectorTemplateFactory.java`, `domain/model/SectorType.java` -- sector templates.
- DB: `db/modules/catalog/V1–V2` (`uq_products_name` on `lower(name)`, `fk_products_merged_into`, `idx_products_product_key`), `db/modules/crm/V2` (client Bref columns, `store_id`), `db/modules/store/V3` (`share_clients` default false).
- Bref (`api-bref` `origin/develop` 6faa8b4, journal 18.3 up to date on 11/11): `product` (`shopId` varchar, `productKey`, `reference`, `unitPrice numeric(14,2)`, `priceManual`, `isArchived`, `mergedIntoId` varchar, unique `(shopId, productKey)`), `customer` (`shopId` uuid, `phone`, `type person|company`, `nui`, `rccm`, `address`, `contactName`, `website`, `birthDate` text, `notes`, consent columns, unique `(shopId, phone)`).
- `keevo/backend/src/test/resources/migration/bref-source-schema.sql` -- add the two Bref tables (DDL of the 03/11 dump).
- `keevo/contracts/bref-export.json` -- document the two sections' fields (format stays 1: sections were declared).

## Tasks & Acceptance

**Execution:**
- `migration/domain/model/BrefProduct.java`, `BrefCustomer.java` -- records (Bref field names, canonical form, no personal data in `toString`); `BrefSnapshot` gains `products`, `customers` (old 4-arg constructor kept).
- `migration/adapter/out/source/BrefRows.java`, `JdbcBrefSource.java`, `JsonExportBrefSource.java` -- read both sections, same mapping, same checksums.
- `migration/domain/service/BrefCatalogPlanner.java` -- pure planner: key groups, merge resolution, unique names, prices, notes; returns planned products and the record of every Bref product. O(n log n).
- `migration/domain/service/BrefClientMapper.java` -- pure mapping of a customer to an imported client with its notes (limits of `ClientProfileRules`).
- `migration/domain/service/BrefSnapshotValidator.java` + `BlockedCase.Kind` -- `PRODUCT_INVALID` (unknown shop, duplicate id, price out of range), `CLIENT_INVALID` (unknown shop, duplicate id, phone not E.164).
- `migration/application/step/CategoriesStep.java` (60), `ProductsStep.java` (70), `ClientsStep.java` (80) -- one tenant transaction each (rows + records), run updated after commit.
- `identity/api/SectorTemplatePort.java` + `identity/onboarding/adapter/in/api/SectorTemplateAdapter.java` -- template category names of a sector code (unknown → empty).
- `catalog/api/CatalogImportPort.java` + `catalog/category/adapter/in/api/…` / `catalog/product/adapter/in/api/CatalogImportApiAdapter.java` + `adapter/out/persistence/JdbcCatalogImportWriter.java` -- `seedImportCategories(schema, templateNames, importedName)` (existing root names skipped case-insensitively, one select + one insert) and `importProducts(schema, products)` (SKUs, one insert, returns inserted ids).
- `catalog/product/domain/service/ProductSkus.java` -- SKU generation shared with the derivation use case.
- `crm/api/ClientImportPort.java` + `crm/contact/adapter/in/api/ClientImportApiAdapter.java` + `adapter/out/persistence/JdbcClientImportWriter.java` -- one insert, returns inserted ids.
- Tests: planner and mapper unit tests (every row of the matrix), validator cases, JSON source sections, `BrefOwnerImportFullContextTest` extended (versioned synthetic Bref data: two shops, same key in both, barcode key, merged product, broken merge, name clash, cents, same phone in two shops, bad email, consent), re-run and partial re-run, export = database checksums.
- `docs/entitlements/endpoints.md` regenerated if needed (no new endpoint expected).

**Acceptance Criteria:**
- Given an imported owner whose sector is ELECTRONICS, when the import runs, then the tenant has the ELECTRONICS template root categories and one « Produits importés » category, and every imported product belongs to it.
- Given Bref products of the same `productKey` in two shops, when imported, then one Keevo product (`track_stock = false`) exists and both Bref ids have a record pointing to it.
- Given a Bref product merged into another, when imported, then it exists archived with `merged_into_id` = the Keevo product of its target.
- Given two Bref customers with the same phone in two shops, when imported, then two Keevo clients exist, each with the store of its shop, and both stores keep `share_clients = false`.
- Given the same import re-run, then no product, client or category is written and `alreadyImported` counts their records.
- Given the export of the same owner, then it yields the same products and customers (same canonical forms) as the database source.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 44 findings — high 0, medium 7, low 32, false 5, maybe-false 0 (36 from the four hunting layers + 8 descriptive intent-alignment observations, logged as low rejects)
- findings:
  - **Design layer (FUS-NFR11)**
    - `[medium]` `[patch]` `migration_records` written one row per statement (`batchUpdate` without `reWriteBatchedInserts`): thousands of round trips per owner with the catalogue. Fixed: `JdbcMigrationRecordStore.insert` is one `INSERT … SELECT FROM unnest(…) ON CONFLICT DO NOTHING`.
    - `[low]` `[patch]` Name suffixing O(k²) for k identical names. Fixed: next suffix kept per base name (O(k)).
    - `[low]` `[defer→routed 24-9]` Source rows fully buffered (no fetch size) and products/clients inserted in one unbounded statement. One owner's rows on a restored copy today; relevant only for the cutover over every owner → routed to 24.9 (fetch size + chunks in the cutover command).
    - `[low]` `[reject]` Merge chains re-walked per merged product (O(m²) on long chains). Bref merge chains are 1–2 hops (Bref redirects earlier merges); a memo adds code for no measurable gain.
  - **Blind hunter**
    - `[medium]` `[patch]` Product `shopId` matched only in exact lower/upper case: mixed-case or padded rows silently missing from the database source. Fixed: `lower(btrim("shopId")) = ANY(…)` (same rule as `user.shopId`; per-owner scan documented), test with a padded mixed-case id.
    - `[low]` `[reject]` Stale « Produits importés » category on re-run if the owner deleted it. The tenant stays SUSPENDED (read-only) until activation (24.9); re-runs happen only before activation.
    - `[medium]` `[patch]` Planner ignores names already stored: a new product could take a stored name on a partial re-run and fail the step. Fixed: the step reads the tenant's product names (one query) and the planner reserves them; already-imported rows are not renamed. Tested (unit + full context « COCA (2) »).
    - `[low]` `[reject]` Java `toLowerCase(ROOT)` vs PostgreSQL `lower()` may differ on rare characters. Stored names now come from PostgreSQL `lower()`; a residual mismatch fails the step loudly (no partial write). Residual risk.
    - `[low]` `[patch]` Barcode/key/e-mail length checked in UTF-16 units vs code points. Fixed: code points everywhere.
    - `[low]` `[reject]` Keys over 250 characters cut, which could group two keys. Bref keys are `nm:<name>`/`bc:<code>`; a 250-character product name is not realistic and is already noted when the name is cut.
    - `[low]` `[reject]` Other members' references/names in a key group not kept. By design (D1, one product per key); every source row is traced with its checksum and note « Produit présent dans plusieurs boutiques ».
    - `[low]` `[patch]` Consent author kept without consent date. Fixed: author only with a consent.
    - `[low]` `[reject]` `ClientImportApiAdapter.check` misses notes/future birth date/store existence. Notes are free text (TEXT); the mapper drops future dates; `clients.store_id` has no foreign key and the store comes from the validated shop.
    - `[low]` `[reject]` Client limits duplicated (mapper and adapter). Sharing them needs new `crm :: api` surface; the adapter check refuses any drift loudly before the insert.
    - `[low]` `[reject]` SKU regeneration unchecked twice, no unique index. ~1 in 2 billion per product, same rule as 20.6; the owner's import runs alone (one RUNNING batch).
    - `[low]` `[reject]` Clients present without record recorded without checking the row. Only possible when a record is lost; same id = same Bref row.
    - `[low]` `[patch]` `pending` HashMap gives a non-deterministic record order. Fixed: LinkedHashMap.
    - `[low]` `[patch]` Notes repeated on every re-run. Fixed: notes counted only for rows written by the run.
    - `[low]` `[reject]` Category name > 100 / inactive root category reused. Template names are short constants; the imported tenant is new.
    - `[low]` `[patch]` Missing tests (ProductSkus, adapter check, name stability). Added `ProductSkusTest`, `ClientImportApiAdapterTest`, planner `stableNames`, full-context collision re-run.
  - **Edge-case hunter**
    - `[medium]` `[patch]` Re-run after Bref adds a product named like a stored row → step fails forever. Same fix as the blind-hunter name finding.
    - `[low]` `[reject]` Group representative changes between runs (oldest member merged later). Bref is stopped for the cutover (ADR-10): the source no longer changes between runs; the case leaves a duplicate product at worst, never an FK failure (the old rep row exists).
    - `[low]` `[reject]` Deleted imported category. Same as the blind-hunter finding (tenant suspended).
    - `[medium]` `[patch]` Mixed-case/padded product `shopId`. Same fix as the blind-hunter finding.
    - `[low]` `[patch]` Malformed `marketingConsentById` aborts the snapshot. Fixed: `BrefMappings.uuidOrNull`.
    - `[low]` `[reject]` Raw FK error instead of the controlled refusal when a rep is skipped. With reserved names a rep is no longer skipped; any remaining error still fails the step with nothing committed.
    - `[low]` `[patch]` Notes emitted for already-imported rows. Same fix as above.
    - `[false]` `[reject]` Claim « export and database disagree on shopId » — fixed by the same patch; with it both sources read trimmed, any-case ids (test asserts equal products).
    - `[false]` `[reject]` Claim « untargeted ON CONFLICT silently skips name conflicts » — the step re-checks every planned id and fails the step when one is neither inserted nor present.
  - **Verification gap**
    - `[medium]` `[patch]` Partial re-run with a name collision untested. Added the full-context case and the planner unit test.
    - `[medium]` `[patch]` `ProductSkus.unique` collision branch untested. Added `ProductSkusTest`.
    - `[low]` `[patch]` `ClientImportApiAdapter.check` rejection untested. Added `ClientImportApiAdapterTest`.
    - `[false]` `[reject]` « Unstable names across runs » as a separate defect — same root cause as the name finding, fixed there.
    - `[false]` `[reject]` « DB and export disagree on shopId » — fixed by the shopId patch.
    - `[false]` `[reject]` « BrefRows.customer throws on malformed consent author » — fixed by the uuidOrNull patch.
  - **Intent alignment** (descriptive, logged as rejects)
    - `[low]` `[reject]` Live dump check not in the diff — done: see Auto Run Result (live `bref_src_t243`).
    - `[low]` `[reject]` `share_clients` checked, not written — 24.2 writes it false (ADR-08), 24.3 asserts it.
    - `[low]` `[reject]` Re-run does not update rows already imported (prices, names) — reading « sans effet ou complète les manquants »; the source is frozen at the cutover (ADR-10).
    - `[low]` `[reject]` ProductsStep/ClientsStep/writers only tested end to end — the full-context test runs the real beans on PostgreSQL; pure rules have unit tests.
    - `[low]` `[reject]` Refactors outside the brief (ProductSkus, ownerSector) — behaviour-preserving, covered by the catalog (373) and migration suites.
    - `[low]` `[reject]` `productsMerged` counts key merges only — documented counter (Dashboard section).
    - `[low]` `[reject]` `package-info` mentions 24.9's cutover — documentation of the module's roadmap after ADR-10.
    - `[low]` `[reject]` Readings R4 (oldest representative, final-target redirect) — decisions D1/D2.

## Design Notes

**Patterns:** Pipeline (three new `ImportStep`s, nothing earlier touched); pure Domain Services for planning (`BrefCatalogPlanner`, `BrefClientMapper`) so every rule is unit-tested without I/O; ports and adapters for writes (catalog, crm, identity own their tables); Strategy unchanged for sources (both adapters share `BrefRows`).

**Decisions (taken by default, conservative, 11/11/2026):**
- **D1 Group representative and attributes** — see the matrix (oldest product names the group; the owner's typed price wins over the last sale price). Deterministic, so re-runs and both sources agree.
- **D2 Bref-merged products** get their own archived row (history of 24.4 lines that cite them), redirected one hop to the final target's group; a broken merge is imported archived, unredirected, noted.
- **D3 Name clashes** (Keevo `uq_products_name` is tenant-wide, Bref names are per shop and per key) are resolved by a numbered suffix, noted; never by merging different keys.
- **D4 Prices** are Keevo integers: cents rounded half-up and noted (catalogue price is a suggestion; documents keep Bref amounts, 24.4); out-of-range blocks.
- **D5 Clients** need an E.164 phone (Keevo invariant `Client`, else the row is unreadable): an unreadable phone blocks the owner; other bad optional fields are cut or dropped with a note (same limits as `ClientProfileRules`).
- **D6 Categories** are seeded once per tenant (record of « Produits importés »); « Produits importés » is a custom category (the owner can rename it).
- **D7 Counters** are not imported (24.4 recomputes them from documents).
- **D8 Bref `product.shopId` is text**: the query passes lower- and upper-case shop ids to keep the `(shopId, productKey)` index usable.

**Cost per owner:** source +2 queries; categories: 1 select + 1 insert + 1 records batch; products: 1 SKU check + 1 insert + 1 records batch; clients: 1 insert + 1 records batch. Planner O(n log n), mapper O(n). Memory: the owner's rows only.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t243 migration catalog crm identity/onboarding architecture` -- expected: all green.

**Manual checks:**
- Live: `bref_src_t243` (dump of 03/11, read-only) → backend on 4643 with `keevo_dev_t243`: import twice, aggregates only; then drop both databases.

## Auto Run Result

Status: done.

**Summary.** Three new steps of the Bref import pipeline:
- `CategoriesStep` (60): sector template categories (`SectorTemplatePort`, identity) + « Produits importés », once per tenant (record against the owner) — the item routed by 24.2 is done.
- `ProductsStep` (70): `BrefCatalogPlanner` (one product per `productKey` across shops, Bref merges archived and redirected, unique stable names, integer prices) → `CatalogImportPort.importProducts` (one insert, untracked, auto SKU) + one record per Bref product.
- `ClientsStep` (80): `BrefClientMapper` (ADR-08: same id, shop's store, E.164, limits, consent) → `ClientImportPort.importClients` (one insert) + records.
- Sources: `products` and `customers` read by both adapters (database: two queries; export: two sections, contract updated), same canonical checksums. Validator: `PRODUCT_INVALID`, `CLIENT_INVALID`.
- `migration_records` now written in one statement (review).

**Files:** see Code Map; plus `ProductSkus` (shared with 20.6), `CatalogImportWriter`/`JdbcCatalogImportWriter`, `ClientImportWriter`/`JdbcClientImportWriter`, `SectorTemplateAdapter`, `BrefProduct`, `BrefCustomer`, tests (`BrefCatalogPlannerTest`, `BrefClientMapperTest`, `CategoriesStepTest`, `ProductSkusTest`, `ClientImportApiAdapterTest`, validator/JSON/full-context extensions), test DDL `bref-source-schema.sql` (product, customer), contract `bref-export.json`. No Flyway migration (V85 unused), no new endpoint.

**Review.** 44 findings: 16 patched (7 medium, 9 low), 1 routed to 24.9, 27 rejected with their reasons (Review Triage Log). Patched by verdict: 0 high, 7 medium, 9 low.

**Verification (all green):** migration 37, catalog 375 (after patches), catalog/product/domain 59, crm 241, crm/contact/adapter 140, identity/onboarding 76, architecture 173 (1 skipped, pre-existing).

**Live check** (port 4643, `keevo_dev_t243`, source `bref_src_t243` restored from the 03/11 dump, read through a SELECT-only role; aggregates only):
- Source: 1 owner, 1 shop, 6 products, 3 customers.
- Import 1: 201 COMPLETED in 3.4 s — categoriesCreated 6 (5 ELECTRONICS template + « Produits importés »), productsCreated 6 (all untracked, all in « Produits importés »; Σ prices = Σ rounded Bref prices = 164 200), clientsCreated 3 (store set, E.164), identity as 24.2; only note LOGO_NOT_FETCHED (logo download off).
- Import 2: COMPLETED in 0.04 s, every creation 0, alreadyImported 23 = records (tenants 1, users 4, memberships 4, stores 1, employees 3, categories 1, products 6, clients 3).
- `share_clients` false; no phone number in the backend log; databases and role dropped afterwards.

**Follow-up review recommended:** `true` — seven medium patches touched the write path (single-statement records insert, name reservation on re-runs, product `shopId` matching). The records rewrite also serves the 24.2 steps; it is exercised by the full-context tests and the live run, but no failure-injection test covers it.

**Residual risks:**
- Java vs PostgreSQL lower-casing of exotic characters could make two planned names collide: the step then fails loudly (nothing half-written).
- Product reads scan Bref's `product` table per owner (`lower(btrim(shopId))`); the cutover over every owner (24.9) should read in one pass with a fetch size and chunked inserts (routed).
- A representative change between runs (only if Bref changed between runs) can leave a duplicate product; Bref is stopped during the cutover (ADR-10).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commit de la story 24.3, branche de l'epic 24, en attente de fusion). **Aucun nouvel endpoint ni champ pour l'app** : la story importe des données Bref dans les tables existantes (catalogue 20.1, clients 20.2). Pas de clé de suivi mobile.

### Ce qui est fait côté API

- **Catégories** : une entreprise importée a les catégories de son secteur (comme après un onboarding) et une catégorie « Produits importés » qui contient tous les produits repris de Bref.
- **Produits** : un seul produit par article Bref (même clé dans plusieurs boutiques → un produit), sans suivi de stock (`trackStock = false`), prix entier (centimes arrondis), référence/code-barres repris. Un produit fusionné dans Bref arrive **archivé** avec `mergedIntoId` vers le produit gardé (comportement 20.1 existant). Un nom déjà pris reçoit un numéro : « Savon (2) ».
- **Clients** : un client par client Bref, dans la boutique d'origine (`storeId`), téléphone E.164 ; le même numéro dans deux boutiques donne deux clients (ADR-08) ; partage entre boutiques désactivé ; accord marketing repris.

### À utiliser

Rien de nouveau : pull des produits, catégories et clients (spec-16-10), filtres par boutique des clients (20.2), produits sans suivi (17.12/20.1).

### À implémenter côté mobile

Rien. Vérifier seulement, au premier full pull d'un compte importé, que les produits archivés fusionnés ne s'affichent pas dans les listes actives (règle 20.1 déjà en place).

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref importé puis activé (24.9), **When** il ouvre le catalogue, **Then** il voit ses articles Bref dans « Produits importés », sans stock, et pas les articles fusionnés.
- **Given** un client présent dans deux boutiques Bref, **When** l'owner consulte « tous mes établissements », **Then** il voit deux fiches, chacune rattachée à sa boutique.

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commit de la story 24.3). Mêmes routes que 24.2 (`POST /api/v1/admin/migration/bref/owners/{ownerId}/imports`, `GET /api/v1/admin/migration/batches/{batchId}`, `AdminBrefMigrationController.java`) ; le lot porte de nouveaux compteurs, remarques et cas bloquants.

### Ce qui est fait côté API

Le lot d'import compte et explique maintenant les catégories, les produits et les clients.

### À utiliser

- `counts` (nouvelles clés) : `categoriesCreated`, `productsCreated`, `productsMerged` (articles Bref rattachés à un produit d'une autre boutique, sans fiche propre), `clientsCreated`.
- `notes` (nouveaux `code`, afficher `label (count)`) : `PRODUCT_MERGED_BY_KEY`, `PRODUCT_MERGED_IN_BREF`, `PRODUCT_MERGE_BROKEN`, `PRODUCT_RENAMED`, `PRODUCT_NAME_SHORTENED`, `PRODUCT_NAME_MISSING`, `PRODUCT_PRICE_ROUNDED`, `PRODUCT_REFERENCE_DROPPED`, `CLIENT_NAME_MISSING`, `CLIENT_FIELD_SHORTENED`, `CLIENT_FIELD_DROPPED`, `CLIENT_CONSENT_AUTHOR_UNKNOWN`.
- `cases` (nouveaux `code`, afficher `label`) : `PRODUCT_INVALID` (« Produit invalide : … »), `CLIENT_INVALID` (« Client invalide : téléphone invalide » …). `sourceTable` vaut `product` ou `customer` ; `sourceId` ne s'affiche jamais.

### À implémenter côté dashboard

1. Libellés français des nouveaux compteurs dans l'écran « Migration Bref » : « Catégories créées », « Produits créés », « Produits regroupés (même article dans plusieurs boutiques) », « Clients créés ».
2. Rien d'autre : remarques et cas bloquants s'affichent déjà par leur `label`.

### Critères d'acceptation dashboard

- **Given** un owner avec un même article dans deux boutiques, **When** le super-admin lance l'import, **Then** le lot « Terminé » montre « Produits regroupés : 1 » et la remarque « Produit présent dans plusieurs boutiques : un seul produit importé (1) ».
- **Given** un client Bref au téléphone illisible, **Then** le lot est « Bloqué » avec « Client invalide : téléphone invalide ».

### Clé de suivi dashboard

`d24-3-import-produits-clients`
