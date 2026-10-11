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
    expect(find.text('main'), findsOneWidget);
  });
}
