import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/stock_transfer_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';

class _MockRepo extends Mock implements StockTransferRepository {}

class _FakeStockTransferModel extends Fake implements StockTransferModel {}

final _destinationStores = [
  StoreModel(
    id: 'dst-001',
    name: 'Boutique B',
    createdAt: DateTime(2026, 1, 1),
    updatedAt: DateTime(2026, 1, 1),
  ),
  StoreModel(
    id: 'dst-002',
    name: 'Boutique C',
    createdAt: DateTime(2026, 1, 1),
    updatedAt: DateTime(2026, 1, 1),
  ),
];

Widget _wrap(Widget child, {List<Override> overrides = const []}) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp(
      home: Scaffold(body: child),
    ),
  );
}

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    registerFallbackValue(_FakeStockTransferModel());
  });

  group('TransferFormBottomSheet (Story 3.3 — Task 15)', () {
    late _MockRepo mockRepo;

    final fakeTransfer = StockTransferModel(
      id: 'tf-001',
      sourceStoreId: 'src-001',
      destinationStoreId: 'dst-001',
      productId: 'prod-001',
      quantity: 5,
      actorId: 'actor-001',
      occurredAt: DateTime(2026, 3, 14),
      status: 'COMPLETED',
    );

    setUp(() {
      mockRepo = _MockRepo();
    });

    Future<void> openSheet(WidgetTester tester) async {
      await tester.pumpWidget(_wrap(
        Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => showTransferFormBottomSheet(
              context: ctx,
              sourceStoreId: 'src-001',
              sourceStoreName: 'Boutique A',
              productId: 'prod-001',
              productName: 'Chaussures Test',
              destinationStores: _destinationStores,
            ),
            child: const Text('Ouvrir'),
          ),
        ),
        overrides: [
          stockTransferRepositoryProvider.overrideWithValue(mockRepo),
        ],
      ));
      await tester.tap(find.text('Ouvrir'));
      await tester.pumpAndSettle();
    }

    testWidgets('shows product name and source store in the form',
        (tester) async {
      await openSheet(tester);

      expect(find.textContaining('Chaussures Test'), findsOneWidget);
      expect(find.textContaining('Boutique A'), findsOneWidget);
    });

    testWidgets('shows quantity field and destination store dropdown',
        (tester) async {
      await openSheet(tester);

      expect(find.byKey(const Key('transfer_quantity_field')),
          findsOneWidget);
      expect(find.byKey(const Key('transfer_destination_dropdown')),
          findsOneWidget);
    });

    testWidgets('submit button is present', (tester) async {
      await openSheet(tester);

      expect(find.byKey(const Key('transfer_submit_button')), findsOneWidget);
    });
  });
}
