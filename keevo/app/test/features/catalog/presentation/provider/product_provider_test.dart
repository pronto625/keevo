import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/repository/product_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';

class _MockProductRepository extends Mock implements ProductRepository {}

void main() {
  group('productListForPickerProvider', () {
    late _MockProductRepository mockRepo;

    final fakeProduct = ProductModel(
      id: 'prod-001',
      name: 'Robe Wax L',
      createdAt: DateTime(2026, 3, 14),
      updatedAt: DateTime(2026, 3, 14),
    );

    setUp(() {
      mockRepo = _MockProductRepository();
    });

    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          productRepositoryProvider.overrideWithValue(mockRepo),
        ]);

    test('returns local data when syncFromRemote succeeds', () async {
      when(() => mockRepo.syncFromRemote()).thenAnswer((_) async {});
      when(() => mockRepo.getAll()).thenAnswer((_) async => [fakeProduct]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(productListForPickerProvider.future);

      expect(result, hasLength(1));
      expect(result.first.id, 'prod-001');
    });

    test(
        'falls back to local data when syncFromRemote throws (offline) '
        'instead of propagating the error to the picker', () async {
      when(() => mockRepo.syncFromRemote())
          .thenThrow(Exception('DioException: connection error'));
      when(() => mockRepo.getAll()).thenAnswer((_) async => [fakeProduct]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(productListForPickerProvider.future);

      expect(result, hasLength(1));
      expect(result.first.id, 'prod-001');
    });
  });
}
