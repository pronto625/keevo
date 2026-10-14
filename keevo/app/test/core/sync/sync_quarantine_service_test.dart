import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show OrderingTerm, Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
import 'package:keevo/core/sync/sync_quarantine_service.dart';
import 'package:sqlite3/open.dart';

import '../../helpers/no_technical_text.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// v1s-16-15: actions on quarantined ("bloquées") money operations.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late SyncQuarantineService service;
  final now = DateTime(2026, 10, 11, 14, 30);

  setUp(() {
    db = AppDatabase.forTesting();
    service = SyncQuarantineService(db, SyncEventLogger(db));
  });

  tearDown(() => db.close());

  var clock = DateTime(2026, 10, 1);
  Future<void> op(
    String id,
    String operation, {
    String? entityId,
    Map<String, dynamic> payload = const {},
    bool quarantined = true,
    String? lastError = 'PRODUCT_NOT_FOUND',
  }) {
    clock = clock.add(const Duration(seconds: 1));
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: jsonEncode(payload),
          createdAt: clock,
          entityId: Value(entityId),
          lastError: Value(lastError),
          quarantinedAt: Value(quarantined ? now : null),
        ));
  }

  Future<SyncQueueData?> row(String id) =>
      (db.select(db.syncQueue)..where((t) => t.id.equals(id)))
          .getSingleOrNull();

  final salePayload = {
    'saleId': 'sale-1',
    'storeId': 'store-ghost',
    'paymentMode': 'CASH',
    'discountAmount': 0,
    'items': [
      {
        'itemId': 'i-1',
        'productId': 'p-ghost',
        'variantId': 'v-ghost',
        'productName': 'Savon',
        'appliedUnitPrice': 500,
        'quantity': 2,
      },
      {
        'itemId': 'i-2',
        'productId': 'p-ok',
        'variantId': null,
        'productName': 'Riz',
        'appliedUnitPrice': 12000,
        'quantity': 1,
      },
    ],
  };

  Future<void> movement(String id, String productId, {bool synced = false}) =>
      db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: id,
          productId: productId,
          storeId: 'store-ghost',
          type: 'SALE',
          quantityDelta: -1,
          actorId: 'u',
          synced: Value(synced),
          createdAt: now));

  Future<void> product(String id, String name,
          {bool archived = false, String status = 'ACTIVE'}) =>
      db.into(db.products).insert(ProductsCompanion.insert(
          id: id,
          name: name,
          archived: Value(archived),
          status: Value(status),
          createdAt: now,
          updatedAt: now));

  Future<void> localSale() async {
    await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-1',
        storeId: 'store-ghost',
        employeeId: 'u',
        totalAmount: 13000,
        paymentMode: 'CASH',
        createdAt: now));
    for (final (id, productId, variantId, name, price, qty) in const [
      ('i-1', 'p-ghost', 'v-ghost', 'Savon', 500, 2),
      ('i-2', 'p-ok', null, 'Riz', 12000, 1),
    ]) {
      await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
          id: id,
          saleId: 'sale-1',
          productId: productId,
          variantId: Value(variantId),
          productName: name,
          unitPrice: price,
          quantity: qty,
          subtotal: price * qty,
          createdAt: now));
    }
  }

  test('the abandon trace survives the 50-event rotation', () async {
    final logger = SyncEventLogger(db);
    await logger.logOperationAbandoned('Vente du 01/10');
    for (var i = 0; i < 60; i++) {
      await logger.logPushSuccess(i);
    }

    final events = await db.select(db.syncEvents).get();
    expect(events.where((e) => e.type == kSyncEventAbandon), hasLength(1));
    expect(events.where((e) => e.type == 'PUSH'), hasLength(50));
  });

  test('schema 28: sync_queue has quarantined_at', () async {
    expect(db.schemaVersion, greaterThanOrEqualTo(28));
    final columns = await db
        .customSelect("SELECT name FROM pragma_table_info('sync_queue')")
        .get();
    expect(columns.map((c) => c.read<String>('name')),
        contains('quarantined_at'));
  });

  test('chainOf: the sale first, then its chained ops — from any member',
      () async {
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1');
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1');
    await op('pending-cancel', 'CANCEL_SALE',
        entityId: 'sale-1', quarantined: false);

    expect((await service.chainOf('sale-op')).map((o) => o.id),
        ['sale-op', 'validate-op']);
    expect((await service.chainOf('validate-op')).map((o) => o.id),
        ['sale-op', 'validate-op']);
    expect(await service.chainOf('pending-cancel'), isEmpty,
        reason: 'not quarantined');
  });

  test('release clears the quarantine on the whole chain only', () async {
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1');
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1');
    await op('other', 'STOCK_ADJUST', entityId: 'sl-1');

    await service.release('sale-op');

    expect((await row('sale-op'))!.quarantinedAt, isNull);
    expect((await row('validate-op'))!.quarantinedAt, isNull);
    expect((await row('other'))!.quarantinedAt, isNotNull);
    expect((await row('sale-op'))!.lastError, 'PRODUCT_NOT_FOUND',
        reason: 'kept for the queue screen until the next answer');
  });

  test(
      'reattachSale rewrites the queued payload and the local rows, then '
      'releases the chain', () async {
    await localSale();
    await product('p-real', 'Savon de Marseille');
    await movement('i-1', 'p-ghost'); // the sale's movement (item id)
    await movement('m-other', 'p-ghost');
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1');

    await service.reattachSale('validate-op',
        productIds: {'p-ghost': 'p-real'}, storeId: 'store-real');

    final payload =
        jsonDecode((await row('sale-op'))!.payload) as Map<String, dynamic>;
    final items = (payload['items'] as List).cast<Map<String, dynamic>>();
    expect(payload['storeId'], 'store-real');
    expect(items[0]['productId'], 'p-real');
    expect(items[0].containsKey('variantId'), false);
    expect(items[0]['productName'], 'Savon de Marseille');
    expect(items[1]['productId'], 'p-ok', reason: 'unmapped line untouched');
    expect(items[1]['productName'], 'Riz');

    final saleItems = await (db.select(db.saleItems)
          ..orderBy([(t) => OrderingTerm.asc(t.id)]))
        .get();
    expect(saleItems.map((i) => (i.productId, i.variantId, i.productName)),
        [('p-real', null, 'Savon de Marseille'), ('p-ok', null, 'Riz')]);
    final movements = {
      for (final m in await db.select(db.stockMovements).get())
        m.id: m.productId,
    };
    expect(movements, {'i-1': 'p-real', 'm-other': 'p-ghost'});
    expect((await db.select(db.sales).getSingle()).storeId, 'store-real');
    expect((await row('sale-op'))!.quarantinedAt, isNull);
    expect((await row('validate-op'))!.quarantinedAt, isNull);
  });

  // v1s-16-15 follow-up: the chained validation must not keep naming the
  // ghost product (draft remapping / initial stock), or the server refuses
  // it again and the chain goes straight back to quarantine.
  test('reattachSale strips the ghost product from the chained validation',
      () async {
    await localSale();
    await product('p-real', 'Savon de Marseille');
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1', payload: {
      'saleId': 'sale-1',
      'justification': 'ok',
      'productIdRemappings': {'p-ghost': 'p-ghost-promoted', 'p-x': 'p-y'},
      'initialStockEntries': {'p-ghost-promoted': 5, 'p-ghost': 3, 'p-y': 2},
    });

    await service.reattachSale('sale-op', productIds: {'p-ghost': 'p-real'});

    final validate =
        jsonDecode((await row('validate-op'))!.payload) as Map<String, dynamic>;
    expect(validate['productIdRemappings'], {'p-x': 'p-y'});
    expect(validate['initialStockEntries'], {'p-y': 2});
    expect(validate['justification'], 'ok');
    expect((await row('validate-op'))!.quarantinedAt, isNull);
  });

  test('reattachSale: a validation naming only the ghost loses both maps',
      () async {
    await localSale();
    await product('p-real', 'Savon de Marseille');
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1', payload: {
      'saleId': 'sale-1',
      'initialStockEntries': {'p-ghost': 4},
    });

    await service.reattachSale('validate-op',
        productIds: {'p-ghost': 'p-real'});

    final validate =
        jsonDecode((await row('validate-op'))!.payload) as Map<String, dynamic>;
    expect(validate, {'saleId': 'sale-1'});
  });

  test(
      'reattachSale: a remapping whose value is the re-attached product is '
      'dropped with its initial stock; a non-quarantined validation too',
      () async {
    await localSale();
    await product('p-real', 'Savon de Marseille');
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    // Queued after the quarantine, not yet joined to the chain.
    await op('validate-op', 'VALIDATE_SALE',
        entityId: 'sale-1',
        quarantined: false,
        lastError: null,
        payload: {
          'saleId': 'sale-1',
          'productIdRemappings': {'p-draft': 'p-ghost', 'p-x': 'p-y'},
          'initialStockEntries': {'p-ghost': 4, 'p-y': 2},
        });

    await service.reattachSale('sale-op', productIds: {'p-ghost': 'p-real'});

    final validate =
        jsonDecode((await row('validate-op'))!.payload) as Map<String, dynamic>;
    expect(validate['productIdRemappings'], {'p-x': 'p-y'});
    expect(validate['initialStockEntries'], {'p-y': 2});
  });

  test('reattachSale refuses anything but a sale', () async {
    await op('closure-op', 'CREATE_DAY_CLOSURE', entityId: 'dc-1');

    expect(() => service.reattachSale('closure-op', storeId: 's'),
        throwsStateError);
    expect((await row('closure-op'))!.quarantinedAt, isNotNull);
  });

  test(
      'abandon deletes the chain, the local sale and its items, and logs '
      '"Opération abandonnée"', () async {
    await localSale();
    await movement('i-1', 'p-ghost');
    await movement('i-2', 'p-ok');
    await movement('m-other', 'p-ok');
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('cancel-op', 'CANCEL_SALE', entityId: 'sale-1');
    await op('pending-validate', 'VALIDATE_SALE',
        entityId: 'sale-1', quarantined: false, lastError: null);
    await op('other', 'CREATE_SALE', entityId: 'sale-2');

    expect(await service.abandon('sale-op'), true);

    expect((await db.select(db.syncQueue).get()).map((o) => o.id), ['other'],
        reason: 'pending ops of the abandoned sale would reach the server '
            'orphaned');
    expect(await db.select(db.sales).get(), isEmpty);
    expect(await db.select(db.saleItems).get(), isEmpty);
    expect((await db.select(db.stockMovements).get()).map((m) => m.id),
        ['m-other']);
    expect(await service.abandon('sale-op'), false,
        reason: 'already gone: nothing done');
    final events = await db.select(db.syncEvents).get();
    expect(events, hasLength(1));
    expect(events.single.type, kSyncEventAbandon);
    expect(events.single.errorMessage, contains('Enregistrement de vente'));
    expect(events.single.errorMessage, contains('13 000 XAF'));
  });

  test('abandon of stock ops deletes their unsynced movement / transfer',
      () async {
    await movement('mov-adjust', 'p-1');
    await movement('mov-entry', 'p-1');
    await movement('mov-synced', 'p-1', synced: true);
    await db.into(db.stockTransfers).insert(StockTransfersCompanion.insert(
        id: 'tr-1',
        sourceStoreId: 's-1',
        destinationStoreId: 's-2',
        productId: 'p-1',
        quantity: 1,
        actorId: 'u',
        occurredAt: now));
    await op('adjust', 'STOCK_ADJUST', entityId: 'mov-adjust');
    await op('entry', 'RECORD_STOCK_ENTRY', entityId: 'mov-entry');
    await op('synced-entry', 'RECORD_STOCK_ENTRY', entityId: 'mov-synced');
    await op('transfer', 'STOCK_TRANSFER', entityId: 'tr-1');

    for (final id in ['adjust', 'entry', 'synced-entry', 'transfer']) {
      expect(await service.abandon(id), true);
    }

    expect((await db.select(db.stockMovements).get()).map((m) => m.id),
        ['mov-synced']);
    expect(await db.select(db.stockTransfers).get(), isEmpty);
    expect(await db.select(db.syncQueue).get(), isEmpty);
  });

  test(
      'reattachable products / stores exclude archived, drafts, inactive and '
      'entities whose creation is still queued', () async {
    await product('p-ok', 'B');
    await product('p-archived', 'A', archived: true);
    await product('p-draft', 'C', status: 'DRAFT');
    await product('p-pending', 'D');
    for (final (id, active) in const [
      ('s-ok', true),
      ('s-off', false),
      ('s-pending', true),
    ]) {
      await db.into(db.stores).insert(StoresCompanion.insert(
          id: id,
          name: id,
          tenantId: 't',
          isActive: Value(active),
          createdAt: now,
          updatedAt: now));
    }
    await op('cp', 'CREATE_PRODUCT', entityId: 'p-pending', quarantined: false);
    await op('cs', 'CREATE_STORE', entityId: 's-pending', quarantined: false);

    expect((await service.reattachableProducts()).map((p) => p.id), ['p-ok']);
    expect((await service.reattachableStores()).map((s) => s.id), ['s-ok']);
  });

  test('abandon of a day closure deletes the unsynced local closure', () async {
    for (final (id, synced) in const [('dc-1', false), ('dc-2', true)]) {
      await db.into(db.dayClosures).insert(DayClosuresCompanion.insert(
          id: id,
          storeId: 'store-1',
          actorId: 'u',
          closedAt: now,
          totalSales: 0,
          totalRevenue: 0,
          cashAmount: 0,
          momoAmount: 0,
          pendingSalesCount: 0,
          pendingSalesTotal: 0,
          synced: Value(synced),
          createdAt: now));
    }
    await op('closure-op', 'CREATE_DAY_CLOSURE',
        entityId: 'dc-1', lastError: 'STORE_NOT_FOUND');

    await service.abandon('closure-op');

    expect(await db.select(db.syncQueue).get(), isEmpty);
    expect((await db.select(db.dayClosures).get()).map((c) => c.id), ['dc-2']);
  });

  test('exportText describes the sale in French, without raw payload (v1s-16-20)',
      () async {
    await db.into(db.stores).insert(StoresCompanion.insert(
        id: 'store-ghost',
        name: 'Boutique Centre',
        tenantId: 't',
        createdAt: now,
        updatedAt: now));
    await localSale();
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('validate-op', 'VALIDATE_SALE', entityId: 'sale-1');

    final text = await service.exportText('sale-op');

    expect(text, contains('Opération : Enregistrement de vente'));
    expect(text, contains('Motif du refus : Produit introuvable'));
    expect(text, contains('Boutique : Boutique Centre'));
    expect(text, contains('- Savon : 2 × 500 XAF = 1 000 XAF'));
    expect(text, contains('- Riz : 1 × 12 000 XAF = 12 000 XAF'));
    expect(text, contains('Total : 13 000 XAF'));
    expect(text, contains('Paiement : Espèces'));
    expect(text, contains('Opérations liées :'));
    expect(text, contains('- Validation de vente du '));
    expect(text, isNot(contains('Données brutes')));
    expect(text, isNot(contains('sale-op')));
    expect(text, isNot(contains('p-ghost')));
    expect(text, isNot(contains('CASH')));
    expectNoTechnicalString(text);
  });

  test('exportText: unnamed line with an unknown product, unknown payment mode',
      () async {
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: {
      'saleId': 'sale-1',
      'storeId': '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f',
      'paymentMode': 'BANK_CARD',
      'items': [
        {
          'productId': '0b1c2d3e-2b9a-4c1e-9d0f-0a1b2c3d4e5f',
          'appliedUnitPrice': 500,
          'quantity': 1,
        },
      ],
    });

    final text = await service.exportText('sale-op');

    expect(text, contains('- Produit inconnu sur cet appareil : 1 × 500 XAF'));
    expect(text, contains('Boutique : Boutique inconnue sur cet appareil'));
    expect(text, contains('Paiement : Bank card'));
    expectNoTechnicalString(text);
  });

  test('exportText of a stock op names the local product, or says unknown',
      () async {
    await product('p-1', 'Huile 1L');
    await op('adjust', 'STOCK_ADJUST',
        entityId: 'm-1', payload: {'productId': 'p-1', 'quantity': 3});
    await op('entry', 'RECORD_STOCK_ENTRY',
        entityId: 'm-2', payload: {'productId': 'p-gone', 'quantity': 1});

    expect(await service.exportText('adjust'), contains('Produit : Huile 1L'));
    expect(await service.exportText('entry'),
        contains('Produit : Produit inconnu sur cet appareil'));
    expect(await service.exportText('entry'), isNot(contains('p-gone')));
  });

  test('exportText names an unknown store and is empty when not quarantined',
      () async {
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    await op('pending', 'CREATE_SALE', entityId: 'sale-2', quarantined: false);

    expect(await service.exportText('sale-op'),
        contains('Boutique inconnue sur cet appareil'));
    expect(await service.exportText('sale-op'), isNot(contains('store-ghost')));
    expect(await service.exportText('pending'), isEmpty);
  });

  // J26: the blocked card's one-line summary (product × quantity, route).
  test('productSummary / transferRoute: names, never ids', () async {
    await product('p-1', 'Huile 1L');
    await op('tr', 'STOCK_TRANSFER', entityId: 't-1', payload: {
      'sourceStoreId': 'st-gone',
      'destinationStoreId': 'st-gone-2',
      'productId': 'p-1',
      'quantity': 4,
    });
    await op('sale-op', 'CREATE_SALE', entityId: 'sale-1', payload: salePayload);
    final rows = await db.select(db.syncQueue).get();
    final tr = rows.firstWhere((r) => r.id == 'tr');
    final sale = rows.firstWhere((r) => r.id == 'sale-op');

    expect(await service.productSummary(tr), 'Huile 1L × 4');
    expect(await service.transferRoute(tr),
        'Boutique inconnue sur cet appareil → Boutique inconnue sur cet appareil');
    expect(await service.productSummary(sale), isNull);
    expect(await service.transferRoute(sale), isNull);
  });
}
