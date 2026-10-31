import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:keevo_core/auth/jwt_claims.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/storage/secure_storage_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../auth/session_refresher.dart';

/// Secure-storage key of the access token.
const kAccessTokenKey = 'jwt_token';

/// Secure-storage key of the refresh token.
const kRefreshTokenKey = 'refresh_token';

/// Home route of a signed-in user: OWNER → `/dashboard`, anyone else
/// (EMPLOYEE, unknown role) → `/pos`.
String routeForRole(String? role) => role == 'OWNER' ? '/dashboard' : '/pos';

/// The session decision shared by [hasSession] and `SessionPresenceCache`:
/// a valid access token, or an expired one that a stored refresh token can
/// renew. [hasRefreshToken] is only consulted when the access token expired.
Future<bool> isSessionUsable(
  String? accessToken, {
  required Future<bool> Function() hasRefreshToken,
  DateTime? now,
}) async {
  final claims = JwtClaims.tryParse(accessToken);
  if (claims == null) return false;
  if (!claims.isExpired(now: now)) return true;
  return hasRefreshToken();
}

/// Whether the device holds a usable session: a valid access token, or an
/// expired one that the stored refresh token can renew (the HTTP layer renews
/// it before the next request; offline, the user keeps working meanwhile).
///
/// Always reads secure storage; the router goes through the cached
/// `SessionPresenceCache` instead.
Future<bool> hasSession(FlutterSecureStorage storage) async {
  return isSessionUsable(
    await storage.read(key: kAccessTokenKey),
    hasRefreshToken: () async =>
        await storage.read(key: kRefreshTokenKey) != null,
  );
}

/// Decides where the app opens.
///
/// A signed-in user is never sent back to login while their refresh token
/// works: an expired access token is renewed, and when the server cannot be
/// reached the session is kept so the shop can keep selling offline. Only a
/// refusal of the refresh token by the server ends the session.
///
/// A platform super-admin session (web admin dashboard only — installed by
/// app versions that let it through) is ended the same way, and
/// [onPlatformAdminSessionRefused] is told so the user sees why.
Future<String> resolveStartupRoute({
  required FlutterSecureStorage storage,
  required SharedPreferences prefs,
  required SessionRefresher refresher,
  required Future<void> Function(String accessToken) applyClaims,
  void Function()? onPlatformAdminSessionRefused,
}) async {
  var token = await storage.read(key: kAccessTokenKey);
  if (token != null && JwtClaims.tryParse(token) == null) {
    await storage.delete(key: kAccessTokenKey);
    markSessionSecretsChanged();
    token = null;
  }

  if (token != null && JwtClaims.tryParse(token)!.isExpired()) {
    switch (await refresher.refresh()) {
      case RefreshOutcome.renewed:
        token = await storage.read(key: kAccessTokenKey);
      case RefreshOutcome.unreachable:
        break; // keep the expired token: offline session
      case RefreshOutcome.rejected:
        // Same session end as the HTTP layer: every session secret goes,
        // device secrets (DB key, device id) stay.
        await clearSessionSecrets(storage);
        token = null;
    }
  }

  final claims = JwtClaims.tryParse(token);
  if (token == null || claims == null) return _signedOutRoute(prefs);

  if (claims.isPlatformAdmin) {
    await clearSessionSecrets(storage);
    await prefs.remove(kUserRoleKey);
    onPlatformAdminSessionRefused?.call();
    return _signedOutRoute(prefs);
  }

  await applyClaims(token);

  // The server says whether the setup wizard was completed. A wizard finished
  // on this device wins over a claim of false: the registration token says
  // false and is only replaced when the session is renewed. tenantStatus is
  // ACTIVE as soon as the account exists, so it is only a fallback for tokens
  // issued before the claim existed.
  final completedHere = prefs.getBool(kOnboardingWizardCompletedKey) ?? false;
  final wizardDone = completedHere ||
      (claims.onboardingCompleted ??
          ((prefs.getBool(kOnboardingWizardSeenKey) ?? false) ||
              claims.tenantStatus == 'ACTIVE'));
  await prefs.setBool(kOnboardingWizardSeenKey, wizardDone);
  if (!wizardDone) return '/onboarding/sector';

  if (prefs.getBool(kPasswordChangeRequiredKey) ?? false) {
    return '/auth/change-password';
  }
  return routeForRole(claims.role);
}

String _signedOutRoute(SharedPreferences prefs) {
  final onboardingSeen = prefs.getBool(kOnboardingSeenKey) ?? false;
  final termsAccepted = prefs.getBool(kTermsAcceptedKey) ?? false;
  // onboarding_seen is written on "Commencer", before the CGU: leaving the
  // terms page without accepting must bring the user back to it.
  if (!onboardingSeen) return '/onboarding';
  return termsAccepted ? '/auth/login' : '/terms';
}
