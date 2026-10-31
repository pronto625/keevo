import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Story 15.4b — Riverpod 3 throws when a disposed `Ref` / notifier is used
/// (Riverpod 2 let it pass silently). An auto-dispose notifier that a screen
/// only reads (`ref.read(p.notifier).save(…)`, no listener) can be disposed
/// during the `await` of its own method; [keepAliveWhile] keeps it alive
/// until [body] completes, then lets auto-dispose run again.
extension KeepAliveWhile on Ref {
  Future<T> keepAliveWhile<T>(Future<T> Function() body) async {
    final link = keepAlive();
    try {
      return await body();
    } finally {
      link.close();
    }
  }
}
