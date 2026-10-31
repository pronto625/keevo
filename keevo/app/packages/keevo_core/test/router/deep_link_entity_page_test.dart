import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo_core/router/deep_link_entity_page.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// N3 — `/products/{id}/edit`, `/clients/:id`, `/suppliers/:id`,
/// `/settings/team/:id/edit` opened without `extra` (notification) load the
/// entity by id and open its form; absent → list + French snackbar.
void main() {
  Widget app(Future<String?> Function() load) => ProviderScope(
        retry: noProviderRetry,
        child: MaterialApp(
          home: DeepLinkEntityPage<String>(
            load: (_) => load(),
            formBuilder: (name) => Scaffold(body: Text('FORM $name')),
            fallback: const Scaffold(body: Text('LISTE')),
            notFoundMessage: 'Ce client n\'est pas disponible sur cet appareil.',
          ),
        ),
      );

  testWidgets('entity found locally → its form', (tester) async {
    await tester.pumpWidget(app(() async => 'Awa'));
    await tester.pumpAndSettle();

    expect(find.text('FORM Awa'), findsOneWidget);
    expect(find.text('LISTE'), findsNothing);
  });

  testWidgets('entity absent → list with a French snackbar', (tester) async {
    await tester.pumpWidget(app(() async => null));
    await tester.pumpAndSettle();

    expect(find.text('LISTE'), findsOneWidget);
    expect(find.text('Ce client n\'est pas disponible sur cet appareil.'),
        findsOneWidget);
  });

  testWidgets('local read failure behaves as absent (no red screen)',
      (tester) async {
    await tester.pumpWidget(app(() async => throw StateError('db closed')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('LISTE'), findsOneWidget);
  });
}
