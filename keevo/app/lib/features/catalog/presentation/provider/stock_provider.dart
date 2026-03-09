import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_stock_datasource.dart';
import '../../data/datasource/remote_stock_datasource.dart';
import '../../data/repository/stock_repository_impl.dart';
import '../../domain/model/stock_level_model.dart';
import '../../domain/model/stock_movement_model.dart';
import '../../domain/repository/stock_repository.dart';
import '../../domain/usecase/get_stock_history_usecase.dart';
import '../../domain/usecase/get_stock_level_usecase.dart';
import '../../domain/usecase/set_threshold_usecase.dart';

part 'stock_provider.g.dart';

// ── Tenant store provider ─────────────────────────────────────────────────────

/// Resolves the primary (first active) store UUID for the current tenant.
///
/// Calls GET /api/v1/tenant/stores and returns the first store's id.
/// Used by stock entry / adjust bottom sheets that require a real UUID.
///
/// Story 2.3 hotfix — Epic 3 will replace with a full store selector.
final primaryStoreIdProvider = FutureProvider<String?>((ref) async {
  final dio = ref.watch(dioProvider);
  try {
    final response = await dio.get<Map<String, dynamic>>('/api/v1/tenant/stores');
    final data = response.data!['data'] as List<dynamic>;
    if (data.isEmpty) return null;
    return (data.first as Map<String, dynamic>)['id'] as String?;
  } catch (_) {
    return null;
  }
});

// ── Infrastructure providers ──────────────────────────────────────────────────

final localStockDataSourceProvider = Provider<LocalStockDataSource>((ref) {
  return LocalStockDataSource(ref.watch(appDatabaseProvider));
});

final remoteStockDataSourceProvider = Provider<RemoteStockDataSource>((ref) {
  return RemoteStockDataSource(dio: ref.watch(dioProvider));
});

final stockRepositoryProvider = Provider<StockRepository>((ref) {
  return StockRepositoryImpl(
    local: ref.watch(localStockDataSourceProvider),
    remote: ref.watch(remoteStockDataSourceProvider),
  );
});

// ── Use case providers ────────────────────────────────────────────────────────

final getStockLevelUseCaseProvider = Provider<GetStockLevelUseCase>((ref) {
  return GetStockLevelUseCase(ref.watch(stockRepositoryProvider));
});

final getStockHistoryUseCaseProvider = Provider<GetStockHistoryUseCase>((ref) {
  return GetStockHistoryUseCase(ref.watch(stockRepositoryProvider));
});

final setThresholdUseCaseProvider = Provider<SetThresholdUseCase>((ref) {
  return SetThresholdUseCase(ref.watch(stockRepositoryProvider));
});

// ── State: current stock levels for a product ─────────────────────────────────

/// Notifier state for stock management of a specific product.
class StockState {
  final List<StockLevelModel> levels;
  final List<StockMovementModel> movements;
  /// True while fetching stock levels, recording entry/adjust/threshold.
  final bool isLoading;
  /// True while fetching movement history — independent of [isLoading]
  /// so the history page spinner is not affected by level load races.
  final bool isLoadingHistory;
  final String? error;
  final bool hasMoreHistory;

  const StockState({
    this.levels = const [],
    this.movements = const [],
    this.isLoading = false,
    this.isLoadingHistory = false,
    this.error,
    this.hasMoreHistory = true,
  });

  StockState copyWith({
    List<StockLevelModel>? levels,
    List<StockMovementModel>? movements,
    bool? isLoading,
    bool? isLoadingHistory,
    String? error,
    bool? hasMoreHistory,
  }) =>
      StockState(
        levels: levels ?? this.levels,
        movements: movements ?? this.movements,
        isLoading: isLoading ?? this.isLoading,
        isLoadingHistory: isLoadingHistory ?? this.isLoadingHistory,
        error: error,
        hasMoreHistory: hasMoreHistory ?? this.hasMoreHistory,
      );
}

/// StockNotifier — manages stock levels and movement history for a product.
///
/// GoF: Observer — Riverpod notifies all listeners on state change.
/// Accessed via [stockNotifierProvider](productId).
///
/// Story 2.3.
@riverpod
class StockNotifier extends _$StockNotifier {
  late final String _productId;

  @override
  StockState build(String productId) {
    _productId = productId;
    // Load levels on creation
    Future.microtask(() => _loadLevels());
    return const StockState(isLoading: true);
  }

  Future<void> _loadLevels() async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      final levels = await ref
          .read(getStockLevelUseCaseProvider)
          .execute(_productId);
      state = state.copyWith(levels: levels, isLoading: false);
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
    }
  }

  /// Loads or refreshes movement history.
  ///
  /// Uses [isLoadingHistory] — independent from [isLoading] (levels flag) —
  /// so concurrent level loading does not reset the history spinner.
  Future<void> loadHistory({
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
  }) async {
    state = state.copyWith(isLoadingHistory: true, error: null);
    try {
      final movements = await ref
          .read(getStockHistoryUseCaseProvider)
          .execute(_productId,
              storeId: storeId,
              movementType: movementType,
              from: from,
              to: to,
              page: page);
      final hasMore = movements.length == 20;
      state = state.copyWith(
        movements: page == 0 ? movements : [...state.movements, ...movements],
        isLoadingHistory: false,
        hasMoreHistory: hasMore,
      );
    } catch (e) {
      state = state.copyWith(isLoadingHistory: false, error: e.toString());
    }
  }

  /// Records a stock entry and refreshes levels.
  Future<bool> recordEntry({
    required String storeId,
    required int quantity,
    String? notes,
  }) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await ref.read(stockRepositoryProvider).recordEntry(
            productId: _productId,
            storeId: storeId,
            quantity: quantity,
            notes: notes,
          );
      await _loadLevels();
      return true;
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
      return false;
    }
  }

  /// Adjusts stock to absolute quantity and refreshes.
  Future<bool> adjustStock({
    required String storeId,
    required int newQuantity,
    required String notes,
  }) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await ref.read(stockRepositoryProvider).adjustStock(
            productId: _productId,
            storeId: storeId,
            newQuantity: newQuantity,
            notes: notes,
          );
      await _loadLevels();
      return true;
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
      return false;
    }
  }

  /// Sets minimum threshold and refreshes levels.
  Future<bool> setThreshold(int minimumThreshold) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await ref.read(setThresholdUseCaseProvider).execute(
            productId: _productId,
            minimumThreshold: minimumThreshold,
          );
      await _loadLevels();
      return true;
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
      return false;
    }
  }

  /// Refreshes both levels and history.
  Future<void> refresh() async {
    await _loadLevels();
  }
}
