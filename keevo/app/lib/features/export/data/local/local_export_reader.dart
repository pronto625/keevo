import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../../audit/domain/audit_format.dart';
import '../../domain/local/local_export_schema.dart';

/// Store / period filters of an on-device export. A dataset applies only the
/// filters it declares (`storeFilter`, `periodFilter` of the catalogue);
/// [from] and [to] are inclusive bounds on its event column.
class LocalExportFilter {
  final String? storeId;
  final DateTime? from;
  final DateTime? to;

  const LocalExportFilter({this.storeId, this.from, this.to});
}

/// One exported row: sendable primitives only (`String`, `int`, `bool`,
/// `DateTime`, `null`), in the dataset's column order.
typedef LocalExportRow = List<Object?>;

/// LocalExportReader — pages every export dataset out of Drift (v1s-18-5).
///
/// Same columns, order, filters and French labels as the server providers
/// (18-2). v1s-16-20: ids are only read to join / aggregate, never written
/// into a row. Runs on the main isolate (the SQLCipher-opened database cannot be
/// used from a background isolate) in small pages: `LIMIT/OFFSET` over a
/// total order ending in the row id, plus per-page id lookups for
/// aggregates. Only small reference maps (stores, categories, actor names)
/// are preloaded.
class LocalExportReader {
  final AppDatabase _db;

  LocalExportReader(this._db);

  /// Backing table of each dataset (its local row count).
  static const backingTables = <String, String>{
    'products': 'products',
    'categories': 'categories',
    'stock_levels': 'stock_levels',
    'stock_movements': 'stock_movements',
    'stock_transfers': 'stock_transfers',
    'sales': 'sales',
    'sale_items': 'sale_items',
    'day_closures': 'day_closures',
    'inventory_sessions': 'inventory_sessions',
    'inventory_counts': 'inventory_counts',
    'clients': 'clients',
    'suppliers': 'suppliers',
    'employees': 'employees',
    'stores': 'stores',
    'reports': 'reports',
    'audit_log': 'audit_entries',
  };

  /// Local row count of every catalogue dataset.
  Future<Map<String, int>> counts() async {
    final result = <String, int>{};
    for (final d in localExportCatalog) {
      final table = backingTables[d.key];
      if (table == null) {
        result[d.key] = 0;
        continue;
      }
      final row = await _db
          .customSelect('SELECT COUNT(*) AS c FROM $table')
          .getSingle();
      result[d.key] = row.read<int>('c');
    }
    return result;
  }

  /// Rows of dataset [key] matching [filter], [pageSize] rows at most per
  /// emitted page.
  Stream<List<LocalExportRow>> pages(String key, LocalExportFilter filter,
      {int pageSize = 500}) async* {
    if (pageSize < 1) throw ArgumentError.value(pageSize, 'pageSize');
    final dataset = localExportDataset(key);
    if (dataset == null) throw ArgumentError.value(key, 'key');
    final f = LocalExportFilter(
      storeId: dataset.storeFilter ? filter.storeId : null,
      from: dataset.periodFilter ? filter.from : null,
      to: dataset.periodFilter ? filter.to : null,
    );
    final spec = _spec(key, f);
    final refs = await _References.load(_db);
    var offset = 0;
    while (true) {
      final rows = await _db.customSelect(
        '${spec.query} LIMIT ? OFFSET ?',
        variables: [
          ...spec.variables,
          Variable<int>(pageSize),
          Variable<int>(offset),
        ],
      ).get();
      if (rows.isEmpty) break;
      yield await spec.map(rows, refs);
      if (rows.length < pageSize) break;
      offset += rows.length;
    }
  }

  // ── Dataset queries ────────────────────────────────────────────────────────

  _Spec _spec(String key, LocalExportFilter f) {
    final where = _Where();
    switch (key) {
      case 'products':
        return _Spec(
          'SELECT p.id, p.sku, p.name, p.category_id, p.status, p.price,'
          ' p.buy_price, p.transport_cost, p.archived, p.created_at,'
          ' p.updated_at FROM products p'
          ' ORDER BY julianday(p.created_at), p.id',
          const [],
          (rows, refs) => _products(rows, refs, f.storeId),
        );
      case 'categories':
        return _Spec(
          'SELECT c.id, c.name, c.parent_id, c.is_active, c.is_custom,'
          ' c.created_at FROM categories c'
          ' ORDER BY julianday(c.created_at), c.id',
          const [],
          (rows, refs) async => [
            for (final r in rows)
              [
                r.read<String>('name'),
                refs.categories[r.readNullable<String>('parent_id')],
                r.read<bool>('is_active'),
                r.read<bool>('is_custom'),
                r.read<DateTime>('created_at'),
              ],
          ],
        );
      case 'stock_levels':
        where.store('sl.store_id', f);
        return _Spec(
          'SELECT sl.id, sl.store_id, sl.product_id, sl.quantity,'
          ' sl.minimum_threshold, sl.updated_at, st.name AS store_name,'
          ' p.sku, p.name AS product_name, p.category_id, p.buy_price,'
          ' p.archived FROM stock_levels sl'
          ' LEFT JOIN stores st ON st.id = sl.store_id'
          ' LEFT JOIN products p ON p.id = sl.product_id'
          '${where.sql}'
          " ORDER BY COALESCE(st.name, ''), COALESCE(p.name, ''), sl.id",
          where.variables,
          (rows, refs) async => [for (final r in rows) _stockLevel(r, refs)],
        );
      case 'stock_movements':
        where
          ..store('m.store_id', f)
          ..period('m.created_at', f);
        return _Spec(
          'SELECT m.id, m.created_at, st.name AS store_name, p.sku,'
          ' p.name AS product_name, m.type, m.quantity_before,'
          ' m.quantity_delta, m.quantity_after, m.actor_id, m.reason'
          ' FROM stock_movements m'
          ' LEFT JOIN stores st ON st.id = m.store_id'
          ' LEFT JOIN products p ON p.id = m.product_id'
          '${where.sql}'
          ' ORDER BY julianday(m.created_at), m.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                r.read<DateTime>('created_at'),
                r.readNullable<String>('store_name'),
                r.readNullable<String>('sku'),
                r.readNullable<String>('product_name'),
                exportLabel(exportLabels['stock_movements.type'],
                    r.readNullable<String>('type')),
                r.readNullable<int>('quantity_before'),
                r.readNullable<int>('quantity_delta'),
                r.readNullable<int>('quantity_after'),
                refs.actor(r.readNullable<String>('actor_id')),
                AuditFormat.notes(r.readNullable<String>('reason')),
              ],
          ],
        );
      case 'stock_transfers':
        if (f.storeId != null) {
          where.raw('(t.source_store_id = ? OR t.destination_store_id = ?)',
              [Variable<String>(f.storeId!), Variable<String>(f.storeId!)]);
        }
        where.period('t.occurred_at', f);
        return _Spec(
          'SELECT t.id, t.occurred_at, src.name AS src_name,'
          ' t.source_store_name, dst.name AS dst_name,'
          ' t.destination_store_name, p.sku, p.name AS product_name,'
          ' t.product_name AS product_snapshot, t.quantity, t.status,'
          ' t.actor_id, t.notes FROM stock_transfers t'
          ' LEFT JOIN stores src ON src.id = t.source_store_id'
          ' LEFT JOIN stores dst ON dst.id = t.destination_store_id'
          ' LEFT JOIN products p ON p.id = t.product_id'
          '${where.sql}'
          ' ORDER BY julianday(t.occurred_at), t.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                r.read<DateTime>('occurred_at'),
                r.readNullable<String>('src_name') ??
                    _nonEmpty(r.readNullable<String>('source_store_name')),
                r.readNullable<String>('dst_name') ??
                    _nonEmpty(r.readNullable<String>('destination_store_name')),
                r.readNullable<String>('sku'),
                r.readNullable<String>('product_name') ??
                    _nonEmpty(r.readNullable<String>('product_snapshot')),
                r.readNullable<int>('quantity'),
                exportLabel(exportLabels['stock_transfers.status'],
                    r.readNullable<String>('status')),
                refs.actor(r.readNullable<String>('actor_id')),
                AuditFormat.notes(r.readNullable<String>('notes')),
              ],
          ],
        );
      case 'sales':
        where
          ..store('s.store_id', f)
          ..period(_saleDate, f);
        return _Spec(
          'SELECT s.id, $_saleDate AS sale_date, st.name AS store_name,'
          ' s.employee_id, cl.name AS client_name, s.status, s.payment_mode,'
          ' s.mobile_money_ref, s.discount_amount, s.total_amount FROM sales s'
          ' LEFT JOIN stores st ON st.id = s.store_id'
          ' LEFT JOIN clients cl ON cl.id = s.client_id'
          '${where.sql}'
          ' ORDER BY julianday($_saleDate), s.id',
          where.variables,
          _sales,
        );
      case 'sale_items':
        where
          ..store('s.store_id', f)
          ..period(_saleDate, f);
        return _Spec(
          'SELECT i.id, s.id AS sale_id, $_saleDate AS sale_date,'
          ' st.name AS store_name, s.employee_id, s.status, i.product_id,'
          ' p.sku, i.product_name, i.quantity, i.catalogue_unit_price,'
          ' i.unit_price, i.subtotal FROM sale_items i'
          ' JOIN sales s ON s.id = i.sale_id'
          ' LEFT JOIN stores st ON st.id = s.store_id'
          ' LEFT JOIN products p ON p.id = i.product_id'
          '${where.sql}'
          ' ORDER BY julianday($_saleDate), s.id, i.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                exportSaleReference(r.read<DateTime>('sale_date'),
                    r.readNullable<String>('store_name')),
                r.read<DateTime>('sale_date'),
                r.readNullable<String>('store_name'),
                refs.actor(r.readNullable<String>('employee_id')),
                exportLabel(exportLabels['sale_items.saleStatus'],
                    r.readNullable<String>('status')),
                r.readNullable<String>('sku'),
                r.readNullable<String>('product_name'),
                r.readNullable<int>('quantity'),
                r.readNullable<int>('catalogue_unit_price'),
                r.readNullable<int>('unit_price'),
                r.readNullable<int>('subtotal'),
              ],
          ],
        );
      case 'day_closures':
        where
          ..store('d.store_id', f)
          ..period('d.closed_at', f);
        return _Spec(
          'SELECT d.id, d.closed_at, st.name AS store_name, d.is_automatic,'
          ' d.actor_id, d.total_sales, d.total_revenue, d.cash_amount,'
          ' d.momo_amount, d.pending_sales_count, d.pending_sales_total,'
          ' d.top_product_name, d.top_product_qty FROM day_closures d'
          ' LEFT JOIN stores st ON st.id = d.store_id'
          '${where.sql}'
          ' ORDER BY julianday(d.closed_at), d.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                // closureDate: the WAT date of closedAt (DATE cell) — an
                // approximation: the server exports the stored
                // closure_date, which the device does not keep.
                r.read<DateTime>('closed_at'),
                r.read<DateTime>('closed_at'),
                r.readNullable<String>('store_name'),
                r.readNullable<bool>('is_automatic'),
                refs.actor(r.readNullable<String>('actor_id')),
                r.readNullable<int>('total_sales'),
                r.readNullable<int>('total_revenue'),
                r.readNullable<int>('cash_amount'),
                r.readNullable<int>('momo_amount'),
                r.readNullable<int>('pending_sales_count'),
                r.readNullable<int>('pending_sales_total'),
                r.readNullable<String>('top_product_name'),
                r.readNullable<int>('top_product_qty'),
              ],
          ],
        );
      case 'inventory_sessions':
        where
          ..store('s.store_id', f)
          ..period('s.started_at', f);
        return _Spec(
          'SELECT s.id, s.started_at, st.name AS store_name, s.scope,'
          ' s.status, s.started_by, s.completed_at, s.cancelled_at'
          ' FROM inventory_sessions s'
          ' LEFT JOIN stores st ON st.id = s.store_id'
          '${where.sql}'
          ' ORDER BY julianday(s.started_at), s.id',
          where.variables,
          _inventorySessions,
        );
      case 'inventory_counts':
        where
          ..store('s.store_id', f)
          ..period('s.started_at', f);
        return _Spec(
          'SELECT c.id, s.id AS session_id, s.started_at,'
          ' st.name AS store_name, s.status, c.product_id, c.product_name,'
          ' c.variant_label, c.theoretical, c.physical, c.counted_at,'
          ' c.counted_by FROM inventory_counts c'
          ' JOIN inventory_sessions s ON s.id = c.session_id'
          ' LEFT JOIN stores st ON st.id = s.store_id'
          '${where.sql}'
          ' ORDER BY julianday(s.started_at), s.id, c.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows) _inventoryCount(r, refs),
          ],
        );
      case 'reports':
        where
          ..store('r.store_id', f)
          ..period('r.created_at', f);
        return _Spec(
          'SELECT r.id, r.report_date, r.report_type,'
          ' COALESCE(st.name, r.store_name) AS store_name, r.total_revenue,'
          ' r.total_sales, r.is_automatic, r.actor_id, r.delivery_status,'
          ' r.created_at, r.content FROM reports r'
          ' LEFT JOIN stores st ON st.id = r.store_id'
          '${where.sql}'
          ' ORDER BY julianday(r.created_at), r.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                r.readNullable<DateTime>('report_date'),
                exportLabel(exportLabels['reports.type'],
                    r.readNullable<String>('report_type')),
                r.readNullable<String>('store_name'),
                r.readNullable<int>('total_revenue'),
                r.readNullable<int>('total_sales'),
                r.readNullable<bool>('is_automatic'),
                refs.actor(r.readNullable<String>('actor_id')),
                exportLabel(exportLabels['reports.deliveryStatus'],
                    r.readNullable<String>('delivery_status')),
                r.read<DateTime>('created_at'),
                r.readNullable<String>('content'),
              ],
          ],
        );
      case 'clients':
        return _Spec(
          'SELECT c.id, c.name, c.phone, c.email, c.notes, c.archived,'
          ' c.created_at FROM clients c'
          ' ORDER BY julianday(c.created_at), c.id',
          const [],
          _clients,
        );
      case 'suppliers':
        return _Spec(
          'SELECT s.id, s.name, s.phone, s.email, s.archived, s.created_at'
          ' FROM suppliers s ORDER BY julianday(s.created_at), s.id',
          const [],
          _suppliers,
        );
      case 'employees':
        where.store('e.store_id', f);
        return _Spec(
          'SELECT e.id, e.first_name, e.last_name, u.phone_number,'
          ' st.name AS store_name, e.status, e.created_at FROM employees e'
          ' LEFT JOIN users u ON u.id = e.user_id'
          ' LEFT JOIN stores st ON st.id = e.store_id'
          '${where.sql}'
          ' ORDER BY julianday(e.created_at), e.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                r.readNullable<String>('first_name'),
                r.readNullable<String>('last_name'),
                r.readNullable<String>('phone_number'),
                r.readNullable<String>('store_name'),
                exportLabel(exportLabels['employees.status'],
                    r.readNullable<String>('status')),
                r.read<DateTime>('created_at'),
              ],
          ],
        );
      case 'stores':
        where.store('s.id', f);
        return _Spec(
          'SELECT s.id, s.name, s.type, s.address, s.phone, s.is_active,'
          ' s.created_at FROM stores s'
          '${where.sql}'
          ' ORDER BY julianday(s.created_at), s.id',
          where.variables,
          (rows, refs) async => [
            for (final r in rows)
              [
                r.readNullable<String>('name'),
                exportLabel(
                    exportLabels['stores.type'], r.readNullable<String>('type')),
                r.readNullable<String>('address'),
                r.readNullable<String>('phone'),
                r.readNullable<bool>('is_active'),
                r.read<DateTime>('created_at'),
              ],
          ],
        );
      case 'audit_log':
        where
          ..store('a.store_id', f)
          ..period('a.occurred_at', f);
        return _Spec(
          'SELECT a.id, a.occurred_at, a.user_id, a.action, a.entity_type,'
          ' a.entity_id, a.entity_label, st.name AS store_name, a.source,'
          ' a.device_id, a.value_before, a.value_after, a.metadata'
          ' FROM audit_entries a'
          ' LEFT JOIN stores st ON st.id = a.store_id'
          '${where.sql}'
          ' ORDER BY julianday(a.occurred_at) DESC, a.id DESC',
          where.variables,
          (rows, refs) async => [for (final r in rows) _audit(r, refs)],
        );
    }
    throw ArgumentError.value(key, 'key');
  }

  static const _saleDate = 'COALESCE(s.occurred_at, s.created_at)';

  // ── Row mappers ────────────────────────────────────────────────────────────

  Future<List<LocalExportRow>> _products(
      List<QueryRow> rows, _References refs, String? storeId) async {
    final ids = [for (final r in rows) r.read<String>('id')];
    final stock = <String, int>{};
    for (final r in await _db.customSelect(
      'SELECT product_id, COALESCE(SUM(quantity), 0) AS q FROM stock_levels'
      ' WHERE product_id IN (${_marks(ids.length)})'
      '${storeId != null ? ' AND store_id = ?' : ''} GROUP BY product_id',
      variables: [
        for (final id in ids) Variable<String>(id),
        if (storeId != null) Variable<String>(storeId),
      ],
    ).get()) {
      stock[r.read<String>('product_id')] = r.read<int>('q');
    }
    final suppliers = <String, List<String>>{};
    for (final r in await _db.customSelect(
      'SELECT ps.product_id, s.name FROM product_suppliers ps'
      ' JOIN suppliers s ON s.id = ps.supplier_id'
      ' WHERE ps.product_id IN (${_marks(ids.length)})'
      ' ORDER BY s.name',
      variables: [for (final id in ids) Variable<String>(id)],
    ).get()) {
      (suppliers[r.read<String>('product_id')] ??= [])
          .add(r.read<String>('name'));
    }
    return [
      for (final r in rows)
        [
          r.readNullable<String>('sku'),
          r.readNullable<String>('name'),
          refs.categories[r.readNullable<String>('category_id')],
          exportLabel(exportLabels['products.status'],
              r.readNullable<String>('status')),
          r.readNullable<int>('price'),
          r.readNullable<int>('buy_price'),
          r.readNullable<int>('transport_cost'),
          null, // minimumThreshold: not stored on the device
          stock[r.read<String>('id')] ?? 0,
          suppliers[r.read<String>('id')]?.join(', '),
          r.readNullable<bool>('archived'),
          r.read<DateTime>('created_at'),
          r.read<DateTime>('updated_at'),
        ],
    ];
  }

  LocalExportRow _stockLevel(QueryRow r, _References refs) {
    final quantity = r.readNullable<int>('quantity');
    final ownThreshold = r.readNullable<int>('minimum_threshold') ?? 0;
    final threshold = ownThreshold > 0 ? ownThreshold : 0;
    final buyPrice = r.readNullable<int>('buy_price');
    final q = quantity ?? 0;
    return [
      r.readNullable<String>('store_name'),
      r.readNullable<String>('sku'),
      r.readNullable<String>('product_name'),
      refs.categories[r.readNullable<String>('category_id')],
      quantity,
      threshold,
      stockStatus(q, threshold),
      buyPrice,
      q <= 0 || buyPrice == null ? 0 : q * buyPrice,
      r.readNullable<bool>('archived'),
      r.read<DateTime>('updated_at'),
    ];
  }

  /// `stock_levels.stockStatus` (server `StockLevelsExportDatasetProvider`).
  static String stockStatus(int quantity, int threshold) {
    if (quantity <= 0) return exportStockOutOfStock;
    if (threshold > 0 && quantity <= threshold) return exportStockLow;
    return exportStockOk;
  }

  Future<List<LocalExportRow>> _sales(
      List<QueryRow> rows, _References refs) async {
    final ids = [for (final r in rows) r.read<String>('id')];
    final items = <String, int>{};
    for (final r in await _db.customSelect(
      'SELECT sale_id, COALESCE(SUM(quantity), 0) AS q FROM sale_items'
      ' WHERE sale_id IN (${_marks(ids.length)}) GROUP BY sale_id',
      variables: [for (final id in ids) Variable<String>(id)],
    ).get()) {
      items[r.read<String>('sale_id')] = r.read<int>('q');
    }
    return [
      for (final r in rows)
        [
          exportSaleReference(r.read<DateTime>('sale_date'),
              r.readNullable<String>('store_name')),
          r.read<DateTime>('sale_date'),
          r.readNullable<String>('store_name'),
          refs.actor(r.readNullable<String>('employee_id')),
          r.readNullable<String>('client_name'),
          exportLabel(
              exportLabels['sales.status'], r.readNullable<String>('status')),
          exportLabel(exportLabels['sales.paymentMode'],
              r.readNullable<String>('payment_mode')),
          r.readNullable<String>('mobile_money_ref'),
          items[r.read<String>('id')] ?? 0,
          r.readNullable<int>('discount_amount'),
          r.readNullable<int>('total_amount'),
        ],
    ];
  }

  Future<List<LocalExportRow>> _inventorySessions(
      List<QueryRow> rows, _References refs) async {
    final ids = [for (final r in rows) r.read<String>('id')];
    final agg = <String, (int, int, int)>{};
    for (final r in await _db.customSelect(
      'SELECT session_id,'
      ' SUM(CASE WHEN physical IS NOT NULL THEN 1 ELSE 0 END) AS counted,'
      ' SUM(CASE WHEN physical IS NOT NULL AND physical <> theoretical'
      ' THEN 1 ELSE 0 END) AS gaps,'
      ' COALESCE(SUM(CASE WHEN physical IS NOT NULL'
      ' THEN physical - theoretical END), 0) AS total_gap'
      ' FROM inventory_counts WHERE session_id IN (${_marks(ids.length)})'
      ' GROUP BY session_id',
      variables: [for (final id in ids) Variable<String>(id)],
    ).get()) {
      agg[r.read<String>('session_id')] = (
        r.read<int>('counted'),
        r.read<int>('gaps'),
        r.read<int>('total_gap')
      );
    }
    return [
      for (final r in rows)
        [
          r.read<DateTime>('started_at'),
          r.readNullable<String>('store_name'),
          exportLabel(exportLabels['inventory_sessions.scope'],
              r.readNullable<String>('scope')),
          exportLabel(exportLabels['inventory_sessions.status'],
              r.readNullable<String>('status')),
          refs.actor(r.readNullable<String>('started_by')),
          r.readNullable<DateTime>('completed_at'),
          r.readNullable<DateTime>('cancelled_at'),
          agg[r.read<String>('id')]?.$1 ?? 0,
          agg[r.read<String>('id')]?.$2 ?? 0,
          agg[r.read<String>('id')]?.$3 ?? 0,
        ],
    ];
  }

  LocalExportRow _inventoryCount(QueryRow r, _References refs) {
    final theoretical = r.readNullable<int>('theoretical');
    final physical = r.readNullable<int>('physical');
    return [
      r.read<DateTime>('started_at'),
      r.readNullable<String>('store_name'),
      exportLabel(exportLabels['inventory_counts.sessionStatus'],
          r.readNullable<String>('status')),
      r.readNullable<String>('product_name'),
      r.readNullable<String>('variant_label'),
      theoretical,
      physical,
      physical == null || theoretical == null ? null : physical - theoretical,
      r.readNullable<DateTime>('counted_at'),
      refs.actor(r.readNullable<String>('counted_by')),
    ];
  }

  Future<List<LocalExportRow>> _clients(
      List<QueryRow> rows, _References refs) async {
    final ids = [for (final r in rows) r.read<String>('id')];
    final agg = <String, (int, int, DateTime?)>{};
    // Bare column `last_purchase` comes from the row of MAX(julianday(…)).
    for (final r in await _db.customSelect(
      'SELECT client_id, COUNT(*) AS n, COALESCE(SUM(total_amount), 0) AS t,'
      ' COALESCE(occurred_at, created_at) AS last_purchase,'
      ' MAX(julianday(COALESCE(occurred_at, created_at))) AS jd'
      " FROM sales WHERE status = 'COMPLETED'"
      ' AND client_id IN (${_marks(ids.length)}) GROUP BY client_id',
      variables: [for (final id in ids) Variable<String>(id)],
    ).get()) {
      agg[r.read<String>('client_id')] = (
        r.read<int>('n'),
        r.read<int>('t'),
        r.readNullable<DateTime>('last_purchase'),
      );
    }
    return [
      for (final r in rows)
        [
          r.readNullable<String>('name'),
          r.readNullable<String>('phone'),
          r.readNullable<String>('email'),
          r.readNullable<String>('notes'),
          agg[r.read<String>('id')]?.$1 ?? 0,
          agg[r.read<String>('id')]?.$2 ?? 0,
          agg[r.read<String>('id')]?.$3,
          r.readNullable<bool>('archived'),
          r.read<DateTime>('created_at'),
        ],
    ];
  }

  Future<List<LocalExportRow>> _suppliers(
      List<QueryRow> rows, _References refs) async {
    final ids = [for (final r in rows) r.read<String>('id')];
    final products = <String, int>{};
    for (final r in await _db.customSelect(
      'SELECT supplier_id, COUNT(*) AS n FROM product_suppliers'
      ' WHERE supplier_id IN (${_marks(ids.length)}) GROUP BY supplier_id',
      variables: [for (final id in ids) Variable<String>(id)],
    ).get()) {
      products[r.read<String>('supplier_id')] = r.read<int>('n');
    }
    return [
      for (final r in rows)
        [
          r.readNullable<String>('name'),
          r.readNullable<String>('phone'),
          r.readNullable<String>('email'),
          products[r.read<String>('id')] ?? 0,
          r.readNullable<bool>('archived'),
          r.read<DateTime>('created_at'),
        ],
    ];
  }

  LocalExportRow _audit(QueryRow r, _References refs) {
    final userId = r.readNullable<String>('user_id');
    final role = refs.role(userId);
    final actor = refs.employeeName(userId) ??
        _actorLabel(r.readNullable<String>('metadata')) ??
        switch (role) {
          'OWNER' => 'Le propriétaire',
          'EMPLOYEE' => 'Un employé',
          _ => 'Un utilisateur',
        };
    final label = r.readNullable<String>('entity_label');
    final valueAfter = r.readNullable<String>('value_after');
    final metadata = r.readNullable<String>('metadata');
    // v1s-16-20 — server `AuditLogExportDatasetProvider.row`: French action
    // and object type, readable « Changements » / « Détails », no id, device
    // id or JSON.
    return [
      r.read<DateTime>('occurred_at'),
      actor,
      // Other platform roles have no label (server behaviour).
      role == null ? null : exportLabels['audit_log.actorRole']![role],
      exportLabel(
          exportLabels['audit_log.action'], r.readNullable<String>('action')),
      exportLabel(exportLabels['audit_log.entityType'],
          r.readNullable<String>('entity_type')),
      label == null || AuditFormat.isTechnical(label) ? null : label,
      r.readNullable<String>('store_name'),
      exportLabel(
          exportLabels['audit_log.source'], r.readNullable<String>('source')),
      AuditFormat.changesText(r.readNullable<String>('value_before'), valueAfter),
      AuditFormat.detailsText(metadata, valueAfter),
    ];
  }

  static String? _actorLabel(String? metadata) {
    final label = AuditFormat.decodeMap(metadata)['actorLabel'];
    if (label is String && label.trim().isNotEmpty) return label.trim();
    return null;
  }

  static String? _nonEmpty(String? s) => s == null || s.isEmpty ? null : s;

  static String _marks(int n) => List.filled(n, '?').join(', ');
}

typedef _PageMapper = Future<List<LocalExportRow>> Function(
    List<QueryRow> rows, _References refs);

class _Spec {
  final String query;
  final List<Variable> variables;
  final _PageMapper map;

  const _Spec(this.query, this.variables, this.map);
}

/// WHERE clause builder: store equality and inclusive period bounds compared
/// as Julian days (dates are stored as ISO-8601 text, UTC or with an offset).
class _Where {
  final _clauses = <String>[];
  final variables = <Variable>[];

  void store(String column, LocalExportFilter f) {
    if (f.storeId == null) return;
    raw('$column = ?', [Variable<String>(f.storeId!)]);
  }

  void period(String column, LocalExportFilter f) {
    if (f.from != null) {
      raw('julianday($column) >= julianday(?)', [Variable<DateTime>(f.from!)]);
    }
    if (f.to != null) {
      raw('julianday($column) <= julianday(?)', [Variable<DateTime>(f.to!)]);
    }
  }

  void raw(String clause, List<Variable> vars) {
    _clauses.add(clause);
    variables.addAll(vars);
  }

  String get sql => _clauses.isEmpty ? '' : ' WHERE ${_clauses.join(' AND ')}';
}

/// Small reference maps preloaded once per dataset: categories and actor
/// names / roles.
class _References {
  final Map<String?, String> categories;
  final Map<String, String> _employeeNames;
  final Map<String, String> _userRoles;
  final Set<String> _employeeUsers;

  _References(this.categories, this._employeeNames, this._userRoles,
      this._employeeUsers);

  static Future<_References> load(AppDatabase db) async {
    final categories = <String?, String>{
      for (final c in await db.select(db.categories).get()) c.id: c.name,
    };
    // Latest employee row of each user wins (server: ORDER BY updated_at
    // DESC LIMIT 1; the device keeps createdAt only).
    final employees = await (db.select(db.employees)
          ..orderBy([
            (e) => OrderingTerm.asc(e.createdAt),
            (e) => OrderingTerm.asc(e.id),
          ]))
        .get();
    final names = <String, String>{};
    final employeeUsers = <String>{};
    for (final e in employees) {
      employeeUsers.add(e.userId);
      final name = '${e.firstName} ${e.lastName}'.trim();
      if (name.isEmpty) {
        names.remove(e.userId);
      } else {
        names[e.userId] = name;
      }
    }
    final roles = <String, String>{
      for (final u in await db.select(db.users).get()) u.id: u.role,
    };
    return _References(categories, names, roles, employeeUsers);
  }

  /// Display name of the latest employee row of [userId], or `null`.
  String? employeeName(String? userId) =>
      userId == null ? null : _employeeNames[userId];

  /// Actor name (server `ExportValues.actorNameSelect`): employee name, else
  /// « Propriétaire » for an owner, else `null`.
  String? actor(String? userId) {
    if (userId == null) return null;
    return _employeeNames[userId] ??
        (_userRoles[userId] == 'OWNER' ? exportOwnerLabel : null);
  }

  /// Role code of [userId]: its `users` row, else `EMPLOYEE` when an
  /// employee row points at it.
  String? role(String? userId) {
    if (userId == null) return null;
    return _userRoles[userId] ??
        (_employeeUsers.contains(userId) ? 'EMPLOYEE' : null);
  }
}
