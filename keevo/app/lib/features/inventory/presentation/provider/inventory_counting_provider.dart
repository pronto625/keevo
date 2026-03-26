import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_inventory_count_datasource.dart';
import '../../data/datasource/remote_inventory_count_datasource.dart';
import '../../data/repository/inventory_count_repository_impl.dart';
import '../../domain/model/inventory_count_model.dart';
import '../../domain/model/inventory_product_row_model.dart';
import '../../domain/repository/inventory_count_repository.dart';
import '../../domain/usecase/save_inventory_count_usecase.dart';
import 'inventory_session_provider.dart';

part 'inventory_counting_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localInventoryCountDsProvider =
    Provider<LocalInventoryCountDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalInventoryCountDataSource(db);
});

final remoteInventoryCountDsProvider =
    Provider<RemoteInventoryCountDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteInventoryCountDataSource(dio: dio);
});

final inventoryCountRepositoryProvider =
    Provider<InventoryCountRepository>((ref) {
  return InventoryCountRepositoryImpl(
    localCount: ref.watch(localInventoryCountDsProvider),
    localSession: ref.watch(localInventorySessionDsProvider),
    remote: ref.watch(remoteInventoryCountDsProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

final saveInventoryCountUseCaseProvider =
    Provider<SaveInventoryCountUseCase>((ref) {
  return SaveInventoryCountUseCase(
    ref.watch(inventoryCountRepositoryProvider),
  );
});

// ── Reactive providers ──────────────────────────────────────────────────────

/// Fetches products in scope for a counting session.
@riverpod
Future<List<InventoryProductRowModel>> countingProducts(
  CountingProductsRef ref,
  String sessionId,
) async {
  return ref
      .watch(inventoryCountRepositoryProvider)
      .getCountingProducts(sessionId);
}

/// Filter enum for counting list (AC4: Tous / Non comptés / Écarts seulement).
enum InventoryCountFilter { all, uncounted, discrepancies }

/// Filter state — defaults to uncounted (AC4).
final inventoryCountFilterProvider =
    StateProvider<InventoryCountFilter>((ref) => InventoryCountFilter.uncounted);

/// Search query for product name filtering.
final inventoryCountSearchProvider = StateProvider<String>((ref) => '');

// ── Action notifier — saves individual count ─────────────────────────────

@riverpod
class SaveCountNotifier extends _$SaveCountNotifier {
  @override
  FutureOr<InventoryCountModel?> build() => null;

  Future<InventoryCountModel> save({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  }) async {
    // NOTE: Do NOT set state = AsyncLoading() here.
    // Multiple rows fire save() concurrently (per-row debounce).
    // Setting AsyncLoading creates an internal Completer that races
    // with the next call → "Future already completed".
    try {
      final count = await ref.read(saveInventoryCountUseCaseProvider).execute(
            sessionId: sessionId,
            productId: productId,
            variantId: variantId,
            productName: productName,
            variantLabel: variantLabel,
            theoretical: theoretical,
            physical: physical,
          );
      state = AsyncData(count);
      // Invalidate product list to refresh écart + counted state
      ref.invalidate(countingProductsProvider);
      return count;
    } catch (e, st) {
      state = AsyncError(e, st);
      rethrow;
    }
  }
}
