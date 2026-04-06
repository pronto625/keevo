import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/product_profitability_detail_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

class MockProfitabilityRepository extends Mock implements ProfitabilityRepository {}

void main() {
  setUpAll(() {
    registerFallbackValue(
      ProfitabilityParams(from: DateTime(2025), to: DateTime(2025)),
    );
  });

  ProductProfitabilityDetail makeDetail() => const ProductProfitabilityDetail(
        productId: 'p1',
        productName: 'Café Arabica',
        categoryName: 'Boissons',
        unitsSold: 10,
        totalRevenue: 50000,
        totalCost: 30000,
        grossMarginXaf: 20000,
        marginPercent: 66.67,
        isLoss: false,
        storeId: null,
        marginLevel: 'PROFITABLE',
        currentCataloguePrice: 5000,
        currentBuyPrice: 2000,
        currentTransportCost: 500,
        minAppliedPrice: 4500,
        maxAppliedPrice: 5500,
        avgAppliedPrice: 5100,
        dailyMarginLast7: [
          DailyMarginEntry(date: '2026-03-25', marginXaf: 3000),
          DailyMarginEntry(date: '2026-03-26', marginXaf: 4000),
        ],
        topStoreId: 's1',
        topStoreName: 'Boutique Nord',
        topStoreUnitsSold: 6,
      );

  Widget buildUnderTest(
      MockProfitabilityRepository mockRepo, String productId) {
    return ProviderScope(
      overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
      ],
      child: MaterialApp(
        home: ProductProfitabilityDetailPage(productId: productId),
      ),
    );
  }

  group('ProductProfitabilityDetailPage', () {
    testWidgets('rendersHeader_withProductNameAndCurrentPrices', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitabilityDetail(
        productId: 'p1',
        params: any(named: 'params'),
      )).thenAnswer((_) async => makeDetail());

      await tester.pumpWidget(buildUnderTest(mockRepo, 'p1'));
      await tester.pumpAndSettle();

      expect(find.text('Café Arabica'), findsWidgets);
    });

    testWidgets('rendersPriceRangeSection_withMinMaxAvg', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitabilityDetail(
        productId: 'p1',
        params: any(named: 'params'),
      )).thenAnswer((_) async => makeDetail());

      await tester.pumpWidget(buildUnderTest(mockRepo, 'p1'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Prix appliqués'), findsWidgets);
    });

    testWidgets('rendersSparkline_withLast7DaysData', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitabilityDetail(
        productId: 'p1',
        params: any(named: 'params'),
      )).thenAnswer((_) async => makeDetail());

      await tester.pumpWidget(buildUnderTest(mockRepo, 'p1'));
      await tester.pumpAndSettle();

      expect(find.byType(MarginSparkline), findsOneWidget);
    });

    testWidgets('rendersTopStoreRow_whenMultiStore', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitabilityDetail(
        productId: 'p1',
        params: any(named: 'params'),
      )).thenAnswer((_) async => makeDetail());

      await tester.pumpWidget(buildUnderTest(mockRepo, 'p1'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Boutique Nord'), findsOneWidget);
    });
  });
}
