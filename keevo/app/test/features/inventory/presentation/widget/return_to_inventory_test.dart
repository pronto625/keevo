import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/inventory/presentation/widget/apply_adjustments_button.dart';

/// J1 — après « Appliquer les ajustements », la page « Inventaire » ne doit
/// pas devenir la racine de navigation : le retour Android ramène au menu
/// Plus au lieu de fermer l'application.
void main() {
  GoRouter buildRouter() => GoRouter(
        initialLocation: '/settings',
        routes: [
          GoRoute(
              path: '/settings',
              builder: (_, __) => const Scaffold(body: Text('PLUS'))),
          GoRoute(
              path: '/inventory',
              builder: (_, __) => const Scaffold(body: Text('INVENTAIRE'))),
          GoRoute(
              path: '/inventory/counting/:id',
              builder: (_, __) => const Scaffold(body: Text('COMPTAGE'))),
          GoRoute(
              path: '/inventory/gap-report/:id',
              builder: (_, __) => const Scaffold(body: Text('ECARTS'))),
        ],
      );

  Future<GoRouter> pump(WidgetTester tester) async {
    final router = buildRouter();
    addTearDown(router.dispose);
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();
    return router;
  }

  Future<bool> pressBack(WidgetTester tester) async {
    final handled = await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    return handled;
  }

  testWidgets('Plus > Inventaire > comptage > écarts: back after validation '
      'returns to Plus', (tester) async {
    final router = await pump(tester);
    router.push('/inventory');
    await tester.pumpAndSettle();
    router.push('/inventory/counting/s1');
    await tester.pumpAndSettle();
    router.push('/inventory/gap-report/s1');
    await tester.pumpAndSettle();
    expect(find.text('ECARTS'), findsOneWidget);

    returnToInventory(router);
    await tester.pumpAndSettle();
    expect(find.text('INVENTAIRE'), findsOneWidget);
    // Inventaire sits on top of Plus: its back arrow is shown (canPop).
    expect(router.canPop(), isTrue);
    expect(router.routerDelegate.currentConfiguration.matches, hasLength(2));

    expect(await pressBack(tester), isTrue);
    expect(find.text('PLUS'), findsOneWidget);
  });

  testWidgets('history report opened from Inventaire pops back to it',
      (tester) async {
    final router = await pump(tester);
    router.push('/inventory');
    await tester.pumpAndSettle();
    router.push('/inventory/gap-report/s1');
    await tester.pumpAndSettle();

    returnToInventory(router);
    await tester.pumpAndSettle();
    expect(find.text('INVENTAIRE'), findsOneWidget);
    expect(router.canPop(), isTrue);
  });

  testWidgets('gap report opened directly: Inventaire rebuilt over Plus',
      (tester) async {
    final router = await pump(tester);
    router.go('/inventory/gap-report/s1');
    await tester.pumpAndSettle();
    expect(router.canPop(), isFalse);

    returnToInventory(router);
    await tester.pumpAndSettle();
    expect(find.text('INVENTAIRE'), findsOneWidget);

    expect(await pressBack(tester), isTrue);
    expect(find.text('PLUS'), findsOneWidget);
  });

  // Story 15.8 — `/inventory` is the `counting` module's route: without it,
  // back to Plus, « Inventaire » not reopened.
  testWidgets('counting not readable: back to Plus, Inventaire not reopened',
      (tester) async {
    final router = await pump(tester);
    router.push('/inventory');
    await tester.pumpAndSettle();
    router.push('/inventory/gap-report/s1');
    await tester.pumpAndSettle();

    returnToInventory(router, inventoryAvailable: false);
    await tester.pumpAndSettle();
    expect(find.text('PLUS'), findsOneWidget);
    expect(find.text('INVENTAIRE'), findsNothing);
    expect(router.canPop(), isFalse);
  });
}
