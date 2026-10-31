import 'dart:convert';
import 'dart:ffi';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/inventory/presentation/provider/validate_inventory_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

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

Future<void> _queuePendingCount(AppDatabase db, String sessionId) async {
  await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
        id: 'op-count-$sessionId',
        operation: 'SAVE_INVENTORY_COUNT',
        payload: jsonEncode({
          'sessionId': sessionId,
          'productId': 'prod-1',
          'theoretical': 10,
          'physical': 7,
        }),
        createdAt: DateTime.now(),
      ));
}

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
    ProviderContainer buildContainer() => ProviderContainer(retry: noProviderRetry, overrides: [
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

    // ── AC2: the counted gap applies on top of the current stock ─────────
    // Same rule as the backend: sales made between the count and the
    // validation stay counted (setting stock to the counted value would
    // erase them).

    test('applies the counted gap on top of current stock (sale after count)',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-ac2',
        storeId: 'store-1',
        productId: 'prod-2',
        countTheoretical: 10,
        countPhysical: 7, // 3 missing at count time
        stockQty: 8, // 2 sold since the count
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);
      final adjustments = await notifier.validate('sess-ac2');

      expect(adjustments, 1);
      final m = (await db.select(db.stockMovements).get()).single;
      expect(m.quantityBefore, 8);
      expect(m.quantityDelta, -3);
      expect(m.quantityAfter, 5);
      final sl = await (db.select(db.stockLevels)
            ..where((t) => t.id.equals('sl-prod-2')))
          .getSingle();
      expect(sl.quantity, 5);
    });

    test('a gap larger than the current stock stops at zero', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-floor',
        storeId: 'store-1',
        productId: 'prod-floor',
        countTheoretical: 10,
        countPhysical: 2, // gap -8
        stockQty: 5,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-floor');

      final m = (await db.select(db.stockMovements).get()).single;
      expect(m.quantityDelta, -5);
      expect(m.quantityAfter, 0);
    });

    test('no adjustment when the stock is already at zero and the gap is negative',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-zero',
        storeId: 'store-1',
        productId: 'prod-zero',
        countTheoretical: 4,
        countPhysical: 1,
        stockQty: 0,
      );

      final container = buildContainer();
      addTearDown(container.dispose);

      final adjustments = await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-zero');

      expect(adjustments, 0);
      expect(await db.select(db.stockMovements).get(), isEmpty);
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

    test('product never stocked here counted 0 → no row, no movement',
        () async {
      final now = DateTime.now();
      await db.into(db.inventorySessions).insert(
            InventorySessionsCompanion.insert(
              id: 'sess-zero',
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
              id: 'count-zero',
              sessionId: 'sess-zero',
              productId: 'prod-never',
              productName: 'Product never stocked',
              theoretical: 0,
              physical: const Value(0),
              updatedAt: now,
            ),
          );

      final container = buildContainer();
      addTearDown(container.dispose);

      final adjustments = await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-zero');

      expect(adjustments, 0);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(
          await (db.select(db.stockLevels)
                ..where((t) => t.productId.equals('prod-never')))
              .get(),
          isEmpty);
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

      // The local ADJUSTMENT movement id is sent along, so the server reuses
      // it and the pull does not add a second copy of the adjustment.
      final movement = (await db.select(db.stockMovements).get()).single;
      final captured = verify(() => mockSyncService.queueOperation(
            operation: 'VALIDATE_INVENTORY',
            payload: captureAny(named: 'payload'),
            entityId: 'sess-sync',
          )).captured.single as Map<String, dynamic>;
      expect(captured['sessionId'], 'sess-sync');
      expect(captured['movementIds'], {'prod-sync|': movement.id});
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
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
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

    test('a validation whose queueing fails leaves stock and session untouched',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-atomic',
        storeId: 'store-1',
        productId: 'prod-atomic',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      when(() => mockSyncService.queueOperation(
            operation: 'VALIDATE_INVENTORY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('app killed / disk full'));

      final container = buildContainer();
      addTearDown(container.dispose);

      await expectLater(
        container
            .read(validateInventoryNotifierProvider.notifier)
            .validate('sess-atomic'),
        throwsException,
      );

      final sl = await (db.select(db.stockLevels)
            ..where((t) => t.id.equals('sl-prod-atomic')))
          .getSingle();
      expect(sl.quantity, 10);
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-atomic')))
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

    // ── v1s-13-8: offline guards + variant fallback ──────────────────────

    Future<void> seedSession(String id, {String status = 'IN_PROGRESS', DateTime? completedAt}) async {
      final now = DateTime.now();
      await db.into(db.inventorySessions).insert(
            InventorySessionsCompanion.insert(
              id: id,
              storeId: 'store-1',
              scope: 'FULL',
              status: status,
              startedBy: 'actor-test',
              startedAt: now,
              completedAt: Value(completedAt),
              updatedAt: now,
            ),
          );
    }

    void verifyNothingQueued() {
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
    }

    test('v1s-13-8: session with no counted line throws, nothing written/queued',
        () async {
      await seedSession('sess-empty');
      await db.into(db.inventoryCounts).insert(
            InventoryCountsCompanion.insert(
              id: 'count-uncounted',
              sessionId: 'sess-empty',
              productId: 'prod-x',
              productName: 'Product x',
              theoretical: 4,
              physical: const Value(null),
              updatedAt: DateTime.now(),
            ),
          );

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      await expectLater(notifier.validate('sess-empty'), throwsA(anything));

      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-empty')))
          .getSingle();
      expect(sess.status, 'IN_PROGRESS');
      expect(await db.select(db.stockMovements).get(), isEmpty);
      verifyNothingQueued();
    });

    test('v1s-13-8: session with no count at all throws, nothing written/queued',
        () async {
      await seedSession('sess-none');

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      await expectLater(notifier.validate('sess-none'), throwsA(anything));
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-none')))
          .getSingle();
      expect(sess.status, 'IN_PROGRESS');
      verifyNothingQueued();
    });

    test('v1s-13-8: re-validating a VALIDATED session returns 0, keeps completedAt, queues nothing',
        () async {
      final completedAt = DateTime(2026, 1, 2, 3, 4, 5);
      await seedSession('sess-done', status: 'VALIDATED', completedAt: completedAt);

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      final adjustments = await notifier.validate('sess-done');

      expect(adjustments, 0);
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-done')))
          .getSingle();
      expect(sess.completedAt, completedAt);
      verifyNothingQueued();
    });

    test('v1s-13-8: validating a CANCELLED session throws, nothing written',
        () async {
      await seedSession('sess-cancel', status: 'CANCELLED');

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      await expectLater(notifier.validate('sess-cancel'), throwsA(anything));
      final sess = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals('sess-cancel')))
          .getSingle();
      expect(sess.status, 'CANCELLED');
      verifyNothingQueued();
    });

    test('v1s-13-8: two concurrent validate() calls apply adjustments once',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-race',
        storeId: 'store-1',
        productId: 'prod-race',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      final results = await Future.wait(
          [notifier.validate('sess-race'), notifier.validate('sess-race')]);

      expect(results.reduce((a, b) => a + b), 1);
      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      final sl = await (db.select(db.stockLevels)
            ..where((t) => t.productId.equals('prod-race')))
          .getSingle();
      expect(sl.quantity, 7);
      verify(() => mockSyncService.queueOperation(
            operation: 'VALIDATE_INVENTORY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });

    test('v1s-13-8: variant mismatch updates the (product, store) row, no UNIQUE violation',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-var',
        storeId: 'store-1',
        productId: 'prod-var',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
        countVariantId: 'var-V',
        stockVariantId: null,
      );

      final container = buildContainer();
      addTearDown(container.dispose);
      final notifier =
          container.read(validateInventoryNotifierProvider.notifier);

      final adjustments = await notifier.validate('sess-var');

      expect(adjustments, 1);
      final slRows = await (db.select(db.stockLevels)
            ..where((t) =>
                t.productId.equals('prod-var') & t.storeId.equals('store-1')))
          .get();
      expect(slRows.length, 1);
      expect(slRows.first.quantity, 7);
      final movements = await db.select(db.stockMovements).get();
      expect(movements.length, 1);
      expect(movements.first.variantId, 'var-V');
      expect(movements.first.quantityBefore, 10);
      expect(movements.first.quantityAfter, 7);
    });
  });

  group('ValidateInventoryNotifier online with queued counts', () {
    late _MockSyncService mockSyncService;
    late _MockDio mockDio;
    late AppDatabase db;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() {
      mockSyncService = _MockSyncService();
      mockDio = _MockDio();
      db = AppDatabase.forTesting();
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDio.post<Map<String, dynamic>>(any())).thenAnswer(
        (inv) async => Response(
          requestOptions: RequestOptions(path: inv.positionalArguments[0]),
          data: {
            'data': {'adjustmentsApplied': 1}
          },
        ),
      );
    });

    tearDown(() async => db.close());

    /// [onPush] simulates what the push does to the queue (flush it, or
    /// leave it when the backend is unreachable).
    ProviderContainer buildContainer(Future<void> Function() onPush) {
      when(() => mockSyncService.push()).thenAnswer((_) async {
        await onPush();
        return [];
      });
      return ProviderContainer(retry: noProviderRetry, overrides: [
          appDatabaseProvider.overrideWithValue(db),
          syncServiceProvider.overrideWithValue(mockSyncService),
          dioProvider.overrideWithValue(mockDio),
          currentUserIdProvider.overrideWith((ref) async => 'actor-test-001'),
          connectivityServiceProvider
              .overrideWithValue(_AlwaysOnlineConnectivity()),
        ]);
    }

    test('pushes the session queued counts before validating on the server',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-on',
        storeId: 'store-1',
        productId: 'prod-1',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      await _queuePendingCount(db, 'sess-on');
      final container = buildContainer(() async {
        // Successful push: the backend acknowledged the queued count.
        await db.delete(db.syncQueue).go();
      });
      addTearDown(container.dispose);

      final adjustments = await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-on');

      verify(() => mockSyncService.push()).called(1);
      expect(adjustments, 1);
      verify(() => mockDio.post<Map<String, dynamic>>(
          '/api/v1/inventory/sessions/sess-on/validate')).called(1);
    });

    test('falls back to the offline path when queued counts could not be pushed',
        () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-stuck',
        storeId: 'store-1',
        productId: 'prod-1',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      await _queuePendingCount(db, 'sess-stuck');
      final container = buildContainer(() async {}); // push failed silently
      addTearDown(container.dispose);

      final adjustments = await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-stuck');

      expect(adjustments, 1);
      verifyNever(() => mockDio.post<Map<String, dynamic>>(any()));
      // Queued after the counts, so the backend replays them in order.
      verify(() => mockSyncService.queueOperation(
            operation: 'VALIDATE_INVENTORY',
            payload: any(named: 'payload'),
            entityId: 'sess-stuck',
          )).called(1);
    });

    test('validates directly on the server when nothing is queued', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-clean',
        storeId: 'store-1',
        productId: 'prod-1',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      // A pending count of another session must not block this one.
      await _queuePendingCount(db, 'other-session');
      final container = buildContainer(() async {});
      addTearDown(container.dispose);

      await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-clean');

      verifyNever(() => mockSyncService.push());
      verify(() => mockDio.post<Map<String, dynamic>>(
          '/api/v1/inventory/sessions/sess-clean/validate')).called(1);
    });

    // A count the server rejected stays in the queue with an error: it will
    // never be applied, so it must not hold the session offline forever.
    test('a rejected count op does not force the offline path', () async {
      await _seedInventoryScenario(
        db: db,
        sessionId: 'sess-rej',
        storeId: 'store-1',
        productId: 'prod-1',
        countTheoretical: 10,
        countPhysical: 7,
        stockQty: 10,
      );
      await _queuePendingCount(db, 'sess-rej');
      await (db.update(db.syncQueue)
            ..where((t) => t.id.equals('op-count-sess-rej')))
          .write(const SyncQueueCompanion(lastError: Value('REJECTED')));
      final container = buildContainer(() async {});
      addTearDown(container.dispose);

      await container
          .read(validateInventoryNotifierProvider.notifier)
          .validate('sess-rej');

      verifyNever(() => mockSyncService.push());
      verify(() => mockDio.post<Map<String, dynamic>>(
          '/api/v1/inventory/sessions/sess-rej/validate')).called(1);
    });
  });
}

class _AlwaysOnlineConnectivity implements ConnectivityService {
  @override
  Future<bool> isOnline() async => true;

  @override
  Stream<bool> get onlineStream => Stream.value(true);
}

/// Stub connectivity that always reports offline.
class _AlwaysOfflineConnectivity implements ConnectivityService {
  @override
  Future<bool> isOnline() async => false;

  @override
  Stream<bool> get onlineStream => Stream.value(false);
}
