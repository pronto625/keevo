import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/audit/data/datasource/local_audit_datasource.dart';
import 'package:keevo/features/audit/data/datasource/remote_audit_datasource.dart';
import 'package:keevo/features/audit/data/repository/audit_repository_impl.dart';
import 'package:keevo/features/audit/domain/exception/audit_exception.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:mocktail/mocktail.dart';

// ── Mocks ──────────────────────────────────────────────────────────────────

class MockRemoteAuditDataSource extends Mock implements RemoteAuditDataSource {}

class MockLocalAuditDataSource extends Mock implements LocalAuditDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

// ── Test data ──────────────────────────────────────────────────────────────

final _userEntry = AuditEntryDto(
  id: 'entry-uuid-001',
  entityType: 'User',
  entityId: 'user-uuid-001',
  action: 'USER_REGISTERED',
  valueBefore: null,
  valueAfter: '{"tenantCode":"KV-ABC123"}',
  userId: 'user-uuid-001',
  occurredAt: DateTime.utc(2026, 3, 1, 10, 0, 0),
);

final _productEntry = AuditEntryDto(
  id: 'entry-uuid-002',
  entityType: 'Product',
  entityId: 'product-uuid-001',
  action: 'STOCK_ADJUSTED',
  valueBefore: '{"qty":5}',
  valueAfter: '{"qty":10}',
  userId: 'user-uuid-001',
  occurredAt: DateTime.utc(2026, 3, 1, 11, 0, 0),
);

final _pageResult = AuditPageResult(
  entries: [_userEntry, _productEntry],
  hasMore: false,
);

void main() {
  late MockRemoteAuditDataSource mockRemote;
  late MockLocalAuditDataSource mockLocal;
  late MockConnectivityService mockConnectivity;
  late AuditRepositoryImpl repository;

  setUp(() {
    mockRemote = MockRemoteAuditDataSource();
    mockLocal = MockLocalAuditDataSource();
    mockConnectivity = MockConnectivityService();
    repository = AuditRepositoryImpl(mockRemote, mockLocal, mockConnectivity);
  });

  group('AuditRepositoryImpl.getAuditHistoryPage()', () {
    test('online → fetches from remote datasource', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: 'Product',
            entityId: 'product-uuid-001',
          )).thenAnswer((_) async => AuditPageResult(
            entries: [_productEntry],
            hasMore: false,
          ));

      final result = await repository.getAuditHistoryPage(
        page: 0,
        size: 20,
        entityType: 'Product',
        entityId: 'product-uuid-001',
      );

      expect(result.entries, hasLength(1));
      expect(result.entries.first.entityType, equals('Product'));
      verify(() => mockRemote.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: 'Product',
            entityId: 'product-uuid-001',
          )).called(1);
      verifyNever(() => mockLocal.getAuditHistoryPage(
            page: any(named: 'page'),
            size: any(named: 'size'),
            entityType: any(named: 'entityType'),
            entityId: any(named: 'entityId'),
          ));
    });

    test('offline → reads from local datasource', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: null,
            entityId: null,
          )).thenAnswer((_) async => _pageResult);

      final result = await repository.getAuditHistoryPage(
        page: 0,
        size: 20,
      );

      expect(result.entries, hasLength(2));
      verifyNever(() => mockRemote.getAuditHistoryPage(
            page: any(named: 'page'),
            size: any(named: 'size'),
            entityType: any(named: 'entityType'),
            entityId: any(named: 'entityId'),
          ));
      verify(() => mockLocal.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: null,
            entityId: null,
          )).called(1);
    });

    test('online but remote throws → falls back to local', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditHistoryPage(
            page: any(named: 'page'),
            size: any(named: 'size'),
            entityType: any(named: 'entityType'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('Network error'));
      when(() => mockLocal.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: null,
            entityId: null,
          )).thenAnswer((_) async => _pageResult);

      final result = await repository.getAuditHistoryPage(
        page: 0,
        size: 20,
      );

      expect(result.entries, hasLength(2));
      verify(() => mockRemote.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: null,
            entityId: null,
          )).called(1);
      verify(() => mockLocal.getAuditHistoryPage(
            page: 0,
            size: 20,
            entityType: null,
            entityId: null,
          )).called(1);
    });
  });

  // ── v1s-18-7 — history slice + detail ─────────────────────────────────────

  group('AuditRepositoryImpl.getAuditSlice()', () {
    const filter = AuditFilter(storeId: 'store-1', query: 'pagne');
    final remoteSlice = AuditSlice(entries: [_productEntry], nextCursor: 'c1');
    final localSlice =
        AuditSlice(entries: [_userEntry], nextCursor: null, isOffline: true);

    setUpAll(() => registerFallbackValue(AuditFilter.empty));

    test('online → remote slice with the server cursor', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditSlice(filter, cursor: 'c0', size: 20))
          .thenAnswer((_) async => remoteSlice);

      final r = await repository.getAuditSlice(filter, cursor: 'c0');

      expect(r.isOffline, isFalse);
      expect(r.nextCursor, 'c1');
      verifyNever(() => mockLocal.getAuditSlice(any(),
          cursor: any(named: 'cursor'), size: any(named: 'size')));
    });

    test('offline → local slice, flagged offline', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getAuditSlice(filter, cursor: null, size: 20))
          .thenAnswer((_) async => localSlice);

      final r = await repository.getAuditSlice(filter);

      expect(r.isOffline, isTrue);
      verifyNever(() => mockRemote.getAuditSlice(any(),
          cursor: any(named: 'cursor'), size: any(named: 'size')));
    });

    test('first page: connection failure → local slice', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditSlice(filter, cursor: null, size: 20))
          .thenThrow(const AuditException(
              domainCode: 'NETWORK_ERROR', message: 'x'));
      when(() => mockLocal.getAuditSlice(filter, cursor: null, size: 20))
          .thenAnswer((_) async => localSlice);

      final r = await repository.getAuditSlice(filter);

      expect(r.isOffline, isTrue);
      expect(r.entries.single.id, _userEntry.id);
    });

    test('server-cursor page: connection failure is rethrown, no local mix',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditSlice(filter, cursor: 'c0', size: 20))
          .thenThrow(const AuditException(
              domainCode: 'NETWORK_ERROR', message: 'x'));

      await expectLater(repository.getAuditSlice(filter, cursor: 'c0'),
          throwsA(isA<AuditException>()));
      verifyNever(() => mockLocal.getAuditSlice(any(),
          cursor: any(named: 'cursor'), size: any(named: 'size')));
    });

    test('server error (500) is rethrown, no local fallback', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getAuditSlice(filter, cursor: null, size: 20))
          .thenThrow(const AuditException(
              domainCode: 'INTERNAL_ERROR', message: 'boom', statusCode: 500));

      await expectLater(
          repository.getAuditSlice(filter), throwsA(isA<AuditException>()));
      verifyNever(() => mockLocal.getAuditSlice(any(),
          cursor: any(named: 'cursor'), size: any(named: 'size')));
    });

    test('a local cursor keeps paging the local copy even online', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getAuditSlice(filter, cursor: 'local:20', size: 20))
          .thenAnswer((_) async => localSlice);

      final r = await repository.getAuditSlice(filter, cursor: 'local:20');

      expect(r.isOffline, isTrue);
      verifyNever(() => mockRemote.getAuditSlice(any(),
          cursor: any(named: 'cursor'), size: any(named: 'size')));
    });
  });

  group('AuditRepositoryImpl.getAuditEntry()', () {
    test('online → remote detail', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getById('e1')).thenAnswer((_) async => _userEntry);

      expect(await repository.getAuditEntry('e1'), _userEntry);
      verifyNever(() => mockLocal.getById(any()));
    });

    test('remote failure → local row', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getById('e1')).thenThrow(
          const AuditException(domainCode: 'NETWORK_ERROR', message: 'x'));
      when(() => mockLocal.getById('e1'))
          .thenAnswer((_) async => _productEntry);

      expect(await repository.getAuditEntry('e1'), _productEntry);
    });

    test('404 and no local row → AUDIT_ENTRY_NOT_FOUND rethrown', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.getById('e1')).thenThrow(const AuditException(
          domainCode: 'AUDIT_ENTRY_NOT_FOUND', message: 'x', statusCode: 404));
      when(() => mockLocal.getById('e1')).thenAnswer((_) async => null);

      await expectLater(
        repository.getAuditEntry('e1'),
        throwsA(isA<AuditException>().having(
            (e) => e.domainCode, 'domainCode', 'AUDIT_ENTRY_NOT_FOUND')),
      );
    });

    test('offline and unknown locally → not found', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getById('e1')).thenAnswer((_) async => null);

      await expectLater(
          repository.getAuditEntry('e1'), throwsA(isA<AuditException>()));
      verifyNever(() => mockRemote.getById(any()));
    });
  });
}
