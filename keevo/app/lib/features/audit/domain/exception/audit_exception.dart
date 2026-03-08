/// AuditException — domain exception for audit-related errors.
///
/// Thrown by [RemoteAuditDataSource] and propagated through [AuditRepositoryImpl].
/// The [domainCode] matches the backend `domainCode` field (e.g. AUDIT_IMMUTABLE, UNAUTHORIZED).
class AuditException implements Exception {
  /// Backend domain error code (e.g. "AUDIT_IMMUTABLE", "UNAUTHORIZED").
  final String domainCode;

  /// Human-readable French message for display.
  final String message;

  /// HTTP status code (401, 403, 500, etc.) — null if network error.
  final int? statusCode;

  const AuditException({
    required this.domainCode,
    required this.message,
    this.statusCode,
  });

  @override
  String toString() =>
      'AuditException(domainCode: $domainCode, statusCode: $statusCode, message: $message)';
}
