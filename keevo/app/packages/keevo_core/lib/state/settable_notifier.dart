import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A [Notifier] holding a single value that callers set from outside
/// (Story 15.4b — replaces Riverpod 2's legacy `StateProvider`).
///
/// ```dart
/// final queryProvider =
///     NotifierProvider<SettableNotifier<String>, String>(
///         () => SettableNotifier(''));
///
/// ref.read(queryProvider.notifier).set('riz');
/// ref.read(counterProvider.notifier).update((n) => n + 1);
/// ```
///
/// Rebuilding the provider (invalidate, auto-dispose) resets it to the
/// initial value, like `StateProvider` did.
///
/// Listeners are notified when the new value is not `==` to the previous one
/// (Riverpod 3 default). For a collection state (`List`, `Map`, `Set`), `==`
/// is identity: always [set] a new instance (`[...old, x]`, `{...old}`) —
/// mutating the current one in place notifies nobody. For a value type with
/// `==` (enum, String, freezed model), setting an equal value is a no-op.
class SettableNotifier<T> extends Notifier<T> {
  SettableNotifier(this._initial);

  final T _initial;

  @override
  T build() => _initial;

  /// The current value (readable from outside, unlike [Notifier.state]).
  T get value => state;

  /// False once the provider was disposed: Riverpod 3 then throws on [set] /
  /// [update] (check it before a deferred write, e.g. from a `dispose`).
  bool get mounted => ref.mounted;

  /// Replaces the value.
  void set(T value) => state = value;

  /// Replaces the value with `cb(current)` and returns the new value.
  T update(T Function(T current) cb) => state = cb(state);
}
