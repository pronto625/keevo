import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockDio extends Mock implements Dio {}

class MockRemoteProductDataSource extends Mock
    implements RemoteProductDataSource {}

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

/// Pull payloads shaped exactly like the backend delta providers
/// (DayClosureDeltaProvider, SaleDeltaProvider, ReportsDeltaProvider) with
/// ISO-8601 instants.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio mockDio;
  late RestSyncService syncService;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    syncService = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: mockDio,
      secureStorage: const FlutterSecureStorage(),
      prefs: prefs,
    );
  });

  tearDown(() async => db.close());

  void stubPull(Map<String, dynamic> entities) {
    when(() => mockDio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((_) async => Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
          statusCode: 200,
          data: {
            'data': {
              'serverTimestamp': '2026-10-14T20:00:00.000Z',
              'entities': entities,
              'counts': <String, dynamic>{},
            },
          },
        ));
  }

  Map<String, dynamic> closure({int pendingCount = 2}) => {
        'id': 'dc-1',
        'storeId': 'store-1',
        'employeeId': 'emp-1',
        'closedAt': '2026-10-14T19:00:00.123456Z',
        'totalSales': 12,
        'totalRevenue': 54000,
        'cashTotal': 40000,
        'mobileMoneyTotal': 14000,
        'isAutomatic': false,
        'createdAt': '2026-10-14T19:00:00.123456Z',
        'topProductId': 'prod-1',
        'topProductName': 'Savon',
        'topProductQty': 7,
        'pendingSalesCount': pendingCount,
        'pendingSalesTotal': 3500,
      };

  test('day closure pull keeps top product and pending sales', () async {
    stubPull({'dayClosures': [closure()]});

    await syncService.pull();

    final row = await (db.select(db.dayClosures)
          ..where((d) => d.id.equals('dc-1')))
        .getSingle();
    expect(row.totalRevenue, 54000);
    expect(row.cashAmount, 40000);
    expect(row.momoAmount, 14000);
    expect(row.topProductId, 'prod-1');
    expect(row.topProductName, 'Savon');
    expect(row.topProductQty, 7);
    expect(row.pendingSalesCount, 2);
    expect(row.pendingSalesTotal, 3500);
    // Stored like every other pulled date (device-local time with its offset):
    // the same instant, not the server's wall clock.
    final raw = await db
        .customSelect("SELECT closed_at FROM day_closures WHERE id = 'dc-1'")
        .getSingle();
    expect(
      DateTime.parse(raw.data['closed_at'] as String)
          .isAtSameMomentAs(DateTime.utc(2026, 10, 14, 19, 0, 0, 123, 456)),
      isTrue,
    );
    expect(row.closedAt.isAtSameMomentAs(
            DateTime.utc(2026, 10, 14, 19, 0, 0, 123, 456)),
        isTrue);
  });

  test('day closure re-pull updates the summary fields', () async {
    stubPull({'dayClosures': [closure(pendingCount: 0)]});
    await syncService.pull();
    stubPull({'dayClosures': [closure(pendingCount: 3)]});
    await syncService.pull();

    final row = await (db.select(db.dayClosures)
          ..where((d) => d.id.equals('dc-1')))
        .getSingle();
    expect(row.pendingSalesCount, 3);
  });

  test('older payload without the summary keys still inserts', () async {
    final legacy = closure()
      ..remove('topProductId')
      ..remove('topProductName')
      ..remove('topProductQty')
      ..remove('pendingSalesCount')
      ..remove('pendingSalesTotal');
    stubPull({'dayClosures': [legacy]});

    await syncService.pull();

    final row = await (db.select(db.dayClosures)
          ..where((d) => d.id.equals('dc-1')))
        .getSingle();
    expect(row.topProductName, isNull);
    expect(row.pendingSalesCount, 0);
  });

  test('sale item created_at is stored in local time like the sale',
      () async {
    stubPull({
      'sales': [
        {
          'id': 'sale-1',
          'storeId': 'store-1',
          'employeeId': 'emp-1',
          'clientId': null,
          'totalAmount': 1500,
          'discountAmount': 0,
          'paymentMode': 'CASH',
          'status': 'COMPLETED',
          'occurredAt': '2026-10-14T09:30:00Z',
          'createdAt': '2026-10-14T09:30:01Z',
          'items': [
            {
              'id': 'item-1',
              'productId': 'prod-1',
              'variantId': null,
              'productName': 'Savon',
              'appliedUnitPrice': 500,
              'catalogueUnitPrice': 500,
              'quantity': 3,
              'subtotal': 1500,
            },
          ],
        },
      ],
    });

    await syncService.pull();

    final saleRow = await db
        .customSelect("SELECT created_at FROM sales WHERE id = 'sale-1'")
        .getSingle();
    final itemRow = await db
        .customSelect("SELECT created_at FROM sale_items WHERE id = 'item-1'")
        .getSingle();
    expect(itemRow.data['created_at'], saleRow.data['created_at']);
  });

  Map<String, dynamic> report({required String status, int attempts = 1}) => {
        'id': 'rep-1',
        'tenantId': 'tenant-1',
        'storeId': 'store-1',
        'storeName': 'Boutique Centre',
        'actorId': null,
        'actorName': null,
        'reportType': 'DAILY',
        'reportDate': '2026-10-14',
        'content': 'Rapport du jour',
        'deliveryStatus': status,
        'deliveryAttempts': attempts,
        'lastAttemptAt': '2026-10-14T20:05:00Z',
        'totalRevenue': 54000,
        'totalSales': 12,
        'isAutomatic': true,
        'createdAt': '2026-10-14T20:00:00Z',
      };

  // ReportsDeltaProvider pages on updated_at: a later delivery attempt sends
  // the report again and the device must take the new status.
  test('report re-pull updates its delivery status', () async {
    stubPull({'reports': [report(status: 'PENDING', attempts: 0)]});
    await syncService.pull();
    stubPull({'reports': [report(status: 'SENT', attempts: 1)]});
    await syncService.pull();

    final row = await db
        .customSelect(
            "SELECT delivery_status, delivery_attempts FROM reports WHERE id = 'rep-1'")
        .getSingle();
    expect(row.data['delivery_status'], 'SENT');
    expect(row.data['delivery_attempts'], 1);
  });
}
