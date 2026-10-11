import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/inventory_product_row_model.dart';
import 'package:keevo/features/inventory/domain/repository/inventory_count_repository.dart';
import 'package:keevo/features/inventory/presentation/page/inventory_counting_page.dart';
import 'package:keevo/features/inventory/presentation/provider/inventory_counting_provider.dart';

class _MockCountRepo extends Mock implements InventoryCountRepository {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('completion banner fits a 360dp screen', (tester) async {
    tester.view.physicalSize = const Size(360, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final repo = _MockCountRepo();
    when(() => repo.getCountingProducts('sess-1')).thenAnswer((_) async => [
          const InventoryProductRowModel(
            productId: 'p1',
            productName: 'Riz',
            theoreticalQty: 10,
            physicalQty: 10,
            ecart: 0,
          ),
          const InventoryProductRowModel(
            productId: 'p2',
            productName: 'Huile',
            theoreticalQty: 10,
            physicalQty: 8,
            ecart: -2,
          ),
        ]);

    await tester.pumpWidget(ProviderScope(
      overrides: [inventoryCountRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: InventoryCountingPage(sessionId: 'sess-1')),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('Inventaire terminé'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
