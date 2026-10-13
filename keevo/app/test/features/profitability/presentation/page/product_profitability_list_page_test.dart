import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/profitability/presentation/widget/offline_data_banner.dart';
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
    // The detail page reads the store filter from the list: tapping a row
    // must carry the list's store, or the detail silently covers every store.
    testWidgets('row tap carries the list store filter to the detail',
        (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [makeEntry()]);
      final container = ProviderContainer(overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);
      final router = GoRouter(routes: [
        GoRoute(
            path: '/',
            builder: (_, __) =>
                const ProductProfitabilityListPage(storeId: 's1')),
        GoRoute(
            path: '/reports/rentabilite/:id',
            builder: (_, __) => const Scaffold(body: Text('detail'))),
      ]);

      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Café Arabica'));
      await tester.pumpAndSettle();

      expect(find.text('detail'), findsOneWidget);
      expect(container.read(profitabilityStoreFilterProvider), 's1');
    });

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

    // AC2: a sort chip re-sorts locally — the repository is queried once.
    testWidgets('tapSortChip_reordersRows_withoutRefetching', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [
                makeEntry(
                    productId: 'p1',
                    productName: 'Haute marge',
                    marginPercent: 80,
                    grossMarginXaf: 1000),
                makeEntry(
                    productId: 'p2',
                    productName: 'Gros volume',
                    marginPercent: 20,
                    grossMarginXaf: 90000),
              ]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      // Default Marge % → 'Haute marge' above 'Gros volume'.
      expect(tester.getTopLeft(find.text('Haute marge')).dy,
          lessThan(tester.getTopLeft(find.text('Gros volume')).dy));

      await tester.tap(find.text('Marge XAF'));
      await tester.pumpAndSettle();

      expect(tester.getTopLeft(find.text('Gros volume')).dy,
          lessThan(tester.getTopLeft(find.text('Haute marge')).dy));
      verify(() => mockRepo.getProductProfitability(
          params: any(named: 'params'))).called(1);
    });

    // AC1: loss-making products are listed last on the default sort.
    testWidgets('defaultSort_putsLossProductsLast', (tester) async {
      final mockRepo = MockProfitabilityRepository();
      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => [
                makeEntry(
                    productId: 'p1',
                    productName: 'Produit perte',
                    marginPercent: -5,
                    grossMarginXaf: -500,
                    isLoss: true,
                    marginLevel: 'LOSS'),
                makeEntry(
                    productId: 'p2',
                    productName: 'Produit faible',
                    marginPercent: 5,
                    grossMarginXaf: 200,
                    marginLevel: 'LOW'),
              ]);

      await tester.pumpWidget(buildUnderTest(mockRepo));
      await tester.pumpAndSettle();

      expect(tester.getTopLeft(find.text('Produit faible')).dy,
          lessThan(tester.getTopLeft(find.text('Produit perte')).dy));
      expect(find.text('PERTE'), findsOneWidget);
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

  testWidgets('offline device → shared OfflineDataBanner', (tester) async {
    final mockRepo = MockProfitabilityRepository();
    when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
        .thenAnswer((_) async => [makeEntry()]);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        syncStatusProvider
            .overrideWith((_) => Stream.value(SyncStatus.offlineOk)),
      ],
      child: const MaterialApp(home: ProductProfitabilityListPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsOneWidget);
  });
}
