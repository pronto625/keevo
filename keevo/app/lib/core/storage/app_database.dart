import 'dart:developer' as dev;
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'categories_table.dart';
import 'clients_table.dart';
import 'product_suppliers_table.dart';
import 'products_table.dart';
import 'sale_items_table.dart';
import 'sales_table.dart';
import 'stock_levels_table.dart';
import 'stock_movements_table.dart';
import 'stores_table.dart';
import 'suppliers_table.dart';
import 'sync_queue_table.dart';
import 'users_table.dart';

part 'app_database.g.dart';

/// AppDatabase — Drift SQLite database encrypted with SQLCipher.
///
/// Facade pattern: hides encrypted connection details behind a clean API.
/// Schema version 3: products table extended with description, sku, photoUrl,
/// archived, status columns (Story 2.1).
/// Schema version 4: products table extended with transportCost column (Story 2.2).
/// Schema version 5: stock_movements extended with variantId, quantityBefore,
/// quantityAfter; stock_levels extended with variantId, minimumThreshold (Story 2.3).
/// Schema version 6: clients, suppliers, product_suppliers tables added;
/// sales extended with clientId column (Story 2.5).
@DriftDatabase(tables: [
  SyncQueue,
  Products,
  StockLevels,
  StockMovements,
  Sales,
  SaleItems,
  Stores,
  Users,
  Categories,
  Clients,
  Suppliers,
  ProductSuppliers,
])
class AppDatabase extends _$AppDatabase {
  /// Production constructor — uses SQLCipher encrypted file database.
  ///
  /// [hexKey]: 64-char lowercase hex string (32 bytes) from [DbEncryptionKeyService].
  AppDatabase({required String hexKey})
      : super(_openEncryptedConnection(hexKey));

  /// Test constructor — in-memory Drift database, no encryption, no file system.
  AppDatabase.forTesting() : super(NativeDatabase.memory());

  @override
  int get schemaVersion => 6;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (migrator) async {
      await migrator.createAll();
    },
    onUpgrade: (migrator, from, to) async {
      if (from < 2) {
        await migrator.createTable(products);
        await migrator.createTable(stockLevels);
        await migrator.createTable(stockMovements);
        await migrator.createTable(sales);
        await migrator.createTable(saleItems);
        await migrator.createTable(stores);
        await migrator.createTable(users);
        await migrator.createTable(categories);
      }
      if (from < 3) {
        // Story 2.1 — extend products table with catalogue fields.
        await migrator.addColumn(products, products.description);
        await migrator.addColumn(products, products.sku);
        await migrator.addColumn(products, products.photoUrl);
        await migrator.addColumn(products, products.archived);
        await migrator.addColumn(products, products.status);
      }
      if (from < 4) {
        // Story 2.2 — add transportCost column for pricing engine.
        await migrator.addColumn(products, products.transportCost);
      }
      if (from < 5) {
        // Story 2.3 — stock movements: add variantId, quantityBefore, quantityAfter.
        await migrator.addColumn(stockMovements, stockMovements.variantId);
        await migrator.addColumn(stockMovements, stockMovements.quantityBefore);
        await migrator.addColumn(stockMovements, stockMovements.quantityAfter);
        // Story 2.3 — stock levels: add variantId, minimumThreshold.
        await migrator.addColumn(stockLevels, stockLevels.variantId);
        await migrator.addColumn(stockLevels, stockLevels.minimumThreshold);
      }
      if (from < 6) {
        // Story 2.5 — client & supplier contact book.
        await migrator.createTable(clients);
        await migrator.createTable(suppliers);
        await migrator.createTable(productSuppliers);
        // Story 2.5 — link sales to clients (nullable).
        await migrator.addColumn(sales, sales.clientId);
      }
    },
  );

  /// Purge transactional data older than 30 days.
  ///
  /// Safety invariant: ONLY rows where synced=true AND syncedAt < cutoff
  /// are eligible. Unsynced rows are NEVER purged (zero data loss guarantee).
  ///
  /// Purge order matters:
  ///   1. [saleItems] — no synced/syncedAt columns; purged via parent saleId.
  ///   2. [sales]    — parent rows deleted after children to maintain consistency.
  ///   3. [stockMovements] — independent; synced+syncedAt guard applied directly.
  Future<void> purgeOldTransactionalData() async {
    final cutoff = DateTime.now().subtract(const Duration(days: 30));
    await transaction(() async {
      // 1. Collect IDs of sales eligible for deletion.
      final eligibleSales = await (select(sales)
            ..where((s) =>
                s.synced.equals(true) &
                s.syncedAt.isSmallerThan(Variable<DateTime>(cutoff))))
          .map((s) => s.id)
          .get();

      // 2. Delete sale_items whose parent sale is being purged.
      //    SaleItems have no synced/syncedAt — sync state is inherited from Sales.
      if (eligibleSales.isNotEmpty) {
        await (delete(saleItems)
              ..where((i) => i.saleId.isIn(eligibleSales)))
            .go();
      }

      // 3. Delete the eligible sales.
      await (delete(sales)
            ..where((s) =>
                s.synced.equals(true) &
                s.syncedAt.isSmallerThan(Variable<DateTime>(cutoff))))
          .go();

      // 4. Delete synced stock movements older than cutoff.
      await (delete(stockMovements)
            ..where((m) =>
                m.synced.equals(true) &
                m.syncedAt.isSmallerThan(Variable<DateTime>(cutoff))))
          .go();
    });
  }
}

/// Opens the SQLCipher-encrypted database file.
///
/// Sets the SQLCipher PRAGMA key immediately after opening via the [setup]
/// callback — this is the only correct way to set the key with Drift + sqlcipher.
///
/// File: `keevo_v2.sqlite` — distinct from the old unencrypted `keevo.sqlite`
/// to avoid "file is not a database" crash on devices upgrading from old builds.
LazyDatabase _openEncryptedConnection(String hexKey) {
  return LazyDatabase(() async {
    // Note: libsqlcipher.so override is done in main() before any DB access.
    final dbFolder = await getApplicationDocumentsDirectory();
    final file = File(p.join(dbFolder.path, 'keevo_v2.sqlite'));

    dev.log(
      '[DB] Opening encrypted DB at ${file.path}',
      name: 'AppDatabase',
    );

    // NativeDatabase (not createInBackground) — the background isolate variant
    // does NOT inherit open.overrideFor() from main(), causing sqlite3 to look
    // for libsqlite3.so (absent) instead of libsqlcipher.so. Running in the
    // calling isolate where the override is already set fixes the issue.
    return NativeDatabase(
      file,
      setup: (db) {
        // SQLCipher PRAGMA key — must be set BEFORE any other DB operation.
        // Format: x'HEX' sets a raw binary key (not a passphrase).
        db.execute("PRAGMA key = \"x'$hexKey'\"");

        // Verify SQLCipher is active by reading cipher_version.
        // Standard SQLite returns empty; SQLCipher returns e.g. "4.5.5 community".
        final cipherVersion = db
            .select('PRAGMA cipher_version')
            .firstOrNull
            ?.values
            .firstOrNull;

        if (cipherVersion != null && cipherVersion.toString().isNotEmpty) {
          dev.log(
            '[DB] ✅ SQLCipher active — version: $cipherVersion',
            name: 'AppDatabase',
          );
        } else {
          dev.log(
            '[DB] ⚠️ cipher_version empty — SQLCipher may not be linked correctly',
            name: 'AppDatabase',
          );
        }

        // Verify the DB is readable after keying (would throw if key is wrong).
        final pageCount = db
            .select('PRAGMA page_count')
            .firstOrNull
            ?.values
            .firstOrNull;
        dev.log(
          '[DB] ✅ DB readable — page_count=$pageCount',
          name: 'AppDatabase',
        );

        // Verify KDF iterations (SQLCipher default: 256000 for v4).
        final kdfIter = db
            .select('PRAGMA kdf_iter')
            .firstOrNull
            ?.values
            .firstOrNull;
        dev.log(
          '[DB] ✅ kdf_iter=$kdfIter (256000 = SQLCipher v4 default)',
          name: 'AppDatabase',
        );
      },
    );
  });
}
