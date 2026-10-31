import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/audit/domain/exception/audit_exception.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:keevo/features/audit/presentation/page/audit_log_page.dart';
import 'package:keevo/features/audit/presentation/provider/audit_log_provider.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../../../helpers/no_technical_text.dart';
import 'audit_test_helpers.dart';

/// v1s-18-7 — « Historique des actions » list.
void main() {
  late FakeAuditRepository repo;
  AuditEntryDto? opened;
  ExportPrefill? exportPrefill;
  late String role;

  setUp(() {
    repo = FakeAuditRepository();
    opened = null;
    exportPrefill = null;
    role = 'OWNER';
  });

  Widget app() => ProviderScope(
        retry: noProviderRetry,
        overrides: [
          auditRepositoryProvider.overrideWithValue(repo),
          currentUserRoleProvider.overrideWithValue(role),
          currentUserIdProvider.overrideWith((_) async => 'owner-1'),
          auditStoreOptionsProvider.overrideWith((_) async => const [
                AuditFilterOption('store-1', 'Boutique Centre'),
                AuditFilterOption('store-2', 'Boutique Marché'),
              ]),
          auditEmployeeOptionsProvider.overrideWith((_) async => const [
                AuditFilterOption('user-awa', 'Awa Diallo'),
              ]),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/audit',
            routes: [
              GoRoute(path: '/audit', builder: (_, __) => const AuditLogPage()),
              GoRoute(
                path: '/exports/new',
                builder: (_, state) {
                  exportPrefill = state.extra as ExportPrefill?;
                  return const Scaffold(body: Text('EXPORTER'));
                },
              ),
              GoRoute(
                path: '/audit/entry/:id',
                builder: (_, state) {
                  opened = state.extra as AuditEntryDto?;
                  return Scaffold(
                      body: Text('detail ${state.pathParameters['id']}'));
                },
              ),
            ],
          ),
        ),
      );

  testWidgets(
      'day groups, readable sentences, store and time; tap opens detail',
      (tester) async {
    final now = DateTime.now();
    final today = auditEntry('t1',
        at: DateTime(now.year, now.month, now.day, 0, 5),
        actorName: 'Awa',
        entityLabel: 'Pagne wax');
    final yesterday = auditEntry('y1',
        at: DateTime(now.year, now.month, now.day - 1, 9, 30),
        action: 'PRICE_OVERRIDDEN',
        entityType: 'PriceOverride',
        entityLabel: 'Savon',
        storeName: null);
    repo.onSlice = (_, __) async =>
        AuditSlice(entries: [today, yesterday], nextCursor: null);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    expect(find.text('Historique des actions'), findsOneWidget);
    expect(find.text('Aujourd\'hui'), findsOneWidget);
    expect(find.text('Hier'), findsOneWidget);
    expect(find.text('Awa a créé le produit Pagne wax', findRichText: true),
        findsOneWidget);
    expect(find.text('Awa a modifié le prix de Savon', findRichText: true),
        findsOneWidget);
    expect(find.text('Boutique Centre · 00:05'), findsOneWidget);
    expect(find.text('09:30'), findsOneWidget);
    expect(find.byKey(const Key('audit-offline-banner')), findsNothing);
    expectNoTechnicalText(tester);

    await tester
        .tap(find.text('Awa a créé le produit Pagne wax', findRichText: true));
    await tester.pumpAndSettle();
    expect(find.text('detail t1'), findsOneWidget);
    expect(opened?.id, 't1');
  });

  testWidgets(
      'v1s-16-20 guard: unknown codes and ids never reach the list',
      (tester) async {
    final now = DateTime.now();
    repo.onSlice = (_, __) async => AuditSlice(entries: [
          auditEntry('x1',
              at: DateTime(now.year, now.month, now.day, 8),
              action: 'SOME_NEW_ACTION',
              entityType: 'SomeThing',
              entityLabel: '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f',
              actorName: null,
              valueAfter: '{"status":"PENDING_VALIDATION"}'),
        ], nextCursor: null);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    expect(find.textContaining('Some new action', findRichText: true),
        findsWidgets);
    expectNoTechnicalText(tester);
  });

  testWidgets('v1s-16-23: a transfer line ends with « vers Boutique B »',
      (tester) async {
    final now = DateTime.now();
    repo.onSlice = (_, __) async => AuditSlice(entries: [
          auditEntry('tr1',
              at: DateTime(now.year, now.month, now.day, 8),
              action: 'STOCK_ADJUSTED',
              entityType: 'StockMovement',
              actorName: 'Awa',
              entityLabel: 'Pagne wax',
              valueAfter: '{"movementType":"TRANSFER_OUT","quantityChange":-3,'
                  '"quantityAfter":7}',
              metadata: '{"destinationStoreName":"Boutique B"}'),
        ], nextCursor: null);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    expect(
        find.text('Awa a transféré du stock de Pagne wax vers Boutique B',
            findRichText: true),
        findsOneWidget);
    expectNoTechnicalText(tester);
  });

  testWidgets('a filter chip reloads from the first page with the filter',
      (tester) async {
    repo.onSlice = (_, __) async => AuditSlice(entries: [auditEntry('a')]);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    expect(repo.sliceCalls.single.$1, AuditFilter.empty);

    await tester.ensureVisible(find.text('Boutique'));
    await tester.tap(find.text('Boutique'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique Marché'));
    await tester.pumpAndSettle();

    expect(repo.sliceCalls.last.$1.storeId, 'store-2');
    expect(repo.sliceCalls.last.$2, isNull, reason: 'first page');
    expect(find.text('Boutique Marché'), findsOneWidget, reason: 'chip label');
    expect(find.text('Effacer'), findsOneWidget);

    await tester.ensureVisible(find.text('Employé'));
    await tester.tap(find.text('Employé'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Awa Diallo'));
    await tester.pumpAndSettle();
    expect(repo.sliceCalls.last.$1.userId, 'user-awa');
    expect(repo.sliceCalls.last.$1.storeId, 'store-2');

    await tester.ensureVisible(find.text('Effacer'));
    await tester.tap(find.text('Effacer'));
    await tester.pumpAndSettle();
    expect(repo.sliceCalls.last.$1, AuditFilter.empty);
  });

  testWidgets('period, action and object chips set the matching filters',
      (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Période'));
    await tester.tap(find.text('Période'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('7 derniers jours'));
    await tester.pumpAndSettle();
    final f = repo.sliceCalls.last.$1;
    expect(f.from, isNotNull);
    expect(f.to, isNotNull);
    expect(f.to!.difference(f.from!).inDays, 6);
    expect(find.text('7 jours'), findsOneWidget);

    await tester.ensureVisible(find.text('Action'));
    await tester.tap(find.text('Action'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Vente annulée'), 100,
        scrollable: find.byType(Scrollable).last);
    // More actions in the list (J7/J10): bring the row fully on screen.
    await tester.ensureVisible(find.text('Vente annulée'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Vente annulée'));
    await tester.pumpAndSettle();
    expect(repo.sliceCalls.last.$1.action, 'SALE_CANCELLED');

    await tester.ensureVisible(find.text('Objet'));
    await tester.tap(find.text('Objet'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Ventes'));
    await tester.pumpAndSettle();
    expect(repo.sliceCalls.last.$1.entityType, 'Sale');
  });

  testWidgets('object search text is sent as the query after a pause',
      (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('audit-search')), 'pagne');
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(repo.sliceCalls.last.$1.normalizedQuery, 'pagne');
  });

  testWidgets('offline slice shows the banner', (tester) async {
    repo.onSlice = (_, __) async => AuditSlice(
        entries: [auditEntry('a', action: 'FOO_BAR')], isOffline: true);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    expect(find.text('Hors ligne — dernier état connu'), findsOneWidget);
    expect(
        find.text('Awa a effectué « Foo bar » sur Pagne wax',
            findRichText: true),
        findsOneWidget);
  });

  testWidgets('offline and empty → offline empty state', (tester) async {
    repo.onSlice =
        (_, __) async => const AuditSlice(entries: [], isOffline: true);

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    expect(find.text('Hors ligne — dernier état connu'), findsOneWidget);
    expect(
        find.text(
            'Aucune action synchronisée sur cet appareil pour ces filtres'),
        findsOneWidget);
  });

  testWidgets('online and empty → « Aucune action pour ces filtres »',
      (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    expect(find.text('Aucune action pour ces filtres'), findsOneWidget);
  });

  testWidgets('server error → AppErrorWidget, Réessayer reloads',
      (tester) async {
    var fail = true;
    repo.onSlice = (_, __) async {
      if (fail) {
        throw const AuditException(
            domainCode: 'INTERNAL_ERROR',
            message: 'Erreur serveur',
            statusCode: 500);
      }
      return AuditSlice(entries: [auditEntry('a')]);
    };

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    expect(find.text('Historique indisponible'), findsOneWidget);
    expect(find.text('Erreur serveur'), findsOneWidget);

    fail = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(find.text('Awa a créé le produit Pagne wax', findRichText: true),
        findsOneWidget);
  });

  testWidgets('scrolling to the end appends the next slice with the cursor',
      (tester) async {
    final first = [
      for (var i = 0; i < 20; i++) auditEntry('e$i', entityLabel: 'Produit $i'),
    ];
    final next = Completer<AuditSlice>();
    repo.onSlice = (_, cursor) {
      if (cursor == null) {
        return Future.value(AuditSlice(entries: first, nextCursor: 'c1'));
      }
      return next.future;
    };

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView).last, const Offset(0, -5000));
    await tester.pump();
    expect(repo.sliceCalls.last.$2, 'c1');

    next.complete(AuditSlice(entries: [
      first.last, // duplicate id must not be shown twice
      auditEntry('more', entityLabel: 'Dernier produit'),
    ]));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -5000));
    await tester.pumpAndSettle();

    expect(
        find.text('Awa a créé le produit Dernier produit', findRichText: true),
        findsOneWidget);
    expect(find.text('Awa a créé le produit Produit 19', findRichText: true),
        findsOneWidget);
    expect(repo.sliceCalls.where((c) => c.$2 != null), hasLength(1));
  });

  testWidgets('load-more failure → SnackBar, list kept', (tester) async {
    repo.onSlice = (_, cursor) async {
      if (cursor == null) {
        return AuditSlice(entries: [
          for (var i = 0; i < 20; i++) auditEntry('e$i', entityLabel: 'P$i'),
        ], nextCursor: 'c1');
      }
      throw const AuditException(
          domainCode: 'INTERNAL_ERROR', message: 'x', statusCode: 500);
    };

    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -5000));
    await tester.pumpAndSettle();

    expect(find.text('Impossible de charger la suite de l\'historique'),
        findsOneWidget);
    expect(find.text('Awa a créé le produit P19', findRichText: true),
        findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget, reason: 'footer retry');
  });

  // v1s-18-4 — « Exporter » pre-filled with the audit log and the filters.
  testWidgets('« Exporter » opens the export screen with the current filters',
      (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Boutique'));
    await tester.tap(find.text('Boutique'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique Marché'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Employé'));
    await tester.tap(find.text('Employé'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Awa Diallo'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Période'));
    await tester.tap(find.text('Période'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('7 derniers jours'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('audit-search')), ' pagne ');
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    final filter = repo.sliceCalls.last.$1;

    await tester.tap(find.byKey(const Key('audit-export')));
    await tester.pumpAndSettle();

    expect(find.text('EXPORTER'), findsOneWidget);
    final p = exportPrefill!;
    expect(p.datasets, {'audit_log'});
    expect(p.storeId, 'store-2');
    expect(p.from, filter.from);
    expect(p.to, filter.to);
    expect(p.criteria, {'userId': 'user-awa', 'q': 'pagne'});
  });

  testWidgets('no « Exporter » button for an employee', (tester) async {
    role = 'EMPLOYEE';
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    expect(find.byTooltip('Exporter'), findsNothing);
  });
}
