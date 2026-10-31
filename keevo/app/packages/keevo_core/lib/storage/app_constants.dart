// Application-wide SharedPreferences keys.
//
// Centralised here so that [app_router.dart] and feature pages
// all reference the exact same strings without cross-importing each other.

// Set to `true` after the user has seen the onboarding slides.
const String kOnboardingSeenKey = 'onboarding_seen';

// Set to `true` after the user has accepted the Terms of Service.
const String kTermsAcceptedKey = 'terms_accepted';

// Set to `true` after the user has completed the onboarding wizard
// (sector selection + shop name). Used by the splash redirect to route
// a newly registered user through the wizard before reaching the POS.
const String kOnboardingWizardSeenKey = 'onboarding_wizard_seen';

/// Story 9-5 — latest `sentAt` of the Keevo announcements already fetched
/// (ISO-8601 UTC); cleared on sign-out.
const String kAnnouncementsFetchedAtKey = 'announcements_fetched_at';

// Written only by the wizard itself when it completes. Wins over an
// onboardingCompleted=false claim: the registration token says false and lives
// until it is renewed, which may not have happened yet (offline).
const String kOnboardingWizardCompletedKey = 'onboarding_wizard_completed';

// One-shot flag: the pull cursor was reset once so rows pulled before dates
// were stored with their local offset get re-pulled in the new format.
const String kPullTzFixDoneKey = 'pull_tz_fix_v1';

// "<userId>@<tenantId>" of the account whose data fills the local DB. A
// different account signing in on the same device starts from a cleared DB,
// so it never sees nor pushes the previous account's data.
const String kLocalDataOwnerKey = 'local_data_owner';

// Set to `true` after the POS tutorial SnackBar has been shown once.
const String kPosTutorialShownKey = 'pos_tutorial_shown';

// Stores the selected sector apiCode (e.g. 'CLOTHING') persisted after wizard
// completion. Used by [PosPlaceholderPage] to show the correct sector emoji
// in the empty state (AC6).
const String kSectorTypeKey = 'sector_type';

// Encryption key for SQLCipher — 32-byte random hex string, stored in FlutterSecureStorage.
// Generated once on first launch; reused on subsequent launches (AC1).
const String kDbEncryptionKey = 'db_encryption_key';

// Timestamp (milliseconds since epoch) of the first moment the device went offline.
// Used by SyncIndicator to compute days-offline count (AC3).
// Cleared when the device comes back online.
const String kFirstOfflineDateKey = 'first_offline_date_ms';

// Timestamp (milliseconds since epoch) of the last successful sync.
// Displayed in the SyncIndicator bottom sheet (AC4).
const String kLastSyncTimestampKey = 'last_sync_timestamp_ms';

/// Timestamp (milliseconds since epoch) of the last completed sync cycle (push+pull).
/// Written by RestSyncService.pull() on success.
/// Used by syncGateStateProvider to compute daysSinceLastSync.
/// Stored in SharedPreferences (synchronous access for gate computation).
/// Distinct from kLastSyncTimestampKey (pull cursor, in SecureStorage).
const String kLastSyncAtKey = 'last_sync_at_ms';

// Set of closure IDs for which an auto-closure SnackBar has been shown.
// Stored via prefs.getStringList / setStringList.
// Capped at 200 entries to avoid unbounded growth.
const String kAutoClosureNotifiedIds = 'auto_closure_notified_ids';

// SharedPreferences key for the signed-in user's first name (JWT firstName
// claim). Read by the dashboard greeting and the inventory gap report
// signature.
const String kUserFirstNameKey = 'user_first_name';

/// SharedPreferences key of the store chosen as the active context
/// (`ActiveStoreNotifier`). Cleared by sync when that store's offline
/// creation is rejected by the server (v1s-16-12).
const String kActiveStoreIdKey = 'active_store_id';

/// Country pre-selected in phone fields (v1s-16-13). Keevo targets Cameroon;
/// locale detection was rejected (Cameroonian devices often report fr_FR,
/// which would pre-select France) — the picker lets the user change it.
const String kDefaultPhoneCountryCode = 'CM';

/// Secure-storage key of this install's sync device id (written by the sync
/// service, read to tell « Cet appareil » apart — v1s-16-20).
const String kDeviceIdKey = 'keevo_device_id';

/// Story 15.1 — last `GET /api/v1/tenants/me/modules` answer (JSON: owner
/// `<userId>@<tenantId>` + module states + fetch date), so the app keeps the
/// tenant's module states offline / across restarts. Only read back for the
/// same [kLocalDataOwnerKey]; cleared on sign-out and account switch.
const String kEntitlementsSnapshotKey = 'entitlements_snapshot';

/// Story 15.1 — set (to a unique int) when the next pull must be a full one
/// (a module became readable again: the delta pull skipped it while DISABLED
/// but advanced the cursor). Read by `RestSyncService` at the start of a pull,
/// cleared after it saved its cursor only if it still holds the value read at
/// start — a request raised during a pull survives it.
const String kFullPullRequestedKey = 'full_pull_requested';
