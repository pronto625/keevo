/// Translates a backend domain error code (the `reason` field on a REJECTED
/// sync operation result) into a short, user-facing French message.
///
/// The backend sends the raw `ErrorCode` enum name (see
/// `AbstractSyncOperationHandler.mapDomainException` on the Java side) —
/// this only covers the codes a queued offline operation can realistically
/// be rejected with. Unrecognised codes fall back to a generic message that
/// still includes the raw code, so nothing is silently swallowed.
String friendlySyncRejectionReason(String? reasonCode) {
  return switch (reasonCode) {
    'PRODUCT_NAME_ALREADY_EXISTS' => 'Un produit avec ce nom existe déjà',
    'PLAN_LIMIT_EXCEEDED' => 'Limite du plan atteinte',
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
    'VALIDATION_ERROR' || 'VALIDATION_FAILED' => 'Données invalides',
    'FORBIDDEN' => 'Action non autorisée',
    'UNAUTHORIZED' => 'Session expirée',
    null => 'Raison inconnue',
    _ => 'Erreur : $reasonCode',
  };
}

/// Translates a `sync_queue.operation` value into a short French label for
/// display in rejection notifications and the sync queue screen.
String friendlySyncOperationLabel(String operation) {
  return switch (operation) {
    'CREATE_PRODUCT' => 'Création de produit',
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
    'CREATE_SUPPLIER' => 'Création de fournisseur',
    'UPDATE_SUPPLIER' => 'Modification de fournisseur',
    'ARCHIVE_SUPPLIER' => 'Archivage de fournisseur',
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
    _ => operation,
  };
}
