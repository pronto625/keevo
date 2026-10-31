import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/widget/deferred_widget.dart';

void main() {
  testWidgets('loading: spinner with a back bar; then the built screen',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: DeferredWidget(
        loader: () => Future<void>.delayed(const Duration(seconds: 1)),
        builder: (_) => const Text('SCREEN'),
      ),
    ));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byType(AppBar), findsOneWidget);

    await tester.pumpAndSettle(const Duration(seconds: 1));
    expect(find.text('SCREEN'), findsOneWidget);
  });

  testWidgets('load failure → French error, « Réessayer » loads again',
      (tester) async {
    var attempts = 0;
    await tester.pumpWidget(MaterialApp(
      home: DeferredWidget(
        loader: () async {
          attempts++;
          if (attempts == 1) throw Exception('Deferred library failed');
        },
        builder: (_) => const Text('SCREEN'),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Réessayer'), findsOneWidget);
    expect(find.text(kDeferredLoadFailedMessage), findsOneWidget);
    expect(find.textContaining('Deferred'), findsNothing);

    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(attempts, 2);
    expect(find.text('SCREEN'), findsOneWidget);
  });
}
