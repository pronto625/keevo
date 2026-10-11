import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/features/account/data/datasource/remote_account_deletion_datasource.dart';
import 'package:keevo/features/account/data/repository/account_deletion_repository_impl.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';

class _MockRemote extends Mock implements RemoteAccountDeletionDataSource {}

class _MockTokenStorage extends Mock implements TokenStorage {}

/// Unsigned three-part token: the app only reads its own claims.
String _jwt({String tenantId = 'kv_abc123', String tenantStatus = 'ACTIVE'}) {
  String part(Map<String, dynamic> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${part({'alg': 'none'})}.'
      '${part({'sub': 'u1', 'tenantId': tenantId, 'tenantStatus': tenantStatus})}.sig';
}

DioException _conflict(String domainCode) => DioException(
      requestOptions: RequestOptions(path: '/api/v1/account/delete/cancel'),
      response: Response(
        requestOptions: RequestOptions(path: '/api/v1/account/delete/cancel'),
        statusCode: 409,
        data: {'domainCode': domainCode, 'error': 'conflit'},
      ),
    );

void main() {
  final now = DateTime(2026, 10, 11, 9, 0);
  late _MockRemote remote;
  late _MockTokenStorage storage;
  late SharedPreferences prefs;

  AccountDeletionRepositoryImpl build() => AccountDeletionRepositoryImpl(
        remote: remote,
        prefs: prefs,
        tokenStorage: storage,
        now: () => now,
      );

  setUp(() async {
    remote = _MockRemote();
    storage = _MockTokenStorage();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    when(() => storage.getToken()).thenAnswer((_) async => _jwt());
    when(() => remote.requestDeletion()).thenAnswer((_) async {});
    when(() => remote.cancelDeletion()).thenAnswer((_) async {});
  });

  group('AccountDeletionRepositoryImpl (Story 14.5 — AC7/AC8)', () {
    test('no local trace and ACTIVE claim → no pending status', () async {
      expect(await build().currentStatus(), isNull);
    });

    test('requestDeletion calls the API and schedules now + 30 days',
        () async {
      final repo = build();
      final status = await repo.requestDeletion();

      verify(() => remote.requestDeletion()).called(1);
      expect(status.scheduledAt, now.add(const Duration(days: 30)));
      expect(status.daysRemaining(now: now), 30);

      // Persisted per tenant, readable back.
      expect(prefs.getString(AccountDeletionRepositoryImpl.prefsKey('kv_abc123')),
          isNotNull);
      final current = await repo.currentStatus();
      expect(current?.scheduledAt, now.add(const Duration(days: 30)));
    });

    test('a request failure stores nothing', () async {
      when(() => remote.requestDeletion()).thenThrow(_conflict('DELETION_ALREADY_REQUESTED'));

      await expectLater(build().requestDeletion(), throwsA(isA<DioException>()));
      expect(await build().currentStatus(), isNull);
    });

    test('local trace is ignored for another tenant', () async {
      await build().requestDeletion();
      when(() => storage.getToken())
          .thenAnswer((_) async => _jwt(tenantId: 'kv_other1'));

      expect(await build().currentStatus(), isNull);
    });

    test('an expired local trace is dropped', () async {
      await prefs.setString(
        AccountDeletionRepositoryImpl.prefsKey('kv_abc123'),
        now.subtract(const Duration(days: 1)).toIso8601String(),
      );

      expect(await build().currentStatus(), isNull);
      expect(prefs.getString(AccountDeletionRepositoryImpl.prefsKey('kv_abc123')),
          isNull);
    });

    test('DELETION_PENDING claim without local trace → pending, no date',
        () async {
      when(() => storage.getToken())
          .thenAnswer((_) async => _jwt(tenantStatus: 'DELETION_PENDING'));

      final status = await build().currentStatus();
      expect(status, isNotNull);
      expect(status!.scheduledAt, isNull);
      expect(status.daysRemaining(), isNull);
    });

    test('cancelDeletion calls the API and clears the local trace', () async {
      final repo = build();
      await repo.requestDeletion();

      await repo.cancelDeletion();

      verify(() => remote.cancelDeletion()).called(1);
      expect(await repo.currentStatus(), isNull);
    });

    test('cancel on DELETION_NOT_PENDING (already cancelled elsewhere) clears locally',
        () async {
      final repo = build();
      await repo.requestDeletion();
      when(() => remote.cancelDeletion())
          .thenThrow(_conflict('DELETION_NOT_PENDING'));

      await repo.cancelDeletion();

      expect(await repo.currentStatus(), isNull);
    });

    test('cancel on another error rethrows and keeps the local trace',
        () async {
      final repo = build();
      await repo.requestDeletion();
      when(() => remote.cancelDeletion()).thenThrow(_conflict('FORBIDDEN'));

      await expectLater(repo.cancelDeletion(), throwsA(isA<DioException>()));
      expect(await repo.currentStatus(), isNotNull);
    });

    test('no token → nothing pending, request still succeeds', () async {
      when(() => storage.getToken()).thenAnswer((_) async => null);

      final status = await build().requestDeletion();
      expect(status.scheduledAt, now.add(const Duration(days: 30)));
      expect(await build().currentStatus(), isNull);
    });
  });
}
