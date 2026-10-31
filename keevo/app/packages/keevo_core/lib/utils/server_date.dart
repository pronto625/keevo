import 'package:json_annotation/json_annotation.dart';

/// Parses a backend ISO-8601 timestamp (usually UTC, ending in `Z`) and
/// returns it in the device's local time, so the UI never shows UTC hours.
///
/// Strings without an offset are already local for Dart; `toLocal()` is then
/// a no-op, so date-only values (`yyyy-MM-dd`) never shift a day.
DateTime parseServerDate(String raw) => DateTime.parse(raw).toLocal();

/// Lenient variant of [parseServerDate]: returns null for null, empty or
/// malformed input.
DateTime? tryParseServerDate(String? raw) {
  if (raw == null || raw.isEmpty) return null;
  return DateTime.tryParse(raw)?.toLocal();
}

/// json_serializable converter for backend timestamps: local time on read,
/// UTC ISO-8601 on write so the instant sent back to the server is unchanged.
class ServerDateTimeConverter implements JsonConverter<DateTime, String> {
  const ServerDateTimeConverter();

  @override
  DateTime fromJson(String json) => parseServerDate(json);

  @override
  String toJson(DateTime object) => object.toUtc().toIso8601String();
}

/// Nullable counterpart of [ServerDateTimeConverter].
class NullableServerDateTimeConverter
    implements JsonConverter<DateTime?, String?> {
  const NullableServerDateTimeConverter();

  @override
  DateTime? fromJson(String? json) =>
      json == null ? null : parseServerDate(json);

  @override
  String? toJson(DateTime? object) => object?.toUtc().toIso8601String();
}
