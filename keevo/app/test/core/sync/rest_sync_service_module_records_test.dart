import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/modules/registered_modules.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_rejection_messages.dart';
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

/// Story 15.5b — the app shell side of the offline invoices: pulled
/// `invoices` go to the module records through the module's merger (pending
/// ones kept), the invoice operations are pushed like the others, a refused
/// offline creation rolls its ghost back, any other refusal asks for a full
/// pull, a device upgraded with an existing cursor seeds the records once.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio dio;
  late SharedPreferences prefs;
  late RestSyncService sync;
  late ModuleRecordStore store;

  setUp(() async {
    db = AppDatabase.forTesting();
    dio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    store = ModuleRecordStore(db);
    sync = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: dio,
      secureStorage: const FlutterSecureStorage(),
      prefs: prefs,
      moduleMergers: composeModuleMergers(kRegisteredModules),
      moduleRecordPaths: composeModuleRecordPaths(kRegisteredModules),
    );
  });

  tearDown(() => db.close());

  final pulledParams = <Map<String, dynamic>?>[];

  void stubPull(Map<String, dynamic> entities) {
    pulledParams.clear();
    when(() => dio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((inv) async {
      pulledParams.add(
          inv.namedArguments[#queryParameters] as Map<String, dynamic>?);
      return Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
        statusCode: 200,
        data: {
          'data': {
            'serverTimestamp': '2026-10-31T12:00:00.000Z',
            'entities': entities,
            'counts': <String, dynamic>{},
          }
        },
      );
    });
  }

  void stubPush(List<Map<String, dynamic>> results) {
    when(() => dio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/sync/push'),
              statusCode: 200,
              data: {
                'data': {'results': results},
              },
            ));
  }

  Future<void> queueOp(String id, String operation, String entityId,
      {int order = 0}) {
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: jsonEncode({'invoiceId': entityId}),
          createdAt: DateTime(2026, 10, 31, 10, order),
          entityId: Value(entityId),
        ));
  }

  Map<String, dynamic> row(String id, String status, {String? number}) => {
        'id': id,
        'status': status,
        'number': number,
        'updatedAt': '2026-10-31T11:00:00Z',
      };

  test('pull: `invoices` stored as module records; one with a queued '
      'operation keeps its local copy', () async {
    await store.put('invoicing', 'invoices', 'local', {'id': 'local'},
        pending: true);
    await queueOp('q1', 'ISSUE_INVOICE', 'local');
    stubPull({
      'invoices': [
        row('srv', 'PAID', number: 'FAC-2026-000001'),
        row('local', 'ISSUED', number: 'FAC-2026-000002'),
      ],
    });

    await sync.pull();

    final srv = (await store.get('invoicing', 'invoices', 'srv'))!;
    expect(srv.json['number'], 'FAC-2026-000001');
    expect(srv.pending, isFalse);
    final local = (await store.get('invoicing', 'invoices', 'local'))!;
    expect(local.json.containsKey('number'), isFalse);
    expect(local.pending, isTrue);
  });

  test('invoice operations pushed in queue order; applied ones removed',
      () async {
    await queueOp('c', 'CREATE_INVOICE', 'inv-1', order: 0);
    await queueOp('i', 'ISSUE_INVOICE', 'inv-1', order: 1);
    await queueOp('p', 'PAY_INVOICE', 'inv-1', order: 2);
    stubPush([
      for (final id in ['c', 'i', 'p'])
        {'operationId': id, 'status': 'APPLIED', 'serverEntityId': 'inv-1'},
    ]);

    expect(await sync.push(), isEmpty);

    final sent = verify(() => dio.post<dynamic>(any(),
        data: captureAny(named: 'data'))).captured.single as Map;
    expect((sent['operations'] as List).map((o) => o['operationType']),
        ['CREATE_INVOICE', 'ISSUE_INVOICE', 'PAY_INVOICE']);
    expect(await db.select(db.syncQueue).get(), isEmpty);
  });

  test('refused offline creation (terminal): the ghost invoice and its '
      'queued follow-ups are removed, the user told — a French history line '
      'names it', () async {
    await store.put(
        'invoicing',
        'invoices',
        'inv-1',
        {
          'id': 'inv-1',
          'client': {'name': 'Awa Traoré'},
          'totalTtc': 15000,
        },
        pending: true);
    await queueOp('c', 'CREATE_INVOICE', 'inv-1', order: 0);
    await queueOp('i', 'ISSUE_INVOICE', 'inv-1', order: 1);
    stubPush([
      {'operationId': 'c', 'status': 'REJECTED', 'reason': 'VALIDATION_ERROR'},
      {'operationId': 'i', 'status': 'REJECTED', 'reason': 'INVOICE_NOT_FOUND'},
    ]);

    final notices = await sync.push();

    expect(notices.single['operation'], 'CREATE_INVOICE');
    expect(notices.single['rolledBack'], isTrue);
    expect(await store.get('invoicing', 'invoices', 'inv-1'), isNull);
    expect(await db.select(db.syncQueue).get(), isEmpty);
    expect(prefs.getInt(kFullPullRequestedKey), isNull);
    final history = await db.select(db.syncEvents).getSingle();
    expect(history.type, kSyncEventReject);
    expect(history.errorMessage,
        'Facture de Awa Traoré — 15 000 FCFA : Données invalides');
  });

  test('refused payment (date before the issue): dropped with its French '
      'message and the later queued operations of the invoice; the invoice '
      'read again from the server, no full pull', () async {
    await store.put('invoicing', 'invoices', 'inv-1',
        {'id': 'inv-1', 'status': 'PAID'},
        pending: true);
    await queueOp('p', 'PAY_INVOICE', 'inv-1', order: 0);
    await queueOp('c', 'CANCEL_INVOICE', 'inv-1', order: 1);
    await queueOp('x', 'ISSUE_INVOICE', 'other', order: 2);
    stubPush([
      {
        'operationId': 'p',
        'status': 'REJECTED',
        'reason': 'INVOICE_PAYMENT_DATE_INVALID',
      },
      {'operationId': 'c', 'status': 'APPLIED'},
      {'operationId': 'x', 'status': 'APPLIED'},
    ]);
    when(() => dio.get<Map<String, dynamic>>('/api/v1/invoices/inv-1'))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/invoices/inv-1'),
              statusCode: 200,
              data: {'data': row('inv-1', 'ISSUED', number: 'FAC-2026-000003')},
            ));

    final notices = await sync.push();

    expect(notices.single['reason'], 'INVOICE_PAYMENT_DATE_INVALID');
    expect(friendlySyncRejectionReason('INVOICE_PAYMENT_DATE_INVALID'),
        'Date de paiement invalide : ni dans le futur, ni avant l\'émission');
    expect(friendlySyncOperationLabel('PAY_INVOICE'), 'Paiement de facture');
    // the cancellation built on the refused payment went with it
    final sent = verify(() => dio.post<dynamic>(any(),
        data: captureAny(named: 'data'))).captured.single as Map;
    expect((sent['operations'] as List).length, 3);
    expect(await db.select(db.syncQueue).get(), isEmpty);
    final local = (await store.get('invoicing', 'invoices', 'inv-1'))!;
    expect(local.json['status'], 'ISSUED');
    expect(local.pending, isFalse);
    expect(prefs.getInt(kFullPullRequestedKey), isNull);
  });

  test('refused change, invoice gone on the server (404): removed locally; '
      'unreadable: one full pull instead', () async {
    await store.put('invoicing', 'invoices', 'gone', {'id': 'gone'},
        pending: true);
    await store.put('invoicing', 'invoices', 'down', {'id': 'down'},
        pending: true);
    await queueOp('g', 'ISSUE_INVOICE', 'gone', order: 0);
    await queueOp('d', 'ISSUE_INVOICE', 'down', order: 1);
    stubPush([
      {'operationId': 'g', 'status': 'REJECTED', 'reason': 'INVOICE_NOT_FOUND'},
      {
        'operationId': 'd',
        'status': 'REJECTED',
        'reason': 'INVOICE_NOT_ISSUABLE',
      },
    ]);
    when(() => dio.get<Map<String, dynamic>>('/api/v1/invoices/gone'))
        .thenThrow(DioException(
      requestOptions: RequestOptions(path: '/api/v1/invoices/gone'),
      response: Response(
          requestOptions: RequestOptions(path: '/api/v1/invoices/gone'),
          statusCode: 404),
      type: DioExceptionType.badResponse,
    ));
    when(() => dio.get<Map<String, dynamic>>('/api/v1/invoices/down'))
        .thenThrow(DioException.connectionError(
            requestOptions: RequestOptions(path: '/api/v1/invoices/down'),
            reason: 'off'));

    await sync.push();

    expect(await store.get('invoicing', 'invoices', 'gone'), isNull);
    expect(await store.get('invoicing', 'invoices', 'down'), isNotNull);
    expect(prefs.getInt(kFullPullRequestedKey), isNotNull);
  });

  test('module disabled: MODULE_INACTIVE holds the operation, the invoice '
      'kept', () async {
    await store.put('invoicing', 'invoices', 'inv-1', {'id': 'inv-1'},
        pending: true);
    await queueOp('c', 'CREATE_INVOICE', 'inv-1');
    stubPush([
      {'operationId': 'c', 'status': 'REJECTED', 'reason': 'MODULE_INACTIVE'},
    ]);

    await sync.push();

    final held = await db.select(db.syncQueue).getSingle();
    expect(held.lastError, 'MODULE_INACTIVE');
    expect(await store.get('invoicing', 'invoices', 'inv-1'), isNotNull);
  });

  test('device upgraded with an existing cursor: one full pull seeds the '
      'module records, then delta pulls again', () async {
    await const FlutterSecureStorage().write(
        key: kLastSyncTimestampKey,
        value: DateTime.utc(2026, 10, 1).millisecondsSinceEpoch.toString());
    await prefs.setInt(
        kLastSyncAtKey, DateTime.utc(2026, 10, 1).millisecondsSinceEpoch);
    await prefs.setBool(kPullTzFixDoneKey, true);
    stubPull({'invoices': []});

    await sync.pull();
    expect(pulledParams.single?['since'], isNull, reason: 'full pull');
    expect(prefs.getBool(kModuleRecordsSeededKey), isTrue);

    await sync.pull();
    expect(pulledParams.last?['since'], isNotNull, reason: 'delta again');
  });
}
