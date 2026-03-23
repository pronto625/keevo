import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/features/audit/data/datasource/local_audit_datasource.dart';
import 'package:keevo/features/audit/data/datasource/remote_audit_datasource.dart';
import 'package:keevo/features/audit/data/repository/audit_repository_impl.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';

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
}
