import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

import 'package:keevo/features/catalog/domain/model/category_model.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/inventory/data/datasource/remote_quick_add_datasource.dart';
import 'package:keevo/features/inventory/presentation/provider/quick_add_product_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/quick_add_product_sheet.dart';

/// Fake notifier that allows controlling quickAdd results in widget tests.
class _FakeQuickAddNotifier extends QuickAddProductNotifier {
  QuickAddResult? resultToReturn;
  bool shouldReturnNull = false; // simulates dedup

  @override
  FutureOr<void> build() {}

  @override
  Future<QuickAddResult?> quickAdd({
    required String sessionId,
    required String name,
    required String categoryId,
    required int physicalQty,
  }) async {
    state = const AsyncLoading();
    if (shouldReturnNull) {
      state = const AsyncData(null);
      return null;
    }
    state = const AsyncData(null);
    return resultToReturn;
  }
}

final _categories = [
  CategoryModel(
    id: 'cat-001',
    name: 'Vêtements',
    parentId: null,
    isActive: true,
    isCustom: false,
    createdAt: DateTime(2026, 1, 1),
    updatedAt: DateTime(2026, 1, 1),
  ),
  CategoryModel(
    id: 'cat-002',
    name: 'Chaussures',
    parentId: null,
    isActive: true,
    isCustom: false,
    createdAt: DateTime(2026, 1, 1),
    updatedAt: DateTime(2026, 1, 1),
  ),
];

final _fakeResult = QuickAddResult(
  productId: 'prod-new',
  productName: 'Robe Wax L',
  sku: 'KEV-ABC123',
  categoryId: 'cat-001',
  physicalQty: 3,
  inventoryCountId: 'cnt-001',
  stockLevelId: 'stk-001',
);

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
  });

  group('QuickAddProductSheet (Story 6.2.a — Task 19)', () {
    late _FakeQuickAddNotifier fakeNotifier;

    setUp(() {
      fakeNotifier = _FakeQuickAddNotifier();
    });

    Future<void> openSheet(WidgetTester tester,
        {List<Override> extraOverrides = const []}) async {
      await tester.pumpWidget(_wrap(
        Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => showQuickAddProductSheet(
              context: ctx,
              sessionId: 'session-001',
            ),
            child: const Text('Ouvrir'),
          ),
        ),
        overrides: [
          categoriesProvider.overrideWith((_) async => _categories),
          quickAddProductNotifierProvider
              .overrideWith(() => fakeNotifier),
          ...extraOverrides,
        ],
      ));
      await tester.tap(find.text('Ouvrir'));
      await tester.pumpAndSettle();
    }

    testWidgets('renders title and 3 form fields', (tester) async {
      await openSheet(tester);

      expect(find.text('Nouveau produit'), findsOneWidget);
      expect(find.text('Nom du produit'), findsOneWidget);
      expect(find.text('Catégorie'), findsOneWidget);
      expect(find.text('Quantité physique'), findsOneWidget);
    });

    testWidgets('shows validation errors when fields are empty',
        (tester) async {
      await openSheet(tester);

      // Tap Créer le produit without filling fields
      await tester.tap(find.text('Créer le produit'));
      await tester.pumpAndSettle();

      expect(find.text('Le nom est requis'), findsOneWidget);
      expect(find.text('La catégorie est requise'), findsOneWidget);
    });

    testWidgets('cancel button dismisses the sheet', (tester) async {
      await openSheet(tester);

      expect(find.text('Nouveau produit'), findsOneWidget);

      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();

      expect(find.text('Nouveau produit'), findsNothing);
    });

    testWidgets('category dropdown is populated from provider',
        (tester) async {
      await openSheet(tester);

      // Open the dropdown by tapping the dropdown field area
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();

      expect(find.text('Vêtements'), findsWidgets);
      expect(find.text('Chaussures'), findsWidgets);
    });

    testWidgets(
        'submit calls provider and closes sheet on success',
        (tester) async {
      fakeNotifier.resultToReturn = _fakeResult;

      await openSheet(tester);

      // Fill name
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Nom du produit'), 'Robe Wax L');
      await tester.pump();

      // Select category
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Vêtements').last);
      await tester.pumpAndSettle();

      // Fill quantity
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Quantité physique'), '3');
      await tester.pump();

      // Submit
      await tester.tap(find.text('Créer le produit'));
      await tester.pumpAndSettle();

      // Sheet should be dismissed (title gone)
      expect(find.text('Nouveau produit'), findsNothing);
    });

    testWidgets('shows dedup dialog on 409 response', (tester) async {
      fakeNotifier.shouldReturnNull = true; // simulate dedup (provider returns null)

      await openSheet(tester);

      // Fill all fields
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Nom du produit'), 'Robe Wax L');
      await tester.pump();

      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Vêtements').last);
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Quantité physique'), '3');
      await tester.pump();

      // Submit
      await tester.tap(find.text('Créer le produit'));
      await tester.pumpAndSettle();

      // Dedup dialog should appear
      expect(find.text('Produit existant'), findsOneWidget);
      expect(find.text("Compter l'existant"), findsOneWidget);
    });
  });
}
