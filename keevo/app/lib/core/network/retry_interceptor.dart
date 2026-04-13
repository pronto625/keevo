import 'package:dio/dio.dart';

/// RetryOnConnectionClosedInterceptor
///
/// Retries a request exactly once when Dio fails with the
/// "Connection closed before full header was received" error.
///
/// Root cause: Dio reuses HTTP/1.1 keep-alive connections. If the server
/// (Spring Boot) silently closes an idle persistent connection while Dio hasn't
/// noticed, the next request that tries to reuse that socket receives this
/// error. A single retry opens a fresh connection and succeeds.
///
/// A guard flag (`extra['_retried']`) prevents infinite retry loops.
class RetryOnConnectionClosedInterceptor extends Interceptor {
  final Dio _dio;

  RetryOnConnectionClosedInterceptor(this._dio);

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    // Only retry on connection-closed errors (not auth, not timeouts, etc.).
    if (err.type == DioExceptionType.unknown &&
        err.error?.toString().contains('Connection closed') == true &&
        err.requestOptions.extra['_retried'] != true) {
      // Mark so the retried request won't loop back here.
      final options = err.requestOptions;
      options.extra['_retried'] = true;
      try {
        final response = await _dio.fetch<dynamic>(options);
        handler.resolve(response);
        return;
      } catch (_) {
        // Retry also failed — fall through to the original error.
      }
    }
    handler.next(err);
  }
}
