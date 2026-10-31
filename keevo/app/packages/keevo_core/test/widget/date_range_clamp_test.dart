import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/widget/date_range_clamp.dart';

void main() {
  final first = DateTime(2025, 1, 1);
  final last = DateTime(2026, 1, 3, 9, 30);

  test('a range inside the bounds is kept (dates only)', () {
    final r = clampDateRange(
        DateTimeRange(
            start: DateTime(2025, 6, 1, 8), end: DateTime(2025, 6, 7, 23)),
        first,
        last)!;
    expect(r.start, DateTime(2025, 6, 1));
    expect(r.end, DateTime(2025, 6, 7));
  });

  test('a range ending later today is clamped to lastDate', () {
    final r = clampDateRange(
        DateTimeRange(
            start: DateTime(2026, 1, 1), end: DateTime(2026, 1, 3, 23, 59)),
        first,
        last)!;
    expect(r.end, DateTime(2026, 1, 3));
  });

  test('a stored range before firstDate (after New Year) is clamped', () {
    final r = clampDateRange(
        DateTimeRange(start: DateTime(2024, 12, 20), end: DateTime(2024, 12, 31)),
        first,
        last)!;
    expect(r.start, DateTime(2025, 1, 1));
    expect(r.end, DateTime(2025, 1, 1));
  });

  test('a range after lastDate collapses on lastDate', () {
    final r = clampDateRange(
        DateTimeRange(start: DateTime(2026, 2, 1), end: DateTime(2026, 2, 5)),
        first,
        last)!;
    expect(r.start, DateTime(2026, 1, 3));
    expect(r.end, DateTime(2026, 1, 3));
  });

  test('null stays null', () {
    expect(clampDateRange(null, first, last), isNull);
  });
}
