import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/network/auth_interceptor.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockTokenStorage extends Mock implements TokenStorage {}

class MockDio extends Mock implements Dio {}

class MockRequestInterceptorHandler extends Mock
    implements RequestInterceptorHandler {}

class MockErrorInterceptorHandler extends Mock
    implements ErrorInterceptorHandler {}

// Lets the async-void interceptor callbacks run to completion.
Future<void> _pump() async {
  for (var i = 0; i < 10; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

String _jwt({required Duration expiresIn}) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp = DateTime.now().add(expiresIn).millisecondsSinceEpoch ~/ 1000;
  return '${part({'alg': 'RS256'})}.${part({'exp': exp, 'role': 'OWNER'})}.sig';
}

final _validToken = _jwt(expiresIn: const Duration(hours: 1));
final _expiredToken = _jwt(expiresIn: const Duration(hours: -1));
final _renewedToken = _jwt(expiresIn: const Duration(hours: 2));

DioException _unauthorized(RequestOptions request, String domainCode) =>
    DioException(
      requestOptions: request,
      response: Response(
        requestOptions: request,
        statusCode: 401,
        data: {'domainCode': domainCode},
      ),
    );

RequestOptions _sentWith(String token, {String path = '/api/v1/products'}) =>
    RequestOptions(path: path, headers: {'Authorization': 'Bearer $token'});

Response<dynamic> _ok(RequestOptions request) =>
    Response(requestOptions: request, statusCode: 200, data: {'items': []});

void main() {
  late MockTokenStorage storage;
  late MockDio dio;
  late MockDio refreshDio;
  late int sessionExpiredCount;
  late int accountSuspendedCount;
  late List<String> renewed;
  late AuthInterceptor interceptor;
  late String storedToken;

  setUpAll(() {
    registerFallbackValue(RequestOptions(path: '/'));
    registerFallbackValue(DioException(requestOptions: RequestOptions(path: '/')));
    registerFallbackValue(Response<dynamic>(requestOptions: RequestOptions(path: '/')));
  });

  setUp(() {
    storage = MockTokenStorage();
    dio = MockDio();
    refreshDio = MockDio();
    sessionExpiredCount = 0;
    accountSuspendedCount = 0;
    renewed = [];
    storedToken = _validToken;

    when(() => storage.getToken()).thenAnswer((_) async => storedToken);
    when(() => storage.getRefreshToken()).thenAnswer((_) async => 'refresh-1');
    when(() => storage.saveToken(any())).thenAnswer((inv) async {
      storedToken = inv.positionalArguments.first as String;
    });
    when(() => storage.saveRefreshToken(any())).thenAnswer((_) async {});
    when(() => storage.clearAll()).thenAnswer((_) async {});

    interceptor = AuthInterceptor(
      storage: storage,
      dio: dio,
      refreshDio: refreshDio,
      onSessionExpired: () => sessionExpiredCount++,
      onAccountSuspended: () => accountSuspendedCount++,
      onSessionRenewed: (token) async => renewed.add(token),
    );
  });

  void refreshReturnsNewTokens() {
    when(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
              statusCode: 200,
              data: {'accessToken': _renewedToken, 'refreshToken': 'refresh-2'},
            ));
  }

  void refreshThrows(DioException error) {
    when(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenThrow(error);
  }

  void retrySucceeds() {
    when(() => dio.fetch<dynamic>(any()))
        .thenAnswer((inv) async => _ok(inv.positionalArguments.first as RequestOptions));
  }

  MockErrorInterceptorHandler errorHandler() {
    final h = MockErrorInterceptorHandler();
    when(() => h.next(any())).thenReturn(null);
    when(() => h.resolve(any())).thenReturn(null);
    return h;
  }

  group('onRequest', () {
    MockRequestInterceptorHandler requestHandler() {
      final h = MockRequestInterceptorHandler();
      when(() => h.next(any())).thenReturn(null);
      return h;
    }

    test('adds the Bearer token', () async {
      final options = RequestOptions(path: '/api/v1/products');

      interceptor.onRequest(options, requestHandler());
      await _pump();

      expect(options.headers['Authorization'], 'Bearer $_validToken');
    });

    test('logout is public: an expired token is not renewed first', () async {
      storedToken = _expiredToken;
      final options = RequestOptions(path: '/api/v1/auth/logout');
      final h = requestHandler();

      interceptor.onRequest(options, h);
      await _pump();

      verifyNever(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
      expect(options.headers.containsKey('Authorization'), isFalse);
      verify(() => h.next(options)).called(1);
    });

    // An async interceptor that throws before calling the handler leaves the
    // request pending forever.
    test('a failure while renewing still sends the request', () async {
      storedToken = _expiredToken;
      refreshReturnsNewTokens();
      interceptor = AuthInterceptor(
        storage: storage,
        dio: dio,
        refreshDio: refreshDio,
        onSessionExpired: () => sessionExpiredCount++,
        onSessionRenewed: (_) async => throw StateError('prefs unavailable'),
      );
      final options = RequestOptions(path: '/api/v1/products');
      final h = requestHandler();

      interceptor.onRequest(options, h);
      await _pump();

      verify(() => h.next(options)).called(1);
    });

    test('no header when no token is stored', () async {
      storedToken = '';
      when(() => storage.getToken()).thenAnswer((_) async => null);
      final options = RequestOptions(path: '/api/v1/products');

      interceptor.onRequest(options, requestHandler());
      await _pump();

      expect(options.headers.containsKey('Authorization'), isFalse);
    });

    test('public auth endpoints get no token and no refresh', () async {
      storedToken = _expiredToken;
      final options = RequestOptions(path: '/api/v1/auth/login');

      interceptor.onRequest(options, requestHandler());
      await _pump();

      expect(options.headers.containsKey('Authorization'), isFalse);
      verifyNever(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
    });

    test('an expired access token is renewed before the request is sent', () async {
      storedToken = _expiredToken;
      refreshReturnsNewTokens();
      final options = RequestOptions(path: '/api/v1/products');

      interceptor.onRequest(options, requestHandler());
      await _pump();

      expect(options.headers['Authorization'], 'Bearer $_renewedToken');
      expect(renewed, [_renewedToken]);
    });

    test('offline: the expired token is still sent and the session kept', () async {
      storedToken = _expiredToken;
      refreshThrows(DioException(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        type: DioExceptionType.connectionError,
      ));
      final options = RequestOptions(path: '/api/v1/products');

      interceptor.onRequest(options, requestHandler());
      await _pump();

      expect(options.headers['Authorization'], 'Bearer $_expiredToken');
      verifyNever(() => storage.clearAll());
      expect(sessionExpiredCount, 0);
    });
  });

  group('onError — renewal', () {
    test('TOKEN_EXPIRED: refresh, then retry with the new token', () async {
      refreshReturnsNewTokens();
      retrySucceeds();
      final handler = errorHandler();

      interceptor.onError(_unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED'), handler);
      await _pump();

      final retried = verify(() => dio.fetch<dynamic>(captureAny())).captured.single
          as RequestOptions;
      expect(retried.headers['Authorization'], 'Bearer $_renewedToken');
      verify(() => handler.resolve(any())).called(1);
      verify(() => storage.saveRefreshToken('refresh-2')).called(1);
    });

    test('STORE_REASSIGNED: renewed claims carry the new store, request retried', () async {
      refreshReturnsNewTokens();
      retrySucceeds();
      final handler = errorHandler();

      interceptor.onError(_unauthorized(_sentWith(_validToken), 'STORE_REASSIGNED'), handler);
      await _pump();

      expect(renewed, [_renewedToken]);
      verify(() => handler.resolve(any())).called(1);
    });

    test('concurrent 401s share a single refresh', () async {
      final gate = Completer<Response<Map<String, dynamic>>>();
      when(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
          .thenAnswer((_) => gate.future);
      retrySucceeds();
      final h1 = errorHandler();
      final h2 = errorHandler();

      interceptor.onError(_unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED'), h1);
      interceptor.onError(_unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED'), h2);
      await _pump();
      gate.complete(Response(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        statusCode: 200,
        data: {'accessToken': _renewedToken, 'refreshToken': 'refresh-2'},
      ));
      await _pump();

      verify(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
          .called(1);
      verify(() => h1.resolve(any())).called(1);
      verify(() => h2.resolve(any())).called(1);
    });

    test('token already renewed by another request: retry without refreshing', () async {
      storedToken = _renewedToken;
      retrySucceeds();
      final handler = errorHandler();

      interceptor.onError(_unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED'), handler);
      await _pump();

      verifyNever(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
      verify(() => handler.resolve(any())).called(1);
    });

    test('refresh refused by the server: session expired', () async {
      refreshThrows(DioException(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
          statusCode: 401,
        ),
      ));
      final handler = errorHandler();
      final err = _unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED');

      interceptor.onError(err, handler);
      await _pump();

      verify(() => storage.clearAll()).called(1);
      expect(sessionExpiredCount, 1);
      verify(() => handler.next(err)).called(1);
    });

    test('network error during refresh keeps the session', () async {
      refreshThrows(DioException(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        type: DioExceptionType.connectionTimeout,
      ));
      final handler = errorHandler();
      final err = _unauthorized(_sentWith(_validToken), 'TOKEN_EXPIRED');

      interceptor.onError(err, handler);
      await _pump();

      verifyNever(() => storage.clearAll());
      expect(sessionExpiredCount, 0);
      verify(() => handler.next(err)).called(1);
    });

    test('a failing retry is handed to the caller, the session kept', () async {
      refreshReturnsNewTokens();
      final request = _sentWith(_validToken);
      final conflict = DioException(
        requestOptions: request,
        response: Response(requestOptions: request, statusCode: 409),
      );
      when(() => dio.fetch<dynamic>(any())).thenThrow(conflict);
      final handler = errorHandler();

      interceptor.onError(_unauthorized(request, 'TOKEN_EXPIRED'), handler);
      await _pump();

      verifyNever(() => storage.clearAll());
      expect(sessionExpiredCount, 0);
      verify(() => handler.next(conflict)).called(1);
    });

    test('a retried request answering 401 again is not retried a second time', () async {
      final request = _sentWith(_validToken)..extra[AuthInterceptor.retriedKey] = true;
      final handler = errorHandler();
      final err = _unauthorized(request, 'TOKEN_EXPIRED');

      interceptor.onError(err, handler);
      await _pump();

      verifyNever(() => refreshDio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
      verify(() => handler.next(err)).called(1);
    });

    test('other 401s pass through untouched', () async {
      final handler = errorHandler();
      final err = _unauthorized(_sentWith(_validToken), 'INVALID_CREDENTIALS');

      interceptor.onError(err, handler);
      await _pump();

      verifyNever(() => storage.getRefreshToken());
      verifyNever(() => storage.clearAll());
      verify(() => handler.next(err)).called(1);
    });
  });

  group('onError — revoked access', () {
    for (final code in ['SESSION_REVOKED', 'ACCOUNT_INACTIVE']) {
      test('$code clears the session and notifies the user', () async {
        final handler = errorHandler();
        final err = _unauthorized(_sentWith(_validToken), code);

        interceptor.onError(err, handler);
        await _pump();

        verify(() => storage.clearAll()).called(1);
        expect(sessionExpiredCount, 1);
        expect(accountSuspendedCount, 1);
        verify(() => handler.next(err)).called(1);
      });
    }
  });
}
