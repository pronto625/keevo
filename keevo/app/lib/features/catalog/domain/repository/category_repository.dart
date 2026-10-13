import '../model/category_model.dart';

/// CategoryNotFoundException — the category row no longer exists locally (v1s-14-15).
///
/// Thrown by [CategoryRepository.toggleCategoryStatus] and
/// [CategoryRepository.renameCategory]; `appErrorMessage(e)` shows its French message.
class CategoryNotFoundException implements Exception {
  final String categoryId;

  const CategoryNotFoundException(this.categoryId);

  @override
  String toString() => 'Catégorie introuvable ou supprimée.';
}

/// Thrown when another active category with the same parent already has this
/// name, compared case- and accent-insensitively ("boissons" = "Boissons").
class CategoryNameTakenException implements Exception {
  final String name;

  const CategoryNameTakenException(this.name);

  String get message => 'Une catégorie « $name » existe déjà.';

  @override
  String toString() => message;
}

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

  /// Toggle category active status locally and queue the sync operation.
  ///
  /// Throws [CategoryNotFoundException] when the category no longer exists locally
  /// (nothing written, nothing queued).
  Future<CategoryModel> toggleCategoryStatus(String categoryId);

  /// Rename a category locally and queue the sync operation.
  ///
  /// Throws [CategoryNotFoundException] when the category no longer exists locally
  /// (nothing written, nothing queued).
  Future<CategoryModel> renameCategory(String categoryId, String newName);

  /// Soft-delete a category (sets isActive = false) locally and queue the sync operation.
  ///
  /// No-op when the category is already inactive or no longer exists locally.
  Future<void> deleteCategory(String categoryId);
}
