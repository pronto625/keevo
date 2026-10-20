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

import '../../../../helpers/no_technical_text.dart';
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
    // v1s-16-20: the device is named after the actor, never by its id.
    expect(find.text('Appareil de Awa'), findsOneWidget);
    expect(find.textContaining('tablette-1'), findsNothing);
    expect(find.text('Quoi'), findsOneWidget);
    expect(find.text('Prix modifié en caisse'), findsOneWidget);
    expect(find.text('Sur quoi'), findsOneWidget);
    expect(find.text('Quand et où'), findsOneWidget);
    expect(find.text('12/10/2026 09:05'), findsOneWidget);
    expect(find.text('Boutique Centre'), findsOneWidget);
    expect(
        find.text('Synchronisé depuis l\'appareil de Awa'), findsOneWidget);
    await scrollTo(tester, 'Contexte');
    expect(find.text('Changements'), findsOneWidget);
    expect(find.text('15 000 XAF'), findsOneWidget);
    expect(find.text('12 500 XAF'), findsOneWidget);
    expect(find.text('Client fidèle'), findsOneWidget);
    expect(repo.entryCalls, ['e1']);
    expectNoTechnicalText(tester);
  });

  testWidgets(
      'v1s-16-20 guard: ids, device id, nested values and codes never shown',
      (tester) async {
    repo.onEntry = (_) => Completer<AuditEntryDto>().future;
    final e = auditEntry('t1',
        action: 'PRODUCT_UPDATED',
        entityType: 'Product',
        entityLabel: 'Savon',
        valueBefore: '{"id":"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f",'
            '"status":"DRAFT","categoryId":"0b1c2d3e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"}',
        valueAfter: '{"status":"PENDING_VALIDATION","extra":{"a":1},'
            '"ref":"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f","kind":"SOME_CODE"}',
        metadata: '{"storeId":"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f",'
            '"notes":"INVENTORY:7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"}',
        source: 'SYNC',
        deviceId: 'android-3f2a9c1d8e7b6a5f');

    await tester.pumpWidget(app('t1', e));
    await tester.pump();
    await scrollTo(tester, 'Contexte');

    expect(find.text('Code'), findsNothing);
    expect(find.text('Ajustement d\'inventaire'), findsOneWidget);
    expectNoTechnicalText(tester);
  });

  testWidgets('v1s-16-23: a sending reads « vers B » with a « Vers » row',
      (tester) async {
    repo.onEntry = (_) => Completer<AuditEntryDto>().future;
    final e = auditEntry('t-out',
        action: 'STOCK_ADJUSTED',
        entityType: 'StockMovement',
        entityLabel: 'Pagne wax',
        valueAfter: '{"movementType":"TRANSFER_OUT","quantityChange":-3,'
            '"quantityAfter":7}',
        metadata: '{"actorLabel":"Awa","destinationStoreName":"Boutique B"}');

    await tester.pumpWidget(app('t-out', e));
    await tester.pump();

    expect(find.text('Awa a transféré du stock de Pagne wax vers Boutique B'),
        findsOneWidget);
    await scrollTo(tester, 'Contexte');
    expect(find.text('Vers'), findsOneWidget);
    expect(find.text('Boutique B'), findsOneWidget);
    expectNoTechnicalText(tester);
  });

  testWidgets('v1s-16-23: a receipt reads « depuis A » with a « Depuis » row',
      (tester) async {
    repo.onEntry = (_) => Completer<AuditEntryDto>().future;
    final e = auditEntry('t-in',
        action: 'STOCK_ADJUSTED',
        entityType: 'StockMovement',
        entityLabel: 'Pagne wax',
        valueAfter: '{"movementType":"TRANSFER_IN","quantityChange":3,'
            '"quantityAfter":3}',
        metadata: '{"actorLabel":"Awa","sourceStoreName":"Boutique A"}');

    await tester.pumpWidget(app('t-in', e));
    await tester.pump();

    expect(
        find.text(
            'Awa a reçu par transfert du stock de Pagne wax depuis Boutique A'),
        findsOneWidget);
    await scrollTo(tester, 'Contexte');
    expect(find.text('Depuis'), findsOneWidget);
    expect(find.text('Boutique A'), findsOneWidget);
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
    expect(find.text('FOO_BAR'), findsNothing, reason: 'never the raw code');
    expect(find.text('Code'), findsNothing);
    expect(find.text('Propriétaire'), findsOneWidget);
    expect(find.text('Gizmo'), findsOneWidget);
    expect(find.text('Non renseigné'), findsWidgets);
    await scrollTo(tester, 'Aucun changement détaillé');
    expect(find.text('Contexte'), findsNothing);
    expectNoTechnicalText(tester);
  });
}
