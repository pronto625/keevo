import 'local_export_schema.dart';

/// CSV cell conventions of the on-device export (v1s-18-5) — a pure mirror of
/// the server `CsvExportRenderer` / `ExportCells`: `;` separator, CRLF,
/// integer XAF amounts, decimal comma, `Oui`/`Non`, `dd/MM/yyyy` dates and
/// `dd/MM/yyyy HH:mm:ss` datetimes in WAT, formula-injection guard, and the
/// commons-csv `QuoteMode.MINIMAL` quoting rules.
///
/// No Flutter import: runs in the writer isolate.

/// UTF-8 byte order mark written at the start of every CSV (French Excel).
const csvBom = '﻿';

/// Field separator (French-locale Excel).
const csvDelimiter = ';';

/// Record separator.
const csvRecordSeparator = '\r\n';

/// WAT = UTC+01:00, fixed (Africa/Douala has no DST). The returned value is
/// a UTC [DateTime] whose fields read as the WAT wall clock — never the
/// device time zone.
DateTime toWat(DateTime value) => value.toUtc().add(const Duration(hours: 1));

/// Numbers, signed numbers and phone numbers (`+237 6 99 00 00 00`) are not
/// formulas (Java `^[+-]?[\d\s().]+$`, ASCII digits / Java whitespace).
final _numericLike = RegExp(r'^[+-]?[0-9 \t\n\x0B\f\r().]+$');

/// CSV formula-injection guard: text starting with `= + - @ TAB CR` gets a
/// leading `'`, unless it is numeric-like (phone numbers, signed numbers).
String safeCsvText(String s) {
  if (s.isEmpty) return s;
  final c = s[0];
  final risky =
      c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
  return risky && !_numericLike.hasMatch(s) ? "'$s" : s;
}

String _two(int v) => v.toString().padLeft(2, '0');

String _date(DateTime wat) =>
    '${_two(wat.day)}/${_two(wat.month)}/${wat.year.toString().padLeft(4, '0')}';

String _dateTime(DateTime wat) =>
    '${_date(wat)} ${_two(wat.hour)}:${_two(wat.minute)}:${_two(wat.second)}';

int _toInt(Object v) {
  if (v is int) return v;
  if (v is double) return v.round();
  if (v is num) return v.round();
  return int.parse(v.toString().trim());
}

String _toDecimal(Object v) {
  final s = v is num ? v.toString() : v.toString().trim();
  return s.replaceAll('.', ',');
}

bool _toBool(Object v) {
  if (v is bool) return v;
  if (v is num) return v != 0;
  return v.toString().toLowerCase() == 'true';
}

DateTime _toDateTime(Object v) {
  if (v is DateTime) return v;
  return DateTime.parse(v.toString());
}

/// Text of one cell of [type] (`null` → empty cell).
String formatCsvCell(LocalColumnType type, Object? value) {
  if (value == null) return '';
  return switch (type) {
    LocalColumnType.text => safeCsvText(value.toString()),
    LocalColumnType.integer ||
    LocalColumnType.amountXaf =>
      _toInt(value).toString(),
    LocalColumnType.decimal => _toDecimal(value),
    LocalColumnType.boolean => _toBool(value) ? 'Oui' : 'Non',
    LocalColumnType.date => _date(toWat(_toDateTime(value))),
    LocalColumnType.datetime => _dateTime(toWat(_toDateTime(value))),
  };
}

const _quote = 0x22; // "
const _comment = 0x23; // #
const _space = 0x20;
const _lf = 0x0A;
const _cr = 0x0D;
final _delim = csvDelimiter.codeUnitAt(0);

/// commons-csv `QuoteMode.MINIMAL`: whether [value] must be quoted.
bool _needsQuotes(String value, bool first) {
  if (value.isEmpty) return first;
  if (value.codeUnitAt(0) <= _comment) return true;
  for (var i = 0; i < value.length; i++) {
    final c = value.codeUnitAt(i);
    if (c == _lf || c == _cr || c == _quote || c == _delim) return true;
  }
  return value.codeUnitAt(value.length - 1) <= _space;
}

/// One CSV record of already formatted [cells], CRLF-terminated.
String csvRecord(List<String> cells) {
  final sb = StringBuffer();
  for (var i = 0; i < cells.length; i++) {
    if (i > 0) sb.write(csvDelimiter);
    final v = cells[i];
    if (_needsQuotes(v, i == 0)) {
      sb
        ..write('"')
        ..write(v.replaceAll('"', '""'))
        ..write('"');
    } else {
      sb.write(v);
    }
  }
  sb.write(csvRecordSeparator);
  return sb.toString();
}
