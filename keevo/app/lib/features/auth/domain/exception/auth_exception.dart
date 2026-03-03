/// AuthException — Domain exception for authentication errors.
///
/// Lives in the domain layer so that both data and presentation layers
/// can reference it without violating architectural boundaries.
class AuthException implements Exception {
  final String domainCode;
  final String message;

  const AuthException({required this.domainCode, required this.message});

  @override
  String toString() => 'AuthException[$domainCode]: $message';
}
