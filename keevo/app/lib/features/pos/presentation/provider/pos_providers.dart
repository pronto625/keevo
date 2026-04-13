import 'package:drift/drift.dart' show Variable;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../inventory/presentation/provider/global_stock_provider.dart';
import '../../data/datasource/local_sale_datasource.dart';
import '../../data/datasource/remote_sale_datasource.dart';
import '../../data/repository/sale_repository_impl.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/repository/sale_repository.dart';
import '../../domain/usecase/record_sale_usecase.dart';
import 'pos_search_provider.dart';

export 'pos_search_provider.dart' show PosProductResult;

/// LocalSaleDataSource DI
final localSaleDataSourceProvider = Provider<LocalSaleDataSource>((ref) {
  return LocalSaleDataSource(ref.watch(appDatabaseProvider));
});

/// RemoteSaleDataSource DI
final remoteSaleDataSourceProvider = Provider<RemoteSaleDataSource>((ref) {
  return RemoteSaleDataSource(ref.watch(dioProvider));
});

/// SaleRepository DI
final saleRepositoryProvider = Provider<SaleRepository>((ref) {
  return SaleRepositoryImpl(
    ref.watch(localSaleDataSourceProvider),
    ref.watch(remoteSaleDataSourceProvider),
    ref.watch(appDatabaseProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

/// RecordSaleUseCase DI
final recordSaleUseCaseProvider = Provider<RecordSaleUseCase>((ref) {
  return RecordSaleUseCase(ref.watch(saleRepositoryProvider));
});

/// Pending validation sales for OWNER review (Story 4.3).
final pendingSalesProvider =
    FutureProvider.autoDispose.family<List<Sale>, String?>((ref, storeId) async {
  final repo = ref.watch(saleRepositoryProvider);
  return repo.getPendingSales(storeId);
});

/// Count of pending sales for navigation badge (Story 4.3).
final pendingSalesCountProvider =
    FutureProvider.autoDispose.family<int, String?>((ref, storeId) async {
  final repo = ref.watch(saleRepositoryProvider);
  return repo.countPendingSales(storeId);
});

/// Single sale by ID for detail page (Story 4.4).
final saleByIdProvider =
    FutureProvider.autoDispose.family<Sale?, String>((ref, saleId) async {
  final repo = ref.watch(saleRepositoryProvider);
  return repo.getSaleById(saleId);
});

/// Active employee/user ID — read from FlutterSecureStorage.
final activeEmployeeIdProvider = FutureProvider<String?>((ref) async {
  return ref.watch(currentUserIdProvider.future);
});

const _kPosGridPageSize = 24;

/// State for the POS product grid — holds the current page of products
/// plus pagination metadata.
class FrequentProductsState {
  final List<PosProductResult> products;
  final bool hasMore;
  final bool isLoadingMore;

  const FrequentProductsState({
    required this.products,
    this.hasMore = true,
    this.isLoadingMore = false,
  });

  FrequentProductsState copyWith({
    List<PosProductResult>? products,
    bool? hasMore,
    bool? isLoadingMore,
  }) =>
      FrequentProductsState(
        products: products ?? this.products,
        hasMore: hasMore ?? this.hasMore,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      );
}

/// Paginated POS product grid notifier.
///
/// [build()] loads the first page ([_kPosGridPageSize] items) on first watch
/// or after invalidation. [loadMore()] appends the next page when the user
/// scrolls near the bottom.
///
/// AutoDispose: recreated fresh on each POS page navigation (GoRouter
/// ShellRoute destroys the page on tab switch).
///
/// Tenant isolation: every SQL query includes `WHERE sl.store_id = storeId`.
/// The local Drift DB only contains data synced for the authenticated tenant
/// (the backend JWT already enforces per-tenant schema isolation at sync time),
/// so no cross-tenant leakage is possible.
class FrequentProductsNotifier extends AutoDisposeFamilyAsyncNotifier<
    FrequentProductsState, ({String? storeId, String? categoryId})> {
  int _offset = 0;
  bool _hasMore = true;
  bool _isLoadingMore = false;
  List<String> _ids = [];

  @override
  Future<FrequentProductsState> build(
      ({String? storeId, String? categoryId}) arg) async {
    _offset = 0;
    _hasMore = true;
    _isLoadingMore = false;
    _ids = [];

    final storeId = arg.storeId;
    if (storeId == null) {
      _hasMore = false;
      return const FrequentProductsState(products: [], hasMore: false);
    }

    // Sync stock from remote — offline-first, errors are silently ignored.
    try {
      await ref
          .read(multiStoreStockRepositoryProvider)
          .getStoreStockDetail(storeId, page: 0, size: 100, sortLowFirst: false);
    } catch (_) {}

    // Fetch frequent product IDs (most recently sold) for priority ordering.
    _ids = await ref
        .read(saleRepositoryProvider)
        .getFrequentProductIds(storeId, limit: 50);

    final products =
        await _fetchPage(storeId: storeId, categoryId: arg.categoryId, offset: 0);
    _offset = _kPosGridPageSize;
    return FrequentProductsState(products: products, hasMore: _hasMore);
  }

  bool get isLoadingMore => _isLoadingMore;

  /// Appends the next page. No-op if already loading or no more pages.
  Future<void> loadMore() async {
    if (!_hasMore || _isLoadingMore) return;
    final current = state.valueOrNull;
    if (current == null) return;
    final storeId = arg.storeId;
    if (storeId == null) return;

    _isLoadingMore = true;
    state = AsyncData(current.copyWith(isLoadingMore: true));

    try {
      final page = await _fetchPage(
          storeId: storeId, categoryId: arg.categoryId, offset: _offset);
      _offset += _kPosGridPageSize;
      state = AsyncData(FrequentProductsState(
        products: [...current.products, ...page],
        hasMore: _hasMore,
        isLoadingMore: false,
      ));
    } catch (_) {
      state = AsyncData(current.copyWith(isLoadingMore: false));
    } finally {
      _isLoadingMore = false;
    }
  }

  Future<List<PosProductResult>> _fetchPage({
    required String storeId,
    required String? categoryId,
    required int offset,
  }) async {
    final db = ref.read(appDatabaseProvider);

    final String categoryClause;
    Variable? categoryVar;
    if (categoryId != null) {
      categoryClause = 'AND p.category_id = ? ';
      categoryVar = Variable.withString(categoryId);
    } else {
      categoryClause = '';
    }

    final String frequentOrder;
    final List<Variable> frequentOrderVars;
    if (_ids.isEmpty) {
      frequentOrder = '1';
      frequentOrderVars = [];
    } else {
      final placeholders = _ids.map((_) => '?').join(',');
      frequentOrder = 'CASE WHEN p.id IN ($placeholders) THEN 0 ELSE 1 END';
      frequentOrderVars = _ids.map(Variable.withString).toList();
    }

    // Variable order: storeId, categoryId?, ...ids, limit+1, offset
    final variables = [
      Variable.withString(storeId),
      if (categoryVar != null) categoryVar,
      ...frequentOrderVars,
      Variable.withInt(_kPosGridPageSize + 1), // +1 to detect hasMore
      Variable.withInt(offset),
    ];

    final rows = await db.customSelect(
      'SELECT p.id, p.name, p.price, p.photo_url, '
      'COALESCE(d.quantity, 0) as stock, '
      'c.name as category_name '
      'FROM products p '
      'LEFT JOIN ('
      '  SELECT sl.product_id, sl.quantity '
      '  FROM stock_levels sl '
      '  WHERE sl.store_id = ? '
      '  GROUP BY sl.product_id'
      ') d ON d.product_id = p.id '
      'LEFT JOIN categories c ON c.id = p.category_id '
      'WHERE p.archived = 0 AND p.status = \'ACTIVE\' '
      '$categoryClause'
      'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, '
      '$frequentOrder ASC, '
      'p.name ASC '
      'LIMIT ? OFFSET ?',
      variables: variables,
    ).get();

    _hasMore = rows.length > _kPosGridPageSize;
    final limited = _hasMore ? rows.take(_kPosGridPageSize).toList() : rows;
    return limited
        .map((r) => PosProductResult(
              id: r.read<String>('id'),
              name: r.read<String>('name'),
              price: r.read<int>('price'),
              stock: r.read<int>('stock'),
              photoUrl: r.readNullable<String>('photo_url'),
              categoryName: r.readNullable<String>('category_name'),
            ))
        .toList();
  }
}

/// Paginated POS product grid provider.
///
/// Keyed by (storeId, categoryId). A new notifier is instantiated for each
/// unique key combination, so changing the category filter resets the page
/// automatically.
final frequentProductsProvider = AsyncNotifierProvider.autoDispose.family<
    FrequentProductsNotifier,
    FrequentProductsState,
    ({String? storeId, String? categoryId})>(FrequentProductsNotifier.new);
