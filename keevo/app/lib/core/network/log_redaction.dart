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
