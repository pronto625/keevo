/// ContactException — domain exceptions for the contact feature (Story 2.5).
class ContactException implements Exception {
  final String domainCode;
  final String message;

  const ContactException({required this.domainCode, required this.message});

  factory ContactException.clientNotFound() => const ContactException(
        domainCode: 'CLIENT_NOT_FOUND',
        message: 'Client introuvable',
      );

  factory ContactException.supplierNotFound() => const ContactException(
        domainCode: 'SUPPLIER_NOT_FOUND',
        message: 'Fournisseur introuvable',
      );

  factory ContactException.unknown(String message) => ContactException(
        domainCode: 'UNKNOWN',
        message: message,
      );

  @override
  String toString() => 'ContactException[$domainCode]: $message';
}
