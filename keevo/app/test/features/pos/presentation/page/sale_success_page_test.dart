import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/extension/extension_actions.dart';
import 'package:keevo/core/extension/extension_slot.dart';
import 'package:keevo/features/pos/presentation/page/sale_success_page.dart';

import '../../../../helpers/fake_entitlements.dart';

const _facturer = ExtensionContribution(
  id: 'invoicing.invoice-sale',
  label: 'Facturer',
  order: 100,
  action: kOpenSaleInvoiceAction,
);

void main() {
  late int fetches;
  late List<Map<String, Object?>> runs;

  setUp(() {
    fetches = 0;
    runs = [];
  });

  Widget app(SaleSuccessPage page,
      {Future<List<ExtensionContribution>> Function()? slot}) {
    return ProviderScope(
      overrides: [
        entitlementsOverride({'invoicing': 'ENABLED'}),
        extensionContributionsFetcherProvider.overrideWithValue((_) {
          fetches++;
          return (slot ?? () async => const [])();
        }),
        extensionActionsProvider.overrideWithValue({
          kOpenSaleInvoiceAction: (context, ref, args) async => runs.add(args),
        }),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(path: '/', builder: (_, __) => page),
          GoRoute(path: '/pos', builder: (_, __) => const Text('POS')),
        ]),
      ),
    );
  }

  group('SaleSuccessPage — P1 (v1s-12-10)', () {
    testWidgets('employee draft sale: recorded sale + drafts-to-validate note',
        (tester) async {
      await tester.pumpWidget(app(const SaleSuccessPage(
        totalAmount: 30000,
        hasDraftsToValidate: true,
      )));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.text('Vente enregistrée !'), findsOneWidget);
      expect(find.text('Vente en attente'), findsNothing);
      expect(find.text('Produit(s) brouillon à valider par le propriétaire'),
          findsOneWidget);

      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('plain sale: no draft note', (tester) async {
      await tester.pumpWidget(app(const SaleSuccessPage(totalAmount: 1000)));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.text('Vente enregistrée !'), findsOneWidget);
      expect(find.textContaining('brouillon'), findsNothing);

      await tester.pumpAndSettle(const Duration(seconds: 2));
    });
  });

  group('SaleSuccessPage — post-sale slot (15.3)', () {
    testWidgets(
        'invoicing active: « Facturer » shown, auto-return cancelled, '
        '« Nouvelle vente » returns to the POS', (tester) async {
      await tester.pumpWidget(app(
          const SaleSuccessPage(totalAmount: 1000, saleId: 's-1'),
          slot: () async => [_facturer]));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('Facturer'), findsOneWidget);
      expect(find.text('Nouvelle vente'), findsOneWidget);

      await tester.pump(const Duration(seconds: 6));
      await tester.pumpAndSettle();
      expect(find.text('POS'), findsNothing);

      await tester.tap(find.text('Facturer'));
      await tester.pump();
      expect(runs, [
        {'saleId': 's-1'}
      ]);

      await tester.tap(find.text('Nouvelle vente'));
      await tester.pumpAndSettle();
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('invoicing inactive (empty slot): nothing shown, auto-return',
        (tester) async {
      await tester.pumpWidget(
          app(const SaleSuccessPage(totalAmount: 1000, saleId: 's-1')));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('Facturer'), findsNothing);
      expect(find.text('Nouvelle vente'), findsNothing);

      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('slot failure (offline): nothing shown, auto-return',
        (tester) async {
      await tester.pumpWidget(app(
          const SaleSuccessPage(totalAmount: 1000, saleId: 's-1'),
          slot: () async => throw Exception('offline')));
      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('Facturer'), findsNothing);
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('slot still loading at the deadline: waits for it',
        (tester) async {
      final answer = Completer<List<ExtensionContribution>>();
      await tester.pumpWidget(app(
          const SaleSuccessPage(totalAmount: 1000, saleId: 's-1'),
          slot: () => answer.future));
      await tester.pump(const Duration(seconds: 2));
      answer.complete([_facturer]);
      await tester.pump();
      await tester.pump(const Duration(seconds: 4));
      expect(find.text('Facturer'), findsOneWidget);
      expect(find.text('POS'), findsNothing);
    });

    testWidgets('pending sale or unknown sale id: no slot, no request',
        (tester) async {
      await tester.pumpWidget(app(
          const SaleSuccessPage(
              totalAmount: 1000,
              saleId: 's-1',
              saleStatus: 'PENDING_VALIDATION'),
          slot: () async => [_facturer]));
      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('Facturer'), findsNothing);
      expect(find.text('POS'), findsOneWidget);

      await tester.pumpWidget(app(const SaleSuccessPage(totalAmount: 1000),
          slot: () async => [_facturer]));
      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(fetches, 0);
    });

    testWidgets('unknown action only: treated as no action (auto-return)',
        (tester) async {
      await tester.pumpWidget(
          app(const SaleSuccessPage(totalAmount: 1000, saleId: 's-1'),
              slot: () async => [
                    const ExtensionContribution(
                        id: 'x',
                        label: 'Autre',
                        order: 1,
                        action: 'other.thing')
                  ]));
      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('Autre'), findsNothing);
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('slot never answers → lands on the POS after the deadline',
        (tester) async {
      final never = Completer<List<ExtensionContribution>>();
      await tester.pumpWidget(app(
          const SaleSuccessPage(totalAmount: 1000, saleId: 's-1'),
          slot: () => never.future));
      await tester.pump(SaleSuccessPage.autoReturnDelay);
      await tester.pump(SaleSuccessPage.slotWait);
      await tester.pumpAndSettle();
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets(
        'actions vanish after the return was cancelled → « Nouvelle vente » '
        'stays', (tester) async {
      var states = {'invoicing': 'ENABLED'};
      var answer = <ExtensionContribution>[_facturer];
      final fake = FakeEntitlementsNotifier(() async => states);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          entitlementsProvider.overrideWith(() => fake),
          extensionContributionsFetcherProvider
              .overrideWithValue((_) async => answer),
          extensionActionsProvider.overrideWithValue({
            kOpenSaleInvoiceAction: (context, ref, args) async {},
          }),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(routes: [
            GoRoute(
                path: '/',
                builder: (_, __) =>
                    const SaleSuccessPage(totalAmount: 1000, saleId: 's-1')),
            GoRoute(path: '/pos', builder: (_, __) => const Text('POS')),
          ]),
        ),
      ));
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.text('Facturer'), findsOneWidget);

      // Invoicing disabled meanwhile: the slot empties.
      states = {'invoicing': 'DISABLED'};
      answer = [];
      await fake.refresh();
      await tester.pumpAndSettle();
      expect(find.text('Facturer'), findsNothing);
      expect(find.text('Nouvelle vente'), findsOneWidget);

      await tester.tap(find.text('Nouvelle vente'));
      await tester.pumpAndSettle();
      expect(find.text('POS'), findsOneWidget);
    });
  });
}
