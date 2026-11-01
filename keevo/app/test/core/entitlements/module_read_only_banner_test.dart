import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/entitlements/module_read_only_banner.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart'
    show kTenantNameKey;
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../helpers/fake_entitlements.dart';

/// Story 16.1 — read-only banner on every screen of a module in grace.
void main() {
  // 2026-11-07 23:30 UTC = 08/11/2026 00:30 in Douala (UTC+1)
  final grace = DateTime.utc(2026, 11, 7, 23, 30);
  late SharedPreferences prefs;
  late List<String> launched;

  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({kTenantNameKey: 'Boutique Mama'});
    prefs = await SharedPreferences.getInstance();
    launched = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('plugins.flutter.io/url_launcher'),
            (call) async {
      if (call.method == 'launch') {
        launched.add((call.arguments as Map)['url'] as String);
        return true;
      }
      return false;
    });
  });

  GoRouter router() => GoRouter(initialLocation: '/invoicing', routes: [
        GoRoute(
            path: '/invoicing',
            builder: (_, __) => Scaffold(
                appBar: AppBar(title: const Text('Factures')),
                body: Builder(
                    builder: (context) => TextButton(
                        onPressed: () => context.push('/clients'),
                        child: const Text('ouvrir clients'))))),
        GoRoute(
            path: '/clients',
            builder: (_, __) =>
                Scaffold(appBar: AppBar(title: const Text('Clients')))),
      ]);

  Future<void> pump(WidgetTester tester,
      {required String role,
      Map<String, String> states = const {
        'invoicing': kModuleReadOnly,
        'crm': kModuleEnabled
      },
      DateTime? graceUntil}) async {
    final appRouter = router();
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue(role),
        currentUserPhoneProvider.overrideWithValue('+237690000009'),
        entitlementsOverride(states,
            displayNames: const {'invoicing': 'Facturation'},
            details: {
              'invoicing': ModuleDetails(
                  status: kSubscriptionActive,
                  category: 'premium',
                  graceUntil: graceUntil),
            }),
      ],
      child: MaterialApp.router(
        routerConfig: appRouter,
        builder: (context, child) =>
            ModuleReadOnlyLayout(router: appRouter, child: child!),
      ),
    ));
    await tester.pumpAndSettle();
  }

  test('text: Douala date dd/MM/yyyy whatever the device zone, no date when '
      'not sent', () {
    expect(readOnlyBannerText(null), 'Module en lecture seule');
    // 23:30 UTC is already the next day in Douala (UTC+1)
    expect(readOnlyBannerText(DateTime.utc(2026, 11, 7, 23, 30)),
        'Module en lecture seule jusqu\'au 08/11/2026');
    expect(readOnlyBannerText(DateTime.utc(2026, 11, 7, 22, 59)),
        'Module en lecture seule jusqu\'au 07/11/2026');
  });

  test('support text: neutral when the module name is unknown', () {
    final neutral = reactivationMessage(businessName: 'Boutique Mama');
    expect(neutral,
        'Bonjour, je souhaite réactiver un module en lecture seule pour mon '
        'commerce « Boutique Mama » sur Keevo. Merci de m\'indiquer la marche '
        'à suivre.');
    expect(reactivationMessage(moduleName: 'Facturation'),
        contains('réactiver le module « Facturation »'));
  });

  testWidgets('owner: banner with the grace date and « Réactiver » → support',
      (tester) async {
    await pump(tester, role: 'OWNER', graceUntil: grace);

    expect(find.text('Module en lecture seule jusqu\'au 08/11/2026'),
        findsOneWidget);
    expect(find.text(ModuleReadOnlyBanner.reactivateLabel), findsOneWidget);
    expect(find.text('Factures'), findsOneWidget); // the page stays below

    await tester.tap(find.text(ModuleReadOnlyBanner.reactivateLabel));
    await tester.pumpAndSettle();
    expect(launched, hasLength(1));
    final text = Uri.decodeFull(launched.single);
    expect(text, contains('réactiver le module « Facturation »'));
    expect(text, contains('Boutique Mama'));
    expect(text, isNot(contains('invoicing')));
  });

  testWidgets('employee: banner without the button', (tester) async {
    await pump(tester, role: 'EMPLOYEE');
    expect(find.text('Module en lecture seule'), findsOneWidget);
    expect(find.text(ModuleReadOnlyBanner.reactivateLabel), findsNothing);
  });

  testWidgets('ENABLED module: no banner', (tester) async {
    await pump(tester,
        role: 'OWNER',
        states: const {'invoicing': kModuleEnabled},
        graceUntil: grace);
    expect(find.byKey(const Key('module-read-only-banner')), findsNothing);
  });

  testWidgets('banner follows the route: gone on a screen of an enabled module',
      (tester) async {
    await pump(tester, role: 'OWNER', graceUntil: grace);
    expect(find.byKey(const Key('module-read-only-banner')), findsOneWidget);

    await tester.tap(find.text('ouvrir clients'));
    await tester.pumpAndSettle();
    expect(find.text('Clients'), findsOneWidget);
    expect(find.byKey(const Key('module-read-only-banner')), findsNothing);

    // back: the stack was kept and the banner is shown again
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('Factures'), findsOneWidget);
    expect(find.byKey(const Key('module-read-only-banner')), findsOneWidget);
  });
}
