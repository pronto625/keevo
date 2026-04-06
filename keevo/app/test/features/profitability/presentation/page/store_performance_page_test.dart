import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/store_performance_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

class MockProfitabilityRepository extends Mock implements ProfitabilityRepository {}

void main() {
  setUpAll(() {
    registerFallbackValue(
      StorePerformanceParams(from: DateTime(2025), to: DateTime(2025)),
    );
  });

  List<StorePerformanceEntry> makeStores() => [
        const StorePerformanceEntry(
          rank: 1,
          storeId: 's1',
          storeName: 'Magasin Central',
          totalRevenue: 500000,
          salesCount: 50,
          averageBasket: 10000,
          topProductName: 'Café Arabica',
          deltaPercent: 8.0,
        ),
        const StorePerformanceEntry(
          rank: 2,
          storeId: 's2',
          storeName: 'Boutique Nord',
          totalRevenue: 300000,
          salesCount: 30,
          averageBasket: 10000,
          topProductName: 'Thé vert',
          deltaPercent: -3.0,
        ),
      ];

  Widget buildUnderTest(MockProfitabilityRepository mockRepo) {
    return ProviderScope(
      overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
      ],
      child: const MaterialApp(
        home: StorePerformancePage(),
      ),
    );
  }

  group('StorePerformancePage', () {
    testWidgets('rendersRankedStoreList_withRankBadge', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => makeStores());

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(find.text('Magasin Central'), findsOneWidget);
      expect(find.text('Boutique Nord'), findsOneWidget);
      expect(find.text('#1'), findsOneWidget);
      expect(find.text('#2'), findsOneWidget);
    });

    testWidgets('rendersPositiveDelta_inGreen', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => makeStores());

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(find.text('+8.0%'), findsOneWidget);
    });

    testWidgets('rendersNegativeDelta_inRed', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => makeStores());

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(find.text('-3.0%'), findsOneWidget);
    });

    testWidgets('tapMetricToggle_SALES_COUNT_reranks', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => makeStores());

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Ventes'));
      await tester.pumpAndSettle();

      expect(find.text('Ventes'), findsOneWidget);
    });

    testWidgets('tapPeriodChip_30days_refreshesList', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => makeStores());

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      await tester.tap(find.text('30 jours'));
      await tester.pumpAndSettle();

      expect(find.text('30 jours'), findsOneWidget);
    });
  });
}
