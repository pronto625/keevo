import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/features/inventory/presentation/provider/validate_inventory_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/apply_adjustments_button.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../../../helpers/fake_entitlements.dart';

class _FakeValidate extends ValidateInventoryNotifier {
  @override
  Future<int> validate(String sessionId) async => 3;

  @override
  void invalidateAll() {}
}

void main() {
  final summary = const InventoryGapSummaryModel(
    totalCounted: 4,
    totalConcordant: 1,
    totalSurplus: 1,
    totalShortage: 2,
    totalSurplusValueXaf: 3000,
    totalShortageValueXaf: 25000,
  );

  Widget buildButton() {
    return ProviderScope(
      retry: noProviderRetry,
      overrides: [
        currentUserRoleProvider.overrideWithValue('OWNER'),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: ApplyAdjustmentsButton(
            sessionId: 's1',
            summary: summary,
          ),
        ),
      ),
    );
  }

  testWidgets('shows button with correct label', (tester) async {
    await tester.pumpWidget(buildButton());
    await tester.pump();

    expect(find.text('Appliquer les ajustements'), findsOneWidget);
    expect(find.byIcon(Icons.check_circle_outline), findsOneWidget);
  });

  testWidgets('shows confirmation dialog on tap', (tester) async {
    await tester.pumpWidget(buildButton());
    await tester.pump();

    await tester.tap(find.text('Appliquer les ajustements'));
    await tester.pumpAndSettle();

    expect(find.text('Confirmer les ajustements'), findsOneWidget);
    expect(find.textContaining('irréversible'), findsOneWidget);
    expect(find.text('Annuler'), findsOneWidget);
    expect(find.text('Confirmer'), findsOneWidget);
  });

  testWidgets('dismiss dialog on Annuler tap', (tester) async {
    await tester.pumpWidget(buildButton());
    await tester.pump();

    await tester.tap(find.text('Appliquer les ajustements'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();

    // Dialog dismissed — no confirmation title
    expect(find.text('Confirmer les ajustements'), findsNothing);
    // Button still enabled
    expect(find.text('Appliquer les ajustements'), findsOneWidget);
  });

  testWidgets('button has correct min height', (tester) async {
    await tester.pumpWidget(buildButton());
    await tester.pump();

    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.style?.minimumSize?.resolve({}),
        const Size.fromHeight(48));
  });

  // Story 15.8 — `/inventory` is the `counting` module's route: after a
  // validation without the module, back to Plus, « Inventaire » not reopened.
  testWidgets('counting DISABLED: validation ends on Plus, no Inventaire',
      (tester) async {
    final router = GoRouter(
      initialLocation: '/settings',
      routes: [
        GoRoute(
            path: '/settings',
            builder: (_, __) => const Scaffold(body: Text('PLUS'))),
        GoRoute(
            path: '/inventory',
            builder: (_, __) => const Scaffold(body: Text('INVENTAIRE'))),
        GoRoute(
          path: '/inventory/gap-report/:id',
          builder: (_, __) => Scaffold(
            body: ApplyAdjustmentsButton(sessionId: 's1', summary: summary),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        currentUserRoleProvider.overrideWithValue('OWNER'),
        validateInventoryNotifierProvider.overrideWith(_FakeValidate.new),
        entitlementsOverride({'counting': 'DISABLED'}),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    // The app keeps the module states loaded (watched app-wide); here the
    // provider is read once so the server answer is in when validating.
    ProviderScope.containerOf(tester.element(find.text('PLUS')))
        .read(entitlementsProvider);
    await tester.pumpAndSettle();
    router.push('/inventory');
    await tester.pumpAndSettle();
    router.push('/inventory/gap-report/s1');
    await tester.pumpAndSettle();

    await tester.tap(find.text('Appliquer les ajustements'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirmer'));
    // The button's spinner never settles: fixed steps until the success
    // overlay (1.5 s) closes and the navigation runs.
    for (var i = 0; i < 25; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();

    expect(find.text('PLUS'), findsOneWidget);
    expect(find.text('INVENTAIRE'), findsNothing);
    expect(router.canPop(), isFalse);
  });
}
