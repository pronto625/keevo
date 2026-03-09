/// ProductException — typed errors for product CRUD operations.
///
/// Maps HTTP status codes + domain error codes from the backend API.
class ProductException implements Exception {
  final String domainCode;
  final String message;
  final int? statusCode;

  const ProductException({
    required this.domainCode,
    required this.message,
    this.statusCode,
  });

  @override
  String toString() =>
      'ProductException($domainCode, $statusCode): $message';
}
