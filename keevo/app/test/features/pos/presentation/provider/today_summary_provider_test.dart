import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/domain/repository/day_closure_repository.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockDayClosureRepository extends Mock implements DayClosureRepository {}

DayClosureSummary _summary(int totalSales) => DayClosureSummary(
      totalSales: totalSales,
      totalRevenue: totalSales * 1000,
      cashAmount: totalSales * 1000,
      momoAmount: 0,
      topProductQty: 0,
      pendingSalesCount: 0,
      pendingSalesTotal: 0,
    );

/// v1s-15-8 (deferred from HF-2 ghost-badge): todaySummaryProvider scoped to
/// an EMPLOYEE (employeeId set) vs the whole store (employeeId null).
void main() {
  late _MockDayClosureRepository repo;
  late ProviderContainer container;

  setUp(() {
    repo = _MockDayClosureRepository();
    when(() => repo.computeTodaySummary('store-1', 'emp-1'))
        .thenAnswer((_) async => _summary(2));
    when(() => repo.computeTodaySummary('store-1', null))
        .thenAnswer((_) async => _summary(9));
    container = ProviderContainer(retry: noProviderRetry, overrides: [
      dayClosureRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
  });

  test('EMPLOYEE scope forwards the employee id to the repository', () async {
    final summary = await container.read(
        todaySummaryProvider((storeId: 'store-1', employeeId: 'emp-1')).future);

    expect(summary.totalSales, 2);
    verify(() => repo.computeTodaySummary('store-1', 'emp-1')).called(1);
    verifyNever(() => repo.computeTodaySummary(any(), any(that: isNull)));
  });

  test('employee and store-wide summaries are distinct requests',
      () async {
    final employee = await container.read(
        todaySummaryProvider((storeId: 'store-1', employeeId: 'emp-1')).future);
    final store = await container.read(
        todaySummaryProvider((storeId: 'store-1', employeeId: null)).future);

    expect(employee.totalSales, 2);
    expect(store.totalSales, 9);
    verify(() => repo.computeTodaySummary('store-1', 'emp-1')).called(1);
    verify(() => repo.computeTodaySummary('store-1', null)).called(1);
  });
}
