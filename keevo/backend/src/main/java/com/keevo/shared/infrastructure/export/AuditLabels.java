package com.keevo.shared.infrastructure.export;

import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * AuditLabels — the French table of the action history (Story v1s-16-20), shared by the server
 * {@code audit_log} export and the app « Historique des actions » through the export contract
 * ({@code keevo/contracts/export-datasets.json}: {@code labels["audit_log.action"]},
 * {@code labels["audit_log.entityType"]} and the {@code audit} section). The app
 * {@code AuditPresenter} maps must stay equal to these (contract tests on both sides).
 *
 * <p>Codes, field keys and class-like object types are never written raw: an unknown one is
 * humanized ({@link ExportValues#humanize(String)}).
 */
public final class AuditLabels {

    private AuditLabels() {
    }

    /** Short French title of every action written by {@code AuditEventListener} (+ app-only codes). */
    public static final Map<String, String> ACTIONS = Map.ofEntries(
            // Account / identity
            entry("USER_REGISTERED", "Inscription"),
            entry("USER_AUTHENTICATED", "Connexion"),
            entry("PASSWORD_RESET_REQUESTED", "Réinitialisation demandée"),
            entry("PASSWORD_RESET", "Mot de passe réinitialisé"),
            entry("ONBOARDING_COMPLETED", "Configuration terminée"),
            entry("ACCOUNT_DELETION_REQUESTED", "Suppression du compte demandée"),
            entry("ACCOUNT_DELETION_CANCELLED", "Suppression du compte annulée"),
            entry("REPORT_PREFERENCES_UPDATED", "Préférences des rapports modifiées"),
            // Products
            entry("PRODUCT_CREATED", "Produit créé"),
            entry("PRODUCT_UPDATED", "Produit modifié"),
            entry("PRODUCT_PHOTO_UPDATED", "Photo du produit modifiée"),
            entry("PRODUCT_ARCHIVED", "Produit archivé"),
            entry("PRODUCT_UNARCHIVED", "Produit désarchivé"),
            entry("PRODUCT_ACTIVATED", "Produit activé"),
            entry("PRICE_OVERRIDDEN", "Prix modifié en caisse"),
            // Stock
            entry("STOCK_ADJUSTED", "Mouvement de stock"),
            entry("STOCK_THRESHOLD_UPDATED", "Seuil d'alerte modifié"),
            entry("STOCK_THRESHOLD_BREACHED", "Alerte de stock bas"),
            entry("STOCK_FORCED_ZERO", "Stock forcé à zéro"),
            // Contacts
            entry("CLIENT_CREATED", "Client créé"),
            entry("CLIENT_ARCHIVED", "Client archivé"),
            entry("CLIENT_UNARCHIVED", "Client désarchivé"),
            entry("SUPPLIER_CREATED", "Fournisseur créé"),
            entry("SUPPLIER_ARCHIVED", "Fournisseur archivé"),
            entry("SUPPLIER_UPDATED", "Fournisseur modifié"),
            entry("SUPPLIER_PRODUCTS_UPDATED", "Produits du fournisseur modifiés"),
            entry("SUPPLIER_UNARCHIVED", "Fournisseur désarchivé"),
            // Categories
            entry("CATEGORY_CREATED", "Catégorie créée"),
            entry("CATEGORY_RENAMED", "Catégorie renommée"),
            entry("CATEGORY_ACTIVATED", "Catégorie activée"),
            entry("CATEGORY_DEACTIVATED", "Catégorie désactivée"),
            // Stores
            entry("STORE_CREATED", "Boutique créée"),
            entry("STORE_UPDATED", "Boutique modifiée"),
            entry("STORE_DEACTIVATED", "Boutique désactivée"),
            entry("STORE_REACTIVATED", "Boutique réactivée"),
            // Team
            entry("EMPLOYEE_CREATED", "Employé ajouté"),
            entry("EMPLOYEE_UPDATED", "Employé modifié"),
            entry("EMPLOYEE_ROLE_CHANGED", "Rôle modifié"),
            entry("EMPLOYEE_PASSWORD_SET_BY_OWNER", "Mot de passe défini"),
            // Sales
            entry("SALE_COMPLETED", "Vente enregistrée"),
            entry("SALE_PENDING_VALIDATION", "Vente en attente"),
            entry("SALE_AUTO_VALIDATED", "Vente validée automatiquement"),
            entry("SALE_MANUALLY_VALIDATED", "Vente validée"),
            entry("SALE_CANCELLED", "Vente annulée"),
            entry("SALE_CORRECTED", "Vente corrigée"),
            // Closures / inventory
            entry("DAY_CLOSED", "Journée clôturée"),
            entry("INVENTORY_SESSION_CREATED", "Inventaire lancé"),
            entry("INVENTORY_SESSION_CANCELLED", "Inventaire annulé"),
            entry("INVENTORY_COUNT_SAVED", "Comptage enregistré"),
            entry("INVENTORY_REPORT_GENERATED", "Rapport d'inventaire"),
            entry("INVENTORY_VALIDATED", "Inventaire validé"),
            // Export
            entry("DATA_EXPORTED", "Données exportées"),
            // Modules (Story 13.8a: super-admin activation / deactivation)
            entry("MODULE_ACTIVATED", "Module activé"),
            entry("MODULE_DEACTIVATED", "Module désactivé"),
            // Invoicing (Story 14.1)
            entry("INVOICE_CREATED", "Facture créée"),
            entry("INVOICE_ISSUED", "Facture émise"),
            entry("INVOICE_PAID", "Facture payée"),
            entry("INVOICE_CANCELLED", "Facture annulée"),
            entry("INVOICING_SETTINGS_UPDATED", "Paramètres de facturation modifiés"),
            entry("INVOICE_SENT", "Facture envoyée"),
            // Credit notes (Story 14.7)
            entry("CREDIT_NOTE_ISSUED", "Avoir émis"),
            entry("COMPANY_PROFILE_UPDATED", "Identité de l'entreprise modifiée"));

    /** Singular French noun of each audited object type ({@code entityType}). */
    public static final Map<String, String> ENTITY_TYPES = Map.ofEntries(
            entry("Product", "Produit"),
            entry("Sale", "Vente"),
            entry("StockMovement", "Mouvement de stock"),
            entry("StockAlert", "Alerte de stock"),
            entry("StockLevel", "Niveau de stock"),
            entry("PriceOverride", "Prix de vente"),
            entry("Client", "Client"),
            entry("Supplier", "Fournisseur"),
            entry("Category", "Catégorie"),
            entry("Store", "Boutique"),
            entry("Employee", "Employé"),
            entry("User", "Compte utilisateur"),
            entry("Tenant", "Compte"),
            entry("DayClosure", "Clôture de journée"),
            entry("InventorySession", "Inventaire"),
            entry("InventoryCount", "Comptage d'inventaire"),
            entry("Export", "Export de données"),
            entry("Module", "Module"),
            entry("Invoice", "Facture"),
            entry("InvoicingSettings", "Paramètres de facturation"),
            entry("CreditNote", "Avoir"));

    /** French label of each before/after field key. */
    public static final Map<String, String> FIELDS = Map.ofEntries(
            entry("productName", "Produit"),
            entry("productSku", "Référence"),
            entry("sku", "Référence"),
            entry("name", "Nom"),
            entry("newName", "Nouveau nom"),
            entry("firstName", "Prénom"),
            entry("lastName", "Nom de famille"),
            entry("phoneNumber", "Téléphone"),
            entry("storeId", "Boutique"),
            entry("type", "Type"),
            entry("archived", "Archivé"),
            entry("active", "Active"),
            entry("activated", "Activée"),
            entry("deactivated", "Désactivée"),
            entry("status", "Statut"),
            entry("totalAmount", "Montant total"),
            entry("discountAmount", "Remise"),
            entry("appliedPrice", "Prix appliqué"),
            entry("cataloguePrice", "Prix catalogue"),
            entry("totalRevenue", "Chiffre d'affaires"),
            entry("totalSales", "Nombre de ventes"),
            entry("price", "Prix"),
            entry("photo", "Photo"),
            entry("sellingPrice", "Prix de vente"),
            entry("purchasePrice", "Prix d'achat"),
            entry("buyPrice", "Prix d'achat"),
            entry("costPrice", "Prix de revient"),
            entry("unitPrice", "Prix unitaire"),
            entry("transportCost", "Frais de transport"),
            entry("quantity", "Quantité"),
            entry("quantityBefore", "Quantité avant"),
            entry("quantityChange", "Variation"),
            entry("quantityAfter", "Quantité après"),
            entry("movementType", "Type de mouvement"),
            entry("currentQuantity", "Quantité actuelle"),
            entry("threshold", "Seuil d'alerte"),
            entry("lowStockThreshold", "Seuil d'alerte"),
            entry("requestedQuantity", "Quantité demandée"),
            entry("availableQuantity", "Quantité disponible"),
            entry("theoretical", "Stock théorique"),
            entry("physical", "Stock compté"),
            entry("ecart", "Écart"),
            entry("adjustmentCount", "Ajustements"),
            entry("role", "Rôle"),
            entry("fieldsChanged", "Champs modifiés"),
            entry("forcedReset", "Changement de mot de passe imposé"),
            entry("itemsSnapshot", "Articles"),
            entry("scheduledDeletionAt", "Suppression prévue le"),
            entry("cancelledAt", "Annulée le"),
            entry("createdAt", "Créé le"),
            entry("updatedAt", "Modifié le"),
            entry("storeName", "Boutique"),
            entry("sectorType", "Secteur"),
            entry("categoriesCreated", "Catégories créées"),
            entry("tenantCode", "Code du compte"),
            entry("description", "Description"),
            entry("barcode", "Code-barres"),
            entry("unit", "Unité"),
            entry("format", "Format"),
            entry("rowCount", "Nombre de lignes"),
            entry("from", "Du"),
            entry("to", "Au"),
            entry("mode", "Mode"),
            entry("phone", "Téléphone"),
            entry("email", "E-mail"),
            entry("productCount", "Nombre de produits"),
            entry("productsAdded", "Produits ajoutés"),
            entry("productsRemoved", "Produits retirés"),
            entry("eodReportEnabled", "Rapport de fin de journée"),
            entry("eodReportTime", "Heure du rapport de fin de journée"),
            entry("eodReportChannel", "Envoi du rapport de fin de journée"),
            entry("weeklyReportEnabled", "Rapport hebdomadaire"),
            entry("weeklyReportDay", "Jour du rapport hebdomadaire"),
            entry("weeklyReportTime", "Heure du rapport hebdomadaire"),
            entry("weeklyReportChannel", "Envoi du rapport hebdomadaire"),
            entry("inventoryReportEnabled", "Rapport d'inventaire"),
            entry("inventoryReportChannel", "Envoi du rapport d'inventaire"),
            entry("stockAlertEnabled", "Alertes de stock bas"),
            entry("stockAlertChannel", "Envoi des alertes de stock"),
            entry("trendNotificationEnabled", "Notifications de tendance"),
            // Modules (Story 13.8a, ModuleLifecycleService audit payload)
            entry("module", "Module"),
            entry("statut", "Statut"),
            entry("finAbonnement", "Fin de l'abonnement"),
            entry("entreprise", "Entreprise"),
            entry("donnees", "Données"),
            // Invoicing (Story 14.1)
            entry("invoiceNumber", "Numéro de facture"),
            entry("clientName", "Client"),
            entry("paymentMethod", "Mode de paiement"),
            // Invoice sending, company identity (Story 14.2)
            entry("sentTo", "Envoyée au"),
            entry("designation", "Désignation"),
            entry("logo", "Logo"),
            // Credit notes (Story 14.7)
            entry("creditNoteNumber", "Numéro d'avoir"));

    /** French label of each code value (statuses, types, roles, sectors, export modes…). */
    public static final Map<String, String> VALUES = Map.ofEntries(
            entry("DRAFT", "Brouillon"),
            entry("ACTIVE", "Actif"),
            entry("INACTIVE", "Inactif"),
            entry("PENDING_VALIDATION", "En attente de validation"),
            entry("COMPLETED", "Terminé(e)"),
            entry("CANCELLED", "Annulé(e)"),
            entry("VALIDATED", "Validé"),
            entry("IN_PROGRESS", "En cours"),
            entry("IN_TRANSIT", "En transit"),
            entry("PENDING_SYNC", "En attente de synchronisation"),
            entry("CONFLICT", "En conflit"),
            entry("SALE", "Vente"),
            entry("PURCHASE", "Réception"),
            entry("STOCK_ENTRY", "Entrée de stock"),
            entry("ADJUSTMENT", "Ajustement"),
            entry("TRANSFER_OUT", "Transfert (sortie)"),
            entry("TRANSFER_IN", "Transfert (entrée)"),
            entry("SALE_CANCELLED", "Annulation de vente"),
            entry("INVENTORY", "Inventaire"),
            entry("CASH", "Espèces"),
            entry("MOBILE_MONEY", "Mobile Money"),
            entry("OWNER", "Propriétaire"),
            entry("EMPLOYEE", "Employé"),
            entry("MANAGER", "Gérant"),
            entry("STORE", "Boutique"),
            entry("WAREHOUSE", "Entrepôt"),
            entry("FULL", "Complet"),
            entry("CATEGORY", "Par catégorie"),
            entry("PARTIAL", "Partiel"),
            // Sectors (identity/onboarding SectorType)
            entry("CLOTHING", "Vêtements & Shopping"),
            entry("ELECTRONICS", "Électronique & Smartphones"),
            entry("BOOKS_STATIONERY", "Librairie & Fournitures scolaires"),
            entry("HOME_APPLIANCES", "Électroménager & Cuisine"),
            entry("FOOD_GROCERY", "Alimentation"),
            entry("PHARMACY", "Pharmacie"),
            entry("HARDWARE", "Quincaillerie"),
            entry("OTHER", "Autre"),
            // Export modes (DataExportedEvent.MODE_*)
            entry("DIRECT", "Téléchargement direct"),
            entry("DIRECT_INCOMPLETE", "Téléchargement interrompu"),
            entry("BACKGROUND", "Préparé en arrière-plan"));

    /** French label of each context key (metadata, or the context keys of legacy value_after). */
    public static final Map<String, String> CONTEXT = Map.ofEntries(
            entry("justification", "Justification"),
            entry("notes", "Motif"),
            entry("note", "Motif"),
            entry("motif", "Motif"),
            entry("reason", "Motif"),
            entry("scope", "Périmètre"),
            entry("isAutomatic", "Déclenchement"),
            entry("forcedProducts", "Produits forcés"),
            // v1s-16-23: other store of a transfer
            entry("destinationStoreName", "Vers"),
            entry("sourceStoreName", "Depuis"),
            // Story 14.7: the invoice a credit note is issued on (« sur la facture FAC-… »)
            entry("detail", "Détail"));

    /**
     * Keys never shown (technical or pure context ids). Any key named {@code id} or ending with
     * {@code Id} / {@code Ids} is hidden too ({@link AuditChangesFormatter#isHiddenKey(String)}).
     */
    public static final Set<String> HIDDEN_KEYS = Set.of(
            "actorLabel",
            "occurredAt",
            "cancelledBy",
            "schemaName",
            "ip",
            "tenantId",
            "datasets",
            "criteria");

    /** Keys whose string values are codes (translated through {@link #VALUES}, else humanized). */
    public static final Set<String> CODE_KEYS = Set.of(
            "status", "type", "movementType", "role", "scope", "source", "previousRole", "newRole",
            "storeType", "sectorType", "mode");
}
