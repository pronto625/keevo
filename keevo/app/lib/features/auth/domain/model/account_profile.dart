/// AccountProfile — Domain model for the authenticated user's profile (Story 8.6 AC3, AC4).
///
/// Story 16.11 — the app's source of role, store, first name, password-change
/// flag, account status and onboarding state (the access token proves identity
/// only). Cached on the device for offline display; the server decides.
///
/// EMPLOYEE: firstName, lastName, storeId, storeName populated.
/// OWNER: firstName, lastName, storeId, storeName are null.
class AccountProfile {
  final String userId;
  final String phoneNumber;
  final String role;
  final String? firstName;
  final String? lastName;
  final String? storeId;
  final String? storeName;
  final bool passwordChangeRequired;
  final String? tenantStatus;
  final bool? onboardingCompleted;

  const AccountProfile({
    required this.userId,
    required this.phoneNumber,
    required this.role,
    this.firstName,
    this.lastName,
    this.storeId,
    this.storeName,
    this.passwordChangeRequired = false,
    this.tenantStatus,
    this.onboardingCompleted,
  });

  factory AccountProfile.fromJson(Map<String, dynamic> json) {
    return AccountProfile(
      userId: json['userId'] as String,
      phoneNumber: json['phoneNumber'] as String,
      role: json['role'] as String,
      firstName: json['firstName'] as String?,
      lastName: json['lastName'] as String?,
      storeId: json['storeId'] as String?,
      storeName: json['storeName'] as String?,
      passwordChangeRequired: json['passwordChangeRequired'] == true,
      tenantStatus: json['tenantStatus'] as String?,
      onboardingCompleted: json['onboardingCompleted'] as bool?,
    );
  }
}
