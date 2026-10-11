import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/storage/secure_storage_provider.dart';
import '../../../notifications/presentation/provider/notification_provider.dart';
import '../../../pos/presentation/provider/cart_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
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
  /// (offline, server error) — the caller warns before signing out.
  Future<bool> flushPendingOperations() async {
    final sync = _ref.read(syncServiceProvider);
    try {
      await sync.push();
    } catch (e) {
      debugPrint('[Auth] Push before sign-out failed: $e');
    }
    return !await sync.hasPendingOperations();
  }

  Future<void> signOut() async {
    // Server calls first, while the tokens are still attached to requests.
    final refreshToken = await _ref.read(tokenStorageProvider).getRefreshToken();
    if (refreshToken != null) {
      try {
        await _ref.read(authRepositoryProvider).logout(refreshToken);
      } catch (e) {
        // Offline: the device is signed out anyway; the token expires on its own.
        debugPrint('[Auth] Refresh token revocation on sign-out failed: $e');
      }
    }

    final fcm = _ref.read(fcmServiceProvider);
    final fcmToken = await fcm.getToken();
    if (fcmToken != null) {
      try {
        await _ref.read(remoteDeviceTokenDataSourceProvider).deleteToken(fcmToken);
      } catch (e) {
        debugPrint('[FCM] Remote token deletion on logout failed: $e');
      }
    }
    // Unconditional: local unsubscribe must happen even if the remote delete failed.
    await fcm.deleteToken();

    await clearSessionSecrets(_ref.read(flutterSecureStorageProvider));

    final prefs = _ref.read(sharedPreferencesProvider);
    await prefs.remove(kUserRoleKey);
    await prefs.remove(kUserPhoneKey);
    await prefs.remove(kPasswordChangeRequiredKey);
    await prefs.remove(kOnboardingWizardSeenKey);

    _ref.read(cartProvider.notifier).clearCart();
    _ref.invalidate(currentUserRoleProvider);
    _ref.invalidate(currentUserPhoneProvider);
    _ref.invalidate(currentUserIdProvider);
    _ref.invalidate(tenantStatusClaimProvider);
  }
}

final signOutServiceProvider = Provider<SignOutService>(SignOutService.new);
