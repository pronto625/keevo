import '../../../onboarding/domain/model/sector_type.dart';

/// TenantPreferencesModel — Modèle des préférences du tenant.
///
/// Contient les informations du tenant incluant le secteur d'activité,
/// utilisé pour filtrer les catégories appropriées.
class TenantPreferencesModel {
  final SectorType? sectorType;
  final String eodReportTime;
  final bool stockAlertEnabled;
  final DateTime createdAt;

  const TenantPreferencesModel({
    required this.sectorType,
    required this.eodReportTime,
    required this.stockAlertEnabled,
    required this.createdAt,
  });

  /// Factory from API response
  factory TenantPreferencesModel.fromJson(Map<String, dynamic> json) {
    SectorType? sectorType;
    final sectorTypeString = json['sectorType'] as String?;
    if (sectorTypeString != null) {
      // Find the SectorType enum by its apiCode
      for (final sector in SectorType.values) {
        if (sector.apiCode == sectorTypeString) {
          sectorType = sector;
          break;
        }
      }
    }

    return TenantPreferencesModel(
      sectorType: sectorType,
      eodReportTime: json['eodReportTime'] as String? ?? '20:00:00',
      stockAlertEnabled: json['stockAlertEnabled'] as bool? ?? true,
      createdAt: DateTime.parse(json['createdAt'] as String),
    );
  }

  /// Convert to JSON for API requests
  Map<String, dynamic> toJson() {
    return {
      'sectorType': sectorType?.apiCode,
      'eodReportTime': eodReportTime,
      'stockAlertEnabled': stockAlertEnabled,
      'createdAt': createdAt.toIso8601String(),
    };
  }
}