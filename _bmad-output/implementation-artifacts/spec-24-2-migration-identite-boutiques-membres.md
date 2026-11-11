---
title: '24.2 — Bref migration module: batches, records, identity, stores, members'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '717aacdc0ddb2ab787a2dacb4cb76325e877177b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/fusion-keevo-bref-analyse-2026-10-31.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** No tool brings a Bref owner into Keevo. Epic 24 needs a platform `migration` module that reads one owner's snapshot from Bref and creates, idempotently and traceably, the owner's account, a tenant shaped for the Bref document flow, the owner's shops and the owner's team (steps 1–3 of analysis §10.2). Stories 24.3–24.10 plug in on top of it.

**Approach:** A new Spring Modulith module `com.keevo.migration` (manifest `migration`, category platform, SUPER_ADMIN). A `BrefSourcePort` has two adapters that produce the same immutable `BrefSnapshot`:
- the versioned JSON export of api-bref (contract `keevo/contracts/bref-export.json`, sha256 sidecar);
- a read-only JDBC connection to a restored Bref database.

The import service validates the whole snapshot (no write), then runs one transaction per step through new import ports of `identity`, `store` and `shared.module`. It traces every written row in `public.migration_records` and every run in `public.migration_batches` (Flyway V81).

## Boundaries & Constraints

**Always:**
- **Ids and history:** Bref UUIDs are kept (user, shop, member). Original `created_at`/`updated_at` are kept. Every insert uses `ON CONFLICT DO NOTHING`, so a re-run changes nothing or completes what is missing.
- **Blocking without partial writes:** an owner with any blocking case (collision, invalid value) gets no write at all. The batch ends `BLOCKED` with listed cases (code, French label, source table, source id).
- **Tenant settings:**
  - The sale flow goes through `TenantSaleFlowCommandPort.set(schema, DOCUMENT)`, then `ModuleActivationRulesPort.applyInitialRules(schema)`.
  - Logos go through `StoreLogoUseCase` (store module), never through SQL on `logo_path`.
- **Set-based writes:** batch inserts and one query per step and table. No per-row query.
- **Source access:** the Bref source is read-only (Hikari `readOnly`, `SET TRANSACTION READ ONLY`). It is never a Spring `DataSource` bean.
- **Personal data:** no source personal data in logs. Logs carry counts and ids only.
- **Scope:** super-admin only (`/api/v1/admin/**` + `CurrentActor.requireSuperAdmin()`).

**Never:**
- Code in api-bref (24.1 is the Bref team's).
- Linking to an existing Keevo account (bridge = 24.7).
- Products, clients, documents, plan or credit import (24.3–24.5).
- Reconciliation (24.8), waves, activation and rollback (24.9).
- Events that notify the owner (no `TenantSuspendedEvent`, no `UserRegisteredEvent`, no WhatsApp).
- Plan-limit checks on imported stores and members (the plan is 24.5's).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | Owner with 1 shop, 3 members (admin, 2 sellers); no Keevo account matches | 201 batch `COMPLETED`; counts: tenant 1, users 4, memberships 4, stores 1, employees 3. Tenant `SUSPENDED` (reason « Migration Bref en cours »), flow DOCUMENT, no « Ma Boutique », bcrypt hashes identical | — |
| Re-run | Same owner already imported | New batch `COMPLETED`, all counts 0, `alreadyImported` = totals; DB unchanged | — |
| Partial re-run | Shop added in Bref since the first run | Only the new store and its records are written | — |
| Account collision | Owner phone (E.164) or email or a member username already held by another Keevo account | Batch `BLOCKED`, case `ACCOUNT_EXISTS` « Un compte Keevo existe déjà avec ce téléphone / cet e-mail / cet identifiant (rapprochement : story 24.7) »; nothing written | 201 with BLOCKED status |
| Invalid value | Member username outside `^[A-Za-z0-9._-]{1,50}$`, shop name < 2 chars, unknown role, owner without any identifier, deleted or inactive owner | Batch `BLOCKED` with one case per row | 201 BLOCKED |
| Deleted Bref shop | `deletedAt` set | Imported inactive, note « Boutique supprimée dans Bref » | — |
| Deleted Bref member | `deletedAt` set | Not imported, counted `membersSkippedDeleted` | — |
| Logo | `photoData` in export, or `photoUrl` on an allowed https host with fetch enabled | Logo stored through StoreLogoUseCase, record `store_logo`; otherwise note « Logo non rapatrié (…) » | Failure → note, never fails the batch |
| Source unavailable | Source not configured (no export dir / JDBC URL) | — | 503 `MIGRATION_SOURCE_UNAVAILABLE` |
| Unknown owner | Owner absent from the source, or not role `owner` | — | 404 `MIGRATION_OWNER_NOT_FOUND` |
| Bad export | Checksum mismatch, missing sidecar, unsupported `formatVersion`, bad JSON | — | 422 `MIGRATION_EXPORT_INVALID` |
| Concurrent run | A batch of the same owner `RUNNING` for under 1 h | — | 409 `MIGRATION_ALREADY_RUNNING` |
| Step failure | Unexpected exception in a step | Batch `FAILED` with French error label; committed steps stay (a re-run completes them) | 201 FAILED |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantFactory.java`: `create()`. Add `create(TenantCreation)` (name, initial status, cause, seedDefaultStore). `create()` delegates to it.
- `.../shared/infrastructure/persistence/TenantSchemaProvisioner.java`: `seedData`, `SEED_STORE`. Add an overload taking `seedDefaultStore`.
- `.../shared/capability/entitlement/PlanChangeCause.java`: add `MIGRATION_BREF`. The DB CHECK is widened in V81.
- `.../identity/api/`: new `LegacyIdentityImportPort` and its records (`LegacyAccount`, `ConflictKind`, `ImportedTenant`, `ImportedMember`, `MemberImportResult`). Implemented in the new package `identity/legacyimport` (service + JDBC store). Reuses `TenantFactory`, `TenantSchemaPort.assignOwnerRole`, `public.users`/`user_tenant_memberships`, `"<schema>".employees`/`tenant_preferences`.
- `.../identity/onboarding/adapter/in/api/TenantSaleFlowCommandAdapter.java`: reused as is. It needs a `tenant_preferences` row, which the import port inserts first.
- `.../store/api/StoreImportPort.java` (new, implemented by `store/store/adapter/in/api/StoreImportApiAdapter` + `adapter/out/persistence/JdbcStoreImportWriter`; `StoreApiAdapter` left untouched):
  - `importStores(schema, List<ImportedStore>)`: normalized by the store rules, then one batch insert `ON CONFLICT (id) DO NOTHING` with no lock, limit or event;
  - `importLogo(schema, storeId, bytes, actorId)`, which delegates to `StoreLogoUseCase.upload`.
  - Both need the tenant context bound (`TenantContext.requireCurrent`). `StoreCommandPort.validateName` / `normalizeProfile` are reused for the pre-write validation.
- `.../identity/api/TenantQueryPort.java` + `identity/auth/adapter/in/api/TenantQueryAdapter.java`: add `findById` (a re-run finds the tenant's schema from its record).
- `.../admin/revenue/domain/model/RevenueMetrics.java`: `MIGRATION_BREF` is not a plan change (churn and conversion ignore it, like `PLAN_MIGRATION`).
- `.../store/store/application/service/StoreLogoService.java`: max 200 KB, re-encodes the image.
- `.../shared/module/ModuleActivationRulesPort.java`, `application/ModuleActivationRulesService.java`, `application/port/ModuleActivationRuleStore.java`, `adapter/out/persistence/JdbcModuleActivationRuleStore.java`: add `ownerDisableAllBut(schema, keep)`. It filters the manifests (owner-toggleable, not platform, not in `keep`) and runs one `INSERT … unnest … ON CONFLICT DO NOTHING`.
- `.../shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/GlobalExceptionHandler.java`, `ErrorMessages.java`: 5 new `MIGRATION_*` codes.
- `.../shared/domain/PhoneNumbers.java`: `toE164(raw, CAMEROON)`.
- `keevo/backend/src/main/resources/keevo/modules/migration/module.json`: new platform manifest. Added to `ModuleIdsAtGoLivePinTest.KNOWN` (V81 owner-disables it, per the README rule).
- `keevo/backend/src/main/resources/db/migration/V81__bref_migration.sql`: `migration_batches`, `migration_records`, the plan-event cause and the manifest line.
- New `keevo/backend/src/main/java/com/keevo/migration/**`:
  - `domain/model`: `BrefSnapshot`, `BrefAccount`, `BrefShop`, `BlockedCase`, `ImportNote`, `MigrationBatch`, `MigrationRecord`, `Canonical`, `BatchStatus`, `SourceKind`;
  - `domain/service`: `BrefSnapshotValidator`, `BrefMappings`;
  - `domain/port/out`: `BrefSourcePort`, `MigrationRecordStore`, `BrefLogoFetcher`;
  - `application`: `BrefOwnerImportService`, `ImportRun`, `ImportStep`, `MigrationUnitOfWork`, `step/{Identity,Profile,Stores,Logos,Members}Step`;
  - `adapter/out/source`: `JsonExportBrefSource`, `JdbcBrefSource`;
  - `adapter/out/persistence`: `JdbcMigrationRecordStore`;
  - `adapter/out/http`: `HttpBrefLogoFetcher`;
  - `adapter/in/rest`: `AdminBrefMigrationController`.
- `keevo/contracts/bref-export.json`: format v1 (envelope, owner, shops, members; later sections declared for 24.3–24.6).
- `keevo/backend/src/test/resources/migration/bref-source-schema.sql`: Bref DDL for `user`/`shop` (state of api-bref `origin/develop` 6faa8b4) with synthetic rows.

## Tasks & Acceptance

**Execution:**
- `V81__bref_migration.sql`:
  - the two tables, with indexes: unique `(source_table, source_id, target_table)`, `(target_table, target_id)`, `(bref_owner_id)`, a partial unique index on `migration_batches(bref_owner_id) WHERE status = 'RUNNING'`, and `(started_at DESC, id DESC)`;
  - CHECK of `tenant_plan_events.cause` widened with `MIGRATION_BREF`;
  - `migration` owner-disabled for existing tenants.
- `TenantFactory` / `TenantSchemaProvisioner` / `PlanChangeCause`: creation options, no default store when asked.
- `identity/api/LegacyIdentityImportPort` + `identity/legacyimport/**`:
  - `findConflicts(accounts)`: one query over the unnested arrays;
  - `createOwnerTenant(owner, tenantName)`: owner user, tenant `SUSPENDED` with cause `MIGRATION_BREF`, membership OWNER, owner role;
  - `prepareProfile(schema, tenantId, sector)`: preferences row with reports and alerts off, plus the suspension trace;
  - `importMembers(schema, tenantId, members)`: three batch inserts.
- `store/api` + `StoreApiAdapter`: `importStores` and `importLogo`.
- `shared/module`: `ownerDisableAllBut`.
- `migration/**`:
  - the module (`package-info` `allowedDependencies = {"shared", "identity :: api", "store :: api"}`);
  - the sources, validator, steps, record store, logo fetcher and controller;
  - configuration `keevo.migration.bref.*`, with every source and fetch off by default.
- `ErrorCode` / handler / messages: the 5 codes.
- `keevo/contracts/bref-export.json`: contract v1 with a synthetic example.
- Tests:
  - unit tests for the validator, the mappings (roles, sector, phone), the JSON adapter (contract example, checksum, version, skipped sections) and the record checksum;
  - `BrefOwnerImportFullContextTest`: synthetic Bref DB on the test container, JDBC source, then import, re-run (no change), partial re-run, collision (blocked, nothing written), 403 for an owner token, and the same model from the JSON adapter;
  - unit tests for the `ownerDisableAllBut` service filter;
  - updates to the architecture pins (`ModuleIdsAtGoLivePinTest`) and to `docs/entitlements/endpoints.md`.

**Acceptance Criteria:**
- Given a Bref owner with shops and members and no matching Keevo account, when `POST /api/v1/admin/migration/bref/owners/{ownerId}/imports {"source":"DATABASE"}` runs, then:
  - the tenant exists `SUSPENDED` with no « Ma Boutique », `default_sale_flow = DOCUMENT`, the DOCUMENT rules plus the non-Bref modules owner-disabled;
  - stores keep the Bref ids, timestamps and fields;
  - members have mapped roles (admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER), identical bcrypt hashes and `employees` rows on their store;
  - every row has one `migration_records` line with a sha256 checksum.
- Given the same import re-run, when it completes, then no table changes, the batch reports zero writes, and `GET /api/v1/admin/migration/batches/{id}` returns it.
- Given the JSON export of the same owner, when it is parsed, then it yields a `BrefSnapshot` equal to the JDBC one (same per-row checksums).
- Given an owner token, when it calls either route, then the answer is 403.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 47 findings — high 0, medium 23, low 21, false 3, maybe-false 0 (the 5 intent-alignment divergences are descriptive and logged as rejects)
- findings:
  - **Design layer (FUS-NFR11)**
    - `[medium]` `[patch]` `findTargets` loaded the owner's whole record history, then copied it into the run. Fixed: it now loads only the target tables the steps declare (`ImportStep.targetTables()`), using index `(bref_owner_id, target_table)`. The run keeps the store's map instead of a copy.
    - `[medium]` `[patch]` Batch inserts relied on per-statement update counts, which break under `reWriteBatchedInserts`. Fixed: users, memberships, employees and stores now use `INSERT … SELECT FROM unnest(…) ON CONFLICT DO NOTHING RETURNING`, one round trip each, with instants passed as ISO text cast to `timestamptz`.
    - `[low]` `[patch]` The fixed-point loop in `modulesToDisable` cost O(m²·r). Replaced by a worklist, O(m+r), with a test of a two-level chain.
    - `[low]` `[reject]` Bref `user.shopId` has no index. This is the dry-run source only, it is documented in `JdbcBrefSource`, and wave runs use the export.
    - `[low]` `[reject]` Logos are downloaded one after another. An owner has a handful of shops. Parallel downloads belong to the waves (24.9).
    - `[low]` `[patch]` `idx_migration_batches_owner` and `_started` had no reader. Removed from V81; the batch lists and their indexes come with 24.9.
  - **Blind hunter**
    - `[medium]` `[defer→routed 24.5]` The registration trial plan could expire and record `EXPIRY` before 24.5 imports the real plan. 24.5 owns the plan import, and its routed item now says this.
    - `[medium]` `[patch]` The phone/username conflict check was one-way and not normalised. The query now matches an imported phone against existing usernames, and a numeric username in E.164 against existing phones. The validator applies the same rule (`usernameAsPhone`). Tested.
    - `[medium]` `[patch]` The export did not check shop ownership or duplicate rows. The validator now blocks with `SHOP_INVALID` « boutique d'un autre compte ou en double » or `DUPLICATE_IDENTIFIER` « membre en double ». Tested.
    - `[medium]` `[patch]` An embedded logo had no size limit. Base64 text longer than 2 MiB is now refused before decoding (`MIGRATION_EXPORT_INVALID`).
    - `[low]` `[patch]` Nothing flagged an owner who signs in with Google or Apple. Added the note `OWNER_SOCIAL_SIGN_IN`; the social authenticator stays routed to 24.7.
    - `[medium]` `[patch]` In `LogosStep`, a non-domain storage failure failed the whole import, and the download time was not fully bounded. It now catches `RuntimeException`, and the fetcher bounds headers and body in one 15 s deadline (`sendAsync` + `get(timeout)` + cancel).
    - `[low]` `[reject]` A refused request (source, owner, export) leaves no batch. These are 4xx/5xx answers with nothing written; the batch records the runs that started.
    - `[medium]` `[patch]` A throwing `finishBatch` could leave the batch RUNNING. It is now wrapped in `finish()` and logged; the stale take-over frees the owner (now tested).
    - `[low]` `[patch]` `ACCOUNT_EXISTS` did not say which identifier clashes. The label now names it (« téléphone, e-mail »), never the value. `sourceId` stays: it is a handle that is never displayed (Dashboard section).
    - `[low]` `[reject→routed 24.9]` There is no list of batches. The unused indexes are removed, and the list belongs to 24.9.
    - `[low]` `[patch]` The stale-run check mixed the application clock and the database clock. It now uses `now() - make_interval(secs => ?)` on the database.
    - `[medium]` `[patch]` Paths had no tests. Added: stale take-over and 409, `TENANT_GONE`, `tenantName` (fallbacks and code points), the two-level module chain, bad logo, altered export (422), and profile not reset by a re-run.
    - `[low]` `[patch]` The `MigrationBatch` javadoc named a missing `ImportCounts`. It now points to `ImportRun`.
    - `[false]` `[reject]` « `ownerDisableAllBut` after `applyInitialRules` can keep a module on ». `applyInitialRules` only inserts `activated=false` rows (`APPLY_SQL`), so no row with `true` exists for DO NOTHING to keep.
    - `[low]` `[patch]` `NOT VALID` followed by an immediate `VALIDATE` in the same transaction gained nothing. Now a plain `ADD CONSTRAINT`.
    - `[low]` `[patch]` `tenantName` could cut a surrogate pair. It now truncates by code points. Tested.
  - **Edge-case hunter**
    - `[medium]` `[patch]` An orphan schema was left when the identity transaction rolled back after `TenantFactory.create` (the schema is committed on its own connection). `LegacyIdentityImportService` now registers `afterCompletion(ROLLED_BACK)` → `dropSchemaIfExists`.
    - `[medium]` `[patch]` The `ImportRun` was mutated inside the transaction lambdas. The identity, stores and members steps now return their results and update the run only after commit.
    - `[medium]` `[patch]` An `UncheckedIOException` from logo storage failed the import. Same fix as the blind-hunter logo finding.
    - `[medium]` `[patch]` The body read was not covered by the timeout. Same fix as the blind-hunter logo finding.
    - `[low]` `[patch]` `shopId` case or padding. `MEMBERS_SQL` now compares `lower(btrim(...))`.
    - `[medium]` `[patch]` The export did not check shop ownership or duplicates. Same fix as the blind-hunter export finding.
    - `[medium]` `[patch]` A throwing `finishBatch` left the batch RUNNING. Same fix as the blind-hunter `finishBatch` finding.
    - `[low]` `[patch]` A rollback exception in the `finally` block masked `OWNER_NOT_FOUND`. The rollback exception is now swallowed (read-only connection).
    - `[low]` `[patch]` Surrogate cut. Same fix as the blind-hunter `tenantName` finding.
    - `[false]` `[reject]` « A global record key leaves rows untraced under another owner ». The same Bref member under owner B gets `SAME_ID` without a record in B's import, so `ACCOUNT_ID_TAKEN` blocks before any write. Nothing is written untraced.
    - `[low]` `[reject]` Preferences and module activations have no record. They are not source rows; the tenant's records and the 24.9 rollback (schema drop) cover them.
    - `[medium]` `[patch]` The `LogosStep` javadoc claim was not true. Same fix as the blind-hunter logo finding.
    - `[medium]` `[patch]` The fetcher javadoc claim about the 15 s bound was not true. Same fix as the blind-hunter logo finding.
  - **Verification gap**
    - `[medium]` `[patch]` `MIGRATION_BREF` exclusion from churn and conversion was untested. Added `RevenueMetricsTest.migrationBref_ignoredByChurnAndConversion`.
    - `[medium]` `[patch]` Nothing proved a re-run leaves the profile alone. The full-context test now sets POS and re-enables a module, re-runs, and checks both survive.
    - `[medium]` `[patch]` `TENANT_GONE` was untested. Added a unit test.
    - `[medium]` `[patch]` Batch locking was tested only against a mock. Added a Postgres test: second RUNNING → empty and HTTP 409, stale take-over with its label.
    - `[low]` `[patch]` The HTTP mappings were unchecked. 409 and 422 are now asserted over HTTP. 503 is covered by the unit refusal plus the `GlobalExceptionHandler` mapping (both sources are configured in the full context).
    - `[medium]` `[patch]` The bad-logo path was untested. Added a test: COMPLETED, `LOGO_FAILED`, team imported.
    - `[low]` `[patch]` Tests in the other touched packages had not been run. Now run: architecture, identity, store, shared, admin (see Verification).
    - `[medium]` `[patch]` A throwing `finishBatch` left the batch RUNNING. Same fix as the blind-hunter `finishBatch` finding.
  - **Intent alignment** (descriptive)
    - `[low]` `[reject]` Reading A2: link an existing account. 24.2's AC and the 24.7 story both put linking in the bridge (24.7), which the intent names. D1 is consistent with that.
    - `[low]` `[reject]` Real data shapes. The live run on `bref_src_t242` exercised them: no blocking case, aggregates in the Auto Run Result.
    - `[low]` `[reject]` Producer conformance is not proved. api-bref's serializer is 24.1, built by the Bref team. The contract and its example are the shared surface, tested by `JsonExportBrefSourceTest`.
    - `[false]` `[reject]` Reading B2: plan entitlement. The routed 19.13 item asks for the function list; entitlement is 24.5's acceptance criterion.
    - `[low]` `[reject]` Logos from `photoUrl`. A real download was verified in the live run with fetch enabled (see Auto Run Result). Unit tests cover the allow-list and the resize URL.


## Design Notes

**Patterns:**
- **Ports and adapters with a Strategy:** two `BrefSourcePort` adapters, chosen per request by `source`.
- **Pipeline:** an ordered list of `ImportStep` (identity, profile, stores, logos, members). 24.3–24.6 append steps (categories, products, clients, documents, monetisation, annexes) without touching the earlier ones.
- **Validation as a pure function:** `BrefSnapshotValidator` takes the snapshot and the conflicts and returns the cases. It runs before any write.
- **Unit of work per step:** `TransactionTemplate`, with tenant scope for the store and member steps, like `admin.crosstenant.TenantScope`.

**Decisions (taken by default, conservative, 11/11/2026):**
- **D1 Identity.** The owner is created with the Bref UUID only when no Keevo account holds its id, E.164 phone, email or username. Any match blocks the owner and lists the case; linking an existing account is 24.7. A re-run is recognised only through `migration_records`.
- **D2 Suspended tenant.** The imported tenant is created `SUSPENDED` with reason « Migration Bref en cours — activation en attente ». No event is sent.
  - Effect: read-only lockout, excluded from the report, alert and portal jobs.
  - Activation to `ACTIVE` and rollback are 24.9's.
- **D3 Plan.** The registration plan of the catalogue (like any new tenant) is recorded with the new cause `MIGRATION_BREF`. The real plan is 24.5's.
- **D4 Profile.**
  - Sector: the category of the oldest shop. fashion→CLOTHING; electronics, telecom→ELECTRONICS; stationery, education→BOOKS_STATIONERY; home, furniture→HOME_APPLIANCES; food, restaurant, agriculture→FOOD_GROCERY; hardware, automotive→HARDWARE; other categories→OTHER.
  - Reports and alerts (end of day, weekly, inventory, trends, stock) are off, because Bref had none.
  - Flow: POS→DOCUMENT through the port, then `applyInitialRules`.
  - The Bref-equivalent functions are kept: catalog, crm, invoicing, expenses, finance, reporting, whatsapp-cloud, ai-assistant, client-portal, and the non-toggleable modules. Every other toggleable module is owner-disabled (advanced-reports, alerts, exports, profitability, report-config, mcp-access, plus the DOCUMENT rules). The owner can still enable them in « Mes modules ».
  - Entitlement is untouched: it stays with the plan (24.5).
  - Suppliers (crm) are masked by the DOCUMENT navigation preset on the app side, so there is no sub-module (Mobile section).
- **D5 Stores.**
  - Every shop of the owner is imported, deleted ones as inactive, so that the documents of 24.4 keep their store. `share_clients=false` (ADR-08).
  - Logo: the base64 `photoData` of the export first. Otherwise an https fetch on the allowed hosts (`res.cloudinary.com`, with a `c_limit,w_512` transformation so it fits the 200 KB limit), off by default. Any failure is noted.
  - `invoicing_settings` filled from the first shop is routed to 24.4.
- **D6 Members.**
  - Bref users whose `shopId` is one of the owner's shops, with role admin, seller or viewer.
  - Deleted members are skipped and counted. Inactive members get an inactive membership and an `INACTIVE` employee.
  - `employees.id` = Bref user id. Names fall back to the username, then « Membre ».
  - `password_change_required` = `mustChangePassword`. `users.role` = the mapped role.
  - Bref `isVerified` + phone → `phone_verified_at`, and `emailVerified` → `email_verified_at` (date = Bref `updatedAt`, the only date available).
- **D7 Export format.**
  - JSON v1 (`formatVersion: 1`), file `owner-<uuid>.json` plus `owner-<uuid>.json.sha256` (hex of the exact bytes) in `keevo.migration.bref.export-dir`.
  - Unknown sections are skipped while streaming, so memory is bounded to owner, shops and members.
  - Timestamps are UTC ISO-8601, kept to the millisecond (the export goes through JavaScript dates), so both adapters give the same values and checksums. The Bref `timestamp without time zone` columns hold UTC wall-clock times. This was checked on the 03/11 dump: the latest refresh token is at 20:21 for a dump taken at 21:33 WAT (20:33 UTC).
- **D8 Concurrency.** One `RUNNING` batch per owner (partial unique index). A `RUNNING` batch older than 1 h is closed `FAILED` (« interrompu ») and taken over.

**Cost:**
- Validation: one conflict query.
- Identity: about 10 statements.
- Stores: one batch insert.
- Members: three batch inserts.
- Records: one batch insert per step and one read per run.
- Logos: one HTTP call and one upload per shop without a logo record.
- JDBC source: three queries.
- JSON source: one streaming pass.

**Golden flow:**
```
validate(snapshot, identity.findConflicts(all accounts)) → cases? BLOCKED
tx: owner+tenant(+record) → tx: prefs, saleFlow.set(DOCUMENT), applyInitialRules, ownerDisableAllBut
tenantTx: importStores(+records) → per logo: importLogo(+record) → tenantTx: importMembers(+records)
```

## Verification

**Commands:**
- `wt-pkgs.sh <backend> t242 migration identity shared/module store architecture admin`: expected all green.
- Live: restore `bref_src_t242`, backend on port 4642 with database `keevo_dev_t242` and the JDBC source pointing at `bref_src_t242`, run the import twice for the dump's owner, and report aggregates only.

## Auto Run Result

Status: done (code commit `992e8d1e`).

**Summary.** New platform module `migration`, Flyway V81 (`migration_batches`, `migration_records`, cause `MIGRATION_BREF`).
- **Source:** `BrefSourcePort` has two adapters, the api-bref JSON export (contract `keevo/contracts/bref-export.json` v1, SHA-256 sidecar, streaming) and a read-only JDBC Bref database. Both map rows through the same `BrefRows`, so they give the same snapshot and the same checksums.
- **Validation:** a pure validator runs before any write and produces the blocking cases.
- **Pipeline:** idempotent `ImportStep`s, one unit of work each:
  - identity: owner account and SUSPENDED tenant without placeholder store;
  - profile: preferences, DOCUMENT through the port, 19.13 rules, Bref-equivalent modules;
  - stores;
  - logos through `StoreLogoUseCase`;
  - members: bcrypt copied, roles mapped, employees.
- **Endpoints:** two super-admin routes.
- **Import ports:** `identity.api.LegacyIdentityImportPort`, `store.api.StoreImportPort`, `ModuleActivationRulesPort.ownerDisableAllBut`, `TenantQueryPort.findById`, `TenantFactory.create(TenantCreation)`.

**Files:** see Code Map, plus:
- `docs/entitlements/endpoints.md` regenerated;
- architecture and module pins updated (migration module, 25 manifests);
- tests: `migration/**` (21), `RevenueMetricsTest` (+1), `ModuleActivationRulesServiceTest` (+1).

**Review.** 47 findings: 33 patched (4 design, 11 blind, 10 edge-case, 8 verification), 13 rejected with their reasons (Review Triage Log), 1 routed to 24.5, 0 deferred to deferred-work. Patched by verdict: 0 high, 23 medium, 10 low. Routed items:
- sector categories → 24.3;
- `invoicing_settings` → 24.4;
- start plan and trial expiry → 24.5;
- account linking and Google/Apple owner → 24.7;
- activation/rollback, batch list and export download → 24.9.

**Verification (all green):**

| Package | Tests |
|---|---|
| migration | 21 |
| architecture | 171 (1 skipped, pre-existing) |
| identity | 966 |
| store | 128 |
| shared | 1 398 (one interpolation-guard false positive fixed, then shared/infrastructure/persistence 125 and shared/module 559 green) |
| admin | 722 |
| admin/revenue | 64 |

**Live check** (backend on port 4642, `keevo_dev_t242`, source `bref_src_t242` restored from the 03/11 dump; aggregates only):
- Source: 1 owner, 1 shop (with photo), 3 members (admin, 2 sellers).
- Import 1: 201 COMPLETED in 9.4 s. tenantsCreated 1, usersCreated 4, membershipsCreated 4, storesCreated 1, employeesCreated 3, logosImported 1 (real Cloudinary download, resized, through `StoreLogoUseCase`), no case, no note.
- Import 2: COMPLETED in 0.05 s, all creations 0, alreadyImported 14.
- Tenant: SUSPENDED with reason, no « Ma Boutique ».
- Profile: DOCUMENT, sector ELECTRONICS, reports off; 10 modules owner-disabled; plan event MIGRATION_BREF.
- Records: tenants 1, users 4, memberships 4, stores 1, store_logo 1, employees 3.
- 3/3 bcrypt hashes identical to the source.
- No phone in the logs. The databases `keevo_dev_t242` and `bref_src_t242` were dropped afterwards.

**Follow-up review recommended:** `true`. Many medium patches touched the write path:
- the set-based `unnest … RETURNING` inserts;
- the run state applied after commit;
- the orphan-schema compensation through `afterCompletion(ROLLED_BACK)`.

The compensation is not exercised by any failure test.

**Residual risks:**
- The registration trial plan stays until 24.5 (routed).
- Members' passwords could not be tried live, since they are unknown. Only the hash equality was checked.
- The JDBC source scans Bref's `user` table per owner (dry-run only).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commit `992e8d1e`, branche de la story 24.2, en attente de fusion). **Aucun nouvel endpoint pour l'app** : la story importe des comptes Bref dans Keevo. L'app doit seulement savoir accueillir ces comptes quand leur vague sera activée (24.9).

### Ce qui est fait côté API

- **Owner Bref importé** :
  - un compte Keevo est créé avec l'UUID Bref, son téléphone E.164 et son e-mail ;
  - le mot de passe n'est pas repris : un owner Bref se connecte par code ; s'il avait un mot de passe, le hash bcrypt est copié tel quel ;
  - son entreprise porte le nom de sa plus ancienne boutique.
- **Boutiques** : chacune garde son id Bref. Il n'y a pas de « Ma Boutique ». Une boutique supprimée dans Bref arrive inactive.
- **Membres** :
  - les comptes admin, vendeur et lecteur Bref deviennent des membres `MANAGER`, `EMPLOYEE` et `VIEWER` ;
  - ils gardent leur **identifiant** (username) et leur **mot de passe Bref** (hash bcrypt copié), et sont rattachés à leur boutique ;
  - `mustChangePassword` Bref devient `passwordChangeRequired`.
- **Profil** :
  - parcours de vente `DOCUMENT`, donc préréglage de navigation `DOCUMENT` (19.13) ;
  - modules sans équivalent Bref désactivés par le propriétaire : caisse, stock, inventaire, transferts, rapports avancés, exports, rentabilité, configuration des rapports, alertes, accès IA externe. Le propriétaire peut les activer dans « Mes modules » ;
  - rapports et alertes programmés désactivés.
- **Entreprise suspendue** : l'entreprise importée reste `SUSPENDED` (motif « Migration Bref en cours — activation en attente ») jusqu'à son activation par la vague (24.9). C'est le verrouillage en lecture seule existant : bannière de suspension, écritures refusées en 403 `ACCOUNT_SUSPENDED`.

### À utiliser

Rien de nouveau. Les parcours existants s'appliquent tels quels :
- connexion par identifiant + mot de passe (19.4) ;
- connexion par code (19.1) ;
- sélecteur d'entreprise (19.6) ;
- `passwordChangeRequired` (changement de mot de passe forcé) ;
- `GET /api/v1/tenants/me/modules` → `meta.navigationPreset` (`CAISSE` | `DOCUMENT`, 19.13 ; `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java`) ;
- `tenantStatus=SUSPENDED` dans le jeton (bannière existante).

### À implémenter côté mobile

1. **Fournisseurs masqués en préréglage DOCUMENT** (décision D4 de 24.2) :
   - quand le préréglage de navigation vaut `DOCUMENT`, l'entrée « Fournisseurs » n'apparaît ni dans les menus ni dans les raccourcis ;
   - le module `crm` reste actif pour les clients ;
   - aucun écran « bientôt disponible » (rappel FUS-UX7).
2. **Connexion d'un membre importé** : l'identifiant Bref (lettres, chiffres, point, tiret, souligné) et son mot de passe Bref fonctionnent tels quels. Après connexion, `passwordChangeRequired=true` mène à l'écran existant de changement de mot de passe.
3. **Entreprise suspendue « en migration »** : rien de spécifique. La bannière de suspension existante s'affiche avec le texte fourni par l'API, sans mention technique.

### Implémentation de référence

Aucune dans `keevo/app` (client de test).

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner importé puis activé (24.9), **When** il se connecte par code, **Then** il arrive sur l'écran Documents (préréglage DOCUMENT) et ne voit ni Caisse, ni Stock, ni Fournisseurs.
- **Given** un vendeur Bref importé avec `mustChangePassword`, **When** il se connecte avec son identifiant et son mot de passe Bref, **Then** l'app lui demande de changer son mot de passe.
- **Given** une entreprise importée pas encore activée, **When** un membre se connecte, **Then** la bannière de suspension s'affiche et toute écriture est refusée (403 `ACCOUNT_SUSPENDED`, message tel quel).
- Mise en place (curl, super-admin, base Bref restaurée) : `POST /api/v1/admin/migration/bref/owners/<uuid owner Bref>/imports` avec `{"source":"DATABASE"}`.

### Clé de suivi mobile

`m24-2-migration-identite-boutiques-membres`

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md` (Bearer super-admin, enveloppe `{"data"}`, erreurs `{"error", "code", "domainCode"}` : afficher `error` tel quel, décider sur `domainCode`, dates UTC à afficher en heure de Douala).

### Statut

API livrée (commit `992e8d1e`) : pilotage **minimal** de l'outil de migration. Le pilotage complet des vagues (planifier, geler, activer, annuler, rapport de réconciliation) relève de 24.8 et 24.9.

### Ce qui est fait côté API

- Import d'un owner Bref (identité, entreprise, boutiques, équipe) depuis l'export api-bref ou depuis une base Bref restaurée en lecture seule.
- Chaque import est un **lot** qui garde :
  - son statut ;
  - ses compteurs ;
  - ses **cas bloquants** : un owner bloqué n'a rien d'écrit, il faut le résoudre à la main ;
  - ses **remarques** : ce qui n'a pas été repris tel quel, sans bloquer ;
  - le libellé de l'erreur en cas d'échec.
- Relancer un import est sans effet, ou complète ce qui manque.

### À utiliser

Code : `keevo/backend/src/main/java/com/keevo/migration/adapter/in/rest/AdminBrefMigrationController.java` et `MigrationBatchResponse.java`.

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| POST | `/api/v1/admin/migration/bref/owners/{ownerId}/imports` | `{"source": "EXPORT" \| "DATABASE"}` | **201** + lot (ci-dessous), quel que soit le résultat (`COMPLETED`, `BLOCKED`, `FAILED`). Import synchrone (moins d'une minute par owner). |
| GET | `/api/v1/admin/migration/batches/{batchId}` | — | 200 + lot |

**Lot :**

```
{id, brefOwnerId, source, sourceLabel, status, statusLabel, tenantId, startedAt, finishedAt, counts, cases, notes, errorLabel}
```

- `sourceLabel` : « Export Bref » ou « Base Bref (lecture seule) ».
- `statusLabel` : « Terminé », « Bloqué », « Échec » ou « En cours ».
- `counts` : `tenantsCreated`, `usersCreated`, `membershipsCreated`, `storesCreated`, `logosImported`, `employeesCreated`, `alreadyImported` (lignes déjà importées par un lot précédent), `membersSkippedDeleted`.
- `cases` : `[{code, label, sourceTable, sourceId}]`. Afficher `label`. `sourceTable`/`sourceId` (« user », « shop » + UUID Bref) servent à retrouver la ligne dans Bref, jamais à l'écran.
- `notes` : `[{code, label, count}]`. Afficher « label (count) ».
- `errorLabel` : texte français à afficher, `FAILED` uniquement.

**Erreurs (rien n'est créé) :**

| HTTP | `domainCode` | Message |
|---|---|---|
| 422 | `VALIDATION_FAILED` | « Source inconnue : choisissez l'export Bref ou la base Bref » |
| 503 | `MIGRATION_SOURCE_UNAVAILABLE` | « La source Bref demandée n'est pas disponible » |
| 404 | `MIGRATION_OWNER_NOT_FOUND` | « Ce compte Bref est introuvable dans la source » |
| 422 | `MIGRATION_EXPORT_INVALID` | « L'export Bref est illisible ou altéré » |
| 409 | `MIGRATION_ALREADY_RUNNING` | « Un import de ce compte Bref est déjà en cours » |
| 404 | `MIGRATION_BATCH_NOT_FOUND` | « Lot d'import introuvable » |

Un owner (non super-admin) reçoit 403.

### À implémenter côté dashboard

1. Un écran « Migration Bref » (provisoire jusqu'à 24.9). Il contient :
   - un champ « Identifiant du compte Bref » et le choix de la source ;
   - un bouton « Importer » ;
   - l'affichage du lot renvoyé.
2. **Lot affiché** :
   - statut (libellé) ;
   - compteurs avec des libellés français : « Entreprise créée », « Comptes créés », « Accès créés », « Boutiques créées », « Logos repris », « Fiches membres créées », « Déjà importés », « Membres supprimés dans Bref (non repris) » ;
   - liste des cas bloquants (`label`) ;
   - remarques (`label (count)`) ;
   - `errorLabel` ;
   - un bouton « Relancer », qui refait le même POST.
3. Un lien vers la fiche de l'entreprise créée (`tenantId`) quand il est présent. Le libellé de cette entreprise est son nom, jamais l'id.

### Critères d'acceptation dashboard

- **Given** un owner Bref sans compte Keevo, **When** le super-admin lance l'import, **Then** le lot « Terminé » montre 1 entreprise, les boutiques et les membres.
- **Given** le même import relancé, **Then** tous les compteurs de création valent 0 et « Déjà importés » est positif.
- **Given** un owner dont le téléphone est déjà dans Keevo, **Then** le lot « Bloqué » affiche « Un compte Keevo existe déjà avec ce téléphone, cet e-mail ou cet identifiant (rapprochement des comptes : story 24.7) ».

### Clé de suivi dashboard

`d24-2-migration-identite-boutiques-membres`
