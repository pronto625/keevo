import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/inventory_gap_report_model.dart';
import '../../domain/model/inventory_gap_row_model.dart';

/// LocalGapReportDataSource — generates gap analysis report from Drift tables.
///
/// Queries inventorySessions, inventoryCounts, products, and stores
/// to build the report entirely offline. Story 6.3.
class LocalGapReportDataSource {
  final AppDatabase _db;

  const LocalGapReportDataSource(this._db);

  /// Generate gap report for a given session.
  ///
  /// Steps:
  /// 1. Load session → storeId, scope
  /// 2. Load store → storeName
  /// 3. Load counted counts (physical != null)
  /// 4. Load products for price lookup
  /// 5. Build rows, split into concordant/surplus/shortage
  /// 6. Compute summary
  Future<InventoryGapReportModel> generate(String sessionId) async {
    // 1. Load session
    final sessionQuery = _db.select(_db.inventorySessions)
      ..where((s) => s.id.equals(sessionId));
    final session = await sessionQuery.getSingle();

    // 2. Load store name
    final storeQuery = _db.select(_db.stores)
      ..where((s) => s.id.equals(session.storeId));
    final store = await storeQuery.getSingle();

    // 3. Load counts for session (only counted — physical != null)
    final countsQuery = _db.select(_db.inventoryCounts)
      ..where(
          (c) => c.sessionId.equals(sessionId) & c.physical.isNotNull());
    final counts = await countsQuery.get();

    if (counts.isEmpty) {
      return _emptyReport(sessionId, session.storeId, store.name, session.scope);
    }

    // 4. Load products for price + photo + sku
    final productIds = counts.map((c) => c.productId).toSet().toList();
    final productsQuery = _db.select(_db.products)
      ..where((p) => p.id.isIn(productIds));
    final products = await productsQuery.get();
    final productMap = {for (final p in products) p.id: p};

    // 5. Load categories for category names
    final categoryIds = products
        .where((p) => p.categoryId != null)
        .map((p) => p.categoryId!)
        .toSet()
        .toList();
    final Map<String, String> categoryNameMap;
    if (categoryIds.isNotEmpty) {
      final catQuery = _db.select(_db.categories)
        ..where((c) => c.id.isIn(categoryIds));
      final cats = await catQuery.get();
      categoryNameMap = {for (final c in cats) c.id: c.name};
    } else {
      categoryNameMap = {};
    }

    // 6. Build rows
    final allRows = <InventoryGapRowModel>[];
    for (final count in counts) {
      final product = productMap[count.productId];
      final unitPrice = product?.price ?? 0;
      final ecart = count.physical! - count.theoretical;

      allRows.add(InventoryGapRowModel(
        productId: count.productId,
        productName: count.productName,
        sku: product?.sku.isNotEmpty == true ? product!.sku : null,
        photoUrl: product?.photoUrl,
        categoryName: product?.categoryId != null
            ? categoryNameMap[product!.categoryId!]
            : null,
        variantId: count.variantId,
        variantLabel: count.variantLabel,
        theoretical: count.theoretical,
        physical: count.physical!,
        ecart: ecart,
        unitPriceXaf: unitPrice,
        gapValueXaf: ecart.abs() * unitPrice,
      ));
    }

    // 7. Split into sections
    final concordant = allRows.where((r) => r.isConcordant).toList()
      ..sort((a, b) => a.productName.compareTo(b.productName));
    final surplus = allRows.where((r) => r.isSurplus).toList()
      ..sort((a, b) => b.gapValueXaf.compareTo(a.gapValueXaf));
    final shortage = allRows.where((r) => r.isShortage).toList()
      ..sort((a, b) => b.gapValueXaf.compareTo(a.gapValueXaf));

    return InventoryGapReportModel(
      sessionId: sessionId,
      storeId: session.storeId,
      storeName: store.name,
      scope: session.scope,
      summary: InventoryGapSummaryModel(
        totalCounted: allRows.length,
        totalConcordant: concordant.length,
        totalSurplus: surplus.length,
        totalShortage: shortage.length,
        totalSurplusValueXaf: surplus.fold(0, (sum, r) => sum + r.gapValueXaf),
        totalShortageValueXaf:
            shortage.fold(0, (sum, r) => sum + r.gapValueXaf),
      ),
      concordantRows: concordant,
      surplusRows: surplus,
      shortageRows: shortage,
      generatedAt: DateTime.now(),
    );
  }

  InventoryGapReportModel _emptyReport(
    String sessionId,
    String storeId,
    String storeName,
    String scope,
  ) {
    return InventoryGapReportModel(
      sessionId: sessionId,
      storeId: storeId,
      storeName: storeName,
      scope: scope,
      summary: const InventoryGapSummaryModel(
        totalCounted: 0,
        totalConcordant: 0,
        totalSurplus: 0,
        totalShortage: 0,
        totalSurplusValueXaf: 0,
        totalShortageValueXaf: 0,
      ),
      concordantRows: const [],
      surplusRows: const [],
      shortageRows: const [],
      generatedAt: DateTime.now(),
    );
  }
}
