/// StoreException — domain exceptions for the stores feature (Story 3.1).
class StoreException implements Exception {
  final String domainCode;
  final String message;

  const StoreException({required this.domainCode, required this.message});

  factory StoreException.notFound() => const StoreException(
        domainCode: 'STORE_NOT_FOUND',
        message: 'Boutique introuvable',
      );

  factory StoreException.planLimitExceeded() => const StoreException(
        domainCode: 'PLAN_LIMIT_EXCEEDED',
        message: 'Limite du plan atteinte. Passez à un plan supérieur pour créer plus de boutiques.',
      );

  factory StoreException.warehouseAlreadyExists() => const StoreException(
        domainCode: 'WAREHOUSE_ALREADY_EXISTS',
        message: 'Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte.',
      );

  factory StoreException.unknown(String message) => StoreException(
        domainCode: 'UNKNOWN',
        message: message,
      );

  @override
  String toString() => 'StoreException[$domainCode]: $message';
}
