---
title: 'v1s-16-14 — Catalog list quality (search escaping, dedup, never-stocked, scale)'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_revision: 'a8a73fe2c080d2e4a1c6faa0b250d2916b9c2011'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Four deferred catalog-list items (Story 16.14): (1) a `%` or `_` typed in a search box is a SQL LIKE wildcard, so "50%" or "a_b" match far too much; (2) `productList` drops every product whose lower-cased name was already seen, which can hide a distinct product; (3) a product never stocked in the active store shows the same red "0" badge (or no badge at all) as a product that sold out; (4) the whole tenant catalog is loaded client-side without pagination.

**Approach:** (1) One shared helper escapes `\`, `%`, `_` and every user-facing local search uses `ESCAPE '\'` (catalog, POS, contacts, multi-store stock). (2) Replace the name dedup by an id dedup — name collisions are resolved at the sync layer and forbidden server-side. (3) `ProductCard` shows a neutral "Jamais stocké" badge when no `stock_levels` row exists in the displayed scope; red "0" stays for real stock-outs. (4) Rejected with reason (offline-first full local catalog, lazy list, NFR5 at 500 products) and pinned by an NFR5 regression test.

## Boundaries & Constraints

**Always:** search stays local SQLite, case-insensitive substring on name/SKU/category (Story 16.4 behavior unchanged for plain text); tenant-wide catalog listing per store (spec-catalog-product-visibility-other-stores) unchanged — never-stocked products stay in the list and in the "Rupture" tab; per-store stock scope of the card unchanged; bump `keevo/app/pubspec.yaml` 1.1.23+22 → 1.1.24+23.

**Never:** no backend change; no server pagination or infinite scroll; no change to sorting or to the Rupture/Stock faible membership rules; no restyle of the existing stock badge colors.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Literal percent | products "Remise 50%", "Riz 500g"; query `50%` | only "Remise 50%" | No error |
| Literal underscore | products "a_b", "axb"; query `a_b` | only "a_b" | No error |
| Backslash | product "C:\x"; query `\x` | matches "C:\x" | No error |
| Plain text | query `riz` | unchanged substring match on name/SKU/category | No error |
| Same name, two ids | local rows p1 "Coca" and p2 "coca" | both listed | No error |
| Never stocked here | no stock_levels row for active store | neutral "Jamais stocké" badge | No error |
| Sold out | row with quantity 0 | red "0" badge (unchanged) | No error |
| Stock loading | levels not loaded yet | no stock badge | No error |

</intent-contract>

## Code Map

- `keevo/app/lib/features/catalog/data/datasource/local_product_datasource.dart:47-67` -- `search()`: `'%${query.toLowerCase()}%'` + drift `.like(q)` on categories.name, products.name, products.sku. Drift 2.28 `like(String, {String? escapeChar})` is available. Also used by `ProductRepositoryImpl.create/createDraft` name-uniqueness check (escaping makes it more correct).
- `keevo/app/lib/features/pos/presentation/provider/pos_search_provider.dart:50-90` -- two raw `LOWER(p.name) LIKE ?` queries.
- `keevo/app/lib/features/contact/data/datasource/local_client_datasource.dart:24-31`, `local_supplier_datasource.dart:30-35` -- drift `.like(q)`.
- `keevo/app/lib/features/inventory/data/datasource/local_multi_store_stock_datasource.dart:~97` -- raw `p.name LIKE ?` with `'%$query%'`.
- `keevo/app/lib/features/catalog/presentation/provider/product_provider.dart:127-131` -- `seenNames` dedup to replace.
- Evidence for (2): backend unique index `uq_products_name` on `lower(name)` (`TenantSchemaSyncService.java:239`); local offline duplicates merged by server are dropped by `RestSyncService._dropMergedLocalProduct` (`rest_sync_service.dart:203-215`); local `create/createDraft` reject duplicate names.
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart:33-49,125-150` -- `visibleLevels`, `hasStockData` (any store), stock badge.
- `StockState.isLoading` in `catalog/presentation/provider/stock_provider.dart:78-95`.
- `keevo/app/lib/features/catalog/presentation/page/catalog_page.dart:663` -- `ListView.builder` (lazy) for the list.
- Tests: `test/features/catalog/data/datasource/local_product_datasource_test.dart`, `test/features/catalog/presentation/provider/product_provider_test.dart`, `test/features/catalog/presentation/widget/product_card_test.dart`, `test/features/pos/presentation/provider/pos_search_provider_test.dart`, `test/features/inventory/data/datasource/local_multi_store_stock_datasource_test.dart`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/storage/sql_like.dart` -- new: `const likeEscapeChar = r'\'`; `String likeContains(String raw)` returns `%<escaped raw>%` (caller decides casing), escaping `\` first then `%`, `_` -- single source of truth.
- `local_product_datasource.dart`, `local_client_datasource.dart`, `local_supplier_datasource.dart` -- use `likeContains(query.toLowerCase())` + `.like(q, escapeChar: likeEscapeChar)`.
- `pos_search_provider.dart`, `local_multi_store_stock_datasource.dart` -- `LIKE ? ESCAPE '\'` with `likeContains(...)` (keep each query's current casing semantics).
- `product_provider.dart` -- replace `seenNames` by an id-keyed dedup with a comment citing `uq_products_name` + `_dropMergedLocalProduct`.
- `product_card.dart` -- when stock finished loading and `visibleLevels` is empty, render a neutral (onSurfaceVariant) badge "Jamais stocké"; keep the numeric badge otherwise.
- tests -- datasource escaping cases (`%`, `_`, `\`, plain); POS search literal `%`; multi-store search literal `_`; provider keeps two same-name products; card shows "Jamais stocké" vs red "0"; NFR5 test: `search()` over 500 products completes < 500 ms on in-memory DB.
- `keevo/app/pubspec.yaml` -- 1.1.24+23.

**Acceptance Criteria:**
- Given a catalog containing "Remise 50%" and "Riz 500g", when the user searches `50%` in Catalogue, then only "Remise 50%" is listed; same literal behavior in POS search.
- Given two local products whose names differ only by case, when the catalog list is built, then both appear.
- Given the active store has no stock row for a product, when the card renders, then it shows "Jamais stocké" and not a red "0"; given a row with quantity 0, then the red "0" badge shows.
- Given 500 local products, when `search()` runs, then it returns in under 500 ms (NFR5).

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 27 findings — high 0, medium 1, low 21, false 5, maybe-false 0
- findings:
  - `[low]` `[patch]` (edge) Shared `StockState.error` (history/entry failures) hides "Jamais stocké" — rejected as fix (needs a levels-specific state flag; rare, error snackbar already shown); pinned instead: load-error test asserts no badge.
  - `[low]` `[reject]` (edge) Load failure renders no badge, indistinguishable — getLevels swallows remote failures and returns local; only a local Drift read can fail; adding an "unknown" badge is new surface for a near-unreachable state.
  - `[medium]` `[defer]` (edge/blind) Non-ASCII case folding (SQLite LOWER ASCII-only): "épicerie" misses "Épicerie" — pre-existing, not caused by this change → routed to new key `v1s-16-16-search-unicode-folding` (brief in epics-remediation-audit.md).
  - `[low]` `[reject]` (edge/verif/blind) NFR5 wall-clock assertion may flake — 500 rows in-memory search runs in a few ms vs 500 ms budget; margin ≫ 10x.
  - `[low]` `[patch]` (edge/blind) Dedup comment overstated name-collision guarantees (update() no check, non-ASCII, pull-before-push window) — comment rewritten to state these cases and why rows are shown on purpose.
  - `[low]` `[patch]` (verif) Client/supplier search escaping untested — search groups added to client/supplier repository offline-first tests (`50%`, phone `0_01`, `a_b`).
  - `[low]` `[patch]` (verif/blind) SKU and category LIKE escapes unpinned — SKU `KEV_01` vs `KEVX01` and category "50% off" vs "500g" cases added.
  - `[low]` `[patch]` (verif) Error branch of `neverStocked` untested — throwing stock repository test added.
  - `[low]` `[reject]` (verif other) Dedup safety-net claims unverified — covered by the comment patch; `_dropMergedLocalProduct` has its own tests from v1s-16-12.
  - `[low]` `[reject]` (blind) Duplicate window during sync now visible — intended (intent: never hide distinct rows); documented in the patched comment.
  - `[low]` `[reject]` (blind) isLoading flicker hides badge during a stock entry — sub-second, same as the numeric badge path pre-change; fix needs a separate levels flag.
  - `[low]` `[reject]` (blind) Badge markup duplicated (~25 lines) — cosmetic, no named divergence harm beyond font weight.
  - `[false]` `[reject]` (blind) No 360dp layout test for "Jamais stocké" — badges sit in a `Wrap`, which wraps instead of overflowing.
  - `[low]` `[patch]` (blind) Weak color assertion `isNot(errorColor)` — now asserts `colorScheme.onSurfaceVariant`.
  - `[low]` `[reject]` (blind) POS tests cover only `%`, multi-store only `_` — both go through the single `likeContains` helper covered exhaustively by `sql_like_test.dart`.
  - `[low]` `[reject]` (blind) NFR5 test does not time the provider path — provider adds two stock-map queries and an in-memory sort; documented rejection rests on lazy ListView + NFR5 sizing.
  - `[false]` `[reject]` (blind) Never-stocked products in Rupture tab untested — membership unchanged; covered by product_provider_test (visibility spec regression: store-b out-of-stock).
  - `[low]` `[reject]` (blind) SQLite test bootstrap copied again — pre-existing pattern in 10+ files; shared helper is unrelated refactor.
  - `[false]` `[reject]` (blind) Id-dedup test does not check which row is kept — `products.id` is the PK, two rows with one id cannot come from the DB.
  - `[low]` `[patch]` (blind) `likeContains` hard-codes backslash — replacements now derived from `likeEscapeChar`.
  - `[low]` `[reject]` (intent) Epic checklist not ticked — done at finalize (this run).
  - `[low]` `[reject]` (intent) SKU not used as dedup key — deliberate: SKU dedup would hide rows whose SKUs collide locally (auto-generated/offline SKUs); id is the sync identity.
  - `[false]` `[reject]` (intent) Tab surface not changed for item 3 — decision recorded: card badge disambiguates; tab membership must stay (visibility spec).
  - `[low]` `[reject]` (intent) NFR5 evidence covers search, not list — see above.
  - `[low]` `[reject]` (intent) Escaping widened to contacts/inventory beyond "catalog" — same defect class, one helper; tests added.
  - `[false]` `[reject]` (intent) No item closed as already covered — verified: 14-15/16-11 touched none of the four items (search, seenNames, card badge, list load unchanged at baseline).
  - `[low]` `[reject]` (intent) libsqlite3 override in POS test — required to run the new in-memory POS tests on Linux.

## Design Notes

**Item 3 decision (PO/UX, from docs):** UX spec reserves red for "rupture totale" (ux-design-specification.md:301,768). A product never stocked in this store is not a stock-out: neutral badge. It stays in the "Rupture" tab (visibility spec: needed for its first stock movement) — the badge disambiguates there too.

**Item 4 decision (rejected, reason):** offline-first architecture keeps the full tenant catalog in local SQLite (POS must work offline); NFR5/FR16 size the catalog at 500 products; the list is a lazy `ListView.builder`, so only visible `ProductCard`s (and their stock loads) are built. Paging a local query would add complexity without measurable gain; the NFR5 test pins the budget. Server-side `/api/v1/products` pull remains full by design (sync).

## Verification

**Commands:**
- `flutter test --concurrency=4 <touched test files>` -- all pass
- `run-tests-chunked.sh app test/core test/features/catalog test/features/pos test/features/contact test/features/inventory` -- no new failures (baseline: offline_gate_banner_test)
- `flutter analyze` -- no new errors/warnings

## Auto Run Result

**Summary:** 4/4 Story 16.14 items closed. (1) LIKE escaping via `core/storage/sql_like.dart` (`likeContains` + `ESCAPE '\'`) in catalog (name/SKU/category), POS, clients, suppliers, multi-store stock search. (2) `productList` dedup by id instead of name. (3) `ProductCard` neutral "Jamais stocké" badge when no stock row in the displayed scope; red "0" kept for real stock-outs. (4) Pagination rejected with reason (offline-first full local catalog, lazy ListView, NFR5/FR16 sizing), pinned by an NFR5 test. App 1.1.24+23.

**Files:** `lib/core/storage/sql_like.dart` (new helper); `catalog/data/datasource/local_product_datasource.dart`, `contact/data/datasource/local_client_datasource.dart`, `local_supplier_datasource.dart`, `inventory/data/datasource/local_multi_store_stock_datasource.dart`, `pos/presentation/provider/pos_search_provider.dart` (escaped LIKE); `catalog/presentation/provider/product_provider.dart` (id dedup); `catalog/presentation/widget/product_card.dart` (never-stocked badge); `pubspec.yaml`; tests: sql_like, local_product_datasource, product_provider, product_card, pos_search_provider, local_multi_store_stock_datasource, client/supplier repository offline-first.

**Review:** 1 pass, 27 findings — 7 patched (all low), 1 deferred (medium, pre-existing → `v1s-16-16-search-unicode-folding`), 19 rejected (reasons in triage log). Follow-up review: false (no high, no medium patched).

**Verification:** batched app tests — test/core +341 -1 (known baseline offline_gate_banner_test), catalog +131, pos +209, contact +24, inventory +173 all green; `flutter analyze` 630 issues (baseline, none new).

**Residual risks:** non-ASCII case folding (routed); after a failed stock write/history load the card hides its never-stocked badge until the next level reload; behavior change — a product stocked only in other stores now shows "Jamais stocké" in the active store instead of red "0".
