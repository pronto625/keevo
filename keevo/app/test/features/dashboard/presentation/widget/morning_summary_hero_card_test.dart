import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/widget/morning_summary_hero_card.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('MorningSummaryHeroCard', () {
    testWidgets('renders today CA formatted', (tester) async {
      await tester.pumpWidget(_wrap(MorningSummaryHeroCard(
        todayCA: 125000,
        trendPercent: 10.5,
        last7Days: List.generate(
          7,
          (i) => DailyCA(date: DateTime(2025, 1, i + 1), amount: (i + 1) * 1000),
        ),
        lastUpdated: DateTime.now(),
      )));

      // Should display XAF formatted amount
      expect(find.textContaining('125'), findsAtLeast(1));
    });

    testWidgets('shows trend badge when trendPercent > 0', (tester) async {
      await tester.pumpWidget(_wrap(MorningSummaryHeroCard(
        todayCA: 50000,
        trendPercent: 15.3,
        last7Days: const [],
        lastUpdated: DateTime.now(),
      )));

      expect(find.textContaining('↑'), findsOneWidget);
    });

    testWidgets('hides trend badge when trendPercent is 0', (tester) async {
      await tester.pumpWidget(_wrap(MorningSummaryHeroCard(
        todayCA: 50000,
        trendPercent: 0,
        last7Days: const [],
        lastUpdated: DateTime.now(),
      )));

      expect(find.textContaining('↑'), findsNothing);
      expect(find.textContaining('↓'), findsNothing);
    });

    testWidgets('displays CA D\'AUJOURD\'HUI label', (tester) async {
      await tester.pumpWidget(_wrap(MorningSummaryHeroCard(
        todayCA: 0,
        trendPercent: 0,
        last7Days: const [],
        lastUpdated: DateTime.now(),
      )));

      expect(find.text("CA D'AUJOURD'HUI"), findsOneWidget);
    });
  });
}
