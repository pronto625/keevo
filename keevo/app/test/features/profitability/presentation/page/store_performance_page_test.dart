import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/profitability/presentation/widget/offline_data_banner.dart';
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

    // AC5: the metric toggle re-ranks locally — the repository is queried once
    // and the rank badges are renumbered.
    testWidgets('tapMetricToggle_reranksLocally_withoutRefetching',
        (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .thenAnswer((_) async => [
                const StorePerformanceEntry(
                  rank: 1,
                  storeId: 's1',
                  storeName: 'Magasin Central',
                  totalRevenue: 500000,
                  salesCount: 10,
                  averageBasket: 50000,
                  topProductName: null,
                  deltaPercent: 0,
                ),
                const StorePerformanceEntry(
                  rank: 2,
                  storeId: 's2',
                  storeName: 'Boutique Nord',
                  totalRevenue: 300000,
                  salesCount: 60,
                  averageBasket: 5000,
                  topProductName: null,
                  deltaPercent: 0,
                ),
              ]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('Magasin Central')).dy,
          lessThan(tester.getTopLeft(find.text('Boutique Nord')).dy));

      await tester.tap(find.text('Ventes'));
      await tester.pumpAndSettle();

      // Boutique Nord has more sales → now #1, listed first.
      expect(tester.getTopLeft(find.text('Boutique Nord')).dy,
          lessThan(tester.getTopLeft(find.text('Magasin Central')).dy));
      expect(tester.getTopLeft(find.text('#1')).dy,
          lessThan(tester.getTopLeft(find.text('#2')).dy));
      verify(() => mockRepo.getStorePerformance(params: any(named: 'params')))
          .called(1);
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

  testWidgets('offline device → shared OfflineDataBanner', (tester) async {
    final mockRepo = MockProfitabilityRepository();
    when(() => mockRepo.getStorePerformance(params: any(named: 'params')))
        .thenAnswer((_) async => makeStores());

    await tester.pumpWidget(ProviderScope(
      overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        syncStatusProvider
            .overrideWith((_) => Stream.value(SyncStatus.offlineOk)),
      ],
      child: const MaterialApp(home: StorePerformancePage()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsOneWidget);
  });
}
