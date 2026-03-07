import 'package:flutter_riverpod/flutter_riverpod.dart';

/// AccountStatus — reflects the tenant's account state from the JWT or subscription.
///
/// Used by [SuspensionBanner] to show contextual banners.
enum AccountStatus {
  /// Tenant is active — no banner shown.
  active,

  /// Tenant is manually suspended by Super Admin — writes are blocked.
  /// Source: JWT claim `tenantStatus = SUSPENDED`.
  suspended,

  /// Tenant was on PREMIUM_TRIAL but it expired → downgraded to FREE.
  /// Source: subscription planType was PREMIUM_TRIAL and status = EXPIRED.
  trialExpired,
}

/// [accountStatusProvider] — provides the current account status.
///
/// Default is [AccountStatus.active]. Override in tests or inject from the
/// auth state (JWT claims) in the full implementation.
///
/// Phase 2: replace with an AsyncNotifier that reads the JWT `tenantStatus`
/// claim from [TokenStorage] and/or the subscription expires_at field.
final accountStatusProvider = Provider<AccountStatus>(
  (ref) => AccountStatus.active,
);
