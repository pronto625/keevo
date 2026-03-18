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

/// Active employee/user ID — read from FlutterSecureStorage.
final activeEmployeeIdProvider = FutureProvider<String?>((ref) async {
  return ref.watch(currentUserIdProvider.future);
});

/// Frequently sold products for POS grid (AC1 — ordered by sale_items.quantity DESC).
/// Always shows ALL active products; frequent ones appear first.
///
/// AutoDispose: POS page is destroyed on tab switch (GoRouter ShellRoute),
/// so the provider re-queries fresh stock on each navigation.
final frequentProductsProvider =
    FutureProvider.autoDispose.family<List<PosProductResult>, String?>((ref, storeId) async {
  if (storeId == null) return [];

  // Sync stock for active store from remote so POS has fresh data.
  // Silently ignore errors — offline-first: will use cached local data.
  try {
    final stockRepo = ref.watch(multiStoreStockRepositoryProvider);
    await stockRepo.getStoreStockDetail(storeId, page: 0, size: 100, sortLowFirst: false);
  } catch (_) {}

  final db = ref.watch(appDatabaseProvider);
  final repo = ref.watch(saleRepositoryProvider);
  final ids = await repo.getFrequentProductIds(storeId, limit: 12);

  // Build ORDER BY clause: frequent products first (by their position in ids),
  // then products with stock, then alphabetically.
  // Always show ALL active non-archived products so brand-new products are visible.
  final String frequentOrder;
  final List<Variable> variables;
  if (ids.isEmpty) {
    frequentOrder = '1'; // constant — no frequency to sort by
    variables = [Variable.withString(storeId)];
  } else {
    final placeholders = ids.map((_) => '?').join(',');
    frequentOrder = 'CASE WHEN p.id IN ($placeholders) THEN 0 ELSE 1 END';
    variables = [
      Variable.withString(storeId),
      ...ids.map(Variable.withString),
    ];
  }

  final rows = await db.customSelect(
    'SELECT p.id, p.name, p.price, p.photo_url, '
    'COALESCE(d.quantity, 0) as stock '
    'FROM products p '
    'LEFT JOIN ('
    '  SELECT sl.product_id, sl.quantity '
    '  FROM stock_levels sl '
    '  WHERE sl.store_id = ? '
    '  GROUP BY sl.product_id'
    ') d ON d.product_id = p.id '
    'WHERE p.archived = 0 AND p.status = \'ACTIVE\' '
    'ORDER BY $frequentOrder ASC, '
    '(CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, '
    'p.name ASC '
    'LIMIT 12',
    variables: variables,
  ).get();

  final results = rows
      .map((r) => PosProductResult(
            id: r.read<String>('id'),
            name: r.read<String>('name'),
            price: r.read<int>('price'),
            stock: r.read<int>('stock'),
            photoUrl: r.readNullable<String>('photo_url'),
          ))
      .toList();

  // Re-sort frequent products to respect their original frequency order.
  if (ids.isNotEmpty) {
    final freqFirst = results.where((p) => ids.contains(p.id)).toList()
      ..sort((a, b) => ids.indexOf(a.id).compareTo(ids.indexOf(b.id)));
    final rest = results.where((p) => !ids.contains(p.id)).toList();
    return [...freqFirst, ...rest];
  }
  return results;
});
