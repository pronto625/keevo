package com.keevo.migration.domain.model;

/**
 * Story 24.2 — something the import did not take as is, without blocking (aggregated per kind in the batch; the row
 * concerned carries the same label in {@code migration_records.note} when it was written).
 */
public record ImportNote(String code, String label, int count) {

    /** Every note of 24.2. */
    public enum Kind {
        SHOP_DELETED("Boutique supprimée dans Bref, importée inactive"),
        SHOP_INACTIVE("Boutique désactivée dans Bref, importée inactive"),
        SHOP_PHONE_DROPPED("Téléphone de boutique invalide, non repris"),
        MEMBER_DELETED("Membre supprimé dans Bref, non importé"),
        MEMBER_INACTIVE("Membre désactivé dans Bref, importé désactivé"),
        MEMBER_NO_PASSWORD("Membre sans mot de passe : il devra en définir un"),
        OWNER_SOCIAL_SIGN_IN("Propriétaire connecté par un fournisseur externe dans Bref sans compte Google ou Apple "
                + "exploitable : connexion par code"),
        // Story 24.7 — identity bridge
        OWNER_LINKED("Propriétaire déjà inscrit dans Keevo : entreprise ajoutée à son compte existant"),
        MEMBER_LINKED("Membre déjà inscrit dans Keevo : rattaché à son compte existant, il se connecte avec ses "
                + "identifiants Keevo"),
        MEMBER_INVITED("Membre déjà inscrit dans Keevo : invitation à accepter à l'ouverture de l'entreprise"),
        MEMBER_INACTIVE_NOT_INVITED("Membre désactivé dans Bref et déjà inscrit dans Keevo : non invité"),
        MEMBER_EMAIL_DROPPED("E-mail de membre déjà utilisé par un autre compte Keevo, non repris"),
        SOCIAL_NOT_LINKED("Compte Google ou Apple de Bref non relié : le compte Keevo en a déjà un autre"),
        LOGO_NOT_FETCHED("Logo non rapatrié (téléchargement désactivé ou adresse non autorisée)"),
        LOGO_FAILED("Logo non rapatrié (image indisponible ou refusée)"),
        // Story 24.3 — catalogue and clients
        PRODUCT_MERGED_BY_KEY("Produit présent dans plusieurs boutiques : un seul produit importé"),
        PRODUCT_MERGED_IN_BREF("Produit fusionné dans Bref, importé archivé"),
        PRODUCT_MERGE_BROKEN("Produit fusionné dans Bref vers un produit introuvable : importé archivé, sans "
                + "redirection"),
        PRODUCT_RENAMED("Nom de produit déjà pris : numéro ajouté au nom"),
        PRODUCT_NAME_SHORTENED("Nom de produit trop long, raccourci à 200 caractères"),
        PRODUCT_NAME_MISSING("Produit sans nom : nommé « Produit importé »"),
        PRODUCT_PRICE_ROUNDED("Prix de produit avec centimes, arrondi au franc"),
        PRODUCT_REFERENCE_DROPPED("Référence de produit trop longue, non reprise"),
        CLIENT_NAME_MISSING("Client sans nom : son téléphone sert de nom"),
        CLIENT_FIELD_SHORTENED("Information de client trop longue, raccourcie"),
        CLIENT_FIELD_DROPPED("Information de client invalide (e-mail, date de naissance ou type), non reprise"),
        CLIENT_CONSENT_AUTHOR_UNKNOWN("Accord marketing déclaré par un compte inconnu : auteur non repris");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
