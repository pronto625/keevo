import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/contact/domain/repository/client_repository.dart';
import 'package:keevo/features/contact/domain/repository/supplier_repository.dart';
import 'package:keevo/features/contact/presentation/page/client_form_page.dart';
import 'package:keevo/features/contact/presentation/page/client_list_page.dart';
import 'package:keevo/features/contact/presentation/page/supplier_form_page.dart';
import 'package:keevo/features/contact/presentation/page/supplier_list_page.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/contact/presentation/provider/contact_provider.dart';

class _MockClientRepository extends Mock implements ClientRepository {}

class _MockSupplierRepository extends Mock implements SupplierRepository {}

void main() {
  testWidgets('ClientListPage: sync failure shows an error, not success',
      (tester) async {
    final repo = _MockClientRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    // The silent sync on open must not surface an uncaught error either.
    when(() => repo.syncFromRemote()).thenThrow(Exception('network'));

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Synchroniser'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation impossible'), findsOneWidget);
    expect(find.text('Clients synchronisés'), findsNothing);
  });

  testWidgets('SupplierListPage: sync failure shows an error, not success',
      (tester) async {
    final repo = _MockSupplierRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.syncFromRemote()).thenThrow(Exception('network'));

    await tester.pumpWidget(ProviderScope(
      overrides: [supplierRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: SupplierListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Synchroniser'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation impossible'), findsOneWidget);
    expect(find.textContaining('synchronisés'), findsNothing);
  });

  testWidgets('ClientFormPage: save failure shows an error and keeps the form',
      (tester) async {
    final repo = _MockClientRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.create(
          name: any(named: 'name'),
          phone: any(named: 'phone'),
          email: any(named: 'email'),
          notes: any(named: 'notes'),
        )).thenThrow(Exception('Base locale indisponible'));
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientFormPage()),
    ));
    await tester.pumpAndSettle();

    final fields = find.byType(TextField);
    await tester.enterText(fields.at(0), 'Awa');
    await tester.enterText(fields.at(1), '+237600000000');
    await tester.ensureVisible(find.text('Ajouter le client'));
    await tester.tap(find.text('Ajouter le client'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Base locale indisponible'), findsOneWidget);
    expect(find.text('Ajouter le client'), findsOneWidget);
  });

  testWidgets('SupplierFormPage: product load error is shown with a retry',
      (tester) async {
    final repo = _MockSupplierRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        supplierRepositoryProvider.overrideWithValue(repo),
        productListForPickerProvider
            .overrideWith((ref) async => throw Exception('db')),
      ],
      child: const MaterialApp(home: SupplierFormPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Impossible de charger les produits'), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
  });

  testWidgets('SupplierFormPage: product picker search is accent-insensitive '
      '(v1s-16-16)', (tester) async {
    final repo = _MockSupplierRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    ProductModel product(String id, String name) => ProductModel(
        id: id,
        name: name,
        createdAt: DateTime(2026),
        updatedAt: DateTime(2026));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        supplierRepositoryProvider.overrideWithValue(repo),
        productListForPickerProvider.overrideWith((ref) async => [
              product('p-epi', 'Épicerie fine'),
              product('p-huile', 'Huile'),
            ]),
      ],
      child: const MaterialApp(home: SupplierFormPage()),
    ));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Associer des produits'));
    await tester.tap(find.text('Associer des produits'));
    await tester.pumpAndSettle();
    expect(find.text('Huile'), findsOneWidget);

    await tester.enterText(
        find.widgetWithText(TextField, 'Rechercher un produit…'), 'epicerie');
    await tester.pumpAndSettle();

    expect(find.text('Épicerie fine'), findsOneWidget);
    expect(find.text('Huile'), findsNothing);
  });
}
