import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/audit/data/datasource/remote_audit_datasource.dart';
import 'package:keevo/features/audit/domain/exception/audit_exception.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';

class MockDio extends Mock implements Dio {}

Map<String, dynamic> _entryJson(String id) => {
      'id': id,
      'entityType': 'Product',
      'entityId': 'p-1',
      'action': 'PRODUCT_CREATED',
      'valueBefore': null,
      'valueAfter': '{"productName":"Pagne"}',
      'userId': 'u-1',
      'actorPhone': '+237600000000',
      'occurredAt': '2026-10-12T08:15:30.123456Z',
      'storeId': 's-1',
      'storeName': 'Boutique Centre',
      'entityLabel': 'Pagne',
      'deviceId': 'device-D',
      'source': 'SYNC',
      'metadata': {'actorLabel': 'Awa', 'notes': 'x'},
      'actorName': 'Awa Diallo',
    };

/// v1s-18-7 — query params (filters, UTC instants, cursor, q), nextCursor and
/// detail mapping of the audit API.
void main() {
  late MockDio dio;
  late RemoteAuditDataSource ds;

  setUp(() {
    dio = MockDio();
    ds = RemoteAuditDataSource(dio: dio);
  });

  Response<Map<String, dynamic>> ok(Map<String, dynamic> data) => Response(
        requestOptions: RequestOptions(path: '/api/v1/audit'),
        statusCode: 200,
        data: {'success': true, 'data': data},
      );

  test('getAuditSlice sends every filter and parses nextCursor', () async {
    Map<String, dynamic>? sent;
    when(() => dio.get<Map<String, dynamic>>('/api/v1/audit',
            queryParameters: any(named: 'queryParameters')))
        .thenAnswer((inv) async {
      sent = inv.namedArguments[#queryParameters] as Map<String, dynamic>;
      return ok({
        'entries': [_entryJson('a'), _entryJson('b')],
        'hasMore': true,
        'page': 0,
        'size': 20,
        'nextCursor': 'next-c',
      });
    });

    final from = DateTime.utc(2026, 10, 1);
    final to = DateTime.utc(2026, 10, 12, 23, 59, 59);
    final slice = await ds.getAuditSlice(
      AuditFilter(
        from: from,
        to: to,
        storeId: 's-1',
        userId: 'u-1',
        action: 'PRODUCT_CREATED',
        entityType: 'Product',
        query: '  pagne ',
      ),
      cursor: 'c0',
    );

    expect(sent, {
      'size': 20,
      'from': '2026-10-01T00:00:00.000Z',
      'to': '2026-10-12T23:59:59.000Z',
      'storeId': 's-1',
      'userId': 'u-1',
      'action': 'PRODUCT_CREATED',
      'entityType': 'Product',
      'q': 'pagne',
      'cursor': 'c0',
    });
    expect(slice.entries.map((e) => e.id), ['a', 'b']);
    expect(slice.nextCursor, 'next-c');
    expect(slice.isOffline, isFalse);
    final e = slice.entries.first;
    expect(e.storeName, 'Boutique Centre');
    expect(e.actorName, 'Awa Diallo');
    expect(e.metadata, '{"actorLabel":"Awa","notes":"x"}');
  });

  test('no filter → only size; hasMore=false → no cursor', () async {
    Map<String, dynamic>? sent;
    when(() => dio.get<Map<String, dynamic>>('/api/v1/audit',
            queryParameters: any(named: 'queryParameters')))
        .thenAnswer((inv) async {
      sent = inv.namedArguments[#queryParameters] as Map<String, dynamic>;
      return ok({'entries': [], 'hasMore': false, 'nextCursor': null});
    });

    final slice = await ds.getAuditSlice(AuditFilter.empty);

    expect(sent, {'size': 20});
    expect(slice.nextCursor, isNull);
  });

  test('getById parses the detail; 404 → AUDIT_ENTRY_NOT_FOUND', () async {
    when(() => dio.get<Map<String, dynamic>>('/api/v1/audit/a'))
        .thenAnswer((_) async => ok(_entryJson('a')));
    expect((await ds.getById('a')).entityLabel, 'Pagne');

    when(() => dio.get<Map<String, dynamic>>('/api/v1/audit/x')).thenThrow(
      DioException(
        requestOptions: RequestOptions(path: '/api/v1/audit/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/audit/x'),
          statusCode: 404,
          data: {'domainCode': 'AUDIT_ENTRY_NOT_FOUND', 'error': 'nope'},
        ),
      ),
    );
    await expectLater(
      ds.getById('x'),
      throwsA(isA<AuditException>()
          .having((e) => e.domainCode, 'code', 'AUDIT_ENTRY_NOT_FOUND')
          .having((e) => e.statusCode, 'status', 404)),
    );
  });

  test('a non-JSON error body still maps to AuditException', () async {
    for (final status in [403, 502]) {
      when(() => dio.get<Map<String, dynamic>>('/api/v1/audit/h')).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/api/v1/audit/h'),
          response: Response(
            requestOptions: RequestOptions(path: '/api/v1/audit/h'),
            statusCode: status,
            data: '<html>Bad Gateway</html>',
          ),
        ),
      );
      await expectLater(
        ds.getById('h'),
        throwsA(isA<AuditException>()
            .having((e) => e.statusCode, 'status', status)
            .having((e) => e.domainCode, 'code', 'UNKNOWN')),
        reason: '$status',
      );
    }
  });

  test('connection error → AuditException without status code', () async {
    when(() => dio.get<Map<String, dynamic>>('/api/v1/audit',
        queryParameters: any(named: 'queryParameters'))).thenThrow(DioException(
      requestOptions: RequestOptions(path: '/api/v1/audit'),
      type: DioExceptionType.connectionError,
    ));

    await expectLater(
      ds.getAuditSlice(AuditFilter.empty),
      throwsA(
          isA<AuditException>().having((e) => e.statusCode, 'status', null)),
    );
  });
}
