import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../features/catalog/data/datasource/remote_product_datasource.dart';
import '../storage/app_constants.dart';
import '../storage/app_database.dart';
import 'sync_service.dart';

/// RestSyncService — REST implementation for offline queue batch push sync.
///
/// push() replays queued offline operations as unified batches to
/// POST /api/v1/sync/push. Online writes go directly to individual
/// REST endpoints (backend-first pattern) — this service only handles
/// the offline safety-net queue.
class RestSyncService implements SyncService {
  final AppDatabase _database;
  final RemoteProductDataSource _remoteProducts;
  final Dio _dio;
  final FlutterSecureStorage _secureStorage;

  RestSyncService({
    required AppDatabase database,
    required RemoteProductDataSource remoteProducts,
    required Dio dio,
    required FlutterSecureStorage secureStorage,
  })  : _database = database,
        _remoteProducts = remoteProducts,
        _dio = dio,
        _secureStorage = secureStorage;

  @override
  Future<void> push() async {
    final pendingOps = await (_database.select(_database.syncQueue)
          ..where((t) => t.synced.equals(false))
          ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
        .get();

    if (pendingOps.isEmpty) return;

    dev.log('📦 Found ${pendingOps.length} pending operations', name: 'RestSync');

    // Batch in groups of 50
    for (var i = 0; i < pendingOps.length; i += 50) {
      final batch = pendingOps.sublist(i, min(i + 50, pendingOps.length));
      await _pushBatch(batch);
    }
  }

  Future<void> _pushBatch(List<SyncQueueData> ops) async {
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

    try {
      final response = await _dio.post('/api/v1/sync/push', data: payload);
      final results = (response.data['data']['results'] as List)
          .cast<Map<String, dynamic>>();

      for (final result in results) {
        final opId = result['operationId'] as String;
        final status = result['status'] as String;

        if (status == 'APPLIED' || status == 'DUPLICATE') {
          await (_database.delete(_database.syncQueue)
                ..where((t) => t.id.equals(opId)))
              .go();
        } else if (status == 'REJECTED') {
          final currentOp = ops.firstWhere((o) => o.id == opId);
          await (_database.update(_database.syncQueue)
                ..where((t) => t.id.equals(opId)))
              .write(SyncQueueCompanion(
            retryCount: Value(currentOp.retryCount + 1),
            lastAttemptAt: Value(DateTime.now()),
          ));
        } else if (status == 'CONFLICT') {
          // Server processed it — remove from queue (conflict resolution in Story 5.3)
          await (_database.delete(_database.syncQueue)
                ..where((t) => t.id.equals(opId)))
              .go();
        }
      }
    } on DioException {
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
  }

  @override
  Future<bool> hasPendingOperations() async {
    final count = await (_database.selectOnly(_database.syncQueue)
          ..addColumns([_database.syncQueue.id.count()])
          ..where(_database.syncQueue.synced.equals(false)))
        .map((row) => row.read(_database.syncQueue.id.count()))
        .getSingle();
    return (count ?? 0) > 0;
  }

  Future<String> _getDeviceId() async {
    const key = 'keevo_device_id';
    var deviceId = await _secureStorage.read(key: key);
    if (deviceId == null) {
      deviceId = const Uuid().v4();
      await _secureStorage.write(key: key, value: deviceId);
    }
    return deviceId;
  }

  @override
  Future<void> pull() async {
    try {
      // 1. Read last pull timestamp
      final lastSyncMs = await _secureStorage.read(key: kLastSyncTimestampKey);
      String? since;
      if (lastSyncMs != null) {
        final lastSync =
            DateTime.fromMillisecondsSinceEpoch(int.parse(lastSyncMs));
        since = lastSync.toUtc().toIso8601String();
      }

      // 2. Call pull endpoint
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/sync/pull',
        queryParameters: since != null ? {'since': since} : null,
      );

      final data = response.data!['data'] as Map<String, dynamic>;
      final serverTimestamp = data['serverTimestamp'] as String;
      final entities = data['entities'] as Map<String, dynamic>;

      // 3. Load pending entity IDs once for AC7 protection
      final pendingIds = await _getPendingEntityIds();

      // 4. Upsert all entities in a single transaction
      await _database.transaction(() async {
        await _upsertProducts(
            entities['products'] as List<dynamic>? ?? [], pendingIds);
        await _upsertStockLevels(
            entities['stockLevels'] as List<dynamic>? ?? []);
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
        await _upsertSales(entities['sales'] as List<dynamic>? ?? []);
        await _upsertDayClosures(
            entities['dayClosures'] as List<dynamic>? ?? []);
        await _upsertStockMovements(
            entities['stockMovements'] as List<dynamic>? ?? []);
        await _upsertStockTransfers(
            entities['stockTransfers'] as List<dynamic>? ?? []);
        await _upsertAuditEntries(
            entities['auditEntries'] as List<dynamic>? ?? []);
      });

      // 4. Store serverTimestamp as new lastPullTimestamp
      final serverInstant = DateTime.parse(serverTimestamp);
      await _secureStorage.write(
        key: kLastSyncTimestampKey,
        value: serverInstant.millisecondsSinceEpoch.toString(),
      );
    } catch (e) {
      dev.log('Pull sync failed: $e', name: 'RestSync');
      rethrow;
    }
  }

  // ── Upsert helpers ────────────────────────────────────────────────────────

  /// AC7 — Get entity IDs that have pending (unsynced) operations in sync_queue.
  /// These records must NOT be overwritten by pull data.
  Future<Set<String>> _getPendingEntityIds() async {
    final pendingOps = await (_database.select(_database.syncQueue)
          ..where((t) => t.synced.equals(false)))
        .get();
    return pendingOps
        .map((o) => o.entityId)
        .whereType<String>()
        .toSet();
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
          map['createdAt'],
          map['updatedAt'],
        ],
      );
    }
  }

  Future<void> _upsertStockLevels(List<dynamic> levels) async {
    if (levels.isEmpty) return;
    for (final l in levels) {
      final map = l as Map<String, dynamic>;
      await _database.customStatement(
        'INSERT INTO stock_levels (id, product_id, variant_id, store_id, '
        'quantity, minimum_threshold, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'product_id = excluded.product_id, store_id = excluded.store_id, '
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
          map['updatedAt'] ?? DateTime.now().toIso8601String(),
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
          map['createdAt'],
          map['updatedAt'],
        ],
      );
    }
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
          map['createdAt'],
          map['updatedAt'],
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
          map['createdAt'],
          map['updatedAt'],
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
          map['createdAt'],
          map['updatedAt'],
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
          map['createdAt'],
        ],
      );
    }
  }

  Future<void> _upsertSales(List<dynamic> sales) async {
    if (sales.isEmpty) return;
    for (final s in sales) {
      final map = s as Map<String, dynamic>;
      await _database.customStatement(
        'INSERT INTO sales (id, store_id, employee_id, total_amount, '
        'discount_amount, payment_mode, client_id, status, occurred_at, '
        'synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?) '
        'ON CONFLICT(id) DO UPDATE SET '
        'status = excluded.status, synced = 1',
        [
          map['id'],
          map['storeId'],
          map['employeeId'],
          map['totalAmount'],
          map['discountAmount'] ?? 0,
          map['paymentMode'],
          map['clientId'],
          map['status'] ?? 'COMPLETED',
          map['occurredAt'],
          map['createdAt'],
        ],
      );
      // Upsert nested sale items
      final items = map['items'] as List<dynamic>? ?? [];
      for (final item in items) {
        final iMap = item as Map<String, dynamic>;
        await _database.customStatement(
          'INSERT INTO sale_items (id, sale_id, product_id, variant_id, '
          'product_name, unit_price, catalogue_unit_price, quantity, subtotal, '
          'created_at) '
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) '
          'ON CONFLICT(id) DO NOTHING',
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
      await _database.customStatement(
        'INSERT INTO day_closures (id, store_id, actor_id, closed_at, '
        'total_sales, total_revenue, cash_amount, momo_amount, '
        'pending_sales_count, pending_sales_total, is_automatic, '
        'synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?) '
        'ON CONFLICT(id) DO NOTHING',
        [
          map['id'],
          map['storeId'],
          map['employeeId'],
          map['closedAt'],
          map['totalSales'],
          map['totalTransactions'],
          map['cashTotal'],
          map['mobileMoneyTotal'],
          map['closedAt'],
        ],
      );
    }
  }

  Future<void> _upsertStockMovements(List<dynamic> movements) async {
    if (movements.isEmpty) return;
    for (final m in movements) {
      final map = m as Map<String, dynamic>;
      // INSERT OR IGNORE — movements are immutable
      await _database.customStatement(
        'INSERT OR IGNORE INTO stock_movements (id, product_id, variant_id, '
        'store_id, type, quantity_before, quantity_delta, quantity_after, '
        'actor_id, reason, synced, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)',
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
          map['occurredAt'],
        ],
      );
    }
  }

  Future<void> _upsertStockTransfers(List<dynamic> transfers) async {
    if (transfers.isEmpty) return;
    for (final t in transfers) {
      final map = t as Map<String, dynamic>;
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
          map['occurredAt'],
          map['status'],
          map['notes'],
        ],
      );
    }
  }

  Future<void> _upsertAuditEntries(List<dynamic> entries) async {
    if (entries.isEmpty) return;
    for (final e in entries) {
      final map = e as Map<String, dynamic>;
      // INSERT OR IGNORE — audit entries are immutable
      await _database.customStatement(
        'INSERT OR IGNORE INTO audit_entries (id, user_id, entity_type, '
        'entity_id, action, value_before, value_after, occurred_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        [
          map['id'],
          map['userId'],
          map['entityType'],
          map['entityId'],
          map['action'],
          map['valueBefore'],
          map['valueAfter'],
          map['occurredAt'],
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