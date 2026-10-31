import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/inventory/domain/repository/inventory_session_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/inventory_session_provider.dart';
import 'package:keevo/features/inventory/presentation/widget/inventory_config_bottom_sheet.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:mocktail/mocktail.dart';

class _MockSessionRepo extends Mock implements InventorySessionRepository {}

class _FixedActiveStore extends ActiveStoreNotifier {
  _FixedActiveStore(this.id);
  final String id;
  @override
  String? build() => id;
}

class _FakeStoreList extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [
        StoreModel(
          id: 'store-open',
          name: 'Boutique ouverte',
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ),
        StoreModel(
          id: 'store-closed',
          name: 'Boutique fermée',
          isActive: false,
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ),
      ];
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  Widget buildApp(String activeStoreId, InventorySessionRepository repo) =>
      ProviderScope(
        retry: noProviderRetry,
        overrides: [
          inventorySessionRepositoryProvider.overrideWithValue(repo),
          activeStoreIdProvider
              .overrideWith(() => _FixedActiveStore(activeStoreId)),
          storeListNotifierProvider.overrideWith(_FakeStoreList.new),
          categoriesProvider.overrideWith((_) async => []),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => showInventoryConfigBottomSheet(context),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      );

  testWidgets('an inactive active store is not preselected', (tester) async {
    await tester.pumpWidget(buildApp('store-closed', _MockSessionRepo()));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Sélectionner une boutique'), findsOneWidget);
    final start = tester.widget<FilledButton>(find.ancestor(
        of: find.text('Démarrer l\'inventaire'),
        matching: find.byWidgetPredicate((w) => w is FilledButton)));
    expect(start.onPressed, isNull);
  });

  testWidgets('a failed creation re-enables the start button',
      (tester) async {
    final repo = _MockSessionRepo();
    when(() => repo.create(
          storeId: any(named: 'storeId'),
          scope: any(named: 'scope'),
          categoryIds: any(named: 'categoryIds'),
        )).thenThrow(Exception('Une session est déjà en cours'));

    await tester.pumpWidget(buildApp('store-open', repo));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Démarrer l\'inventaire'));
    await tester.pumpAndSettle();

    expect(find.text('Une session est déjà en cours'), findsOneWidget);
    final start = tester.widget<FilledButton>(find.ancestor(
        of: find.text('Démarrer l\'inventaire'),
        matching: find.byWidgetPredicate((w) => w is FilledButton)));
    expect(start.onPressed, isNotNull);
  });

  testWidgets('draws a single drag handle (the theme one)', (tester) async {
    await tester.pumpWidget(buildApp('store-open', _MockSessionRepo()));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    final selfDrawn = find.byWidgetPredicate((w) =>
        w is Container &&
        w.constraints?.maxWidth == 40 &&
        w.constraints?.maxHeight == 4);
    expect(selfDrawn, findsNothing);
  });

  // 13.10b — live bug: a Free tenant tapping « Démarrer l'inventaire » got a
  // 403 MODULE_NOT_ENTITLED and saw nothing (SnackBar under the sheet).
  testWidgets('module refusal: Premium message in the sheet, « Passer à '
      'Premium » closes it and opens the subscription screen', (tester) async {
    final repo = _MockSessionRepo();
    when(() => repo.create(
              storeId: any(named: 'storeId'),
              scope: any(named: 'scope'),
              categoryIds: any(named: 'categoryIds'),
            ))
        .thenThrow(
            const ModuleEntitlementException(ModuleRefusalReason.notEntitled));
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (context, _) => Scaffold(
          body: TextButton(
            onPressed: () => showInventoryConfigBottomSheet(context),
            child: const Text('open'),
          ),
        ),
      ),
      GoRoute(
        path: '/settings/subscription',
        builder: (_, __) => const Scaffold(body: Text('ÉCRAN ABONNEMENT')),
      ),
    ]);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        inventorySessionRepositoryProvider.overrideWithValue(repo),
        activeStoreIdProvider
            .overrideWith(() => _FixedActiveStore('store-open')),
        storeListNotifierProvider.overrideWith(_FakeStoreList.new),
        categoriesProvider.overrideWith((_) async => []),
        isOwnerSessionProvider.overrideWithValue(true),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Démarrer l\'inventaire'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('inventory-start-error')), findsOneWidget);
    expect(find.text(ModuleEntitlementException.notEntitledMessage),
        findsOneWidget);

    await tester.tap(find.text('Passer à Premium'));
    await tester.pumpAndSettle();
    expect(find.text('ÉCRAN ABONNEMENT'), findsOneWidget);
    expect(find.text('Démarrer l\'inventaire'), findsNothing);
  });
}
