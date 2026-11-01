import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';

import 'subscription_info_provider.dart';

/// AccountStatus — reflects the tenant's account state from the profile or subscription.
///
/// Used by [SuspensionBanner] to show contextual banners.
enum AccountStatus {
  /// Tenant is active — no banner shown.
  active,

  /// Tenant is manually suspended by Super Admin — writes are blocked.
  /// Source: the profile's account status `SUSPENDED` (Story 16.11).
  suspended,

  /// Tenant was on PREMIUM_TRIAL but it expired → downgraded to FREE.
  /// Source: subscriptionInfoProvider.planType == 'FREE' (Story 1.6 guarantees
  /// FREE only occurs via trial expiry — no direct-to-FREE registration path).
  trialExpired,
}

/// The account status (`ACTIVE`, `SUSPENDED`, `DELETION_PENDING`…) of the
/// cached profile — Story 16.11: read from `GET /api/v1/auth/profile` with
/// every session, never from the token.
///
/// Public so tests can `await .future` before reading [accountStatusProvider].
/// Invalidated at every auth transition (login, tenant selection, logout —
/// see auth_provider.dart/settings_page.dart) so a stale `suspended` claim
/// from a previous session never leaks into the next one on a shared device.
/// [subscriptionInfoProvider] (used for `trialExpired`) is invalidated on a
/// new session and on sign-out (v1s-16-13), and [accountStatusProvider]
/// ignores it while it reloads, so a previous tenant's plan never shows.
final tenantStatusClaimProvider = FutureProvider<String?>(
  (ref) async => ref.watch(sharedPreferencesProvider).getString(kTenantStatusKey),
);

/// [accountStatusProvider] — provides the current account status.
///
/// Derived from two sources (evaluated in priority order):
/// 1. Profile account status == 'SUSPENDED'  → [AccountStatus.suspended]
/// 2. Subscription `planType` == 'FREE'       → [AccountStatus.trialExpired]
/// 3. Otherwise                                → [AccountStatus.active]
///
/// Falls back to [AccountStatus.active] while data is loading or on any error,
/// matching the previous stub behaviour (no phantom banners).
final accountStatusProvider = Provider<AccountStatus>((ref) {
  final tenantStatus = ref.watch(tenantStatusClaimProvider).value;
  if (tenantStatus == 'SUSPENDED') return AccountStatus.suspended;

  // v1s-16-13: while the subscription reloads (after an auth transition) or
  // failed, its previous value may belong to another tenant — ignore it.
  final subscription = ref.watch(subscriptionInfoProvider);
  final planType = (subscription.isLoading || subscription.hasError)
      ? null
      : subscription.value?.planType;
  if (planType == 'FREE') return AccountStatus.trialExpired;

  return AccountStatus.active;
});
