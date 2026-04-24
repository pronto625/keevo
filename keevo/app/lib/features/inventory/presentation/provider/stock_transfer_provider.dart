import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../data/datasource/local_stock_transfer_datasource.dart';
import '../../data/datasource/remote_stock_transfer_datasource.dart';
import '../../data/repository/stock_transfer_repository_impl.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../../domain/repository/stock_transfer_repository.dart';

part 'stock_transfer_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localStockTransferDsProvider =
    Provider<LocalStockTransferDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalStockTransferDataSource(db);
});

final remoteStockTransferDsProvider =
    Provider<RemoteStockTransferDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteStockTransferDataSource(dio: dio);
});

final stockTransferRepositoryProvider =
    Provider<StockTransferRepository>((ref) {
  return StockTransferRepositoryImpl(
    local: ref.watch(localStockTransferDsProvider),
    remote: ref.watch(remoteStockTransferDsProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
  );
});

// ── Transfer history (keyed by optional storeId / productId) ────────────────

@riverpod
Future<List<StockTransferModel>> transferHistory(
  TransferHistoryRef ref, {
  String? sourceStoreId,
  String? destinationStoreId,
  String? productId,
  int page = 0,
  int pageSize = 20,
}) async {
  final transfers = await ref.watch(stockTransferRepositoryProvider).getHistory(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        page: page,
        pageSize: pageSize,
      );

  // Enrich denormalized display fields from local providers (spec AC5).
  // Use ref.read (not ref.watch) to avoid AutoDispose race conditions: watching
  // an AutoDisposeFutureProvider inside an async body can cause
  // "Bad state: Future already completed" when the provider auto-disposes
  // mid-await (e.g. when the form bottom sheet closes its listeners).
  final stores = await ref.read(storeListNotifierProvider.future);
  final products = await ref.read(productListForPickerProvider.future);
  final storeMap = {for (final s in stores) s.id: s.name};
  final productMap = {for (final p in products) p.id: p.name};

  return transfers
      .map((t) => t.copyWith(
            sourceStoreName: t.sourceStoreName.isNotEmpty
                ? t.sourceStoreName
                : (storeMap[t.sourceStoreId] ?? ''),
            destinationStoreName: t.destinationStoreName.isNotEmpty
                ? t.destinationStoreName
                : (storeMap[t.destinationStoreId] ?? ''),
            productName: t.productName.isNotEmpty
                ? t.productName
                : (productMap[t.productId] ?? ''),
          ))
      .toList();
}

// ── Execute transfer notifier ─────────────────────────────────────────────

@riverpod
class ExecuteTransferNotifier extends _$ExecuteTransferNotifier {
  @override
  FutureOr<StockTransferModel?> build() => null;

  Future<StockTransferModel> execute({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(() =>
        ref.read(stockTransferRepositoryProvider).executeTransfer(
              sourceStoreId: sourceStoreId,
              destinationStoreId: destinationStoreId,
              productId: productId,
              variantId: variantId,
              quantity: quantity,
              notes: notes,
            ));
    state = result;
    if (result.hasError) throw result.error!;
    return result.value!;
  }
}

// ── Complete transfer notifier ────────────────────────────────────────────

@riverpod
class CompleteTransferNotifier extends _$CompleteTransferNotifier {
  @override
  FutureOr<StockTransferModel?> build() => null;

  Future<StockTransferModel> complete(String transferId) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(() =>
        ref.read(stockTransferRepositoryProvider).completeTransfer(transferId));
    state = result;
    if (result.hasError) throw result.error!;
    return result.value!;
  }
}
