/// AccountProfile — Domain model for the authenticated user's profile (Story 8.6 AC3, AC4).
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

  const AccountProfile({
    required this.userId,
    required this.phoneNumber,
    required this.role,
    this.firstName,
    this.lastName,
    this.storeId,
    this.storeName,
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
    );
  }
}
