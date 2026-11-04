package com.keevo.shared.infrastructure.web;

import java.util.Map;

/**
 * ErrorMessages — the single French catalogue of the {@code error} text of every domain code (Story 16.14).
 *
 * <p>Used by {@link GlobalExceptionHandler} (controller errors) and by the authentication filter
 * ({@code JwtAuthFilter}, errors raised before any controller) so both answer the same French text. Every
 * {@code ErrorCode} constant has an entry (guard test). NEVER expose technical details — all messages are
 * human-readable French; {@code {key}} placeholders are filled from the exception details.
 */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    /** French user-facing error messages mapped by domain code. */
    static final Map<String, String> FR_MESSAGES = Map.ofEntries(
            Map.entry("USER_NOT_FOUND",           "Utilisateur introuvable"),
            Map.entry("USER_ALREADY_EXISTS",       "Ce numéro est déjà associé à un compte"),
            Map.entry("INVALID_CREDENTIALS",       "Téléphone ou mot de passe incorrect"),
            Map.entry("ACCOUNT_LOCKED",            "Compte temporairement bloqué, réessayez dans 15 minutes"),
            Map.entry("TOKEN_EXPIRED",             "Session expirée, veuillez vous reconnecter"),
            Map.entry("REFRESH_TOKEN_INVALID",     "Session expirée, veuillez vous reconnecter"),
            Map.entry("TOKEN_INVALID",             "Jeton d'authentification invalide"),
            Map.entry("PHONE_COUNTRY_NOT_SUPPORTED", "Ce pays n'est pas encore pris en charge"),
            Map.entry("OTP_INVALID",               "Code incorrect ou expiré"),
            Map.entry("OTP_TOO_MANY_ATTEMPTS",     "Trop d'essais. Demandez un nouveau code"),
            Map.entry("OTP_TOO_MANY_REQUESTS",     "Trop de demandes de code. Réessayez plus tard"),
            Map.entry("PUSH_ENDPOINT_OWNED",       "Cet appareil est déjà lié à un autre compte"),
            Map.entry("COMPANY_ALREADY_EXISTS",    "Vous avez déjà une entreprise"),
            Map.entry("UNAUTHORIZED",              "Accès non autorisé"),
            Map.entry("FORBIDDEN",                 "Vous n'avez pas les droits nécessaires"),
            Map.entry("TENANT_NOT_FOUND",          "Espace de travail introuvable"),
            Map.entry("PLAN_LIMIT_EXCEEDED",       "Limite de votre plan atteinte"),
            Map.entry("QUOTA_EXCEEDED",            "Quota de votre offre atteint"),
            Map.entry("PRODUCT_QUOTA_UNAVAILABLE", "Impossible de vérifier votre quota de produits pour le moment. Réessayez dans quelques instants."),
            Map.entry("PLAN_PROFILE_INVALID",      "Profil non enregistré : {reason}"),
            Map.entry("RATE_LIMIT_EXCEEDED",       "Trop de requêtes, veuillez patienter"),
            Map.entry("AUDIT_IMMUTABLE",           "Les entrées du journal d'audit ne peuvent pas être modifiées"),
            Map.entry("AUDIT_ENTRY_NOT_FOUND",     "Entrée du journal d'audit introuvable"),
            Map.entry("INSUFFICIENT_STOCK",        "Stock insuffisant pour cette opération"),
            Map.entry("STOCK_NOT_FOUND",           "Niveau de stock introuvable"),
            Map.entry("STOCK_NOT_TRACKED",         "Le suivi du stock est désactivé pour ce produit"),
            Map.entry("CLIENT_NOT_FOUND",          "Client introuvable"),
            Map.entry("SUPPLIER_NOT_FOUND",        "Fournisseur introuvable"),
            Map.entry("SUBSCRIPTION_NOT_FOUND",    "Aucun abonnement actif trouvé"),
            Map.entry("PRODUCT_NAME_ALREADY_EXISTS", "Un produit avec ce nom existe déjà dans votre catalogue"),
            Map.entry("WAREHOUSE_ALREADY_EXISTS",   "Vous avez déjà un entrepôt. Un seul entrepôt est autorisé par compte."),
            Map.entry("STORE_NOT_FOUND",             "Boutique introuvable"),
            Map.entry("PRODUCT_NOT_FOUND",           "Produit introuvable"),
            Map.entry("PRODUCT_PHOTO_TOO_LARGE",     "La photo est trop lourde (200 Ko maximum)"),
            Map.entry("PRODUCT_PHOTO_INVALID",       "Format de photo non pris en charge (JPEG, PNG ou WebP)"),
            Map.entry("PRODUCT_PHOTO_NOT_FOUND",     "Ce produit n'a pas de photo"),
            Map.entry("STORE_NOT_ACTIVE",           "Cette boutique est désactivée"),
            Map.entry("SAME_SOURCE_DESTINATION",    "Source et destination doivent être différentes"),
            Map.entry("CSV_PARSE_ERROR",           "Erreur de lecture du fichier CSV"),
            Map.entry("EMPLOYEE_NOT_FOUND",        "Employé introuvable"),
            Map.entry("DEVICE_SESSION_NOT_FOUND",  "Cet appareil n'est plus connecté"),
            Map.entry("PHONE_ALREADY_REGISTERED",  "Ce numéro de téléphone est déjà utilisé par un autre compte"),
            Map.entry("CANNOT_CHANGE_OWN_ROLE",    "Vous ne pouvez pas modifier votre propre rôle"),
            Map.entry("CANNOT_DEMOTE_LAST_OWNER",  "Impossible de retirer le dernier propriétaire actif du compte"), // demotion AND deactivation (v1s-13-9)
            Map.entry("CANNOT_SET_OWN_PASSWORD",   "Vous ne pouvez pas définir votre propre mot de passe ici : utilisez « Changer mon mot de passe »"),
            Map.entry("PASSWORD_CHANGE_REQUIRED",  "Vous devez changer votre mot de passe avant de continuer"),
            Map.entry("STORE_REASSIGNED",          "Votre boutique a été modifiée, veuillez vous reconnecter"),
            Map.entry("ACCOUNT_INACTIVE",          "Ce compte a été désactivé"),
            Map.entry("SESSION_REVOKED",           "Votre session a été révoquée, veuillez vous reconnecter"),
            Map.entry("SALE_NOT_FOUND",            "Vente introuvable"),
            Map.entry("SALE_ALREADY_EXISTS",       "Cette vente a déjà été enregistrée"),
            Map.entry("VALIDATION_FAILED",         "Données invalides"),
            Map.entry("VALIDATION_ERROR",          "Données invalides"),
            Map.entry("DISCOUNT_EXCEEDS_SUBTOTAL","La réduction dépasse le sous-total"),
            Map.entry("SALE_NOT_PENDING",          "Cette vente n'est pas en attente de validation"),
            Map.entry("JUSTIFICATION_REQUIRED",    "Une justification est requise"),
            Map.entry("JUSTIFICATION_TOO_SHORT",   "La justification doit contenir au moins 10 caractères"),
            Map.entry("SALE_ALREADY_CANCELLED",    "Cette vente a déjà été annulée"),
            Map.entry("SALE_NOT_COMPLETED",        "Seule une vente complétée peut être corrigée"),
            Map.entry("INTERNAL_ERROR",            "Une erreur inattendue s'est produite"),
            Map.entry("DAY_ALREADY_CLOSED",        "La journée a déjà été clôturée pour cette boutique"),
            Map.entry("MISSING_PARAMETER",         "Paramètre requis manquant"),
            Map.entry("INVALID_OR_EXPIRED_CODE", "Code invalide ou expiré"),
            Map.entry("CODE_LOCKED",             "Trop de tentatives, demandez un nouveau code"),
            Map.entry("SYNC_REQUIRED",              "Synchronisation requise — données trop anciennes"),
            Map.entry("DEVICE_ID_MISMATCH",       "Cet appareil n'est pas associé à votre compte"),
            Map.entry("MODULE_INACTIVE",          "Module non activé pour votre compte"),
            Map.entry("MODULE_NOT_ENTITLED",      "Ce module n'est pas inclus dans votre abonnement"),
            Map.entry("MODULE_READ_ONLY",         "Ce module est en lecture seule : abonnement expiré"),
            Map.entry("MODULE_NOT_FOUND",         "Module introuvable"),
            Map.entry("MODULE_ACTIVATION_BLOCKED", "Activation impossible : {reasons}"),
            Map.entry("MODULE_HAS_DEPENDENTS",    "Désactivation impossible : ce module est utilisé par {dependents}"),
            Map.entry("CORE_MODULE_REQUIRED",     "Ce module fait partie du socle et ne peut pas être désactivé"),
            Map.entry("MODULE_PROVISIONING_FAILED", "Le module n'a pas été activé : la création de ses tables a échoué. Vous pouvez réessayer."),
            Map.entry("MODULE_ACTIVATION_FAILED", "Le module n'a pas été activé : sa mise en service a échoué. Vous pouvez réessayer."),
            Map.entry("MODULE_DEACTIVATION_FAILED", "Le module n'a pas été désactivé : sa mise hors service a échoué. Vous pouvez réessayer."),
            Map.entry("MODULE_BACKFILL_RUNNING", "Une migration des modules est déjà en cours. Réessayez dans quelques minutes."),
            Map.entry("BUNDLE_NOT_FOUND",         "Pack introuvable"),
            Map.entry("ADMIN_JOB_RUNNING",        "« {label} » est déjà en cours. Attendez la fin de la tâche avant d'en relancer une."),
            Map.entry("ADMIN_JOB_NOT_FOUND",      "Tâche introuvable"),
            Map.entry("BUNDLE_INVALID",           "Pack invalide : {reason}"),
            Map.entry("BUNDLE_NOT_APPLICABLE",    "Le pack « {name} » contient un module qui n'est plus disponible. Modifiez-le avant de l'appliquer."),
            Map.entry("INVENTORY_SESSION_ALREADY_ACTIVE", "Un inventaire est déjà en cours pour cette boutique"),
            Map.entry("INVENTORY_SESSION_NOT_FOUND",     "Session d'inventaire introuvable"),
            Map.entry("INVENTORY_SESSION_NOT_IN_PROGRESS", "Cette session d'inventaire n'est pas en cours"),
            Map.entry("INVENTORY_STORE_NOT_FOUND",       "Boutique cible introuvable"),
            Map.entry("INVENTORY_INVALID_CATEGORIES",    "Une ou plusieurs catégories sont invalides"),
            Map.entry("ACCOUNT_DELETION_PENDING",  "Suppression de compte en cours — écriture bloquée"),
            Map.entry("DELETION_ALREADY_REQUESTED", "Une demande de suppression est déjà en cours"),
            Map.entry("DELETION_NOT_PENDING",       "Aucune demande de suppression en cours"),
            Map.entry("SERVICE_UNAVAILABLE",        "Service momentanément indisponible, réessayez dans un instant"),
            Map.entry("INVALID_STATE",              "Action impossible dans l'état actuel du compte"),
            Map.entry("WHATSAPP_DELIVERY_FAILED",         "Envoi WhatsApp échoué. Vérifiez votre connexion et réessayez."),
            Map.entry("PLAN_FEATURE_REQUIRED",       "Cette fonctionnalité nécessite l'offre Premium"),
            Map.entry("EXPORT_NOT_FOUND",            "Export introuvable"),
            Map.entry("EXPORT_EXPIRED",              "Ce fichier d'export a expiré, relancez l'export"),
            Map.entry("EXPORT_NOT_READY",            "Cet export n'est pas encore prêt"),
            Map.entry("EXPORT_DATASET_UNKNOWN",      "Jeu de données inconnu"),
            Map.entry("EXPORT_FORMAT_UNSUPPORTED",   "Ce format d'export n'est pas encore disponible"),
            Map.entry("EXPORT_LINK_INVALID",         "Lien de téléchargement invalide"),
            Map.entry("EXPORT_QUEUE_FULL",           "Trop d'exports en cours, réessayez dans quelques minutes"),
            Map.entry("EXPORT_PDF_TOO_LARGE",        "Ce PDF dépasserait la limite de {limit} {unit} : réduisez la période, choisissez une boutique ou exportez en Excel"),
            Map.entry("INVOICE_NOT_FOUND",           "Facture introuvable"),
            Map.entry("INVOICE_NOT_EDITABLE",        "Cette facture a été émise : elle ne peut plus être modifiée ni supprimée"),
            Map.entry("INVOICE_INVALID_TRANSITION",  "Cette action n'est pas possible pour une facture {status}"),
            Map.entry("INVOICE_NOT_ISSUABLE",        "La facture ne peut pas être émise : {reason}"),
            Map.entry("INVOICE_PAYMENT_DATE_INVALID", "Date de paiement invalide : {reason}"),
            Map.entry("INVOICE_PHONE_REQUIRED",      "Indiquez le numéro WhatsApp du client"),
            Map.entry("INVOICE_PHONE_INVALID",       "Le numéro WhatsApp du client n'est pas valide"),
            Map.entry("INVOICE_LINK_INVALID",        "Ce lien de facture n'est pas valide"),
            Map.entry("INVOICE_LINK_RATE_LIMITED",   "Trop de demandes pour ce lien, réessayez plus tard"),
            Map.entry("INVOICE_LINK_EXPIRED",        "Ce lien de facture a expiré : demandez un nouvel envoi à votre commerçant"),
            Map.entry("CREDIT_NOTE_NOT_FOUND",       "Avoir introuvable"),
            Map.entry("CREDIT_NOTE_INVALID",         "L'avoir n'est pas valide : {reason}"),
            Map.entry("WHATSAPP_UNAVAILABLE",        "L'envoi WhatsApp n'est pas disponible pour le moment"),
            Map.entry("COMPANY_LOGO_INVALID",        "Format de logo non pris en charge (PNG ou JPEG)"),
            Map.entry("COMPANY_LOGO_TOO_LARGE",      "Le logo est trop lourd (200 Ko maximum)"),
            Map.entry("COMPANY_LOGO_TOO_HEAVY",      "Logo trop lourd après traitement : choisissez une image plus simple"),
            Map.entry("COMPANY_LOGO_NOT_FOUND",      "Aucun logo n'a été ajouté"),
            Map.entry("NOTIFICATION_NOT_FOUND",      "Notification introuvable"),
            Map.entry("OPTIMISTIC_LOCK",              "Cette donnée a été modifiée par une autre opération, veuillez réessayer"),
            Map.entry("INVALID_AMOUNT", "Montant invalide"),
            Map.entry("NEGATIVE_MONETARY_VALUE", "Un montant ne peut pas être négatif"),
            Map.entry("EMAIL_ALREADY_EXISTS", "Cette adresse e-mail est déjà utilisée"),
            Map.entry("INVALID_PHONE_NUMBER", "Numéro de téléphone invalide"),
            Map.entry("INVALID_PASSWORD", "Mot de passe invalide"),
            Map.entry("TENANT_ALREADY_EXISTS", "Cet espace de travail existe déjà"),
            Map.entry("TENANT_PROVISION_FAILED", "La création de votre espace de travail a échoué, réessayez dans un instant"),
            Map.entry("MEMBERSHIP_ALREADY_EXISTS", "Cet utilisateur fait déjà partie de cette entreprise"),
            Map.entry("MEMBERSHIP_NOT_FOUND", "Vous n'avez pas accès à cette entreprise"),
            Map.entry("ONBOARDING_ALREADY_COMPLETED", "La configuration initiale est déjà terminée"),
            Map.entry("SECTOR_TEMPLATE_NOT_FOUND", "Secteur d'activité inconnu"),
            Map.entry("PREFERENCES_NOT_FOUND", "Préférences introuvables"),
            Map.entry("CATEGORY_NOT_FOUND", "Catégorie introuvable"),
            Map.entry("INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE", "Ce produit ne fait pas partie de cet inventaire"),
            Map.entry("TRANSFER_NOT_FOUND", "Transfert introuvable"),
            Map.entry("TRANSFER_INVALID_STATUS", "Ce transfert n'est plus en cours"),
            Map.entry("TRANSFER_IN_TRANSIT", "Un transfert de ce produit est en cours : réceptionnez-le avant de désactiver le suivi du stock"),
            Map.entry("REPORT_NOT_FOUND", "Rapport introuvable"),
            Map.entry("REPORT_ALREADY_SENT", "Ce rapport a déjà été envoyé"),
            Map.entry("SYNC_CONFLICT", "Cette donnée a été modifiée ailleurs, synchronisez puis réessayez"),
            Map.entry("OFFLINE_LIMIT_EXCEEDED", "Trop d'opérations hors ligne : connectez-vous pour synchroniser"),
            Map.entry("SYNC_OPERATION_REJECTED", "Opération refusée par la synchronisation"),
            Map.entry("SYNC_DUPLICATE_OPERATION", "Cette opération a déjà été synchronisée"),
            Map.entry("SYNC_UNKNOWN_OPERATION_TYPE", "Opération inconnue : mettez à jour l'application"),
            Map.entry("ACCOUNT_SUSPENDED", "Compte suspendu : consultation seule"),
            Map.entry("PLAN_PROFILE_NOT_FOUND", "Offre introuvable"),
            Map.entry("NOT_FOUND", "Ressource introuvable")
    );

    /** The French catalogue text of a code ({@code null}: unknown code). */
    public static String of(String domainCode) {
        return domainCode == null ? null : FR_MESSAGES.get(domainCode);
    }

    /** The French catalogue text of a code, or {@code fallback} for an unknown one. */
    public static String of(String domainCode, String fallback) {
        String fr = of(domainCode);
        return fr != null ? fr : fallback;
    }

    /**
     * The {@code error} text of a domain exception: the explicit French {@code userMessage} set at the throw site
     * ({@code DomainException.userFacing}, opt-in, Story 16.14), else the French catalogue text with its {@code {key}}
     * placeholders filled from the details (v1s-18-3); for a code without catalogue text, {@code fallback}. The
     * exception's own (technical, possibly English) message is never shown for a catalogued code.
     */
    public static String userMessage(String domainCode, String userMessage, String fallback,
                                     Map<String, Object> details) {
        if (userMessage != null && !userMessage.isBlank()) {
            return userMessage;
        }
        String fr = of(domainCode);
        return fr != null ? withDetails(fr, details) : fallback;
    }

    /** Fills {@code {key}} placeholders of a French message from the exception details (v1s-18-3). */
    static String withDetails(String message, Map<String, Object> details) {
        if (message == null || details == null || details.isEmpty() || message.indexOf('{') < 0) return message;
        String result = message;
        for (Map.Entry<String, Object> e : details.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return result;
    }
}
