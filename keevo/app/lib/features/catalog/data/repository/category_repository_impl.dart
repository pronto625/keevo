import 'package:drift/drift.dart';

import '../../../../core/services/api_service.dart';
import '../../../../core/storage/app_database.dart';
import '../../domain/model/category_model.dart';
import '../../domain/repository/category_repository.dart';

/// CategoryRepositoryImpl — Concrete implementation with API sync.
///
/// Handles sync between backend /api/v1/categories endpoints and local Drift database.
class CategoryRepositoryImpl implements CategoryRepository {
  final AppDatabase _database;
  final ApiService _apiService;

  const CategoryRepositoryImpl(this._database, this._apiService);

  @override
  Future<List<CategoryModel>> getLocalCategories() async {
    final rows = await (_database.select(_database.categories)
          ..where((c) => c.isActive.equals(true))
          ..orderBy([(c) => OrderingTerm.asc(c.name)]))
        .get();

    return rows.map(_mapToModel).toList();
  }

  @override
  Future<List<CategoryModel>> getRootCategories() async {
    final rows = await (_database.select(_database.categories)
          ..where((c) => c.isActive.equals(true) & c.parentId.isNull())
          ..orderBy([(c) => OrderingTerm.asc(c.name)]))
        .get();

    return rows.map(_mapToModel).toList();
  }

  @override
  Future<List<CategoryModel>> getSubcategories(String parentId) async {
    final rows = await (_database.select(_database.categories)
          ..where((c) => c.isActive.equals(true) & c.parentId.equals(parentId))
          ..orderBy([(c) => OrderingTerm.asc(c.name)]))
        .get();

    return rows.map(_mapToModel).toList();
  }

  @override
  Future<List<CategoryModel>> syncFromApi() async {
    try {
      // GET /categories
      final response = await _apiService.get('/api/v1/categories');
      
      // Extract data from ApiResponseWrapper
      final dataList = response['data'] as List<dynamic>? ?? [];
      
      final categories = dataList
          .map((json) => CategoryModel.fromJson(json as Map<String, dynamic>))
          .toList();

      // Clear and repopulate local categories
      await _database.transaction(() async {
        await _database.delete(_database.categories).go();
        
        for (final category in categories) {
          await _database.into(_database.categories).insert(
            CategoriesCompanion.insert(
              id: category.id,
              name: category.name,
              parentId: Value(category.parentId),
              isActive: Value(category.isActive),
              isCustom: Value(category.isCustom),
              createdAt: category.createdAt,
              updatedAt: category.updatedAt,
            ),
          );
        }
      });

      return categories;
    } catch (e) {
      // If API fails, return local categories (offline fallback)
      return await getLocalCategories();
    }
  }

  @override
  Future<CategoryModel> createCustomCategory(String name, {String? parentId}) async {
    try {
      final response = await _apiService.post('/api/v1/categories', data: {
        'name': name,
        if (parentId != null) 'parentId': parentId,
      });

      final categoryData = response['data'] as Map<String, dynamic>;
      final category = CategoryModel.fromJson(categoryData);

      // Insert to local database
      await _database.into(_database.categories).insert(
        CategoriesCompanion.insert(
          id: category.id,
          name: category.name,
          parentId: Value(category.parentId),
          isActive: Value(category.isActive),
          isCustom: Value(category.isCustom),
          createdAt: category.createdAt,
          updatedAt: category.updatedAt,
        ),
      );

      return category;
    } catch (e) {
      // Fallback: create locally only (will sync later)
      final now = DateTime.now();
      final id = 'local-${now.millisecondsSinceEpoch}';
      final companion = CategoriesCompanion.insert(
        id: id,
        name: name,
        parentId: Value(parentId),
        isActive: const Value(true),
        isCustom: const Value(true),
        createdAt: now,
        updatedAt: now,
      );

      await _database.into(_database.categories).insert(companion);
      
      final category = await (_database.select(_database.categories)
            ..where((c) => c.id.equals(id)))
          .getSingle();

      return _mapToModel(category);
    }
  }

  @override
  Future<CategoryModel> toggleCategoryStatus(String categoryId) async {
    try {
      final response = await _apiService.patch('/api/v1/categories/$categoryId/toggle');
      final categoryData = response['data'] as Map<String, dynamic>;
      final category = CategoryModel.fromJson(categoryData);

      // Update locally
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
        isActive: Value(category.isActive),
        updatedAt: Value(category.updatedAt),
      ));

      return category;
    } catch (e) {
      // Fallback: toggle locally only (will sync later)
      final category = await (_database.select(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .getSingle();

      final now = DateTime.now();
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
        isActive: Value(!category.isActive),
        updatedAt: Value(now),
      ));

      final updated = await (_database.select(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .getSingle();

      return _mapToModel(updated);
    }
  }

  @override
  Future<CategoryModel> renameCategory(String categoryId, String newName) async {
    try {
      final response = await _apiService.patch(
        '/api/v1/categories/$categoryId',
        data: {'name': newName},
      );
      final categoryData = response['data'] as Map<String, dynamic>;
      final category = CategoryModel.fromJson(categoryData);
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
        name: Value(category.name),
        updatedAt: Value(category.updatedAt),
      ));
      return category;
    } catch (e) {
      // Offline fallback: rename locally
      final now = DateTime.now();
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(name: Value(newName), updatedAt: Value(now)));
      final row = await (_database.select(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .getSingle();
      return _mapToModel(row);
    }
  }

  @override
  Future<void> deleteCategory(String categoryId) async {
    try {
      await _apiService.delete('/api/v1/categories/$categoryId');
    } catch (_) {
      // Proceed with local removal even if network fails.
    }
    await (_database.delete(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .go();
  }

  /// Map Drift Category to CategoryModel
  CategoryModel _mapToModel(Category row) {
    return CategoryModel(
      id: row.id,
      name: row.name,
      parentId: row.parentId,
      isActive: row.isActive,
      isCustom: row.isCustom,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    );
  }
}