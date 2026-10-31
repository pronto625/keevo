import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/root_scope.dart';
import 'package:keevo_core/state/provider_retry.dart';

final _answer = Provider<int>((ref) => 1);

/// Story 15.4b — the root scope built by `main` disables Riverpod 3's
/// automatic retry of failing providers.
void main() {
  test('the root scope never retries a failing provider', () {
    final scope = buildRootScope(overrides: const [], child: const SizedBox());
    expect(scope.retry, same(noProviderRetry));
  });

  testWidgets('the root scope keeps its overrides', (tester) async {
    late int read;
    await tester.pumpWidget(buildRootScope(
      overrides: [_answer.overrideWithValue(42)],
      child: Consumer(builder: (_, ref, __) {
        read = ref.watch(_answer);
        return const SizedBox();
      }),
    ));
    expect(read, 42);
  });
}
