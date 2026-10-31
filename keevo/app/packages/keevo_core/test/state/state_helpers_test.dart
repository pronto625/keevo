import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/state/keep_alive_while.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/state/settable_notifier.dart';

final _counter =
    NotifierProvider<SettableNotifier<int>, int>(() => SettableNotifier(3));

/// Auto-dispose action notifier only read (never listened), like the
/// screens do with `ref.read(p.notifier).save(…)`.
class _Action extends Notifier<int> {
  @override
  int build() => 0;

  Future<int> run(Future<void> gap) => ref.keepAliveWhile(() async {
        await gap;
        return state = state + 1;
      });
}

final _action = NotifierProvider.autoDispose<_Action, int>(_Action.new);

void main() {
  group('SettableNotifier (Story 15.4b)', () {
    test('starts at its initial value, set / update replace it', () {
      final c = ProviderContainer(retry: noProviderRetry);
      addTearDown(c.dispose);
      expect(c.read(_counter), 3);
      c.read(_counter.notifier).set(5);
      expect(c.read(_counter), 5);
      expect(c.read(_counter.notifier).update((n) => n + 1), 6);
      expect(c.read(_counter.notifier).value, 6);
    });

    test('an invalidated provider goes back to the initial value', () {
      final c = ProviderContainer(retry: noProviderRetry);
      addTearDown(c.dispose);
      c.read(_counter.notifier).set(9);
      c.invalidate(_counter);
      expect(c.read(_counter), 3);
    });
  });

  test('keepAliveWhile keeps a read-only auto-dispose notifier usable', () async {
    final c = ProviderContainer(retry: noProviderRetry);
    addTearDown(c.dispose);
    final notifier = c.read(_action.notifier);
    final gap = Future<void>.delayed(const Duration(milliseconds: 10));
    expect(await notifier.run(gap), 1);
  });

  test('noProviderRetry never retries', () {
    expect(noProviderRetry(0, Exception('x')), isNull);
    expect(noProviderRetry(5, StateError('x')), isNull);
  });
}
