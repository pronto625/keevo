import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/contact/domain/repository/client_repository.dart';
import 'package:keevo/features/contact/domain/repository/supplier_repository.dart';
import 'package:keevo/features/contact/presentation/provider/contact_provider.dart';
import 'package:mocktail/mocktail.dart';

class MockClientRepository extends Mock implements ClientRepository {}

class MockSupplierRepository extends Mock implements SupplierRepository {}

void main() {
  group('ClientListNotifier keeps the archived toggle', () {
    late MockClientRepository repo;
    late ProviderContainer container;

    setUp(() {
      repo = MockClientRepository();
      when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.syncFromRemote()).thenAnswer((_) async {});
      container = ProviderContainer(
          overrides: [clientRepositoryProvider.overrideWithValue(repo)]);
      container.listen(clientListNotifierProvider, (_, __) {});
    });

    tearDown(() => container.dispose());

    test('syncFromRemote after showing archived keeps includeArchived', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      clearInteractions(repo);

      await notifier.syncFromRemote();

      verify(() => repo.getAll(includeArchived: true)).called(1);
    });

    test('empty search after showing archived keeps includeArchived', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      clearInteractions(repo);

      await notifier.search('');

      verify(() => repo.getAll(includeArchived: true)).called(1);
    });
  });

  group('ClientListNotifier.syncFromRemote failure', () {
    test('rethrows the sync error but still reloads the local list', () async {
      final repo = MockClientRepository();
      when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.syncFromRemote()).thenThrow(Exception('network'));
      final container = ProviderContainer(
          overrides: [clientRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(container.dispose);
      container.listen(clientListNotifierProvider, (_, __) {});
      await container.read(clientListNotifierProvider.future);
      clearInteractions(repo);

      await expectLater(
          container.read(clientListNotifierProvider.notifier).syncFromRemote(),
          throwsA(isA<Exception>()));

      verify(() => repo.getAll(includeArchived: false)).called(1);
      expect(container.read(clientListNotifierProvider).hasValue, isTrue);
    });
  });

  group('SupplierListNotifier keeps the archived toggle', () {
    late MockSupplierRepository repo;
    late ProviderContainer container;

    setUp(() {
      repo = MockSupplierRepository();
      when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.syncFromRemote()).thenAnswer((_) async {});
      container = ProviderContainer(
          overrides: [supplierRepositoryProvider.overrideWithValue(repo)]);
      container.listen(supplierListNotifierProvider, (_, __) {});
    });

    tearDown(() => container.dispose());

    test('syncFromRemote after showing archived keeps includeArchived', () async {
      await container.read(supplierListNotifierProvider.future);
      final notifier = container.read(supplierListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      clearInteractions(repo);

      await notifier.syncFromRemote();

      verify(() => repo.getAll(includeArchived: true)).called(1);
    });

    test('empty search after showing archived keeps includeArchived', () async {
      await container.read(supplierListNotifierProvider.future);
      final notifier = container.read(supplierListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      clearInteractions(repo);

      await notifier.search('');

      verify(() => repo.getAll(includeArchived: true)).called(1);
    });
  });

  // v1s-16-13: the query survives refreshes and follows the archive toggle.
  group('ClientListNotifier keeps the search', () {
    late MockClientRepository repo;
    late ProviderContainer container;

    setUp(() {
      repo = MockClientRepository();
      when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.search(any(),
              includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.syncFromRemote()).thenAnswer((_) async {});
      when(() => repo.archive(any())).thenAnswer((_) async {});
      container = ProviderContainer(
          overrides: [clientRepositoryProvider.overrideWithValue(repo)]);
      container.listen(clientListNotifierProvider, (_, __) {});
    });

    tearDown(() => container.dispose());

    test('search with archived shown includes archived matches', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      clearInteractions(repo);

      await notifier.search('ali');

      verify(() => repo.search('ali', includeArchived: true)).called(1);
      verifyNever(() =>
          repo.getAll(includeArchived: any(named: 'includeArchived')));
    });

    test('toggling archived re-runs the current search', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.search('ali');
      clearInteractions(repo);

      await notifier.refresh(includeArchived: true);

      verify(() => repo.search('ali', includeArchived: true)).called(1);
    });

    test('sync and archive keep the query', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.search('ali');
      clearInteractions(repo);

      await notifier.syncFromRemote();
      await notifier.archive('c1');

      verify(() => repo.search('ali', includeArchived: false)).called(2);
      verifyNever(() =>
          repo.getAll(includeArchived: any(named: 'includeArchived')));
    });

    test('clearing the search lists everything again', () async {
      await container.read(clientListNotifierProvider.future);
      final notifier = container.read(clientListNotifierProvider.notifier);
      await notifier.search('ali');
      clearInteractions(repo);

      await notifier.search('');
      await notifier.refresh();

      verify(() => repo.getAll(includeArchived: false)).called(2);
    });
  });

  group('SupplierListNotifier keeps the search', () {
    test('sync refresh keeps the query and the archived toggle', () async {
      final repo = MockSupplierRepository();
      when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.search(any(),
              includeArchived: any(named: 'includeArchived')))
          .thenAnswer((_) async => []);
      when(() => repo.syncFromRemote()).thenAnswer((_) async {});
      final container = ProviderContainer(
          overrides: [supplierRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(container.dispose);
      container.listen(supplierListNotifierProvider, (_, __) {});
      await container.read(supplierListNotifierProvider.future);
      final notifier = container.read(supplierListNotifierProvider.notifier);
      await notifier.refresh(includeArchived: true);
      await notifier.search('gros');
      clearInteractions(repo);

      await notifier.syncFromRemote();

      verify(() => repo.search('gros', includeArchived: true)).called(1);
    });
  });
}
