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

// Helper: pumps microtasks (lets async-void methods complete)
Future<void> _pump() => Future.delayed(Duration.zero);

void main() {
  late MockTokenStorage mockStorage;
  late MockDio mockDio;
  late MockDio mockRefreshDio;
  late int sessionExpiredCount;
  late AuthInterceptor interceptor;

  setUpAll(() {
    registerFallbackValue(RequestOptions(path: '/'));
    registerFallbackValue(
      DioException(requestOptions: RequestOptions(path: '/')),
    );
    registerFallbackValue(
      Response<dynamic>(requestOptions: RequestOptions(path: '/')),
    );
  });

  setUp(() {
    mockStorage = MockTokenStorage();
    mockDio = MockDio();
    mockRefreshDio = MockDio();
    sessionExpiredCount = 0;

    interceptor = AuthInterceptor(
      storage: mockStorage,
      dio: mockDio,
      refreshDio: mockRefreshDio,
      onSessionExpired: () => sessionExpiredCount++,
    );
  });

  group('AuthInterceptor.onRequest()', () {
    test('adds Bearer token header when jwt_token is stored', () async {
      when(() => mockStorage.getToken()).thenAnswer((_) async => 'access.jwt.token');
      final handler = MockRequestInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final options = RequestOptions(path: '/api/v1/products');
      interceptor.onRequest(options, handler); // async void — can't await
      await _pump(); // drain microtask queue

      expect(options.headers['Authorization'], equals('Bearer access.jwt.token'));
      verify(() => handler.next(options)).called(1);
    });

    test('does NOT add header when no token stored', () async {
      when(() => mockStorage.getToken()).thenAnswer((_) async => null);
      final handler = MockRequestInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final options = RequestOptions(path: '/api/v1/products');
      interceptor.onRequest(options, handler);
      await _pump();

      expect(options.headers.containsKey('Authorization'), isFalse);
    });
  });

  group('AuthInterceptor.onError() — TOKEN_EXPIRED + refresh', () {
    test(
        'AC3: 401 TOKEN_EXPIRED triggers refresh call and retries original request',
        () async {
      when(() => mockStorage.getRefreshToken())
          .thenAnswer((_) async => 'old-refresh-token');
      when(() => mockStorage.saveToken(any())).thenAnswer((_) async {});
      when(() => mockStorage.saveRefreshToken(any())).thenAnswer((_) async {});

      // Refresh endpoint returns new tokens
      when(() => mockRefreshDio.post<Map<String, dynamic>>(
            any(),
            data: any(named: 'data'),
          )).thenAnswer(
        (_) async => Response(
          requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
          statusCode: 200,
          data: {
            'accessToken': 'new.access.token',
            'refreshToken': 'new-refresh-token',
          },
        ),
      );

      // Retry original request succeeds
      when(() => mockDio.fetch<dynamic>(any())).thenAnswer(
        (_) async => Response(
          requestOptions: RequestOptions(path: '/api/v1/products'),
          statusCode: 200,
          data: {'items': []},
        ),
      );

      final handler = MockErrorInterceptorHandler();
      when(() => handler.resolve(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'TOKEN_EXPIRED'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();
      await _pump(); // two pumps for nested async ops

      // Verify refresh was called
      verify(() => mockRefreshDio.post<Map<String, dynamic>>(
            '/api/v1/auth/refresh',
            data: {'refreshToken': 'old-refresh-token'},
          )).called(1);

      // Verify new tokens persisted
      verify(() => mockStorage.saveToken('new.access.token')).called(1);
      verify(() => mockStorage.saveRefreshToken('new-refresh-token')).called(1);

      // Verify original request retried
      verify(() => mockDio.fetch<dynamic>(any())).called(1);

      // Verify resolved (not errored)
      verify(() => handler.resolve(any())).called(1);
    });

    test(
        'AC3: refresh failure clears tokens and calls onSessionExpired',
        () async {
      when(() => mockStorage.getRefreshToken())
          .thenAnswer((_) async => 'expired-refresh-token');
      when(() => mockStorage.clearAll()).thenAnswer((_) async {});

      // Refresh call fails with 401
      when(() => mockRefreshDio.post<Map<String, dynamic>>(
            any(),
            data: any(named: 'data'),
          )).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
          response: Response(
            requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
            statusCode: 401,
          ),
        ),
      );

      final handler = MockErrorInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'TOKEN_EXPIRED'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();
      await _pump();

      // Verify tokens cleared
      verify(() => mockStorage.clearAll()).called(1);

      // Verify session expired callback was invoked
      expect(sessionExpiredCount, equals(1));
    });

    test('passes non-TOKEN_EXPIRED 401 errors through unchanged', () async {
      final handler = MockErrorInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'INVALID_CREDENTIALS'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();

      verifyNever(() => mockStorage.getRefreshToken());
      verify(() => handler.next(err)).called(1);
    });
  });

  group('AuthInterceptor.onError() — SESSION_REVOKED / ACCOUNT_INACTIVE', () {
    late int accountSuspendedCount;

    setUp(() {
      accountSuspendedCount = 0;
      interceptor = AuthInterceptor(
        storage: mockStorage,
        dio: mockDio,
        refreshDio: mockRefreshDio,
        onSessionExpired: () => sessionExpiredCount++,
        onAccountSuspended: () => accountSuspendedCount++,
      );
    });

    test(
        'AC4: 401 SESSION_REVOKED clears tokens, calls onSessionExpired AND '
        'onAccountSuspended, then passes through', () async {
      when(() => mockStorage.clearAll()).thenAnswer((_) async {});

      final handler = MockErrorInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'SESSION_REVOKED'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();

      verify(() => mockStorage.clearAll()).called(1);
      expect(sessionExpiredCount, equals(1));
      expect(accountSuspendedCount, equals(1));
      verify(() => handler.next(err)).called(1);
    });

    test(
        'AC4: 401 ACCOUNT_INACTIVE clears tokens, calls both callbacks, '
        'then passes through', () async {
      when(() => mockStorage.clearAll()).thenAnswer((_) async {});

      final handler = MockErrorInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'ACCOUNT_INACTIVE'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();

      verify(() => mockStorage.clearAll()).called(1);
      expect(sessionExpiredCount, equals(1));
      expect(accountSuspendedCount, equals(1));
      verify(() => handler.next(err)).called(1);
    });

    test(
        'INVALID_CREDENTIALS still passes through unchanged '
        '(not matched by SESSION_REVOKED nor TOKEN_EXPIRED)', () async {
      final handler = MockErrorInterceptorHandler();
      when(() => handler.next(any())).thenReturn(null);

      final originalRequest = RequestOptions(path: '/api/v1/products');
      final err = DioException(
        requestOptions: originalRequest,
        response: Response(
          requestOptions: originalRequest,
          statusCode: 401,
          data: {'domainCode': 'INVALID_CREDENTIALS'},
        ),
      );

      interceptor.onError(err, handler);
      await _pump();

      verifyNever(() => mockStorage.getRefreshToken());
      verifyNever(() => mockStorage.clearAll());
      expect(sessionExpiredCount, equals(0));
      expect(accountSuspendedCount, equals(0));
      verify(() => handler.next(err)).called(1);
    });
  });
}

