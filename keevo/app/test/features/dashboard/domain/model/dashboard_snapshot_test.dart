import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  DashboardSnapshot _make({
    int todayCA = 0,
    int yesterdayCA = 0,
    int dayBeforeYesterdayCA = 0,
    int totalTransactionsMonth = 0,
    int averageBasketMonth = 0,
    int prevMonthTransactions = 0,
    int prevMonthAverageBasket = 0,
    int lowStockCount = 0,
    int todaySalesCount = 0,
    List<DailyCA> dailyCALast30 = const [],
  }) =>
      DashboardSnapshot(
        todayCA: todayCA,
        yesterdayCA: yesterdayCA,
        dayBeforeYesterdayCA: dayBeforeYesterdayCA,
        trendPercent: 0,
        totalTransactionsMonth: totalTransactionsMonth,
        averageBasketMonth: averageBasketMonth,
        prevMonthTransactions: prevMonthTransactions,
        prevMonthAverageBasket: prevMonthAverageBasket,
        lowStockCount: lowStockCount,
        weeklyTopProducts: const [],
        weeklyWorstProducts: const [],
        dailyCALast30: dailyCALast30,
        storeOverviews: const [],
        todaySalesCount: todaySalesCount,
      );

  group('heroTrendPercent', () {
    test('returns 0 when yesterdayCA is 0', () {
      final s = _make(todayCA: 1000, yesterdayCA: 0);
      expect(s.heroTrendPercent, 0);
    });

    test('returns positive percent when todayCA > yesterdayCA', () {
      final s = _make(todayCA: 150, yesterdayCA: 100);
      expect(s.heroTrendPercent, 50.0);
    });

    test('returns negative percent when todayCA < yesterdayCA', () {
      final s = _make(todayCA: 80, yesterdayCA: 100);
      expect(s.heroTrendPercent, -20.0);
    });
  });

  group('transactionsTrend', () {
    test('returns 0 when prevMonthTransactions is 0', () {
      final s = _make(totalTransactionsMonth: 10, prevMonthTransactions: 0);
      expect(s.transactionsTrend, 0);
    });

    test('calculates correct trend percent', () {
      final s = _make(totalTransactionsMonth: 120, prevMonthTransactions: 100);
      expect(s.transactionsTrend, 20.0);
    });
  });

  group('averageBasketTrend', () {
    test('returns 0 when prevMonthAverageBasket is 0', () {
      final s = _make(averageBasketMonth: 5000, prevMonthAverageBasket: 0);
      expect(s.averageBasketTrend, 0);
    });

    test('calculates correct trend percent', () {
      final s = _make(averageBasketMonth: 6000, prevMonthAverageBasket: 5000);
      expect(s.averageBasketTrend, closeTo(20.0, 0.01));
    });
  });

  group('last7DaysCA', () {
    test('returns all items when list has <= 7 entries', () {
      final days = List.generate(
        5,
        (i) => DailyCA(date: DateTime(2025, 1, i + 1), amount: i * 100),
      );
      final s = _make(dailyCALast30: days);
      expect(s.last7DaysCA.length, 5);
    });

    test('returns last 7 items from 30-day list', () {
      final days = List.generate(
        30,
        (i) => DailyCA(date: DateTime(2025, 1, i + 1), amount: (i + 1) * 100),
      );
      final s = _make(dailyCALast30: days);
      expect(s.last7DaysCA.length, 7);
      expect(s.last7DaysCA.first.amount, 2400); // day 24
      expect(s.last7DaysCA.last.amount, 3000); // day 30
    });
  });

  group('computeStoreStatus', () {
    test('stable when yesterdayCA is 0', () {
      expect(computeStoreStatus(0, 0), StoreStatusLevel.stable);
    });

    test('stable when todayCA >= 80% of yesterday', () {
      expect(computeStoreStatus(80, 100), StoreStatusLevel.stable);
      expect(computeStoreStatus(100, 100), StoreStatusLevel.stable);
    });

    test('attention when todayCA between 50% and 80% of yesterday', () {
      expect(computeStoreStatus(60, 100), StoreStatusLevel.attention);
      expect(computeStoreStatus(50, 100), StoreStatusLevel.attention);
    });

    test('enBaisse when todayCA < 50% of yesterday', () {
      expect(computeStoreStatus(49, 100), StoreStatusLevel.enBaisse);
      expect(computeStoreStatus(0, 100), StoreStatusLevel.enBaisse);
    });
  });

  // withAsOf copies every field by hand; a field dropped there would silently
  // empty a chart (the list parameters have defaults, so it still compiles).
  test('withAsOf keeps every field and only sets asOf', () {
    final day = DateTime(2026, 10, 1);
    const top = TopProduct(
        productId: 'p1', name: 'Savon', unitsSold: 3, revenue: 1500, sharePercent: 60);
    const worst = TopProduct(
        productId: 'p2', name: 'Riz', unitsSold: 1, revenue: 500, sharePercent: 20);
    final s = DashboardSnapshot(
      todayCA: 1,
      yesterdayCA: 2,
      dayBeforeYesterdayCA: 3,
      trendPercent: 4.5,
      totalTransactionsMonth: 5,
      averageBasketMonth: 6,
      prevMonthTransactions: 7,
      prevMonthAverageBasket: 8,
      lowStockCount: 9,
      weeklyTopProducts: const [top],
      weeklyWorstProducts: const [worst],
      dailyCALast30: [DailyCA(date: day, amount: 10)],
      weeklyCA: [DailyCA(date: day, amount: 11)],
      monthlyCA: [DailyCA(date: day, amount: 12)],
      yearlyCA: [DailyCA(date: day, amount: 13)],
      storeOverviews: const [],
      todaySalesCount: 14,
    );
    final at = DateTime(2026, 10, 11, 9);

    final c = s.withAsOf(at);

    expect(c.asOf, at);
    expect([
      c.todayCA, c.yesterdayCA, c.dayBeforeYesterdayCA,
      c.totalTransactionsMonth, c.averageBasketMonth,
      c.prevMonthTransactions, c.prevMonthAverageBasket,
      c.lowStockCount, c.todaySalesCount,
    ], [1, 2, 3, 5, 6, 7, 8, 9, 14]);
    expect(c.trendPercent, 4.5);
    expect(c.weeklyTopProducts, same(s.weeklyTopProducts));
    expect(c.weeklyWorstProducts, same(s.weeklyWorstProducts));
    expect(c.dailyCALast30, same(s.dailyCALast30));
    expect(c.weeklyCA, same(s.weeklyCA));
    expect(c.monthlyCA, same(s.monthlyCA));
    expect(c.yearlyCA, same(s.yearlyCA));
    expect(c.storeOverviews, same(s.storeOverviews));
  });
}
