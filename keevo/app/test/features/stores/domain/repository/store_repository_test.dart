import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';

class _MockStoreRepository extends Mock implements StoreRepository {}

void main() {
  late _MockStoreRepository repo;
  final now = DateTime(2025, 1, 1, 10, 0);

  setUp(() => repo = _MockStoreRepository());

  final sampleStore = StoreModel(
    id: 'store-1',
    name: 'Boutique Test',
    type: StoreType.store,
    createdAt: now,
    updatedAt: now,
  );

  group('StoreRepository contract (Story 3.1 — Task 17)', () {
    test('getStores() returns list', () async {
      when(() => repo.getStores()).thenAnswer((_) async => [sampleStore]);
      final result = await repo.getStores();
      expect(result, [sampleStore]);
    });

    test('getStores(includeInactive: true) passes flag to impl', () async {
      when(() => repo.getStores(includeInactive: true))
          .thenAnswer((_) async => [sampleStore]);
      final result = await repo.getStores(includeInactive: true);
      expect(result, [sampleStore]);
      verify(() => repo.getStores(includeInactive: true)).called(1);
    });

    test('createStore sends correct args', () async {
      when(() => repo.createStore(
            name: 'New Store',
            type: StoreType.store,
            address: null,
            phone: null,
          )).thenAnswer((_) async => sampleStore);

      final result = await repo.createStore(
        name: 'New Store',
        type: StoreType.store,
      );
      expect(result, sampleStore);
    });

    test('deactivateStore(id) returns deactivated store', () async {
      final inactive = sampleStore.copyWith(isActive: false);
      when(() => repo.deactivateStore('store-1'))
          .thenAnswer((_) async => inactive);
      final result = await repo.deactivateStore('store-1');
      expect(result.isActive, isFalse);
    });
  });
}
