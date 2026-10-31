import 'package:dio/dio.dart';
import 'package:keevo_core/auth/jwt_claims.dart';

import '../../features/auth/domain/repository/token_storage.dart';
import '../auth/session_refresher.dart';

/// AuthInterceptor — Dio interceptor handling JWT injection and session renewal.
///
/// A session is renewed with the refresh token, never by asking the user to
/// sign in again ([SessionRefresher]):
/// - [onRequest]: injects `Authorization: Bearer <token>`; an access token
///   that is already expired is renewed first.
/// - [onError] 401 TOKEN_EXPIRED / STORE_REASSIGNED: renew, then retry once
///   with the new token. STORE_REASSIGNED means the token's claims are stale
///   (employee moved to another store); the renewed token carries the new store.
/// - Only a server refusal of the refresh token ends the session. Offline or a
///   server error keeps it, so the shopkeeper keeps working offline.
/// - 401 SESSION_REVOKED / ACCOUNT_INACTIVE: session ended server-side →
///   [onSessionExpired] + [onAccountSuspended].
/// - 403 PLAN_LIMIT_EXCEEDED → [onPlanLimitExceeded].
///
/// The refresh call goes through a separate [Dio] without this interceptor,
/// to avoid refresh loops.
class AuthInterceptor extends Interceptor {
  /// Marks a request already retried after a renewal, so a second 401 is final.
  static const retriedKey = 'auth.retried';

  /// Renew this long before expiry, so a request does not expire in flight.
  static const _renewalMargin = Duration(minutes: 1);

  final TokenStorage _storage;
  final Dio _dio; // main Dio instance (for original-request retry)
  final SessionRefresher _refresher;
  final void Function()
      onSessionExpired; // navigate to /auth/login + clear tokens

  /// Called when 403 PLAN_LIMIT_EXCEEDED is received. Parameters:
  /// - [entity]: 'stores' | 'products' | 'employees'
  /// - [limit]: max allowed count for the current plan
  final void Function(String entity, int limit)? onPlanLimitExceeded;

  /// Called when 401 SESSION_REVOKED or ACCOUNT_INACTIVE is received, after the
  /// tokens were cleared — the caller informs the user.
  final void Function()? onAccountSuspended;

  AuthInterceptor({
    required TokenStorage storage,
    required Dio dio,
    required Dio refreshDio,
    required this.onSessionExpired,
    this.onPlanLimitExceeded,
    this.onAccountSuspended,
    SessionRefresher? refresher,
    Future<void> Function(String accessToken)? onSessionRenewed,
  })  : _storage = storage,
        _dio = dio,
        _refresher = refresher ??
            SessionRefresher(
              storage: storage,
              refreshDio: refreshDio,
              onRenewed: onSessionRenewed,
            );

  /// Public auth endpoints that must NOT receive a Bearer token. Logout is
  /// public on the server: signing out with an expired access token must not
  /// renew (rotate) the session first.
  static const _publicPaths = {
    '/api/v1/auth/login',
    '/api/v1/auth/register',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
  };

  static const _renewableCodes = {'TOKEN_EXPIRED', 'STORE_REASSIGNED'};

  // ── onRequest: inject Bearer token, renew an expired one first ───────────

  @override
  void onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    final isPublic = _publicPaths.any((p) => options.path.endsWith(p));
    if (!isPublic) {
      try {
        var token = await _storage.getToken();
        final claims = JwtClaims.tryParse(token);
        if (claims != null && claims.isExpired(margin: _renewalMargin)) {
          final outcome = await _refresher.refresh();
          if (outcome == RefreshOutcome.renewed) {
            token = await _storage.getToken();
          } else if (outcome == RefreshOutcome.rejected) {
            await _expireSession();
            token = null;
          }
          // unreachable: send as-is — offline, the request fails on the network
          // anyway, and the session must survive until the server is reachable.
        }
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
      } catch (_) {
        // Storage or session-apply failure: the request must still be sent
        // (or fail on its own), never hang without reaching the handler.
      }
    }
    handler.next(options);
  }

  // ── onError ───────────────────────────────────────────────────────────────

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final statusCode = err.response?.statusCode;
    final domainCode = _extractDomainCode(err.response?.data);

    if (statusCode == 401) {
      if (domainCode == 'SESSION_REVOKED' || domainCode == 'ACCOUNT_INACTIVE') {
        try {
          await _expireSession();
          onAccountSuspended?.call();
        } catch (_) {
          // Storage failure — still propagate the error so the request
          // doesn't hang indefinitely.
        }
        handler.next(err);
        return;
      }

      final alreadyRetried = err.requestOptions.extra[retriedKey] == true;
      if (_renewableCodes.contains(domainCode) && !alreadyRetried) {
        await _renewAndRetry(err, handler);
        return;
      }
    }

    // ── 403 PLAN_LIMIT_EXCEEDED ──────────────────────────────────────────
    if (statusCode == 403) {
      final body = err.response?.data;
      if (domainCode == 'PLAN_LIMIT_EXCEEDED' && onPlanLimitExceeded != null) {
        final entity = _extractDetail(body, 'entity') ?? 'items';
        final limitStr = _extractDetail(body, 'limit') ?? '0';
        final limit = int.tryParse(limitStr) ?? 0;
        onPlanLimitExceeded!(entity, limit);
      }
    }

    handler.next(err);
  }

  Future<void> _renewAndRetry(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    final sentHeader = err.requestOptions.headers['Authorization'];
    String? current;
    try {
      current = await _storage.getToken();

      // Another request may already have renewed the session while this one was
      // in flight: retry with that token instead of spending another refresh.
      final renewedMeanwhile =
          current != null && sentHeader != 'Bearer $current';
      if (!renewedMeanwhile) {
        final outcome = await _refresher.refresh();
        if (outcome == RefreshOutcome.rejected) {
          await _expireSession();
          handler.next(err);
          return;
        }
        if (outcome == RefreshOutcome.unreachable) {
          handler.next(err);
          return;
        }
        current = await _storage.getToken();
      }
    } catch (_) {
      // Storage or session-apply failure: hand the original error back.
      handler.next(err);
      return;
    }
    if (current == null) {
      handler.next(err);
      return;
    }

    final retryOptions = err.requestOptions
      ..headers['Authorization'] = 'Bearer $current'
      ..extra[retriedKey] = true;
    try {
      handler.resolve(await _dio.fetch<dynamic>(retryOptions));
    } on DioException catch (retryError) {
      // The retry's own failure (409, 422, timeout…) belongs to the caller.
      handler.next(retryError);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /// Clears the session tokens and triggers navigation to the login screen.
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

  /// Extracts a string value from the 'details' map in the response body.
  String? _extractDetail(dynamic body, String key) {
    if (body is Map) {
      final details = body['details'];
      if (details is Map) {
        return details[key]?.toString();
      }
    }
    return null;
  }
}
