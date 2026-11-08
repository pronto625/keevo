---
title: '20-10 Module expenses — Bref expenses per store and category, offline'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: 'e1862565157a106d3d695924d98bf7c5e19404ee'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-4-encaissements-echeancier-retard.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref merchants record their expenses (rent, salaries, goods purchases…) per shop and category, offline; Keevo has no expenses at all, so Bref users cannot move to Keevo (FUS-FR25) and the finance summary of 20.11 has nothing to subtract.

**Approach:** A new premium module `expenses` (manifest, `db/modules/expenses/V1`, own package `com.keevo.expenses`, JDBC adapters like `invoicing`): REST CRUD under `/api/v1/expenses` gated by entitlement and per-role permissions, store-scoped like the 20.2 clients, keyset-paginated list with Bref's filters, sorts and total, offline operations `CREATE_/UPDATE_/DELETE_EXPENSE` (idempotent, LWW on `clientTimestamp`), an `expenses` pull delta with tombstones, and a shared contract file.

## Boundaries & Constraints

**Always:**
- **Bref rules, exactly** (`api-bref/src/expense/**`): the 10 category keys `goods_purchase, rent, salaries, transport, utilities, telecom, marketing, taxes, maintenance, other` (stored as keys, French labels in responses: « Achat de marchandises », « Loyer », « Salaires », « Transport », « Eau et électricité », « Internet et téléphone », « Publicité », « Taxes et impôts », « Entretien et réparations », « Autre »); payment methods `cash, mobile_money, bank_transfer, card, other` (optional; « Espèces », « Mobile money », « Virement », « Carte », « Autre »); amount 0.01 to 999 999 999 999 with at most 2 decimals (`NUMERIC(14,2)`); date `yyyy-MM-dd`, a real calendar date, at most one day after today (UTC, Bref's `isTooFarInFuture`), not before 2000-01-01 (Keevo floor); when absent: REST creation = today (UTC, Bref), offline creation = the day it was queued (`clientTimestamp`, Africa/Douala), edit = the current date (never « today »); free text (Bref `description`, here `label`) ≤ 500, trimmed, blank = none. Plus Keevo's `note` (≤ 1000). Lengths are checked by the domain only, after trimming. An invalid category / date / amount → 422 `VALIDATION_ERROR` with a French message.
- **Table** `expenses(id, store_id, category, amount, expense_date, label, note, payment_method, created_by, updated_by, created_at, updated_at, archived_at)` in the tenant schema, created lazily by the module provisioner (never in `public`, no JPA). `date` of the AC is the column `expense_date` (`date` is a SQL keyword); exposed as `expenseDate` (Bref's name).
- **Manifest** `expenses` 1.0.0, category `premium`, `free: false`, `requires: [core, store]`, no routes (the RN app lives in the Bref repo; the Flutter route contract stays unchanged), permissions `EXPENSE_VIEW` (every role), `EXPENSE_CREATE` (create + edit: MANAGER, EMPLOYEE), `EXPENSE_DELETE` (MANAGER). OWNER holds all. Offer as data: public `V56` grants it to PRO, TEAM, BUSINESS, BUSINESS_TRIAL and the archived PREMIUM / PREMIUM_TRIAL (`profile_modules`) and owner-disables it for existing tenants (README rule « Modules introduced after the go-live »); no plan code, price or limit in Java.
- **Store scope (ADR-08, like 20.2):** an OWNER sees every store, or one with `storeId=`; anyone else sees and writes only their assigned store (a `storeId` filter is ignored, as for clients; no store → empty list, creation `FORBIDDEN`). A new expense goes to: member → own store (another store requested → 403); owner → the requested active store (unknown/inactive → 422 field `storeId`) or the oldest active store. Someone else's expense = 404 `EXPENSE_NOT_FOUND` « Dépense introuvable ».
- **Writes:** `POST` with optional device `id` (201; replayed → the existing expense, 200, never a second row; a deleted id → 404 `EXPENSE_NOT_FOUND`; another store's id → 403 without disclosing it; `INSERT … ON CONFLICT (id) DO NOTHING`); `PUT /{id}` replaces the whole content (absent optional fields cleared; `storeId` absent = unchanged, owner only may change it), optional `expectedUpdatedAt` → 409 `OPTIMISTIC_LOCK`; `DELETE /{id}` = soft delete (`archived_at`), idempotent (already deleted → 204), terminal (no restore: an edit of a deleted expense → 404).
- **Sync:** `CREATE_EXPENSE {id, storeId?, amount, category, expenseDate?, label?, note?, paymentMethod?}`, `UPDATE_EXPENSE {expenseId, …same, expectedUpdatedAt?}`, `DELETE_EXPENSE {expenseId}`, validated with the REST DTOs; same permissions (missing → `REJECTED FORBIDDEN`); module gate of the push (`MODULE_INACTIVE` / `MODULE_READ_ONLY`); re-sent operation → `DUPLICATE` (operation log); `UPDATE_/DELETE_EXPENSE` resolved last-write-wins on `clientTimestamp` by the existing `LastWriteWinsStrategy` (new LWW entity `EXPENSE`, which never queries a table the tenant does not have). Pull `expenses`: rows as the REST response, store-scoped for members, `{id, deleted: true, updatedAt}` for a deleted one. Contract `keevo/contracts/expense-sync-payloads.json`.
- **List** `GET /api/v1/expenses?storeId=&category=&from=&to=&search=&sort=&cursor=&size=`: Bref filters (`from`/`to` inclusive dates, invalid → 400; `category` invalid → 400; `search` ≤ 100 chars on label/note, case-insensitive), Bref sorts `recent` (default, also for an unknown value as in Bref), `oldest`, `amount_desc`, `amount_asc`; keyset cursor (opaque, bound to a hash of storeId/category/from/to/search/sort: reused with other filters, tampered or out of range → 400 « Position de page invalide »), size 1–100 (default 20); `meta = {size, hasMore, nextCursor}` and, on the first page only, `count` and `totalAmount` of every matching expense (Bref's `totalAmount`).
- Additive only: no change to an existing `/api/v1` response; new error code `EXPENSE_NOT_FOUND` (404).

**Never:** no `finance` summary (20.11), no Bref admin cross-account list `/expenses?ownerId=` (20.13), no import of Bref expenses (24.x), no app route / Flutter screen, no audit trail, no expense events (nothing consumes them yet), no OFFSET pagination, no technical data in labels.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create (owner) | `{amount: 15000, category: "rent"}` | 201, oldest active store, date = today UTC, `categoryLabel` « Loyer », `storeName` | — |
| Replay create | same `id` twice | same expense, one row | — |
| Bad category | `category: "food"` | refused | 422 `VALIDATION_ERROR` |
| Amount 3 decimals / 0 | `12.345`, `0` | refused | 422 |
| Date too far | today + 2 days | refused « La date de la dépense ne peut pas dépasser demain » | 422 |
| Employee list | 2 stores with expenses | only their store's | — |
| Employee create other store | `storeId` ≠ own | refused | 403 `FORBIDDEN` |
| Owner filter unknown store | `storeId=<random>` | refused | 400 |
| Stale edit | `expectedUpdatedAt` older | refused | 409 `OPTIMISTIC_LOCK` |
| Sync pushed twice | same operation | second `DUPLICATE` | — |
| Older offline edit | `UPDATE_EXPENSE` with `clientTimestamp` before the last write | not applied (LWW superseded) | — |
| Delete then pull | `DELETE_EXPENSE` | pull row `{id, deleted: true}` | — |
| Module off | push / REST | `MODULE_INACTIVE` / 403 | — |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `resources/keevo/modules/invoicing/module.json`, `java/com/keevo/invoicing/module/InvoicingModule.java`, `java/com/keevo/invoicing/package-info.java` -- module template (premium, own tables, JDBC).
- `java/com/keevo/invoicing/invoice/adapter/in/sync/InvoiceSyncHandler.java`, `InvoiceDeltaProvider.java`, `adapter/out/persistence/JdbcInvoiceRepository.java` (`tablesExist`, `delta`), `InvoicingTables.java` -- sync and persistence templates.
- `java/com/keevo/crm/contact/application/service/ClientStoreScope.java`, `adapter/in/CurrentClientAccess.java` -- store scope template (20.2).
- `java/com/keevo/sync/sync/application/strategy/ConflictStrategyRegistry.java`, `LastWriteWinsStrategy.java`, `domain/port/out/EntityLastModifiedRepository.java`, `adapter/out/persistence/impl/EntityLastModifiedRepositoryAdapter.java` -- LWW.
- `java/com/keevo/shared/infrastructure/persistence/SyncKeysetIndex.java` -- keyset pull index registry.
- `resources/db/modules/README.md`, `resources/db/migration/V50__module_owner_activation.sql`, `V43__plans_fusion.sql` -- new-module rules and profile modules.
- Tests: `src/test/java/com/keevo/architecture/{ApplicationModulesBaselineTest,ModuleIdsAtGoLivePinTest,EndpointEntitlementCoverageTest}.java`, `docs/entitlements/endpoints.md`, `src/test/java/com/keevo/shared/module/ClasspathModuleManifestsTest.java`.
- Bref (read-only): `api-bref/src/expense/{expense-categories.ts, expense-date.ts, expense.service.ts, dto/*.ts, infrastructure/persistence/relational/repositories/expense.repository.ts}`, app `bref/features/expense/types/expense.types.ts`, `bref/lib/i18n/locales/fr/expense.json`.

## Tasks & Acceptance

**Execution:**
- `resources/keevo/modules/expenses/module.json`, `java/com/keevo/expenses/**` -- module, domain (`Expense`, `ExpenseCategory`, `ExpensePaymentMethod`, `ExpenseRules`, `ExpenseSort`), store scope, use cases, JDBC repository, REST controller + DTOs, sync handler, delta provider.
- `resources/db/modules/expenses/V1__expenses.sql` -- table, CHECKs, partial indexes, sync keyset index; `resources/db/migration/V56__expenses_module_offer.sql` -- profile modules + owner-disable backfill.
- `shared` -- `ErrorCode.EXPENSE_NOT_FOUND` (404, « Dépense introuvable »), `SyncKeysetIndex` entry; `sync` -- LWW entity `EXPENSE` (table-existence guard), LWW ops.
- `keevo/contracts/expense-sync-payloads.json` + handler contract test.
- Tests: domain rules, use cases, handler, full context REST (scope, validation, keyset, totals) and sync (duplicate, LWW, tombstone, module off); updated baseline / manifest / pin / entitlement table tests.

**Acceptance Criteria:**
- Given the `expenses` manifest and its V1 script, when the module is used by a tenant, then the table is provisioned and every route is gated by entitlement and permission.
- Given `CREATE_/UPDATE_/DELETE_EXPENSE` pushed twice, then the second is `DUPLICATE`; the pull carries deletions as tombstones; LWW on `clientTimestamp`.
- Given an EMPLOYEE of one store, when listing expenses (REST or pull), then only that store's expenses are returned.

## Review Triage Log

### 2026-11-08 — Review pass (5 layers: blind, edge case, verification gap, intent, patterns)
- verdicts: 41 findings — high 0, medium 13, low 19, false 4, maybe-false 5 (grouped by root cause)
- findings:
  - `[medium]` `[patch]` Blind/edge: sync rejections lost their French reason — handler maps the message into `conflictData.message`; validate() reasons in French; contract + tests.
  - `[medium]` `[patch]` Blind: offline CREATE/UPDATE without date took the apply day — CREATE defaults to the clientTimestamp's Douala date, UPDATE keeps the current date.
  - `[medium]` `[patch]` Blind/edge: replay of a deleted id returned the archived row — 404 / REJECTED EXPENSE_NOT_FOUND after the visibility check.
  - `[low]` `[patch]` Blind: REST replay answered 201 — 200 when not created.
  - `[medium]` `[patch]` Edge: CREATE_EXPENSE without entityId broke later LWW — entityId required and equal to the id.
  - `[medium]` `[patch]` Patterns/blind: no trigram index for search — GIN trigram on label/note (guarded DO block, public.gin_trgm_ops).
  - `[low]` `[patch]` Patterns/blind: category index without the keyset tail — `(store_id, category, expense_date, created_at, id)`.
  - `[low]` `[patch]` Patterns/blind: member pull scanned all stores — `(store_id, updated_at, id::text)` index.
  - `[low]` `[patch]` Patterns: re-read after write — in-memory row returned.
  - `[low]` `[reject]` Patterns: to_regclass probe per LWW op — constant cost, prevents an aborted transaction.
  - `[low]` `[reject]` Patterns: oldest store chosen in memory — bounded store set, one query.
  - `[medium]` `[patch]` Blind/edge: cursor not tied to filters / tampered cursor 500 — filter hash + bounded decode → 400.
  - `[low]` `[patch]` Edge: pre-2000 dates, java.sql.Date conversion — 422 + LocalDate binding.
  - `[low]` `[patch]` Blind/edge/verification: DTO @Size before trim — removed, domain rule only.
  - `[low]` `[patch]` Blind: member edit in an inactive store / generic refusals — same rule as creation, actionable French messages.
  - `[medium]` `[patch]` Blind: response had no display names — `storeName` (one batch lookup per page); `createdByName` not available (identity::api not allowed) — noted in the mobile section.
  - `[low]` `[patch]` Blind: contract file incomplete — pull row, tombstone, LWW and rejection examples, checked by the contract test.
  - `[medium]` `[patch]` Verification: missing-table guard untested — UPDATE/DELETE before provisioning test.
  - `[medium]` `[patch]` Verification: cross-store create replay untested — 403 without leak.
  - `[medium]` `[patch]` Verification: manager cross-store delete untested — 404, row live.
  - `[low]` `[patch]` Verification: search escaping test could not fail — decoy note, `_` and `\`.
  - `[medium]` `[patch]` Verification: LWW on DELETE untested — older delete superseded.
  - `[low]` `[patch]` Blind: other gaps (member without store, owner move, cursor reuse, date ties) — tests added (member without store as unit test: full context requires employees.store_id).
  - `[medium]` `[defer]` Edge/blind: an expense moved out of a store sends no tombstone to that store's devices — routed to 22.5 (same gap as clients), noted in the mobile section.
  - `[maybe-false]` `[defer]` Intent: Bref keeps expenses owner-only, the AC gives employees store-scoped access — routed to 19-5b (owner decision).
  - `[false]` `[reject]` Intent: Bref route/PATCH/offset shape not mirrored — Keevo API conventions (keyset, PUT) apply; the RN app adapts.
  - `[maybe-false]` `[defer]` Intent: Bref admin cross-account listing — 20.13.
  - `[false]` `[reject]` Intent: table created on first use rather than at activation — the platform's lazy provisioning is the module contract (13.4).
  - `[false]` `[reject]` Intent: DUPLICATE only for the same operationId — that is the sync protocol (16.10); a same-id create is idempotent.
  - `[low]` `[reject]` Edge (claim): no ManageExpenseUseCase unit test — covered end to end; ExpenseUseCasesTest added for the member-without-store branch.

## Design Notes

**Patterns.** Hexagonal module like `invoicing` (domain records + rules, use cases, JDBC adapters, REST/sync inbound adapters). The sync handler is the existing **Template Method** (`AbstractSyncOperationHandler`) reusing the REST DTOs as the single validation source; LWW is the existing **Strategy** (`LastWriteWinsStrategy`) with one more entity. Store visibility is a small **policy object** (`ExpenseStoreScope` + `ExpenseAccess`) resolved once per request from the actor, so use cases never read the security context. Sorts are a **strategy enum** (`ExpenseSort`: ordered key columns + direction) from which the keyset predicate and `ORDER BY` are generated, so each sort is one index range scan.

**Hot paths and cost (FUS-NFR11):**
- List page: one query `WHERE [store_id = ?] AND archived_at IS NULL [AND category = ?] [AND expense_date BETWEEN ? AND ?] [AND (label ILIKE ? OR note ILIKE ?)] AND (keyset predicate) ORDER BY <sort keys> LIMIT size+1`, never OFFSET. Without search: O(log n + size) on the partial indexes `(store_id, expense_date, created_at, id)` / `(expense_date, created_at, id)` (recent/oldest, forward or backward scan), `(store_id, category, expense_date, created_at, id)` (category filter in that order) and `(store_id, amount, expense_date, id)` / `(amount, expense_date, id)` (amount sorts; with a category or a date range the planner filters inside that range). With `search`: the GIN trigram indexes `idx_expenses_label_trgm` / `idx_expenses_note_trgm` (partial, `public.gin_trgm_ops`, skipped with a warning where pg_trgm is not in public) give the candidate rows (bitmap OR), then a sort of the matches — O(matches · log matches), bounded by what the search selects; LIKE wildcards in the text are escaped.
- Totals (`count`, `totalAmount`): one aggregate over the same filters, first page only — the same index range (or trigram bitmap) as the page.
- Store names of a page: one query through `StoreQueryPort.namesByIds` (the tenant's stores, a handful).
- Write: one `INSERT … ON CONFLICT DO NOTHING` (+ one id lookup for a device id); edit/delete: one `SELECT … FOR UPDATE` + one `UPDATE`; the row written is returned (no read back). Store checks: at most one store read per request.
- Pull: keyset on `(updated_at, CAST(id AS text))` — owner: `idx_expenses_sync_keyset` (registered in `SyncKeysetIndex`); store member: `idx_expenses_store_sync_keyset (store_id, updated_at, CAST(id AS text))` — O(log n + page); plus one store-names query per page; no per-row query.
- LWW pre-check: one `to_regclass` + one `updated_at` read per update/delete operation.

**Bref deviations (documented):** amount sorts break ties by date then id in the sort direction (Bref: `amount_asc` ties by newest date) so both share one index; Bref's ILIKE on `description` also covers `note`. Bref's admin cross-account route is 20.13.

## Verification

**Commands:**
- `kmvn.sh -o -q test -Dtest='com/keevo/expenses/**/*Test*'` -- expected: pass
- same for `architecture`, `shared`, `sync`, plus `admin` (plan/module catalogue lists) -- expected: pass

## Auto Run Result

Status: done

**Change:** new premium module `expenses` (« Dépenses », Bref expenses in Keevo, FUS-FR25). Manifest 1.0.0 (`requires: [core, store]`, permissions `EXPENSE_VIEW` / `EXPENSE_CREATE` / `EXPENSE_DELETE`, no app route), table `expenses` in `db/modules/expenses/V1` (lazy per tenant, CHECKs on Bref's 10 categories, 5 payment methods and amount bounds, 5 partial list indexes + sync keyset index), public `V56` (granted to PRO, TEAM, BUSINESS, BUSINESS_TRIAL and the archived PREMIUM / PREMIUM_TRIAL; owner-disabled for existing tenants). REST `/api/v1/expenses` (create idempotent on the device id, keyset list with Bref filters/sorts and first-page totals, get, full-replace edit with optional optimistic check, final idempotent soft delete, `/options`), store scope like the 20.2 clients, sync `CREATE_/UPDATE_/DELETE_EXPENSE` (DUPLICATE by the operation log, LWW via the existing strategy with a new `EXPENSE` entity guarded against tenants without the table), pull `expenses` with tombstones and store filter. New error code `EXPENSE_NOT_FOUND` (404 « Dépense introuvable »).

**Files (main):** `keevo/backend/src/main/java/com/keevo/expenses/**` (module, domain `Expense`/`ExpenseContent`/`ExpenseCategory`/`ExpensePaymentMethod`/`ExpenseSort`/`ExpenseCursor`, port `ExpenseRepository`, `ExpenseAccess`/`ExpenseStoreScope`, `ManageExpenseUseCase`/`ExpenseQueryUseCase`, `ExpenseController` + DTOs, `ExpenseSyncHandler`, `ExpenseDeltaProvider`, `JdbcExpenseRepository`); `resources/keevo/modules/expenses/module.json`; `resources/db/modules/expenses/V1__expenses.sql`; `resources/db/migration/V56__expenses_module_offer.sql`; shared `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler`/`SyncKeysetIndex`; sync `EntityLastModifiedRepository(+Adapter)`, `ConflictStrategyRegistry`, `LastWriteWinsStrategy`; `docs/entitlements/endpoints.md`; contract `keevo/contracts/expense-sync-payloads.json`. Tests: `ExpenseContentTest`, `ExpenseStoreScopeTest`, `ExpenseSyncHandlerTest`, `ExpensesFullContextTest`; updated `ApplicationModulesBaselineTest`, `ModuleIdsAtGoLivePinTest`, `RequiresGraphAcyclicityTest`, `ClasspathModuleManifestsTest`, `PremiumGatingFullContextTest`, `JdbcPlanCatalogPostgresIntegrationTest`.

**Review:** no separate review pass in this worktree (orchestrated build); see residual risks.

**Verification** (serialized wrapper, one package at a time):
- `com/keevo/expenses/**`: 28 tests, 0 failures (`ExpensesFullContextTest` 8, `ExpenseContentTest` 11, `ExpenseSyncHandlerTest` 5, `ExpenseStoreScopeTest` 4).
- `com/keevo/architecture/**` (with `-Dkeevo.entitlements.regenerate=true`, then without): 125 tests, 0 failures.
- `com/keevo/shared/**`: 1 320 tests — 13 failures on the first run, all module-count / module-list fixtures (new module, V56); fixed and the 12 affected classes re-run green.
- `com/keevo/sync/**`: 675 tests — 1 failure (`SyncModuleGuardPostgresIntegrationTest` handler map); fixed, re-run green.
- `com/keevo/admin/**`: 587 tests — 4 failures (paid-module counts, PREMIUM profile rows); fixed (V56 also grants the archived PREMIUM / PREMIUM_TRIAL), the 5 affected classes re-run green.
- Spot checks outside these packages: `ModularVisionAcceptanceTest`, `DocumentQuotaFullContextTest`, `PlanLimitGuardTest`, `AppVersionFullContextTest`, `MultiCompanyMemberFullContextTest`, `InvoiceSyncFullContextTest` green.
- Not run: the full suite package by package (identity, invoicing, commerce, reporting, catalog, crm…) and a live check on keevo_dev.

**Review fixes (2026-11-08, `fix(20-10)`):** French sync rejections (`conflictData.message`, French malformed-operation reasons); offline creation date = queued day (Africa/Douala), edit keeps the date; replay of a deleted id → 404 / `REJECTED EXPENSE_NOT_FOUND`; REST replay → 200; `CREATE_EXPENSE` requires `entityId`; V1 indexes (trigram on label/note, category index with the sort keys, store-scoped pull keyset); cursor bound to the filters and range-checked; date floor 2000-01-01, `LocalDate` binding; length checks only after trimming; member edits follow the creation store rule, French store refusals; `storeName` in responses (one `StoreQueryPort.namesByIds` call per page; no `createdByName`: no allowed port gives member names); writes return the written row; contract examples (pull row, tombstone, superseded and rejected results, FORBIDDEN / OPTIMISTIC_LOCK / EXPENSE_NOT_FOUND). Re-verified: `expenses` 39 tests, `sync` 675, `architecture` 125, `shared` 1 320 — 0 failures each; `store` 128 — 0 failures (new `StoreQueryPort.namesByIds`).

**Residual risks:** an expense moved by the owner out of an employee's store sends that employee no tombstone (same gap as clients, routed to 22.5); V1 applies lazily per tenant on the first gated call / sync operation / pull; the trigram indexes are skipped (warning) on a database without pg_trgm in public.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur la branche de la story (commit à reporter à la fusion) — mobile à faire. Conventions générales (enveloppes, `domainCode`, authentification) : spec-16-11 ; protocole de synchro (push / pull, curseur, `DUPLICATE`, conflits) : spec-16-10.

**Ce qui est fait côté API :** les dépenses de Bref existent dans Keevo, par boutique et par catégorie, en ligne et hors ligne. Mêmes règles que Bref : 10 catégories, 5 modes de paiement, montant de 0,01 à 999 999 999 999 avec au plus 2 décimales, date réelle au format AAAA-MM-JJ, au plus demain (UTC), pas avant le 1er janvier 2000 ; si absente : aujourd'hui (UTC) pour une création en ligne, le jour où la dépense a été saisie hors ligne (heure de Douala) pour une création synchronisée, la date actuelle pour une modification. Le propriétaire voit toutes ses boutiques (ou une seule) ; un employé, un gérant ou un lecteur ne voit et n'enregistre que les dépenses de sa boutique. Module premium « Dépenses » (inclus à partir de Pro) ; désactivé par défaut pour les comptes existants (le propriétaire l'active dans Paramètres › Mes modules).

**À utiliser :**
1. Base `/api/v1/expenses` (l'app Bref appelait `/v1/shop/{shopId}/expenses` : la boutique n'est plus dans l'URL, elle est dans le corps ou le filtre `storeId`).
2. `GET /api/v1/expenses/options` → `data.categories[]` et `data.paymentMethods[]` `{key, label}` (libellés français, ordre de Bref). Clés : `goods_purchase, rent, salaries, transport, utilities, telecom, marketing, taxes, maintenance, other` ; `cash, mobile_money, bank_transfer, card, other`.
3. Création `POST /api/v1/expenses` (droit `EXPENSE_CREATE` : propriétaire, gérant, employé) — corps `{id?, storeId?, amount, category, expenseDate?, label?, note?, paymentMethod?}` ; `label` = l'ancien `description` de Bref (le nom `description` est aussi accepté), ≤ 500 ; `note` ≤ 1000 ; `id` = identifiant généré par l'app (renvoyé deux fois → la même dépense, réponse 200 au lieu de 201, jamais de doublon ; id d'une dépense supprimée → 404 `EXPENSE_NOT_FOUND` ; id utilisé dans une autre boutique → 403) ; `storeId` absent → boutique de l'employé, ou la plus ancienne boutique active du propriétaire. Réponse 201, `data` = `{id, storeId, storeName, category, categoryLabel, amount, expenseDate, label, note, paymentMethod, paymentMethodLabel, createdBy, createdAt, updatedAt}` (`amount` sans zéros inutiles : `150000`, `12.5` ; `storeName` = nom de la boutique à afficher). Pas de nom de l'auteur (`createdByName`) : aucun port autorisé au module ne donne les noms des membres en un appel — afficher l'auteur seulement si l'app le connaît déjà.
4. Liste `GET /api/v1/expenses?storeId=&category=&from=&to=&search=&sort=&cursor=&size=` (droit `EXPENSE_VIEW`, tous les rôles) — `sort` = `recent` (défaut) | `oldest` | `amount_desc` | `amount_asc` (valeur inconnue = `recent`, comme Bref) ; `from`/`to` inclus (AAAA-MM-JJ) ; `search` ≤ 100 caractères dans le libellé et la note ; `size` 1–100 (défaut 20). Pagination par curseur (plus de `page`) : `meta = {size, hasMore, nextCursor}` ; repasser `cursor=<nextCursor>` avec exactement les mêmes filtres et le même `sort` pour la page suivante (sinon 400 « Position de page invalide : rechargez la liste »). La première page (sans `cursor`) porte aussi `meta.count` et `meta.totalAmount` (total de toutes les dépenses filtrées — l'ancien `totalAmount` de Bref). `storeId` est ignoré pour un non-propriétaire.
5. Détail `GET /api/v1/expenses/{id}` ; modification `PUT /api/v1/expenses/{id}` (même corps que la création, **remplace tout** : renvoyer tous les champs ; `label`/`note`/`paymentMethod` absents = effacés, `expenseDate` absente = date actuelle conservée, `storeId` absent = inchangé, seul le propriétaire peut changer de boutique ; `expectedUpdatedAt` facultatif = le `updatedAt` affiché) ; suppression `DELETE /api/v1/expenses/{id}` → 204 (droit `EXPENSE_DELETE` : propriétaire et gérant ; définitive ; une seconde suppression répond aussi 204).
6. Hors ligne (`POST /api/v1/sync/push`, `entityId` = id de la dépense) : `CREATE_EXPENSE` `{id, …corps de création}`, `UPDATE_EXPENSE` `{expenseId, …corps complet, expectedUpdatedAt?}`, `DELETE_EXPENSE` `{expenseId}`. Opération renvoyée deux fois → `DUPLICATE` ; modification ou suppression plus ancienne (`clientTimestamp`) que la dernière écriture → `APPLIED` avec `reason: LWW_SUPERSEDED` (non appliquée : la version serveur revient au pull). Pull : `entities.expenses`, lignes au format REST, dépense supprimée = `{id, deleted: true, updatedAt}` (supprimer la copie locale). Contrat partagé : `keevo/contracts/expense-sync-payloads.json`.
7. Erreurs REST (HTTP — `domainCode` — message `error` — réaction de l'app), vérifiées dans le code et les tests :
   - 422 `VALIDATION_ERROR` — « Catégorie de dépense inconnue », « Le montant doit être d'au moins 0,01 », « Le montant accepte au plus 2 décimales », « Le montant est trop élevé », « Date de dépense invalide (format attendu AAAA-MM-JJ) », « La date de la dépense ne peut pas dépasser demain », « La date de la dépense ne peut pas être antérieure au 1er janvier 2000 », « Le libellé ne doit pas dépasser 500 caractères », « La note ne doit pas dépasser 1000 caractères », « Établissement inconnu ou désactivé », « Aucun établissement actif : créez une boutique avant d'enregistrer une dépense » — afficher le message sous le champ (`details` = champ → message pour les erreurs de format).
   - 400 `VALIDATION_ERROR` (liste) — « Catégorie de dépense inconnue », « La date de début doit précéder la date de fin », « Position de page invalide : rechargez la liste », « Établissement inconnu » — recharger la liste depuis la première page.
   - 404 `EXPENSE_NOT_FOUND` — « Dépense introuvable » (inconnue, supprimée ou d'une autre boutique) — retirer la ligne locale.
   - 409 `OPTIMISTIC_LOCK` — « Cette dépense a été modifiée entre-temps : rechargez-la avant de l'enregistrer. » — recharger puis proposer de refaire la modification.
   - 403 `FORBIDDEN` — « Vous n'avez pas les droits nécessaires » (rôle sans le droit, ex. employé qui supprime) — masquer l'action ; ou, règles de boutique : « Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une. », « Votre boutique est désactivée : demandez au propriétaire de la réactiver pour gérer ses dépenses. », « Vous ne pouvez gérer que les dépenses de votre boutique. », « Seul le propriétaire peut changer la boutique d'une dépense. », « Cet identifiant de dépense est déjà utilisé dans une autre boutique : enregistrez une nouvelle dépense. » (générer un nouvel id) — afficher le message.
   - 403 `MODULE_NOT_ENTITLED` / `MODULE_DISABLED_BY_OWNER` / `MODULE_READ_ONLY` — écran « module non activé ».
   - Push : `REJECTED` avec `reason` = le code (`VALIDATION_ERROR`, `FORBIDDEN`, `EXPENSE_NOT_FOUND`, `OPTIMISTIC_LOCK`) et `conflictData.message` = le message français ci-dessus à afficher (plus `conflictData.field` pour un champ invalide) ; opération mal formée → `reason` = un message français (« Champ obligatoire manquant ou invalide : expenseId », « entityId obligatoire : l'identifiant de la dépense », « entityId ne correspond pas à id ») ; `MODULE_INACTIVE` / `MODULE_READ_ONLY` : garder l'opération en attente. `CREATE_EXPENSE` exige `entityId` = id de la dépense.
   Code : `ExpenseController.java`, `ExpenseRequestDto.java`, `ExpenseResponseDto.java`, `ExpenseContent.java`, `ExpenseSyncHandler.java`, `ExpenseDeltaProvider.java`.

**À noter :** une dépense que le propriétaire déplace vers une autre boutique n'envoie pas encore de suppression (tombstone) aux appareils de l'ancienne boutique : elle y reste visible jusqu'à une resynchronisation complète. Suivi en 22.5.

**À implémenter côté mobile :**
1. Remplacer `features/expense/api/expense.api.ts` par les routes ci-dessus (plus de `shopId` dans l'URL ; `description` → `label`) et la pagination `page` par le curseur.
2. Enregistrer / modifier / supprimer hors ligne via la file de synchro (`CREATE_/UPDATE_/DELETE_EXPENSE`, id généré sur l'appareil) ; appliquer le pull `expenses` (suppressions comprises) dans la base locale.
3. Afficher `categoryLabel` / `paymentMethodLabel` (ou `/options`), jamais les clés ni les identifiants.
4. Ouvrir l'écran aux employés et gérants (leur boutique uniquement) ; bouton Supprimer seulement pour propriétaire et gérant ; filtre boutique seulement pour le propriétaire.

**Critères d'acceptation mobile :**
- Given le module Dépenses actif et l'appareil hors ligne, when l'utilisateur enregistre une dépense « Loyer » de 150 000, then elle apparaît tout de suite et, une fois en ligne, est synchronisée une seule fois (un second envoi répond `DUPLICATE`).
- Given un employé de la boutique B, when il ouvre les dépenses, then il ne voit que celles de la boutique B et aucun bouton Supprimer.
- Given une dépense supprimée sur un autre appareil, when l'app synchronise, then elle disparaît de la liste.
- Given une date au-delà de demain, when l'utilisateur enregistre, then « La date de la dépense ne peut pas dépasser demain » s'affiche.
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"amount":150000,"category":"rent","label":"Loyer de novembre","paymentMethod":"mobile_money"}' $API/api/v1/expenses` puis `curl -H "Authorization: Bearer $TOKEN" "$API/api/v1/expenses?sort=recent&size=20"`.

**Clé de suivi mobile :** `m20-10-module-expenses`.
