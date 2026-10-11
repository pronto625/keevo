import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/stock_transfer_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

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

StoreModel _store(String id, String name) => StoreModel(
      id: id,
      name: name,
      createdAt: DateTime(2026, 1, 1),
      updatedAt: DateTime(2026, 1, 1),
    );

final _allStores = [
  _store('store-a', 'Boutique A'),
  _store('store-b', 'Boutique B'),
  _store('store-c', 'Boutique C'),
];

class _AllStores extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => _allStores;
}

class _ActiveStore extends ActiveStoreNotifier {
  _ActiveStore(this._id);
  final String? _id;
  @override
  String? build() => _id;
}

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

  // ── Story v1s-12-11 — self-load mode (history FAB): source scope ──────────
  group('TransferFormBottomSheet self-load source scope (v1s-12-11)', () {
    Future<void> openSelfLoad(WidgetTester tester,
        {required String role, String? activeStoreId}) async {
      await tester.pumpWidget(_wrap(
        Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => showTransferFormBottomSheet(
              context: ctx,
              sourceStoreId: '',
              sourceStoreName: '',
              productId: '',
              productName: '',
              destinationStores: const [],
            ),
            child: const Text('Ouvrir'),
          ),
        ),
        overrides: [
          stockTransferRepositoryProvider.overrideWithValue(_MockRepo()),
          storeListNotifierProvider.overrideWith(_AllStores.new),
          productListForPickerProvider.overrideWith((_) async => []),
          currentUserRoleProvider.overrideWithValue(role),
          activeStoreIdProvider.overrideWith(() => _ActiveStore(activeStoreId)),
        ],
      ));
      await tester.tap(find.text('Ouvrir'));
      await tester.pumpAndSettle();
    }

    DropdownButton<StoreModel> sourceDropdown(WidgetTester tester) =>
        tester.widget<DropdownButton<StoreModel>>(find.descendant(
          of: find.byKey(const Key('transfer_source_dropdown')),
          matching: find.byType(DropdownButton<StoreModel>),
        ));

    testWidgets('EMPLOYEE: source locked to their own store', (tester) async {
      await openSelfLoad(tester, role: 'EMPLOYEE', activeStoreId: 'store-b');

      final dd = sourceDropdown(tester);
      expect(dd.items!.map((i) => i.value!.id), ['store-b']);
      expect(dd.value?.id, 'store-b');
      expect(dd.onChanged, isNull); // disabled
    });

    testWidgets('EMPLOYEE without matching store: no source option',
        (tester) async {
      await openSelfLoad(tester, role: 'EMPLOYEE', activeStoreId: 'unknown');

      final dd = sourceDropdown(tester);
      expect(dd.items, isEmpty);
      expect(dd.value, isNull);
      expect(find.text('Votre boutique est introuvable'), findsOneWidget);
    });

    testWidgets('OWNER: all stores selectable as source', (tester) async {
      await openSelfLoad(tester, role: 'OWNER', activeStoreId: 'store-b');

      final dd = sourceDropdown(tester);
      expect(dd.items!.map((i) => i.value!.id),
          ['store-a', 'store-b', 'store-c']);
      expect(dd.value, isNull);
      expect(dd.onChanged, isNotNull);
    });
  });
}
