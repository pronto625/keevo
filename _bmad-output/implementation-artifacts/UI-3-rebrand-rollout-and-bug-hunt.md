# Story UI-3: Soft/Modern rebrand rollout + bug hunt

**Epic**: cross-cutting (UI + stabilisation)
**Status**: done
**Date**: 2026-09-30
**Base**: `3a0377d` (UI-2 rebrand import from `kv-ui-update`)

---

## 1. Integration of the redesign branch

`origin/develop` = `deploy` + one commit (`3a0377d`, the UI-2 rebrand already
ported from `kv-ui-update` with the `catalog_page.dart` conflict resolved).
`deploy` and `develop` were fast-forwarded to it. No conflict, UI-only change.

Follow-up fixes on top of UI-2:

| Change | Why |
|---|---|
| `AppTheme.softTint(brightness)` used everywhere instead of `primarySoftTint` | The light tint is opaque: in dark mode it made light text unreadable |
| `lang_toggle.dart` moved `core/widgets/` → `core/widget/` | Project convention |
| New shared `SoftIconButton`, `SoftIconBadge`, `AppTheme.softShadow`, `AppTheme.softCardDecoration` | One implementation of the pastille button / round badge / soft card instead of private copies |

## 2. Rebrand rollout (UX unchanged)

Every remaining screen follows the UI-2 patterns (neutral headers, pastille
actions, soft cards, round diluted badges, no saturated gradients, no
decorative emoji, global input theme, dark-mode-safe colours). No route, key,
provider, callback, validator, condition or text changed, apart from removing
decorative emoji. Content sent outside the app (WhatsApp report bodies,
exported text) is untouched.

Features covered: catalog (all remaining pages/widgets), audit, inventory,
POS, contact, team, stores, settings, reports, profitability, secondary auth
pages (forgot/reset/change password, tenant picker), sync indicator,
notifications, debug, dashboard pages, `main_shell` overlay, offline gate banner.

`inventory/presentation/page/transfer_history_page.dart` was first blocked by the tool
permission classifier, then rebranded in wave 2 (keys, role checks and callbacks unchanged).

Tests edited because they asserted an exact emoji string:
`sync_required_modal_test.dart`, `checkout_page_test.dart`.

## 3. Behaviour decisions

- **AuthPage back button** (deterministic, no `canPop()`):
  1. mode switched inside the page → back restores the initial mode;
  2. opened in register mode → `/terms`;
  3. opened in login mode as first screen (splash, logout, session expiry) →
     no back arrow (the welcome screen would force the CGU again).
- **CGU before registration**: switching to sign-up, the `/auth/register`
  route and the splash all require `terms_accepted` (UI-1 flow: "onboarding
  seen but terms not accepted → /terms"). Login does not.

## 4. Bugs fixed (each with a failing test first)

| # | Area | Bug | Fix |
|---|---|---|---|
| 1 | App — critical | Logout / session expiry called `deleteAll()` on secure storage, deleting `db_encryption_key`; next cold start deleted the encrypted DB with every unsynced sale | `clearSessionSecrets()` deletes session keys only |
| 2 | App | Network error during token refresh, or a failing retried request, logged the user out | Session expires only when the refresh endpoint answers; retry errors go to the caller |
| 3 | App | Splash deleted a valid refresh token when the server was unreachable | Tokens deleted only when the server rejects the refresh |
| 4 | App — critical | 423 (7-day gate) aborted the pull; retries were push-only → device stuck forever with its queue | On 423: pull (lifts the server gate, v1s-13-2), then push again; retries run the full cycle |
| 5 | App | Multi-membership login bounced from `/tenant-picker` back to login (no JWT yet) | `/tenant-picker` public (falls back to login without its extra) |
| 6 | App | Registration possible without accepting the CGU | See §3 |
| 7 | App — money | Discount kept after cart edits → sale with negative total, rejected by the backend forever | Discount re-bounded on every cart change; use case clamps it |
| 8 | App — sync | `CREATE_PRODUCT` / `CREATE_DRAFT_PRODUCT` queued without entityId → product merge redirect (3dc8aff) never applied | entityId = product id |
| 9 | App — sync | Quick-add (inventory) sent `CREATE_PRODUCT` without `id` → server created another id; stock entry + count pointed to an unknown product | `id` in payload |
| 10 | App — data loss | Opening stock history deleted legitimate unsynced movements (two 1-unit sales of one product) | Dedup key includes before/after quantities |
| 11 | Backend — critical | A REJECTED op was answered DUPLICATE on replay → client deleted it as synced | `isSettled()`: only non-REJECTED log entries short-circuit |
| 12 | Backend — security | Reset-code attempts counter rolled back with the DomainException → unlimited guesses | `@Transactional(noRollbackFor = DomainException.class)` |
| 13 | Backend — security | Sync push let EMPLOYEE manage employees, validate inventories, sell or close the day in another store | Guards mirroring the REST controllers |

Stale tests fixed (code was right): `catalog_page_test` (employees may create
products since 1464d34), `users_first_name_migration_test` (schemaVersion 27),
`api_error_interceptor_test` (missing `getUserId`, also unblocked
`log_redaction_test`).

## 5. Wave 2 (2026-10-02) — backlog fixed

Every item of the original backlog was verified in code, then fixed with a
failing test first, except where noted. Agents' detailed reports are summarised here.

### 5.1 Session lifecycle (refresh token) — how it works now

The app never asks for the password again while the refresh token is valid.

| Piece | Behaviour |
|---|---|
| `SessionTokenIssuer` (backend) | Single place building access-token claims (role from the membership, real tenantStatus, employee storeId / passwordChangeRequired / firstName, new `onboardingCompleted`) and persisting the refresh token. Used by tenant selection, registration and refresh. |
| `RefreshTokenService` (backend) | Rotation: a refresh token is single-use (`replaced_by_id`, `rotated_at`, migration **V8**). Replayed rotated token whose successor is unused (lost response, or parallel send by old app versions) → new session, within 7 days. Replayed token whose successor was used → every session of the user revoked. Revoked outright (logout, password change, deactivation) → refused. Tenant DELETED or membership inactive → refused. |
| Registration | Returns a refresh token (was missing: every new owner was logged out at day 60). |
| `POST /api/v1/auth/logout` | Revokes the refresh token (public, idempotent, always 204). |
| Store reassignment | No longer revokes refresh tokens: the old token gets `STORE_REASSIGNED`, the app renews and receives the new store. **Deviation from story 3.5 AC6** (forced re-login), per owner's instruction that reconnection goes through the refresh token. |
| `SessionRefresher` (app) | Single-flight refresh shared by the HTTP interceptor and the splash. Outcomes: renewed / rejected (4xx — session over) / unreachable (offline, 5xx — session kept). |
| `AuthInterceptor` (app) | Renews an expired token before sending; on 401 TOKEN_EXPIRED or STORE_REASSIGNED renews then retries once; concurrent 401s share one refresh; only a server refusal ends the session. |
| `SessionClaimsApplier` (app) | Applies every token (login, tenant selection, registration, renewal, startup) to app state. On sign-in by a different account (`local_data_owner`), clears the local DB (key kept), the pull cursor and the cart. |
| `resolveStartupRoute` / `hasSession` (app) | Expired access token + refresh token = still signed in (renewed, or offline work continues). Setup wizard decided by `onboardingCompleted` (fixes "app killed during the wizard → wizard skipped forever"). Login, tenant selection and forced password change all go through `/splash` for the destination. |
| Sign-out (`SignOutService`) | Pushes the queue first; warns if operations remain; revokes the refresh token server-side; clears session secrets only (DB key and device id kept); the same account signing back in finds its unsynced operations. |

### 5.2 Sync engine and push semantics

- Pull paginated (keyset `hasMore`/`nextCursor`), failing provider never advances the cursor, 30 s safety margin; sale status/amount changes and transfer completion now pulled (`updated_at`).
- Push: op log claimed with `INSERT … ON CONFLICT` before running the handler (no double application on concurrent pushes); REJECTED ops reprocessed on replay; only dependency creations hoisted, the rest in client order.
- App: single-flight push/pull/sync; update ops no longer deduplicated away (edits kept in order); pending VALIDATE_SALE / VALIDATE_INVENTORY protect local stock from the pull.
- Offline sales keep their real time (`occurredAt`); offline closures keep their id and day (`closedAt`); closure window = since previous closure; offline overselling applied as CONFLICT (spec 5.3 AC2 — negative stock now allowed only on that path); inventory validation applies the counted gap on top of current stock; stock movements/counts keep the client id; weekly report covers the last completed week; corrections validated before touching stock; concurrent cancellations restore stock once.
- Native SQL (claim, keyset paging, conditional updates) verified on a throwaway PostgreSQL 16: all migrations V1–V8 apply; claim returns 1/0/1 for fresh/APPLIED/REJECTED and a concurrent claim waits then returns 0; keyset paging over equal timestamps returns every row exactly once.

### 5.3 App features

POS/reports/dashboard (18 items), catalog/inventory/audit/contacts/team/stores (36 items) — see the agents' summaries in the session; notable product decisions below.

### 5.4 Product decisions taken (confirm or reverse)

- One day closure per store and day; later sales go into the next closure.
- Negative stock possible only from offline sales synced late (CONFLICT); inventory validation floors at 0.
- Emptied inventory field reverts to the saved count (the API cannot delete a count).
- Unsynced operations of another account are cleared when a different account signs in on the device (the user is warned at sign-out).
- Sales history with no active store asks to pick a store; employee reports wait for the user id.
- Store reassignment renews the session instead of forcing a re-login.
- Inventory gap reports (WhatsApp and shared text) are signed with the user's first name, no longer the phone number (privacy; code review 2026-10-11).

### 5.6 Release checklist

1. Deploy the backend first (Flyway V8 refresh-token rotation, V9 `day_closures.received_at`, lot-1 review fixes). This app build sends fields only that backend reads: closure `id`/`closedAt`, sale `occurredAt`, movement ids as `entityId`, inventory adjustment `movementIds`. An app released before the backend creates duplicate closures and movements locally.
2. Then publish the app build.

### 5.5 Still open

- `'default'` used as store id in all-stores mode by `reports_page.dart` / `pos_speed_dial.dart` for closure state — needs a decision on closures in all-stores mode.
- Access-token lifetime is still 60 days (`application.yml`). With refresh in place it can safely be shortened (e.g. 24 h) to limit a stolen access token — deployment decision.
- `FcmService` listener de-duplication has no unit test (Firebase statics).

## 6. Verification (2026-09-30)

- `flutter analyze`: 0 errors, no new warning vs. the `8e9b24f` baseline.
- `flutter test`: 934 passed, 9 failed. Before: 13 failed, and these 9 are all in §5 "Stale tests".
- `flutter build apk --debug`: OK.
- Backend `mvn test`: 1769 run, 7 failures + 42 errors in 11 classes (Flyway/JPA
  persistence, controller slices, onboarding, account deletion). The same run on
  the untouched commit gives the identical 7 + 42 in the same classes:
  pre-existing, unrelated to this change. All sync, handler and reset-password
  tests pass.

### Verification after wave 2 (2026-10-02)

- `flutter analyze`: 0 errors; 9 warnings, all pre-existing (unused fields/imports in files not changed for these fixes).
- `flutter test`: 1140 passed, 1 failed — `offline_gate_banner_test` banner_warning_amberBannerShown (pre-existing: expects `#FFF3BF`, theme gives `#FFF9DB`). All previously failing stale tests were fixed or replaced.
- Sync, POS and dashboard suites also pass under `TZ=Africa/Douala`, `UTC` and `America/New_York`.
- `flutter build apk --debug`: OK.
- Backend `mvn test`: 1841 run; 7 failures + 42 errors, identical to the baseline before any change (same 11 persistence/controller-slice classes — no database in the test environment).

## 7. Code review

### Review Findings — lot 1/5: backend (2026-10-11)

Diff: `git diff HEAD -- keevo/backend` + untracked files (6,514 lines). Layers: Blind Hunter, Edge Case Hunter, Verification Gap, Acceptance Auditor. Remaining lots: 2 app core + auth, 3 POS/reports/dashboard, 4 catalog/inventory/audit, 5 other app features.

- [x] [Review][Decision → Patch, option 1 "harden"] Refresh-token lost-response recovery — Each recovery calls `presented.rotatedTo(newId, now)`: `rotatedAt` resets, so the 7-day window slides forever. Every replay of an old token mints one more live session, and earlier successors stay valid, so a stolen rotated token keeps working as long as its latest successor is unused. `revoke()` (logout) skips already-rotated tokens, so logging out with a token whose answer was lost leaves its successor alive. And a successor revoked by logout (`replacedById == null`) is treated like a used one: replaying the previous token after logout revokes every session of the user on every device. [RefreshTokenService.java:86,95,108]
- [x] [Review][Decision → Patch, option 1 "strict rule"] Late offline closures and sales — (a) an older offline closure pushed after a newer one falls back to a midnight window that overlaps sales the newer closure already counted (double counting in both EOD reports); (b) a sale pushed late with an `occurredAt` inside an already-closed window is in no closure; (c) client `closedAt`/`occurredAt` have no lower bound (any past day can be closed). Needs the product rule: reject, absorb into the next closure, or bound the backdating. [CloseDayService.java:56-79]
- [x] [Review][Patch] Day-closure pull pages on the client-backdated `closed_at`: an offline closure pushed late is older than other devices' cursor and never reaches them. Page on a server-stamped column (needs a `created_at`/`updated_at` column on `day_closures`, tenant DDL + backfill) [DayClosureDeltaProvider.java:30]
- [x] [Review][Patch] Forgot-password reset does not revoke refresh tokens (only `tokens_valid_after`, which refresh never checks): a stolen refresh token survives the reset. Call `refreshTokenRepository.revokeAllByUserId` like ChangePasswordService [ResetPasswordService.java:125]
- [x] [Review][Patch] REST `POST /day-closures` has no employee store-scope guard (the sync handler has one, §4 #13 claims they mirror) and now accepts a client `id` (JPA merge can overwrite another closure row) and `closedAt` [DayClosureController.java:65]
- [x] [Review][Patch] STOCK_NEGATIVE conflict raised whenever the resulting stock is negative, including a positive entry on already-negative stock (now reachable). Flag only when the op itself decreased stock [DeltaStockStrategy.java:64]
- [x] [Review][Patch] Missing tests: `POST /auth/logout` controller/security test; `noRollbackFor` pin on `RefreshTokenService.refresh`; PostgreSQL test of the sync-log `claim` SQL (1/1/0) and of `transitionStatusNative`; `DeltaStockStrategy` variant clause; `@DataJpaTest` for `updateStatusIfCurrent` [AuthControllerTest, RefreshTokenServiceTest, SyncOperationsLogSpringRepository.java:36, DeltaStockStrategyTest, SaleRepositoryAdapterTest]
- [x] [Review][Patch] `POST /auth/logout` answers 400 on a blank/missing token; spec says always 204 [AuthController.java:246]
- [x] [Review][Patch] `SaleJpaEntity` `@PreUpdate` only sets `updatedAt` when null; it should move on every update [SaleJpaEntity.java:57]
- [x] [Review][Patch] Unused `RefreshTokenRepository` dependency left in ReassignStoreService and UpdateEmployeeService [ReassignStoreService.java:25, UpdateEmployeeService.java:36]
- [x] [Review][Defer] `SessionTokenIssuer.onboardingCompleted` swallows a SQL error inside the caller's transaction; on PostgreSQL that aborts the transaction [SessionTokenIssuer.java:96] — deferred: unverified (medium if true); settle by checking whether any tenant schema can lack `tenant_preferences` at login/refresh time, or with a Testcontainers test
- [x] [Review][Defer] CorrectSaleService can rewrite a sale cancelled concurrently back to COMPLETED [CorrectSaleService.java:115] — deferred: pre-existing race
- [x] [Review][Defer] Offline sale stock movements still get server ids, so the pull adds a second SALE movement next to the device's [RecordSaleService.java:89] — deferred: pre-existing, needs the app to send movement ids in CREATE_SALE (cross-lot)
- [x] [Review][Defer] Weekly report with the default Sunday 20:00 trigger misses Sunday 20:00–23:59 sales [WeeklyReportScheduler.java:124] — deferred: pre-existing (same with the old `with(SUNDAY)`)
- [x] [Review][Defer] `refresh_tokens` now keeps every rotated row with no purge, and has no index on `user_id`/`replaced_by_id` [V8] — deferred: growth/perf, not a correctness bug
- [x] [Review][Defer] Keyset pull ordering `(cursor expr, CAST(id AS text))` has no supporting index [DeltaPageQuery] — deferred: unverified perf impact; settle with EXPLAIN on a large tenant

#### Resolution (2026-10-11)

Decisions taken by the owner: option 1 for both.

- **Refresh token** (`RefreshToken`, `RefreshTokenService`, `RefreshTokenSpringRepository`): the recovery window counts from the first exchange (`withSuccessor` keeps `rotatedAt`); a recovery supersedes the unused successor (`supersededAt`: revoked, no successor, `rotatedAt` set), so one token of the chain is alive; presenting a superseded token, or a token whose successor was used or superseded, revokes every session; a successor revoked outright (logout, password change) gives a plain REFRESH_TOKEN_INVALID; logout follows the rotation chain and revokes the live token; the hash lookup is row-locked so two parallel refreshes of one token are serialised.
- **Day closures** (`ClosureWindow`, `CloseDayService`, `EndOfDayReportBuilder`): a sale belongs to the first closure, in arrival order, that it happened before and reached the server before (`sales.created_at` is server-stamped). A closure covers `occurredAt <= C.closedAt AND createdAt <= C.receivedAt AND (occurredAt > P.closedAt OR createdAt > P.receivedAt)`; the 14 EOD report queries use the same predicate. A closure not after the store's last closure, or with an existing id (retried push), is DAY_ALREADY_CLOSED (DUPLICATE on sync); a closure more than 7 days old is VALIDATION_ERROR (REJECTED).
- **Pull cursor**: new `day_closures.received_at` (server time). Flyway V9 (public) + `TenantSchemaSyncService` (tenants): column added without default, backfilled from `closed_at`, indexed `(received_at, id)`. `DayClosureDeltaProvider` pages on it.
- Reset password revokes refresh tokens; REST `POST /day-closures` checks the employee's store; STOCK_NEGATIVE only when the operation decreased stock; `POST /auth/logout` always 204; `SaleJpaEntity` `@PreUpdate` always moves `updated_at`; unused `RefreshTokenRepository` removed from ReassignStoreService/UpdateEmployeeService.
- Tests: RefreshTokenServiceTest (+5), AuthControllerTest (+2, and the slice now starts: 3 missing `@MockBean`s), ResetPasswordServiceTest, CloseDayServiceTest (+3, window test rewritten), ClosureWindowTest (new), DayClosureControllerTest (+1), DeltaStockStrategyTest (+2), SaleJpaEntityUpdatedAtTest (new), NativeSqlPostgresIntegrationTest (new, Testcontainers PostgreSQL 16 on tmpfs: claim 1/1/0, sale and transfer compare-and-set, `findForClosure`, V9 column). `src/test/resources/docker-java.properties` pins Docker API 1.44 (Docker 29 refuses the Testcontainers 1.20 default).

Rejected (19):
- false — DayClosureSyncHandler entityId fallback = storeId: the app always sends `entityId: closure.id` and `id` in the payload.
- false — Quick-add stock-level id reused as movement id: fresh UUID v4, no local movement exists, no collision.
- false — Inventory clamp ignores negative stock: matches §5.4 "inventory validation floors at 0".
- false — Client id collisions overwrite movements/counts: retries are deduplicated by operationId; a closure retry returns DUPLICATE.
- false — PROMOTE_PRODUCT no longer hoisted before stock entry: the stock path has no DRAFT check.
- false — RegistrationService hard-codes "OWNER": registration always creates the owner.
- false — SaleSyncHandler rejects instead of rewriting the store: deliberate, documented, stricter than REST.
- low — PENDING_VALIDATION transitions without CAS: no client produces PENDING_VALIDATION sales.
- low — CloseDayService 10,000-sale page cap: unrealistic per window for this market.
- low — Pull cursor unsigned: a forged cursor only affects the requester's own pull.
- low — Claim placeholder status REJECTED on crash: placeholder is rolled back with the transaction.
- low — Second device's inventory count keeps the server row id: rare, fix adds complexity.
- low — Refresh no longer checks user existence: active membership check covers it.
- low — Employee lookup without status filter: one employee row per user per tenant.
- low — StockLevel non-negative invariant moved to the service: design preference.
- low — RecordSaleService duplicated recordOperation branch: cosmetic.
- low — Weekly report one-off transition gap at deploy: one report, once.
- low — Reset-code test checks the annotation only: implementation verified correct.
- spec edit — Record the story 3.5 AC6 deviation in story 3.5/PRD: fix edits other specs.

### Review Findings — lots 2–5: app (2026-10-11)

Diffs: lot 2 core+auth (6,786 lines), lot 3 POS/reports/dashboard (7,008), lot 4 catalog/inventory/audit (8,840), lot 5 other features (7,835). 4 layers per lot (16 reviewers), every finding verified in code.

Decisions:
- [x] [Review][Decision → option a] Previous account's unsynced operations on account switch — after a session expiry/revocation, a different account signing in calls clearAllData() and silently deletes the previous account's queued sales; the only warning is the Settings sign-out [session_claims_applier.dart:54-65] (L2-3, L2-23)
- [x] [Review][Decision → option a] Offline inventory validation duplicates ADJUSTMENT movements — local movements get random ids, VALIDATE_INVENTORY carries only sessionId, the server creates its own → shown twice after pull [validate_inventory_provider.dart:163] (L4-2)
- [x] [Review][Decision → option a] Gap report signature — WhatsApp/shared text now signed with the first name instead of the phone number (spec §2 says content sent outside is untouched) [inventory_gap_report_page.dart:247-260] (L4-3)
- [x] [Review][Decision → option a] Plan limit on store creation — plan sheet opens over the still-open form, then the form shows the same message inline [stores_list_page.dart:113-126] (L5-9)
- [x] [Review][Decision → option a] Release order — the app now sends closure id/closedAt, sale occurredAt and movement entityIds that only the new backend reads; app released first ⇒ duplicate closures locally (L3-24)

Patches:
- [x] [Review][Patch] HIGH /terms missing from router public paths: signed-out user without accepted terms loops /splash↔/terms, nobody new can register; plus a test on the real appRouter redirect [app_router.dart:173] (L2-19, L2-26e)
- [x] [Review][Patch] HIGH setup wizard reopens on every cold start for up to 60 days: registration token says onboardingCompleted=false, never renewed after the wizard, and the claim overwrites the local flag [startup_route.dart:61, shop_name_page.dart:58,103] (L2-28)
- [x] [Review][Patch] Data owner key never written for sessions upgraded while signed in → next different account inherits the data; account-bound prefs survive the switch [session_claims_applier.dart] (L2-4, L2-5)
- [x] [Review][Patch] Dates pulled before _toLocalIso stay one hour off (no migration, no cursor reset) [rest_sync_service.dart] (L2-10)
- [x] [Review][Patch] Unguarded applyClaims/storage errors: splash spins forever, login button spins forever, interceptor request never settles [app_router.dart _redirect, auth_provider.dart, auth_interceptor.dart:75-99] (L2-21, L2-22, L2-7)
- [x] [Review][Patch] Logout goes through the token-refreshing path: add /api/v1/auth/logout to the interceptor public paths [auth_interceptor.dart:64] (L2-2, L2-30)
- [x] [Review][Patch] Refresh: only 400/401/403 end the session; 408/429 are unreachable [session_refresher.dart:56] (L2-6)
- [x] [Review][Patch] FCM listeners flag set before setup succeeds [fcm_service.dart:63] (L2-13)
- [x] [Review][Patch] Forgot-password countdown can run twice as fast [forgot_password_page.dart:49-87] (L2-25)
- [x] [Review][Patch] Splash refusal clears only the two tokens; use clearSessionSecrets [startup_route.dart:44-49] (L2-8)
- [x] [Review][Patch] Tests: follow-up push after a cycle; pull protection for VALIDATE_SALE initialStockEntries/productIdRemappings [sync_trigger_single_flight_test, rest_sync_service_pull_test] (L2-26a,c)
- [x] [Review][Patch] Local closure totals never reconciled with the server (late offline sales): store the POST response summary, update totals on pull conflict [day_closure_repository_impl.dart, rest_sync_service.dart:865] (L3-1)
- [x] [Review][Patch] Online closure refused (409) is queued and shown as success [day_closure_repository_impl.dart:118] (L3-3)
- [x] [Review][Patch] Push triggered inside the draft-checkout transaction runs in drift's transaction zone [riverpod_sync_trigger_dispatcher.dart:21] (L3-12)
- [x] [Review][Patch] Draft-checkout rollback test cannot see a rollback; use real datasources [record_sale_notifier_test.dart] (L3-10)
- [x] [Review][Patch] Employee reports spin forever when the user id is missing [reports_page.dart:37] (L3-18)
- [x] [Review][Patch] Motivational messages start with emoji [motivational_message_service.dart] (L3-4)
- [x] [Review][Patch] Dead code: kLastClosureDate/kAutoClosureNotifiedDate, getSalesCountAfter (L3-5, L3-22)
- [x] [Review][Patch] Small: use-case discount clamp test, withAsOf test, weekly/yearly bucketing tests, sales-history page dedupe by id, day-summary banner colours, correction quantity digits-only and >0 (L3-7, L3-8, L3-9, L3-20, L3-23, L3-27)
- [x] [Review][Patch] Offline inventory validation sets stock to the counted value instead of the gap on top of current stock (backend rule) [validate_inventory_provider.dart:134-148] (L4-1)
- [x] [Review][Patch] A rejected count op sends every validation of the session offline forever; online path should push and re-check [validate_inventory_provider.dart:40-80] (L4-10, L4-11)
- [x] [Review][Patch] Movement dedup still deletes real unsynced movements (no variantId; 10→9, 9→10, 10→9) [local_stock_datasource.dart:166-190] (L4-8)
- [x] [Review][Patch] Product merge orphans quick-add inventory counts [rest_sync_service.dart _dropMergedLocalProduct] (L4-22)
- [x] [Review][Patch] Small: transfer history header while stores load, inventory row saved value only on success, search focus border, refresh handlers swallow errors, CSV preview column shift/duplicate headers, collapsed store cards reload, stock search query autoDispose, CSV retry test (L4-5, L4-6, L4-13, L4-14, L4-15, L4-18, L4-19, L4-24)
- [x] [Review][Patch] "Voir les désactivées" leaks deactivated stores into every store picker until restart [store_provider.dart:47-60] (L5-17)
- [x] [Review][Patch] Offline product detail: sparkline and top store ignore the store filter [local_profitability_datasource.dart:148-199] (L5-1, L5-2 test)
- [x] [Review][Patch] Sign-out: no progress, double tap runs two flushes; dialog untested [settings_page.dart:346-406] (L5-14)
- [x] [Review][Patch] Small: employee actions queue on 5xx, shared loading SoftIconButton, store type emoji, unused employee notifiers, phone validity after country change (L5-5, L5-7, L5-12, L5-16, L5-18)
- [x] [Review][Defer] Pending-sale validation flow (L3-11, L3-14, L3-19, L3-26, L3-28) — deferred: legacy path, no client creates PENDING_VALIDATION sales
- [x] [Review][Defer] Quick-add / product insert without transaction (L4-7), CSV mapping not cleared on cancel (L4-16), draft promotion stock input (L4-21), contact header buttons and search vs archived (L5-10, L5-11), custom date picker after New Year (L5-19), /auth/login redirect bypasses splash (L2-29), tenant picker / forced password change exits (L2-16), reset-password without phone (L2-15), auth pages French-only (L2-18) — deferred: pre-existing

Rejected (lots 2–5): L2-1, L2-9, L2-20, L2-24 false; L2-11, L2-12, L2-14, L2-17, L2-27 low/no fix needed; L3-2, L3-6, L3-13, L3-15, L3-16, L3-17, L3-21, L3-25; L4-4, L4-9, L4-12, L4-17, L4-20, L4-23; L5-3, L5-4, L5-6, L5-8, L5-10 (stores), L5-13, L5-15. Details in the session triage.

#### Resolution (2026-10-11)

Decisions: option (a) for all five.
- D1: `SessionClaimsApplier` raises `PreviousAccountDataException` (count of pending ops) instead of clearing; Login / SelectTenant / Registration show "N opérations de l'ancien compte seront perdues" (`previous_account_data_dialog.dart`): Continuer clears and signs in, Annuler deletes the new tokens and keeps the data.
- D2: `VALIDATE_INVENTORY` carries `movementIds` ("productId|variantId" → local movement id); backend `ValidateInventoryService` / `InventoryValidationSyncHandler` reuse them (invalid ids ignored, REST unchanged).
- D3: first-name signature kept (§5.4); `kUserFirstNameKey` moved to `core/storage/app_constants.dart`.
- D4: on PLAN_LIMIT_EXCEEDED the store form closes, then the plan-limit sheet opens.
- D5: §5.6 release checklist (backend before app).

All patches applied with tests (details per id in the session reports). Not done: L2-23 (wait for an in-flight sync before an account-switch clear; low, needs a public sync API); no dedicated tests for FCM init, the forgot-password timer, L4-5/L4-6/L4-18 and `SoftIconButton(loading)`.

Verification: app `flutter test` 1197 passed, 1 failed (`offline_gate_banner_test` amber colour, pre-existing); `flutter analyze` 0 errors, 9 pre-existing warnings in untouched files. Backend `mvn test` 1867 run, 7 failures + 29 errors in the same 10 pre-existing persistence/slice classes.
