import 'package:drift/drift.dart' show Variable;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';

/// Product search result for POS display.
class PosProductResult {
  final String id;
  final String name;
  final int price;
  final int stock;
  final String? photoUrl;

  const PosProductResult({
    required this.id,
    required this.name,
    required this.price,
    required this.stock,
    this.photoUrl,
  });
}

/// PosSearchNotifier — fuzzy product search from local Drift products table.
///
/// Returns results sorted with out-of-stock products at the bottom (AC2).
class PosSearchNotifier extends Notifier<AsyncValue<List<PosProductResult>>> {
  @override
  AsyncValue<List<PosProductResult>> build() => const AsyncData([]);

  Future<void> search(String query, String? storeId) async {
    if (query.length < 2) {
      state = const AsyncData([]);
      return;
    }

    state = const AsyncLoading();
    try {
      final db = ref.read(appDatabaseProvider);
      final lowerQuery = '%${query.toLowerCase()}%';
      final List<dynamic> rows;

      if (storeId != null) {
        rows = await db.customSelect(
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
          'WHERE LOWER(p.name) LIKE ? '
          'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
          'LIMIT 50',
          variables: [
            Variable.withString(storeId),
            Variable.withString(lowerQuery),
          ],
        ).get();
      } else {
        rows = await db.customSelect(
          'SELECT p.id, p.name, p.price, p.photo_url, '
          'COALESCE(agg.total_stock, 0) as stock '
          'FROM products p '
          'LEFT JOIN ('
          '  SELECT d.product_id, SUM(d.quantity) as total_stock '
          '  FROM ('
          '    SELECT sl.product_id, sl.store_id, sl.quantity '
          '    FROM stock_levels sl '
          '    GROUP BY sl.product_id, sl.store_id '
          '    HAVING sl.updated_at = MAX(sl.updated_at)'
          '  ) d '
          '  GROUP BY d.product_id'
          ') agg ON agg.product_id = p.id '
          'WHERE LOWER(p.name) LIKE ? '
          'ORDER BY (CASE WHEN COALESCE(agg.total_stock, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
          'LIMIT 50',
          variables: [
            Variable.withString(lowerQuery),
          ],
        ).get();
      }

      final results = rows.map((r) => PosProductResult(
            id: r.read<String>('id'),
            name: r.read<String>('name'),
            price: r.read<int>('price'),
            stock: r.read<int>('stock'),
            photoUrl: r.readNullable<String>('photo_url'),
          )).toList();

      state = AsyncData(results);
    } catch (e, st) {
      state = AsyncError(e, st);
    }
  }

  void clear() => state = const AsyncData([]);
}

final posSearchProvider = NotifierProvider<PosSearchNotifier,
    AsyncValue<List<PosProductResult>>>(PosSearchNotifier.new);
