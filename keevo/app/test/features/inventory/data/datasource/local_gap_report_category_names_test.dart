import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/data/datasource/local_gap_report_datasource.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// Shaped exactly like InventoryGapReportResponseDto (inside `data`):
/// rows are InventoryGapRowDto — no `categoryName` key.
Map<String, dynamic> _serverReport() => {
      'sessionId': 'sess-1',
      'storeId': 'store-1',
      'storeName': 'Boutique Centre',
      'scope': 'FULL',
      'summary': {
        'totalCounted': 2,
        'totalConcordant': 1,
        'totalSurplus': 0,
        'totalShortage': 1,
        'totalSurplusValueXaf': 0,
        'totalShortageValueXaf': 3000,
      },
      'concordantRows': [
        {
          'productId': 'prod-1',
          'productName': 'Savon',
          'sku': 'SAV-1',
          'photoUrl': null,
          'variantId': null,
          'variantLabel': null,
          'theoretical': 5,
          'physical': 5,
          'ecart': 0,
          'unitPriceXaf': 500,
          'gapValueXaf': 0,
        },
      ],
      'surplusRows': <Map<String, dynamic>>[],
      'shortageRows': [
        {
          'productId': 'prod-2',
          'productName': 'Riz 5kg',
          'sku': 'RIZ-5',
          'photoUrl': null,
          'variantId': null,
          'variantLabel': null,
          'theoretical': 4,
          'physical': 3,
          'ecart': -1,
          'unitPriceXaf': 3000,
          'gapValueXaf': 3000,
        },
      ],
      'generatedAt': '2026-10-14T10:00:00.123456789Z',
      'sessionStatus': 'IN_PROGRESS',
    };

void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late LocalGapReportDataSource datasource;

  setUp(() async {
    db = AppDatabase.forTesting();
    datasource = LocalGapReportDataSource(db);
    await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-1',
          name: 'Hygiène',
          createdAt: DateTime(2026, 1, 1),
          updatedAt: DateTime(2026, 1, 1),
        ));
    await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-1',
          name: 'Savon',
          categoryId: const Value('cat-1'),
          createdAt: DateTime(2026, 1, 1),
          updatedAt: DateTime(2026, 1, 1),
        ));
    await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-2',
          name: 'Riz 5kg',
          createdAt: DateTime(2026, 1, 1),
          updatedAt: DateTime(2026, 1, 1),
        ));
  });

  tearDown(() async => db.close());

  test('server gap report parses and gets category names from the catalogue',
      () async {
    final parsed = InventoryGapReportModel.fromJson(_serverReport());
    expect(parsed.concordantRows.single.categoryName, isNull);
    expect(parsed.summary.totalShortageValueXaf, 3000);
    expect(parsed.sessionStatus, 'IN_PROGRESS');

    final report = await datasource.withLocalCategoryNames(parsed);

    expect(report.concordantRows.single.categoryName, 'Hygiène');
    // Product without a category stays without one.
    expect(report.shortageRows.single.categoryName, isNull);
    expect(report.shortageRows.single.gapValueXaf, 3000);
  });
}
