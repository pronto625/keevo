import 'package:drift/drift.dart' show Variable;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/search_fold.dart';

/// Product search result for POS display.
class PosProductResult {
  final String id;
  final String name;
  final int price;
  final int stock;
  final String? photoUrl;
  final String? categoryName;
  final String status;

  const PosProductResult({
    required this.id,
    required this.name,
    required this.price,
    required this.stock,
    this.photoUrl,
    this.categoryName,
    this.status = 'ACTIVE',
  });

  bool get isDraft => status == 'DRAFT';
}

/// PosSearchNotifier — fuzzy product search from local Drift products table.
///
/// Returns results sorted with out-of-stock products at the bottom (AC2).
class PosSearchNotifier extends Notifier<AsyncValue<List<PosProductResult>>> {
  String _lastQuery = '';

  String get lastQuery => _lastQuery;

  @override
  AsyncValue<List<PosProductResult>> build() => const AsyncData([]);

  Future<void> search(String query, String? storeId) async {
    _lastQuery = query;
    if (query.length < 2) {
      state = const AsyncData([]);
      return;
    }

    state = const AsyncLoading();
    try {
      final db = ref.read(appDatabaseProvider);
      // v1s-16-16: case- and accent-insensitive (shared Unicode fold).
      final foldedPattern = likeContainsFolded(query);
      final foldedName = foldedSqlRaw('p.name');
      final List<dynamic> rows;

      if (storeId != null) {
        rows = await db.customSelect(
          'SELECT p.id, p.name, p.price, p.photo_url, p.status, '
          'COALESCE(d.quantity, 0) as stock '
          'FROM products p '
          'LEFT JOIN ('
          '  SELECT sl.product_id, sl.quantity '
          '  FROM stock_levels sl '
          '  WHERE sl.store_id = ? '
          '  GROUP BY sl.product_id'
          ') d ON d.product_id = p.id '
          "WHERE p.archived = 0 AND p.status IN ('ACTIVE', 'DRAFT') AND $foldedName LIKE ? ESCAPE '\\' "
          'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
          'LIMIT 50',
          variables: [
            Variable.withString(storeId),
            Variable.withString(foldedPattern),
          ],
        ).get();
      } else {
        rows = await db.customSelect(
          'SELECT p.id, p.name, p.price, p.photo_url, p.status, '
          'COALESCE(agg.total_stock, 0) as stock '
          'FROM products p '
          'LEFT JOIN ('
          '  SELECT d.product_id, SUM(d.quantity) as total_stock '
          '  FROM ('
          '    SELECT sl.product_id, sl.store_id, sl.quantity '
          '    FROM stock_levels sl '
          '    GROUP BY sl.product_id, sl.store_id'
          '  ) d '
          '  GROUP BY d.product_id'
          ') agg ON agg.product_id = p.id '
          "WHERE p.archived = 0 AND p.status IN ('ACTIVE', 'DRAFT') AND $foldedName LIKE ? ESCAPE '\\' "
          'ORDER BY (CASE WHEN COALESCE(agg.total_stock, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
          'LIMIT 50',
          variables: [
            Variable.withString(foldedPattern),
          ],
        ).get();
      }

      final results = rows.map((r) => PosProductResult(
            id: r.read<String>('id'),
            name: r.read<String>('name'),
            price: r.read<int>('price'),
            stock: r.read<int>('stock'),
            photoUrl: r.readNullable<String>('photo_url'),
            status: r.read<String>('status'),
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
