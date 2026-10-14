import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/domain/exception/store_exception.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/page/stores_list_page.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _MockStoreRepository extends Mock implements StoreRepository {}

void main() {
  late _MockStoreRepository repo;

  setUpAll(() => registerFallbackValue(StoreType.store));

  setUp(() {
    repo = _MockStoreRepository();
    when(() => repo.getStores(includeInactive: any(named: 'includeInactive')))
        .thenAnswer((_) async => []);
    when(() => repo.getStores()).thenAnswer((_) async => []);
    when(() => repo.syncFromRemote()).thenAnswer((_) async {});
  });

  Widget buildPage() => ProviderScope(
        overrides: [storeRepositoryProvider.overrideWithValue(repo)],
        child: const MaterialApp(home: StoresListPage()),
      );

  testWidgets('sync keeps the "Voir les désactivées" choice', (tester) async {
    await tester.pumpWidget(buildPage());
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    clearInteractions(repo);

    await tester.tap(find.byTooltip('Synchroniser'));
    await tester.pumpAndSettle();

    // The page's own list is reloaded with the toggle kept.
    verify(() => repo.getStores(includeInactive: true)).called(greaterThan(0));
  });

  testWidgets('"Voir les désactivées" does not leak into the shared store list',
      (tester) async {
    final now = DateTime(2025, 1, 1);
    final active = StoreModel(
        id: 's1', name: 'Ouverte', type: StoreType.store,
        createdAt: now, updatedAt: now);
    final inactive = StoreModel(
        id: 's2', name: 'Fermée', type: StoreType.store, isActive: false,
        createdAt: now, updatedAt: now);
    when(() => repo.getStores()).thenAnswer((_) async => [active]);
    when(() => repo.getStores(includeInactive: false))
        .thenAnswer((_) async => [active]);
    when(() => repo.getStores(includeInactive: true))
        .thenAnswer((_) async => [active, inactive]);
    final container =
        ProviderContainer(overrides: [storeRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    // Another screen (e.g. the shell) keeps the shared list alive.
    container.listen(storeListNotifierProvider, (_, __) {});
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: StoresListPage()),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(find.text('Fermée'), findsOneWidget);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: SizedBox()),
    ));
    await container.read(storeListNotifierProvider.notifier).refresh();

    expect(container.read(storeListNotifierProvider).value, [active]);
  });

  testWidgets('plan limit closes the form, then shows the plan-limit sheet',
      (tester) async {
    when(() => repo.createStore(
          name: any(named: 'name'),
          type: any(named: 'type'),
          address: any(named: 'address'),
          phone: any(named: 'phone'),
        )).thenThrow(StoreException.planLimitExceeded());
    await tester.pumpWidget(buildPage());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ajouter une boutique'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Boutique 4');
    await tester.tap(find.text('Créer'));
    await tester.pumpAndSettle();

    expect(find.text('Nouvelle boutique'), findsNothing);
    // FREE plan: 1 store (PlanType.FREE) — never the old hard-coded 3.
    expect(find.textContaining('Vous avez atteint la limite de 1 boutique '),
        findsOneWidget);
    expect(find.text(StoreException.planLimitExceeded().message), findsNothing);
  });

  testWidgets('empty state names the actual add button', (tester) async {
    await tester.pumpWidget(buildPage());
    await tester.pumpAndSettle();

    expect(find.textContaining('Appuyez sur +'), findsNothing);
    expect(find.textContaining('« Ajouter une boutique »'), findsOneWidget);
  });

  testWidgets('manual sync failure shows an error instead of success',
      (tester) async {
    await tester.pumpWidget(buildPage());
    await tester.pumpAndSettle();
    when(() => repo.syncFromRemote()).thenThrow(Exception('network'));

    await tester.tap(find.byTooltip('Synchroniser'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation impossible'), findsOneWidget);
    expect(find.text('Boutiques synchronisées'), findsNothing);
  });

  group('J7 — désactiver / réactiver', () {
    final now = DateTime(2025, 1, 1);
    final active = StoreModel(
        id: 's1', name: 'Akwa', type: StoreType.store,
        createdAt: now, updatedAt: now);
    final inactive = StoreModel(
        id: 's2', name: 'Bonamoussadi', type: StoreType.store,
        isActive: false, createdAt: now, updatedAt: now);

    setUp(() {
      when(() => repo.getStores(includeInactive: any(named: 'includeInactive')))
          .thenAnswer((_) async => [active, inactive]);
      when(() => repo.getStores()).thenAnswer((_) async => [active]);
    });

    Future<void> openMenu(WidgetTester tester, String name) async {
      await tester.tap(find.descendant(
        of: find.ancestor(of: find.text(name), matching: find.byType(ListTile)),
        matching: find.byTooltip('Actions'),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('« Désactiver » asks for confirmation; Annuler does nothing',
        (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await openMenu(tester, 'Akwa');
      await tester.tap(find.text('Désactiver'));
      await tester.pumpAndSettle();

      expect(find.text('Désactiver la boutique ?'), findsOneWidget);
      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();

      verifyNever(() => repo.deactivateStore(any()));
    });

    testWidgets('confirming deactivates the store', (tester) async {
      when(() => repo.deactivateStore('s1'))
          .thenAnswer((_) async => active.copyWith(isActive: false));
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await openMenu(tester, 'Akwa');
      await tester.tap(find.text('Désactiver'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Désactiver'));
      await tester.pumpAndSettle();

      verify(() => repo.deactivateStore('s1')).called(1);
    });

    testWidgets('a deactivated store offers « Réactiver »', (tester) async {
      when(() => repo.reactivateStore('s2'))
          .thenAnswer((_) async => inactive.copyWith(isActive: true));
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();

      await openMenu(tester, 'Bonamoussadi');
      expect(find.text('Désactiver'), findsNothing);
      expect(find.text('Modifier'), findsNothing);
      await tester.tap(find.text('Réactiver'));
      await tester.pumpAndSettle();

      verify(() => repo.reactivateStore('s2')).called(1);
      expect(find.text('« Bonamoussadi » est de nouveau active.'),
          findsOneWidget);
    });

    testWidgets('reactivation over the plan limit shows the French error',
        (tester) async {
      when(() => repo.reactivateStore('s2'))
          .thenThrow(StoreException.planLimitExceeded());
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();

      await openMenu(tester, 'Bonamoussadi');
      await tester.tap(find.text('Réactiver'));
      await tester.pumpAndSettle();

      expect(find.text(StoreException.planLimitExceeded().message),
          findsOneWidget);
    });
  });

  testWidgets('create failure keeps the form open with the error shown',
      (tester) async {
    when(() => repo.createStore(
          name: any(named: 'name'),
          type: any(named: 'type'),
          address: any(named: 'address'),
          phone: any(named: 'phone'),
        )).thenThrow(StoreException.warehouseAlreadyExists());
    await tester.pumpWidget(buildPage());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ajouter une boutique'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Dépôt Nord');
    await tester.tap(find.text('Créer'));
    await tester.pumpAndSettle();

    expect(find.text('Nouvelle boutique'), findsOneWidget);
    expect(find.text('Dépôt Nord'), findsOneWidget);
    expect(find.text(StoreException.warehouseAlreadyExists().message),
        findsOneWidget);
  });
}
