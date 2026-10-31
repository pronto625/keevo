import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

/// Counts its builds; the grid content comes from [products].
class _CountingGridNotifier extends FrequentProductsNotifier {
  static int builds = 0;
  static List<PosProductResult> products = const [];

  @override
  Future<FrequentProductsState> build(
      ({String? storeId, String? categoryId}) arg) async {
    builds++;
    return FrequentProductsState(products: products, hasMore: false);
  }
}

const _rice = PosProductResult(
  id: 'p1',
  name: 'Riz',
  price: 2500,
  stock: 4,
  status: 'ACTIVE',
);

/// G3 (device test): on a first sign-in the POS grid stayed on « Aucun
/// produit configuré » after the initial pull, until the user changed tab.
void main() {
  test('a completed sync reloads the POS product grid', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final sync = _MockSyncService();
    final logger = _MockEventLogger();
    when(() => logger.logPushSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPullSuccess(any())).thenAnswer((_) async {});
    when(() => sync.push()).thenAnswer((_) async => []);
    // The initial full pull brings the catalogue.
    when(() => sync.pull()).thenAnswer((_) async {
      _CountingGridNotifier.products = const [_rice];
    });
    _CountingGridNotifier.builds = 0;
    _CountingGridNotifier.products = const [];

    final container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityStreamProvider.overrideWith(
        (ref) => Stream.value([ConnectivityResult.wifi]),
      ),
      frequentProductsProvider.overrideWith(_CountingGridNotifier.new),
    ]);
    addTearDown(container.dispose);

    const key = (storeId: 's1', categoryId: null);
    final sub = container.listen(frequentProductsProvider(key), (_, __) {});
    addTearDown(sub.close);
    expect((await container.read(frequentProductsProvider(key).future)).products,
        isEmpty);

    await container.read(syncTriggerNotifierProvider.notifier).triggerSync();

    final after = await container.read(frequentProductsProvider(key).future);
    expect(_CountingGridNotifier.builds, 2);
    expect(after.products.map((p) => p.name), ['Riz']);
  });
}
