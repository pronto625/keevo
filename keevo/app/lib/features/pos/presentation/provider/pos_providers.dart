import 'package:drift/drift.dart' show Variable;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_sale_datasource.dart';
import '../../data/datasource/remote_sale_datasource.dart';
import '../../data/repository/sale_repository_impl.dart';
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

/// Active employee/user ID — read from FlutterSecureStorage.
final activeEmployeeIdProvider = FutureProvider<String?>((ref) async {
  return ref.watch(currentUserIdProvider.future);
});

/// Frequently sold products for POS grid (AC1 — ordered by sale_items.quantity DESC).
/// Falls back to all products if no sales exist yet.
///
/// AutoDispose: POS page is destroyed on tab switch (GoRouter ShellRoute),
/// so the provider re-queries fresh stock on each navigation.
final frequentProductsProvider =
    FutureProvider.autoDispose.family<List<PosProductResult>, String?>((ref, storeId) async {
  if (storeId == null) return [];
  final db = ref.watch(appDatabaseProvider);
  final repo = ref.watch(saleRepositoryProvider);
  final ids = await repo.getFrequentProductIds(storeId, limit: 12);

  if (ids.isEmpty) {
    // Fallback: return all products ordered by name (first-time use / no sales yet)
    final rows = await db.customSelect(
      'SELECT p.id, p.name, p.price, p.photo_url, '
      'COALESCE(d.quantity, 0) as stock '
      'FROM products p '
      'LEFT JOIN ('
      '  SELECT sl.product_id, sl.quantity '
      '  FROM stock_levels sl '
      '  WHERE sl.store_id = ? '
      '  GROUP BY sl.product_id '
      '  HAVING sl.updated_at = MAX(sl.updated_at)'
      ') d ON d.product_id = p.id '
      'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
      'LIMIT 12',
      variables: [Variable.withString(storeId)],
    ).get();
    return rows
        .map((r) => PosProductResult(
              id: r.read<String>('id'),
              name: r.read<String>('name'),
              price: r.read<int>('price'),
              stock: r.read<int>('stock'),
              photoUrl: r.readNullable<String>('photo_url'),
            ))
        .toList();
  }

  // Fetch product details for frequent IDs, preserving frequency order
  final placeholders = ids.map((_) => '?').join(',');
  final rows = await db.customSelect(
    'SELECT p.id, p.name, p.price, p.photo_url, '
    'COALESCE(d.quantity, 0) as stock '
    'FROM products p '
    'LEFT JOIN ('
    '  SELECT sl.product_id, sl.quantity '
    '  FROM stock_levels sl '
    '  WHERE sl.store_id = ? '
    '  GROUP BY sl.product_id '
    '  HAVING sl.updated_at = MAX(sl.updated_at)'
    ') d ON d.product_id = p.id '
    'WHERE p.id IN ($placeholders)',
    variables: [
      Variable.withString(storeId),
      ...ids.map(Variable.withString),
    ],
  ).get();

  // Re-sort by frequency order
  final byId = <String, dynamic>{};
  for (final r in rows) {
    byId[r.read<String>('id')] = r;
  }
  return ids
      .where((id) => byId.containsKey(id))
      .map((id) {
        final r = byId[id];
        return PosProductResult(
          id: r.read<String>('id'),
          name: r.read<String>('name'),
          price: r.read<int>('price'),
          stock: r.read<int>('stock'),
          photoUrl: r.readNullable<String>('photo_url'),
        );
      })
      .toList();
});
