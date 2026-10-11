import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../auth/jwt_claims.dart';
import '../auth/session_refresher.dart';
import '../di/providers.dart';
import '../storage/app_constants.dart';
import '../storage/secure_storage_provider.dart';

const _kAccessTokenKey = 'jwt_token';
const _kRefreshTokenKey = 'refresh_token';

/// Whether the device holds a usable session: a valid access token, or an
/// expired one that the stored refresh token can renew (the HTTP layer renews
/// it before the next request; offline, the user keeps working meanwhile).
Future<bool> hasSession(FlutterSecureStorage storage) async {
  final claims = JwtClaims.tryParse(await storage.read(key: _kAccessTokenKey));
  if (claims == null) return false;
  if (!claims.isExpired()) return true;
  return await storage.read(key: _kRefreshTokenKey) != null;
}

/// Decides where the app opens.
///
/// A signed-in user is never sent back to login while their refresh token
/// works: an expired access token is renewed, and when the server cannot be
/// reached the session is kept so the shop can keep selling offline. Only a
/// refusal of the refresh token by the server ends the session.
Future<String> resolveStartupRoute({
  required FlutterSecureStorage storage,
  required SharedPreferences prefs,
  required SessionRefresher refresher,
  required Future<void> Function(String accessToken) applyClaims,
}) async {
  var token = await storage.read(key: _kAccessTokenKey);
  if (token != null && JwtClaims.tryParse(token) == null) {
    await storage.delete(key: _kAccessTokenKey);
    token = null;
  }

  if (token != null && JwtClaims.tryParse(token)!.isExpired()) {
    switch (await refresher.refresh()) {
      case RefreshOutcome.renewed:
        token = await storage.read(key: _kAccessTokenKey);
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
  return claims.role == 'OWNER' ? '/dashboard' : '/pos';
}

String _signedOutRoute(SharedPreferences prefs) {
  final onboardingSeen = prefs.getBool(kOnboardingSeenKey) ?? false;
  final termsAccepted = prefs.getBool(kTermsAcceptedKey) ?? false;
  // onboarding_seen is written on "Commencer", before the CGU: leaving the
  // terms page without accepting must bring the user back to it.
  if (!onboardingSeen) return '/onboarding';
  return termsAccepted ? '/auth/login' : '/terms';
}
