import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart';
import '../../../../core/storage/search_fold.dart';
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
    // v1s-14-15: the Drift write and its sync_queue entry commit in ONE transaction.
    final id = const Uuid().v4();
    final now = DateTime.now();
    final model = await _database.transaction(() async {
      await _requireNameFree(name, parentId: parentId);
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
      return _mapToModel(await _requireRow(id));
    });
    _syncTriggerDispatcher.triggerPushIfIdle();
    return model;
  }

  /// Refuses a name already used by another active category under the same
  /// parent, case- and accent-insensitively (the server dedups the same way).
  Future<void> _requireNameFree(String name,
      {String? parentId, String? exceptId}) async {
    final wanted = foldForSql(name.trim());
    final rows = await (_database.select(_database.categories)
          ..where((c) =>
              c.isActive.equals(true) &
              (parentId == null
                  ? c.parentId.isNull()
                  : c.parentId.equals(parentId))))
        .get();
    final taken = rows.any(
        (c) => c.id != exceptId && foldForSql(c.name.trim()) == wanted);
    if (taken) throw CategoryNameTakenException(name.trim());
  }

  @override
  Future<CategoryModel> toggleCategoryStatus(String categoryId) async {
    // Offline-first (Story 5.6): toggle locally, queue TOGGLE_CATEGORY.
    // v1s-14-15: read + check + write + queue are atomic (no lost op, no double toggle).
    final model = await _database.transaction(() async {
      final category = await _requireRow(categoryId);
      final newActive = !category.isActive;
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
        isActive: Value(newActive),
        updatedAt: Value(DateTime.now()),
      ));
      await _syncService.queueOperation(
        operation: 'TOGGLE_CATEGORY',
        payload: {'categoryId': categoryId, 'isActive': newActive},
        entityId: categoryId,
      );
      return _mapToModel(await _requireRow(categoryId));
    });
    _syncTriggerDispatcher.triggerPushIfIdle();
    return model;
  }

  @override
  Future<CategoryModel> renameCategory(String categoryId, String newName) async {
    // Offline-first (Story 5.6): rename locally, queue RENAME_CATEGORY.
    // v1s-14-15: atomic; a missing row raises CategoryNotFoundException.
    final model = await _database.transaction(() async {
      final current = await _requireRow(categoryId);
      await _requireNameFree(newName,
          parentId: current.parentId, exceptId: categoryId);
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
              name: Value(newName), updatedAt: Value(DateTime.now())));
      await _syncService.queueOperation(
        operation: 'RENAME_CATEGORY',
        payload: {'categoryId': categoryId, 'name': newName},
        entityId: categoryId,
      );
      return _mapToModel(await _requireRow(categoryId));
    });
    _syncTriggerDispatcher.triggerPushIfIdle();
    return model;
  }

  @override
  Future<void> deleteCategory(String categoryId) async {
    // Offline-first: soft-deactivate via TOGGLE_CATEGORY (set-based, idempotent).
    // Mirrors toggleCategoryStatus() but forces isActive=false.
    // v1s-14-15: atomic; no-op when the row is missing or already inactive (D4).
    final queued = await _database.transaction(() async {
      final category = await (_database.select(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .getSingleOrNull();
      if (category == null || !category.isActive) return false;
      await (_database.update(_database.categories)
            ..where((c) => c.id.equals(categoryId)))
          .write(CategoriesCompanion(
        isActive: const Value(false),
        updatedAt: Value(DateTime.now()),
      ));
      await _syncService.queueOperation(
        operation: 'TOGGLE_CATEGORY',
        payload: {'categoryId': categoryId, 'isActive': false},
        entityId: categoryId,
      );
      return true;
    });
    if (queued) _syncTriggerDispatcher.triggerPushIfIdle();
  }

  /// Reads a category row or throws [CategoryNotFoundException].
  Future<Category> _requireRow(String categoryId) async {
    final row = await (_database.select(_database.categories)
          ..where((c) => c.id.equals(categoryId)))
        .getSingleOrNull();
    if (row == null) throw CategoryNotFoundException(categoryId);
    return row;
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