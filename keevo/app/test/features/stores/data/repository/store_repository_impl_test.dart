import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/data/datasource/local_store_datasource.dart';
import 'package:keevo/features/stores/data/datasource/remote_store_datasource.dart';
import 'package:keevo/features/stores/data/repository/store_repository_impl.dart';
import 'package:keevo/features/stores/domain/exception/store_exception.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';

class _MockLocalDataSource extends Mock implements LocalStoreDataSource {}

class _MockRemoteDataSource extends Mock implements RemoteStoreDataSource {}

void main() {
  late _MockLocalDataSource local;
  late _MockRemoteDataSource remote;
  late StoreRepositoryImpl repo;

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
    registerFallbackValue(StoreModel(
      id: 'fallback',
      name: 'fallback',
      createdAt: DateTime(2025),
      updatedAt: DateTime(2025),
    ));
  });

  setUp(() {
    local = _MockLocalDataSource();
    remote = _MockRemoteDataSource();
    repo = StoreRepositoryImpl(local: local, remote: remote);
  });

  group('StoreRepositoryImpl (Story 3.1 — Task 18.4)', () {
    test('getStores() returns local data', () async {
      when(() => local.getAll(includeInactive: false))
          .thenAnswer((_) async => [sampleStore]);

      final result = await repo.getStores();
      expect(result.length, 1);
      verifyNever(() => remote.getAll());
    });

    test('createStore() calls remote then upserts locally', () async {
      when(() => remote.create(
            name: any(named: 'name'),
            type: any(named: 'type'),
            address: any(named: 'address'),
            phone: any(named: 'phone'),
          )).thenAnswer((_) async => sampleStore);
      when(() => local.upsert(sampleStore)).thenAnswer((_) async => sampleStore);

      final result = await repo.createStore(
        name: 'Boutique Test',
        type: StoreType.store,
      );
      expect(result, sampleStore);
      verify(() => remote.create(
            name: 'Boutique Test',
            type: StoreType.store,
            address: null,
            phone: null,
          )).called(1);
      verify(() => local.upsert(sampleStore)).called(1);
    });

    test('createStore() rethrows StoreException.planLimitExceeded', () async {
      when(() => remote.create(
            name: any(named: 'name'),
            type: any(named: 'type'),
            address: any(named: 'address'),
            phone: any(named: 'phone'),
          )).thenThrow(StoreException.planLimitExceeded());

      expect(
        () => repo.createStore(name: 'X', type: StoreType.store),
        throwsA(isA<StoreException>().having(
            (e) => e.domainCode, 'domainCode', 'PLAN_LIMIT_EXCEEDED')),
      );
    });

    test('syncFromRemote() fetches all and upserts each locally', () async {
      when(() => remote.getAll(includeInactive: true))
          .thenAnswer((_) async => [sampleStore]);
      when(() => local.upsert(any())).thenAnswer((_) async => sampleStore);

      await repo.syncFromRemote();
      verify(() => local.upsert(sampleStore)).called(1);
    });
  });
}
