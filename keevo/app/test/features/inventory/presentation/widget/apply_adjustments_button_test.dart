import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo/features/inventory/presentation/widget/apply_adjustments_button.dart';

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
}
