import 'package:dio/dio.dart';

import '../../features/auth/domain/repository/token_storage.dart';

/// AuthInterceptor — Dio interceptor handling JWT token injection and refresh.
///
/// AC3: Automatically refreshes the access token when a 401 TOKEN_EXPIRED
/// response is received, then retries the original request transparently.
///
/// Responsibilities:
/// - [onRequest]: inject [Authorization: Bearer <token>] header if token exists
/// - [onError]: detect 401 TOKEN_EXPIRED → refresh → retry; on refresh failure,
///   call [onSessionExpired] (clear tokens + navigate to login)
///
/// Architecture note: the [Dio] instance passed to this interceptor is the
/// SAME instance that fires the original request. To avoid the inifinite refresh
/// loop, refresh calls use a separate [Dio] instance.
class AuthInterceptor extends Interceptor {
  final TokenStorage _storage;
  final Dio _dio; // main Dio instance (for original-request retry)
  final Dio _refreshDio; // separate Dio for refresh calls (avoids interceptor loop)
  final void Function() onSessionExpired; // navigate to /auth/login + clear tokens

  /// Guards against concurrent refresh attempts.
  bool _isRefreshing = false;

  AuthInterceptor({
    required TokenStorage storage,
    required Dio dio,
    required Dio refreshDio,
    required this.onSessionExpired,
  })  : _storage = storage,
        _dio = dio,
        _refreshDio = refreshDio;

  // ── onRequest: inject Bearer token ──────────────────────────────────────

  /// Public auth endpoints that must NOT receive a Bearer token.
  static const _publicPaths = {
    '/api/v1/auth/login',
    '/api/v1/auth/register',
    '/api/v1/auth/refresh',
  };

  @override
  void onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    // Skip token injection for public auth endpoints — they don't require
    // authentication and sending a stale/stub token would pollute the request.
    final isPublic = _publicPaths.any((p) => options.path.endsWith(p));
    if (!isPublic) {
      final token = await _storage.getToken();
      if (token != null) {
        options.headers['Authorization'] = 'Bearer $token';
      }
    }
    handler.next(options);
  }

  // ── onError: handle 401 TOKEN_EXPIRED → refresh + retry ─────────────────

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final statusCode = err.response?.statusCode;

    // Only handle 401 TOKEN_EXPIRED and only once (not during refresh call itself)
    if (statusCode == 401 && !_isRefreshing) {
      final body = err.response?.data;
      final domainCode = _extractDomainCode(body);

      if (domainCode == 'TOKEN_EXPIRED') {
        _isRefreshing = true;
        try {
          final refreshToken = await _storage.getRefreshToken();
          if (refreshToken == null) {
            await _expireSession();
            handler.next(err);
            return;
          }

          // Call refresh endpoint on a separate Dio (no interceptor loop)
          final refreshResponse = await _refreshDio.post<Map<String, dynamic>>(
            '/api/v1/auth/refresh',
            data: {'refreshToken': refreshToken},
          );

          final responseData = refreshResponse.data!;
          final newAccessToken = responseData['accessToken'] as String;
          final newRefreshToken = responseData['refreshToken'] as String;

          // Persist new tokens
          await Future.wait([
            _storage.saveToken(newAccessToken),
            _storage.saveRefreshToken(newRefreshToken),
          ]);

          // Retry the original failed request with the new token
          final retryOptions = err.requestOptions;
          retryOptions.headers['Authorization'] = 'Bearer $newAccessToken';
          final retryResponse = await _dio.fetch<dynamic>(retryOptions);
          handler.resolve(retryResponse);
          return;
        } on DioException catch (_) {
          // Refresh failed → logout
          await _expireSession();
          handler.next(err);
          return;
        } finally {
          _isRefreshing = false;
        }
      }
    }

    handler.next(err);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /// Clears all tokens and triggers navigation to the login screen.
  Future<void> _expireSession() async {
    await _storage.clearAll();
    onSessionExpired();
  }

  /// Extracts the backend domainCode from various response body formats.
  String? _extractDomainCode(dynamic body) {
    if (body is Map) {
      return body['domainCode'] as String?;
    }
    return null;
  }
}
