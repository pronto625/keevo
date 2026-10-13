import 'dart:async';

import 'package:dio/dio.dart';
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
  Object? errorToThrow; // simulates a rethrown server refusal

  @override
  FutureOr<void> build() {}

  @override
  Future<QuickAddResult?> quickAdd({
    required String sessionId,
    required String name,
    required String categoryId,
    required int physicalQty,
    int? sellingPrice,
  }) async {
    state = const AsyncLoading();
    if (errorToThrow != null) {
      state = const AsyncData(null);
      throw errorToThrow!;
    }
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

bool _isSelfDrawnHandle(Widget w) =>
    w is Container &&
    (w.constraints?.maxHeight == 4) &&
    (w.constraints?.maxWidth == 36 || w.constraints?.maxWidth == 40);

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  group('QuickAddProductSheet (Story 6.2.a — Task 19)', () {
    late _FakeQuickAddNotifier fakeNotifier;

    setUp(() {
      fakeNotifier = _FakeQuickAddNotifier();
    });

    // Value returned by showQuickAddProductSheet once the sheet closes.
    Object? sheetReturn = 'pending';

    Future<void> openSheet(WidgetTester tester,
        {List<Override> extraOverrides = const []}) async {
      sheetReturn = 'pending';
      await tester.pumpWidget(_wrap(
        Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              sheetReturn = await showQuickAddProductSheet(
                context: ctx,
                sessionId: 'session-001',
              );
            },
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

    testWidgets('relies on the theme drag handle (no second one)',
        (tester) async {
      await openSheet(tester);

      expect(find.byWidgetPredicate(_isSelfDrawnHandle), findsNothing);
    });

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

    // ── v1s-14-13: FREE plan product limit ───────────────────────────────

    Future<void> fillAndSubmit(WidgetTester tester) async {
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
      await tester.tap(find.text('Créer le produit'));
      await tester.pumpAndSettle();
    }

    DioException refusal(int status, Map<String, dynamic> body) {
      final options = RequestOptions(path: '/inventory/sessions/s/quick-add');
      return DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        message: 'This exception was thrown because the response has a '
            'status code of $status',
        response: Response(
            requestOptions: options, statusCode: status, data: body),
      );
    }

    testWidgets(
        'v1s-14-13 — 403 PLAN_LIMIT_EXCEEDED opens the French plan sheet, '
        'no code, no snackbar, no result', (tester) async {
      fakeNotifier.errorToThrow = refusal(403, {
        'error': 'Limite de produits atteinte',
        'code': 403,
        'domainCode': 'PLAN_LIMIT_EXCEEDED',
        'details': {'entity': 'products', 'limit': 500, 'current': 500},
      });

      await openSheet(tester);
      await fillAndSubmit(tester);

      expect(
          find.text('Vous avez atteint la limite de 500 produits '
              'sur votre plan gratuit'),
          findsOneWidget);
      expect(find.textContaining('PLAN_LIMIT_EXCEEDED'), findsNothing);
      expect(find.byType(SnackBar), findsNothing);

      // Dismiss the plan sheet, then the quick-add sheet: no result passed.
      await tester.tap(find.text('Plus tard'));
      await tester.pumpAndSettle();
      expect(find.text('Nouveau produit'), findsOneWidget);
      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();
      expect(sheetReturn, isNull);
    });

    testWidgets(
        'v1s-14-13 — other 4xx shows a French message, never « Erreur réseau »',
        (tester) async {
      fakeNotifier.errorToThrow = refusal(404, {
        // Real backend text carries the session UUID: never shown.
        'error': 'Session d\'inventaire introuvable : '
            '3f2b9c1e-8a4d-4f6b-9c2e-1a2b3c4d5e6f',
        'code': 404,
        'domainCode': 'INVENTORY_SESSION_NOT_FOUND',
      });

      await openSheet(tester);
      await fillAndSubmit(tester);

      expect(find.byType(SnackBar), findsOneWidget);
      expect(
          find.text('Une erreur inattendue est survenue. Réessayez plus tard.'),
          findsOneWidget);
      expect(find.textContaining('3f2b9c1e'), findsNothing);
      expect(find.textContaining('Erreur réseau'), findsNothing);
      expect(find.textContaining('status code'), findsNothing);
      expect(find.textContaining('limite'), findsNothing);
    });
  });
}
