import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/presentation/page/inventory_gap_report_page.dart';
import 'package:keevo/features/inventory/presentation/provider/gap_report_provider.dart';

final _report = InventoryGapReportModel(
  sessionId: 's1',
  storeId: 'st1',
  storeName: 'Boutique Test',
  scope: 'FULL',
  summary: const InventoryGapSummaryModel(
    totalCounted: 4,
    totalConcordant: 1,
    totalSurplus: 1,
    totalShortage: 2,
    totalSurplusValueXaf: 3000,
    totalShortageValueXaf: 25000,
  ),
  concordantRows: [
    InventoryGapRowModel(
      productId: 'p1',
      productName: 'Concordant',
      theoretical: 10,
      physical: 10,
      ecart: 0,
      unitPriceXaf: 500,
      gapValueXaf: 0,
    ),
  ],
  surplusRows: [
    InventoryGapRowModel(
      productId: 'p2',
      productName: 'Surplus Item',
      theoretical: 5,
      physical: 8,
      ecart: 3,
      unitPriceXaf: 1000,
      gapValueXaf: 3000,
    ),
  ],
  shortageRows: [
    InventoryGapRowModel(
      productId: 'p3',
      productName: 'Manquant A',
      theoretical: 10,
      physical: 5,
      ecart: -5,
      unitPriceXaf: 3000,
      gapValueXaf: 15000,
    ),
    InventoryGapRowModel(
      productId: 'p4',
      productName: 'Manquant B',
      theoretical: 20,
      physical: 15,
      ecart: -5,
      unitPriceXaf: 2000,
      gapValueXaf: 10000,
    ),
  ],
  generatedAt: DateTime(2025, 1, 15, 14, 30),
);

Widget _buildPage({String role = 'OWNER'}) {
  return ProviderScope(
    overrides: [
      gapReportProvider('s1').overrideWith((_) async => _report),
      currentUserRoleProvider.overrideWithValue(role),
    ],
    child: const MaterialApp(
      home: InventoryGapReportPage(sessionId: 's1'),
    ),
  );
}

void main() {
  testWidgets('renders summary + sections when data loaded', (tester) async {
    await tester.pumpWidget(_buildPage());
    await tester.pumpAndSettle();

    // Summary
    expect(find.text('Résumé'), findsOneWidget);
    expect(find.text('4 comptés'), findsOneWidget);
    expect(find.text('1 concordants'), findsOneWidget);

    // Sections
    expect(find.text('Concordants : 1 produits'), findsOneWidget);
    expect(find.textContaining('Manquants'), findsWidgets);
    expect(find.textContaining('Surplus'), findsWidgets);
  });

  testWidgets('WhatsApp button visible for both OWNER and EMPLOYEE',
      (tester) async {
    await tester.pumpWidget(_buildPage(role: 'EMPLOYEE'));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.share), findsOneWidget);
  });

  testWidgets('download button visible only for OWNER', (tester) async {
    // OWNER — download visible
    await tester.pumpWidget(_buildPage(role: 'OWNER'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.download), findsOneWidget);

    // EMPLOYEE — download hidden
    await tester.pumpWidget(_buildPage(role: 'EMPLOYEE'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.download), findsNothing);
  });

  testWidgets('Appliquer button disabled with placeholder text',
      (tester) async {
    await tester.pumpWidget(_buildPage());
    await tester.pumpAndSettle();

    // Scroll to the bottom to reveal the button
    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(find.text('Appliquer les ajustements'), findsOneWidget);
    expect(
        find.text('Disponible dans une prochaine mise à jour'), findsOneWidget);

    // Button should be disabled (onPressed == null on FilledButton)
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
  });
}
