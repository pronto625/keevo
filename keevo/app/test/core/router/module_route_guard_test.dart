import 'dart:async';
import 'dart:ffi';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/core/router/deep_link_navigation.dart';
import 'package:keevo/core/router/module_routes.dart';
import 'package:keevo/core/router/module_unavailable_page.dart';
import 'package:keevo/core/router/session_presence_cache.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart'
    show flutterSecureStorageProvider;
import 'package:keevo/features/contact/presentation/page/client_list_page.dart';
import 'package:keevo/features/invoicing/domain/model/invoice.dart';
import 'package:keevo/features/invoicing/presentation/page/invoice_detail_page.dart';
import 'package:keevo/features/invoicing/presentation/provider/invoicing_providers.dart';
import 'package:keevo/features/pos/presentation/page/sale_success_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/widget/deferred_widget.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

import '../../helpers/fake_entitlements.dart';

class _FakeContext extends Fake implements BuildContext {}

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// Secure storage that cannot be read: the splash falls back to the login
/// form (no dashboard to build) — as in `splash_login_navigation_test.dart`.
class _BrokenStorage extends FlutterSecureStorage {
  const _BrokenStorage();

  @override
  Future<String?> read({
    required String key,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async =>
      throw StateError('keystore unreadable');
}

/// The router's session cache answers « signed in ».
class _SignedInCache extends SessionPresenceCache {
  _SignedInCache() : super(const _BrokenStorage());

  @override
  Future<bool> hasSession() async => true;
}

class _IdleSync extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async {}

  @override
  Future<void> onAppStartup() async {}
}

/// Story 15.2 — the module guard of appRouter: one test per I/O matrix row,
/// the deep link path and the « module non activé » screen.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Entitlements known(Map<String, String> states,
          {Map<String, String> names = const {}}) =>
      Entitlements(
          states: states,
          status: EntitlementsStatus.server,
          displayNames: names);

  String unavailable(String module) =>
      '$kModuleUnavailableRoute?module=$module';

  Future<String?> redirect(String path, Entitlements e,
          {String role = 'OWNER', bool session = true}) =>
      appRouteRedirect(
        path,
        hasSession: () async => session,
        readRole: () async => role,
        entitlements: () => e,
      );

  group('module guard (moduleRouteRedirect / appRouteRedirect)', () {
    test('inactive module, direct route → « module non activé »', () async {
      final e = known({'multi-store': 'DISABLED'});
      expect(await redirect('/stock/transfers', e), unavailable('multi-store'));
    });

    test('cached states gate too (offline restart)', () async {
      final e = known({'multi-store': 'DISABLED'})
          .withStatus(EntitlementsStatus.cached);
      expect(await redirect('/stock/transfers', e), unavailable('multi-store'));
    });

    test('inactive module, deep link with params → same redirect', () async {
      final e = known({'counting': 'DISABLED'});
      expect(await redirect('/inventory/counting/s-42', e),
          unavailable('counting'));
      expect(await redirect('/inventory/gap-report/s-42', e),
          unavailable('counting'));
    });

    test('active or grace module → page opens', () async {
      for (final state in const ['ENABLED', 'READ_ONLY']) {
        final e = known({'profitability': state});
        expect(await redirect('/reports/rentabilite', e), isNull,
            reason: state);
        expect(await redirect('/reports/rentabilite/p1', e), isNull,
            reason: state);
      }
    });

    test('unknown state → page opens (the backend decides)', () async {
      expect(await redirect('/exports', Entitlements.unknown), isNull);
      expect(moduleRouteRedirect('/stock/transfers', Entitlements.unknown),
          isNull);
    });

    test('module absent from known states is not readable', () async {
      expect(await redirect('/clients', known({'pos': 'ENABLED'})),
          unavailable('crm'));
    });

    test('employee on an owner-only route → role guard first (/pos)', () async {
      final e = known({'exports': 'DISABLED'});
      expect(await redirect('/exports', e, role: 'EMPLOYEE'), '/pos');
    });

    test('employee on a module route open to employees → module guard',
        () async {
      final e = known({'multi-store': 'DISABLED'});
      expect(await redirect('/stock/transfers', e, role: 'EMPLOYEE'),
          unavailable('multi-store'));
    });

    test('signed out → splash before any module check', () async {
      expect(await redirect('/stock/transfers', known({}), session: false),
          '/splash');
    });

    test('free routes are never gated', () async {
      final e = known({'exports': 'DISABLED', 'multi-store': 'DISABLED'});
      for (final path in const [
        '/exports/new',
        '/exports/local',
        '/stock/low',
        '/pos',
        '/reports',
        kModuleUnavailableRoute,
      ]) {
        expect(await redirect(path, e), isNull, reason: path);
      }
    });
  });

  test('shouldPingModuleGate: only known-ness or the readable set count', () {
    final a = known({'crm': 'ENABLED', 'exports': 'DISABLED'});
    expect(shouldPingModuleGate(a, a.withStatus(EntitlementsStatus.cached)),
        isFalse);
    expect(
        shouldPingModuleGate(
            a,
            Entitlements(
                states: a.states,
                status: EntitlementsStatus.server,
                fetchedAt: DateTime(2026))),
        isFalse);
    expect(shouldPingModuleGate(a, known({'crm': 'READ_ONLY'})), isFalse);
    expect(shouldPingModuleGate(a, known({'crm': 'DISABLED'})), isTrue);
    expect(
        shouldPingModuleGate(
            a, known({'crm': 'ENABLED', 'exports': 'ENABLED'})),
        isTrue);
    expect(shouldPingModuleGate(Entitlements.unknown, known({})), isTrue);
    expect(shouldPingModuleGate(null, Entitlements.unknown), isFalse);
  });

  test('appRouter mounts the « module non activé » screen', () {
    final match = appRouter.configuration
        .findMatch(Uri.parse('$kModuleUnavailableRoute?module=crm'));
    final route = match.matches.last.route as GoRoute;
    final state = appRouter.configuration.buildTopLevelGoRouterState(match);
    final page = route.builder!(_FakeContext(), state);
    expect(page, isA<ModuleUnavailablePage>());
    expect((page as ModuleUnavailablePage).moduleId, 'crm');
  });

  test('15.3: appRouter mounts /invoicing/:id (invoicing module, deferred)',
      () {
    expect(moduleOfPath('/invoicing/abc'), 'invoicing');
    final match = appRouter.configuration.findMatch(Uri.parse('/invoicing/abc'));
    final route = match.matches.last.route as GoRoute;
    expect(route.path, '/invoicing/:id');
    final state = appRouter.configuration.buildTopLevelGoRouterState(match);
    expect(route.builder!(_FakeContext(), state), isA<DeferredWidget>());
  });

  testWidgets('15.3: /invoicing/:id builds InvoiceDetailPage with its id',
      (tester) async {
    final match =
        appRouter.configuration.findMatch(Uri.parse('/invoicing/inv-7'));
    final route = match.matches.last.route as GoRoute;
    final state = appRouter.configuration.buildTopLevelGoRouterState(match);
    final pending = Completer<Invoice>();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        invoiceByIdProvider('inv-7').overrideWith((ref) => pending.future),
      ],
      child: MaterialApp(
        home: Builder(builder: (context) => route.builder!(context, state)),
      ),
    ));
    // loadLibrary completes outside the fake clock.
    for (var i = 0;
        i < 20 && find.byType(InvoiceDetailPage).evaluate().isEmpty;
        i++) {
      await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pump(const Duration(milliseconds: 20));
    }
    final page =
        tester.widget<InvoiceDetailPage>(find.byType(InvoiceDetailPage));
    expect(page.invoiceId, 'inv-7');
  });

  test('15.3: /pos/success passes the sale id to the success page', () {
    SaleSuccessPage build(String uri) {
      final match = appRouter.configuration.findMatch(Uri.parse(uri));
      final route = match.matches.last.route as GoRoute;
      final state = appRouter.configuration.buildTopLevelGoRouterState(match);
      return route.builder!(_FakeContext(), state) as SaleSuccessPage;
    }

    expect(build('/pos/success?total=10&status=COMPLETED&sale=s-1').saleId,
        's-1');
    expect(build('/pos/success?total=10&status=COMPLETED').saleId, isNull);
  });

  group('deep link + screen', () {
    late SharedPreferences prefs;

    Future<GoRouter> pumpApp(
      WidgetTester tester, {
      required Map<String, String> states,
      String role = 'OWNER',
    }) async {
      SharedPreferences.setMockInitialValues({kUserRoleKey: role});
      prefs = await SharedPreferences.getInstance();
      late ProviderContainer container;
      final gate = ModuleGateRefresh();
      addTearDown(gate.dispose);
      final router = GoRouter(
        initialLocation: '/home',
        refreshListenable: gate,
        redirect: (context, state) => appRouteRedirect(
          state.uri.path,
          hasSession: () async => true,
          readRole: () async => role,
          entitlements: () =>
              container.read(entitlementsProvider).valueOrNull ??
              Entitlements.unknown,
        ),
        routes: [
          GoRoute(
              path: '/home',
              builder: (_, __) => const Scaffold(body: Text('Accueil'))),
          GoRoute(
              path: '/dashboard',
              builder: (_, __) => const Scaffold(body: Text('Dashboard'))),
          GoRoute(
              path: '/pos',
              builder: (_, __) => const Scaffold(body: Text('Caisse'))),
          GoRoute(
              path: '/settings/subscription',
              builder: (_, __) => const Scaffold(body: Text('Abonnement'))),
          GoRoute(
              path: '/inventory/counting/:sessionId',
              builder: (_, __) => const Scaffold(body: Text('Comptage'))),
          GoRoute(
            path: kModuleUnavailableRoute,
            builder: (_, state) => ModuleUnavailablePage(
                moduleId: state.uri.queryParameters['module']),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          entitlementsOverride(states,
              displayNames: const {'counting': 'Inventaire assisté'}),
        ],
        child: Consumer(builder: (context, ref, _) {
          container = ProviderScope.containerOf(context);
          // Same wiring as KeevoApp: new module states → guard re-run.
          listenModuleGate(ref, refresh: gate);
          return MaterialApp.router(routerConfig: router);
        }),
      ));
      // Let the module states load (server answer of the fake).
      container.read(entitlementsProvider);
      await tester.pumpAndSettle();
      return router;
    }

    testWidgets(
        'owner: notification deep link of a disabled module → screen '
        'naming it in French, « Passer à Premium » opens the subscription',
        (tester) async {
      final router = await pumpApp(tester, states: {'counting': 'DISABLED'});

      openDeepLink(router, '/inventory/counting/s-42');
      await tester.pumpAndSettle();

      expect(find.byType(ModuleUnavailablePage), findsOneWidget);
      expect(find.text('Comptage'), findsNothing);
      expect(find.text(ModuleUnavailablePage.title), findsOneWidget);
      expect(
          find.text(
              'Le module « Inventaire assisté » n\'est pas activé pour votre '
              'commerce.'),
          findsOneWidget);
      expect(find.textContaining('counting'), findsNothing);
      expect(find.text(ModuleUnavailablePage.employeeMessage), findsNothing);

      await tester.tap(find.text('Passer à Premium'));
      await tester.pumpAndSettle();
      expect(find.text('Abonnement'), findsOneWidget);
    });

    testWidgets('employee: message without the upgrade action; home = /pos',
        (tester) async {
      final router = await pumpApp(tester,
          states: {'counting': 'DISABLED'}, role: 'EMPLOYEE');

      openDeepLink(router, '/inventory/counting/s-42');
      await tester.pumpAndSettle();

      expect(find.text(ModuleUnavailablePage.employeeMessage), findsOneWidget);
      expect(find.text('Passer à Premium'), findsNothing);

      await tester.tap(find.text(ModuleUnavailablePage.homeLabel));
      await tester.pumpAndSettle();
      expect(find.text('Caisse'), findsOneWidget);
    });

    testWidgets('active module: the deep link opens its screen',
        (tester) async {
      final router = await pumpApp(tester, states: {'counting': 'ENABLED'});

      openDeepLink(router, '/inventory/counting/s-42');
      await tester.pumpAndSettle();

      expect(find.text('Comptage'), findsOneWidget);
      expect(find.byType(ModuleUnavailablePage), findsNothing);
    });

    testWidgets('no display name known → generic French wording, never the id',
        (tester) async {
      final router = await pumpApp(tester, states: {'crm': 'DISABLED'});

      router.push('$kModuleUnavailableRoute?module=crm');
      await tester.pumpAndSettle();

      expect(find.text(ModuleUnavailablePage.fallbackMessage), findsOneWidget);
      expect(find.textContaining('crm'), findsNothing);
    });

    testWidgets(
        'state changes: re-enabled → the route opens without restart; '
        'disabled while open → the guard moves the user out', (tester) async {
      final states = {'counting': 'DISABLED'};
      final router = await pumpApp(tester, states: states);
      final container =
          ProviderScope.containerOf(tester.element(find.text('Accueil')));

      router.push('/inventory/counting/s-1');
      await tester.pumpAndSettle();
      expect(find.byType(ModuleUnavailablePage), findsOneWidget);
      router.pop();
      await tester.pumpAndSettle();

      states['counting'] = 'ENABLED';
      await container.read(entitlementsProvider.notifier).refresh();
      await tester.pumpAndSettle();
      router.go('/inventory/counting/s-1');
      await tester.pumpAndSettle();
      expect(find.text('Comptage'), findsOneWidget);

      states['counting'] = 'DISABLED';
      await container.read(entitlementsProvider.notifier).refresh();
      await tester.pumpAndSettle();
      expect(find.text('Comptage'), findsNothing);
      expect(find.byType(ModuleUnavailablePage), findsOneWidget);
    });
  });

  group('real appRouter (redirect + refresh wiring)', () {
    setUpAll(() {
      _overrideSqlite3ForLinuxTesting();
      GoogleFonts.config.allowRuntimeFetching = false;
    });

    Future<void> settle(WidgetTester tester) async {
      for (var i = 0; i < 20; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    String location() =>
        appRouter.routerDelegate.currentConfiguration.uri.toString();

    testWidgets(
        'owner session: a disabled module route lands on « module non '
        'activé »; a module disabled while its screen is open moves the user '
        'out', (tester) async {
      SharedPreferences.setMockInitialValues({kUserRoleKey: 'OWNER'});
      final prefs = await SharedPreferences.getInstance();
      final db = AppDatabase.forTesting();
      addTearDown(db.close);
      final states = {'counting': 'DISABLED', 'crm': 'ENABLED'};

      await tester.pumpWidget(ProviderScope(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          appDatabaseProvider.overrideWithValue(db),
          flutterSecureStorageProvider
              .overrideWithValue(const _BrokenStorage()),
          sessionPresenceCacheProvider.overrideWithValue(_SignedInCache()),
          syncTriggerNotifierProvider.overrideWith(_IdleSync.new),
          entitlementsProvider
              .overrideWith(() => FakeEntitlementsNotifier(() async => states)),
        ],
        child: Consumer(builder: (context, ref, _) {
          listenModuleGate(ref); // KeevoApp's wiring
          return MaterialApp.router(routerConfig: appRouter);
        }),
      ));
      await settle(tester);

      appRouter.go('/inventory/counting/x');
      await settle(tester);
      expect(location(), '$kModuleUnavailableRoute?module=counting');
      expect(find.byType(ModuleUnavailablePage), findsOneWidget);

      appRouter.go('/clients');
      await settle(tester);
      expect(location(), '/clients');
      expect(find.byType(ClientListPage), findsOneWidget);

      states['crm'] = 'DISABLED';
      final container = ProviderScope.containerOf(
          tester.element(find.byType(ClientListPage)));
      await container.read(entitlementsProvider.notifier).refresh();
      await settle(tester);
      expect(location(), '$kModuleUnavailableRoute?module=crm');
      expect(find.byType(ClientListPage), findsNothing);

      await tester.pumpWidget(const SizedBox());
    });
  });
}
