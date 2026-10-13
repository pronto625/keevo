import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/domain/usecase/get_stock_level_usecase.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';
import 'package:keevo/features/catalog/presentation/widget/stock_level_widget.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _FakeStockRepository implements StockRepository {
  @override
  Future<List<StockLevelModel>> getLevels(String productId) async => [
        StockLevelModel(
          id: 'l1',
          productId: productId,
          storeId: 'main',
          quantity: 4,
          updatedAt: DateTime(2026),
        ),
      ];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Fails until [failing] is cleared (v1s-16-11 follow-up retry test).
class _FlakyStockRepository extends _FakeStockRepository {
  bool failing = true;
  int calls = 0;

  @override
  Future<List<StockLevelModel>> getLevels(String productId) {
    calls++;
    if (failing) throw StateError('database is locked');
    return super.getLevels(productId);
  }
}

class _NoStores extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => const [];
}

void main() {
  testWidgets('unknown store with a short id does not throw', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        getStockLevelUseCaseProvider
            .overrideWithValue(GetStockLevelUseCase(_FakeStockRepository())),
        primaryStoreIdProvider.overrideWith((_) async => null),
        storeListNotifierProvider.overrideWith(_NoStores.new),
      ],
      child: const MaterialApp(
        home: Scaffold(body: StockLevelWidget(productId: 'p1')),
      ),
    ));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    // v1s-16-20: an unknown store reads « Boutique », never its id.
    expect(find.text('main'), findsNothing);
    expect(find.text('Boutique'), findsOneWidget);
  });

  testWidgets('levels load failure → Réessayer reloads the levels '
      '(v1s-16-11 follow-up)', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final repo = _FlakyStockRepository();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        getStockLevelUseCaseProvider
            .overrideWithValue(GetStockLevelUseCase(repo)),
        primaryStoreIdProvider.overrideWith((_) async => null),
        storeListNotifierProvider.overrideWith(_NoStores.new),
      ],
      child: const MaterialApp(
        home: Scaffold(
            body: SingleChildScrollView(
                child: StockLevelWidget(productId: 'p1'))),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Stock indisponible'), findsOneWidget);
    expect(find.textContaining('database'), findsNothing);
    expect(find.text('Boutique'), findsNothing);

    repo.failing = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Stock indisponible'), findsNothing);
    expect(find.text('Boutique'), findsOneWidget);
    expect(repo.calls, 2);
  });
}
