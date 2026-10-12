---
title: 'v1s-18-2 — Exportable datasets: one ExportDatasetProvider per domain'
type: 'feature'
created: '2026-10-12'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-18-1-export-module-foundation.md'
warnings: [oversized]
deferred: []
baseline_revision: '7febe521eea33a790a16f4ec2fd99cd97831cc51'
---

<intent-contract>

## Intent

**Problem:** FR94 — an owner must be able to export the whole of their business data (products, stock, movements, sales, closures, inventories, clients, suppliers, team, audit log), filtered by store and period. Story 18-1 delivered the export engine with one demo dataset (`stores`); no business dataset exists yet, and the audit-log screen (18-7) needs an « Exporter » dataset honouring its own filters (employee, action, object type, search) for 18-4.

**Approach:** One `ExportDatasetProvider` per domain, each in its owning module under `adapter/out/export/`, built on a shared keyset helper (native SQL through the tenant-routed `EntityManager`, N-column keyset cursor, typed value converters) so every provider is a declarative column list + SQL. The port gains filter metadata (store/period applicability, dataset-specific criteria) and `ExportFilter` gains a `criteria` map, validated by `ExportService`, persisted on background jobs (Flyway V16 + tenant DDL), exposed by `GET /api/v1/exports/datasets`, and pinned by a golden schema test that is the documented contract for the on-device export (18-5).

## Boundaries & Constraints

**Always:**
- Providers read only through the injected `EntityManager` (tenant search_path), never `JdbcTemplate`/`DataSource`; one `@Transactional(readOnly = true)` per `count`/`readPage`; at most `limit` rows per page (fetch `limit + 1`); keyset strictly increasing on a unique tuple ending with a primary key; never load a whole table. Every keyset expression is non-null (wrap joined text in `COALESCE(…, '')`).
- Column keys are stable camelCase (`[a-z][a-zA-Z0-9]*`), labels in French, types from `ExportColumnType`. Amounts are `AMOUNT_XAF` integers (`Long`), quantities `INTEGER`, instants `DATETIME` (`Instant`, rendered WAT by the renderers), calendar dates `DATE` (`LocalDate`), flags `BOOLEAN`. Enum codes are rendered as French labels (see Design Notes); unknown codes fall back to the raw code.
- Filter semantics (exposed per dataset): **event datasets** filter the period on their event column and the store on their store column; **reference datasets** ignore the period (they are a snapshot of current data) and filter the store only when store-scoped. `stores` follows the reference rule (its 18-1 period filter on `created_at` is removed — update the 18-1 test accordingly).
- Archived / inactive / draft / cancelled rows are exported (FR94 « intégralité »), with an explicit status or « Archivé » column; nothing is silently dropped. No secret is ever exported (no `password_hash`, lock counters, tokens, refresh state, FCM tokens).
- Actor names resolve as the audit adapter does: `employees` row by `user_id` (LATERAL, latest `updated_at`, `LIMIT 1` — never duplicates rows), else « Propriétaire » when `public.users.role = 'OWNER'`, else null. `sales.employee_id`, `*.actor_id`, `started_by`, `counted_by`, `audit_log.user_id` are **user ids**.
- Unknown criteria key for a requested dataset, or a malformed criteria value (bad UUID, `q` > 100 chars) → 400 `VALIDATION_ERROR` before any rate-limit slot is consumed.

**Never:**
- No change to the renderers' cell formats (CSV/XLSX conventions of 18-1 stay as they are), no PDF (18-3), no app/dashboard change (18-4/18-5).
- No product-variant dataset: the server schema has no variant catalogue (only `variant_id` references); `inventory_counts.variant_label` is exported where it exists.
- No new dependency on `reporting` from domain modules; the export module still reads no domain table.
- Do not run the whole Maven suite; do not touch Docker containers.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Store + period on event dataset | `sales` with storeId=S1, from/to = this month | only S1 sales whose `COALESCE(occurred_at, created_at)` is inside the period | — |
| Period on reference dataset | `products` with from/to | every product (archived included), period ignored; with storeId the stock column is that store's quantity | — |
| Store on transfers | `stock_transfers`, storeId=S1 | transfers whose source **or** destination is S1 | — |
| Sale lines | `sale_items`, storeId + period | lines of the matching sales only | — |
| Inventory counts | `inventory_counts`, storeId + period | counts of sessions of S1 started in the period | — |
| Audit criteria | `audit_log`, criteria `{userId, action, entityType, q}` + store + period | same rows as `GET /api/v1/audit` with the same filters, newest first | bad UUID / unknown key → 400 `VALIDATION_ERROR` |
| Criteria on wrong dataset | `products` with criteria `{action: "X"}` | — | 400 `VALIDATION_ERROR` |
| Tenant isolation | tenants A and B, same datasets | A's files contain no B row (marker names) | — |
| Keyset pagination | page limit 1, ties on the leading keyset column | every row exactly once, in order | malformed cursor → `IllegalArgumentException` |
| Background job with criteria | Premium, > threshold, criteria set | criteria persisted on `export_jobs.criteria` and applied by the runner | — |
| Datasets listing | `GET /api/v1/exports/datasets` | each dataset with columns + `storeFilter`, `periodFilter`, `criteria` keys | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/application/port/export/` -- `ExportDatasetProvider` (add defaults `supportsStoreFilter()` true, `supportsPeriodFilter()` true, `Set<String> criteriaKeys()` empty), `ExportFilter` (add `Map<String,String> criteria`, keep a 3-arg constructor and `none()`; null → empty, unmodifiable; helper `criterion(key)`).
- `store/store/adapter/out/export/StoresExportDatasetProvider.java` -- the 18-1 template (keyset `(created_at,id)`, `Position` cursor, `toInstant`); refactor onto the shared helper.
- `shared/infrastructure/persistence/impl/AuditLogRepositoryAdapter.java` (`SELECT_ENRICHED`, `LABEL_EXPR`, `search(AuditQuery, cursor, page, size)` keyset `(occurred_at,id) DESC`, `escapeLike`) + `shared/infrastructure/web/AuditController.java:63-110` (filter params and validation: from/to/storeId/userId/action/entityType/entityId/q ≤ 100) -- reuse for the audit dataset; add a `count(AuditQuery)` sharing the same WHERE builder.
- Tables (tenant DDL `shared/infrastructure/persistence/TenantSchemaProvisioner.java`): `products` :130 (+ `minimum_threshold`), `categories` :113, `stock_levels`, `stock_movements` (types incl. `SALE_CANCELLED`), `stock_transfers` (status COMPLETED/PENDING_SYNC/CONFLICT/IN_TRANSIT), `clients`, `suppliers`, `product_suppliers`, `sales` (+ status, occurred_at, discount_amount), `sale_items` (+ catalogue_unit_price), `employees`, `day_closures`, `inventory_sessions`, `inventory_counts`, `reports` (+ actor_name), `export_jobs` :783 (`DDL_EXPORT_JOBS`); `TenantSchemaSyncService` (`REQUIRED_TENANT_TABLES_DDL`, column sync) for the new column.
- Owning modules: `catalog/product`, `catalog/category`, `catalog/stock` (levels, movements, transfers), `catalog/contact` (clients, suppliers), `commerce/sale` (sales, sale items, day closures), `inventory/counting`, `identity/employee`, `store/store`, `reporting/report` (reports), `shared` (audit).
- Enum sources for French labels: `commerce/sale/domain/model/PaymentMode` (CASH, MOBILE_MONEY), `catalog/stock/domain/entity/MovementType`, `reporting/report/domain/model/ReportType`, report `delivery_status` values in `reporting/report`.
- `reporting/export/application/service/ExportService.java` (`validate` :197, filter checks :210-240), `domain/model/ExportRequest`, `ExportJob`, `domain/event/DataExportedEvent`, `adapter/in/rest/ExportController` + `dto/ExportRequestDto`, `dto/ExportDatasetDto`, `adapter/out/persistence/ExportJobJpaEntity` + adapter, `ExportJobRunner` (rebuilds the filter from the job), `shared/infrastructure/web/AuditEventListener.on(DataExportedEvent)` (value_after).
- `keevo/backend/src/main/resources/db/migration/V15__export_jobs.sql` -- last migration; next is `V16`. `ddl-auto=validate` checks entities against `public`.
- Tests: `reporting/export/integration/ExportPostgresIntegrationTest.java` (extends `FullContextIntegrationTest`, `TenantFactory`, `directCsv`, `filters()` test to adapt), `reporting/export/application/service/ExportServiceTest.java`, `ExportControllerTest.java`, `ExportDatasetRegistryTest.java`; XLSX read-back pattern in `XlsxExportRendererTest`.

## Tasks & Acceptance

**Execution:**
- `shared/application/port/export/ExportDatasetProvider.java`, `ExportFilter.java` -- add filter metadata and criteria (see Code Map) -- datasets declare which filters they honour.
- `shared/infrastructure/export/NativeKeysetExportDataset.java` (new, name free) + `ExportValues.java` -- reusable base/helper: given select list, FROM/JOIN clause, fixed WHERE, store predicate (nullable), period expression (nullable), criteria → predicate mapping, ordered keyset expressions with their types (TIMESTAMP, DATE, TEXT, UUID), and a row mapper, it implements `count` and `readPage` (fetch `limit+1`, tuple comparison `(k1,…,kn) > (:k1,…,:kn)`, Base64URL JSON-free cursor of the typed values, malformed cursor → `IllegalArgumentException`); converters `toInstant`, `toLocalDate`, `toLong`, `str`, `frLabel(map, code)` -- one implementation of the keyset/tenant contract instead of sixteen.
- One provider per dataset (each `@Component`, in `<module>/adapter/out/export/`), keys/labels/columns per Design Notes: `catalog/product` → `products`; `catalog/category` → `categories`; `catalog/stock` → `stock_levels`, `stock_movements`, `stock_transfers`; `catalog/contact` → `clients`, `suppliers`; `commerce/sale` → `sales`, `sale_items`, `day_closures`; `inventory/counting` → `inventory_sessions`, `inventory_counts`; `identity/employee` → `employees`; `store/store` → `stores` (refactored, period ignored); `reporting/report` → `reports`; `shared/infrastructure/export` (or `shared/infrastructure/persistence/export`) → `audit_log` via `AuditPort` search/count.
- `ExportService` / `ExportRequest` / `ExportRequestDto` (`criteria` object of string values) / `ExportController` -- validate criteria keys against every requested dataset's `criteriaKeys()` and values (UUID keys `userId`, `entityId`; `q` ≤ 100; `action`/`entityType` ≤ 80) → 400 `VALIDATION_ERROR`; pass criteria to providers; include criteria in `DataExportedEvent` and the `DATA_EXPORTED` value_after.
- `ExportDatasetDto` -- add `storeFilter`, `periodFilter` (booleans) and `criteria` (keys).
- `ExportJob` + `ExportJobJpaEntity` + adapter + `ExportJobRunner` -- persist/restore criteria as JSON text in `criteria`.
- `db/migration/V16__export_jobs_criteria.sql` (`ALTER TABLE public.export_jobs ADD COLUMN IF NOT EXISTS criteria TEXT`) + `TenantSchemaProvisioner` (`DDL_EXPORT_JOBS` column + idempotent `ALTER … ADD COLUMN IF NOT EXISTS`) + `TenantSchemaSyncService` -- background jobs keep their criteria.
- Tests (RED first): helper unit tests (cursor round-trip, malformed cursor, typed keyset params); `ExportDatasetCatalogTest` golden schema — every provider bean's key, label, column keys/labels/types and filter metadata asserted against a fixed table (this is the documented, stable contract for 18-5); `ExportServiceTest` criteria validation cases; `ExportControllerTest` criteria body + datasets listing metadata; `ExportDatasetsPostgresIntegrationTest extends FullContextIntegrationTest` — two tenants (Premium subscription for XLSX), each seeded with ≥ 1 row in every table across two stores and two periods (inside/outside), then for **every registered dataset**: CSV and XLSX direct export, header = labels, row counts as expected, no tenant-B marker, store filter and period filter honoured per the dataset's metadata, archived/cancelled rows present, amounts integer, audit criteria filter, keyset with `limit 1` returns every row exactly once; V16 column present in public and tenant `export_jobs`, background job round-trips criteria; adapt `ExportPostgresIntegrationTest.filters()` to the reference-dataset rule.

**Acceptance Criteria:**
- Given a store and a period filter, when exporting any dataset, then only rows matching the filters that the dataset declares are included, and the dataset listing states which filters apply.
- Given the provider catalogue, when the golden schema test runs, then every dataset's columns (keys, French labels, types) match the documented schema of this spec.
- Given any dataset with amounts and dates, when exported in CSV and XLSX, then amounts are integer XAF and datetimes are rendered in WAT by the 18-1 renderers.
- Given the « Journal d'audit » dataset with employee/action/object/search criteria, when exported, then the rows equal those of `GET /api/v1/audit` with the same filters.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 30 findings — high 0, medium 0, low 17, false 13, maybe-false 0
- findings:
  - `[false]` `reject` (intent) Products carry no variant data — the server schema has no variant catalogue (only `variant_id` references, no app-side variant table either); `inventory_counts.variant_label` is exported.
  - `[false]` `reject` (intent) Reference datasets ignore the period / non-store-scoped ones ignore the store — deliberate reading recorded in Design Notes and disclosed per dataset (`storeFilter`/`periodFilter`); not a defect.
  - `[false]` `reject` (intent) "Same schema as 18.5" not cross-checked — 18.5 is not built; the golden schema test is the contract it must follow (18.5 brief already says « schéma de colonnes de 18.2 »).
  - `[low]` `reject` (intent) Postgres test enters at the use case, not HTTP — HTTP parsing/validation is covered by ExportControllerTest and OWNER-only by the 18-1 security slice; adding a JWT harness is out of proportion.
  - `[low]` `reject` (intent) Audit parity checked against `AuditPort.search`, not the controller — the controller maps its params 1:1 to `AuditQuery`; the port is the shared query.
  - `[false]` `reject` (intent) WAT offset asserted in CSV only — XLSX stores the WAT-converted value through the same 18-1 `ExportCells` conversion, already tested by XlsxExportRendererTest.
  - `[false]` `reject` (intent) Employee phone exported — the owner created these accounts and calls the team by phone; « sans données sensibles » = no credentials/lock state/tokens, none exported.
  - `[false]` `reject` (intent) Criteria/V16/`/datasets` metadata beyond the literal intent — required by the intent's audit filters (employee/action/object) and by background jobs.
  - `[false]` `reject` (intent) Chunked-lot process not visible in the diff — lots were run (see Verification).
  - `[low]` `patch` (edge) Corrupt `export_jobs.criteria` JSON breaks the whole job listing — parse failure now logs WARN and yields empty criteria.
  - `[false]` `reject` (edge) `quantity * buyPrice` overflow — both are INTEGER columns (≤ 2^31), their product fits in a long.
  - `[low]` `reject` (edge) Export trims action/entityType criteria while GET /audit does not — only differs for values with surrounding spaces, which no client sends; trimming is the safer behaviour.
  - `[low]` `reject` (edge) `products.storeFilter=true` without narrowing rows — documented semantics (the store selects the stock column); the flag tells 18-4 the store matters for this dataset.
  - `[low]` `reject` (blind) No `(created_at,id)`/expression indexes behind keysets — each page is a top-N sort over the filtered set; 50k rows ≈ 50 pages × 50k row visits, acceptable for SME tenant volumes; new indexes need tenant DDL for a speculative gain.
  - `[low]` `reject` (blind) `count()` executes display joins — LATERAL lookups are index-backed (`idx_employees_user_id`) and count runs once per Premium request.
  - `[low]` `reject` (blind) `stock_levels` keyset on mutable names — only a rename during a running multi-page background job could skip/duplicate a line; rare, and the human-friendly order is worth it.
  - `[false]` `reject` (blind) Audit action/entityType exported as raw codes — the schema declares `action:Action (code)`; French action wording lives in the app (18-7) and has no backend source.
  - `[low]` `reject` (blind) `AuditPort.count` default throws — only test doubles lack it; making it abstract breaks unrelated anonymous fakes for no runtime gain.
  - `[low]` `reject` (blind) Criteria are request-global (audit filter + other datasets → 400) — explicit matrix row; routed to 18-4 so the screen sends criteria only with « Journal d'audit ».
  - `[low]` `reject` (blind) Client aggregates ignore store/period — reference snapshot by design (lifetime totals).
  - `[low]` `patch` (blind) `ExportCriteria` in the domain model imports a port — moved to `reporting/export/application/service`.
  - `[low]` `reject` (blind) Criteria normalisation duplicated in controller and service — the controller pre-check gives 400 (service-level VALIDATION_ERROR maps to 422), same pattern as 18-1 `from > to`; both call the same `ExportCriteria`.
  - `[low]` `patch` (blind) Corrupt criteria row breaks every job read — same root cause as edge #1, patched there.
  - `[low]` `patch` (blind) Audit parity under-tested (count vs search, entityId, keyset with filters) — count equality, entityId case and filtered limit-1 keyset pass added.
  - `[low]` `reject` (blind) Code→label maps (e.g. `DAILY_COMBINED`) not in the golden test — labels are documented in the providers; routed to 18-5 to reuse them verbatim.
  - `[low]` `reject` (blind) Actor-name SQL differs from the audit adapter (no actorLabel fallback) — event tables carry no metadata; the employee/« Propriétaire » resolution is the same rule.
  - `[low]` `patch` (verification-gap) « Stock bas » and per-store threshold override unasserted — assertions + seeded override added.
  - `[low]` `patch` (verification-gap) Clients COMPLETED-only aggregate unexercised — client attached to a cancelled sale, totals unchanged asserted.
  - `[low]` `patch` (verification-gap) Actor LATERAL non-duplication untested — second, older employee row seeded; counts and newest name still asserted.
  - `[low]` `patch` (verification-gap) Employee phone column unasserted — phone assertion added.

## Design Notes

**Dataset schema** (key « label » [filters] — columns `key:Label:TYPE`; E = event dataset, R = reference). Keyset in parentheses.

- `products` « Produits » [R, store → stock column only] (`p.created_at, p.id`): id:Identifiant:TEXT, sku:Référence:TEXT, name:Nom:TEXT, category:Catégorie:TEXT, status:Statut:TEXT (Actif/Brouillon), salePrice:Prix de vente:AMOUNT_XAF, buyPrice:Prix d'achat:AMOUNT_XAF, transportCost:Frais de transport:AMOUNT_XAF, minimumThreshold:Seuil minimum:INTEGER, stockQuantity:Stock:INTEGER (sum of `stock_levels`, or the store's), suppliers:Fournisseurs:TEXT (names joined « , »), archived:Archivé:BOOLEAN, createdAt:Créé le:DATETIME, updatedAt:Modifié le:DATETIME.
- `categories` « Catégories » [R] (`created_at, id`): id, name:Nom, parent:Catégorie parente, active:Active:BOOLEAN, custom:Personnalisée:BOOLEAN, createdAt:Créée le.
- `stock_levels` « Stocks » [R, store] (`store name, product name, sl.id`): storeId:Identifiant boutique, store:Boutique, productId:Identifiant produit, sku:Référence, product:Produit, category:Catégorie, quantity:Quantité:INTEGER, minimumThreshold:Seuil minimum:INTEGER, stockStatus:État:TEXT (Rupture if ≤ 0, Stock bas if ≤ threshold and threshold > 0, OK), buyPrice:Prix d'achat:AMOUNT_XAF, stockValue:Valeur du stock:AMOUNT_XAF (quantity × buy price, 0 when quantity < 0), productArchived:Produit archivé:BOOLEAN, updatedAt:Mis à jour le.
- `stock_movements` « Mouvements de stock » [E occurred_at, store] (`occurred_at, id`): id, occurredAt:Date:DATETIME, store:Boutique, sku, product:Produit, type:Type (Vente, Entrée de stock, Transfert entrant, Transfert sortant, Ajustement, Annulation de vente), quantityBefore:Quantité avant, quantityChange:Variation, quantityAfter:Quantité après, actor:Auteur, notes:Notes.
- `stock_transfers` « Transferts » [E occurred_at, store = source OR destination] (`occurred_at, id`): id, occurredAt:Date, sourceStore:Boutique d'origine, destinationStore:Boutique de destination, sku, product:Produit, quantity:Quantité, status:Statut (Terminé, En attente de synchronisation, En conflit, En transit), actor:Auteur, notes:Notes.
- `sales` « Ventes » [E `COALESCE(occurred_at, created_at)`, store] (same expr, `id`): id:Identifiant, occurredAt:Date, store:Boutique, employee:Vendeur, client:Client, status:Statut (Validée, Annulée, En attente de validation), paymentMode:Mode de paiement (Espèces, Mobile Money), itemCount:Articles:INTEGER (sum of quantities), discount:Remise:AMOUNT_XAF, total:Montant total:AMOUNT_XAF.
- `sale_items` « Lignes de vente » [E sale date, store of the sale] (`sale date, sale id, item id`): saleId:Vente, occurredAt:Date, store:Boutique, employee:Vendeur, saleStatus:Statut de la vente, productId:Identifiant produit, sku:Référence (current product, may be empty), product:Produit (snapshot name), quantity:Quantité, cataloguePrice:Prix catalogue:AMOUNT_XAF, unitPrice:Prix appliqué:AMOUNT_XAF, subtotal:Sous-total:AMOUNT_XAF.
- `day_closures` « Clôtures » [E closed_at, store] (`closed_at, id`): id, closureDate:Jour:DATE, closedAt:Clôturée le, store:Boutique, automatic:Automatique:BOOLEAN, actor:Clôturée par, salesCount:Nombre de ventes, revenue:Chiffre d'affaires:AMOUNT_XAF, cashAmount:Espèces:AMOUNT_XAF, momoAmount:Mobile Money:AMOUNT_XAF, pendingSalesCount:Ventes en attente, pendingSalesTotal:Montant en attente:AMOUNT_XAF, topProduct:Meilleur produit, topProductQuantity:Quantité meilleur produit.
- `inventory_sessions` « Inventaires » [E started_at, store] (`started_at, id`): id, startedAt:Commencé le, store:Boutique, scope:Périmètre (Complet, Partiel), status:Statut (En cours, Validé, Annulé), startedBy:Commencé par, completedAt:Validé le, cancelledAt:Annulé le, countedProducts:Produits comptés, gapProducts:Produits en écart, totalGap:Écart total:INTEGER (sum physical − theoretical of counted lines).
- `inventory_counts` « Comptages d'inventaire » [E session started_at, session store] (`s.started_at, s.id, c.id`): sessionId:Inventaire, sessionStartedAt:Date de l'inventaire, store:Boutique, sessionStatus:Statut de l'inventaire, productId:Identifiant produit, product:Produit, variant:Variante, theoretical:Stock théorique, physical:Stock compté (null if not counted), gap:Écart (null if not counted), countedAt:Compté le, countedBy:Compté par.
- `clients` « Clients » [R] (`created_at, id`): id, name:Nom, phone:Téléphone, email:E-mail, notes:Notes, salesCount:Nombre d'achats (COMPLETED sales), totalSpent:Total des achats:AMOUNT_XAF, lastPurchaseAt:Dernier achat:DATETIME, archived:Archivé, createdAt:Créé le.
- `suppliers` « Fournisseurs » [R] (`created_at, id`): id, name:Nom, phone:Téléphone, email:E-mail, productCount:Produits fournis:INTEGER, archived:Archivé, createdAt:Créé le.
- `employees` « Équipe » [R, store] (`created_at, id`): id, firstName:Prénom, lastName:Nom, phone:Téléphone (`public.users.phone_number`), store:Boutique, status:Statut (Actif, Inactif), createdAt:Ajouté le.
- `stores` « Boutiques » [R, store] — unchanged columns (18-1).
- `reports` « Rapports » [E created_at, store] (`created_at, id`): id, reportDate:Date du rapport:DATE, type:Type (Fin de journée, Hebdomadaire; raw code otherwise), store:Boutique, revenue:Chiffre d'affaires:AMOUNT_XAF, salesCount:Nombre de ventes, automatic:Automatique, actor:Généré par (`actor_name`), deliveryStatus:Envoi (French label of each code), createdAt:Généré le, content:Contenu.
- `audit_log` « Journal d'audit » [E occurred_at, store; criteria `userId`, `action`, `entityType`, `entityId`, `q`] (`occurred_at DESC, id DESC`, via `AuditPort.search` cursor): id, occurredAt:Date, actor:Auteur (resolved name / actorLabel / phone), actorRole:Rôle (Propriétaire, Employé), action:Action (code), entityType:Type d'objet, entityId:Identifiant de l'objet, entityLabel:Objet, store:Boutique, source:Source (En ligne, Synchronisé, Système), deviceId:Appareil, valueBefore:Avant, valueAfter:Après.

Reference vs event (decision): filtering the catalogue by creation date would make « export this month » lose most products; a snapshot is what an owner expects next to the month's sales.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o -q test-compile` -- expected: success.
- `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest='<new and touched test classes>' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures / 0 errors.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend catalog commerce inventory identity store reporting shared architecture` -- expected: every lot OK (run by the orchestrating agent, one lot at a time; never the whole suite).

## Auto Run Result

Status: done

**Summary:** 16 export datasets, one `ExportDatasetProvider` per domain in `<module>/adapter/out/export/` on a shared keyset helper (`shared/infrastructure/export/NativeKeysetExportDataset` + `KeysetCursor` + `ExportValues`): products, categories, stock_levels, stock_movements, stock_transfers, clients, suppliers, sales, sale_items, day_closures, inventory_sessions, inventory_counts, employees, stores (refactored, period ignored as a reference dataset), reports, audit_log (through `AuditPort.search` + new `AuditPort.count`, criteria userId/action/entityType/entityId/q). Port gains `supportsStoreFilter()`, `supportsPeriodFilter()`, `criteriaKeys()`; `ExportFilter` gains `criteria`; criteria validated (`ExportCriteria`), exposed on `GET /api/v1/exports/datasets`, audited in `DATA_EXPORTED`, persisted on background jobs (Flyway V16 + tenant DDL/sync).

**Files changed (main):**
- `shared/application/port/export/ExportDatasetProvider.java`, `ExportFilter.java` -- filter metadata + criteria.
- `shared/infrastructure/export/*` -- keyset base, cursor, converters/actor joins, audit dataset provider.
- `catalog/{product,category,stock,contact}/adapter/out/export/*`, `commerce/sale/adapter/out/export/*`, `inventory/counting/adapter/out/export/*`, `identity/employee/adapter/out/export/*`, `reporting/report/adapter/out/export/*`, `store/store/adapter/out/export/StoresExportDatasetProvider.java` -- providers.
- `shared/application/port/AuditPort.java`, `shared/infrastructure/persistence/impl/AuditLogRepositoryAdapter.java` -- shared WHERE builder, `count`, `actorRole`.
- `reporting/export/**` -- criteria in request/controller/service/event/job (JPA + adapter), `ExportCriteria`, dataset DTO metadata.
- `db/migration/V16__export_jobs_criteria.sql`, `TenantSchemaProvisioner`, `TenantSchemaSyncService` -- `export_jobs.criteria`.
- Tests: `ExportDatasetCatalogTest` (golden schema = documented contract for 18-5), `NativeKeysetExportDatasetTest`, `ExportJobCriteriaMappingTest`, `ExportDatasetsPostgresIntegrationTest` (every dataset CSV + XLSX, 2 tenants, 4 filter combinations, content, audit parity, limit-1 keyset with and without filters, V16 + background criteria), updates to ExportServiceTest, ExportControllerTest, AuditEventListenerExportTest, ExportPostgresIntegrationTest.

**Review findings:** 30 (0 high, 0 medium, 17 low, 13 false). Patched: 9 low rows (7 distinct fixes: corrupt criteria tolerated, ExportCriteria moved to application layer, 5 test-strengthening items). Deferred: none. Rejected with reasons in the triage log. Routed: criteria-only-with-audit rule → v1s-18-4; reuse of column schema + label maps → v1s-18-5.

**Follow-up review recommended: false** -- no high and no medium entry was patched.

**Verification:**
- Targeted (implementation agent): 150 tests then 47 after patches, 0 failures.
- Chunked lots before patches: reporting 313, shared 451, architecture 6, store 40, catalog 394, commerce 222, inventory 160, identity 338 — all OK, 0 failures / 0 errors. After patches (reporting-only code + tests): reporting 314, shared 451, architecture 6 — OK.

**Residual risks:**
- Keyset pages re-sort the filtered set (no dedicated `(created_at,id)` indexes); fine at SME volumes, to watch on very large tenants.
- `stock_levels` keyset on store/product names: a rename during a running multi-page background job could skip/duplicate a line.
- `AuditPort.count` default throws for non-adapter implementations (test doubles only).
