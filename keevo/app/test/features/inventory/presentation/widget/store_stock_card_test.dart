import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';
import 'package:keevo/features/inventory/domain/repository/multi_store_stock_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/global_stock_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/store_stock_card.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockRepo extends Mock implements MultiStoreStockRepository {}

const _summary = StoreStockSummaryModel(
  storeId: 's1',
  storeName: 'Boutique Centre',
  storeType: 'STORE',
  productCount: 30,
  totalValueXaf: 20000,
  lowStockCount: 1,
);

List<StoreProductStockModel> _page(int start, int count) => [
      for (var i = start; i < start + count; i++)
        StoreProductStockModel(
          productId: 'p$i',
          productName: 'Produit $i',
          storeId: 's1',
          quantity: 10,
          status: 'NORMAL',
          isLow: false,
          isCritical: false,
        ),
    ];

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockRepo repo;
  late ProviderContainer container;

  setUp(() async {
    repo = _MockRepo();
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    container = ProviderContainer(retry: noProviderRetry, overrides: [
      multiStoreStockRepositoryProvider.overrideWithValue(repo),
      sharedPreferencesProvider.overrideWithValue(prefs),
    ]);
  });
  tearDown(() => container.dispose());

  Future<void> pumpCard(WidgetTester tester) async {
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: StoreStockCard(summary: _summary)),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique Centre'));
    await tester.pumpAndSettle();
  }

  testWidgets('"Voir plus" failure is shown instead of being swallowed',
      (tester) async {
    when(() => repo.getStoreStockDetail('s1',
        page: 0,
        size: 25,
        sortLowFirst: true,
        lowOnly: false)).thenAnswer((_) async => _page(0, 25));
    when(() => repo.getStoreStockDetail('s1',
            page: 1, size: 25, sortLowFirst: true, lowOnly: false))
        .thenThrow(Exception('Serveur indisponible'));

    await pumpCard(tester);
    await tester.scrollUntilVisible(find.text('Voir plus'), 200);
    await tester.tap(find.text('Voir plus'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Serveur indisponible'), findsOneWidget);
    expect(find.textContaining('Exception'), findsNothing);
  });

  testWidgets('an initial load error shows a readable message',
      (tester) async {
    when(() => repo.getStoreStockDetail('s1',
            page: 0, size: 25, sortLowFirst: true, lowOnly: false))
        .thenThrow(Exception('Serveur indisponible'));

    await pumpCard(tester);

    expect(find.textContaining('Serveur indisponible'), findsOneWidget);
    expect(find.textContaining('Exception'), findsNothing);
  });

  testWidgets('switching the low-stock filter reloads the list',
      (tester) async {
    when(() => repo.getStoreStockDetail('s1',
            page: 0, size: 25, sortLowFirst: true, lowOnly: false))
        .thenAnswer((_) async => _page(0, 3));
    when(() => repo.getStoreStockDetail('s1',
            page: 0, size: 25, sortLowFirst: true, lowOnly: true))
        .thenAnswer((_) async => _page(100, 1));

    await pumpCard(tester);
    expect(find.text('Produit 0'), findsOneWidget);

    container.read(showLowStockOnlyProvider.notifier).state = true;
    await tester.pumpAndSettle();

    expect(find.text('Produit 0'), findsNothing);
    expect(find.text('Produit 100'), findsOneWidget);
  });
}
