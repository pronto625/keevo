# Story 5.4: Limite 7 Jours Hors-ligne & Gate de Déverrouillage

Status: done

## Story

As the system,
I want to suspend write access after 7 days without synchronization and require a sync to unlock,
so that data integrity is maintained and the server always has an accurate recent state of every active tenant.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Building on Epic 5 Foundation

> **Story 5.1 (DONE) established the push pipeline: offline queue → batch POST /api/v1/sync/push**
> **Story 5.2 (DONE) established the pull pipeline: GET /api/v1/sync/pull → delta merge**
> **Story 5.3 (DONE) established conflict resolution: DELTA_SUM + LWW + AppendOnly strategies**
> **Story 5.4 (THIS STORY) adds the temporal access gate: 7-day offline limit + write blocking + sync unlock**

### Current State After 5.1–5.3

```
┌──────────────────────────────────────────────────────────────────────
│ FLUTTER SYNC ENGINE (fully operational after 5.1–5.3)              │
│                                                                      │
│  SyncStatus enum: online | syncing | offlineOk | offlineCritical   │
│  syncStatusProvider: StreamProvider<SyncStatus>                     │
│    → derived from connectivityStreamProvider + firstOfflineDateProvider │
│  daysOfflineProvider: Provider<int>                                  │
│    → days since device went OFFLINE (kFirstOfflineDateKey in SharedPrefs) │
│  kLastSyncTimestampKey: in SecureStorage                             │
│    → pull cursor (since= parameter for next pull), written after pull │
│  SyncTriggerNotifier: handles connectivity-restore + 30s push + 5min │
│  RestSyncService: push() / pull() / queueOperation()               │
└──────────────────────────────────────────────────────────────────────
```

### Critical Design Decision — Two Separate Time Measures

```
daysOfflineProvider   → "How long has the DEVICE Been disconnected?"
                        Source: kFirstOfflineDateKey (SharedPreferences)
                        Cleared when: device comes back online
                        Used by: existing SyncStatus display

daysSinceLastSyncProvider → "How long since the LAST SUCCESSFUL SYNC?"
                        Source: kLastSyncAtKey (SharedPreferences) — NEW key in this story
                        Written: in RestSyncService.pull() on success (alongside existing SecureStorage write)
                        Never cleared automatically — only reset by successful sync
                        Used by: THIS story's write gate (SyncGateState)
```

The gate fires based on `daysSinceLastSync`, NOT `daysOffline`. You can be:
- Online for 3 days but last sync was 6 days ago → gate warns (bad network during sync attempts)
- Offline for 2 days but last sync was 3 days ago → gate at day 3, warns at day 5

### What Story 5.4 Adds

```
┌──────────────────────────────────────────────────────────────────────
│ SYNC GATE LAYER (Story 5.4)                                         │
│                                                                      │
│ FLUTTER:                                                            │
│   kLastSyncAtKey: new SharedPreferences key (written after pull)    │
│   daysSinceLastSyncProvider: Provider<int> — reads kLastSyncAtKey   │
│   SyncGateState enum: open / warning / critical / blocked          │
│   syncGateStateProvider: Provider<SyncGateState> — derived          │
│   Banners: Day 5 yellow + Day 6 red in MainShellPage scaffold       │
│   SyncRequiredModal: blocking dialog on write attempt (Day 7+)      │
│   SyncIndicator: updated labels for blocked/warning/critical states │
│   30-min foreground check: timer in SyncTriggerNotifier             │
│   Auto-sync on reconnect already works (SyncTriggerNotifier 5.1)    │
│                                                                      │
│ BACKEND:                                                            │
│   user_sync_state table (PUBLIC schema): tracks per-device lastPushAt│
│   SyncController.push(): 7-day stale check → HTTP 423 SYNC_REQUIRED │
│   SyncPushService: update user_sync_state after each successful push │
│   UserSyncStateRepository port (domain) + adapter (JPA, public schema)│
└──────────────────────────────────────────────────────────────────────
```

### SyncGateState Transitions

```
daysSinceLastSync    SyncGateState    User Experience
─────────────────────────────────────────────────────
0–4 days             open             Normal operation. No restrictions.
5 days               warning          🟡 Persistent banner: "⚠ Synchronisation requise dans 2 jours"
6 days               critical         🔴 Persistent banner: "🔴 Dernière chance : synchronisez demain..."
≥ 7 days             blocked          🔴 "Accès limité — Sync requise" + blocking modal on any write
```

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Four distinct gate states with different UI rendering (no-op, yellow banner, red banner, blocking modal) and behavioral effects (writes either permitted or blocked). The gate check logic is the same but the response to it varies. |
| What might change in the future? | Gate threshold (7 days could become configurable). Gate scope (read operations might eventually be restricted too — Story 5.5). Bypass mechanism for super-admin or specific user roles. Different per-plan limits (FREE = 7 days, PREMIUM = 14 days). |
| Which GoF pattern(s) apply? | **State** — `SyncGateState` enum with `SyncGateNotifier` controlling transitions. Each state maps to distinct UI behavior. **Observer** — Riverpod `syncGateStateProvider` watched by SyncIndicator, MainShellPage banners, and write-triggering notifiers. **Template Method** — all write notifiers use a common `_checkWriteGate(ref)` helper before calling repos. |
| How does it enable Open/Closed principle? | Adding a new gate state (e.g., permanently-blocked for subscription expiry) = add enum value + handle in `SyncGateNotifier`. Zero changes to individual write notifiers or repositories. |
| Where is the pattern applied? | **State** → `SyncGateState` (open/warning/critical/blocked) in `lib/core/sync/sync_gate_state.dart`. **Observer** → `syncGateStateProvider` (Riverpod Provider) in `lib/core/sync/sync_gate_provider.dart` watched by presentation layer. **Template Method** → `SyncGateGuard.assertWriteAllowed(ref)` static helper called by write notifiers before repo calls. |

---

## Acceptance Criteria

### AC1 — SyncIndicator: labels based on daysSinceLastSync (Flutter)

- **Given** the app tracks `daysSinceLastSync` (days since `kLastSyncAtKey` timestamp)
- **When** the `syncGateStateProvider` is computed
- **Then** the `SyncIndicator` updates its display:
  - `SyncGateState.open` (days 0–4): existing behavior → SyncStatus-based labels (no change)
  - `SyncGateState.warning` (day 5): SyncIndicator still shows "Hors-ligne — Jour 5/7" (existing style) when offline, OR "En ligne (sync J-2)" when online + `syncStatusProvider == online`
  - `SyncGateState.critical` (day 6): SyncIndicator shows "Hors-ligne critique — Jour 6/7" when offline, OR "⚠ Sync urgente" when online
  - `SyncGateState.blocked` (day ≥ 7): SyncIndicator ALWAYS shows "🔴 Accès limité — Sync requise" regardless of connectivity
- **And** the SyncIndicator dot color for blocked state: `#FA5252` (red, same as offlineCritical)
- **And** in blocked state, tapping the SyncIndicator opens the `SyncRequiredModal` directly (not the normal bottom sheet)

### AC2 — Persistent banners at Day 5 and Day 6 (Flutter)

- **Given** the `syncGateStateProvider` has been computed at app startup and refreshed every 30 minutes
- **When** `syncGateState == SyncGateState.warning` (day 5)
- **Then** a persistent amber banner appears BELOW the AppBar (inside the main scaffold body, above page content):
  ```
  ┌─────────────────────────────────────────────────────────┐
  │ ⚠  Synchronisation requise dans 2 jours          [×]   │
  └─────────────────────────────────────────────────────────┘
  ```
  - Background: `Color(0xFFFFF3BF)` (amber 50 — very light yellow, not aggressive)
  - Icon: `⚠` amber (#FCC419)
  - Text: "Synchronisation requise dans 2 jours" (`bodySmall`)
  - Dismiss: [×] button persists dismissal in SharedPreferences per-day (auto-re-shows next day)
  - The banner does NOT auto-dismiss — it stays until connectivity syncs OR user dismisses
- **When** `syncGateState == SyncGateState.critical` (day 6)
- **Then** the amber banner is REPLACED by a red persistent banner:
  ```
  ┌─────────────────────────────────────────────────────────┐
  │ 🔴 Dernière chance : synchronisez demain ou         [×] │
  │    certaines actions seront bloquées                    │
  └─────────────────────────────────────────────────────────┘
  ```
  - Background: `Color(0xFFFFE3E3)` (red 50 — light red)
  - Icon: `●` red (#FA5252)
  - Text: "Dernière chance : synchronisez demain ou certaines actions seront bloquées"
  - Dismiss: NOT dismissable at day 6 (critical — user must acknowledge)
- **When** `syncGateState == SyncGateState.blocked` (day ≥ 7)
- **Then** banners are no longer shown (replaced by the blocking modal mechanism — see AC3)

### AC3 — Blocking modal on write attempt at Day 7+ (Flutter)

- **Given** `syncGateState == SyncGateState.blocked`
- **When** the user attempts any write operation:
  - Record a sale (POS checkout)
  - Adjust stock (stock entry / adjustment)
  - Execute a transfer (stock transfer)
  - Create/update/archive a product
- **Then** the Drift write is NOT performed (the repository call is never made)
- **And** the `SyncRequiredModal` appears as a full-screen `showDialog` (barrier dismissible: false):
  ```
  ┌─────────────────────────────────────────────────────────┐
  │                   ⏸ Synchronisation requise             │
  │                                                         │
  │  Vous n'avez pas synchronisé depuis 7 jours.            │
  │  Connectez-vous à internet pour synchroniser            │
  │  et retrouver l'accès complet.                          │
  │                                                         │
  │  [          Synchroniser maintenant          ]          │
  │                                                         │
  │  [    Continuer en lecture seule             ]          │
  └─────────────────────────────────────────────────────────┘
  ```
  - Primary button "Synchroniser maintenant": triggers push+pull cycle
  - Secondary text button "Continuer en lecture seule": dismisses modal (user can still view data)
  - READ operations (view stock, view history, view catalog) are NEVER blocked
- **And** if NO connectivity when modal opens: primary button shows "Hors-ligne — connexion requise" (disabled)
- **And** the modal is shown as a `WriteBlockedException` caught by the calling notifier

### AC4 — Sync progress while blocked + gate lift on success (Flutter)

- **Given** the user taps "Synchroniser maintenant" in the `SyncRequiredModal`
- **When** connectivity is available
- **Then** the modal transitions to a progress state:
  ```
  ┌─────────────────────────────────────────────────────────┐
  │                   🔄 Synchronisation en cours...        │
  │                                                         │
  │         ══════════════════════▰▰▰▰     (LinearProgress) │
  │                                                         │
  │  Envoi de vos données hors-ligne...                     │
  └─────────────────────────────────────────────────────────┘
  ```
- **When** sync push+pull completes successfully
- **Then** the modal is dismissed
- **And** `kLastSyncAtKey` is updated with `DateTime.now()` (via `daysSinceLastSyncProvider` reset)
- **And** `syncGateStateProvider` transitions to `SyncGateState.open`
- **And** a SnackBar appears: "✅ Synchronisation réussie — accès complet restauré" (green, 4s)
- **And** Riverpod providers are invalidated so UI refreshes with pulled data
- **When** sync fails after retries (network error)
- **Then** the modal returns to the error state:
  ```
  ┌─────────────────────────────────────────────────────────┐
  │                   ⏸ Synchronisation requise             │
  │                                                         │
  │  Impossible de synchroniser. Vérifiez votre             │
  │  connexion internet et réessayez.                       │
  │                                                         │
  │  [  Réessayer  ]   [  Continuer en lecture seule  ]     │
  └─────────────────────────────────────────────────────────┘
  ```
- **And** "Réessayer" bypasses exponential backoff (immediate retry — user-initiated)

### AC5 — lastSyncAt persistence in SharedPreferences (Flutter)

- **Given** a successful pull sync completes in `RestSyncService.pull()`
- **When** the server timestamp is written to SecureStorage (existing `kLastSyncTimestampKey`)
- **Then** the same timestamp is ALSO written to SharedPreferences under `kLastSyncAtKey = 'last_sync_at_ms'`
- **And** `daysSinceLastSyncProvider` reads this SharedPreferences key synchronously (no async)
- **And** if `kLastSyncAtKey` is null (first install / never synced): `daysSinceLastSync = 0` (no restriction on first install)
- **And** the key survives app relaunch and Drift database reset (SharedPreferences is independent of Drift)
- **And** the check runs on every app open (provider is recomputed when sharedPreferencesProvider is consumed)
- **And** it also runs every 30 minutes: a `_gateCheckTimer` in `SyncTriggerNotifier` refreshes the computation

### AC6 — 30-minute foreground check (Flutter)

- **Given** the app is in the foreground and `syncGateState` is currently `open`
- **When** 30 minutes have elapsed since the last gate check
- **Then** `syncGateStateProvider` is recomputed by invalidating the underlying SharedPreferences read
- **And** if `daysSinceLastSync` has crossed any threshold (4→5, 5→6, 6→7), the banner/indicator updates immediately
- **And** the implementation uses a `Timer.periodic(Duration(minutes: 30), ...)` in `SyncTriggerNotifier.build()`
- **And** the timer is cancelled in `ref.onDispose()` (no memory leaks)

### AC7 — Auto-sync on connectivity restore lifts gate (Flutter)

- **Given** `syncGateState == SyncGateState.blocked`
- **When** connectivity is restored (existing `_onConnectivityRestored()` in `SyncTriggerNotifier`)
- **Then** the existing sync trigger fires automatically (push+pull cycle)
- **And** on SUCCESS: `kLastSyncAtKey` in SharedPreferences is updated → `daysSinceLastSync` resets to 0 → gate lifts
- **And** the `SyncRequiredModal` (if visible) auto-transitions to the progress state (AC4 flow)
- **And** the implementation: `SyncTriggerNotifier` listens to `syncGateStateProvider`; if gate is blocked when connectivity restores, the modal shows the progress indicator automatically via `ref.read(syncGateNotifier.notifier).markSyncing()`

### AC8 — Backend: user_sync_state table (Backend)

- **Given** the backend needs to track per-device sync state
- **When** the backend is initialized
- **Then** the `public.user_sync_state` table exists (PUBLIC schema — device state is global, not per-tenant):
  ```sql
  CREATE TABLE IF NOT EXISTS public.user_sync_state (
    device_id   VARCHAR(36)  NOT NULL,
    user_id     UUID         NOT NULL,
    tenant_id   VARCHAR(100),
    last_push_at TIMESTAMPTZ,
    last_pull_at TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id)
  );
  CREATE INDEX IF NOT EXISTS idx_user_sync_state_user ON public.user_sync_state(user_id);
  ```
- **And** this DDL is executed at application startup via a `@Component` implementing `ApplicationListener<ApplicationReadyEvent>` (NOT via TenantSchemaProvisioner — it's PUBLIC schema)
- **And** the table is NOT per-tenant (all devices tracked in one table for monitoring purposes)

### AC9 — Backend: update user_sync_state on push (Backend)

- **Given** a push batch is received by `SyncController.push()`
- **When** the push is successfully processed (at least partially applied — not a total failure)
- **Then** `user_sync_state` is upserted:
  ```sql
  INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at, updated_at)
  VALUES (?, ?, ?, NOW(), NOW())
  ON CONFLICT (device_id) DO UPDATE SET
    last_push_at = NOW(),
    updated_at = NOW()
  ```
- **And** this update happens AFTER the push batch processing (not blocking the operation processing)
- **And** the `deviceId` comes from the push request `SyncPushRequestDto.deviceId()`

### AC10 — Backend: HTTP 423 SYNC_REQUIRED on stale push (Backend)

- **Given** a push request arrives from a known device (`device_id` exists in `user_sync_state`)
- **When** `user_sync_state.last_push_at < NOW() - INTERVAL '7 days'`
- **Then** the controller returns HTTP 423 Locked:
  ```json
  {
    "success": false,
    "errorCode": "SYNC_REQUIRED",
    "domainCode": "SYNC_REQUIRED",
    "message": "Synchronisation requise",
    "details": {
      "daysSinceLastSync": 8,
      "lastPushAt": "2026-03-15T10:00:00Z"
    }
  }
  ```
- **And** the DomainCode `SYNC_REQUIRED` is added to the `DomainErrorCode` enum (or equivalent)
- **And** the check happens BEFORE any batch processing (fail early)
- **Note** If device is NOT found in `user_sync_state` (first ever push): allow the push — record will be created in AC9
- **And** the Flutter `RestSyncService._pushBatch()` handles HTTP 423 by throwing `SyncRequiredException` (caught by `SyncTriggerNotifier` and displayed in the `SyncRequiredModal`)

### AC11 — TDD: Backend unit + integration tests (Backend)

#### Unit Tests (JUnit 5)

- `UserSyncStateRepositoryAdapterTest.java`
  - `save_newDevice_insertsRecord()`
  - `save_existingDevice_updatesLastPushAt()`
  - `findByDeviceId_exists_returnsRecord()`
  - `findByDeviceId_notFound_returnsEmpty()`
- `SyncGateCheckServiceTest.java`
  - `isStale_deviceNotFound_returnsFalse()` — first push always allowed
  - `isStale_deviceLastPushWithin7Days_returnsFalse()`
  - `isStale_deviceLastPushExactly7DaysAgo_returnsFalse()` — boundary: 7 days ago is ok (>7 required)
  - `isStale_deviceLastPushOver7DaysAgo_returnsTrue()`
  - `isStale_lastPushAtNull_returnsFalse()` — null = never pushed→ allow
- `SyncControllerPushGateTest.java` (`@WebMvcTest`)
  - `push_newDevice_returns200Processing()`
  - `push_freshDevice_returns200Processing()`
  - `push_staleDevice7DaysPlus_returns423SyncRequired()`
  - `push_staleDevice_response_hasDaysSinceLastSync()`
  - `push_staleDevice_response_domainCode_isSyncRequired()`
  - `push_staleDevice_response_hasLastPushAt()`

#### cURL Integration Tests (`curl-tests-story-5-4.sh`)

```bash
# Self-contained iterative script
# Requires: backend on localhost:8080, psql access

# Test script structure — each step logs result and cumulative pass/fail counter

STEP 1  — Register fresh tenant, get tokens (full auth flow)
STEP 2  — POST /api/v1/sync/push with real payload → expect 200
STEP 3  — Verify user_sync_state record created (SELECT last_push_at)
STEP 4  — UPDATE public.user_sync_state SET last_push_at = NOW() - INTERVAL '8 days' WHERE device_id = ?
STEP 5  — POST /api/v1/sync/push again → expect 423 with SYNC_REQUIRED
STEP 6  — Verify 423 response body has daysSinceLastSync and lastPushAt fields
STEP 7  — UPDATE public.user_sync_state SET last_push_at = NOW() (reset to fresh)
STEP 8  — POST /api/v1/sync/push again → expect 200 (gate lifts after reset)
STEP 9  — GET /api/v1/sync/pull → expect 200 (pull NOT gated by 7-day check)
STEP 10 — Verify user_sync_state last_push_at was updated in STEP 8
# Expected: 10/10 PASS
```

### AC12 — TDD: Flutter unit + widget tests

#### Unit Tests (flutter_test)

- `sync_gate_state_test.dart`
  - `fromDaysSinceLastSync_0_returnsOpen()`
  - `fromDaysSinceLastSync_4_returnsOpen()`
  - `fromDaysSinceLastSync_5_returnsWarning()`
  - `fromDaysSinceLastSync_6_returnsCritical()`
  - `fromDaysSinceLastSync_7_returnsBlocked()`
  - `fromDaysSinceLastSync_100_returnsBlocked()`
- `days_since_last_sync_provider_test.dart`
  - `daysSinceLastSync_noKey_returns0()` — null key → 0 (first install)
  - `daysSinceLastSync_syncedToday_returns0()`
  - `daysSinceLastSync_syncedYesterday_returns1()`
  - `daysSinceLastSync_synced5DaysAgo_returns5()`
  - `daysSinceLastSync_synced7DaysAgo_returns7()`
- `sync_gate_state_provider_test.dart`
  - `syncGateState_0days_returnsOpen()`
  - `syncGateState_5days_returnsWarning()`
  - `syncGateState_6days_returnsCritical()`
  - `syncGateState_7days_returnsBlocked()`

#### Widget Tests (flutter_test)

- `sync_required_modal_test.dart`
  - `modal_offline_primaryButtonDisabled()`
  - `modal_online_primaryButtonEnabled()`
  - `modal_tapSynchroniserMaintenant_callsTriggerSync()`
  - `modal_tapContinuerLectureSeule_dismissesModal()`
  - `modal_syncing_showsProgressIndicator()`
  - `modal_syncError_showsRetryButton()`
  - `modal_syncSuccess_dismissesAndShowsSnackBar()`
- `offline_gate_banner_test.dart`
  - `banner_open_notShown()`
  - `banner_warning_amberBannerShown()`
  - `banner_critical_redBannerShown_notDismissable()`
  - `banner_blocked_noBannerShown()` — modal takes over
  - `banner_warning_dismiss_hiddenForToday()`
- `sync_indicator_gate_test.dart`
  - `indicator_blocked_showsAccesLimite()`
  - `indicator_blocked_tapOpensModal()`
  - `indicator_open_showsNormalStatus()`
- `sync_required_exception_test.dart`
  - `toString_containsDaysSinceLastSync()`
  - `isException_runtimeType_isException()`
  - `fieldsPreserved_daysSinceLastSync_andLastPushAt()`

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Task 1 — TDD RED: Tests backend domain + DDL (AC8, AC9, AC10)

- [x] **1.1** Créer `UserSyncState.java` — domain record
  ```java
  // Package: com/keevo/sync/sync/domain/model/
  // File: UserSyncState.java
  public record UserSyncState(
      String deviceId,
      UUID userId,
      String tenantId,
      Instant lastPushAt,
      Instant lastPullAt,
      Instant updatedAt
  ) {}
  ```

- [x] **1.2** Créer `UserSyncStateRepositoryAdapterTest.java` — RED
  ```java
  // Package: com/keevo/sync/sync/adapter/out/persistence/impl/
  // Tests: save_newDevice_insertsRecord, save_existingDevice_updatesLastPushAt,
  //        findByDeviceId_exists_returnsRecord, findByDeviceId_notFound_returnsEmpty
  ```

- [x] **1.3** Créer `SyncGateCheckServiceTest.java` — RED
  ```java
  // Package: com/keevo/sync/sync/application/service/
  // Tests: isStale_deviceNotFound_returnsFalse,
  //        isStale_deviceLastPushWithin7Days_returnsFalse,
  //        isStale_deviceLastPushExactly7DaysAgo_returnsFalse,
  //        isStale_deviceLastPushOver7DaysAgo_returnsTrue,
  //        isStale_lastPushAtNull_returnsFalse
  ```

- [x] **1.4** Créer `SyncControllerPushGateTest.java` — RED
  ```java
  // @WebMvcTest, mock SyncGateCheckService + UserSyncStateRepository
  // Tests: push_newDevice_returns200, push_freshDevice_returns200,
  //        push_staleDevice_returns423, push_staleDevice_hasDomainCode,
  //        push_staleDevice_hasDaysSinceLastSync, push_staleDevice_hasLastPushAt
  ```

---

### Task 2 — Implémentation backend: domain + ports (AC8)

- [x] **2.1** Créer `UserSyncState.java` record (domain model)
  - `com/keevo/sync/sync/domain/model/UserSyncState.java`

- [x] **2.2** Créer `UserSyncStateRepository.java` — port out
  ```java
  // com/keevo/sync/sync/domain/port/out/UserSyncStateRepository.java
  public interface UserSyncStateRepository {
      Optional<UserSyncState> findByDeviceId(String deviceId);
      void upsert(UserSyncState state);  // INSERT ... ON CONFLICT DO UPDATE
  }
  ```

- [x] **2.3** Créer `SyncGateCheckService.java` — application service
  ```java
  // com/keevo/sync/sync/application/service/SyncGateCheckService.java
  @Service
  public class SyncGateCheckService {
      private static final long GATE_THRESHOLD_DAYS = 7L;
      private final UserSyncStateRepository userSyncStateRepository;

      /**
       * Returns true if this device's last push is older than 7 days.
       * Returns false if device is unknown (first ever push — allowed).
       */
      public boolean isStalePush(String deviceId) {
          return userSyncStateRepository.findByDeviceId(deviceId)
              .map(s -> s.lastPushAt() != null &&
                        s.lastPushAt().isBefore(Instant.now().minus(GATE_THRESHOLD_DAYS, ChronoUnit.DAYS)))
              .orElse(false);  // unknown device → first push → allowed
      }
  }
  ```

---

### Task 3 — Implémentation backend: DDL initializer + JPA adapter (AC8, AC9)

- [x] **3.1** Créer `UserSyncStateDdlInitializer.java` — runs on startup
  ```java
  // com/keevo/sync/sync/adapter/out/persistence/UserSyncStateDdlInitializer.java
  @Component
  public class UserSyncStateDdlInitializer implements ApplicationListener<ApplicationReadyEvent> {
      private final JdbcTemplate jdbcTemplate;

      @Override
      public void onApplicationEvent(ApplicationReadyEvent event) {
          jdbcTemplate.execute("""
              CREATE TABLE IF NOT EXISTS public.user_sync_state (
                device_id   VARCHAR(36)  NOT NULL,
                user_id     UUID         NOT NULL,
                tenant_id   VARCHAR(100),
                last_push_at TIMESTAMPTZ,
                last_pull_at TIMESTAMPTZ,
                updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                PRIMARY KEY (device_id)
              )""");
          jdbcTemplate.execute(
              "CREATE INDEX IF NOT EXISTS idx_user_sync_state_user " +
              "ON public.user_sync_state(user_id)");
      }
  }
  ```

- [x] **3.2** Créer `UserSyncStateRepositoryAdapter.java` — JPA adapter
  ```java
  // com/keevo/sync/sync/adapter/out/persistence/impl/UserSyncStateRepositoryAdapter.java
  @Repository
  public class UserSyncStateRepositoryAdapter implements UserSyncStateRepository {
      private final JdbcTemplate jdbcTemplate;

      @Override
      public Optional<UserSyncState> findByDeviceId(String deviceId) {
          // SELECT * FROM public.user_sync_state WHERE device_id = ?
          // Return empty Optional if not found
      }

      @Override
      public void upsert(UserSyncState state) {
          jdbcTemplate.update("""
              INSERT INTO public.user_sync_state
                (device_id, user_id, tenant_id, last_push_at, updated_at)
              VALUES (?, ?, ?, NOW(), NOW())
              ON CONFLICT (device_id) DO UPDATE SET
                last_push_at = NOW(),
                updated_at = NOW()
              """, state.deviceId(), state.userId(), state.tenantId());
      }
  }
  ```
  > ⚠️ **IMPORTANT**: Use `JdbcTemplate` with explicit schema `public.user_sync_state` — NOT `EntityManager` (which would use TenantContext). This table is in PUBLIC schema and NOT tenant-isolated.

---

### Task 4 — Implémentation backend: SyncController gate (AC10)

- [x] **4.1** Modifier `SyncController.java` — add gate check in push()
  ```java
  // In SyncController.push(), BEFORE the SyncUseCase call:
  
  // 1. Extract deviceId from request
  String deviceId = request.deviceId();
  
  // 2. Gate check
  if (syncGateCheckService.isStalePush(deviceId)) {
      Optional<UserSyncState> state = userSyncStateRepository.findByDeviceId(deviceId);
      long daysSince = state.map(s -> ChronoUnit.DAYS.between(s.lastPushAt(), Instant.now()))
                           .orElse(0L);
      return ResponseEntity.status(HttpStatus.LOCKED)
          .body(ApiResponseWrapper.error(
              "SYNC_REQUIRED",
              "Synchronisation requise",
              Map.of(
                  "daysSinceLastSync", daysSince,
                  "lastPushAt", state.map(s -> s.lastPushAt().toString()).orElse(null)
              )
          ));
  }
  
  // 3. Process normally...
  SyncBatchResult result = syncUseCase.pushBatch(...);
  
  // 4. After success: update user_sync_state
  userSyncStateRepository.upsert(new UserSyncState(
      deviceId, actorId, tenantId, Instant.now(), null, Instant.now()));
  
  return ResponseEntity.ok(ApiResponseWrapper.ok(SyncPushResponseDto.from(result)));
  ```

- [x] **4.2** Ajouter `SYNC_REQUIRED` dans `DomainErrorCode.java` (ou son équivalent enum)
  ```java
  SYNC_REQUIRED("SYNC_REQUIRED", "Synchronisation requise — données trop anciennes"),
  ```

- [x] **4.3** Ajouter `ApiResponseWrapper.error(String code, String message, Map<String,Object> details)` si non-existant
  > Vérifier si la méthode existe déjà dans `ApiResponseWrapper`. Si oui, skip. Si non, ajouter l'overload.

---

### Task 5 — Implémentation backend: cURL tests (AC11)

- [x] **5.1** Créer `curl-tests-story-5-4.sh` dans `keevo/scripts/`
  ```bash
  #!/usr/bin/env bash
  # Story 5.4 — Gate 7 jours offline — Tests E2E cURL
  # Usage: ./curl-tests-story-5-4.sh
  # Requires: backend on :8080, psql on localhost:5444

  set -euo pipefail
  BASE="http://localhost:8080"
  PASS=0; FAIL=0

  step() { echo "STEP $1 — $2"; }
  ok()   { echo "  ✅ PASS: $1"; ((PASS++)); }
  fail() { echo "  ❌ FAIL: $1"; ((FAIL++)); }
  check_eq() { [ "$1" = "$2" ] && ok "$3" || fail "$3 (expected='$2', got='$1')"; }

  set -euo pipefail
  BASE="http://localhost:8080"
  PASS=0; FAIL=0
  step()        { echo; echo "─── STEP $1 — $2 ───"; }
  ok()          { echo "  ✅ PASS: $1"; ((PASS++)); }
  fail()        { echo "  ❌ FAIL: $1"; ((FAIL++)); }
  check_eq()    { [ "$1" = "$2" ] && ok "$3" || fail "$3 (expected='$2' got='$1')"; }
  check_has()   { echo "$1" | grep -q "$2" && ok "$3" || fail "$3 (missing '$2')"; }

  # ─── STEP 1: Register fresh tenant, obtain JWT ────────────────────────────────
  step 1 "Register fresh tenant and obtain JWT"
  PHONE="+237610$(date +%s | tail -c 6)"
  DEVICE_ID="test-device-$(date +%s)"
  REGISTER=$(curl -s -X POST "$BASE/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\",\"tenantName\":\"Gate Test Co\"}")
  TOKEN=$(echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['accessToken'])")
  [ -n "$TOKEN" ] && ok "Registration + JWT obtained" || { fail "Registration failed: $REGISTER"; exit 1; }

  # ─── STEP 2: First push — expect 200 ──────────────────────────────────────────
  step 2 "POST /api/v1/sync/push (fresh device) → expect 200"
  PUSH1=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
  check_eq "$PUSH1" "200" "First push returns 200"

  # ─── STEP 3: Verify user_sync_state record created ────────────────────────────
  step 3 "Verify public.user_sync_state record was created in DB"
  DB_COUNT=$(PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
    -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -t -c \
    "SELECT COUNT(*) FROM public.user_sync_state WHERE device_id = '$DEVICE_ID';" | tr -d ' ')
  check_eq "$DB_COUNT" "1" "user_sync_state record exists for test device"

  # ─── STEP 4: Simulate stale device (8 days old last_push_at) ─────────────────
  step 4 "Inject stale last_push_at = NOW() - INTERVAL '8 days'"
  PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
    -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -c \
    "UPDATE public.user_sync_state SET last_push_at = NOW() - INTERVAL '8 days' WHERE device_id = '$DEVICE_ID';" \
    > /dev/null
  ok "last_push_at set to 8 days ago"

  # ─── STEP 5: Push with stale device → expect 423 ──────────────────────────────
  step 5 "POST /api/v1/sync/push (stale 8-day-old device) → expect 423"
  PUSH2_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
  check_eq "$PUSH2_STATUS" "423" "Stale device push returns 423"

  # ─── STEP 6: Verify 423 response body structure ────────────────────────────────
  step 6 "Verify 423 response body: domainCode=SYNC_REQUIRED + detail fields"
  PUSH2_BODY=$(curl -s -X POST "$BASE/api/v1/sync/push" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
  check_has "$PUSH2_BODY" "SYNC_REQUIRED"      "Response has domainCode SYNC_REQUIRED"
  check_has "$PUSH2_BODY" "daysSinceLastSync"  "Response has daysSinceLastSync field"
  check_has "$PUSH2_BODY" "lastPushAt"         "Response has lastPushAt field"

  # ─── STEP 7: Reset last_push_at to NOW ────────────────────────────────────────
  step 7 "Reset last_push_at to NOW() (simulate successful sync)"
  PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
    -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -c \
    "UPDATE public.user_sync_state SET last_push_at = NOW() WHERE device_id = '$DEVICE_ID';" \
    > /dev/null
  ok "last_push_at reset to NOW"

  # ─── STEP 8: Push again after reset → expect 200 ──────────────────────────────
  step 8 "POST /api/v1/sync/push after reset → expect 200 (gate lifted)"
  PUSH3=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
  check_eq "$PUSH3" "200" "Push after reset returns 200 (gate lifted)"

  # ─── STEP 9: Pull is NOT gated — always 200 ───────────────────────────────────
  step 9 "GET /api/v1/sync/pull → always 200 (pull not subject to 7-day gate)"
  PULL=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/sync/pull" \
    -H "Authorization: Bearer $TOKEN")
  check_eq "$PULL" "200" "Pull returns 200 regardless of device sync age"

  # ─── STEP 10: Verify last_push_at was refreshed after STEP 8 ──────────────────
  step 10 "Verify last_push_at updated in DB after successful push (STEP 8)"
  SECS_AGO=$(PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
    -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -t -c \
    "SELECT EXTRACT(EPOCH FROM (NOW() - last_push_at))::int \
     FROM public.user_sync_state WHERE device_id = '$DEVICE_ID';" | tr -d ' ')
  [ "$SECS_AGO" -lt 60 ] \
    && ok "last_push_at refreshed recently (${SECS_AGO}s ago)" \
    || fail "last_push_at NOT refreshed — still ${SECS_AGO}s ago"

  # ─── RESULTS ──────────────────────────────────────────────────────────────────
  echo
  echo "════════════════════════════════════"
  echo "  RESULTS:  $PASS PASS  |  $FAIL FAIL"
  [ $FAIL -eq 0 ] && echo "  🎉 ALL 10 TESTS PASSED" || echo "  ⚠️  SOME TESTS FAILED"
  echo "════════════════════════════════════"
  ```

---

### Task 6 — TDD RED: Flutter provider tests (AC5, AC6, AC12)

- [x] **6.1** Créer `sync_gate_state_test.dart`
  ```dart
  // test/core/sync/sync_gate_state_test.dart
  // Tests from AC12 — all fromDaysSinceLastSync() cases
  ```

- [x] **6.2** Créer `days_since_last_sync_provider_test.dart`
  ```dart
  // test/core/sync/days_since_last_sync_provider_test.dart
  // Use ProviderContainer with overridden sharedPreferencesProvider
  // Tests: null key → 0, today → 0, yesterday → 1, 5 days → 5, 7 days → 7
  ```

- [x] **6.3** Créer `sync_gate_state_provider_test.dart`
  ```dart
  // test/core/sync/sync_gate_state_provider_test.dart
  // Tests: 0days→open, 5days→warning, 6days→critical, 7days→blocked
  ```

---

### Task 7 — TDD RED: Flutter widget tests (AC2, AC3, AC4, AC12)

- [x] **7.1** Créer `sync_required_modal_test.dart`
  ```dart
  // test/features/sync/presentation/widget/sync_required_modal_test.dart
  // Tests from AC12 (modal tests — 7 tests)
  ```

- [x] **7.2** Créer `offline_gate_banner_test.dart`
  ```dart
  // test/core/sync/offline_gate_banner_test.dart
  // Tests from AC12 (banner tests — 5 tests)
  ```

- [x] **7.3** Créer `sync_indicator_gate_test.dart`
  ```dart
  // test/features/sync_indicator/presentation/widget/sync_indicator_gate_test.dart
  // Tests: blocked indicator label, tap behavior, open state unchanged
  ```

---

### Task 8 — Implémentation Flutter: constants + provider foundation (AC5)

- [x] **8.1** Modifier `app_constants.dart` — add new SharedPreferences key
  ```dart
  // In lib/core/storage/app_constants.dart
  // After kLastSyncTimestampKey:

  /// Timestamp (milliseconds since epoch) of the last COMPLETED sync cycle (push+pull).
  /// Written by RestSyncService.pull() on success.
  /// Used by syncGateStateProvider to compute daysSinceLastSync.
  /// Stored in SharedPreferences (synchronous access for gate computation).
  /// Distinct from kLastSyncTimestampKey (pull cursor, in SecureStorage).
  const String kLastSyncAtKey = 'last_sync_at_ms';
  ```

- [x] **8.2** Modifier `rest_sync_service.dart` — write kLastSyncAtKey after successful pull
  ```dart
  // In RestSyncService.pull(), after the SecureStorage write:

  // Existing (Story 5.2 — keep unchanged):
  await _secureStorage.write(
    key: kLastSyncTimestampKey,
    value: serverInstant.millisecondsSinceEpoch.toString(),
  );

  // NEW (Story 5.4): also update SharedPreferences for synchronous gate check
  final prefs = ref.read(sharedPreferencesProvider);  // injected in constructor
  await prefs.setInt(kLastSyncAtKey, serverInstant.millisecondsSinceEpoch);
  ```
  > ⚠️ **DEPENDENCY**: `RestSyncService` needs `SharedPreferences` injected. If not already, add `final SharedPreferences _prefs` to the constructor and update the provider in `providers.dart`.

- [x] **8.3** Créer `sync_gate_state.dart`
  ```dart
  // lib/core/sync/sync_gate_state.dart

  /// SyncGateState — temporal state of the offline access gate.
  ///
  /// State pattern: each value maps to distinct UI behavior and write blocking.
  /// GoF State applied at the enum level: UI selects rendering strategy per state.
  enum SyncGateState {
    /// Days 0–4: normal operation. No restrictions, no banners.
    open,

    /// Day 5: yellow persistent banner. Writes still allowed.
    warning,

    /// Day 6: red persistent banner, not dismissable. Writes still allowed.
    critical,

    /// Days ≥ 7: writes BLOCKED. SyncRequiredModal on any write attempt.
    blocked;

    /// Derives SyncGateState from daysSinceLastSync.
    static SyncGateState fromDaysSinceLastSync(int days) {
      if (days < 5) return SyncGateState.open;
      if (days == 5) return SyncGateState.warning;
      if (days == 6) return SyncGateState.critical;
      return SyncGateState.blocked;
    }

    bool get isWriteBlocked => this == SyncGateState.blocked;
    bool get showsBanner => this == SyncGateState.warning || this == SyncGateState.critical;
  }
  ```

- [x] **8.4** Créer `sync_required_exception.dart` — exception Flutter pour le gate serveur (HTTP 423)
  ```dart
  // lib/core/sync/sync_required_exception.dart

  /// Thrown by [RestSyncService.push()] when the server returns HTTP 423 SYNC_REQUIRED.
  ///
  /// Caught by [SyncTriggerNotifier.triggerSync()] to:
  ///   1. ABORT the pull cycle (do NOT call pull after server 423)
  ///   2. Force kLastSyncAtKey to stale value so client gate stays blocked
  ///   3. Invalidate daysSinceLastSyncProvider for immediate UI recomputation
  ///
  /// Distinct from [WriteBlockedException] (client-side gate, thrown locally).
  class SyncRequiredException implements Exception {
    final int daysSinceLastSync;
    final String? lastPushAt;

    const SyncRequiredException({
      required this.daysSinceLastSync,
      this.lastPushAt,
    });

    @override
    String toString() =>
        'SyncRequiredException: $daysSinceLastSync days since last push (server 423 gate)';
  }
  ```

- [x] **8.5** Modifier `rest_sync_service.dart` — gérer HTTP 423 dans `push()` (AC10)
  ```dart
  // In RestSyncService.push() — inside the DioException catch, BEFORE rethrow:
  } on DioException catch (e) {
    if (e.response?.statusCode == 423) {
      // Server-side 7-day gate: parse daysSinceLastSync from 423 response body
      final details = (e.response?.data?['details'] as Map<String, dynamic>?);
      final days = (details?['daysSinceLastSync'] as num?)?.toInt() ?? 7;
      final lastPushAt = details?['lastPushAt'] as String?;
      throw SyncRequiredException(daysSinceLastSync: days, lastPushAt: lastPushAt);
    }
    rethrow;  // Other DioExceptions propagate normally
  }
  ```
  > ⚠️ **CRITICAL**: `SyncRequiredException` from `push()` must be caught in `SyncTriggerNotifier.triggerSync()` (Task 9.3) to ABORT the pull cycle. If `pull()` runs after a 423 `push()`, it writes `kLastSyncAtKey = now`, falsely reopening the client gate while the server still considers the device stale — causing an infinite push→423→pull→gate-open→write→push→423 loop.

---

### Task 9 — Implémentation Flutter: gate providers (AC5, AC6)

- [x] **9.1** Créer `sync_gate_provider.dart`
  ```dart
  // lib/core/sync/sync_gate_provider.dart

  /// Days since the last successful sync (push+pull completed).
  ///
  /// Reads kLastSyncAtKey from SharedPreferences synchronously.
  /// Returns 0 if never synced (first install — no restrictions).
  final daysSinceLastSyncProvider = Provider<int>((ref) {
    final prefs = ref.watch(sharedPreferencesProvider);
    final ms = prefs.getInt(kLastSyncAtKey);
    if (ms == null) return 0;  // never synced → open gate (first install)
    final lastSync = DateTime.fromMillisecondsSinceEpoch(ms);
    return DateTime.now().difference(lastSync).inDays;
  });

  /// Current gate state derived from daysSinceLastSync.
  ///
  /// Observer pattern: watched by SyncIndicator, MainShellPage banners,
  /// write notifiers (POS, stock, transfer, catalog).
  final syncGateStateProvider = Provider<SyncGateState>((ref) {
    final days = ref.watch(daysSinceLastSyncProvider);
    return SyncGateState.fromDaysSinceLastSync(days);
  });
  ```

- [x] **9.2** Modifier `sync_trigger_notifier.dart` — add 30-min gate check timer (AC6)
  ```dart
  // In SyncTriggerNotifier.build(), add after _periodicSyncTimer:

  // AC6: 30-min gate state refresh — updates banners/indicator if gate crossed a threshold.
  _gateCheckTimer = Timer.periodic(const Duration(minutes: 30), (_) {
    // Invalidating daysSinceLastSyncProvider forces re-reads from SharedPreferences
    ref.invalidate(daysSinceLastSyncProvider);
  });
  ```
  ```dart
  // In onDispose():
  _gateCheckTimer?.cancel();
  ```
  ```dart
  // Field declaration:
  Timer? _gateCheckTimer;
  ```

- [x] **9.3** Modifier `sync_trigger_notifier.dart` — gérer `SyncRequiredException` dans `triggerSync()` (AC10, prévention boucle infinie)
  ```dart
  // In SyncTriggerNotifier.triggerSync(), REPLACE the existing push try-catch block:

  // ─── BEFORE (existing — all push errors treated the same) ───
  // try {
  //   conflicts = await syncService.push();
  // } catch (e) {
  //   dev.log('Push failed during sync cycle: $e', name: 'SyncTrigger');
  //   // Push failure does NOT prevent pull  <-- BUG when 423: pull resets kLastSyncAtKey
  // }

  // ─── AFTER (differentiate SyncRequiredException from other errors) ───
  List<Map<String, dynamic>> conflicts = [];
  try {
    conflicts = await syncService.push();
  } on SyncRequiredException catch (e) {
    // Server 423: device is stale server-side.
    // ABORT pull — pull would write kLastSyncAtKey = now and falsely reopen client gate.
    dev.log(
      'Push blocked by server 423 gate: ${e.daysSinceLastSync} days stale',
      name: 'SyncTrigger',
    );
    // Force client gate to stay blocked (align with server reality)
    final prefs = ref.read(sharedPreferencesProvider);
    final staleMs = DateTime.now()
        .subtract(const Duration(days: 7))
        .millisecondsSinceEpoch;
    await prefs.setInt(kLastSyncAtKey, staleMs);
    ref.invalidate(daysSinceLastSyncProvider);  // immediate blocked state
    rethrow;  // outer catch handles _scheduleRetry
  } catch (e) {
    dev.log('Push failed during sync cycle: $e', name: 'SyncTrigger');
    // Non-423 push failure: proceed with pull (pull is NOT gated)
  }
  ```
  > ⚠️ **WHY THIS MATTERS**: Without the `SyncRequiredException`-specific handler, a 423 from `push()` would be silently swallowed. `pull()` would then run and write `kLastSyncAtKey = now`. The client gate would open (`daysSinceLastSync = 0`). The user would queue a write, `push()` would again return 423, `pull()` would again reset `kLastSyncAtKey`, and so on infinitely. This fix aborts the pull cycle and forces the client gate to reflect the server reality.

---

### Task 10 — Implémentation Flutter: SyncGateGuard (AC3)

- [x] **10.1** Créer `sync_gate_guard.dart`
  ```dart
  // lib/core/sync/sync_gate_guard.dart

  /// SyncGateGuard — Template Method pattern for write protection.
  ///
  /// All write notifiers call SyncGateGuard.assertWriteAllowed(ref)
  /// BEFORE calling repository write methods.
  /// If the gate is blocked, throws [WriteBlockedException].
  class SyncGateGuard {
    const SyncGateGuard._();

    /// Throws [WriteBlockedException] if daysSinceLastSync >= 7.
    /// No-op for all other states (open, warning, critical — writes permitted).
    static void assertWriteAllowed(Ref ref) {
      final gate = ref.read(syncGateStateProvider);
      if (gate.isWriteBlocked) throw const WriteBlockedException();
    }
  }

  /// Thrown by SyncGateGuard.assertWriteAllowed() when daysSinceLastSync >= 7.
  ///
  /// Caught by write notifiers to display SyncRequiredModal.
  class WriteBlockedException implements Exception {
    const WriteBlockedException();

    @override
    String toString() => 'WriteBlockedException: Sync required (7+ days offline)';
  }
  ```

- [x] **10.2** Modifier les notifiers d'écriture pour appeler `SyncGateGuard.assertWriteAllowed(ref)`

  > **Notifiers à modifier** (tous ceux qui déclenchent des écritures Drift):

  **a) RecordSaleNotifier** (`lib/features/pos/presentation/provider/record_sale_notifier.dart`):
  ```dart
  // At the TOP of the submit() method, before any repo call:
  try {
    SyncGateGuard.assertWriteAllowed(ref);
  } on WriteBlockedException {
    state = RecordSaleState.blockedByGate();  // OR: use existing error state
    return;  // UI catches this state and shows SyncRequiredModal
  }
  ```

  **b) StockNotifier** (`lib/features/catalog/presentation/provider/stock_notifier.dart` or similar):
  ```dart
  // Same pattern in adjustStock() and recordEntry() methods
  ```

  **c) ExecuteTransferNotifier + CompleteTransferNotifier** (`lib/features/inventory/presentation/provider/`):
  ```dart
  // Same pattern in submit() method
  ```

  **d) ProductNotifier** (`lib/features/catalog/presentation/provider/product_notifier.dart`):
  ```dart
  // Same pattern in create(), update(), archive(), unarchive()
  ```

  > ⚠️ **IMPORTANT**: Only block WRITE operations. Read operations (loadProducts, loadStockLevels, etc.) must NEVER be blocked.

  > ⚠️ **STATE SHAPE**: Each notifier needs a "blockedByGate" state variant. Use the existing error state or add a specific case. The UI handler maps this state → shows `SyncRequiredModal`.

---

### Task 11 — Implémentation Flutter: SyncRequiredModal widget (AC3, AC4)

- [x] **11.1** Créer `sync_required_modal.dart`
  ```dart
  // lib/core/sync/presentation/widget/sync_required_modal.dart

  /// SyncRequiredModal — blocking dialog shown when write is blocked (AC3, AC4).
  ///
  /// State pattern: renders differently per _ModalState (idle/syncing/error/success).
  /// Observer pattern: listens to syncTriggerNotifier to reflect sync progress.
  class SyncRequiredModal extends ConsumerStatefulWidget {
    const SyncRequiredModal({super.key});

    // Show as a non-dismissable full-screen dialog
    static Future<void> show(BuildContext context) {
      return showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => const SyncRequiredModal(),
      );
    }
  }

  class _SyncRequiredModalState extends ConsumerState<SyncRequiredModal> {
    _ModalPhase _phase = _ModalPhase.idle;
    String? _errorMessage;

    // Phases: idle | syncing | error
    // success → auto-dismiss handled by gate lifting

    @override
    Widget build(BuildContext context) {
      // Watch gate state: when gate lifts (open), close dialog
      ref.listen(syncGateStateProvider, (prev, next) {
        if (next == SyncGateState.open && mounted) {
          Navigator.of(context).pop();
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('✅ Synchronisation réussie — accès complet restauré'),
              backgroundColor: Color(0xFF51CF66),
              duration: Duration(seconds: 4),
            ),
          );
        }
      });

      return AlertDialog(
        title: _buildTitle(),
        content: _buildContent(),
        actions: _buildActions(context),
      );
    }

    Widget _buildTitle() => /* ⏸ Synchronisation requise / 🔄 Synchronisation en cours... */ ...;
    Widget _buildContent() => /* phase-dependent content */ ...;
    List<Widget> _buildActions(BuildContext context) => /* buttons per phase */ ...;

    Future<void> _triggerSync() async {
      setState(() { _phase = _ModalPhase.syncing; _errorMessage = null; });
      try {
        await ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
        // Gate auto-lifts via ref.listen above if sync succeeded
      } catch (e) {
        setState(() {
          _phase = _ModalPhase.error;
          _errorMessage = 'Impossible de synchroniser. Vérifiez votre connexion internet.';
        });
      }
    }
  }

  enum _ModalPhase { idle, syncing, error }
  ```

---

### Task 12 — Implémentation Flutter: OfflineGateBanner widget (AC2)

- [x] **12.1** Créer `offline_gate_banner.dart`
  ```dart
  // lib/core/sync/presentation/widget/offline_gate_banner.dart

  /// OfflineGateBanner — persistent banner shown at Day 5 and Day 6.
  ///
  /// Observer pattern: watches syncGateStateProvider and rebuilds on change.
  /// NOT shown for open (days 0–4) or blocked (modal takes over).
  class OfflineGateBanner extends ConsumerStatefulWidget {
    const OfflineGateBanner({super.key});
  }

  class _OfflineGateBannerState extends ConsumerState<OfflineGateBanner> {
    bool _dismissedToday = false;

    @override
    void initState() {
      super.initState();
      // Restore persisted dismissal: prevents banner reappearing on every app restart
      WidgetsBinding.instance.addPostFrameCallback((_) => _restoreDismissedState());
    }

    Future<void> _restoreDismissedState() async {
      if (!mounted) return;
      final prefs = ref.read(sharedPreferencesProvider);
      final dismissedDate = prefs.getString('gate_banner_dismissed_date');
      if (dismissedDate != null) {
        final today = DateTime.now().toIso8601String().substring(0, 10);
        if (dismissedDate == today && mounted) {
          setState(() => _dismissedToday = true);
        }
      }
    }

    @override
    Widget build(BuildContext context) {
      final gate = ref.watch(syncGateStateProvider);

      if (gate == SyncGateState.open || gate == SyncGateState.blocked) {
        return const SizedBox.shrink();
      }
      if (gate == SyncGateState.warning && _dismissedToday) {
        return const SizedBox.shrink();
      }

      final isWarning = gate == SyncGateState.warning;
      return AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        width: double.infinity,
        color: isWarning ? const Color(0xFFFFF3BF) : const Color(0xFFFFE3E3),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(
              isWarning ? Icons.warning_amber_rounded : Icons.circle,
              color: isWarning ? const Color(0xFFFCC419) : const Color(0xFFFA5252),
              size: 16,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                isWarning
                    ? '⚠ Synchronisation requise dans 2 jours'
                    : '🔴 Dernière chance : synchronisez demain ou certaines actions seront bloquées',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
            if (isWarning)
              IconButton(
                key: const Key('banner_dismiss'),
                icon: const Icon(Icons.close, size: 16),
                onPressed: _dismissForToday,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
          ],
        ),
      );
    }

    Future<void> _dismissForToday() async {
      final prefs = ref.read(sharedPreferencesProvider);
      final today = DateTime.now().toIso8601String().substring(0, 10); // YYYY-MM-DD
      await prefs.setString('gate_banner_dismissed_date', today);
      setState(() => _dismissedToday = true);
    }
  }
  ```

- [x] **12.2** Modifier `main_shell.dart` (MainShellPage ou equivalent) — injecter `OfflineGateBanner`
  ```dart
  // Locate the main scaffold Column in MainShellPage.
  // Insert OfflineGateBanner() BETWEEN the AppBar and the page body:

  body: Column(
    children: [
      const OfflineGateBanner(),   // NEW — AC2 banner
      Expanded(child: /* existing page content */),
    ],
  ),
  ```
  > ⚠️ **FIND THE RIGHT FILE**: Search for `main_shell` or `BottomNavigationBar` + `IndexedStack` in the Flutter app. The file is likely `lib/features/shell/presentation/page/main_shell_page.dart` or `lib/app/shell/main_shell.dart`.

---

### Task 13 — Implémentation Flutter: SyncIndicator update (AC1)

- [x] **13.1** Modifier `sync_indicator.dart` — add gate-aware display
  ```dart
  // In SyncIndicator.build():
  
  final gateState = ref.watch(syncGateStateProvider);  // NEW watch

  // BLOCKED state overrides connectivity status
  if (gateState == SyncGateState.blocked) {
    return GestureDetector(
      onTap: () => SyncRequiredModal.show(context),  // tap → modal directly
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              key: const Key('sync_dot'),
              width: 10, height: 10,
              decoration: const BoxDecoration(
                color: Color(0xFFFA5252),  // red
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 6),
            const Text('Accès limité — Sync requise',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: Color(0xFFFA5252)),
            ),
          ],
        ),
      ),
    );
  }

  // All other states: existing behavior unchanged
  // ...existing code...
  ```

---

### Task 14 — Implémentation Flutter: handle WriteBlockedException in UI (AC3)

- [x] **14.1** Modifier les pages consommatrices des write notifiers pour attraper l'état blockedByGate

  **a) PosPage / CheckoutPage** (for RecordSaleNotifier):
  ```dart
  // In the existing notifier listener for RecordSaleState,
  // add handling for the blockedByGate state:
  ref.listen(recordSaleNotifierProvider, (prev, next) {
    next.when(
      // ... existing states ...
      blockedByGate: () => SyncRequiredModal.show(context),
    );
  });
  ```

  **b) StockPage** (for StockNotifier write operations):
  ```dart
  // Same pattern in stock adjustment handlers
  ```

  **c) TransferPage** (ExecuteTransferNotifier):
  ```dart
  // Same pattern in transfer submit handler
  ```

  **d) ProductPage / CatalogPage** (ProductNotifier):
  ```dart
  // Same pattern in create/edit/archive handlers
  ```

---

### Task 15 — Provider wiring in providers.dart (AC5)

- [x] **15.1** Vérifier que `RestSyncService` reçoit `SharedPreferences` (Task 8.2 dependency)
  ```dart
  // In lib/core/di/providers.dart, find the syncServiceProvider:
  final syncServiceProvider = Provider<SyncService>((ref) {
    final db = ref.watch(appDatabaseProvider);
    final dio = ref.watch(dioProvider);
    final secureStorage = ref.watch(secureStorageProvider);
    final prefs = ref.watch(sharedPreferencesProvider);  // NEW — if not already injected
    return RestSyncService(
      database: db,
      dio: dio,
      secureStorage: secureStorage,
      prefs: prefs,  // NEW
    );
  });
  ```

- [x] **15.2** Vérifier que `syncGateStateProvider` et `daysSinceLastSyncProvider` sont dans le graph Riverpod
  - Ces providers ne nécessitent PAS d'enregistrement explicite (Riverpod les découvre automatiquement)
  - Importer dans les fichiers consommateurs suffit

---

### Task 16 — Tests Flutter: exécution finale (TDD GREEN)

- [x] **16.1** Vérifier `daysSinceLastSyncProvider` tests: all GREEN
- [x] **16.2** Vérifier `syncGateStateProvider` tests: all GREEN
- [x] **16.3** Vérifier `SyncRequiredModal` widget tests: all GREEN
- [x] **16.4** Vérifier `OfflineGateBanner` widget tests: all GREEN
- [x] **16.5** Vérifier `SyncIndicator` gate tests: all GREEN
- [x] **16.6** Run full Flutter suite: `flutter test` → all GREEN

---

### Task 17 — Tests Backend: exécution finale (TDD GREEN)

- [x] **17.1** Vérifier `UserSyncStateRepositoryAdapterTest`: all GREEN
- [x] **17.2** Vérifier `SyncGateCheckServiceTest`: all GREEN
- [x] **17.3** Vérifier `SyncControllerPushGateTest`: all GREEN
- [x] **17.4** Run full backend suite: `mvn test` → all GREEN (no regressions)
- [x] **17.5** Run `curl-tests-story-5-4.sh` → 10/10 PASS

---

## Dev Notes

### Architecture Compliance Checklist

- ✅ **Hexagonal ports**: `UserSyncStateRepository` is a port-out interface in `sync/sync/domain/port/out/`; adapter in `sync/sync/adapter/out/persistence/impl/`
- ✅ **GoF patterns applied**: State (`SyncGateState`), Observer (Riverpod providers), Template Method (`SyncGateGuard.assertWriteAllowed`)
- ✅ **TDD**: all tests written RED before GREEN implementation
- ✅ **No Drift for lastSyncAt**: written to SharedPreferences (synchronous access for Provider computation). Epic spec said `flutter_secure_storage` — **deliberate deviation**: Riverpod `Provider<int>` requires synchronous reads; SecureStorage is async-only. SharedPreferences survives database resets just like SecureStorage. The pull cursor (`kLastSyncTimestampKey`) remains in SecureStorage unchanged.
- ✅ **PUBLIC schema for user_sync_state**: NOT per-tenant — use `JdbcTemplate` with explicit `public.` prefix, NOT `EntityManager` (which uses TenantContext)
- ✅ **Read operations NEVER blocked**: only write operations are gated
- ✅ **Existing SyncStatus/daysOfflineProvider unchanged**: Story 5.4 uses separate `daysSinceLastSyncProvider`
- ✅ **Anti-loop guard**: `SyncRequiredException` from `push()` aborts pull cycle in `triggerSync()` — prevents `kLastSyncAtKey` false-reset that would reopen client gate while server stays stale

### Critical File Paths

#### Flutter (new files)

| File | Purpose |
|---|---|
| `lib/core/sync/sync_gate_state.dart` | SyncGateState enum — State pattern |
| `lib/core/sync/sync_gate_provider.dart` | daysSinceLastSyncProvider + syncGateStateProvider |
| `lib/core/sync/sync_gate_guard.dart` | SyncGateGuard.assertWriteAllowed() + WriteBlockedException |
| `lib/core/sync/sync_required_exception.dart` | SyncRequiredException — server 423 gate exception (Task 8.4) |
| `lib/core/sync/presentation/widget/sync_required_modal.dart` | Blocking modal (AC3, AC4) |
| `lib/core/sync/presentation/widget/offline_gate_banner.dart` | Day 5/6 persistent banners (AC2) |

#### Flutter (modified files)

| File | Change |
|---|---|
| `lib/core/storage/app_constants.dart` | Add `kLastSyncAtKey = 'last_sync_at_ms'` |
| `lib/core/sync/rest_sync_service.dart` | Write kLastSyncAtKey to SharedPreferences in pull(); throw SyncRequiredException on HTTP 423 in push() |
| `lib/core/sync/sync_trigger_notifier.dart` | Add 30-min `_gateCheckTimer`; handle `SyncRequiredException` in `triggerSync()` to abort pull and force client gate blocked |
| `lib/core/sync/sync_indicator/presentation/widget/sync_indicator.dart` | Handle `blocked` state display + tap → modal |
| `lib/features/shell/presentation/page/main_shell_page.dart` | Add `OfflineGateBanner()` in Column above page content |
| `lib/core/di/providers.dart` | Inject SharedPreferences into RestSyncService if needed |
| `lib/features/pos/presentation/provider/record_sale_notifier.dart` | Add SyncGateGuard.assertWriteAllowed at write entry point |
| `lib/features/catalog/presentation/provider/stock_notifier.dart` | Add SyncGateGuard.assertWriteAllowed |
| `lib/features/inventory/presentation/provider/execute_transfer_notifier.dart` | Add SyncGateGuard.assertWriteAllowed |
| `lib/features/catalog/presentation/provider/product_notifier.dart` | Add SyncGateGuard.assertWriteAllowed |

#### Backend (new files)

| File | Purpose |
|---|---|
| `sync/sync/domain/model/UserSyncState.java` | Domain record |
| `sync/sync/domain/port/out/UserSyncStateRepository.java` | Port out interface |
| `sync/sync/application/service/SyncGateCheckService.java` | 7-day stale check logic |
| `sync/sync/adapter/out/persistence/UserSyncStateDdlInitializer.java` | PUBLIC schema DDL on startup |
| `sync/sync/adapter/out/persistence/impl/UserSyncStateRepositoryAdapter.java` | JdbcTemplate adapter |
| `scripts/curl-tests-story-5-4.sh` | 10-step E2E integration tests |

#### Backend (modified files)

| File | Change |
|---|---|
| `sync/sync/adapter/in/rest/SyncController.java` | Add gate check + upsert in push() |
| `shared/domain/model/DomainErrorCode.java` (or equivalent) | Add `SYNC_REQUIRED` |

### Project Structure Notes

- Backend sync module: `com/keevo/sync/sync/` — follows hexagonal architecture established in 5.1
- Flutter sync core: `lib/core/sync/` — existing sync engine, providers added alongside existing ones
- MainShellPage location: likely `lib/features/shell/presentation/page/main_shell_page.dart` or `lib/app/` — **VERIFY** by searching for `BottomNavigationBar` or `IndexedStack` in Flutter code
- `SyncIndicator` is in `lib/features/sync_indicator/presentation/widget/sync_indicator.dart` (confirmed from codebase search)

### Critical Implementation Constraints

1. **kLastSyncTimestampKey vs kLastSyncAtKey**: 
   - `kLastSyncTimestampKey` (SecureStorage): pull cursor for `?since=` parameter — DO NOT CHANGE
   - `kLastSyncAtKey` (SharedPreferences): NEW key for gate computation
   - Written in `RestSyncService.pull()` on success — BUT ONLY if `push()` did NOT throw `SyncRequiredException`
   - If `push()` threw 423 → `triggerSync()` catches `SyncRequiredException`, writes `kLastSyncAtKey = now - 7 days`, and does NOT call `pull()`. See Task 9.3.
   - **⚠️ DO NOT move `kLastSyncAtKey` write to inside `pull()` for the 423 case** — this breaks the anti-loop guard

2. **PUBLIC schema for user_sync_state**:
   - Use `JdbcTemplate jdbcTemplate` (plain public schema connection)
   - NEVER use `EntityManager` (would resolve to tenant schema via TenantContext)
   - Pattern established in `UserSyncStateDdlInitializer` using `@Primary` datasource

3. **Circular dependency risk in RestSyncService**:
   - `RestSyncService` is called by `SyncTriggerNotifier`
   - `SyncTriggerNotifier` reads `syncGateStateProvider`
   - `syncGateStateProvider` reads `daysSinceLastSyncProvider`
   - `daysSinceLastSyncProvider` reads `sharedPreferencesProvider`
   - **No circular dep**: the flow is one-way. Gate providers don't call SyncService.

4. **HTTP 423 handling in RestSyncService**:
   - Add `catch (DioException e) { if (e.response?.statusCode == 423) throw SyncRequiredException(...); }`
   - `SyncTriggerNotifier.triggerSync()` catches `SyncRequiredException` and handles display

5. **WriteBlockedException propagation**:
   - `SyncGateGuard.assertWriteAllowed(ref)` throws `WriteBlockedException`
   - Each write notifier catches it and updates its state to `blockedByGate`
   - The calling UI page listens to notifier state and calls `SyncRequiredModal.show(context)`

6. **First install / never synced**:
   - `kLastSyncAtKey` is null → `daysSinceLastSync = 0` → `SyncGateState.open`
   - No restriction whatsoever on first install (correct behavior — user can't sync before first login)

7. **Background timer in SyncTriggerNotifier**:
   - The `_gateCheckTimer` calls `ref.invalidate(daysSinceLastSyncProvider)` every 30 minutes
   - This forces `syncGateStateProvider` to recompute, which rebuilds watching widgets
   - `ref.invalidate()` is safe in keepAlive Riverpod notifiers (no dispose issues)

### GoF Compliance Verification

| Pattern | Applied At | Verification |
|---|---|---|
| State | `SyncGateState` enum with `fromDaysSinceLastSync()` | 4 states map to 4 distinct UI behaviors |
| Observer | `syncGateStateProvider` watched by SyncIndicator, OfflineGateBanner, SyncRequiredModal | Any state change → reactive UI update |
| Template Method | `SyncGateGuard.assertWriteAllowed(ref)` called by each write notifier | Common algorithm skeleton with variant (throw vs no-op) per gate state |

### UX Compliance

- [UX Source: ux-design-specification.md#Flow 22] Sync Forcée flow: "7 jours offline → Accès suspendu → Sync obligatoire → Sync auto < 60s → Accès restauré"
- [UX Source: ux-design-specification.md#Section 6] Sync Indicator states: "Online synced | Online syncing | Offline OK (<7j) | Offline critique (>5j)"
- [UX Source: ux-design-specification.md] Banner palette: `#FCC419` amber alerts, `#FA5252` red errors
- [UX Source: ux-design-specification.md] "Offline = Normal" principle: READ operations NEVER blocked
- [Architecture Source: architecture.md] Hexagonal ports in `domain/port/out/`, adapters in `adapter/out/persistence/impl/`

### References

- [Source: epics/epic-5-moteur-de-synchronisation-offline-first.md#Story 5.4] — Full acceptance criteria
- [Source: implementation-artifacts/5-3-resolution-de-conflits-delta-stock-last-write-wins.md#Dev Notes] — Previous story patterns (ConflictStrategyRegistry, SyncPushService structure)
- [Source: implementation-artifacts/1-5-local-data-storage-encryption-connectivity-indicator.md] — SyncIndicator, firstOfflineDateProvider, kFirstOfflineDateKey
- [Source: implementation-artifacts/5-2-pull-sync-telechargement-delta-fusion-locale.md] — RestSyncService.pull(), kLastSyncTimestampKey SecureStorage write
- [Source: implementation-artifacts/5-1-push-sync-envoi-par-lot-des-operations-en-file.md] — SyncTriggerNotifier, push pipeline
- [Source: planning-artifacts/architecture.md#Sync Table L842-864] — Repository write patterns
- [Source: app_constants.dart] — Existing constants: kFirstOfflineDateKey, kLastSyncTimestampKey

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

### Completion Notes List

- Story 5.4 code review completed 2026-03-24: H2 banner colors fixed (#FFF3BF/#FFE3E3), H3 "Continuer en lecture seule" button added to SyncRequiredModal, M1 success SnackBar added, broken Dart emoji escape \U0001f534→🔴 fixed in SyncIndicator, all 3 AC12 widget test files created, M3 gate-blocked test added to RecordSaleNotifier tests, story File List populated.

### File List

**Flutter — new files**
- `keevo/app/lib/core/sync/sync_gate_state.dart`
- `keevo/app/lib/core/sync/sync_gate_provider.dart`
- `keevo/app/lib/core/sync/sync_gate_guard.dart`
- `keevo/app/lib/core/sync/sync_required_exception.dart`
- `keevo/app/lib/core/scaffold/offline_gate_banner.dart`
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_required_modal.dart`
- `keevo/app/test/core/sync/sync_gate_state_test.dart`
- `keevo/app/test/core/sync/days_since_last_sync_provider_test.dart`
- `keevo/app/test/core/sync/sync_gate_state_provider_test.dart`
- `keevo/app/test/core/sync/offline_gate_banner_test.dart`
- `keevo/app/test/core/sync/sync_required_exception_test.dart`
- `keevo/app/test/features/sync_indicator/sync_required_modal_test.dart`
- `keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart`

**Flutter — modified files**
- `keevo/app/lib/core/storage/app_constants.dart`
- `keevo/app/lib/core/sync/rest_sync_service.dart`
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart`
- `keevo/app/lib/core/di/providers.dart`
- `keevo/app/lib/core/scaffold/main_shell.dart`
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart`
- `keevo/app/lib/features/catalog/presentation/provider/product_provider.dart`
- `keevo/app/lib/features/catalog/presentation/provider/stock_provider.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_adjust_bottom_sheet.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_entry_bottom_sheet.dart`
- `keevo/app/lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart`
- `keevo/app/lib/features/pos/presentation/page/checkout_page.dart`
- `keevo/app/lib/features/pos/presentation/provider/record_sale_notifier.dart`
- `keevo/app/test/core/sync/rest_sync_service_pull_test.dart`
- `keevo/app/test/core/sync/sync_pull_merge_test.dart`
- `keevo/app/test/features/pos/presentation/provider/record_sale_notifier_test.dart`
- `keevo/app/test/features/sync_indicator/sync_indicator_test.dart`

**Backend — new files**
- `keevo/backend/src/main/java/com/keevo/sync/sync/domain/model/UserSyncState.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/out/UserSyncStateRepository.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncGateCheckService.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/out/persistence/UserSyncStateDdlInitializer.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/out/persistence/impl/UserSyncStateRepositoryAdapter.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/service/SyncGateCheckServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/out/persistence/impl/UserSyncStateRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/in/rest/SyncControllerPushGateTest.java`
- `keevo/scripts/curl-tests-story-5-4.sh`

**Backend — modified files**
- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/rest/SyncController.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/rest/dto/SyncPushRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/in/rest/SyncControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/in/rest/SyncControllerConflictsTest.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/in/rest/dto/SyncPushRequestDtoTest.java`
