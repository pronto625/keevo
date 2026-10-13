---
title: '11-5 — Performance fixes: admin tenant-list N+1, pending-sales N+1, keyset pull indexes'
type: 'refactor'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/11-5-correctifs-performance-n1-bug-index.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 11-5 (ready-for-dev) lists audit-confirmed performance defects still present: the admin tenant list fires one `SELECT schema_name … WHERE id = ?` per row (and the detail path re-queries it), the pending-sales list lazily loads `sale_items` once per sale, and the keyset pull (`DeltaPageQuery`, ORDER BY cursor, id) has no supporting indexes on large tenant tables. The day-closure "index bug" (AC5) is already fixed on this branch (`DayClosureDeltaProvider` maps `totalRevenue` = `row[5]`, no `totalTransactions` key).

**Approach:** Implement story file tasks 1, 2 and 4 as written, record AC5 as already satisfied (with a regression test that `totalSales` = row[4] and `totalRevenue` = row[5]), and close the routed keyset-index item with tenant-schema indexes matching the cursor/id order of each delta provider.

## Boundaries & Constraints

**Always:** Tenant list/detail responses byte-identical to today for the same data (only query count changes). Pending sales: `SELECT DISTINCT s … LEFT JOIN FETCH s.items WHERE s.storeId = :storeId AND s.status = :status` (same pattern as `findByIdWithItems`), one row per sale. Keyset indexes: for each table read by a delta provider through `DeltaPageQuery`, an index on `(<cursor column>, id)` created idempotently (`CREATE INDEX IF NOT EXISTS`) in `TenantSchemaProvisioner` for new schemas and through its existing per-schema migration/sync path for existing schemas (the mechanism v1s-13-12 uses for added DDL); when the cursor is an expression (e.g. `COALESCE(updated_at, created_at)`), index that same expression; when the id is compared as text (`id::text`), index the same expression. Schema names stay gated by `TenantSchema.isValid`.

**Never:** No change to the 9-1 tenant DTOs or labels (that is spec 9-1-completion); no change to cross-schema metric queries other than reusing the preloaded schema name; no change to sync payloads.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List page of 25 | 25 tenants | exactly one schema-name query for the page (or none if the list query carries it) | — |
| Tenant with null/invalid schema | schema missing | item kept with zero metrics, as today | — |
| Pending sale with 3 items | 2 sales | 2 sales, each 3 items, one SQL statement | — |
| Existing tenant schema | already provisioned | indexes created on next schema sync, idempotent | failure logged, request not failed |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java:112,144,257-290` -- list/detail enrichment, `safeLoadSchemaName`.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java:26-35` -- `findByIdWithItems` pattern, derived `findByStoreIdAndStatus`.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DeltaPageQuery.java:16-44` and every `*DeltaProvider` in that package -- cursor expression and id expression per table.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java:36-45` -- AC5 already fixed.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` + `TenantSchemaSyncService` -- DDL for new and existing schemas.

## Tasks & Acceptance

**Execution:**
- `AdminTenantService.java` + `AdminTenantServiceTest.java` -- story Task 1 (batched schema names, detail reuse, remove `safeLoadSchemaName` if unused).
- `SaleSpringRepository.java` + a JPA test (`@DataJpaTest` on the Testcontainers base) -- story Task 2.
- `DayClosureDeltaProviderTest.java` -- regression test for AC5.
- `TenantSchemaProvisioner.java` (+ sync path) and its test -- keyset indexes per delta table.
- `11-5-correctifs-performance-n1-bug-index.md` -- tick tasks and routed items, Status done, completion notes (AC5 already fixed by an earlier rename).

**Acceptance Criteria:**
- Given the admin tenant list of 25 rows, when it loads, then the number of `schema_name` lookups does not grow with the row count (asserted in test).
- Given an existing tenant schema, when the app starts or the schema sync runs, then the keyset indexes exist (asserted by an integration test querying `pg_indexes`).

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.commerce.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.sync.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
