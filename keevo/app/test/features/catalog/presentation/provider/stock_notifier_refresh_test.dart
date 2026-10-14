import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/domain/usecase/get_stock_level_usecase.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';

class _MutableStockRepository implements StockRepository {
  int quantity = 12;

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async => [
        StockLevelModel(
          id: 'l1',
          productId: productId,
          storeId: 's1',
          quantity: quantity,
          updatedAt: DateTime(2026),
        ),
      ];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// G4 (device test): catalog cards kept an old quantity after a sync.
void main() {
  test('family invalidation (post-sync) reloads stock levels', () async {
    final repo = _MutableStockRepository();
    final container = ProviderContainer(overrides: [
      getStockLevelUseCaseProvider.overrideWithValue(GetStockLevelUseCase(repo)),
    ]);
    addTearDown(container.dispose);
    final sub = container.listen(stockNotifierProvider('p1'), (_, __) {});
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(sub.read().levels.single.quantity, 12);

    repo.quantity = 11;
    container.invalidate(stockNotifierProvider);
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(sub.read().levels.single.quantity, 11);
  });

  test('invalidation while unwatched reloads on next watch', () async {
    final repo = _MutableStockRepository();
    final container = ProviderContainer(overrides: [
      getStockLevelUseCaseProvider.overrideWithValue(GetStockLevelUseCase(repo)),
    ]);
    addTearDown(container.dispose);
    var sub = container.listen(stockNotifierProvider('p1'), (_, __) {});
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(sub.read().levels.single.quantity, 12);
    sub.close();

    repo.quantity = 11;
    container.invalidate(stockNotifierProvider);
    await Future<void>.delayed(Duration.zero);
    sub = container.listen(stockNotifierProvider('p1'), (_, __) {});
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(sub.read().levels.single.quantity, 11);
  });

  test('a slow load started before the sync never overwrites the reload', () async {
    final repo = _SlowFirstRepository();
    final container = ProviderContainer(overrides: [
      getStockLevelUseCaseProvider.overrideWithValue(GetStockLevelUseCase(repo)),
    ]);
    addTearDown(container.dispose);
    final sub = container.listen(stockNotifierProvider('p1'), (_, __) {});
    await Future<void>.delayed(Duration.zero);
    // First load still in flight; a sync completes and invalidates.
    container.invalidate(stockNotifierProvider);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(sub.read().levels.single.quantity, 11);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(sub.read().levels.single.quantity, 11);
  });
}

/// The first call (made before the pull) answers late with the old quantity.
class _SlowFirstRepository implements StockRepository {
  int calls = 0;

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async {
    calls++;
    if (calls == 1) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
      return [_level(productId, 12)];
    }
    return [_level(productId, 11)];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

StockLevelModel _level(String productId, int qty) => StockLevelModel(
      id: 'l1',
      productId: productId,
      storeId: 's1',
      quantity: qty,
      updatedAt: DateTime(2026),
    );
