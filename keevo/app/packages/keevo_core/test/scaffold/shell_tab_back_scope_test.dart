import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/scaffold/shell_tab_back_scope.dart';

/// J2 — retour Android sur un onglet autre que le premier : retour au premier
/// onglet, jamais de sortie de l'application.
void main() {
  const tabs = ['/dashboard', '/pos', '/products', '/reports', '/settings'];

  GoRouter buildRouter(String initial) => GoRouter(
        initialLocation: initial,
        routes: [
          ShellRoute(
            builder: (context, state, child) {
              final loc = state.uri.path;
              var index = 0;
              for (var i = tabs.length - 1; i >= 0; i--) {
                if (loc.startsWith(tabs[i])) {
                  index = i;
                  break;
                }
              }
              return ShellTabBackScope(
                tabIndex: index,
                onGoToFirstTab: () => context.go(tabs.first),
                child: Scaffold(body: child),
              );
            },
            routes: [
              for (final t in tabs)
                GoRoute(path: t, builder: (_, __) => Text('TAB $t')),
            ],
          ),
          GoRoute(
            path: '/inventory',
            builder: (_, __) => const Scaffold(body: Text('INVENTAIRE')),
          ),
        ],
      );

  Future<GoRouter> pump(WidgetTester tester, String initial) async {
    final router = buildRouter(initial);
    addTearDown(router.dispose);
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();
    return router;
  }

  /// Simule le bouton retour Android ; renvoie `false` si l'app se fermerait.
  Future<bool> pressBack(WidgetTester tester) async {
    final handled = await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    return handled;
  }

  for (final tab in tabs.skip(1)) {
    testWidgets('back on $tab goes to the first tab, app stays open',
        (tester) async {
      await pump(tester, tab);
      expect(find.text('TAB $tab'), findsOneWidget);

      expect(await pressBack(tester), isTrue);
      expect(find.text('TAB /dashboard'), findsOneWidget);
    });
  }

  testWidgets('back on the first tab keeps the system behaviour (exit)',
      (tester) async {
    await pump(tester, '/dashboard');
    expect(await pressBack(tester), isFalse);
  });

  testWidgets('back on a page pushed over a non-first tab pops to that tab',
      (tester) async {
    final router = await pump(tester, '/settings');
    router.push('/inventory');
    await tester.pumpAndSettle();
    expect(find.text('INVENTAIRE'), findsOneWidget);

    expect(await pressBack(tester), isTrue);
    expect(find.text('TAB /settings'), findsOneWidget);
  });
}
