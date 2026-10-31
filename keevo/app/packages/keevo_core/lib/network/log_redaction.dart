/// Redacts the `Authorization` header value from a formatted log line.
///
/// Used by LogInterceptor's logPrint callback to prevent Bearer tokens from
/// appearing in debug logs while preserving diagnostic value of other headers.
///
/// Example:
/// ```dart
/// // Input:  "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
/// // Output: "Authorization: Bearer [REDACTED]"
/// ```
///
/// Lines that do NOT contain 'Authorization' are returned unchanged.
String redactAuthorizationHeader(String line) {
  if (!line.contains('Authorization')) return line;
  return line.replaceAll(RegExp(r'Bearer\s+\S+'), 'Bearer [REDACTED]');
}

/// Body / query keys whose values must never reach a debug log.
const sensitiveLogKeys = <String>[
  'password',
  'newPassword',
  'currentPassword',
  'oldPassword',
  'code',
  'otp',
  'refreshToken',
  'loginToken',
  'accessToken',
  'token',
];

const _redacted = '[REDACTED]';
final String _keys = '(${sensitiveLogKeys.join('|')})';

// JSON string value: "password":"Jean2027!" (escaped quotes handled).
final _jsonStringValue =
    RegExp('"$_keys"' r'(\s*:\s*)"(?:[^"\\]|\\.)*"');
// JSON bare value: "code": 123456
final _jsonBareValue = RegExp('"$_keys"' r'(\s*:\s*)(?!")[^,}\]\s]+');
// Dart Map.toString(): {phoneNumber: +237…, password: Jean2027!}
final _mapValue = RegExp(r'(?<![\w"])' '$_keys' r'(:[ \t]*)[^,}\]\r\n]*');
// Query string: ?token=abc&x=1
final _queryValue = RegExp(r'(?<![\w])' '$_keys' r'=[^&\s]*');

/// Replaces the values of [sensitiveLogKeys] in a formatted log line (JSON
/// bodies, Dart map dumps such as `{phoneNumber: …, password: …}`, query
/// strings) so passwords, OTP codes and tokens never appear in clear.
String redactSensitiveFields(String line) {
  return line
      .replaceAllMapped(
          _jsonStringValue, (m) => '"${m[1]}"${m[2]}"$_redacted"')
      .replaceAllMapped(_jsonBareValue, (m) => '"${m[1]}"${m[2]}$_redacted')
      .replaceAllMapped(_mapValue, (m) => '${m[1]}${m[2]}$_redacted')
      .replaceAllMapped(_queryValue, (m) => '${m[1]}=$_redacted');
}

/// Redaction applied to every Dio debug log line: the Authorization header
/// plus the sensitive body / query fields.
String redactLogLine(String line) =>
    redactSensitiveFields(redactAuthorizationHeader(line));
