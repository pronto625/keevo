---
title: '19.12 — Extended stores (Bref fields, logo) and default sale flow preference'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred: []
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

## Design Notes

- **Patterns:** Ports & Adapters for the logo (domain port `StoreLogoStorage`, disk adapter; S3 = another adapter); a small domain **Value Object/validator** (`StoreCategory` enum + one rules helper) shared by REST and sync so the two entry points cannot diverge; **partial update by presence** (absent ⇒ keep) to stay V1-safe; extraction of a shared image re-encoder (DRY) instead of a third copy.
- **Cost:** every endpoint is O(1) queries (store by id + one update; prefs one row). Delta pull: same keyset query, 6 more columns, no new index, no N+1. Logo read: one file read, size bounded by max-bytes. Migrations: `ADD COLUMN … DEFAULT const` is metadata-only (no rewrite) — O(1) per tenant schema.
- `logoUrl` is a stable API path; clients refresh after `updatedAt` changes (delta) — no cache-busting token stored.

## Verification

**Commands:**
- `cd keevo/backend && ./mvnw -q -o test -Dtest='com/keevo/store/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green (then sync, identity, shared packages one at a time).
- `./mvnw -q -o compile` -- no errors; ArchUnit/Modulith tests green (no store→identity dependency; shared image helper has no module dependency).

**Manual checks:**
- Local backend (WhatsApp noop, FCM off): curl PATCH/POST stores with new fields, logo PUT/GET/DELETE, `PUT /tenant/sale-flow`; `psql` shows columns on a dev tenant.
