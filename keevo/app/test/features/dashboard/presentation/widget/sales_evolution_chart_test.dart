import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/widget/sales_evolution_chart.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr');
  });

  group('SalesEvolutionChart', () {
    testWidgets('renders title text', (tester) async {
      await tester.pumpWidget(_wrap(SalesEvolutionChart(
        data: List.generate(
          10,
          (i) => DailyCA(date: DateTime(2025, 1, i + 1), amount: (i + 1) * 1000),
        ),
      )));

      expect(find.text('Évolution des ventes'), findsOneWidget);
    });

    testWidgets('renders with empty data', (tester) async {
      await tester.pumpWidget(_wrap(const SalesEvolutionChart(data: [])));

      expect(find.text('Évolution des ventes'), findsOneWidget);
      expect(find.byType(CustomPaint), findsAtLeast(1));
    });

    testWidgets('renders CustomPaint for bar chart', (tester) async {
      await tester.pumpWidget(_wrap(SalesEvolutionChart(
        data: [
          DailyCA(date: DateTime(2025, 1, 1), amount: 5000),
          DailyCA(date: DateTime(2025, 1, 2), amount: 8000),
          DailyCA(date: DateTime(2025, 1, 3), amount: 3000),
        ],
      )));

      expect(find.byType(CustomPaint), findsAtLeast(1));
    });
  });
}
