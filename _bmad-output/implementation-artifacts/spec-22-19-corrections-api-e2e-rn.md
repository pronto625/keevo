---
title: '22-19 — API corrections from the RN end-to-end tests of 23/11 (kv-fix-migration follow-up, B2/B3, majors)'
type: 'bugfix'
created: '2026-11-23'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'b38838e3ad220968c05a685d56e798dca873f8f8'
context:
  - '{project-root}/_bmad-output/test-artifacts/e2e-rn-2026-11-23/RAPPORT.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The 23/11 end-to-end run of the RN app (AI/bref `b9050d5`) against `refonte/modulaire` + the mobile developer's branch `kv-fix-migration` (merged fast-forward, `b38838e3ad220968c05a685d56e798dca873f8f8`) left backend defects: R1–R10 (report §0.5), the backend part of B2 (two stock counters, negative stock) and B3 (seller cannot read its store), and the backend majors of §7.2 (disabled account login, portal OTP silent failure, 429 ceiling, English audit labels).

**Approach:** Fix every backend item in one story (user decision 23/11: « corrige tout, que ce soit côté mobile ou côté backend »), additive API changes only (V1 2.0.1 apps stay compatible), then re-run the device tests with the corrected app (story in AI/bref, branch `fix/e2e-corrections-2026-11-23`).

## Boundaries & Constraints

**Always:** additive responses; French error messages via ErrorCode/ErrorMessages; set-based SQL; tenant schema from TenantContext; tests per touched package, run in chunks.

**Never:** deploy; change V1 response shapes; break the Bref import/cutover (24-x) behaviour except to mark setup completed.

## Decisions (user delegated all choices, 23/11)

- D1 (R1) `setup_completed_at = NOW()` when a tenant is provisioned by `/auth/register` (OwnerTenantProvisioner path used by RegistrationService) and by the Bref import (`LegacyIdentityImportService.createOwnerTenant`). `create-company` keeps NULL (the app runs its 3 steps).
- D2 (R2) `GET /tenant/preferences` never writes: without a row it returns the defaults (same DTO, `sectorType` null). `POST /account/company/setup/complete` runs the sector onboarding once (same core as `OnboardingService.complete`: sector template, TenantOnboardedEvent, prefs row, OnboardingCompletedEvent) with the sector mapped from the company category, idempotent when prefs already exist. `PUT /tenant/report-preferences` without a row creates it from defaults first.
- D3 (R3) report-preferences accept `HH:mm` and `HH:mm:ss`; GET keeps `HH:mm`.
- D4 (R4) Issuing never fails because of an earlier requested date: issue date = max(requested date, latest issued date of the series); the requested date stays in `requested_issue_date`. The counter row is locked before reading the latest date (no race). A requested date in the future stays refused.
- D5 (R5) `totalPending` = amount still due on UNPAID+PARTIAL (TTC − paid − credit notes); credit notes reduce `totalAmount`.
- D6 (R6) Sale-generated invoice lines carry the sale line `productId`; top products group by product first.
- D7 (R7) `@RequiresPermission(INVOICE_VIEW)` on stats and top-products.
- D8 (R8) Marketing consent goes through the CapabilityRegistry (no direct ClientCommandPort), in its own transaction, failure logged only; updating an instalment publishes an event recorded in audit + invoice history.
- D9 (R9) `memberships[].tenantName` = company designation, else name, else code; category errors list French labels.
- D10 (R10) Upload folder = `bref/<tenant code>/<folder>`; HEIC accepted only for heic/heix/hevc/mif1/msf1 brands; per-tenant upload limit 30/min (429 French); tests for stats, top-products, uploads, instalment update, issue date.
- D11 (B2) `POST /products` with `stockQuantity > 0` and stock tracking records a stock entry (movement « Stock initial ») in `storeId` if given else the default store; `products.stock_quantity` is kept equal to the sum of the store levels by the stock operation service. Online sale (REST) beyond the store level → 409 `INSUFFICIENT_STOCK` « Stock insuffisant dans cette boutique : il reste N ». Offline sale pushed by sync beyond stock → applied (the goods are gone), level floored at 0 with an automatic adjustment movement « Écart constaté à la synchronisation », op status APPLIED with a French warning, stock alert published.
- D12 (B3) `GET /stores/{id}` readable by any active member of that store (owner: any store); `/auth/profile` already returns `storeId`.
- D13 disabled account: `/auth/login` and `/auth/otp/verify` for a user whose only memberships are deactivated → 403 `ACCOUNT_DISABLED` « Votre compte a été désactivé. Contactez le propriétaire de la boutique. »
- D14 portal code request: delivery failure → 503 `CODE_DELIVERY_FAILED` « Le code n'a pas pu être envoyé. Réessayez dans quelques minutes. »
- D15 rate limit: property `keevo.rate-limit.requests-per-minute` default 300 per tenant (was hardcoded 100).
- D17 (B1 owner name) `PUT /api/v1/auth/profile` `{firstName, lastName}` (each 1–50, trimmed, French validation) updates the authenticated user's own name (owner included, any tenant role) and returns the same body as `GET /auth/profile`; audit `PROFILE_UPDATED`.
- D18 (B5) `CANCEL_SALE` sync op cancels a COMPLETED sale of the same business day (not only pending ones) for OWNER/MANAGER or a role holding the sale-cancel permission; reverses stock movements and cancels the sale's paid invoice internally (credit note path if the domain requires it), French rejection reasons (« Une vente ne s'annule que le jour même », « Vente déjà annulée »).
- D19 (B2) per-store stock is readable without the multi-store entitlement: `GET /products?storeId=X` (and the product detail) add `storeStockQuantity` for that store (additive), and `GET /stock/stores/{id}/products` is allowed for the tenant's single/default store and for a member's own store.
- D20 product responses add `hasPhoto` (additive) so the app only requests `/products/{id}/photo` when it exists.
- D21 invoices accept optional `sellerSignature` / `clientSignature` (PNG data URL ≤ 200 KB each) on create/update, return them, and draw them in the PDF signature boxes when present.
- D16 audit: `/api/v1/audit` adds `actionLabel` (French, AuditLabels) additively; inventory count events get an object label.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Register | `/auth/register` | tenant `setup_completed_at` set |
| Bref import | cutover import of an owner | `setupCompleted=true` in `/account/company/profile` |
| Prefs read before setup | GET prefs, no row | 200 defaults, still 0 rows |
| Setup complete | category `food` | prefs row with sector FOOD_GROCERY, sector categories seeded; second call 200 no-op |
| Report prefs | PUT with `eodReportTime: "18:00"` | 200, stored 18:00:00 |
| Late offline invoice | issue with requested date J-1 after an invoice issued at J | 200, issueDate J, requestedIssueDate J-1 |
| POS oversell online | sale qty 3, store level 1 | 409 INSUFFICIENT_STOCK French |
| Offline oversell sync | SALE op qty 3, level 1 | APPLIED, level 0, adjustment movement, warning |
| Seller store | seller GET `/stores/{own}` | 200; other store 403 |
| Self profile | owner `PUT /auth/profile` `{firstName:"Aicha", lastName:"Mbarga"}` | 200, users row updated |
| Disabled seller login | only membership inactive | 403 ACCOUNT_DISABLED |

</frozen-after-approval>

## Code Map

Root `keevo/backend/src/main/java/com/keevo/`.
- `identity/auth/application/service/OwnerTenantProvisioner.java:52`, `RegistrationService.java:49`, `identity/legacyimport/application/LegacyIdentityImportService.java:69` -- D1; `identity/company/adapter/out/persistence/JdbcCompanyProfileRepository.java:39,84` markSetupCompleted.
- `identity/onboarding/application/service/OnboardingService.java:60-108`, `identity/onboarding/adapter/in/web/TenantPreferencesController.java:75-90`, `JpaTenantPreferencesRepository.java:69,107,114`, `UpdateReportPreferencesRequestDto.java:25,43`, `identity/company/application/service/CompanyProfileService.java:198,238`, `identity/company/domain/model/CompanyCategory.java:12`, `SectorKind`/`SectorType` -- D2/D3.
- `invoicing/invoice/application/usecase/InvoiceLifecycleUseCase.java:102-131`, `adapter/out/persistence/JdbcInvoiceRepository.java:384,409,420,482,491`, `adapter/in/rest/InvoiceController.java:223-260,441`, `SaleInvoiceMapper.java:68,109,111`, `domain/model/LineInput.java:13`, `ConvertQuoteUseCase.java:55,72,165,184`, `InvoicePaymentUseCase.java:121,140,194` -- D4–D8.
- `identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:41-47`, `AuthenticationService.java:103,138`, `AuthController.java:370,514` -- D9/D13.
- `upload/application/ImageUploadService.java:24,34,43,67`, `upload/adapter/out/cloudinary/CloudinaryImageStorage.java:55-59` -- D10.
- `catalog/product/application/usecase/CreateProductUseCase.java:186`, `ProductController.java:92-112`, `JpaProductDerivationRepository.java:51`, `inventory/stock/application/usecase/RecordStockEntryUseCase.java`, `StockOperationService`, `sync/sync/application/handler/SaleSyncHandler.java:75-111`, `sync/sync/application/strategy/DeltaStockStrategy.java:62`, `SyncPushService.java:592-598`, `store/store/adapter/in/api/StoreApiAdapter.java:101` -- D11.
- `store/store/adapter/in/rest/StoreController.java:35,79` -- D12.
- `identity/auth/application/service/RequestOtpService.java:103,136-157`, `clientportal/auth/adapter/in/rest/PortalAuthController.java:81` -- D14.
- `shared/infrastructure/web/RateLimitFilter.java:44,64` -- D15.
- `shared/infrastructure/export/AuditLabels.java:24`, `shared/infrastructure/web/AuditController.java`, `AuditEventListener.java:889-945` -- D16.

## Tasks & Acceptance

**Execution:**
- [x] identity (D1, D2, D3, D9, D13, D17) -- code + tests (`identity` packages)
- [x] invoicing (D4–D8, D21, D10 tests) -- code + tests
- [x] upload (D10) -- code + tests
- [x] catalog/inventory/sync/store/commerce (D11, D12, D18, D19, D20) -- code + tests
- [x] clientportal/shared (D14, D15, D16) -- code + tests
- [x] `docs/entitlements/endpoints.md` (regenerated) + this spec's Mobile section -- contract notes

**Acceptance Criteria:**
- Given each row of the I/O matrix, when replayed by API, then the expected result holds.
- Given the corrected RN app on emulator + Redmi, when the matrix and the B1–B6 scenarios are replayed online/offline, then no regression versus `matrice-r2.jsonl` and B2/B3 pass.

## Implementation Notes

- D1 — `TenantRepositoryAdapter.save` now `saveAndFlush` (the tenant row must be visible to the raw JDBC setup mark of the same transaction; `tenant_plan_events` only worked thanks to its deferred FK). `OwnerTenantProvisioner.provision(owner, device, setupCompleted)`: `/auth/register` passes true (`CompanyProfileRepository.markSetupCompleted`), `create-company` keeps the 2-arg path (NULL). Bref import: `LegacyIdentityStore.markSetupCompleted(tenantId)` right after the owner role.
- D2 — `GET /tenant/preferences` answers `TenantPreferences.defaults(null).asStored()` without a row (times « 20:00 »). The three preference writes (report, leaderboard, sale flow) create the row from the defaults first. `POST /account/company/setup/complete` → `CompanySetupService` (mark + `SetupSectorOnboardingUseCase.onboardIfAbsent`, sector = `CompanyCategory.sector(category)`, the Bref import table). A sector-less row (a settings write came first) gets the sector + template categories instead of a second row; a row with a sector = no-op.
- D3 — `UpdateReportPreferencesRequestDto.TIME` accepts `HH:mm(:ss)`, normalised to `HH:mm:ss` before the use case.
- D4 — `InvoiceTerms.issueDateOr`: future refused; an earlier year → 1 January of the current year (sync on 1/1 of a 31/12 sale). Issue: `lockCounter(year)` (counter row `FOR UPDATE`, created at 0) then issue day = max(requested, latest issued of the year); `requested_issue_date` unchanged.
- D5 — stats buckets carry `credited` and `due` (correlated sums on `idx_credit_notes_invoice` / `idx_invoice_payments_invoice`, as `outstandingByClient`); `totalAmount` = TTC − credit notes, `totalPending` = due on UNPAID + PARTIAL. `byState` unchanged (V1 shape).
- D6 — `SaleCompletedEvent.Line.productId` (additive, null in old registry rows) → sale invoice lines carry it.
- D8 — new capability `crm.api.ClientConsentPort` (provided by `CrmModule`, declared in the crm/invoicing manifests); `ConvertQuoteUseCase.recordMarketingConsent` runs in a `REQUIRES_NEW` template, any failure logged only. Instalment change → `InvoicePaymentRecordedEvent.Kind.UPDATED` → audit `INVOICE_INSTALMENT_UPDATED` « Échéance modifiée » on the invoice (its history).
- D9 — memberships SQL: `COALESCE(designation, name, code)`; `CompanyCategory.accepted()` lists the French labels (`StoreCategory`).
- D10 — `ImageUploadService.upload(..., tenantSchema)`: folder `bref/<KV-CODE>/<folder>`, HEIF brands only, `UploadRateLimiter` 30/min/tenant → 429 `UPLOAD_TOO_MANY_REQUESTS`.
- D11 — REST `POST /products` (`storeId` optional; a member: his store) → `CreateOrRestoreProductUseCase.execute(dto, storeId)` records `InitialStockPort` « Stock initial » for a fresh tracked product. `StockOperationService` keeps `products.stock_quantity` = Σ levels through `catalog.api.ProductStockCounterPort` (one aggregate + one conditional UPDATE; **`updated_at` deliberately not moved** — moving it made later offline product edits look outdated to the sync conflict rules, caught by `StockTrackingFullContextTest` / `InitialStockAtCreationFullContextTest`). Offline oversell: SALE movement kept as is, then `ADJUSTMENT` « Écart constaté à la synchronisation » to 0 with id `StockFloor.adjustmentIdOf(saleMovementId)`; `DeltaStockStrategy` finds those ids from the pushed `itemId`s (one query) → APPLIED + `conflictData.warning` (conflict type `STOCK_FLOORED`, logged in the conflict log); alert always published. REST oversell: `INSUFFICIENT_STOCK` « Stock insuffisant dans cette boutique : il reste N » — **HTTP 422 kept, not 409** (the global mapping of this code is a V1 contract shared by transfers/adjustments; « never change V1 response shapes » wins; to decide by the user).
- D12 — `GET /stores/{id}` (`GetStoreUseCase`): owner any store, member only `assignedStoreId` (403 « Vous n'avez accès qu'à votre boutique »).
- D13 — `UserRepository.hasOnlyInactiveMemberships` (one aggregate) checked when the active list is empty, on `/auth/login` and `/auth/otp/verify` (the code stays consumed).
- D14 — `RequestOtpService.send` reports whether a channel delivered / handed over the code; `CLIENT_PORTAL` purpose → 503 `CODE_DELIVERY_FAILED` when none did (merchant sign-in unchanged).
- D15 — `keevo.rate-limit.requests-per-minute` (default 300), 429 body states the configured value.
- D16 — `AuditEntryResponse.actionLabel` (`AuditLabels.ACTIONS`, humanised fallback); inventory count events labelled « Comptage d'inventaire ». New labels `PROFILE_UPDATED`, `INVOICE_INSTALMENT_UPDATED` (export contract regenerated). Note: the V1 Flutter `AuditPresenter` contract test is on the frozen app side — it will see two new codes (humanised there).
- D17 — `PUT /api/v1/auth/profile` (`OwnProfileController` → `UpdateOwnProfileService`): names trimmed 1–50 (French 422), `public.users` + the member's employee record, `UserProfileUpdatedEvent` → audit `PROFILE_UPDATED`; same body as the GET.
- D18 — `CANCEL_SALE` (sync): owner or holder of new permission `SALE_CANCEL` « Annuler une vente du jour » (MANAGER by default, pos manifest); `CancelSaleInput(..., pendingOnly=false, sameDayOnly=true)`; a COMPLETED sale of another business day (WAT) → `SALE_CANCEL_NOT_SAME_DAY`; refusals carry `conflictData.message` in French; blank justification → « Annulation de la vente depuis la caisse ». Stock restored by the existing path; the sale invoice is credited by the existing `SaleCancelledEvent` listener (`SaleCreditNoteUseCase`).
- D19 — `GET /products?storeId=` and `GET /products/{id}?storeId=` add `storeStockQuantity` (`ProductStockQuantities`, one grouped query; a member: own store only, else 403). `GET /stock/stores/{id}/products` gated by `inventory` (read): member = own store (existing scope), owner = default store free, other stores still need `multi-store`.
- D20 — `hasPhoto` on product list (one grouped photo query), detail and write answers.
- D21 — `InvoiceRequestDto.sellerSignature` / `clientSignature` (PNG data URLs ≤ 200 KB, checked before any write) stored through `InvoicePresentationUseCase.sign` (REST create/update; drawn in the PDF boxes as in 20.7); create/update/read answers carry them back as data URLs (lists do not).
- Verification (23/11, chunked, 0 failure at the end): identity.** + migration.** ; invoicing.**, upload, crm, clientportal, store ; inventory.**, sync.**, catalog.**, commerce.sale.** ; shared.**, architecture.** (incl. `ApplicationModulesBaselineTest`, `EndpointEntitlementCoverageTest`), `ExportContractFileTest` ; acceptance.**, reporting.**. New: `acceptance/Corrections2219FullContextTest` (the I/O matrix by HTTP on PostgreSQL: register, prefs, setup, HH:mm, own name, audit label, memberships name, disabled login, initial stock, online/offline oversell, per-store read, seller store, CANCEL_SALE of the day, late invoice, stats, signatures), plus unit tests per decision.
- Pre-existing test breaks fixed on the way: invoicing module V20 (mobile branch) not reflected in `InvoicingMigrationPostgresIntegrationTest`, `InvoicingTermsMigrationPostgresIntegrationTest`, `InvoiceSendingFullContextTest`, `InvoiceSyncFullContextTest`;  `create-company` now requires `name` (79c5f7b5) — `OtpSignInFullContextTest`, `SocialSignInFullContextTest`, `BrefJourneyAcceptanceFullContextTest` send it.

## Spec Change Log

- 2026-11-23 device replay — `setup/complete` applied the 19.13 sale-flow module rules with the default POS flow, so a new RN merchant (no flow asked by the app) lost the invoicing module (403 MODULE_DISABLED_BY_OWNER). Amended: `OnboardingCompletedEvent.skipSaleFlowRules` (additive, false by default and for payloads stored before 22.19), true only on the sign-up steps' path; `/onboarding/complete` (V1) unchanged. Golden payload updated (additive field). KEEP: sector + template categories on setup/complete.

- 2026-11-23 review 2 fixes — D18 same-day rule uses the till time (`CancelSaleInput.cancelledAt` = op `clientTimestamp`, ignored if > 5 min ahead of the server or before the sale); a floored offline sale gives back only what left the stock when cancelled; `STOCK_FLOORED.missingQuantity` ≤ line quantity, names in the same query; onboarding done = a prefs row WITH a sector, sector-less rows reused, prefs find-or-insert under a per-tenant xact lock (`TenantPreferencesRepository.lockForCreation`); signatures written with the draft in one transaction, same image not re-signed; clamped issue day moves an earlier due date (`due_date` now written at issue); `totalOverdue` = still owed; unknown/closed `storeId` at product creation → 422; one store-read rule (member with a store → own store; owner or member without store → default store free, others need `multi-store`); uploads counted after storage availability and released on failure, folder = tenant code read from `public.tenants`; `SALE_ALREADY_CANCELLED` REST text restored (short text only in the sync `conflictData.message`); category accepted by key or French label; instalment audit carries previous values and is skipped for a note-only change; `PUT /auth/profile` on the VIEWER self-service allow-list.
- 2026-11-23 review 1 — D11 online oversell status: implementation kept 422 `INSUFFICIENT_STOCK` (global code already returned to V1 for transfers/adjustments; V1 contract wins over the 409 written in D11). Decision taken under the user's delegation; the app shows the server `error` text whatever the status. KEEP: French message « Stock insuffisant dans cette boutique : il reste N ».

## Review Triage Log

| Source | Finding | Verdict | Evidence / route |
|---|---|---|---|
| BH/EC | D11 oversell answers 422 not 409 | medium | deliberate: INSUFFICIENT_STOCK is global and V1 receives it for transfers/adjustments; kept 422 (V1 contract), Mobile line 141 corrected → patch (doc + test name) |
| BH | @Transactional moved from lockAndHasNonZeroStock to sumQuantityByProduct | medium | verified StockLevelRepositoryAdapter.java:44-50; latent TransactionRequiredException → patch |
| BH/EC/VG | PUT /auth/profile missing from ViewerWriteGuardFilter.ALLOWED_WRITES | medium | verified ViewerWriteGuardFilter.java:49; VIEWER gets 403 against D17 → patch |
| BH/EC | same-day cancel compares with server clock, late sync refused | medium | verified ValidateSaleService.java:222; offline cancel at 23:55 synced 00:10 refused → patch (clientTimestamp, bounded skew) |
| EC | floored offline sale later cancelled restores full quantity (phantom stock) | medium | restore ignores StockFloor adjustment → patch |
| EC | missingQuantity overstated when level already negative | low | direct min() fix → patch |
| BH | /auth/register tenants never get sector onboarding | false | V1 register flow calls POST /onboarding/complete itself; RN uses create-company → setup/complete |
| EC | settings write first creates sector-less row, then /onboarding/complete refuses | medium | hasOnboardingCompleted = count>0 → patch (sector_type not null + reuse row) |
| BH/EC | first preference writes can race into two rows | medium | find-then-insert without lock in 4 services → patch (per-tenant advisory lock) |
| BH/EC | signatures stored outside the draft transaction; re-signed on every save | medium | controller not transactional → patch (one use case, sign only on change) |
| EC | clamped issue date still fails when dueDate precedes it | medium | assertDueDateFrom throws after clamp → patch (dueDate = max(dueDate, issueDay)) |
| BH/EC | totalOverdue gross while totalPending net | medium | InvoiceController stats → patch (use due) |
| BH/EC | unknown/inactive storeId silently falls back to default store | medium | CreateOrRestoreProductUseCase → patch (422 VALIDATION_ERROR storeId) |
| EC/BH | company-wide non-owner (no assigned store) cannot read per-store stock; MultiStoreStockController skips entitlement for it | medium | ProductController.java:352 / MultiStoreStockController → patch (one rule: owner or company-wide member → any store with multi-store check off the default store; store member → own store) |
| EC | upload limiter slot consumed when storage unavailable/fails | low | direct reorder → patch |
| BH | upload folder derived from schema name instead of tenants.code | medium | ImageUploadService.tenantFolder → patch (read code); bref/ prefix present in CloudinaryImageStorage (false part) |
| BH/VG | SALE_ALREADY_CANCELLED global text changed (V1 sees it) | medium | verified ErrorMessages.java:87 → patch (restore global text, short text only in sync conflictData) |
| BH | category error lists French labels the API rejects; SECTORS copy not pinned | low | → patch (accept labels case/accent-insensitive, pin test) |
| BH | N+1 product name lookups in flooredSale | low | project rule set-based → patch |
| BH | instalment-update audit lacks before values, fires when unchanged | low | → patch |
| BH | updateStockQuantity updatedAt parameter ignored | low | → patch (drop param) |
| BH | Javadoc misplaced in JdbcLegacyIdentityStore | low | → patch |
| BH | MultiStoreStockController 3-arg test constructor throws | low | → patch |
| VG | D6 productId on sale invoice lines untested (tests ignore it) | medium | → patch tests |
| VG | D5 credit-note half of stats untested | medium | → patch test |
| VG | D19/D20/STOCK_FLOORED assertions pass on missing fields | medium | → patch tests |
| VG | D11 store selection untested (any()) | medium | → patch tests |
| VG | D1 import setup_completed only mocked | medium | → patch BrefOwnerImportFullContextTest assertion |
| VG | D8/D16 audit codes/labels untested; D7 permission untested | medium | → patch tests |

## Verification

**Commands:** (from `keevo/backend`, always `env -u POSTGRES_PASSWORD mvn -o …`; never the full suite at once; never `pkill -f spring-boot:run`, stop a local backend with `fuser -k 4500/tcp`; do not commit or push)
- `mvn -o test -Dtest='<package classes>'` per package (chunked) -- 0 failure
- `ApplicationModulesBaselineTest` -- green

## Mobile (React Native — dépôt AI/bref)

Statut : corrections appliquées dans le même lot par nous, branche `fix/e2e-corrections-2026-11-23` du dépôt AI/bref (story `docs/stories/correctifs-e2e-2026-11-23.md`). Clé de suivi : `m22-19-corrections-app-e2e-rn`.
À utiliser : `GET /stores/{id}` (boutique du vendeur), `storeId` de `GET /auth/profile`, 422 `INSUFFICIENT_STOCK` (statut V1 conservé, voir Spec Change Log), 403 `ACCOUNT_DISABLED`, 503 `CODE_DELIVERY_FAILED`, heures `HH:mm` acceptées, `PUT /auth/profile` pour le nom du propriétaire, `POST /account/company/setup/complete` crée les préférences et les catégories du secteur.

Contrat détaillé (ajouts seulement, réponses V1 inchangées) :
- `PUT /api/v1/auth/profile` `{firstName, lastName}` → même corps que `GET /auth/profile` ; 422 `VALIDATION_ERROR` en français si vide ou > 50.
- `POST /api/v1/products` : `storeId` facultatif ; `stockQuantity > 0` = entrée « Stock initial » dans cette boutique (sinon la boutique par défaut ; un vendeur : sa boutique). Ne plus envoyer en plus une `RECORD_STOCK_ENTRY` pour la quantité initiale (elle serait comptée deux fois).
- `GET /api/v1/products?storeId=X` et `GET /api/v1/products/{id}?storeId=X` : `storeStockQuantity` (stock de la boutique X ; absent pour un produit sans suivi) ; `hasPhoto` sur chaque produit (n'appeler `/products/{id}/photo` que s'il vaut `true`).
- `GET /api/v1/stock/stores/{id}/products` : la boutique du vendeur, et pour le propriétaire la boutique par défaut, sans l'offre multi-boutiques.
- Vente en ligne au-delà du stock : `INSUFFICIENT_STOCK` (statut HTTP 422 conservé, celui que lit V1 ; lire `domainCode`), `error` = « Stock insuffisant dans cette boutique : il reste N ». Vente hors ligne synchronisée au-delà du stock : `APPLIED`, `conflictData.conflictType = STOCK_FLOORED`, `conflictData.warning` (texte français à afficher), stock de la boutique remis à 0.
- `CANCEL_SALE` (synchro) : vente terminée du jour annulable (propriétaire, gérant ou rôle avec « Annuler une vente du jour ») ; refus `SALE_CANCEL_NOT_SAME_DAY` / `SALE_ALREADY_CANCELLED` avec `conflictData.message` en français ; `justification` facultative.
- Factures : `sellerSignature` / `clientSignature` (data URL PNG ≤ 200 Ko) à la création / modification ; renvoyées en data URL sur la création, la modification et `GET /invoices/{id}` ; dessinées dans le PDF.
- `GET /tenant/preferences` répond toujours 200 (valeurs par défaut, `sectorType` null, tant que la configuration n'est pas finie) ; `POST /account/company/setup/complete` à la fin des étapes d'inscription (il ferme les étapes et crée préférences + catégories du secteur, rejouable sans effet).
- `GET /invoices/stats` : `totalPending` = reste dû (TTC − avoirs − encaissements) des factures impayées et partielles ; `totalAmount` net des avoirs. Droit `INVOICE_VIEW` exigé.
- `POST /uploads` : 429 `UPLOAD_TOO_MANY_REQUESTS` au-delà de 30 envois/minute ; HEIC accepté, vidéo refusée.
- Journal d'audit : `actionLabel` (français) sur chaque entrée de `/api/v1/audit`.
- Plafond de requêtes : 300/min par entreprise (réglable), la sobriété côté app reste nécessaire.
