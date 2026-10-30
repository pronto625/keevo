---
title: '15.1 — entitlementsProvider Flutter (active modules of the tenant)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '9f0f2f205a017aa1f070c4bb08262a4c0a6a25c5'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The app has no single source for "which modules does this tenant have". Today a 13.10b stopgap (`tenantModuleStatesProvider`) fetches `GET /api/v1/tenants/me/modules` for owners only, keeps nothing across restarts, and employees get an empty answer — so stories 15.2/15.3/15.8 have nothing to gate on, and offline the app forgets everything. Two routed items also depend on it: re-pulling a module's data when it is re-enabled, and the export dataset list showing premium datasets the tenant cannot use.

**Approach:** One `entitlementsProvider` (app core) holding module id → state (`ENABLED` / `READ_ONLY` / `DISABLED`) for every session role, seeded from the JWT `modules` claim when present (Epic 16.3, forward-compatible) and from the last persisted snapshot, then refreshed from `GET /api/v1/tenants/me/modules` after each token (backend = source of truth). The endpoint opens to every tenant role (dates owner-only); `GET /exports/datasets` lists only datasets whose module is readable.

## Boundaries & Constraints

**Always:**
- Backend stays the source of truth: an API answer always replaces claim/snapshot states.
- Offline / API failure: keep the last known state (persisted snapshot or claim); never wipe it on a network error.
- Snapshot is per local data owner (`kLocalDataOwnerKey`); sign-out and account switch clear it.
- `READ_ONLY` counts as readable (grace period), not as active for writes: expose both `isActive(id)` (ENABLED) and `isReadable(id)` (ENABLED or READ_ONLY).
- Nothing known yet (first launch offline, no claim) is a distinct `unknown` status, so callers keep their free/default path rather than treating the tenant as having zero modules.
- Employees read states (moduleId, displayName, state) but get `expiresAt`/`graceUntil` = null.
- French labels only in any UI touched; no module ids shown to users.
- Bump `keevo/app/pubspec.yaml` 2.0.7+79 → 2.0.8+80.

**Never:**
- No JWT `modules` claim emission on the backend (that is Story 16.3) — the app only reads it if present.
- No route/nav gating, no ExtensionSlot, no « Mes modules » screen (15.2/15.3/15.6/15.8).
- No Riverpod 3 migration (15.4b): follow the Riverpod style already in the codebase.
- No local schema change (Drift).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Login online | token (no `modules` claim), API answers `invoicing: ENABLED, multi-store: DISABLED` | provider = known states from API; snapshot persisted | — |
| Claim present | token `modules: ["invoicing"]`, API not yet answered | provider seeds `invoicing: ENABLED` (source claim); API answer replaces it | — |
| Restart offline | snapshot persisted, API unreachable | provider = snapshot states | failure not cached; retried on next token / refresh |
| First launch offline | no snapshot, no claim, API fails | status `unknown`; `isReadable` false for all | retried later |
| Module disabled server-side | token renewed, API now says `multi-store: DISABLED` | module no longer readable | — |
| Module re-enabled | previous snapshot `inventory-assisted: DISABLED` (or absent), API now ENABLED/READ_ONLY | pull cursor reset → next sync is a full pull | no reset when there was no previous snapshot |
| Employee session | employee token | states returned by API (200), dates null | — |
| Export datasets | tenant without `multi-store` | `GET /exports/datasets` omits datasets whose `moduleId` is not readable | — |

</intent-contract>

## Code Map

- `keevo/app/lib/features/settings/presentation/provider/tenant_modules_provider.dart` -- 13.10b stopgap (`TenantModuleStates`, `tenantModuleStatesProvider`, `isMultiStoreOverviewAvailable`, `kMultiStoreModuleId`). Replace by the new core provider; keep `isMultiStoreOverviewAvailable` semantics (3 s timeout → false; failure → retry next time) on top of it.
- Users to migrate: `features/inventory/presentation/page/local_low_stock_page.dart:40`, `features/settings/presentation/page/subscription_page.dart:25` (invalidates after purchase), `features/auth/presentation/provider/sign_out_service.dart:113`, `features/auth/presentation/provider/session_claims_applier.dart:91` (invalidates after every token — keep that hook, it is how "token refreshed → module disappears" happens).
- Tests overriding the old provider: `test/features/settings/presentation/provider/tenant_modules_provider_test.dart`, `.../page/subscription_page_test.dart:87`, `test/features/inventory/presentation/page/local_low_stock_page_test.dart:62`, `test/features/dashboard/presentation/page/dashboard_page_test.dart:499`, `test/features/catalog/presentation/page/catalog_page_test.dart:482`.
- `keevo/app/lib/core/auth/jwt_claims.dart` -- add a `modules` getter (List<String>? from claim `modules`, null when absent/malformed).
- `keevo/app/lib/core/storage/app_constants.dart` -- `kLastSyncTimestampKey` (pull cursor in secure storage, line 54), `kLocalDataOwnerKey`; add the snapshot pref key here. `SessionClaimsApplier._isolateFromPreviousAccount` already deletes the cursor for a full pull — same mechanism for the re-enable reset.
- `keevo/app/lib/core/di/providers.dart` -- `dioProvider`, `sharedPreferencesProvider`; `isOwnerSessionProvider` lives in `features/auth/presentation/provider/auth_provider.dart`.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java:49` -- `CurrentActor.requireOwner` to drop; null dates for non-owners (`CurrentActor` has role helpers).
- `keevo/backend/src/main/java/com/keevo/reporting/export/adapter/in/rest/ExportController.java:94` -- `datasets()`; filter with `EntitlementService.isEnabled(schema, provider.moduleId())` (same check as `ExportService.requireDatasetModules:263`).
- `docs/entitlements/endpoints.md` -- generated; regenerate if the generator test flags a diff.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/.../TenantModulesController.java` -- allow every authenticated tenant role; owner gets dates, others null; update javadoc -- employees must gate their UI too.
- `keevo/backend/.../ExportController.java` -- filter `datasets()` to readable modules -- routed item 13-10a.
- backend tests (`TenantModulesController` + `ExportController` existing test classes, or new ones next to them) -- employee 200 with null dates; datasets list omits a DISABLED module's dataset and keeps it when READ_ONLY.
- `keevo/app/lib/core/entitlements/entitlements.dart` (new) -- immutable `Entitlements` (states map, status `unknown|claim|cached|server`, `fetchedAt`, `isActive`, `isReadable`) + JSON (de)serialisation for the snapshot.
- `keevo/app/lib/core/entitlements/entitlements_provider.dart` (new) -- `entitlementsProvider` (async notifier style used in the codebase): seed from claim/snapshot synchronously-available state, refresh from API, persist, reset pull cursor on re-enable, `refresh()` method; failures keep the previous state.
- `keevo/app/lib/core/auth/jwt_claims.dart` -- `modules` getter.
- old `tenant_modules_provider.dart` + its users and tests -- migrate to the new provider (delete the stopgap provider; keep `isMultiStoreOverviewAvailable`/`kMultiStoreModuleId` where it fits).
- `session_claims_applier.dart` / `sign_out_service.dart` -- refresh after every token; clear snapshot on sign-out and account switch.
- `keevo/app/test/core/entitlements/` (new) -- unit tests for every I/O matrix row (fake Dio, in-memory prefs/secure storage).
- `keevo/app/pubspec.yaml` -- 2.0.8+80.

**Acceptance Criteria:**
- Given a login, when the token is received, then `entitlementsProvider` exposes the tenant's module states and a `GET /api/v1/tenants/me/modules` answer confirms/refreshes them.
- Given a module disabled on the backend, when the token is renewed, then the module is no longer readable in `entitlementsProvider`.
- Given the app restarted without network, when the provider is read, then it returns the last persisted states.
- Given an employee session, when the provider loads, then it holds the tenant's module states (not empty).
- Given all touched backend packages and Flutter folders, when tests run package by package, then all pass.

## Spec Change Log

### 2026-10-31 — review pass 1 (cursor reset robustness)
- Trigger: review findings (blind, edge, verification-gap) — the direct delete of `kLastSyncTimestampKey` from the entitlements provider (a) is overwritten by a pull already running (`RestSyncService._pull` writes the cursor at its end), (b) loses its baseline when sign-out clears the snapshot (same account signs back in), (c) never fires on devices upgrading from 2.0.7 (no snapshot yet).
- Amended: Design Notes "Re-enable reset" — marker consumed by the sync service instead of a direct cursor delete; sign-out keeps the owner-tagged snapshot; no snapshot + existing cursor ⇒ one full pull.
- Known-bad state avoided: re-enabled module rows silently never pulled.
- KEEP: Entitlements model/status/serialisation, claim+snapshot seeding, generation guard, backend controller + export filter + their tests, migrated tests, fake notifier, pubspec 2.0.8+80. Re-derivation is incremental on the existing diff (no revert): only the reset mechanism, its tests and the sync-service hook change.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 36 findings — high 0, medium 5, low 17, false 14, maybe-false 0
- findings:
  - `[medium]` `[bad_spec]` (blind) sign-out clears the snapshot → same account re-login never resets the cursor — amended Design Notes (sign-out keeps owner-tagged snapshot).
  - `[medium]` `[bad_spec]` (blind) upgrade from 2.0.7 has no snapshot → no reset — amended (no snapshot + cursor ⇒ full pull once).
  - `[medium]` `[bad_spec]` (blind) running pull rewrites the deleted cursor — amended (marker consumed by `_pull`).
  - `[false]` `[reject]` (blind) claim seeds READ_ONLY modules as ENABLED — 16.3 defines the claim as TRIAL/ACTIVE modules only; READ_ONLY never appears in it.
  - `[low]` `[reject]` (blind) snapshot `fetchedAt` (device clock) vs `iat` (server clock) — claim not emitted until 16.3; the server answer replaces the seed seconds later.
  - `[medium]` `[patch]` (blind) `refresh()` never joins the in-flight fetch → duplicate GETs and out-of-order answers — refresh joins `_inFlight` when one is running; answers older than the latest request dropped.
  - `[low]` `[reject]` (blind) equality includes `fetchedAt` → extra rebuilds — one rebuild per token renewal, negligible.
  - `[low]` `[reject]` (blind) token read twice in `build()` — two secure-storage reads per token, negligible.
  - `[false]` `[reject]` (blind) offline overview never available — the multi-store overview is a server page; offline it cannot load, the free local list is the right destination (unchanged from 13.10b).
  - `[low]` `[patch]` (blind) dead `on TimeoutException` branch — removed.
  - `[low]` `[reject]` (blind) helper lives in the low-stock page file — single owner of that navigation, 15.8 will move gating helpers.
  - `[false]` `[reject]` (blind) datasets filter misses core/null moduleId tests — every provider returns a non-null module id; `core` goes through the same `isEnabled` as the export request (`ExportService.requireDatasetModules`), so list and request agree.
  - `[low]` `[reject]` (blind) N `isEnabled` lookups per listing — ~15 datasets, cached entitlement reads.
  - `[low]` `[patch]` (blind) no test that the owner still gets dates — owner-dates assertion added.
  - `[low]` `[reject]` (blind) inline role check instead of a shared helper — only OWNER/EMPLOYEE tenant roles exist.
  - `[low]` `[patch]` (blind) applier test does not cover renewal / account-switch ordering — renewal test added (same as verification-gap gap).
  - `[low]` `[reject]` (blind) formatting-only line in `local_low_stock_page.dart` — dart format output.
  - `[low]` `[reject]` (blind) `refresh()` swallows errors — documented contract: known states kept, retried next token.
  - `[low]` `[patch]` (verification-gap) renewal re-fetch untested at the applier — test: two `apply` calls → two fetches / new state wins.
  - `[medium]` `[bad_spec]` (verification-gap other) running pull overwrites the reset — same root cause as blind #3.
  - `[medium]` `[patch]` (edge) refresh while fetch in flight — same root cause as blind #6.
  - `[medium]` `[patch]` (edge) subscription page double GET — same root cause as blind #6.
  - `[medium]` `[bad_spec]` (edge) no previous snapshot after sign-out — same root cause as blind #1.
  - `[low]` `[reject]` (edge) `ensureServerState` may return the previous generation's answer — one tap at worst, next tap asks again.
  - `[low]` `[patch]` (edge) `ensureServerState`/`refresh` with no token send an unauthenticated GET — guarded with `hasSession()`.
  - `[low]` `[reject]` (edge) clock skew snapshot vs claim — same as blind #5.
  - `[false]` `[reject]` (edge) claim READ_ONLY → ENABLED — same as blind #4.
  - `[false]` `[reject]` (edge) `iat` decoded as double — `jsonDecode` yields `int` for integral JSON numbers; jjwt emits integral seconds.
  - `[low]` `[patch]` (edge) `onServerAnswer` failure discards the server answer — state is set even when persisting fails.
  - `[false]` `[reject]` (edge) older backend 403 for employees — backend and app ship together on refonte; a 403 is caught and keeps the seed.
  - `[medium]` `[bad_spec]` (edge claim) "reset before next sync" not guaranteed — resolved by the marker design.
  - `[false]` `[reject]` (edge claim) last-arriving answer wins — covered by the refresh patch (sequence guard).
  - `[false]` `[reject]` (intent) claim path inactive in prod — explicit spec boundary: claim emission is 16.3.
  - `[false]` `[reject]` (intent) offline snapshot unsigned, no 7-day bound — the 7-day offline gate already bounds offline use of the whole app; the snapshot only mirrors server answers.
  - `[false]` `[reject]` (intent) full pull instead of per-module re-pull — the pull cursor is global; a per-module cursor is a sync redesign, the full pull is the defensible reading.
  - `[false]` `[reject]` (intent) export filter done on the backend — the item names the unfiltered endpoint; the app consumes it.

## Design Notes

Re-enable reset: compare the newly fetched readable set with the previous persisted snapshot of the same owner. Any module that becomes readable — or no previous snapshot while a pull cursor exists (upgrade from 2.0.7, first answer) — raises a "full pull requested" marker (a SharedPreferences value, e.g. a counter/timestamp under a new key in `app_constants.dart`). `RestSyncService._pull` reads the marker at its start: when set it ignores the cursor (full pull, same path as the existing stale-cursor guard at `rest_sync_service.dart:~985`); after the pull wrote its new cursor it clears the marker only if it still holds the value read at start. A pull already running when the marker is raised therefore leaves it in place for the next pull. Sign-out keeps the owner-tagged snapshot so the same account signing back in still has its baseline; an account switch clears it. Local pending operations are untouched (13.10a skips DISABLED providers but advances the cursor, hence the full pull).

## Verification

**Commands:**
- `bash /tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/b3540a1e-019a-42f0-9dea-6ba6965d948e/scratchpad/runpkg.sh shared` then `reporting`, `architecture` -- expected: 0 failures each.
- `cd keevo/app && flutter analyze lib test/core test/features/settings test/features/auth test/features/inventory` -- expected: no new issues.
- `cd keevo/app && flutter test test/core/entitlements` then each folder whose test overrides the old provider -- expected: all pass.

## Auto Run Result

Status: done

**Summary:** One `entitlementsProvider` (app core) gives every session role the tenant's module states: seeded from the JWT `modules` claim (read only; emitted from 16.3) or this account's persisted snapshot, then replaced by `GET /api/v1/tenants/me/modules` after every token. Offline keeps the last known state. A module that becomes readable again (or the first answer after upgrading from 2.0.7) raises a "full pull requested" marker consumed by the sync pull. Backend: the modules endpoint opens to employees (dates null for them); `GET /exports/datasets` lists only datasets whose module is readable.

**Files changed:**
- `keevo/app/lib/core/entitlements/entitlements.dart` (new) — immutable model, status, snapshot JSON, API parser.
- `keevo/app/lib/core/entitlements/entitlements_provider.dart` (new) — AsyncNotifier: seed, server confirmation, generation + request-sequence guards, snapshot, full-pull marker.
- `keevo/app/lib/core/sync/rest_sync_service.dart` — `_pull` consumes the marker (full pull, cleared only if unchanged).
- `keevo/app/lib/core/auth/jwt_claims.dart` — `modules`, `issuedAt`.
- `keevo/app/lib/core/storage/app_constants.dart` — snapshot + marker keys.
- `session_claims_applier.dart` / `sign_out_service.dart` — re-seed on every token; snapshot kept at sign-out, cleared on account switch.
- `local_low_stock_page.dart`, `subscription_page.dart` — migrated off the deleted 13.10b `tenant_modules_provider.dart`.
- Backend `TenantModulesController`, `ExportController` + tests.
- Tests: `test/core/entitlements/` (26), pull marker group in `rest_sync_service_pull_test.dart`, applier renewal test, migrated page tests, `test/helpers/fake_entitlements.dart`.
- `keevo/app/pubspec.yaml` 2.0.8+80.

**Review:** 36 findings — 5 medium (3 root causes): cursor-reset robustness → spec amended + incremental re-derivation (marker design), refresh/in-flight join → patched; lows patched: dead branch, signed-out GET guard, persist failure keeps answer, applier renewal test, owner dates test. 14 false and the remaining lows rejected with reasons in the triage log. Nothing deferred.

**Follow-up review recommended:** true — two medium entries patched; unverified risk: the full-pull marker interplay with real sync cycles on a device (marker raised while a pull/push runs, app killed mid-pull).

**Verification:** backend shared 1046 / reporting 404 / architecture 32 — 0 failures. Flutter (one folder at a time): core/entitlements 26, core/sync 347, core/auth 15, settings 69, auth 113, inventory 241, catalog 194, dashboard 143, export 141 — all pass. `flutter analyze lib`: info-level only (426 → 421). Live (dev, kv_zggt7g): owner and new test employee « Testeur Modules » get 200 on `/tenants/me/modules` (employee dates null), employee 403 on `/exports/datasets`; a temporary DISABLED `multi-store` row hides `stock_transfers` (16 → 15 datasets) and the employee sees `DISABLED`; row removed afterwards (tenant_modules back to 331). Device test not possible: the adb device is `unauthorized`.

**Residual risks:** the claim path is inactive until 16.3; employees with `multi-store` readable now open the multi-store overview (backend already serves employees there); dev data now has employee « Testeur Modules » (+237677151501).
