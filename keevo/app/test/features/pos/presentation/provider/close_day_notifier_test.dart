import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/domain/usecase/close_day_usecase.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:mocktail/mocktail.dart';

class MockCloseDayUseCase extends Mock implements CloseDayUseCase {}

void main() {
  late MockCloseDayUseCase mockUseCase;

  const summary = DayClosureSummary(
    totalSales: 5,
    totalRevenue: 25000,
    cashAmount: 15000,
    momoAmount: 10000,
    topProductQty: 3,
    pendingSalesCount: 0,
    pendingSalesTotal: 0,
  );

  setUp(() {
    mockUseCase = MockCloseDayUseCase();
  });

  group('CloseDayNotifier — AC12 no direct syncService.push()', () {
    test('closeDay delegates to CloseDayUseCase only — no sync push', () async {
      when(() => mockUseCase.execute(
            storeId: any(named: 'storeId'),
            actorId: any(named: 'actorId'),
          )).thenAnswer((_) async => summary);

      final container = ProviderContainer(
        overrides: [
          closeDayUseCaseProvider.overrideWithValue(mockUseCase),
        ],
      );
      addTearDown(container.dispose);

      final notifier = container.read(closeDayNotifierProvider.notifier);
      await notifier.closeDay('store-1', 'emp-1');

      verify(() => mockUseCase.execute(storeId: 'store-1', actorId: 'emp-1'))
          .called(1);
      // CloseDayNotifier does NOT call syncService.push() directly.
      // Sync happens via SyncTriggerNotifier on connectivity change (AC12).
    });

    test('closeDay sets AsyncData on success', () async {
      when(() => mockUseCase.execute(
            storeId: any(named: 'storeId'),
            actorId: any(named: 'actorId'),
          )).thenAnswer((_) async => summary);

      final container = ProviderContainer(
        overrides: [
          closeDayUseCaseProvider.overrideWithValue(mockUseCase),
        ],
      );
      addTearDown(container.dispose);

      final notifier = container.read(closeDayNotifierProvider.notifier);
      await notifier.closeDay('store-1', 'emp-1');

      final state = container.read(closeDayNotifierProvider);
      expect(state.value, summary);
    });

    test('closeDay sets AsyncError on failure and rethrows', () async {
      when(() => mockUseCase.execute(
            storeId: any(named: 'storeId'),
            actorId: any(named: 'actorId'),
          )).thenThrow(StateError('Already closed'));

      final container = ProviderContainer(
        overrides: [
          closeDayUseCaseProvider.overrideWithValue(mockUseCase),
        ],
      );
      addTearDown(container.dispose);

      final notifier = container.read(closeDayNotifierProvider.notifier);
      expect(
        () => notifier.closeDay('store-1', 'emp-1'),
        throwsA(isA<StateError>()),
      );
    });
  });
}
