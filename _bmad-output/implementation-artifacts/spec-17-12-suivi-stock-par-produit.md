---
title: '17-12 Stock tracking per product (products.track_stock) — API, sync and every stock surface; mobile handover'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_revision: 'fc8ee783956b44e25c6fd2d7227e7d4eabb0280b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 17.12 (`epics-refonte-modulaire.md`, user decision 2026-11-01, read the whole story): stock tracking must be set **per product** (never by sector or store type). Today there is no setting: `RecordSaleService` always checks stock (`INSUFFICIENT_STOCK`) for every product, so services and non-inventoried items cannot be sold without stock. This repo delivers the API; the form checkbox, local column and POS display are the React Native app's job (other repo).

**Approach:** Add `products.track_stock` (default true, existing products unchanged) and make every server stock path honour it: sales (online, sync, pending validation, correction/cancellation) skip availability checks and SALE movements for untracked products; no stock rows, alerts, transfers, inventory sessions, exports or stock value for them; enabling tracking takes an optional starting quantity (STOCK_ENTRY for one store), disabling freezes levels; without the `inventory` module every product behaves as untracked. Then write the Mobile section.

## Boundaries & Constraints

**Always:**
- Tenant migration: `products.track_stock BOOLEAN NOT NULL DEFAULT true` (existing rows true → zero behaviour change). Exposed additively in product REST responses/requests, CSV import (optional column `suivi_stock`, default `oui`, French yes/no values), and the products sync delta + `CREATE_PRODUCT` / `UPDATE_PRODUCT` payloads (absent field = unchanged / true on create — V1 2.0.1 never sends it).
- Untracked product (`track_stock=false`, or `inventory` module not ENABLED/TRIAL for the tenant): no availability check and no SALE movement on sale (REST, sync `CREATE_SALE`, pending sale validation, 17.1 correction/cancellation — no restock movement either); no `stock_levels` row created; excluded from threshold alerts, transfers (refused with a French reason if requested), inventory sessions/counts, stock exports and stock value; a mixed sale only checks/decrements tracked lines. Initial stock (`RECORD_STOCK_ENTRY`) on an untracked product → REJECTED with a stable reason.
- Enabling tracking (owner, or manager per existing permissions): dedicated operation (REST + sync op, e.g. `SET_PRODUCT_STOCK_TRACKING {productId, tracked:true, initialQuantity?, storeId?, movementId?}`): optional starting quantity for one store creates a STOCK_ENTRY (same mechanics as 17.11), other stores start at 0, no movement when empty. Disabling: levels kept but frozen (no new movements), history readable; re-enabling resumes from the frozen quantities. Audited with French labels « Suivi du stock activé » / « Suivi du stock désactivé ».
- Sales already queued offline by V1 apps for a product that becomes untracked are accepted (no stock check) — never rejected because of tracking.
- Spec ends with « ## Mobile (React Native — dépôt AI/bref) » (checkbox in product form, hidden initial-quantity field for untracked, POS shows « Sans suivi de stock » and never caps quantity, local column, sync ops, confirmations) and `m17-12-suivi-stock-par-produit` in the MOBILE block; « ## Dashboard super-admin (autre dépôt) » only if an admin surface shows tracking.

**Never:**
- Tracking is never derived from sector, store type or category. No removal or rename of existing fields (V1 2.0.1). No deliverable work in `keevo/app` / `keevo/dashboard` beyond keeping their tests green.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Untracked sale | product U untracked, no stock; sale online and via sync | accepted, no movement, no INSUFFICIENT_STOCK / STOCK_NEGATIVE | — |
| Mixed sale | tracked T (stock 3) qty 2 + untracked U qty 5 | T decremented to 1, U untouched | T over stock → existing refusal for T only |
| Exclusions | U untracked | absent from alerts, transfer, inventory session, stock export, stock value | transfer of U → French refusal |
| Enable with 12 | U untracked, enable with 12 for store A | STOCK_ENTRY 12 in A, others 0, next sale checked | — |
| Enable empty | U untracked, enable without quantity | tracked, 0 everywhere, no movement | — |
| Disable | T tracked with stock, disable | later sales create no movement, levels frozen, history kept, audit FR | — |
| Re-enable | T disabled then enabled | resumes from frozen levels | — |
| No inventory module | inventory DISABLED | every product behaves as untracked | — |
| Existing tenants | after migration | all products tracked, behaviour unchanged | — |
| V1 payload | CREATE_PRODUCT without trackStock | tracked | — |
| Entry on untracked | RECORD_STOCK_ENTRY on U | REJECTED stable reason | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/commerce/sale/` -- `RecordSaleService` (stock check, `INSUFFICIENT_STOCK`), sale validation (`ValidateSaleSyncHandler`), correction/cancellation (17.1 `CorrectSaleService`, `CancelSaleSyncHandler`).
- `keevo/backend/src/main/java/com/keevo/inventory/` -- stock levels, movements (`RecordStockEntryUseCase`), transfers, counting/inventory sessions, alerts/thresholds, exports (`StockMovementsExportDatasetProvider`), stock value; `inventory/api/*Port` used by commerce/catalog.
- `keevo/backend/src/main/java/com/keevo/catalog/product/` -- product model/entity/DTOs, `CreateProductUseCase`, `UpdateProductUseCase`, `ImportCsvProductsUseCase`, `ProductDeltaProvider` (sync delta columns).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/` -- `ProductSyncHandler`, `StockAdjustSyncHandler`, `TransferSyncHandler`, `InventorySessionSyncHandler`, `SaleSyncHandler`.
- Tenant DDL: module migrations under `keevo/backend/src/main/resources/db/modules/<module>/` or `TenantSchemaProvisioner` (follow how recent product columns were added, e.g. `archived`).
- Module state: `shared/module` entitlements (is `inventory` ENABLED/TRIAL).
- Audit labels: `shared/infrastructure/export/AuditLabels.java`, `AuditEventListener`.
- Conventions for the handover: Mobile sections of `spec-16-11-*`, `spec-16-10-*`, `spec-16-14-*`, `spec-17-10-*`, `spec-17-11-*`.

## Tasks & Acceptance

**Execution:**
- Migration + product model/DTO/CSV/sync delta and payloads (additive).
- Stock paths honour tracking (sales incl. sync/validation/correction, entries, transfers, inventory sessions, alerts, exports, stock value); inventory-module gate.
- Enable/disable operation (REST + sync) with optional starting quantity, freeze, audit.
- Tests: unit + full-context PostgreSQL for every matrix row (REST and sync), migration on an existing tenant.
- Mobile section + `m17-12` key; tick 17.12 in the epics doc.

**Acceptance Criteria:**
- Given a local backend (WhatsApp noop, FCM off) and an owner signed in through the API, when the matrix scenarios are run over HTTP (REST + `/sync/push`), then the outcomes match (recorded in the Auto Run Result).
- Given the Flutter test client 2.0.21 on the Redmi (all products tracked), when it sells and syncs, then behaviour is unchanged (basic check).

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 45 findings — high 1, medium 16, low 25, false 3, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Owner-only instead of « owner or manager » — no manager role exists in the code (`identity/employee` roles: OWNER/EMPLOYEE); owner-only is the existing permission.
  - `[medium]` `[patch]` (intent) Edit path is a second toggle without gate or starting quantity — routed through the same use case and gate; single audit.
  - `[medium]` `[patch]` (intent) Alerts, dashboard, reports low-stock untested — Postgres test added.
  - `[low]` `[patch]` (intent) Stock export tested by provider, products export cell untested — products export column + empty cell test.
  - `[low]` `[reject]` (intent) CSV tested at row-factory level only — use-case test added for the « non » + quantity row; endpoint parsing unchanged.
  - `[low]` `[patch]` (intent) Inventory validation skip unit-level only — test + summary exclusion.
  - `[low]` `[reject]` (intent) Module-off not exercised for entries/transfers/counts — existing `@RequiresEntitlement(inventory)` guards on those endpoints; unchanged.
  - `[low]` `[reject]` (intent) `keevo/app` touched despite « Never » — `local_export_contract_test.dart` reads `contracts/export-datasets.json`; labels + pubspec bump keep the test client green (allowed).
  - `[false]` `[reject]` (intent) Spec/docs/contracts not in the diff — excluded on purpose; present in the working tree.
  - `[medium]` `[patch]` (intent) Second toggle path via UPDATE_PRODUCT — same root as the edit-path row.
  - `[medium]` `[patch]` (blind) Concurrent enables double the starting stock — conditional update gating entry and audit; concurrency test.
  - `[medium]` `[patch]` (blind) Enable with quantity on an already-tracked product: APPLIED, no movement — `conflictData.initialEntry = IGNORED`; Mobile rule.
  - `[medium]` `[patch]` (blind) Edit path bypasses inventory gate and starting quantity — same root as the edit-path row.
  - `[low]` `[patch]` (blind) Empty « Produit modifié » audit after a tracking-only change — skipped.
  - `[medium]` `[patch]` (blind) Frozen stock still shown in cross-store availability, product stock, pull, PDF, admin counts — excluded from availability, PDF, admin counts; pull and product stock documented (client hides).
  - `[low]` `[patch]` (blind) `stockQuantity` stored for untracked products — forced null.
  - `[low]` `[reject]` (blind) Several lookups per sale line — per-line product read on small baskets; batching is an optimisation without measured need.
  - `[medium]` `[patch]` (blind) Products export drops the setting; re-import resets it — « Suivi du stock » column added.
  - `[low]` `[patch]` (blind) Yes/no validator docs/tests; silent quantity drop — unit test, Javadoc, row warning.
  - `[low]` `[patch]` (blind) Loose tests (400/422, contains 4000, optionalBoolean, store check) — tightened.
  - `[low]` `[reject]` (blind) Owner check order and event building duplicated — cosmetic; behaviour identical.
  - `[low]` `[patch]` (blind) `DeltaStockStrategy` legacy constructor disables the check — removed.
  - `[false]` `[reject]` (blind) endpoints.md / contracts not in the patch — excluded on purpose.
  - `[low]` `[patch]` (blind) Inventory summary may report unapplied gaps — excluded from the summary.
  - `[medium]` `[patch]` (verification-gap) STOCK_NEGATIVE skip untested — test with negative frozen level.
  - `[medium]` `[patch]` (verification-gap) Pending-sale cascade skip untested — test added.
  - `[medium]` `[patch]` (verification-gap) Inventory validation skip untested — test added.
  - `[low]` `[patch]` (verification-gap) PARTIAL scope filter untested — test added.
  - `[medium]` `[patch]` (verification-gap) Dashboard / report low-stock filters untested — Postgres test.
  - `[low]` `[patch]` (verification-gap) CSV « non » + quantity guard untested — test added.
  - `[low]` `[patch]` (verification-gap) Products export empty cell untested — test added.
  - `[low]` `[patch]` (verification-gap) UPDATE_PRODUCT `trackStock` untested — covered with the edit-path patch (not deferred).
  - `[low]` `[patch]` (verification-gap other) Non-string productId/storeId → ClassCastException — type validation.
  - `[low]` `[reject]` (verification-gap other) App audit labels untested — static maps covered by the contract test.
  - `[medium]` `[patch]` (edge) In-transit transfer stuck after disabling tracking — disable refused while transfers are in transit.
  - `[medium]` `[patch]` (edge) Concurrent enable doubles stock — same root as the blind row.
  - `[low]` `[patch]` (edge) Concurrent enable/disable duplicate audits — same root.
  - `[false]` `[reject]` (edge) Starting quantity on a product with variants — no variant model in the catalogue (verified in 17-11).
  - `[medium]` `[patch]` (edge) APPLIED with an orphan local entry — same root as the blind row.
  - `[low]` `[patch]` (edge) Non-string productId/storeId — same root as the verification-gap row.
  - `[low]` `[patch]` (edge) Unparsable movementId silently replaced — REJECTED with a clear reason.
  - `[low]` `[patch]` (edge) CSV header case / missing mapped column — case-insensitive match.
  - `[medium]` `[patch]` (edge) Employee creates an untracked product — owner required for `false`.
  - `[low]` `[patch]` (edge) `stock_quantity` stored for untracked products — same root as the blind row.
  - `[high]` `[patch]` (edge) Inventory READ_ONLY treated as untracked: sales stop decrementing during grace — only DISABLED makes products untracked; tests.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.<pkg>.**'` for every package (catalog, inventory, commerce, sync, shared, reporting, admin, identity, subscription, store, crm, invoicing, messaging, feedback, acceptance, architecture), one at a time, never two Maven builds in parallel -- expected: green

**Manual checks (if no CLI):**
- Matrix over HTTP with curl; backend with `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, stopped with `fuser -k 4500/tcp`; sign in through `/auth/login` + `/auth/select-tenant`, never forge tokens.

## Auto Run Result

**Backend (chunked, one Maven build at a time, 2026-11-02):** catalog 260, inventory 405, commerce 260, sync 603, shared 1145, reporting 404, admin 561, identity 457, subscription 87, store 60, crm 158, invoicing 170, messaging 176, feedback 52, acceptance 4, architecture 32 — 0 failures (after regenerating `keevo/backend/docs/entitlements/endpoints.md` for the new `PUT /stock-tracking` route and `keevo/contracts/export-datasets.json` for the two audit labels). New: `StockTrackingFullContextTest` (14, every matrix row, REST + `/sync/push`, migration on an existing tenant), `StockTrackingSyncHandlerTest` (7), `ChangeStockTrackingUseCaseTest` (7), `StockTrackingPolicyTest` (3), plus cases in `StockOperationServiceTest`, `StockCommandAdapterTest`, `MultiStoreStockRepositoryAdapterPostgresTest`, `ProductCsvRowFactoryTest`, `UpdateProductUseCaseTest`, `AuditEventListenerTest`. Flutter test client: `test/features/audit` + `test/features/export` 246 green.

**After review (2026-11-02, targeted runs only, one Maven build):** 30 touched test classes — 291 + 24 tests green, incl. `StockTrackingFullContextTest` 20 (adds: two concurrent activations → one entry / one audit, quantity on an already-tracked product → `initialEntry: IGNORED`, disable refused with a transfer in transit then allowed once received, sync `UPDATE_PRODUCT trackStock:false` → single « Suivi du stock désactivé », non-boolean rejected, legacy column 0 + products export empty stock / « Suivi du stock », dashboard / end-of-day / weekly low-stock counts exclude a frozen-low untracked product, exact value / status assertions, unknown and inactive store), new `SetProductStockTrackingUseCaseTest` and `OptionalYesNoValidatorTest`. Flutter `test/features/export` + `test/features/audit` 246 green. Wider suites left to the coordinator.

**Live (local backend, `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, fresh owner through `/auth/register` → `/auth/login` → `/auth/select-tenant`, stopped with `fuser -k 4500/tcp`):**
- untracked U (`CREATE_PRODUCT trackStock:false`): REST sale ×2 → 201, sync `CREATE_SALE` ×5 → `APPLIED` (no conflict), no stock row, empty history;
- mixed REST sale T(3)×2 + U×5 → 201, T = 1, U untouched; T×2 again → 422 `INSUFFICIENT_STOCK` « Stock insuffisant pour cette opération »;
- `RECORD_STOCK_ENTRY` on U → `REJECTED` / `STOCK_NOT_TRACKED`;
- `PUT /products/U/stock-tracking {tracked:true, initialQuantity:12, storeId}` → `STOCK_ENTRY` 12; sale 13 → 422, sale 2 → 201, stock 10;
- V enabled empty (`SET_PRODUCT_STOCK_TRACKING`) → `APPLIED`, no level, sale 1 → 422;
- T disabled (sync) → sale 9 → 201, level frozen at 1, history `[SALE, STOCK_ENTRY]` still readable; re-enabled → resumes at 1 (sale 2 → 422, sale 1 → 201 → 0);
- `GET /products/{id}` → `trackStock` present; audit list → « STOCK_TRACKING_DISABLED » then « STOCK_TRACKING_ENABLED » (via `PATCH trackStock:true`), label = product name.

**Redmi (Flutter test client `com.example.keevo` 2.0.21, all products tracked, `adb reverse tcp:4500`):** app relaunched against the new backend — profile, modules, store stock, `GET /api/v1/sync/pull`, refresh: 14 requests, all 200, no backend error.

## Design Notes

**Effective rule.** A product's stock is tracked iff `products.track_stock = true` **and** the `inventory` module is not `DISABLED` for the tenant (`ENABLED`/TRIAL and the `READ_ONLY` grace keep normal tracking, so levels never drift during a grace; only `DISABLED` makes every product untracked). Computed in one place: `inventory/stock/domain/service/StockTrackingPolicy`, published as `StockQueryPort.isUntracked(tenant, productId)` (unknown product → `false`, so the existing `PRODUCT_NOT_FOUND` paths are unchanged).

- **Sale side** (commerce): `RecordSaleService` (REST + sync `CREATE_SALE`), `ValidateSaleService`, `SaleValidationCascadeService` skip the availability check of untracked lines; `StockCommandAdapter.recordMovement` (every SALE / SALE_CANCELLED / correction ADJUSTMENT / draft initial STOCK_ENTRY coming from commerce) is a silent no-op for an untracked product — never an error, so a V1 sale queued offline is always accepted. `DeltaStockStrategy` skips untracked products, so a frozen negative level never raises `STOCK_NEGATIVE`.
- **Inventory side**: `StockOperationService` refuses any movement on a product whose setting is off → `STOCK_NOT_TRACKED` (HTTP 422, « Le suivi du stock est désactivé pour ce produit » ; sync `REJECTED` / reason `STOCK_NOT_TRACKED`): `RECORD_STOCK_ENTRY`, `STOCK_ADJUST`, REST entry/adjust, transfers (checked first in `ExecuteTransferService`, before the quantity check; turning tracking off is refused with `TRANSFER_IN_TRANSIT` (409, « Un transfert de ce produit est en cours : réceptionnez-le avant de désactiver le suivi du stock ») while a transfer of the product is in transit, so a transfer is never stuck), inventory adjustments. Inventory sessions never list an untracked product (`FullScopeResolver` / `PartialScopeResolver`); a count saved before the product was disabled is ignored at validation (the rest of the session is validated) and left out of the validation summary (`adjustmentsApplied`, `InventoryValidatedEvent`) and of the gap report.
- **Read surfaces**: store detail, store overview (count, value, low-stock), cross-store availability (empty for an untracked product), dashboard / end-of-day / weekly low-stock counts, the super-admin catalogue low / out-of-stock counts and the `stock_levels` export (and its value column, and therefore the « État de stock » PDF built from it) filter `p.track_stock = true`. The products export leaves the « Stock » cell empty and has a « Suivi du stock » column (Oui / Non) so an export → re-import keeps the setting. The `stock_movements` export and the product history keep the movements recorded while the product was tracked (history readable). The pull (`stockLevels`) and `GET /products/{id}/stock` keep delivering the frozen levels on purpose (re-enabling resumes from them); clients hide them while `trackStock` is false.
- **Toggle**: catalogue `SetProductStockTrackingUseCase` is the single path of every change (dedicated REST / sync operation and a product edit): OWNER only (there is no manager role with catalogue rights), refused with `MODULE_NOT_ENTITLED` while `inventory` is DISABLED, transfer guard on disable (`StockTrackingGuard`, catalogue API port implemented by inventory), then a **conditional update** (`UPDATE … SET track_stock = :t WHERE id = :id AND track_stock <> :t`): of two concurrent identical calls only one reports `changed`, and only that one records the starting entry and publishes `ProductStockTrackingChangedEvent` → audit `STOCK_TRACKING_ENABLED` « Suivi du stock activé » / `STOCK_TRACKING_DISABLED` « Suivi du stock désactivé » (entity label = product name, no technical value). `inventory` `ChangeStockTrackingUseCase` orchestrates the optional starting quantity in one transaction (setting + one STOCK_ENTRY, note « Stock de départ (activation du suivi) »); a quantity sent while the product is already tracked (or that lost a concurrent race) is ignored — the sync answer is `APPLIED` with `conflictData = {"initialEntry": "IGNORED"}`. `UPDATE_PRODUCT` / `PATCH /products/{id}` with `trackStock` goes through the same use case (never with a quantity); when tracking is the only change, no « Produit modifié » entry is written and nothing else is touched (no draft promotion, description kept). Creating a product with `trackStock: false` is OWNER-only (`FORBIDDEN` « Seul le propriétaire peut créer un produit sans suivi de stock »). The legacy `products.stock_quantity` column is never written for an untracked product (stays 0, REST / sync / CSV / edit).
- **Schema**: `V38__products_track_stock.sql` (public) + `TenantSchemaProvisioner.DDL_PRODUCTS_MIGRATE_TRACK_STOCK` run at provisioning and by `TenantSchemaSyncService` on every existing tenant (default true → zero behaviour change, proven by `migrationOnExistingTenant`).
- **CSV**: optional column `suivi_stock` (or the column named by `trackStockColumn` in the mapping) — blank / absent → tracked; `oui/o/vrai/1/yes/true`, `non/n/faux/0/no/false` (case and accents ignored); anything else is a row error « Suivi du stock : répondez « oui » ou « non » ». Header matched case-insensitively. An untracked row never records its `quantite_initiale`: the row is imported with a warning (`warnings[]` of the import result, additive) « Quantité initiale ignorée : produit sans suivi de stock ». The template gained the column and a « Livraison à domicile … non » example.
- **Contract files**: `keevo/contracts/export-datasets.json` regenerated (two audit labels, products column « Suivi du stock »); the Flutter test client's audit catalogue (`audit_labels.dart`, `audit_presenter.dart`) and local export schema (`trackStock` column, empty on the device) follow so its contract test stays green — `pubspec.yaml` bumped to 2.0.22+94 (no feature work in the app).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; protocole de synchro et politique de rejet : section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md` ; messages d'erreur : `spec-16-14-api-prete-client-mobile.md` ; stock initial à la création : `spec-17-11-stock-initial-creation-produit.md` (sa règle « champ masqué pour un produit non suivi » est livrée ici). Tous les ajouts sont **additifs** (compatibles V1 2.0.1) : un client qui n'envoie jamais `trackStock` crée des produits suivis et ne change jamais le réglage.

**Statut** : API livrée (commit `874719b7`, test `sync/sync/adapter/in/rest/StockTrackingFullContextTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-12-suivi-stock-par-produit`

### Ce qui est fait côté API

- Chaque produit porte `trackStock` (booléen, défaut `true`). Le réglage appartient au **produit** (toutes boutiques) ; jamais déduit du secteur, du type de boutique ni de la catégorie. Tous les produits existants sont suivis.
- **Produit non suivi** (`trackStock: false`) **ou module `inventory` désactivé** (`DISABLED` ; pendant la période de grâce en lecture seule, le suivi continue normalement) : la vente n'est jamais contrôlée ni refusée pour le stock, aucun mouvement `SALE` (ni à la validation d'une vente en attente, ni à la correction, ni à l'annulation), aucune ligne de stock, aucune alerte de seuil, exclu des transferts, des inventaires, de l'export et de la valeur du stock. Une vente mixte ne contrôle et ne décrémente que les lignes suivies.
- Une entrée / un ajustement / un transfert sur un produit non suivi est refusé : HTTP 422 `domainCode: STOCK_NOT_TRACKED`, `error: « Le suivi du stock est désactivé pour ce produit »` ; en synchro `REJECTED`, `reason: STOCK_NOT_TRACKED`, `conflictData: null`.
- **Activer** : quantité de départ **facultative** pour la boutique active → un `STOCK_ENTRY` (note « Stock de départ (activation du suivi) ») ; les autres boutiques restent à 0 ; sans quantité, aucun mouvement. Si le produit avait déjà du stock gelé, la réactivation repart de ces quantités (la quantité de départ s'ajoute seulement si le produit n'était pas suivi ; envoyée sur un produit déjà suivi, elle est ignorée).
- **Désactiver** : niveaux conservés, **gelés** (plus aucun mouvement), historique consultable. Les lignes `stockLevels` gelées continuent d'arriver au pull et dans `GET /api/v1/products/{id}/stock` : l'app les **masque** tant que `trackStock` est `false` (elles réapparaissent à la réactivation). Refusé tant qu'un transfert du produit est en cours : `TRANSFER_IN_TRANSIT` (409 / `REJECTED`), message « Un transfert de ce produit est en cours : réceptionnez-le avant de désactiver le suivi du stock ».
- Création d'un produit non suivi : **propriétaire seulement** (employé → `FORBIDDEN`). La colonne historique `stockQuantity` d'un produit non suivi vaut toujours 0 (ne pas l'envoyer).
- Réservé au **propriétaire** (`FORBIDDEN` sinon). Changement audité : « Suivi du stock activé » / « Suivi du stock désactivé » (actions `STOCK_TRACKING_ENABLED` / `STOCK_TRACKING_DISABLED` de l'historique des actions, libellés dans `keevo/contracts/export-datasets.json`).

### À utiliser

| Besoin | Appel | Réponse / effet | Source |
|---|---|---|---|
| Lire le réglage | pull `products[].trackStock` ; `GET /api/v1/products[/{id}]` → `data.trackStock` | booléen, toujours présent (absent chez un vieux serveur → `true`) | `sync/.../provider/ProductDeltaProvider.java`, `catalog/.../dto/ProductResponseDto.java` |
| Créer non suivi (hors ligne) | `CREATE_PRODUCT` `payload.trackStock: false` | `APPLIED` ; absent → suivi | `sync/.../handler/ProductSyncHandler.java` |
| Activer / désactiver (hors ligne, **recommandé**) | `SET_PRODUCT_STOCK_TRACKING`, `entityId` = id produit, `payload` = `{productId, tracked, initialQuantity?, storeId?, movementId?}` | `APPLIED` (`serverEntityId` = id produit) ; `FORBIDDEN` (employé) ; `VALIDATION_ERROR` (quantité < 0, quantité avec `tracked: false`, quantité sans `storeId`) ; `STORE_NOT_FOUND` / `STORE_NOT_ACTIVE` ; `PRODUCT_NOT_FOUND` ; `MODULE_INACTIVE` (module `inventory` coupé : reste en file) ; même `movementId` renvoyé sous un nouvel `operationId` → `DUPLICATE` (même contenu) ou `REJECTED` / `MOVEMENT_ID_ALREADY_USED` ; quantité envoyée alors que le produit était déjà suivi → `APPLIED` avec `conflictData: {"initialEntry": "IGNORED"}` ; `TRANSFER_IN_TRANSIT` (désactivation) ; ids mal formés → `REJECTED`, `reason` `Invalid productId|storeId|movementId: must be a UUID` | `sync/.../handler/StockTrackingSyncHandler.java`, `inventory/.../usecase/ChangeStockTrackingUseCase.java` |
| Activer / désactiver (en ligne) | `PUT /api/v1/products/{id}/stock-tracking` `{tracked, initialQuantity?, storeId?}` | 200 `data: {productId, trackStock, initialEntry: {id, movementType: "STOCK_ENTRY", quantityAfter…} \| null}` ; 403 `FORBIDDEN` / `MODULE_NOT_ENTITLED` ; 422 `VALIDATION_ERROR` | `inventory/.../web/StockController.java` |
| Modifier via la fiche (sans quantité) | `UPDATE_PRODUCT` / `PATCH /api/v1/products/{id}` avec `trackStock` | absent → inchangé ; changement par un employé → `FORBIDDEN` (toute la modification est refusée) ; module `inventory` désactivé → `MODULE_NOT_ENTITLED` ; valeur non booléenne → `REJECTED` `Invalid trackStock: must be a boolean` ; seule modification → une seule entrée « Suivi du stock … » (pas de « Produit modifié ») | `catalog/.../usecase/UpdateProductUseCase.java` |
| Vente | `CREATE_SALE` / `POST /api/v1/sales` inchangés | ligne non suivie : jamais `INSUFFICIENT_STOCK`, jamais `STOCK_NEGATIVE`, aucun mouvement au pull | `commerce/.../RecordSaleService.java` |
| Entrée / ajustement / transfert sur non suivi | `RECORD_STOCK_ENTRY`, `STOCK_ADJUST`, `STOCK_TRANSFER`, REST équivalents | `REJECTED` / 422 `STOCK_NOT_TRACKED` | `inventory/.../StockOperationService.java`, `ExecuteTransferService.java` |
| Import CSV | colonne facultative `suivi_stock` (`oui` / `non`, vide → `oui`) ou `trackStockColumn` dans le mapping | ligne `non` : produit non suivi, `quantite_initiale` ignorée avec un avertissement dans `warnings[]` (« Quantité initiale ignorée : produit sans suivi de stock ») ; en-tête insensible à la casse ; l'export « Produits » a une colonne « Suivi du stock » (Oui / Non) ; autre valeur → erreur de ligne « Suivi du stock : répondez « oui » ou « non » » | `catalog/.../csv/ProductCsvRowFactory.java` |

### À implémenter côté mobile

1. **Colonne locale** `track_stock` (booléen, défaut `true`) sur la table produits + migration locale (produits existants → `true`) ; remplie depuis `products[].trackStock` au pull (absent → `true`).
2. **Formulaire produit** (création et modification, **owner seulement**) : case « Suivre le stock », cochée par défaut, aide « Décochez pour un service ou un article sans inventaire ». Employé : case non affichée (création → produit suivi ; ne jamais envoyer `trackStock` dans `UPDATE_PRODUCT`).
3. **Création** : case décochée → `CREATE_PRODUCT` avec `trackStock: false`, champ « Quantité initiale » (17-11) **masqué** et aucune `RECORD_STOCK_ENTRY`. Case cochée → comportement 17-11 inchangé (`trackStock` peut être omis ou `true`).
4. **Activer sur un produit existant** (fiche, owner) : feuille de confirmation « Activer le suivi du stock ? » avec un champ facultatif « Quantité de départ — <boutique active> » (entier ≥ 0, vide autorisé ; message « Entrez un nombre entier positif » sinon) et le texte « Les autres boutiques démarreront à 0. » (si le produit a des niveaux gelés : « Le stock reprendra à la dernière quantité connue. »). Une seule transaction locale : `track_stock = 1`, si Q > 0 mouvement local M (`STOCK_ENTRY`, note « Stock de départ (activation du suivi) ») + niveau (X, boutique active) += Q, mise en file de `SET_PRODUCT_STOCK_TRACKING {productId: X, tracked: true, initialQuantity: Q, storeId: S, movementId: M}` (sans `initialQuantity` / `storeId` / `movementId` si vide).
5. **Désactiver** (owner) : confirmation explicite « Désactiver le suivi du stock ? Les quantités actuelles seront conservées mais ne bougeront plus ; l'historique reste consultable. » → `track_stock = 0` + `SET_PRODUCT_STOCK_TRACKING {productId, tracked: false}`. Ne pas supprimer les niveaux ni les mouvements locaux.
6. **POS / catalogue / fiche** : produit non suivi (ou module `inventory` inactif) → afficher « Sans suivi de stock » au lieu d'une quantité, ne **jamais** plafonner la quantité au panier, aucun badge « stock bas » / « rupture », pas d'avertissement de stock insuffisant. Vente hors ligne : ne créer **aucun** mouvement local `SALE` ni niveau pour ces lignes (le serveur n'en renverra pas) ; une vente mixte ne décrémente localement que les lignes suivies.
7. **Écrans de stock** : exclure les produits non suivis des listes de stock par boutique, alertes, choix de produit d'un transfert, sessions d'inventaire (comptage), export local et valeur du stock ; masquer les actions « Entrée de stock » / « Ajuster » / « Transférer » sur leur fiche. L'onglet « Historique » reste visible (mouvements passés).
8. **Activation ignorée** : `APPLIED` avec `conflictData.initialEntry = "IGNORED"` (le produit était déjà suivi) → supprimer le mouvement local M et le niveau ajouté localement, puis relire les niveaux au prochain pull ; avis discret « Le stock de ce produit était déjà suivi : la quantité de départ n'a pas été ajoutée. »
8b. **Rejets** (politique 16-10) : `STOCK_NOT_TRACKED` sur une entrée / un ajustement / un transfert (réglage changé depuis un autre appareil) → retirer l'opération et son mouvement local, avis « Le suivi du stock a été désactivé pour ce produit : l'opération n'a pas été enregistrée. » ; `FORBIDDEN` sur `SET_PRODUCT_STOCK_TRACKING` / `UPDATE_PRODUCT` → rétablir le réglage local d'après le pull ; `TRANSFER_IN_TRANSIT` → rétablir « suivi » localement et afficher le message serveur (proposer d'ouvrir le transfert à réceptionner) ; `FORBIDDEN` sur un `CREATE_PRODUCT` non suivi (ne doit pas arriver : case masquée pour l'employé) → politique « création refusée » ; `VALIDATION_ERROR` / `MOVEMENT_ID_ALREADY_USED` → quarantaine (bug client) ; `DUPLICATE` → retirer de la file ; `MODULE_INACTIVE` → reste en file. Jamais de `reason`, de code ni d'identifiant à l'écran.
9. **Ordre en file** : `SET_PRODUCT_STOCK_TRACKING` passe dans l'ordre des `clientTimestamp` (pas de remontée comme les créations) ; une vente faite après l'activation doit donc être mise en file après l'opération d'activation.
10. **Module `inventory` désactivé** (`DISABLED` dans `GET /api/v1/tenants/me/modules` ; la grâce en lecture seule garde le suivi) : traiter tous les produits comme non suivis à l'affichage (règle 6) et ne pas proposer la case ; ne rien envoyer.
11. **Historique des actions** : libellés « Suivi du stock activé » / « Suivi du stock désactivé » (catégorie Stock, objet = nom du produit).

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`, `DEV` fixe), `S` = boutique active (voir 17-11).

```bash
u(){ cat /proc/sys/kernel/random/uuid; }
T(){ date -u +%Y-%m-%dT%H:%M:%SZ; }
P=$(u); M=$(u); SALE=$(u)
push(){ curl -s -X POST $BASE/api/v1/sync/push -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$1" | jq -c '.data.results[] | {status, reason}'; }
# produit non suivi, vendu sans stock
push '{"deviceId":"'$DEV'","operations":[{"operationId":"'$(u)'","operationType":"CREATE_PRODUCT","entityId":"'$P'","payload":{"id":"'$P'","name":"Livraison","price":500,"trackStock":false},"clientTimestamp":"'$(T)'"}]}'
push '{"deviceId":"'$DEV'","operations":[{"operationId":"'$(u)'","operationType":"CREATE_SALE","entityId":"'$SALE'","payload":{"saleId":"'$SALE'","storeId":"'$S'","paymentMode":"CASH","items":[{"itemId":"'$(u)'","productId":"'$P'","productName":"Livraison","catalogueUnitPrice":500,"appliedUnitPrice":500,"quantity":3}]},"clientTimestamp":"'$(T)'"}]}'   # → APPLIED, pas de STOCK_NEGATIVE
# activation avec 12 pour la boutique active
push '{"deviceId":"'$DEV'","operations":[{"operationId":"'$(u)'","operationType":"SET_PRODUCT_STOCK_TRACKING","entityId":"'$P'","payload":{"productId":"'$P'","tracked":true,"initialQuantity":12,"storeId":"'$S'","movementId":"'$M'"},"clientTimestamp":"'$(T)'"}]}'
curl -s $BASE/api/v1/products/$P/stock -H "Authorization: Bearer $AT" | jq -c '.data[] | {storeId, quantity}'   # → 12 dans S
# désactivation en ligne
curl -s -X PUT $BASE/api/v1/products/$P/stock-tracking -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"tracked":false}' | jq -c .data
```

1. **Création non suivie** — Given l'owner décoche « Suivre le stock », Then le champ « Quantité initiale » disparaît ; après synchro le produit a `trackStock: false` et aucune ligne de stock.
2. **Vente sans stock** — Given un produit non suivi, When un vendeur en vend 5 hors ligne puis synchronise, Then le POS n'a jamais limité la quantité, la vente est `APPLIED`, aucun mouvement ni avertissement de stock.
3. **Vente mixte** — Given « Savon » suivi (3) et « Livraison » non suivie, When on vend 2 savons + 5 livraisons, Then « Savon » affiche 1, « Livraison » affiche « Sans suivi de stock ».
4. **Activation avec 12** — Given « Livraison » non suivie, When l'owner active le suivi avec 12, Then la boutique active affiche 12, les autres 0, l'historique montre une entrée « Stock de départ (activation du suivi) » (une seule après le pull), la vente suivante de 13 est limitée.
5. **Activation sans quantité** — Then stock 0 partout, aucun mouvement, la vente suivante est limitée à 0.
6. **Désactivation** — Given « Huile » suivi avec 5, When l'owner désactive et confirme, Then le POS affiche « Sans suivi de stock », les ventes ne bougent plus rien, l'historique reste lisible, l'historique des actions montre « Suivi du stock désactivé ».
7. **Réactivation** — Then le stock repart de 5.
8. **Employé** — Given un employé, Then aucune case « Suivre le stock » ; une vente d'un produit non suivi reste possible.
9. **Exclusions** — Given un produit non suivi, Then il n'apparaît ni dans les alertes, ni dans le choix de produit d'un transfert, ni dans un inventaire, ni dans l'export du stock.
10. **Conflit entre appareils** — Given un appareil A qui désactive le suivi, When l'appareil B (pas encore synchronisé) envoie une entrée de stock sur ce produit, Then B reçoit `STOCK_NOT_TRACKED`, retire l'entrée locale et affiche l'avis de la règle 8.

### Final verification (orchestrator)

- **Review:** 45 findings — high 1 (inventory READ_ONLY made products untracked → only DISABLED now), medium 16, low 25, false 3; patched entries: grace semantics, concurrent enable (conditional update gating entry and audit), ignored starting quantity signalled (`conflictData.initialEntry = IGNORED`), single gated path for tracking changes (dedicated op and product edit), owner-only untracked creation, legacy `stock_quantity` never written for untracked, frozen stock hidden (cross-store availability, admin counts), « Suivi du stock » export column, disable refused with a transfer in transit (`TRANSFER_IN_TRANSIT`), inventory summary, removed `DeltaStockStrategy` legacy constructor, missing tests (cascade, partial scope, low-stock counts on Postgres, CSV warning, validator, STOCK_NEGATIVE skip). 0 deferred; rejected rows with reasons in the triage log.
- **Follow-up review recommended: true** — the high and 16 medium patches reworked the tracking write path (conditional update, edit path routing, transfer guard); a second look at `SetProductStockTrackingUseCase` and the sync handlers is the unverified risk.
- **Backend:** every package one at a time — 4868 tests; 2 golden export tests failed on the new « Suivi du stock » column (expected schema change), golden schema and content assertion updated, `com.keevo.reporting.**` re-run green.
- **Flutter test client:** `flutter analyze` 0 errors/warnings, every test folder green (audit labels + local export column for the contract test; version 2.0.22+94).
- **Redmi:** debug APK 2.0.22 installed, app online, pull at 00:58:45 UTC, no backend error.
