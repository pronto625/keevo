import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/network/auth_interceptor.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
void main() {
  group('AuthInterceptor — 403 PLAN_LIMIT_EXCEEDED', () {
    late Dio dio;
    late Dio refreshDio;

    setUp(() {
      dio = Dio();
      refreshDio = Dio();
    });

    tearDown(() {
      dio.close();
      refreshDio.close();
    });

    test('intercepts 403 PLAN_LIMIT_EXCEEDED and calls onPlanLimitExceeded callback',
        () async {
      String? capturedEntity;
      int? capturedLimit;

      final storage = _FakeTokenStorage();
      final interceptor = AuthInterceptor(
        storage: storage,
        dio: dio,
        refreshDio: refreshDio,
        onSessionExpired: () {},
        onPlanLimitExceeded: (entity, limit) {
          capturedEntity = entity;
          capturedLimit = limit;
        },
      );

      final options = RequestOptions(path: '/api/v1/stores');
      final response = Response<Map<String, dynamic>>(
        requestOptions: options,
        statusCode: 403,
        data: {
          'domainCode': 'PLAN_LIMIT_EXCEEDED',
          'details': {
            'entity': 'stores',
            'limit': '1',
          },
        },
      );
      final dioError = DioException(
        requestOptions: options,
        response: response,
        type: DioExceptionType.badResponse,
      );

      bool handlerCalled = false;
      final handler = _TestErrorInterceptorHandler(
        onNextCalled: () => handlerCalled = true,
      );

      interceptor.onError(dioError, handler);

      expect(capturedEntity, equals('stores'));
      expect(capturedLimit, equals(1));
      expect(handlerCalled, isTrue);
    });

    test('does NOT call onPlanLimitExceeded for 403 with different domainCode',
        () async {
      bool callbackCalled = false;

      final storage = _FakeTokenStorage();
      final interceptor = AuthInterceptor(
        storage: storage,
        dio: dio,
        refreshDio: refreshDio,
        onSessionExpired: () {},
        onPlanLimitExceeded: (_, __) => callbackCalled = true,
      );

      final options = RequestOptions(path: '/test');
      final response = Response<Map<String, dynamic>>(
        requestOptions: options,
        statusCode: 403,
        data: {'domainCode': 'ACCOUNT_SUSPENDED'},
      );
      final dioError = DioException(
        requestOptions: options,
        response: response,
        type: DioExceptionType.badResponse,
      );

      bool handlerCalled = false;
      final handler = _TestErrorInterceptorHandler(
        onNextCalled: () => handlerCalled = true,
      );

      interceptor.onError(dioError, handler);

      expect(callbackCalled, isFalse);
      expect(handlerCalled, isTrue);
    });
  });
}

/// Fake token storage for tests — returns null for all tokens.
class _FakeTokenStorage implements TokenStorage {
  @override
  Future<String?> getToken() async => null;

  @override
  Future<String?> getRefreshToken() async => null;

  @override
  Future<void> saveToken(String token) async {}

  @override
  Future<void> saveRefreshToken(String token) async {}

  @override
  Future<void> saveUserId(String userId) async {}

  @override
  Future<void> saveTenantId(String tenantId) async {}

  @override
  Future<void> clearAll() async {}
}

/// Minimal [ErrorInterceptorHandler] that records whether [next] was called.
class _TestErrorInterceptorHandler extends ErrorInterceptorHandler {
  final void Function() onNextCalled;

  _TestErrorInterceptorHandler({required this.onNextCalled});

  @override
  void next(DioException err) => onNextCalled();

  @override
  void resolve(Response response) {}

  @override
  void reject(DioException err, {bool callFollowingErrorInterceptor = false}) {}
}
