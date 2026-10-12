import 'package:flutter/material.dart';

/// Clamps [range] into [first, last] by calendar day (v1s-16-13).
///
/// `showDateRangePicker` asserts that its initial range lies within
/// [firstDate, lastDate]: a stored custom range (e.g. picked last year, or
/// ending later today) would crash the picker. Null [range] → null.
DateTimeRange? clampDateRange(
    DateTimeRange? range, DateTime first, DateTime last) {
  if (range == null) return null;
  final lo = DateUtils.dateOnly(first);
  final hi = DateUtils.dateOnly(last);
  DateTime clamp(DateTime d) {
    final day = DateUtils.dateOnly(d);
    if (day.isBefore(lo)) return lo;
    if (day.isAfter(hi)) return hi;
    return day;
  }

  final start = clamp(range.start);
  final end = clamp(range.end);
  return DateTimeRange(start: start, end: end.isBefore(start) ? start : end);
}
