import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';

/// SectorType — Domain enum representing the merchant's business sector.
///
/// Each value carries UI metadata (emoji, label, selectedColor) and the
/// [apiCode] string sent to the backend POST /api/v1/onboarding/complete.
enum SectorType {
  clothing(
    apiCode: 'CLOTHING',
    emoji: '👔',
    label: 'Vêtements',
    selectedColor: AppTheme.iconBlueBg,
  ),
  electronics(
    apiCode: 'ELECTRONICS',
    emoji: '📱',
    label: 'Électronique',
    selectedColor: AppTheme.iconBlueBg,
  ),
  booksStationery(
    apiCode: 'BOOKS_STATIONERY',
    emoji: '📚',
    label: 'Librairie / Papeterie',
    selectedColor: AppTheme.iconBlueBg,
  ),
  homeAppliances(
    apiCode: 'HOME_APPLIANCES',
    emoji: '🏠',
    label: 'Électroménager',
    selectedColor: AppTheme.iconBlueBg,
  ),
  foodGrocery(
    apiCode: 'FOOD_GROCERY',
    emoji: '🛒',
    label: 'Alimentation',
    selectedColor: AppTheme.iconBlueBg,
  ),
  pharmacy(
    apiCode: 'PHARMACY',
    emoji: '💊',
    label: 'Pharmacie',
    selectedColor: AppTheme.iconBlueBg,
  ),
  hardware(
    apiCode: 'HARDWARE',
    emoji: '🔧',
    label: 'Quincaillerie',
    selectedColor: AppTheme.iconBlueBg,
  ),
  other(
    apiCode: 'OTHER',
    emoji: '🏪',
    label: 'Autre',
    selectedColor: AppTheme.iconBlueBg,
  );

  const SectorType({
    required this.apiCode,
    required this.emoji,
    required this.label,
    required this.selectedColor,
  });

  final String apiCode;
  final String emoji;
  final String label;
  final Color selectedColor;

  /// Parses a backend API code string back to a [SectorType].
  static SectorType fromApiCode(String code) {
    return SectorType.values.firstWhere(
      (e) => e.apiCode == code,
      orElse: () => throw ArgumentError('Unknown sector type code: $code'),
    );
  }
}
