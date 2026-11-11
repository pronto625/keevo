package com.keevo.migration.domain.model;

import java.util.UUID;

/**
 * Story 24.2 — a reason that blocks the import of an owner before any write, to resolve by hand (listed in the batch).
 * The source row is named by its table and id (handles for the operator, never shown as such to a merchant).
 *
 * @param code  stable code ({@link Kind} name)
 * @param label French label (the kind's label, optionally completed by the rule's own message)
 */
public record BlockedCase(String code, String label, String sourceTable, UUID sourceId) {

    public static BlockedCase of(Kind kind, String sourceTable, UUID sourceId) {
        return new BlockedCase(kind.name(), kind.label(), sourceTable, sourceId);
    }

    public static BlockedCase of(Kind kind, String detail, String sourceTable, UUID sourceId) {
        return new BlockedCase(kind.name(), detail == null || detail.isBlank() ? kind.label()
                : kind.label() + " : " + detail, sourceTable, sourceId);
    }

    /** Every blocking case of 24.2 and 24.3 (24.7 adds the identity bridge cases). */
    public enum Kind {
        OWNER_DELETED("Compte supprimé dans Bref"),
        OWNER_INACTIVE("Compte désactivé dans Bref"),
        OWNER_NO_SHOP("Aucune boutique à importer"),
        NO_IDENTIFIER("Ni téléphone, ni e-mail, ni identifiant de connexion"),
        PHONE_INVALID("Numéro de téléphone invalide"),
        EMAIL_INVALID("Adresse e-mail trop longue"),
        USERNAME_INVALID("Identifiant de connexion invalide (lettres, chiffres, point, tiret ou souligné, 50 au plus)"),
        NAME_TOO_LONG("Prénom ou nom trop long (100 caractères au plus)"),
        PASSWORD_UNSUPPORTED("Mot de passe enregistré dans un format non reconnu"),
        ROLE_UNKNOWN("Rôle Bref inconnu"),
        MEMBER_SHOP_UNKNOWN("Membre rattaché à une boutique absente de ce compte Bref"),
        DUPLICATE_IDENTIFIER("Téléphone, e-mail ou identifiant en double dans ce compte Bref"),
        ACCOUNT_EXISTS("Un compte Keevo existe déjà avec ce téléphone, cet e-mail ou cet identifiant "
                + "(rapprochement des comptes : story 24.7)"),
        ACCOUNT_ID_TAKEN("Un compte Keevo non issu de cet import porte déjà le même identifiant interne"),
        SHOP_INVALID("Boutique invalide"),
        TENANT_GONE("L'entreprise importée précédemment n'existe plus (retour arrière à terminer)"),
        // Story 24.3
        PRODUCT_INVALID("Produit invalide"),
        CLIENT_INVALID("Client invalide");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
