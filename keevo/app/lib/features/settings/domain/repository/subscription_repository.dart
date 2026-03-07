import '../model/subscription_info.dart';

/// SubscriptionRepository — port: fetches subscription data for the current tenant.
abstract interface class SubscriptionRepository {
  /// Calls GET /api/v1/subscription/me and returns the current subscription state.
  Future<SubscriptionInfo> getMySubscription();
}
