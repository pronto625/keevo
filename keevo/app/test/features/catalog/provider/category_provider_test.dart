import 'dart:ffi';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/catalog/data/repository/category_repository_impl.dart';
import 'package:keevo/features/catalog/domain/model/category_model.dart';
import 'package:keevo/features/catalog/domain/repository/category_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/identity/domain/model/tenant_preferences_model.dart';
import 'package:keevo/features/identity/presentation/provider/tenant_preferences_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

/// Simple mock implementation of CategoryRepository for testing
class MockCategoryRepository implements CategoryRepository {
  List<CategoryModel> _localCategories = [];
  List<CategoryModel> _apiCategories = [];

  void setLocalCategories(List<CategoryModel> categories) {
    _localCategories = categories;
  }

  void setApiCategories(List<CategoryModel> categories) {
    _apiCategories = categories;
  }

  @override
  Future<List<CategoryModel>> getLocalCategories() async {
    return List.from(_localCategories);
  }

  @override
  Future<List<CategoryModel>> syncFromApi() async {
    return List.from(_apiCategories);
  }

  @override
  Future<List<CategoryModel>> getRootCategories() async {
    return _localCategories.where((c) => c.parentId == null).toList();
  }

  @override
  Future<List<CategoryModel>> getSubcategories(String parentId) async {
    return _localCategories.where((c) => c.parentId == parentId).toList();
  }

  @override
  Future<CategoryModel> createCustomCategory(String name, {String? parentId}) async {
    final now = DateTime.now();
    final category = CategoryModel(
      id: 'mock-${now.millisecondsSinceEpoch}',
      name: name,
      isActive: true,
      parentId: parentId,
      isCustom: true,
      createdAt: now,
      updatedAt: now,
    );
    _localCategories.add(category);
    return category;
  }

  @override
  Future<CategoryModel> toggleCategoryStatus(String categoryId) async {
    final index = _localCategories.indexWhere((c) => c.id == categoryId);
    if (index == -1) throw Exception('Category not found');
    
    final category = _localCategories[index];
    final now = DateTime.now();
    final updated = CategoryModel(
      id: category.id,
      name: category.name,
      isActive: !category.isActive,
      parentId: category.parentId,
      isCustom: category.isCustom,
      createdAt: category.createdAt,
      updatedAt: now,
    );
    _localCategories[index] = updated;
    return updated;
  }

  @override
  Future<void> deleteCategory(String categoryId) async {
    _localCategories.removeWhere((c) => c.id == categoryId);
  }

  @override
  Future<CategoryModel> renameCategory(String categoryId, String newName) async {
    final index = _localCategories.indexWhere((c) => c.id == categoryId);
    if (index == -1) throw Exception('Category not found');
    final cat = _localCategories[index];
    final now = DateTime.now();
    final updated = CategoryModel(
      id: cat.id,
      name: newName,
      isActive: cat.isActive,
      parentId: cat.parentId,
      isCustom: cat.isCustom,
      createdAt: cat.createdAt,
      updatedAt: now,
    );
    _localCategories[index] = updated;
    return updated;
  }
}

class _MockSyncService extends Mock implements SyncService {}

class _MockSyncTriggerDispatcher extends Mock
    implements SyncTriggerDispatcher {}

class _MockDio extends Mock implements Dio {}

final _preferencesOverride = tenantPreferencesProvider.overrideWith((_) async =>
    TenantPreferencesModel(
      sectorType: null,
      eodReportTime: '20:00:00',
      stockAlertEnabled: false,
      createdAt: DateTime(2025),
    ));

void main() {
  setUpAll(() {
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  group('CategoryProvider Tests', () {
    late ProviderContainer container;
    late MockCategoryRepository mockRepository;
    late _MockSyncService mockSync;

    setUp(() {
      mockRepository = MockCategoryRepository();
      mockSync = _MockSyncService();
      when(() => mockSync.pull()).thenAnswer((_) async {});

      container = ProviderContainer(
        retry: noProviderRetry,
        overrides: [
          categoryRepositoryProvider.overrideWithValue(mockRepository),
          syncServiceProvider.overrideWithValue(mockSync),
          tenantPreferencesProvider.overrideWith((_) async =>
              TenantPreferencesModel(
                sectorType: null,
                eodReportTime: '20:00:00',
                stockAlertEnabled: false,
                createdAt: DateTime(2025),
              )),
        ],
      );
    });

    tearDown(() {
      container.dispose();
    });

    test('categoriesProvider pulls when local is empty and returns the synced categories',
        () async {
      mockRepository.setLocalCategories([]);
      final now = DateTime.now();
      when(() => mockSync.pull()).thenAnswer((_) async {
        mockRepository.setLocalCategories([
          CategoryModel(
            id: '1',
            name: 'Electronics',
            isActive: true,
            isCustom: false,
            createdAt: now,
            updatedAt: now,
          ),
          CategoryModel(
            id: '2',
            name: 'Clothing',
            isActive: true,
            isCustom: false,
            createdAt: now,
            updatedAt: now,
          ),
        ]);
      });

      final result = await container.read(categoriesProvider.future);

      verify(() => mockSync.pull()).called(1);
      expect(result.map((c) => c.name), ['Electronics', 'Clothing']);
    });

    test('categoriesProvider falls back to GET /api/v1/categories when the pull brings nothing',
        () async {
      final db = AppDatabase.forTesting();
      addTearDown(db.close);
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>('/api/v1/categories'))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/api/v1/categories'),
                data: {
                  'data': [
                    {
                      'id': 'c1',
                      'name': 'Clothing',
                      'parentId': null,
                      'isActive': true,
                      'isCustom': false,
                      'createdAt': '2025-01-01T00:00:00Z',
                      'updatedAt': '2025-01-01T00:00:00Z',
                    },
                    {
                      'id': 'c2',
                      'name': 'Electronics',
                      'parentId': null,
                      'isActive': true,
                      'isCustom': false,
                      'createdAt': '2025-01-01T00:00:00Z',
                      'updatedAt': '2025-01-01T00:00:00Z',
                    },
                  ],
                },
              ));
      final apiContainer = ProviderContainer(retry: noProviderRetry, overrides: [
        appDatabaseProvider.overrideWithValue(db),
        categoryRepositoryProvider.overrideWithValue(CategoryRepositoryImpl(
            db, mockSync, _MockSyncTriggerDispatcher())),
        syncServiceProvider.overrideWithValue(mockSync),
        dioProvider.overrideWithValue(dio),
        _preferencesOverride,
      ]);
      addTearDown(apiContainer.dispose);

      final result = await apiContainer.read(categoriesProvider.future);

      verify(() => mockSync.pull()).called(1);
      expect(result.map((c) => c.name), ['Clothing', 'Electronics']);
    });

    test('categoriesProvider returns local categories when available', () async {
      // Setup: local categories available
      final now = DateTime.now();
      final localCategories = [
        CategoryModel(
          id: '1',
          name: 'Local Category',
          isActive: true,
          parentId: null,
          isCustom: true,
          createdAt: now,
          updatedAt: now,
        ),
      ];
      
      mockRepository.setLocalCategories(localCategories);

      final result = await container.read(categoriesProvider.future);

      expect(result.length, equals(1));
      expect(result[0].name, equals('Local Category'));
      expect(result[0].isCustom, equals(true));
    });

    test('rootCategoriesProvider returns only root categories', () async {
      // Setup: mix of root and sub categories
      final now = DateTime.now();
      final categories = [
        CategoryModel(
          id: '1',
          name: 'Root Category',
          isActive: true,
          parentId: null,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
        CategoryModel(
          id: '2',
          name: 'Subcategory',
          isActive: true,
          parentId: '1',
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
      ];
      
      mockRepository.setLocalCategories(categories);

      final result = await container.read(rootCategoriesProvider.future);

      expect(result.length, equals(1));
      expect(result[0].name, equals('Root Category'));
      expect(result[0].parentId, isNull);
    });

    test('subcategoriesProvider returns subcategories for parent', () async {
      const parentId = 'parent-123';
      
      // Setup: parent with subcategories
      final now = DateTime.now();
      final categories = [
        CategoryModel(
          id: parentId,
          name: 'Parent Category',
          isActive: true,
          parentId: null,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
        CategoryModel(
          id: '2',
          name: 'Subcategory 1',
          isActive: true,
          parentId: parentId,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
        CategoryModel(
          id: '3',
          name: 'Subcategory 2',
          isActive: true,
          parentId: parentId,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
        CategoryModel(
          id: '4',
          name: 'Other Category',
          isActive: true,
          parentId: null,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
      ];
      
      mockRepository.setLocalCategories(categories);

      final result = await container.read(
        subcategoriesProvider(parentId).future,
      );

      expect(result.length, equals(2));
      expect(result[0].name, equals('Subcategory 1'));
      expect(result[1].name, equals('Subcategory 2'));
      expect(result.every((c) => c.parentId == parentId), isTrue);
    });

    test('categoriesProvider returns empty list when sync fails', () async {
      // Setup: empty local, API throws error
      mockRepository.setLocalCategories([]);
      // No API categories set, which will cause syncFromApi to return empty list

      final result = await container.read(categoriesProvider.future);

      expect(result, isEmpty);
    });
  });
}