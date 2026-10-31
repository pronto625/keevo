import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/router/module_routes.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../helpers/fake_entitlements.dart';

/// Story 15.8 — in-page gating helpers: nothing known yet → shown (backend
/// 403 fallback); READ_ONLY → readable but not writable; DISABLED → hidden.
void main() {
  Entitlements known(Map<String, String> states) =>
      Entitlements(states: states, status: EntitlementsStatus.server);

  group('pure rules', () {
    test('unknown → readable and writable', () {
      expect(isModuleReadable(Entitlements.unknown, 'crm'), isTrue);
      expect(isModuleWritable(Entitlements.unknown, 'crm'), isTrue);
    });
    test('ENABLED → readable and writable', () {
      final e = known({'crm': 'ENABLED'});
      expect(isModuleReadable(e, 'crm'), isTrue);
      expect(isModuleWritable(e, 'crm'), isTrue);
    });
    test('READ_ONLY → readable, not writable', () {
      final e = known({'crm': 'READ_ONLY'});
      expect(isModuleReadable(e, 'crm'), isTrue);
      expect(isModuleWritable(e, 'crm'), isFalse);
    });
    test('DISABLED or absent → neither', () {
      for (final e in [known({'crm': 'DISABLED'}), known({})]) {
        expect(isModuleReadable(e, 'crm'), isFalse);
        expect(isModuleWritable(e, 'crm'), isFalse);
      }
    });
  });

  group('WidgetRef helpers', () {
    Future<Map<String, bool>> probe(
        WidgetTester tester, Map<String, String>? states) async {
      late Map<String, bool> result;
      await tester.pumpWidget(ProviderScope(
        retry: noProviderRetry,
        overrides: [
          if (states != null)
            entitlementsOverride(states)
          else
            entitlementsProvider.overrideWith(() => FakeEntitlementsNotifier(
                () => Completer<Map<String, String>>().future)),
        ],
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            result = {
              'available': isModuleAvailable(ref, 'counting'),
              'availableNow': isModuleAvailableNow(ref, 'counting'),
              'active': isModuleActive(ref, 'counting'),
              'routeNow': isRouteAvailableNow(ref, '/inventory'),
              'route': isRouteAvailable(ref, '/inventory'),
            };
            return const SizedBox();
          }),
        ),
      ));
      await tester.pumpAndSettle();
      return result;
    }

    testWidgets('unknown (no answer yet) → all shown', (tester) async {
      expect((await probe(tester, null)).values, everyElement(isTrue));
    });

    testWidgets('ENABLED → all shown', (tester) async {
      expect((await probe(tester, {'counting': 'ENABLED'})).values,
          everyElement(isTrue));
    });

    testWidgets('READ_ONLY → shown, write action hidden', (tester) async {
      final r = await probe(tester, {'counting': 'READ_ONLY'});
      expect(r['available'], isTrue);
      expect(r['availableNow'], isTrue);
      expect(r['route'], isTrue);
      expect(r['routeNow'], isTrue);
      expect(r['active'], isFalse);
    });

    testWidgets('DISABLED → all hidden', (tester) async {
      expect((await probe(tester, {'counting': 'DISABLED'})).values,
          everyElement(isFalse));
    });
  });
}
