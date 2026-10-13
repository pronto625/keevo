import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

import 'package:keevo/features/inventory/domain/model/inventory_scope.dart';
import 'package:keevo/features/inventory/domain/model/inventory_session_model.dart';
import 'package:keevo/features/inventory/presentation/widget/active_session_banner.dart';
import 'package:keevo/features/inventory/presentation/widget/session_history_card.dart';

import '../../../../helpers/no_technical_text.dart';

/// v1s-16-20 — inventory session widgets show French scope / status labels,
/// never the raw codes (`FULL`, `PARTIAL`, `SOME_STATUS`) or ids.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  const uuid = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
  InventorySessionModel session(String scope, String status) =>
      InventorySessionModel(
        id: uuid,
        storeId: uuid,
        scope: scope,
        status: status,
        startedBy: uuid,
        startedAt: DateTime(2026, 10, 12, 9),
        updatedAt: DateTime(2026, 10, 12, 9),
      );

  Widget host(Widget child) =>
      MaterialApp(home: Scaffold(body: SingleChildScrollView(child: child)));

  test('scope labels', () {
    expect(inventoryScopeLabel('FULL'), 'Complet');
    expect(inventoryScopeLabel('PARTIAL'), 'Partiel');
    expect(inventoryScopeLabel('SOME_SCOPE'), 'Some scope');
  });

  testWidgets('active session banner: « Complet », no code', (tester) async {
    await tester.pumpWidget(host(ActiveSessionBanner(
        session: session('FULL', 'IN_PROGRESS'),
        onResume: () {},
        onCancel: () {})));
    expect(find.text('Complet'), findsOneWidget);
    expectNoTechnicalText(tester);
  });

  testWidgets('history card: « Inventaire partiel », unknown status humanized',
      (tester) async {
    await tester.pumpWidget(host(Column(children: [
      SessionHistoryCard(
          session: session('PARTIAL', 'VALIDATED'), storeName: 'Boutique A'),
      SessionHistoryCard(session: session('FULL', 'SOME_STATUS')),
    ])));
    expect(find.text('Inventaire partiel'), findsOneWidget);
    expect(find.text('Inventaire complet'), findsOneWidget);
    expect(find.text('Some status'), findsOneWidget);
    expectNoTechnicalText(tester);
  });
}
