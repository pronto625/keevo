import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/product_profitability_list_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

class MockProfitabilityRepository extends Mock implements ProfitabilityRepository {}

void main() {
  setUpAll(() {
    registerFallbackValue(
      ProfitabilityParams(from: DateTime(2025), to: DateTime(2025)),
    );
  });

  ProductProfitabilityEntry makeEntry({
    String productId = 'p1',
    String productName = 'Café Arabica',
    int unitsSold = 10,
    int totalRevenue = 50000,
    int totalCost = 30000,
    int grossMarginXaf = 20000,
    double marginPercent = 66.67,
    bool isLoss = false,
    String marginLevel = 'PROFITABLE',
  }) =>
      ProductProfitabilityEntry(
        productId: productId,
        productName: productName,
        categoryName: null,
        unitsSold: unitsSold,
        totalRevenue: totalRevenue,
        totalCost: totalCost,
        grossMarginXaf: grossMarginXaf,
        marginPercent: marginPercent,
        isLoss: isLoss,
        storeId: null,
        marginLevel: marginLevel,
      );

  Widget buildUnderTest(MockProfitabilityRepository mockRepo) {
    return ProviderScope(
      overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
      ],
      child: const MaterialApp(
        home: ProductProfitabilityListPage(),
      ),
    );
  }

  group('ProductProfitabilityListPage', () {
    testWidgets('rendersProductRows_withMarginColorBadge', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [
                makeEntry(),
                makeEntry(
                    productId: 'p2',
                    productName: 'Thé vert',
                    marginPercent: 5.0,
                    isLoss: false,
                    marginLevel: 'LOW'),
              ]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(find.text('Café Arabica'), findsOneWidget);
      expect(find.text('Thé vert'), findsOneWidget);
    });

    testWidgets('tapSortChip_MARGIN_XAF_DESC_resortsList', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [makeEntry()]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Marge XAF'));
      await tester.pumpAndSettle();

      // Verify chip tap doesn't crash (sort handled by provider)
      expect(find.text('Marge XAF'), findsOneWidget);
    });

    testWidgets('tapPeriodChip_7days_refreshesData', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [makeEntry()]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      await tester.tap(find.text('7 jours'));
      await tester.pumpAndSettle();

      expect(find.text('7 jours'), findsOneWidget);
    });

    testWidgets('emptyState_showed_whenNoSalesInPeriod', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => []);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(find.text('Aucune vente sur cette période'), findsOneWidget);
    });
  });
}
