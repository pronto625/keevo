import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_quarantine_service.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/quarantined_operations_section.dart';
import 'package:mocktail/mocktail.dart';

class _MockQuarantine extends Mock implements SyncQuarantineService {}

class _FakeTrigger extends SyncTriggerNotifier {
  static int syncs = 0;

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async => syncs++;
}

/// v1s-16-15: the "Bloquées" list and its actions.
void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    registerFallbackValue(SyncQueueData(
        id: '',
        operation: '',
        payload: '{}',
        createdAt: DateTime(2026),
        synced: false,
        retryCount: 0));
  });

  late _MockQuarantine service;
  late List<String> shared;
  late bool shareSucceeds;

  final sale = SyncQueueData(
    id: 'sale-op',
    operation: 'CREATE_SALE',
    payload: jsonEncode({
      'saleId': 'sale-1',
      'storeId': 'store-1',
      'items': [
        {'productId': 'p-1', 'productName': 'Savon', 'quantity': 2,
         'appliedUnitPrice': 500},
      ],
    }),
    createdAt: DateTime(2026, 10, 11, 9, 5),
    synced: false,
    retryCount: 1,
    entityId: 'sale-1',
    lastError: 'PRODUCT_NOT_FOUND',
    quarantinedAt: DateTime(2026, 10, 11, 10),
  );
  final validation = SyncQueueData(
    id: 'validate-op',
    operation: 'VALIDATE_SALE',
    payload: '{}',
    createdAt: DateTime(2026, 10, 11, 9, 6),
    synced: false,
    retryCount: 0,
    entityId: 'sale-1',
    quarantinedAt: DateTime(2026, 10, 11, 10),
  );

  setUp(() {
    service = _MockQuarantine();
    shared = [];
    shareSucceeds = false;
    when(() => service.storeLabel(any()))
        .thenAnswer((_) async => 'Boutique Centre');
    when(() => service.saleTotal(any())).thenAnswer((_) async => 1000);
    when(() => service.exportText(any())).thenAnswer((_) async => 'EXPORT');
    when(() => service.abandon(any())).thenAnswer((_) async => true);
    when(() => service.release(any())).thenAnswer((_) async {});
    when(() => service.reattachSale(any(),
        productIds: any(named: 'productIds'),
        storeId: any(named: 'storeId'))).thenAnswer((_) async {});
    when(() => service.reattachableProducts()).thenAnswer((_) async => [
          Product(
              id: 'p-real',
              name: 'Savon de Marseille',
              sku: '',
              price: 500,
              buyPrice: 0,
              transportCost: 0,
              stockQuantity: 0,
              archived: false,
              status: 'ACTIVE',
              createdAt: DateTime(2026),
              updatedAt: DateTime(2026)),
        ]);
    when(() => service.reattachableStores()).thenAnswer((_) async => []);
    _FakeTrigger.syncs = 0;
  });

  Widget build(String role) => ProviderScope(
        overrides: [
          quarantinedSyncQueueProvider
              .overrideWith((ref) => Future.value([sale, validation])),
          quarantinedSyncCountProvider.overrideWith((ref) => Future.value(2)),
          pendingSyncQueueProvider.overrideWith((ref) => Future.value([])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(0)),
          syncHistoryProvider.overrideWith((ref) => Future.value([])),
          syncQuarantineServiceProvider.overrideWithValue(service),
          syncTriggerNotifierProvider.overrideWith(_FakeTrigger.new),
          currentUserRoleProvider.overrideWithValue(role),
          quarantineExportShareProvider.overrideWithValue((text) async {
            shared.add(text);
            return shareSucceeds;
          }),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: ListView(
              children: const [QuarantinedOperationsSection(standalone: true)],
            ),
          ),
        ),
      );

  testWidgets('OWNER: one card per chain with the four actions',
      (tester) async {
    await tester.pumpWidget(build('OWNER'));
    await tester.pumpAndSettle();

    expect(find.text('Bloquées (1)'), findsOneWidget,
        reason: 'the validation is shown under its sale');
    expect(find.text('Enregistrement de vente'), findsOneWidget);
    expect(find.text('Motif : Produit introuvable'), findsOneWidget);
    expect(find.text('1 article — 1 000 FCFA'), findsOneWidget);
    expect(find.text('Boutique : Boutique Centre'), findsOneWidget);
    expect(find.text('Avec : Validation de vente'), findsOneWidget);
    for (final action in ['Renvoyer', 'Exporter', 'Rattacher', 'Abandonner']) {
      expect(find.text(action), findsOneWidget, reason: action);
    }
  });

  testWidgets('EMPLOYEE: Renvoyer and Exporter only', (tester) async {
    await tester.pumpWidget(build('EMPLOYEE'));
    await tester.pumpAndSettle();

    expect(find.text('Renvoyer'), findsOneWidget);
    expect(find.text('Exporter'), findsOneWidget);
    expect(find.text('Rattacher'), findsNothing);
    expect(find.text('Abandonner'), findsNothing);
  });

  testWidgets('Abandonner with the share sheet dismissed deletes nothing',
      (tester) async {
    await tester.pumpWidget(build('OWNER'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Abandonner'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Exporter et abandonner'));
    await tester.pumpAndSettle();

    expect(shared, ['EXPORT']);
    verifyNever(() => service.abandon(any()));
    expect(find.text('Exportez la vente avant de l\'abandonner'),
        findsOneWidget);
  });

  testWidgets('Abandonner after a successful export abandons the chain',
      (tester) async {
    shareSucceeds = true;
    await tester.pumpWidget(build('OWNER'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Abandonner'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Exporter et abandonner'));
    await tester.pumpAndSettle();

    verify(() => service.abandon('sale-op')).called(1);
    expect(find.text('Opération abandonnée'), findsOneWidget);
  });

  testWidgets('Abandonner cancelled in the dialog exports nothing',
      (tester) async {
    await tester.pumpWidget(build('OWNER'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Abandonner'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();

    expect(shared, isEmpty);
    verifyNever(() => service.abandon(any()));
  });

  testWidgets('Renvoyer releases the chain and triggers a sync',
      (tester) async {
    await tester.pumpWidget(build('EMPLOYEE'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Renvoyer'));
    await tester.pumpAndSettle();

    verify(() => service.release('sale-op')).called(1);
    expect(_FakeTrigger.syncs, 1);
  });

  testWidgets('Rattacher: picking a product and saving re-attaches the line',
      (tester) async {
    await tester.pumpWidget(build('OWNER'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Rattacher'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Savon × 2'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Savon de Marseille'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Rattacher et renvoyer'));
    await tester.pumpAndSettle();

    verify(() => service.reattachSale('sale-op',
        productIds: {'p-1': 'p-real'}, storeId: null)).called(1);
    expect(_FakeTrigger.syncs, 1);
  });
}
