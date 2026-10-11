import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _MockStoreRepository extends Mock implements StoreRepository {}

void main() {
  late _MockStoreRepository mockRepo;

  final now = DateTime(2025, 1, 1, 10, 0);
  final sampleStore = StoreModel(
    id: 'store-1',
    name: 'Boutique Test',
    type: StoreType.store,
    createdAt: now,
    updatedAt: now,
  );

  setUpAll(() {
    registerFallbackValue(StoreType.store);
  });

  setUp(() {
    mockRepo = _MockStoreRepository();
    // Default stubs for all getStores() call signatures
    when(() => mockRepo.getStores()).thenAnswer((_) async => [sampleStore]);
    when(() => mockRepo.getStores(includeInactive: false))
        .thenAnswer((_) async => [sampleStore]);
    when(() => mockRepo.getStores(includeInactive: true))
        .thenAnswer((_) async => [sampleStore]);
    when(() => mockRepo.syncFromRemote()).thenAnswer((_) async {});
  });

  ProviderContainer makeContainer() => ProviderContainer(
        overrides: [
          storeRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );

  group('StoreListNotifier (Story 3.1 — Task 19)', () {
    test('initial state loads stores from repository', () async {
      final container = makeContainer();
      addTearDown(container.dispose);

      final state = await container.read(storeListNotifierProvider.future);
      expect(state, [sampleStore]);
    });

    test('createStore delegates to repository', () async {
      when(() => mockRepo.createStore(
            name: any(named: 'name'),
            type: any(named: 'type'),
            address: any(named: 'address'),
            phone: any(named: 'phone'),
          )).thenAnswer((_) async => sampleStore);

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(storeListNotifierProvider.future);
      await container
          .read(storeListNotifierProvider.notifier)
          .createStore(name: 'Test', type: StoreType.store);

      verify(() => mockRepo.createStore(
            name: 'Test',
            type: StoreType.store,
            address: null,
            phone: null,
          )).called(1);
    });

    test('refresh updates state', () async {
      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(storeListNotifierProvider.future);
      clearInteractions(mockRepo);
      await container.read(storeListNotifierProvider.notifier).refresh();

      verify(() => mockRepo.getStores()).called(1);
      verifyNever(() => mockRepo.getStores(includeInactive: true));
    });
  });

  // The shared list feeds every store picker: the stores page's
  // "Voir les désactivées" must never leak deactivated stores into it.
  group('managedStoresProvider', () {
    test('lists inactive stores without changing the shared list', () async {
      final inactive = StoreModel(
        id: 'store-2',
        name: 'Fermée',
        type: StoreType.store,
        isActive: false,
        createdAt: now,
        updatedAt: now,
      );
      when(() => mockRepo.getStores(includeInactive: true))
          .thenAnswer((_) async => [sampleStore, inactive]);
      final container = makeContainer();
      addTearDown(container.dispose);

      final managed = await container.read(managedStoresProvider(true).future);
      final shared = await container.read(storeListNotifierProvider.future);
      await container.read(storeListNotifierProvider.notifier).refresh();

      expect(managed, [sampleStore, inactive]);
      expect(shared, [sampleStore]);
      expect(container.read(storeListNotifierProvider).value, [sampleStore]);
    });
  });
}

