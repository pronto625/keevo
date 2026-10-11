import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/profitability/presentation/widget/period_filter_chips.dart';

void main() {
  test('a preset window follows the calendar (recomputed after midnight)', () {
    final before = PeriodSelection.last7.range(DateTime(2026, 3, 16, 23, 59));
    final after = PeriodSelection.last7.range(DateTime(2026, 3, 17, 0, 1));

    expect(before, (from: DateTime(2026, 3, 10), to: DateTime(2026, 3, 16)));
    expect(after, (from: DateTime(2026, 3, 11), to: DateTime(2026, 3, 17)));
  });

  test('a custom window keeps its dates', () {
    final c = PeriodSelection.custom(DateTime(2026, 1, 1), DateTime(2026, 1, 5));
    expect(c.range(DateTime(2026, 6, 1)),
        (from: DateTime(2026, 1, 1), to: DateTime(2026, 1, 5)));
  });

  testWidgets('the selected chip is chosen by kind, not by dates',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: PeriodFilterChips(
          selected: PeriodSelection.last30,
          onChanged: (_) {},
        ),
      ),
    ));

    ChoiceChip chip(String label) =>
        tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));
    expect(chip('30 jours').selected, isTrue);
    expect(chip('Personnalisé').selected, isFalse);
  });
}
