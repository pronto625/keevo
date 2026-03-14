import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';
import 'package:keevo/features/inventory/domain/repository/multi_store_stock_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/global_stock_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/store_product_stock_tile.dart';
import 'package:keevo/features/inventory/presentation/widget/store_stock_card.dart';

class _MockRepo extends Mock implements MultiStoreStockRepository {}

/// Widget tests for StoreStockCard (Story 3.2 — Task 14.1).
void main() {
  late _MockRepo mockRepo;

  const summary = StoreStockSummaryModel(
    storeId: 'store-1',
    storeName: 'Boutique Yaoundé',
    storeType: 'STORE',
    productCount: 3,
    totalValueXaf: 75000,
    lowStockCount: 1,
  );

  const warehouseSummary = StoreStockSummaryModel(
    storeId: 'wh-1',
    storeName: 'Entrepôt Central',
    storeType: 'WAREHOUSE',
    productCount: 10,
    totalValueXaf: 500000,
    lowStockCount: 0,
  );

  final products = [
    const StoreProductStockModel(
      productId: 'p1',
      productName: 'Sachet de sel',
      storeId: 'store-1',
      quantity: 3,
      minimumThreshold: 10,
      status: 'BAS',
      isLow: true,
      isCritical: false,
    ),
  ];

  setUp(() {
    mockRepo = _MockRepo();
    when(() => mockRepo.getStoreStockDetail(
          any(),
          page: any(named: 'page'),
          size: any(named: 'size'),
          sortLowFirst: any(named: 'sortLowFirst'),
        )).thenAnswer((_) async => products);
  });

  Widget _wrap(Widget child) => ProviderScope(
        overrides: [
          multiStoreStockRepositoryProvider.overrideWithValue(mockRepo),
        ],
        child: MaterialApp(
          home: Scaffold(body: ListView(children: [child])),
        ),
      );

  group('StoreStockCard (Story 3.2 — Task 14.1)', () {
    testWidgets('shows store name and product count', (tester) async {
      await tester.pumpWidget(_wrap(StoreStockCard(summary: summary)));
      expect(find.text('Boutique Yaoundé'), findsOneWidget);
      expect(find.textContaining('3'), findsWidgets);
    });

    testWidgets('shows Boutique type chip for STORE', (tester) async {
      await tester.pumpWidget(_wrap(StoreStockCard(summary: summary)));
      expect(find.text('Boutique'), findsOneWidget);
    });

    testWidgets('shows Entrepôt type chip for WAREHOUSE', (tester) async {
      await tester.pumpWidget(_wrap(StoreStockCard(summary: warehouseSummary)));
      expect(find.text('Entrepôt'), findsOneWidget);
    });

    testWidgets('shows low stock badge when lowStockCount > 0', (tester) async {
      await tester.pumpWidget(_wrap(StoreStockCard(summary: summary)));
      // The badge shows the lowStockCount value
      expect(find.text('1'), findsWidgets);
    });

    testWidgets('no low stock badge when lowStockCount == 0', (tester) async {
      await tester.pumpWidget(
          _wrap(StoreStockCard(summary: warehouseSummary)));
      expect(find.byIcon(Icons.warning_amber_rounded), findsNothing);
    });

    testWidgets('expands to show product list on tap', (tester) async {
      await tester.pumpWidget(_wrap(StoreStockCard(summary: summary)));

      // Initially no product tile visible
      expect(find.byType(StoreProductStockTile), findsNothing);

      // Tap to expand
      await tester.tap(find.byType(InkWell).first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Wait for async fetch to complete
      await tester.pumpAndSettle();

      expect(find.byType(StoreProductStockTile), findsOneWidget);
    });

    testWidgets('auto-expands when highlightedStoreIdProvider matches',
        (tester) async {
      final container = ProviderContainer(overrides: [
        multiStoreStockRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(
            body: ListView(children: [StoreStockCard(summary: summary)]),
          ),
        ),
      ));

      expect(find.byType(StoreProductStockTile), findsNothing);

      // Simulate a search result tap setting the highlighted store
      container.read(highlightedStoreIdProvider.notifier).state = 'store-1';
      await tester.pumpAndSettle();

      expect(find.byType(StoreProductStockTile), findsOneWidget);
      // Highlight should be cleared after expand
      expect(container.read(highlightedStoreIdProvider), isNull);
    });
  });
}
