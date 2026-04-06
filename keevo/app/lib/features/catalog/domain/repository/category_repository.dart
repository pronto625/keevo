import '../model/category_model.dart';

/// CategoryRepository — Port for category data access.
///
/// Handles sync between remote API and local Drift database.
abstract class CategoryRepository {
  /// Get all active categories (root + subcategories) from local database.
  Future<List<CategoryModel>> getLocalCategories();

  /// Get root categories only (parentId = null) from local database.
  Future<List<CategoryModel>> getRootCategories();

  /// Get subcategories of a specific parent from local database.
  Future<List<CategoryModel>> getSubcategories(String parentId);

  /// Sync categories from API to local database.
  /// Returns synced categories.
  Future<List<CategoryModel>> syncFromApi();

  /// Create a custom category via API and sync to local.
  Future<CategoryModel> createCustomCategory(String name, {String? parentId});

  /// Toggle category active status via API and sync to local.
  Future<CategoryModel> toggleCategoryStatus(String categoryId);

  /// Rename a category via API and update local cache.
  Future<CategoryModel> renameCategory(String categoryId, String newName);

  /// Soft-delete a category (sets isActive = false) via API and purge from local.
  Future<void> deleteCategory(String categoryId);
}
