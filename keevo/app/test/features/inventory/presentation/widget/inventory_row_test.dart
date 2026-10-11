import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/inventory_product_row_model.dart';
import 'package:keevo/features/inventory/domain/repository/inventory_count_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/inventory_counting_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/inventory_row.dart';

class _MockCountRepo extends Mock implements InventoryCountRepository {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets(
      'clearing an already-saved count does not make the row look uncounted',
      (tester) async {
    final repo = _MockCountRepo();
    await tester.pumpWidget(ProviderScope(
      overrides: [inventoryCountRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(
        home: Scaffold(
          body: InventoryRow(
            sessionId: 'sess-1',
            product: InventoryProductRowModel(
              productId: 'p1',
              productName: 'Riz 5kg',
              theoreticalQty: 10,
              physicalQty: 7,
              ecart: -3,
            ),
          ),
        ),
      ),
    ));

    await tester.enterText(find.byType(TextFormField), '');
    await tester.pump(const Duration(milliseconds: 500));
    // Still shows the saved gap while the field is empty…
    expect(find.text('-3'), findsOneWidget);

    // …and the saved count comes back when the user leaves the field.
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    expect(find.widgetWithText(TextFormField, '7'), findsOneWidget);

    verifyNever(() => repo.saveCount(
          sessionId: any(named: 'sessionId'),
          productId: any(named: 'productId'),
          variantId: any(named: 'variantId'),
          productName: any(named: 'productName'),
          variantLabel: any(named: 'variantLabel'),
          theoretical: any(named: 'theoretical'),
          physical: any(named: 'physical'),
        ));
  });
}
