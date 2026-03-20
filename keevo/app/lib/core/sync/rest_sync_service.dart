import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../features/catalog/data/datasource/remote_product_datasource.dart';
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
      final products = await _remoteProducts.getAll();

      // Preserve local-only photoUrl values (backend doesn't store images)
      final localProducts = await _database.select(_database.products).get();
      final photoUrlMap = <String, String>{};
      for (final p in localProducts) {
        if (p.photoUrl != null && p.photoUrl!.isNotEmpty) {
          photoUrlMap[p.id] = p.photoUrl!;
        }
      }

      await _database.transaction(() async {
        await _database.delete(_database.products).go();

        for (final productDto in products) {
          await _database.into(_database.products).insert(
            ProductsCompanion.insert(
              id: productDto.id,
              name: productDto.name,
              description: Value(productDto.description),
              sku: Value(productDto.sku),
              categoryId: Value(productDto.categoryId),
              price: Value(productDto.price),
              buyPrice: Value(productDto.buyPrice),
              stockQuantity: Value(productDto.stockQuantity),
              photoUrl: Value(productDto.photoUrl ?? photoUrlMap[productDto.id]),
              archived: Value(productDto.archived),
              status: Value(productDto.status),
              createdAt: DateTime.parse(productDto.createdAt),
              updatedAt: DateTime.parse(productDto.updatedAt),
            ),
          );
        }
      });

      // Sync stock_levels for each product so POS has fresh data
      for (final productDto in products) {
        try {
          final resp = await _dio.get<Map<String, dynamic>>(
            '/api/v1/products/${productDto.id}/stock',
          );
          final levels = (resp.data!['data'] as List<dynamic>);
          for (final l in levels) {
            final map = l as Map<String, dynamic>;
            await _database.customStatement(
              'INSERT INTO stock_levels (id, product_id, variant_id, store_id, quantity, minimum_threshold, updated_at) '
              'VALUES (?, ?, ?, ?, ?, ?, ?) '
              'ON CONFLICT(product_id, store_id) DO UPDATE SET '
              'id = excluded.id, variant_id = excluded.variant_id, '
              'quantity = excluded.quantity, minimum_threshold = excluded.minimum_threshold, '
              'updated_at = excluded.updated_at',
              [
                map['id'] as String,
                map['productId'] as String,
                map['variantId'],
                map['storeId'] as String,
                map['quantity'] as int,
                (map['minimumThreshold'] as int?) ?? 0,
                (map['updatedAt'] as String?) ?? DateTime.now().toIso8601String(),
              ],
            );
          }
        } catch (e) {
          dev.log('⚠️ Stock sync failed for ${productDto.id}: $e', name: 'RestSync');
        }
      }
    } catch (e) {
      print('Pull sync failed: $e');
      rethrow;
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