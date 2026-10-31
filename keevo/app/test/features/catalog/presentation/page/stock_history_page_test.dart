import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo/features/catalog/domain/model/stock_movement_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/domain/usecase/get_stock_history_usecase.dart';
import 'package:keevo/features/catalog/domain/usecase/get_stock_level_usecase.dart';
import 'package:keevo/features/catalog/presentation/page/stock_history_page.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/no_technical_text.dart';

class _FakeStockRepository implements StockRepository {
  final List<StockMovementModel> movements;
  final historyCalls = <String?>[];
  _FakeStockRepository(this.movements);

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async => [];

  @override
  Future<List<StockMovementModel>> getMovementHistory(
    String productId, {
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  }) async {
    historyCalls.add(movementType);
    return movements;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

StockMovementModel _movement() => StockMovementModel(
      id: 'm1',
      productId: 'p1',
      storeId: 's1',
      movementType: 'SALE',
      quantityBefore: 5,
      quantityDelta: -1,
      quantityAfter: 4,
      actorId: 'u1',
      occurredAt: DateTime(2026, 3, 1),
    );

Future<_FakeStockRepository> _pump(
    WidgetTester tester, List<StockMovementModel> movements) async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  final repo = _FakeStockRepository(movements);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      getStockHistoryUseCaseProvider
          .overrideWithValue(GetStockHistoryUseCase(repo)),
      getStockLevelUseCaseProvider
          .overrideWithValue(GetStockLevelUseCase(repo)),
    ],
    child: const MaterialApp(
      home: StockHistoryPage(productId: 'p1', productName: 'Riz'),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

void main() {
  testWidgets('"Tous" resets the movement type filter', (tester) async {
    final repo = await _pump(tester, [_movement()]);
    expect(repo.historyCalls, [null]);

    await tester.tap(find.byIcon(Icons.filter_alt_outlined));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Ventes'));
    await tester.pumpAndSettle();
    expect(repo.historyCalls.last, 'SALE');

    await tester.tap(find.byIcon(Icons.filter_alt));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tous'));
    await tester.pumpAndSettle();

    expect(repo.historyCalls.length, 3);
    expect(repo.historyCalls.last, isNull);
    expect(find.byIcon(Icons.filter_alt_outlined), findsOneWidget);
  });

  testWidgets('empty history supports pull-to-refresh', (tester) async {
    final repo = await _pump(tester, []);
    expect(find.text('Aucun mouvement enregistré'), findsOneWidget);
    expect(repo.historyCalls.length, 1);

    await tester.fling(
        find.text('Aucun mouvement enregistré'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    expect(repo.historyCalls.length, 2);
  });

  testWidgets(
      'v1s-16-20 guard: inventory notes and unknown movement types are readable',
      (tester) async {
    const uuid = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
    await _pump(tester, [
      StockMovementModel(
        id: uuid,
        productId: uuid,
        storeId: uuid,
        movementType: 'ADJUSTMENT',
        quantityBefore: 5,
        quantityDelta: -1,
        quantityAfter: 4,
        actorId: uuid,
        occurredAt: DateTime(2026, 3, 1),
        notes: 'INVENTORY:$uuid',
      ),
      StockMovementModel(
        id: 'm2',
        productId: 'p1',
        storeId: 's1',
        movementType: 'SOME_NEW_TYPE',
        quantityBefore: 4,
        quantityDelta: 1,
        quantityAfter: 5,
        actorId: 'u1',
        occurredAt: DateTime(2026, 3, 2),
      ),
    ]);

    expect(find.text('Ajustement d\'inventaire'), findsOneWidget);
    expect(find.textContaining('Some new type'), findsWidgets);
    expectNoTechnicalText(tester);
  });
}
