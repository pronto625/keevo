import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';

void main() {
  group('AuditEntryDto', () {
    test('fromJson() maps all fields correctly', () {
      final json = {
        'id': 'aabbccdd-0000-0000-0000-112233445566',
        'entityType': 'Product',
        'entityId': '00000000-0000-0000-0000-000000000001',
        'action': 'STOCK_ADJUSTED',
        'valueBefore': '{"qty":5}',
        'valueAfter': '{"qty":10}',
        'userId': '00000000-0000-0000-0000-000000000099',
        'occurredAt': '2026-03-01T10:00:00.000Z',
      };

      final dto = AuditEntryDto.fromJson(json);

      expect(dto.id, equals('aabbccdd-0000-0000-0000-112233445566'));
      expect(dto.entityType, equals('Product'));
      expect(dto.entityId, equals('00000000-0000-0000-0000-000000000001'));
      expect(dto.action, equals('STOCK_ADJUSTED'));
      expect(dto.valueBefore, equals('{"qty":5}'));
      expect(dto.valueAfter, equals('{"qty":10}'));
      expect(dto.userId, equals('00000000-0000-0000-0000-000000000099'));
      expect(dto.occurredAt.isUtc, isTrue);
      expect(dto.occurredAt.year, equals(2026));
    });

    test('fromJson() with null valueBefore — field is null (nullable)', () {
      final json = {
        'id': 'aabbccdd-0000-0000-0000-112233445566',
        'entityType': 'User',
        'entityId': '00000000-0000-0000-0000-000000000002',
        'action': 'USER_REGISTERED',
        'valueBefore': null,
        'valueAfter': '{"tenantCode":"KV-ABC123"}',
        'userId': '00000000-0000-0000-0000-000000000002',
        'occurredAt': '2026-03-01T08:30:00.000Z',
      };

      final dto = AuditEntryDto.fromJson(json);

      expect(dto.valueBefore, isNull);
      expect(dto.valueAfter, isNotNull);
      expect(dto.action, equals('USER_REGISTERED'));
    });

    test('fromJson() with absent valueBefore key — field is null', () {
      final json = {
        'id': 'aabbccdd-0000-0000-0000-112233445566',
        'entityType': 'Tenant',
        'entityId': '00000000-0000-0000-0000-000000000003',
        'action': 'ONBOARDING_COMPLETED',
        // no 'valueBefore' key at all
        'valueAfter': '{"sector":"CLOTHING"}',
        'userId': '00000000-0000-0000-0000-000000000003',
        'occurredAt': '2026-03-01T09:00:00.000Z',
      };

      final dto = AuditEntryDto.fromJson(json);

      expect(dto.valueBefore, isNull);
    });

    test('equality — two DTOs with same data are equal', () {
      final json = {
        'id': 'aabbccdd-0000-0000-0000-112233445566',
        'entityType': 'Product',
        'entityId': '00000000-0000-0000-0000-000000000001',
        'action': 'STOCK_ADJUSTED',
        'valueBefore': null,
        'valueAfter': '{"qty":10}',
        'userId': '00000000-0000-0000-0000-000000000099',
        'occurredAt': '2026-03-01T10:00:00.000Z',
      };

      final a = AuditEntryDto.fromJson(json);
      final b = AuditEntryDto.fromJson(json);

      expect(a, equals(b));
    });
  });
}
