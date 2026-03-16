import 'dart:convert';
import 'dart:developer' as dev;

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../features/catalog/data/datasource/remote_product_datasource.dart';
import '../storage/app_database.dart';
import 'sync_service.dart';

/// RestSyncService — Implémentation REST basique pour synchroniser les produits.
/// 
/// Cette implémentation synchronise immédiatement les opérations vers le backend
/// au lieu d'utiliser une queue complexe (Epic 5 feature).
class RestSyncService implements SyncService {
  final AppDatabase _database;
  final RemoteProductDataSource _remoteProducts;
  final Dio _dio;

  RestSyncService({
    required AppDatabase database, 
    required RemoteProductDataSource remoteProducts,
    required Dio dio,
  }) : _database = database, _remoteProducts = remoteProducts, _dio = dio;

  @override
  Future<void> push() async {
    dev.log('🔄 RestSyncService.push() - Starting sync check', name: 'RestSync');
    
    // Récupérer toutes les opérations en attente
    final pendingOps = await _database.select(_database.syncQueue).get();
    
    dev.log('📦 Found ${pendingOps.length} pending operations', name: 'RestSync');
    
    for (final op in pendingOps) {
      try {
        dev.log('⚡ Executing sync operation: ${op.operation}', name: 'RestSync');
        await _executeSyncOperation(op);
        
        // Supprimer l'opération réussie de la queue
        await (_database.delete(_database.syncQueue)
              ..where((t) => t.id.equals(op.id))).go();
              
        dev.log('✅ Sync operation completed: ${op.operation}', name: 'RestSync');
      } catch (e) {
        // En cas d'erreur, on laisse l'opération dans la queue pour retry
        dev.log('❌ Sync failed for operation ${op.id}: $e', name: 'RestSync');
      }
    }
  }

  Future<void> _executeSyncOperation(SyncQueueData op) async {
    final payload = jsonDecode(op.payload) as Map<String, dynamic>;
    
    dev.log('📋 Executing ${op.operation} with payload: ${payload.length} fields', name: 'RestSync');
    
    switch (op.operation) {
      case 'CREATE_PRODUCT':
        dev.log('🆕 Creating product via API', name: 'RestSync');
        await _remoteProducts.create(payload);
        dev.log('✅ Product created successfully', name: 'RestSync');
        break;
        
      case 'UPDATE_PRODUCT':
        final productId = payload['productId'] as String;
        payload.remove('productId'); // RemoteAPI n'attend pas l'ID dans le payload
        dev.log('📝 Updating product $productId via API', name: 'RestSync');
        await _remoteProducts.update(productId, payload);
        dev.log('✅ Product updated successfully', name: 'RestSync');
        break;
        
      case 'ARCHIVE_PRODUCT':
        final productId = payload['productId'] as String;
        dev.log('🗄️ Archiving product $productId via API', name: 'RestSync');
        await _remoteProducts.archive(productId);
        dev.log('✅ Product archived successfully', name: 'RestSync');
        break;
        
      case 'UNARCHIVE_PRODUCT':
        final productId = payload['productId'] as String;
        dev.log('📦 Unarchiving product $productId via API', name: 'RestSync');
        await _remoteProducts.unarchive(productId);
        dev.log('✅ Product unarchived successfully', name: 'RestSync');
        break;

      case 'CREATE_SALE':
        dev.log('💰 Pushing sale via API', name: 'RestSync');
        await _dio.post('/api/v1/sales', data: payload);
        // Mark sale as synced locally
        final saleId = payload['saleId'] as String?;
        if (saleId != null) {
          await (_database.update(_database.sales)
                ..where((s) => s.id.equals(saleId)))
              .write(const SalesCompanion(
            synced: Value(true),
          ));
        }
        dev.log('✅ Sale pushed successfully', name: 'RestSync');
        break;
        
      default:
        dev.log('❓ Unknown sync operation: ${op.operation}', name: 'RestSync');
    }
  }

  @override
  Future<void> pull() async {
    // Cette méthode est appelée par la synchronisation automatique
    // Elle récupère les données du backend et les met à jour en local
    try {
      final products = await _remoteProducts.getAll();
      
      // Vider la table locale et la repeupler (stratégie simple)
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
              photoUrl: Value(productDto.photoUrl),
              archived: Value(productDto.archived),
              status: Value(productDto.status),
              createdAt: DateTime.parse(productDto.createdAt),
              updatedAt: DateTime.parse(productDto.updatedAt),
            ),
          );
        }
      });
    } catch (e) {
      print('Pull sync failed: $e');
      rethrow;
    }
  }

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
  }) async {
    const uuid = Uuid();
    
    dev.log('📥 Queueing operation: $operation', name: 'RestSync');
    
    // Ajouter l'opération à la queue pour sync plus tard
    await _database.into(_database.syncQueue).insert(
      SyncQueueCompanion.insert(
        id: uuid.v4(),
        operation: operation,
        payload: jsonEncode(payload),
        createdAt: DateTime.now(),
      ),
    );
    
    dev.log('✅ Operation queued, attempting immediate sync', name: 'RestSync');
    
    // Tenter de synchroniser immédiatement (best effort)
    try {
      await push();
    } catch (e) {
      // Si ça échoue, l'opération reste dans la queue pour retry plus tard
      dev.log('⚠️ Immediate sync failed, will retry later: $e', name: 'RestSync');
    }
  }
}