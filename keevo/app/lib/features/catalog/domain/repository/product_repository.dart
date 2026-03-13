import 'dart:typed_data';

import '../model/csv_import_result.dart';
import '../model/product_model.dart';

/// ProductRepository — port (abstract interface) for product CRUD.
///
/// Offline-first: all reads come from local Drift DB.
/// Writes go to local DB + sync queue, then sync on next push.
abstract interface class ProductRepository {
  /// Get all non-archived products for the current store.
  Future<List<ProductModel>> getAll();

  /// Get all archived products.
  Future<List<ProductModel>> getArchived();

  /// Get a product by ID. Returns null if not found.
  Future<ProductModel?> getById(String id);

  /// Search products by name, SKU, or category (offline — against local DB).
  Future<List<ProductModel>> search(String query);

  /// Create a product locally and queue for backend sync.
  ///
  /// Returns the created [ProductModel] with its generated ID.
  Future<ProductModel> create({
    required String name,
    String? description,
    String? sku,
    String? categoryId,
    int price,
    int buyPrice,
    int transportCost,
    String? photoUrl,
  });

  /// Update a product locally and queue for backend sync.
  Future<ProductModel> update({
    required String id,
    String? name,
    String? description,
    String? sku,
    String? categoryId,
    int? price,
    int? buyPrice,
    int? transportCost,
    String? photoUrl,
  });

  /// Archive a product (soft delete — sets archived=true).
  Future<void> archive(String id);

  /// Unarchive a product (restore — sets archived=false).
  Future<void> unarchive(String id);

  /// Pull latest products from backend and merge into local DB.
  Future<void> syncFromRemote();

  /// Upload CSV bytes to backend for bulk import (AC2 — requires connectivity).
  Future<CsvImportResult> importCsv({
    required Uint8List csvBytes,
    required String fileName,
    required Map<String, String> columnMapping,
  });

  /// Download the CSV import template from backend (AC5).
  Future<Uint8List> downloadCsvTemplate();

  /// Create a product with DRAFT status for on-the-fly POS creation (AC6).
  Future<ProductModel> createDraft({
    required String name,
    required int priceVente,
    required String categoryId,
    int stockQuantity,
  });

  /// Count DRAFT products in local DB — drives AC10 nav badge.
  Future<int> countDrafts();
}
