import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/domain/usecase/get_stock_level_usecase.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';
import 'package:keevo/features/catalog/presentation/widget/product_card.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';

class _FakeStockRepository implements StockRepository {
  final List<StockLevelModel> levels;
  _FakeStockRepository(this.levels);

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async => levels;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _MockLocalSaleDs extends Mock implements LocalSaleDataSource {}

/// Promotion succeeds and, like the real list refresh, removes the card.
class _FakeActions extends ProductActions {
  final VoidCallback onPromoted;
  _FakeActions(super.ref, this.onPromoted);

  /// When set, archive/unarchive fail with it.
  static Object? failWith;

  @override
  Future<String> promoteToActive(String id) async {
    onPromoted();
    return 'new-$id';
  }

  // Archiving / unarchiving also moves the product out of the shown list.
  @override
  Future<void> archive(String id) async {
    if (failWith != null) throw failWith!;
    onPromoted();
  }

  @override
  Future<void> unarchive(String id) async {
    if (failWith != null) throw failWith!;
    onPromoted();
  }
}

StockLevelModel _level(String storeId, int qty, int threshold) =>
    StockLevelModel(
      id: 'l-$storeId',
      productId: 'p1',
      storeId: storeId,
      quantity: qty,
      minimumThreshold: threshold,
      updatedAt: DateTime(2026),
    );

ProductModel _product({
  String name = 'Riz parfumé',
  ProductStatus status = ProductStatus.active,
  int price = 2500,
  bool archived = false,
}) =>
    ProductModel(
      id: 'p1',
      name: name,
      price: price,
      status: status,
      archived: archived,
      createdAt: DateTime(2026),
      updatedAt: DateTime(2026),
    );

Future<void> _pump(
  WidgetTester tester, {
  required ProductModel product,
  List<StockLevelModel> levels = const [],
  String? activeStoreId,
  String? role,
  ValueNotifier<bool>? visible,
}) async {
  SharedPreferences.setMockInitialValues({
    if (activeStoreId != null) 'active_store_id': activeStoreId,
    if (role != null) kUserRoleKey: role,
  });
  final prefs = await SharedPreferences.getInstance();
  final repo = _FakeStockRepository(levels);
  final shown = visible ?? ValueNotifier(true);
  final saleDs = _MockLocalSaleDs();
  when(() => saleDs.cascadeValidatePendingSales(any(), any(), any()))
      .thenAnswer((_) async => 0);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      getStockLevelUseCaseProvider
          .overrideWithValue(GetStockLevelUseCase(repo)),
      stockRepositoryProvider.overrideWithValue(repo),
      localSaleDataSourceProvider.overrideWithValue(saleDs),
      currentUserIdProvider.overrideWith((_) async => 'u1'),
      productListProvider.overrideWith((_) async => const []),
      productActionsProvider
          .overrideWith((ref) => _FakeActions(ref, () => shown.value = false)),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: ValueListenableBuilder<bool>(
          valueListenable: shown,
          builder: (_, v, __) => ListView(
            children: [if (v) ProductCard(product: product)],
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('no fake variants badge based on the product name',
      (tester) async {
    await _pump(tester, product: _product(name: 'Robe rouge'));
    expect(find.textContaining('variantes'), findsNothing);
  });

  testWidgets('low-stock badge follows the active store only', (tester) async {
    await _pump(
      tester,
      product: _product(),
      levels: [_level('s1', 10, 5), _level('s2', 1, 5)],
      activeStoreId: 's1',
    );
    expect(find.text('10'), findsOneWidget);
    expect(find.text('Stock bas'), findsNothing);
  });

  testWidgets('draft + low stock badges fit on a 360dp screen',
      (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await _pump(
      tester,
      product: _product(status: ProductStatus.draft, price: 1250000),
      levels: [_level('s1', 2, 5)],
    );

    expect(tester.takeException(), isNull);
    expect(find.text('Brouillon'), findsOneWidget);
    expect(find.text('Stock bas'), findsOneWidget);
  });

  testWidgets('promoting a draft confirms even though the card goes away',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(
      tester,
      product: _product(status: ProductStatus.draft),
      role: 'OWNER',
      // An active store shows the initial-stock field in the dialog.
      activeStoreId: 's1',
    );

    await tester.longPress(find.byType(ProductCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Valider ce produit'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Valider'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(ProductCard), findsNothing);
    expect(find.text('Produit validé avec succès'), findsOneWidget);
  });

  for (final archived in [false, true]) {
    final action = archived ? 'Désarchiver' : 'Archiver';
    final done = archived
        ? 'Produit désarchivé avec succès'
        : 'Produit archivé avec succès';
    testWidgets('$action confirms even though the card goes away',
        (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await _pump(tester, product: _product(archived: archived), role: 'OWNER');

      await tester.longPress(find.byType(ProductCard));
      await tester.pumpAndSettle();
      await tester.tap(find.text(action));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, action));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byType(ProductCard), findsNothing);
      expect(find.text(done), findsOneWidget);
    });
  }

  for (final archived in [false, true]) {
    final action = archived ? 'Désarchiver' : 'Archiver';
    testWidgets('$action failure other than ProductException is reported',
        (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      _FakeActions.failWith = Exception('Base locale indisponible');
      addTearDown(() => _FakeActions.failWith = null);
      await _pump(tester, product: _product(archived: archived), role: 'OWNER');

      await tester.longPress(find.byType(ProductCard));
      await tester.pumpAndSettle();
      await tester.tap(find.text(action));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, action));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Base locale indisponible'), findsOneWidget);
    });
  }
}
