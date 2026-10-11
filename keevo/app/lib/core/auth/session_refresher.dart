import 'package:dio/dio.dart';

import '../../features/auth/domain/repository/token_storage.dart';

/// Result of asking the server for a new access token.
enum RefreshOutcome {
  /// New access + refresh tokens stored.
  renewed,

  /// The server refused the refresh token (revoked, expired, replayed) — the
  /// session is over and the user has to sign in again.
  rejected,

  /// The server could not be reached or failed (offline, timeout, 5xx). Says
  /// nothing about the session: keep the tokens and work offline.
  unreachable,
}

/// Renews the session with the stored refresh token — the only way the app
/// keeps a user signed in, at startup and whenever an access token expires.
///
/// Single-flight: concurrent callers share one request. Refresh tokens are
/// single-use on the server, so two parallel refreshes would invalidate each
/// other and look like a replay.
class SessionRefresher {
  final TokenStorage _storage;
  final Dio _refreshDio;
  final Future<void> Function(String accessToken)? _onRenewed;

  Future<RefreshOutcome>? _inFlight;

  SessionRefresher({
    required TokenStorage storage,
    required Dio refreshDio,
    Future<void> Function(String accessToken)? onRenewed,
  })  : _storage = storage,
        _refreshDio = refreshDio,
        _onRenewed = onRenewed;

  Future<RefreshOutcome> refresh() {
    return _inFlight ??= _refresh().whenComplete(() => _inFlight = null);
  }

  Future<RefreshOutcome> _refresh() async {
    final refreshToken = await _storage.getRefreshToken();
    if (refreshToken == null) return RefreshOutcome.rejected;

    final Response<Map<String, dynamic>> response;
    try {
      response = await _refreshDio.post<Map<String, dynamic>>(
        '/api/v1/auth/refresh',
        data: {'refreshToken': refreshToken},
      );
    } on DioException catch (e) {
      // Only an explicit refusal ends the session. A throttle (429), timeout
      // (408) or conflict from a proxy says nothing about the token.
      final status = e.response?.statusCode;
      final refused = status == 400 || status == 401 || status == 403;
      return refused ? RefreshOutcome.rejected : RefreshOutcome.unreachable;
    }

    final body = response.data;
    final access = body?['accessToken'] as String?;
    final refresh = body?['refreshToken'] as String?;
    if (access == null || refresh == null) return RefreshOutcome.unreachable;

    await _storage.saveToken(access);
    await _storage.saveRefreshToken(refresh);
    await _onRenewed?.call(access);
    return RefreshOutcome.renewed;
  }
}
