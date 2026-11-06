---
title: '20-2 Extended CRM — Bref client fields, clients per store (ADR-08), duplicate-phone warning, marketing consent'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_revision: 'a8b348a452982bcc038f2c8e76cb4798225550ed'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-1-catalogue-sans-stock-cle-fusion.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A client moved by the OWNER to another store stays on the old store's devices (no removal row in the pull).
    evidence: |-
      ClientDeltaProvider filters on the current visible set over updated_at; a row leaving the set is simply not sent. Routed to 22-5-pull-delta-fusion.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ClientDeltaProvider.java
    severity: medium
  - summary: >-
      Client list and search are unbounded and use LIKE without a trigram index (pre-existing).
    evidence: |-
      findAllScoped / searchScoped keep the pre-20.2 shape. Routed to 20-6.
    location: >-
      keevo/backend/src/main/java/com/keevo/crm/contact/adapter/out/persistence/ClientSpringRepository.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Keevo clients are a flat tenant-wide list (name, phone, email, notes). Bref merchants keep full records per shop: type, NIU, RCCM, address, contact, website, birth date, and a traced marketing consent. FUS-FR15 / ADR-08 require clients **per store**: an employee sees only their store, the owner can see all stores, stores can opt in to share clients. Creating a client whose phone already exists in the same store must warn without blocking. Campaigns (21.6) need a reliable consent and opt-out state.

**Approach:** Additive `crm` module V2 (new nullable columns + indexes, public mirror V53) and `store` module V3 (`share_clients`, mirror in the same V53). One visibility rule — `store_id IS NULL` (legacy, tenant-wide) or in the actor's visible store set — is applied to the REST list/read/write, to the sync pull and to sync writes. Duplicate-phone detection becomes a warning on create plus a pre-check endpoint. Consent gets its own endpoint and sync operation. A one-shot job fills `phone_normalized` for existing rows.

## Boundaries & Constraints

**Always:**
- Additive only (FUS-NFR1): existing request bodies keep working unchanged (V1 2.0.1 apps send none of the new fields); existing responses keep every field and only gain new ones; new fields are nullable in responses except `marketingEligible` (boolean) and `storeId` (nullable for legacy clients).
- **Store of a new client** (REST and `CREATE_CLIENT` sync): a store member (EMPLOYEE, MANAGER) always gets their assigned store (`CurrentActor.assignedStoreId()`); a `storeId` sent by a store member that differs from it → 403 `FORBIDDEN` (REST) / `REJECTED` `FORBIDDEN` (sync), like `StockController`. The OWNER may send `storeId` (must be an existing active store of the tenant, else 400 `VALIDATION_ERROR`); without it the client goes to the tenant's oldest active store (`created_at`, then `id`). Existing clients keep `store_id NULL`; no backfill of `store_id`.
- **Visibility** (one rule, one pure helper): for a store member, visible store set = {own store} ∪ (if own store has `share_clients` → every active store with `share_clients`); visible clients = `store_id IS NULL OR store_id IN visible set`. OWNER: unrestricted; `GET /api/v1/clients?storeId=X` narrows to the clients visible from store X under the same rule; without `storeId` the owner gets every store ("tous mes établissements"). A store member's `GET /{id}`, `PATCH`, archive, consent on a non-visible client → 404 `CLIENT_NOT_FOUND`; sync writes on a non-visible client → `REJECTED` `CLIENT_NOT_FOUND`. Only the OWNER may change a client's `storeId` (PATCH).
- **Sync pull** of clients for a restricted scope uses the same rule in SQL (`store_id IS NULL OR store_id = ANY(:visibleStoreIds)`), visible set computed once per pull through a store API port; unrestricted (OWNER) pull unchanged. Store pull adds `shareClients`.
- **Duplicate phone:** `phone_normalized` = E.164 form of the phone (`+237` prefixed for 9 local digits, `00` → `+`, separators stripped — reuse/extract the logic of `InvoicePhones.normalize` into a shared pure helper, without changing invoicing behaviour or its error codes). On create (REST and sync), if another non-archived client with the same `phone_normalized` exists **in the same store** (`store_id` equal, both non-null), the client is still created and the response carries the warning: REST `meta.warnings = [{code:"DUPLICATE_PHONE", existingClient:{id,name,phone,storeId}}]`, sync result `APPLIED` with `reason = "DUPLICATE_PHONE"` and `conflictData.existingClientId`. Same number in another store or in a legacy (NULL-store) client is not a duplicate. No unique constraint, no automatic merge. Pre-check: `GET /api/v1/clients/duplicates?phone=...&storeId=...` (storeId forced to the assigned store for members) → list of matching non-archived clients of that store (empty list when none).
- **Consent** (Bref semantics): `PUT /api/v1/clients/{id}/marketing-consent {consent:boolean}` and sync `SET_CLIENT_MARKETING_CONSENT {clientId, consent}`. `true` → `marketing_consent_at = now`, `marketing_consent_by = actor`; `false` (withdrawal) → both cleared. `marketing_opt_out_at` (the client's own STOP) is never written by these and is never cleared by a team consent. Eligibility (`marketingEligible`, and a `ClientQueryPort` method for 21.6) = `marketing_consent_at IS NOT NULL AND marketing_opt_out_at IS NULL AND NOT archived` — a withdrawal makes the client ineligible from the moment it commits. Consent changes are audited (French label) and never accepted from VIEWER (existing `ViewerWriteGuardFilter`).
- New fields on create/update (REST DTOs, sync payloads): `clientType` (`PERSON` | `COMPANY`, nullable), `niu`, `rccm`, `address`, `contactName`, `website`, `birthDate` (ISO date, not in the future), with Bref-compatible length limits (validated, French messages). PATCH: absent = unchanged, blank string = cleared (20.1 convention). `phone_normalized`, consent columns and `store_id` are never taken from create/update payloads except `storeId` as stated above.
- Pull and REST responses carry every new column (`clientType`, `niu`, `rccm`, `address`, `contactName`, `website`, `birthDate`, `storeId`, `phoneNormalized`, `marketingConsentAt`, `marketingOptOutAt`, `marketingConsentBy`, `marketingEligible`); export dataset `clients` gains the user-meaningful ones (French headers, no UUIDs: store name instead of store id, consent as Oui/Non + date); `export-datasets.json` regenerated.
- Design notes complexity rules (FUS-NFR11): indexes `clients(store_id, phone_normalized)` and `clients(store_id)`; visibility resolved with at most one stores query per request/pull; backfill set-based.

**Never:**
- No change to sales/invoice client lookups (`ClientLookupPort`, `InvoiceClientResolver`, `RecordSaleService`): a sale or invoice may still reference any client by id.
- No data UPDATE in module SQL scripts; no unique constraint on phone; no automatic merge; no campaign sending (21.6) and no inbound STOP handling (21.5).
- No dashboard (super-admin) API change. No deliverable feature in `keevo/app` (Flutter is a test client; if its models must follow the API, keep it compiling and bump `pubspec.yaml`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee creates | EMPLOYEE of store A, no storeId | client `store_id = A` | — |
| Employee forges store | EMPLOYEE of A, `storeId = B` | refused | 403 `FORBIDDEN` / sync `REJECTED` |
| Owner creates without store | OWNER, tenant stores A (oldest), B | `store_id = A` | — |
| Owner creates with unknown store | `storeId` not a tenant active store | refused | 400 `VALIDATION_ERROR` |
| Employee list | A not sharing; clients in A, B, legacy NULL | A + legacy | — |
| Sharing | A and B share, C does not; EMPLOYEE of A | A + B + legacy (not C); EMPLOYEE of C sees C + legacy | — |
| Owner list | no storeId / `storeId=C` | all clients / C + legacy | — |
| Member reads other store | EMPLOYEE of A, GET client of C | not found | 404 `CLIENT_NOT_FOUND` |
| Duplicate same store | `+237 6 99 00 11 22` exists in A, create `699001122` in A | created + `DUPLICATE_PHONE` warning with existing client | 201 |
| Same phone other store | exists in B, create in A | created, no warning | — |
| Archived duplicate | only an archived match in A | no warning | — |
| Consent withdrawal | consent true then `consent:false` | consent_at/by null, `marketingEligible=false` | — |
| Opt-out wins | `marketing_opt_out_at` set, team `consent:true` | consent_at set, opt-out kept, `marketingEligible=false` | — |
| V1 create | V1 body (name, phone, email, notes) | created, store per rules, response is a superset | — |
| Backfill | existing rows with `phone_normalized NULL` | filled once (marker), re-run no-op | per-tenant failure logged, others continue |

</intent-contract>

## Code Map

All backend paths under `keevo/backend/src/main/`.
- `resources/db/modules/crm/V1__crm_baseline.sql` -- `clients` L26-40; indexes L99+; style (idempotent, `SET LOCAL lock_timeout`) to copy into new `V2__clients_bref_fields_store.sql`. `idx_clients_sync_keyset` belongs to `java/com/keevo/shared/infrastructure/persistence/SyncKeysetIndex.java:74` (do not duplicate).
- `resources/db/modules/store/V2__stores_bref_fields.sql` -- style for new `V3__stores_share_clients.sql`. Public mirrors: `resources/db/migration/V1__baseline_public.sql:44-54` (clients), `V49__public_templates_store_bref_fields_sale_flow.sql` (stores); latest is `V52`, add `V53__public_templates_clients_bref_fields_store_share.sql`. Mirror rule: `resources/db/modules/README.md:21-31`. Fixture: `keevo/backend/src/test/resources/tenant-shape/fresh-tenant-shape.json`.
- CRM (`java/com/keevo/crm/`): domain `contact/domain/entity/Client.java:15-33` (record, E.164 regex); JPA `contact/adapter/out/persistence/entity/ClientJpaEntity.java`; `ClientRepository.java:19-23`, `ClientSpringRepository.java:17,21`, `ClientRepositoryAdapter.java`; use cases in `contact/application/usecase/` (Create, GetClients, GetClientProfile, Update, Archive, Unarchive); API `crm/api/` (`ClientCommandPort`, `ClientQueryPort`, `ClientLookupPort` — unchanged semantics, `ClientView`, `CreateClientInput`, `UpdateClientInput`), adapters `contact/adapter/in/api/ClientApiAdapter.java`, `ContactViews.java`; controller `contact/adapter/in/web/ClientController.java` (`/api/v1/clients`, class `@PreAuthorize` L26, endpoints L48/62/74/90/103, `@RequiresEntitlement("crm")`); DTOs `contact/adapter/in/web/dto/` (`CreateClientRequestDto`, `UpdateClientRequestDto`, `ClientResponseDto`, `ClientProfileResponseDto`).
- Sync: `crm/contact/adapter/in/sync/CreateClientSyncHandler.java` (CREATE_CLIENT, idempotent on id), `ClientSyncHandler.java` (UPDATE/ARCHIVE/UNARCHIVE_CLIENT); `sync/sync/application/service/SyncPushService.java:169-172` (CREATE_CLIENT hoisted tier; register `SET_CLIENT_MARKETING_CONSENT` like UPDATE_CLIENT); result type `SyncOperationResult(operationId, status, serverEntityId, reason, conflictData)`. Pull: `sync/sync/application/provider/ClientDeltaProvider.java:36-53` (unscoped today) — copy `SaleDeltaProvider.java` (`queryDeltaPage(..., PullScope)`), `DeltaPageQuery.java:115` `fetchForStore` / `fetch(..., filter, Map params)` (bind a UUID array for `= ANY(:visibleStoreIds)`); `StoreDeltaProvider.java:39-58` (add `shareClients`). `PullScope` (`shared/application/port/sync/PullScope.java:23`).
- Current actor / store: `shared/infrastructure/security/CurrentActor.java` (`assignedStoreId()`, `isOwner()`, `isStoreMember()`), patterns `commerce/sale/adapter/in/rest/SaleController.java:41-58`, `inventory/stock/adapter/in/web/StockController.java:114-115,143`.
- Store (`java/com/keevo/store/`): `store/adapter/out/persistence/StoreJpaEntity.java`, `store/domain/model/Store.java`, `api/StoreView.java`, `api/StoreQueryPort.java`, `api/StoreProfilePatch.java:15`, `api/UpdateStoreInput.java`, `store/application/service/UpdateStoreService.java`, `store/adapter/in/rest/StoreController.java` (`PATCH /api/v1/stores/{storeId}` L94, OWNER only), `UpdateStoreRequestDto.java:13-22`. New port method: visible store ids for client sharing (own + sharing set when own shares; empty when the store is unknown/inactive).
- Phone: `invoicing/invoice/application/service/InvoicePhones.java:28` (logic to extract), `shared/domain/PhoneNumbers.java:20` (does not add +237).
- Warnings carrier: `shared/infrastructure/web/ApiResponseWrapper.java:39` `ok(data, meta)`.
- Audit: `shared/infrastructure/export/AuditEventListener.java:369-385`, `shared/infrastructure/export/AuditLabels.java:54-56`, events `shared/event/catalog/contact/`. Export: `crm/contact/adapter/out/export/ClientsExportDatasetProvider.java` (L29-38 columns), `keevo/contracts/export-datasets.json`.
- Backfill job pattern: `catalog/product/adapter/in/bootstrap/ProductKeyBackfillJob.java` (marker `public.data_migrations` L51-58, `ApplicationReadyEvent`, ShedLock; register in `SchedulerLockCoverageTest` if it takes a lock name there).
- Bref reference (read-only): `/home/toor/Project/FreeLance/AI/api-bref/src/customer/` (`domain/customer.ts` fields, `CUSTOMER_TYPES = ['person','company']`, `setMarketingConsent` in `infrastructure/persistence/relational/repositories/*.ts:253` — opt-out never touched by team consent).
- Flutter test client: `keevo/app/lib/features/contact/` (model `domain/model/client_model.dart`, datasources, pages) — only if needed to keep it compiling/coherent.

## Tasks & Acceptance

**Execution:**
- `crm/V2__clients_bref_fields_store.sql`, `store/V3__stores_share_clients.sql`, public `V53__...sql`, tenant-shape fixture -- columns `client_type VARCHAR(10)`, `niu VARCHAR(50)`, `rccm VARCHAR(50)`, `address VARCHAR(500)`, `contact_name VARCHAR(200)`, `website VARCHAR(255)`, `birth_date DATE`, `store_id UUID`, `phone_normalized VARCHAR(20)`, `marketing_consent_at TIMESTAMPTZ`, `marketing_opt_out_at TIMESTAMPTZ`, `marketing_consent_by UUID`; indexes `(store_id, phone_normalized)`, `(store_id)`; `stores.share_clients BOOLEAN NOT NULL DEFAULT false`; FK `clients.store_id → stores(id)` only if guarded like 20.1's FK.
- Shared phone helper (e.g. `shared/domain/PhoneNumbers.toE164(raw, defaultCountryCode)`) + `InvoicePhones` delegating to it (same results, same invoicing error codes) -- one normalization.
- Store: domain/JPA/view/patch/DTO/service/controller `shareClients`; `StoreQueryPort` (or a new store api port) `clientVisibleStoreIds(UUID storeId)`; `StoreDeltaProvider` field -- sharing flag and the visible set.
- CRM domain + JPA + views + DTOs + use cases + controller -- new fields, store assignment, visibility helper (pure, unit-tested), `?storeId` on list, `GET /duplicates`, `PUT /{id}/marketing-consent`, warnings in `meta`, `marketingEligible`, `ClientQueryPort` eligibility method for 21.6.
- Sync handlers (create/update/consent) + `SyncPushService` registration + `ClientDeltaProvider` scoped pull -- same rules offline.
- Events/audit/labels (consent given/withdrawn, French labels), export dataset + `export-datasets.json`, `docs/entitlements/endpoints.md` regenerated (new endpoints carry `@RequiresEntitlement("crm")`).
- `ClientPhoneBackfillJob` (crm, bootstrap) -- marker `crm-client-phone-normalized-backfill`, one set-based statement per tenant (`UPDATE clients SET phone_normalized = <normalized phone> WHERE phone_normalized IS NULL`, normalization done in SQL only if identical to the Java helper for stored E.164 phones — otherwise keyset batches of 500 in Java), per-tenant failure logged and retried next boot.
- Tests: unit (visibility helper, phone helper incl. InvoicePhones parity, consent rules, store assignment), controller/use-case tests for every matrix row, sync handler + pull provider tests (restricted/sharing/unrestricted), Postgres integration for migrations + backfill + pull SQL, architecture tests green.
- Spec final section `## Auto Run Result

Status: done

**Change:** Bref client fields (type, NIU, RCCM, address, contact, website, birth date), clients per store (ADR-08: store assigned by the server, one visibility rule for REST, sync writes and the scoped pull, owner "all stores" and `?storeId`, per-store sharing `stores.share_clients`), `DUPLICATE_PHONE` warning on create plus `GET /clients/duplicates`, marketing consent (`PUT /clients/{id}/marketing-consent`, sync `SET_CLIENT_MARKETING_CONSENT`, withdrawal always wins, client STOP never overridden, eligibility port for 21.6), one E.164 phone normalization shared with invoicing, `phone_normalized` backfill job, pull re-seed when a device's visible store set changes, store-move audit.

**Files (main groups):** crm module V2, store module V3, public V53 + V54 (`user_sync_state.last_scope_client_stores`); crm domain/application/REST/sync/export/backfill (`ClientVisibility`, `ClientProfileRules`, `ClientStoreScope`, `ClientFinder`, `SetClientMarketingConsentUseCase`, `FindDuplicateClientsUseCase`, `ClientPhoneBackfillJob`, `RequestScopedClientVisibleStores`); store `StoreClientSharing` + `shareClients`; sync `ClientDeltaProvider` scoped, `SyncPushService` scope fingerprint, LWW for consent; `PhoneNumbers.toE164`; audit labels and export contract; `ClientCommandPort.createWithId` removed (unused); Flutter test client audit/export mirror, 2.0.25+97.

**Review:** 33 triage rows (43 reviewer findings): 20 patched (medium: visible-set reseed, consent LWW with withdrawal always applied, consent lost update, per-batch store reads, eligibility query + DB test, createWithId removal, store-move audit, owner pre-check across stores, export test, store sync test; low: member of inactive store, unknown storeId filter 400, regex parity, legacy ClientView phone, eligibility parity), 2 deferred (client moved out of a store stays on old devices → 22-5; unbounded client list/search → 20-6), 11 rejected with reasons in the triage log.

**Follow-up review recommended:** true — 2+ medium entries patched; named risk: the new pull scope fingerprint (cursor format, V54 column, reseed trigger) and the consent conflict rule are new sync behaviour exercised by unit and full-context tests but not yet by a real device.

**Verification:**
- Full backend suite package by package: 6,084 tests; 1 failure (`TenantSchemaInterpolationGuardTest`, a request-attribute cache key flagged as SQL) fixed by a reviewed allowlist entry, test re-run green.
- Live on keevo_dev (WhatsApp noop, FCM/Expo off): V54 applied, 364 tenants checked 0 failed; owner list with unknown storeId → 400; create (store assigned, E.164, COMPANY) → 201; same-store duplicate → 201 + `DUPLICATE_PHONE`; owner pre-check without store → matches; consent → PATCH keeps consent (eligible) → withdrawal → not eligible. Earlier implementer pass: full curl matrix incl. employee scope, sharing, sync pull.

**Residual risks:** the two deferred items; RN app must apply the 16-13 purge on a sharing-triggered reseed (documented in the Mobile section).

## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-2` (done at finalize).

**Acceptance Criteria:**
- Given an EMPLOYEE of store A on the REST API, when they create, list, read, update, archive or set consent on clients, then every rule of the I/O matrix holds and the response bodies are supersets of today's.
- Given a restricted sync pull from a device of store A, when sharing is off/on, then the clients delta contains exactly the visible clients (A [+ sharing stores] + legacy), and the stores delta carries `shareClients`.
- Given a tenant migrated from the previous build, when the new build boots, then the module migrations apply on every schema, existing clients keep `store_id NULL` and get `phone_normalized` from the backfill, and the full backend suite passes package by package.
- Given a live local backend (WhatsApp noop, FCM/Expo off) on keevo_dev, when the curl scenarios of the mobile section run, then the documented status codes, warnings and fields are observed.

## Design Notes

Pattern: **policy object + ports**. A pure `ClientVisibility` policy (inputs: actor role, assigned store, visible store set, client store) is the single source used by the use cases, the sync handlers and (as SQL `store_id IS NULL OR store_id = ANY(:ids)`) by the repository and the pull provider; the visible set comes from the store module through its API port, so crm never reads the `stores` table. Duplicate detection and consent are application services on the existing ports; the warning travels in `meta` (REST) / `reason` (sync) so request contracts stay unchanged.

Cost: list = 1 stores query (visible set, ≤ number of stores) + 1 clients query using `idx_clients(store_id)`; create = 1 visible-set or store check + 1 duplicate probe on `(store_id, phone_normalized)` + insert; pull = 1 stores query per pull + the existing keyset page query with an extra `= ANY` predicate. Backfill: one set-based statement (or 500-row keyset batches) per tenant, once.

Reconciliations: "store_id obligatoire pour les nouvelles fiches" is enforced by the server always assigning a store (members: theirs; owner: given or oldest active store) — never by a request validation that V1 apps would fail. "Le serveur renvoie un avertissement" is read as: creation succeeds and carries the warning (no DB uniqueness), with a pre-check endpoint so the app can warn before validating.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/crm/**/*Test*'` -- expected: pass
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/store/**/*Test*,com/keevo/sync/**/*Test*,com/keevo/architecture/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false --keevo.push.expo.enabled=false`), curl the matrix with an owner and an employee of a test tenant -- expected: as documented.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur `refonte/modulaire` / `develop`, commit `b2387df8`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** chaque nouveau client appartient à une boutique ; un employé ne voit que les clients de sa boutique (plus ceux des boutiques qui partagent leurs clients avec la sienne, et les anciens clients sans boutique) ; le propriétaire voit tout et peut filtrer par boutique. Créer un client dont le numéro existe déjà dans la même boutique fonctionne mais renvoie un avertissement. La fiche client porte les champs Bref et l'accord marketing (donné / retiré, par qui) ; un « STOP » du client l'emporte toujours.

**À utiliser :**
1. Champs ajoutés aux clients (REST `GET/POST/PATCH /api/v1/clients`, `GET /api/v1/clients/{id}`, synchro `CREATE_CLIENT` / `UPDATE_CLIENT`, pull `clients`) : `clientType` (`PERSON` | `COMPANY`), `niu` (≤ 50), `rccm` (≤ 50), `address` (≤ 255), `contactName` (≤ 120), `website` (≤ 200), `birthDate` (`AAAA-MM-JJ`, pas dans le futur) — absent = inchangé, chaîne vide = effacé ; `storeId` (création : l'employé n'envoie rien ou sa propre boutique ; le propriétaire choisit une boutique active, sinon la plus ancienne ; modification : propriétaire seulement). Lecture seule : `phoneNormalized` (E.164), `marketingConsentAt`, `marketingConsentBy`, `marketingOptOutAt`, `marketingEligible`. Le téléphone peut être saisi `699001122`, `+237 6 99 00 11 22` ou `00237…` : il est stocké en `+237699001122`.
2. Liste : `GET /api/v1/clients?search=&includeArchived=&storeId=` (`storeId` : propriétaire seulement, « clients vus depuis cette boutique » ; sans lui = toutes les boutiques).
3. Doublon : la création répond 201 avec `meta.warnings = [{code: "DUPLICATE_PHONE", existingClient: {id, name, phone, storeId}}]` ; en synchro `APPLIED` avec `reason = "DUPLICATE_PHONE"` et `conflictData.existingClientId`. Vérification avant validation : `GET /api/v1/clients/duplicates?phone=…&storeId=…` → liste (vide si aucun ; l'employé vérifie toujours sa boutique).
4. Accord marketing : `PUT /api/v1/clients/{id}/marketing-consent` `{"consent": true|false}` → client à jour ; synchro `SET_CLIENT_MARKETING_CONSENT` `{clientId, consent}`. Lecteur : 403.
5. Boutiques : `shareClients` (booléen) dans `GET/PATCH /api/v1/stores/{id}` (propriétaire), synchro `UPDATE_STORE` et pull `stores`.
   Quand le partage change (une boutique active ou désactive `shareClients`, une boutique qui partage est désactivée ou réactivée), le pull suivant d'un employé concerné répond `reseed: true` depuis le début : l'app applique la même règle de purge que pour un changement de boutique (spec-16-13) — elle vide ses clients locaux puis reçoit exactement les clients désormais visibles.
6. `GET /api/v1/clients/duplicates` sans `storeId` par le propriétaire : doublons de toutes ses boutiques. Le propriétaire qui change un client de boutique laisse une trace dans l'historique (« Client changé de boutique »).
7. Erreurs : client d'une autre boutique → 404 `CLIENT_NOT_FOUND` (synchro `REJECTED CLIENT_NOT_FOUND`) ; employé qui force une autre boutique → 403 `FORBIDDEN` (synchro `REJECTED FORBIDDEN`) ; boutique inconnue ou désactivée (propriétaire, création ou filtre `?storeId=`) → 400 `VALIDATION_ERROR` ; employé d'une boutique désactivée qui crée un client → 403 ; champ trop long / date invalide → 422 `VALIDATION_ERROR` avec le message français.
Code : `keevo/backend/src/main/java/com/keevo/crm/contact/adapter/in/web/ClientController.java`, `CreateClientUseCase.java`, `ClientStoreScope.java`, `ClientProfileRules.java`, `SetClientMarketingConsentUseCase.java`, `sync/.../ClientDeltaProvider.java`.

**À implémenter côté mobile :**
1. Fiche client : type (Particulier / Entreprise), NIU, RCCM, adresse, personne à contacter, site web, date de naissance ; interrupteur « Accepte les offres WhatsApp » (désactivé avec la mention « Le client s'est désinscrit » quand `marketingOptOutAt` est renseigné).
2. Avant de valider une création : appeler `duplicates` (en ligne) ou chercher localement par `phoneNormalized` dans la boutique ; afficher « Un client avec ce numéro existe déjà : X » avec « Ouvrir sa fiche » / « Créer quand même ». Après une création, afficher l'avertissement si `meta.warnings` ou `reason = DUPLICATE_PHONE` revient.
3. Propriétaire : sélecteur « Toutes mes boutiques / Boutique X » sur la liste des clients ; réglage « Partager les clients avec les autres boutiques » dans la fiche boutique.
4. Aucune donnée technique à l'écran (ni identifiants, ni codes).

**Critères d'acceptation mobile :**
- Given un employé de la boutique A, when il ouvre la liste des clients, then il ne voit que les clients de A (et des boutiques partageant avec A) et les anciens clients.
- Given un client existant `+237699001122` dans A, when l'employé crée `699001122`, then l'app l'avertit et la création reste possible.
- Given un accord retiré, when la liste des clients éligibles aux campagnes est calculée, then le client n'y figure plus.
- Mise en place : `curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"consent":true}' $API/api/v1/clients/<id>/marketing-consent`.

**Clé de suivi mobile :** `m20-2-crm-etendu-clients-par-etablissement`.
