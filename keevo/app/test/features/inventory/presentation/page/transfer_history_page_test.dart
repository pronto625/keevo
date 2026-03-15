import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/presentation/page/transfer_history_page.dart';
import 'package:keevo/features/inventory/presentation/provider/stock_transfer_provider.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _FakeStoreListNotifier extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [];
}

class _MockRepo extends Mock implements StockTransferRepository {}

Widget _wrap(Widget child, {List<Override> overrides = const []}) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp(home: child),
  );
}

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  group('TransferHistoryPage (Story 3.3 — Task 16)', () {
    late _MockRepo mockRepo;

    setUp(() {
      mockRepo = _MockRepo();
    });

    final transfers = [
      StockTransferModel(
        id: 'tf-001',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
        actorId: 'actor-001',
        occurredAt: DateTime(2026, 3, 14),
        status: 'COMPLETED',
        sourceStoreName: 'Boutique A',
        destinationStoreName: 'Boutique B',
        productName: 'Chaussures Test',
      ),
      StockTransferModel(
        id: 'tf-002',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 3,
        actorId: 'actor-001',
        occurredAt: DateTime(2026, 3, 15),
        status: 'PENDING_SYNC',
        sourceStoreName: 'Boutique A',
        destinationStoreName: 'Boutique B',
        productName: 'Chaussures Test',
      ),
    ];

    // Common overrides: stub store/product providers to avoid real DB access.
    List<Override> buildOverrides() => [
          stockTransferRepositoryProvider.overrideWithValue(mockRepo),
          storeListNotifierProvider.overrideWith(_FakeStoreListNotifier.new),
          productListForPickerProvider
              .overrideWith((_) async => <ProductModel>[]),
        ];

    testWidgets('shows AppBar with title', (tester) async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => transfers);
      await tester.pumpWidget(
          _wrap(const TransferHistoryPage(), overrides: buildOverrides()));
      await tester.pumpAndSettle();

      expect(find.text('Transferts inter-boutiques'), findsOneWidget);
    });

    testWidgets('shows list of transfers when data loaded', (tester) async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => transfers);
      await tester.pumpWidget(
          _wrap(const TransferHistoryPage(), overrides: buildOverrides()));
      await tester.pumpAndSettle();

      expect(find.textContaining('Boutique A'), findsWidgets);
      expect(find.textContaining('Chaussures Test'), findsWidgets);
    });

    testWidgets('shows Effectué badge for completed transfers', (tester) async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => transfers);
      await tester.pumpWidget(
          _wrap(const TransferHistoryPage(), overrides: buildOverrides()));
      await tester.pumpAndSettle();

      // AC5 spec: shows 'Effectué' badge (not raw 'COMPLETED')
      expect(find.textContaining('Effectué'), findsWidgets);
    });

    testWidgets('shows En attente de sync badge for pending_sync transfers',
        (tester) async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => transfers);
      await tester.pumpWidget(
          _wrap(const TransferHistoryPage(), overrides: buildOverrides()));
      await tester.pumpAndSettle();

      // AC5 spec: shows 'En attente de sync' badge (not raw 'PENDING_SYNC')
      expect(find.textContaining('En attente'), findsWidgets);
    });

    testWidgets('shows empty message when no transfers', (tester) async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => []);
      await tester.pumpWidget(
          _wrap(const TransferHistoryPage(), overrides: buildOverrides()));
      await tester.pumpAndSettle();

      expect(find.textContaining('aucun transfert'), findsOneWidget);
    });
  });
}
