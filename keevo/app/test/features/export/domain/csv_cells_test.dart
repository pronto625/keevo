import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/domain/local/csv_cells.dart';
import 'package:keevo/features/export/domain/local/local_export_schema.dart';

/// v1s-18-5 — cell formats identical to the server `CsvExportRenderer` /
/// `ExportCells`.
void main() {
  group('formatCsvCell', () {
    test('null is an empty cell for every type', () {
      for (final t in LocalColumnType.values) {
        expect(formatCsvCell(t, null), '', reason: t.name);
      }
    });

    test('integers and XAF amounts are plain integers', () {
      expect(formatCsvCell(LocalColumnType.amountXaf, 1500), '1500');
      expect(formatCsvCell(LocalColumnType.amountXaf, 1500000), '1500000');
      expect(formatCsvCell(LocalColumnType.integer, -3), '-3');
      expect(formatCsvCell(LocalColumnType.integer, 2.5), '3');
    });

    test('decimals use a decimal comma', () {
      expect(formatCsvCell(LocalColumnType.decimal, 12.5), '12,5');
      expect(formatCsvCell(LocalColumnType.decimal, 7), '7');
    });

    test('booleans are Oui / Non', () {
      expect(formatCsvCell(LocalColumnType.boolean, true), 'Oui');
      expect(formatCsvCell(LocalColumnType.boolean, false), 'Non');
    });

    test('datetimes are WAT wall clock, crossing midnight', () {
      final v = DateTime.utc(2026, 10, 12, 23, 30);
      expect(formatCsvCell(LocalColumnType.datetime, v), '13/10/2026 00:30:00');
      expect(formatCsvCell(LocalColumnType.date, v), '13/10/2026');
      expect(
          formatCsvCell(
              LocalColumnType.datetime, DateTime.utc(2026, 1, 2, 3, 4, 5)),
          '02/01/2026 04:04:05');
    });

    test('a non-UTC DateTime is converted through UTC, not the device zone',
        () {
      final local = DateTime.utc(2026, 10, 12, 23, 30).toLocal();
      expect(formatCsvCell(LocalColumnType.datetime, local),
          '13/10/2026 00:30:00');
    });

    test('text guarded against formula injection', () {
      expect(formatCsvCell(LocalColumnType.text, '=SUM(A1)'), "'=SUM(A1)");
      expect(formatCsvCell(LocalColumnType.text, '@cmd'), "'@cmd");
      expect(formatCsvCell(LocalColumnType.text, '-x'), "'-x");
      expect(formatCsvCell(LocalColumnType.text, '+cmd'), "'+cmd");
      expect(formatCsvCell(LocalColumnType.text, '\tx'), "'\tx");
      expect(formatCsvCell(LocalColumnType.text, 'Pagne'), 'Pagne');
    });

    test('phone numbers and signed numbers are not formulas', () {
      expect(formatCsvCell(LocalColumnType.text, '+237 699'), '+237 699');
      expect(formatCsvCell(LocalColumnType.text, '+237 6 99 00 00 00'),
          '+237 6 99 00 00 00');
      expect(formatCsvCell(LocalColumnType.text, '-12.5'), '-12.5');
      expect(formatCsvCell(LocalColumnType.text, '-(3)'), '-(3)');
    });
  });

  group('csvRecord (commons-csv QuoteMode.MINIMAL)', () {
    test('plain values, ; separator, CRLF', () {
      expect(csvRecord(['a', 'b', '1500']), 'a;b;1500\r\n');
    });

    test('empty value quoted only when first in the record', () {
      expect(csvRecord(['', 'x', '']), '"";x;\r\n');
      expect(csvRecord(['x', '', '']), 'x;;\r\n');
    });

    test('first char <= # is quoted', () {
      expect(csvRecord(['#1']), '"#1"\r\n');
      expect(csvRecord(['x', '!a']), 'x;"!a"\r\n');
      expect(csvRecord([' a']), '" a"\r\n');
      expect(csvRecord(['x', '#y']), 'x;"#y"\r\n');
    });

    test('separator, quotes, CR, LF are quoted, quotes doubled', () {
      expect(csvRecord(['a;b']), '"a;b"\r\n');
      expect(csvRecord(['dit "oui"']), '"dit ""oui"""\r\n');
      expect(csvRecord(['l1\nl2']), '"l1\nl2"\r\n');
      expect(csvRecord(['l1\rl2']), '"l1\rl2"\r\n');
    });

    test('trailing space is quoted', () {
      expect(csvRecord(['a ']), '"a "\r\n');
    });

    test('guarded text keeps its apostrophe, unquoted', () {
      expect(csvRecord([safeCsvText('=SUM(A1)')]), "'=SUM(A1)\r\n");
    });
  });

  test('toWat is UTC+1 whatever the device zone', () {
    final w = toWat(DateTime.utc(2026, 12, 31, 23, 0));
    expect([w.year, w.month, w.day, w.hour], [2027, 1, 1, 0]);
  });
}
