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
        message:
            'Limite du plan atteinte. Passez à un plan supérieur pour créer plus de boutiques.',
      );

  factory StoreException.warehouseAlreadyExists() => const StoreException(
        domainCode: 'WAREHOUSE_ALREADY_EXISTS',
        message:
            'Vous avez déjà un entrepôt. Un seul entrepôt est autorisé par compte.',
      );

  /// La réactivation re-vérifie la limite du forfait côté serveur : elle
  /// n'est pas mise en file d'attente hors connexion.
  factory StoreException.reactivationNeedsNetwork() => const StoreException(
        domainCode: 'OFFLINE',
        message:
            'Connexion internet requise pour réactiver une boutique. Réessayez une fois connecté.',
      );

  factory StoreException.unknown(String message) => StoreException(
        domainCode: 'UNKNOWN',
        message: message,
      );

  @override
  String toString() => 'StoreException[$domainCode]: $message';
}
