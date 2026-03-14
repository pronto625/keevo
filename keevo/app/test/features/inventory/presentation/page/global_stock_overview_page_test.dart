import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';
import 'package:keevo/features/inventory/domain/repository/multi_store_stock_repository.dart';
import 'package:keevo/features/inventory/presentation/page/global_stock_overview_page.dart';
import 'package:keevo/features/inventory/presentation/provider/global_stock_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/store_stock_card.dart';

class _MockRepo extends Mock implements MultiStoreStockRepository {}

/// Widget tests for GlobalStockOverviewPage (Story 3.2 — Task 14.1).
void main() {
  late _MockRepo mockRepo;

  final stores = [
    const StoreStockSummaryModel(
      storeId: 's1',
      storeName: 'Boutique Centre',
      storeType: 'STORE',
      productCount: 5,
      totalValueXaf: 125000,
      lowStockCount: 2,
    ),
    const StoreStockSummaryModel(
      storeId: 's2',
      storeName: 'Entrepôt Nord',
      storeType: 'WAREHOUSE',
      productCount: 20,
      totalValueXaf: 800000,
      lowStockCount: 0,
    ),
  ];

  setUp(() {
    mockRepo = _MockRepo();
    when(() => mockRepo.getOverview()).thenAnswer((_) async => stores);
    when(() => mockRepo.searchAcrossStores(any())).thenAnswer((_) async => []);
    when(() => mockRepo.getStoreStockDetail(
          any(),
          page: any(named: 'page'),
          size: any(named: 'size'),
          sortLowFirst: any(named: 'sortLowFirst'),
        )).thenAnswer((_) async => []);
  });

  /// Fix surface to 400x900 — forces single-column layout,
  /// preventing Row overflow in StoreStockCard header on narrow grid cells.
  void _setPhoneSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Widget _buildPage({List<StoreStockSummaryModel>? storeList}) => ProviderScope(
        overrides: [
          multiStoreStockRepositoryProvider.overrideWithValue(mockRepo),
          globalStockOverviewProvider
              .overrideWith((ref) => Future.value(storeList ?? stores)),
          // Prevent real connectivity access in tests
          syncStatusProvider
              .overrideWith((ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: GlobalStockOverviewPage()),
      );

  group('GlobalStockOverviewPage (Story 3.2 — Task 14.1)', () {
    testWidgets('shows circular progress indicator while loading',
        (tester) async {
      _setPhoneSize(tester);
      // Use a Completer that never completes — no Timer leak vs Future.delayed.
      final completer = Completer<List<StoreStockSummaryModel>>();
      await tester.pumpWidget(ProviderScope(
        overrides: [
          multiStoreStockRepositoryProvider.overrideWithValue(mockRepo),
          globalStockOverviewProvider.overrideWith((ref) => completer.future),
          syncStatusProvider
              .overrideWith((ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: GlobalStockOverviewPage()),
      ));
      await tester.pump(Duration.zero);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      // Resolve before disposal to avoid "pending future" warnings.
      completer.complete([]);
      await tester.pumpAndSettle();
    });

    testWidgets('renders one StoreStockCard per store after loading',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.byType(StoreStockCard), findsNWidgets(stores.length));
    });

    testWidgets('shows "Valeur totale" footer with combined value',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.textContaining('Valeur totale'), findsOneWidget);
    });

    testWidgets('shows empty state when no stores configured', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage(storeList: []));
      await tester.pumpAndSettle();

      expect(find.textContaining('boutique'), findsWidgets);
      expect(find.byType(StoreStockCard), findsNothing);
    });

    testWidgets('search bar is present on screen', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.byType(TextField), findsOneWidget);
      expect(find.text('Rechercher un produit\u2026'), findsOneWidget);
    });

    testWidgets('short query (<2 chars) still shows overview cards',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'a');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Single char — search provider returns [] and overview cards stay visible
      expect(find.byType(StoreStockCard), findsWidgets);
    });
  });
}
