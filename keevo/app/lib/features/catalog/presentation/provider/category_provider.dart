import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/services/api_service.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../identity/presentation/provider/tenant_preferences_provider.dart';
import '../../data/repository/category_repository_impl.dart';
import '../../domain/model/category_model.dart';
import '../../domain/repository/category_repository.dart';

/// Provider for CategoryRepository with authenticated API service
final categoryRepositoryProvider = Provider<CategoryRepository>((ref) {
  final database = ref.watch(appDatabaseProvider);
  // Use authenticated API service from auth module when available
  ApiService apiService;
  try {
    final dio = ref.read(dioProvider);
    apiService = DioApiService(dio: dio);
  } catch (e) {
    // Fallback to default if not authenticated  
    apiService = StubApiService();
  }
  return CategoryRepositoryImpl(database, apiService);
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
  
  // Get tenant preferences to access sector type
  final tenantPrefs = await ref.watch(tenantPreferencesProvider.future);
  
  // If no tenant preferences (onboarding not completed), return empty list
  if (tenantPrefs == null) {
    return <CategoryModel>[];
  }
  
  // Try to get local categories first
  final localCategories = await repository.getLocalCategories();
  
  // If no local categories, sync from API (happens after onboarding)
  if (localCategories.isEmpty) {
    try {
      return await repository.syncFromApi();
    } catch (e) {
      // If sync fails, return empty list (offline mode)
      return <CategoryModel>[];
    }
  }
  
  return localCategories;
});

/// Provides root categories only (parentId = null) for the current tenant sector
final rootCategoriesProvider = FutureProvider.autoDispose<List<CategoryModel>>((ref) async {
  final repository = ref.watch(categoryRepositoryProvider);
  
  // Check if tenant has completed onboarding
  final tenantPrefs = await ref.watch(tenantPreferencesProvider.future);
  if (tenantPrefs == null) {
    return <CategoryModel>[];
  }
  
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

/// Trigger category sync from API
Future<void> refreshCategories(WidgetRef ref) async {
  final repository = ref.read(categoryRepositoryProvider);
  await repository.syncFromApi();
  // Trigger provider refresh
  ref.read(categoryRefreshProvider.notifier).state++;
}

