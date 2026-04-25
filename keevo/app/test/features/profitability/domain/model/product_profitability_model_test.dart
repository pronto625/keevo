// ignore_for_file: avoid_redundant_argument_values
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/theme/app_theme.dart';
import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';

void main() {
  // ── ProductProfitabilityEntry ───────────────────────────────────────────────

  group('ProductProfitabilityEntry', () {
    test('fromJson_parsesAllFields', () {
      final json = {
        'productId': '11111111-1111-1111-1111-111111111111',
        'productName': 'Café Arabica 250g',
        'categoryName': 'Boissons',
        'unitsSold': 10,
        'totalRevenue': 50000,
        'totalCost': 30000,
        'grossMarginXaf': 20000,
        'marginPercent': 66.67,
        'isLoss': false,
        'storeId': null,
        'marginLevel': 'PROFITABLE',
      };

      final entry = ProductProfitabilityEntry.fromJson(json);

      expect(entry.productId, '11111111-1111-1111-1111-111111111111');
      expect(entry.productName, 'Café Arabica 250g');
      expect(entry.categoryName, 'Boissons');
      expect(entry.unitsSold, 10);
      expect(entry.totalRevenue, 50000);
      expect(entry.totalCost, 30000);
      expect(entry.grossMarginXaf, 20000);
      expect(entry.marginPercent, closeTo(66.67, 0.01));
      expect(entry.isLoss, false);
    });

    test('marginLevel_returnsLoss_whenNegativeMargin', () {
      const entry = ProductProfitabilityEntry(
        productId: 'p1',
        productName: 'Produit',
        categoryName: null,
        unitsSold: 5,
        totalRevenue: 10000,
        totalCost: 12000,
        grossMarginXaf: -2000,
        marginPercent: -16.67,
        isLoss: true,
        storeId: null,
        marginLevel: 'LOSS',
      );

      expect(entry.marginColor, AppTheme.errorColor);
    });

    test('marginLevel_returnsLow_whenBelow10Percent', () {
      const entry = ProductProfitabilityEntry(
        productId: 'p1',
        productName: 'Produit',
        categoryName: null,
        unitsSold: 5,
        totalRevenue: 10000,
        totalCost: 9500,
        grossMarginXaf: 500,
        marginPercent: 5.26,
        isLoss: false,
        storeId: null,
        marginLevel: 'LOW',
      );

      expect(entry.marginColor, AppTheme.errorColor);
    });

    test('marginLevel_returnsModerate_when10to19Percent', () {
      const entry = ProductProfitabilityEntry(
        productId: 'p1',
        productName: 'Produit',
        categoryName: null,
        unitsSold: 5,
        totalRevenue: 10000,
        totalCost: 8500,
        grossMarginXaf: 1500,
        marginPercent: 17.65,
        isLoss: false,
        storeId: null,
        marginLevel: 'MODERATE',
      );

      expect(entry.marginColor, AppTheme.warning);
    });

    test('marginLevel_returnsProfitable_when20PlusPercent', () {
      const entry = ProductProfitabilityEntry(
        productId: 'p1',
        productName: 'Produit',
        categoryName: null,
        unitsSold: 5,
        totalRevenue: 15000,
        totalCost: 4500,
        grossMarginXaf: 10500,
        marginPercent: 233.33,
        isLoss: false,
        storeId: null,
        marginLevel: 'PROFITABLE',
      );

      expect(entry.marginColor, AppTheme.success);
    });

    test('marginLevel_color_matchesPricingCalculatorWidget', () {
      // Mirrors _MarginLevel enum in pricing_calculator_widget.dart:
      // LOSS/LOW → red, MODERATE → orange, PROFITABLE → green
      final cases = [
        ('LOSS', AppTheme.errorColor),
        ('LOW', AppTheme.errorColor),
        ('MODERATE', AppTheme.warning),
        ('PROFITABLE', AppTheme.success),
      ];

      for (final (level, expected) in cases) {
        final entry = ProductProfitabilityEntry(
          productId: 'p',
          productName: 'P',
          categoryName: null,
          unitsSold: 1,
          totalRevenue: 1000,
          totalCost: 500,
          grossMarginXaf: 500,
          marginPercent: 100,
          isLoss: level == 'LOSS',
          storeId: null,
          marginLevel: level,
        );
        expect(entry.marginColor, expected,
            reason: 'level=$level should map to $expected');
      }
    });
  });

  // ── ProductProfitabilityDetail ─────────────────────────────────────────────

  group('ProductProfitabilityDetail', () {
    test('productProfitabilityDetail_fromJson_includesDailySparkline', () {
      final json = {
        'productId': '22222222-2222-2222-2222-222222222222',
        'productName': 'Thé vert',
        'categoryName': null,
        'unitsSold': 20,
        'totalRevenue': 40000,
        'totalCost': 20000,
        'grossMarginXaf': 20000,
        'marginPercent': 100.0,
        'isLoss': false,
        'storeId': null,
        'marginLevel': 'PROFITABLE',
        'currentCataloguePrice': 2000,
        'currentBuyPrice': 800,
        'currentTransportCost': 200,
        'minAppliedPrice': 1800,
        'maxAppliedPrice': 2100,
        'avgAppliedPrice': 2000.0,
        'dailyMarginLast7': [
          {'date': '2026-03-25', 'marginXaf': 3000},
          {'date': '2026-03-26', 'marginXaf': 4000},
        ],
        'topStoreId': '33333333-3333-3333-3333-333333333333',
        'topStoreName': 'Boutique Nord',
        'topStoreUnitsSold': 12,
      };

      final detail = ProductProfitabilityDetail.fromJson(json);

      expect(detail.productName, 'Thé vert');
      expect(detail.currentCataloguePrice, 2000);
      expect(detail.dailyMarginLast7, hasLength(2));
      expect(detail.dailyMarginLast7.first.date, '2026-03-25');
      expect(detail.dailyMarginLast7.first.marginXaf, 3000);
      expect(detail.topStoreName, 'Boutique Nord');
    });
  });

  // ── StorePerformanceEntry ──────────────────────────────────────────────────

  group('StorePerformanceEntry', () {
    test('storePerformanceEntry_fromJson_includesRankAndDelta', () {
      final json = {
        'rank': 1,
        'storeId': '44444444-4444-4444-4444-444444444444',
        'storeName': 'Magasin Central',
        'totalRevenue': 500000,
        'salesCount': 50,
        'averageBasket': 10000,
        'topProductName': 'Café Arabica',
        'deltaPercent': 8.0,
      };

      final entry = StorePerformanceEntry.fromJson(json);

      expect(entry.rank, 1);
      expect(entry.storeName, 'Magasin Central');
      expect(entry.totalRevenue, 500000);
      expect(entry.salesCount, 50);
      expect(entry.averageBasket, 10000);
      expect(entry.topProductName, 'Café Arabica');
      expect(entry.deltaPercent, closeTo(8.0, 0.01));
    });
  });
}
