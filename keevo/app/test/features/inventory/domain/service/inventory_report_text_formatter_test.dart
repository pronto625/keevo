import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/domain/service/inventory_report_text_formatter.dart';

void main() {
  final report = InventoryGapReportModel(
    sessionId: 's1',
    storeId: 'st1',
    storeName: 'Boutique Centre',
    scope: 'FULL',
    summary: const InventoryGapSummaryModel(
      totalCounted: 5,
      totalConcordant: 2,
      totalSurplus: 1,
      totalShortage: 2,
      totalSurplusValueXaf: 3000,
      totalShortageValueXaf: 47500,
    ),
    concordantRows: const [],
    surplusRows: [
      InventoryGapRowModel(
        productId: 'p3',
        productName: 'Huile',
        theoretical: 5,
        physical: 8,
        ecart: 3,
        unitPriceXaf: 1000,
        gapValueXaf: 3000,
      ),
    ],
    shortageRows: [
      InventoryGapRowModel(
        productId: 'p1',
        productName: 'Savon',
        theoretical: 10,
        physical: 5,
        ecart: -5,
        unitPriceXaf: 5000,
        gapValueXaf: 25000,
      ),
      InventoryGapRowModel(
        productId: 'p2',
        productName: 'Bière 33',
        theoretical: 20,
        physical: 15,
        ecart: -5,
        unitPriceXaf: 4500,
        gapValueXaf: 22500,
      ),
    ],
    generatedAt: DateTime(2025, 1, 15, 14, 30),
  );

  test('WhatsApp format matches expected emoji pattern', () {
    final text = formatWhatsAppReport(report, 'Jean');

    expect(text, contains('📋 Rapport d\'inventaire — Boutique Centre'));
    expect(text, contains('📅 15 janvier 2025 — 14h30'));
    expect(text, contains('👤 Jean'));
    expect(text, contains('✅ Concordants : 2 produits'));
    expect(text, contains('🔴 Manquants : 2 produits'));
    expect(text, contains('Top manques :'));
  });

  test('top 5 shortages only in WhatsApp text', () {
    // Create report with 7 shortages
    final bigReport = InventoryGapReportModel(
      sessionId: 's1',
      storeId: 'st1',
      storeName: 'Test',
      scope: 'FULL',
      summary: const InventoryGapSummaryModel(
        totalCounted: 7,
        totalConcordant: 0,
        totalSurplus: 0,
        totalShortage: 7,
        totalSurplusValueXaf: 0,
        totalShortageValueXaf: 70000,
      ),
      concordantRows: const [],
      surplusRows: const [],
      shortageRows: List.generate(
        7,
        (i) => InventoryGapRowModel(
          productId: 'p$i',
          productName: 'Produit $i',
          theoretical: 10,
          physical: 5,
          ecart: -5,
          unitPriceXaf: 2000,
          gapValueXaf: 10000,
        ),
      ),
      generatedAt: DateTime(2025, 1, 15, 14, 0),
    );

    final text = formatWhatsAppReport(bigReport, 'Admin');
    final bulletCount = '•'.allMatches(text).length;
    expect(bulletCount, equals(5));
  });

  test('XAF format with space thousands separator', () {
    expect(formatXaf(1500), equals('1 500 FCFA'));
    expect(formatXaf(47500), equals('47 500 FCFA'));
    expect(formatXaf(1000000), equals('1 000 000 FCFA'));
    expect(formatXaf(500), equals('500 FCFA'));
    expect(formatXaf(0), equals('0 FCFA'));
  });
}
