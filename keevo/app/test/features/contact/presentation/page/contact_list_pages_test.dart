import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:keevo/features/contact/domain/model/supplier_model.dart';
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

  // v1s-16-13: the archived / sync buttons stay reachable once the header
  // collapses, and a search with « Voir les archivés » includes archived.
  testWidgets('ClientListPage: header actions stay pinned while scrolling',
      (tester) async {
    final repo = _MockClientRepository();
    final now = DateTime(2026, 1, 1);
    final clients = [
      for (var i = 0; i < 30; i++)
        ClientModel(
            id: 'c$i',
            name: 'Client $i',
            phone: '+2376900000${i.toString().padLeft(2, '0')}',
            createdAt: now,
            updatedAt: now),
    ];
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => clients);
    when(() => repo.search(any(),
            includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => clients);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.drag(find.byType(CustomScrollView), const Offset(0, -1500));
    await tester.pumpAndSettle();

    expect(find.byTooltip('Voir les archivés').hitTestable(), findsOneWidget);
    expect(find.byTooltip('Synchroniser').hitTestable(), findsOneWidget);

    await tester.tap(find.byTooltip('Voir les archivés'));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(CustomScrollView), const Offset(0, 1500));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'cli');
    await tester.pumpAndSettle();

    verify(() => repo.search('cli', includeArchived: true)).called(1);
  });

  // v1s-16-13: clearing the search lists everything again.
  testWidgets('ClientListPage: the clear button reloads the full list',
      (tester) async {
    final repo = _MockClientRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.search(any(),
            includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientListPage()),
    ));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'ali');
    await tester.pumpAndSettle();
    clearInteractions(repo);

    await tester.tap(find.byIcon(Icons.clear_rounded));
    await tester.pumpAndSettle();

    verify(() => repo.getAll(includeArchived: false)).called(1);
    verifyNever(() =>
        repo.search(any(), includeArchived: any(named: 'includeArchived')));
  });

  testWidgets('SupplierListPage: the clear button reloads the full list',
      (tester) async {
    final repo = _MockSupplierRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.search(any(),
            includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => []);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});

    await tester.pumpWidget(ProviderScope(
      overrides: [supplierRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: SupplierListPage()),
    ));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'gros');
    await tester.pumpAndSettle();
    clearInteractions(repo);

    await tester.tap(find.byIcon(Icons.clear_rounded));
    await tester.pumpAndSettle();

    verify(() => repo.getAll(includeArchived: false)).called(1);
    verifyNever(() =>
        repo.search(any(), includeArchived: any(named: 'includeArchived')));
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

  // v1s-16-11 follow-up: « Réessayer » reloads the local list without
  // starting a remote sync (the sync button keeps that role).
  testWidgets('ClientListPage: Réessayer reloads the list, no new sync',
      (tester) async {
    final repo = _MockClientRepository();
    var failing = true;
    final now = DateTime(2026, 1, 1);
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async {
      if (failing) throw StateError('database is locked');
      return [
        ClientModel(
            id: 'c1',
            name: 'Awa Diallo',
            phone: '+237690000001',
            createdAt: now,
            updatedAt: now),
      ];
    });
    when(() => repo.syncFromRemote()).thenThrow(Exception('offline'));

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientListPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Erreur de chargement'), findsOneWidget);
    expect(find.textContaining('database'), findsNothing);
    verify(() => repo.syncFromRemote()).called(1);

    failing = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Awa Diallo'), findsOneWidget);
    expect(find.text('Réessayer'), findsNothing);
    verifyNever(() => repo.syncFromRemote());
    expect(find.textContaining('Synchronisation impossible'), findsNothing);
  });

  testWidgets('SupplierListPage: Réessayer reloads the list, no new sync',
      (tester) async {
    final repo = _MockSupplierRepository();
    var failing = true;
    final now = DateTime(2026, 1, 1);
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async {
      if (failing) throw StateError('database is locked');
      return [
        SupplierModel(
            id: 's1',
            name: 'Grossiste Mokolo',
            phone: '+237690000002',
            createdAt: now,
            updatedAt: now),
      ];
    });
    when(() => repo.syncFromRemote()).thenThrow(Exception('offline'));

    await tester.pumpWidget(ProviderScope(
      overrides: [supplierRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: SupplierListPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Erreur de chargement'), findsOneWidget);
    verify(() => repo.syncFromRemote()).called(1);

    failing = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Grossiste Mokolo'), findsOneWidget);
    expect(find.text('Réessayer'), findsNothing);
    verifyNever(() => repo.syncFromRemote());
  });

  // A22: an archived card (« Voir les archivés ») is restored by long press.
  testWidgets('ClientListPage: long press on an archived client unarchives it',
      (tester) async {
    final repo = _MockClientRepository();
    final now = DateTime(2026, 1, 1);
    final archived = ClientModel(
        id: 'c1',
        name: 'Alice',
        phone: '+237690000001',
        archived: true,
        createdAt: now,
        updatedAt: now);
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => [archived]);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});
    when(() => repo.unarchive(any())).thenAnswer((_) async {});

    await tester.pumpWidget(ProviderScope(
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ClientListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.longPress(find.text('Alice'));
    await tester.pumpAndSettle();
    expect(find.text('Désarchiver le client ?'), findsOneWidget);
    expect(find.text('Archiver le client ?'), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'Désarchiver'));
    await tester.pumpAndSettle();

    verify(() => repo.unarchive('c1')).called(1);
    verifyNever(() => repo.archive(any()));
  });

  testWidgets('SupplierListPage: unarchive asks first; Annuler does nothing',
      (tester) async {
    final repo = _MockSupplierRepository();
    final now = DateTime(2026, 1, 1);
    final archived = SupplierModel(
        id: 's1',
        name: 'Grossiste Mokolo',
        phone: '+237690000002',
        archived: true,
        createdAt: now,
        updatedAt: now);
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => [archived]);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});
    when(() => repo.unarchive(any())).thenAnswer((_) async {});

    await tester.pumpWidget(ProviderScope(
      overrides: [supplierRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: SupplierListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.longPress(find.text('Grossiste Mokolo'));
    await tester.pumpAndSettle();
    expect(find.text('Désarchiver le fournisseur ?'), findsOneWidget);
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    verifyNever(() => repo.unarchive(any()));

    await tester.longPress(find.text('Grossiste Mokolo'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Désarchiver'));
    await tester.pumpAndSettle();

    verify(() => repo.unarchive('s1')).called(1);
  });
}
