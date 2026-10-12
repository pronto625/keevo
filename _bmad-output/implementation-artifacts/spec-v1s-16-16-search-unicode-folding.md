---
title: 'v1s-16-16 — Local search insensitive to case and accents (Unicode folding)'
type: 'bugfix'
created: '2026-10-12'
baseline_revision: '0096cc3596098f249a1938280507e6ecc661d8ac'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Local searches lower-case the query in Dart (Unicode) but compare it with SQLite `LOWER()` / drift `.lower()`, which fold ASCII only: "épicerie" misses "Épicerie", and "epicerie" (accent-less typing, common for French-speaking users on mobile keyboards) misses both. The same ASCII-only folding weakens the local duplicate-name checks (`ProductRepositoryImpl.create/createDraft`, quick-add product).

**Approach:** One shared fold — lower-case + strip Latin accents/ligatures — defined once as a Dart map and emitted both as a Dart function (applied to the query) and as a pure-SQL expression (nested `replace()` over `lower(col)`) applied to the searched columns. No schema change, no registered SQLite function, no write-path maintenance. Every `likeContains` site and the in-memory search filters use it; duplicate-name checks use it to find candidates, then keep the server's case-only equality (`lower(name)` unique index — accents are distinct names server-side).

## Boundaries & Constraints

**Always:** keep the 16.14 LIKE escaping (`likeContains` + `ESCAPE '\'`); keep substring semantics on catalog name/SKU/category, POS name, client name/phone, supplier name, multi-store product name; NFR5 — `search()` over 500 products < 500 ms (existing test stays green); bump `keevo/app/pubspec.yaml` 1.1.24+23 → 1.1.25+24.

**Never:** no Drift schema/migration (schema stays 28); no `createFunction` registration (would require every `NativeDatabase` opener and the ~test in-memory DBs to register it, and the background-isolate setup); no fuzzy/typo matching (Story 16.4 scoped it out); no change to the sync category dedup (`rest_sync_service.dart` `GROUP BY LOWER(name)` — server-sourced rows, not a search); no backend change; duplicate-name checks must NOT become accent-insensitive ("Café" and "Cafe" stay two valid products, matching the backend `lower(name)` index).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Case beyond ASCII | product "Épicerie fine"; query `épicerie` | found | No error |
| Accent-less typing | product "Épicerie fine"; query `epicerie` | found | No error |
| Accented query, plain data | product "Epicerie"; query `Épicerie` | found | No error |
| Ligature | product "Œufs frais"; query `oeufs` | found | No error |
| Literal wildcard kept | "Remise 50%" vs "Riz 500g"; query `50%` | only "Remise 50%" | No error |
| Category / SKU | category "Électroménager"; query `electro` | its products found | No error |
| Duplicate by case | existing "Épicerie"; create "épicerie" | rejected as duplicate | `ProductException` (existing) |
| Distinct by accent | existing "Café"; create "Cafe" | created | No error |
| Outside Latin map | stored "ЖУК"; query `жук` | not found (documented limit) | No error |

</intent-contract>

## Code Map

- `keevo/app/lib/core/storage/sql_like.dart` -- `likeContains`, `likeEscapeChar` (16.14). New fold lives beside it.
- `keevo/app/lib/features/catalog/data/datasource/local_product_datasource.dart:47-68` -- `search()`: drift `.lower().like(q, escapeChar:)` on categories.name, products.name, products.sku.
- `keevo/app/lib/features/catalog/data/repository/product_repository_impl.dart:66-70,299-303` -- create/createDraft: `_local.search(name)` then Dart `toLowerCase()` equality (keep equality; candidates now Unicode-correct).
- `keevo/app/lib/features/inventory/presentation/provider/quick_add_product_provider.dart:173-179` -- `p.name.lower().equals(...)` + `getSingleOrNull()` (throws on 2 rows).
- `keevo/app/lib/features/pos/presentation/provider/pos_search_provider.dart:51-90` -- two raw `LOWER(p.name) LIKE ? ESCAPE '\\'`.
- `keevo/app/lib/features/contact/data/datasource/local_client_datasource.dart:24-31`, `local_supplier_datasource.dart:30-35` -- drift `.lower().like`.
- `keevo/app/lib/features/inventory/data/datasource/local_multi_store_stock_datasource.dart:85-106` -- raw `p.name LIKE ?` (no lower).
- In-memory filters (Dart, accent-sensitive): `pos/presentation/page/checkout_page.dart:505-508` (client autocomplete), `contact/presentation/page/supplier_form_page.dart:593`, `inventory/presentation/page/inventory_counting_page.dart:321-325`, `sync_indicator/presentation/widget/quarantined_operations_section.dart:541-547`.
- Tests: `test/core/storage/sql_like_test.dart`, `test/features/catalog/data/datasource/local_product_datasource_test.dart` (has NFR5 test), `test/features/catalog/data/repository/product_repository_offline_first_test.dart:118`, `test/features/pos/presentation/provider/pos_search_provider_test.dart`, `test/features/inventory/data/datasource/local_multi_store_stock_datasource_test.dart`, contact repository offline-first tests (search groups from 16-14).

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/storage/search_fold.dart` -- new: single fold table (Latin-1 + Œ/œ/Ÿ/ß letters, plus Cameroonian alphabet letters Ɛ/ɛ→e, Ɔ/ɔ→o, Ŋ/ŋ→n, upper and lower, → ASCII base; combining marks U+0300–U+0304, U+0306–U+0308, U+030A, U+030B, U+030C, U+0323, U+0327, U+0328, U+0331 → ''). Two Dart folds over the SAME table: `String foldForSql(String)` = table applied to the raw characters, then ASCII-only lower-case (A–Z) — an exact mirror of the SQL side, so a char outside the table keeps its case on both sides (same-case non-Latin text still matches, as before); `String foldForSearch(String)` = Unicode `toLowerCase()` + table, for in-memory Dart filters only (both sides folded in Dart). `Expression<String> foldedSqlExpr(Expression<String>)` (nested `replace` over `lower`, string literals); `String foldedSqlRaw(String columnSql)`; `String likeContainsFolded(String query)` = `likeContains(foldForSql(query))`.
- `local_product_datasource.dart`, `local_client_datasource.dart`, `local_supplier_datasource.dart` -- `foldedSqlExpr(col).like(likeContainsFolded(query), escapeChar: likeEscapeChar)` (client phone too).
- `pos_search_provider.dart`, `local_multi_store_stock_datasource.dart` -- `${foldedSqlRaw('p.name')} LIKE ? ESCAPE '\\'` with `likeContainsFolded(query)`.
- `quick_add_product_provider.dart` -- candidates via `foldedSqlExpr(p.name).equals(foldForSql(trimmed))` with `.get()`, duplicate when any candidate `name.toLowerCase() == trimmed.toLowerCase()`.
- `product_repository_impl.dart` -- comment only: candidates accent-insensitive, equality case-only (backend `uq_products_name` on `lower(name)`).
- In-memory filters (4 files above) -- compare `foldForSearch(field).contains(foldForSearch(query))`.
- tests -- `search_fold_test.dart` (Dart fold cases; parity: for every table key AND for non-table chars `Ж`, `ж`, `Ā`, `Ł`, mixed strings, the SQL fold in an in-memory DB equals `foldForSql(input)`; every key is one code point and no key/value contains `'`); datasource cases from the matrix (name, SKU, category, ligature, `50%` still literal, `ЖУК` finds `ЖУК`, `жук` does not find `ЖУК`); `ndole` finds "Ndɔlɛ"; POS `epicerie`; multi-store `épicerie` and `ЖУК` finds `ЖУК`; client/supplier accent-less; repository create "épicerie" vs "Épicerie" rejected, "Cafe" vs "Café" allowed; quick-add duplicate by non-ASCII case and identical non-Latin name ("ЖУК" twice) detected; widget tests typing an accent-less query into the inventory counting search, the checkout client autocomplete and the quarantine "Rattacher" product picker, each asserting the accented item is listed and a non-matching item is hidden.
- `keevo/app/pubspec.yaml` -- 1.1.25+24.

**Acceptance Criteria:**
- Given a product "Épicerie fine", when the user types `epicerie` or `ÉPICERIE` in Catalogue or POS search, then it is listed.
- Given a client "Hélène" and a supplier "Société Générale", when searching `helene` / `societe`, then each is found; the multi-store stock search finds "Épicerie" from `epicerie`.
- Given a local product "Épicerie", when creating "épicerie" (catalog form or quick-add), then the duplicate is rejected; creating "Epicerie" next to "Épicerie" is allowed.
- Given 500 local products, when `search()` runs, then it returns in under 500 ms (existing NFR5 test).

## Spec Change Log

### 2026-10-12 — Loop 1 (review pass 1, bad_spec)
- **Trigger:** edge-case + blind hunters — Dart query fold used Unicode `toLowerCase()` while SQL `lower()` folds ASCII only; for any cased char outside the table (Cyrillic, Ā, Ł…) the query was lower-cased and the column was not, so the multi-store search regressed (typing exact "ЖУК" no longer found "ЖУК") and quick-add / create duplicate checks missed identical non-Latin names. Verification-gap: the four in-memory filters had no test. Blind: Cameroonian letters ɛ/ɔ/ŋ missing.
- **Amended:** Tasks — split `foldForSql` (exact SQL mirror, used for every SQL path) from `foldForSearch` (Unicode, in-memory only); add ɛ/ɔ/ŋ + U+0323/U+0331; parity tests on non-table chars; widget tests for three in-memory filters.
- **Known-bad state avoided:** SQL searches where the Dart side folds a char the SQL side cannot, silently dropping exact-case matches.
- **KEEP:** everything else from attempt 1 (patch saved at `/tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/04db4194-544b-4c10-a501-504a3970eded/scratchpad/attempt1-16-16.patch`, may be re-applied with `git apply` as the starting point): file layout of `search_fold.dart` (`\uXXXX` table with comments, `foldedSqlExpr`/`foldedSqlRaw` builders), all call-site rewrites, quick-add `.get()` + case-only equality, repository comments, multi-store `final sql`, all attempt-1 tests (adjusting only the ones that depended on Unicode lower-casing of the query on SQL paths), version 1.1.25+24.

## Review Triage Log

### 2026-10-12 — Review pass 1
- verdicts: 22 findings — high 0, medium 5, low 13, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Unicode read as Latin/French only, not all scripts — documented limit (Cameroon FR/EN), same-case non-Latin restored by the loop-1 amendment; full ICU folding needs a registered function (rejected in Design Notes).
  - `[low]` `[reject]` (intent) Repository create/createDraft tests mock `search()` and would pass pre-change — the equality is unchanged; the fixed part (candidate retrieval) is covered by real-DB datasource tests.
  - `[false]` `[reject]` (intent) Mechanism is neither stored column nor registered function — intent says "e.g."; decision recorded in Design Notes; no migration needed.
  - `[false]` `[reject]` (intent) NFR5 not evidenced — existing `local_product_datasource_test` NFR5 test runs `search()` over 500 products through the fold and passed (catalog batch +143 green).
  - `[medium]` `[bad_spec]` (intent) No screen-level / in-memory filter tests — grouped with verification-gap row; amendment adds widget tests.
  - `[false]` `[reject]` (intent) Changes beyond named items — intent: shared normalization for all local searches in Catalogue/POS/Contacts; in-memory filters and quick-add are the same defect class.
  - `[medium]` `[bad_spec]` (verif) Four in-memory filters untested — amendment: widget tests for inventory counting, checkout autocomplete, quarantine picker (supplier picker identical one-liner).
  - `[medium]` `[bad_spec]` (edge) Dart Unicode lower vs SQL ASCII lower asymmetry for non-table chars — amendment: `foldForSql` exact SQL mirror for every SQL path.
  - `[medium]` `[bad_spec]` (edge) Multi-store search regression: exact "ЖУК" no longer finds "ЖУК" — same root, same amendment + test.
  - `[low]` `[bad_spec]` (edge) Quick-add misses identical non-Latin duplicate — same root (pre-existing for create, fixed by the amendment) + test.
  - `[low]` `[bad_spec]` (edge) create/createDraft miss identical non-Latin duplicate — same root.
  - `[low]` `[reject]` (edge) Combining-marks-only query in multi-store folds to `%%` — unrealistic input from a mobile keyboard; fix is a new guard.
  - `[low]` `[reject]` (edge) Same for POS (raw length >= 2, folded empty) — same reason.
  - `[low]` `[reject]` (edge) NFD marks outside the table (beyond U+0323/U+0331 added) stay accent-sensitive — mobile keyboards emit NFC; documented limit.
  - `[medium]` `[bad_spec]` (blind) Dart/SQL case mismatch, multi-store regression — same root as the edge rows.
  - `[low]` `[bad_spec]` (blind) Cameroonian letters ɛ/ɔ/ŋ missing — amendment adds them to the table.
  - `[low]` `[reject]` (blind) ~80 `replace()` per column unmeasured; phone fold useless; per-rune allocation — NFR5 test bounds the SQL cost at the sized catalog; phone fold harmless; in-memory lists are small.
  - `[low]` `[bad_spec]` (blind) Parity tests miss non-table chars and pin the broken behaviour — amendment adds non-table parity + `ЖУК` exact-case tests.
  - `[low]` `[bad_spec]` (blind) In-memory filters untested — same as verification-gap row.
  - `[low]` `[reject]` (blind) `foldedSqlRaw` does not escape `'` in keys — const reviewed table; amendment adds a test asserting no key/value contains `'`.
  - `[low]` `[reject]` (blind) Results sorted by raw bytes, accented names last — pre-existing ordering, 16.14 "no change to sorting"; not a matching defect.
  - `[false]` `[reject]` (blind) Presentation imports a drift file for a string helper — no named harm: these widgets already depend on storage/providers; drift is tree-shaken-free dependency already in the app.

### 2026-10-12 — Review pass 2 (after loop 1)
- verdicts: 28 findings — high 0, medium 0, low 22, false 6, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Parity relies on non-ICU SQLite; shipped SQLCipher untested — `sqlcipher_flutter_libs` is built without ICU, so its `lower()` folds ASCII only like the host lib the parity test runs on.
  - `[low]` `[reject]` (blind) ~94 nested `replace()` per column, rebuilt per call, phone fold useless — NFR5 test (500 products, 3 folded columns) passes far under budget; caching is premature; phone fold harmless.
  - `[low]` `[patch]` (blind) Cameroonian/tone-marked letters missing (ɓ ɗ ə ɨ ʉ ƴ, ā…ū, ǎ…ǔ, ń ǹ ḿ ẽ) — table extended (upper + lower), covered by table-wide parity test + fold test.
  - `[low]` `[reject]` (blind) Accented names sort after ASCII — pre-existing byte ordering; 16.14 boundary "no change to sorting"; not a matching defect.
  - `[low]` `[reject]` (blind) In-memory filters fold non-Latin case, SQL does not — documented limit (Design Notes); in-memory keeps its pre-existing Unicode behaviour.
  - `[low]` `[reject]` (blind) No NFC normalization on write; NFD/NFC twins can be created — backend `lower(name)` has the same semantics; mobile keyboards emit NFC; out of this story's search scope.
  - `[low]` `[reject]` (blind) POS / multi-store do not search SKU — pre-existing scope of those searches (name-only by design), unchanged by this story.
  - `[low]` `[patch]` (blind) Supplier picker untested; repository dedup tests mock search; NULL sku — supplier picker widget test added; candidate retrieval covered by real-DB datasource tests; `products.sku` is non-null in the Drift table.
  - `[low]` `[patch]` (blind) Stale name `lowerQuery` in POS search — renamed `foldedPattern`.
  - `[low]` `[reject]` (blind) Duplicated test body in search_fold_test — cosmetic, no named harm.
  - `[low]` `[reject]` (blind) In-memory fold runs on every rebuild — lists are small (picker/autocomplete), allocation negligible.
  - `[low]` `[patch]` (verif) Supplier form product picker folding untested — widget test added in contact_list_pages_test (same fix as blind row).
  - `[low]` `[patch]` (verif) SKU branch of in-memory filters untested — inventory counting test gains an 'ÉTÉ-01' SKU row found by 'ete-01'.
  - `[low]` `[reject]` (verif other) Fold cost unmeasured on POS/multi-store — same fold cost as catalog search, which is bounded by the NFR5 test.
  - `[low]` `[reject]` (verif other) Quick-add misses "жук" vs "ЖУК" — documented limit; backend rejection surfaces via sync quarantine (existing flow).
  - `[low]` `[reject]` (edge) Quick-add misses non-table case variants — pre-existing (old `.lower()` missed them too); documented limit; fix adds a full-table Dart scan.
  - `[low]` `[reject]` (edge) create/createDraft same — same reason.
  - `[low]` `[patch]` (edge) NFC tone-marked letters not folded — same patch as the blind table row.
  - `[low]` `[reject]` (edge) Unlisted combining marks (U+0309, U+031B…) — Vietnamese-specific; mobile keyboards emit NFC; documented limit.
  - `[low]` `[reject]` (edge) Multi-store: combining-mark-only query → '%%' — carried from pass 1.
  - `[low]` `[reject]` (edge) POS: length guard on raw query vs folded — carried from pass 1.
  - `[low]` `[reject]` (edge) Checkout autocomplete: folded-empty query lists every client — harmless (same as empty query), unrealistic input.
  - `[false]` `[reject]` (intent) Mechanism neither column nor function; SQLCipher claim untested — carried: intent says "e.g."; `lower()`/`replace()` are core SQLite built-ins present in SQLCipher.
  - `[low]` `[reject]` (intent) Partial Unicode rejection not stated against the item — recorded explicitly in the epic checklist note at finalize.
  - `[false]` `[reject]` (intent) Duplicate check is candidate-only (D-b) — carried: intended, matches backend `lower(name)`; real-DB candidate retrieval covered at datasource level.
  - `[false]` `[reject]` (intent) Tests run below the screens — Catalogue/Contacts repositories delegate `search()` to these datasources (verified pass 1); POS notifier is the screen's data source.
  - `[false]` `[reject]` (intent) NFR5 only on catalog — catalog search folds 3 columns, a superset of POS/multi-store (1 column).
  - `[false]` `[reject]` (intent) Scope growth beyond named sites — carried from pass 1.

## Design Notes

**Why SQL `replace()` chain, not a column or a function:** a `search_key` column needs a migration plus maintenance on every write path (local create/update, sync upserts via raw `INSERT`, categories, clients, suppliers) — many places to forget. A registered Dart function needs registration in every opener (SQLCipher main DB, background isolate, ~dozens of test in-memory DBs) or queries crash with `no such function`. The `replace` chain is pure SQLite, works unchanged under SQLCipher and in tests, and costs ~70 C `replace` calls per row/column (≈ a few ms for 500 products; NFR5 test guards it). Single table → Dart and SQL folds cannot drift (parity test).

**Limit (accepted):** folding covers Latin-1/French letters plus ɛ/ɔ/ŋ (Cameroon: FR/EN + common national-language letters). Other scripts stay ASCII-case-only in SQL, as before: same-case text matches, different-case does not. `foldForSql` must therefore never lower-case beyond ASCII, or the query and column diverge.

## Verification

**Commands:**
- `flutter test --concurrency=4 <touched test files>` -- all pass
- `run-tests-chunked.sh app test/core test/features/catalog test/features/pos test/features/contact test/features/inventory test/features/stores test/features/sync_indicator` -- no new failures (baseline: offline_gate_banner_test)
- `flutter analyze` -- no new errors/warnings

## Auto Run Result

**Summary:** Story 16.16 item closed. One shared fold (`core/storage/search_fold.dart`, single `searchFoldTable`: Latin-1, Œ/Ÿ, Cameroonian alphabet letters ɛ ɔ ŋ ɓ ɗ ə ɨ ʉ ƴ, macron/caron tone vowels, ń ǹ ḿ ẽ, common combining marks → ASCII) applied in SQL as nested `replace()` over `lower(col)` and in Dart as `foldForSql` (exact SQL mirror, ASCII-only lower) for every SQL search, and `foldForSearch` (Unicode lower + table) for in-memory filters. "epicerie"/"épicerie"/"ÉPICERIE" now find "Épicerie" in Catalogue (name/SKU/category), POS, clients (name/phone), suppliers, multi-store stock search, plus inventory counting, checkout client autocomplete, supplier product picker and quarantine product picker. 16.14 LIKE escaping kept. No schema change (stays 28). Duplicate-name checks (create/createDraft, quick-add) find candidates through the fold and keep case-only equality (backend `uq_products_name` on `lower(name)`): "épicerie" vs "Épicerie" rejected, "Cafe" vs "Café" allowed. App 1.1.25+24.

**Files:** `lib/core/storage/search_fold.dart` (new); `catalog/data/datasource/local_product_datasource.dart`, `contact/data/datasource/local_client_datasource.dart`, `local_supplier_datasource.dart`, `inventory/data/datasource/local_multi_store_stock_datasource.dart`, `pos/presentation/provider/pos_search_provider.dart` (folded SQL search); `inventory/presentation/provider/quick_add_product_provider.dart` (folded candidates + case-only equality, no more `getSingleOrNull` crash on 2 rows); `catalog/data/repository/product_repository_impl.dart` (comments); `pos/presentation/page/checkout_page.dart`, `contact/presentation/page/supplier_form_page.dart`, `inventory/presentation/page/inventory_counting_page.dart`, `sync_indicator/presentation/widget/quarantined_operations_section.dart` (in-memory fold); `pubspec.yaml`; tests: search_fold (new, SQL/Dart parity over every key + non-table chars), local_product_datasource, product_repository_offline_first, client/supplier repository offline-first, local_multi_store_stock_datasource, quick_add_product_provider, pos_search_provider, checkout_page, inventory_counting_page, quarantined_operations_section, contact_list_pages.

**Review:** 2 passes. Pass 1: 22 findings (medium 5, low 13, false 4) → bad_spec loop 1 (Dart Unicode lower vs SQL ASCII lower asymmetry regressed exact-case non-Latin multi-store search; in-memory filters untested; ɛ/ɔ/ŋ missing) — spec amended, code re-derived. Pass 2: 28 findings (low 22, false 6) — 4 patched (table extension with GACL + tone vowels, supplier picker widget test, inventory SKU folding test, `foldedPattern` rename), 0 deferred, rest rejected with reasons in the triage log. Follow-up review: false (patched: 0 high, 0 medium, 4 low).

**Verification:** batched app tests — test/core +356 -1 (known baseline offline_gate_banner_test), catalog +145, pos +212, contact +27, inventory +180, stores +45, sync_indicator +59 all green; NFR5 test (500 products) green; `flutter analyze` 630 issues (baseline, none new).

**Residual risks:** other scripts (Cyrillic, Greek…) and Latin letters outside the table remain ASCII-case-only in SQL (same-case match only; documented); results still sorted by raw bytes (accented names after ASCII); fold cost (~120 `replace()` per column) measured only on the test host via NFR5.

