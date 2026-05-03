import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/repository/category_repository_impl.dart';
import '../../domain/model/category_model.dart';
import '../../domain/repository/category_repository.dart';

/// Provider for CategoryRepository using offline-first strategy (Story 5.6)
final categoryRepositoryProvider = Provider<CategoryRepository>((ref) {
  return CategoryRepositoryImpl(
    ref.watch(appDatabaseProvider),
    ref.watch(syncServiceProvider),
    ref.watch(syncTriggerDispatcherProvider),
  );
});

/// Provides all active categories for the current tenant sector.
/// 
/// Auto-syncs from API based on tenant's sector type. Returns categories
/// appropriate for the tenant's business sector (e.g., CLOTHING, ELECTRONICS).
///
/// Usage:
/// ```dart
/// final asyncCats = ref.watch(categoriesProvider);
/// asyncCats.when(data: (cats) => ..., loading: ..., error: ...);
/// ```
final categoriesProvider = FutureProvider.autoDispose<List<CategoryModel>>((ref) async {
  // Re-run when category list is mutated.
  ref.watch(categoryRefreshProvider);

  final repository = ref.watch(categoryRepositoryProvider);

  // Try to get local categories first (persisted in Drift DB)
  final localCategories = await repository.getLocalCategories();

  // If no local categories (fresh install / first login), trigger a pull sync.
  if (localCategories.isEmpty) {
    try {
      await ref.read(syncServiceProvider).pull();
      final afterSync = await repository.getLocalCategories();
      if (afterSync.isNotEmpty) return afterSync;
    } catch (_) {}

    // Fallback: direct GET /api/v1/categories for resilience against empty delta.
    try {
      final dio = ref.read(dioProvider);
      final db = ref.read(appDatabaseProvider);
      final response = await dio.get<Map<String, dynamic>>('/api/v1/categories');
      final rawCats = response.data!['data'] as List<dynamic>;
      String? _toLocal(dynamic utcIso) {
        if (utcIso == null) return null;
        return DateTime.parse(utcIso as String).toLocal().toIso8601String();
      }
      for (final c in rawCats) {
        final map = c as Map<String, dynamic>;
        await db.customStatement(
          'INSERT INTO categories (id, name, parent_id, is_active, is_custom, '
          'created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) '
          'ON CONFLICT(id) DO UPDATE SET name = excluded.name, '
          'parent_id = excluded.parent_id, is_active = excluded.is_active, '
          'is_custom = excluded.is_custom, updated_at = excluded.updated_at',
          [
            map['id'],
            map['name'],
            map['parentId'],
            (map['isActive'] == true) ? 1 : 0,
            (map['isCustom'] == true) ? 1 : 0,
            _toLocal(map['createdAt']),
            _toLocal(map['updatedAt']),
          ],
        );
      }
      return await repository.getLocalCategories();
    } catch (_) {
      return <CategoryModel>[];
    }
  }

  return localCategories;
});

/// Provides root categories only (parentId = null)
final rootCategoriesProvider = FutureProvider.autoDispose<List<CategoryModel>>((ref) async {
  final repository = ref.watch(categoryRepositoryProvider);
  return await repository.getRootCategories();
});

/// Provides subcategories for a specific parent
final subcategoriesProvider = FutureProvider.autoDispose
    .family<List<CategoryModel>, String>((ref, parentId) async {
  final repository = ref.watch(categoryRepositoryProvider);
  return await repository.getSubcategories(parentId);
});

/// Trigger for manual category refresh (increment to force providers to re-run).
final categoryRefreshProvider = StateProvider<int>((ref) => 0);

/// Category actions — create, rename, delete.
class CategoryActions {
  final Ref _ref;
  const CategoryActions(this._ref);

  CategoryRepository get _repo => _ref.read(categoryRepositoryProvider);

  Future<CategoryModel> create(String name, {String? parentId}) async {
    final result = await _repo.createCustomCategory(name, parentId: parentId);
    _ref.read(categoryRefreshProvider.notifier).state++;
    return result;
  }

  Future<CategoryModel> rename(String categoryId, String newName) async {
    final result = await _repo.renameCategory(categoryId, newName);
    _ref.read(categoryRefreshProvider.notifier).state++;
    return result;
  }

  Future<void> delete(String categoryId) async {
    await _repo.deleteCategory(categoryId);
    _ref.read(categoryRefreshProvider.notifier).state++;
  }
}

final categoryActionsProvider = Provider<CategoryActions>((ref) {
  return CategoryActions(ref);
});

/// Trigger a full pull sync then invalidate the category provider.
Future<void> refreshCategories(WidgetRef ref) async {
  await ref.read(syncServiceProvider).pull();
  ref.read(categoryRefreshProvider.notifier).state++;
}

