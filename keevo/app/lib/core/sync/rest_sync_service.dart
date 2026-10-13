import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../../features/catalog/data/datasource/remote_product_datasource.dart';
import '../di/providers.dart' show kUserRoleKey;
import '../storage/app_constants.dart';
import '../storage/app_database.dart';
import 'sync_rejection_policy.dart';
import 'sync_required_exception.dart';
import 'sync_service.dart';

/// RestSyncService — REST implementation for offline queue batch push sync.
///
/// push() replays queued offline operations as unified batches to
/// POST /api/v1/sync/push. Online writes go directly to individual
/// REST endpoints (backend-first pattern) — this service only handles
/// the offline safety-net queue.
class RestSyncService implements SyncService {
  final AppDatabase _database;
  final Dio _dio;
  final FlutterSecureStorage _secureStorage;
  final SharedPreferences _prefs;

  /// Operations each carrying a distinct change (new field values, absolute
  /// state, stock quantity): never deduplicated on (entityId, operation).
  static const _appendOnlyOperations = {
    'UPDATE_PRODUCT',
    'UPDATE_CLIENT',
    'UPDATE_SUPPLIER',
    'UPDATE_STORE',
    'RENAME_CATEGORY',
    'TOGGLE_CATEGORY',
    'REASSIGN_EMPLOYEE',
    'SAVE_INVENTORY_COUNT',
    'RECORD_STOCK_ENTRY',
    'STOCK_ADJUST',
    'STOCK_TRANSFER',
  };

  /// Operations applied at most once per entity: once one is APPLIED, other
  /// queued copies for the same entity are redundant.
  static bool _isOneShot(String operation) =>
      operation.startsWith('CREATE_') ||
      const {
        'VALIDATE_SALE',
        'VALIDATE_INVENTORY',
        'CANCEL_SALE',
        'PROMOTE_PRODUCT',
      }.contains(operation);

  RestSyncService({
    required AppDatabase database,
    required RemoteProductDataSource remoteProducts,
    required Dio dio,
    required FlutterSecureStorage secureStorage,
    required SharedPreferences prefs,
  })  : _database = database,
        _dio = dio,
        _secureStorage = secureStorage,
        _prefs = prefs;

  // Single-flight: two pushes of the same queue at once would replay the same
  // operations twice. A caller arriving mid-push joins the running one.
  Future<List<Map<String, dynamic>>>? _pushInFlight;

  @override
  Future<List<Map<String, dynamic>>> push() {
    final running = _pushInFlight;
    if (running != null) return running;
    final push = _push().whenComplete(() => _pushInFlight = null);
    _pushInFlight = push;
    return push;
  }

  Future<List<Map<String, dynamic>>> _push() async {
    // A validation/cancellation queued after its sale was quarantined joins
    // the sale's chain instead of being sent (and refused) on its own.
    await _database.customStatement(
      'UPDATE sync_queue SET quarantined_at = ? '
      'WHERE synced = 0 AND quarantined_at IS NULL '
      "AND operation IN ('VALIDATE_SALE', 'CANCEL_SALE') "
      'AND entity_id IN (SELECT entity_id FROM sync_queue '
      "WHERE synced = 0 AND operation = 'CREATE_SALE' "
      'AND quarantined_at IS NOT NULL)',
      [_database.typeMapping.mapToSqlVariable(DateTime.now())],
    );
    // Quarantined operations (v1s-16-15) are kept but never pushed until
    // the user releases them.
    final pendingOps = await (_database.select(_database.syncQueue)
          ..where((t) => t.synced.equals(false) & t.quarantinedAt.isNull())
          ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
        .get();

    if (pendingOps.isEmpty) return [];

    dev.log('📦 Found ${pendingOps.length} pending operations', name: 'RestSync');

    final allConflicts = <Map<String, dynamic>>[];
    // Queue ids removed by a rejection cascade during this push: they are
    // neither sent in a later batch nor reported from the current one.
    final removed = <String>{};
    // Queue ids whose payload a rejection cascade rewrote (ghost reference
    // stripped): a refusal of the copy already sent is not final.
    final rewritten = <String>{};
    // Queue ids quarantined during this push together with the sale they
    // depend on (chain): their own refusal is not reported, and they are
    // not sent in a later batch.
    final quarantined = <String>{};
    // Batch in groups of 50
    for (var i = 0; i < pendingOps.length; i += 50) {
      var batch = pendingOps
          .sublist(i, min(i + 50, pendingOps.length))
          .where((o) => !removed.contains(o.id))
          .toList();
      if (i > 0 && batch.isNotEmpty) {
        // Re-read: an earlier batch's reconciliation may have rewritten
        // these payloads.
        final ids = batch.map((o) => o.id).toList();
        batch = await (_database.select(_database.syncQueue)
              ..where((t) => t.id.isIn(ids) & t.quarantinedAt.isNull())
              ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
            .get();
        // Sent with their current payload from now on.
        rewritten.removeAll(ids);
      }
      if (batch.isEmpty) continue;
      allConflicts
          .addAll(await _pushBatch(batch, removed, rewritten, quarantined));
    }
    return allConflicts;
  }

  Future<void> _dropMergedLocalProduct(
      String localProductId, String serverProductId) async {
    // Counts entered offline (quick-add) move to the server product: the
    // server redirects the queued count to it, and the counting screen must
    // not show a count for a product that no longer exists locally. A count
    // the server product already has in the same session wins.
    await _database.customStatement(
      'DELETE FROM inventory_counts WHERE product_id = ? AND session_id IN '
      '(SELECT session_id FROM inventory_counts WHERE product_id = ?)',
      [localProductId, serverProductId],
    );
    await _database.customStatement(
      'UPDATE inventory_counts SET product_id = ? WHERE product_id = ?',
      [serverProductId, localProductId],
    );
    for (final table in const ['stock_levels', 'stock_movements']) {
      await _database.customStatement(
          'DELETE FROM $table WHERE product_id = ?', [localProductId]);
    }
    await _database
        .customStatement('DELETE FROM products WHERE id = ?', [localProductId]);
  }

  Future<List<Map<String, dynamic>>> _pushBatch(List<SyncQueueData> ops,
      Set<String> removed, Set<String> rewritten, Set<String> quarantined) async {
    final payload = {
      'deviceId': await _getDeviceId(),
      'operations': ops.map((op) {
        final decoded = jsonDecode(op.payload) as Map<String, dynamic>;
        return {
          'operationId': op.id,
          'operationType': op.operation,
          'entityId': op.entityId,
          'payload': decoded,
          'clientTimestamp': op.createdAt.toUtc().toIso8601String(),
        };
      }).toList(),
    };

    final conflicts = <Map<String, dynamic>>[];

    try {
      final response = await _dio.post('/api/v1/sync/push', data: payload);
      final results = (response.data['data']['results'] as List)
          .cast<Map<String, dynamic>>();

      for (final result in results) {
        final opId = result['operationId'] as String;
        final status = result['status'] as String;
        // Already dropped by the reconciliation of an earlier rejection in
        // this push (dependent of a rolled-back entity): nothing to report.
        if (removed.contains(opId)) continue;

        if (status == 'APPLIED' || status == 'DUPLICATE') {
          // Collect conflicts from APPLIED results with conflictData (LWW)
          if (result['conflictData'] != null) {
            conflicts.add(result);
          }
          // Delete the confirmed op — and, for one-shot operations, any
          // duplicates for the same (entityId, operation): pre-existing
          // duplicates from before the dedup guard was in place would
          // otherwise stay REJECTED forever. Other operations are deleted by
          // id only: a later edit of the same entity is still to be sent.
          final confirmedOp = ops.firstWhere((o) => o.id == opId);
          // The server merged this product creation into an already-existing
          // product of the same name (serverEntityId != our local id) and
          // will redirect our queued stock entries / sales to it. Drop the
          // local duplicate so the catalogue doesn't show the name twice —
          // the real product arrives through the next pull.
          final serverEntityId = result['serverEntityId'] as String?;
          if ((confirmedOp.operation == 'CREATE_PRODUCT' ||
                  confirmedOp.operation == 'CREATE_DRAFT_PRODUCT') &&
              serverEntityId != null &&
              confirmedOp.entityId != null &&
              serverEntityId != confirmedOp.entityId) {
            await _dropMergedLocalProduct(
                confirmedOp.entityId!, serverEntityId);
          }
          if (confirmedOp.operation == 'CREATE_DAY_CLOSURE' &&
              confirmedOp.entityId != null) {
            if (status == 'APPLIED') {
              await _database.customStatement(
                  'UPDATE day_closures SET synced = 1 WHERE id = ?',
                  [confirmedOp.entityId]);
            } else {
              // DUPLICATE: the day was already closed on the server (e.g.
              // by the auto-closure); that closure arrives through pull.
              await _database.customStatement(
                  'DELETE FROM day_closures WHERE id = ? AND synced = 0',
                  [confirmedOp.entityId]);
            }
          }
          if (confirmedOp.operation == 'CREATE_INVENTORY_SESSION' &&
              confirmedOp.entityId != null) {
            // The server has the session: let pull update it from now on
            // (pull skips synced=0 sessions).
            await _database.customStatement(
                'UPDATE inventory_sessions SET synced = 1 WHERE id = ?',
                [confirmedOp.entityId]);
          }
          if (confirmedOp.entityId != null &&
              _isOneShot(confirmedOp.operation)) {
            await (_database.delete(_database.syncQueue)
                  ..where((t) =>
                      t.entityId.equals(confirmedOp.entityId!) &
                      t.operation.equals(confirmedOp.operation)))
                .go();
          } else {
            await (_database.delete(_database.syncQueue)
                  ..where((t) => t.id.equals(opId)))
                .go();
          }
        } else if (status == 'REJECTED') {
          // Quarantined earlier in this push with the sale it depends on:
          // the sale's notice already covers it.
          if (quarantined.contains(opId)) continue;
          final currentOp = ops.firstWhere((o) => o.id == opId);
          final reason = result['reason'] as String?;
          // Enrich with operation/entity info — the server result only
          // carries operationId/status/reason, not what was rejected.
          Map<String, dynamic> notice({required bool rolledBack}) => {
                ...result,
                'operation': currentOp.operation,
                'entityId': currentOp.entityId,
                'reason': reason,
                'rolledBack': rolledBack,
              };

          // The sale is already cancelled on the server: settled.
          if (currentOp.operation == 'CANCEL_SALE' &&
              reason == 'SALE_ALREADY_CANCELLED') {
            await (_database.delete(_database.syncQueue)
                  ..where((t) => t.id.equals(opId)))
                .go();
            continue;
          }

          // The copy sent in this batch still carried a ghost reference that
          // an earlier rejection stripped: retry the rewritten payload.
          if (rewritten.contains(opId)) {
            await (_database.update(_database.syncQueue)
                  ..where((t) => t.id.equals(opId)))
                .write(SyncQueueCompanion(
              retryCount: Value(currentOp.retryCount + 1),
              lastAttemptAt: Value(DateTime.now()),
            ));
            continue;
          }

          // A money / stock-ledger operation refused for good: kept on the
          // device but set aside (never pushed, not pending) until the user
          // acts on it — see sync_rejection_policy.dart.
          if (quarantinesOnRejection(currentOp.operation, reason)) {
            // A validation/cancellation follows its sale: while the sale is
            // still pending it is only retried; once the sale is
            // quarantined it joins that chain silently.
            final sale = saleChainOperations.contains(currentOp.operation) &&
                    currentOp.entityId != null
                ? await (_database.select(_database.syncQueue)
                      ..where((t) =>
                          t.synced.equals(false) &
                          t.operation.equals('CREATE_SALE') &
                          t.entityId.equals(currentOp.entityId!))
                      ..limit(1))
                    .getSingleOrNull()
                : null;
            if (sale != null) {
              await (_database.update(_database.syncQueue)
                    ..where((t) => t.id.equals(opId)))
                  .write(SyncQueueCompanion(
                retryCount: Value(currentOp.retryCount + 1),
                lastAttemptAt: Value(DateTime.now()),
                lastError: Value(reason),
                quarantinedAt: sale.quarantinedAt != null
                    ? Value(DateTime.now())
                    : const Value.absent(),
              ));
              continue;
            }
            await _database.transaction(
                () => _quarantine(currentOp, reason, quarantined));
            conflicts.add({...notice(rolledBack: false), 'quarantined': true});
            continue;
          }

          if (isTransientSyncRejection(reason)) {
            // Kept in the queue and surfaced only the first time this reason
            // is seen — otherwise every retry would re-notify the user.
            if (currentOp.lastError != reason) {
              conflicts.add(notice(rolledBack: false));
            }
            await (_database.update(_database.syncQueue)
                  ..where((t) => t.id.equals(opId)))
                .write(SyncQueueCompanion(
              retryCount: Value(currentOp.retryCount + 1),
              lastAttemptAt: Value(DateTime.now()),
              lastError: Value(reason),
            ));
            continue;
          }

          // Terminal: reconcile the local state, then drop the operation —
          // the server would refuse it again on every push, and a stuck op
          // keeps hasPendingOperations() true, which blocks server refreshes.
          final rolledBack = await _database.transaction(
              () => _reconcileRejection(currentOp, reason, removed, rewritten));
          if (currentOp.operation == 'CREATE_STORE' &&
              currentOp.entityId != null &&
              _prefs.getString(kActiveStoreIdKey) == currentOp.entityId) {
            await _prefs.remove(kActiveStoreIdKey);
          }
          conflicts.add(notice(rolledBack: rolledBack));
        } else if (status == 'CONFLICT') {
          conflicts.add(result);
          await (_database.delete(_database.syncQueue)
                ..where((t) => t.id.equals(opId)))
              .go();
        }
      }
    } on DioException catch (e) {
      // HTTP 423: server-side 7-day gate — throw SyncRequiredException to abort pull cycle
      if (e.response?.statusCode == 423) {
        final details =
            (e.response?.data?['details'] as Map<String, dynamic>?);
        final days = (details?['daysSinceLastSync'] as num?)?.toInt() ?? 7;
        final lastPushAt = details?['lastPushAt'] as String?;
        throw SyncRequiredException(
            daysSinceLastSync: days, lastPushAt: lastPushAt);
      }
      // Network error — increment retry on all ops in this batch
      for (final op in ops) {
        await (_database.update(_database.syncQueue)
              ..where((t) => t.id.equals(op.id)))
            .write(SyncQueueCompanion(
          retryCount: Value(op.retryCount + 1),
          lastAttemptAt: Value(DateTime.now()),
        ));
      }
      rethrow;
    }
    return conflicts;
  }

  /// Puts [op] in quarantine (v1s-16-15) with, for a `CREATE_SALE`, its
  /// queued `VALIDATE_SALE` / `CANCEL_SALE` (the chain), whose ids are
  /// recorded in [quarantined]. Runs inside the caller's transaction.
  Future<void> _quarantine(
      SyncQueueData op, String? reason, Set<String> quarantined) async {
    final now = DateTime.now();
    await (_database.update(_database.syncQueue)
          ..where((t) => t.id.equals(op.id)))
        .write(SyncQueueCompanion(
      retryCount: Value(op.retryCount + 1),
      lastAttemptAt: Value(now),
      lastError: Value(reason),
      quarantinedAt: Value(now),
    ));
    final entityId = op.entityId;
    if (op.operation != 'CREATE_SALE' || entityId == null) return;
    final chained = await (_database.select(_database.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.quarantinedAt.isNull() &
              t.entityId.equals(entityId) &
              t.operation.isIn(saleChainOperations)))
        .get();
    if (chained.isEmpty) return;
    final ids = chained.map((q) => q.id).toList();
    await (_database.update(_database.syncQueue)..where((t) => t.id.isIn(ids)))
        .write(SyncQueueCompanion(quarantinedAt: Value(now)));
    quarantined.addAll(ids);
  }

  /// Reconciles the local state after a terminal rejection of [op] (see
  /// `sync_rejection_policy.dart`) and deletes [op] plus every queued
  /// operation that depended on it, recording their ids in [removed] (and
  /// the ids of queued operations whose payload was rewritten in
  /// [rewritten]). Returns whether a local change was undone on the device.
  /// Runs inside the caller's transaction.
  Future<bool> _reconcileRejection(SyncQueueData op, String? reason,
      Set<String> removed, Set<String> rewritten) async {
    final queue = await (_database.select(_database.syncQueue)
          ..where((t) => t.synced.equals(false)))
        .get();
    final toDrop = <String>{op.id};
    var rolledBack = false;
    final operation = op.operation;
    final entityId = op.entityId;

    if (ghostCreateOperations.contains(operation) && entityId != null) {
      await _rollbackGhostEntity(operation, entityId);
      toDrop.addAll(queue.where((q) => q.entityId == entityId).map((q) => q.id));
      if (operation == 'CREATE_PRODUCT' ||
          operation == 'CREATE_DRAFT_PRODUCT') {
        // Stock entries and counts for the ghost product (quick-add queues
        // them under their own entity ids). Sales are never dropped.
        toDrop.addAll(queue
            .where((q) =>
                q.operation != 'CREATE_SALE' &&
                _payloadOf(q)['productId'] == entityId)
            .map((q) => q.id));
      }
      if (operation == 'CREATE_STORE') {
        // Stock movements recorded in the ghost store. Sales and closures
        // are never dropped.
        toDrop.addAll(queue
            .where((q) {
              final p = _payloadOf(q);
              return switch (q.operation) {
                'RECORD_STOCK_ENTRY' || 'STOCK_ADJUST' =>
                  p['storeId'] == entityId,
                'STOCK_TRANSFER' => p['sourceStoreId'] == entityId ||
                    p['destinationStoreId'] == entityId,
                _ => false,
              };
            })
            .map((q) => q.id));
      }
      // Queued operations that only reference the ghost keep going without
      // the reference instead of being refused (and rolled back) in turn.
      final strip = switch (operation) {
        'CREATE_CATEGORY' => (
            const {
              'CREATE_PRODUCT',
              'CREATE_DRAFT_PRODUCT',
              'UPDATE_PRODUCT',
              'PROMOTE_PRODUCT',
            },
            'categoryId'
          ),
        'CREATE_CLIENT' => (const {'CREATE_SALE'}, 'clientId'),
        _ => null,
      };
      if (strip != null) {
        final (operations, key) = strip;
        for (final q in queue) {
          if (toDrop.contains(q.id) || !operations.contains(q.operation)) {
            continue;
          }
          final payload = Map<String, dynamic>.of(_payloadOf(q));
          if (payload[key] != entityId) continue;
          payload.remove(key);
          await (_database.update(_database.syncQueue)
                ..where((t) => t.id.equals(q.id)))
              .write(SyncQueueCompanion(payload: Value(jsonEncode(payload))));
          rewritten.add(q.id);
        }
      }
      rolledBack = true;
    } else if (const {
      'CREATE_INVENTORY_SESSION',
      'SAVE_INVENTORY_COUNT',
      'VALIDATE_INVENTORY',
    }.contains(operation)) {
      final sessionId = operation == 'SAVE_INVENTORY_COUNT'
          ? _payloadOf(op)['sessionId'] as String?
          : entityId ?? _payloadOf(op)['sessionId'] as String?;
      if (sessionId != null) {
        Iterable<String> sessionOps() => queue
            .where((q) =>
                ((q.operation == 'CREATE_INVENTORY_SESSION' ||
                        q.operation == 'VALIDATE_INVENTORY') &&
                    q.entityId == sessionId) ||
                (q.operation == 'SAVE_INVENTORY_COUNT' &&
                    _payloadOf(q)['sessionId'] == sessionId))
            .map((q) => q.id);
        final now = DateTime.now();
        final sessionRow = _database.update(_database.inventorySessions)
          ..where((t) => t.id.equals(sessionId));

        if (operation == 'CREATE_INVENTORY_SESSION' ||
            reason == 'INVENTORY_SESSION_NOT_FOUND') {
          // The server never had (or no longer has) this session: abandon it.
          await _revertInventoryAdjustments(sessionId);
          await sessionRow.write(InventorySessionsCompanion(
            status: const Value('CANCELLED'),
            cancelledAt: Value(now),
            updatedAt: Value(now),
          ));
          toDrop.addAll(sessionOps());
          rolledBack = true;
        } else if (reason == 'INVENTORY_SESSION_NOT_IN_PROGRESS') {
          // Closed on the server (cancelled or validated elsewhere): undo
          // the offline validation and let the next pull bring its status.
          await _revertInventoryAdjustments(sessionId);
          await sessionRow
              .write(const InventorySessionsCompanion(synced: Value(true)));
          toDrop.addAll(sessionOps());
          rolledBack = true;
        } else if (operation == 'VALIDATE_INVENTORY') {
          // Validation refused (e.g. FORBIDDEN): the session stays open on
          // the server, so it goes back to IN_PROGRESS here too.
          await _revertInventoryAdjustments(sessionId);
          await sessionRow.write(InventorySessionsCompanion(
            status: const Value('IN_PROGRESS'),
            completedAt: const Value(null),
            updatedAt: Value(now),
          ));
          rolledBack = true;
        } else if (operation == 'SAVE_INVENTORY_COUNT' && entityId != null) {
          // The server never got this count: a later offline validation must
          // not apply an adjustment from it.
          await _database.customStatement(
              'DELETE FROM inventory_counts WHERE id = ?', [entityId]);
          rolledBack = true;
        }
      }
    }

    await (_database.delete(_database.syncQueue)
          ..where((t) => t.id.isIn(toDrop)))
        .go();
    removed.addAll(toDrop);
    return rolledBack;
  }

  static Map<String, dynamic> _payloadOf(SyncQueueData op) {
    try {
      final decoded = jsonDecode(op.payload);
      return decoded is Map<String, dynamic> ? decoded : const {};
    } catch (_) {
      return const {};
    }
  }

  /// Deletes the local row created offline by [operation] for [entityId].
  Future<void> _rollbackGhostEntity(String operation, String entityId) async {
    switch (operation) {
      case 'CREATE_PRODUCT':
      case 'CREATE_DRAFT_PRODUCT':
        for (final table in const [
          'inventory_counts',
          'stock_levels',
          'stock_movements',
        ]) {
          await _database.customStatement(
              'DELETE FROM $table WHERE product_id = ?', [entityId]);
        }
        await _database.customStatement(
            'DELETE FROM product_suppliers WHERE product_id = ?', [entityId]);
        await _database
            .customStatement('DELETE FROM products WHERE id = ?', [entityId]);
      case 'CREATE_CATEGORY':
        await _database.customStatement(
            'UPDATE products SET category_id = NULL WHERE category_id = ?',
            [entityId]);
        await _database.customStatement(
            'UPDATE categories SET parent_id = NULL WHERE parent_id = ?',
            [entityId]);
        await _database
            .customStatement('DELETE FROM categories WHERE id = ?', [entityId]);
      case 'CREATE_STORE':
        for (final table in const ['stock_levels', 'stock_movements']) {
          await _database.customStatement(
              'DELETE FROM $table WHERE store_id = ?', [entityId]);
        }
        // Local transfer history of the ghost store (its queued
        // STOCK_TRANSFER ops are dropped by the caller).
        await _database.customStatement(
            'DELETE FROM stock_transfers '
            'WHERE source_store_id = ? OR destination_store_id = ?',
            [entityId, entityId]);
        await _database
            .customStatement('DELETE FROM stores WHERE id = ?', [entityId]);
      case 'CREATE_CLIENT':
        // The sales are kept, without the client.
        await _database.customStatement(
            'UPDATE sales SET client_id = NULL WHERE client_id = ?',
            [entityId]);
        await _database
            .customStatement('DELETE FROM clients WHERE id = ?', [entityId]);
      case 'CREATE_SUPPLIER':
        await _database.customStatement(
            'DELETE FROM product_suppliers WHERE supplier_id = ?', [entityId]);
        await _database
            .customStatement('DELETE FROM suppliers WHERE id = ?', [entityId]);
    }
  }

  /// Reverts the stock adjustments an offline validation of [sessionId]
  /// applied (unsynced ADJUSTMENT movements tagged with the session) and
  /// deletes those movements. Quantities never go below 0.
  Future<void> _revertInventoryAdjustments(String sessionId) async {
    final movements = await (_database.select(_database.stockMovements)
          ..where((t) =>
              t.inventorySessionId.equals(sessionId) & t.synced.equals(false)))
        .get();
    for (final m in movements) {
      // Same lookup as the offline validation: exact variant, else the
      // (product, store) row.
      final exact = await (_database.select(_database.stockLevels)
            ..where((t) {
              final base =
                  t.productId.equals(m.productId) & t.storeId.equals(m.storeId);
              final variantId = m.variantId;
              return variantId == null
                  ? base & t.variantId.isNull()
                  : base & t.variantId.equals(variantId);
            }))
          .get();
      final rows = exact.isNotEmpty
          ? exact
          : await (_database.select(_database.stockLevels)
                ..where((t) =>
                    t.productId.equals(m.productId) &
                    t.storeId.equals(m.storeId)))
              .get();
      if (rows.isNotEmpty) {
        final level = rows.first;
        await (_database.update(_database.stockLevels)
              ..where((t) => t.id.equals(level.id)))
            .write(StockLevelsCompanion(
          quantity: Value(max(0, level.quantity - m.quantityDelta)),
          updatedAt: Value(DateTime.now()),
        ));
      }
      await (_database.delete(_database.stockMovements)
            ..where((t) => t.id.equals(m.id)))
          .go();
    }
  }

  /// Quarantined operations are not pending: they are never pushed and must
  /// not block the server refreshes (v1s-16-15).
  @override
  Future<bool> hasPendingOperations() async {
    final count = await (_database.selectOnly(_database.syncQueue)
          ..addColumns([_database.syncQueue.id.count()])
          ..where(_database.syncQueue.synced.equals(false) &
              _database.syncQueue.quarantinedAt.isNull()))
        .map((row) => row.read(_database.syncQueue.id.count()))
        .getSingle();
    return (count ?? 0) > 0;
  }

  @override
  Future<int> quarantinedOperationCount() async {
    final count = await (_database.selectOnly(_database.syncQueue)
          ..addColumns([_database.syncQueue.id.count()])
          ..where(_database.syncQueue.synced.equals(false) &
              _database.syncQueue.quarantinedAt.isNotNull()))
        .map((row) => row.read(_database.syncQueue.id.count()))
        .getSingle();
    return count ?? 0;
  }

  Future<String> _getDeviceId() async {
    const key = kDeviceIdKey;
    var deviceId = await _secureStorage.read(key: key);
    if (deviceId == null) {
      deviceId = const Uuid().v4();
      await _secureStorage.write(key: key, value: deviceId);
    }
    return deviceId;
  }

  // Single-flight: a pull already running is joined, not duplicated (two
  // concurrent pulls would race on the cursor they save).
  Future<void>? _pullInFlight;

  /// Upper bound on pages per pull — a guard against a server that never
  /// reports the end of the delta.
  static const _maxPullPages = 500;

  @override
  Future<void> pull() {
    final running = _pullInFlight;
    if (running != null) return running;
    final pull = _pull().whenComplete(() => _pullInFlight = null);
    _pullInFlight = pull;
    return pull;
  }

  Future<void> _pull() async {
    try {
      // 1. Read last pull timestamp
      var lastSyncMs = await _secureStorage.read(key: kLastSyncTimestampKey);

      // Guard against stale SecureStorage after Android "Clear Data".
      // SharedPreferences is reliably cleared, but FlutterSecureStorage
      // (Android Keystore) may survive — causing delta pulls against an
      // empty local DB.  When SharedPrefs has no gate key but SecureStorage
      // still has a cursor, reset to force a full sync.
      final prefsLastSync = _prefs.getInt(kLastSyncAtKey);
      if (prefsLastSync == null && lastSyncMs != null) {
        dev.log('Stale sync cursor detected — forcing full sync',
            name: 'RestSync');
        await _secureStorage.delete(key: kLastSyncTimestampKey);
        lastSyncMs = null;
      }

      // Rows pulled before dates were stored with their local offset are read
      // as UTC (one hour off), and a delta pull never sends them again: one
      // full pull rewrites them in the current format.
      if (!(_prefs.getBool(kPullTzFixDoneKey) ?? false)) {
        if (lastSyncMs != null) {
          dev.log('Date format upgrade — forcing one full sync', name: 'RestSync');
          await _secureStorage.delete(key: kLastSyncTimestampKey);
          lastSyncMs = null;
        }
        await _prefs.setBool(kPullTzFixDoneKey, true);
      }

      String? since;
      if (lastSyncMs != null) {
        final lastSync =
            DateTime.fromMillisecondsSinceEpoch(int.parse(lastSyncMs));
        since = lastSync.toUtc().toIso8601String();
      }

      // 2. Call pull endpoint (include deviceId header for device registration).
      // The server pages a large delta (hasMore + nextCursor): fetch every
      // page with the same `since` before saving the new cursor, otherwise the
      // rows of the remaining pages would be skipped for good.
      final deviceId = await _getDeviceId();
      String? cursor;
      late String serverTimestamp;
      var pages = 0;
      do {
        if (++pages > _maxPullPages) {
          throw StateError('Pull did not finish after $_maxPullPages pages');
        }
        final params = <String, dynamic>{
          if (since != null) 'since': since,
          if (cursor != null) 'cursor': cursor,
        };
        final response = await _dio.get<Map<String, dynamic>>(
          '/api/v1/sync/pull',
          queryParameters: params.isEmpty ? null : params,
          options: Options(headers: {'X-Device-Id': deviceId}),
        );

        final data = response.data!['data'] as Map<String, dynamic>;
        serverTimestamp = data['serverTimestamp'] as String;
        await _mergePulledEntities(data['entities'] as Map<String, dynamic>);
        cursor = data['hasMore'] == true ? data['nextCursor'] as String? : null;
      } while (cursor != null);

      // 4. Store serverTimestamp as new lastPullTimestamp
      final serverInstant = DateTime.parse(serverTimestamp);
      await _secureStorage.write(
        key: kLastSyncTimestampKey,
        value: serverInstant.millisecondsSinceEpoch.toString(),
      );
      // 5. Update SharedPreferences gate key (synchronous access for gate check)
      await _prefs.setInt(
          kLastSyncAtKey, serverInstant.millisecondsSinceEpoch);
    } catch (e) {
      dev.log('Pull sync failed: $e', name: 'RestSync');
      rethrow;
    }
  }

  Future<void> _mergePulledEntities(Map<String, dynamic> entities) async {
    // 3. Load pending entity IDs once for AC7 protection
    final pendingIds = await _getPendingEntityIds();
    // Load pending (productId, storeId) keys affected by unsynced
    // stock-mutating operations — stock_levels is a pre-existing server
    // resource (not keyed by entityId like the tables above), so it needs
    // its own composite-key protection against pull overwrite.
    final pendingStockKeys = await _getPendingStockKeys();

    // 4. Upsert all entities in a single transaction
    await _database.transaction(() async {
      await _upsertProducts(
          entities['products'] as List<dynamic>? ?? [], pendingIds);
      await _upsertStockLevels(
          entities['stockLevels'] as List<dynamic>? ?? [],
          pendingStockKeys);
      await _upsertCategories(
          entities['categories'] as List<dynamic>? ?? [], pendingIds);
      await _upsertClients(
          entities['clients'] as List<dynamic>? ?? [], pendingIds);
      await _upsertSuppliers(
          entities['suppliers'] as List<dynamic>? ?? [], pendingIds);
      await _upsertStores(
          entities['stores'] as List<dynamic>? ?? [], pendingIds);
      await _upsertEmployees(
          entities['employees'] as List<dynamic>? ?? [], pendingIds);
      await _upsertSales(
          entities['sales'] as List<dynamic>? ?? [], pendingIds);
      await _upsertDayClosures(
          entities['dayClosures'] as List<dynamic>? ?? []);
      await _upsertStockMovements(
          entities['stockMovements'] as List<dynamic>? ?? []);
      await _upsertStockTransfers(
          entities['stockTransfers'] as List<dynamic>? ?? [], pendingIds);
      await _upsertAuditEntries(
          entities['auditEntries'] as List<dynamic>? ?? []);
      await _upsertInventorySessions(
          entities['inventorySessions'] as List<dynamic>? ?? []);
      await _upsertInventoryCounts(
          entities['inventoryCounts'] as List<dynamic>? ?? []);
      await _upsertReports(
          entities['reports'] as List<dynamic>? ?? []);
    });
  }

  // ── Upsert helpers ────────────────────────────────────────────────────────

  /// Converts a server UTC ISO-8601 timestamp to the text Drift itself writes
  /// for a local DateTime (storeDateTimeAsText: local time plus its offset,
  /// e.g. `2026-03-22T00:30:00.000 +01:00`). Pulled rows then sort and compare
  /// like locally-created ones, and read back through Drift as the same
  /// instant — local time without the offset would be read as UTC.
  /// Metadata arrives as JSON text from the pull delta; a map is re-encoded.
  static String? _jsonText(dynamic value) {
    if (value == null) return null;
    if (value is String) return value;
    return jsonEncode(value);
  }

  String? _toLocalIso(dynamic utcIso) {
    if (utcIso == null) return null;
    return _database.typeMapping
        .mapToSqlVariable(DateTime.parse(utcIso as String).toLocal()) as String;
  }

  /// AC7 — Get entity IDs that have pending (unsynced) operations in sync_queue.
  /// These records must NOT be overwritten by pull data.
  Future<Set<String>> _getPendingEntityIds() async {
    // Quarantined operations are never pushed: they protect nothing.
    final pendingOps = await (_database.select(_database.syncQueue)
          ..where((t) => t.synced.equals(false) & t.quarantinedAt.isNull()))
        .get();
    return pendingOps
        .map((o) => o.entityId)
        .whereType<String>()
        .toSet();
  }

  /// Bug fix — pull-merge protection for `stock_levels`.
  ///
  /// Unlike products/categories/etc. (protected via `_getPendingEntityIds`,
  /// keyed by `entityId`), `stock_levels` rows are a pre-existing server
  /// resource keyed by the composite business key `(productId, storeId)` —
  /// the pull always finds a server row for them, so entityId-based
  /// protection doesn't apply. This computes the set of `(productId,
  /// storeId)` pairs affected by unsynced offline operations that mutate
  /// stock (STOCK_ADJUST, RECORD_STOCK_ENTRY, CREATE_SALE, STOCK_TRANSFER),
  /// plus the stock touched locally by a pending VALIDATE_SALE /
  /// VALIDATE_INVENTORY,
  /// so `_upsertStockLevels` can skip overwriting those rows with stale
  /// server data while the operation is still pending in `sync_queue`.
  Future<Set<String>> _getPendingStockKeys() async {
    const stockMutatingOps = {
      'STOCK_ADJUST',
      'RECORD_STOCK_ENTRY',
      'CREATE_SALE',
      'STOCK_TRANSFER',
      'VALIDATE_SALE',
      'VALIDATE_INVENTORY',
    };
    final pendingOps = await (_database.select(_database.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.quarantinedAt.isNull() &
              t.operation.isIn(stockMutatingOps)))
        .get();

    final keys = <String>{};
    for (final op in pendingOps) {
      Map<String, dynamic> payload;
      try {
        payload = jsonDecode(op.payload) as Map<String, dynamic>;
      } catch (_) {
        // Malformed payload — ignore defensively rather than fail the pull.
        continue;
      }

      switch (op.operation) {
        case 'STOCK_ADJUST':
        case 'RECORD_STOCK_ENTRY':
          final key = _stockKey(payload['productId'], payload['storeId']);
          if (key != null) keys.add(key);
          break;
        case 'CREATE_SALE':
          final storeId = payload['storeId'];
          final items = payload['items'] as List<dynamic>? ?? [];
          for (final item in items) {
            if (item is! Map<String, dynamic>) continue;
            final key = _stockKey(item['productId'], storeId);
            if (key != null) keys.add(key);
          }
          break;
        case 'STOCK_TRANSFER':
          final productId = payload['productId'];
          final srcKey = _stockKey(productId, payload['sourceStoreId']);
          if (srcKey != null) keys.add(srcKey);
          final dstKey = _stockKey(productId, payload['destinationStoreId']);
          if (dstKey != null) keys.add(dstKey);
          break;
        // Their payloads carry no product/store: the stock they changed
        // locally is read back from the local sale / inventory session.
        case 'VALIDATE_SALE':
          final saleId = payload['saleId'];
          if (saleId is! String) break;
          final rows = await _database.customSelect(
            'SELECT si.product_id, s.store_id FROM sale_items si '
            'JOIN sales s ON s.id = si.sale_id WHERE si.sale_id = ?',
            variables: [Variable<String>(saleId)],
          ).get();
          String? storeId;
          for (final row in rows) {
            storeId = row.read<String>('store_id');
            final key = _stockKey(row.read<String>('product_id'), storeId);
            if (key != null) keys.add(key);
          }
          storeId ??= (await _database.customSelect(
            'SELECT store_id FROM sales WHERE id = ?',
            variables: [Variable<String>(saleId)],
          ).getSingleOrNull())
              ?.read<String>('store_id');
          final entries = payload['initialStockEntries'];
          if (entries is Map) {
            for (final productId in entries.keys) {
              final key = _stockKey(productId, storeId);
              if (key != null) keys.add(key);
            }
          }
          final remappings = payload['productIdRemappings'];
          if (remappings is Map) {
            for (final productId in remappings.values) {
              final key = _stockKey(productId, storeId);
              if (key != null) keys.add(key);
            }
          }
          break;
        case 'VALIDATE_INVENTORY':
          final sessionId = payload['sessionId'];
          if (sessionId is! String) break;
          final rows = await _database.customSelect(
            'SELECT c.product_id, s.store_id FROM inventory_counts c '
            'JOIN inventory_sessions s ON s.id = c.session_id '
            'WHERE c.session_id = ?',
            variables: [Variable<String>(sessionId)],
          ).get();
          for (final row in rows) {
            final key = _stockKey(
                row.read<String>('product_id'), row.read<String>('store_id'));
            if (key != null) keys.add(key);
          }
          break;
      }
    }
    return keys;
  }

  /// Builds the `"productId|storeId"` composite key used by
  /// `_getPendingStockKeys`/`_upsertStockLevels`. Returns null (rather than
  /// throwing) when either field is missing or not a String, so a malformed
  /// payload is silently ignored instead of crashing the pull.
  static String? _stockKey(dynamic productId, dynamic storeId) {
    if (productId is! String || storeId is! String) return null;
    return '$productId|$storeId';
  }

  Future<void> _upsertProducts(
      List<dynamic> products, Set<String> pendingIds) async {
    if (products.isEmpty) return;

    // Preserve local-only photoUrl values (backend doesn't store images)
    final localProducts = await _database.select(_database.products).get();
    final photoUrlMap = <String, String>{};
    for (final p in localProducts) {
      if (p.photoUrl != null && p.photoUrl!.isNotEmpty) {
        photoUrlMap[p.id] = p.photoUrl!;
      }
    }

    for (final p in products) {
      final map = p as Map<String, dynamic>;
      final id = map['id'] as String;

      // AC7: skip if pending push for this product
      if (pendingIds.contains(id)) continue;

      await _database.customStatement(
        'INSERT INTO products (id, name, description, sku, category_id, price, '
        'buy_price, transport_cost, stock_quantity, photo_url, archived, status, '
        'created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'name = excluded.name, description = excluded.description, '
        'sku = excluded.sku, category_id = excluded.category_id, '
        'price = excluded.price, buy_price = excluded.buy_price, '
        'transport_cost = excluded.transport_cost, '
        'stock_quantity = excluded.stock_quantity, '
        'photo_url = COALESCE(excluded.photo_url, products.photo_url), '
        'archived = excluded.archived, status = excluded.status, '
        'updated_at = excluded.updated_at',
        [
          id,
          map['name'],
          map['description'],
          map['sku'] ?? '',
          map['categoryId'],
          map['price'],
          map['buyPrice'],
          map['transportCost'] ?? 0,
          map['stockQuantity'] ?? 0,
          map['photoUrl'] ?? photoUrlMap[id],
          (map['archived'] == true) ? 1 : 0,
          map['status'] ?? 'ACTIVE',
          _toLocalIso(map['createdAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  Future<void> _upsertStockLevels(
      List<dynamic> levels, Set<String> pendingStockKeys) async {
    if (levels.isEmpty) return;
    for (final l in levels) {
      final map = l as Map<String, dynamic>;

      // Bug fix: skip rows with a pending (unsynced) offline stock-mutating
      // operation for this (productId, storeId) — otherwise the pull would
      // silently overwrite the local quantity with stale server data while
      // the operation is still queued for push (see _getPendingStockKeys).
      final key = _stockKey(map['productId'], map['storeId']);
      if (key != null && pendingStockKeys.contains(key)) continue;

      // Conflict target is (product_id, store_id) — the natural business key.
      // The server may assign a different UUID than the locally-generated one,
      // so conflicting on id alone would miss existing rows and hit the
      // UNIQUE INDEX on (product_id, store_id), crashing the transaction.
      await _database.customStatement(
        'INSERT INTO stock_levels (id, product_id, variant_id, store_id, '
        'quantity, minimum_threshold, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(product_id, store_id) DO UPDATE SET '
        'id = excluded.id, '
        'variant_id = excluded.variant_id, '
        'quantity = excluded.quantity, '
        'minimum_threshold = excluded.minimum_threshold, '
        'updated_at = excluded.updated_at',
        [
          map['id'],
          map['productId'],
          map['variantId'],
          map['storeId'],
          map['quantity'],
          (map['minimumThreshold'] as int?) ?? 0,
          _toLocalIso(map['updatedAt']) ?? DateTime.now().toIso8601String(),
        ],
      );
    }
  }

  Future<void> _upsertCategories(
      List<dynamic> categories, Set<String> pendingIds) async {
    if (categories.isEmpty) return;
    for (final c in categories) {
      final map = c as Map<String, dynamic>;
      final id = map['id'] as String;
      if (pendingIds.contains(id)) continue; // AC7
      await _database.customStatement(
        'INSERT INTO categories (id, name, parent_id, is_active, is_custom, '
        'created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'name = excluded.name, parent_id = excluded.parent_id, '
        'is_active = excluded.is_active, is_custom = excluded.is_custom, '
        'updated_at = excluded.updated_at',
        [
          map['id'],
          map['name'],
          map['parentId'],
          (map['isActive'] == true) ? 1 : 0,
          (map['isCustom'] == true) ? 1 : 0,
          _toLocalIso(map['createdAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }

    // G3: Remove duplicate categories with the same (name, parent_id) keeping
    // the one with the oldest created_at (i.e. the first one inserted).
    await _database.customStatement(
      'DELETE FROM categories '
      'WHERE id NOT IN ('
      '  SELECT id FROM categories '
      '  GROUP BY LOWER(name), COALESCE(parent_id, \'\') '
      '  HAVING id = MIN(id)'
      ')',
    );
  }

  Future<void> _upsertClients(
      List<dynamic> clients, Set<String> pendingIds) async {
    if (clients.isEmpty) return;
    for (final c in clients) {
      final map = c as Map<String, dynamic>;
      final id = map['id'] as String;
      if (pendingIds.contains(id)) continue; // AC7
      await _database.customStatement(
        'INSERT INTO clients (id, name, phone, email, notes, archived, '
        'created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'name = excluded.name, phone = excluded.phone, '
        'email = excluded.email, notes = excluded.notes, '
        'archived = excluded.archived, updated_at = excluded.updated_at',
        [
          map['id'],
          map['name'],
          map['phone'],
          map['email'],
          map['notes'],
          (map['archived'] == true) ? 1 : 0,
          _toLocalIso(map['createdAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  Future<void> _upsertSuppliers(
      List<dynamic> suppliers, Set<String> pendingIds) async {
    if (suppliers.isEmpty) return;
    for (final s in suppliers) {
      final map = s as Map<String, dynamic>;
      final id = map['id'] as String;
      if (pendingIds.contains(id)) continue; // AC7
      await _database.customStatement(
        'INSERT INTO suppliers (id, name, phone, email, archived, '
        'created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'name = excluded.name, phone = excluded.phone, '
        'email = excluded.email, archived = excluded.archived, '
        'updated_at = excluded.updated_at',
        [
          map['id'],
          map['name'],
          map['phone'],
          map['email'],
          (map['archived'] == true) ? 1 : 0,
          _toLocalIso(map['createdAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  Future<void> _upsertStores(
      List<dynamic> stores, Set<String> pendingIds) async {
    if (stores.isEmpty) return;
    for (final s in stores) {
      final map = s as Map<String, dynamic>;
      final id = map['id'] as String;
      if (pendingIds.contains(id)) continue; // AC7
      await _database.customStatement(
        'INSERT INTO stores (id, name, tenant_id, type, address, phone, '
        'is_active, created_at, updated_at) '
        'VALUES (?, ?, \'\', ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'name = excluded.name, type = excluded.type, '
        'address = excluded.address, phone = excluded.phone, '
        'is_active = excluded.is_active, updated_at = excluded.updated_at',
        [
          map['id'],
          map['name'],
          map['type'] ?? 'STORE',
          map['address'],
          map['phone'],
          (map['isActive'] == true) ? 1 : 0,
          _toLocalIso(map['createdAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  Future<void> _upsertEmployees(
      List<dynamic> employees, Set<String> pendingIds) async {
    if (employees.isEmpty) return;
    for (final e in employees) {
      final map = e as Map<String, dynamic>;
      final id = map['id'] as String;
      if (pendingIds.contains(id)) continue; // AC7
      await _database.customStatement(
        'INSERT INTO employees (id, user_id, first_name, last_name, store_id, '
        'status, password_change_required, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'first_name = excluded.first_name, last_name = excluded.last_name, '
        'store_id = excluded.store_id, status = excluded.status, '
        'password_change_required = excluded.password_change_required',
        [
          map['id'],
          map['userId'],
          map['firstName'],
          map['lastName'],
          map['storeId'],
          map['status'] ?? 'ACTIVE',
          (map['passwordChangeRequired'] == true) ? 1 : 0,
          _toLocalIso(map['createdAt']),
        ],
      );
    }
  }

  Future<void> _upsertSales(
      List<dynamic> sales, Set<String> pendingIds) async {
    if (sales.isEmpty) return;
    for (final s in sales) {
      final map = s as Map<String, dynamic>;
      // AC7: a local validation/cancellation not pushed yet wins.
      if (pendingIds.contains(map['id'])) continue;
      await _database.customStatement(
        'INSERT INTO sales (id, store_id, employee_id, total_amount, '
        'discount_amount, payment_mode, client_id, status, occurred_at, '
        'synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        // Never downgrade a locally-completed/cancelled sale back to
        // PENDING_VALIDATION with stale server state. Any other server status
        // (a cancellation, a validation) and corrected amounts do apply.
        'status = CASE WHEN excluded.status = ? '
        'AND sales.status IN (?, ?) '
        'THEN sales.status ELSE excluded.status END, '
        'total_amount = excluded.total_amount, '
        'discount_amount = excluded.discount_amount, '
        'payment_mode = excluded.payment_mode, '
        'client_id = excluded.client_id, '
        'synced = 1, '
        'occurred_at = excluded.occurred_at, created_at = excluded.created_at',
        [
          map['id'],
          map['storeId'],
          map['employeeId'],
          map['totalAmount'],
          map['discountAmount'] ?? 0,
          map['paymentMode'],
          map['clientId'],
          map['status'] ?? 'COMPLETED',
          _toLocalIso(map['occurredAt']),
          _toLocalIso(map['createdAt']),
          // Parameters for the CASE guard in ON CONFLICT DO UPDATE:
          'PENDING_VALIDATION',
          'COMPLETED',
          'CANCELLED',
        ],
      );
      // Delete existing sale_items then re-insert from server (authoritative)
      final items = map['items'] as List<dynamic>? ?? [];
      if (items.isNotEmpty) {
        await _database.customStatement(
          'DELETE FROM sale_items WHERE sale_id = ?',
          [map['id']],
        );
      }
      for (final item in items) {
        final iMap = item as Map<String, dynamic>;
        await _database.customStatement(
          'INSERT OR IGNORE INTO sale_items (id, sale_id, product_id, variant_id, '
          'product_name, unit_price, catalogue_unit_price, quantity, subtotal, '
          'created_at) '
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
          [
            iMap['id'],
            map['id'],
            iMap['productId'],
            iMap['variantId'],
            iMap['productName'],
            iMap['appliedUnitPrice'],
            iMap['catalogueUnitPrice'] ?? iMap['appliedUnitPrice'],
            iMap['quantity'],
            iMap['subtotal'],
            map['createdAt'],
          ],
        );
      }
    }
  }

  Future<void> _upsertDayClosures(List<dynamic> closures) async {
    if (closures.isEmpty) return;
    for (final c in closures) {
      final map = c as Map<String, dynamic>;
      final closedAtLocal = _toLocalIso(map['closedAt']);
      final isAutomatic = (map['isAutomatic'] == true) ? 1 : 0;
      await _database.customStatement(
        'INSERT INTO day_closures (id, store_id, actor_id, closed_at, '
        'total_sales, total_revenue, cash_amount, momo_amount, '
        'pending_sales_count, pending_sales_total, is_automatic, '
        'synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 1, ?) '
        // The server keeps the client's closure id: a closure pushed from
        // here comes back with it and is now confirmed.
        // Its totals are the server's: late offline sales synced after the
        // previous closure belong to this one, which the device cannot know.
        'ON CONFLICT(id) DO UPDATE SET is_automatic = excluded.is_automatic, '
        'total_sales = excluded.total_sales, '
        'total_revenue = excluded.total_revenue, '
        'cash_amount = excluded.cash_amount, '
        'momo_amount = excluded.momo_amount, '
        'synced = 1',
        [
          map['id'],
          map['storeId'],
          map['employeeId'],
          closedAtLocal,
          map['totalSales'],
          map['totalRevenue'],
          map['cashTotal'],
          map['mobileMoneyTotal'],
          isAutomatic,
          closedAtLocal,
        ],
      );
    }
  }

  Future<void> _upsertStockMovements(List<dynamic> movements) async {
    if (movements.isEmpty) return;
    for (final m in movements) {
      final map = m as Map<String, dynamic>;
      // Movements are immutable on the server, which is the source of truth for a
      // given id. v1s-13-13: an owner cancel/correction is also written locally under
      // the server's deterministic id (cashier as actor, no reason, local quantities),
      // so the pulled row overwrites those fields; `synced_at` (purge clock) is kept.
      await _database.customStatement(
        'INSERT INTO stock_movements (id, product_id, variant_id, '
        'store_id, type, quantity_before, quantity_delta, quantity_after, '
        'actor_id, reason, synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?) '
        'ON CONFLICT(id) DO UPDATE SET product_id = excluded.product_id, '
        'variant_id = excluded.variant_id, store_id = excluded.store_id, '
        'type = excluded.type, quantity_before = excluded.quantity_before, '
        'quantity_delta = excluded.quantity_delta, '
        'quantity_after = excluded.quantity_after, actor_id = excluded.actor_id, '
        'reason = excluded.reason, synced = 1, created_at = excluded.created_at',
        [
          map['id'],
          map['productId'],
          map['variantId'],
          map['storeId'],
          map['movementType'],
          map['quantityBefore'],
          map['quantityChange'],
          map['quantityAfter'],
          map['actorId'],
          map['notes'],
          _toLocalIso(map['occurredAt']),
        ],
      );
    }
  }

  Future<void> _upsertStockTransfers(
      List<dynamic> transfers, Set<String> pendingIds) async {
    if (transfers.isEmpty) return;
    for (final t in transfers) {
      final map = t as Map<String, dynamic>;
      final id = map['id'] as String;
      // Defense in depth, mirrors the AC7 pattern used by other tables:
      // skip if this transfer still has a pending (unsynced) STOCK_TRANSFER
      // in sync_queue (entityId == transfer id).
      if (pendingIds.contains(id)) continue;
      await _database.customStatement(
        'INSERT INTO stock_transfers (id, source_store_id, '
        'destination_store_id, product_id, variant_id, quantity, actor_id, '
        'occurred_at, status, notes) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'status = excluded.status, notes = excluded.notes',
        [
          map['id'],
          map['sourceStoreId'],
          map['destinationStoreId'],
          map['productId'],
          map['variantId'],
          map['quantity'],
          map['actorId'],
          _toLocalIso(map['occurredAt']),
          map['status'],
          map['notes'],
        ],
      );
    }
  }

  Future<void> _upsertAuditEntries(List<dynamic> entries) async {
    // v1s-18-7 (FR98): the audit log is owner-only. A non-owner device drops
    // any rows pulled before the server-side gate (they carry justification
    // context) and never stores new ones.
    if (_prefs.getString(kUserRoleKey) != 'OWNER') {
      await _database.delete(_database.auditEntries).go();
      return;
    }
    if (entries.isEmpty) return;
    for (final e in entries) {
      final map = e as Map<String, dynamic>;
      // INSERT OR IGNORE — audit entries are immutable
      await _database.customStatement(
        'INSERT OR IGNORE INTO audit_entries (id, user_id, entity_type, '
        'entity_id, action, value_before, value_after, occurred_at, '
        'store_id, entity_label, device_id, source, metadata) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          map['id'],
          map['userId'],
          map['entityType'],
          map['entityId'],
          map['action'],
          map['valueBefore'],
          map['valueAfter'],
          _toLocalIso(map['occurredAt']),
          // v1s-18-7 — enrichment keys (absent from older servers → null)
          map['storeId'],
          map['entityLabel'],
          map['deviceId'],
          map['source'],
          _jsonText(map['metadata']),
        ],
      );
    }
  }

  Future<void> _upsertInventorySessions(List<dynamic> sessions) async {
    if (sessions.isEmpty) return;
    for (final s in sessions) {
      final map = s as Map<String, dynamic>;
      final id = map['id'] as String;
      // Protect pending IDs (synced=false → local offline create not yet pushed)
      final pendingRows = await _database.customSelect(
        'SELECT 1 FROM inventory_sessions WHERE id = ? AND synced = 0',
        variables: [Variable<String>(id)],
      ).get();
      if (pendingRows.isNotEmpty) continue;

      await _database.customStatement(
        'INSERT INTO inventory_sessions '
        '(id, store_id, scope, category_ids, status, started_by, '
        'started_at, cancelled_by, cancelled_at, completed_at, updated_at, synced) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) '
        'ON CONFLICT(id) DO UPDATE SET '
        'status = excluded.status, '
        'cancelled_by = excluded.cancelled_by, '
        'cancelled_at = excluded.cancelled_at, '
        'completed_at = excluded.completed_at, '
        'updated_at = excluded.updated_at, '
        'synced = 1',
        [
          id,
          map['storeId'],
          map['scope'],
          map['categoryIds'],
          map['status'],
          map['startedBy'],
          _toLocalIso(map['startedAt']),
          map['cancelledBy'],
          _toLocalIso(map['cancelledAt']),
          _toLocalIso(map['completedAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  Future<void> _upsertReports(List<dynamic> reports) async {
    if (reports.isEmpty) return;
    for (final r in reports) {
      final map = r as Map<String, dynamic>;
      // reportDate comes as 'YYYY-MM-DD' LocalDate — parse as noon UTC to avoid
      // timezone-shift artefacts when Drift stores as local ISO-8601.
      final reportDateStr = map['reportDate'] as String?;
      final reportDate = reportDateStr != null
          ? DateTime.parse('${reportDateStr}T12:00:00').toLocal().toIso8601String()
          : null;
      await _database.customStatement(
        'INSERT INTO reports '
        '(id, tenant_id, store_id, store_name, report_type, report_date, '
        'content, delivery_status, delivery_attempts, last_attempt_at, '
        'total_revenue, total_sales, is_automatic, created_at, actor_id) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'delivery_status = excluded.delivery_status, '
        'delivery_attempts = excluded.delivery_attempts, '
        'last_attempt_at = excluded.last_attempt_at, '
        'store_name = excluded.store_name',
        [
          map['id'],
          map['tenantId'],
          map['storeId'],
          map['storeName'],
          map['reportType'],
          reportDate,
          map['content'],
          map['deliveryStatus'],
          map['deliveryAttempts'] ?? 0,
          _toLocalIso(map['lastAttemptAt']),
          map['totalRevenue'] ?? 0,
          map['totalSales'] ?? 0,
          ((map['isAutomatic'] ?? map['automatic']) == true ? 1 : 0),
          _toLocalIso(map['createdAt']),
          map['actorId'],
        ],
      );
    }
  }

  Future<void> _upsertInventoryCounts(List<dynamic> counts) async {
    if (counts.isEmpty) return;
    for (final c in counts) {
      final map = c as Map<String, dynamic>;
      final id = map['id'] as String;
      // Protect pending IDs (synced=false → local offline count not yet pushed)
      final pendingRows = await _database.customSelect(
        'SELECT 1 FROM inventory_counts WHERE id = ? AND synced = 0',
        variables: [Variable<String>(id)],
      ).get();
      if (pendingRows.isNotEmpty) continue;

      await _database.customStatement(
        'INSERT INTO inventory_counts '
        '(id, session_id, product_id, variant_id, product_name, variant_label, '
        'theoretical, physical, counted_by, counted_at, updated_at, synced) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) '
        'ON CONFLICT(id) DO UPDATE SET '
        'physical = excluded.physical, '
        'counted_by = excluded.counted_by, '
        'counted_at = excluded.counted_at, '
        'updated_at = excluded.updated_at, '
        'synced = 1',
        [
          id,
          map['sessionId'],
          map['productId'],
          map['variantId'],
          map['productName'],
          map['variantLabel'],
          map['theoretical'],
          map['physical'],
          map['countedBy'],
          _toLocalIso(map['countedAt']),
          _toLocalIso(map['updatedAt']),
        ],
      );
    }
  }

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  }) async {
    const uuid = Uuid();

    // Deduplicate: a one-shot operation already queued for this entity (e.g.
    // user taps Validate twice while offline) is skipped; a state change
    // (archive, deactivate...) is skipped only when it repeats the latest
    // queued operation of the entity, so ARCHIVE → UNARCHIVE → ARCHIVE keeps
    // all three. Edits and stock movements are never skipped — each carries a
    // newer value or a distinct quantity, and all must reach the server in
    // order.
    if (entityId != null && !_appendOnlyOperations.contains(operation)) {
      final query = _database.select(_database.syncQueue)
        ..where((t) => t.entityId.equals(entityId));
      final bool duplicate;
      if (_isOneShot(operation)) {
        query.where((t) => t.operation.equals(operation));
        duplicate = (await query.get()).isNotEmpty;
      } else {
        query
          ..orderBy([(t) => OrderingTerm.desc(t.createdAt)])
          ..limit(1);
        duplicate = (await query.getSingleOrNull())?.operation == operation;
      }
      if (duplicate) {
        dev.log(
          '⏭️ Skipping duplicate $operation for entity $entityId',
          name: 'RestSync',
        );
        return;
      }
    }

    dev.log('📥 Queueing operation: $operation', name: 'RestSync');

    await _database.into(_database.syncQueue).insert(
      SyncQueueCompanion.insert(
        id: uuid.v4(),
        operation: operation,
        payload: jsonEncode(payload),
        createdAt: DateTime.now(),
        entityId: Value(entityId),
      ),
    );

    dev.log('✅ Operation queued — SyncTriggerNotifier will handle push', name: 'RestSync');
  }
}