import 'package:freezed_annotation/freezed_annotation.dart';

part 'csv_import_result.freezed.dart';
part 'csv_import_result.g.dart';

/// CsvImportResult — result of a CSV bulk import (AC2, AC3).
///
/// Maps to [ImportResultResponseDto] from the backend. The JSON keys are the
/// backend's (`limitMessage`, `lineNumber`): with the former Dart names, any
/// file with a skipped row failed to parse and a successful import was shown
/// as an error (found on device).
@freezed
abstract class CsvImportResult with _$CsvImportResult {
  const factory CsvImportResult({
    required int imported,
    required int skipped,
    @Default([]) List<CsvRowError> errors,
    @Default(false) bool limitReached,
    @JsonKey(name: 'limitMessage') String? message,
  }) = _CsvImportResult;

  factory CsvImportResult.fromJson(Map<String, dynamic> json) =>
      _$CsvImportResultFromJson(json);
}

/// CsvRowError — validation error for a single CSV row.
@freezed
abstract class CsvRowError with _$CsvRowError {
  const factory CsvRowError({
    @JsonKey(name: 'lineNumber') required int line,
    required String column,
    required String message,
  }) = _CsvRowError;

  factory CsvRowError.fromJson(Map<String, dynamic> json) =>
      _$CsvRowErrorFromJson(json);
}
