import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/jwt_claims.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/storage/app_database.dart';
import '../../../../core/storage/secure_storage_provider.dart';
import '../../../pos/presentation/provider/cart_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
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

/// Applies an access token's claims to app state. Called with every token the
/// app receives — login, tenant selection, startup and silent renewal — so a
/// renewed token (new store, password-change flag…) takes effect immediately.
class SessionClaimsApplier {
  final Ref _ref;

  SessionClaimsApplier(this._ref);

  /// [newSession]: the token comes from a sign-in rather than a renewal. Only
  /// then can the account differ from the one owning the local data.
  ///
  /// Throws [PreviousAccountDataException] when a different account signs in
  /// while the previous one still has unsynced operations, unless
  /// [discardPreviousAccountData] says the user accepted losing them.
  Future<void> apply(
    String accessToken, {
    bool newSession = false,
    bool discardPreviousAccountData = false,
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
    final role = claims.role;
    if (role != null) await prefs.setString(kUserRoleKey, role);
    await prefs.setBool(kPasswordChangeRequiredKey, claims.passwordChangeRequired);
    // Always overwrite so a previous user's name is never shown.
    final firstName = claims.firstName;
    if (firstName != null && firstName.isNotEmpty) {
      await prefs.setString('user_first_name', firstName);
    } else {
      await prefs.remove('user_first_name');
    }

    // An employee works in the store of their token — a renewal after a
    // reassignment moves them. An owner keeps the store they picked, except
    // on sign-in where "all stores" is the default.
    final activeStore = _ref.read(activeStoreIdProvider.notifier);
    if (role == 'EMPLOYEE') {
      activeStore.setActiveStore(claims.storeId);
    } else if (newSession) {
      activeStore.setActiveStore(null);
    }

    _ref.invalidate(currentUserRoleProvider);
    _ref.invalidate(tenantStatusClaimProvider);
    _ref.invalidate(currentUserIdProvider);
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
      await _ref.read(flutterSecureStorageProvider).delete(key: kLastSyncTimestampKey);
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
