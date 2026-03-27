import 'dart:developer' as dev;

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_database.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/remote_quick_add_datasource.dart';

part 'quick_add_product_provider.g.dart';

// ── Infrastructure provider ─────────────────────────────────────────────────

final remoteQuickAddDatasourceProvider =
    Provider<RemoteQuickAddDatasource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteQuickAddDatasource(dio: dio);
});

// ── Notifier ────────────────────────────────────────────────────────────────

@riverpod
class QuickAddProductNotifier extends _$QuickAddProductNotifier {
  @override
  FutureOr<void> build() {}

  /// Quick-add a product during inventory counting.
  /// Returns the result on success; null on dedup error (caller handles dialog).
  /// Throws on other errors.
  Future<QuickAddResult?> quickAdd({
    required String sessionId,
    required String name,
    required String categoryId,
    required int physicalQty,
    int? sellingPrice,
  }) async {
    try {
      // Online: POST to backend
      final result = await ref.read(remoteQuickAddDatasourceProvider).quickAdd(
            sessionId: sessionId,
            name: name,
            categoryId: categoryId,
            physicalQty: physicalQty,
            sellingPrice: sellingPrice,
          );

      // Cache locally in Drift (product, stock_level, inventory_count).
      // Non-fatal: the backend POST already succeeded, so we return the
      // result even if local caching fails.
      try {
        await _cacheLocally(result, sessionId, sellingPrice: sellingPrice);
      } catch (cacheError) {
        dev.log('Local cache failed (non-fatal): $cacheError',
            name: 'QuickAddProduct');
      }

      return result;
    } on DioException catch (e) {
      if (e.response?.statusCode == 409) {
        // Dedup: product name already exists
        return null;
      }
      // Offline fallback for network errors
      if (_isNetworkError(e)) {
        return _createOffline(
          sessionId: sessionId,
          name: name,
          categoryId: categoryId,
          physicalQty: physicalQty,
          sellingPrice: sellingPrice,
        );
      }
      rethrow;
    } catch (e) {
      // Offline fallback
      if (_isNetworkError(e)) {
        return _createOffline(
          sessionId: sessionId,
          name: name,
          categoryId: categoryId,
          physicalQty: physicalQty,
          sellingPrice: sellingPrice,
        );
      }
      rethrow;
    }
  }

  bool _isNetworkError(Object e) {
    return e is DioException &&
        (e.type == DioExceptionType.connectionTimeout ||
            e.type == DioExceptionType.connectionError ||
            e.type == DioExceptionType.receiveTimeout);
  }

  Future<void> _cacheLocally(QuickAddResult result, String sessionId, {int? sellingPrice}) async {
    final db = ref.read(appDatabaseProvider);
    final now = DateTime.now();

    // Resolve storeId from session
    final session = await (db.select(db.inventorySessions)
          ..where((s) => s.id.equals(sessionId)))
        .getSingleOrNull();
    final storeId = session?.storeId ?? '';

    // Cache product
    await db.into(db.products).insertOnConflictUpdate(
          ProductsCompanion.insert(
            id: result.productId,
            name: result.productName,
            sku: Value(result.sku),
            categoryId: Value(result.categoryId),
            price: Value(sellingPrice ?? 0),
            buyPrice: const Value(0),
            transportCost: const Value(0),
            stockQuantity: Value(result.physicalQty),
            archived: const Value(false),
            status: const Value('ACTIVE'),
            createdAt: now,
            updatedAt: now,
          ),
        );

    // Cache stock level
    await db.into(db.stockLevels).insertOnConflictUpdate(
          StockLevelsCompanion.insert(
            id: result.stockLevelId,
            productId: result.productId,
            storeId: storeId,
            quantity: result.physicalQty,
            updatedAt: now,
          ),
        );

    // Cache inventory count
    await db.into(db.inventoryCounts).insertOnConflictUpdate(
          InventoryCountsCompanion.insert(
            id: result.inventoryCountId,
            sessionId: sessionId,
            productId: result.productId,
            productName: result.productName,
            theoretical: 0,
            physical: Value(result.physicalQty),
            countedAt: Value(now),
            updatedAt: now,
          ),
        );
  }

  Future<QuickAddResult?> _createOffline({
    required String sessionId,
    required String name,
    required String categoryId,
    required int physicalQty,
    int? sellingPrice,
  }) async {
    final db = ref.read(appDatabaseProvider);
    final syncService = ref.read(syncServiceProvider);
    final uuid = const Uuid();
    final now = DateTime.now();

    final productId = uuid.v4();
    final stockLevelId = uuid.v4();
    final countId = uuid.v4();
    final sku =
        'KEV-${uuid.v4().replaceAll('-', '').substring(0, 6).toUpperCase()}';

    // Check local dedup
    final existing = await (db.select(db.products)
          ..where(
              (p) => p.name.lower().equals(name.trim().toLowerCase())))
        .getSingleOrNull();
    if (existing != null) {
      return null; // Caller shows dedup dialog
    }

    // Resolve storeId from session
    final session = await (db.select(db.inventorySessions)
          ..where((s) => s.id.equals(sessionId)))
        .getSingleOrNull();
    final storeId = session?.storeId ?? '';

    // Create product locally
    await db.into(db.products).insert(
          ProductsCompanion.insert(
            id: productId,
            name: name.trim(),
            sku: Value(sku),
            categoryId: Value(categoryId),
            price: Value(sellingPrice ?? 0),
            buyPrice: const Value(0),
            transportCost: const Value(0),
            stockQuantity: Value(physicalQty),
            archived: const Value(false),
            status: const Value('ACTIVE'),
            createdAt: now,
            updatedAt: now,
          ),
        );

    // Create stock level locally
    await db.into(db.stockLevels).insert(
          StockLevelsCompanion.insert(
            id: stockLevelId,
            productId: productId,
            storeId: storeId,
            quantity: physicalQty,
            updatedAt: now,
          ),
        );

    // Create inventory count locally
    await db.into(db.inventoryCounts).insert(
          InventoryCountsCompanion.insert(
            id: countId,
            sessionId: sessionId,
            productId: productId,
            productName: name.trim(),
            theoretical: 0,
            physical: Value(physicalQty),
            countedAt: Value(now),
            updatedAt: now,
          ),
        );

    // Queue sync operations in order
    await syncService.queueOperation(
      operation: 'CREATE_PRODUCT',
      payload: {
        'name': name.trim(),
        'sku': sku,
        'categoryId': categoryId,
        'price': sellingPrice ?? 0,
        'buyPrice': 0,
        'transportCost': 0,
        'stockQuantity': physicalQty,
      },
      entityId: productId,
    );

    await syncService.queueOperation(
      operation: 'RECORD_STOCK_ENTRY',
      payload: {
        'productId': productId,
        'storeId': storeId,
        'quantity': physicalQty,
      },
      entityId: stockLevelId,
    );

    await syncService.queueOperation(
      operation: 'SAVE_INVENTORY_COUNT',
      payload: {
        'sessionId': sessionId,
        'productId': productId,
        'productName': name.trim(),
        'theoretical': 0,
        'physical': physicalQty,
      },
      entityId: countId,
    );

    dev.log('Quick-add offline: product=$productId, stock=$stockLevelId, count=$countId',
        name: 'QuickAddProduct');

    return QuickAddResult(
      productId: productId,
      productName: name.trim(),
      sku: sku,
      categoryId: categoryId,
      physicalQty: physicalQty,
      inventoryCountId: countId,
      stockLevelId: stockLevelId,
    );
  }
}
