import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../domain/model/account_profile.dart';

/// Story 16.11 — the signed-in user's identity as the server reports it
/// (`GET /api/v1/auth/profile`), cached in preferences for offline display:
/// role, store, first name, password-change flag, account status, onboarding.
/// The access token proves identity only and is never read for these; the
/// server checks every right on every request.
class SessionIdentity {
  const SessionIdentity._();

  /// Every key written here (cleared when another session starts).
  static const keys = [
    kUserRoleKey,
    kPasswordChangeRequiredKey,
    kUserFirstNameKey,
    kTenantStatusKey,
    kUserStoreIdKey,
    kUserStoreNameKey,
    kOnboardingCompletedKey,
  ];

  static Future<void> save(SharedPreferences prefs, AccountProfile p) async {
    await prefs.setString(kUserRoleKey, p.role);
    await prefs.setBool(kPasswordChangeRequiredKey, p.passwordChangeRequired);
    await _setOrRemove(prefs, kUserFirstNameKey, p.firstName);
    await _setOrRemove(prefs, kTenantStatusKey, p.tenantStatus);
    await _setOrRemove(prefs, kUserStoreIdKey, p.storeId);
    await _setOrRemove(prefs, kUserStoreNameKey, p.storeName);
    final onboarding = p.onboardingCompleted;
    if (onboarding == null) {
      await prefs.remove(kOnboardingCompletedKey);
    } else {
      await prefs.setBool(kOnboardingCompletedKey, onboarding);
    }
  }

  /// A new session whose profile could not be loaded: never show the previous
  /// user's identity; seed what the sign-in answer said (role, store, flag).
  static Future<void> reset(
    SharedPreferences prefs, {
    String? role,
    String? storeId,
    bool passwordChangeRequired = false,
  }) async {
    for (final key in keys) {
      await prefs.remove(key);
    }
    if (role != null) await prefs.setString(kUserRoleKey, role);
    if (storeId != null) await prefs.setString(kUserStoreIdKey, storeId);
    await prefs.setBool(kPasswordChangeRequiredKey, passwordChangeRequired);
  }

  static String? role(SharedPreferences prefs) => prefs.getString(kUserRoleKey);
  static String? storeId(SharedPreferences prefs) =>
      prefs.getString(kUserStoreIdKey);
  static String? tenantStatus(SharedPreferences prefs) =>
      prefs.getString(kTenantStatusKey);
  static bool? onboardingCompleted(SharedPreferences prefs) =>
      prefs.getBool(kOnboardingCompletedKey);

  static Future<void> _setOrRemove(
      SharedPreferences prefs, String key, String? value) async {
    if (value == null || value.isEmpty) {
      await prefs.remove(key);
    } else {
      await prefs.setString(key, value);
    }
  }
}
