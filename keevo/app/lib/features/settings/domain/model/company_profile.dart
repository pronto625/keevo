/// Company identity shown on invoices and export documents (Story 14.2).
///
/// [designation] is what the owner typed (null when none); [displayName] is
/// the name actually printed — the designation, else the shop name;
/// [shopName] is the shop name typed at onboarding (null when none), printed
/// once the designation is cleared.
class CompanyProfile {
  final String? designation;
  final String displayName;
  final String? shopName;
  final bool hasLogo;

  const CompanyProfile({
    required this.designation,
    required this.displayName,
    this.shopName,
    required this.hasLogo,
  });

  factory CompanyProfile.fromJson(Map<String, dynamic> json) {
    final designation = json['designation'] as String?;
    final shopName = json['shopName'] as String?;
    return CompanyProfile(
      designation:
          designation == null || designation.trim().isEmpty ? null : designation,
      displayName: (json['displayName'] as String?) ?? 'Mon commerce',
      shopName: shopName == null || shopName.trim().isEmpty ? null : shopName,
      hasLogo: json['hasLogo'] == true,
    );
  }
}
