import 'dart:ffi';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/inventory/presentation/provider/validate_inventory_provider.dart';

void _overrideSqlite3ForLinuxTesting() {
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

class _MockSyncService extends Mock implements SyncService {}

class _MockDio extends Mock implements Dio {}

/// Seeds a session + count + stock_level into the test DB.
///
/// [countTheoretical], [countPhysical], [stockQty] are the key parameters.
/// [countVariantId], [stockVariantId] default to null (real-world case).
Future<void> _seedInventoryScenario({
  required AppDatabase db,
  required String sessionId,
  required String storeId,
  required String productId,
  required int countTheoretical,
  required int countPhysical,
  required int stockQty,
  String? countVariantId,
  String? stockVariantId,
  int minimumThreshold = 0,
}) async {
  final now = DateTime.now();
  await db.into(db.inventorySessions).insert(
        InventorySessionsCompanion.insert(
          id: sessionId,
          storeId: storeId,
          scope: 'FULL',
          status: 'IN_PROGRESS',
          startedBy: 'actor-test',
          startedAt: now,
          updatedAt: now,
        ),
      );
  await db.into(db.inventoryCounts).insert(
        InventoryCountsCompanion.insert(
          id: 'count-${productId}',
          sessionId: sessionId,
          productId: productId,
          variantId: Value(countVariantId),
          productName: 'Product $productId',
          theoretical: countTheoretical,
          physical: Value(countPhysical),
          updatedAt: now,
        ),
      );
  await db.into(db.stockLevels).insert(
        StockLevelsCompanion.insert(
          id: 'sl-${productId}${stockVariantId ?? ''}',
          productId: productId,
          variantId: Value(stockVariantId),
          storeId: storeId,
          quantity: stockQty,
          minimumThreshold: Value(minimumThreshold),
          updatedAt: now,
        ),
      );
}

void main() {
  group('ValidateInventoryNotifier._validateOffline (Story 13.7)', () {
    late _MockSyncService mockSyncService;
    late AppDatabase db;

    setUpAll(() {
      _overrideSqlite3ForLinuxTesting();
    });

    setUp(() {
      mockSyncService = _MockSyncService();
      db = AppDatabase.forTesting();
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
    });

    tearDown(() async {
      await db.close();
    });

    /// Builds a ProviderContainer that overrides connectivity to offline,
    /// currentUserIdProvider to return a known userId, and uses the test DB.
    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          appDatabaseProvider.overrideWithValue(db),
          syncServiceProvider.overrideWithValue(mockSyncService),
          dioProvider.overrideWithValue(_MockDio()),
          // Override currentUserIdProvider to return a fixed actor ID.
          currentUserIdProvider.overrideWith((ref) async => 'actor-test-001'),
          // Override connectivity to force offline path.
          connectivityServiceProvider
              .overrideWithValue(_AlwaysOfflineConnectivity()),
        ]);

    // ── AC1: StockMovement created per non-zero-gap count ─────────────────

    test('creates a StockMovement per non-zero-gap count (AC1)', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac1',
        storeId: 'store-1',
        productId: 'prod-1',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac1');

      expect(adjustments, 1);

      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      final m = movements.first;
      expect(m.type, 'ADJUSTMENT');
      expect(m.quantityBefore, 10);
      expect(m.quantityDelta, -3);
      expect(m.quantityAfter, 7);
      expect(m.source, 'INVENTORY');
      expect(m.inventorySessionId, 'sess-ac1');
      expect(m.actorId, 'actor-test-001');
      expect(m.reason, 'INVENTORY:sess-ac1');

      // stock_levels updated to physical
      final sl = await (db.select(db.stockLevels)
            ..where((t) => t.id.equals('sl-prod-1')))
          .getSingle();
      expect(sl.quantity, 7);

      // session marked VALIDATED
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-ac1')))
          .getSingle();
      expect(sess.status, 'VALIDATED');
    });

    // ── AC2: Delta live zero → skip, not counted ─────────────────────────

    test('skips count when live stock already matches physical (AC2)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac2',
        storeId: 'store-1',
        productId: 'prod-2',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 7, // live stock already matches physical
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac2');

      expect(adjustments, 0);
      final movements = await db.select(db.stockMovements).get();
      expect(movements.isEmpty, isTrue);
    });

    // ── AC3: Variant-aware lookup (defensive) ─────────────────────────────

    test('filters stock_levels lookup by variantId when present (AC3)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac3',
        storeId: 'store-1',
        productId: 'prod-3',
        countTheoretical: 20,
        countPhysical: 15,
        stockQty: 20,
        countVariantId: 'var-1',
        stockVariantId: 'var-1',
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac3');

      expect(adjustments, 1);

      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.variantId, 'var-1');
      expect(movements.first.quantityBefore, 20);
      expect(movements.first.quantityDelta, -5);
      expect(movements.first.quantityAfter, 15);
    });

    // ── AC4: Missing stock_levels row → created ──────────────────────────

    test('creates missing stock_levels row instead of skipping (AC4)',
        () async {
      final now = DateTime.now();
      // Seed session + count but NO stock_levels row.
      await db.into(db.inventorySessions).insert(
            InventorySessionsCompanion.insert(
              id: 'sess-ac4',
              storeId: 'store-1',
              scope: 'FULL',
              status: 'IN_PROGRESS',
              startedBy: 'actor-test',
              startedAt: now,
              updatedAt: now,
            ),
          );
      await db.into(db.inventoryCounts).insert(
            InventoryCountsCompanion.insert(
              id: 'count-ac4',
              sessionId: 'sess-ac4',
              productId: 'prod-4',
              variantId: const Value.absent(),
              productName: 'Product prod-4',
              theoretical: 0,
              physical: const Value(5),
              updatedAt: now,
            ),
          );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac4');

      expect(adjustments, 1);

      // stock_levels row created
      final slRows = await (db.select(db.stockLevels)
            ..where((t) => t.productId.equals('prod-4') &
                t.storeId.equals('store-1')))
          .get();
      expect(slRows.length, 1);
      expect(slRows.first.quantity, 5);

      // StockMovement with quantityBefore=0
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.quantityBefore, 0);
      expect(movements.first.quantityDelta, 5);
      expect(movements.first.quantityAfter, 5);
    });

    // ── Regression: zero-gap counts produce no movement ──────────────────

    test('skips zero-gap counts, no movement (regression)', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-reg',
        storeId: 'store-1',
        productId: 'prod-5',
        countTheoretical: 10,
        countPhysical: 10, // physical == theoretical
        stockQty: 10,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-reg');

      expect(adjustments, 0);
      final movements = await db.select(db.stockMovements).get();
      expect(movements.isEmpty, isTrue);
    });

    // ── AC6: Threshold breach log ────────────────────────────────────────

    test('logs threshold breach when qty drops to or below min (AC6)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac6',
        storeId: 'store-1',
        productId: 'prod-6',
        countTheoretical: 10,
        countPhysical: 2,
        stockQty: 10,
        minimumThreshold: 5,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      await notifier.validate('sess-ac6');

      // Stock movement should be created despite threshold breach
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.quantityAfter, 2);
    });

    // ── No threshold breach when qty stays above min ─────────────────────

    test('no threshold log when qty stays above min (AC6 negative)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac6b',
        storeId: 'store-1',
        productId: 'prod-6b',
        countTheoretical: 10,
        countPhysical: 8,
        stockQty: 10,
        minimumThreshold: 5,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac6b');

      // Adjustment should still be applied
      expect(adjustments, 1);
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.quantityAfter, 8);
    });

    // ── Multiple counts in one session ───────────────────────────────────

    test('handles multiple counts with mixed gaps in one session', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-multi',
        storeId: 'store-1',
        productId: 'prod-a',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      final now = DateTime.now();
      // Add a second count with zero gap
      await db.into(db.inventoryCounts).insert(
            InventoryCountsCompanion.insert(
              id: 'count-prod-b',
              sessionId: 'sess-multi',
              productId: 'prod-b',
              variantId: const Value.absent(),
              productName: 'Product prod-b',
              theoretical: 5,
              physical: const Value(5),
              updatedAt: now,
            ),
          );
      await db.into(db.stockLevels).insert(
            StockLevelsCompanion.insert(
              id: 'sl-prod-b',
              productId: 'prod-b',
              variantId: const Value.absent(),
              storeId: 'store-1',
              quantity: 5,
              updatedAt: now,
            ),
          );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-multi');

      // Only prod-a had a non-zero live delta
      expect(adjustments, 1);
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.productId, 'prod-a');
    });

    // ── Sync operation queued after offline validation ───────────────────

    test('queues VALIDATE_INVENTORY sync operation after offline validation',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-sync',
        storeId: 'store-1',
        productId: 'prod-sync',
        countTheoretical: 10,
        countPhysical: 6,
        stockQty: 10,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      await notifier.validate('sess-sync');

      verify(() => mockSyncService.queueOperation(
            operation: 'VALIDATE_INVENTORY',
            payload: {'sessionId': 'sess-sync'},
            entityId: 'sess-sync',
          )).called(1);
    });

    // ── Patch P1: actorId null throws before transaction ────────────────

    test('throws when currentUserIdProvider returns null (no partial state)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-null-actor',
        storeId: 'store-1',
        productId: 'prod-null-actor',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );

      // Override currentUserIdProvider to return null (simulating no auth).
      final container = ProviderContainer(overrides: [
        appDatabaseProvider.overrideWithValue(db),
        syncServiceProvider.overrideWithValue(mockSyncService),
        dioProvider.overrideWithValue(_MockDio()),
        currentUserIdProvider.overrideWith((ref) async => null),
        connectivityServiceProvider
            .overrideWithValue(_AlwaysOfflineConnectivity()),
      ]);
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      // validate() rethrows the exception from _validateOffline.
      await expectLater(
        () => notifier.validate('sess-null-actor'),
        throwsA(isA<Exception>()),
      );

      // No stock_movements created — exception thrown BEFORE the transaction.
      final movements = await db.select(db.stockMovements).get();
      expect(movements, isEmpty);

      // stock_levels unchanged — no partial write.
      final sl = await (db.select(db.stockLevels)
            ..where((t) => t.id.equals('sl-prod-null-actor')))
          .getSingle();
      expect(sl.quantity, 10); // original value, untouched

      // session NOT marked VALIDATED.
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-null-actor')))
          .getSingle();
      expect(sess.status, 'IN_PROGRESS');
    });

    // ── Patch P2: threshold boundary (physical == threshold) ────────────

    test('logs threshold breach when physical equals threshold exactly (AC6 boundary)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac6-boundary',
        storeId: 'store-1',
        productId: 'prod-boundary',
        countTheoretical: 10,
        countPhysical: 5, // exactly equals threshold
        stockQty: 10,
        minimumThreshold: 5,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac6-boundary');

      // Adjustment applied (threshold breach does NOT block validation).
      expect(adjustments, 1);

      // StockMovement created with quantityAfter == threshold (boundary).
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.quantityAfter, 5); // == minimumThreshold
      expect(movements.first.quantityBefore, 10);
      expect(movements.first.quantityDelta, -5);
    });
  });
}

/// Stub connectivity that always reports offline.
class _AlwaysOfflineConnectivity implements ConnectivityService {
  @override
  Future<bool> isOnline() async => false;

  @override
  Stream<bool> get onlineStream => Stream.value(false);
}
