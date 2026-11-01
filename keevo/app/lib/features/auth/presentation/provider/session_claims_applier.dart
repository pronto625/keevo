import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/auth/jwt_claims.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/storage/secure_storage_provider.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/entitlements/entitlements_provider.dart';
import '../../../../core/extension/extension_slot.dart';
import '../../../account/presentation/provider/account_deletion_provider.dart';
import '../../../pos/presentation/provider/cart_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../../settings/presentation/provider/subscription_info_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../data/session_identity.dart';
import '../../domain/model/account_profile.dart';
import 'auth_provider.dart';

/// The local DB holds operations of another account that never reached the
/// server; signing in with a different account would delete them. The sign-in
/// flow asks the user before going on (see [SessionClaimsApplier.apply]).
class PreviousAccountDataException implements Exception {
  final int pendingOperations;

  const PreviousAccountDataException(this.pendingOperations);

  @override
  String toString() =>
      'PreviousAccountDataException($pendingOperations pending operations)';
}

/// Applies a new access token to app state. Called with every token the app
/// receives — login, tenant selection, startup and silent renewal.
///
/// Story 16.11 — the token proves identity only (user, account, device
/// session): it decides which account owns the local data. Role, store, first
/// name, password-change flag and account status come from the profile
/// (`GET /api/v1/auth/profile`), cached for offline display; when the profile
/// cannot be loaded the cached identity stays (a new session starts from what
/// the sign-in answer said). A reassigned employee moves at the next renewal.
class SessionClaimsApplier {
  final Ref _ref;

  SessionClaimsApplier(this._ref);

  /// [newSession]: the token comes from a sign-in rather than a renewal. Only
  /// then can the account differ from the one owning the local data.
  ///
  /// Throws [PreviousAccountDataException] when a different account signs in
  /// while the previous one still has unsynced operations, unless
  /// [discardPreviousAccountData] says the user accepted losing them.
  /// [seedRole] / [seedStoreId] / [seedPasswordChangeRequired]: what the
  /// sign-in answer body said, used only when the profile cannot be loaded.
  Future<void> apply(
    String accessToken, {
    bool newSession = false,
    bool discardPreviousAccountData = false,
    String? seedRole,
    String? seedStoreId,
    bool seedPasswordChangeRequired = false,
  }) async {
    final claims = JwtClaims.tryParse(accessToken);
    if (claims == null) return;

    if (newSession) {
      await _isolateFromPreviousAccount(claims,
          discardPendingOperations: discardPreviousAccountData);
    } else {
      await _recordOwnerIfMissing(claims);
    }

    final prefs = _ref.read(sharedPreferencesProvider);
    AccountProfile? profile;
    try {
      profile = await _ref.read(sessionProfileFetcherProvider)(accessToken);
    } catch (_) {
      profile = null; // offline or server error: keep the cached identity
    }
    if (profile != null) {
      await SessionIdentity.save(prefs, profile);
    } else if (newSession) {
      await SessionIdentity.reset(prefs,
          role: seedRole,
          storeId: seedStoreId,
          passwordChangeRequired: seedPasswordChangeRequired);
    }
    final role = SessionIdentity.role(prefs);

    // An employee works in their assigned store — a reassignment moves them at
    // the next renewal. An owner keeps the store they picked, except on
    // sign-in where "all stores" is the default.
    final activeStore = _ref.read(activeStoreIdProvider.notifier);
    if (role == 'EMPLOYEE') {
      final storeId = SessionIdentity.storeId(prefs);
      if (storeId != null || newSession) activeStore.setActiveStore(storeId);
    } else if (newSession) {
      activeStore.setActiveStore(null);
    }

    _ref.invalidate(currentUserRoleProvider);
    _ref.invalidate(tenantStatusClaimProvider);
    _ref.invalidate(currentUserIdProvider);
    // v1s-16-13: a sign-in may be another tenant (shared POS device).
    // Only when mounted: Ref.invalidate mounts (and fetches) it in debug.
    if (newSession && _ref.exists(subscriptionInfoProvider)) {
      _ref.invalidate(subscriptionInfoProvider);
    }
    // 15.1 / 16.3: every token (sign-in, tenant selection, renewal) re-seeds
    // the module states from this account's snapshot and asks the server
    // again — tokens carry no module list. Mounted now, not on the next read,
    // so a re-enabled module's pull-cursor reset happens before the next sync.
    if (_ref.exists(entitlementsProvider)) {
      _ref.invalidate(entitlementsProvider);
    }
    _ref.read(entitlementsProvider);
    // 15.3: a sign-in (maybe another account / tenant) starts a new slot
    // cache; a renewal keeps it (it follows the readable module set).
    if (newSession) _ref.invalidate(extensionContributionsProvider);
    // Same for the account-deletion banner (cached per process) — on every
    // token: the profile loaded with a renewal carries the account status
    // after a deletion request / cancellation (A50).
    if (_ref.exists(deletionPendingStatusProvider)) {
      _ref.invalidate(deletionPendingStatusProvider);
    }
  }

  /// Ends a sign-in that the user abandoned (e.g. to keep the previous
  /// account's unsynced operations): the new tokens go, local data stays.
  Future<void> abandonSignIn() =>
      clearSessionSecrets(_ref.read(flutterSecureStorageProvider));

  /// Prefs that belong to the account rather than to the device.
  static const _accountPrefs = [
    kSectorTypeKey,
    kLastSyncAtKey,
    kAutoClosureNotifiedIds,
    kOnboardingWizardCompletedKey,
    kEntitlementsSnapshotKey,
  ];

  Future<void> _isolateFromPreviousAccount(
    JwtClaims claims, {
    required bool discardPendingOperations,
  }) async {
    final owner = _ownerOf(claims);
    final prefs = _ref.read(sharedPreferencesProvider);
    final previous = prefs.getString(kLocalDataOwnerKey);
    if (previous != null && previous != owner) {
      final db = _ref.read(appDatabaseProvider);
      if (!discardPendingOperations) {
        final pending = await _pendingOperations(db);
        if (pending > 0) throw PreviousAccountDataException(pending);
      }
      await db.clearAllData();
      // The pull cursor belonged to the previous account: start with a full pull.
      final secure = _ref.read(flutterSecureStorageProvider);
      await secure.delete(key: kLastSyncTimestampKey);
      // So did the sync device id: the server binds a device to one user and
      // answers DEVICE_ID_MISMATCH (403) to anyone else, so an employee signing
      // in on the owner's phone could never sync (device test). A fresh id is
      // created on the next sync.
      await secure.delete(key: kDeviceIdKey);
      for (final key in _accountPrefs) {
        await prefs.remove(key);
      }
      _ref.read(cartProvider.notifier).clearCart();
    }
    await prefs.setString(kLocalDataOwnerKey, owner);
  }

  /// A session that predates the owner key (app upgraded while signed in) owns
  /// the data already on the device: record it, so the next different account
  /// signing in does not inherit that data.
  Future<void> _recordOwnerIfMissing(JwtClaims claims) async {
    final prefs = _ref.read(sharedPreferencesProvider);
    if (prefs.getString(kLocalDataOwnerKey) == null) {
      await prefs.setString(kLocalDataOwnerKey, _ownerOf(claims));
    }
  }

  static String _ownerOf(JwtClaims claims) => '${claims.subject}@${claims.tenantId}';

  static Future<int> _pendingOperations(AppDatabase db) async {
    final row = await db
        .customSelect('SELECT COUNT(*) AS c FROM sync_queue WHERE synced = 0')
        .getSingle();
    return row.read<int>('c');
  }
}

final sessionClaimsApplierProvider =
    Provider<SessionClaimsApplier>(SessionClaimsApplier.new);
