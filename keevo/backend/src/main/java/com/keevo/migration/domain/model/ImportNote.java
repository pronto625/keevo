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
        OWNER_SOCIAL_SIGN_IN("Propriétaire connecté par Google ou Apple dans Bref : reliaison du compte social à faire "
                + "(story 24.7), connexion par code en attendant"),
        LOGO_NOT_FETCHED("Logo non rapatrié (téléchargement désactivé ou adresse non autorisée)"),
        LOGO_FAILED("Logo non rapatrié (image indisponible ou refusée)");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
