/// ConnectivityService — Abstraction for network connectivity detection.
///
/// Strategy pattern: allows swapping implementations (real connectivity_plus
/// vs test mock) without changing callers.
///
/// Used by all repositories to decide the write path:
/// online → backend-first, offline → local-first + sync_queue.
abstract class ConnectivityService {
  /// Returns true if network is available (wifi, mobile, ethernet).
  Future<bool> isOnline();

  /// Stream of connectivity changes (true = online, false = offline).
  Stream<bool> get onlineStream;
}
