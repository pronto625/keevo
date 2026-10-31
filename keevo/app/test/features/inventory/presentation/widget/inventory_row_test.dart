import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/inventory_count_model.dart';
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
      retry: noProviderRetry,
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

  // Story 15.4b — the row only reads the auto-dispose save notifier (no
  // listener): under Riverpod 3 the save past the 400 ms debounce must still
  // record the count and refresh the counting list.
  testWidgets('a typed count is saved after the debounce and the list reloads',
      (tester) async {
    final repo = _MockCountRepo();
    when(() => repo.saveCount(
          sessionId: any(named: 'sessionId'),
          productId: any(named: 'productId'),
          variantId: any(named: 'variantId'),
          productName: any(named: 'productName'),
          variantLabel: any(named: 'variantLabel'),
          theoretical: any(named: 'theoretical'),
          physical: any(named: 'physical'),
        )).thenAnswer((_) => Future.delayed(
        const Duration(milliseconds: 100),
        () => InventoryCountModel(
              id: 'cnt-1',
              sessionId: 'sess-1',
              productId: 'p1',
              productName: 'Riz 5kg',
              theoretical: 10,
              physical: 8,
              updatedAt: DateTime(2026, 1, 1),
            )));
    var listBuilds = 0;
    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      inventoryCountRepositoryProvider.overrideWithValue(repo),
      countingProductsProvider('sess-1').overrideWith((ref) async {
        listBuilds++;
        return const <InventoryProductRowModel>[];
      }),
    ]);
    addTearDown(container.dispose);
    // The counting page keeps the list on screen.
    final list =
        container.listen(countingProductsProvider('sess-1'), (_, __) {});
    addTearDown(list.close);
    await container.read(countingProductsProvider('sess-1').future);
    expect(listBuilds, 1);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        home: Scaffold(
          body: InventoryRow(
            sessionId: 'sess-1',
            product: InventoryProductRowModel(
              productId: 'p1',
              productName: 'Riz 5kg',
              theoreticalQty: 10,
              ecart: null,
            ),
          ),
        ),
      ),
    ));

    await tester.enterText(find.byType(TextFormField), '8');
    await tester.pump(const Duration(milliseconds: 300));
    verifyNever(() => repo.saveCount(
          sessionId: any(named: 'sessionId'),
          productId: any(named: 'productId'),
          variantId: any(named: 'variantId'),
          productName: any(named: 'productName'),
          variantLabel: any(named: 'variantLabel'),
          theoretical: any(named: 'theoretical'),
          physical: any(named: 'physical'),
        ));
    await tester.pump(const Duration(milliseconds: 200)); // past 400 ms
    await tester.pump(const Duration(milliseconds: 150)); // the save answers
    await tester.pump();

    verify(() => repo.saveCount(
          sessionId: 'sess-1',
          productId: 'p1',
          variantId: null,
          productName: 'Riz 5kg',
          variantLabel: null,
          theoretical: 10,
          physical: 8,
        )).called(1);
    expect(listBuilds, 2, reason: 'counting list refreshed after the save');
    expect(tester.takeException(), isNull);
  });
}
