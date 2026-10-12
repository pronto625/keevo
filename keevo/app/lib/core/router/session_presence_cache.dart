import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../storage/secure_storage_provider.dart';
import 'startup_route.dart';

/// Router-side cache of the session secrets, so the global auth guard does
/// not read secure storage on every navigation.
///
/// It keeps the access token and whether a refresh token exists, tagged with
/// the [sessionSecretsGeneration] read *before* the storage read. The cached
/// values are reused only while that generation is unchanged: every write or
/// clear of a session secret ([markSessionSecretsChanged]) forces the next
/// check to re-read storage. A write racing with a read bumps the generation
/// after the tag was taken, so the next check re-reads too.
///
/// Token expiry is evaluated on every call ([isSessionUsable]), so an access
/// token that expires while cached falls back to the refresh-token presence.
///
/// Storage errors are not cached: they propagate to the caller (the router
/// keeps its `/splash` fallback). Direct [hasSession] stays uncached.
class SessionPresenceCache {
  final FlutterSecureStorage _storage;
  final DateTime Function() _now;

  int? _generation;
  String? _accessToken;
  bool _hasRefreshToken = false;

  SessionPresenceCache(this._storage, {DateTime Function()? now})
      : _now = now ?? DateTime.now;

  /// Whether the device holds a usable session — same answer as [hasSession].
  Future<bool> hasSession() async {
    final generation = sessionSecretsGeneration;
    if (_generation != generation) {
      final accessToken = await _storage.read(key: kAccessTokenKey);
      final hasRefreshToken =
          await _storage.read(key: kRefreshTokenKey) != null;
      _accessToken = accessToken;
      _hasRefreshToken = hasRefreshToken;
      _generation = generation;
    }
    final hasRefreshToken = _hasRefreshToken;
    return isSessionUsable(
      _accessToken,
      hasRefreshToken: () async => hasRefreshToken,
      now: _now(),
    );
  }
}

/// One [SessionPresenceCache] per provider container (fresh in each test).
final sessionPresenceCacheProvider = Provider<SessionPresenceCache>((ref) {
  return SessionPresenceCache(ref.watch(flutterSecureStorageProvider));
});
