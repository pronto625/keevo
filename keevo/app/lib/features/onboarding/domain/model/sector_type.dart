import 'package:flutter/material.dart';

/// SectorType — Domain enum representing the merchant's business sector.
///
/// Each value carries UI metadata (emoji, label, selectedColor) and the
/// [apiCode] string sent to the backend POST /api/v1/onboarding/complete.
enum SectorType {
  clothing(
    apiCode: 'CLOTHING',
    emoji: '👔',
    label: 'Vêtements',
    selectedColor: Color(0xFFD0EBFF),
  ),
  electronics(
    apiCode: 'ELECTRONICS',
    emoji: '📱',
    label: 'Électronique',
    selectedColor: Color(0xFFD0EBFF),
  ),
  booksStationery(
    apiCode: 'BOOKS_STATIONERY',
    emoji: '📚',
    label: 'Librairie / Papeterie',
    selectedColor: Color(0xFFD0EBFF),
  ),
  homeAppliances(
    apiCode: 'HOME_APPLIANCES',
    emoji: '🏠',
    label: 'Électroménager',
    selectedColor: Color(0xFFD0EBFF),
  ),
  foodGrocery(
    apiCode: 'FOOD_GROCERY',
    emoji: '🛒',
    label: 'Alimentation',
    selectedColor: Color(0xFFD0EBFF),
  ),
  pharmacy(
    apiCode: 'PHARMACY',
    emoji: '💊',
    label: 'Pharmacie',
    selectedColor: Color(0xFFD0EBFF),
  ),
  hardware(
    apiCode: 'HARDWARE',
    emoji: '🔧',
    label: 'Quincaillerie',
    selectedColor: Color(0xFFD0EBFF),
  ),
  other(
    apiCode: 'OTHER',
    emoji: '🏪',
    label: 'Autre',
    selectedColor: Color(0xFFD0EBFF),
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
