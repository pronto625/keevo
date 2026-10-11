import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/inventory/domain/model/inventory_session_model.dart';
import 'package:keevo/features/inventory/domain/repository/inventory_session_repository.dart';
import 'package:keevo/features/inventory/presentation/page/inventory_launch_page.dart';
import 'package:keevo/features/inventory/presentation/provider/inventory_session_provider.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _MockSessionRepo extends Mock implements InventorySessionRepository {}

class _FixedActiveStore extends ActiveStoreNotifier {
  @override
  String? build() => 'store-1';
}

class _FakeStoreList extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [
        StoreModel(
          id: 'store-1',
          name: 'Boutique Centre',
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ),
      ];
}

final _session = InventorySessionModel(
  id: 'sess-42',
  storeId: 'store-1',
  scope: 'FULL',
  status: 'IN_PROGRESS',
  startedBy: 'u1',
  startedAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockSessionRepo repo;

  setUp(() {
    repo = _MockSessionRepo();
    when(() => repo.getHistory(
        page: any(named: 'page'),
        size: any(named: 'size'))).thenAnswer((_) async => []);
  });

  Widget buildApp() {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const InventoryLaunchPage()),
      GoRoute(
        path: '/inventory/counting/:id',
        builder: (_, s) => Text('counting ${s.pathParameters['id']}'),
      ),
    ]);
    return ProviderScope(
      overrides: [
        inventorySessionRepositoryProvider.overrideWithValue(repo),
        activeStoreIdProvider.overrideWith(_FixedActiveStore.new),
        storeListNotifierProvider.overrideWith(_FakeStoreList.new),
        categoriesProvider.overrideWith((_) async => []),
      ],
      child: MaterialApp.router(routerConfig: router),
    );
  }

  testWidgets('starting a session opens its counting page', (tester) async {
    var created = false;
    when(() => repo.getActiveByStoreId('store-1'))
        .thenAnswer((_) async => created ? _session : null);
    when(() => repo.create(
          storeId: any(named: 'storeId'),
          scope: any(named: 'scope'),
          categoryIds: any(named: 'categoryIds'),
        )).thenAnswer((_) async {
      created = true;
      return _session;
    });

    await tester.pumpWidget(buildApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Nouvel inventaire'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Démarrer l\'inventaire'));
    await tester.pumpAndSettle();

    expect(find.text('counting sess-42'), findsOneWidget);
  });

  testWidgets('a failed cancellation is reported to the user', (tester) async {
    when(() => repo.getActiveByStoreId('store-1'))
        .thenAnswer((_) async => _session);
    when(() => repo.cancel('sess-42'))
        .thenThrow(Exception('Session déjà validée'));

    await tester.pumpWidget(buildApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Oui, annuler'));
    await tester.pumpAndSettle();

    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.text('Session déjà validée'), findsOneWidget);
  });
}
