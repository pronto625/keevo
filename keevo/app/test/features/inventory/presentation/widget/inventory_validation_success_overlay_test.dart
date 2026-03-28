import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/presentation/widget/inventory_validation_success_overlay.dart';

void main() {
  testWidgets('shows checkmark icon and text', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: InventoryValidationSuccessOverlay(
            adjustmentsApplied: 3,
            onComplete: () {},
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.byIcon(Icons.check), findsOneWidget);
    expect(find.text('Inventaire validé'), findsOneWidget);
    expect(find.text('3 ajustements appliqués'), findsOneWidget);
  });

  testWidgets('singular text for 1 adjustment', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: InventoryValidationSuccessOverlay(
            adjustmentsApplied: 1,
            onComplete: () {},
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('1 ajustement appliqué'), findsOneWidget);
  });
}
