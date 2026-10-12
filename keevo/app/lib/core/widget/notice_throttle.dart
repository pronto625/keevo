/// Lets a notice through at most once per [window] (v1s-16-13).
///
/// Several concurrent requests can report the same event (e.g. N requests
/// refused with SESSION_REVOKED at once): only the first one in the window
/// shows a SnackBar.
class NoticeThrottle {
  final Duration window;
  final DateTime Function() _now;
  DateTime? _lastShownAt;

  NoticeThrottle({
    this.window = const Duration(seconds: 10),
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  /// True when the notice may be shown now; records it as shown.
  bool tryAcquire() {
    final now = _now();
    final last = _lastShownAt;
    // A clock moved backwards (now before last) does not suppress notices.
    if (last != null && !now.isBefore(last) && now.difference(last) < window) {
      return false;
    }
    _lastShownAt = now;
    return true;
  }
}
