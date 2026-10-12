import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/export/data/local/local_export_reader.dart';
import 'package:keevo/features/export/domain/local/local_export_schema.dart';

import '../export_test_helpers.dart';

/// v1s-18-5 — the reader pages each dataset out of Drift with the server
/// providers' columns, derivations, filters, ordering and French labels.
void main() {
  late AppDatabase db;
  late LocalExportReader reader;

  final t0 = DateTime.utc(2026, 10, 1, 8);
  DateTime day(int d, [int h = 10]) => DateTime.utc(2026, 10, d, h);

  setUpAll(overrideSqliteForLinuxTests);

  setUp(() async {
    db = AppDatabase.forTesting();
    reader = LocalExportReader(db);
  });

  tearDown(() => db.close());

  Future<List<List<Object?>>> rows(String key,
      [LocalExportFilter filter = const LocalExportFilter(),
      int pageSize = 500]) async {
    final all = <List<Object?>>[];
    await for (final page in reader.pages(key, filter, pageSize: pageSize)) {
      expect(page.length, lessThanOrEqualTo(pageSize));
      all.addAll(page);
    }
    return all;
  }

  Map<String, Object?> named(String key, List<Object?> row) {
    final cols = localExportDataset(key)!.columns;
    expect(row, hasLength(cols.length), reason: key);
    return {for (var i = 0; i < cols.length; i++) cols[i].key: row[i]};
  }

  Future<void> seedPeople() async {
    for (final (id, name) in [('s-A', 'Boutique A'), ('s-B', 'Boutique B')]) {
      await db.into(db.stores).insert(StoresCompanion.insert(
          id: id,
          name: name,
          tenantId: 't',
          createdAt: id == 's-A' ? t0 : t0.add(const Duration(hours: 1)),
          updatedAt: t0,
          address: const Value('Akwa'),
          phone: const Value('+237 699')));
    }
    await db.into(db.users).insert(UsersCompanion.insert(
        id: 'u-owner',
        phoneNumber: '+237600',
        tenantId: 't',
        role: 'OWNER',
        createdAt: t0));
    await db.into(db.users).insert(UsersCompanion.insert(
        id: 'u-emp',
        phoneNumber: '+237611',
        tenantId: 't',
        role: 'EMPLOYEE',
        createdAt: t0));
    await db.into(db.employees).insert(EmployeesCompanion.insert(
        id: 'e-1',
        userId: 'u-emp',
        firstName: 'Awa',
        lastName: 'Diallo',
        storeId: 's-A',
        createdAt: t0));
  }

  test('counts: local rows per dataset, 0 when empty', () async {
    await seedPeople();
    final counts = await reader.counts();
    expect(counts.keys, [for (final d in localExportCatalog) d.key]);
    expect(counts['stores'], 2);
    expect(counts['employees'], 1);
    expect(counts['reports'], 0);
    expect(await rows('reports'), isEmpty);
  });

  test('products: category, labels, stock sum (store-scoped), suppliers',
      () async {
    await seedPeople();
    await db.into(db.categories).insert(CategoriesCompanion.insert(
        id: 'c-1', name: 'Tissus', createdAt: t0, updatedAt: t0));
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-2',
        name: 'Second',
        createdAt: day(5),
        updatedAt: day(5),
        status: const Value('WEIRD')));
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-1',
        name: '=SUM(A1)',
        sku: const Value('PG-1'),
        categoryId: const Value('c-1'),
        price: const Value(1500),
        buyPrice: const Value(1000),
        transportCost: const Value(50),
        archived: const Value(true),
        status: const Value('DRAFT'),
        createdAt: DateTime.utc(2026, 10, 2, 23, 30),
        updatedAt: day(3)));
    for (final (id, store, q) in [('l1', 's-A', 3), ('l2', 's-B', 4)]) {
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: id,
          productId: 'p-1',
          storeId: store,
          quantity: q,
          updatedAt: t0));
    }
    for (final (id, name) in [('sup-1', 'Zed'), ('sup-2', 'Alpha')]) {
      await db.into(db.suppliers).insert(SuppliersCompanion.insert(
          id: id, name: name, phone: '1', createdAt: t0, updatedAt: t0));
      await db.into(db.productSuppliers).insert(
          ProductSuppliersCompanion.insert(supplierId: id, productId: 'p-1'));
    }

    final all = await rows('products');
    expect(all.map((r) => r[0]), ['p-1', 'p-2']); // createdAt order
    final p1 = named('products', all[0]);
    expect(p1['sku'], 'PG-1');
    expect(p1['name'], '=SUM(A1)');
    expect(p1['category'], 'Tissus');
    expect(p1['status'], 'Brouillon');
    expect(p1['salePrice'], 1500);
    expect(p1['buyPrice'], 1000);
    expect(p1['transportCost'], 50);
    expect(p1['minimumThreshold'], isNull);
    expect(p1['stockQuantity'], 7);
    expect(p1['suppliers'], 'Alpha, Zed');
    expect(p1['archived'], true);
    expect((p1['createdAt'] as DateTime).toUtc(),
        DateTime.utc(2026, 10, 2, 23, 30));
    final p2 = named('products', all[1]);
    expect(p2['status'], 'WEIRD'); // unknown code → raw code
    expect(p2['stockQuantity'], 0);
    expect(p2['suppliers'], isNull);
    expect(p2['category'], isNull);

    // Store filter narrows the stock column only; the period is ignored.
    final scoped = await rows('products',
        LocalExportFilter(storeId: 's-A', from: day(20), to: day(21)));
    expect(scoped, hasLength(2));
    expect(named('products', scoped[0])['stockQuantity'], 3);
  });

  test('categories: parent name', () async {
    await db.into(db.categories).insert(CategoriesCompanion.insert(
        id: 'c-1', name: 'Tissus', createdAt: t0, updatedAt: t0));
    await db.into(db.categories).insert(CategoriesCompanion.insert(
        id: 'c-2',
        name: 'Wax',
        parentId: const Value('c-1'),
        isCustom: const Value(true),
        createdAt: day(2),
        updatedAt: t0));
    final all = await rows('categories');
    expect(named('categories', all[1]), {
      'id': 'c-2',
      'name': 'Wax',
      'parent': 'Tissus',
      'active': true,
      'custom': true,
      'createdAt': all[1][5]
    });
  });

  test('stock_levels: order, threshold, state, value, store filter', () async {
    await seedPeople();
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-1',
        name: 'Pagne',
        buyPrice: const Value(1000),
        createdAt: t0,
        updatedAt: t0));
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-2', name: 'Ananas', createdAt: t0, updatedAt: t0));
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'l1',
        productId: 'p-1',
        storeId: 's-B',
        quantity: 3,
        minimumThreshold: const Value(5),
        updatedAt: t0));
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'l2',
        productId: 'p-1',
        storeId: 's-A',
        quantity: 0,
        updatedAt: t0));
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'l3',
        productId: 'p-2',
        storeId: 's-A',
        quantity: 9,
        updatedAt: t0));
    final all = await rows('stock_levels');
    // store name, product name, id
    expect(all.map((r) => '${r[1]}/${r[4]}'),
        ['Boutique A/Ananas', 'Boutique A/Pagne', 'Boutique B/Pagne']);
    expect(named('stock_levels', all[0])['stockStatus'], 'OK');
    final out = named('stock_levels', all[1]);
    expect(out['stockStatus'], 'Rupture');
    expect(out['stockValue'], 0);
    final low = named('stock_levels', all[2]);
    expect(low['minimumThreshold'], 5);
    expect(low['stockStatus'], 'Stock bas');
    expect(low['stockValue'], 3000);
    expect(low['productArchived'], false);
    expect(await rows('stock_levels', const LocalExportFilter(storeId: 's-B')),
        hasLength(1));
  });

  test('stock_movements: labels, actor, period on createdAt', () async {
    await seedPeople();
    for (final (id, d, actor) in [
      ('m-1', day(2), 'u-emp'),
      ('m-2', day(5), 'u-owner'),
      ('m-3', day(9), 'u-ghost'),
    ]) {
      await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: id,
          productId: 'p-x',
          storeId: 's-A',
          type: id == 'm-1' ? 'SALE_CANCELLED' : 'ADJUSTMENT',
          quantityBefore: const Value(10),
          quantityDelta: -2,
          quantityAfter: const Value(8),
          actorId: actor,
          reason: const Value('casse'),
          createdAt: d));
    }
    final all = await rows('stock_movements');
    final m1 = named('stock_movements', all[0]);
    expect(m1['type'], 'Annulation de vente');
    expect(m1['actor'], 'Awa Diallo');
    expect(m1['quantityChange'], -2);
    expect(m1['notes'], 'casse');
    expect(named('stock_movements', all[1])['actor'], 'Propriétaire');
    expect(named('stock_movements', all[2])['actor'], isNull);
    final period = await rows(
        'stock_movements', LocalExportFilter(from: day(5), to: day(9)));
    expect(period.map((r) => r[0]), ['m-2', 'm-3']); // inclusive bounds
  });

  test('stock_transfers: source OR destination store, snapshot fallback',
      () async {
    await seedPeople();
    await db.into(db.stockTransfers).insert(StockTransfersCompanion.insert(
        id: 't-1',
        sourceStoreId: 's-A',
        destinationStoreId: 's-gone',
        destinationStoreName: const Value('Ancienne'),
        productId: 'p-gone',
        productName: const Value('Pagne (snap)'),
        quantity: 2,
        actorId: 'u-owner',
        status: const Value('IN_TRANSIT'),
        occurredAt: day(3)));
    await db.into(db.stockTransfers).insert(StockTransfersCompanion.insert(
        id: 't-2',
        sourceStoreId: 's-B',
        destinationStoreId: 's-A',
        productId: 'p-gone',
        quantity: 1,
        actorId: 'u-emp',
        occurredAt: day(4)));
    final t1 = named('stock_transfers', (await rows('stock_transfers'))[0]);
    expect(t1['sourceStore'], 'Boutique A');
    expect(t1['destinationStore'], 'Ancienne');
    expect(t1['product'], 'Pagne (snap)');
    expect(t1['status'], 'En transit');
    expect(t1['actor'], 'Propriétaire');
    expect(
        await rows('stock_transfers', const LocalExportFilter(storeId: 's-A')),
        hasLength(2));
    expect(
        await rows('stock_transfers', const LocalExportFilter(storeId: 's-B')),
        hasLength(1));
  });

  Future<void> seedSales() async {
    await db.into(db.clients).insert(ClientsCompanion.insert(
        id: 'cl-1',
        name: 'Mme Ngo',
        phone: '+237 6',
        createdAt: t0,
        updatedAt: t0));
    // s-1: A, old; s-2: B; s-3: A, recent, cancelled; s-4: A, no status.
    Future<void> sale(
        String id, String store, DateTime? occurred, DateTime created,
        {String? status = 'COMPLETED', String pay = 'CASH'}) {
      return db.into(db.sales).insert(SalesCompanion.insert(
          id: id,
          storeId: store,
          employeeId: 'u-emp',
          totalAmount: 5000,
          discountAmount: const Value(200),
          paymentMode: pay,
          clientId: const Value('cl-1'),
          status: Value(status),
          occurredAt: Value(occurred),
          createdAt: created));
    }

    await sale('s-1', 's-A', day(1), day(1, 12));
    await sale('s-2', 's-B', day(8), day(8));
    await sale('s-3', 's-A', day(8, 11), day(8, 11),
        status: 'CANCELLED', pay: 'MOBILE_MONEY');
    await sale('s-4', 's-A', null, day(9), status: null);
    for (final (id, saleId, q) in [
      ('i-1', 's-1', 2),
      ('i-2', 's-1', 3),
      ('i-3', 's-3', 1)
    ]) {
      await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
          id: id,
          saleId: saleId,
          productId: 'p-1',
          productName: 'Pagne',
          unitPrice: 900,
          catalogueUnitPrice: const Value(1000),
          quantity: q,
          subtotal: 900 * q,
          createdAt: t0));
    }
  }

  test('sales: labels, item count, status null, store + period filters',
      () async {
    await seedPeople();
    await seedSales();
    final all = await rows('sales');
    expect(all.map((r) => r[0]), ['s-1', 's-2', 's-3', 's-4']);
    final s1 = named('sales', all[0]);
    expect(s1['store'], 'Boutique A');
    expect(s1['employee'], 'Awa Diallo');
    expect(s1['client'], 'Mme Ngo');
    expect(s1['status'], 'Validée');
    expect(s1['paymentMode'], 'Espèces');
    expect(s1['itemCount'], 5);
    expect(s1['discount'], 200);
    expect(s1['total'], 5000);
    final s3 = named('sales', all[2]);
    expect(s3['status'], 'Annulée');
    expect(s3['paymentMode'], 'Mobile Money');
    final s4 = named('sales', all[3]);
    expect(s4['status'], isNull);
    expect((s4['occurredAt'] as DateTime).toUtc(), day(9)); // createdAt
    // Store A within the last days: only s-3 and s-4.
    final scoped = await rows(
        'sales', LocalExportFilter(storeId: 's-A', from: day(2), to: day(9)));
    expect(scoped.map((r) => r[0]), ['s-3', 's-4']);
  });

  test('sale_items: through the sale (date, store, status)', () async {
    await seedPeople();
    await seedSales();
    final all = await rows('sale_items');
    expect(all.map((r) => r[0]), ['s-1', 's-1', 's-3']);
    final i3 = named('sale_items', all[2]);
    expect(i3['saleStatus'], 'Annulée');
    expect(i3['employee'], 'Awa Diallo');
    expect(i3['product'], 'Pagne');
    expect(i3['cataloguePrice'], 1000);
    expect(i3['unitPrice'], 900);
    expect(i3['subtotal'], 900);
    expect(
        await rows('sale_items', LocalExportFilter(from: day(2), to: day(31))),
        hasLength(1));
  });

  test('clients: aggregates over local COMPLETED sales only', () async {
    await seedPeople();
    await seedSales();
    final c = named('clients', (await rows('clients')).single);
    expect(c['salesCount'], 2); // s-1, s-2 (s-3 cancelled, s-4 null)
    expect(c['totalSpent'], 10000);
    expect((c['lastPurchaseAt'] as DateTime).toUtc(), day(8));
    expect(c['phone'], '+237 6');
    expect(c['archived'], false);
  });

  test('day_closures: closure date, actor, figures', () async {
    await seedPeople();
    await db.into(db.dayClosures).insert(DayClosuresCompanion.insert(
        id: 'd-1',
        storeId: 's-A',
        actorId: 'u-owner',
        closedAt: DateTime.utc(2026, 10, 12, 23, 30),
        totalSales: 4,
        totalRevenue: 20000,
        cashAmount: 15000,
        momoAmount: 5000,
        pendingSalesCount: 1,
        pendingSalesTotal: 3000,
        topProductName: const Value('Pagne'),
        topProductQty: const Value(3),
        isAutomatic: const Value(true),
        createdAt: t0));
    final d = named('day_closures', (await rows('day_closures')).single);
    expect(d['actor'], 'Propriétaire');
    expect(d['automatic'], true);
    expect(d['revenue'], 20000);
    expect(d['momoAmount'], 5000);
    expect(d['topProductQuantity'], 3);
    expect((d['closureDate'] as DateTime).toUtc(),
        DateTime.utc(2026, 10, 12, 23, 30)); // DATE cell → 13/10/2026 WAT
  });

  test('inventory_sessions / inventory_counts: aggregates, gap, labels',
      () async {
    await seedPeople();
    await db.into(db.inventorySessions).insert(
        InventorySessionsCompanion.insert(
            id: 'inv-1',
            storeId: 's-A',
            scope: 'PARTIAL',
            status: 'VALIDATED',
            startedBy: 'u-emp',
            startedAt: day(3),
            updatedAt: day(3)));
    for (final (id, theo, phys) in [
      ('c-1', 5, 5),
      ('c-2', 4, 1),
      ('c-3', 2, null)
    ]) {
      await db.into(db.inventoryCounts).insert(InventoryCountsCompanion.insert(
          id: id,
          sessionId: 'inv-1',
          productId: 'p-$id',
          productName: 'Produit $id',
          theoretical: theo,
          physical: Value(phys),
          countedBy: const Value('u-owner'),
          updatedAt: day(3)));
    }
    final s =
        named('inventory_sessions', (await rows('inventory_sessions')).single);
    expect(s['scope'], 'Partiel');
    expect(s['status'], 'Validé');
    expect(s['startedBy'], 'Awa Diallo');
    expect(s['countedProducts'], 2);
    expect(s['gapProducts'], 1);
    expect(s['totalGap'], -3);
    final counts = await rows('inventory_counts');
    expect(
        counts.map((r) => named('inventory_counts', r)['gap']), [0, -3, null]);
    final c2 = named('inventory_counts', counts[1]);
    expect(c2['sessionStatus'], 'Validé');
    expect(c2['countedBy'], 'Propriétaire');
    expect(c2['store'], 'Boutique A');
    expect(
        await rows('inventory_counts', const LocalExportFilter(storeId: 's-B')),
        isEmpty);
  });

  test('reports: labels, store fallback, actor', () async {
    await seedPeople();
    await db.into(db.reports).insert(ReportsCompanion.insert(
        id: 'r-1',
        tenantId: 't',
        storeId: 's-gone',
        storeName: const Value('Ancienne boutique'),
        actorId: const Value('u-emp'),
        reportType: 'DAILY_COMBINED',
        reportDate: day(4),
        content: 'Texte',
        deliveryStatus: 'IN_APP_ONLY',
        totalRevenue: const Value(9000),
        totalSales: const Value(3),
        createdAt: day(4)));
    final r = named('reports', (await rows('reports')).single);
    expect(r['type'], 'Fin de journée (toutes boutiques)');
    expect(r['deliveryStatus'], 'Dans l\'application uniquement');
    expect(r['store'], 'Ancienne boutique');
    expect(r['actor'], 'Awa Diallo');
    expect(r['revenue'], 9000);
    expect(r['content'], 'Texte');
  });

  test('suppliers, employees, stores', () async {
    await seedPeople();
    await db.into(db.suppliers).insert(SuppliersCompanion.insert(
        id: 'sup-1', name: 'Zed', phone: '1', createdAt: t0, updatedAt: t0));
    for (final p in ['p-1', 'p-2']) {
      await db.into(db.productSuppliers).insert(
          ProductSuppliersCompanion.insert(supplierId: 'sup-1', productId: p));
    }
    expect(named('suppliers', (await rows('suppliers')).single)['productCount'],
        2);
    final e = named('employees', (await rows('employees')).single);
    expect(e, containsPair('phone', '+237611'));
    expect(e, containsPair('store', 'Boutique A'));
    expect(e, containsPair('status', 'Actif'));
    expect(await rows('employees', const LocalExportFilter(storeId: 's-B')),
        isEmpty);
    final stores = await rows('stores');
    expect(stores.map((r) => r[1]), ['Boutique A', 'Boutique B']);
    expect(named('stores', stores[0]), containsPair('type', 'STORE'));
    expect(await rows('stores', const LocalExportFilter(storeId: 's-B')),
        hasLength(1));
  });

  test('audit_log: newest first, actor fallbacks, role and source labels',
      () async {
    await seedPeople();
    Future<void> entry(String id, String user, DateTime at,
        {String? metadata, String? source, String? store}) {
      return db.into(db.auditEntries).insert(AuditEntriesCompanion.insert(
          id: id,
          userId: user,
          entityType: 'PRODUCT',
          entityId: 'p-1',
          action: 'PRODUCT_UPDATED',
          occurredAt: at,
          metadata: Value(metadata),
          source: Value(source),
          storeId: Value(store),
          entityLabel: const Value('Pagne')));
    }

    await entry('a-1', 'u-emp', day(1), source: 'SYNC', store: 's-A');
    await entry('a-2', 'u-owner', day(2), source: 'ONLINE');
    await entry('a-3', 'u-x', day(3),
        metadata: '{"actorLabel":"Système Keevo"}');
    await entry('a-4', 'u-y', day(4), source: 'WEIRD');
    final all = await rows('audit_log');
    expect(all.map((r) => r[0]), ['a-4', 'a-3', 'a-2', 'a-1']);
    final a1 = named('audit_log', all[3]);
    expect(a1['actor'], 'Awa Diallo');
    expect(a1['actorRole'], 'Employé');
    expect(a1['source'], 'Synchronisé');
    expect(a1['store'], 'Boutique A');
    expect(a1['entityLabel'], 'Pagne');
    final a2 = named('audit_log', all[2]);
    expect(a2['actor'], 'Le propriétaire');
    expect(a2['actorRole'], 'Propriétaire');
    expect(named('audit_log', all[1])['actor'], 'Système Keevo');
    final a4 = named('audit_log', all[0]);
    expect(a4['actor'], 'Un utilisateur');
    expect(a4['actorRole'], isNull);
    expect(a4['source'], 'WEIRD');
    expect(
        (await rows('audit_log',
                LocalExportFilter(storeId: 's-A', from: day(1), to: day(2))))
            .map((r) => r[0]),
        ['a-1']);
  });

  group('store + period filters (inclusive bounds) per event dataset', () {
    // x-1: A before `from`; x-2: A at `from`; x-3: B inside; x-4: A at `to`;
    // x-5: A after `to`. Store A, [day 5, day 9] → x-2, x-4.
    final fixtures = [
      ('x-1', 's-A', day(2)),
      ('x-2', 's-A', day(5)),
      ('x-3', 's-B', day(6)),
      ('x-4', 's-A', day(9)),
      ('x-5', 's-A', day(9, 11)),
    ];
    final filter = LocalExportFilter(storeId: 's-A', from: day(5), to: day(9));

    final seeders = <String, Future<void> Function(String, String, DateTime)>{
      'stock_movements': (id, store, at) => db.into(db.stockMovements).insert(
          StockMovementsCompanion.insert(
              id: id,
              productId: 'p',
              storeId: store,
              type: 'ADJUSTMENT',
              quantityDelta: 1,
              actorId: 'u',
              createdAt: at)),
      'stock_transfers': (id, store, at) => db.into(db.stockTransfers).insert(
          StockTransfersCompanion.insert(
              id: id,
              sourceStoreId: store,
              destinationStoreId: 's-C',
              productId: 'p',
              quantity: 1,
              actorId: 'u',
              occurredAt: at)),
      'sale_items': (id, store, at) async {
        await db.into(db.sales).insert(SalesCompanion.insert(
            id: id,
            storeId: store,
            employeeId: 'u',
            totalAmount: 1,
            paymentMode: 'CASH',
            occurredAt: Value(at),
            createdAt: t0));
        await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
            id: 'i-$id',
            saleId: id,
            productId: 'p',
            productName: 'P',
            unitPrice: 1,
            quantity: 1,
            subtotal: 1,
            createdAt: t0));
      },
      'day_closures': (id, store, at) => db.into(db.dayClosures).insert(
          DayClosuresCompanion.insert(
              id: id,
              storeId: store,
              actorId: 'u',
              closedAt: at,
              totalSales: 0,
              totalRevenue: 0,
              cashAmount: 0,
              momoAmount: 0,
              pendingSalesCount: 0,
              pendingSalesTotal: 0,
              createdAt: t0)),
      'inventory_sessions': (id, store, at) => db
          .into(db.inventorySessions)
          .insert(InventorySessionsCompanion.insert(
              id: id,
              storeId: store,
              scope: 'FULL',
              status: 'IN_PROGRESS',
              startedBy: 'u',
              startedAt: at,
              updatedAt: t0)),
      'inventory_counts': (id, store, at) async {
        await db.into(db.inventorySessions).insert(
            InventorySessionsCompanion.insert(
                id: id,
                storeId: store,
                scope: 'FULL',
                status: 'IN_PROGRESS',
                startedBy: 'u',
                startedAt: at,
                updatedAt: t0));
        await db.into(db.inventoryCounts).insert(
            InventoryCountsCompanion.insert(
                id: 'c-$id',
                sessionId: id,
                productId: 'p',
                productName: 'P',
                theoretical: 1,
                updatedAt: t0));
      },
      'reports': (id, store, at) => db.into(db.reports).insert(
          ReportsCompanion.insert(
              id: id,
              tenantId: 't',
              storeId: store,
              reportType: 'DAILY',
              reportDate: at,
              content: 'c',
              deliveryStatus: 'SENT',
              createdAt: at)),
    };

    for (final entry in seeders.entries) {
      test(entry.key, () async {
        for (final (id, store, at) in fixtures) {
          await entry.value(id, store, at);
        }
        expect((await rows(entry.key)).map((r) => r[0]),
            ['x-1', 'x-2', 'x-3', 'x-4', 'x-5']);
        expect(
            (await rows(entry.key, filter)).map((r) => r[0]), ['x-2', 'x-4']);
        expect(
            (await rows(entry.key, const LocalExportFilter(storeId: 's-B')))
                .map((r) => r[0]),
            ['x-3']);
        expect(
            (await rows(entry.key, LocalExportFilter(from: day(5), to: day(9))))
                .map((r) => r[0]),
            ['x-2', 'x-3', 'x-4']);
      });
    }
  });

  test('paging: 1,201 rows in pages of at most 500, total order kept',
      () async {
    await db.batch((b) {
      for (var i = 0; i < 1201; i++) {
        b.insert(
            db.clients,
            ClientsCompanion.insert(
                id: 'c-${i.toString().padLeft(4, '0')}',
                name: 'Client $i',
                phone: '$i',
                createdAt: t0, // same instant: the id breaks the tie
                updatedAt: t0));
      }
    });
    final sizes = <int>[];
    final ids = <Object?>[];
    await for (final page
        in reader.pages('clients', const LocalExportFilter(), pageSize: 500)) {
      sizes.add(page.length);
      ids.addAll(page.map((r) => r[0]));
    }
    expect(sizes, [500, 500, 201]);
    expect(ids, hasLength(1201));
    expect(ids.toSet(), hasLength(1201));
    expect(ids.first, 'c-0000');
    expect(ids.last, 'c-1200');
  });

  test('rows hold sendable primitives only', () async {
    await seedPeople();
    await seedSales();
    for (final d in localExportCatalog) {
      for (final r in await rows(d.key)) {
        for (final v in r) {
          expect(
              v == null ||
                  v is String ||
                  v is int ||
                  v is bool ||
                  v is DateTime,
              isTrue,
              reason: '${d.key}: $v');
        }
      }
    }
  });
}
