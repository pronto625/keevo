import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/entitlements/entitlements_provider.dart';
import '../../../../core/extension/extension_slot.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/storage/secure_storage_provider.dart';
import '../../../notifications/presentation/provider/notification_provider.dart';
import '../../../account/presentation/provider/account_deletion_provider.dart';
import '../../../pos/presentation/provider/cart_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../../settings/presentation/provider/subscription_info_provider.dart';
import 'auth_provider.dart';

/// Ends the session on this device.
///
/// The local database is kept: if the same account signs in again, its
/// unsynced operations are still there and get pushed. Another account
/// signing in starts from a cleared database (see SessionClaimsApplier).
class SignOutService {
  final Ref _ref;

  SignOutService(this._ref);

  /// Pushes the sync queue. Returns false when operations could not be sent
  /// (offline, server error), are held (module off, Story 13.8b) or are
  /// blocked (quarantined, v1s-16-15) — the caller warns before signing out.
  Future<bool> flushPendingOperations() async {
    final sync = _ref.read(syncServiceProvider);
    try {
      await sync.push();
    } catch (e) {
      debugPrint('[Auth] Push before sign-out failed: $e');
    }
    if (await sync.hasPendingOperations()) return false;
    if (await sync.heldOperationCount() > 0) return false;
    return await sync.quarantinedOperationCount() == 0;
  }

  Future<void> signOut() async {
    // Server calls first, while the tokens are still attached to requests.
    // An unreadable storage must not keep the session alive: the secrets
    // are still cleared below.
    String? refreshToken;
    try {
      refreshToken = await _ref.read(tokenStorageProvider).getRefreshToken();
    } catch (e) {
      debugPrint('[Auth] Refresh token read on sign-out failed: $e');
    }
    if (refreshToken != null) {
      try {
        await _ref.read(authRepositoryProvider).logout(refreshToken);
      } catch (e) {
        // Offline: the device is signed out anyway; the token expires on its own.
        debugPrint('[Auth] Refresh token revocation on sign-out failed: $e');
      }
    }

    final fcm = _ref.read(fcmServiceProvider);
    // v1s-16-13: an FCM failure (offline) must not keep the session alive.
    String? fcmToken;
    try {
      fcmToken = await fcm.getToken();
    } catch (e) {
      debugPrint('[FCM] Token read on logout failed: $e');
    }
    if (fcmToken != null) {
      try {
        await _ref.read(remoteDeviceTokenDataSourceProvider).deleteToken(fcmToken);
      } catch (e) {
        debugPrint('[FCM] Remote token deletion on logout failed: $e');
      }
    }
    // v1s-16-2: the backend may still hold an older token (a refresh whose
    // re-registration failed) — revoke it too.
    final registeredToken = fcm.lastRegisteredToken;
    if (registeredToken != null && registeredToken != fcmToken) {
      try {
        await _ref
            .read(remoteDeviceTokenDataSourceProvider)
            .deleteToken(registeredToken);
      } catch (e) {
        debugPrint('[FCM] Remote deletion of the registered token failed: $e');
      }
    }
    // Unconditional: local unsubscribe must happen even if the remote delete failed.
    try {
      await fcm.deleteToken();
    } catch (e) {
      debugPrint('[FCM] Local token deletion on logout failed: $e');
    }

    await clearSessionSecrets(_ref.read(flutterSecureStorageProvider));

    final prefs = _ref.read(sharedPreferencesProvider);
    await prefs.remove(kUserRoleKey);
    await prefs.remove(kUserPhoneKey);
    await prefs.remove(kPasswordChangeRequiredKey);
    await prefs.remove(kOnboardingWizardSeenKey);
    await prefs.remove(kAnnouncementsFetchedAtKey);
    // 15.1: the owner-tagged module snapshot is kept — the same account
    // signing back in still has its baseline (an account switch clears it).

    _ref.read(cartProvider.notifier).clearCart();
    _ref.invalidate(currentUserRoleProvider);
    _ref.invalidate(currentUserPhoneProvider);
    _ref.invalidate(currentUserIdProvider);
    _ref.invalidate(tenantStatusClaimProvider);
    // v1s-16-13: the next account must not see this tenant's plan. Only
    // when mounted: Ref.invalidate mounts (and fetches) it in debug.
    if (_ref.exists(subscriptionInfoProvider)) {
      _ref.invalidate(subscriptionInfoProvider);
    }
    // 15.1: module states are per session.
    if (_ref.exists(entitlementsProvider)) {
      _ref.invalidate(entitlementsProvider);
    }
    // 15.3: the extension slot contents too (every point id).
    _ref.invalidate(extensionContributionsProvider);
    // Same for the account-deletion banner (cached per process).
    if (_ref.exists(deletionPendingStatusProvider)) {
      _ref.invalidate(deletionPendingStatusProvider);
    }
  }
}

final signOutServiceProvider = Provider<SignOutService>(SignOutService.new);
