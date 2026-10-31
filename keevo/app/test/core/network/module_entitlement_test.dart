import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/network/auth_interceptor.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/network/retry_interceptor.dart';
import 'package:keevo_core/services/api_service.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:mocktail/mocktail.dart';

// 13.10b — 403 MODULE_NOT_ENTITLED / MODULE_READ_ONLY become a typed
// refusal with a French message; never a logout, a renewal or a retry.

class MockTokenStorage extends Mock implements TokenStorage {}

class MockDio extends Mock implements Dio {}

/// Answers every request with [status] + JSON [body] and counts the calls.
class _FixedAdapter implements HttpClientAdapter {
  final int status;
  final Map<String, dynamic> body;
  int calls = 0;

  _FixedAdapter(this.status, this.body);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    calls++;
    return ResponseBody.fromString(jsonEncode(body), status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

String _jwt() {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp =
      DateTime.now().add(const Duration(hours: 1)).millisecondsSinceEpoch ~/
          1000;
  return '${part({'alg': 'RS256'})}.${part({'exp': exp})}.sig';
}

DioException _dio403(String code) {
  final request = RequestOptions(path: '/api/v1/stock/overview');
  return DioException(
    requestOptions: request,
    type: DioExceptionType.badResponse,
    response: Response(
      requestOptions: request,
      statusCode: 403,
      data: {
        'domainCode': code,
        'error': 'Ce module n\'est pas inclus dans votre abonnement',
      },
    ),
  );
}

void main() {
  group('ModuleEntitlementException mapping', () {
    test('403 MODULE_NOT_ENTITLED / MODULE_READ_ONLY → typed refusal', () {
      expect(
          ModuleEntitlementException.of(_dio403('MODULE_NOT_ENTITLED'))!.reason,
          ModuleRefusalReason.notEntitled);
      expect(ModuleEntitlementException.of(_dio403('MODULE_READ_ONLY'))!.reason,
          ModuleRefusalReason.readOnly);
    });

    test('other 403 (FORBIDDEN, PLAN_LIMIT_EXCEEDED) and other statuses → null',
        () {
      expect(ModuleEntitlementException.of(_dio403('FORBIDDEN')), isNull);
      expect(ModuleEntitlementException.of(_dio403('PLAN_LIMIT_EXCEEDED')),
          isNull);
      expect(
          ModuleEntitlementException.fromResponse(
              409, {'domainCode': 'MODULE_NOT_ENTITLED'}),
          isNull);
    });

    test('ApiException and text states are recognised', () {
      expect(
          ModuleEntitlementException.of(
                  ApiException('x', 403, domainCode: 'MODULE_READ_ONLY'))!
              .reason,
          ModuleRefusalReason.readOnly);
      expect(
          ModuleEntitlementException.of(
              ApiException('x', 403, domainCode: 'ACCESS_DENIED')),
          isNull);
      expect(
          ModuleEntitlementException.of(
              ModuleEntitlementException.notEntitledMessage),
          isNotNull);
    });

    test('feature exceptions carrying the code are recognised (13.10b review)',
        () {
      expect(ModuleEntitlementException.of(_CodedException('MODULE_READ_ONLY'))
          ?.reason, ModuleRefusalReason.readOnly);
      expect(ModuleEntitlementException.of(_CodedException('REPORT_ERROR')),
          isNull);
      expect(
          const ModuleEntitlementException(ModuleRefusalReason.notEntitled)
              .code,
          'MODULE_NOT_ENTITLED');
    });

    test('appErrorMessage gives the French messages, no code', () {
      for (final error in <Object>[
        _dio403('MODULE_NOT_ENTITLED'),
        ApiException('Ce module…', 403, domainCode: 'MODULE_NOT_ENTITLED'),
        const ModuleEntitlementException(ModuleRefusalReason.notEntitled),
      ]) {
        expect(appErrorMessage(error),
            'Cette fonctionnalité fait partie de l\'offre Premium');
      }
      expect(appErrorMessage(_dio403('MODULE_READ_ONLY')),
          'Abonnement expiré : ce module est en lecture seule');
      expect(appErrorMessage(_dio403('MODULE_READ_ONLY')),
          isNot(contains('MODULE')));
    });

    test('other 403 keeps its previous message', () {
      expect(appErrorMessage(_dio403('ACCESS_DENIED')), 'Accès non autorisé.');
    });
  });

  group('Dio chain on a module refusal', () {
    late MockTokenStorage storage;
    late MockDio refreshDio;
    late int sessionExpired;
    late int accountSuspended;

    setUp(() {
      storage = MockTokenStorage();
      refreshDio = MockDio();
      sessionExpired = 0;
      accountSuspended = 0;
      when(() => storage.getToken()).thenAnswer((_) async => _jwt());
      when(() => storage.clearAll()).thenAnswer((_) async {});
    });

    Future<(DioException, _FixedAdapter)> call(String code) async {
      final dio = Dio(BaseOptions(baseUrl: 'https://keevo.test'));
      final adapter = _FixedAdapter(403, {
        'domainCode': code,
        'error': 'Ce module n\'est pas inclus dans votre abonnement',
      });
      dio.httpClientAdapter = adapter;
      dio.interceptors
        ..add(RetryOnConnectionClosedInterceptor(dio))
        ..add(AuthInterceptor(
          storage: storage,
          dio: dio,
          refreshDio: refreshDio,
          onSessionExpired: () => sessionExpired++,
          onAccountSuspended: () => accountSuspended++,
        ))
        ..add(ModuleEntitlementInterceptor());
      try {
        await dio.get<dynamic>('/api/v1/stock/overview');
        fail('expected a 403');
      } on DioException catch (e) {
        return (e, adapter);
      }
    }

    for (final code in ['MODULE_NOT_ENTITLED', 'MODULE_READ_ONLY']) {
      test('$code: typed error, sent once, no logout / refresh', () async {
        final (error, adapter) = await call(code);

        expect(error.error, isA<ModuleEntitlementException>());
        expect(error.response?.statusCode, 403);
        expect(adapter.calls, 1, reason: 'never retried');
        expect(sessionExpired, 0);
        expect(accountSuspended, 0);
        verifyNever(() => storage.clearAll());
        verifyZeroInteractions(refreshDio);
      });
    }

    test('other 403 is left untouched', () async {
      final (error, adapter) = await call('FORBIDDEN');
      expect(error.error, isNot(isA<ModuleEntitlementException>()));
      expect(adapter.calls, 1);
      expect(sessionExpired, 0);
    });
  });
}

/// A feature exception (ReportException-like) exposing `domainCode`.
class _CodedException implements Exception {
  final String domainCode;
  _CodedException(this.domainCode);
}
