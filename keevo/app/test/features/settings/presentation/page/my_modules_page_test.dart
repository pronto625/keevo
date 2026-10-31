import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart'
    show kTenantNameKey;
import 'package:keevo/features/settings/presentation/page/my_modules_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/utils/support_contact.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';
import '../../../../helpers/no_technical_text.dart';

// Story 15.6 — « Mes modules » (owner): one test per I/O matrix row.

final _trialEnd = DateTime.utc(2026, 11, 5, 10);
final _graceEnd = DateTime.utc(2026, 11, 7, 10);

const _states = {
  'catalog': 'ENABLED',
  'crm': 'DISABLED',
  'invoicing': 'ENABLED',
  'multi-store': 'READ_ONLY',
  'profitability': 'DISABLED',
  'reporting': 'DISABLED',
};

const _names = {
  'catalog': 'Catalogue',
  'crm': 'Clients et fournisseurs',
  'invoicing': 'Facturation',
  'multi-store': 'Multi-boutiques et transferts',
  'profitability': 'Rentabilité',
  'reporting': 'Rapports',
};

final _details = {
  'catalog': const ModuleDetails(free: true, category: 'business'),
  'crm': const ModuleDetails(free: true, category: 'business',
      status: kSubscriptionDisabled),
  'invoicing': ModuleDetails(
      status: kSubscriptionTrial, category: 'premium', expiresAt: _trialEnd),
  'multi-store': ModuleDetails(
      status: kSubscriptionActive,
      category: 'premium',
      expiresAt: DateTime.utc(2026, 10, 31, 10),
      graceUntil: _graceEnd),
  'profitability': const ModuleDetails(category: 'premium'),
  'reporting': const ModuleDetails(
      status: kSubscriptionExpired, category: 'premium'),
};

void main() {
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

  /// [tall]: the whole list is built at once (the pull-to-refresh arming
  /// distance grows with the viewport, so that test keeps the default size).
  Future<void> pump(WidgetTester tester, FakeEntitlementsNotifier fake,
      {bool tall = true, bool settle = true}) async {
    if (tall) {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserPhoneProvider.overrideWithValue('+237690000009'),
        entitlementsProvider.overrideWith(() => fake),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(path: '/', builder: (_, __) => const MyModulesPage()),
        ]),
      ),
    ));
    if (settle) await tester.pumpAndSettle();
  }

  FakeEntitlementsNotifier online() => FakeEntitlementsNotifier(
      () async => _states,
      displayNames: _names,
      details: _details);

  group('moduleRow (statuses in French)', () {
    test('free module → « Inclus », no action', () {
      final r = moduleRow('Catalogue', kModuleEnabled,
          const ModuleDetails(free: true, category: 'business'));
      expect(r.section, ModuleSection.included);
      expect(r.status, 'Inclus');
      expect(r.action, isNull);
    });

    test('core module is included even without the free flag', () {
      final r = moduleRow(
          'Socle', kModuleEnabled, const ModuleDetails(category: 'core'));
      expect(r.section, ModuleSection.included);
    });

    test('free module switched off → reactivation through support', () {
      final r = moduleRow('Clients', kModuleDisabled,
          const ModuleDetails(free: true, status: kSubscriptionDisabled));
      expect(r.section, ModuleSection.included);
      expect(r.status, 'Non activé');
      expect(r.action, ModuleAction.reactivate);
    });

    test('trial → « Essai jusqu\'au … », no action', () {
      final r = moduleRow('Facturation', kModuleEnabled,
          ModuleDetails(status: kSubscriptionTrial, expiresAt: _trialEnd));
      expect(r.section, ModuleSection.paid);
      expect(r.status, 'Essai jusqu\'au 05 nov. 2026');
      expect(r.action, isNull);
    });

    test('active with an end → « Actif jusqu\'au … »; without → « Actif »',
        () {
      expect(
          moduleRow('X', kModuleEnabled,
                  ModuleDetails(status: kSubscriptionActive, expiresAt: _trialEnd))
              .status,
          'Actif jusqu\'au 05 nov. 2026');
      expect(
          moduleRow('X', kModuleEnabled,
                  const ModuleDetails(status: kSubscriptionActive))
              .status,
          'Actif');
    });

    test('grace → « Expiré — lecture seule jusqu\'au … » + activation', () {
      final r = moduleRow('Multi', kModuleReadOnly,
          ModuleDetails(status: kSubscriptionActive, graceUntil: _graceEnd));
      expect(r.section, ModuleSection.paid);
      expect(r.status, 'Expiré — lecture seule jusqu\'au 07 nov. 2026');
      expect(r.action, ModuleAction.requestActivation);
    });

    test('expired subscription → « Expiré » in the paid group', () {
      final r = moduleRow('Rapports', kModuleDisabled,
          const ModuleDetails(status: kSubscriptionExpired));
      expect(r.section, ModuleSection.paid);
      expect(r.status, 'Expiré');
      expect(r.action, ModuleAction.requestActivation);
    });

    test('paid module switched off → « Désactivé » + activation', () {
      for (final status in [
        kSubscriptionDisabled,
        kSubscriptionTrial,
        kSubscriptionActive,
      ]) {
        final r = moduleRow(
            'Rapports', kModuleDisabled, ModuleDetails(status: status));
        expect(r.section, ModuleSection.paid, reason: status);
        expect(r.status, 'Désactivé', reason: status);
        expect(r.action, ModuleAction.requestActivation, reason: status);
      }
    });

    test('included module in READ_ONLY → « Lecture seule » + reactivation',
        () {
      final r = moduleRow('Catalogue', kModuleReadOnly,
          const ModuleDetails(free: true, category: 'business'));
      expect(r.section, ModuleSection.included);
      expect(r.status, 'Lecture seule');
      expect(r.action, ModuleAction.reactivate);
    });

    test('no details (2.0.15 snapshot) → « Informations à confirmer », no '
        'action, no guessed group', () {
      for (final state in [kModuleEnabled, kModuleDisabled, kModuleReadOnly]) {
        final r = moduleRow('Catalogue', state, null);
        expect(r.section, ModuleSection.unconfirmed, reason: state);
        expect(r.status, MyModulesPage.unconfirmedStatus, reason: state);
        expect(r.action, isNull, reason: state);
      }
    });

    test('unknown state → neutral row, no action', () {
      final r = moduleRow(
          'Rentabilité', 'SUSPENDED', const ModuleDetails(category: 'premium'));
      expect(r.section, ModuleSection.unconfirmed);
      expect(r.status, MyModulesPage.unconfirmedStatus);
      expect(r.action, isNull);
      expect(r.active, isFalse);
    });

    test('refreshFailed: unchanged answer, unconfirmed or missing states', () {
      final at = DateTime.utc(2026, 10, 30, 8);
      Entitlements e(EntitlementsStatus s, DateTime? f) =>
          Entitlements(states: const {}, status: s, fetchedAt: f);
      expect(refreshFailed(at, e(EntitlementsStatus.server, at)), isTrue);
      expect(refreshFailed(at, e(EntitlementsStatus.cached, at)), isTrue);
      expect(refreshFailed(at, null), isTrue);
      expect(
          refreshFailed(
              at, e(EntitlementsStatus.server, at.add(const Duration(seconds: 1)))),
          isFalse);
      expect(refreshFailed(null, e(EntitlementsStatus.server, at)), isFalse);
    });

    test('never subscribed → « Disponibles », « Non activé » + activation',
        () {
      for (final details in [
        const ModuleDetails(category: 'premium'),
        const ModuleDetails(status: kSubscriptionAvailable),
      ]) {
        final r = moduleRow('Rentabilité', kModuleDisabled, details);
        expect(r.section, ModuleSection.available);
        expect(r.status, 'Non activé');
        expect(r.action, ModuleAction.requestActivation);
      }
    });

    test('dates are in Africa/Douala (UTC+1)', () {
      expect(formatModuleDate(DateTime.utc(2026, 11, 5, 23, 30)),
          '06 nov. 2026');
    });

    test('a module without a French name is never listed (no ids)', () {
      final groups = groupModules(Entitlements(
        states: const {'catalog': 'ENABLED', 'mystery': 'ENABLED'},
        status: EntitlementsStatus.server,
        displayNames: const {'catalog': 'Catalogue'},
      ));
      final all = [for (final s in ModuleSection.values) ...groups.of(s)];
      expect(all.map((r) => r.name), ['Catalogue']);
    });
  });

  test('support message names the business and the module', () {
    expect(
        supportMessage(moduleName: 'Facturation', businessName: 'Chez Ali'),
        contains('activer le module « Facturation » pour mon commerce '
            '« Chez Ali »'));
    expect(
        supportMessage(moduleName: 'Clients', reactivate: true),
        contains('réactiver le module « Clients » pour mon commerce sur'));
    expect(
        supportMessage(
            moduleName: 'Facturation',
            businessName: 'Chez Ali',
            ownerPhone: '+237690000009'),
        contains('« Chez Ali » (téléphone +237690000009) sur Keevo'));
    expect(supportWhatsAppUri('Bonjour', number: '00237 690 00 00 01').toString(),
        'https://wa.me/237690000001?text=Bonjour');
    expect(supportWhatsAppUri('Bonjour', number: '+237 690-00-00-01').toString(),
        'https://wa.me/237690000001?text=Bonjour');
    expect(supportWhatsAppUri('Bonjour', number: '').toString(),
        'https://wa.me/?text=Bonjour');
  });

  testWidgets(
      'owner: included, paid and available groups with French statuses and '
      'dates, no technical text', (tester) async {
    await pump(tester, online());

    expect(find.text(MyModulesPage.title), findsOneWidget);
    expect(find.text('INCLUS'), findsOneWidget);
    expect(find.text('MES MODULES PAYANTS'), findsOneWidget);
    expect(find.text('DISPONIBLES'), findsOneWidget);

    expect(find.text('Catalogue'), findsOneWidget);
    expect(find.text('Inclus'), findsOneWidget);
    expect(find.text('Essai jusqu\'au 05 nov. 2026'), findsOneWidget);
    expect(find.text('Expiré — lecture seule jusqu\'au 07 nov. 2026'),
        findsOneWidget);
    expect(find.text('Expiré'), findsOneWidget);
    // profitability (never subscribed) + crm (free, switched off).
    expect(find.text('Non activé'), findsNWidgets(2));

    // Paid not active: multi-store (grace), reporting (expired),
    // profitability (available). Trial and free modules: none.
    expect(find.text(MyModulesPage.requestActivationLabel), findsNWidgets(3));
    expect(find.text(MyModulesPage.reactivateLabel), findsOneWidget);
    expect(find.byKey(const Key('my-modules-offline')), findsNothing);

    expectNoTechnicalText(tester);
    for (final id in _states.keys) {
      expect(find.textContaining(id), findsNothing, reason: id);
    }
    for (final code in ['TRIAL', 'ACTIVE', 'EXPIRED', 'READ_ONLY', 'ENABLED']) {
      expect(find.textContaining(code), findsNothing, reason: code);
    }
  });

  testWidgets('« Demander l\'activation » opens WhatsApp to support naming '
      'the business and the module', (tester) async {
    await pump(tester, online());

    final card = find.ancestor(
        of: find.text('Rentabilité'), matching: find.byType(Container));
    final button = find.descendant(
        of: card.first,
        matching: find.text(MyModulesPage.requestActivationLabel));
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(launched, hasLength(1));
    expect(launched.single, startsWith('https://wa.me/'));
    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, contains('« Rentabilité »'));
    expect(text, contains('« Boutique Mama » (téléphone +237690000009)'));
    expect(text, contains('activer'));
  });

  testWidgets('free module off: « Contactez le support pour le réactiver »',
      (tester) async {
    await pump(tester, online());

    await tester.tap(find.text(MyModulesPage.reactivateLabel));
    await tester.pumpAndSettle();

    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, contains('réactiver le module « Clients et fournisseurs »'));
  });

  testWidgets('offline: last known states with a « Hors ligne » note',
      (tester) async {
    final fake = FakeEntitlementsNotifier(
      () async => throw Exception('hors ligne'),
      seed: Entitlements(
        states: _states,
        status: EntitlementsStatus.cached,
        fetchedAt: DateTime.utc(2026, 10, 30, 8),
        displayNames: _names,
        details: _details,
      ),
    );
    await pump(tester, fake);

    expect(find.byKey(const Key('my-modules-offline')), findsOneWidget);
    expect(find.text('Hors ligne — états connus au 30 oct. 2026'),
        findsOneWidget);
    expect(find.text('Essai jusqu\'au 05 nov. 2026'), findsOneWidget);
    expect(find.text('Catalogue'), findsOneWidget);
  });

  testWidgets('pull-to-refresh asks the server again', (tester) async {
    final fake = online();
    await pump(tester, fake, tall: false);
    final before = fake.fetchCalls;

    await tester.fling(find.byType(ListView), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    expect(fake.fetchCalls, before + 1);
  });

  testWidgets('failed pull-to-refresh shows the « Hors ligne » note',
      (tester) async {
    var offline = false;
    final fake = FakeEntitlementsNotifier(() async {
      if (offline) throw Exception('hors ligne');
      return _states;
    }, displayNames: _names, details: _details);
    await pump(tester, fake, tall: false);
    expect(find.byKey(const Key('my-modules-offline')), findsNothing);

    offline = true;
    await tester.fling(find.byType(ListView), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('my-modules-offline')), findsOneWidget);
    expect(find.text('Catalogue'), findsOneWidget);
  });

  testWidgets('claim seed without names: spinner while the server is asked, '
      'never the « impossible » message', (tester) async {
    final answer = Completer<Map<String, String>>();
    final fake = FakeEntitlementsNotifier(() => answer.future,
        seed: Entitlements.fromClaim(const ['invoicing']),
        displayNames: _names,
        details: _details);
    await pump(tester, fake, settle: false);
    await tester.pump();
    await tester.pump();

    expect(find.byKey(const Key('my-modules-loading')), findsOneWidget);
    expect(find.byKey(const Key('my-modules-empty')), findsNothing);

    answer.complete(_states);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('my-modules-loading')), findsNothing);
    expect(find.text('Facturation'), findsOneWidget);
  });

  testWidgets('2.0.15 snapshot offline: « Informations à confirmer », no CTA',
      (tester) async {
    final fake = FakeEntitlementsNotifier(
      () async => throw Exception('hors ligne'),
      seed: Entitlements(
        states: const {'catalog': 'ENABLED', 'profitability': 'DISABLED'},
        status: EntitlementsStatus.cached,
        displayNames: _names,
      ),
    );
    await pump(tester, fake);

    expect(find.text('À CONFIRMER'), findsOneWidget);
    expect(find.text(MyModulesPage.unconfirmedStatus), findsNWidgets(2));
    expect(find.text('INCLUS'), findsNothing);
    expect(find.text('MES MODULES PAYANTS'), findsNothing);
    expect(find.text(MyModulesPage.requestActivationLabel), findsNothing);
    expect(fake.fetchCalls, greaterThan(0), reason: 'confirmed with the server');
  });

  testWidgets('load error with no states: French error + « Réessayer »',
      (tester) async {
    final fake = _FailingSeed();
    await pump(tester, fake);

    expect(find.text(MyModulesPage.errorTitle), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);

    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(_FailingSeed.loads, greaterThan(1));
  });

  testWidgets('nothing known (first launch offline): French empty message',
      (tester) async {
    await pump(
        tester,
        FakeEntitlementsNotifier(() async => throw Exception('hors ligne')));

    expect(find.byKey(const Key('my-modules-empty')), findsOneWidget);
    expect(find.text(MyModulesPage.offlineFallback), findsOneWidget);
  });
}

/// Its seed cannot be read: the provider is an `AsyncError` with no value.
class _FailingSeed extends FakeEntitlementsNotifier {
  static int loads = 0;
  _FailingSeed() : super(() async => const {});

  @override
  Future<Entitlements> loadSeed() async {
    loads++;
    throw StateError('stockage illisible');
  }
}
