/// Translates a backend domain error code (the `reason` field on a REJECTED
/// sync operation result) into a short, user-facing French message.
///
/// The backend sends the raw `ErrorCode` enum name (see
/// `AbstractSyncOperationHandler.mapDomainException` on the Java side) —
/// this only covers the codes a queued offline operation can realistically
/// be rejected with. An unrecognised code or free text (an unmapped server
/// exception message, e.g. "Product not found: …") falls back to a generic
/// French message — never shown raw, never naming the code (v1s-16-20).
String friendlySyncRejectionReason(String? reasonCode) {
  return switch (reasonCode) {
    'PRODUCT_NAME_ALREADY_EXISTS' => 'Un produit avec ce nom existe déjà',
    'PLAN_LIMIT_EXCEEDED' =>
      'Limite de votre forfait atteinte — passez à un forfait supérieur',
    'CATEGORY_NOT_FOUND' => 'Catégorie introuvable',
    'PRODUCT_NOT_FOUND' => 'Produit introuvable',
    'CLIENT_NOT_FOUND' => 'Client introuvable',
    'SUPPLIER_NOT_FOUND' => 'Fournisseur introuvable',
    'STORE_NOT_FOUND' => 'Boutique introuvable',
    'STORE_NOT_ACTIVE' => 'Boutique désactivée',
    'INSUFFICIENT_STOCK' => 'Stock insuffisant',
    'STOCK_NOT_FOUND' => 'Stock introuvable',
    'SALE_NOT_FOUND' => 'Vente introuvable',
    'SALE_NOT_PENDING' => 'Cette vente n\'est plus en attente',
    'SALE_ALREADY_CANCELLED' => 'Vente déjà annulée',
    'DAY_ALREADY_CLOSED' => 'La journée est déjà clôturée',
    'DISCOUNT_EXCEEDS_SUBTOTAL' => 'Remise supérieure au montant de la vente',
    'JUSTIFICATION_REQUIRED' => 'Justification manquante',
    'JUSTIFICATION_TOO_SHORT' => 'Justification trop courte',
    'INVENTORY_SESSION_ALREADY_ACTIVE' =>
      'Un autre inventaire est déjà en cours dans cette boutique',
    'INVENTORY_STORE_NOT_FOUND' => 'Boutique de l\'inventaire introuvable',
    'INVENTORY_INVALID_CATEGORIES' => 'Catégories de l\'inventaire invalides',
    'INVENTORY_SESSION_NOT_IN_PROGRESS' => 'Cet inventaire n\'est plus en cours',
    'INVENTORY_SESSION_NOT_FOUND' => 'Inventaire introuvable sur le serveur',
    'OPTIMISTIC_LOCK' =>
      'Modifié en même temps sur un autre appareil — nouvel essai automatique',
    'INTERNAL_ERROR' ||
    'TRANSACTION_ROLLED_BACK' =>
      'Incident temporaire du serveur — nouvel essai automatique',
    'DATA_INTEGRITY_VIOLATION' =>
      'Données en conflit avec le serveur — nouvel essai automatique',
    'UNKNOWN_OPERATION_TYPE' =>
      'Action non prise en charge — mettez l\'application à jour',
    'SUBSCRIPTION_NOT_FOUND' => 'Abonnement introuvable',
    'TENANT_NOT_FOUND' => 'Compte de l\'entreprise introuvable',
    'WAREHOUSE_ALREADY_EXISTS' => 'Un seul entrepôt est autorisé par compte',
    'USER_ALREADY_EXISTS' => 'Un utilisateur avec ces identifiants existe déjà',
    'EMPLOYEE_NOT_FOUND' => 'Employé introuvable',
    'CANNOT_DEMOTE_LAST_OWNER' =>
      'Impossible de retirer le dernier propriétaire du compte',
    'VALIDATION_ERROR' || 'VALIDATION_FAILED' => 'Données invalides',
    'FORBIDDEN' => 'Action non autorisée',
    'UNAUTHORIZED' => 'Session expirée',
    null => 'Raison inconnue',
    String code when _errorCodePattern.hasMatch(code) =>
      'Refusé par le serveur',
    String text when text.toLowerCase().contains('not found') =>
      'Élément introuvable sur le serveur',
    _ => 'Données refusées par le serveur',
  };
}

/// A backend `ErrorCode` name (UPPER_SNAKE), as opposed to free text.
final _errorCodePattern = RegExp(r'^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$');

/// Translates a `sync_queue.operation` value into a short French label for
/// display in rejection notifications and the sync queue screen. An unknown
/// operation reads « Opération » — never the raw code (v1s-16-20).
String friendlySyncOperationLabel(String operation) {
  return switch (operation) {
    'CREATE_PRODUCT' => 'Création de produit',
    'CREATE_DRAFT_PRODUCT' => 'Création de produit brouillon',
    'UPDATE_PRODUCT' => 'Modification de produit',
    'ARCHIVE_PRODUCT' => 'Archivage de produit',
    'UNARCHIVE_PRODUCT' => 'Restauration de produit',
    'PROMOTE_PRODUCT' => 'Validation de produit brouillon',
    'CREATE_CATEGORY' => 'Création de catégorie',
    'RENAME_CATEGORY' => 'Renommage de catégorie',
    'TOGGLE_CATEGORY' => 'Activation/désactivation de catégorie',
    'CREATE_CLIENT' => 'Création de client',
    'UPDATE_CLIENT' => 'Modification de client',
    'ARCHIVE_CLIENT' => 'Archivage de client',
    'UNARCHIVE_CLIENT' => 'Désarchivage de client',
    'CREATE_SUPPLIER' => 'Création de fournisseur',
    'UPDATE_SUPPLIER' => 'Modification de fournisseur',
    'ARCHIVE_SUPPLIER' => 'Archivage de fournisseur',
    'UNARCHIVE_SUPPLIER' => 'Désarchivage de fournisseur',
    'CREATE_STORE' => 'Création de boutique',
    'UPDATE_STORE' => 'Modification de boutique',
    'DEACTIVATE_STORE' => 'Désactivation de boutique',
    'CREATE_SALE' => 'Enregistrement de vente',
    'VALIDATE_SALE' => 'Validation de vente',
    'CANCEL_SALE' => 'Annulation de vente',
    'CREATE_DAY_CLOSURE' => 'Clôture de journée',
    'STOCK_ADJUST' => 'Ajustement de stock',
    'RECORD_STOCK_ENTRY' => 'Entrée de stock',
    'STOCK_TRANSFER' => 'Transfert de stock',
    'CREATE_INVENTORY_SESSION' => 'Ouverture d\'inventaire',
    'SAVE_INVENTORY_COUNT' => 'Comptage d\'inventaire',
    'VALIDATE_INVENTORY' => 'Validation d\'inventaire',
    'REASSIGN_EMPLOYEE' => 'Réaffectation d\'employé',
    'DEACTIVATE_EMPLOYEE' => 'Désactivation d\'employé',
    'REACTIVATE_EMPLOYEE' => 'Réactivation d\'employé',
    'CREATE_EMPLOYEE' => 'Création d\'employé',
    'SUBMIT_FEEDBACK' => 'Envoi d\'un avis',
    _ => 'Opération',
  };
}
