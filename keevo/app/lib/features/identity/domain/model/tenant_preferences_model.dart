import '../../../onboarding/domain/model/sector_type.dart';

/// Normalise une heure en format HH:mm:ss — Java sérialise LocalTime.of(20,0) → "20:00" sans secondes.
String _normalizeTime(String? t, [String fallback = '20:00:00']) {
  if (t == null || t.isEmpty) return fallback;
  final parts = t.split(':');
  if (parts.length == 2) return '$t:00';
  return t;
}

/// TenantPreferencesModel — Modèle des préférences du tenant.
///
/// Story 7.5: ajout des champs de configuration des rapports et WhatsApp.
class TenantPreferencesModel {
  final SectorType? sectorType;
  final String eodReportTime;
  final bool stockAlertEnabled;
  final DateTime createdAt;

  // Story 7.5 — Report & WhatsApp preferences
  final bool eodReportEnabled;
  final String eodReportChannel;
  final bool weeklyReportEnabled;
  final int weeklyReportDay;
  final String weeklyReportTime;
  final String weeklyReportChannel;
  final bool inventoryReportEnabled;
  final String inventoryReportChannel;
  final String stockAlertChannel;

  const TenantPreferencesModel({
    required this.sectorType,
    required this.eodReportTime,
    required this.stockAlertEnabled,
    required this.createdAt,
    this.eodReportEnabled = true,
    this.eodReportChannel = 'WHATSAPP',
    this.weeklyReportEnabled = true,
    this.weeklyReportDay = 0,
    this.weeklyReportTime = '20:00:00',
    this.weeklyReportChannel = 'WHATSAPP',
    this.inventoryReportEnabled = true,
    this.inventoryReportChannel = 'WHATSAPP',
    this.stockAlertChannel = 'PUSH',
  });

  /// Factory from API response
  factory TenantPreferencesModel.fromJson(Map<String, dynamic> json) {
    SectorType? sectorType;
    final sectorTypeString = json['sectorType'] as String?;
    if (sectorTypeString != null) {
      for (final sector in SectorType.values) {
        if (sector.apiCode == sectorTypeString) {
          sectorType = sector;
          break;
        }
      }
    }

    return TenantPreferencesModel(
      sectorType: sectorType,
      eodReportTime: _normalizeTime(json['eodReportTime'] as String?),
      stockAlertEnabled: json['stockAlertEnabled'] as bool? ?? true,
      createdAt: DateTime.parse(json['createdAt'] as String),
      eodReportEnabled: json['eodReportEnabled'] as bool? ?? false,
      eodReportChannel: json['eodReportChannel'] as String? ?? 'WHATSAPP',
      weeklyReportEnabled: json['weeklyReportEnabled'] as bool? ?? false,
      weeklyReportDay: json['weeklyReportDay'] as int? ?? 5,
      weeklyReportTime: _normalizeTime(json['weeklyReportTime'] as String?, '08:00:00'),
      weeklyReportChannel: json['weeklyReportChannel'] as String? ?? 'WHATSAPP',
      inventoryReportEnabled: json['inventoryReportEnabled'] as bool? ?? false,
      inventoryReportChannel: json['inventoryReportChannel'] as String? ?? 'WHATSAPP',
      stockAlertChannel: json['stockAlertChannel'] as String? ?? 'PUSH',
    );
  }

  /// Convert to JSON for API requests
  Map<String, dynamic> toJson() {
    return {
      'sectorType': sectorType?.apiCode,
      'eodReportTime': eodReportTime,
      'stockAlertEnabled': stockAlertEnabled,
      'createdAt': createdAt.toIso8601String(),
      'eodReportEnabled': eodReportEnabled,
      'eodReportChannel': eodReportChannel,
      'weeklyReportEnabled': weeklyReportEnabled,
      'weeklyReportDay': weeklyReportDay,
      'weeklyReportTime': weeklyReportTime,
      'weeklyReportChannel': weeklyReportChannel,
      'inventoryReportEnabled': inventoryReportEnabled,
      'inventoryReportChannel': inventoryReportChannel,
      'stockAlertChannel': stockAlertChannel,
    };
  }

  TenantPreferencesModel copyWith({
    SectorType? sectorType,
    String? eodReportTime,
    bool? stockAlertEnabled,
    DateTime? createdAt,
    bool? eodReportEnabled,
    String? eodReportChannel,
    bool? weeklyReportEnabled,
    int? weeklyReportDay,
    String? weeklyReportTime,
    String? weeklyReportChannel,
    bool? inventoryReportEnabled,
    String? inventoryReportChannel,
    String? stockAlertChannel,
  }) {
    return TenantPreferencesModel(
      sectorType: sectorType ?? this.sectorType,
      eodReportTime: eodReportTime ?? this.eodReportTime,
      stockAlertEnabled: stockAlertEnabled ?? this.stockAlertEnabled,
      createdAt: createdAt ?? this.createdAt,
      eodReportEnabled: eodReportEnabled ?? this.eodReportEnabled,
      eodReportChannel: eodReportChannel ?? this.eodReportChannel,
      weeklyReportEnabled: weeklyReportEnabled ?? this.weeklyReportEnabled,
      weeklyReportDay: weeklyReportDay ?? this.weeklyReportDay,
      weeklyReportTime: weeklyReportTime ?? this.weeklyReportTime,
      weeklyReportChannel: weeklyReportChannel ?? this.weeklyReportChannel,
      inventoryReportEnabled: inventoryReportEnabled ?? this.inventoryReportEnabled,
      inventoryReportChannel: inventoryReportChannel ?? this.inventoryReportChannel,
      stockAlertChannel: stockAlertChannel ?? this.stockAlertChannel,
    );
  }
}