import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/domain/repository/token_storage.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import 'subscription_info_provider.dart';

/// AccountStatus — reflects the tenant's account state from the JWT or subscription.
///
/// Used by [SuspensionBanner] to show contextual banners.
enum AccountStatus {
  /// Tenant is active — no banner shown.
  active,

  /// Tenant is manually suspended by Super Admin — writes are blocked.
  /// Source: JWT claim `tenantStatus = SUSPENDED` (decoded via TokenStorage).
  suspended,

  /// Tenant was on PREMIUM_TRIAL but it expired → downgraded to FREE.
  /// Source: subscriptionInfoProvider.planType == 'FREE' (Story 1.6 guarantees
  /// FREE only occurs via trial expiry — no direct-to-FREE registration path).
  trialExpired,
}

/// Decodes the `tenantStatus` claim from the stored JWT access token.
///
/// Returns `null` if the token is absent, malformed, or the claim is missing.
/// Mirroring the JWT-decode pattern used in app_router.dart:_isTenantActive
/// and auth_provider.dart:_extractFirstNameFromJwt.
Future<String?> _readTenantStatusClaim(TokenStorage storage) async {
  final token = await storage.getToken();
  if (token == null) return null;

  final parts = token.split('.');
  if (parts.length != 3) return null;

  try {
    final payload = utf8.decode(
      base64Url.decode(base64Url.normalize(parts[1])),
    );
    final claims = jsonDecode(payload) as Map<String, dynamic>;
    return claims['tenantStatus'] as String?;
  } catch (_) {
    return null;
  }
}

/// FutureProvider that asynchronously reads the tenantStatus claim.
///
/// Public so tests can `await .future` before reading [accountStatusProvider].
/// Invalidated at every auth transition (login, tenant selection, logout —
/// see auth_provider.dart/settings_page.dart) so a stale `suspended` claim
/// from a previous session never leaks into the next one on a shared device.
/// Note: [subscriptionInfoProvider] (used for `trialExpired`) is NOT
/// invalidated here — it is a pre-existing provider with its own network
/// fetch, and eagerly invalidating it on every auth transition triggers an
/// unconditional API call; same staleness characteristic it already had
/// before this story (tracked as a defer, see deferred-work.md).
final tenantStatusClaimProvider = FutureProvider<String?>(
  (ref) => _readTenantStatusClaim(ref.watch(tokenStorageProvider)),
);

/// [accountStatusProvider] — provides the current account status.
///
/// Derived from two sources (evaluated in priority order):
/// 1. JWT claim `tenantStatus` == 'SUSPENDED' → [AccountStatus.suspended]
/// 2. Subscription `planType` == 'FREE'       → [AccountStatus.trialExpired]
/// 3. Otherwise                                → [AccountStatus.active]
///
/// Falls back to [AccountStatus.active] while data is loading or on any error,
/// matching the previous stub behaviour (no phantom banners).
final accountStatusProvider = Provider<AccountStatus>((ref) {
  final tenantStatus = ref.watch(tenantStatusClaimProvider).valueOrNull;
  if (tenantStatus == 'SUSPENDED') return AccountStatus.suspended;

  final planType = ref.watch(subscriptionInfoProvider).valueOrNull?.planType;
  if (planType == 'FREE') return AccountStatus.trialExpired;

  return AccountStatus.active;
});
