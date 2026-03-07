/// SubscriptionInfo — domain model for the current tenant's subscription state.
///
/// Returned by [GET /api/v1/subscription/me].
/// [maxStores], [maxProducts], [maxEmployees] are null when the plan is unlimited.
class SubscriptionInfo {
  final String planType; // 'FREE' | 'PREMIUM_TRIAL' | 'PREMIUM'
  final String status; // 'ACTIVE' | 'SUSPENDED' | 'EXPIRED'
  final String? expiresAt; // ISO-8601 string or null (FREE plan has no expiry)
  final int currentStores;
  final int? maxStores; // null = unlimited
  final int currentProducts;
  final int? maxProducts;
  final int currentEmployees;
  final int? maxEmployees;

  const SubscriptionInfo({
    required this.planType,
    required this.status,
    this.expiresAt,
    required this.currentStores,
    this.maxStores,
    required this.currentProducts,
    this.maxProducts,
    required this.currentEmployees,
    this.maxEmployees,
  });

  factory SubscriptionInfo.fromJson(Map<String, dynamic> json) {
    return SubscriptionInfo(
      planType: json['planType'] as String,
      status: json['status'] as String,
      expiresAt: json['expiresAt'] as String?,
      currentStores: json['currentStores'] as int? ?? 0,
      maxStores: json['maxStores'] as int?,
      currentProducts: json['currentProducts'] as int? ?? 0,
      maxProducts: json['maxProducts'] as int?,
      currentEmployees: json['currentEmployees'] as int? ?? 0,
      maxEmployees: json['maxEmployees'] as int?,
    );
  }

  /// Returns true if the tenant has an unlimited plan.
  bool get isUnlimited => maxStores == null;
}
