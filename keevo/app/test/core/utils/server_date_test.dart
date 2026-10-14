import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/utils/server_date.dart';

/// Server timestamps (UTC, `Z`) must reach the UI in device local time.
/// Assertions compare instants so they pass in any test-machine timezone;
/// in Africa/Douala (UTC+1) 23:56Z reads as 00:56 the next day.
void main() {
  const utcIso = '2026-10-13T23:56:03Z';
  final instant = DateTime.parse(utcIso);

  group('parseServerDate', () {
    test('UTC string → local DateTime at the same instant', () {
      final d = parseServerDate(utcIso);

      expect(d.isUtc, isFalse);
      expect(d.isAtSameMomentAs(instant), isTrue);
      expect(d.hour, instant.toLocal().hour);
      expect(d.minute, 56);
    });

    test('offset-less string is already local — unchanged', () {
      final d = parseServerDate('2026-10-14T00:56:03');

      expect(d.isUtc, isFalse);
      expect(d, DateTime(2026, 10, 14, 0, 56, 3));
    });

    test('date-only value never shifts a day', () {
      final d = parseServerDate('2026-10-13');

      expect(d, DateTime(2026, 10, 13));
    });
  });

  group('tryParseServerDate', () {
    test('null, empty or malformed → null', () {
      expect(tryParseServerDate(null), isNull);
      expect(tryParseServerDate(''), isNull);
      expect(tryParseServerDate('pas une date'), isNull);
    });

    test('UTC string → local DateTime at the same instant', () {
      final d = tryParseServerDate(utcIso)!;

      expect(d.isUtc, isFalse);
      expect(d.isAtSameMomentAs(instant), isTrue);
    });
  });

  group('ServerDateTimeConverter', () {
    test('fromJson → local, toJson → UTC ISO of the same instant', () {
      const c = ServerDateTimeConverter();
      final d = c.fromJson(utcIso);

      expect(d.isUtc, isFalse);
      expect(d.isAtSameMomentAs(instant), isTrue);
      expect(c.toJson(d), '2026-10-13T23:56:03.000Z');
    });

    test('nullable variant passes null through', () {
      const c = NullableServerDateTimeConverter();

      expect(c.fromJson(null), isNull);
      expect(c.toJson(null), isNull);
      expect(c.fromJson(utcIso)!.isUtc, isFalse);
    });
  });
}
