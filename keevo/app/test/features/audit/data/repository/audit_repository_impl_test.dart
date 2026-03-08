import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/audit/data/datasource/remote_audit_datasource.dart';
import 'package:keevo/features/audit/data/repository/audit_repository_impl.dart';
import 'package:keevo/features/audit/domain/exception/audit_exception.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';

// ── Mocks ──────────────────────────────────────────────────────────────────

class MockRemoteAuditDataSource extends Mock implements RemoteAuditDataSource {}

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

void main() {
  late MockRemoteAuditDataSource mockDataSource;
  late AuditRepositoryImpl repository;

  setUp(() {
    mockDataSource = MockRemoteAuditDataSource();
    repository = AuditRepositoryImpl(mockDataSource);
  });

  group('AuditRepositoryImpl.getAuditHistory()', () {
    test('both entityType + entityId → calls datasource with both parameters', () async {
      when(() => mockDataSource.getAuditHistory(
            entityType: 'Product',
            entityId: 'product-uuid-001',
          )).thenAnswer((_) async => [_productEntry]);

      final result = await repository.getAuditHistory(
        entityType: 'Product',
        entityId: 'product-uuid-001',
      );

      expect(result, hasLength(1));
      expect(result.first.entityType, equals('Product'));
      expect(result.first.entityId, equals('product-uuid-001'));
      verify(() => mockDataSource.getAuditHistory(
            entityType: 'Product',
            entityId: 'product-uuid-001',
          )).called(1);
    });

    test('only entityType (no entityId) → calls datasource with entityType only', () async {
      when(() => mockDataSource.getAuditHistory(
            entityType: 'User',
            entityId: null,
          )).thenAnswer((_) async => [_userEntry]);

      final result = await repository.getAuditHistory(entityType: 'User');

      expect(result, hasLength(1));
      expect(result.first.entityType, equals('User'));
      verify(() => mockDataSource.getAuditHistory(
            entityType: 'User',
            entityId: null,
          )).called(1);
    });

    test('no params → calls datasource with no filters (full tenant log)', () async {
      when(() => mockDataSource.getAuditHistory(
            entityType: null,
            entityId: null,
          )).thenAnswer((_) async => [_userEntry, _productEntry]);

      final result = await repository.getAuditHistory();

      expect(result, hasLength(2));
      verify(() => mockDataSource.getAuditHistory(
            entityType: null,
            entityId: null,
          )).called(1);
    });

    test('datasource throws AuditException(UNAUTHORIZED) → propagates', () async {
      when(() => mockDataSource.getAuditHistory(
            entityType: any(named: 'entityType'),
            entityId: any(named: 'entityId'),
          )).thenThrow(const AuditException(
        domainCode: 'UNAUTHORIZED',
        message: 'Non authentifié',
        statusCode: 401,
      ));

      expect(
        () => repository.getAuditHistory(),
        throwsA(
          isA<AuditException>().having(
            (e) => e.domainCode,
            'domainCode',
            equals('UNAUTHORIZED'),
          ),
        ),
      );
    });

    test('datasource throws AuditException(AUDIT_IMMUTABLE) on 403 → propagates', () async {
      when(() => mockDataSource.getAuditHistory(
            entityType: any(named: 'entityType'),
            entityId: any(named: 'entityId'),
          )).thenThrow(const AuditException(
        domainCode: 'AUDIT_IMMUTABLE',
        message: 'Les entrées du journal d\'audit ne peuvent pas être modifiées',
        statusCode: 403,
      ));

      expect(
        () => repository.getAuditHistory(),
        throwsA(
          isA<AuditException>().having(
            (e) => e.statusCode,
            'statusCode',
            equals(403),
          ),
        ),
      );
    });
  });
}
