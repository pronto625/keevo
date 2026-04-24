import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart';
import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/model/category_model.dart';
import '../../domain/repository/category_repository.dart';

/// CategoryRepositoryImpl — Offline-first implementation (Story 5.6).
///
/// All writes hit local Drift first, then queue a sync operation for background push.
/// Remote sync (syncFromApi) is unchanged — still pulls from backend.
class CategoryRepositoryImpl implements CategoryRepository {
  final AppDatabase _database;
  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const CategoryRepositoryImpl(
    this._database,
    this._syncService,
    this._syncTriggerDispatcher,
  );

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
    // syncFromApi requires an authenticated ApiService — callers must obtain
    // the right datasource directly when online sync is needed.
    // Offline-first writes (create/toggle/rename) are handled via SyncService.
    return getLocalCategories();
  }

  @override
  Future<CategoryModel> createCustomCategory(String name, {String? parentId}) async {
    // Offline-first (Story 5.6): generate UUID locally, write to Drift, queue sync.
    final id = const Uuid().v4();
    final now = DateTime.now();
    await _database.into(_database.categories).insertOnConflictUpdate(
      CategoriesCompanion.insert(
        id: id,
        name: name,
        parentId: Value(parentId),
        isActive: const Value(true),
        isCustom: const Value(true),
        createdAt: now,
        updatedAt: now,
      ),
    );
    await _syncService.queueOperation(
      operation: 'CREATE_CATEGORY',
      payload: {
        'id': id,
        'name': name,
        if (parentId != null) 'parentId': parentId,
      },
      entityId: id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    final row = await (_database.select(_database.categories)
          ..where((c) => c.id.equals(id)))
        .getSingle();
    return _mapToModel(row);
  }

  @override
  Future<CategoryModel> toggleCategoryStatus(String categoryId) async {
    // Offline-first (Story 5.6): toggle locally, queue TOGGLE_CATEGORY.
    final category = await (_database.select(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .getSingle();
    final now = DateTime.now();
    final newActive = !category.isActive;
    await (_database.update(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .write(CategoriesCompanion(
      isActive: Value(newActive),
      updatedAt: Value(now),
    ));
    await _syncService.queueOperation(
      operation: 'TOGGLE_CATEGORY',
      payload: {'categoryId': categoryId, 'isActive': newActive},
      entityId: categoryId,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    final updated = await (_database.select(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .getSingle();
    return _mapToModel(updated);
  }

  @override
  Future<CategoryModel> renameCategory(String categoryId, String newName) async {
    // Offline-first (Story 5.6): rename locally, queue RENAME_CATEGORY.
    final now = DateTime.now();
    await (_database.update(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .write(CategoriesCompanion(name: Value(newName), updatedAt: Value(now)));
    await _syncService.queueOperation(
      operation: 'RENAME_CATEGORY',
      payload: {'categoryId': categoryId, 'name': newName},
      entityId: categoryId,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    final row = await (_database.select(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .getSingle();
    return _mapToModel(row);
  }

  @override
  Future<void> deleteCategory(String categoryId) async {
    // Local delete only — categories are managed by admin, no sync queue.
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