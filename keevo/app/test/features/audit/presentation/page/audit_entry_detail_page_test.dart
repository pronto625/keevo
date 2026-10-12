import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/audit/domain/exception/audit_exception.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/presentation/page/audit_entry_detail_page.dart';
import 'package:keevo/features/audit/presentation/provider/audit_log_provider.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';

import 'audit_test_helpers.dart';

/// v1s-18-7 — detail of one action: Qui / Quoi / Sur quoi / Quand et où /
/// Changements / Contexte.
void main() {
  late FakeAuditRepository repo;

  setUp(() => repo = FakeAuditRepository());

  Widget app(String id, AuditEntryDto? initial) => ProviderScope(
        overrides: [
          auditRepositoryProvider.overrideWithValue(repo),
          currentUserIdProvider.overrideWith((_) async => 'owner-1'),
          auditEmployeeOptionsProvider.overrideWith((_) async => const [
                AuditFilterOption('user-awa', 'Awa Diallo'),
              ]),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/audit/entry/$id',
            routes: [
              GoRoute(
                path: '/audit/entry/:id',
                builder: (_, state) => AuditEntryDetailPage(
                    entryId: state.pathParameters['id']!,
                    initialEntry: initial),
              ),
              GoRoute(
                path: '/pos/sales-history/:id',
                builder: (_, state) =>
                    Scaffold(body: Text('sale ${state.pathParameters['id']}')),
              ),
            ],
          ),
        ),
      );

  Future<void> scrollTo(WidgetTester tester, String text) =>
      tester.scrollUntilVisible(find.text(text), 100,
          scrollable: find.byType(Scrollable).first);

  testWidgets('renders the tapped entry at once with every section',
      (tester) async {
    final pending = Completer<AuditEntryDto>();
    repo.onEntry = (_) => pending.future;
    final entry = auditEntry(
      'e1',
      action: 'PRICE_OVERRIDDEN',
      entityType: 'PriceOverride',
      entityLabel: 'Pagne wax 6 yards',
      valueBefore: '{"cataloguePrice":15000}',
      valueAfter: '{"appliedPrice":12500,"productName":"Pagne wax 6 yards"}',
      metadata: '{"actorLabel":"Awa","notes":"Client fidèle"}',
      source: 'SYNC',
      deviceId: 'tablette-1',
      at: DateTime(2026, 10, 12, 9, 5),
    );

    await tester.pumpWidget(app('e1', entry));
    await tester.pump();

    expect(find.text('Awa a modifié le prix de Pagne wax 6 yards'),
        findsOneWidget);
    expect(find.text('Qui'), findsOneWidget);
    expect(find.text('Awa'), findsOneWidget);
    expect(find.text('Employé'), findsOneWidget, reason: 'role');
    expect(find.text('tablette-1'), findsOneWidget);
    expect(find.text('Quoi'), findsOneWidget);
    expect(find.text('Prix modifié en caisse'), findsOneWidget);
    expect(find.text('Sur quoi'), findsOneWidget);
    expect(find.text('Quand et où'), findsOneWidget);
    expect(find.text('12/10/2026 09:05'), findsOneWidget);
    expect(find.text('Boutique Centre'), findsOneWidget);
    expect(
        find.text('Synchronisé depuis l\'appareil tablette-1'), findsOneWidget);
    await scrollTo(tester, 'Contexte');
    expect(find.text('Changements'), findsOneWidget);
    expect(find.text('15 000 XAF'), findsOneWidget);
    expect(find.text('12 500 XAF'), findsOneWidget);
    expect(find.text('Client fidèle'), findsOneWidget);
    expect(repo.entryCalls, ['e1']);
  });

  testWidgets('the fresh detail replaces the tapped data', (tester) async {
    repo.onEntry = (_) async =>
        auditEntry('e1', storeName: 'Boutique Marché', source: 'ONLINE');

    await tester
        .pumpWidget(app('e1', auditEntry('e1', storeName: null, source: null)));
    await tester.pumpAndSettle();

    expect(find.text('Boutique Marché'), findsOneWidget);
    expect(find.text('En ligne'), findsOneWidget);
  });

  testWidgets('404 keeps the tapped data', (tester) async {
    repo.onEntry = (_) async => throw const AuditException(
        domainCode: 'AUDIT_ENTRY_NOT_FOUND', message: 'x', statusCode: 404);

    await tester.pumpWidget(app('e1', auditEntry('e1')));
    await tester.pumpAndSettle();

    expect(find.text('Awa a créé le produit Pagne wax'), findsOneWidget);
    expect(find.text('Action introuvable'), findsNothing);
  });

  testWidgets('no tapped data and not found → error with Réessayer',
      (tester) async {
    repo.onEntry = (_) async => throw const AuditException(
        domainCode: 'AUDIT_ENTRY_NOT_FOUND',
        message: 'Action introuvable sur cet appareil');

    await tester.pumpWidget(app('e1', null));
    await tester.pumpAndSettle();

    expect(find.text('Action introuvable'), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
  });

  testWidgets('sale: amount as object and « Voir la vente » opens the sale',
      (tester) async {
    repo.onEntry = (_) => Completer<AuditEntryDto>().future;
    final sale = auditEntry('s1',
        action: 'SALE_COMPLETED',
        entityType: 'Sale',
        entityId: 'sale-42',
        entityLabel: 'Vente',
        valueAfter: '{"totalAmount":15000,"discountAmount":0}',
        metadata: '{"storeId":"x","occurredAt":"y"}');

    await tester.pumpWidget(app('s1', sale));
    await tester.pump();

    expect(find.text('Vente de 15 000 XAF'), findsOneWidget);
    await tester.tap(find.byKey(const Key('audit-open-sale')));
    await tester.pumpAndSettle();
    expect(find.text('sale sale-42'), findsOneWidget);
  });

  testWidgets('unknown action: generic rendering, no changes, no context',
      (tester) async {
    repo.onEntry = (_) => Completer<AuditEntryDto>().future;
    final e = auditEntry('u1',
        action: 'FOO_BAR',
        entityType: 'Gizmo',
        entityLabel: null,
        userId: 'owner-1',
        source: null);

    await tester.pumpWidget(app('u1', e));
    await tester.pump();

    expect(find.text('Vous avez effectué « Foo bar »'), findsOneWidget);
    expect(find.text('Foo bar'), findsOneWidget);
    expect(find.text('FOO_BAR'), findsOneWidget, reason: 'raw code shown');
    expect(find.text('Propriétaire'), findsOneWidget);
    expect(find.text('Gizmo'), findsOneWidget);
    expect(find.text('Non renseigné'), findsWidgets);
    await scrollTo(tester, 'Aucun changement détaillé');
    expect(find.text('Contexte'), findsNothing);
  });
}
