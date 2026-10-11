import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/domain/repository/day_closure_repository.dart';
import 'package:keevo/features/pos/domain/usecase/close_day_usecase.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:mocktail/mocktail.dart';

class MockRepo extends Mock implements DayClosureRepository {}

DayClosure _closure(DateTime at) => DayClosure(
      id: 'c1',
      storeId: 'store-1',
      actorId: 'owner',
      closedAt: at,
      summary: const DayClosureSummary(
        totalSales: 1,
        totalRevenue: 1000,
        cashAmount: 1000,
        momoAmount: 0,
        topProductQty: 1,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      ),
      isAutomatic: false,
      synced: true,
    );

void main() {
  late MockRepo repo;

  setUp(() => repo = MockRepo());

  test(
      'stays closed after a closure today even with new sales — the backend '
      'accepts only one closure per day', () async {
    final closedAt = DateTime.now().subtract(const Duration(seconds: 1));
    when(() => repo.getLastClosure('store-1'))
        .thenAnswer((_) async => _closure(closedAt));
    when(() => repo.getTodaySalesCount('store-1')).thenAnswer((_) async => 5);

    final container = ProviderContainer(
      overrides: [dayClosureRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);

    final state =
        await container.read(dayClosureStateProvider('store-1').future);
    expect(state, DayCloseButtonState.closed);
  });

  test('second closure the same day throws a readable exception', () async {
    when(() => repo.hasClosureToday('store-1')).thenAnswer((_) async => true);

    final useCase = CloseDayUseCase(repository: repo);

    await expectLater(
      useCase.execute(storeId: 'store-1', actorId: 'owner'),
      throwsA(isA<Exception>().having((e) => e.toString(), 'message',
          'La journée a déjà été clôturée pour cette boutique.')),
    );
  });

  // v1s-13-11 (D3): lastClosureAtProvider is derived from lastClosureProvider.
  group('lastClosureAtProvider', () {
    test('yields the closedAt of the store\'s last closure', () async {
      final closedAt = DateTime(2026, 3, 19, 20, 0);
      when(() => repo.getLastClosure('store-1'))
          .thenAnswer((_) async => _closure(closedAt));
      final container = ProviderContainer(
        overrides: [dayClosureRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      expect(await container.read(lastClosureAtProvider('store-1').future),
          closedAt);
    });

    test('yields null when the store has no closure', () async {
      when(() => repo.getLastClosure('store-1')).thenAnswer((_) async => null);
      final container = ProviderContainer(
        overrides: [dayClosureRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      expect(
          await container.read(lastClosureAtProvider('store-1').future), isNull);
    });

    test('refreshes when lastClosureProvider is invalidated', () async {
      when(() => repo.getLastClosure('store-1')).thenAnswer((_) async => null);
      final container = ProviderContainer(
        overrides: [dayClosureRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);
      final sub = container.listen(lastClosureAtProvider('store-1'), (_, __) {});
      addTearDown(sub.close);
      expect(
          await container.read(lastClosureAtProvider('store-1').future), isNull);

      final closedAt = DateTime(2026, 3, 19, 20, 0);
      when(() => repo.getLastClosure('store-1'))
          .thenAnswer((_) async => _closure(closedAt));
      container.invalidate(lastClosureProvider);

      expect(await container.read(lastClosureAtProvider('store-1').future),
          closedAt);
    });
  });
}
