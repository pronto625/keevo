import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/held_operations.dart';
import 'package:keevo/core/theme/app_theme.dart';
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

/// Stock levels never finish loading.
class _PendingStockRepository implements StockRepository {
  final _never = Completer<List<StockLevelModel>>();

  @override
  Future<List<StockLevelModel>> getLevels(String productId) => _never.future;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Stock levels fail to load.
class _ThrowingStockRepository implements StockRepository {
  @override
  Future<List<StockLevelModel>> getLevels(String productId) async =>
      throw Exception('Base locale indisponible');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// First load (before the pull) answers late with 12; later loads say 11.
class _SequencedStockRepository implements StockRepository {
  int _calls = 0;

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async {
    _calls++;
    if (_calls == 1) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      return [_level('s1', 12, 0)];
    }
    return [_level('s1', 11, 0)];
  }

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
  StockRepository? stockRepository,
  Map<String, String> held = const {},
}) async {
  SharedPreferences.setMockInitialValues({
    if (activeStoreId != null) 'active_store_id': activeStoreId,
    if (role != null) kUserRoleKey: role,
  });
  final prefs = await SharedPreferences.getInstance();
  final repo = stockRepository ?? _FakeStockRepository(levels);
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
      heldEntityReasonsProvider.overrideWith((_) async => held),
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
  // Story 13.8b: a product created/changed while its module is off.
  testWidgets('a held product shows « En attente — module non activé »',
      (tester) async {
    await _pump(tester,
        product: _product(), held: const {'p1': 'MODULE_INACTIVE'});
    expect(find.text('En attente — module non activé'), findsOneWidget);
  });

  testWidgets('a product without held operation has no held badge',
      (tester) async {
    await _pump(tester, product: _product());
    expect(find.textContaining('En attente —'), findsNothing);
  });

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

  // v1s-16-14: never stocked here ≠ sold out.
  testWidgets('no stock row in the active store shows a neutral "Jamais stocké"',
      (tester) async {
    await _pump(
      tester,
      product: _product(),
      levels: [_level('s2', 7, 5)],
      activeStoreId: 's1',
    );
    expect(find.text('Jamais stocké'), findsOneWidget);
    expect(find.text('0'), findsNothing);
    final text = tester.widget<Text>(find.text('Jamais stocké'));
    final cs = Theme.of(tester.element(find.text('Jamais stocké'))).colorScheme;
    expect(text.style?.color, cs.onSurfaceVariant);
  });

  testWidgets('no stock row at all (no active store) shows "Jamais stocké"',
      (tester) async {
    await _pump(tester, product: _product());
    expect(find.text('Jamais stocké'), findsOneWidget);
  });

  testWidgets('a stock row with quantity 0 keeps the red "0" badge',
      (tester) async {
    await _pump(
      tester,
      product: _product(),
      levels: [_level('s1', 0, 5)],
      activeStoreId: 's1',
    );
    expect(find.text('Jamais stocké'), findsNothing);
    final text = tester.widget<Text>(find.text('0'));
    expect(text.style?.color, AppTheme.errorColor);
  });

  testWidgets('when stock levels fail to load, no stock badge is shown',
      (tester) async {
    await _pump(
      tester,
      product: _product(),
      activeStoreId: 's1',
      stockRepository: _ThrowingStockRepository(),
    );
    expect(find.text('Jamais stocké'), findsNothing);
    expect(find.byIcon(Icons.inventory_2_outlined), findsNothing);
  });

  testWidgets('while stock levels load, no stock badge is shown',
      (tester) async {
    await _pump(
      tester,
      product: _product(),
      activeStoreId: 's1',
      stockRepository: _PendingStockRepository(),
    );
    expect(find.text('Jamais stocké'), findsNothing);
    expect(find.byIcon(Icons.inventory_2_outlined), findsNothing);
  });

  // G4 (device test): the card kept 12 after a sync brought 11.
  testWidgets('a sync refresh shows the pulled quantity even if an earlier '
      'load answers late', (tester) async {
    final repo = _SequencedStockRepository();
    SharedPreferences.setMockInitialValues({'active_store_id': 's1'});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        getStockLevelUseCaseProvider
            .overrideWithValue(GetStockLevelUseCase(repo)),
        stockRepositoryProvider.overrideWithValue(repo),
        currentUserIdProvider.overrideWith((_) async => 'u1'),
        productListProvider.overrideWith((_) async => const []),
      ],
      child: MaterialApp(
        home: Scaffold(body: ListView(children: [ProductCard(product: _product())])),
      ),
    ));
    await tester.pump(); // first load starts (pre-sync, slow, answers 12)

    // The sync completes and invalidates every stock notifier.
    ProviderScope.containerOf(tester.element(find.byType(ProductCard)))
        .invalidate(stockNotifierProvider);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));
    await tester.pump();
    expect(find.text('11'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 200)); // late answer
    await tester.pump();
    expect(find.text('11'), findsOneWidget);
    expect(find.text('12'), findsNothing);
  });

  testWidgets('draft + low stock badges fit on a 360dp screen', (tester) async {
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
    // v1s-12-10: the entry is additive — the dialog says so.
    expect(find.text('Stock à ajouter'), findsOneWidget);
    expect(
        find.text("S'ajoute au stock déjà saisi en boutique"), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Valider'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(ProductCard), findsNothing);
    expect(find.text('Produit validé avec succès'), findsOneWidget);
  });

  // v1s-16-13: an invalid stock entry is no longer read as 0.
  test('parsePromotionStock', () {
    expect(parsePromotionStock(''), 0);
    expect(parsePromotionStock('  '), 0);
    expect(parsePromotionStock('12'), 12);
    expect(parsePromotionStock(' 7 '), 7);
    for (final bad in ['abc', '-3', '1.5', '1,5', '+2', '12a']) {
      expect(parsePromotionStock(bad), isNull, reason: bad);
    }
  });

  testWidgets('promotion dialog: invalid stock shows an error, Valider disabled',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(
      tester,
      product: _product(status: ProductStatus.draft),
      role: 'OWNER',
      activeStoreId: 's1',
    );

    await tester.longPress(find.byType(ProductCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Valider ce produit'));
    await tester.pumpAndSettle();

    FilledButton valider() =>
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Valider'));
    expect(valider().onPressed, isNotNull, reason: 'empty → 0 is allowed');

    for (final bad in ['abc', '-3', '1.5']) {
      await tester.enterText(find.byKey(const Key('promote_stock_field')), bad);
      await tester.pump();
      expect(find.text('Entrez un nombre entier positif'), findsOneWidget,
          reason: bad);
      expect(valider().onPressed, isNull, reason: bad);
    }

    await tester.enterText(find.byKey(const Key('promote_stock_field')), '4');
    await tester.pump();
    expect(find.text('Entrez un nombre entier positif'), findsNothing);
    expect(valider().onPressed, isNotNull);
  });

  // v1s-12-10: an EMPLOYEE update of a DRAFT is rejected server-side, so the
  // app does not open the edit form (no GoRouter here: a push would throw).
  // Fail closed: an unknown (null) role is treated like an EMPLOYEE.
  for (final role in <String?>['EMPLOYEE', null]) {
    testWidgets('$role tapping a draft card is told only the owner edits it',
        (tester) async {
      await _pump(tester,
          product: _product(status: ProductStatus.draft), role: role);

      await tester.tap(find.byType(ProductCard));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(
          find.text('Seul le propriétaire peut modifier un produit brouillon'),
          findsOneWidget);
    });
  }

  testWidgets('employee "Modifier" on a draft does not open the edit form',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(tester,
        product: _product(status: ProductStatus.draft), role: 'EMPLOYEE');

    await tester.longPress(find.byType(ProductCard));
    await tester.pumpAndSettle();
    expect(find.text('Valider ce produit'), findsNothing);
    await tester.tap(find.text('Modifier'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Seul le propriétaire peut modifier un produit brouillon'),
        findsOneWidget);
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
