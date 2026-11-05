---
title: '19.12 — Extended stores (Bref fields, logo) and default sale flow preference'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: '31d9cd58fd76103a838ea597fa977277310e26be'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred:
  - summary: >-
      Imported Bref tenants get DOCUMENT only once the 24.2 importer calls TenantSaleFlowCommandPort.
    evidence: |-
      The port is delivered and tested here; no importer exists yet. Routed to story 24.2 in epics-fusion-keevo-bref.md.
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/api/TenantSaleFlowCommandPort.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** A Bref shop carries a category (19 keys), description, photo/logo, NIU, RCCM and country; a Keevo store only has name/address/phone/type. Keevo also has no tenant-level notion of the default sale flow, so an ex-Bref owner cannot land on documents and an ex-Keevo owner on the till (FUS-FR12, FR17).

**Approach:** Additive tenant migrations: store module `V2` adds `category, description, logo_path, niu, rccm, country` to `stores`; core module `V3` adds `tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}` default `POS`. Expose them through the existing store REST endpoints, the store sync push/pull and `GET /tenant/preferences`, add an owner-only store logo upload/read/delete backed by a storage port (local disk today), and an owner-only `PUT /tenant/sale-flow` plus an `identity.api` command port the Bref importer (24.x) uses to set `DOCUMENT`.

## Boundaries & Constraints

**Always:**
- V1 2.0.1 compatibility: additive fields only on every existing response/delta; a V1 `PATCH /stores/{id}` or `UPDATE_STORE`/`CREATE_STORE` sync op carrying only `name/address/phone(/type)` must leave the new fields untouched (absent ⇒ unchanged). Existing semantics of `address`/`phone` (null clears) are unchanged.
- New-field write rule (REST and sync): absent or `null` ⇒ unchanged; empty/blank string ⇒ cleared (`category`, `description`, `niu`, `rccm`); `country` can never be cleared (blank ⇒ `VALIDATION_ERROR`).
- `category` ∈ exactly the 19 Bref keys: `fashion, electronics, telecom, food, restaurant, beauty, sports, home, furniture, hardware, automotive, agriculture, stationery, jewelry, events, education, real_estate, services, other` (source `api-bref/src/shop/domain/shop.ts`), held in one Java enum in `store.api`; no DB CHECK (list may grow). `country` = ISO 3166-1 alpha-2 uppercase `[A-Z]{2}`, input upper-cased, default `CM`. Lengths: description ≤ 1000, niu ≤ 30, rccm ≤ 50 (trimmed) — over ⇒ `VALIDATION_ERROR` with a French message.
- Writes of store fields, logo and sale flow are OWNER only (existing `CurrentActor.requireOwner` + `@PreAuthorize`); the store logo GET is readable by every tenant role (OWNER, MANAGER, EMPLOYEE, VIEWER) — it is a read, so VIEWER guard is unaffected.
- Logo goes through a storage port (`StoreLogoStorage`, local disk adapter, root `keevo.store-logo.storage-dir`, path `<root>/<validated tenant schema>/<storeId>.<png|jpg>`, `.part` + atomic move, implements `TenantFilesPurger`), bytes checked and re-encoded (PNG/JPEG recognised by bytes, longest side capped, metadata dropped), size capped by `keevo.store-logo.max-bytes` (default 204800). The image re-encoding is NOT duplicated: extract the reusable core of `ImageIoCompanyLogoNormalizer` into `shared` and make both company and store use it (company behaviour byte-for-byte unchanged, its tests stay green).
- `logo_path` stores the relative file name (`<storeId>.png|jpg`) or NULL; API/delta expose `logoUrl` = `/api/v1/stores/{id}/logo` when set, else null (never the disk path).
- Every change is audited through the existing event/audit path (store update event; logo added/replaced/removed; sale flow before/after with French labels « Caisse » / « Documents »).
- Migrations idempotent forever (`ADD COLUMN IF NOT EXISTS`, constant defaults ⇒ no table rewrite), `SET LOCAL lock_timeout = '10s'`, no data UPDATE.

**Never:**
- No change to `type` immutability, store plan limits, deactivate/reactivate.
- No interface-profile/module activation logic (19.13), no signup choice of the flow (19.13), no Bref import code (24.x) beyond the command port.
- No public (Flyway `db/migration`) migration; no edit of already-released module scripts.
- No dashboard or Flutter feature work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| V1 patch | `PATCH /stores/{id}` `{name,address,phone}` on a store with category `food`, niu set | name/address/phone updated, category/niu/country kept | — |
| Set fields | OWNER `PATCH` `{name, category:"real_estate", country:"ci", niu:" M0123 "}` | 200, `category:"real_estate"`, `country:"CI"`, `niu:"M0123"`; delta row carries them | — |
| Clear field | `PATCH` `{name, description:""}` | description null | — |
| Bad category | `{category:"Alimentation"}` | 400 | `VALIDATION_ERROR` « Catégorie de boutique inconnue » |
| Bad country | `{country:"CMR"}` or `{country:""}` | 400 | `VALIDATION_ERROR` |
| Too long | niu 31 chars | 400 | `VALIDATION_ERROR` |
| Create | OWNER `POST /stores` with new optional fields | 201 with them; omitted ⇒ country `CM`, others null | — |
| Sync create/update | `CREATE_STORE`/`UPDATE_STORE` with `category` invalid | op REJECTED with `VALIDATION_ERROR` (same as a bad name today) | — |
| Logo upload | OWNER `PUT /stores/{id}/logo` multipart `file` PNG 50 KB | 200 store DTO with `logoUrl`; file on disk; `logo_path` set; `updated_at` bumped (delta) | — |
| Logo invalid | GIF, text, corrupt bytes | 422 | `STORE_LOGO_INVALID` |
| Logo too big | > max-bytes | 422 | `STORE_LOGO_TOO_LARGE` |
| Logo unknown store | other tenant's / missing id | 404 | `STORE_NOT_FOUND` |
| Logo read | EMPLOYEE `GET /stores/{id}/logo` | 200 image, `nosniff`, private no-cache | 404 `STORE_LOGO_NOT_FOUND` when none |
| Logo delete | OWNER `DELETE /stores/{id}/logo` | 200, `logoUrl:null`, file gone; no-op when none | — |
| Non-owner write | MANAGER/EMPLOYEE/VIEWER `PUT …/logo`, `PUT /tenant/sale-flow` | 403 | existing forbidden handling |
| Prefs read | any role `GET /tenant/preferences` on an existing tenant | `defaultSaleFlow:"POS"` (additive field) | — |
| Sale flow set | OWNER `PUT /tenant/sale-flow` `{defaultSaleFlow:"DOCUMENT"}` | 200 prefs with `DOCUMENT`; NOT gated by module `report-config` | `VALIDATION_ERROR` for other values / null |
| Importer | `TenantSaleFlowCommandPort.set(schema, DOCUMENT)` | stored, subsequent GET returns `DOCUMENT` | invalid schema ⇒ IllegalArgumentException |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/modules/store/V1__store_baseline.sql` -- style reference (idempotent header, lock_timeout); NEW sibling `V2__stores_bref_fields.sql`.
- `keevo/backend/src/main/resources/db/modules/core/V1__core_baseline.sql:86-121` -- `tenant_preferences` DDL; NEW `core/V3__tenant_preferences_sale_flow.sql` (column + named CHECK added only if absent).
- `keevo/backend/src/main/java/com/keevo/store/store/domain/model/Store.java` -- record; add the 6 fields (update the 7 `new Store(` call sites: `grep -rn "new Store(" src`).
- `.../store/store/adapter/out/persistence/StoreJpaEntity.java`, `StoreRepositoryAdapter.java` -- map new columns.
- `.../store/store/application/service/{Create,Update}StoreService.java` + `domain/port/in/{Create,Update}StoreCommand.java` -- validation + absent⇒unchanged rule (put the rules in one domain helper, e.g. `StoreDetails`/`StoreProfileRules`, reused by REST and sync).
- `.../store/store/adapter/in/rest/StoreController.java` (class-level `hasRole('OWNER')`) + `dto/{Create,Update}StoreRequestDto.java`, `dto/StoreResponseDto.java` -- additive fields; `logoUrl`.
- `.../store/api/{StoreView,StoreCommandPort,UpdateStoreInput,CreateStoreWithIdInput}.java`, `store/store/adapter/in/api/{StoreApiAdapter,StoreViews}.java` -- cross-module views/commands used by sync handlers; add fields; NEW `store/api/StoreCategory.java` enum (keys, `fromKey`).
- `.../sync/sync/application/handler/{CreateStoreSyncHandler,StoreSyncHandler}.java` -- read optional new payload keys, validate via the store command port (REJECTED on invalid like the name today).
- `.../sync/sync/application/provider/StoreDeltaProvider.java:37-55` -- add columns to the select list and map `category, description, logoUrl, niu, rccm, country` (no new index: same keyset cursor).
- `.../identity/company/adapter/out/image/ImageIoCompanyLogoNormalizer.java`, `adapter/out/storage/LocalDiskCompanyLogoStorage.java`, `adapter/in/rest/CompanyController.java:85-104` -- the pattern to follow for logo upload/read/delete; extract the ImageIO re-encode core into `com.keevo.shared.infrastructure.image` (new) and reuse.
- `.../shared/domain/port/out/TenantFilesPurger.java` -- implement in the store logo disk adapter (account deletion purges).
- `keevo/backend/src/main/resources/application.yml:199-208` -- add `keevo.store-logo.{storage-dir: ${KEEVO_STORE_LOGO_DIR:${user.home}/keevo-data/store-logos}, max-bytes: 204800, max-dimension: 600}`.
- `.../shared/domain/exception/ErrorCode.java:191-194` + the global exception→HTTP mapping used for `COMPANY_LOGO_*` -- add `STORE_LOGO_INVALID` (422), `STORE_LOGO_TOO_LARGE` (422), `STORE_LOGO_NOT_FOUND` (404) with French messages.
- `.../identity/onboarding/adapter/out/persistence/TenantPreferencesJpaEntity.java`, `domain/model/TenantPreferences.java`, `adapter/in/web/TenantPreferencesResponseDto.java` (append `defaultSaleFlow` as LAST component), `TenantPreferencesController.java:68-136` -- add `PUT /sale-flow`; follow `UpdateLeaderboardVisibilityService` (owner-only, audited, not gated by `report-config`).
- `.../identity/api/` -- NEW `SaleFlow` enum (`POS`, `DOCUMENT`, French label) and `TenantSaleFlowCommandPort` (+ adapter in `identity/onboarding/adapter/in/api/`) for the 24.x importer.
- `.../security/ViewerWriteGuardFilter` (19.5) -- verify the new GET is a read and the new PUT/DELETE are blocked for VIEWER by default (no allow-list change).
- `keevo/contracts/` + `ExportContractFileTest` -- only if the stores export dataset changes (it should NOT: export unchanged).
- `keevo/backend/docs/entitlements/endpoints.md` -- list the new routes with their entitlement (as earlier stories did).

## Tasks & Acceptance

**Execution:**
- `db/modules/store/V2__stores_bref_fields.sql` -- add 6 columns (`category VARCHAR(30)`, `description VARCHAR(1000)`, `logo_path VARCHAR(255)`, `niu VARCHAR(30)`, `rccm VARCHAR(50)`, `country VARCHAR(2) NOT NULL DEFAULT 'CM'`) -- idempotent, metadata-only.
- `db/modules/core/V3__tenant_preferences_sale_flow.sql` -- `default_sale_flow VARCHAR(10) NOT NULL DEFAULT 'POS'` + CHECK (POS, DOCUMENT) guarded by name.
- store domain/application/adapters (Code Map) -- fields, validation helper, partial-update rule, logo use case (`StoreLogoService`: check store exists in tenant, size, normalize, store, set `logo_path`, bump `updated_at`, audit; delete; read).
- `store/store/adapter/in/rest/StoreLogoController.java` (NEW, `/api/v1/stores/{storeId}/logo`, GET all roles with `@RequiresEntitlement(store, READ)`, PUT multipart/DELETE OWNER with `@RequiresEntitlement("store")`).
- shared image helper + company refactor -- no behaviour change.
- sync handlers + delta provider -- optional keys, validation, new delta fields.
- identity preferences -- column, DTO field, `PUT /tenant/sale-flow`, `TenantSaleFlowCommandPort`.
- Tests: unit tests for the validation helper (every matrix row incl. absent/null/blank, country upper-casing, 19 keys); controller/integration tests (Postgres testcontainer pattern of earlier stories) for PATCH V1 body preserving fields, logo upload/read/delete/invalid/too-large/other-role, sale-flow GET default/PUT/403, delta pull fields, sync REJECTED on bad category; migration test applying V2/V3 twice on a tenant schema; company logo tests unchanged and green.

**Acceptance Criteria:**
- Given an existing tenant after migration, when `GET /api/v1/stores` and a delta pull run, then each store has `country:"CM"` and null `category/description/logoUrl/niu/rccm`, and `GET /tenant/preferences` returns `defaultSaleFlow:"POS"`, with all previous fields unchanged.
- Given the V1 app body, when it updates a store, then no new field is lost.
- Given the importer port, when it sets `DOCUMENT` for a tenant, then the owner sees `DOCUMENT` and can switch back to `POS`.
- Given a tenant deletion, when tenant files are purged, then its store logos are deleted.

## Spec Change Log

- 2026-11-05 (implementation verify): `ddl-auto: validate` checks JPA entities against the public template tables, so the new JPA-mapped tenant columns made boot fail. Amended: a public migration `V49` MIRRORS the store V2 / core V3 columns on `public.stores` / `public.tenant_preferences` for boot validation only (tenants still get them from the module scripts); the "no public migration" rule of this spec is narrowed to "no public migration as the way tenants get the columns". Rejected alternative: `@Formula` read + native UPDATE (bypasses validation, splits writes). Also: `VALIDATION_ERROR` maps to HTTP 422 codebase-wide — the matrix's "400" reads as 422. KEEP: absent ⇒ unchanged partial update, shared `StoreProfileRules`, `ImageReencoder` extraction.
- 2026-11-05 (live check): on a tenant that has not finished onboarding (no `tenant_preferences` row), `PUT /tenant/sale-flow` answered 500 `PREFERENCES_NOT_FOUND`, as `PUT /leaderboard-visibility` and `/report-preferences` already did (unmapped code). Fixed: `PREFERENCES_NOT_FOUND` → 404 in `GlobalExceptionHandler`, consistent with `GET /tenant/preferences` (404 before onboarding); controller test added. KEEP: the services still throw when the row is missing (no row creation outside onboarding).

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 41 findings — high 0, medium 9, low 18, false 6, maybe-false 0 (+8 descriptive intent-alignment observations, triaged below)
- findings:
  - Blind hunter:
    - `[medium]` `[patch]` `save()` drops `defaultSaleFlow` (create path stores POS) — fixed: save writes it; update still never touches it; test added.
    - `[medium]` `[patch]` Logo written before commit under a fixed name; rollback/concurrency corrupt or orphan files — fixed: unique file name + unique temp file, row FOR UPDATE, delete old after commit and new after rollback.
    - `[medium]` `[patch]` `logoUrl` never changes on replace, no-cache without validators — fixed: one URL helper, `?v=` from the unique file name, 30-day private cache.
    - `[low]` `[patch]` Post-normalization overflow reported as TOO_LARGE with a misleading message — fixed: new `STORE_LOGO_TOO_HEAVY` (422) like the company logo.
    - `[low]` `[patch]` Upload buffered before the size check — fixed: `file.getSize()` check first.
    - `[low]` `[patch]` Declared-type allowlist refuses `image/jpg` and types with parameters — fixed: parsed media type with an extended accepted set.
    - `[low]` `[reject]` No endpoint exposes the French category labels — the app maps the 19 keys like every other enum; the mobile section carries the key→label table; adding API surface is not needed.
    - `[low]` `[patch]` Country accepts any two letters — fixed: checked against `Locale.getISOCountries()`.
    - `[low]` `[patch]` Category input is case-sensitive — fixed: trimmed and lower-cased.
    - `[low]` `[patch]` Store audit leaves out name/address/phone before/after; a logo-only event reads as a name change — fixed: diff includes them; logo event carries only the logo change.
    - `[low]` `[patch]` Sale-flow PUT `request == null` branch unreachable — fixed: `@RequestBody(required = false)`.
    - `[low]` `[reject]` `TenantSaleFlowCommandAdapter` without `@Transactional` — the UPDATE is a single atomic statement, and audit writes are `REQUIRES_NEW` by codebase design (audit-on-attempt), so a wrapping transaction changes nothing.
    - `[low]` `[patch]` Logo URL format duplicated in DTO and delta — grouped with the cache fix (one helper).
    - `[low]` `[reject]` Profile normalized up to 3 times per sync op — O(1), no observable harm.
  - Edge case hunter:
    - `[medium]` `[patch]` Concurrent uploads of different formats: afterCommit deletes the committed file — grouped with the logo durability fix.
    - `[medium]` `[patch]` Shared `.part` temp file — grouped with the logo durability fix.
    - `[medium]` `[patch]` Rollback after a disk write leaves an orphan or overwritten logo — grouped with the logo durability fix.
    - `[low]` `[patch]` `image/jpg` or a type with parameters refused — same as the blind-hunter finding.
    - `[medium]` `[patch]` Full-column UPDATE from a stale entity can restore the old flow — fixed: `@DynamicUpdate` + conditional UPDATE for the flow.
    - `[false]` `[reject]` Audit row lands in the wrong schema when the importer runs inside another schema's transaction — `AuditLogRepositoryAdapter.record` is `REQUIRES_NEW` and opens a new connection bound to the rebound `TenantContext`.
    - `[false]` `[reject]` Null `tenantId` on the owner path — the controller always runs with a bound tenant (`TenantContext.requireCurrent`).
    - `[low]` `[patch]` Missing body gets a parse error, not the French message — same as the blind-hunter finding.
    - `[low]` `[patch]` Concurrent PUTs audit from a stale before — fixed: conditional UPDATE, audit only when one row changed.
    - `[low]` `[patch]` Non-ISO country — same as the blind-hunter finding.
  - Pattern / complexity:
    - `[medium]` `[patch]` Logo not versioned, re-downloaded on every view — same as the blind-hunter finding.
    - `[low]` `[patch]` Image work and cheap checks run after the DB read — fixed: checks and normalize before `find()`.
    - `[low]` `[reject]` Triple normalization — same as the blind-hunter finding.
    - `[low]` `[patch]` Sale flow: 2 SELECTs + 1 UPDATE with no lock — fixed: one conditional UPDATE.
    - `[low]` `[patch]` `count(*)` used for an existence test — fixed: `EXISTS`.
    - `[false]` `[reject]` Per-pixel transparency loop — pre-existing code moved unchanged, bounded at 600×600.
    - `[false]` `[reject]` `TenantPreferences` constructor chain — no defect today; a builder can come the next time the record changes.
  - Verification gap:
    - `[medium]` `[patch]` Deactivate/reactivate never proven to keep the Bref fields — tests added with a fully filled store.
    - `[medium]` `[patch]` Importer port "no preferences row" failure untested — test added (IllegalStateException, no audit).
    - `[medium]` `[patch]` Same-format replace overwritten on rollback — grouped with the logo durability fix.
  - Intent alignment:
    - `[low]` `[defer]` Imported Bref tenant receives DOCUMENT only through the port; no importer calls it yet → routed to 24-2 (the importer must call `TenantSaleFlowCommandPort.set(schema, DOCUMENT)`).
    - `[false]` `[reject]` Landing screen by flow — client behaviour (mobile section and 19.13), API-only repo.
    - `[false]` `[reject]` Per-feature storage port instead of one shared port — consistent with product photos and company logo (Ports & Adapters); S3 is another adapter of the port.
    - `[low]` `[reject]` `logoUrl` exposed instead of the raw `logo_path` — deliberate: never expose disk paths.
    - `[low]` `[reject]` Flutter tolerance of extra fields untested — V1 parses by key (checked in `release/2.0.x` `remote_store_datasource.dart`); covered by the end-of-epic device test.
    - `[low]` `[reject]` Sync push writes not covered by the "(OWNER)" wording — false concern: both handlers keep the OWNER-only guard (`StoreSyncHandler.java:71`, `CreateStoreSyncHandler.java:72`).
    - `[low]` `[reject]` Extra work (audit, V49 mirror, purge, re-encoder) — required by project rules or by boot validation.

## Design Notes

- **Patterns:** Ports & Adapters for the logo (domain port `StoreLogoStorage`, disk adapter; S3 = another adapter); a small domain **Value Object/validator** (`StoreCategory` enum + one rules helper) shared by REST and sync so the two entry points cannot diverge; **partial update by presence** (absent ⇒ keep) to stay V1-safe; extraction of a shared image re-encoder (DRY) instead of a third copy.
- **Cost:** every endpoint is O(1) queries (store by id + one update; prefs one row). Delta pull: same keyset query, 6 more columns, no new index, no N+1. Logo read: one file read, size bounded by max-bytes. Migrations: `ADD COLUMN … DEFAULT const` is metadata-only (no rewrite) — O(1) per tenant schema.
- `logoUrl` is a stable API path; clients refresh after `updatedAt` changes (delta) — no cache-busting token stored.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/store/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green (then sync, identity, shared packages one at a time).
- `mvn -q -o compile` -- no errors; ArchUnit/Modulith tests green (no store→identity dependency; shared image helper has no module dependency).

**Manual checks:**
- Local backend (WhatsApp noop, FCM off): curl PATCH/POST stores with new fields, logo PUT/GET/DELETE, `PUT /tenant/sale-flow`; `psql` shows columns on a dev tenant.

## Auto Run Result

Status: done

**Summary.** Stores carry Bref's profile fields — `category` (19 keys, lower-cased), `description`, `niu`, `rccm`, `country` (ISO alpha-2, upper-cased, default `CM`) — through `POST/PATCH /stores` (absent ⇒ unchanged, V1 bodies keep the values), the store sync push (OWNER) and pull (with `logoUrl`), validated by one shared `StoreProfileRules`. Owners upload (PNG/JPEG ≤ 200 KB, re-encoded by the shared `ImageReencoder`), read (all roles, 30-day private cache, versioned URL) and delete a store logo behind a storage port (local disk, unique names, old file deleted after commit, new one after rollback, row locked). `tenant_preferences.default_sale_flow` (POS by default, DOCUMENT) is returned by `GET /tenant/preferences`, set by the owner via `PUT /tenant/sale-flow` (not gated by `report-config`, audited `SALE_FLOW_UPDATED` only when it changes) and by the 24.2 importer through `TenantSaleFlowCommandPort`.

**Files.** New: tenant migrations store `V2`, core `V3`; public `V49` (template mirror for boot validation); `StoreCategory`, `StoreCountry`, `StoreLogoUrls`, `StoreProfilePatch`, `StoreProfileRules`, `StoreLogoController`/`Service`/`UseCase`, `StoreLogoStorage` + `LocalDiskStoreLogoStorage`, `StoreLogoNormalizer`, shared `ImageReencoder`, `SaleFlow`, `TenantSaleFlowCommandPort` + adapter, `UpdateSaleFlowService`/`UseCase`/`RequestDto`, `SaleFlowChangedEvent`, `StoreProfilePayload`, tests. Changed: store domain/persistence/REST DTOs, store sync handlers and delta provider, `TenantPreferences*`, `ImageIoCompanyLogoNormalizer`, error codes (`STORE_LOGO_*`), `GlobalExceptionHandler` (`PREFERENCES_NOT_FOUND` → 404), `AuditLabels`, `db/modules/README.md`, `docs/entitlements/endpoints.md`, export datasets contract.

**Review.** 41 findings (5 layers): 0 high, 9 medium, 18 low, 6 false + 8 intent observations. Patched: all 9 medium (save path dropping the flow, logo write/rollback/concurrency, logo URL versioning, …) and the low ones listed in the triage log. Routed: 1 low → 24.2 (importer sets DOCUMENT). Rejected: reasons in the triage log.

**Follow-up review: recommended (true)** — several medium patches on the logo path (file lifecycle around commit/rollback, row lock) are covered by unit and full-context tests but concurrent replace was not exercised live; the public `V49` mirror is a new pattern (rule added to `db/modules/README.md`).

**Verification.** Full suite package by package: 5 738 tests, 0 failures (acceptance 4, admin 586, architecture 115, catalog 306, commerce 265, crm 158, feedback 52, identity 801, inventory 411, invoicing 179, messaging 357, reporting 422, shared 1 209, store 123, subscription 106, sync 644); after the 404 fix, `ReportPreferencesControllerTest`, `GlobalExceptionHandlerTest`, `UpdateSaleFlowServiceTest` green. Live `keevo_dev` (WhatsApp noop, FCM/Expo off), new tenant: store fields default `country:"CM"`; PATCH with `"Fashion"`/`"cm"` → `fashion`/`CM`; bad category / country `ZZ` → 422 in French; PATCH with the name only keeps the other fields; logo GET before upload → 404 `STORE_LOGO_NOT_FOUND`, PUT PNG → 200 with versioned `logoUrl`, GET → `image/png` `private, max-age=2592000`, text file → 422 `STORE_LOGO_INVALID`, DELETE → 200 then GET 404; sale flow: before onboarding 500 (fixed to 404, see change log), after onboarding GET `POS`, PUT `DOCUMENT` → 200, same PUT again → 200 and a single `SALE_FLOW_UPDATED` audit row; `psql` shows the columns and values on the tenant schema.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit d601c9f4), branche `refonte/modulaire` / `develop`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

### Ce qui est fait côté API
- Une boutique porte maintenant, comme dans Bref : `category` (19 clés), `description`, `niu`, `rccm`, `country` (code pays à deux lettres, `CM` par défaut) et un logo (`logoUrl`).
- Le tenant a un parcours de vente par défaut `defaultSaleFlow` : `POS` (« Caisse ») ou `DOCUMENT` (« Documents »). Tous les tenants Keevo existants sont en `POS` ; l'import Bref (24.2) mettra `DOCUMENT`.
- Champs ajoutés uniquement : l'app V1 Flutter 2.0.1 continue de fonctionner, et un envoi sans ces champs ne les efface jamais.

### À utiliser
- **Lire les boutiques** : `GET /api/v1/stores` (OWNER) et le pull de synchro (entité `stores`). Chaque boutique contient en plus `category` (clé ou `null`), `description`, `niu`, `rccm`, `country` et `logoUrl` (`null` ou `/api/v1/stores/{id}/logo?v=…`).
- **Créer / modifier** (OWNER) : `POST /api/v1/stores` et `PATCH /api/v1/stores/{id}`, plus les opérations de synchro `CREATE_STORE` / `UPDATE_STORE`, avec les mêmes clés optionnelles.
  - Règle d'écriture : un champ absent ou `null` est laissé tel quel ; une chaîne vide efface `category`, `description`, `niu` ou `rccm` ; `country` ne peut pas être effacé.
  - `category` : la clé en minuscules, une des 19 valeurs ci-dessous.
  - `country` : code ISO à deux lettres (la casse est corrigée).
  - Longueurs maximales : description 1000, NIU 30, RCCM 50.
- **Logo** (`keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/StoreLogoController.java`) :
  - `PUT /api/v1/stores/{id}/logo` : OWNER, multipart, champ `file`, PNG ou JPEG de 200 Ko maximum. Renvoie la boutique avec un nouveau `logoUrl`.
  - `GET` sur le `logoUrl` : tous les rôles ; l'image peut être mise en cache 30 jours, et l'URL change quand le logo change.
  - `DELETE /api/v1/stores/{id}/logo` : OWNER. Renvoie la boutique avec `logoUrl: null`.
- **Parcours de vente** :
  - `GET /api/v1/tenant/preferences` contient `defaultSaleFlow`.
  - `PUT /api/v1/tenant/sale-flow` (OWNER) avec `{"defaultSaleFlow":"POS"|"DOCUMENT"}` renvoie les préférences.
- **Erreurs** (le message français vient de la réponse) :

| Code | HTTP | Message | Réaction de l'app |
|---|---|---|---|
| `VALIDATION_ERROR` | 422 | « Catégorie de boutique inconnue », « Pays inconnu : indiquez le code du pays en deux lettres (par exemple CM) », « Le pays de la boutique est obligatoire », « Le NIU ne doit pas dépasser 30 caractères »… | afficher le message sous le champ |
| `STORE_LOGO_INVALID` | 422 | « Format de logo non pris en charge (PNG ou JPEG) » | proposer une autre image |
| `STORE_LOGO_TOO_LARGE` | 422 | « Le logo est trop lourd (200 Ko maximum) » | compresser avant l'envoi (600 px, JPEG ~80 %) |
| `STORE_LOGO_TOO_HEAVY` | 422 | « Logo trop lourd après traitement : choisissez une image plus simple » | proposer une autre image |
| `STORE_LOGO_NOT_FOUND` | 404 | « Cette boutique n'a pas de logo » | afficher l'initiale de la boutique |
| `STORE_NOT_FOUND` | 404 | « Boutique introuvable » | revenir à la liste |
| `PREFERENCES_NOT_FOUND` | 404 | « Préférences introuvables » (entreprise pas encore configurée) | terminer l'onboarding puis réessayer |
| `FORBIDDEN` | 403 | — | n'afficher l'édition qu'au propriétaire |

- **Catégories** (clé → libellé à afficher) :
  - fashion → Mode et vêtements ; electronics → Électronique ; telecom → Téléphonie ; food → Alimentation ; restaurant → Restauration
  - beauty → Beauté et cosmétiques ; sports → Sport et loisirs ; home → Maison et décoration ; furniture → Ameublement ; hardware → Quincaillerie
  - automotive → Automobile ; agriculture → Agriculture ; stationery → Papeterie ; jewelry → Bijouterie ; events → Événementiel
  - education → Éducation ; real_estate → Immobilier ; services → Services ; other → Autre

### À implémenter côté mobile
1. **Fiche boutique (propriétaire)** :
   - catégorie : liste des 19 libellés, la clé envoyée ;
   - description, NIU, RCCM ;
   - pays : liste de pays, Cameroun par défaut ;
   - logo : choisir, recadrer, compresser à 600 px avant l'envoi, puis remplacer ou supprimer.
2. **Affichage du logo** partout où la boutique apparaît. Il est mis en cache par URL ; sans logo, on affiche l'initiale.
3. **Hors ligne** : les champs texte passent par `CREATE_STORE` / `UPDATE_STORE`. Le logo n'est envoyé qu'en ligne ; sans réseau, afficher « Le logo sera envoyé au retour du réseau » et réessayer plus tard.
4. **Paramètres › Parcours de vente** (propriétaire) : choix « Caisse » ou « Documents ». Il décide de l'écran d'arrivée ; la navigation complète relève de la 19.13.
5. Ne jamais afficher une clé (`real_estate`, `POS`) : seulement les libellés français.

### Implémentation de référence
Aucune dans keevo/app (l'app Flutter ne sert qu'au test). Côté backend : `keevo/backend/src/main/java/com/keevo/store/store/domain/model/StoreProfileRules.java` (règles), `store/api/StoreCategory.java` (libellés), `identity/api/SaleFlow.java`.

### Critères d'acceptation mobile / scénarios de test
- Given un propriétaire connecté, When il choisit « Immobilier » et le pays « Côte d'Ivoire », Then la boutique se recharge avec ces libellés et `country: "CI"`.
- Given une boutique avec logo, When le propriétaire envoie un nouveau logo, Then toutes les vues affichent le nouveau logo (nouvelle URL), sans vider le cache.
- Given un vendeur, When il ouvre l'app, Then il voit le logo mais aucune action de modification.
- Given « Documents » choisi, When l'app redémarre, Then elle arrive sur les documents.
- Préparation (curl) :
  - `PATCH $API/api/v1/stores/$ID` avec `{"name":"Ma boutique","category":"real_estate","country":"ci"}` ;
  - `curl -X PUT -F file=@logo.png $API/api/v1/stores/$ID/logo` ;
  - `curl -X PUT -d '{"defaultSaleFlow":"DOCUMENT"}' $API/api/v1/tenant/sale-flow`.

### Clé de suivi mobile
`m19-12-boutiques-etendues-parcours-vente`
