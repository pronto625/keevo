import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/core/router/deep_link_navigation.dart';
import 'package:keevo_core/entitlements/entitlements.dart';

import '../../helpers/fake_entitlements.dart';

/// J16 — tapping a notification whose deep link is a bottom-nav tab
/// (« /products ») pushed a shell route over « Notifications » → duplicate
/// page key assertion (red screen). [openDeepLink] is the single path used by
/// the notifications page and the FCM tap handlers.
void main() {
  /// Every deep link the backend writes in a notification payload
  /// (grep `NotificationPayload.of(` in keevo/backend), ids filled in, plus
  /// the legacy ones still stored on devices.
  const uuid = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
  const backendDeepLinks = [
    '/products', // CSV import, batched employee product creations
    '/products/$uuid/edit', // stock alert, employee product created / stock
    '/stock/overview', // stock alert batch, employee stock batch
    '/stock/transfers', // transfer created
    '/reports/history', // day closure, sales trend
    '/pos/pending/$uuid', // sale pending validation
    '/pos/sales-history/$uuid', // sale validated / cancelled
    '/exports/$uuid', // export ready / failed
  ];
  const legacyDeepLinks = [
    '/products/$uuid', // EMPLOYEE_STOCK_MODIFIED before J16
    '/stock/history', // EMPLOYEE_STOCK_MODIFIED_BATCH before J16
  ];

  group('every notification deep link resolves to an app route', () {
    for (final link in [...backendDeepLinks, ...legacyDeepLinks]) {
      test(link, () {
        final target = normalizeDeepLink(link);
        final match = appRouter.configuration.findMatch(Uri.parse(target));
        expect(match.isError, isFalse, reason: '$link → $target');
        // The go/push choice agrees with the router: shell tabs only.
        final inShell = match.matches.first is ShellRouteMatch;
        expect(isShellTabLocation(target), inShell, reason: target);
      });
    }
  });

  test('legacy links are mapped to existing screens', () {
    expect(normalizeDeepLink('/products/$uuid'), '/products/$uuid/edit');
    expect(normalizeDeepLink('/stock/history'), '/stock/overview');
    expect(normalizeDeepLink('/products/new'), '/products/new');
    expect(normalizeDeepLink('/products/import'), '/products/import');
  });

  group('15.2: stock overview links without multi-store', () {
    Entitlements known(String state) => Entitlements(
        states: {'multi-store': state}, status: EntitlementsStatus.server);

    test('multi-store DISABLED → the free local « stock bas » list', () {
      for (final link in const [
        '/stock/history',
        '/stock/overview',
        '/stock/overview?showLowOnly=true',
      ]) {
        expect(normalizeDeepLink(link, entitlements: known('DISABLED')),
            '/stock/low?all=true',
            reason: link);
      }
      final match =
          appRouter.configuration.findMatch(Uri.parse('/stock/low?all=true'));
      expect(match.isError, isFalse);
    });

    test('readable or unknown → the overview (backend decides)', () {
      for (final e in [
        known('ENABLED'),
        known('READ_ONLY'),
        Entitlements.unknown,
      ]) {
        expect(normalizeDeepLink('/stock/history', entitlements: e),
            '/stock/overview',
            reason: '$e');
      }
      expect(
          normalizeDeepLink('/stock/transfers',
              entitlements: known('DISABLED')),
          '/stock/transfers');
    });

    testWidgets('openDeepLink reads the states from the router scope',
        (tester) async {
      final router = GoRouter(
        initialLocation: '/home',
        routes: [
          GoRoute(path: '/home', builder: (_, __) => const Text('HOME')),
          GoRoute(
              path: '/stock/overview',
              builder: (_, __) => const Text('OVERVIEW')),
          GoRoute(
              path: '/stock/low',
              builder: (_, state) =>
                  Text('LOCAL all=${state.uri.queryParameters['all']}')),
        ],
      );
      addTearDown(router.dispose);
      late ProviderContainer container;
      await tester.pumpWidget(ProviderScope(
        overrides: [
          entitlementsOverride({'multi-store': 'DISABLED'})
        ],
        child: Consumer(builder: (context, ref, _) {
          container = ProviderScope.containerOf(context);
          return MaterialApp.router(routerConfig: router);
        }),
      ));
      await container.read(entitlementsProvider.future);
      await tester.pumpAndSettle();

      openDeepLink(router, '/stock/history');
      await tester.pumpAndSettle();
      expect(find.text('LOCAL all=true'), findsOneWidget);
      expect(find.text('OVERVIEW'), findsNothing);
    });
  });

  test('shell tab detection ignores query strings and trailing slash', () {
    expect(isShellTabLocation('/reports?tab=jour'), isTrue);
    expect(isShellTabLocation('/products/'), isTrue);
    expect(isShellTabLocation('/reports/history'), isFalse);
    expect(isShellTabLocation('/pos/pending/x'), isFalse);
  });

  group('from a page pushed over the shell (« Notifications »)', () {
    GoRouter buildRouter() => GoRouter(
          initialLocation: '/dashboard',
          routes: [
            ShellRoute(
              builder: (_, __, child) => Scaffold(body: child),
              routes: [
                for (final t in shellTabLocations)
                  GoRoute(path: t, builder: (_, __) => Text('TAB $t')),
              ],
            ),
            GoRoute(
              path: '/notifications',
              builder: (_, __) => const Scaffold(body: Text('NOTIFS')),
            ),
            GoRoute(
              path: '/reports/history',
              builder: (_, __) => const Scaffold(body: Text('HISTORIQUE')),
            ),
          ],
        );

    Future<GoRouter> openNotifications(WidgetTester tester) async {
      final router = buildRouter();
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pumpAndSettle();
      router.push('/notifications');
      await tester.pumpAndSettle();
      expect(find.text('NOTIFS'), findsOneWidget);
      return router;
    }

    testWidgets('openDeepLink: a shell tab is opened with go, no assertion',
        (tester) async {
      final router = await openNotifications(tester);

      openDeepLink(router, '/products');
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('TAB /products'), findsOneWidget);
    });

    testWidgets('openDeepLink: any other screen is pushed, BACK returns',
        (tester) async {
      final router = await openNotifications(tester);

      openDeepLink(router, '/reports/history');
      await tester.pumpAndSettle();
      expect(find.text('HISTORIQUE'), findsOneWidget);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('NOTIFS'), findsOneWidget);
    });
    // Last: the broken tree of the reproduction must not leak into the
    // other tests.
    testWidgets('reproduction: push of a shell tab asserts (duplicate key)',
        (tester) async {
      final router = await openNotifications(tester);

      // The broken tree keeps reporting follow-up errors: collect them all,
      // then unmount before restoring the handler.
      final errors = <FlutterErrorDetails>[];
      final previous = FlutterError.onError;
      FlutterError.onError = errors.add;
      try {
        router.push('/products');
        await tester.pump();
        await tester.pump();
        await tester.pumpWidget(const SizedBox());
      } finally {
        FlutterError.onError = previous;
      }
      tester.takeException();

      expect(
          errors.any((e) => e.exception.toString().contains('keyReservation')),
          isTrue,
          reason: errors.map((e) => e.exception).join('\n'));
    });
  });
}
