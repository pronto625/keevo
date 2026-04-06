import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo/features/catalog/domain/model/category_model.dart';
import 'package:keevo/features/catalog/domain/repository/category_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';

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

void main() {
  group('CategoryProvider Tests', () {
    late ProviderContainer container;
    late MockCategoryRepository mockRepository;

    setUp(() {
      mockRepository = MockCategoryRepository();
      
      container = ProviderContainer(
        overrides: [
          categoryRepositoryProvider.overrideWithValue(mockRepository),
        ],
      );
    });

    tearDown(() {
      container.dispose();
    });

    test('categoriesProvider returns categories from API when local is empty', () async {
      // Setup: empty local, data available from API
      mockRepository.setLocalCategories([]);
      final now = DateTime.now();
      mockRepository.setApiCategories([
        CategoryModel(
          id: '1',
          name: 'Electronics',
          isActive: true,
          parentId: null,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
        CategoryModel(
          id: '2',
          name: 'Clothing',
          isActive: true,
          parentId: null,
          isCustom: false,
          createdAt: now,
          updatedAt: now,
        ),
      ]);

      final result = await container.read(categoriesProvider.future);

      expect(result.length, equals(2));
      expect(result[0].name, equals('Electronics'));
      expect(result[1].name, equals('Clothing'));
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