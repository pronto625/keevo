---
title: '19-11b — Super-admin screens without a per-account loop (analytics, operations, admin catalogue)'
type: 'refactor'
created: '2026-11-10'
status: 'done'
baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-13-api-super-admin-heritee-bref.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-11-charge-nombre-tenants.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Remaining index / streaming / keyset follow-ups of the three screens and the other admin per-tenant loops, routed to 19-11c.
    evidence: |-
      Catalogue list has no products(updated_at, id) tenant index and deep pages read chunks x (offset + size) rows; export loads all rows in memory;
      TenantLastActivitySql legs are not index-bounded; ILIKE misses the lower(name) trigram index; adoption probes lack indexes;
      tenant list / sync monitoring / revenue plans / alerts / health / jobs still loop per tenant (pre-existing).
    location: >-
      _bmad-output/planning-artifacts/epics-fusion-keevo-bref.md (Story 19.11c)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Three super-admin services still run one statement (or six) per tenant schema at request time — `AdminAnalyticsService` (overview stores/products, top stores, adoption, geography), `AdminOperationsService` (tenant activity table, inactive tenants) and `AdminCatalogService` (product list, summary, CSV export). Prod has ~25 accounts and will reach thousands after the Bref migration (epic 24): the screens would issue thousands of statements per call (routed from review 20-13, FUS-NFR11, R1).

**Approach:** Move every remaining loop onto the cross-tenant pattern of 20-13 — one tenant + table-presence catalogue query per request (`CrossTenantSql.withPresence`), then per-chunk `UNION ALL` statements (`CrossTenantSql.query`, chunk of 100, per-tenant retry on a broken schema) aggregated or top-K-limited in SQL and merged in Java; the owner phones of the geography screen in one set-based public query through a batch method of the identity `OwnerRecipientResolver` api. The tenant activity table enriches only the requested page.

## Boundaries & Constraints

**Always:**
- Response shapes, field names, sort orders, filters and French error messages of every endpoint unchanged (V1 / dashboard compatibility, additive only).
- Same business definitions: STORE-type active stores, non-archived products, COMPLETED sales on `COALESCE(occurred_at, created_at)`, WAT day bounds, `TenantLastActivitySql` merchant last activity, plan from `subscriptions` else `public.tenants.plan_type`, owner phone = primary active owner normalised `+<digits>`.
- Statement count per call = constant + ⌈T/100⌉ per figure (T = tenants holding the tables); never a statement per tenant except the per-tenant retry of a failing chunk.
- A missing module table is never a statement error: the branch is omitted or the figure is `0` / `false` / `NULL` for that tenant (table presence from the catalogue).
- A broken tenant schema is skipped with a WARN (tenant id only); connection / resource failures fail the request.
- Schema names interpolated only after `TenantSchema` validation; tenant ids as parsed-UUID literals; user input always bound.

**Never:** no summary / materialised table, no scheduler, no cache added; no change of other admin services (tenant list, sync monitoring, revenue current plans, alerts, health probe, feedback labels — routed to 19-11c); no OFFSET-to-keyset contract change on `/admin/catalog/products`; no change to `keevo/app` or `keevo/dashboard`; no public migration unless an index is proven missing (V76 reserved, unused if not).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Count does not grow | 3 then 12 seeded tenants | Each endpoint issues the same number of JDBC statements | — |
| Module table missing | tenant without `inventory_sessions` / `reports` | Adoption: tenant still counted active, feature `false`; overview/activity figures from the tables present | No statement error |
| Broken schema | tenant row with a valid name but a dropped table mid-request | Chunk retried tenant by tenant; that tenant skipped, others kept | WARN with tenant id |
| DB outage | connection failure | Request fails (5xx), never an all-zero 200 | rethrown |
| Activity page | page 2, size 25, 60 tenants | Sorted by GMV desc then name; only the 25 page tenants enriched; total = 60 | — |
| Catalogue page | page 3, size 25, filters | Same rows/order as before (updated_at desc, nulls last); total = filtered count | — |
| Geography | owners with `+237…`, `+44…`, none | CM / OTHER / UNKNOWN counts, as before | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/crosstenant/CrossTenantSql.java` -- `withPresence` (1 catalogue query), `query` (chunked union + retry), `tenantLiteral`. Reuse as is.
- `.../admin/crosstenant/TenantTable.java` -- add specs: `INVENTORY_SESSIONS`, `STOCK_TRANSFERS`, `REPORTS`, `TENANT_PREFERENCES`, `NOTIFICATION_COOLDOWNS`, `SYNC_OPERATIONS_LOG`, `SUBSCRIPTIONS`, `AUDIT_LOG` (columns read by these screens; DDL in `db/modules/{inventory,reporting,core,messaging,sync}/V1__*.sql`).
- `.../admin/TenantLastActivitySql.java` -- add a presence-aware builder (missing sales / audit / sync leg → `NULL`), params appended to the branch list.
- `.../admin/analytics/application/service/AdminAnalyticsService.java:122-157,225-238,315-353,401-450` -- overview, geography, top stores, adoption loops.
- `.../admin/operations/application/service/AdminOperationsService.java:130-200` -- activity table + inactive tenants loops.
- `.../admin/catalog/application/service/AdminCatalogService.java:53-107,191-260` -- list / summary / export loops.
- `.../identity/api/OwnerRecipientResolver.java`, `.../identity/auth/adapter/out/persistence/impl/OwnerRecipientResolverAdapter.java`, `UserRepository` + `UserRepositoryAdapter` (`OWNER_BY_SCHEMA_SQL` line 52) -- batch owner phones (`DISTINCT ON (schema_name)` with the same ORDER BY).
- `keevo/backend/src/test/java/com/keevo/architecture/OwnerRecipientResolutionArchTest.java` -- extend the guarded method set to the batch repository method.
- Tests: `AdminAnalyticsServiceIntegrationTest`, `AdminOperationsServiceIntegrationTest` (PostgreSQL, shared DB), unit `AdminAnalyticsServiceTest`, `AdminOperationsServiceTest`, `AdminCatalogServiceTest` (mock SQL per tenant — rewrite the loop-specific cases).

## Tasks & Acceptance

**Execution:**
- `admin/crosstenant/TenantTable.java` -- add the table specs -- presence-aware branches.
- `admin/TenantLastActivitySql.java` -- add `expression(TenantBranch, List<Object>)` -- one definition, presence-aware.
- `identity/**` -- `resolveOwnerWhatsAppPhones(Collection<String>)` (api default = loop, adapter = one query) + `UserRepository.findPrimaryOwnersByTenantSchemaNames` -- geography in one statement.
- `admin/analytics/.../AdminAnalyticsService.java` -- overview / top stores (per-chunk top 20 + Java merge) / adoption (FILTER counts) / geography on chunked unions -- constant + ⌈T/c⌉ statements.
- `admin/operations/.../AdminOperationsService.java` -- activity: sort all tenants on revenue totals in Java, enrich the page tenants in one union; inactive: chunked union filtered in SQL -- page cost constant.
- `admin/catalog/.../AdminCatalogService.java` -- summary: one branch with six `COUNT(*) FILTER`; list: count union + page union (`ORDER BY updated_at DESC NULLS LAST, tenant, id LIMIT offset+size` per branch and chunk) merged in Java; export = same with no limit -- no per-tenant loop.
- Tests -- rewrite loop-specific unit cases, extend PostgreSQL tests (missing module table, broken tenant, page enrichment, catalogue list/summary), add `AdminScreensStatementCountIntegrationTest` (3 vs 12 tenants, counting JDBC statements through a DataSource proxy).
- `docs/entitlements/endpoints.md` -- regenerate (no route change expected).

**Acceptance Criteria:**
- Given 3 then 12 provisioned tenants, when each screen endpoint of the three services is called, then the number of JDBC statements is identical.
- Given a tenant whose schema lacks an optional module table, when adoption / overview / activity are read, then no statement fails and the tenant's other figures are kept.
- Given the PostgreSQL integration suites of 9-4 / 9-7, when they run on the new code, then every existing assertion still holds.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 43 findings — high 2, medium 14, low 16, false 4, maybe-false 7
- findings:
  - Intent alignment
    - `[medium]` `[reject]` Scope reads « every super-admin screen » but only the three routed services changed — decision D1 (story scope = the 3 routed items, brief 19.11b); the other loops are routed to 19-11c with file:line.
    - `[medium]` `[reject]` Cost is constant + ⌈T/100⌉, not O(1) — decision D2 (lock budget makes one all-tenant statement impossible; the intent lists « UNION ALL generated once » as acceptable; summary table rejected in Design Notes).
    - `[low]` `[reject]` Count test runs at chunk size 1000 — deliberate (shared test DB); chunk growth covered by `CrossTenantSqlChunkTest`; documented in the test Javadoc.
    - `[low]` `[reject]` Count measured at the service, not HTTP — controllers are thin delegations (no SQL), the statements all come from the services.
    - `[maybe-false]` `[defer]` Rows read still grow with T × page depth / export — routed to 19-11c (keyset + streaming export).
    - `[low]` `[reject]` 9-7 assertion on the unreadable tenant changed — decision D3 (an account is never hidden from the activity table).
  - FUS-NFR11 layer
    - `[medium]` `[patch]` Catalogue branch order not index-servable + misleading Javadoc — `BRANCH_ORDER` now `p.updated_at DESC NULLS LAST, p.id` (uuid byte order = hex text C order), Javadoc corrected; the missing tenant index routed to 19-11c (catalog module migration, out of the reserved public V76).
    - `[medium]` `[patch]` `resolveOwnerWhatsAppPhones` default loop could bring the N+1 back — method made abstract.
    - `[medium]` `[patch]` Top stores Java merge tie-break ≠ SQL — merge on `RankedStore` (gmv, lower name, tenant id, store id), same order as SQL.
    - `[maybe-false]` `[defer]` `TenantLastActivitySql` sales leg full scan — pre-existing expression, routed to 19-11c.
    - `[maybe-false]` `[defer]` audit leg backward walk — pre-existing, routed to 19-11c.
    - `[maybe-false]` `[defer]` `user_daily_activity` leg reads all tenant rows — pre-existing, routed to 19-11c.
    - `[low]` `[defer]` ILIKE cannot use the `lower(name)` trigram index — pre-existing filter, routed to 19-11c.
    - `[maybe-false]` `[defer]` Export loads every product in memory — pre-existing, routed to 19-11c.
    - `[low]` `[defer]` Deep catalogue pages — routed to 19-11c (keyset).
    - `[low]` `[defer]` Adoption EXISTS probes without index — pre-existing, routed to 19-11c.
  - Blind hunter
    - `[high]` `[patch]` Full-column presence specs drop older schemas (products barcode / merged_into_id, sales payment_mode / client_id, product detail) — narrow specs per branch (`SALES_CORE`, `PRODUCTS_ARCHIVED`, `PRODUCTS_LIST`, `PRODUCTS_CATEGORY`, `PRODUCTS_STATUS`, `PRODUCTS_STOCK`, `PRODUCTS_CREATED`, `PRODUCTS_TABLE`); summary counts read 0 per missing column group.
    - `[low]` `[patch]` Possible NPE on `byId.get(tenant_id)` in the catalogue mapper — row skipped when the tenant is unknown.
    - `[medium]` `[patch]` Two catalogue branches interpolate the schema without `validate` — `validSchema(...)` at use.
    - `[low]` `[defer]` Deep pages unbounded in rows — same as FUS item, 19-11c.
    - `[medium]` `[patch]` Top stores tie-break — same root cause as FUS item.
    - `[medium]` `[patch]` Activity ranking without id tie-break → rows repeated / lost between pages — `thenComparing(TenantRef::id)`.
    - `[low]` `[reject]` Unreadable tenant shown like a dormant one / absent from inactive list — decision D3 (listed in the table, not asserted inactive); no new field (additive DTO change not worth it for a rare case).
    - `[medium]` `[patch]` Interface default N+1 — same root cause as FUS item.
    - `[medium]` `[patch]` No test for the batch owner query — `OwnerLookupPostgresIntegrationTest.batch_sameRuleAsSingleLookup`.
    - `[low]` `[patch]` Last-activity SQL duplicated — `expression(String)` now delegates to the branch builder.
    - `[low]` `[patch]` Geography now fails as a whole on a query error — documented in code (public-only query = database failure, decision D4).
    - `[low]` `[patch]` Cosmetics (double blank line, comment indent) fixed; 19.11c AC wording fixed (3 then 12).
    - `[maybe-false]` `[reject]` Diff lacks spec / sprint-status — they are in the worktree and committed with the story.
  - Edge case hunter
    - `[medium]` `[patch]` Activity tie-break — same root cause as above.
    - `[low]` `[reject]` Unreadable tenant shown « Jamais » — decision D3.
    - `[medium]` `[patch]` `pageSize = 0` with a negative page → 500 — validation now applies to every non-export call (422).
    - `[maybe-false]` `[reject]` Count union and page union may skip different tenants — only when a schema fails transiently between two statements; the next request is consistent; no state kept.
    - `[high]` `[patch]` Presence specs too wide — same root cause as blind hunter item.
    - `[low]` `[patch]` Top stores collation tie — same root cause.
    - `[false]` `[reject]` Default method calls the single lookup with null — method is abstract now, the adapter filters null / blank.
    - `[low]` `[patch]` Geography whole-request failure — documented (D4).
    - `[false]` `[reject]` Invalid-schema WARN lost — `queryable()` (analytics), `loadActiveTenants()` (operations) and `onlyValidSchemas()` (catalogue) still log the tenant id.
    - `[low]` `[reject]` 9-7 AC « every assertion holds » vs changed broken-tenant assertion — D3, recorded here.
    - `[false]` `[reject]` Test name differs from the Tasks wording — fix would edit the spec.
    - `[false]` `[reject]` Missing unrelated column zeroes a leg — fixed by the narrow specs (same root cause).
  - Verification gap
    - `[medium]` `[patch]` No real-SQL test where the per-branch LIMIT cuts rows — `cataloguePages_globalOrderAcrossTenants` (two interleaved tenants, 9 products, pages of 2).
    - `[medium]` `[patch]` No test of which owner the batch picks — same as blind hunter item (multi-owner fixtures).

## Design Notes

**Pattern choice.** Composite chunked query (20-13) over a periodically refreshed summary table: the screens take arbitrary windows (top stores `from/to`, operations CUSTOM up to 366 days, adoption 30 days rolling) and filters (catalogue search / status / stock level), so a summary would need per-day per-store aggregates plus a copy of every product row, write hooks in four modules and a refresh job with staleness — two sources of truth for a console read a few times a day. The union keeps the module tables the single source, fresh, and reuses audited code (presence check, lock-budget chunks, per-tenant retry). A single all-tenant statement is impossible beyond ~250 tenants (`max_locks_per_transaction`), hence chunks.

**Cost (T = tenants holding the tables, c = 100).**
- Analytics overview: 2 activity + 1 tenants + 1 presence + ⌈T/c⌉ figures + revenue (⌈T/c⌉). Top stores: 1 + 1 + ⌈T/c⌉, each chunk returns ≤ 20 rows. Adoption: 1 active ids + 1 + 1 + ⌈A/c⌉ (A = active tenants). Geography: 1 tenants + 1 owner phones.
- Operations activity: 1 tenants + revenue + 1 presence (page tenants only) + 1 union (≤ 100 page tenants). Inactive: 1 + 1 + ⌈T/c⌉, only inactive rows returned.
- Catalogue summary: 1 + 1 + ⌈T/c⌉. List: 1 + 1 + ⌈T/c⌉ count + ⌈T/c⌉ page (each branch ≤ offset+size rows, top-N sort on `updated_at` — no index yet, routed to 19-11c); export unbounded by nature (all matching rows), ⌈T/c⌉ statements.
- Java: O(T) tenant maps, O(chunks·k log) merges; no O(n²).

**Presence specs.** Each branch checks only the columns it reads (`SALES_CORE`, `PRODUCTS_ARCHIVED`, `PRODUCTS_LIST`, `PRODUCTS_CATEGORY`, `PRODUCTS_STATUS`, `PRODUCTS_STOCK`, `PRODUCTS_CREATED`, `PRODUCTS_TABLE`, module tables): an older schema missing an unrelated column keeps its figures.

**Decisions (taken by default, conservative).**
- D1 — Scope = the three routed services (brief 19.11b). The other request-time loops (tenant list + last-activity filter, sync monitoring, revenue current plans, alerts, health probe, background jobs) and the index / streaming follow-ups are routed to **19-11c** with file:line.
- D2 — « Constant » = constant up to 100 tenants, then +1 statement per 100 tenants per figure: one statement over every schema exhausts `max_locks_per_transaction` near 250 tenants (20-13 measure), and a summary table would duplicate four modules' data with staleness. No V76 migration (no public index needed).
- D3 — A tenant whose schema cannot be read stays in the activity table (an account is never hidden; plan from `public.tenants`, 0 stores, no last activity) but is not asserted « inactive » in the inactive list.
- D4 — Geography resolves every owner phone in one public query: a failure there is a database failure and fails the screen (before: per-tenant skip around a per-tenant lookup).
- D5 — `pageSize = 0` keeps its historical meaning (every row); a negative page / size is now 422 « Le numéro et la taille de page doivent être positifs » (was a 500).

**Statement-count test.** Counts `prepareStatement` / `createStatement` on a proxied DataSource; runs with `keevo.admin.cross-tenant.chunk-size=1000` so the shared test database (other classes' tenants) never crosses a chunk boundary — the ⌈T/c⌉ growth is the documented lock budget, covered by `CrossTenantSqlChunkTest`.

## Mobile (React Native — dépôt AI/bref)

Statut : rien à faire côté mobile. Cette story ne touche que des écrans super-admin (`/api/v1/admin/**`), jamais appelés par l'app. Pas de clé `m19-11b`.

## Dashboard super-admin (autre dépôt)

Statut : API livrée sans changement de contrat (mêmes routes, mêmes champs, mêmes tris et filtres). Pas de clé `d19-11b` : rien à implémenter.

Ce qui change, à connaître seulement :
- `GET /api/v1/admin/operations/tenants` (tableau d'activité) : une entreprise dont les données ne peuvent pas être lues reste listée (offre issue de la fiche entreprise, 0 boutique, dernière activité vide → afficher « — »), au lieu de disparaître ; `totalElements` compte donc toutes les entreprises actives.
- `GET /api/v1/admin/catalog/products` : un numéro de page négatif répond 422 `VALIDATION_ERROR` « Le numéro et la taille de page doivent être positifs » (avant : 500) — afficher le message.
- Les écrans analytics / opérations / catalogue répondent avec un nombre de requêtes base constant jusqu'à 100 entreprises (puis +1 par tranche de 100) : aucun changement d'usage.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t1911b admin.analytics admin.operations admin.catalog admin.crosstenant identity.auth architecture` -- expected: all green.

**Manual checks (if no CLI):**
- Live on `keevo_dev_t1911b` (port 4591): each endpoint answers 200 with the same figures before/after; backend log shows no per-tenant statements.

## Auto Run Result

Status: done (reprise après redémarrage du 11/11/2026 : tests relancés, contrôle live refait).

**Summary.** The three routed super-admin services no longer run any statement per tenant: one tenants query + one table-presence catalogue query + chunked `UNION ALL` statements (`CrossTenantSql`, 100 tenants per statement) aggregated / top-K-limited in SQL; the geography screen resolves every owner phone in one public query (identity api batch method); the activity table enriches only the requested page.

**Files.**
- `admin/crosstenant/TenantTable.java` — narrow presence specs per branch + module tables of these screens.
- `admin/TenantLastActivitySql.java` — presence-aware builder for branches; the single-schema form delegates to it.
- `admin/analytics/.../AdminAnalyticsService.java` — overview / top stores / adoption / geography set-based; deterministic top-20 merge.
- `admin/operations/.../AdminOperationsService.java` — activity: rank in Java, enrich page tenants in one union (id tie-break); inactive: filtered union.
- `admin/catalog/.../AdminCatalogService.java` — count union + page union (per-branch top-N) + summary with per-column-group counts; 422 on a negative page.
- `identity/api/OwnerRecipientResolver.java`, `OwnerRecipientResolverAdapter`, `UserRepository(Adapter)` — batch owner lookup (`DISTINCT ON`, same rule).
- Tests: `AdminScreensCrossTenantIntegrationTest` (statement count 3 vs 12 tenants, catalogue real SQL + interleaved paging, missing module table, broken schema, geography), rewritten unit tests of the three services, `OwnerLookupPostgresIntegrationTest.batch_sameRuleAsSingleLookup`, arch rule extended to the batch method.

**Review.** 43 findings (high 2, medium 14, low 16, false 4, maybe-false 7): patched — narrow presence specs (high), abstract batch resolver, deterministic top-stores merge, activity id tie-break, schema validation at use, null-tenant row guard, 422 on negative page, deduplicated last-activity SQL, index-friendly branch order, two new real-SQL tests; deferred → 19-11c (indexes, streaming export, keyset, last-activity legs, other admin loops); rejected with reasons in the triage log (D1–D5).

**Follow-up review:** recommended — a `high` entry was patched (presence specs narrowed per branch): an older tenant schema missing columns has not been exercised on a real database beyond the dropped-table case.

**Verification.** `wt-pkgs.sh … t1911b`: admin/crosstenant 17/17, admin/catalog 18/18, admin/analytics 90/90, admin/operations 54/54, identity/auth 518/518, architecture 147/147 (endpoints table unchanged). Live on `keevo_dev_t1911b` (port 4591, WhatsApp noop, FCM off): 13 screen endpoints 200 with 15 then 24 accounts (owners registered through the API), negative catalogue page 422 with the French message, no error / cross-tenant warning in the log; backend stopped by port, DB dropped.

**Residual risks.** Statement count still grows by one per 100 tenants per figure (lock budget, D2); catalogue deep pages / export cost rows (19-11c).
