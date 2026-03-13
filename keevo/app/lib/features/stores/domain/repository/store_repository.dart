import '../model/store_model.dart';
import '../model/store_type.dart';

/// StoreRepository — abstract domain port for store persistence.
///
/// Implementations must be offline-first: reads from local Drift DB,
/// writes go remote-first (optimistic local fallback when offline).
///
/// Story 3.1 — Tasks 17 & 18.
abstract class StoreRepository {
  /// Returns all stores, optionally including inactive ones.
  Future<List<StoreModel>> getStores({bool includeInactive = false});

  /// Creates a new store or warehouse.
  ///
  /// Throws [StoreException.planLimitExceeded] if the tenant's plan limit is reached.
  /// Throws [StoreException.warehouseAlreadyExists] if [type] is WAREHOUSE and one already exists.
  Future<StoreModel> createStore({
    required String name,
    required StoreType type,
    String? address,
    String? phone,
  });

  /// Updates a store's name, address, or phone. Type is immutable after creation.
  ///
  /// Throws [StoreException.notFound] if no store with [storeId] exists.
  Future<StoreModel> updateStore({
    required String storeId,
    required String name,
    String? address,
    String? phone,
  });

  /// Soft-deletes a store (sets isActive = false).
  ///
  /// Throws [StoreException.notFound] if no store with [storeId] exists.
  Future<StoreModel> deactivateStore(String storeId);

  /// Background sync: fetches all stores from the backend and writes them to local Drift DB.
  Future<void> syncFromRemote();
}
