import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/category_model.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/repository/product_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/catalog/presentation/widget/create_draft_product_bottom_sheet.dart';

class _MockProductRepository extends Mock implements ProductRepository {}

final _category = CategoryModel(
  id: 'c1',
  name: 'Épicerie',
  isActive: true,
  isCustom: false,
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

Future<void> _openSheet(WidgetTester tester, ProductRepository repo) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      categoriesProvider.overrideWith((_) async => [_category]),
      productRepositoryProvider.overrideWithValue(repo),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showModalBottomSheet<DraftCreationResult>(
              context: context,
              isScrollControlled: true,
              builder: (_) =>
                  const CreateDraftProductBottomSheet(prefillName: 'Savon'),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

Future<void> _fill(WidgetTester tester, {required String qty}) async {
  await tester.enterText(
      find.widgetWithText(TextFormField, 'Prix de vente (XAF) *'), '500');
  await tester.enterText(
      find.widgetWithText(TextFormField, 'Quantité pour cette vente'), qty);
  await tester.tap(find.text('Catégorie *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Épicerie').last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('quantity 0 is rejected instead of silently becoming 1',
      (tester) async {
    final repo = _MockProductRepository();
    await _openSheet(tester, repo);
    await _fill(tester, qty: '0');

    await tester.tap(find.text('Créer & Ajouter'));
    await tester.pumpAndSettle();

    expect(find.textContaining('quantité valide'), findsOneWidget);
    verifyNever(() => repo.createDraft(
          name: any(named: 'name'),
          priceVente: any(named: 'priceVente'),
          categoryId: any(named: 'categoryId'),
        ));
  });

  testWidgets('failure after the sheet is closed does not call setState',
      (tester) async {
    final repo = _MockProductRepository();
    final pending = Completer<ProductModel>();
    when(() => repo.createDraft(
          name: any(named: 'name'),
          priceVente: any(named: 'priceVente'),
          categoryId: any(named: 'categoryId'),
        )).thenAnswer((_) => pending.future);
    await _openSheet(tester, repo);
    await _fill(tester, qty: '2');

    await tester.tap(find.text('Créer & Ajouter'));
    await tester.pump();
    await tester.tap(find.byIcon(Icons.close_rounded));
    await tester.pumpAndSettle();

    pending.completeError(Exception('boom'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
  });
}
